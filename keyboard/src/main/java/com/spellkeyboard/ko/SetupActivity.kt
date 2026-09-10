package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.billing.AiQuota
import com.spellkeyboard.core.billing.Tier
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
        apiKeyField.setText(Prefs.apiKey(this))
        modelField.setText(Prefs.model(this))
        findViewById<Button>(R.id.api_key_save).setOnClickListener {
            Prefs.setApiKey(this, apiKeyField.text.toString())
            Prefs.setModel(this, modelField.text.toString())
            Toast.makeText(this, R.string.setting_api_key_saved, Toast.LENGTH_LONG).show()
        }

        val modelsOutput = findViewById<TextView>(R.id.models_output)
        findViewById<Button>(R.id.list_models).setOnClickListener {
            val key = apiKeyField.text.toString().trim()
            val model = modelField.text.toString().trim()
            modelsOutput.setText(R.string.setting_listing_models)
            // 네트워크를 타므로 UI 스레드에서 하면 화면이 멎는다.
            Thread {
                val report = runDiagnosis(key, model.ifEmpty { GeminiCorrector.DEFAULT_MODEL })
                runOnUiThread { modelsOutput.text = report }
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

        quotaOutput = findViewById(R.id.quota_output)
        findViewById<CompoundButton>(R.id.subscriber_switch).apply {
            isChecked = Prefs.tier(this@SetupActivity) == Tier.SUBSCRIBER
            setOnCheckedChangeListener { _, checked ->
                Prefs.setTier(this@SetupActivity, if (checked) Tier.SUBSCRIBER else Tier.FREE)
                showQuota()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 키보드에서 쓰고 돌아오면 숫자가 줄어 있어야 한다.
        showQuota()
    }

    private fun showQuota() {
        val status = Prefs.quota(this).status()
        quotaOutput?.text = when (val remaining = status.remaining) {
            null -> getString(R.string.setting_quota_unlimited)
            else -> getString(R.string.setting_quota_free, remaining, AiQuota.FREE_DAILY_LIMIT)
        }
    }

    /**
     * AI 경로를 끝까지 밟아 보고 어디서 막히는지 보여준다.
     *
     * "AI 가 작동 안 함" 만으로는 원인이 열 가지다. 한 번 눌러 그걸 가른다.
     */
    private fun runDiagnosis(apiKey: String, model: String): String {
        if (apiKey.isEmpty()) return getString(R.string.setting_api_key_hint)

        val corrector = GeminiCorrector(apiKey, model)
        val checks = runCatching { corrector.diagnose() }.getOrElse { error ->
            return getString(
                R.string.setting_models_failed,
                error.message ?: error.javaClass.simpleName
            )
        }
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
