package com.spellkeyboard.ko

import android.content.Context

/**
 * 구독자 전용 꾸밈(바다사자 테마·소리팩)을 열어 줄지.
 *
 * **폰 안에서만 판단한다** — 키를 누를 때마다 서버에 물을 수는 없고, 인터넷이 없어도 산 것은
 * 써져야 한다. 근거는 둘:
 * - Play 구매 토큰. [BillingManager] 가 구독이 끝나면 지운다.
 * - 서버가 마지막으로 알려 준 요금 상태가 구독자. 계정으로 다른 기기의 구독을 쓰는 경우다.
 *
 * AI 처럼 돈이 드는 것이 아니라서 이 정도로 충분하다. 돈이 드는 판단은 서버가 따로 한다.
 */
object Premium {
    private const val SUBSCRIBER_PLAN = "subscriber"

    fun active(context: Context): Boolean =
        Prefs.purchaseToken(context).isNotEmpty() || Prefs.lastQuota(context)?.plan == SUBSCRIBER_PLAN
}
