package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 소리가 같거나 비슷해 헷갈리는 말([ConfusableRules]). **고치는 것보다 안 건드리는 것이 더 중요하다** — 그래서 맞는 글을 지키는
 * 시험이 고치는 시험만큼 길다.
 */
class ConfusableRulesTest {

    private val engine = CorrectionEngine()

    private fun fix(text: String): String = engine.correct(text).text

    private fun assertUntouched(text: String) {
        val result = engine.correct(text)
        assertEquals(text, result.text, "고치지 말아야 할 문장을 고쳤다: ${result.corrections}")
    }

    @Test
    fun `이게 낳을까 저게 낳을까 — 비교하는 자리의 낳은 낫이다`() {
        assertEquals("이게 나을까 저게 나을까", fix("이게 낳을까 저게 낳을까"))
        assertEquals("뭐가 더 나을까", fix("뭐가 더 낳을까"))
        assertEquals("뭐가 나을까", fix("뭐가 낳을까"))
        assertEquals("차라리 그게 낫겠다", fix("차라리 그게 낳겠다"))
        assertEquals("이건 저것보다 낫다", fix("이건 저것보다 낳다"))
        assertEquals("그 사람보다는 내가 낫지", fix("그 사람보다는 내가 낳지"))
        assertEquals("커피보다 차가 나을까", fix("커피보다 차가 낳을까"))
        assertEquals("혼자 하는 게 나아", fix("혼자 하는 게 낳아"))
        assertEquals("훨씬 나은 거 같아", fix("훨씬 낳은 거 같아"))
        assertEquals("어제보다 나아졌어", fix("어제보다 낳아졌어"))
        assertEquals("상황이 나아지면 좋겠다", fix("상황이 낳아지면 좋겠다"))
    }

    @Test
    fun `병이 낫는 자리 — 아픈 곳 뒤의 낳은 낫이다`() {
        assertEquals("감기가 빨리 나았으면 좋겠다", fix("감기가 빨리 낳았으면 좋겠다"))
        assertEquals("몸이 좀 나았어?", fix("몸이 좀 낳았어?"))
        assertEquals("허리가 나으면 운동할래", fix("허리가 낳으면 운동할래"))
        assertEquals("열이 다 나았어", fix("열이 다 낳았어"))
    }

    @Test
    fun `아이와 결과를 낳는 자리 — 목적어 뒤의 나는 낳이다`() {
        assertEquals("아이를 낳을까 고민했어", fix("아이를 나을까 고민했어"))
        assertEquals("아기를 낳았어", fix("아기를 나았어"))
        assertEquals("고양이가 새끼를 낳았어", fix("고양이가 새끼를 나았어"))
        assertEquals("닭이 알을 낳았다", fix("닭이 알을 나았다"))
        assertEquals("쌍둥이를 낳았대", fix("쌍둥이를 나았대"))
        assertEquals("아들을 낳으면 좋겠다", fix("아들을 나으면 좋겠다"))
        assertEquals("큰 비극을 낳았다", fix("큰 비극을 나았다"))
        assertEquals("좋은 결과를 낳을 거야", fix("좋은 결과를 나을 거야"))
        assertEquals("오해를 낳을 수 있어", fix("오해를 나을 수 있어"))
    }

    @Test
    fun `낫과 낳이 둘 다 되는 자리는 건드리지 않는다`() {
        // 이것이 빚어낸 결과(낳다) ↔ 더 좋은 결과(낫다)
        assertUntouched("이게 낳은 결과야")
        assertUntouched("그게 낳은 비극이지")
        // 목적어를 줄인 출산
        assertUntouched("몸이 안 좋아서 일찍 낳았어")
        assertUntouched("허리가 아파서 일찍 낳았어")
        assertUntouched("하나 더 낳을까")
        assertUntouched("아이를 더 낳을까 고민이야")
        assertUntouched("고양이가 새끼를 더 낳았어")
        assertUntouched("병이 있는데도 아이를 낳았어")
        // 아이가 나았다(병) — '아이도' 를 '낳았어' 로 읽으면 안 된다
        assertUntouched("아이도 나았어")
        assertUntouched("애도 나았어")
        assertUntouched("아이 나았어")
        // 좋아 보이다
        assertUntouched("결과를 더 나아 보이게 만들었다")
        assertUntouched("문제를 나아 보이게 하는 방법")
    }

    @Test
    fun `맞는 낫과 낳은 그대로 둔다`() {
        for (s in listOf(
            "이게 나을까 저게 나을까", "뭐가 더 나을까", "감기가 빨리 나았으면 좋겠다", "그게 더 낫다", "이게 더 나은 것 같아",
            "기분이 나아졌어", "아이를 낳을까 고민했어", "아기를 낳았어", "알을 낳았다", "좋은 결과를 낳을 거야", "쌍둥이를 낳았대",
            "나아가야 해", "낫을 갈았어", "낫으로 풀을 베었다", "병이 나았어", "빨리 나으세요", "그 사람이 낳은 아이야",
        )) assertUntouched(s)
    }

    @Test
    fun `낫 에 ㅅ 을 남긴 없는 꼴은 고친다`() {
        assertEquals("병이 나았어", fix("병이 낫았어"))
        assertEquals("빨리 나아", fix("빨리 낫아"))
        assertEquals("나을까", fix("낫을까"))
        // 연장 낫의 조사는 건드리지 않는다
        assertUntouched("낫을 갈았다")
        assertUntouched("낫은 날카롭다")
    }

