package com.spellkeyboard.core.translate

import com.spellkeyboard.core.hangul.Hangul

/**
 * 번역기에 넘기기 전에 채팅 한국어를 다듬는다.
 *
 * ## 왜
 *
 * 온디바이스 번역기는 **문장다운 문장**으로 배웠다. 마침표가 있고, 'ㅋㅋ' 같은 게 없고,
 * 글자가 늘어지지 않은 문장이다. 채팅 한국어는 셋 다 어긋난다.
 *
 * - 마침표가 없다. 번역기는 문장이 끝났는지 몰라 뒤를 흘리거나 의문문을 평서문으로 옮긴다.
 * - 'ㅋㅋㅋ' 은 자모라 번역기가 통째로 헛소리를 만든다 ("kkk").
 * - '좋아아아아' 처럼 늘여 쓴 글자는 사전에 없는 낱말이 된다.
 *
 * 이 셋을 떼어 내고, 번역이 끝난 뒤 어울리는 자리에 도로 붙인다. 번역기를 못 바꾸니
 * **번역기에 먹이는 것**을 바꾸는 것이다.
 */
object ChatText {

    /** 웃음·울음처럼 번역할 수 없는 감탄 자모. */
    enum class Emphasis { LAUGH, CRY }

    /**
     * 다듬은 결과.
     *
     * @param core 번역기와 관용구집에 넘길 알맹이. 부호도 감탄 자모도 없다.
     * @param ending 번역문 뒤에 붙일 문장 부호. 원문에 있던 것이거나, 없으면 우리가 고른 것.
     * @param emphasis 원문에 있던 감탄 자모. 없으면 null.
     */
    data class Normalized(val core: String, val ending: String, val emphasis: Emphasis?)

    fun normalize(sentence: String): Normalized {
        var emphasis: Emphasis? = null
        val kept = ArrayList<String>()

        for (token in sentence.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            val jamoKind = emphasisOf(token)
            if (jamoKind != null) {
                // 'ㅋㅋ' 은 번역기에 넘기지 않는다. 뭉치가 여럿이면 마지막 것만 남긴다.
                emphasis = jamoKind
                continue
            }
            kept += collapseRuns(token)
        }

        val joined = kept.joinToString(" ")
        val core = joined.trimEnd(*TRAILING_PUNCTUATION).trim()
        if (core.isEmpty()) return Normalized("", "", emphasis)

        val original = joined.substring(core.length).trim()
        val ending = when {
            original.isNotEmpty() -> original
            isQuestion(core) -> "?"
            else -> "."
        }
        return Normalized(core, ending, emphasis)
    }

    /**
     * 이 어절이 통째로 감탄 자모인가. 'ㅋㅋ', 'ㅎㅎㅎ', 'ㅠㅠ', 'ㅜㅜ'.
     * 'ㅇㅇ'(응), 'ㄴㄴ'(아니) 같은 줄임말은 뜻이 있어 여기서 다루지 않는다 — 관용구집의 몫이다.
     */
    private fun emphasisOf(token: String): Emphasis? {
        val bare = token.trim(*TRAILING_PUNCTUATION)
        if (bare.length < 2 || bare.any { it !in COMPAT_JAMO }) return null
        return when {
            bare.all { it == 'ㅋ' || it == 'ㅎ' } -> Emphasis.LAUGH
            bare.all { it == 'ㅠ' || it == 'ㅜ' } -> Emphasis.CRY
            else -> null
        }
    }

    /**
     * 늘여 쓴 글자를 줄인다.
     *
     * 같은 글자가 셋 이상 이어지면 하나로 줄인다 — '좋아아아아' → '좋아'.
     * 그 글자가 **앞 글자의 모음을 늘인 것**이면(초성 ㅇ, 받침 없음, 같은 모음) 통째로 버린다 —
     * '네에에에' 의 '에' 는 '네' 의 일부가 아니라 늘임소리다. '좋아아아' 의 '아' 는 '좋아' 의
     * 일부라서 하나는 남겨야 하는데, 그 경우 앞 글자 '좋' 의 모음(ㅗ)과 달라 구별된다.
     */
    private fun collapseRuns(token: String): String {
        val out = StringBuilder(token.length)
        var index = 0
        while (index < token.length) {
            var run = 1
            while (index + run < token.length && token[index + run] == token[index]) run++
            val char = token[index]
            index += run
            if (run >= REPEAT_LIMIT && out.isNotEmpty() && isStretchOf(out.last(), char)) continue
            out.append(char)
            if (run < REPEAT_LIMIT) repeat(run - 1) { out.append(char) }
        }
        return out.toString()
    }

    /** [stretched] 가 [previous] 의 모음을 늘인 글자인가. '네' 뒤의 '에'. */
    private fun isStretchOf(previous: Char, stretched: Char): Boolean {
        val (cho, jung, jong) = Hangul.decompose(stretched) ?: return false
        if (Hangul.CHOSEONG[cho] != 'ㅇ' || jong != 0) return false
        val before = Hangul.decompose(previous) ?: return false
        return before.second == jung
    }

    /**
     * 물음표를 붙일 문장인가.
     *
     * 종결어미만으로는 못 가린다 — '좋아요'(평서)와 '어떠세요'(의문)가 똑같이 '요' 로 끝난다.
     * 그래서 **의문형 어미**이거나 **의문사가 한 어절로 들어 있으면** 물음표로 본다.
     * 의문사는 어절 통째로 맞을 때만 센다 — '왜냐하면' 의 '왜' 에 걸리지 않게.
     */
    private fun isQuestion(core: String): Boolean {
        if (INTERROGATIVE_ENDINGS.any { core.endsWith(it) }) return true
        return core.split(WHITESPACE).any { it in INTERROGATIVE_WORDS }
    }

    private val WHITESPACE = Regex("""\s+""")
    private val COMPAT_JAMO = 'ㄱ'..'ㅣ'
    private val TRAILING_PUNCTUATION =
        charArrayOf('.', '!', '?', '…', '~', ',', '。', '！', '？', ' ')

    /** 이만큼 이어지면 늘여 쓴 것으로 본다. */
    private const val REPEAT_LIMIT = 3

    /** 뜻만으로 의문문이 되는 어미. 평서형과 겹치지 않는 것만. */
    private val INTERROGATIVE_ENDINGS =
        listOf("까", "까요", "나요", "니", "냐", "는가", "은가", "을까", "ㄹ까", "든가")

    /** 의문사. 자주 쓰는 활용형까지 적어 둔다. */
    private val INTERROGATIVE_WORDS = setOf(
        "뭐", "뭘", "뭐야", "뭐예요", "뭐죠", "무슨", "무엇", "무엇을",
        "어디", "어디야", "어디서", "어디로", "어딨어", "어디예요",
        "언제", "언제야", "누구", "누가", "누구세요", "누구야",
        "왜", "왜요", "어떻게", "어떤", "어때", "어때요", "어떠세요", "어떠신가요",
        "얼마", "얼마나", "얼마예요", "몇", "몇시", "몇 시"
    )
}
