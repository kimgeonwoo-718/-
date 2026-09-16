package com.spellkeyboard.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * [TokenSpacer] 가 지켜야 할 것들 — **이미 띄어 쓴 평범한 글**의 띄어쓰기.
 *
 * 시험 하나가 행동 하나를 지킨다. 여기서도 중요한 것은 여는 쪽이 아니라 **안 여는
 * 쪽**이다. 이 겹의 첫 판은 말뭉치 두 벌에서 손상이 0 이었는데 말뭉치 밖의 평범한
 * 한국어 254문장에 태우니 23문장을 부쉈다 — 아래 `안 건드리는 것` 시험들이 그때
 * 실제로 깨진 문장들이다. 말뭉치로 고른 규칙을 말뭉치로만 검사하면 안 보인다.
 *
 * 엔진 전체를 태워서 잰다. 이 겹만 떼어 재면 앞 단계(`NBestCorrector`)가 이미
 * 손대 놓은 글을 못 보므로, 사용자가 실제로 받는 답과 달라진다.
 *
 * 캐시 폴더는 [SpellEngineTest] 와 같은 곳을 쓴다.
 */
class TokenSpacerTest {

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

    // ---------------------------------------------------------------- 여는 쪽

    /**
     * 사용자가 실제로 친 문장. 이 파일이 생긴 까닭이다.
     *
     * 예전에는 붙여 쓴 덩어리가 없어 띄어쓰기 기계가 통째로 안 돌았고, 프로그램이
     * "고칠 것이 없었습니다" 를 냈다. 여덟 군데 중 일곱을 고친다 — `재 생일` 만
     * 못 고치고, 까닭은 [SpellingFixer] 머리말에 적어 두었다.
     */
    @Test
    fun `사용자 원문의 띄어쓰기 네 자리를 고친다`() = withEngine { fix ->
        val input = "안녕하세요 저는 김건우 입니다 오늘은 제가 무엇을 할거냐면 아니 든는둥 마는둥 " +
            "하지마세요 진짜 적당이 하셔야지 장난칩니까 무슨 천천이 하세요 아버지가 방에 들어가신다 " +
            "오늘은 재 생일입니다 축화해주세요"
        val expected = "안녕하세요 저는 김건우입니다 오늘은 제가 무엇을 할 거냐면 아니 듣는 둥 마는 둥 " +
            "하지 마세요 진짜 적당히 하셔야지 장난칩니까 무슨 천천히 하세요 아버지가 방에 들어가신다 " +
            "오늘은 재 생일입니다 축하해 주세요"
        assertEquals(expected, fix(input))
    }

    /** 관형사형 어미 뒤의 의존명사는 띄어 쓴다 (한글 맞춤법 42항). */
    @Test
    fun `관형형 뒤 의존명사를 띄운다`() = withEngine { fix ->
        assertEquals("무엇을 할 거냐면", fix("무엇을 할거냐면"))
        assertEquals("아는 만큼만 말해 주면 돼", fix("아는만큼만 말해 주면 돼"))
    }

    /** `-지 -고 -게` 뒤의 보조용언은 47항의 허용이 안 미쳐서 늘 띄어 쓴다. */
    @Test
    fun `지 고 게 뒤 보조용언을 띄운다`() = withEngine { fix ->
        assertEquals("제발 하지 마세요", fix("제발 하지마세요"))
        assertEquals("빨리 걱정하지 마", fix("빨리 걱정하지마"))
    }

    /** 47항 다만: 본용언이 합성어면 `-아/-어` 뒤도 띄어 쓴다. */
    @Test
    fun `합성 본용언 뒤 보조용언을 띄운다`() = withEngine { fix ->
        assertEquals("축하해 주세요", fix("축하해주세요"))
    }

    /** `-는 둥 마는 둥` 은 사전이 어미 하나로 삼켜 버려 꼴로 잡는다. */
    @Test
    fun `는 둥 마는 둥을 띄운다`() = withEngine { fix ->
        assertEquals("듣는 둥 마는 둥 했다", fix("듣는둥 마는둥 했다"))
    }

    /** 서술격조사 `-이다` 는 체언에 붙여 쓴다 (41항). 이 겹에서 붙이는 자리는 이것뿐이다. */
    @Test
    fun `서술격조사 이다를 앞 체언에 붙인다`() = withEngine { fix ->
        assertEquals("저는 김건우입니다", fix("저는 김건우 입니다"))
        assertEquals("제가 찾던 게 바로 그것이다", fix("제가 찾던 게 바로 그것 이다"))
    }

    // ---------------------------------------------------------- 안 건드리는 쪽

