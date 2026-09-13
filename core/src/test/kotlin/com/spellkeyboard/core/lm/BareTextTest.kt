package com.spellkeyboard.core.lm

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test

/**
 * **공백을 전부 지운 문장**을 얼마나 되돌리는지 잰다. 전체교정이 실제로 받는 입력이다.
 *
 * 경계 단위 정밀도/재현율로 잰다 — 문장 통째 일치만 보면 부분 점수가 안 보인다.
 * `SPELL_EVAL_FILES` 가 있을 때만 돈다.
 */
class BareTextTest {
    @Test
    fun `공백을 전부 지운 문장을 되돌리는 비율`() {
        val files = System.getenv("SPELL_EVAL_FILES")?.split(',')?.filter { it.isNotBlank() } ?: return
        val sentences = LinkedHashSet<String>()
        for (path in files) {
            File(path).useLines { lines ->
                lines.drop(1).forEach { line ->
                    line.split('\t').take(2).forEach { cell ->
                        val clean = cell.trim().trim('"')
                        if (clean.length in 6..60 && clean.count { it == ' ' } >= 2 &&
                            clean.all { it in '가'..'힣' || it == ' ' || it in ".,!?" }
                        ) sentences += clean.trimEnd('.', '!', '?', ',')
                    }
                }
            }
        }
        val sample = sentences.shuffled(Random(7)).take(3000)

        val dir = Files.createTempDirectory("bare").toFile().also { it.deleteOnExit() }
        val spacer = Spacer(SpacingDictionary.open(dir))
        val engine = CorrectionEngine().apply {
            this.spacer = spacer
            this.context = ContextCorrector(LanguageModel.open(dir), spacer)
        }

        fun breaksOf(text: String): Set<Int> {
            val at = sortedSetOf<Int>()
            var k = 0
            for (c in text) if (c == ' ') { if (k > 0) at += k } else k++
            return at
        }

        var hit = 0L; var got = 0L; var want = 0L; var exact = 0
        for (gold in sample) {
            val bare = gold.replace(" ", "")
            val out = engine.correctAll(bare).text
            // 글자가 바뀌면 경계를 견줄 수 없다. 그런 경우는 통째로 틀린 것으로 친다.
            if (out.replace(" ", "") != bare) { want += breaksOf(gold).size; continue }
            val g = breaksOf(gold); val p = breaksOf(out)
            want += g.size; got += p.size
            hit += p.count { it in g }
            if (g == p) exact++
        }
        val prec = 100.0 * hit / maxOf(1, got)
        val rec = 100.0 * hit / maxOf(1, want)
        println(
            "우리 엔진: 정밀도 %.1f%% 재현율 %.1f%% F1 %.1f%%  문장통째 %.1f%%".format(
                prec, rec, 2 * prec * rec / maxOf(0.01, prec + rec), 100.0 * exact / sample.size
            )
        )
    }
}
