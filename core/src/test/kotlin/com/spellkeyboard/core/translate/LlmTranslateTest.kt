package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmTranslateTest {

    // --- 지시문 -------------------------------------------------------------------------------

    @Test
    fun `세 언어 지시문이 다 있고 목표 언어 이름과 보기 여섯 개를 담는다`() {
        val expected = mapOf("en" to "English", "ja" to "Japanese", "zh" to "Simplified Chinese")
        for ((code, name) in expected) {
            val prompt = assertNotNull(LlmPrompt.system(code), "$code 지시문 자원이 없다")
            assertTrue(prompt.startsWith("Translate Korean chat messages into $name."), code)
            val examples = prompt.substringAfter("Examples\n").lines().filter { it.contains(" -> ") }
            assertEquals(6, examples.size, "$code 보기는 여섯 개")
            assertTrue("leave no Hangul" in prompt, code)
        }
    }

    @Test
    fun `모르는 언어는 지시문이 없다`() {
        assertNull(LlmPrompt.system("fr"))
        assertNull(LlmPrompt.system(""))
    }

    @Test
    fun `지시문은 짧다 — 폰에서 처음 계산하는 시간이 이 길이에 달렸다`() {
        for (code in LlmPrompt.SUPPORTED) {
            val prompt = assertNotNull(LlmPrompt.system(code))
            assertTrue(prompt.length < 1500, "$code 지시문이 ${prompt.length}자다 — 1,500자를 넘기지 마라")
        }
    }

    @Test
    fun `보기의 한국어는 세 언어에서 같다`() {
        fun koreanOf(code: String) = LlmPrompt.system(code)!!.substringAfter("Examples\n").lines()
            .filter { " -> " in it }.map { it.substringBefore(" -> ") }
        assertEquals(koreanOf("en"), koreanOf("ja"))
        assertEquals(koreanOf("en"), koreanOf("zh"))
    }

    // --- 모델이 내놓은 글 ------------------------------------------------------------------------

    @Test
    fun `멀쩡한 번역은 그대로 쓴다`() {
        assertEquals("お会計は別でお願いします。", LlmOutput.clean("お会計は別でお願いします。", "계산은 따로 해 주세요", "ja"))
        assertEquals("I'm starving 😭", LlmOutput.clean("  I'm starving 😭\n", "배고파 ㅠㅠ", "en"))
        assertEquals("请单独结账。", LlmOutput.clean("请单独结账。", "계산은 따로 해 주세요", "zh"))
    }

    @Test
    fun `머리말과 따옴표 코드 울타리를 뗀다`() {
        assertEquals("Thank you.", LlmOutput.clean("Translation: Thank you.", "고마워", "en"))
        assertEquals("ありがとう", LlmOutput.clean("「ありがとう」", "고마워", "ja"))
        assertEquals("谢谢", LlmOutput.clean("```\n谢谢\n```", "고마워", "zh"))
        // 안에 따옴표가 또 있으면 원문의 인용이므로 그대로 둔다.
        assertEquals("\"Hi,\" he said", LlmOutput.clean("\"Hi,\" he said", "안녕이라고 했어", "en").also { })
    }

    @Test
    fun `한글이 남은 번역은 쓰지 않는다`() {
        assertNull(LlmOutput.clean("わぁ、すごい！本当に축하해！", "와 대박 진짜 축하해", "ja"))
        assertNull(LlmOutput.clean("오랜만에见到朋友们", "오랜만에 친구들 만나서", "zh"))
        // 자모(ㅋㅋ·ㅠㅠ)는 음절이 아니라서 봐준다.
        assertEquals("カカオトーク送ったのに既読スルーだよㅠㅠ", LlmOutput.clean("カカオトーク送ったのに既読スルーだよㅠㅠ", "카톡 보냈는데 읽씹이야ㅠㅠ", "ja"))
    }

    @Test
    fun `빈 답과 같은 말을 되풀이한 답은 쓰지 않는다`() {
        assertNull(LlmOutput.clean("   ", "안녕", "en"))
        assertNull(LlmOutput.clean("Hello ".repeat(40), "안녕", "en"))
        assertEquals("Hello", LlmOutput.clean("Hello", "안녕", "en"))
    }

    @Test
    fun `엉뚱한 언어로 나온 답은 쓰지 않는다`() {
        assertNull(LlmOutput.clean("今天天气真好啊朋友", "오늘 날씨 좋다", "ja")) // 일본어를 달랬는데 한자뿐
        assertNull(LlmOutput.clean("今日はいい天気ですね", "오늘 날씨 좋다", "zh")) // 중국어를 달랬는데 가나
        assertNull(LlmOutput.clean("今日はいい天気ですね", "오늘 날씨 좋다", "en"))
        assertEquals("OK 😂", LlmOutput.clean("OK 😂", "ㅇㅋ", "ja")) // 글자가 거의 없으면 어느 언어든 맞다
    }

    // --- 대신 옮기기 ---------------------------------------------------------------------------

    @Test
    fun `앞 번역기가 못 하면 뒤 번역기가 옮긴다`() {
        val calls = ArrayList<String>()
        val primary = TranslationPipeline.Engine { text, _, onFailed -> calls += "primary:$text"; onFailed(IllegalStateException("x")) }
        val secondary = TranslationPipeline.Engine { text, onResult, _ -> calls += "secondary:$text"; onResult("hello") }
        var got: String? = null
        FallbackEngine(primary, secondary).translate("안녕", { got = it }, { got = "FAILED" })
        assertEquals("hello", got)
        assertEquals(listOf("primary:안녕", "secondary:안녕"), calls)
    }

    @Test
    fun `앞 번역기가 하면 뒤 번역기는 부르지 않는다`() {
        var secondaryCalled = false
        val primary = TranslationPipeline.Engine { _, onResult, _ -> onResult("hi") }
        val secondary = TranslationPipeline.Engine { _, _, _ -> secondaryCalled = true }
        var got: String? = null
        FallbackEngine(primary, secondary).translate("안녕", { got = it }, { })
        assertEquals("hi", got)
        assertTrue(!secondaryCalled)
    }

    @Test
    fun `둘 다 못 하면 실패로 알린다`() {
        val failing = TranslationPipeline.Engine { _, _, onFailed -> onFailed(IllegalStateException("x")) }
        var failed = false
        FallbackEngine(failing, failing).translate("안녕", { }, { failed = true })
        assertTrue(failed)
    }
}
