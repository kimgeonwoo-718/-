package com.spellkeyboard.core.translate

/**
 * 문장별 번역 결과를 기억한다.
 *
 * 번역 입력줄에서는 사용자가 **끝에만** 글자를 더한다. 그래서 앞 문장들의 번역은 매번
 * 똑같다 — 다시 번역하면 시간만 쓰고, 번역기가 조금씩 다른 답을 내면 앞쪽 영어가 이유
 * 없이 흔들린다. 한 번 번역한 문장은 여기 담아 두고 바뀐 문장만 새로 번역한다.
 *
 * 번역 언어를 바꾸면 [clear] 로 통째로 버린다.
 */
class TranslationMemory(private val capacity: Int = 64) {

    private val known = object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > capacity
    }

    fun cached(sentence: String): String? = known[sentence]

    fun remember(sentence: String, translation: String) {
        known[sentence] = translation
    }

    fun clear() {
        known.clear()
    }

    /** [sentences] 중 아직 번역하지 않은 것들. 같은 문장이 두 번 나오면 한 번만. */
    fun missing(sentences: List<String>): List<String> =
        sentences.distinct().filter { it !in known }

    /**
     * 번역들을 이어 붙인다. 아직 없는 문장은 원문을 그대로 놓는다 — 그 자리가 비면
     * 사용자는 글이 사라진 줄로 안다.
     */
    fun assemble(sentences: List<String>): String =
        sentences.map { known[it] ?: it }.filter { it.isNotBlank() }.joinToString(" ")
}
