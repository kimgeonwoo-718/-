package androidx.exifinterface.media

import java.io.InputStream

class ExifInterface(stream: InputStream) {
    fun getAttributeInt(tag: String, default: Int): Int = default
    companion object {
        const val TAG_ORIENTATION = "Orientation"
        const val ORIENTATION_NORMAL = 1
        const val ORIENTATION_ROTATE_90 = 6
        const val ORIENTATION_ROTATE_180 = 3
        const val ORIENTATION_ROTATE_270 = 8
    }
}
