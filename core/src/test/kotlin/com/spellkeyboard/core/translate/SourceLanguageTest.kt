package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 번역할 글의 언어를 글자 모양으로 가린다([SourceLanguage]). */
class SourceLanguageTest {

    @Test
    fun `네 언어를 가린다`() {
        assertEquals("ko", SourceLanguage.detect("오늘 저녁 뭐 먹을까?"))
        assertEquals("en", SourceLanguage.detect("What should we eat tonight?"))
        assertEquals("ja", SourceLanguage.detect("今夜は何を食べようか？"))
        assertEquals("zh", SourceLanguage.detect("今晚吃什么？"))
    }

    @Test
    fun `한자가 많아도 가나가 있으면 일본어`() {
        assertEquals("ja", SourceLanguage.detect("東京都渋谷区に住んでいます"))
        assertEquals("ja", SourceLanguage.detect("コーヒー"))
    }

    @Test
    fun `섞였으면 많은 쪽 — 한글 한 글자는 로마자 두 글자로 센다`() {
        assertEquals("ko", SourceLanguage.detect("오늘 meeting 있어"))
        assertEquals("ko", SourceLanguage.detect("OK 알겠어 ㅋㅋ"))
        assertEquals("en", SourceLanguage.detect("I love 김치"))
    }

    @Test
    fun `글자가 없으면 모른다`() {
        assertNull(SourceLanguage.detect("12:30 👍"))
        assertNull(SourceLanguage.detect(""))
    }
}
