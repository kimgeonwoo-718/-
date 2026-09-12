package com.spellkeyboard.ko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * 키보드 배경 사진.
 *
 * 고른 사진의 URI 를 들고 있지 않고 **앱 폴더에 복사해 둔다.** 입력기는 다른 앱 위에서
 * 도는 서비스라 사진 제공자 권한이 나중에 사라질 수 있고, 원본은 수 천만 화소라
 * 키보드가 뜰 때마다 그걸 풀면 느리다. 화면 폭에 맞게 줄여 한 번만 저장한다.
 */
object BackgroundImage {

    private const val FILE_NAME = "keyboard_background.jpg"

    /** 이보다 긴 변은 줄인다. 폰 화면 폭의 한 배 남짓이면 충분하다. */
    private const val MAX_EDGE = 1600

    private const val JPEG_QUALITY = 88

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).length() > 0

    /** 파일이 바뀌었는지 싸게 알아보는 값. 키보드가 같은 사진을 다시 풀지 않게 한다. */
    fun stamp(context: Context): Long = file(context).takeIf { it.length() > 0 }?.lastModified() ?: 0L

    fun clear(context: Context) {
        file(context).delete()
    }

    /** 고른 사진을 줄여서 저장한다. 실패하면 이전 배경은 그대로 둔다. */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false

        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val upright = if (rotation == 0) decoded else rotate(decoded, rotation)

        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        tmp.renameTo(file(context))
    }.getOrDefault(false)

    fun load(context: Context): Bitmap? {
        val target = file(context)
        if (target.length() <= 0) return null
        return runCatching { BitmapFactory.decodeFile(target.path) }.getOrNull()
    }

    /** 카메라 사진은 픽셀은 눕혀 두고 EXIF 로 "돌려서 보라" 고 적는다. 그대로 깔면 눕는다. */
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
