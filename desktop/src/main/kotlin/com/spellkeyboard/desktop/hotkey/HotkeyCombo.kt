package com.spellkeyboard.desktop.hotkey

/**
 * 전역 단축키 한 조합 — 조합키 묶음([modifiers])과 **Win32 가상키** [keyCode].
 *
 * ## 왜 데이터로 들고 있는가
 *
 * 설정에서 단축키를 바꾸면 **앱을 다시 켜지 않고** 갈아 끼울 수 있어야 한다. 그러려면
 * 조합이 코드에 박힌 상수가 아니라 주고받을 수 있는 값이어야 한다. 그래서 이 클래스는
 * 순수한 값이고, 등록하는 일은 [GlobalHotkeyManager] 가 한다. 창도 JNA 도 모른다 —
 * 그래서 화면 없이 시험할 수 있다.
 *
 * ## 왜 AWT 키코드가 아니라 Win32 가상키인가
 *
 * `RegisterHotKey` 는 가상키를 **하나도 검사하지 않는다.** vk=0 도, vk=-1 도, vk=0x10000
 * 도 전부 TRUE 를 돌려준다(이 기계에서 재 봤다). 그러니 AWT 키코드를 그대로 넘기면
 * 등록은 멀쩡히 성공하고 그 단축키는 **평생 안 눌린다.** 오류도 로그도 안 남아 잡을 길이
 * 없다. 그래서 표를 코드로 박아 두고([keyName]/[keyCode]) 시험으로 지킨다.
 *
 * 실제로 어긋나는 것들:
 *
 *     Enter   AWT 0x0A ≠ Win32 0x0D      Delete  AWT 0x7F ≠ Win32 0x2E
 *     Insert  AWT 0x9B ≠ Win32 0x2D      ;       AWT 0x3B ≠ Win32 0xBA
 *     [       AWT 0x5B ≠ Win32 0xDB      -       AWT 0x2D ≠ Win32 0xBD
 *
 * 하필 AWT 의 `[`(0x5B)가 Win32 에서는 **왼쪽 윈도우키**이고 AWT 의 `-`(0x2D)는
 * **Insert** 다. 옮겨 적지 않고 넘기면 엉뚱한 키를 온 컴퓨터에서 가로챈다.
 *
 * ## MOD_NOREPEAT 를 여기 넣지 않는 까닭
 *
 * 그것은 등록하는 쪽이 붙일 깃발이고, 무엇보다 WM_HOTKEY 가 돌려주는 lParam 에서는
 * **벗겨져서** 온다(0x4006 으로 등록해도 0x6 으로 온다). 저장값에 섞어 두면 돌아온 값과
 * 비교가 영영 안 맞는다.
 */
data class HotkeyCombo(val modifiers: Int, val keyCode: Int) {

    /**
     * 설정 쪽이 쓰는 이름. 같은 값이다.
     *
     * 부품 넷이 따로 만들어지면서 한쪽은 `modifiers/keyCode`, 한쪽은 `mods/vk` 라고 불렀다.
     * 형을 둘로 두면 같은 뜻의 값이 두 벌 돌아다니다 한쪽만 고쳐지는 날이 온다. 그래서
     * 클래스는 하나로 두고 이름만 둘 다 살렸다.
     */
    val mods: Int get() = modifiers
    val vk: Int get() = keyCode

    /**
     * 사람이 읽는 꼴이자 [java.util.prefs.Preferences] 에 적는 꼴. `"Ctrl+Shift+Space"`.
     *
     * `KeyEvent.getKeyText` 를 쓰지 않는다. 그것은 지역화되어 한국어 윈도우에서는
     * "스페이스" 처럼 나오고, 그 글자가 설정 파일에 들어가면 영어 윈도우에서 도로
     * 못 읽는다. 설정값은 기계가 다시 읽을 것이므로 **고정된 영문 이름**이어야 한다.
     */
    fun format(): String = buildString {
        // 차례는 Ctrl, Shift, Alt, Win 으로 못 박는다. 같은 조합이 늘 같은 글자가 되어야
        // 설정 파일이 왔다 갔다 하지 않고, 시험도 한 가지 답만 보면 된다.
        if (modifiers and MOD_CONTROL != 0) append("Ctrl+")
        if (modifiers and MOD_SHIFT != 0) append("Shift+")
        if (modifiers and MOD_ALT != 0) append("Alt+")
        if (modifiers and MOD_WIN != 0) append("Win+")
        append(keyName(keyCode))
    }

    override fun toString(): String = format()

