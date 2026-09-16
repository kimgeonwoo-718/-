package com.spellkeyboard.desktop.hotkey

import com.spellkeyboard.desktop.hotkey.HotkeyTestSupport.F19
import com.spellkeyboard.desktop.hotkey.HotkeyTestSupport.F20
import com.spellkeyboard.desktop.hotkey.HotkeyTestSupport.F21
import com.spellkeyboard.desktop.hotkey.HotkeyTestSupport.F22
import com.spellkeyboard.desktop.hotkey.HotkeyTestSupport.isWindows
import com.spellkeyboard.desktop.hotkey.HotkeyTestSupport.postSyntheticPress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 진짜 `RegisterHotKey` 를 부르는 시험이다. **화면은 필요 없다** — 창도 Robot 도 안 쓴다.
 * 잡는 조합은 자판에 없는 F19~F22 뿐이고, 어느 길로 끝나든 [tearDown] 이 놓는다.
 */
class GlobalHotkeyManagerTest {

    private val started = ConcurrentLinkedQueue<GlobalHotkeyManager>()

    private fun manager(
        onHotkeyThread: (() -> Unit)? = null,
        onError: (Throwable) -> Unit = {},
        toUi: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
        onPressed: () -> Unit = {},
    ) = GlobalHotkeyManager("hotkey-test", onHotkeyThread, onError, toUi, onPressed).also { started.add(it) }

    @AfterTest
    fun tearDown() {
        // 시험이 터져도 단축키를 남기지 않는다. 남기면 그 조합이 온 컴퓨터에서 먹통이 된다.
        while (true) (started.poll() ?: break).stop()
    }

    @Test
    fun `MSG 구조는 48바이트여야 한다`() {
        if (!isWindows) return
        // 40바이트로 잡히면 칸이 밀려 WM_HOTKEY 를 영영 못 본다. 아무 오류도 안 난다.
        assertEquals(Win32.EXPECTED_MSG_SIZE, MSG().size())
    }

    @Test
    fun `켜면 정말 그 조합을 쥐고 끄면 정말 놓는다`() {
        if (!isWindows) return
        assertTrue(HotkeyTestSupport.isFree(F19), "시험 시작 전부터 ${F19} 가 잡혀 있다")

        val mgr = manager()
        val r = mgr.start(F19)

        assertTrue(r.ok, "등록 실패: $r")
        assertEquals(F19, mgr.current)
        assertTrue(mgr.isRunning)
        // 진짜 OS 가 쥐고 있는지 밖에서 확인한다. 1409 면 우리 것이다.
        assertEquals(Win32.ERROR_HOTKEY_ALREADY_REGISTERED, HotkeyTestSupport.errorFor(F19))

        assertTrue(mgr.stop(), "실이 안 끝났다")
        assertEquals("WM_QUIT", mgr.exitReason)
        assertNull(mgr.current)
        assertTrue(HotkeyTestSupport.isFree(F19), "끄고도 ${F19} 를 안 놓았다")
    }

    @Test
    fun `남이 쥔 조합은 실패로 돌아오고 실은 살아남는다`() {
        if (!isWindows) return
        HotkeyTestSupport.hold(F20).use {
            val mgr = manager()
            val r = mgr.start(F20)

            val failed = assertIs<HotkeyResult.Failed>(r, "남이 쥔 조합이 등록됐다")
            assertEquals(HotkeyFailure.ALREADY_TAKEN, failed.reason)
            assertEquals(Win32.ERROR_HOTKEY_ALREADY_REGISTERED, failed.errorCode)
            assertTrue(failed.message.contains(F20.format()), "사용자에게 어느 조합인지 안 알려 준다: ${failed.message}")
            assertNull(mgr.current)

            // **여기가 핵심이다.** 등록에 실패해도 실은 살아 있어야 한다. 그래야 사용자가
            // 설정에서 다른 조합을 고르면 앱을 다시 켜지 않고 갈아 끼울 수 있다.
            assertTrue(mgr.isRunning, "실패했다고 실까지 죽었다")
            val second = mgr.change(F21)
            assertTrue(second.ok, "다른 조합으로도 못 갈아 끼웠다: $second")
            assertEquals(F21, mgr.current)
        }
    }

