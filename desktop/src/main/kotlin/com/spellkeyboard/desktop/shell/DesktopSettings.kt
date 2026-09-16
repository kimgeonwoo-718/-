package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.Win32Keys

import java.util.prefs.Preferences

/**
 * 저장되는 설정 전부. 값만 들었고 화면도 JNA 도 모른다 — 그래야 시험할 수 있다.
 *
 * @param hotkey 창을 부르는 전역 조합.
 * @param pasteExtraDelayMs 돌려 붙이기에서 앞 창이 돌아온 것을 확인한 **뒤에** 더 주는 여유.
 * @param hideOnFocusLoss 다른 창을 누르면 저절로 숨을지.
 * @param liveCorrection 치는 대로 고칠지.
 */
data class DesktopSettings(
    val hotkey: HotkeyCombo = HotkeyCombo.DEFAULT,
    val pasteExtraDelayMs: Int = DEFAULT_PASTE_DELAY_MS,
    val hideOnFocusLoss: Boolean = false,
    val liveCorrection: Boolean = true,
) {
    companion object {
        /**
         * ## 이 값이 왜 50 인가 — 그리고 왜 0 이 아닌가
         *
         * 이 기계에서 재 봤다: 창을 숨기면 앞 창이 3.8~8.3ms 만에 돌아온다. 그러니
         * **제대로 만든 붙여넣기는 고정 대기가 아니라 GetForegroundWindow 를 지켜본다.**
         * 그 방식이면 이 값은 0 이어도 100/100 성공한다.
         *
         * 그런데도 0 이 아닌 까닭은 두 가지다. 하나, 이 값을 **유일한** 대기로 쓰는
         * 구현과 엮여도 안전해야 한다(설계서 §3-4 는 50~100ms 를 적어 두었다). 둘,
         * 50ms 는 사람이 못 느끼는 시간이라 잃는 것이 없다.
         *
         * 늦어서 생기는 손해는 "조금 느리다" 뿐이다. 일러서 생기는 손해는 자판이
         * 허공으로 날아가는 것이다. 그래서 기본값은 넉넉한 쪽으로 둔다.
         */
        const val DEFAULT_PASTE_DELAY_MS = 50

        /** 0 은 "지켜보기에 전부 맡긴다" 는 뜻으로 일부러 허용한다. */
        const val MIN_PASTE_DELAY_MS = 0

        /** 이보다 길면 사용자는 프로그램이 멎은 줄 안다. */
        const val MAX_PASTE_DELAY_MS = 500
    }
}

/** 설정이 바뀔 때마다 불린다. **EDT 에서** 불린다 — 화면을 바로 고쳐도 된다. */
fun interface SettingsListener {
    fun onSettings(settings: DesktopSettings)
}

/**
 * [Preferences] 위에 얹은 얇은 저장소.
 *
 * ## 마디를 왜 손으로 적는가
 *
 * 기존 창([com.spellkeyboard.desktop.CorrectorWindow])이 `실시간 교정` 을
 * `Preferences.userNodeForPackage(CorrectorWindow::class.java)` 에, 즉
 * `/com/spellkeyboard/desktop` 마디에 이미 적어 왔다. 이 클래스는
 * `...desktop.shell` 꾸러미에 있어서 `userNodeForPackage` 를 쓰면 마디가
 * `/com/spellkeyboard/desktop/shell` 로 갈라지고, **사용자가 껐던 실시간 교정이
 * 새 설치처럼 다시 켜진다.** 그래서 경로를 못 박는다.
 *
 * ## 실은 왜 하나로 묶었나
 *
 * 실시간 교정 스위치가 세 군데 있다 — 창 위쪽 네모칸, 알림 영역 차림표, 설정 창.
 * 셋이 각자 [Preferences] 를 읽고 쓰면 반드시 어긋난다. 전부 여기를 거치고,
 * 바뀌면 [addListener] 로 알린다.
 */
class SettingsStore(private val prefs: Preferences = defaultNode()) {

    private val listeners = mutableListOf<SettingsListener>()

    @Volatile
    private var current: DesktopSettings = read()

    /** 지금 값. 읽기는 [Preferences] 를 다시 안 판다 — 자주 불리는 자리다. */
    fun get(): DesktopSettings = current

