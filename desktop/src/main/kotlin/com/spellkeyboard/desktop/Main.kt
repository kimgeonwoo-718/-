package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.Correction
import com.spellkeyboard.desktop.floating.FloatingWindow
import com.spellkeyboard.desktop.floating.PreferenceSettings
import com.spellkeyboard.desktop.hotkey.GlobalHotkeyManager
import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.HotkeyResult
import com.spellkeyboard.desktop.paste.PasteBack
import com.spellkeyboard.desktop.paste.PasteOutcome
import com.spellkeyboard.desktop.paste.PasteSettings
import com.spellkeyboard.desktop.paste.WindowHider
import com.spellkeyboard.desktop.paste.ownWindowHandle
import com.spellkeyboard.desktop.shell.DesktopSettings
import com.spellkeyboard.desktop.shell.SettingsDialog
import com.spellkeyboard.desktop.shell.SettingsStore
import com.spellkeyboard.desktop.shell.SingleInstance
import com.spellkeyboard.desktop.shell.SingleInstanceResult
import com.spellkeyboard.desktop.shell.TrayController
import com.spellkeyboard.desktop.shell.TrayResult
import com.spellkeyboard.desktop.shell.Win32HotkeyProbe
import com.spellkeyboard.desktop.shell.installHideOnClose
import com.spellkeyboard.desktop.shell.onEdt
import kotlin.system.exitProcess
import com.spellkeyboard.desktop.live.LiveCorrector
import com.spellkeyboard.desktop.live.caretFollowingComposition
import com.spellkeyboard.desktop.live.changedSpan
import com.spellkeyboard.desktop.live.Report
import com.spellkeyboard.desktop.live.spellEngineTail
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * 떠 있는 교정창을 세운다.
 *
 * ## 차례가 왜 이런가
 *
 * 1. **한 벌만 돈다**([SingleInstance]). 전역 단축키는 먼저 잡는 쪽이 임자라, 두 벌이
 *    뜨면 둘째는 1409 를 받고 아무 일도 못 하면서 알림 영역에만 남는다. 그래서 단축키를
 *    잡기 **전에** 자리를 다툰다. 둘째는 첫째의 창을 띄워 주고 조용히 나간다.
 * 2. 창을 **만들고**([CorrectorWindow.build]) 떠 있는 창 노릇을 붙인다([FloatingWindow.install]).
 *    창은 여기서 딱 한 번 만들어지고 그 뒤로는 보이고 숨을 뿐이다 — 사전이 올라간 엔진을
 *    다시 부수지 않으려는 것이다.
 * 3. 그다음에 단축키를 잡는다. 실패하면 **반드시 화면에 적는다.** 설계서가 고른
 *    Ctrl+Alt+Space 는 이 기계에서 이미 남이 쓰고 있었다.
 * 4. 알림 영역을 붙인다. 못 붙이면 그 사실과 "닫으면 진짜 끝난다" 를 같이 말한다.
 */
fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val store = SettingsStore()

    // 둘째 벌이 첫째에게 "창 띄워라" 를 보내면 소켓 실에서 이 상자가 불린다.
    // 창은 아직 없으므로 상자에 담아 두고, 다 세운 뒤에 채운다.
    val summon = java.util.concurrent.atomic.AtomicReference<() -> Unit>({})
    val single = SingleInstance.acquire { onEdt { summon.get().invoke() } }
    if (single is SingleInstanceResult.AlreadyRunning) {
        if (!single.notifiedFirstInstance) {
            // 첫째에게 말을 못 걸었다. 그래도 둘이 뜨는 것보다는 낫다.
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "맞춤법 교정기가 이미 실행 중입니다.\n알림 영역 아이콘을 누르거나 단축키로 부르세요.",
                "맞춤법 교정기",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
        }
        exitProcess(0)
    }

    SwingUtilities.invokeLater { DesktopApp(store, single).start(summon) }
}

/**
 * 부품 넷(단축키·떠 있는 창·돌려 붙이기·알림 영역)을 하나로 묶는 자리.
 *
 * 부품끼리는 서로를 모른다. 아는 것은 여기뿐이라, 무엇이 무엇을 부르는지 보려면
 * 이 한 클래스만 읽으면 된다.
 */