    @Test
    fun `조합을 바꾸면 옛 것을 놓고 새 것을 쥔다`() {
        if (!isWindows) return
        val mgr = manager()
        assertTrue(mgr.start(F19).ok)

        val r = mgr.change(F22)

        assertTrue(r.ok, "$r")
        assertEquals(F22, mgr.current)
        // 덧등록 함정: 쥔 id 에 새 조합을 그냥 등록하면 TRUE 가 나면서 **둘 다** 살아남는다.
        // 옛 조합이 정말 풀렸는지 밖에서 확인해야 그 함정에 빠진 줄 안다.
        assertTrue(HotkeyTestSupport.isFree(F19), "옛 조합 ${F19} 를 아직도 쥐고 있다")
        assertEquals(Win32.ERROR_HOTKEY_ALREADY_REGISTERED, HotkeyTestSupport.errorFor(F22))
    }

    @Test
    fun `바꾸다 실패하면 옛 조합이 그대로 남는다`() {
        if (!isWindows) return
        val mgr = manager()
        assertTrue(mgr.start(F19).ok)

        HotkeyTestSupport.hold(F20).use {
            val r = mgr.change(F20)
            val failed = assertIs<HotkeyResult.Failed>(r)
            assertEquals(HotkeyFailure.ALREADY_TAKEN, failed.reason)
        }

        // 새 조합이 막혔다고 단축키를 통째로 잃으면 창을 부를 길이 없어진다.
        assertEquals(F19, mgr.current)
        assertEquals(Win32.ERROR_HOTKEY_ALREADY_REGISTERED, HotkeyTestSupport.errorFor(F19))
    }

