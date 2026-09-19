package com.spellkeyboard.ko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 자판 위 도구 줄을 그대로 그려 놓고, 누른 동그라미를 막대기로 가리키며 설명하는 그림.
 *
 * 글로만 늘어놓으면 "왼쪽에서 둘째 동그라미" 같은 말을 읽고 머릿속에서 자판과 맞춰
 * 봐야 한다. 그림이 있으면 그럴 필요가 없다.
 *
 * **한 번에 하나만 가리킨다.** 여섯 자리에 막대기를 한꺼번에 내리면 선이 서로 엉키고,
 * 폰 너비에서는 설명 글자리도 안 나온다. 누른 것 하나만 가리키면 선은 곧게 하나뿐이고
 * 설명은 줄 전체를 쓴다.
 *
 * 높이는 가장 긴 설명에 맞춰 잡아 둔다 — 누를 때마다 칸이 늘었다 줄었다 하면 밑에 있는
 * 것들이 따라 움직여서 눈이 어지럽다.
 */
class ToolbarGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * 도구 줄의 한 자리. [glyph] 가 비면 [icon] 을 그린다(클립보드는 글꼴 기호로
     * 같은 모양이 안 나와서 벡터를 쓴다). [accent] 는 켜진 상태로 그릴 자리 —
     * LIVE 가 켜져 있을 때 어떻게 보이는지가 곧 설명이다.
     */
    private data class Spot(
        val glyph: String,
        val glyphSp: Float,
        val name: Int,
        val desc: Int,
        val icon: Int = 0,
        val accent: Boolean = false,
        /** 실제 도구 줄에서 동그라미 없이 글자만 있는 자리(접기 손잡이). */
        val bare: Boolean = false
    )

    private val spots = listOf(
        Spot("▲", 9f, R.string.guide_collapse_name, R.string.guide_collapse_desc, bare = true),
        Spot("☺︎", 15f, R.string.guide_emoji_name, R.string.guide_emoji_desc),
        Spot("ALL", 9.5f, R.string.guide_all_name, R.string.guide_all_desc),
        Spot("", 0f, R.string.guide_clipboard_name, R.string.guide_clipboard_desc, icon = R.drawable.ic_clipboard),
        Spot("LIVE", 9.5f, R.string.guide_live_name, R.string.guide_live_desc, accent = true),
        Spot("번역", 9.5f, R.string.guide_translate_name, R.string.guide_translate_desc),
        Spot("⚙︎", 15f, R.string.guide_settings_name, R.string.guide_settings_desc)
    )

    private var selected = 2

    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(15f)
    }
    private val descPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(14f) }

    /** 동그라미 가운데 x. 너비가 정해질 때 채운다. */
    private val centers = FloatArray(spots.size)

    private var descLayout: StaticLayout? = null

    /** 가장 긴 설명이 차지하는 높이. 칸 높이를 여기 맞춰 고정한다. */
    private var boxTextHeight = 0

    private val icons = HashMap<Int, Drawable?>()

    init {
        isClickable = true
        applyColors()
    }

    private fun applyColors() {
        stripPaint.color = color(R.color.toss_fill)
        circlePaint.color = color(R.color.toss_card)
        accentPaint.color = color(R.color.toss_blue)
        ringPaint.color = color(R.color.toss_blue)
        stickPaint.color = color(R.color.toss_text3)
        glyphPaint.color = color(R.color.toss_text)
        namePaint.color = color(R.color.toss_text)
        descPaint.color = color(R.color.toss_text2)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        layOut(width)
        val height = (STRIP_TOP + STRIP_H + STICK_H).let { dp(it) } +
            dp(BOX_PAD * 2) + namePaint.lineHeight() + dp(4f) + boxTextHeight
        setMeasuredDimension(width, height.toInt())
    }

    /** 너비가 정해져야 할 수 있는 계산 — 동그라미 자리와 설명 줄 나누기. */
    private fun layOut(width: Int) {
        if (width <= 0) return
        val gap = (width - spots.size * dp(CIRCLE)) / (spots.size + 1)
        for (i in spots.indices) {
            centers[i] = gap * (i + 1) + dp(CIRCLE) * i + dp(CIRCLE) / 2
        }
        val textWidth = (width - dp(BOX_PAD * 2)).toInt().coerceAtLeast(1)
        boxTextHeight = spots.maxOf { build(context.getString(it.desc), textWidth).height }
        descLayout = build(context.getString(spots[selected].desc), textWidth)
    }

    private fun build(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, descPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(dp(4f), 1f)
            .setIncludePad(false)
            .build()

    override fun onDraw(canvas: Canvas) {
        val stripTop = dp(STRIP_TOP)
        val stripBottom = stripTop + dp(STRIP_H)
        val radius = dp(CIRCLE) / 2

        canvas.drawRoundRect(
            RectF(0f, stripTop, width.toFloat(), stripBottom), dp(10f), dp(10f), stripPaint
        )

        for (i in spots.indices) {
            val spot = spots[i]
            val cx = centers[i]
            val cy = (stripTop + stripBottom) / 2
            if (!spot.bare) canvas.drawCircle(cx, cy, radius, if (spot.accent) accentPaint else circlePaint)
            if (i == selected) canvas.drawCircle(cx, cy, radius + dp(3f), ringPaint)

            if (spot.icon != 0) {
                val icon = icon(spot.icon)
                if (icon != null) {
                    val half = (dp(CIRCLE) * ICON_RATIO / 2).toInt()
                    icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                    icon.draw(canvas)
                }
            } else {
                glyphPaint.textSize = sp(spot.glyphSp)
                glyphPaint.typeface = if (spot.glyphSp <= 10f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // 켜진 자리는 파란 바탕이라 글자가 흰색이어야 읽힌다 — 어두운 테마에서도.
                glyphPaint.color = if (spot.accent) android.graphics.Color.WHITE else color(R.color.toss_text)
                val fm = glyphPaint.fontMetrics
                canvas.drawText(spot.glyph, cx, cy - (fm.ascent + fm.descent) / 2, glyphPaint)
            }
        }

        // 막대기. 고른 동그라미 밑에서 설명 칸 위까지 곧게 내린다.
        val boxTop = stripBottom + dp(STICK_H)
        val cx = centers[selected]
        canvas.drawLine(cx, stripBottom + dp(5f), cx, boxTop, stickPaint)

        // 설명 칸. 바탕 없이 글만 둔다 — 카드 안의 카드가 되면 답답하다.
        var y = boxTop + dp(BOX_PAD)
        val fm = namePaint.fontMetrics
        canvas.drawText(context.getString(spots[selected].name), dp(BOX_PAD), y - fm.ascent, namePaint)
        y += namePaint.lineHeight() + dp(4f)
        canvas.save()
        canvas.translate(dp(BOX_PAD), y)
        descLayout?.draw(canvas)
        canvas.restore()
    }

    /**
     * 동그라미를 고른다. 사이를 눌러도 가장 가까운 것이 잡히게 — 28dp 짜리 과녁을
     * 정확히 맞히라고 하면 잘 안 맞는다.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val stripBottom = dp(STRIP_TOP) + dp(STRIP_H)
        if (event.y > stripBottom + dp(STICK_H)) return super.onTouchEvent(event)

        var best = 0
        for (i in spots.indices) {
            if (kotlin.math.abs(event.x - centers[i]) < kotlin.math.abs(event.x - centers[best])) best = i
        }
        if (best != selected) {
            selected = best
            descLayout = build(context.getString(spots[best].desc), (width - dp(BOX_PAD * 2)).toInt())
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            contentDescription = context.getString(spots[best].name)
            invalidate()
        }
        return true
    }

    private fun icon(res: Int): Drawable? = icons.getOrPut(res) {
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(color(R.color.toss_text)) }
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun Paint.lineHeight(): Float = fontMetrics.descent - fontMetrics.ascent

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val STRIP_TOP = 4f
        const val STRIP_H = 44f
        const val CIRCLE = 28f
        const val STICK_H = 26f
        const val BOX_PAD = 2f
        const val ICON_RATIO = 0.55f
    }
}
