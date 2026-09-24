package com.spellkeyboard.ko

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.clipboard.ClipboardHistory
import com.spellkeyboard.core.editor.Editor
import com.spellkeyboard.core.editor.TypingSession
import com.spellkeyboard.core.hangul.CheonjiinAutomata
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.hangul.HangulAutomata
import com.spellkeyboard.core.editor.TranslateBuffer
import com.spellkeyboard.core.editor.TranslationOutput
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.translate.ChatText
import com.spellkeyboard.core.translate.Phrasebook
import com.spellkeyboard.core.translate.SentenceSplitter
import com.spellkeyboard.core.translate.TranslationMemory
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실시간 교정 키보드.
 *
 * 입력 처리 로직은 전부 [TypingSession] 에 있다. 이 클래스가 하는 일은 두 가지뿐이다 —
 * 안드로이드의 `InputConnection` 을 core 의 [Editor] 로 감싸는 것, 그리고 교정 결과를
 * 화면에 보여주는 것. 로직을 안드로이드 밖에 둬야 단위 테스트로 검증할 수 있다.
 *
 * 입력한 글자는 어디로도 나가지 않는다. 교정은 전부 기기 안에서 끝나고, 비밀번호나
 * 이메일 같은 입력란에서는 교정 자체를 끈다.
 */
class SpellKeyboardService : InputMethodService(), KeyboardView.Listener {

    private val session = TypingSession()
    private val qwertyAutomata = HangulAutomata()
    private val cheonjiinAutomata = CheonjiinAutomata()

    /**
     * 천지인 연타 판단. 같은 키를 [MULTI_TAP_MS] 안에 다시 누르면 "연타" 로 오토마타에
     * 알린다(ㄱ→ㅋ). 시간은 여기서 재고, 오토마타는 결과만 받는다.
     */
    private var lastTapKey: Char? = null
    private var lastTapAt = 0L

    /** 부호 키 연타 위치와 그 키의 부호 목록. */
    private var punctuationIndex = -1
    private var punctuationOptions = ""

    /** 복사해 둔 글 목록. 안드로이드 클립보드는 마지막 하나만 들고 있다. */
    private val clipboardHistory by lazy { ClipboardHistory(Prefs.clipboardStore(this)) }
    private var keyboard: KeyboardView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var corrector: GeminiCorrector? = null
    private var correctorSettings: CorrectorSettings? = null

    /** 구매 토큰이 바뀌면 헤더가 달라지므로 교정기를 새로 만든다. */
    private data class CorrectorSettings(val purchaseToken: String)

    /** AI 교정은 한 번에 하나만. 연타로 요청이 겹치면 글이 꼬인다. */
    @Volatile
    private var aiBusy = false

    /** 요청마다 매기는 번호. 시간 초과로 포기한 뒤 뒤늦게 온 응답을 걸러낸다. */
    private val aiRequestId = AtomicInteger(0)

    /**
     * 이 입력란이 교정해도 되는 곳인가 (비밀번호·이메일이 아닌가).
     *
     * 자동 교정 스위치와는 별개다. 스위치를 껐다고 AI 버튼까지 사라지면 안 된다 —
     * 실시간 교정은 싫지만 다 쓰고 한 번에 손보고 싶은 사람이 있다.
     */
    private var fieldCorrectable = true

    /**
     * 이 입력란의 글을 **기기 밖으로 내보내도 되는가.**
     *
     * [fieldCorrectable] 보다 엄격하다. 기기 안에서 도는 교정은 아무것도 남기지 않지만,
     * 서버로 보내는 것은 글이 남의 컴퓨터를 거친다는 뜻이다. 그 둘은 같은 문지방을
     * 쓸 수 없다.
     */
    private var fieldSendable = true

    /** 우리가 마지막으로 편집한 시각. 우리가 일으킨 커서 알림을 가려내는 데 쓴다. */
    @Volatile
    private var lastEditAt = 0L

    // --- 번역 모드 -------------------------------------------------------------
    //
    // 켜져 있으면 모든 키 입력이 앱 입력란 대신 [translateBuffer] 로 간다 ([editor] 가 그걸
    // 돌려준다). 한글 조합과 자동 교정은 평소와 똑같이 그 위에서 돌고, 내용이 바뀔 때마다
    // 잠깐 뒤 온디바이스 번역기가 돌아 앱 입력란의 번역문을 갈아 끼운다.

    private var translating = false
    private val translateBuffer = TranslateBuffer().also { it.onChange = { onTranslateSourceChanged() } }
    private val translateOutput = TranslationOutput()
    private var translator: OnDeviceTranslator? = null

    /** 번역 요청 번호. 늦게 도착한 옛 결과를 버리는 데 쓴다. */
    private var translateSerial = 0

    /**
     * 번역기에 넘기기 전에 문장으로 자른다. **여러 문장을 한 번에 넘기면 번역기가 가운데를
     * 통째로 삼킨다** — 실기기에서 네 문장이 두 문장으로 줄고 물음표만 엉뚱한 데 붙었다.
     * 형태소 사전은 늦게 올라오므로 그때그때 물어본다(없으면 문장 부호로만 자른다).
     */
    private val sentenceSplitter =
        SentenceSplitter { session.engine.spacer?.endsWithFinalEnding(it) == true }

    /** 문장별 번역 결과. 끝에만 글자가 붙으므로 앞 문장은 다시 번역할 일이 없다. */
    private val translationMemory = TranslationMemory()

    /** 마지막으로 번역한 원문. 지금 내용과 같으면 입력란의 번역문이 최신이다. */
    private var translatedSource = ""

    /** 엔터를 눌렀는데 번역이 아직 안 끝났다. 끝나면 보낸다. */
    private var pendingEnter = false

    /** 서버 번역이 도는 중. 두 번 겹쳐 돌지 않게 한다. */
    private var polishing = false
    private val translateNow = Runnable { translateBufferNow() }

    /** `InputConnection` 을 core 의 [Editor] 로 감싼 어댑터. */
    private class ConnectionEditor(private val ic: InputConnection) : Editor {

        override fun beginBatch() {
            ic.beginBatchEdit()
        }

        override fun endBatch() {
            ic.endBatchEdit()
        }

        override fun commitText(text: String) {
            ic.commitText(text, 1)
        }

        override fun setComposingText(text: String) {
            ic.setComposingText(text, 1)
        }

        override fun finishComposing() {
            ic.finishComposingText()
        }

        override fun deleteBefore(count: Int) {
            ic.deleteSurroundingText(count, 0)
        }

        override fun textBeforeCursor(maxChars: Int): String =
            ic.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
    }

    override fun onCreate() {
        super.onCreate()
        loadSpacingDictionary()
    }

