package com.spellkeyboard.core.translate

import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.hangul.NO_JONG
import com.spellkeyboard.core.lm.ContextCorrector

/**
 * 큰 모델이 고친 글에서 **믿을 만한 고침만** 받아들인다. 원문의 어절마다 본다.
 *
 * 글자가 바뀐 어절은 **바뀐 음절마다 자모 하나씩, 사람이 실제로 내는 오타 방향**이어야 받아들인다(음절 수·부호·숫자는 그대로).
 * - 오타 방향: 빈 받침 채우기(마아서 → 많아서, 우사 → 우산), 받침 마저 채우기(햇 → 했, 괜찬 → 괜찮), 소리 같은 모음(부내 → 부네, 네일 → 내일),
 *   쉬프트를 놓친 된소리(있을가 → 있을까).
 * - **말뭉치가 모르는 말**(오타일 가능성이 큰 말)은 두 음절까지 고친다.
 * - **말뭉치가 아는 말**은 한 음절만, 받침 마저 채우기와 소리 같은 모음만 받는다. 빈 받침 채우기는 드문 말(ln 빈도 < [ContextCorrector.CODA_SLIP_MAX_LN])만
 *   ('거 → 것' 은 안 받는다). 된소리 바꾸기는 안 받는다 — 둘 다 표준어인 말을 바꾼다(따듯한 → 따뜻한). 아는 말의 된소리 오타는 문맥 교정기가 먼저 고친다.
 * - 그 밖(딴 말·딴 말투로 바꾸기, 음절을 빼거나 더하기, 부호 떼기)은 **원래 어절로 되돌린다**
 *   (진정하셔요 → 진정하세요, 차고해 → 취소해, 아른거리나요. → 아른거리나요).
 *
 * 띄어쓰기는 받아들인 글자 사이에서 모델 것을 쓴다. 다만 '-아/-어' 뒤 보조용언(해 보다·해 주다·해 드리다·해 놓다·해 두다 …)은 띄어도
 * 붙여도 맞으니(한글 맞춤법 제47항) 사용자가 쓴 대로 두고, '-어지다·-아하다' 는 늘 붙인다.
 *
 * 문맥 교정기([ContextCorrector])가 멀쩡한 말을 고치는 기준과 같다(2026-10-11, 큰 시험지로 정함).
 */
object LlmMerge {

    /**
     * @param frequency 원문 어절(앞뒤 부호를 뗀 한글)이 말뭉치에 나온 횟수의 ln. 모르면 null([ContextCorrector.lnCount]).
     * @return 받아들일 고침만 담은 글. 아무것도 안 받아들이면 원문 그대로.
     */
    fun merge(original: String, candidate: String, frequency: (String) -> Float?): String {
        val o = letters(original)
        val c = letters(candidate)
        val tokens = tokens(original)
        if (tokens.isEmpty()) return original
        val map = boundaryMap(o.chars, c.chars)
        // 원문 어절마다 글자 범위(공백 뺀 자리)
        val starts = IntArray(tokens.size)
        val ends = IntArray(tokens.size)
        var letterIndex = 0
        var t = 0
        for (k in original.indices) {
            if (original[k].isWhitespace()) continue
            if (t < tokens.size && k == tokens[t].first) starts[t] = letterIndex
            letterIndex++
            if (t < tokens.size && k == tokens[t].last) { ends[t] = letterIndex; t++ }
        }

        // 받아들인 어절은 글자 수가 원문과 같다 — 글자마다 모델 쪽 자리가 하나씩 맞는다.
        val out = o.chars.toCharArray()
        val modelAt = IntArray(out.size) { -1 }
        for (i in tokens.indices) {
            val from = o.chars.substring(starts[i], ends[i])
            val to = c.chars.substring(map[starts[i]], map[ends[i]])
            if (from != to && !acceptable(from, to, frequency)) continue
            for (k in starts[i] until ends[i]) {
                out[k] = to[k - starts[i]]
                modelAt[k] = map[starts[i]] + k - starts[i]
            }
        }

        val text = StringBuilder(original.length + 8)
        t = 0
        for (k in out.indices) {
            if (k == ends[t]) t++
            if (k > 0) {
                val spacedBefore = k == starts[t]
                var spaced = spacedBefore
                // 둘 다 받아들인 글자 사이만 모델의 띄어쓰기를 본다. 되돌린 어절 둘레는 원문 그대로.
                // 'ㅋㅋ·ㅠㅠ' 같은 낱자 옆은 채팅 말투라 맞고 틀림이 없다 — 사용자가 쓴 대로 둔다.
                if (modelAt[k - 1] >= 0 && modelAt[k] >= 0 && !isJamo(out[k - 1]) && !isJamo(out[k])) {
                    val modelSpaced = c.spaceBefore(modelAt[k])
                    if (modelSpaced != spacedBefore) spaced = when (auxiliary(out, k)) {
                        Auxiliary.EITHER -> spacedBefore
                        Auxiliary.ATTACHED -> false
                        null -> modelSpaced
                    }
                }
                // 원문의 띄어쓰기를 그대로 쓰는 자리는 원문의 공백(두 칸이면 두 칸)을 옮긴다.
                if (spaced) text.append(if (spacedBefore) original.substring(tokens[t - 1].last + 1, tokens[t].first) else " ")
            }
            text.append(out[k])
        }
        val merged = text.toString()
        val lead = original.substring(0, tokens.first().first)
        val trail = original.substring(tokens.last().last + 1)
        return lead + merged + trail
    }

