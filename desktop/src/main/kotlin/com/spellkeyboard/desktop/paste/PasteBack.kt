package com.spellkeyboard.desktop.paste

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/**
 * 고친 글을 **사용자가 치고 있던 그 창으로 돌려보낸다.**
 *
 * ## 차례
 *
 * 1. 단축키가 눌리면 [remember] — 우리 창을 띄우기 **전에** 앞 창을 갈무리한다.
 * 2. 사용자가 Enter — [pasteBack] 이 클립보드에 넣고, 우리 창을 숨기고,
 *    앞 창이 **정말로** 돌아온 것을 확인한 뒤에 Ctrl+V 를 보낸다.
 *
 * ## 왜 이렇게까지 조심하는가
 *
 * 이 부품은 틀리면 **사용자의 진짜 문서를 망가뜨린다.** 그래서 규칙이 하나 있다 —
 * `GetForegroundWindow` 가 내가 기억한 그 창이라고 답하기 전에는 절대 자판을 보내지
 * 않는다. 못 보내면 글은 클립보드에 그대로 두고 사람에게 말한다. 눈 감고 붙이는 것보다
 * "Ctrl+V 로 붙여 넣으세요" 가 언제나 낫다.
 *
 * ## 재 본 것 (이 기계, 윈도우 11 26200)
 *
 * - 창을 숨기고 앞 창이 돌아오기까지 **3.8~8.3ms**. 250번 재는 동안 엉뚱한 제3의 창이
 *   중간에 앞으로 나선 적은 **한 번도 없었다.**
 * - 조건을 기다리는 방식은 100/100 성공, 평균 5.5ms. `GetForegroundWindow` 한 번이
 *   1.2us 라 바쁘게 돌아도 공짜다.
 * - Ctrl+V 를 너무 일찍 보내면 **엉뚱한 창에 붙는 것이 아니라 그냥 사라진다.** 420번
 *   중 잘못 붙은 것 0. 그래도 이 부품은 확인하고 보낸다 — 사라지는 것이 늘 보장되는
 *   성질은 아니다.
 */
