package com.spellkeyboard.core.editor

/**
 * 안드로이드 `InputConnection` 의 동작을 흉내 내는 가짜 편집기.
 *
 * 실제 편집기와 맞춰야 하는 규칙은 두 가지다.
 * - `commitText` 는 조합 중이던 글자를 **대체**하고 조합 상태를 푼다.
 * - `setComposingText` 는 조합 영역을 통째로 갈아 끼운다. 빈 문자열이면 영역이 사라진다.
 */
class FakeEditor(
    /**
     * false 면 커서 앞 텍스트를 못 읽어 주는 편집기를 흉내 낸다.
     * 안드로이드에서 `getTextBeforeCursor` 가 null 을 돌려주는 상황이다.
     */
    private val canReadBack: Boolean = true
) : Editor {

    private val buffer = StringBuilder()
    private var cursor = 0
    private var composingStart = -1

    /** 배치가 제대로 열고 닫히는지 확인하려고 센다. */
    var batchDepth = 0
        private set

    var maxBatchDepth = 0
        private set

    val text: String get() = buffer.toString()

    val isComposing: Boolean get() = composingStart >= 0

    override fun beginBatch() {
        batchDepth++
        maxBatchDepth = maxOf(maxBatchDepth, batchDepth)
    }

    override fun endBatch() {
        batchDepth--
    }

    override fun commitText(text: String) {
        replaceComposingRegion(text)
        composingStart = -1
    }

    override fun setComposingText(text: String) {
        val start = if (composingStart >= 0) composingStart else cursor
        replaceComposingRegion(text)
        composingStart = if (text.isEmpty()) -1 else start
    }

    override fun finishComposing() {
        composingStart = -1
    }

    override fun deleteBefore(count: Int) {
        val start = (cursor - count).coerceAtLeast(0)
        buffer.delete(start, cursor)
        cursor = start
        composingStart = -1
    }

    override fun textBeforeCursor(maxChars: Int): String {
        if (!canReadBack) return ""
        val start = (cursor - maxChars).coerceAtLeast(0)
        return buffer.substring(start, cursor)
    }

    private fun replaceComposingRegion(text: String) {
        if (composingStart >= 0) {
            buffer.delete(composingStart, cursor)
            cursor = composingStart
        }
        buffer.insert(cursor, text)
        cursor += text.length
    }
}
