import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File
import kotlin.random.Random

/**
 * 띄어쓰기 품질 자. 주장은 없고 표만 찍는다 — 고치기 전후로 돌려 견주는 데 쓴다.
 *
 * 시험지는 두 갈래다.
 * - `chat.txt` (저장소에 붙어 있다): 실제로 이 키보드에 칠 법한 구어체.
 * - `SPELL_EVAL_FILES`: 말뭉치 밖 격식체(KorNLI/XNLI). 세션이 죽으면 다시 받아야 한다.
 */
fun main(args: Array<String>) {
    val limit = (System.getenv("EVAL_LIMIT") ?: "1500").toInt()
    val show = (System.getenv("EVAL_SHOW") ?: "0").toInt()
    showMangled = System.getenv("EVAL_MANGLED") == "1"

    val sets = LinkedHashMap<String, List<String>>()
    args.firstOrNull()?.let { path ->
        val file = File(path)
        if (file.exists()) sets["구어체"] = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
    System.getenv("SPELL_EVAL_FILES")?.split(',')?.filter { it.isNotBlank() }?.let { paths ->
        val out = LinkedHashSet<String>()
        for (path in paths) File(path).useLines { lines ->
            lines.drop(1).forEach { line ->
                line.split('\t').take(2).forEach { cell ->
                    val clean = cell.trim().trim('"')
                    if (clean.length in 6..60 && clean.count { it == ' ' } >= 2 &&
                        clean.all { it in '가'..'힣' || it == ' ' || it in ".,!?" }
                    ) out += clean.trimEnd('.', '!', '?', ',')
                }
            }
        }
        if (out.isNotEmpty()) sets["격식체"] = out.shuffled(Random(7)).take(limit)
    }
    if (sets.isEmpty()) { println("시험지가 없다."); return }

    val cache = File(System.getProperty("java.io.tmpdir"), "spell-eval")
    val spacer = Spacer(SpacingDictionary.open(cache))
    val engine = CorrectionEngine().apply {
        this.spacer = spacer
        this.speller = Speller(spacer)
        this.context = ContextCorrector(LanguageModel.open(cache), spacer)
    }

    for ((name, sample) in sets) {
        println("\n══ $name  ${sample.size}문장 ".padEnd(60, '═'))
        intact(engine, sample, show)
        oneGap(engine, sample, show)
        partial(engine, sample, "절반 지움", 0.5, show)
        bare(engine, sample, show)
    }
}

private fun breaksOf(text: String): Set<Int> {
    val at = sortedSetOf<Int>()
    var k = 0
    for (c in text) if (c == ' ') { if (k > 0) at += k } else k++
    return at
}

/** 1. 멀쩡한 글을 건드리는 비율. */
private fun intact(engine: CorrectionEngine, sample: List<String>, show: Int) {
    val touched = ArrayList<Pair<String, String>>()
    for (s in sample) {
        val out = engine.correctAll(s).text
        if (out != s) touched += s to out
    }
    println("1. 멀쩡한 글 건드림   %4d/%-4d = %5.2f%%   (낮을수록 좋다)"
        .format(touched.size, sample.size, 100.0 * touched.size / sample.size))
    touched.take(show).forEach { (a, b) -> println("     $a\n   → $b") }
}

/** 2. 공백 하나만 지운 것을 되돌리는 비율. */
private fun oneGap(engine: CorrectionEngine, sample: List<String>, show: Int) {
    val random = Random(11)
    var tried = 0; var fixed = 0; var harmed = 0
    val missed = ArrayList<String>()
    for (s in sample) {
        val spaces = s.indices.filter { s[it] == ' ' }
        if (spaces.isEmpty()) continue
        val at = spaces[random.nextInt(spaces.size)]
        val broken = s.removeRange(at, at + 1)
        val out = engine.correctAll(broken).text
        tried++
        when (out) {
            s -> fixed++
            broken -> missed += "$broken   (정답 $s)"
            else -> harmed++
        }
    }
    println("2. 공백 하나 복원     %4d/%-4d = %5.2f%%   망침 %d"
        .format(fixed, tried, 100.0 * fixed / maxOf(1, tried), harmed))
    missed.take(show).forEach { println("     $it") }
}

/** EVAL_MANGLED=1 이면 글자를 바꾼 문장(글자바뀜)을 찍는다. */
private var showMangled = false

/** 3. 공백을 [ratio] 만큼 지운 것. 경계 정밀도/재현율. */
private fun partial(engine: CorrectionEngine, sample: List<String>, label: String, ratio: Double, show: Int) {
    val random = Random(13)
    var hit = 0L; var got = 0L; var want = 0L; var exact = 0; var mangled = 0
    val bad = ArrayList<String>()
    for (gold in sample) {
        val sb = StringBuilder()
        for (c in gold) if (c == ' ' && random.nextDouble() < ratio) Unit else sb.append(c)
        val input = sb.toString()
        val out = engine.correctAll(input).text
        val g = breaksOf(gold)
        want += g.size
        if (out.replace(" ", "") != gold.replace(" ", "")) { mangled++; if (showMangled) println("   ✗ $input → $out"); continue }
        val p = breaksOf(out)
        got += p.size
        hit += p.count { it in g }
        if (g == p) exact++ else if (bad.size < show) bad += "$input\n   → $out\n   ✓ $gold"
    }
    val prec = 100.0 * hit / maxOf(1, got)
    val rec = 100.0 * hit / maxOf(1, want)
    println("3. %s        정밀도 %5.1f  재현율 %5.1f  F1 %5.1f  통째 %5.1f%%  글자바뀜 %d"
        .format(label, prec, rec, 2 * prec * rec / maxOf(0.01, prec + rec), 100.0 * exact / sample.size, mangled))
    bad.forEach { println("     $it") }
}

/** 4. 공백을 전부 지운 것. 전체교정이 만나는 최악. */
private fun bare(engine: CorrectionEngine, sample: List<String>, show: Int) {
    var hit = 0L; var got = 0L; var want = 0L; var exact = 0; var mangled = 0
    val bad = ArrayList<String>()
    for (gold in sample) {
        val input = gold.replace(" ", "")
        val out = engine.correctAll(input).text
        val g = breaksOf(gold)
        want += g.size
        if (out.replace(" ", "") != input) { mangled++; if (showMangled) println("   ✗ $input → $out"); continue }
        val p = breaksOf(out)
        got += p.size
        hit += p.count { it in g }
        if (g == p) exact++ else if (bad.size < show) bad += "$input\n   → $out\n   ✓ $gold"
    }
    val prec = 100.0 * hit / maxOf(1, got)
    val rec = 100.0 * hit / maxOf(1, want)
    println("4. 전부 지움          정밀도 %5.1f  재현율 %5.1f  F1 %5.1f  통째 %5.1f%%  글자바뀜 %d"
        .format(prec, rec, 2 * prec * rec / maxOf(0.01, prec + rec), 100.0 * exact / sample.size, mangled))
    bad.forEach { println("     $it") }
}
