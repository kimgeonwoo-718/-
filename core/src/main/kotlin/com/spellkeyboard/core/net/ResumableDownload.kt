package com.spellkeyboard.core.net

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 큰 파일(기기 안 번역 모델, 조각 하나가 1.9GB)을 **끊겨도 이어서** 받는다.
 *
 * 폰에서 3GB 를 한 번에 끝까지 받는 일은 드물다 — 와이파이가 바뀌고, 화면이 꺼지고, 서버가 연결을 끊는다. 그래서 `.part` 파일에 쌓으며 받고,
 * 끊기면 `.part` 크기부터 `Range` 로 이어 받는다. 안드로이드에 기대지 않아서 컨테이너에서 가짜 서버로 시험한다(ResumableDownloadTest).
 *
 * - 리다이렉트는 직접 따라간다(깃허브 릴리스는 서명된 다른 주소로 넘긴다). **넘어갈 때마다 Range 를 다시 붙인다.**
 * - 서버가 Range 를 무시하고 200 으로 처음부터 보내면 `.part` 를 비우고 처음부터 받는다.
 * - 오류 없이 빈 응답만 오는 경우에 같은 자리를 무한히 도는 것을 막는다(진전이 없으면 실패로 센다).
 * - 연달아 [maxFailures] 번 실패하면 그만둔다. 성공하면 센 값을 지운다.
 */
class ResumableDownload(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val maxFailures: Int = 5,
    private val userAgent: String = "SpellKeyboard"
) {

    /**
     * [url] 을 [part] 에 [expectedBytes] 만큼 채운다.
     * @param cancelled 참이면 곧 멈추고 [IOException] 을 던진다(`.part` 는 남겨 이어 받을 수 있다)
     * @param onProgress `.part` 의 현재 크기를 알린다
     * @throws IOException 못 받았을 때
     */
    fun download(
        url: String,
        part: File,
        expectedBytes: Long,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ) {
        var failures = 0
        while (part.length() < expectedBytes) {
            if (cancelled()) throw IOException("받기를 멈췄습니다")
            try {
                val before = part.length()
                fetch(url, part, cancelled, onProgress)
                // 오류 없이 한 바이트도 못 받았다 — 같은 자리를 무한히 돌지 않게 실패로 센다.
                if (part.length() <= before && part.length() < expectedBytes) throw IOException("받은 것이 없습니다")
                failures = 0
            } catch (e: IOException) {
                if (cancelled()) throw e
                if (++failures >= maxFailures) throw IOException("받다가 끊겼습니다: ${e.message}")
                pause(BACKOFF_MS * failures)
            }
        }
        if (part.length() > expectedBytes) throw IOException("받은 파일이 예상보다 큽니다")
    }

    /** `.part` 의 끝에서부터 한 번 이어 받는다(끊기면 예외 없이 돌아오기도 한다 — 바깥 반복이 다시 부른다). */
    private fun fetch(url: String, part: File, cancelled: () -> Boolean, onProgress: (Long) -> Unit) {
        var offset = part.length()
        var target = URL(url)
        for (hop in 0..MAX_REDIRECTS) {
            val conn = open(target).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent)
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location") ?: throw IOException("넘김 주소가 없습니다")
                    target = URL(target, next)
                    continue
                }
                if (code == 416) return // 이미 다 받았거나 서버 쪽 파일이 더 짧다 — 바깥에서 진전이 없음으로 걸린다
                if (code != 200 && code != 206) throw IOException("서버가 $code 를 돌려줬습니다")
                if (code == 200 && offset > 0) {
                    // Range 를 무시하고 처음부터 보낸다. 이어 붙이면 파일이 깨진다.
                    part.delete()
                    offset = 0
                }
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(offset)
                    out.seek(offset)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancelled()) throw IOException("받기를 멈췄습니다")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            offset += n
                            onProgress(offset)
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("넘김이 너무 많습니다")
    }

    private companion object {
        const val MAX_REDIRECTS = 6
        const val BACKOFF_MS = 2000L
    }
}
