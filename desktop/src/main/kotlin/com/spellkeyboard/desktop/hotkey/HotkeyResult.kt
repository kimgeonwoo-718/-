package com.spellkeyboard.desktop.hotkey

/**
 * 등록해 봤더니 어떻게 됐는가. **예외가 아니라 값이다.**
 *
 * 등록 실패는 드문 사고가 아니라 **늘 있는 결과 가운데 하나**다. 설계서가 고른
 * Ctrl+Alt+Space 는 맨 처음 켠 기계에서 이미 남이 쓰고 있었다. 실패를 예외로 던지면
 * 부르는 쪽이 try 를 빼먹는 순간 조용히 삼켜지고, 사용자에게는 "단축키를 눌러도 아무
 * 일이 없다" 로만 보인다. 그래서 돌려주고, 설정 창이 [HotkeyResult.Failed.message] 를
 * 그대로 띄우게 한다.
 */
sealed interface HotkeyResult {
    val combo: HotkeyCombo
    val ok: Boolean

    data class Ok(override val combo: HotkeyCombo) : HotkeyResult {
        override val ok: Boolean get() = true
    }

    data class Failed(
        override val combo: HotkeyCombo,
        val reason: HotkeyFailure,
        /** Win32 `GetLastError`. 우리가 스스로 막은 경우는 0 이다. */
        val errorCode: Int = 0,
        /** 우리가 지어낸 설명. 없으면 [reason] 이 만들어 준다. */
        val detail: String? = null,
    ) : HotkeyResult {
        override val ok: Boolean get() = false

        /** 사용자에게 **그대로 보여 줄** 한 마디. 로그용이 아니다. */
        val message: String get() = detail ?: reason.describe(combo, errorCode)
    }
}

/** 실패의 갈래. 숫자(1409 따위)를 UI 까지 들고 가지 않으려고 둔다. */
enum class HotkeyFailure {
    /** 우리가 막았다. 조합키 없는 키, 조합키 자체를 키로 쓴 경우 따위. */
    REJECTED,

    /** 1409. 남이 쓰고 있다 — **사용자가 가장 자주 만날 실패다.** */
    ALREADY_TAKEN,

    /** 1004. fsModifiers 에 있을 수 없는 비트. 우리 코드 잘못이지 사용자 잘못이 아니다. */
    BAD_MODIFIERS,

    /** 단축키 실이 없거나 이미 죽었다. */
    NOT_RUNNING,

    /** 실은 살아 있는데 시간 안에 대답하지 않았다. */
    TIMEOUT,

    /** MSG 구조 크기가 어긋났다 — 이대로 두면 눌러도 아무 일이 안 생긴다. */
    BROKEN_BINDING,

    /** 그 밖의 Win32 오류. */
    WINDOWS_ERROR;

    fun describe(combo: HotkeyCombo, errorCode: Int): String = when (this) {
        REJECTED -> "${combo.format()} 는 단축키로 쓸 수 없습니다."
        ALREADY_TAKEN -> "${combo.format()} 는 다른 프로그램이 이미 쓰고 있습니다. 다른 조합을 골라 주세요."
        BAD_MODIFIERS -> "${combo.format()} 의 조합키가 잘못되었습니다."
        NOT_RUNNING -> "단축키가 켜져 있지 않습니다."
        TIMEOUT -> "단축키를 바꾸는 데 시간이 너무 걸렸습니다. 잠시 뒤 다시 해 주세요."
        BROKEN_BINDING -> "이 컴퓨터에서는 전역 단축키를 쓸 수 없습니다."
        WINDOWS_ERROR -> "${combo.format()} 를 등록하지 못했습니다 (윈도우 오류 $errorCode)."
    }

    internal companion object {
        fun of(errorCode: Int): HotkeyFailure = when (errorCode) {
            Win32.ERROR_HOTKEY_ALREADY_REGISTERED -> ALREADY_TAKEN
            Win32.ERROR_INVALID_FLAGS -> BAD_MODIFIERS
            else -> WINDOWS_ERROR
        }
    }
}
