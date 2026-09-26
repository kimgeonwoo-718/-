package com.spellkeyboard.ko

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 결제 화면.
 *
 * 설정의 "프리미엄" 버튼이 여기로 온다. 무료와 프리미엄을 표로 나란히 놓아 무엇을
 * 얼마에 사는지 먼저 보여주고, 그 다음에 결제창이다. 결제 자체는 [BillingManager] 가
 * Google Play 에 맡긴다.
 *
 * 표의 칸은 **✓ 아니면 ✗** 다. 둘 다 되는 것은 양쪽 ✓, 무료에 없는 것은 무료 쪽 ✗.
 * 예전에는 "기본 번역"/"AI 번역"처럼 글자로 된 칸이 있었는데, 무엇이 되고 안 되는지가
 * 한눈에 안 들어왔다.
 *
 * **하루 한도 숫자는 안 보여 준다.** 한때 "카톡 한 줄 기준 100번" 같은 줄이 있었는데,
 * 파는 건 "AI 를 쓸 수 있다" 지 글자 수가 아니다. 숫자가 보이면 아껴 쓰게 되고, 실제로
 * 닿는 사람도 거의 없다. 한도는 서버가 지키고, 닿으면 그때 서버가 알려 준다.
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

    /** 표의 한 줄. [sub] 는 이름 밑에 작게 붙는 설명 — AI 줄에 무엇이 AI 인지 적는다. */
    private class Row(val label: String, val free: String, val premium: String, val sub: String? = null)

    private fun fillTable(table: LinearLayout) {
        table.removeAllViews()
        for (row in rows()) table.addView(rowView(row))
    }

    private fun rowView(row: Row): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), dp(8), dp(8))
            addView(TextView(context).apply {
                text = row.label
                setTextColor(ContextCompat.getColor(context, R.color.toss_text))
                textSize = 15f
            })
            if (row.sub != null) addView(TextView(context).apply {
                text = row.sub
                setTextColor(ContextCompat.getColor(context, R.color.toss_text3))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(cell(row.free, premium = false), LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(cell(row.premium, premium = true), LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 표의 한 칸. ✓ 는 무료 쪽 회색, 프리미엄 쪽 파랑. ✗ 는 빨강 — 무료에 없는 것이 한눈에 보여야 한다. */
    private fun cell(value: String, premium: Boolean): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        text = value
        when (value) {
            CHECK -> {
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, if (premium) R.color.toss_blue else R.color.toss_text3))
            }
            else -> {
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.toss_red))
            }
        }
    }

    /** 위 네 줄은 둘 다 된다. 아래 두 줄(AI 교정·AI 번역)이 프리미엄이다 — 따로 설명한다. */
    private fun rows(): List<Row> = listOf(
        Row(getString(R.string.paywall_row_realtime), CHECK, CHECK),
        Row(getString(R.string.paywall_row_correct_all), CHECK, CHECK),
        Row(getString(R.string.paywall_row_device_translate), CHECK, CHECK),
        Row(getString(R.string.paywall_row_keyboard), CHECK, CHECK),
        Row(getString(R.string.paywall_row_ai), CROSS, CHECK, getString(R.string.paywall_row_ai_sub)),
        Row(getString(R.string.paywall_row_ai_translate), CROSS, CHECK, getString(R.string.paywall_row_ai_translate_sub)),
        // 테마와 소리는 따로 고른다(설정에서 각각 켠다). 표에서도 한 줄씩.
        Row(getString(R.string.paywall_row_sealion_theme), CROSS, CHECK, getString(R.string.paywall_row_sealion_theme_sub)),
        Row(getString(R.string.paywall_row_sealion_sound), CROSS, CHECK, getString(R.string.paywall_row_sealion_sound_sub))
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHECK = "✓"
        private const val CROSS = "✗"
        private const val CLOSE_AFTER_MS = 1500L
    }
}
