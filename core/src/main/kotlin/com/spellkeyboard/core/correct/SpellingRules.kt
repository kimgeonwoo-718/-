package com.spellkeyboard.core.correct

import com.spellkeyboard.core.hangul.Hangul

/**
 * 맞춤법 규칙 모음.
 *
 * 두 갈래로 나뉜다.
 * - [WORD]: 어절 하나 안에서만 판단하는 규칙. 앞뒤 문맥이 필요 없어 오교정 위험이 가장 낮다.
 * - [CONTEXT]: 어절 경계를 넘어야 판단되는 규칙. 전체 창(window) 문자열에 적용한다.
 *
 * 여기 들어가는 항목의 기준은 하나다. **문맥 없이도 거의 항상 틀린 표기**만 넣는다.
 * '로서/로써', '던지/든지', '반드시/반듯이'처럼 의미를 알아야 갈리는 것은
 * 규칙 엔진의 영역이 아니라서 일부러 뺐다. 오교정 한 번이 미교정 열 번보다 나쁘다.
 */
object SpellingRules {

    private const val R = "맞춤법"

    /** 'ㄹ + 께' 를 'ㄹ + 게' 로 볼 때 제외할 표기. 높임 조사 '께'가 붙는 경우다. */
    private val KKE_EXCEPTIONS = setOf("딸께", "들께")


