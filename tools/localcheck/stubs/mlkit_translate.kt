package com.google.mlkit.nl.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions

object TranslateLanguage {
    const val KOREAN = "ko"
    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    const val CHINESE = "zh"
}

interface Translator : java.io.Closeable {
    fun downloadModelIfNeeded(conditions: DownloadConditions): Task<Void>
    fun translate(text: String): Task<String>
    override fun close()
}

class TranslatorOptions {
    class Builder {
        fun setSourceLanguage(code: String): Builder = this
        fun setTargetLanguage(code: String): Builder = this
        fun build(): TranslatorOptions = TranslatorOptions()
    }
}

object Translation {
    @JvmStatic fun getClient(options: TranslatorOptions): Translator = throw UnsupportedOperationException()
}
