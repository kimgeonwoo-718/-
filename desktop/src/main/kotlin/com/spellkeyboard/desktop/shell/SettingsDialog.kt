package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.Win32Keys

import com.spellkeyboard.desktop.koreanFont
import com.spellkeyboard.desktop.smallFont
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

// ---------------------------------------------------------------- 갈무리 판단

/** 갈무리 칸이 키 하나를 받고 내리는 판단. 화면 없이 시험하려고 뽑아 놓았다. */
sealed interface CaptureOutcome {
    /** 아직 조합키만 눌렀다. 기다린다. */
    data object Pending : CaptureOutcome

    /** 갈무리를 그만둔다(Esc). 원래 값으로 되돌린다. */
    data object Cancelled : CaptureOutcome

    /** 쓸 수 없는 조합. [reason] 을 곧바로 보여 준다. */
    data class Rejected(val reason: String) : CaptureOutcome

    data class Captured(val combo: HotkeyCombo) : CaptureOutcome
}

/**
 * 누른 키를 어떻게 받아들일지.
 *
 * ## Esc 가 왜 조합이 못 되는가
 *
 * Esc 는 Ctrl 이나 Alt 와 같이 눌러도 여기서는 "그만두기" 다. 갈무리 중에는
 * 창을 닫을 다른 길이 없어서(모든 키를 이 칸이 삼킨다) 빠져나갈 문이 하나는
 * 있어야 한다. Esc 를 단축키로 쓰고 싶은 사람은 없다시피 하니 그 자리를 내준다.
 *
 * ## 왜 조합키 자체는 [Pending] 인가
 *
 * 사용자가 Ctrl 을 누르고 Shift 를 누르고 그 다음 Space 를 누르는 중이다.
 * Ctrl 하나를 조합으로 굳혀 버리면 절대 원하는 것을 못 고른다.
 */
fun captureOutcome(keyCode: Int, modifiersEx: Int): CaptureOutcome {
    if (keyCode == KeyEvent.VK_ESCAPE) return CaptureOutcome.Cancelled
    if (keyCode in MODIFIER_KEYS) return CaptureOutcome.Pending
    val combo = HotkeyCombo.fromKeyEvent(keyCode, modifiersEx)
        ?: return CaptureOutcome.Rejected("이 키는 단축키로 쓸 수 없습니다.")
    combo.rejectReason()?.let { return CaptureOutcome.Rejected(it) }
    return CaptureOutcome.Captured(combo)
}

/** AWT 가 조합키 자체에 주는 코드들. 한/영 키(VK_KANA 등)는 표에 없어 저절로 걸러진다. */
private val MODIFIER_KEYS = setOf(
    KeyEvent.VK_CONTROL,
    KeyEvent.VK_SHIFT,
    KeyEvent.VK_ALT,
    KeyEvent.VK_ALT_GRAPH,
    KeyEvent.VK_META,
    KeyEvent.VK_WINDOWS,
    KeyEvent.VK_CAPS_LOCK,
    KeyEvent.VK_UNDEFINED,
)

// ---------------------------------------------------------------- 설정 창

/**
 * 작은 설정 창.
 *
 * 창 하나에 네 줄이다. 탭도 없고 갈래도 없다 — 설정이 넷뿐인데 서랍을 만들면
 * 찾는 데만 더 걸린다.
 */
