package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.Correction
import java.awt.BorderLayout
import java.awt.Color
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
        border = EmptyBorder(6, 10, 6, 8)
    }

    /**
     * 사전이 어디까지 올라왔는지. 교정할 때마다 덮이면 안 되므로 상태줄과 따로 둔다 —
     * "문맥까지" 가 아니면 성능이 떨어진 채로 쓰고 있다는 뜻이라 늘 보여야 한다.
     */
    private val level = JLabel("").apply {
        border = EmptyBorder(6, 8, 6, 10)
        foreground = Color(0x70, 0x70, 0x70)
    }

    private val bar = JPanel(BorderLayout())
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
        frame.contentPane.add(statusBar(), BorderLayout.NORTH)
        frame.contentPane.add(centre(), BorderLayout.CENTER)
        frame.contentPane.add(buttons(), BorderLayout.SOUTH)

        bindCorrectShortcut()

        frame.size = Dimension(560, 540)
        frame.minimumSize = Dimension(420, 380)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        input.requestFocusInWindow()

        engine.load { state -> showState(state) }
    }

    // ---- 화면 ----

    private fun statusBar(): JComponent = bar.apply {
        add(status, BorderLayout.WEST)
        add(level, BorderLayout.EAST)
    }

    /**
     * 원문과 고친 글을 위아래로 둔다. 좌우로 두면 창이 넓어야 하는데, 이 창은 작아야 한다 —
     * 5단계에서 단축키로 부르는 떠 있는 입력창이 될 물건이다.
     */
    private fun centre(): JComponent {
        val texts = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            titled("원문 — 여기에 붙여넣으세요", input),
            titled("고친 글", output),
        ).apply {
            resizeWeight = 0.5
            border = null
        }
        val changes = titled("무엇을 고쳤나", details).apply {
            preferredSize = Dimension(0, 108)
            minimumSize = Dimension(0, 56)
        }
        return JPanel(BorderLayout()).apply {
            border = EmptyBorder(0, 8, 0, 8)
            add(texts, BorderLayout.CENTER)
            add(changes, BorderLayout.SOUTH)
        }
    }

    private fun buttons(): JComponent {
        val paste = JButton("붙여넣기").apply { addActionListener { pasteIntoInput() } }
        correctButton.addActionListener { correctNow() }
        val copy = JButton("복사").apply { addActionListener { copyOutput() } }
        val check = JButton("점검").apply {
            toolTipText = "사전이 제대로 붙었는지 엔진이 스스로 검사한다"
            addActionListener { runSelfTest() }
        }

        return JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            add(check)
            add(Box.createHorizontalStrut(16))
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
            EngineState.Loading -> {
                status.text = "사전 올리는 중…"
                level.text = ""
            }
            is EngineState.Ready -> {
                level.text = "${state.level.label} · ${state.millis}ms"
                // 사전이 덜 올라온 것은 회색 글씨로 흘릴 일이 아니다. 언어모델이 빠지면
                // 붙여쓴 글 경계 F1 이 85 에서 48 로 떨어진다 — 성능이 절반이다.
                when (state.level) {
                    EngineLevel.CONTEXT -> {
                        status.text = "준비됐습니다."
                        warn(false)
                    }
                    EngineLevel.DICTIONARY -> {
                        status.text = "언어모델을 못 열었습니다. 띄어쓰기 복원이 크게 떨어집니다."
                        warn(true)
                    }
                    EngineLevel.RULES -> {
                        status.text = "사전을 못 열었습니다. 정규식 규칙만 돕니다."
                        warn(true)
                    }
                }
                state.trouble?.let { details.text = it }
            }
        }
    }

    private fun warn(on: Boolean) {
        bar.isOpaque = on
        bar.background = if (on) WARN_BACKGROUND else null
        bar.repaint()
    }

    private fun showCorrected(corrected: Corrected) {
        val result = corrected.result
        output.text = result.text
        output.caretPosition = 0

        details.text = describe(result.corrections)
        details.caretPosition = 0

        val guessed = corrected.guessedSpacing && result.corrections.any { it.reason == SPACING }
        status.text = when {
            !result.changed -> "고칠 것이 없었습니다 · ${corrected.millis}ms"
            guessed -> "${result.corrections.size}군데 고쳤습니다 — 원문에 공백이 없어 띄어쓰기는 추측입니다"
            else -> "${result.corrections.size}군데 고쳤습니다 · ${corrected.millis}ms"
        }
    }

    /**
     * 고친 것을 사유별로 묶는다. 셋은 믿을 만한 정도가 전혀 다른데 지금까지 한 줄에
     * 섞여 나왔다 — 맞춤법은 규칙이라 확실하고, 띄어쓰기는 공백이 없던 자리면 추측이다.
     */
    private fun describe(corrections: List<Correction>): String {
        if (corrections.isEmpty()) return "고칠 것이 없었습니다."
        return corrections.groupBy { it.reason }
            .entries
            .sortedBy { REASON_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: REASON_ORDER.size }
            .joinToString("\n\n") { (reason, items) ->
                val hint = REASON_HINT[reason]?.let { " — $it" } ?: ""
                "$reason ${items.size}군데$hint\n" +
                    items.joinToString("\n") { "    " + describeOne(it) }
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
            "${correction.from.take(COLLAPSE_OVER)}…  →  ${added}군데 띄었습니다"
        } else {
            "${correction.from}  →  ${correction.to}"
        }
    }

    // ---- 잡다한 것 ----

    private companion object {
        const val SPACING = "띄어쓰기"

        /** 손봐야 할 수도 있는 것부터 보여 준다. 추측이 맨 위다. */
        val REASON_ORDER = listOf(SPACING, "문맥", "맞춤법")

        val REASON_HINT = mapOf(
            SPACING to "원문에 공백이 없던 자리는 추측입니다",
            "문맥" to "언어모델이 앞뒤를 보고 골랐습니다",
        )

        /** 이보다 긴 원문 조각은 그대로 찍지 않고 접는다. */
        const val COLLAPSE_OVER = 24

        val WARN_BACKGROUND = Color(0xFF, 0xF3, 0xCD)
    }

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
