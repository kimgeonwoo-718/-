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
    private val suffix = tagSet(SUFFIX)
    private val endingTag = tagSet(EOMI + listOf("EP", "ETM", "ETN"))
    private val verbalish = tagSet(VERBAL + listOf("VCP", "XSV", "XSA", "EP"))
    private val particleOrEnding = tagSet(JOSA + EOMI + listOf("EP", "ETM", "ETN", "VCP"))
    private val nominalish = tagSet(NOMINAL + EXTRA_CONTENT)
    private val connectiveEnding = dictionary.tagId("EC")
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
        val spacedBefore: Boolean,
        val morph: Int
    )

    /**
     * [word] 를 어절로 나눈다.
     *
     * @return 띄어쓰기를 넣은 문자열. 나눌 것이 없거나 분석에 실패하면 null.
     */
    fun space(word: String): String? {
        if (word.length < MIN_SYLLABLES) return null
        val analysis = analyze(word, allowSpaces = true) ?: return null
        val spaced = render(word, analysis)
        return if (spaced == word) null else spaced
    }

    /**
     * 이 어절이 형태소 분석기 눈에 얼마나 자연스러운지.
     *
     * 낮을수록 자연스럽다. 분석 자체가 안 되면 [UNANALYZABLE].
     * 맞춤법 후보를 고를 때 이 값을 비교한다.
     */
    fun cost(word: String): Int =
        analyzeCost(word, allowSpaces = false) ?: UNANALYZABLE

    private fun analyze(word: String, allowSpaces: Boolean): List<Pair<Int, Boolean>>? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        if (word.none { it in HANGUL_SYLLABLES }) return null
        if (word.any { it.isWhitespace() }) return null

        return runAnalysis(word, allowSpaces)?.first
    }

    /**
     * 한 어절로 본 형태소 분석 결과를 "자모표층/품사" 로 늘어놓는다. 진단·조정용.
     * 한 어절로 분석이 안 되면 null.
     */
    fun describe(word: String): String? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        val jamo = StringBuilder().also { sb -> word.forEach { appendJamo(sb, it) } }.toString()
        var at = 0
        val parts = pieces.map { (length, morph) ->
            val surface = jamo.substring(at, at + length)
            at += length
            "$surface/" + dictionary.tagName(dictionary.headTag(morph)) +
                (if (dictionary.tailTag(morph) != dictionary.headTag(morph)) "+" + dictionary.tagName(dictionary.tailTag(morph)) else "")
        }
        return parts.joinToString(" ") + " ($cost)"
    }

    /**
     * [word] 를 한 어절로 분석했을 때 [syllableIndex] 번째 음절 첫머리에서 **매인 형태소**
     * (조사·어미·접미사·서술격조사처럼 홀로 어절을 시작할 수 없는 것)가 시작하는가.
     *
     * '안녕 하세요' 를 합쳐도 되는지 판단할 때 쓴다 — '안녕하세요' 에서 둘째 어절이 붙던
     * 자리에 접미사 '하'(XSV)가 시작하면 원래 띄어 쓸 수 없던 자리다. 한 어절로 분석이
     * 안 되면 null.
     */
    fun boundMorphemeStartsAt(word: String, syllableIndex: Int): Boolean? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES || syllableIndex !in 1 until word.length) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        // 한 어절로 억지로 분석하면 '열어봐' 가 열/VV 어/EP 봐/EC 처럼 엉뚱한 품사로 맞춰진다.
        // 형태소 하나당 비용이 높으면 그런 억지 분석이라 믿지 않는다.
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return null
        var target = 0
        for (i in 0 until syllableIndex) target += jamoLength(word[i])
        var at = 0
        for ((length, morph) in pieces) {
            if (at == target) return cannotStart[dictionary.headTag(morph)]
            if (at > target) return false
            at += length
        }
        return false
    }

    /**
     * [word] 가 한 어절로 무리 없이 분석되는가 — 분석이 되고, 형태소당 비용이 믿을 만한 범위인가.
     * '연습문제'(명사+명사)는 그렇고, '할수있다'(비용 11000)는 아니다.
     */
    fun isWellFormed(word: String): Boolean {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return false
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return false
        return cost <= BOUND_TRUST_COST_PER_MORPH * pieces.size
    }

    /**
     * [surface] 가 사전에서 매인 형태소(조사·어미·접미사)로도 읽힐 수 있는가.
     * 한 음절짜리 '가'·'이'·'지' 는 낱말로도 조사·어미로도 읽히는데, 어절을 잘라서
     * 그런 조각을 새로 만드는 것은 거의 언제나 틀렸다.
     */
    fun couldBeBound(surface: String): Boolean {
        val jamo = StringBuilder().also { sb -> surface.forEach { appendJamo(sb, it) } }.toString()
        val bytes = jamo.toByteArray(Charsets.UTF_8)
        val key = dictionary.find(bytes, 0, bytes.size)
        if (key < 0) return false
        for (morph in dictionary.morphStart(key) until dictionary.morphEnd(key)) {
            // 접미사(XSN 등)는 뺀다 — '수'·'번'은 접미사로도 읽히지만 의존명사로 홀로 서는 게 흔하다.
            if (particleOrEnding[dictionary.headTag(morph)]) return true
        }
        return false
    }

    /**
     * 한 어절로 분석한 [word] 에서 [syllableIndex] 번째 음절 첫머리에 시작하는 형태소의 품사.
     * 거기서 시작하는 형태소가 없거나, 분석이 안 되거나 믿기 어려우면 null.
     */
    fun tagAt(word: String, syllableIndex: Int): String? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES || syllableIndex !in 1 until word.length) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return null
        var target = 0
        for (i in 0 until syllableIndex) target += jamoLength(word[i])
        var at = 0
        for ((length, morph) in pieces) {
            if (at == target) return dictionary.tagName(dictionary.headTag(morph))
            if (at > target) return null
            at += length
        }
        return null
    }

    /** 용언 품사인가(동사·형용사·보조용언). */
    fun isVerbTag(tag: String): Boolean = tag in VERBAL

    /**
     * 한 어절로 분석한 [word] 의 첫 형태소 품사와 끝 형태소 품사. 어절을 자른 두 조각이
     * 명사|명사 로 맞닿는지('연습|문제') 볼 때 쓴다. 분석이 안 되거나 믿기 어려우면 null.
     */
    fun edgeTags(word: String): Pair<String, String>? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return null
        return dictionary.tagName(dictionary.headTag(pieces.first().second)) to
            dictionary.tagName(dictionary.tailTag(pieces.last().second))
    }

    /** 체언(과 관형사) 품사인가. [edgeTags] 결과를 볼 때 쓴다. 이름 조각 앞뒤의 '이·그' 도 체언처럼 다룬다. */
    fun isNominalTag(tag: String): Boolean = tag in NOMINAL || tag == "MM"

    /**
     * [word] 가 매인 형태소(조사·어미·접미사)로 시작하는가. '로', '이라는', '들이' 처럼
     * 홀로 어절이 될 수 없는 것을 가려낼 때 쓴다. 분석이 안 되면 null.
     */
    fun startsWithBoundMorpheme(word: String): Boolean? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        val (pieces, _) = runAnalysisWithMorphs(word) ?: return null
        return cannotStart[dictionary.headTag(pieces.first().second)]
    }

    private fun runAnalysisWithMorphs(word: String): Pair<List<Pair<Int, Int>>, Int>? {
        val lattice = buildLattice(word, allowSpaces = false) ?: return null
        val end = lattice.size - 1
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
        val pieces = ArrayList<Pair<Int, Int>>()
        var position = end
        var context = bestContext
        while (position > 0) {
            val node = lattice[position]?.get(context) ?: return null
            pieces.add(node.length to node.morph)
            position = node.fromPosition
            context = node.fromContext
        }
        pieces.reverse()
        return pieces to bestCost
    }

    private fun runAnalysis(
        word: String,
        allowSpaces: Boolean
    ): Pair<List<Pair<Int, Boolean>>, Int>? {
        val lattice = buildLattice(word, allowSpaces) ?: return null
        return backtrack(lattice, lattice.size - 1, allowSpaces)
    }

    private fun buildLattice(word: String, allowSpaces: Boolean): Array<HashMap<Int, Node>?>? {
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
        lattice[0] = hashMapOf(0 to Node(0, -1, -1, 0, false, -1))

        for (i in 0 until jamo.length) {
            val here = lattice[i] ?: continue
            for (length in 1..MAX_MORPH_JAMO) {
                if (i + length > jamo.length) break
                val key = dictionary.find(bytes, byteOffset[i], byteOffset[i + length])
                if (key < 0) continue

                val attachableVerb = ATTACHABLE_STEMS.any { jamo.startsWith(it, i) }
                for (morph in dictionary.morphStart(key) until dictionary.morphEnd(key)) {
                    relax(here, lattice, i, length, morph, syllableBoundary, allowSpaces, attachableVerb)
                }
            }
        }

        return lattice
    }

    /** [analyze] 와 같은 탐색이지만 경로 대신 비용만 돌려준다. */
    private fun analyzeCost(word: String, allowSpaces: Boolean): Int? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        if (word.none { it in HANGUL_SYLLABLES }) return null
        if (word.any { it.isWhitespace() }) return null
        return runAnalysis(word, allowSpaces)?.second
    }

    private fun relax(
        here: HashMap<Int, Node>,
        lattice: Array<HashMap<Int, Node>?>,
        at: Int,
        length: Int,
        morph: Int,
        syllableBoundary: BooleanArray,
        allowSpaces: Boolean,
        attachableVerb: Boolean
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
                // 접미사는 체언에만 붙는다. 부사 뒤의 접미사('안'+'되'(XSV))를 허용하면
                // '안되요' 가 한 어절로 분석돼 버려서 "말이 안 되는 붙여쓰기" 를 못 가려낸다.
                previousTag >= 0 && suffix[head] && !nominalish[previousTag] -> continue
                // 어미는 용언(과 서술격조사·선어말어미) 뒤에만 온다. 조사 뒤의 어미('햇'+'는'+'대요')를
                // 허용하면 오타가 멀쩡한 어절로 분석된다.
                previousTag >= 0 && endingTag[head] && !verbalish[previousTag] -> continue
                cannotStart[head] -> {
                    mayAttach = true; maySpace = false
                }
                previousTag == BOS_TAG || cannotEnd[previousTag] -> {
                    mayAttach = true; maySpace = false
                }
                mustSpace(previousTag, head, allowSpaces, attachableVerb) -> {
                    // 띄어 쓸 수 없는 상황이면 이 경로 자체가 성립하지 않는다.
                    mayAttach = false; maySpace = allowSpaces
                }
                else -> {
                    mayAttach = true; maySpace = allowSpaces
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
            target[rightContext] = Node(best, at, bestFrom, length, bestSpaced, morph)
        }
    }

    /**
     * 조사·어미·부사 뒤에 실질형태소가 오면 한 어절 안에 있을 수 없다.
     *
     * 예외 하나: 띄어쓰기를 넣지 않는 분석([allowSpaces] 가 false — "이게 한 어절로 말이
     * 되나"를 묻는 것)에서는 연결어미 뒤의 용언('남아있다', '해두고', '넘겨받다')을 붙여 둔다.
     * 보조용언은 띄는 것이 원칙이지만 붙이는 것도 허용이고 합성동사는 붙이는 게 맞아서,
     * 사람이 붙여 쓴 것을 굳이 쪼개면 안 된다. 덩어리를 나눌 때([allowSpaces])는 원칙대로 띄운다.
     */
    private fun mustSpace(previousTag: Int, headTag: Int, allowSpaces: Boolean, attachableVerb: Boolean): Boolean {
        if (!content[headTag]) return false
        // 연결어미 뒤의 용언도 마찬가지 — 합성동사('넘겨받다', '들어가다')거나 보조용언이다.
        if (!allowSpaces && previousTag == connectiveEnding && verbal[headTag]) return false
        if (josa[previousTag] || eomi[previousTag]) return true
        if (previousTag == adnominalEnding || previousTag == nounEnding) return true
        if (adverbial[previousTag]) return true
        // 체언 바로 뒤의 용언 어간. '감사하다' 의 '하' 는 XSV 라 여기 걸리지 않는다.
        // '받다·당하다·시키다·드리다' 는 체언에 붙어 한 낱말이 된다('발급받다', '고소당하다').
        return nominal[previousTag] && verbal[headTag] && !attachableVerb
    }

    private fun backtrack(
        lattice: Array<HashMap<Int, Node>?>,
        end: Int,
        @Suppress("UNUSED_PARAMETER") allowSpaces: Boolean
    ): Pair<List<Pair<Int, Boolean>>, Int>? {
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
        return pieces to bestCost
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

        /** 분석이 아예 안 될 때의 비용. 어떤 실제 비용보다도 크다. */
        const val UNANALYZABLE = 1_000_000

        /** [boundMorphemeStartsAt] 이 분석을 믿는 형태소당 비용 상한. */
        private const val BOUND_TRUST_COST_PER_MORPH = 2000

        private const val MIN_SYLLABLES = 3
        private const val MAX_SYLLABLES = 24
        private const val MAX_MORPH_JAMO = 24
        private const val TAG_CAPACITY = 256

        /** 우문맥 id 0 은 문장 경계(BOS/EOS)다. */
        private const val BOS_TAG = -1

        private val HANGUL_SYLLABLES = '가'..'힣'

        private val JOSA = listOf("JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC")
        private val EOMI = listOf("EF", "EC")
        // XSN(명사 파생 접미사)은 체언 끝에 붙으므로 뒤에 오는 것에 대해서는 체언과 같다.
        // 이게 빠지면 '할수있다' 가 할(NNG)+수(XSN)+있다 로 한 어절이 돼 버린다.
        private val NOMINAL = listOf("NNG", "NNP", "NNB", "NNBC", "NR", "NP", "XR", "XSN")
        private val VERBAL = listOf("VV", "VA", "VX", "VCN")
        private val ADVERBIAL = listOf("MAG", "MAJ", "MM", "IC")
        private val EXTRA_CONTENT = listOf("XPN", "SL", "SH", "SN")

        private val CANNOT_START =
            JOSA + EOMI + listOf("EP", "ETM", "ETN", "VCP", "XSV", "XSA", "XSN")
        private val CANNOT_END =
            VERBAL + listOf("VCP", "XSV", "XSA", "EP", "XPN")
        private val SUFFIX = listOf("XSN", "XSV", "XSA")

        /** 체언 뒤에 붙어 한 낱말을 이루는 용언 어간(첫가끝 자모). */
        private val ATTACHABLE_STEMS = listOf("받", "당하", "시키", "드리").map { stem ->
            StringBuilder().also { sb -> stem.forEach { appendJamo(sb, it) } }.toString()
        }

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
