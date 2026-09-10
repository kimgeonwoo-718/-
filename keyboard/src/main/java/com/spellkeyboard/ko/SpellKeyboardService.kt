package com.spellkeyboard.ko

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.spellkeyboard.core.editor.CorrectionEvent
import com.spellkeyboard.core.editor.Editor
import com.spellkeyboard.core.editor.TypingSession
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.io.File

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
    private var keyboard: KeyboardView? = null

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
     */
    private fun loadSpacingDictionary() {
        val target = File(filesDir, DICTIONARY_DIR)
        Thread {
            runCatching { Spacer(SpacingDictionary.open(target)) }
                .onSuccess { session.engine.spacer = it }
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
        session.correctionEnabled = Prefs.autoCorrectEnabled(this) && isCorrectableField(info)
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

    /** 길게 눌러 나온 대체 글자. 방금 넣은 자모를 이것으로 갈아 끼운다. */
    override fun onLongPressChar(c: Char) {
        val editor = editor() ?: return
        if (keyboard?.currentMode() == KeyboardMode.KOREAN && Hangul.isJamo(c)) {
            session.replaceLastJamo(editor, c)
        } else {
            session.pressText(editor, c)
        }
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
