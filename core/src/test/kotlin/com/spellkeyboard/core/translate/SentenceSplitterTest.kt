package com.spellkeyboard.core.translate

import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceSplitterTest {

    private val plain = SentenceSplitter()

    @Test
    fun `문장 부호에서 자른다`() {
        assertEquals(
            listOf("안녕하세요.", "오늘 기분이 어떠세요?", "저는 좋아요!"),
            plain.split("안녕하세요. 오늘 기분이 어떠세요? 저는 좋아요!")
        )
    }

    @Test
    fun `줄바꿈에서 자른다`() {
        assertEquals(listOf("첫 줄", "둘째 줄"), plain.split("첫 줄\n둘째 줄"))
    }

    @Test
    fun `부호가 없으면 한 덩어리로 둔다`() {
        assertEquals(listOf("오늘 날씨가 좋네"), plain.split("오늘 날씨가 좋네"))
    }

    @Test
    fun `빈 글과 공백만 있는 글`() {
        assertEquals(emptyList(), plain.split(""))
        assertEquals(emptyList(), plain.split("   \n  "))
    }

    @Test
    fun `너무 길면 공백에서 끊는다`() {
        val long = (1..30).joinToString(" ") { "단어$it" }
        val pieces = plain.split(long)
        assertTrue(pieces.size > 1, "안 잘렸다")
        pieces.forEach { assertTrue(it.length <= SentenceSplitter.maxChars, it) }
        assertEquals(long, pieces.joinToString(" "))
    }

    // --- 형태소 사전이 있을 때: 부호 없는 채팅도 자른다 -------------------------

    @Test
    fun `종결어미에서 자른다`() {
        assertEquals(
            listOf("안녕하세요", "저는 김건우입니다", "오늘 기분이 어떠세요", "저는 좋아요"),
            morphological.split("안녕하세요 저는 김건우입니다 오늘 기분이 어떠세요 저는 좋아요")
        )
    }

    @Test
    fun `문장이 끝나는 어절을 가려낸다`() {
        for (word in listOf(
            "안녕하세요", "김건우입니다", "어떠세요", "좋아요", "합니다", "가자", "좋다",
            "봐요", "지내세요", "괜찮아요", "고마워요", "먹었습니다", "그렇죠", "할게요",
            "볼까요", "먹을까", "왔다", "간다", "예뻐요", "좋네"
        )) {
            assertTrue(endsSentence(word), "'$word' 에서 문장이 끝나야 한다")
        }
        for (word in listOf(
            "갔는데", "좋아서", "기분이", "날씨가", "학교에", "어제", "저는", "오늘",
            "먹으면", "하지만", "가려고", "좋다고", "간다면", "먹고"
        )) {
            kotlin.test.assertFalse(endsSentence(word), "'$word' 는 문장 중간이다")
        }
    }

    @Test
    fun `문장 가운데는 자르지 않는다`() {
        assertEquals(listOf("오늘 날씨가 좋아서 기분이 좋다"), morphological.split("오늘 날씨가 좋아서 기분이 좋다"))
        assertEquals(listOf("내가 어제 학교에 갔는데"), morphological.split("내가 어제 학교에 갔는데"))
    }

    @Test
    fun `원문을 빠뜨리지 않는다`() {
        for (text in listOf(
            "안녕하세요 저는 김건우입니다 오늘 기분이 어떠세요 저는 좋아요",
            "밥 먹었어? 나는 아직",
            "어떻게 지내세요 저는 잘 지내요"
        )) {
            assertEquals(
                text.split(Regex("\\s+")).filter { it.isNotEmpty() },
                morphological.split(text).flatMap { it.split(" ") },
                "'$text' 에서 어절이 샜다"
            )
        }
    }

    companion object {
        private val spacer: Spacer by lazy {
            val dir = Files.createTempDirectory("split").toFile().also { it.deleteOnExit() }
            Spacer(SpacingDictionary.open(dir))
        }
        private val morphological: SentenceSplitter by lazy {
            SentenceSplitter { spacer.endsWithFinalEnding(it) }
        }

        private fun endsSentence(word: String) = spacer.endsWithFinalEnding(word)
    }
}
