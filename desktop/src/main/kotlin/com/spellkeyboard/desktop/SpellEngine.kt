package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.correct.CorrectionResult
import com.spellkeyboard.core.correct.SelfTestSamples
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * 사전이 어디까지 올라왔나. 셋 다 글은 고쳐 준다 — 아래로 갈수록 잘 고친다.
 */
enum class EngineLevel(val label: String) {
    /** 사전을 못 열었다. 정규식 규칙만 돈다. */
    RULES("규칙만"),

    /** 형태소 사전이 올라왔다. 어절 하나씩 띄어쓰기·맞춤법. */
    DICTIONARY("사전"),

    /** 언어모델까지 올라왔다. 문맥을 보고 고친다. 이게 제 성능이다. */
    CONTEXT("문맥까지"),
}

sealed interface EngineState {
    data object Loading : EngineState

    /**
     * @param trouble 사전을 못 연 까닭. 열렸으면 null. 성능이 떨어질 뿐 프로그램은 돈다.
     */
    data class Ready(
        val level: EngineLevel,
        val millis: Long,
        val trouble: String? = null,
    ) : EngineState
}

/**
 * 교정 한 번의 결과와 걸린 시간.
 *
 * @param guessedSpacing 원문에 공백이 없어 엔진이 **추측해서** 띄운 자리가 있나.
 *   있으면 창이 그렇다고 말해 줘야 한다 — 복원이 아니라 추측이다.
 */
data class Corrected(
    val result: CorrectionResult,
    val millis: Long,
    val guessedSpacing: Boolean = false,
)

/**
 * 사전 푸는 폴더.
 *
 * **매번 새로 만드는 임시 폴더를 쓰면 안 된다.** 푼 파일을 mmap 으로 물고 있어서 JVM 이
 * 살아 있는 동안 지워지지 않는다. 임시 폴더를 쓰면 실행할 때마다 62MB 가 쌓인다.
 * 사람마다 고정된 한 곳을 쓰고, 끝날 때 지우려 들지 마라.
 */
fun defaultCacheDir(): File {
    val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    return File(base, "SpellDesktop").also { it.mkdirs() }
}

/**
 * 교정 엔진을 감싸 창이 쓰기 좋게 만든 것.
 *
 * ## 스레드
 *
 * 사전 열기와 교정을 **단일 스레드 하나**에 몰아 놓았다. 두 가지 이유다.
 *
 * 1. `SpacingDictionary.open` 과 `LanguageModel.open` 은 캐시 폴더에 `*.tmp` 라는
 *    **고정된 이름**으로 풀었다가 이름을 바꾼다. 둘을 동시에 부르면 그 파일을 두고 다툰다.
 * 2. 엔진의 낱말 분석 캐시는 monitor 안에서 계산한다. 긴 글 교정이 짧은 교정을 막으므로
 *    어차피 줄을 서야 한다면 줄 하나로 세우는 편이 낫다.
 *
 * 덕분에 사전이 아직 안 올라왔을 때 교정을 요청해도 **그냥 뒤에 줄을 선다.** 버튼을
 * 잠글 필요가 없다.
 *
 * 결과는 [toUi] 를 거쳐 돌려준다. 기본값은 Swing 의 EDT 다. 시험에서는 `{ it() }` 을
 * 넣어 그 자리에서 받는다 — 그래야 화면 없이도 돌릴 수 있다.
 */
