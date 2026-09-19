package com.spellkeyboard.core.correct

import com.spellkeyboard.core.TestCache
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
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

    private fun all(text: String): String = engine.correctAll(text).text

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
    fun `조사가 붙어도 아는 낱말이면 가르지 않는다`() {
        // 말뭉치에 '전국경제인연합회' 는 있어도 '전국경제인연합회가' 는 없다. 조사가 붙은
        // 채로 물어보면 모르는 낱말로 보여서 긴 덩어리 분해기가 멋대로 갈랐다.
        assertEquals("전국경제인연합회가 보고서를 냈다", all("전국경제인연합회가 보고서를 냈다"))
        // '소비자물가지수가' 는 여기 안 적는다. 여덟 음절이라 긴 덩어리 분해기가 아예 보지
        // 않고, 그것을 가르는 것은 문맥 디코더라 이 보호막이 닿지 않는다.
    }

    @Test
    fun `붙여 쓴 글은 그대로 갈라야 한다`() {
        // 위의 보호막이 갈라야 할 것까지 막으면 안 된다.
        assertEquals("오늘은 날씨가 좋아서 기분이 좋다", all("오늘은날씨가좋아서기분이좋다"))
        assertEquals("현재는 품절입니다", all("현재는품절입니다"))
    }

    @Test
    fun `여덟 음절이라도 억지스러우면 가른다`() {
        // 실기기 제보. 예전에는 길이(여덟 음절 넘으면)로 걸러서 이 말이 한 글자 차이로
        // 빠져나갔다. 이제 형태소 분석 비용으로 거른다 — 자연스러운 한 낱말이면 길어도
        // 지키고, 억지로 분석되면 짧아도 가른다.
        assertEquals("리뷰 이벤트 시에만", all("리뷰이벤트시에만"))
        assertEquals("본체는 기본이고", all("본체는기본이고"))
    }

    @Test
    fun `한 낱말로 자연스러우면 길어도 지킨다`() {
        // 위 문지방을 낮게 잡으면 이것이 '교육 과학 기술 부가' 로 깨진다. 기관 이름
        // 하나라 어디서 끊어도 틀린다.
        assertEquals("교육과학기술부가 발표했다", all("교육과학기술부가 발표했다"))
        // '국세청홈택스'는 여기 있었는데 뺐다. 기관 이름('국세청')과 서비스 이름
        // ('홈택스')이 이어진 것이라 **띄어 쓰는 쪽이 원칙**이다. 명사|명사 나누기를
        // 넓히면서 갈리게 됐는데, 그건 고친 것이지 망친 것이 아니다.
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
            val dir = TestCache.dir
            val spacer = Spacer(SpacingDictionary.open(dir))
            CorrectionEngine().apply {
                this.spacer = spacer
                this.speller = Speller(spacer)
                this.context = ContextCorrector(LanguageModel.open(dir), spacer)
            }
        }
    }
}
