/**
 * Google Play 구독 확인.
 *
 * 앱이 보내온 구매 토큰이 진짜인지, 지금도 살아 있는지는 구글에게 물어봐야 안다.
 * 앱은 얼마든지 "나 결제했어" 라고 말할 수 있다. Play Developer API 를 서비스 계정으로
 * 부르는데, 그 인증이 JWT 서명이라 여기서 직접 만든다 — 라이브러리 없이 WebCrypto 로.
 */

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';

/** 이 상태면 돈을 내고 있는 것으로 본다. 유예 기간은 결제 실패 직후라 아직 살려 둔다. */
export const ACTIVE_STATES = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
]);

/**
 * 해지 예약 상태. 사용자가 해지를 눌렀지만 **이미 낸 기간이 남아 있다** — 구글은 만료 시각까지 쓰게 한다.
 * 그래서 만료 시각 전이면 살아 있는 것으로 본다(윈도우 점검 ⑦①). 예전에는 해지 버튼을 누르는 순간 막았다.
 */
export const CANCELED_STATE = 'SUBSCRIPTION_STATE_CANCELED';

/**
 * 우리가 파는 구독 상품. 앱(`BillingManager.PRODUCT_ID`)과 Play 콘솔과 **글자 하나까지 같아야** 한다.
 * `PLAY_PRODUCT_ID` 가 이긴다.
 */
export const DEFAULT_PRODUCT_ID = 'ai_unlimited_monthly';

