package com.spellkeyboard.core.editor

/**
 * 번역 입력줄의 편집기.
 *
 * 번역 모드에서는 키 입력이 앱의 입력란이 아니라 여기로 들어온다. 한글 조합·자동 교정은
 * 평소와 똑같이 [TypingSession] 이 이 편집기 위에서 돌리고, 여기 쌓인 한국어를 번역기가
 * 읽어 앱 입력란에는 번역문을 넣는다([TranslationOutput]). 커서는 늘 끝에 있다 —
 * 입력줄은 한 줄짜리라 커서를 옮길 일이 없다.
 *
 * 규칙은 [Editor] 의 다른 구현과 같다: `commitText` 는 조합 중이던 글자를 대체하고,
 * `setComposingText` 는 조합 영역을 통째로 갈아 끼운다.
 */
class TranslateBuffer : Editor {

    private val buffer = StringBuilder()
    private var composingStart = -1
    private var batchDepth = 0

    /** 내용이 바뀔 때마다 (배치 안에서는 배치가 닫힐 때 한 번) 부른다. 화면 갱신과 번역 예약용. */
    var onChange: (() -> Unit)? = null

    /** 조합 중인 글자까지 포함한 지금 내용. 번역기에 넘기는 것은 이것이다. */
    val text: String get() = buffer.toString()

    val isEmpty: Boolean get() = buffer.isEmpty()

    fun clear() {
        buffer.setLength(0)
        composingStart = -1
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
        changed()
    }

    override fun setComposingText(text: String) {
        val start = if (composingStart >= 0) composingStart else buffer.length
        replaceComposing(text)
        composingStart = if (text.isEmpty()) -1 else start
        changed()
    }

    override fun finishComposing() {
        composingStart = -1
    }

    override fun deleteBefore(count: Int) {
        val start = (buffer.length - count).coerceAtLeast(0)
        buffer.setLength(start)
        composingStart = -1
        changed()
    }

    override fun textBeforeCursor(maxChars: Int): String =
        buffer.substring((buffer.length - maxChars).coerceAtLeast(0))

    private fun replaceComposing(text: String) {
        if (composingStart >= 0) buffer.setLength(composingStart)
        buffer.append(text)
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
