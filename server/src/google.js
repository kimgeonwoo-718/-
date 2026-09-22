/**
 * 구글 로그인 확인. 앱이 받아 온 ID 토큰이 **진짜 구글이 내준 것인지** 여기서 가린다.
 *
 * ## 왜 서버가 확인해야 하나
 *
 * ID 토큰은 앱을 거쳐 온다. 앱은 사용자 손에 있으므로 무엇이든 보낼 수 있다 —
 * "나 아무개야" 라고 적힌 글자를 그냥 믿으면 누구나 남의 계정이 된다. 구글이 서명해
 * 두었으므로, **구글 공개키로 서명을 확인하는 것**만이 믿을 근거다.
 *
 * ## 무엇을 가져오고 무엇을 안 가져오나
 *
 * `sub` 하나만 쓴다 — 구글 계정마다 붙는 바뀌지 않는 번호다. 그것도 **해시해서** 쓴다.
 * 이메일·이름·사진은 토큰 안에 들어 있어도 읽지 않는다. 키보드 앱이 사람 이름을 모아 둘
 * 이유가 없고, 모으지 않으면 샐 것도 없다.
 */

/** 구글이 공개키를 내놓는 자리. */
const JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';

/** 토큰 발행자. 구글은 이 둘 중 하나로 적는다. */
const ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);

/** 공개키를 얼마나 들고 있을 것인가. 구글은 자주 바꾸지 않지만 영원하지도 않다. */
export const JWKS_TTL_MS = 60 * 60 * 1000;

/** 시계가 조금 어긋나도 봐준다. 폰 시계는 생각보다 잘 틀어진다. */
const CLOCK_SKEW_SEC = 60;

export function base64UrlToBytes(text) {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

function decodeJson(part) {
  return JSON.parse(new TextDecoder().decode(base64UrlToBytes(part)));
}

export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** 설정에 적힌 우리 클라이언트 ID들. 안드로이드·아이폰·윈도우가 저마다 다르다. */
export function allowedClientIds(env) {
  return String(env.GOOGLE_CLIENT_IDS ?? '')
    .split(',')
    .map((one) => one.trim())
    .filter((one) => one.length > 0);
}

/**
 * 구글 공개키를 받아 온다. [cache] 에 넣어 두고 [JWKS_TTL_MS] 동안 다시 안 받는다.
 *
 * 매번 받으면 로그인할 때마다 왕복이 하나 더 붙고, 구글이 잠깐 느릴 때 로그인이 통째로
 * 막힌다.
 */
export async function fetchJwks({ cacheGet, cacheSet, db, fetchImpl, nowMs }) {
  const cached = await cacheGet(db, 'google:jwks', nowMs);
  if (cached?.keys) return cached.keys;

  const res = await fetchImpl(JWKS_URL);
  if (!res.ok) throw new Error(`jwks failed: ${res.status}`);
  const body = await res.json();
  if (!Array.isArray(body.keys) || body.keys.length === 0) throw new Error('jwks empty');
  await cacheSet(db, 'google:jwks', { keys: body.keys }, JWKS_TTL_MS, nowMs);
  return body.keys;
}

/**
 * ID 토큰을 확인하고 `sub` 의 해시를 돌려준다. 못 믿을 것이면 null.
 *
 * 보는 것 넷이다 — **서명**(구글 공개키), **발행자**, **받는 이**(우리 클라이언트 ID),
 * **기한**. 하나라도 어긋나면 null 이다. 왜 틀렸는지는 밖으로 알려 주지 않는다:
 * 찍어 보는 쪽에 단서를 주지 않으려고.
 */
export async function verifyIdToken({ idToken, clientIds, keys, nowMs }) {
  if (typeof idToken !== 'string') return null;
  const parts = idToken.split('.');
  if (parts.length !== 3) return null;

  let header;
  let payload;
  try {
    header = decodeJson(parts[0]);
    payload = decodeJson(parts[1]);
  } catch {
    return null;
  }
  if (header.alg !== 'RS256') return null;

  const jwk = keys.find((one) => one.kid === header.kid && one.alg === 'RS256');
  if (!jwk) return null;

  let ok = false;
  try {
    const key = await crypto.subtle.importKey(
      'jwk',
      { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: 'RS256', ext: true },
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['verify']
    );
    ok = await crypto.subtle.verify(
      'RSASSA-PKCS1-v1_5',
      key,
      base64UrlToBytes(parts[2]),
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
    );
  } catch {
    return null;
  }
  if (!ok) return null;

  if (!ISSUERS.has(payload.iss)) return null;
  // **받는 이를 반드시 본다.** 이게 없으면 남의 앱에 발급된 멀쩡한 구글 토큰으로도
  // 우리 계정에 들어올 수 있다.
  if (clientIds.length === 0 || !clientIds.includes(payload.aud)) return null;

  const nowSec = Math.floor(nowMs / 1000);
  if (typeof payload.exp !== 'number' || nowSec > payload.exp + CLOCK_SKEW_SEC) return null;
  if (typeof payload.iat === 'number' && nowSec + CLOCK_SKEW_SEC < payload.iat) return null;
  if (typeof payload.sub !== 'string' || payload.sub.length === 0) return null;

  return sha256Hex('google:' + payload.sub);
}
