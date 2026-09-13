package com.spellkeyboard.core.lm

import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextCorrectorTest {

    private fun fix(text: String, before: String? = LanguageModel.BOS): String =
        corrector.correct(text, before) ?: text

    private fun assertFixed(expected: String, input: String, before: String? = LanguageModel.BOS) {
        assertEquals(expected, fix(input, before), "'$input' →")
    }

    private fun assertUntouched(text: String, before: String? = LanguageModel.BOS) {
        assertNull(corrector.correct(text, before), "'$text' 를 '${fix(text, before)}' 로 고쳤다")
    }

    // --- 사용자가 실기기에서 잡아낸 세 문장 -----------------------------------

    @Test
    fun `있는대요 를 있는데요 로`() {
        assertFixed("여기 있는데요", "여기 있는대요")
    }

    @Test
    fun `잘못 떨어진 어절을 합치면서 오타도 고친다`() {
        assertFixed("뭐 하세요", "뭐 하 새요")
    }

    @Test
    fun `아버지 가방 문장은 두 갈래 중 하나`() {
        // 문법적으로는 둘 다 맞는 문장이다. 어느 쪽이든 되고, 다른 걸로 망가뜨리지만 않으면 된다.
        val out = fix("아버지 가방에 들어가신다")
        assertTrue(out == "아버지가 방에 들어가신다" || out == "아버지 가방에 들어가신다", out)
    }

    // --- 어절 합치기 ------------------------------------------------------------

    @Test
    fun `떨어진 어절을 합친다`() {
        assertFixed("안녕하세요", "안녕 하세요")
        assertFixed("감사합니다", "감사 합니다")
        assertFixed("밥 먹었어요", "밥 먹었 어요")
        assertFixed("사랑합니다", "사랑 합니다")
    }

    // --- 어절 나누기 ------------------------------------------------------------

    @Test
    fun `붙여 쓴 것을 나눈다`() {
        // '할수있다' 는 규칙(SpacingRules)의 몫이라 여기서는 보지 않는다. 엔진 테스트가 본다.
        assertFixed("오늘은 날씨가 좋아서 기분이 좋다", "오늘은날씨가좋아서기분이좋다")
        assertFixed("밥 먹었어요", "밥먹었어요")
    }

    // --- 오타 ------------------------------------------------------------------

    @Test
    fun `사전에 없는 오타를 고친다`() {
        assertFixed("안녕하세요", "안녕하새요")
        assertFixed("했는데요", "햇는대요")
        // '됬어요' 도 말뭉치에 흔한 오타라 언어모델은 둘 중 하나를 고른다. 어느 쪽이든
        // 엔진의 어절 규칙('됬'→'됐')이 마무리한다.
        assertTrue(fix("됫어요") in setOf("됐어요", "됬어요"), fix("됫어요"))
        assertFixed("너무 좋아요", "너무 조아요")
    }

    // --- 오교정 방지 --------------------------------------------------------------

    @Test
    fun `멀쩡한 문장은 그대로 둔다`() {
        for (text in listOf(
            "나는 학교에 간다", "오늘 날씨가 정말 좋네요", "할 수 있다", "먹을 것 같다",
            "네가 좋아", "내가 좋아", "여기 와 봐", "집 밖에 나갔다", "돼지고기를 먹었다",
            "감사합니다", "안녕하세요", "잘 지내", "왜 그래", "밥 먹자",
            "카카오톡 열어 봐", "인스타그램 스토리 올렸어", "대박 사건", "친구야 놀자",
            "배고파 죽겠다", "삼성 갤럭시 폰", "김철수가 왔다", "치킨 시켜 먹자",
            "그 사람 진짜 웃겨", "우리 언제 만나", "내일 몇 시에 볼까", "지금 뭐 해",
            "이거 진짜 맛있다", "저는 학생입니다", "이것은 책상입니다", "선생님께 드렸다",
            "개 키우고 싶다", "게 먹으러 가자", "새로운 게임 나왔어", "세 명이 왔다",
            "매일 아침 운동해요", "메일 보냈어요", "되게 예쁘다", "돼지 키운다"
        )) {
            assertUntouched(text)
        }
    }

    @Test
    fun `한글이 아닌 것은 건드리지 않고 그 자리에서 문맥을 끊는다`() {
        assertUntouched("hello world")
        assertUntouched("3시에 만나요")
        assertUntouched("ㅋㅋㅋ 진짜")
        assertFixed("3시에 있는데요", "3시에 있는대요")
    }

    @Test
    fun `문장 부호가 붙은 어절은 부호를 지킨다`() {
        assertFixed("밥 먹었어요. 뭐 하세요", "밥 먹었어요. 뭐 하 새요")
        assertFixed("여기 있는데요.", "여기 있는대요.")
    }

    @Test
    fun `실시간 입력을 따라갈 만큼 빠르다`() {
        val samples = listOf(
            "여기 있는대요", "뭐 하 새요", "아버지 가방에 들어가신다", "오늘은날씨가좋아서기분이좋다",
            "내가 그 사람한테 몇 번이나", "행복해지는 게 아니라고", "어처구니가 없더라고"
        )
        repeat(5) { samples.forEach { corrector.correct(it, LanguageModel.BOS) } }
        val started = System.nanoTime()
        val rounds = 30
        repeat(rounds) { samples.forEach { corrector.correct(it, LanguageModel.BOS) } }
        val perCall = (System.nanoTime() - started) / (rounds.toLong() * samples.size) / 1_000_000.0
        println("창 하나에 평균 %.2f ms".format(perCall))
        assertTrue(perCall < 20.0, "너무 느리다: %.2f ms".format(perCall))
    }

    companion object {
        private val corrector: ContextCorrector by lazy {
            val dir = Files.createTempDirectory("spell-lm").toFile()
            dir.deleteOnExit()
            ContextCorrector(LanguageModel.open(dir), Spacer(SpacingDictionary.open(dir)))
        }
    }
}
