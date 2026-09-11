package com.spellkeyboard.ko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * 소프트 키보드 화면.
 *
 * 키 배열을 코드로 조립한다. 자판이 세 가지(한글/영문/기호)뿐이고 시프트에 따라
 * 라벨만 바뀌어서, XML 레이아웃 여섯 벌을 만드는 것보다 이쪽이 짧고 어긋날 여지가 적다.
 *
 * 생김새는 삼성 키보드(One UI)를 기준으로 맞췄다 — 흰 글자 키, 한 단계 어두운 기능 키,
 * 늘 떠 있는 숫자 줄, 위쪽 도구 줄. 쓰던 키보드와 자판 위치가 다르면 오타가 나서,
 * 교정을 아무리 잘해도 손에 붙지 않는다.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onChar(c: Char)

        /** 길게 눌러 나온 대체 글자. 방금 넣은 글자를 이것으로 바꾼다. */
        fun onLongPressChar(c: Char)

        fun onAction(action: KeyAction)

        /** 문장 전체를 Gemini API 로 교정한다. */
        fun onAiCorrect()

        /** 클립보드 목록을 열었다. 지금 복사돼 있는 것을 담을 기회다. */
        fun onClipboardOpened()

        /** 목록에서 고른 글을 넣는다. */
        fun onClipboardPaste(text: String)

        /** 목록에서 하나를 지운다. */
        fun onClipboardDelete(text: String)

        fun onOpenSettings()
    }

    var listener: Listener? = null

    private var mode = KeyboardMode.KOREAN
    private var shifted = false

    private val statusView: TextView
    private val aiButton: TextView
    private val clipboardButton: TextView
    private val rowContainer: LinearLayout
    private val clipboardPanel: LinearLayout
    private val clipboardList: LinearLayout
    private val repeatHandler = Handler(Looper.getMainLooper())

    /** 길게 누르는 중에 떠 있는 미리보기. 손을 떼면 여기 글자가 들어간다. */
    private var alternatePopup: PopupWindow? = null
    private var pendingAlternate: Char? = null

    private val repeatBackspace = object : Runnable {
        override fun run() {
            listener?.onAction(KeyAction.BACKSPACE)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(color(R.color.keyboard_background))
        setPadding(dp(2), dp(4), dp(2), dp(6))

        statusView = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(color(R.color.status_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            // 서버 오류 메시지가 잘리면 원인을 알 수 없다. 두 줄까지 보여준다.
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            text = context.getString(R.string.status_idle)
        }
        aiButton = toolbarButton("✨") { listener?.onAiCorrect() }
        clipboardButton = toolbarButton("📋") { showClipboard() }
        val settingsButton = toolbarButton("⚙") { listener?.onOpenSettings() }

        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(aiButton, toolbarParams())
            addView(clipboardButton, toolbarParams())
            addView(settingsButton, toolbarParams())
            addView(statusView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        rowContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(rowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        clipboardList = LinearLayout(context).apply { orientation = VERTICAL }
        clipboardPanel = buildClipboardPanel()
        addView(clipboardPanel, LayoutParams(LayoutParams.MATCH_PARENT, dp(CLIPBOARD_HEIGHT_DP)))

        render()
    }

    /** 상단 줄 문구를 바꾼다. 무엇을 검사했고 무엇을 고쳤는지 보여주는 자리다. */
    fun showStatus(text: String) {
        statusView.text = text
    }

    /** API 키가 설정돼 있을 때만 AI 교정 버튼을 띄운다. */
    fun setAiAvailable(available: Boolean) {
        aiButton.isVisible = available
    }

    fun setMode(newMode: KeyboardMode) {
        if (mode == newMode) return
        mode = newMode
        shifted = false
        render()
    }

    fun toggleShift() {
        if (!KeyboardLayout.supportsShift(mode)) return
        shifted = !shifted
        render()
    }

    fun clearShift() {
        if (!shifted) return
        shifted = false
        render()
    }

    fun currentMode(): KeyboardMode = mode

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatHandler.removeCallbacksAndMessages(null)
        dismissAlternate()
    }

    // --- 클립보드 -------------------------------------------------------------

    private fun buildClipboardPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        isVisible = false

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@KeyboardView.context).apply {
                    text = context.getString(R.string.clipboard_title)
                    setTextColor(color(R.color.key_text))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(10), 0, 0, 0)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(toolbarButton("✕") { hideClipboard() }, toolbarParams())
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        addView(
            ScrollView(this@KeyboardView.context).apply { addView(clipboardList) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /** 클립보드 목록을 채운다. 비어 있으면 왜 비었는지 알려 준다. */
    fun showClipboardItems(items: List<String>) {
        clipboardList.removeAllViews()
        if (items.isEmpty()) {
            clipboardList.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clipboard_empty)
                    setTextColor(color(R.color.status_text))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(12), dp(16), dp(12), dp(16))
                }
            )
            return
        }
        items.forEach { clipboardList.addView(clipboardItemView(it)) }
    }

    private fun clipboardItemView(text: String): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundRect(color(R.color.panel_item_background))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(3), dp(6), dp(3))
        }

        val label = TextView(this@KeyboardView.context).apply {
            this.text = text
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        attachKeyTouch(label, onPress = {
            listener?.onClipboardPaste(text)
            hideClipboard()
        })

        val delete = toolbarButton("✕") { listener?.onClipboardDelete(text) }
        addView(delete, toolbarParams())
    }

    private fun showClipboard() {
        listener?.onClipboardOpened()
        clipboardPanel.isVisible = true
        rowContainer.isVisible = false
    }

    private fun hideClipboard() {
        clipboardPanel.isVisible = false
        rowContainer.isVisible = true
    }

    /** 클립보드가 열려 있으면 닫고 true. 뒤로 가기에서 쓴다. */
    fun closePanels(): Boolean {
        if (!clipboardPanel.isVisible) return false
        hideClipboard()
        return true
    }

    // --- 자판 조립 -------------------------------------------------------------

    private fun render() {
        // 화면을 다시 그리면 누르고 있던 키의 뷰가 사라진다. 예약해 둔 길게 누르기나
        // 반복 입력이 그대로 살아 있으면 손을 뗀 뒤에 글자가 튀어나온다.
        repeatHandler.removeCallbacksAndMessages(null)
        dismissAlternate()
        rowContainer.removeAllViews()
        val rows = KeyboardLayout.rowsFor(mode, shifted)

        // 숫자 줄은 늘 떠 있다. 숫자 하나 넣자고 기호 자판을 오갈 일이 없어진다.
        if (KeyboardLayout.showsNumberRow(mode)) {
            rowContainer.addView(buildRow { KeyboardLayout.NUMBER_ROW.forEach { addCharKey(it) } })
        }

        rowContainer.addView(buildRow { rows[0].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow { rows[1].forEach { addCharKey(it) } })

        // 시프트 + 문자 + 백스페이스.
        rowContainer.addView(buildRow {
            if (KeyboardLayout.supportsShift(mode)) {
                addActionKey(if (shifted) "⬆" else "⇧", KeyAction.SHIFT, weight = 1.5f)
            } else {
                addSpacer(1.5f)
            }
            rows[2].forEach { addCharKey(it) }
            addSpacer((7 - rows[2].length).coerceAtLeast(0) * 1f)
            addActionKey("⌫", KeyAction.BACKSPACE, weight = 1.5f, repeatable = true)
        })

        // 자판 전환과 스페이스.
        rowContainer.addView(buildRow {
            val symbolLabel = if (mode == KeyboardMode.SYMBOLS) "가A" else "!#1"
            addActionKey(symbolLabel, KeyAction.SYMBOLS, weight = 1.4f)
            addActionKey(if (mode == KeyboardMode.ENGLISH) "EN" else "한", KeyAction.LANGUAGE, 1.4f)
            addCharKey(',', weight = 0.9f)
            addActionKey("", KeyAction.SPACE, weight = 3.9f, letterKey = true)
            addCharKey('.', weight = 0.9f)
            addActionKey("↵", KeyAction.ENTER, weight = 1.5f)
        })
    }

    private fun buildRow(build: LinearLayout.() -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT_DP)).apply {
                topMargin = dp(4)
            }
            weightSum = 10f
        }
        row.build()
        return row
    }

    private fun LinearLayout.addCharKey(c: Char, weight: Float = 1f) {
        val alternate = KeyboardLayout.longPressOf(c)
        val view = keyView(c.toString(), keyBackground(isAction = false))
        attachKeyTouch(view, onPress = { listener?.onChar(c) }, alternate = alternate)
        addView(view, keyParams(weight))
    }

    private fun LinearLayout.addActionKey(
        label: String,
        action: KeyAction,
        weight: Float,
        repeatable: Boolean = false,
        letterKey: Boolean = false
    ) {
        val view = keyView(label, keyBackground(isAction = !letterKey))
        attachKeyTouch(view, onPress = { listener?.onAction(action) }, repeatable = repeatable)
        addView(view, keyParams(weight))
    }

    private fun LinearLayout.addSpacer(weight: Float) {
        if (weight <= 0f) return
        addView(View(context), keyParams(weight))
    }

    private fun keyParams(weight: Float) =
        LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }

    // 파라미터 이름을 background 로 두면 apply 블록 안에서 TextView 자신의
    // background 프로퍼티가 먼저 잡힌다. 조용히 배경이 사라지므로 이름을 달리한다.
    private fun keyView(label: String, keyFace: StateListDrawable): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 14f else 20f)
            isFocusable = false
            background = keyFace
            // 누름 시점에 처리하느라 onClick 을 쓰지 않는다. 화면 낭독기가 키를
            // 읽을 수 있도록 라벨은 남겨 둔다.
            contentDescription = label
        }

    private fun toolbarButton(label: String, onPress: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = circle(color(R.color.toolbar_button))
            contentDescription = label
            attachKeyTouch(this, onPress = onPress)
        }

    private fun toolbarParams() =
        LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4); marginEnd = dp(4) }

    /**
     * 키를 손가락에 붙인다.
     *
     * **누르는 순간(ACTION_DOWN)에 입력한다.** onClick 은 손을 뗄 때까지 기다리고
     * 이동 거리까지 따져서, 빨리 치면 눌린 게 씹힌 것처럼 느껴진다. 시중 키보드가
     * 전부 누름 시점에 반응하는 이유다.
     *
     * [alternate] 가 있으면 **꾹 누르는 동안 미리보기를 띄우고, 손을 떼는 순간**
     * 그 글자로 바꾼다. 예전에는 타이머가 울리는 즉시 바꿔 버려서, 바뀐 줄 모르고
     * 계속 누르고 있다 손을 떼면 이미 딴 글자가 들어가 있었다. 삼성 키보드처럼
     * 떼는 순간에 확정해야 무엇이 들어갈지 보고 결정할 수 있다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachKeyTouch(
        view: View,
        onPress: () -> Unit,
        alternate: Char? = null,
        repeatable: Boolean = false
    ) {
        val showPopup = alternate?.let { alt -> Runnable { showAlternate(view, alt) } }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.isPressed = true
                    target.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    onPress()
                    showPopup?.let { repeatHandler.postDelayed(it, LONG_PRESS_MS) }
                    if (repeatable) repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    // 키 밖으로 끌고 나가면 취소한다. 잘못 눌렀을 때 빠져나갈 길이 있어야 한다.
                    if (!insideView(target, event)) {
                        target.isPressed = false
                        showPopup?.let { repeatHandler.removeCallbacks(it) }
                        dismissAlternate()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    target.isPressed = false
                    showPopup?.let { repeatHandler.removeCallbacks(it) }
                    repeatHandler.removeCallbacks(repeatBackspace)
                    // 미리보기가 떠 있었으면 그것이 사용자가 고른 글자다.
                    pendingAlternate?.let { listener?.onLongPressChar(it) }
                    dismissAlternate()
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    showPopup?.let { repeatHandler.removeCallbacks(it) }
                    repeatHandler.removeCallbacks(repeatBackspace)
                    dismissAlternate()
                }
            }
            true
        }
    }

    private fun insideView(view: View, event: MotionEvent): Boolean {
        val bounds = Rect(0, 0, view.width, view.height)
        return bounds.contains(event.x.toInt(), event.y.toInt())
    }

    /** 키 바로 위에 큼직하게 띄운다. 손가락에 가려지면 띄운 의미가 없다. */
    private fun showAlternate(anchor: View, alternate: Char) {
        dismissAlternate()
        val bubble = TextView(context).apply {
            text = alternate.toString()
            gravity = Gravity.CENTER
            setTextColor(color(R.color.popup_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = roundRect(color(R.color.popup_background))
        }
        val popup = PopupWindow(
            bubble,
            anchor.width.coerceAtLeast(dp(40)),
            (anchor.height * 1.35f).toInt(),
            false
        ).apply {
            isClippingEnabled = false
            isTouchable = false
        }
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        popup.showAtLocation(
            this,
            Gravity.NO_GRAVITY,
            location[0],
            location[1] - (anchor.height * 1.15f).toInt()
        )
        alternatePopup = popup
        pendingAlternate = alternate
        anchor.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun dismissAlternate() {
        alternatePopup?.dismiss()
        alternatePopup = null
        pendingAlternate = null
    }

    private fun keyBackground(isAction: Boolean): StateListDrawable {
        val normalColor =
            if (isAction) color(R.color.key_action_background) else color(R.color.key_background)
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundRect(color(R.color.key_pressed_background))
            )
            addState(intArrayOf(), roundRect(normalColor))
        }
    }

    private fun roundRect(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9).toFloat()
        setColor(fill)
    }

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private companion object {
        const val KEY_HEIGHT_DP = 48
        const val CLIPBOARD_HEIGHT_DP = 244
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        const val LONG_PRESS_MS = 300L
    }
}
