package com.spellkeyboard.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 화면 없이 도는 시험만 둔다. 창이 실제로 뜨는지는 사람 PC 에서만 알 수 있다.
 *
 * 캐시 폴더는 `core` 시험들이 쓰는 그 폴더를 같이 쓴다. 따로 잡으면 62MB 를 한 벌 더
 * 풀게 된다.
 */
class SpellEngineTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "spell-keyboard-test-cache")

    /** 시험은 EDT 를 거치지 않는다. 부른 자리에서 그대로 받는다. */
    private fun engine() = SpellEngine(cacheDir = cacheDir, toUi = { it() })

    private fun readyEngine(): SpellEngine {
        val engine = engine()
        val done = CountDownLatch(1)
        engine.load { done.countDown() }
        if (!done.await(60, TimeUnit.SECONDS)) fail("사전 여는 데 60초가 넘게 걸렸다")
        return engine
    }

    private fun correct(engine: SpellEngine, text: String): Corrected {
        var out: Corrected? = null
        val done = CountDownLatch(1)
        engine.correctAll(text) { out = it; done.countDown() }
        if (!done.await(60, TimeUnit.SECONDS)) fail("교정에 60초가 넘게 걸렸다")
        return out ?: fail("결과가 없다")
    }

    @Test
    fun `사전과 언어모델이 올라오면 문맥까지 준비된다`() {
        val engine = readyEngine()
        try {
            val state = engine.state
            if (state !is EngineState.Ready) fail("준비가 안 됐다: $state")
            assertNull(state.trouble, "사전을 여는 데 문제가 있었다")
            assertEquals(EngineLevel.CONTEXT, state.level)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `붙여 쓴 글을 띄어 쓴다`() {
        val engine = readyEngine()
        try {
            val corrected = correct(engine, "아버지가방에들어가신다")
            assertTrue(corrected.result.changed, "고치지 않았다: ${corrected.result.text}")
            assertTrue(
                corrected.result.text.contains(' '),
                "띄어쓰기가 하나도 안 들어갔다: ${corrected.result.text}",
            )
            assertTrue(corrected.result.corrections.isNotEmpty(), "무엇을 고쳤는지가 비었다")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `한글이 없는 글은 건드리지 않는다`() {
        val engine = readyEngine()
        try {
            val corrected = correct(engine, "hello world")
            assertEquals("hello world", corrected.result.text)
            assertTrue(!corrected.result.changed)
            assertTrue(corrected.result.corrections.isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `엔진 점검이 전부 통과한다`() {
        val engine = readyEngine()
        try {
            var failures: List<String>? = null
            val done = CountDownLatch(1)
            engine.selfTest { failures = it; done.countDown() }
            if (!done.await(60, TimeUnit.SECONDS)) fail("점검에 60초가 넘게 걸렸다")
            assertEquals(emptyList(), failures, "점검에서 떨어진 것이 있다")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `사전이 올라오기 전에 요청해도 줄을 서서 처리된다`() {
        val engine = engine()
        try {
            // load 를 부르자마자, 끝나기를 기다리지 않고 교정을 요청한다.
            val loaded = CountDownLatch(1)
            engine.load { loaded.countDown() }
            val corrected = correct(engine, "아버지가방에들어가신다")

            // 교정 결과가 돌아온 시점에 사전 열기는 이미 끝나 있어야 한다.
            // 기다리지 않고 재는 것이 요점이다 — 여기서 기다리면 순서를 못 본다.
            assertTrue(
                loaded.await(0, TimeUnit.SECONDS),
                "교정이 사전 열기를 앞질렀다 — 줄서기가 깨졌다",
            )
            assertTrue(corrected.result.text.contains(' '), "사전이 붙기 전 결과가 나왔다")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `숫자가 섞여 있어도 띄어쓰기가 들어간다`() {
        // Spacer_space 는 한글 아닌 글자가 끼면 null 을 준다. 손질 없이 넣으면 공백이
        // 하나도 안 들어간다 — 붙여넣는 글의 대부분이 여기 해당한다.
        val engine = readyEngine()
        try {
            val corrected = correct(engine, "오늘은3시에친구를만나기로했다")
            assertTrue(
                corrected.result.text.count { it == ' ' } >= 3,
                "숫자가 끼자 띄어쓰기가 죽었다: ${corrected.result.text}",
            )
            // '3시' 는 붙어 있어야 한다. 숫자 뒤의 한글은 단위다.
            assertTrue("3시" in corrected.result.text, "단위를 떼어 놓았다: ${corrected.result.text}")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `문장이 여럿이어도 뒤 문장까지 띄어진다`() {
        val engine = readyEngine()
        try {
            val corrected = correct(engine, "어제는하루종일집에있었다.밖에비가많이왔기때문이다.")
            val tail = corrected.result.text.substringAfter('.')
            assertTrue(
                tail.count { it == ' ' } >= 3,
                "첫 문장만 띄우고 뒤는 붙은 채로 뒀다: ${corrected.result.text}",
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `바르게 띄어 쓴 글은 건드리지 않는다`() {
        val engine = readyEngine()
        try {
            val clean = listOf(
                "오늘 날씨가 참 좋네요.",
                "내일 오전 10시에 회의가 있습니다.",
                "어제 친구를 만나서 저녁을 먹었다.",
            )
            for (text in clean) {
                val corrected = correct(engine, text)
                assertEquals(text, corrected.result.text, "멀쩡한 글을 건드렸다")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `숫자 문자열을 깨뜨리지 않는다`() {
        val engine = readyEngine()
        try {
            // 전처리가 부호에까지 공백을 넣으면 이런 것들이 깨진다.
            for (text in listOf("전화번호는010-1234-5678이다", "원주율은3.14159이다", "가격은 12,500원입니다")) {
                val corrected = correct(engine, text)
                val digitsOnly = { s: String -> s.filter { it.isDigit() || it == '-' || it == '.' || it == ',' } }
                assertEquals(
                    digitsOnly(text),
                    digitsOnly(corrected.result.text),
                    "숫자 덩어리가 깨졌다: ${corrected.result.text}",
                )
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `두 번 돌려도 결과는 사용자가 넣은 원문을 기준으로 나온다`() {
        val engine = readyEngine()
        try {
            val original = "오늘은3시에친구를만나기로했다"
            val corrected = correct(engine, original)
            assertEquals(original, corrected.result.original, "중간 결과가 원문 자리에 들어갔다")
            assertTrue(corrected.result.changed)
            assertTrue(corrected.guessedSpacing, "붙여 쓴 글인데 추측 표시가 안 섰다")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `띄어쓰기가 멀쩡한 글에는 추측 표시를 세우지 않는다`() {
        val engine = readyEngine()
        try {
            val corrected = correct(engine, "오늘 날씨가 참 좋네요.")
            assertTrue(!corrected.guessedSpacing, "평범한 글에 추측 표시가 섰다")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `교정은 언제나 같은 스레드 하나에서 돈다`() {
        val engine = readyEngine()
        try {
            val threads = mutableSetOf<String>()
            val done = CountDownLatch(5)
            repeat(5) {
                engine.correctAll("할수있다") {
                    // toUi 가 { it() } 이므로 콜백은 일한 스레드에서 그대로 불린다.
                    synchronized(threads) { threads += Thread.currentThread().name }
                    done.countDown()
                }
            }
            if (!done.await(60, TimeUnit.SECONDS)) fail("교정 다섯 번이 60초를 넘겼다")
            assertEquals(setOf(SpellEngine.WORKER_NAME), threads)
        } finally {
            engine.close()
        }
    }
}