class PasteBack(
    private val hider: WindowHider,
    private val settings: () -> PasteSettings = { PasteSettings() },
    private val windows: WindowSystem = Win32Windows,
    private val clipboard: ClipboardPort = AwtClipboard,
    private val keys: KeyPort = RobotKeys,
) {

    /**
     * 우리 창 손잡이. [ownWindowHandle] 로 창을 띄운 **직후 EDT 에서 한 번만** 구해
     * 여기에 넣어 둬라.
     *
     * 0 이어도 동작은 한다 — 같은 프로세스인지도 따로 보기 때문이다. 다만 "우리 창이
     * 앞에 있었다" 는 말을 정확히 해 주려면 넣어 두는 편이 낫다.
     */
    @Volatile
    var ownWindow: Long = 0L

    /** 단축키가 눌렸을 때 앞에 있던 창. 0 이면 모른다는 뜻이다. */
    @Volatile
    var target: Long = 0L
        private set

    /**
     * 단축키 실에서, **우리 창을 띄우기 전에** 불러라. 창을 띄운 뒤에 부르면
     * `GetForegroundWindow` 가 우리 창을 돌려준다.
     *
     * 우리 것으로 보이는 창은 기억하지 않는다. 사용자가 우리 창을 보면서 단축키를 또
     * 누르는 일은 흔하고, 그때 기억을 우리 창으로 덮어쓰면 **직전에 기억해 둔 진짜
     * 목표를 잃는다.**
     *
     * 반대로 셸(작업 표시줄·바탕화면)이 앞에 있었다면 **일부러 잊는다.** 까닭은
     * [SHELL_CLASSES] 를 보라 — 트레이로 창을 부르는 길이 이 경우다.
     */
    fun remember(): Long = remember(windows.foreground())

    /** 단축키 부품이 이미 앞 창을 읽어 왔다면 그 값을 그대로 넘겨라. */
    fun remember(hwnd: Long): Long {
        if (hwnd == 0L || isOurs(hwnd)) return target
        target = if (windows.classOf(hwnd) in SHELL_CLASSES) 0L else hwnd
        return target
    }

    /** 창을 아이콘이나 메뉴로 열었을 때처럼, 돌아갈 곳이 없는 것이 확실한 경우. */
    fun forget() {
        target = 0L
    }

    /**
     * 고친 글을 앞 창에 돌려 넣는다. **EDT 에서 부르지 마라** — 안에서
     * `invokeAndWait` 을 쓰므로 그 자리에서 잠긴다. [pasteBackAsync] 를 써라.
     */
    fun pasteBack(text: String, to: Long = target): PasteOutcome {
        check(!SwingUtilities.isEventDispatchThread()) {
            "pasteBack 은 EDT 에서 부르면 안 된다. pasteBackAsync 를 써라."
        }
        val opt = settings().sane()

        // 빈 글을 붙이면 상대 창에서 **고른 글이 지워진다.** 아무 것도 하지 않는 쪽이 옳다.
        if (text.isEmpty()) return PasteOutcome.Failed("붙여 넣을 글이 없습니다.")

        // 손잡이는 다섯 가지로 막는다. 다섯 다 실제로 일어난다.
        //
        // 이것들은 **창을 숨기기 전에** 걸러진다. 그래서 창이 그대로 떠 있고, 사용자는
        // "클립보드에 있습니다" 를 눈으로 본다. 숨겨 놓고 말하면 아무도 못 본다.
        if (to == 0L) return leaveOnClipboard(text, opt, PasteOutcome.Reason.NO_PREVIOUS_WINDOW, windowHidden = false)
        if (isOurs(to)) return leaveOnClipboard(text, opt, PasteOutcome.Reason.OUR_OWN_WINDOW, windowHidden = false)
        if (!windows.isWindow(to)) return leaveOnClipboard(text, opt, PasteOutcome.Reason.WINDOW_GONE, windowHidden = false)
        // 숨겨진 창은 앞으로 부를 수가 없다. 400ms 를 헛되이 기다리느니 바로 말한다.
        if (!windows.isVisible(to)) return leaveOnClipboard(text, opt, PasteOutcome.Reason.WINDOW_GONE, windowHidden = false)
        if (windows.classOf(to) in SHELL_CLASSES) {
            return leaveOnClipboard(text, opt, PasteOutcome.Reason.SHELL_WINDOW, windowHidden = false)
        }

        // 되돌릴 생각이 없으면 읽지도 않는다. 클립보드는 읽기도 남의 프로그램과 다투는 일이다.
        val previous = if (opt.restoreClipboard) runCatching { clipboard.get() }.getOrNull() else null

        if (!writeWithRetry(text, opt)) {
            return PasteOutcome.Failed("클립보드에 넣지 못했습니다. 잠시 뒤에 다시 해 보세요.")
        }

        // 시계는 **숨기라고 말하기 전에** 건다. 숨긴 뒤에 걸면 거짓말이 된다 —
        // `invokeAndWait` 한 번이 4~8ms 인데 앞 창은 그 안에 이미 돌아와 있어서,
        // 나중에 걸면 늘 0.0x ms 가 찍힌다(실제로 그렇게 찍혔다). 그 숫자를 믿고
        // "고정 sleep 0ms 면 충분하다" 고 결론 내리는 것이 바로 함정이다.
        val began = System.nanoTime()

        // **숨기는 것은 EDT 에서.** 일꾼 실에서 ShowWindow(SW_HIDE) 로 숨기면 창이
        // 앞에 그대로 남아 30ms 가 지나도 자판이 허공으로 간다(420번 재서 전부 실패).
        //
        // 못 숨겼으면 **거기서 멈춘다.** 창이 앞에 남은 채로 기다려 봐야 앞 창은 영영
        // 안 돌아오고, 그 사이에 사용자가 뭘 하든 자판이 엉뚱한 데로 갈 빌미만 생긴다.
        if (hider.runCatching { hide() }.isFailure) {
            return PasteOutcome.OnClipboard(PasteOutcome.Reason.HIDE_FAILED, windowHidden = false)
        }

        // 고정으로 기다리는 설정. 기본은 꺼져 있다 — 이 기계에서 앞 창은 5ms 면 돌아오고,
        // 그보다 오래 자는 것은 사람이 느끼는 지연일 뿐이다. 켜도 **이것만 믿지는 않는다.**
        // 자고 난 뒤에도 아래 조건 확인을 똑같이 거친다. 그래서 이 설정은 안전을 못 깎는다.
        if (opt.useFixedDelay) sleep(opt.fixedDelayMs)

        if (!waitForForeground(to, opt.waitForFocusMs)) {
            // 바로 직전까지 우리가 앞 창이었으므로 이 호출에는 권한이 있다(100/100 TRUE).
            // 다만 **돌아온 값은 믿지 않는다** — 확인은 GetForegroundWindow 로 다시 한다.
            windows.setForeground(to)
            if (!waitForForeground(to, opt.waitForFocusMs)) {
                return PasteOutcome.OnClipboard(PasteOutcome.Reason.FOCUS_NEVER_RETURNED, windowHidden = true)
            }
        }
        val waited = (System.nanoTime() - began) / 1e6

        if (opt.settleMs > 0) sleep(opt.settleMs)

        // 자판을 보내기 **직전에** 한 번 더 본다. 1.2us 짜리 확인으로 남의 문서를 지킨다.
        if (windows.foreground() != to) {
            return PasteOutcome.OnClipboard(PasteOutcome.Reason.FOCUS_LOST, windowHidden = true)
        }

        keys.ctrlV(opt.holdMs)

        // **붙인 뒤에만** 되돌린다. 위의 실패 경로들은 전부 여기 못 오는데, 그게 핵심이다 —
        // 못 붙였다면 고친 글은 사용자가 손으로 Ctrl+V 할 유일한 사본이다. 그것을 옛
        // 클립보드로 덮으면 고친 글이 아예 사라진다.
        if (previous != null) scheduleRestore(previous, ours = text, opt = opt)
        return PasteOutcome.Pasted(focusWaitMs = waited, restoreScheduled = previous != null)
    }

    /**
     * EDT 에서 불러도 되는 입구. 일꾼 실에서 돌리고 결과만 EDT 로 돌려준다.
     *
     * Enter 처리에서 이것을 써라. [pasteBack] 은 실패 경로에서 800ms 까지 잡고 있을 수
     * 있는데, 그동안 화면이 굳으면 사용자는 프로그램이 죽은 줄 안다.
     */
    fun pasteBackAsync(text: String, to: Long = target, onDone: (PasteOutcome) -> Unit) {
        worker.execute {
            val outcome = runCatching { pasteBack(text, to) }
                .getOrElse { PasteOutcome.Failed("돌려 붙이기가 실패했습니다: ${it.message}") }
            SwingUtilities.invokeLater { onDone(outcome) }
        }
    }

    /** 프로그램을 끝낼 때. 일꾼 실은 daemon 이라 안 불러도 JVM 을 붙잡지는 않는다. */
    fun close() {
        worker.shutdown()
    }

    // ---- 속 ----

    /**
     * 우리 창인가. 손잡이 하나만 보지 않고 **프로세스까지** 본다. 설정 창이나 트레이
     * 풍선처럼 우리가 나중에 더 만들 창까지 한꺼번에 걸러 주기 때문이다.
     */
    private fun isOurs(hwnd: Long): Boolean {
        if (hwnd == 0L) return false
        if (hwnd == ownWindow) return true
        val pid = windows.processIdOf(hwnd)
        return pid != 0L && pid == ourPid
    }

    private fun leaveOnClipboard(
        text: String,
        opt: PasteSettings,
        reason: PasteOutcome.Reason,
        windowHidden: Boolean,
    ): PasteOutcome {
        if (!writeWithRetry(text, opt)) {
            return PasteOutcome.Failed("클립보드에 넣지 못했습니다. 잠시 뒤에 다시 해 보세요.")
        }
        return PasteOutcome.OnClipboard(reason, windowHidden)
    }

    /**
     * 클립보드는 한 번에 한 프로그램만 연다. `setContents` 는 정말로 던진다 —
     * 여기서 600번에 2번 꼴로 "cannot open system clipboard" 가 났고, 안 받았더니
     * 측정 한 판이 통째로 죽었다. 10ms 뒤 한 번만 다시 걸면 전부 살아났다.
     */
    private fun writeWithRetry(text: String, opt: PasteSettings): Boolean {
        repeat(opt.clipboardTries) { attempt ->
            try {
                clipboard.set(text)
                return true
            } catch (_: IllegalStateException) {
                if (attempt < opt.clipboardTries - 1) sleep(10)
            }
        }
        return false
    }

    /**
     * 사용자의 원래 클립보드를 되돌린다. **기본은 끄는 쪽이다.**
     *
     * 너무 일찍 되돌리면 붙여넣기가 실패하는 게 아니라 **옛 클립보드가 대신 붙는다.**
     * 재 봤다 — V 를 누른 뒤 0/1/2/5/10ms 에 되돌리니 125번 중 118번이 옛 글이었다.
     * 사용자 문서에 남의 글이 조용히 박히는 것이다. 상대가 클립보드를 읽은 때는 누른 뒤
     * 21~25ms 였고, 워드나 크롬 같은 무거운 프로그램은 **더 늦게** 읽는다. 그래서 필요한
     * 여유는 잴수록 커지지 작아지지 않는다. 안전이 증명되는 시간 같은 것은 없다.
     *
     * 그래도 켜는 사람이 있을 테니 두 겹으로 막는다 — 500ms 를 기다리고,
     * **그때까지 클립보드에 우리 글이 그대로 있을 때만** 되돌린다. 그 사이 사용자가
     * 다른 것을 복사했다면 그쪽이 임자다.
     *
     * 덧붙여 우리가 되돌릴 수 있는 것은 **글뿐이다.** 그림이나 파일이 들어 있었다면
     * 어차피 날아간다. 기본값이 꺼짐인 또 하나의 까닭이다.
     */
    private fun scheduleRestore(previous: String, ours: String, opt: PasteSettings) {
        Thread({
            sleep(opt.restoreDelayMs)
            val now = runCatching { clipboard.get() }.getOrNull()
            if (shouldRestore(now, ours)) writeWithRetry(previous, opt)
        }, "clipboard-restore").apply { isDaemon = true }.start()
    }

    /**
     * `GetForegroundWindow` 가 [want] 를 돌려줄 때까지 기다린다.
     *
     * **고정 sleep 을 쓰지 마라.** 한 번 부르는 값이 1.2us 라, 바쁘게 도는 쪽이 5ms 만에
     * 끝나고 정확하다. 길어지면 자러 간다 — 앞 창이 영영 안 돌아오는 경우까지 400ms 를
     * 바쁘게 돌 이유는 없다.
     */
    private fun waitForForeground(want: Long, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var spins = 0
        while (true) {
            if (windows.foreground() == want) return true
            if (System.nanoTime() >= deadline) return false
            if (spins++ < SPIN_LIMIT) Thread.onSpinWait() else sleep(1)
        }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 실 하나짜리다. Enter 를 연달아 눌러도 돌려 붙이기가 **겹쳐 돌면 안 된다** —
     * 두 벌이 클립보드를 번갈아 덮으면 어느 글이 박힐지 아무도 모른다.
     */
    private val worker = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread(r, "paste-back").apply { isDaemon = true }
    })

    private companion object {
        /** 이만큼 바쁘게 돌면 보통 30ms 쯤 된다. 그 뒤로는 1ms 씩 잔다. */
        const val SPIN_LIMIT = 20_000

        val ourPid: Long = runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)
    }
}

