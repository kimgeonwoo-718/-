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
        TextRule.of("^구지$", "굳이", R)
    )

    /** 어절 경계를 넘겨야 판단되는 맞춤법 규칙. */
    val CONTEXT: List<TextRule> = listOf(
        TextRule.of("""돼지(\s*)않""", "되지 않", R),
        TextRule.of("""몇\s+일(?!요)""", "며칠", R)
    )
}
