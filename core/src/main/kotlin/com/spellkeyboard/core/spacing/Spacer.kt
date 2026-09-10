package com.spellkeyboard.core.spacing

/**
 * 붙여 쓴 한 덩어리를 어절로 나눈다.
 *
 * ## 어떻게 동작하나
 *
 * mecab-ko-dic 의 형태소 사전과 연결비용 행렬로 비터비 탐색을 돌린다. 다만 연결비용
 * 행렬에는 **어절이라는 개념이 없다** — 형태소끼리 얼마나 잘 이어지는지만 안다.
 * 그래서 띄어쓰기는 문법 제약으로 강제한다.
 *
 * - 조사·어미·서술격조사·파생접미사는 어절을 **시작할 수 없다**. ("입니다" 홀로 못 선다)
 * - 용언 어간·선어말어미로 어절이 **끝날 수 없다**.
 * - 조사/어말어미/관형형어미/부사 뒤에 실질형태소가 오면 **반드시 띄어야 한다**.
 * - 체언 뒤에 용언 어간이 바로 오는 것도 한 어절이 될 수 없다. (파생은 XSV/XSA 로 태깅된다)
 *
 * 이 제약 안에서 연결비용이 가장 싼 분석을 고른다. 제약이 "띄어야 할 곳"을,
 * 비용이 "띄지 말아야 할 곳"을 담당한다. 둘 중 하나만으로는 안 된다 —
 * 제약만 쓰면 '감사합니다'를 '감사 합니다'로 쪼개고, 비용만 쓰면 아무 데도 안 띄운다.
 *
 * ## 자모 단위로 맞추는 이유
 *
 * '들어가신다' = 들어가(VV) + 시(EP) + ᆫ다(EF) 처럼 어미가 앞 음절의 종성으로
 * 녹아든다. 음절 단위로는 못 맞춘다. 그래서 입력과 사전 표층형을 모두 첫가끝
 * 자모로 펴서 비교한다.
 */
class Spacer(private val dictionary: SpacingDictionary) {

    private val cannotStart = tagSet(CANNOT_START)
    private val cannotEnd = tagSet(CANNOT_END)
    private val josa = tagSet(JOSA)
    private val eomi = tagSet(EOMI)
    private val adverbial = tagSet(ADVERBIAL)
    private val nominal = tagSet(NOMINAL)
    private val verbal = tagSet(VERBAL)
    private val content = tagSet(NOMINAL + VERBAL + ADVERBIAL + EXTRA_CONTENT)
    private val adnominalEnding = dictionary.tagId("ETM")
    private val nounEnding = dictionary.tagId("ETN")

    /** 격자의 한 칸. 같은 우문맥 id 로 도달하는 경로 중 가장 싼 것만 남긴다. */
    private class Node(
        val cost: Int,
        val fromPosition: Int,
        val fromContext: Int,
        val length: Int,
        val spacedBefore: Boolean
    )

    /**
     * [word] 를 어절로 나눈다.
     *
     * @return 띄어쓰기를 넣은 문자열. 나눌 것이 없거나 분석에 실패하면 null.
     */
    fun space(word: String): String? {
        if (word.length < MIN_SYLLABLES || word.length > MAX_SYLLABLES) return null
        if (word.none { it in HANGUL_SYLLABLES }) return null
        if (word.any { it.isWhitespace() }) return null

        val jamo = StringBuilder()
        val syllableBoundary = BooleanArray(word.length * 3 + 1)
        syllableBoundary[0] = true
        for (ch in word) {
            appendJamo(jamo, ch)
            syllableBoundary[jamo.length] = true
        }

        val bytes = jamo.toString().toByteArray(Charsets.UTF_8)
        val byteOffset = IntArray(jamo.length + 1)
        var b = 0
        for (i in jamo.indices) {
            byteOffset[i] = b
            b += utf8Length(jamo[i])
        }
        byteOffset[jamo.length] = b

        val lattice = arrayOfNulls<HashMap<Int, Node>>(jamo.length + 1)
        lattice[0] = hashMapOf(0 to Node(0, -1, -1, 0, false))

        for (i in 0 until jamo.length) {
            val here = lattice[i] ?: continue
            for (length in 1..MAX_MORPH_JAMO) {
                if (i + length > jamo.length) break
                val key = dictionary.find(bytes, byteOffset[i], byteOffset[i + length])
                if (key < 0) continue

                for (morph in dictionary.morphStart(key) until dictionary.morphEnd(key)) {
                    relax(here, lattice, i, length, morph, syllableBoundary)
                }
            }
        }

        val pieces = backtrack(lattice, jamo.length) ?: return null
        val spaced = render(word, pieces)
        return if (spaced == word) null else spaced
    }

    private fun relax(
        here: HashMap<Int, Node>,
        lattice: Array<HashMap<Int, Node>?>,
        at: Int,
        length: Int,
        morph: Int,
        syllableBoundary: BooleanArray
    ) {
        val head = dictionary.headTag(morph)
        val leftContext = dictionary.leftContext(morph)
        val rightContext = dictionary.rightContext(morph)
        val wordCost = dictionary.wordCost(morph)

        var best = Int.MAX_VALUE
        var bestFrom = -1
        var bestSpaced = false

        for ((previousContext, node) in here) {
            val previousTag =
                if (previousContext == 0) BOS_TAG else dictionary.tagOfRightContext(previousContext)

            val mayAttach: Boolean
            val maySpace: Boolean
            when {
                cannotStart[head] -> {
                    mayAttach = true; maySpace = false
                }
                previousTag == BOS_TAG || cannotEnd[previousTag] -> {
                    mayAttach = true; maySpace = false
                }
                mustSpace(previousTag, head) -> {
                    mayAttach = false; maySpace = true
                }
                else -> {
                    mayAttach = true; maySpace = true
                }
            }

            if (mayAttach) {
                val cost = node.cost +
                    dictionary.connectionCost(previousContext, leftContext) + wordCost
                if (cost < best) {
                    best = cost; bestFrom = previousContext; bestSpaced = false
                }
            }
            if (maySpace && at > 0 && syllableBoundary[at]) {
                val cost = node.cost +
                    dictionary.connectionCost(previousContext, 0) +
                    dictionary.connectionCost(0, leftContext) +
                    wordCost + SPACE_PENALTY
                if (cost < best) {
                    best = cost; bestFrom = previousContext; bestSpaced = true
                }
            }
        }
        if (bestFrom < 0) return

        val target = lattice[at + length] ?: HashMap<Int, Node>().also { lattice[at + length] = it }
        val existing = target[rightContext]
        if (existing == null || best < existing.cost) {
            target[rightContext] = Node(best, at, bestFrom, length, bestSpaced)
        }
    }

