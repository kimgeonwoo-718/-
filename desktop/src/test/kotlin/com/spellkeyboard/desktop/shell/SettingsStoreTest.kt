package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.Win32Keys

import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    /**
     * 사용자의 진짜 설정을 건드리면 안 된다. 시험은 제 마디에서만 논다.
     * (`defaultNode()` 가 어디를 가리키는지는 아래 따로 시험한다.)
     */
    private val node: Preferences =
        Preferences.userRoot().node("/com/spellkeyboard/desktop-test/settings-${System.nanoTime()}")

    @AfterTest
    fun cleanUp() {
        runCatching { node.removeNode() }
        runCatching { node.flush() }
    }

    @Test
    fun `빈 곳에서 읽으면 기본값이다`() {
        val s = SettingsStore(node).get()
        assertEquals(HotkeyCombo.DEFAULT, s.hotkey)
        assertEquals(DesktopSettings.DEFAULT_PASTE_DELAY_MS, s.pasteExtraDelayMs)
        assertFalse(s.hideOnFocusLoss)
        assertTrue(s.liveCorrection) // 기존 창의 기본값과 같아야 한다
    }

    @Test
    fun `적은 값이 새 저장소에서 그대로 읽힌다`() {
        val wanted = DesktopSettings(
            hotkey = HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x4B),
            pasteExtraDelayMs = 120,
            hideOnFocusLoss = true,
            liveCorrection = false,
        )
        SettingsStore(node).update(wanted)
        assertEquals(wanted, SettingsStore(node).get())
    }

    @Test
    fun `범위를 벗어난 지연은 깎인다`() {
        val store = SettingsStore(node)
        store.update(store.get().copy(pasteExtraDelayMs = 99_999))
        assertEquals(DesktopSettings.MAX_PASTE_DELAY_MS, store.get().pasteExtraDelayMs)
        store.update(store.get().copy(pasteExtraDelayMs = -5))
        assertEquals(DesktopSettings.MIN_PASTE_DELAY_MS, store.get().pasteExtraDelayMs)
    }

    @Test
    fun `저장된 단축키가 깨졌으면 기본값으로 뜬다`() {
        // 프로그램이 안 뜨는 것이 최악이다. 깨진 값은 조용히 기본값이 된다.
        node.put(SettingsStore.KEY_HOTKEY, "이건단축키가아니다")
        assertEquals(HotkeyCombo.DEFAULT, SettingsStore(node).get().hotkey)
    }

    @Test
    fun `위험한 단축키가 저장되어 있어도 기본값으로 바뀐다`() {
        // 손으로 고쳤든 옛 판이 적었든, 조합키 없는 맨 키를 들고 뜨면 안 된다.
        node.put(SettingsStore.KEY_HOTKEY, "A")
        assertEquals(HotkeyCombo.DEFAULT, SettingsStore(node).get().hotkey)
    }

    @Test
    fun `위험한 단축키는 저장도 안 된다`() {
        val store = SettingsStore(node)
        store.setHotkey(HotkeyCombo(HotkeyCombo.MOD_SHIFT, 0x41)) // Shift+A
        assertEquals(HotkeyCombo.DEFAULT, store.get().hotkey)
    }

    // ---- 듣는 이 ----

    @Test
    fun `붙이는 즉시 지금 값으로 한 번 불린다`() {
        val seen = mutableListOf<DesktopSettings>()
        SettingsStore(node).addListener { seen += it }
        assertEquals(1, seen.size)
        assertEquals(HotkeyCombo.DEFAULT, seen[0].hotkey)
    }

    @Test
    fun `바뀌면 알리고 안 바뀌면 안 알린다`() {
        val store = SettingsStore(node)
        val seen = mutableListOf<DesktopSettings>()
        store.addListener { seen += it }
        seen.clear()

        store.setLiveCorrection(false)
        assertEquals(1, seen.size)
        assertFalse(seen.last().liveCorrection)

        // 같은 값을 또 넣어도 조용해야 한다 — 안 그러면 알림 영역 네모칸이 깜빡인다.
        store.setLiveCorrection(false)
        assertEquals(1, seen.size)

        store.setLiveCorrection(true)
        assertEquals(2, seen.size)
    }

    @Test
    fun `듣는 이를 떼면 더 안 불린다`() {
        val store = SettingsStore(node)
        var count = 0
        val l = SettingsListener { count++ }
        store.addListener(l)
        count = 0
        store.removeListener(l)
        store.setLiveCorrection(false)
        assertEquals(0, count)
    }

    @Test
    fun `듣는 이 하나가 던져도 나머지는 불린다`() {
        // 설정 창 하나가 터졌다고 알림 영역 아이콘이 옛 상태로 남으면 안 된다.
        val store = SettingsStore(node)
        store.addListener { error("일부러 던진다") }
        var reached = false
        store.addListener { reached = true }
        reached = false
        store.setLiveCorrection(false)
        assertTrue(reached)
    }

    // ---- 기존 창과 같은 자리를 쓰는가 ----

    @Test
    fun `기본 마디가 기존 창이 쓰던 자리와 같다`() {
        // 갈라지면 사용자가 껐던 실시간 교정이 새 설치처럼 도로 켜진다.
        val fromPackage = Preferences.userNodeForPackage(com.spellkeyboard.desktop.CorrectorWindow::class.java)
        assertEquals(fromPackage.absolutePath(), SettingsStore.defaultNode().absolutePath())
        assertEquals("liveCorrection", SettingsStore.KEY_LIVE)
    }

    @Test
    fun `기본 저장소를 읽어도 터지지 않는다`() {
        // 사용자의 진짜 설정을 **읽기만** 한다. 쓰지 않는다.
        assertNotNull(SettingsStore(SettingsStore.defaultNode()).get())
    }
}