    /**
     * 띄어쓰기 사전을 백그라운드에서 연다.
     *
     * 처음 한 번은 50MB 를 풀어야 해서 몇 초 걸린다. 그동안에도 규칙 교정은
     * 그대로 동작하고, 준비되면 조용히 끼워 넣는다. 사전을 못 열어도
     * 키보드는 계속 쓸 수 있어야 하므로 실패는 삼킨다.
     *
     * `Speller` 도 여기서 같이 끼워 넣는다 — 같은 사전(`Spacer`)의 분석 비용을
     * 갖다 쓰는 것뿐이라 새로 여는 게 없다. **이걸 빼먹으면 '잇는대요' 처럼
     * 사전에 없는 오타는 타이핑 중에 하나도 안 잡힌다** — 실시간 교정에서 가장
     * 눈에 띄는 실수라 특히 조심한다.
     */
    /** 올라와 있으면 긴 덩어리 띄어쓰기를 맡는다. 못 올리면 null 이고 기존 엔진이 한다. */
    private var kiwi: KiwiSpacer? = null

    /**
     * 서비스가 이미 죽었는가.
     *
     * Kiwi 를 올리는 데 실기기에서 수 초가 걸린다. 그 사이에 키보드를 닫으면 [onDestroy] 가
     * 먼저 끝나고, 그 뒤에 올라온 모델을 **아무도 닫아 주지 않는다** — 네이티브 쪽이 잡은
     * 100MB 가 프로세스가 죽을 때까지 남는다. 올리고 나서 이 깃발을 보고 바로 닫는다.
     */
    @Volatile
    private var destroyed = false

