package com.spellkeyboard.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 맞춤법 교정기가 지켜야 할 것들.
 *
 * 시험 하나가 **행동 하나**를 지킨다. 여기서 제일 중요한 것은 고치는 쪽이 아니라
 * **안 고치는 쪽**이다 — 이 엔진에서 맞는 글의 음절을 하나 바꾸면 그 줄은 채점에서
 * 0점이 되고, 사용자는 교정기를 끈다. 그래서 맞은 꼴을 쓴 문장이 시험의 절반이다.
 *
 * 캐시 폴더는 [SpellEngineTest] 와 같은 곳을 쓴다.
 */
class ConfusionFixerTest {

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

    // ---------------------------------------------------------------- 고치는 쪽

    /** 되/돼는 '되어'로 풀리느냐로 갈린다. 언어모델은 여기서 틀린 쪽을 더 좋아한다. */
    @Test
    fun `되와 돼를 문법으로 가른다`() = withEngine { fix ->
        assertEquals("그렇게 하면 돼요", fix("그렇게 하면 되요"))
        assertEquals("안 돼서 다시 했다", fix("안 되서 다시 했다"))
        assertEquals("어제 다 됐다고 들었다", fix("어제 다 됬다고 들었다"))
    }

    /** `-지` 뒤의 보조용언은 않-/못하-/말- 뿐이다. 엔진이 스스로 내는 `앉아서` 도 되잡는다. */
    @Test
    fun `지 뒤의 안을 않으로 되잡는다`() = withEngine { fix ->
        assertEquals("배가 고프지 않아서 저녁을 걸렀다", fix("배가 고프지 안아서 저녁을 걸렀다"))
        assertEquals("그는 아무 말도 하지 않고 나갔다", fix("그는 아무 말도 하지 안고 나갔다"))
    }

    /** 왠은 왠지 하나에만 산다. 나머지는 전부 웬이다. */
    @Test
    fun `웬과 왠을 가른다`() = withEngine { fix ->
        assertEquals("왠지 마음이 놓이지 않는다", fix("웬지 마음이 놓이지 않는다"))
    }

    /** 어느 자리에서도 맞은 적이 없는 굳은 꼴들. */
    @Test
    fun `굳은 오자를 고친다`() = withEngine { fix ->
        assertEquals("며칠 뒤에 다시 만나기로 했다", fix("몇일 뒤에 다시 만나기로 했다"))
        assertEquals("새해 복 많이 받으십시오", fix("새해 복 많이 받으십시요"))
        assertEquals("약속을 잘 안 지키는 것 같아", fix("약속을 잘 안 지키는 것 같애"))
        assertEquals("제 바람대로 일이 풀렸다", fix("제 바램대로 일이 풀렸다"))
    }

    /** `어떡해`는 그 자체로 서술어라 뒤에 용언을 또 달 수 없다. */
    @Test
    fun `어떡해 뒤에 용언이 오면 어떻게로 고친다`() = withEngine { fix ->
        assertEquals("이 문제는 어떻게 해결해야 할까", fix("이 문제는 어떻해 해결해야 할까"))
    }

    /** `-던`은 관형사형, `-든`은 연결어미. 짝을 이룬 `-던지`는 고름의 `-든지`다. */
    @Test
    fun `든과 던을 자리로 가른다`() = withEngine { fix ->
        assertEquals("밥을 먹든지 말든지 네 마음대로 해라", fix("밥을 먹던지 말던지 네 마음대로 해라"))
        assertEquals("책상 위에 놓여 있던 서류가 사라졌다", fix("책상 위에 놓여 있든 서류가 사라졌다"))
    }

    // ---------------------------------------------------------------- 안 고치는 쪽

