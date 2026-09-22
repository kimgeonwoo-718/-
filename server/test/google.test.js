import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import { fakeDb } from './fakeDb.js';
import { testKeyPair } from './play.test.js';
import { verifyIdToken, allowedClientIds, base64UrlToBytes } from '../src/google.js';
import { purchaseForDevice, purchaseOfAccount } from '../src/account.js';

const NOW = Date.UTC(2026, 0, 1, 3, 0);
const NOW_SEC = Math.floor(NOW / 1000);
const CLIENT = '123-android.apps.googleusercontent.com';
const SIGNIN = 'https://spell.test/v1/account/signin';
const SIGNOUT = 'https://spell.test/v1/account/signout';
const SUBSCRIPTION = 'https://spell.test/v1/account/subscription';

function b64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
const encode = (value) => b64url(new TextEncoder().encode(JSON.stringify(value)));

/**
 * 구글 흉내. **열쇠 한 벌을 온 시험이 같이 쓴다** — 진짜 구글도 그렇고, 서버가 공개키를
 * 캐시하므로 토큰마다 열쇠를 새로 만들면 둘째 토큰부터 못 알아본다.
 */
const KID = 'k1';
const pair = await crypto.subtle.generateKey(
  { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
  true,
  ['sign', 'verify']
);
const JWK = { ...(await crypto.subtle.exportKey('jwk', pair.publicKey)), kid: KID, alg: 'RS256' };

/** ID 토큰 하나. 진짜처럼 RS256 으로 서명한다. */
async function googleIdToken({ sub = 'user-1', aud = CLIENT, iss = 'https://accounts.google.com', exp = NOW_SEC + 3600, kid = KID } = {}) {
  const unsigned = `${encode({ alg: 'RS256', kid, typ: 'JWT' })}.${encode({ iss, aud, sub, exp, iat: NOW_SEC })}`;
  const signature = new Uint8Array(
    await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, new TextEncoder().encode(unsigned))
  );
  return { token: `${unsigned}.${b64url(signature)}`, jwk: JWK };
}

/** 구글·Play 흉내. jwks 는 시험이 넘겨준 공개키를 내놓는다. */
function upstream(jwks, { activeToken = 'paid-token' } = {}) {
  return async (url) => {
    if (url.startsWith('https://www.googleapis.com/oauth2/v3/certs')) {
      return new Response(JSON.stringify({ keys: jwks }), { status: 200 });
    }
    if (url.startsWith('https://oauth2.googleapis.com/token')) {
      return new Response(JSON.stringify({ access_token: 'ya29', expires_in: 3600 }), { status: 200 });
    }
    if (url.includes('androidpublisher.googleapis.com')) {
      const token = decodeURIComponent(url.split('/tokens/')[1]);
      return token === activeToken
        ? new Response(JSON.stringify({ subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' }), { status: 200 })
        : new Response('{}', { status: 404 });
    }
    throw new Error(`unexpected upstream ${url}`);
  };
}

async function paidEnv(overrides = {}) {
  const { pem } = await testKeyPair();
  return {
    DB: fakeDb(),
    GEMINI_API_KEY: 'server-key',
    PLAY_PACKAGE: 'com.spellkeyboard.ko',
    PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }),
    GOOGLE_CLIENT_IDS: CLIENT,
    TEST_INSTALL_IDS: '',
    ...overrides,
  };
}

const post = (url, body, headers = {}) =>
  new Request(url, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body ?? {}) });

test('로그인하면서 구매를 들고 오면 계정에 붙는다', async () => {
  const e = await paidEnv();
  const { token, jwk } = await googleIdToken();
  const deps = { fetch: upstream([jwk]), now: () => NOW };

  const res = await handle(post(SIGNIN, { idToken: token, purchaseToken: 'paid-token' }), e, deps);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.plan, 'subscriber');
  assert.match(body.deviceToken, /^[0-9a-f]{64}$/);

  // 그 기기 토큰이 정말 그 구매로 풀려야 한다.
  const found = await purchaseForDevice(e.DB, body.deviceToken, NOW);
  assert.equal(found.purchaseToken, 'paid-token');
});

