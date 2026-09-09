package com.spellkeyboard.ko

/** 문자 키가 아닌 기능 키. */
enum class KeyAction {
    SHIFT,
    BACKSPACE,
    SPACE,
    ENTER,
    LANGUAGE,
    SYMBOLS
}

/** 키보드가 보여줄 문자 배열. */
enum class KeyboardMode {
    KOREAN,
    ENGLISH,
    SYMBOLS
}

/**
 * 두벌식/QWERTY 자판 배열.
 *
 * 각 문자열 하나가 한 줄이다. 줄마다 길이가 달라서 뷰에서 좌우 여백으로 균형을 맞춘다.
 */
object KeyboardLayout {

    private val KOREAN = listOf(
        "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ",
        "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ",
        "ㅋㅌㅊㅍㅠㅜㅡ"
    )

    /** 두벌식에서 시프트로 얻는 것은 쌍자음 5개와 ㅒ/ㅖ 뿐이다. */
    private val KOREAN_SHIFTED = listOf(
        "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖ",
        "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ",
        "ㅋㅌㅊㅍㅠㅜㅡ"
    )

    private val ENGLISH = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    private val SYMBOLS = listOf(
        "1234567890",
        "-/:;()₩&@",
        ".,?!'"
    )

    fun rowsFor(mode: KeyboardMode, shifted: Boolean): List<String> = when (mode) {
        KeyboardMode.KOREAN -> if (shifted) KOREAN_SHIFTED else KOREAN
        KeyboardMode.ENGLISH -> if (shifted) ENGLISH.map { it.uppercase() } else ENGLISH
        KeyboardMode.SYMBOLS -> SYMBOLS
    }

    /** 시프트가 의미 있는 배열인지. 기호 자판에는 시프트가 없다. */
    fun supportsShift(mode: KeyboardMode): Boolean = mode != KeyboardMode.SYMBOLS
}
