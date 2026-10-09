package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationPipelineTest {

    /** 부른 문장을 기록하는 가짜 번역기. 즉시 답한다. */
    private class FakeEngine(val answer: (String) -> String? = { "<$it>" }) : TranslationPipeline.Engine {
        val calls = ArrayList<String>()
        override fun translate(text: String, onResult: (String) -> Unit, onFailed: (Exception) -> Unit) {
            calls += text
            val reply = answer(text)
            if (reply == null) onFailed(RuntimeException("실패")) else onResult(reply)
        }
    }

    private val pipeline = TranslationPipeline(SentenceSplitter())

    private fun run(source: String, engine: TranslationPipeline.Engine, pipe: TranslationPipeline = pipeline): TranslationPipeline.Result {
        var out: TranslationPipeline.Result? = null
        pipe.translate(source, Phrasebook.ENGLISH, engine) { out = it }
        return out!!
    }

    @Test
    fun `문장마다 따로 옮겨 이어 붙인다`() {
        val engine = FakeEngine()
        val result = run("고양이가 소파 위에서 자고 있어. 내일 날씨가 흐릴 것 같아", engine)
        assertEquals(2, engine.calls.size)
        assertTrue(result.text.startsWith("<"), result.text)
        assertEquals(0, result.failed)
    }

    @Test
    fun `표에 있는 문장은 번역기를 부르지 않는다`() {
        val engine = FakeEngine()
        val result = run("안녕하세요", engine)
        assertEquals(0, engine.calls.size)
        assertTrue(result.text.startsWith("Hello"), result.text)
    }

    @Test
    fun `이미 옮긴 문장은 다시 옮기지 않는다`() {
        val engine = FakeEngine()
        run("고양이가 소파 위에서 자고 있어.", engine)
        val first = engine.calls.size
        run("고양이가 소파 위에서 자고 있어. 내일 날씨가 흐릴 것 같아", engine)
        // 두 번째 문장 하나만 새로 불렸다
        assertEquals(first + 1, engine.calls.size)
    }

    @Test
    fun `못 옮긴 문장은 원문을 남기고 센다`() {
        val engine = FakeEngine { if (it.contains("날씨")) null else "ok" }
        val result = run("고양이가 소파 위에서 자고 있어. 내일 날씨가 흐릴 것 같아.", engine)
        assertEquals(1, result.failed)
        assertTrue("내일 날씨가 흐릴 것 같아." in result.text, result.text)
    }

    @Test
    fun `ㅋㅋ만 있는 문장은 감탄만 남긴다`() {
        val engine = FakeEngine()
        val result = run("ㅋㅋㅋ", engine)
        assertEquals(0, engine.calls.size)
        assertEquals("lol", result.text)
    }

    @Test
    fun `비동기 번역기도 끝났을 때 한 번만 알린다`() {
        val pending = ArrayList<() -> Unit>()
        val engine = TranslationPipeline.Engine { text, onResult, _ -> pending += { onResult("[$text]") } }
        var done = 0
        var text = ""
        pipeline.translate("고양이가 소파 위에서 자고 있어. 내일 날씨가 흐릴 것 같아.", Phrasebook.ENGLISH, engine) { done++; text = it.text }
        assertEquals(0, done)
        pending[0](); assertEquals(0, done)
        pending[1](); assertEquals(1, done)
        assertTrue(text.contains("["), text)
    }
}
