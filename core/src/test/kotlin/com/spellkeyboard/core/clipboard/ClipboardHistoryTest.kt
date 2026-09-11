package com.spellkeyboard.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardHistoryTest {

    private class FakeStore : ClipboardHistory.Store {
        var items: List<String> = emptyList()
        override fun read(): List<String> = items
        override fun write(items: List<String>) {
            this.items = items
        }
    }

    private val store = FakeStore()
    private val history = ClipboardHistory(store)

    @Test
    fun `새로 복사한 것이 맨 앞에 온다`() {
        history.add("먼저")
        history.add("나중")

        assertEquals(listOf("나중", "먼저"), history.items())
    }

    @Test
    fun `같은 글을 다시 복사하면 맨 앞으로 올린다`() {
        history.add("가")
        history.add("나")
        history.add("다")
        history.add("가")

        assertEquals(listOf("가", "다", "나"), history.items(), "같은 글이 두 번 있으면 안 된다")
    }

    @Test
    fun `방금 담은 것을 또 담지 않는다`() {
        assertTrue(history.add("같은 글"))
        assertFalse(history.add("같은 글"), "맨 앞과 같으면 담을 이유가 없다")
        assertEquals(1, history.items().size)
    }

    @Test
    fun `앞뒤 공백은 떼고 담는다`() {
        history.add("  글자  ")
        assertEquals(listOf("글자"), history.items())
    }

    @Test
    fun `빈 글은 담지 않는다`() {
        assertFalse(history.add(""))
        assertFalse(history.add("   \n  "))
        assertEquals(emptyList(), history.items())
    }

    @Test
    fun `너무 긴 글은 담지 않는다`() {
        assertFalse(history.add("가".repeat(ClipboardHistory.MAX_LENGTH + 1)))
        assertTrue(history.add("가".repeat(ClipboardHistory.MAX_LENGTH)))
    }

    @Test
    fun `정해진 개수를 넘으면 오래된 것부터 버린다`() {
        repeat(ClipboardHistory.CAPACITY + 5) { history.add("글 $it") }

        val items = history.items()
        assertEquals(ClipboardHistory.CAPACITY, items.size)
        assertEquals("글 ${ClipboardHistory.CAPACITY + 4}", items.first(), "최신이 맨 앞")
        assertFalse(items.contains("글 0"), "가장 오래된 것은 버려야 한다")
    }

    @Test
    fun `하나만 지울 수 있다`() {
        history.add("가")
        history.add("나")
        history.remove("가")

        assertEquals(listOf("나"), history.items())
    }

    @Test
    fun `전부 지울 수 있다`() {
        history.add("가")
        history.add("나")
        history.clear()

        assertEquals(emptyList(), history.items())
    }

    @Test
    fun `쓴 것을 다시 읽으면 그대로다`() {
        for (items in listOf(
            listOf("평범한 글"),
            listOf("줄\n바꿈이 든 글", "탭\t도"),
            listOf("숫자로 시작 12:34", "콜론: 포함"),
            listOf("이모지 😀 포함", ""),
            emptyList()
        )) {
            val roundTrip = ClipboardHistory.decode(ClipboardHistory.encode(items))
            assertEquals(items, roundTrip, "왕복이 깨졌다: $items")
        }
    }

    @Test
    fun `망가진 저장값에도 죽지 않는다`() {
        for (broken in listOf("", "abc", "5:짧", "10:", ":::", "3:가나다4:")) {
            ClipboardHistory.decode(broken)  // 예외가 나지 않으면 된다
        }
        // 앞부분이 멀쩡하면 읽을 수 있는 만큼은 살린다.
        assertEquals(listOf("가나다"), ClipboardHistory.decode("3:가나다99:잘림"))
    }

    @Test
    fun `저장소에 쌓여 있던 것이 넘쳐도 정해진 개수만 내놓는다`() {
        store.items = (1..100).map { "글 $it" }
        assertEquals(ClipboardHistory.CAPACITY, history.items().size)
    }
}
