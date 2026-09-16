package com.spellkeyboard.desktop.hotkey

import com.sun.jna.Native
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * 전역 단축키 하나를 쥐고, 눌리면 [onPressed] 를 **EDT 에서** 부른다.
 *
 * ## 왜 제 실을 따로 쓰는가
 *
 * `RegisterHotKey(NULL, …)` 은 단축키를 **부른 실에 묶는다.** WM_HOTKEY 는 그 실의 메시지
 * 줄로만 들어온다. 다른 실이 제 줄을 아무리 퍼내도 아무것도 못 본다(재 봤다: 옆 실은
 * 1.5초 동안 0건, 등록한 실이 퍼내니 두 건이 그대로 있었다). 그래서 **등록도 퍼내기도 한
 * 실**이어야 하고, 그 실은 GetMessage 에서 잠들어 있어야 한다. EDT 를 쓸 수는 없다 —
 * EDT 는 AWT 의 것이고 우리가 고리를 돌릴 수 없다.
 *
 * ## 공짜다
 *
 * GetMessage 로 막혀 있는 실은 CPU 를 **한 나노초도** 안 쓴다(15초 동안 0 ns, 프로세스
 * 전체 CPU 도 실이 있으나 없으나 46 ms 로 같았다). PeekMessage 로 돌리면 코어 하나의
 * 99.5% 를 태운다. 그래서 PeekMessage 는 실이 뜰 때 큐를 만드는 데 딱 한 번만 쓴다.
 *
 * ## 쓰는 법
 *
 * ```
 * val hotkeys = GlobalHotkeyManager(
 *     onHotkeyThread = { target = foregroundWindowHandle() },   // 창 띄우기 **전에** 읽어야 하는 것
 *     onError = { log(it) },
 * ) { toggleFloatingWindow() }                                  // EDT 에서 불린다
 *
 * val r = hotkeys.start(HotkeyCombo.parseOrDefault(prefs.get(KEY, null)))
 * if (!r.ok) showToUser((r as HotkeyResult.Failed).message)     // 절대 삼키지 마라
 * …
 * hotkeys.changeAsync(newCombo) { if (!it.ok) showToUser(...) } // 설정에서 바꿀 때
 * hotkeys.stop()                                               // 트레이 '종료' 에서
 * ```
 *
 * @param onPressed 단축키가 눌렸을 때 할 일. **EDT 에서** 불린다. 여기서 던져도 고리 실은
 *   안 죽는다([onError] 로 간다).
 * @param onHotkeyThread 눌린 **그 순간에만** 읽을 수 있는 것(앞 창 손잡이 따위)을 갈무리할
 *   자리. 단축키 실에서 불리므로 마이크로초짜리 일만 해라. 없어도 된다.
 * @param onError 로그로 남길 자리다. **사용자에게 보여 줄 말은 여기가 아니라**
 *   [HotkeyResult.Failed.message] 다. 여기로는 콜백이 던진 것과 우리 쪽 잔소리가 온다.
 * @param toUi EDT 로 넘기는 방법. 시험에서만 갈아 끼운다. **invokeAndWait 를 넣지 마라** —
 *   단축키 실이 EDT 를 기다리다 다음 누름을 놓친다.
 */
