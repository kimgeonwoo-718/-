package com.spellkeyboard.desktop.paste

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 화면 없이 도는 시험만 둔다. 창이 정말로 숨겨지고 자판이 정말로 날아가는지는
 * 사람 PC 에서만 알 수 있고, 그쪽은 `harness.MainKt` 가 재 본다.
 *
 * 여기서 지키는 것은 **순서와 문턱**이다. 이 부품이 틀리면 사용자의 진짜 문서가
 * 망가지므로, "언제 붙이지 **않는가**" 가 "언제 붙이는가" 보다 시험이 많다.
 */
class PasteBackTest {

    // ---- 가짜 바깥 ----

    /** 무슨 일이 어떤 **차례로** 일어났는지가 이 부품의 전부라 한 줄에 적는다. */
    private val log = mutableListOf<String>()

    private class FakeWindows(
        var fg: Long,
        var alive: MutableSet<Long> = mutableSetOf(),
        var pids: MutableMap<Long, Long> = mutableMapOf(),
    ) : WindowSystem {
        var reads = 0
        var setForegroundCalls = 0

        /** 앞 창이 몇 번째 읽기에서 바뀔지. 진짜 기계에서 5ms 쯤 걸리는 그 순간이다. */
        var flipsTo: Long? = null
        var flipAfter: Int = Int.MAX_VALUE

        /** 여기 없는 창은 보통 창(빈 클래스)으로 친다. */
        var classes: MutableMap<Long, String> = mutableMapOf()
        var hidden: MutableSet<Long> = mutableSetOf()

        override fun foreground(): Long {
            reads++
            flipsTo?.let { if (reads >= flipAfter) fg = it }
            return fg
        }

        override fun isWindow(hwnd: Long) = hwnd in alive
        override fun isVisible(hwnd: Long) = hwnd in alive && hwnd !in hidden
        override fun processIdOf(hwnd: Long) = pids[hwnd] ?: 0L
        override fun classOf(hwnd: Long) = classes[hwnd] ?: ""
        override fun setForeground(hwnd: Long): Boolean {
            setForegroundCalls++
            return true
        }
    }

    private inner class FakeClipboard(var content: String? = null) : ClipboardPort {
        /** 앞으로 몇 번을 던질까. 진짜로 0.3% 쯤 던진다. */
        var throwsLeft = 0
        val writes = mutableListOf<String>()

        override fun set(text: String) {
            if (throwsLeft > 0) {
                throwsLeft--
                log += "클립보드-던짐"
                throw IllegalStateException("cannot open system clipboard")
            }
            content = text
            writes += text
            log += "클립보드=$text"
        }

        override fun get(): String? = content
    }

    private inner class FakeKeys : KeyPort {
        var pressed = 0
        override fun ctrlV(holdMs: Int) {
            pressed++
            log += "Ctrl+V"
        }
    }

    private inner class FakeHider : WindowHider {
        var hidden = 0
        override fun hide() {
            hidden++
            log += "숨김"
        }
    }

    private val TARGET = 0x1234L
    private val OURS = 0x9999L

    private fun windows() = FakeWindows(fg = OURS, alive = mutableSetOf(TARGET, OURS))

    private fun pasteBack(
        w: WindowSystem,
        c: ClipboardPort,
        k: KeyPort,
        h: WindowHider,
        opt: PasteSettings = PasteSettings(waitForFocusMs = 80),
    ) = PasteBack(hider = h, settings = { opt }, windows = w, clipboard = c, keys = k)
        .apply { ownWindow = OURS }

    // ---- 붙이지 않는 경우들 ----

    @Test
    fun `빈 글은 클립보드도 창도 건드리지 않는다`() {
        val c = FakeClipboard("사용자가 아까 복사해 둔 것")
        val h = FakeHider()
        val k = FakeKeys()
        val out = pasteBack(windows(), c, k, h).pasteBack("")

        assertTrue(out is PasteOutcome.Failed, "빈 글인데 $out")
        assertEquals("사용자가 아까 복사해 둔 것", c.content, "빈 글 때문에 클립보드를 날렸다")
        assertEquals(0, h.hidden)
        assertEquals(0, k.pressed)
    }

