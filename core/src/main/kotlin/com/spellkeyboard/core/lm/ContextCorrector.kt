package com.spellkeyboard.core.lm

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

    /** 형태소 사전이 어절을 어떻게 나누는지. 같은 어절은 한 번만. 값이 null 이면 안 나눈다. */
    private val splits = object : LinkedHashMap<String, String?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean =
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

    /** 합칠 자리에 매인 형태소가 오는지도 분석이 필요하다. 같은 (어절, 자리)는 한 번만. */
    private val boundJunctions = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > UNKNOWN_CACHE_SIZE
    }

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
        for (particle in TRAILING_PARTICLES) {
            // 조사를 떼고 남는 것이 한 음절이면 어절로 보지 않는다.
            if (word.length <= particle.length + 1) continue
            if (!word.endsWith(particle)) continue
            if (lm.lnCount(word.dropLast(particle.length)) != null) return true
        }
        return false
    }

    fun correct(window: String, contextBefore: String? = null): String? {
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
            val decoded = decode(segment.map { it.core }, prev)
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
         * 고칠 수 있는 어절 — 한글 음절로만 되어 있고, 뒤에는 문장 부호만 붙을 수 있다.
         * '3시에' 처럼 앞에 다른 글자가 붙은 것은 언어모델이 본 적 없는 어절이라 건드리지 않는다.
         */
        val soft: Boolean =
            !whitespace && prefix.isEmpty() && core.isNotEmpty() && core.length <= MAX_TOKEN_SYLLABLES &&
                core.all { it in HANGUL } && suffix.all { it in PUNCTUATION }
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
        val pieceBoundary: BooleanArray
    )

    /**
     * 어절 묶음 하나를 푼다.
     *
     * @return 새 어절 목록. 원문 그대로가 최선이면 null.
     */
    private fun decode(cores: List<String>, contextBefore: String?, trace: StringBuilder? = null): List<String>? {
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
        val layout = Layout(cores, boundary, starts, owner, pieceBoundary)
        val candidates = HashMap<Long, List<Candidate>>()
        for (span in spans) {
            val key = spanKey(span.start, span.end)
            val list = candidates[key]
            val fresh = candidatesFor(text.substring(span.start, span.end), span.kind)
            candidates[key] = if (list == null) fresh else merge(list, fresh)
        }

        val lattice = arrayOfNulls<HashMap<String, Node>>(n + 1)
        val initialKey = contextBefore ?: UNKNOWN_CONTEXT
        lattice[0] = hashMapOf(initialKey to Node(0f, -1, "", ""))

        for (i in 0 until n) {
            val here = lattice[i] ?: continue
            for (j in i + 1..minOf(n, i + MAX_TOKEN_SYLLABLES)) {
                val list = candidates[spanKey(i, j)] ?: continue
                for ((prevKey, node) in prune(here)) {
                    for (candidate in list) {
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

        val finish = lattice[n] ?: return null
        val best = finish.values.maxByOrNull { it.score } ?: return null
        val identity = identityScore(cores, contextBefore, trace)

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
        if (trace != null) {
            trace.append("최선 %.2f: %s\n".format(best.score, tokens.joinToString(" ")))
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
            trace.append("차이 %.2f (문턱 %.2f)\n".format(best.score - identity, MARGIN))
        }
        if (best.score - identity < MARGIN) return null
        return if (tokens == cores) null else tokens
    }

    /** 왜 그렇게 고쳤는지(또는 안 고쳤는지) 점수를 풀어 쓴다. 조정·진단용. */
    fun explain(window: String, contextBefore: String? = null): String {
        val trace = StringBuilder()
        val pieces = tokenize(window)
        val cores = pieces.filter { it.soft }.map { it.core }
        if (cores.isEmpty()) return "고칠 수 있는 어절이 없다"
        decode(cores, contextBefore, trace)
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

    private fun candidatesFor(surface: String, kind: Int): List<Candidate> {
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
        if (kind == KIND_PIECE) return out

        // 편집 후보는 언어모델이 아는 어절만. 모르는 말끼리 형태소 비용으로 겨루게 하면
        // 이름과 외래어('웰트쿠겔브루넨')가 비슷한 소리의 엉뚱한 말로 바뀐다.
        val penalty = knownPenalty(identityCount) + EDIT_BASE_COST
        for ((edited, cost) in singleEdits(
            surface,
            codaSlip = (identityCount ?: -1f) < CODA_SLIP_MAX_LN,
            nearKey = (identityCount ?: -1f) < NEAR_KEY_MAX_LN
        )) {
            if (lm.lnCount(edited) != null && !(kind == KIND_FREE && startsBound(edited))) {
                out += Candidate(edited, -(cost + penalty))
            }
        }
        if (kind == KIND_IDENTITY && !identityKnown && surface.length <= MAX_DOUBLE_EDIT_SYLLABLES) {
            for ((once, cost1) in singleEdits(surface)) {
                for ((twice, cost2) in singleEdits(once)) {
                    if (twice != surface && lm.lnCount(twice) != null) {
                        out += Candidate(twice, -(cost1 + cost2 + EDIT_BASE_COST))
                    }
                }
            }
        }
        return if (out.size <= 1) out else merge(out, emptyList())
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
                if (endsWithBoundaryMarker(prevKey)) cost += BOUNDARY_MARKER_BONUS
                else if (nounJunction(prevKey, text)) cost -= NOUN_JUNCTION_COST
                if (endsWithAEo(prevKey) && spacer?.tagAt(core, offset)?.let { spacer?.isVerbTag(it) } == true) return null
                // 체언 뒤의 '받다·당하다·시키다·드리다' 는 한 낱말이다('발급받았다', '인계받았다').
                if (ATTACHABLE_VERBS.any { text.startsWith(it) } && endsNominal(prevKey)) return null
            }
            cost += SPLIT_COST
        }
        var merged = false
        for (p in start + 1 until end) {
            if (!layout.boundary[p]) continue
            if (!boundAt(text, p - start)) return null
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
        val known = lm.lnConditional(prev, text) ?: return unknownScore(text)
        return known + usagePenalty(text)
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
        if (cost >= Spacer.UNANALYZABLE || (text.length >= 3 && splitOf(text) != null)) {
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
    private fun splitOf(text: String): String? {
        val spacer = this.spacer ?: return null
        synchronized(splits) { if (splits.containsKey(text)) return splits[text] }
        val spaced = spacer.space(text)?.let { rejoinOptional(text, it) }
        synchronized(splits) { splits[text] = spaced }
        return spaced
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
                // 명사|명사: 언어모델이 두 조각을 잇달아 충분히 봤을 때만 띄운다.
                (nounJunction(before, after) && !nounSplitWorthIt(before, after)) ||
                    // '-아/-어' 뒤의 용언: 합성동사거나 보조용언.
                    (endsWithAEo(before) && tagAtJunction != null && spacer?.isVerbTag(tagAtJunction) == true) ||
                    // 조사·어미로도 읽히는 한 음절('어', '오')을 조각으로 떼어 내지 않는다.
                    boundOnlyPiece(before) || boundOnlyPiece(after) ||
                    // 체언 뒤의 '받다·당하다·시키다·드리다' 는 한 낱말('발급받았다').
                    (ATTACHABLE_VERBS.any { after.startsWith(it) } && endsNominal(before))
            if (optional) out[out.size - 1] = before + after else out += after
            junction += after.length
        }
        return if (out.size < 2) null else out.joinToString(" ")
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
    private fun singleEdits(word: String, codaSlip: Boolean = false, nearKey: Boolean = false): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for (index in word.indices) {
            val (cho, jung, jong) = Hangul.decompose(word[index]) ?: continue
            if (codaSlip && jong == 0) CODA_SLIPS.forEach { (alternative, cost) ->
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
                val id = Hangul.jungseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(cho, id, jong)) to cost
            }
            JONGSEONG_EDITS[Hangul.JONGSEONG[jong]]?.forEach { (alternative, cost) ->
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
        /** 원문보다 이만큼(ln) 좋아야 고친다. 통로 비용이 이미 들어간 뒤의 여유분이다. */
        const val MARGIN = 1.5f

        /**
         * 원래 있던 띄어쓰기를 없애는 비용. 지운 자리에 매인 형태소(접미사·어미·조사)가
         * 시작할 때만 허용한다 — 원래 띄면 안 되던 자리다. 그 밖의 자리는 아예 붙이지 않는다.
         * 한국어 표준은 띄는 쪽이고, 사람들이 붙여 쓰는 습관('와봐', '할수')을 말뭉치가
         * 그대로 배웠기 때문이다.
         */
        const val BOUND_MERGE_COST = -3.0f

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
        private val TOKEN = Regex("""\S+|\s+""")
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…')
        private val PUNCTUATION = setOf('.', ',', '!', '?', '…', '~', ')', '"', '\'', '”', '’', ';', ':')

        private fun edits(vararg pairs: Pair<Char, Pair<Char, Float>>): Map<Char, List<Pair<Char, Float>>> =
            pairs.groupBy({ it.first }, { it.second })

        /** 된소리/예사소리. */
        val CHOSEONG_EDITS = edits(
            'ㄱ' to ('ㄲ' to 1.8f), 'ㄲ' to ('ㄱ' to 1.8f),
            'ㄷ' to ('ㄸ' to 1.8f), 'ㄸ' to ('ㄷ' to 1.8f),
            'ㅂ' to ('ㅃ' to 1.8f), 'ㅃ' to ('ㅂ' to 1.8f),
            'ㅅ' to ('ㅆ' to 1.8f), 'ㅆ' to ('ㅅ' to 1.8f),
            'ㅈ' to ('ㅉ' to 1.8f), 'ㅉ' to ('ㅈ' to 1.8f)
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
        val JONGSEONG_EDITS = edits(
            'ㅅ' to ('ㅆ' to 0.8f), 'ㅆ' to ('ㅅ' to 0.8f),
            'ㄴ' to ('ㄶ' to 1.5f), 'ㄶ' to ('ㄴ' to 1.5f),
            'ㄹ' to ('ㅀ' to 1.5f), 'ㅀ' to ('ㄹ' to 1.5f),
            'ㄱ' to ('ㄲ' to 1.8f), 'ㄲ' to ('ㄱ' to 1.8f),
            'ㅂ' to ('ㅄ' to 1.8f), 'ㅄ' to ('ㅂ' to 1.8f),
            'ㄹ' to ('ㄻ' to 2.0f), 'ㄻ' to ('ㄹ' to 2.0f),
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
