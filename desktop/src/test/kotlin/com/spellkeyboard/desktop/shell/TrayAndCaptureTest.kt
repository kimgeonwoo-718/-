package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.Win32Keys

import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrayAndCaptureTest {

    private val node: Preferences =
        Preferences.userRoot().node("/com/spellkeyboard/desktop-test/tray-${System.nanoTime()}")

    @AfterTest
    fun cleanUp() {
        runCatching { node.removeNode() }
    }

    // ---- 아이콘 그리기 ----

    @Test
    fun `아이콘은 화면 없이도 그려진다`() {
        // 시험은 화면 없이 돌아야 한다. 화면이 붙은 채로 돌리면 아래
        // `알림 영역이 없으면…` 시험이 개발자의 진짜 알림 영역을 건드릴 위험이 생긴다.
        // 여기서 터지면 desktop/build.gradle.kts 의 tasks.test 에 이 줄을 넣어라:
        //     systemProperty("java.awt.headless", "true")
        assertTrue(
            GraphicsEnvironment.isHeadless(),
            "시험은 headless 로 돌아야 한다 — tasks.test 에 " +
                "systemProperty(\"java.awt.headless\", \"true\") 를 넣어라",
        )
        val img = TrayIconArt.render(16, live = true)
        assertEquals(16, img.width)
        assertEquals(16, img.height)
    }

    @Test
    fun `아이콘이 빈 그림이 아니다`() {
        for (size in listOf(16, 20, 24, 32, 64)) {
            val img = TrayIconArt.render(size, live = true)
            var opaque = 0
            var white = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val argb = img.getRGB(x, y)
                if (argb ushr 24 != 0) opaque++
                // 흰 글자가 실제로 찍혔는가 — `가` 가 안 그려지면 여기가 0 이 된다.
                if ((argb and 0xFFFFFF) > 0xE0E0E0) white++
            }
            assertTrue(opaque > size * size / 3, "${size}px: 바탕이 거의 안 칠해졌다")
            assertTrue(white > 0, "${size}px: 글자가 안 찍혔다")
        }
    }

    @Test
    fun `실시간 교정을 끄면 아이콘 색이 달라진다`() {
        // 껐다는 것을 한눈에 알아야 한다. 같은 그림이면 이 시험이 잡는다.
        val on = TrayIconArt.render(24, live = true)
        val off = TrayIconArt.render(24, live = false)
        assertNotEquals(on.getRGB(12, 4), off.getRGB(12, 4))
    }

    @Test
    fun `터무니없는 크기를 줘도 그린다`() {
        assertEquals(8, TrayIconArt.render(0, live = true).width)
        assertEquals(256, TrayIconArt.render(9999, live = true).width)
    }

    // ---- 누름 판단 ----

    @Test
    fun `왼쪽 단추만 창을 연다`() {
        assertEquals(TrayClick.OPEN, trayClickAction(MouseEvent.BUTTON1))
        // 오른쪽은 Windows 가 차림표를 띄운다. 우리가 창까지 띄우면 겹친다.
        assertEquals(TrayClick.IGNORE, trayClickAction(MouseEvent.BUTTON3))
        assertEquals(TrayClick.IGNORE, trayClickAction(MouseEvent.BUTTON2))
        assertEquals(TrayClick.IGNORE, trayClickAction(MouseEvent.NOBUTTON))
    }

    // ---- 알림 영역이 없을 때 ----

    @Test
    fun `알림 영역이 없으면 창을 띄우고 그 사실을 알린다`() {
        var opened = 0
        val result = TrayController.install(
            store = SettingsStore(node),
            onOpen = { opened++ },
            onSettings = {},
            onQuit = {},
            traySupported = { false },
        )
        val unavailable = assertIs<TrayResult.Unavailable>(result)
        assertEquals(1, opened, "창은 그래도 떠야 한다")
        assertTrue("알림 영역" in unavailable.message)
        // 끄는 길이 없어진다는 것을 반드시 말해야 한다.
        assertTrue("끝납니다" in unavailable.message)
    }

    // ---- 갈무리 판단 ----

    @Test
    fun `조합키만 누르면 기다린다`() {
        assertIs<CaptureOutcome.Pending>(captureOutcome(KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK))
        assertIs<CaptureOutcome.Pending>(captureOutcome(KeyEvent.VK_SHIFT, InputEvent.SHIFT_DOWN_MASK))
        assertIs<CaptureOutcome.Pending>(captureOutcome(KeyEvent.VK_ALT, InputEvent.ALT_DOWN_MASK))
        assertIs<CaptureOutcome.Pending>(captureOutcome(KeyEvent.VK_WINDOWS, InputEvent.META_DOWN_MASK))
    }

    @Test
    fun `Esc 는 갈무리를 그만둔다`() {
        assertIs<CaptureOutcome.Cancelled>(captureOutcome(KeyEvent.VK_ESCAPE, 0))
        // 조합키를 같이 눌러도 마찬가지다. 갈무리 중에 빠져나갈 문은 하나여야 한다.
        assertIs<CaptureOutcome.Cancelled>(captureOutcome(KeyEvent.VK_ESCAPE, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `조합키 없이 누르면 곧바로 까닭을 준다`() {
        val out = captureOutcome(KeyEvent.VK_A, 0)
        val rejected = assertIs<CaptureOutcome.Rejected>(out)
        assertTrue(rejected.reason.isNotBlank())
    }

    @Test
    fun `Shift 만 섞은 것도 막는다`() {
        assertIs<CaptureOutcome.Rejected>(captureOutcome(KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `쓸 수 없는 키는 막는다`() {
        assertIs<CaptureOutcome.Rejected>(captureOutcome(KeyEvent.VK_F13, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `제대로 된 조합은 갈무리된다`() {
        val out = captureOutcome(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        assertEquals(HotkeyCombo.DEFAULT, assertIs<CaptureOutcome.Captured>(out).combo)
    }

    @Test
    fun `갈무리한 조합은 Win32 코드로 들어간다`() {
        // 여기가 AWT 코드 그대로 새어 들어오는지 보는 마지막 관문이다.
        val out = captureOutcome(KeyEvent.VK_OPEN_BRACKET, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
        val combo = assertIs<CaptureOutcome.Captured>(out).combo
        assertEquals(0xDB, combo.vk)          // VK_OEM_4 이지 VK_LWIN(0x5B) 이 아니다
        assertEquals("Ctrl+Alt+[", combo.display())
    }

    // ---- 쓸 수 있는가 판단 ----

    @Test
    fun `남이 쓰는 조합만 막는다`() {
        assertTrue(HotkeyAvailability.Free.usable())
        assertTrue(HotkeyAvailability.Ours.usable())
        // 확인을 못 한 것은 사용자 잘못이 아니다. 막지 않는다.
        assertTrue(HotkeyAvailability.Unknown("JNA 없음").usable())
        assertTrue(!HotkeyAvailability.Taken("남의 것", 1409).usable())
    }
}
