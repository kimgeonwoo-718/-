package com.spellkeyboard.ko

import android.content.Context
import androidx.annotation.StringRes
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.spellkeyboard.core.translate.Phrasebook
import com.spellkeyboard.core.translate.SourceLanguage

/**
 * 번역 패널에서 고르는 목표 언어. 원문은 대개 한국어지만 정해 두지 않는다 — 입력란의 일본어를 중국어로 옮기기도 한다
 * ([com.spellkeyboard.core.translate.SourceLanguage] 가 문장마다 가린다).
 *
 * [code] 는 ML Kit 에 넘기는 값, [tag] 는 **우리 쪽** 값이다(관용구 표의 열쇠이자 중계
 * 서버에 보내는 언어 이름). 지금은 둘이 같지만 남의 라이브러리 상수에 우리 규약을 묶어
 * 두면, 그쪽 값이 바뀌는 날 관용구 표와 서버 번역이 조용히 안 맞게 된다. 따로 적어 둔다.
 */
enum class TargetLanguage(
    val code: String,
    val tag: String,
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
 * 원문 언어는 문장마다 받는다(한국어·영어·일본어·중국어, [com.spellkeyboard.core.translate.SourceLanguage]). 예전에는 늘 한국어로 정해
 * 두어서 입력란의 일본어를 중국어로 옮기면 그대로 나왔다(2026-10-11 사용자 제보). 원문·목표 언어 짝마다 클라이언트 하나를 만들어 두고
 * 끝날 때 닫는다. 콜백은 전부 메인 스레드로 온다.
 */
class OnDeviceTranslator {

    private val clients = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    /** 받는 중인 짝과, 받고 나서 알릴 곳들. 같은 짝을 받는 중에 또 부르면 같이 기다린다. */
    private val downloading = HashMap<String, MutableList<Pair<() -> Unit, (Exception) -> Unit>>>()

    /** 한국어 → [target] 언어팩이 있나. 번역 입력줄(한국어)이 바로 옮길 수 있는지 본다. */
    fun isReady(target: TargetLanguage): Boolean = isReady(SourceLanguage.KOREAN, target.tag)

    /** [source] → [target] 언어팩을 이번 실행에서 확인했나. 아니면 옮기기 전에 확인하고(없으면 받고) 옮긴다. */
    fun isReady(source: String, target: String): Boolean = key(source, target) in ready

    /** 한국어 → [target] 언어팩을 확인하고 없으면 받는다. 번역 패널을 열 때 미리 부른다. */
    fun ensureModel(target: TargetLanguage, onReady: () -> Unit, onFailed: (Exception) -> Unit) =
        ensureModel(SourceLanguage.KOREAN, target.tag, onReady, onFailed)

    /**
     * [source] → [target] 언어팩을 확인하고 없으면 받는다. 이미 있으면 [onReady] 를 바로 부른다.
     * 받는 중에 또 부르면 같이 기다렸다가 끝날 때 함께 알린다.
     */
    fun ensureModel(source: String, target: String, onReady: () -> Unit, onFailed: (Exception) -> Unit) {
        val key = key(source, target)
        if (key in ready) {
            onReady()
            return
        }
        downloading[key]?.let {
            it += onReady to onFailed
            return
        }
        downloading[key] = mutableListOf(onReady to onFailed)
        // 와이파이를 요구하지 않는다. 30MB 한 번이고, 기다리게 하면 "번역이 안 된다" 로 보인다.
        val conditions = DownloadConditions.Builder().build()
        client(source, target).downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                ready.add(key)
                downloading.remove(key)?.forEach { it.first() }
            }
            .addOnFailureListener { error ->
                downloading.remove(key)?.forEach { it.second(error) }
            }
    }

    /** [text] 를 [source] 에서 [target] 으로 옮긴다. 언어팩이 없으면 받고 나서 옮긴다. */
    fun translate(text: String, source: String, target: String, onResult: (String) -> Unit, onFailed: (Exception) -> Unit) {
        ensureModel(source, target, onReady = {
            client(source, target).translate(text)
                .addOnSuccessListener { onResult(it) }
                .addOnFailureListener { onFailed(it) }
        }, onFailed = onFailed)
    }

    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        ready.clear()
        downloading.clear()
    }

    private fun key(source: String, target: String) = "$source>$target"

    private fun client(source: String, target: String): Translator =
        clients.getOrPut(key(source, target)) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(mlKitCode(source))
                    .setTargetLanguage(mlKitCode(target))
                    .build()
            )
        }

    /** 우리 언어 코드 → ML Kit 상수. 지금은 값이 같지만 남의 라이브러리 상수에 우리 규약을 묶지 않는다([TargetLanguage] 주석). */
    private fun mlKitCode(tag: String): String = when (tag) {
        Phrasebook.ENGLISH -> TranslateLanguage.ENGLISH
        Phrasebook.JAPANESE -> TranslateLanguage.JAPANESE
        Phrasebook.CHINESE -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.KOREAN
    }
}
