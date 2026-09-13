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
 * 글 전체 교정([CorrectionEngine.correctAll]).
 *
 * 여기서 지키는 것은 두 가지다. **원문을 잃지 않는 것**(자른 조각을 도로 이어 붙이면
 * 원문이어야 한다)과 **긴 글이 조용히 안 고쳐지지 않는 것**이다. 둘째가 이 기능을
 * 만들면서 제일 무서웠던 실패다 — 문맥 교정기는 구간이 길면 그 구간을 통째로
 * 포기하는데, 그게 아무 소리 없이 일어난다.
 */
class CorrectAllTest {

    @Test
    fun `자른 조각을 이어 붙이면 원문이다`() {
        val samples = listOf(
            "안녕하세요. 반갑습니다!",
            "줄바꿈이\n들어간\n글입니다",
            "  앞뒤에   공백이  많은   글  ",
            "부호가 없는 아주 긴 문장이 하나 있는데 이것은 서른여섯 음절을 훌쩍 넘겨서 여러 조각으로 잘려야 정상이다",
            "",
            "\n\n",
            "영어 mixed 와 숫자 123 이 섞인 글"
        )
        for (sample in samples) {
            assertEquals(sample, CorrectionEngine.chunk(sample).joinToString(""), "원문: <$sample>")
        }
    }

    @Test
    fun `긴 문장은 여러 조각으로 잘린다`() {
        val long = "가나다라마바사아자차카타파하".repeat(6) // 84 음절, 공백 없음
        val pieces = CorrectionEngine.chunk("$long 끝")
        assertTrue(pieces.size >= 2, "한 조각으로 남으면 교정기가 통째로 포기한다: ${pieces.size}")
    }

    @Test
    fun `줄바꿈에서 끊어서 문단이 섞이지 않는다`() {
        val pieces = CorrectionEngine.chunk("첫째 줄입니다\n둘째 줄입니다")
        assertTrue(pieces.any { it.contains('\n') }, "줄바꿈이 제 조각으로 떨어져야 한다")
        assertTrue(pieces.none { it.contains("첫째") && it.contains("둘째") }, "두 줄이 한 조각에 섞였다")
    }

    @Test
    fun `한글이 없으면 손대지 않는다`() {
        val engine = CorrectionEngine()
        val text = "hello world 123 !!!"
        assertEquals(text, engine.correctAll(text).text)
    }

    @Test
    fun `긴 글도 실제로 고친다 — 조각내기가 없으면 통째로 건너뛴다`() {
        val dir = TestCache.dir
        val spacer = Spacer(SpacingDictionary.open(dir))
        val engine = CorrectionEngine().apply {
            this.spacer = spacer
            this.context = ContextCorrector(LanguageModel.open(dir), spacer)
        }

        // 띄어쓰기를 지운 문장을 여럿 이어 붙여 한 줄로 만든다. 부호가 없어서
        // 조각내기가 없으면 문맥 교정기가 이 줄을 통째로 포기한다.
        val broken = "오늘은날씨가좋아서 밖에나가서 산책을했습니다 그리고 저녁에는 친구를만나서 밥을먹었어요"
        val result = engine.correctAll(broken)

        assertTrue(result.changed, "긴 글이 통째로 건너뛰어졌다")
        // 공백을 빼면 글자는 그대로여야 한다 — 띄어쓰기만 손대는 일이다.
        assertEquals(
            broken.filterNot { it.isWhitespace() },
            result.text.filterNot { it.isWhitespace() },
            "띄어쓰기를 고치면서 글자가 바뀌었다"
        )
    }

    @Test
    fun `2000자 글도 사람이 기다릴 만한 시간에 끝난다`() {
        val dir = TestCache.dir
        val spacer = Spacer(SpacingDictionary.open(dir))
        val engine = CorrectionEngine().apply {
            this.spacer = spacer
            this.context = ContextCorrector(LanguageModel.open(dir), spacer)
        }
        val paragraph = ("오늘은날씨가좋아서 밖에나가서 산책을했습니다. " +
            "그리고 저녁에는 친구를만나서 밥을먹었어요. 내일도 이렇게 좋으면좋겠다. ").repeat(40)
        require(paragraph.length > 2000) { "표본이 짧다: " + paragraph.length }

        engine.correctAll(paragraph) // 사전 올리기와 JIT 를 빼고 잰다
        val startedAt = System.nanoTime()
        val result = engine.correctAll(paragraph)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        println(paragraph.length.toString() + "자 전체교정: " + tookMs + "ms, 고친 곳 " + result.corrections.size)
        assertTrue(result.changed, "고친 것이 없다")
        // 사용자가 버튼을 누르고 기다리는 시간이다. 백그라운드 스레드에서 돌지만
        // 이보다 길면 "멈췄나" 로 보인다.
        assertTrue(tookMs < 3000, "너무 느리다: " + tookMs + "ms")
    }

    @Test
    fun `문장이 여럿이면 각 문장을 첫머리로 본다`() {
        val pieces = CorrectionEngine.chunk("첫 문장이다. 둘째 문장이다. 셋째다!")
        assertEquals(3, pieces.size, "문장 부호마다 끊겨야 한다: $pieces")
    }
}
