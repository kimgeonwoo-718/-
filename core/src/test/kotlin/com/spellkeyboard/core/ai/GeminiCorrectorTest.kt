package com.spellkeyboard.core.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiCorrectorTest {

    /** 실제로 오간 요청을 붙잡아 두는 가짜 통신부. */
    private class FakeTransport(
        private val response: GeminiCorrector.HttpResponse
    ) : GeminiCorrector.Transport {
        var method: String? = null
        var url: String? = null
        var apiKey: String? = null
        var body: String? = null
        var called = false

        override fun send(
            method: String,
            url: String,
            apiKey: String,
            body: String?
        ): GeminiCorrector.HttpResponse {
            this.method = method
            this.url = url
            this.apiKey = apiKey
            this.body = body
            this.called = true
            return response
        }
    }

    private fun ok(text: String) = GeminiCorrector.HttpResponse(
        200,
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":${Json.quote(text)}}]," +
            "\"role\":\"model\"},\"finishReason\":\"STOP\"}]}"
    )

    // --- 교정 -----------------------------------------------------------------

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

        assertEquals("POST", transport.method)
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
                "{\"error\":{\"code\":400,\"message\":\"API key not valid\"," +
                    "\"status\":\"INVALID_ARGUMENT\"}}"
            )
        )
        val result = GeminiCorrector("bad", transport = transport).correct("원문")

        assertTrue(result.isFailure)
        assertEquals("API key not valid", result.exceptionOrNull()?.message)
    }

    @Test
    fun `안전 필터에 걸리면 실패로 돌려준다`() {
        val transport = FakeTransport(
            GeminiCorrector.HttpResponse(
                200,
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"
            )
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
                "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}"
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
        assertTrue(!transport.called, "요청을 보내지 말았어야 한다")
    }

    // --- 모델 목록 -------------------------------------------------------------

    @Test
    fun `쓸 수 있는 모델만 골라 온다`() {
        val transport = FakeTransport(
            GeminiCorrector.HttpResponse(
                200,
                "{\"models\":[" +
                    "{\"name\":\"models/gemini-a\",\"supportedGenerationMethods\":" +
                    "[\"generateContent\"]}," +
                    "{\"name\":\"models/embed-only\",\"supportedGenerationMethods\":" +
                    "[\"embedContent\"]}," +
                    "{\"name\":\"models/gemini-b\",\"supportedGenerationMethods\":" +
                    "[\"countTokens\",\"generateContent\"]}]}"
            )
        )
        val models = GeminiCorrector("key", transport = transport).availableModels()

        assertEquals(listOf("gemini-a", "gemini-b"), models.getOrNull())
        assertEquals("GET", transport.method)
        assertEquals(null, transport.body)
    }

    // --- 낡은 모델 이름 자동 교체 ---------------------------------------------

    /** 호출 순서대로 다른 답을 돌려주는 통신부. */
    private class ScriptedTransport(
        private vararg val responses: GeminiCorrector.HttpResponse
    ) : GeminiCorrector.Transport {
        val urls = mutableListOf<String>()

        override fun send(
            method: String,
            url: String,
            apiKey: String,
            body: String?
        ): GeminiCorrector.HttpResponse {
            urls += url
            return responses[minOf(urls.size - 1, responses.size - 1)]
        }
    }

    private fun modelList(vararg names: String) = GeminiCorrector.HttpResponse(
        200,
        "{\"models\":[" + names.joinToString(",") {
            "{\"name\":\"models/$it\",\"supportedGenerationMethods\":[\"generateContent\"]}"
        } + "]}"
    )

    private fun noSuchModel(name: String) = GeminiCorrector.HttpResponse(
        404,
        "{\"error\":{\"code\":404,\"message\":\"models/$name is not found for API " +
            "version v1beta\",\"status\":\"NOT_FOUND\"}}"
    )

    @Test
    fun `없는 모델이면 목록을 받아 갈아 끼우고 다시 시도한다`() {
        val transport = ScriptedTransport(
            noSuchModel("gemini-옛것"),
            modelList("gemini-3.0-pro", "gemini-3.0-flash", "gemini-2.5-flash"),
            ok("안녕하세요")
        )
        val corrector = GeminiCorrector("key", model = "gemini-옛것", transport = transport)

        assertEquals("안녕하세요", corrector.correct("안녕하새요").getOrNull())
        assertEquals("gemini-3.0-flash", corrector.activeModel)
        assertContains(transport.urls.last(), "gemini-3.0-flash:generateContent")
    }

    @Test
    fun `모델을 지목한 400 도 갈아 끼운다`() {
        val transport = ScriptedTransport(
            GeminiCorrector.HttpResponse(
                400,
                "{\"error\":{\"code\":400,\"message\":\"This model models/gemini-2.5-flash " +
                    "is not supported\",\"status\":\"INVALID_ARGUMENT\"}}"
            ),
            modelList("gemini-2.0-flash"),
            ok("고침")
        )
        val corrector = GeminiCorrector("key", transport = transport)

        assertEquals("고침", corrector.correct("원문").getOrNull())
        assertEquals("gemini-2.0-flash", corrector.activeModel)
    }

    @Test
    fun `갈아 끼울 것이 없으면 원래 이유를 알린다`() {
        val transport = ScriptedTransport(
            noSuchModel("gemini-옛것"),
            modelList("gemini-embedding-001"),
            ok("여기까지 오면 안 된다")
        )
        val corrector = GeminiCorrector("key", model = "gemini-옛것", transport = transport)
        val result = corrector.correct("원문")

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "is not found")
        assertEquals("gemini-옛것", corrector.activeModel)
    }

    @Test
    fun `키가 틀린 것뿐이면 모델을 건드리지 않는다`() {
        val transport = ScriptedTransport(
            GeminiCorrector.HttpResponse(
                403,
                "{\"error\":{\"message\":\"API key not valid\"}}"
            )
        )
        val corrector = GeminiCorrector("bad", transport = transport)

        assertEquals("API key not valid", corrector.correct("원문").exceptionOrNull()?.message)
        assertEquals(GeminiCorrector.DEFAULT_MODEL, corrector.activeModel)
        assertEquals(1, transport.urls.size, "목록까지 받아 볼 이유가 없다")
    }

    @Test
    fun `교정에 쓸 만한 모델을 고른다`() {
        // 최신 세대 > flash > 정식 출시본 순.
        assertEquals(
            "gemini-3.0-flash",
            GeminiCorrector.pickModel(
                listOf(
                    "gemini-3.0-flash",
                    "gemini-3.0-flash-preview-11-2025",
                    "gemini-3.0-flash-lite",
                    "gemini-3.0-pro",
                    "gemini-2.5-flash"
                )
            )
        )
        // 글을 만들어 내지 않는 것들은 후보가 아니다.
        assertEquals(
            null,
            GeminiCorrector.pickModel(
                listOf("gemini-embedding-001", "imagen-4.0-generate-001", "gemini-2.0-flash-tts")
            )
        )
    }

    // --- 진단 -----------------------------------------------------------------

    @Test
    fun `진단은 키가 없으면 거기서 멈춘다`() {
        val transport = ScriptedTransport(ok("안 불려야 한다"))
        val checks = GeminiCorrector("", transport = transport).diagnose()

        assertEquals(1, checks.size, "키가 없으면 네트워크를 만질 이유가 없다")
        assertFalse(checks.first().ok)
        assertEquals(0, transport.urls.size)
    }

    @Test
    fun `진단은 연결이 막히면 그 단계를 짚는다`() {
        val transport = ScriptedTransport(
            GeminiCorrector.HttpResponse(
                403,
                "{\"error\":{\"message\":\"API key not valid\"}}"
            )
        )
        val checks = GeminiCorrector("bad-key", transport = transport).diagnose()

        val connection = checks.first { it.name == "서버 연결" }
        assertFalse(connection.ok)
        assertContains(connection.detail, "API key not valid")
        // 연결이 안 되는데 그 뒤 단계를 재 볼 이유가 없다.
        assertEquals("서버 연결", checks.last().name)
    }

    @Test
    fun `진단은 끝까지 통과하면 교정 결과를 보여준다`() {
        val transport = ScriptedTransport(
            modelList("gemini-2.5-flash"),
            ok("안녕하세요 오늘 날씨가 좋아요")
        )
        val checks = GeminiCorrector("key", transport = transport).diagnose()

        assertTrue(checks.all { it.ok }, "전부 통과해야 한다: $checks")
        assertContains(checks.last().detail, "안녕하세요 오늘 날씨가 좋아요")
    }

    @Test
    fun `진단은 모델이 갈렸으면 새 이름을 알려준다`() {
        val transport = ScriptedTransport(
            modelList("gemini-3.0-flash"),          // 목록 조회
            noSuchModel("gemini-2.5-flash"),        // 첫 교정 시도 — 거절
            modelList("gemini-3.0-flash"),          // 갈아 끼우려고 다시 조회
            ok("고침")
        )
        val checks = GeminiCorrector("key", transport = transport).diagnose()

        val swap = checks.first { it.name == "모델 자동 교체" }
        assertContains(swap.detail, "gemini-3.0-flash")
    }

    @Test
    fun `모델 목록 오류도 메시지를 전한다`() {
        val transport = FakeTransport(
            GeminiCorrector.HttpResponse(
                403,
                "{\"error\":{\"message\":\"API key expired\",\"status\":\"PERMISSION_DENIED\"}}"
            )
        )
        val result = GeminiCorrector("key", transport = transport).availableModels()

        assertTrue(result.isFailure)
        assertEquals("API key expired", result.exceptionOrNull()?.message)
    }
}
