package com.spellkeyboard.core.lm

import com.spellkeyboard.core.TestCache
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import kotlin.test.Test
import kotlin.test.assertTrue

/** 사람이 실제로 틀리는 것들을 지금 엔진이 고치는지 본다. 주장은 없고 표만 찍는다. */
class RealWorldTest {
    @Test
    fun `자주 틀리는 것들을 고치는지 본다`() {
        val dir = TestCache.dir
        val spacer = Spacer(SpacingDictionary.open(dir))
        val engine = CorrectionEngine().apply {
            this.spacer = spacer
            this.speller = Speller(spacer)
            this.context = ContextCorrector(LanguageModel.open(dir), spacer)
        }

        val cases = listOf(
            // 맞춤법 — 자주 틀리는 낱말
            "도데체 왜 그래" to "도대체 왜 그래",
            "어의없네 진짜" to "어이없네 진짜",
            "이거 안되요" to "이거 안 돼요",
            "몇일 후에 보자" to "며칠 후에 보자",
            "왠지 기분이 좋아" to "왠지 기분이 좋아",
            "금새 끝났어" to "금세 끝났어",
            "오랫만이야" to "오랜만이야",
            "감기 빨리 낳으세요" to "감기 빨리 나으세요",
            "설겆이 좀 해줘" to "설거지 좀 해줘",
            "일찍 일어날께" to "일찍 일어날게",
            "내일 뵈요" to "내일 봬요",
            "어떻해 이제" to "어떡해 이제",
            "잇엇는대 몰랐네" to "있었는데 몰랐네",
            "할려고 했어" to "하려고 했어",
            "역활이 뭐야" to "역할이 뭐야",
            "회의를 맞추자" to "회의를 맞추자",
            // '어제그저께'의 준말이라 'ㅈ'이 앞 음절 받침으로 남는다. 실기기에서
            // 이 낱말 하나 때문에 문단 전체가 "고칠 것 없음" 으로 나왔다.
            "어그저께 만났어" to "엊그저께 만났어",
            "귀뜸이라도 해주지" to "귀띔이라도 해주지",
            "가르키는 대로 가" to "가리키는 대로 가",
            // 받침을 통째로 빠뜨린 오타. 두벌식에서 받침은 키를 한 번 더 눌러야 해서 흔한데,
            // 편집 표에 없어서 예전에는 0.4%밖에 못 되돌렸다.
            "학교에 가서 채을 읽었다" to "학교에 가서 책을 읽었다",
            "그 사라이 그랬어" to "그 사람이 그랬어",
            "생가보다 어렵네" to "생각보다 어렵네",
            "오느 날씨가 좋다" to "오늘 날씨가 좋다",
            // 두벌식 자판에서 옆 키를 누른 오타. 폰에서 제일 흔한데 편집 표가 소리 혼동만
            // 보고 있어서 예전에는 3.0%밖에 못 되돌렸다.
            "그 사란이 그랬어" to "그 사람이 그랬어",
            "오늘 뭐 하고 있너" to "오늘 뭐 하고 있어",
            "내일 다시 연라할게" to "내일 다시 연락할게",
            "빨리 와 즈세요" to "빨리 와 주세요",
            // 띄어쓰기
            "아버지가방에 들어가신다" to "아버지가 방에 들어가신다",
            "제가할게요" to "제가 할게요",
            "그럴수도있지" to "그럴 수도 있지",
            "먹을것이없다" to "먹을 것이 없다"
        )

        var ok = 0
        for ((broken, gold) in cases) {
            val got = engine.correct(broken, LanguageModel.BOS).text
            val mark = if (got == gold) { ok++; "OK  " } else "틀림"
            println("$mark %-22s → %-24s (정답 %s)".format(broken, got, gold))
        }
        println()
        println("맞춘 것 $ok / ${cases.size}")
        // 바닥을 못 박아 둔다. 값을 흔들다 여기가 내려가면 바로 알아야 한다.
        assertTrue(ok >= FLOOR, "실사용 시험이 내려갔다: $ok / ${cases.size} (바닥 $FLOOR)")
    }

    companion object {
        /**
         * 지금 전부 맞는다. 값을 흔들다 여기가 내려가면 바로 알아야 해서 바닥을 박아 둔다.
         * 새 사례를 넣어 떨어지면 바닥을 낮추지 말고 **고쳐라** — 낮추는 순간 이 시험은
         * 아무것도 지키지 않는다.
         */
        private const val FLOOR = 31
    }
}
