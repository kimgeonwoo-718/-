package com.spellkeyboard.core.lm

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test

/**
 * 말뭉치 밖의 문장으로 오교정률과 교정률을 잰다. 주장은 없고 표만 찍는다.
 *
 * `SPELL_EVAL_FILES` 환경변수에 문장 파일(줄마다 한 문장, 탭이 있으면 첫 두 칸)을
 * 쉼표로 이어 주면 돈다. 없으면 아무것도 하지 않는다 — CI 에는 말뭉치가 없다.
 *
 * 세 가지를 잰다.
 * 1. 멀쩡한 문장을 건드리는 비율 (오교정) — 이게 가장 중요하다.
 * 2. 띄어쓰기 하나를 지운 문장을 되돌리는 비율.
 * 3. 어절 하나에 ㅐ/ㅔ 를 바꿔 넣은 문장을 되돌리는 비율.
 * 되돌리지도 못하고 다른 데를 건드린 것은 "망침" 으로 따로 센다.
 */
class EvaluationTest {

    @Test
    fun `말뭉치 밖 문장으로 오교정률을 잰다`() {
        val files = System.getenv("SPELL_EVAL_FILES")?.split(',')?.filter { it.isNotBlank() } ?: return
        val sentences = LinkedHashSet<String>()
        for (path in files) {
            val separator = if (path.endsWith(".csv")) ',' else '\t'
            File(path).useLines { lines ->
                lines.drop(1).forEach { line ->
                    line.split(separator).take(2).forEach { cell ->
                        val clean = cell.trim().trim('"')
                        if (clean.length in 6..60 && clean.count { it == ' ' } >= 2 &&
                            clean.all { it in '가'..'힣' || it == ' ' || it in ".,!?" }
                        ) sentences += clean.trimEnd('.', '!', '?', ',')
                    }
                }
            }
        }
        val limit = (System.getenv("SPELL_EVAL_LIMIT") ?: "3000").toInt()
        val sample = sentences.shuffled(Random(7)).take(limit)
        println("평가 문장 ${sample.size}개 (전체 ${sentences.size}개)")

        val dir = Files.createTempDirectory("eval").toFile().also { it.deleteOnExit() }
        val spacer = Spacer(SpacingDictionary.open(dir))
        val engine = CorrectionEngine().apply {
            this.spacer = spacer
            this.speller = Speller(spacer)
            this.context = ContextCorrector(LanguageModel.open(dir), spacer)
        }
        fun run(text: String): String = engine.correct(text, LanguageModel.BOS).text

        // 1. 오교정
        val touched = ArrayList<Pair<String, String>>()
        for (s in sample) {
            val out = run(s)
            if (out != s) touched += s to out
        }
        println("오교정: ${touched.size}/${sample.size} = %.2f%%".format(100.0 * touched.size / sample.size))
        touched.take(60).forEach { (a, b) -> println("  $a  →  $b") }

        // 2. 띄어쓰기 지우기
        val random = Random(11)
        var spaceTried = 0; var spaceFixed = 0; var spaceHarm = 0
        val spaceMiss = ArrayList<String>()
        for (s in sample) {
            val spaces = s.indices.filter { s[it] == ' ' }
            if (spaces.isEmpty()) continue
            val at = spaces[random.nextInt(spaces.size)]
            val broken = s.removeRange(at, at + 1)
            val out = run(broken)
            spaceTried++
            when (out) {
                s -> spaceFixed++
                broken -> spaceMiss += "$broken"
                else -> { spaceHarm++; if (spaceHarm <= 30) println("  [띄어쓰기 망침] $broken → $out (정답 $s)") }
            }
        }
        println("띄어쓰기 복원: $spaceFixed/$spaceTried = %.1f%%, 망침 $spaceHarm".format(100.0 * spaceFixed / spaceTried))
        spaceMiss.take(30).forEach { println("  [띄어쓰기 못 고침] $it") }

        // 3. ㅐ/ㅔ 바꾸기
        var vowelTried = 0; var vowelFixed = 0; var vowelHarm = 0
        val vowelMiss = ArrayList<String>()
        for (s in sample) {
            val spots = s.indices.filter { i ->
                Hangul.decompose(s[i])?.let { (_, jung, _) -> Hangul.JUNGSEONG[jung] == 'ㅐ' || Hangul.JUNGSEONG[jung] == 'ㅔ' } == true
            }
            if (spots.isEmpty()) continue
            val at = spots[random.nextInt(spots.size)]
            val (cho, jung, jong) = Hangul.decompose(s[at])!!
            val swapped = Hangul.jungseongIndex(if (Hangul.JUNGSEONG[jung] == 'ㅐ') 'ㅔ' else 'ㅐ')
            val broken = s.substring(0, at) + Hangul.compose(cho, swapped, jong) + s.substring(at + 1)
            val out = run(broken)
            vowelTried++
            when (out) {
                s -> vowelFixed++
                broken -> vowelMiss += broken
                else -> { vowelHarm++; if (vowelHarm <= 30) println("  [모음 망침] $broken → $out (정답 $s)") }
            }
        }
        println("ㅐ/ㅔ 복원: $vowelFixed/$vowelTried = %.1f%%, 망침 $vowelHarm".format(100.0 * vowelFixed / vowelTried))
        vowelMiss.take(30).forEach { println("  [모음 못 고침] $it") }
    }
}
