import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * 실시간 교정을 폰과 **같은 길로** 흉내 낸다: 어절 하나를 치고 스페이스를 누를 때마다
 * `correctTail`(커서 앞 세 어절)을 부르고, 고친 것으로 갈아 끼운 뒤 다음 어절을 친다.
 * `try.sh` 는 전체교정(`correctAll`)이라 이 길과 다르다.
 *
 * 입력: `친 글<TAB>정답` (정답이 여럿이면 ` | ` 로). `#` 줄과 빈 줄은 건너뛴다.
 * 재는 것 둘:
 *   고침  — 친 글이 정답 중 하나가 되었나
 *   지킴  — **정답을 그대로 쳤을 때** 안 건드리나(멀쩡한 글 망가뜨리기)
 */
fun main(args: Array<String>) {
    val cache = File(System.getProperty("java.io.tmpdir"), "spell-live")
    val engine = CorrectionEngine()
    val spacer = runCatching { Spacer(SpacingDictionary.open(cache)) }
        .onSuccess { engine.spacer = it; engine.speller = Speller(it) }
        .getOrNull()
    runCatching { LanguageModel.open(cache) }
        .onSuccess { engine.context = ContextCorrector(it, spacer) }
    val show = System.getenv("LIVE_SHOW")?.toIntOrNull() ?: 200

    val plain = args.flatMap { path ->
        File(path).readLines().map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
            .map { line -> line.substringBefore('\t') to line.substringAfter('\t').split(" | ").map { it.trim() } }
    }
    // LIVE_DECO=1: 모든 줄 끝에 채팅 장식(ㅋㅋ·ㅠㅠ·이모지…)을 붙여 잰다. 장식 때문에 어절을
    // 통째로 건너뛰던 일이 있었다('햇어😂'). 문장 부호로 끝나는 줄은 그대로 둔다.
    val rows = if (System.getenv("LIVE_DECO") != "1") plain else plain.mapIndexed { i, (typed, answers) ->
        if (answers.first().last() in "?.!") typed to answers
        else DECORATIONS[i % DECORATIONS.size].let { deco -> typed + deco to answers.map { it + deco } }
    }

    var fixed = 0
    var kept = 0
    val misses = mutableListOf<String>()
    val broken = mutableListOf<String>()
    for ((typed, answers) in rows) {
        val out = type(engine, typed)
        if (out in answers) fixed++ else misses += "  $typed\n    → $out\n    ✓ ${answers.joinToString(" | ")}"
        val clean = answers.first()
        val again = type(engine, clean)
        if (again in answers) kept++ else broken += "  $clean\n    → $again"
    }
    println("고침: $fixed / ${rows.size}  (${pct(fixed, rows.size)})")
    println("지킴: $kept / ${rows.size}  (${pct(kept, rows.size)})  — 정답을 그대로 쳤을 때 안 건드린 것")
    if (misses.isNotEmpty()) { println("\n## 못 고친 것 (${misses.size})"); misses.take(show).forEach(::println) }
    if (broken.isNotEmpty()) { println("\n## 멀쩡한 걸 건드린 것 (${broken.size})"); broken.take(show).forEach(::println) }
}

/** 어절마다 스페이스를 누르는 것처럼. 마지막 어절 뒤에도 스페이스를 한 번 누른다. */
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

val DECORATIONS = listOf("ㅋㅋ", "ㅠㅠ", "~", "!!", "ㅎㅎ", "😂", "..", "^^")

fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(a * 100.0 / b)
