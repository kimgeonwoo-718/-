package com.spellkeyboard.core.translate

/**
 * [primary] 가 못 옮기면(실패·쓸 수 없는 답) 같은 글을 [secondary] 로 옮긴다.
 *
 * 기기 안 큰 모델(느리고 가끔 틀린다)을 앞에, ML Kit(빠르고 늘 답한다)을 뒤에 둔다. 둘 다 못 하면 그제서야 실패로 알린다.
 */
class FallbackEngine(
    private val primary: TranslationPipeline.Engine,
    private val secondary: TranslationPipeline.Engine
) : TranslationPipeline.Engine {

    override fun translate(text: String, onResult: (String) -> Unit, onFailed: (Exception) -> Unit) {
        primary.translate(text, onResult) { secondary.translate(text, onResult, onFailed) }
    }
}
