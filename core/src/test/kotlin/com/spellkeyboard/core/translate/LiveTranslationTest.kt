package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 가짜 시계와 가짜 번역기로 실시간 번역의 진행(초안→확정본, 엔터 대기, 낡은 결과 버리기)을 시험한다. */
class LiveTranslationTest {

    private class FakeEnv : LiveTranslation.Env {
        var source = ""
        var language = "en"
        var deviceReady = true
        var bigUsable = false
        var shownText = false
        val log = ArrayList<String>()

        // 번역 요청을 쥐고 있다가 테스트가 원하는 때 답한다.
        val deviceRequests = ArrayList<Pair<String, (TranslationPipeline.Result) -> Unit>>()
        val bigRequests = ArrayList<Pair<String, (TranslationPipeline.Result) -> Unit>>()

        var now = 0L
        private class Timer(val due: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val timers = ArrayList<Timer>()

        override fun source() = source
        override fun language() = language
        override fun deviceReady() = deviceReady
        override fun bigUsable() = bigUsable
        override fun translateDevice(source: String, language: String, onDone: (TranslationPipeline.Result) -> Unit) {
            log += "device:$source"
            deviceRequests += source to onDone
        }
        override fun translateBig(source: String, language: String, onDone: (TranslationPipeline.Result) -> Unit) {
            log += "big:$source"
            bigRequests += source to onDone
        }
        override fun cancelBig() { log += "cancelBig" }
        override fun show(result: TranslationPipeline.Result, draft: Boolean) {
            shownText = true
            log += (if (draft) "draft:" else "final:") + result.text
        }
        override fun showEmpty() { shownText = false; log += "empty" }
        override fun warnFailed() { log += "warn" }
        override fun send() { log += "send" }
        override fun hasShownText() = shownText
        override fun after(ms: Long, task: () -> Unit): LiveTranslation.Handle {
            val timer = Timer(now + ms, task)
            timers += timer
            return LiveTranslation.Handle { timer.cancelled = true }
        }

        /** 시계를 [ms] 만큼 보낸다. 그 사이 만기가 된 일을 순서대로 한다. */
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
                now = next.due
                next.cancelled = true
                next.task()
            }
            now = end
        }

        fun answerDevice(index: Int = deviceRequests.lastIndex, text: String, failed: Int = 0) =
            deviceRequests[index].second(TranslationPipeline.Result(text, failed))

