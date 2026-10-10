package com.spellkeyboard.ko.llm

/**
 * 키보드 프로세스와 `:llm` 프로세스 사이의 메시지 규약(Messenger).
 *
 * 요청 번호(arg1)는 키보드 쪽이 매기고 늘 커진다. `MSG_CANCEL_BEFORE(n)` 은 n 보다 작은 번호의 요청을 전부 버리게 한다 —
 * 글이 바뀌어 낡아진 요청을 한꺼번에 거두는 방법이다.
 */
internal object LlmProtocol {
    // 키보드 → 서비스
    const val MSG_TRANSLATE = 1 // arg1 = 요청 번호, data = {lang, text}
    const val MSG_CANCEL_BEFORE = 2 // arg1 = 이 번호보다 작은 요청을 버린다(돌고 있으면 멈춘다)
    const val MSG_WARM = 3 // data = {lang} — 모델을 올리고 그 언어 지시문을 미리 계산해 둔다
    const val MSG_SHUTDOWN = 4 // 모델을 놓고 프로세스를 끝낸다

    // 서비스 → 키보드
    const val R_RESULT = 101 // arg1 = 요청 번호, arg2 = 0 이면 성공(data.text) 아니면 오류 값
    const val R_READY = 102 // arg1 = 1 성공 / 0 실패(data.error) — 모델을 올린 결과

    const val KEY_LANG = "lang"
    const val KEY_TEXT = "text"
    const val KEY_ERROR = "error"

    /** R_RESULT 의 arg2 — 번역기가 답했지만 쓸 수 없는 글(한글 남음·엉뚱한 언어·되풀이)이다. */
    const val STATUS_BAD_OUTPUT = 1

    /** R_RESULT 의 arg2 — 모델을 못 올렸거나 계산이 실패했다. */
    const val STATUS_FAILED = 2
}