    private fun loadSpacingDictionary() {
        val target = File(filesDir, DICTIONARY_DIR)
        Thread {
            val spacer = runCatching { Spacer(SpacingDictionary.open(target)) }
                .onSuccess {
                    session.engine.spacer = it
                    session.engine.speller = Speller(it)
                }
                .getOrNull()
            // 언어모델은 형태소 사전 뒤에 연다. 이게 올라오면 어절 하나씩 보던 교정 대신
            // 창 전체를 앞뒤 문맥으로 푸는 교정이 된다. 못 열면 위의 둘로 계속 간다.
            val lm = runCatching { LanguageModel.open(target) }
                .onSuccess { session.engine.context = ContextCorrector(it, spacer) }
                .getOrNull()
            // Kiwi 는 맨 끝에 올린다. 105MB 를 꺼내고 읽느라 수 초가 걸려서, 앞의 둘이
            // 먼저 준비돼야 그 동안에도 교정이 된다. 32비트 폰에서는 안 올라오고,
            // 그때는 형태소 사전이 그대로 이 일을 한다.
            //
            // **부르는 것 자체를 감싼다.** KiwiSpacer 안에도 runCatching 이 있지만, 그건
            // 이미 클래스가 올라온 뒤의 이야기다. 네이티브 라이브러리가 없는 기기에서는
            // `KiwiSpacer` 를 **처음 건드리는 순간** NoClassDefFoundError 가 나서
            // 그 안으로 들어가지도 못한다. arm64 가 아닌 폰이 정확히 그 경우다.
            // 오타 교정기는 "말뭉치가 아는 낱말은 안 건드린다" 를 마지막 문지방으로 쓴다.
            // 언어모델을 못 열었으면 그 문지방이 없는 셈이라 **아무것도 안 고치는 쪽**으로 둔다 —
            // 문지방 없이 돌리면 멀쩡한 낱말을 다른 멀쩡한 낱말로 바꾸는 일이 두 배가 된다.
            val known: (String) -> Boolean = if (lm == null) { { true } } else { { lm.lnCount(it) != null } }
            val opened = runCatching { KiwiSpacer.open(this, known) }.getOrNull()
            if (opened != null) {
                if (destroyed) {
                    runCatching { opened.close() }
                } else {
                    kiwi = opened
                    session.engine.longSpacer = opened
                    session.engine.typoFixer = opened
                }
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    override fun onCreateInputView(): View =
        KeyboardView(this).also {
            it.listener = this
            keyboard = it
        }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (translating) exitTranslate()
        session.reset()
        lastTapKey = null
        punctuationIndex = -1
        syncAutomata()
        fieldCorrectable = isCorrectableField(info)
        fieldSendable = isSendableField(info)
        session.correctionEnabled = Prefs.autoCorrectEnabled(this) && fieldCorrectable
        keyboard?.setAutoCorrectOn(Prefs.autoCorrectEnabled(this))
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 설정에서 자판을 바꾸고 **이미 열려 있던** 입력란으로 돌아오면 onStartInput 은
        // 안 불린다. 자판만 바뀌고 조립기는 옛것이 남아, 쿼티 키가 천지인 조립기로 들어가
        // 낱자로 흩어졌다 — 실기기에서 "쿼티 먹통" 으로 나타났다. 여기서도 맞춘다.
        syncAutomata()
        // 설정에서 테마나 배경을 바꾸고 돌아왔을 수 있다. 바뀐 게 없으면 싸게 끝난다.
        keyboard?.applyAppearance()
        // 비밀번호 입력란에서는 교정을 아예 내놓지 않는다. 기기 안에서 도는 일이라도
        // 비밀번호를 고쳐 주는 것은 도움이 아니라 사고다.
        // 다만 자동 교정 스위치와는 묶지 않는다 — 그건 실시간 교정만 끄는 스위치다.
        keyboard?.setCorrectAllAvailable(fieldCorrectable)
        // **번역도 같이 감춘다.** 번역을 길게 누르면 입력란의 글이 통째로 서버로 간다.
        // 예전에는 AI 동그라미만 감춰서, 비밀번호 칸에서도 번역은 그대로 눌렸다.
        keyboard?.setTranslateAvailable(fieldSendable)
        // 예전에는 여기서 서버를 한 번 두드려 모델 목록을 미리 받았다. 뺐다 —
        // 아래 [corrector] 주석에 이유를 적어 뒀다.
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (translating) exitTranslate()
        currentInputConnection?.finishComposingText()
        session.reset()
    }

    override fun onDestroy() {
        destroyed = true
        translator?.close()
        translator = null
        // 네이티브 쪽이 잡고 있는 것을 놓아 준다. 키보드가 죽어도 모델이 남으면
        // 다음에 올릴 때 메모리가 모자란다.
        session.engine.longSpacer = null
        session.engine.typoFixer = null
        runCatching { kiwi?.close() }
        kiwi = null
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        // **번역 모드에서는 이 알림이 전부 우리 것이다.**
        //
        // 사용자는 앱 입력란이 아니라 번역 입력줄에 쓰고 있고, 앱 입력란이 바뀌는 것은
        // 우리가 번역문을 갈아 끼울 때뿐이다. 그런데 번역은 타이핑이 멎고 250ms 뒤에 도는
        // 반면 아래의 자기 편집 창은 200ms 라, 번역할 때마다 창 밖이라 판정돼 조합이 끊겼다.
        // 실기기에서 '어' 를 쓰고 'ㄸ' 를 누르면 '어' 가 지워지는 것으로 나타났다.
        if (translating) return

        if (!session.isComposing()) return

        // **우리가 방금 고친 직후의 알림은 우리 것이다.**
        //
        // 이 알림은 비동기라 빠르게 치면 순서가 밀려 도착한다. 그걸 "사용자가 커서를
        // 옮겼다" 로 잘못 읽으면 조합이 한복판에서 끊겨, 치던 글자가 깨지거나 다음
        // 자모가 새 글자로 시작한다 — 실기기에서 "키가 씹힌다" 로 보인다.
        if (android.os.SystemClock.uptimeMillis() - lastEditAt < SELF_EDIT_WINDOW_MS) return

        // 시간으로만 가리면 빠른 타자에서 새는 게 있다 — 알림이 200ms 넘게 밀리면 그때는
        // 우리 것인데도 끊는다. 그래서 **내용**으로 가린다: 조합 중인 글자가 커서 바로
        // 앞에 그대로 있으면 커서는 우리 자리에 있는 것이다. 시간 창은 빠른 길일 뿐이다.
        if (newSelStart == newSelEnd) {
            val composing = session.composingText()
            val before = currentInputConnection?.getTextBeforeCursor(composing.length, 0)?.toString()
            if (before != null && composing.isNotEmpty() && before == composing) return
            if (newSelEnd == candidatesEnd) return
        }
        // 글자를 골라 잡았거나 커서가 조합 영역을 떠났다. 사용자가 손댄 것이니 조합을 끊는다.
        currentInputConnection?.finishComposingText()
        session.reset()
    }

    // --- 키 입력 -------------------------------------------------------------

    override fun onChar(c: Char) {
        val editor = editor() ?: return
        keyboard?.clearShift()
        lastTapKey = null
        punctuationIndex = -1

        if (keyboard?.currentMode() == KeyboardMode.KOREAN && Hangul.isJamo(c)) {
            // 자모가 onChar 로 온다는 것은 쿼티 자판이라는 뜻이다. 조립기가 다른 것이면
            // 지금 갈아 끼운다 — 천지인 키가 자기 조립기를 끼우는 것과 대칭.
            ensureAutomata(qwertyAutomata)
            session.pressJamo(editor, c)
        } else {
            session.pressText(editor, c)
        }
    }

    private fun isCheonjiin(): Boolean = Prefs.layoutType(this) == LayoutType.CHEONJIIN

    /** 설정의 자판에 맞는 조립기를 끼운다. 이미 맞으면 아무것도 안 한다. */
    private fun syncAutomata() {
        ensureAutomata(if (isCheonjiin()) cheonjiinAutomata else qwertyAutomata)
    }

    private fun ensureAutomata(wanted: com.spellkeyboard.core.hangul.JamoAutomata) {
        if (session.automata !== wanted) session.automata = wanted
    }

    /**
     * 천지인 낱자 키. 자판 판별은 뷰가 이미 했다 — 여기로 왔다는 것 자체가 천지인이다.
     * 만일을 위해 오토마타가 천지인이 아니면 지금 갈아 끼운다.
     */
    override fun onCheonjiinKey(key: Char) {
        val editor = editor() ?: return
        keyboard?.clearShift()
        punctuationIndex = -1
        ensureAutomata(cheonjiinAutomata)

        val now = android.os.SystemClock.uptimeMillis()
        val repeat = key == lastTapKey && now - lastTapAt <= MULTI_TAP_MS
        session.pressJamo(editor, key, repeat)
        lastTapKey = key
        lastTapAt = now
    }

    /**
     * 천지인 `.,?!` 키. 처음엔 마침표, [MULTI_TAP_MS] 안에 또 누르면 방금 넣은 부호를
     * 다음 것으로 바꾼다. 부호는 조합 대상이 아니라 확정된 글자라, 지우고 다시 넣는다.
     */
    override fun onPunctuationCycle(options: String) {
        val editor = editor() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val cycling = punctuationIndex >= 0 && punctuationOptions == options && now - lastTapAt <= MULTI_TAP_MS
        punctuationOptions = options
        lastTapKey = null
        lastTapAt = now
        if (cycling) {
            editor.deleteBefore(1)
            session.notifyDeleted(1)
            punctuationIndex = (punctuationIndex + 1) % options.length
        } else {
            punctuationIndex = 0
        }
        session.pressText(editor, options[punctuationIndex])
    }

    /**
     * 길게 눌러 나온 대체 글자. 방금 넣은 글자를 이것으로 갈아 끼운다.
     *
     * 누르는 순간 원래 글자가 이미 들어갔으므로 **반드시 그걸 걷어내고** 넣어야 한다.
     * 그냥 넣으면 '1' 을 길게 눌렀을 때 '1!' 이 된다.
     */
    override fun onLongPressChar(c: Char) {
        val editor = editor() ?: return
        lastTapKey = null
        if (keyboard?.currentMode() == KeyboardMode.KOREAN && Hangul.isJamo(c)) {
            session.replaceLastJamo(editor, c)
            return
        }
        // 천지인 키의 숫자, 숫자 줄의 기호. 누르는 순간 들어간 것을 걷어내고 넣는다.
        if (!session.undoLastJamo(editor)) {
            editor.deleteBefore(1)
            session.notifyDeleted(1)
        }
        session.pressText(editor, c)
    }

    /** 뒤로 가기로 클립보드를 닫는다. 열어 놓고 나갈 길이 없으면 갇힌 느낌이 든다. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && keyboard?.closePanels() == true) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onAction(action: KeyAction) {
        val editor = editor() ?: return
        lastTapKey = null
        punctuationIndex = -1
        when (action) {
            KeyAction.SHIFT -> keyboard?.toggleShift()

            KeyAction.BACKSPACE -> if (!session.pressBackspace(editor)) {
                // 조합 중인 글자도 없고 되돌릴 교정도 없으면 평범한 삭제다.
                editor.deleteBefore(1)
                session.notifyDeleted(1)
            }

            // 천지인의 스페이스는 "다음 글자" 를 겸한다. 한 키에 자음이 둘셋 실려서
            // "안녕" 의 ㄴ→ㄴ 처럼 같은 키를 연달아 눌러야 하는 글자는 연타(ㄴ→ㄹ)와
            // 구별하려고 기다려야 하는데, 그 기다림을 스페이스가 끊어 준다. 조합 중이면
            // 글자만 끊고, 끊긴 뒤에 또 누르면 그때 띄어쓰기가 들어간다 — 삼성과 같다.
            KeyAction.SPACE ->
                if (isCheonjiin() && session.composingText().isNotEmpty()) session.commitPending(editor)
                else session.pressSpace(editor)

            KeyAction.ENTER -> if (translating) {
                pressEnterWhileTranslating(editor)
            } else {
                session.pressEnter(editor)
                if (!sendDefaultEditorAction(true)) session.pressText(editor, '\n')
            }

            KeyAction.LANGUAGE -> switchMode(editor, KeyboardMode.ENGLISH)
            KeyAction.SYMBOLS -> switchMode(editor, KeyboardMode.SYMBOLS)
            KeyAction.SYMBOL_PAGE -> keyboard?.flipSymbolPage()
            KeyAction.NUMPAD -> switchMode(editor, KeyboardMode.NUMPAD)
            KeyAction.KOREAN -> {
                session.commitPending(editor)
                keyboard?.setMode(KeyboardMode.KOREAN)
            }
        }
    }

    /** 이모티콘. 조합을 끝내고 통째로 넣는다. */
    override fun onEmoji(text: String) {
        val editor = editor() ?: return
        lastTapKey = null
        punctuationIndex = -1
        session.pressString(editor, text)
    }

    // --- 클립보드 -------------------------------------------------------------

    /**
     * 클립보드를 열 때 지금 복사돼 있는 것을 담는다.
     *
     * 안드로이드 10 부터 백그라운드에서는 클립보드를 못 읽는다. 입력기가 화면에 떠
     * 있는 지금이 읽을 수 있는 때다 — 그래서 감시하지 않고 열 때마다 한 번 본다.
     */
    override fun onClipboardOpened() {
        captureClipboard()
        keyboard?.showClipboardItems(clipboardHistory.items())
    }

    override fun onClipboardPaste(text: String) {
        val editor = editor() ?: return
        session.commitPending(editor)
        editor.commitText(text)
        session.reset()
    }

    override fun onClipboardDelete(text: String) {
        clipboardHistory.remove(text)
        keyboard?.showClipboardItems(clipboardHistory.items())
    }

    /** 자판의 설정 버튼. 자판·테마를 바꾸러 온 것이라 첫 화면이 아니라 키보드 맞춤설정으로 간다. */
    override fun onOpenSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * 자판 위 '교정' 버튼. 설정 화면의 스위치와 같은 값을 뒤집는다.
     *
     * 지금 입력란에 바로 먹는다 — 다음 입력란부터 적용되면 "껐는데 왜 고치냐" 가 된다.
     * 교정이 안 되는 입력란(비밀번호 등)에서는 값만 저장되고 동작은 그대로 꺼져 있다.
     */
    /**
     * 스페이스를 꾹 누른 채 좌우로 밀면 커서가 움직인다.
     *
     * 방향키 이벤트로 옮긴다 — 어느 입력란이든 받아 주고, 줄 바꿈이 있는 글에서도 글자
     * 단위로 자연스럽게 움직인다. 조합 중인 글자는 먼저 확정한다. 커서가 딴 데로 가면
     * 조합 상태는 의미가 없다.
     */
    override fun onMoveCursor(delta: Int) {
        val editor = editor() ?: return
        session.commitPending(editor)
        session.reset()
        val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(key) }
    }

    override fun onToggleAutoCorrect() {
        val enabled = !Prefs.autoCorrectEnabled(this)
        Prefs.setAutoCorrectEnabled(this, enabled)
        session.correctionEnabled = enabled && fieldCorrectable
        keyboard?.setAutoCorrectOn(enabled)
    }

    override fun onCursorModeChanged(active: Boolean) {
        // 자판 위에 문구를 띄우지 않는다. 스페이스를 잡고 있는 손가락이 이미 알고 있다.
    }

    /**
     * 지금 복사돼 있는 글을 기록에 담는다.
     *
     * **비밀번호는 담지 않는다.** 안드로이드 13 부터 복사한 쪽이 "민감함" 이라고
     * 표시해 주는데, 키보드는 사용자가 치는 모든 것을 보는 앱이라 이런 표시는
     * 반드시 지켜야 한다. 표시가 없는 옛 기기에서는 알 방법이 없으니, 사용자가
     * 목록에서 지울 수 있게 해 두는 것이 최선이다.
     */
    private fun captureClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = manager.primaryClip ?: return
        if (isSensitive(clip.description)) return

        val text = (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(this)?.toString() }
            .firstOrNull { it.isNotBlank() } ?: return
        clipboardHistory.add(text)
    }

