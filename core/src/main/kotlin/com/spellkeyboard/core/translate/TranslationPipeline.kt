package com.spellkeyboard.core.translate

/**
 * 한국어 글을 **기기 번역기**로 옮기는 한 벌의 절차.
 *
 * 입력줄의 실시간 미리보기와 '전체번역' 이 같은 길을 탄다. 예전에는 서비스 안에 풀어 적혀 있어서
 * 두 번째 쓰임새(전체번역)를 만들려면 복사해야 했다 — 그러면 한쪽만 고치다 어긋난다.
 *
 * ## 순서
 *
 * 1. [SentenceSplitter] 로 문장마다 자른다(번역기는 여러 문장을 한 번에 주면 가운데를 삼킨다).
 * 2. 문장마다 [ChatText] 로 다듬는다(마침표 붙이기, 'ㅋㅋ' 떼기, 늘임 줄이기).
 * 3. [Phrasebook] 에 있으면 사람이 옮겨 둔 것을 쓴다.
 * 4. 없으면 [Engine] 이 옮긴다.
 * 5. 문장마다 부호·감탄을 도로 붙이고 이어 붙인다.
 *
 * 번역은 [memory] 에 문장 단위로 기억한다 — 끝에만 글자가 붙는 입력줄에서 앞 문장을 다시 옮기지 않으려는 것이고,
 * 번역기가 조금씩 다른 답을 내서 앞쪽 영어가 흔들리는 것도 막는다. 언어를 바꾸면 [memory] 를 비운다.
 */
class TranslationPipeline(
    private val splitter: SentenceSplitter,
    val memory: TranslationMemory = TranslationMemory()
) {

    /** 한 문장을 옮기는 번역기. 콜백은 한 번만 부른다. */
    fun interface Engine {
        fun translate(text: String, onResult: (String) -> Unit, onFailed: (Exception) -> Unit)
    }

    /** @param failed 못 옮겨서 원문을 그대로 둔 문장 수 */
    class Result(val text: String, val failed: Int)

    /**
     * [source] 를 [language]([Phrasebook] 의 언어 코드)로 옮긴다. [onDone] 은 정확히 한 번 부른다.
     * 번역기가 비동기여도 된다 — 모든 문장이 끝났을 때 부른다.
     */
    fun translate(source: String, language: String, engine: Engine, onDone: (Result) -> Unit) {
        val sentences = splitter.split(source)
        var failed = 0

        val pending = ArrayList<String>()
        for (sentence in memory.missing(sentences)) {
            val normalized = ChatText.normalize(sentence)
            if (normalized.core.isEmpty()) {
                // 'ㅋㅋ' 만 있는 문장. 옮길 말이 없으니 감탄만 남긴다.
                memory.remember(sentence, normalized.emphasis?.let { Phrasebook.emphasis(it, language) }.orEmpty())
                continue
            }
            val fromBook = Phrasebook.lookup(normalized.core, language)
            if (fromBook != null) {
                memory.remember(sentence, Phrasebook.decorate(fromBook, normalized, language))
                continue
            }
            pending += sentence
        }
        if (pending.isEmpty()) {
            onDone(Result(memory.assemble(sentences), 0))
            return
        }

        var remaining = pending.size
        for (sentence in pending) {
            val normalized = ChatText.normalize(sentence)
            engine.translate(
                normalized.core + normalized.ending,
                onResult = { result ->
                    // 늦게 온 결과라도 기억은 해 둔다. 다음 요청이 그걸 쓴다.
                    memory.remember(sentence, Phrasebook.decorate(result, normalized, language))
                    if (--remaining == 0) onDone(Result(memory.assemble(sentences), failed))
                },
                onFailed = {
                    // 못 옮긴 문장은 원문을 그대로 둔다. 그 자리가 비면 글이 사라진 것처럼 보인다.
                    memory.remember(sentence, sentence)
                    failed++
                    if (--remaining == 0) onDone(Result(memory.assemble(sentences), failed))
                }
            )
        }
    }
}
