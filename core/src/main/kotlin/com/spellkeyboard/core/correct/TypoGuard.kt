package com.spellkeyboard.core.correct

import com.spellkeyboard.core.hangul.Hangul

/**
 * 오타 교정기([TypoFixer], 폰에서는 Kiwi)가 한 어절을 "이렇게 고치겠다" 고 할 때, **오타 고침이 아닌 것**을 가려낸다.
 *
 * ## 왜
 *
 * Kiwi 의 오타 변형기는 "이 글자를 저 글자로 바꾸면 사전에 있는 말이 되는가" 를 따진다. 언어모델이 모르는 어절은
 * 이 단계가 그대로 받아들이는데, **이름은 사전에 없다.** 사용자 제보: '류서인' 이 '유서인' 으로 바뀐다
 * (2026-10-09). Kiwi 를 사용자와 같은 판(v0.24.0)으로 돌려 이름 8천여 문장을 넣어 보니 세 가지가 이름을 망쳤다:
 *
 *  1. **두음법칙 쪽 첫소리 바꿈** — 류→유(류서인→유서인, 류진→유진, 류덕환→유덕환), 리→이, 녀→여. 옆 키(ㄹ↔ㅇ)이기도 해서
 *     진짜 오타처럼 보이지만, ㄹ·ㄴ 으로 시작하는 이름·외래어(류·라온·로운·리아·뉴진스)를 망치는 쪽이 훨씬 많다.
 *  2. **연음으로 받침을 다음 글자로 넘기는 바꿈** — 로운아→로우나, 나온은→나오는, 예린은→예리는, 다올아→다오라.
 *     이름 뒤에 '아·야·은·이' 가 붙으면 Kiwi 가 소리 나는 대로 다시 쓴다. 이런 오타는 없다(막는 방향은 '먹어요→머거요').
 *
 * ㅐ/ㅔ 되살림(내가↔네가)은 이 단계가 하려고 있는 일이라 건드리지 않는다. 그래서 '태오→테오', '이레야→이래야' 같은
 * 이름은 여전히 바뀔 수 있다 — 이름인지 오타인지는 사전 없이는 가를 수 없다.
 *
 * ## 값 (폰과 같은 Kiwi v0.24.0 으로 잼)
 *
 * 이름이 든 8,734 문장을 코어 + Kiwi 단계로 쳐 보니, 이 막이를 얹기 전에 바뀐 문장이 1,192개, 얹은 뒤 794개다.
 * 지켜진 398개에 **새로 바뀐 문장은 0개**다. 남은 것은 대부분 가상 이름 '류온→류원'(184)과 '에게'·'입니다' 를 앞말에 붙이는
 * 정상 교정이다. 현실적인 문장(조사·호칭이 제대로 붙은 이름 1,270문장)은 바뀐 것이 44개에서 7개로 줄었다.
 * 진짜 오타 되살림은 하나도 안 줄었다(ㅆ→ㅅ·받침빠짐·겹받침·ㅐ↔ㅔ·옆 키 8종 5,536문장, 두 글자 오타 562문장).
 *
 * "한 어절에서 바뀐 글자가 하나뿐일 때만 고친다" 는 더 넓은 막이도 대 봤는데, 이름은 10개 더 지키는 대신 두 글자
 * 오타 되살림 11개를 잃어서 뺐다.
 */
object TypoGuard {

    /** 이 고침을 하지 말아야 하면 true. [original] 은 사용자가 친 어절, [fixed] 는 오타 교정기가 만든 어절. */
    fun rejects(original: String, fixed: String): Boolean =
        flipsInitialSound(original, fixed) || movesFinalIntoNextSyllable(original, fixed)

    private val INITIAL_SOUND_PAIR: Set<Int> =
        setOf('ㄹ', 'ㄴ', 'ㅇ').map { Hangul.choseongIndex(it) }.toSet()

    private val IEUNG = Hangul.choseongIndex('ㅇ')

    /**
     * 어절의 첫 한글 글자 첫소리가 ㄹ·ㄴ·ㅇ 사이에서 바뀌었는가(류↔유, 리↔이, 녀↔여).
     * 앞의 '@'·'#'·따옴표 같은 장식은 건너뛰고 본다.
     */
    internal fun flipsInitialSound(original: String, fixed: String): Boolean {
        val i = original.indexOfFirst { Hangul.isSyllable(it) }
        if (i < 0 || i >= fixed.length) return false
        val before = Hangul.decompose(original[i]) ?: return false
        val after = Hangul.decompose(fixed[i]) ?: return false
        return before.first != after.first &&
            before.first in INITIAL_SOUND_PAIR && after.first in INITIAL_SOUND_PAIR
    }

    /**
     * 받침이 다음 글자의 첫소리로 넘어갔는가(로운아→로우나). 앞 글자는 받침을 잃고, 뒤 글자는 'ㅇ' 이던 첫소리가
     * 다른 자음이 된 꼴이다. 글자 수가 같은 때만 본다.
     */
    internal fun movesFinalIntoNextSyllable(original: String, fixed: String): Boolean {
        if (original.length != fixed.length) return false
        for (i in 0 until original.length - 1) {
            if (original[i] == fixed[i] || original[i + 1] == fixed[i + 1]) continue
            val a = Hangul.decompose(original[i]) ?: continue
            val b = Hangul.decompose(fixed[i]) ?: continue
            val na = Hangul.decompose(original[i + 1]) ?: continue
            val nb = Hangul.decompose(fixed[i + 1]) ?: continue
            if (a.third != 0 && b.third == 0 && na.first == IEUNG && nb.first != IEUNG) return true
        }
        return false
    }
}
