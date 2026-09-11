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

    /**
     * 길게 눌렀을 때 나오는 글자.
     *
     * 두벌식에서 쌍자음은 시프트로만 넣을 수 있어 한 글자 치는 데 두 번 눌러야 한다.
     * 길게 누르기를 열어 두면 시프트를 거치지 않고 바로 넣을 수 있다.
     */
    private val LONG_PRESS = mapOf(
        'ㄱ' to 'ㄲ', 'ㄷ' to 'ㄸ', 'ㅂ' to 'ㅃ', 'ㅅ' to 'ㅆ', 'ㅈ' to 'ㅉ',
        'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ',
        // 숫자 줄에서는 기호가 나온다. 기호 자판으로 건너가지 않고 바로 넣을 수 있다.
        '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')'
    )

    fun longPressOf(c: Char): Char? = LONG_PRESS[c]

    /**
     * 자판 위에 늘 떠 있는 숫자 줄.
     *
     * 숫자 하나 넣자고 기호 자판으로 건너갔다 돌아오는 건 번거롭다. 시중 키보드가
     * 대부분 숫자 줄을 고정해 두는 이유다. 기호 자판은 첫 줄이 이미 숫자라 뺀다.
     */
    const val NUMBER_ROW = "1234567890"

    fun showsNumberRow(mode: KeyboardMode): Boolean = mode != KeyboardMode.SYMBOLS

    fun rowsFor(mode: KeyboardMode, shifted: Boolean): List<String> = when (mode) {
        KeyboardMode.KOREAN -> if (shifted) KOREAN_SHIFTED else KOREAN
        KeyboardMode.ENGLISH -> if (shifted) ENGLISH.map { it.uppercase() } else ENGLISH
        KeyboardMode.SYMBOLS -> SYMBOLS
    }

    /** 시프트가 의미 있는 배열인지. 기호 자판에는 시프트가 없다. */
    fun supportsShift(mode: KeyboardMode): Boolean = mode != KeyboardMode.SYMBOLS
}
