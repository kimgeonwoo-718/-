import com.spellkeyboard.core.correct.Correction
import com.spellkeyboard.core.correct.SpacingRules
import com.spellkeyboard.core.correct.SpellingRules
import com.spellkeyboard.core.correct.TextRule
import java.io.File

/**
 * 규칙 하나하나가 **멀쩡한 글에서** 무엇을 바꾸는지 본다(rule-audit.sh). 엔진 전체를 돌리는 mine.sh 는 "맞춤법" 단계가 바꾼 것을
 * 한데 묶어 어느 규칙이 범인인지 모른다. 여기서는 규칙마다 따로 돌려 규칙 번호·정규식과 함께 찍는다.
 *
 * 2026-10-13 에 만들었다. 표 규칙이 낱말 **한가운데**에서도 걸려 '물건들이 → 물건드리', '문화일보 → 문파일보',
 * '포스트잇을 → 포스트있을' 같은 오교정이 수십 개 나왔다 — 낱말 하나씩 써 보다가는 끝내 못 찾을 종류다.
 *
 * 인자: `이름:파일` 여럿. 이름이 clean 으로 시작하면 멀쩡한 글(기사·위키·격식체), 아니면 사람이 친 글로 센다.
 *   AUDIT_TOP=20      규칙마다 보기 몇 개
 *   AUDIT_MAX=200000  파일마다 몇 줄까지
 */
fun main(args: Array<String>) {
    val top = System.getenv("AUDIT_TOP")?.toIntOrNull() ?: 20
    val max = System.getenv("AUDIT_MAX")?.toIntOrNull() ?: 200_000
    val words = HashMap<String, IntArray>()           // 어절 → [멀쩡한 글 수, 친 글 수]
    val sentences = ArrayList<Pair<String, Int>>()    // 문장, 0 멀쩡 / 1 친 글
    for (arg in args) {
        val label = arg.substringBefore(':')
        val kind = if (label.startsWith("clean")) 0 else 1
        File(arg.substringAfter(':')).useLines { lines ->
            for (line in lines.take(max)) {
                val s = line.trim()
                if (s.isEmpty()) continue
                sentences += s to kind
                for (w in s.split(Regex("\\s+"))) words.getOrPut(w) { IntArray(2) }[kind]++
            }
        }
    }
    System.err.println("어절 ${words.size}개, 문장 ${sentences.size}개")

    report("어절 규칙(SpellingRules.WORD)", SpellingRules.WORD, top) { rule, hit ->
        for ((w, n) in words) {
            val out = rule.apply(w, ArrayList())
            if (out != w) hit("$w → $out", n)
        }
    }
    report("문장 규칙(SpellingRules.CONTEXT + SpacingRules.DEFAULT)", SpellingRules.CONTEXT + SpacingRules.DEFAULT, top) { rule, hit ->
        val n = IntArray(2)
        for ((s, kind) in sentences) {
            val sink = ArrayList<Correction>()
            rule.apply(s, sink)
            n[0] = 0; n[1] = 0; n[kind] = 1
            for (c in sink) hit("${c.from} → ${c.to}", n)
        }
    }
}

fun report(title: String, rules: List<TextRule>, top: Int, run: (TextRule, (String, IntArray) -> Unit) -> Unit) {
    println("# $title — 규칙 ${rules.size}개")
    for ((i, rule) in rules.withIndex()) {
        val hits = HashMap<String, IntArray>()
        run(rule) { key, n -> hits.getOrPut(key) { IntArray(2) }.let { it[0] += n[0]; it[1] += n[1] } }
        if (hits.isEmpty()) continue
        val clean = hits.values.sumOf { it[0] }
        val typed = hits.values.sumOf { it[1] }
        println("\n[$i] ${pattern(rule)}   멀쩡한 글 $clean · 친 글 $typed")
        // 멀쩡한 글에서 걸린 것이 먼저 — 오교정 후보다
        for ((key, n) in hits.entries.sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }.thenByDescending { it.value[1] }).take(top)) {
            println("    %5d %5d  %s".format(n[0], n[1], key))
        }
    }
}

fun pattern(rule: TextRule): String =
    runCatching {
        val field = TextRule::class.java.getDeclaredField("regex").apply { isAccessible = true }
        (field.get(rule) as Regex).pattern.let { if (it.length > 160) it.take(160) + "…" else it }
    }.getOrDefault("?")
