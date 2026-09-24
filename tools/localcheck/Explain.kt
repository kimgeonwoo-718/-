import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.io.File

/** 디코더가 왜 그렇게 골랐는지 풀어 본다. 점수와 후보를 그대로 찍는다. */
fun main(args: Array<String>) {
    val cache = File(System.getProperty("java.io.tmpdir"), "spell-explain")
    val spacer = Spacer(SpacingDictionary.open(cache))
    val lm = LanguageModel.open(cache)
    val context = ContextCorrector(lm, spacer)
    val engine = CorrectionEngine().apply {
        cheonjiin = System.getenv("SPELL_CJI") == "1"; this.spacer = spacer; this.context = context
    }

    // 한 음절 조각을 조사·어미로 볼 것이냐가 '잘해결됐어' 류를 가른다. 사전 판정과
    // 어절 빈도를 나란히 찍는다 — SINGLE_WORD_MIN_LN 을 정할 때 본 표다.
    println("한 음절: " + "잘더다안나가서어오는을이과와도만은".map {
        it + "=" + spacer.couldBeBound(it.toString()) + "/" +
            (lm.lnCount(it.toString())?.let { c -> "%.1f".format(c) } ?: "모름")
    }.joinToString(" "))
    for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
        println("═".repeat(70))
        println("입력: $line")
        println("결과: " + engine.correctAll(line).text)
        for (word in line.split(" ")) {
            if (word.isBlank()) continue
            println("  $word  lm=" + (lm.lnCount(word)?.let { "%.2f".format(it) } ?: "모름") +
                "  분석비용=" + (spacer.cost(word)) +
                "  한어절=" + spacer.isWellFormed(word) +
                "  사전나눔=" + (spacer.space(word) ?: "안 나눔") +
                "  " + spacer.describe(word))
        }
        println(context.explain(line, LanguageModel.BOS))
    }
}