    /**
     * 사전이 `장난 칩니까` 를 내놓지만 `장난치다` 는 한 낱말이다. 이 겹이 생긴 뒤에도
     * 여기가 안 열려야 한다 — 문을 품사로 좁힌 까닭 전부가 이 한 줄이다.
     */
    @Test
    fun `한 낱말인 합성용언을 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "무슨 장난칩니까 지금",
            "어제 본 영화는 정말 재미있었다",
            "아버지가 방에 들어가신다",
            "타고난 재능이 있다",
            "먹고살기 바쁘다",
            "마지못해 승낙했다",
        ))
    }

    /**
     * `-지마` 로 끝나는 어절을 **표기만 보고** 가르면 사람 이름이 쪼개진다.
     * 왼쪽 조각이 어미로 끝났을 때만 연다: 걱정하지=(NNG,EC) 는 열리고
     * 나카지=null, 고지=(NNG,NNG), 미야지=(NNP,NNP) 는 다 닫힌다.
     */
    @Test
    fun `지마로 끝나는 이름을 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "나카지마 선수가 결승골을 넣었습니다",
            "후쿠지마 원전 소식을 들었어요",
            "고지마 감독의 신작이 나왔다",
            "미야지마 섬에 다녀왔어요",
            "가와지마 씨에게 연락드렸습니다",
        ))
    }

    /**
     * `-어다` + 주다/보다 는 사전에 한 낱말로 올라 있다. 음절 수만 보는 규칙으로는
     * 이 무리가 통째로 새어 나간다.
     */
    @Test
    fun `데려다주다 무리를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "공항까지 데려다주신 기사님께 인사드렸습니다",
            "아이를 학교까지 데려다주세요",
            "동생을 역까지 바래다줬어요",
            "창밖을 올려다봤다",
            "그분을 우러러봤다",
            "들여다봤어요",
        ))
    }

    /** `어쩌다` 가 동사라서 `-(으)ㄴ 지` 문이 열려 버렸다. 굳은 부사라 막아 둔다. */
    @Test
    fun `어쩐지를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "어쩐지 오늘따라 길이 막히더라",
            "어쩐지 기분이 좋더라",
        ))
    }

    /** `먹을거리`·`볼거리` 는 사전 표제어다 (먹을거리/NNG). */
    @Test
    fun `거리로 끝나는 한 낱말을 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "먹을거리 장터가 제일 붐볐어",
            "읽을거리가 많은 잡지다",
            "볼거리와 먹을거리가 넘쳐 난다",
        ))
    }

    /** `되는대로` 는 한 낱말인 부사고, `-고말고` 와 `-ㄴ바` 는 어미다. */
    @Test
    fun `한 낱말인 부사와 어미를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "되는대로 아무거나 골랐다",
            "그렇고말고 네 말이 맞아",
            "살펴본바 문제가 없었습니다",
            // 처음 실은 가드는 right 가 정확히 `말고` 일 때만 닫혀서 `-요` 가 붙은 꼴이 샜다.
            "그렇고말고요",
        ))
        // 여기 없는 것: 좋고말고, 알고말고요, 먹고말고요. 셋 다 맨 엔진이 쪼갠다
        // (CorrectionEngine 에 spacer+speller+context 만 물린 상태에서 재현된다).
        // core/ 는 폰 앱과 공유하므로 이 세션의 범위 밖이다. 우리 겹은 보태지도 빼지도
        // 못한다 — 넣기만 하는 겹이라 엔진이 넣은 공백을 되돌릴 수 없다.
    }

    /**
     * `-고말고` 를 막는다고 보조용언 `말다` 까지 막으면 안 된다.
     *
     * 갈리는 자리는 앞 어미다 — `-고` 뒤면 종결어미라 붙이고, `-지` 뒤면 보조용언이라 띈다.
     */
    @Test
    fun `지 뒤의 보조용언 말다는 그대로 띄운다`() = withEngine { fix ->
        assertEquals("하지 말고 기다려", fix("하지말고 기다려"))
        assertEquals("울지 말고 웃어", fix("울지말고 웃어"))
        assertEquals("가지 말고 있어", fix("가지말고 있어"))
    }

    /** 어미 `-는데 -ㄹ게 -ㄹ뿐더러` 와 체언 뒤 조사 `뿐`·`밖에` 는 붙여 쓴다. */
    @Test
    fun `어미와 체언 뒤 조사를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "가는데 비가 왔다",
            "없는데 어떡하지",
            "전화할게 이따가",
            "될뿐더러 값도 싸다",
            "그럴 수밖에 없었다",
            "그것뿐이야",
        ))
    }

    /** `-이/-히` 부사의 `이` 를 의존명사로 보면 `가만 이` 가 나온다. 목록에서 뺐다. */
    @Test
    fun `이히 부사를 가르지 않는다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "방을 깨끗이 치웠어요",
            "틈틈이 공부하고 있습니다",
            "가만히 앉아 있었다",
            "일일이 확인하기가 어렵다",
        ))
    }

    /** `-이다` 가 아닌 것을 앞말에 붙이면 안 된다. 목록은 활용형을 통째로 맞대어 본다. */
    @Test
    fun `이로 시작할 뿐인 낱말은 안 붙인다`() = withEngine { fix ->
        keepAll(fix, listOf(
            "이다음에 다시 얘기하자",
            "회의 이후에 뵙겠습니다",
            "사과 이야기를 꺼냈다",
            "그 이력을 정리했어요",
        ))
    }

    // ------------------------------------------------------------ 글 모양 보존

    /** 줄바꿈·탭·앞뒤 공백을 하나도 잃지 않는다. 공백 배치 말고는 한 글자도 안 바꾼다. */
    @Test
    fun `줄바꿈과 부호를 잃지 않는다`() = withEngine { fix ->
        for (text in listOf("", " ", "\n", "  \n\t ", "줄1\n줄2\r\n줄3", "  앞뒤 공백  ", "!!!", "123")) {
            val out = fix(text)
            assertEquals(
                text.count { it == '\n' }, out.count { it == '\n' },
                "줄바꿈을 잃었다: ${text.replace("\n", "\\n")}",
            )
            assertEquals(
                text.filter { !it.isWhitespace() }, out.filter { !it.isWhitespace() },
                "공백 아닌 글자가 달라졌다: ${text.replace("\n", "\\n")}",
            )
        }
    }

    /**
     * 줄바꿈을 사이에 둔 자리는 안 붙인다. 붙이는 문은 **공백 한 칸**일 때만 연다.
     */
    @Test
    fun `줄바꿈 너머로는 안 붙인다`() = withEngine { fix ->
        assertEquals("저는 김건우\n입니다", fix("저는 김건우\n입니다"))
    }
}
