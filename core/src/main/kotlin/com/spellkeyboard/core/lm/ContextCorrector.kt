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
        for ((edited, cost) in singleEdits(surface)) {
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
                if (isWellFormed(core)) return null
                if (boundAt(core, offset)) return null
                if (prevKey == UNKNOWN_CONTEXT || lm.lnBigramCount(prevKey, text) == null) return null
                if (nounJunction(prevKey, text)) return null
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
                (nounJunction(before, after) && (lm.lnBigramCount(before, after) ?: 0f) < NOUN_SPLIT_MIN_LN) ||
                    // '-아/-어' 뒤의 용언: 합성동사거나 보조용언.
                    (endsWithAEo(before) && tagAtJunction != null && spacer?.isVerbTag(tagAtJunction) == true) ||
                    // 조사·어미로도 읽히는 한 음절('어', '오')을 조각으로 떼어 내지 않는다.
                    (before.length == 1 && couldBeBound(before)) || (after.length == 1 && couldBeBound(after)) ||
                    // 체언 뒤의 '받다·당하다·시키다·드리다' 는 한 낱말('발급받았다').
                    (ATTACHABLE_VERBS.any { after.startsWith(it) } && endsNominal(before))
            if (optional) out[out.size - 1] = before + after else out += after
            junction += after.length
        }
        return if (out.size < 2) null else out.joinToString(" ")
    }

    /** '-아/-어' 꼴로 끝나는가: 받침 없이 ㅏ·ㅓ 계열 모음으로 끝난다 (아, 어, 해, 봐, 줘, 겨, 려…). */
    private fun endsWithAEo(text: String): Boolean {
        val (_, jung, jong) = Hangul.decompose(text.last()) ?: return false
        return jong == 0 && Hangul.JUNGSEONG[jung] in A_EO_VOWELS
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
    private fun singleEdits(word: String): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for (index in word.indices) {
            val (cho, jung, jong) = Hangul.decompose(word[index]) ?: continue
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

        /** 없던 띄어쓰기를 넣는 비용. 나뉜 조각이 모두 아는 어절이어야 하므로 싸게 둔다. */
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
        const val WELL_FORMED_PER_SYLLABLE = 0.5f
        const val WELL_FORMED_COST_MAX = 2f
        /** 한 어절로 분석이 안 되는 덩어리: 두 음절을 넘는 음절마다 이만큼. 어절 하나 값이다. */
        const val UNANALYZABLE_PER_SYLLABLE = 4.5f
        /** 아는 어절이지만 한 어절로 분석되지 않을 때 깎는 값. */
        const val USAGE_PENALTY = -5f
        /** 분석은 되지만 비용이 이걸 넘으면 [USAGE_PENALTY_PER_COST] 마다 1 씩, 최대 [USAGE_PENALTY_GRADED_MAX]. */
        const val USAGE_PENALTY_FREE_COST = 4000f
        const val USAGE_PENALTY_PER_COST = 2000f
        const val USAGE_PENALTY_GRADED_MAX = 3f

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