class SpellEngine(
    private val cacheDir: File = defaultCacheDir(),
    private val toUi: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_NAME).apply { isDaemon = true }
    }

    /** 규칙만으로도 돌아가므로 처음부터 만들어 둔다. 사전은 올라오는 대로 꽂는다. */
    private val engine = CorrectionEngine()

    /** 전처리에서 직접 부른다. 엔진이 들고 있지만 밖으로 읽어 주지 않는다. */
    @Volatile
    private var spacer: Spacer? = null

    /**
     * 사전과 언어모델이 둘 다 올라왔을 때만 만들어진다. 없으면 예전 길([correctGlued])로 간다.
     *
     * 이것 하나가 띄어쓰기를 맡는다 — 엔진 뒤에 붙어, 엔진이 모르는 어절을 남긴 자리
     * 둘레만 다시 고르고, 사전에 있는 복합명사는 통째로 지킨다. 자세한 것은
     * [NBestCorrector] 의 머리말에 있다.
     */
    @Volatile
    private var nbest: NBestCorrector? = null

    /**
     * 엔진의 `typoFixer` 자리에 꽂은 것. [SpellingFixer.source] 에 **사람이 친 원문**을
     * 넣어 줘야 자모 오타 규칙이 깨어난다 — 그래서 여기 따로 들고 있는다.
     */
    @Volatile
    private var spelling: SpellingFixer? = null

    /**
     * 맨 마지막에 도는 띄어쓰기 겹. 이것 하나가 **이미 띄어 쓴 글**을 맡는다 —
     * 그 전까지는 붙여 쓴 덩어리가 없으면 띄어쓰기 기계가 통째로 안 돌았다.
     */
    @Volatile
    private var tokenSpacer: TokenSpacer? = null

    @Volatile
    var state: EngineState = EngineState.Loading
        private set

    /**
     * 사전과 언어모델을 연다. 즉시 돌아오고, 다 되면 [onState] 를 부른다.
     *
     * 둘 다 실패해도 던지지 않는다 — 규칙만으로 돌리고 까닭을 [EngineState.Ready.trouble]
     * 에 담는다. 사용자에게 "안 된다" 대신 "덜 된다" 를 보여 주는 편이 낫다.
     */
    fun load(onState: (EngineState) -> Unit) {
        worker.execute {
            val started = System.nanoTime()
            val troubles = mutableListOf<String>()

            // 1) 형태소 사전. open 이 던지는 것은 전부 unchecked 라 runCatching 으로 받는다.
            val spacer = runCatching { Spacer(SpacingDictionary.open(cacheDir)) }
                .onFailure { troubles += "형태소 사전을 못 열었다: ${describe(it)}" }
                .getOrNull()
            if (spacer != null) {
                engine.spacer = spacer
                engine.speller = Speller(spacer)
                this.spacer = spacer
            }

            // 2) 언어모델. 이게 올라와야 문맥을 본다.
            val lm = runCatching { LanguageModel.open(cacheDir) }
                .onFailure { troubles += "언어모델을 못 열었다: ${describe(it)}" }
                .getOrNull()
            // spacer 가 null 이어도 넣는다. 문맥 교정은 사전 없이도 절반은 한다.
            val context = if (lm != null) ContextCorrector(lm, spacer) else null
            if (context != null) engine.context = context

            // 둘 다 올라왔으면 띄어쓰기를 [NBestCorrector] 에 넘긴다. 생성자가 엔진의
            // longSpacer 를 복합어 거부권으로 감싸므로, 여기서 longSpacer 를 따로 건드리면
            // 안 된다. 하나라도 없으면 nbest 는 null 로 남고 예전 길이 그대로 돈다.
            if (spacer != null && lm != null && context != null) {
                // 맞춤법이 먼저(엔진 안의 typoFixer 자리), 띄어쓰기가 나중(엔진 밖의 재분절).
                //
                // [ConfusionFixer] 는 사람이 늘 틀리는 혼동쌍만 골라 잡는다. 자모를 흔들어
                // 후보를 만들고 언어모델 여백으로 고르는 길과 죽은 `Speller` 를 되살리는
                // 길은 둘 다 재 봤고 둘 다 손해였다 — 앞의 것은 dev 전체 F1 을 0.95 에서
                // 0.72~0.91 로 떨어뜨렸고, 뒤의 것은 test 에서 출력이 한 글자도 안 바뀌면서
                // 맞는 글(`날씨가 개어서` → `깨어서`)을 새로 망가뜨렸다.
                //
                // 이것만 test 145행에서 값을 냈다: `spelling` F1 0.7500 → 1.0000(12/12),
                // 전체 F1 0.9481 → 0.9647, `mutated` 4행 → 1행, 손상은 1/73 그대로.
                // 나머지 여덟 갈래는 tp/fp/fn 이 한 자리도 안 움직였다.
                // [SpellingFixer] 가 [ConfusionFixer] 를 감싸 세 갈래를 더 본다:
                // -이/-히(51항), 굳은 꼴(뵈요→봬요, -세여→-세요), 그리고 **말뭉치가
                // 모르는 어절에서만** 여는 자모 오타 하나. 새 test 194행에서 F1
                // 0.8183 → 0.8702, 통째 정답 117 → 130 이고, 옛 말뭉치 290행은
                // 한 글자도 안 달라진다.
                val fixer = SpellingFixer(spacer, lm, ConfusionFixer(spacer, lm))
                engine.typoFixer = fixer
                spelling = fixer
                nbest = NBestCorrector(engine, spacer, lm)
                // 맨 마지막 겹. 재분절이 안 도는 글 — 이미 띄어 쓴 평범한 글 — 의
                // 어절을 하나씩 사전에 다시 물어본다. 새 test 에서 통째 정답 +21 행.
                tokenSpacer = TokenSpacer(spacer, lm)
            }

            val level = when {
                spacer != null && lm != null -> EngineLevel.CONTEXT
                spacer != null -> EngineLevel.DICTIONARY
                else -> EngineLevel.RULES
            }
            val ready = EngineState.Ready(
                level = level,
                millis = millisSince(started),
                trouble = troubles.joinToString("\n").ifEmpty { null },
            )
            state = ready
            toUi { onState(ready) }
        }
    }

    /**
     * 글 전체를 고친다. 긴 글은 엔진이 알아서 문장 단위로 잘라 문맥을 넘겨 준다.
     *
     * `correct` 가 아니라 `correctAll` 인 까닭: 긴 글에서 `correct` 는 언어모델 해독을
     * 건너뛴다. 같은 글에 대해 `correctAll` 이 늘 더 많이 잡는다.
     */
    fun correctAll(text: String, onResult: (Corrected) -> Unit) {
        worker.execute {
            val started = System.nanoTime()
            val corrected = Corrected(correctGlued(text), millisSince(started), engine.hasGluedRun(text))
            toUi { onResult(corrected) }
        }
    }

    /**
     * 붙여 쓴 글을 엔진에 넣기 전에 손질하고, 한 번 더 돌린다.
     *
     * ## 왜 필요한가
     *
     * `Spacer.space` 는 **한글 아닌 글자가 하나라도 끼면 무조건 null** 이다. 마침표
     * 하나, 숫자 하나면 그 어절은 손도 안 댄 채 나간다. 그래서 손질 없이 넣으면:
     *
     *     오늘은3시에친구를만나기로했다        → 그대로 (공백 0개)
     *     어제는집에있었다.비가왔기때문이다     → 첫 문장만 띄고 나머지는 붙은 채
     *
     * 실제로 붙여넣는 글은 거의 다 숫자나 마침표를 품고 있으므로, 이것을 안 하면
     * 프로그램이 제일 흔한 경우에 아무 일도 안 하는 것처럼 보인다.
     *
     * 재 본 값 (붙여쓴 글 50문장): 경계 F1 85.3 → 96.1, 문장 통째 정답 48% → 66%.
     * 값은 666자에 135ms 로, 버튼 한 번 누르는 데 0.1초다.
     *
     * ## 왜 두 번 돌리나
     *
     * 첫 회차의 텍스트 규칙이 `있었다.비가` 같은 자리에 공백을 넣어 주고, 그러면
     * 두 번째 회차에서 비로소 뒤 문장이 사전에 닿는다. 마침표 앞에 정규식으로 공백을
     * 넣는 방법도 재 봤는데 쉼표·물음표를 손으로 하나씩 추가해야 해서 버렸다.
     *
     * 붙여 쓴 덩어리가 없으면 아무것도 하지 않는다 — 평범한 글은 예전 그대로 한 번만
     * 돈다. 바르게 띄어 쓴 50문장에서 이 길은 한 번도 타지 않았다.
     *
     * ## 이제는 [NBestCorrector] 가 맡는다
     *
     * 사전과 언어모델이 둘 다 올라왔으면 이 길을 [NBestCorrector.correctResult] 에
     * 넘긴다. 그쪽이 여기 적힌 전처리·두 번 돌리기를 **그대로** 품고 있고, 그 뒤에
     * 재분절을 한 겹 얹는다. 아래 몸통은 사전이 덜 올라왔을 때만 도는 되돌림 길이다.
     */
    private fun correctGlued(text: String): CorrectionResult {
        nbest?.let { corrector ->
            // 원문을 꽂아 준다. [SpellingFixer.typedByHand] 가 이것으로 "사람이 친
            // 어절" 과 "띄어쓰기가 갈라 놓은 조각" 을 가른다 — 안 꽂으면 자모 오타
            // 규칙이 조용히 쉰다(손상이 아니라 재현율로 값을 치른다).
            spelling?.source = text
            val engineOut = try {
                corrector.correctResult(text)
            } finally {
                spelling?.source = null
            }
            val layer = tokenSpacer ?: return engineOut
            // 교정 내역은 엔진이 낸 것 그대로 둔다. 이 겹은 음절을 안 건드리고
            // 공백만 옮기므로 내역에 넣으면 같은 교정이 두 번 세어진다
            // ([NBestCorrector.correctResult] 와 같은 태도).
            return CorrectionResult(text, layer.correct(engineOut.text), engineOut.corrections)
        }

        if (!engine.hasGluedRun(text)) return engine.correctAll(text)

        val first = engine.correctAll(preSpace(text))
        val second = engine.correctAll(first.text)
        // 사용자가 넣은 원문을 기준으로 합친다. 두 회차 각각의 original 은 중간 결과다.
        return CorrectionResult(text, second.text, first.corrections + second.corrections)
    }

    /**
     * 한글과 다른 글자가 섞인 어절을 한글 덩어리로 잘라 각각 띄운다.
     *
     * **순한글 어절은 건드리지 않는다.** 그쪽은 엔진이 문맥까지 보고 푸는 편이 늘 낫다.
     * 순한글까지 손대 보니 `재미있었다` 를 `재미 있었다` 로 갈라 놓는 손상이 생겼다.
     *
     * 예외가 하나 있다 — 언어모델을 못 열었으면 순한글 덩어리를 풀어 줄 것이 없으므로
     * 그때는 모든 어절을 미리 띄운다 (경계 F1 47.9 → 91.4, 새로 생기는 손상은 없었다).
     */
    private fun preSpace(text: String): String {
        val sp = spacer ?: return text
        val noLanguageModel = engine.context == null

        return text.split(AROUND_WHITESPACE).joinToString("") { token ->
            when {
                token.isBlank() -> token
                token.none { it.isHangulSyllable() } -> token
                token.all { it.isHangulSyllable() } ->
                    if (noLanguageModel) sp.spaceLongIfWorth(token) else token
                else -> loosenMixedToken(sp, token)
            }
        }
    }

    private fun loosenMixedToken(sp: Spacer, token: String): String = buildString {
        var i = 0
        var previousWasHangul = false
        while (i < token.length) {
            val c = token[i]
            if (c.isHangulSyllable()) {
                var end = i
                while (end < token.length && token[end].isHangulSyllable()) end++
                append(sp.spaceLongIfWorth(token.substring(i, end)))
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

    private fun Spacer.spaceLongIfWorth(chunk: String): String =
        if (chunk.length > MIN_SPLIT_SYLLABLES) spaceLong(chunk) ?: chunk else chunk

    private fun Char.isHangulSyllable() = this in '가'..'힣'

    /**
     * 엔진이 제대로 배선됐는지 스스로 확인한다. 통과하면 빈 목록이다.
     *
     * 사전이 하나도 안 올라온 맨 엔진은 여기서 몇 개 떨어진다. 그래서 "사전이 진짜로
     * 붙었나" 를 재는 자로 쓸 수 있다.
     */
    fun selfTest(onResult: (List<String>) -> Unit) {
        worker.execute {
            val failures = SelfTestSamples.ALL.mapNotNull { (input, expected) ->
                val actual = engine.correct(input).text
                if (actual == expected) null else "$input → $actual  (기대: $expected)"
            }
            toUi { onResult(failures) }
        }
    }

    fun close() {
        worker.shutdown()
    }

    private fun millisSince(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    /** 예외 메시지가 비어 있는 것들이 있어 클래스 이름이라도 남긴다. */
    private fun describe(error: Throwable) =
        error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "알 수 없는 오류")

    companion object {
        const val WORKER_NAME = "spell-engine"

        /** 이보다 짧은 한글 덩어리는 미리 띄워 봐야 얻을 것이 없다. */
        private const val MIN_SPLIT_SYLLABLES = 3

        /** 공백을 버리지 않고 어절을 가른다. 원문의 줄바꿈·들여쓰기를 그대로 살린다. */
        private val AROUND_WHITESPACE = Regex("(?<=\\s)|(?=\\s)")
    }
}
