package com.spellkeyboard.core.hangul

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HangulAutomataTest {

    /** 자모를 차례로 눌렀을 때 화면에 보이는 전체 텍스트(확정 + 조합 중). */
    private fun type(jamo: String): String {
        val automata = HangulAutomata()
        val committed = StringBuilder()
        for (c in jamo) {
            committed.append(automata.press(c).committed)
        }
        return committed.toString() + automata.composing()
    }

    private fun typing(jamo: String): HangulAutomata {
        val automata = HangulAutomata()
        for (c in jamo) automata.press(c)
        return automata
    }

    @Test
    fun `기본 음절을 조합한다`() {
        assertEquals("안녕", type("ㅇㅏㄴㄴㅕㅇ"))
        assertEquals("한글", type("ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("카톡", type("ㅋㅏㅌㅗㄱ"))
    }

    @Test
    fun `복합 모음을 조합한다`() {
        assertEquals("과", type("ㄱㅗㅏ"))
        assertEquals("의", type("ㅇㅡㅣ"))
        assertEquals("왜", type("ㅇㅗㅐ"))
        assertEquals("웠", type("ㅇㅜㅓㅆ"))
    }

    @Test
    fun `겹받침을 조합한다`() {
        assertEquals("앉", type("ㅇㅏㄴㅈ"))
        assertEquals("닭", type("ㄷㅏㄹㄱ"))
        assertEquals("없", type("ㅇㅓㅂㅅ"))
    }

    @Test
    fun `받침 뒤에 모음이 오면 받침이 다음 음절로 넘어간다`() {
        assertEquals("가가", type("ㄱㅏㄱㅏ"))
        assertEquals("먹어", type("ㅁㅓㄱㅇㅓ"))
    }

    @Test
    fun `겹받침 뒤에 모음이 오면 뒤쪽 받침만 넘어간다`() {
        assertEquals("안자", type("ㅇㅏㄴㅈㅏ"))
        assertEquals("달가", type("ㄷㅏㄹㄱㅏ"))
        assertEquals("업서", type("ㅇㅓㅂㅅㅓ"))
    }

    @Test
    fun `연속된 자음은 쌍자음으로 합쳐지지 않는다`() {
        // 두벌식에서 쌍자음은 시프트로만 입력한다. ㄱㄱ 는 ㄲ 가 아니다.
        assertEquals("ㄱㄱ", type("ㄱㄱ"))
        assertEquals("각ㄴ", type("ㄱㅏㄱㄴ"))
    }

    @Test
    fun `받침이 될 수 없는 자음은 새 음절을 시작한다`() {
        assertEquals("가ㄸ", type("ㄱㅏㄸ"))
        assertEquals("따", type("ㄸㅏ"))
    }

    @Test
    fun `홀로 입력된 모음도 그대로 보인다`() {
        assertEquals("ㅏ", type("ㅏ"))
        assertEquals("ㅘ", type("ㅗㅏ"))
        assertEquals("ㅏㅓ", type("ㅏㅓ"))
    }

    @Test
    fun `백스페이스는 조합을 한 단계씩 되돌린다`() {
        val automata = typing("ㅇㅏㄴ")
        assertEquals("안", automata.composing())

        automata.backspace()
        assertEquals("아", automata.composing())

        automata.backspace()
        assertEquals("ㅇ", automata.composing())

        automata.backspace()
        assertEquals("", automata.composing())
    }

    @Test
    fun `백스페이스는 겹받침과 복합 모음도 한 단계씩 푼다`() {
        val jamo = typing("ㅇㅏㄴㅈ")
        jamo.backspace()
        assertEquals("안", jamo.composing())

        val vowel = typing("ㄱㅗㅏ")
        vowel.backspace()
        assertEquals("고", vowel.composing())
    }

    @Test
    fun `조합 중인 글자가 없으면 백스페이스를 편집기로 넘긴다`() {
        assertNull(HangulAutomata().backspace())

        val automata = typing("ㄱㅏ")
        automata.flush()
        assertNull(automata.backspace())
    }

    @Test
    fun `확정된 글자와 조합 중인 글자를 구분해서 내보낸다`() {
        val automata = HangulAutomata()
        automata.press('ㄱ')
        automata.press('ㅏ')

        // 받침이 붙는 동안에는 아직 확정되지 않는다.
        val withJong = automata.press('ㄱ')
        assertEquals("", withJong.committed)
        assertEquals("각", withJong.composing)

        // 모음이 들어오는 순간 앞 음절이 확정된다.
        val moved = automata.press('ㅏ')
        assertEquals("가", moved.committed)
        assertEquals("가", moved.composing)
    }

    @Test
    fun `flush 는 조합을 끝내고 상태를 비운다`() {
        val automata = typing("ㅎㅏㄴ")
        assertEquals("한", automata.flush())
        assertEquals("", automata.composing())
        assertEquals("", automata.flush())
    }

    @Test
    fun `자모가 아닌 문자는 조합을 끊는다`() {
        val automata = typing("ㅎㅏㄴ")
        val output = automata.press('!')
        assertEquals("한!", output.committed)
        assertEquals("", output.composing)
    }
}
