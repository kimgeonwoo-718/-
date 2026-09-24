import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File
import kotlin.random.Random

/**
 * 오타 되살리기를 잰다. 맞는 문장의 어절 하나에 **실제로 흔한 오타**를 한 개 넣고, 실시간 교정
 * (어절마다 스페이스)으로 친 결과가 원문으로 돌아오는지 본다.
 *
 * 디코더가 멀쩡한 말을 딴 말로 바꾸는 것('주기에는'→'죽기에는')을 줄이려면 편집을 아껴야 하는데,
 * 그러면 진짜 오타도 덜 고친다. 그 값을 숫자로 보려고 만들었다.
 *
 * 오타 종류:
 *   ㅆ→ㅅ   했어→햇어, 있어→잇어, 겠→겟   (구어체에서 제일 흔하다)
 *   받침빠짐  괜찮아→괜차아 같은 것 말고, 받침 하나가 통째로 빠짐 (먹었→머었)
 *   겹받침    괜찮→괜찬, 없→업, 않→안
 *   ㅐ↔ㅔ    내가→네가(이건 되돌릴 수 없는 경우도 있다)
 *   TYPO_CJI=1 이면 천지인 오타도: 자음 연타 한 번 덜·더(했→핶 말고 ㅅ↔ㅎ, ㄴ↔ㄹ, ㅇ↔ㅁ),
 *   모음 획 방향(ㅏ↔ㅓ, ㅗ↔ㅜ)
 */
