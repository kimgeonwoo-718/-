package com.spellkeyboard.ko

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 첫 실행 둘러보기. **진짜 첫 화면 위에서** 한 단계에 버튼 하나만 밝게 남기고 나머지는 어둡게
 * 덮은 뒤, 그 버튼에서 작대기를 그어 설명 칸을 단다. "다음" 을 누르면 다음 버튼으로 넘어간다.
 *
 * 순서: 키보드 켜기 → 입력기 고르기 → 써 보기 → 도구 줄 → ☰ → (서랍을 열고) 로그인 → 키보드
 * 맞춤설정. 끝나면 서랍을 닫고 맨 위로 돌아간다. 프리미엄 버튼은 짚지 않는다 — 버튼에 적힌
 * 그대로라 설명할 게 없다는 평.
 *
 * 다음/건너뛰기는 **화면 맨 아래 고정 막대**에 있다. 처음엔 설명 칸 안에 두었는데, 과녁이 화면
 * 아래쪽이면 설명 칸이 화면 밖으로 밀려나 버튼째 사라져서 넘어갈 수가 없었다(프리미엄 단계).
 * 이제 설명 칸은 재어 보고 들어가는 쪽에만 두며, 어느 쪽에도 안 들어가면 화면 안으로 당긴다.
 *
 * 따로 그린 그림이나 흉내 화면을 쓰지 않는 이유: 이전 판(흉내 화면 다섯 쪽)은 "어색하다" 는
 * 평이었다. 진짜 화면을 짚으면 사용자가 본 그대로를 다시 찾아갈 수 있고, 화면이 바뀌어도 이
 * 단계 목록만 맞으면 된다.
 *
 * 둘러보는 동안에는 밝힌 버튼도 눌리지 않는다 — 덮개가 손을 전부 받는다. 설명만 하는 시간이다.
 * 건너뛰든 끝까지 보든 한 번 닫으면 다시 안 뜬다([Prefs.onboardingSeen]). 더보기 서랍 "사용 안내
 * 다시 보기" 로 다시 연다.
 */