class SettingsDialog private constructor(
    owner: Window?,
    private val store: SettingsStore,
    private val probe: HotkeyProbe,
) {

    private val dialog = JDialog(owner, "설정", java.awt.Dialog.ModalityType.APPLICATION_MODAL)

    /** 지금 화면에 잡혀 있는 조합. 아직 저장 전이다. */
    private var picked: HotkeyCombo = store.get().hotkey

    private var capturing = false

    /** 확인이 끝난 결과. 확인 단추를 막을지 여기서 정한다. */
    private var availability: HotkeyAvailability = HotkeyAvailability.Ours

    /**
     * 확인 요청 번호.
     *
     * 사용자가 조합을 연달아 바꾸면 확인이 겹친다. 늦게 돌아온 옛 답이 새 답을
     * 덮어쓰면 **엉뚱한 조합에 대한 판정이 화면에 남는다.** 번호가 다르면 버린다.
     */
    private val checkSeq = AtomicInteger(0)

    private val hotkeyField = JTextField().apply {
        isEditable = false // 글자를 치는 칸이 아니다. 키를 받는 칸이다.
        font = koreanFont(13)
        preferredSize = Dimension(190, 28)
        toolTipText = "눌러서 고른 뒤 원하는 조합을 누르세요"
        // Tab 과 Shift+Tab 을 삼켜야 한다. 안 그러면 Ctrl+Tab 을 고르려는 순간
        // 초점이 다음 칸으로 달아난다.
        focusTraversalKeysEnabled = false
        // 글자 깜빡이를 없앤다. 재 보니 초점이 오면 `Ctrl+Alt+K|` 처럼 깜빡이가
        // 서서, 글을 쳐 넣는 칸처럼 보인다. 여기는 키를 받는 칸이다.
        caret = object : DefaultCaret() {
            override fun setVisible(visible: Boolean) = super.setVisible(false)
        }.apply { blinkRate = 0 }
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val hotkeyNote = JLabel(" ").apply { font = smallFont() }

    private val delaySpinner = JSpinner(
        SpinnerNumberModel(
            store.get().pasteExtraDelayMs,
            DesktopSettings.MIN_PASTE_DELAY_MS,
            DesktopSettings.MAX_PASTE_DELAY_MS,
            10,
        ),
    ).apply { preferredSize = Dimension(70, 26) }

    private val hideOnBlur = JCheckBox("다른 창을 누르면 숨기기").apply {
        font = koreanFont(12)
        isSelected = store.get().hideOnFocusLoss
        toolTipText = "글을 쓰다 다른 곳을 누르면 교정 창이 저절로 숨습니다"
    }

    private val liveBox = JCheckBox("실시간 교정").apply {
        font = koreanFont(12)
        isSelected = store.get().liveCorrection
        toolTipText = "치는 대로 어절이 끝날 때마다 고칩니다"
    }

    private val okButton = JButton("확인")

    private fun build() {
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val form = JPanel(GridBagLayout()).apply { border = BorderFactory.createEmptyBorder(12, 14, 6, 14) }
        val c = GridBagConstraints().apply {
            insets = Insets(4, 0, 4, 8)
            anchor = GridBagConstraints.WEST
            gridy = 0
        }

        c.gridx = 0; form.add(label("단축키"), c)
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0; form.add(hotkeyField, c)

        c.gridy = 1; c.gridx = 1; form.add(hotkeyNote, c)

        c.gridy = 2; c.gridx = 0; c.fill = GridBagConstraints.NONE; c.weightx = 0.0
        form.add(label("붙여넣기 여유"), c)
        c.gridx = 1
        form.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(delaySpinner)
                add(JLabel("ms").apply { font = smallFont() })
            },
            c,
        )
        c.gridy = 3; c.gridx = 1
        form.add(
            JLabel("앞 창이 돌아온 것을 확인한 뒤 더 기다릴 시간입니다.").apply {
                font = smallFont()
                foreground = MUTED
            },
            c,
        )

        c.gridy = 4; c.gridx = 1; form.add(hideOnBlur, c)
        c.gridy = 5; c.gridx = 1; form.add(liveBox, c)

        val cancel = JButton("취소").apply { addActionListener { dialog.dispose() } }
        okButton.addActionListener { apply() }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 10, 10, 12)
            add(cancel)
            add(okButton)
        }

        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(form, BorderLayout.CENTER)
        dialog.contentPane.add(buttons, BorderLayout.SOUTH)

        wireCapture()
        // 갈무리 중이 아닐 때만 Esc 로 창을 닫는다. 갈무리 중의 Esc 는 갈무리 취소다.
        dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
        dialog.rootPane.actionMap.put(
            "close",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    if (!capturing) dialog.dispose()
                }
            },
        )
        dialog.rootPane.defaultButton = okButton

        showPicked()
        recheck()
        dialog.pack()
        dialog.minimumSize = Dimension(330, dialog.height)
        dialog.setLocationRelativeTo(dialog.owner)
    }

    private fun label(text: String) = JLabel(text).apply { font = koreanFont(12) }

    // ---- 단축키 갈무리 ----

    private fun wireCapture() {
        hotkeyField.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = startCapture()
        })
        hotkeyField.addFocusListener(object : FocusAdapter() {
            // 초점이 나가면 갈무리도 끝난다. 안 그러면 사용자가 딴 칸을 만지는데도
            // 칸이 계속 "키를 누르세요" 인 채로 남는다.
            override fun focusLost(e: FocusEvent) = stopCapture()
        })
        hotkeyField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!capturing) {
                    // 칸에 초점만 있고 갈무리 전이면 Space/Enter 로 갈무리를 시작한다.
                    if (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER) {
                        startCapture()
                        e.consume()
                    }
                    return
                }
                // 갈무리 중에는 **전부 삼킨다.** 안 삼키면 Enter 가 확인 단추를
                // 누르고 Space 가 네모칸을 토글한다.
                e.consume()
                when (val outcome = captureOutcome(e.keyCode, e.modifiersEx)) {
                    CaptureOutcome.Pending -> hotkeyField.text = pendingText(e.modifiersEx)
                    CaptureOutcome.Cancelled -> stopCapture()
                    is CaptureOutcome.Rejected -> {
                        // 곧바로 말해 준다. 확인을 누를 때까지 기다리지 않는다.
                        note(outcome.reason, BAD)
                    }
                    is CaptureOutcome.Captured -> {
                        picked = outcome.combo
                        stopCapture()
                        recheck()
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) { if (capturing) e.consume() }
            override fun keyTyped(e: KeyEvent) { if (capturing) e.consume() }
        })
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        hotkeyField.requestFocusInWindow()
        hotkeyField.background = CAPTURING
        hotkeyField.text = "키를 누르세요…"
        note("Ctrl, Alt, Win 중 하나를 섞어 주세요. Esc 는 취소입니다.", MUTED)
    }

    private fun stopCapture() {
        if (!capturing) return
        capturing = false
        hotkeyField.background = Color.WHITE
        showPicked()
    }

    /** 조합키만 눌린 중간 상태를 그대로 보여 준다. 사용자가 무엇을 쥐고 있는지 보인다. */
    private fun pendingText(modifiersEx: Int): String {
        val mods = HotkeyCombo.modsFromAwt(modifiersEx)
        if (mods == 0) return "키를 누르세요…"
        return buildString {
            if (mods and HotkeyCombo.MOD_CONTROL != 0) append("Ctrl+")
            if (mods and HotkeyCombo.MOD_ALT != 0) append("Alt+")
            if (mods and HotkeyCombo.MOD_SHIFT != 0) append("Shift+")
            if (mods and HotkeyCombo.MOD_WIN != 0) append("Win+")
            append("…")
        }
    }

    private fun showPicked() {
        hotkeyField.text = picked.display()
    }

    /**
     * 고른 조합을 정말 잡을 수 있는지 물어본다.
     *
     * **EDT 를 막지 않는다.** 네이티브 등록은 짧지만, 짧다는 이유로 EDT 에서
     * 부르기 시작하면 다음 사람이 거기에 뭘 더 붙인다.
     */
    private fun recheck() {
        val combo = picked
        val seq = checkSeq.incrementAndGet()
        note("확인 중…", MUTED)
        okButton.isEnabled = false
        Thread({
            val result = runCatching { probe.check(combo) }
                .getOrElse { HotkeyAvailability.Unknown(it.message ?: "확인 실패") }
            SwingUtilities.invokeLater {
                if (seq != checkSeq.get()) return@invokeLater // 지나간 답이다. 버린다.
                availability = result
                okButton.isEnabled = result.usable()
                when (result) {
                    HotkeyAvailability.Free -> note("쓸 수 있습니다.", GOOD)
                    HotkeyAvailability.Ours -> note("지금 쓰고 있는 조합입니다.", GOOD)
                    is HotkeyAvailability.Taken -> note(result.reason, BAD)
                    is HotkeyAvailability.Unknown -> note(result.why + " 그래도 저장할 수는 있습니다.", MUTED)
                }
            }
        }, "hotkey-check").apply { isDaemon = true }.start()
    }

    private fun note(text: String, color: Color) {
        hotkeyNote.text = text
        hotkeyNote.foreground = color
    }

    // ---- 저장 ----

    private fun apply() {
        if (!availability.usable()) return // 단추가 이미 꺼져 있지만, 기본 단추 경로도 막는다.
        store.update(
            store.get().copy(
                hotkey = picked,
                pasteExtraDelayMs = (delaySpinner.value as Number).toInt(),
                hideOnFocusLoss = hideOnBlur.isSelected,
                liveCorrection = liveBox.isSelected,
            ),
        )
        dialog.dispose()
    }

    companion object {
        private val MUTED = Color(0x70, 0x70, 0x70)
        private val GOOD = Color(0x1E, 0x7A, 0x34)
        private val BAD = Color(0xB3, 0x26, 0x1E)
        private val CAPTURING = Color(0xFF, 0xF6, 0xD5)

        /**
         * 설정 창을 연다. **EDT 에서 불러라.** 창은 잠금창이라 닫힐 때까지 안 돌아온다.
         *
         * 저장은 [store] 로만 나간다 — 부르는 쪽은 [SettingsStore.addListener] 로
         * 바뀐 것을 받는다. 되돌아오는 값이 따로 없는 까닭이 이것이다.
         */
        fun open(owner: Window?, store: SettingsStore, probe: HotkeyProbe) {
            val d = SettingsDialog(owner, store, probe)
            d.build()
            d.dialog.isVisible = true
        }

        /** 시험과 눈으로 보는 확인용. 창을 띄우지 않고 만들어만 둔다. */
        internal fun create(owner: Window?, store: SettingsStore, probe: HotkeyProbe): JDialog {
            val d = SettingsDialog(owner, store, probe)
            d.build()
            return d.dialog
        }
    }
}
