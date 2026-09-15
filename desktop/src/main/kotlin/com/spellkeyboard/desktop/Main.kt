package com.spellkeyboard.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    SwingUtilities.invokeLater { CorrectorWindow(SpellEngine()).open() }
}

/**
 * 3단계 창. **붙여넣기 → 전체교정 → 복사**, 그것뿐이다.
 *
 * 떠 있는 입력창도, 단축키도, AI 도 아직 없다. 이 창의 일은 하나다 —
 * **교정이 실제로 되는지 눈으로 확인시키는 것.**
 */
class CorrectorWindow(private val engine: SpellEngine) {

    private val frame = JFrame("맞춤법 교정기")
    private val input = textArea(editable = true)
    private val output = textArea(editable = false)
    private val details = textArea(editable = false).apply { font = font.deriveFont(13f) }
    private val status = JLabel("사전 올리는 중…").apply {
        border = EmptyBorder(6, 10, 6, 10)
    }
    private val correctButton = JButton("전체교정")

    fun open() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                engine.close()
                frame.dispose()
            }
        })

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(status, BorderLayout.NORTH)
        frame.contentPane.add(centre(), BorderLayout.CENTER)
        frame.contentPane.add(buttons(), BorderLayout.SOUTH)

        bindCorrectShortcut()

        frame.size = Dimension(960, 640)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        input.requestFocusInWindow()

        engine.load { state -> showState(state) }
    }

    // ---- 화면 ----

    private fun centre(): JComponent {
        val texts = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            titled("원문 — 여기에 붙여넣으세요", input),
            titled("고친 글", output),
        ).apply {
            resizeWeight = 0.5
            border = null
        }
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, texts, titled("무엇을 고쳤나", details)).apply {
            resizeWeight = 0.72
            border = EmptyBorder(0, 8, 0, 8)
        }
    }

    private fun buttons(): JComponent {
        val paste = JButton("붙여넣기").apply { addActionListener { pasteIntoInput() } }
        correctButton.addActionListener { correctNow() }
        val copy = JButton("고친 글 복사").apply { addActionListener { copyOutput() } }
        val check = JButton("엔진 점검").apply { addActionListener { runSelfTest() } }

        return JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
            add(check)
            add(Box.createHorizontalStrut(24))
            add(paste)
            add(correctButton)
            add(copy)
        }
    }

    private fun titled(title: String, area: JTextArea): JComponent =
        JScrollPane(area).apply {
            border = BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), title,
            )
        }

    /** Ctrl+Space 로도 교정한다. 입력칸에 커서가 있어도 먹게 창 전체에 건다. */
    private fun bindCorrectShortcut() {
        val root = frame.rootPane
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, "correct")
        root.actionMap.put("correct", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = correctNow()
        })
        correctButton.toolTipText = "Ctrl+Space"
    }

    // ---- 동작 ----

    private fun pasteIntoInput() {
        val text = clipboardText()
        if (text == null) {
            status.text = "클립보드에서 글을 못 읽었다. 다시 해 보세요."
            return
        }
        input.text = text
        input.caretPosition = input.document.length
        correctNow()
    }

    private fun correctNow() {
        val text = input.text
        if (text.isBlank()) {
            status.text = "고칠 글이 없습니다."
            return
        }
        status.text = "교정 중…"
        engine.correctAll(text) { corrected -> showCorrected(corrected) }
    }

    private fun copyOutput() {
        if (output.text.isEmpty()) {
            status.text = "복사할 것이 없습니다. 먼저 교정하세요."
            return
        }
        setClipboard(output.text)
        status.text = "고친 글을 클립보드에 넣었습니다."
    }

    private fun runSelfTest() {
        status.text = "엔진 점검 중…"
        engine.selfTest { failures ->
            status.text = if (failures.isEmpty()) {
                "엔진 점검 통과 — 배선에 문제 없습니다."
            } else {
                "엔진 점검에서 ${failures.size}개가 떨어졌습니다. 아래를 보세요."
            }
            details.text = failures.joinToString("\n").ifEmpty { "점검 항목을 전부 통과했습니다." }
            details.caretPosition = 0
        }
    }

    // ---- 그리기 ----

    private fun showState(state: EngineState) {
        when (state) {
            EngineState.Loading -> status.text = "사전 올리는 중…"
            is EngineState.Ready -> {
                val head = "준비됨 · ${state.level.label} · ${state.millis}ms"
                status.text = if (state.trouble == null) head else "$head — ${state.trouble.replace('\n', ' ')}"
            }
        }
    }

    private fun showCorrected(corrected: Corrected) {
        val result = corrected.result
        output.text = result.text
        output.caretPosition = 0

        details.text = if (result.corrections.isEmpty()) {
            "고칠 것이 없었습니다."
        } else {
            result.corrections.joinToString("\n") { "${it.from}  →  ${it.to}      (${it.reason})" }
        }
        details.caretPosition = 0

        status.text = if (result.changed) {
            "${result.corrections.size}군데 고쳤습니다 · ${corrected.millis}ms"
        } else {
            "고칠 것이 없었습니다 · ${corrected.millis}ms"
        }
    }

    // ---- 잡다한 것 ----

    private fun textArea(editable: Boolean) = JTextArea().apply {
        this.isEditable = editable
        lineWrap = true
        wrapStyleWord = true
        font = koreanFont(15)
        border = EmptyBorder(8, 8, 8, 8)
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
