package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 기기 안 큰 모델(제마)의 교정문 거름([LlmCorrection])과 믿을 만한 고침만 받아들이기([LlmMerge]). */
class LlmCorrectionTest {

    @Test
    fun `교정문의 군더더기를 떼고 원문에 없던 부호는 뗀다`() {
        assertEquals("그렇게 하면 안 돼요", LlmCorrection.clean("그렇게 하면 안 돼요?", "그렇게 하면 안되요"))
        assertEquals("나 어제 밥 먹었어", LlmCorrection.clean("교정: \"나 어제 밥 먹었어\"", "나 어제 밥먹엇어"))
        // 지시문의 보기 모양을 흉내 낸 답
        assertEquals("나 어제 밥 먹었어", LlmCorrection.clean("나 어제 밥먹엇어 -> 나 어제 밥 먹었어", "나 어제 밥먹엇어"))
        // 고친 문장 뒤에 설명을 붙인 답은 첫 줄만
        assertEquals("내일 몇 시에 만날까", LlmCorrection.clean("내일 몇 시에 만날까\n띄어쓰기를 고쳤습니다.", "내일 몇시에 만날까"))
    }

    @Test
    fun `붙여 쓴 숫자와 단위는 붙인 채로 둔다`() {
        assertEquals("사과 3개 샀는데 5천원이었어", LlmCorrection.clean("사과 3 개 샀는데 5천 원이었어", "사과 3개 샀는데 5천원이였어"))
    }

    @Test
    fun `교정이 아닌 답은 버린다`() {
        assertNull(LlmCorrection.clean("저는 구글에서 만든 언어모델입니다.", "너 누구야"))
        assertNull(LlmCorrection.clean("대한민국의 수도는 서울입니다", "대한민국 수도가 어디야"))
        assertNull(LlmCorrection.clean("오늘은 날씨가 정말 좋아서 공원에 가서 산책도 하고 친구도 만났어요", "날씨 좋다"))
        assertNull(LlmCorrection.clean("   ", "날씨 좋다"))
    }

    @Test
    fun `조각은 원문 안 자리로 자르고 사이의 공백과 줄바꿈은 남긴다`() {
        val text = "안녕하세요. 오늘 날씨 좋네요!  내일 봐요\n\n둘째 줄 ㅋㅋ"
        assertEquals(
            listOf("안녕하세요.", "오늘 날씨 좋네요!", "내일 봐요", "둘째 줄 ㅋㅋ"),
            LlmCorrection.segments(text).map { text.substring(it) }
        )
        val long = "가".repeat(10) + " " + "나".repeat(10) + " " + "다".repeat(10)
        assertEquals(listOf("가".repeat(10), "나".repeat(10), "다".repeat(10)), LlmCorrection.segments(long, 15).map { long.substring(it) })
    }

    /** 말뭉치 빈도(ln). '지그' 는 드문 말, 나머지는 흔한 말. 표에 없는 말은 말뭉치가 모르는 말(오타일 가능성이 큰 말). */
    private val frequency = mapOf(
        "따뜻할" to 6f, "거" to 12f, "같아요" to 9f, "썸" to 5f, "타는" to 8f, "중" to 10f, "안되요" to 5f, "짐" to 6f,
        "싸세요" to 4f, "전화" to 9f, "또" to 10f, "안" to 11f, "받네" to 5f, "그렇게" to 10f, "하면" to 10f, "지그" to 1.5f, "어디쯤" to 5f, "왔어" to 9f,
        "따듯한" to 5f, "곳으로" to 7f, "아는" to 10f, "만큼" to 9f, "보이는" to 8f, "바람이" to 7f, "많이" to 10f, "부내" to 2f
    )

    private fun merge(original: String, candidate: String) = LlmMerge.merge(original, candidate) { frequency[it] }

    @Test
    fun `멀쩡한 말을 딴 말이나 딴 말투로 바꾼 것은 되돌린다`() {
        assertEquals("따뜻할 거 같아요.", merge("따뜻할 거 같아요.", "따뜻할 것 같아요."))
        assertEquals("썸 타는 중", merge("썸 타는 중", "섬 타는 중"))
        assertEquals("짐 싸세요", merge("짐 싸세요", "짐 사세요"))
        // 되돌린 어절 둘레는 원문의 띄어쓰기를 쓴다('전화도' 로 붙지 않는다).
        assertEquals("전화 또 안 받네", merge("전화 또 안 받네", "전화도 안 받네"))
        // 둘 다 표준어인 말을 된소리로(큰 시험지 2026-10-11).
        assertEquals("따듯한 곳으로", merge("따듯한 곳으로", "따뜻한 곳으로"))
    }

