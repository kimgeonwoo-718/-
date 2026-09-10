package com.spellkeyboard.core.editor

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.hangul.HangulAutomata

/**
 * 입력 한 번에 대한 처리 전부 — 한글 조합, 교정 시점 판단, 되돌리기.
 *
 * 안드로이드에 의존하지 않는다. IME 는 [Editor] 어댑터만 끼워 주면 되고,
 * 그 덕에 "스페이스를 눌렀을 때 실제로 무슨 일이 벌어지는가"를 전부 단위 테스트로 확인한다.
 */
class TypingSession(
    /** 교정 엔진. 사전이 늦게 올라오므로 밖에서 [CorrectionEngine.spacer] 를 끼울 수 있게 공개한다. */
    val engine: CorrectionEngine = CorrectionEngine()
) {

    private val automata = HangulAutomata()
    private var undo: Undo? = null

    /**
     * 우리가 편집기에 써 넣은 텍스트의 사본.
     *
     * 교정하려면 커서 앞 텍스트를 읽어야 하는데, 편집기에 따라
     * `getTextBeforeCursor` 가 빈 값을 돌려주는 경우가 있다. 그러면 읽을 게 없어
     * 아무것도 고치지 않고 조용히 넘어간다 — 증상이 "교정이 아예 안 된다" 로 보인다.
     * 그래서 우리가 쓴 것은 우리가 기억해 두고, 편집기가 못 읽어 줄 때 이걸 쓴다.
     */
    private val mirror = StringBuilder()

    /** 비밀번호 입력란처럼 교정하면 안 되는 곳에서 끈다. */
    var correctionEnabled: Boolean = true

    /** 교정 시도 결과를 받는 곳. 키보드 상단 줄이 구독한다. */
    var onEvent: ((CorrectionEvent) -> Unit)? = null

    private data class Undo(val applied: String, val original: String)

    fun reset() {
        automata.reset()
        undo = null
        mirror.setLength(0)
    }

    /** 아직 조합 중인 글자가 있는지. 커서 이동을 감지할 때 쓴다. */
    fun isComposing(): Boolean = !automata.isEmpty()

    /** 우리를 거치지 않고 편집기에서 글자가 지워졌을 때 사본을 맞춘다. */
    fun notifyDeleted(count: Int = 1) {
        mirror.setLength((mirror.length - count).coerceAtLeast(0))
    }

    /** 편집기에 확정 입력하면서 사본도 같이 갱신한다. */
    private fun commit(editor: Editor, text: String) {
        editor.commitText(text)
        mirror.append(text)
        if (mirror.length > MIRROR_CAPACITY) {
            mirror.delete(0, mirror.length - MIRROR_CAPACITY)
        }
    }

    /** 한글 자모 하나. 조합이 끝나기 전에는 교정하지 않는다. */
    fun pressJamo(editor: Editor, jamo: Char) {
        undo = null
        val output = automata.press(jamo)
        editor.beginBatch()
        if (output.committed.isNotEmpty()) commit(editor, output.committed)
        editor.setComposingText(output.composing)
        editor.endBatch()
    }

    /**
     * 방금 넣은 자모를 [jamo] 로 바꾼다. 키를 길게 눌러 쌍자음을 낼 때 쓴다.
     *
     * 누르는 순간 이미 예사소리가 들어갔으므로, 조합을 한 단계 되돌린 뒤 다시 넣는다.
     * 오토마타가 조합 중인 상태라 편집기의 확정된 글자는 건드리지 않는다.
     */
    fun replaceLastJamo(editor: Editor, jamo: Char) {
        val undone = automata.backspace() ?: return
        editor.setComposingText(undone.composing)

        val output = automata.press(jamo)
        editor.beginBatch()
        if (output.committed.isNotEmpty()) commit(editor, output.committed)
        editor.setComposingText(output.composing)
        editor.endBatch()
    }

    /** 자모가 아닌 문자. 문장부호면 어절이 끝난 것으로 보고 교정한다. */
    fun pressText(editor: Editor, c: Char) {
        commitPending(editor)
        if (c in WORD_TERMINATORS) {
            correctThenCommit(editor, c.toString())
        } else {
            undo = null
            commit(editor, c.toString())
        }
    }

    fun pressSpace(editor: Editor) {
        commitPending(editor)
        correctThenCommit(editor, " ")
    }

    /** 엔터 직전까지 교정한다. 개행이나 편집기 액션 전송은 호출자 몫이다. */
    fun pressEnter(editor: Editor) {
        commitPending(editor)
        correctThenCommit(editor, "")
    }

    /**
     * 백스페이스.
     *
     * @return true 면 여기서 처리를 끝냈다. false 면 호출자가 편집기에서 한 글자를 지워야 한다.
     */
    fun pressBackspace(editor: Editor): Boolean {
        // 1) 조합 중인 글자가 있으면 한 단계만 되돌린다. (안녕 -> 안녀)
        val output = automata.backspace()
        if (output != null) {
            if (output.composing.isEmpty()) {
                editor.setComposingText("")
                editor.finishComposing()
            } else {
                editor.setComposingText(output.composing)
            }
            return true
        }

        // 2) 방금 자동 교정이 일어났다면 그것부터 되돌린다.
        return revertLastCorrection(editor)
    }

    /** 조합 중인 글자를 확정한다. */
    fun commitPending(editor: Editor) {
        val pending = automata.flush()
        if (pending.isNotEmpty()) commit(editor, pending) else editor.finishComposing()
    }

    /**
     * 커서 앞 어절들을 교정한 뒤 [separator] 를 입력한다.
     *
     * 지우고 다시 쓰는 편집이라 배치로 묶는다. 묶지 않으면 편집기가 중간 상태를
     * 화면에 그려서 글자가 깜빡인다.
     */
    private fun correctThenCommit(editor: Editor, separator: String) {
        if (!correctionEnabled) {
            undo = null
            if (separator.isNotEmpty()) commit(editor, separator)
            onEvent?.invoke(CorrectionEvent.Disabled)
            return
        }

        val before = readBeforeCursor(editor, LOOKBEHIND_CHARS)
        val tail = engine.correctTail(before)

        editor.beginBatch()
        if (tail != null) {
            editor.deleteBefore(tail.deleteBefore)
            notifyDeleted(tail.deleteBefore)
            commit(editor, tail.replacement)
        }
        if (separator.isNotEmpty()) commit(editor, separator)
        editor.endBatch()

        undo = tail?.let { Undo(it.replacement + separator, it.original + separator) }
        onEvent?.invoke(
            if (tail != null) {
                CorrectionEvent.Applied(tail.original, tail.replacement)
            } else {
                CorrectionEvent.Unchanged(engine.tailWindow(before))
            }
        )
    }

    /**
     * 마지막 자동 교정을 되돌린다.
     *
     * 되돌리기 전에 편집기의 실제 내용을 확인한다. 그사이 커서가 움직였거나 다른 주체가
     * 텍스트를 바꿨을 수 있어서, 우리가 쓴 그대로일 때만 손댄다.
     */
    private fun revertLastCorrection(editor: Editor): Boolean {
        val pending = undo ?: return false
        undo = null

        if (readBeforeCursor(editor, pending.applied.length) != pending.applied) return false

        editor.beginBatch()
        editor.deleteBefore(pending.applied.length)
        notifyDeleted(pending.applied.length)
        commit(editor, pending.original)
        editor.endBatch()

        onEvent?.invoke(CorrectionEvent.Reverted)
        return true
    }

    /**
     * 커서 앞 텍스트를 읽는다.
     *
     * 편집기가 못 읽어 주면 우리가 써 넣은 사본으로 대신한다. 사본은 우리 입력만
     * 담고 있어서 사용자가 다른 방법으로 넣은 글자는 빠져 있지만, 방금 친 어절을
     * 고치는 데에는 그걸로 충분하다.
     */
    private fun readBeforeCursor(editor: Editor, maxChars: Int): String {
        val fromEditor = editor.textBeforeCursor(maxChars)
        if (fromEditor.isNotEmpty()) return fromEditor
        return mirror.toString().takeLast(maxChars)
    }

    companion object {
        /** 교정 창을 만들 때 커서 앞에서 읽어오는 최대 글자 수. */
        const val LOOKBEHIND_CHARS = 64

        /** 사본으로 들고 있을 최대 글자 수. */
        private const val MIRROR_CAPACITY = 256

        /** 어절이 끝났다고 보고 교정을 실행하는 문자들. */
        val WORD_TERMINATORS = setOf('.', ',', '!', '?', ';', ':')
    }
}