test('같은 구글 계정이면 다른 기기도 같은 구독을 받는다', async () => {
  // 이 기능을 만든 이유 그 자체다 — 윈도우·아이폰에는 스토어가 없거나 다르다.
  const e = await paidEnv();
  const android = await googleIdToken();
  const deps = { fetch: upstream([android.jwk]), now: () => NOW };

  await handle(post(SIGNIN, { idToken: android.token, purchaseToken: 'paid-token' }), e, deps);

  // 윈도우: 로그인만 한다. 구매 토큰이 없다.
  const windows = await googleIdToken({ sub: 'user-1' });
  const both = deps;
  const res = await handle(post(SIGNIN, { idToken: windows.token, label: '윈도우' }), e, both);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.plan, 'subscriber', '같은 구글 계정이니 구독이 따라와야 한다');

  const state = await handle(new Request(SUBSCRIPTION, { method: 'POST', headers: { 'x-device-token': body.deviceToken } }), e, both);
  assert.deepEqual(await state.json(), { plan: 'subscriber' });
});

test('다른 구글 계정은 남의 구독을 못 받는다', async () => {
  const e = await paidEnv();
  const mine = await googleIdToken({ sub: 'user-1' });
  const deps = { fetch: upstream([mine.jwk]), now: () => NOW };
  await handle(post(SIGNIN, { idToken: mine.token, purchaseToken: 'paid-token' }), e, deps);

  const stranger = await googleIdToken({ sub: 'user-2' });
  const both = deps;
  const res = await handle(post(SIGNIN, { idToken: stranger.token }), e, both);
  assert.equal((await res.json()).plan, 'free');
});

test('엉터리 구매를 들고 와도 구독자가 안 된다', async () => {
  // 접근을 막는 것은 **교정할 때마다 다시 하는 확인**이다. 로그인 때 무엇을 붙였든
  // Play 가 아니라고 하면 아니다. 이 시험이 그 선을 지킨다.
  const e = await paidEnv();
  const liar = await googleIdToken({ sub: 'liar' });
  const deps = { fetch: upstream([liar.jwk]), now: () => NOW };

  const res = await handle(post(SIGNIN, { idToken: liar.token, purchaseToken: 'made-up' }), e, deps);
  const { deviceToken, plan } = await res.json();
  assert.equal(plan, 'free');

  const state = await handle(
    new Request(SUBSCRIPTION, { method: 'POST', headers: { 'x-device-token': deviceToken } }),
    e,
    deps
  );
  assert.deepEqual(await state.json(), { plan: 'free' }, '엉터리 구매로 구독자가 됐다');
});

test('살아 있지 않은 구매는 계정에 안 붙는다', async () => {
  // 붙이기는 **옛 계정에서 떼어 오는 동작**이다. 살아 있지도 않은 토큰으로 그 동작을
  // 부를 수 있으면 남의 자리를 흔들 여지가 생긴다. 그래서 붙이기 전에 한 번 거른다.
  const e = await paidEnv();
  const owner = await googleIdToken({ sub: 'owner' });
  const deps = { fetch: upstream([owner.jwk]), now: () => NOW };
  await handle(post(SIGNIN, { idToken: owner.token, purchaseToken: 'paid-token' }), e, deps);

  const liar = await googleIdToken({ sub: 'liar' });
  await handle(post(SIGNIN, { idToken: liar.token, purchaseToken: 'cancelled-token' }), e, deps);

  const attached = [...e.DB.accounts.values()].map((row) => row.purchase_token);
  assert.deepEqual(attached.filter(Boolean), ['paid-token'], '살아 있지 않은 토큰이 표에 들어왔다');
});

