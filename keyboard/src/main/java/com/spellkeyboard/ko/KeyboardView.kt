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
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

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
        fun onAction(action: KeyAction)
    }

    var listener: Listener? = null

    private var mode = KeyboardMode.KOREAN
    private var shifted = false

    private val statusView: TextView
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            text = context.getString(R.string.status_idle)
        }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))

        rowContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(rowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render()
    }

    /** 상단 줄에 방금 고친 내용을 보여준다. null 이면 기본 안내로 되돌린다. */
    fun showCorrection(before: String?, after: String?) {
        statusView.text = if (before == null || after == null) {
            context.getString(R.string.status_idle)
        } else {
            context.getString(R.string.status_corrected, before.trim(), after.trim())
        }
    }

    /** 교정이 꺼진 입력란(비밀번호 등)임을 알린다. */
    fun showCorrectionDisabled() {
        statusView.text = context.getString(R.string.status_disabled)
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
        addView(keyView(c.toString(), keyBackground(false)) { listener?.onChar(c) }, keyParams(1f))
    }

    private fun LinearLayout.addActionKey(
        label: String,
        action: KeyAction,
        weight: Float,
        repeatable: Boolean = false,
        accent: Boolean = false
    ) {
        val view = keyView(label, keyBackground(true, accent)) { listener?.onAction(action) }
        if (repeatable) attachBackspaceRepeat(view)
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
    private fun keyView(label: String, keyFace: StateListDrawable, onPress: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 14f else 19f)
            isClickable = true
            isFocusable = false
            background = keyFace
            setOnClickListener { onPress() }
        }

    /** 길게 누르면 반복 입력되는 백스페이스. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachBackspaceRepeat(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    repeatHandler.removeCallbacks(repeatBackspace)
            }
            // false 를 돌려줘야 첫 입력은 평소대로 onClick 으로 들어간다.
            false
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
        const val KEY_HEIGHT_DP = 48
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