    @Test
    fun `로서 는 자격이고 로써 는 수단이다`() {
        assertEquals("학생으로서 해야 할 일", fix("학생으로써 해야 할 일"))
        assertEquals("부모로서 당연하지", fix("부모로써 당연하지"))
        assertEquals("칼로써 잘랐다", fix("칼로서 잘랐다"))
        assertEquals("말로써 설명한다", fix("말로서 설명한다"))
        assertEquals("이 방법으로써 해결했다", fix("이 방법으로서 해결했다"))
        // 앞이 한글인 낱말 속의 '형' 은 사람이 아니다
        assertUntouched("모형으로써 설명했다")
        // 자격으로도 수단으로도 쓰는 말은 건드리지 않는다
        assertUntouched("돈으로서의 가치는 없어")
        assertUntouched("도구로서의 역할을 한다")
        assertUntouched("이로써 모든 일이 끝났다")
    }

    @Test
    fun `맞추다 와 맞히다 를 목적어로 가린다`() {
        assertEquals("문제를 맞혔다", fix("문제를 맞췄다"))
        assertEquals("과녁을 맞혔다", fix("과녁을 맞췄다"))
        assertEquals("퍼즐을 맞췄다", fix("퍼즐을 맞혔다"))
        assertEquals("시간을 맞췄다", fix("시간을 맞혔다"))
        // 정답을 서로 맞춰 본다(맞추다)와 정답을 맞혔다(맞히다)가 다 된다
        assertUntouched("정답을 맞췄어")
        assertUntouched("답을 맞춰 보자")
        assertUntouched("문제를 맞춰 봐")
        assertUntouched("퍼즐을 맞췄다")
        assertUntouched("문제를 맞혔다")
    }

    @Test
    fun `부치다 와 붙이다 를 목적어로 가린다`() {
        assertEquals("편지를 부쳤어", fix("편지를 붙였어"))
        assertEquals("택배를 부치다", fix("택배를 붙이다"))
        assertEquals("우표를 붙였어", fix("우표를 부쳤어"))
        assertEquals("스티커를 붙이다", fix("스티커를 부치다"))
        assertUntouched("전을 부쳤다")
        assertUntouched("벽에 편지를 붙였다")
        assertUntouched("우표를 붙였다")
        assertUntouched("사진을 부쳤어")
    }

    @Test
    fun `가리키다 와 가르치다`() {
        assertEquals("손가락으로 가리켰다", fix("손가락으로 가르켰다"))
        assertEquals("영어를 가르친다", fix("영어를 가르킨다"))
        assertEquals("수학을 가르쳤다", fix("수학을 가르켰다"))
        assertUntouched("손가락으로 가리켰다")
        assertUntouched("영어를 가르친다")
        // 사람을 가리켰는지 가르쳤는지는 문맥 없이 못 가린다
        assertUntouched("학생을 가리켰다")
    }

    @Test
    fun `틀리다 와 다르다 — 원래 정답이 없는 것이 주어면 다르다`() {
        assertEquals("우리는 성격이 달라", fix("우리는 성격이 틀려"))
        assertEquals("입맛이 달라요", fix("입맛이 틀려요"))
        assertEquals("서로 달라", fix("서로 틀려"))
        assertEquals("목소리가 다른 사람", fix("목소리가 틀린 사람"))
        // 맞지 않다는 뜻의 틀리다
        assertUntouched("답이 틀려")
        assertUntouched("내 생각이 틀렸어")
        assertUntouched("스펠링이 틀렸어")
        assertUntouched("계산이 틀렸다")
        assertUntouched("음정이 틀려")
    }

    @Test
    fun `늘리다 와 늘이다`() {
        assertEquals("체중을 늘렸다", fix("체중을 늘였다"))
        assertEquals("인원을 늘려", fix("인원을 늘여"))
        assertEquals("고무줄을 늘였다", fix("고무줄을 늘렸다"))
        assertUntouched("엿가락을 늘였다")
        assertUntouched("인원을 늘렸다")
    }

    @Test
    fun `한참 은 오랫동안이고 한창 은 가장 활발한 때다`() {
        assertEquals("한창 바쁜 때야", fix("한참 바쁜 때야"))
        assertEquals("공사가 한창이다", fix("공사가 한참이다"))
        assertEquals("지금 한참 기다렸어", fix("지금 한창 기다렸어"))
        assertUntouched("한참 기다렸어")
        assertUntouched("한창 바쁜 때야")
        assertUntouched("한참 뒤에 도착했다")
    }

    @Test
    fun `지난 일을 떠올리는 던 을 든 으로 쓴 것은 앞에 때를 나타내는 말이 있을 때만 고친다`() {
        assertEquals("어제 먹던 음식", fix("어제 먹든 음식"))
        assertEquals("어릴 때 살던 곳", fix("어릴 때 살든 곳"))
        // 고르는 말 '-든' 은 그대로
        assertUntouched("어디를 가든 상관없어")
        assertUntouched("뭘 하든 집에 가자")
        assertUntouched("배고플 때 먹든 음식은 다 맛있어")
    }
}
