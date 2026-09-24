package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 2026-09-24 실시간 시험지(`tools/localcheck/live/` 의 tsv)에서 걸려 더한 규칙들.
 *
 * 규칙만 켠 엔진으로 본다(사전·언어모델 없이). 고치는 쪽과 **고치면 안 되는 쪽**을 같이 둔다 —
 * 이 규칙들은 뒤에 올 말을 적어 두는 것으로 오교정을 막는데, 그 목록이 줄거나 넓어지면
 * 아래 "안 건드린다" 쪽이 먼저 깨진다.
 */
class ChatRulesTest {

    private val engine = CorrectionEngine()

    private fun fix(text: String): String = engine.correct(text).text

    private fun assertFixed(expected: String, input: String) = assertEquals(expected, fix(input), "'$input' →")

    private fun assertUntouched(vararg texts: String) {
        for (text in texts) assertEquals(text, fix(text), "고치지 말아야 할 문장을 고쳤다")
    }

    // --- 맞춤법 ---------------------------------------------------------------

    @Test
    fun `표에 더한 낱말`() {
        assertFixed("어차피 늦었어", "어짜피 늦었어")
        assertFixed("요새 너무 바빠", "요세 너무 바빠")
        assertFixed("김치 담갔어", "김치 담궜어")
        assertFixed("문 잠갔어", "문 잠궜어")
        assertFixed("번호 바뀌었어", "번호 바꼈어")
        assertFixed("명예훼손이야", "명예회손이야")
        assertFixed("그러려고 한 게 아니야", "그럴려고 한 게 아니야")
        assertFixed("두근두근 설렌다", "두근두근 설레인다")
    }

    @Test
    fun `ㄹ 받침 뒤 껄 은 걸 — 웃음소리는 그대로`() {
        assertFixed("말할걸", "말할껄")
        assertFixed("그럴 줄 알았으면 말할걸", "그럴 줄 알았으면 말할껄")
        assertUntouched("껄껄 웃었다")
    }

    @Test
    fun `어절 첫머리 않 은 부사 안 — 않다 활용은 그대로`() {
        assertFixed("전화를 안 받네", "전화를 않받네")
        assertFixed("아직 안 끝났어", "아직 않끝났어")
        assertUntouched("그거 별로 좋지 않아", "하지 않았어", "먹지 않고", "괜찮지 않은")
    }

    @Test
    fun `예기 는 얘기 — 예기치 는 그대로`() {
        assertFixed("얘기 좀 하자", "예기 좀 하자")
        assertFixed("쓸데없는 얘기 그만해", "쓸데없는 예기 그만해")
        assertUntouched("예기치 못한 일")
    }

    @Test
    fun `홀로 선 되 는 돼 — 부피 단위는 그대로`() {
        assertFixed("해도 돼", "해도 되")
        assertFixed("들어가도 돼?", "들어가도 되?")
        assertUntouched("쌀 한 되 주세요", "그렇게 되면 좋겠다")
    }

    @Test
    fun `안 될 · 안 됩니다 — 딱하다는 뜻의 안됐다 는 그대로`() {
        assertFixed("그건 안 될 것 같아", "그건 안될 것 같아")
        assertUntouched("그거 참 안됐다")
    }

    @Test
    fun `길 바래 는 바라 — 빛바램은 그대로`() {
        assertFixed("잘되길 바라", "잘되길 바래")
        assertFixed("합격하길 바라요", "합격하길 바래요")
        assertUntouched("색이 많이 바랬네")
    }

    @Test
    fun `어떡해 뒤에 동사가 오면 어떻게`() {
        assertFixed("이거 어떻게 하는 거야", "이거 어떻해 하는 거야")
        assertFixed("이제 어떡해", "이제 어떻해")
    }

    @Test
    fun `결재 결제 낳다 낫다 이따가 — 문맥이 분명할 때만`() {
        assertFixed("카드로 결제했어", "카드로 결재했어")
        assertFixed("감기 다 나았어?", "감기 다 낳았어?")
        assertFixed("상처가 금방 나았어", "상처가 금방 낳았어")
        assertFixed("이따가 보자", "있다가 보자")
        assertUntouched(
            "결재 서류 올렸어요", "아이를 낳았어요", "좀 있다가 나갈게", "집에 있다가 나왔어"
        )
    }

    @Test
    fun `어이 가려고 예요`() {
        assertFixed("다들 어이가 없대", "다들 어의가 없대")
        assertFixed("머리 자르러 가려고", "머리 자르러 갈려고")
        assertFixed("여기가 어디예요?", "여기가 어디에요?")
        assertUntouched("타이어 갈려고", "학교에요")
    }

    // --- 띄어쓰기: 붙여 친 부사·관형사·대명사 --------------------------------------