    /**
     * 저장용 대문자 꼴. `"CTRL+SHIFT+SPACE"`.
     *
     * [format] 을 대문자로 올린 것뿐이다. [parse] 가 대소문자를 안 가리므로 둘 중 무엇으로
     * 적혀 있어도 읽힌다 — 예전 설정이 어느 쪽으로 적혀 있든 사용자 값이 안 날아간다.
     */
    fun store(): String = format().uppercase()

    /** 화면용. [format] 과 같다 — 설정 창에 뜬 글자와 설정 파일에 적힌 글자가 같아야 한다. */
    fun display(): String = format()

    /**
     * 이 조합을 정말 등록해도 되는가. 안 되면 **사용자에게 그대로 보여 줄** 까닭을 돌려준다.
     *
     * 윈도우는 이런 것을 하나도 막아 주지 않는다. Ctrl 없이 Space 하나만 등록해도 TRUE 를
     * 주고, 그 순간부터 **온 컴퓨터에서 스페이스바가 우리 것**이 된다. 사용자가 글을 못
     * 친다. 그래서 여기서 막는다.
     */
    fun rejectReason(): String? = when {
        keyCode <= 0 || keyCode > 0xFF -> "쓸 수 없는 키입니다."
        keyCode in MODIFIER_KEYS -> "조합키만으로는 단축키가 안 됩니다."
        // 이름조차 없는 가상키는 막는다. 윈도우는 vk=0xFF 도 받아 주고, 그렇게 잡힌
        // 단축키는 화면에 "Ctrl+0xFF" 라고 뜬 채 영영 안 눌린다.
        !isNamedKey(keyCode) -> "쓸 수 없는 키입니다."
        // Shift 만으로는 안 된다. Shift+A 를 잡으면 대문자 A 를 영영 못 친다.
        modifiers and (MOD_CONTROL or MOD_ALT or MOD_WIN) == 0 ->
            "Ctrl, Alt, Win 가운데 하나는 있어야 합니다."
        modifiers and MODIFIER_MASK.inv() != 0 -> "모르는 조합키가 섞였습니다."
        else -> null
    }

    /** 등록해도 되는 조합인가. [rejectReason] 이 null 이면 참이다. */
    val isUsable: Boolean get() = rejectReason() == null

    companion object {
        // Win32 fsModifiers. 숫자가 그대로 RegisterHotKey 로 간다.
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_WIN = 0x0008
        const val MODIFIER_MASK = MOD_ALT or MOD_CONTROL or MOD_SHIFT or MOD_WIN

        /**
         * 기본값은 **Ctrl+Shift+Space**.
         *
         * 설계서가 고른 Ctrl+Alt+Space 는 첫 기계에서 이미 남이 쓰고 있었다(1409). Alt+Space
         * 는 창 메뉴, Win+Space 는 자판 전환이라 셋 다 못 쓴다. 사용자가 직접 고른 값이다.
         */
        val DEFAULT = HotkeyCombo(MOD_CONTROL or MOD_SHIFT, 0x20)

        /** 조합키 자체는 단축키의 '키' 자리에 올 수 없다. Win32 값이다. */
        private val MODIFIER_KEYS = setOf(
            0x10, 0x11, 0x12,             // Shift, Control, Alt(Menu)
            0xA0, 0xA1, 0xA2, 0xA3,       // L/R Shift, L/R Control
            0xA4, 0xA5,                   // L/R Alt
            0x5B, 0x5C,                   // L/R Win
        )

        /**
         * 사람이 쓴 글을 조합으로 되읽는다. 못 읽으면 **null** — 예외가 아니다.
         *
         * 설정 파일은 사용자가 손으로 고칠 수 있고, 예전 판이 적어 둔 값이 남아 있을 수도
         * 있다. 읽다 터지면 앱이 안 뜬다. 못 읽으면 부른 쪽이 기본값으로 돌아가면 된다
         * ([parseOrDefault]).
         *
         * 받아들이는 꼴:
         *  - `"Ctrl+Shift+Space"` — [format] 이 뱉는 표준 꼴
         *  - `"ctrl + shift + space"`, `"CONTROL+SHIFT+SPACE"` — 대소문자와 빈칸은 무시
         *  - `"Ctrl+Alt+0x4B"` — 이름 없는 가상키는 16진수로. [format] 도 이렇게 뱉는다
         *  - `"6:32"` — 숫자 꼴(`mods:vk`). 설정 쪽이 숫자로 적어 둔 것도 읽어 준다
         */
        fun parse(text: String?): HotkeyCombo? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return null

            // 숫자 꼴 먼저. 사람 눈에는 안 보이지만 예전 설정이 이렇게 적혀 있을 수 있다.
            if (':' in raw) {
                val (m, v) = raw.split(':', limit = 2)
                val mods = m.trim().toIntOrNull() ?: return null
                val vk = v.trim().toIntOrNull() ?: return null
                if (mods and MODIFIER_MASK.inv() != 0) return null
                if (vk <= 0 || vk > 0xFF) return null
                return HotkeyCombo(mods, vk)
            }

            val tokens = splitTokens(raw) ?: return null
            if (tokens.isEmpty()) return null

            var mods = 0
            // **마지막 토막이 키**, 앞은 모두 조합키. 자리로 가리므로 "Win+LWin" 처럼
            // 비슷한 이름이 나란히 와도 헷갈릴 일이 없다.
            for (i in 0 until tokens.size - 1) {
                val bit = modifierBit(tokens[i]) ?: return null
                if (mods and bit != 0) return null   // "Ctrl+Ctrl+A" 는 사람 실수다. 조용히 넘기지 않는다.
                mods = mods or bit
            }
            val vk = keyCode(tokens.last()) ?: return null
            return HotkeyCombo(mods, vk)
        }

