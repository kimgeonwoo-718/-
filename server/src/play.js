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

/** 구글 응답을 "살아 있나" 하나로 줄인다. */
export function interpretSubscription(json) {
  const state = json?.subscriptionState ?? 'UNKNOWN';
  return { active: ACTIVE_STATES.has(state), state };
}

/**
 * 구매 토큰이 유효한 구독인지 묻는다.
 *
 * 없는 토큰은 구글이 400/404 로 답한다 — 그건 "오류" 가 아니라 "구독 아님" 이다.
 * 그 밖의 실패(5xx, 인증)는 예외로 올려서 호출하는 쪽이 캐시하지 않게 한다.
 */
export async function verifySubscription({ pkg, purchaseToken, accessToken, fetchImpl = fetch }) {
  const res = await fetchImpl(subscriptionUrl(pkg, purchaseToken), {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (res.status === 400 || res.status === 404) return { active: false, state: 'NOT_FOUND' };
  if (!res.ok) throw new Error(`play verify failed: ${res.status}`);
  return interpretSubscription(await res.json());
}
