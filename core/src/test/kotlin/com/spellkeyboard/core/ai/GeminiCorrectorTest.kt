package com.spellkeyboard.core.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiCorrectorTest {

    /** 실제로 오간 요청을 붙잡아 두는 가짜 통신부. */
    private class FakeTransport(
        private val response: GeminiCorrector.HttpResponse
    ) : GeminiCorrector.Transport {
        var url: String? = null
        var apiKey: String? = null
        var body: String? = null

        override fun post(
            url: String,
            apiKey: String,
            body: String
        ): GeminiCorrector.HttpResponse {
            this.url = url
            this.apiKey = apiKey
            this.body = body
            return response
        }
    }

    private fun ok(text: String) = GeminiCorrector.HttpResponse(
        200,
        """{"candidates":[{"content":{"parts":[{"text":${Json.quote(text)}}],"role":"model"},
           "finishReason":"STOP"}]}"""
    )

    @Test
    fun `교정문을 꺼내 온다`() {
        val transport = FakeTransport(ok("안녕하세요 반갑습니다"))
        val result = GeminiCorrector("key", transport = transport).correct("안녕하새요 반갑슴니다")

        assertEquals("안녕하세요 반갑습니다", result.getOrNull())
    }

    @Test
    fun `요청에 원문과 지시문을 함께 싣는다`() {
        val transport = FakeTransport(ok("고침"))
        GeminiCorrector("my-key", model = "gemini-test", transport = transport).correct("원문")

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent",
            transport.url
        )
        assertEquals("my-key", transport.apiKey)

        val body = transport.body.orEmpty()
        assertContains(body, "\"원문\"")
        assertContains(body, "system_instruction")
        // 온도가 0 이어야 같은 문장에 같은 결과가 나온다.
        assertContains(body, "\"temperature\":0")
    }

    @Test
    fun `따옴표와 줄바꿈이 든 글도 깨지지 않는다`() {
        val transport = FakeTransport(ok("고침"))
        val tricky = "그가 \"안녕\"이라 했다.\n다음 줄\t탭"
        GeminiCorrector("key", transport = transport).correct(tricky)

        // 요청이 다시 파싱되면 이스케이프가 제대로 된 것이다.
        val parsed = Json.parse(transport.body.orEmpty())
        val contents = Json.dig(parsed, "contents") as List<*>
        val parts = Json.dig(contents.first(), "parts") as List<*>
        assertEquals(tricky, Json.dig(parts.first(), "text"))
    }

    @Test
    fun `서버 오류 메시지를 그대로 전한다`() {
        val transport = FakeTransport(
            GeminiCorrector.HttpResponse(
                400,
                """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
            )
        )
        val result = GeminiCorrector("bad", transport = transport).correct("원문")

        assertTrue(result.isFailure)
        assertEquals("API key not valid", result.exceptionOrNull()?.message)
    }

    @Test
    fun `안전 필터에 걸리면 실패로 돌려준다`() {
        val transport = FakeTransport(
            GeminiCorrector.HttpResponse(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        )
        val result = GeminiCorrector("key", transport = transport).correct("원문")

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "SAFETY")
    }

    @Test
    fun `응답이 비면 실패로 돌려준다`() {
        val transport = FakeTransport(
            GeminiCorrector.HttpResponse(
                200,
                """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""
            )
        )
        val result = GeminiCorrector("key", transport = transport).correct("원문")

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "MAX_TOKENS")
    }

    @Test
    fun `말이 안 되는 응답에도 죽지 않는다`() {
        val transport = FakeTransport(GeminiCorrector.HttpResponse(200, "이건 JSON 이 아니다"))
        assertTrue(GeminiCorrector("key", transport = transport).correct("원문").isFailure)
    }

    @Test
    fun `빈 글은 보내지 않는다`() {
        val transport = FakeTransport(ok("고침"))
        assertTrue(GeminiCorrector("key", transport = transport).correct("   ").isFailure)
        assertEquals(null, transport.body, "요청을 보내지 말았어야 한다")
    }
}
