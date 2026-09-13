package com.spellkeyboard.core.correct

import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 키보드가 실제로 차리는 구성(규칙 + 형태소 사전 + 언어모델)으로 스페이스를 눌렀을 때
 * 무엇이 바뀌는지 본다. 부품 테스트가 다 통과해도 여기가 깨지면 실기기에서 안 되는 것이다.
 */
class EngineWithContextTest {

    private fun tail(text: String): String =
        engine.correctTail(text)?.let { text.substring(0, text.length - it.deleteBefore) + it.replacement } ?: text

    @Test
    fun `사용자가 실기기에서 잡은 문장들`() {
        assertEquals("여기 있는데요", tail("여기 있는대요"))
        assertEquals("뭐 하세요", tail("뭐 하 새요"))
    }

    @Test
    fun `규칙과 언어모델이 이어 붙어 마무리한다`() {
        // 언어모델이 '됬어요' 를 고르더라도 어절 규칙이 '됐어요' 로 끝낸다.
        assertEquals("됐어요", tail("됫어요"))
        assertEquals("그렇게 하면 안 돼요", tail("그렇게 하면 안되요"))
        assertEquals("할 수 있다", tail("할수있다"))
        assertEquals("나는 할 수 있다", tail("나는 할수있다"))
        assertEquals("오늘은 날씨가 좋아서 기분이 좋다", tail("오늘은날씨가좋아서기분이좋다"))
    }

    @Test
    fun `홀로 떨어진 조사를 붙이되 수사 만 은 건드리지 않는다`() {
        assertEquals("학교에서 만나자", tail("학교 에서 만나자"))
        assertEquals("내일까지", tail("내일 까지"))
        assertNull(engine.correctTail("당신이 준 만 달러로"), "'만 달러'의 '만'을 붙였다")
    }

    @Test
    fun `앞 문맥이 창 밖에 있어도 쓴다`() {
        // 창은 마지막 세 어절이지만, 그 앞 어절도 문맥으로 넘긴다.
        assertEquals("나는 어제 할 수 있다", tail("나는 어제 할수있다"))
    }

    @Test
    fun `멀쩡한 문장은 그대로`() {
        for (text in listOf(
            "나는 학교에 간다", "오늘 날씨가 정말 좋네요", "네가 좋아", "내가 좋아",
            "카카오톡 열어 봐", "삼성 갤럭시 폰", "저는 학생입니다", "선생님께 드렸다"
        )) {
            assertNull(engine.correctTail(text), "$text → ${tail(text)}")
        }
    }

    companion object {
        private val engine: CorrectionEngine by lazy {
            val dir = Files.createTempDirectory("engine-ctx").toFile().also { it.deleteOnExit() }
            val spacer = Spacer(SpacingDictionary.open(dir))
            CorrectionEngine().apply {
                this.spacer = spacer
                this.speller = Speller(spacer)
                this.context = ContextCorrector(LanguageModel.open(dir), spacer)
            }
        }
    }
}
