package com.spellkeyboard.ko

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 결제 화면.
 *
 * 설정의 "프리미엄" 버튼이 여기로 온다. 챗GPT Plus 업그레이드 화면의 구조를 따른다 —
 * 무료와 프리미엄을 표로 나란히 놓아 무엇을 얼마에 사는지 먼저 보여주고, 그 다음에
 * 결제창이다. 결제 자체는 [BillingManager] 가 Google Play 에 맡긴다.
 *
 * 비교표는 코드로 채운다. 숫자는 서버 한도([DAILY_CHARS])에서 나오므로 레이아웃에 박아
 * 두면 한도를 바꿀 때 어긋난다.
 */
class PaywallActivity : AppCompatActivity() {

    private var billing: BillingManager? = null
    private var premiumSelected = true

    private lateinit var segFree: TextView
    private lateinit var segPremium: TextView
    private lateinit var cta: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        findViewById<View>(R.id.paywall_back).setOnClickListener { finish() }
        paintTitle(findViewById(R.id.paywall_title))

        val status = findViewById<TextView>(R.id.paywall_status)
        billing = BillingManager(this) { message ->
            runOnUiThread {
                status.text = message
                // 구독이 확인됐으면 이 화면은 할 일이 끝났다. 잠깐 보여 주고 닫는다.
                if (Prefs.purchaseToken(this).isNotEmpty()) {
                    status.postDelayed({ if (!isFinishing) finish() }, CLOSE_AFTER_MS)
                }
            }
        }.also { it.start() }

        segFree = findViewById(R.id.paywall_seg_free)
        segPremium = findViewById(R.id.paywall_seg_premium)
        cta = findViewById(R.id.paywall_cta)
        segFree.setOnClickListener { selectPlan(premium = false) }
        segPremium.setOnClickListener { selectPlan(premium = true) }
        selectPlan(premium = true)

        cta.setOnClickListener {
            if (premiumSelected) billing?.subscribe(this) else finish()
        }
        // 다른 기기에서 결제했거나 재설치한 사람. start() 가 Play 에 구매를 다시 물어본다.
        findViewById<View>(R.id.paywall_restore).setOnClickListener { billing?.start() }

        fillTable(findViewById(R.id.paywall_table))
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        super.onDestroy()
    }

    /** "맞춤법 키보드 프리미엄" — 요금제 이름만 파랗게. */
    private fun paintTitle(title: TextView) {
        val app = getString(R.string.paywall_title_app)
        val plan = getString(R.string.paywall_title_plan)
        val text = SpannableStringBuilder("$app $plan")
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.toss_blue)),
            app.length + 1,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        title.text = text
    }

    /** 위의 알약과 아래 버튼이 같은 것을 가리키게 한다. */
    private fun selectPlan(premium: Boolean) {
        premiumSelected = premium
        segFree.isSelected = !premium
        segPremium.isSelected = premium
        cta.setText(if (premium) R.string.paywall_cta_upgrade else R.string.paywall_cta_free)
    }

    /** 무료와 프리미엄을 한 줄씩. 칸은 ✓, —, 또는 숫자. */
    private fun fillTable(table: LinearLayout) {
        table.removeAllViews()
        rows().forEach { (label, free, premium) ->
            table.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                addView(TextView(context).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(context, R.color.toss_text))
                    textSize = 16f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(cell(free, premium = false), LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(cell(premium, premium = true), LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    /**
     * 표의 한 칸. 무료 쪽 ✓ 는 회색, 프리미엄 쪽 ✓ 는 파랑 — 챗GPT 와 같다. — 는 회색.
     * 숫자는 글자 그대로.
     */
    private fun cell(value: String, premium: Boolean): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        text = value
        when (value) {
            CHECK -> {
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, if (premium) R.color.toss_blue else R.color.toss_text3))
            }
            DASH -> {
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.toss_text3))
            }
            else -> {
                textSize = 14f
                setTypeface(typeface, if (premium) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, if (premium) R.color.toss_blue else R.color.toss_text2))
            }
        }
    }

    private fun rows(): List<Triple<String, String, String>> {
        val freeTimes = getString(R.string.paywall_times, FREE_DAILY_CALLS.toString())
        fun premiumTimes(chars: Int) =
            getString(R.string.paywall_times, String.format(Locale.KOREA, "%,d", DAILY_CHARS / chars))
        return listOf(
            Triple(getString(R.string.paywall_row_realtime), CHECK, CHECK),
            Triple(getString(R.string.paywall_row_keyboard), CHECK, CHECK),
            Triple(getString(R.string.paywall_row_ai), getString(R.string.paywall_free_ai), getString(R.string.paywall_premium_ai)),
            Triple(getString(R.string.paywall_row_100), freeTimes, premiumTimes(100)),
            Triple(getString(R.string.paywall_row_500), freeTimes, premiumTimes(500)),
            Triple(getString(R.string.paywall_row_2000), freeTimes, premiumTimes(2_000))
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 서버의 SUB_DAILY_CHARS / FREE_DAILY_LIMIT 과 같아야 한다. 서버가 실제 한도이고 이건 안내다. */
        const val DAILY_CHARS = 100_000
        const val FREE_DAILY_CALLS = 5

        private const val CHECK = "✓"
        private const val DASH = "—"
        private const val CLOSE_AFTER_MS = 1500L
    }
}