/**
 * 되돌릴지 말지. 순수 함수로 떼어 둔 까닭은 이 판단이 **사용자 문서를 지키는 마지막
 * 문턱**이라 화면 없이 시험해야 하기 때문이다.
 */
fun shouldRestore(current: String?, ours: String): Boolean = current != null && current == ours

// ---------------------------------------------------------------- 결과

/**
 * 돌려 붙이기 결과. 실패해도 **고친 글은 언제나 클립보드에 남아 있다** — 사용자가
 * 손으로라도 붙일 수 있게. [PasteOutcome.Failed] 만 그것마저 안 된 경우다.
 */
sealed class PasteOutcome {

    /** 우리 창을 숨겼는가. 숨겼다면 할 말이 있을 때 **다시 띄워야** 사용자가 본다. */
    abstract val windowHidden: Boolean

    /** 사용자에게 보여 줄 말. 성공이면 비어 있다. */
    abstract val message: String

    /**
     * @param focusWaitMs **숨기라고 말한 순간부터** 앞 창이 돌아온 것을 확인할 때까지.
     *   `invokeAndWait` 값이 여기 들어 있다 — 그게 맞다. 사용자가 Enter 를 누르고 글이
     *   박히기까지 실제로 흐른 시간이 이것이고, 설정 창이 "이 기계는 얼마나 걸리나" 를
     *   보여 줄 때 쓸 숫자도 이것이다. 이 기계에서 4~9ms 였다.
     */
    data class Pasted(val focusWaitMs: Double, val restoreScheduled: Boolean = false) : PasteOutcome() {
        override val windowHidden: Boolean get() = true
        override val message: String get() = ""
    }

