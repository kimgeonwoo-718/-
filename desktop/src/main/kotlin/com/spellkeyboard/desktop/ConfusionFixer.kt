package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.correct.TypoFixer
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * 한국 사람이 **늘 틀리는 몇 쌍**만 골라 잡는 맞춤법 교정기.
 *
 * ## 무엇을 안 하나
 *
 * 오타를 일반적으로 고치지 않는다. 자모를 하나씩 바꿔 가며 후보를 만들고 언어모델 점수로
 * 고르는 길은 **재 봤고, 손해였다**. dev 145행에서 여백 2/4/6/8 어느 값으로 재도 전체 F1이
 * 0.9528 → 0.72~0.91 로 떨어졌고, `spelling` 은 한 번도 올라가지 않았다. 까닭은 이 자리가
 * 받는 글에 있다 — [CorrectionEngine.applyTypoFixer] 는 띄어쓰기가 **이미 끝난** 글을 받는다.
 * 그래서 "언어모델이 모르는 어절" 은 대개 오타가 아니라 띄어쓰기가 잘못 갈라 놓은 조각이다
 * (`자른 지한 달` 의 `지한`, `응시 료의` 의 `료의`). 그 조각의 음절을 바꾸면 고칠 수 있던
 * 띄어쓰기 오류가 **되돌릴 수 없는 음절 오류로 굳는다.**
 *
 * ## 무엇을 하나
 *
 * 헷갈리는 **혼동쌍**을 손으로 적어 두고, 그 자리에 온 낱말이 짝 중
 * 어느 쪽이어야 하는지만 가린다. 잡는 범위는 좁지만 **오작동이 사실상 없다.** 이 자리에서는
 * 그 편이 훨씬 값지다: `mutated` 는 양날이라, 맞는 글의 음절을 하나 바꾸면 그 줄은 F1 0점이
 * 된다. 놓친 오타 한 개보다 멀쩡한 문장 하나를 망가뜨리는 쪽이 훨씬 나쁘다.
 *
 * 혼동쌍은 표면형만 보고 걸리므로 **띄어쓰기가 만든 조각에는 애초에 걸리지 않는다** —
 * `지한`, `료의`, `거장이나` 는 `되요` 도 `웬지` 도 `어떡해` 도 아니다. 위에 적은 함정을
 * 구조적으로 비껴간다.
 *
 * ## 판단 근거를 세 등급으로 나눈다
 *
 * 1. **문법으로 결정되는 것** — `되요`(되+요는 어미가 붙을 자리가 없다), `왠`(왠지 말고는
 *    없다), `않`(어미 없이 홀로 못 선다), `-십시요`. 언어모델을 아예 안 본다. 실제로
 *    언어모델은 `되서`를 `돼서`보다, `금새`를 `금세`보다 높게 준다 — 물어보면 손해다.
 * 2. **형태소로 결정되는 것** — `-지` 뒤에 올 수 있는 보조용언은 않-/못하-/말- 뿐이라
 *    `고프지 안아서`는 `않아서`다. 앞 어절의 `지`가 어미인지([Spacer.edgeTags] 의 꼬리가
 *    EC/EF) 이름씨의 일부인지(`먼지`, `바지`)를 사전에 물어 가른다.
 * 3. **뜻으로만 갈리는 것** — `-로서/-로써` 하나가 남았는데 **기본으로 꺼 두었다.**
 *    문법으로는 둘 다 서고 어느 쪽인지는 쓴 사람만 아는 짝이라, 말뭉치 빈도로 정하면
 *    맞게 쓴 글을 뒤집는다. 실측 2/15. 자세한 것은 [Tuning.semanticRules] 에 있다.
 *    여백으로 재는 방식은 아래 [roseoRosseo] 아래의 주석에 적어 둔 까닭으로 다 걷어냈다.
 *
 * 그래서 지금 실제로 도는 것은 **1등급과 2등급, 곧 문법과 형태소로 결정되는 것뿐이다.**
 * 언어모델에 뜻을 물어보는 자리는 하나도 없다.
 *
 * ## 공백은 못 건드린다
 *
 * [CorrectionEngine.applyTypoFixer] 는 공백 수가 달라진 결과를 **말없이 버린다.** 그래서
 * `않갔어요 → 안 갔어요` 처럼 띄어야 맞는 것은 `안갔어요` 까지만 간다. 다행히 corpus 의
 * spelling 24행(dev 12 + test 12)은 전부 입력과 정답의 어절 수가 같아 이 제약이 아무것도
 * 앗아가지 않는다. 실제로 재 봤다: 글 875개에 걸어 어절 수가 달라진 적이 **0번**이다.
 *
 * ## 재 본 값
 *
 * dev 145행, 출하본(= [NBestCorrector] 만) 대 이것을 얹은 것:
 *
 * ```
 *              전체 F1   exact     damaged  mutated   spelling F1  spelling exact
 * 출하본        0.9528   104/145   2/69     3/145     0.7500       9/12
 * + 이 교정기   0.9695   107/145   2/69     0/145     1.0000       12/12
 * ```
 *
 * daily·work·news·chat·numeric·compound·ambiguous·clean **여덟 갈래는 글자 하나 안 달라졌다.**
 * `spelling` 은 천장(12/12)을 쳤고, 손상은 늘지 않았으며, `mutated` 는 오히려 3행에서
 * 0행으로 줄었다 — 예전에 음절을 틀리게 바꾸던 세 행이 이제 정답과 똑같아졌기 때문이다.
 *
 * 정밀도는 따로 쟀다. **맞은 꼴을 쓴 문장 988개(어절 4,400여 개)에 한 번도 안 걸렸다:**
 * 혼동쌍을 일부러 넣은 문장 314개, 그것을 출하본에 한 번 태운 것 314개, dev 정답 145개,
 * 뜻으로만 갈리는 짝을 노린 문장 70개, 그리고 **dev 입력에 대한 출하본의 출력 145개**
 * (여기에는 띄어쓰기가 잘못 갈라 놓은 조각이 그대로 들어 있다 — 자유 후보 탐색 교정기가
 * 망가뜨린 자리가 전부 이것이었다). 걸린 세 번은 전부 고쳐야 할 자리였다.
 *
 * 재현율은 틀린 꼴 50문장에서 출하본 29 → 49 다.
 *
 * ## 쓰는 법
 * ```kotlin
 * val correct: (String) -> String = ConfusionFixer.create(cacheDir)
 * ```
 *
 * **스레드**: [NBestCorrector] 와 같다. 하나에서만 부르라.
 */
