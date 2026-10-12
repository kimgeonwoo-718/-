package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 고객센터로 의견을 보내는 일 — 사진 줄이기, 서버로 보내기, 메일로 보내기. 화면은 [FeedbackActivity].
 *
 * 서버는 `POST /v1/feedback`(server/src/feedback.js)이고, **값이 안 드는 길**이다(AI 를 부르지 않는다).
 * 받은 것은 운영자가 서버의 `/feedback` 화면에서 읽는다.
 */
object Feedback {

    /** 글 길이. 서버(feedback.js 의 MAX_TEXT)와 같다. */
    const val MAX_TEXT = 2000

    /** 사진 수. 서버(MAX_PHOTOS)와 같다. */
    const val MAX_PHOTOS = 3

    /**
     * 사진의 긴 변과 크기. 서버는 무료 등급이라 요청당 CPU 10ms, 데이터베이스 500MB(AI 한도와 같이 쓴다)라서
     * 사진을 서버에서 줄이지 않는다 — 여기서 줄여 보낸다. 긴 변 1280 이면 폰 화면 캡처의 글자가 읽힌다.
     * 서버는 base64 로 42만 자(약 315KB)까지 받는다.
     */
    private const val MAX_EDGE = 1280
    private const val MAX_BYTES = 300_000

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 보낸 결과. 화면이 사람 말로 바꾼다. */
    sealed class Result {
        object Sent : Result()
        /** 글은 받았는데 서버의 사진 자리가 다 차서 사진은 빠졌다. */
        object SentWithoutPhotos : Result()
        /** 오늘 보낼 수 있는 만큼 보냈다. */
        object Limit : Result()
        object TooLarge : Result()
        /** 사진만 보냈는데 서버의 사진 자리가 다 찼다. */
        object PhotoStorageFull : Result()
        object Network : Result()
        class Server(val reason: String) : Result()
    }

    /** 줄인 사진을 두는 곳. 보내고 나면 지운다. */
    fun photoDir(context: Context): File = File(context.cacheDir, "feedback").apply { mkdirs() }

    /**
     * 고른 사진을 긴 변 [MAX_EDGE], [MAX_BYTES] 안쪽 JPEG 로 줄여 캐시에 둔다. 못 읽거나 아무리 줄여도 크면 null.
     * 오래 걸린다(수백 ms) — 작업 스레드에서 부른다.
     */
    fun shrink(context: Context, uri: Uri): File? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2의 거듭제곱으로 먼저 거칠게 줄여 읽는다(메모리). 긴 변이 MAX_EDGE 밑으로 떨어지기 직전까지만.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        var bitmap = opaque(fit(decoded, MAX_EDGE))
        if (rotation != 0) bitmap = rotate(bitmap, rotation)

        // 화질을 내려 보고, 그래도 크면 더 작게. 캡처는 거의 첫 번째에서 끝난다.
        var bytes = jpeg(bitmap, 80)
        if (bytes.size > MAX_BYTES) bytes = jpeg(bitmap, 65)
        for (edge in intArrayOf(960, 720)) {
            if (bytes.size <= MAX_BYTES) break
            bitmap = fit(bitmap, edge)
            bytes = jpeg(bitmap, 60)
        }
        if (bytes.size > MAX_BYTES) return null

        File(photoDir(context), UUID.randomUUID().toString() + ".jpg").apply { writeBytes(bytes) }
    }.getOrNull()

    /**
     * 서버로 보낸다. 작업 스레드에서 부른다.
     *
     * **실패해도 다시 보내지 않는다.** 서버가 받고 답만 못 돌려준 것이면 같은 의견이 두 번 쌓인다. 사람이 다시 누른다.
     */
    fun send(context: Context, text: String, reply: String, photos: List<File>): Result {
        val body = buildString {
            append("{\"text\":").append(quote(text))
            if (reply.isNotEmpty()) append(",\"reply\":").append(quote(reply))
            append(",\"app\":").append(quote(appVersion(context)))
            append(",\"device\":").append(quote("${Build.MANUFACTURER} ${Build.MODEL}"))
            append(",\"os\":").append(quote(Build.VERSION.RELEASE ?: ""))
            append(",\"photos\":[")
            photos.forEachIndexed { i, file ->
                if (i > 0) append(',')
                append('"').append(Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)).append('"')
            }
            append("]}")
        }.toByteArray(Charsets.UTF_8)

        val (code, answer) = try {
            post(Prefs.serverUrl() + "/v1/feedback", Prefs.installId(context), body)
        } catch (error: Exception) {
            return Result.Network
        }
        return when (code) {
            200 -> if (answer.contains("\"photosDropped\"")) Result.SentWithoutPhotos else Result.Sent
            429 -> Result.Limit
            413 -> Result.TooLarge
            507 -> Result.PhotoStorageFull
            else -> Result.Server(errorOf(answer) ?: "HTTP $code")
        }
    }

    /**
     * 메일 앱으로 보내기 — 예전의 고객센터 그대로. 받는 사람·제목을 채우고 본문에 [text] 와 앱 버전·기기·설치 ID 를 붙인다.
     * 고객센터 메일이 빌드에 없으면 null.
     */
    fun emailIntent(context: Context, text: String = ""): Intent? {
        val to = context.getString(R.string.support_email).trim()
        if (to.isEmpty()) return null
        val footer = context.getString(
            R.string.support_body,
            appVersion(context),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE,
            Prefs.installId(context),
        )
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.support_subject))
            .putExtra(Intent.EXTRA_TEXT, text + footer)
    }

    fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()

    // --- 안쪽 --------------------------------------------------------------------

    private fun post(url: String, installId: String, body: ByteArray): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            // 길이를 미리 알려 준다 — 서버가 큰 본문을 읽기 전에 거를 수 있고, 통째로 버퍼에 두지 않는다.
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Install-Id", installId)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /** 서버가 준 오류 코드(`error.message`). 우리 서버가 만든 소문자 코드라 이스케이프가 없다. */
    private fun errorOf(text: String): String? =
        Regex("\"message\"\\s*:\\s*\"([^\"\\\\]*)\"").find(text)?.groupValues?.get(1)

    /** 문자열을 JSON 리터럴로. 의견은 여러 줄이라 줄바꿈을 살린다(AccountManager 의 quote 는 버린다). */
    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
        append('"')
    }

    /** 긴 변이 [edge] 를 넘으면 그만큼 줄인다. */
    private fun fit(source: Bitmap, edge: Int): Bitmap {
        val long = maxOf(source.width, source.height)
        if (long <= edge) return source
        val scale = edge.toFloat() / long
        return Bitmap.createScaledBitmap(
            source,
            maxOf(1, (source.width * scale).toInt()),
            maxOf(1, (source.height * scale).toInt()),
            true,
        )
    }

    /** JPEG 에는 투명이 없다. 투명한 PNG 를 그대로 바꾸면 비친 곳이 까맣게 된다 — 흰 바탕에 얹는다. */
    private fun opaque(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return flat
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /** 카메라 사진은 픽셀은 눕혀 두고 EXIF 로 "돌려서 보라" 고 적는다. 그대로 보내면 누워서 간다. */
    private fun exifRotation(stream: java.io.InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
