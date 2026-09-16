package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * # 어절마다 사전에게 물어보고, 문은 좁게 연다
 *
 * ## 왜 필요한가
 *
 * 이 겹이 붙기 전까지 프로그램은 **붙여 쓴 덩어리가 있을 때만** 띄어쓰기를 만졌다.
 * `CorrectionEngine.hasGluedRun` 이 거짓이면 [NBestCorrector] 의 재분절도, 엔진의
 * 전처리도 돌지 않는다. 그런데 사람이 실제로 치는 글은 이미 띄어져 있고, 틀린 자리는
 * 한두 군데다.
 *
 *     안녕하세요 저는 김건우 입니다 … 하지마세요 … 축화해주세요
 *
 * 이 글에는 붙여 쓴 덩어리가 없다. 그래서 "고칠 것이 없었습니다" 가 나왔다.
 * 정작 사전은 답을 알고 있다.
 *
 *     spacer.space("하지마세요")   = 하지 마세요     ← 안다
 *     spacer.space("축화해주세요")  = 축화해 주세요   ← 안다
 *     spacer.space("장난칩니까")   = 장난 칩니까     ← 틀렸다. 장난치다는 한 낱말이다
 *
 * 그래서 이 파일이 하는 일은 둘이다. **묻고**, **거른다.**
 *
 * ## 왜 거르는 것이 전부인가
 *
 * 묻기만 하면 손해다. 새 dev 200행의 어절을 전부 `space()` 에 넣어 보면 경계 34개를
 * 맞히고 38개를 틀린다 — 맞는 글을 부수는 쪽이 더 많다.
 *
 *     장난칩니까   → 장난 칩니까     재미있었다 → 재미 있었다
 *     부탁드립니다  → 부탁 드립니다    끝났어요   → 끝 났어요
 *
 * 값 하나로는 갈라지지 않는다. `cost` 도 `lnCount` 도 이 두 무리에서 완전히 겹치고,
 * 하필 이름난 두 짝에서는 **거꾸로** 간다 (하지마세요 cost=-660 / 장난칩니까 cost=4578).
 * 갈라 주는 것은 **경계에 선 두 형태소의 품사**다. 그래서 문은 다섯 개만 낸다.
 *
 * | 문 | 언제 열리나 |
 * |---|---|
 * | [depAfterAdnominal] | 관형형어미(ETM) 뒤의 의존명사, 쪼갠 쪽이 더 싸면 |
 * | [auxAfterConnective] | 앞말이 `-지 -고 -게` 이고 뒷말이 그 어미가 받는 보조용언이면 |
 * | [auxAfterCompound] | 앞말이 `-아/-어` 이고 세 음절 이상이면 (본용언이 합성어) |
 * | [boundNounTag] | 사전이 그 자리를 NNB 로 짚어 주면 |
 * | [fixedShape] | `-는 둥`, `-지 마`, `-ㄹ 수 있다` — 꼴이 못 박힌 자리 |
 *
 * 붙이는 자리는 하나뿐이다 — 서술격조사 `-이다`([joinCopula], 41항).
 *
 * ## 문마다 손으로 막아 둔 자리 — 전부 맞는 글에서 걸렸던 것들이다
 *
 * 이 겹의 첫 판은 말뭉치 두 벌에서 손상이 0 이었는데, **말뭉치 밖의 평범한 한국어**
 * 254문장에 태우니 23문장을 부쉈다. 말뭉치로 고른 규칙을 말뭉치로 검사하면
 * 안 보이는 것들이다. 고친 다섯 자리:
 *
 * ```
 *   나카지마 선수가    → 나카지 마 …    꼴_지마가 표기만 보고 이름을 갈랐다 (7문장)
 *   데려다주신 기사님께 → 데려다 주신 …   -어다 + 주다/보다는 한 낱말이다 (5문장)
 *   어쩐지 오늘따라    → 어쩐 지 …      어쩌다가 동사라 -(으)ㄴ 지 문이 열렸다 (3문장)
 *   먹을거리 장터가    → 먹을 거리 …     먹을거리/NNG 는 사전 표제어다 (3문장)
 *   되는대로 아무거나  → 되는 대로 …     한 낱말인 부사 (1문장)
 *   그렇고말고 네 말이 → 그렇고 말고 …   -고말고는 종결어미다 (1문장)
 *   살펴본바 문제가    → 살펴본 바 …     -ㄴ바는 연결어미다 (1문장)
 * ```
 *
 * 이 일곱을 막고 나서 다시 재니 **254문장 중 이 겹이 새로 부순 것은 0문장**이고,
 * held-out 점수로 치른 값은 새 test 통째 정답 한 행뿐이다(139 → 138).
 *
 * ## 이 파일이 안 하는 일
 *
 * - **조사를 일반적으로 붙이지 않는다.** `-이다` 말고는 공백을 넣기만 한다.
 *   `문 밖에 택배`(의존명사+조사), `보다 나은 방법`(부사), `김건우 씨`(의존명사) 처럼
 *   표면형이 조사와 똑같으면서 띄는 것이 맞는 자리가 수두룩하다.
 * - **`space()` 가 안 내놓은 자리는 의존명사 목록으로만 연다.** 아무 자리나 안 흔든다.
 * - 한글 아닌 글자가 섞인 어절, 두 음절 이하, 열여섯 음절 넘는 어절은 손대지 않는다.
 *
 * ## 재 본 값 — 배포본 → 이 겹을 얹은 것 ([SpellingFixer] 없이)
 *
 *     새 dev 200행   F1 0.8297 → 0.8438, 통째 정답 127 → 149, 손상 1/79 → 1/79
 *     새 test 194행  F1 0.8183 → 0.8339, 통째 정답 117 → 138, 손상 2/74 → 2/74
 *     옛 dev 145행   F1 0.9695 → 0.9706, 통째 정답 107 → 107, 손상 2/69 → 2/69
 *     옛 test 145행  F1 0.9647 → 0.9652, 통째 정답 115 → 116, 손상 1/73 → 1/73
 *
 * 남은 손상은 전부 배포본이 이미 내던 것이고 이 겹은 하나도 보태지 않았다.
 *
 * **스레드**: 하나에서만 부르라. 안의 [Spacer] 와 [memo] 가 동기화되어 있지 않다 —
 * `CorrectionEngine`, [NBestCorrector] 와 같은 사정이다.
 */
