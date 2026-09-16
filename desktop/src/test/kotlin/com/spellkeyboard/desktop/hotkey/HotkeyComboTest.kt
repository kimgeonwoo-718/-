package com.spellkeyboard.desktop.hotkey

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 글자 ↔ 조합 왕복은 **설정에 적히고 다시 읽히는 바로 그 길**이다. 여기가 한 군데라도
 * 어긋나면 사용자가 고른 단축키가 다음에 켤 때 조용히 다른 키가 된다.
 */
class HotkeyComboTest {

    @Test
    fun `기본값은 Ctrl+Shift+Space 다`() {
        assertEquals("Ctrl+Shift+Space", HotkeyCombo.DEFAULT.format())
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse("Ctrl+Shift+Space"))
        assertEquals(0x20, HotkeyCombo.DEFAULT.keyCode)
        assertTrue(HotkeyCombo.DEFAULT.isUsable)
    }

    @Test
    fun `모든 가상키와 모든 조합키 묶음이 적었다 읽어도 그대로다`() {
        var n = 0
        for (vk in 1..0xFF) {
            for (mods in 0..HotkeyCombo.MODIFIER_MASK) {
                val combo = HotkeyCombo(mods, vk)
                val text = combo.format()
                assertEquals(combo, HotkeyCombo.parse(text), "왕복이 깨졌다: '$text'")
                // 설정 파일을 손으로 고치는 사람이 대소문자를 어떻게 쓰든 같아야 한다.
                assertEquals(combo, HotkeyCombo.parse(text.lowercase()), "소문자에서 깨졌다: '$text'")
                assertEquals(combo, HotkeyCombo.parse(text.uppercase()), "대문자에서 깨졌다: '$text'")
                n++
            }
        }
        assertEquals(255 * 16, n)
    }

    @Test
    fun `빈칸이 섞여도 읽는다`() {
        val want = HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x4B)
        for (text in listOf("Ctrl+Alt+K", " Ctrl + Alt + K ", "ctrl+alt+k", "CONTROL + ALT + K", "Ctl+Menu+K")) {
            assertEquals(want, HotkeyCombo.parse(text), "'$text'")
        }
    }

    @Test
    fun `이름이 여럿인 키도 같은 값으로 읽는다`() {
        assertEquals(0x0D, HotkeyCombo.keyCode("Enter"))
        assertEquals(0x0D, HotkeyCombo.keyCode("Return"))
        assertEquals(0x1B, HotkeyCombo.keyCode("Esc"))
        assertEquals(0x1B, HotkeyCombo.keyCode("Escape"))
        assertEquals(0x22, HotkeyCombo.keyCode("PgDn"))
        assertEquals(0x22, HotkeyCombo.keyCode("PageDown"))
        assertEquals(0x60, HotkeyCombo.keyCode("Numpad0"))
        assertEquals(0x60, HotkeyCombo.keyCode("Num0"))
    }

    @Test
    fun `AWT 키코드가 아니라 Win32 가상키다`() {
        // 여기가 어긋나면 등록은 성공하고 단축키는 평생 안 눌린다.
        assertEquals(0x0D, HotkeyCombo.keyCode("Enter"))        // AWT 는 0x0A
        assertEquals(0x2E, HotkeyCombo.keyCode("Delete"))       // AWT 는 0x7F
        assertEquals(0x2D, HotkeyCombo.keyCode("Insert"))       // AWT 는 0x9B
        assertEquals(0xBA, HotkeyCombo.keyCode(";"))            // AWT 는 0x3B
        assertEquals(0xDB, HotkeyCombo.keyCode("["))            // AWT 는 0x5B — Win32 에선 왼쪽 윈도우키다
        assertEquals(0xBD, HotkeyCombo.keyCode("-"))            // AWT 는 0x2D — Win32 에선 Insert 다
        assertEquals(0x5B, HotkeyCombo.keyCode("LWin"))
    }

    @Test
    fun `키가 더하기표인 조합도 오간다`() {
        val plus = HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0xBB)
        assertEquals(plus, HotkeyCombo.parse("Ctrl++"))
        assertEquals(plus, HotkeyCombo.parse("Ctrl + +"))
        assertEquals(plus, HotkeyCombo.parse("Ctrl+="))   // 미국 자판에서 같은 키다
        assertEquals("Ctrl+=", plus.format())
    }

    @Test
    fun `이름 없는 가상키는 16진수로 오간다`() {
        val odd = HotkeyCombo(HotkeyCombo.MOD_WIN, 0x07)
        assertEquals("Win+0x07", odd.format())
        assertEquals(odd, HotkeyCombo.parse("Win+0x07"))
        assertEquals(odd, HotkeyCombo.parse("win+0X07"))
    }

    @Test
    fun `못 읽는 글은 null 이지 예외가 아니다`() {
        // 설정 파일은 사람이 고칠 수 있다. 읽다 터지면 앱이 아예 안 뜬다.
        // "Ctrl" 만 적힌 설정이 'Ctrl 키 하나' 라는 조합으로 읽히면 안 된다 — 사용자는
        // 뒤를 덜 적은 것이지 그런 단축키를 바란 것이 아니다.
        for (bad in listOf("", "   ", "Ctrl", "Shift", "Alt", "Win", "Ctrl+", "Ctrl+Nope", "Nope+A",
                           "Ctrl+Ctrl+A", "0x100", "0x00", "Ctrl+0xZZ")) {
            assertNull(HotkeyCombo.parse(bad), "'$bad' 를 읽어 버렸다")
        }
        assertNull(HotkeyCombo.parse(null))
        // 글자로는 읽히지만 **써서는 안 되는** 것은 갈래가 다르다. 읽기는 문법이고,
        // 쓸 수 있는지는 [HotkeyCombo.rejectReason] 가 따로 본다.
        val bare = assertNotNull(HotkeyCombo.parse("+"))
        assertTrue(!bare.isUsable, "조합키 없는 키가 쓸 수 있다고 나왔다")
    }

    @Test
    fun `못 읽으면 기본값으로 돌아간다`() {
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parseOrDefault(null))
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parseOrDefault("이런 건 없다"))
        assertEquals(HotkeyCombo(HotkeyCombo.MOD_ALT, 0x51), HotkeyCombo.parseOrDefault("Alt+Q"))
    }

    @Test
    fun `숫자로 적힌 예전 설정도 읽는다`() {
        // 설정 쪽이 "mods:vk" 로 적어 두었더라도 사용자 설정이 날아가면 안 된다.
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse("6:32"))
        assertEquals(HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x4B), HotkeyCombo.parse("3:75"))
        assertNull(HotkeyCombo.parse("99:32"))   // 있을 수 없는 조합키 비트
        assertNull(HotkeyCombo.parse("2:0"))     // 있을 수 없는 키
        assertNull(HotkeyCombo.parse("2:256"))
    }

    @Test
    fun `쓸 수 없는 조합은 까닭을 말해 준다`() {
        // 조합키 없이 Space 만 등록하면 윈도우는 받아 주고, 그 순간 온 컴퓨터에서
        // 스페이스바가 우리 것이 된다. 여기서 막아야 한다.
        assertNotNull(HotkeyCombo(0, 0x20).rejectReason())
        // Shift 만도 마찬가지다 — Shift+A 를 잡으면 대문자 A 를 못 친다.
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_SHIFT, 0x41).rejectReason())
        // 조합키 자체는 키 자리에 못 온다.
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x11).rejectReason())
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x5B).rejectReason())
        // 윈도우가 검사해 주지 않는 값들.
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0).rejectReason())
        assertNotNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x10000).rejectReason())
        assertNotNull(HotkeyCombo(0x0010 or HotkeyCombo.MOD_CONTROL, 0x41).rejectReason())

        assertNull(HotkeyCombo.DEFAULT.rejectReason())
        assertNull(HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x4B).rejectReason())
    }

    @Test
    fun `Preferences 를 거쳐도 그대로 돌아온다`() {
        // 이 왕복이 진짜 쓰임새다. 값이 아니라 **글자**가 저장된다.
        val node = Preferences.userRoot().node("com/spellkeyboard/desktop-test/hotkey-roundtrip")
        try {
            for (combo in listOf(
                HotkeyCombo.DEFAULT,
                HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x4B),
                HotkeyCombo(HotkeyCombo.MOD_WIN or HotkeyCombo.MOD_SHIFT, 0x7B),
                HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_SHIFT, 0xBA),
            )) {
                node.put("hotkey", combo.format())
                node.flush()
                assertEquals(combo, HotkeyCombo.parseOrDefault(node.get("hotkey", null)), combo.format())
            }
            // 값이 없으면 기본값.
            assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parseOrDefault(node.get("없는키", null)))
        } finally {
            runCatching { node.removeNode(); Preferences.userRoot().flush() }
        }
    }
}