class ConfusionFixer(
    private val spacer: Spacer,
    private val lm: LanguageModel,
    private val tuning: Tuning = Tuning(),
) : TypoFixer {

    /**
     * 손으로 맞춘 값은 이것뿐이다. 문법으로 결정되는 짝은 이 값을 아예 안 본다.
     *
     * 자유롭게 후보를 만들어 언어모델 여백으로 고르는 길은 dev 의 맞는 어절 1033개 위에서
     * 여백 2 에 3.6%, 4 에 0.6% 가 오작동한다. 맞춤법 교정기가 맞는 글을 100번에 한 번
     * 망가뜨리면 아무도 안 켠다. 그래서 그 길을 안 가고, 남은 한 자리(로서/로써)에서도
     * 여백 대신 **"바꿀 쪽은 말뭉치가 알고 원래 쪽은 모른다"** 는 더 센 조건을 쓴다.
     */
    data class Tuning(
        /** 바꿔 넣을 쪽이 적어도 이만큼은 흔해야 한다. 말뭉치에 한두 번 스친 것으로는 모자란다. */
        val minKnownCount: Float = 2.0f,
        /**
         * 뜻으로만 갈리는 규칙([roseoRosseo])을 켜는 스위치. **기본은 꺼짐이다.**
         *
         * 켜고 재 보니 맞게 쓴 `-로써` 문장 15개 중 2개를 뒤집었다:
         *
         *     대화로써 풀어야 한다   → 대화로서 풀어야 한다
         *     연구로써 증명해야 한다 → 연구로서 증명해야 한다
         *
         * 까닭은 고칠 수 있는 종류가 아니다. `대화로서` 도 `대화로써` 도 문법으로는 둘 다
         * 서고, 어느 쪽인지는 **쓴 사람만 안다.** 말뭉치 빈도로 정하면 흔한 쪽으로 쏠릴
         * 뿐이다 — 규칙 스스로도 "모르는 어절의 4.4% 가 멀쩡한 말" 이라고 적어 두었다.
         *
         * 말뭉치(dev·test 290행)에 이 짝이 **한 건도 없다.** 점수에 보태는 것은 0이고
         * 무는 것은 맞는 글이라, 끄는 쪽이 어느 방향으로도 손해가 없다.
         */
        val semanticRules: Boolean = false,
    )

    /** 어느 규칙이 몇 번 걸렸나. 시험에서만 읽는다. */
    val fired = LinkedHashMap<String, Int>()

    override fun fix(text: String): String? {
        // 공백을 버리지 않고 자른다. 원문의 줄바꿈·들여쓰기를 그대로 살려야 어절 수가 같다.
        val pieces = SPLIT.findAll(text).map { it.value }.toMutableList()
        val wordAt = pieces.indices.filter { pieces[it].isNotBlank() }
        if (wordAt.isEmpty()) return null

        // 부호를 떼어 낸 한글 몸통만 규칙에 넘긴다. '되요.' 의 몸통은 '되요' 다.
        val body = wordAt.map { core(pieces[it]) }
        val lead = wordAt.mapIndexed { k, at -> pieces[at].substring(0, headOf(pieces[at])) }
        val tail = wordAt.mapIndexed { k, at -> pieces[at].substring(headOf(pieces[at]) + body[k].length) }

        val out = body.toMutableList()
        val done = BooleanArray(body.size)

        // 짝을 이룬 `-든지 -든지` 는 어절 하나만 봐서는 못 가린다. 먼저 훑는다.
        coordinatedDeunji(out, done)

        // '-든'이 한 마디에 하나뿐인가. 둘이면 고름('가든 말든')이라 관형사형 규칙을 끈다.
        val loneDeun = out.count { it.endsWith("든") } == 1

        for (k in out.indices) {
            if (done[k]) continue
            val fixedWord = fixWord(out, k, loneDeun) ?: continue
            if (fixedWord == out[k]) continue
            out[k] = fixedWord
            done[k] = true
        }
        if (out == body) return null

        for (k in out.indices) pieces[wordAt[k]] = lead[k] + out[k] + tail[k]
        return pieces.joinToString("")
    }

    // -------------------------------------------------------------------------
    // 1등급: 문법만으로 결정된다. 언어모델을 안 본다.
    // -------------------------------------------------------------------------

    private fun fixWord(words: List<String>, k: Int, loneDeun: Boolean): String? {
        val w = words[k]
        // '않' 한 글자는 어미가 없으니 낱말이 아니다. 길이 검사보다 먼저 잡는다 —
        // 형태소 사전은 이것을 버젓이 이름씨(NNG)로 읽어 주므로 사전에 물어서는 못 가린다.
        if (w == "않") { hit("안/않"); return "안" }
        // `왠 사람` 처럼 한 글자로 서는 것도 있다. 웬/왠은 길이 검사 앞에 둔다.
        waenWen(w)?.let { hit("웬/왠"); return it }
        if (w.length < 2) return null
        val prev = words.getOrNull(k - 1)
        val next = words.getOrNull(k + 1)
        val after = words.getOrNull(k + 2)

        doeDwae(w, prev)?.let { hit("되/돼"); return it }
        anAnh(w, prev)?.let { hit("안/않"); return it }
        fixedShapes(w)?.let { hit("굳은꼴"); return it }
        eotteoke(w, next)?.let { hit("어떻게/어떡해"); return it }
        geumse(w, next, after)?.let { hit("금새/금세"); return it }
        baram(w, prev)?.let { hit("바램/바람"); return it }
        deonRelative(w, next, loneDeun)?.let { hit("든/던"); return it }
        machuda(w, prev)?.let { hit("맞추/맞히"); return it }
        euromsseo(w)?.let { hit("ㅁ으로써"); return it }

        if (!tuning.semanticRules) return null

        // -------------------------------------------------------------------
        // 3등급: 뜻으로만 갈린다. 여기서만 언어모델에 물어본다.
        // -------------------------------------------------------------------
        roseoRosseo(w, prev)?.let { hit("로서/로써"); return it }
        return null
    }

    /**
     * 되/돼. **돼는 '되어'의 준말이다** — 그 자리에 '되어'를 넣어 말이 되면 돼, 안 되면 되.
     *
     * 언어모델은 여기서 도움이 안 된다. dev+test 오타 어절 위에서 재 보니 말뭉치가
     * `되서`(틀림)를 `돼서`(맞음)보다 높게 매긴다. 사람이 하도 많이 틀려서 말뭉치에
     * 틀린 쪽이 더 많이 들어갔다. 그러니 문법으로만 간다.
     */
    private fun doeDwae(w: String, prev: String?): String? {
        // '되 + 요'. 어간에 보조사 '요'가 바로 붙을 수 없다. '되어요 → 돼요' 뿐이다.
        // 다만 '되'는 부피 단위이기도 하다 — '쌀 두 되요' 를 피하려고 수관형사 뒤는 뺀다.
        if (w.endsWith("되요") && prev !in COUNTERS) return w.dropLast(2) + "돼요"
        // '되 + 서' 도 같다. '되어서 → 돼서'.
        if (w.endsWith("되서")) return w.dropLast(2) + "돼서"
        // '됬' 은 한글 맞춤법에 없는 음절이다. '되었 → 됐' 이 유일하다.
        if (w.contains("됬")) return w.replace("됬", "됐")
        // 거꾸로, '되어고/되어면' 같은 말은 없으므로 그 자리의 돼는 되로 되돌린다.
        // 뒤 음절에 '지'를 넣지 않은 것은 **돼지(짐승)** 때문이다. '돼지만'만 따로 받는다.
        val back = DWAE_TOO_FAR.replace(w, "되")
        if (back != w) return back
        return null
    }

    /**
     * 안/않. `안`은 부사고 `않-`은 용언(아니하-)이라 **어미 없이 홀로 설 수 없다.**
     *
     * 뒤집힌 쪽(`-지 안아서`)은 형태소를 봐야 한다. `-지` 뒤에 오는 보조용언은 않-/못하-/말-
     * 셋뿐이라, 앞 어절이 어미 `-지`로 끝났으면 뒤의 `안-`도 `앉-`도 있을 수 없다.
     * 앞 어절의 `지`가 어미인지는 사전에 묻는다 — `먼지 앉은 책` 의 `먼지`는 이름씨라
     * 꼬리 태그가 NNG 로 나오고, 그래서 걸리지 않는다.
     *
     * 이 규칙은 엔진이 스스로 내는 오류도 되잡는다: 지금 엔진은 `고프지 안아서` 를
     * 말뭉치 빈도만 보고 `고프지 앉아서` 로 바꿔 놓는다(`앉아서` 8.375 > `않아서` 8.125).
     * 문법으로 막을 수 있는 것을 확률로 재면 이렇게 진다.
     */
    private fun anAnh(w: String, prev: String?): String? {
        // '않돼', '않된다' 처럼 어미가 안 붙었으면 그것은 부사 '안'이다. 사전은 `않돼` 도
        // 분석해 내므로(않/NNG + 되/XSV) **분석이 되느냐**가 아니라 **머리가 용언이냐**를 본다:
        // 진짜 '않-'은 `않고`·`않아서`·`않았다` 모두 머리 태그가 VV 로 나온다.
        if (w.startsWith("않") && spacer.edgeTags(w)?.first?.let { !spacer.isVerbTag(it) } == true) {
            val alt = "안" + w.substring(1)
            if (spacer.edgeTags(alt)?.first?.let { spacer.isVerbTag(it) } == true) return alt
        }
        if (prev == null || prev.length < 2 || !prev.endsWith("지")) return null
        val prevTail = spacer.edgeTags(prev)?.second ?: return null
        if (prevTail != "EC" && prevTail != "EF") return null
        if (!AN_STEM.containsMatchIn(w)) return null
        val alt = "않" + w.substring(1)
        return if (lm.lnCount(alt) != null) alt else null
    }

    /**
     * 웬/왠. **`왠`은 `왠지` 하나에만 산다** (왜인지의 준말). 나머지는 전부 `웬`이다 —
     * 웬일, 웬만하다, 웬걸, 웬 사람.
     */
    private fun waenWen(w: String): String? {
        if (w == "웬지") return "왠지"
        val fixed = WAEN_ALONE.replace(w, "웬")
        return if (fixed != w) fixed else null
    }

    /**
     * 짝의 한쪽이 **어느 자리에서도 맞은 적이 없는** 것들. 문맥을 볼 것이 없다.
     *
     * - `몇일`: 며칠은 '몇'+'일'의 합성이 아니라 굳은 낱말이라 소리대로 적는다.
     * - `-십시요`: 하십시오체의 종결어미는 `-오`다. `-요`는 보조사라 여기 못 온다.
     * - `같애`: 어간 `같-` + 어미 `-아`. `-애`라는 어미는 없다.
     */
    private fun fixedShapes(w: String): String? {
        if (w.contains("몇일")) return w.replace("몇일", "며칠")
        if (w.endsWith("십시요")) return w.dropLast(3) + "십시오"
        if (w.contains("같애")) return w.replace("같애", "같아")
        return null
    }

    /**
     * 어떻게/어떡해. **`어떡해`는 그 자체로 서술어**('어떻게 해'의 준말)라 **뒤에 용언을
     * 또 달 수 없다.** `어떡해 해결해야` 가 말이 안 되는 까닭이 그것이다.
     *
     * 뒤 어절이 용언 꼴인지는 [Spacer.edgeTags] 의 꼬리가 어미(E로 시작)인지로 본다 —
     * `해결해야` 는 첫 형태소가 이름씨(해결/NNG)라 머리 태그로는 못 가린다.
     *
     * 반대 방향(문장 끝의 `어떻게` → `어떡해`)은 안 한다. '그건 어떻게?' 처럼 부사로
     * 끝나는 물음이 멀쩡히 있어서 가릴 근거가 없다.
     *
     * 말뭉치도 같은 말을 한다: `어떻게 해결해야` 4.0 대 `어떡해 해결해야` 없음.
     * 문법이 이미 답을 냈으니 이건 확인일 뿐이지만, 확인이 공짜면 받는다.
     */
    private fun eotteoke(w: String, next: String?): String? {
        // `어떻해` 는 [SpellingRules.WORD] 가 이 자리에 오기 전에 `어떡해` 로 바꿔 놓으므로
        // 실제로는 아래 `어떡해` 쪽만 탄다. 그래도 같이 받아 둔다 — 규칙 표가 바뀌어도
        // 이 교정기가 혼자 옳게 돌아야 한다.
        if (w != "어떡해" && w != "어떻해") return null
        if (next == null || !isPredicate(next)) return null
        val good = lm.lnBigramCount("어떻게", next) ?: return null
        val bad = lm.lnBigramCount("어떡해", next)
        return if (bad == null || good > bad) "어떻게" else null
    }

    /**
     * 금새/금세. `금세`는 '금시(今時)에'가 줄어든 부사고, `금새`는 '물건 값'이라는 딴
     * 이름씨다. 둘 다 있는 말이라 **뒤에 용언이 오는 자리에서만** 부사로 본다.
     *
     * 말뭉치는 여기서도 틀린 쪽(`금새`)을 더 높게 준다. 물어보지 않는다.
     */
    private fun geumse(w: String, next: String?, after: String?): String? {
        // 조사가 붙었으면 이름씨다 — `금새가 올랐다`. 맨 `금새` 만 부사로 본다. 그래서
        // 뒤 두 어절까지 넓혀 봐도 안전하다: `금새 다 먹었다` 의 `다` 를 넘어갈 수 있다.
        if (w != "금새") return null
        return if (listOfNotNull(next, after).any { isPredicate(it) }) "금세" else null
    }

    /**
     * 바램/바람. 소망은 `바라다`에서 온 `바람`이다. `바램`은 `바래다`(빛이 날아가다)의
     * 이름꼴이라 실제로 있는 말이긴 하다 — 그래서 **소망 자리에 오는 꼬리**일 때만 고치고,
     * 앞 어절이 빛깔을 말하면(색이 바램) 손대지 않는다.
     */
    private fun baram(w: String, prev: String?): String? {
        if (!w.startsWith("바램")) return null
        if (prev in FADING) return null
        val rest = w.substring(2)
        return if (rest.isEmpty() || rest in WISH_TAILS) "바람$rest" else null
    }

    /**
     * `-든` / `-던`. **`-던`은 관형사형 어미**라 뒤에 이름씨가 오고, **`-든`은 연결어미**라
     * 뒤에 마디가 온다. 그래서 `있든 사람` 은 `있던 사람` 이다.
     *
     * `누구든 오세요`·`언제든 연락`·`밥이든 국이든` 이 안 걸리는 까닭: `든`이 용언 어간이
     * 아니라 이름씨에 붙은 것이라 [Spacer.edgeTags] 의 **머리 태그가 NP/NNG** 로 나온다.
     * 이름씨에 붙은 `-든`은 애초에 `-던`이 될 수 없으니, 머리가 용언일 때만 본다.
     */
    private fun deonRelative(w: String, next: String?, alone: Boolean): String? {
        if (!w.endsWith("든") || w.length < 2 || next == null) return null
        // 짝이 있으면 고름의 '-든'이다: '비가 오든 눈이 오든'. 한 마디에 '-든'이 하나뿐일 때만 본다.
        if (!alone) return null
        // '-든'이 **용언 어간**에 붙었을 때만. `누구든`·`언제든`·`밥이든` 은 머리가 체언(NP/NNG)
        // 이라 여기서 걸러진다 — 이름씨에 붙은 보조사 '-든'은 애초에 '-던'이 될 수 없다.
        val head = spacer.edgeTags(w)?.first ?: return null
        if (!spacer.isVerbTag(head)) return null
        val nextTags = spacer.edgeTags(next) ?: return null
        if (nextTags.second.startsWith("E")) return null // 뒤가 용언이면 연결어미 '-든'이 맞다
        if (next.endsWith("든") || next.endsWith("던")) return null
        if (!spacer.isNominalTag(nextTags.first)) return null
        // 바이그램은 여기서 못 쓴다 — `먹던 빵이` 도 `먹든 빵이` 도 말뭉치에 없어 둘 다 null 이다.
        // 대신 활용형 자체의 흔함을 견준다. `먹던` 5.0 > `먹든` 4.0, `있던` 9.125 > `있든` 6.25.
        val alt = w.dropLast(1) + "던"
        val good = lm.lnCount(alt) ?: return null
        val bad = lm.lnCount(w)
        return if (bad == null || good > bad) alt else null
    }

    /**
     * 짝을 이룬 `-던지 … -던지` 는 고름의 `-든지`다.
     *
     * 회상의 `-던지`는 짝으로 오지 않는다 — '얼마나 춥던지' 하나로 끝난다. 되풀이되는
     * 경우('얼마나 춥던지 얼마나 힘들던지')는 앞에 감탄 부사가 붙으므로 그것으로 가른다.
     *
     * 꼬리가 어미인지는 [Spacer.edgeTags] 의 **꼬리 태그**로 본다. [Spacer.tagAt] 을 쓰면
     * `말던지` 를 놓친다 — 사전이 '말'을 이름씨로 읽고 `이(VCP)+던지` 를 붙이는 바람에
     * 마지막에서 둘째 음절의 태그가 VCP 로 나온다.
     */
    private fun coordinatedDeunji(words: MutableList<String>, done: BooleanArray) {
        for (k in words.indices) {
            val a = words[k]
            if (!a.endsWith("든지") && !a.endsWith("던지")) continue
            val at = (k + 1..k + 2).firstOrNull { j ->
                val b = words.getOrNull(j) ?: return@firstOrNull false
                b.endsWith("든지") || b.endsWith("던지")
            } ?: continue
            val b = words[at]
            if (!a.endsWith("던지") && !b.endsWith("던지")) continue
            if ((k - 2..at).any { words.getOrNull(it) in EXCLAIM }) continue
            if (!endsWithEnding(a) || !endsWithEnding(b)) continue
            if (a.endsWith("던지")) { words[k] = a.dropLast(2) + "든지"; done[k] = true; hit("든지/던지") }
            if (b.endsWith("던지")) { words[at] = b.dropLast(2) + "든지"; done[at] = true; hit("든지/던지") }
        }
    }

    /**
     * 맞추다/맞히다. 사전 뜻으로는 `맞히다`가 '적중시키다', `맞추다`가 '나란히 대어 보다'인데,
     * `답을 맞추다`(남의 답과 대어 보다)도 멀쩡한 말이라 **목적어만으로는 못 가리는 것이
     * 대부분이다.** 그래서 한쪽으로만 읽히는 목적어에서만 고친다 — 과녁은 대어 보는 것이
     * 아니고, 시간은 적중시키는 것이 아니다.
     */
    private fun machuda(w: String, prev: String?): String? {
        if (prev == null) return null
        if (prev in HIT_OBJECTS) {
            val fixed = CHU_TO_HI.entries.firstOrNull { w.startsWith(it.key) } ?: return null
            return fixed.value + w.substring(fixed.key.length)
        }
        if (prev in FIT_OBJECTS) {
            val fixed = HI_TO_CHU.entries.firstOrNull { w.startsWith(it.key) } ?: return null
            return fixed.value + w.substring(fixed.key.length)
        }
        return null
    }

    /**
     * `-ㅁ으로서` → `-ㅁ으로써`. 용언의 이름꼴 `-(으)ㅁ` 뒤에 오는 것은 수단의 `으로써`다.
     * 자격의 `으로서`는 이름씨에만 붙는다. 앞 음절의 받침이 ㅁ인지만 보면 되므로 문법이다.
     */
    private fun euromsseo(w: String): String? {
        if (!w.endsWith("으로서") || w.length < 4) return null
        val stem = w[w.length - 4]
        val jong = Hangul.decompose(stem)?.third ?: return null
        if (Hangul.JONGSEONG.getOrNull(jong) != 'ㅁ') return null
        return w.dropLast(1) + "써"
    }

    // -------------------------------------------------------------------------
    // 3등급: 뜻으로만 갈린다. 여기 하나만 남았다.
    // -------------------------------------------------------------------------

    /**
     * -로서(자격) / -로써(수단). 앞에 오는 이름씨가 정하는데 그 이름씨를 목록으로 적을 수는
     * 없다. 그래서 어절 통째로 말뭉치에 묻는다 — **쓴 쪽은 본 적 없고 바꿀 쪽은 흔할 때만**
     * 바꾼다. 한쪽만 아는 것으로는 모자란다는 것이 재 본 결과다(모르는 어절의 4.4%가
     * 멀쩡한 말이었다).
     */
    private fun roseoRosseo(w: String, prev: String?): String? {
        val alt = when {
            w.contains("로서") -> w.replace("로서", "로써")
            w.contains("로써") -> w.replace("로써", "로서")
            else -> return null
        }
        if (lm.lnCount(w) != null) return null
        if ((lm.lnCount(alt) ?: return null) < tuning.minKnownCount) return null
        // 앞이 이름씨여야 조사 자리다. 띄어쓰기가 만든 조각 옆에서는 아무것도 안 한다.
        if (prev != null && !spacer.isWellFormed(prev)) return null
        return alt
    }

    /*
     * 여기 **없는** 두 쌍에 대하여 — 짓다가 재 보고 도로 걷어냈다. 다시 넣으려는 사람을
     * 위해 그 까닭을 적어 둔다.
     *
     * **낫다/낮다**: 활용꼴이 통째로 겹쳐서(`낫고`·`낮고`, `나아`·`낮아`) 형태소로는 한
     * 글자도 못 가린다. 남는 것은 앞 어절과의 바이그램뿐인데, 안전한 여백(2.5 nat)에서는
     * 일부러 만든 틀린 문장 4개에 **한 번도 안 걸렸다.** 걸리게 하려고 여백을 내리면
     * 그것은 곧 자유 후보 탐색 + 언어모델 여백이고, 그 길은 dev 에서 여백 2/4/6/8 모두
     * 손해로 판명됐다. 재현율 0인 규칙은 위험만 지고 있는 것이라 뺐다.
     *
     * **있다가/이따가**: 같은 이유다. `이따가 다시`·`이따가 점심때쯤` 같은 바이그램이
     * 말뭉치에 아예 없어 여백을 잴 수가 없다. 틀린 문장 3개에 0번 걸렸다.
     *
     * **났다**도 뺐다. `병이 낫다`도 `병이 났다`도 맞는 말이라 가릴 근거 자체가 없다.
     * (지금 엔진은 `병이 낫고` 를 `병이 났고` 로 바꿔 놓는데, 이건 [ContextCorrector]
     * 쪽 문제라 이 자리에서 되돌리려면 같은 근거 없는 판단을 반대로 해야 한다.)
     */

    // -------------------------------------------------------------------------
    // 잔심부름
    // -------------------------------------------------------------------------

    /** 어절이 용언 꼴인가. 머리 태그로는 못 본다 — `해결해야` 의 머리는 이름씨다. */
    private fun isPredicate(word: String): Boolean =
        spacer.edgeTags(word)?.second?.startsWith("E") == true

    private fun endsWithEnding(word: String): Boolean =
        spacer.edgeTags(word)?.second?.let { it == "EC" || it == "EF" } == true

    private fun hit(rule: String) {
        fired[rule] = (fired[rule] ?: 0) + 1
    }

    /** 앞뒤 부호를 뺀 한글 몸통. 없으면 빈 글자열이라 어떤 규칙에도 안 걸린다. */
    private fun core(token: String): String {
        val from = headOf(token)
        if (from == token.length) return ""
        var to = token.length
        while (to > from && !token[to - 1].isHangul()) to--
        return token.substring(from, to)
    }

    private fun headOf(token: String): Int {
        var from = 0
        while (from < token.length && !token[from].isHangul()) from++
        return from
    }

    private fun Char.isHangul() = this in '가'..'힣'

    companion object {
        /** 공백을 버리지 않고 어절을 가른다. */
        private val SPLIT = Regex("\\S+|\\s+")

        /** '되어'로 풀 수 없는 어미들. `지`는 **돼지** 때문에 뺐고 `지만`만 따로 받는다. */
        private val DWAE_TOO_FAR = Regex("돼(?=[고면는니며게기려자든었더겠세십]|지만)")

        /** `-지` 뒤에 왔다면 보조용언 자리다. `안-`도 `앉-`도 못 온다. */
        private val AN_STEM = Regex("^[안앉][아어았었은을는고게다지으겠네나더]")

        /** `왠`은 `왠지` 말고는 없다. */
        private val WAEN_ALONE = Regex("왠(?!지)")

        /** '되'는 부피 단위이기도 하다. 수관형사 뒤의 `되요`는 건드리지 않는다. */
        private val COUNTERS = setOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "몇")

        /** 빛깔이 앞에 오면 `바램`은 바래다의 이름꼴이다. */
        private val FADING = setOf("색", "색이", "빛", "빛이", "색깔", "색깔이", "물", "물이")

        /** 소망의 `바람`이 달고 다니는 꼬리. */
        private val WISH_TAILS = setOf("대로", "이", "은", "도", "과", "을", "이다", "입니다", "이었다", "처럼")

        /** 회상의 `-던지`를 이끄는 감탄 부사. 여기 걸리면 짝으로 안 본다. */
        private val EXCLAIM = setOf("얼마나", "어찌나", "어쩌나", "하도", "어찌", "얼마")

        /** 대어 볼 수 없는 것들. 이것들은 맞히는 것이다. */
        private val HIT_OBJECTS = setOf("과녁을", "과녁도", "화살을", "목표물을", "표적을", "급소를")

        /** 적중시킬 수 없는 것들. 이것들은 맞추는 것이다. */
        private val FIT_OBJECTS = setOf("시간을", "일정을", "속도를", "초점을", "간격을", "박자를", "장단을")

        private val CHU_TO_HI = linkedMapOf(
            "맞췄" to "맞혔", "맞춰" to "맞혀", "맞추" to "맞히", "맞춘" to "맞힌", "맞출" to "맞힐", "맞춥" to "맞힙",
        )
        private val HI_TO_CHU = linkedMapOf(
            "맞혔" to "맞췄", "맞혀" to "맞춰", "맞히" to "맞추", "맞힌" to "맞춘", "맞힐" to "맞출", "맞힙" to "맞춥",
        )

        /**
         * 출하본과 **똑같이** 배선하고 이 교정기만 얹는다. [NBestCorrector] 를 끄지 않는다 —
         * 맞춤법과 띄어쓰기는 같이 돌아야 한다.
         */
        fun create(cacheDir: File, tuning: Tuning = Tuning()): (String) -> String {
            val spacer = Spacer(SpacingDictionary.open(cacheDir))
            return create(spacer, LanguageModel.open(cacheDir), tuning)
        }

        /** 이미 열어 둔 사전으로 만든다. */
        fun create(spacer: Spacer, lm: LanguageModel, tuning: Tuning = Tuning()): (String) -> String {
            val context = ContextCorrector(lm, spacer)
            val engine = CorrectionEngine()
            engine.spacer = spacer
            engine.speller = Speller(spacer)
            engine.context = context
            engine.typoFixer = ConfusionFixer(spacer, lm, tuning)
            val corrector = NBestCorrector(engine, spacer, lm)
            return { text -> corrector.correct(text) }
        }
    }
}
