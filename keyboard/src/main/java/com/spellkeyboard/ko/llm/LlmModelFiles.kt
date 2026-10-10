package com.spellkeyboard.ko.llm

/**
 * 받을 모델 파일들. 릴리스 `llm-assets-1` (publish-llm-assets 일감이 올린다)에 있고, 크기와 SHA-256 을 여기 박아 둔다.
 *
 * **해시를 반드시 확인한다.** 3GB 를 남의 서버(깃허브 CDN)에서 받아 네이티브 코드가 읽는다 — 중간에 깨졌거나 바뀌었으면 쓰지 않는다.
 * 모델을 바꾸면(다른 양자화·다른 모델) 일감이 새 값을 찍으므로 그 값으로 이 목록을 통째로 바꾼다.
 */
object LlmModelFiles {

    const val RELEASE_BASE = "https://github.com/kimgeonwoo-718/-/releases/download/llm-assets-1"

    class Shard(val name: String, val bytes: Long, val sha256: String) {
        val url: String get() = "$RELEASE_BASE/$name"
    }

    /** 첫 조각이 앞이다. 엔진은 첫 조각 경로만 받고 나머지는 같은 폴더에서 찾는다. */
    val SHARDS = listOf(
        Shard("gemma-4-E2B-it-Q4_K_M-00001-of-00002.gguf", 1_914_329_056L, "48520da9b54b781fd536abf333467c3401c5e3e32072f3864d1b514cd1464443"),
        Shard("gemma-4-E2B-it-Q4_K_M-00002-of-00002.gguf", 1_192_409_440L, "b7e8c0f0c66af7ea3e406dd0ac79841d6363a6e0f05ce10f976f41b3428ec7a2")
    )

    val totalBytes: Long get() = SHARDS.sumOf { it.bytes }
}