    @Test
    fun `이번 주 다음 달 — 지난주 다음번은 한 낱말`() {
        assertFixed("이번 주말에", "이번주말에")
        assertFixed("다음 달에", "다음달에")
        assertUntouched("지난주에 만났잖아", "다음번엔 내가 살게")
    }

    @Test
    fun `뭐 어디 왜 무슨 잘 같이 빨리`() {
        assertFixed("뭐 먹을까", "뭐먹을까")
        assertFixed("어디 있어?", "어디있어?")
        assertFixed("왜 그래", "왜그래")
        assertFixed("무슨 일 있어?", "무슨일 있어?")
        assertFixed("잘 자", "잘자")
        assertFixed("같이 가자", "같이가자")
        assertFixed("빨리 와", "빨리와")
        assertUntouched("뭐야", "뭐라고", "왜냐하면", "왜곡", "잘못 보냈어", "잘했어", "빨리빨리 해")
    }

    @Test
    fun `시간 되면 밥 먹자 비 온다 신경 쓰지 마`() {
        assertFixed("시간 되면", "시간되면")
        assertFixed("밥 먹었어?", "밥먹었어?")
        assertFixed("비 온다", "비온다")
        assertFixed("신경 쓰지 마", "신경쓰지마")
        assertUntouched("비와 바람")
    }

    @Test
    fun `그 이 저 우리 + 명사 — 한 낱말과 딴 낱말은 그대로`() {
        assertFixed("그 사람 누구야", "그사람 누구야")
        assertFixed("저 사람들 누구야", "저사람들 누구야")
        assertFixed("이 노래 좋다", "이노래 좋다")
        assertFixed("우리 집에", "우리집에")
        assertFixed("우리 엄마가", "우리엄마가")
        assertUntouched(
            "그거", "그때 기억나?", "그분이 누구셔?", "이집트 여행", "우리나라 최고", "우리말"
        )
    }

    @Test
    fun `더 이상 한 번 더 조금만 더 걱정 마`() {
        assertFixed("더 이상 못 참아", "더이상 못참아")
        assertFixed("한 번 더", "한번더")
        assertFixed("조금만 더", "조금만더")
        assertFixed("걱정 마세요", "걱정마세요")
    }

    @Test
    fun `러 가다 게 되다 야 되다`() {
        assertFixed("놀러 와", "놀러와")
        assertFixed("영화 보러 갈래?", "영화 보러갈래?")
        assertFixed("이렇게 되면", "이렇게되면")
        assertFixed("해야 돼", "해야돼")
        assertUntouched("달러가 많이 올랐네", "그림 컬러가 예쁘다", "해야겠다")
    }

    // --- 띄어쓰기: 의존명사 ---------------------------------------------------------

    @Test
    fun `거 게 중 적 척`() {
        assertFixed("그런 거 아니야", "그런거 아니야")
        assertFixed("늦게 온 거지?", "늦게 온거지?")
        assertFixed("그럴 거면", "그럴거면")
        assertFixed("할 게 뭐 있어", "할게 뭐 있어")
        assertFixed("먹을 게 없네", "먹을게 없네")
        assertFixed("밥 먹는 중", "밥먹는중")
        assertFixed("본 적 있어", "본적 있어")
        assertFixed("아는 척하지 마", "아는척 하지마")
        assertUntouched("근거 있는 말이야?", "선거 끝났어", "설거지 내가 할게", "내가 할게 좀 기다려")
    }

    @Test
    fun `만하다 — 띄어 와도 받고 조사 만 은 그대로`() {
        assertFixed("먹을 만해", "먹을만 해")
        assertFixed("볼 만한 영화", "볼만한 영화")
        assertUntouched("잘만 하면 돼", "일만 하고 왔어")
    }

    @Test
    fun `안 뒤에 더한 음절`() {
        assertFixed("안 그래도", "안그래도")
        assertFixed("안 좋은 일", "안좋은 일")
        assertFixed("안 할래", "안할래")
        assertUntouched("안녕", "안경", "안하무인")
    }

    @Test
    fun `수 + 단위 — 그릇`() {
        assertFixed("한 그릇 주세요", "한그릇 주세요")
        assertFixed("세 그릇", "세그릇")
    }

    // --- 띄어쓰기: 명사 + 하다 붙이기 ------------------------------------------------

    @Test
    fun `공부 하는 은 공부하는`() {
        assertFixed("나 공부하는 중이야", "나 공부 하는 중이야")
        assertFixed("오늘 운동했어", "오늘 운동 했어")
        assertFixed("걱정하지 마", "걱정 하지마")
        assertFixed("청소해야 돼", "청소 해야돼")
        assertFixed("감사합니다", "감사 합니다")
    }

    @Test
    fun `하다 로 안 읽히는 뒤말은 붙이지 않는다`() {
        assertUntouched(
            "생각 하나 해 봤어", "여행 해외로 가자", "생각 한 번 해 봐", "공부 하루 종일",
            "말 한마디"
        )
    }
}