    /**
     * 자주 틀리는 표기 표.
     *
     * 위의 [WORD] 규칙들과 기준은 같다 — **문맥 없이도 거의 항상 틀린 표기**만 넣는다.
     * 다른 것은 방식뿐이다. 규칙 하나당 정규식 하나로 늘어놓으면 어절마다 수백 번을
     * 훑게 되는데, 실시간 입력 경로라 그게 그대로 타이핑 지연이 된다.
     * [TextRule.dictionary] 는 표 전체를 정규식 하나로 묶어 한 번만 훑는다.
     *
     * ## 넣지 않은 것
     *
     * 뜻을 알아야 갈리는 짝은 넣지 않았다. 몇 개만 적어 두면 —
     * '결재/결제', '로서/로써', '던지/든지', '맞추다/맞히다', '늘리다/늘이다',
     * '~장이/~쟁이', '반드시/반듯이', '두껍다/두텁다', '천정/천장'(천정부지는 맞다).
     * 이런 것은 문맥 교정기의 몫이거나, 아직 아무도 못 하는 일이다.
     * **오교정 한 번이 미교정 열 번보다 나쁘다.**
     */
    private val COMMON_MISSPELLINGS: List<Pair<String, String>> = listOf(
        // --- ㅐ / ㅔ 혼동 ----------------------------------------------------
        "도데체" to "도대체",
        "핑게" to "핑계",
        "돌맹이" to "돌멩이",
        "지팽이" to "지팡이",
        "널판지" to "널빤지",
        "얘기치" to "예기치",
        "재털이" to "재떨이",
        "베게" to "베개",
        "배개" to "베개",
        "구렛나루" to "구레나룻",

        // --- 있다/잇다 --------------------------------------------------------
        // '잇다'(실을 잇다)의 활용은 '이어/이었'이라 '잇어·잇엇'은 어느 쪽으로도 틀린다.
        // '잇는'은 '실을 잇는'이 맞아서 넣지 않았다.
        "잇어" to "있어",
        "잇엇" to "있었",
        "잇슴" to "있음",

        // --- 받침·어간 ------------------------------------------------------
        "넉두리" to "넋두리",
        "닥달" to "닦달",
        "단촐" to "단출",
        "밑둥" to "밑동",
        "발자욱" to "발자국",
        "으시대" to "으스대",
        "추스리" to "추스르",
        "핼쓱" to "핼쑥",
        "해꼬지" to "해코지",
        "궁시렁" to "구시렁",
        "서슴치" to "서슴지",
        "승락" to "승낙",
        "실증나" to "싫증나",
        "절래절래" to "절레절레",
        "초죽음" to "초주검",
        "트름" to "트림",
        "장농" to "장롱",
        "부시럼" to "부스럼",
        "뒤치닥거리" to "뒤치다꺼리",
        "건들이" to "건드리",
        "건들였" to "건드렸",
        "널부러" to "널브러",

        // --- 낱말 혼동 ------------------------------------------------------
        "하마트면" to "하마터면",
        "함부러" to "함부로",
        "일부로" to "일부러",
        "여지껏" to "여태껏",
        "아뭏든" to "아무튼",
        "요컨데" to "요컨대",
        "얼만큼" to "얼마큼",
        "옛부터" to "예부터",
        "제작년" to "재작년",
        "주구장창" to "주야장천",
        "야밤도주" to "야반도주",
        "홧병" to "화병",
        "폐륜" to "패륜",
        "조취를" to "조치를",
        "오뚜기" to "오뚝이",
        "창란젓" to "창난젓",
        "케찹" to "케첩",
        "우뢰와" to "우레와",
        "내노라하" to "내로라하",
        "성대묘사" to "성대모사",
        "삼가하시" to "삼가시",
        "안절부절하" to "안절부절못하",

        // --- ㄹ 첨가: '-(으)려고' ---------------------------------------------
        // 받침 뒤의 '을려고'는 어떤 낱말에서도 바른 표기가 아니다 — 관형형 어미 'ㄹ' 뒤에
        // 는 '려고'가 올 수 없다. '먹을려고 → 먹으려고'.
        "을려고" to "으려고",
        "을려면" to "으려면",
        "을려는" to "으려는",
        "을려니" to "으려니",
        // 모음 어간은 낱말마다 갈린다. '갈려고'는 '갈다'(갈아 끼우다)로도 읽히고
        // '줄려고'는 '줄다'로도 읽혀서 못 넣는다. '할다'·'볼다'는 없으니 이 둘만 넣는다.
        "할려고" to "하려고",
        "할려면" to "하려면",
        "할려는" to "하려는",
        "할려니" to "하려니",
        "볼려고" to "보려고",
        "볼려면" to "보려면",

        // --- 외래어 표기 ----------------------------------------------------
        "메세지" to "메시지",
        "컨텐츠" to "콘텐츠",
        "리더쉽" to "리더십",
        "멤버쉽" to "멤버십",
        "스폰서쉽" to "스폰서십",
        "워크샵" to "워크숍",
        "플래쉬" to "플래시",
        "쥬스" to "주스",
        "카페트" to "카펫",
        "커텐" to "커튼",
        "쇼파" to "소파",
        "악세사리" to "액세서리",
        "앰블런스" to "앰뷸런스",
        "바베큐" to "바비큐",
        "비스켓" to "비스킷",
        "비지니스" to "비즈니스",
        "데뷰" to "데뷔",
        "도너츠" to "도넛",
        "디지탈" to "디지털",
        "로보트" to "로봇",
        "로케트" to "로켓",
        "맛사지" to "마사지",
        "미스테리" to "미스터리",
        "발란스" to "밸런스",
        "부페" to "뷔페",
        "샌달" to "샌들",
        "스티로폴" to "스티로폼",
        "심포지움" to "심포지엄",
        "알콜" to "알코올",
        "앙콜" to "앙코르",
        "에어콘" to "에어컨",
        "오리지날" to "오리지널",
        "초콜렛" to "초콜릿",
        "카라멜" to "캐러멜",
        "컨셉" to "콘셉트",
        "콘트롤" to "컨트롤",
        "크리스찬" to "크리스천",
        "타겟" to "타깃",
        "텔레비젼" to "텔레비전",
        "팜플렛" to "팸플릿",
        "페스티발" to "페스티벌",
        "프랜카드" to "플래카드",
        "레크레이션" to "레크리에이션",
        "슈퍼마켙" to "슈퍼마켓",
        "케잌" to "케이크",
        "쵸콜릿" to "초콜릿",
        "아이섀도우" to "아이섀도",
        "윈도우즈" to "윈도",
        "리모콘" to "리모컨",
        "메니저" to "매니저",
        "스케쥴" to "스케줄",
        "화일" to "파일",
        "후라이" to "프라이",
        "휀스" to "펜스"
    )

