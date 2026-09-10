package com.spellkeyboard.core.spacing

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpellerTest {

    @Test
    fun `사전에 없는 오타도 잡는다`() {
        assertEquals("안녕하세요", speller.correct("안녕하새요"))
        assertEquals("했는데요", speller.correct("햇는대요"))
        assertEquals("됐어요", speller.correct("됫어요"))
    }

    @Test
    fun `멀쩡한 말은 건드리지 않는다`() {
        for (word in listOf(
            "안녕하세요", "감사합니다", "사랑해", "그래서", "새로운", "대박",
            "네가", "내가", "매일", "메일", "학교에서", "괜찮아요", "먹었습니다",
            "카카오톡", "인스타그램", "스마트폰", "대한민국",
            "재밌다", "맛있어", "예뻐요", "고마워", "반가워요", "잘했어",
            "오빠", "언니", "친구야", "좋아요", "싫어요", "배고파"
        )) {
            assertNull(speller.correct(word), "$word 를 고쳤다")
        }
    }

    @Test
    fun `한글이 아니면 손대지 않는다`() {
        assertNull(speller.correct("hello"))
        assertNull(speller.correct("123"))
    }

    companion object {
        private val speller: Speller by lazy {
            val dir = Files.createTempDirectory("spell-dict").toFile()
            dir.deleteOnExit()
            Speller(Spacer(SpacingDictionary.open(dir)))
        }
    }
}