class CoachTour(
    private val activity: Activity,
    private val more: MoreDrawer,
) {
    private class Step(val targetId: Int, val title: Int, val body: Int, val inDrawer: Boolean = false)

    private val allSteps = listOf(
        Step(R.id.enable_button, R.string.tour_enable_title, R.string.tour_enable_body),
        Step(R.id.pick_button, R.string.tour_pick_title, R.string.tour_pick_body),
        Step(R.id.test_field, R.string.tour_try_title, R.string.tour_try_body),
        Step(R.id.toolbar_guide, R.string.tour_toolbar_title, R.string.tour_toolbar_body),
        Step(R.id.menu_button, R.string.tour_menu_title, R.string.tour_menu_body),
        Step(R.id.account_card, R.string.tour_account_title, R.string.tour_account_body, inDrawer = true),
        Step(R.id.menu_keyboard, R.string.tour_keyboard_title, R.string.tour_keyboard_body, inDrawer = true),
    )

    /** 이 빌드에서 안 보이는 것(로그인이 꺼진 빌드의 계정 칸 등)은 뺀다. */
    private var steps: List<Step> = emptyList()
    private var index = 0
    private var overlay: Overlay? = null

    val isShowing: Boolean get() = overlay != null

    fun start() {
        if (isShowing) return
        more.close()
        steps = allSteps.filter { step ->
            val view = activity.findViewById<View>(step.targetId)
            view != null && (step.inDrawer || view.isShown) && view.visibility == View.VISIBLE
        }
        if (steps.isEmpty()) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        overlay = Overlay(activity).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        index = 0
        show()
    }

    /** 건너뛰기·끝·뒤로 가기. 서랍을 닫고 맨 위로 돌려놓는다. */
    fun finish() {
        val view = overlay ?: return
        overlay = null
        Prefs.setOnboardingSeen(activity, true)
        more.close()
        activity.findViewById<ScrollView>(R.id.setup_scroll)?.smoothScrollTo(0, 0)
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view)
        }.start()
    }

    private fun next() {
        if (index >= steps.lastIndex) finish() else {
            index++
            show()
        }
    }

    /**
     * 한 단계를 보인다. 먼저 과녁이 화면에 들어오게 한다 — 첫 화면 것은 스크롤해서 위에서
     * 4분의 1쯤에, 서랍 것은 서랍을 열어서. 움직이는 동안은 구멍 없이 어둡게만 두었다가 자리가
     * 잡히면 구멍과 설명 칸을 낸다.
     */
    private fun show() {
        val view = overlay ?: return
        val step = steps[index]
        view.clear()

        val delay = if (step.inDrawer) {
            if (!more.isOpen) more.open(animate = true)
            SETTLE_MS
        } else {
            if (more.isOpen) more.close()
            scrollTo(step.targetId)
        }
        view.postDelayed({ if (overlay === view) view.point(step) }, delay)
    }

    /** 과녁이 위에서 4분의 1쯤 오게 스크롤한다. 얼마나 기다려야 하는지 돌려준다. */
    private fun scrollTo(targetId: Int): Long {
        val scroll = activity.findViewById<ScrollView>(R.id.setup_scroll) ?: return 0L
        val target = activity.findViewById<View>(targetId) ?: return 0L
        val scrollAt = IntArray(2).also { scroll.getLocationInWindow(it) }
        val targetAt = IntArray(2).also { target.getLocationInWindow(it) }
        val inContent = targetAt[1] - scrollAt[1] + scroll.scrollY
        val wanted = (inContent - scroll.height / 4).coerceAtLeast(0)
        if (wanted == scroll.scrollY) return SHORT_MS
        scroll.smoothScrollTo(0, wanted)
        return SETTLE_MS
    }

    /**
     * 화면 전체를 덮는 판. 어둡게 칠하고 과녁 자리만 구멍을 내고(밝게 남는다), 구멍 둘레에 흰 테,
     * 테에서 설명 칸까지 흰 작대기를 긋는다. 설명 칸은 진짜 뷰라 글자가 선명하다.
     */
    private inner class Overlay(context: Context) : FrameLayout(context) {

        private val density = resources.displayMetrics.density
        private val hole = RectF()
        private var hasHole = false
        private var round = false

        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        private val stick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val path = Path()

        private val bubble: View = View.inflate(context, R.layout.coach_bubble, null)
        private val controls: View = View.inflate(context, R.layout.coach_controls, null)

        init {
            setWillNotDraw(false)
            // 덮개가 손을 전부 받는다. 밝힌 버튼도 이 동안은 눌리지 않는다.
            isClickable = true
            alpha = 0f
            animate().alpha(1f).setDuration(FADE_MS).start()
            addView(bubble, LayoutParams(-1, -2).apply {
                val side = (16 * density).toInt()
                leftMargin = side
                rightMargin = side
            })
            bubble.visibility = INVISIBLE
            addView(controls, LayoutParams(-1, -2, Gravity.BOTTOM))
            controls.findViewById<View>(R.id.coach_skip).setOnClickListener { finish() }
            controls.findViewById<View>(R.id.coach_next).setOnClickListener { next() }
        }

        /** 자리를 옮기는 동안: 구멍도 설명 칸도 없이 어둡게만. */
        fun clear() {
            hasHole = false
            bubble.visibility = INVISIBLE
            invalidate()
        }

        fun point(step: Step) {
            val target = activity.findViewById<View>(step.targetId) ?: return next()
            val mine = IntArray(2).also { getLocationInWindow(it) }
            val at = IntArray(2).also { target.getLocationInWindow(it) }
            val pad = 6f * density
            val left = (at[0] - mine[0]).toFloat()
            val top = (at[1] - mine[1]).toFloat()
            hole.set(left - pad, top - pad, left + target.width + pad, top + target.height + pad)
            // 네모 버튼(☰)은 동그랗게, 나머지는 모서리 둥근 네모로 판다.
            round = target.width <= target.height * 1.3f
            hasHole = true

            bubble.findViewById<TextView>(R.id.coach_title).setText(step.title)
            bubble.findViewById<TextView>(R.id.coach_body).setText(step.body)
            controls.findViewById<TextView>(R.id.coach_step).text =
                context.getString(R.string.guide_step, index + 1, steps.size)
            controls.findViewById<TextView>(R.id.coach_next)
                .setText(if (index == steps.lastIndex) R.string.guide_start else R.string.guide_next)

            bubble.layoutParams = (bubble.layoutParams as LayoutParams).apply {
                gravity = Gravity.TOP
                topMargin = bubbleTop()
            }
            bubble.alpha = 0f
            bubble.visibility = VISIBLE
            bubble.animate().alpha(1f).setDuration(FADE_MS).start()
            invalidate()
        }

        /**
         * 설명 칸의 위쪽 자리. 칸을 먼저 재어 보고, 과녁 아래(작대기 길이만큼 떨어져)에 들어가면
         * 아래, 위에 들어가면 위. 둘 다 안 되면 넓은 쪽에 두되 화면 안으로 당긴다 — 과녁을 조금
         * 가리더라도 칸이 화면 밖으로 사라지는 것보다 낫다. 아래 막대 자리는 비워 둔다.
         */
        private fun bubbleTop(): Int {
            val side = (16 * density).toInt()
            bubble.measure(
                MeasureSpec.makeMeasureSpec(width - side * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val bubbleHeight = bubble.measuredHeight
            val gap = 36 * density
            val edge = 8 * density
            val floor = (height - controls.height).toFloat() - edge

            val belowTop = hole.bottom + gap
            val aboveTop = hole.top - gap - bubbleHeight
            val top = when {
                belowTop + bubbleHeight <= floor -> belowTop
                aboveTop >= edge -> aboveTop
                floor - hole.bottom >= hole.top -> belowTop
                else -> aboveTop
            }
            return top.coerceIn(edge, maxOf(edge, floor - bubbleHeight)).toInt()
        }

        override fun dispatchDraw(canvas: Canvas) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (hasHole) {
                if (round) path.addOval(hole, Path.Direction.CW)
                else path.addRoundRect(hole, radius(), radius(), Path.Direction.CW)
            }
            canvas.drawPath(path, dim)

            if (hasHole) {
                if (round) canvas.drawOval(hole, ring)
                else canvas.drawRoundRect(hole, radius(), radius(), ring)
                // 설명 칸이 과녁과 겹치게 당겨졌으면(둘 다 안 들어갈 때) 작대기는 긋지 않는다.
                val clear = bubble.top >= hole.bottom || bubble.bottom <= hole.top
                if (bubble.visibility == VISIBLE && bubble.height > 0 && clear) drawStick(canvas)
            }
            super.dispatchDraw(canvas)
        }

        /** 구멍 가장자리 가운데쯤에서 설명 칸 가장자리까지. 설명 칸 안쪽으로 너무 치우치지 않게. */
        private fun drawStick(canvas: Canvas) {
            val margin = 28 * density
            val x = hole.centerX().coerceIn(bubble.left + margin, bubble.right - margin)
            val below = bubble.top >= hole.bottom
            val fromY = if (below) hole.bottom else hole.top
            val toY = if (below) bubble.top.toFloat() else bubble.bottom.toFloat()
            val fromX = hole.centerX().coerceIn(hole.left + radius(), hole.right - radius())
            canvas.drawLine(fromX, fromY, x, toY, stick)
            canvas.drawCircle(x, toY, 3.5f * density, dot)
        }

        private fun radius(): Float = minOf(hole.height() / 2, 16 * density)
    }

    private companion object {
        const val FADE_MS = 180L
        const val SETTLE_MS = 330L
        /** 스크롤이 필요 없을 때도 조금은 기다린다 — 막 붙은 덮개가 한 번은 자리를 잡아야 한다. */
        const val SHORT_MS = 80L
    }
}
