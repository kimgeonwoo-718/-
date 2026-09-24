package com.spellkeyboard.ko

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * 키보드 맞춤설정.
 *
 * 실시간 교정 스위치와 자판 모양(종류·밝기·배경·투명도). 한 번 맞춰 두면 잘 안 만지지만
 * 찾으면 있어야 하는 것. 더보기 서랍([MoreDrawer])의 "키보드 맞춤설정" 과 자판 도구 줄의 설정
 * 버튼이 여기로 온다.
 */
class SettingsActivity : AppCompatActivity() {

    private var backgroundStatus: TextView? = null

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
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this).nightMode())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // 밝게/어둡게를 바꾸면 액티비티가 통째로 다시 만들어진다. 스크롤 위치를 직접
        // 살려 놓지 않으면 그때마다 맨 위로 튄다. 레이아웃이 끝난 뒤에 옮겨야 한다 —
        // 그 전에는 내용 높이가 0 이라 무시된다.
        savedInstanceState?.getInt(KEY_SCROLL, 0)?.takeIf { it > 0 }?.let { y ->
            findViewById<View>(R.id.settings_scroll).post { findViewById<View>(R.id.settings_scroll).scrollTo(0, y) }
        }

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        bindCorrection()
        bindKeyboard()
    }

    override fun onResume() {
        super.onResume()
        // 자판 위 '교정' 버튼으로 껐다 켜고 돌아올 수 있다. 스위치가 그걸 따라가야 한다.
        findViewById<CompoundButton>(R.id.auto_correct_switch).isChecked = Prefs.autoCorrectEnabled(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, findViewById<View>(R.id.settings_scroll).scrollY)
    }

    // --- 교정 -----------------------------------------------------------------

    private fun bindCorrection() {
        findViewById<CompoundButton>(R.id.auto_correct_switch).apply {
            isChecked = Prefs.autoCorrectEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoCorrectEnabled(this@SettingsActivity, checked)
            }
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
            progress = Prefs.keyTransparency(this@SettingsActivity)
            show(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = show(value)

                // 끌고 있는 동안은 숫자만 바꾸고, 손을 뗄 때 저장한다. 끄는 내내 저장하면
                // 한 번 움직일 때마다 디스크에 쓴다.
                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    Prefs.setKeyTransparency(this@SettingsActivity, bar.progress)
                }
            })
        }
    }

    /**
     * 테마를 바꾸면 이 화면도 같은 밝기로 다시 뜬다 — 그래서 선택 표시를 따로 갱신할 필요가
     * 없다. 뒤에 깔린 첫 화면도 돌아갈 때 같은 밝기로 다시 만들어진다.
     */
    private fun chooseTheme(mode: ThemeMode) {
        if (mode == Prefs.themeMode(this)) return
        Prefs.setThemeMode(this, mode)
        AppCompatDelegate.setDefaultNightMode(mode.nightMode())
    }

    private fun showBackgroundStatus() {
        backgroundStatus?.setText(
            if (BackgroundImage.exists(this)) R.string.setting_background_set else R.string.setting_background_none
        )
    }

    private companion object {
        const val KEY_SCROLL = "scroll_y"
    }
}

/** 앱 화면 밝기. 첫 화면과 키보드 맞춤설정이 같은 값을 따른다. */
internal fun ThemeMode.nightMode(): Int = when (this) {
    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}
