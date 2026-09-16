package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.hotkey.HotkeyCombo
import com.spellkeyboard.desktop.hotkey.Win32Keys

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 조합 하나가 지금 쓸 수 있는가. */
sealed interface HotkeyAvailability {
    /** 등록할 수 있다. */
    data object Free : HotkeyAvailability

    /** 우리가 이미 쓰고 있다. 사용자에겐 "지금 쓰는 조합" 이라고 말하면 된다. */
    data object Ours : HotkeyAvailability

    /** 못 쓴다. [reason] 을 그대로 보여 주고, **고르지 못하게 막아라.** */
    data class Taken(val reason: String, val errorCode: Int = 0) : HotkeyAvailability

    /**
     * 확인을 못 했다(JNA 가 없다, 윈도우가 아니다, 물어보다 시간이 지났다).
     *
     * 이때는 **막지 않는다.** 확인에 실패한 것은 우리 사정이지 사용자 잘못이
     * 아니고, 실제로는 멀쩡히 등록될 수도 있다. 대신 그렇다고 말은 해 준다.
     */
    data class Unknown(val why: String) : HotkeyAvailability
}

/** 이 조합을 저장하게 둬도 되는가. */
fun HotkeyAvailability.usable(): Boolean = this !is HotkeyAvailability.Taken

/**
 * 조합을 실제로 잡아 볼 수 있는지 물어본다.
 *
 * ## 왜 갈라 놓았나
 *
 * 전역 단축키를 굴리는 것은 다른 부품의 일이다. 설정 창은 "이 조합이 되냐" 만
 * 알면 되고, 그 답은 **실제로 Windows 에 물어봐야만** 나온다 — 어떤 조합이 이미
 * 남의 것인지 알 길이 다른 데 없다. 그래서 물음만 창구로 뽑았다.
 *
 * 엮는 쪽은 단축키 부품에 대고 구현해 주면 된다. 아무것도 없으면
 * [Win32HotkeyProbe] 가 혼자서도 답한다.
 *
 * **EDT 에서 부르지 마라.** 네이티브 호출이고, 느려질 자리가 있다.
 */
fun interface HotkeyProbe {
    fun check(combo: HotkeyCombo): HotkeyAvailability
}

/**
 * 진짜로 잡아 봤다가 곧바로 놓는다.
 *
 * ## 왜 [currentlyOwned] 를 따로 받는가
 *
 * 재 봤다: **이미 자기가 잡고 있는 조합을 다시 잡아도 1409 가 난다.** 같은 실,
 * 같은 id 여도 그렇다. 그러니 오류 번호만으로는 "내가 쓰는 중" 과 "남이 쓰는 중"
 * 을 구별할 수 없다. 앱이 제 등록을 스스로 알고 있어야 한다 — 그것이 이 인자다.
 * 이게 없으면 설정 창이 **지금 쓰는 단축키를 두고 "이미 쓰는 중입니다" 라고
 * 거짓말한다.**
 *
 * ## 왜 실을 하나 따로 두는가
 *
 * `RegisterHotKey(NULL, ...)` 은 **부른 실에 묶인다.** 놓는 것도 같은 실이라야 한다.
 * 실 하나를 두고 거기서만 잡았다 놓으면 그 규칙이 저절로 지켜진다. 그리고 그 실이
 * EDT 가 아니므로 설정 창이 멈추지 않는다.
 */
class Win32HotkeyProbe(
    private val currentlyOwned: () -> HotkeyCombo?,
) : HotkeyProbe, AutoCloseable {

    // 이름 있는 데몬 실 하나. 데몬이라야 이것 때문에 JVM 이 안 끝나는 일이 없다.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hotkey-probe").apply { isDaemon = true }
    }

    override fun check(combo: HotkeyCombo): HotkeyAvailability {
        combo.rejectReason()?.let { return HotkeyAvailability.Taken(it) }
        if (combo == currentlyOwned()) return HotkeyAvailability.Ours

        return try {
            worker.submit<HotkeyAvailability> { tryTake(combo) }.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // JNA 가 없거나 윈도우가 아니면 여기로 온다. 모르겠으면 막지 않는다 —
            // 확인을 못 했다고 사용자가 고르지도 못하게 하는 것은 과하다.
            HotkeyAvailability.Unknown("확인하지 못했습니다 (${e.javaClass.simpleName}).")
        }
    }

    private fun tryTake(combo: HotkeyCombo): HotkeyAvailability {
        // MOD_NOREPEAT 을 얹어서 본 등록과 똑같은 깃발로 물어본다. 1004 는 깃발이
        // 잘못됐을 때만 나오므로, 여기서 통과하면 본 등록도 깃발로는 안 막힌다.
        val ok = U.RegisterHotKey(null, PROBE_ID, combo.mods or MOD_NOREPEAT, combo.vk)
        if (!ok) {
            // 실패한 호출 **직후에**, 같은 실에서 읽어야 한다.
            val err = Native.getLastError()
            return HotkeyAvailability.Taken(explain(err), err)
        }
        // 잡았으면 반드시 놓는다. 안 놓으면 그 조합이 온 컴퓨터에서 막힌다.
        U.UnregisterHotKey(null, PROBE_ID)
        return HotkeyAvailability.Free
    }

    override fun close() {
        worker.shutdownNow()
    }

    private fun explain(err: Int): String = when (err) {
        ERROR_HOTKEY_ALREADY_REGISTERED -> "다른 프로그램이 이미 쓰고 있습니다."
        ERROR_INVALID_FLAGS -> "쓸 수 없는 조합키입니다."
        else -> "등록할 수 없습니다 (오류 $err)."
    }

    private interface U32 : StdCallLibrary {
        fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    }

    private companion object {
        /**
         * 본 단축키가 쓰는 id 와 **겹치면 안 된다.** 겹치면 물어보는 것만으로
         * 본 등록이 풀린다. 아무도 안 쓸 만한 값을 고른다.
         */
        const val PROBE_ID = 0x5A11

        const val MOD_NOREPEAT = 0x4000
        const val ERROR_INVALID_FLAGS = 1004
        const val ERROR_HOTKEY_ALREADY_REGISTERED = 1409

        val U: U32 by lazy { Native.load("user32", U32::class.java) }
    }
}