private class DesktopApp(
    private val store: SettingsStore,
    private val single: SingleInstanceResult,
) {
    private val window = CorrectorWindow(SpellEngine(), store)
    private val hotkeys = GlobalHotkeyManager(
        // 단축키가 눌린 **그 순간**, 우리 창을 띄우기 전에 앞 창을 갈무리한다.
        // 이 실에서 할 수 있는 유일하게 옳은 일이다 — 창이 뜨고 나면
        // GetForegroundWindow 는 우리 창을 돌려준다.
        onHotkeyThread = { paste.remember() },
        onError = { it.printStackTrace() },
    ) { float.toggle() }

    private val paste = PasteBack(
        hider = WindowHider.swing(window.frame),
        // 설정의 "붙여넣기 여유" 는 **앞 창이 돌아온 것을 확인한 뒤** 더 주는 시간이다
        // (settleMs). 설계서의 고정 대기(§3-4)를 그대로 쓰지 않는 까닭은 재 봤기
        // 때문이다: 앞 창은 3.8~8.3ms 에 돌아오고, 고정 대기만 믿으면 느린 기계에서
        // 자판이 허공으로 간다. 지켜보기가 본길이고 이 값은 덤이다.
        settings = { PasteSettings(settleMs = store.get().pasteExtraDelayMs.toLong()) },
    )

    private lateinit var float: FloatingWindow
    private var tray: TrayResult? = null
    private val probe = Win32HotkeyProbe { hotkeys.current }

    fun start(summon: java.util.concurrent.atomic.AtomicReference<() -> Unit>) {
        window.build()

        float = FloatingWindow.install(
            frame = window.frame,
            editor = window.input,
            settings = PreferenceSettings(SettingsStore.defaultNode()),
            // Enter. **일꾼 실에서** 불리고 창은 이미 숨어 있다.
            onApply = { text -> applyAndPaste(text) },
            onCorrectAll = { window.correctNow() },
            onQuit = { window.shutdown() },
        )
        // **여기서도 remember() 를 불러야 한다.** 창을 부르는 길은 셋인데(단축키·알림 영역·
        // 두 번째 실행) 예전에는 앞의 둘만 앞 창을 갈무리했다. 두 번째 실행으로 부르면
        // 기억해 둔 창이 없거나 한참 전 것이라, 치고 Enter 를 눌러도 글이 아무 데도
        // 안 들어갔다. 부르는 길이 늘면 이것도 같이 늘어야 한다.
        summon.set {
            paste.remember()
            float.summon()
        }

        // 창은 이미 실체화돼 있다(install 이 pack 했다). 손잡이는 여기서 한 번만 읽는다 —
        // 나중에 일꾼 실에서 읽으면 프로세스가 통째로 멎는 일이 있었다.
        paste.ownWindow = ownWindowHandle(window.frame)

        // **알림 영역을 먼저 붙인다.** 단축키 등록이 실패하면 풍선말로도 알리는데,
        // 순서가 반대면 그때 tray 가 아직 null 이라 그 길이 통째로 죽는다. 실제로 그랬다.
        installTray()
        startHotkey()

        // 설정이 바뀌면 여기서 갈라 나간다. 붙이는 즉시 지금 값으로 한 번 불린다.
        store.addListener { s -> onSettings(s) }

        // 처음 켠 사람에게는 창을 보여 준다. 알림 영역에만 뜨면 무엇이 켜졌는지 모른다.
        float.summon()
    }

    // ---- Enter ----

    private fun applyAndPaste(text: String) {
        // 여기서 던지면 [FloatingWindow] 가 삼킨다(일꾼 실이 죽지 않게 감싸 놓았다).
        // 삼켜지면 창은 숨고 글은 어디에도 안 남는다 — 그것만은 안 된다.
        val outcome = runCatching { paste.pasteBack(text) }
            .getOrElse { PasteOutcome.Failed("돌려 붙이지 못했습니다: ${it.message}") }
        if (outcome is PasteOutcome.Pasted) return
        // 못 붙였으면 고친 글은 클립보드에 있다. 그 사실을 **보여 줘야** 한다 —
        // 숨은 창에 적어 두면 아무도 못 읽는다.
        //
        // 글도 글칸에 도로 넣는다. Enter 를 누른 자리에서 글칸을 비우는데, 못 넣었는데
        // 비우기까지 하면 사용자가 쓴 글이 화면에서 사라진다. 클립보드에 있다고 해서
        // 없어져도 되는 것은 아니다.
        SwingUtilities.invokeLater {
            float.summon()
            window.input.text = text
            window.status(outcome.message)
        }
    }

    // ---- 단축키 ----

    /**
     * 마지막으로 **등록해 본** 조합. 쥐고 있는 조합([GlobalHotkeyManager.current])과 다르다 —
     * 실패하면 쥔 것은 없지만 해 보기는 한 것이다. 이것을 안 적어 두면, 등록에 실패한 직후
     * 설정 듣는 이가 "쥔 것이 없네" 하고 같은 조합을 다시 걸어 **같은 실패를 두 번** 알린다.
     */
    private var attempted: HotkeyCombo? = null

    /**
     * **[GlobalHotkeyManager.startAsync] 를 쓴다.** 여기는 EDT 다. 짝인 `start()` 는
     * 빗장을 최대 5초 기다리므로 그 길로 빠지면 화면이 그대로 언다. 그 클래스가 스스로
     * "EDT 에서 부르지 마라" 고 막대그림을 찍는 호출이기도 하다 — 띄울 때마다 stderr 에
     * 1,451자짜리 자취가 남아 진짜 오류를 덮고 있었다.
     */
    private fun startHotkey() {
        val combo = store.get().hotkey
        attempted = combo
        hotkeys.startAsync(combo) { result -> if (!result.ok) reportHotkey(result) }
    }

    /**
     * 단축키를 못 잡았다는 말은 **절대 삼키지 않는다.** 삼키면 프로그램이 멀쩡히 떠
     * 있는데 단축키만 안 먹는 상태가 되고, 사용자는 까닭을 알 길이 없다.
     */
    private fun reportHotkey(result: HotkeyResult) {
        val failed = result as? HotkeyResult.Failed ?: return
        val message = "${failed.message} (${failed.combo.display()}) — 설정에서 다른 조합을 고르세요."
        onEdt {
            float.summon()
            // status() 가 아니라 stickyWarning() 이다. 사전이 다 올라오면서 상태 한 줄을
            // "준비됐습니다." 로 덮어 버려, 단축키가 죽은 줄 모르게 하던 자리다.
            window.stickyWarning(message)
            (tray as? TrayResult.Installed)?.controller?.notify("단축키를 못 잡았습니다", message, error = true)
        }
    }

    // ---- 알림 영역 ----

    private fun installTray() {
        val result = TrayController.install(
            store = store,
            onOpen = {
                // 알림 영역으로 부를 때도 반드시 다시 기억한다. 안 부르면 한참 전
                // 단축키 때 잡아 둔 **옛 창**을 그대로 겨눈 채로 남는다. 작업 표시줄이
                // 앞 창이면 remember 가 0 으로 지워 준다.
                paste.remember()
                float.summon()
            },
            onSettings = { SettingsDialog.open(window.frame, store, probe) },
            onQuit = { quit() },
        )
        tray = result
        if (result is TrayResult.Unavailable) {
            // 알림 영역이 없으면 `종료` 차림표가 없다. 그때만 닫기가 진짜 종료다.
            installHideOnClose(window.frame, hasTray = false, onQuit = { quit() })
            onEdt { window.status(result.message.replace("\n", " ")) }
        }
    }

    // ---- 설정이 바뀌면 ----

    private fun onSettings(s: DesktopSettings) {
        float.setHideOnFocusLoss(s.hideOnFocusLoss)
        // 같은 조합이면 건드리지 않는다. 다시 등록하면 그 찰나에 단축키가 비고,
        // 무엇보다 change() 는 실패할 수 있는 일이다.
        if (attempted == s.hotkey) return

        attempted = s.hotkey
        // **안 돌고 있으면 다시 띄운다.** 처음 등록이 실패하면 실이 안 뜨는데, 예전에는
        // isRunning 일 때만 갈아 끼워서 그 경우 설정에서 다른 조합을 골라도 아무 일도
        // 안 났다 — 실패 메시지가 "설정에서 다른 조합을 고르세요" 라고 안내하는 바로
        // 그 길이 막혀 있었다.
        val apply: ((HotkeyResult) -> Unit) -> Unit =
            if (hotkeys.isRunning) { cb -> hotkeys.changeAsync(s.hotkey, cb) }
            else { cb -> hotkeys.startAsync(s.hotkey, cb) }

        apply { r ->
            if (!r.ok) reportHotkey(r)
            // 잡았으면 걸려 있던 경고를 걷는다. 안 걷으면 고친 뒤에도 "못 잡았습니다" 가
            // 상태줄에 남아 사용자가 아직 안 된 줄 안다.
            else onEdt { window.stickyWarning(null); window.status("단축키를 ${s.hotkey.display()} 로 바꿨습니다.") }
        }
    }

    // ---- 끝내기 ----

    /**
     * 끄는 길은 이것 하나다(알림 영역의 `종료`, 또는 알림 영역이 아예 없을 때의 닫기).
     *
     * **종료 갈고리에서 부르지 마라.** 갈고리에서 `dispose()` 로 들어가면 한국어 IME 가
     * 걸린 EDT 를 `invokeAndWait` 으로 기다리다 프로세스가 영영 안 죽는다. 그렇게 멎은
     * JVM 을 이 기계에서 넷 봤다.
     */
    private fun quit() = onEdt {
        (tray as? TrayResult.Installed)?.controller?.remove()
        hotkeys.stop()          // 단축키를 놓는다. 이것을 빼먹으면 조합이 새어 나간다.
        runCatching { probe.close() }
        paste.close()
        (single as? SingleInstanceResult.First)?.instance?.close()
        float.shutdown()        // 숨기기 → window.shutdown() → dispose, 전부 EDT 에서
        exitProcess(0)
    }
}

