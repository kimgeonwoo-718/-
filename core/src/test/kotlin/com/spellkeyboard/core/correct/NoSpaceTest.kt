package com.spellkeyboard.core.correct

import com.spellkeyboard.core.TestCache
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **공백을 아예 안 친 글**을 푸는지 본다.
 *
 * [com.spellkeyboard.core.lm.EvaluationTest] 가 재는 것은 멀쩡한 문장에서 공백을
 * **하나만** 지운 것이다. 실제 쓰임은 이쪽이다 — 빨리 치느라 통째로 안 띄운 글.
 * 완전히 다른 문제이고, 한동안 여기서 **공백을 하나도 못 넣고 있었다.**
 *
 * 원인이 둘이었다.
 * 1. 문맥 교정기는 14음절 넘는 어절을 아예 쳐다보지 않는다. 안 띄운 글은 30~50음절이다.
 * 2. 형태소 사전은 24음절까지만 본다. 그 위는 null 을 준다.
 *
 * 지금은 [Spacer.spaceLong] 이 창을 밀며 풀고, 그 결과를 문맥 교정기가 다듬는다.
 */
class NoSpaceTest {

    private fun engine(): CorrectionEngine {
        val dir = TestCache.dir
        val spacer = Spacer(SpacingDictionary.open(dir))
        return CorrectionEngine().apply {
            this.spacer = spacer
            this.context = ContextCorrector(LanguageModel.open(dir), spacer)
        }
    }

    @Test
    fun `공백 없이 친 글을 띄운다`() {
        val engine = engine()
        // 실기기에서 받은 글이다. 숫자는 지금 실력이고, 바닥으로 못 박아 둔다.
        val cases = listOf(
            "번이나말했잖아너혼자서만잘산다고해서전혀행복해지는게아니라고." to 7,
            "그친구는내말을듣는둥마는둥하더니결국자기맘대로일처리를해버렸다." to 7,
            "들은바에의하면지난번에받은상금중에절반을못받았다고하던데진짜어처구니가없더라고." to 8,
            "하긴남의말을개똥으로아는놈이니오죽하겠냐마는그래도사람이면최소한의예의는차려야하는거아니냐?" to 11,
            "이번주말까지다시연락을주기로했으니일단기다려보는수밖에없겠다." to 8
        )
        for ((broken, floor) in cases) {
            val out = engine.correctAll(broken).text
            val spaces = out.count { it == ' ' }
            println("  공백 %2d개: %s".format(spaces, out))
            assertTrue(spaces >= floor, "덜 띄웠다(${spaces} < $floor): $out")
            // 띄우기만 해야 한다. 공백을 빼면 글자가 그대로여야 한다.
            assertEquals(
                broken.filterNot { it.isWhitespace() },
                out.filterNot { it.isWhitespace() },
                "띄우면서 글자가 바뀌었다"
            )
        }
    }

    @Test
    fun `끝의 문장 부호가 마지막 조각을 막지 않는다`() {
        val engine = engine()
        // 형태소 사전은 어절을 받는다. 부호를 떼지 않고 넘기면 분석이 통째로 실패해서
        // **마지막 조각이 늘 안 풀렸다** — 문장 끝이라 부호가 거의 항상 붙어 있다.
        val withMark = engine.correctAll("이번주말까지다시연락을주기로했으니일단기다려보는수밖에없겠다.").text
        val without = engine.correctAll("이번주말까지다시연락을주기로했으니일단기다려보는수밖에없겠다").text
        assertEquals(
            without.count { it == ' ' },
            withMark.count { it == ' ' },
            "부호가 붙었다고 덜 띄운다: <$withMark> vs <$without>"
        )
    }
}
