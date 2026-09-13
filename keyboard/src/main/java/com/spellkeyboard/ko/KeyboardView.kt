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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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

        /** 문장 전체를 중계 서버(AI)로 교정한다. */
        fun onAiCorrect()

        /** 클립보드 목록을 열었다. 지금 복사돼 있는 것을 담을 기회다. */
        fun onClipboardOpened()

        /** 목록에서 고른 글을 넣는다. */
        fun onClipboardPaste(text: String)

        /** 목록에서 하나를 지운다. */
        fun onClipboardDelete(text: String)

        fun onOpenSettings()

        /** 자판 위 '교정' 동그라미. 실시간 교정을 끄고 켠다. */
        fun onToggleAutoCorrect()

        /** 번역 입력줄 열기/닫기. */
        fun onToggleTranslate()

        /** 번역 입력줄의 언어 칩을 눌러 다음 언어로. */
        fun onCycleTranslateTarget()

        /** 자판 위 '교정' 버튼. 실시간 온디바이스 교정을 끄고 켠다. */

        /** 스페이스를 꾹 누른 채 밀어서 커서를 [delta] 글자만큼 옮긴다. 음수면 왼쪽. */
        fun onMoveCursor(delta: Int)

        /** 커서 이동 모드에 들어가거나 나왔다. 상단 줄 안내를 바꿀 기회다. */
        fun onCursorModeChanged(active: Boolean)

        /**
         * 연타로 도는 부호 키(천지인 `.,?!`, 숫자 판 `.,-/`). [options] 순서로 돈다.
         * 시간 판단은 받는 쪽 몫.
         */
        fun onPunctuationCycle(options: String)

        /** 이모티콘 판에서 하나 골랐다. 여러 코드 단위일 수 있다. */
        fun onEmoji(text: String)

        /**
         * 천지인 낱자 키. 두벌식과 달리 **전용 통로**로 보낸다 — onChar 로 보내면
         * 서비스가 어느 자판인지 다시 판별해야 하고, 그 판별이 어긋나면 점(ㆍ)이 그냥
         * 글자로 박혀 모음이 안 만들어진다(실기기에서 "안 모아짐"). 여기로 오면 무조건
         * 천지인 오토마타로 간다.
         */
        fun onCheonjiinKey(key: Char)
    }

    var listener: Listener? = null

    private var mode = KeyboardMode.KOREAN
    private var shifted = false
    private var symbolPage = 0

    /** 지금 팔레트. [applyAppearance] 가 설정을 읽어 바꾼다. */
    private var theme: KeyboardTheme = KeyboardTheme.current(context)

    /** 사용자가 고른 배경 사진. 없으면 팔레트의 바탕색. */
    private var photo: Bitmap? = null
    private var photoStamp = -1L
    private var layoutType: LayoutType = Prefs.layoutType(context)
    private var keyAlpha = alphaFor(Prefs.keyTransparency(context))

    private val emojiButton: TextView
    private val aiButton: TextView
    private val clipboardButton: TextView
    private val correctionButton: TextView
    private var autoCorrectOn = true
    private val translateButton: TextView
    private var translateOn = false
    private val translatePanel: LinearLayout
    private val translateSource: TextView
    private val translateTarget: TextView
    private val settingsButton: TextView
    private val clipboardTitle: TextView
    private val rowContainer: LinearLayout
    private val clipboardPanel: LinearLayout
    private val clipboardList: LinearLayout
    private val emojiPanel: LinearLayout
    private val emojiList: LinearLayout
    private val emojiTitle: TextView

    /**
     * 잡고 있는 스페이스. 다른 키가 눌리는 순간 이걸 먼저 넣는다.
     *
     * 스페이스는 떼는 순간에 넣는데(꾹 누르면 커서 이동이라), 빨리 치면 스페이스를 떼기
     * 전에 다음 키가 먼저 들어가 "다 ㅁ" 이 "다ㅁ " 이 된다 — 220타 사용자가 "키가 씹힌다"
     * 로 겪은 것. 삼성처럼 다음 키의 DOWN 에서 잡고 있던 스페이스를 확정한다.
     */
    private var heldSpaceFlush: (() -> Unit)? = null
    private val repeatHandler = Handler(Looper.getMainLooper())

    /**
     * 지금 자판에 올라 있는 키들. [KeyPad] 가 여기서 손가락 아래 키를 찾는다.
     * 자판을 다시 그릴 때마다 비운다.
     */
    private val keySlots = ArrayList<PadSlot>()

    /** 손가락마다 어느 키를 잡고 있는지. 두 손가락이 동시에 눌려도 섞이지 않는다. */
    private val activeSlots = android.util.SparseArray<PadSlot>()

    /** 잠깐 떴다 사라지는 알림. [flash] 참고. */
    private var flashView: TextView? = null
    private val hideFlash = Runnable { dismissFlash() }

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
        setPadding(0, dp(2), 0, 0)

        // 컬러 이모지는 삼성 키보드의 단색 선 아이콘과 톤이 어긋난다. 글꼴에 든 기호를 쓴다.
        emojiButton = toolbarButton("☺\uFE0E") { showEmoji() }
        // AI 는 그림 대신 글자 "AI". 삼성의 ✨ 자리에 들어가는 우리 기능이라 이름을 그대로 쓴다.
        aiButton = toolbarButton("AI") { listener?.onAiCorrect() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        clipboardButton = toolbarButton("▤") { showClipboard() }
        // 실시간 교정 켬/끔. 켜져 있으면 강조색 동그라미라 글자 없이도 상태가 보인다.
        correctionButton = toolbarButton(context.getString(R.string.toolbar_correction)) {
            listener?.onToggleAutoCorrect()
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        // 번역 입력줄 열기/닫기. 열려 있으면 '교정' 처럼 강조색.
        translateButton = toolbarButton(context.getString(R.string.toolbar_translate)) {
            listener?.onToggleTranslate()
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        settingsButton = toolbarButton("⚙\uFE0E") { listener?.onOpenSettings() }

        // 삼성처럼 동그라미들을 줄 전체에 고르게 펼친다. 사이와 양끝의 빈칸이 같은 무게라
        // 간격이 저절로 같아진다.
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            listOf(emojiButton, aiButton, clipboardButton, correctionButton, translateButton, settingsButton).forEach { button ->
                addView(View(context), LayoutParams(0, 1, 1f))
                addView(button, toolbarParams())
            }
            addView(View(context), LayoutParams(0, 1, 1f))
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(TOOLBAR_HEIGHT_DP)))

        // 번역 입력줄. 지보드처럼 자판 바로 위에 열리고, 여기 쓴 한국어가 앱에는 번역돼 들어간다.
        translateSource = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        translateTarget = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            attachKeyTouch(this, onPress = { listener?.onCycleTranslateTarget() })
        }
        translatePanel = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isVisible = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(translateSource, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(translateTarget, LayoutParams(LayoutParams.WRAP_CONTENT, dp(TOOLBAR_BUTTON_DP)))
            addView(View(context), LayoutParams(dp(6), 1))
            addView(toolbarButton("✕") { listener?.onToggleTranslate() }, toolbarParams())
        }
        addView(translatePanel, LayoutParams(LayoutParams.MATCH_PARENT, dp(TRANSLATE_HEIGHT_DP)))

        rowContainer = KeyPad(context).apply {
            orientation = VERTICAL
            // 자판 둘레의 여백. **판 바깥이 아니라 판 안쪽 여백이어야 한다** — 그래야
            // 화면 맨 아래나 좌우 가장자리를 눌러도 가까운 키가 받는다.
            setPadding(dp(2), 0, dp(2), dp(6))
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

        emojiList = LinearLayout(context).apply { orientation = VERTICAL }
        emojiTitle = TextView(context).apply {
            text = context.getString(R.string.emoji_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        }
        emojiPanel = buildEmojiPanel()
        addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, dp(CLIPBOARD_HEIGHT_DP)))

        applyAppearance(force = true)
    }

    /** '교정' 동그라미의 켜짐 표시. 켜져 있으면 강조색, 꺼져 있으면 흐리게. */
    fun setAutoCorrectOn(on: Boolean) {
        autoCorrectOn = on
        styleCorrectionButton()
    }

    /** AI 가 도는 동안 AI 버튼을 흐리게. */
    fun setAiBusy(busy: Boolean) {
        aiButton.alpha = if (busy) 0.35f else 1f
    }

    /** 번역 입력줄을 열거나 닫는다. [targetLabel] 은 언어 칩에 쓰는 이름("영어"). */
    fun setTranslateMode(on: Boolean, targetLabel: String) {
        translateOn = on
        translateTarget.text = "$targetLabel ▾"
        translatePanel.isVisible = on
        if (on) closePanels()
        styleTranslateButton()
    }

    /** 입력줄에 지금까지 쓴 한국어. 비면 [hint] 가 흐리게 보인다. */
    fun setTranslateSource(text: String, hint: String) {
        translateSource.text = text
        translateSource.hint = hint
    }

    private fun styleTranslateButton() {
        if (translateOn) {
            translateButton.setTextColor(theme.onAccent)
            translateButton.background = circle(theme.accent)
        } else {
            styleToolbarButton(translateButton)
        }
        translateSource.setTextColor(theme.text)
        translateSource.setHintTextColor(theme.hint)
        translateTarget.setTextColor(theme.onAccent)
        translateTarget.background = roundRect(theme.accent)
        translatePanel.background = roundRect(keyFill(theme.panelItem))
    }

    /**
     * 자판 위에 작은 알림을 잠깐 띄웠다 지운다.
     *
     * 자판에 글을 상시로 두지 않기로 했으므로, 꼭 알아야 할 것(눌렀는데 안 된 이유,
     * 고칠 게 없었다는 것)만 이렇게 스쳐 지나가게 한다. 시스템 토스트는 자판 아래
     * 엉뚱한 데 뜨고 키를 가려서 쓰지 않는다. 오버레이에 그리므로 자판 배치는 안 흔들린다.
     */
    fun flash(text: String) {
        dismissFlash()
        val view = TextView(context).apply {
            this.text = text
            setTextColor(theme.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundRect(withAlpha(theme.text, 0.88f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            measure(
                MeasureSpec.makeMeasureSpec(width - dp(24), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val left = (width - view.measuredWidth) / 2
        val top = (dp(TOOLBAR_HEIGHT_DP) - view.measuredHeight) / 2 + dp(2)
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
        view.alpha = 0f
        overlay.add(view)
        view.animate().alpha(1f).setDuration(120).start()
        flashView = view
        repeatHandler.postDelayed(hideFlash, FLASH_MS)
    }

    private fun dismissFlash() {
        repeatHandler.removeCallbacks(hideFlash)
        flashView?.let { view ->
            view.animate().alpha(0f).setDuration(220).withEndAction { overlay.remove(view) }.start()
        }
        flashView = null
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
        val nextLayout = Prefs.layoutType(context)
        val nextAlpha = alphaFor(Prefs.keyTransparency(context))
        val themeChanged = nextTheme != theme
        val photoChanged = nextStamp != photoStamp
        val layoutChanged = nextLayout != layoutType
        val alphaChanged = nextAlpha != keyAlpha
        if (!force && !themeChanged && !photoChanged && !layoutChanged && !alphaChanged) return

        theme = nextTheme
        layoutType = nextLayout
        keyAlpha = nextAlpha
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
        listOf(emojiButton, aiButton, clipboardButton, settingsButton).forEach { styleToolbarButton(it) }
        styleCorrectionButton()
        styleTranslateButton()
        clipboardTitle.setTextColor(theme.text)
        emojiTitle.setTextColor(theme.text)
    }

    private fun styleToolbarButton(view: TextView) {
        view.setTextColor(theme.text)
        view.background = circle(keyFill(theme.toolbarButton))
    }

    private fun styleCorrectionButton() {
        if (autoCorrectOn) {
            correctionButton.setTextColor(theme.onAccent)
            correctionButton.background = circle(theme.accent)
        } else {
            correctionButton.setTextColor(theme.hint)
            correctionButton.background = circle(keyFill(theme.toolbarButton))
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

    /**
     * 사진 위에서는 키를 반투명하게 한다. 사진을 깔았는데 안 보이면 깐 의미가 없다.
     * 86% → 50% → 30% 로, "사진이 더 보여야 한다" 는 말을 두 번 듣고 내렸다. 글자는
     * 진한 색 그대로라 키가 거의 비쳐도 읽힌다.
     */
    private fun keyFill(color: Int): Int = if (photo != null) withAlpha(color, keyAlpha) else color

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
        symbolPage = 0
        render()
    }

    /** 기호 페이지를 넘긴다(1/2 ↔ 2/2). */
    fun flipSymbolPage() {
        symbolPage = (symbolPage + 1) % symbolPageCount()
        render()
    }

    private fun symbolPageCount(): Int =
        if (layoutType == LayoutType.CHEONJIIN) KeyboardLayout.CHEONJIIN_SYMBOL_PAGES.size
        else KeyboardLayout.SYMBOL_PAGES.size

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
        setPadding(dp(2), 0, dp(2), dp(6))

        clipboardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        clipboardTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
            addView(clipboardTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(toolbarButton("✕") { hideClipboard() }, toolbarParams())
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        clipboardList.setPadding(dp(4), 0, dp(4), dp(6))
        addView(
            ScrollView(this@KeyboardView.context).apply {
                isVerticalScrollBarEnabled = false
                addView(clipboardList)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /**
     * 클립보드 목록을 삼성처럼 2열 카드 격자로 채운다. 비어 있으면 왜 비었는지 알려 준다.
     */
    fun showClipboardItems(items: List<String>) {
        clipboardList.removeAllViews()
        if (items.isEmpty()) {
            clipboardList.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clipboard_empty)
                    setTextColor(theme.status)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(28), dp(24), dp(28))
                }
            )
            return
        }
        // 두 개씩 한 줄. 홀수면 마지막 칸은 빈 자리로 폭을 맞춘다.
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            pair.forEach { row.addView(clipboardCard(it), cardParams()) }
            if (pair.size == 1) row.addView(View(context), cardParams())
            clipboardList.addView(row)
        }
    }

    private fun cardParams() = LayoutParams(0, dp(CLIPBOARD_CARD_HEIGHT_DP), 1f).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    /** 카드 하나 — 눌러 붙여넣기, 오른쪽 위 작은 ✕ 로 삭제. */
    private fun clipboardCard(text: String): View {
        val card = android.widget.FrameLayout(context).apply {
            background = roundRect(keyFill(theme.panelItem))
        }
        val label = TextView(context).apply {
            this.text = text
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(label, android.widget.FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))
        attachKeyTouch(card, onPress = {
            listener?.onClipboardPaste(text)
            hideClipboard()
        })

        val delete = TextView(context).apply {
            this.text = "✕"
            gravity = Gravity.CENTER
            setTextColor(theme.status)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = circle(theme.background)
            attachKeyTouch(this, onPress = { listener?.onClipboardDelete(text) })
        }
        card.addView(delete, android.widget.FrameLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(6), dp(6), 0)
        })
        return card
    }

    private fun showClipboard() {
        listener?.onClipboardOpened()
        emojiPanel.isVisible = false
        clipboardPanel.isVisible = true
        rowContainer.isVisible = false
    }

    private fun hideClipboard() {
        clipboardPanel.isVisible = false
        rowContainer.isVisible = true
    }

    /** 클립보드나 이모티콘 판이 열려 있으면 닫고 true. 뒤로 가기에서 쓴다. */
    fun closePanels(): Boolean {
        if (!clipboardPanel.isVisible && !emojiPanel.isVisible) return false
        clipboardPanel.isVisible = false
        emojiPanel.isVisible = false
        rowContainer.isVisible = true
        return true
    }

    // --- 이모티콘 -------------------------------------------------------------

    private fun buildEmojiPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        isVisible = false
        setPadding(dp(2), 0, dp(2), dp(6))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
            addView(emojiTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            // 판을 닫지 않고 지울 수 있어야 한다. 하나 넣고 보니 아니다 싶은 게 잦다.
            addView(toolbarButton("⌫") { listener?.onAction(KeyAction.BACKSPACE) }, toolbarParams())
            addView(toolbarButton("✕") { closePanels() }, toolbarParams())
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        emojiList.setPadding(dp(4), 0, dp(4), dp(6))
        addView(
            ScrollView(this@KeyboardView.context).apply {
                isVerticalScrollBarEnabled = false
                addView(emojiList)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun showEmoji() {
        if (emojiList.childCount == 0) fillEmoji()
        clipboardPanel.isVisible = false
        emojiPanel.isVisible = true
        rowContainer.isVisible = false
    }

    /** 갈래 이름 한 줄, 그 아래 8열 격자. 처음 열 때 한 번만 만든다. */
    private fun fillEmoji() {
        EmojiSet.CATEGORIES.forEach { category ->
            emojiList.addView(
                TextView(context).apply {
                    text = category.name
                    setTextColor(theme.status)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(8), dp(8), dp(8), dp(2))
                }
            )
            category.items.chunked(EMOJI_COLUMNS).forEach { line ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                line.forEach { emoji ->
                    val cell = TextView(context).apply {
                        text = emoji
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        contentDescription = emoji
                    }
                    attachKeyTouch(cell, onPress = { listener?.onEmoji(emoji) })
                    row.addView(cell, LayoutParams(0, dp(EMOJI_CELL_DP), 1f))
                }
                repeat(EMOJI_COLUMNS - line.size) { row.addView(View(context), LayoutParams(0, dp(EMOJI_CELL_DP), 1f)) }
                emojiList.addView(row)
            }
        }
    }

    // --- 자판 조립 -------------------------------------------------------------

    private fun render() {
        // 화면을 다시 그리면 누르고 있던 키의 뷰가 사라진다. 예약해 둔 길게 누르기나
        // 반복 입력이 그대로 살아 있으면 손을 뗀 뒤에 글자가 튀어나온다.
        repeatHandler.removeCallbacksAndMessages(null)
        dismissAlternate()
        // 사라질 뷰를 가리키는 키가 남아 있으면, 손을 떼는 순간 없는 키가 입력된다.
        keySlots.clear()
        activeSlots.clear()
        rowContainer.removeAllViews()
        if (layoutType == LayoutType.CHEONJIIN) {
            when (mode) {
                KeyboardMode.KOREAN -> return renderCheonjiin()
                KeyboardMode.NUMPAD -> return renderNumpad()
                KeyboardMode.SYMBOLS -> return renderCheonjiinSymbols()
                KeyboardMode.ENGLISH -> Unit
            }
        }
        if (mode == KeyboardMode.SYMBOLS || mode == KeyboardMode.NUMPAD) {
            renderSymbols()
            return
        }
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

    /**
     * 천지인 한글 판. 삼성 배열 그대로:
     *
     *     ㅣ¹  ㆍ²  ㅡ³   ⌫
     *     ㄱㅋ⁴ ㄴㄹ⁵ ㄷㅌ⁶  ↵
     *     ㅂㅍ⁷ ㅅㅎ⁸ ㅈㅊ⁹  .,?!
     *     !#1 한/영 ㅇㅁ⁰  ␣   ,
     *
     * 줄이 넷뿐이라 쿼티(다섯 줄)와 높이를 맞추려고 키를 더 높게 둔다.
     */
    private fun renderCheonjiin() {
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addPunctuationKey(".,?!", KeyboardLayout.CHEONJIIN_PUNCTUATION) }
        )
        KeyboardLayout.CHEONJIIN_GRID.forEachIndexed { index, gridRow ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                gridRow.forEach { addCheonjiinKey(it) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addActionKey("!#1", KeyAction.NUMPAD, weight = GRID_W / 2)
            addActionKey("한/영", KeyAction.LANGUAGE, weight = GRID_W / 2)
            addCheonjiinKey(KeyboardLayout.CHEONJIIN_ZERO_KEY)
            // 삼성처럼 스페이스가 "다음 글자" 를 겸한다. 조합 중이면 한 번 눌러 글자를
            // 끊고, 한 번 더 누르면 그때 띄어쓰기다. 처리는 SpellKeyboardService 쪽에 있다.
            addActionKey("", KeyAction.SPACE, weight = GRID_W, letterKey = true)
            addCharKey(',', weight = GRID_W)
        })
    }

    /**
     * 천지인 숫자 판(삼성의 !#1). 1~9 · ⌫ ↵ .,-/ · 넷째 줄 !@# 가 0 ␣ ,
     */
    private fun renderNumpad() {
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addPunctuationKey(".,-/", KeyboardLayout.NUMPAD_PUNCTUATION) }
        )
        KeyboardLayout.CHEONJIIN_NUMPAD.forEachIndexed { index, digits ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                digits.forEach { addCharKey(it, weight = GRID_W) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addActionKey("!@#", KeyAction.SYMBOLS, weight = GRID_W / 2)
            addActionKey("가", KeyAction.KOREAN, weight = GRID_W / 2)
            addCharKey('0', weight = GRID_W)
            addActionKey("", KeyAction.SPACE, weight = GRID_W, letterKey = true)
            addCharKey(',', weight = GRID_W)
        })
    }

    /**
     * 천지인 기호 판(삼성의 !@#). 6열 × 3줄 + 오른쪽 기능 열, 넷째 줄 123 가 1/3 ␣ ,
     */
    private fun renderCheonjiinSymbols() {
        val pages = KeyboardLayout.CHEONJIIN_SYMBOL_PAGES
        val page = pages[symbolPage % pages.size]
        val symbolW = (10f - GRID_W) / 6f
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addPunctuationKey(".,?!", KeyboardLayout.CHEONJIIN_PUNCTUATION) }
        )
        page.forEachIndexed { index, symbols ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                symbols.forEach { addCharKey(it, weight = symbolW) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addActionKey("123", KeyAction.NUMPAD, weight = symbolW)
            addActionKey("가", KeyAction.KOREAN, weight = symbolW)
            addActionKey("${symbolPage % pages.size + 1}/${pages.size}", KeyAction.SYMBOL_PAGE, weight = symbolW * 2)
            addActionKey("", KeyAction.SPACE, weight = symbolW * 2, letterKey = true)
            addCharKey(',', weight = GRID_W)
        })
    }

    /** 천지인 낱자 키. 오른쪽 위 숫자 힌트, 길게 누르면 그 숫자. */
    private fun LinearLayout.addCheonjiinKey(key: CheonjiinKey) {
        val view = hintedKeyView(key.label, key.hint, if (key.label.length > 1) 19f else 22f)
        addPadKey(view, GRID_W, charTouch(view, onPress = { listener?.onCheonjiinKey(key.key) }, alternate = key.hint))
    }

    /**
     * 큰 글자 가운데, 작은 힌트 오른쪽 위. 배경과 눌림 상태는 바깥 틀이 받는다.
     */
    private fun hintedKeyView(label: String, hint: Char, textSp: Float): View =
        android.widget.FrameLayout(context).apply {
            background = keyBackground(isAction = false)
            contentDescription = label
            addView(
                TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(theme.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                },
                android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            addView(
                TextView(context).apply {
                    text = hint.toString()
                    setTextColor(theme.hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                },
                android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(4), dp(7), 0)
                }
            )
        }

    private fun LinearLayout.addPunctuationKey(label: String, options: String) {
        val view = keyView(label, keyBackground(isAction = true), textSp = 15f)
        addPadKey(view, GRID_W, charTouch(view, onPress = { listener?.onPunctuationCycle(options) }))
    }

    /**
     * 삼성식 기호 자판. 숫자·기호 3줄 + [1/2 · 특수 7키 · ⌫] 줄 + 바닥 기능 줄.
     */
    private fun renderSymbols() {
        val page = KeyboardLayout.SYMBOL_PAGES[symbolPage]
        rowContainer.addView(buildRow { page.rows[0].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow { page.rows[1].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow { page.rows[2].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow {
            val label = "${symbolPage + 1}/${KeyboardLayout.SYMBOL_PAGES.size}"
            addActionKey(label, KeyAction.SYMBOL_PAGE, weight = 1.5f)
            page.extra.forEach { addCharKey(it, weight = 1f) }
            addSpacer((7 - page.extra.length).coerceAtLeast(0) * 1f)
            addActionKey("⌫", KeyAction.BACKSPACE, weight = 1.5f, repeatable = true)
        })
        rowContainer.addView(buildRow {
            addActionKey("가A", KeyAction.SYMBOLS, weight = 1.35f)
            addActionKey("한/영", KeyAction.LANGUAGE, weight = 1.05f)
            addCharKey(',', weight = 0.9f)
            addActionKey("", KeyAction.SPACE, weight = 4.35f, letterKey = true)
            addCharKey('.', weight = 0.9f)
            addActionKey("↵", KeyAction.ENTER, weight = 1.45f)
        })
    }

    private fun buildRow(heightDp: Int = KEY_HEIGHT_DP, build: LinearLayout.() -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
                topMargin = dp(ROW_GAP_DP)
            }
            weightSum = 10f
        }
        row.build()
        return row
    }

    private fun LinearLayout.addCharKey(c: Char, weight: Float = 1f) {
        val alternate = KeyboardLayout.longPressOf(c)
        val view = keyView(c.toString(), keyBackground(isAction = false))
        addPadKey(view, weight, charTouch(view, onPress = { listener?.onChar(c) }, alternate = alternate))
    }

    private fun LinearLayout.addActionKey(
        label: String,
        action: KeyAction,
        weight: Float,
        repeatable: Boolean = false,
        letterKey: Boolean = false
    ) {
        val view = keyView(label, keyBackground(isAction = !letterKey))
        val touch =
            if (action == KeyAction.SPACE) spaceTouch(view)
            else charTouch(view, onPress = { listener?.onAction(action) }, repeatable = repeatable)
        addPadKey(view, weight, touch)
    }

    /**
     * 키를 줄에 넣고 판에 등록한다. **뷰에 리스너를 달지 않는다** — 손가락을 어느 키에
     * 줄지는 [KeyPad] 가 정한다.
     */
    private fun LinearLayout.addPadKey(view: View, weight: Float, touch: KeyTouch) {
        keySlots += PadSlot(view, touch)
        addView(view, keyParams(weight))
    }

    /**
     * 스페이스만 다르게 다룬다: **손을 뗄 때** 띄어쓰기가 들어가고, 꾹 누르면 커서 이동
     * 모드다. 누르는 순간에 넣어 버리면 꾹 눌렀을 때 이미 들어간 공백을 도로 빼야 하고,
     * 그 공백이 교정까지 한 번 돌린 뒤라 되돌리기가 지저분하다. 삼성 키보드도 스페이스는
     * 떼는 순간에 넣는다.
     */
    private fun spaceTouch(key: View): KeyTouch = object : KeyTouch {
        private var cursorMode = false
        private var consumed = false
        private var anchorX = 0f
        private val stepPx = dp(CURSOR_STEP_DP).toFloat()
        private val enterCursorMode = Runnable {
            cursorMode = true
            key.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            listener?.onCursorModeChanged(true)
        }

        override fun down(x: Float, y: Float) {
            flushHeldSpace()
            cursorMode = false
            consumed = false
            anchorX = x
            key.isPressed = true
            key.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            repeatHandler.postDelayed(enterCursorMode, SPACE_HOLD_MS)
            // 다음 키가 눌리면 그 키보다 먼저 이 스페이스를 넣는다.
            heldSpaceFlush = {
                repeatHandler.removeCallbacks(enterCursorMode)
                if (!cursorMode && !consumed) {
                    consumed = true
                    listener?.onAction(KeyAction.SPACE)
                }
            }
        }

        override fun move(x: Float, y: Float, inside: Boolean) {
            if (!cursorMode) return
            val steps = ((x - anchorX) / stepPx).toInt()
            if (steps != 0) {
                listener?.onMoveCursor(steps)
                anchorX += steps * stepPx
            }
        }

        override fun up() {
            key.isPressed = false
            repeatHandler.removeCallbacks(enterCursorMode)
            heldSpaceFlush = null
            if (cursorMode) {
                listener?.onCursorModeChanged(false)
            } else if (!consumed) {
                listener?.onAction(KeyAction.SPACE)
            }
            consumed = true
            cursorMode = false
        }

        /**
         * 취소도 탭으로 친다. 스페이스는 화면 맨 아래라 제스처 영역에 걸려 취소되는
         * 일이 있는데, 그때 띄어쓰기가 통째로 사라졌다.
         */
        override fun cancel() = up()
    }

    private fun flushHeldSpace() {
        val flush = heldSpaceFlush ?: return
        heldSpaceFlush = null
        flush()
    }

    private fun LinearLayout.addSpacer(weight: Float) {
        if (weight <= 0f) return
        addView(View(context), keyParams(weight))
    }

    private fun keyParams(weight: Float) =
        LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = dp(KEY_GAP_DP)
            marginEnd = dp(KEY_GAP_DP)
        }

    // 파라미터 이름을 background 로 두면 apply 블록 안에서 TextView 자신의
    // background 프로퍼티가 먼저 잡힌다. 조용히 배경이 사라지므로 이름을 달리한다.
    private fun keyView(
        label: String,
        keyFace: StateListDrawable,
        textSp: Float = if (label.length > 1) 14f else 20f
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
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
            // 아이콘이 동그라미를 거의 채우도록. 28dp 동그라미에 18sp 면 그 비율이다.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            includeFontPadding = false
            contentDescription = label
            attachKeyTouch(this, onPress = onPress)
            styleToolbarButton(this)
        }

    private fun toolbarParams() = LayoutParams(dp(TOOLBAR_BUTTON_DP), dp(TOOLBAR_BUTTON_DP))

    /**
     * 키 하나를 손가락에 붙인다.
     *
     * **누르는 순간(down)에 입력한다.** onClick 은 손을 뗄 때까지 기다리고 이동 거리까지
     * 따져서, 빨리 치면 눌린 게 씹힌 것처럼 느껴진다. 시중 키보드가 전부 누름 시점에
     * 반응하는 이유다.
     *
     * [alternate] 가 있으면 **꾹 누르는 동안 미리보기를 띄우고, 손을 떼는 순간** 그
     * 글자로 바꾼다. 예전에는 타이머가 울리는 즉시 바꿔 버려서, 바뀐 줄 모르고 계속
     * 누르고 있다 손을 떼면 이미 딴 글자가 들어가 있었다.
     */
    private fun charTouch(
        key: View,
        onPress: () -> Unit,
        alternate: Char? = null,
        repeatable: Boolean = false
    ): KeyTouch = object : KeyTouch {
        // **이 상태는 키마다 따로 있어야 한다.** 빠르게 치면 앞 손가락이 떨어지기 전에
        // 다음 손가락이 닿아서 두 키가 동시에 눌린 상태가 된다. 상태를 하나로 공유하면
        // 나중 키를 떼는 순간 앞 키의 대체 글자가 들어가고, 앞 키를 떼면 아무 일도
        // 일어나지 않는다 — 엉뚱한 글자가 들어가거나 씹힌 것처럼 보인다.
        private var previewing = false
        private val showPreview = alternate?.let { alt ->
            Runnable {
                showAlternate(key, alt)
                previewing = true
            }
        }

        override fun down(x: Float, y: Float) {
            // 잡고 있던 스페이스가 있으면 이 키보다 먼저 들어가야 한다.
            flushHeldSpace()
            previewing = false
            key.isPressed = true
            key.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            onPress()
            showPreview?.let { repeatHandler.postDelayed(it, LONG_PRESS_MS) }
            if (repeatable) repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
        }

        override fun move(x: Float, y: Float, inside: Boolean) {
            // 키 밖으로 끌고 나가면 대체 글자만 취소한다. 이미 들어간 글자는 그대로
            // 둔다 — 여기서 지우면 빠르게 칠 때 글자가 사라진다.
            if (previewing && !inside) {
                showPreview?.let { repeatHandler.removeCallbacks(it) }
                previewing = false
                dismissAlternate()
            }
        }

        override fun up() {
            key.isPressed = false
            showPreview?.let { repeatHandler.removeCallbacks(it) }
            repeatHandler.removeCallbacks(repeatBackspace)
            // 미리보기를 띄운 것이 **이 키** 일 때만 갈아 끼운다.
            if (previewing && alternate != null) listener?.onLongPressChar(alternate)
            if (previewing) dismissAlternate()
            previewing = false
        }

        override fun cancel() {
            key.isPressed = false
            showPreview?.let { repeatHandler.removeCallbacks(it) }
            repeatHandler.removeCallbacks(repeatBackspace)
            if (previewing) dismissAlternate()
            previewing = false
        }
    }

    /**
     * 자판 밖의 키(도구 줄, 이모티콘 칸, 클립보드 칸)는 뷰마다 리스너를 단다. 이쪽은
     * 키가 드문드문 있어서 "가까운 것을 누른 것으로 친다" 가 오히려 해롭다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachKeyTouch(view: View, onPress: () -> Unit) {
        val touch = charTouch(view, onPress)
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> touch.down(event.x, event.y)
                MotionEvent.ACTION_MOVE -> touch.move(event.x, event.y, insideView(target, event))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touch.up()
                MotionEvent.ACTION_CANCEL -> touch.cancel()
            }
            true
        }
    }

    private fun insideView(view: View, event: MotionEvent): Boolean {
        val bounds = Rect(0, 0, view.width, view.height)
        return bounds.contains(event.x.toInt(), event.y.toInt())
    }

    // --- 자판 바닥 전체가 키다 ---------------------------------------------------

    /**
     * 자판 바닥.
     *
     * **키와 키 사이의 여백, 화면 가장자리까지 전부 어느 한 키에 속한다.** 삼성
     * 키보드가 그렇다 — ㅅ과 ㅎ 사이를 누르면 가까운 쪽이 눌리고, 줄 맨 끝 키는
     * 화면 가장자리까지가 제 영역이다.
     *
     * 키 뷰마다 리스너를 달면 그 여백은 아무 키도 아니어서 입력이 조용히 사라진다.
     * 빠르게 칠수록 손가락이 키 한가운데에 정확히 떨어지지 않으니 자주 겪는다 —
     * 220타로 칠 때 "키가 씹힌다" 던 것의 정체다. 그래서 손가락을 어느 키에 줄지는
     * 뷰에 맡기지 않고 판이 직접 정한다.
     */
    private inner class KeyPad(context: Context) : LinearLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            routeTouch(event)
            return true
        }
    }

    /** 자판 위의 키 한 자리. */
    private class PadSlot(val view: View, val touch: KeyTouch) {
        /** 판 기준 좌표. 누를 때마다 다시 잰다 — 줄 높이나 자판이 바뀔 수 있다. */
        val bounds = Rect()
    }

    /**
     * 키 하나가 손가락에 반응하는 법. 판이 직접 불러 주거나([KeyPad]), 자판 밖에서는
     * 뷰의 터치 리스너가 불러 준다([attachKeyTouch]).
     */
    private interface KeyTouch {
        /** 좌표는 키 왼쪽 위 기준. */
        fun down(x: Float, y: Float)

        /** [inside] 는 손가락이 아직 이 키의 영역에 있는지. */
        fun move(x: Float, y: Float, inside: Boolean)
        fun up()
        fun cancel()
    }

    /**
     * 손가락을 키에 나눠 준다.
     *
     * 손가락(pointer)마다 처음 닿은 키를 끝까지 잡고 있는다. 여러 손가락이 동시에
     * 눌려도 서로 섞이지 않고, 한 키 위에 손가락 두 개가 겹쳐도 둘 다 입력된다.
     */
    private fun routeTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 첫 손가락이라면 남아 있는 것은 전부 놓친 것이다(화면이 바뀌는 사이
                // 손을 떼면 UP 이 안 온다). 그대로 두면 ⌫ 반복이 멎지 않는다.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelActiveSlots()
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val slot = slotAt(x, y) ?: return
                activeSlots.put(event.getPointerId(index), slot)
                slot.touch.down(x - slot.bounds.left, y - slot.bounds.top)
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val slot = activeSlots.get(event.getPointerId(index)) ?: continue
                    val x = event.getX(index)
                    val y = event.getY(index)
                    // 끌고 나가도 키는 바뀌지 않는다. 이미 들어간 글자가 있으니
                    // 넘겨줄 수 없다 — 대체 글자 미리보기만 취소된다.
                    val inside = slotAt(x, y) === slot
                    slot.touch.move(x - slot.bounds.left, y - slot.bounds.top, inside)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = event.getPointerId(event.actionIndex)
                activeSlots.get(pointer)?.touch?.up()
                activeSlots.remove(pointer)
            }

            MotionEvent.ACTION_CANCEL -> cancelActiveSlots()
        }
    }

    private fun cancelActiveSlots() {
        for (i in 0 until activeSlots.size()) activeSlots.valueAt(i).touch.cancel()
        activeSlots.clear()
    }

    /**
     * 이 점을 품은 키. 없으면 **가장 가까운** 키.
     *
     * 여백은 양쪽 키의 한가운데를 경계로 갈린다. 키 사이 거리가 3dp 안팎이라 이
     * 한 줄이 곧 "키보드 전체가 키" 다.
     */
    private fun slotAt(x: Float, y: Float): PadSlot? {
        var nearest: PadSlot? = null
        var best = Float.MAX_VALUE
        for (slot in keySlots) {
            // 아직 자리를 못 잡은 키(폭 0)는 거리가 엉뚱하게 나온다.
            if (slot.view.width == 0) continue
            fillBounds(slot)
            val dx = gapTo(x, slot.bounds.left.toFloat(), slot.bounds.right.toFloat())
            val dy = gapTo(y, slot.bounds.top.toFloat(), slot.bounds.bottom.toFloat())
            if (dx == 0f && dy == 0f) return slot
            val distance = dx * dx + dy * dy
            if (distance < best) {
                best = distance
                nearest = slot
            }
        }
        return nearest
    }

    /** 구간 [min]~[max] 에서 [value] 가 벗어난 거리. 안에 있으면 0. */
    private fun gapTo(value: Float, min: Float, max: Float): Float = when {
        value < min -> min - value
        value > max -> value - max
        else -> 0f
    }

    /** 키의 자리를 판 기준 좌표로 잰다. 키는 줄 안에 있고, 줄은 판 안에 있다. */
    private fun fillBounds(slot: PadSlot) {
        var view: View? = slot.view
        var left = 0
        var top = 0
        while (view != null && view !== rowContainer) {
            left += view.left
            top += view.top
            view = view.parent as? View
        }
        slot.bounds.set(left, top, left + slot.view.width, top + slot.view.height)
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
            addState(intArrayOf(), keyFace(normalColor))
        }
    }

    /**
     * 키 면. 아래에 1dp 그림자를 깔아 살짝 떠 보이게 한다 — 삼성 키보드의 키가 평면
     * 사각형과 다르게 보이는 이유가 이 한 줄이다. 사진 위에서는 그림자도 같이 비친다.
     */
    private fun keyFace(fill: Int): Drawable {
        val shadow = roundRect(keyFill(theme.keyShadow))
        val face = roundRect(fill)
        return LayerDrawable(arrayOf(shadow, face)).apply {
            setLayerInset(0, 0, dp(1), 0, 0)
            setLayerInset(1, 0, 0, 0, dp(1))
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
        /**
         * 도구 줄. 삼성 실측은 동그라미 ≈33dp, 줄 48dp 였는데 실기기 화면에서 그마저 커
         * 보여, 사용자가 그어 준 선(위쪽 약 16dp)만큼 더 줄였다. 줄 34dp 에 동그라미 28dp.
         */
        const val TOOLBAR_HEIGHT_DP = 34
        const val TRANSLATE_HEIGHT_DP = 40
        const val TOOLBAR_BUTTON_DP = 28
        const val FLASH_MS = 1600L
        // 삼성 키보드 실측에 맞춘 값. 키는 조금 높고, 틈은 조금 넓다.
        const val KEY_HEIGHT_DP = 48
        const val ROW_GAP_DP = 5
        const val KEY_GAP_DP = 3
        /** 스페이스를 이만큼 누르고 있으면 커서 이동 모드. 쌍자음보다 조금 길게. */
        const val SPACE_HOLD_MS = 380L
        /** 커서 이동 모드에서 이만큼 밀 때마다 한 글자. */
        const val CURSOR_STEP_DP = 18
        /** 설정의 "키 투명도"(0~100)를 불투명도로. 100 이어도 완전히 사라지지는 않게 둔다. */
        fun alphaFor(transparency: Int): Float = (1f - transparency / 100f).coerceIn(0.08f, 1f)
        const val CHEONJIIN_KEY_HEIGHT_DP = 58
        /** 천지인·숫자 판의 한 칸 너비(weightSum 10 기준). 넷이면 딱 맞는다. */
        const val GRID_W = 2.5f
        const val EMOJI_COLUMNS = 8
        const val EMOJI_CELL_DP = 44
        const val CLIPBOARD_CARD_HEIGHT_DP = 76
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
