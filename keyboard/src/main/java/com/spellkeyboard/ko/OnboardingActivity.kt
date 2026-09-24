package com.spellkeyboard.ko

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.abs

/**
 * 첫 실행 안내. 앱을 처음 깔면 첫 화면보다 먼저 뜬다. 옆으로 넘기는 다섯 쪽:
 *
 * 1. 쓰는 대로 바로 고쳐요 — 예문이 고쳐지는 모습
 * 2. 자판 위 도구 줄 — 첫 화면 '사용법' 그림 그대로(누르면 설명)
 * 3. 꾹 누르면 AI — ALL · 번역 버튼의 짧게/꾹
 * 4. ☰ 더보기 — ☰ 위치, 서랍 안 로그인 · 키보드 맞춤설정 · 고객센터
 * 5. 키보드 켜기 — 첫 화면 '시작하기' 두 줄
 *
 * **그림 파일이 없다.** 화면을 흉내 낸 뷰 위에 [CalloutLayout] 이 동그라미와 작대기를 긋고,
 * 설명은 진짜 글자다. 이미지 AI 에 맡겨 봤더니 우리 화면을 모르고 한글을 깨뜨렸다. 이렇게 두면
 * 선명하고, 어두운 테마를 따라가고, 화면이 바뀌면 여기만 고치면 된다.
 *
 * 건너뛰든 끝까지 보든 한 번 닫으면 다시 안 뜬다([Prefs.onboardingSeen]). 더보기 서랍의
 * "사용 안내 다시 보기" 로 언제든 다시 연다.
 */
class OnboardingActivity : AppCompatActivity() {

    private val layouts = listOf(
        R.layout.onboarding_page_correct,
        R.layout.onboarding_page_toolbar,
        R.layout.onboarding_page_ai,
        R.layout.onboarding_page_more,
        R.layout.onboarding_page_start,
    )

    /**
     * 쪽마다 (동그라미 칠 것, 설명 글) 짝. 레이아웃에서 둘의 자리를 정하고, 여기서 잇는다.
     * 2쪽은 [ToolbarGuideView] 가 알아서 그려서 짝이 없다.
     */
    private val links = mapOf(
        R.layout.onboarding_page_correct to listOf(
            R.id.demo_fixed to R.id.label_fixed,
        ),
        R.layout.onboarding_page_ai to listOf(
            R.id.mock_all to R.id.label_all,
            R.id.mock_translate to R.id.label_translate,
        ),
        R.layout.onboarding_page_more to listOf(
            R.id.mock_menu to R.id.label_menu,
            R.id.mock_login to R.id.label_login,
            R.id.mock_keyboard to R.id.label_keyboard,
            R.id.mock_support to R.id.label_support,
        ),
        R.layout.onboarding_page_start to listOf(
            R.id.mock_enable to R.id.label_enable,
            R.id.mock_pick to R.id.label_pick,
        ),
    )

    private lateinit var container: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var dots: LinearLayout
    private lateinit var next: TextView
    private lateinit var skip: View
    private lateinit var gestures: GestureDetector

    private var index = 0
    private var sliding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this).nightMode())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        container = findViewById(R.id.pages)
        dots = findViewById(R.id.dots)
        next = findViewById(R.id.next_button)
        skip = findViewById(R.id.skip_button)

        pages = layouts.map { layout ->
            layoutInflater.inflate(layout, container, false).also { page ->
                val callout = page.findViewById<CalloutLayout>(R.id.page_callout)
                links[layout]?.forEach { (target, label) -> callout?.link(target, label) }
            }
        }
        buildDots()

        index = savedInstanceState?.getInt(KEY_INDEX, 0)?.coerceIn(pages.indices) ?: 0
        container.addView(pages[index])
        showChrome()

        skip.setOnClickListener { done() }
        next.setOnClickListener { if (index == pages.lastIndex) done() else go(index + 1) }

        val minFling = 500 * resources.displayMetrics.density
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // 세로로 스크롤하다 살짝 옆으로 샌 것까지 넘기면 짜증난다. 옆으로 확실할 때만.
                if (abs(velocityX) < minFling || abs(velocityX) < abs(velocityY) * 1.5f) return false
                go(if (velocityX < 0) index + 1 else index - 1)
                return true
            }
        })
    }

    /** 쪽 안의 스크롤·버튼이 손을 먼저 가져가도 옆으로 튕기는 것은 여기서 본다. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
    }

    /** 뒤로 가기는 앞 쪽으로. 첫 쪽에서 누르면 건너뛰기와 같다. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (index > 0) go(index - 1) else done()
    }

    private fun go(to: Int) {
        if (sliding || to !in pages.indices || to == index) return
        val direction = if (to > index) 1 else -1
        val width = container.width.toFloat()
        val leaving = pages[index]
        val coming = pages[to]

        sliding = true
        coming.translationX = direction * width
        container.addView(coming)
        coming.animate().translationX(0f).setDuration(SLIDE_MS).start()
        leaving.animate().translationX(-direction * width).setDuration(SLIDE_MS).withEndAction {
            container.removeView(leaving)
            leaving.translationX = 0f
            sliding = false
        }.start()

        index = to
        showChrome()
    }

    /** 쪽 표시, 다음/시작하기, 건너뛰기(마지막 쪽에선 필요 없다). */
    private fun showChrome() {
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val on = i == index
            dot.setBackgroundResource(if (on) R.drawable.bg_dot_on else R.drawable.bg_dot_off)
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(if (on) 18 else 7)
            }
        }
        val last = index == pages.lastIndex
        next.setText(if (last) R.string.guide_start else R.string.guide_next)
        skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
    }

    private fun buildDots() {
        repeat(pages.size) {
            dots.addView(View(this), LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun done() {
        Prefs.setOnboardingSeen(this, true)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_INDEX = "index"
        const val SLIDE_MS = 260L
    }
}
