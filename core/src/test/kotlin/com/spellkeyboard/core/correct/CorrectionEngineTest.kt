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
    fun `의존명사 둥 을 띄운다`() {
        assertEquals("듣는 둥 마는 둥", fix("듣는둥마는둥"))
        assertEquals("자는 둥 마는 둥", fix("자는둥마는둥"))
        assertEquals("갈 둥 말 둥", fix("갈둥말둥"))
        // '하다'가 뒤에 붙는 꼴. 그 밖의 말이 뒤에 붙는 것("...말둥고민했다")은 규칙이
        // 아니라 디코더가 뗀다 — 이 시험은 규칙만 켜 놓고 돌기 때문에 여기서는 안 다룬다.
        assertEquals("먹는 둥 마는 둥 했다", fix("먹는둥마는둥했다"))
        // 이미 띄어져 있으면 그대로. 규칙이 같은 자리를 다시 만나도 결과가 같아야 한다.
        assertEquals("듣는 둥 마는 둥", fix("듣는 둥 마는 둥"))
    }

    @Test
    fun `둥 으로 끝나도 짝이 아니면 건드리지 않는다`() {
        // 이 규칙이 좁은 이유다 — '둥'으로 끝나는 낱말이 잇달아 두 번 나오는 일은
        // 관용구 말고는 없다. 짝을 요구하지 않으면 아래가 다 깨진다.
        assertUntouched("산둥반도에 갔다")
        assertUntouched("안둥그렇다")
        assertUntouched("둥그렇게 앉았다")
    }

    @Test
    fun `관형형 뒤의 의존명사들을 띄운다`() {
        assertEquals("갈 거야", fix("갈거야"))
        assertEquals("할 거예요", fix("할거예요"))
        assertEquals("먹을 게 없어", fix("먹을게없어"))
        assertEquals("어떻게 된 건지", fix("어떻게 된건지"))
        assertEquals("갈 데가 없다", fix("갈데가 없다"))
        assertEquals("아는 만큼 보인다", fix("아는만큼 보인다"))
        assertEquals("갔을 뿐이다", fix("갔을뿐이다"))
        assertEquals("생각한 바가 있다", fix("생각한바가 있다"))
        assertEquals("입은 채로 잤다", fix("입은채로 잤다"))
        assertEquals("아는 척도 안 한다", fix("아는척도 안 한다"))
        assertEquals("그럴 리가 없다", fix("그럴리가 없다"))
        assertEquals("다칠 뻔했다", fix("다칠뻔했다"))
        assertEquals("볼 만한 영화", fix("볼만한 영화"))
        assertEquals("가는 중이야", fix("가는중이야"))
        assertEquals("올 듯 말 듯", fix("올듯말듯"))
        assertEquals("되는 대로 하자", fix("되는대로 하자"))
        assertEquals("온 지 얼마 안 됐다", fix("온지 얼마 안 됐다"))
    }

    @Test
    fun `의존명사처럼 생겼지만 아닌 자리는 건드리지 않는다`() {
        // '-는데'(어미)와 의존명사 '데'는 **조사를 받을 수 있느냐**로 갈린다.
        assertUntouched("노력했는데도 안 됐다")
        assertUntouched("그런데도 계속했다")
        // 조사 '만큼'·'뿐'은 체언에 붙여 쓴다.
        assertUntouched("돈만큼 중요한 것")
        assertUntouched("사람뿐이었다")
        // '-ㄹ게'(어미)는 뒤가 서술어가 아니라 갈리지 않는다.
        assertUntouched("내가 할게요")
        // '리'로 끝나는 용언.
        assertUntouched("빨리 가자")
        assertUntouched("격주로 열리는 행사")
        // '만' 은 조사이기도 하다.
        assertUntouched("일만 하고 살았다")
        // '대로' 도 조사이기도 하다.
        assertUntouched("절대로 안 된다")
        // '건'으로 끝나는 명사.
        assertUntouched("다음 안건지 확인해 주세요")
        // '무한대' 는 한 낱말이다.
        assertUntouched("무한대로 늘어난다")
    }

    @Test
    fun `부정의 못 과 안 을 띄운다`() {
        assertEquals("못 받아", fix("못받아"))
        assertEquals("못 갔어", fix("못갔어"))
        assertEquals("안 가져왔어", fix("안가져왔어"))
        assertEquals("안 먹어", fix("안먹어"))
    }

    @Test
    fun `못 하다 와 안 이 낱말의 일부인 자리는 건드리지 않는다`() {
        // '못하다' 는 한 낱말이다. 활용형을 통째로 빼지 않으면 격식체가 무너진다.
        assertUntouched("이해하지 못했다")
        assertUntouched("알지 못합니다")
        assertUntouched("못된 짓을 했다")
        assertUntouched("연못이 얼었다")
        // '안-' 으로 시작하는 낱말은 아주 많다.
        assertUntouched("안내를 받았다")
        assertUntouched("안경을 잃어버렸다")
        assertUntouched("안녕히 계세요")
    }

    @Test
    fun `부사 좀 을 띄운다`() {
        assertEquals("이것 좀 봐", fix("이것좀 봐"))
        assertEquals("문 좀 닫아", fix("문 좀닫아"))
        assertUntouched("좀도둑이 들었다")
    }

    @Test
    fun `보조용언 고 싶다 와 지 마 를 띄운다`() {
        assertEquals("보고 싶다", fix("보고싶다"))
        assertEquals("걱정하지 마", fix("걱정하지마"))
        assertEquals("가지 마라", fix("가지마라"))
    }

    @Test
    fun `수관형사와 단위 의존명사를 띄운다`() {
        assertEquals("세 개만", fix("세개만"))
        assertEquals("두 번 다시", fix("두번 다시"))
        assertEquals("다섯 시까지", fix("다섯시까지"))
        assertEquals("이십 분 정도", fix("이십분 정도"))
        assertEquals("삼십 분 뒤", fix("삼십분 뒤"))
        assertEquals("백 원짜리", fix("백원짜리"))
        assertEquals("몇 시쯤", fix("몇시쯤"))
        assertEquals("열 명쯤", fix("열명쯤"))
    }

    @Test
    fun `수관형사로 보이지만 낱말 속인 자리는 건드리지 않는다`() {
        // 이것 하나가 없으면 격식체가 통째로 무너진다.
        assertUntouched("확인해 주십시오")
        assertUntouched("말씀하십시오")
        // 수관형사는 어절 첫머리에서만 본다.
        assertUntouched("천장이 높다")
        assertUntouched("백분율을 구했다")
        // 한 낱말로 굳은 것들.
        assertUntouched("두통이 심하다")
        assertUntouched("세계 여행")
        assertUntouched("열대 지방")
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
