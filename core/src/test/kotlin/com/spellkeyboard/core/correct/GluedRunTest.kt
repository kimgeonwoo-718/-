package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 붙여 쓴 덩어리를 알아보는지.
 *
 * 이 값 하나로 "AI 에 보내기 전에 우리가 먼저 풀지" 가 갈린다. 잘못 참을 내면 괜히
 * 몇 초를 쓰고, 잘못 거짓을 내면 AI 가 우리보다 못하는 일을 대신 하게 된다.
 */
class GluedRunTest {

    private val engine = CorrectionEngine()

    @Test
    fun `띄어쓰기를 안 친 긴 덩어리를 찾는다`() {
        assertTrue(engine.hasGluedRun("오늘은날씨가좋아서기분이좋다"))
        assertTrue(engine.hasGluedRun("아버지가방에들어가신다"))
        // 문장 한가운데 한 덩어리만 붙어 있어도 찾아야 한다.
        assertTrue(engine.hasGluedRun("어제 말한거기서만나자고했잖아 기억나?"))
    }

    @Test
    fun `띄어 쓴 글은 건드리지 않는다`() {
        assertFalse(engine.hasGluedRun("오늘 날씨가 참 좋은 것 같아요"))
        assertFalse(engine.hasGluedRun("내일 아침에 비가 온다니까 우산 챙겨서 나가는 게 좋겠다"))
        assertFalse(engine.hasGluedRun(""))
        assertFalse(engine.hasGluedRun("Hello, world!"))
    }

    @Test
    fun `여덟 음절까지는 그냥 긴 말이다`() {
        // 한국어에 여덟 음절짜리 한 어절은 드물지 않다. 여기서 참을 내면 멀쩡한 글마다
        // 몇 초씩 쓰게 된다.
        assertFalse(engine.hasGluedRun("가".repeat(CorrectionEngine.GLUED_RUN_SYLLABLES)))
        assertTrue(engine.hasGluedRun("가".repeat(CorrectionEngine.GLUED_RUN_SYLLABLES + 1)))
    }

    @Test
    fun `부호나 숫자가 끼어도 붙여 쓴 것은 붙여 쓴 것이다`() {
        // '오늘은3시에만나자' 는 띄어쓰기를 안 친 글이다. 숫자가 덩어리를 끊으면 안 된다.
        assertTrue(engine.hasGluedRun("오늘은3시에학교앞에서만나자"))
        // 반대로 공백은 끊는다.
        assertFalse(engine.hasGluedRun("오늘은 3시에 학교 앞에서 만나자"))
    }

    @Test
    fun `줄바꿈도 덩어리를 끊는다`() {
        assertFalse(engine.hasGluedRun("오늘은날씨가\n좋아서기분이"))
    }
}