    /** 글자가 바뀐 어절을 받아들일까. */
    private fun acceptable(from: String, to: String, frequency: (String) -> Float?): Boolean {
        if (from.length != to.length) return false // 음절을 빼거나 더했다 — 딴 말이거나 부호를 뗐다
        val changed = from.indices.filter { from[it] != to[it] }
        val core = from.filter { it in '가'..'힣' }
        val edits = changed.map { edit(from[it], to[it], core.length) ?: return false } // 부호·숫자가 바뀌었거나 오타 방향이 아니다
        val seen = frequency(core) ?: return edits.size <= MAX_UNKNOWN_EDITS // 말뭉치가 모르는 말 — 오타일 가능성이 크다
        if (edits.size != 1) return false
        return when (edits[0]) {
            Edit.COMPLETION, Edit.VOWEL -> true
            Edit.FILL -> seen < ContextCorrector.CODA_SLIP_MAX_LN
            Edit.TENSE -> false
        }
    }

    private enum class Edit { TENSE, VOWEL, COMPLETION, FILL }

    /** 한 음절에서 자모 하나만, 사람이 실제로 내는 오타 방향으로 바뀌었으면 그 갈래. [syllables] 는 어절의 음절 수. */
    private fun edit(a: Char, b: Char, syllables: Int): Edit? {
        val x = Hangul.decompose(a) ?: return null
        val y = Hangul.decompose(b) ?: return null
        val changed = listOf(x.first != y.first, x.second != y.second, x.third != y.third).count { it }
        if (changed != 1) return null
        return when {
            x.first != y.first ->
                Edit.TENSE.takeIf { PLAIN_OF[Hangul.CHOSEONG[y.first]] == Hangul.CHOSEONG[x.first] }
            x.second != y.second ->
                Edit.VOWEL.takeIf { syllables >= 2 && Hangul.JUNGSEONG[x.second] in SAME_SOUND && Hangul.JUNGSEONG[y.second] in SAME_SOUND }
            (Hangul.JONGSEONG[x.third] to Hangul.JONGSEONG[y.third]) in COMPLETIONS -> Edit.COMPLETION
            x.third == 0 -> Edit.FILL
            else -> null
        }
    }

    /** 한 음절만 사람이 실제로 내는 오타 방향으로 바뀌었나(빈 받침 채우기는 빼고). */
    fun typoDirection(from: String, to: String): Boolean {
        if (from.length != to.length) return false
        val diff = from.indices.filter { from[it] != to[it] }
        if (diff.size != 1) return false
        val kind = edit(from[diff[0]], to[diff[0]], from.length)
        return kind != null && kind != Edit.FILL
    }

    private enum class Auxiliary { EITHER, ATTACHED }

    /**
     * 글자 [k] 앞이 '-아/-어' + 보조용언 자리인가. [Auxiliary.EITHER] 는 띄어도 붙여도 맞는 것(해 보다·해 주다 …),
     * [Auxiliary.ATTACHED] 는 늘 붙이는 것(만들어지다·좋아하다).
     *
     * '-ㄴ가/-나/-ㄹ까 보다'(바쁜가 봐요·하나 봐·올까 봐)와 '-다 보면' 은 '-아/-어' 가 아니라 띄는 것만 맞다 — 모델이 띄우면 받아들인다.
     */
    private fun auxiliary(out: CharArray, k: Int): Auxiliary? {
        val left = out[k - 1]
        val x = Hangul.decompose(left) ?: return null
        if (x.third != 0 || Hangul.JUNGSEONG[x.second] !in A_EO || left in NOT_A_EO) return null
        // '가' 앞이 관형사형(바쁜·힘든·있는)이면 '-ㄴ가' 다. 아니면 '가다'(가 봐·들어가 봐).
        if (left == '가' && k >= 2 && (out[k - 2] == '는' || Hangul.jongseongOf(out[k - 2]) == 'ㄴ')) return null
        val right = out[k]
        val next = out.getOrNull(k + 1)
        return when {
            right in ATTACHED_FIRST && !(next != null && "$right$next" in NOT_AUXILIARY) -> Auxiliary.ATTACHED
            right in EITHER_FIRST -> Auxiliary.EITHER
            next != null && "$right$next" in EITHER_TWO -> Auxiliary.EITHER
            else -> null
        }
    }

