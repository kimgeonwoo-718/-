package com.spellkeyboard.core.hangul

/**
 * 천지인 오토마타.
 *
 * 키는 열둘이다. 모음은 ㅣ ㆍ ㅡ 세 획을 겹쳐 만들고(ㅣ+ㆍ=ㅏ, ㅏ+ㆍ=ㅑ, ㆍ+ㅡ=ㅗ …),
 * 자음은 한 키에 두셋이 실려 같은 키를 다시 누르면 다음 것으로 돈다(ㄱ→ㅋ→ㄲ).
 *
 * "같은 키를 다시 눌렀다" 는 [press] 의 `repeat` 로 받는다. 시간 판단은 키보드 몫이다 —
 * 두벌식과 달리 천지인은 같은 키로 다른 글자를 내므로 "각가" 를 치려면 ㄱ 사이에
 * 잠깐 쉬어야 하고, 그 "잠깐" 을 여기서 재면 테스트가 시계에 묶인다.
 *
 * 상태는 음절 하나: 초성, 모음 획 열, 종성. 모음은 완성된 글자가 아니라 **획의
 * 나열**로 들고 있는다. 그래야 백스페이스가 획 하나씩 되돌아가고(갸→가→기→ㄱ),
 * ㆍ 하나만 눌린 미완성 상태도 자연스럽게 표현된다.
 */
class CheonjiinAutomata : JamoAutomata {

    private var cho = -1
    private val strokes = StringBuilder()
    /** 마지막 입력이 모음 획이라 다음 획이 여기 이어 붙는가. 자음이 오면 닫힌다. */
    private var vowelOpen = false
    private var jong = 0

    /** 연타로 돌릴 수 있는 마지막 자음. 어느 키를, 어느 자리에, 몇 번째 글자로 넣었나. */
    private var lastKey: Char? = null
    private var lastSlot: Slot? = null
    private var cycleIndex = 0

    private enum class Slot { CHO, JONG, JONG_SECOND }

    override fun isEmpty(): Boolean = cho < 0 && strokes.isEmpty() && jong == 0

    override fun composing(): String {
        val vowel = foldStrokes(strokes)
        val jungIdx = jungIndexOf(vowel)
        return when {
            cho >= 0 && jungIdx >= 0 -> Hangul.compose(cho, jungIdx, jong).toString()
            cho >= 0 -> Hangul.CHOSEONG[cho].toString() + (vowel ?: "")
            jungIdx >= 0 -> Hangul.JUNGSEONG[jungIdx].toString()
            else -> vowel ?: ""
        }
    }

    /**
     * 확정할 글자. ㆍ 하나만 눌린 미완성 모음은 버린다 — 글자가 아니다.
     */
    override fun flush(): String {
        val vowel = foldStrokes(strokes)
        val jungIdx = jungIndexOf(vowel)
        val pending = when {
            cho >= 0 && jungIdx >= 0 -> Hangul.compose(cho, jungIdx, jong).toString()
            cho >= 0 -> Hangul.CHOSEONG[cho].toString()
            jungIdx >= 0 -> Hangul.JUNGSEONG[jungIdx].toString()
            else -> ""
        }
        reset()
        return pending
    }

    override fun reset() {
        cho = -1
        strokes.setLength(0)
        vowelOpen = false
        jong = 0
        resetCycle()
    }

    private fun resetCycle() {
        lastKey = null
        lastSlot = null
        cycleIndex = 0
    }

    override fun press(input: Char, repeat: Boolean): AutomataOutput = when {
        input in STROKES -> pressStroke(input)
        input in CYCLES -> pressConsonantKey(input, repeat)
        else -> AutomataOutput(flush() + input, "")
    }

