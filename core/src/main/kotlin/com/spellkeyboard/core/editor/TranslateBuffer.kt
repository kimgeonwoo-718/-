package com.spellkeyboard.core.editor

/**
 * 번역 입력줄의 편집기.
 *
 * 번역 모드에서는 키 입력이 앱의 입력란이 아니라 여기로 들어온다. 한글 조합·자동 교정은
 * 평소와 똑같이 [TypingSession] 이 이 편집기 위에서 돌리고, 여기 쌓인 한국어를 번역기가
 * 읽어 앱 입력란에는 번역문을 넣는다([TranslationOutput]).
 *
 * **커서가 있다.** 처음에는 늘 끝에만 있다고 봤다 — 한 줄짜리라 옮길 일이 없다고. 그런데 입력줄에
 * 깜빡이는 커서가 안 보이니 어디에 쓰는지 알 수 없었고, 쓰다가 앞쪽 오타를 고치려면 전부 지우고
 * 다시 쳐야 했다. 지금은 [moveCursor]·[setCursor] 로 옮기고, 글자는 커서 자리에 들어간다.
 *
 * 규칙은 [Editor] 의 다른 구현과 같다: `commitText` 는 조합 중이던 글자를 대체하고,
 * `setComposingText` 는 조합 영역을 통째로 갈아 끼운다.
 */
class TranslateBuffer : Editor {

    private val buffer = StringBuilder()
    private var cursor = 0

    /** 조합 영역 [composingStart, composingEnd). 조합 중이 아니면 둘 다 -1. */
    private var composingStart = -1
    private var composingEnd = -1
    private var batchDepth = 0

    /** 내용이 바뀔 때마다 (배치 안에서는 배치가 닫힐 때 한 번) 부른다. 화면 갱신과 번역 예약용. */
    var onChange: (() -> Unit)? = null

    /** 글자는 그대로이고 커서만 움직였다. 화면만 갱신하면 된다 — 번역을 다시 돌릴 이유가 없다. */
    var onCaret: (() -> Unit)? = null

    /** 조합 중인 글자까지 포함한 지금 내용. 번역기에 넘기는 것은 이것이다. */
    val text: String get() = buffer.toString()

    /** 커서 위치(글자 앞에서 센 번호). 0 이면 맨 앞, [text] 길이면 맨 끝. */
    val cursorIndex: Int get() = cursor

    val isEmpty: Boolean get() = buffer.isEmpty()

    fun clear() {
        buffer.setLength(0)
        cursor = 0
        composingStart = -1
        composingEnd = -1
        batchDepth = 0
    }

    /** 커서를 [delta] 글자만큼 옮긴다. 조합 중이던 글자는 먼저 확정한다. */
    fun moveCursor(delta: Int) = setCursor(cursor + delta)

    /** 커서를 [index] 로 옮긴다. 범위를 벗어나면 끝으로 맞춘다. */
    fun setCursor(index: Int) {
        finishComposing()
        val next = index.coerceIn(0, buffer.length)
        if (next == cursor) return
        cursor = next
        onCaret?.invoke()
    }

    override fun beginBatch() {
        batchDepth++
    }

    override fun endBatch() {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) onChange?.invoke()
    }

    override fun commitText(text: String) {
        replaceComposing(text)
        composingStart = -1
        composingEnd = -1
        changed()
    }

    override fun setComposingText(text: String) {
        val start = if (hasComposing()) composingStart else cursor
        replaceComposing(text)
        if (text.isEmpty()) {
            composingStart = -1
            composingEnd = -1
        } else {
            composingStart = start
            composingEnd = start + text.length
        }
        changed()
    }

    override fun finishComposing() {
        composingStart = -1
        composingEnd = -1
    }

    override fun deleteBefore(count: Int) {
        val start = (cursor - count).coerceAtLeast(0)
        buffer.delete(start, cursor)
        cursor = start
        composingStart = -1
        composingEnd = -1
        changed()
    }

    override fun textBeforeCursor(maxChars: Int): String =
        buffer.substring((cursor - maxChars).coerceAtLeast(0), cursor)

    /** 커서 뒤 글자를 최대 [maxChars] 개. */
    fun textAfterCursor(maxChars: Int): String =
        buffer.substring(cursor, (cursor + maxChars).coerceAtMost(buffer.length))

    private fun hasComposing(): Boolean {
        // 조합 영역이 내용 밖으로 나가 있을 수는 없다. 밖에서 내용을 지웠는데 조합 상태만
        // 남아 있으면 앞 글자를 먹으므로, 어긋난 값은 여기서 버린다.
        if (composingStart < 0 || composingEnd < composingStart || composingEnd > buffer.length) {
            composingStart = -1
            composingEnd = -1
            return false
        }
        return true
    }

    /** 조합 영역(없으면 커서 자리)을 [text] 로 바꾸고 커서를 그 뒤에 둔다. */
    private fun replaceComposing(text: String) {
        val from = if (hasComposing()) composingStart else cursor
        val to = if (hasComposing()) composingEnd else cursor
        buffer.replace(from, to, text)
        cursor = from + text.length
    }

    private fun changed() {
        if (batchDepth == 0) onChange?.invoke()
    }
}

/**
 * 앱 입력란에 번역문을 넣고, 번역이 바뀌면 앞서 넣은 것을 지우고 다시 넣는다.
 *
 * 우리가 넣은 글자 수만 기억한다. 사용자가 그사이 입력란을 건드리면 어긋날 수 있는데,
 * 번역 모드에서는 키보드가 입력란을 독점하므로 실제로는 일어나지 않는다.
 */
class TranslationOutput {

    /** 지금 입력란에 들어가 있는 번역문의 길이. */
    var inserted: Int = 0
        private set

    /** 앞서 넣은 번역문을 [text] 로 바꾼다. 빈 문자열이면 지우기만 한다. */
    fun replace(editor: Editor, text: String) {
        if (inserted == 0 && text.isEmpty()) return
        editor.beginBatch()
        editor.finishComposing()
        if (inserted > 0) editor.deleteBefore(inserted)
        if (text.isNotEmpty()) editor.commitText(text)
        editor.endBatch()
        inserted = text.length
    }

    /** 넣은 것을 입력란에 남겨 둔 채 잊는다. 번역 모드를 닫을 때. */
    fun detach() {
        inserted = 0
    }
}
