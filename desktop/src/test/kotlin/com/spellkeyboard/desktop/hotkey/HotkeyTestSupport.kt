package com.spellkeyboard.desktop.hotkey

import com.sun.jna.Native
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 시험 도구. **화면은 필요 없다** — RegisterHotKey 는 창도 그림도 없이 도는 커널 호출이라
 * 여기 있는 것들은 전부 머리 없는 채로 돈다.
 *
 * 시험이 잡는 조합은 전부 F19~F22 다. 실제 자판에 없는 키라 어떤 프로그램도 거기에 무엇을
 * 걸어 두지 않았고, 혹 시험이 중간에 죽어도 사용자가 쓰던 키를 빼앗지 않는다.
 */
internal object HotkeyTestSupport {
    private const val PROBE_ID = 0x5150
    private val u = Win32.user32

    val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** 이 조합이 비어 있는가. 잠깐 잡아 보고 바로 놓는다. */
    fun isFree(combo: HotkeyCombo): Boolean {
        val ok = u.RegisterHotKey(null, PROBE_ID, combo.modifiers or Win32.MOD_NOREPEAT, combo.keyCode)
        if (ok) u.UnregisterHotKey(null, PROBE_ID)
        return ok
    }

    /** 잡아 보고 실패한 까닭. 0 이면 잡혔다(그리고 도로 놓았다). */
    fun errorFor(combo: HotkeyCombo): Int {
        val ok = u.RegisterHotKey(null, PROBE_ID, combo.modifiers or Win32.MOD_NOREPEAT, combo.keyCode)
        val err = if (ok) 0 else Native.getLastError()
        if (ok) u.UnregisterHotKey(null, PROBE_ID)
        return err
    }

    /**
     * "남이 쓰고 있는" 상황을 만든다. 등록한 실이 살아 있는 동안만 조합이 잡혀 있으므로
     * 제 실을 하나 띄워 붙들고 있다가 [Holder.close] 에서 놓는다.
     */
    fun hold(combo: HotkeyCombo): Holder {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val err = AtomicInteger(-1)
        val t = Thread({
            val ok = u.RegisterHotKey(null, PROBE_ID + 1, combo.modifiers or Win32.MOD_NOREPEAT, combo.keyCode)
            err.set(if (ok) 0 else Native.getLastError())
            ready.countDown()
            release.await()
            if (ok) u.UnregisterHotKey(null, PROBE_ID + 1)
        }, "hold-${combo.format()}").apply { isDaemon = true }
        t.start()
        check(ready.await(3, TimeUnit.SECONDS)) { "붙드는 실이 안 떴다" }
        check(err.get() == 0) { "${combo.format()} 를 붙들지 못했다 (오류 ${err.get()})" }
        return Holder(t, release)
    }

    class Holder(private val t: Thread, private val release: CountDownLatch) : AutoCloseable {
        override fun close() {
            release.countDown()
            t.join(3000)
        }
    }

    /**
     * 진짜 키를 누르지 않고 WM_HOTKEY 만 고리에 부친다.
     *
     * 왜 이렇게 하는가: 시험이 사용자 자판을 건드리지 않아야 하고, Robot 은 화면이 있어야
     * 한다. 고리가 메시지를 받아 콜백까지 옮기는 길은 이렇게 해도 **한 줄도 다르지 않다**.
     * 진짜 자판으로 눌러 보는 것은 [LiveCheck] 가 따로 한다.
     */
    fun postSyntheticPress(mgr: GlobalHotkeyManager, combo: HotkeyCombo): Boolean {
        val tid = mgr.win32ThreadId
        if (tid == 0) return false
        val lParam = (combo.keyCode.toLong() shl 16) or combo.modifiers.toLong()
        return u.PostThreadMessageW(
            tid, Win32.WM_HOTKEY,
            UlongPtr(GlobalHotkeyManager.HOTKEY_ID.toLong()), UlongPtr(lParam)
        )
    }

    /** 시험에 쓰는 조합들. 자판에 없는 F19~F22 라 아무도 안 쓴다. */
    val F19 = HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x82)
    val F20 = HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT, 0x83)
    val F21 = HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_SHIFT, 0x84)
    val F22 = HotkeyCombo(HotkeyCombo.MOD_CONTROL or HotkeyCombo.MOD_ALT or HotkeyCombo.MOD_SHIFT, 0x85)
}
