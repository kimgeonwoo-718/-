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

    /** 우리가 마지막으로 편집한 시각. 우리가 일으킨 커서 알림을 가려내는 데 쓴다. */
    @Volatile
    private var lastEditAt = 0L

    /** 모델 목록을 이미 받아 뒀는가. 키보드가 뜰 때마다 다시 받을 이유는 없다. */
    @Volatile
    private var aiWarmed = false

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
            runCatching { LanguageModel.open(target) }
                .onSuccess { session.engine.context = ContextCorrector(it, spacer) }
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
        // 비밀번호 입력란에서는 AI 교정도 내놓지 않는다. 그 글이 서버로 나가면 안 된다.
        // 다만 자동 교정 스위치와는 묶지 않는다 — 그건 실시간 교정만 끄는 스위치다.
        val aiOn = Prefs.aiAvailable() && fieldCorrectable
        keyboard?.setAiAvailable(aiOn)
        if (aiOn) warmUpAi()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (translating) exitTranslate()
        currentInputConnection?.finishComposingText()
        session.reset()
    }

    override fun onDestroy() {
        translator?.close()
        translator = null
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

    override fun onOpenSettings() {
        startActivity(
            Intent(this, SetupActivity::class.java)
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
        val missing = translationMemory.missing(sentences)
        if (missing.isEmpty()) {
            renderTranslation(sentences, source, serial)
            return
        }
        var remaining = missing.size
        for (sentence in missing) {
            translator.translate(
                sentence, target,
                onResult = { result ->
                    // 늦게 온 결과라도 기억은 해 둔다. 다음 요청이 그걸 쓴다.
                    translationMemory.remember(sentence, result)
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
        if (translateBuffer.text.trim() == translatedSource) {
            finishEnter()
        } else {
            pendingEnter = true
            mainHandler.removeCallbacks(translateNow)
            translateBufferNow()
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

    override fun onAiCorrect() {
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
            val engine = runCatching { corrector() }
            val result = engine.mapCatching { it.correct(original).getOrThrow() }
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
     * 모델 목록을 미리 받아 둔다.
     *
     * 붐비는 모델을 만났을 때 곧장 한가한 쪽으로 옮기려면 후보를 알고 있어야 하는데,
     * 그걸 버튼 누른 뒤에 받으면 왕복이 그대로 대기 시간이 된다. 키보드가 뜰 때
     * 미리 받아 두면 정작 누르는 순간에는 공짜다. 실패해도 그냥 넘어간다 —
     * 목록이 없으면 지금 모델로 그대로 시도할 뿐이다.
     */
    private fun warmUpAi() {
        if (aiWarmed) return
        aiWarmed = true
        Thread {
            runCatching { corrector().prefetchModels() }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 설정이 그대로면 만들어 둔 것을 다시 쓴다.
     *
     * 언제나 중계 서버로 간다. 모델 이름은 기본값에서 시작하고, 거절당하면 교정기가
     * 스스로 갈아탄다 — 그 결과는 이 객체 안에 남아 다음 요청부터 바로 쓰인다.
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
    private fun isCorrectableField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return (type and InputType.TYPE_MASK_VARIATION) !in SENSITIVE_VARIATIONS
    }

    private companion object {
        /** 입력줄이 바뀐 뒤 번역까지 기다리는 시간. 타이핑 사이 간격보다 살짝 길게. */
        private const val TRANSLATE_DEBOUNCE_MS = 250L

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
         * 보내는 글자가 곧 요금이다 — 답도 그만큼 통째로 돌아오니 두 배로. 앞뒤 2,000 자씩
         * 보내던 것을 줄였다. 키보드에서 고치는 글은 대개 메시지 한 통이고, 그보다 긴 글은
         * 커서 앞 부분만 고쳐진다.
         */
        const val AI_BEFORE_CHARS = 1500
        const val AI_AFTER_CHARS = 500

        /**
         * "교정하는 중…" 을 이보다 오래 붙잡지 않는다.
         *
         * 모델을 옮겨 가며 최대 세 번 오간다. 통신부 타임아웃(연결 5 초 + 읽기 15 초)
         * 을 다 더하면 이보다 길어질 수 있는데, 그때는 화면만 풀어 주고 늦게 온 응답은
         * 버린다. 사용자가 영영 묶여 있지 않게 하는 것이 이 값의 목적이다.
         */
        const val AI_WATCHDOG_MS = 35_000L

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