    data class OnClipboard(val reason: Reason, override val windowHidden: Boolean) : PasteOutcome() {
        override val message: String get() = reason.message
    }

    data class Failed(override val message: String) : PasteOutcome() {
        override val windowHidden: Boolean get() = false
    }

    enum class Reason(val message: String) {
        NO_PREVIOUS_WINDOW("돌아갈 창을 기억하지 못했습니다. 고친 글은 클립보드에 있습니다."),
        OUR_OWN_WINDOW("바로 앞에 있던 것이 이 창이라 돌려보낼 곳이 없습니다. 고친 글은 클립보드에 있습니다."),
        WINDOW_GONE("돌아갈 창이 그새 닫혔습니다. 고친 글은 클립보드에 있습니다."),
        SHELL_WINDOW("돌아갈 곳이 작업 표시줄이라 붙일 수 없습니다. 고친 글은 클립보드에 있습니다."),
        HIDE_FAILED("이 창을 숨기지 못했습니다. 고친 글은 클립보드에 있으니 Ctrl+V 로 붙여 넣으세요."),
        FOCUS_NEVER_RETURNED("앞 창이 돌아오지 않았습니다. 고친 글은 클립보드에 있으니 Ctrl+V 로 붙여 넣으세요."),
        FOCUS_LOST("붙여 넣기 직전에 다른 창이 앞으로 나섰습니다. 고친 글은 클립보드에 있으니 Ctrl+V 로 붙여 넣으세요."),
    }
}

