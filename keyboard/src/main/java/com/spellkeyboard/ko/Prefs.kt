package com.spellkeyboard.ko

import android.content.Context
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.billing.AiQuota
import com.spellkeyboard.core.billing.Tier

/** 키보드 설정. */
object Prefs {

    private const val FILE = "spell_keyboard"
    private const val KEY_AUTO_CORRECT = "auto_correct"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_TIER = "tier"
    private const val KEY_QUOTA_DAY = "quota_day"
    private const val KEY_QUOTA_USED = "quota_used"

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

    /**
     * 요금제.
     *
     * 결제가 아직 없어서 설정에서 손으로 바꾼다. 결제가 붙으면 그쪽이 이 값을 세우고,
     * 나머지 코드는 손댈 것이 없다.
     */
    fun tier(context: Context): Tier =
        runCatching { Tier.valueOf(prefs(context).getString(KEY_TIER, "").orEmpty()) }
            .getOrDefault(Tier.FREE)

    fun setTier(context: Context, tier: Tier) {
        prefs(context).edit().putString(KEY_TIER, tier.name).apply()
    }

    /** AI 전체 교정 사용량. 온디바이스 교정은 여기 걸리지 않는다. */
    fun quota(context: Context): AiQuota = AiQuota(QuotaStore(context.applicationContext))

    private class QuotaStore(private val context: Context) : AiQuota.Store {
        // Prefs. 를 붙여 둔다 — 안 붙이면 바로 위 tier() 를 부르는 것처럼 읽힌다.
        override fun tier(): Tier = Prefs.tier(context)

        override fun day(): Long = prefs(context).getLong(KEY_QUOTA_DAY, 0L)

        override fun used(): Int = prefs(context).getInt(KEY_QUOTA_USED, 0)

        override fun save(day: Long, used: Int) {
            prefs(context).edit()
                .putLong(KEY_QUOTA_DAY, day)
                .putInt(KEY_QUOTA_USED, used)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