    private fun pressStroke(stroke: Char): AutomataOutput {
        resetCycle()

        // 모음을 만드는 중이면 획을 이어 붙여 본다.
        if (vowelOpen && strokes.isNotEmpty()) {
            val current = foldStrokes(strokes)
            if (current != null && transition(current, stroke) != null) {
                strokes.append(stroke)
                return AutomataOutput("", composing())
            }
            // 더 이어질 수 없는 획이면 이 음절은 끝났다. 새 음절을 이 획으로 시작한다.
            val committed = flush()
            strokes.append(stroke)
            vowelOpen = true
            return AutomataOutput(committed, composing())
        }

        // 받침 뒤에 모음이 오면 받침이 다음 음절의 초성으로 넘어간다. 겹받침이면 뒤쪽만.
        if (jong > 0) {
            val jongChar = Hangul.JONGSEONG[jong]
            val split = Hangul.splitJongseong(jongChar)
            val staying = split?.first
            val moving = split?.second ?: jongChar
            val stayingIdx = if (staying != null) Hangul.jongseongIndex(staying) else 0
            val jungIdx = jungIndexOf(foldStrokes(strokes))
            val committed = Hangul.compose(cho, jungIdx, stayingIdx).toString()

            cho = Hangul.choseongIndex(moving)
            jong = 0
            strokes.setLength(0)
            strokes.append(stroke)
            vowelOpen = true
            return AutomataOutput(committed, composing())
        }

        // 모음이 닫힌 채 남아 있는 경우는 없다 — 자음이 왔으면 받침이 됐거나 새 음절이 됐다.
        // 그래도 방어적으로: 닫힌 모음이 있으면 음절을 끝낸다.
        if (strokes.isNotEmpty()) {
            val committed = flush()
            strokes.append(stroke)
            vowelOpen = true
            return AutomataOutput(committed, composing())
        }

        strokes.append(stroke)
        vowelOpen = true
        return AutomataOutput("", composing())
    }

    private fun pressConsonantKey(key: Char, repeat: Boolean): AutomataOutput {
        val letters = CYCLES.getValue(key)

        // 같은 키 연타: 방금 넣은 자음을 다음 글자로 바꾼다.
        if (repeat && lastKey == key && lastSlot != null) {
            cycle(letters)
            return AutomataOutput("", composing())
        }

        vowelOpen = false
        // ㆍ 하나만 눌린 미완성 모음은 글자가 못 된다. 자음이 오면 버린다.
        if (strokes.isNotEmpty() && jungIndexOf(foldStrokes(strokes)) < 0) {
            strokes.setLength(0)
        }

        val letter = letters[0]
        lastKey = key
        cycleIndex = 0

        // 받침 없는 완성 음절 뒤의 자음은 받침이 된다.
        if (cho >= 0 && strokes.isNotEmpty() && jong == 0 && Hangul.jongseongIndex(letter) > 0) {
            jong = Hangul.jongseongIndex(letter)
            lastSlot = Slot.JONG
            return AutomataOutput("", composing())
        }

        // 받침이 있으면 겹받침을 시도한다.
        if (jong > 0) {
            val combined = Hangul.combineJongseong(Hangul.JONGSEONG[jong], letter)
            if (combined != null) {
                jong = Hangul.jongseongIndex(combined)
                lastSlot = Slot.JONG_SECOND
                return AutomataOutput("", composing())
            }
        }

        // 그 외에는 지금까지를 확정하고 새 음절의 초성으로.
        val committed = flush()
        cho = Hangul.choseongIndex(letter)
        lastKey = key
        lastSlot = Slot.CHO
        cycleIndex = 0
        return AutomataOutput(committed, composing())
    }

    /**
     * 연타 한 바퀴. 그 자리에 못 들어가는 글자는 건너뛴다 — ㄸ·ㅃ·ㅉ 은 받침이 될 수
     * 없고, 겹받침의 뒷글자는 앞글자와 짝이 맞아야 한다. 첫 글자는 언제나 들어간다.
     */
    private fun cycle(letters: String) {
        repeat(letters.length) {
            cycleIndex = (cycleIndex + 1) % letters.length
            val letter = letters[cycleIndex]
            when (lastSlot) {
                Slot.CHO -> {
                    cho = Hangul.choseongIndex(letter)
                    return
                }

                Slot.JONG -> {
                    val idx = Hangul.jongseongIndex(letter)
                    if (idx > 0) {
                        jong = idx
                        return
                    }
                }

                Slot.JONG_SECOND -> {
                    val first = Hangul.splitJongseong(Hangul.JONGSEONG[jong])?.first ?: return
                    val combined = Hangul.combineJongseong(first, letter)
                    if (combined != null) {
                        jong = Hangul.jongseongIndex(combined)
                        return
                    }
                }

                null -> return
            }
        }
    }

