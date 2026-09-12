package com.spellkeyboard.core.hangul

/**
 * 한 번의 입력이 만들어낸 결과.
 *
 * @param committed 더 이상 조합되지 않아 편집기에 확정 입력해야 할 텍스트.
 * @param composing 아직 조합 중이라 조합 영역(composing region)에 두어야 할 텍스트.
 */
data class AutomataOutput(val committed: String, val composing: String)

/**
 * 두벌식 한글 오토마타.
 *
 * 조합 중인 음절 하나만 상태로 들고 있다가, 새 음절이 시작되는 순간
 * 직전 음절을 [AutomataOutput.committed] 로 흘려보낸다. IME 쪽에서는
 * committed 를 commitText 로, composing 을 setComposingText 로 넘기면 된다.
 *
 * 이 클래스는 안드로이드에 의존하지 않는다. 순수 상태 기계라 단위 테스트로 전부 검증한다.
 */
class HangulAutomata : JamoAutomata {

    private var cho = -1
    private var jung = -1
    private var jong = 0

    override fun isEmpty(): Boolean = cho < 0 && jung < 0 && jong == 0

    /** 현재 조합 중인 문자열. 비어 있으면 "". */
    override fun composing(): String = when {
        cho >= 0 && jung >= 0 -> Hangul.compose(cho, jung, jong).toString()
        cho >= 0 -> Hangul.CHOSEONG[cho].toString()
        jung >= 0 -> Hangul.JUNGSEONG[jung].toString()
        else -> ""
    }

    /** 조합을 끝내고 남은 글자를 돌려준다. */
    override fun flush(): String {
        val pending = composing()
        reset()
        return pending
    }

    override fun reset() {
        cho = -1
        jung = -1
        jong = 0
    }

    /** 자모 하나를 입력한다. 자모가 아닌 문자는 조합을 끊고 그대로 확정된다. [repeat] 는 안 쓴다. */
    override fun press(input: Char, repeat: Boolean): AutomataOutput = press(input)

    fun press(jamo: Char): AutomataOutput = when {
        Hangul.isVowel(jamo) -> pressVowel(jamo)
        Hangul.isConsonant(jamo) -> pressConsonant(jamo)
        else -> AutomataOutput(flush() + jamo, "")
    }

    private fun pressConsonant(c: Char): AutomataOutput {
        val asJong = Hangul.jongseongIndex(c)

        // 받침 없는 완성 음절 뒤에 받침이 될 수 있는 자음이 오면 받침으로 붙인다.
        if (cho >= 0 && jung >= 0 && jong == 0 && asJong > 0) {
            jong = asJong
            return AutomataOutput("", composing())
        }

        // 이미 받침이 있으면 겹받침을 시도한다. (ㄱ + ㅅ -> ㄳ)
        if (jong > 0) {
            val combined = Hangul.combineJongseong(Hangul.JONGSEONG[jong], c)
            if (combined != null) {
                jong = Hangul.jongseongIndex(combined)
                return AutomataOutput("", composing())
            }
        }

        // 그 외에는 지금까지를 확정하고 새 음절의 초성으로 시작한다.
        val asCho = Hangul.choseongIndex(c)
        if (asCho < 0) return AutomataOutput(flush() + c, "")

        val committed = flush()
        cho = asCho
        return AutomataOutput(committed, composing())
    }

    private fun pressVowel(v: Char): AutomataOutput {
        val asJung = Hangul.jungseongIndex(v)

        // 받침이 있는 상태에서 모음이 오면 받침이 다음 음절의 초성으로 넘어간다.
        // 겹받침이면 앞쪽만 남기고 뒤쪽이 넘어간다. (갃 + ㅏ -> 각사)
        if (jong > 0) {
            val jongChar = Hangul.JONGSEONG[jong]
            val split = Hangul.splitJongseong(jongChar)
            val staying = split?.first
            val moving = split?.second ?: jongChar

            val stayingIdx = if (staying != null) Hangul.jongseongIndex(staying) else 0
            val committed = Hangul.compose(cho, jung, stayingIdx).toString()

            cho = Hangul.choseongIndex(moving)
            jung = asJung
            jong = 0
            return AutomataOutput(committed, composing())
        }

        // 초성만 있는 상태 -> 중성을 채워 음절을 만든다.
        if (cho >= 0 && jung < 0) {
            jung = asJung
            return AutomataOutput("", composing())
        }

        // 중성이 이미 있으면 복합 모음을 시도한다. (ㅗ + ㅏ -> ㅘ)
        if (jung >= 0) {
            val combined = Hangul.combineJungseong(Hangul.JUNGSEONG[jung], v)
            if (combined != null) {
                jung = Hangul.jungseongIndex(combined)
                return AutomataOutput("", composing())
            }
            val committed = flush()
            jung = asJung
            return AutomataOutput(committed, composing())
        }

        // 아무것도 없는 상태에서 들어온 모음은 홀로 선다.
        jung = asJung
        return AutomataOutput("", composing())
    }

    /**
     * 조합 중인 글자를 한 단계 되돌린다.
     *
     * 조합 중인 글자가 없어 편집기의 확정된 문자를 지워야 하는 경우 null 을 돌려준다.
     */
    override fun backspace(): AutomataOutput? {
        if (isEmpty()) return null

        if (jong > 0) {
            val split = Hangul.splitJongseong(Hangul.JONGSEONG[jong])
            jong = if (split != null) Hangul.jongseongIndex(split.first) else 0
            return AutomataOutput("", composing())
        }

        if (jung >= 0) {
            val split = Hangul.splitJungseong(Hangul.JUNGSEONG[jung])
            jung = if (split != null) Hangul.jungseongIndex(split.first) else -1
            return AutomataOutput("", composing())
        }

        cho = -1
        return AutomataOutput("", composing())
    }
}