class TokenSpacer(
    private val spacer: Spacer,
    private val lm: LanguageModel,
    private val tuning: Tuning = Tuning(),
) {

    /**
     * 손으로 맞춘 값은 이것이 전부다. 전부 굵은 눈금이고, 하나하나 새 dev 200행에서
     * 켜고 꺼 보며 정했다. 기본값에서 어느 쪽으로 움직여도 손상이 늘거나 회수가 준다.
     */
    data class Tuning(
        /** 관형형 뒤 의존명사 문을 연다. */
        val depAfterAdnominal: Boolean = true,
        /** `-지 -고 -게` 뒤 보조용언 문을 연다. */
        val auxAfterConnective: Boolean = true,
        /** `-아/-어` 뒤 보조용언 문을, 앞말이 [compoundStemSyllables] 음절 이상일 때만 연다. */
        val auxAfterCompound: Boolean = true,
        /** 사전이 NNB 로 짚은 자리를 연다. */
        val boundNounTag: Boolean = true,
        /** `-는 둥 마는 둥`, `-ㄹ 수 있다` 처럼 꼴이 못 박힌 자리를 연다. */
        val fixedShapes: Boolean = true,
        /**
         * 보조용언 두 문에 **언어모델 여백**을 덧대 본다: 통째로 본 적이 있는 어절인데
         * 쪼갠 두 조각이 이어 나온 적이 더 드물면 닫는다 ([lmAgrees]).
         *
         * **꺼 둔다.** 재 보니 주기만 하고 받는 것이 없었다 — 새 dev 에서 문장 통째
         * 정답 149 → 147, 손상은 1/79 로 **똑같다**. 막아 주는 것이 하나도 없다는 뜻이다.
         * 품사만으로 이미 틀린 자리가 0 이라 여백이 할 일이 남아 있지 않다.
         *
         * 게다가 이 신호는 여기서 방향이 없다. `lnCount(하지마세요)=6.50` 인데
         * `lnCount(장난칩니까)=3.63` 이다 — 갈라야 할 쪽이 더 흔하다. 말뭉치가
         * `축화해 주세요` `매달아 놓았습니다` 같은 바른 짝을 본 적이 없어
         * `lnBigramCount` 가 null 인 것도 흔하다.
         */
        val lmGuard: Boolean = false,
        /**
         * `-아/-어 + 보조용언` 에서 본용언을 합성어로 볼 최소 음절 수.
         *
         * 한글 맞춤법 47항은 `-아/-어` 뒤의 보조용언을 붙여 쓰는 것도 허용하지만,
         * **본용언이 합성어이면 띄어 써야 한다** (매달아 놓다, 집어넣어 두다).
         * 두 음절짜리 앞말은 거의 다 한 낱말이라 붙여야 하고(도와주다, 살펴보다,
         * 물어보다, 정해지다, 여쭤보다), 세 음절부터는 합성어 쪽이다.
         * 3 을 2 로 내리면 새 dev 의 bojo 함정 다섯 개가 한꺼번에 깨진다.
         */
        val compoundStemSyllables: Int = 3,
        /** 손대는 어절의 길이. `Spacer.space` 자체가 3..24 밖을 거절한다. */
        val minTokenSyllables: Int = 3,
        val maxTokenSyllables: Int = 16,
        /** 서술격조사 `-이다` 를 앞 체언에 붙인다 (41항). 이 겹에서 붙이는 자리는 이것뿐. */
        val joinCopula: Boolean = true,
    )

    /** 무엇이 왜 열렸는지 밖에서 보고 싶을 때 꽂는다. 평소에는 null 이다. */
    var audit: ((token: String, index: Int, rule: String, accepted: Boolean) -> Unit)? = null
        set(value) { field = value; memo.clear() }   // 감사 기록이 캐시에 먹히지 않게 한다

    /** 어절 → 답(손 안 댔으면 빈 문자열). [Spacer] 와 같은 사정으로 동기화하지 않는다. */
    private val memo = HashMap<String, String>()

    /**
     * 글 전체를 어절 단위로 훑는다. 공백 배치 말고는 한 글자도 안 바꾼다.
     *
     * 어절 양 끝의 문장부호는 벗겨 내고 속살만 사전에 묻는다 — `Spacer.space` 는 한글
     * 아닌 글자가 하나라도 끼면 무조건 null 이라, 벗기지 않으면 `하지마세요.` 처럼
     * 마침표 하나 붙은 어절이 통째로 빠져나간다. 실제로 사람이 치는 글은 거의 다 그렇다.
     */
    fun correct(rawText: String): String {
        if (rawText.isEmpty()) return rawText
        val text = if (tuning.joinCopula) joinCopula(rawText) else rawText
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) { out.append(c); i++; continue }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            val token = text.substring(i, end)

            var head = 0
            while (head < token.length && !token[head].isHangulSyllable() && !token[head].isLetterOrDigit()) head++
            var tail = token.length
            while (tail > head && !token[tail - 1].isHangulSyllable() && !token[tail - 1].isLetterOrDigit()) tail--
            val core = token.substring(head, tail)
            val spaced = if (core.length == token.length) split(token) else split(core)

            if (spaced == null) out.append(token)
            else out.append(token, 0, head).append(spaced).append(token, tail, token.length)
            i = end
        }
        return out.toString()
    }

    /**
     * **서술격조사 `-이다` 는 체언에 붙여 쓴다** (한글 맞춤법 41항: 조사는 그 앞말에 붙여 씀).
     *
     *     저는 김건우 입니다  → 저는 김건우입니다
     *     바로 그것 이다      → 바로 그것이다
     *     막혔기 때문 입니다   → 막혔기 때문입니다
     *
     * 이 겹에서 **붙이는 자리는 여기 하나뿐이다.** 조사를 일반적으로 붙이는 길은 안 간다 —
     * `문 밖에 택배`(의존명사+조사), `보다 나은 방법`(부사), `김건우 씨`(의존명사) 처럼
     * 표면형이 조사와 똑같으면서 띄는 것이 맞는 자리가 수두룩하다. `-이다` 의 활용형은
     * [COPULA] 에 적은 닫힌 목록이고, 그 꼴로 홀로 서는 낱말이 국어에 없다.
     *
     * 조건 둘: 앞 어절이 **순한글 체언으로 끝나야** 하고(조사·어미로 끝나면 안 붙인다 —
     * `밥을 입니다` 는 붙일 자리가 아니다), 사이가 **공백 한 칸**이어야 한다(줄바꿈을
     * 사이에 둔 자리는 건드리지 않는다).
     */
    private fun joinCopula(text: String): String {
        var out: StringBuilder? = null
        var i = 0
        var prevEnd = -1      // 바로 앞 어절의 끝(공백 한 칸을 사이에 두고 있을 때만 유효)
        var prevStart = -1
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            val oneSpaceBefore = i > 0 && prevEnd == i - 1 && text[i - 1] == ' '
            if (oneSpaceBefore && text.substring(i, end) in COPULA) {
                val left = text.substring(prevStart, prevEnd)
                if (isBareNoun(left)) {
                    if (out == null) out = StringBuilder(text)
                    out[i - 1] = ' '     // 표시만 해 두고 마지막에 걷어 낸다
                }
            }
            prevStart = i; prevEnd = end; i = end
        }
        return out?.toString()?.replace(" ", "") ?: text
    }

    /** 순한글이고 마지막 형태소가 체언인가. 조사·어미로 끝났으면 거짓. */
    private fun isBareNoun(word: String): Boolean {
        if (word.isEmpty() || !word.all { it.isHangulSyllable() }) return false
        val tag = spacer.edgeTags(word)?.second ?: return false
        return spacer.isNominalTag(tag)
    }

    /**
     * 어절 하나를 본다. 열 자리가 없으면 null — "손 안 댔다" 와 "똑같이 돌려줬다" 를
     * 구별할 수 있게 해 둔다.
     */
    fun split(token: String): String? {
        if (token.length < tuning.minTokenSyllables || token.length > tuning.maxTokenSyllables) return null
        if (!token.all { it.isHangulSyllable() }) return null
        // 한 자 칠 때마다 글 전체가 다시 도는 길(live/)이 있어 같은 어절을 수없이 다시 묻는다.
        // 답은 사전에만 달려 있으므로 그대로 담아 둔다. 뚜껑을 씌워 무한정 자라지 못하게 한다.
        memo[token]?.let { return it.ifEmpty { null } }
        val answer = decide(token)
        if (memo.size < MEMO_LIMIT) memo[token] = answer ?: ""
        return answer
    }

    private fun decide(token: String): String? {
        // `-고말고` 는 종결어미다 (좋고말고, 알고말고요, 그렇고말고). 보조용언 `말다` 는
        // `-지` 뒤에 오지 `-고` 뒤에 오지 않으므로 끝이 `고말고`면 통째로 둔다.
        // `하지말고` 는 `지말고` 라 여기 안 걸리고 그대로 `하지 말고` 로 쪼개진다.
        //
        // 보조용언 문에 `right == "말고"` 가드가 따로 있지만 그것만으로는 모자랐다 —
        // `알고말고요` 는 right 가 `말고요` 라 비껴가고, `좋고말고` 는 다른 문에서 열린다.
        // 어절 끝 모양으로 막는 편이 어느 문이 열리든 한 번에 걸린다.
        if (ENDING_GOMALGO.containsMatchIn(token)) return null

        val proposed = proposedCuts(token)
        val cuts = sortedSetOf<Int>()
        for (index in 1 until token.length) {
            val rule = ruleFor(token, index, index in proposed) ?: continue
            cuts += index
            audit?.invoke(token, index, rule, true)
        }
        if (cuts.isEmpty()) return null

        val out = StringBuilder(token.length + cuts.size)
        for ((at, ch) in token.withIndex()) {
            if (at in cuts) out.append(' ')
            out.append(ch)
        }
        return out.toString()
    }

    // ---------------------------------------------------------------------------
    // 문 네 개
    // ---------------------------------------------------------------------------

    /**
     * 이 자리를 열 까닭이 있으면 그 까닭의 이름을, 없으면 null 을 준다.
     *
     * 순서는 값과 무관하다 — 어느 문이든 하나만 열리면 자른다. 이름은 감사용이다.
     */
    private fun ruleFor(token: String, index: Int, proposedBySpacer: Boolean): String? {
        val left = token.substring(0, index)
        val right = token.substring(index)

        if (tuning.fixedShapes) fixedShape(token, index, left, right)?.let { return it }

        // 아래 두 문은 **사전이 먼저 그 자리를 짚었을 때만** 연다 — `space()` 가 경계로
        // 내놓았거나, 그 자리에서 보조용언(VX)이 시작한다고 말했거나. 아무 자리나 흔들면
        // `보고했습니다 → 보고 했습니다` 같은 것이 바로 생긴다.
        if (proposedBySpacer || spacer.tagAt(token, index) == "VX") {
            if (tuning.auxAfterConnective && auxAfterConnective(token, index, left, right)) return "AUX_지고게"
            if (tuning.auxAfterCompound && auxAfterCompound(token, index, left, right)) return "AUX_합성어"
        }
        if (tuning.depAfterAdnominal && depAfterAdnominal(token, index, left, right)) return "DEP_관형형"
        if (tuning.boundNounTag && boundNounTag(token, index, left, right)) return "DEP_NNB"
        return null
    }

    /**
     * **관형형어미 + 의존명사.** 가장 많이 여는 문이다 — 두 말뭉치에서 맞음 8, 틀림 0.
     *
     * 두 가지를 동시에 요구한다.
     *
     * 1. `edgeTags(앞조각).second == "ETM"`. 관형사형 어미로 끝났다는 뜻이고,
     *    그 뒤에 오는 이름씨는 의존명사일 수밖에 없다.
     * 2. **쪼갠 쪽이 더 싸야 한다** (`cost(앞)+cost(뒤) < cost(통째)`).
     *    이 하나가 `-는데 -ㄹ게 -ㄹ지 -ㄹ뿐더러` 를 전부 막는다. 어미로 붙어 있는
     *    쪽이 형태소 사전에서 훨씬 싸기 때문이다:
     *
     *        가는데 → 가는|데   cost 7250 vs 쪼갠 쪽 9625+9750  → 안 연다
     *        할거냐면 → 할|거냐면 cost 1553 vs 쪼갠 쪽  467+793  → 연다
     */
    private fun depAfterAdnominal(token: String, index: Int, left: String, right: String): Boolean {
        val dep = DEP.firstOrNull { right.startsWith(it) } ?: return false
        if (dep == "뿐" && right.startsWith("뿐더러")) return false      // -ㄹ뿐더러는 어미다
        // 아래 넷은 맞는 글에서 걸렸던 자리다. 머리말의 표 참고.
        // `먹을거리`·`읽을거리`·`볼거리` 는 사전에 한 낱말로 올라 있다 (먹을거리/NNG).
        if (dep == "거" && right.startsWith("거리")) return false
        // `-ㄴ바` 는 연결어미다 (살펴본바 = 살펴본/VX 바/EC). 의존명사 `바` 는 조사를
        // 달고 온다 (느낀 바가). 조사가 없으면 어미 쪽으로 본다.
        if (dep == "바" && right.length < 2) return false
        if (token in ONE_WORD) return false
        if (left in NOT_ADNOMINAL) return false

        if (dep == "지") return elapsedTimeJi(left, right)
        if (!isAdnominal(left)) return false
        // `뿐` 은 비용 문턱을 면제한다. 붙여 쓴 쪽(`-ㄹ뿐더러`)은 위에서 이미 걸렀고,
        // 체언 뒤 조사 `뿐`(그것뿐, 이것뿐)은 앞조각이 관형형이 아니라 여기 못 온다.
        if (dep == "뿐") return true
        return spacer.cost(left) + spacer.cost(right) < spacer.cost(token)
    }

    /**
     * 앞조각이 관형사형으로 끝났는가.
     *
     * 원칙은 `edgeTags(앞).second == "ETM"` 이다. 다만 사전이 `-는` 을 조사(JX)로 읽어
     * 버리는 자리가 있어(`끝나는대로` = 끝/NNG 나/NP 는/JX), **`는` 으로 끝나면** 그
     * 오독까지 받아 준다. 체언 + 보조사 `는` 뒤에 의존명사가 붙어 오는 글은 없다.
     */
    private fun isAdnominal(left: String): Boolean {
        val tag = spacer.edgeTags(left)?.second
        return tag == "ETM" || (left.last() == '는' && tag == "JX")
    }

    /**
     * **`-(으)ㄴ 지`(시간 경과)만 연다.** 같은 두 글자가 어미이기도 해서, 형태로 가른다.
     *
     *     밥 먹은지 두 시간   → 먹은 지   (동사 + 관형사형 -은 + 의존명사 지)
     *     잘했는지 모르겠다    → 그대로     (-았/-었는지 는 어미, 앞음절 받침이 ㅆ)
     *     가능하신지 여쭤요    → 그대로     (앞이 용언이 아니다)
     *     예쁜지 물어봤어      → 그대로     (형용사 뒤 -ㄴ지 는 어미)
     *
     * 동사에만 붙는 것이 핵심이다. 형용사 뒤의 `-(으)ㄴ지` 는 언제나 어미고,
     * 동사 뒤의 `-(으)ㄴ 지` 는 언제나 의존명사다.
     */
    private fun elapsedTimeJi(left: String, right: String): Boolean {
        if (left.length < 2 || right.length > 3) return false
        // `는` 과 `던` 도 받침이 ㄴ이다. 받침만 보면 `샀는지 → 샀는 지` 가 나온다 —
        // 이 두 음절은 관형사형이 아니라 어미 `-는지/-던지` 의 몸통이므로 먼저 뺀다.
        if (left.last() == '는' || left.last() == '던') return false
        if (jongseong(left.last()) != 'ㄴ') return false
        val edge = spacer.edgeTags(left) ?: return false
        return edge.first == "VV" && edge.second == "ETM"
    }

    /**
     * **앞말이 `-지 -고 -게` 인 보조용언.** 47항의 허용(붙여 쓰기)은 `-아/-어` 와
     * 관형사형+의존명사에만 미친다. 이 세 꼴 뒤는 띄는 것이 원칙이자 유일한 답이다.
     *
     *     하지마세요 → 하지 마세요      보고싶다 → 보고 싶다
     *     정리하고있습니다 → 정리하고 있습니다   알게됐어요 → 알게 됐어요
     *
     * `드리다` 는 보조용언에서 **뺐다**. 접미사처럼 붙여 쓰는 것이 관행이고
     * (연락드리다, 말씀드리다, 회신드리다), 사전은 `보고드렸더니` 를 `보/VV 고/EC
     * 드렸/VX` 로 읽어 `보고 드렸더니` 를 내놓는다 — 맞는 글을 부수는 자리다.
     */
    private fun auxAfterConnective(token: String, index: Int, left: String, right: String): Boolean {
        if (left.length < 2) return false
        val allowed = when (left.last()) {
            '지' -> AUX_AFTER_JI
            '고' -> AUX_AFTER_GO
            '게' -> AUX_AFTER_GE
            else -> return false
        }
        if (allowed.none { right.startsWith(it) }) return false
        // `-고말고` 는 종결어미다 (좋고말고, 알고말고, 그렇고말고). 보조용언 `말다` 는
        // `말았다`·`말아서` 처럼 어미를 달고 오지 맨 `말고` 로 서지 않는다.
        if (right == "말고") return false
        if (!lmAgrees(token, left, right)) return false
        return spacer.edgeTags(left)?.second == "EC" || spacer.tagAt(token, index) in VERBAL
    }

    /**
     * 언어모델 여백. [Tuning.lmGuard] 가 꺼져 있으면 늘 참이다 — 왜 껐는지는 거기 적었다.
     *
     * 켜면: 통째 어절을 본 적이 없으면 통과, 본 적이 있으면 쪼갠 두 조각이 이어 나온
     * 횟수가 더 많아야 통과. 짝을 본 적이 없으면(null) 닫는다.
     */
    private fun lmAgrees(token: String, left: String, right: String): Boolean {
        if (!tuning.lmGuard) return true
        val whole = lm.lnCount(token) ?: return true
        val pair = lm.lnBigramCount(left, right) ?: return false
        return pair > whole
    }

    /**
     * **앞말이 `-아/-어` 이고 본용언이 합성어인 보조용언.** 47항 다만 조항이다.
     *
     *     매달아놓았습니다 → 매달아 놓았습니다    집어넣어두었어요 → 집어넣어 두었어요
     *
     * 합성어인지는 **음절 수로 가른다** ([Tuning.compoundStemSyllables]). 거칠어 보이지만
     * 두 음절짜리 `-아/-어` 앞말은 거의 다 사전에 한 낱말로 올라 있는 쪽이고
     * (도와주다, 살펴보다, 물어보다, 알아보다, 정해지다, 여쭤보다 — 새 dev 의 bojo 함정이
     * 전부 여기다), 세 음절부터는 합성어다. 한 낱말인데 세 음절인 것들만 따로 막는다.
     */
    private fun auxAfterCompound(token: String, index: Int, left: String, right: String): Boolean {
        if (left.length < tuning.compoundStemSyllables) return false
        if (left in SINGLE_WORD_STEMS) return false
        // `-어다` 로 끝나는 앞말은 뒤의 주다/보다와 **한 낱말로 굳은** 것들이다:
        // 데려다주다, 가져다주다, 바래다주다, 올려다보다, 내려다보다, 들여다보다.
        // 음절 수만 보는 규칙으로는 이 무리가 통째로 새어 나간다 (실측 5문장).
        if (left.last() == '다') return false
        if (AUX_AFTER_AEO.none { right.startsWith(it) }) return false
        if (!lmAgrees(token, left, right)) return false
        if (spacer.edgeTags(left)?.second != "EC") return false
        return spacer.tagAt(token, index) in VERBAL
    }

    /**
     * **사전이 그 자리를 NNB 로 짚어 줄 때.** 드물지만 공짜다 — 두 말뭉치에서 맞음 1, 틀림 0.
     *
     *     열흘만에 → 열흘 만에      올듯하다 → 올 듯하다
     *
     * 짚어 주는 의존명사 중에서도 [DEP_BY_TAG] 안의 것만 받는다. `분` 을 받으면
     * `참석자분들께` 가 깨지고, `이` 를 받으면 `가만이/확실이` 가 깨진다.
     */
    private fun boundNounTag(token: String, index: Int, left: String, right: String): Boolean {
        if (spacer.tagAt(token, index) !in BOUND_NOUN_TAGS) return false
        if (DEP_BY_TAG.none { right.startsWith(it) }) return false
        return spacer.cost(left) + spacer.cost(right) < spacer.cost(token)
    }

    /**
     * 꼴이 못 박힌 세 자리. **사전이 통째로 삼켜서 `space()` 가 아무 말도 안 하는** 곳이고,
     * 품사 표지도 안 나온다. 셋 다 그 꼴로 끝나는 낱말이 국어에 없다는 것이 근거다.
     *
     * - `-는 둥 마는 둥`: `든는둥` `마는둥` 은 `는둥/EC` 라는 어미 하나로 읽힌다.
     * - `-지 마`: `걱정하지마` 를 사전은 고유명사 하나로 읽는다(desc=걱정하지마/NNP).
     * - `-ㄹ 수 있다/없다`: 제일 흔한 구문인데 `갈수있다` 처럼 `수` 가 어미로 읽힌다.
     *   앞 음절 받침이 `ㄹ` 이고 뒤가 `있/없` 일 때만 열고, 한 낱말인 `별수` 는 뺀다.
     */
    private fun fixedShape(token: String, index: Int, left: String, right: String): String? {
        if (right == "둥" && token.endsWith("는둥") && index == token.length - 1) return "꼴_는둥"
        if (right == "마" && token.endsWith("지마") && left.length >= 2) {
            // `나카지마`·`고지마`·`후쿠지마` 는 사람 이름이지 `-지 마` 가 아니다. 이 문만
            // 사전을 안 보고 표기만 보고 있었고, 그래서 이름을 갈랐다. 왼쪽 조각이
            // **어미로 끝났을 때만** 연다: 걱정하지=(NNG,EC) 는 열리고, 나카지=null,
            // 고지=(NNG,NNG), 미야지=(NNP,NNP) 는 다 닫힌다.
            if (spacer.edgeTags(left)?.second == "EC") return "꼴_지마"
        }
        if (right.startsWith("수") && right.length >= 2 && (right[1] == '있' || right[1] == '없')) {
            if (jongseong(left.last()) == 'ㄹ' && left !in NOT_A_STEM_BEFORE_SU) return "꼴_ㄹ수있다"
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // 잔손질
    // ---------------------------------------------------------------------------

    /** `space()` 가 내놓은 경계의 자리들. 아무 말도 없으면 빈 집합이다. */
    private fun proposedCuts(token: String): Set<Int> {
        val spaced = spacer.space(token) ?: return emptySet()
        if (spaced == token) return emptySet()
        val cuts = HashSet<Int>()
        var at = 0
        for (c in spaced) {
            if (c == ' ') cuts += at else at++
        }
        return cuts
    }

    /** 받침. 없으면 null. `Hangul.NO_JONG` 이 ' ' 이라 표에 넣지 않고 인덱스를 민다. */
    private fun jongseong(c: Char): Char? {
        if (c !in '가'..'힣') return null
        val index = (c - '가') % 28
        return if (index == 0) null else JONG[index - 1]
    }

    private fun Char.isHangulSyllable() = this in '가'..'힣'

    companion object {

        /**
         * 관형형 뒤에 설 수 있는 의존명사. [depAfterAdnominal] 의 문지방을 넘을 후보를
         * 만드는 데만 쓴다 — 여기 있다고 열리는 것이 아니라, 여기 없으면 아예 안 물어본다.
         *
         * 일부러 뺀 것들:
         * - `이`  — `가만이 확실이 분명이` 가 `가만 이` 로 깨진다. -이/-히는 다른 사람 몫이다.
         * - `게`  — `먹을 게`(것이) 와 `전화할게`(어미)가 같은 꼴이다. 가릴 수가 없어 포기했다.
         * - `데`  — `-는데` 어미가 압도적으로 흔하다. 여는 쪽의 이득이 없다.
         * - `밖`  — `수밖에` 가 깨진다. 조사 `밖에` 와 구별이 안 된다.
         * - `분`  — `참석자분들께` 가 깨진다.
         */
        val DEP = listOf(
            "것", "거", "수", "리", "바", "때", "채", "줄", "뿐", "따름", "나름",
            "만큼", "대로", "듯", "양", "척", "체", "만", "지", "김", "통", "겸", "둥",
            "차", "턱", "셈", "적", "편", "뻔", "법", "성", "참", "터", "무렵", "즈음",
            "나위", "노릇", "따위", "마련",
        )

        /** [boundNounTag] 가 받아 주는 의존명사. [DEP] 보다 좁다. */
        val DEP_BY_TAG = listOf("만", "듯", "뻔", "채", "대로", "만큼", "것", "거", "수", "지", "둥", "김", "리", "바", "터", "줄")

        /**
         * 보조용언은 **앞 어미마다 올 수 있는 것이 다르다.** 47항이 꼽는 대로 갈라 두면
         * 문이 그만큼 좁아진다 — `타고나다`, `먹고살다` 처럼 `-고` 뒤에 아무 동사나
         * 오는 한 낱말을 이 목록이 막아 준다.
         *
         * `드리다` 는 어디에도 없다. 접미사처럼 붙여 쓰는 것이 관행이고(연락드리다,
         * 말씀드리다, 회신드리다), 사전은 `보고드렸더니` 의 `드렸` 을 VX 로 읽는다.
         */
        val AUX_AFTER_JI = listOf("마", "말", "못", "않")                        // -지 아니하다/못하다/말다
        val AUX_AFTER_GO = listOf("있", "싶", "말", "계시", "보")                 // -고 있다/싶다/말다/계시다/보다
        val AUX_AFTER_GE = listOf("하", "해", "했", "되", "돼", "됐", "만들")      // -게 하다/되다/만들다

        /**
         * `-아/-어` 뒤에 오는 보조용언. [auxAfterCompound] 에서만 쓴다.
         *
         * `지다` 는 없다. `-아/-어지다` 는 피동·상태 변화를 만드는 하나의 낱말이라 늘
         * 붙여 쓴다 (가벼워졌어요, 만들어졌다, 좋아지다). 넣으면 바로 부순다.
         */
        val AUX_AFTER_AEO = listOf(
            "보", "봐", "봤", "주", "줘", "줬", "놓", "놔", "놨", "두", "둬", "뒀",
            "버리", "버려", "버렸", "가", "갔", "간", "오", "와", "왔",
            "내", "냈", "대", "댔", "쌓", "있", "계시",
        )

        /** `-아/-어` 앞말이 세 음절이어도 한 낱말인 것들. [auxAfterCompound] 의 예외표. */
        val SINGLE_WORD_STEMS = setOf("들여다", "내려다", "돌아다", "거들떠", "굽어", "우러러", "넘겨다")

        /**
         * 서술격조사 `-이다` 의 활용형. **닫힌 목록이다** — 이 꼴로 홀로 서는 낱말이
         * 국어에 없어야 목록에 넣었다. `이다음`·`이달`·`이력` 처럼 `이-` 로 시작하는
         * 낱말은 어절 전체를 맞대어 보므로 걸리지 않는다.
         */
        val COPULA = setOf(
            "이다", "입니다", "입니까", "이며", "이고", "이지만", "인데", "이니", "이나",
            "이라고", "이라는", "이라서", "이라며", "이라도", "이라면", "이었다", "이었어요",
            "이에요", "이었습니다", "였다", "였어요", "였습니다", "이었고", "입니다만",
        )

        /** 한 낱말인 부사. 관형형+의존명사와 꼴이 똑같아 문이 그냥 열린다. */
        val ONE_WORD = setOf("되는대로", "그런대로", "이런대로", "저런대로")

        /** 종결어미 `-고말고`. `-요` 가 붙은 꼴까지 덮는다. [decide] 참고. */
        val ENDING_GOMALGO = Regex("고말고요?$")

        /** 관형사형처럼 보이지만 굳은 부사. `어쩐지` 의 `어쩐` 은 `어쩌다`의 활용이라 VV+ETM 이다. */
        val NOT_ADNOMINAL = setOf("어쩐")

        /** `별수 없다` 는 `별수` 가 한 낱말이다. */
        val NOT_A_STEM_BEFORE_SU = setOf("별")

        private const val MEMO_LIMIT = 20_000
        private val BOUND_NOUN_TAGS = setOf("NNB", "NNBC")
        private val VERBAL = setOf("VX", "VV", "VA", "VCP")
        /** 받침 스물일곱 개. 첫 칸(받침 없음)은 빼 두었다 — [jongseong] 이 1 을 뺀다. */
        private val JONG = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ".toCharArray()

        /**
         * 배포본 그대로의 교정기를 만들고 그 위에 이 겹을 얹는다.
         *
         * 배포본이란 `SpellEngine.load` 가 꽂는 것 전부다 — [Speller], [ContextCorrector],
         * [ConfusionFixer], [NBestCorrector]. 하나도 끄지 않는다. 이 겹은 **맨 마지막**에
         * 돈다: 엔진이 제 할 말을 다 한 뒤, 남은 어절만 사전에 다시 물어보는 자리다.
         *
         * `SpacingDictionary.open` / `LanguageModel.open` 은 한 프로세스에서 한 번만
         * 불러야 한다. 이미 열어 두었으면 아래 [create] 를 쓴다.
         */
        fun create(cacheDir: File, tuning: Tuning = Tuning()): (String) -> String {
            val spacer = Spacer(SpacingDictionary.open(cacheDir))
            return create(spacer, LanguageModel.open(cacheDir), tuning)
        }

        /** 이미 열어 둔 사전으로 만든다. */
        fun create(spacer: Spacer, lm: LanguageModel, tuning: Tuning = Tuning()): (String) -> String {
            val engine = CorrectionEngine()
            engine.spacer = spacer
            engine.speller = Speller(spacer)
            engine.context = ContextCorrector(lm, spacer)
            engine.typoFixer = ConfusionFixer(spacer, lm)
            val nbest = NBestCorrector(engine, spacer, lm)
            val layer = TokenSpacer(spacer, lm, tuning)
            return { text -> layer.correct(nbest.correct(text)) }
        }
    }
}
