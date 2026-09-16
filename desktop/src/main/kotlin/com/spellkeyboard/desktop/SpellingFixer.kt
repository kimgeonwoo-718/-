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
 * [ConfusionFixer] 뒤에 세 갈래를 더 얹는다. **음절만 고친다** —
 * [CorrectionEngine.applyTypoFixer] 는 공백 수가 달라진 결과를 말없이 버리므로 여기서
 * 띄어쓰기를 손댈 수는 없고, 손댈 생각도 없다. 띄어쓰기는 [NBestCorrector] 와
 * [TokenSpacer] 몫이다.
 *
 * ## 세 갈래
 *
 * 1. **-이/-히** ([adverbIHi]). 한글 맞춤법 51항이 규칙으로 정해 놓은 자리다.
 *    되돌리는 쪽(`깨끗히→깨끗이`)은 말뭉치를 아예 안 본다 — ㅅ받침과 굳은 목록이
 *    답을 이미 알고 있다. 밀어 넣는 쪽(`적당이→적당히`)만 말뭉치에 물어본다.
 * 2. **굳은 꼴** ([fixedShapes]). `뵈요→봬요`, `-세여→-세요`. 문법으로 끝난다.
 * 3. **혼동 자모 한 개** ([narrowTypo]). 손으로 고른 자모 짝만 바꿔 후보를 만들고
 *    말뭉치 여백으로 고른다. 자유롭게 자모를 바꾸는 길이 아니다 — 그 길은
 *    맞는 글의 2.9%(여백 4 기준)를 망가뜨린다고 이미 재 두었다.
 *
 * ## 자모 오타는 **말뭉치가 모르는 어절에서만** 연다
 *
 * 이것이 이 파일에서 제일 중요한 한 줄이다([narrowTypo] 첫 줄). 말뭉치가 아는 어절
 * 위에서 자모를 하나 갈아 끼우는 것은 오타 교정이 아니라 **낱말 바꿔치기**다.
 * 조건 없이 열었을 때 맞는 글 254문장에서 실제로 잡힌 것들이다:
 *
 * ```
 *   실을 잇는 방법     → 있는     (잇다: 잇는 7.13 을 말뭉치가 안다)
 *   아이를 업고 병원에  → 없고
 *   아기를 안아 주었어요 → 않아
 *   세로 방향으로      → 새로
 *   네일 아트를        → 내일
 *   짐을 두고 왔다     → 집을
 *   아까 그거 어디에   → 그가
 * ```
 *
 * 일곱 가지가 **전부 뜻이 뒤집히는 바꿔치기**다. "아기를 안아 주었어요" 를
 * "않아 주었어요" 로 만드는 교정기는 아무것도 안 하는 교정기보다 나쁘다.
 * 원말이 말뭉치에 없다는 조건 하나로 일곱이 다 막히고, 진짜 오타(`축화해주세요`,
 * `든는둥`, `하지먼`, `화긴`)는 원말이 낱말이 아니므로 그대로 지나간다.
 *
 * 그 위에 두 음절짜리 홀소리 건너뛰기를 한 번 더 막는다([Tuning.leapMinSyllables]).
 * `청자 → 창자` 가 그 조건이 없으면 살아 나온다 — 말뭉치가 `청자` 를 모르기 때문이다.
 * 짧은 어절일수록 홀소리 하나를 옮기면 딴 낱말에 그대로 내려앉는다.
 *
 * ## 연음 되돌리기와 한 글자 `재` 는 안 싣는다
 *
 * 둘 다 만들어 재 봤고 둘 다 맞는 글을 부순다.
 *
 * - **연음**(`화긴→확인`): "원말을 모를 때만" 을 걸어도 `올해 장마는 예년보다` 가
 *   `장만은` 이 된다. `장마는` 은 말뭉치에 없지만 버젓한 말이다. 음절 두 개가
 *   한꺼번에 바뀌는 편집이라 닿는 낱말이 너무 많다.
 * - **`재`→`제`**: 뒤 어절이 체언이면 `제`(저의)로 보는 규칙인데, `재`(灰)도 이름씨라
 *   뒤에 체언이 온다. 맞는 글 다섯 문장이 다섯 다 깨졌다 — `재 위에`, `재 가루가`,
 *   `재 한 줌`, `재 더미를`, `재 처리 비용`. 바이그램으로도 안 갈린다:
 *   `lnBigram(제,위에)=1.375` 인데 `lnBigram(재,위에)` 는 아예 없어서, 물어보면
 *   **틀린 쪽으로** 민다. 그래서 사용자 원문의 `재 생일` 은 **못 고친다.**
 *
 * ## ㅐ/ㅔ 는 따로 만들지 않았다
 *
 * `매다/메다`, `배다/베다`, `결재/결제`, `새로/세로`, `-대요/-데요` 는 **양쪽이 다
 * 맞는 말**이고 어느 쪽인지는 뜻이 정한다. 빈도로 고르면 한 방향을 맞히는 만큼 반대
 * 방향을 틀린다. 바이그램도 못 구한다: dev 의 ㅐ/ㅔ 자리 40곳 중 11곳은 양쪽 다
 * 바이그램이 아예 없다(`-대요/-데요` 일곱 곳은 전부). 그래서 [narrowTypo] 안의 짝
 * 두 개로만 남기고 `내`/`네` 는 통째로 뺐다([NEVER_TOUCH_AE]).
 *
 * ## 재 본 값 (이 겹만, [TokenSpacer] 없이)
 *
 * ```
 *                  F1       exact      damaged   mutated
 * 새 dev 200행
 *   출하본        0.8297   127/200    1/79      37/200
 *   + 이 교정기   0.8946   143/200    1/79      18/200
 * 새 test 194행 (held out)
 *   출하본        0.8183   117/194    2/74      34/194
 *   + 이 교정기   0.8702   130/194    2/74      20/194
 * 옛 dev·test 290행 (회귀 감시)
 *   출하본과 **한 글자도 다르지 않다** (0.9695 / 0.9647 그대로)
 * ```
 *
 * 맞는 글 260문장에 태워 한 글자라도 달라지면 손실로 세었다. **출하본이 이미 바꾸는
 * 8문장 말고는 한 문장도 더 바꾸지 않는다.** 그 260문장은 다른 사람이 이 교정기를
 * 부수려고 모은 것이라(`잇는`·`업고`·`안아`·`세로`·`네일`·`청자`·`그거`·`장마는`·
 * `재 위에`), 스스로 만든 시험보다 셈이 정직하다.
 *
 * **스레드**: [ConfusionFixer] 와 같다. 하나에서만 부르라. [source] 도 같은 스레드다.
 */
