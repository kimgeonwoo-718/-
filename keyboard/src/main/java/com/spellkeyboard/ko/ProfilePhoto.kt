package com.spellkeyboard.ko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 구글 프로필 사진. 더보기 화면에서 이름 옆에 동그랗게 띄운다.
 *
 * 로그인할 때 한 번 받아 앱 폴더에 넣어 두고, 그 뒤로는 파일만 읽는다. 화면을 열 때마다
 * 구글에서 받아 오면 느리고, 망이 없으면 빈칸이 된다.
 *
 * **이 폰에만 있다.** 우리 서버로 보내지 않는다. 로그아웃·탈퇴하면 지운다.
 *
 * 이미지 라이브러리(Glide 등)는 안 들인다 — 사진 한 장 받자고 APK 를 키울 이유가 없다.
 */
object ProfilePhoto {

    private const val FILE_NAME = "profile_photo.png"

    /** 저장할 크기(px). 화면에는 56dp 로 뜨니 xxxhdpi(4배)에서도 넉넉하다. */
    private const val SIZE_PX = 224

    private const val TIMEOUT_MS = 10_000

    /**
     * 구글이 준 주소에서 사진을 받아 동그랗게 잘라 저장한다. **작업 스레드에서 부른다.**
     *
     * 실패해도 조용히 넘어간다 — 사진이 없으면 이름 첫 글자가 대신 뜬다. 로그인 자체를
     * 사진 때문에 실패로 만들 이유가 없다.
     */
    fun download(context: Context, url: String?): Boolean {
        clear(context)
        if (url.isNullOrBlank() || !url.startsWith("https://")) return false
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val source = try {
                if (connection.responseCode != 200) return false
                connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return false
            } finally {
                connection.disconnect()
            }
            val round = circle(source, SIZE_PX)
            file(context).outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        }.getOrDefault(false)
    }

    /** 저장해 둔 사진. 없으면 null — 그때는 이름 첫 글자를 띄운다. */
    fun load(context: Context): Bitmap? =
        file(context).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** 가운데를 정사각형으로 잘라 [size] 크기 동그라미로. 바깥은 투명하다. */
    private fun circle(source: Bitmap, size: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