    /**
     * 듣는 이를 붙인다. 붙이는 즉시 지금 값으로 한 번 불러 준다 —
     * 화면을 처음 그리는 코드를 따로 안 써도 되고, 그래서 어긋날 자리가 하나 준다.
     */
    fun addListener(listener: SettingsListener) {
        listeners += listener
        // [update] 와 같이 삼킨다. 붙이자마자 던지는 듣는 이 하나 때문에 **붙이던
        // 쪽이** 죽으면 안 된다 — 알림 영역을 붙이는 도중이면 아이콘만 남고 차림표가
        // 없는 상태가 된다.
        runCatching { listener.onSettings(current) }
    }

    fun removeListener(listener: SettingsListener) {
        listeners.remove(listener)
    }

    /**
     * 값을 바꾼다. 안 바뀌었으면 **아무것도 안 한다** — 알림 영역의 네모칸을
     * 사용자가 누르지도 않았는데 다시 그리면 깜빡인다.
     *
     * EDT 에서 불러라. 듣는 이들이 곧장 화면을 만진다.
     */
    fun update(next: DesktopSettings) {
        val clean = clamp(next)
        if (clean == current) return
        current = clean
        write(clean)
        // 듣는 중에 목록을 고치는 일이 있어서(설정 창이 닫히며 제 듣는 이를 떼는 등)
        // 베껴 놓고 돈다. 안 그러면 ConcurrentModificationException 이 난다.
        for (l in listeners.toList()) runCatching { l.onSettings(clean) }
    }

    fun setLiveCorrection(on: Boolean) = update(current.copy(liveCorrection = on))

    fun setHotkey(combo: HotkeyCombo) = update(current.copy(hotkey = combo))

    // ---- 읽고 쓰기 ----

    private fun read(): DesktopSettings = clamp(
        DesktopSettings(
            // 깨진 값이 들어 있어도 프로그램은 떠야 한다. 못 읽으면 기본값.
            hotkey = HotkeyCombo.parse(prefs.get(KEY_HOTKEY, null)) ?: HotkeyCombo.DEFAULT,
            pasteExtraDelayMs = prefs.getInt(KEY_PASTE_DELAY, DesktopSettings.DEFAULT_PASTE_DELAY_MS),
            hideOnFocusLoss = prefs.getBoolean(KEY_HIDE_ON_BLUR, false),
            liveCorrection = prefs.getBoolean(KEY_LIVE, true),
        ),
    )

    private fun write(s: DesktopSettings) {
        prefs.put(KEY_HOTKEY, s.hotkey.store())
        prefs.putInt(KEY_PASTE_DELAY, s.pasteExtraDelayMs)
        prefs.putBoolean(KEY_HIDE_ON_BLUR, s.hideOnFocusLoss)
        prefs.putBoolean(KEY_LIVE, s.liveCorrection)
        // flush 를 안 부르면 JVM 이 죽을 때 몰아 쓰는데, 강제 종료되면 그대로 날아간다.
        // 설정은 자주 안 바뀌니 매번 밀어 두는 값이 싸다. 던지면 삼킨다 — 설정을
        // 못 적었다고 프로그램이 죽으면 안 된다.
        runCatching { prefs.flush() }
    }

    private fun clamp(s: DesktopSettings): DesktopSettings {
        val delay = s.pasteExtraDelayMs
            .coerceIn(DesktopSettings.MIN_PASTE_DELAY_MS, DesktopSettings.MAX_PASTE_DELAY_MS)
        val hotkey = if (s.hotkey.rejectReason() == null) s.hotkey else HotkeyCombo.DEFAULT
        return if (delay == s.pasteExtraDelayMs && hotkey == s.hotkey) s
        else s.copy(pasteExtraDelayMs = delay, hotkey = hotkey)
    }

    companion object {
        const val KEY_HOTKEY = "hotkey"
        const val KEY_PASTE_DELAY = "pasteDelayMs"
        const val KEY_HIDE_ON_BLUR = "hideOnFocusLoss"

        /** 기존 창이 쓰던 이름 그대로여야 한다. 바꾸면 사용자가 껐던 것이 도로 켜진다. */
        const val KEY_LIVE = "liveCorrection"

        /** `userNodeForPackage(CorrectorWindow::class.java)` 와 같은 자리다. */
        fun defaultNode(): Preferences = Preferences.userRoot().node("/com/spellkeyboard/desktop")
    }
}
