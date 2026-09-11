import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  b64url,
  unsignedJwt,
  pemToDer,
  signJwt,
  fetchAccessToken,
  interpretSubscription,
  verifySubscription,
  subscriptionUrl,
} from '../src/play.js';

/** 테스트용 RSA 키 한 쌍. 서비스 계정 JSON 의 private_key 와 같은 PEM 모양으로 만든다. */
export async function testKeyPair() {
  const pair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  );
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  const b64 = btoa(String.fromCharCode(...pkcs8)).replace(/(.{64})/g, '$1\n');
  const pem = `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n`;
  return { pem, publicKey: pair.publicKey };
}

function decodePart(part) {
  const padded = part.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (part.length % 4)) % 4);
  return JSON.parse(atob(padded));
}

test('서명 전 JWT 에 구글이 요구하는 항목이 전부 있다', () => {
  const unsigned = unsignedJwt({ clientEmail: 'svc@proj.iam.gserviceaccount.com', nowSec: 1_700_000_000 });
  const [header, claims] = unsigned.split('.').map(decodePart);
  assert.deepEqual(header, { alg: 'RS256', typ: 'JWT' });
  assert.equal(claims.iss, 'svc@proj.iam.gserviceaccount.com');
  assert.equal(claims.scope, 'https://www.googleapis.com/auth/androidpublisher');
  assert.equal(claims.aud, 'https://oauth2.googleapis.com/token');
  assert.equal(claims.iat, 1_700_000_000);
  assert.equal(claims.exp, 1_700_003_600);
});

test('base64url 은 패딩과 +/ 를 쓰지 않는다', () => {
  const out = b64url(new Uint8Array([251, 255, 254]));
  assert.ok(!/[+/=]/.test(out), out);
});

test('PEM 을 DER 로 풀면 WebCrypto 가 받는다', async () => {
  const { pem } = await testKeyPair();
  const key = await crypto.subtle.importKey('pkcs8', pemToDer(pem), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  assert.equal(key.type, 'private');
});

test('서명한 JWT 를 공개키로 검증할 수 있다', async () => {
  const { pem, publicKey } = await testKeyPair();
  const unsigned = unsignedJwt({ clientEmail: 'a@b', nowSec: 1 });
  const jwt = await signJwt(unsigned, pem);
  const [h, c, s] = jwt.split('.');
  assert.equal(`${h}.${c}`, unsigned);

  const sig = Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4)), (ch) => ch.charCodeAt(0));
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', publicKey, sig, new TextEncoder().encode(unsigned));
  assert.ok(ok, '서명이 맞지 않는다');
});

test('액세스 토큰 교환은 JWT 를 assertion 으로 보낸다', async () => {
  const { pem } = await testKeyPair();
  let captured;
  const fetchImpl = async (url, init) => {
    captured = { url, init };
    return new Response(JSON.stringify({ access_token: 'ya29.test', expires_in: 3599 }), { status: 200 });
  };
  const out = await fetchAccessToken({ client_email: 'a@b', private_key: pem }, fetchImpl, 10);
  assert.deepEqual(out, { token: 'ya29.test', expiresInSec: 3599 });
  assert.equal(captured.url, 'https://oauth2.googleapis.com/token');
  const params = captured.init.body;
  assert.equal(params.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  assert.equal(params.get('assertion').split('.').length, 3);
});

test('구독 상태를 살아 있음 하나로 줄인다', () => {
  assert.equal(interpretSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' }).active, true);
  assert.equal(interpretSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' }).active, true);
  assert.equal(interpretSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }).active, false);
  assert.equal(interpretSubscription({ subscriptionState: 'SUBSCRIPTION_STATE_CANCELED' }).active, false);
  assert.equal(interpretSubscription({}).active, false);
  assert.equal(interpretSubscription(null).active, false);
});

test('없는 토큰은 오류가 아니라 구독 아님이다', async () => {
  const fetchImpl = async () => new Response('{"error":{}}', { status: 404 });
  const out = await verifySubscription({ pkg: 'p', purchaseToken: 't', accessToken: 'a', fetchImpl });
  assert.deepEqual(out, { active: false, state: 'NOT_FOUND' });
});

test('구글 쪽 장애는 예외로 올려 캐시되지 않게 한다', async () => {
  const fetchImpl = async () => new Response('', { status: 503 });
  await assert.rejects(() => verifySubscription({ pkg: 'p', purchaseToken: 't', accessToken: 'a', fetchImpl }));
});

test('토큰과 패키지명을 URL 에 안전하게 넣는다', () => {
  const url = subscriptionUrl('com.spellkeyboard.ko', 'ab/c d');
  assert.ok(url.endsWith('/applications/com.spellkeyboard.ko/purchases/subscriptionsv2/tokens/ab%2Fc%20d'));
});
