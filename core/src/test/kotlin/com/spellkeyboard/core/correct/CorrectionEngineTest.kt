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
    fun `홀로 떨어진 서술격 조사 이다 는 모르는 말 뒤에도 붙인다`() {
        // 사람 이름은 언어모델이 몰라서 예전엔 그대로 남았다(2026-10-03 제보).
        assertEquals("저는 김건우입니다", fix("저는 김건우 입니다"))
        assertEquals("이곳은 서울입니까", fix("이곳은 서울 입니까"))
        // 받침 없는 말 뒤에서는 줄어든 꼴로.
        assertEquals("김건우예요", fix("김건우 이에요"))
        assertEquals("제 친구는 박민수였어요", fix("제 친구는 박민수 이었어요"))
        // 받침 있는 말 뒤에서는 그대로 붙인다.
        assertEquals("저는 학생이에요", fix("저는 학생 이에요"))
        assertUntouched("저는 김건우입니다")
        // 감탄사 '이야' 는 건드리지 않는다.
        assertUntouched("와 이야 대박")
    }

    @Test
    fun `되 돼 를 가린다`() {
        assertEquals("됐다", fix("됬다"))
        assertEquals("안 됐어", fix("안 됬어"))
        assertEquals("그렇게 돼요", fix("그렇게 되요"))
        assertEquals("늦어서 돼서", fix("늦어서 되서"))
        assertEquals("잘 되는 것", fix("잘 돼는 것"))
        // 2026-10-03 제보로 더한 것.
        assertEquals("그거 하면 안 돼", fix("그거 하면 안되"))
        assertEquals("이게 되나요", fix("이게 돼나요"))
        assertEquals("그럼 되잖아", fix("그럼 돼잖아"))
        assertEquals("되니까 괜찮아", fix("돼니까 괜찮아"))
        assertEquals("친구가 돼줄게", fix("친구가 되줄게"))
        assertUntouched("돼지만 키워")
        assertUntouched("쌀 한 되")
        assertUntouched("하되 조심해")
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
        assertEquals("설렘이 가득해", fix("설레임이 가득해"))
        // 맨 '설레임' 은 아이스크림 이름이기도 하다.
        assertUntouched("설레임 하나 사 와")
    }

    @Test
    fun `처음 보는 오타 시험지에서 놓친 흔한 오타를 고친다`() {
        // 2026-10-14 셋째 판. 처음 보는 시험지에서 아침 엔진도 지금 엔진도 놓친 것들.
        assertEquals("오늘 저녁에 뭐 먹을까", fix("오늘 저녘에 뭐 먹을까"))
        assertEquals("선물 고마워", fix("선물 고마어"))
        assertEquals("다음 주에 시험 있어", fix("다음 주에 시험 이써"))
        assertEquals("돈이 없어", fix("돈이 업써"))
        assertEquals("그 영화 재미있대", fix("그 영화 재미잇대"))
        assertEquals("오늘따라 사람이 많네", fix("오늘따라 사라미 많네"))
        assertEquals("지금 어디야", fix("지금 어디얘"))
        assertEquals("그거 얼마예요", fix("그거 얼마얘요"))
        assertEquals("감사합니다", fix("감사함니다"))
        assertEquals("확인 부탁드립니다", fix("확인 부탁드림니다"))
        assertEquals("그게 아닙니까", fix("그게 아님니까"))
        assertEquals("힘이 없습니다", fix("힘이 없음니다"))
        assertEquals("비가 와서 우산 챙겨갔어", fix("비가 와서 우산 챙겨갓어"))
        assertEquals("집에 다녀왔다", fix("집에 다녀왓다"))
        assertEquals("엄마한테 전화해 봐", fix("엄마한태 전화해 봐"))
        assertEquals("선생님한테 혼났어", fix("선생님한태 혼났어"))
        assertEquals("너무 피곤해서 일찍 잘래", fix("너무 피곤해서 일직 잘래"))
        assertEquals("배터리가 없어", fix("배터리가 업어"))
        assertEquals("오늘 날씨 정말 좋다", fix("오늘 날씨 정말 조타"))
        assertEquals("날씨가 추워서 감기 걸렸어", fix("날씨가 추어서 감기 걸렸어"))
        assertEquals("추운 것 같아", fix("추운 것 가타"))
        assertEquals("그 얘기 들어 봤어", fix("그 얘기 드러 봤어"))
        assertEquals("회의 시간 언제야", fix("회의 시가 언제야"))
        assertEquals("이거 얼마에 샀어", fix("이거 얼마애 샀어"))
        assertEquals("그건 내 거야", fix("그건 내 꺼야"))
        // 같은 날 앞서 넣은 것 — 막이를 세우며 디코더가 놓치게 된 흔한 오타.
        assertEquals("내용이 좋아", fix("내요이 좋아"))
        assertEquals("영화 보러 가자", fix("여화 보러 가자"))
        assertEquals("아침부터 바빠", fix("아치부터 바빠"))
        assertEquals("문 좀 닫아줄래", fix("문 좀 닫아줄레"))
        assertEquals("쉴 새도 없이 일했어", fix("쉴 세도 없이 일했어"))
        assertEquals("잘 안 하네", fix("잘 안 하내"))
        assertEquals("자료 보내 드릴게요", fix("자료 보네 드릴게요"))
        assertEquals("그냥 두고 가", fix("그냐 두고 가"))
        assertEquals("세 개만 줘", fix("세 개마 줘"))
        assertEquals("두 시간이나 걸렸어", fix("두 시가이나 걸렸어"))
        assertEquals("회신 부탁드립니다", fix("회시 부탁드립니다"))
        assertUntouched("아줄레 타일이 예쁘다")
        assertUntouched("하내 사람들은 친절했다")
        assertUntouched("시가 총액이 늘었다")
        // 바른 말·진짜 낱말은 그대로.
        assertUntouched("그는 일직 근무를 섰다")
        assertUntouched("일직선으로 쭉 가면 돼")
        assertUntouched("엄마가 아기를 업어 줬어")
        assertUntouched("남친이 업어 줬어")
        assertUntouched("춤을 추어서 땀이 났다")
        assertUntouched("하랄트 추어 하우젠은 의학자다")
        assertUntouched("조타수의 조타 미숙이 원인이었다")
        assertUntouched("조호익의 시가 걸려 있다")
        assertUntouched("진실이 드러 났다")
        assertUntouched("그는 땅바닥에 드러 누웠다")
        assertUntouched("정부의 안일한태도가 문제다")
        assertUntouched("한태웅 선수가 골을 넣었다")
        assertUntouched("그는 갓 태어난 아기를 안았다")
        assertUntouched("삿갓은 대나무로 만든다")
        assertUntouched("이 써 봐 괜찮아")
        assertUntouched("새벽녘에 일어났다")
        assertUntouched("불 꺼 줘")
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
        // '-무좀' 으로 끝나도 '나무·업무' 뒤의 '좀' 은 부사다. 어절 통째로 '무좀' 일 때만 지킨다.
        assertEquals("나무 좀 심자", fix("나무좀 심자"))
        assertEquals("업무 좀 도와줘", fix("업무좀 도와줘"))
        // '좀비'·'좀약' 으로 시작해도 뒤가 '비싸·비켜·약해' 면 부사 '좀' 이다. '좀도둑' 만 피하니 '좀도와' 도 뗀다.
        assertEquals("좀 비싸네", fix("좀비싸네"))
        assertEquals("좀 비켜 봐", fix("좀비켜 봐"))
        assertEquals("좀 약해", fix("좀약해"))
        assertEquals("좀 도와줘", fix("좀도와줘"))
    }

    @Test
    fun `좀 으로 끝나거나 시작하는 낱말은 가르지 않는다`() {
        // 윈도우 보고(2026-10-12): '발에 무좀 생겼어' 가 '무 좀' 이 됐다. 같은 규칙이 좀비·좀처럼 … 도 갈랐다.
        for (text in listOf(
            "발에 무좀 생겼어", "무좀", "손발톱무좀 치료 중", "발무좀 때문에", "무좀약 발랐어",
            "좀비 영화 봤어", "좀비가 나와서 무서웠어", "좀처럼 안 오네", "좀체 말을 안 해", "좀체로 안 와",
            "좀벌레가 나왔어", "좀약 좀 사 와", "그 사람 좀스럽다", "좀스러운 짓", "이 좀팽이야", "좀생이 같아",
        )) {
            assertUntouched(text)
        }
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
    fun `않길 않긴 은 바른 말이라 그대로 두고 어절 첫머리 않받네 는 고친다`() {
        // '하지 않길 바랄게요'(않기를)를 '안 길' 로 뗐다 — 큰 시험지(챗봇 답 6천 문장)에서 열두 번 나온 오교정(2026-10-11).
        assertUntouched("길지 않길 바랄게요")
        assertUntouched("후회하지 않길 바라요")
        assertUntouched("가지 않긴 했어")
        assertEquals("안 받네", fix("않받네"))
    }

    @Test
    fun `일부로 는 앞말을 보고 일부러 로 고친다`() {
        assertEquals("일부러 안 웃어도 돼요", fix("일부로 안 웃어도 돼요"))
        // 명사 '일부' + '로' 는 맞는 말이다.
        assertUntouched("한 남자가 오케스트라의 일부로 연주하고 있다")
        assertUntouched("세 부분이 일부로 포함된다")
    }

    @Test
    fun `텐대 는 텐데`() {
        assertEquals("벅찰 텐데요", fix("벅찰텐대요"))
    }

    @Test
    fun `아라비아 숫자 뒤 단위는 붙여 쓴 그대로 둔다`() {
        // 숫자와 어울려 쓰는 단위는 붙여 써도 된다(한글 맞춤법 제43항 다만). '5천원' 만 '5천 원' 으로 떼고 있었다(2026-10-11).
        assertUntouched("사과 3개 샀어")
        assertUntouched("5천원이야")
        assertUntouched("2천원짜리")
        assertUntouched("3백명 왔어")
        assertUntouched("3만원 줘")
        assertUntouched("10분 뒤에")
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
    fun `수 단위 뒤에 딴 글자가 이어지면 이름이라 가르지 않는다`() {
        // 깨끗한 글 띄어쓰기 오교정(2026-10-14 넷째 판). 꼬리가 조사·수량 꼬리일 때만 뗀다.
        assertUntouched("한명숙 전 총리")
        assertUntouched("구조단들이 두알라에서 준비했다")
        assertEquals("열 시보다는 아홉 시에", fix("열시보다는 아홉시에"))
        assertEquals("삼십 대일까요", fix("삼십대일까요"))
        assertEquals("세 개라서", fix("세개라서"))
    }

    @Test
    fun `낱말 속이나 조사 뒤의 조사꼴은 건드리지 않는다`() {
        // 깨끗한 글 띄어쓰기 오교정(2026-10-14 넷째 판).
        assertUntouched("안젤리나 졸리, 비욘세 등이 뒤를 이었다.")
        assertUntouched("파트너를 지정해주는 등")
        assertUntouched("주류 도·소매 업체")
        assertUntouched("통신 및 도·감청")
        assertUntouched("세계타이틀전에 나선다")
        // 앞말에 붙는 '-ㄹ지' 는 그대로 고친다.
        assertEquals("갈지 모르겠다", fix("갈 지 모르겠다"))
    }

    @Test
    fun `받침 빠진 오타를 뒷말로 가려 고친다`() {
        // 2026-10-15. 단위 앞 '하·며', 수 뒤 '버', 때 뒤 '저도', 용언 앞 '조·마이·아', '지그'.
        assertEquals("아이스 아메리카노 한 잔만", fix("아이스 아메리카노 하 잔만"))
        assertEquals("내일 몇 시에 만날까", fix("내일 며 시에 만날까"))
        assertEquals("두 번 다시", fix("두 버 다시"))
        assertEquals("이십 분 정도 걸려", fix("이십 분 저도 걸려"))
        assertEquals("자주 좀 보자", fix("자주 조 보자"))
        assertEquals("바람이 많이 부네", fix("바람이 마이 부네"))
        assertEquals("말도 안 돼", fix("말도 아 돼"))
        assertEquals("지금 어디쯤 왔어", fix("지그 어디쯤 왔어"))
        // 인용 뒤 어미 '-며', 외국 이름 '조', 감탄사 '아', '마이 페이지', '지그재그' 는 그대로.
        assertUntouched("“국회가 거점이 돼야 한다”며 장외투쟁 중단을 촉구했다.")
        assertUntouched("조 하트는 세리에 A에서 뛰었다.")
        assertUntouched("아 맞다 그거")
        assertUntouched("마이 페이지에서 확인")
        assertUntouched("지그재그로 걸어")
    }

    @Test
    fun `과거형 자리의 쉬프트 오타와 ㅐ ㅔ 오타를 고친다`() {
        // 디코더는 고친 말을 말뭉치가 알 때만 고쳐서 드문 꼴이 남았다(2026-10-15).
        assertEquals("표는 미리 예매해뒀어", fix("표는 미리 예메해둿어"))
        assertEquals("책 두 권을 빌렸어", fix("책 두 권을 빌렷어"))
        assertEquals("일정을 다음 주로 미뤘어", fix("일정을 다음 주로 미뤗어"))
        assertEquals("그는 이렇게 말했다", fix("그는 이렇게 말햇다"))
        assertEquals("처음에는 어려웠는데 하다 보니까", fix("처음에는 어려웠는대 하다 보니까"))
        assertEquals("한 번만 더 해 보자", fix("한 번만 더 헤 보자"))
        // 받침 ㅅ 명사 + 을, '무엇', '빼앗다·뺏다', '뱃지', 전해 들은 말 '-대' 는 그대로.
        assertUntouched("무엇을 원해")
        assertUntouched("파일 포맷을 바꿔")
        assertUntouched("돈을 뺏어")
        assertUntouched("빼앗고 말았다")
        assertUntouched("뱃지 달아")
        assertUntouched("걔 결혼한대")
    }

    @Test
    fun `낱말로 굳은 손때 한시 일본 이름과 받침이 안 맞는 조사는 건드리지 않는다`() {
        // 2026-10-15 여섯째 판 ① — 처음 보는 시험 표본(test2).
        assertUntouched("30년 손때 묻은 집")
        assertUntouched("취득세 한시 인하 조치의 종료를 앞두고")
        assertUntouched("사쿠라지마 섬은 꽤 잠잠해졌다")
        assertUntouched("백일희는 1955년 를 부른 가수이고")
        // 같은 규칙이 고치던 것은 그대로 고친다.
        assertEquals("먹을 때 조심해", fix("먹을때 조심해"))
        assertEquals("내일 한 시에 보자", fix("내일 한시에 보자"))
        assertEquals("울지 마", fix("울지마"))
        assertEquals("학교에서 만나", fix("학교 에서 만나"))
        assertEquals("1955년을 기억해", fix("1955년 을 기억해"))
    }

    @Test
    fun `소리 같은 모음과 겹받침 오타를 앞뒤 말로 가려 고친다`() {
        // 2026-10-15 여섯째 판 ② — 오타 시험지에서 남던 것.
        assertEquals("공부하는 데 방해가 됐어", fix("공부하는 대 방해가 됐어"))
        assertEquals("그런 데다가 값도 비싸", fix("그런 대다가 값도 비싸"))
        assertEquals("모르는 체하지 마", fix("모르는 채하지 마"))
        assertEquals("할 게 너무 많아", fix("할 개 너무 많아"))
        assertEquals("회의는 내일 오전으로 미뤄졌습니다", fix("회의는 네일 오전으로 미뤄졌습니다"))
        assertEquals("많이 서운했겠다", fix("만이 서운했겠다"))
        // 맞는 말은 그대로 — 대(對)·대가(代價)·대로, 수 + 개, 손톱 네일, 붙은 '만이'.
        assertUntouched("인간 대 기계의 대결")
        assertUntouched("노력한 대가를 받았다")
        assertUntouched("하는 대로 해")
        assertUntouched("열 개 너무 많아")
        assertUntouched("네일 하러 가자")
        assertUntouched("응답자 8%만이 걱정 없이 잔다")
        // 외래어 표기('렌털' — 디코더가 말뭉치에 흔한 '렌탈' 로 바꾸던 것을 표가 도로 잡는다).
        assertEquals("렌털 서비스", fix("렌탈 서비스"))
    }

    @Test
    fun `의존명사와 횟수를 띄운다`() {
        // 붙여 친 글 띄어쓰기 자에서 놓치던 것(2026-10-15).
        assertEquals("그냥 쉬는 게 낫지", fix("그냥 쉬는게 낫지"))
        assertEquals("가는 게 좋겠어", fix("가는게 좋겠어"))
        assertEquals("그런 줄도 모르고", fix("그런줄도 모르고"))
        assertEquals("셋 다 마음에 들어", fix("셋다 마음에 들어"))
        assertEquals("한 번만 더 해 보자", fix("한번만 더 해 보자"))
        assertEquals("한 번도 없어", fix("한번도 없어"))
        assertEquals("아는 사람 중에 한 명이야", fix("아는 사람중에 한 명이야"))
        // '-ㄹ게'(어미), '한번 해 봐'(시험 삼아), '우리다', '그중' 은 그대로.
        assertUntouched("내가 할게 더 줘")
        assertUntouched("한번 해 봐")
        assertUntouched("그건 우리다")
        assertUntouched("그중에 하나")
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
