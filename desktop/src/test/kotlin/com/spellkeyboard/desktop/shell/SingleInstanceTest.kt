package com.spellkeyboard.desktop.shell

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SingleInstanceTest {

    private val dir: File = Files.createTempDirectory("single-instance").toFile()
    private val opened = mutableListOf<SingleInstance>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun acquire(onShow: () -> Unit = {}): SingleInstanceResult =
        SingleInstance.acquire(dir, onShow).also {
            if (it is SingleInstanceResult.First) opened += it.instance
        }

    @Test
    fun `첫 번째는 자리를 잡는다`() {
        val first = acquire()
        assertIs<SingleInstanceResult.First>(first)
        assertEquals(null, first.warning)
        assertTrue(first.instance.port in 1..65535, "되돌이 포트를 열었어야 한다")
    }

    /**
     * 같은 JVM 안에서 두 번 부르면 [java.nio.channels.OverlappingFileLockException] 이
     * 던져진다 — null 이 아니라 **던진다.** 그것을 안 받으면 여기서 터진다.
     */
    @Test
    fun `두 번째는 막히고 첫 번째 창을 띄우라고 전한다`() {
        val shown = CountDownLatch(1)
        acquire { shown.countDown() }

        val second = acquire()
        assertIs<SingleInstanceResult.AlreadyRunning>(second)
        assertTrue(second.notifiedFirstInstance, "먼저 뜬 쪽에 전했어야 한다")
        assertTrue(shown.await(3, TimeUnit.SECONDS), "첫 번째의 창 띄우기가 불렸어야 한다")
    }

    @Test
    fun `세 번째도 마찬가지로 막힌다`() {
        val shown = CountDownLatch(2)
        acquire { shown.countDown() }
        acquire()
        acquire()
        assertTrue(shown.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `첫 번째가 놓으면 다음이 자리를 잡는다`() {
        val first = acquire()
        assertIs<SingleInstanceResult.First>(first)
        first.instance.close()
        opened.remove(first.instance)

        val next = acquire()
        assertIs<SingleInstanceResult.First>(next)
    }

    @Test
    fun `close 를 두 번 불러도 괜찮다`() {
        val first = acquire()
        assertIs<SingleInstanceResult.First>(first)
        first.instance.close()
        first.instance.close()
        opened.remove(first.instance)
    }

    // ---- 낡은 파일 ----

    @Test
    fun `포트 파일이 없으면 전하지 못했다고 알린다`() {
        acquire()
        File(dir, "instance.port").delete()
        val second = acquire()
        assertIs<SingleInstanceResult.AlreadyRunning>(second)
        assertFalse(second.notifiedFirstInstance)
    }

    @Test
    fun `포트 파일이 깨졌으면 전하지 못했다고 알린다`() {
        acquire()
        File(dir, "instance.port").writeText("쓰레기\n")
        val second = acquire()
        assertIs<SingleInstanceResult.AlreadyRunning>(second)
        assertFalse(second.notifiedFirstInstance)
    }

    @Test
    fun `아무도 안 듣는 포트가 적혀 있어도 멎지 않는다`() {
        acquire()
        // 닫힌 포트. 붙으러 갔다가 거절당하고 돌아와야 한다 — 매달리면 안 된다.
        File(dir, "instance.port").writeText("1\nabc\n")
        val started = System.nanoTime()
        val second = acquire()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertIs<SingleInstanceResult.AlreadyRunning>(second)
        assertFalse(second.notifiedFirstInstance)
        assertTrue(ms < 3000, "너무 오래 매달렸다: ${ms}ms")
    }

    // ---- 암호 ----

    @Test
    fun `암호가 틀리면 창을 띄워 주지 않는다`() {
        var shown = 0
        val first = acquire { shown++ }
        assertIs<SingleInstanceResult.First>(first)
        val port = first.instance.port

        // 되돌이 주소에 아무나 대고 말을 걸 수 있다. 암호 없이는 안 통해야 한다.
        val reply = Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000)
            s.soTimeout = 1000
            s.getOutputStream().write("SHOW\n".toByteArray())
            s.getOutputStream().flush()
            runCatching { s.getInputStream().read() }.getOrDefault(-1)
        }
        assertEquals(-1, reply, "암호 없는 쪽에는 대답하지 않아야 한다")
        Thread.sleep(150)
        assertEquals(0, shown)
    }

    @Test
    fun `엉뚱한 말을 걸어도 받는 실이 죽지 않는다`() {
        val shown = CountDownLatch(1)
        val first = acquire { shown.countDown() }
        assertIs<SingleInstanceResult.First>(first)

        repeat(3) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), first.instance.port), 1000)
                    s.getOutputStream().write("아무말\n".toByteArray())
                }
            }
        }
        // 그러고 나서도 제대로 된 두 번째 실행은 여전히 통해야 한다.
        assertIs<SingleInstanceResult.AlreadyRunning>(acquire())
        assertTrue(shown.await(3, TimeUnit.SECONDS), "받는 실이 죽었다")
    }
}
