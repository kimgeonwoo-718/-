package com.spellkeyboard.core.correct

import com.spellkeyboard.core.hangul.Hangul

/**
 * 띄어쓰기 규칙 모음. 어절 경계를 넘나들어야 하므로 전체 창 문자열에 적용한다.
 *
 * ## 이 규칙들이 못 하는 일
 *
 * 여기 있는 것은 **특정 패턴을 고치는 규칙**이지 일반적인 띄어쓰기 복원이 아니다.
 * "제가할게요" 같이 통째로 붙여 쓴 문장을 나누려면 형태소 분석이나 문자 단위 모델이
 * 필요하다. 규칙만으로 그걸 하려 들면 오교정이 폭발한다. 모델은 다음 단계다.
 *
 * 그래서 조건을 일부러 좁게 잡았다. 의존명사는 앞의 관형형 어미(받침 ㄹ/ㄴ)와
 * 뒤에 오는 서술어를 **둘 다** 확인한 뒤에만 띄운다. '실수 있다' 같은 오교정을
 * 만들지 않기 위해서다.
 */
object SpacingRules {

    private const val R = "띄어쓰기"

    /** 관형형 어미의 받침. 이 받침이 앞에 있을 때만 뒤 의존명사를 띄운다. */
    private val ADNOMINAL_JONG = setOf('ㄹ', 'ㄴ')

    /** 받침이 ㄹ 이지만 의존명사 '수'가 아닌 명사들. */
    private val SU_EXCEPTIONS = setOf("별수", "실수", "홀수", "술수")

    /** 받침이 ㄹ/ㄴ 이지만 의존명사 '것'이 아닌 합성어들. */
    private val GEOT_EXCEPTIONS = setOf("별것", "날것", "들것")

    /** 한 단어로 붙여 쓰는 '때'들. */
    private val TTAE_EXCEPTIONS = setOf("한때", "물때", "본때")

    /**
     * 수관형사. 뒤에 단위 의존명사가 오면 띄어 쓴다 ('네 개', '두 명', '몇 번').
     * 긴 것부터 놓아야 '다섯'이 '섯'으로 잘리지 않는다.
     */
    private val NUMERALS = listOf(
        "여러", "다섯", "여섯", "일곱", "여덟", "아홉", "스무", "열", "몇", "한", "두", "세", "네"
    )

    /**
     * 단위 의존명사. 앞의 수관형사와 반드시 띄어 쓴다.
     *
     * 짧은 것 위주로 골랐다. 남는 충돌은 [NUMERAL_EXCEPTIONS] 가 걸러낸다.
     */
    private val UNITS = listOf(
        "마리", "가지", "군데", "시간", "사람",
        "개", "명", "번", "살", "권", "장", "대", "잔", "병", "통", "채", "칸", "척", "알"
    )

    /**
     * 수관형사 + 단위로 읽히지만 실은 한 낱말인 것들.
     *
     * 이 표가 이 규칙의 안전장치다. '두통'을 '두 통'으로, '열대'를 '열 대'로 만드는 것이
     * 이 규칙이 낼 수 있는 가장 나쁜 실수라서, 부딪히는 것을 미리 적어 둔다.
     * '한번'은 부사('한번 해 보자')와 수량('한 번만')이 둘 다 맞아 아예 손대지 않는다.
     */
    private val NUMERAL_EXCEPTIONS = setOf(
        "한번", "한통", "한장", "한대", "한살", "한개", "한명", "한잔", "한병",
        "두통", "두상", "두목", "두장", "두번",
        "세상", "세대", "세계", "세월", "세살", "세면", "세척", "세개", "세번",
        "네발", "네모", "네온",
        "열대", "열장", "열병", "열사", "열개", "열번",
        "몇몇", "여러분", "스무고개"
    )

    /**
     * 앞말과 붙여 써야 하는 조사. 긴 것부터 놓아야 '에서'가 '에'로 잘리지 않는다.
     * '만'은 뺐다 — '준 만 달러', '삼 만 명'처럼 수사 '만(萬)'이 홀로 서는 일이 잦다.
     */
    /**
     * '-지 않다'로 보이지만 한 낱말인 것들. '머지않아'(오래지 않아)가 대표다 —
     * 여기서 띄우면 없는 말('머지')이 생긴다.
     */
    private val JI_EXCEPTIONS = setOf("머지", "오래지", "적지", "멀지")

