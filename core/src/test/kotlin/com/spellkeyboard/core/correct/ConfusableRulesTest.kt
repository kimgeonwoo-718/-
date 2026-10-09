package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 소리가 같거나 비슷해 헷갈리는 말([ConfusableRules]). **고치는 것보다 안 건드리는 것이 더 중요하다** — 그래서 맞는 글을 지키는
 * 시험이 고치는 시험만큼 길다.
 *
 * 낫다/낳다의 문맥 판정은 규칙이 아니라 학습된 모델이 한다 — [ConfusableClassifierTest]. 여기 남은 것은 문맥을 볼 것 없이 없는 꼴인 것뿐이다.
 */
class ConfusableRulesTest {

    private val engine = CorrectionEngine()

    private fun fix(text: String): String = engine.correct(text).text

    private fun assertUntouched(text: String) {
        val result = engine.correct(text)
        assertEquals(text, result.text, "고치지 말아야 할 문장을 고쳤다: ${result.corrections}")
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
