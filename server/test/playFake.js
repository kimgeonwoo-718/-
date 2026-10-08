/**
 * 구글 subscriptionsv2 가 살아 있는 구독에 돌려주는 답의 흉내.
 *
 * 예전 흉내는 `{ subscriptionState: ACTIVE }` 한 줄뿐이었다. 진짜 답에는 상품 줄(lineItems)과 승인 상태가
 * 실려 오고, 서버가 그걸 본다(우리 상품인가, 승인됐나 — play.js 의 interpretSubscription). 그래서 흉내도
 * 진짜 모양대로 싣는다. 바꿔 보고 싶은 것만 [overrides] 로 덮는다.
 */
export function activeSubscriptionJson(overrides = {}) {
  return {
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    startTime: '2025-12-01T00:00:00Z',
    lineItems: [{ productId: 'ai_unlimited_monthly', expiryTime: '2099-01-01T00:00:00Z' }],
    ...overrides,
  };
}
