package com.spellkeyboard.core.spacing

import com.spellkeyboard.core.hangul.Hangul

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
        // 겹조사 뒤의 '의' 는 앞에 붙는다('유가족으로부터 의', '타깃층에서 의' — 사전이 조사 둘의 잇기 값을 높게 매겨 띄웠다).
        val spaced = render(word, analysis).replace(LONE_GENITIVE, "$1의")
        return if (spaced == word) null else spaced
    }


    /**
     * 아주 긴 덩어리를 푼다. 공백을 **아예 안 친** 글을 위한 것이다.
     *
     * [space] 는 [MAX_SYLLABLES] 음절까지만 본다. 격자가 음절 수에 따라 커져서 그 위로는
     * 실시간 입력을 막기 때문이다. 그런데 빨리 치느라 띄어쓰기를 통째로 생략한 글은
     * 30~50음절로 들어온다 — 그게 진짜 쓰임인데 [space] 는 그런 글에 null 을 준다.
     *
     * 여기서는 창을 앞에서부터 밀며 푼다. 창 끝에 걸친 조각은 잘렸을 수 있으니
     * **내보내지 않고 다음 창으로 넘긴다.**
     *
     * @return 띄운 결과. 한 군데도 못 띄우면 null.
     */
    fun spaceLong(word: String): String? {
        if (word.length <= MAX_SYLLABLES) return space(word)

        val out = ArrayList<String>()
        var rest = word
        while (rest.length > MAX_SYLLABLES) {
            val pieces = firstSplit(rest.substring(0, MAX_SYLLABLES))
            // 이 창을 어떻게도 못 나누면 더 밀어 봐야 같은 답이다. 남은 것을 그대로 둔다.
            if (pieces == null) break
            // 마지막 조각은 창 끝에서 잘렸을 수 있다. 되돌려 다음 창에 붙인다.
            out += pieces.dropLast(1)
            rest = pieces.last() + rest.substring(MAX_SYLLABLES)
        }
        out += space(rest)?.split(' ') ?: listOf(rest)

        val spaced = out.joinToString(" ")
        return if (spaced == word) null else spaced
    }

    /**
     * 창 하나를 나눈다. 통째로는 안 나뉘어도 앞쪽만 잘라 보면 나뉘는 일이 있어서
     * ('...했으니일' 처럼 끝이 어중간할 때) 창을 줄여 가며 시도한다.
     *
     * 반드시 **두 조각 이상**을 돌려준다 — 한 조각이면 창을 밀 수가 없어 제자리걸음이다.
     */
    private fun firstSplit(window: String): List<String>? {
        var length = window.length
        while (length >= MIN_SYLLABLES * 2) {
            val pieces = space(window.substring(0, length))?.split(' ')
            if (pieces != null && pieces.size >= 2) {
                // 줄여서 푼 경우, 잘라 낸 꼬리를 마지막 조각에 도로 붙인다.
                if (length == window.length) return pieces
                return pieces.dropLast(1) + (pieces.last() + window.substring(length))
            }
            length--
        }
        return null
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

    /** [word] 가 사전에 통째로 **고유명사**로만 올라 있는가('이정' — 사람 이름). 일반명사로도 오르면 아니다. */
    fun isLexiconProperNoun(word: String): Boolean {
        if (word.isEmpty() || word.length > MAX_SYLLABLES || word.any { it !in HANGUL_SYLLABLES }) return false
        val jamo = StringBuilder().also { sb -> word.forEach { appendJamo(sb, it) } }.toString()
        val bytes = jamo.toByteArray(Charsets.UTF_8)
        val key = dictionary.find(bytes, 0, bytes.size)
        if (key < 0) return false
        var proper = false
        for (morph in dictionary.morphStart(key) until dictionary.morphEnd(key)) {
            val head = dictionary.headTag(morph)
            if (dictionary.tailTag(morph) != head) continue
            if (head == commonNoun) return false
            if (head == properNoun) proper = true
        }
        return proper
    }

    /**
     * [word] 가 사전에 **통째로** 일반명사·고유명사로 올라 있는가('발간', '지바', '허재', '여수'). 조사가 붙은 꼴은 안 본다 — 부르는 쪽이 뗀다.
     *
     * 문맥 교정기가 멀쩡한 낱말을 흔한 딴 말로 바꾸지 못하게 할 때 쓴다(2026-10-13). mecab-ko-dic 에는 고유명사만 39만 개가 있어서
     * 언어모델(말뭉치)이 모르는 이름·지명도 여기서는 안다.
     */
    fun isLexiconNoun(word: String): Boolean {
        if (word.isEmpty() || word.length > MAX_SYLLABLES || word.any { it !in HANGUL_SYLLABLES }) return false
        val jamo = StringBuilder().also { sb -> word.forEach { appendJamo(sb, it) } }.toString()
        val bytes = jamo.toByteArray(Charsets.UTF_8)
        val key = dictionary.find(bytes, 0, bytes.size)
        if (key < 0) return false
        for (morph in dictionary.morphStart(key) until dictionary.morphEnd(key)) {
            val head = dictionary.headTag(morph)
            if ((head == commonNoun || head == properNoun) && dictionary.tailTag(morph) == head) return true
        }
        return false
    }

    /**
     * [word] 를 한 어절로 읽은 분석이 **명사(와 접두·접미사)만 이어지고 끝에 조사만** 붙은 꼴인가 — 이름·합성어 모양
     * ('대한상공회의소는', '챔피언스리그에서의', '국토교통위'). 동사·형용사·부사·어미·의존명사가 끼면 아니다 — 붙여 친 글은 대개
     * 여기 든다('그때보자', '친구만나서'). 분석값은 따지지 않는다(긴 이름은 값이 높다).
     */
    fun isNounChunk(word: String): Boolean {
        if (word.length < 2 || word.length > MAX_SYLLABLES) return false
        val (pieces, _) = runAnalysisWithMorphs(word) ?: return false
        var nouns = 0
        var particles = false
        for ((_, morph) in pieces) {
            val head = dictionary.tagName(dictionary.headTag(morph))
            val tail = dictionary.tagName(dictionary.tailTag(morph))
            if (head.startsWith("J") && tail.startsWith("J")) {
                particles = true
                continue
            }
            if (particles || head !in NOUNISH_TAGS || tail !in NOUNISH_TAGS) return false
            nouns++
        }
        return nouns >= 2
    }

    /**
     * [word] 를 한 어절로 읽은 분석에서 [p] 음절 자리가 형태소 한가운데인가 — 분석값은 따지지 않는다('끝납니다' 는 끝납니다/VV+EF 한
     * 형태소인데 값이 높다). 분석이 안 되면 null.
     */
    fun insideMorpheme(word: String, p: Int): Boolean? {
        if (p <= 0 || p >= word.length || word.length > MAX_SYLLABLES) return null
        val (pieces, _) = runAnalysisWithMorphs(word) ?: return null
        var jamo = 0
        for (i in 0 until p) jamo += jamoLength(word[i])
        var at = 0
        for ((length, _) in pieces) {
            if (at == jamo) return false
            if (at > jamo) return true
            at += length
        }
        return at != jamo
    }

    /**
     * [word] 를 한 어절로 읽은 분석에서 음절 자리마다, 그 자리가 **사전 명사(일반·고유) 한가운데**면 그 명사가 덮는 음절 수(아니면 0).
     * 분석값은 따지지 않는다 — 드문 이름('보츠와나서' 의 보츠와나)은 값이 높다. 부르는 쪽이 값에 따라 문턱을 달리 둔다.
     * 분석이 안 되면 null.
     */
    fun nounSpans(word: String): IntArray? {
        if (word.length < 2 || word.length > MAX_SYLLABLES) return null
        val (pieces, _) = runAnalysisWithMorphs(word) ?: return null
        val starts = IntArray(word.length + 1)
        for (i in word.indices) starts[i + 1] = starts[i] + jamoLength(word[i])
        val out = IntArray(word.length + 1)
        var at = 0
        for ((length, morph) in pieces) {
            val head = dictionary.headTag(morph)
            if ((head == commonNoun || head == properNoun) && dictionary.tailTag(morph) == head) {
                val end = at + length
                val covered = (0 until word.length).count { starts[it] >= at && starts[it + 1] <= end }
                for (p in 1 until word.length) if (starts[p] > at && starts[p] < end) out[p] = covered
            }
            at += length
        }
        return out
    }

    private val commonNoun = dictionary.tagId("NNG")
    private val properNoun = dictionary.tagId("NNP")

    /**
     * 한 어절로 분석한 [word] 에서 [syllableIndex] 번째 음절 앞이 **형태소 경계**면 (앞 형태소의 끝 품사, 뒤 형태소의 첫 품사).
     * 경계가 아니면 빈 쌍("" to ""). 분석이 안 되거나 믿기 어려우면 null.
     *
     * 나눈 자리가 낱말 한가운데('피렌|체의')인지, 조사 뒤('피렌체의|밤')인지 가를 때 쓴다. 조각을 따로 분석하면 '시에만' 의 '시' 가
     * 일반명사로 읽히는데, 통째로 읽으면 의존명사다('리뷰이벤트시에만' = 리뷰/이벤트/시/에/만).
     */
    fun boundaryTags(word: String, syllableIndex: Int): Pair<String, String>? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES || syllableIndex !in 1 until word.length) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return null
        var target = 0
        for (i in 0 until syllableIndex) target += jamoLength(word[i])
        var at = 0
        var previous = -1
        for ((length, morph) in pieces) {
            if (at == target && previous >= 0) {
                return dictionary.tagName(dictionary.tailTag(previous)) to dictionary.tagName(dictionary.headTag(morph))
            }
            if (at > target) return "" to ""
            at += length
            previous = morph
        }
        return "" to ""
    }

    /**
     * 한 어절로 분석한 [word] 의 음절 자리마다 [boundaryTags] 의 답(길이 word.length + 1, 안쪽 자리만 채운다). 형태소 경계면
     * (앞 형태소의 끝 품사, 뒤 형태소의 첫 품사), 형태소 한가운데면 null. 음절마다 분석을 되풀이하지 않고 한 번으로 낸다.
     * 분석이 안 되거나 믿기 어려우면 null.
     */
    fun junctionTags(word: String): Array<Pair<String, String>?>? = syllableTags(word)?.first

    /**
     * [junctionTags] 와 같은 분석에서 (경계 자리의 품사, **형태소 한가운데인** 자리마다 그 형태소의 (첫 품사, 끝 품사)).
     * 둘째는 '국가대표팀에|서는'(에서/JKB)·'상징으로|서의'(으로서/JKB) 처럼 조사 한가운데를 자르는지 볼 때 쓴다.
     */
    fun syllableTags(word: String): Pair<Array<Pair<String, String>?>, Array<Pair<String, String>?>>? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return null
        val tagsAt = HashMap<Int, Pair<String, String>>()
        // 형태소마다 (자모 시작, 자모 끝, 형태소).
        val spans = ArrayList<Triple<Int, Int, Int>>()
        var at = 0
        var previous = -1
        for ((length, morph) in pieces) {
            if (previous >= 0) tagsAt[at] = dictionary.tagName(dictionary.tailTag(previous)) to dictionary.tagName(dictionary.headTag(morph))
            spans += Triple(at, at + length, morph)
            at += length
            previous = morph
        }
        val junctions = arrayOfNulls<Pair<String, String>>(word.length + 1)
        val inside = arrayOfNulls<Pair<String, String>>(word.length + 1)
        var jamo = 0
        for (i in 0 until word.length) {
            if (i > 0) {
                junctions[i] = tagsAt[jamo]
                if (junctions[i] == null) {
                    spans.firstOrNull { it.first < jamo && jamo < it.second }?.let {
                        inside[i] = dictionary.tagName(dictionary.headTag(it.third)) to dictionary.tagName(dictionary.tailTag(it.third))
                    }
                }
            }
            jamo += jamoLength(word[i])
        }
        return junctions to inside
    }

    /**
     * [before]|[after] 가 **체언·어근 + '-하다'** 한 낱말을 가른 자리인가('문송|합니다', '옴짝달싹|하지', '티격태격|하지만').
     * '-하다' 는 접미사라 앞말에 붙여 쓴다. 부사 뒤의 '하다'('같이 하자', '안 해')는 딴 낱말이라 뗀다 — 다만 네 음절 이상인
     * 흉내말 한 낱말('옴짝달싹', '티격태격')에는 '-하다' 가 붙는다. 때를 나타내는 명사('오늘 하자')는 부르는 쪽이 거른다.
     */
    fun derivesWithHada(before: String, after: String): Boolean {
        if (before.isEmpty() || after.isEmpty() || after[0] !in HADA_SYLLABLES) return false
        val head = edgeTags(after)?.first ?: return false
        if (head != "VV" && head != "XSV" && head != "XSA" && head != "VX") return false
        // 분석이 안 되는 앞말은 대개 신조어·이름 같은 체언이다('문송').
        val left = edgeTags(before) ?: return true
        // 형용사 + '-어하다'('아쉬워|하였기에', '귀여워|하고', '좋아|해')는 한 낱말이다.
        if (left.first == "VA" && left.second == "EC" && after[0] in "하해했" && endsWithA(before.last())) return true
        return when (left.second) {
            "NNG", "NNP", "XR" -> true
            "MAG" -> before.length >= 4 && firstMorpheme(before)?.first == before.length && before.last() !in "이히로게"
            else -> false
        }
    }

    /** 음절이 '-아/-어' 로 끝나는가(모음 ㅏ·ㅓ·ㅝ·ㅘ·ㅕ·ㅐ, 받침 없음) — '아쉬워', '기뻐', '좋아', '미워'. */
    private fun endsWithA(c: Char): Boolean {
        val (_, jung, jong) = Hangul.decompose(c) ?: return false
        return jong == 0 && Hangul.JUNGSEONG[jung] in "ㅏㅓㅝㅘㅕㅐ"
    }

    /** 용언 품사인가(동사·형용사·보조용언). */
    fun isVerbTag(tag: String): Boolean = tag in VERBAL

    /**
     * [word] 에서 문장이 끝나는가.
     *
     * 한국어 채팅은 마침표를 거의 안 쓰는데, 번역기에 여러 문장을 한 번에 넘기면 뭉개진다.
     * 그래서 부호 없이도 문장 끝을 찾아야 한다.
     *
     * mecab-ko-dic 은 종결어미를 대부분 **연결어미와 같은 EC 로** 태깅한다 ('좋아요' 도
     * '좋아서' 도 EC 다). 그래서 태그만으로는 못 가린다. 태그가 EF 면 그대로 믿고,
     * EC 면 어미의 표층형을 본다 — [FINAL_TAILS] 는 연결형에 거의 안 나타나는 종결 꼬리다.
     * 어절이 조사나 명사로 끝나면('기분이', '오늘') 문장 중간이므로 자르지 않는다.
     *
     * 못 잡는 것도 있다. 반말 종결('먹었어', '그래')은 연결형('좋아 보여')과 꼬리가 같아
     * 일부러 뺐다 — 문장 둘이 붙는 손해가, 한 문장을 잘못 자르는 손해보다 작다.
     */
    fun endsWithFinalEnding(word: String): Boolean {
        val tail = edgeTags(word)?.second ?: return false
        if (tail == "EF") return true
        if (tail != "EC") return false
        return FINAL_TAILS.any { word.endsWith(it) }
    }

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

    /**
     * [word] 를 한 어절로 분석했을 때 **첫 형태소가 덮는 음절 수와 그 품사**('고새하셨어요' → 2 to "NNG"). 첫 형태소가 음절 한가운데서
     * 끝나면('했' = 하+ㅆ) 그 음절까지 센다. 분석이 안 되거나 믿기 어려우면 null. 분석을 한 번만 한다 — [boundaryTags] 를 음절마다 부르면
     * 그만큼 분석을 되풀이한다.
     */
    fun firstMorpheme(word: String): Pair<Int, String>? {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return null
        val (pieces, cost) = runAnalysisWithMorphs(word) ?: return null
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return null
        val (length, morph) = pieces.first()
        var jamo = 0
        var syllables = 0
        while (syllables < word.length && jamo < length) {
            jamo += jamoLength(word[syllables])
            syllables++
        }
        return syllables to dictionary.tagName(dictionary.headTag(morph))
    }

    /**
     * 체언 [before] 뒤에 붙여 친 [after] 가 **합성어의 뒷부분일 수 없는** 말인가 — 그러면 명사|명사 자리라도 띄운다.
     *
     * - 자리·때를 가리키는 명사에 조사만 붙은 꼴: '회사|앞에서', '오늘|안에', '아는 사람|중에', '학생|등'
     * - 명사에 '-하다·-되다' 가 붙은 용언: '감기|조심해', '입금|확인되면', '추후|공지하겠습니다'
     *
     * 명사|명사는 합성어·이름이라 도로 붙이는데(2026-10-13), 그 막이가 이것들까지 붙여 붙여 쓴 글 되살림이 떨어졌다
     * (공백을 다 지운 시험지 F1 93.9 → 89.9). 앞말과 합쳐 사전에 오른 낱말('눈앞', '집안', '한밤중', '자기소개')은 붙여 둔다.
     */
    fun startsApartFromNoun(before: String, after: String): Boolean {
        if (before.isEmpty() || after.isEmpty() || after.length > MAX_SYLLABLES) return false
        // 뒤가 관형사('학생등|여러 사람이'). 사전은 '여러' 한 낱말에도 높은 값을 매겨 분석을 못 믿는다고 해서 글자로 본다.
        if (after in APART_DETERMINERS) return true
        // 앞이 합성어 머리가 못 되는 의존명사('공부하는 데|방해가', '온 지|얼마'). 뒤가 멀쩡히 읽히는 말일 때만.
        if (before in APART_BOUND_NOUNS) return edgeTags(after) != null
        val (pieces, cost) = runAnalysisWithMorphs(after) ?: return false
        if (cost > BOUND_TRUST_COST_PER_MORPH * pieces.size) return false
        val tags = pieces.map { dictionary.tagName(dictionary.headTag(it.second)) }
        val head = firstMorpheme(after)?.first ?: return false
        val stem = after.substring(0, head)
        if (isLexiconNoun(before + stem)) return false
        val rest = tags.drop(1)
        return when {
            stem in APART_NOUNS && (tags[0] == "NNG" || tags[0] == "NNB") -> rest.all { it.startsWith("J") }
            tags[0] == "NNG" && stem.length >= 2 -> rest.isNotEmpty() && (rest[0] == "XSV" || rest[0] == "XSA")
            else -> false
        }
    }

    /**
     * 관형사 [before] 가 뒷말 [after] 의 첫 형태소와 합쳐 **사전에 오른 한 낱말**이 되는가 — '그|날은'(그날), '이|번에'(이번),
     * '그|중에'(그중), '이|곳은'(이곳). 그러면 뗄 자리가 아니다. '이|사람' 은 '이사' 가 낱말이어도 첫 형태소가 '사람' 이라 뗀다.
     */
    fun joinsDeterminer(before: String, after: String): Boolean {
        if (before.isEmpty() || before.length > 2 || after.isEmpty()) return false
        val head = firstMorpheme(after)?.first ?: return false
        val word = before + after.substring(0, head)
        return isLexiconNoun(word) || edgeTags(word)?.let { it.first == "NP" && it.second == "NP" } == true
    }

    /**
     * [word] 를 한 어절로 읽은 최선의 분석이 **고유명사만으로** 이뤄졌는가('배스킨라빈스' = 배스킨/라빈/스). 분석값이 높아 다른 데서는
     * 못 믿는다고 버리는 분석이라도, 조사·어미 하나 없이 이름 조각만 나왔다면 통째로 이름이다 — 가를 자리가 없다(2026-10-14).
     */
    fun isProperNounChunk(word: String): Boolean {
        if (word.isEmpty() || word.length > MAX_SYLLABLES) return false
        val (pieces, _) = runAnalysisWithMorphs(word) ?: return false
        return pieces.size >= 2 && pieces.all { dictionary.headTag(it.second) == properNoun && dictionary.tailTag(it.second) == properNoun }
    }

    /**
     * [word] 를 한 어절로 읽은 최선의 분석에서 **두 음절 넘는 일반·고유명사 형태소**가 덮는 음절 자리들('비서시장' → [0,2), [2,4)).
     * 형태소 경계가 음절 한가운데에 오는 것은 뺀다. 분석값이 높아도 쓴다 — 부르는 쪽이 말뭉치 빈도로 다시 거른다.
     */
    fun nounMorphemeRanges(word: String): List<IntRange> {
        if (word.length < 2 || word.length > MAX_SYLLABLES) return emptyList()
        val (pieces, _) = runAnalysisWithMorphs(word) ?: return emptyList()
        val syllableStart = IntArray(word.length + 1)
        for (i in word.indices) syllableStart[i + 1] = syllableStart[i] + jamoLength(word[i])
        val out = ArrayList<IntRange>()
        var at = 0
        for ((length, morph) in pieces) {
            val head = dictionary.headTag(morph)
            if ((head == commonNoun || head == properNoun) && dictionary.tailTag(morph) == head) {
                val from = syllableStart.indexOf(at)
                val to = syllableStart.indexOf(at + length)
                if (from >= 0 && to > from && to - from >= 2) out += from until to
            }
            at += length
        }
        return out
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

    /**
     * 자모 길이로 나온 조각을 원문 음절로 되돌린다.
     *
     * 형태소 경계가 음절 한가운데('갔|다' 의 'ㅆ')에 오면 앞 조각이 그 음절을 통째로 가져가 글자가 먼저 바닥난다. 그때 남은 조각
     * 앞에 공백만 찍으면 끝에 빈 조각이 생긴다('…다 ') — 문맥 디코더가 그 빈 조각을 글 끝 너머의 구간으로 읽다가 죽었다(2026-10-14).
     */
    private fun render(word: String, pieces: List<Pair<Int, Boolean>>): String {
        val out = StringBuilder()
        var index = 0
        for ((length, spacedBefore) in pieces) {
            if (index >= word.length) break
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

        /** [isNounChunk] 의 명사 쪽 품사. 의존명사(NNB)·대명사는 뺀다 — 붙여 친 글의 '것·수·데' 다. */
        private val NOUNISH_TAGS = setOf("NNG", "NNP", "NR", "XSN", "XPN", "SL", "SH", "SN")

        /** 겹조사 뒤에 홀로 띄운 '의'. [space] */
        private val LONE_GENITIVE = Regex("(에서|으로부터|로부터|에게서|한테서|으로서|로서|에서부터|까지|부터|만큼|에게|한테|과|와) 의(?= |$)")

        /** '-하다' 활용꼴의 첫 음절. [derivesWithHada] */
        private const val HADA_SYLLABLES = "하해했합한할함"

        /** 자리·때·까닭을 가리키는 명사. 앞 체언과 합성어를 이루는 일이 드물다([startsApartFromNoun]). '속·밖' 은 '마음속·창밖' 같은 합성어가 많아 뺐다. */
        private val APART_NOUNS = setOf(
            "앞", "뒤", "옆", "위", "아래", "안", "중", "등", "때", "전", "후", "사이", "쪽", "동안", "근처", "주변",
            "무렵", "도중", "이후", "이전", "이내", "내내", "때문", "덕분", "대신"
        )

        /** 체언 뒤에 와도 늘 딴 어절인 관형사. '두·세·네·한' 은 '엄마두(도)·주민세·철수네·한국' 과 부딪혀 뺐다. */
        private val APART_DETERMINERS = setOf("여러", "모든", "온갖", "무슨", "어느", "어떤", "몇")

        /** 홀로 선 조각이면 늘 의존명사이고 합성어 머리가 못 되는 말. '수' 는 '수백만' 의 접두사와 부딪혀 뺐다. */
        private val APART_BOUND_NOUNS = setOf("데", "지", "것", "줄", "바", "척", "체", "듯", "뻔", "김", "탓", "뿐", "만큼", "대로")

        /**
         * EC 로 태깅된 어미 중 문장을 끝내는 꼬리. 연결형('-아서', '-는데', '-고', '-면',
         * '-지만')과 겹치지 않는 것만 넣는다.
         */
        private val FINAL_TAILS = listOf("요", "다", "까", "죠", "네", "군", "구나", "자")

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
