package com.spellkeyboard.ko

import android.content.Context

/** 키보드 설정. 지금은 자동 교정 on/off 하나뿐이다. */
object Prefs {

    private const val FILE = "spell_keyboard"
    private const val KEY_AUTO_CORRECT = "auto_correct"
    private const val KEY_API_KEY = "anthropic_api_key"

    fun autoCorrectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CORRECT, true)

    fun setAutoCorrectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CORRECT, enabled).apply()
    }

    /**
     * Anthropic API 키. 사용자가 직접 넣는다.
     *
     * 앱 전용 저장소에 둔다. APK 에 키를 박아 넣으면 누구나 꺼내 쓸 수 있어서,
     * 개인이 쓰는 앱에서는 각자 자기 키를 넣는 쪽이 맞다.
     */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty().trim()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    /** 키가 있어야 AI 교정 버튼이 뜬다. */
    fun aiAvailable(context: Context): Boolean = apiKey(context).isNotEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
