package com.spellkeyboard.core.lm

import com.spellkeyboard.core.TestCache
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextCorrectorTest {

    private fun fix(text: String, before: String? = LanguageModel.BOS): String =
        corrector.correct(text, before) ?: text

    private fun assertFixed(expected: String, input: String, before: String? = LanguageModel.BOS) {
        assertEquals(expected, fix(input, before), "'$input' →")
    }

    private fun assertUntouched(text: String, before: String? = LanguageModel.BOS) {
        assertNull(corrector.correct(text, before), "'$text' 를 '${fix(text, before)}' 로 고쳤다")
    }

    // --- 사용자가 실기기에서 잡아낸 세 문장 -----------------------------------

    @Test
    fun `있는대요 를 있는데요 로`() {
        assertFixed("여기 있는데요", "여기 있는대요")
    }

    @Test
    fun `맞는 는대 는 는데 로 바꾸지 않는다 — 전해 들은 말`() {
        // '밥 먹는대'(먹는다고 한다)를 '먹는데'로 바꿨다(2026-09-24).
        assertUntouched("밥 먹는대")
    }

    @Test
    fun `잘못 떨어진 어절을 합치면서 오타도 고친다`() {
        assertFixed("뭐 하세요", "뭐 하 새요")
    }

    @Test
    fun `띄어 쓰면 멀쩡한 말은 글자를 바꾸지 않는다 — 먹은거지 는 먹은 건지 가 아니다`() {
        // '먹은거지'(먹은 것이지)를 한 낱말로 모르니 받침 하나 더한 '먹은건지'(먹은 것인지)로 바꿨다 — 뜻이 바뀐다(2026-10-11).
        assertFixed("나 아까 밥 먹은 거지", "나 아까 밥 먹은거지")
        assertFixed("밥 먹은 거지", "밥 먹은거지")
        // 진짜 '건지' 는 글자를 그대로 둔다(띄어쓰기는 고쳐도 된다).
        val out = fix("뭘 먹은건지 모르겠어")
        assertEquals("뭘먹은건지모르겠어", out.replace(" ", ""), out)
    }

    // --- 멀쩡한 말은 딴 말로 안 바꾼다 (큰 시험지 2026-10-11) -------------------------------------

    @Test
    fun `말뭉치가 아는 멀쩡한 말은 딴 말로 바꾸지 않는다`() {
        // 큰 시험지(챗봇 답 6천 문장)에서 디코더가 글자를 바꾼 것을 하나씩 판정해 보니 틀린 것이 거의 이 꼴이었다.
        // 예사소리로 바꾸기(깨 → 개, 싸 → 사, 썸 → 섬), 겹받침 줄이기(앓아 → 알아), 소리 같은 받침 바꾸기(잊 → 있).
        assertUntouched("깨 볶는 소리가 들리네요")
        assertUntouched("짐 빼놓지 말고 싸세요")
        assertUntouched("도시락 싸는 거 귀찮다")
        assertUntouched("썸의 기준이 뭐야")
        assertUntouched("살 찔 거 같아")
        assertUntouched("앓아 누웠어")
        assertUntouched("나 좀 쩌는 듯")
        val forget = fix("빨리 잊는게 상책이에요")
        assertEquals("빨리잊는게상책이에요", forget.replace(" ", ""), forget)
    }

    @Test
    fun `멀쩡한 말이어도 사람이 실제로 내는 오타 방향은 고친다`() {
        // 쉬프트를 놓친 것(햇 → 했, 가 → 까, 잇 → 있)과 겹받침 한쪽을 빠뜨린 것(괜찬 → 괜찮)은 말뭉치가 그 꼴을 알아도 고친다.
        assertFixed("했는데요", "햇는대요")
        assertFixed("다시 만날 수 있을까", "다시 만날 수 있을가")
        assertFixed("한번쯤 아픈 것도 괜찮아", "한번쯤 아픈 것도 괜찬아")
    }

    @Test
    fun `아버지 가방 문장은 두 갈래 중 하나`() {
        // 문법적으로는 둘 다 맞는 문장이다. 어느 쪽이든 되고, 다른 걸로 망가뜨리지만 않으면 된다.
        val out = fix("아버지 가방에 들어가신다")
        assertTrue(out == "아버지가 방에 들어가신다" || out == "아버지 가방에 들어가신다", out)
    }

    // --- 어절 합치기 ------------------------------------------------------------

    @Test
    fun `어미 앞 ㅅ 은 ㅆ — 낫다 잇다 햇살은 그대로`() {
        // 폰에서 쉬프트를 놓쳐 나는 제일 흔한 오타. 원문도 그럴듯하게 분석돼서 문턱을 못 넘었다.
        assertFixed("나 지금 출발했어", "나 지금 출발햇어")
        assertFixed("커피 네 잔이나 마셨어", "커피 네 잔이나 마셧어")
        assertUntouched("이게 더 낫네")
        assertUntouched("병이 빨리 낫는 중이야")
        assertUntouched("실을 잇는 중")
        assertUntouched("햇살이 좋다")
    }

    @Test
    fun `채팅 장식이 붙은 어절도 고친다`() {
        // 뒤에 부호만 허용해서 'ㅋㅋ·ㅠㅠ·이모지'가 붙은 어절을 통째로 건너뛰었다(2026-09-24).
        assertFixed("어제 늦게 잤어ㅋㅋ", "어제 늦게 잣어ㅋㅋ")
        assertFixed("오늘 했어😂", "오늘 햇어😂")
        assertFixed("ㅋㅋ했어", "ㅋㅋ햇어")
        assertFixed("(나도 했어)", "(나도 햇어)")
        assertUntouched("진짜 웃기다ㅋㅋㅋ")
        assertUntouched("보고 싶어♡")
    }

    @Test
    fun `천지인 오타는 천지인일 때만 고친다`() {
        // 같은 키를 한 번 덜·더 누른 자음(ㄷ↔ㅌ, ㄱ↔ㅋ)과 ㆍ를 반대쪽에 찍은 모음(ㅏ↔ㅓ).
        assertEquals("카페에서 기다리고 있을게", cheonjiin.correct("카페에서 기타리고 있을게", LanguageModel.BOS))
        // '양화 → 영화'(ㅑ↔ㅕ) 예는 바꿨다 — '양화' 는 사전에 오른 명사라 이제 이름·드문 낱말로 보고 비싸게만 고친다(2026-10-13).
        // 같은 '·' 자리 오타(ㅗ↔ㅜ)를 사전에 없는 꼴로 본다. 두벌식에서는 손대지 않는다(아래).
        assertEquals("그 영화 너무 좋아", cheonjiin.correct("그 영화 너무 줗아", LanguageModel.BOS))
        assertEquals("조금만 기다려줘", cheonjiin.correct("조금만 기더려줘", LanguageModel.BOS))
        // 두벌식에서는 드문 오타라 손대지 않는다.
        assertUntouched("카페에서 기타리고 있을게")
        assertUntouched("그 영화 너무 줗아")
        // 조사 '은↔을'은 한 키 차이지만 문맥으로 못 가려서 안 바꾼다.
        assertNull(cheonjiin.correct("그 선택사항들은 지루하거나 비용이 많이 든다", LanguageModel.BOS))
    }

    @Test
    fun `띄어 친 가 와 는 조사로 붙이지 않는다`() {
        // '나 학교 가' 가 '나 학교가' 가 되고 '친구 와' 가 '친구와' 가 됐다(2026-09-24).
        // 홀로 선 '가·와' 는 '가다·오다' 가 훨씬 흔하다.
        assertUntouched("나 학교 가")
        assertUntouched("나 먼저 가")
        assertUntouched("친구 와")
        assertUntouched("동생 군대 가")
    }

    @Test
    fun `떨어진 어절을 합친다`() {
        assertFixed("안녕하세요", "안녕 하세요")
        assertFixed("감사합니다", "감사 합니다")
        assertFixed("밥 먹었어요", "밥 먹었 어요")
        assertFixed("사랑합니다", "사랑 합니다")
    }

    // --- 어절 나누기 ------------------------------------------------------------

    @Test
    fun `붙여 쓴 것을 나눈다`() {
        // '할수있다' 는 규칙(SpacingRules)의 몫이라 여기서는 보지 않는다. 엔진 테스트가 본다.
        assertFixed("오늘은 날씨가 좋아서 기분이 좋다", "오늘은날씨가좋아서기분이좋다")
        assertFixed("밥 먹었어요", "밥먹었어요")
    }

    // --- 오타 ------------------------------------------------------------------

    @Test
    fun `사전에 없는 오타를 고친다`() {
        assertFixed("안녕하세요", "안녕하새요")
        assertFixed("했는데요", "햇는대요")
        // '됬어요' 도 말뭉치에 흔한 오타라 언어모델은 둘 중 하나를 고른다. 어느 쪽이든
        // 엔진의 어절 규칙('됬'→'됐')이 마무리한다.
        assertTrue(fix("됫어요") in setOf("됐어요", "됬어요"), fix("됫어요"))
        // '조아요' 는 말뭉치에 드물게 있는 말(ln 2.4)이라 디코더는 이제 안 바꾼다 — 어절 규칙이 '좋아요' 로 고친다(ChatRulesTest).
        // 말뭉치에 없는 받침 빠진 오타는 흔한 말로 되살린다(2026-10-13).
        assertFixed("지금 통화 괜찮아", "지금 통화 괘찮아")
        assertFixed("오늘 저녁에 뭐 먹을까", "오늘 저녁애 뭐 먹을까")
    }

    // --- 오교정 방지 --------------------------------------------------------------

    @Test
    fun `멀쩡한 문장은 그대로 둔다`() {
        for (text in listOf(
            "나는 학교에 간다", "오늘 날씨가 정말 좋네요", "할 수 있다", "먹을 것 같다",
            "네가 좋아", "내가 좋아", "여기 와 봐", "집 밖에 나갔다", "돼지고기를 먹었다",
            "감사합니다", "안녕하세요", "잘 지내", "왜 그래", "밥 먹자",
            "카카오톡 열어 봐", "인스타그램 스토리 올렸어", "대박 사건", "친구야 놀자",
            "배고파 죽겠다", "삼성 갤럭시 폰", "김철수가 왔다", "치킨 시켜 먹자",
            "그 사람 진짜 웃겨", "우리 언제 만나", "내일 몇 시에 볼까", "지금 뭐 해",
            "이거 진짜 맛있다", "저는 학생입니다", "이것은 책상입니다", "선생님께 드렸다",
            "개 키우고 싶다", "게 먹으러 가자", "새로운 게임 나왔어", "세 명이 왔다",
            "매일 아침 운동해요", "메일 보냈어요", "되게 예쁘다", "돼지 키운다"
        )) {
            assertUntouched(text)
        }
    }

    @Test
    fun `한글이 아닌 것은 건드리지 않고 그 자리에서 문맥을 끊는다`() {
        assertUntouched("hello world")
        assertUntouched("3시에 만나요")
        assertUntouched("ㅋㅋㅋ 진짜")
        assertFixed("3시에 있는데요", "3시에 있는대요")
    }

    @Test
    fun `문장 부호가 붙은 어절은 부호를 지킨다`() {
        assertFixed("밥 먹었어요. 뭐 하세요", "밥 먹었어요. 뭐 하 새요")
        assertFixed("여기 있는데요.", "여기 있는대요.")
    }

    @Test
    fun `실시간 입력을 따라갈 만큼 빠르다`() {
        val samples = listOf(
            "여기 있는대요", "뭐 하 새요", "아버지 가방에 들어가신다", "오늘은날씨가좋아서기분이좋다",
            "내가 그 사람한테 몇 번이나", "행복해지는 게 아니라고", "어처구니가 없더라고"
        )
        repeat(5) { samples.forEach { corrector.correct(it, LanguageModel.BOS) } }
        val started = System.nanoTime()
        val rounds = 30
        repeat(rounds) { samples.forEach { corrector.correct(it, LanguageModel.BOS) } }
        val perCall = (System.nanoTime() - started) / (rounds.toLong() * samples.size) / 1_000_000.0
        println("창 하나에 평균 %.2f ms".format(perCall))
        assertTrue(perCall < 20.0, "너무 느리다: %.2f ms".format(perCall))
    }

    companion object {
        private val corrector: ContextCorrector by lazy {
            val dir = TestCache.dir
            dir.deleteOnExit()
            ContextCorrector(LanguageModel.open(dir), Spacer(SpacingDictionary.open(dir)))
        }

        private val cheonjiin: ContextCorrector by lazy {
            val dir = TestCache.dir
            ContextCorrector(LanguageModel.open(dir), Spacer(SpacingDictionary.open(dir))).apply { cheonjiin = true }
        }
    }
}
