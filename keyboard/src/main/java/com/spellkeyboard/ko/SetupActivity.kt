package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity

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

        findViewById<CompoundButton>(R.id.auto_correct_switch).apply {
            isChecked = Prefs.autoCorrectEnabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoCorrectEnabled(this@SetupActivity, checked)
            }
        }
    }
}
