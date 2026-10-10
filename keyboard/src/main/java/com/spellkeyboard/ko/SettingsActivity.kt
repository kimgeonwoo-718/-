package com.spellkeyboard.ko

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.spellkeyboard.ko.llm.LlmModelStore
import com.spellkeyboard.ko.llm.LlmSupport

/**
 * 키보드 맞춤설정.
 *
 * 자판 모양(종류·밝기·배경·투명도)과 소리. 실시간 교정 켬/끔은 자판 위 'LIVE' 에만 있다. 한 번 맞춰 두면 잘 안 만지지만
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
        bindKeyboard()
        bindLlm()
        bindSound()
    }

    override fun onDestroy() {
        llmHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, findViewById<View>(R.id.settings_scroll).scrollY)
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
        val seaLion = findViewById<TextView>(R.id.theme_sealion)
        // 구독이 끝났으면 저장된 값이 바다사자여도 밝게로 그려진다 — 표시도 실제를 따른다.
        val currentTheme = Prefs.effectiveTheme(this)
        light.isSelected = currentTheme == ThemeMode.LIGHT
        dark.isSelected = currentTheme == ThemeMode.DARK
        seaLion.isSelected = currentTheme == ThemeMode.SEA_LION
        markPremium(seaLion, R.string.setting_theme_sealion)
        light.setOnClickListener { chooseTheme(ThemeMode.LIGHT) }
        dark.setOnClickListener { chooseTheme(ThemeMode.DARK) }
        seaLion.setOnClickListener { chooseTheme(ThemeMode.SEA_LION) }

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
        if (mode.premium && !premiumOrPaywall()) return
        val before = Prefs.effectiveTheme(this)
        if (mode == before) return
        Prefs.setThemeMode(this, mode)
        AppCompatDelegate.setDefaultNightMode(mode.nightMode())
        // 밝기가 같은 테마끼리(밝게 ↔ 바다사자)는 화면이 다시 안 만들어져서 선택 표시가 그대로다.
        if (before.nightMode() == mode.nightMode()) recreate()
    }

    // --- 고성능 번역 (기기 안 AI 모델) ---------------------------------------------

    private val llmHandler = Handler(Looper.getMainLooper())

    /**
     * 모델을 받고 지우고 켜고 끄는 카드. 상태는 [LlmModelStore] 가 쥐고 있고, 받는 동안은 1초마다 다시 그린다.
     * 기기가 못 돌리면 이유만 보이고 받기 단추는 숨는다 — 못 쓸 파일을 3GB 받게 하지 않는다.
     */
    private fun bindLlm() {
        val status = findViewById<TextView>(R.id.llm_status)
        val action = findViewById<TextView>(R.id.llm_action)
        val delete = findViewById<TextView>(R.id.llm_delete)
        val progress = findViewById<android.widget.ProgressBar>(R.id.llm_progress)
        val useRow = findViewById<View>(R.id.llm_use_row)
        val on = findViewById<TextView>(R.id.llm_on)
        val off = findViewById<TextView>(R.id.llm_off)

        fun showUse() {
            val enabled = Prefs.llmEnabled(this)
            on.isSelected = enabled
            off.isSelected = !enabled
        }

        fun render() {
            val reason = LlmSupport.check(this)
            if (reason != LlmSupport.Reason.OK) {
                status.setText(
                    when (reason) {
                        LlmSupport.Reason.NOT_ARM64 -> R.string.setting_llm_reason_not_arm64
                        LlmSupport.Reason.OLD_ANDROID -> R.string.setting_llm_reason_old_android
                        LlmSupport.Reason.LOW_MEMORY -> R.string.setting_llm_reason_low_memory
                        else -> R.string.setting_llm_reason_old_cpu
                    }
                )
                action.visibility = View.GONE
                delete.visibility = View.GONE
                progress.visibility = View.GONE
                useRow.visibility = View.GONE
                return
            }
            val now = LlmModelStore.status(this)
            val running = now.state == LlmModelStore.State.DOWNLOADING || now.state == LlmModelStore.State.VERIFYING
            progress.visibility = if (now.state == LlmModelStore.State.DOWNLOADING) View.VISIBLE else View.GONE
            progress.progress = (now.fraction * 1000).toInt()
            useRow.visibility = if (now.state == LlmModelStore.State.READY) View.VISIBLE else View.GONE
            delete.visibility = if (now.state == LlmModelStore.State.READY || (now.doneBytes > 0 && !running)) View.VISIBLE else View.GONE
            action.visibility = View.VISIBLE
            when (now.state) {
                LlmModelStore.State.NOT_INSTALLED -> {
                    status.setText(R.string.setting_llm_status_none)
                    action.setText(R.string.setting_llm_get)
                }
                LlmModelStore.State.DOWNLOADING -> {
                    status.text = getString(
                        R.string.setting_llm_status_downloading,
                        (now.fraction * 100).toInt(),
                        Formatter.formatShortFileSize(this, now.doneBytes),
                        Formatter.formatShortFileSize(this, now.totalBytes)
                    )
                    action.setText(R.string.setting_llm_cancel)
                }
                LlmModelStore.State.VERIFYING -> {
                    status.setText(R.string.setting_llm_status_verifying)
                    action.visibility = View.GONE
                }
                LlmModelStore.State.READY -> {
                    status.setText(R.string.setting_llm_status_ready)
                    action.visibility = View.GONE
                }
                LlmModelStore.State.FAILED -> {
                    status.text = getString(R.string.setting_llm_status_failed, now.message.orEmpty())
                    action.setText(R.string.setting_llm_get)
                }
            }
            if (running) llmHandler.postDelayed({ render() }, 1000)
        }

        fun begin(allowMetered: Boolean) {
            LlmModelStore.start(this, allowMetered) { runOnUiThread { render() } }
            render()
        }

        action.setOnClickListener {
            val now = LlmModelStore.status(this)
            when (now.state) {
                LlmModelStore.State.DOWNLOADING -> {
                    LlmModelStore.cancel()
                    llmHandler.postDelayed({ render() }, 400)
                }
                LlmModelStore.State.NOT_INSTALLED, LlmModelStore.State.FAILED -> {
                    if (!LlmModelStore.hasRoom(this)) {
                        Toast.makeText(this, R.string.setting_llm_no_room, Toast.LENGTH_LONG).show()
                    } else if (LlmModelStore.onMeteredNetwork(this)) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.setting_llm_metered_title)
                            .setMessage(R.string.setting_llm_metered_message)
                            .setPositiveButton(R.string.setting_llm_metered_data) { _, _ -> begin(true) }
                            .setNegativeButton(R.string.setting_llm_metered_wifi, null)
                            .show()
                    } else {
                        begin(false)
                    }
                }
                else -> Unit
            }
        }
        delete.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.setting_llm_delete_confirm)
                .setPositiveButton(R.string.setting_llm_delete_yes) { _, _ ->
                    // 3GB 를 지우고 받던 스레드를 기다리므로 화면 스레드에서 하지 않는다.
                    Thread {
                        LlmModelStore.delete(this)
                        runOnUiThread { render() }
                    }.start()
                }
                .setNegativeButton(R.string.setting_llm_delete_no, null)
                .show()
        }
        on.setOnClickListener { Prefs.setLlmEnabled(this, true); showUse() }
        off.setOnClickListener { Prefs.setLlmEnabled(this, false); showUse() }

        showUse()
        render()
    }

    // --- 소리 -----------------------------------------------------------------

    private fun bindSound() {
        val none = findViewById<TextView>(R.id.sound_none)
        val seaLion = findViewById<TextView>(R.id.sound_sealion)
        markPremium(seaLion, R.string.setting_sound_sealion)
        fun show() {
            val chosen = Prefs.soundPack(this)
            val current = if (chosen.premium && !Premium.active(this)) SoundPack.NONE else chosen
            none.isSelected = current == SoundPack.NONE
            seaLion.isSelected = current == SoundPack.SEA_LION
        }
        show()
        none.setOnClickListener { Prefs.setSoundPack(this, SoundPack.NONE); show() }
        seaLion.setOnClickListener {
            if (!premiumOrPaywall()) return@setOnClickListener
            Prefs.setSoundPack(this, SoundPack.SEA_LION)
            show()
        }

        val volumeValue = findViewById<TextView>(R.id.sound_volume_value)
        findViewById<SeekBar>(R.id.sound_volume).apply {
            fun showVolume(percent: Int) {
                volumeValue.text = getString(R.string.setting_sound_volume_value, percent)
            }
            progress = Prefs.soundVolume(this@SettingsActivity)
            showVolume(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = showVolume(value)
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    Prefs.setSoundVolume(this@SettingsActivity, bar.progress)
                }
            })
        }
    }

    /** 구독자 전용 항목에 자물쇠를 단다. 구독자에게는 안 단다. */
    private fun markPremium(view: TextView, label: Int) {
        view.text = if (Premium.active(this)) getString(label) else getString(R.string.premium_lock_suffix, getString(label))
    }

    /** 구독자면 true. 아니면 알려 주고 구독 화면을 연다. */
    private fun premiumOrPaywall(): Boolean {
        if (Premium.active(this)) return true
        Toast.makeText(this, R.string.premium_only_sealion, Toast.LENGTH_SHORT).show()
        startActivity(android.content.Intent(this, PaywallActivity::class.java))
        return false
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
    ThemeMode.LIGHT, ThemeMode.SEA_LION -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}
