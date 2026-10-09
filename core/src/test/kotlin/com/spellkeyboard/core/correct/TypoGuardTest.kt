package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypoGuardTest {

    @Test
    fun `첫소리를 ㄹ·ㄴ·ㅇ 사이에서 바꾸는 고침은 막는다 — 이름을 망친다`() {
        for ((from, to) in listOf(
            "류서인" to "유서인", "류진" to "유진", "류덕환" to "유덕환", "류하준" to "유하준", "류아" to "유아",
            "리하" to "이하", "라온" to "아온", "로운" to "오운", "녀석" to "여석", "뉴진스" to "유진스",
            // 장식이 앞에 붙어도 첫 한글 글자를 본다
            "@류진" to "@유진", "\"류서인" to "\"유서인",
            // 거꾸로도 막는다 — 대칭이다
            "유리" to "류리",
        )) {
            assertTrue(TypoGuard.rejects(from, to), "$from → $to 는 막아야 한다")
        }
    }

    @Test
    fun `받침을 다음 글자로 넘기는 연음 고침은 막는다`() {
        for ((from, to) in listOf(
            "로운아" to "로우나", "나온은" to "나오는", "예린은" to "예리는", "다올아" to "다오라", "해든아" to "해드나",
            "리온이야" to "리오니야", "이솔아" to "이소라", "먹어요" to "머거요",
        )) {
            assertTrue(TypoGuard.rejects(from, to), "$from → $to 는 막아야 한다")
        }
    }

    @Test
    fun `진짜 오타 고침은 막지 않는다`() {
        for ((from, to) in listOf(
            // ㅐ/ㅔ 되살림 — 이 단계가 하려고 있는 일이다
            "네가" to "내가", "하예" to "하얘", "됬어요" to "됐어요",
            // 받침 되살림
            "머었어" to "먹었어", "괜찬아" to "괜찮아", "업어요" to "없어요", "햇어" to "했어",
            // 연음을 **되돌리는** 쪽은 정상이다
            "머거요" to "먹어요", "일거요" to "읽어요",
            // 첫소리가 ㄹ·ㄴ·ㅇ 이 아닌 쪽으로 바뀌는 것
            "하람" to "사람", "다온" to "따온", "스랑" to "사랑",
            // 첫 글자가 같고 뒤 글자만 바뀐다
            "류서인" to "류서윤", "유서인" to "유서인",
        )) {
            assertFalse(TypoGuard.rejects(from, to), "$from → $to 는 막으면 안 된다")
        }
    }

    @Test
    fun `글자 수가 다르거나 한글이 아니면 연음 판정을 하지 않는다`() {
        assertFalse(TypoGuard.rejects("", ""))
        assertFalse(TypoGuard.rejects("abc", "abd"))
        assertFalse(TypoGuard.rejects("로운아", "로우나요"))
        assertFalse(TypoGuard.rejects("123", "124"))
        // 첫 글자가 한글이 아니어도 첫 한글 글자를 본다
        assertTrue(TypoGuard.rejects("#류진", "#유진"))
        // 고친 쪽이 한글 글자가 아니면(사라졌으면) 판정하지 않는다
        assertFalse(TypoGuard.rejects("류진", "ㅠ"))
    }
}
