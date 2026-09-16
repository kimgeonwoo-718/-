package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.correct.CorrectionResult
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.LongSpacer
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * # N-best 띄어쓰기 — 여러 갈래를 만들어 **문장 통째로** 물어보고 고른다
 *
 * ## 왜 이것이 필요한가
 *
 * 지금 엔진은 붙여 쓴 덩어리를 만나면 `Spacer.spaceLong` 이 내놓는 **한 갈래**를 그대로
 * 받아 적는다. 그 한 갈래는 형태소 사전의 비용만 보고 고른 것이라, 한국어로 말이 되는지는
 * 아무도 안 물어본다. 그래서 이런 일이 벌어진다.
 *
 * ```
 * 아버지가방에들어가신다   → 아버지 가방에 들어가신다      (엔진)
 * 아버지가방에 들어가신다   → 아버지가 방에 들어가신다      (공백 하나 넣었더니 뒤집힌다)
 * ```
 *
 * 뒤집히는 까닭은 간단하다. 옳은 답은 **닿을 수 있는 곳에 있는데 점수를 매겨 본 적이 없다.**
 * 그래서 이 파일이 하는 일은 하나다 — **묻는다.**
 *
 * 1. 엔진이 내놓은 답에서 **모르는 어절**이 있는 자리를 찾는다 ([suspectSpans]).
 * 2. 그 둘레만 `Spacer` 의 비용 격자에서 상위 K 갈래로 다시 뽑는다 ([kBest]).
 * 3. 문장을 통째로 [LanguageModel.BOS] 부터 이어 붙여 점수를 내고 고른다 ([Rescorer]).
 *
 * ## 점수를 어떻게 매기나
 *
 * 언어모델 점수만 믿으면 **더 나빠진다.** 재 봤다. 그래서 세 가지를 더한다.
 *
 * - **언어모델 연쇄** `Σ lnConditional(앞 어절, 어절)`. 모르는 어절은 길이에 비례해 벌한다.
 * - **형태소 사전** ([splitScore]). `Spacer` 가 최소로 만드는 바로 그 값을 밖에서 그대로
 *   다시 계산해 쓴다. 이것이 없으면 언어모델이 `산책시키다가` 를 `산책 시키다가` 로,
 *   `깊어지면서` 를 `깊어지면 서` 로 찢는다 — 조각마다 말뭉치에 흔하니 점수가 잘 나온다.
 * - **문법** ([boundCuts], [grammarBreaks]). 매인 형태소가 시작하는 자리는 어절 경계가 아니다.
 *
 * 그리고 세 가지를 아예 후보에서 뺀다.
 *
 * - **사용자가 찍은 공백은 얼린다** ([plan]). 사람이 손으로 갈라 놓은 자리는 근거다.
 *   지우지 않고, 그 자리를 넘어 붙이지도 않는다. dev 145행의 사용자 공백 562개는 하나도
 *   빠짐없이 정답 공백이었다.
 * - **복합명사는 되붙이고 잠근다** ([rejoinCompounds], [Vocabulary]). `개인정보보호위원회는`
 *   은 무슨 일이 있어도 안 쪼갠다. 이건 확률 문제가 아니라 낱말을 아느냐 모르냐의 문제다 —
 *   언어모델은 이 낱말을 쪼갠 쪽을 36 nat 차이로 더 좋아한다.
 * - **후보는 Spacer 의 격자에서만 뽑는다.** 아무 자리나 흔들어 만든 후보를 주면 언어모델이
 *   `주 말에친구들이랑한강 에서` 같은 것을 만들어 낸다.
 *
 * ## 엔진에 어떻게 붙나
 *
 * [correct] 는 `engine.correctAll` 을 **감싼다.** 엔진이 먼저 제 일(맞춤법, 규칙, 문맥
 * 해독)을 다 하고, 그 결과를 받아 **띄어쓰기만** 다시 고른다. 순서를 이렇게 한 까닭은
 * 마지막에 말할 권리가 있어야 하기 때문이다 — 엔진 앞에 서면 `ContextCorrector` 가 내
 * 결정을 다시 뒤집는다. `core/` 는 한 줄도 건드리지 않는다.
 *
 * 쓰는 법:
 * ```kotlin
 * val correct: (String) -> String = NBestCorrector.create(cacheDir)
 * correct("아버지가방에들어가신다")   // → 아버지가 방에 들어가신다
 * ```
 *
 * **스레드**: 하나에서만 부르라. 안에 든 캐시가 동기화되어 있지 않다 — `CorrectionEngine`
 * 과 같은 사정이고, 데스크톱 앱은 어차피 교정을 단일 스레드 하나에 몰아 놓았다.
 */