    /**
     * 짝의 **맞은 쪽**을 쓴 글은 한 글자도 안 건드린다.
     *
     * 이 줄들은 전부 교정기가 노리는 바로 그 짝을 담고 있고, 전부 맞게 쓴 것이다.
     */
    @Test
    fun `혼동쌍의 맞은 쪽을 쓴 글은 건드리지 않는다`() = withEngine { fix ->
        val keep = listOf(
            "돼지고기를 구워 먹었다.",
            "쌀 두 되를 샀다.",
            "그렇게 하면 되고 안 하면 안 된다.",
            "먼지 앉은 책상을 닦았다.",
            "의자에 앉아서 기다렸다.",
            "꽃다발을 안고 들어왔다.",
            "두 팔로 꼭 안는다.",
            "웬일로 일찍 왔니",
            "웬만하면 그냥 넘어가자.",
            "가든 말든 네 마음대로 해라.",
            "누구든 올 수 있습니다.",
            "얼마나 춥던지 손이 곱았다.",
            "내가 살던 고향은 멀다.",
            // -로서/-로써 는 문법으로 안 갈린다. 빈도로 정하면 맞게 쓴 글이 뒤집힌다.
            // Tuning.semanticRules 를 켜면 되살아나므로 못으로 박아 둔다.
            //
            // `대화로써 풀어야 한다` 는 여기 없다. 그것은 이 교정기가 아니라 core 의
            // ContextCorrector 가 바꾼다 — typoFixer 도 NBest 도 없는 맨 엔진에서
            // 재현된다(`대화로써 문제를 풀었다.` 는 멀쩡하니 뒤 어절에 달린 문제다).
            // core/ 는 폰 앱과 공유하므로 이 세션의 범위 밖이다.
            "교사로서 할 일을 다했다.",
            "대화로써 문제를 풀었다.",
            "연구로써 증명해야 한다",
            "정답을 맞혀 상품을 받았다.",
            "시간을 맞춰 도착했다.",
            "이 문제를 어떻게 풀어야 할까",
            "집에 있다가 나왔다.",
            "이따가 다시 전화할게.",
        )
        for (text in keep) assertEquals(text, fix(text), "맞게 쓴 글을 고쳤다")
    }

    /**
     * 띄어쓰기가 만든 **조각**의 음절은 건드리지 않는다.
     *
     * 이 자리는 띄어쓰기가 이미 끝난 글을 받으므로, "언어모델이 모르는 어절" 은 대개
     * 오타가 아니라 잘못 갈라진 조각이다 (`자른 지한 달` 의 `지한`). 그 음절을 바꾸면
     * 고칠 수 있던 띄어쓰기 오류가 되돌릴 수 없는 음절 오류로 굳는다. 혼동쌍은 표면형만
     * 보고 걸리므로 애초에 여기 안 걸린다 — 그것을 못으로 박아 둔다.
     */
    @Test
    fun `띄어쓰기가 만든 조각의 음절을 바꾸지 않는다`() = withEngine { fix ->
        val glued = listOf(
            "자른지한달만에머리를다시잘랐다",
            "응시료의환불은어렵습니다",
            "중소벤처기업부산하기관에문의했다",
            "쟁점법안세건을처리했다",
        )
        for (text in glued) {
            assertEquals(
                text.filter { !it.isWhitespace() },
                fix(text).filter { !it.isWhitespace() },
                "음절이 바뀌었다: ${fix(text)}",
            )
        }
    }

    /**
     * 같은 글을 두 번 눌러도 달라지지 않는다.
     *
     * [NBestCorrector] 는 엔진을 두 번 돌리므로 교정기도 제 출력 위에서 한 번 더 돈다.
     * 고정점이 아니면 버튼을 누를 때마다 글이 흔들린다.
     */
    @Test
    fun `두 번 눌러도 글이 달라지지 않는다`() = withEngine { fix ->
        val texts = listOf(
            "그렇게 하면 되요",
            "이 문제는 어떻해 해결해야 할까",
            "밥을 먹던지 말던지 네 마음대로 해라",
            "돼지고기를 구워 먹었다.",
            "아버지가방에들어가신다",
        )
        for (text in texts) {
            val once = fix(text)
            assertEquals(once, fix(once), "두 번째에 또 바뀌었다: $text")
        }
    }
}
