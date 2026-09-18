package com.spellkeyboard.desktop

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **사람이 붙여 쓴 어절**을 둘러싼 규칙들.
 *
 * 이 파일이 생긴 까닭: 사용자가 평범한 업무 메일을 넣었더니 "고칠 것이 없었습니다" 가
 * 나왔다. 사전은 답을 알고 있었는데([Spacer.space] 가 `구매 감사합니다` 를 내놓는다)
 * 우리 겹이 그것을 버리고 있었다 — 사람이 한 덩어리로 친 어절은 손대지 않는다는
 * 거부권이 너무 넓었다.
 *
 * 그 거부권을 **눈금**으로 바꾸면서([NBestCorrector.Tuning.wholeWordMargin]) 열린 자리와,
 * 열리면 안 되는 자리를 여기서 못 박는다. 시험 하나가 행동 하나를 지킨다.
 *
 * 캐시 폴더는 다른 시험들과 같은 곳을 쓴다 — 따로 잡으면 62MB 를 한 벌 더 푼다.
 */
class GluedWordTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "spell-keyboard-test-cache")

    private fun withEngine(body: ((String) -> String) -> Unit) {
        val engine = SpellEngine(cacheDir = cacheDir, toUi = { it() })
        val loaded = CountDownLatch(1)
        engine.load { loaded.countDown() }
        if (!loaded.await(60, TimeUnit.SECONDS)) fail("사전 여는 데 60초가 넘게 걸렸다")
        try {
            body { text ->
                var out: Corrected? = null
                val done = CountDownLatch(1)
                engine.correctAll(text) { out = it; done.countDown() }
                if (!done.await(60, TimeUnit.SECONDS)) fail("교정에 60초가 넘게 걸렸다")
                (out ?: fail("결과가 없다")).result.text
            }
        } finally {
            engine.close()
        }
    }

    private fun keepAll(fix: (String) -> String, texts: List<String>) {
        for (text in texts) assertEquals(text, fix(text), "맞게 쓴 글을 고쳤다")
    }

    // ------------------------------------------------------------------ 사용자가 보낸 글

    /**
     * 이 일이 시작된 문장. 예전 배포본은 **넷 다** 못 고치고 "고칠 것이 없었습니다" 를 냈다.
     *
     * 지금 고치는 것 셋: `구매감사합니다`, `빠른시일내에`(앞 경계), `발송해드리겠습니다`.
     * 못 고치는 것 하나: `배송지연이`. 까닭은 우리 겹이 아니라 사전이다 —
     * `spacer.space("배송지연이")` 가 `배송지 연이` 를 내놓고, 말뭉치에는 `배송지연` 이
     * 한 낱말로 올라 있어 수상한 자리로 뽑히지도 않는다. 눈금을 3 아래로 내리면 닿지만
     * 그때 맞는 글 손상이 22행에서 60행 너머로 뛴다 — 재 보고 안 하기로 했다.
     */
    @Test
    fun `사용자가 보낸 업무 메일의 붙임 오류를 고친다`() = withEngine { fix ->
        val out = fix(
            "안녕하세요 고객님 구매감사합니다 배송지연이 되는 점 정말 죄송합니다 " +
                "빠른시일내에 발송해드리겠습니다 항상 감사합니다"
        )
        assertEquals(
            "안녕하세요 고객님 구매 감사합니다 배송지연이 되는 점 정말 죄송합니다 " +
                "빠른 시일내에 발송해 드리겠습니다 항상 감사합니다",
            out,
        )
    }

    /** 사람이 한 덩어리로 친 **두 어절**을 이제 갈라 준다. 예전에는 넷 다 그대로 나갔다. */
    @Test
    fun `사람이 붙여 쓴 두 어절을 가른다`() = withEngine { fix ->
        assertEquals("고객님 구매 감사합니다", fix("고객님 구매감사합니다"))
        assertEquals("확인 후 회신 주시기 바랍니다", fix("확인 후 회신주시기바랍니다"))
        assertEquals("오늘 저녁에 뭐 먹을까", fix("오늘저녁에 뭐 먹을까"))
        assertEquals("주문하신 상품은 오늘 발송해 드리겠습니다", fix("주문하신 상품은 오늘 발송해드리겠습니다"))
    }

    // ------------------------------------------------------------------ 접미사 (붙여 쓴다)

    /**
     * **「체언 + 드리다」는 한 낱말이다.** `드리다` 가 접미사로 쓰인 파생어라 붙여 쓴다.
     *
     * 눈금을 열면 제일 먼저 무너지던 갈래다 — 손상 41행 가운데 30행이 이 모양이었다.
     * `보고드렸습니다` 는 사전이 `보고` 를 `보/VV + 고/EC` 로 읽어 따로 짚어 줘야 한다
     * ([NBestCorrector] 의 `NOUN_READ_AS_VERB`).
     */
    @Test
    fun `체언에 붙은 드리다를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "문의 주시면 친절하게 안내드리겠습니다",
            "자료를 공유드립니다",
            "영업부장님께 보고드렸습니다",
            "오랜만에 연락드려요",
            "확인 후 회신드립니다",
            "공항까지 데려다주신 기사님께 인사드렸습니다",
            "검토 후 말씀드리겠습니다",
        ))
    }

    /**
     * 같은 `드리다` 라도 **`-아/-어/-여` 뒤에서는 보조 용언**이라 띄는 것이 원칙이다
     * (한글맞춤법 47항). 앞 조각이 체언으로 끝나는지 하나로 가른다.
     */
    @Test
    fun `어미 뒤의 보조용언 드리다는 띄운다`() = withEngine { fix ->
        assertEquals("주문하신 상품은 오늘 발송해 드리겠습니다", fix("주문하신 상품은 오늘 발송해드리겠습니다"))
    }

    /** **「체언 + 스럽다」도 파생어다.** `드리다` 와 같은 자리에서 같은 규칙으로 막는다. */
    @Test
    fun `체언에 붙은 스럽다를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf("이 재킷은 고급스러워요", "분위기가 자연스러웠다"))
    }

    // ------------------------------------------------------------------ 눈금과 무관한 보호

    /**
     * **형태소 사전조차 한 어절로 읽는 어절은 눈금과 상관없이 막는다**
     * ([NBestCorrector.Tuning.hardVetoWhenMorphWhole]).
     *
     * 합성동사·파생어를 가르는 것이 제일 부끄러운 손상이다 — `올라 가겠습니다`,
     * `들어 가신다`. 눈금을 아무리 낮춰도 이 갈래는 열리면 안 되므로 거부권을 그대로 뒀다.
     */
    @Test
    fun `합성동사와 파생어는 눈금과 무관하게 지킨다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "형이 계단을 올라가겠습니다",
            "아버지가 방에 들어가신다",
            "들여쓰기가 문제입니다",
            "날이 밝아지면서 기온이 올랐다",
            "내일 비가 온다더라",
            "어제 본 영화는 정말 재미있었다",
            "지금 저한테 장난칩니까",
        ))
    }

    /**
     * 여덟 음절 이상 복합명사와 고유명사는 그대로다. 눈금이 **조각 수에 비례**하기 때문이다 —
     * 세 조각으로 부수려면 두 조각의 두 배를 이겨야 한다. 이 목록이 무너지면
     * [NBestCorrector.Tuning.wholeWordMargin] 을 너무 낮춘 것이다.
     */
    @Test
    fun `긴 복합명사와 고유명사를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "국립현대미술관이 새 전시를 연다",
            "개인정보보호위원회가 어제 과징금을 부과했다",
            "부동산중개수수료는 거래 금액에 따라 달라집니다",
            "대한상공회의소가 주관하는 자격시험 접수가 시작됩니다",
            "전원공급장치가 손상되면 사용하지 마십시오.",
            "자연어처리가 널리 쓰이고 있다.",
            "포항공과대학교가 새 소식을 알렸다.",
            "구조방정식모형을 사용하여 검증하였다.",
        ))
    }

    // ------------------------------------------------------------------ 억제 오라클

    /**
     * **맨엔진이 띄운 자리를 우리가 지우지 않는다.**
     *
     * 정답이 없어도 돌릴 수 있는 검사다. 코어(`CorrectionEngine`)가 공백을 찍은 자리인데
     * 우리 겹을 얹으면 사라진다면, 그것은 우리가 코어보다 나빠진 자리다. 예전 배포본은
     * 2,557행에서 269자리를 지웠고 그 가운데 152자리는 정답에도 있던 자리였다.
     *
     * 여기 박은 여덟 줄은 예전 배포본이 실제로 지우던 자리들이다. 전부 사라지면
     * 거부권이 다시 넓어진 것이다.
     */
    @Test
    fun `맨엔진이 띄운 자리를 지우지 않는다`() {
        val spacer = Spacer(SpacingDictionary.open(cacheDir))
        val lm = LanguageModel.open(cacheDir)
        val bare = CorrectionEngine().apply {
            this.spacer = spacer
            speller = Speller(spacer)
            context = ContextCorrector(lm, spacer)
        }
        withEngine { fix ->
            val rows = listOf(
                "주문번호 알려주시면 바로 확인해드리겠습니다",
                "오늘저녁에 뭐 먹을까",
                "자세한내용은 아래를 확인해 주세요",
                "기분좋았습니다",
                "수고하셨습니다 내일뵙겠습니다",
                "회의는 내일오후에 시작합니다",
                "확인 후 회신주시기바랍니다",
                "고객님 구매감사합니다",
            )
            for (row in rows) {
                val bareOut = bare.correctAll(row).text
                val ours = fix(row)
                // 음절을 바꾼 줄은 이 검사의 대상이 아니다 — 경계 자리가 어긋난다.
                if (bareOut.filterNot { it.isWhitespace() } != ours.filterNot { it.isWhitespace() }) continue
                val lost = boundaries(bareOut) - boundaries(ours)
                assertTrue(lost.isEmpty(), "맨엔진이 띄운 자리를 지웠다\n  맨 : $bareOut\n  우리: $ours")
            }
        }
    }

    /** 공백 지운 스트림에서의 경계 자리. `shared/harness/Eval.kt` 와 같은 정의다. */
    private fun boundaries(text: String): Set<Int> {
        val out = HashSet<Int>()
        var at = 0
        var pending = false
        for (c in text) {
            if (c.isWhitespace()) { pending = true; continue }
            if (pending && at > 0) out += at
            pending = false
            at++
        }
        return out
    }

    /**
     * 사전에 없는 합성용언을 가르지 않는다.
     *
     * `마음먹다` 는 사전에 낱말로 없어서 `마음 먹었지만` 으로 갈라졌다. 갈라 놓으면
     * 틀린 한국어다. 활용형마다 음절 수가 달라 눈금에 걸리기도 하고 안 걸리기도 하므로
     * 넷을 다 박아 둔다.
     */
    @Test
    fun `사전에 없는 합성용언을 가르지 않는다`() = withEngine { fix ->
        for (text in listOf(
            "마음먹었지만 실천이 어렵더라",
            "단단히 마음먹고 시작했다",
            "마음먹은 대로 되지 않았다",
            "맘먹었다 하면 끝을 본다",
        )) assertEquals(text, fix(text), "합성용언을 갈랐다")
    }
}
