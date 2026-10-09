package com.spellkeyboard.core.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslateBufferTest {

    @Test
    fun `한글 조합과 교정이 입력줄 위에서 그대로 돈다`() {
        val buffer = TranslateBuffer()
        val session = TypingSession()
        "안녕하새요".forEach { syllable ->
            // 두벌식 자모로 풀어서 누른다.
            jamoOf(syllable).forEach { session.pressJamo(buffer, it) }
        }
        assertEquals("안녕하새요", buffer.text)
        session.pressSpace(buffer)
        // 규칙 사전만으로도 '됬' 류는 잡지만 '하새요'는 언어모델 몫이라 여기서는 공백만 본다.
        assertEquals("안녕하새요 ", buffer.text)
        session.pressBackspace(buffer).let { handled -> if (!handled) buffer.deleteBefore(1) }
        assertEquals("안녕하새요", buffer.text)
    }

    @Test
    fun `바뀔 때마다 알리되 배치 안에서는 한 번만`() {
        val buffer = TranslateBuffer()
        var changes = 0
        buffer.onChange = { changes++ }
        buffer.commitText("가")
        assertEquals(1, changes)
        buffer.beginBatch()
        buffer.deleteBefore(1)
        buffer.commitText("나")
        buffer.commitText("다")
        assertEquals(1, changes)
        buffer.endBatch()
        assertEquals(2, changes)
        assertEquals("나다", buffer.text)
    }

    @Test
    fun `조합이 밖에서 끊겨도 앞 글자를 먹지 않는다`() {
        // 실기기 버그: 번역문을 앱에 써 넣으면 커서 알림이 오고, 그걸 "사용자가 커서를
        // 옮겼다" 로 읽어 조합을 끊었다. 그 뒤 자모를 누르면 옛 조합 자리가 앞 글자를 덮었다 —
        // '어' 를 쓰고 'ㄸ' 를 누르면 '어' 가 사라졌다.
        val buffer = TranslateBuffer()
        val session = TypingSession()
        session.pressJamo(buffer, 'ㅇ')
        session.pressJamo(buffer, 'ㅓ')
        assertEquals("어", buffer.text)

        buffer.finishComposing()
        session.reset()
        session.pressJamo(buffer, 'ㄸ')
        assertEquals("어ㄸ", buffer.text)
    }

    @Test
    fun `입력줄을 비울 때는 조립기도 함께 비운다`() {
        // 둘 중 하나만 비우면 어긋난다 — 조립기에 '어' 가 남은 채로 'ㄱ' 을 누르면 '억' 이 된다.
        // 서비스의 enterTranslate/exitTranslate 는 늘 둘을 함께 비운다.
        val buffer = TranslateBuffer()
        val session = TypingSession()
        session.pressJamo(buffer, 'ㅇ')
        session.pressJamo(buffer, 'ㅓ')
        buffer.clear()
        session.reset()
        session.pressJamo(buffer, 'ㄱ')
        assertEquals("ㄱ", buffer.text)
    }

    @Test
    fun `번역문을 갈아 끼운다`() {
        val editor = FakeEditor()
        editor.commitText("Hi ")
        val output = TranslationOutput()
        output.replace(editor, "Hello")
        assertEquals("Hi Hello", editor.text)
        output.replace(editor, "Hello there")
        assertEquals("Hi Hello there", editor.text)
        output.replace(editor, "")
        assertEquals("Hi ", editor.text)
        output.replace(editor, "Bye")
        output.detach()
        output.replace(editor, "Again")
        // 잊은 뒤에는 앞의 것을 지우지 않는다.
        assertEquals("Hi ByeAgain", editor.text)
        assertEquals(0, editor.batchDepth)
    }

    private fun jamoOf(syllable: Char): List<Char> {
        val (cho, jung, jong) = com.spellkeyboard.core.hangul.Hangul.decompose(syllable)!!
        val out = mutableListOf(
            com.spellkeyboard.core.hangul.Hangul.CHOSEONG[cho],
            com.spellkeyboard.core.hangul.Hangul.JUNGSEONG[jung]
        )
        if (jong != 0) out += com.spellkeyboard.core.hangul.Hangul.JONGSEONG[jong]
        return out
    }

    @Test
    fun `커서를 옮기면 글자가 커서 자리에 들어간다`() {
        val buffer = TranslateBuffer()
        buffer.commitText("안녕세요")
        assertEquals(4, buffer.cursorIndex)
        buffer.moveCursor(-2)
        assertEquals(2, buffer.cursorIndex)
        buffer.commitText("하")
        assertEquals("안녕하세요", buffer.text)
        assertEquals(3, buffer.cursorIndex)
        assertEquals("안녕하", buffer.textBeforeCursor(10))
        assertEquals("세요", buffer.textAfterCursor(10))
    }

    @Test
    fun `커서 앞을 지우면 커서 뒤는 그대로다`() {
        val buffer = TranslateBuffer()
        buffer.commitText("안녕하세요")
        buffer.setCursor(3)
        buffer.deleteBefore(1)
        assertEquals("안녕세요", buffer.text)
        assertEquals(2, buffer.cursorIndex)
    }

    @Test
    fun `한글 조합이 커서 자리에서 돈다`() {
        val buffer = TranslateBuffer()
        val session = TypingSession()
        // '안녕' 을 쓰고 '하' 를 앞에 끼운다: 커서를 맨 앞으로 옮기고 ㅎ ㅏ
        "안녕".forEach { syllable -> jamoOf(syllable).forEach { session.pressJamo(buffer, it) } }
        session.commitPending(buffer)
        buffer.setCursor(0)
        session.reset()
        jamoOf('하').forEach { session.pressJamo(buffer, it) }
        assertEquals("하안녕", buffer.text)
        assertEquals(1, buffer.cursorIndex)
    }

    @Test
    fun `커서만 옮기면 내용이 바뀌었다고 알리지 않는다`() {
        val buffer = TranslateBuffer()
        buffer.commitText("가나다")
        var changes = 0
        var carets = 0
        buffer.onChange = { changes++ }
        buffer.onCaret = { carets++ }
        buffer.moveCursor(-1)
        assertEquals(0, changes)
        assertEquals(1, carets)
        // 같은 자리로는 알리지 않는다
        buffer.setCursor(2)
        assertEquals(1, carets)
        // 범위를 벗어나면 끝으로 맞춘다
        buffer.moveCursor(100)
        assertEquals(3, buffer.cursorIndex)
        buffer.moveCursor(-100)
        assertEquals(0, buffer.cursorIndex)
    }

    @Test
    fun `커서를 옮기면 조합이 확정된다`() {
        val buffer = TranslateBuffer()
        buffer.setComposingText("ㅎ")
        buffer.moveCursor(-1)
        // 조합이 풀렸으니 새 조합은 커서 자리에서 새로 시작한다
        buffer.setComposingText("가")
        assertEquals("가ㅎ", buffer.text)
    }
}
