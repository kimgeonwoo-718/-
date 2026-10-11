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
 *
 * ## 글 전체를 고칠 때
 *
 * **뒤까지 본 판정만 믿지 않는다 — 앞만 본 판정(입력 중에 쓰는 것)도 같은 쪽이어야 고친다**([ConfusablePair.crossCheck] 인 방향만).
 * 뒤까지 보는 모델은 '더' 를 보면 '더 나아' 로 세게 기울어 '아이를 더 낳을까'·'새끼를 더 낳았어' 를 '나을까·나았어' 로 바꿨다
 * (2026-10-10 윈도우 세션 보고 — 같은 문장을 입력 중 길은 맞게 뒀다). 받아 둔 글 전체(청원·영화평·번역문 2만 5천 문장)로
 * 견줘 보니 이 확인으로 덜 고치게 된 낳→낫 은 대부분 **잘못 고치던 것**('아이 더 낳기 무섭습니다' → '낫기')이었다. 반대 방향(낫→낳)은
 * 문턱이 이미 0.98 이라 잘못 고치는 일이 드물고, 여기에 확인을 걸면 '아이들 많이 나아서' 같은 진짜 오타를 놓쳐서 걸지 않는다.
 *
 * **오타가 다른 진짜 낱말이 된 짝**(재발 → 제발, 애기 → 얘기)은 [TypoPair] — 드문 쪽 → 흔한 쪽만 고친다.
 *
 * **같은 짝이 한 글에 여럿이면 두 번 본다.** '이게 낳아 저게 낳아' 의 앞 '낳아' 는 뒤 '낳아'(이것도 틀린 글자)를 문맥으로 읽어
 * 낳다 쪽으로 끌려갔다. 첫 바퀴에서 뒤엣것을 고친 뒤, 못 고친 것만 고친 문맥으로 한 번 더 본다.
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
        // 글 전체면 두 바퀴: 뒤에서 고친 것을 문맥으로 앞의 못 고친 것을 다시 본다. 입력 중에는 한 바퀴.
        val rounds = if (lookahead && found.size > 1) 2 else 1
        for (round in 0 until rounds) for (c in found) {
            if (c.index in replaced) continue
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
            val token = words[j].substring(c.run.first, c.run.last + 1)
            val slice = words.subList(s, e + 1)
            val look = when {
                lookahead -> ConfusableModel.Look.ALL
                c.pair.needsNextWord(token) -> when {
                    e > j -> ConfusableModel.Look.NEXT
                    // 다음 어절이 아직 안 나왔다. 그 짝이 허락하고 앞말만으로 확실하면 지금 고치고('애기했어' → '얘기했어'), 아니면 기다린다.
                    earlyFlip(c, slice, j - s, bos) -> ConfusableModel.Look.NONE
                    else -> continue
                }
                else -> ConfusableModel.Look.NONE
            }

            val p = model.probability(c.pair.name, look, slice, j - s, bos) ?: continue
            val confidence = if (c.cls == 0) p else 1 - p            // 반대쪽이라고 믿는 정도
            if (confidence < c.pair.flipConfidence(c.cls, look)) continue
            // 다음 어절이 그 짝의 드문 뜻에만 붙는 말이면 안 고친다('재발 방지', '재발 없이').
            if (e > j && c.pair.protectedBy(hangulRun(words[j + 1]))) continue
            if (look == ConfusableModel.Look.ALL && c.pair.crossCheck(c.cls)) {
                // 앞만 본 판정도 같은 쪽이어야 한다(위 "글 전체를 고칠 때"). 관형형은 앞만으로는 못 가리니 한 어절 뒤까지 본 것과 견준다.
                val near = if (c.pair.needsNextWord(words[j].substring(c.run.first, c.run.last + 1))) ConfusableModel.Look.NEXT else ConfusableModel.Look.NONE
                val q = model.probability(c.pair.name, near, slice, j - s, bos) ?: continue
                if ((if (c.cls == 0) q else 1 - q) < c.pair.flipConfidence(c.cls, near)) continue
            }
            val target = 1 - c.cls
            val original = words[j]
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

    /** 다음 어절 없이 앞말만으로 [ConfusablePair.earlyConfidence] 이상 확신하나. 그 짝이 이른 판정을 안 하면 false. */
    private fun earlyFlip(c: Candidate, slice: List<String>, i: Int, bos: Boolean): Boolean {
        val early = c.pair.earlyConfidence(c.cls) ?: return false
        val p = model.probability(c.pair.name, ConfusableModel.Look.NONE, slice, i, bos) ?: return false
        return (if (c.cls == 0) p else 1 - p) >= early
    }

    private class Candidate(val index: Int, val pair: ConfusablePair, val cls: Int, val run: IntRange)

    companion object {
        /** 입력 중에 판정하려면 대상 앞에 있어야 하는 어절 수(문장 첫머리면 면제). [ConfusableModel] 의 `rt` 와 같다. */
        private const val REQUIRED_LEFT_WORDS = 2

        private val WORD = Regex("""\S+""")
        private val ONE_RUN = Regex("""[^가-힣]*([가-힣]+)[^가-힣]*""")
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…')

        val DEFAULT_PAIRS: List<ConfusablePair> = listOf(NatNah, Jebal, Yaegi)

        /** 어절의 한글만(부호·자모·영문을 뗀 것). 보호 말을 견줄 때 쓴다. */
        private fun hangulRun(word: String): String = word.filter { it in '가'..'힣' }

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

    /** [look] 으로 본 판정에서 고치는 문턱. 기본은 보는 범위와 상관없이 [flipConfidence] 다. */
    fun flipConfidence(from: Int, look: ConfusableModel.Look): Double = flipConfidence(from)

    /**
     * 글 전체를 고칠 때 [from] 쪽으로 쓴 것을 뒤집으려면, 뒤까지 본 판정에 더해 **앞만 본 판정도** 같은 쪽이어야 하나.
     * 뒤까지 보는 모델이 한쪽으로 쏠리는 버릇이 있는 방향에만 건다([ConfusableClassifier] 의 "글 전체를 고칠 때").
     */
    fun crossCheck(from: Int): Boolean = false

    /**
     * [needsNextWord] 인 꼴을 입력 중에 다음 어절 없이 고치려면 앞말만 본 모델이 이만큼 확신해야 한다. null 이면 늘 다음 어절을 기다린다.
     * 다음 어절이 끝내 안 오는 메시지 끝('…애기했어')을 확실할 때만 잡으려는 것이다. 이렇게 고친 것은 뒤에 [protectedBy] 말이 와도 되돌리지 못한다.
     */
    fun earlyConfidence(from: Int): Double? = null

    /** 다음 어절(한글만)이 이 짝의 드문 쪽 뜻에만 붙는 말이라 고치면 안 되나('재발 방지'). 모델이 드물게 본 말을 지킨다. */
    fun protectedBy(next: String): Boolean = false
}

/**
 * 오타가 우연히 **다른 진짜 낱말**이 되는 짝(2026-10-11). 0 = 드문 말(오타로 잘 나는 꼴, 재발), 1 = 흔한 말(제발).
 *
 * 문맥 교정기는 진짜 낱말을 다른 진짜 낱말로 바꾸는 데 아주 조심한다(멀쩡한 말을 딴 말로 바꾸는 오교정을 막느라 — 큰 시험지 2026-10-11).
 * 그래서 '재발 그만해' 를 못 고쳤다(제발이 80배 흔한데도). 이 짝들은 앞뒤 말을 말뭉치에서 배운 모델로 가린다.
 *
 * - **0→1 만 고친다**. 흔한 말을 드문 말로 바꾸는 일은 없다.
 * - **다음 어절을 기다린다**: '재발 방지' 인지 '재발 좀' 인지는 뒷말이 정한다(문턱 [FLIP_NEXT]). 짝에 따라 앞말만으로 [EARLY] 이상
 *   확실하면 기다리지 않고 고친다(애기/얘기). 글 전체를 고칠 때는 앞뒤 두 어절씩 보고 [FLIP].
 * - 학습: `tools/confusable/train.py` 의 같은 이름 짝(두 쪽 무게를 맞춤). [classOf] 는 그쪽 함수와 똑같아야 한다.
 */
abstract class TypoPair(
    override val name: String,
    /** 드문 쪽(0)의 갈리는 앞부분. */
    private val rare: String,
    /** 흔한 쪽(1)의 갈리는 앞부분. */
    private val common: String
) : ConfusablePair {

    override fun convert(token: String, to: Int): String? {
        val from = classOf(token) ?: return null
        if (from == to) return null
        return if (to == 1) common + token.substring(rare.length) else rare + token.substring(common.length)
    }

    override fun needsNextWord(token: String): Boolean = true

    override fun flipConfidence(from: Int): Double = if (from == 0) FLIP else NEVER

    override fun flipConfidence(from: Int, look: ConfusableModel.Look): Double = when {
        from != 0 -> NEVER
        look == ConfusableModel.Look.NEXT -> FLIP_NEXT
        else -> FLIP
    }

    override fun earlyConfidence(from: Int): Double? = if (from == 0) EARLY else null

    companion object {
        /** 드문 말을 흔한 말로 고치는 문턱(글 전체를 볼 때, 앞말만 볼 때). 학습 도구의 TYPO_FLIP 과 같다. */
        const val FLIP = 0.95

        /**
         * 입력 중 다음 어절이 막 나왔을 때의 문턱. 한 어절 뒤까지 보는 모델은 글 전체 모델보다 확신이 덜 뾰족해서, 0.95 면
         * '재발 그만해'(0.93) 같은 뻔한 오타를 놓쳤다. 0.90 으로 내려도 멀쩡한 '재발' 을 건드리는 것은 거의 안 늘었다
         * (시험지 값은 docs/HANDOFF.md). 글 전체 쪽은 0.90 이면 건드림이 늘어서 0.95 로 둔다.
         */
        const val FLIP_NEXT = 0.90

        /**
         * 다음 어절 없이 앞말만으로 고치는 문턱('애기했어' 로 끝나는 메시지 — 어절 안의 '했어' 가 이미 단서다). 학습에서 뺀 문장으로 재 보니
         * 애기/얘기 는 이걸로 오타 52개 중 8개를 더 고치고 멀쩡한 말은 더 건드리지 않았다. 재발/제발 은 앞말만으로는 고치지 않는다([Jebal]).
         */
        const val EARLY = 0.95

        /** 흔한 말을 드문 말로는 고치지 않는다. */
        const val NEVER = 1.01
    }
}

/** 재발(0) ↔ 제발(1). 홀로 선 꼴만 — '제발' 은 '요' 말고는 붙는 말이 없어서 '재발을·재발했다' 는 오타일 수가 없다. */
object Jebal : TypoPair("jebal", "재발", "제발") {
    override fun classOf(token: String): Int? = when (token) {
        "제발", "제발요" -> 1
        "재발", "재발요" -> 0
        else -> null
    }

    /** '재발요' 는 늘 제발요다 — 기다리지 않는다. */
    override fun needsNextWord(token: String): Boolean = token == "재발"

    /**
     * '재발' 은 **늘 다음 어절을 기다린다**(앞말만으로 고치지 않는다). 앞말만 보고 고치면 뒤에 '방지·할 수' 가 와도 되돌릴 수 없는데,
     * 청원 문장('엄중하게 처벌해주시고 재발 방지')은 앞말이 부탁하는 꼴이라 모델이 제발 쪽으로 확신한다 — 학습에서 뺀 문장으로 재 보니
     * 앞말만으로 고친 것 7개 중 4개가 그런 것이었고, 문턱을 0.99 로 올려도 3개는 그대로였다. 그래서 메시지 끝의 '아 재발' 은
     * 입력 중에는 못 고치고 전체교정에서 고친다.
     */
    override fun earlyConfidence(from: Int): Double? = null

    override fun protectedBy(next: String): Boolean =
        next in JAEBAL_SINGLE || JAEBAL_PREFIX.any { next.startsWith(it) }

    /**
     * 병·사고가 다시 생긴다는 뜻의 '재발' 뒤에 오는 말. 모델이 드물게 본 문맥이라도 이 말이 뒤에 오면 안 고친다.
     *
     * - 말뭉치에서 '재발' 뒤에 가장 많이 온 말(방지·가능성·위험·우려·시·확률 …)과 병원에서 쓰는 말(진단·검사·판정·환자 …).
     * - **'재발하다·재발되다' 를 띄어 쓴 꼴**('재발 할 수 있습니다', '재발 된 것'). 모델이 '제발 해 줘' 와 헷갈려 '제발 할 수' 로 바꿨다.
     *   '제발 해·하지·한 번' 처럼 부탁에 쓰는 꼴은 넣지 않았다 — 그쪽은 모델이 가린다.
     * - **'재발이다·재발 없다' 를 띄어 쓴 꼴**('세 번째 재발 이야', '수술 후에 재발 없었어'). '제발' 은 어찌씨라 '이다·없다' 를 받지 않는다
     *   ('제발 없어져' 처럼 부탁하는 꼴은 넣지 않았다).
     */
    private val JAEBAL_PREFIX = listOf(
        "방지", "가능성", "위험", "우려", "여부", "없이", "걱정", "소식", "판정", "진단", "검사", "확률", "예방", "환자", "사례",
        "이력", "억제", "징후", "대책", "막으", "막기", "막는", "막을", "률", "율",
        "되", "된", "될", "됐", "됨", "했", "하였", "한다", "하는", "할", "함", "하기", "하여", "하면",
        "이야", "이라", "이래", "이었", "이에", "이예", "이네", "이다", "이지", "인데", "인가", "인지", "일까", "일지", "였",
        "없었", "없는", "없음", "없다", "없고", "없으", "없을", "없어요", "없어서"
    )
    private val JAEBAL_SINGLE = setOf("시", "후", "전", "때", "땐", "인", "임")
}

/** 애기(0, 아기) ↔ 얘기(1, 이야기). */
object Yaegi : TypoPair("yaegi", "애기", "얘기") {
    override fun classOf(token: String): Int? = when {
        token.startsWith("애기") -> 0
        token.startsWith("얘기") -> 1
        else -> null
    }
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

    /** 낳→낫 만. 뒤까지 보는 모델이 '더' 를 보면 낫다 쪽으로 쏠린다('아이를 더 낳을까' → '나을까'). */
    override fun crossCheck(from: Int): Boolean = from == 1

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
