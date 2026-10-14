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

    /** 어절마다 스페이스를 누르며 친다(실시간 길). */
    private fun typed(sentence: String): String {
        val buffer = StringBuilder()
        for (word in sentence.split(' ')) {
            buffer.append(word)
            val before = buffer.toString()
            engine.correctTail(before)?.let { buffer.replace(before.length - it.deleteBefore, buffer.length, it.replacement) }
            buffer.append(' ')
        }
        return buffer.toString().trim().replace(Regex("\\s+"), " ")
    }

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
        // '리뷰이벤트' 는 합성어라 붙여 쓴 대로 둔다(2026-10-13 — 긴 덩어리 나누기가 명사|명사를 가르지 않는다). 의존명사 '시' 앞은 뗀다.
        assertEquals("리뷰이벤트 시에만", all("리뷰이벤트시에만"))
        assertEquals("본체는 기본이고", all("본체는기본이고"))
    }

    @Test
    fun `사전이 나눈 것을 도로 붙이지 않는다`() {
        // 세 자리 모두 사전은 제대로 나누고 있었는데 rejoinOptional 이 도로 붙이고 있었다.
        // 도로 붙이면 "사전이 나눴다" 는 사실이 사라져서 디코더가 한 낱말로 본다.

        // 체언+조사를 '-아/-어' 어미로 잘못 읽던 자리.
        assertEquals("사흘 내내 비가 왔어", all("사흘 내내 비가왔어"))
        // '-아서'(연결어미)는 보조용언을 데려오지 않는다.
        assertEquals("전화를 못 받아서 미안", all("전화를 못 받아서미안"))
        // '-았어/-었어'(종결어미)도 마찬가지다.
        assertEquals("잘 먹었어 고마워", all("잘 먹었어고마워"))
        // 명사|명사. 말뭉치에 '오늘 날씨가' 연쇄가 없어서 연쇄만 요구하면 안 풀린다.
        assertEquals("오늘 날씨가 정말 좋다", all("오늘날씨가 정말 좋다"))
        assertEquals("지금 통화 괜찮아", all("지금통화 괜찮아"))
        // 한 음절 부사. 사전은 '잘'·'더'를 어미로도 읽지만 부사로 홀로 서는 일이 훨씬 잦다.
        assertEquals("덕분에 잘 해결됐어", all("덕분에 잘해결됐어"))
        assertEquals("십 분만 더 자고 싶다", all("십 분만 더자고 싶다"))
    }

    @Test
    fun `붙여 써도 맞는 자리는 도로 붙인다`() {
        // 위 변경이 이쪽까지 풀어 버리면 안 된다. '-아/-어' 뒤의 보조용언·합성동사는
        // 붙여 써도 맞다(한글 맞춤법 제47항).
        assertEquals("문 좀 닫아줄래", all("문 좀 닫아줄래"))
        assertEquals("미리 사놓자", all("미리 사놓자"))
        assertEquals("같이 가보자", all("같이 가보자"))
        assertEquals("정리해놓을게", all("정리해놓을게"))
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
    fun `어미 앞은 형태소 사전이 나눈 자리여도 자르지 않는다`() {
        // 윈도우 보고(2026-10-12). '공유해주시기' 는 공유/해/주/시(높임)/기 인데, 형태소 사전이 '공유해 주 시기'(시기 = 때)로
        // 나눈 자리를 디코더가 그대로 써서 '공유해주 시기' 가 됐다. 실시간·전체교정 둘 다.
        for (text in listOf("자료를 공유해주시기 바랍니다", "연락해주시기 바랍니다", "참석해주시기를 부탁드립니다")) {
            assertEquals(text, all(text))
            assertEquals(text, tail(text))
        }
        // 띄어 쓴 보조용언도 그대로, 붙여 쓴 구는 여전히 가른다.
        assertEquals("자료를 공유해 주시기 바랍니다", all("자료를 공유해 주시기 바랍니다"))
        assertEquals("현재는 품절입니다", all("현재는품절입니다"))
        // 막는 것은 높임의 '-시-' 하나다. 선어말어미 전부로 넓혔을 때 분석 사전이 '있'(감동/이/있/으면서)과 '된' 뒤의 '시'(관련/된/시/나)를
        // 선어말어미로 읽어 아래 가르기를 잃었다.
        assertEquals("그날은 선약이 있어", all("그날은 선약이있어"))
        assertEquals("변경 사항이 있으면 연락드리겠습니다", all("변경 사항이있으면 연락드리겠습니다"))
        assertEquals("관련된 시나 그림", all("관련된시나 그림"))
    }

    @Test
    fun `무좀 은 무 좀 으로 가르지 않는다`() {
        assertEquals("발에 무좀 생겼어", all("발에 무좀 생겼어"))
        assertEquals("발에 무좀 생겼어", tail("발에 무좀 생겼어"))
        assertEquals("나무 좀 심자", all("나무좀 심자"))
    }

    @Test
    fun `띄어쓰기 사전이 빈 조각을 내도 죽지 않는다`() {
        // 위키 문장을 어절마다 치다가 ArrayIndexOutOfBounds 로 죽었다 — 폰에서는 키보드가 튕긴다(2026-10-14).
        val sentence = "이바일로가 죽었다는 헛소문이 퍼지자 터르노보는 패닉에 빠졌고"
        val buffer = StringBuilder()
        for (word in sentence.split(' ')) {
            buffer.append(word)
            engine.correctTail(buffer.toString())
            buffer.append(' ')
        }
        all(sentence)
    }

    @Test
    fun `띄어 친 보조용언을 붙이지 않고 이름 덩어리를 가르지 않는다`() {
        // 보조용언은 띄어 쓰는 것이 원칙이고 붙여 써도 된다 — 사람이 띄어 친 것을 붙이면 맞는 걸 고친 것이다(2026-10-14).
        assertEquals("카카오톡으로 보내 줘", all("카카오톡으로 보내 줘"))
        assertEquals("카카오톡으로 보내 줘", tail("카카오톡으로 보내 줘"))
        // 통째 분석이 이름 조각뿐인 덩어리('배스킨/라빈/스')는 이름이다.
        assertEquals("배스킨라빈스 31", all("배스킨라빈스 31"))
    }

    @Test
    fun `기사에서 진짜 낱말을 흔한 딴 말로 바꾸지 않는다`() {
        // 큰 시험지(기사·제목)에서 실시간 길로 바뀌던 것(2026-10-14).
        for (text in listOf(
            "회의록이 대통령기록관으로 이관되지 않은 것도",   // 흔한 명사 줄기 안(이관 → 일관)
            "드라마에 시대의 메시지를 담다",                   // 어미 끝 음절에 받침 채우기(담다 → 담당)
            "베네수엘라 카드 결제기 사용 급증",                // 명사 + 한 음절의 끝을 조사로(결제기 → 결제가)
            "총을 든 로버트 드지에칸스키를 보자",              // 둘 다 모르는 조각 사이를 가르기(드지 → 들지)
            "삼성 빅스비로 AI 비서시장 도전장",                 // 합성어 속 흔한 명사(시장 → 실장)
            "모스크바국제도서전에 한국관 설치",                 // 합성어 속 흔한 명사(도서전 → 도 선전)
            "美 전 국방장관들 미국의 힘은 동맹에서",            // 흔한 명사 합성어 + 들(→ 을)
            "노벨상 작가 르 클레지오 황석영 만나",             // 두 음절 어절 + 한 음절 어절을 붙이며 고치기(→ 작가를)
            "정부는 방식을 바꾸었으며, 새로운 제도를 만들었다", // 연결 어미 '-며' → '-면'
            "그는 일자리가 줄어든다며 걱정했다",               // '-다며' → '-다면'
            "드라마 속 장면을 설명하다 결국 울었다",            // 기본형 '-하다' → '-한다'
            "그녀의 직업은 요양보호사다."                      // 문장 부호 앞 끝 음절(다 → 가)
        )) {
            assertEquals(text, typed(text), text)
        }
    }

    @Test
    fun `붙여 친 글에서 합성어가 아닌 자리는 뗀다`() {
        assertEquals("회사 앞에서 보자", all("회사앞에서보자"))
        assertEquals("감기 조심해", all("감기조심해"))
        assertEquals("공부하는 데 방해가 됐어", all("공부하는 데방해가 됐어"))
        // 관형사와 뒷말이 합쳐 사전에 오른 낱말은 붙여 둔다.
        assertEquals("그날은 선약이 있어", all("그날은선약이있어"))
        assertEquals("이 사람이 누구야", all("이사람이누구야"))
        // 합성어는 붙여 써도 맞다.
        assertEquals("눈앞에서 사라졌어", all("눈앞에서사라졌어"))
    }

    @Test
    fun `붙여 친 문장 속 보조용언과 부사는 뗀다`() {
        // 2026-10-14 셋째 판. '-아/-어' 가 아닌 연결어미 뒤 보조용언, 사전이 이름으로 읽는 한 음절 부사.
        assertEquals("작년에 갔던 곳인데 이번에도 거기로 가면 어떨까 싶어", all("작년에갔던곳인데이번에도거기로가면어떨까싶어"))
        assertEquals("비가 그치면 산책이라도 하러 나갈까 하는데 같이 갈래", all("비가그치면산책이라도하러나갈까하는데같이갈래"))
        assertEquals("푹 쉬는 게 좋겠다", all("푹쉬는게좋겠다"))
        assertEquals("아버지가 방에 들어가신다", all("아버지가방에들어가신다"))
        // '-아/-어' 뒤 보조용언과 명사 뒤 '드리다' 는 붙여 둔다.
        assertEquals("변경사항이 있으면 연락드리겠습니다", all("변경사항이있으면연락드리겠습니다"))
        // 수관형사+단위로 읽히는 낱말('열병', '세대')을 떼면 기사 속 이름이 깨졌다 — 그 떼기는 넣었다가 뺐다.
        for (text in listOf("아프리카돼지열병이 확산되고 있다", "한일차세대학술포럼 개최", "지역가입세대의 소득")) {
            assertEquals(text, all(text))
        }
    }

    @Test
    fun `기사 위키에서 진짜 낱말을 흔한 말로 바꾸던 것`() {
        // 2026-10-14 셋째 판. 사전 명사 + 맞는 조사, 붙여 쓴 조각의 된소리, 모르는 이름 조각.
        for (text in listOf(
            "인간의 야심과 비전을 담는 곳이며",
            "홀로 양육하다가 지병에 걸려 사망했다.",
            "호수에 있는 사라의 별장으로 여행을 떠났다.",
            "그러나 무제는 오손과의 동맹으로 흉노를 깨고자 했다.",
            "쁘띠프랑스, 그레뱅뮤지엄, 난타",
            "대표 주관사는 미래에셋대우다.",
            "바삭하게 구운 빵",
            "메주 말리기 좋은 날",
            "ELS 발행사는 헤지를 위해 장외에서 옵션을 매도한다.",
            "차량 내비게이션에 등록 캠핑장을 수록해",
            "경찰은 문화제로 신고하고 열린 집회를 불법으로 판단했다.",
            "야하다 가족과 함께 보기는 민망한 듯",
            "원하시다면 예약해 드릴게요"
        )) {
            assertEquals(text, all(text))
        }
        // 일상어 오타는 그대로 고친다.
        assertEquals("내일은 좀 나아지길 바랄게", all("네일은 좀 나아지길 바랄게"))
        assertEquals("회의 중에는 전화를 못 받아", all("회의 중애는 전화를 못 받아"))
        // 조사가 앞말 받침과 안 맞는 꼴의 빠진 받침도 그대로 고친다.
        assertEquals("생각보다 사람이 많네", all("생각보다 사라이 많네"))
        assertEquals("하시는 분인 것 같습니다.", all("하시는 분인것 같습니다."))
        // 된소리 막이는 어미 '-ㄹ가 → -ㄹ까'(쉬프트 오타)는 그대로 둔다.
        assertEquals("그럼 내일은 어떠실까요?", all("그럼 내일은 어떠실가요?"))
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