fun main(args: Array<String>) {
    val limit = (System.getenv("TYPO_LIMIT") ?: "1000").toInt()
    val show = (System.getenv("TYPO_SHOW") ?: "0").toInt()
    val sets = LinkedHashMap<String, List<String>>()
    File(args[0]).takeIf { it.exists() }?.let { f ->
        sets["구어체"] = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
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

    val cache = File(System.getProperty("java.io.tmpdir"), "spell-typo")
    val spacer = Spacer(SpacingDictionary.open(cache))
    val engine = CorrectionEngine().apply {
        cheonjiin = System.getenv("SPELL_CJI") == "1"
        this.spacer = spacer
        this.speller = Speller(spacer)
        this.context = ContextCorrector(LanguageModel.open(cache), spacer)
    }

    for ((name, sample) in sets) {
        println("\n══ $name ".padEnd(50, '═'))
        // 원문이 이미 우리 결과와 다르면(원래부터 건드리는 문장) 빼고 잰다 — 오타 탓과 섞이지 않게.
        val clean = sample.filter { type(engine, it) == it }
        val byKind = LinkedHashMap<String, IntArray>()  // [시도, 되살림, 딴데망침]
        val misses = mutableListOf<String>()
        val rnd = Random(11)
        for (s in clean) {
            for ((kind, typo) in typos(s, rnd)) {
                val out = type(engine, typo)
                val c = byKind.getOrPut(kind) { IntArray(3) }
                c[0]++
                if (out == s) c[1]++ else {
                    if (damagedElsewhere(s, typo, out)) c[2]++
                    if (misses.size < show) misses += "  [$kind] $typo\n    → $out\n    ✓ $s"
                }
            }
        }
        var tried = 0; var fixed = 0
        for ((kind, c) in byKind) {
            tried += c[0]; fixed += c[1]
            println("  %-6s %4d개  되살림 %5.1f%%  딴 데 망침 %d".format(kind, c[0], c[1] * 100.0 / c[0], c[2]))
        }
        println("  합계   %4d개  되살림 %5.1f%%   (원문을 그대로 두던 문장 %d/%d 만 씀)".format(tried, fixed * 100.0 / tried, clean.size, sample.size))
        misses.forEach(::println)
    }
}

/** 문장 하나에서 종류마다 오타 하나씩. 그 종류로 만들 수 있는 어절이 없으면 건너뛴다. */
fun typos(s: String, rnd: Random): List<Pair<String, String>> {
    val words = s.split(' ')
    val out = mutableListOf<Pair<String, String>>()
    fun pick(kind: String, f: (String) -> String?) {
        val options = words.indices.mapNotNull { i -> f(words[i])?.let { i to it } }
        if (options.isEmpty()) return
        val (i, w) = options[rnd.nextInt(options.size)]
        out += kind to words.toMutableList().also { it[i] = w }.joinToString(" ")
    }
    pick("ㅆ→ㅅ") { w -> replaceFirstSyllable(w) { jong -> if (jong == 'ㅆ') 'ㅅ' else null } }
    pick("받침빠짐") { w -> replaceFirstSyllable(w) { jong -> if (jong != ' ' && jong !in "ㅆㄹ") ' ' else null } }
    pick("겹받침") { w -> replaceFirstSyllable(w) { jong -> mapOf('ㄶ' to 'ㄴ', 'ㅄ' to 'ㅂ', 'ㄺ' to 'ㄱ', 'ㄼ' to 'ㄹ', 'ㄻ' to 'ㅁ')[jong] } }
    pick("ㅐ↔ㅔ") { w -> swapVowel(w) }
    // TYPO_CJI=1: 천지인 오타. 자음은 같은 키를 한 번 덜·더 눌러 이웃 글자로(ㅅ↔ㅎ, ㄴ↔ㄹ, ㅇ↔ㅁ…),
    // 모음은 ㆍ를 ㅣ·ㅡ 반대쪽에 찍어서(ㅏ↔ㅓ, ㅗ↔ㅜ).
    if (System.getenv("TYPO_CJI") == "1") {
        pick("천지인자음") { w -> cjiConsonant(w, rnd) }
        pick("천지인모음") { w -> cjiVowel(w, rnd) }
    }
    return out
}

private val CJI_CHO = mapOf('ㄱ' to 'ㅋ', 'ㅋ' to 'ㄱ', 'ㄴ' to 'ㄹ', 'ㄹ' to 'ㄴ', 'ㄷ' to 'ㅌ', 'ㅌ' to 'ㄷ',
    'ㅂ' to 'ㅍ', 'ㅍ' to 'ㅂ', 'ㅅ' to 'ㅎ', 'ㅎ' to 'ㅅ', 'ㅈ' to 'ㅊ', 'ㅊ' to 'ㅈ', 'ㅇ' to 'ㅁ', 'ㅁ' to 'ㅇ')
private val CJI_VOWEL = mapOf('ㅏ' to 'ㅓ', 'ㅓ' to 'ㅏ', 'ㅗ' to 'ㅜ', 'ㅜ' to 'ㅗ', 'ㅑ' to 'ㅕ', 'ㅕ' to 'ㅑ', 'ㅛ' to 'ㅠ', 'ㅠ' to 'ㅛ')

/** 어절 속 아무 음절의 초성이나 받침 하나를 천지인 이웃 글자로. */
fun cjiConsonant(w: String, rnd: Random): String? {
    val options = mutableListOf<String>()
    for ((i, c) in w.withIndex()) {
        val d = Hangul.decompose(c) ?: continue
        CJI_CHO[Hangul.CHOSEONG[d.first]]?.let { to ->
            options += w.substring(0, i) + Hangul.compose(Hangul.choseongIndex(to), d.second, d.third) + w.substring(i + 1)
        }
        if (d.third != 0) CJI_CHO[JONG[d.third]]?.let { to ->
            val idx = JONG.indexOf(to)
            if (idx > 0) options += w.substring(0, i) + Hangul.compose(d.first, d.second, idx) + w.substring(i + 1)
        }
    }
    return if (options.isEmpty()) null else options[rnd.nextInt(options.size)]
}

fun cjiVowel(w: String, rnd: Random): String? {
    val options = mutableListOf<String>()
    for ((i, c) in w.withIndex()) {
        val d = Hangul.decompose(c) ?: continue
        CJI_VOWEL[Hangul.JUNGSEONG[d.second]]?.let { to ->
            options += w.substring(0, i) + Hangul.compose(d.first, Hangul.jungseongIndex(to), d.third) + w.substring(i + 1)
        }
    }
    return if (options.isEmpty()) null else options[rnd.nextInt(options.size)]
}

private val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

/** 어절 안에서 조건에 맞는 받침을 가진 첫 음절의 받침을 바꾼다. */
fun replaceFirstSyllable(w: String, f: (Char) -> Char?): String? {
    for ((i, c) in w.withIndex()) {
        val d = Hangul.decompose(c) ?: continue
        val jong = JONG[d.third]
        val to = f(jong) ?: continue
        val idx = JONG.indexOf(to)
        return w.substring(0, i) + Hangul.compose(d.first, d.second, idx) + w.substring(i + 1)
    }
    return null
}

fun swapVowel(w: String): String? {
    for ((i, c) in w.withIndex()) {
        val d = Hangul.decompose(c) ?: continue
        val v = when (d.second) { 1 -> 5; 5 -> 1; else -> -1 }  // ㅐ(1) ↔ ㅔ(5)
        if (v < 0) continue
        return w.substring(0, i) + Hangul.compose(d.first, v, d.third) + w.substring(i + 1)
    }
    return null
}

/** 오타를 넣은 어절 말고 다른 어절까지 바뀌었나. */
fun damagedElsewhere(orig: String, typo: String, out: String): Boolean {
    val a = orig.split(' '); val t = typo.split(' '); val o = out.split(' ')
    if (a.size != o.size) return true
    return a.indices.any { i -> a[i] == t[i] && a[i] != o[i] }
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
