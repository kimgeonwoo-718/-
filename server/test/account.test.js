import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import { fakeDb } from './fakeDb.js';
import { testKeyPair } from './play.test.js';
import { accountForGoogle, accountForPurchase, issueDevice, purchaseForDevice, validDeviceToken } from '../src/account.js';

const GENERATE = 'https://spell.test/v1beta/models/gemini-x:generateContent';
const SUBSCRIPTION = 'https://spell.test/v1/account/subscription';
const NOON_KST = Date.UTC(2026, 0, 1, 3, 0);
const WINDOWS = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa';

/** 구글 흉내. 'paid-token' 만 구독 중이라고 답한다. */
function upstream() {
  return async (url, init) => {
    if (url.startsWith('https://oauth2.googleapis.com/token')) {
      return new Response(JSON.stringify({ access_token: 'ya29', expires_in: 3600 }), { status: 200 });
    }
    if (url.includes('androidpublisher.googleapis.com')) {
      const token = decodeURIComponent(url.split('/tokens/')[1]);
      return token === 'paid-token'
        ? new Response(JSON.stringify({ subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' }), { status: 200 })
        : new Response('{}', { status: 404 });
    }
    if (url.includes(':generateContent')) {
      const text = JSON.parse(init.body).contents[0].parts[0].text;
      return new Response(
        JSON.stringify({
          candidates: [{ content: { parts: [{ text }] } }],
          usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 10, thoughtsTokenCount: 0 },
        }),
        { status: 200 }
      );
    }
    throw new Error(`unexpected upstream ${url}`);
  };
}

async function paidEnv(overrides = {}) {
  const { pem } = await testKeyPair();
  return {
    DB: fakeDb(),
    GEMINI_API_KEY: 'server-key',
    GEMINI_MODEL: 'gemini-3.5-flash-lite',
    PLAY_PACKAGE: 'com.spellkeyboard.ko',
    PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }),
    TEST_INSTALL_IDS: '',
    SUB_DAILY_CHARS: '1000',
    ...overrides,
  };
}

/** 안드로이드가 구매를 계정에 붙이고, 윈도우가 쓸 기기 토큰을 하나 받아 둔다. */
async function linkedDevice(e, purchaseToken = 'paid-token') {
  const accountId = await accountForPurchase(e.DB, purchaseToken, NOON_KST);
  return issueDevice(e.DB, accountId, '윈도우', NOON_KST);
}

const deps = { fetch: upstream(), now: () => NOON_KST };

function correctWith(headers, chars = 100) {
  return new Request(GENERATE, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-install-id': WINDOWS, ...headers },
    body: JSON.stringify({ contents: [{ parts: [{ text: '가'.repeat(chars) }] }] }),
  });
}

test('스토어가 없는 기기는 기기 토큰으로 AI 를 쓴다', async () => {
  // 윈도우에는 스토어가 아예 없어서 구매 토큰을 얻을 길이 없다. 그 자리를 기기 토큰이 맡는다.
  const e = await paidEnv();
  const device = await linkedDevice(e);

  const res = await handle(correctWith({ 'x-device-token': device }), e, deps);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-plan'), 'subscriber');
});

test('기기 토큰과 구매 토큰이 같은 한도 통을 쓴다', async () => {
  // **이게 이 설계의 핵심이다.** 기기 토큰이 계정을 거쳐 같은 구매로 풀리므로,
  // 폰에서 쓰든 윈도우에서 쓰든 "결제 하나에 한도 하나" 가 저절로 지켜진다.
  // 여기가 깨지면 구독 하나로 기기 수만큼 한도가 나서 쓰는 사람이 늘수록 적자가 난다.
  const e = await paidEnv();
  const device = await linkedDevice(e);

  const phone = await handle(correctWith({ 'x-purchase-token': 'paid-token' }, 600), e, deps);
  assert.equal(phone.status, 200);
  assert.equal(phone.headers.get('x-quota-remaining'), '400');

  const windows = await handle(correctWith({ 'x-device-token': device }, 300), e, deps);
  assert.equal(windows.status, 200);
  assert.equal(windows.headers.get('x-quota-remaining'), '100', '폰이 쓴 만큼 윈도우에서도 줄어 있어야 한다');

  const over = await handle(correctWith({ 'x-device-token': device }, 200), e, deps);
  assert.equal(over.status, 402);
  assert.equal((await over.json()).error.message, 'sub_daily_limit');
});

