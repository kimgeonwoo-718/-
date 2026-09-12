package com.spellkeyboard.core.editor

import com.spellkeyboard.core.hangul.CheonjiinAutomata
import com.spellkeyboard.core.hangul.Hangul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 실제 타이핑 흐름 검증.
 *
 * 교정 엔진만 맞아서는 소용이 없다. 조합 확정 -> 커서 앞 읽기 -> 지우기 -> 다시 쓰기가
 * 순서대로 맞물려야 화면의 글자가 실제로 바뀐다. 여기서 그 맞물림을 확인한다.
 */
class TypingSessionTest {

    private val editor = FakeEditor()
    private val session = TypingSession()
    private val events = mutableListOf<CorrectionEvent>()

    init {
        session.onEvent = { events += it }
    }

    /** 자모 문자열을 실제 키보드처럼 한 글자씩 눌러 넣는다. */
    private fun type(keys: String) {
        for (c in keys) {
            when {
                c == ' ' -> session.pressSpace(editor)
                Hangul.isJamo(c) -> session.pressJamo(editor, c)
                else -> session.pressText(editor, c)
            }
        }
    }

    // --- 조합 -----------------------------------------------------------------

    @Test
    fun `자모를 눌러 한글이 완성된다`() {
        type("ㅇㅏㄴㄴㅕㅇ")
        assertEquals("안녕", editor.text)
    }

    @Test
    fun `조합이 끝나기 전에는 조합 영역에 머문다`() {
        type("ㅎㅏ")
        assertTrue(editor.isComposing, "조합 중이어야 한다")

        session.pressSpace(editor)
        assertTrue(!editor.isComposing, "스페이스를 누르면 조합이 끝나야 한다")
        assertEquals("하 ", editor.text)
    }

    // --- 교정이 실제로 화면에 반영되는가 ---------------------------------------

    @Test
    fun `스페이스를 누르면 직전 어절이 교정된다`() {
        type("ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ")
        assertEquals("할수있다", editor.text)

        session.pressSpace(editor)
        assertEquals("할 수 있다 ", editor.text)
    }

    @Test
    fun `맞춤법도 스페이스에서 고쳐진다`() {
        type("ㄷㅚㅆㄷㅏ")
        assertEquals("됬다", editor.text)

        session.pressSpace(editor)
        assertEquals("됐다 ", editor.text)
    }

    @Test
    fun `문장부호에서도 교정한다`() {
        type("ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ.")
        assertEquals("할 수 있다.", editor.text)
    }

    @Test
    fun `엔터 직전에도 교정한다`() {
        type("ㅁㅓㄱㅇㅡㄹㄸㅐ")
        assertEquals("먹을때", editor.text)

        session.pressEnter(editor)
        assertEquals("먹을 때", editor.text)
    }

    @Test
    fun `앞 어절은 건드리지 않고 마지막 어절만 고친다`() {
        type("ㄴㅏㄴㅡㄴ ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ")
        session.pressSpace(editor)
        assertEquals("나는 할 수 있다 ", editor.text)
    }

    @Test
    fun `교정할 것이 없으면 글자를 건드리지 않는다`() {
        type("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ")
        session.pressSpace(editor)
        assertEquals("안녕하세요 ", editor.text)
    }

    // --- 되돌리기 --------------------------------------------------------------

    @Test
    fun `교정 직후 백스페이스로 되돌린다`() {
        type("ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ")
        session.pressSpace(editor)
        assertEquals("할 수 있다 ", editor.text)

        assertTrue(session.pressBackspace(editor), "되돌리기가 백스페이스를 소비해야 한다")
        assertEquals("할수있다 ", editor.text)
    }

    @Test
    fun `되돌리기는 한 번만 먹는다`() {
        type("ㄷㅚㅆㄷㅏ")
        session.pressSpace(editor)
        session.pressBackspace(editor)
        assertEquals("됬다 ", editor.text)

        // 두 번째 백스페이스는 평범한 삭제라 호출자에게 넘긴다.
        assertTrue(!session.pressBackspace(editor), "두 번째는 편집기가 지워야 한다")
    }

    @Test
    fun `조합 중일 때 백스페이스는 한 단계씩 푼다`() {
        type("ㅇㅏㄴ")
        assertTrue(session.pressBackspace(editor))
        assertEquals("아", editor.text)

        assertTrue(session.pressBackspace(editor))
        assertEquals("ㅇ", editor.text)
    }

    // --- 교정 끄기 -------------------------------------------------------------

    @Test
    fun `교정이 꺼져 있으면 글자를 그대로 둔다`() {
        session.correctionEnabled = false
        type("ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ")
        session.pressSpace(editor)

        assertEquals("할수있다 ", editor.text)
        assertIs<CorrectionEvent.Disabled>(events.last())
    }

    // --- 진단용 이벤트 ---------------------------------------------------------

