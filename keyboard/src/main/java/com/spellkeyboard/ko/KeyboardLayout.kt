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
    SYMBOL_PAGE,
    /** 천지인의 숫자 판으로. */
    NUMPAD,
    /** 어느 판에서든 한글 판으로 돌아간다(삼성의 "가" 키). */
    KOREAN,
    /**
     * 천지인 "다음". 조합 중인 글자를 끝내고 다음 글자를 새로 시작한다.
     *
     * 천지인은 한 키에 자음이 둘셋 실려서, 같은 키를 연달아 누르면 다음 글자로 돈다
     * (ㄴ→ㄹ). 그래서 "안녕" 처럼 받침 ㄴ 뒤에 초성 ㄴ 이 오면 잠시 기다렸다 눌러야 한다.
     * 이 키가 그 기다림을 대신한다 — 누르면 곧바로 다음 글자로 넘어간다.
     */
    NEXT_CHAR
}

/** 키보드가 보여줄 문자 배열. */
enum class KeyboardMode {
    KOREAN,
    ENGLISH,
    SYMBOLS,
    /** 천지인 전용 숫자 판. 쿼티에서는 쓰지 않는다. */
    NUMPAD
}

/** 한글 자판 종류. 영문·기호 자판은 둘 다 같다. */
enum class LayoutType {
    QWERTY,
    CHEONJIIN
}

/**
 * 천지인 키 하나. [label] 이 키에 보이는 글자, [key] 가 오토마타에 보내는 키 이름
 * (그 키의 첫 글자), [hint] 는 오른쪽 위에 작게 보이는 숫자 — 길게 누르면 그게 들어간다.
 */
data class CheonjiinKey(val label: String, val key: Char, val hint: Char)

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

    fun showsNumberRow(mode: KeyboardMode): Boolean =
        mode == KeyboardMode.KOREAN || mode == KeyboardMode.ENGLISH

    /**
     * 천지인 한글 판의 위 세 줄. 삼성 배열 그대로 — 전화기 1~9 자리에 숫자 힌트가 붙는다.
     * 넷째 줄(!#1 · 한/영 · ㅇㅁ⁰ · 스페이스 · ,)과 오른쪽 기능 열(⌫ ↵ .,?!)은 뷰가 그린다.
     */
    val CHEONJIIN_GRID: List<List<CheonjiinKey>> = listOf(
        listOf(CheonjiinKey("ㅣ", 'ㅣ', '1'), CheonjiinKey("ㆍ", 'ㆍ', '2'), CheonjiinKey("ㅡ", 'ㅡ', '3')),
        listOf(CheonjiinKey("ㄱㅋ", 'ㄱ', '4'), CheonjiinKey("ㄴㄹ", 'ㄴ', '5'), CheonjiinKey("ㄷㅌ", 'ㄷ', '6')),
        listOf(CheonjiinKey("ㅂㅍ", 'ㅂ', '7'), CheonjiinKey("ㅅㅎ", 'ㅅ', '8'), CheonjiinKey("ㅈㅊ", 'ㅈ', '9'))
    )

    /** 넷째 줄의 ㅇㅁ. 전화기 0 자리. */
    val CHEONJIIN_ZERO_KEY = CheonjiinKey("ㅇㅁ", 'ㅇ', '0')

    /** 천지인 문장부호 키가 연타로 도는 순서. */
    const val CHEONJIIN_PUNCTUATION = ".,?!"

    /** 숫자 판의 오른쪽 부호 키가 도는 순서. */
    const val NUMPAD_PUNCTUATION = ".,-/"

    /** 천지인 숫자 판. 넷째 줄(!@# · 가 · 0 · 스페이스 · ,)은 뷰가 그린다. */
    val CHEONJIIN_NUMPAD = listOf("123", "456", "789")

    /** 천지인 기호 판. 6열 × 3줄, 세 페이지. 첫 페이지는 삼성 배열 그대로. */
    val CHEONJIIN_SYMBOL_PAGES: List<List<String>> = listOf(
        listOf("!?.,()", "@:;/-♡", "*_%~^#"),
        listOf("[]{}<>", "+=×÷\$₩", "€£¥&|\\"),
        listOf("©®™℅°•", "√π∞≠≈∆", "★☆♥♪¡¿")
    )

    fun rowsFor(mode: KeyboardMode, shifted: Boolean): List<String> = when (mode) {
        KeyboardMode.KOREAN -> if (shifted) KOREAN_SHIFTED else KOREAN
        KeyboardMode.ENGLISH -> if (shifted) ENGLISH.map { it.uppercase() } else ENGLISH
        KeyboardMode.SYMBOLS -> SYMBOL_PAGES.first().rows
        KeyboardMode.NUMPAD -> CHEONJIIN_NUMPAD
    }

    /** 시프트가 의미 있는 배열인지. 기호 자판에는 시프트가 없다. */
    fun supportsShift(mode: KeyboardMode): Boolean =
        mode == KeyboardMode.KOREAN || mode == KeyboardMode.ENGLISH
}
