package com.spellkeyboard.core.spacing

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpacerTest {

    @Test
    fun `붙여 쓴 문장을 어절로 나눈다`() {
        assertOneOf("아버지가방에들어가신다", "아버지가 방에 들어가신다", "아버지 가방에 들어가신다")
        assertEquals("할 수 있다", space("할수있다"))
        assertEquals("제가 할게요", space("제가할게요"))
        assertEquals("밥 먹었어요", space("밥먹었어요"))
        assertEquals("나는 학교에 간다", space("나는학교에간다"))
        assertEquals("그것을 보고 싶어요", space("그것을보고싶어요"))
        assertEquals("오늘 날씨가 정말 좋네요", space("오늘날씨가정말좋네요"))
        assertEquals("오늘은 날씨가 좋아서 기분이 좋다", space("오늘은날씨가좋아서기분이좋다"))
        assertEquals("내가 만든 앱이 잘 동작한다", space("내가만든앱이잘동작한다"))
        assertEquals("이것은 책상입니다", space("이것은책상입니다"))
        assertEquals("저는 학생입니다", space("저는학생입니다"))
    }

    @Test
    fun `이미 한 어절인 말은 쪼개지 않는다`() {
        for (word in listOf(
            "감사합니다", "안녕하세요", "학교에서", "먹었습니다", "사랑합니다",
            "괜찮아요", "생각하고", "대한민국", "스마트폰", "반갑습니다"
        )) {
            assertNull(space(word), "$word 를 쪼갰다")
        }
    }

    @Test
    fun `한글이 아니거나 너무 짧으면 손대지 않는다`() {
        assertNull(space("hello"))
        assertNull(space("123456"))
        assertNull(space("가나"))
        assertNull(space("이미 띄어 쓴 문장"))
    }

    @Test
    fun `실시간 입력을 따라갈 만큼 빠르다`() {
        val samples = listOf(
            "오늘은날씨가좋아서기분이좋다", "내가만든앱이잘동작한다",
            "아버지가방에들어가신다", "감사합니다", "할수있다"
        )
        repeat(20) { samples.forEach { spacer.space(it) } }   // 워밍업

        val started = System.nanoTime()
        val rounds = 200
        repeat(rounds) { samples.forEach { spacer.space(it) } }
        val perCall = (System.nanoTime() - started) / (rounds.toLong() * samples.size) / 1_000_000.0

        println("어절당 평균 %.2f ms".format(perCall))
        // 타이핑 간격이 100~200ms 라 한 어절에 20ms 를 넘으면 손끝에서 느껴진다.
        kotlin.test.assertTrue(perCall < 20.0, "너무 느리다: %.2f ms".format(perCall))
    }

    private fun space(word: String) = spacer.space(word)

    private fun assertOneOf(input: String, vararg expected: String) {
        val actual = space(input)
        kotlin.test.assertTrue(
            actual in expected,
            "$input -> $actual (기대: ${expected.joinToString(" 또는 ")})"
        )
    }

    companion object {
        // 사전을 푸는 데 시간이 걸려 테스트 클래스마다 한 번만 연다.
        private val spacer: Spacer by lazy {
            val dir = Files.createTempDirectory("spell-dict").toFile()
            dir.deleteOnExit()
            Spacer(SpacingDictionary.open(dir))
        }
    }
}
