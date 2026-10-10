package com.spellkeyboard.core.translate

/**
 * 모델이 내놓은 번역문을 **믿고 쓸 수 있는지 가리고** 군더더기를 뗀다.
 *
 * 작은 모델은 가끔 (1) 머리말·따옴표를 붙이고, (2) 낯선 말 하나를 못 옮겨 한글로 남기고, (3) 엉뚱한 언어로 답하고, (4) 같은 말을 되풀이한다.
 * 번역문은 사용자가 그대로 보내므로 이런 글은 쓰지 않고 `null` 을 돌려준다 — 부르는 쪽이 ML Kit 번역으로 대신한다.
 * 서버의 `cleanTranslation`·`translationProblem`(server/src/openai.js)과 같은 생각이다.
 */
object LlmOutput {

    /** @return 쓸 수 있는 번역문, 못 쓰면 null. */
    fun clean(raw: String, source: String, language: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        // 코드 울타리, "번역:" 머리말, 통째로 감싼 따옴표.
        text = text.replace(FENCE_START, "").replace(FENCE_END, "").trim()
        text = text.replace(LABEL, "")
        val wrapped = WRAPPED.matchEntire(text)
        if (wrapped != null && QUOTES.none { it in wrapped.groupValues[2] }) text = wrapped.groupValues[2]
        text = text.trim()
        if (text.isEmpty()) return null

        // 한글(음절)이 남았으면 못 옮긴 것이다. ㅋㅋ·ㅠㅠ 같은 자모는 음절이 아니라 봐준다.
        if (text.any { it in '가'..'힣' }) return null

        // 원문보다 터무니없이 길면 되풀이다. 짧은 글에서 쓸 여유(60자)를 둔다.
        if (text.length > maxOf(MIN_ROOM, source.length * MAX_RATIO)) return null

        if (wrongScript(text, language)) return null
        return text
    }

    /** 요청한 언어의 글자로 안 쓰였나. 숫자·영문·이모지뿐이면 어느 언어든 맞다. */
    private fun wrongScript(text: String, language: String): Boolean {
        val letters = text.count { it.isLetter() }
        val kana = text.any { it in '぀'..'ヿ' }
        val han = text.any { it in '一'..'鿿' }
        return when (language) {
            Phrasebook.JAPANESE -> letters >= 4 && !kana && han // 한자만 있는 일본어는 드물다 → 중국어로 나온 것
            Phrasebook.CHINESE -> kana || (letters >= 4 && !han && !Regex("[A-Za-z]{4}").containsMatchIn(text))
            Phrasebook.ENGLISH -> kana || han
            else -> false
        }
    }

    private const val MIN_ROOM = 60
    private const val MAX_RATIO = 8
    private val FENCE_START = Regex("""^```[A-Za-z]*\s*\n?""")
    private val FENCE_END = Regex("""\n?```\s*$""")
    private val LABEL = Regex("""^(?:번역(?:문)?|Translation|Translated(?: text)?|翻訳|翻译|译文)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    private val WRAPPED = Regex("""^(["“「『])([^\n]*)(["”」』])$""")
    private val QUOTES = charArrayOf('"', '“', '”', '「', '」', '『', '』')
}