        fun answerBig(index: Int = bigRequests.lastIndex, text: String, failed: Int = 0) =
            bigRequests[index].second(TranslationPipeline.Result(text, failed))
    }

    private fun setup(big: Boolean): Pair<FakeEnv, LiveTranslation> {
        val env = FakeEnv().apply { bigUsable = big }
        return env to LiveTranslation(env)
    }

    // --- ML Kit 만 있을 때 -----------------------------------------------------------------------

    @Test
    fun `큰 모델이 없으면 ML Kit 결과가 바로 확정본이다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed()
        env.advance(249)
        assertEquals(emptyList(), env.log, "디바운스 안에는 번역하지 않는다")
        env.advance(1)
        assertEquals(listOf("device:안녕"), env.log)
        env.answerDevice(text = "Hello")
        assertEquals("final:Hello", env.log.last())
        assertEquals("안녕", live.finalSource)
    }

    @Test
    fun `큰 모델이 없을 때 엔터는 확정본이 있으면 바로 보낸다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed(); env.advance(250); env.answerDevice(text = "Hello")
        env.log.clear()
        live.enter()
        assertEquals(listOf("send"), env.log)
    }

    @Test
    fun `큰 모델이 없을 때 엔터가 번역보다 먼저 오면 번역이 오는 대로 보낸다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed()
        live.enter()
        assertTrue(live.pendingEnter)
        assertEquals(listOf("device:안녕"), env.log)
        env.answerDevice(text = "Hello")
        assertEquals(listOf("device:안녕", "final:Hello", "send"), env.log)
        assertFalse(live.pendingEnter)
    }

    @Test
    fun `글이 바뀐 뒤에 온 낡은 결과는 버린다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed(); env.advance(250)
        env.source = "안녕하세요"
        env.answerDevice(0, "Hello") // 낡은 결과
        assertEquals(listOf("device:안녕"), env.log)
        assertEquals("", live.finalSource)
    }

    @Test
    fun `언어가 바뀐 뒤에 온 낡은 결과는 버린다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed(); env.advance(250)
        env.language = "ja"
        live.languageChanged()
        env.answerDevice(0, "Hello")
        assertEquals(listOf("device:안녕", "cancelBig"), env.log)
    }

    // --- 큰 모델이 있을 때 -----------------------------------------------------------------------

    @Test
    fun `ML Kit 은 초안이고 큰 모델이 확정본이다`() {
        val (env, live) = setup(big = true)
        env.source = "계산은 따로 해 주세요"
        live.changed()
        env.advance(250)
        assertEquals(listOf("cancelBig", "device:계산은 따로 해 주세요"), env.log, "큰 모델은 아직 시작하지 않는다")
        env.answerDevice(text = "Calculation is separate.")
        assertEquals("draft:Calculation is separate.", env.log.last())
        assertEquals("", live.finalSource, "초안은 확정본이 아니다")

        env.advance(699)
        assertTrue(env.bigRequests.isEmpty())
        env.advance(1)
        assertEquals("big:계산은 따로 해 주세요", env.log.last())
        env.answerBig(text = "Please pay separately.")
        assertEquals("final:Please pay separately.", env.log.last())
        assertEquals("계산은 따로 해 주세요", live.finalSource)
    }

    @Test
    fun `큰 모델이 먼저 끝나면 늦게 온 초안으로 덮지 않는다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250)       // 초안 요청
        env.advance(700)                       // 큰 모델 요청
        env.answerBig(text = "Hello there.")
        env.answerDevice(text = "Hi.")         // 늦게 온 초안
        assertEquals(listOf("final:Hello there."), env.log.filter { it.startsWith("draft") || it.startsWith("final") })
    }

    @Test
    fun `글을 계속 치면 큰 모델은 시작하지 않고 낡은 일은 거둔다`() {
        val (env, live) = setup(big = true)
        env.source = "안"
        live.changed(); env.advance(250)
        env.source = "안녕"
        live.changed(); env.advance(250)       // 700ms 가 지나기 전에 글이 또 바뀌었다
        env.advance(699)
        assertTrue(env.bigRequests.isEmpty(), "치는 중에는 큰 모델을 돌리지 않는다")
        env.advance(1)
        assertEquals(listOf("big:안녕"), env.log.filter { it.startsWith("big") })
    }

    @Test
    fun `큰 모델이 도는 중에 글이 바뀌면 그 결과는 버린다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250 + 700)
        env.source = "안녕하세요"
        live.changed()
        env.answerBig(0, "Hello")
        assertFalse(env.log.any { it.startsWith("final") })
    }

    @Test
    fun `엔터가 큰 모델보다 먼저 오면 큰 모델을 지금 시작하고 확정본이 오면 보낸다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250)
        env.answerDevice(text = "Hi.")
        env.log.clear()

        live.enter()
        assertTrue(live.pendingEnter)
        assertTrue(env.bigRequests.size == 1, "디바운스를 기다리지 않고 바로 시작")
        assertFalse(env.log.contains("send"))

        env.answerBig(text = "Hello.")
        assertEquals("send", env.log.last())
        assertEquals("final:Hello.", env.log[env.log.size - 2])
    }

    @Test
    fun `이미 큰 모델이 도는 중이면 엔터가 같은 글로 또 시작하지 않는다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250 + 700) // 큰 모델 시작됨
        live.enter()
        assertEquals(1, env.bigRequests.size)
        env.answerBig(text = "Hello.")
        assertEquals("send", env.log.last())
    }

    @Test
    fun `엔터를 누르고 9초가 지나도 확정본이 없으면 입력란의 초안을 보낸다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250)
        env.answerDevice(text = "Hi.")
        live.enter()
        env.advance(8_999)
        assertFalse(env.log.contains("send"))
        env.advance(1)
        assertEquals("send", env.log.last())
        assertFalse(live.pendingEnter)
    }

    @Test
    fun `보여 줄 번역문이 하나도 없으면 인내가 끝나도 보내지 않는다`() {
        val (env, live) = setup(big = true)
        env.deviceReady = false // ML Kit 도 아직이고 큰 모델은 답이 없다
        env.source = "안녕"
        live.changed()
        live.enter()
        env.advance(9_000)
        assertFalse(env.log.contains("send"))
        assertFalse(live.pendingEnter)
    }

    @Test
    fun `큰 모델 확정본에 못 옮긴 문장이 있으면 알린다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(950)
        env.answerBig(text = "Hello.", failed = 1)
        assertTrue(env.log.contains("warn"))
    }

    @Test
    fun `큰 모델만 되고 ML Kit 이 아직이어도 확정본이 나온다`() {
        val (env, live) = setup(big = true)
        env.deviceReady = false
        env.source = "안녕"
        live.changed(); env.advance(250)
        assertTrue(env.deviceRequests.isEmpty())
        env.advance(700)
        env.answerBig(text = "Hello.")
        assertEquals("final:Hello.", env.log.last())
    }

    // --- 비었을 때·초기화 -----------------------------------------------------------------------

    @Test
    fun `입력줄이 비면 입력란 번역문을 지우고 큰 모델 일을 거둔다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250)
        env.answerDevice(text = "Hi.")
        env.source = ""
        live.changed(); env.advance(250)
        assertEquals("empty", env.log.last())
        assertEquals("cancelBig", env.log[env.log.size - 2])
        env.advance(5_000)
        assertTrue(env.bigRequests.isEmpty(), "비었으면 큰 모델을 시작하지 않는다")
    }

    @Test
    fun `비우고 엔터를 기다리던 중이면 보낸다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed()
        live.enter()
        env.source = ""
        live.changed(); env.advance(250)
        assertEquals("send", env.log.last())
    }

    @Test
    fun `초기화하면 걸려 있던 시계가 전부 멈춘다`() {
        val (env, live) = setup(big = true)
        env.source = "안녕"
        live.changed(); env.advance(250)
        live.reset()
        env.advance(60_000)
        assertTrue(env.bigRequests.isEmpty())
        assertEquals("", live.finalSource)
        assertFalse(live.pendingEnter)
    }

    @Test
    fun `확정본을 낡았다고 표시하면 엔터가 다시 번역을 기다린다`() {
        val (env, live) = setup(big = false)
        env.source = "안녕"
        live.changed(); env.advance(250); env.answerDevice(text = "Hello")
        live.invalidateFinal()
        env.log.clear()
        live.enter()
        assertTrue(live.pendingEnter)
        assertEquals(listOf("device:안녕"), env.log)
    }
}
