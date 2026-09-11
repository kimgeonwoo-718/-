package com.spellkeyboard.core.clipboard

/**
 * 복사해 둔 글 목록.
 *
 * 안드로이드의 클립보드는 **마지막 하나**만 들고 있다. 주소를 복사해 붙여넣고 나면
 * 그 전에 복사한 계좌번호는 사라진다. 키보드가 지나간 것들을 들고 있으면 그 일이 없다.
 *
 * 저장은 [Store] 에 맡긴다. 안드로이드를 모르니 규칙을 JVM 테스트로 확인할 수 있다.
 *
 * ## 담지 않는 것
 *
 * 비밀번호는 담지 않는다. 안드로이드 13 부터 복사한 쪽이 "민감함" 이라고 표시해 주고,
 * 그걸 지키는 건 호출하는 쪽 책임이다([add] 를 부르지 않으면 된다). 키보드는 사용자가
 * 치는 모든 것을 보는 앱이라, 담지 않기로 한 것은 정말로 담으면 안 된다.
 */
class ClipboardHistory(private val store: Store) {

    interface Store {
        fun read(): List<String>
        fun write(items: List<String>)
    }

    fun items(): List<String> = store.read().take(CAPACITY)

    /**
     * 새로 복사된 글을 맨 앞에 넣는다.
     *
     * 같은 글을 다시 복사하면 새 항목을 만들지 않고 맨 앞으로 올린다 — 목록이 같은
     * 글로 채워지면 쓸모가 없다.
     *
     * @return 실제로 담았으면 true.
     */
    fun add(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return false

        val current = items()
        if (current.firstOrNull() == trimmed) return false

        store.write((listOf(trimmed) + current.filterNot { it == trimmed }).take(CAPACITY))
        return true
    }

    fun remove(text: String) {
        store.write(items().filterNot { it == text })
    }

    fun clear() {
        store.write(emptyList())
    }

    companion object {
        /**
         * 목록을 문자열 하나로 잇는다.
         *
         * 설정 파일에는 문자열 하나만 넣을 수 있는데, 구분자를 쓰면 그 글자가 든 글을
         * 담는 순간 목록이 갈라진다 — 줄바꿈이 든 글을 복사하면 바로 깨진다. 그래서
         * 길이를 앞에 적어 두고 그만큼 잘라 읽는다.
         */
        fun encode(items: List<String>): String =
            items.joinToString("") { "${it.length}:$it" }

        fun decode(raw: String): List<String> {
            val out = ArrayList<String>()
            var index = 0
            while (index < raw.length) {
                val colon = raw.indexOf(':', index)
                if (colon < 0) break
                val length = raw.substring(index, colon).toIntOrNull() ?: break
                val start = colon + 1
                val end = start + length
                if (end > raw.length) break
                out += raw.substring(start, end)
                index = end
            }
            return out
        }

        /** 들고 있을 개수. 화면에 보이는 만큼이면 충분하고, 넘으면 오래된 것부터 버린다. */
        const val CAPACITY = 20

        /**
         * 이보다 긴 글은 담지 않는다.
         *
         * 문서를 통째로 복사한 것까지 설정 파일에 쌓으면 앱이 느려진다. 붙여넣기용
         * 짧은 조각을 위한 기능이다.
         */
        const val MAX_LENGTH = 5_000
    }
}
