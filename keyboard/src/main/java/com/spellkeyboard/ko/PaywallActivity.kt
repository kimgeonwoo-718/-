package com.spellkeyboard.ko

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
 * 설정의 "프리미엄" 버튼이 여기로 온다. 결제창을 바로 띄우지 않고 무료와 프리미엄을
 * 나란히 놓아 무엇을 얼마에 사는지 먼저 보여준다 — 챗GPT 의 업그레이드 화면과 같은
 * 구조다. 결제 자체는 [BillingManager] 가 Google Play 에 맡긴다.
 *
 * "10만 자면 이만큼" 표는 코드로 채운다. 숫자는 서버 한도([DAILY_CHARS])에서 나오므로
 * 레이아웃에 박아 두면 한도를 바꿀 때 어긋난다.
 */
class PaywallActivity : AppCompatActivity() {

    private var billing: BillingManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        findViewById<View>(R.id.paywall_close).setOnClickListener { finish() }

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

        fillTable(findViewById(R.id.paywall_table))
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        super.onDestroy()
    }

    /** 글 길이별로 하루에 몇 번 고칠 수 있는지. 사람이 감을 잡을 만한 길이 다섯 개. */
    private fun fillTable(table: LinearLayout) {
        table.removeAllViews()
        SAMPLE_LENGTHS.forEachIndexed { index, (label, chars) ->
            if (index > 0) table.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.toss_divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

            val times = DAILY_CHARS / chars
            table.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                addView(TextView(context).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(context, R.color.toss_text))
                    textSize = 15f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = getString(R.string.paywall_times, String.format(Locale.KOREA, "%,d", times))
                    setTextColor(ContextCompat.getColor(context, R.color.toss_blue))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 서버의 SUB_DAILY_CHARS 와 같아야 한다. 서버가 실제 한도이고 이건 안내다. */
        const val DAILY_CHARS = 100_000

        private const val CLOSE_AFTER_MS = 1500L

        /** 표에 보여줄 길이. 라벨은 사람이 겪는 글의 크기로 붙였다. */
        private val SAMPLE_LENGTHS = listOf(
            "카톡 한 줄 (100자)" to 100,
            "짧은 글 (300자)" to 300,
            "긴 문자 (500자)" to 500,
            "메모·이메일 (1,000자)" to 1_000,
            "긴 글 (2,000자)" to 2_000
        )
    }
}
