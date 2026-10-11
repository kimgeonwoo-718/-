import com.spellkeyboard.core.correct.Correction
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * 문장을 잔뜩 넣고 **엔진이 무엇을 건드렸는지** 한 번에 뽑아 종류별로 센다. 시험지는 `build-sheet.py` 가 만든다.
 *
 * 써 보다가 하나씩 발견하던 오교정('먹은거지 → 먹은 건지')을 한꺼번에 모으려고 만들었다(2026-10-11). 고친 것을
 *   - 띄어쓰기만 바꾼 것 / 글자를 바꾼 것
 *   - 어느 단계가 바꿨는지(맞춤법 규칙 · 띄어쓰기 · 문맥 디코더 · 헷갈리는 말)
 * 로 나누고, 같은 고침(from → to)을 묶어 많은 순으로 찍는다. 글자를 바꾼 것은 자모가 어떻게 바뀌었는지(받침 +ㄴ, ㅐ→ㅔ …)로도 묶는다.
 *
 *   MINE_MODE=full      전체교정 길(correctAll). 기본은 실시간 길(어절마다 스페이스 → correctTail).
 *   MINE_TOP=40         묶음을 몇 개까지 찍나.
 *   MINE_EXAMPLES=1     묶음마다 보기 문장 몇 개.
 *   MINE_TSV=경로       바뀐 문장을 전부 tsv 로 남긴다(종류, 원문, 결과, 고침들).
 */
