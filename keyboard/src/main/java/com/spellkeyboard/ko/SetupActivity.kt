package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.correct.SelfTestSamples
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

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

        bindSetup()
        bindCorrection()
        bindKeyboard()
        bindTrouble()
    }

    override fun onResume() {
        super.onResume()
        // 시스템 설정에서 켜고 돌아오면 '완료' 로 바뀌어야 하고, 키보드에서 AI 를 쓰고
        // 돌아오면 숫자가 줄어 있어야 한다.
        showSetupProgress()
        showQuota()
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
        findViewById<View>(R.id.subscribe_button).setOnClickListener { billing?.subscribe(this) }
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

    // --- 문제 해결 -------------------------------------------------------------

    private fun bindTrouble() {
        val content = findViewById<View>(R.id.trouble_content)
        val chevron = findViewById<TextView>(R.id.trouble_chevron)
        findViewById<View>(R.id.trouble_toggle).setOnClickListener {
            content.isVisible = !content.isVisible
            chevron.text = if (content.isVisible) "▴" else "▾"
        }

        val selfTestOutput = findViewById<TextView>(R.id.selftest_output)
        findViewById<View>(R.id.selftest_button).setOnClickListener {
            selfTestOutput.setText(R.string.selftest_running)
            // 사전을 처음 여는 데 몇 초 걸린다. UI 스레드에서 하면 화면이 멎는다.
            Thread {
                val report = runSelfTest()
                runOnUiThread { selfTestOutput.text = report }
            }.start()
        }

        val modelsOutput = findViewById<TextView>(R.id.models_output)
        findViewById<View>(R.id.list_models).setOnClickListener {
            modelsOutput.setText(R.string.setting_listing_models)
            Thread {
                val report = runDiagnosis()
                runOnUiThread {
                    modelsOutput.text = report
                    showQuota()
                }
            }.start()
        }
    }

    /**
     * AI 경로를 끝까지 밟아 보고 어디서 막히는지 보여준다.
     *
     * 키보드가 실제로 쓰는 것과 **똑같은** 경로(중계 서버)를 탄다.
     */
    private fun runDiagnosis(): String {
        if (!Prefs.serverAvailable()) return getString(R.string.diag_no_server)
        val corrector = GeminiCorrector(
            "",
            GeminiCorrector.DEFAULT_MODEL,
            GeminiCorrector.HttpTransport(Prefs.serverHeaders(this)),
            baseUrl = Prefs.serverBase()
        )
        val checks = runCatching { corrector.diagnose() }.getOrElse { error ->
            return getString(R.string.setting_models_failed, error.message ?: error.javaClass.simpleName)
        }
        corrector.lastQuota?.let { Prefs.rememberQuota(this, it) }
        return checks.joinToString("\n") { check ->
            val mark = if (check.ok) "OK  " else "실패"
            "$mark ${check.name}\n     ${check.detail}"
        }
    }

    /**
     * 교정 엔진만 따로 돌려 본다.
     *
     * 키보드에서 교정이 안 될 때 원인이 엔진인지 키보드 연결부인지 가른다.
     */
    private fun runSelfTest(): String {
        val engine = CorrectionEngine()
        val dictionary = runCatching {
            Spacer(SpacingDictionary.open(File(filesDir, "spacing")))
        }
        // 키보드와 **똑같이** 차려야 진단이 의미가 있다. 여기서 speller 를 빼먹으면
        // 자체 점검은 통과하는데 실기기에서는 안 잡히는, 최악의 거짓 신호가 나온다.
        dictionary.getOrNull()?.let {
            engine.spacer = it
            engine.speller = Speller(it)
        }

        val lines = SelfTestSamples.ALL.map { (input, expected) ->
            val actual = engine.correct(input).text
            val mark = if (actual == expected) "OK  " else "FAIL"
            "$mark $input -> $actual"
        }
        val header = if (dictionary.isSuccess) "띄어쓰기 사전: 준비됨" else "띄어쓰기 사전: 열지 못함 (규칙만 동작)"
        return (listOf(header) + lines).joinToString("\n")
    }
}
