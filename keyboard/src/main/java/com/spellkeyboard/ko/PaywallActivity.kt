package com.spellkeyboard.ko

import android.graphics.Typeface
import android.os.Bundle
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
 * 설정의 "프리미엄" 버튼이 여기로 온다. 무료와 프리미엄을 표로 나란히 놓아 무엇을
 * 얼마에 사는지 먼저 보여주고, 그 다음에 결제창이다. 결제 자체는 [BillingManager] 가
 * Google Play 에 맡긴다.
 *
 * 표의 칸은 **✓ 아니면 ✗** 다. 둘 다 되는 것은 양쪽 ✓, 무료에 없는 것은 무료 쪽 ✗.
 * 예전에는 "기본 번역"/"AI 번역"처럼 글자로 된 칸이 있었는데, 무엇이 되고 안 되는지가
 * 한눈에 안 들어왔다. 횟수 줄만 숫자다 — 그것도 무료 쪽은 ✗ 다.
 *
 * 횟수는 서버 한도([DAILY_CHARS])에서 계산한다. 레이아웃에 박아 두면 한도를 바꿀 때
 * 어긋난다.
 */
class PaywallActivity : AppCompatActivity() {

    private var billing: BillingManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        findViewById<View>(R.id.paywall_back).setOnClickListener { finish() }

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

        findViewById<View>(R.id.paywall_cta).setOnClickListener { billing?.subscribe(this) }
        // 다른 기기에서 결제했거나 재설치한 사람. start() 가 Play 에 구매를 다시 물어본다.
        findViewById<View>(R.id.paywall_restore).setOnClickListener { billing?.start() }

        fillTable(findViewById(R.id.paywall_table))
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        super.onDestroy()
    }

    /** 표의 한 줄. [free]·[premium] 은 [CHECK], [CROSS], 아니면 그대로 보여 줄 글자. */
    private class Row(val label: String, val free: String, val premium: String)

    private fun fillTable(table: LinearLayout) {
        table.removeAllViews()
        for (row in features()) table.addView(rowView(row))
        table.addView(subheader(getString(R.string.paywall_quota_header, String.format(Locale.KOREA, "%,d", DAILY_CHARS))))
        for (row in quotaRows()) table.addView(rowView(row))
    }

    private fun rowView(row: Row): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        addView(TextView(context).apply {
            text = row.label
            setTextColor(ContextCompat.getColor(context, R.color.toss_text))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(cell(row.free, premium = false), LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(cell(row.premium, premium = true), LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 표 가운데 끼는 작은 제목. 횟수 줄들이 무엇을 기준으로 한 숫자인지 말해 준다. */
    private fun subheader(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.toss_text3))
        textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    /**
     * 표의 한 칸.
     *
     * ✓ 는 무료 쪽 회색, 프리미엄 쪽 파랑. ✗ 는 빨강 — 무료에 없는 것이 한눈에 보여야
     * 한다. 숫자는 프리미엄 쪽에만 오므로 파랗고 굵게.
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
            CROSS -> {
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.toss_red))
            }
            else -> {
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.toss_blue))
            }
        }
    }

    /** 위 네 줄은 둘 다 된다. 아래 두 줄이 프리미엄이다. */
    private fun features(): List<Row> = listOf(
        Row(getString(R.string.paywall_row_realtime), CHECK, CHECK),
        Row(getString(R.string.paywall_row_correct_all), CHECK, CHECK),
        Row(getString(R.string.paywall_row_device_translate), CHECK, CHECK),
        Row(getString(R.string.paywall_row_keyboard), CHECK, CHECK),
        Row(getString(R.string.paywall_row_ai), CROSS, CHECK),
        Row(getString(R.string.paywall_row_ai_translate), CROSS, CHECK)
    )

    /** 하루 한도로 몇 번인가. 무료는 AI 를 아예 못 쓰니 ✗ 다. */
    private fun quotaRows(): List<Row> {
        fun times(chars: Int) =
            getString(R.string.paywall_times, String.format(Locale.KOREA, "%,d", DAILY_CHARS / chars))
        return listOf(
            Row(getString(R.string.paywall_row_100), CROSS, times(100)),
            Row(getString(R.string.paywall_row_500), CROSS, times(500)),
            Row(getString(R.string.paywall_row_2000), CROSS, times(2_000))
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * 서버의 SUB_DAILY_CHARS 와 같아야 한다. 서버가 실제 한도이고 이건 안내다.
         *
         * 1만 자인 이유는 server/src/quota.js 머리말에 있다 — 어떻게 쓰든 구독 하나가
         * 적자를 못 내는 선이다.
         */
        const val DAILY_CHARS = 10_000

        private const val CHECK = "✓"
        private const val CROSS = "✗"
        private const val CLOSE_AFTER_MS = 1500L
    }
}