    private val PARTICLES = listOf(
        "이라고", "라고", "에게서", "에게", "에서", "께서", "으로",
        "부터", "까지", "마다", "조차", "처럼",
        "은", "는", "을", "를", "에", "의", "도"
    )

    private fun adnominalBefore(match: MatchResult): Boolean =
        Hangul.jongseongOf(match.groupValues[1][0]) in ADNOMINAL_JONG

    private fun rieulBefore(match: MatchResult): Boolean =
        Hangul.jongseongOf(match.groupValues[1][0]) == 'ㄹ'

    val DEFAULT: List<TextRule> = listOf(
        // 문장 부호 뒤에는 공백이 온다. ("네,저는" -> "네, 저는")
        // 앞뒤가 **둘 다 한글**일 때만 건드린다 — 숫자(1,000 / 3.14)를 피하려는 것이다.
        TextRule.of("""([가-힣][,.!?])([가-힣])""", "$1 $2", R),

        // 수관형사 + 단위 의존명사는 띄어 쓴다. ("네개야" -> "네 개야", "두명의" -> "두 명의")
        TextRule.of(
            """(${NUMERALS.joinToString("|")})(${UNITS.joinToString("|")})""",
            "$1 $2",
            R
        ) { match -> match.value !in NUMERAL_EXCEPTIONS },

        // 보조용언 '-고 있다', '-지 않다/못하다/말다' 는 띄어 쓴다.
        // ("알고있다" -> "알고 있다", "쉽지않습니다" -> "쉽지 않습니다")
        TextRule.of("""([가-힣]고)(있|없)""", "$1 $2", R),
        TextRule.of("""([가-힣]지)(않|못하|말고|말자|맙)""", "$1 $2", R) { match ->
            match.groupValues[1] !in JI_EXCEPTIONS
        },

        // 조사만 홀로 떨어져 나온 경우 앞말에 붙인다. ("학교 에서" -> "학교에서")
        // '와/과'는 명령형 '와'와, '밖에'는 명사 '밖'과 겹쳐서 뺐다.
        TextRule.of(
            """([가-힣])\s+(${PARTICLES.joinToString("|")})(?![가-힣])""",
            "$1$2",
            R
        ),

        // 의존명사 '수': 앞은 관형형 어미 ㄹ, 뒤는 '있/없'일 때만 띄운다.
        TextRule.of("""([가-힣])\s*수\s*밖에""", "$1 수밖에", R) { match ->
            rieulBefore(match) && (match.groupValues[1] + "수") !in SU_EXCEPTIONS
        },
        TextRule.of("""([가-힣])\s*수\s*(있|없)""", "$1 수 $2", R) { match ->
            rieulBefore(match) && (match.groupValues[1] + "수") !in SU_EXCEPTIONS
        },

        // 의존명사 '것'
        TextRule.of("""([가-힣])\s*것\s*같""", "$1 것 같", R) { match ->
            adnominalBefore(match) && (match.groupValues[1] + "것") !in GEOT_EXCEPTIONS
        },
        TextRule.of(
            """([가-힣])\s*것\s*(이다|이라|인가|일까|이야|입니다)""",
            "$1 것$2",
            R
        ) { match ->
            adnominalBefore(match) && (match.groupValues[1] + "것") !in GEOT_EXCEPTIONS
        },

        // 의존명사 '줄' ("할줄알아" -> "할 줄 알아")
        // '모르다'는 활용형이 갈려서('몰랐다') 어간만으로는 잡히지 않는다.
        TextRule.of(
            """([가-힣])\s*줄\s*(알|몰라|몰랐|모르|모른|모를)""",
            "$1 줄 $2",
            R,
            ::adnominalBefore
        ),

        // 의존명사 '때문'은 앞말과 늘 띄고, 뒤의 조사와는 붙는다.
        TextRule.of("""([가-힣])\s*때문""", "$1 때문", R),

        // 의존명사 '때' ("먹을때" -> "먹을 때")
        TextRule.of("""([가-힣])때""", "$1 때", R) { match ->
            adnominalBefore(match) && match.value !in TTAE_EXCEPTIONS
        }
    )
}