fun main(args: Array<String>) {
    val cache = File(System.getProperty("java.io.tmpdir"), "spell-live")
    val engine = CorrectionEngine()
    val spacer = runCatching { Spacer(SpacingDictionary.open(cache)) }
        .onSuccess { engine.spacer = it; engine.speller = Speller(it) }
        .getOrNull()
    runCatching { LanguageModel.open(cache) }.onSuccess { engine.context = ContextCorrector(it, spacer) }

    val full = System.getenv("MINE_MODE") == "full"
    val top = System.getenv("MINE_TOP")?.toIntOrNull() ?: 40
    val examples = System.getenv("MINE_EXAMPLES")?.toIntOrNull() ?: 1
    val tsv = System.getenv("MINE_TSV")?.let { File(it).printWriter() }

    for (path in args) {
        val lines = File(path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        var changed = 0
        var letterChanged = 0
        val byStage = HashMap<String, Int>()
        val letterGroups = HashMap<String, Group>()
        val spaceGroups = HashMap<String, Group>()
        val jamoGroups = HashMap<String, Group>()

        for (line in lines) {
            val original = squeeze(line)
            val (out, corrections) =
                if (full) engine.correctAll(line).let { squeeze(it.text) to it.corrections } else typeAndCollect(engine, line)
            if (out == original) continue
            changed++
            val letters = strip(out) != strip(original)
            if (letters) letterChanged++
            for (c in corrections) {
                if (c.from == c.to) continue
                val spacingOnly = strip(c.from) == strip(c.to)
                byStage.merge("${c.reason}·${if (spacingOnly) "띄움" else "글자"}", 1, Int::plus)
                val groups = if (spacingOnly) spaceGroups else letterGroups
                groups.getOrPut("${c.reason}\t${c.from} → ${c.to}") { Group() }.add(original, out, examples)
                if (!spacingOnly) {
                    jamoGroups.getOrPut("${jamoSignature(c.from, c.to)}\t${c.reason}") { Group() }.add(original, out, examples)
                }
            }
            tsv?.println(
                listOf(
                    if (letters) "글자" else "띄움", original, out,
                    corrections.joinToString(" / ") { "${it.reason}:${it.from}→${it.to}" }
                ).joinToString("\t")
            )
        }

        val n = lines.size
        println("== ${File(path).name} [${if (full) "전체교정" else "실시간"}] ${n}문장")
        println(
            "바뀐 문장 %d (%s) — 띄어쓰기만 %d (%s), 글자 바뀜 %d (%s)".format(
                changed, pct(changed, n), changed - letterChanged, pct(changed - letterChanged, n), letterChanged, pct(letterChanged, n)
            )
        )
        println("단계·종류별 고침 수: " + byStage.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" })
        section("글자를 바꾼 고침 (같은 것끼리, 많은 순)", letterGroups, top)
        section("글자 바뀜을 자모로 묶은 것", jamoGroups, top)
        section("띄어쓰기만 바꾼 고침 (많은 순)", spaceGroups, top)
        println()
    }
    tsv?.close()
}

class Group {
    var count = 0
    val examples = ArrayList<String>()
    fun add(original: String, out: String, keep: Int) {
        count++
        if (examples.size < keep) examples += "$original  ⇒  $out"
    }
}

fun section(title: String, groups: Map<String, Group>, top: Int) {
    if (groups.isEmpty()) return
    println("-- $title: ${groups.size}가지, ${groups.values.sumOf { it.count }}번")
    for ((key, group) in groups.entries.sortedWith(compareByDescending<Map.Entry<String, Group>> { it.value.count }.thenBy { it.key }).take(top)) {
        val (stage, what) = key.split('\t').let { it[0] to it.getOrElse(1) { "" } }
        println("  %4d  %-10s %s".format(group.count, what.ifEmpty { stage }, if (what.isEmpty()) "" else "[$stage]"))
        for (example in group.examples) println("          $example")
    }
}

/** 어절마다 스페이스를 누르는 것처럼 치면서 엔진이 고친 것을 모은다(Live.kt 의 type 과 같은 길). */
fun typeAndCollect(engine: CorrectionEngine, sentence: String): Pair<String, List<Correction>> {
    val buffer = StringBuilder()
    val corrections = ArrayList<Correction>()
    for (word in sentence.trim().split(Regex("\\s+"))) {
        buffer.append(word)
        val before = buffer.toString().takeLast(64)
        val cut = buffer.length - before.length
        engine.correctTail(before)?.let { tail ->
            val start = cut + before.length - tail.deleteBefore
            buffer.replace(start, buffer.length, tail.replacement)
            corrections += tail.corrections
        }
        buffer.append(' ')
    }
    return squeeze(buffer.toString()) to corrections
}

/**
 * 글자가 어떻게 바뀌었나. 공백을 뺀 두 말이 **한 음절만** 다르면 그 음절의 자모 차이로(받침 +ㄴ, ㅐ→ㅔ, 초성 ㄷ→ㄸ),
 * 한 음절이 늘거나 줄었으면 그 음절로, 그 밖에는 '여러 곳'.
 */
fun jamoSignature(from: String, to: String): String {
    val a = strip(from)
    val b = strip(to)
    if (a.length == b.length) {
        val diff = a.indices.filter { a[it] != b[it] }
        if (diff.size != 1) return "여러 곳"
        val x = Hangul.decompose(a[diff[0]]) ?: return "한글 아닌 글자"
        val y = Hangul.decompose(b[diff[0]]) ?: return "한글 아닌 글자"
        val parts = ArrayList<String>()
        if (x.first != y.first) parts += "초성 ${Hangul.CHOSEONG[x.first]}→${Hangul.CHOSEONG[y.first]}"
        if (x.second != y.second) parts += "${Hangul.JUNGSEONG[x.second]}→${Hangul.JUNGSEONG[y.second]}"
        if (x.third != y.third) {
            parts += when {
                x.third == 0 -> "받침 +${Hangul.JONGSEONG[y.third]}"
                y.third == 0 -> "받침 −${Hangul.JONGSEONG[x.third]}"
                else -> "받침 ${Hangul.JONGSEONG[x.third]}→${Hangul.JONGSEONG[y.third]}"
            }
        }
        return parts.joinToString(" + ")
    }
    if (b.length == a.length + 1) {
        val k = a.indices.firstOrNull { a[it] != b[it] } ?: a.length
        if (a.substring(k) == b.substring(k + 1)) return "음절 +${b[k]}"
    }
    if (a.length == b.length + 1) {
        val k = b.indices.firstOrNull { a[it] != b[it] } ?: b.length
        if (a.substring(k + 1) == b.substring(k)) return "음절 −${a[k]}"
    }
    return "여러 곳"
}

fun squeeze(text: String) = text.trim().replace(Regex("\\s+"), " ")

fun strip(text: String) = text.replace(Regex("\\s+"), "")

fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(a * 100.0 / b)
