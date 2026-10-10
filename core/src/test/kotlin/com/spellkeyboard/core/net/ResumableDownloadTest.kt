package com.spellkeyboard.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 진짜 HTTP 서버(JDK 내장)를 띄워, 끊김·리다이렉트·Range 무시를 흉내 내며 이어 받기를 시험한다. */
class ResumableDownloadTest {

    private val data = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val dir: File = Files.createTempDirectory("dl-test").toFile()
    private var server: HttpServer? = null

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    /** [cutFirst] 번째 요청까지는 [cutAfter] 바이트만 보내고 연결을 끊는다. [ignoreRange] 면 Range 를 무시하고 200 으로 처음부터 보낸다. */
    private fun serve(cutFirst: Int = 0, cutAfter: Int = 100_000, ignoreRange: Boolean = false, status: Int? = null): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        http.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        http.createContext("/file") { ex -> respond(ex, ++requests, cutFirst, cutAfter, ignoreRange, status) }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun respond(ex: HttpExchange, n: Int, cutFirst: Int, cutAfter: Int, ignoreRange: Boolean, status: Int?) {
        if (status != null) {
            ex.sendResponseHeaders(status, -1)
            ex.close()
            return
        }
        val range = ex.requestHeaders.getFirst("Range")?.takeIf { !ignoreRange }
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        if (start >= data.size) {
            ex.sendResponseHeaders(416, -1)
            ex.close()
            return
        }
        val body = data.copyOfRange(start, data.size)
        ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
        val out = ex.responseBody
        if (n <= cutFirst) {
            out.write(body, 0, minOf(cutAfter, body.size))
            out.flush()
            ex.close() // 약속한 길이보다 적게 보내고 끊는다 — 클라이언트는 예외를 받는다
        } else {
            out.write(body)
            out.close()
        }
    }

    private fun downloader() = ResumableDownload(pause = { }, maxFailures = 4)

    @Test
    fun `한 번에 끝까지 받는다`() {
        val base = serve()
        val part = File(dir, "a.part")
        downloader().download("$base/file", part, data.size.toLong())
        assertContentEquals(data, part.readBytes())
    }

    @Test
    fun `리다이렉트를 따라간다`() {
        val base = serve()
        val part = File(dir, "a.part")
        downloader().download("$base/redirect", part, data.size.toLong())
        assertContentEquals(data, part.readBytes())
    }

    @Test
    fun `두 번 끊겨도 이어 받아 같은 파일이 된다`() {
        val base = serve(cutFirst = 2, cutAfter = 70_000)
        val part = File(dir, "a.part")
        val progress = ArrayList<Long>()
        downloader().download("$base/file", part, data.size.toLong(), onProgress = { progress += it })
        assertContentEquals(data, part.readBytes())
        assertTrue(progress.last() == data.size.toLong())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "진행은 줄지 않는다")
    }

    @Test
    fun `이전에 받다 만 파일부터 이어 받는다`() {
        val base = serve()
        val part = File(dir, "a.part")
        part.writeBytes(data.copyOfRange(0, 123_456))
        downloader().download("$base/file", part, data.size.toLong())
        assertContentEquals(data, part.readBytes())
    }

    @Test
    fun `서버가 Range 를 무시하면 처음부터 다시 받는다`() {
        val base = serve(ignoreRange = true)
        val part = File(dir, "a.part")
        part.writeBytes(data.copyOfRange(0, 50_000))
        downloader().download("$base/file", part, data.size.toLong())
        assertContentEquals(data, part.readBytes())
    }

    @Test
    fun `서버가 계속 오류를 주면 정해진 횟수 뒤에 그만둔다`() {
        val base = serve(status = 503)
        val part = File(dir, "a.part")
        val e = assertFailsWith<IOException> { downloader().download("$base/file", part, data.size.toLong()) }
        assertTrue("끊겼습니다" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `서버 파일이 더 짧으면 무한히 돌지 않고 실패한다`() {
        val base = serve()
        val part = File(dir, "a.part")
        // 기대 크기가 실제보다 크다 — 다 받은 뒤 Range 를 보내면 서버가 416 을 준다.
        assertFailsWith<IOException> { downloader().download("$base/file", part, data.size.toLong() + 10) }
    }

    @Test
    fun `취소하면 받은 데까지 남기고 멈춘다`() {
        val base = serve()
        val part = File(dir, "a.part")
        var cancel = false
        assertFailsWith<IOException> {
            downloader().download("$base/file", part, data.size.toLong(), cancelled = { cancel }, onProgress = { if (it > 40_000) cancel = true })
        }
        assertTrue(part.length() in 1 until data.size.toLong())
        // 이어서 다시 받으면 완성된다.
        downloader().download("$base/file", part, data.size.toLong())
        assertEquals(data.size.toLong(), part.length())
        assertContentEquals(data, part.readBytes())
    }
}
