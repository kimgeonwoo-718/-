package com.spellkeyboard.desktop.floating

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/** 창이 키를 받고 하려는 일. [FloatingController] 가 이것만 보고 움직인다. */
enum class FloatIntent {
    /** Enter. 고친 글을 내보내고 창을 숨긴다. */
    APPLY_AND_HIDE,

    /** Shift+Enter. 줄만 바꾼다. */
    NEWLINE,

    /** Esc. 아무것도 내보내지 않고 숨긴다. */
    CANCEL,

    /** Ctrl+Space. 전체교정. */
    CORRECT_ALL,

    /** 우리 일이 아니다. Swing 이 원래 하던 대로 하게 둔다. */
    PASS,
}

/**
 * 설계서 3-1 의 자판 배치. **순수 함수라 화면 없이 시험된다.**
 *
 * `KeyEvent` 는 상수만 쓴다 — `java.awt.event` 의 상수 읽기는 headless 에서도 된다.
 */
object FloatKeymap {

    /**
     * @param modifiersEx `KeyEvent.getModifiersEx()`. 옛 `getModifiers()` 를 쓰면
     *   Enter 가 Ctrl+Enter 와 구분이 안 된다.
     * @param composing 한글 IME 가 **조합 중**인가. 왜 필요한지는 아래를 보라.
     */
    fun intentFor(keyCode: Int, modifiersEx: Int, composing: Boolean = false): FloatIntent {
        // 조합 중에는 Esc 말고 전부 흘린다.
        //
        // "한글" 을 치는 중에 Enter 를 누르는 것은 **보내라는 뜻이 아니라 조합을 끝내라는
        // 뜻이다.** 카카오톡이든 슬랙이든 한국어 입력에서는 첫 Enter 가 조합을 확정하고
        // 두 번째 Enter 가 보낸다. 여기서 바로 숨겨 버리면 마지막 글자가 통째로 날아간다.
        // 전체교정도 마찬가지다 — 조합 중에 글을 갈아 끼우면 IME 가 쓰던 자리가 어긋나
        // 유령 글자가 남는다(LiveCorrector 가 조합 중에 아예 손을 떼는 것과 같은 까닭).
        //
        // Esc 만 살려 두는 것은, 조합 중에 Esc 가 우리한테까지 왔다면 IME 가 그 키를
        // 원하지 않았다는 뜻이고, 그때 사용자가 바라는 것은 "이 창 치워" 하나뿐이기 때문이다.
        if (composing && keyCode != KeyEvent.VK_ESCAPE) return FloatIntent.PASS

        val ctrl = modifiersEx and InputEvent.CTRL_DOWN_MASK != 0
        val shift = modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0
        val alt = modifiersEx and InputEvent.ALT_DOWN_MASK != 0
        val meta = modifiersEx and InputEvent.META_DOWN_MASK != 0

        return when (keyCode) {
            KeyEvent.VK_ESCAPE ->
                // Esc 는 조합키가 뭐가 붙었든 숨긴다. 급해서 누르는 키라 Shift 가 얹혀
                // 있다고 안 듣는 것은 성의 없는 짓이다.
                FloatIntent.CANCEL

            KeyEvent.VK_ENTER ->
                when {
                    shift && !ctrl && !alt && !meta -> FloatIntent.NEWLINE
                    !shift && !ctrl && !alt && !meta -> FloatIntent.APPLY_AND_HIDE
                    // Ctrl+Enter, Alt+Enter 따위는 설계서에 없다. 멋대로 정해서 붙이면
                    // 나중에 진짜 뜻이 정해질 때 사용자 손가락을 두 번 고치게 만든다.
                    else -> FloatIntent.PASS
                }

            KeyEvent.VK_SPACE ->
                // Ctrl+Space 만. Ctrl+Shift+Space 는 창을 부르는 전역 단축키라 여기까지
                // 오지도 않지만, 와도 전체교정으로 먹으면 안 된다.
                if (ctrl && !shift && !alt && !meta) FloatIntent.CORRECT_ALL else FloatIntent.PASS

            else -> FloatIntent.PASS
        }
    }
}
