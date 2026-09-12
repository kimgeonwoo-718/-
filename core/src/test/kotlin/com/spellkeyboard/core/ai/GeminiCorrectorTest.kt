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
        val badModel = GeminiCorrector.HttpResponse(
            400,
            "{\"error\":{\"code\":400,\"message\":\"This model models/" +
                GeminiCorrector.DEFAULT_MODEL + " is not supported\"," +
                "\"status\":\"INVALID_ARGUMENT\"}}"
        )
        val transport = ScriptedTransport(
            badModel,   // 1차
            badModel,   // 숙고를 빼고 한 번 더 — 이유가 그것이 아니었으니 똑같이 거절
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
            "gemini-3.0-flash-lite",
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

    // --- 서버 혼잡 재시도 -------------------------------------------------------

    private fun overloaded() = GeminiCorrector.HttpResponse(
        503,
        "{\"error\":{\"code\":503,\"message\":\"This model is currently experiencing " +
            "high demand. Spikes in demand are usually temporary. Please try again later.\"," +
            "\"status\":\"UNAVAILABLE\"}}"
    )

    /** 기다리지 않는 교정기. 테스트가 실제로 잠들 이유가 없다. */
    private fun corrector(
        transport: GeminiCorrector.Transport,
        model: String = GeminiCorrector.DEFAULT_MODEL
    ) = GeminiCorrector("key", model = model, transport = transport, sleep = {})

    @Test
    fun `붐비면 기다리지 않고 다른 모델로 옮긴다`() {
        val waits = mutableListOf<Long>()
        val transport = ScriptedTransport(
            modelList("gemini-2.5-flash-lite", "gemini-2.0-flash-lite"), // 미리 받아 둔 목록
            overloaded(),                                               // 1차 — 붐빔
            ok("안녕하세요")                                             // 옮긴 모델에서 성공
        )
        val corrector = GeminiCorrector("key", transport = transport, sleep = { waits += it })
        corrector.prefetchModels()

        assertEquals("안녕하세요", corrector.correct("안녕하새요").getOrNull())
        assertEquals(emptyList(), waits, "옮길 모델이 있으면 기다릴 이유가 없다")
    }

    @Test
    fun `목록을 미리 받아 두면 교정할 때 다시 받지 않는다`() {
        val transport = ScriptedTransport(modelList("gemini-2.0-flash-lite"), ok("고침"))
        val corrector = corrector(transport)
        corrector.prefetchModels()
        val afterPrefetch = transport.urls.size

        corrector.correct("원문")
        corrector.correct("원문 둘")

        // 교정 두 번에 요청 두 번. 목록을 다시 받았다면 더 늘었을 것이다.
        assertEquals(afterPrefetch + 2, transport.urls.size)
    }

    @Test
    fun `쓸 만한 모델이 전부 붐비면 그제야 한 번 쉬었다 다시 본다`() {
        val waits = mutableListOf<Long>()
        val transport = ScriptedTransport(
            modelList("gemini-2.5-flash-lite", "gemini-2.0-flash"),
            overloaded(),                        // 첫 모델 — 붐빔
            overloaded(),                        // 옮겨 간 모델도 붐빔
            ok("고침")
        )
        val corrector = GeminiCorrector("key", transport = transport, sleep = { waits += it })
        corrector.prefetchModels()

        assertEquals("고침", corrector.correct("원문").getOrNull())
        assertEquals(listOf(700L), waits, "마지막에 딱 한 번만 쉰다")
    }

    @Test
    fun `키가 틀린 것은 모델을 옮기지도 기다리지도 않는다`() {
        val transport = ScriptedTransport(
            GeminiCorrector.HttpResponse(400, "{\"error\":{\"message\":\"API key not valid\"}}")
        )
        assertTrue(corrector(transport).correct("원문").isFailure)
        // 400 이면 숙고 설정 탓인지 한 번 확인하느라 두 번 간다. 그 뒤로는 멈춘다 —
        // 모델을 옮기거나 기다렸다 또 보내 봐야 똑같이 틀린다.
        assertEquals(2, transport.urls.size)
    }

    @Test
    fun `교정에는 가볍고 덜 붐비는 모델을 고른다`() {
        // lite 가 빠르고 싸고 덜 붐빈다. 맞춤법 교정에 큰 모델이 필요 없다.
        assertEquals(
            "gemini-2.5-flash-lite",
            GeminiCorrector.pickModel(
                listOf(
                    "gemini-2.5-pro",
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-2.0-flash-lite"
                )
            )
        )
    }

    @Test
    fun `타임아웃도 다른 모델로 옮겨 본다`() {
        // 통신부가 예외를 던져도 재시도·모델 교체를 건너뛰면 안 된다.
        var call = 0
        val transport = GeminiCorrector.Transport { _, url, _, _ ->
            call++
            when {
                url.contains("?pageSize") -> modelList("gemini-2.5-flash-lite")
                call == 2 -> throw java.net.SocketTimeoutException("timeout")
                else -> ok("고침")
            }
        }
        val corrector = GeminiCorrector("key", transport = transport, sleep = {})
        corrector.prefetchModels()

        assertEquals("고침", corrector.correct("원문").getOrNull())
    }

    @Test
    fun `숙고를 꺼서 보낸다`() {
        val transport = FakeTransport(ok("고침"))
        GeminiCorrector("key", transport = transport).correct("원문")

        // 맞춤법 교정에 숙고는 필요 없다 — 켜 두면 느리고 비싸다.
        assertContains(transport.body.orEmpty(), "\"thinkingBudget\":0")
    }

    @Test
    fun `숙고 항목을 모르는 모델에는 빼고 다시 보낸다`() {
        val bodies = mutableListOf<String?>()
        var call = 0
        val transport = GeminiCorrector.Transport { _, _, _, body ->
            bodies += body
            call++
            if (call == 1) {
                // 실기기가 실제로 돌려준 문구. 어떤 항목이 문제인지 말해 주지 않는다.
                GeminiCorrector.HttpResponse(
                    400,
                    "{\"error\":{\"message\":\"Request contains an invalid argument.\"}}"
                )
            } else {
                ok("고침")
            }
        }
        val corrector = GeminiCorrector("key", transport = transport, sleep = {})

        assertEquals("고침", corrector.correct("원문").getOrNull())
        assertContains(bodies[0].orEmpty(), "thinkingBudget")
        assertTrue(!bodies[1].orEmpty().contains("thinkingBudget"), "두 번째는 빼고 보내야 한다")
    }

    @Test
    fun `숙고 거절은 그 모델에만 기억하고 옮긴 모델에는 다시 끄고 보낸다`() {
        val bodies = mutableListOf<String?>()
        val urls = mutableListOf<String>()
        var call = 0
        val transport = GeminiCorrector.Transport { _, url, _, body ->
            call++
            if (body == null) return@Transport modelList("gemini-a", "gemini-b")
            bodies += body
            urls += url
            when (call) {
                // gemini-a: 숙고 끄기를 거절하고, 빼고 보내면 붐빔 → 옮긴다
                2 -> GeminiCorrector.HttpResponse(400, "{\"error\":{\"message\":\"Request contains an invalid argument.\"}}")
                3 -> overloaded()
                else -> ok("고침")
            }
        }
        val corrector = GeminiCorrector("key", model = "gemini-a", transport = transport, sleep = {})
        corrector.prefetchModels()

        assertEquals("고침", corrector.correct("원문").getOrNull())
        assertContains(bodies[0].orEmpty(), "thinkingBudget", message = "처음엔 끄고 보낸다")
        assertTrue(!bodies[1].orEmpty().contains("thinkingBudget"), "거절한 모델엔 빼고")
        assertContains(urls[2], "gemini-b")
        assertContains(bodies[2].orEmpty(), "thinkingBudget", message = "옮긴 모델에는 다시 끄고 보낸다 — 숙고 토큰은 돈이다")
    }

    @Test
    fun `미리 받을 때 못 쓰는 이름이면 그 자리에서 갈아 끼운다`() {
        // 교정할 때 실패하고 옮기면 그 왕복이 사용자 대기 시간이 된다.
        val transport = ScriptedTransport(modelList("gemini-3.8-flash"))
        val corrector = GeminiCorrector("key", model = "없는-이름", transport = transport)

        corrector.prefetchModels()
        assertEquals("gemini-3.8-flash", corrector.activeModel)
    }

    // --- 중계 서버 --------------------------------------------------------------

    private val PROXY = "https://spell.example/v1beta/models"

    @Test
    fun `중계 서버로 보내면 호스트만 바뀌고 키는 비어 있다`() {
        val transport = FakeTransport(ok("고침"))
        GeminiCorrector("", model = "gemini-x", transport = transport, baseUrl = PROXY).correct("원문")

        assertEquals("$PROXY/gemini-x:generateContent", transport.url)
        assertEquals("", transport.apiKey)
    }

    @Test
    fun `서버가 헤더로 알려 준 남은 횟수를 기억한다`() {
        val response = GeminiCorrector.HttpResponse(
            200,
            ok("고침").body,
            mapOf("x-plan" to "free", "x-quota-remaining" to "3", "x-quota-limit" to "5")
        )
        val corrector = GeminiCorrector("", transport = FakeTransport(response), baseUrl = PROXY)
        corrector.correct("원문")

        assertEquals(GeminiCorrector.Quota(3, 5, "free"), corrector.lastQuota)
    }

    @Test
    fun `구글 직통이면 요금 상태가 없다`() {
        val corrector = GeminiCorrector("key", transport = FakeTransport(ok("고침")))
        corrector.correct("원문")
        assertEquals(null, corrector.lastQuota)
    }

    @Test
    fun `한도 초과 402 는 모델을 옮기지 않고 바로 알린다`() {
        val transport = ScriptedTransport(
            GeminiCorrector.HttpResponse(
                402,
                "{\"error\":{\"message\":\"free_daily_limit\"}}",
                mapOf("x-plan" to "free", "x-quota-remaining" to "0", "x-quota-limit" to "5")
            )
        )
        val corrector = GeminiCorrector("", transport = transport, sleep = {}, baseUrl = PROXY)
        val result = corrector.correct("원문")

        assertTrue(result.isFailure)
        assertContains(GeminiCorrector.explain(result.exceptionOrNull()?.message), "구독")
        // 서버가 한도라고 했는데 다른 모델을 두드려 봐야 소용없다. 한 번으로 끝낸다.
        assertEquals(1, transport.urls.size)
        assertEquals(0, corrector.lastQuota?.remaining)
    }

    @Test
    fun `중계 서버 경유면 키 없이도 진단이 끝까지 간다`() {
        val transport = ScriptedTransport(modelList("gemini-x"), ok("안녕하세요"))
        val checks = GeminiCorrector("", model = "gemini-x", transport = transport, baseUrl = PROXY).diagnose()

        assertTrue(checks.all { it.ok }, "$checks")
        assertContains(checks.first().detail, "중계")
    }

    // --- 오류 문구 --------------------------------------------------------------

    @Test
    fun `서버 메시지를 짧은 한국어로 바꾼다`() {
        assertContains(
            GeminiCorrector.explain(
                "This model is currently experiencing high demand. " +
                    "Spikes in demand are usually temporary."
            ),
            "붐빕니다"
        )
        assertContains(GeminiCorrector.explain("API key not valid"), "API 키")
        assertContains(GeminiCorrector.explain("Quota exceeded"), "한도")
        assertContains(GeminiCorrector.explain("models/x is not found"), "모델")
        // 모르는 메시지는 원문을 그대로 둔다 — 삼키면 원인을 잃는다.
        assertEquals("뭔가 새로운 오류", GeminiCorrector.explain("뭔가 새로운 오류"))
        assertEquals("알 수 없는 오류", GeminiCorrector.explain(null))
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
        // 진단 화면도 번역된 문구를 쓴다 — 영어 원문은 좁은 화면에서 잘린다.
        assertContains(connection.detail, "API 키")
        // 연결이 안 되는데 그 뒤 단계를 재 볼 이유가 없다.
        assertEquals("서버 연결", checks.last().name)
    }

    @Test
    fun `진단은 끝까지 통과하면 교정 결과를 보여준다`() {
        val transport = ScriptedTransport(
            modelList(GeminiCorrector.DEFAULT_MODEL),
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
    fun `최신 세대보다 값싼 등급을 먼저 고른다`() {
        // 실기기가 실제로 받아 온 목록. 예전 기준으로는 3.8-flash 가 뽑혀서
        // 비싼 모델을 계속 썼다.
        assertEquals(
            "gemini-3.5-flash-lite",
            GeminiCorrector.pickModel(
                listOf(
                    "gemini-3.8-flash",
                    "gemini-3.5-flash-lite",
                    "gemini-3.7-flash",
                    "gemini-3.6-flash"
                )
            )
        )
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
