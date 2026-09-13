package com.spellkeyboard.core

import java.io.File

/**
 * 시험들이 같이 쓰는 사전·모델 캐시 폴더.
 *
 * ## 왜 하나를 같이 쓰나
 *
 * `SpacingDictionary.open` 과 `LanguageModel.open` 은 받은 폴더에 자원을 **푼다**.
 * 언어모델만 62MB 다. 시험마다 `createTempDirectory` 를 부르면 그 62MB 가 시험 수만큼
 * 쌓인다 — 실제로 한 세션에 **358개, 18GB** 가 남아 디스크를 채웠다.
 *
 * `deleteOnExit` 로는 안 지워진다. 그건 **빈 폴더**만 지우고, 그것도 JVM 이 곱게 끝날
 * 때만이다. 그래들 시험 JVM 은 그렇지 않을 때가 있다.
 *
 * 하나를 같이 쓰면 푸는 일도 한 번이라 시험도 빨라진다.
 */
object TestCache {

    /**
     * 시스템 임시 폴더 **밑의 고정된 이름**이다. 매번 새로 만들지 않으므로 쌓이지 않고,
     * 이미 풀려 있으면 그대로 다시 쓴다.
     */
    val dir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "spell-keyboard-test-cache")
            .also { it.mkdirs() }
    }
}