// ---------------------------------------------------------------- 설정

/**
 * 설계서 3-4 는 "50~100ms 기다린다, 기계마다 다르니 설정으로 둬라" 고 했다.
 * 재 보니 **고정 시간은 애초에 틀린 연장**이었다 — 짧으면 자판이 사라지고, 길면 그냥
 * 느려질 뿐인데, 앞 창이 돌아온 순간은 1.2us 짜리 호출로 정확히 알 수 있다.
 *
 * 그래서 기본은 조건 기다림이고, [useFixedDelay] 는 남겨 둔 뒷문이다. 켜도 잔 뒤에
 * 조건 확인을 똑같이 거치므로 안전이 깎이지 않는다.
 */
data class PasteSettings(
    /** 앞 창이 돌아오기를 이만큼까지 기다린다. 이 기계에서 실제로 걸린 최대값이 8.3ms 다. */
    val waitForFocusMs: Long = 400,
    /** 조건 기다림 앞에 고정으로 자는 뒷문. 기본 꺼짐. */
    val useFixedDelay: Boolean = false,
    val fixedDelayMs: Long = 60,
    /** 앞 창이 돌아온 뒤 한 숨. 0 으로도 100/100 이었지만 느린 기계를 위해 남겨 둔다. */
    val settleMs: Long = 0,
    /** Ctrl+V 를 누르고 있는 시간. 상대가 클립보드를 읽는 데 21~25ms 가 걸린다. */
    val holdMs: Int = 20,
    /** 원래 클립보드를 되돌릴까. **기본은 꺼짐** — 까닭은 [PasteBack] 안의 긴 주석. */
    val restoreClipboard: Boolean = false,
    val restoreDelayMs: Long = 500,
    /** 클립보드 쓰기를 몇 번까지 다시 걸까. 여기서 실패율은 0.3% 쯤이었다. */
    val clipboardTries: Int = 10,
) {

    /**
     * 설정 창에서 어떤 값이 들어와도 부품이 위험해지지 않게 자른다.
     * 특히 [restoreDelayMs] 의 아래끝 500ms 는 **양보하면 안 되는 값**이다 —
     * 그보다 짧으면 사용자 문서에 옛 클립보드가 박힌다.
     */
    fun sane(): PasteSettings = copy(
        waitForFocusMs = waitForFocusMs.coerceIn(50, 5_000),
        fixedDelayMs = fixedDelayMs.coerceIn(0, 2_000),
        settleMs = settleMs.coerceIn(0, 500),
        holdMs = holdMs.coerceIn(0, 200),
        restoreDelayMs = restoreDelayMs.coerceIn(500, 10_000),
        clipboardTries = clipboardTries.coerceIn(1, 50),
    )

    fun save(prefs: Preferences) {
        prefs.putLong(WAIT_KEY, waitForFocusMs)
        prefs.putBoolean(FIXED_KEY, useFixedDelay)
        prefs.putLong(DELAY_KEY, fixedDelayMs)
        prefs.putLong(SETTLE_KEY, settleMs)
        prefs.putBoolean(RESTORE_KEY, restoreClipboard)
        prefs.putLong(RESTORE_DELAY_KEY, restoreDelayMs)
    }

    companion object {
        const val WAIT_KEY = "pasteWaitMs"
        const val FIXED_KEY = "pasteUseFixedDelay"
        const val DELAY_KEY = "pasteDelayMs"
        const val SETTLE_KEY = "pasteSettleMs"
        const val RESTORE_KEY = "pasteRestoreClipboard"
        const val RESTORE_DELAY_KEY = "pasteRestoreDelayMs"

        fun load(prefs: Preferences): PasteSettings {
            val d = PasteSettings()
            return PasteSettings(
                waitForFocusMs = prefs.getLong(WAIT_KEY, d.waitForFocusMs),
                useFixedDelay = prefs.getBoolean(FIXED_KEY, d.useFixedDelay),
                fixedDelayMs = prefs.getLong(DELAY_KEY, d.fixedDelayMs),
                settleMs = prefs.getLong(SETTLE_KEY, d.settleMs),
                restoreClipboard = prefs.getBoolean(RESTORE_KEY, d.restoreClipboard),
                restoreDelayMs = prefs.getLong(RESTORE_DELAY_KEY, d.restoreDelayMs),
            ).sane()
        }
    }
}

