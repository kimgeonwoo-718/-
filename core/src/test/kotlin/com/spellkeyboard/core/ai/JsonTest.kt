package com.spellkeyboard.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTest {

    @Test
    fun `값을 읽는다`() {
        assertEquals("hi", Json.parse(""""hi""""))
        assertEquals(12.0, Json.parse("12"))
        assertEquals(-1.5, Json.parse("-1.5"))
        assertEquals(true, Json.parse("true"))
        assertNull(Json.parse("null"))
        assertEquals(listOf(1.0, 2.0), Json.parse("[1, 2]"))
        assertEquals(mapOf("a" to 1.0), Json.parse("""{"a": 1}"""))
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
    }

    @Test
    fun `이스케이프를 풀어 읽는다`() {
        assertEquals("따옴표 \" 와 역슬래시 \\", Json.parse(""""따옴표 \" 와 역슬래시 \\""""))
        assertEquals("줄\n바꿈\t탭", Json.parse(""""줄\n바꿈\t탭""""))
        // 원시 문자열이라 한 는 이스케이프되지 않고 파서에 그대로 전달된다.
        assertEquals("한", Json.parse(""""한""""))
    }

    @Test
    fun `쓴 것을 다시 읽으면 그대로다`() {
        for (original in listOf(
            "평범한 글",
            "따옴표 \" 포함",
            "역슬래시 \\ 포함",
            "줄\n바꿈",
            "탭\t문자",
            "이모지 😀",
            "제어문자 \u0001 와 폼피드 \u000C"
        )) {
            assertEquals(original, Json.parse(Json.quote(original)), "왕복이 깨졌다: $original")
        }
    }

    @Test
    fun `중첩된 값을 따라 내려간다`() {
        val root = Json.parse("""{"a":{"b":{"c":"깊다"}}}""")
        assertEquals("깊다", Json.dig(root, "a", "b", "c"))
        assertNull(Json.dig(root, "a", "x", "c"))
        assertNull(Json.dig(null, "a"))
    }

    @Test
    fun `망가진 JSON 은 예외로 알린다`() {
        for (broken in listOf("{", """{"a"}""", """{"a":}""", "[1,", """"닫히지 않은""")) {
            assertFailsWith<Exception>("$broken 를 통과시켰다") { Json.parse(broken) }
        }
    }
}