export function b64url(input) {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input;
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** 서명 전 JWT. 순수 함수라 테스트에서 내용을 그대로 확인한다. */
export function unsignedJwt({ clientEmail, nowSec, scope = SCOPE, aud = TOKEN_URL, ttlSec = 3600 }) {
  const header = { alg: 'RS256', typ: 'JWT' };
  const claims = { iss: clientEmail, scope, aud, iat: nowSec, exp: nowSec + ttlSec };
  return `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
}

/** 서비스 계정 JSON 의 private_key(PEM) 를 WebCrypto 가 먹는 DER 로. */
export function pemToDer(pem) {
  const body = pem
    .replace(/-----BEGIN [^-]+-----/g, '')
    .replace(/-----END [^-]+-----/g, '')
    .replace(/\s+/g, '');
  const binary = atob(body);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

export async function signJwt(unsigned, privateKeyPem, subtle = crypto.subtle) {
  const key = await subtle.importKey(
    'pkcs8',
    pemToDer(privateKeyPem),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${b64url(new Uint8Array(signature))}`;
}

/** 서비스 계정으로 액세스 토큰을 받는다. 한 시간짜리라 호출하는 쪽이 캐시한다. */
export async function fetchAccessToken(serviceAccount, fetchImpl = fetch, nowSec = Math.floor(Date.now() / 1000)) {
  const jwt = await signJwt(
    unsignedJwt({ clientEmail: serviceAccount.client_email, nowSec }),
    serviceAccount.private_key,
  );
  const res = await fetchImpl(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`token exchange failed: ${res.status}`);
  const json = await res.json();
  if (!json.access_token) throw new Error('token exchange returned no access_token');
  return { token: json.access_token, expiresInSec: Number(json.expires_in ?? 3600) };
}

export function subscriptionUrl(pkg, purchaseToken) {
  return (
    'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' +
    `${encodeURIComponent(pkg)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`
  );
}

/**
 * 구글 응답(subscriptionsv2)을 "살아 있나" 로 줄인다. 보는 것 넷이다(윈도우 점검 ⑦):
 *
 * 1. **우리 상품인가.** 영수증이 "우리 앱의 구독" 이라는 것만으로는 부족하다 — 나중에 더 싼 구독
 *    (테마팩 같은)을 팔면 그걸 산 사람도 AI 를 쓰게 된다. `lineItems` 에 우리 상품 ID 가 있어야 한다.
 * 2. **상태.** 정상·유예는 살아 있고, 해지 예약(CANCELED)은 **만료 시각 전까지** 살아 있다.
 * 3. **시험 결제인가.** 라이선스 테스터(Play 콘솔에 운영자가 넣은 계정)의 시험 결제는 돈이 안 나가고,
 *    다시 사면 토큰이 새로 나와 한도도 새로 난다. `allowTest` 일 때만(출시 전 시험 기간) 쳐 준다.
 * 4. **승인됐나.** 3일 안에 승인 안 된 구매는 구글이 자동 환불한다. 여기서는 표시만 하고(`needsAck`),
 *    승인은 호출한 쪽(서버)이 한다.
 */
export function interpretSubscription(json, { productId = DEFAULT_PRODUCT_ID, nowMs = Date.now(), allowTest = false } = {}) {
  const rawState = json?.subscriptionState ?? 'UNKNOWN';
  const items = Array.isArray(json?.lineItems) ? json.lineItems : [];
  const item = items.find((one) => one?.productId === productId);
  const expiryMs = item ? Date.parse(item.expiryTime ?? '') : NaN;
  const startMs = Date.parse(json?.startTime ?? '');
  const out = {
    active: false,
    state: rawState,
    expiryMs: Number.isFinite(expiryMs) ? expiryMs : null,
    startMs: Number.isFinite(startMs) ? startMs : null,
    needsAck: json?.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING',
    test: json?.testPurchase != null,
  };
  if (!item) return { ...out, state: items.length > 0 ? 'WRONG_PRODUCT' : rawState };
  if (out.test && !allowTest) return { ...out, state: 'TEST_PURCHASE' };
  if (ACTIVE_STATES.has(rawState)) return { ...out, active: true };
  if (rawState === CANCELED_STATE) return { ...out, active: out.expiryMs != null && out.expiryMs > nowMs };
  return out;
}

/**
 * 구매 토큰이 유효한 구독인지 묻는다.
 *
 * 없는 토큰은 구글이 400/404 로, 만료된 지 오래된 토큰은 410 으로 답한다 — 그건 "오류" 가 아니라
 * "구독 아님" 이다(410 을 오류로 치면 옛 토큰을 든 사람이 영영 '잠시 후 다시' 만 본다).
 * 그 밖의 실패(5xx, 429, 인증)는 예외로 올려서 호출하는 쪽이 장애로 다루게 한다.
 */
export async function verifySubscription({ pkg, purchaseToken, accessToken, fetchImpl = fetch, ...options }) {
  const res = await fetchImpl(subscriptionUrl(pkg, purchaseToken), {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (res.status === 400 || res.status === 404 || res.status === 410) {
    return { active: false, state: 'NOT_FOUND', expiryMs: null, startMs: null, needsAck: false, test: false };
  }
  if (!res.ok) throw new Error(`play verify failed: ${res.status}`);
  return interpretSubscription(await res.json(), options);
}

export function acknowledgeUrl(pkg, productId, purchaseToken) {
  return (
    'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' +
    `${encodeURIComponent(pkg)}/purchases/subscriptions/${encodeURIComponent(productId)}` +
    `/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`
  );
}

/**
 * 서버가 구매를 **직접 승인**한다(윈도우 점검 ⑦③).
 *
 * 앱은 결제 직후 승인하지만, 뜯어고친 앱은 일부러 안 한다 — 그러면 3일 쓰고 자동 환불받고 또 사는 것을
 * 되풀이할 수 있다. 서버가 승인해 버리면 환불되지 않는다. 서비스 계정에 Play 콘솔의 "주문 및 구독 관리"
 * 권한이 있어야 한다. 성공하면 true, 실패하면 false(던지지 않는다).
 */
export async function acknowledgeSubscription({ pkg, productId, purchaseToken, accessToken, fetchImpl = fetch }) {
  try {
    const res = await fetchImpl(acknowledgeUrl(pkg, productId, purchaseToken), {
      method: 'POST',
      headers: { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
      body: '{}',
    });
    return res.ok;
  } catch {
    return false;
  }
}
