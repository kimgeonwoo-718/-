package com.spellkeyboard.core.spacing

/**
 * 공백을 아예 안 친 긴 덩어리를 푸는 것.
 *
 * [Spacer] 가 늘 있고, 더 나은 것(Kiwi)이 올라오면 그쪽이 대신한다. 코어 모듈은
 * 안드로이드에도 Kiwi 에도 기대지 않으므로 **자리만 내어 준다** — 끼우는 것은 앱의 몫이다.
 */
fun interface LongSpacer {
    /** 띄운 결과. 한 군데도 못 띄우면 null. */
    fun space(text: String): String?
}
