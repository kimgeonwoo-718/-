package com.spellkeyboard.desktop.hotkey

import java.awt.event.KeyEvent

/**
 * AWT 가상 키 코드 ↔ Win32 가상 키 코드.
 *
 * ## 왜 표가 필요한가 — 그냥 넘기면 조용히 엉뚱한 키가 잡힌다
 *
 * 글자와 숫자는 두 쪽이 같은 값이라 그냥 넘겨도 된다. 그래서 속는다. 어긋나는 것들이
 * 하필 **다른 키의 코드와 정확히 겹친다**:
 *
 *     AWT VK_OPEN_BRACKET  0x5B  ==  Win32 VK_LWIN      → `[` 를 골랐는데 왼쪽 윈도우 키가 잡힌다
 *     AWT VK_BACK_SLASH    0x5C  ==  Win32 VK_RWIN      → `\` 를 골랐는데 오른쪽 윈도우 키
 *     AWT VK_PERIOD        0x2E  ==  Win32 VK_DELETE    → `.` 를 골랐는데 Delete
 *     AWT VK_MINUS         0x2D  ==  Win32 VK_INSERT    → `-` 를 골랐는데 Insert
 *     AWT VK_ENTER         0x0A  !=  Win32 VK_RETURN 0x0D
 *     AWT VK_DELETE        0x7F  !=  Win32 VK_DELETE 0x2E
 *
 * 등록은 성공하고, 화면에는 `Ctrl+Alt+[` 라고 적히고, 실제로 반응하는 것은 Ctrl+Alt+왼쪽윈도우다.
 * 오류도 안 난다. 그래서 표를 둔다.
 *
 * ## 이 표와 [HotkeyCombo] 의 표가 따로인 까닭
 *
 * 여기는 **설정 창이 자판에서 갈무리할 수 있는 키**의 표다(AWT 가 이름을 아는 것만).
 * [HotkeyCombo] 쪽 표는 **글자로 적혀 저장될 수 있는 키**의 표라 F13~F24 처럼 여기 없는
 * 것까지 담는다. 설정 파일을 손으로 고친 사람의 값을 잃지 않으려는 것이다.
 */
object Win32Keys {

    const val VK_SPACE = 0x20
    const val VK_ESCAPE = 0x1B

    /** (AWT 코드, Win32 코드, 저장 이름, 화면 이름) */
    private data class Key(val awt: Int, val vk: Int, val store: String, val shown: String)

    private val keys: List<Key> = buildList {
        // 글자·숫자는 두 쪽이 같다.
        for (c in 'A'..'Z') add(Key(c.code, c.code, c.toString(), c.toString()))
        for (c in '0'..'9') add(Key(c.code, c.code, c.toString(), c.toString()))
        // F1~F12 도 같다. F13 위로는 갈라지므로 손대지 않는다.
        for (i in 1..12) add(Key(0x70 + i - 1, 0x70 + i - 1, "F$i", "F$i"))
        // 숫자판도 같다.
        for (i in 0..9) add(Key(0x60 + i, 0x60 + i, "NUMPAD$i", "숫자판 $i"))

        add(Key(KeyEvent.VK_SPACE, 0x20, "SPACE", "Space"))
        add(Key(KeyEvent.VK_ENTER, 0x0D, "ENTER", "Enter"))          // 0x0A → 0x0D
        add(Key(KeyEvent.VK_TAB, 0x09, "TAB", "Tab"))
        add(Key(KeyEvent.VK_BACK_SPACE, 0x08, "BACKSPACE", "Backspace"))
        add(Key(KeyEvent.VK_ESCAPE, 0x1B, "ESC", "Esc"))
        add(Key(KeyEvent.VK_DELETE, 0x2E, "DELETE", "Delete"))        // 0x7F → 0x2E
        add(Key(KeyEvent.VK_INSERT, 0x2D, "INSERT", "Insert"))        // 0x9B → 0x2D
        add(Key(KeyEvent.VK_HOME, 0x24, "HOME", "Home"))
        add(Key(KeyEvent.VK_END, 0x23, "END", "End"))
        add(Key(KeyEvent.VK_PAGE_UP, 0x21, "PAGEUP", "PageUp"))
        add(Key(KeyEvent.VK_PAGE_DOWN, 0x22, "PAGEDOWN", "PageDown"))
        add(Key(KeyEvent.VK_LEFT, 0x25, "LEFT", "←"))
        add(Key(KeyEvent.VK_UP, 0x26, "UP", "↑"))
        add(Key(KeyEvent.VK_RIGHT, 0x27, "RIGHT", "→"))
        add(Key(KeyEvent.VK_DOWN, 0x28, "DOWN", "↓"))

        // 여기부터가 위험한 것들. 전부 OEM_* 로 옮겨야 한다.
        add(Key(KeyEvent.VK_BACK_QUOTE, 0xC0, "BACKQUOTE", "`"))       // 우연히 같다
        add(Key(KeyEvent.VK_MINUS, 0xBD, "MINUS", "-"))
        add(Key(KeyEvent.VK_EQUALS, 0xBB, "EQUALS", "="))
        add(Key(KeyEvent.VK_OPEN_BRACKET, 0xDB, "LBRACKET", "["))
        add(Key(KeyEvent.VK_CLOSE_BRACKET, 0xDD, "RBRACKET", "]"))
        add(Key(KeyEvent.VK_BACK_SLASH, 0xDC, "BACKSLASH", "\\"))
        add(Key(KeyEvent.VK_SEMICOLON, 0xBA, "SEMICOLON", ";"))
        add(Key(KeyEvent.VK_QUOTE, 0xDE, "QUOTE", "'"))                // 우연히 같다
        add(Key(KeyEvent.VK_COMMA, 0xBC, "COMMA", ","))
        add(Key(KeyEvent.VK_PERIOD, 0xBE, "PERIOD", "."))
        add(Key(KeyEvent.VK_SLASH, 0xBF, "SLASH", "/"))
    }

    private val byAwt: Map<Int, Key> = keys.associateBy { it.awt }
    private val byVk: Map<Int, Key> = keys.associateBy { it.vk }
    private val byStore: Map<String, Key> = keys.associateBy { it.store }

    /** 우리가 다루지 않는 키(조합키 자체, F13 위, 한/영 키 등)는 null 이다. */
    fun fromAwt(awtKeyCode: Int): Int? = byAwt[awtKeyCode]?.vk

    fun toAwt(vk: Int): Int? = byVk[vk]?.awt

    fun storeName(vk: Int): String? = byVk[vk]?.store

    fun displayName(vk: Int): String? = byVk[vk]?.shown

    fun byStoreName(name: String): Int? = byStore[name]?.vk
}
