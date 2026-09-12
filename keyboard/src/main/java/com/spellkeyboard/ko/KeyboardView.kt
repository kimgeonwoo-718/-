package com.spellkeyboard.ko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
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
import android.widget.ScrollView
import android.widget.TextView
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

        /** 자판 위 '교정' 버튼. 실시간 온디바이스 교정을 끄고 켠다. */
        fun onToggleAutoCorrect()
    }

    var listener: Listener? = null

    private var mode = KeyboardMode.KOREAN
    private var shifted = false

    /** 지금 팔레트. [applyAppearance] 가 설정을 읽어 바꾼다. */
    private var theme: KeyboardTheme = KeyboardTheme.current(context)

    /** 사용자가 고른 배경 사진. 없으면 팔레트의 바탕색. */
    private var photo: Bitmap? = null
    private var photoStamp = -1L
    private var autoCorrectOn = true

    private val statusView: TextView
    private val aiButton: TextView
    private val clipboardButton: TextView
    private val correctionButton: TextView
    private val settingsButton: TextView
    private val clipboardTitle: TextView
    private val rowContainer: LinearLayout
    private val clipboardPanel: LinearLayout
    private val clipboardList: LinearLayout
    private val repeatHandler = Handler(Looper.getMainLooper())

    /** 길게 누르는 중에 떠 있는 미리보기. 손을 떼면 여기 글자가 들어간다. */
    private var bubble: TextView? = null
    private var pendingAlternate: Char? = null

    private val repeatBackspace = object : Runnable {
        override fun run() {
            listener?.onAction(KeyAction.BACKSPACE)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(6))

        statusView = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            // 서버 오류 메시지가 잘리면 원인을 알 수 없다. 두 줄까지 보여준다.
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            text = context.getString(R.string.status_idle)
        }
        aiButton = toolbarButton("✨") { listener?.onAiCorrect() }
        clipboardButton = toolbarButton("📋") { showClipboard() }
        correctionButton = toolbarButton(context.getString(R.string.toolbar_correction)) {
            listener?.onToggleAutoCorrect()
        }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        settingsButton = toolbarButton("⚙") { listener?.onOpenSettings() }

        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(aiButton, toolbarParams())
            addView(clipboardButton, toolbarParams())
            addView(correctionButton, toolbarParams())
            addView(settingsButton, toolbarParams())
            addView(statusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        rowContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            // 손가락 두 개가 서로 다른 줄의 키를 동시에 눌러도 둘 다 받는다.
            isMotionEventSplittingEnabled = true
        }
        addView(rowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        clipboardList = LinearLayout(context).apply { orientation = VERTICAL }
        clipboardTitle = TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), 0, 0, 0)
        }
        clipboardPanel = buildClipboardPanel()
        addView(clipboardPanel, LayoutParams(LayoutParams.MATCH_PARENT, dp(CLIPBOARD_HEIGHT_DP)))

        applyAppearance(force = true)
    }

    /** 상단 줄 문구를 바꾼다. 무엇을 검사했고 무엇을 고쳤는지 보여주는 자리다. */
    fun showStatus(text: String) {
        statusView.text = text
    }

    /** '교정' 버튼의 켜짐 표시. 켜져 있으면 강조색, 꺼져 있으면 흐리게. */
    fun setAutoCorrectOn(on: Boolean) {
        autoCorrectOn = on
        styleCorrectionButton()
    }

    // --- 모양 -------------------------------------------------------------------

    /**
     * 설정(테마·배경 사진)을 다시 읽어 적용한다.
     *
     * 키보드가 뜰 때마다 불린다. 바뀐 게 없으면 아무것도 하지 않는다 — 자판을 다시
     * 조립하는 건 싸지 않고, 사진을 다시 푸는 건 더 비싸다.
     */
    fun applyAppearance(force: Boolean = false) {
        val nextTheme = KeyboardTheme.current(context)
        val nextStamp = BackgroundImage.stamp(context)
        val themeChanged = nextTheme != theme
        val photoChanged = nextStamp != photoStamp
        if (!force && !themeChanged && !photoChanged) return

        theme = nextTheme
        if (photoChanged || force) {
            photo = if (nextStamp == 0L) null else BackgroundImage.load(context)
            photoStamp = nextStamp
        }
        restyle()
        render()
        updateBackground()
    }

    /** 자판 밖의 것들(도구 줄, 클립보드 머리)에 팔레트를 입힌다. 키는 [render] 가 한다. */
    private fun restyle() {
        statusView.setTextColor(theme.status)
        // 사진 위에서는 글자가 묻힌다. 반투명 바탕을 깔아 읽히게 한다.
        statusView.background = if (photo != null) roundRect(withAlpha(theme.background, 0.72f)) else null
        listOf(aiButton, clipboardButton, settingsButton).forEach { styleToolbarButton(it) }
        styleCorrectionButton()
        clipboardTitle.setTextColor(theme.text)
    }

    private fun styleToolbarButton(view: TextView) {
        view.setTextColor(theme.text)
        view.background = circle(theme.toolbarButton)
    }

    private fun styleCorrectionButton() {
        if (autoCorrectOn) {
            correctionButton.setTextColor(theme.onAccent)
            correctionButton.background = circle(theme.accent)
        } else {
            correctionButton.setTextColor(theme.hint)
            correctionButton.background = circle(theme.toolbarButton)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) updateBackground()
    }

    /**
     * 바탕을 깐다. 사진이 있으면 키보드 크기에 맞춰 가운데를 잘라 쓴다.
     *
     * 크기가 정해진 뒤에만 그릴 수 있어서 [onSizeChanged] 에서도 부른다. 클립보드를
     * 열면 높이가 바뀌므로 그때도 다시 잘린다.
     */
    private fun updateBackground() {
        val source = photo
        if (source == null || width <= 0 || height <= 0) {
            background = ColorDrawable(theme.background)
            return
        }
        val scale = maxOf(width / source.width.toFloat(), height / source.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        }
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        background = BitmapDrawable(resources, cropped)
    }

    /** 사진 위에서는 키를 살짝 비쳐 보이게 한다. 사진을 깔았는데 안 보이면 깐 의미가 없다. */
    private fun keyFill(color: Int): Int = if (photo != null) withAlpha(color, 0.86f) else color

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

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
            addView(clipboardTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
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
                    setTextColor(theme.status)
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
        background = roundRect(keyFill(theme.panelItem))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(3), dp(6), dp(3))
        }

        val label = TextView(this@KeyboardView.context).apply {
            this.text = text
            setTextColor(theme.text)
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

        // 둘째 줄은 9 개라 한 칸이 남는다. 좌우에 반 칸씩 둬서 **가운데**에 놓는다 —
        // 그냥 두면 왼쪽으로 쏠려서 위아래 줄과 키 위치가 어긋나고, 그 어긋남이
        // 그대로 오타가 된다.
        rowContainer.addView(buildRow {
            addSpacer(0.5f)
            rows[1].forEach { addCharKey(it) }
            addSpacer(0.5f)
        })

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
            addActionKey(symbolLabel, KeyAction.SYMBOLS, weight = 1.35f)
            val language = if (mode == KeyboardMode.ENGLISH) "EN" else "한/영"
            addActionKey(language, KeyAction.LANGUAGE, weight = 1.05f)
            addCharKey(',', weight = 0.9f)
            addActionKey("", KeyAction.SPACE, weight = 4.35f, letterKey = true)
            addCharKey('.', weight = 0.9f)
            addActionKey("↵", KeyAction.ENTER, weight = 1.45f)
        })
    }

    private fun buildRow(build: LinearLayout.() -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
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
            setTextColor(theme.text)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            contentDescription = label
            attachKeyTouch(this, onPress = onPress)
            styleToolbarButton(this)
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
        // **이 상태는 키마다 따로 있어야 한다.** 빠르게 치면 앞 손가락이 떨어지기 전에
        // 다음 손가락이 닿아서 두 키가 동시에 눌린 상태가 된다. 상태를 하나로 공유하면
        // 나중 키를 떼는 순간 앞 키의 대체 글자가 들어가고, 앞 키를 떼면 아무 일도
        // 일어나지 않는다 — 엉뚱한 글자가 들어가거나 씹힌 것처럼 보인다.
        var previewing = false
        val showPreview = alternate?.let { alt ->
            Runnable {
                showAlternate(view, alt)
                previewing = true
            }
        }

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    previewing = false
                    target.isPressed = true
                    target.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    onPress()
                    showPreview?.let { repeatHandler.postDelayed(it, LONG_PRESS_MS) }
                    if (repeatable) repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    // 키 밖으로 끌고 나가면 대체 글자만 취소한다. 이미 들어간 글자는
                    // 그대로 둔다 — 여기서 지우면 빠르게 칠 때 글자가 사라진다.
                    if (previewing && !insideView(target, event)) {
                        showPreview?.let { repeatHandler.removeCallbacks(it) }
                        previewing = false
                        dismissAlternate()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    target.isPressed = false
                    showPreview?.let { repeatHandler.removeCallbacks(it) }
                    repeatHandler.removeCallbacks(repeatBackspace)
                    // 미리보기를 띄운 것이 **이 키** 일 때만 갈아 끼운다.
                    if (previewing && alternate != null) listener?.onLongPressChar(alternate)
                    if (previewing) dismissAlternate()
                    previewing = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    showPreview?.let { repeatHandler.removeCallbacks(it) }
                    repeatHandler.removeCallbacks(repeatBackspace)
                    if (previewing) dismissAlternate()
                    previewing = false
                }
            }
            true
        }
    }

    private fun insideView(view: View, event: MotionEvent): Boolean {
        val bounds = Rect(0, 0, view.width, view.height)
        return bounds.contains(event.x.toInt(), event.y.toInt())
    }

    /**
     * 키 바로 위에 큼직하게 띄운다. 손가락에 가려지면 띄운 의미가 없다.
     *
     * ## PopupWindow 를 쓰지 않는 이유
     *
     * 미리보기를 별도 윈도우로 띄우면 입력기 윈도우의 포커스가 흔들리고, 그 바람에
     * `onStartInput` 이 다시 불려 `session.reset()` 이 조합 상태를 날린다. 그러면
     * 쌍자음 다음에 친 모음이 새 글자로 시작해 `ㄲ우` 가 된다 — 실기기에서 "쌍자음이
     * 모음이랑 안 합쳐진다" 로 나타났다. 그래서 윈도우를 만들지 않고 키보드 자신의
     * 오버레이에 그린다. 입력 상태를 건드릴 일이 아예 없다.
     */
    private fun showAlternate(anchor: View, alternate: Char) {
        dismissAlternate()
        val width = anchor.width.coerceAtLeast(dp(44))
        val height = (anchor.height * 1.25f).toInt()

        val view = TextView(context).apply {
            text = alternate.toString()
            gravity = Gravity.CENTER
            setTextColor(theme.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = roundRect(theme.accent)
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }

        // 키의 위치를 키보드 기준 좌표로 옮긴다.
        val keyAt = IntArray(2).also { anchor.getLocationInWindow(it) }
        val selfAt = IntArray(2).also { getLocationInWindow(it) }
        val left = keyAt[0] - selfAt[0] - (width - anchor.width) / 2
        // 오버레이는 키보드 밖으로 못 나간다. 맨 윗줄이면 잘리지 않게 끌어내린다.
        val top = (keyAt[1] - selfAt[1] - height - dp(4)).coerceAtLeast(0)
        view.layout(left, top, left + width, top + height)

        overlay.add(view)
        bubble = view
        pendingAlternate = alternate
        anchor.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun dismissAlternate() {
        bubble?.let { overlay.remove(it) }
        bubble = null
        pendingAlternate = null
    }

    private fun keyBackground(isAction: Boolean): StateListDrawable {
        val normalColor = keyFill(if (isAction) theme.actionKey else theme.key)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundRect(theme.pressed))
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

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private companion object {
        const val KEY_HEIGHT_DP = 46
        const val CLIPBOARD_HEIGHT_DP = 244
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        /**
         * 이만큼 누르고 있어야 대체 글자가 뜬다.
         *
         * 450ms 로 늘렸다가 "쌍자음 낼 때 너무 느리다" 는 말을 듣고 되돌렸다. 짧으면
         * 의도치 않게 쌍자음이 들어갈 수 있지만, 쌍자음을 자주 쓰는 쪽이 체감이 크다.
         * 대신 키를 떼는 순간에 확정하므로, 미리보기가 뜬 걸 보고 손가락을 옆으로
         * 빼면 취소된다.
         */
        const val LONG_PRESS_MS = 300L
    }
}
