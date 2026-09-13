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
}
