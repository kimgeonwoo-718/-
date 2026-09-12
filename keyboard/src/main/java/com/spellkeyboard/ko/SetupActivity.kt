package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.correct.SelfTestSamples
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

/**
 * 키보드를 켜고 시험해 보는 화면.
 *
 * IME 는 설치만으로는 쓸 수 없다. 시스템 설정에서 활성화한 뒤 입력기로 선택해야 해서,
 * 그 두 단계를 바로 열어 주는 버튼을 둔다.
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

        findViewById<Button>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.pick_button).setOnClickListener {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showInputMethodPicker()
        }

        val apiKeyField = findViewById<EditText>(R.id.api_key_field)
        val modelField = findViewById<EditText>(R.id.model_field)
        apiKeyField.setText(Prefs.userApiKey(this))
        modelField.setText(Prefs.model(this))
        findViewById<Button>(R.id.api_key_save).setOnClickListener {
            Prefs.setApiKey(this, apiKeyField.text.toString())
            Prefs.setModel(this, modelField.text.toString())
            Toast.makeText(this, R.string.setting_api_key_saved, Toast.LENGTH_LONG).show()
            showQuota()
        }

        val modelsOutput = findViewById<TextView>(R.id.models_output)
        findViewById<Button>(R.id.list_models).setOnClickListener {
            val key = apiKeyField.text.toString().trim()
            val model = modelField.text.toString().trim()
            modelsOutput.setText(R.string.setting_listing_models)
            // 네트워크를 타므로 UI 스레드에서 하면 화면이 멎는다.
            Thread {
                val report = runDiagnosis(key, model.ifEmpty { GeminiCorrector.DEFAULT_MODEL })
                runOnUiThread {
                    modelsOutput.text = report
                    showQuota()
                }
            }.start()
        }

        val output = findViewById<TextView>(R.id.selftest_output)
        findViewById<Button>(R.id.selftest_button).setOnClickListener {
            output.setText(R.string.selftest_running)
            // 사전을 처음 여는 데 몇 초 걸린다. UI 스레드에서 하면 화면이 멎는다.
            Thread {
                val report = runSelfTest()
                runOnUiThread { output.text = report }
            }.start()
        }

        findViewById<CompoundButton>(R.id.auto_correct_switch).apply {
            isChecked = Prefs.autoCorrectEnabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoCorrectEnabled(this@SetupActivity, checked)
            }
        }

        findViewById<RadioGroup>(R.id.theme_group).apply {
            check(
                when (Prefs.themeMode(this@SetupActivity)) {
                    ThemeMode.SYSTEM -> R.id.theme_system
                    ThemeMode.LIGHT -> R.id.theme_light
                    ThemeMode.DARK -> R.id.theme_dark
                }
            )
            // 리스너는 초기값을 넣은 **뒤에** 건다. 먼저 걸면 초기값 넣는 순간 화면이 다시 뜬다.
            setOnCheckedChangeListener { _, checkedId ->
                val mode = when (checkedId) {
                    R.id.theme_light -> ThemeMode.LIGHT
                    R.id.theme_dark -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                if (mode == Prefs.themeMode(this@SetupActivity)) return@setOnCheckedChangeListener
                Prefs.setThemeMode(this@SetupActivity, mode)
                AppCompatDelegate.setDefaultNightMode(nightModeOf(mode))
            }
        }

        backgroundStatus = findViewById(R.id.background_status)
        showBackgroundStatus()
        findViewById<Button>(R.id.background_pick).setOnClickListener {
            pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.background_clear).setOnClickListener {
            BackgroundImage.clear(this)
            showBackgroundStatus()
        }

        quotaOutput = findViewById(R.id.quota_output)
        val billingStatus = findViewById<TextView>(R.id.billing_status)
        billing = BillingManager(this) { message ->
            runOnUiThread {
                billingStatus.text = message
                showQuota()
            }
        }.also { it.start() }
        findViewById<Button>(R.id.subscribe_button).setOnClickListener {
            billing?.subscribe(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 키보드에서 쓰고 돌아오면 숫자가 줄어 있어야 한다.
        showQuota()
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        super.onDestroy()
    }

    private fun showBackgroundStatus() {
        backgroundStatus?.setText(
            if (BackgroundImage.exists(this)) R.string.setting_background_set else R.string.setting_background_none
        )
    }

    private fun nightModeOf(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    /**
     * 요금 상태 한 줄.
     *
     * 판단은 서버가 하고, 여기 보이는 숫자는 서버가 마지막으로 알려 준 것이다. 자기 키를
     * 쓰는 사람은 서버를 안 거치니 한도가 없다.
     */
    private fun showQuota() {
        val view = quotaOutput ?: return
        if (Prefs.usingOwnKey(this)) {
            view.setText(R.string.setting_quota_own_key)
            return
        }
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

    /**
     * AI 경로를 끝까지 밟아 보고 어디서 막히는지 보여준다.
     *
     * "AI 가 작동 안 함" 만으로는 원인이 열 가지다. 한 번 눌러 그걸 가른다. 키보드가
     * 실제로 쓰는 것과 **똑같은** 경로를 탄다 — 칸이 비었으면 중계 서버, 아니면 내 키.
     */
    private fun runDiagnosis(typedKey: String, model: String): String {
        val corrector = when {
            typedKey.isNotEmpty() -> GeminiCorrector(typedKey, model, GeminiCorrector.HttpTransport())
            Prefs.serverAvailable() -> GeminiCorrector(
                "",
                model,
                GeminiCorrector.HttpTransport(Prefs.serverHeaders(this)),
                baseUrl = Prefs.serverBase()
            )
            else -> return getString(R.string.diag_no_key_at_all)
        }

        val checks = runCatching { corrector.diagnose() }.getOrElse { error ->
            return getString(
                R.string.setting_models_failed,
                error.message ?: error.javaClass.simpleName
            )
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
     * 여기서 전부 통과하는데 키보드에서 안 고쳐지면 문제는 연결부에 있다.
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
        val header = if (dictionary.isSuccess) {
            "띄어쓰기 사전: 준비됨"
        } else {
            "띄어쓰기 사전: 열지 못함 (규칙만 동작)"
        }
        return (listOf(header) + lines).joinToString("\n")
    }
}