    @Test
    fun `못 쓸 조합은 윈도우에 물어보지도 않는다`() {
        if (!isWindows) return
        val mgr = manager()
        assertTrue(mgr.start(F19).ok)

        // 윈도우는 이것들을 전부 받아 준다. 우리가 막아야 한다.
        for (bad in listOf(
            HotkeyCombo(0, 0x20),                          // 조합키 없는 Space
            HotkeyCombo(HotkeyCombo.MOD_SHIFT, 0x41),      // Shift+A — 대문자를 못 치게 된다
            HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x11),    // Ctrl+Ctrl
            HotkeyCombo(HotkeyCombo.MOD_CONTROL, 0x10000), // 윈도우가 검사 안 하는 가상키
        )) {
            val failed = assertIs<HotkeyResult.Failed>(mgr.change(bad), "$bad 가 등록됐다")
            assertEquals(HotkeyFailure.REJECTED, failed.reason)
            assertTrue(failed.message.isNotBlank())
        }
        // 막혔어도 원래 쓰던 조합은 멀쩡해야 한다.
        assertEquals(F19, mgr.current)
    }

    @Test
    fun `눌리면 EDT 에서 부르고 고리 실은 기다리지 않는다`() {
        if (!isWindows) return
        val onEdt = ConcurrentLinkedQueue<Boolean>()
        val loopHits = AtomicInteger(0)
        val done = CountDownLatch(3)
        val mgr = manager(
            onHotkeyThread = { loopHits.incrementAndGet() },
            // EDT 쪽 일이 200 ms 씩 걸려도 고리 실은 그것을 기다리면 안 된다.
            onPressed = { onEdt.add(SwingUtilities.isEventDispatchThread()); Thread.sleep(200); done.countDown() },
        )
        assertTrue(mgr.start(F19).ok)

        val t0 = System.nanoTime()
        repeat(3) { assertTrue(postSyntheticPress(mgr, F19)) }
        // 고리 실이 세 번을 다 받아 내는 데 걸린 시간. EDT 를 기다렸다면 600 ms 가 넘는다.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (loopHits.get() < 3 && System.nanoTime() < deadline) Thread.sleep(1)
        val loopMs = (System.nanoTime() - t0) / 1e6

        assertEquals(3, loopHits.get(), "고리 실이 세 번을 다 못 받았다")
        assertTrue(loopMs < 150, "고리 실이 EDT 를 기다렸다: ${"%.1f".format(loopMs)} ms")
        assertTrue(done.await(5, TimeUnit.SECONDS), "EDT 콜백이 세 번 다 안 왔다")
        assertEquals(listOf(true, true, true), onEdt.toList(), "EDT 가 아닌 데서 불렀다")
        assertEquals(3, mgr.pressCount)
    }

    @Test
    fun `콜백이 던져도 고리 실은 죽지 않는다`() {
        if (!isWindows) return
        val errors = ConcurrentLinkedQueue<Throwable>()
        val calls = AtomicInteger(0)
        val mgr = manager(
            onError = { errors.add(it) },
            // 콜백이 던지면 고리 실이 죽고, finally 가 단축키를 놓아 버려 그 조합이
            // **남은 세션 내내 죽은 키**가 된다. 오류도 안 보인다. 그래서 감싼다.
            toUi = { it.run() },   // 던지는 자리를 고리 실 바로 위로 끌어와 가장 험하게 본다
            onPressed = { calls.incrementAndGet(); error("콜백이 터졌다") },
        )
        assertTrue(mgr.start(F19).ok)

        repeat(3) {
            assertTrue(postSyntheticPress(mgr, F19))
            Thread.sleep(30)
        }

        assertEquals(3, calls.get())
        assertEquals(3, errors.size, "오류가 사용자/로그 쪽으로 안 갔다")
        assertTrue(mgr.isRunning, "콜백이 던져서 고리 실이 죽었다")
        assertEquals(F19, mgr.current)
        assertEquals(Win32.ERROR_HOTKEY_ALREADY_REGISTERED, HotkeyTestSupport.errorFor(F19))
    }

    @Test
    fun `끄기는 여러 번 불러도 탈이 없다`() {
        if (!isWindows) return
        val mgr = manager()
        assertTrue(mgr.start(F19).ok)
        repeat(5) { assertTrue(mgr.stop(), "${it}번째 stop 이 실패했다") }
        assertFalse(mgr.isRunning)
        assertTrue(HotkeyTestSupport.isFree(F19))
    }

    @Test
    fun `켜고 끄기를 되풀이해도 샘이 없다`() {
        if (!isWindows) return
        val mgr = manager()
        repeat(3) { round ->
            assertTrue(mgr.start(F19).ok, "$round 번째 등록 실패")
            assertTrue(postSyntheticPress(mgr, F19))
            assertTrue(mgr.stop(), "$round 번째 stop 실패")
            assertTrue(HotkeyTestSupport.isFree(F19), "$round 번째에 조합이 남았다")
        }
        assertEquals(3, mgr.pressCount)
    }

    @Test
    fun `안 켠 채로 바꾸면 안 켰다고 말한다`() {
        if (!isWindows) return
        val mgr = manager()
        val failed = assertIs<HotkeyResult.Failed>(mgr.change(F19))
        assertEquals(HotkeyFailure.NOT_RUNNING, failed.reason)
        assertTrue(mgr.stop(), "켜지도 않은 것을 끄는데 실패했다")
    }

    @Test
    fun `두 번 켜면 갈아 끼우는 것과 같다`() {
        if (!isWindows) return
        val mgr = manager()
        assertTrue(mgr.start(F19).ok)
        assertTrue(mgr.start(F21).ok)
        assertEquals(F21, mgr.current)
        assertTrue(HotkeyTestSupport.isFree(F19))
    }

    @Test
    fun `EDT 에서 막는 갈래를 부르면 알려 준다`() {
        if (!isWindows) return
        val errors = ConcurrentLinkedQueue<Throwable>()
        val mgr = manager(onError = { errors.add(it) })
        SwingUtilities.invokeAndWait { mgr.start(F19) }
        assertTrue(errors.any { it is IllegalStateException && it.message.orEmpty().contains("EDT") },
            "EDT 에서 불렀는데 아무 말도 없다: ${errors.toList()}")
    }

    @Test
    fun `비동기 갈래는 결과를 EDT 로 돌려준다`() {
        if (!isWindows) return
        val where = ConcurrentLinkedQueue<Boolean>()
        val got = CountDownLatch(2)
        val mgr = manager()
        // 설정 창은 EDT 에서 부르고 EDT 에서 답을 받아야 한다. 그 길을 그대로 따라간다.
        mgr.startAsync(F19) { first ->
            where.add(SwingUtilities.isEventDispatchThread())
            assertTrue(first.ok, "$first")
            got.countDown()
            mgr.changeAsync(F21) { where.add(SwingUtilities.isEventDispatchThread()); got.countDown() }
        }
        assertTrue(got.await(5, TimeUnit.SECONDS), "결과가 안 왔다")
        assertEquals(listOf(true, true), where.toList())
        assertEquals(F21, mgr.current)
    }
}
