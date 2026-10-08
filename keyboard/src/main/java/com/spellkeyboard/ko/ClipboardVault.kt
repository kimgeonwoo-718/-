package com.spellkeyboard.ko

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.spellkeyboard.core.clipboard.ClipboardHistory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 클립보드 기록을 담아 두는 곳 — **암호화해서 넣고, 하루 지나면 버린다.**
 *
 * ## 왜 (2026-10-08, 윈도우 점검 ⑬)
 *
 * 예전에는 복사한 글을 설정 파일에 **평문으로, 기한 없이** 넣었다. 다른 앱은 원래 못 읽지만, 폰을 손에 넣은
 * 사람이 디버그 빌드에서 파일을 꺼내거나, 안드로이드 12 이하에서 비밀번호 관리 앱이 "민감" 표시를 안 해 줘
 * 복사한 비밀번호·인증번호가 기록에 남는 일이 있었다. 그래서 둘을 한다:
 *
 * 1. **암호화.** 열쇠는 안드로이드 키 저장소(AndroidKeyStore)에 만든다 — 열쇠 자체는 파일로 꺼낼 수 없다.
 *    AES-GCM 이라 누가 파일을 고치면 풀리지 않고 버려진다.
 * 2. **하루 뒤 삭제.** 항목마다 담은 시각을 같이 적고 [TTL_MS] 가 지나면 버린다(지보드는 1시간).
 *
 * 순서·개수·길이 같은 목록 규칙은 그대로 core 의 [ClipboardHistory] 가 정한다. 여기는 **어떻게 담느냐**만
 * 바꾼다 — 그래서 core(윈도우와 같이 쓰는 엔진)는 안 건드린다.
 *
 * 키 저장소가 고장 난 폰에서는 기록이 안 남을 뿐 키보드는 멀쩡해야 한다. 그래서 암호 쪽 실패는 전부 삼킨다.
 * **평문으로 되돌아가 저장하지는 않는다** — 이 파일이 있는 이유가 그것이다.
 */
internal class ClipboardVault(
    private val prefs: SharedPreferences,
    private val sealer: Sealer = KeystoreSealer,
    private val now: () -> Long = System::currentTimeMillis,
) : ClipboardHistory.Store {

    /** 글을 봉하고 여는 쪽. 폰에서는 [KeystoreSealer]. 못 하면 null — 던지지 않는다. */
    interface Sealer {
        fun seal(plain: String): String?
        fun open(sealed: String): String?
    }

    override fun read(): List<String> = entries().map { it.first }

    override fun write(items: List<String>) {
        val before = entries()
        val seen = before.toMap()
        val stamp = now()
        // 맨 앞이 바뀌었고 목록이 줄지 않았으면 방금 복사한 것이다(같은 글을 다시 복사해 앞으로 온 경우 포함)
        // — 그 항목만 시각을 새로 단다. 맨 앞을 **지워서** 다음 것이 앞으로 온 경우는 목록이 줄었으므로
        // 새로 단 것이 아니다. 나머지는 원래 시각을 그대로 둔다.
        val copied = items.firstOrNull() != before.firstOrNull()?.first && items.size >= before.size
        save(items.mapIndexed { i, text -> text to if (i == 0 && copied) stamp else seen[text] ?: stamp })
    }

    /** 담아 둔 것(글, 담은 시각) 중 하루가 안 지난 것. 지난 것이 있으면 그 자리에서 지운다. */
    private fun entries(): List<Pair<String, Long>> {
        migrateLegacy()
        val sealed = prefs.getString(KEY_SEALED, null) ?: return emptyList()
        // 못 열면(열쇠가 바뀌었거나 파일이 상했으면) 그 기록은 버린다 — 되살릴 길이 없다.
        val all = unpack(sealer.open(sealed) ?: return emptyList<Pair<String, Long>>().also { drop() })
        val fresh = all.filter { now() - it.second < TTL_MS }
        if (fresh.size != all.size) save(fresh)
        return fresh
    }

    private fun save(entries: List<Pair<String, Long>>) {
        if (entries.isEmpty()) return drop()
        val sealed = sealer.seal(pack(entries)) ?: return // 암호화 못 하면 안 남긴다(평문으로 두지 않는다)
        prefs.edit().putString(KEY_SEALED, sealed).apply()
    }

    private fun drop() {
        prefs.edit().remove(KEY_SEALED).apply()
    }

    /** 옛 판이 평문으로 넣어 둔 기록을 한 번 옮기고 **평문은 지운다.** 옮기지 못해도 평문은 지운다. */
    private fun migrateLegacy() {
        val legacy = prefs.getString(KEY_LEGACY, null) ?: return
        prefs.edit().remove(KEY_LEGACY).apply()
        val stamp = now()
        val texts = ClipboardHistory.decode(legacy)
        if (texts.isNotEmpty() && prefs.getString(KEY_SEALED, null) == null) save(texts.map { it to stamp })
    }

    /**
     * 안드로이드 키 저장소의 AES-256-GCM 열쇠로 봉한다. 열쇠는 처음 쓸 때 만들고 키 저장소 밖으로 안 나온다.
     * 봉한 것 = Base64(IV 12바이트 + 암호문·인증 꼬리표). 무엇이든 실패하면 null.
     */
    object KeystoreSealer : Sealer {
        override fun seal(plain: String): String? = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
        }.getOrNull()

        override fun open(sealed: String): String? = runCatching {
            val all = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()

        private fun key(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }

    companion object {
        /** 담아 두는 기간. 붙여넣기용 짧은 조각이라 하루면 충분하다. */
        const val TTL_MS = 24L * 60 * 60 * 1000

        private const val KEY_SEALED = "clipboard_sealed"
        /** 옛 판이 평문으로 쓰던 자리. 읽으면 옮기고 지운다. */
        private const val KEY_LEGACY = "clipboard"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "spell_keyboard_clipboard_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** (글, 시각) 목록을 문자열 하나로. 길이를 앞에 적는 core 의 방식을 두 겹으로 쓴다. */
        internal fun pack(entries: List<Pair<String, Long>>): String =
            ClipboardHistory.encode(
                listOf(
                    ClipboardHistory.encode(entries.map { it.first }),
                    ClipboardHistory.encode(entries.map { it.second.toString() }),
                )
            )

        internal fun unpack(raw: String): List<Pair<String, Long>> {
            val parts = ClipboardHistory.decode(raw)
            val texts = ClipboardHistory.decode(parts.getOrElse(0) { "" })
            val times = ClipboardHistory.decode(parts.getOrElse(1) { "" })
            return texts.mapIndexed { i, text -> text to (times.getOrNull(i)?.toLongOrNull() ?: 0L) }
        }
    }
}
