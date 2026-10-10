package com.spellkeyboard.ko.llm

/**
 * 네이티브 번역 엔진(`keyboard/src/main/cpp/spell_llm.cpp`) 입구. **`:llm` 프로세스에서만 부른다** — 키보드 본 프로세스는 이 클래스를
 * 건드리지 않는다(라이브러리를 올리지도 않는다). 모델이 메모리를 많이 쓰고 네이티브 코드는 죽을 수 있어서, 죽더라도 키보드는 살아야 하기 때문이다.
 *
 * 글은 전부 UTF-8 ByteArray 다. JNI 의 문자열은 이모지(😭)를 깨뜨리는 '고친 UTF-8' 이라서 쓰지 않는다.
 * 함수 이름은 JNI 규칙대로 `Java_com_spellkeyboard_ko_llm_LlmNative_<이름>` 에 맞춰 있다 — 패키지나 이름을 바꾸면 `spell_llm_jni.cpp` 도 같이 바꾼다.
 */
object LlmNative {

    init {
        System.loadLibrary("spellllm")
    }

    /** 모델을 읽는다. 0 이면 실패. 분할 파일이면 첫 조각 경로를 준다. */
    @JvmStatic
    external fun nativeLoad(path: ByteArray, threads: Int, nCtx: Int): Long

    /** 번역 한 번. 성공하면 번역문(UTF-8), 실패하면 null — 이유는 [nativeLastError]. */
    @JvmStatic
    external fun nativeGenerate(handle: Long, system: ByteArray, user: ByteArray, maxNew: Int): ByteArray?

    /** 마지막 [nativeGenerate] 가 실패였으면 `SPELL_LLM_ERR_*` 값(음수), 아니면 0. */
    @JvmStatic
    external fun nativeLastError(handle: Long): Int

    /** 돌고 있는 [nativeGenerate] 를 멈추게 한다(다른 스레드에서 불러도 된다). */
    @JvmStatic
    external fun nativeAbort(handle: Long)

    @JvmStatic
    external fun nativeFree(handle: Long)
}