    @Test
    fun `오타 방향이 아닌 고침과 부호 떼기는 말뭉치가 모르는 말이어도 되돌린다`() {
        // 말투 바꾸기(셔 → 세), 음절 빼기(버이 → 거), 딴 말로 바꾸기(차고 → 취소), 문장부호 떼기.
        assertEquals("진정하셔요.", merge("진정하셔요.", "진정하세요."))
        assertEquals("아는 만큼 보이는 버이야", merge("아는 만큼 보이는 버이야", "아는 만큼 보이는 거야"))
        assertEquals("일정이 변경되었으니 차고해 주세요", merge("일정이 변경되었으니 차고해 주세요", "일정이 변경되었으니 취소해 주세요"))
        assertEquals("아른거리나요.", merge("아른거리나요.", "아른거리나요"))
    }

    @Test
    fun `오타와 띄어쓰기 고침은 받아들인다`() {
        // 말뭉치가 아는 흔한 오타도 소리 같은 모음 하나면 받아들인다(되요 → 돼요, 부내 → 부네).
        assertEquals("그렇게 하면 안 돼요", merge("그렇게 하면 안되요", "그렇게 하면 안 돼요"))
        assertEquals("바람이 많이 부네", merge("바람이 많이 부내", "바람이 많이 부네"))
        // 말뭉치가 모르는 말(오타)은 오타 방향 고침을 받아들인다(받침 마저 채우기·빈 받침 채우기).
        assertEquals("나 어제 밥 먹었어", merge("나 어제 밥먹엇어", "나 어제 밥 먹었어"))
        assertEquals("사람이 너무 많아서", merge("사람이 너무 마아서", "사람이 너무 많아서"))
        assertEquals("방금 도착했어", merge("방금 도차했어", "방금 도착했어"))
        // 띄어쓰기만 다르면 그대로.
        assertEquals("내일 몇 시에", merge("내일 몇시에", "내일 몇 시에"))
        // 드문 말에 빠진 받침을 채운 것(받침 빠짐 오타)은 받아들인다. 흔한 말('거 → 것')은 위에서처럼 되돌린다.
        assertEquals("지금 어디쯤 왔어", merge("지그 어디쯤 왔어", "지금 어디쯤 왔어"))
        // 원문의 두 칸 공백은 그대로 둔다.
        assertEquals("밥  먹었어", merge("밥  먹엇어", "밥 먹었어"))
        // 'ㅋㅋ' 옆 띄어쓰기는 채팅 말투라 사용자가 쓴 대로 둔다.
        assertEquals("ㅋㅋ했어", merge("ㅋㅋ햇어", "ㅋㅋ 했어"))
    }

    @Test
    fun `띄어도 붙여도 맞는 보조용언은 사용자가 쓴 대로 둔다`() {
        assertEquals("생각해보세요.", merge("생각해보세요.", "생각해 보세요."))
        assertEquals("연락해 볼까", merge("연락해 볼까", "연락해볼까"))
        assertEquals("소개시켜드릴게요", merge("소개시켜드릴게요", "소개시켜 드릴게요"))
        // 그 둘레의 다른 고침은 받아들인다.
        assertEquals("어떻게 된 건지 설명해줘", merge("어떻게 된 건지 설며해줘", "어떻게 된 건지 설명해 줘"))
        // '-ㄴ가/-나 보다', '-다 보면' 은 띄는 것만 맞다.
        assertEquals("많이 바쁜가 봐요", merge("많이 바쁜가봐요", "많이 바쁜가 봐요"))
        assertEquals("확신이 없나 봐요", merge("확신이 없나봐요", "확신이 없나 봐요"))
        assertEquals("하다 보면 늘어요", merge("하다보면 늘어요", "하다 보면 늘어요"))
        // '-아하다·-어지다' 는 늘 붙인다 — 붙인 것은 받고, 띄운 것은 안 받는다.
        assertEquals("내가 좋아하는 노래", merge("내가 좋아 하는 노래", "내가 좋아하는 노래"))
        assertEquals("내가 좋아하는 노래", merge("내가 좋아하는 노래", "내가 좋아 하는 노래"))
    }

    @Test
    fun `오타 방향은 한 음절 한 자모만 — 빈 받침 채우기는 따로`() {
        kotlin.test.assertTrue(LlmMerge.typoDirection("있을가", "있을까"))
        kotlin.test.assertTrue(LlmMerge.typoDirection("괜찬아", "괜찮아"))
        kotlin.test.assertTrue(LlmMerge.typoDirection("같은대", "같은데"))
        kotlin.test.assertTrue(!LlmMerge.typoDirection("싸세요", "사세요"))
        kotlin.test.assertTrue(!LlmMerge.typoDirection("앓아", "알아"))
        kotlin.test.assertTrue(!LlmMerge.typoDirection("떼", "때"))
        kotlin.test.assertTrue(!LlmMerge.typoDirection("거", "것"))
    }
}
