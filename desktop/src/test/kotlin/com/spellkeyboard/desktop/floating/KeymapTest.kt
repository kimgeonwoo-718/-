package com.spellkeyboard.desktop.floating

import java.awt.event.InputEvent.ALT_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_ENTER
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_TAB
import kotlin.test.Test
import kotlin.test.assertEquals

/** 설계서 3-1 의 자판 배치 그대로. 화면 없이 돈다. */
class KeymapTest {

    private fun key(code: Int, mods: Int = 0, composing: Boolean = false) =
        FloatKeymap.intentFor(code, mods, composing)

    @Test
    fun `설계서에 적힌 넉 자`() {
        assertEquals(FloatIntent.APPLY_AND_HIDE, key(VK_ENTER))
        assertEquals(FloatIntent.NEWLINE, key(VK_ENTER, SHIFT_DOWN_MASK))
        assertEquals(FloatIntent.CANCEL, key(VK_ESCAPE))
        assertEquals(FloatIntent.CORRECT_ALL, key(VK_SPACE, CTRL_DOWN_MASK))
    }

    @Test
    fun `맨 스페이스는 그냥 띄어쓰기다`() {
        assertEquals(FloatIntent.PASS, key(VK_SPACE))
        assertEquals(FloatIntent.PASS, key(VK_SPACE, SHIFT_DOWN_MASK))
    }

    @Test
    fun `창을 부르는 Ctrl Shift Space 를 전체교정으로 먹지 않는다`() {
        assertEquals(FloatIntent.PASS, key(VK_SPACE, CTRL_DOWN_MASK or SHIFT_DOWN_MASK))
    }

    @Test
    fun `정해 두지 않은 Enter 조합은 손대지 않는다`() {
        assertEquals(FloatIntent.PASS, key(VK_ENTER, CTRL_DOWN_MASK))
        assertEquals(FloatIntent.PASS, key(VK_ENTER, ALT_DOWN_MASK))
        assertEquals(FloatIntent.PASS, key(VK_ENTER, CTRL_DOWN_MASK or SHIFT_DOWN_MASK))
    }

    @Test
    fun `다른 키는 전부 흘린다`() {
        assertEquals(FloatIntent.PASS, key(VK_A))
        assertEquals(FloatIntent.PASS, key(VK_TAB))
    }

    // ---- 한글 조합 중 ----

    @Test
    fun `조합 중 Enter 는 조합을 끝내는 키다`() {
        // "한글" 을 치다 Enter 를 누르면 그것은 보내라는 뜻이 아니라 마지막 글자를
        // 확정하라는 뜻이다. 여기서 숨겨 버리면 그 글자가 통째로 사라진다.
        assertEquals(FloatIntent.PASS, key(VK_ENTER, composing = true))
        assertEquals(FloatIntent.PASS, key(VK_ENTER, SHIFT_DOWN_MASK, composing = true))
    }

    @Test
    fun `조합 중에는 전체교정도 안 돈다`() {
        // 조합 중에 글을 갈아 끼우면 IME 가 쓰던 자리가 어긋나 유령 글자가 남는다.
        assertEquals(FloatIntent.PASS, key(VK_SPACE, CTRL_DOWN_MASK, composing = true))
    }

    @Test
    fun `조합 중이어도 Esc 는 닫는다`() {
        assertEquals(FloatIntent.CANCEL, key(VK_ESCAPE, composing = true))
    }

    @Test
    fun `Esc 는 조합키가 붙어도 닫는다`() {
        assertEquals(FloatIntent.CANCEL, key(VK_ESCAPE, SHIFT_DOWN_MASK))
        assertEquals(FloatIntent.CANCEL, key(VK_ESCAPE, CTRL_DOWN_MASK))
    }
}
