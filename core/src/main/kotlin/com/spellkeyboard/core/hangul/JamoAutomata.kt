package com.spellkeyboard.core.hangul

/**
 * 키 입력을 한글 음절로 조립하는 상태 기계의 공통 얼굴.
 *
 * 두벌식([HangulAutomata])과 천지인([CheonjiinAutomata])은 키가 다르고 조립 규칙도
 * 다르지만, [TypingSession] 이 보기에는 "키 하나 넣으면 확정분과 조합 중인 것이 나온다"
 * 로 같다. 세션은 이 얼굴만 보고, 어느 자판인지는 모른다.
 */
interface JamoAutomata {

    fun isEmpty(): Boolean

    /** 현재 조합 중인 문자열. 비어 있으면 "". */
    fun composing(): String

    /** 조합을 끝내고 남은 글자를 돌려준다. */
    fun flush(): String

    fun reset()

    /**
     * 키 하나를 넣는다.
     *
     * @param repeat 직전과 **같은 키를 짧은 간격으로** 다시 눌렀는가. 천지인은 이걸로
     *   ㄱ→ㅋ→ㄲ 을 돌고, 두벌식은 무시한다. 시간 판단은 밖(키보드)에서 하고 여기는
     *   결과만 받는다 — 상태 기계에 시계를 넣으면 테스트할 수 없다.
     */
    fun press(input: Char, repeat: Boolean = false): AutomataOutput

    /**
     * 조합 중인 글자를 한 단계 되돌린다.
     *
     * 조합 중인 글자가 없어 편집기의 확정된 문자를 지워야 하는 경우 null 을 돌려준다.
     */
    fun backspace(): AutomataOutput?
}
