package com.spellkeyboard.core.correct

import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.Speller

/** [CorrectionEngine.correct] 의 결과. */
data class CorrectionResult(
    val original: String,
    val text: String,
    val corrections: List<Correction>
) {
    val changed: Boolean get() = text != original
}

/**
 * 커서 앞 텍스트를 얼마나 지우고 무엇으로 바꿔 쓸지.
 *
 * IME 는 이 값을 받아 `deleteSurroundingText(deleteBefore, 0)` 후
 * `commitText(replacement)` 하면 된다.
 */
data class TailCorrection(
    val deleteBefore: Int,
    val original: String,
    val replacement: String,
    val corrections: List<Correction>
)

/**
 * 규칙 기반 한국어 맞춤법/띄어쓰기 교정기.
 *
 * 안드로이드에 의존하지 않는 순수 Kotlin 이라 데스크톱에서도 그대로 쓴다.
 * 실시간 입력 경로에서 호출되므로 정규식 몇 개를 순서대로 훑는 것 이상은 하지 않는다.
 */
class CorrectionEngine(
    private val wordRules: List<TextRule> = SpellingRules.WORD,
    private val textRules: List<TextRule> = SpellingRules.CONTEXT + SpacingRules.DEFAULT
) {

    /**
     * 일반 띄어쓰기 복원기. 사전을 푸는 데 시간이 걸려 나중에 끼워 넣을 수 있게 열어 둔다.
     * null 이면 규칙만으로 동작한다 — 패턴은 고치지만 '아버지가방에' 류는 손대지 못한다.
     */
    @Volatile
    var spacer: Spacer? = null
        set(value) {
            field = value
            // 사전이 없던 동안 "고칠 것 없음" 으로 기억해 둔 것들은 이제 틀렸다.
            analysed.clear()
        }

    /** 사전 기반 맞춤법 교정기. 규칙 사전이 못 잡는 오타를 분석 비용으로 잡는다. */
    @Volatile
    var speller: Speller? = null
        set(value) {
            field = value
            analysed.clear()
        }

    /**
     * 한 번 분석한 어절의 결과.
     *
     * **스페이스를 칠 때마다 커서 앞 몇 어절을 통째로 다시 본다.** 그런데 그중 새로
     * 끝난 것은 하나뿐이고 나머지는 직전에 이미 분석한 것들이다. 형태소 분석은
     * 어절 하나에 수 ms 가 들어서, 이 헛일이 그대로 타이핑을 막는다 — 실기기에서
     * "스페이스 누를 때 키가 씹힌다" 로 나타났다.
     *
     * 어절 하나를 어떻게 고치는지는 앞뒤 문맥과 무관해서(사전과 비용만 본다) 기억해
     * 둬도 안전하다. 사전이 바뀌면 통째로 버린다.
     */
    private val analysed = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > ANALYSIS_CACHE_SIZE
    }

    @Synchronized
    private fun analyse(word: String, compute: (String) -> String): String =
        analysed.getOrPut(word) { compute(word) }

    /** 문자열 전체를 교정한다. */
    fun correct(text: String): CorrectionResult {
        if (!hasHangul(text)) return CorrectionResult(text, text, emptyList())

        val sink = mutableListOf<Correction>()
        var current = applyWordRules(text, sink)
        current = applySpacer(current, sink)
        current = applySpeller(current, sink)
        current = applyTextRules(current, sink)
        // 띄어쓰기 교정으로 새 어절이 드러날 수 있어 어절 규칙을 한 번 더 돌린다.
        current = applyWordRules(current, sink)

        return CorrectionResult(text, current, sink)
    }

    /**
     * 커서 앞 텍스트의 끝부분만 교정한다.
     *
     * 사용자가 이미 지나간 문장을 건드리지 않도록 마지막 [maxWords] 어절까지만 본다.
     * 고칠 것이 없으면 null.
     */
    fun correctTail(textBeforeCursor: String, maxWords: Int = DEFAULT_WINDOW_WORDS): TailCorrection? {
        if (textBeforeCursor.isEmpty()) return null

        val start = windowStart(textBeforeCursor, maxWords)
        val window = textBeforeCursor.substring(start)
        val result = correct(window)
        if (!result.changed) return null

        return TailCorrection(
            deleteBefore = window.length,
            original = window,
            replacement = result.text,
            corrections = result.corrections
        )
    }

    /** [correctTail] 이 실제로 검사하는 구간. 진단 표시에 쓴다. */
    fun tailWindow(textBeforeCursor: String, maxWords: Int = DEFAULT_WINDOW_WORDS): String =
        textBeforeCursor.substring(windowStart(textBeforeCursor, maxWords))

    /**
     * 어절 규칙을 훑는다.
     *
     * 규칙이 백 개가 넘고 한 번 교정할 때 두 번 도는데, 창 안의 어절 대부분은 직전에
     * 이미 훑은 것이다. 어절 하나에 대한 규칙 적용 결과는 앞뒤와 무관하므로 기억해 둔다.
     */
    private fun applyWordRules(text: String, sink: MutableList<Correction>): String =
        mapWords(text) { word ->
            val fixed = analyse(RULE_KEY + word) {
                wordRules.fold(word) { acc, rule -> rule.apply(acc, mutableListOf()) }
            }
            if (fixed != word) sink += Correction(word, fixed, "맞춤법")
            fixed
        }

    /** 붙여 쓴 덩어리를 어절로 나눈다. 사전이 아직 안 올라왔으면 아무것도 하지 않는다. */
    private fun applySpacer(text: String, sink: MutableList<Correction>): String {
        val spacer = this.spacer ?: return text
        return mapWords(text) { word ->
            val spaced = analyse(SPACE_KEY + word) { spacer.space(word) ?: word }
            if (spaced != word) sink += Correction(word, spaced, "띄어쓰기")
            spaced
        }
    }

    /** 규칙 사전이 못 잡은 오타를 형태소 분석으로 잡는다. */
    private fun applySpeller(text: String, sink: MutableList<Correction>): String {
        val speller = this.speller ?: return text
        return mapWords(text) { word ->
            val fixed = analyse(SPELL_KEY + word) { speller.correct(word) ?: word }
            if (fixed != word) sink += Correction(word, fixed, "맞춤법")
            fixed
        }
    }

    private inline fun mapWords(text: String, transform: (String) -> String): String =
        buildString {
            for (token in TOKEN.findAll(text)) {
                val value = token.value
                if (value.isBlank()) append(value) else append(transform(value))
            }
        }

    private fun applyTextRules(text: String, sink: MutableList<Correction>): String =
        textRules.fold(text) { acc, rule -> rule.apply(acc, sink) }

    companion object {
        /** 기억해 둘 어절 수. 한 문단 분량이면 충분하고, 넘으면 오래된 것부터 버린다. */
        private const val ANALYSIS_CACHE_SIZE = 512

        // 띄어쓰기와 맞춤법은 같은 어절에 서로 다른 답을 내므로 키를 갈라 둔다.
        private const val SPACE_KEY = "\u0000s"
        private const val SPELL_KEY = "\u0000p"
        private const val RULE_KEY = "\u0000r"

        const val DEFAULT_WINDOW_WORDS = 3

        private val TOKEN = Regex("""\S+|\s+""")

        private fun hasHangul(text: String): Boolean =
            text.any { it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x3163 }

        /** 뒤에서부터 [maxWords] 개의 어절을 포함하는 창의 시작 위치를 찾는다. */
        internal fun windowStart(text: String, maxWords: Int): Int {
            var i = text.length
            while (i > 0 && text[i - 1].isWhitespace()) i--

            var words = 0
            while (i > 0 && words < maxWords) {
                while (i > 0 && !text[i - 1].isWhitespace()) i--
                words++
                if (words < maxWords) {
                    while (i > 0 && text[i - 1].isWhitespace()) i--
                }
            }
            return i
        }
    }
}