class GlobalHotkeyManager(
    private val threadName: String = "global-hotkey",
    private val onHotkeyThread: (() -> Unit)? = null,
    private val onError: (Throwable) -> Unit = { it.printStackTrace() },
    private val toUi: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val onPressed: () -> Unit,
) {
    /** 지금 실제로 쥐고 있는 조합. 아무것도 못 쥐었으면 null. */
    @Volatile
    var current: HotkeyCombo? = null
        private set

    /** 마지막 [start]/[change] 의 결과. 설정 창이 나중에 다시 읽을 수 있게 남겨 둔다. */
    @Volatile
    var lastResult: HotkeyResult? = null
        private set

    /** 눌린 횟수. 진단과 시험용이다. */
    @Volatile
    var pressCount: Int = 0
        private set

    /** 고리가 어떻게 끝났는지. 진단용. */
    @Volatile
    var exitReason: String = "아직 시작하지 않음"
        private set

    val isRunning: Boolean get() = loopThread?.isAlive == true

    /** 단축키 실의 Win32 실 번호. 0 이면 아직 안 떴거나 이미 죽었다. */
    @Volatile
    var win32ThreadId: Int = 0
        private set

    private val lock = Any()
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val firstResult = AtomicReference<HotkeyResult?>(null)
    private val warnedOnEdt = AtomicBoolean(false)

    @Volatile private var loopThread: Thread? = null

    /** 설정 창이 부르는 비동기 갈래가 쓰는 실. 부르기 전에는 만들지도 않는다. */
    private val worker by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "$threadName-worker").apply { isDaemon = true } }
    }

    // ------------------------------------------------------------------ 켜기
    /**
     * 단축키 실을 띄우고 [combo] 를 등록한다. 등록이 끝날 때까지 기다렸다가 **결과를
     * 돌려준다** — 실패도 결과다.
     *
     * 등록에 실패해도 **실은 살아 있다.** 그래야 사용자가 설정에서 다른 조합을 골랐을 때
     * 앱을 다시 켜지 않고 [change] 로 갈아 끼울 수 있다.
     *
     * 이미 켜져 있으면 [change] 와 같게 움직인다(두 번 불러도 안전하다).
     *
     * 이 기계에서 잰 값 1~3 ms. EDT 에서 부르지 마라 — [startAsync] 가 있다.
     */
    fun start(combo: HotkeyCombo = HotkeyCombo.DEFAULT): HotkeyResult = synchronized(lock) {
        warnIfEdt("start")
        val running = loopThread
        if (running != null && running.isAlive) return change(combo)

        firstResult.set(null)
        tasks.clear()
        val ready = CountDownLatch(1)
        val t = Thread({ pump(combo, ready) }, threadName).apply { isDaemon = true }
        loopThread = t
        t.start()
        val result = if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            HotkeyResult.Failed(combo, HotkeyFailure.TIMEOUT, 0, "단축키 실이 제때 뜨지 않았습니다.")
        } else {
            firstResult.get() ?: HotkeyResult.Failed(combo, HotkeyFailure.WINDOWS_ERROR)
        }
        lastResult = result
        result
    }

    /** [start] 를 일꾼 실에서 하고 결과를 [toUi] 로 돌려준다. EDT 에서 부르기 좋은 갈래. */
    fun startAsync(combo: HotkeyCombo = HotkeyCombo.DEFAULT, onResult: (HotkeyResult) -> Unit) {
        worker.execute { val r = start(combo); toUi(Runnable { onResult(r) }) }
    }

    // ------------------------------------------------------------------ 바꾸기
    /**
     * 설정에서 조합이 바뀌었을 때. **앱을 다시 켤 필요가 없다.**
     *
     * 실패하면 **옛 조합을 도로 등록해 둔다.** 새 조합이 막혔다고 단축키를 통째로 잃으면
     * 사용자는 창을 부를 길이 없어진다.
     *
     * 이 기계에서 잰 값 0.1 ms 남짓. 그래도 EDT 에서는 [changeAsync] 를 써라.
     */
    fun change(combo: HotkeyCombo): HotkeyResult {
        warnIfEdt("change")
        val t = loopThread
        if (t == null || !t.isAlive) {
            return HotkeyResult.Failed(combo, HotkeyFailure.NOT_RUNNING).also { lastResult = it }
        }
        combo.rejectReason()?.let {
            // 윈도우한테 물어볼 것도 없다. 여기서 막고 까닭을 그대로 돌려준다.
            return HotkeyResult.Failed(combo, HotkeyFailure.REJECTED, 0, it).also { r -> lastResult = r }
        }
        val result = onLoopThread(combo) {
            val old = current
            // **반드시 먼저 놓는다.** 쥐고 있는 id 에 다른 조합을 덧등록하면 TRUE 가 나면서
            // 둘 다 살아남는다 — 옛 조합은 온 컴퓨터에서 계속 삼켜지는데 우리는 옮긴 줄 안다.
            // 한 번 그렇게 되면 UnregisterHotKey 한 번에 둘이 같이 풀려서 눈치도 못 챈다.
            if (old != null) {
                Win32.user32.UnregisterHotKey(null, HOTKEY_ID)
                current = null
            }
            val r = registerHere(combo)
            if (!r.ok && old != null) {
                val back = Win32.user32.RegisterHotKey(null, HOTKEY_ID, old.modifiers or Win32.MOD_NOREPEAT, old.keyCode)
                if (back) current = old
            }
            r
        }
        lastResult = result
        return result
    }

    /** [change] 를 일꾼 실에서 하고 결과를 [toUi] 로 돌려준다. 설정 창은 이것을 써라. */
    fun changeAsync(combo: HotkeyCombo, onResult: (HotkeyResult) -> Unit) {
        worker.execute { val r = change(combo); toUi(Runnable { onResult(r) }) }
    }

    // ------------------------------------------------------------------ 끄기
    /**
     * 고리에 WM_QUIT 를 부쳐 곱게 끝낸다. 실이 빠져나가며 `finally` 에서 단축키를 놓는다.
     * 잰 값 0.2 ms, 여러 번 불러도 탈 없다(두 번째부터는 0.00 ms).
     *
     * 안 부르고 죽어도 윈도우가 실이 죽을 때 단축키를 거둬 간다 — 그래도 트레이 '종료'
     * 에서는 불러라. 끝나는 길이 분명해야 다음 사람이 안 헷갈린다.
     *
     * 트레이 메뉴는 EDT 에서 도는데, 여기는 **EDT 에서 불러도 된다**. 잰 값이 0.2 ms 이고
     * 어차피 끝내는 길이라 화면이 멈출 일이 없다. 끝내지 않고 끄기만 할 때만 [stopAsync].
     *
     * @return 실이 정말 끝났으면 참.
     */
    fun stop(joinMs: Long = STOP_JOIN_MS): Boolean = synchronized(lock) {
        val t = loopThread ?: return true
        if (!t.isAlive) { loopThread = null; return true }
        val tid = win32ThreadId
        if (tid != 0 && !Win32.user32.PostThreadMessageW(tid, Win32.WM_QUIT, UlongPtr(), UlongPtr())) {
            // 1444 면 실이 이미 죽은 것이다. 아래 join 이 곧 밝혀 준다.
            exitReason = "WM_QUIT 를 못 부쳤다 (오류 ${Native.getLastError()})"
        }
        t.join(joinMs)
        val dead = !t.isAlive
        if (dead) loopThread = null
        dead
    }

    /** [stop] 을 일꾼 실에서. 트레이 '종료' 가 EDT 를 막지 않고 끝내고 싶을 때. */
    fun stopAsync(onDone: (Boolean) -> Unit = {}) {
        worker.execute { val ok = stop(); toUi(Runnable { onDone(ok) }) }
    }

    // ------------------------------------------------------------------ 속살
    /**
     * 단축키 실 본체. 이 실만이 등록하고, 이 실만이 퍼낸다.
     */
    private fun pump(initial: HotkeyCombo, ready: CountDownLatch) {
        win32ThreadId = Win32.kernel32.GetCurrentThreadId()
        val msg = MSG()
        if (msg.size() != Win32.EXPECTED_MSG_SIZE) {
            // 크기가 어긋나면 칸이 통째로 밀려 **아무 말 없이** WM_HOTKEY 를 못 본다.
            // 조용히 먹통이 되느니 여기서 못 한다고 말하고 끝낸다.
            firstResult.set(
                HotkeyResult.Failed(
                    initial, HotkeyFailure.BROKEN_BINDING, 0,
                    "MSG 구조가 ${msg.size()}바이트로 잡혔습니다 (48이어야 합니다)."
                )
            )
            exitReason = "MSG 크기 어긋남"
            ready.countDown()
            return
        }
        // 큐부터 만들어 둔다. 큐를 한 번도 안 만진 실에는 PostThreadMessage 가 1444 로
        // 튕긴다 — RegisterHotKey 도 큐를 만들지만, 등록이 실패하는 길에서도 [stop] 과
        // [change] 가 통해야 하므로 여기서 먼저 만든다. 공짜다.
        Win32.user32.PeekMessageW(msg, null, Win32.WM_USER, Win32.WM_USER, Win32.PM_NOREMOVE)

        firstResult.set(registerHere(initial))
        ready.countDown()

        try {
            while (true) {
                val r = Win32.user32.GetMessageW(msg, null, 0, 0)   // 여기서 잠든다. 공짜다.
                if (r == -1) { exitReason = "GetMessage 오류 ${Native.getLastError()}"; break }
                if (r == 0) { exitReason = "WM_QUIT"; break }
                when (msg.message) {
                    // wParam 은 등록할 때 준 id 가 그대로 돌아온다. 우리 것만 받는다.
                    Win32.WM_HOTKEY -> if (msg.wParam.toInt() == HOTKEY_ID) fire()
                    Win32.WM_APP_TASK -> while (true) (tasks.poll() ?: break).run()
                }
                // 이 실에는 창이 없다. TranslateMessage/DispatchMessage 는 할 일이 없다.
            }
        } catch (t: Throwable) {
            exitReason = "고리가 터졌다: $t"
            runCatching { onError(t) }
        } finally {
            // 어느 길로 나가든 놓고 나간다. 안 놓고 죽으면 그 조합이 이 프로세스가 살아
            // 있는 동안 온 컴퓨터에서 먹통이 된다.
            if (current != null) Win32.user32.UnregisterHotKey(null, HOTKEY_ID)
            current = null
            win32ThreadId = 0
        }
    }

    /** **단축키 실에서만** 불러야 한다. 등록은 퍼내는 실이 해야 WM_HOTKEY 가 거기로 온다. */
    private fun registerHere(combo: HotkeyCombo): HotkeyResult {
        combo.rejectReason()?.let { return HotkeyResult.Failed(combo, HotkeyFailure.REJECTED, 0, it) }
        // MOD_NOREPEAT: 키를 누르고 있으면 자동 반복이 쏟아지는데, 그것을 한 번으로 묶어 준다
        // (반복 20번이 1번으로 줄었다). 저장값에는 넣지 않는다 — lParam 에서는 벗겨져 온다.
        val ok = Win32.user32.RegisterHotKey(null, HOTKEY_ID, combo.modifiers or Win32.MOD_NOREPEAT, combo.keyCode)
        // 실패한 그 자리에서 바로 읽는다. 한 줄만 지나도 다른 호출이 덮어쓴다.
        val err = if (ok) 0 else Native.getLastError()
        return if (ok) {
            current = combo
            HotkeyResult.Ok(combo)
        } else {
            // 1409 는 "남이 쓴다" 와 "내가 이미 쥐고 있다" 를 구별해 주지 않는다. 그래서
            // 우리가 쥔 것은 [current] 로 따로 적어 두고, 바꿀 때 반드시 먼저 놓는다.
            HotkeyResult.Failed(combo, HotkeyFailure.of(err), err)
        }
    }

    /** 일감을 단축키 실 위에서 돌린다. (해제·등록은 그 실만 할 수 있다.) */
    private fun onLoopThread(combo: HotkeyCombo, body: () -> HotkeyResult): HotkeyResult {
        val done = CountDownLatch(1)
        val out = AtomicReference<HotkeyResult?>(null)
        val task = Runnable {
            out.set(runCatching(body).getOrElse { e ->
                runCatching { onError(e) }
                HotkeyResult.Failed(combo, HotkeyFailure.WINDOWS_ERROR, 0, "단축키를 바꾸다 실패했습니다: ${e.message}")
            })
            done.countDown()
        }
        tasks.add(task)
        val tid = win32ThreadId
        if (tid == 0 || !Win32.user32.PostThreadMessageW(tid, Win32.WM_APP_TASK, UlongPtr(), UlongPtr())) {
            tasks.remove(task)
            return HotkeyResult.Failed(combo, HotkeyFailure.NOT_RUNNING, if (tid == 0) 0 else Native.getLastError())
        }
        if (!done.await(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            tasks.remove(task)
            return HotkeyResult.Failed(combo, HotkeyFailure.TIMEOUT)
        }
        return out.get() ?: HotkeyResult.Failed(combo, HotkeyFailure.WINDOWS_ERROR)
    }

    /** 단축키가 눌렸다. **이 실은 곧장 GetMessage 로 돌아가야 한다.** */
    private fun fire() {
        pressCount++
        try {
            // 앞 창 손잡이처럼 창을 띄우기 **전에** 읽어야 하는 것은 여기서 읽는다.
            onHotkeyThread?.invoke()
        } catch (t: Throwable) {
            runCatching { onError(t) }
        }
        try {
            // 진짜 일은 EDT 로 넘긴다. invokeLater 는 곧장 돌아오므로 이 실은 안 막히고,
            // 창을 EDT 밖에서 만지는 사고도 원천에서 막힌다.
            toUi(Runnable {
                // 여기서 던지면 고리 실이 죽고 단축키가 **조용히** 먹통이 된다. 반드시 감싼다.
                try { onPressed() } catch (t: Throwable) { runCatching { onError(t) } }
            })
        } catch (t: Throwable) {
            runCatching { onError(t) }
        }
    }

    /**
     * EDT 에서 막는 갈래를 부르면 한 번만 알려 준다. 잰 값은 1~3 ms 라 당장은 티가 안 나지만,
     * 고리 실이 무슨 까닭으로 막히면 그 시간이 그대로 화면이 얼어붙는 시간이 된다.
     */
    private fun warnIfEdt(who: String) {
        if (!SwingUtilities.isEventDispatchThread()) return
        if (!warnedOnEdt.compareAndSet(false, true)) return
        runCatching {
            onError(IllegalStateException("$who() 를 EDT 에서 불렀다. ${who}Async() 를 써라."))
        }
    }

    companion object {
        /**
         * 단축키 id. 실마다 따로 세는 값이라 아무 숫자나 되지만, 눈에 띄는 값을 쓰면
         * wParam 으로 돌아온 값을 볼 때 우리 것인지 한눈에 안다.
         */
        internal const val HOTKEY_ID = 0xC0DE

        /** 실이 뜨고 등록까지 걸리는 시간. 잰 값 1~3 ms 라 넉넉하다. */
        private const val START_TIMEOUT_MS = 5_000L

        /** 고리 실에 일감을 맡기고 기다리는 시간. 잰 값 0.1 ms. */
        private const val TASK_TIMEOUT_MS = 3_000L

        /** WM_QUIT 를 부치고 실이 나가기를 기다리는 시간. 잰 값 0.2 ms. */
        private const val STOP_JOIN_MS = 3_000L
    }
}
