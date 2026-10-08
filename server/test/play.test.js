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
  acknowledgeUrl,
  acknowledgeSubscription,
} from '../src/play.js';
import { activeSubscriptionJson } from './playFake.js';

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

const NOW = Date.UTC(2026, 9, 8, 3, 0);
const sub = (overrides) => activeSubscriptionJson(overrides);

test('구독 상태를 살아 있음 하나로 줄인다', () => {
  assert.equal(interpretSubscription(sub(), { nowMs: NOW }).active, true);
  assert.equal(interpretSubscription(sub({ subscriptionState: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' }), { nowMs: NOW }).active, true);
  assert.equal(interpretSubscription(sub({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }), { nowMs: NOW }).active, false);
  assert.equal(interpretSubscription(sub({ subscriptionState: 'SUBSCRIPTION_STATE_ON_HOLD' }), { nowMs: NOW }).active, false);
  assert.equal(interpretSubscription({}).active, false);
  assert.equal(interpretSubscription(null).active, false);
});

test('해지 예약은 이미 낸 기간이 끝날 때까지 살아 있다 (⑦①)', () => {
  // 해지를 눌러도 구글은 그 달 끝까지 쓰게 한다. 해지 버튼을 누르는 순간 막으면 돈 낸 기간을 뺏는다.
  const canceled = (expiryTime) =>
    sub({ subscriptionState: 'SUBSCRIPTION_STATE_CANCELED', lineItems: [{ productId: 'ai_unlimited_monthly', expiryTime }] });
  assert.equal(interpretSubscription(canceled('2026-10-20T00:00:00Z'), { nowMs: NOW }).active, true, '만료 전');
  assert.equal(interpretSubscription(canceled('2026-10-01T00:00:00Z'), { nowMs: NOW }).active, false, '만료 뒤');
  assert.equal(interpretSubscription(canceled(undefined), { nowMs: NOW }).active, false, '만료 시각을 모르면 막는다');
});

test('우리 상품이 아닌 구독은 쳐 주지 않는다 (⑦②)', () => {
  // 나중에 더 싼 구독을 팔면, 그걸 산 사람이 AI 를 쓰면 안 된다.
  const other = sub({ lineItems: [{ productId: 'theme_pack_monthly', expiryTime: '2099-01-01T00:00:00Z' }] });
  const out = interpretSubscription(other, { nowMs: NOW });
  assert.equal(out.active, false);
  assert.equal(out.state, 'WRONG_PRODUCT');
  assert.equal(interpretSubscription(sub({ lineItems: [] }), { nowMs: NOW }).active, false, '상품 줄이 없으면 막는다');
  assert.equal(interpretSubscription(other, { nowMs: NOW, productId: 'theme_pack_monthly' }).active, true, 'PLAY_PRODUCT_ID 로 바꿀 수 있다');
});

test('시험 결제는 allowTest 일 때만 쳐 준다', () => {
  // 라이선스 테스터의 시험 결제는 돈이 안 나가고, 다시 사면 토큰이 새로 나와 한도도 새로 난다.
  const testBuy = sub({ testPurchase: {} });
  assert.equal(interpretSubscription(testBuy, { nowMs: NOW }).active, false);
  assert.equal(interpretSubscription(testBuy, { nowMs: NOW }).state, 'TEST_PURCHASE');
  assert.equal(interpretSubscription(testBuy, { nowMs: NOW, allowTest: true }).active, true);
});

test('승인 대기면 needsAck 로 알린다 (⑦③)', () => {
  const pending = sub({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' });
  assert.equal(interpretSubscription(pending, { nowMs: NOW }).needsAck, true);
  assert.equal(interpretSubscription(sub(), { nowMs: NOW }).needsAck, false);
  assert.equal(interpretSubscription(pending, { nowMs: NOW }).startMs, Date.parse('2025-12-01T00:00:00Z'));
});

test('없는 토큰·오래 만료된 토큰은 오류가 아니라 구독 아님이다', async () => {
  for (const status of [400, 404, 410]) {
    const fetchImpl = async () => new Response('{"error":{}}', { status });
    const out = await verifySubscription({ pkg: 'p', purchaseToken: 't', accessToken: 'a', fetchImpl });
    assert.equal(out.active, false, `HTTP ${status}`);
    assert.equal(out.state, 'NOT_FOUND');
  }
});

test('서버 승인은 구독 acknowledge 주소로 POST 하고, 실패해도 던지지 않는다', async () => {
  assert.ok(acknowledgeUrl('com.spellkeyboard.ko', 'ai_unlimited_monthly', 'ab/c').endsWith(
    '/applications/com.spellkeyboard.ko/purchases/subscriptions/ai_unlimited_monthly/tokens/ab%2Fc:acknowledge'));
  const seen = [];
  const ok = await acknowledgeSubscription({
    pkg: 'p', productId: 'x', purchaseToken: 't', accessToken: 'a',
    fetchImpl: async (url, init) => { seen.push({ url, init }); return new Response('', { status: 200 }); },
  });
  assert.equal(ok, true);
  assert.equal(seen[0].init.method, 'POST');
  assert.equal(seen[0].init.headers.authorization, 'Bearer a');
  assert.equal(await acknowledgeSubscription({ pkg: 'p', productId: 'x', purchaseToken: 't', accessToken: 'a',
    fetchImpl: async () => new Response('', { status: 403 }) }), false);
  assert.equal(await acknowledgeSubscription({ pkg: 'p', productId: 'x', purchaseToken: 't', accessToken: 'a',
    fetchImpl: async () => { throw new Error('net'); } }), false);
});

test('구글 쪽 장애는 예외로 올려 캐시되지 않게 한다', async () => {
  const fetchImpl = async () => new Response('', { status: 503 });
  await assert.rejects(() => verifySubscription({ pkg: 'p', purchaseToken: 't', accessToken: 'a', fetchImpl }));
});

test('토큰과 패키지명을 URL 에 안전하게 넣는다', () => {
  const url = subscriptionUrl('com.spellkeyboard.ko', 'ab/c d');
  assert.ok(url.endsWith('/applications/com.spellkeyboard.ko/purchases/subscriptionsv2/tokens/ab%2Fc%20d'));
});
