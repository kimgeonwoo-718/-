package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorrectionEngineTest {

    private val engine = CorrectionEngine()

    private fun fix(text: String): String = engine.correct(text).text

    /** 교정 결과가 입력과 같아야 한다. 오교정 방지 테스트에 쓴다. */
    private fun assertUntouched(text: String) {
        val result = engine.correct(text)
        assertEquals(text, result.text, "고치지 말아야 할 문장을 고쳤다: ${result.corrections}")
    }

    // --- 맞춤법 --------------------------------------------------------------

    @Test
    fun `되 돼 를 가린다`() {
        assertEquals("됐다", fix("됬다"))
        assertEquals("안 됐어", fix("안 됬어"))
        assertEquals("그렇게 돼요", fix("그렇게 되요"))
        assertEquals("늦어서 돼서", fix("늦어서 되서"))
        assertEquals("잘 되는 것", fix("잘 돼는 것"))
    }

    @Test
    fun `안 과 않 을 가린다`() {
        assertEquals("안 돼요", fix("않되요"))
        assertEquals("안 된다", fix("않된다"))
        assertEquals("안 그래도", fix("않그래도"))
    }

    @Test
    fun `부사 안 은 되다 와 띄운다`() {
        assertEquals("그러면 안 돼요", fix("그러면 안되요"))
        assertEquals("그러면 안 돼요", fix("그러면 안돼요"))
        assertEquals("안 돼", fix("안돼"))
        assertEquals("안 되겠다", fix("안되겠다"))
        assertEquals("안 돼서 그래", fix("안되서 그래"))
        assertUntouched("편안되게")
    }

    @Test
    fun `왠 과 웬 을 가린다`() {
        assertEquals("웬만하면", fix("왠만하면"))
        assertEquals("웬일이야", fix("왠일이야"))
        assertEquals("왠지", fix("웬지"))
    }

    @Test
    fun `ㄹ 받침 뒤의 께 와 꺼 를 고친다`() {
        assertEquals("내가 할게요", fix("내가 할께요"))
        assertEquals("금방 갈게", fix("금방 갈께"))
        assertEquals("내가 할 거야", fix("내가 할꺼야"))
    }

    @Test
    fun `옛 표기 읍니다 를 고친다`() {
        assertEquals("있습니다", fix("있읍니다"))
        assertEquals("먹었습니까", fix("먹었읍니까"))
    }

    @Test
    fun `자주 틀리는 어휘를 고친다`() {
        assertEquals("어이없다", fix("어의없다"))
        assertEquals("어떡해", fix("어떻해"))
        assertEquals("역할", fix("역활"))
        assertEquals("며칠", fix("몇일"))
        assertEquals("오랜만이야", fix("오랫만이야"))
        assertEquals("깨끗이 치웠다", fix("깨끗히 치웠다"))
        assertEquals("개수를 세다", fix("갯수를 세다"))
        assertEquals("김치찌개", fix("김치찌게"))
        assertEquals("설렘", fix("설레임"))
    }

    @Test
    fun `어절 전체가 일치할 때만 고치는 규칙이 있다`() {
        assertEquals("굳이", fix("구지"))
        assertUntouched("구지면 사무소")
    }

    // --- 띄어쓰기 ------------------------------------------------------------

    @Test
    fun `의존명사 수 를 띄운다`() {
        assertEquals("할 수 있다", fix("할수있다"))
        assertEquals("할 수 있다", fix("할 수있다"))
        assertEquals("할 수 있다", fix("할수 있다"))
        assertEquals("갈 수 없어", fix("갈수없어"))
        assertEquals("먹을 수밖에", fix("먹을 수 밖에"))
    }

    @Test
    fun `의존명사 것 을 띄운다`() {
        assertEquals("먹을 것 같다", fix("먹을것같다"))
        assertEquals("갈 것이다", fix("갈것이다"))
    }

    @Test
    fun `의존명사 때 와 때문 을 띄운다`() {
        assertEquals("먹을 때", fix("먹을때"))
        assertEquals("갔을 때부터", fix("갔을때부터"))
        assertEquals("너 때문에", fix("너때문에"))
        assertEquals("비 때문에", fix("비때문에"))
    }

    @Test
    fun `의존명사 줄 을 띄운다`() {
        assertEquals("할 줄 알아", fix("할줄알아"))
        assertEquals("갈 줄 몰랐다", fix("갈줄몰랐다"))
    }

    @Test
    fun `홀로 떨어진 조사를 앞말에 붙인다`() {
        assertEquals("학교에서 만나자", fix("학교 에서 만나자"))
        assertEquals("나는 간다", fix("나 는 간다"))
        assertEquals("집으로 가자", fix("집 으로 가자"))
        assertEquals("내일까지", fix("내일 까지"))
    }

    // --- 오교정 방지 ---------------------------------------------------------

    @Test
    fun `ㄹ 받침 명사 뒤의 수 는 건드리지 않는다`() {
        assertUntouched("실수 없이 해냈다")
        assertUntouched("별수 없다")
        assertUntouched("홀수 없이")
    }

    @Test
    fun `관형형이 아닌 앞말 뒤의 의존명사는 건드리지 않는다`() {
        // '선/점'은 관형형 어미가 아니라 명사의 일부다.
        assertUntouched("선수 있다")
        assertUntouched("점수 없다")
    }

    @Test
    fun `한 단어로 굳어진 표기는 건드리지 않는다`() {
        assertUntouched("한때 유행했다")
        assertUntouched("본때를 보여줬다")
        assertUntouched("물때가 맞다")
        assertUntouched("별것 아니다")
    }

    @Test
    fun `높임 조사 께 는 건드리지 않는다`() {
        assertUntouched("선생님께 드렸다")
        assertUntouched("아버지께서 오셨다")
    }

    @Test
    fun `조사처럼 보이는 낱말은 붙이지 않는다`() {
        // '와'는 명령형 '오다'와 겹치고, '밖에'는 명사 '밖'과 겹친다.
        assertUntouched("여기 와 봐")
        assertUntouched("집 밖에 나갔다")
    }

    @Test
    fun `왠지 는 그대로 둔다`() {
        assertUntouched("왠지 기분이 좋다")
    }

    @Test
    fun `돼지 는 그대로 두되 돼지 않 만 고친다`() {
        assertUntouched("돼지고기를 먹었다")
        assertEquals("되지 않는다", fix("돼지 않는다"))
    }

    @Test
    fun `합성어 속의 꺼 는 건드리지 않는다`() {
        assertUntouched("볼꺼리가 많다")
    }

    @Test
    fun `한글이 없는 문장은 손대지 않는다`() {
        assertUntouched("hello world")
        assertUntouched("123 + 456")
    }

    @Test
    fun `이미 올바른 문장은 그대로 둔다`() {
        assertUntouched("할 수 있다")
        assertUntouched("나는 학교에서 공부한다")
        assertUntouched("먹을 것 같다")
        assertUntouched("너 때문에 늦었다")
    }

    // --- 꼬리 교정 -----------------------------------------------------------

    @Test
    fun `커서 앞 마지막 어절들만 교정한다`() {
        val tail = engine.correctTail("나는 어제 학교에 갔다 됬다")
        checkNotNull(tail)

        assertEquals("학교에 갔다 됬다", tail.original)
        assertEquals("학교에 갔다 됐다", tail.replacement)
        assertEquals("학교에 갔다 됬다".length, tail.deleteBefore)
    }

    @Test
    fun `창 밖의 오류는 건드리지 않는다`() {
        // 맨 앞 '됬다'는 3어절 창 밖이라 그대로 남는다.
        assertNull(engine.correctTail("됬다 하나 둘 셋"))
    }

    @Test
    fun `고칠 것이 없으면 null 을 준다`() {
        assertNull(engine.correctTail("나는 학교에 간다"))
        assertNull(engine.correctTail(""))
    }

    @Test
    fun `무엇을 고쳤는지 기록한다`() {
        val result = engine.correct("할수있다")
        assertTrue(result.changed)
        assertTrue(result.corrections.isNotEmpty())
        assertEquals("띄어쓰기", result.corrections.first().reason)
    }

    @Test
    fun `창 시작 위치를 어절 경계에서 찾는다`() {
        assertEquals(0, CorrectionEngine.windowStart("하나 둘", 3))
        assertEquals(3, CorrectionEngine.windowStart("하나 둘 셋 넷", 3))
        assertEquals(0, CorrectionEngine.windowStart("", 3))
    }
}
