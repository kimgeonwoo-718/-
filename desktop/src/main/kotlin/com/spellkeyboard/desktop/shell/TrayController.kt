package com.spellkeyboard.desktop.shell

import java.awt.CheckboxMenuItem
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** 알림 영역에서 누를 때 무엇을 할지. 화면 없이 시험하려고 갈라 놓았다. */
enum class TrayClick { OPEN, IGNORE }

/**
 * 알림 영역 아이콘 누름을 읽는다.
 *
 * ## 왜 [TrayIcon.addActionListener] 를 안 쓰는가
 *
 * 윈도우에서 그 청자는 **두 번 눌러야** 불린다. 요구는 한 번 누르면 창이 뜨는
 * 것이므로 [java.awt.event.MouseListener] 로 받아야 한다.
 *
 * 오른쪽 단추는 흘린다. 차림표는 Windows 가 [PopupMenu] 로 직접 띄우는데, 우리가
 * 여기서 창까지 띄우면 차림표를 열 때마다 창이 따라 뜬다.
 */
fun trayClickAction(button: Int): TrayClick =
    if (button == MouseEvent.BUTTON1) TrayClick.OPEN else TrayClick.IGNORE

/**
 * 알림 영역에 사는 아이콘과 그 차림표.
 *
 * ## 이것이 없으면 프로그램을 끌 수가 없다
 *
 * 설계상 창을 닫아도 프로그램은 안 끝난다(끝나면 단축키가 같이 죽는다). 그래서
 * **끄는 길은 여기 `종료` 하나뿐**이다. 이 아이콘을 못 붙이면 그 사실을 사용자에게
 * 반드시 말해야 한다 — [TrayResult.Unavailable] 이 그 자리다.
 */
class TrayController private constructor(
    private val tray: SystemTray,
    private val icon: TrayIcon,
    private val store: SettingsStore,
    private val liveItem: CheckboxMenuItem,
) {

    /** 설정이 바뀌면 아이콘·차림표·풍선말을 맞춘다. */
    private val listener = SettingsListener { s -> render(s) }

    /**
     * 우리가 [CheckboxMenuItem.setState] 로 건드리는 중인지.
     *
     * AWT 문서상 [CheckboxMenuItem.setState] 는 ItemEvent 를 안 쏘지만, 그것에
     * 기대면 되돌이 고리가 생겼을 때 원인이 안 보인다. 한 줄로 못을 박는다.
     */
    private var syncing = false

    private fun render(s: DesktopSettings) {
        icon.image = TrayIconArt.render(iconSize(tray), s.liveCorrection)
        // 풍선말에 지금 조합을 적어 둔다. "단축키가 뭐였더라" 를 설정 창을 열지
        // 않고 답해 준다. 등록에 실패했더라도 사용자가 고른 값이 여기 보인다.
        icon.toolTip = "맞춤법 교정기 · ${s.hotkey.display()}"
        syncing = true
        try {
            liveItem.state = s.liveCorrection
        } finally {
            syncing = false
        }
    }

    /** 끝낼 때. 안 부르면 프로그램이 죽어도 아이콘이 잠시 남는다. */
    fun remove() {
        store.removeListener(listener)
        runCatching { tray.remove(icon) }
    }

    /** 사용자에게 한마디. 단축키 등록 실패처럼 창이 없을 때도 보여야 하는 것에 쓴다. */
    fun notify(title: String, text: String, error: Boolean = false) {
        runCatching {
            icon.displayMessage(
                title,
                text,
                if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO,
            )
        }
    }

    companion object {

        /**
         * 아이콘을 붙인다. **EDT 에서 불러라.**
         *
         * 네 콜백은 전부 EDT 에서 불린다 — AWT 가 알림 영역 사건을 EDT 로 보낸다.
         * 그러니 콜백 안에서 오래 걸리는 일(사전 읽기, 소켓)을 하지 마라.
         *
         * @param traySupported 시험에서만 바꾼다. 화면 없는 시험에서 진짜 알림 영역을
         *   건드리지 않으려고 뚫어 둔 구멍이다.
         */
        fun install(
            store: SettingsStore,
            onOpen: () -> Unit,
            onSettings: () -> Unit,
            onQuit: () -> Unit,
            traySupported: () -> Boolean = { SystemTray.isSupported() },
        ): TrayResult {
            if (!traySupported()) {
                // 요구대로: 그냥 창을 띄우고 사실을 말한다.
                runCatching { onOpen() }
                return TrayResult.Unavailable(
                    "이 컴퓨터에서는 알림 영역을 쓸 수 없습니다.\n" +
                        "창을 닫으면 프로그램이 끝납니다. 단축키로 다시 부를 수는 없습니다.",
                )
            }

            val tray = try {
                SystemTray.getSystemTray()
            } catch (e: Throwable) {
                // isSupported() 가 참이어도 여기서 던지는 자리가 있다(원격 데스크톱 등).
                runCatching { onOpen() }
                return TrayResult.Unavailable("알림 영역을 열지 못했습니다: ${e.message}")
            }

            val liveItem = CheckboxMenuItem("실시간 교정")
            val menu = PopupMenu().apply {
                add(MenuItem("열기").apply { addActionListener { onOpen() } })
                add(liveItem)
                add(MenuItem("설정").apply { addActionListener { onSettings() } })
                addSeparator() // 종료를 떼어 놓는다. 잘못 누르면 되돌릴 수 없는 유일한 항목이다.
                add(MenuItem("종료").apply { addActionListener { onQuit() } })
            }

            val icon = TrayIcon(TrayIconArt.render(iconSize(tray), store.get().liveCorrection))
            // 우리가 크기를 맞춰 그리지만, 배율이 도중에 바뀌면 여기가 받아 준다.
            icon.isImageAutoSize = true
            icon.popupMenu = menu
            icon.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (trayClickAction(e.button) == TrayClick.OPEN) onOpen()
                }
            })

            val controller = TrayController(tray, icon, store, liveItem)
            liveItem.addItemListener { e ->
                if (controller.syncing) return@addItemListener
                store.setLiveCorrection(e.stateChange == ItemEvent.SELECTED)
            }

            return try {
                tray.add(icon)
                // 붙인 다음에 듣기 시작한다. 붙이다 실패하면 듣는 이가 남으면 안 된다.
                store.addListener(controller.listener) // 붙는 즉시 지금 값으로 한 번 그린다
                TrayResult.Installed(controller)
            } catch (e: Throwable) {
                runCatching { onOpen() }
                TrayResult.Unavailable("알림 영역에 아이콘을 못 붙였습니다: ${e.message}")
            }
        }

        /** 배율에 따라 16~32 사이가 온다. 0 이나 헛값이 오는 기계가 있어 바닥을 둔다. */
        private fun iconSize(tray: SystemTray): Int =
            runCatching { tray.trayIconSize.width }.getOrDefault(16).coerceIn(16, 64)
    }
}

