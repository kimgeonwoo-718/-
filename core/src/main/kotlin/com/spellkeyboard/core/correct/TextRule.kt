package com.spellkeyboard.core.correct

/** 규칙 하나가 실제로 고친 내용. UI 표시와 디버깅에 쓴다. */
data class Correction(val from: String, val to: String, val reason: String)

/**
 * 정규식 기반 교정 규칙.
 *
 * 실시간 경로에서 도는 코드라 무겁게 만들지 않는다. 정규식 하나 + 선택적 가드가 전부고,
 * 가드는 앞 음절의 받침 같은 값싼 판정만 한다.
 *
 * @param guard 매칭이 일어나도 이 가드가 false 면 원문을 그대로 둔다.
 *              "받침이 ㄹ 일 때만" 같은 조건을 표현하는 데 쓴다.
 */
class TextRule private constructor(
    private val regex: Regex,
    private val reason: String,
    private val transform: (MatchResult) -> String?
) {

    /** [input] 에 규칙을 적용하고, 바뀐 부분을 [sink] 에 기록한다. */
    fun apply(input: String, sink: MutableList<Correction>): String =
        regex.replace(input) { match ->
            val replaced = transform(match)
            if (replaced == null || replaced == match.value) {
                match.value
            } else {
                sink += Correction(match.value, replaced, reason)
                replaced
            }
        }

    companion object {
        private val GROUP_REF = Regex("""\$(\d)""")

        private fun expand(template: String, match: MatchResult): String =
            GROUP_REF.replace(template) { ref ->
                match.groupValues[ref.groupValues[1].toInt()]
            }

        /** 문자 그대로 치환하는 규칙. 가장 흔한 형태다. */
        fun literal(from: String, to: String, reason: String = "$from -> $to"): TextRule =
            TextRule(Regex(Regex.escape(from)), reason) { to }

        /** 정규식과 `$1` 형태의 치환 템플릿으로 만드는 규칙. */
        fun of(
            pattern: String,
            template: String,
            reason: String,
            guard: (MatchResult) -> Boolean = { true }
        ): TextRule = TextRule(Regex(pattern), reason) { match ->
            if (guard(match)) expand(template, match) else null
        }
    }
}
