package com.spellkeyboard.ko

import android.content.Context
import com.spellkeyboard.core.ai.GeminiCorrector

/** 키보드 설정. 지금은 자동 교정 on/off 하나뿐이다. */
object Prefs {

    private const val FILE = "spell_keyboard"
    private const val KEY_AUTO_CORRECT = "auto_correct"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"

    fun autoCorrectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CORRECT, true)

    fun setAutoCorrectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CORRECT, enabled).apply()
    }

    /**
     * Gemini API 키. 사용자가 직접 넣는다.
     *
     * 앱 전용 저장소에 둔다. APK 에 키를 박아 넣으면 누구나 꺼내 쓸 수 있어서,
     * 개인이 쓰는 앱에서는 각자 자기 키를 넣는 쪽이 맞다.
     */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty().trim()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    /**
     * 쓸 모델 이름.
     *
     * 구글 쪽 모델 이름은 계속 바뀌어서 설정에서 갈아 끼울 수 있게 열어 뒀다.
     * 비워 두면 기본값을 쓴다.
     */
    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, "").orEmpty().trim()
            .ifEmpty { GeminiCorrector.DEFAULT_MODEL }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    /** 키가 있어야 AI 교정 버튼이 뜬다. */
    fun aiAvailable(context: Context): Boolean = apiKey(context).isNotEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