    @Test
    fun `기억한 창이 없으면 창을 숨기지 않고 클립보드에만 남긴다`() {
        val c = FakeClipboard()
        val h = FakeHider()
        val k = FakeKeys()
        val out = pasteBack(windows(), c, k, h).pasteBack("고친 글", to = 0L)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.NO_PREVIOUS_WINDOW, windowHidden = false), out)
        assertEquals("고친 글", c.content, "실패해도 글은 클립보드에 있어야 한다")
        // 창을 숨기면 사용자는 "클립보드에 있습니다" 를 볼 수 없다.
        assertEquals(0, h.hidden, "돌아갈 곳도 없는데 창을 숨겼다")
        assertEquals(0, k.pressed, "돌아갈 창이 없는데 자판을 보냈다")
    }

    @Test
    fun `우리 창이 앞에 있었으면 붙이지 않는다`() {
        val c = FakeClipboard()
        val k = FakeKeys()
        val out = pasteBack(windows(), c, k, FakeHider()).pasteBack("고친 글", to = OURS)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.OUR_OWN_WINDOW, windowHidden = false), out)
        assertEquals(0, k.pressed)
    }

    /** 손잡이가 달라도 우리 프로세스의 창이면 안 된다 — 설정 창 같은 것이 생길 것이다. */
    @Test
    fun `같은 프로세스의 다른 창도 돌려보낼 곳이 아니다`() {
        val w = windows()
        val sibling = 0x7777L
        w.alive += sibling
        w.pids[sibling] = ProcessHandle.current().pid()
        val k = FakeKeys()

        val out = pasteBack(w, FakeClipboard(), k, FakeHider()).pasteBack("고친 글", to = sibling)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.OUR_OWN_WINDOW, windowHidden = false), out)
        assertEquals(0, k.pressed)
    }

    @Test
    fun `그새 닫힌 창에는 붙이지 않는다`() {
        val w = windows()
        w.alive.remove(TARGET)
        val k = FakeKeys()

        val out = pasteBack(w, FakeClipboard(), k, FakeHider()).pasteBack("고친 글", to = TARGET)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.WINDOW_GONE, windowHidden = false), out)
        assertEquals(0, k.pressed)
    }

    /** 트레이 아이콘으로 창을 부르면 앞 창은 작업 표시줄이다. 거기에 붙일 일은 없다. */
    @Test
    fun `작업 표시줄에는 붙이지 않는다`() {
        val w = windows()
        val tray = 0x4444L
        w.alive += tray
        w.classes[tray] = "Shell_TrayWnd"
        val k = FakeKeys()

        val out = pasteBack(w, FakeClipboard(), k, FakeHider()).pasteBack("고친 글", to = tray)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.SHELL_WINDOW, windowHidden = false), out)
        assertEquals(0, k.pressed)
    }

    /**
     * 기억까지 막아야 한다. 트레이로 부를 때마다 기억을 갱신하지 않으면 **한참 전에
     * 잡아 둔 옛 창**에 글이 박힌다 — 그게 더 나쁘다.
     */
    @Test
    fun `셸이 앞에 있었으면 옛 기억까지 지운다`() {
        val w = windows().apply { fg = TARGET }
        val p = pasteBack(w, FakeClipboard(), FakeKeys(), FakeHider())
        assertEquals(TARGET, p.remember())

        val tray = 0x4444L
        w.alive += tray
        w.classes[tray] = "Shell_TrayWnd"
        w.fg = tray

        assertEquals(0L, p.remember(), "작업 표시줄에서 불렀는데 옛 창을 그대로 겨누고 있다")
    }

    @Test
    fun `숨겨진 창에는 붙이지 않는다`() {
        val w = windows().apply { hidden += TARGET }
        val k = FakeKeys()
        val h = FakeHider()

        val out = pasteBack(w, FakeClipboard(), k, h).pasteBack("고친 글", to = TARGET)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.WINDOW_GONE, windowHidden = false), out)
        assertEquals(0, k.pressed)
        assertEquals(0, h.hidden, "어차피 못 붙일 것을 알면서 창부터 숨겼다")
    }

    /** 숨기다 튕기면 우리 창이 앞에 남는다. 그 위로 자판을 쏘면 안 된다. */
    @Test
    fun `창을 못 숨기면 붙이지 않는다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 1 }
        val k = FakeKeys()
        val broken = WindowHider { throw java.lang.reflect.InvocationTargetException(RuntimeException("EDT 가 튕겼다")) }

        val out = pasteBack(w, FakeClipboard(), k, broken).pasteBack("고친 글", to = TARGET)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.HIDE_FAILED, windowHidden = false), out)
        assertEquals(0, k.pressed)
    }

    @Test
    fun `앞 창이 끝내 안 돌아오면 붙이지 않고 사람에게 말한다`() {
        val w = windows()          // 앞 창은 영영 우리 창인 채로 있다
        val k = FakeKeys()
        val h = FakeHider()

        val out = pasteBack(w, FakeClipboard(), k, h).pasteBack("고친 글", to = TARGET)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.FOCUS_NEVER_RETURNED, windowHidden = true), out)
        assertEquals(0, k.pressed, "눈 감고 붙였다 — 이것이 남의 문서를 망가뜨리는 길이다")
        assertEquals(1, h.hidden, "숨기기는 했어야 한다")
        assertEquals(1, w.setForegroundCalls, "뒷받침을 한 번은 해 봐야 한다")
        assertTrue(out.windowHidden, "창이 숨겨졌다는 것을 엮는 쪽이 알아야 다시 띄운다")
    }

    /** 자판을 보내기 **직전**에 남이 앞을 채가는 경우. 1.2us 짜리 확인이 막는다. */
    @Test
    fun `붙이기 직전에 앞 창이 바뀌면 붙이지 않는다`() {
        val other = 0x5555L
        val w = object : WindowSystem by windows() {
            private val seen = AtomicInteger(0)
            override fun foreground(): Long = when (seen.incrementAndGet()) {
                1 -> TARGET       // 기다림이 여기서 성공하고
                else -> other     // 마지막 확인에서 남이 앞에 있다
            }
            override fun isWindow(hwnd: Long) = true
            override fun processIdOf(hwnd: Long) = 0L
            override fun setForeground(hwnd: Long) = true
        }
        val k = FakeKeys()

        val out = pasteBack(w, FakeClipboard(), k, FakeHider()).pasteBack("고친 글", to = TARGET)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.FOCUS_LOST, windowHidden = true), out)
        assertEquals(0, k.pressed)
    }

    @Test
    fun `클립보드가 끝까지 안 열리면 창을 숨기지 않는다`() {
        val c = FakeClipboard().apply { throwsLeft = 99 }
        val h = FakeHider()
        val k = FakeKeys()

        val out = pasteBack(windows(), c, k, h, PasteSettings(clipboardTries = 2))
            .pasteBack("고친 글", to = TARGET)

        assertTrue(out is PasteOutcome.Failed, "$out")
        // 글이 아무 데도 없는 채로 창까지 숨기면 사용자는 고친 글을 통째로 잃는다.
        assertEquals(0, h.hidden, "글을 어디에도 못 넣었는데 창을 숨겼다")
        assertEquals(0, k.pressed)
    }

    // ---- 붙이는 경우 ----

    @Test
    fun `앞 창이 돌아오면 클립보드 숨김 Ctrl+V 차례로 간다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 3 }
        val c = FakeClipboard()
        val k = FakeKeys()
        val h = FakeHider()

        val out = pasteBack(w, c, k, h).pasteBack("고친 글", to = TARGET)

        assertTrue(out is PasteOutcome.Pasted, "$out")
        assertEquals(1, k.pressed)
        // 차례가 뒤집히면(숨기고 나서 클립보드에 넣으면) 상대가 옛 글을 읽을 틈이 생긴다.
        assertEquals(listOf("클립보드=고친 글", "숨김", "Ctrl+V"), log)
        assertEquals(0, w.setForegroundCalls, "그냥 돌아왔는데 앞으로 끌어당겼다")
    }

    @Test
    fun `안 돌아오면 끌어당겨서라도 확인한 뒤에 붙인다`() {
        val w = windows()
        val k = FakeKeys()
        // setForeground 가 실제로 앞 창을 바꾸는 시늉을 한다.
        val pulling = object : WindowSystem by w {
            override fun setForeground(hwnd: Long): Boolean {
                w.setForegroundCalls++
                w.fg = hwnd
                return true
            }
        }

        val out = pasteBack(pulling, FakeClipboard(), k, FakeHider()).pasteBack("고친 글", to = TARGET)

        assertTrue(out is PasteOutcome.Pasted, "$out")
        assertEquals(1, k.pressed)
        assertEquals(1, w.setForegroundCalls)
    }

    @Test
    fun `클립보드가 한 번 던져도 다시 걸어서 성공한다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 1 }
        val c = FakeClipboard().apply { throwsLeft = 2 }
        val k = FakeKeys()

        val out = pasteBack(w, c, k, FakeHider()).pasteBack("고친 글", to = TARGET)

        assertTrue(out is PasteOutcome.Pasted, "$out")
        assertEquals(listOf("고친 글"), c.writes)
        assertEquals(1, k.pressed)
    }

    /** 조건 기다림은 **고정으로 자지 않는다.** 앞 창이 이미 와 있으면 바로 붙인다. */
    @Test
    fun `앞 창이 이미 와 있으면 기다리지 않는다`() {
        val w = windows().apply { fg = TARGET }
        val began = System.nanoTime()

        val out = pasteBack(w, FakeClipboard(), FakeKeys(), FakeHider(), PasteSettings(waitForFocusMs = 4_000))
            .pasteBack("고친 글", to = TARGET)

        val ms = (System.nanoTime() - began) / 1e6
        assertTrue(out is PasteOutcome.Pasted, "$out")
        assertTrue(ms < 200, "조건이 이미 참인데 ${ms}ms 나 잡고 있었다")
    }

    /** 뒷문(고정 기다림)을 켜도 **확인은 그대로** 한다. 안전이 깎이면 안 된다. */
    @Test
    fun `고정 기다림을 켜도 앞 창을 확인하지 않고는 안 붙인다`() {
        val w = windows()                       // 앞 창이 영영 안 돌아온다
        val k = FakeKeys()
        val opt = PasteSettings(useFixedDelay = true, fixedDelayMs = 10, waitForFocusMs = 50)

        val out = pasteBack(w, FakeClipboard(), k, FakeHider(), opt).pasteBack("고친 글", to = TARGET)

        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.FOCUS_NEVER_RETURNED, windowHidden = true), out)
        assertEquals(0, k.pressed, "고정으로 잤다는 이유만으로 붙였다")
    }

    @Test
    fun `고정 기다림을 켜면 그만큼 자고 나서 붙인다`() {
        val w = windows().apply { fg = TARGET }
        val opt = PasteSettings(useFixedDelay = true, fixedDelayMs = 120)
        val began = System.nanoTime()

        val out = pasteBack(w, FakeClipboard(), FakeKeys(), FakeHider(), opt).pasteBack("고친 글", to = TARGET)

        val ms = (System.nanoTime() - began) / 1e6
        assertTrue(out is PasteOutcome.Pasted, "$out")
        assertTrue(ms >= 100, "고정 120ms 를 켰는데 ${ms}ms 만에 돌아왔다")
    }

    // ---- 클립보드 되돌리기 ----

    @Test
    fun `되돌리기는 기본으로 꺼져 있고 원래 글을 읽지도 않는다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 1 }
        val c = FakeClipboard("사용자의 원래 클립보드")

        val out = pasteBack(w, c, FakeKeys(), FakeHider()).pasteBack("고친 글", to = TARGET)

        assertTrue(out is PasteOutcome.Pasted, "$out")
        Thread.sleep(120)
        assertEquals("고친 글", c.content, "끄라고 했는데 되돌렸다")
    }

    @Test
    fun `되돌리기를 켜면 기다린 뒤에 원래 글을 되돌린다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 1 }
        val c = FakeClipboard("사용자의 원래 클립보드")
        val opt = PasteSettings(restoreClipboard = true, restoreDelayMs = 500)

        val out = pasteBack(w, c, FakeKeys(), FakeHider(), opt).pasteBack("고친 글", to = TARGET)
        assertTrue(out is PasteOutcome.Pasted, "$out")

        // 상대가 클립보드를 읽는 데 21~25ms 가 걸린다. 그 안에 되돌아가면 **옛 글이 박힌다.**
        Thread.sleep(200)
        assertEquals("고친 글", c.content, "500ms 도 안 됐는데 벌써 되돌렸다 — 옛 글이 박힌다")

        val until = System.currentTimeMillis() + 2_000
        while (c.content != "사용자의 원래 클립보드" && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("사용자의 원래 클립보드", c.content, "되돌린다고 해 놓고 안 되돌렸다")
    }

    @Test
    fun `되돌리는 사이에 사용자가 다른 것을 복사했으면 덮지 않는다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 1 }
        val c = FakeClipboard("사용자의 원래 클립보드")
        val opt = PasteSettings(restoreClipboard = true, restoreDelayMs = 500)

        pasteBack(w, c, FakeKeys(), FakeHider(), opt).pasteBack("고친 글", to = TARGET)
        c.content = "그 사이 사용자가 복사한 것"      // 임자가 바뀌었다

        Thread.sleep(900)
        assertEquals("그 사이 사용자가 복사한 것", c.content, "사용자가 방금 복사한 것을 덮었다")
    }

    /**
     * 가장 무서운 경우다. 못 붙였다면 클립보드의 고친 글이 **유일한 사본**이고 사용자는
     * 그것을 손으로 Ctrl+V 하려 한다. 그 사이에 되돌리기가 돌면 고친 글이 사라진다.
     */
    @Test
    fun `못 붙였으면 되돌리기를 아예 걸지 않는다`() {
        val w = windows()                       // 앞 창이 영영 안 돌아온다
        val c = FakeClipboard("사용자의 원래 클립보드")
        val opt = PasteSettings(restoreClipboard = true, restoreDelayMs = 500, waitForFocusMs = 60)

        val out = pasteBack(w, c, FakeKeys(), FakeHider(), opt).pasteBack("고친 글", to = TARGET)
        assertEquals(PasteOutcome.OnClipboard(PasteOutcome.Reason.FOCUS_NEVER_RETURNED, windowHidden = true), out)

        Thread.sleep(900)
        assertEquals("고친 글", c.content, "못 붙였는데 되돌려서 고친 글을 없앴다")
    }

    @Test
    fun `되돌릴지 말지는 우리 글이 그대로일 때만 참이다`() {
        assertTrue(shouldRestore("우리 글", "우리 글"))
        assertFalse(shouldRestore("남의 글", "우리 글"))
        assertFalse(shouldRestore(null, "우리 글"), "글이 아닌 것이 들어 있으면 우리 것이 아니다")
    }

    // ---- 부르는 법 ----

    @Test
    fun `EDT 에서 부르면 그 자리에서 튕긴다`() {
        var thrown: Throwable? = null
        java.awt.EventQueue.invokeAndWait {
            thrown = runCatching {
                pasteBack(windows(), FakeClipboard(), FakeKeys(), FakeHider()).pasteBack("고친 글", to = TARGET)
            }.exceptionOrNull()
        }
        // 잠그는 대신 큰 소리로 터진다. 조용히 굳는 것보다 낫다.
        assertTrue(thrown is IllegalStateException, "EDT 에서 불렀는데 안 튕겼다: $thrown")
    }

    @Test
    fun `비동기 입구는 EDT 를 잡지 않고 결과를 EDT 로 돌려준다`() {
        val w = windows().apply { flipsTo = TARGET; flipAfter = 1 }
        val done = CountDownLatch(1)
        var onEdt = false
        var out: PasteOutcome? = null

        java.awt.EventQueue.invokeAndWait {
            pasteBack(w, FakeClipboard(), FakeKeys(), FakeHider()).pasteBackAsync("고친 글", to = TARGET) {
                onEdt = java.awt.EventQueue.isDispatchThread()
                out = it
                done.countDown()
            }
        }
        if (!done.await(5, TimeUnit.SECONDS)) fail("비동기 결과가 안 왔다")
        assertTrue(out is PasteOutcome.Pasted, "$out")
        assertTrue(onEdt, "결과를 EDT 밖에서 돌려줬다 — 받는 쪽이 화면을 못 고친다")
    }

    // ---- 기억하기 ----

    @Test
    fun `단축키를 또 눌러도 먼저 기억한 창을 잃지 않는다`() {
        val w = windows().apply { fg = TARGET }
        val p = pasteBack(w, FakeClipboard(), FakeKeys(), FakeHider())

        assertEquals(TARGET, p.remember(), "앞 창을 기억 못 했다")
        w.fg = OURS                                  // 우리 창을 보면서 단축키를 또 눌렀다
        assertEquals(TARGET, p.remember(), "우리 창으로 덮어써서 진짜 목표를 잃었다")

        p.forget()
        assertEquals(0L, p.target)
    }

    @Test
    fun `앞 창이 없을 때는 아무 것도 기억하지 않는다`() {
        val w = windows().apply { fg = 0L }
        val p = pasteBack(w, FakeClipboard(), FakeKeys(), FakeHider())
        assertEquals(0L, p.remember())
    }

    // ---- 설정 ----

    @Test
    fun `설정 창이 무슨 값을 넣어도 위험해지지 않게 자른다`() {
        val wild = PasteSettings(
            waitForFocusMs = 0,
            fixedDelayMs = -5,
            settleMs = 99_999,
            holdMs = -1,
            restoreDelayMs = 0,
            clipboardTries = 0,
        ).sane()

        assertEquals(50, wild.waitForFocusMs)
        assertEquals(0, wild.fixedDelayMs)
        assertEquals(500, wild.settleMs)
        assertEquals(0, wild.holdMs)
        // 여기가 가장 중요하다. 500ms 아래로 내려가면 사용자 문서에 옛 클립보드가 박힌다.
        assertEquals(500, wild.restoreDelayMs, "되돌리기 여유를 500ms 아래로 깎았다")
        assertEquals(1, wild.clipboardTries)
    }

    @Test
    fun `기본값은 조건 기다림이고 되돌리기는 꺼져 있다`() {
        val d = PasteSettings()
        assertFalse(d.useFixedDelay, "고정 기다림이 기본이 되면 안 된다")
        assertFalse(d.restoreClipboard, "되돌리기가 기본이 되면 안 된다")
        assertNotEquals(0L, d.waitForFocusMs)
    }

    @Test
    fun `설정을 적었다 읽으면 그대로 돌아온다`() {
        val prefs = java.util.prefs.Preferences.userRoot().node("spell-desktop-test/paste-${System.nanoTime()}")
        try {
            val mine = PasteSettings(
                waitForFocusMs = 700,
                useFixedDelay = true,
                fixedDelayMs = 90,
                settleMs = 3,
                restoreClipboard = true,
                restoreDelayMs = 800,
            )
            mine.save(prefs)
            val back = PasteSettings.load(prefs)
            assertEquals(700, back.waitForFocusMs)
            assertTrue(back.useFixedDelay)
            assertEquals(90, back.fixedDelayMs)
            assertEquals(3, back.settleMs)
            assertTrue(back.restoreClipboard)
            assertEquals(800, back.restoreDelayMs)
        } finally {
            prefs.removeNode()
        }
    }
}
