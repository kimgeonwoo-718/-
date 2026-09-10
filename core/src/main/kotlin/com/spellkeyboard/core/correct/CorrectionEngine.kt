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
    private val textRules: List<TextRule> = SpellingRules.CONTEXT + SpacingRules.DEFAULT,
    /**
     * 일반 띄어쓰기 복원기. 사전을 푸는 데 시간이 걸려 나중에 끼워 넣을 수 있게 열어 둔다.
     * null 이면 규칙만으로 동작한다 — 패턴은 고치지만 '아버지가방에' 류는 손대지 못한다.
     */
    @Volatile var spacer: Spacer? = null,
    /** 사전 기반 맞춤법 교정기. 규칙 사전이 못 잡는 오타를 분석 비용으로 잡는다. */
    @Volatile var speller: Speller? = null
) {

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

    private fun applyWordRules(text: String, sink: MutableList<Correction>): String =
        buildString {
            for (token in TOKEN.findAll(text)) {
                val value = token.value
                if (value.isBlank()) {
                    append(value)
                } else {
                    append(wordRules.fold(value) { acc, rule -> rule.apply(acc, sink) })
                }
            }
        }

    /** 붙여 쓴 덩어리를 어절로 나눈다. 사전이 아직 안 올라왔으면 아무것도 하지 않는다. */
    private fun applySpacer(text: String, sink: MutableList<Correction>): String {
        val spacer = this.spacer ?: return text
        return mapWords(text) { word ->
            val spaced = spacer.space(word)
            if (spaced != null) sink += Correction(word, spaced, "띄어쓰기")
            spaced ?: word
        }
    }

    /** 규칙 사전이 못 잡은 오타를 형태소 분석으로 잡는다. */
    private fun applySpeller(text: String, sink: MutableList<Correction>): String {
        val speller = this.speller ?: return text
        return mapWords(text) { word ->
            val fixed = speller.correct(word)
            if (fixed != null) sink += Correction(word, fixed, "맞춤법")
            fixed ?: word
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
