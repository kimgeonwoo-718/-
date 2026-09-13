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
}