class SpellingFixer(
    private val spacer: Spacer,
    private val lm: LanguageModel,
    private val inner: TypoFixer,
    private val tuning: Tuning = Tuning(),
) : TypoFixer {

    /**
     * 손으로 맞춘 값은 이것뿐이다. 문법으로 결정되는 자리는 이 값을 아예 안 본다.
     *
     * 값을 내리면 재현율이 아니라 **손상**이 먼저 는다. 아래 값은 전부
     * "맞는 글 1,962어절에서 0번 걸리는" 가장 낮은 자리에서 한 칸 위다.
     */
    data class Tuning(
        /**
         * `이`를 `히`로 밀어 넣는 여백. `ln(어근+히) - ln(어근+이)` 가 이만큼은 돼야 한다.
         *
         * dev 에서 잰 두 무리는 겹치지 않는다: 정답이 `히`인 17개는 최소 4.00(한 글자
         * 어근인 `급`만 2.50), 맞는 `-이` 부사 40개는 최대 2.00. 3.0 은 그 사이다.
         */
        val hiMargin: Float = 3.0f,
        /**
         * 어근이 흔한 이름씨면 `이`는 주격조사일 수 있다. `ln(어근+을)`·`ln(어근+를)` 이
         * 이 값을 넘으면 손대지 않는다.
         *
         * 이게 `속이`(→속히), `사실이`, `일이`, `말이`, `눈이`, `돈이`, `안전이`, `이익이`
         * 를 통째로 막는다. 진짜 `히` 어근은 목적어를 안 받아서 최대가 `급을` 3.50 이다.
         */
        val nounMax: Float = 5.0f,
        /** 혼동 자모 한 개를 바꿀 때 필요한 여백. */
        val typoMargin: Float = 3.4f,
        /** 바꿔 넣을 쪽을 말뭉치가 이만큼은 알아야 한다. 한두 번 스친 것으로는 모자란다. */
        val typoFloor: Float = 2.0f,
        /**
         * ㅐ/ㅔ 만 따로 더 센 바닥을 쓴다. **이 짝은 뜻으로 갈리는 자리가 섞여 있다** —
         * `매다/메다`, `배다/베다`, `결재/결제`, `새로/세로` 는 둘 다 맞는 말이다.
         *
         * `밤을 새웠어요`(맞음) → `세웠어요` 가 여백 3.88 로 걸린다. 그런데 `ln(세웠어요)`
         * 는 2.88 뿐이다. **말뭉치가 잘 아는 쪽으로만 바꾼다**는 조건 하나로 이게 막히고,
         * `네일→내일`(ln 8.38), `세로→새로`(ln 8.50) 는 그대로 걸린다.
         */
        val aeFloor: Float = 4.0f,
        /**
         * 홀소리 건너뛰기(ㅓ/ㅏ/ㅗ/ㅘ/ㅕ/ㅛ)를 여는 최소 음절 수.
         *
         * 짧을수록 홀소리 하나를 옮기면 **딴 낱말에 그대로 내려앉는다**. 말뭉치가
         * 모르는 어절만 여는데도 `청자 → 창자`(靑瓷 → 창자) 가 살아 나왔다 — 두 음절
         * 짜리는 아예 닫는다. 말뭉치 네 벌(dev·test, 새 것·옛 것 679행)에서 이 조건을
         * 켜고 꺼도 **점수가 한 자리도 안 움직인다.** 공짜로 사는 안전이다.
         */
        val leapMinSyllables: Int = 3,
    )

    /** 어느 규칙이 몇 번 걸렸나. 시험에서만 읽는다. */
    val fired = LinkedHashMap<String, Int>()

    /** 걸린 자리를 하나씩 받아 보고 싶을 때. 시험에서만 꽂는다. */
    var trace: ((String) -> Unit)? = null

    private fun hit(rule: String) {
        fired[rule] = (fired[rule] ?: 0) + 1
    }

    /**
     * **사람이 실제로 친 글.** 교정기를 부르기 전에 [create] 가 꽂아 준다.
     *
     * 이게 없으면 이 교정기는 띄어쓰기가 만든 조각을 오타로 착각한다. 재 봤고,
     * 옛 dev 에서 세 행을 망가뜨렸다 — 자세한 것은 [typedByHand] 에 적어 두었다.
     *
     * **null 이면 말뭉치에 물어보는 규칙(`자모오타`)이 통째로 쉰다.**
     * 잊고 안 꽂았을 때 조용히 손상이 나는 것보다, 조용히 덜 고치는 편이 낫다.
     * 문법으로 결정되는 `이/히` 와 `굳은꼴` 은 원문 없이도 돈다 — 조각 위에서
     * 한 번도 안 걸리는 것을 옛 dev 전체에서 확인했다.
     *
     * **스레드**: 교정 한 번마다 갈아 끼우므로 [fix] 와 같은 스레드에서만 건드려야 한다.
     */
    @Volatile
    var source: String? = null

    private var sourceWords: List<String> = emptyList()
    private var sourceOf: String? = null

    /**
     * 이 어절을 사람이 정말로 쳤나 — 원문 어절 하나와 **앞이든 뒤든 끝이 맞닿아 있나.**
     *
     * 이 교정기가 받는 글은 띄어쓰기가 **이미 끝난** 글이다. 그래서 여기 있는 어절이
     * 전부 사람이 친 어절인 것은 아니다. 붙여 쓴 덩어리를 디코더가 갈라 놓은 조각이
     * 섞여 있고, **말뭉치는 그 조각을 모른다.** 모르는 조각에 자모를 하나 바꾸면 흔한
     * 낱말이 하나 튀어나오므로, 여백만 보고 가면 반드시 걸린다. 실측한 네 자리:
     *
     * ```
     * 지하철에서졸다가내릴역을한정거장이나…  →  … 한정 [거장이나] …   여백 4.25 → 고장이나
     * 국회는본회의를열고쟁점법안세건을처리했다 →  … 법안 [세건을] …     여백 3.75 → 세간을
     * 중소벤처기업부산하기관의지원사업공고가… →  … 부산하기 [관의] …   여백 4.00 → 간의
     * 아까그거어디서샀는지진짜궁금하다        →  아까 [그거] 어디서 …   여백 6.00 → 그가
     * ```
     *
     * 넷 다 고칠 수 있던 **띄어쓰기 오류를 되돌릴 수 없는 음절 오류로 굳힌다.**
     * 앞 세 줄은 옛 dev 에서 실제로 F1 을 0.9695 → 0.9528 로 끌어내렸다.
     *
     * 원문 어절의 앞이나 뒤에 맞닿은 것만 받는 까닭은 **띄어쓰기 담당이 먼저 손대고
     * 넘겨줄 수 있기 때문이다** — `축화해주세요` 가 `축화해 주세요` 로 갈라져 와도
     * `축화해` 는 여전히 원문 어절의 앞머리라 통과한다. 반면 위 네 조각은 전부
     * 덩어리 한가운데서 잘려 나와 어느 끝에도 안 닿는다.
     *
     * 값을 치르는 쪽은 **통째로 붙여 쓴 글**이다. 거기서는 이 교정기가 거의 일을 안 한다.
     * 그건 옛 말뭉치의 모습이고, 이 교정기가 노리는 것은 새 말뭉치의 모습 — 이미 띄어
     * 쓴 채로 오타만 한둘 있는, 사람이 실제로 치는 글이다.
     */
    private fun typedByHand(word: String): Boolean {
        val src = source ?: return false
        if (sourceOf !== src) {
            // 원문 어절과 그 **한글 몸통**을 둘 다 담는다. `(적당이)` 처럼 양쪽에 부호가
            // 붙어 있으면 어느 끝에도 안 닿아서 몸통 없이는 통째로 막혀 버린다.
            sourceWords = src.trim().split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .flatMap { listOf(it, core(it)) }
                .filter { it.isNotEmpty() }
            sourceOf = src
        }
        return sourceWords.any { it == word || it.startsWith(word) || it.endsWith(word) }
    }

    override fun fix(text: String): String? {
        // 먼저 출하본 규칙을 태운다. 그쪽이 문법으로 확실히 아는 것들이라 우선권이 있다.
        val base = inner.fix(text) ?: text

        val pieces = SPLIT.findAll(base).map { it.value }.toMutableList()
        val at = pieces.indices.filter { pieces[it].isNotBlank() }
        if (at.isEmpty()) return if (base == text) null else base

        val body = at.map { core(pieces[it]) }
        val out = body.toMutableList()

        for (k in out.indices) {
            val w = out[k]
            if (w.isEmpty()) continue
            val prev = out.getOrNull(k - 1)?.ifEmpty { null }
            val next = out.getOrNull(k + 1)?.ifEmpty { null }

            // 문법으로 결정되는 두 규칙은 조각 위에서도 안전하다 — 실측으로
            // 옛 dev 전체에서 한 번도 안 걸렸다. 원문을 몰라도 돈다.
            val ihi = adverbIHi(w)
            if (ihi != null) { out[k] = ihi; hit("이/히"); trace?.invoke("이/히\t$w->$ihi"); continue }
            val shape = fixedShapes(w)
            if (shape != null) { out[k] = shape; hit("굳은꼴"); trace?.invoke("굳은꼴\t$w->$shape"); continue }

            // 말뭉치에 물어보는 규칙은 **사람이 친 어절에만** 건다.
            if (!typedByHand(w)) continue
            val typo = narrowTypo(prev, w, next)
            if (typo != null) { out[k] = typo; hit("자모오타"); trace?.invoke("자모오타\t$w->$typo") }
        }
        if (out == body) return if (base == text) null else base

        for (k in out.indices) {
            val token = pieces[at[k]]
            val from = headOf(token)
            pieces[at[k]] = token.substring(0, from) + out[k] + token.substring(from + body[k].length)
        }
        return pieces.joinToString("")
    }

    // -------------------------------------------------------------------------
    // 1. -이 / -히 (한글 맞춤법 51항)
    // -------------------------------------------------------------------------

    /**
     * 부사의 끝음절 `-이`/`-히`.
     *
     * 51항은 "`이`로만 나는 것은 `-이`, `히`로만 나거나 `이`나 `히`로 나는 것은 `-히`"
     * 라고 적고, 그 아래에 **어느 쪽인지 소리로 가리는 요령**을 붙여 놓았다. 프로그램이
     * 쓸 수 있는 것은 그 요령뿐이다:
     *
     * - **ㅅ받침 뒤는 `이`다.** 깨끗이, 버젓이, 지긋이, 빠듯이, 반듯이. 예외가 없다.
     *   겹받침 ㅄ 도 같이 본다 — 끊임없이, 어이없이, 틀림없이.
     * - **굳은 `-이` 부사 목록.** 첩어 뒤(틈틈이, 번번이, 나날이)와 ㄱ받침 토박이말
     *   뒤(깊숙이, 수북이, 나직이)가 여기 들어간다. **이 둘은 규칙으로 못 가린다** —
     *   첩어라서 `이`인 `번번이` 옆에 첩어인데 `히`인 `천천히`·`단단히`·`꼼꼼히` 가 있고,
     *   ㄱ받침이라서 `이`인 `깊숙이` 옆에 ㄱ받침인데 `히`인 `엄격히`·`정확히` 가 있다.
     *   가르는 것은 어근이 토박이말이냐 한자말이냐인데, 그걸 물어볼 데가 없다.
     *
     * 되돌리는 쪽(`깨끗히 → 깨끗이`)은 위 둘만으로 끝난다. **말뭉치를 안 본다.**
     * 봐야 손해다 — 말뭉치는 `틈틈히`(4.88)도 `곰곰히`(6.13)도 버젓이 알고 있다.
     *
     * 밀어 넣는 쪽(`적당이 → 적당히`)은 목록으로 못 끝낸다. 틀린 `-이` 꼴은 무한하고,
     * 게다가 **`적당이`처럼 이름씨+주격조사로도 읽히는 것**이 섞여 있다. 그래서 세 겹으로 건다:
     *
     * 1. `어근+히` 가 **형태소 하나짜리 부사(MAG)** 로 분석돼야 한다. 이게
     *    `사실이`·`일이`·`값이`·`눈이` 같은 이름씨+조사를 통째로 걷어 낸다
     *    (`사실히` 는 `사실/NNG 히/NNG` 로, `눈히` 는 `눈/NNG 히/NNG` 로 갈라진다).
     * 2. 어근이 **목적어를 받는 이름씨가 아니어야** 한다([Tuning.nounMax]).
     *    `속히` 는 버젓한 부사지만 `속이`는 훨씬 흔한 이름씨+조사라, 이 조건이
     *    `속이 안 좋다`를 지킨다.
     * 3. 말뭉치가 `히` 쪽을 [Tuning.hiMargin] 만큼 더 알아야 한다.
     *
     * **어근이 한 글자면 아예 안 고친다.** `급이`→`급히` 를 잃지만(dev 1행),
     * `속이`·`극이`·`딱이`·`정이`·`심이` 를 다 지킨다. 한 글자 이름씨+`이` 는 너무 흔하고,
     * 맞게 쓴 것과 틀리게 쓴 것을 가를 근거가 이 자리에 없다.
     */
    private fun adverbIHi(w: String): String? {
        if (w.length < 3) return null
        if (!w.all { it in '가'..'힣' }) return null
        val stem = w.dropLast(1)
        if (stem.length < 2) return null

        // 히 -> 이. 규칙과 목록만 본다.
        if (w.endsWith("히")) {
            val alt = stem + "이"
            if (alt in I_ADVERBS) return alt
            if (jongseong(stem.last()) in SIOT_FINALS && lm.lnCount(alt) != null) return alt
            return null
        }
        if (!w.endsWith("이")) return null

        // 이 -> 히.
        if (w in I_ADVERBS) return null
        if (stem in AMBIGUOUS_STEMS) return null
        if (jongseong(stem.last()) in SIOT_FINALS) return null
        val alt = stem + "히"
        // 1) '어근히' 가 형태소 하나짜리 부사여야 한다.
        if (!isSingleAdverb(alt)) return null
        // 2) 어근이 목적어를 받는 이름씨면 '이'는 주격조사다.
        val asNoun = maxOf(ln(stem + "을"), ln(stem + "를"))
        if (asNoun >= tuning.nounMax) return null
        // 3) 말뭉치가 '히' 쪽을 확실히 더 알아야 한다.
        if (ln(alt) - ln(w) < tuning.hiMargin) return null
        return alt
    }

    // -------------------------------------------------------------------------
    // 2. 굳은 꼴 — 문법으로 끝난다. 말뭉치를 안 본다.
    // -------------------------------------------------------------------------

    /**
     * 짝의 한쪽이 **어느 자리에서도 맞은 적이 없는** 것들. [ConfusionFixer] 의
     * "굳은꼴" 옆에 있어야 할 것들인데 거기 빠져 있었다.
     *
     * - `뵈요`: `봬`는 `뵈어`의 준말이다. 어간 `뵈-` 에 보조사 `요`가 바로 붙을 자리가
     *   없으므로 `뵈어요 → 봬요` 뿐이다. `되요 → 돼요` 와 같은 꼴.
     * - `-세여`: 하십시오체의 `-세요` 는 어미 `-시-` + `-어요` 다. `-여` 라는 어미는
     *   없다. `가세여`·`하세여`·`오세여` 는 전부 친 사람이 `요`를 `여`로 친 것이다.
     *   **`여`로 끝나는 것을 통째로 고치지는 않는다** — 호격조사 `-이여`(`그대여`,
     *   `님이여`)와 `보여`·`쓰여` 가 멀쩡히 있어서 `세여` 로 좁혔다.
     *
     * 말뭉치는 여기서 도움이 안 된다: `ln(뵈요)` 3.75 대 `ln(봬요)` 3.88 로 거의 같고,
     * `안녕하세여` 는 4.38 로 말뭉치가 버젓이 알고 있다. 물어보지 않는다.
     */
    private fun fixedShapes(w: String): String? {
        if (w.endsWith("뵈요")) return w.dropLast(2) + "봬요"
        if (w.length >= 3 && w.endsWith("세여")) return w.dropLast(1) + "요"
        return null
    }

    // -------------------------------------------------------------------------
    // 3. 혼동 자모 한 개 — **말뭉치가 모르는 어절에서만** 연다
    // -------------------------------------------------------------------------

    /**
     * 손으로 고른 자모 짝 하나만 바꿔 후보를 만들고, 말뭉치가 훨씬 잘 아는 쪽으로 간다.
     *
     * **자모를 자유롭게 바꾸면 못 쓴다.** 초성 19 · 중성 21 · 종성 28 을 다 열어 보면
     * 맞는 어절의 2.9%(여백 4)가 망가진다 — `쬔다→된다`, `쉴게→쉽게`, `순창→순찰`,
     * `도커→도쿄`. 드문 말과 이름씨는 흔한 말과 자모 하나 거리에 널려 있다.
     * 짝을 [PAIRS_CHO]·[PAIRS_JUNG]·[PAIRS_JONG] 으로 좁히면 오작동이 0 이 된다.
     *
     * 짝은 전부 실측으로 골랐다. **한 번도 안 맞히면서 맞는 글을 건드린 짝은 뺐다:**
     * `ㅏ>ㅓ`(밥은→법은, 우산→우선 두 번), `ㅗ>ㅓ`(달로→달러), `ㅏ>ㅘ`(초가→초과),
     * `ㄹ>ㅀ`(올지→옳지 두 번), `ㅆ>ㅅ`(분씩→분식), `ㅛ>ㅕ`(요기만→여기만).
     *
     * 남은 짝도 다 같은 문턱을 쓰지는 않는다 — 무리는 [Kind] 에 갈라 두었다.
     *
     * 초성 `ㅅ>ㅇ` 은 **앞 음절 받침이 ㅅ/ㅆ 일 때만** 연다. 받침을 두 번 친 오타
     * (`먹었서요`→`먹었어요`)가 이 꼴이기 때문이다. 이 제한이 `쓰신다→쓰인다`,
     * `이슈는→이유는`, `시로→이로`, `시에→이에` 를 한꺼번에 막는다 — 앞 음절이
     * 없거나 받침이 없는 자리들이다.
     *
     * 그리고 **원말을 말뭉치가 알면 아예 시작도 안 한다.** 그 한 줄이 이 규칙을
     * 싣게 만든 조건이다 — 없으면 맞는 글 254문장에서 열여덟 문장을 부순다
     * (`잇는→있는`, `업고→없고`, `안아→않아`, `세로→새로`, `네일→내일`, `짐을→집을`,
     * `그거→그가`). 클래스 머리말에 목록이 있다.
     */
    private fun narrowTypo(prev: String?, w: String, next: String?): String? {
        if (w.length < 2 || w.length > 12) return null
        if (!w.all { it in '가'..'힣' }) return null
        val here = ln(w)
        // **말뭉치가 아는 낱말은 오타가 아니다.** 아는 낱말 위에서 자모를 하나 갈아
        // 끼우는 것은 오타 교정이 아니라 낱말 바꿔치기고, 뜻이 뒤집힌다.
        if (here != UNKNOWN) return null
        var bestWord: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for ((cand, kind) in candidates(w)) {
            if (cand == w) continue
            val cu = lm.lnCount(cand) ?: continue
            if (cu < (if (kind == Kind.AE) tuning.aeFloor else tuning.typoFloor)) continue
            if (kind == Kind.AE && (w in NEVER_TOUCH_AE || cand in NEVER_TOUCH_AE)) continue
            // 짧은 어절에서 홀소리를 건너뛰면 **딴 낱말에 그대로 내려앉는다**:
            // 청자→창자. 두 음절짜리는 아예 열지 않는다.
            if (kind == Kind.LEAP && w.length < tuning.leapMinSyllables) continue
            // 앞뒤 바이그램을 같이 본다. 없으면 -3 으로 깎는다 — 없는 것도 정보다.
            val score = (cu - here) + (big(prev, cand) - big(prev, w)) + (big(cand, next) - big(w, next))
            if (score > bestScore) { bestScore = score; bestWord = cand }
        }
        if (bestWord == null || bestScore < tuning.typoMargin) return null
        return bestWord
    }

    /**
     * 후보 한 개가 어느 무리에 드나. 무리마다 문턱이 다르다.
     *
     * - [PLAIN] — 받침·된소리 따위. 바꿔도 뜻이 통째로 바뀌는 일이 드물다.
     * - [AE] — ㅐ/ㅔ/ㅒ/ㅖ. **뜻으로 갈리는 짝이 섞여 있다.** 바닥이 더 높다.
     * - [LEAP] — ㅓ/ㅏ/ㅗ/ㅘ/ㅕ/ㅛ 처럼 **아주 다른 홀소리로 건너뛰는** 짝.
     *   `청자→창자`, `그거→그가`, `역을→욕을` 이 전부 여기라 짧은 어절에서는 닫는다.
     */
    private enum class Kind { PLAIN, AE, LEAP }

    private fun candidates(w: String): List<Pair<String, Kind>> {
        val out = ArrayList<Pair<String, Kind>>(16)
        for (i in w.indices) {
            val t = Hangul.decompose(w[i]) ?: continue
            for ((from, to) in PAIRS_CHO) {
                if (Hangul.CHOSEONG[t.first] != from) continue
                // ㅅ>ㅇ 은 받침 중복 입력 자리에서만.
                if (from == 'ㅅ' && to == 'ㅇ') {
                    if (i == 0) continue
                    val p = Hangul.decompose(w[i - 1]) ?: continue
                    if (p.third == 0 || Hangul.JONGSEONG[p.third] !in SIOT_DOUBLE) continue
                }
                val j = Hangul.CHOSEONG.indexOf(to)
                if (j >= 0) out += put(w, i, Hangul.compose(j, t.second, t.third)) to Kind.PLAIN
            }
            for ((from, to) in PAIRS_JUNG) {
                if (Hangul.JUNGSEONG[t.second] != from) continue
                val j = Hangul.JUNGSEONG.indexOf(to)
                if (j < 0) continue
                val kind = when {
                    from in AE_E && to in AE_E -> Kind.AE
                    (from to to) in LEAPS -> Kind.LEAP
                    else -> Kind.PLAIN
                }
                out += put(w, i, Hangul.compose(t.first, j, t.third)) to kind
            }
            for ((from, to) in PAIRS_JONG) {
                val cur = if (t.third == 0) NO_JONG else Hangul.JONGSEONG[t.third]
                if (cur != from) continue
                val j = if (to == NO_JONG) 0 else Hangul.JONGSEONG.indexOf(to)
                if (j >= 0) out += put(w, i, Hangul.compose(t.first, t.second, j)) to Kind.PLAIN
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // 잔심부름
    // -------------------------------------------------------------------------

    /**
     * 낱말 전체가 **형태소 하나짜리 부사(MAG)** 로 읽히나.
     *
     * [Spacer.edgeTags] 로 물으면 안 된다. 그쪽은 분석 비용이 형태소당 2000 을 넘으면
     * 통째로 null 을 돌려주는데, **진짜 부사의 절반이 그 위에 있다** — 적당히 2201,
     * 확실히 2100, 단단히 2330, 솔직히 2230, 신속히 2235. 처음에 edgeTags 로 짰다가
     * `적당이`·`확실이`·`단단이` 를 못 고쳤고, 그게 바로 사용자가 친 낱말이었다.
     *
     * [Spacer.describe] 는 같은 자리에서 분석을 글자로 그대로 내준다. 비용은 괄호 안에
     * 붙어 오므로 앞부분만 본다.
     *
     * **표면형을 대조하면 안 된다.** describe 가 내주는 한글은 **낱자로 풀린 꼴(NFD)**
     * 이다 — `천천히/MAG` 의 앞부분이 U+110E U+1165 U+11AB … 로 온다. 모아쓴 `"천천히"`
     * 와 문자열 비교를 하면 **언제나 거짓이고, 조용히 거짓이다.** 처음에 그렇게 짰다가
     * 규칙이 통째로 안 걸렸다. 그래서 표면은 안 보고 **형태소 개수와 품사만** 본다:
     * 띄어쓰기가 없으면 형태소 하나, 꼬리가 `/MAG` 면 부사.
     */
    private fun isSingleAdverb(word: String): Boolean {
        val head = (spacer.describe(word) ?: return false).substringBefore(" (")
        return !head.contains(' ') && head.endsWith("/MAG")
    }

    private fun ln(w: String): Float = lm.lnCount(w) ?: UNKNOWN

    private fun big(a: String?, b: String?): Float =
        if (a == null || b == null) NO_BIGRAM else lm.lnBigramCount(a, b) ?: NO_BIGRAM

    private fun jongseong(c: Char): Char {
        val t = Hangul.decompose(c) ?: return NO_JONG
        return if (t.third == 0) NO_JONG else Hangul.JONGSEONG[t.third]
    }

    private fun put(w: String, i: Int, c: Char) = w.substring(0, i) + c + w.substring(i + 1)

    /** 앞뒤 부호를 뺀 한글 몸통. */
    private fun core(token: String): String {
        val from = headOf(token)
        if (from == token.length) return ""
        var to = token.length
        while (to > from && token[to - 1] !in '가'..'힣') to--
        return token.substring(from, to)
    }

    private fun headOf(token: String): Int {
        var from = 0
        while (from < token.length && token[from] !in '가'..'힣') from++
        return from
    }

    companion object {
        private val SPLIT = Regex("\\S+|\\s+")
        private val WHITESPACE = Regex("\\s+")

        /** 말뭉치가 모르는 것. 0 으로 두면 "한 번 스친 것" 과 구별이 안 된다. */
        private const val UNKNOWN = -1f

        /** 바이그램이 없을 때의 값. 없는 것도 정보라 0 이 아니다. */
        private const val NO_BIGRAM = -3f

        /**
         * **[Hangul.NO_JONG] 은 공백이 아니라 `' '` 이다.** 받침 없음을 `' '` 로
         * 열쇠 삼으면 받침을 *더하는* 짝이 통째로 조용히 사라진다. 여기서 한 번 집어
         * 쓰고 다시는 직접 쓰지 않는다.
         */
        private val NO_JONG: Char = Hangul.JONGSEONG[0]

        /** ㅅ소리 받침. 51항이 `-이`로 못 박은 자리다. ㅄ 는 `-없이` 무리를 위해 같이 본다. */
        private val SIOT_FINALS = setOf('ㅅ', 'ㅆ', 'ㅄ')

        /**
         * **어근+`이` 도 어근+`히` 도 맞는 말인 어근들.** 여기서는 손대지 않는다.
         *
         * `성실히` 는 버젓한 부사고 `성실이` 는 버젓한 이름씨+주격조사다
         * (`성실이 최고의 무기다`). 둘 다 서므로 **어느 쪽인지는 쓴 사람만 안다** —
         * [Tuning.nounMax] 로도 못 가른다(`성실을` 1.63 은 진짜 히-어근인 `급을` 3.50,
         * `극을` 4.38 보다도 낮다). 말뭉치 빈도로 정하면 흔한 쪽으로 쏠릴 뿐이라,
         * [ConfusionFixer.Tuning.semanticRules] 와 같은 이유로 아예 비켜 간다.
         *
         * 이 중 대부분은 이미 여백이나 [Tuning.nounMax] 에 막힌다(`사실` -9.75,
         * `진실` -5.63, `속` 0.00, `안전` -2.63). 실측으로 반드시 필요한 것은 `성실`
         * 하나지만, 같은 꼴은 같이 적어 두는 편이 나중에 읽기 쉽다.
         */
        private val AMBIGUOUS_STEMS = setOf(
            "성실", "진실", "사실", "우연", "안전", "정직", "공정", "친절", "영원", "정당",
        )

        /** 받침을 두 번 친 오타가 생길 수 있는 받침. */
        private val SIOT_DOUBLE = setOf('ㅅ', 'ㅆ')

        private val AE_E = setOf('ㅐ', 'ㅔ', 'ㅒ', 'ㅖ')

        /**
         * **아주 다른 홀소리로 건너뛰는 짝.** [Tuning.leapMinSyllables] 가 붙는다.
         * 붙여 놓은 수는 (진짜 오타에서 맞힌 여백 / 맞는 글에서 걸린 여백) 이다.
         */
        private val LEAPS = setOf(
            'ㅓ' to 'ㅏ',   // 하지먼→하지만 12.50 / 그거→그가 6.00
            'ㅓ' to 'ㅗ',   // 어늘→오늘 11.00 / 거장이나→고장이나 4.25
            'ㅘ' to 'ㅏ',   // 축화→축하 3.50 / 관의→간의 4.00
            'ㅕ' to 'ㅛ',   // 안녕하세여→안녕하세요 5.88 / 역을→욕을
        )

        /**
         * **`내`/`네`는 건드리지 않는다.** 맞춤법에 이걸 정하는 규칙이 없다 —
         * 문맥만이 답을 아는데, 말뭉치는 두 신호가 서로 반대다:
         * 유니그램은 `내가`(11.38) > `네가`(10.13) 로 한쪽을 밀고, 바이그램은
         * 자리마다 뒤집힌다. 실제로 `아까 내가 나한테` 는 바로잡고
         * `네가 준 선물` 은 망가뜨린다 — 같은 규칙이 같은 힘으로 양쪽을 한다.
         *
         * 낱말 전체로 막는다. 음절 짝(ㅐ↔ㅔ)으로 막으면 `네일→내일` 까지 잃는다.
         */
        private val NEVER_TOUCH_AE = setOf(
            "내", "네", "내가", "네가", "내게", "네게", "내거", "네거", "내걸", "네걸",
            "내꺼", "네꺼", "내건", "네건",
        )
        // 실측으로 고른 혼동 짝. 짝마다 (맞힌 수 / 맞는 글을 건드린 수) 를 달아 둔다.
        private val PAIRS_CHO = listOf(
            'ㅅ' to 'ㅇ',   // 먹었서요→먹었어요 (1/0, 앞 받침 ㅅ/ㅆ 일 때만)
            // `ㄱ↔ㄲ` 는 뺐다. 말뭉치 네 벌에서 **한 번도 안 맞히면서**
            // `얼마나 춥던지 손이 곱았다` 를 `꼽았다` 로 만든다 (곱다는 말뭉치가 모르고
            // 꼽다는 안다). 된소리 짝은 대개 딴 낱말이지 오타가 아니다.
        )
        private val PAIRS_JUNG = listOf(
            'ㅐ' to 'ㅔ',   // 새로로→세로로 (1/0, ㅐ/ㅔ 바닥 적용)
            'ㅔ' to 'ㅐ',   // 네일→내일    (1/0, ㅐ/ㅔ 바닥 적용)
            'ㅒ' to 'ㅖ', 'ㅖ' to 'ㅒ',
            'ㅚ' to 'ㅙ',   // 뵈요→봬요 꼴 (0/0, 문법 규칙이 먼저 잡는다)
            'ㅙ' to 'ㅚ',
            'ㅘ' to 'ㅏ',   // 축화→축하 (건너뛰기 문턱)
            'ㅕ' to 'ㅛ',   // 안녕하세여→안녕하세요 (건너뛰기 문턱)
            // 'ㅛ' to 'ㅕ' 는 뺐다 — 맞힌 적이 한 번도 없으면서
            //   `간단히 요기만 하고` 를 `여기만` 으로 망가뜨렸다.
            'ㅓ' to 'ㅏ',   // 하지먼→하지만 (건너뛰기 문턱)
            'ㅓ' to 'ㅗ',   // 어늘→오늘 (건너뛰기 문턱)
            'ㅡ' to 'ㅢ',   // 회으→회의 (1/0)
            'ㅢ' to 'ㅡ',
            'ㅗ' to 'ㅙ',   // 됬→됐 (0/0, 출하본 규칙이 먼저 잡는다)
            'ㅜ' to 'ㅝ', 'ㅝ' to 'ㅜ',
        )
        private val PAIRS_JONG = listOf(
            'ㅅ' to 'ㅆ',   // 알겟습니다→알겠습니다 (1/0)
            'ㅁ' to 'ㅂ',   // 함니다→합니다 (3/2@2.25)
            'ㅂ' to 'ㅁ',
            'ㄴ' to 'ㄶ',   // 괜찬아요→괜찮아요, 만아서→많아서 (2/0)
            NO_JONG to 'ㄶ', // 안았→않았 (1/0)
            NO_JONG to 'ㅎ',
            'ㄴ' to 'ㄷ',   // 든는→듣는 (1/0)
            'ㄷ' to 'ㅌ',
            'ㅂ' to 'ㅄ',
        )

        /**
         * 굳은 `-이` 부사. 규칙으로 못 가리는 자리를 이게 메운다.
         *
         * 첩어 뒤(51항) 와 ㄱ받침 토박이말 뒤가 대부분이다. 둘 다 반례가 있어서
         * ("첩어인데 히" = 천천히·단단히·꼼꼼히·답답히, "ㄱ받침인데 히" = 엄격히·정확히·
         * 솔직히) 목록 말고는 길이 없다. ㅅ받침 것들은 규칙이 이미 잡지만, 되돌리는
         * 쪽에서 말뭉치를 안 보고도 끝내려고 흔한 것은 같이 적어 둔다.
         */
        val I_ADVERBS: Set<String> = setOf(
            // 첩어·준첩어 뒤
            "간간이", "겹겹이", "골골샅샅이", "곳곳이", "나날이", "다달이", "땀땀이", "몫몫이",
            "번번이", "샅샅이", "알알이", "앞앞이", "줄줄이", "짬짬이", "철철이", "틈틈이",
            "일일이", "집집이", "낱낱이", "곰곰이", "뿔뿔이", "구구절절이", "누누이", "번번이",
            // ㅅ받침 뒤
            "기웃이", "나긋나긋이", "남짓이", "뜨뜻이", "버젓이", "번듯이", "빠듯이", "지긋이",
            "깨끗이", "의젓이", "반듯이", "느긋이", "뚜렷이", "산뜻이", "오롯이", "따뜻이",
            // ㅂ불규칙 어간 뒤
            "가벼이", "괴로이", "기꺼이", "너그러이", "부드러이", "새로이", "쉬이", "외로이",
            "즐거이", "대수로이", "날카로이", "애처로이", "번거로이", "가까이", "고이", "헛되이",
            // '-하다'가 안 붙는 어간 뒤
            "같이", "굳이", "길이", "깊이", "높이", "많이", "실없이", "적이",
            "끊임없이", "하염없이", "어이없이", "틀림없이", "다름없이", "변함없이", "소용없이",
            "쓸데없이", "상관없이", "관계없이", "문제없이", "어김없이", "거침없이", "아낌없이",
            "빠짐없이", "남김없이", "여지없이", "시름없이", "사정없이", "온데간데없이", "속절없이",
            // 부사 뒤
            "더욱이", "생긋이", "오뚝이", "일찍이", "히죽이", "일일이",
            // ㄱ받침 토박이말 (51항 예외 무리)
            "깊숙이", "수북이", "축축이", "큼직이", "끔찍이", "나직이", "두둑이", "멀찍이",
            "삐죽이", "고즈넉이", "널찍이", "촉촉이",
        )

        /**
         * 출하본과 **똑같이** 배선하고 이것만 더 얹는다. [NBestCorrector] 도
         * [ConfusionFixer] 도 끄지 않는다 — 셋이 같이 돌아야 한다.
         */
        fun create(cacheDir: File, tuning: Tuning = Tuning()): (String) -> String {
            val spacer = Spacer(SpacingDictionary.open(cacheDir))
            return create(spacer, LanguageModel.open(cacheDir), tuning)
        }

        /** 이미 열어 둔 사전으로 만든다. */
        fun create(
            spacer: Spacer,
            lm: LanguageModel,
            tuning: Tuning = Tuning(),
            confusion: ConfusionFixer.Tuning = ConfusionFixer.Tuning(),
        ): (String) -> String {
            val context = ContextCorrector(lm, spacer)
            val engine = CorrectionEngine()
            engine.spacer = spacer
            engine.speller = Speller(spacer)
            engine.context = context
            val fixer = SpellingFixer(spacer, lm, ConfusionFixer(spacer, lm, confusion), tuning)
            engine.typoFixer = fixer
            val corrector = NBestCorrector(engine, spacer, lm)
            // **원문을 꽂아 주고 부른다.** 이 한 줄이 [SpellingFixer.typedByHand] 를
            // 살린다 — 없으면 띄어쓰기가 만든 조각을 오타로 착각한다.
            return { text ->
                fixer.source = text
                try {
                    corrector.correct(text)
                } finally {
                    fixer.source = null
                }
            }
        }
    }
}
