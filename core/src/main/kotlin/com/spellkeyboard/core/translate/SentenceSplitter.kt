package com.spellkeyboard.core.translate

/**
 * 번역에 넘기기 전에 문장 단위로 자른다.
 *
 * ## 왜 잘라야 하나
 *
 * 온디바이스 번역기(ML Kit)는 **문장 하나**를 번역하도록 만들어진 작은 모델이다. 여러
 * 문장을 한 번에 던지면 중간을 통째로 삼켜 버린다. 실기기에서
 *
 *     안녕하세요 저는 김건우입니다 오늘 기분이 어떠세요? 저는 좋아요
 *     → Hello, I am Kim Kun-woo? I like it.
 *
 * 처럼 가운데 문장이 사라지고 물음표만 엉뚱한 데 붙었다. 문장마다 따로 번역해서 이어
 * 붙이면 이 문제가 사라진다.
 *
 * ## 어디서 자르나
 *
 * 1. 문장 부호(`. ! ? …`)와 줄바꿈 — 가장 확실한 경계.
 * 2. **종결어미로 끝나는 어절** — 한국어 채팅은 부호를 거의 안 쓴다. '안녕하세요',
 *    '김건우입니다', '좋아요' 는 형태소 분석기 눈에 종결어미(EF)로 끝나므로 거기가
 *    문장 끝이다. 이 판정은 [endsSentence] 에 맡긴다 (형태소 사전이 없으면 안 쓴다).
 * 3. 그래도 [maxChars] 를 넘게 이어지면 마지막 공백에서 끊는다. 번역기가 감당할
 *    길이를 넘기면 어차피 뭉개진다.
 *
 * 잘게 자르면 문맥이 사라져 대명사 번역이 나빠질 수 있지만, 통째로 삼켜지는 것에 비하면
 * 훨씬 작은 손해다.
 */
class SentenceSplitter(
    /** 이 어절에서 문장이 끝나는가(종결어미). 형태소 사전이 없으면 늘 false. */
    private val endsSentence: (String) -> Boolean = { false }
) {

    /**
     * [text] 를 문장들로 자른다. 각 문장은 앞뒤 공백이 없고, 빈 문장은 들어가지 않는다.
     * 마지막 문장은 아직 쓰는 중일 수 있으므로 종결어미로 끝나도 더 자르지 않는다.
     */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()

        for (token in TOKEN.findAll(text)) {
            val value = token.value
            if (value.isBlank()) {
                // 줄바꿈은 그 자체로 문장 경계다.
                if (value.any { it == '\n' }) flush(current, out) else if (current.isNotEmpty()) current.append(' ')
                continue
            }
            if (current.isNotEmpty() && current.length + value.length > maxChars) flush(current, out)
            current.append(value)
            if (endsHere(value)) flush(current, out)
        }
        flush(current, out)
        return out
    }

    /** 이 어절 뒤에서 문장이 끝나는가. */
    private fun endsHere(word: String): Boolean {
        val core = word.trimEnd(*TRAILING)
        if (word.length > core.length && word.last() in ENDERS) return true
        // 부호가 없으면 종결어미로 판단한다. 조사가 붙은 인용('좋아요라고')은 EF 로 안 끝난다.
        return core.isNotEmpty() && core.all { it in HANGUL } && endsSentence(core)
    }

    private fun flush(current: StringBuilder, out: MutableList<String>) {
        val sentence = current.toString().trim()
        if (sentence.isNotEmpty()) out += sentence
        current.setLength(0)
    }

    companion object {
        /** 한 문장의 최대 길이. 번역기가 이보다 길면 뒤를 흘린다. */
        const val maxChars = 80

        private val TOKEN = Regex("""\S+|\s+""")
        private val ENDERS = setOf('.', '!', '?', '…', '。', '！', '？')
        private val TRAILING = charArrayOf(
            '.', '!', '?', '…', '。', '！', '？', ',', '"', '\'', ')', ']', '»', '”', '’', '~'
        )
        private val HANGUL = '가'..'힣'
    }
}
