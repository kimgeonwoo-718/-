package com.spellkeyboard.core.spacing

import com.spellkeyboard.core.hangul.Hangul
import com.spellkeyboard.core.hangul.NO_JONG

/**
 * 형태소 사전으로 맞춤법을 고친다.
 *
 * 규칙 사전은 미리 적어 둔 표기만 잡는다. '안녕하새요', '햇는대요' 처럼 매번 새로
 * 생기는 오타는 목록으로 못 따라간다.
 *
 * 그래서 판정을 뒤집는다. **고친 쪽이 형태소 분석기 눈에 훨씬 자연스러우면** 고친다.
 * '안녕하새요' 는 억지로 분석되지만 비용이 높고, '안녕하세요' 는 값싸게 분석된다.
 * 그 차이가 문턱을 넘을 때만 손댄다.
 *
 * 후보는 **한국어에서 실제로 헷갈리는 자모**만 바꿔서 만든다. 아무 글자나 바꿔가며
 * 뒤지면 신조어와 고유명사를 짓밟는다.
 */
class Speller(private val spacer: Spacer) {

    /**
     * @return 고친 어절. 고칠 것이 없으면 null.
     */
    fun correct(word: String): String? {
        if (word.length !in MIN_LENGTH..MAX_LENGTH) return null
        if (word.none { Hangul.isSyllable(it) }) return null

        val originalCost = spacer.cost(word)
        var current = word
        var currentCost = originalCost

        // 한 번에 한 자모씩 고쳐 나간다. '햇는대요' 처럼 두 군데 틀린 경우
        // 각 단계의 이득은 작아서, 단계 문턱은 낮게 두고 최종 이득으로 판정한다.
        repeat(MAX_EDITS) {
            var bestWord: String? = null
            var bestCost = currentCost - STEP_MARGIN

            for (candidate in candidates(current)) {
                val cost = spacer.cost(candidate)
                if (cost < bestCost) {
                    bestCost = cost
                    bestWord = candidate
                }
            }
            val improved = bestWord ?: return@repeat
            current = improved
            currentCost = bestCost
        }

        if (current == word) return null
        // 다 고치고 나서도 원래보다 확실히 자연스럽지 않으면 손대지 않는다.
        return if (originalCost - currentCost >= TOTAL_MARGIN) current else null
    }

    /** 헷갈리는 자모 하나만 바꾼 후보들. */
    private fun candidates(word: String): List<String> {
        val out = ArrayList<String>()
        for (index in word.indices) {
            val parts = Hangul.decompose(word[index]) ?: continue
            val (cho, jung, jong) = parts

            CHOSEONG_CONFUSIONS[Hangul.CHOSEONG[cho]]?.forEach { alternative ->
                val id = Hangul.choseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(id, jung, jong))
            }
            JUNGSEONG_CONFUSIONS[Hangul.JUNGSEONG[jung]]?.forEach { alternative ->
                val id = Hangul.jungseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(cho, id, jong))
            }
            JONGSEONG_CONFUSIONS[Hangul.JONGSEONG[jong]]?.forEach { alternative ->
                val id = if (alternative == NO_JONG) 0 else Hangul.jongseongIndex(alternative)
                if (id >= 0) out += swap(word, index, Hangul.compose(cho, jung, id))
            }
        }
        return out
    }

    private fun swap(word: String, index: Int, syllable: Char): String =
        buildString {
            append(word, 0, index)
            append(syllable)
            append(word, index + 1, word.length)
        }

    private companion object {
        /** 한 단계에서 요구하는 최소 이득. 탐색을 이어가기 위한 값이라 낮다. */
        const val STEP_MARGIN = 300

        /**
         * 최종적으로 요구하는 이득. 실제 채택 여부는 이 값이 정한다.
         *
         * 낮추면 더 많이 고치지만 신조어·고유명사를 망가뜨린다.
         * 오교정 한 번이 미교정 열 번보다 나쁘다는 원칙은 여기서도 같다.
         */
        const val TOTAL_MARGIN = 2000

        /** 한 어절에 허용하는 최대 수정 횟수. '햇는대요' 처럼 두 군데 틀리는 일이 흔하다. */
        const val MAX_EDITS = 2

        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 12

        /** 된소리/예사소리 혼동. */
        val CHOSEONG_CONFUSIONS = mapOf(
            'ㄱ' to listOf('ㄲ'), 'ㄲ' to listOf('ㄱ'),
            'ㄷ' to listOf('ㄸ'), 'ㄸ' to listOf('ㄷ'),
            'ㅂ' to listOf('ㅃ'), 'ㅃ' to listOf('ㅂ'),
            'ㅅ' to listOf('ㅆ'), 'ㅆ' to listOf('ㅅ'),
            'ㅈ' to listOf('ㅉ'), 'ㅉ' to listOf('ㅈ')
        )

        /** ㅐ/ㅔ 처럼 소리가 같아진 모음들. 한국어 오타의 큰 축이다. */
        val JUNGSEONG_CONFUSIONS = mapOf(
            'ㅐ' to listOf('ㅔ'), 'ㅔ' to listOf('ㅐ'),
            'ㅒ' to listOf('ㅖ'), 'ㅖ' to listOf('ㅒ'),
            'ㅙ' to listOf('ㅚ', 'ㅞ'),
            'ㅚ' to listOf('ㅙ', 'ㅞ'),
            'ㅞ' to listOf('ㅙ', 'ㅚ')
        )

        /** 받침 혼동. '햇다/했다', '안/않' 같은 것들. */
        val JONGSEONG_CONFUSIONS = mapOf(
            'ㅅ' to listOf('ㅆ'), 'ㅆ' to listOf('ㅅ'),
            'ㄱ' to listOf('ㄲ'), 'ㄲ' to listOf('ㄱ'),
            'ㄴ' to listOf('ㄶ'), 'ㄶ' to listOf('ㄴ'),
            'ㄹ' to listOf('ㅀ'), 'ㅀ' to listOf('ㄹ')
        )
    }
}
