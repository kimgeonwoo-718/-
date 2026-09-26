package com.spellkeyboard.ko

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * AI 로 글을 보내기 전에 한 번 묻는 창.
 *
 * AI 교정·AI 번역은 입력란의 글을 서버와 구글로 보낸다. 실시간 교정은 기기 안에서만 도니까,
 * 사용자는 "이 키보드는 글을 안 내보낸다" 고 알고 있을 수 있다. 그래서 **처음 누를 때** 무엇이
 * 어디로 가는지 보여 주고 동의를 받는다. 방침 페이지에만 적어 두면 아무도 안 읽는다.
 *
 * 키보드(서비스)가 띄운다. 동의해도 누르던 일을 이어 하지는 않는다 — 앱이 바뀌면서 입력란
 * 연결이 끊기기 때문이다. 대신 "다시 눌러 주세요" 라고 알린다.
 */
class AiConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        val text = ContextCompat.getColor(this, R.color.toss_text)
        val sub = ContextCompat.getColor(this, R.color.toss_text2)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            addView(TextView(context).apply {
                setText(R.string.ai_consent_title)
                setTextColor(text)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                setText(R.string.ai_consent_body)
                setTextColor(text)
                textSize = 15f
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(14), 0, 0)
            })
            addView(TextView(context).apply {
                setText(R.string.ai_consent_note)
                setTextColor(sub)
                textSize = 13f
                setPadding(0, dp(12), 0, 0)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
                addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.ai_consent_decline)
                    setTextColor(sub)
                    setOnClickListener { finish() }
                })
                addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.ai_consent_agree)
                    setTextColor(ContextCompat.getColor(context, R.color.toss_blue))
                    setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener {
                        Prefs.setAiConsented(this@AiConsentActivity, true)
                        Toast.makeText(this@AiConsentActivity, R.string.ai_consent_done, Toast.LENGTH_LONG).show()
                        finish()
                    }
                })
            })
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
