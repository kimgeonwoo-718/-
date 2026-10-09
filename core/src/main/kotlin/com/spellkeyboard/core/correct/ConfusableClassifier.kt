package com.spellkeyboard.core.correct

import com.spellkeyboard.core.lm.LanguageModel

/**
 * 헷갈리는 말을 [ConfusableModel] 로 가려 고친다.
 *
 * 규칙 표([ConfusableRules])가 "이 낱말 옆에 이 낱말이면" 을 하나씩 적어 둔 것이라면, 이쪽은 **실제 글에서
 * 센 값**으로 고른다. 처음 보는 낱말('삼전이 낳을까')에도 되는 것이 차이다.
 *
 * ## 언제 고치나
 *
 * 모델이 반대쪽이라고 [ConfusablePair.flipConfidence] 이상 확신할 때만 고친다. **애매하면 안 건드린다.**
 * 고치는 것보다 멀쩡한 글을 안 건드리는 것이 더 중요하다 — 번역문(KorNLI) 2,201 용례에서 모델이 글쓴이와
 * 90% 이상 반대로 본 것은 2건(0.09%)이었다.
 *
 * **문턱은 방향마다 다르다.** 사람들이 틀리는 방향이 한쪽으로 쏠려 있어서다. 영화평에서 '낳' 으로 쓴 140개 중
 * 24% 는 문맥이 '낫' 이었다('원작이 더 낳다'). 반대로 '나' 로 쓴 1,101개 중 문맥이 '낳' 인 것은 0.3% 였다.
 * 틀렸을 가능성이 높은 쪽(낳→낫)은 문턱을 낮추고, 낮은 쪽(낫→낳)은 아주 확신할 때만 고친다.
 *
 * ## 입력 중에는 뒤를 못 본다
 *
 * 실시간 교정은 커서 앞 세 어절만 본다. 그래서 입력 중에는 **앞 두 어절만으로** 고른다([ConfusableModel.Look.NONE]).
 * 글 전체를 고칠 때만 뒤 두 어절까지 본다([ConfusableModel.Look.ALL]).
 *
 * **다만 관형형('낳은·낳을')은 다음 어절이 나올 때까지 기다린다**([ConfusablePair.needsNextWord]). '이게 낳은 ○○' 는
 * ○○ 가 '결과' 면 낳다(빚어낸)고 '것' 이면 낫다(더 좋은)라, 앞 어절만으로는 반반이다. 다음 어절이 나오면 창이 밀려
 * 이 어절이 아직 창 안에 있으므로, 그때 한 어절 뒤까지 보고 가린다([ConfusableModel.Look.NEXT]).
 *
 * **앞 두 어절이 안 갖춰진 어절은 입력 중에는 건드리지 않는다.** 창이 밀리면서 같은 어절이 다른 앞
 * 문맥으로 다시 검사되는데, 앞이 한 어절뿐인 채로 방금 한 판정을 뒤집으면 고친 것을 도로 되돌리게 된다.
 */
