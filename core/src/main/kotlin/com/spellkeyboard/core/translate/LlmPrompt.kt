package com.spellkeyboard.core.translate

/**
 * 기기 안 번역 모델(제마 4, 약 2B)에 주는 지시문.
 *
 * 지시문은 코드가 아니라 **자원 파일**(`llm_prompt_en.txt`·`_ja`·`_zh`)에 둔다. 컨테이너의 시험 도구(tools/llmcheck)가
 * 같은 파일을 그대로 읽어 모델에 먹이므로, 시험한 글과 앱이 쓰는 글이 어긋나지 않는다.
 *
 * ## 왜 짧은가 (약 350토큰)
 *
 * 서버 AI 번역의 지시문(1,200~2,200토큰)을 폰에 그대로 얹으면 처음 한 번 계산하는 데 10~20초가 든다. 같은 시험지(시험지 B, 새 문장 60개)로
 * 재 보니 보기 여섯 개짜리 짧은 지시문이 긴 것과 같은 점수를 냈다(일본어 58.8 / 57.5, 중국어 45.3 / 45.8, 영어 64.8 / 67.7) —
 * 작은 모델은 긴 규칙을 다 못 따르고 보기를 보고 배운다. 첫 계산은 15초 → 5초로 줄었다.
 *
 * 앞부분이 늘 같아서 엔진이 계산을 한 번만 하고 재사용한다(spell_llm.cpp 의 KV 재사용). **지시문을 바꾸면 그 재사용이 첫 요청에서만 깨진다.**
 */
object LlmPrompt {

    /** [code] 는 [Phrasebook] 의 언어 코드("en"·"ja"·"zh"). 모르는 코드거나 자원이 없으면 null. */
    fun system(code: String): String? {
        if (code !in SUPPORTED) return null
        synchronized(cache) { cache[code]?.let { return it } }
        val text = LlmPrompt::class.java.getResourceAsStream("/llm_prompt_$code.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null
        synchronized(cache) { cache[code] = text }
        return text
    }

    val SUPPORTED = setOf(Phrasebook.ENGLISH, Phrasebook.JAPANESE, Phrasebook.CHINESE)

    private val cache = HashMap<String, String>()
}
