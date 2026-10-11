package com.spellkeyboard.core.translate

import com.spellkeyboard.core.hangul.Hangul

/**
 * 기기 안 큰 모델(제마)이 낸 **교정문**을 믿고 쓸 수 있는지 가리고 다듬는다. 못 쓰면 null — 부르는 쪽이 우리 엔진(correctAll)으로 대신한다.
 *
 * 번역문을 거르는 [LlmOutput] 과 같은 자리지만 보는 것이 반대다. 번역문은 한글이 남으면 안 되고, 교정문은 **원문과 거의 같아야** 한다.
 * 서버가 AI 교정문을 거르는 `tooDifferent`·`tidyCorrection`(server/src/openai.js)을 옮겼다 — 같은 실수를 같은 방법으로 막는다.
 *
 * - 머리말·따옴표·보기 흉내('원문 -> 고친 글')를 뗀다.
 * - **교정이 아닌 답**(대답·요약·딴 말)은 버린다: 길이가 크게 달라졌거나, 자모가 절반 넘게 바뀌었거나, 원문에 없던 '니다'·'죄송' 같은 대답 말이 생겼으면.
 * - 원문에 없던 문장부호를 떼고, 원문에 붙여 쓴 숫자+단위(3개·5천원)를 모델이 띄웠으면 도로 붙인다.
 */
object LlmCorrection {

    /** @return 쓸 수 있는 교정문(고칠 것이 없으면 원문 그대로), 못 쓰면 null. */
    fun clean(raw: String, original: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = text.replace(FENCE_START, "").replace(FENCE_END, "").trim()
        text = text.replace(LABEL, "")
        // 원문이 한 줄이면 첫 줄만 — 작은 모델은 고친 문장 뒤에 설명을 붙이기도 한다.
        if ('\n' !in original) text = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        // 지시문의 보기 모양('원문 -> 고친 글')을 흉내 냈으면 화살표 뒤만.
        for (arrow in ARROWS) if (arrow in text && arrow !in original) text = text.substringAfterLast(arrow).trim()
        val wrapped = WRAPPED.matchEntire(text)
        if (wrapped != null && WRAPPED.matchEntire(original.trim()) == null) text = wrapped.groupValues[2].trim()
        if (text.isEmpty()) return null
        if (tooDifferent(original, text)) return null
        return keepAttachedNumbers(original, dropAddedPunctuation(original, text))
    }

