package com.spellkeyboard.core.correct

import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.nio.file.Files
import kotlin.test.Test

/** 스페이스를 누르는 순간 도는 교정이 타이핑을 막을 만큼 느린지 잰다. */
class EngineSpeedTest {

    @Test
    fun `스페이스 한 번에 걸리는 시간`() {
        val dir = Files.createTempDirectory("speed").toFile().also { it.deleteOnExit() }
        val spacer = Spacer(SpacingDictionary.open(dir))
        val speller = Speller(spacer)

        val withSpeller = CorrectionEngine().apply {
            this.spacer = spacer
            this.speller = speller
        }
        val withoutSpeller = CorrectionEngine().apply { this.spacer = spacer }

        // 교정 창은 커서 앞 몇 어절이다. 실제로 그만한 덩어리를 넣어 본다.
        val samples = listOf(
            "말했잖아",
            "행복해지는 게 아니라고",
            "어처구니가 없더라고",
            "오죽하겠냐마는 그래도",
            "내가 그 사람한테 몇 번이나"
        )

        for (engine in listOf(withoutSpeller to "사전만", withSpeller to "사전+맞춤법")) {
            repeat(3) { samples.forEach { engine.first.correct(it) } }  // 예열
            val start = System.nanoTime()
            repeat(20) { samples.forEach { engine.first.correct(it) } }
            val perCall = (System.nanoTime() - start) / 1_000_000.0 / (20 * samples.size)
            println("[${engine.second}] 한 번에 %.1f ms".format(perCall))
        }

        // 가장 긴 것 하나만 따로.
        val worst = "내가 그 사람한테 몇 번이나"
        repeat(5) { withSpeller.correct(worst) }
        val start = System.nanoTime()
        repeat(20) { withSpeller.correct(worst) }
        println("[최악] %.1f ms".format((System.nanoTime() - start) / 1_000_000.0 / 20))
    }
}