class ConfusableClassifier internal constructor(
    private val model: ConfusableModel,
    private val pairs: List<ConfusablePair>
) {

    constructor(model: ConfusableModel) : this(model, DEFAULT_PAIRS.filter { model.has(it.name) })

    /**
     * [text] 안의 헷갈리는 어절을 문맥으로 가려 고친다.
     *
     * @param contextBefore [text] 바로 앞 어절. [LanguageModel.BOS] 면 문장 첫머리, null 이면 모름.
     * @param lookahead [text] 가 문장을 끝까지 담고 있나(글 전체 교정). 입력 중 창이면 false.
     */
    fun apply(text: String, contextBefore: String?, lookahead: Boolean, sink: MutableList<Correction>): String {
        if (pairs.isEmpty()) return text
        val matches = WORD.findAll(text).toList()
        if (matches.isEmpty()) return text

        // 후보를 싸게 먼저 찾는다. 대부분의 창에는 없다.
        var candidates: ArrayList<Candidate>? = null
        for ((index, m) in matches.withIndex()) {
            val run = ONE_RUN.matchEntire(m.value) ?: continue
            val token = run.groups[1]!!.value
            for (pair in pairs) {
                val cls = pair.classOf(token) ?: continue
                if (pair.skip(token)) continue
                val list = candidates ?: ArrayList<Candidate>().also { candidates = it }
                list.add(Candidate(index, pair, cls, run.groups[1]!!.range))
                break
            }
        }
        val found = candidates ?: return text

        // 앞 어절을 한 칸 밀어 넣는다. 그러면 위치 계산이 한 가지다.
        val priorWord = contextBefore?.takeIf { it != LanguageModel.BOS }
        val offset = if (priorWord != null) 1 else 0
        val words = ArrayList<String>(matches.size + offset)
        if (priorWord != null) words += priorWord
        for (m in matches) words += m.value
        val breaks = BooleanArray(words.size)
        for (k in words.indices) {
            val atEnd = words[k].lastOrNull() in SENTENCE_ENDERS
            val newlineAfter = k >= offset && k - offset + 1 < matches.size &&
                text.substring(matches[k - offset].range.last + 1, matches[k - offset + 1].range.first).contains('\n')
            breaks[k] = atEnd || newlineAfter
        }

        val replaced = HashMap<Int, String>()
        for (c in found) {
            val j = c.index + offset
            var s = j
            var bos = false
            while (s > 0) {
                if (breaks[s - 1]) { bos = true; break }
                s--
            }
            if (s == 0 && !bos) bos = priorWord == null && contextBefore == LanguageModel.BOS
            if (!lookahead && !bos && j - s < REQUIRED_LEFT_WORDS) continue

            var e = j
            while (e < words.size - 1 && !breaks[e]) e++
            val look = when {
                lookahead -> ConfusableModel.Look.ALL
                c.pair.needsNextWord(words[j].substring(c.run.first, c.run.last + 1)) ->
                    if (e > j) ConfusableModel.Look.NEXT else continue      // 다음 어절이 아직 안 나왔다 — 기다린다
                else -> ConfusableModel.Look.NONE
            }

            val slice = words.subList(s, e + 1)
            val p = model.probability(c.pair.name, look, slice, j - s, bos) ?: continue
            val confidence = if (c.cls == 0) p else 1 - p            // 반대쪽이라고 믿는 정도
            if (confidence < c.pair.flipConfidence(c.cls)) continue
            val target = 1 - c.cls
            val original = words[j]
            val token = original.substring(c.run.first, c.run.last + 1)
            val moved = c.pair.convert(token, target) ?: continue
            val fixed = original.substring(0, c.run.first) + moved + original.substring(c.run.last + 1)
            words[j] = fixed              // 다음 어절은 고친 말을 앞 문맥으로 본다
            replaced[c.index] = fixed
            sink += Correction(original, fixed, "맞춤법(문맥)")
        }
        if (replaced.isEmpty()) return text

        val out = StringBuilder(text.length)
        var last = 0
        for ((index, m) in matches.withIndex()) {
            val fixed = replaced[index] ?: continue
            out.append(text, last, m.range.first).append(fixed)
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private class Candidate(val index: Int, val pair: ConfusablePair, val cls: Int, val run: IntRange)

    companion object {
        /** 입력 중에 판정하려면 대상 앞에 있어야 하는 어절 수(문장 첫머리면 면제). [ConfusableModel] 의 `rt` 와 같다. */
        private const val REQUIRED_LEFT_WORDS = 2

        private val WORD = Regex("""\S+""")
        private val ONE_RUN = Regex("""[^가-힣]*([가-힣]+)[^가-힣]*""")
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…')

        val DEFAULT_PAIRS: List<ConfusablePair> = listOf(NatNah)

        @Volatile
        private var bundled: ConfusableClassifier? = null

        @Volatile
        private var bundledTried = false

        /**
         * 묶음 안의 모델로 만든 것을 한 번만 읽어 돌려 쓴다. 없으면 null.
         * 읽는 데 수십 ms 가 들어서, 앱은 시작할 때 미리 불러 둔다.
         */
        fun bundled(): ConfusableClassifier? {
            if (bundledTried) return bundled
            synchronized(this) {
                if (!bundledTried) {
                    bundled = ConfusableModel.loadBundled()?.let { ConfusableClassifier(it) }
                    bundledTried = true
                }
            }
            return bundled
        }
    }
}

/**
 * 헷갈리는 한 짝. 어절이 어느 쪽인지 알아보고, 반대쪽 꼴로 옮길 줄 안다. 어느 쪽이 맞는지는 모델이 정한다.
 * [name] 은 학습 도구의 `PAIRS` 이름과 같아야 한다.
 */
interface ConfusablePair {
    val name: String

    /** 한글 덩어리가 어느 쪽인가: 0 / 1 / 둘 다 아니면 null. 학습 도구의 같은 이름 함수와 똑같아야 한다. */
    fun classOf(token: String): Int?

    /** 모델이 판정하기 전에 건드리지 않기로 한 것(같은 글자의 다른 낱말). */
    fun skip(token: String): Boolean = false

    /** [token] 을 반대쪽([to]) 꼴로. 못 옮기면 null. */
    fun convert(token: String, to: Int): String?

    /**
     * 뒤에 오는 말을 봐야 가려지는 꼴인가(관형형). 그러면 입력 중에는 다음 어절이 나올 때까지 판정을 미룬다.
     * 글 전체를 고칠 때는 뒤가 이미 있어서 미루지 않는다.
     */
    fun needsNextWord(token: String): Boolean = false

    /**
     * [from] 쪽으로 쓴 것을 반대쪽으로 고치려면 모델이 반대쪽이라고 이만큼은 확신해야 한다.
     * 사람들이 어느 쪽을 더 자주 틀리는지에 따라 방향마다 다르다.
     */
    fun flipConfidence(from: Int): Double
}

/**
 * 낫다(0) ↔ 낳다(1).
 *
 * 낫다는 ㅅ 불규칙이라 모음 어미 앞에서 '나'가 된다(나아·나은·나을·나았). 자음 어미 앞에서는 '낫'(낫다·낫겠다).
 * 낳다는 늘 '낳'. 그래서 옮길 때 어미의 첫 음절이 모음으로 시작하면 '나', 아니면 '낫'을 쓴다.
 */
object NatNah : ConfusablePair {
    override val name = "natda"

    private val NAT = Regex("^(?:낫|나아(?!가|갈|간|갑|갔|감|오|올|온|옵|왔|옴)|나은|나을|나았|나으)")

    // 연장 '낫'(낫을 갈았다)과 사람 이름 '나은'(나은이)은 이 짝이 아니다.
    private val SICKLE = Regex("^낫(?:이|을|은|으로|도|만|과|와|의|에|처럼|질|날|자루)")
    private val NAME = Regex("^나은(?:이|아|야|양|씨|님)")

    override fun classOf(token: String): Int? = when {
        token.startsWith('낳') -> 1
        NAT.containsMatchIn(token) -> 0
        else -> null
    }

    override fun skip(token: String): Boolean = SICKLE.containsMatchIn(token) || NAME.containsMatchIn(token)

    /** '낳은·낳을·낳는' 처럼 어미가 관형형 하나뿐인 것. '낳을까·낳는다' 는 문장 끝 꼴이라 뒤를 안 봐도 된다. */
    override fun needsNextWord(token: String): Boolean = token.length == 2 && token[1] in "은을는"

    /**
     * 낳→낫 은 0.90, 낫→낳 은 0.98. 영화평에서 '낳' 으로 쓴 것의 24% 가 문맥은 '낫' 이었고(원작이 더 낳다),
     * '나' 로 쓴 것 중 문맥이 '낳' 인 것은 0.3% 였다. 처음 보는 문장 시험지(학습 도구 `eval/`)에서 문턱을 훑어
     * 정했다 — 0.98 이면 낫→낳 을 70% 고치면서 '아이도 나았어'(아이가 나았다) 같은 둘 다 되는 문장을 대부분 둔다.
     */
    override fun flipConfidence(from: Int): Double = if (from == 1) 0.90 else 0.98

    override fun convert(token: String, to: Int): String? {
        val from = classOf(token) ?: return null
        if (from == to || token.length < 1) return null
        val rest = token.substring(1)           // 낳 / 낫 / 나 는 모두 한 글자다
        return if (to == 1) {
            "낳$rest"
        } else when {
            rest.isEmpty() -> "낫"
            startsWithVowel(rest) -> "나" + asNat(rest)
            else -> "낫$rest"
        }
    }

    /** 낫다는 '나어'·'나었' 이 아니라 '나아'·'나았' 이다. */
    private fun asNat(rest: String): String = when (rest[0]) {
        '어' -> "아" + rest.substring(1)
        '었' -> "았" + rest.substring(1)
        else -> rest
    }

    /** 첫 음절의 첫소리가 ㅇ(모음으로 시작하는 어미)인가. */
    private fun startsWithVowel(rest: String): Boolean {
        val c = rest[0]
        return c in '가'..'힣' && (c - '가') / 588 == 11
    }
}
