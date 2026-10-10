package com.spellkeyboard.ko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 번역 입력줄의 한국어. **깜빡이는 커서가 있다.**
 *
 * 예전에는 한 줄짜리 `TextView` 였다. 커서가 없어서 어디에 쓰고 있는지 알 수 없었고, 글이 길어지면
 * 앞이 잘려 나가("…") 앞쪽 오타를 볼 수도 고칠 수도 없었다. 이 뷰는 줄바꿈해 보여 주고,
 * 커서가 있는 줄이 늘 보이도록 밀어 올리며, 글자를 눌러 커서를 옮길 수 있다.
 *
 * **높이는 글에 맞춘다.** 한 줄이면 한 줄 높이, 줄바꿈이 생기면 [maxRows] 줄까지 칸이 자란다. 처음부터 두 줄 높이를 잡아 두면
 * 한 줄만 쓰는 대부분의 때에 빈칸이 남는다. 안내 문구는 한 줄로만 보인다(길면 …).
 *
 * 글을 들고 있지 않는다 — 그리라고 받은 것(글, 커서 자리)만 그린다. 진짜 내용은 `TranslateBuffer` 에 있다.
 */
@SuppressLint("ViewConstructor")
class TranslateSourceView(context: Context) : View(context) {

    /** 눌러서 커서를 옮기려는 자리(글자 앞에서 센 번호). */
    var onCaretRequested: ((Int) -> Unit)? = null

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
    }
    private val caretPaint = Paint().apply { strokeWidth = dp(2).toFloat() }

    private var text = ""
    private var cursor = 0
    private var hint = ""
    private var textColor = 0xFF000000.toInt()
    private var hintColor = 0xFF888888.toInt()
    private var caretColor = 0xFF1A73E8.toInt()

    private var layout: StaticLayout? = null
    private var shown = ""
    private var layoutWidth = -1

    /** 칸이 자라는 한계 줄 수. 이보다 길면 커서가 있는 줄이 보이도록 밀어 올린다. 가로 화면은 1 로 줄인다. */
    var maxRows = DEFAULT_MAX_ROWS
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** 마지막으로 잰 줄 수. 줄 수가 바뀔 때만 다시 재려고 쥔다(글자마다 재면 낭비다). */
    private var measuredRows = 0

    /** 이 첫 줄부터 그린다. 커서가 보이는 줄 안에 오도록 [scrollToCaret] 가 정한다. */
    private var firstLine = 0

    private var caretVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private val blink = object : Runnable {
        override fun run() {
            caretVisible = !caretVisible
            invalidate()
            handler.postDelayed(this, BLINK_MS)
        }
    }

    /** 글·커서·안내 문구를 바꾼다. 글이 같고 커서만 움직여도 부른다. */
    fun setContent(text: String, cursor: Int, hint: String) {
        this.text = text
        this.cursor = cursor.coerceIn(0, text.length)
        this.hint = hint
        // 쓰는 동안은 커서가 꺼진 채로 멈춰 있으면 안 된다. 바뀔 때마다 켜 두고 깜빡임을 다시 시작한다.
        restartBlink()
        rebuild()
        if (wantedRows() != measuredRows) requestLayout()
        invalidate()
    }

    fun setColors(text: Int, hint: Int, caret: Int) {
        textColor = text
        hintColor = hint
        caretColor = caret
        invalidate()
    }

    /** 이 뷰가 한 번에 보여 주는 줄 수. 높이가 줄 높이의 몇 배인지로 잰다. */
    private fun visibleLines(): Int {
        val line = lineHeight()
        return max(1, (height - paddingTop - paddingBottom) / max(1, line))
    }

    /** 한 줄의 높이. 실제로 그려지는 줄의 높이를 쓴다 — 어림값이 모자라면 칸이 마지막 줄을 조금 자른다. */
    private fun lineHeight(): Int =
        layout?.let { it.getLineBottom(0) - it.getLineTop(0) }?.takeIf { it > 0 }
            ?: (paint.fontMetrics.let { it.descent - it.ascent } * LINE_SPACING).toInt()

    private fun rebuild() {
        val inner = width - paddingLeft - paddingRight
        if (inner > 0) build(inner)
    }

    private fun build(inner: Int) {
        shown = text.ifEmpty { hint }
        layoutWidth = inner
        paint.color = if (text.isEmpty()) hintColor else textColor
        val builder = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
        // 안내 문구는 한 줄만. 두 줄이 되면 글을 치기 시작할 때 칸이 줄어 자판이 출렁인다.
        if (text.isEmpty()) builder.setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END)
        layout = builder.build()
        scrollToCaret()
    }

    /** 지금 글이 차지하는 줄 수(1~[maxRows]). */
    private fun wantedRows(): Int = (layout?.lineCount ?: 1).coerceIn(1, maxRows)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        if (inner > 0 && (layout == null || layoutWidth != inner)) build(inner)
        measuredRows = wantedRows()
        val wanted = paddingTop + paddingBottom + measuredRows * lineHeight()
        setMeasuredDimension(width, resolveSize(wanted, heightMeasureSpec))
    }

    /** 커서가 있는 줄이 보이는 칸 안에 오도록 첫 줄을 정한다. */
    private fun scrollToCaret() {
        val l = layout ?: return
        val lines = l.lineCount
        val rows = visibleLines()
        val caretLine = if (text.isEmpty()) 0 else l.getLineForOffset(cursor.coerceIn(0, shown.length))
        // 커서를 마지막 보이는 줄에 두되, 맨 위로 너무 올라가지 않게.
        firstLine = (caretLine - rows + 1).coerceIn(0, max(0, lines - rows))
        if (caretLine < firstLine) firstLine = caretLine
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val top = l.getLineTop(firstLine)
        val rows = visibleLines()
        val lastLine = min(l.lineCount - 1, firstLine + rows - 1)
        val bottom = l.getLineBottom(lastLine)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat() - top)
        canvas.clipRect(0f, top.toFloat(), layoutWidth.toFloat(), bottom.toFloat())
        l.draw(canvas)
        if (caretVisible) {
            val caretLine = if (text.isEmpty()) 0 else l.getLineForOffset(cursor.coerceIn(0, shown.length))
            // 안내 문구가 떠 있을 때는 맨 앞에 둔다.
            val x = if (text.isEmpty()) 0f else l.getPrimaryHorizontal(cursor.coerceIn(0, shown.length))
            caretPaint.color = caretColor
            canvas.drawLine(
                x.coerceAtMost(layoutWidth - caretPaint.strokeWidth), l.getLineTop(caretLine).toFloat() + dp(1),
                x.coerceAtMost(layoutWidth - caretPaint.strokeWidth), l.getLineBottom(caretLine).toFloat() - dp(1),
                caretPaint
            )
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = layout ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (text.isEmpty()) return true
                val y = (event.y - paddingTop).toInt() + l.getLineTop(firstLine)
                val line = l.getLineForVertical(y.coerceIn(0, l.height - 1))
                val x = event.x - paddingLeft
                onCaretRequested?.invoke(l.getOffsetForHorizontal(line, x).coerceIn(0, text.length))
                return true
            }
        }
        return true
    }

    private fun restartBlink() {
        caretVisible = true
        handler.removeCallbacks(blink)
        if (isShown) handler.postDelayed(blink, BLINK_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) restartBlink() else handler.removeCallbacks(blink)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private companion object {
        const val TEXT_SP = 15f
        const val DEFAULT_MAX_ROWS = 2
        const val LINE_SPACING = 1.1f
        const val BLINK_MS = 530L
    }
}
