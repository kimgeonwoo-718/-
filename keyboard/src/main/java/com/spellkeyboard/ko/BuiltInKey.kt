package com.spellkeyboard.ko

/**
 * 앱에 내장된 Gemini API 키.
 *
 * ## 왜 이렇게 넣는가
 *
 * APK 에 든 문자열은 **반드시 추출된다.** 디컴파일 몇 분이면 되고, 저장소가 공개라
 * 소스에 적어 두면 그보다 빠르다. 그래서 세 겹으로 둔다.
 *
 * 1. **소스에 두지 않는다.** 빌드할 때 `GEMINI_API_KEY` 환경변수(CI 비밀값)에서 읽어
 *    `BuildConfig` 로 들어온다. 저장소에는 키가 없다.
 * 2. **바이너리 안에서 가린다.** 빌드 스크립트가 XOR 로 섞어 넣고 여기서 푼다.
 *    `strings` 한 방에 나오지 않게 하는 정도다 — 마음먹고 뒤지면 풀린다. 그래서
 * 3. **구글 쪽에서 앱에 묶는다.** 요청마다 앱 신원([AppIdentity])을 헤더로 보내고,
 *    구글 콘솔에서 키를 이 패키지·서명에만 허용해 둔다. 이게 진짜 방어다. 추출된
 *    키는 우리 앱의 서명 없이는 거절당한다.
 *
 * 2번은 시간을 벌고, 3번이 막는다. 2번만 믿으면 안 된다.
 */
object BuiltInKey {

    /** 내장 키. 빌드에 안 넣었으면 빈 문자열이다. */
    val value: String by lazy { decode(BuildConfig.GEMINI_KEY_OBF) }

    val isConfigured: Boolean get() = value.isNotEmpty()

    /**
     * 빌드 스크립트와 짝을 이루는 복호화. 바꾸면 양쪽을 같이 바꿔야 한다.
     * 마스크는 비밀이 아니다 — 소스에 있다. 가리는 것이지 잠그는 것이 아니다.
     */
    private fun decode(hex: String): String {
        if (hex.isEmpty() || hex.length % 2 != 0) return ""
        val mask = MASK.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(hex.length / 2) { i ->
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return ""
            (byte xor mask[i % mask.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private const val MASK = "spell-keyboard-2026"
}