// ---------------------------------------------------------------- 바깥과 닿는 자리

/**
 * 우리 창을 숨기는 방법. 부품이 창을 직접 알지 않게 갈라 놓았다 — 그래야 화면 없이
 * 시험할 수 있고, 창을 누가 만들든 상관이 없어진다.
 */
fun interface WindowHider {
    /** 돌아왔을 때는 창이 **이미 숨겨져 있어야** 한다. 미루면 그만큼 자판이 허공으로 간다. */
    fun hide()

    companion object {
        /**
         * Swing 창을 숨기는 보통의 방법.
         *
         * `isVisible = false` 다. `dispose()` 가 아니다 — 설계서대로 창은 한 번 만들고
         * 숨겼다 폈다 한다. 그리고 반드시 **EDT 에서** 숨긴다. 일꾼 실에서 Win32
         * `ShowWindow(SW_HIDE)` 로 숨기면 EDT 가 임자인 창은 앞에 그대로 남는다.
         */
        fun swing(window: Window): WindowHider = WindowHider {
            if (SwingUtilities.isEventDispatchThread()) window.isVisible = false
            else SwingUtilities.invokeAndWait { window.isVisible = false }
        }
    }
}

/** 창 임자. 시험에서 갈아 끼우려고 갈라 두었다. */
interface WindowSystem {
    fun foreground(): Long
    fun isWindow(hwnd: Long): Boolean

