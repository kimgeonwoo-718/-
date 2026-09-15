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
