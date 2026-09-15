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
            if (lm != null) engine.context = ContextCorrector(lm, spacer)

            // longSpacer 는 비워 둔다. 엔진이 알아서 spacer.spaceLong 으로 되돌아간다.
            // typoFixer 는 Kiwi 자리다. v1 에는 없다.

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
     */
    private fun correctGlued(text: String): CorrectionResult {
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