    val WORD: List<TextRule> = listOf(
        // --- 되 / 돼 ---------------------------------------------------------
        // '됬'은 어떤 문맥에서도 바른 표기가 아니다.
        TextRule.literal("됬", "됐", R),
        TextRule.literal("됀", "된", R),
        TextRule.literal("됌", "됨", R),
        TextRule.literal("되요", "돼요", R),
        TextRule.literal("되서", "돼서", R),
        TextRule.literal("돼고", "되고", R),
        TextRule.literal("돼는", "되는", R),
        TextRule.literal("돼면", "되면", R),
        TextRule.literal("돼서는", "되어서는", R),

        // --- 안 / 않 ---------------------------------------------------------
        // '않'은 '아니하-'의 준말이라 뒤에 어미가 붙는다. 아래 조합은 전부 부사 '안'이어야 한다.
        TextRule.literal("않되요", "안 돼요", R),
        TextRule.literal("않돼요", "안 돼요", R),
        TextRule.literal("않된다", "안 된다", R),
        TextRule.literal("않되", "안 되", R),
        TextRule.literal("않돼", "안 돼", R),
        TextRule.literal("않하", "안 하", R),
        TextRule.literal("않했", "안 했", R),
        TextRule.literal("않할", "안 할", R),
        TextRule.literal("않한", "안 한", R),
        TextRule.literal("않해", "안 해", R),
        TextRule.literal("않그래도", "안 그래도", R),

        // --- 왠 / 웬 ---------------------------------------------------------
        // '왠'이 바른 경우는 '왠지' 하나뿐이다.
        TextRule.literal("웬지", "왠지", R),
        TextRule.of("왠(?!지)", "웬", R),

        // --- -ㄹ게 / -ㄹ 거 --------------------------------------------------
        // 받침 ㄹ 뒤의 '께'는 '게'가 맞다. 높임 조사 '께'와 구분하려고 예외를 둔다.
        TextRule.of("([가-힣])께", "$1게", R) { match ->
            val prev = match.groupValues[1][0]
            Hangul.jongseongOf(prev) == 'ㄹ' && match.value !in KKE_EXCEPTIONS
        },
        // '할꺼야' 계열. 뒤에 오는 글자를 제한해 '볼꺼리' 같은 합성어를 건드리지 않는다.
        TextRule.of("([가-힣])꺼(?=야|에|예|다|요|임|라|니|$)", "$1 거", R) { match ->
            Hangul.jongseongOf(match.groupValues[1][0]) == 'ㄹ'
        },

        // --- -습니다 ---------------------------------------------------------
        // 1988년 맞춤법 개정 이후 '-읍니다'는 언제나 틀린 표기다.
        TextRule.literal("읍니다", "습니다", R),
        TextRule.literal("읍니까", "습니까", R),

        // --- 이에요 / 예요 ---------------------------------------------------
        TextRule.literal("아니예요", "아니에요", R),
        TextRule.literal("이예요", "이에요", R),
        TextRule.literal("뭐에요", "뭐예요", R),
        TextRule.literal("거에요", "거예요", R),
        TextRule.literal("뵈요", "봬요", R),
        TextRule.literal("뵜", "뵀", R),

        // --- 부사 '-이' / '-히' ----------------------------------------------
        TextRule.literal("깨끗히", "깨끗이", R),
        TextRule.literal("틈틈히", "틈틈이", R),
        TextRule.literal("곰곰히", "곰곰이", R),
        TextRule.literal("일일히", "일일이", R),
        TextRule.literal("번번히", "번번이", R),
        TextRule.literal("샅샅히", "샅샅이", R),
        TextRule.literal("낱낱히", "낱낱이", R),
        TextRule.literal("짬짬히", "짬짬이", R),
        TextRule.literal("겹겹히", "겹겹이", R),
        TextRule.literal("나날히", "나날이", R),
        TextRule.literal("깊숙히", "깊숙이", R),
        TextRule.literal("솔직이", "솔직히", R),
        TextRule.literal("조용이", "조용히", R),
        TextRule.literal("일찌기", "일찍이", R),

        // --- 어간/활용 오류 ---------------------------------------------------
        TextRule.literal("설레임", "설렘", R),
        TextRule.literal("설레이", "설레", R),
        TextRule.literal("헤매이", "헤매", R),
        TextRule.literal("목메이", "목메", R),
        TextRule.literal("잠궈", "잠가", R),
        TextRule.literal("잠군", "잠근", R),
        TextRule.literal("잠굴", "잠글", R),
        TextRule.literal("잠구고", "잠그고", R),
        TextRule.literal("담궈", "담가", R),
        TextRule.literal("담군", "담근", R),
        TextRule.literal("담굴", "담글", R),
        TextRule.literal("담구고", "담그고", R),
        TextRule.literal("치뤘", "치렀", R),
        TextRule.literal("치뤄", "치러", R),
        TextRule.literal("치룬", "치른", R),
        TextRule.literal("치룰", "치를", R),
        TextRule.literal("알맞는", "알맞은", R),
        TextRule.literal("걸맞는", "걸맞은", R),
        TextRule.literal("삼가하", "삼가", R),
        TextRule.literal("우겨넣", "욱여넣", R),

        // --- 어휘 혼동 -------------------------------------------------------
        TextRule.literal("어의없", "어이없", R),
        TextRule.literal("어떻해", "어떡해", R),
        TextRule.literal("어쨋든", "어쨌든", R),
        TextRule.literal("어쩃든", "어쨌든", R),
        TextRule.literal("금새", "금세", R),
        TextRule.literal("오랫만", "오랜만", R),
        TextRule.literal("오랜동안", "오랫동안", R),
        TextRule.literal("역활", "역할", R),
        TextRule.literal("희안", "희한", R),
        TextRule.literal("설겆이", "설거지", R),
        TextRule.literal("몇일", "며칠", R),
        TextRule.literal("눈쌀", "눈살", R),
        TextRule.literal("눈꼽", "눈곱", R),
        TextRule.literal("넓직", "널찍", R),
        TextRule.literal("폭팔", "폭발", R),
        TextRule.literal("갯수", "개수", R),
        TextRule.literal("촛점", "초점", R),
        TextRule.literal("싯가", "시가", R),
        TextRule.literal("댓가", "대가", R),
        TextRule.literal("뒷처리", "뒤처리", R),
        TextRule.literal("뒤쳐지", "뒤처지", R),
        TextRule.literal("무릎쓰", "무릅쓰", R),
        TextRule.literal("옳바른", "올바른", R),
        TextRule.literal("널부러", "널브러", R),
        TextRule.literal("부시시", "부스스", R),
        TextRule.literal("흐리멍텅", "흐리멍덩", R),
        TextRule.literal("개거품", "게거품", R),
        TextRule.literal("계시판", "게시판", R),
        TextRule.literal("어물쩡", "어물쩍", R),
        TextRule.literal("움추리", "움츠리", R),
        TextRule.literal("우뢰", "우레", R),
        TextRule.literal("통털어", "통틀어", R),
        TextRule.literal("초생달", "초승달", R),
        TextRule.literal("강남콩", "강낭콩", R),
        TextRule.literal("짜집기", "짜깁기", R),
        TextRule.literal("안성마춤", "안성맞춤", R),
        TextRule.literal("절대절명", "절체절명", R),
        TextRule.literal("홀홀단신", "혈혈단신", R),
        TextRule.literal("성대묘사", "성대모사", R),
        TextRule.literal("뇌졸증", "뇌졸중", R),
        TextRule.literal("임신공격", "인신공격", R),
        TextRule.literal("어리버리", "어리바리", R),
        TextRule.literal("육계장", "육개장", R),
        TextRule.literal("곱배기", "곱빼기", R),
        TextRule.literal("찌게", "찌개", R),
        TextRule.literal("떡볶기", "떡볶이", R),
        TextRule.literal("되물림", "대물림", R),
        TextRule.literal("저희나라", "우리나라", R),
        TextRule.literal("문안한", "무난한", R),
        TextRule.literal("문안해", "무난해", R),
        TextRule.literal("문안하다", "무난하다", R),

        // 어절 전체가 '구지'일 때만 '굳이'로 본다. 지명·상호에 섞이는 것을 피한다.
        TextRule.of("^구지$", "굳이", R),

        // 자주 틀리는 표기 표. 위의 규칙들이 먼저 돌고 나서 한 번에 훑는다.
        TextRule.dictionary(COMMON_MISSPELLINGS, R)
    )