    /**
     * 모델에 한 번에 넘길 조각들의 **원문 안 자리**. 줄바꿈과 문장 부호(`. ! ? …` 뒤 공백)에서 자르고, 그래도 [maxChars] 를 넘으면
     * 그 앞의 마지막 공백에서 자른다. 조각 사이의 공백·줄바꿈은 건드리지 않고 남는다 — 고친 조각을 그 자리에 도로 끼우면 된다.
     *
     * 번역용 [SentenceSplitter] 를 안 쓰는 까닭: 그쪽은 원래 띄어쓰기와 줄바꿈을 버리고 문장을 새로 이어 붙인다. 교정은 사용자의 글
     * 모양을 그대로 둬야 한다.
     */
    fun segments(text: String, maxChars: Int = MAX_SEGMENT_CHARS): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        var lastSpace = -1
        fun close(end: Int) { // end 는 끝 글자 자리(포함)
            if (start < 0) return
            var e = end
            while (e >= start && text[e].isWhitespace()) e--
            if (e >= start) out += start..e
            start = -1
            lastSpace = -1
        }
        for ((i, ch) in text.withIndex()) {
            if (ch == '\n') { close(i - 1); continue }
            if (start < 0) {
                if (ch.isWhitespace()) continue
                start = i
            }
            if (ch.isWhitespace()) {
                lastSpace = i
                if (i > 0 && text[i - 1] in ENDERS) { close(i - 1); continue }
            }
            if (start >= 0 && i - start + 1 > maxChars && lastSpace > start) {
                val cut = lastSpace
                close(cut - 1)
                start = cut + 1
                while (start <= i && text[start].isWhitespace()) start++
                if (start > i) start = -1
            }
        }
        close(text.length - 1)
        return out
    }

    /** 교정문이 아닌가 — 길이, 자모 편집 거리, 대답에만 나오는 말(server/src/openai.js 의 tooDifferent). */
    fun tooDifferent(original: String, corrected: String): Boolean {
        val before = original.count { !it.isWhitespace() }
        val after = corrected.count { !it.isWhitespace() }
        val lengthOff = if (before < 20) after > before * 2 + 4 else after < before * 0.6 || after > before * 1.6
        if (lengthOff) return true
        val a = jamo(original)
        val b = jamo(corrected)
        if (a.isNotEmpty() && b.isNotEmpty() && a.size <= MAX_JAMO && b.size <= MAX_JAMO &&
            editDistance(a, b).toDouble() / maxOf(a.size, b.size) > MAX_JAMO_CHANGE
        ) return true
        val plainBefore = original.filterNot { it.isWhitespace() }.lowercase()
        val plainAfter = corrected.filterNot { it.isWhitespace() }.lowercase()
        return ANSWER_MARKS.any { it in plainAfter && it !in plainBefore }
    }

    /** 원문에 **한 번도 안 쓴** 문장부호를 모델이 붙였으면 뗀다. 채팅 말투가 딱딱해진다. */
    fun dropAddedPunctuation(original: String, corrected: String): String {
        var out = corrected
        for (mark in ADDED_MARKS) {
            if (mark in original || mark !in out) continue
            out = out.replace(mark.toString(), "")
        }
        return if (out == corrected) corrected else out.replace(SPACES, " ").trim()
    }

    /** 원문에 붙여 쓴 숫자+단위를 모델이 띄웠으면 도로 붙인다(맞춤법 43항 다만). 원문이 띄어 썼으면 그대로. */
    fun keepAttachedNumbers(original: String, corrected: String): String {
        if (original.none { it.isDigit() }) return corrected
        return SPACED_NUMBER.replace(corrected) { m ->
            val joined = m.groupValues[1] + m.groupValues[2]
            if (joined in original) joined else m.value
        }
    }

    private fun jamo(text: String): List<Char> {
        val out = ArrayList<Char>(text.length * 3)
        for (ch in text.lowercase()) {
            if (ch.isWhitespace() || ch in IGNORED) continue
            val parts = Hangul.decompose(ch)
            if (parts == null) {
                out += ch
                continue
            }
            out += Hangul.CHOSEONG[parts.first]
            out += Hangul.JUNGSEONG[parts.second]
            if (parts.third != 0) out += Hangul.JONGSEONG[parts.third]
        }
        return out
    }

    private fun editDistance(a: List<Char>, b: List<Char>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val row = IntArray(b.size + 1)
            row[0] = i
            for (j in 1..b.size) {
                row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = row
        }
        return previous[b.size]
    }

    /** 한 번에 넘길 조각의 최대 길이. 작은 모델은 길수록 뒤를 흘리고, 폰에서는 한 조각에 몇 초씩 든다. */
    const val MAX_SEGMENT_CHARS = 120
    private val ENDERS = setOf('.', '!', '?', '…', '。', '！', '？')
    private const val MAX_JAMO = 600
    private const val MAX_JAMO_CHANGE = 0.5
    private val ADDED_MARKS = charArrayOf('.', ',', '?', '!')
    private val IGNORED = ".,!?~'\"“”‘’…·:;()-".toSet()
    private val ANSWER_MARKS = listOf(
        "gemma", "제마", "젬마", "google", "구글", "인공지능", "언어모델", "챗봇", "assistant",
        "교정기", "교정할", "교정된", "텍스트", "도와드", "입력해주", "죄송", "니다"
    )
    private val ARROWS = listOf("->", "→", "=>")
    private val SPACES = Regex(" {2,}")
    private val SPACED_NUMBER = Regex("""(\d[\d,.]*[십백천만억조]*) +([가-힣])""")
    private val FENCE_START = Regex("""^```[A-Za-z]*\s*\n?""")
    private val FENCE_END = Regex("""\n?```\s*$""")
    private val LABEL = Regex("""^(?:교정(?:문|된 문장)?|고친 문장|수정(?:본)?|출력)\s*[:：]\s*""")
    private val WRAPPED = Regex("""^(["“「『'])([^\n]*)(["”」』'])$""")
}
