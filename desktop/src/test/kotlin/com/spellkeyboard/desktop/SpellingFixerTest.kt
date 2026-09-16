package com.spellkeyboard.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * [SpellingFixer] 가 지켜야 할 것들 — 음절 맞춤법.
 *
 * `안 고치는 쪽` 시험이 절반을 넘는다. 이 교정기에서 맞는 글의 음절을 하나 바꾸면
 * **뜻이 뒤집힌다** — `안아 주었어요` 가 `않아 주었어요` 가 되는 식이다. 그 줄은
 * 채점에서 0점이고, 사용자는 교정기를 끈다.
 *
 * 캐시 폴더는 [SpellEngineTest] 와 같은 곳을 쓴다.
 */
class SpellingFixerTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "spell-keyboard-test-cache")

    private fun withEngine(body: ((String) -> String) -> Unit) {
        val engine = SpellEngine(cacheDir = cacheDir, toUi = { it() })
        val ready = CountDownLatch(1)
        engine.load { ready.countDown() }
        if (!ready.await(60, TimeUnit.SECONDS)) fail("사전 여는 데 60초가 넘게 걸렸다")
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

    // ---------------------------------------------------------------- 고치는 쪽

    /**
     * 한글 맞춤법 51항. 밀어 넣는 쪽(`이`→`히`)은 말뭉치에 물어본다 —
     * `ctx.knowsWord("적당이")` 가 참이라 "모르는 낱말" 로는 못 잡는다.
     */
    @Test
    fun `하다 어근 뒤의 이를 히로 고친다`() = withEngine { fix ->
        assertEquals("진짜 적당히 하셔야지", fix("진짜 적당이 하셔야지"))
        assertEquals("천천히 하세요", fix("천천이 하세요"))
        assertEquals("확실히 해 두자", fix("확실이 해 두자"))
    }

    /** 되돌리는 쪽(`히`→`이`)은 말뭉치를 안 본다. 말뭉치는 `틈틈히`·`곰곰히` 를 안다. */
    @Test
    fun `굳은 이 부사의 히를 이로 되돌린다`() = withEngine { fix ->
        assertEquals("방을 깨끗이 치웠어요", fix("방을 깨끗히 치웠어요"))
        assertEquals("틈틈이 공부하고 있습니다", fix("틈틈히 공부하고 있습니다"))
    }

    /** `봬` 는 `뵈어` 의 준말이다. 어간 `뵈-` 에 보조사 `요` 가 바로 붙을 자리가 없다. */
    @Test
    fun `뵈요와 세여를 고친다`() = withEngine { fix ->
        assertEquals("내일 봬요", fix("내일 뵈요"))
        assertEquals("안녕하세요", fix("안녕하세여"))
    }

    /** 말뭉치가 **모르는** 어절이면 혼동 자모 하나를 갈아 끼운다. */
    @Test
    fun `모르는 어절의 혼동 자모 하나를 고친다`() = withEngine { fix ->
        assertEquals("듣는 둥 마는 둥 했다", fix("든는둥 마는둥 했다"))
        assertEquals("축하해 주세요", fix("축화해주세요"))
    }

    // ---------------------------------------------------------- 안 건드리는 쪽

    /**
     * **말뭉치가 아는 낱말은 오타가 아니다.** 이 조건이 없으면 아래 일곱이 전부
     * 뜻이 뒤집힌 채로 나간다 — 맞는 글 254문장에서 실제로 잡혔던 것들이다.
     */
    @Test
    fun `아는 낱말의 자모를 갈아 끼우지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "실을 잇는 방법을 배웠다",          // 잇는 → 있는
            "아이를 업고 병원에 다녀왔다",       // 업고 → 없고
            "아이를 안아 올렸다",              // 안아 → 않아
            "세로 방향으로 접어 주세요",         // 세로 → 새로
            "네일 아트를 받으러 갔다",          // 네일 → 내일
            "급히 나가느라 짐을 두고 왔다",      // 짐을 → 집을
            "아까 그거 어디에 뒀는지 기억나",     // 그거 → 그가
            "얼마나 춥던지 손이 곱았다",         // 곱았다 → 꼽았다
        ))
    }

    /**
     * 말뭉치가 모르는 어절이어도 **두 음절짜리 홀소리 건너뛰기**는 안 연다.
     * 짧을수록 홀소리 하나를 옮기면 딴 낱말에 그대로 내려앉는다.
     */
    @Test
    fun `두 음절 어절의 홀소리를 건너뛰지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "청자 빛깔이 은은하게 곱다",         // 청자 → 창자
            "강진 청자 축제에 다녀왔어요",
        ))
    }

    /** `재`(灰)도 이름씨라 뒤에 체언이 온다. 그래서 `재`→`제` 규칙은 안 싣는다. */
    @Test
    fun `홀로 선 재를 제로 바꾸지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "화로에 남은 재 위에 물을 뿌렸다",
            "재 가루가 옷에 묻었다",
            "창문 크기를 세로로 재 보세요",
        ))
    }

    /** 연음 되돌리기는 안 싣는다. `장마는` 은 말뭉치에 없지만 버젓한 말이다. */
    @Test
    fun `연음처럼 보이는 맞는 글을 되돌리지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "올해 장마는 예년보다 길어질 것으로 전망된다",
            "주말에 바다 보고 싶다",
        ))
    }

    /** 어근이 이름씨면 `이` 는 주격조사다. 목적어를 받는지로 가른다 (`Tuning.nounMax`). */
    @Test
    fun `이름씨 더하기 주격조사를 부사로 보지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "속이 안 좋아서 병원에 갔다",
            "사실이 아니라고 하더라",
            "그 일이 잘 풀렸으면 좋겠다",
            "성실이 최고의 무기라고 배웠다",
            "안전이 가장 중요합니다",
            "급이 다른 실력이었다",
        ))
    }

    /** `-이` 로 적는 부사는 그대로 둔다. 51항이 규칙으로 정해 놓은 자리다. */
    @Test
    fun `맞게 쓴 이 부사를 히로 밀지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "곰곰이 생각해 봤어요",
            "번번이 실패했지만 포기하지 않았다",
            "낱낱이 밝혀야 한다",
            "깊숙이 숨겨 두었다",
            "수북이 쌓인 낙엽",
            "아낌없이 주는 나무",
            "굳이 그럴 필요 없어",
        ))
    }

    /** `-히` 로 적는 부사도 그대로 둔다. */
    @Test
    fun `맞게 쓴 히 부사를 이로 되돌리지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "꾸준히 운동하고 있어요",
            "솔직히 잘 모르겠어요",
            "정확히 세 시에 만나요",
            "가만히 앉아 있었다",
        ))
    }

    /** 드문 말과 고유명사는 흔한 말과 자모 하나 거리에 널려 있다. */
    @Test
    fun `드문 말과 고유명사를 흔한 말로 바꾸지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "도커 이미지를 새로 빌드했다",
            "순창 고추장을 샀어요",
            "가야산에 다녀왔습니다",
            "햇볕을 쬔다",
            "감자를 삶아서 으깼다",
        ))
    }

    /**
     * 띄어쓰기가 만든 **조각**의 음절은 안 건드린다 — 사람이 친 어절과 끝이 맞닿아
     * 있어야 한다 ([SpellingFixer.source]). 이게 없으면 고칠 수 있던 띄어쓰기 오류가
     * 되돌릴 수 없는 음절 오류로 굳는다.
     */
    @Test
    fun `띄어쓰기가 만든 조각의 음절을 바꾸지 않는다`() = withEngine { fix ->
        val glued = listOf(
            "지하철에서졸다가내릴역을한정거장이나지나쳤다",
            "국회는본회의를열고쟁점법안세건을처리했다",
            "아까그거어디서샀는지진짜궁금하다",
        )
        for (text in glued) {
            val out = fix(text)
            assertEquals(
                text.filter { !it.isWhitespace() }, out.filter { !it.isWhitespace() },
                "붙여 쓴 글의 음절을 바꿨다: $text -> $out",
            )
        }
    }
}
