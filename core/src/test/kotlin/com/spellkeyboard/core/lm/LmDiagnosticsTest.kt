package com.spellkeyboard.core.lm

import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.nio.file.Files
import kotlin.test.Test

/** 조정용 표. 주장은 없고 값만 찍는다. 문턱을 만질 때 `-i` 로 돌려 본다. */
class LmDiagnosticsTest {

    @Test
    fun `어절별 언어모델 점수와 형태소 비용`() {
        val dir = Files.createTempDirectory("lm-diag").toFile().also { it.deleteOnExit() }
        val spacer = Spacer(SpacingDictionary.open(dir))
        val lm = LanguageModel.open(dir)
        val words = listOf(
            "안녕하세요", "감사합니다", "들어가신다", "먹었어요", "했는데요", "카카오톡", "인스타그램",
            "스마트폰", "대박", "존맛탱", "개꿀잼", "한번", "그동안", "오랜만에", "이따가", "왠지",
            "어쨌든", "그러니까", "근데", "진짜", "너무너무", "할수있다", "할수", "안되요", "안돼요",
            "여기", "있는데요", "있는대요", "하세요", "하", "새요", "아버지", "아버지가", "가방에",
            "방에", "여기와", "여기와봐", "햇는대요", "조아요", "좋아요", "김철수가", "박지성",
            "갤럭시", "치킨", "먹자", "볼까", "학생입니다", "책상입니다", "선생님께", "드렸다",
            "게", "개", "세", "새", "매일", "메일", "되게", "돼지", "네가", "내가", "몇시에", "몇", "시에"
        )
        val corrector = ContextCorrector(lm, spacer)
        for (window in listOf("밥먹었어요", "제가할게요", "오늘은날씨가좋아서기분이좋다", "여기 와 봐", "아버지 가방에 들어가신다", "뭐 하 새요", "여기 있는대요", "당신이 준 만 달러로", "맥코이는 만 달러어치의", "안녕하새요", "햇는대요", "너무 조아요")) {
            println("=== $window")
            println(corrector.explain(window, LanguageModel.BOS))
        }
        println("=== 번역 입력줄에 친 문장 (실기기 화면)")
        for (chat in listOf(
            "안녕하세요", "저는 김건우입니다", "저는 김건우 입니다", "오늘 기분이 어떠세요",
            "저는 좋아요", "어떻게", "어떻게 지내세요", "어떻게 해야 하지", "어떻게 생각해",
            "안녕하세요 저는 김건우입니다", "오늘 기분이 어떠세요 저는 좋아요"
        )) {
            val out = corrector.correct(chat, LanguageModel.BOS)
            println("  " + (if (out == null) "○ $chat" else "△ $chat → $out"))
        }

        println("=== 채팅 문장 (그대로 두거나 자연스럽게 고쳐야 한다)")
        for (chat in listOf(
            "오늘 뭐해", "밥 먹었어", "어디야", "지금 가고있어", "내일 봐요", "잘자", "고마워요", "사랑해",
            "보고싶어", "알겠어요", "괜찮아요", "그래서 뭐라고 했는데", "이따가 전화할게", "집에 가는중",
            "언제 와", "존맛탱", "개웃겨", "헐 대박", "몰라 그냥", "아 진짜", "왜그래", "뭐라구요",
            "안녕히계세요", "잘지내지", "감사합니당", "넵 알겠습니다", "어디에요", "우리집에 놀러와",
            "나 지금 집이야", "너 어디 있어", "그거 진짜 맛있었어", "오늘 너무 피곤해", "내가 살께",
            "이거 얼마에요", "언제 도착해", "빨리 와줘", "미안해 늦었어", "괜찮아 천천히 와",
            "주말에 뭐 할거야", "영화 볼래", "치킨 시킬까", "배고파 죽겠어", "잘 자고 내일 봐"
        )) {
            val out = corrector.correct(chat, LanguageModel.BOS)
            println("  " + (if (out == null) "○ $chat" else "△ $chat → $out"))
        }
        println("%-12s %10s %8s  %s".format("어절", "lnP", "형태소비용", "분석"))
        for (w in words + listOf("와봐", "열어봐", "열어", "봐", "안되요", "할수", "할수있다", "먹었", "어요", "제가할게요", "제가", "할게요", "오늘은날씨가좋아서기분이좋다", "밥먹었어요", "제작자다", "좋아했거나", "의사소통만이", "연습문제는", "조용해지리라는", "남아있다")) {
            val ln = lm.lnUnigram(w)?.let { "%.2f".format(it) } ?: "모름"
            println("%-12s %10s %8d  %s".format(w, ln, spacer.cost(w), spacer.describe(w) ?: "-"))
        }
    }
}
