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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.billing.AiQuota
import com.spellkeyboard.core.clipboard.ClipboardHistory
import com.spellkeyboard.core.editor.CorrectionEvent
import com.spellkeyboard.core.editor.Editor
import com.spellkeyboard.core.editor.TypingSession
import com.spellkeyboard.core.hangul.Hangul
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

    /** 복사해 둔 글 목록. 안드로이드 클립보드는 마지막 하나만 들고 있다. */
    private val clipboardHistory by lazy { ClipboardHistory(Prefs.clipboardStore(this)) }
    private var keyboard: KeyboardView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var corrector: GeminiCorrector? = null
    private var correctorSettings: Pair<String, String>? = null

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

    /** 모델 목록을 이미 받아 뒀는가. 키보드가 뜰 때마다 다시 받을 이유는 없다. */
    @Volatile
    private var aiWarmed = false

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
        session.onEvent = ::showEvent
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
            runCatching { Spacer(SpacingDictionary.open(target)) }
                .onSuccess {
                    session.engine.spacer = it
                    session.engine.speller = Speller(it)
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
        session.reset()
        fieldCorrectable = isCorrectableField(info)
        session.correctionEnabled = Prefs.autoCorrectEnabled(this) && fieldCorrectable
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.showStatus(
            if (session.correctionEnabled) {
                getString(R.string.status_idle)
            } else {
                getString(R.string.status_disabled)
            }
        )
        // 비밀번호 입력란에서는 AI 교정도 내놓지 않는다. 그 글이 서버로 나가면 안 된다.
        // 다만 자동 교정 스위치와는 묶지 않는다 — 그건 실시간 교정만 끄는 스위치다.
        val aiOn = Prefs.aiAvailable(this) && fieldCorrectable
        keyboard?.setAiAvailable(aiOn)
        if (aiOn) warmUpAi()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentInputConnection?.finishComposingText()
        session.reset()
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
        if (!session.isComposing()) return

        // 우리가 조합을 갱신한 직후에는 커서가 조합 영역 끝에 있다. 그렇지 않다면
        // 사용자가 직접 커서를 옮겼다는 뜻이라 조합을 끊는다.
        if (newSelStart != newSelEnd || newSelEnd != candidatesEnd) {
            currentInputConnection?.finishComposingText()
            session.reset()
        }
    }

    // --- 키 입력 -------------------------------------------------------------

    override fun onChar(c: Char) {
        val editor = editor() ?: return
        keyboard?.clearShift()

        if (keyboard?.currentMode() == KeyboardMode.KOREAN && Hangul.isJamo(c)) {
            session.pressJamo(editor, c)
        } else {
            session.pressText(editor, c)
        }
    }

    /**
     * 길게 눌러 나온 대체 글자. 방금 넣은 글자를 이것으로 갈아 끼운다.
     *
     * 누르는 순간 원래 글자가 이미 들어갔으므로 **반드시 그걸 걷어내고** 넣어야 한다.
     * 그냥 넣으면 '1' 을 길게 눌렀을 때 '1!' 이 된다.
     */
    override fun onLongPressChar(c: Char) {
        val editor = editor() ?: return
        if (keyboard?.currentMode() == KeyboardMode.KOREAN && Hangul.isJamo(c)) {
            session.replaceLastJamo(editor, c)
            return
        }
        if (!session.pressBackspace(editor)) {
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
        when (action) {
            KeyAction.SHIFT -> keyboard?.toggleShift()

            KeyAction.BACKSPACE -> if (!session.pressBackspace(editor)) {
                // 조합 중인 글자도 없고 되돌릴 교정도 없으면 평범한 삭제다.
                editor.deleteBefore(1)
                session.notifyDeleted(1)
            }

            KeyAction.SPACE -> session.pressSpace(editor)

            KeyAction.ENTER -> {
                session.pressEnter(editor)
                if (!sendDefaultEditorAction(true)) session.pressText(editor, '\n')
            }

            KeyAction.LANGUAGE -> switchMode(editor, KeyboardMode.ENGLISH)
            KeyAction.SYMBOLS -> switchMode(editor, KeyboardMode.SYMBOLS)
        }
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
     * 입력란의 글 전체를 Gemini API 로 교정한다.
     *
     * 실시간 경로가 아니다. 왕복이 수백 ms 라 타이핑을 따라갈 수 없어서, 사용자가
     * 버튼을 눌렀을 때만 돈다. 네트워크는 이 순간에만 쓴다.
     *
     * **화면이 "교정하는 중…" 에서 영영 멈춰 있으면 안 된다.** 요청마다 번호를 매겨
     * 두고, [AI_WATCHDOG_MS] 가 지나도 그 번호가 안 돌아오면 포기하고 버튼을 풀어
     * 준다 — 네트워크가 막힌 곳(방화벽, 기내 모드, 죽은 와이파이)에서도 사용자가
     * 손발이 묶이지 않게. 뒤늦게 응답이 와도 번호가 안 맞으면 조용히 버린다.
     */
    override fun onAiCorrect() {
        // 아래 세 가지는 예전에 조용히 return 했다. 그러면 눌러도 **아무 일도 안 일어나고**,
        // 사용자에게는 "AI 가 작동 안 함" 으로만 보인다. 원인을 가릴 수가 없다.
        if (aiBusy) {
            keyboard?.showStatus(getString(R.string.ai_busy))
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            keyboard?.showStatus(getString(R.string.ai_no_connection))
            return
        }
        val apiKey = Prefs.apiKey(this)
        if (apiKey.isEmpty()) {
            keyboard?.showStatus(getString(R.string.ai_no_key))
            return
        }

        // 되읽기가 안 되는 앱에서는 우리가 써 넣은 사본으로 대신한다. 온디바이스 교정이
        // 쓰는 것과 같은 폴백이다 — 이게 없으면 그런 앱에서 AI 만 영영 안 된다.
        val editor = ConnectionEditor(connection)
        val before = session.readBeforeCursor(editor, AI_CONTEXT_CHARS)
        val after = connection.getTextAfterCursor(AI_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val original = before + after
        if (original.isBlank()) {
            keyboard?.showStatus(getString(R.string.ai_empty))
            return
        }

        // 하루치를 다 썼는지는 보내기 전에 본다. 다 쓰고 나서 막으면 요금만 나간다.
        val quota = Prefs.quota(this)
        if (!quota.canUse()) {
            keyboard?.showStatus(getString(R.string.ai_quota_spent, AiQuota.FREE_DAILY_LIMIT))
            return
        }

        aiBusy = true
        keyboard?.showStatus(getString(R.string.ai_running))
        val model = Prefs.model(this)
        val requestId = aiRequestId.incrementAndGet()

        mainHandler.postDelayed({
            if (aiBusy && aiRequestId.get() == requestId) {
                aiBusy = false
                keyboard?.showStatus(getString(R.string.ai_timeout))
            }
        }, AI_WATCHDOG_MS)

        Thread {
            val engine = runCatching { corrector(apiKey, model) }
            val result = engine.mapCatching { it.correct(original).getOrThrow() }
            // 이름이 낡아 거절당하면 엔진이 스스로 갈아 끼운다. 그 결과를 받아 둔다.
            val used = engine.getOrNull()?.activeModel
            mainHandler.post {
                // 이미 시간 초과로 포기했거나 그 사이 새 요청이 시작됐으면 버린다.
                if (aiRequestId.get() != requestId) return@post
                aiBusy = false
                if (used != null && used != model) rememberModel(used)
                result
                    .onSuccess {
                        // 성공했을 때만 깎는다. 실패한 요청까지 세면 사용자는 아무것도
                        // 못 받고 하루치만 잃는다.
                        quota.consume()
                        applyAiResult(before, after, it)
                    }
                    .onFailure {
                        // 영어 원문을 그대로 실으면 두 줄에서 잘려 정작 원인이 안 보인다.
                        keyboard?.showStatus(
                            getString(R.string.ai_failed, GeminiCorrector.explain(it.message))
                        )
                    }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun applyAiResult(before: String, after: String, corrected: String) {
        if (corrected == before + after) {
            keyboard?.showStatus(getString(R.string.ai_unchanged) + remainingSuffix())
            return
        }
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        connection.deleteSurroundingText(before.length, after.length)
        connection.commitText(corrected, 1)
        connection.endBatchEdit()
        // 글을 통째로 갈아 끼웠으니 조합 상태와 사본을 버린다.
        session.reset()
        keyboard?.showStatus(getString(R.string.ai_done) + remainingSuffix())
    }

    /**
     * " · 오늘 3회 남음" 처럼 뒤에 붙일 문구.
     *
     * 무제한이면 빈 문자열이다 — 구독자에게 횟수를 들이밀 이유가 없다.
     */
    private fun remainingSuffix(): String {
        val remaining = Prefs.quota(this).status().remaining ?: return ""
        return getString(R.string.ai_remaining_suffix, remaining)
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
        val apiKey = Prefs.apiKey(this)
        val model = Prefs.model(this)
        Thread {
            runCatching { corrector(apiKey, model).prefetchModels() }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 엔진이 스스로 찾아낸 모델 이름을 설정에도 남긴다.
     *
     * 이러지 않으면 켤 때마다 낡은 이름으로 한 번 헛걸음한 뒤에야 통한다.
     */
    @Synchronized
    private fun rememberModel(model: String) {
        Prefs.setModel(this, model)
        correctorSettings = correctorSettings?.copy(second = model)
    }

    /** 설정이 그대로면 만들어 둔 것을 다시 쓴다. */
    @Synchronized
    private fun corrector(apiKey: String, model: String): GeminiCorrector {
        val settings = apiKey to model
        val cached = corrector
        if (cached != null && correctorSettings == settings) return cached
        return GeminiCorrector(apiKey, model).also {
            corrector = it
            correctorSettings = settings
        }
    }

    private fun switchMode(editor: Editor, target: KeyboardMode) {
        session.commitPending(editor)
        val current = keyboard?.currentMode() ?: KeyboardMode.KOREAN
        keyboard?.setMode(if (current == target) KeyboardMode.KOREAN else target)
    }

    private fun editor(): Editor? = currentInputConnection?.let(::ConnectionEditor)

    // --- 상태 표시 -----------------------------------------------------------

    /**
     * 교정 결과를 상단 줄에 보여준다.
     *
     * 고친 경우뿐 아니라 **고치지 않은 경우에도** 무엇을 검사했는지 보여준다.
     * 아무 표시가 없으면 "교정이 안 된다" 와 "고칠 것이 없었다" 를 구분할 수 없다.
     */
    private fun showEvent(event: CorrectionEvent) {
        val text = when (event) {
            is CorrectionEvent.Applied ->
                getString(R.string.status_corrected, event.from.trim(), event.to.trim())

            is CorrectionEvent.Unchanged -> {
                val examined = event.examined.trim()
                if (examined.isEmpty()) {
                    getString(R.string.status_unreadable)
                } else {
                    getString(R.string.status_checked, examined)
                }
            }

            CorrectionEvent.Reverted -> getString(R.string.status_reverted)
            CorrectionEvent.Disabled -> getString(R.string.status_disabled)
        }
        keyboard?.showStatus(text)
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
        const val DICTIONARY_DIR = "spacing"

        /** AI 교정에 실어 보낼 커서 앞뒤 최대 글자 수. */
        const val AI_CONTEXT_CHARS = 2000

        /**
         * "교정하는 중…" 을 이보다 오래 붙잡지 않는다.
         *
         * 모델을 옮겨 가며 최대 세 번 오간다. 통신부 타임아웃(연결 5 초 + 읽기 15 초)
         * 을 다 더하면 이보다 길어질 수 있는데, 그때는 화면만 풀어 주고 늦게 온 응답은
         * 버린다. 사용자가 영영 묶여 있지 않게 하는 것이 이 값의 목적이다.
         */
        const val AI_WATCHDOG_MS = 35_000L

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