    override fun backspace(): AutomataOutput? {
        if (isEmpty()) return null
        resetCycle()

        if (jong > 0) {
            val split = Hangul.splitJongseong(Hangul.JONGSEONG[jong])
            jong = if (split != null) Hangul.jongseongIndex(split.first) else 0
            return AutomataOutput("", composing())
        }

        // 모음은 획 하나씩 되돌린다. 갸 → 가 → 기 → ㄱ.
        if (strokes.isNotEmpty()) {
            strokes.setLength(strokes.length - 1)
            vowelOpen = strokes.isNotEmpty()
            return AutomataOutput("", composing())
        }

        cho = -1
        return AutomataOutput("", composing())
    }

    companion object {
        /** 이 오토마타가 알아듣는 키인가. 키보드가 어느 경로로 보낼지 가르는 데 쓴다. */
        fun isKey(c: Char): Boolean = c in STROKES || c in CYCLES

        /** 모음 획. ㆍ 는 옛한글 아래아(U+318D)를 빌려 쓴다. 화면에도 이 글자가 보인다. */
        const val DOT = 'ㆍ'
        private val STROKES = setOf('ㅣ', DOT, 'ㅡ')

        /** 자음 키와 그 키가 도는 순서. 키는 첫 글자로 부른다. */
        val CYCLES: Map<Char, String> = linkedMapOf(
            'ㄱ' to "ㄱㅋㄲ",
            'ㄴ' to "ㄴㄹ",
            'ㄷ' to "ㄷㅌㄸ",
            'ㅂ' to "ㅂㅍㅃ",
            'ㅅ' to "ㅅㅎㅆ",
            'ㅈ' to "ㅈㅊㅉ",
            'ㅇ' to "ㅇㅁ"
        )

        /**
         * 모음 조립표: (지금 모음, 획) → 다음 모음. 지금 모음은 완성 모음이거나
         * 미완성 "ㆍ"/"ㆍㆍ" 다. 표에 없으면 이어질 수 없다.
         */
        private val VOWEL_TRANSITIONS: Map<Pair<String, Char>, String> = mapOf(
            ("ㅣ" to DOT) to "ㅏ",
            ("ㅏ" to DOT) to "ㅑ",
            ("ㅏ" to 'ㅣ') to "ㅐ",
            ("ㅑ" to 'ㅣ') to "ㅒ",
            ("ㆍ" to 'ㅣ') to "ㅓ",
            ("ㆍ" to DOT) to "ㆍㆍ",
            ("ㆍ" to 'ㅡ') to "ㅗ",
            ("ㆍㆍ" to 'ㅣ') to "ㅕ",
            ("ㆍㆍ" to 'ㅡ') to "ㅛ",
            ("ㅓ" to 'ㅣ') to "ㅔ",
            ("ㅕ" to 'ㅣ') to "ㅖ",
            ("ㅗ" to 'ㅣ') to "ㅚ",
            ("ㅚ" to DOT) to "ㅘ",
            ("ㅘ" to 'ㅣ') to "ㅙ",
            ("ㅡ" to DOT) to "ㅜ",
            ("ㅡ" to 'ㅣ') to "ㅢ",
            ("ㅜ" to DOT) to "ㅠ",
            ("ㅜ" to 'ㅣ') to "ㅟ",
            ("ㅠ" to 'ㅣ') to "ㅝ",
            ("ㅝ" to 'ㅣ') to "ㅞ"
        )

        /** 접힌 모음이 완성 모음이면 그 중성 인덱스, 미완성(ㆍ)이거나 없으면 -1. */
        private fun jungIndexOf(vowel: String?): Int =
            if (vowel != null && vowel.length == 1) Hangul.jungseongIndex(vowel[0]) else -1

        private fun transition(current: String, stroke: Char): String? =
            VOWEL_TRANSITIONS[current to stroke]

        /** 획 열을 모음(또는 미완성 "ㆍ"/"ㆍㆍ")으로 접는다. 빈 열이면 null. */
        private fun foldStrokes(strokes: CharSequence): String? {
            if (strokes.isEmpty()) return null
            var state = strokes[0].toString()
            for (i in 1 until strokes.length) {
                state = transition(state, strokes[i]) ?: return state
            }
            return state
        }
    }
}