    /** 화면에 나와 있나. 최소화된 창도 참이다 — 참이 아니면 앞으로 부를 수가 없다. */
    fun isVisible(hwnd: Long): Boolean
    fun processIdOf(hwnd: Long): Long

    /** 창 클래스 이름. 작업 표시줄·바탕화면을 가려내는 데 쓴다. 못 읽으면 빈 글. */
    fun classOf(hwnd: Long): String
    fun setForeground(hwnd: Long): Boolean
}

/**
 * 껍데기(셸)의 창들. **여기에 글을 돌려보낼 일은 없다.**
 *
 * 트레이 아이콘으로 창을 부르면 그 순간 앞 창은 사용자가 글을 쓰던 곳이 아니라
 * **작업 표시줄**이다. 그것을 목표로 기억하면 Enter 를 눌렀을 때 자판이 작업 표시줄로
 * 날아간다. 더 나쁜 길도 있다 — 기억을 갱신하지 않으면 한참 전에 단축키로 잡아 둔
 * **엉뚱한 옛 창**에 글이 박힌다. 그래서 부를 때마다 기억을 갱신하되, 셸이면 "모른다"
 * 로 둔다. 모른다고 말하는 쪽은 사용자가 Ctrl+V 한 번 더 누르면 끝이지만, 잘못 박힌
 * 글은 사용자가 직접 지워야 한다.
 */
private val SHELL_CLASSES = setOf(
    "Shell_TrayWnd",        // 작업 표시줄
    "Shell_SecondaryTrayWnd",
    "Progman",              // 바탕화면
    "WorkerW",              // 바탕화면(배경 전환 중)
    "NotifyIconOverflowWindow",
    "TopLevelWindowForOverflowXamlIsland",
    "Windows.UI.Core.CoreWindow",  // 시작 메뉴·검색 같은 셸 UI
)

/**
 * user32 는 필요한 여섯 개만 직접 선언한다.
 *
 * `GetClassName` 만 A/W 짝이 있어 **반드시 `GetClassNameW`** 라고 적는다. 나머지는
 * 짝이 없어 이 이름 그대로 링크된다. (`GetMessage` 처럼 바깥에 W 만 있는 이름을 맨이름으로
 * 적으면 `Native.load` 는 통과하고 **첫 호출에서** 터진다 — 그런 것은 이 파일에 없다.)
 */
private interface User32Min : StdCallLibrary {
    fun GetForegroundWindow(): Pointer?
    fun SetForegroundWindow(hWnd: Pointer?): Boolean
    fun IsWindow(hWnd: Pointer?): Boolean
    fun IsWindowVisible(hWnd: Pointer?): Boolean
    fun GetWindowThreadProcessId(hWnd: Pointer?, lpdwProcessId: IntByReference?): Int
    fun GetClassNameW(hWnd: Pointer?, lpClassName: CharArray, nMaxCount: Int): Int
}

object Win32Windows : WindowSystem {

    private val u: User32Min by lazy { Native.load("user32", User32Min::class.java) }

    private fun p(hwnd: Long): Pointer? = if (hwnd == 0L) null else Pointer(hwnd)

    override fun foreground(): Long = u.GetForegroundWindow()?.let { Pointer.nativeValue(it) } ?: 0L