    @Test
    fun `무엇을 했는지 이벤트로 알린다`() {
        type("ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ")
        session.pressSpace(editor)

        val applied = assertIs<CorrectionEvent.Applied>(events.last())
        assertEquals("할수있다", applied.from)
        assertEquals("할 수 있다", applied.to)
    }

    @Test
    fun `고칠 것이 없으면 검사한 구간을 알린다`() {
        type("ㅇㅏㄴㄴㅕㅇ")
        session.pressSpace(editor)

        val unchanged = assertIs<CorrectionEvent.Unchanged>(events.last())
        assertEquals("안녕", unchanged.examined)
    }

    // --- 편집기가 되읽기를 지원하지 않을 때 -----------------------------------

    @Test
    fun `편집기가 커서 앞 텍스트를 못 읽어 줘도 교정한다`() {
        // getTextBeforeCursor 가 빈 값을 주는 편집기가 있다. 그대로 두면 읽을 게 없어
        // 아무것도 고치지 않고 조용히 넘어간다 — 겉으로는 "교정이 안 되는" 것으로 보인다.
        val blind = FakeEditor(canReadBack = false)
        val blindSession = TypingSession()

        for (c in "ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ") blindSession.pressJamo(blind, c)
        blindSession.pressSpace(blind)

        assertEquals("할 수 있다 ", blind.text)
    }

    @Test
    fun `되읽기가 안 되는 편집기에서도 앞 어절을 망가뜨리지 않는다`() {
        val blind = FakeEditor(canReadBack = false)
        val blindSession = TypingSession()

        for (c in "ㄴㅏㄴㅡㄴ") blindSession.pressJamo(blind, c)
        blindSession.pressSpace(blind)
        for (c in "ㄷㅚㅆㄷㅏ") blindSession.pressJamo(blind, c)
        blindSession.pressSpace(blind)

        assertEquals("나는 됐다 ", blind.text)
    }

    // --- 길게 눌러 쌍자음 ------------------------------------------------------

    @Test
    fun `방금 넣은 자모를 쌍자음으로 바꾼다`() {
        session.pressJamo(editor, 'ㄱ')
        session.replaceLastJamo(editor, 'ㄲ')
        assertEquals("ㄲ", editor.text)
    }

    @Test
    fun `받침으로 들어간 자모도 바꾼다`() {
        type("ㄱㅏㄱ")
        assertEquals("각", editor.text)

        session.replaceLastJamo(editor, 'ㄲ')
        assertEquals("갂", editor.text)
    }

    @Test
    fun `앞에 확정된 글자는 건드리지 않는다`() {
        type("ㄱㅏㄴㄷ")
        assertEquals("간ㄷ", editor.text)

        session.replaceLastJamo(editor, 'ㄸ')
        assertEquals("간ㄸ", editor.text)
    }

    // --- 편집 묶음 -------------------------------------------------------------

    @Test
    fun `편집을 배치로 묶고 반드시 닫는다`() {
        type("ㅎㅏㄹㅅㅜㅇㅣㅆㄷㅏ")
        session.pressSpace(editor)

        assertEquals(0, editor.batchDepth, "batch 가 닫히지 않았다")
        assertTrue(editor.maxBatchDepth > 0, "batch 로 묶지 않았다")
    }

    // --- 천지인 ---------------------------------------------------------------

    @Test
    fun `천지인 자판으로 바꾸면 세 획으로 글자가 된다`() {
        session.automata = CheonjiinAutomata()
        for (c in "ㅇㅣㆍㄴㄴㆍㆍㅣㅇ") session.pressJamo(editor, c)
        assertEquals("안녕", editor.text)

        session.pressSpace(editor)
        assertEquals("안녕 ", editor.text)
    }

    @Test
    fun `천지인 연타는 repeat 로 전달된다`() {
        session.automata = CheonjiinAutomata()
        session.pressJamo(editor, 'ㄱ')
        session.pressJamo(editor, 'ㄱ', repeat = true)
        session.pressJamo(editor, 'ㅣ')
        session.pressJamo(editor, 'ㆍ')
        assertEquals("카", editor.text)
    }

    @Test
    fun `천지인에서도 교정은 스페이스에서 돈다`() {
        session.automata = CheonjiinAutomata()
        // 됬다 → 됐다. ㄷ ㅡㆍㆍㅣ(ㅝ)? 아니다 — '됬' 은 ㄷ+ㅚ+ㅆ: ㄷ ㆍㅡㅣ ㅅRㅅRㅅ
        session.pressJamo(editor, 'ㄷ')
        for (c in "ㆍㅡㅣ") session.pressJamo(editor, c)
        session.pressJamo(editor, 'ㅅ')
        session.pressJamo(editor, 'ㅅ', repeat = true)
        session.pressJamo(editor, 'ㅅ', repeat = true)
        session.pressJamo(editor, 'ㄷ')
        for (c in "ㅣㆍ") session.pressJamo(editor, c)
        assertEquals("됬다", editor.text)
        session.pressSpace(editor)
        assertEquals("됐다 ", editor.text)
    }
}