class NBestCorrector(
    private val engine: CorrectionEngine,
    private val spacer: Spacer,
    private val lm: LanguageModel,
    private val tuning: Tuning = Tuning(),
) {

    /**
     * 손으로 맞춘 값은 이것이 전부다. dev 145행에서 정했고, 전부 굵은 눈금이다 — 소수점
     * 둘째 자리까지 맞춘 값은 하나도 없고, 어느 값이든 한 칸 움직여도 점수는 0.005 안쪽에서
     * 논다. 앞의 다섯 개가 점수고 뒤의 다섯 개는 크기 제한이다.
     */
    data class Tuning(
        /**
         * 모르는 어절의 음절당 벌점.
         *
         * 0 에 가까우면 모르는 긴 덩어리가 공짜라 아무것도 안 쪼개지고, 너무 크면 복합명사를
         * 잘게 부순다. −6 은 "말뭉치에 없는 음절 하나 ≈ 1/400" 이라는 문자 단위 모형에
         * 해당한다. 길이에 비례해야 한다 — 고정 벌점을 쓰면 모르는 긴 덩어리 하나가 모르는
         * 짧은 낱말 하나와 같은 값이 되어 `아버지|가|방에들어가신다` 같은 답이 이긴다.
         */
        val unknownPerSyllable: Float = -6f,
        /**
         * 형태소 사전의 의견에 주는 무게. 단위는 "Spacer 의 공백 한 칸(=SPACE_PENALTY)만큼의
         * 비용 차이를 몇 nat 으로 칠 것인가" 다.
         *
         * **이 값이 없으면 안 된다.** 언어모델은 말뭉치에서 본 적 없는 활용형을 어간과 어미로
         * 찢고 싶어한다: `산책시키다가 → 산책 시키다가`, `깊어지면서 → 깊어지면 서`,
         * `온다더라 → 온다 더라`. 3 보다 낮추면 이 셋이 하나씩 되살아난다 (dev 에서
         * 2 로 내리면 `온다 더라` 가, 1 로 내리면 `깊어지면 서` 가 돌아온다).
         */
        val morphWeight: Float = 3f,
        /**
         * 문법을 어긴 자리 하나당 벌점. [grammarBreaks] 참고.
         *
         * 사실상 금지에 가깝게 크게 잡았다 — 문법으로 막을 수 있는 것을 확률로 재지 않는다.
         * 6 이든 20 이든 dev 점수는 같다.
         */
        val grammarPenalty: Float = 20f,
        /**
         * 엔진이 이미 내놓은 답을 뒤집으려면 이만큼은 이겨야 한다.
         *
         * 언어모델 점수는 어절 수에 끌려다니므로 박빙일 때는 엔진(형태소 사전 + 문맥
         * 해독기)을 믿는 편이 낫다. 1 nat 는 "e배쯤 더 그럴듯할 때만 바꾼다"는 뜻이다.
         */
        val overrideMargin: Float = 1f,
        /**
         * 복합어로 볼 최대 조각 수. [Vocabulary] 참고.
         *
         * 2 에서 3 으로 올리면 dev 의 손상이 1행에서 0행으로 떨어지지만(`근로자퇴직급여보장법에`),
         * 그 대신 `회의자료준비상황을`(= 회의자료|준비|상황을, 셋 다 아는 조각이고 앞 둘이
         * 체언으로 끝난다)이 붙은 채로 굳는다. dev 에는 없고 실제로는 있는 말이라 2 로 둔다.
         */
        val compoundChunks: Int = 2,
        /**
         * 복합어 거부권([rejoinCompounds])을 쓸 최소 음절 수.
         *
         * **거부권이 짧은 어절에서 거꾸로 돌던 것을 막는 눈금이다.** `coverPieces` 는
         * "아는 조각 둘, 앞 조각이 체언으로 끝남" 이면 복합어로 보는데, 그것은 사람이
         * 공백 하나를 빠뜨렸을 때 생기는 모양이기도 하다: `오늘저녁에`(오늘+저녁에),
         * `확인한뒤`, `수있을까`, `두대를`, `새해복`. 배포본은 엔진이 이미 바르게 갈라 놓은
         * 이 다섯을 도로 붙여 놓았다 — A/B 조사가 센 회귀 313건 가운데 228건(73%)이
         * 이 모양이었다.
         *
         * 실제로 지켜야 하는 복합명사는 전부 6음절 이상이고(`아파트관리비가` 7,
         * `마음먹었지만` 6, `부동산중개수수료는` 9), 거꾸로 도는 것은 전부 5음절 이하다.
         * dev·test 를 3~8 로 훑어 보면 6 이 꼭짓점이다 — 7 부터 dev 손상이 2행에서
         * 3행으로, 8 에서 4행으로 는다.
         */
        val rejoinMinSyllables: Int = 6,

        /** Spacer 격자에서 뽑아 볼 갈래 수. 8 아래로 내리면 떨어지고, 위로는 평평하다. */
        val beam: Int = 12,
        /** 한 어절로 인정할 최대 음절 수. 후보 수와 `Spacer.cost` 호출 수를 함께 누른다. */
        val maxWordSyllables: Int = 10,
        /**
         * 모르는 어절을 가운데 놓고 몇 어절을 데리고 다시 풀 것인가 (= `2×이 값 + 1` 어절).
         *
         * 경계가 새어 나간 거리는 대개 한 어절이라 1 이면 닿는다. 넓히면 멀쩡한 자리까지
         * 흔들려서 dev 에서 2 는 −0.003, 3 은 −0.005 다.
         */
        val spanRadius: Int = 1,
        /** 한 번에 다시 풀 최대 음절 수. 넘으면 포기한다 — 비용도 위험도 제곱으로 는다. */
        val maxSpanSyllables: Int = 18,
    )

    private val vocabulary = Vocabulary(lm, spacer, tuning.compoundChunks)
    private val rescorer = Rescorer(lm, tuning)

    /**
     * `Spacer.cost` 값을 재 쓴다. 한 자모 단위 비터비라 한 번에 23 µs 쯤 들고, 한 자리를
     * 다시 풀 때 같은 조각을 격자 만들 때와 점수 낼 때 두 번 이상 물어본다. 크기를 512 로
     * 묶은 것은 `CorrectionEngine` 의 분석 캐시와 같은 이유다 — 긴 글을 한 번 돌리는 동안만
     * 쓸모가 있고, 무한정 들고 있을 값이 아니다.
     */
    private val costs = object : LinkedHashMap<String, Int>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Int>?) = size > COST_CACHE_SIZE
    }

    private fun cost(piece: String): Int = costs.getOrPut(piece) { spacer.cost(piece) }

    init {
        // 엔진 안의 `applyLongSplit` 을 복합어 거부권으로 감싼다. 아예 끄면 안 된다 —
        // 재 봤다. 문맥 해독기 혼자서는 긴 덩어리를 못 풀어서 dev 경계 F1 이 0.95 에서
        // 0.83 으로 무너진다. 엔진이 쪼개 준 어절이 있어야 문맥 해독이 돌아간다.
        engine.longSpacer = LongSpacer { word ->
            if (vocabulary.keepsWhole(word)) null else spacer.spaceLong(word)
        }
    }

    /**
     * 데스크톱 앱이 쓰는 전처리 + 두 번 돌리기를 그대로 두고, 맨 뒤에 재분절을 얹는다.
     *
     * 앞 두 단계는 손대지 않았다 — 숫자·부호가 섞인 어절을 푸는 전처리는 이미 검증된
     * 이득(경계 F1 85.3 → 96.1)이고, 두 번 돌리는 것은 텍스트 규칙이 넣어 준 공백 덕에
     * 두 번째 회차에서야 뒷 문장이 사전에 닿기 때문이다.
     *
     * ## 들일 때 고친 것 (1/2)
     *
     * `preSpace` 를 `hasGluedRun` 으로 **막는다.** 원래는 조건 없이 불렀는데, 그러면
     * 배포본이 손도 안 대는 글까지 손질하게 된다. `loosenMixedToken` 은 한글 뒤에 붙은
     * 숫자·영문 앞에 공백을 넣으므로, 이미 맞게 쓴 `아이폰15프로맥스를`·`코로나19`·
     * `오전10시에` 가 전부 갈라졌다 — 손으로 쓴 12문장에서 11개 손상, 배포본은 0개.
     * 말뭉치에는 이미 맞게 쓴 혼합 어절 행이 없어 dev/test 둘 다 이것을 못 본다.
     * 막아도 말뭉치 점수는 **한 글자도 안 바뀐다** (dev·test 모두 tp/fp/fn 동일).
     */
    fun correct(text: String): String = correctResult(text).text

    /**
     * [correct] 와 같은 글을 내면서 엔진이 남긴 교정 내역까지 들고 나온다.
     *
     * 창이 "몇 군데 고쳤습니다" 와 까닭별 묶음을 보여 주려면 내역이 있어야 하는데,
     * [correct] 는 글자만 돌려주므로 창 쪽에서 파이프라인을 한 번 더 돌리는 낭비가 생긴다.
     * 내역은 **엔진이 낸 것 그대로**다 — 재분절이 옮긴 공백은 여기 안 들어간다. 재분절은
     * 음절을 건드리지 않고 엔진이 이미 띄운 자리를 옮길 뿐이라, 셈에 넣으면 같은 교정이
     * 두 번 세어진다.
     */
    fun correctResult(text: String): CorrectionResult {
        val glued = engine.hasGluedRun(text)
        val pre = if (glued) preSpace(text) else text
        val first = engine.correctAll(pre)
        val second = if (glued) engine.correctAll(first.text) else null
        val engineOut = second?.text ?: first.text
        val corrections = first.corrections + (second?.corrections ?: emptyList())
        return CorrectionResult(text, respace(text, engineOut), corrections)
    }

    // ==================================================================================
    // 1) 재분절 — 수상한 자리 둘레만 다시 고른다
    // ==================================================================================

    /**
     * [engineOut] 의 띄어쓰기를 다시 고른다. **음절은 하나도 건드리지 않는다.**
     *
     * 자리 계산은 전부 '공백 뺀 음절 열' 위의 색인으로 한다 — 공백을 넣고 빼도 색인이
     * 밀리지 않아서 원문과 엔진 답을 곧바로 견줄 수 있다.
     */
    private fun respace(original: String, engineOut: String): String {
        val originalLines = original.split(NEWLINE)
        val engineLines = engineOut.split(NEWLINE)
        // 줄 수가 같으면 **줄마다 따로** 고른다. 아래 [plan] 은 맞춤법 교정으로 음절이
        // 하나라도 바뀌면 자리를 맞출 수 없어 통째로 포기하는데, 글 하나에 줄이 여럿이면
        // 엉뚱한 줄에서 난 교정 하나가 문서 전체의 재분절을 꺼 버린다 (실제로 겪었다:
        // 한국어 산문 1,500자에서 `매인`→`메인` 하나가 847줄짜리 재분절을 통째로 껐다).
        // 줄바꿈은 어차피 [applyCuts] 가 무조건 경계로 치므로, 줄을 갈라도 잃는 것이 없다.
        if (originalLines.size > 1 && originalLines.size == engineLines.size) {
            return originalLines.indices.joinToString("\n") { respaceLine(originalLines[it], engineLines[it]) }
        }
        return respaceLine(original, engineOut)
    }

    private fun respaceLine(original: String, engineOut: String): String {
        val plan = plan(original, engineOut) ?: return engineOut
        // 칸은 음절 열을 빈틈없이 덮고 칸의 경계는 늘 어절 경계다. 그러니 고른 답의 어절
        // 시작 자리를 모으면 그것이 곧 새 경계 집합이다 (얼린 자리도 여기에 들어 있다).
        val cuts = HashSet<Int>()
        for (i in plan.slots.indices) {
            var at = plan.slots[i].at
            var first = true
            // 칸의 첫머리가 어절 안쪽이면(부호를 떼어 낸 자리) 거기에 공백을 찍으면 안 된다.
            for (word in plan.chosen[i]) {
                if (at > 0 && (!first || plan.slots[i].cutAtStart)) cuts += at
                first = false
                at += word.length
            }
        }
        return applyCuts(engineOut, cuts)
    }

    /**
     * 정한 경계를 [text] 에 입힌다. **원문의 공백 모양을 살린다.**
     *
     * 음절 열에서 다시 찍어 내면 간단하지만, 그러면 줄바꿈과 들여쓰기가 전부 한 칸짜리
     * 공백으로 뭉개진다. 창에 붙여 넣는 글은 대개 여러 문단이라 그건 못 쓴다. 그래서
     * 있던 공백은 그대로 두고 **없던 자리에만 한 칸을 넣는다.**
     *
     * 줄바꿈이 낀 자리는 무조건 경계로 친다 — 사람이 줄을 바꾼 자리를 붙일 일은 없다.
     */
    private fun applyCuts(text: String, cuts: Set<Int>): String {
        val sb = StringBuilder(text.length + 8)
        val pending = StringBuilder()
        var index = 0
        for (c in text) {
            if (c.isWhitespace()) { pending.append(c); continue }
            when {
                index == 0 -> sb.append(pending)                        // 맨 앞 들여쓰기
                index in cuts || pending.any { it == '\n' || it == '\r' } ->
                    sb.append(if (pending.isEmpty()) " " else pending)
                // 경계가 아니면 그 자리의 공백은 버린다.
            }
            pending.setLength(0)
            sb.append(c)
            index++
        }
        return sb.append(pending).toString()                            // 끝에 남은 공백
    }

    private class Plan(val slots: List<Slot>, val chosen: List<List<String>>)

    /**
     * 무엇을 어디까지 다시 고를지 정하고, 문장 전체를 칸으로 덮어 한 번에 푼다.
     *
     * 맞춤법 교정으로 음절이 바뀌었으면 원문과 엔진 답의 음절 열이 어긋나 자리를 맞출 수
     * 없으므로 null 을 준다 — 그런 행은 애초에 붙여 쓴 글이 아니다.
     */
    private fun plan(original: String, engineOut: String): Plan? {
        val stream = strip(original)
        if (strip(engineOut) != stream) return null

        // 사람이 찍은 공백. 여기서는 **자리를 넓힐 때의 울타리로만** 쓴다.
        val frozen = boundaries(original)
        val cuts = HashSet(boundaries(engineOut))
        // ## 들일 때 고친 것 (2/2)
        //
        // 원래는 여기서 `cuts += frozen` 으로 사용자가 찍은 공백을 되살려 **얼렸다.**
        // 그러면 엔진이 합칠 줄 아는 것까지 도로 갈라 놓는다. 정답 문장에 공백을 하나
        // 더 친 변형 4,435개에서 배포본은 1,103개(24.9%)를 되돌리는데 얼린 쪽은 0개였다.
        // `할 수 밖에 없다` → `할 수밖에 없다` 같은 맞춤법 규칙이 통째로 죽는다.
        // 푼 뒤: 1,176개(26.5%) 복원 — 배포본보다 낫고, 더 망친 비율은 11.2% → 8.3% 로
        // 오히려 줄었다. 말뭉치 점수는 test 에서 그대로고(f1·exact·damaged 동일), dev
        // 에서만 한 행(`금융 당국의`)을 잃는다. 그 한 행보다 합치기 기능이 크다.
        val locked = rejoinCompounds(stream, frozen, cuts)

        val words = cutsToWords(stream, cuts)

        // 어절을 다시 **조각**으로 나눈다. 앞뒤에 붙은 한글 아닌 글자를 떼어 놓으면
        // `.아버지` 의 `아버지` 도 자리에 데려갈 수 있다 ([peel]).
        //
        // 이것이 없으면 맨 앞에 부호 한 글자만 붙어도 답이 뒤집힌다. `Spacer` 는 한글 아닌
        // 글자가 하나라도 끼면 분석을 포기하므로 `.아버지` 는 통째로 "데려갈 수 없는 어절"
        // 이 되고, 그러면 `아버지가|방에` 가 후보에 아예 못 오른다. 조사가 잰 값: test 145행
        // 앞에 점 하나씩만 붙였더니 정확 일치가 111 → 108, 손상 행이 16 → 21 로 무너졌다.
        val pieces = ArrayList<String>(words.size)
        val realBoundary = ArrayList<Boolean>(words.size)   // 이 조각의 첫머리가 진짜 어절 경계인가
        val wordFrom = ArrayList<Int>(words.size)           // 조각이 속한 **어절 전체**의 자리
        val wordTo = ArrayList<Int>(words.size)
        var scan = 0
        for (word in words) {
            val parts = peel(word)
            for ((k, part) in parts.withIndex()) {
                pieces += part
                realBoundary += (k == 0)
                wordFrom += scan
                wordTo += scan + word.length
            }
            scan += word.length
        }
        val spans = suspectSpans(pieces, frozen, locked, wordFrom, wordTo)

        // 고칠 자리는 후보가 여럿이고 나머지는 하나다. 안 고칠 어절까지 격자에 넣는 까닭은
        // **앞뒤 어절이 문맥으로 점수에 들어가야** 하기 때문이다 — 그래야 '문장 통째로'다.
        val slots = ArrayList<Slot>(pieces.size)
        var at = 0
        var w = 0
        while (w < pieces.size) {
            val span = spans.firstOrNull { it.first == w }
            if (span == null) {
                slots += Slot(at, listOf(listOf(pieces[w])), NO_MORPH, realBoundary[w])
                at += pieces[w].length
                w++
            } else {
                val incumbent = pieces.subList(span.first, span.last + 1).toList()
                val text = incumbent.joinToString("")
                val list = candidates(text, incumbent)
                // 엔진 답을 0 점으로 놓은 상대값이라 칸끼리 더해도 argmax 가 안 바뀐다.
                val base = splitScore(incumbent)
                val forbidden = boundCuts(incumbent)
                slots += Slot(at, list, FloatArray(list.size) {
                    tuning.morphWeight * (base - splitScore(list[it])) / SPACE_PENALTY -
                        tuning.grammarPenalty * grammarBreaks(list[it], forbidden)
                }, realBoundary[span.first])
                at += text.length
                w = span.last + 1
            }
        }
        return Plan(slots, rescorer.best(slots))
    }

    /**
     * 사람이 **통째로 쓴 어절**이 사전에 오른 복합어처럼 보이면, 엔진이 그 안에 넣은 공백을
     * 지운다. 복합명사 손상(F1)의 복구 지점이다.
     *
     * 사람이 쓴 어절 하나 통째일 때만 본다. 숫자에 잘려 나온 토막(`9시부터진행됩니다` 의
     * `시부터진행됩니다`)은 낱말이 아니므로 여기에 걸리면 안 된다 — 그대로 굳어 버린다.
     *
     * @return 되붙인 덩어리가 차지하는 자리. 이 안은 다시 쪼개지 않는다.
     */
    private fun rejoinCompounds(stream: String, frozen: Set<Int>, cuts: MutableSet<Int>): BooleanArray {
        val locked = BooleanArray(stream.length)
        var start = 0
        for (end in 1..stream.length) {
            if (end != stream.length && end !in frozen) continue
            val token = stream.substring(start, end)
            if (token.length >= tuning.rejoinMinSyllables &&
                token.all { it.isHangul() } && vocabulary.keepsWhole(token)
            ) {
                for (i in start + 1 until end) { cuts -= i; locked[i] = true }
                locked[start] = true
            }
            start = end
        }
        return locked
    }

    /**
     * 다시 볼 자리를 고른다 — **언어모델이 모르는 어절 둘레**다.
     *
     * 모르는 어절은 "이 분절은 한국어가 아니다"에 가장 가까운 신호다: `말씀 하 신건은` 의
     * `신건은`, `이일주 일` 의 `이일주`, `부산하기 관의` 의 `부산하기`. 그 어절 하나만 봐서는
     * 고칠 수 없고(경계가 옆으로 새어 나갔으니), 그렇다고 문장 전체를 다시 고르면 멀쩡한
     * 자리까지 흔들린다. 그래서 어절 몇 개만 데리고 다시 푼다.
     *
     * 넓히기는 **어절 수**를 맞춘다. 좌우로 각각 세면 수상한 자리가 문장 끝에 있을 때
     * 한쪽을 못 쓰고 버리는데, 하필 `아버지 가방에 [들어가신다]` 가 그 꼴이다 — `아버지` 를
     * 못 데려오면 `아버지가|방에` 가 후보에 아예 못 오른다.
     *
     * 사람이 찍은 공백은 넘지 않는다. 되붙인 복합어도 건드리지 않는다.
     */
    private fun suspectSpans(
        words: List<String>,
        frozen: Set<Int>,
        locked: BooleanArray,
        wordFrom: List<Int>,
        wordTo: List<Int>,
    ): List<IntRange> {
        val starts = IntArray(words.size)
        var at = 0
        for (i in words.indices) { starts[i] = at; at += words[i].length }

        // 숫자·부호가 하나라도 끼면 `Spacer` 가 분석을 통째로 포기한다. 순한글만 데려간다.
        fun canTake(index: Int) = !locked[starts[index]] && words[index].all { it.isHangul() }

        val out = ArrayList<IntRange>()
        for (i in words.indices) {
            if (locked[starts[i]] || !words[i].all { it.isHangul() }) continue
            if (vocabulary.isWord(words[i])) continue
            var lo = i
            var hi = i
            while (hi - lo + 1 < 2 * tuning.spanRadius + 1) {
                val canLeft = lo > 0 && starts[lo] !in frozen && canTake(lo - 1)
                val canRight = hi < words.size - 1 && starts[hi + 1] !in frozen && canTake(hi + 1)
                if (!canLeft && !canRight) break
                // 좌우를 번갈아 넓혀 수상한 자리가 되도록 가운데에 오게 한다.
                if (canLeft && (!canRight || i - lo <= hi - i)) lo-- else hi++
            }
            if (out.isNotEmpty() && out.last().last >= lo) {
                out[out.size - 1] = out.last().first..maxOf(out.last().last, hi)
            } else {
                out += lo..hi
            }
        }
        val total = if (words.isEmpty()) 0 else starts.last() + words.last().length
        return out.filter { span ->
            val length = (span.first..span.last).sumOf { words[it].length }
            if (length !in MIN_SPAN..tuning.maxSpanSyllables) return@filter false
            // **사람이 통째로 친 어절을 엔진도 통째로 두었으면 손대지 않는다.**
            //
            // 그 자리는 사람과 형태소 사전이 둘 다 "이건 한 낱말이다" 라고 말한 곳이다.
            // 그런데 자리가 그 어절 하나뿐이면 좌우로 넓힐 곳이 없어(양쪽이 다 사용자 공백)
            // 후보가 곧 그 어절을 쪼갠 것들뿐이고, 엔진 답을 지키는 것은 [Tuning.overrideMargin]
            // 1 nat 하나다. 흔한 이름씨 둘이면 그 정도는 쉽게 넘는다.
            //
            // 그래서 배포본은 **맞게 쓴 글을 쪼갰다**: 들여쓰기가 → 들여 쓰기가,
            // 묻어나지 → 묻어 나지, 들어가셨다 → 들어 가셨다, 올라가겠습니다 → 올라 가겠습니다,
            // 국립현대미술관이 → 국립 현대 미술관이, 자연어처리가 → 자연어 처리가,
            // 교통유발부담금은 → 교통 유발 부담금은. 손으로 쓴 맞는 글 114행에서 손상이
            // 21행이었는데(되돌림 길은 18행), 이 한 줄로 3행이 된다 — 남은 셋은 되돌림 길도
            // 똑같이 망가뜨리는 예전 결함이다.
            //
            // 값도 치른다: `주시기바랍니다` 처럼 사람이 붙여 쓴 **두 어절**도 같이 지켜진다
            // (되돌림 길은 이것을 `주시기 바랍니다` 로 고친다). 이 엔진에서는 못 고친 오타
            // 하나보다 멀쩡한 글 스무 줄을 망가뜨리는 쪽이 훨씬 나쁘다고 보고 받아들였다.
            //
            // 붙여 쓴 긴 덩어리는 그대로 풀린다 — 엔진의 `applyLongSplit` 이 이미 쪼개
            // 놓았으므로 자리가 어절 하나짜리가 아니다 (`아버지가방에들어가신다`,
            // `회의자료준비상황을`).
            if (span.first == span.last) {
                val from = wordFrom[span.first]
                val to = wordTo[span.first]
                if ((from == 0 || from in frozen) && (to == total || to in frozen)) return@filter false
            }
            true
        }
    }

    /**
     * 어절 앞뒤에 붙은 한글 아닌 글자를 떼어 낸다. 한글 속살이 2음절 이상일 때만 — 그보다
     * 짧으면 떼어 봐야 자리에 쓸 수 없고, `010-1234-5678로` 의 `로` 같은 것이 혼자 떨어져
     * 나오는 일만 생긴다.
     */
    private fun peel(word: String): List<String> {
        var lo = 0
        var hi = word.length
        while (lo < hi && !word[lo].isHangul()) lo++
        while (hi > lo && !word[hi - 1].isHangul()) hi--
        if (hi - lo < 2 || (lo == 0 && hi == word.length)) return listOf(word)
        for (i in lo until hi) if (!word[i].isHangul()) return listOf(word)   // 가운데가 섞였으면 둔다
        val out = ArrayList<String>(3)
        if (lo > 0) out += word.substring(0, lo)
        out += word.substring(lo, hi)
        if (hi < word.length) out += word.substring(hi)
        return out
    }

    /**
     * 한 자리의 후보 목록. **0번은 늘 엔진이 내놓은 답이다** — 동점이면 그것을 쓴다.
     */
    private fun candidates(text: String, incumbent: List<String>): List<List<String>> {
        val out = LinkedHashSet<List<String>>()
        out += incumbent
        out += listOf(text)                             // 통째로 두는 갈래
        // 여기 **있었던 것**: 문맥 해독기에게 붙은 원문을 통째로 다시 물어보는 후보.
        //
        // 값이 싸다고 적어 두었는데 거짓이었다. `ContextCorrector.decode` 는 null 을 돌려줄지
        // 정하기 **전에** 격자를 세우고 비터비를 다 돌린다. 한 번에 7.9ms 고, 수상한 자리마다
        // 한 번씩 문다. 표본 프로파일에서 `respace` 안쪽 시간의 83.9% 가 이 한 줄이었다:
        // 짧은 줄 4,000개(52,000자)를 고치는 데 3,967ms — 이 줄을 빼면 385ms 로 떨어진다.
        // 붙여 쓴 한글 8,800자는 4,589ms → 237ms 다.
        //
        // 그러면서 값은 없다. dev 145행에서 이 후보가 이긴 적이 한 번도 없고, 빼 본 문서
        // 43개에서 출력이 43/43 글자까지 똑같았다. 말뭉치 점수도 dev·test 모두 그대로다.
        out += kBest(text, tuning.beam)
        return out.toList()
    }

    // ==================================================================================
    // 2) 후보 생성 — Spacer 비용 격자의 상위 K 갈래
    // ==================================================================================

    /**
     * `Spacer.space()` 가 고르는 방식을 그대로 흉내 내되, 1등만이 아니라 **상위 K 등까지**
     * 꺼낸다.
     *
     * `Spacer` 의 판정은 밖에서 정확히 재현된다: 분절 하나의 값은
     * `Σ cost(조각) + SPACE_PENALTY × (조각 수 − 1)` 이고 이 식의 최솟값이 `space()` 의
     * 답과 일치한다 (앞선 조사가 30개 토큰 중 29개에서 확인했다). 그러니까 K=1 은 지금
     * 엔진이 내놓는 답이고, **2등부터가 지금까지 한 번도 점수를 못 받아 본 답들**이다.
     *
     * 실제 예 (부동산중개수수료가): 1등 `부동산중 개수 수료가`(4727) … 5등
     * `부동산 중개 수수료가`(6003), 6등 통째(6230). 옳은 답이 1등과 1,503 밖에 차이가
     * 안 나는데도 영영 안 보였던 것이다.
     */
    private fun kBest(run: String, k: Int): List<List<String>> {
        val n = run.length
        // paths[i] = 앞에서부터 i 음절을 덮는 상위 K 경로, 값이 작은 순.
        val paths = arrayOfNulls<ArrayList<Path>>(n + 1)
        paths[0] = arrayListOf(Path(0, null, 0))
        for (i in 1..n) {
            val here = ArrayList<Path>(k + 1)
            for (j in maxOf(0, i - tuning.maxWordSyllables) until i) {
                val from = paths[j] ?: continue
                val piece = cost(run.substring(j, i))
                if (piece >= Spacer.UNANALYZABLE) continue
                val step = piece + if (j == 0) 0 else SPACE_PENALTY
                for (p in from) insert(here, Path(i, p, p.score + step), k)
            }
            paths[i] = if (here.isEmpty()) null else here
        }
        return (paths[n] ?: return emptyList()).map { it.words(run) }
    }

    private class Path(val at: Int, val prev: Path?, val score: Int) {
        fun words(run: String): List<String> {
            val out = ArrayList<String>()
            var p: Path? = this
            while (p?.prev != null) {
                out += run.substring(p.prev!!.at, p.at)
                p = p.prev
            }
            out.reverse()
            return out
        }
    }

    /** 값이 작은 순으로 [k] 개만 들고 있는 작은 정렬 삽입. */
    private fun insert(list: ArrayList<Path>, path: Path, k: Int) {
        var at = list.size
        while (at > 0 && list[at - 1].score > path.score) at--
        if (at >= k) return
        list.add(at, path)
        while (list.size > k) list.removeAt(list.size - 1)
    }

    // ==================================================================================
    // 3) 형태소·문법 점수 — 언어모델 혼자 두면 안 되는 자리
    // ==================================================================================

    /**
     * `Spacer.space()` 가 최소로 만드는 그 값. 낮을수록 형태소 사전이 좋아한다.
     *
     * 흉내가 아니라 **같은 목적함수**다. 그래서 이 값을 언어모델 점수와 나란히 더하면
     * "형태소 사전과 말뭉치가 둘 다 납득하는 분절"을 고르게 된다.
     */
    private fun splitScore(pieces: List<String>): Float {
        var total = 0f
        for (p in pieces) {
            val c = cost(p)
            // 분석이 안 되는 조각은 한국어가 아니다. 갈 수는 있되 아주 비싸게 친다.
            total += if (c >= Spacer.UNANALYZABLE) UNANALYZABLE_COST else c.toFloat()
        }
        return total + SPACE_PENALTY * (pieces.size - 1)
    }

    /**
     * 이 자리 안에서 **새 어절이 시작될 수 없는** 곳들.
     *
     * 엔진이 이미 한 어절로 읽은 낱말 안쪽에서, 그 자리에 시작하는 형태소가 매인 것
     * (어미·조사·접미사)이면 거기를 끊어서는 안 된다. `들어가신다` 를 한 어절로 분석하면
     * `들어가/VV + 시/EP + ㄴ다/EF` 라서 3번째 음절에 어미가 시작한다 — 그런데 `신다` 는
     * '신발을 신다'의 그 `신다` 라서 말뭉치에 흔하다. 언어모델만 물으면 `들어가 신다` 가
     * 7 nat 차이로 이긴다. 한국어로는 있을 수 없는 분절인데도 그렇다.
     *
     * 형태소 분석이 미덥지 않은 낱말(= 진짜로 붙여 쓴 덩어리)에서는 `boundMorphemeStartsAt`
     * 이 null 을 준다. 그래서 이 잣대는 **고쳐야 할 자리는 건드리지 않고** 멀쩡한 활용형만
     * 지킨다.
     */
    private fun boundCuts(incumbent: List<String>): BooleanArray {
        val out = BooleanArray(incumbent.sumOf { it.length })
        var at = 0
        for (word in incumbent) {
            for (k in 1 until word.length) {
                if (spacer.boundMorphemeStartsAt(word, k) == true) out[at + k] = true
            }
            at += word.length
        }
        return out
    }

    /**
     * 이 후보가 문법으로 어긴 자리의 수. 둘을 센다.
     *
     * 1. [forbidden] 이 짚은 자리를 끊었는가 ([boundCuts]).
     * 2. 새로 만든 조각이 **한 음절짜리 매인 말**인가. `Spacer.couldBeBound` 가 바로 이것을
     *    위해 있다 — 한 음절짜리 '가'·'이'·'지'·'다'는 낱말로도 조사·어미로도 읽히는데,
     *    어절을 잘라서 그런 조각을 새로 만드는 것은 거의 언제나 틀렸다.
     *
     * 2번이 없으면 `들어가신 다` 가 나온다. 1번은 '다' 앞을 못 막는다 — 어미 'ㄴ다' 는
     * '신' 의 받침에서 시작해서 음절 경계에 걸리지 않기 때문이다. 2번은 dev 에서 한 행
     * (`다들 잘` → `다들잘`)을 잃지만, 문법으로 막을 수 있는 것을 확률에 맡기지 않는 편이
     * 낫다고 봤다 — 잃는 쪽은 경계 하나고, 얻는 쪽은 사람이 보면 바로 틀린 줄 아는 출력이다.
     */
    private fun grammarBreaks(pieces: List<String>, forbidden: BooleanArray): Int {
        var at = 0
        var n = 0
        for (i in 0 until pieces.size - 1) {
            at += pieces[i].length
            if (at < forbidden.size && forbidden[at]) n++
        }
        for (i in 1 until pieces.size) if (pieces[i].length == 1 && spacer.couldBeBound(pieces[i])) n++
        return n
    }

    // ==================================================================================
    // 4) 재채점 — 문장을 통째로 이어 붙여 점수를 낸다
    // ==================================================================================

    /**
     * 음절 열 [at] 부터 시작하는 한 칸과, 거기에 들어갈 수 있는 분절들. 0번이 엔진의 답이다.
     * [morph] 는 후보마다의 형태소·문법 점수 — 엔진 답을 0 으로 맞춘 상대값이다.
     * [cutAtStart] 가 false 면 이 칸의 첫머리는 어절 안쪽이라 공백을 찍으면 안 된다.
     */
    private class Slot(
        val at: Int,
        val candidates: List<List<String>>,
        val morph: FloatArray,
        val cutAtStart: Boolean = true,
    )

    /**
     * 후보 조합 전체를 **문장 하나씩** 채점해 가장 좋은 것을 고른다.
     *
     * 조합은 K^(칸 수) 개라 다 적어 볼 수는 없지만, `lnConditional` 은 바로 앞 어절만 보는
     * 1차 연쇄라 **마지막 어절만 기억하면** 비터비로 정확한 최댓값을 찾을 수 있다. 그러니까
     * 이것은 "몇 개만 추려 보는" 근사가 아니라 후보 격자 위의 **정확한 argmax** 다.
     */
    private class Rescorer(private val lm: LanguageModel, private val tuning: Tuning) {

        fun best(slots: List<Slot>): List<List<String>> {
            var layer = listOf(Cell(LanguageModel.BOS, 0f, null, -1))
            for (slot in slots) {
                val next = ArrayList<Cell>(slot.candidates.size)
                for ((index, candidate) in slot.candidates.withIndex()) {
                    val fixed = inner(candidate) + slot.morph.getOrElse(index) { 0f } +
                        // 엔진 답(0번)에는 문턱을 얹어 준다. 박빙이면 안 뒤집는다.
                        if (index == 0) tuning.overrideMargin else 0f
                    var bestScore = Float.NEGATIVE_INFINITY
                    var bestFrom: Cell? = null
                    for (cell in layer) {
                        val s = cell.score + step(cell.tail, candidate.first())
                        if (s > bestScore) { bestScore = s; bestFrom = cell }
                    }
                    next += Cell(key(candidate.last()), bestScore + fixed, bestFrom, index)
                }
                layer = prune(next)
            }
            var cell = layer.maxByOrNull { it.score }!!
            val picked = IntArray(slots.size)
            for (i in slots.indices.reversed()) { picked[i] = cell.choice; cell = cell.from!! }
            return slots.mapIndexed { i, s -> s.candidates[picked[i]] }
        }

        /** 같은 꼬리 어절로 끝나는 칸은 하나만 남긴다 — 앞으로의 점수가 똑같기 때문이다. */
        private fun prune(cells: List<Cell>): List<Cell> {
            val best = LinkedHashMap<String, Cell>()
            for (c in cells) {
                val old = best[c.tail]
                if (old == null || c.score > old.score) best[c.tail] = c
            }
            return best.values.toList()
        }

        private class Cell(val tail: String, val score: Float, val from: Cell?, val choice: Int)

        private fun inner(words: List<String>): Float {
            var s = 0f
            for (i in 1 until words.size) s += step(key(words[i - 1]), words[i])
            return s
        }

        /** 문장 머리부터 이 후보만 이어 붙인 점수. [NBestCorrector.explain] 이 보여 줄 때 쓴다. */
        fun score(words: List<String>): Float = step(LanguageModel.BOS, words.first()) + inner(words)

        /** 어절 하나의 값. 모르는 어절은 [Tuning.unknownPerSyllable] 로 길이에 비례해 벌한다. */
        private fun step(prev: String, word: String): Float {
            val w = key(word)
            if (w.isEmpty()) return 0f
            return lm.lnConditional(prev, w) ?: (tuning.unknownPerSyllable * w.length)
        }
    }

    // ==================================================================================
    // 5) 거부권 — 사전에 오른 복합어는 아예 손대지 않는다
    // ==================================================================================

    /**
     * "이 덩어리는 원래 한 낱말인가" 를 **확률이 아니라 어휘 지식으로** 판정한다.
     *
     * 확률로는 못 가른다. `개인정보보호위원회는` 과 `회의자료준비상황을` 은 Spacer 가 보는
     * 모든 숫자(길이·비용·형태소 수·여유값)가 사실상 같고, 언어모델은 둘 다 쪼개고 싶어
     * 한다 — 앞의 것은 36 nat 차이로. 갈라지는 지점은 딱 하나다: **말뭉치가 그 말을 붙여
     * 쓴 채로 본 적이 있는가.**
     */
    private class Vocabulary(
        private val lm: LanguageModel,
        private val spacer: Spacer,
        private val chunks: Int,
    ) {

        private val cache = HashMap<String, Boolean>()

        fun keepsWhole(run: String): Boolean = cache.getOrPut(run) { decide(run) }

        private fun decide(run: String): Boolean {
            if (run.length < 3) return true
            if (!run.all { it.isHangul() }) return false
            if (isWord(run)) return true
            // 아는 조각 둘로 덮이면 복합어다. `개인정보보호|위원회는` 은 2, 붙여 쓴 문장은
            // 어절 수만큼 나온다. 이 하나가 Spacer 의 모든 신호를 이긴다.
            return coverPieces(run) <= chunks
        }

        /**
         * 말뭉치가 이 낱말을 본 적이 있나. 조사는 떼고도 본다 — '아파트관리비가' 는 모르지만
         * '아파트관리비' 는 안다. 부호는 LM 열쇠가 아니라 먼저 떼어 낸다.
         */
        fun isWord(word: String): Boolean {
            val w = key(word)
            if (w.isEmpty()) return true
            if (known(w)) return true
            for (j in JOSA) if (w.length > j.length + 1 && w.endsWith(j) && known(w.dropLast(j.length))) return true
            return false
        }

        private fun known(w: String) = lm.lnCount(w) != null

        /**
         * 앞 조각이 **체언으로 끝나는가.** 복합명사는 명사를 이어 붙인 것이라 앞 조각이 늘
         * 체언으로 끝나고(`개인정보보호`+`위원회는`), 붙여 쓴 문장은 앞 조각이 이미 완결된
         * 어절이라 조사나 어미로 끝난다(`아버지가`+`방에`, `내일까지`+`보고서를`).
         *
         * 이 조건이 없으면 `아버지가방에`·`내일까지보고서를`·`지금바로연락주세요` 가 전부
         * "아는 조각 둘"이라 복합어로 지켜져 버린다 — 하필 첫 번째가 이 일이 시작된 문장이다.
         */
        private fun endsNominal(chunk: String): Boolean {
            val tail = spacer.edgeTags(chunk)?.second ?: return false
            return spacer.isNominalTag(tail)
        }

        /** w 를 LM 이 아는 조각으로 덮는 최소 조각 수. 못 덮으면 [UNCOVERABLE]. */
        private fun coverPieces(w: String): Int {
            val n = w.length
            val dp = IntArray(n + 1) { UNCOVERABLE }
            dp[0] = 0
            for (i in 1..n) {
                for (j in maxOf(0, i - MAX_CHUNK) until i) {
                    if (dp[j] >= UNCOVERABLE || dp[j] + 1 >= dp[i]) continue
                    val chunk = w.substring(j, i)
                    if (!known(chunk)) continue
                    if (i < n && !endsNominal(chunk)) continue
                    dp[i] = dp[j] + 1
                }
            }
            return dp[n]
        }

        private companion object {
            const val UNCOVERABLE = 99

            /** 조각 하나의 최대 길이. `국민건강보험공단` 같은 것이 한 조각으로 들어가야 한다. */
            const val MAX_CHUNK = 12

            /** 조사 목록. 긴 것부터 본다. 여기 없는 조사는 [coverPieces] 쪽에서 걸린다. */
            val JOSA = listOf(
                "에서는", "에서도", "으로는", "에게는", "이라고", "으로서", "으로써",
                "에서", "에게", "으로", "부터", "까지", "보다", "처럼", "한테", "라고", "로서", "로써",
                "에는", "에도", "이나", "이란", "이라", "들이", "들을", "들은",
                "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "도", "만", "로", "나", "께",
            )
        }
    }

    // ==================================================================================
    // 6) 전처리 — 데스크톱 앱과 같은 코드
    // ==================================================================================

    /**
     * 한글과 다른 글자가 섞인 어절을 한글 덩어리로 잘라 각각 띄운다.
     *
     * `Spacer.space` 는 **한글 아닌 글자가 하나라도 끼면 무조건 null** 이라, 이것을 안 하면
     * `오늘은3시에친구를만나기로했다` 가 손도 안 댄 채 나간다. 순한글 어절은 건드리지
     * 않는다 — 그쪽은 엔진이 문맥까지 보고 푸는 편이 늘 낫다.
     */
    private fun preSpace(text: String): String =
        text.split(AROUND_WHITESPACE).joinToString("") { token ->
            when {
                token.isBlank() -> token
                token.none { it.isHangul() } -> token
                token.all { it.isHangul() } -> token
                else -> loosenMixed(token)
            }
        }

    private fun loosenMixed(token: String): String = buildString {
        var i = 0
        var previousWasHangul = false
        while (i < token.length) {
            val c = token[i]
            if (c.isHangul()) {
                var end = i
                while (end < token.length && token[end].isHangul()) end++
                val chunk = token.substring(i, end)
                append(if (chunk.length > 3) spacer.spaceLong(chunk) ?: chunk else chunk)
                i = end
                previousWasHangul = true
            } else {
                // 한글 바로 뒤의 숫자·영문은 붙여 쓴 것이다: '오늘은3시' → '오늘은 3시'.
                // 숫자 뒤의 한글은 아니다 — '10시', '50만원' 은 붙어 있는 것이 맞다.
                // 부호도 아니다. 그랬다간 '1.2.3' 과 '12,500' 이 깨진다.
                if (previousWasHangul && c.isLetterOrDigit()) append(' ')
                append(c)
                i++
                previousWasHangul = false
            }
        }
    }

    // ==================================================================================
    // 자리 계산과 진단
    // ==================================================================================

    private fun cutsToWords(stream: String, cuts: Set<Int>): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (i in 1 until stream.length) if (i in cuts) { out += stream.substring(last, i); last = i }
        if (stream.isNotEmpty()) out += stream.substring(last)
        return out
    }

    /** 어디를 왜 다시 골랐는지 사람이 읽을 수 있게 늘어놓는다. 손볼 때 쓰라고 둔 것이다. */
    fun explain(text: String): String {
        val pre = if (engine.hasGluedRun(text)) preSpace(text) else text
        val engineOut =
            if (engine.hasGluedRun(text)) engine.correctAll(engine.correctAll(pre).text).text
            else engine.correctAll(pre).text
        val sb = StringBuilder()
        sb.append("원문 : ").append(text).append('\n')
        sb.append("엔진 : ").append(engineOut).append('\n')
        val plan = plan(text, engineOut)
            ?: return sb.append("(맞춤법 교정으로 음절이 바뀌어 재분절하지 않는다)\n").toString()
        for (i in plan.slots.indices) {
            val slot = plan.slots[i]
            if (slot.candidates.size == 1) continue
            sb.append("자리 ").append(slot.at).append(" '")
                .append(slot.candidates[0].joinToString(" ")).append("'\n")
            for ((n, candidate) in slot.candidates.withIndex()) {
                sb.append(String.format(
                    "    %-30s lm=%8.2f 형태소·문법=%7.2f%s%s%n",
                    candidate.joinToString(" "),
                    rescorer.score(candidate),
                    slot.morph[n],
                    if (n == 0) "  (엔진)" else "",
                    if (candidate == plan.chosen[i]) " <=" else "",
                ))
            }
        }
        return sb.append("결과 : ").append(respace(text, engineOut)).append('\n').toString()
    }

    companion object {
        /** `Spacer.SPACE_PENALTY`. core 에서 private 이라 값을 옮겨 적었다 (Spacer.kt:507). */
        private const val SPACE_PENALTY = 2000

        /** 분석이 안 되는 조각의 값. `Spacer.UNANALYZABLE`(백만) 을 그대로 더하면 넘친다. */
        private const val UNANALYZABLE_COST = 50_000f

        /** `Spacer.cost` 캐시 크기. `CorrectionEngine.ANALYSIS_CACHE_SIZE` 와 같은 값이다. */
        private const val COST_CACHE_SIZE = 512

        /** 이보다 짧은 자리는 다시 갈라 봐야 나올 것이 없다 (`Spacer` 의 최소 어절이 3음절). */
        private const val MIN_SPAN = 4

        /** 줄마다 따로 고르려고 가른다. [respace] 참고. */
        private const val NEWLINE = '\n'

        private val NO_MORPH = FloatArray(0)
        private val AROUND_WHITESPACE = Regex("(?<=\\s)|(?=\\s)")

        private fun Char.isHangul() = this in '가'..'힣'

        private fun strip(text: String): String = text.filter { !it.isWhitespace() }

        /** 경계 = 공백 뺀 음절 열에서 '바로 앞에 공백이 있던' 자리. 채점기의 정의와 같다. */
        private fun boundaries(text: String): Set<Int> {
            val out = HashSet<Int>()
            var index = 0
            var afterSpace = false
            for (c in text) {
                if (c.isWhitespace()) { afterSpace = true; continue }
                if (afterSpace && index > 0) out += index
                afterSpace = false
                index++
            }
            return out
        }

        /** LM 열쇠는 부호 없는 순수 어절이다 — `lnCount("있다")=13.25` 인데 `lnCount("있다.")=null`. */
        private fun key(word: String): String = word.trim { !it.isLetterOrDigit() }

        /**
         * 사전을 열고 곧바로 쓸 수 있는 교정기를 만든다.
         *
         * `SpacingDictionary.open` / `LanguageModel.open` 은 캐시 폴더에 고정된 이름으로
         * 풀었다가 이름을 바꾸므로 **한 프로세스에서 한 번만** 불러야 한다. 사전을 이미
         * 열어 두었으면 아래 [create] 를 쓴다.
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
            val corrector = NBestCorrector(engine, spacer, lm, tuning)
            return { text -> corrector.correct(text) }
        }
    }
}