    private fun isSensitive(description: ClipDescription?): Boolean {
        if (description == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
    }

    // --- AI 교정 -------------------------------------------------------------

    /**
     * 입력란의 글 전체를 중계 서버의 AI 로 교정한다.
     *
     * 실시간 경로가 아니다. 왕복이 수백 ms 라 타이핑을 따라갈 수 없어서, 사용자가
     * 버튼을 눌렀을 때만 돈다. 네트워크는 이 순간에만 쓴다.
     *
     * **화면이 "교정하는 중…" 에서 영영 멈춰 있으면 안 된다.** 요청마다 번호를 매겨
     * 두고, [AI_WATCHDOG_MS] 가 지나도 그 번호가 안 돌아오면 포기하고 버튼을 풀어
     * 준다 — 네트워크가 막힌 곳(방화벽, 기내 모드, 죽은 와이파이)에서도 사용자가
     * 손발이 묶이지 않게. 뒤늦게 응답이 와도 번호가 안 맞으면 조용히 버린다.
     */
    // --- 번역 ----------------------------------------------------------------

    override fun onToggleTranslate() {
        if (translating) exitTranslate() else enterTranslate()
    }

    override fun onCycleTranslateTarget() {
        val next = Prefs.translateTarget(this).next()
        Prefs.setTranslateTarget(this, next)
        translationMemory.clear()
        if (!translating) return
        keyboard?.setTranslateMode(true, next.label(this))
        translatedSource = ""
        prepareTranslator(next)
    }

    private fun enterTranslate() {
        val connection = currentInputConnection
        if (connection == null) {
            notify(getString(R.string.ai_no_connection))
            return
        }
        // 앱 입력란에 조합 중이던 글자는 거기서 끝낸다. 입력줄은 빈 채로 시작한다.
        session.commitPending(ConnectionEditor(connection))
        session.reset()
        translateBuffer.clear()
        translateOutput.detach()
        translatedSource = ""
        pendingEnter = false
        translating = true
        val target = Prefs.translateTarget(this)
        keyboard?.setTranslateMode(true, target.label(this))
        keyboard?.setTranslateSource("", getString(R.string.translate_hint, target.label(this)))
        prepareTranslator(target)
    }

    /** 번역문은 입력란에 남긴 채 입력줄만 닫는다. */
    private fun exitTranslate() {
        translating = false
        pendingEnter = false
        polishing = false
        keyboard?.setTranslateBusy(false)
        mainHandler.removeCallbacks(translateNow)
        translateSerial++
        // 조합을 먼저 끝낸다. 안 그러면 다음에 입력줄을 열었을 때 옛 조합 자리가 남아
        // 첫 글자가 앞 글자를 덮어쓴다.
        translateBuffer.finishComposing()
        translateBuffer.clear()
        translateOutput.detach()
        translatedSource = ""
        session.reset()
        keyboard?.setTranslateMode(false, Prefs.translateTarget(this).label(this))
    }

    /** 언어팩이 있으면 바로, 없으면 받고 나서 입력줄의 안내를 바꾼다. */
    private fun prepareTranslator(target: TargetLanguage) {
        val translator = this.translator ?: OnDeviceTranslator().also { this.translator = it }
        val label = target.label(this)
        if (!translator.isReady(target)) {
            keyboard?.setTranslateSource(translateBuffer.text, getString(R.string.translate_downloading, label))
        }
        translator.ensureModel(
            target,
            onReady = {
                if (!translating) return@ensureModel
                keyboard?.setTranslateSource(translateBuffer.text, getString(R.string.translate_hint, label))
                onTranslateSourceChanged()
            },
            onFailed = {
                if (!translating) return@ensureModel
                notify(getString(R.string.translate_download_failed, label))
                keyboard?.setTranslateSource(translateBuffer.text, getString(R.string.translate_download_failed, label))
            }
        )
    }

    /** 입력줄 내용이 바뀌었다. 화면을 갱신하고 잠깐 뒤 번역한다 — 글자마다 돌리면 낭비다. */
    private fun onTranslateSourceChanged() {
        if (!translating) return
        val target = Prefs.translateTarget(this)
        keyboard?.setTranslateSource(translateBuffer.text, getString(R.string.translate_hint, target.label(this)))
        mainHandler.removeCallbacks(translateNow)
        mainHandler.postDelayed(translateNow, TRANSLATE_DEBOUNCE_MS)
    }

    private fun translateBufferNow() {
        if (!translating) return
        val source = translateBuffer.text.trim()
        val connection = currentInputConnection ?: return
        if (source.isEmpty()) {
            translateOutput.replace(ConnectionEditor(connection), "")
            translatedSource = ""
            if (pendingEnter) finishEnter()
            return
        }
        val target = Prefs.translateTarget(this)
        val translator = translator ?: return
        if (!translator.isReady(target)) return

        // 문장마다 따로 번역해서 이어 붙인다. 이미 번역한 문장은 기억해 둔 것을 쓴다 —
        // 빠르기도 하고, 앞쪽 번역문이 이유 없이 흔들리지도 않는다.
        val sentences = sentenceSplitter.split(source)
        val serial = ++translateSerial
        val code = target.tag

        // 모델을 부르기 전에 두 가지를 먼저 한다.
        // 1) 채팅 한국어를 다듬는다 — 마침표를 붙이고 'ㅋㅋ' 와 늘여 쓴 글자를 떼어 낸다.
        //    번역기는 문장다운 문장으로 배웠고, 그렇지 않은 입력에서 눈에 띄게 나빠진다.
        // 2) 관용구 표를 본다 — 자주 쓰는 채팅 문장은 사람이 옮겨 둔 것이 모델보다 낫다.
        //    '저는 좋아요' 를 모델은 "I like it" 으로 옮겼다.
        val pending = ArrayList<String>()
        for (sentence in translationMemory.missing(sentences)) {
            val normalized = ChatText.normalize(sentence)
            if (normalized.core.isEmpty()) {
                // 'ㅋㅋ' 만 있는 문장. 옮길 말이 없으니 감탄만 남긴다.
                val only = normalized.emphasis?.let { Phrasebook.emphasis(it, code) }.orEmpty()
                translationMemory.remember(sentence, only)
                continue
            }
            val fromBook = Phrasebook.lookup(normalized.core, code)
            if (fromBook != null) {
                translationMemory.remember(sentence, Phrasebook.decorate(fromBook, normalized, code))
                continue
            }
            pending += sentence
        }
        if (pending.isEmpty()) {
            renderTranslation(sentences, source, serial)
            return
        }

        var remaining = pending.size
        for (sentence in pending) {
            val normalized = ChatText.normalize(sentence)
            translator.translate(
                normalized.core + normalized.ending, target,
                onResult = { result ->
                    // 늦게 온 결과라도 기억은 해 둔다. 다음 요청이 그걸 쓴다.
                    translationMemory.remember(sentence, Phrasebook.decorate(result, normalized, code))
                    if (--remaining == 0) renderTranslation(sentences, source, serial)
                },
                onFailed = {
                    // 못 옮긴 문장은 원문을 그대로 둔다. 그 자리가 비면 글이 사라진 것처럼 보인다.
                    translationMemory.remember(sentence, sentence)
                    if (--remaining == 0) {
                        notify(getString(R.string.translate_failed))
                        renderTranslation(sentences, source, serial)
                    }
                }
            )
        }
    }

    /** 문장별 번역을 이어 붙여 앱 입력란에 넣는다. 그사이 새 요청이 나갔으면 버린다. */
    private fun renderTranslation(sentences: List<String>, source: String, serial: Int) {
        if (!translating || serial != translateSerial) return
        val connection = currentInputConnection ?: return
        // 이 편집으로 올 커서 알림도 우리 것이다.
        lastEditAt = android.os.SystemClock.uptimeMillis()
        translateOutput.replace(ConnectionEditor(connection), translationMemory.assemble(sentences))
        translatedSource = source
        if (pendingEnter) finishEnter()
    }

    /**
     * 번역 모드의 엔터. 마지막 어절을 교정하고, 입력란의 번역문이 최신이면 바로 보낸다.
     * 아직 번역이 도는 중이면 끝나기를 기다렸다 보낸다 — 옛 번역을 보내면 안 된다.
     */
    private fun pressEnterWhileTranslating(editor: Editor) {
        session.pressEnter(editor)
        val source = translateBuffer.text.trim()
        // 구독자는 보내기 직전에 서버가 문장 전체를 다시 옮긴다. 기기 번역은 그때까지
        // 보여 주는 미리보기이고, 실제로 나가는 글은 이쪽이다.
        if (source.isNotEmpty() && canPolish()) {
            polishThenSend(source)
            return
        }
        sendWhenTranslated()
    }

    /** 기기 번역이 최신이면 바로, 아직이면 끝나기를 기다렸다 보낸다. */
    private fun sendWhenTranslated() {
        if (translateBuffer.text.trim() == translatedSource) {
            finishEnter()
        } else {
            pendingEnter = true
            mainHandler.removeCallbacks(translateNow)
            translateBufferNow()
        }
    }

    /** 서버 번역을 쓸 수 있는가. 구독자 전용이고 서버가 있어야 한다. */
    private fun canPolish(): Boolean =
        !polishing && Prefs.aiAvailable() && isSubscriber() && currentInputConnection != null

    /** 서버가 마지막으로 알려 준 요금제. 폰의 숫자는 못 믿지만 기능을 여는 데는 이걸로 충분하다 —
     *  진짜 판단은 서버가 한다. 구독자가 아닌데 보내도 서버가 막는다. */
    private fun isSubscriber(): Boolean = Prefs.lastQuota(this)?.plan == SUBSCRIBER_PLAN

    /**
     * 입력줄의 글을 서버 번역으로 다시 옮겨 앱에 넣고 보낸다.
     *
     * 실패하면 기기 번역문을 그대로 보낸다 — 못 보내는 것보다 낫다.
     */
    private fun polishThenSend(source: String) {
        polishing = true
        keyboard?.setTranslateBusy(true)
        val requestId = aiRequestId.incrementAndGet()
        val target = Prefs.translateTarget(this)

        mainHandler.postDelayed({
            if (polishing && aiRequestId.get() == requestId) {
                polishing = false
                keyboard?.setTranslateBusy(false)
                notify(getString(R.string.ai_timeout))
                if (translating) sendWhenTranslated()
            }
        }, AI_WATCHDOG_MS)

        Thread {
            val engine = runCatching { corrector() }
            val result = engine.mapCatching { it.translate(source, target.tag).getOrThrow() }
            val quota = engine.getOrNull()?.lastQuota
            mainHandler.post {
                if (aiRequestId.get() != requestId) return@post
                polishing = false
                keyboard?.setTranslateBusy(false)
                quota?.let { Prefs.rememberQuota(this, it) }
                if (!translating) return@post
                result
                    .onSuccess { polished ->
                        currentInputConnection?.let {
                            translateOutput.replace(ConnectionEditor(it), polished)
                        }
                        finishEnter()
                    }
                    .onFailure {
                        notify(getString(R.string.translate_ai_failed, GeminiCorrector.explain(it.message)))
                        sendWhenTranslated()
                    }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 번역 동그라미를 길게 눌렀다. 입력란에 이미 쓴 글을 통째로 옮긴다.
     *
     * 입력줄은 한 줄짜리라 긴 글을 거기 칠 수는 없다. 붙여넣었거나 이미 써 둔 글은
     * 이 길로 옮긴다 — AI 교정 버튼이 하는 일과 같은 모양이다.
     */
    override fun onTranslateField() {
        if (!fieldSendable) {
            notify(getString(R.string.sensitive_field_blocked))
            return
        }
        if (polishing || aiBusy) {
            notify(getString(R.string.ai_busy))
            return
        }
        if (!isSubscriber()) {
            notify(getString(R.string.translate_premium_only))
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            notify(getString(R.string.ai_no_connection))
            return
        }
        if (!Prefs.aiAvailable()) {
            notify(getString(R.string.ai_no_key))
            return
        }
        // 입력줄이 열려 있으면 닫는다. 여기서 옮기는 것은 입력란의 글이다.
        if (translating) exitTranslate()

        val editor = ConnectionEditor(connection)
        val before = session.readBeforeCursor(editor, AI_BEFORE_CHARS)
        val after = connection.getTextAfterCursor(AI_AFTER_CHARS, 0)?.toString().orEmpty()
        val original = before + after
        if (original.isBlank()) {
            notify(getString(R.string.ai_empty))
            return
        }

        polishing = true
        keyboard?.setTranslateBusy(true)
        val requestId = aiRequestId.incrementAndGet()
        val target = Prefs.translateTarget(this)

        mainHandler.postDelayed({
            if (polishing && aiRequestId.get() == requestId) {
                polishing = false
                keyboard?.setTranslateBusy(false)
                notify(getString(R.string.ai_timeout))
            }
        }, AI_WATCHDOG_MS)

        Thread {
            val toSend = spacedForServer(original)
            val engine = runCatching { corrector() }
            val result = engine.mapCatching { it.translate(toSend, target.tag).getOrThrow() }
            val quota = engine.getOrNull()?.lastQuota
            mainHandler.post {
                if (aiRequestId.get() != requestId) return@post
                polishing = false
                keyboard?.setTranslateBusy(false)
                quota?.let { Prefs.rememberQuota(this, it) }
                result
                    .onSuccess { applyAiResult(before, after, it) }
                    .onFailure {
                        notify(getString(R.string.translate_ai_failed, GeminiCorrector.explain(it.message)))
                    }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** 입력줄을 닫고 번역문을 앱에 보낸다(엔터 동작). 보낼 수 없는 입력란이면 줄바꿈. */
    private fun finishEnter() {
        pendingEnter = false
        exitTranslate()
        if (!sendDefaultEditorAction(true)) {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    /**
     * 글 전체를 **기기 안에서** 고친다. 서버도, 한도도, 통신도 없다.
     *
     * 자판 위 '전체' 를 짧게 누르면 이것이다. 요금제와 상관없이 누구나, 몇 번이든 쓴다.
     * 2,600자에 8ms 라 사실상 기다림이 없지만, 아주 긴 글에서 화면이 멎지 않게
     * 다른 스레드에서 돌린다.
     */
    override fun onCorrectAll() {
        if (translating) {
            notify(getString(R.string.translate_blocks_ai))
            return
        }
        if (aiBusy || polishing) {
            notify(getString(R.string.ai_busy))
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            notify(getString(R.string.ai_no_connection))
            return
        }

        // 서버로 보낼 때와 달리 글자 수를 아낄 이유가 없다. 입력란에 있는 만큼 다 읽는다.
        val editor = ConnectionEditor(connection)
        val before = session.readBeforeCursor(editor, FULL_BEFORE_CHARS)
        val after = connection.getTextAfterCursor(FULL_AFTER_CHARS, 0)?.toString().orEmpty()
        val original = before + after
        if (original.isBlank()) {
            notify(getString(R.string.ai_empty))
            return
        }

        aiBusy = true
        keyboard?.setAiBusy(true)
        val requestId = aiRequestId.incrementAndGet()

        Thread {
            val result = runCatching { session.engine.correctAll(original) }
            mainHandler.post {
                if (aiRequestId.get() != requestId) return@post
                aiBusy = false
                keyboard?.setAiBusy(false)
                result
                    .onSuccess { fixed ->
                        if (!fixed.changed) {
                            // 이건 AI 가 아니라 기기 안 교정이다. "AI 가 보기에" 라고 하면
                            // 서버까지 갔다 온 줄 알고 AI 를 다시 안 눌러 본다.
                            notify(getString(R.string.correct_all_unchanged))
                        } else {
                            applyAiResult(before, after, fixed.text)
                            notify(resources.getQuantityString(R.plurals.correct_all_done, fixed.corrections.size, fixed.corrections.size))
                        }
                    }
                    .onFailure { notify(getString(R.string.correct_all_failed)) }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun onAiCorrect() {
        // 단추를 감추는 것만 믿지 않는다. 감추기는 화면의 일이고, 이 줄은 글이 기기 밖으로
        // 나가는 마지막 문이다. 문은 문대로 잠가 둔다.
        if (!fieldSendable) {
            notify(getString(R.string.sensitive_field_blocked))
            return
        }
        if (translating) {
            notify(getString(R.string.translate_blocks_ai))
            return
        }
        // 아래 세 가지는 예전에 조용히 return 했다. 그러면 눌러도 **아무 일도 안 일어나고**,
        // 사용자에게는 "AI 가 작동 안 함" 으로만 보인다. 원인을 가릴 수가 없다.
        if (aiBusy) {
            notify(getString(R.string.ai_busy))
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            notify(getString(R.string.ai_no_connection))
            return
        }
        if (!Prefs.aiAvailable()) {
            notify(getString(R.string.ai_no_key))
            return
        }

        // 되읽기가 안 되는 앱에서는 우리가 써 넣은 사본으로 대신한다. 온디바이스 교정이
        // 쓰는 것과 같은 폴백이다 — 이게 없으면 그런 앱에서 AI 만 영영 안 된다.
        val editor = ConnectionEditor(connection)
        val before = session.readBeforeCursor(editor, AI_BEFORE_CHARS)
        val after = connection.getTextAfterCursor(AI_AFTER_CHARS, 0)?.toString().orEmpty()
        val original = before + after
        if (original.isBlank()) {
            notify(getString(R.string.ai_empty))
            return
        }

        // 무료 한도는 서버가 센다. 폰에 있는 숫자는 누구나 고칠 수 있어서 여기서는
        // 판단하지 않는다 — 넘겼으면 서버가 402 로 답하고, 그 문구를 그대로 보여준다.

        aiBusy = true
        keyboard?.setAiBusy(true)
        val requestId = aiRequestId.incrementAndGet()

        mainHandler.postDelayed({
            if (aiBusy && aiRequestId.get() == requestId) {
                aiBusy = false
                keyboard?.setAiBusy(false)
                notify(getString(R.string.ai_timeout))
            }
        }, AI_WATCHDOG_MS)

        Thread {
            val toSend = spacedForServer(original)
            val engine = runCatching { corrector() }
            val result = engine.mapCatching { it.correct(toSend).getOrThrow() }
            // 서버가 헤더로 알려 준 남은 횟수. 한도 초과(402)에도 실려 오니 실패해도 받는다.
            val quota = engine.getOrNull()?.lastQuota
            mainHandler.post {
                // 이미 시간 초과로 포기했거나 그 사이 새 요청이 시작됐으면 버린다.
                if (aiRequestId.get() != requestId) return@post
                aiBusy = false
                keyboard?.setAiBusy(false)
                quota?.let { Prefs.rememberQuota(this, it) }
                result
                    .onSuccess { applyAiResult(before, after, it) }
                    .onFailure {
                        // 영어 원문을 그대로 실으면 두 줄에서 잘려 정작 원인이 안 보인다.
                        notify(getString(R.string.ai_failed, GeminiCorrector.explain(it.message)))
                    }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 서버로 보내기 전에 **붙여 쓴 덩어리만 우리가 먼저 푼다.**
     *
     * ## 왜
     *
     * 같은 시험지로 재 봤다. 띄어쓰기를 통째로 지운 글에서
     *
     *     우리 (폰 안, 공짜)        문장 통째 78.1%
     *     gemini-3.5-flash-lite    문장 통째 70.0%, 게다가 30% 를 엉뚱하게 바꾼다
     *
     * 우리가 더 잘하는 일을 돈 주고 시키면서 결과까지 나빠지고 있었다. 맞춤법은 저쪽이
     * 훨씬 낫지만(92~96%) 띄어쓰기는 아니다. 각자 잘하는 것만 시킨다.
     *
     * ## 풀 것이 있을 때만 판다
     *
     * 먼저 푸는 데도 시간이 든다 — 2,000자면 폰에서 몇 초다. 그래서 [hasGluedRun] 으로
     * 훑어보고 붙여 쓴 데가 없으면 **아무 일도 안 한다.** 대부분의 글이 그렇다.
     *
     * ## 입력란은 안 건드린다
     *
     * 여기서 만든 글은 **서버에 보낼 사본**이다. 화면의 글은 그대로 둔다 — 사용자가
     * 누른 것은 AI 교정이지 전체교정이 아니고, 결과는 어차피 `applyAiResult` 가 통째로
     * 갈아 끼운다. 그래서 지울 길이(before/after)도 원래 것을 그대로 쓴다.
     *
     * 실패하면 원문을 그대로 보낸다. 먼저 푸는 것은 도움이지 관문이 아니다.
     */
    private fun spacedForServer(original: String): String {
        if (!session.engine.hasGluedRun(original)) return original
        return runCatching { session.engine.correctAll(original).text }.getOrDefault(original)
    }

    private fun applyAiResult(before: String, after: String, corrected: String) {
        if (corrected == before + after) {
            // 버튼을 눌렀는데 아무 일도 안 일어나면 고장으로 보인다. 이것만은 알린다.
            notify(getString(R.string.ai_unchanged))
            return
        }
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        connection.deleteSurroundingText(before.length, after.length)
        connection.commitText(corrected, 1)
        connection.endBatchEdit()
        // 글을 통째로 갈아 끼웠으니 조합 상태와 사본을 버린다.
        session.reset()
        // 글이 바뀐 것이 곧 결과다. 따로 알리지 않는다.
    }

    /**
     * 설정이 그대로면 만들어 둔 것을 다시 쓴다.
     *
     * 언제나 중계 서버로 간다. 모델 이름은 기본값에서 시작하고, 거절당하면 교정기가
     * 스스로 갈아탄다 — 그 결과는 이 객체 안에 남아 다음 요청부터 바로 쓰인다.
     *
     * ## 키보드가 뜰 때 서버를 미리 두드리지 않는다
     *
     * 예전에는 [GeminiCorrector.prefetchModels] 를 백그라운드로 불러 모델 목록을 받아
     * 뒀다. 두 가지가 겹쳐 뺐다.
     *
     * 1. **값어치가 없다.** 받아 온 이름은 요청 주소에 실려 나가는데, 중계 서버는 그걸
     *    읽지 않는다 — 어느 모델로 갈지는 서버가 정한다(`server/src/index.js`). 이미
     *    깔린 APK 들이 저마다 다른 이름을 들고 있어서 그렇게 만들었다.
     * 2. **무료 사용자까지 두드렸다.** 조건이 "서버 주소가 있나" 뿐이라 AI 를 평생 못 쓰는
     *    사람도 키보드가 새로 뜰 때마다 한 번씩 쳤다. DAU 10 만이면 하루 30 만 요청인데
     *    그중 쓸모 있는 것은 2 만뿐이고, Cloudflare 무료 등급은 하루 10 만이다.
     *    **쓸모없는 요청 때문에 돈 낸 사람이 막히는 그림**이라 그냥 끊었다.
     *
     * 모델 갈아타기 자체는 그대로 산다. 교정하다 거절당하면 [GeminiCorrector] 가 그때
     * 목록을 받아 옮긴다(`candidates()`). 그 한 왕복은 실제로 옮겨야 할 때만 든다.
     */
    @Synchronized
    private fun corrector(): GeminiCorrector {
        val settings = CorrectorSettings(Prefs.purchaseToken(this))
        val cached = corrector
        if (cached != null && correctorSettings == settings) return cached
        val fresh = GeminiCorrector(
            "",
            GeminiCorrector.DEFAULT_MODEL,
            GeminiCorrector.HttpTransport(Prefs.serverHeaders(this)),
            baseUrl = Prefs.serverBase()
        )
        return fresh.also {
            corrector = it
            correctorSettings = settings
        }
    }

    /**
     * 사용자에게 한 줄 알린다. 자판 위에는 글을 띄우지 않기로 했으므로, 눌렀는데 안 된
     * 이유처럼 꼭 알아야 할 것만 토스트로 잠깐 보여준다.
     */
    private fun notify(text: String) {
        mainHandler.post {
            val view = keyboard
            if (view != null) view.flash(text)
            else android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchMode(editor: Editor, target: KeyboardMode) {
        session.commitPending(editor)
        val current = keyboard?.currentMode() ?: KeyboardMode.KOREAN
        keyboard?.setMode(if (current == target) KeyboardMode.KOREAN else target)
    }

    /**
     * 입력을 고칠 통로. 가져가는 순간을 "우리가 방금 건드렸다" 로 기록해 둔다.
     *
     * 모든 입력 경로가 이걸 거치므로 여기 한 군데만 찍으면 된다.
     */
    private fun editor(): Editor? {
        lastEditAt = android.os.SystemClock.uptimeMillis()
        if (translating) return translateBuffer
        return currentInputConnection?.let(::ConnectionEditor)
    }


    /**
     * 교정하면 안 되는 입력란인지 판별한다.
     *
     * 좁게 잡는다. 비밀번호·이메일·URL 처럼 사람이 쓴 문장이 아닌 것만 뺀다.
     * 검색창(FILTER)이나 NO_SUGGESTIONS 플래그까지 막았더니 인스타그램 검색처럼
     * 멀쩡한 입력란에서 교정이 통째로 꺼졌다. 그 플래그는 "추천 단어를 띄우지 말라"는
     * 뜻이지 "맞춤법을 고치지 말라"는 뜻이 아니고, 한국어 앱들이 습관적으로 켜 둔다.
     */
    /**
     * 이 입력란의 글을 서버로 보내도 되는가.
     *
     * 교정해도 되는 칸이어야 하고([isCorrectableField]), 그 위에 앱이 **"이 입력은
     * 학습하거나 저장하지 마라"** 고 표시하지 않아야 한다.
     *
     * 그 표시(`NO_PERSONALIZED_LEARNING`)를 실시간 교정까지 막는 데에는 쓰지 않는다.
     * 기기 안 교정은 아무것도 남기지 않으므로 그 표시가 말리는 일을 애초에 하지 않고,
     * 한국어 앱들이 이런 플래그를 습관적으로 켜 두어서 막으면 멀쩡한 칸에서 교정이
     * 통째로 꺼진다. 막아야 할 것은 **글이 남의 컴퓨터로 가는 것**이다.
     */
    private fun isSendableField(info: EditorInfo?): Boolean {
        if (!isCorrectableField(info)) return false
        // inputType 이 아니라 imeOptions 에 실려 온다. 상수는 컴파일 때 값으로 박히므로
        // 옛 안드로이드에서도 그냥 0 과 비교하는 셈이라 안전하다.
        val options = info?.imeOptions ?: return false
        return options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    }

    private fun isCorrectableField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return (type and InputType.TYPE_MASK_VARIATION) !in SENSITIVE_VARIATIONS
    }

    private companion object {
        /** 입력줄이 바뀐 뒤 번역까지 기다리는 시간. 타이핑 사이 간격보다 살짝 길게. */
        private const val TRANSLATE_DEBOUNCE_MS = 250L

        /** 서버가 알려 주는 구독자 표시. `x-plan` 헤더 값이다. */
        private const val SUBSCRIBER_PLAN = "subscriber"

        const val DICTIONARY_DIR = "spacing"

        /**
         * 편집 직후 이 시간 안에 온 커서 알림은 우리가 일으킨 것으로 본다.
         *
         * 사용자가 글자를 친 지 0.2 초 안에 커서를 직접 옮기는 일은 거의 없다.
         * 놓치더라도 다음 탭에서 잡히지만, 잘못 끊으면 글자가 깨진다.
         */
        const val SELF_EDIT_WINDOW_MS = 200L

        /**
         * AI 교정에 실어 보낼 커서 앞·뒤 최대 글자 수.
         *
         * 보내는 글자가 곧 요금이다 — 답도 그만큼 통째로 돌아오니 두 배로.
         *
         * 한때 6,000 자(4000+2000)까지 열었었다. 하루 한도가 10 만 자일 때는 꽉 채운
         * 교정이 16 번이라 말이 됐는데, 한도를 2 만 자로 내리면서 **같은 창이 하루 세
         * 번**이 돼 버렸다. 창과 한도는 같이 정해야 하는 값인데 따로 건드렸던 것이다.
         *
         * 제미나이 시절 값(1500+500)으로 되돌린다. 하루 2 만 자면 꽉 채운 교정 10 번이고,
         * 카톡 한 줄짜리는 최소 과금 50 자라 400 번이다. 되돌리려는 것이 그 시절 감이니
         * 창 크기도 그때 것이 맞다.
         */
        /**
         * 기기 안 전체교정이 읽는 길이. 서버로 보낼 때와 달리 **글자가 곧 요금이 아니라서**
         * 아낄 이유가 없다. 입력란에 있는 만큼 다 읽는다.
         */
        const val FULL_BEFORE_CHARS = 20000
        const val FULL_AFTER_CHARS = 20000

        const val AI_BEFORE_CHARS = 1500
        const val AI_AFTER_CHARS = 500

        /**
         * "교정하는 중…" 을 이보다 오래 붙잡지 않는다.
         *
         * 모델을 옮겨 가며 최대 세 번 오간다. 통신부 타임아웃(연결 5 초 + 읽기 15 초)
         * 을 다 더하면 이보다 길어질 수 있는데, 그때는 화면만 풀어 주고 늦게 온 응답은
         * 버린다. 사용자가 영영 묶여 있지 않게 하는 것이 이 값의 목적이다.
         *
         * 창을 6,000 자로 넓히면서 60 초까지 늘렸다가, 창을 2,000 자로 되돌리면서 같이
         * 줄인다. 다만 35 초보다는 넉넉히 둔다 — 짧아서 손해 보는 것은 "답이 오는 중에
         * 우리가 끊는 것" 이고, 길어서 손해 보는 것은 진짜 고장 났을 때 기다리는 시간뿐이다.
         */
        const val AI_WATCHDOG_MS = 45_000L

        /** 천지인 연타로 인정하는 간격. 이보다 뜸하면 같은 키라도 새 글자다. */
        const val MULTI_TAP_MS = 700L

        val SENSITIVE_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )
    }
}
