package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * 설정 화면.
 *
 * 위에서부터: 시작하기(켜기·선택) → 써 보기 → 교정(실시간 스위치, AI 와 요금제) →
 * 키보드(자판·테마·배경) → 문제 해결(접혀 있음). 사용자가 매일 만지는 것이 위에,
 * 한 번 하고 마는 것이 아래에 온다. API 키나 모델 이름 같은 내부 사정은 보여 주지 않는다.
 */
class SetupActivity : AppCompatActivity() {

    private var quotaOutput: TextView? = null
    private var backgroundStatus: TextView? = null
    private var billing: BillingManager? = null

    /**
     * 시스템 사진 선택창. 저장소 권한 없이 사용자가 고른 그 한 장만 받는다.
     * 원본은 크므로 [BackgroundImage] 가 줄여서 앱 폴더에 넣는다 — 그 동안 화면이
     * 멎지 않게 다른 스레드에서 한다.
     */
    private val pickBackground = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        backgroundStatus?.setText(R.string.setting_background_saving)
        Thread {
            val saved = BackgroundImage.save(this, uri)
            runOnUiThread {
                if (!saved) Toast.makeText(this, R.string.setting_background_failed, Toast.LENGTH_LONG).show()
                showBackgroundStatus()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 설정 화면도 키보드와 같은 밝기를 따른다. 키보드는 어두운데 설정만 하얗게 뜨면 어색하다.
        AppCompatDelegate.setDefaultNightMode(nightModeOf(Prefs.themeMode(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        // 밝게/어둡게를 바꾸면 액티비티가 통째로 다시 만들어진다. 스크롤 위치를 직접
        // 살려 놓지 않으면 그때마다 맨 위로 튄다 — 실기기에서 그렇게 보였다.
        // 레이아웃이 끝난 뒤에 옮겨야 한다. 그 전에는 내용 높이가 0 이라 무시된다.
        savedInstanceState?.getInt(KEY_SCROLL, 0)?.takeIf { it > 0 }?.let { y ->
            findViewById<View>(R.id.setup_scroll).post { findViewById<View>(R.id.setup_scroll).scrollTo(0, y) }
        }

        bindSetup()
        bindCorrection()
        bindKeyboard()
    }

    override fun onResume() {
        super.onResume()
        // 시스템 설정에서 켜고 돌아오면 '완료' 로 바뀌어야 하고, 키보드에서 AI 를 쓰고
        // 돌아오면 숫자가 줄어 있어야 한다.
        showSetupProgress()
        showQuota()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, findViewById<View>(R.id.setup_scroll).scrollY)
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        super.onDestroy()
    }

    // --- 시작하기 -------------------------------------------------------------

    private fun bindSetup() {
        findViewById<View>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.pick_button).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    /** 두 단계가 어디까지 됐는지 시스템에 물어 표시한다. 사용자가 기억할 필요가 없다. */
    private fun showSetupProgress() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            .orEmpty().startsWith(packageName)

        markStep(findViewById(R.id.enable_button), enabled)
        markStep(findViewById(R.id.pick_button), selected)
        findViewById<View>(R.id.setup_all_done).isVisible = enabled && selected
    }

    private fun markStep(pill: TextView, done: Boolean) {
        if (done) {
            pill.setText(R.string.setup_done)
            pill.setBackgroundResource(R.drawable.bg_badge)
            pill.setTextColor(ContextCompat.getColor(this, R.color.toss_blue))
        } else {
            pill.setText(R.string.setup_open)
            pill.setBackgroundResource(R.drawable.bg_button_secondary)
            pill.setTextColor(ContextCompat.getColor(this, R.color.toss_text))
        }
    }

    // --- 교정 -----------------------------------------------------------------

    private fun bindCorrection() {
        findViewById<CompoundButton>(R.id.auto_correct_switch).apply {
            isChecked = Prefs.autoCorrectEnabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoCorrectEnabled(this@SetupActivity, checked)
            }
        }

        quotaOutput = findViewById(R.id.quota_output)
        val billingStatus = findViewById<TextView>(R.id.billing_status)
        billing = BillingManager(this) { message ->
            runOnUiThread {
                billingStatus.text = message
                showQuota()
            }
        }.also { it.start() }
        // 결제창을 바로 띄우지 않는다. 무엇을 얼마에 사는지 먼저 보여주는 화면을 거친다.
        findViewById<View>(R.id.subscribe_button).setOnClickListener {
            startActivity(android.content.Intent(this, PaywallActivity::class.java))
        }
    }

    /**
     * 요금 상태 한 줄. 판단은 서버가 하고, 여기 보이는 숫자는 서버가 마지막으로 알려 준 것이다.
     */
    private fun showQuota() {
        val view = quotaOutput ?: return
        if (!Prefs.serverAvailable()) {
            view.setText(R.string.setting_quota_no_server)
            return
        }
        val quota = Prefs.lastQuota(this)
        view.text = when {
            quota == null -> getString(R.string.setting_quota_unknown)
            quota.remaining == null -> getString(R.string.setting_quota_unlimited)
            quota.countsChars -> getString(
                R.string.setting_quota_premium,
                String.format(java.util.Locale.KOREA, "%,d", quota.remaining)
            )
            else -> getString(R.string.setting_quota_free, quota.remaining, quota.limit ?: 0)
        }
    }

    // --- 키보드 ---------------------------------------------------------------

    private fun bindKeyboard() {
        val qwerty = findViewById<TextView>(R.id.layout_qwerty)
        val cheonjiin = findViewById<TextView>(R.id.layout_cheonjiin)
        fun showLayout() {
            val current = Prefs.layoutType(this)
            qwerty.isSelected = current == LayoutType.QWERTY
            cheonjiin.isSelected = current == LayoutType.CHEONJIIN
        }
        showLayout()
        qwerty.setOnClickListener { Prefs.setLayoutType(this, LayoutType.QWERTY); showLayout() }
        cheonjiin.setOnClickListener { Prefs.setLayoutType(this, LayoutType.CHEONJIIN); showLayout() }

        val light = findViewById<TextView>(R.id.theme_light)
        val dark = findViewById<TextView>(R.id.theme_dark)
        val currentTheme = Prefs.themeMode(this)
        light.isSelected = currentTheme == ThemeMode.LIGHT
        dark.isSelected = currentTheme == ThemeMode.DARK
        light.setOnClickListener { chooseTheme(ThemeMode.LIGHT) }
        dark.setOnClickListener { chooseTheme(ThemeMode.DARK) }

        backgroundStatus = findViewById(R.id.background_status)
        showBackgroundStatus()
        findViewById<View>(R.id.background_pick).setOnClickListener {
            pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.background_clear).setOnClickListener {
            BackgroundImage.clear(this)
            showBackgroundStatus()
        }

        val transparencyValue = findViewById<TextView>(R.id.key_transparency_value)
        findViewById<SeekBar>(R.id.key_transparency).apply {
            fun show(percent: Int) {
                transparencyValue.text = getString(R.string.setting_key_transparency_value, percent)
            }
            progress = Prefs.keyTransparency(this@SetupActivity)
            show(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = show(value)

                // 끌고 있는 동안은 숫자만 바꾸고, 손을 뗄 때 저장한다. 끄는 내내 저장하면
                // 한 번 움직일 때마다 디스크에 쓴다.
                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    Prefs.setKeyTransparency(this@SetupActivity, bar.progress)
                }
            })
        }
    }

    /** 테마를 바꾸면 이 화면도 같은 밝기로 다시 뜬다 — 그래서 선택 표시를 따로 갱신할 필요가 없다. */
    private fun chooseTheme(mode: ThemeMode) {
        if (mode == Prefs.themeMode(this)) return
        Prefs.setThemeMode(this, mode)
        AppCompatDelegate.setDefaultNightMode(nightModeOf(mode))
    }

    private fun showBackgroundStatus() {
        backgroundStatus?.setText(
            if (BackgroundImage.exists(this)) R.string.setting_background_set else R.string.setting_background_none
        )
    }

    private fun nightModeOf(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    companion object {
        private const val KEY_SCROLL = "scroll_y"
    }
}
