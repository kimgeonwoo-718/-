package com.spellkeyboard.ko

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 동그라미 치고 작대기 그어 설명하는 판.
 *
 * 안에 든 것은 평범한 뷰다 — 화면을 흉내 낸 버튼들과 설명 글. 이 판은 그 위에 **선만** 긋는다:
 * [link] 로 짝지은 (가리킬 뷰, 설명 글) 마다 가리킬 뷰를 파란 테로 두르고, 테 가장자리에서
 * 설명 글 쪽으로 작대기를 긋고 끝에 점을 찍는다.
 *
 * 테 모양: 동그란 버튼은 동그라미, 옆으로 긴 것(예문 칸, 서랍 한 줄)은 모서리 둥근 네모.
 * 긴 것에 동그라미를 치면 양 끝이 좁아져서 테가 글자를 가로지른다.
 *
 * 글자를 그림으로 넣지 않고 진짜 글자로 두는 이유: 선명하고, 어두운 테마를 따라가고, 문구를
 * 고칠 때 그림을 다시 만들 필요가 없다. 이미지 AI 는 한글을 거의 항상 깨뜨린다.
 *
 * 설명 글의 자리는 레이아웃이 정한다. 이 판은 두 뷰가 어디 있든 가장 가까운 쪽끼리 잇는다
 * — 글이 아래 있으면 글 위쪽 가운데로, 왼쪽에 있으면 글 오른쪽 가운데로.
 */
class CalloutLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val links = mutableListOf<Pair<Int, Int>>()

    private val density = resources.displayMetrics.density
    private val blue = ContextCompat.getColor(context, R.color.toss_blue)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = blue
    }
    private val stick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = blue
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = blue
    }

    private val target = Rect()
    private val label = Rect()
    private val oval = RectF()

    init {
        // FrameLayout 은 기본으로 자기 그림을 건너뛴다. 선은 dispatchDraw 에서 긋지만 확실히 해 둔다.
        setWillNotDraw(false)
    }

    /** [targetId] 뷰를 동그라미 치고 [labelId] 글로 작대기를 긋는다. 둘 다 이 판 안에 있어야 한다. */
    fun link(targetId: Int, labelId: Int) {
        links += targetId to labelId
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        for ((targetId, labelId) in links) {
            val t = findViewById<View>(targetId) ?: continue
            val l = findViewById<View>(labelId) ?: continue
            if (!t.isShown || !l.isShown) continue
            boundsOf(t, target)
            boundsOf(l, label)
            drawCallout(canvas)
        }
    }

    private fun drawCallout(canvas: Canvas) {
        val pad = 5f * density
        oval.set(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
        val round = target.width() <= target.height() * WIDE_RATIO
        if (round) {
            canvas.drawOval(oval, ring)
        } else {
            val radius = minOf(target.height() / 2f, 14f * density) + pad
            canvas.drawRoundRect(oval, radius, radius, ring)
        }

        val cx = oval.centerX()
        val cy = oval.centerY()

        // 설명 글에서 닿을 점: 글이 동그라미의 어느 쪽에 있느냐에 따라 그 가장자리 가운데쯤.
        val inset = 6f * density
        val gap = 3f * density
        val (ax, ay) = when {
            label.top >= oval.bottom -> clamp(cx, label.left + inset, label.right - inset) to label.top - gap
            label.bottom <= oval.top -> clamp(cx, label.left + inset, label.right - inset) to label.bottom + gap
            label.right <= oval.left -> label.right + gap to clamp(cy, label.top + inset, label.bottom - inset)
            else -> label.left - gap to clamp(cy, label.top + inset, label.bottom - inset)
        }

        // 테 둘레에서 출발한다. 가운데서 그으면 가리키는 버튼 위를 선이 덮는다.
        val angle = atan2(ay - cy, ax - cx)
        val dx = cos(angle)
        val dy = sin(angle)
        val reach = if (round) {
            1f
        } else {
            // 네모는 가운데서 쏜 반직선이 먼저 닿는 변까지. 둥근 모서리는 무시해도 눈에 안 띈다.
            val hx = if (abs(dx) < 1e-4f) Float.MAX_VALUE else (oval.width() / 2) / abs(dx)
            val hy = if (abs(dy) < 1e-4f) Float.MAX_VALUE else (oval.height() / 2) / abs(dy)
            minOf(hx, hy)
        }
        val sx = if (round) cx + oval.width() / 2 * dx else cx + reach * dx
        val sy = if (round) cy + oval.height() / 2 * dy else cy + reach * dy
        canvas.drawLine(sx, sy, ax, ay, stick)
        canvas.drawCircle(ax, ay, 2.6f * density, dot)
    }

    private fun boundsOf(view: View, out: Rect) {
        view.getDrawingRect(out)
        offsetDescendantRectToMyCoords(view, out)
    }

    private fun clamp(value: Float, low: Float, high: Float): Float =
        if (low > high) (low + high) / 2 else value.coerceIn(low, high)

    private companion object {
        /** 가로가 세로의 이만큼을 넘으면 "옆으로 긴 것" — 동그라미 대신 둥근 네모. */
        const val WIDE_RATIO = 1.4f
    }
}