test('연결되지 않은 기기 토큰은 구독자가 아니다', async () => {
  const e = await paidEnv();
  const stranger = 'f'.repeat(64);

  const res = await handle(correctWith({ 'x-device-token': stranger }), e, deps);
  assert.equal(res.status, 402);
  assert.equal((await res.json()).error.message, 'subscribers_only');
});

test('모양이 틀린 기기 토큰은 아예 신원으로 안 친다', async () => {
  const e = await paidEnv();
  for (const bad of ['', 'short', 'g'.repeat(64), '../../etc/passwd']) {
    assert.equal(validDeviceToken(bad), false, bad);
    const res = await handle(correctWith({ 'x-device-token': bad }), e, deps);
    assert.equal(res.status, 402, bad);
  }
});

test('둘 다 실려 오면 구매 토큰 쪽을 믿는다', async () => {
  // 스토어에 직접 물어볼 수 있는 쪽이 더 확실한 근거다.
  const e = await paidEnv();
  const device = await linkedDevice(e, 'other-token');

  const res = await handle(
    correctWith({ 'x-purchase-token': 'paid-token', 'x-device-token': device }),
    e,
    deps
  );
  assert.equal(res.status, 200, '구매 토큰이 살아 있으면 통과해야 한다');
});

test('구독 상태만 물어보는 길이 있다', async () => {
  const e = await paidEnv();
  const device = await linkedDevice(e);

  const res = await handle(
    new Request(SUBSCRIPTION, { method: 'POST', headers: { 'x-device-token': device } }),
    e,
    deps
  );
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { plan: 'subscriber' });
});

test('구독 상태를 물어도 구매 토큰은 내보내지 않는다', async () => {
  // 구매 토큰은 그 자체가 구독 증명이다. 윈도우로 내려보내면 그게 새는 통로가 된다.
  const e = await paidEnv();
  const device = await linkedDevice(e);

  const res = await handle(
    new Request(SUBSCRIPTION, { method: 'POST', headers: { 'x-device-token': device } }),
    e,
    deps
  );
  const body = await res.text();
  assert.equal(body.includes('paid-token'), false, '몸통에 구매 토큰이 섞여 나왔다');
  assert.equal([...res.headers.values()].some((v) => v.includes('paid-token')), false, '헤더로 샜다');
});

test('연결 안 된 기기가 물으면 404 로 갈린다', async () => {
  // 402(구독 아님)와 달라야 한다. 윈도우가 "연결부터 하세요" 를 띄울 수 있어야 해서다.
  const e = await paidEnv();
  const res = await handle(
    new Request(SUBSCRIPTION, { method: 'POST', headers: { 'x-device-token': 'a'.repeat(64) } }),
    e,
    deps
  );
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error.message, 'device_not_linked');
});

test('같은 구매로 다시 붙여도 계정은 하나다', async () => {
  // 앱을 지웠다 깔아도 계정이 갈리면 안 된다. 갈리면 기기 연결이 통째로 끊긴다.
  const e = await paidEnv();
  const first = await accountForPurchase(e.DB, 'paid-token', NOON_KST);
  const again = await accountForPurchase(e.DB, 'paid-token', NOON_KST + 1000);
  assert.equal(again, first);
  assert.equal(e.DB.accounts.size, 1);
});

test('기기 토큰은 해시로만 저장한다', async () => {
  // 데이터베이스가 새도 그것만으로 남의 기기 행세를 할 수 없어야 한다.
  const e = await paidEnv();
  const device = await linkedDevice(e);
  assert.equal(e.DB.devices.has(device), false, '토큰이 그대로 저장돼 있다');
  assert.equal(e.DB.devices.size, 1);

  const found = await purchaseForDevice(e.DB, device, NOON_KST);
  assert.equal(found.purchaseToken, 'paid-token');
});

// --- 회원 탈퇴 ---------------------------------------------------------------

const DELETE = 'https://spell.test/v1/account/delete';

function deleteWith(device) {
  return new Request(DELETE, { method: 'POST', headers: { 'x-device-token': device } });
}

test('탈퇴하면 계정과 거기 붙은 기기가 전부 사라진다', async () => {
  // 폰에서 탈퇴를 눌렀는데 윈도우가 계속 구독을 빌려 쓰면 탈퇴가 아니다.
  const e = await paidEnv();
  const accountId = await accountForPurchase(e.DB, 'paid-token', NOON_KST);
  const phone = await issueDevice(e.DB, accountId, '폰', NOON_KST);
  const windows = await issueDevice(e.DB, accountId, '윈도우', NOON_KST);

  const res = await handle(deleteWith(phone), e, deps);
  assert.equal(res.status, 200);
  assert.equal(e.DB.accounts.size, 0);
  assert.equal(e.DB.devices.size, 0);

  const after = await handle(correctWith({ 'x-device-token': windows }), e, deps);
  assert.equal(after.status, 402, '빌려 쓰던 기기는 끊긴다');
});

