package com.spellkeyboard.ko

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.hangul.HangulAutomata

/**
 * 실시간 교정 키보드.
 *
 * ## 교정 시점
 *
 * 글자마다 고치지 않는다. 한글은 조합 중인 음절이 계속 바뀌기 때문에(ㅇ→아→안→안ㄴ→안녀)
 * 조합이 끝나기 전에 손대면 입력이 깨진다. 그래서 **어절이 확정되는 순간**
 * (스페이스, 문장부호, 엔터)에 커서 앞 마지막 몇 어절을 교정한다.
 *
 * ## 되돌리기
 *
 * 교정 직후 백스페이스를 누르면 원래 입력으로 되돌린다. 자동 교정에서 이게 없으면
 * 고유명사나 신조어를 쓸 방법이 사라진다.
 *
 * ## 하지 않는 것
 *
 * 입력한 글자는 어디로도 나가지 않는다. 교정은 전부 기기 안에서 끝나고, 비밀번호나
 * 이메일 같은 입력란에서는 교정 자체를 끈다.
 */
class SpellKeyboardService : InputMethodService(), KeyboardView.Listener {

    private val automata = HangulAutomata()
    private val engine = CorrectionEngine()

    private var keyboard: KeyboardView? = null
    private var correctionAllowed = true
    private var undo: Undo? = null

    /** 방금 적용한 교정. 다음 백스페이스 한 번으로 되돌릴 수 있다. */
    private data class Undo(val applied: String, val original: String)

    override fun onCreateInputView(): View =
        KeyboardView(this).also {
            it.listener = this
            keyboard = it
        }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        automata.reset()
        undo = null
        correctionAllowed = Prefs.autoCorrectEnabled(this) && isCorrectableField(info)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (correctionAllowed) {
            keyboard?.showCorrection(null, null)
        } else {
            keyboard?.showCorrectionDisabled()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentInputConnection?.finishComposingText()
        automata.reset()
        undo = null
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
        if (automata.isEmpty()) return

        // 우리가 조합을 갱신한 직후에는 커서가 조합 영역 끝에 있다. 그렇지 않다면
        // 사용자가 직접 커서를 옮겼다는 뜻이라 조합을 끊는다.
        if (newSelStart != newSelEnd || newSelEnd != candidatesEnd) {
            currentInputConnection?.finishComposingText()
            automata.reset()
        }
    }

    // --- 키 입력 -------------------------------------------------------------

    override fun onChar(c: Char) {
        val ic = currentInputConnection ?: return
        keyboard?.clearShift()

        if (keyboard?.currentMode() == KeyboardMode.KOREAN && Hangul.isJamo(c)) {
            undo = null
            val output = automata.press(c)
            ic.beginBatchEdit()
            if (output.committed.isNotEmpty()) ic.commitText(output.committed, 1)
            ic.setComposingText(output.composing, 1)
            ic.endBatchEdit()
            return
        }

        commitPending(ic)
        if (c in WORD_TERMINATORS) {
            correctThenCommit(ic, c.toString())
        } else {
            undo = null
            ic.commitText(c.toString(), 1)
        }
    }

    override fun onAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            KeyAction.SHIFT -> keyboard?.toggleShift()
            KeyAction.BACKSPACE -> handleBackspace(ic)
            KeyAction.SPACE -> {
                commitPending(ic)
                correctThenCommit(ic, " ")
            }
            KeyAction.ENTER -> handleEnter(ic)
            KeyAction.LANGUAGE -> switchMode(ic, KeyboardMode.ENGLISH)
            KeyAction.SYMBOLS -> switchMode(ic, KeyboardMode.SYMBOLS)
        }
    }

    private fun handleBackspace(ic: InputConnection) {
        // 1) 조합 중인 글자가 있으면 한 단계만 되돌린다. (안녕 -> 안녀)
        val output = automata.backspace()
        if (output != null) {
            if (output.composing.isEmpty()) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                ic.setComposingText(output.composing, 1)
            }
            return
        }

        // 2) 방금 자동 교정이 일어났다면 그것부터 되돌린다.
        if (revertLastCorrection(ic)) return

        // 3) 평범한 삭제.
        ic.deleteSurroundingText(1, 0)
    }

    private fun handleEnter(ic: InputConnection) {
        commitPending(ic)
        correctThenCommit(ic, "")
        if (!sendDefaultEditorAction(true)) {
            ic.commitText("\n", 1)
        }
    }

    private fun switchMode(ic: InputConnection, target: KeyboardMode) {
        commitPending(ic)
        automata.reset()
        val current = keyboard?.currentMode() ?: KeyboardMode.KOREAN
        keyboard?.setMode(if (current == target) KeyboardMode.KOREAN else target)
    }

    // --- 교정 ---------------------------------------------------------------

    /** 조합 중인 글자를 확정한다. */
    private fun commitPending(ic: InputConnection) {
        val pending = automata.flush()
        if (pending.isNotEmpty()) ic.commitText(pending, 1) else ic.finishComposingText()
    }

    /**
     * 커서 앞 어절들을 교정한 뒤 [separator] 를 입력한다.
     *
     * 지우고 다시 쓰는 편집이라 [InputConnection.beginBatchEdit] 로 묶는다. 묶지 않으면
     * 편집기가 중간 상태를 화면에 그려서 글자가 깜빡인다.
     */
    private fun correctThenCommit(ic: InputConnection, separator: String) {
        if (!correctionAllowed) {
            undo = null
            if (separator.isNotEmpty()) ic.commitText(separator, 1)
            return
        }

        val before = ic.getTextBeforeCursor(LOOKBEHIND_CHARS, 0)?.toString().orEmpty()
        val tail = engine.correctTail(before)

        ic.beginBatchEdit()
        if (tail != null) {
            ic.deleteSurroundingText(tail.deleteBefore, 0)
            ic.commitText(tail.replacement, 1)
        }
        if (separator.isNotEmpty()) ic.commitText(separator, 1)
        ic.endBatchEdit()

        undo = tail?.let { Undo(it.replacement + separator, it.original + separator) }
        keyboard?.showCorrection(tail?.original, tail?.replacement)
    }

    /**
     * 마지막 자동 교정을 되돌린다.
     *
     * 되돌리기 전에 편집기의 실제 내용을 확인한다. 사용자가 그사이 커서를 옮겼거나
     * 다른 앱이 텍스트를 바꿨을 수 있어서, 우리가 쓴 그대로일 때만 손댄다.
     */
    private fun revertLastCorrection(ic: InputConnection): Boolean {
        val pending = undo ?: return false
        undo = null

        val before = ic.getTextBeforeCursor(pending.applied.length, 0)?.toString()
        if (before != pending.applied) return false

        ic.beginBatchEdit()
        ic.deleteSurroundingText(pending.applied.length, 0)
        ic.commitText(pending.original, 1)
        ic.endBatchEdit()

        keyboard?.showCorrection(null, null)
        return true
    }

    /** 비밀번호·이메일·URL 처럼 교정하면 안 되는 입력란인지 판별한다. */
    private fun isCorrectableField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return (type and InputType.TYPE_MASK_VARIATION) !in SENSITIVE_VARIATIONS
    }

    private companion object {
        /** 교정 창을 만들 때 커서 앞에서 읽어오는 최대 글자 수. */
        const val LOOKBEHIND_CHARS = 64

        /** 어절이 끝났다고 보고 교정을 실행하는 문자들. */
        val WORD_TERMINATORS = setOf('.', ',', '!', '?', ';', ':')

        val SENSITIVE_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER
        )
    }
}
