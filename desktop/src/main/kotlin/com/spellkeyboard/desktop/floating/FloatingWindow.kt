package com.spellkeyboard.desktop.floating

import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.DefaultEditorKit
import javax.swing.text.JTextComponent

/**
 * 떠 있는 창을 실제 [JFrame] 에 붙인다. **여기가 위젯을 아는 유일한 곳이다.**
 *
 * 쓰는 법:
 * ```
 * val float = FloatingWindow.install(
 *     frame = window.frame,
 *     editor = window.textArea,
 *     settings = PreferenceSettings(prefs),
 *     onApply = { text -> pasteBackInto(...) },   // 일꾼 실에서 불린다
 *     onCorrectAll = { window.correctAll() },     // EDT 에서 불린다
 * )
 * // 전역 단축키 실에서:  float.toggle()
 * // 쟁반 아이콘에서:     float.summon()
 * // 쟁반 "종료" 에서:    float.shutdown()
 * ```
 */
class FloatingWindow private constructor(
    private val frame: JFrame,
    private val editor: JTextComponent,
    private val onApply: (String) -> Unit,
    private val onCorrectAll: () -> Unit,
    private val onHidden: (HideReason) -> Unit,
    private val onQuit: () -> Unit,
    settings: FloatingSettings,
) : FloatingHost {

    /**
     * 붙여넣기를 실어 보낼 일꾼 실 하나.
     *
     * **EDT 에서 부르면 안 된다.** 붙여넣기는 앞 창이 돌아올 때까지 (잰 값으로 4~8ms,
     * 실패 길에서는 최대 800ms) 기다리고 그동안 EDT 가 멈추면 창이 얼어붙는다.
     * 실을 하나만 두는 까닭은 Enter 를 연달아 눌러도 붙여넣기가 서로 끼어들지 않게 하려는 것.
     */
    private val applier = Executors.newSingleThreadExecutor(
        ThreadFactory { r -> Thread(r, "float-apply").apply { isDaemon = true } },
    )

    private val controller = FloatingController(
        host = this,
        settings = settings,
        onApply = { text -> applier.execute { runCatching { onApply(text) } } },
        onCorrectAll = { onCorrectAll() },
        onHidden = { reason -> onHidden(reason) },
    )

    // ---- 바깥에서 부르는 것. **어느 실에서 불러도 된다.** ----

    /** 단축키. 떠 있고 초점까지 있으면 숨기고, 아니면 띄운다. */
    fun toggle() = onEdt { controller.toggle() }

    /** 쟁반 아이콘. 언제나 띄운다. */
    fun summon() = onEdt { controller.summon() }

    fun hideNow() = onEdt { controller.hideExternally() }

    fun setHideOnFocusLoss(on: Boolean) = onEdt { controller.setHideOnFocusLoss(on) }

    fun setPolicy(policy: PlacementPolicy) = onEdt { controller.setPolicy(policy) }

    val isShowing: Boolean get() = controller.isShowing

    /**
     * 진짜로 끝낼 때만. 쟁반 메뉴의 "종료" 하나뿐이다.
     *
     * **종료 갈고리(shutdown hook)에서 부르지 마라.** 갈고리에서 `dispose()` 를 부르면
     * `EventQueue.invokeAndWait` 로 들어가는데, 한국어 IME 가 걸린 EDT 가 그때
     * `WInputMethod.getNativeLocale` 에서 멈춰 있으면 프로세스가 영영 안 죽는다
     * (이 기계에서 JVM 넷이 그렇게 걸려 있었다). 끝낼 때는 **먼저 숨기고**, 그다음
     * EDT 에서 바로 부수고, 그다음에 나가라.
     */
    fun shutdown() = onEdt {
        controller.hideExternally()          // 먼저 화면에서 치운다
        applier.shutdownNow()
        runCatching { onQuit() }             // 엔진 닫기·실시간 교정 떼기가 여기 들어간다
        frame.dispose()                      // **EDT 에서** 부순다
    }

    // ---- FloatingHost. 전부 EDT 에서 불린다. ----

    override fun place(bounds: Rect) {
        frame.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override fun showWindow() {
        // Swing 으로 띄운다. Win32 `ShowWindow` 를 EDT 밖에서 부르면 창이 앞으로도
        // 안 나오고 초점도 안 옮겨 간다(붙여넣기 탐침에서 420번 전부 실패).
        if (!frame.isVisible) frame.isVisible = true
        frame.toFront()
    }

    override fun hideWindow() {
        // **`dispose()` 가 아니다.** 부수면 사전이 올라간 엔진까지 같이 날아가고,
        // 다시 부를 때 몇 초가 걸려 "즉시 뜬다" 가 거짓말이 된다.
        frame.isVisible = false
    }

    override fun focusEditor() {
        editor.requestFocusInWindow()
        // 방금 띄운 창은 아직 초점을 받을 수 있는 상태가 아닐 수 있어서 위의 요청이
        // 조용히 false 로 끝난다. 한 번 더 미뤄 두면 활성화가 끝난 뒤에 들어간다.
        // 이미 들어가 있으면 두 번째는 아무 일도 안 한다.
        SwingUtilities.invokeLater {
            if (frame.isVisible && !editor.hasFocus()) editor.requestFocusInWindow()
        }
    }

    override fun editorText(): String = editor.text ?: ""

    override fun windowSize(): Rect {
        val w = if (frame.width > 0) frame.width else frame.preferredSize.width
        val h = if (frame.height > 0) frame.height else frame.preferredSize.height
        return Rect(0, 0, w, h)
    }

    override fun windowBounds(): Rect? = frame.bounds?.toRect()

    override fun screens(): List<ScreenBox> = AwtScreens.all()

    override fun pointer(): Pt? = AwtScreens.pointer()

    // ---- 배선 ----

    private fun wire() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        // 부르면 보이는 창이어야 한다. 앞 창 위에 안 뜨면 부른 보람이 없다.
        frame.isAlwaysOnTop = true

        // **미리 실체화해 둔다.** 창의 네이티브 peer 는 첫 `setVisible(true)` 때
        // 만들어지고, 그때 룩앤필·글꼴·IME 가 같이 올라온다. 재 보니 그 값이
        // 첫 소환 33ms · 그다음 25ms 였다. 여기서 해 두면 그 8ms 가 프로그램
        // 시작할 때로 옮겨 가고, 사용자가 처음 부르는 순간도 두 번째와 똑같아진다.
        // `pack()` 은 창 크기를 제 뜻대로 바꾸므로 원래 크기를 도로 넣는다 —
        // 336x324 는 일부러 고른 값이다.
        if (!frame.isDisplayable) {
            val size = frame.size
            frame.pack()
            if (size.width > 0 && size.height > 0) frame.size = size
        }

        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = controller.onCloseButton()
        })

        frame.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) = controller.onFocusGained()
            override fun windowLostFocus(e: WindowEvent) = controller.onFocusLost()
        })

        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = controller.onWindowMoved()
        })

        bindKeys()
    }

    /**
     * 설계서의 자판을 실제로 건다.
     *
     * ## Enter 는 **글칸에** 걸어야 한다
     *
     * `WHEN_IN_FOCUSED_WINDOW` 에 걸면 안 먹는다. 초점을 쥔 [JTextComponent] 자신의
     * `WHEN_FOCUSED` 지도에 Enter 가 이미 `insert-break` 로 박혀 있고, 그쪽이 먼저
     * 이기기 때문이다. 그래서 글칸 지도를 직접 덮어쓴다.
     *
     * Shift+Enter 는 반대로 **일부러** 줄바꿈으로 못 박는다. 기본 지도에 있는 것은
     * 맨 Enter 뿐이라 Shift+Enter 가 무엇을 할지는 룩앤필에 달려 있다 — 못 박아 두면
     * 어디서 돌든 똑같이 준다.
     *
     * Esc 와 Ctrl+Space 는 창 전체([JComponent.WHEN_IN_FOCUSED_WINDOW])에 건다.
     * 단추에 초점이 가 있어도 Esc 로 닫혀야 하기 때문이다.
     */
    private fun bindKeys() {
        val editorMap = editor.getInputMap(JComponent.WHEN_FOCUSED)
        editorMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACT_ENTER)
        editorMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            DefaultEditorKit.insertBreakAction,
        )
        editor.actionMap.put(ACT_ENTER, keyAction(KeyEvent.VK_ENTER, 0))

        val rootMap = frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACT_ESC)
        frame.rootPane.actionMap.put(ACT_ESC, keyAction(KeyEvent.VK_ESCAPE, 0))

        // 창이 이미 걸어 둔 Ctrl+Space 를 **같은 자리에 덮어쓴다.** InputMap 은
        // 키를 열쇠로 삼으므로 둘이 같이 불리는 일은 없다 — 전체교정이 두 번 도는
        // 것을 막으려면 이렇게 덮어써야 한다.
        val ctrl = InputEvent.CTRL_DOWN_MASK
        rootMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, ctrl), ACT_CORRECT)
        frame.rootPane.actionMap.put(ACT_CORRECT, keyAction(KeyEvent.VK_SPACE, ctrl))
    }

    /**
     * 키 하나를 [FloatingController] 로 넘긴다. 컨트롤러가 안 먹었다고 하면
     * (조합 중이라 흘리기로 한 경우) **원래 하던 일을 대신 해 준다** — 안 그러면
     * 조합 중 Enter 가 아무 데도 안 가고 사라진다.
     */
    private fun keyAction(keyCode: Int, modifiersEx: Int) = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) {
            val handled = controller.onKey(keyCode, modifiersEx, composing())
            if (!handled) fallback(keyCode, e)
        }
    }

    private fun fallback(keyCode: Int, e: ActionEvent) {
        if (keyCode != KeyEvent.VK_ENTER) return
        editor.actionMap.get(DefaultEditorKit.insertBreakAction)?.actionPerformed(e)
    }

    /**
     * 한글 IME 가 조합 중인가.
     *
     * `문서 길이 - committedTextLength`. [com.spellkeyboard.desktop.live.LiveCorrector]
     * 가 쓰는 것과 **같은 자**다. 리플렉션도 내부 API 도 아니다.
     */
    private fun composing(): Boolean {
        val requests = editor.inputMethodRequests ?: return false
        return runCatching { editor.document.length > requests.committedTextLength }
            .getOrDefault(false)
    }

    private fun onEdt(body: () -> Unit) {
        // 이미 EDT 면 그 자리에서. 아니면 미룬다. **invokeAndWait 은 안 쓴다** —
        // 단축키 실이 EDT 를 기다리는 동안 다음 단축키를 못 받는다.
        if (SwingUtilities.isEventDispatchThread()) body() else SwingUtilities.invokeLater(body)
    }

    companion object {
        private const val ACT_ENTER = "float.applyAndHide"
        private const val ACT_ESC = "float.cancel"
        private const val ACT_CORRECT = "float.correctAll"

        /**
         * 이미 만들어져 있는 창에 떠 있는 창 노릇을 붙인다. **EDT 에서 불러라.**
         *
         * @param onApply Enter. **일꾼 실에서** 불리고, 부를 때 창은 **이미 숨어 있다.**
         *   받는 쪽(붙여넣기)은 앞 창이 돌아오기를 기다렸다가 Ctrl+V 를 보내면 된다.
         * @param onCorrectAll Ctrl+Space. EDT 에서 불린다.
         * @param onHidden 왜 숨었는지 알고 싶은 쪽(쟁반 아이콘 표시 등)을 위한 것.
         * @param onQuit [shutdown] 이 창을 부수기 **직전에** EDT 에서 부른다.
         *   `engine.close()` 와 `live.detach()` 가 여기 들어간다. 매개변수로 받는
         *   까닭은 순서를 못 박기 위해서다 — 숨기기 → 엔진 닫기 → dispose 순서를
         *   손으로 지키게 두면 언젠가 누가 거꾸로 쓴다.
         */
        fun install(
            frame: JFrame,
            editor: JTextComponent,
            settings: FloatingSettings,
            onApply: (String) -> Unit,
            onCorrectAll: () -> Unit,
            onHidden: (HideReason) -> Unit = {},
            onQuit: () -> Unit = {},
        ): FloatingWindow {
            check(SwingUtilities.isEventDispatchThread()) { "install 은 EDT 에서" }
            return FloatingWindow(frame, editor, onApply, onCorrectAll, onHidden, onQuit, settings)
                .apply { wire() }
        }
    }
}
