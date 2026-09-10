package com.spellkeyboard.ko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * 소프트 키보드 화면.
 *
 * 키 배열을 코드로 조립한다. 자판이 세 가지(한글/영문/기호)뿐이고 시프트에 따라
 * 라벨만 바뀌어서, XML 레이아웃 여섯 벌을 만드는 것보다 이쪽이 짧고 어긋날 여지가 적다.
 *
 * 상단 한 줄은 방금 무엇을 고쳤는지 보여주는 자리다. 자동 교정은 사용자 몰래 글자를
 * 바꾸는 기능이라, 무엇이 바뀌었는지 보이지 않으면 신뢰를 잃는다.
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
    }

    var listener: Listener? = null

    private var mode = KeyboardMode.KOREAN
    private var shifted = false

    private val statusView: TextView
    private val aiButton: TextView
    private val rowContainer: LinearLayout
    private val repeatHandler = Handler(Looper.getMainLooper())

    private val repeatBackspace = object : Runnable {
        override fun run() {
            listener?.onAction(KeyAction.BACKSPACE)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(color(R.color.keyboard_background))
        setPadding(dp(3), dp(3), dp(3), dp(6))

        statusView = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(color(R.color.status_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            // 서버 오류 메시지가 잘리면 원인을 알 수 없다. 두 줄까지 보여준다.
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = context.getString(R.string.status_idle)
        }
        aiButton = TextView(context).apply {
            text = context.getString(R.string.ai_correct)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = roundRect(color(R.color.key_background))
            isVisible = false
        }
        attachKeyTouch(aiButton, onPress = { listener?.onAiCorrect() })

        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(statusView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(
                aiButton,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(6)
                }
            )
        }
        addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))

        rowContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(rowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        repeatHandler.removeCallbacks(repeatBackspace)
    }

    private fun render() {
        // 화면을 다시 그리면 누르고 있던 키의 뷰가 사라진다. 예약해 둔 길게 누르기나
        // 반복 입력이 그대로 살아 있으면 손을 뗀 뒤에 글자가 튀어나온다.
        repeatHandler.removeCallbacksAndMessages(null)
        rowContainer.removeAllViews()
        val rows = KeyboardLayout.rowsFor(mode, shifted)

        // 1행: 문자만.
        rowContainer.addView(buildRow {
            rows[0].forEach { addCharKey(it) }
        })

        // 2행: 1행보다 짧아 좌우에 반 칸씩 여백을 준다.
        rowContainer.addView(buildRow {
            addSpacer(0.5f)
            rows[1].forEach { addCharKey(it) }
            addSpacer(0.5f)
        })

        // 3행: 시프트 + 문자 + 백스페이스.
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

        // 4행: 자판 전환과 스페이스.
        rowContainer.addView(buildRow {
            val symbolLabel = if (mode == KeyboardMode.SYMBOLS) "가A" else "!#1"
            addActionKey(symbolLabel, KeyAction.SYMBOLS, weight = 1.5f)
            addActionKey(if (mode == KeyboardMode.ENGLISH) "EN" else "한", KeyAction.LANGUAGE, weight = 1.5f)
            addActionKey("", KeyAction.SPACE, weight = 4f)
            addCharKey('.')
            addActionKey("↵", KeyAction.ENTER, weight = 2f, accent = true)
        })
    }

    private fun buildRow(build: LinearLayout.() -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT_DP)).apply {
                topMargin = dp(3)
            }
            weightSum = 10f
        }
        row.build()
        return row
    }

    private fun LinearLayout.addCharKey(c: Char) {
        val alternate = KeyboardLayout.longPressOf(c)
        val view = keyView(c.toString(), keyBackground(false))
        attachKeyTouch(
            view,
            onPress = { listener?.onChar(c) },
            onLongPress = alternate?.let { alt -> { listener?.onLongPressChar(alt) } }
        )
        addView(view, keyParams(1f))
    }

    private fun LinearLayout.addActionKey(
        label: String,
        action: KeyAction,
        weight: Float,
        repeatable: Boolean = false,
        accent: Boolean = false
    ) {
        val view = keyView(label, keyBackground(true, accent))
        attachKeyTouch(
            view,
            onPress = { listener?.onAction(action) },
            onLongPress = null,
            repeatable = repeatable
        )
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 14f else 19f)
            isFocusable = false
            background = keyFace
            // 누름 시점에 처리하느라 onClick 을 쓰지 않는다. 화면 낭독기가 키를
            // 읽을 수 있도록 라벨은 남겨 둔다.
            contentDescription = label
        }

    /**
     * 키를 손가락에 붙인다.
     *
     * **누르는 순간(ACTION_DOWN)에 입력한다.** onClick 은 손을 뗄 때까지 기다리고
     * 이동 거리까지 따져서, 빨리 치면 눌린 게 씹힌 것처럼 느껴진다. 시중 키보드가
     * 전부 누름 시점에 반응하는 이유다. 진동도 같이 준다 — 반응이 왔다는 신호가
     * 빠를수록 키보드가 가볍게 느껴진다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachKeyTouch(
        view: View,
        onPress: () -> Unit,
        onLongPress: (() -> Unit)? = null,
        repeatable: Boolean = false
    ) {
        val longPress = onLongPress?.let { Runnable { it() } }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.isPressed = true
                    target.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    onPress()
                    longPress?.let { repeatHandler.postDelayed(it, LONG_PRESS_MS) }
                    if (repeatable) repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    longPress?.let { repeatHandler.removeCallbacks(it) }
                    repeatHandler.removeCallbacks(repeatBackspace)
                }
            }
            true
        }
    }

    private fun keyBackground(isAction: Boolean, accent: Boolean = false): StateListDrawable {
        val normalColor = when {
            accent -> color(R.color.key_accent_background)
            isAction -> color(R.color.key_action_background)
            else -> color(R.color.key_background)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundRect(color(R.color.key_pressed_background)))
            addState(intArrayOf(), roundRect(normalColor))
        }
    }

    private fun roundRect(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(7).toFloat()
        setColor(fill)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private companion object {
        const val KEY_HEIGHT_DP = 52
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        const val LONG_PRESS_MS = 320L
    }
}