    private fun isJamo(ch: Char) = ch in 'ㄱ'..'ㆎ'

    /** 원문 글자 자리(경계) → 모델 글자 자리. 편집 거리로 맞춘다. */
    private fun boundaryMap(a: String, b: String): IntArray {
        val n = a.length
        val m = b.length
        if (a == b) return IntArray(n + 1) { it }
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        // 뒤에서부터 따라가며 원문 경계 i 에 모델 경계 j 를 적는다.
        val map = IntArray(n + 1)
        var i = n
        var j = m
        map[n] = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1) {
                i--; j--
                map[i] = j
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                i--
                map[i] = j
            } else {
                j--
            }
        }
        return map
    }

    private class Letters(val chars: String, private val positions: IntArray) {
        /** 글자 [index] 앞에 공백이 있었나. */
        fun spaceBefore(index: Int): Boolean = index > 0 && index < positions.size && positions[index] - positions[index - 1] > 1
    }

    /** 어절 자리. 공백은 [letters] 와 같은 기준([Char.isWhitespace] — 줄 바꿈 없는 공백도)으로 가른다. */
    private fun tokens(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for ((k, ch) in text.withIndex()) {
            if (!ch.isWhitespace()) {
                if (start < 0) start = k
            } else if (start >= 0) {
                out += start until k
                start = -1
            }
        }
        if (start >= 0) out += start until text.length
        return out
    }

    private fun letters(text: String): Letters {
        val chars = StringBuilder()
        val positions = ArrayList<Int>()
        for ((k, ch) in text.withIndex()) {
            if (ch.isWhitespace()) continue
            chars.append(ch)
            positions += k
        }
        return Letters(chars.toString(), positions.toIntArray())
    }

    /** 말뭉치가 모르는 어절에서 고칠 수 있는 음절 수. */
    private const val MAX_UNKNOWN_EDITS = 2

    private val PLAIN_OF = mapOf('ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ')
    private val SAME_SOUND = setOf('ㅐ', 'ㅔ', 'ㅙ', 'ㅚ', 'ㅞ')
    private val COMPLETIONS = setOf(
        'ㅅ' to 'ㅆ', 'ㄴ' to 'ㄶ', 'ㄴ' to 'ㄵ', 'ㄹ' to 'ㅀ', 'ㄹ' to 'ㄻ', 'ㄹ' to 'ㄼ',
        'ㄱ' to 'ㄲ', 'ㄱ' to 'ㄳ', 'ㅂ' to 'ㅄ', NO_JONG to 'ㅎ'
    )

    /** '-아/-어' 로 끝난 음절의 모음(해·봐·줘·려·와 …). */
    private val A_EO = setOf('ㅏ', 'ㅓ', 'ㅕ', 'ㅐ', 'ㅘ', 'ㅝ', 'ㅙ')

    /** 모음은 맞아도 '-아/-어' 가 아닌 끝: -나·-ㄹ까(보다), -다(보면), -서, -며. */
    private val NOT_A_EO = setOf('나', '까', '다', '서', '며')

    /** 띄어도 붙여도 맞는 보조용언의 첫 음절: 보다·주다·두다·놓다·내다·가다·오다·대다·있다. */
    private val EITHER_FIRST = setOf(
        '보', '봐', '봤', '볼', '본', '봄', '봅',
        '주', '줘', '줬', '줄', '준', '줌', '줍',
        '두', '둬', '뒀', '둘', '둔', '둠', '둡',
        '놓', '놔', '놨',
        '내', '낸', '낼', '냈', '냅',
        '가', '간', '갈', '갔', '갑',
        '오', '와', '왔', '올', '온', '옵',
        '대', '댄', '댈', '댔',
        '있'
    )

    /** 띄어도 붙여도 맞는 보조용언의 첫 두 음절: 드리다·버리다·계시다·치우다. */
    private val EITHER_TWO = setOf(
        "드리", "드려", "드릴", "드린", "드렸", "드립", "드림",
        "버리", "버려", "버릴", "버린", "버렸", "버립",
        "계시", "계셔", "계신", "계실", "계셨", "계십",
        "치우", "치워", "치웠", "치울", "치운"
    )

    /** 늘 붙이는 '-어지다·-아하다' 의 첫 음절. */
    private val ATTACHED_FIRST = setOf('지', '져', '졌', '진', '질', '집', '하', '해', '했', '할', '한', '합', '함')

    /** 첫 음절은 같아도 보조용언이 아닌 말. */
    private val NOT_AUXILIARY = setOf("지금", "지난", "하루", "하나", "한번", "한테", "한두", "해서")
}
