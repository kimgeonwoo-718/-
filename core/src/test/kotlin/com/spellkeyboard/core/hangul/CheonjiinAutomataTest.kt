package com.spellkeyboard.core.hangul

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheonjiinAutomataTest {

    /**
     * 키를 차례로 눌렀을 때 화면에 보이는 전체 텍스트(확정 + 조합 중).
     * 대문자 'R' 뒤의 키는 "같은 키 연타" 로 넣는다. 예: "ㄱR" 은 ㄱ 을 두 번 빠르게.
     */
    private fun type(keys: String): String {
        val automata = CheonjiinAutomata()
        val committed = StringBuilder()
        var repeatNext = false
        for (c in keys) {
            if (c == 'R') {
                repeatNext = true
                continue
            }
            committed.append(automata.press(c, repeatNext).committed)
            repeatNext = false
        }
        return committed.toString() + automata.composing()
    }

    private fun typing(keys: String): CheonjiinAutomata {
        val automata = CheonjiinAutomata()
        for (c in keys) automata.press(c)
        return automata
    }

    @Test
    fun `세 획으로 기본 모음을 만든다`() {
        assertEquals("가", type("ㄱㅣㆍ"))
        assertEquals("거", type("ㄱㆍㅣ"))
        assertEquals("고", type("ㄱㆍㅡ"))
        assertEquals("구", type("ㄱㅡㆍ"))
        assertEquals("기", type("ㄱㅣ"))
        assertEquals("그", type("ㄱㅡ"))
    }

    @Test
    fun `점을 겹쳐 이중 모음을 만든다`() {
        assertEquals("갸", type("ㄱㅣㆍㆍ"))
        assertEquals("겨", type("ㄱㆍㆍㅣ"))
        assertEquals("교", type("ㄱㆍㆍㅡ"))
        assertEquals("규", type("ㄱㅡㆍㆍ"))
    }

    @Test
    fun `ㅣ를 붙여 복합 모음을 만든다`() {
        assertEquals("개", type("ㄱㅣㆍㅣ"))
        assertEquals("걔", type("ㄱㅣㆍㆍㅣ"))
        assertEquals("게", type("ㄱㆍㅣㅣ"))
        assertEquals("계", type("ㄱㆍㆍㅣㅣ"))
        assertEquals("괴", type("ㄱㆍㅡㅣ"))
        assertEquals("과", type("ㄱㆍㅡㅣㆍ"))
        assertEquals("괘", type("ㄱㆍㅡㅣㆍㅣ"))
        assertEquals("귀", type("ㄱㅡㆍㅣ"))
        assertEquals("궈", type("ㄱㅡㆍㆍㅣ"))
        assertEquals("궤", type("ㄱㅡㆍㆍㅣㅣ"))
        assertEquals("긔", type("ㄱㅡㅣ"))
    }

    @Test
    fun `점만 눌린 동안은 미완성으로 보인다`() {
        assertEquals("ㄱㆍ", type("ㄱㆍ"))
        assertEquals("ㄱㆍㆍ", type("ㄱㆍㆍ"))
        assertEquals("ㆍ", type("ㆍ"))
    }

    @Test
    fun `같은 자음 키를 연타하면 다음 글자로 돈다`() {
        assertEquals("ㅋ", type("ㄱRㄱ"))
        assertEquals("ㄲ", type("ㄱRㄱRㄱ"))
        assertEquals("ㄱ", type("ㄱRㄱRㄱRㄱ"))
        assertEquals("ㄹ", type("ㄴRㄴ"))
        assertEquals("ㅎ", type("ㅅRㅅ"))
        assertEquals("ㅁ", type("ㅇRㅇ"))
        assertEquals("카", type("ㄱRㄱㅣㆍ"))
        assertEquals("하", type("ㅅRㅅㅣㆍ"))
    }

    @Test
    fun `연타가 아니면 새 음절이 시작된다`() {
        // 잠깐 쉬고 누른 ㄱ 은 받침이 되고, 또 쉬고 누른 ㄱ 은 다음 음절의 초성이다.
        assertEquals("각ㄱ", type("ㄱㅣㆍㄱㄱ"))
        assertEquals("각가", type("ㄱㅣㆍㄱㄱㅣㆍ"))
    }

    @Test
    fun `받침도 연타로 돈다`() {
        assertEquals("갘", type("ㄱㅣㆍㄱRㄱ"))
        assertEquals("갂", type("ㄱㅣㆍㄱRㄱRㄱ"))
        // ㄸ 은 받침이 될 수 없어 건너뛴다: ㄷ → ㅌ → ㄷ
        assertEquals("갇", type("ㄱㅣㆍㄷRㄷRㄷ"))
    }

    @Test
    fun `받침 뒤에 모음이 오면 받침이 넘어간다`() {
        assertEquals("가기", type("ㄱㅣㆍㄱㅣ"))
        assertEquals("안녕", type("ㅇㅣㆍㄴㄴㆍㆍㅣㅇ"))
        assertEquals("한글", type("ㅅRㅅㅣㆍㄴㄱㅡㄴRㄴ"))
    }

    @Test
    fun `겹받침을 만들고 뒤쪽만 넘긴다`() {
        assertEquals("닭", type("ㄷㅣㆍㄴRㄴㄱ"))
        assertEquals("달기", type("ㄷㅣㆍㄴRㄴㄱㅣ"))
        assertEquals("없", type("ㅇㆍㅣㅂㅅ"))
    }

    @Test
    fun `이어질 수 없는 획은 새 음절을 연다`() {
        // 가 + ㅡ: ㅏ 뒤에 ㅡ 는 없으니 '가' 확정, 'ㅡ' 새로.
        assertEquals("가ㅡ", type("ㄱㅣㆍㅡ"))
        assertEquals("가으", type("ㄱㅣㆍㅇㅡ"))
    }

    @Test
    fun `자음이 오면 미완성 점은 버린다`() {
        assertEquals("ㄱㄴ", type("ㄱㆍㄴ"))
    }

    @Test
    fun `백스페이스는 획 하나씩 되돌린다`() {
        val automata = typing("ㄱㅣㆍㆍ")
        assertEquals("갸", automata.composing())
        assertEquals("가", automata.backspace()?.composing)
        assertEquals("기", automata.backspace()?.composing)
        assertEquals("ㄱ", automata.backspace()?.composing)
        assertEquals("", automata.backspace()?.composing)
        assertNull(automata.backspace())
    }

    @Test
    fun `백스페이스는 받침부터 지운다`() {
        val automata = typing("ㄱㅣㆍㄱ")
        assertEquals("각", automata.composing())
        assertEquals("가", automata.backspace()?.composing)
        assertEquals("기", automata.backspace()?.composing)
    }

    @Test
    fun `자모가 아닌 문자는 조합을 끊고 확정된다`() {
        assertEquals("가.", type("ㄱㅣㆍ."))
        assertEquals("ㄱ ", type("ㄱㆍ "))
    }

    @Test
    fun `flush 는 미완성 점을 버린다`() {
        val automata = typing("ㄱㆍ")
        assertEquals("ㄱ", automata.flush())
        assertEquals("", automata.composing())
    }
}
