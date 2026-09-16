package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.Win32Keys

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotkeyComboTest {

    // ---- 저장형 왕복 ----

    @Test
    fun `기본값이 Ctrl+Shift+Space 다`() {
        assertEquals("CTRL+SHIFT+SPACE", HotkeyCombo.DEFAULT.store())
        assertEquals("Ctrl+Shift+Space", HotkeyCombo.DEFAULT.display())
        assertEquals(0x20, HotkeyCombo.DEFAULT.vk)
    }

    @Test
    fun `적어 두었다 다시 읽으면 같은 조합이다`() {
        val combos = listOf(
            HotkeyCombo.DEFAULT,
            HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x4B),  // Ctrl+Alt+K
            HotkeyCombo(HotkeyCombo.MOD_WIN, 0x71),                              // Win+F2
            HotkeyCombo(HotkeyCombo.MOD_ALT or HotkeyCombo.MOD_SHIFT, 0xBE),     // Alt+Shift+.
            HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0xDB),                          // Ctrl+[
        )
        for (c in combos) assertEquals(c, HotkeyCombo.parse(c.store()), "왕복 실패: ${c.display()}")
    }

    @Test
    fun `대소문자와 공백을 가리지 않는다`() {
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse("ctrl + shift + space"))
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse("Control+Shift+Space"))
    }

    @Test
    fun `깨진 값은 null 이고 기본값으로 떨어질 수 있다`() {
        assertNull(HotkeyCombo.parse(null))
        assertNull(HotkeyCombo.parse(""))
        assertNull(HotkeyCombo.parse("CTRL+"))          // 본 키가 없다
        assertNull(HotkeyCombo.parse("CTRL+A+B"))       // 본 키가 둘
        assertNull(HotkeyCombo.parse("CTRL+않는키"))
    }

    // ---- AWT → Win32. 이 표가 이 부품에서 가장 조용히 틀릴 수 있는 곳이다. ----

    @Test
    fun `글자와 숫자와 F키는 두 쪽 코드가 같다`() {
        assertEquals(0x41, Win32Keys.fromAwt(KeyEvent.VK_A))
        assertEquals(0x5A, Win32Keys.fromAwt(KeyEvent.VK_Z))
        assertEquals(0x30, Win32Keys.fromAwt(KeyEvent.VK_0))
        assertEquals(0x39, Win32Keys.fromAwt(KeyEvent.VK_9))
        assertEquals(0x70, Win32Keys.fromAwt(KeyEvent.VK_F1))
        assertEquals(0x7B, Win32Keys.fromAwt(KeyEvent.VK_F12))
        assertEquals(0x20, Win32Keys.fromAwt(KeyEvent.VK_SPACE))
    }

    /**
     * 여기가 핵심이다. AWT 코드를 그냥 넘기면 **다른 키가 등록되고 오류도 안 난다.**
     * 아래 기대값은 전부 Win32 의 진짜 코드다.
     */
    @Test
    fun `어긋나는 키들이 Win32 코드로 옮겨진다`() {
        // 그냥 넘기면 각각 VK_LWIN, VK_RWIN, VK_APPS, VK_DELETE, VK_INSERT 가 잡힌다.
        assertEquals(0xDB, Win32Keys.fromAwt(KeyEvent.VK_OPEN_BRACKET))   // AWT 0x5B == VK_LWIN
        assertEquals(0xDC, Win32Keys.fromAwt(KeyEvent.VK_BACK_SLASH))     // AWT 0x5C == VK_RWIN
        assertEquals(0xDD, Win32Keys.fromAwt(KeyEvent.VK_CLOSE_BRACKET))  // AWT 0x5D == VK_APPS
        assertEquals(0xBE, Win32Keys.fromAwt(KeyEvent.VK_PERIOD))         // AWT 0x2E == VK_DELETE
        assertEquals(0xBD, Win32Keys.fromAwt(KeyEvent.VK_MINUS))          // AWT 0x2D == VK_INSERT
        assertEquals(0xBB, Win32Keys.fromAwt(KeyEvent.VK_EQUALS))
        assertEquals(0xBA, Win32Keys.fromAwt(KeyEvent.VK_SEMICOLON))
        assertEquals(0xBC, Win32Keys.fromAwt(KeyEvent.VK_COMMA))
        assertEquals(0xBF, Win32Keys.fromAwt(KeyEvent.VK_SLASH))
        // Enter 와 Delete 는 값 자체가 다르다.
        assertEquals(0x0D, Win32Keys.fromAwt(KeyEvent.VK_ENTER))          // AWT 는 0x0A
        assertEquals(0x2E, Win32Keys.fromAwt(KeyEvent.VK_DELETE))         // AWT 는 0x7F
        assertEquals(0x2D, Win32Keys.fromAwt(KeyEvent.VK_INSERT))         // AWT 는 0x9B
    }

    @Test
    fun `Win32 코드가 서로 겹치지 않는다`() {
        // 겹치면 화면에 엉뚱한 이름이 뜬다. 표를 늘릴 때 이 시험이 먼저 깨져야 한다.
        val all = (0..0xFF).mapNotNull { Win32Keys.storeName(it) }
        assertEquals(all.size, all.toSet().size, "저장 이름이 겹친다")
    }

    @Test
    fun `모르는 키는 null 이다`() {
        assertNull(Win32Keys.fromAwt(KeyEvent.VK_CONTROL))
        assertNull(Win32Keys.fromAwt(KeyEvent.VK_SHIFT))
        assertNull(Win32Keys.fromAwt(KeyEvent.VK_F13))
        assertNull(Win32Keys.fromAwt(KeyEvent.VK_UNDEFINED))
    }

    // ---- 조합키 비트 ----

    @Test
    fun `AWT 조합키 비트가 Win32 MOD 비트로 바뀐다`() {
        assertEquals(HotkeyCombo.MOD_CONTROL, HotkeyCombo.modsFromAwt(InputEvent.CTRL_DOWN_MASK))
        assertEquals(HotkeyCombo.MOD_SHIFT, HotkeyCombo.modsFromAwt(InputEvent.SHIFT_DOWN_MASK))
        assertEquals(HotkeyCombo.MOD_ALT, HotkeyCombo.modsFromAwt(InputEvent.ALT_DOWN_MASK))
        assertEquals(HotkeyCombo.MOD_WIN, HotkeyCombo.modsFromAwt(InputEvent.META_DOWN_MASK))
        assertEquals(
            HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_SHIFT,
            HotkeyCombo.modsFromAwt(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
        assertEquals(0, HotkeyCombo.modsFromAwt(0))
    }

    @Test
    fun `누른 키가 그대로 조합이 된다`() {
        val combo = HotkeyCombo.fromKeyEvent(
            KeyEvent.VK_SPACE,
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
        )
        assertEquals(HotkeyCombo.DEFAULT, combo)
    }

    // ---- 막아야 할 조합 ----

    @Test
    fun `조합키가 없으면 막는다`() {
        // 이것을 통과시키면 A 한 글자가 온 컴퓨터에서 안 쳐진다.
        val bare = HotkeyCombo(0, 0x41)
        assertNotNull(bare.rejectReason())
    }

    @Test
    fun `Shift 만으로는 안 된다`() {
        val shiftOnly = HotkeyCombo(HotkeyCombo.MOD_SHIFT, 0x41)
        val why = shiftOnly.rejectReason()
        assertNotNull(why)
        assertTrue("Ctrl" in why)
    }

    @Test
    fun `Ctrl 이나 Alt 나 Win 이 하나만 있으면 통과한다`() {
        assertNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x41).rejectReason())
        assertNull(HotkeyCombo(HotkeyCombo.MOD_ALT, 0x41).rejectReason())
        assertNull(HotkeyCombo(HotkeyCombo.MOD_WIN, 0x41).rejectReason())
        assertNull(HotkeyCombo.DEFAULT.rejectReason())
    }

    @Test
    fun `표에 없는 가상 키는 막는다`() {
        // Windows 는 vk=0x00 도 vk=0xFF 도 TRUE 로 등록해 준다. 우리가 막아야 한다.
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x00).rejectReason())
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0xFF).rejectReason())
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x11).rejectReason()) // VK_CONTROL
    }
}
