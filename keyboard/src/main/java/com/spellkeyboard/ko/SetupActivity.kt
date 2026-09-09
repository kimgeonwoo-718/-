package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.spellkeyboard.core.correct.CorrectionEngine

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

        val output = findViewById<TextView>(R.id.selftest_output)
        findViewById<Button>(R.id.selftest_button).setOnClickListener {
            output.text = runSelfTest()
        }

        findViewById<CompoundButton>(R.id.auto_correct_switch).apply {
            isChecked = Prefs.autoCorrectEnabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoCorrectEnabled(this@SetupActivity, checked)
            }
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
        return SAMPLES.joinToString("\n") { (input, expected) ->
            val actual = engine.correct(input).text
            val mark = if (actual == expected) "OK  " else "FAIL"
            "$mark $input -> $actual"
        }
    }

    private companion object {
        val SAMPLES = listOf(
            "할수있다" to "할 수 있다",
            "됬다" to "됐다",
            "왠만하면" to "웬만하면",
            "먹을때" to "먹을 때",
            "할께요" to "할게요",
            "너때문에" to "너 때문에",
            "안녕하세요" to "안녕하세요"
        )
    }
}
