package com.spellkeyboard.core.ai

/**
 * 딱 필요한 만큼의 JSON.
 *
 * 응답 하나를 읽고 요청 하나를 쓰려고 라이브러리를 끌어오면 APK 가 무거워진다.
 * 안드로이드의 `org.json` 을 쓰면 이 모듈을 JVM 테스트로 돌릴 수 없다.
 * 그래서 작게 직접 쓴다 — 대신 파서는 테스트로 조인다.
 */
internal object Json {

    /** JSON 문자열을 Map/List/String/Double/Boolean/null 로 읽는다. */
    fun parse(text: String): Any? = Reader(text).let {
        val value = it.readValue()
        it.skipWhitespace()
        require(it.atEnd()) { "JSON 뒤에 남은 글자가 있다" }
        value
    }

    /** 문자열을 JSON 리터럴로 감싼다. 따옴표까지 붙여 돌려준다. */
    fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    // 제어문자는 그대로 두면 안 되는 JSON 이 된다.
                    if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** 중첩된 맵에서 키를 따라 내려간다. 중간에 없으면 null. */
    fun dig(root: Any?, vararg keys: String): Any? {
        var current = root
        for (key in keys) {
            current = (current as? Map<*, *>)?.get(key) ?: return null
        }
        return current
    }

    private class Reader(private val source: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= source.length

        fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(index < source.length) { "JSON 이 끝났는데 값이 없다" }
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            index++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue()
                skipWhitespace()
                when (val next = next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("객체에 예상 못 한 글자: $next")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return list
            }
            while (true) {
                list += readValue()
                skipWhitespace()
                when (val next = next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("배열에 예상 못 한 글자: $next")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val ch = next()
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(ch)
                }
            }
        }

        private fun readEscape(): Char = when (val ch = next()) {
            '"', '\\', '/' -> ch
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                require(index + 4 <= source.length) { "잘린 유니코드 이스케이프" }
                val code = source.substring(index, index + 4).toInt(16)
                index += 4
                code.toChar()
            }
            else -> throw IllegalArgumentException("알 수 없는 이스케이프: \\$ch")
        }

        private fun readNumber(): Double {
            val start = index
            while (index < source.length && source[index] !in ",]} \t\n\r") index++
            return source.substring(start, index).toDouble()
        }

        private fun <T> literal(word: String, value: T): T {
            require(source.startsWith(word, index)) { "$word 이 아니다" }
            index += word.length
            return value
        }

        private fun peek(): Char = source[index]

        private fun next(): Char {
            require(index < source.length) { "JSON 이 갑자기 끝났다" }
            return source[index++]
        }

        private fun expect(ch: Char) {
            require(next() == ch) { "$ch 가 있어야 한다" }
        }
    }
}
