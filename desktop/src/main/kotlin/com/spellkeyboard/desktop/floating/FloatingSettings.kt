package com.spellkeyboard.desktop.floating

import java.util.prefs.Preferences

/**
 * 창 노릇에 관한 설정. 순수 논리 쪽에서는 이 얼굴만 본다 —
 * 그래야 시험이 [MemorySettings] 로 돌고 사용자의 진짜 설정을 건드리지 않는다.
 */
interface FloatingSettings {
    /**
     * 다른 창을 누르면 숨을 것인가.
     *
     * **취향이 갈린다.** 숨는 쪽은 임시 도구답고 화면이 깨끗하다. 안 숨는 쪽은 옆에
     * 띄워 두고 원문을 보며 고칠 수 있다. 어느 한쪽을 강요하면 반은 화를 낸다.
     * **실제로 도는 기본값은 꺼짐이다.** 앱이 시작할 때 `SettingsStore.hideOnFocusLoss`
     * (기본 거짓)가 이 값을 덮어쓰기 때문이다 — 여기 적힌 `true` 는 저장소를 안 거치고
     * 이 클래스만 따로 쓸 때의 값이다.
     *
     * 꺼 둔 까닭: 글을 쓰다가 사전이나 다른 창을 잠깐 보는 일이 잦은데, 그때마다 쓰던
     * 글이 든 창이 사라지면 놀란다. 설정에서 켤 수 있다.
     */
    var hideOnFocusLoss: Boolean

    var policy: PlacementPolicy

    /** 사용자가 마지막으로 창을 둔 자리. 없으면 아직 한 번도 안 옮긴 것이다. */
    var remembered: Rect?
}

/** 시험용. 그리고 설정을 못 읽을 때의 대피처. */
class MemorySettings(
    override var hideOnFocusLoss: Boolean = true,
    override var policy: PlacementPolicy = PlacementPolicy.FOLLOW_POINTER,
    override var remembered: Rect? = null,
) : FloatingSettings

/**
 * [Preferences] 에 적어 두는 진짜 설정.
 *
 * 쓰기가 실패해도 **절대 던지지 않는다.** 레지스트리가 잠겨 있다고 해서 창이 안 뜨면
 * 안 된다. 자리를 못 적어 두는 것은 다음에 가운데에 뜨는 것으로 끝나는 일이다.
 */
class PreferenceSettings(private val prefs: Preferences) : FloatingSettings {

    override var hideOnFocusLoss: Boolean
        get() = runCatching { prefs.getBoolean(KEY_HIDE_ON_BLUR, true) }.getOrDefault(true)
        set(value) { runCatching { prefs.putBoolean(KEY_HIDE_ON_BLUR, value) } }

    override var policy: PlacementPolicy
        get() = PlacementPolicy.decode(runCatching { prefs.get(KEY_POLICY, null) }.getOrNull())
        set(value) { runCatching { prefs.put(KEY_POLICY, value.name) } }

    override var remembered: Rect?
        get() = Rect.decode(runCatching { prefs.get(KEY_BOUNDS, null) }.getOrNull())
        set(value) {
            runCatching {
                if (value == null) prefs.remove(KEY_BOUNDS) else prefs.put(KEY_BOUNDS, value.encode())
            }
        }

    private companion object {
        const val KEY_HIDE_ON_BLUR = "float.hideOnFocusLoss"
        const val KEY_POLICY = "float.placement"
        const val KEY_BOUNDS = "float.bounds"
    }
}
