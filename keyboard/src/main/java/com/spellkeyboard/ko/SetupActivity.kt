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
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.io.File

/**
 * 키보드를 켜고 시험해 보는 화면.
 *
 * IME 는 설치만으로는 쓸 수 없다. 시스템 설정에서 활성화한 뒤 입력기로 선택해야 해서,
 * 그 두 단계를 바로 열어 주는 버튼을 둔다.
 */
class SetupActivity : AppCompatActivity() {

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
            modelsOutput.setText(R.string.setting_listing_models)
            // 모델 이름을 추측하는 대신 API 에 물어본다. 키마다 쓸 수 있는 것이 다르다.
            Thread {
                val report = describeModels(key)
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
    }

    /** 이 키로 실제로 쓸 수 있는 모델을 물어본다. */
    private fun describeModels(apiKey: String): String {
        if (apiKey.isEmpty()) return getString(R.string.setting_api_key_hint)

        val result = GeminiCorrector(apiKey).availableModels()
        val models = result.getOrElse { error ->
            return getString(
                R.string.setting_models_failed,
                error.message ?: error.javaClass.simpleName
            )
        }
        if (models.isEmpty()) return getString(R.string.setting_models_empty)

        // 이름이 열 개도 넘게 온다. 교정에 쓸 만한 것을 짚어 준다.
        val recommended = GeminiCorrector.pickModel(models)
        val listing = models.joinToString("\n") { if (it == recommended) "★ $it" else "  $it" }
        return getString(R.string.setting_models_header) + "\n\n" + listing
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
        engine.spacer = dictionary.getOrNull()

        val lines = SAMPLES.map { (input, expected) ->
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

    private companion object {
        val SAMPLES = listOf(
            "할수있다" to "할 수 있다",
            "됬다" to "됐다",
            "왠만하면" to "웬만하면",
            "먹을때" to "먹을 때",
            "할께요" to "할게요",
            "너때문에" to "너 때문에",
            "아버지가방에들어가신다" to "아버지 가방에 들어가신다",
            "오늘은날씨가좋아서기분이좋다" to "오늘은 날씨가 좋아서 기분이 좋다",
            "안녕하세요" to "안녕하세요"
        )
    }
}
