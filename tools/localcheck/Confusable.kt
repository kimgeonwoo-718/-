import com.spellkeyboard.core.correct.ConfusableRules
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.correct.NatNah
import com.spellkeyboard.core.correct.SpacingRules
import com.spellkeyboard.core.correct.SpellingRules
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * 헷갈리는 말(낫/낳)을 **세 가지 설정**으로 재서 견준다. 시험지는 `tools/confusable/eval/` 안의 tsv.
 *
 *   R   손으로 쓴 규칙표만 (ConfusableRules)
 *   M   학습된 모델만
 *   RM  둘 다 (지금 앱이 쓰는 것)
 *
 * 문장마다 **틀리게 바꾼 것**(대상 어절을 반대쪽으로 뒤집음)을 실시간 길(어절마다 correctTail)로 쳐서
 * 정답이 되는지(고침), **정답을 그대로 친 것**이 그대로인지(지킴)를 잰다. 줄 이름 끝의 '·전체' 는 같은 것을
 * 전체 교정 길(correctAll — 뒤 두 어절까지 본다)로 잰 값이다. 윈도우 PC 의 전체교정이 이 길이다.
 * 시험지 줄 앞이 KEEPNAT / KEEPNAH 인 것은 정답을 알 수 없는(둘 다 되는) 문장이다 — 쓴 그대로 둬야 한다(지킴만 잰다).
 */
fun main(args: Array<String>) {
    val cache = File(System.getProperty("java.io.tmpdir"), "spell-live")
    val spacer = runCatching { Spacer(SpacingDictionary.open(cache)) }.getOrNull()
    val lm = runCatching { LanguageModel.open(cache) }.getOrNull()
    fun engine(rules: Boolean, model: Boolean) = CorrectionEngine(
        textRules = (if (rules) SpellingRules.CONTEXT else SpellingRules.CONTEXT - ConfusableRules.RULES.toSet()) + SpacingRules.DEFAULT
    ).apply {
        if (spacer != null) { this.spacer = spacer; speller = Speller(spacer) }
        if (lm != null) context = ContextCorrector(lm, spacer)
        confusableEnabled = model
    }
    val engines = listOf("R" to engine(rules = true, model = false), "M" to engine(rules = false, model = true), "RM" to engine(rules = true, model = true))
    val full: (CorrectionEngine, String) -> String = { e, s -> e.correctAll(s).text.trim().replace(Regex("\\s+"), " ") }
    val live: (CorrectionEngine, String) -> String = ::type
    val configs = engines.flatMap { (n, e) -> listOf(Triple(n, e, live), Triple("$n·전체", e, full)) }
    val show = System.getenv("LIVE_SHOW")?.toIntOrNull() ?: 0
    val showConfig = System.getenv("CONF_SHOW") ?: "M"
    val oneRun = Regex("""[^가-힣]*([가-힣]+)[^가-힣]*""")

    for (path in args) {
        val rows = File(path).readLines().filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
            .map { it.substringBefore('\t') to it.substringAfter('\t').trim() }
        println("== ${File(path).name}  (${rows.size}문장)")
        val fixed = HashMap<String, Int>(); val kept = HashMap<String, Int>(); var fixTotal = 0
        val misses = HashMap<String, MutableList<String>>(); val broken = HashMap<String, MutableList<String>>()
        for ((label, gold) in rows) {
            val keepOnly = label.startsWith("KEEP")
            val want = if (label.endsWith("NAH")) 1 else 0
            val words = gold.split(" ")
            val flipped = words.toMutableList()
            var targets = 0
            for ((i, w) in words.withIndex()) {
                val run = oneRun.matchEntire(w) ?: continue
                val token = run.groups[1]!!.value
                val cls = NatNah.classOf(token) ?: continue
                if (NatNah.skip(token) || cls != want) continue
                val moved = NatNah.convert(token, 1 - cls) ?: continue
                flipped[i] = w.substring(0, run.groups[1]!!.range.first) + moved + w.substring(run.groups[1]!!.range.last + 1)
                targets++
            }
            if (targets == 0) continue
            val wrong = flipped.joinToString(" ")
            if (!keepOnly) fixTotal++
            for ((name, e, run) in configs) {
                if (!keepOnly) {
                    val out = run(e, wrong)
                    if (out == gold) fixed.merge(name, 1, Int::plus) else misses.getOrPut(name) { mutableListOf() } += "  $wrong\n    → $out\n    ✓ $gold"
                }
                val again = run(e, gold)
                if (again == gold) kept.merge(name, 1, Int::plus) else broken.getOrPut(name) { mutableListOf() } += "  $gold\n    → $again"
            }
        }
        for ((name, _, _) in configs) {
            println("  %-6s 고침 %3d / %3d (%s)   지킴 %3d / %3d (%s)".format(name, fixed[name] ?: 0, fixTotal, pct(fixed[name] ?: 0, fixTotal),
                kept[name] ?: 0, kept[name]?.let { it + (broken[name]?.size ?: 0) } ?: 0, pct(kept[name] ?: 0, (kept[name] ?: 0) + (broken[name]?.size ?: 0))))
        }
        if (show > 0) {
            println("  -- [$showConfig] 못 고친 것"); misses[showConfig]?.take(show)?.forEach(::println)
            println("  -- [$showConfig] 멀쩡한 걸 건드린 것"); broken[showConfig]?.take(show)?.forEach(::println)
        }
    }
}

fun type(engine: CorrectionEngine, sentence: String): String {
    val buffer = StringBuilder()
    for (word in sentence.trim().split(Regex("\\s+"))) {
        buffer.append(word)
        val before = buffer.toString().takeLast(64)
        val cut = buffer.length - before.length
        engine.correctTail(before)?.let { tail ->
            val start = cut + before.length - tail.deleteBefore
            buffer.replace(start, buffer.length, tail.replacement)
        }
        buffer.append(' ')
    }
    return buffer.toString().trim().replace(Regex("\\s+"), " ")
}

fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(a * 100.0 / b)
