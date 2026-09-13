package com.spellkeyboard.ko

import android.content.Context
import androidx.annotation.StringRes
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.spellkeyboard.core.translate.Phrasebook

/**
 * 번역 입력줄이 내놓는 언어. 원문은 늘 한국어다 — 한국어 키보드니까.
 *
 * [phrasebook] 은 ML Kit 의 [code] 와 같은 값일 가능성이 높지만, 남의 라이브러리 상수에
 * 기대면 값이 바뀔 때 관용구 표가 조용히 안 맞게 된다. 따로 적어 둔다.
 */
enum class TargetLanguage(
    val code: String,
    val phrasebook: String,
    @StringRes val label: Int
) {
    ENGLISH(TranslateLanguage.ENGLISH, Phrasebook.ENGLISH, R.string.lang_english),
    JAPANESE(TranslateLanguage.JAPANESE, Phrasebook.JAPANESE, R.string.lang_japanese),
    CHINESE(TranslateLanguage.CHINESE, Phrasebook.CHINESE, R.string.lang_chinese);

    fun next(): TargetLanguage = entries[(ordinal + 1) % entries.size]

    fun label(context: Context): String = context.getString(label)
}

/**
 * 구글 ML Kit 온디바이스 번역.
 *
 * 언어팩(약 30MB)을 처음 한 번 내려받으면 그 뒤로는 기기 안에서만 돈다 — 네트워크도,
 * 서버 비용도, AI 한도도 쓰지 않는다. 품질은 LLM 보다 딱딱하지만 타이핑을 따라갈 만큼
 * 빠르다(문장 하나에 수십 ms).
 *
 * 콜백은 전부 메인 스레드로 온다. 언어마다 클라이언트 하나를 만들어 두고 끝날 때 닫는다.
 */
class OnDeviceTranslator {

    private val clients = HashMap<TargetLanguage, Translator>()
    private val ready = HashSet<TargetLanguage>()
    private val downloading = HashSet<TargetLanguage>()

    fun isReady(target: TargetLanguage): Boolean = target in ready

    /**
     * 언어팩을 확인하고 없으면 받는다. 이미 있으면 [onReady] 를 바로 부른다.
     * 받는 중에 또 부르면 그 요청은 조용히 무시한다 — 끝나면 한 번만 알린다.
     */
    fun ensureModel(target: TargetLanguage, onReady: () -> Unit, onFailed: (Exception) -> Unit) {
        if (target in ready) {
            onReady()
            return
        }
        if (!downloading.add(target)) return
        // 와이파이를 요구하지 않는다. 30MB 한 번이고, 기다리게 하면 "번역이 안 된다" 로 보인다.
        val conditions = DownloadConditions.Builder().build()
        client(target).downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                downloading.remove(target)
                ready.add(target)
                onReady()
            }
            .addOnFailureListener { error ->
                downloading.remove(target)
                onFailed(error)
            }
    }

    fun translate(text: String, target: TargetLanguage, onResult: (String) -> Unit, onFailed: (Exception) -> Unit) {
        client(target).translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onFailed(it) }
    }

    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        ready.clear()
        downloading.clear()
    }

    private fun client(target: TargetLanguage): Translator =
        clients.getOrPut(target) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.KOREAN)
                    .setTargetLanguage(target.code)
                    .build()
            )
        }
}