sealed interface TrayResult {
    data class Installed(val controller: TrayController) : TrayResult

    /**
     * 아이콘을 못 붙였다. 창은 이미 띄웠으니(install 이 [onOpen] 을 불렀다)
     * [message] 를 사용자에게 보여 주기만 하면 된다.
     */
    data class Unavailable(val message: String) : TrayResult
}

/**
 * 창을 닫아도 프로그램이 안 끝나게 한다.
 *
 * ## 왜 닫아도 안 끝나야 하는가
 *
 * 끝나면 전역 단축키도 같이 죽는다. 그러면 사용자가 창을 한 번 닫은 뒤로 Ctrl+Shift+Space
 * 가 영영 안 먹고, 그것이 왜인지 알 길이 없다. 그래서 닫기는 **숨기기**다.
 *
 * ## 알림 영역이 없으면 이야기가 다르다
 *
 * 그때는 `종료` 차림표가 없어서 끄는 길이 하나도 없다. 작업 관리자로 죽이라고 할
 * 수는 없다. 알림 영역이 없을 때만 닫기가 진짜 종료가 된다 — [hasTray] 가 그것이다.
 *
 * 그리고 **[dispose] 를 종료 갈고리(shutdown hook)에서 부르지 마라.** 한국어 IME 가
 * 붙은 상태에서 갈고리가 `EventQueue.invokeAndWait` 로 창을 없애려 들면 EDT 가
 * `WInputMethod.getNativeLocale` 안에서 멈춰 프로세스가 영영 안 끝난다. 이 기계에서
 * 실제로 JVM 네 개가 그렇게 멎어 있었다. 끝내기 전에, EDT 에서, 먼저 없애라.
 */
fun installHideOnClose(frame: JFrame, hasTray: Boolean, onQuit: () -> Unit) {
    if (!hasTray) {
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) = onQuit()
        })
        return
    }
    frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent) {
            frame.isVisible = false
        }
    })
}

/** EDT 가 아니면 EDT 로 넘긴다. 소켓 실에서 창을 띄울 때 쓰는 자리. */
fun onEdt(body: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) body() else SwingUtilities.invokeLater(body)
}
