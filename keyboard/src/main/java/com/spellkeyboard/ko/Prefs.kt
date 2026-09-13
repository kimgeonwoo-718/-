package com.spellkeyboard.ko

import android.content.Context
import com.spellkeyboard.core.ai.GeminiCorrector
import com.spellkeyboard.core.clipboard.ClipboardHistory
import java.util.UUID

/** 키보드 설정. */
object Prefs {

    private const val FILE = "spell_keyboard"
    private const val KEY_AUTO_CORRECT = "auto_correct"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LAYOUT = "layout_type"
    private const val KEY_TRANSLATE_TARGET = "translate_target"
    private const val KEY_KEY_ALPHA = "key_alpha_percent"
    private const val KEY_CLIPBOARD = "clipboard"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_PURCHASE_TOKEN = "purchase_token"
    private const val KEY_QUOTA_PLAN = "quota_plan"
    private const val KEY_QUOTA_REMAINING = "quota_remaining"
    private const val KEY_QUOTA_LIMIT = "quota_limit"
    private const val KEY_QUOTA_UNIT = "quota_unit"

    fun autoCorrectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CORRECT, true)

    fun setAutoCorrectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CORRECT, enabled).apply()
    }

    // --- 모양 ------------------------------------------------------------------

    fun themeMode(context: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs(context).getString(KEY_THEME, "").orEmpty()) }
            .getOrDefault(ThemeMode.LIGHT) // 예전 값 "SYSTEM" 도 여기로 떨어진다

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.name).apply()
    }

    /** 한글 자판: 쿼티(두벌식) 또는 천지인. */
    fun layoutType(context: Context): LayoutType =
        runCatching { LayoutType.valueOf(prefs(context).getString(KEY_LAYOUT, "").orEmpty()) }
            .getOrDefault(LayoutType.QWERTY)

    fun setLayoutType(context: Context, type: LayoutType) {
        prefs(context).edit().putString(KEY_LAYOUT, type.name).apply()
    }

    /** 번역 입력줄이 내놓는 언어. 마지막에 고른 것을 기억한다. */
    fun translateTarget(context: Context): TargetLanguage =
        runCatching { TargetLanguage.valueOf(prefs(context).getString(KEY_TRANSLATE_TARGET, "").orEmpty()) }
            .getOrDefault(TargetLanguage.ENGLISH)

    fun setTranslateTarget(context: Context, target: TargetLanguage) {
        prefs(context).edit().putString(KEY_TRANSLATE_TARGET, target.name).apply()
    }

    /**
     * 배경 사진 위에서 키를 얼마나 비치게 할지. 0 = 키가 꽉 차서 사진이 안 보임,
     * 100 = 키가 거의 사라지고 사진만 보임. 기본 70 은 예전에 고정값이던 30% 불투명과 같다.
     * 배경 사진이 없으면 이 값은 쓰이지 않는다.
     */
    fun keyTransparency(context: Context): Int =
        prefs(context).getInt(KEY_KEY_ALPHA, DEFAULT_KEY_TRANSPARENCY).coerceIn(0, 100)

    fun setKeyTransparency(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_KEY_ALPHA, percent.coerceIn(0, 100)).apply()
    }

    const val DEFAULT_KEY_TRANSPARENCY = 70

    // --- AI 경로 ----------------------------------------------------------------

    // 앱에는 Gemini 키가 없고, 사용자가 자기 키를 넣는 칸도 없앴다. AI 교정은 전부
    // 중계 서버를 거친다. 키·모델 이름 같은 API 내부 사정을 설정 화면에 드러내지 않는다.

    /** 빌드에 넣은 중계 서버 주소. 비어 있으면 서버 경로가 없다. */
    fun serverUrl(): String = BuildConfig.AI_SERVER_URL.trim().trimEnd('/')

    fun serverAvailable(): Boolean = serverUrl().isNotEmpty()

    /** 중계 서버의 Gemini 호환 경로. 앱은 구글 대신 여기로 같은 요청을 보낸다. */
    fun serverBase(): String = serverUrl() + "/v1beta/models"

    /** AI 교정 버튼을 띄울 수 있는가. 서버 주소가 빌드에 있으면 된다. */
    fun aiAvailable(): Boolean = serverAvailable()

    /**
     * 이 설치를 가리키는 무작위 ID.
     *
     * 서버가 무료 한도를 세는 단위다. 계정이 없어서 이것밖에 없다 — 지우고 다시 깔면
     * 새 ID 가 나오니 한도도 새로 생긴다. 그걸 막으려면 Play Integrity 나 로그인이
     * 필요한데, 무료 5 회를 그렇게까지 지킬 가치는 아직 없다. 서버 쪽 IP 한도가 한 번
     * 더 걸러 준다.
     */
    @Synchronized
    fun installId(context: Context): String {
        val existing = prefs(context).getString(KEY_INSTALL_ID, "").orEmpty()
        if (existing.isNotEmpty()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_INSTALL_ID, fresh).apply()
        return fresh
    }

    /**
     * Play 구매 토큰. 구독하면 [BillingManager] 가 넣어 준다.
     *
     * 이 값이 있다고 구독자로 보지 않는다 — 폰은 얼마든지 거짓말할 수 있다. 서버가
     * 이걸 Play 에 물어보고 판단한다. 여기서는 보낼 수 있게 들고만 있는다.
     */
    fun purchaseToken(context: Context): String =
        prefs(context).getString(KEY_PURCHASE_TOKEN, "").orEmpty()

    fun setPurchaseToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_PURCHASE_TOKEN, token).apply()
    }

    /** 중계 서버로 보낼 때 붙이는 헤더. 서버는 이걸로 누구인지, 돈을 냈는지 안다. */
    fun serverHeaders(context: Context): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["X-Install-Id"] = installId(context)
        purchaseToken(context).takeIf { it.isNotEmpty() }?.let { headers["X-Purchase-Token"] = it }
        return headers
    }

    /**
     * 서버가 마지막으로 알려 준 요금 상태.
     *
     * 표시용이다. 판단은 서버가 하고, 여기 든 숫자는 "오늘 3회 남음" 을 보여 주는 데만
     * 쓴다. 자기 키를 쓰는 사람은 서버를 안 거치니 값이 없다.
     */
    fun lastQuota(context: Context): GeminiCorrector.Quota? {
        val plan = prefs(context).getString(KEY_QUOTA_PLAN, null) ?: return null
        val remaining = prefs(context).getInt(KEY_QUOTA_REMAINING, -1).takeIf { it >= 0 }
        val limit = prefs(context).getInt(KEY_QUOTA_LIMIT, -1).takeIf { it >= 0 }
        val unit = prefs(context).getString(KEY_QUOTA_UNIT, null)
        return GeminiCorrector.Quota(remaining, limit, plan, unit)
    }

    fun rememberQuota(context: Context, quota: GeminiCorrector.Quota) {
        prefs(context).edit()
            .putString(KEY_QUOTA_PLAN, quota.plan)
            .putInt(KEY_QUOTA_REMAINING, quota.remaining ?: -1)
            .putInt(KEY_QUOTA_LIMIT, quota.limit ?: -1)
            .putString(KEY_QUOTA_UNIT, quota.unit)
            .apply()
    }

    // --- 클립보드 ---------------------------------------------------------------

    /**
     * 복사해 둔 글 목록을 담아 두는 곳.
     *
     * `StringSet` 은 순서를 안 지켜서 못 쓴다 — 최신이 맨 앞이어야 하는 목록이다.
     * 잇고 쪼개는 방식은 core 에 두고 테스트로 확인한다. 여기서 깨지면 사용자가
     * 복사해 둔 것이 조용히 날아간다.
     */
    fun clipboardStore(context: Context): ClipboardHistory.Store =
        object : ClipboardHistory.Store {
            override fun read(): List<String> =
                ClipboardHistory.decode(prefs(context).getString(KEY_CLIPBOARD, "").orEmpty())

            override fun write(items: List<String>) {
                prefs(context).edit().putString(KEY_CLIPBOARD, ClipboardHistory.encode(items)).apply()
            }
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
