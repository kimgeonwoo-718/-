package com.spellkeyboard.core.lm

import com.spellkeyboard.core.correct.ProtectedWords
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.hangul.NO_JONG
import com.spellkeyboard.core.spacing.Spacer

/**
 * 창(커서 앞 몇 어절)을 통째로 다시 푼다 — 띄어쓰기와 맞춤법을 한 번에.
 *
 * ## 왜 어절 하나씩 보면 안 되나
 *
 * 예전 엔진은 어절 하나를 따로 떼어 "이게 말이 되나"만 봤다. 그래서
 * - '뭐 하 새요' → '하'도 '새요'도 각각은 말이 되니 그대로 둔다.
 * - '아버지 가방에 들어가신다' → 어절마다 멀쩡하니 그대로 둔다.
 * - '있는대요' → '-는대요'도 어미로 존재하니 그대로 둔다.
 *
 * 사람은 이걸 **앞뒤와 빈도**로 푼다. '하세요'는 흔하고 '뭐' 뒤에 잘 오며, '하 새요'는
 * 그렇지 않다. 이 클래스는 그 판단을 잡음 통로 모형(noisy channel)으로 흉내 낸다:
 *
 *     점수(가설) = 언어모델(가설 어절 연쇄) + 통로 비용(원문에서 가설까지의 편집)
 *
 * 통로 비용은 "사람이 이런 실수를 할 법한가"다. ㅐ/ㅔ 를 바꾸는 건 싸고(자주 헷갈린다),
 * 띄어쓰기를 지우거나 넣는 건 그보다 비싸고, 아무 관계 없는 글자로 바꾸는 건 아예
 * 후보에 없다. 언어모델 점수가 통로 비용을 [MARGIN] 이상 넘어설 때만 고친다.
 *
 * ## 탐색
 *
 * 창의 음절을 한 줄로 늘어놓고(원래 띄어쓰기는 "경계" 표시로만 남긴다), 모든 구간
 * [i, j) 에 대해 후보 어절을 만든 뒤 비터비로 가장 점수 높은 어절 연쇄를 찾는다.
 * 구간은 네 종류다.
 * - 원래 어절 그대로 (편집 후보 포함)
 * - 이웃 어절 둘·셋을 합친 것 ('하'+'새요' → '하세요')
 * - 자유 구간: 언어모델이 아는 어절이 되는 임의의 음절 구간 ('아버지가' '방에')
 * - 형태소 사전이 쪼갠 조각: 모르는 긴 덩어리를 [Spacer] 가 나눈 것
 *
 * 언어모델이 모르는 어절은 형태소 분석 비용으로 대신 매긴다. 분석조차 안 되면 바닥 점수.
 *
 * ## 오교정을 막는 장치
 *
 * - 원문 어절이 흔한 말이면(빈도가 높으면) 편집 비용을 더 물린다. '네가'를 '내가'로
 *   바꾸는 것처럼 둘 다 흔한 말은 문맥이 아주 강하지 않는 한 손대지 않는다.
 * - 후보는 한국어에서 실제로 헷갈리는 자모 사이에서만 만든다.
 * - 한글이 아닌 것(숫자·영문·기호)은 건드리지 않고, 그 자리에서 문맥을 끊는다.
 * - 최종적으로 원문 가설보다 [MARGIN] 이상 좋아야 한다.
 */
