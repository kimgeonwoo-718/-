package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTextTest {

    @Test
    fun `마침표가 없으면 붙인다`() {
        val out = ChatText.normalize("저는 좋아요")
        assertEquals("저는 좋아요", out.core)
        assertEquals(".", out.ending)
        assertNull(out.emphasis)
    }

    @Test
    fun `의문사가 있으면 물음표를 붙인다`() {
        assertEquals("?", ChatText.normalize("오늘 기분이 어떠세요").ending)
        assertEquals("?", ChatText.normalize("지금 뭐 해").ending)
        assertEquals("?", ChatText.normalize("어디야").ending)
        assertEquals("?", ChatText.normalize("밥 먹었니").ending)
        assertEquals("?", ChatText.normalize("같이 갈까").ending)
    }

    @Test
    fun `의문사처럼 보이는 낱말에는 속지 않는다`() {
        // '왜냐하면' 은 '왜' 로 시작하지만 의문사가 아니다. 어절 통째로 맞을 때만 센다.
        assertEquals(".", ChatText.normalize("왜냐하면 바빴어").ending)
        assertEquals(".", ChatText.normalize("저는 좋아요").ending)
        assertEquals(".", ChatText.normalize("밥 먹었어").ending)
    }

    @Test
    fun `원문에 있던 부호는 그대로 쓴다`() {
        val out = ChatText.normalize("진짜?")
        assertEquals("진짜", out.core)
        assertEquals("?", out.ending)
        assertEquals("!", ChatText.normalize("대박!").ending)
    }

    @Test
    fun `웃음과 울음 자모를 떼어 낸다`() {
        val laugh = ChatText.normalize("진짜 웃겨 ㅋㅋㅋ")
        assertEquals("진짜 웃겨", laugh.core)
        assertEquals(ChatText.Emphasis.LAUGH, laugh.emphasis)

        val cry = ChatText.normalize("너무 슬퍼 ㅠㅠ")
        assertEquals("너무 슬퍼", cry.core)
        assertEquals(ChatText.Emphasis.CRY, cry.emphasis)

        // 자모만 있는 문장은 옮길 말이 없다.
        val only = ChatText.normalize("ㅋㅋㅋㅋ")
        assertEquals("", only.core)
        assertEquals(ChatText.Emphasis.LAUGH, only.emphasis)
    }

    @Test
    fun `뜻이 있는 자모 줄임말은 건드리지 않는다`() {
        val out = ChatText.normalize("ㅇㅇ")
        assertEquals("ㅇㅇ", out.core)
        assertNull(out.emphasis)
    }

    @Test
    fun `늘여 쓴 글자를 줄인다`() {
        assertEquals("좋아", ChatText.normalize("좋아아아아").core)
        assertEquals("네", ChatText.normalize("네에에에").core)
        // 두 번까지는 그대로 둔다 — 멀쩡한 낱말일 수 있다.
        assertEquals("고고", ChatText.normalize("고고").core)
    }

    @Test
    fun `빈 글은 빈 결과`() {
        val out = ChatText.normalize("   ")
        assertEquals("", out.core)
        assertEquals("", out.ending)
    }
}
