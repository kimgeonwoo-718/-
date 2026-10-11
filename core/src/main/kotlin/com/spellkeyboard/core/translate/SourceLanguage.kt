package com.spellkeyboard.core.translate

/**
 * 번역할 글이 어느 말인지 **글자 모양**으로 가린다 — 한국어·영어·일본어·중국어 넷만.
 *
 * 기기 번역기(ML Kit)는 원문 언어를 알려 줘야 옮긴다. 예전에는 늘 '한국어' 로 알려 줘서, 입력란의 일본어를 중국어로·중국어를 영어로
 * '전체번역' 하면 그대로 나오거나 엉뚱하게 나왔다(2026-10-11 사용자 제보). 영어 자판으로 친 영어를 일본어로 옮길 때도 같았다.
 *
 * 넷은 글자만 봐도 갈린다: 가나가 있으면 일본어(그 글의 한자도 일본어 몫), 한자만 있으면 중국어, 한글이면 한국어, 로마자면 영어.
 * 섞였으면 많은 쪽이다. 한글·한자 한 글자는 로마자 두 글자로 센다 — 한 글자에 담긴 말이 그만큼 많다
 * ('오늘 meeting 있어' 는 한국어, 'I love 김치' 는 영어). 같으면 한국어 쪽으로 — 한국어 키보드다.
 */
object SourceLanguage {

    const val KOREAN = "ko"

    /** @return [KOREAN]·[Phrasebook.ENGLISH]·[Phrasebook.JAPANESE]·[Phrasebook.CHINESE]. 글자가 없으면(숫자·부호·이모지뿐) null. */
    fun detect(text: String): String? {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (ch in text) {
            when (ch) {
                in '가'..'힣' -> hangul += 2
                in 'ㄱ'..'ㆎ' -> hangul += 1 // ㅋㅋ·ㅠㅠ
                in 'ぁ'..'ヿ', in 'ㇰ'..'ㇿ', in 'ｦ'..'ﾝ' -> kana += 1
                in '一'..'鿿', in '㐀'..'䶿', in '豈'..'﫿' -> han += 2
                in 'a'..'z', in 'A'..'Z' -> latin += 1
            }
        }
        val japanese = if (kana > 0) kana + han else 0
        val chinese = if (kana > 0) 0 else han
        val best = maxOf(hangul, japanese, chinese, latin)
        return when {
            best == 0 -> null
            hangul == best -> KOREAN
            japanese == best -> Phrasebook.JAPANESE
            chinese == best -> Phrasebook.CHINESE
            else -> Phrasebook.ENGLISH
        }
    }
}