    /** 우리 창이 떠 있는 동안 상대가 창을 닫을 수 있다. 쓰기 전에 살아 있는지 본다. */
    override fun isWindow(hwnd: Long): Boolean = hwnd != 0L && u.IsWindow(p(hwnd))

    override fun isVisible(hwnd: Long): Boolean = hwnd != 0L && u.IsWindowVisible(p(hwnd))

    override fun processIdOf(hwnd: Long): Long {
        if (hwnd == 0L) return 0L
        val out = IntByReference()
        u.GetWindowThreadProcessId(p(hwnd), out)
        return out.value.toLong() and 0xFFFFFFFFL
    }

    /** 클래스 이름은 256자를 넘지 않는다(RegisterClass 의 한계). 넉넉히 잡아 둔다. */
    override fun classOf(hwnd: Long): String {
        if (hwnd == 0L) return ""
        val buf = CharArray(512)
        val n = runCatching { u.GetClassNameW(p(hwnd), buf, buf.size) }.getOrDefault(0)
        return if (n <= 0) "" else String(buf, 0, n)
    }

    /**
     * 되돌아오지 않을 때만 쓰는 뒷받침. **주된 길로 삼으면 안 된다** — 한 번도 앞에
     * 나서 본 적 없는 프로세스에서는 false 를 돌려주고 아무 일도 안 일어난다.
     * 우리는 바로 직전까지 앞 창이었기 때문에 이 자리에서만 권한이 있다.
     */
    override fun setForeground(hwnd: Long): Boolean = hwnd != 0L && u.SetForegroundWindow(p(hwnd))
}

/**
 * 우리 창의 손잡이. **창을 띄운 직후 EDT 에서 한 번만 불러 값을 들고 있어라.**
 * 나중에 일꾼 실에서 부르면 프로세스가 통째로 굳는 것을 봤다(명령 고리가 멎고 다시는
 * 안 돌아왔다). 한 번 구해 둔 값은 창이 살아 있는 동안 변하지 않는다.
 */
fun ownWindowHandle(window: Window): Long {
    check(SwingUtilities.isEventDispatchThread()) {
        "ownWindowHandle 은 창을 띄운 직후 EDT 에서 한 번만 불러라."
    }
    return runCatching { Pointer.nativeValue(Native.getWindowPointer(window)) }.getOrDefault(0L)
}

/** 클립보드. `set` 은 **던질 수 있다** — 삼키지 말고 올려라, 다시 걸어야 하니까. */
interface ClipboardPort {
    fun set(text: String)
    fun get(): String?
}

object AwtClipboard : ClipboardPort {
    private val board get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun set(text: String) = board.setContents(StringSelection(text), null)

    /** 글이 아닌 것(그림·파일)이 들어 있으면 null 이다. 그런 것은 되돌릴 수 없다. */
    override fun get(): String? = runCatching { board.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
}

interface KeyPort {
    fun ctrlV(holdMs: Int)
}

object RobotKeys : KeyPort {

    // 화면 없는 곳에서는 Robot 을 못 만든다. 그래서 진짜 쓸 때 만든다.
    private val robot: Robot by lazy { Robot().apply { isAutoWaitForIdle = false } }

    /**
     * 누르고 [holdMs] 만큼 잡았다가 뗀다. 잡고 있는 까닭은 상대가 클립보드를 읽는 데
     * 21~25ms 가 걸리기 때문이다 — 그 전에 떼도 대개 되지만, 잡아 두면 느린 상대에게
     * 시간을 벌어 준다. 20ms 는 윈도우의 자동 반복(250ms)에 한참 못 미쳐 안전하다.
     */
    override fun ctrlV(holdMs: Int) {
        robot.keyPress(KeyEvent.VK_CONTROL)
        robot.keyPress(KeyEvent.VK_V)
        if (holdMs > 0) robot.delay(holdMs)
        robot.keyRelease(KeyEvent.VK_V)
        robot.keyRelease(KeyEvent.VK_CONTROL)
    }
}