        /** 못 읽으면 [DEFAULT]. 설정을 읽는 자리에서 쓴다. */
        fun parseOrDefault(text: String?): HotkeyCombo = parse(text) ?: DEFAULT

        /** 우리가 이름을 아는 키인가. 모르면 등록해 봐야 안 눌리는 키라 막는다. */
        private fun isNamedKey(vk: Int): Boolean =
            vk in VK_TO_NAME || vk in SYMBOL_NAMES || vk in 0x30..0x39 || vk in 0x41..0x5A

        /**
         * 누른 키 하나를 조합으로 바꾼다. 설정 창의 갈무리 칸이 부르는 자리.
         *
         * 조합키만 눌렀을 때(Ctrl 만, Shift 만…)는 null 이다 — 사용자가 Ctrl 을 누르고
         * 아직 본 키를 안 누른 중간 상태라서, 그것을 조합으로 굳히면 안 된다.
         */
        fun fromKeyEvent(awtKeyCode: Int, modifiersEx: Int): HotkeyCombo? {
            val vk = Win32Keys.fromAwt(awtKeyCode) ?: return null
            return HotkeyCombo(modsFromAwt(modifiersEx), vk)
        }

        /** AWT 의 `modifiersEx` 비트를 Win32 MOD_* 비트로. 윈도우 키는 AWT 에서 META 다. */
        fun modsFromAwt(modifiersEx: Int): Int {
            var mods = 0
            if (modifiersEx and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0) mods = mods or MOD_CONTROL
            if (modifiersEx and java.awt.event.InputEvent.ALT_DOWN_MASK != 0) mods = mods or MOD_ALT
            if (modifiersEx and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0) mods = mods or MOD_SHIFT
            if (modifiersEx and java.awt.event.InputEvent.META_DOWN_MASK != 0) mods = mods or MOD_WIN
            return mods
        }

        /**
         * `"Ctrl++"`, `"Ctrl+Shift++"` 처럼 **키가 `+` 자체**인 경우 때문에 그냥 split 을
         * 못 쓴다. 마지막 자리의 빈 토막은 `+` 키로 본다.
         */
        private fun splitTokens(raw: String): List<String>? {
            val parts = raw.split('+').map { it.trim() }
            // 끝에 빈 토막이 둘이면("Ctrl++" → ["Ctrl","",""]) 키가 '+' 자체다.
            // 하나뿐이면("Ctrl+") 키 없이 조합키만 적은 것이라 읽을 수 없다.
            var tail = 0
            while (tail < parts.size && parts[parts.size - 1 - tail].isEmpty()) tail++
            return when (tail) {
                0 -> parts
                2 -> parts.subList(0, parts.size - 2) + "+"
                else -> null
            }
        }

        private fun modifierBit(token: String): Int? = when (token.lowercase()) {
            "ctrl", "control", "ctl" -> MOD_CONTROL
            "shift" -> MOD_SHIFT
            "alt", "menu" -> MOD_ALT
            "win", "windows", "meta", "super", "cmd" -> MOD_WIN
            else -> null
        }

        /** 이름 → Win32 가상키. 모르면 null. */
        fun keyCode(token: String): Int? {
            val t = token.trim()
            if (t.isEmpty()) return null
            if (t.length == 1) {
                val c = t[0].uppercaseChar()
                if (c in '0'..'9') return c.code            // 0x30..0x39
                if (c in 'A'..'Z') return c.code            // 0x41..0x5A
                SYMBOLS[c]?.let { return it }
            }
            NAME_TO_VK[t.lowercase()]?.let { return it }
            // 이름 없는 키는 16진수로 오간다. 이렇게 해 두어야 어떤 가상키든 적었다
            // 읽었을 때 그대로 돌아온다 — 설정이 조용히 다른 키로 바뀌는 일이 없다.
            if (t.startsWith("0x", ignoreCase = true)) {
                val v = t.substring(2).toIntOrNull(16) ?: return null
                return if (v in 1..0xFF) v else null
            }
            return null
        }

