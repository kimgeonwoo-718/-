package com.spellkeyboard.ko

/** 문자 키가 아닌 기능 키. */
enum class KeyAction {
    SHIFT,
    BACKSPACE,
    SPACE,
    ENTER,
    LANGUAGE,
    SYMBOLS,
    /** 기호 자판의 1/2·2/2 — 다음 기호 페이지로 넘긴다. */
    SYMBOL_PAGE
}

/** 키보드가 보여줄 문자 배열. */
enum class KeyboardMode {
    KOREAN,
    ENGLISH,
    SYMBOLS
}

/** 한글 자판 종류. 영문·기호 자판은 둘 다 같다. */
enum class LayoutType {
    QWERTY,
    CHEONJIIN
}

/**
 * 천지인 키 하나. [label] 이 키에 보이는 글자, [key] 가 오토마타에 보내는 키 이름
 * (그 키의 첫 글자). null 이면 기능 키다.
 */
data class CheonjiinKey(val label: String, val key: Char?)

/** 기호 자판 한 페이지. [rows] 는 숫자·기호 3줄, [extra] 는 특수줄 7키. */
data class SymbolPage(val rows: List<String>, val extra: String)

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

    /**
     * 삼성 키보드 기호 배열. 두 페이지다.
     *
     * 각 페이지는 [숫자·기호 3줄, 아래 특수줄 7키]. 특수줄은 1/2·2/2 토글과 백스페이스
     * 사이에 끼는 일곱 키다. 삼성 배열을 눈으로 맞췄다.
     */
    val SYMBOL_PAGES: List<SymbolPage> = listOf(
        SymbolPage(
            rows = listOf(
                "1234567890",
                "+×÷=/_<>[]",
                "!@#₩%^&*()"
            ),
            extra = "-'\":;,?"
        ),
        SymbolPage(
            rows = listOf(
                "1234567890",
                "~`|•√π¶∆°μ",
                "£¢€¥§{}\\℃℉"
            ),
            extra = "©®™℅★♥♡"
        )
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

    /**
     * 천지인 3×4 판. 전화기 자판(1~9, *, 0, #) 그대로다 — ㆍ 가 2번, ㅇㅁ 이 0번 자리.
     * 오른쪽 한 줄은 뷰가 기능 키(⌫ ↵ 스페이스 한/영)로 채운다.
     */
    val CHEONJIIN_GRID: List<List<CheonjiinKey>> = listOf(
        listOf(CheonjiinKey("ㅣ", 'ㅣ'), CheonjiinKey("ㆍ", 'ㆍ'), CheonjiinKey("ㅡ", 'ㅡ')),
        listOf(CheonjiinKey("ㄱㅋ", 'ㄱ'), CheonjiinKey("ㄴㄹ", 'ㄴ'), CheonjiinKey("ㄷㅌ", 'ㄷ')),
        listOf(CheonjiinKey("ㅂㅍ", 'ㅂ'), CheonjiinKey("ㅅㅎ", 'ㅅ'), CheonjiinKey("ㅈㅊ", 'ㅈ')),
        listOf(CheonjiinKey("!#1", null), CheonjiinKey("ㅇㅁ", 'ㅇ'), CheonjiinKey(".,?!", null))
    )

    /** 천지인 문장부호 키가 연타로 도는 순서. */
    const val CHEONJIIN_PUNCTUATION = ".,?!"

    fun rowsFor(mode: KeyboardMode, shifted: Boolean): List<String> = when (mode) {
        KeyboardMode.KOREAN -> if (shifted) KOREAN_SHIFTED else KOREAN
        KeyboardMode.ENGLISH -> if (shifted) ENGLISH.map { it.uppercase() } else ENGLISH
        KeyboardMode.SYMBOLS -> SYMBOL_PAGES.first().rows
    }

    /** 시프트가 의미 있는 배열인지. 기호 자판에는 시프트가 없다. */
    fun supportsShift(mode: KeyboardMode): Boolean = mode != KeyboardMode.SYMBOLS
}
