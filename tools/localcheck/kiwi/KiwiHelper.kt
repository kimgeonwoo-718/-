import com.spellkeyboard.core.correct.ProtectedWords
import com.spellkeyboard.core.correct.TypoGuard
import com.spellkeyboard.core.lm.LanguageModel
import java.io.File

/**
 * sim.py 가 폰의 Kiwi 오타 단계를 흉내 낼 때, 코틀린에만 있는 판정을 **진짜 코드로** 대신 해 준다.
 * 줄마다 하나씩 묻고 답한다:
 *   known<TAB>낱말        → 1/0  (언어모델이 아는 낱말이거나 보호 낱말인가 — 키보드의 knownWord 문지방)
 *   reject<TAB>원래<TAB>고침  → 1/0  (TypoGuard 가 이 고침을 막는가)
 */
fun main(args: Array<String>) {
    val lm = LanguageModel.open(File(args[0]))
    val input = System.`in`.bufferedReader()
    val out = System.out.bufferedWriter()
    while (true) {
        val line = input.readLine() ?: break
        val parts = line.split('\t')
        val answer = when (parts[0]) {
            "known" -> lm.lnCount(parts[1]) != null || ProtectedWords.isProtected(parts[1])
            "reject" -> TypoGuard.rejects(parts[1], parts[2])
            else -> false
        }
        out.write(if (answer) "1" else "0"); out.newLine(); out.flush()
    }
}
