package com.spellkeyboard.core.editor

/**
 * 편집기에 글자를 넣고 빼는 최소 인터페이스.
 *
 * 안드로이드의 `InputConnection` 에서 우리가 실제로 쓰는 것만 추린 것이다.
 * 이걸 끼워 넣으면 입력 처리 로직 전체를 JVM 단위 테스트로 돌릴 수 있다.
 * 실기기 없이 검증할 수 없는 코드가 곧 고칠 수 없는 코드다.
 */
interface Editor {

    /** 여러 편집을 하나로 묶는다. 중간 상태가 화면에 그려지는 것을 막는다. */
    fun beginBatch()

    fun endBatch()

    /** 확정된 텍스트를 커서 위치에 넣는다. 조합 중이던 글자는 이 텍스트로 대체된다. */
    fun commitText(text: String)

    /** 조합 중인 텍스트를 갱신한다. 빈 문자열이면 조합 영역이 사라진다. */
    fun setComposingText(text: String)

    /** 조합을 끝낸다. 화면의 글자는 그대로 두고 조합 상태만 푼다. */
    fun finishComposing()

    /** 커서 앞 [count] 글자를 지운다. */
    fun deleteBefore(count: Int)

    /** 커서 앞 텍스트를 최대 [maxChars] 글자까지 읽는다. */
    fun textBeforeCursor(maxChars: Int): String
}

/** 교정 시도의 결과. 키보드 상단 줄에 무엇을 보여줄지 결정한다. */
sealed interface CorrectionEvent {

    /** 실제로 고쳤다. */
    data class Applied(val from: String, val to: String) : CorrectionEvent

    /** 검사했지만 고칠 것이 없었다. [examined] 는 실제로 검사한 구간이다. */
    data class Unchanged(val examined: String) : CorrectionEvent

    /** 자동 교정을 되돌렸다. */
    data object Reverted : CorrectionEvent

    /** 교정이 꺼져 있어 검사하지 않았다. (비밀번호 입력란 등) */
    data object Disabled : CorrectionEvent
}