    /** 조사·어미·부사 뒤에 실질형태소가 오면 한 어절 안에 있을 수 없다. */
    private fun mustSpace(previousTag: Int, headTag: Int): Boolean {
        if (!content[headTag]) return false
        if (josa[previousTag] || eomi[previousTag]) return true
        if (previousTag == adnominalEnding || previousTag == nounEnding) return true
        if (adverbial[previousTag]) return true
        // 체언 바로 뒤의 용언 어간. '감사하다' 의 '하' 는 XSV 라 여기 걸리지 않는다.
        return nominal[previousTag] && verbal[headTag]
    }

    private fun backtrack(lattice: Array<HashMap<Int, Node>?>, end: Int): List<Pair<Int, Boolean>>? {
        val last = lattice[end] ?: return null
        var bestCost = Int.MAX_VALUE
        var bestContext = -1
        for ((context, node) in last) {
            if (cannotEnd[dictionary.tagOfRightContext(context)]) continue
            val cost = node.cost + dictionary.connectionCost(context, 0)
            if (cost < bestCost) {
                bestCost = cost; bestContext = context
            }
        }
        if (bestContext < 0) return null

        val pieces = ArrayList<Pair<Int, Boolean>>()
        var position = end
        var context = bestContext
        while (position > 0) {
            val node = lattice[position]?.get(context) ?: return null
            pieces.add(node.length to node.spacedBefore)
            position = node.fromPosition
            context = node.fromContext
        }
        pieces.reverse()
        return pieces
    }

    /** 자모 길이로 나온 조각을 원문 음절로 되돌린다. */
    private fun render(word: String, pieces: List<Pair<Int, Boolean>>): String {
        val out = StringBuilder()
        var index = 0
        for ((length, spacedBefore) in pieces) {
            if (spacedBefore && out.isNotEmpty()) out.append(' ')
            var consumed = 0
            while (consumed < length && index < word.length) {
                consumed += jamoLength(word[index])
                out.append(word[index])
                index++
            }
        }
        while (index < word.length) out.append(word[index++])
        return out.toString()
    }

    private fun tagSet(names: List<String>): BooleanArray {
        val flags = BooleanArray(TAG_CAPACITY)
        for (name in names) {
            val id = dictionary.tagId(name)
            if (id >= 0) flags[id] = true
        }
        return flags
    }

    companion object {
        /** 어절을 하나 더 만드는 값. 낮추면 잘게 쪼개고 높이면 붙여 쓴다. */
        private const val SPACE_PENALTY = 2000

        private const val MIN_SYLLABLES = 3
        private const val MAX_SYLLABLES = 24
        private const val MAX_MORPH_JAMO = 24
        private const val TAG_CAPACITY = 256

        /** 우문맥 id 0 은 문장 경계(BOS/EOS)다. */
        private const val BOS_TAG = -1

        private val HANGUL_SYLLABLES = '가'..'힣'

        private val JOSA = listOf("JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC")
        private val EOMI = listOf("EF", "EC")
        private val NOMINAL = listOf("NNG", "NNP", "NNB", "NR", "NP", "XR")
        private val VERBAL = listOf("VV", "VA", "VX", "VCN")
        private val ADVERBIAL = listOf("MAG", "MAJ", "MM", "IC")
        private val EXTRA_CONTENT = listOf("XPN", "SL", "SH", "SN")

        private val CANNOT_START =
            JOSA + EOMI + listOf("EP", "ETM", "ETN", "VCP", "XSV", "XSA", "XSN")
        private val CANNOT_END =
            VERBAL + listOf("VCP", "XSV", "XSA", "EP", "XPN")

        private const val CHO_BASE = 0x1100
        private const val JUNG_BASE = 0x1161
        private const val JONG_BASE = 0x11A8

        /** 음절을 첫가끝 자모로 펴서 [target] 에 붙인다. */
        fun appendJamo(target: StringBuilder, ch: Char) {
            if (ch !in HANGUL_SYLLABLES) {
                target.append(ch)
                return
            }
            val offset = ch.code - 0xAC00
            target.append((CHO_BASE + offset / 588).toChar())
            target.append((JUNG_BASE + (offset % 588) / 28).toChar())
            if (offset % 28 != 0) target.append((JONG_BASE + offset % 28 - 1).toChar())
        }

        /** 음절 하나가 자모 몇 개로 펴지는지. */
        fun jamoLength(ch: Char): Int {
            if (ch !in HANGUL_SYLLABLES) return 1
            return if ((ch.code - 0xAC00) % 28 == 0) 2 else 3
        }

        private fun utf8Length(ch: Char): Int = when {
            ch.code < 0x80 -> 1
            ch.code < 0x800 -> 2
            else -> 3
        }
    }
}