class ContextCorrector(
    private val lm: LanguageModel,
    private val spacer: Spacer?
) {

    /** 형태소 분석 비용은 비싸다(어절당 0.1~0.3ms). 같은 문자열은 한 번만 잰다. */
    private val morphCosts = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** 형태소 사전이 어절을 어떻게 나누는지([splitsOf]). 같은 어절은 한 번만. 값이 null 이면 안 나눈다. */
    private val splits = object : LinkedHashMap<String, Pair<String?, String?>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, String?>>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** [junctionsOf] — 어절을 한 어절로 읽은 분석의 음절 자리별 품사. */
    private val junctions = object : LinkedHashMap<String, Pair<Array<Pair<String, String>?>, Array<Pair<String, String>?>>?>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Array<Pair<String, String>?>, Array<Pair<String, String>?>>?>?
        ): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** [nounSpansOf] — 어절을 한 어절로 읽은 분석에서 사전 명사가 덮는 음절 수([Spacer.nounSpans]). */
    private val nounSpanCache = object : LinkedHashMap<String, IntArray?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray?>?): Boolean = size > UNKNOWN_CACHE_SIZE
    }

    /** [rawBreaks] — 형태소 사전이 다듬지 않고 띄운 자리. */
    private val rawSplits = object : LinkedHashMap<String, Set<Int>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<Int>>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** 어절이 매인 형태소로 시작하는지 / 한 어절로 무리 없이 분석되는지. 같은 어절은 한 번만. */
    private val boundStarts = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }
    private val wellFormed = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    private val edgeTags = object : LinkedHashMap<String, Pair<String, String>?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>?>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    private val nounRangeCache = object : LinkedHashMap<String, List<IntRange>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<IntRange>>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** 명사 뒤 조각이 합성어 뒷부분일 수 없는 말인지([apartFromNoun]). 같은 (앞, 뒤)는 한 번만. */
    private val apart = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** 합칠 자리에 매인 형태소가 오는지도 분석이 필요하다. 같은 (어절, 자리)는 한 번만. */
    private val boundJunctions = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /** 자를 자리에서 높임 선어말어미가 시작하는지([honorificAt]). 같은 (어절, 자리)는 한 번만. */
    private val endingJunctions = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

    /**
     * 천지인 자판으로 치고 있는가. 켜면 **말뭉치가 모르는 어절**에 천지인 오타 편집을 더 본다 —
     * 같은 키를 한 번 덜·더 눌러 이웃 글자가 된 자음(ㅅ↔ㅎ, ㄴ↔ㄹ, ㅇ↔ㅁ…)과 ㆍ를 반대쪽에 찍은
     * 모음(ㅏ↔ㅓ, ㅗ↔ㅜ). 두벌식에서는 드문 오타라 꺼 둔다. 키보드가 자판에 맞춰 켠다.
     */
    @Volatile
    var cheonjiin: Boolean = false

    /**
     * [window] 를 고친다.
     *
     * @param contextBefore 창 바로 앞 어절. [LanguageModel.BOS] 면 문장 첫머리, null 이면 모름.
     * @return 고친 문자열. 고칠 것이 없으면 null.
     */
    /**
     * 언어모델이 이 어절을 본 적 있는가. 붙여 쓴 덩어리인지 가리는 데 쓴다.
     *
     * **조사가 붙은 꼴도 안다고 본다.** 말뭉치에 '소비자물가지수' 는 있어도
     * '소비자물가지수가' 는 없을 수 있다 — 조사는 자리마다 갈리니 모든 꼴이 다 들어 있을
     * 수가 없다. 그대로 물으면 아는 낱말이 모르는 낱말로 보이고, 그러면
     * [CorrectionEngine.applyLongSplit] 의 보호막이 풀려서 '전국경제인연합회가' 가
     * '전국 경제인 연합회가' 로 갈린다.
     *
     * 시험 문장 30개(붙여 쓰는 것이 맞는 긴 낱말)로 재 보면 오교정이 6건에서 5건으로
     * 준다. 같은 시험지에서 갈라야 할 것(20개)을 놓치는 일은 늘지 않는다.
     */
    fun knowsWord(word: String): Boolean {
        if (lm.lnCount(word) != null) return true
        // 학교 줄임말('서울체고')은 말뭉치에 없어도 아는 말이다(ProtectedWords).
        if (ProtectedWords.isProtected(word)) return true
        // 말뭉치보다 새 줄임말('핵노잼이더라')도 아는 말로 친다 — 붙여 쓴 덩어리로 보고 쪼갰다.
        if (SLANG_STEMS.any { word.startsWith(it) }) return true
        for (particle in TRAILING_PARTICLES) {
            // 조사를 떼고 남는 것이 한 음절이면 어절로 보지 않는다.
            if (word.length <= particle.length + 1) continue
            if (!word.endsWith(particle)) continue
            if (lm.lnCount(word.dropLast(particle.length)) != null) return true
        }
        return false
    }

    /** 말뭉치에 이 어절이 나온 횟수의 ln. 모르면 null. 큰 모델의 고침을 받아들일지 가를 때 쓴다([com.spellkeyboard.core.translate.LlmMerge]). */
    fun lnCount(word: String): Float? = lm.lnCount(word)

    /**
     * @param protectLast 창의 마지막 어절이 **방금 친 것이고 뒤가 아직 없다**(실시간 교정). 그 어절이 멀쩡한 낱말이면([isFirmWord])
     *   글자를 바꾸지 않는다 — 뒷말을 모르는 채로 바꾸면 '선수이자 → 선수이다', '처리되었으며 → 처리되었으면', '사용하지마 → 사용하지만'
     *   처럼 뜻이 뒤집힌다(2026-10-13, 기사 58만 문장). 뒷말을 치면 그 어절이 창 안쪽으로 들어오고, 그때 앞뒤를 다 보고 고친다.
     */
    fun correct(window: String, contextBefore: String? = null, protectLast: Boolean = false): String? {
        val pieces = tokenize(window)
        if (pieces.none { it.soft }) return null

        val out = StringBuilder()
        var changed = false
        var prev: String? = contextBefore
        var index = 0
        while (index < pieces.size) {
            val piece = pieces[index]
            if (!piece.soft) {
                out.append(piece.text)
                if (!piece.whitespace) prev = contextAfter(piece)
                index++
                continue
            }
            // 연속된 부드러운 어절 묶음. 앞에 기호가 붙은 어절은 새 묶음을 열고,
            // 뒤에 기호가 붙은 어절은 묶음을 닫는다 — 기호를 사이에 두고 합칠 수는 없다.
            val segment = ArrayList<Piece>()
            val gaps = ArrayList<String>()
            var cursor = index
            while (cursor < pieces.size) {
                val candidate = pieces[cursor]
                if (!candidate.soft) break
                if (segment.isNotEmpty() && candidate.prefix.isNotEmpty()) break
                segment += candidate
                cursor++
                if (candidate.suffix.isNotEmpty()) break
                // 다음이 공백이고 그다음이 부드러운 어절이면 계속 잇는다.
                if (cursor + 1 < pieces.size && pieces[cursor].whitespace && pieces[cursor + 1].soft &&
                    pieces[cursor + 1].prefix.isEmpty()
                ) {
                    gaps += pieces[cursor].text
                    cursor++
                } else {
                    break
                }
            }
            // 이 묶음 뒤에 남은 것이 공백뿐이면 묶음의 마지막 어절이 창의 마지막 어절이다.
            val endsWindow = (cursor until pieces.size).all { pieces[it].whitespace }
            // 문장 부호('바꾸었으며,', '미래에셋대우다.')가 바로 붙은 어절의 끝 음절은 쓴 사람이 일부러 고른 어미다.
            val punctuated = segment.last().suffix.any { it in SENTENCE_MARKS }
            val decoded = decode(segment.map { it.core }, prev, protectLast = protectLast && endsWindow, punctuatedLast = punctuated)
            val first = segment.first()
            val last = segment.last()
            out.append(first.prefix)
            if (decoded == null) {
                for (k in segment.indices) {
                    out.append(segment[k].core)
                    if (k < gaps.size) out.append(gaps[k])
                }
                prev = last.core
            } else {
                changed = true
                out.append(render(segment, gaps, decoded))
                prev = decoded.last()
            }
            out.append(last.suffix)
            if (last.suffix.any { it in SENTENCE_ENDERS }) prev = LanguageModel.BOS
            else if (last.suffix.isNotEmpty()) prev = null
            index = cursor
        }
        return if (changed) out.toString() else null
    }

    // ---------------------------------------------------------------- 토큰

    private class Piece(
        val text: String,
        val whitespace: Boolean,
        val prefix: String,
        val core: String,
        val suffix: String
    ) {
        /**
         * 고칠 수 있는 어절 — 한글 음절로만 되어 있고, 뒤에는 문장 부호나 채팅 장식
         * ('했어ㅋㅋ', '미안ㅠㅠ', '고마워😂', '좋아^^')만 붙을 수 있다. 장식을 막아 두었더니
         * 그 어절을 통째로 안 봐서 '햇어😂'·'많이먹어ㅠㅠ'가 그대로 남았다(2026-09-24).
         * '3시에' 처럼 앞에 다른 글자가 붙은 것은 언어모델이 본 적 없는 어절이라 건드리지 않는다.
         * 앞에 붙은 것이 장식이나 여는 괄호·따옴표뿐이면('ㅋㅋ햇어', '(나도') 본다.
         */
        val soft: Boolean =
            !whitespace && prefix.all { it in OPENERS || isDecoration(it) } && core.isNotEmpty() &&
                core.length <= MAX_TOKEN_SYLLABLES &&
                core.all { it in HANGUL } && suffix.all { it in PUNCTUATION || isDecoration(it) } &&
                SLANG_STEMS.none { core.startsWith(it) } &&
                // 학교 줄임말은 손대지 않는다 — '서울체고' → '서울최고', '과고' → '광고' 가 났다(2026-10-06).
                !ProtectedWords.isProtected(core)
    }

    private fun tokenize(window: String): List<Piece> =
        TOKEN.findAll(window).map { match ->
            val text = match.value
            if (text.isBlank()) return@map Piece(text, true, "", "", "")
            var start = 0
            var end = text.length
            while (start < end && text[start] !in HANGUL) start++
            while (end > start && text[end - 1] !in HANGUL) end--
            val core = text.substring(start, end)
            Piece(text, false, text.substring(0, start), core, text.substring(end))
        }.toList()

    /** 고치지 않는 조각 뒤의 문맥. 문장 부호로 끝나면 문장 첫머리, 아니면 모름. */
    private fun contextAfter(piece: Piece): String? =
        if (piece.text.any { it in SENTENCE_ENDERS }) LanguageModel.BOS else null

    /** 결과 어절들을 원래 공백을 살려 이어 붙인다. 원래 경계가 아닌 곳은 공백 하나. */
    private fun render(segment: List<Piece>, gaps: List<String>, tokens: List<String>): String {
        val gapAt = HashMap<Int, String>()
        var position = 0
        for (k in segment.indices) {
            position += segment[k].core.length
            if (k < gaps.size) gapAt[position] = gaps[k]
        }
        val out = StringBuilder()
        position = 0
        for ((k, token) in tokens.withIndex()) {
            if (k > 0) out.append(gapAt[position] ?: " ")
            out.append(token)
            position += token.length
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- 탐색

    private class Candidate(val text: String, val channel: Float)

    private class Node(val score: Float, val from: Int, val fromKey: String, val text: String)

    private class Span(val start: Int, val end: Int, val kind: Int)

    /**
     * 원래 어절 배치. [owner] 는 음절 위치마다 그 음절이 속한 원래 어절 번호,
     * [pieceBoundary] 는 형태소 사전이 어절을 나눈 자리.
     */
    private class Layout(
        val cores: List<String>,
        val boundary: BooleanArray,
        val starts: IntArray,
        val owner: IntArray,
        val pieceBoundary: BooleanArray,
        /** 어절마다, 사전에 오른 명사(+조사)면 그 명사의 길이(아니면 0) — [lexiconNounLength]. */
        val nounLength: IntArray
    )

    /**
     * 어절 묶음 하나를 푼다.
     *
     * @return 새 어절 목록. 원문 그대로가 최선이면 null.
     */
    private fun decode(
        cores: List<String>,
        contextBefore: String?,
        trace: StringBuilder? = null,
        protectLast: Boolean = false,
        punctuatedLast: Boolean = false
    ): List<String>? {
        val text = cores.joinToString("")
        val n = text.length
        if (n == 0 || n > MAX_SEGMENT_SYLLABLES) return null

        // 원래 어절 경계
        val boundary = BooleanArray(n + 1)
        val starts = IntArray(cores.size)
        val ends = IntArray(cores.size)
        val owner = IntArray(n)
        var position = 0
        boundary[0] = true
        for ((k, core) in cores.withIndex()) {
            starts[k] = position
            for (p in position until position + core.length) owner[p] = k
            position += core.length
            ends[k] = position
            boundary[position] = true
        }
        val pieceBoundary = BooleanArray(n + 1)
        val spans = collectSpans(text, n, cores, starts, ends, boundary, pieceBoundary)
        val layout = Layout(cores, boundary, starts, owner, pieceBoundary, IntArray(cores.size) { lexiconNounLength(cores[it]) })
        // 음절 자리마다, 그 음절이 든 원래 어절이 멀쩡한 말이면 그 어절의 길이(아니면 0). 편집 후보를 거르는 데 쓴다([singleEdits]).
        // 붙여 쓴 덩어리의 조각이 멀쩡한 말이면 그 조각 길이를 **음수로** 적는다 — 받침 넣기는 그쪽만 막는다.
        // 붙여 쓴 덩어리는 형태소 사전이 나눈 조각마다 본다 — '운전하는거' 의 '운전하는'·'거' 는 멀쩡한 말이라 '거 → 건' 으로 받침을
        // 넣지 않는다(큰 시험지 2026-10-11: '운전하는거 재미있어요 → 운전하는건').
        // 음절 자리마다, 사람이 친 어절 안에서 **말뭉치에 흔한 두 음절 넘는 명사**('비서시장' 의 비서·시장, '이관되지' 의 이관) 속인가.
        // 거기 글자는 바꾸지 않는다 — 고치면 딴 낱말이 된다('AI 비서시장 → 비서실장', '국제도서전에 → 국제도 선전에', 2026-10-14).
        // 값: 0 아님, 1 명사 하나뿐인 말('시가이나'), 2 명사가 이어진 합성어·이름('비서시장').
        // 명사 하나뿐인 말이라도 그 명사가 어절 끝까지 가면('지정서') 합성어처럼 다 지킨다 — 뒤에 조사가 붙은 것('시가이나')만 1.
        val commonNounAt = IntArray(n)
        for ((k, core) in cores.withIndex()) {
            val ranges = nounRanges(core)
            val level = if (ranges.size >= 2 || ranges.singleOrNull()?.last == core.length - 1) 2 else 1
            for (range in ranges) for (p in range) commonNounAt[starts[k] + p] = level
        }
        val realLength = IntArray(n)
        for ((k, core) in cores.withIndex()) {
            if (isRealWord(core)) {
                for (p in starts[k] until ends[k]) realLength[p] = core.length
                continue
            }
            val spaced = splitOf(core) ?: continue
            var at = starts[k]
            for (piece in spaced.split(' ')) {
                if (isRealWord(piece)) for (p in at until at + piece.length) realLength[p] = -piece.length
                at += piece.length
            }
        }
        // 멀쩡한 말 한가운데 자리인가 — 그 자리에서 자르면서 글자까지 바꾸면 거의 언제나 딴 말이다('경영대를 → 경영 때를').
        fun insideRealWord(p: Int): Boolean =
            p in 1 until n && !boundary[p] && !pieceBoundary[p] && owner[p] == owner[p - 1] &&
                realLength[p] != 0 && realLength[p - 1] != 0
        // 사람이 친 어절마다 **멀쩡한 낱말인가**([isFirmWord]). 그런 어절의 글자는 비싸게만 바꾸고, 끝 음절(어미·조사)은 조사 짝
        // ('을↔를' …) 말고는 안 바꾼다. 실시간 창의 마지막 어절이면 아예 안 바꾼다([protectLast]).
        val firm = BooleanArray(cores.size) { isFirmWord(cores[it]) }
        val last = cores.lastIndex
        // 사람이 친 어절 안에서 **자르기를 의심할 자리**(깨끗한 글 띄어쓰기 오교정, 2026-10-14). 어절을 한 어절로 읽은 분석에서
        // - 형태소 한가운데('국가대표팀에|서는', '얘|기해주시기')이거나
        // - 명사|명사('대한|상공회의소는', '육군과|학교를', '상임위|별로' — 합성어·이름. 앞이 때를 나타내는 말이거나 뒤가 자리·때를
        //   가리키는 명사면('초반|동안', '개헌|후') 뺀다)인 자리.
        // 붙여 친 글은 통째 분석이 엉뚱할 수 있어서('그때보자' = 그때/보자 를 명사|명사로, '길이막혀서' = 길/이막/혀서) 바로 막지
        // 않는다 — 답이 이 자리를 자르는데 **언어모델이 두 조각을 잇달아 본 적이 없을 때만** 막고 다시 찾는다([unseenSplits]).
        // 형태소 사전이 스스로 띄우는 자리([rawBreaks])거나 어절이 붙여 친 덩어리로 보이면([ONE_WORD_MAX_COST] 위 — 통째 분석을 덜
        // 믿는다) 더 아껴 막는다(값 1): 잘린 조각 하나가 말뭉치에 드물 때만('대한|변협은', '보도사진|전'). 둘 다면 막지 않는다(값 0).
        // 한 낱말로 자연스럽게 읽히는 어절을 사전이 한 군데도 안 띄우면 값 2. 사전이 어디든 띄우는 어절은 통째 분석의 틀이 틀렸을 수
        // 있어 값 1 이다 — '아버지가방에'(사전: 아버지 가방에)를 '아버지가 방에' 로 가르는 것은 막지 않는다.
        val doubtful = IntArray(n + 1)
        // 체언·어근 + '-하다'('문송|합니다', '옴짝달싹|하지')는 늘 한 낱말이라 바로 막는다 — [Spacer.derivesWithHada].
        val blocked = BooleanArray(n + 1)
        val nameInside = BooleanArray(n + 1)
        // 명사만 이어진 어절 안의 자리([Spacer.isNounChunk]) — [unseenSplits] 가 자른 두 조각을 보고 막는다.
        val nounJoint = BooleanArray(n + 1)
        for ((k, core) in cores.withIndex()) {
            if (core.length < 2) continue
            val analysis = junctionsOf(core)
            val tagsAt = analysis?.first
            // 두 음절 어절은 형태소 사전이 띄우지 않는다([Spacer.space]) — 사전이 띄우는 자리처럼 아껴 막는다('두번 → 두 번').
            val spacerBreaks = if (tagsAt == null) emptySet() else if (core.length < 3) (1 until core.length).toSet() else rawBreaks(core)
            val oneWord by lazy { (morphCost(core) ?: Int.MAX_VALUE) <= ONE_WORD_MAX_COST }
            // 명사만 이어진 어절(이름·합성어 모양 — '대한상공회의소는', '국토교통위', '챔피언스리그에서의')은 어디를 자르든
            // 말뭉치가 두 조각을 잇달아 본 적이 없으면 막는다. 붙여 친 명사 짝('영화제목이')도 원래 못 가르던 것이라 잃는 것이 없다.
            val nounChunk = spacer?.isNounChunk(core) == true
            val nounSpans = nounSpansOf(core)
            for (p in 1 until core.length) {
                val at = starts[k] + p
                val before = core.substring(0, p)
                val after = core.substring(p)
                if (tagsAt != null && tagsAt[p]?.let { compoundJunction(before, after, it) } != false) {
                    val spacerSplits = p in spacerBreaks
                    doubtful[at] = if (!spacerSplits && oneWord && spacerBreaks.isEmpty()) 2 else if (!spacerSplits || oneWord) 1 else 0
                    // 조사 한가운데('국가대표팀에|서는' 의 에서, '상징으로|서의' 의 으로서)는 사전이 띄워도 세게 본다 — 붙여 친 글에서
                    // 조사를 가를 일은 드물다('무대에|서는' 처럼 말뭉치가 잇달아 본 짝은 그대로 가른다).
                    if (analysis.second[p]?.let { it.first.startsWith("J") && it.second.startsWith("J") } == true) doubtful[at] = 2
                }
                // 분석을 못 믿는 긴 이름 뒤('챔피언스리그에|서의')에도 끝의 겹조사 한가운데는 글자로 알아본다.
                if (insideTrailingParticle(core, p)) doubtful[at] = 2
                // 사전에 오른 세 음절 넘는 명사 한가운데('큰|어머니인', '중심|가서'의 중심가, '보츠와|나서', '공감이|사회가'의 이사회)도
                // 사전이 그 자리를 띄워도 세게 본다 — 두 음절 명사('아버지가|방에' 의 가방)는 붙여 친 글에서 흔히 걸려 뺀다(2026-10-15).
                // 분석값이 높아 통째 분석을 못 믿는 어절이면 네 음절 넘는 명사만 본다('보츠와|나서').
                if ((nounSpans?.get(p) ?: 0) >= if (analysis != null) LONG_MORPHEME_SYLLABLES else LONG_NAME_SYLLABLES) doubtful[at] = 2
                // 한 낱말로 무리 없이 읽히고 사전도 한 군데 안 띄우는 어절('폭등했었다', '거듭해', '보고받는')은 어느 자리든 의심한다 —
                // 붙여 친 덩어리는 분석값이 높거나 사전이 띄운다('그날은선약이', '어제산옷이').
                if (tagsAt != null && spacerBreaks.isEmpty() && core.length >= 3 && oneWord) doubtful[at] = 2
                nounJoint[at] = nounChunk
                blocked[at] = (before !in ADVERBIAL_NOUNS && spacer?.derivesWithHada(before, after) == true) ||
                    boundVerbJoint(before, after)
            }
            // 이름 조각이라 도로 붙인 덩어리([joinNameFragments]) 안을 자르는 자유 구간은 글자를 못 바꾼다 — 그 안을 자르면서 글자까지
            // 바꾼 답이 이겼다('이번주였나요 → 이번 죽였나요', '공동어로구역의 → 공동으로 구역의', 큰 시험지 2026-10-14). 띄어쓰기만
            // 넣는 것은 둔다 — 붙여 친 글의 사전 조각이 엉뚱해도('날씨가안좋을' = 날씨 가안 좋을) 디코더가 제대로 가른다.
            for (p in joinedBreaks(core)) nameInside[starts[k] + p] = true
        }
        val candidates = HashMap<Long, List<Candidate>>()
        for (span in spans) {
            if (span.start >= span.end || span.end > n) continue
            if ((span.kind == KIND_FREE || span.kind == KIND_PIECE) && (blocked[span.start] || blocked[span.end])) continue
            val key = spanKey(span.start, span.end)
            val list = candidates[key]
            val firstOwner = owner[span.start]
            // 구간이 사람이 친 어절 **하나 안**에 있는가(그대로 둔 어절이거나, 그 속을 자유롭게 자른 조각). 이웃 어절과 합치는 구간
            // ('하 새요 → 하세요')은 원래 띄어쓰기가 틀린 것이라 아래 막이들을 걸지 않는다.
            val withinOne = firstOwner == owner[span.end - 1]
            // '한 어절로 읽히는 말' 은 분석이 **자연스러운** 것만 친다 — 분석 사전은 '있너'(있/VA 너/NP, 3609)도 읽어 낸다.
            val wellFormed = withinOne && (firm[firstOwner] ||
                (isWellFormed(cores[firstOwner]) && (morphCost(cores[firstOwner]) ?: Int.MAX_VALUE) < LOCK_MAX_COST))
            // 명사 + '-하다·-되다' 로 읽히는데 그 움직씨가 말뭉치에 없는 말('피고해서' — 피곤해서, '차고해' — 참고해)은 멀쩡한 말 값을
            // 물리지 않는다 — 분석 사전이 받침 빠진 오타를 흔한 명사 + '-하다' 로 맞춰 읽었다(2026-10-15).
            val fakeVerb = wellFormed && !firm[firstOwner] && unattestedVerb(cores[firstOwner])
            // 실시간 창의 마지막 어절(뒤를 모름). 명사면 안 고치고, 한 어절로 읽히는 말이면 쉬프트 오타(ㅅ→ㅆ)만.
            val protectedTail = protectLast && withinOne && firstOwner == last
            // 자유롭게 떼어 낸 **한 음절** 조각은 글자를 바꾸지 않는다 — '서울아산병원서 → 서울아산병원 써'(2026-10-13).
            // 두 음절 넘는 어절에 뒤의 **한 음절 어절**을 붙이면서 글자까지 바꾸지 않는다 — 이름 앞 조각을 조사로 먹었다('노벨상 작가 르
            // 클레지오 → 작가를', 2026-10-14). 앞이 한 음절 조각인 '뭐 하 새요 → 하세요'(사용자 제보)는 그대로 고친다.
            val lastOwner = owner[span.end - 1]
            val swallowsSyllable = !withinOne && cores[firstOwner].length >= 2 && cores[lastOwner].length == 1 &&
                ends[lastOwner] == span.end && starts[firstOwner] == span.start
            val edits = !(protectedTail && firm[last]) && !swallowsSyllable &&
                !(span.kind == KIND_FREE && (insideRealWord(span.start) || insideRealWord(span.end) || span.end - span.start == 1))
            // 이름 조각이라 도로 붙인 덩어리 안을 자르는 자유 구간은 소리 같은 모음(ㅐ↔ㅔ)과 쉬프트 오타(ㅅ→ㅆ)만 고친다 — '이번주였나요 →
            // 이번 죽였나요', '공동어로구역의 → 공동으로 구역의'는 막고, 오타 토막을 도로 붙인 '태|이프를샀던 → 테이프를 샀던',
            // '나름|재밋었는데 → 나름 재밌었는데'는 고친다(2026-10-15).
            val sameSoundOnly = span.kind == KIND_FREE && (nameInside[span.start] || nameInside[span.end])
            // 끝 음절 잠금은 명사뿐 아니라 **한 어절로 읽히는 말 전부**에 건다 — 어미를 딴 어미로 바꾸면 뜻이 바뀐다('처리되었으며 →
            // 처리되었으면', '선수이자 → 선수이다', '사용하지마 → 사용하지만', '흡연자라 → 흡연자가'). 조사 짝('을↔를')만 바꾼다.
            val fresh = candidatesFor(
                text.substring(span.start, span.end), span.kind, edits,
                // 사전 명사이거나 자연스럽게 읽히는 말의 글자는 비싸게 바꾼다('묵었습니다 → 묶었습니다', '낮겠습니다 → 낫겠습니다',
                // '서명하였다 → 설명하였다', '업어 → 없어'). 쉬프트 오타(ㅅ→ㅆ, -ㄹ가 → -ㄹ까)는 이 값을 물리지 않는다.
                extraCost = if (wellFormed) FIRM_EDIT_COST else 0f,
                fakeVerb = fakeVerb,
                // 구간이 어절 첫머리에서 시작하면 바로 앞 어절(목적어 조사·수 뒤의 진짜 말을 지킨다). 어절 속에서 시작하면 그 어절의
                // 앞 조각이 앞말이다('있는|데면' — 붙여 친 관형형 뒤의 '데', 2026-10-15).
                prevWord = if (span.start == starts[firstOwner]) (if (firstOwner > 0) cores[firstOwner - 1] else contextBefore)
                else text.substring(starts[firstOwner], span.start),
                nextWord = if (withinOne && span.end == ends[firstOwner] && firstOwner < last) cores[firstOwner + 1] else null,
                lockLast = wellFormed && span.end == ends[firstOwner],
                endingFixed = punctuatedLast && span.end == n,
                slipsOnly = protectedTail && wellFormed,
                sameSoundOnly = sameSoundOnly,
                evidentAllowed = !(withinOne && firstOwner < last && titleWord(cores[firstOwner + 1])),
                firmNoun = withinOne && firm[firstOwner],
                firmStem = if (withinOne && firm[firstOwner] && span.start == starts[firstOwner]) layout.nounLength[firstOwner] else 0,
                wholeWord = withinOne && span.start == starts[firstOwner] && span.end == ends[firstOwner],
                insideCommonNoun = { commonNounAt[span.start + it] },
                // 조사 없이 홀로 선 명사 뒤에 용언이 아닌 말이 오면 이름이기 쉽다('이정 유진투자증권 애널리스트' → 일정 X, 기사 2026-10-15).
                // '여락 줘'(뒤가 용언)는 그대로 고친다.
                // 뒷말이 창에 없어도 같다 — 뒤에 용언이 오는지 모른다.
                bareNounBeforeNoun = withinOne && span.start == starts[firstOwner] && span.end == ends[firstOwner] &&
                    (firstOwner == last || !startsWithVerb(cores[firstOwner + 1]))
            ) { realLength[span.start + it] }
            candidates[key] = if (list == null) fresh else merge(list, fresh)
        }

        val initialKey = contextBefore ?: UNKNOWN_CONTEXT
        val identity = identityScore(cores, contextBefore, trace)
        var (best, tokens) = search(n, candidates, layout, initialKey) { _, _, _ -> true } ?: return null
        trace?.let { traceTokens(it, "최선", best, tokens, candidates, layout, contextBefore) }
        // 의심 자리를 처음 보는 두 조각으로 자른 답이면 그 자리를 막고 다시 찾는다. 새 답이 또 딴 의심 자리를 자를 수 있어 몇 번 되풀이한다.
        // 처음 답이 띄어쓰기만 고친 것이었으면 다시 찾을 때도 글자는 못 바꾼다 — 막힌 자리 대신 글자를 바꾼 답이 끼어들었다
        // ('이번주였나요 → 이번 죽였나요', '공동어로구역의 → 공동으로 구역의', 큰 시험지 2026-10-14).
        val refused = BooleanArray(n + 1)
        val spacingOnly = tokens.joinToString("") == text
        var rounds = 0
        while (rounds++ < MAX_REFUSAL_ROUNDS) {
            val unseen = unseenSplits(tokens, layout, text, doubtful, nounJoint)
            if (unseen.isEmpty()) break
            for (p in unseen) refused[p] = true
            trace?.append("처음 보는 두 조각으로 자른 자리 ${unseen.joinToString()} 를 막고 다시 찾는다\n")
            val again = search(n, candidates, layout, initialKey) { i, j, candidate ->
                !refused[i] && !refused[j] &&
                    (!spacingOnly || (candidate.text.length == j - i && text.regionMatches(i, candidate.text, 0, j - i)))
            } ?: return null
            best = again.first
            tokens = again.second
            trace?.let { traceTokens(it, "최선", best, tokens, candidates, layout, contextBefore) }
        }

        // **글자를 바꾼 답은 띄어쓰기만 고친 읽기보다도 확실히 나아야 한다.** 원문 덩어리를 통째로 모르는 말로 매기면
        // 한 글자 고친 '아는 말' 이 쉽게 이긴다 — '밥 먹은거지' 가 '먹은건지'(→ 먹은 건지) 가 됐다(2026-10-11 사용자).
        // 띄어 쓰면 '먹은 거지' 로 멀쩡히 읽히는데 받침 하나로 뜻이 바뀐 것이다. 글자를 그대로 둔 가장 좋은 읽기와
        // 견줘 [MARGIN] 만큼 못 이기면 그 읽기를 쓴다(그것도 원문보다 [MARGIN] 만큼 나을 때만).
        if (tokens.joinToString("") != text) {
            val plain = search(n, candidates, layout, initialKey) { i, j, candidate ->
                !refused[i] && !refused[j] && candidate.text.length == j - i && text.regionMatches(i, candidate.text, 0, j - i)
            }
            if (plain != null) {
                trace?.let { traceTokens(it, "글자 그대로 최선", plain.first, plain.second, candidates, layout, contextBefore) }
                // 한 어절을 **가르면서 그 안의 글자까지** 바꾸는 답은 두 번 고친 셈이라 문턱을 두 배로 둔다 — 이름·새말('검언유착 →
                // 검은 유착', '드지에칸스키를 → 들지 에칸스키를')이 그렇게 바뀌었다(2026-10-14). 붙여 친 오타('저녁애밥먹자')는 고친 말이
                // 훨씬 흔해서 넘는다.
                val needed = if (splitWithEdit(tokens, layout, text)) MARGIN * 2 else MARGIN
                if (best.score - plain.first.score < needed) {
                    trace?.append("글자를 바꾼 답이 글자 그대로 읽기보다 %.2f 낫다 — 문턱 %.2f 를 못 넘어 글자 그대로를 쓴다\n"
                        .format(best.score - plain.first.score, needed))
                    best = plain.first
                    tokens = plain.second
                }
            }
        }
        trace?.append("차이 %.2f (문턱 %.2f)\n".format(best.score - identity, MARGIN))
        if (best.score - identity < MARGIN) return null
        return if (tokens == cores) null else tokens
    }

    /**
     * 답 [tokens] 가 사람이 친 어절 안의 [doubtful] 자리를 자르는데, 언어모델이 그 앞뒤 두 조각을 **잇달아 본 적이 없는** 자리들.
     * 본 적이 있으면 붙여 친 글을 푼 것이라 그대로 둔다. 글자 수가 바뀐 답이면 자리를 못 맞춰 빈 목록.
     */
    private fun unseenSplits(tokens: List<String>, layout: Layout, text: String, doubtful: IntArray, nounJoint: BooleanArray): List<Int> {
        if (tokens.sumOf { it.length } != text.length) return emptyList()
        val out = ArrayList<Int>()
        var at = 0
        for (k in tokens.indices) {
            if (k > 0 && !layout.boundary[at] && (doubtful[at] > 0 || nounJoint[at]) && lm.lnBigramCount(tokens[k - 1], tokens[k]) == null &&
                (doubtful[at] == 2 || (doubtful[at] == 1 && (rarePiece(tokens[k - 1]) || rarePiece(tokens[k]))) ||
                    (nounJoint[at] && nameJoint(tokens[k - 1], tokens[k])))
            ) out += at
            else if (k > 0 && !layout.boundary[at] && wholeWins(tokens[k - 1], tokens[k])) out += at
            else if (k > 0 && !layout.boundary[at] && headJoint(tokens[k - 1], tokens[k], at - tokens[k - 1].length, layout)) out += at
            // 자른 조각 하나가 말뭉치가 모르는 이름 조각이면('팬택씨앤|아이는') 붙여 친 글을 푼 것이 아니다.
            else if (k > 0 && !layout.boundary[at] && lm.lnBigramCount(tokens[k - 1], tokens[k]) == null &&
                (nameLike(tokens[k - 1]) || nameLike(tokens[k]))
            ) out += at
            // 사람이 친 어절을 가르면서 한 조각의 글자를 바꿨는데 **다른 조각은 말뭉치가 모르는 말**이면 어절을 풀어낸 것이 아니다 —
            // 모르는 이름의 앞머리만 흔한 말로 떼어 냈다('그레뱅뮤지엄이 → 그래 뱅뮤지엄이', '아게시스토라타에게 → 알게 시스토라타에게',
            // 대화·위키 2026-10-15). 모르는 말 값은 길수록 커서 앞머리를 떼면 늘 이득이었다.
            else if (k > 0 && !layout.boundary[at] && editedBesideUnknown(tokens[k - 1], tokens[k], at, text)) out += at
            // 자른 **바로 그 자리의 음절**(앞 조각 끝·뒤 조각 첫머리)까지 바꿨는데 두 조각을 잇달아 본 적이 없으면, 어미·조사를 떼어 내려고
            // 글자를 맞춘 것이다 — '달아주리라 → 달아준 리라', '만족스러우실거라고 → 만족스러운 살거라고', '힘듦에도 → 힘들 에도',
            // '민감하신분은 → 민감한 신분은', '좁은방이었습니다 → 좁은 장이었습니다'(2026-10-15). 소리 같은 모음과 쉬프트 오타('가족들이랑|봣다가
            // → 봤다가')는 붙여 친 오타에 흔해서 뺀다. 붙여 친 받침 빠짐('아치먹고 → 아침 먹고')은 두 조각을 본 적이 있어 그대로 고친다.
            else if (k > 0 && !layout.boundary[at] && lm.lnBigramCount(tokens[k - 1], tokens[k]) == null &&
                editedAtCut(tokens[k - 1], tokens[k], at, text)
            ) out += at
            at += tokens[k].length
        }
        return out
    }

    /** [at] 에서 맞닿은 두 조각 중 하나는 원문([text])과 글자가 다르고 다른 하나는 말뭉치가 모르는 말인가([unseenSplits]). */
    private fun editedBesideUnknown(before: String, after: String, at: Int, text: String): Boolean {
        val beforeEdited = !text.regionMatches(at - before.length, before, 0, before.length)
        val afterEdited = !text.regionMatches(at, after, 0, after.length)
        return (beforeEdited && lm.lnCount(after) == null) || (afterEdited && lm.lnCount(before) == null)
    }

    /** [at] 에서 맞닿은 두 조각 중 하나가 **맞닿은 음절**을 원문([text])과 다르게 바꿨는가(소리 같은 모음·쉬프트 오타 말고 — [unseenSplits]). */
    private fun editedAtCut(before: String, after: String, at: Int, text: String): Boolean {
        // 있던 받침을 겹받침으로 채운 것('먹지|안았어 → 않았어')도 붙여 친 오타에 흔해서 뺀다.
        fun completesCoda(a: Char, b: Char): Boolean {
            val x = Hangul.decompose(a) ?: return false
            val y = Hangul.decompose(b) ?: return false
            return x.first == y.first && x.second == y.second && x.third != 0 &&
                (Hangul.JONGSEONG[x.third] to Hangul.JONGSEONG[y.third]) in CODA_COMPLETIONS
        }
        fun changedAtCut(original: String, edited: String, index: Int) =
            original.length == edited.length && original[index] != edited[index] &&
                !sameSoundSwap(original, edited) && !isShiftSlip(original, edited) && !completesCoda(original[index], edited[index])
        val left = text.substring(at - before.length, at)
        val right = text.substring(at, at + after.length)
        // 앞 조각 끝만 고쳤고 뒤 조각이 그대로 **아주 흔한 말**이면 붙여 친 받침 빠짐이다('괜찮으곳이 → 괜찮은 곳이' — 언어모델이 두 말을
        // 잇달아 본 적이 없었다). '달아준|리라', '민감한|신분은', '힘들|에도' 의 뒤 조각은 그만큼 흔하지 않다.
        val commonRight = right == after && (lm.lnCount(after) ?: 0f) >= CUT_RIGHT_COMMON_LN
        return (changedAtCut(left, before, before.length - 1) && !commonRight) || changedAtCut(right, after, 0)
    }

    /**
     * 명사만 이어진 어절을 [before]|[after] 로 가른 것이 이름·합성어 속인가. 왼쪽이 조사로 끝나거나('아버지가|방에') 오른쪽이
     * 용언·부사로 시작하면('그때|보자' — 통째 분석은 '보자' 를 명사로 읽었다) 붙여 친 문장을 푼 것이다.
     */
    /**
     * 명사만 이어진 덩어리([Spacer.isNounChunk])를 [before]|[after] 로 가르는 것이 이름·합성어 속인가 — 말뭉치가 두 조각을
     * 잇달아 본 적이 없고, 양쪽이 명사이거나([nounPair]) 한쪽이 드문 토막이다(왼쪽이 조사·어미로 끝나는 아는 말이면 아니다).
     * 디코더([unseenSplits])와 긴 덩어리 나누기([com.spellkeyboard.core.correct.CorrectionEngine])가 같이 쓴다.
     */
    fun nameJoint(before: String, after: String): Boolean =
        lm.lnBigramCount(before, after) == null &&
            (nounPair(before, after) || (!looseEnd(before) && (rarePiece(before) || rarePiece(after))))

    /**
     * 사람이 붙여 친 어절 [before]|[after] 가 한 낱말로 붙여 쓰는(또는 붙여 써도 되는) 용언 자리인데 말뭉치가 두 조각을 잇달아
     * 본 적이 없는가 — 형태소 사전 조각에서 도로 붙이는 자리([rejoinOptional])와 같다. 자유 구간이 여기를 가르면
     * '앞서갔지만 → 앞서 갔지만', '비난받았을 → 비난 받았을', '폭등했었다 → 폭등 했었다'(사전이 '폭등' 을 폭+등으로 읽었다)가 났다.
     * 말뭉치가 본 짝('돈 받았어', '먹어 봐')은 그대로 가른다.
     */
    fun boundVerbJoint(before: String, after: String, auxiliary: Boolean = true): Boolean {
        if (before.length < 2 || before in ADVERBIAL_NOUNS) return false
        val head = tagsOf(after)?.first ?: return false
        if (spacer?.isVerbTag(head) != true && head != "XSV" && head != "XSA") return false
        val joins = (auxiliary && endsWithAEo(before) && head != "XSV" && head != "XSA") ||
            // '-받다·-당하다·-시키다' 는 '-하다' 가 붙는 명사 뒤에서만 접미사다('비난받다', '보고받다' — '돈 받았어' 는 뗀다).
            // 말뭉치에 붙여 친 꼴('맡아야했다')도 있어 앞말이 체언으로 끝날 때만 본다.
            // 부사('그냥|해', '빨리|해')는 붙여 친 꼴을 말뭉치가 알아도 뗀다.
            ((endsNominal(before) || tagsOf(before)?.first in HADA_ROOT_HEADS) && hadaRoot(before) &&
                (ATTACHABLE_VERBS.any { after.startsWith(it) } || after[0] in "하해했합한할함"))
        return joins && lm.lnBigramCount(before, after) == null
    }

    /** [stem] 에 '-하다' 가 붙은 꼴을 말뭉치가 아는가('폭등했다', '협치하는') — 사전이 '폭등' 을 못 읽어도 '-하다' 줄기임을 안다. */
    private fun hadaRoot(stem: String): Boolean =
        HADA_FORMS.any { (lm.lnCount(stem + it) ?: 0f) >= HADA_ROOT_LN }

    /**
     * 덩어리 [chunk] 의 [p] 자리를 **말뭉치가 아는 이름·명사**가 가로지르는가 — 그 낱말이 덩어리 첫머리에서 시작하거나 덩어리 끝(뒤의
     * 조사는 빼고)에서 끝난다('아비시니|아의' 의 아비시니아, '베를|린지역의' 의 베를린, '함대함유도|무기' 의 유도무기,
     * '셰나바리인베스트먼|트와' 의 인베스트먼트). 그 낱말은 명사로 읽혀야 한다 — 구어체에서 붙여 친 꼴('할수있어', '보고싶어')도
     * 말뭉치에 있어서다. 긴 덩어리 나누기([com.spellkeyboard.core.correct.CorrectionEngine])가 쓴다.
     */
    fun crossesKnownName(chunk: String, p: Int): Boolean {
        val stem = trailingParticleStart(chunk)
        for (i in 0 until p) {
            for (j in p + 1..chunk.length) {
                if (j - i < KNOWN_NAME_MIN_SYLLABLES || (i != 0 && j != stem && j != chunk.length)) continue
                val word = chunk.substring(i, j)
                if ((lm.lnCount(word) ?: 0f) < KNOWN_NAME_LN) continue
                if (spacer?.isLexiconNoun(word) == true || tagsOf(word)?.let { it.first in NOUN_PAIR_TAGS && it.second in NOUN_PAIR_TAGS } == true) return true
            }
        }
        return false
    }

    /**
     * [before]|[after] 가 한 형태소 한가운데이고('끝|납니다' 의 끝나, '재미|있을' 의 재미있, '익숙해|지는') 말뭉치가 붙인 꼴을 두 조각
     * 짝보다 많이 봤는가 — 긴 덩어리 나누기가 이 자리를 자르지 않는다. 붙인 꼴을 말뭉치가 모르면('아버지가방에') 아니다.
     */
    fun glueWins(before: String, after: String): Boolean {
        // '-아/-어 + 지다'('익숙해|지는', '예뻐|졌어')는 늘 붙여 쓴다. 분석 사전은 홀로 선 '지는' 을 지(자기)+는 으로 읽어 품사로는 못 본다.
        // 한 음절 앞말('해|지는' — 해가 지다)은 뺀다.
        if (before.length >= 2 && after[0] in "지져졌집질진" && endsWithAEo(before)) return true
        // '-아/-어 + 트리다·뜨리다'('넘어|트렸으며', '떨어|뜨린')는 힘줌 접미사라 늘 붙인다.
        if (before.length >= 2 && TRI_SUFFIXES.any { after.startsWith(it) } && endsWithAEo(before)) return true
        val whole = lm.lnCount(before + after) ?: return false
        val pair = lm.lnBigramCount(before, after)
        if (pair != null && whole <= pair) return false
        return spacer?.insideMorpheme(before + after, before.length) == true
    }

    /** [chunk] 끝에 붙은 조사를 뗀 자리(조사가 없으면 길이). */
    private fun trailingParticleStart(chunk: String): Int {
        for (particle in TRAILING_PARTICLES) {
            if (chunk.length > particle.length + 1 && chunk.endsWith(particle)) return chunk.length - particle.length
        }
        return chunk.length
    }

    /**
     * 조각이 조사·연결어미·종결어미로 끝나거나 때를 나타내는 말인가 — 그 뒤는 붙여 친 문장의 딴 어절이다('그날은|선약이', '어제|산옷이').
     * 말뭉치가 모르는 토막('아슈와', '하나금융지')은 분석이 조사·어미로 읽어도 이름 조각이라 아니다.
     */
    private fun looseEnd(piece: String): Boolean {
        if (!knowsWord(piece)) return false
        if (adverbialNoun(piece)) return true
        val tail = tagsOf(piece)?.second ?: return false
        return tail.startsWith("J") || tail == "EC" || tail == "EF"
    }

    private fun nounPair(before: String, after: String): Boolean {
        // 때를 나타내는 말·대명사 뒤('지금|통화', '어제|산옷이'), 자리·때를 가리키는 명사 앞('초반|동안', '알게로|등의')은 딴 어절이다.
        // 다만 '-전'(경기·전시)·'-위'(위원회) 는 줄임말 꼬리이기도 하다 — 말뭉치가 잇달아 본 적 없으면 붙인다('러시아|전에',
        // '청년|위와', '국토교통|위'). '식사 전에', '책상 위에' 는 말뭉치가 본 짝이라 여기 안 온다.
        if (adverbialNoun(before)) return false
        if (apartFromNoun(before, after) && after[0] !in ABBREVIATION_TAILS) return false
        // 왼쪽이 조사·어미·관형사로 끝나면('아버지가|방에', '괜찮아|신경', '이|장면이') 붙여 친 문장을 푼 것이다.
        val tail = tagsOf(before)?.second
        if (tail != null && tail !in NOUN_PAIR_TAGS) return false
        val head = tagsOf(after)?.first ?: return true
        return head in NOUN_PAIR_TAGS
    }

    /**
     * 어절 [core] 의 [p] 자리가 어절 끝 겹조사('에서·으로서·로부터…') 한가운데인가 — 그 뒤에는 조사·서술격만 이어진다
     * ('챔피언스리그에|서의', '판단에|서다', '상징으로|서의'). 앞에 줄기가 있어야 한다.
     */
    private fun insideTrailingParticle(core: String, p: Int): Boolean {
        for (particle in SPLITTABLE_PARTICLES) {
            var start = core.indexOf(particle, 1)
            while (start >= 1) {
                if (start < p && p < start + particle.length && core.substring(start + particle.length) in PARTICLE_TAILS) return true
                start = core.indexOf(particle, start + 1)
            }
        }
        return false
    }

    /**
     * 사람이 붙여 친 [before]+[after] 를 말뭉치가 통째로 두 조각 짝보다 훨씬 많이 봤고 뒤가 명사인가 — 이름이다
     * ('더불어민주당이' 4.9 대 '더불어 민주당이' 3.6, '바른미래당' 6.0 대 3.6). 구어체의 붙여 쓴 꼴('안돼', '공부하는데')은 뒤가 명사가
     * 아니라 여기 안 걸린다.
     */
    private fun wholeWins(before: String, after: String): Boolean {
        if (before.length < 2 || before.length + after.length < WHOLE_WINS_MIN_SYLLABLES) return false
        val whole = lm.lnCount(before + after) ?: return false
        val pair = lm.lnBigramCount(before, after)
        if (pair != null && whole < pair + WHOLE_WINS_MARGIN) return false
        val head = tagsOf(after)?.first ?: return false
        return head == "NNG" || head == "NNP"
    }

    /**
     * 사람이 친 어절 첫머리의 한 음절 머리를 떼는데('이|모빌리티', '더|퍼스트', '그|린스버그에서') 말뭉치가 두 조각을 잇달아 본 적이
     * 없고 뒤가 흔하지 않은 체언인가 — 이름·합성어 머리를 뗀 것이다. 붙여 친 글의 '그|사람', '더|좋은' 은 말뭉치가 본 짝이다.
     * 부사 + 체언('아주|대학교를')까지 막아 봤더니 붙여 친 글의 '혹시|시간', '이따가|집에' 를 잃어 뺐다(2026-10-15).
     * [start] 는 [before] 의 자리. 디코더([unseenSplits])와 긴 덩어리 나누기가 같이 쓴다(그쪽은 [layout] 이 없다).
     */
    private fun headJoint(before: String, after: String, start: Int, layout: Layout): Boolean =
        (start == 0 || layout.boundary[start]) && headJoint(before, after)

    fun headJoint(before: String, after: String): Boolean {
        if (before.length != 1 || lm.lnBigramCount(before, after) != null) return false
        // 관형형('쉴|새도' 의 쉬+ㄹ)은 붙여 친 글의 흔한 자리라 뺀다.
        if (tagsOf(before)?.second?.startsWith("E") == true) return false
        // 뒤가 체언(+조사)일 때만 — '잘|해결됐어', '더|자고' 의 부사는 용언을 꾸며 붙여 친 글에서 늘 뗀다.
        return (lm.lnCount(after) ?: 0f) < LONE_HEAD_COMMON_LN && nominalPiece(after)
    }

    /** 조각이 체언(+조사)인가. 분석이 안 되는 조각은 대개 이름이라 체언으로 친다. */
    private fun nominalPiece(piece: String): Boolean {
        val (head, tail) = tagsOf(piece) ?: return true
        return head in NOUN_PAIR_TAGS && (tail in NOUN_PAIR_TAGS || tail.startsWith("J"))
    }

    private fun rarePiece(token: String): Boolean = (lm.lnCount(token) ?: Float.NEGATIVE_INFINITY) < RARE_PIECE_LN

    /**
     * 답 [tokens] 가 원래 어절 하나를 **가르고 그 조각의 글자도 바꿨거나**, 어절 둘을 **붙이면서 글자도 바꿨는가**('작가 르 클레지오 →
     * 작가를'). 두 번 고친 셈이다. 글자 수가 바뀐 답이면 자리를 못 맞춰 false.
     */
    private fun splitWithEdit(tokens: List<String>, layout: Layout, text: String): Boolean {
        if (tokens.sumOf { it.length } != text.length) return false
        var at = 0
        for (token in tokens) {
            val end = at + token.length
            if (!text.regionMatches(at, token, 0, token.length)) {
                val k = layout.owner[at]
                val coreEnd = layout.starts[k] + layout.cores[k].length
                if (layout.owner[end - 1] != k) return true
                if (at != layout.starts[k] || end != coreEnd) return true
            }
            at = end
        }
        return false
    }

    /**
     * 비터비 한 판. [keep] 이 받아 준 후보만 쓴다. 가장 좋은 끝 노드와 그 어절 목록을 돌려준다.
     */
    private fun search(
        n: Int,
        candidates: Map<Long, List<Candidate>>,
        layout: Layout,
        initialKey: String,
        keep: (Int, Int, Candidate) -> Boolean
    ): Pair<Node, List<String>>? {
        val lattice = arrayOfNulls<HashMap<String, Node>>(n + 1)
        lattice[0] = hashMapOf(initialKey to Node(0f, -1, "", ""))
        val original = layout.cores.joinToString("")

        for (i in 0 until n) {
            val here = lattice[i] ?: continue
            for (j in i + 1..minOf(n, i + MAX_TOKEN_SYLLABLES)) {
                val list = candidates[spanKey(i, j)] ?: continue
                for ((prevKey, node) in prune(here)) {
                    for (candidate in list) {
                        if (!keep(i, j, candidate)) continue
                        if (objectThenExistential(prevKey, candidate.text, original, i)) continue
                        val boundaryCost = boundaryCost(layout, prevKey, i, j, candidate.text) ?: continue
                        val score = node.score + lnScore(prevKey, candidate.text) + candidate.channel + boundaryCost
                        val target = lattice[j] ?: HashMap<String, Node>().also { lattice[j] = it }
                        val existing = target[candidate.text]
                        if (existing == null || score > existing.score) {
                            target[candidate.text] = Node(score, i, prevKey, candidate.text)
                        }
                    }
                }
            }
        }

        val best = lattice[n]?.values?.maxByOrNull { it.score } ?: return null
        val tokens = ArrayList<String>()
        var at = n
        var key = best.text
        while (at > 0) {
            val node = lattice[at]?.get(key) ?: return null
            tokens += node.text
            at = node.from
            key = node.fromKey
        }
        tokens.reverse()
        return best to tokens
    }

    private fun traceTokens(
        trace: StringBuilder,
        label: String,
        best: Node,
        tokens: List<String>,
        candidates: Map<Long, List<Candidate>>,
        layout: Layout,
        contextBefore: String?
    ) {
        trace.append("%s %.2f: %s\n".format(label, best.score, tokens.joinToString(" ")))
        var prev = contextBefore ?: UNKNOWN_CONTEXT
        var position = 0
        for (token in tokens) {
            val candidate = candidates[spanKey(position, position + token.length)]?.firstOrNull { it.text == token }
            trace.append(
                "  %s: 모델 %.2f 통로 %.2f 경계 %s\n".format(
                    token, lnScore(prev, token), candidate?.channel ?: Float.NaN,
                    boundaryCost(layout, prev, position, position + token.length, token)?.let { "%.2f".format(it) } ?: "금지"
                )
            )
            prev = token
            position += token.length
        }
    }

    /** 왜 그렇게 고쳤는지(또는 안 고쳤는지) 점수를 풀어 쓴다. 조정·진단용. */
    fun explain(window: String, contextBefore: String? = null, protectLast: Boolean = false): String {
        val trace = StringBuilder()
        val pieces = tokenize(window)
        val cores = pieces.filter { it.soft }.map { it.core }
        if (cores.isEmpty()) return "고칠 수 있는 어절이 없다"
        decode(cores, contextBefore, trace, protectLast = protectLast)
        return trace.toString()
    }

    private fun spanKey(start: Int, end: Int): Long = (start.toLong() shl 32) or end.toLong()

    /** 한 위치에 도달한 상태가 너무 많으면 점수 높은 것만 남긴다. 보통은 몇 개뿐이다. */
    private fun prune(states: HashMap<String, Node>): Collection<Map.Entry<String, Node>> =
        if (states.size <= BEAM) states.entries
        else states.entries.sortedByDescending { it.value.score }.take(BEAM)

    private fun merge(a: List<Candidate>, b: List<Candidate>): List<Candidate> {
        val seen = HashMap<String, Candidate>()
        for (c in a + b) {
            val existing = seen[c.text]
            if (existing == null || c.channel > existing.channel) seen[c.text] = c
        }
        return seen.values.toList()
    }

    private fun collectSpans(
        text: String,
        n: Int,
        cores: List<String>,
        starts: IntArray,
        ends: IntArray,
        boundary: BooleanArray,
        pieceBoundary: BooleanArray
    ): List<Span> {
        val spans = ArrayList<Span>()
        for (k in cores.indices) {
            spans += Span(starts[k], ends[k], KIND_IDENTITY)
            if (k + 1 < cores.size && ends[k + 1] - starts[k] <= MAX_TOKEN_SYLLABLES) {
                spans += Span(starts[k], ends[k + 1], KIND_MERGE)
            }
            if (k + 2 < cores.size && ends[k + 2] - starts[k] <= MAX_TOKEN_SYLLABLES) {
                spans += Span(starts[k], ends[k + 2], KIND_MERGE)
            }
            // 모르는 긴 덩어리는 형태소 사전이 쪼갠 대로도 후보에 넣는다. ('오늘은날씨가좋아서')
            val core = cores[k]
            if (core.length >= 3 && lm.lnCount(core) == null) {
                splitOf(core)?.let { spaced ->
                    var at = starts[k]
                    for (piece in spaced.split(' ')) {
                        if (piece.isEmpty()) continue
                        spans += Span(at, at + piece.length, KIND_PIECE)
                        at += piece.length
                        pieceBoundary[at] = true
                    }
                }
            }
        }
        // 자유 구간: 원래 경계를 안에 최대 하나만 품는 짧은 구간. ('아버지가' '방에')
        for (i in 0 until n) {
            var inside = 0
            for (j in i + 1..minOf(n, i + MAX_FREE_SYLLABLES)) {
                spans += Span(i, j, KIND_FREE)
                // 다음 구간부터는 j 가 안쪽 경계가 된다.
                if (j < n && boundary[j] && ++inside > 1) break
            }
        }
        return spans
    }

    /**
     * @param edits 글자를 고친 후보도 낼 것인가. 멀쩡한 말 한가운데를 자르는 자유 구간이면 false.
     * @param realLength 구간 안 음절 자리 → 그 음절이 든 원래 어절이 멀쩡한 말이면 그 길이, 아니면 0([isRealWord]).
     */
    private fun candidatesFor(
        surface: String,
        kind: Int,
        edits: Boolean = true,
        extraCost: Float = 0f,
        lockLast: Boolean = false,
        slipsOnly: Boolean = false,
        sameSoundOnly: Boolean = false,
        evidentAllowed: Boolean = true,
        firmNoun: Boolean = false,
        firmStem: Int = 0,
        wholeWord: Boolean = true,
        endingFixed: Boolean = false,
        insideCommonNoun: (Int) -> Int = { 0 },
        bareNounBeforeNoun: Boolean = false,
        fakeVerb: Boolean = false,
        prevWord: String? = null,
        nextWord: String? = null,
        realLength: (Int) -> Int = { 0 }
    ): List<Candidate> {
        val identityCount = lm.lnCount(surface)
        val identityKnown = identityCount != null
        val out = ArrayList<Candidate>()
        when (kind) {
            KIND_IDENTITY, KIND_PIECE -> out += Candidate(surface, 0f)
            // 합치거나 자유롭게 자른 구간은 언어모델이 아는 어절이 될 때만 후보다. 모르는
            // 어절로 합치는 것을 허용하면 '여기 와 봐' 같은 짧은 말 셋이 통째로 붙어 버린다 —
            // 짧은 말 셋의 확률 곱은 낱말 하나의 확률보다 늘 작기 때문이다.
            // 조사·어미로 시작하는 조각('로', '이라는')은 어절이 아니므로 뺀다.
            KIND_MERGE, KIND_FREE -> if (identityKnown && !startsBound(surface)) out += Candidate(surface, 0f)
        }
        if (kind == KIND_PIECE || !edits) return out
        // 직함 앞의 말뭉치가 모르는 말은 이름이다('체체 회장은 → 체첸', '태니 대법원장') — 글자를 안 바꾼다.
        if (!evidentAllowed && !identityKnown && wholeWord) return out

        // 편집 후보는 언어모델이 아는 어절만. 모르는 말끼리 형태소 비용으로 겨루게 하면
        // 이름과 외래어('웰트쿠겔브루넨')가 비슷한 소리의 엉뚱한 말로 바뀐다.
        val penalty = knownPenalty(identityCount) + EDIT_BASE_COST
        // 사전 명사 + 앞말 받침과 맞는 조사([isFirmWord] 이고 명사가 어절 끝까지 가지 않는 것).
        val nounWithParticle = firmNoun && firmStem in 1 until surface.length
        // 고친 자리가 **드문 명사 줄기** 안이면(고새하셨어요·화인해볼게·개마) 그 줄기가 오타일 가능성이 크다. 막이가 걸릴 때만 잰다.
        val rareStem by lazy { if (lockLast || slipsOnly || extraCost > 0f) rareNounStemLength(surface, identityCount) else 0 }
        val commonStem by lazy { commonNounStemLength(surface) }
        for ((edited, cost) in singleEdits(
            surface,
            // 받침 넣기는 드문 말에만, 그리고 형태소 분석이 **아주 자연스러운** 말에는 안 한다 — '후련하고 → 훈련하고',
            // '좋아하시니까 → 좋아하십니까', '이해해주기 → 이해해주길' 은 분석 비용이 -1700 ~ -2600 인 멀쩡한 말이었다. 진짜 오타
            // ('채을' 2083, '생가보다' -470, '사란이' 584)는 그 위에 있다(큰 시험지 2026-10-11).
            // 말뭉치가 조사를 떼고도 전혀 모르는 말은 분석이 자연스러워도 받침을 넣어 본다 — 받침 빠진 오타가 드문 사전 명사에 떨어진다
            // ('저화를' -1392). '무제는'(무제 + 는)처럼 말뭉치가 아는 말은 그대로 둔다.
            // 활용형이라 말뭉치가 통째로 모를 뿐인 진짜 말('시해하고', '공여하는')은 뺀다([realWordForm]).
            codaSlip = (identityCount == null && !knowsWord(surface) && !realWordForm(surface)) ||
                ((identityCount ?: -1f) < CODA_SLIP_MAX_LN && (morphCost(surface) ?: 0) >= NATURAL_WORD_COST),
            nearKey = (identityCount ?: -1f) < NEAR_KEY_MAX_LN,
            cheonjiinKey = cheonjiin && (identityCount ?: -1f) < CJI_MAX_LN,
            realLength = realLength
        )) {
            val editedCount = lm.lnCount(edited)
            // 첫소리를 된소리로 바꾸는 편집은 고친 말이 흔할 때만 — 된소리 짝은 둘 다 진짜 말인 일이 많다('하시는 분인것 → 뿐인것',
            // '뿐인것' 은 말뭉치에 열다섯 번뿐이었다). 어미 '-ㄹ가 → -ㄹ까'('어떠실가요')는 쉬프트 오타라 그대로 둔다.
            if ((editedCount ?: 0f) < TENSE_ONSET_MIN_LN && tensedOnset(surface, edited) && !shiftedEnding(surface, edited)) continue
            // 고친 말이 **아주 흔하고** 원문은 드물거나 모르는 말이면 오타가 거의 확실하다 — '저녁애 → 저녁에', '괘찮아 → 괜찮아',
            // '어재 → 어제'. 그럴 때는 멀쩡한 말 막이(끝 음절 잠금·값 더 물리기)를 걷는다. 형태소 분석은 이런 오타도 멀쩡히 읽어 내서
            // (저녁/NNG 애/NNG, 괘/NNG 찮아/XSA) 막이가 진짜 오타까지 막고 있었다 — 오타 되살리기 64.3% → 51.9%(2026-10-13).
            // 막이가 지키려던 말('다졌습니다 → 따졌습니다', '처리되었으며 → 처리되었으면')은 고친 말이 흔하지 않아 그대로 막힌다.
            // 원문이 말뭉치에 **아예 없거나** 아주 드물 때만 연다 — '업어'(ln 2.8)는 드물어도 진짜 말이라 '없어' 로 바꿨다. 뒤에 직함이
            // 오는 말('허재 감독')은 이름이라 열지 않는다([evidentAllowed]). 실시간 창의 마지막 어절은 뒷말을 모르므로 말뭉치에 없는 말일 때만.
            val common = editedCount != null && editedCount >= EVIDENT_EDIT_LN && when {
                identityCount == null -> true
                slipsOnly -> false
                else -> identityCount < EVIDENT_SOURCE_LN
            }
            // 말뭉치에 없는 말의 **소리 같은 모음** 바꾸기(작성헤서 → 작성해서, 힘네 → 힘내)와, 드문 명사 줄기 안의 편집도 같은 까닭으로 연다.
            // 받침이 든 진짜 말끼리('서명하였다 → 설명하였다')는 줄기('서명')가 흔해서 그대로 막힌다.
            // 사전에 명사로 오른 말([isFirmWord])은 고친 말이 **아주 흔한 기능어**([EVERYDAY_WORDS])일 때만 연다 — 이름·지명이 말뭉치에
            // 없으면 흔한 말로 바뀌었다('수애 → 수에', '지단 → 집단', '지바 → 지방'). '어재 → 어제', '계소 → 계속', '중애 → 중에' 는 연다.
            // 첫소리만 바꾸는 편집('개어 → 깨어', '다졌습니다 → 따졌습니다')은 열지 않는다 — 된소리 짝이 둘 다 진짜 움직씨인 일이 흔하다.
            // 끝 음절(조사·어미)을 바꾸는 것은 받침을 채우거나 소리 같은 모음을 바꿀 때만 연다 — '결제기 → 결제가', '무승 → 무슨'.
            // 받침 채우기라도 끝 음절이 **어미**인 말이면 열지 않는다 — '메시지를 담다 → 담당'(용언이 명사가 됐다, 2026-10-14).
            val lastSafe = !lockLast || firstDifference(surface, edited) < surface.length - 1 ||
                sameSoundSwap(surface, edited) || (codaGain(surface, edited) && tagsOf(surface)?.second?.startsWith("E") != true)
            // 고친 자리가 **흔한 명사 줄기 안**이면 딴 낱말로 바꾸는 것이다 — '이관되지 → 일관되지'(이관 ln 6.4, 2026-10-14). 줄기 뒤의
            // 조사·어미 자리('저녁애 → 저녁에', '작성헤서 → 작성해서')는 그대로 연다.
            val insideCommonStem = firstDifference(surface, edited) < commonStem
            // 사전에 고유명사로만 오른 말('이정' — 사람 이름)은 일상 명사로 바꾸지 않는다('이정 → 일정', 기사 2026-10-15).
            val evident = evidentAllowed && editedCount != null &&
                (!firmNoun || edited in EVERYDAY_WORDS ||
                    (everydayNounEdit(edited) && !knowsWord(surface) && spacer?.isLexiconProperNoun(surface) != true &&
                        !(bareNounBeforeNoun && spacer?.isLexiconNoun(surface) == true))) &&
                lastSafe && !insideCommonStem && (
                (common && !onsetOnly(surface, edited)) ||
                    (identityCount == null && sameSoundSwap(surface, edited)) ||
                    ((!slipsOnly || identityCount == null) && firstDifference(surface, edited) < rareStem)
                )
            if (!evident && lockLast && edited.last() != surface.last() && (surface.last() to edited.last()) !in PARTICLE_SWAPS &&
                !endingShiftMiss(surface, edited)
            ) continue
            if (!evident && slipsOnly && !isShiftSlip(surface, edited) && !endingShiftMiss(surface, edited)) continue
            if (sameSoundOnly && !sameSoundSwap(surface, edited) && !isShiftSlip(surface, edited)) continue
            // 목적어 뒤의 '잇-'(잇다 — '뒤를 잇는', '케이블로 잇는')을 '있-' 으로 바꾸지 않는다. '있다' 는 목적어를 못 받는다(큰 시험지 2026-10-15).
            if (surface.startsWith("잇") && edited.startsWith("있") && prevWord != null && (prevWord.endsWith("을") || prevWord.endsWith("를"))) continue
            // 보조용언 '않-' 은 '-지' 뒤에만 온다 — 그 밖의 '안-'('영예를 안았다고', '꼭 안았다')은 '안다'(품다) 다(기사 2026-10-15).
            if (surface.startsWith("안") && edited.startsWith("않") &&
                (prevWord == null || !NEG_AUX_BEFORE.any { prevWord.endsWith(it) })
            ) continue
            // '잃다' 는 목적어를 받는다 — 주어 뒤의 '일-'('논란이 일었으며')은 '일다'(생기다) 다(기사 2026-10-15).
            if (surface.startsWith("일") && edited.startsWith("잃") && prevWord != null && (prevWord.endsWith("이") || prevWord.endsWith("가"))) continue
            // 같은 까닭으로 목적어 뒤의 말을 '생기다' 로 바꾸지 않는다('문신을 새길 수 → 생길 수', '무늬를 새길 → 생길', 2026-10-15).
            if (surface[0] != '생' && edited.startsWith("생") && edited.length >= 2 && edited[1] in "기길긴겨겼" &&
                prevWord != null && (prevWord.endsWith("을") || prevWord.endsWith("를"))
            ) continue
            // 관형형 뒤의 의존명사 '데'('조식 되는 데면', '주차장 있는 데가')를 '대' 로 바꾸지 않는다('대면' 이 됐다, 대화 말뭉치 2026-10-15).
            if (surface.startsWith("데") && edited.startsWith("대") && prevWord != null && prevWord.length >= 2 && adnominalEnd(prevWord)) continue
            // '갓을'(갓 + 을)을 '갔을' 로는 뒤에 의존명사가 올 때만('갓을 때 → 갔을 때'). '갓을 쓰고' 는 그대로(큰 시험지 2026-10-15).
            if (surface.startsWith("갓") && edited.startsWith("갔") && surface.length == 2 && surface[1] == '을' &&
                nextWord?.let { next -> AFTER_ADNOMINAL.any { next.startsWith(it) } } != true
            ) continue
            // 어미 없이 끝난 용언 줄기('머리 아프', 이름 속 '아프')의 끝 음절에 받침을 채워 관형형으로 만들지 않는다.
            if (firstDifference(surface, edited) == surface.length - 1 && codaGain(surface, edited) && bareStem(surface)) continue
            // 수 뒤의 단위 '군데'(네 군데의 → 군대의)는 그대로.
            if (surface.startsWith("군데") && !edited.startsWith("군데") && prevWord in COUNT_WORDS) continue
            // 흔한 한 음절 명사 + 조사·서술격('곳일까요' = 곳 + 일까요)의 명사를 딴 한 음절 명사로 바꾸지 않는다 — '주차장이 있는
            // 곳일까요 → 것일까요'(사람이 친 글 2026-10-15). 빠진 받침 채우기·소리 같은 모음은 그대로 겨룬다.
            if (monoNounSwap(surface, edited)) continue
            // 어절 첫 '아' 에 받침을 채워 부정 부사 '안' 을 만들지 않는다 — 뒤가 말뭉치가 아는 말이면 감탄사 '아' 를 붙여 친 것이다
            // ('아그런가요 → 안 그런가요', '아그러면 → 안 그러면', 사람이 친 대화 2026-10-15). 뜻이 정반대가 된다.
            if (surface.length >= 2 && surface[0] == '아' && edited[0] == '안' && edited.length == surface.length &&
                firstDifference(surface, edited) == 0 && lm.lnCount(surface.substring(1)) != null
            ) continue
            // 용언 줄기 + '-긴'(-기는 — '댓글 달긴 또 첨이네', '먹긴 했어')의 '긴' 을 딴 어미로 바꾸지 않는다('달긴 → 달린', 영화평 2026-10-15).
            if (surface.length >= 2 && surface.last() == '긴' && edited.length == surface.length && edited.last() != '긴' &&
                spacer?.firstMorpheme(surface)?.let { (end, head) -> end == surface.length - 1 && spacer.isVerbTag(head) } == true
            ) continue
            // 자유롭게 떼어 낸 조각이 어미·조사로 시작하면('예약할|라는데요') 앞말의 꼬리다 — 그 첫 음절을 바꿔 딴 낱말로 세우지
            // 않는다('예약할 하는데요', 사람이 친 글 2026-10-15). 자르는 자리와 고치는 자리가 겹치는 두 겹 고침이다.
            if (kind == KIND_FREE && firstDifference(surface, edited) == 0 &&
                (startsBound(surface) || BOUND_STARTS.any { surface.startsWith(it) })
            ) continue
            // 말뭉치가 모르지만 **자연스럽게 읽히는** 말에 받침을 채우는 것은 고친 말이 일상어일 때만([everydayEdit] — '도차했어 →
            // 도착했어', '피고해서 → 피곤해서', '여락 → 연락'). 분석 사전에는 드문 한자어가 다 올라 있어 오타 줄기('도차·사책')와 진짜 말
            // ('시음·서임·저부')을 못 가른다 — 기사·위키에서 '시음하고 → 신음하고', '서임하고 → 선임하고' 가 났다(2026-10-15).
            // 아예 못 읽는 말까지 넓히면('월드와이드 → 월드와일드' 를 막으려고) '뻐했어', '가봣는데' 같은 오타도 막혀서 안 넓혔다.
            if (identityCount == null && codaGain(surface, edited) && !everydayEdit(edited) && !endingFill(surface, edited) &&
                (morphCost(surface) ?: 0) < NATURAL_WORD_COST
            ) continue
            // 형태소 사전이 나눈 **멀쩡한 조각** 안에 받침을 채우는 것은 고친 말이 뻔할 때만 — '운전하는거 → 운전하는건' 은 막고(사용자가
            // 고른 말), 사전이 '더|분에' 로 잘못 나눈 '더분에 → 덕분에' 는 연다(2026-10-15).
            if (codaGain(surface, edited) && realLength(firstDifference(surface, edited)) < 0 && !evident) continue
            // 사전 명사(또는 흔한 명사들로 된 합성어) + 한 음절로 된 모르는 말('결제기' = 결제/기, '국방장관들')의 끝 음절을 딴 음절로
            // 바꾸지 않는다 — 합성어 꼬리를 조사나 딴 명사로 바꿨다('카드 결제기 → 결제가 → 결제시', '전 국방장관들 → 국방장관을',
            // 2026-10-14). 소리 같은 모음('저녁애 → 저녁에')·받침 채우기는 그대로 둔다.
            if (identityCount == null && firstDifference(surface, edited) == surface.length - 1 && edited.length == surface.length &&
                !sameSoundSwap(surface, edited) && !codaGain(surface, edited) &&
                surface.last() !in PARTICLE_SYLLABLES && surface.length >= 3 &&
                (spacer?.isLexiconNoun(surface.dropLast(1)) == true || (0 until surface.length - 1).all { insideCommonNoun(it) > 0 })
            ) continue
            // 문장 부호 앞 끝 음절은 바꾸지 않는다([decode] 의 punctuatedLast). 쉬프트 오타('햇다.')는 끝 음절이 아니라 그대로 고친다.
            if (endingFixed && edited.length == surface.length && edited.last() != surface.last()) continue
            // 연결 어미 '-며'(바꾸었으며·간다며)를 '-면' 으로, 기본형 '-하다' 를 '-한다' 로 바꾸지 않는다 — 둘 다 멀쩡한 말이고 뜻이 바뀐다
            // (기사 2026-10-14: '발전되었으며 → 발전되었으면', '걱정된다며 → 걱정된다면', '설명하다 → 설명한다'). '다' 앞에 ㄴ 을 넣는
            // 것은 어미 가운데서도 막는다 — '원하시다면 → 원하신다면'(둘 다 바른 말, 큰 시험지 2026-10-14).
            if (edited.length == surface.length && surface.last() == '며' && edited.last() == '면' && surface.dropLast(1) == edited.dropLast(1)) continue
            if (edited.length == surface.length && codaGain(surface, edited) && firstDifference(surface, edited) + 1 < surface.length &&
                surface[firstDifference(surface, edited) + 1] == '다' && Hangul.jongseongOf(edited[firstDifference(surface, edited)]) == 'ㄴ'
            ) continue
            // 자연스럽게 읽히는 말의 흔한 명사 줄기는 아예 안 바꾼다 — 값만 더 물리면 뒷말이 센 쪽('일관되지 않은')이 이겼다.
            if (insideCommonStem && extraCost > 0f && !isShiftSlip(surface, edited)) continue
            // 사전 명사(+조사)의 명사 자리 **모음·첫소리**는 오타가 뻔할 때([EVERYDAY_WORDS] 로 바뀔 때)만 바꾼다 — 값만 더 물리면 말뭉치에
            // 더 흔한 비표준 표기가 이겼다('포트 로열에서 → 로얄에서', 2026-10-14). 빠진 받침 채우기('그 사라이 → 사람이')와 소리 같은 모음
            // ('네일 → 내일', '재품 → 제품')은 값으로 겨룬다 — 사전에 드문 낱말로 오른 오타가 많다.
            if (!evident && firstDifference(surface, edited) < firmStem && !isShiftSlip(surface, edited) && !codaGain(surface, edited) &&
                !sameSoundSwap(surface, edited)
            ) continue
            // 사전 명사에 **앞말 받침과 맞는 조사**까지 붙은 말('야심과', '지병에', '사라의')은 명사 자리 받침도 채우지 않는다 — 조사까지
            // 맞춰 친 말은 멀쩡한 말이다. 값을 물려도 말뭉치가 모르는 어절이라 흔한 말('양심과·질병에·사람의')이 이겼다(기사·위키
            // 2026-10-14). 명사 하나만 친 것('차석 → 참석', '저점 → 점점')은 값으로 겨룬다.
            if (!evident && nounWithParticle && firstDifference(surface, edited) < firmStem && codaGain(surface, edited)) continue
            // 사람이 친 어절 속의 흔한 명사 글자도 안 바꾼다(합성어·이름 속까지 — [commonNounAt]). 명사 하나뿐인 말에서 **빠진 받침
            // 채우기**('세 시가이나 → 시간이나', '아치부터 → 아침부터')는 값으로 겨룬다 — 받침 빠뜨림은 폰에서 제일 흔한 오타다(2026-10-14).
            if (edited.length == surface.length && firstDifference(surface, edited) < surface.length && !isShiftSlip(surface, edited)) {
                val level = insideCommonNoun(firstDifference(surface, edited))
                // 받침 채우기를 값으로 겨루게 하는 것은 분석이 자연스러운 말(값이 붙는 말)일 때만 — 분석이 억지인 '지정서'(지정/서 조사)는
                // 값 없이 문맥만으로 '진정서' 가 됐다.
                if (level == 2 || (level == 1 && !(codaGain(surface, edited) && extraCost > 0f))) continue
            }
            if (editedCount != null && !(kind == KIND_FREE && startsBound(edited)) &&
                !hearsayToConnective(surface, edited)
            ) {
                // 멀쩡한 말 값은 편집 종류에 따라 다르게 물린다. 진짜 오타는 거의 소리 같은 모음(ㅐ↔ㅔ — '네일은 → 내일은', '중애는 →
                // 중에는')이나 빠진 받침('사라이 → 사람이', '야은 → 약은')이고, 이름·멀쩡한 말을 바꾸던 것은 첫소리·딴 모음('다졌습니다 →
                // 따졌습니다', '개어 → 깨어')이었다. 문맥 이득을 재 보니 이름('지단 → 집단' 3.06, '허재 → 헌재' 3.23)은 받침 값 2.5 + 문턱
                // 1.5 를 못 넘고 오타(사람이 4.87, 약은 4.13, 새도 4.93)는 넘었다(2026-10-14).
                // 깎아 주는 것은 친 말이 말뭉치에 없거나 아주 드물 때만이다 — 흔한 이름('베가 → 배가')은 그대로 비싸다. 받침은 **없던
                // 받침을 채울 때만** 깎는다('업어 → 없어' 같은 겹받침 채우기는 '엄마가 업어 줬어' 를 바꿨다).
                // 어절을 가르면서 고치는 편집('이나자와 → 이 남자와')은 깎지 않는다.
                // 뒤에 직함이 오는 말('태니 대법원장')도 이름이라 깎지 않는다([evidentAllowed]).
                // 받침은 **채운 자리 바로 뒤가 받침 뒤에만 오는 조사**일 때만 깎는다([particleWantsCoda] — '사라이 → 사람이', '야은 → 약은'). 조사가
                // 앞말과 안 맞는 것이 오타라는 단서다. 맞는 꼴('지병에')이나 용언('야하다 → 약하다' — 큰 시험지 2026-10-14)은 깎지 않는다.
                // 소리 같은 모음은 고친 말이 **일상어로 시작할 때만**([everydayHead] — '네일은 → 내일은', '중애는 → 중에는') 깎는다. 다 깎았더니
                // 기사·위키의 진짜 낱말이 흔한 말로 바뀌었다('메주 → 매주', '헤지를 → 해지를', '문화제로 → 문화재로', '내비게이션에 →
                // 네비게이션에', 큰 시험지 2026-10-14). 고친 말의 빈도로는 못 가른다(내일은 6.1, 매주 7.5).
                val rareSource = wholeWord && evidentAllowed && (identityCount ?: 0f) < EVIDENT_SOURCE_LN
                val firmCost = when {
                    rareSource && sameSoundSwap(surface, edited) && everydayHead(edited, firstDifference(surface, edited)) -> SAME_SOUND_FIRM_COST
                    rareSource && codaGain(surface, edited) && particleWantsCoda(surface, firstDifference(surface, edited)) -> CODA_FIRM_COST
                    else -> extraCost
                }
                // 말뭉치에 없는 '-하다' 로 읽힌 말([decode] 의 fakeVerb — '피고해서', '차고해')을 **일상어**로 고치는 편집은 멀쩡한 말 값을
                // 물리지 않는다. 일상어가 아니면 그대로 문다 — '입하하는 → 입학하는', '비정하는 → 지정하는'(기사 2026-10-15).
                val firm = if (!evident && extraCost > 0f && !isShiftSlip(surface, edited) && !endingShiftMiss(surface, edited) &&
                    !wrongHearsayFix(surface, edited) && !(fakeVerb && everydayEdit(edited))
                ) firmCost else 0f
                // 말뭉치가 조사를 떼고도 전혀 모르는 말에 받침 하나를 채워 **흔한 말**이 되면 받침 빠진 오타다 — 값을 덜 문다('여락 → 연락',
                // '저화를 → 전화를', '도차했어 → 도착했어'. 원문이 한 낱말로 자연스럽게 읽혀 모르는 말 값이 낮았다, 2026-10-15).
                // 고친 말이 일상 명사면([everydayNounEdit] — '주무이 → 주문이', '계사은 → 계산은') 아주 흔하지 않아도 같이 본다.
                // 활용형이라 말뭉치가 통째로 모를 뿐인 진짜 말('시해하고', '공여하는', '주요하다고', '화자들이', '지중해서')은 깎지 않는다 —
                // 다섯째 판의 이 깎기가 기사·위키에서 '시해하고 → 시행하고', '화자들이 → 환자들이' 를 냈다(큰 시험지 2026-10-15).
                // 고친 말이 일상어일 때만 깎는다 — 기사의 '입하하는 → 입학하는', '워룸 → 원룸', '마가진 → 망가진' 이 이 깎기로 넘었다.
                // 끝 음절(어미·조사 자리 — '갔더 → 갔던', '축제르 → 축제를')을 채우는 것은 일상어가 아니어도 깎는다.
                // 자유롭게 떼어 낸 조각('신으|세요 → 신은 세요')은 깎지 않는다.
                val dropped = if ((evident || everydayNounEdit(edited)) && kind != KIND_FREE &&
                    (everydayEdit(edited) || endingFill(surface, edited)) && identityCount == null &&
                    (editedCount ?: 0f) >= DROPPED_CODA_MIN_LN && codaGain(surface, edited) && !knowsWord(surface) && !realWordForm(surface)
                ) {
                    DROPPED_CODA_DISCOUNT
                } else 0f
                out += Candidate(edited, -(cost + penalty + firm - dropped))
            }
        }
        // 두 군데 고치기는 **없는 말**에만, 그리고 두 걸음 다 사람이 실제로 내는 오타 방향일 때만(햇는대요 → 했는데요).
        // 소리 같은 받침끼리 바꾸는 걸음이 끼면 딴 말이 된다('잊는게 → 잇는게 → 있는게').
        if (kind == KIND_IDENTITY && !identityKnown && !slipsOnly && !sameSoundOnly && surface.length <= MAX_DOUBLE_EDIT_SYLLABLES) {
            for ((once, cost1) in singleEdits(surface, realLength = { surface.length })) {
                for ((twice, cost2) in singleEdits(once, realLength = { once.length })) {
                    val twiceCount = lm.lnCount(twice)
                    // 두 군데 고치기는 사전 명사(+조사)에 열지 않는다 — '외국인치고 → 외국인이고'(2026-10-13).
                    val evident = evidentAllowed && !firmNoun && twiceCount != null && twiceCount >= EVIDENT_EDIT_LN
                    if (!evident && lockLast && twice.last() != surface.last() && (surface.last() to twice.last()) !in PARTICLE_SWAPS) continue
                    if (twice != surface && twiceCount != null) {
                        // 한 걸음이 쉬프트 오타면('햇는대요 → 했는대요 → 했는데요') 멀쩡한 말 값을 물리지 않는다.
                        val slip = isShiftSlip(surface, once) || isShiftSlip(once, twice)
                        out += Candidate(twice, -(cost1 + cost2 + EDIT_BASE_COST + if (slip || evident) 0f else extraCost))
                    }
                }
            }
        }
        return if (out.size <= 1) out else merge(out, emptyList())
    }

    /**
     * 맞는 '-는대'(전해 들은 말: '밥 먹는대')를 '-는데'로 바꾸는 편집인가. 말뭉치에 '-는대'가
     * 드물어서 맞게 친 것을 바꿨다(2026-09-24). 과거형·'있다·없다' 뒤의 '-는대'는 없는 꼴이라
     * ('했는대요', '있는대요' — 전해 들은 말이면 '했대', '있대') 그때는 고친다.
     */
    private fun hearsayToConnective(surface: String, edited: String): Boolean {
        val at = surface.indexOf("는대")
        if (at < 0 || edited.length != surface.length || !edited.startsWith("는데", at)) return false
        return at == 0 || surface[at - 1] !in NEUNDAE_WRONG_BEFORE
    }

    /** 원문이 흔한 말일수록 편집을 비싸게. ln(빈도)가 [KNOWN_FREE_LN] 을 넘는 만큼 물린다. */
    private fun knownPenalty(lnCount: Float?): Float =
        if (lnCount == null) 0f else KNOWN_PENALTY_PER_LN * maxOf(0f, lnCount - KNOWN_FREE_LN)

    /**
     * 구간 [start, end) 를 한 어절 [text] 로 삼을 때 띄어쓰기를 바꾸는 값. 허용되지 않는
     * 바꾸기면 null.
     *
     * 원래 경계가 아닌 곳에서 시작하면 어절을 자르는 것이다([SPLIT_COST]) — 단, 한 어절로
     * 무리 없이 분석되는 아는 말은 자르지 않고('연습문제'와 '연습 문제'는 둘 다 맞고, '해두고'를
     * '해 두고'로 바꿀 이유도 없다), 매인 형태소 앞에서도 자르지 않는다('발급받았다'의 '받',
     * '자녀들이'의 '들').
     *
     * 안에 원래 경계가 있으면 그걸 지우는 것이다. 그 자리에 매인 형태소(접미사·어미·조사)가
     * 시작할 때만 허용한다([BOUND_MERGE_COST], '안녕 하세요'의 '하', '먹었 어요'의 '어요').
     * '와 봐'처럼 띄어도 되는 말은 붙이지 않는다.
     */
    private fun boundaryCost(layout: Layout, prevKey: String, start: Int, end: Int, text: String): Float? {
        var cost = 0f
        if (!layout.boundary[start]) {
            val k = layout.owner[start]
            val core = layout.cores[k]
            val offset = start - layout.starts[k]
            // 사전에 오른 명사(합성어 포함)는 한가운데서 가르지 않는다 — '붉은빛을 → 붉은 빛을'(2026-10-13). 합성어는 붙여 써도 맞다.
            if (offset < layout.nounLength[k]) return null
            // 새로 만든 조각이 조사·어미로도 읽히는 한 음절('가', '지')이면 안 된다.
            if (text.length == 1 && couldBeBound(text)) return null
            if (!layout.pieceBoundary[start]) {
                // 형태소 사전이 나눈 자리가 아니면 자르는 근거가 언어모델뿐이다. 그럴 때는
                // 한 어절로 무리 없이 분석되는 말은 자르지 않고 ('연습문제', '저주받은', '해두고' —
                // 언어모델이 모르는 드문 낱말일 뿐이다), 두 조각이 실제로 잇달아 쓰인 적이
                // 있어야 하며('할 수'), 매인 형태소 앞('자녀|들이')이나 명사|명사('치과|의사'),
                // '-아/-어'|용언('불안해|했다') 자리는 손대지 않는다.
                // 한 어절로 분석되는 말은 자르지 않는다 — 였는데, 그것만으로는 못 막는다.
                // mecab 은 거의 모든 한글 덩어리를 어떻게든 분석해 낸다. '당신의선' 도
                // 당신+의+선 으로 읽고, '시간이걸린다' 도 한 어절이라고 한다. 그래서
                // "분석된다" 를 금지 조건으로 쓰면 **붙여 쓴 말이 영영 안 풀린다** —
                // 정답이 후보에 오르지도 못한 채 형태소 사전이 쪼갠 자리만 남는다
                // ('시간이걸린다' → '시간이걸 린다').
                //
                // 대신 **분석이 얼마나 자연스러웠는지**로 값을 매긴다. 한 낱말인 것들은
                // 비용이 낮고(중앙값 -1331) 붙여 쓴 구는 높다(중앙값 7121). 자연스러울수록
                // 자르기 비싸고, 억지스러울수록 싸다. 확실한 증거가 있으면 넘어설 수 있는
                // 벽이라 '시간이걸린다'(비용 2357, 정답이 19점 더 좋다)는 풀리고
                // '연습문제'(비용 167)는 그대로 남는다.
                // 언어모델이 **아는** 어절이면 한 낱말이라는 증거가 둘이다 — 형태소 분석이
                // 되고, 사람들이 실제로 그렇게 쓴다. 세게 말린다.
                //
                // 모르는 어절이면 증거가 형태소 분석 하나뿐인데 그건 약하다. mecab 은
                // '아버지가방에' 도 아버지+가방+에 로 멀쩡히 읽는다. 형태소 비용으로는
                // '국민청원'(1720)과 '아버지가방에'(1733)를 가를 수 없다 — 거의 같다.
                // 가르는 것은 **말뭉치에 있었느냐**다. 모르는 말은 덜 말린다.
                val seen = lm.lnCount(core) != null
                cost -= wellFormedPenalty(core) * (if (seen) 1f else UNSEEN_WELL_FORMED_FACTOR)
                // 매인 형태소 앞('자녀|들이')은 자르지 않는다. 절제 실험에서 이 금지는
                // 재현율을 **하나도** 깎지 않으면서 오교정만 줄였다(0.40 → 0.37%). 순이득이다.
                if (boundAt(core, offset)) return null
                // 앞 어절을 모르면 자를 근거가 없다.
                //
                // 예전에는 여기에 "두 조각이 실제로 잇달아 쓰인 적이 있어야 한다"
                // (`lnBigramCount != null`)가 붙어 있었다. 뺐다 — 절제 실험에서 이 요구는
                // 오교정을 **전혀** 줄이지 않으면서(0.27% 그대로) 망침만 늘렸다(34 → 43).
                // 말뭉치가 6천만 어절이라 멀쩡한 짝도 대부분 안 나온 탓이다. 본 적 없다는
                // 것이 틀렸다는 뜻은 아니다.
                if (prevKey == UNKNOWN_CONTEXT) return null
                // 명사|명사 자리('치과|의사')는 한 낱말인 합성어가 많아 자르기 조심스럽다.
                // 그렇다고 막아 버리면 '컴퓨터의|충돌', '기업|대표들이' 처럼 **띄어 써야 하는
                // 것들까지 통째로 막힌다** — 절제 실험에서 이 금지 하나가 띄어쓰기 복원을
                // 2.8%p 깎고 있었다(66.6 → 69.4%). 금지 대신 값을 매겨 증거와 겨루게 한다.
                // 조사·관형사·관형형 어미로 끝났으면 **어절이 거기서 끝난다.** 한국어에서
                // 이보다 분명한 경계 신호는 없다 ('컴퓨터의|충돌', '그|권리는', '할|책들은').
                // 이 신호가 있으면 명사|명사 조심도 필요 없다 — 조심해야 할 합성어는
                // '치과|의사' 처럼 조사 없이 붙는 것들이지 조사 뒤가 아니다.
                // 떼어 낸 조각이 조사·어미로 시작하면 어절이 될 수 없다.
                if (BOUND_STARTS.any { text.startsWith(it) }) cost -= BOUND_START_COST
                if (endsWithBoundaryMarker(prevKey)) {
                    // 관형사 뒤라도 **말뭉치가 아는 한 낱말** 한가운데면 자르지 않는다 — '한메일 → 한 메일', '이사진 → 이 사진'(2026-10-13).
                    // 낱말 머리로도 흔한 관형사('한·이·새·전…')만 본다 — '그 부분은', '첫 번째' 는 그대로 뗀다.
                    if (seen && endsWithDeterminer(prevKey) && prevKey in WORD_HEAD_DETERMINERS && isWellFormed(core)) return null
                    // 관형사와 뒷말 머리가 합쳐 사전에 오른 낱말('그날', '그중', '이번')도 자르지 않는다.
                    if (endsWithDeterminer(prevKey) && spacer?.joinsDeterminer(prevKey, text) == true) return null
                    cost += BOUNDARY_MARKER_BONUS
                } else if (nounJunction(prevKey, text)) {
                    // **명사|명사 자리는 자르지 않는다**(2026-10-13). 예전에는 값만 매겨 언어모델 증거와 겨루게 했는데, 기사·위키 58만
                    // 문장에서 그 증거가 합성어·이름을 5만 번 넘게 갈랐다 — '평양정상회담 → 평양 정상회담', '카카오게임즈',
                    // '현대자동차그룹이', '시장조사업체', '도착지는 → 도착 지는'. 합성어는 붙여 써도 맞는 말이라 가르는 것은 교정이 아니다.
                    // 앞이 때를 나타내는 말이나 대명사면('오늘날씨', '나오늘') 그대로 값만 매긴다.
                    // 뒤가 '앞에서·중에' 같은 자리 명사거나 '조심해' 같은 '-하다' 용언이면 합성어가 아니다([Spacer.startsApartFromNoun]).
                    if (!adverbialNoun(prevKey) && !apartFromNoun(prevKey, text)) return null
                    cost -= NOUN_JUNCTION_COST
                }
                if (endsWithAEo(prevKey) && spacer?.tagAt(core, offset)?.let { spacer?.isVerbTag(it) } == true) return null
                // 받침 있는 명사 뒤의 '이…' 는 조사·서술격 조사다('아들이래 → 아들 이래', 2026-10-13).
                if (text.startsWith("이") && copulaAfterNoun(prevKey)) return null
                // 같은 '-아/-어' 뒤라도 한 어절로 분석했을 때 **형태소 한가운데**면 자르지 않는다 — '떨어져야할' 은
                // 떨어지/VV 한 덩어리라 '떨어|져야할' 자리에서 시작하는 형태소가 없다(큰 시험지 2026-10-11).
                if (endsWithAEo(prevKey) && isWellFormed(core) && spacer?.tagAt(core, offset) == null) return null
                // 체언 뒤의 '받다·당하다·시키다·드리다' 는 한 낱말이다('발급받았다', '인계받았다').
                if (ATTACHABLE_VERBS.any { text.startsWith(it) } && endsNominal(prevKey)) return null
            } else if (text.startsWith("이") && (spacer?.boundaryTags(core, offset)?.second == "VCP" || copulaAfterNoun(prevKey))) {
                // 사전이 나눈 자리라도 통째 분석에서 거기가 **서술격 조사**('아들이래' = 아들/이/래)면 자르지 않는다 — '아들 이래'(이러다)로
                // 갈랐다(2026-10-13). 선어말어미까지 넓히지 않는 까닭은 아래 높임 검사의 주석과 같다.
                return null
            } else if (lm.lnCount(text) == null && spacer?.boundaryTags(core, offset)?.first?.isEmpty() == true &&
                spacer?.edgeTags(text)?.first?.let { spacer?.isVerbTag(it) } != true
            ) {
                // 사전이 나눈 자리라도 통째 분석에서 **형태소 한가운데**이고 떼어 낸 조각이 말뭉치에 없는 체언이면 자르지 않는다 —
                // '해외이적으로 → 해 외이적으로', '전국해양스포츠대회 → 전국해 양스포츠대회'(2026-10-13). 조각이 아는 말이거나
                // 용언으로 시작하면('들어가신다') 그대로 둔다 — '아버지가방에' 는 통째 분석이 '가방' 으로 읽어도 '방에' 를 안다.
                // 통째 분석을 못 믿는 것까지 넓히면 '현재는|품절입니다' 를 못 뗀다. 그래서 '미래에셋운용이 → 미래에 셋운용이' 는 남았다.
                return null
            } else if (spacer?.boundaryTags(core, offset)?.second?.startsWith("J") == true && LONG_PARTICLES.any { text.startsWith(it) }) {
                // 사전이 나눈 자리라도 통째 분석에서 그 뒤가 **두 음절짜리 조사**면 자르지 않는다 — '얘기치고는 → 얘기 치고는'
                // (2026-10-13). 한 음절 조사('가·이')는 '학교가기싫어'(가 = 가다)처럼 분석이 틀리기 쉬워 넣지 않는다.
                return null
            } else if (honorificAt(core, offset)) {
                // 형태소 사전이 나눈 자리라도, 그 자리가 **용언 줄기와 높임의 '-시-' 사이**면 자르지 않는다. 위의 매인 형태소 검사
                // ([boundAt])가 사전이 나눈 자리에서는 안 돌아서 샜다: '공유해주시기' 는 공유/해/주/시(높임)/기 인데 사전 나눔이
                // '공유해 주 시기'(시기 = 때)를 내서 '공유해주 시기' 가 됐다('연락해주시기'·'참석해주시기를' 도, 윈도우 보고 2026-10-12).
                //
                // 좁게 잡은 까닭(큰 시험지·띄어쓰기 자로 쟀다): 어미 전부로 넓히면 한 어절 분석이 '더자고' 를 더(용언)+자고(어미)로
                // 읽어 '더 자고' 가르기를 막았고, 선어말어미 전부로 넓히면 분석 사전이 '있' 을 선어말어미로도 읽어(감동/이/있/으면서)
                // '감동이 있으면서'·'선약이 있어' 가르기를 막았다. 조사도 같은 까닭('학교가기싫어' 의 '가')으로 넣지 않는다.
                return null
            }
            cost += SPLIT_COST
        }
        var merged = false
        for (p in start + 1 until end) {
            if (!layout.boundary[p]) continue
            if (!boundAt(text, p - start)) return null
            // 띄어 친 **홀로 선 '가'·'와'** 는 붙이지 않는다. 조사로도 읽히지만 '가다'·'오다'의
            // 반말 명령·평서형이 훨씬 흔하다 — '나 학교 가'가 '나 학교가'가 되고 '친구 와'가
            // '친구와'가 됐다(실시간 시험지, 2026-09-24). 조사 규칙(SpacingRules)이 같은 까닭으로
            // '가·와'를 안 붙이는 것과 맞춘다. 조사를 띄어 친 '친구 가 왔어'는 놓친다.
            val right = layout.owner[p]
            if (layout.starts[right] == p && layout.cores[right] in STANDALONE_VERBS) return null
            // 수관형사('한·두·몇')와 뒤의 단위는 붙이지 않는다 — '올 한 해 → 올 한해', '한 해 동안 → 한해 동안'(2026-10-13).
            if (layout.cores[layout.owner[p - 1]] in NUMERAL_DETERMINERS) return null
            // 뒤에 단위가 오는 수관형사를 앞말에 붙이지도 않는다 — '취임 한 달여 → 취임한 달여'(2026-10-15).
            if (layout.starts[right] == p && layout.cores[right] in NUMERAL_DETERMINERS && right + 1 < layout.cores.size &&
                COUNTER_HEADS.any { layout.cores[right + 1].startsWith(it) }
            ) return null
            // '-아야/-어야 하다·되다' 의 '하다·되다' 는 띄어 쓴다 — '내야 하는 → 내야하는'(2026-10-15).
            if (layout.starts[right] == p && layout.cores[layout.owner[p - 1]].let { it.length >= 2 && it.last() == '야' && tagsOf(it)?.second?.startsWith("E") == true } &&
                layout.cores[right][0] in "하해했합한할함되돼됐됩될"
            ) return null
            // '-아/-어' 뒤의 보조용언('보내 줘', '먹어 봐')은 띄어 쓰는 것이 원칙이고 붙여 써도 된다. 사람이 띄어 친 것을 붙이지
            // 않는다 — '카카오톡으로 보내 줘 → 보내줘'(2026-10-14).
            // 분석 사전은 '보내줘' 를 보내/VV 줘/EC 로도 읽어서 품사로는 못 가린다 — 앞 어절의 '-아/-어' 와 뒤 어절의 머리를 본다.
            if (layout.starts[right] == p && endsWithAEo(layout.cores[layout.owner[p - 1]]) &&
                AUXILIARY_HEADS.any { layout.cores[right].startsWith(it) }
            ) return null
            cost += BOUND_MERGE_COST
            merged = true
        }
        // 띄어 쓴 것을 붙여서 만든 말은 흔한 말이어야 한다. '준 만'을 '준만'(드문 오타)으로
        // 붙이는 것처럼, 드물게 나온 표기로 붙이는 것은 근거가 약하다.
        if (merged && (lm.lnCount(text) ?: 0f) < MERGE_MIN_LN_COUNT) return null
        return cost
    }

    /**
     * 이 어절을 "한 낱말" 로 보아 자르기를 얼마나 말릴 것인가.
     *
     * 형태소 분석 비용이 낮을수록(자연스러운 한 낱말일수록) 크고, 높을수록(억지로 분석한
     * 붙여쓴 구일수록) 작다. 분석이 아예 안 되면 0 — 말릴 근거가 없다.
     */
    /**
     * 조사·관형사·관형형 어미로 끝나는가. 그렇다면 그 자리는 어절 경계다.
     *
     * 관형사(MM)를 넣은 것이 중요하다. [Spacer.isNominalTag] 는 MM 을 체언으로 치는데,
     * 그러면 '그|권리는' 이 명사|명사로 잡혀 **자르지 말아야 할 자리로 오해받는다.**
     * 관형사 뒤는 늘 띄어 쓴다.
     */
    private fun endsWithBoundaryMarker(text: String): Boolean {
        if (text == LanguageModel.BOS || text == UNKNOWN_CONTEXT) return false
        val tail = tagsOf(text)?.second ?: return false
        return tail in BOUNDARY_TAILS
    }

    private fun wellFormedPenalty(core: String): Float {
        if (!isWellFormed(core)) return 0f
        val cost = morphCost(core) ?: return WELL_FORMED_MAX
        if (cost >= WELL_FORMED_FREE_COST) return 0f
        val span = (WELL_FORMED_FREE_COST - WELL_FORMED_FIRM_COST).toFloat()
        val above = (cost - WELL_FORMED_FIRM_COST).toFloat()
        return WELL_FORMED_MAX * (1f - (above / span).coerceIn(0f, 1f))
    }

    private fun couldBeBound(text: String): Boolean = spacer?.couldBeBound(text) ?: false

    /**
     * 받침으로 끝나는 명사인가 — 그 뒤에 붙은 '이…' 는 주격 조사나 서술격 조사('아들이래'·'학생이야')라 떼면 안 된다.
     * '창사이래(以來)' 처럼 뗄 것도 있지만 대화에서는 '-이래'(들은 말)가 훨씬 흔하다.
     */
    private fun copulaAfterNoun(text: String): Boolean =
        text.isNotEmpty() && text != LanguageModel.BOS && text != UNKNOWN_CONTEXT &&
            Hangul.hasJongseong(text.last()) && endsNominal(text) && tagsOf(text)?.second?.startsWith("NN") == true

    private fun apartFromNoun(before: String, after: String): Boolean {
        val spacer = this.spacer ?: return false
        if (before == LanguageModel.BOS || before == UNKNOWN_CONTEXT) return false
        val key = before + "\u0000" + after
        synchronized(apart) { apart[key]?.let { return it } }
        val answer = spacer.startsApartFromNoun(before, after)
        synchronized(apart) { apart[key] = answer }
        return answer
    }

    /** 관형사(MM)로 끝나는가. */
    private fun endsWithDeterminer(text: String): Boolean = tagsOf(text)?.second == "MM"

    /**
     * 명사|명사 자리에서 앞말이 **부사처럼 쓰이는 명사**인가 — 때를 나타내는 말('오늘·지금·주말')이나 대명사('나·우리').
     * 이 뒤는 합성어가 아니라 딴 어절이다('오늘|날씨가', '나|오늘'). [nounJunction] 자리를 막을 때 이것만 남긴다.
     */
    private fun adverbialNoun(text: String): Boolean =
        text in ADVERBIAL_NOUNS || tagsOf(text)?.second == "NP"

    /**
     * 앞 조각이 체언으로 끝나고 뒤 조각이 체언으로 시작하는가.
     * 분석이 안 되는 조각('로크로', '어소시에이트')은 대개 이름이라 체언으로 친다.
     */
    private fun nounJunction(before: String, after: String): Boolean {
        val spacer = this.spacer ?: return false
        val last = tagsOf(before)?.second ?: return tagsOf(after)?.first?.let { spacer.isNominalTag(it) } ?: true
        val first = tagsOf(after)?.first ?: return spacer.isNominalTag(last)
        return spacer.isNominalTag(last) && spacer.isNominalTag(first)
    }

    /** 체언으로 끝나는가. 분석이 안 되는 것(이름)도 체언으로 친다. */
    private fun endsNominal(text: String): Boolean {
        val spacer = this.spacer ?: return false
        val last = tagsOf(text)?.second ?: return true
        return spacer.isNominalTag(last)
    }

    private fun tagsOf(text: String): Pair<String, String>? {
        val spacer = this.spacer ?: return null
        synchronized(edgeTags) { if (edgeTags.containsKey(text)) return edgeTags[text] }
        val tags = spacer.edgeTags(text)
        synchronized(edgeTags) { edgeTags[text] = tags }
        return tags
    }

    private fun startsBound(text: String): Boolean {
        val spacer = this.spacer ?: return false
        synchronized(boundStarts) { boundStarts[text] }?.let { return it }
        val bound = spacer.startsWithBoundMorpheme(text) ?: false
        synchronized(boundStarts) { boundStarts[text] = bound }
        return bound
    }

    private fun isWellFormed(text: String): Boolean {
        val spacer = this.spacer ?: return false
        synchronized(wellFormed) { wellFormed[text] }?.let { return it }
        val formed = spacer.isWellFormed(text)
        synchronized(wellFormed) { wellFormed[text] = formed }
        return formed
    }

    /**
     * 사람이 친 이 어절이 **멀쩡한 말**인가 — 말뭉치가 알거나, 형태소 사전이 한 어절로 무리 없이 읽는다.
     *
     * 멀쩡한 말은 사람이 실제로 내는 오타 방향으로만 고친다([singleEdits] 의 `realLength`). 그 밖의 편집은 **딴 말로 바꾸는 것**이라
     * 문맥 점수가 조금 낫다고 해서 자동으로 바꾸면 안 된다. 큰 시험지(챗봇 답 6천 문장, 2026-10-11)에서 디코더가 글자를 바꾼 것을
     * 하나씩 판정해 보니 틀린 것이 거의 이 꼴이었다 — '깨 볶는 → 개 볶는', '짐 싸세요 → 사세요', '썸 → 섬', '잊는게 → 있는게',
     * '후련하고 → 훈련하고', '앓아 → 알아', '날인지는 → 말인지는'.
     *
     * 말뭉치가 흔한 오타('잇는', '봣는데')도 알기 때문에 이것만으로는 오타와 바른 말이 안 갈린다. 그래서 막는 것은 **방향**이다 —
     * 쉬프트를 놓친 것(햇→했, 가→까), 겹받침 한쪽을 빠뜨린 것(괜찬→괜찮, 업→없)은 멀쩡한 말에도 그대로 고친다.
     */
    private fun isRealWord(core: String): Boolean = lm.lnCount(core) != null

    /**
     * 사람이 친 이 어절이 **멀쩡한 낱말로 볼 만큼 확실한가** — 글자를 바꾸는 편집을 비싸게 하고 끝 음절을 잠그는 근거(2026-10-13).
     *
     * [isRealWord](말뭉치가 앎)는 이 질문의 답이 아니다. 말뭉치는 흔한 오타('봣는데', '재밋게')도 안다. 형태소 분석이 자연스러운지도
     * 답이 아니다 — 분석 사전은 '햇어'(햇/VV+EP)·'잣어'·'새요'(NNG)·'출발햇어' 를 멀쩡하게 읽는다(그렇게 막았다가 쉬프트 오타를
     * 못 고쳤다). 그래서 **사전에 명사로 통째로 올라 있는가**만 본다 — 조사를 떼고 봐도 된다('지바', '허재', '여수의', '업이').
     * 단 조사가 받침과 안 맞으면('채을', '사라이', '주의을') 오타다. 용언의 어미를 바꾸는 오교정은 끝 음절 잠금이 막는다([decode]).
     * 기사 58만 문장에서 디코더가 글자를 바꾼 4,584곳을 보니 이런 낱말을 흔한 딴 말로 바꾼 것이 대부분이었다 — '발간 → 빨간',
     * '실재 → 실제', '서명하였다 → 설명하였다', '묵었습니다 → 묶었습니다', '지바 → 지방', '허재 → 헌재'.
     */
    /**
     * '-을/-를' 뒤에 글자를 고쳐 '없-·있-' 을 만드는가. '없다·있다' 는 목적어를 못 받으니 그 꼴은 거의 언제나 딴 동사를 망친 것이다
     * — '아기를 업어 줬어 → 아기를 없어 줬어', '다리를 잇는 → 있는'(2026-10-13). 원래부터 '없·있' 이었으면 손댄 것이 아니다.
     */
    private fun objectThenExistential(prevKey: String, candidate: String, original: String, at: Int): Boolean {
        val head = candidate.firstOrNull() ?: return false
        if (head != '없' && head != '있') return false
        if (at < original.length && original[at] == head) return false
        val last = prevKey.lastOrNull() ?: return false
        return last == '을' || last == '를'
    }

    /** [core] 가 사전에 오른 명사(+조사)면 그 명사의 길이, 아니면 0. 그 안을 가르면 합성어를 쪼개는 것이다. */
    private fun lexiconNounLength(core: String): Int {
        val spacer = this.spacer ?: return 0
        if (spacer.isLexiconNoun(core)) return if (core.length >= 2) core.length else 0
        for (particle in FIRM_PARTICLES) {
            if (!core.endsWith(particle) || core.length <= particle.length) continue
            val base = core.dropLast(particle.length)
            if (base.length >= 2 && spacer.isLexiconNoun(base)) return base.length
        }
        return 0
    }

    /** [surface] 와 [edited] 가 처음 다른 음절 자리(같으면 길이). */
    private fun firstDifference(surface: String, edited: String): Int {
        val n = minOf(surface.length, edited.length)
        for (i in 0 until n) if (surface[i] != edited[i]) return i
        return n
    }

    /** 한 음절의 받침을 채운 것인가 — 없던 받침을 넣거나 겹받침 한쪽을 채웠다('계소 → 계속', '만아 → 많아'). */
    private fun codaGain(surface: String, edited: String): Boolean {
        if (surface.length != edited.length) return false
        val at = firstDifference(surface, edited)
        if (at >= surface.length || !surface.regionMatches(at + 1, edited, at + 1, surface.length - at - 1)) return false
        val a = Hangul.decompose(surface[at]) ?: return false
        val b = Hangul.decompose(edited[at]) ?: return false
        if (a.first != b.first || a.second != b.second || a.third == b.third) return false
        return (a.third == 0 && b.third != 0) || (Hangul.JONGSEONG[a.third] to Hangul.JONGSEONG[b.third]) in CODA_COMPLETIONS
    }

    /** 한 음절의 첫소리만 바꾼 것인가('개어 → 깨어'). */
    private fun onsetOnly(surface: String, edited: String): Boolean {
        if (surface.length != edited.length) return false
        val at = firstDifference(surface, edited)
        if (at >= surface.length || !surface.regionMatches(at + 1, edited, at + 1, surface.length - at - 1)) return false
        val a = Hangul.decompose(surface[at]) ?: return false
        val b = Hangul.decompose(edited[at]) ?: return false
        return a.first != b.first && a.second == b.second && a.third == b.third
    }

    /** [surface] 의 [at] 자리가 받침 없는 음절이고 바로 뒤가 **받침 뒤에만 오는 조사**로 끝나는가('사라|이', '야|은'). */
    private fun particleWantsCoda(surface: String, at: Int): Boolean =
        at < surface.length && !Hangul.hasJongseong(surface[at]) && surface.substring(at + 1) in AFTER_CONSONANT

    /** [edited] 가 일상어([EVERYDAY_WORDS])로 시작하고 고친 자리 [at] 이 그 일상어 안인가('내일은' 의 '내일'). */
    /**
     * [edited] 가 일상 명사(+조사)인가 — 사전에만 있고 말뭉치가 모르는 드문 명사('여락', '저화', '자소')에 받침 빠진 오타가 떨어졌을 때
     * 이 명사로는 고친다('연락', '전화를', '장소는', 2026-10-15). '문제' 는 '무제(한 무제)' 와 겹쳐 넣지 않는다.
     */
    /** 어미 없이 끝난 용언 줄기인가 — 줄기 + '다' 를 말뭉치가 알고 용언으로 읽힌다('아프', '슬프'). 홀로는 분석이 안 된다. */
    private fun bareStem(surface: String): Boolean {
        if (Hangul.hasJongseong(surface.last())) return false
        val base = surface + "다"
        if ((lm.lnCount(base) ?: 0f) < BARE_STEM_LN) return false
        return tagsOf(base)?.first?.let { it == "VA" || it == "VV" } == true
    }

    /** 관형형으로 끝나는가('되는·있는·좋은·할') — 끝 음절이 '는' 이거나 받침 ㄴ·ㄹ 이고 분석 끝이 관형형 어미다. */
    private fun adnominalEnd(word: String): Boolean {
        val last = word.last()
        if (last != '는' && Hangul.jongseongOf(last) != 'ㄴ' && Hangul.jongseongOf(last) != 'ㄹ') return false
        return tagsOf(word)?.second == "ETM"
    }

    /** 끝 음절에 받침을 채워 **어미·조사 음절**이 되는가('갔더 → 갔던', '축제르 → 축제를'). 외래어 끝('커프 → 커플')은 아니다. */
    private fun endingFill(surface: String, edited: String): Boolean =
        firstDifference(surface, edited) == surface.length - 1 && edited.last() in ENDING_FILL_SYLLABLES

    /** 고친 말이 일상어인가 — [EVERYDAY_WORDS] 이거나 [EVERYDAY_NOUNS] 로 시작한다('도착했어', '피곤해서', '연락은'). */
    private fun everydayEdit(edited: String): Boolean =
        edited in EVERYDAY_WORDS || EVERYDAY_NOUNS.any { edited.startsWith(it) && edited.length - it.length <= EVERYDAY_TAIL_MAX }

    private fun everydayNounEdit(edited: String): Boolean =
        EVERYDAY_NOUNS.any { edited.startsWith(it) && (edited.length == it.length || edited.substring(it.length) in EVERYDAY_NOUN_TAILS) }

    private fun everydayHead(edited: String, at: Int): Boolean =
        EVERYDAY_WORDS.any { at < it.length && edited.startsWith(it) }

    /** '-ㄹ가 → -ㄹ까' 를 어절 끝이나 뒤에 '요' 가 붙은 꼴에서 고치는 것인가('할가', '어떠실가요'). */
    private fun shiftedEnding(surface: String, edited: String): Boolean {
        if (endingShiftMiss(surface, edited)) return true
        return surface.endsWith("가요") && edited.endsWith("까요") && endingShiftMiss(surface.dropLast(1), edited.dropLast(1))
    }

    /** 한 음절의 첫소리만 예사소리에서 된소리로(ㄱ→ㄲ, ㄷ→ㄸ, ㅂ→ㅃ, ㅅ→ㅆ, ㅈ→ㅉ) 바꾼 것인가. */
    private fun tensedOnset(surface: String, edited: String): Boolean {
        if (!onsetOnly(surface, edited)) return false
        val at = firstDifference(surface, edited)
        val a = Hangul.CHOSEONG[Hangul.decompose(surface[at])?.first ?: return false]
        val b = Hangul.CHOSEONG[Hangul.decompose(edited[at])?.first ?: return false]
        return (a to b) in TENSE_PAIRS
    }

    /** 한 음절의 모음만 소리 같은 짝(ㅐ↔ㅔ, ㅚ·ㅙ·ㅞ)으로 바꾼 것인가 — 한국어 오타에서 가장 흔한 꼴이다. */
    private fun sameSoundSwap(surface: String, edited: String): Boolean {
        if (surface.length != edited.length) return false
        val at = firstDifference(surface, edited)
        if (at >= surface.length || surface.regionMatches(at + 1, edited, at + 1, surface.length - at - 1).not()) return false
        val a = Hangul.decompose(surface[at]) ?: return false
        val b = Hangul.decompose(edited[at]) ?: return false
        if (a.first != b.first || a.third != b.third) return false
        val pair = setOf(Hangul.JUNGSEONG[a.second], Hangul.JUNGSEONG[b.second])
        return pair == setOf('ㅐ', 'ㅔ') || (pair.size == 2 && SAME_SOUND_VOWELS.containsAll(pair) && 'ㅐ' !in pair && 'ㅔ' !in pair)
    }

    /**
     * [surface] 가 **드문 명사 줄기**로 시작하면 그 줄기의 길이(아니면 0) — '고새|하셨어요', '화인|해볼게', '개마'. 줄기가 말뭉치에
     * 드물면([RARE_STEM_LN]) 줄기 자체가 오타일 가능성이 크다. '서명|하였다' 처럼 줄기가 흔한 말이면 0 이다.
     */
    private fun rareNounStemLength(surface: String, identityCount: Float?): Int {
        if ((identityCount ?: 0f) >= RARE_STEM_LN) return 0
        val (end, head) = spacer?.firstMorpheme(surface) ?: return 0
        if (head != "NNG" && head != "NNP") return 0
        return if ((lm.lnCount(surface.substring(0, end)) ?: 0f) < RARE_STEM_LN) end else 0
    }

    /**
     * [surface] 의 첫 형태소가 **말뭉치에 흔한** 두 음절 넘는 명사면 그 길이('이관되지' → 2, 이관 ln 6.4). 아니면 0. 그 안을 고치면
     * 딴 낱말이 된다 — 오타가 뻔해 보여도([rareNounStemLength] 의 반대쪽) 막이를 걷지 않는다.
     */
    private fun commonNounStemLength(surface: String): Int {
        val (end, head) = spacer?.firstMorpheme(surface) ?: return 0
        if (end < 2 || end > surface.length || (head != "NNG" && head != "NNP")) return 0
        val stem = surface.substring(0, end)
        if ((lm.lnCount(stem) ?: 0f) < COMMON_STEM_LN) return 0
        // 줄기 뒤가 '-하다·-되다' 면 그 움직씨가 말뭉치에 **있어야** 진짜 말이다 — '이관되지'(이관된·이관하는 이 있다)는 지키고,
        // '가사합니다'(가사하다는 없다 — 감사합니다 오타)는 연다(2026-10-14).
        return if (verbAttested(surface, end)) end else 0
    }

    /**
     * 말뭉치가 [surface] 를 통째로는 몰라도 **진짜 말의 활용형·곡용형**인가 — 말뭉치가 아는 명사 + 말뭉치에 있는 '-하다·-되다'
     * ('시해하고' = 시해 + 시해한…, '주요하다고'), 또는 말뭉치가 아는 명사 + 조사('화자들이', '지중해서').
     */
    private fun realWordForm(surface: String): Boolean {
        // 말뭉치가 아는 명사 + 조사(글자로 뗀다 — 분석 사전은 '지중해서' 를 지중 + 하다 로 읽는다). '-들' 도 뗀다('화자들이').
        for (particle in REAL_FORM_PARTICLES) {
            if (surface.length < particle.length + 2 || !surface.endsWith(particle)) continue
            var stem = surface.dropLast(particle.length)
            if (stem.length >= 3 && stem.endsWith("들")) stem = stem.dropLast(1)
            if ((lm.lnCount(stem) ?: 0f) >= REAL_STEM_LN) return true
        }
        // 흔한 명사 + 한자 접미사 '-사'(史·社 — '중국사', '헌법사').
        if (surface.length >= 3 && surface.last() == '사' && (lm.lnCount(surface.dropLast(1)) ?: 0f) >= COMMON_STEM_LN) return true
        val (end, head) = spacer?.firstMorpheme(surface) ?: return false
        // 사전에 오른 용언 줄기의 활용형('주요하다고' = 주요하/VA, '신으세요' = 신/VV, '새기면')도 진짜 말이다.
        // 뒤 조각의 품사는 통째 분석에서 본다 — '면' 만 떼어 읽으면 명사다.
        val next = if (end < surface.length) spacer?.boundaryTags(surface, end)?.second else null
        if (end >= 1 && end <= surface.length && spacer?.isVerbTag(head) == true && (morphCost(surface) ?: Int.MAX_VALUE) < NATURAL_FORM_COST) {
            return end == surface.length || next?.startsWith("E") == true
        }
        if (end < 2 || end >= surface.length || (head != "NNG" && head != "NNP" && head != "XR")) return false
        val stem = surface.substring(0, end)
        if ((lm.lnCount(stem) ?: 0f) < REAL_STEM_LN) return false
        if (surface[end] in VERB_SUFFIX_HEADS) return verbAttested(surface, end)
        return next?.let { it.startsWith("J") || it == "XSN" } == true
    }

    /**
     * [surface] 의 첫 음절이 **흔한 한 음절 명사**이고 뒤가 조사·서술격 조사인데, [edited] 가 그 명사 글자만 바꿔 딴 명사로 만드는가
     * ('곳일까요 → 것일까요'). 받침 채우기·소리 같은 모음·쉬프트 오타는 아니다.
     */
    private fun monoNounSwap(surface: String, edited: String): Boolean {
        if (surface.length < 2 || edited.length != surface.length || surface.substring(1) != edited.substring(1)) return false
        if (codaGain(surface, edited) || sameSoundSwap(surface, edited) || isShiftSlip(surface, edited)) return false
        if ((lm.lnCount(surface.substring(0, 1)) ?: 0f) < MONO_NOUN_LN) return false
        val spacer = this.spacer ?: return false
        fun nounHead(word: String) = spacer.firstMorpheme(word)?.let { (end, head) -> end == 1 && head.startsWith("NN") } == true
        if (!nounHead(surface) || !nounHead(edited)) return false
        val next = spacer.boundaryTags(surface, 1)?.second ?: return false
        return next.startsWith("J") || next.startsWith("VCP")
    }

    /** [surface] 가 명사 + '-하다·-되다' 꼴로 읽히는데 그 움직씨가 말뭉치에 없는가([verbAttested]). 말뭉치가 아는 어절이면 아니다. */
    private fun unattestedVerb(surface: String): Boolean {
        if (knowsWord(surface)) return false
        val (end, head) = spacer?.firstMorpheme(surface) ?: return false
        if (end < 1 || end >= surface.length || (head != "NNG" && head != "NNP")) return false
        return surface[end] in VERB_SUFFIX_HEADS && !verbAttested(surface, end)
    }

    /** [surface] 의 앞 [end] 음절(명사) 뒤가 '-하다·-되다' 꼴이면 그 움직씨가 말뭉치에 있는가. 그런 꼴이 아니면 true. */
    private fun verbAttested(surface: String, end: Int): Boolean {
        if (end >= surface.length) return true
        if (surface[end] !in VERB_SUFFIX_HEADS) return true
        val stem = surface.substring(0, end)
        return VERB_PROBES.any { lm.lnCount(stem + it) != null }
    }

    /** [core] 속의 흔한 명사 자리([Spacer.nounMorphemeRanges] 중 말뭉치 빈도 [COMMON_STEM_LN] 넘는 것). 같은 어절은 한 번만. */
    private fun nounRanges(core: String): List<IntRange> {
        val spacer = this.spacer ?: return emptyList()
        synchronized(nounRangeCache) { nounRangeCache[core]?.let { return it } }
        // 두 음절 넘는 명사 조각이 **모두** 흔해야 한다. 하나라도 드물면 분석이 억지다 — 천지인 오타 '기타리고'(기다리고)를 기타/리고
        // (리고 ln 3.9)로 읽는다. 그러면 아무것도 지키지 않는다.
        val all = spacer.nounMorphemeRanges(core).map { it to (lm.lnCount(core.substring(it.first, it.last + 1)) ?: 0f) }
        // 명사 하나 + '-하다·-되다' 인데 그 움직씨가 말뭉치에 없으면('가사합니다' — 감사합니다 오타) 지키지 않는다([verbAttested]).
        val unattestedVerb = all.size == 1 && all[0].first.first == 0 && !verbAttested(core, all[0].first.last + 1)
        val ranges = if (unattestedVerb || all.any { it.second < COMMON_STEM_LN }) emptyList() else all.map { it.first }
        synchronized(nounRangeCache) { nounRangeCache[core] = ranges }
        return ranges
    }

    /** 직함·호칭인가([TITLES]) — 한 음절짜리('씨·님')는 그 말 하나일 때만, 두 음절 넘는 것은 조사가 붙어도('감독이·선수는'). */
    private fun titleWord(word: String): Boolean = TITLES.any { title ->
        word == title || (title.length >= 2 && word.startsWith(title) && word.length - title.length <= 3)
    }

    private fun isFirmWord(core: String): Boolean {
        val spacer = this.spacer ?: return false
        return lexiconNounBase(spacer, core)
    }

    /** [edited] 가 [surface] 에서 받침 ㅅ 하나를 ㅆ 으로 바꾼 것인가 — 쉬프트를 놓친 오타('햇어 → 했어', '잣어 → 잤어'). */
    private fun isShiftSlip(surface: String, edited: String): Boolean {
        if (surface.length != edited.length) return false
        var diff = -1
        for (i in surface.indices) if (surface[i] != edited[i]) { if (diff >= 0) return false; diff = i }
        if (diff < 0) return false
        val a = Hangul.decompose(surface[diff]) ?: return false
        val b = Hangul.decompose(edited[diff]) ?: return false
        return a.first == b.first && a.second == b.second && a.third == JONG_S && b.third == JONG_SS
    }

    /** '있는대요·했는대' 처럼 **없는 꼴**의 '-는대' 를 '-는데' 로 고치는 것인가([hearsayToConnective] 의 반대쪽). 멀쩡한 말 값을 안 물린다. */
    private fun wrongHearsayFix(surface: String, edited: String): Boolean {
        val at = surface.indexOf("는대")
        return at > 0 && edited.length == surface.length && edited.startsWith("는데", at) &&
            surface[at - 1] in NEUNDAE_WRONG_BEFORE && surface.regionMatches(0, edited, 0, at)
    }

    /** 어미 '-ㄹ까' 를 쉬프트 없이 친 것인가('있을가 → 있을까', '할가 → 할까'). 끝 음절 잠금이 받아 주는 유일한 어미 고치기다. */
    private fun endingShiftMiss(surface: String, edited: String): Boolean =
        surface.length == edited.length && surface.length >= 2 && surface.last() == '가' && edited.last() == '까' &&
            surface.regionMatches(0, edited, 0, surface.length - 1) && Hangul.jongseongOf(surface[surface.length - 2]) == 'ㄹ'

    private fun lexiconNounBase(spacer: Spacer, core: String): Boolean {
        if (spacer.isLexiconNoun(core)) return core.length >= 2
        for (particle in FIRM_PARTICLES) {
            if (!core.endsWith(particle) || core.length <= particle.length) continue
            val base = core.dropLast(particle.length)
            if (!spacer.isLexiconNoun(base)) continue
            val open = !Hangul.hasJongseong(base.last())
            // 조사가 앞말 받침과 안 맞으면 오타다('채을' → 책을, '사라이' → 사람이).
            if (particle in AFTER_CONSONANT && open) return false
            if (particle in AFTER_VOWEL && !open && !(particle == "로" && Hangul.jongseongOf(base.last()) == 'ㄹ')) return false
            return true
        }
        return false
    }

    /**
     * [text] 의 [syllableIndex] 음절이 **높임의 '-시-'** 인가 — 한 어절로 무리 없이 읽었을 때 거기서 선어말어미가 시작하고, 그 음절이
     * '시·셨·셔·세…' 이고, 앞 음절에 받침이 없다(줄기 뒤의 '-시-' 는 늘 받침 없는 음절 뒤에 온다: 주시·하시·가시·받으시).
     * 받침을 보는 것은 분석 사전의 헛읽기를 거르려는 것이다 — '관련된시나' 를 관련/된/시(높임)/나 로 읽는데, '된' 뒤에는 '-시-' 가 못 온다.
     */
    private fun honorificAt(text: String, syllableIndex: Int): Boolean {
        if (syllableIndex !in 1 until text.length || text[syllableIndex] !in HONORIFIC_SYLLABLES) return false
        if (Hangul.hasJongseong(text[syllableIndex - 1])) return false
        val spacer = this.spacer ?: return false
        val key = "$syllableIndex\u0001$text"
        synchronized(endingJunctions) { endingJunctions[key] }?.let { return it }
        val honorific = spacer.tagAt(text, syllableIndex) == "EP"
        synchronized(endingJunctions) { endingJunctions[key] = honorific }
        return honorific
    }

    private fun boundAt(text: String, syllableIndex: Int): Boolean {
        val spacer = this.spacer ?: return false
        val key = "$syllableIndex\u0001$text"
        synchronized(boundJunctions) { boundJunctions[key] }?.let { return it }
        val bound = spacer.boundMorphemeStartsAt(text, syllableIndex) ?: false
        synchronized(boundJunctions) { boundJunctions[key] = bound }
        return bound
    }

    private fun lnScore(prevKey: String, text: String): Float {
        val prev = if (prevKey == UNKNOWN_CONTEXT) null else prevKey
        val known = lm.lnConditional(prev, text) ?: return politeScore(prev, text) ?: unknownScore(text)
        return known + usagePenalty(text)
    }

    /**
     * 말뭉치가 모르는 어절이 **아는 어절 + 높임 '요'**('무거워서요' = 무거워서 + 요, '취해서요', '학교요')면 그 어절 값에서 조금 깎은 값.
     * '요' 는 거의 아무 어미·명사 뒤에나 붙어 말뭉치가 다 못 본다. 모르는 말 값을 물리면 한 글자 다른 흔한 말이 이겼다('무거워서요 →
     * 무서워서요', '취해서요 → 위해서요', 사람이 친 대화 2026-10-15). 앞 어절도 모르면 null.
     */
    private fun politeScore(prev: String?, text: String): Float? {
        if (text.length < 3 || text.last() != '요') return null
        val stem = text.dropLast(1)
        // 붙여 친 덩어리('하면돼', '가는걸로' — 사전이 가른다)에 '요' 가 붙은 것은 그대로 모르는 말로 매겨 띄우게 둔다.
        if (stem.length >= 3 && splitsOf(stem).first != null) return null
        val known = lm.lnConditional(prev, stem) ?: return null
        return known + usagePenalty(stem) - POLITE_YO_COST
    }

    private fun identityScore(cores: List<String>, contextBefore: String?, trace: StringBuilder? = null): Float {
        var score = 0f
        var prev = contextBefore
        for (core in cores) {
            val part = lnScore(prev ?: UNKNOWN_CONTEXT, core)
            trace?.append("원문 %s: %.2f (모름=%s 분석비용=%s)\n".format(core, part, lm.lnCount(core) == null, morphCost(core)))
            score += part
            prev = core
        }
        trace?.append("원문 합계 %.2f\n".format(score))
        return score
    }

    /**
     * 언어모델은 아는데 형태소 사전으로는 한 어절이 될 수 없는 말 — '할수있다', '안되요'.
     *
     * 말뭉치는 사람들이 실제로 쓴 글이라 흔한 **실수**도 그대로 배운다. '할수있다'는
     * 6천만 어절 중 157번 나온다. 빈도만 보면 멀쩡한 말이지만, 형태소로는 관형형 어미 뒤에
     * 의존명사가 붙어 있어 한 어절일 수 없다. 이런 어절은 "쓰긴 하지만 틀린 표기"로 보고
     * 점수를 깎는다. 그래야 '할 수 있다'로 나누는 쪽이 이긴다.
     */
    private fun usagePenalty(text: String): Float {
        if (text.length < 2) return 0f
        val cost = morphCost(text) ?: return 0f
        if (cost >= Spacer.UNANALYZABLE) return USAGE_PENALTY
        // 분석은 되지만 억지스러운 것(비용이 아주 높은 것)도 조금 깎는다. 외래어도 비용이
        // 높은 편이라 세게 깎지는 않는다.
        return -minOf(USAGE_PENALTY_GRADED_MAX, maxOf(0f, cost - USAGE_PENALTY_FREE_COST) / USAGE_PENALTY_PER_COST)
    }

    /**
     * 언어모델이 모르는 어절의 점수.
     *
     * 모른다는 것은 말뭉치 6천만 어절에 세 번도 안 나왔다는 뜻이므로 출발점부터 그 아래
     * ([UNKNOWN_BASE])다. 길수록 더 드물고(형태소가 더 들어 있다), 형태소 분석 비용이
     * 높을수록 더 드물다. 한 어절로 분석조차 안 되면 음절마다 어절 하나 값을 물린다 —
     * 거의 확실히 여러 어절이 붙은 것이고, 그래야 열세 음절짜리 덩어리가 다섯 어절로
     * 나뉜 가설(확률 다섯 개의 곱)에 진다.
     */
    private fun unknownScore(text: String): Float {
        val cost = morphCost(text) ?: return -(UNKNOWN_BASE + UNKNOWN_COST_MAX)
        // 형태소 사전이 "이건 여러 어절이다" 라고 나누는 것도 분석 안 되는 것과 같이 본다.
        // mecab-ko-dic 에는 '은'·'가' 같은 한 음절 명사가 많아서 웬만한 덩어리는 명사 나열로
        // 억지 분석이 되는데, 사전의 나누기 판단은 그 억지 경로와 제대로 된 경로를 비교한 결과다.
        if (cost >= Spacer.UNANALYZABLE || (text.length >= 3 && splitsOf(text).first != null)) {
            return -(UNKNOWN_BASE + UNANALYZABLE_PER_SYLLABLE * maxOf(0, text.length - 2))
        }
        // 무리 없이 한 어절로 분석되는 모르는 말('대문만이', '닮을')은 그냥 드문 낱말일 가능성이
        // 높다. 길이로 깎지 않는다 — 그래야 흔한 말로 한 글자 바꾸는 편집에 쉽게 지지 않는다.
        if (isWellFormed(text)) {
            return -(UNKNOWN_BASE + WELL_FORMED_PER_SYLLABLE * maxOf(0, text.length - 3) +
                minOf(WELL_FORMED_COST_MAX, maxOf(0, cost) / UNKNOWN_SCALE))
        }
        val length = UNKNOWN_PER_SYLLABLE * maxOf(0, text.length - 3)
        return -(UNKNOWN_BASE + length + minOf(UNKNOWN_COST_MAX, maxOf(0, cost) / UNKNOWN_SCALE))
    }

    /**
     * 형태소 사전이 나눈 결과를 다듬어서 돌려준다. 사전이 없거나 안 나누면 null.
     *
     * 사전은 원칙대로 최대한 띄운다. 그중 **띄어도 되고 붙여도 되는 자리**는 사람이 붙여
     * 쓴 대로 둔다 — 둘 다 맞는 표기를 한쪽으로 바꾸는 것은 교정이 아니다.
     * - 명사|명사 ('연습|문제', '치과|의사'): 합성어는 붙여 써도 된다. 언어모델이 두 조각을
     *   잇달아 본 적이 있을 때('오늘 날씨가')만 띄운다.
     * - '-아/-어' 뒤의 용언 ('좋아|했다', '들어|봤다', '남아|있다'): 합성동사거나 보조용언이라
     *   붙여 써도 된다.
     */
    private fun splitOf(text: String): String? = splitsOf(text).second

    /**
     * (다듬은 나누기, 거기서 이름 조각까지 도로 붙인 나누기). 앞의 것은 모르는 어절의 값을 매길 때([unknownScore]) 쓴다 —
     * 이름 조각을 붙였다고 그 어절을 '한 낱말' 로 매기면 오타 덩어리('데러와서' → 데려와서, '법무무장관')도 한 낱말이 되어 고친 말이
     * 문턱을 못 넘었다(2026-10-14). 뒤의 것은 디코더 조각([KIND_PIECE])과 멀쩡한 조각 자리에 쓴다.
     */
    private fun splitsOf(text: String): Pair<String?, String?> {
        val spacer = this.spacer ?: return null to null
        synchronized(splits) { splits[text]?.let { return it } }
        val raw = spacer.space(text)
        val rejoined = raw?.let { rejoinOptional(text, it) }
        val joined = rejoined?.split(' ')
            ?.let { pieces -> joinNameFragments(pieces, holdsUnknown(raw.split(' '), pieces)) }
            ?.takeIf { it.size >= 2 }?.joinToString(" ")
        val both = rejoined to joined
        synchronized(splits) { splits[text] = both }
        return both
    }

    /**
     * 말뭉치가 모르는 데다 한 낱말로도 안 읽히는 조각은 이름 한가운데를 자른 것이다 — 이웃 조각에 도로 붙인다
     * ('배스킨라|빈스는', '스빈후부|드는', '펜실이니|아주' — 깨끗한 글 띄어쓰기 오교정, 2026-10-14). 긴 덩어리 나누기
     * ([com.spellkeyboard.core.correct.CorrectionEngine])와 이 디코더의 사전 조각([splitOf])이 같이 쓴다.
     * 붙여 친 구어체 시험지의 정답 어절 658개 가운데 말뭉치가 모르는 28개는 다 활용형('예매해뒀어', '속상했겠네')이라
     * 여기 안 걸린다 — 분석값이 [NAME_FRAGMENT_COST] 아래고 명사로 끝나지 않는다.
     *
     * [holdsUnknown] 은 조각마다 사전이 처음 나눈 조각 가운데 말뭉치가 모르는 것을 품었는가다. 다 아는 조각을 도로 붙여 생긴
     * 덩어리('어제|산옷이|너무' 의 산옷이, '주신자료')는 붙여 친 글이라 보지 않는다. 붙인 덩어리를 다시 보지도 않는다 — 그러면
     * 붙여 친 문장이 통째로 도로 붙었다. 앞 조각이 아는 말이고 조사·어미·부사·관형사로 끝나면('오늘은|배스킨라빈스에') 그 자리는
     * 뗀 대로 둔다. 모르는 조각이 조사·어미로 끝나고 뒤가 아는 용언이면('체사피크뿐만|아니라') 그 자리도 뗀 대로 둔다.
     */
    fun joinNameFragments(pieces: List<String>, holdsUnknown: List<Boolean>): List<String> {
        if (pieces.size < 2 || spacer == null) return pieces
        val fragment = pieces.indices.map { holdsUnknown[it] && nameFragment(pieces[it]) }
        if (fragment.none { it }) return pieces
        val out = arrayListOf(pieces[0])
        for (k in 1 until pieces.size) {
            val before = pieces[k - 1]
            val after = pieces[k]
            val join = (fragment[k - 1] && !(endsLoose(before) && knowsWord(after) && startsWithVerb(after))) ||
                (fragment[k] && !(knowsWord(before) && endsLoose(before, alsoModifier = true)))
            if (join) out[out.size - 1] = out.last() + after else out += after
        }
        return out
    }

    /**
     * 말뭉치가 모르는 이름 조각인데 체언으로 끝나는가('팬택씨앤'). 붙여 친 글을 실시간으로 풀다 남은 덩어리('봤는데다시' — 부사로
     * 끝남, '확인하시고회신' — 아는 두 말로 갈림)는 아니다 — 그 자리를 막았더니 뒤 조각('봐도')을 못 떼었다(2026-10-15).
     */
    private fun nameLike(piece: String): Boolean =
        nameFragment(piece) && (tagsOf(piece)?.second?.let { it in NOUN_TAILS || it.startsWith("J") } ?: true) &&
            // 아는 두 말로 갈리는 덩어리('확인하시고|회신')는 붙여 친 글이 덜 풀린 것이다.
            (1 until piece.length).none { knowsWord(piece.substring(0, it)) && knowsWord(piece.substring(it)) }

    private fun nameFragment(piece: String): Boolean {
        if (knowsWord(piece)) return false
        // 한 글자만 고치면 흔한 말이 되는 토막은 이름 조각이 아니라 오타다('보능|동안' → 보는, '재밋었는데'). 도로 붙이면 디코더가
        // 그 덩어리 안을 못 고친다(2026-10-15).
        if (typoOfCommonWord(piece)) return false
        if (!isWellFormed(piece)) return true
        val (head, tail) = tagsOf(piece) ?: return true
        // 수로 시작하면 수 + 단위를 붙여 친 것이다('이십분정도').
        if (head == "NR" || head == "MM") return false
        // 줄어든 조사(받침 ㄹ = 를, ㄴ = 는)로 끝나는데 그 받침을 뗀 줄기도 말뭉치가 모르면 이름 한가운데다 — 분석 사전이 '판킬'
        // (판킬리아)을 판키+ㄹ, '팬택씨앤'(팬택씨앤아이)을 팬택씨애+ㄴ 으로 읽었다(2026-10-15). '날·난·걸' 같은 줄임은 아는 말이다.
        if (tail.startsWith("J") && contractedParticle(piece)) return true
        // 분석값이 높아도 조사로 끝나면 붙여 친 낱말 + 조사이기 쉽다('이십분정도', '산옷이').
        if ((morphCost(piece) ?: Int.MAX_VALUE) > NAME_FRAGMENT_COST && !tail.startsWith("J")) return true
        // 명사로 끝나도 사전에 통째로 오른 명사('격조')는 이름 조각이 아니다 — 말뭉치에 어절로 드물 뿐이다.
        return tail in NOUN_TAILS && spacer?.isLexiconNoun(piece) != true
    }

    /** [piece] 끝 음절의 받침이 ㄹ·ㄴ 이고, 그 받침을 뗀 꼴('판키', '팬택씨애')을 말뭉치가 모르는가. */
    private fun contractedParticle(piece: String): Boolean {
        val last = piece.last()
        // 그 자체로 조사 음절인 것('혼자하기에는', '…을')은 줄임이 아니다.
        if (piece.length < 2 || last in FULL_PARTICLE_SYLLABLES) return false
        val (cho, jung, jong) = Hangul.decompose(last) ?: return false
        if (Hangul.JONGSEONG[jong] != 'ㄹ' && Hangul.JONGSEONG[jong] != 'ㄴ') return false
        val stem = piece.dropLast(1) + Hangul.compose(cho, jung, 0)
        return !knowsWord(stem)
    }

    /** [text] 에서 이름 조각을 도로 붙여 생긴 덩어리([joinNameFragments]) 안쪽의 모든 자리(음절 위치). */
    private fun joinedBreaks(text: String): Set<Int> {
        if (text.length < 3) return emptySet()
        val (rejoined, joined) = splitsOf(text)
        if (rejoined == null || rejoined == joined) return emptySet()
        val removed = breaksOf(rejoined) - breaksOf(joined)
        val out = HashSet<Int>()
        var start = 0
        for (piece in (joined ?: text).split(' ')) {
            val end = start + piece.length
            if (removed.any { it in start + 1 until end }) for (p in start + 1 until end) out += p
            start = end
        }
        return out
    }

    private fun breaksOf(spaced: String?): Set<Int> {
        val out = HashSet<Int>()
        var at = 0
        spaced?.split(' ')?.dropLast(1)?.forEach { piece ->
            at += piece.length
            out += at
        }
        return out
    }

    /** [piece] 를 사람이 흔히 내는 오타 한 걸음(옆 키·받침·쉬프트)으로 고치면 말뭉치에 흔한 말이 되는가. */
    private fun typoOfCommonWord(piece: String): Boolean =
        piece.length in 2..MAX_FREE_SYLLABLES &&
            singleEdits(piece, codaSlip = true, nearKey = true).any { (lm.lnCount(it.first) ?: 0f) >= TYPO_NEIGHBOR_LN }

    /** 다듬은 조각([pieces])마다, 그것을 이루는 사전 첫 조각([raw]) 가운데 말뭉치가 모르는 것이 있는가([joinNameFragments]). */
    private fun holdsUnknown(raw: List<String>, pieces: List<String>): List<Boolean> {
        val out = ArrayList<Boolean>()
        var r = 0
        for (piece in pieces) {
            var covered = 0
            var unknown = false
            while (covered < piece.length && r < raw.size) {
                unknown = unknown || !knowsWord(raw[r])
                covered += raw[r].length
                r++
            }
            out += unknown
        }
        return out
    }

    /** 조각이 조사·어미로 끝나는가. [alsoModifier] 면 부사·관형사로 끝나는 것도 친다. */
    private fun endsLoose(piece: String, alsoModifier: Boolean = false): Boolean {
        val tail = tagsOf(piece)?.second ?: return false
        return tail.startsWith("J") || tail.startsWith("E") || (alsoModifier && (tail == "MAG" || tail == "MM"))
    }

    private fun startsWithVerb(piece: String): Boolean = tagsOf(piece)?.first?.let { spacer?.isVerbTag(it) } == true

    private fun nounSpansOf(core: String): IntArray? {
        val spacer = this.spacer ?: return null
        synchronized(nounSpanCache) { if (nounSpanCache.containsKey(core)) return nounSpanCache[core] }
        val spans = spacer.nounSpans(core)
        synchronized(nounSpanCache) { nounSpanCache[core] = spans }
        return spans
    }

    private fun junctionsOf(core: String): Pair<Array<Pair<String, String>?>, Array<Pair<String, String>?>>? {
        val spacer = this.spacer ?: return null
        synchronized(junctions) { if (junctions.containsKey(core)) return junctions[core] }
        val tags = spacer.syllableTags(core)
        synchronized(junctions) { junctions[core] = tags }
        return tags
    }

    /** 형태소 사전이 [text] 를 다듬지 않고 띄운 자리(음절 위치). 붙여 친 글('그때보자' → 그때 보자)의 경계는 거의 다 여기 든다. */
    private fun rawBreaks(text: String): Set<Int> {
        val spacer = this.spacer ?: return emptySet()
        synchronized(rawSplits) { rawSplits[text]?.let { return it } }
        val breaks = HashSet<Int>()
        var at = 0
        spacer.space(text)?.split(' ')?.forEach { piece ->
            at += piece.length
            breaks += at
        }
        synchronized(rawSplits) { rawSplits[text] = breaks }
        return breaks
    }

    private fun rejoinOptional(whole: String, spaced: String): String? {
        val pieces = spaced.split(' ')
        if (pieces.size < 2) return null
        val out = ArrayList<String>()
        out += pieces[0]
        var junction = pieces[0].length
        for (k in 1 until pieces.size) {
            val before = out.last()
            val after = pieces[k]
            val tagAtJunction = spacer?.tagAt(whole, junction)
            val optional =
                // 명사|명사: 합성어·이름은 붙여 쓴 대로 둔다. 앞이 때를 나타내는 말·대명사일 때만 띄운다('오늘|날씨가', '나|오늘').
                // (예전에는 언어모델이 두 조각을 잇달아 충분히 봤으면 띄웠다 — '평양|정상회담' 이 그렇게 갈렸다, 2026-10-13.)
                (nounJunction(before, after) && !adverbialNoun(before) && !apartFromNoun(before, after)) ||
                    // 관형사와 뒷말 머리가 합쳐 사전에 오른 낱말('그|날은' → 그날은, '그|중에' → 그중에).
                    (endsWithDeterminer(before) && spacer?.joinsDeterminer(before, after) == true) ||
                    // '-아/-어' 뒤의 용언: 합성동사거나 보조용언.
                    (endsWithAEo(before) && tagAtJunction != null && spacer?.isVerbTag(tagAtJunction) == true) ||
                    // 조사·어미로도 읽히는 한 음절('어', '오')을 조각으로 떼어 내지 않는다.
                    boundOnlyPiece(before) || boundOnlyPiece(after) ||
                    // 체언 뒤의 '받다·당하다·시키다·드리다' 는 한 낱말('발급받았다').
                    (ATTACHABLE_VERBS.any { after.startsWith(it) } && endsNominal(before)) ||
                    // 통째 분석에서 두 음절짜리 조사로 시작하면 앞말에 붙는다('외국인|치고'). 사전이 여기를 가르면 모르는 말 값을
                    // 붙여 쓴 덩어리로 매겨 '외국인이고' 같은 편집이 이겼다(2026-10-13).
                    (LONG_PARTICLES.any { after.startsWith(it) } && spacer?.boundaryTags(whole, junction)?.second?.startsWith("J") == true) ||
                    // 조사·관형사로 끝나는 조각 뒤가 말뭉치에 없는 말이면 이름 한가운데다('미래에|셋대우다' — 띄어쓰기 단계의
                    // [CorrectionEngine] 막이와 같다). 뒤가 용언으로 시작하면('집에|가고싶다') 뗀다.
                    looseBeforeUnknown(before, after) ||
                    // 명사 + 한 음절 명사 + 조사가 한 낱말인 것('주관사는' = 주관사 + 는). 사전은 '주관|사는'(사다) 으로 가른다.
                    suffixNounJunction(whole, junction, before, after)
            if (optional) out[out.size - 1] = before + after else out += after
            junction += after.length
        }
        return if (out.size < 2) null else out.joinToString(" ")
    }

    private fun looseBeforeUnknown(before: String, after: String): Boolean {
        val spacer = this.spacer ?: return false
        val left = tagsOf(before)?.second ?: return false
        if (!left.startsWith("J") && left != "MM") return false
        // 말뭉치에 어절로는 없어도 아는 말에 조사가 붙었거나([knowsWord]) 한 어절로 자연스럽게 읽히면('품절입니다' = 품절/이/ㅂ니다)
        // 이름이 아니다. '셋대우다'(셋/대/우다)는 억지 분석이라 걸린다.
        if (knowsWord(after) || (isWellFormed(after) && (morphCost(after) ?: Int.MAX_VALUE) < LOCK_MAX_COST)) return false
        return tagsOf(after)?.first?.let { spacer.isVerbTag(it) } != true
    }

    /** 통째 분석에서 명사|명사인 자리를 붙여 둘 것인가. 앞이 때를 나타내는 말·대명사거나 뒤가 자리·때를 가리키는 명사면 뗀다. */
    private fun compoundJunction(before: String, after: String, tags: Pair<String, String>): Boolean =
        tags.first in COMPOUND_TAGS && tags.second in COMPOUND_TAGS && !adverbialNoun(before) && !apartFromNoun(before, after)

    private fun suffixNounJunction(whole: String, junction: Int, before: String, after: String): Boolean {
        val spacer = this.spacer ?: return false
        if (after.length < 2) return false
        val (left, right) = spacer.boundaryTags(whole, junction) ?: return false
        if (left != "NNG" || right != "NNG") return false
        if (lm.lnCount(before + after[0]) == null) return false
        return spacer.boundaryTags(whole, junction + 1)?.second?.startsWith("J") == true
    }

    /**
     * 한 음절짜리 조각을 "조사·어미일 뿐" 으로 보아 도로 붙일 것인가.
     *
     * 형태소 사전은 '잘'·'더'·'다'·'안'을 조사나 어미로도 읽는다. 그런데 이것들은 부사로
     * **홀로 서는 일이 훨씬 잦다.** 사전 말만 듣고 도로 붙이면 '잘해결됐어'·'딱좋은'·
     * '더자고'·'밥을다'가 영영 안 풀린다 — 도로 붙이는 순간 "사전이 나눴다" 는 사실이
     * 사라져서 디코더는 그 덩어리를 드문 한 낱말로 보게 된다.
     *
     * 그래서 말뭉치에 **어절로** 얼마나 나왔는지를 같이 본다([SINGLE_WORD_MIN_LN]).
     */
    private fun boundOnlyPiece(piece: String): Boolean =
        piece.length == 1 && couldBeBound(piece) && (lm.lnCount(piece) ?: 0f) < SINGLE_WORD_MIN_LN

    /**
     * 명사|명사 자리를 사전이 나눈 대로 둘 것인가.
     *
     * 합성어('고속도로')를 지키려고 기본은 "도로 붙인다" 다. 근거가 둘 중 하나는 있어야
     * 나눈 채로 둔다.
     *
     * - **두 조각이 잇달아 쓰인 것을 봤다**([NOUN_SPLIT_MIN_LN]). 가장 센 근거다.
     * - **두 조각이 저마다 흔한 낱말이다**([NOUN_SPLIT_MIN_PIECE_LN]). 말뭉치가 6천만
     *   어절뿐이라 멀쩡한 짝도 대부분 안 나온다 — '오늘 날씨가' 조차 없다. 그래서
     *   연쇄만 요구하면 '오늘날씨가'·'지금통화'·'감기조심해'가 영영 안 풀린다.
     */
    private fun nounSplitWorthIt(before: String, after: String): Boolean {
        if ((lm.lnBigramCount(before, after) ?: 0f) >= NOUN_SPLIT_MIN_LN) return true
        val left = lm.lnCount(before) ?: return false
        val right = lm.lnCount(after) ?: return false
        return left >= NOUN_SPLIT_MIN_PIECE_LN && right >= NOUN_SPLIT_MIN_PIECE_LN
    }

    /**
     * **보조적 연결어미 '-아/-어'** 로 끝나는가 (좋아, 들어, 해, 봐, 줘, 남아…).
     *
     * 이게 참이면 뒤에 오는 용언은 보조용언이거나 합성동사의 뒷말이라, 띄어도 붙여도
     * 맞는 자리다 — 그래서 사전이 나눈 것을 도로 붙인다.
     *
     * 예전에는 **마지막 음절의 모양만** 봤다(받침 없이 ㅏ·ㅓ 계열). 그게 너무 헐거웠다.
     * '비가'·'내가'(체언+조사), '받아서'·'먹었어'(연결·종결어미)가 죄다 걸려서,
     * 사전이 제대로 나눈 '비가 왔어'·'받아서 미안'·'먹었어 고마워'를 도로 붙이고
     * 있었다. 셋을 더 본다.
     *
     * - **어미로 끝나야 한다.** '비가'는 조사(JKS)로 끝난다.
     * - **'-아서/-어서'는 아니다.** 이 어미 뒤에는 보조용언이 오지 않는다.
     * - **'-았어/-었어'도 아니다.** 앞 음절 받침이 ㅆ 이면 지난 일을 말하는 종결어미다.
     */
    private fun endsWithAEo(text: String): Boolean {
        val (_, jung, jong) = Hangul.decompose(text.last()) ?: return false
        if (jong != 0 || Hangul.JUNGSEONG[jung] !in A_EO_VOWELS) return false
        if (text.last() == '서') return false
        if (text.last() == '어' && text.length >= 2 && Hangul.jongseongOf(text[text.length - 2]) == 'ㅆ') return false
        return tagsOf(text)?.second?.startsWith("E") == true
    }

    /** [Spacer.cost] 를 기억해 두고 돌려준다. 사전이 없으면 null. */
    private fun morphCost(text: String): Int? {
        val spacer = this.spacer ?: return null
        synchronized(morphCosts) { morphCosts[text] }?.let { return it }
        val cost = spacer.cost(text)
        synchronized(morphCosts) { morphCosts[text] = cost }
        return cost
    }

    // ---------------------------------------------------------------- 편집 후보

    /** 헷갈리는 자모 하나만 바꾼 후보와 그 비용. */
    /**
     * @param codaSlip 받침을 통째로 빠뜨린 것까지 볼 것인가. **원문이 말뭉치에 없을 때만** 켠다.
     *   받침이 빠지면 거의 언제나 없는 말이 되므로, 있는 말에까지 이 편집을 허용하면
     *   '서명해 → 설명해', '이식될 → 인식될' 처럼 **뜻이 바뀐다**.
     */
    private fun singleEdits(
        word: String,
        codaSlip: Boolean = false,
        nearKey: Boolean = false,
        cheonjiinKey: Boolean = false,
        realLength: (Int) -> Int = { 0 }
    ): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for (index in word.indices) {
            val (cho, jung, jong) = Hangul.decompose(word[index]) ?: continue
            // 이 음절이 든 원래 어절이 멀쩡한 말이면([isRealWord]) **사람이 실제로 내는 오타 방향**만 고친다.
            // 받침 통째로 넣기·옆 키·천지인은 없는 말을 살리는 편집이라 멀쩡한 말에는 안 한다('후련하고 → 훈련하고', '날 → 말').
            val realWordLength = realLength(index)
            val real = realWordLength != 0
            // 어절 끝 조사 '은↔을'은 ㄴ·ㄹ 이 한 키라 한 번에 바뀌는데, 문맥으로 가를 수 없어
            // '선택사항들은'이 '들을'이 됐다. 조사 자리는 천지인 편집을 하지 않는다.
            if (cheonjiinKey && !(index == word.lastIndex && index > 0 && word[index] in CJI_PARTICLES)) {
                CJI_NEIGHBORS[Hangul.CHOSEONG[cho]]?.let { alternative ->
                    val id = Hangul.choseongIndex(alternative)
                    if (id >= 0) out += swap(word, index, Hangul.compose(id, jung, jong)) to CJI_COST
                }
                CJI_VOWELS[Hangul.JUNGSEONG[jung]]?.let { alternative ->
                    val id = Hangul.jungseongIndex(alternative)
                    if (id >= 0) out += swap(word, index, Hangul.compose(cho, id, jong)) to CJI_COST
                }
                if (jong != 0) CJI_NEIGHBORS[Hangul.JONGSEONG[jong]]?.let { alternative ->
                    val id = Hangul.jongseongIndex(alternative)
                    if (id > 0) out += swap(word, index, Hangul.compose(cho, jung, id)) to CJI_COST
                }
            }
            // '거'(것) 에는 받침을 넣지 않는다 — '거/건/걸' 은 사용자가 고른 말이지 오타가 아니다('운전하는거 → 운전하는건').
            // 낱말 첫머리의 '거'('거정하지 → 걱정하지', '거토 → 검토')는 의존명사가 아니라 넣는다(2026-10-15).
            val dependentGeo = word[index] == '거' && (index > 0 || word.length == 1)
            // 멀쩡한 조각 안('운전하는|거')도 후보는 낸다 — 고친 말이 뻔할 때만 남긴다([candidatesFor], '더|분에 → 덕분에').
            if (codaSlip && jong == 0 && !dependentGeo) CODA_SLIPS.forEach { (alternative, cost) ->
                val id = Hangul.jongseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(cho, jung, id)) to cost
            }
            if (nearKey) {
                NEAR_KEYS[Hangul.CHOSEONG[cho]]?.forEach { alternative ->
                    val id = Hangul.choseongIndex(alternative)
                    if (id >= 0) out += swap(word, index, Hangul.compose(id, jung, jong)) to NEAR_KEY_COST
                }
                NEAR_KEYS[Hangul.JUNGSEONG[jung]]?.forEach { alternative ->
                    val id = Hangul.jungseongIndex(alternative)
                    if (id >= 0) out += swap(word, index, Hangul.compose(cho, id, jong)) to NEAR_KEY_COST
                }
                if (jong != 0) NEAR_KEYS[Hangul.JONGSEONG[jong]]?.forEach { alternative ->
                    val id = Hangul.jongseongIndex(alternative)
                    if (id >= 0) out += swap(word, index, Hangul.compose(cho, jung, id)) to NEAR_KEY_COST
                }
            }
            CHOSEONG_EDITS[Hangul.CHOSEONG[cho]]?.forEach { (alternative, cost) ->
                val id = Hangul.choseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(id, jung, jong)) to cost
            }
            JUNGSEONG_EDITS[Hangul.JUNGSEONG[jung]]?.forEach { (alternative, cost) ->
                // 소리가 같은 모음은 멀쩡한 말에도 흔한 오타라(같은대 → 같은데, 하길레 → 하길래) 고친다. 다만 한 음절짜리 말은
                // 거의 언제나 딴 말이고(떼 → 때, 얘 → 예), ㅒ·ㅖ·ㅕ 끼리는 한쪽이 바른 말인 일이 드물어 멀쩡한 말에는 안 한다.
                if (real && (kotlin.math.abs(realWordLength) < 2 || Hangul.JUNGSEONG[jung] !in SAME_SOUND_VOWELS)) return@forEach
                val id = Hangul.jungseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(cho, id, jong)) to cost
            }
            // 어미 바로 앞의 ㅅ → ㅆ('출발햇어', '모엿어', '공지하겟습니다'). 폰에서 쉬프트를
            // 놓쳐 나는 오타라 제일 흔한데, 보통 받침 편집 값을 매기면 원문도 그럴듯하게 분석돼서
            // ('햇'을 XSV+EP 로 읽는다) 문턱을 못 넘었다(오타 시험지 ㅆ→ㅅ 되살림 78%).
            // 모음이 과거형을 이루는 것(ㅏㅓㅕㅐㅘㅝㅙㅞ)이고 뒤에 어미가 올 때만 싸게 준다.
            // '낫다'(더 낫네, 병이 낫는)는 '났다'와 부딪혀서 뺀다. 고친 말이 언어모델이 아는
            // 말일 때만 후보가 되는 것은 다른 편집과 같다.
            if (jong == JONG_S && index + 1 < word.length && jung in PAST_VOWELS &&
                !(cho == CHO_N && jung == JUNG_A) &&
                ENDING_STARTS.any { word.startsWith(it, index + 1) }
            ) {
                out += swap(word, index, Hangul.compose(cho, jung, JONG_SS)) to PAST_SLIP_COST
            }
            JONGSEONG_EDITS[Hangul.JONGSEONG[jong]]?.forEach { (alternative, cost) ->
                // 멀쩡한 말은 받침을 **채우는** 쪽만(ㅅ→ㅆ, ㄴ→ㄶ, ㅂ→ㅄ, 없음→ㅎ). 줄이거나 소리 같은 받침끼리 바꾸면 딴 말이다(앓아 → 알아, 잊 → 있).
                if (real && (Hangul.JONGSEONG[jong] to alternative) !in CODA_COMPLETIONS) return@forEach
                val id = if (alternative == NO_JONG) 0 else Hangul.jongseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(cho, jung, id)) to cost
            }
        }
        return out
    }

    private fun swap(word: String, index: Int, syllable: Char): String =
        buildString(word.length) {
            append(word, 0, index)
            append(syllable)
            append(word, index + 1, word.length)
        }

    companion object {

        /**
         * [knowsWord] 가 떼어 보는 조사. **긴 것부터** 놓아야 '에서' 가 '에' 로 잘리지 않는다.
         *
         * 여기 있는 것을 떼어 본다고 해서 없는 낱말이 생기지는 않는다 — 뗀 결과를 다시
         * 언어모델에 물어보고, 아는 것일 때만 참이다.
         */
        private val TRAILING_PARTICLES = listOf(
            "에서는", "에게는", "으로는", "에서", "에게", "으로", "까지", "부터", "라고",
            "이라", "이나", "이란", "이는", "이가",
            "은", "는", "이", "가", "을", "를", "에", "의", "도", "만", "과", "와", "로"
        )
        /**
         * 부사처럼 쓰이는 명사 — 때를 나타내는 말. 이 뒤의 명사는 합성어가 아니라 딴 어절이라 명사|명사여도 뗀다([adverbialNoun]).
         * 긴 덩어리 나누기([com.spellkeyboard.core.correct.CorrectionEngine])도 같은 목록을 쓴다.
         */
        val ADVERBIAL_NOUNS: Set<String> = setOf(
            "오늘", "내일", "어제", "모레", "그제", "지금", "방금", "아까", "이따", "요즘", "요새", "매일", "작년", "올해", "내년",
            "이번", "다음", "저번", "지난", "주말", "평일", "아침", "점심", "저녁", "밤", "새벽", "오전", "오후", "낮", "하루",
            "이틀", "어젯밤", "오늘밤", "내일모레", "진짜", "정말", "완전", "엄청", "제일", "가장", "항상", "매번", "가끔"
        )

        /**
         * 사전의 드문 명사('어재·계소·와전·중애·하내')로도 읽히는 오타를 고칠 때, 고친 말이 이 안에 있어야 한다 — 대화에서 가장 흔한
         * 부사·기능어라 한 글자 떨어진 드문 명사보다 거의 언제나 이쪽이다. 이름 옆의 말('다은 → 다음')이 끼지 않게 '다음' 은 뺐다.
         */
        private val EVERYDAY_WORDS = setOf(
            "어제", "오늘", "내일", "계속", "많이", "많아", "많은", "그냥", "중에", "전에", "후에", "시에", "때에", "완전", "다섯", "여섯",
            "조금", "하네", "한번", "정말", "진짜", "아직", "벌써", "이제", "지금", "그래서", "그런데", "근데", "때문에", "같은데",
            "없는데", "있는데", "너무", "없는", "있는", "그래", "그런", "이런", "저런", "그거", "이거", "저거", "어디", "언제", "다시",
            "같이", "혼자", "먼저", "나중에", "바로", "빨리", "잠깐", "요즘", "항상", "가끔", "자주", "매일", "확인", "고생", "감사"
        )

        /** [everydayNounEdit] 의 일상 명사. */
        private val EVERYDAY_NOUNS = setOf(
            "연락", "전화", "장소", "접수", "작성", "검토", "정리", "일정", "시간", "사진", "선약", "계산", "주문", "발송", "덕분", "축하",
            "걱정", "응원", "예약", "산책", "아침", "점심", "저녁", "우산", "감기", "내용", "공지", "참고", "약속", "생각", "친구", "동생",
            // 여섯째 판(2026-10-15) — 대화에서 '-하다' 로 흔히 쓰는 말.
            "피곤", "도착", "출발", "설명", "확인", "부탁", "미안", "감사", "준비", "시작", "결정", "고민", "기대", "필요", "다행", "운동",
            "공부", "청소", "결제", "입금", "취소", "변경", "신청", "제출", "참석", "배송", "문의", "답장", "회신", "퇴근", "출근", "야근",
            "회의", "휴가", "여행", "선물", "생일", "주말", "오전", "오후", "근처", "정말", "진짜", "완전", "속상", "창밖", "한잔", "전달"
        )
        /** [bareStem] — 줄기 + '다' 가 말뭉치에 이만큼(ln) 나와야 한다. */
        private const val BARE_STEM_LN = 4.0f

        /** 관형형 뒤에 오는 의존명사의 머리([candidatesFor] — '갔을 때', '갔을 거야'). */
        private val AFTER_ADNOMINAL = listOf("때", "거", "것", "수", "텐", "줄", "리", "뿐", "까", "지", "듯", "만큼", "적")

        /** 단위 앞에 오는 수관형사([candidatesFor] — '네 군데의'). */
        private val COUNT_WORDS = setOf("한", "두", "세", "네", "몇", "여러", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "스무", "수십", "수백", "몇몇")

        /** [endingFill] — 받침을 채워 생기는 어미·조사 음절. */
        private const val ENDING_FILL_SYLLABLES = "던은는을를란난런된한할될온올간갈든큰작"

        /** [everydayEdit] — 일상 명사 뒤에 붙는 꼬리(조사·'-하다' 활용) 길이 한도. */
        private const val EVERYDAY_TAIL_MAX = 5
        private val EVERYDAY_NOUN_TAILS = setOf("은", "는", "이", "가", "을", "를", "에", "에서", "의", "도", "만", "으로", "로", "하고", "해", "해서", "했어")

        /** 사람 이름 뒤에 오는 직함·호칭. 앞 어절이 이름이라는 단서다 — '허재 감독' 을 '헌재 감독' 으로 바꾸지 않는다. */
        private val TITLES = listOf(
            "감독", "선수", "씨", "님", "대표", "의원", "교수", "선생", "작가", "배우", "회장", "사장", "기자", "위원장", "장관",
            "대통령", "총리", "원장", "이사", "부장", "과장", "팀장", "코치", "가수", "변호사", "박사", "군", "양",
            "대법원장", "판사", "검사", "총장", "주교", "신부", "목사", "장군", "국왕", "여왕", "왕자", "공주", "대사", "시장", "지사", "단장"
        )

        /** 낱말 머리로도 흔한 관형사 — '한메일·이사진·새해·전국'. 이것 뒤에서만 아는 한 낱말을 안 가른다. */
        private val WORD_HEAD_DETERMINERS = setOf("한", "이", "새", "전", "총", "각", "본", "동", "현", "구", "신", "순", "대", "두", "세", "네")

        /**
         * 통째 분석에서 이 품사끼리 맞붙은 자리는 합성어·이름 속이다([compoundJunction]). 접미사('상임위|별로' 의 별)도 넣는다.
         * 수사(NR)는 뺀다 — 수 + 단위는 띄어 쓴다('삼십|대', '열|시').
         */
        private val COMPOUND_TAGS = setOf("NNG", "NNP", "XSN")

        /** 자리·때를 가리키지만 줄임말 꼬리이기도 한 명사의 첫 음절 — '-전'(戰·展), '-위'(委). [nounPair] */
        private const val ABBREVIATION_TAILS = "전위"

        /** [nounPair] 의 양쪽 끝 품사 — 이름·합성어 조각. */
        private val NOUN_PAIR_TAGS = setOf("NNG", "NNP", "XSN", "NR", "SL", "SN", "SH")

        /** 두 음절짜리 조사 — 앞말과 늘 붙는다. 한 음절 조사는 용언과 헷갈려 뺐다. */
        /** 앞쪽만 떼면 딴 낱말처럼 보이는 겹조사('에|서는' 의 서다, '으로|써' 의 쓰다) — [insideTrailingParticle]. */
        private val SPLITTABLE_PARTICLES = listOf("에서", "으로서", "로서", "으로써", "로써", "에게서", "한테서", "으로부터", "로부터")

        /** 겹조사 뒤에 붙는 것 — 조사와 서술격('판단에서다', '…에서였다'). */
        private val PARTICLE_TAILS = setOf(
            "", "는", "은", "의", "도", "만", "부터", "까지", "야", "라도", "처럼", "이", "가", "를", "을", "와", "과", "나", "보다",
            "다", "이다", "였다", "이었다", "인", "인지", "라는", "라고", "만은", "도록"
        )

        private val LONG_PARTICLES = listOf(
            "치고", "치곤", "처럼", "까지", "부터", "마저", "조차", "밖에", "만큼", "보다", "에게", "한테", "께서", "에서", "으로",
            "라도", "이나", "마다", "대로", "이랑", "이며", "커녕", "더러"
        )

        /** 뒤의 단위와 띄어 쓰는 수관형사. 디코더가 '한 해' 를 '한해' 로 붙이지 않게 한다. */
        /** 수관형사 뒤에 오는 단위의 첫머리 — [mergeCost] 가 '취임 한 달' 의 '한' 을 앞말에 안 붙인다. */
        private val COUNTER_HEADS = listOf(
            "달", "해", "명", "개", "번", "시간", "살", "잔", "병", "권", "마리", "주일", "년", "분", "초", "시", "가지", "곳", "채", "대", "장", "켤레", "벌", "그릇"
        )

        private val NUMERAL_DETERMINERS = setOf(
            "한", "두", "세", "네", "몇", "여러", "열", "스무", "다섯", "여섯", "일곱", "여덟", "아홉", "열한", "열두", "열세", "열네"
        )

        /**
         * 멀쩡한 낱말([isFirmWord])의 글자를 바꾸는 편집에 더 매기는 값(ln). 문맥이 아주 분명할 때만 넘는다('재미잇는 영화').
         * 2.5 → 3.5(2026-10-13): '결의를 다졌습니다 → 따졌습니다'(1.92 차)가 넘었다. 흔한 말로 고치는 오타는 [EVIDENT_EDIT_LN] 으로 따로 연다.
         */
        const val FIRM_EDIT_COST = 3.5f

        /** 고친 말이 이만큼 흔하면(ln 빈도, 약 400번) 멀쩡한 말 막이를 걷을 수 있다 — 원문이 말뭉치에 없거나 [EVIDENT_SOURCE_LN] 보다 드물 때. */
        const val EVIDENT_EDIT_LN = 6.0f

        /**
         * 말뭉치에 있는 원문은 이보다 드물어야(ln 빈도, 약 9번) 흔한 말로 고칠 때 막이를 걷는다 — '어재·와전·하내'(ln 1.8~2.0)는 열고
         * '허재'(2.5)·'업어'(2.8)·'하지마'(흔함)는 그대로 막는다.
         */
        const val EVIDENT_SOURCE_LN = 2.2f

        /** 멀쩡한 말([FIRM_EDIT_COST])을 소리 같은 모음으로·빠진 받침을 채워 고칠 때 물리는 값. [candidatesFor] */
        const val SAME_SOUND_FIRM_COST = 1.0f
        const val CODA_FIRM_COST = 2.5f

        /** 첫소리를 된소리로 바꿀 때 고친 말이 넘어야 하는 말뭉치 빈도(ln). '뿐인것' 2.75·'빠삭하게' 1.6 은 못 넘고 '쏟아냈다' 3.4·'쓰촨성' 3.8 은 넘는다. */
        const val TENSE_ONSET_MIN_LN = 3.0f
        private val TENSE_PAIRS = setOf('ㄱ' to 'ㄲ', 'ㄷ' to 'ㄸ', 'ㅂ' to 'ㅃ', 'ㅅ' to 'ㅆ', 'ㅈ' to 'ㅉ')

        /** '-하다·-되다' 활용의 첫 음절. */
        private val VERB_SUFFIX_HEADS = setOf('하', '합', '한', '할', '해', '했', '함', '되', '돼', '된', '됐', '될', '됨')

        /** 명사 + '-하다·-되다' 가 진짜 움직씨인지 말뭉치에서 확인할 꼴들([commonNounStemLength]). */
        private val VERB_PROBES = listOf("하는", "한", "했다", "하고", "하기", "하여", "해", "된", "되는", "됐다", "되어", "되고", "할", "될")

        /** 어절 끝에 붙으면 그 어절의 어미를 굳히는 문장 부호. */
        private val SENTENCE_MARKS = setOf(',', '.', '!', '?', ';', ':', '…')

        /** [commonNounStemLength] 가 흔한 명사로 보는 줄기 빈도(ln). '이관' 6.4. */
        const val COMMON_STEM_LN = 4.5f

        /** 명사 줄기가 이보다 드물면(ln 빈도, 약 20번) 그 안의 편집에 막이를 걷는다([rareNounStemLength]). '서명'(6.4)은 흔하다. */
        const val RARE_STEM_LN = 3.0f

        /** 끝 음절 잠금([decode])이 "자연스럽게 한 어절로 읽힌다" 고 보는 형태소 분석 비용 상한. '있너'(3,609)는 그 위다. */
        const val LOCK_MAX_COST = 2500

        /** 멀쩡한 낱말의 끝 음절을 바꿔도 되는 짝 — 받침에 맞춘 조사 고치기뿐이다. 어미('-며 → -면', '-자 → -다')는 안 바꾼다. */
        /** 한 음절 조사. [candidatesFor] 의 '명사 끝 → 조사' 막이. */
        private val PARTICLE_SYLLABLES = setOf('가', '이', '을', '를', '은', '는', '의', '에', '도', '와', '과', '로', '만')

        private val PARTICLE_SWAPS = setOf(
            '을' to '를', '를' to '을', '은' to '는', '는' to '은', '이' to '가', '가' to '이', '과' to '와', '와' to '과'
        )

        /** [isFirmWord] 가 떼어 보는 조사·서술격 조사. 긴 것부터. */
        private val FIRM_PARTICLES = listOf(
            "에서는", "에게는", "으로는", "입니다", "이에요", "이었다", "에서", "에게", "으로", "까지", "부터", "이랑", "이나", "이다", "이야",
            "예요", "였다", "은", "는", "이", "가", "을", "를", "에", "의", "도", "만", "과", "와", "로", "랑", "나", "야",
            // 두 음절 조사도 받는다 — '외국인치고' 가 사전 명사 + 조사로 안 읽혀 '외국인이고' 로 바뀌었다(2026-10-13).
            // '보다' 는 뺐다 — '생가보다'(생가 + 보다)를 지키면 '생각보다' 오타를 못 고친다.
            "치고", "치곤", "처럼", "마저", "조차", "밖에", "한테", "께서", "마다"
        )
        private val AFTER_CONSONANT = setOf("은", "이", "을", "과", "으로", "으로는", "이랑", "이나", "이다", "이야", "이에요", "이었다", "입니다")
        private val AFTER_VOWEL = setOf("는", "가", "를", "와", "로", "랑", "나", "야", "예요", "였다")

        /** 원문보다 이만큼(ln) 좋아야 고친다. 통로 비용이 이미 들어간 뒤의 여유분이다. */
        const val MARGIN = 1.5f

        /**
         * 원래 있던 띄어쓰기를 없애는 비용. 지운 자리에 매인 형태소(접미사·어미·조사)가
         * 시작할 때만 허용한다 — 원래 띄면 안 되던 자리다. 그 밖의 자리는 아예 붙이지 않는다.
         * 한국어 표준은 띄는 쪽이고, 사람들이 붙여 쓰는 습관('와봐', '할수')을 말뭉치가
         * 그대로 배웠기 때문이다.
         */
        const val BOUND_MERGE_COST = -3.0f

        /** 조사와 겉모양이 같은 한 음절 용언. 띄어 쳤으면 앞말에 붙이지 않는다. */
        private val STANDALONE_VERBS = setOf("가", "와")

        /** '-아/-어' 뒤에 오는 보조용언의 머리. 띄어 친 것을 붙이지 않는다([boundaryCost]). */
        private val AUXILIARY_HEADS = listOf(
            "줘", "줬", "주", "줄", "준", "봐", "봤", "보", "볼", "본", "놔", "놨", "놓", "둬", "뒀", "두", "둘", "둔",
            "버", "드려", "드렸", "드리", "드릴", "드린", "드립", "가", "갈", "간", "갔", "와", "올", "온", "왔", "오", "있", "내", "냈", "낼"
        )

        /** 붙여서 만든 어절이 최소한 이만큼은 흔해야 한다. ln(100). */
        const val MERGE_MIN_LN_COUNT = 4.6f

        /**
         * 모든 편집에 얹는 기본 비용. 사람이 자모를 잘못 치는 일은 어절 백 개에 한두 번이라,
         * 고친 쪽이 그만큼(ln 50 ≈ 4) 더 그럴듯해야 한다. 자모별 비용과 합쳐 그 언저리가 된다.
         */
        const val EDIT_BASE_COST = 1.5f

        /** 명사|명사 자리를 띄우려면 두 조각의 연쇄 빈도가 이만큼은 돼야 한다. ln(33). */
        const val NOUN_SPLIT_MIN_LN = 3.5f

        /** 연쇄를 못 봤을 때, 두 조각이 저마다 이만큼은 흔해야 나눈 채로 둔다. */
        const val NOUN_SPLIT_MIN_PIECE_LN = 6.0f

        /**
         * 한 음절짜리 조각이 이만큼 흔하면 조사·어미가 아니라 낱말로 본다.
         *
         * 말뭉치에서 **어절로** 몇 번 나왔나를 센 값이다. 가르는 선이 여기 있다.
         *
         *     부사·대명사   잘 11.3  더 12.1  다 11.4  안 11.1
         *     조사·어미     는 8.8  은 8.6  도 8.6  과 8.4  을 9.4  와 9.1
         *     그 사이       서 10.4  나 9.8  어 9.8  오 9.6  가 9.6
         *
         * 11 로 두면 위 넷만 낱말로 친다. 9 까지 내리면 구어체 복원이 0.5%p 더 오르지만
         * '서'·'어'까지 낱말이 되면서 격식체 오교정이 하나 는다.
         */
        const val SINGLE_WORD_MIN_LN = 11f

        /**
         * 없던 띄어쓰기를 넣는 비용. 나뉜 조각이 모두 아는 어절이어야 하므로 싸게 둔다.
         *
         * -0.5, 0.5 로도 대 봤다. 띄어쓰기 하나 복원이 73.7 → 74.2% 오르는 대신 멀쩡한
         * 글을 건드리는 비율이 1.20 → 1.40% 로 오른다. 지금 값이 낫다.
         */
        const val SPLIT_COST = -1.5f

        /** 원문 어절이 이 ln(빈도)를 넘으면 그만큼 편집 비용을 더 물린다. ln(20). */
        const val KNOWN_FREE_LN = 3.0f
        const val KNOWN_PENALTY_PER_LN = 1.0f

        /** 모르는 어절의 출발점. ln(3 / 6천만) 근처 — 표에 실리는 문턱 바로 아래다. */
        const val UNKNOWN_BASE = 17f

        /** 높임의 '-시-' 가 들어 있는 음절: 시·셔(시+어)·셨(시+었)·세(시+어요)·십(시+ㅂ니다)·실·신·심. [honorificAt] 이 본다. */
        private val HONORIFIC_SYLLABLES = setOf('시', '셔', '셨', '세', '십', '실', '신', '심')
        /**
         * 세 음절을 넘는 음절마다 더 내려간다. 세게 잡는다 — 열네 음절짜리 덩어리가 억지로
         * 한 어절로 분석된다 해도, 아는 어절 다섯으로 나뉜 가설(−30 안팎)보다는 나빠야 한다.
         */
        const val UNKNOWN_PER_SYLLABLE = 2.5f
        /** 형태소 분석 비용이 이만큼 오를 때마다 1 씩 내려간다. 최대 [UNKNOWN_COST_MAX]. */
        const val UNKNOWN_SCALE = 3000f
        const val UNKNOWN_COST_MAX = 3f
        /** 무리 없이 분석되는 모르는 말은 살짝만 깎는다. */
        const val WELL_FORMED_PER_SYLLABLE = 2.5f
        const val WELL_FORMED_COST_MAX = 2f
        /** 한 어절로 분석이 안 되는 덩어리: 두 음절을 넘는 음절마다 이만큼. 어절 하나 값이다. */
        const val UNANALYZABLE_PER_SYLLABLE = 4.5f
        /** 아는 어절이지만 한 어절로 분석되지 않을 때 깎는 값. */
        const val USAGE_PENALTY = -5f
        /** 분석은 되지만 비용이 이걸 넘으면 [USAGE_PENALTY_PER_COST] 마다 1 씩, 최대 [USAGE_PENALTY_GRADED_MAX]. */
        const val USAGE_PENALTY_FREE_COST = 4000f
        const val USAGE_PENALTY_PER_COST = 2000f
        const val USAGE_PENALTY_GRADED_MAX = 3f

        /**
         * 한 어절로 자연스럽게 분석되는 말을 자르지 않으려는 힘. 로그확률 단위다.
         *
         * 12 는 "웬만한 증거로는 못 넘는다" 는 뜻이다. 흔한 말 둘로 쪼개는 것만으로 얻는
         * 이득이 보통 3~6 점이라 그걸로는 안 되고, '시간이걸린다 → 시간이 걸린다'(19점)
         * 처럼 확실할 때만 넘어간다.
         */
        const val WELL_FORMED_MAX = 12f

        /**
         * 명사|명사 자리를 자르는 값.
         *
         * 합성어('치과의사')를 지키려는 힘이다. 예전에는 아예 금지였는데 그러면 띄어 써야
         * 하는 '컴퓨터의|충돌' 까지 막혔다. 값으로 바꾸고 증거가 이만큼 이기면 자른다.
         */
        /**
         * 명사|명사 자리를 자를 때 무는 값. 2.5 로 낮추면 띄어쓰기 복원이 0.5%p 오르지만
         * 오교정이 0.07%p 따라 오른다. 오늘 오교정을 이미 0.97 → 1.20% 로 썼으므로 안 쓴다.
         */
        const val NOUN_JUNCTION_COST = 4f


        /**
         * 조사·어미로 시작하는 조각은 어절이 될 수 없다.
         *
         * 형태소 분석기에 물어보는 [startsBound] 가 이미 있는데 이 표가 따로 필요한 이유는,
         * 분석기가 '부터'·'지도' 를 명사로도 읽어서 **흐릿할 때가 있기 때문**이다. 그래서
         * '데이터로|부터의', '연루되었을|지도' 같은 것이 빠져나갔다.
         *
         * 금지가 아니라 값이다. '지도'(map)처럼 진짜 낱말과 겹치는 것이 있어서, 증거가
         * 확실하면 넘어갈 수 있어야 한다.
         */
        private val BOUND_STARTS = listOf(
            // 조사
            "부터", "까지", "조차", "마저", "처럼", "치고", "커녕", "만큼", "밖에",
            "에서", "에게", "한테", "께서", "로서", "로써", "이라고", "라고", "이라도", "라도",
            "든지", "라든지", "이나마", "나마", "대로", "보다는", "이야말로", "야말로",
            // 어미
            "다는", "라는", "지만", "니까", "는데", "는지", "면서", "으며", "지도",
            "습니다", "입니다", "였다", "았다", "었다", "겠다", "더라", "더니"
        )

        /** 조사·어미로 시작하는 조각을 떼어 내는 값. */
        const val BOUND_START_COST = 10f

        /** 조사·관형사·관형형 어미 뒤라서 자르기 좋은 자리일 때 얹어 주는 값. */
        const val BOUNDARY_MARKER_BONUS = 3f

        /** 이 태그로 끝나면 어절이 끝난 것이다. 조사 전부와 관형사(MM), 관형형 어미(ETM). */
        private val BOUNDARY_TAILS =
            setOf(
                "JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC", "MM", "ETM",
                // 어미로 끝난 어절도 거기서 끝난다 — 연결어미('먹어서|미안'), 종결어미
                // ('먹었어|고마워'). 어미 뒤에 올 수 있는 것은 보조사뿐이다.
                "EC", "EF",
                // 어미로 끝난 어절도 거기서 끝난다 — 연결어미('먹어서|미안'), 종결어미
                // ('먹었어|고마워'). 어미 뒤에 올 수 있는 것은 보조사뿐이고, 딴 낱말이
                // 이어 붙을 수는 없다.
                //
                // '돌아|가다'처럼 '-아/-어' 뒤에 용언이 붙는 합성동사·보조용언은 아래
                // [endsWithAEo] 금지가 따로 막는다.
            )

        /** 이 아래로 자연스러우면 벌점을 다 문다. 한 낱말들의 중앙값이 -1331 쯤이다. */
        const val WELL_FORMED_FIRM_COST = 0

        /** 이 위로 억지스러우면 말리지 않는다. 붙여 쓴 구들의 최솟값이 1477 쯤이다. */
        const val WELL_FORMED_FREE_COST = 4000

        /**
         * 말뭉치에 없던 어절을 "한 낱말" 로 보아 지켜 주는 정도. 1 이면 아는 어절과 같다.
         *
         * 모르는 말은 한 낱말이라는 증거가 형태소 분석 하나뿐이라 약하다. 그래서 덜 지킨다.
         */
        const val UNSEEN_WELL_FORMED_FACTOR = 0.4f

        const val MAX_TOKEN_SYLLABLES = 14
        const val MAX_FREE_SYLLABLES = 8

        /** 처음 보는 두 조각으로 자른 의심 자리를 막고 다시 찾는 횟수 상한([unseenSplits]). */
        private const val MAX_REFUSAL_ROUNDS = 3

        /**
         * 어절의 형태소 분석값이 이 아래면 붙여 친 덩어리가 아니라 한 낱말로 읽힌다([unseenSplits] 의 값 1 자리). 긴 덩어리를 가를지 정하는
         * [com.spellkeyboard.core.correct.CorrectionEngine] 의 문지방과 같은 값이다.
         */
        private const val ONE_WORD_MAX_COST = 3500

        /**
         * 말뭉치가 모르는 조각의 분석값이 이보다 크면 이름 조각으로 본다([joinNameFragments]). 붙여 친 구어체 시험지의 정답
         * 어절 가운데 말뭉치가 모르는 것은 제일 큰 값이 '적어둘게'(3,170)였고, 깨끗한 글에서 잘못 가른 이름 조각은
         * '무어데'(3,454)·'인디펜던트지'(3,696)·'블레이드앤'(3,804)처럼 그 위였다.
         */
        private const val NAME_FRAGMENT_COST = 3300

        /** 이 품사로 끝나면 낱말 끝이 명사다 — 말뭉치가 모르면 이름 조각이다('표도로브', '우파디의'). */
        private val NOUN_TAILS = setOf("NNG", "NNP", "NNB", "NR", "NP")

        /** 말뭉치에 어절로 이만큼(ln) 못 나온 조각은 드물다 — 이름 조각('변협은', '사진|전' 의 보도사진)이기 쉽다([unseenSplits]). */
        private const val RARE_PIECE_LN = 3.0f

        /** 이 음절 수 넘는 사전 명사 한가운데는 세게 본다([Spacer.nounSpans]). 두 음절('가방')은 붙여 친 글에 흔히 걸린다. */
        private const val LONG_MORPHEME_SYLLABLES = 3
        /** 통째 분석값이 높아 못 믿을 때의 문턱 — 드문 이름('보츠와나')만 본다. */
        private const val LONG_NAME_SYLLABLES = 4

        /** [headJoint] — 한 음절 머리 뒤 조각이 말뭉치에 이만큼(ln) 나오면 흔한 말이라 뗀다('그|사람이'). */
        private const val LONE_HEAD_COMMON_LN = 6.5f

        /** 받침 ㄹ·ㄴ 으로 끝나는 온전한 조사 음절 — [contractedParticle] 이 줄임으로 보지 않는다. */
        private const val FULL_PARTICLE_SYLLABLES = "는은을를엔란론선"

        /** '-아/-어' 뒤에 늘 붙는 힘줌 접미사 '-트리다·-뜨리다' 의 꼴([glueWins]). */
        private val TRI_SUFFIXES = listOf("트리", "트려", "트렸", "트린", "트릴", "트립", "뜨리", "뜨려", "뜨렸", "뜨린", "뜨릴", "뜨립")
        const val MAX_DOUBLE_EDIT_SYLLABLES = 8
        const val MAX_SEGMENT_SYLLABLES = 48
        private const val BEAM = 12
        private const val UNKNOWN_CACHE_SIZE = 1024

        private const val KIND_IDENTITY = 0
        private const val KIND_MERGE = 1
        private const val KIND_FREE = 2
        private const val KIND_PIECE = 3

        private const val UNKNOWN_CONTEXT = " ?"

        private val HANGUL = '가'..'힣'
        private val A_EO_VOWELS = setOf('ㅏ', 'ㅓ', 'ㅐ', 'ㅔ', 'ㅕ', 'ㅝ', 'ㅘ', 'ㅙ', 'ㅞ')
        private val ATTACHABLE_VERBS = listOf("받", "당하", "시키", "드리")

        /** [boundVerbJoint] — 이 품사로 시작하는 앞말은 '-하다' 줄기일 수 있다(사전이 '협치' 를 어근 XR 로 읽는다). */
        private val HADA_ROOT_HEADS = setOf("NNG", "NNP", "XR")

        /** [typoOfCommonWord] — 한 걸음 고친 말이 말뭉치에 이만큼(ln) 나와야 흔한 말이다. */
        private const val TYPO_NEIGHBOR_LN = 5.0f

        /** [crossesKnownName] — 이만큼 길고 말뭉치에 이만큼(ln) 나온 낱말만 본다. */
        private const val KNOWN_NAME_MIN_SYLLABLES = 3
        private const val KNOWN_NAME_LN = 2.0f

        /** [wholeWins] — 통째 꼴이 두 조각 짝보다 이만큼(ln) 더 흔해야 하고, 이만큼 길어야 한다. */
        private const val WHOLE_WINS_MARGIN = 1.0f
        private const val WHOLE_WINS_MIN_SYLLABLES = 5

        /** [hadaRoot] 가 보는 '-하다' 활용꼴. */
        private val HADA_FORMS = listOf("했다", "하는", "한다", "하고", "했고", "하기")

        /** [hadaRoot] — 말뭉치에 이만큼(ln) 나온 '-하다' 활용꼴이 있으면 '-하다' 줄기다. */
        private const val HADA_ROOT_LN = 2.0f
        private val TOKEN = Regex("""\S+|\s+""")
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…')
        private val PUNCTUATION = setOf('.', ',', '!', '?', '…', '~', ')', '"', '\'', '”', '’', ';', ':')

        /**
         * 말뭉치보다 새로 생긴 줄임말. 말뭉치가 몰라서 디코더가 잘게 쪼개거나('핵노잼이더라' →
         * '핵 노 잼이더라') 딴 말로 바꿨다('알잘딱깔센' → '알 잘 딱 갈 센'). 이걸로 시작하는
         * 어절은 디코더가 건드리지 않는다. **짧은 말은 넣지 마라** — 접두사로 비교해서
         * 다른 낱말까지 막는다.
         */
        private val SLANG_STEMS = listOf(
            "핵노잼", "핵꿀잼", "알잘딱", "갑분싸", "스불재", "자만추", "중꺾마", "얼죽아", "오운완",
            "존맛탱", "대존맛", "쌉가능", "퇴근각", "킹받", "갓생", "뇌절", "킹정", "꾸안꾸", "머선",
            "많관부", "억텐", "찐텐", "갑통알", "혼코노", "별다줄", "사바사", "케바케",
            "어쩔티비", "저쩔티비", "점메추", "만반잘부", "넘사벽", "답정너", "솔까말", "엄근진", "핵인싸", "띵작", "내돈내산"
        )

        /** 이 음절 뒤의 '-는대'는 없는 꼴이다(과거형·있다·없다). */
        private val NEUNDAE_WRONG_BEFORE = "았었였했갔왔봤졌됐켰셨겠있없".toSet()

        /** 어절 앞에 붙는 여는 괄호·따옴표. */
        private val OPENERS = setOf('(', '"', '\'', '“', '‘', '[', '「', '『')

        /** 어절 끝에 붙는 채팅 장식: 낱자모(ㅋㅋ·ㅠㅠ), 이모지, '^'·'♡' 같은 기호. */
        private fun isDecoration(c: Char): Boolean =
            c in '\u3131'..'\u318E' || c in "^♡♥☆★*" || Character.isSurrogate(c) ||
                Character.getType(c) == Character.OTHER_SYMBOL.toInt()

        private fun edits(vararg pairs: Pair<Char, Pair<Char, Float>>): Map<Char, List<Pair<Char, Float>>> =
            pairs.groupBy({ it.first }, { it.second })

        /**
         * 예사소리 → 된소리. 쉬프트를 놓친 오타를 되돌린다(있을가 → 있을까, 진자 → 진짜, 훨신 → 훨씬).
         *
         * **반대쪽(된소리 → 예사소리)은 없앴다(2026-10-11).** 사람이 실수로 쉬프트를 더 누르는 일은 드물고, 그 편집은 거의 언제나
         * 멀쩡한 말을 딴 말로 바꿨다 — 큰 시험지에서 '깨 볶는 → 개 볶는', '짐 싸세요 → 사세요', '썸 → 섬', '쩌는 → 저는',
         * '살 찔 → 질'. 맞게 고친 것은 '쪼금 → 조금', '짜르기 → 자르기' 같은 말투 고치기뿐이라 잃을 것이 없다(오타 시험지 그대로).
         */
        val CHOSEONG_EDITS = edits(
            'ㄱ' to ('ㄲ' to 1.8f),
            'ㄷ' to ('ㄸ' to 1.8f),
            'ㅂ' to ('ㅃ' to 1.8f),
            'ㅅ' to ('ㅆ' to 1.8f),
            'ㅈ' to ('ㅉ' to 1.8f)
        )

        /** 소리가 같아진 모음들. 한국어 오타의 가장 큰 축이다. */
        val JUNGSEONG_EDITS = edits(
            'ㅐ' to ('ㅔ' to 1.0f), 'ㅔ' to ('ㅐ' to 1.0f),
            'ㅒ' to ('ㅖ' to 1.5f), 'ㅖ' to ('ㅒ' to 1.5f),
            'ㅙ' to ('ㅚ' to 1.2f), 'ㅙ' to ('ㅞ' to 1.5f),
            'ㅚ' to ('ㅙ' to 1.2f), 'ㅚ' to ('ㅞ' to 1.5f),
            'ㅞ' to ('ㅙ' to 1.5f), 'ㅞ' to ('ㅚ' to 1.5f),
            'ㅕ' to ('ㅖ' to 2.0f), 'ㅖ' to ('ㅕ' to 2.0f)
            // ㅢ 는 뺐다. '의'는 가장 흔한 조사라 '이/에'로 바꾸는 후보가 늘 그럴듯해 보여서
            // ('비숍의'→'비숍이') 멀쩡한 문장을 망친다.
        )

        /**
         * 받침을 통째로 빠뜨린 오타를 되돌리는 값.
         *
         * 다른 편집은 **자모를 잘못 친 것**(ㅐ/ㅔ, ㅅ/ㅆ)인데 이건 **키를 하나 덜 친 것**이다.
         * 두벌식에서 받침은 따로 한 번 더 눌러야 해서 흔하다. 그런데 편집 표에 없어서
         * 우리 엔진은 이걸 **0.4%밖에 못 되돌렸다**.
         *
         * | 값 | 받침 되살림 | 멀쩡한 글 건드림 |
         * |---|---|---|
         * | 없음 | 0.4% | 1.00% |
         * | 2.5 | 62.7% | 1.20% |
         * | **3.0** | **60.2%** | 1.17% → **1.00%** (아래 문지방과 함께) |
         * | 4.0 | 54.5% | 1.10% |
         *
         * 받침을 **빼는** 쪽도 대 봤는데 전 항목이 나빠졌다(오교정 1.53%). 안 넣는다.
         */
        const val CODA_ADD_COST = 3.0f

        /** 모르는 말에 받침을 채워 흔한 말(ln [DROPPED_CODA_MIN_LN] 이상)이 되는 편집에서 덜 무는 값([candidatesFor]). */
        private const val DROPPED_CODA_DISCOUNT = 1.5f
        private const val DROPPED_CODA_MIN_LN = 4.0f
        /** [realWordForm] — 줄기 명사가 말뭉치에 이만큼(ln) 나와야 진짜 말로 본다. */
        private const val REAL_STEM_LN = 2.0f

        /** 보조용언 '않-' 이 올 수 있는 앞말의 끝('하지 않았다', '좋지도 않다', '가진 않는다'). */
        private val NEG_AUX_BEFORE = listOf("지", "지도", "지는", "지가", "지를", "지만", "진", "질", "지조차")

        /** [politeScore] — 아는 어절에 '요' 를 붙인 모르는 어절에 더 물리는 값(ln). */
        private const val POLITE_YO_COST = 3.0f

        /** [editedAtCut] — 앞 조각 끝을 고친 자리 뒤의 조각이 이만큼 흔하면(ln, 곳이 8.9·먹고 9.9) 붙여 친 오타를 푼 것으로 본다. 신분은 4.5·에도 6.5. */
        private const val CUT_RIGHT_COMMON_LN = 7.0f

        /** [monoNounSwap] — 한 음절 명사가 이만큼 흔해야(말뭉치 ln, 곳 8.1·몸 7.5) 멀쩡한 명사로 본다. */
        private const val MONO_NOUN_LN = 7.0f
        /** [realWordForm] 이 글자로 떼어 보는 조사·서술격 꼴(긴 것부터). */
        private val REAL_FORM_PARTICLES = listOf(
            "에서는", "에서도", "으로는", "에게는", "에서", "에게", "으로", "에는", "에선", "이나", "이며", "이다", "이고",
            "은", "는", "이", "가", "을", "를", "에", "의", "도", "만", "로", "서", "선", "인", "과", "와"
        )
        /** [realWordForm] — 용언 활용형을 진짜 말로 볼 만큼 분석이 자연스러운가(분석값). */
        private const val NATURAL_FORM_COST = 1500

        /**
         * 이보다 드문 어절에만 받침 넣기를 허용한다. ln(빈도) 3 ≈ 6,700만 어절에 스무 번.
         *
         * 문지방이 없으면 **뜻이 바뀐다** — '서명해 → 설명해', '이식될 → 인식될',
         * '끝내는지 → 끝냈는지'. 받침이 빠지면 거의 언제나 없는 말이 되므로,
         * 흔한 말에까지 이 편집을 열어 줄 이유가 없다.
         *
         * | 문지방 | 받침 되살림 | 멀쩡한 글 건드림 |
         * |---|---|---|
         * | 없음(무제한) | 60.2% | 1.17% |
         * | 0 (모르는 말만) | 45.5% | 1.07% |
         * | **3** | **50.6%** | **1.07%** |
         * | 5 | 53.2% | 1.13% |
         */
        const val CODA_SLIP_MAX_LN = 3.0f

        /** 형태소 분석 비용이 이보다 낮으면 아주 자연스러운 한 낱말로 보고 받침 넣기를 안 한다. 한 낱말인 것들의 중앙값이 -1331 이다. */
        const val NATURAL_WORD_COST = -1000

        /**
         * 두벌식 자판에서 **옆 키를 누른** 오타를 되돌리는 값.
         *
         * 소리가 헷갈려서가 아니라 손가락이 빗나가서 나는 오타다. 폰에서는 이게 제일 흔한데
         * 편집 표가 소리 혼동만 보고 있어서 **3.0%밖에 못 되돌렸다**.
         *
         * 값이 다른 편집(1.0~3.0)보다 훨씬 비싼 이유: 후보가 자모 하나당 서넛씩 쏟아진다.
         * 싸게 두면 아무 말이나 가까운 다른 말로 미끄러진다.
         *
         * | 값 | 옆 키 되살림 | 멀쩡한 글 건드림 | 늘어난 오교정의 성격 |
         * |---|---|---|---|
         * | 없음 | 3.0% | 1.07% | |
         * | **8.0** | **39.0%** | **1.20%** | 지명 표기('로열 → 로얄') 넷 |
         * | 6.0 | 47.4% | 1.27% | 여기서부터 뜻이 바뀐다('먹기 → 막기') |
         * | 4.0 | 55.5% | 1.50% | '케이블 → 테이블', '분비 → 준비' |
         */
        const val NEAR_KEY_COST = 8.0f

        /**
         * 옆 키 편집은 **말뭉치가 아예 모르는 어절에만** 연다.
         *
         * 받침 편집(ln<3)보다 훨씬 좁다. 옆 키 편집은 후보가 자모 하나당 서넛씩 쏟아져서
         * 아무 말이나 가까운 다른 말로 미끄러지기 때문이다.
         */
        const val NEAR_KEY_MAX_LN = 0f

        /**
         * 천지인 오타 편집 값. [cheonjiin] 이 켜졌을 때만, **말뭉치가 모르는 어절**에만 연다.
         *
         * | 값 | 자음 되살림 | 모음 되살림 | 격식체 오교정 |
         * |---|---|---|---|
         * | 없음 | 6.6% | 21.9% | 1.53% |
         * | 2.0 | 58.5% | 54.4% | 2.00% |
         * | **3.0** | **53.7%** | **46.1%** | **1.67%** (지명 '부트→보트', '행사기→행하기') |
         * | 4.0 | 49.8% | 40.8% | 1.67% |
         *
         * 문지방을 ln 3 까지 넓히면 되살림이 조금 오르지만 오교정이 2.67% 로 뛴다.
         */
        const val CJI_COST = 3.0f
        const val CJI_MAX_LN = 0f

        /** 천지인 편집을 하지 않는 어절 끝 조사. */
        private const val CJI_PARTICLES = "은을는를이가"

        /** 천지인 한 키에 실린 이웃 자음. 한 번 덜·더 누르면 이리로 간다. */
        private val CJI_NEIGHBORS = mapOf(
            'ㄱ' to 'ㅋ', 'ㅋ' to 'ㄱ', 'ㄴ' to 'ㄹ', 'ㄹ' to 'ㄴ', 'ㄷ' to 'ㅌ', 'ㅌ' to 'ㄷ',
            'ㅂ' to 'ㅍ', 'ㅍ' to 'ㅂ', 'ㅅ' to 'ㅎ', 'ㅎ' to 'ㅅ', 'ㅈ' to 'ㅊ', 'ㅊ' to 'ㅈ', 'ㅇ' to 'ㅁ', 'ㅁ' to 'ㅇ'
        )

        /** ㆍ를 ㅣ·ㅡ 반대쪽에 찍은 모음. */
        private val CJI_VOWELS = mapOf(
            'ㅏ' to 'ㅓ', 'ㅓ' to 'ㅏ', 'ㅗ' to 'ㅜ', 'ㅜ' to 'ㅗ', 'ㅑ' to 'ㅕ', 'ㅕ' to 'ㅑ', 'ㅛ' to 'ㅠ', 'ㅠ' to 'ㅛ'
        )

        private val CONSONANTS = "ㅂㅈㄷㄱㅅㅁㄴㅇㄹㅎㅋㅌㅊㅍ".toSet()

        /**
         * 두벌식 자판에서 맞닿은 자모. 같은 줄 양옆과 위아래 줄을 잇는다.
         *
         * 초성·중성·종성 어디에 쓰이든 표는 하나다 — 자판은 한 벌이므로. 그 자리에 올 수
         * 없는 자모는 [Hangul] 의 색인이 -1 을 돌려줘서 저절로 걸러진다.
         */
        private val NEAR_KEYS: Map<Char, List<Char>> = buildMap {
            val rows = listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ")
            fun link(a: Char, b: Char) {
                // 자음↔모음은 서로 대신할 수 없다. 이을 이유가 없다.
                if ((a in CONSONANTS) != (b in CONSONANTS)) return
                put(a, (get(a) ?: emptyList()) + b)
                put(b, (get(b) ?: emptyList()) + a)
            }
            for (row in rows) for (i in 0 until row.length - 1) link(row[i], row[i + 1])
            for (i in rows[1].indices) link(rows[0][i], rows[1][i])
            for (i in rows[2].indices) link(rows[1][i], rows[2][i])
        }

        /** 흔한 홑받침. 겹받침은 기존 표가 이미 본다. */
        private val CODA_SLIPS: List<Pair<Char, Float>> =
            listOf('ㄱ', 'ㄴ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅇ').map { it to CODA_ADD_COST }

        /** 받침 혼동. 소리가 같거나(ㅅ/ㅆ/ㄷ/ㅈ/ㅊ/ㅌ) 겹받침의 한쪽이 떨어진 것. */
        private val JONG_S = Hangul.jongseongIndex('ㅅ')
        private val JONG_SS = Hangul.jongseongIndex('ㅆ')
        private val CHO_N = Hangul.choseongIndex('ㄴ')
        private val JUNG_A = Hangul.jungseongIndex('ㅏ')

        /** 과거형 '-았/-었/-였/-했/-겠'을 이루는 모음. */
        private val PAST_VOWELS = "ㅏㅓㅕㅐㅘㅝㅙㅞㅔ".map { Hangul.jungseongIndex(it) }.toSet()

        /** 과거형 받침 뒤에 오는 어미의 첫머리. */
        private val ENDING_STARTS = listOf(
            "어", "다", "네", "지", "고", "는", "던", "을", "음", "잖", "죠", "습", "으", "니",
            "나", "냐", "대", "더", "구", "거", "기", "겠"
        )

        /**
         * 어미 앞 ㅅ→ㅆ 값. **음수다** — 편집 기본값([EDIT_BASE_COST])을 거의 지워 준다.
         * 이 오타는 우연히 맞는 말이 될 일이 드물어서('낫'만 뺐다) 원문과 겨룰 때 확실히 이기게.
         */
        const val PAST_SLIP_COST = -1.0f

        /** 소리가 같아진 모음. 멀쩡한 말(두 음절 이상)의 모음은 이것끼리만 고친다. */
        private val SAME_SOUND_VOWELS = setOf('ㅐ', 'ㅔ', 'ㅙ', 'ㅚ', 'ㅞ')

        /**
         * 받침을 **채우는** 편집 — 겹받침 한쪽이나 쉬프트를 빠뜨린 것(괜찬 → 괜찮, 업 → 없, 잇 → 있, 조아 → 좋아).
         * 멀쩡한 말의 받침은 이쪽으로만 고친다. 반대쪽(줄이기)과 소리 같은 받침끼리 바꾸기는 딴 말을 만든다.
         */
        private val CODA_COMPLETIONS = setOf(
            'ㅅ' to 'ㅆ', 'ㄴ' to 'ㄶ', 'ㄴ' to 'ㄵ', 'ㄹ' to 'ㅀ', 'ㄹ' to 'ㄻ', 'ㄹ' to 'ㄼ',
            'ㄱ' to 'ㄲ', 'ㄱ' to 'ㄳ', 'ㅂ' to 'ㅄ', NO_JONG to 'ㅎ'
        )

        val JONGSEONG_EDITS = edits(
            'ㅅ' to ('ㅆ' to 0.8f), 'ㅆ' to ('ㅅ' to 0.8f),
            'ㄴ' to ('ㄶ' to 1.5f), 'ㄶ' to ('ㄴ' to 1.5f),
            'ㄹ' to ('ㅀ' to 1.5f), 'ㅀ' to ('ㄹ' to 1.5f),
            'ㄱ' to ('ㄲ' to 1.8f), 'ㄲ' to ('ㄱ' to 1.8f),
            'ㅂ' to ('ㅄ' to 1.8f), 'ㅄ' to ('ㅂ' to 1.8f),
            // ㄻ → ㄹ 은 안 한다 — ㄻ 은 ㄹ·ㅁ 두 키를 모음 없이 잇달아 쳐야 생겨 실수로 잘 안 나오고, ㄹ 줄기의 명사형('나이 듦이',
            // '힘듦에도', '만듦')은 바른 말이다. 이 편집이 '듦이 → 들이', '힘듦에도 → 힘들 에도' 를 냈다(2026-10-15).
            'ㄹ' to ('ㄻ' to 2.0f),
            'ㄹ' to ('ㄼ' to 2.0f), 'ㄼ' to ('ㄹ' to 2.0f),
            'ㄴ' to ('ㄵ' to 2.0f), 'ㄵ' to ('ㄴ' to 2.0f),
            'ㄱ' to ('ㄳ' to 2.5f), 'ㄳ' to ('ㄱ' to 2.5f),
            'ㄷ' to ('ㅅ' to 2.0f), 'ㅅ' to ('ㄷ' to 2.2f),
            'ㅈ' to ('ㅅ' to 2.0f), 'ㅅ' to ('ㅈ' to 2.2f),
            'ㅊ' to ('ㅅ' to 2.0f), 'ㅅ' to ('ㅊ' to 2.2f),
            'ㅌ' to ('ㅅ' to 2.0f), 'ㅅ' to ('ㅌ' to 2.2f),
            'ㅎ' to (NO_JONG to 2.0f), NO_JONG to ('ㅎ' to 2.0f),
            'ㅍ' to ('ㅂ' to 2.5f), 'ㅂ' to ('ㅍ' to 2.5f),
            'ㅋ' to ('ㄱ' to 2.5f), 'ㄱ' to ('ㅋ' to 2.5f)
        )

    }
}