    /** 어절 경계를 넘겨야 판단되는 맞춤법 규칙. */
    val CONTEXT: List<TextRule> = listOf(
        TextRule.of("""돼지(\s*)않""", "되지 않", R),
        TextRule.of("""몇\s+일(?!요)""", "며칠", R),
        // 부사 '안' 은 '되다/돼' 와 띄운다. 어절 첫머리의 '안돼/안되' 만 본다 — '편안되'
        // 같은 다른 낱말 속은 앞이 한글이라 걸리지 않는다. 뒤따르는 '되요/되서' 는
        // 어절 규칙이 한 번 더 돌면서 '돼요/돼서' 로 맞춘다.
        TextRule.of("""(^|[^가-힣])안(돼|되)""", "$1안 $2", R),

        // '낳다'(아이를 낳다)와 '낫다'(병이 낫다)는 뜻이 달라 낱말만으로는 못 가린다.
        // 다만 '빨리/얼른/어서 낳으세요' 는 쾌유를 비는 말이 굳어진 자리라 거의 언제나
        // '나으세요' 다. 부사가 앞에 붙은 이 꼴만 고친다.
        TextRule.of("""(빨리|얼른|어서|어여|속히)(\s*)낳(으|아|았)""", "$1$2나$3", R)
    )
}
