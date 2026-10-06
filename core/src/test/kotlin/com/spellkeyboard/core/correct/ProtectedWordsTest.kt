package com.spellkeyboard.core.correct

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedWordsTest {

    @Test
    fun `학교 줄임말은 지킨다 — 조사가 붙어도`() {
        for (word in listOf(
            "서울체고", "부산체고", "체고", "서울예고", "예고", "서울과고", "과고", "대원외고", "외고", "서울공고",
            "여고", "예중", "서울체중", "한체대", "체대", "외대", "공대", "마이스터고", "특성화고", "자사고", "특목고",
            "서울체고를", "서울체고에서", "과고생", "외고야", "서울과학고", "서울과학고등학교", "체고.", "(서울체고",
        )) {
            assertTrue(ProtectedWords.isProtected(word), "$word 는 지켜야 한다")
        }
    }

    @Test
    fun `보통 낱말은 안 걸린다`() {
        for (word in listOf("최고", "광고", "먹고", "하고", "보고", "생각하고", "사고", "한대", "학교", "고등학교", "대박", "고")) {
            assertFalse(ProtectedWords.isProtected(word), "$word 는 학교 줄임말이 아니다")
        }
    }
}
