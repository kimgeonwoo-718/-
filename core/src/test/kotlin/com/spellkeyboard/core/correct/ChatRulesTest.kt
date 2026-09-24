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
        assertUntouched("뭐야", "뭐라고", "뭐가 더 나아?", "왜냐하면", "왜곡", "잘못 보냈어", "잘했어", "빨리빨리 해")
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

    // --- 2라운드 (live/round2.tsv) ---------------------------------------------------

    @Test
    fun `년 · 열두 시 · 만에`() {
        assertFixed("몇 년 만에 만났어", "몇년만에 만났어")
        assertFixed("삼 년 동안", "삼년 동안")
        assertFixed("열두 시에", "열두시에")
        assertFixed("열한 시 반에", "열한시 반에")
        assertFixed("얼마 만에", "얼마만에")
        assertUntouched("오랜만에 만났어", "이년 저년")
    }

    @Test
    fun `텐데 테니 수도 지 갔다 오다`() {
        assertFixed("늦을 텐데", "늦을텐데")
        assertFixed("갈 테니까", "갈테니까")
        assertFixed("비가 올 수도 있어", "비가 올수도 있어")
        assertFixed("만난 지 벌써", "만난지 벌써")
        assertFixed("편의점 갔다 왔어", "편의점 갔다왔어")
        assertUntouched("그녀는 테니스를 좋아한다", "수도가 어디야?", "어제 갔다가 왔어", "다녀올게")
    }

    @Test
    fun `든지 던지 — 짝일 때만`() {
        assertFixed("먹든지 말든지", "먹던지 말던지")
        assertFixed("하든가 말든가", "하던가 말던가")
        assertUntouched("얼마나 춥던지 몰라")
    }

    @Test
    fun `쟤네 걔네 얘들아 아니요 설레`() {
        assertFixed("쟤네 뭐 해?", "재네 뭐해?")
        assertFixed("걔네가 먼저 그랬어", "개네가 먼저 그랬어")
        assertFixed("얘들아", "예들아")
        assertFixed("아니요 괜찮아요", "아니오 괜찮아요")
        assertFixed("오늘 너무 설레", "오늘 너무 설레여")
    }

    // --- 3라운드 (chat.txt 실패 + live/round3.tsv) -------------------------------------

    @Test
    fun `때 부사 어떻게 그냥 딱 완전 많이`() {
        assertFixed("나 지금 출발했어", "나지금 출발했어")
        assertFixed("방금 도착했어", "방금도착했어")
        assertFixed("오늘 하루 종일", "오늘하루 종일")
        assertFixed("어떻게 알았어?", "어떻게알았어?")
        assertFixed("그냥 두고 가", "그냥두고 가")
        assertFixed("딱 좋은 날씨", "딱좋은 날씨")
        assertFixed("완전 대박이야", "완전대박이야")
        assertFixed("생각 많이 났어", "생각많이 났어")
        assertUntouched(
            "지금부터 시작", "지금은 안 돼", "지금까지 뭐 했어", "어떻게든 해 볼게", "딱딱해",
            "완전히 잊었어", "완전한 문장", "오늘도 수고했어"
        )
    }

    @Test
    fun `조사 뒤에 붙어 온 말`() {
        assertFixed("날씨가 안 좋을 것 같아", "날씨가안 좋을 것 같아")
        assertFixed("밥을 다 먹었어", "밥을다 먹었어")
        assertFixed("값도 비싸", "값도비싸")
        assertFixed("집에 가는 길이야", "집에가는 길이야")
        assertFixed("아는 사람 중에 한 명이야", "아는 사람 중에한 명이야")
        assertUntouched("보안 때문에 안 돼", "그림 도안을 그렸어", "불안해", "제안을 받았어")
    }

    @Test
    fun `뒤 전 후 동안 정도 · 부탁 · 한 대로 · 안 하 · 책 두 권`() {
        assertFixed("삼십 분 뒤에 출발해", "삼십 분뒤에 출발해")
        assertFixed("며칠 전에 봤어", "며칠전에 봤어")
        assertFixed("검토 후에", "검토후에")
        assertFixed("이틀 동안", "이틀동안")
        assertFixed("두 번 다시", "두 번다시")
        assertFixed("회신 부탁드립니다", "회신부탁드립니다")
        assertFixed("검토 부탁드려요", "검토 부탁 드려요")
        assertFixed("말한 대로 해볼게", "말한대로 해볼게")
        assertFixed("들은 척도 안 하네", "들은 척도 안하네")
        assertFixed("공부 안 하고", "공부안하고")
        assertFixed("책 두 권을 빌렸어", "책두 권을 빌렸어")
        assertFixed("늦잠 잤어", "늦잠잤어")
        assertUntouched(
            "일전에 말했잖아", "주전 선수야", "무한대로 늘어나", "권한대로 해", "최대한 빨리",
            "안하무인이야", "만두 두 개", "열세 명이 왔어", "월세 두 달 밀렸어", "정도껏 해",
            "잠자리 들었어"
        )
    }

    @Test
    fun `지 안 은 지 않 — 띄어 친 부사 안 은 그대로`() {
        assertFixed("생각보다 춥지 않아", "생각보다 춥지 안아")
        assertFixed("갈 데가 마땅치 않네", "갈 데가 마땅치 안네")
        assertFixed("있을게", "잇을게")
        assertFixed("추후 공지하겠습니다", "추후 공지하겟습니다")
        assertFixed("모르겠어", "모르겟어")
        assertUntouched("하지 안 해도 돼", "실을 잇는 중")
    }

    @Test
    fun `ㅐ ㅔ 오타 — 낱말이 아닌 꼴만`() {
        assertFixed("운동 열심히 했어", "운동 열심히 헸어")
        assertFixed("출발해", "출발헤")
        assertFixed("예약해뒀어", "예약헤뒀어")
        assertFixed("사진 보내줄게", "사진 보내줄개")
        assertFixed("적어둘게", "적어둘개")
        assertFixed("같이 먹을래?", "같이 먹을레?")
        assertFixed("비 많이 왔네", "비 많이 왔내")
        assertFixed("할 만하던데", "할 만하던대")
        assertFixed("일찍 자는 게 좋아", "일찍 자는 개 좋아")
        assertFixed("쓸데없는 걱정", "쓸대없는 걱정")
        assertFixed("그렇게 멀지 않네", "그렇게 멀지 안내")
        assertUntouched(
            "이제 헤어지자", "길을 헤매다", "헤드셋 좋다", "비행기 날개", "쓸개가 아파", "얼굴이 빨개",
            "공원 둘레", "안내 방송 들었어?", "실내 온도", "사과 한 개 좀 줘", "그 개 좀 봐", "이불을 갰다"
        )
    }

    @Test
    fun `만큼 뿐 — ㄹ 받침 명사 뒤는 조사라 붙인다`() {
        assertFixed("먹을 만큼 먹어", "먹을만큼 먹어")
        assertFixed("할 뿐이야", "할뿐이야")
        assertUntouched("명절뿐만 아니라", "서울만큼 크다", "말뿐이야")
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

    @Test
    fun `몰라서 틀리는 표기 — 시험지 여섯`() {
        assertFixed("나 어떡하지", "나 어떻하지")
        assertFixed("이거 내 거야", "이거 내꺼야")
        assertFixed("그건 네 거 아니야", "그건 너꺼 아니야")
        assertFixed("내 바람은 그거야", "내 바램은 그거야")
        assertFixed("너무 헷갈려", "너무 헤깔려")
        assertFixed("위층이 시끄러워", "윗층이 시끄러워")
        assertFixed("순댓국 먹으러 가자", "순대국 먹으러 가자")
        assertFixed("편의점 들렀다 갈게", "편의점 들렸다 갈게")
        assertFixed("얻다 대고 반말이야", "어따 대고 반말이야")
        assertFixed("나 너밖에 없어", "나 너 밖에 없어")
        assertFixed("꼼꼼히 봐 줘", "꼼꼼이 봐 줘")
        assertFixed("초콜릿 사 올게", "초코렛 사 올게")
        // 디코더가 말뭉치에 흔한 틀린 꼴로 바꾸던 것.
        assertUntouched("어쭙잖게 굴지 마")
        assertUntouched(
            "나 밖에 있어", "집 밖에 나가 있어", "소리가 들렸다", "순대 국물", "혼자 말고 같이 가",
            "색이 바랜 사진", "내 거 건드리지 마", "어따가 뒀어"
        )
    }

    @Test
    fun `어미 -ㄹ지 -는지 는 붙이고 의존명사 지 는 둔다`() {
        assertFixed("뭘 해야 할지 모르겠어", "뭘 해야 할 지 모르겠어")
        assertFixed("뭐 하는지 궁금해", "뭐 하는 지 궁금해")
        assertFixed("비가 올지도 몰라", "비가 올 지도 몰라")
        assertFixed("어떻게 처리하는지에 대한 얘기", "어떻게 처리하는 지에 대한 얘기")
        assertFixed("외국인치고 한국말 잘하네", "외국인 치고 한국말 잘하네")
        assertUntouched(
            "여기 온 지 3년 됐어", "만난 지 얼마나 됐지", "걔는 지 멋대로야",
            "처음치고 잘했어", "남자치고는 잘하네", "테니스 치고 왔어", "공을 치고 달렸어"
        )
    }

    @Test
    fun `의존명사 듯 뻔 대로 만에 와 율 률`() {
        assertFixed("오늘 비 올 듯", "오늘 비 올듯")
        assertFixed("진짜 죽을 뻔", "진짜 죽을뻔")
        assertFixed("늦을 뻔했잖아", "늦을뻔 했잖아")
        assertFixed("시키는 대로 해", "시키는데로 해")
        assertFixed("하루 만에 끝냈어", "하루만에 끝냈어")
        assertFixed("합격률이 높아", "합격율이 높아")
        assertFixed("할인율 얼마야?", "할인률 얼마야?")
        assertFixed("여기서 해도 돼", "여기서 해도되")
        assertFixed("이제 돼야 할 텐데", "이제 되야 할 텐데")
        assertFixed("이따 봐", "이따봐")
        assertFixed("그건 좀 아닌 거 같아", "그건 좀 아닌거 같아")
        assertUntouched(
            "그럴듯하네", "반듯하게 놔", "그런 데로 가자", "한데로 모아", "그런대로 괜찮아",
            "오랜만에 봤네", "비율이 높아", "법률 상담", "선율이 예뻐", "불문율이야", "율무차 줘"
        )
    }

    @Test
    fun `채팅 장식이 붙어도 어절 규칙이 걸린다`() {
        assertFixed("오늘 너무 설레ㅠㅠ", "오늘 너무 설레여ㅠㅠ")
        assertUntouched("고마워ㅋㅋ", "진짜 웃기다ㅋㅋㅋ", "잘 자^^", "보고 싶어♡")
    }
}
