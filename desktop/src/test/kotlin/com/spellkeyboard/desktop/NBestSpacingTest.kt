package com.spellkeyboard.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 띄어쓰기를 [NBestCorrector] 에 넘기면서 지켜야 할 것들.
 *
 * 시험 하나가 **행동 하나**를 지킨다. 이름이 곧 그 행동이다. 말뭉치 점수는 여기서
 * 재지 않는다 — 그건 `shared/harness/Eval.kt` 가 할 일이고, 여기서는 눈금이 아니라
 * 무너지면 안 되는 성질을 박아 둔다.
 *
 * 캐시 폴더는 [SpellEngineTest] 와 같은 곳을 쓴다. 따로 잡으면 62MB 를 한 벌 더 푼다.
 */
class NBestSpacingTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "spell-keyboard-test-cache")

    private fun readyEngine(): SpellEngine {
        val engine = SpellEngine(cacheDir = cacheDir, toUi = { it() })
        val done = CountDownLatch(1)
        engine.load { done.countDown() }
        if (!done.await(60, TimeUnit.SECONDS)) fail("사전 여는 데 60초가 넘게 걸렸다")
        return engine
    }

    private fun correct(engine: SpellEngine, text: String): String {
        var out: Corrected? = null
        val done = CountDownLatch(1)
        engine.correctAll(text) { out = it; done.countDown() }
        if (!done.await(60, TimeUnit.SECONDS)) fail("교정에 60초가 넘게 걸렸다")
        return (out ?: fail("결과가 없다")).result.text
    }

    /** 엔진 하나를 열어 여러 글을 넣어 본다. 사전 여는 값이 비싸 시험마다 다시 열지 않는다. */
    private fun withEngine(body: ((String) -> String) -> Unit) {
        val engine = readyEngine()
        try {
            body { text -> correct(engine, text) }
        } finally {
            engine.close()
        }
    }

    // ---------------------------------------------------------------- 사용자가 말한 두 가지

    /**
     * F1 — 바르게 붙여 쓴 복합명사를 가르지 않는다.
     *
     * 배포 전 엔진은 이것을 `개인 정보 보호 위원회가` 로 갈랐다. 사용자가 처음 말한 불만이다.
     */
    @Test
    fun `바르게 붙여 쓴 복합명사를 가르지 않는다`() = withEngine { fix ->
        val keep = listOf(
            "개인정보보호위원회가 어제 과징금을 부과했다",
            "아파트관리비를 한 달 치 밀렸더니 연체료가 붙었다",
            "부동산중개수수료는 거래 금액에 따라 달라집니다",
            "대한상공회의소가 주관하는 자격시험 접수가 시작됩니다",
            "말씀드리겠습니다만 이번 조치는 협의한 결과입니다",
        )
        for (text in keep) assertEquals(text, fix(text), "복합명사를 갈랐다")
    }

    /**
     * F2 — 붙여 쓴 한 덩어리에서 **읽는 자리**를 바르게 고른다.
     *
     * `아버지가|방에` 와 `아버지|가방에` 는 둘 다 사전에 맞는 말이라 형태소 사전만으로는
     * 못 고른다. 문장 전체를 언어모델로 다시 재야 갈린다.
     */
    @Test
    fun `붙여 쓴 문장에서 읽는 자리를 바르게 고른다`() = withEngine { fix ->
        assertEquals("아버지가 방에 들어가신다", fix("아버지가방에들어가신다"))
        // 같은 꼴인데 앞이 사람 이름이 아니면 반대로 읽어야 한다.
        assertEquals("삼촌 가방에 들어가신다", fix("삼촌가방에들어가신다"))
    }

    // ---------------------------------------------------------------- 들일 때 고친 것 두 가지

    /**
     * 고친 것 (1/2) — `preSpace` 를 `hasGluedRun` 으로 막은 것을 지킨다.
     *
     * 막지 않으면 한글 뒤에 붙은 숫자·영문 앞에 무조건 공백이 들어가, 이미 맞게 쓴
     * `아이폰15프로맥스를` 가 `아이폰 15프로 맥스를` 로 갈라진다. 말뭉치에는 이런 행이
     * 없어서 점수로는 안 잡힌다. 이 시험이 유일한 방벽이다.
     */
    @Test
    fun `이미 맞게 쓴 한글과 숫자가 붙은 어절을 가르지 않는다`() = withEngine { fix ->
        val keep = listOf(
            "아이폰15프로맥스를 어제 샀다",
            "코로나19 백신을 맞았다",
            "오전10시에 시작합니다",
            "윈도우11로 올렸다",
            "회의는 A동3층에서 합니다",
        )
        for (text in keep) assertEquals(text, fix(text), "멀쩡한 혼합 어절을 갈랐다")
    }

    /**
     * 고친 것 (1/2) 의 뒷면 — 막았다고 해서 붙여 쓴 글까지 놓치면 안 된다.
     *
     * `hasGluedRun` 이 참이면 전처리는 예전 그대로 돈다. 위 시험과 짝이다.
     */
    @Test
    fun `숫자가 섞인 붙여 쓴 글은 여전히 띄운다`() = withEngine { fix ->
        val out = fix("오늘은3시에친구를만나기로했다")
        assertTrue(out.count { it == ' ' } >= 3, "숫자가 끼자 띄어쓰기가 죽었다: $out")
        assertTrue("3시" in out, "단위를 떼어 놓았다: $out")
    }

    /**
     * 고친 것 (2/2) — 사용자가 찍은 공백을 얼리지 않는다.
     *
     * 얼리면 엔진이 아는 합치기 규칙이 통째로 죽는다. `할 수 밖에` 는 맞춤법상
     * `할 수밖에` 가 맞고, 엔진은 이것을 고칠 줄 안다.
     */
    @Test
    fun `지나치게 띄운 글을 도로 합친다`() = withEngine { fix ->
        assertEquals("할 수밖에 없다", fix("할 수 밖에 없다"))
        assertEquals("날씨가 좋았다", fix("날씨가 좋 았다"))
    }

    // ---------------------------------------------------------------- 무너지면 안 되는 것

    /** 바르게 쓴 글은 한 글자도 건드리지 않는다. 제일 비싼 실수가 이것이다. */
    @Test
    fun `바르게 쓴 글은 건드리지 않는다`() = withEngine { fix ->
        val clean = listOf(
            "오늘 날씨가 참 좋네요.",
            "내일 오전 10시에 회의가 있습니다.",
            "어제 친구를 만나서 저녁을 먹었다.",
            "자세한 내용은 아래 링크에서 확인하세요.",
        )
        for (text in clean) assertEquals(text, fix(text), "멀쩡한 글을 건드렸다")
    }

    /** 재분절은 공백만 옮긴다. 음절이 바뀌었다면 맞춤법 교정이 한 것이라야 한다. */
    @Test
    fun `띄어쓰기만 바꾸고 음절은 건드리지 않는다`() = withEngine { fix ->
        val texts = listOf(
            "회의자료준비상황을알려주세요",
            "개인정보보호위원회가과징금을부과했다",
            "점심시간에잠깐산책을했더니오후에덜졸렸다",
        )
        for (text in texts) {
            assertEquals(
                text.filter { !it.isWhitespace() },
                fix(text).filter { !it.isWhitespace() },
                "음절이 바뀌었다: ${fix(text)}",
            )
        }
    }

    /** 줄바꿈·탭·들여쓰기는 그대로 살아 있어야 한다. 붙여넣는 글은 대개 여러 줄이다. */
    @Test
    fun `줄바꿈과 들여쓰기를 지운다거나 바꾸지 않는다`() = withEngine { fix ->
        val out = fix("오늘은회의가있었다\n\t내일은쉬는날이다")
        assertTrue('\n' in out, "줄바꿈이 사라졌다: $out")
        assertTrue('\t' in out, "탭이 사라졌다: $out")
    }

    /** 한글이 없으면 손대지 않는다. URL·코드·경로가 섞인 글에서 이것이 깨지면 눈에 띈다. */
    @Test
    fun `한글이 없는 글과 부호 덩어리를 깨뜨리지 않는다`() = withEngine { fix ->
        assertEquals("hello world", fix("hello world"))
        assertEquals("", fix(""))
        for (text in listOf("전화번호는010-1234-5678이다", "원주율은3.14159이다", "가격은 12,500원입니다")) {
            val digits = { s: String -> s.filter { it.isDigit() || it in "-.," } }
            assertEquals(digits(text), digits(fix(text)), "숫자 덩어리가 깨졌다: ${fix(text)}")
        }
    }

    /**
     * 창이 "몇 군데 고쳤습니다" 를 띄우려면 교정 내역이 있어야 한다.
     *
     * [NBestCorrector.correctResult] 가 엔진의 내역을 들고 나오는지를 본다 — [NBestCorrector.correct]
     * 만 쓰면 글자만 돌아와 내역이 빈다.
     */
    @Test
    fun `고친 내역을 창에 넘겨 준다`() {
        val engine = readyEngine()
        try {
            var out: Corrected? = null
            val done = CountDownLatch(1)
            engine.correctAll("아버지가방에들어가신다") { out = it; done.countDown() }
            if (!done.await(60, TimeUnit.SECONDS)) fail("교정에 60초가 넘게 걸렸다")
            val result = (out ?: fail("결과가 없다")).result
            assertEquals("아버지가방에들어가신다", result.original, "중간 결과가 원문 자리에 들어갔다")
            assertTrue(result.changed)
            assertTrue(result.corrections.isNotEmpty(), "무엇을 고쳤는지가 비었다")
        } finally {
            engine.close()
        }
    }
}