        /** Win32 가상키 → 이름. 이름이 없으면 `0x4B` 꼴. */
        fun keyName(vk: Int): String {
            VK_TO_NAME[vk]?.let { return it }
            if (vk in 0x30..0x39 || vk in 0x41..0x5A) return vk.toChar().toString()
            SYMBOL_NAMES[vk]?.let { return it }
            return "0x%02X".format(vk)
        }

        // ---- 이름표 ------------------------------------------------------------------
        // Win32 VK 값이다. MSDN 의 Virtual-Key Codes 와 한 자도 다르면 안 된다.
        private val VK_TO_NAME: Map<Int, String> = buildMap {
            put(0x08, "Backspace"); put(0x09, "Tab"); put(0x0D, "Enter")
            put(0x13, "Pause"); put(0x14, "CapsLock"); put(0x1B, "Esc"); put(0x20, "Space")
            put(0x21, "PageUp"); put(0x22, "PageDown"); put(0x23, "End"); put(0x24, "Home")
            put(0x25, "Left"); put(0x26, "Up"); put(0x27, "Right"); put(0x28, "Down")
            put(0x2C, "PrintScreen"); put(0x2D, "Insert"); put(0x2E, "Delete")
            put(0x5B, "LWin"); put(0x5C, "RWin"); put(0x5D, "Apps")
            for (n in 0..9) put(0x60 + n, "Num$n")
            put(0x6A, "NumMultiply"); put(0x6B, "NumAdd"); put(0x6C, "NumSeparator")
            put(0x6D, "NumSubtract"); put(0x6E, "NumDecimal"); put(0x6F, "NumDivide")
            for (n in 1..24) put(0x70 + n - 1, "F$n")
            put(0x90, "NumLock"); put(0x91, "ScrollLock")
            // Shift(0x10)·Ctrl(0x11)·Alt(0x12) 에는 일부러 이름을 안 준다. 이름을 주면
            // "Ctrl" 이라고만 적힌 설정이 **'Ctrl 키 하나'라는 조합**으로 읽혀 버린다.
            // 어차피 조합키는 키 자리에 못 오므로(rejectReason) 16진수로 오가면 그만이다.
        }

        /** 글자 하나로 치는 기호들. 미국 자판 기준 OEM 키다. */
        private val SYMBOLS: Map<Char, Int> = mapOf(
            ';' to 0xBA, '=' to 0xBB, ',' to 0xBC, '-' to 0xBD, '.' to 0xBE, '/' to 0xBF,
            '`' to 0xC0, '[' to 0xDB, '\\' to 0xDC, ']' to 0xDD, '\'' to 0xDE,
            // '+' 는 미국 자판에서 Shift+= 라 같은 OEM_PLUS 다. 사람이 "Ctrl++" 라고 적어도 읽힌다.
            '+' to 0xBB,
        )
        private val SYMBOL_NAMES: Map<Int, String> = mapOf(
            0xBA to ";", 0xBB to "=", 0xBC to ",", 0xBD to "-", 0xBE to ".", 0xBF to "/",
            0xC0 to "`", 0xDB to "[", 0xDC to "\\", 0xDD to "]", 0xDE to "'",
        )

        private val NAME_TO_VK: Map<String, Int> = buildMap {
            for ((vk, name) in VK_TO_NAME) put(name.lowercase(), vk)
            // 사람이 쓰는 다른 이름들. 설정 파일을 손으로 고치는 사람이 있다.
            put("return", 0x0D); put("escape", 0x1B); put("spacebar", 0x20)
            put("del", 0x2E); put("ins", 0x2D); put("pgup", 0x21); put("pgdn", 0x22)
            put("pagedn", 0x22); put("prtsc", 0x2C); put("printscrn", 0x2C)
            put("arrowleft", 0x25); put("arrowright", 0x27); put("arrowup", 0x26); put("arrowdown", 0x28)
            for (n in 0..9) put("numpad$n", 0x60 + n)
            put("plus", 0xBB); put("minus", 0xBD); put("comma", 0xBC); put("period", 0xBE)
            put("slash", 0xBF); put("backslash", 0xDC); put("semicolon", 0xBA)
            put("backquote", 0xC0); put("grave", 0xC0); put("quote", 0xDE); put("equals", 0xBB)
        }
    }
}
