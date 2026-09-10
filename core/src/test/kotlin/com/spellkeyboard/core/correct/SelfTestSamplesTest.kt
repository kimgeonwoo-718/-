package com.spellkeyboard.core.correct

import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 자체 점검 표본이 실제로 전부 통과하는지 본다.
 *
 * 이 테스트가 깨지면 **표본이 틀렸거나 엔진이 상한 것**이고, 둘 중 어느 쪽이든
 * 실기기 점검 화면이 거짓을 말하게 된다. 그러면 사용자가 "안 된다" 고 할 때
 * 원인을 가릴 유일한 도구를 잃는다.
 */
class SelfTestSamplesTest {

    @Test
    fun `자체 점검 표본이 전부 통과한다`() {
        val failures = SelfTestSamples.ALL.mapNotNull { (input, expected) ->
            val actual = engine.correct(input).text
            if (actual == expected) null else "$input -> $actual (기대: $expected)"
        }
        assertEquals(emptyList(), failures, "자체 점검 표본이 실패한다")
    }

    companion object {
        /** 키보드가 차리는 것과 같은 구성. 다르게 차리면 진단이 의미가 없다. */
        private val engine: CorrectionEngine by lazy {
            val dir = Files.createTempDirectory("selftest-dict").toFile()
            dir.deleteOnExit()
            val spacer = Spacer(SpacingDictionary.open(dir))
            CorrectionEngine().apply {
                this.spacer = spacer
                this.speller = Speller(spacer)
            }
        }
    }
}
