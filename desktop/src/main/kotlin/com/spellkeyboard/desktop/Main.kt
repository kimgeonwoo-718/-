package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.Correction
import com.spellkeyboard.desktop.live.LiveCorrector
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

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    SwingUtilities.invokeLater { CorrectorWindow(SpellEngine()).open() }
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
class CorrectorWindow(private val engine: SpellEngine) {

    private val frame = JFrame("맞춤법 교정기")

    /** 원문이자 고친 글. 실시간 교정이 **이 칸을 제자리에서** 고친다. */
    private val input = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        font = koreanFont(14)
        border = EmptyBorder(6, 7, 6, 7)
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

    fun open() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                live.detach()
                engine.close()
                frame.dispose()
            }
        })

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(topBar(), BorderLayout.NORTH)
        frame.contentPane.add(JScrollPane(input).apply { border = null }, BorderLayout.CENTER)
        frame.contentPane.add(bottom(), BorderLayout.SOUTH)

        bindCorrectShortcut()
        wireLive()

        frame.size = Dimension(WIDTH, HEIGHT)
        frame.minimumSize = Dimension(300, 260)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        input.requestFocusInWindow()

        engine.load { state -> showState(state) }
    }

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
        liveToggle.isSelected = prefs.getBoolean(LIVE_KEY, true)
        live.enabled = liveToggle.isSelected
        liveToggle.addActionListener {
            live.enabled = liveToggle.isSelected
            prefs.putBoolean(LIVE_KEY, liveToggle.isSelected)
            say(if (liveToggle.isSelected) "실시간 교정을 켰습니다." else "실시간 교정을 껐습니다.")
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

    private fun correctNow() {
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
                    EngineLevel.CONTEXT -> {
                        say("준비됐습니다.")
                        warn(false)
                    }
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

        const val LIVE_KEY = "liveCorrection"

        val prefs: Preferences = Preferences.userNodeForPackage(CorrectorWindow::class.java)

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