/**
 * 한 칸짜리 교정 창.
 *
 * ## 왜 칸이 하나인가
 *
 * 실시간 교정이 들어오면서 **입력칸이 곧 고친 글**이 됐다. 따로 두던 `고친 글` 칸은
 * 같은 글을 두 번 보여 줄 뿐이라 없앴고, 그 자리를 입력칸이 가져갔다. 창이 560x540
 * 에서 336x324 로 줄었는데도 글 쓰는 칸은 오히려 넓어진 까닭이 이것이다.
 *
 * 이 창은 5단계에서 단축키로 불려 나오는 떠 있는 입력창이 된다. 그래서 세로로 쌓고
 * (가로로 벌리면 좁은 창에서 먼저 깨진다), 단추는 넉 자를 넘기지 않으며, 자세한 것은
 * 접어 두었다가 필요할 때만 편다.
 */
class CorrectorWindow(
    private val engine: SpellEngine,
    /**
     * 설정 하나에서 갈라 나온다. 실시간 교정 스위치가 세 군데(이 창 네모칸, 알림 영역
     * 차림표, 설정 창)에 있어서 각자 [Preferences] 를 만지면 반드시 어긋난다.
     */
    private val settings: SettingsStore = SettingsStore(),
) {

    /** [FloatingWindow] 가 붙을 창. 이 클래스는 창을 띄우지도 숨기지도 않는다. */
    val frame = JFrame("맞춤법 교정기")

    /** 원문이자 고친 글. 실시간 교정이 **이 칸을 제자리에서** 고친다. */
    val input = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        font = koreanFont(14)
        border = EmptyBorder(6, 7, 6, 7)

        // 조합 중에 커서가 글자 앞에 머무는 것을 바로잡는다. 실시간 교정과 무관한
        // Swing·IME 문제라 교정을 꺼도 걸려 있어야 한다. 까닭은 [caretFollowingComposition].
        addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                // 이 자리에서 옮기면 IME 가 아직 문서를 쓰는 중이다. 다음 차례로 미룬다.
                SwingUtilities.invokeLater {
                    val committed = inputMethodRequests?.committedTextLength ?: return@invokeLater
                    val to = caretFollowingComposition(document.length, committed, caretPosition)
                        ?: return@invokeLater
                    runCatching { caretPosition = to }
                }
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        })
    }

    private val live: LiveCorrector = LiveCorrector.attach(input, spellEngineTail(engine))

    /**
     * 실시간 교정 스위치. **끈 것은 다음에 열어도 꺼져 있어야 한다** —
     * 끄는 사람은 그러려고 끈 것이다. [Preferences] 에 적어 둔다.
     */
    private val liveToggle = JCheckBox("실시간 교정").apply {
        font = smallFont()
        isFocusable = false
        toolTipText = "치는 대로 어절이 끝날 때마다 커서 앞 세 어절을 고칩니다"
    }

    /**
     * 사전이 어디까지 올라왔는지. 상태 메시지와 따로 둔다 — "문맥까지" 가 아니면
     * 성능이 떨어진 채로 쓰고 있다는 뜻이라 교정 결과에 덮이면 안 된다.
     */
    private val level = JLabel("").apply {
        font = smallFont()
        foreground = MUTED
    }

    /** 맨 위 띠. 사전이 덜 올라오면 여기가 노래진다. */
    private val bar = JPanel(BorderLayout()).apply {
        border = EmptyBorder(4, 8, 4, 8)
    }

    /**
     * 한 줄짜리 상태. 누르면 [details] 가 펴진다.
     *
     * 좁은 창에서 자세한 내역까지 늘 펴 두면 글 쓰는 칸이 너무 얕아진다. 평소에는
     * 마지막 한 줄만 보이고(`되요 → 돼요`), 전체교정이나 점검처럼 여러 줄이 나올 때만 편다.
     */
    private val status = JLabel("사전 올리는 중…").apply {
        font = smallFont()
        border = EmptyBorder(3, 8, 3, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "눌러서 자세한 내역을 펴고 접습니다"
    }

    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = koreanFont(12)
        border = EmptyBorder(4, 6, 4, 6)
    }

    private val detailPane = JScrollPane(details).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, LINE)
        preferredSize = Dimension(0, 84)
        isVisible = false
    }

    private val correctButton = JButton("전체교정")

    /**
     * 창을 **만들기만** 한다. 띄우지 않는다.
     *
     * 예전에는 `open()` 이었고 끝에서 창을 띄웠다. 이제는 [FloatingWindow] 가 언제
     * 어디에 띄울지를 정하므로(설계서 §3-1: 한 번 만들고 보이고 숨긴다) 여기서 띄우면
     * 자리 잡기가 두 번 일어나 창이 한 번 깜빡인다. 닫기 단추도 마찬가지로
     * [FloatingWindow] 가 받는다 — 여기서 `dispose()` 하면 전역 단축키가 같이 죽는다.
     */
    fun build() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(topBar(), BorderLayout.NORTH)
        frame.contentPane.add(JScrollPane(input).apply { border = null }, BorderLayout.CENTER)
        frame.contentPane.add(bottom(), BorderLayout.SOUTH)

        bindCorrectShortcut()
        wireLive()

        frame.size = Dimension(WIDTH, HEIGHT)
        frame.minimumSize = Dimension(300, 260)

        engine.load { state -> showState(state) }
    }

    /** 끝낼 때 [FloatingWindow.shutdown] 이 창을 부수기 직전에 부른다. */
    fun shutdown() {
        live.detach()
        engine.close()
    }

    /** 바깥(단축키 등록 실패 따위)에서 상태 한 줄에 적을 자리. EDT 에서 불러라. */
    fun status(message: String) {
        say(message)
        addDetail(message)
    }

    /**
     * **지워지면 안 되는 경고.** 프로그램은 멀쩡해 보이는데 기능 하나가 죽어 있는 상태가
     * 여기 들어간다 — 단축키를 못 잡은 것이 그렇다.
     *
     * 왜 따로 두는가: 한 번 적고 마는 [status] 로는 사라진다. 단축키 등록은 시작하자마자
     * 끝나는데 사전은 1초쯤 뒤에 올라오고, 그때 [showState] 가 "준비됐습니다." 로 덮었다.
     * 실제로 그랬다 — 1,224ms 에 찍은 화면에 이미 덮여 있었다. 사용자는 멀쩡해 보이는 창을
     * 보면서 단축키만 안 먹는 까닭을 알 길이 없었다.
     *
     * 그래서 엔진이 다 올라와도 이 말이 이긴다. 준비됐다는 말보다 안 되는 것이 있다는
     * 말이 중요하다. null 을 주면 걷힌다.
     */
    fun stickyWarning(message: String?) {
        sticky = message
        if (message != null) {
            say(message)
            addDetail(message)
            warn(true)
        }
    }

    /** [stickyWarning] 이 적어 둔 말. 엔진 상태가 이것을 못 덮는다. */
    private var sticky: String? = null

    // ---- 화면 ----

    private fun topBar(): JComponent = bar.apply {
        add(liveToggle, BorderLayout.WEST)
        add(level, BorderLayout.EAST)
    }

    /**
     * 아래쪽 셋을 한 덩어리로 쌓는다. 위에서부터 상태 한 줄, 접힌 내역, 단추.
     *
     * 내역을 펴도 창은 그대로고 글 쓰는 칸이 그만큼 줄어든다. 창 크기가 제멋대로
     * 변하는 것보다 낫다 — 떠 있는 입력창이 되면 더욱 그렇다.
     */
    private fun bottom(): JComponent = JPanel(BorderLayout()).apply {
        val head = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, LINE)
            add(status, BorderLayout.CENTER)
        }
        status.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggleDetails()
        })
        add(head, BorderLayout.NORTH)
        add(detailPane, BorderLayout.CENTER)
        add(buttons(), BorderLayout.SOUTH)
    }

    /**
     * 단추 넉 줄. 336px 안에 들어가야 해서 이름을 넉 자 안으로 줄였고, 자주 안 누르는
     * [점검] 만 왼쪽에 떼어 놓았다.
     */
    private fun buttons(): JComponent {
        val check = JButton("점검").apply {
            font = smallFont()
            toolTipText = "사전이 제대로 붙었는지 엔진이 스스로 검사합니다"
            addActionListener { runSelfTest() }
        }
        val paste = JButton("붙여넣기").apply {
            font = smallFont()
            addActionListener { pasteIntoInput() }
        }
        correctButton.font = smallFont()
        correctButton.addActionListener { correctNow() }
        val copy = JButton("복사").apply {
            font = smallFont()
            addActionListener { copyText() }
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(paste)
            add(correctButton)
            add(copy)
        }
        return JPanel(BorderLayout()).apply {
            border = EmptyBorder(5, 6, 6, 6)
            add(check, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    /** Ctrl+Space 로도 전체교정. 입력칸에 커서가 있어도 먹게 창 전체에 건다. */
    private fun bindCorrectShortcut() {
        val root = frame.rootPane
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, "correct")
        root.actionMap.put("correct", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = correctNow()
        })
        correctButton.toolTipText = "글 전체를 고칩니다 (Ctrl+Space) — 실시간보다 셉니다"
    }

    // ---- 실시간 교정 ----

    private fun wireLive() {
        // 네모칸은 **값을 들고 있지 않다.** 누르면 저장소에 말하고, 저장소가 다시
        // 알려 줄 때 비로소 칸이 움직인다. 알림 영역 차림표에서 꺼도 이 칸이 같이
        // 움직이는 까닭이 이것이다.
        liveToggle.addActionListener { settings.setLiveCorrection(liveToggle.isSelected) }
        settings.addListener { s ->
            if (liveToggle.isSelected != s.liveCorrection) liveToggle.isSelected = s.liveCorrection
            if (live.enabled != s.liveCorrection) {
                live.enabled = s.liveCorrection
                say(if (s.liveCorrection) "실시간 교정을 켰습니다." else "실시간 교정을 껐습니다.")
            }
        }
        live.onReport = { report -> showReport(report) }
    }

    /**
     * 실시간 교정이 무엇을 했는지 알린다.
     *
     * [Report.Unchanged] 는 흘린다. 어절을 끝낼 때마다 "고칠 것이 없었다" 가 찍히면
     * 상태줄이 쉬지 않고 깜빡여서 정작 고친 것을 못 본다.
     */
    private fun showReport(report: Report) {
        when (report) {
            is Report.Applied -> {
                // 창이 세 어절이라 `from → to` 를 통째로 찍으면 한 줄에 안 들어간다.
                // 그리고 엔진의 `corrections` 는 재분절 이전 값이라 화면과 어긋난다.
                // 그래서 넣기 전후의 **진짜 글**에서 바뀐 토막만 뽑아 쓴다.
                val (was, now) = changedSpan(report.from, report.to) ?: (report.from to report.to)
                val line = "$was → $now"
                say(line)
                addDetail(line)
            }
            is Report.Reverted -> say("되돌렸습니다 — ${report.to.trimEnd()}")
            is Report.Dropped -> say("고치지 못했습니다 (${report.why}) — ${report.examined}")
            is Report.Unchanged -> Unit
        }
    }

    // ---- 동작 ----

    private fun pasteIntoInput() {
        val text = clipboardText()
        if (text == null) {
            say("클립보드에서 글을 못 읽었습니다.")
            return
        }
        live.replaceDocument(text)
        correctText(text)
    }

    /** Ctrl+Space 와 [FloatingWindow] 의 전체교정이 함께 부른다. */
    fun correctNow() {
        val text = input.text
        if (text.isBlank()) {
            say("고칠 글이 없습니다.")
            return
        }
        correctText(text)
    }

    /**
     * 글 전체를 고쳐 **그 자리에 도로 넣는다.**
     *
     * [LiveCorrector.replaceDocument] 를 거치는 것이 중요하다. 그냥 `input.text = ...`
     * 로 쓰면 실시간 교정이 그것을 사용자의 타자로 오해하고, 되돌리기가 조각나고,
     * 조합 중이면 유령 글자가 앞에 붙는다.
     */
    private fun correctText(text: String) {
        say("교정 중…")
        engine.correctAll(text) { corrected ->
            val result = corrected.result
            if (result.changed) live.replaceDocument(result.text)

            setDetail(describe(result.corrections))
            val guessed = corrected.guessedSpacing && result.corrections.any { it.reason == SPACING }
            say(
                when {
                    !result.changed -> "고칠 것이 없었습니다 · ${corrected.millis}ms"
                    guessed -> "${result.corrections.size}군데 고쳤습니다 — 띄어쓰기는 추측입니다"
                    else -> "${result.corrections.size}군데 고쳤습니다 · ${corrected.millis}ms"
                },
            )
            if (result.changed) showDetails(true)
        }
    }

    private fun copyText() {
        if (input.text.isEmpty()) {
            say("복사할 것이 없습니다.")
            return
        }
        setClipboard(input.text)
        say("클립보드에 넣었습니다.")
    }

    private fun runSelfTest() {
        say("엔진 점검 중…")
        engine.selfTest { failures ->
            say(
                if (failures.isEmpty()) "엔진 점검 통과 — 배선에 문제 없습니다."
                else "엔진 점검에서 ${failures.size}개가 떨어졌습니다.",
            )
            setDetail(failures.joinToString("\n").ifEmpty { "점검 항목을 전부 통과했습니다." })
            showDetails(true)
        }
    }

    // ---- 그리기 ----

    private fun showState(state: EngineState) {
        when (state) {
            EngineState.Loading -> {
                say("사전 올리는 중…")
                level.text = ""
            }
            is EngineState.Ready -> {
                level.text = "${state.level.label} · ${state.millis}ms"
                when (state.level) {
                    // 지워지면 안 되는 경고가 걸려 있으면 그쪽이 이긴다. 준비됐다는 말보다
                    // 안 되는 것이 있다는 말이 중요하다. [stickyWarning] 참고.
                    EngineLevel.CONTEXT -> sticky
                        ?.let { say(it); warn(true) }
                        ?: run { say("준비됐습니다."); warn(false) }
                    EngineLevel.DICTIONARY -> {
                        say("언어모델을 못 열었습니다 — 띄어쓰기가 크게 떨어집니다.")
                        warn(true)
                    }
                    EngineLevel.RULES -> {
                        say("사전을 못 열었습니다 — 규칙만 돕니다.")
                        warn(true)
                    }
                }
                state.trouble?.let {
                    setDetail(it)
                    showDetails(true)
                }
            }
        }
    }

    private fun warn(on: Boolean) {
        bar.isOpaque = on
        bar.background = if (on) WARN_BACKGROUND else null
        bar.repaint()
    }

    /** 상태 한 줄. 좁은 창에서 잘리므로 전문은 툴팁에 남긴다. */
    private fun say(message: String) {
        status.text = (if (detailPane.isVisible) "▼ " else "▶ ") + message
        status.toolTipText = message
    }

    private fun toggleDetails() = showDetails(!detailPane.isVisible)

    private fun showDetails(on: Boolean) {
        if (detailPane.isVisible == on) return
        detailPane.isVisible = on
        status.text = (if (on) "▼ " else "▶ ") + status.text.removePrefix("▶ ").removePrefix("▼ ")
        frame.contentPane.revalidate()
        frame.contentPane.repaint()
    }

    private fun setDetail(text: String) {
        details.text = text
        details.caretPosition = 0
    }

    /** 실시간 교정은 한 어절씩 들어오므로 쌓는다. 너무 길어지면 앞을 버린다. */
    private fun addDetail(text: String) {
        val kept = (details.text.lines() + text.lines()).takeLast(DETAIL_LINES)
        details.text = kept.joinToString("\n").trim()
        details.caretPosition = details.document.length
    }

    /**
     * 고친 것을 사유별로 묶는다. 셋은 믿을 만한 정도가 전혀 다르다 —
     * 맞춤법은 규칙이라 확실하고, 띄어쓰기는 공백이 없던 자리면 추측이다.
     */
    private fun describe(corrections: List<Correction>): String {
        if (corrections.isEmpty()) return "고칠 것이 없었습니다."
        return corrections.groupBy { it.reason }
            .entries
            .sortedBy { REASON_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: REASON_ORDER.size }
            .joinToString("\n") { (reason, items) ->
                "[$reason] " + items.joinToString(", ") { describeOne(it) }
            }
    }

    /**
     * 문장이 통째로 붙어 있으면 `Correction.from` 이 입력 전체라 한 줄이 읽을 수 없게
     * 길어진다. 공백만 들어간 것이면 몇 군데 띄었는지로 접는다.
     */
    private fun describeOne(correction: Correction): String {
        val onlySpacesAdded = correction.from.replace(" ", "") == correction.to.replace(" ", "")
        val added = correction.to.count { it == ' ' } - correction.from.count { it == ' ' }
        return if (onlySpacesAdded && added > 0 && correction.from.length > COLLAPSE_OVER) {
            "${correction.from.take(COLLAPSE_OVER)}… → ${added}군데 띄움"
        } else {
            "${correction.from} → ${correction.to}"
        }
    }

    // ---- 잡다한 것 ----

    private companion object {
        /** 560x540 에서 40% 줄인 값이다. */
        const val WIDTH = 336
        const val HEIGHT = 324

        const val SPACING = "띄어쓰기"

        /** 손봐야 할 수도 있는 것부터 보여 준다. 추측이 맨 위다. */
        val REASON_ORDER = listOf(SPACING, "문맥", "맞춤법")

        /** 이보다 긴 원문 조각은 그대로 찍지 않고 접는다. */
        const val COLLAPSE_OVER = 20

        /** 실시간 교정 내역을 몇 줄까지 들고 있을까. */
        const val DETAIL_LINES = 40

        // 실시간 교정 값은 이제 [SettingsStore] 가 같은 마디(/com/spellkeyboard/desktop)의
        // 같은 이름("liveCorrection")에 적는다. 여기서 또 읽고 쓰면 둘이 어긋난다.

        val WARN_BACKGROUND = Color(0xFF, 0xF3, 0xCD)
        val MUTED = Color(0x70, 0x70, 0x70)
        val LINE = Color(0xDD, 0xDD, 0xDD)
    }
}

/**
 * 클립보드는 다른 프로그램이 잡고 있으면 던진다. 반드시 받아서 삼켜야 한다 —
 * 안 그러면 누를 때마다 창이 죽는다.
 */
fun clipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

fun setClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/** 한글이 네모로 나오지 않게 실제로 깔려 있는 글꼴 중에서 고른다. */
fun koreanFont(size: Int): Font {
    val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    val family = listOf("맑은 고딕", "Malgun Gothic", "나눔고딕", "NanumGothic")
        .firstOrNull { it in available }
        ?: Font.SANS_SERIF
    return Font(family, Font.PLAIN, size)
}

/** 띠와 단추에 쓰는 작은 글꼴. 336px 안에 넉 자 단추 셋이 들어가야 한다. */
fun smallFont(): Font = koreanFont(11)