test('못 믿을 ID 토큰은 다 막는다', async () => {
  const e = await paidEnv();
  const good = await googleIdToken();
  const keys = [good.jwk];

  const bad = [
    ['남의 앱에 발급된 것', await googleIdToken({ aud: 'someone-else.apps.googleusercontent.com' })],
    ['발행자가 구글이 아닌 것', await googleIdToken({ iss: 'https://evil.example' })],
    ['기한이 지난 것', await googleIdToken({ exp: NOW_SEC - 3600 })],
  ];
  for (const [why, made] of bad) {
    const deps = { fetch: upstream(keys), now: () => NOW };
    const res = await handle(post(SIGNIN, { idToken: made.token }), e, deps);
    assert.equal(res.status, 401, why);
    assert.equal((await res.json()).error.message, 'bad_id_token', why);
  }

  // 서명을 한 글자 바꾼 것. 공개키는 멀쩡한 것을 준다.
  const tampered = good.token.slice(0, -2) + (good.token.endsWith('A') ? 'B' : 'A');
  const deps = { fetch: upstream(keys), now: () => NOW };
  const res = await handle(post(SIGNIN, { idToken: tampered }), e, deps);
  assert.equal(res.status, 401, '서명이 깨진 토큰');
});

test('모양이 아예 아닌 것도 조용히 막는다', async () => {
  const keys = [(await googleIdToken()).jwk];
  for (const junk of [null, 42, '', 'a.b', 'a.b.c', '....']) {
    assert.equal(await verifyIdToken({ idToken: junk, clientIds: [CLIENT], keys, nowMs: NOW }), null, String(junk));
  }
});

test('클라이언트 ID 를 안 적어 뒀으면 로그인을 아예 안 받는다', async () => {
  // 이게 비어 있는데 받아 주면 **남의 앱 토큰으로도 들어온다.** 열어 두느니 닫는다.
  const e = await paidEnv({ GOOGLE_CLIENT_IDS: '' });
  const { token, jwk } = await googleIdToken();
  const res = await handle(post(SIGNIN, { idToken: token }), e, { fetch: upstream([jwk]), now: () => NOW });
  assert.equal(res.status, 503);
  assert.equal((await res.json()).error.message, 'google_not_configured');
  assert.deepEqual(allowedClientIds(e), [], '설정이 비었다');
});

test('기기는 다섯 대까지만 붙는다', async () => {
  const e = await paidEnv();
  const { token, jwk } = await googleIdToken();
  const deps = { fetch: upstream([jwk]), now: () => NOW };

  for (let i = 0; i < 5; i++) {
    const res = await handle(post(SIGNIN, { idToken: token }), e, deps);
    assert.equal(res.status, 200, `${i + 1}번째`);
  }
  const over = await handle(post(SIGNIN, { idToken: token }), e, deps);
  assert.equal(over.status, 409);
  assert.equal((await over.json()).error.message, 'too_many_devices');
});

test('기기를 뺄 수 있다', async () => {
  const e = await paidEnv();
  const { token, jwk } = await googleIdToken();
  const deps = { fetch: upstream([jwk]), now: () => NOW };
  const { deviceToken } = await (await handle(post(SIGNIN, { idToken: token, purchaseToken: 'paid-token' }), e, deps)).json();

  const out = await handle(new Request(SIGNOUT, { method: 'POST', headers: { 'x-device-token': deviceToken } }), e, deps);
  assert.equal(out.status, 200);
  assert.equal(await purchaseForDevice(e.DB, deviceToken, NOW), null, '뺀 기기가 아직 살아 있다');

  // 구매는 계정에 그대로 남아야 한다 — 기기를 뺀 것이지 구독을 버린 것이 아니다.
  const accountId = [...e.DB.accounts.keys()][0];
  assert.equal(await purchaseOfAccount(e.DB, accountId), 'paid-token');
});

test('구글 공개키는 한 번만 받아 온다', async () => {
  const e = await paidEnv();
  const { token, jwk } = await googleIdToken();
  let jwksCalls = 0;
  const base = upstream([jwk]);
  const deps = {
    fetch: async (url, init) => {
      if (url.startsWith('https://www.googleapis.com/oauth2/v3/certs')) jwksCalls++;
      return base(url, init);
    },
    now: () => NOW,
  };
  await handle(post(SIGNIN, { idToken: token }), e, deps);
  await handle(post(SIGNIN, { idToken: token }), e, deps);
  assert.equal(jwksCalls, 1, '로그인할 때마다 받아 오면 구글이 느릴 때 로그인이 통째로 막힌다');
});

test('base64url 을 되읽는다', () => {
  assert.deepEqual([...base64UrlToBytes('YQ')], [97]);
  assert.deepEqual([...base64UrlToBytes('-_8')], [251, 255]);
});