test('탈퇴해도 구매한 폰은 구매 토큰으로 계속 쓴다', async () => {
  // 구독은 Play 에 있고 탈퇴로 해지되지 않는다. 돈을 계속 내는 사람을 막으면 안 된다.
  const e = await paidEnv();
  const device = await linkedDevice(e);
  await handle(deleteWith(device), e, deps);

  const res = await handle(correctWith({ 'x-purchase-token': 'paid-token' }), e, deps);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-plan'), 'subscriber');
});

test('다른 사람 계정은 건드리지 않는다', async () => {
  const e = await paidEnv();
  const mine = await linkedDevice(e, 'paid-token');
  const theirs = await linkedDevice(e, 'other-token');

  await handle(deleteWith(mine), e, deps);
  assert.equal(e.DB.accounts.size, 1);
  assert.ok(await purchaseForDevice(e.DB, theirs, NOON_KST), '남의 기기는 그대로 산다');
});

test('모르는 기기 토큰으로는 탈퇴가 안 된다', async () => {
  const e = await paidEnv();
  await linkedDevice(e);

  const unknown = await handle(deleteWith('f'.repeat(64)), e, deps);
  assert.equal(unknown.status, 404);
  const malformed = await handle(deleteWith('nope'), e, deps);
  assert.equal(malformed.status, 400);
  assert.equal(e.DB.accounts.size, 1, '아무것도 안 지워졌다');
});

// --- 로그인 먼저, 구독 나중 ---------------------------------------------------------

const ATTACH = 'https://spell.test/v1/account/attach';

function attachWith(device, purchaseToken) {
  return new Request(ATTACH, {
    method: 'POST',
    headers: { 'x-device-token': device, 'content-type': 'application/json' },
    body: JSON.stringify({ purchaseToken }),
  });
}

/** 구글로 로그인만 한 계정(구매 없음)에 폰과 PC 가 붙어 있다. */
async function signedInWithoutPurchase(e) {
  const accountId = await accountForGoogle(e.DB, 'google-sub-hash', NOON_KST);
  const phone = await issueDevice(e.DB, accountId, '폰', NOON_KST);
  const pc = await issueDevice(e.DB, accountId, '윈도우', NOON_KST);
  return { phone, pc };
}

test('로그인 먼저 하고 나중에 산 구독도 계정에 붙어 PC 가 쓴다', async () => {
  // 로그인할 때만 붙였더니 이 순서에서 PC 는 구독이 없는 걸로 봤다.
  const e = await paidEnv();
  const { phone, pc } = await signedInWithoutPurchase(e);

  const before = await handle(correctWith({ 'x-device-token': pc }), e, deps);
  assert.equal(before.status, 402, '붙이기 전에는 PC 가 못 쓴다');

  const res = await handle(attachWith(phone, 'paid-token'), e, deps);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { plan: 'subscriber' });

  const after = await handle(correctWith({ 'x-device-token': pc }), e, deps);
  assert.equal(after.status, 200);
  assert.equal(after.headers.get('x-plan'), 'subscriber');
});

test('살아 있지 않은 구매는 붙이지 않는다', async () => {
  const e = await paidEnv();
  const { phone, pc } = await signedInWithoutPurchase(e);

  const res = await handle(attachWith(phone, 'expired-token'), e, deps);
  assert.equal(res.status, 402);
  const after = await handle(correctWith({ 'x-device-token': pc }), e, deps);
  assert.equal(after.status, 402, 'PC 는 여전히 못 쓴다');
});

test('붙이기도 구매 토큰을 되돌려 주지 않는다', async () => {
  const e = await paidEnv();
  const { phone } = await signedInWithoutPurchase(e);
  const res = await handle(attachWith(phone, 'paid-token'), e, deps);
  const text = await res.text();
  assert.ok(!text.includes('paid-token'));
  for (const [, value] of res.headers) assert.ok(!value.includes('paid-token'));
});

test('붙이기: 모르는 기기는 404, 모양이 틀리면 400', async () => {
  const e = await paidEnv();
  assert.equal((await handle(attachWith('f'.repeat(64), 'paid-token'), e, deps)).status, 404);
  assert.equal((await handle(attachWith('nope', 'paid-token'), e, deps)).status, 400);
  assert.equal((await handle(attachWith('f'.repeat(64), ''), e, deps)).status, 400);
});
