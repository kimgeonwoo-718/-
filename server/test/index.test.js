import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle, GoogleRelay } from '../src/index.js';
import { fakeDb } from './fakeDb.js';
import { testKeyPair } from './play.test.js';
// 한도는 코드에서 가져온다. 손으로 적어 두면 값을 바꿀 때마다 시험을 같이 고쳐야 한다.
import { DEFAULT_SUB_DAILY_CHARS } from '../src/quota.js';

const INSTALL = '3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f';
const GENERATE = 'https://spell.test/v1beta/models/gemini-x:generateContent';
const NOON_KST = Date.UTC(2026, 0, 1, 3, 0); // KST 12:00

function env(overrides = {}) {
  return {
    DB: fakeDb(),
    GEMINI_API_KEY: 'server-key',
    PLAY_PACKAGE: 'com.spellkeyboard.ko',
    // AI 는 구독자 전용이다. 중계·토큰 세기 같은 것을 보는 시험은 전부 구독자여야
    // 본론까지 간다. 무료가 막히는 것을 보는 시험만 이 값을 비운다.
    TEST_INSTALL_IDS: INSTALL,
    // 모델을 못박아 둔다. 안 박으면 서버가 구글에 목록을 물어 제일 좋은 것을 고르는데,
    // 그러면 중계·헤더·한도를 보는 시험들이 바깥 호출 하나에 같이 흔들린다.
    // 고르는 동작 자체는 아래 '모델 고르기' 시험들이 따로 본다.
    GEMINI_MODEL: 'gemini-3.5-flash-lite',
    ...overrides,
  };
}

/** 구글 흉내. 어디로 무엇을 보냈는지 붙잡아 둔다. */
function upstream({ status = 200, activeToken = null, echo = false } = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url.startsWith('https://oauth2.googleapis.com/token')) {
      return new Response(JSON.stringify({ access_token: 'ya29', expires_in: 3600 }), { status: 200 });
    }
    if (url.includes('androidpublisher.googleapis.com')) {
      const token = decodeURIComponent(url.split('/tokens/')[1]);
      if (token === activeToken) return new Response(JSON.stringify({ subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' }), { status: 200 });
      return new Response('{}', { status: 404 });
    }
    if (url.endsWith('/v1beta/models?pageSize=200')) {
      return new Response(JSON.stringify({ models: [{ name: 'models/gemini-x', supportedGenerationMethods: ['generateContent'] }] }), { status: 200 });
    }
    if (url.includes(':generateContent')) {
      if (status !== 200) return new Response(JSON.stringify({ error: { message: 'boom' } }), { status });
      // echo: 원문을 그대로 돌려준다. 서버에 길이 검사가 있어서(교정문이 원문의 60~160%),
      // 긴 글을 보내는 시험은 답도 그만큼 길어야 본론까지 간다. '고침' 두 글자로는 막힌다.
      const text = echo ? JSON.parse(init.body).contents[0].parts[0].text : '고침';
      return new Response(JSON.stringify({
        candidates: [{ content: { parts: [{ text }] } }],
        usageMetadata: { promptTokenCount: 120, candidatesTokenCount: 30, thoughtsTokenCount: 7 },
      }), { status: 200 });
    }
    throw new Error(`unexpected upstream ${url}`);
  };
  return { fetchImpl, calls };
}

/** 앱이 보내는 모양. 고칠 글이 들어 있어야 한다 — 빈 요청은 400 으로 막힌다. */
const BODY = JSON.stringify({ contents: [{ parts: [{ text: '안녕하새요' }] }] });

function generate(headers = {}, body = BODY) {
  return new Request(GENERATE, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'cf-connecting-ip': '1.2.3.4', 'x-install-id': INSTALL, ...headers },
    body,
  });
}

test('health 는 키 없이도 응답한다', async () => {
  const res = await handle(new Request('https://spell.test/health'), { DB: fakeDb() });
  assert.equal(res.status, 200);
});

test('서버에 키가 없으면 503 으로 말한다', async () => {
  const res = await handle(generate(), env({ GEMINI_API_KEY: '' }));
  assert.equal(res.status, 503);
});

test('설치 ID 없이는 교정하지 않는다', async () => {
  const req = new Request(GENERATE, { method: 'POST', body: '{}' });
  const res = await handle(req, env(), { fetch: upstream().fetchImpl });
  assert.equal(res.status, 400);
  assert.equal((await res.json()).error.message, 'invalid_install_id');
});

test('요청을 구글로 넘기고 우리 키를 붙인다 — 클라이언트 헤더는 넘기지 않는다', async () => {
  const up = upstream();
  const res = await handle(generate({ 'x-purchase-token': 'secret' }), env(), { fetch: up.fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).candidates[0].content.parts[0].text, '고침');

  const call = up.calls.find((c) => c.url.includes(':generateContent'));
  // 주소의 gemini-x 는 앱이 보낸 이름이다. 서버가 정한 이름으로 나가야 한다.
  assert.equal(call.url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent');
  assert.equal(call.init.headers['x-goog-api-key'], 'server-key');
  assert.equal(call.init.headers['x-install-id'], undefined);
  assert.equal(call.init.headers['x-purchase-token'], undefined);
});

test('무료는 AI 를 아예 쓸 수 없다 — 구글까지 가지 않는다', async () => {
  const up = upstream();
  const res = await handle(generate(), env({ TEST_INSTALL_IDS: '' }), { fetch: up.fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 402);
  assert.equal((await res.json()).error.message, 'subscribers_only');
  assert.equal(res.headers.get('x-plan'), 'free');
  assert.equal(res.headers.get('x-quota-limit'), '0');
  assert.equal(res.headers.get('x-quota-remaining'), '0');
  assert.equal(up.calls.length, 0, '돈이 나가는 곳까지 가지 않는다');
});

test('구글이 거절한 요청은 세지 않는다', async () => {
  const e = env();
  const res = await handle(generate(), e, { fetch: upstream({ status: 503 }).fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 503, '상태를 그대로 전한다');
  assert.equal(res.headers.get('x-quota-remaining'), String(DEFAULT_SUB_DAILY_CHARS), '깎이지 않았다');
  assert.equal(e.DB.usage.size, 0);
});

test('자정을 넘기면 되살아난다', async () => {
  const e = env({ SUB_DAILY_CHARS: '120' });
  const up = upstream({ echo: true });
  const body = JSON.stringify({ contents: [{ parts: [{ text: '가'.repeat(100) }] }] });
  const deps = { fetch: up.fetchImpl, now: () => NOON_KST };

  assert.equal((await handle(generate({}, body), e, deps)).status, 200);
  const over = await handle(generate({}, body), e, deps);
  assert.equal(over.status, 402);
  assert.equal((await over.json()).error.message, 'sub_daily_limit');

  const nextDay = NOON_KST + 12 * 60 * 60 * 1000 + 60_000; // KST 00:01
  const res = await handle(generate({}, body), e, { fetch: up.fetchImpl, now: () => nextDay });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-quota-remaining'), '20');
});

/** 구독자 요청. 글자 수를 재야 하니 고칠 글이 들어 있어야 한다. */
function paidGenerate(chars) {
  const text = '가'.repeat(chars);
  return generate({ 'x-purchase-token': 'paid-token' }, JSON.stringify({ contents: [{ parts: [{ text }] }] }));
}

test('같은 구독이면 폰이 달라도 한도를 같이 쓴다', async () => {
  // 한도를 설치 ID 로 세면 앱을 지웠다 깔거나 같은 구글 계정을 여러 폰에 넣는 것만으로
  // 한도가 새로 난다. 그러면 구독 하나로 다섯이 쓸 때 원가는 다섯 배인데 받는 돈은
  // 그대로다 — 한 사람이 꽉 써서 남는 것이 690원뿐이라 둘만 나눠 써도 적자다.
  const { pem } = await testKeyPair();
  const e = env({
    TEST_INSTALL_IDS: '',
    PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }),
    SUB_DAILY_CHARS: '1000',
  });
  const deps = { fetch: upstream({ activeToken: 'paid-token', echo: true }).fetchImpl, now: () => NOON_KST };
  const fromPhone = (installId, chars) =>
    handle(
      new Request(GENERATE, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'cf-connecting-ip': '1.2.3.4',
          'x-install-id': installId,
          'x-purchase-token': 'paid-token',
        },
        body: JSON.stringify({ contents: [{ parts: [{ text: '가'.repeat(chars) }] }] }),
      }),
      e,
      deps
    );

  // 첫 폰이 600 자를 쓴다.
  const first = await fromPhone('11111111-1111-4111-8111-111111111111', 600);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get('x-quota-remaining'), '400');

  // **설치 ID 가 다른 둘째 폰**이 이어서 쓴다. 같은 구독이므로 남은 것은 400 이어야 한다.
  const second = await fromPhone('22222222-2222-4222-8222-222222222222', 300);
  assert.equal(second.status, 200);
  assert.equal(second.headers.get('x-quota-remaining'), '100');

  // 합쳐서 900 을 썼으니 200 자짜리는 안 들어간다.
  const over = await fromPhone('33333333-3333-4333-8333-333333333333', 200);
  assert.equal(over.status, 402);
  assert.equal((await over.json()).error.message, 'sub_daily_limit');
});

test('구독자는 횟수 대신 글자 수로 센다 — 확인은 Play 에 한 번만 물어본다', async () => {
  const { pem } = await testKeyPair();
  const e = env({
    // 진짜 결제 확인 길을 보는 시험이라 시험용 목록을 비운다.
    TEST_INSTALL_IDS: '',
    PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }),
    SUB_DAILY_CHARS: '1000',
  });
  const up = upstream({ activeToken: 'paid-token', echo: true });
  const deps = { fetch: up.fetchImpl, now: () => NOON_KST };

  // 횟수가 아니라 글자 수로 세니 일곱 번도 한도 안이다.
  for (let i = 0; i < 7; i++) {
    const res = await handle(paidGenerate(100), e, deps);
    assert.equal(res.status, 200, `${i + 1}번째`);
    assert.equal(res.headers.get('x-plan'), 'subscriber');
    assert.equal(res.headers.get('x-quota-unit'), 'chars');
    assert.equal(res.headers.get('x-quota-limit'), '1000');
    assert.equal(res.headers.get('x-quota-remaining'), String(1000 - 100 * (i + 1)));
  }
  // 300 남았는데 400 자짜리는 안 된다. 다 쓴 요청은 세지 않는다.
  const over = await handle(paidGenerate(400), e, deps);
  assert.equal(over.status, 402);
  assert.equal((await over.json()).error.message, 'sub_daily_limit');
  assert.equal(over.headers.get('x-quota-remaining'), '300');
  // 300 자짜리는 딱 맞게 들어간다.
  const fits = await handle(paidGenerate(300), e, deps);
  assert.equal(fits.status, 200);
  assert.equal(fits.headers.get('x-quota-remaining'), '0');
  // 확인 결과를 캐시해서 구글에는 한 번만 물어본다.
  assert.equal(up.calls.filter((c) => c.url.includes('androidpublisher')).length, 1);
});

test('구독자의 아주 짧은 요청도 최소 50 자로 친다 — 한 글자짜리 만 번이 공짜가 되지 않게', async () => {
  const { pem } = await testKeyPair();
  const e = env({
    PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }),
    SUB_DAILY_CHARS: '1000',
  });
  const deps = { fetch: upstream({ activeToken: 'paid-token', echo: true }).fetchImpl, now: () => NOON_KST };
  const res = await handle(paidGenerate(1), e, deps);
  assert.equal(res.headers.get('x-quota-remaining'), '950');
});

test('구독자의 헤더에는 단위가 글자로 실린다', async () => {
  const res = await handle(generate(), env(), { fetch: upstream().fetchImpl, now: () => NOON_KST });
  assert.equal(res.headers.get('x-quota-unit'), 'chars');
  assert.equal(res.headers.get('x-plan'), 'subscriber');
});

test('가짜 구매 토큰은 무료로 취급한다', async () => {
  const { pem } = await testKeyPair();
  const e = env({ TEST_INSTALL_IDS: '', PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }) });
  const res = await handle(generate({ 'x-purchase-token': 'forged' }), e, { fetch: upstream({ activeToken: 'real' }).fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 402);
  assert.equal((await res.json()).error.message, 'subscribers_only');
  assert.equal(res.headers.get('x-plan'), 'free');
});

test('서비스 계정이 없으면 토큰이 있어도 무료다', async () => {
  const e = env({ TEST_INSTALL_IDS: '' });
  const res = await handle(generate({ 'x-purchase-token': 'paid' }), e, { fetch: upstream({ activeToken: 'paid' }).fetchImpl, now: () => NOON_KST });
  assert.equal(res.headers.get('x-plan'), 'free');
});

test('Play 확인이 실패하면 그 순간만 무료로 보고 캐시하지 않는다', async () => {
  const { pem } = await testKeyPair();
  const e = env({ TEST_INSTALL_IDS: '', PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }) });
  let playDown = true;
  const good = upstream({ activeToken: 'paid' }).fetchImpl;
  const fetchImpl = async (url, init) => {
    if (playDown && url.includes('androidpublisher')) return new Response('', { status: 503 });
    return good(url, init);
  };
  const first = await handle(generate({ 'x-purchase-token': 'paid' }), e, { fetch: fetchImpl, now: () => NOON_KST });
  assert.equal(first.headers.get('x-plan'), 'free');

  playDown = false;
  const second = await handle(generate({ 'x-purchase-token': 'paid' }), e, { fetch: fetchImpl, now: () => NOON_KST });
  assert.equal(second.headers.get('x-plan'), 'subscriber', '복구되면 바로 구독자로 본다');
});

test('너무 긴 요청은 구글까지 가지 않는다', async () => {
  const up = upstream();
  const res = await handle(generate({}, 'x'.repeat(70 * 1024)), env(), { fetch: up.fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 413);
  assert.equal(up.calls.length, 0);
});

test('모델을 못박아 두면 밖에 묻지 않는다', async () => {
  const up = upstream();
  const req = new Request('https://spell.test/v1beta/models?pageSize=200');
  const res = await handle(req, env(), { fetch: up.fetchImpl });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).models[0].name, 'models/gemini-3.5-flash-lite');
  assert.equal(up.calls.length, 0, '못박힌 이름이 있으면 물어볼 이유가 없다');
});

// --- 모델 고르기 -----------------------------------------------------------------
//
// 예전에는 앱이 구글 목록을 받아 제일 좋은 lite 를 골랐고, 구글이 새 모델을 내면
// 저절로 갈아탔다. OpenAI 로 갔다 되돌아오면서 그 자리를 이름 하나로 못박아 버렸는데,
// 그러면 구글이 새 것을 내도 영영 낡은 것에 머문다. 아래가 그 되살린 동작이다.

/** lite 둘·flash 하나·pro 하나를 내주는 구글. 제일 좋은 lite 는 3.8 이다. */
function upstreamWithModels(names) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url.endsWith('/v1beta/models?pageSize=200')) {
      return new Response(
        JSON.stringify({
          models: names.map((name) => ({ name: 'models/' + name, supportedGenerationMethods: ['generateContent'] })),
        }),
        { status: 200 }
      );
    }
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '고침' }] } }] }), { status: 200 });
  };
  return { calls, fetchImpl };
}

// 2026-09-14 에 구글이 실제로 내주던 목록. 지어낸 이름으로 재면 실제와 어긋난다.
const LIVE_MODELS = [
  'gemini-3-flash-preview', 'gemini-3.1-flash-lite', 'gemini-3.1-flash-lite-preview',
  'gemini-3.1-pro-preview', 'gemini-3.5-flash', 'gemini-3.5-flash-lite',
  'gemini-3.6-flash', 'gemini-3.7-flash', 'gemini-3.8-flash',
  'gemini-flash-latest', 'gemini-flash-lite-latest', 'gemini-pro-latest',
];

test('모델을 안 박으면 구글에 물어 제일 좋은 lite 를 고른다', async () => {
  const up = upstreamWithModels(LIVE_MODELS);
  const e = env({ GEMINI_MODEL: '' });
  const req = new Request('https://spell.test/v1beta/models?pageSize=200');
  const res = await handle(req, e, { fetch: up.fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 200);
  // lite 가 먼저다. 세대를 먼저 보게 하면 3.8-flash 가 뽑혀 값이 몇 배로 뛴다 —
  // 한 번 그렇게 해 봤다가 되돌렸다. 맞춤법 교정에 큰 모델을 당길 이유가 없다.
  assert.equal((await res.json()).models[0].name, 'models/gemini-3.5-flash-lite');
});

test('한 번 고르면 하루는 다시 묻지 않는다 — 사용자가 기다리는 시간이 0 이어야 한다', async () => {
  const up = upstreamWithModels(LIVE_MODELS);
  const e = env({ GEMINI_MODEL: '' });
  for (let i = 0; i < 3; i++) {
    await handle(generate(), e, { fetch: up.fetchImpl, now: () => NOON_KST });
  }
  const asked = up.calls.filter((c) => c.url.endsWith('/v1beta/models?pageSize=200'));
  assert.equal(asked.length, 1, '세 번 교정해도 목록은 한 번만 묻는다');
  const sent = up.calls.filter((c) => c.url.includes(':generateContent'));
  assert.equal(sent.length, 3);
  assert.ok(sent.every((c) => c.url.includes('gemini-3.5-flash-lite')), '고른 것으로 나간다');
});

test('목록을 못 받으면 아는 이름으로 가되, 그 실패를 하루 물고 있지 않는다', async () => {
  let listOk = false;
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url.endsWith('/v1beta/models?pageSize=200')) {
      if (!listOk) return new Response('{}', { status: 503 });
      return new Response(
        JSON.stringify({ models: [{ name: 'models/gemini-9.9-flash-lite', supportedGenerationMethods: ['generateContent'] }] }),
        { status: 200 }
      );
    }
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '고침' }] } }] }), { status: 200 });
  };
  const e = env({ GEMINI_MODEL: '' });

  await handle(generate(), e, { fetch: fetchImpl, now: () => NOON_KST });
  assert.ok(
    calls.find((c) => c.url.includes(':generateContent')).url.includes('gemini-3.5-flash-lite'),
    '못 물었으면 아는 이름으로 간다'
  );

  // 구글이 잠깐 삐끗한 것 때문에 하루를 낡은 모델로 보내면 고치는 의미가 없다.
  listOk = true;
  await handle(generate(), e, { fetch: fetchImpl, now: () => NOON_KST + 1000 });
  const last = calls.filter((c) => c.url.includes(':generateContent')).at(-1);
  assert.ok(last.url.includes('gemini-9.9-flash-lite'), '다음 요청에서 다시 묻는다');
});

test('AI_PROVIDER 로 어느 쪽인지 정한다 — 키를 지우지 않아도 된다', async () => {
  const both = { GEMINI_API_KEY: 'g', OPENAI_API_KEY: 'o' };
  const list = async (overrides) =>
    (await (await handle(new Request('https://spell.test/v1beta/models'), env(overrides))).json())
      .models[0].name;

  assert.equal(await list({ ...both, AI_PROVIDER: 'gemini' }), 'models/gemini-3.5-flash-lite');
  assert.equal(await list({ ...both, AI_PROVIDER: 'openai' }), 'models/gpt-5-nano');
  // 안 적으면 예전 규칙 그대로: OpenAI 키가 있으면 OpenAI.
  assert.equal(await list(both), 'models/gpt-5-nano');
  assert.equal(await list({ GEMINI_API_KEY: 'g' }), 'models/gemini-3.5-flash-lite');
});

test('쓰기로 한 쪽의 키가 없으면 503 — 다른 쪽 키가 있어도', async () => {
  const res = await handle(generate(), env({ GEMINI_API_KEY: '', OPENAI_API_KEY: 'o', AI_PROVIDER: 'gemini' }));
  assert.equal(res.status, 503);
  assert.equal((await res.json()).error.message, 'server_not_configured');
});

test('모르는 경로는 404', async () => {
  const res = await handle(new Request('https://spell.test/v1beta/other'), env(), { fetch: upstream().fetchImpl });
  assert.equal(res.status, 404);
});

// --- 미국 붙박이 중계 객체 ------------------------------------------------------

/** Durable Object 바인딩 흉내. 어디로 무엇을 보내 달라고 했는지 붙잡아 둔다. */
function fakeRelay(response) {
  const calls = [];
  return {
    calls,
    idFromName: (name) => `id:${name}`,
    get: (id, options) => ({
      fetch: async (url, init) => {
        calls.push({ id, options, url, init });
        return response();
      },
    }),
  };
}

test('번역도 교정과 같은 통에서 깎인다 — 한도는 하나다', async () => {
  // echo: 답이 원문만큼 길어야 길이 검사(tooDifferent)를 지나 본론까지 간다.
  const up = upstream({ echo: true });
  const e = env();
  // 최소 과금(50자)보다 길어야 한다. 짧으면 50 으로 깎여서 "글자 수만큼" 을 못 본다.
  const long =
    '오늘 날씨가 참 좋은 것 같아요 우리 내일 어디서 만날까요 시간은 저녁 일곱 시쯤이 괜찮을 것 같은데 어떠세요';
  const body = JSON.stringify({ contents: [{ parts: [{ text: long }] }] });

  // 교정 한 번.
  const one = await handle(generate({}, body), e, { fetch: up.fetchImpl, now: () => NOON_KST });
  assert.equal(one.status, 200);
  const afterCorrect = Number(one.headers.get('x-quota-remaining'));
  assert.equal(afterCorrect, DEFAULT_SUB_DAILY_CHARS - long.length, '글자 수만큼 깎인다');

  // 같은 글을 번역으로 한 번 더. **같은 통에서** 또 깎여야 한다.
  const req = new Request(GENERATE + '?translate=en', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-install-id': INSTALL },
    body,
  });
  const two = await handle(req, e, { fetch: up.fetchImpl, now: () => NOON_KST });
  assert.equal(two.status, 200);
  assert.equal(
    Number(two.headers.get('x-quota-remaining')),
    afterCorrect - long.length,
    '번역은 따로 세지 않는다 — 값이 글자당 똑같이 매겨지므로 통이 하나여야 한다'
  );
});

test('중계 객체가 있으면 구글 호출은 그 안에서 나간다 — 미국 서부 위치 힌트로', async () => {
  const relay = fakeRelay(() => new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '고침' }] } }] }), { status: 200 }));
  const direct = upstream();
  const res = await handle(generate(), env({ RELAY: relay }), { fetch: direct.fetchImpl, now: () => NOON_KST });

  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-quota-remaining'), String(DEFAULT_SUB_DAILY_CHARS - 50));
  assert.equal(relay.calls.length, 1);
  assert.equal(relay.calls[0].url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent');
  assert.equal(relay.calls[0].init.method, 'POST');
  assert.equal(relay.calls[0].init.body, BODY, '교정은 앱 본문을 그대로 넘긴다');
  // 한국에서 가까운 미국이라야 한다. 홍콩은 구글·OpenAI 가 거절하므로 미국은 유지한다.
  assert.equal(relay.calls[0].options.locationHint, 'wnam');
  // 위치 힌트는 **객체를 처음 만들 때만** 먹는다. 이름이 그대로면 미국 동부에 이미
  // 만들어진 객체를 계속 쓰게 되니, 자리를 옮길 때는 이름도 같이 바뀌어야 한다.
  assert.notEqual(relay.calls[0].id, 'id:google-relay', '옛 이름을 그대로 쓰면 자리가 안 옮겨진다');
  assert.ok(!direct.calls.some((c) => c.url.includes('generativelanguage')), '직접 보내면 안 된다');
});

test('중계 객체는 키를 붙여 구글로 그대로 넘긴다', async () => {
  const seen = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    seen.push({ url, init });
    return new Response('{"models":[]}', { status: 200, headers: { 'x-google-internal': 'drop-me' } });
  };
  try {
    const relay = new GoogleRelay({}, { GEMINI_API_KEY: 'server-key\n' });
    const res = await relay.fetch(new Request('https://generativelanguage.googleapis.com/v1beta/models?pageSize=200'));
    assert.equal(res.status, 200);
    assert.equal(await res.text(), '{"models":[]}');
    assert.equal(res.headers.get('x-google-internal'), null);
    assert.equal(seen[0].url, 'https://generativelanguage.googleapis.com/v1beta/models?pageSize=200');
    assert.equal(seen[0].init.headers['x-goog-api-key'], 'server-key', '줄바꿈은 떼고 붙인다');
    assert.equal(seen[0].init.body, null);
  } finally {
    globalThis.fetch = realFetch;
  }
});

// --- 토큰 사용량 ---------------------------------------------------------------

test('성공한 교정의 토큰 수를 날짜별로 쌓고 /stats 로 보여준다', async () => {
  const e = env();
  const up = upstream();
  const deps = { fetch: up.fetchImpl, now: () => NOON_KST };
  await handle(generate(), e, deps);
  await handle(generate(), e, deps);
  // 구글이 거절한 것은 세지 않는다 — 돈도 안 나간다.
  await handle(generate(), e, { fetch: upstream({ status: 503 }).fetchImpl, now: () => NOON_KST });

  const res = await handle(new Request('https://spell.test/stats'), e, deps);
  assert.equal(res.status, 200);
  const { days } = await res.json();
  assert.equal(days.length, 1);
  assert.deepEqual(days[0], { day: '2026-01-01', requests: 2, prompt: 240, output: 60, thoughts: 14 });
});

test('usageMetadata 가 없어도 죽지 않는다', async () => {
  const e = env();
  const fetchImpl = async (url) =>
    url.includes(':generateContent')
      ? new Response('{"candidates":[{"content":{"parts":[{"text":"안녕하세요"}]}}]}', { status: 200 })
      : new Response('{"models":[]}', { status: 200 });
  const res = await handle(generate(), e, { fetch: fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 200);
  assert.deepEqual(e.DB.tokens.get('2026-01-01'), { requests: 1, prompt: 0, output: 0, thoughts: 0 });
});

test('개인정보 처리방침 페이지는 키 없이도 뜬다', async () => {
  const res = await handle(new Request('https://spell.test/privacy'), env({ GEMINI_API_KEY: '' }));
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /text\/html/);
  const body = await res.text();
  assert.match(body, /기기 안에서/);
  assert.ok(!body.includes('문의:'), '연락처가 설정돼 있지 않으면 빈 줄을 만들지 않는다');
});

test('개인정보 처리방침은 계정과 탈퇴를 설명한다', async () => {
  // Play 는 계정을 만드는 앱에 "웹에서 삭제를 요청하는 길" 을 요구한다. 이 페이지의
  // #delete 가 그 자리다.
  const res = await handle(new Request('https://spell.test/privacy'), env({ CONTACT_EMAIL: 'help@example.com' }));
  const body = await res.text();
  assert.match(body, /id="delete"/);
  assert.match(body, /회원 탈퇴/);
  assert.match(body, /이름·이메일·프로필 사진은 서버에 저장하지 않습니다/);
  assert.match(body, /문의: help@example\.com/);
});

test('이용약관 페이지가 뜬다', async () => {
  const res = await handle(new Request('https://spell.test/terms'), env({ GEMINI_API_KEY: '' }));
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /text\/html/);
  const body = await res.text();
  assert.match(body, /서비스 이용약관/);
  assert.match(body, /탈퇴해도 구독은 해지되지 않습니다/);
});


test('시험용 설치 ID 는 결제 없이 구독자로 친다', async () => {
  const e = env({ TEST_INSTALL_IDS: 'other-id, ' + INSTALL });
  const res = await handle(generate(), e, { fetch: upstream().fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-plan'), 'subscriber');
  assert.equal(res.headers.get('x-quota-unit'), 'chars');
});

test('시험용 목록에 없는 설치 ID 는 그대로 무료라 막힌다', async () => {
  const e = env({ TEST_INSTALL_IDS: 'someone-else' });
  const res = await handle(generate(), e, { fetch: upstream().fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 402);
  assert.equal(res.headers.get('x-plan'), 'free');
});

test('시험용 목록이 비어 있으면 아무도 구독자가 아니다', async () => {
  for (const listed of ['', '   ', ',,', undefined]) {
    const res = await handle(generate(), env({ TEST_INSTALL_IDS: listed }), {
      fetch: upstream().fetchImpl,
      now: () => NOON_KST,
    });
    assert.equal(res.status, 402, 'TEST_INSTALL_IDS=' + JSON.stringify(listed));
    assert.equal(res.headers.get('x-plan'), 'free', 'TEST_INSTALL_IDS=' + JSON.stringify(listed));
  }
});

test('구글로 번역하면 우리 번역 지시문을 싣는다 — 교정은 앱 본문 그대로', async () => {
  const seen = [];
  const fetchImpl = async (url, init) => {
    seen.push(JSON.parse(init.body));
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: 'Did you eat?' }] } }] }), { status: 200 });
  };
  const e = env({ AI_PROVIDER: 'gemini' });
  const body = JSON.stringify({ contents: [{ parts: [{ text: '밥 먹었어?' }] }] });

  const res = await handle(
    new Request('https://spell.test/v1beta/models/gemini-x:generateContent?translate=en', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-install-id': INSTALL },
      body,
    }),
    e,
    { fetch: fetchImpl, now: () => NOON_KST }
  );

  assert.equal(res.status, 200);
  assert.equal((await res.json()).candidates[0].content.parts[0].text, 'Did you eat?');
  const sent = seen[0];
  assert.match(sent.system_instruction.parts[0].text, /번역가/, '번역 지시문이 실려야 한다');
  assert.equal(sent.contents[0].parts[0].text, '밥 먹었어?');
  // 구글은 이 항목을 받는다. gpt-5 는 400 이라 못 줬고, 그래서 답이 매번 흔들렸다.
  assert.equal(sent.generationConfig.temperature, 0);
});

test('번역은 길이가 달라도 안 버린다 — 구글 경로도 마찬가지다', async () => {
  const korean = '오늘 날씨가 정말 좋아서 친구들이랑 한강에 나가 자전거를 탔어';
  const english = 'The weather was so nice today that I went biking along the Han River with my friends, and it was honestly the best part of my whole week.';
  const fetchImpl = async () =>
    new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: english }] } }] }), { status: 200 });

  const res = await handle(
    new Request('https://spell.test/v1beta/models/gemini-x:generateContent?translate=en', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-install-id': INSTALL },
      body: JSON.stringify({ contents: [{ parts: [{ text: korean }] }] }),
    }),
    env({ AI_PROVIDER: 'gemini' }),
    { fetch: fetchImpl, now: () => NOON_KST }
  );
  assert.equal(res.status, 200, '길이 검사는 교정일 때만 건다');
});

test('구글 답이 원문과 너무 다르면 버린다 — 교정일 때', async () => {
  const long = '가'.repeat(40);
  const fetchImpl = async () =>
    new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '요약함' }] } }] }), { status: 200 });
  const res = await handle(generate({}, JSON.stringify({ contents: [{ parts: [{ text: long }] }] })),
    env({ AI_PROVIDER: 'gemini' }), { fetch: fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 502);
});

test('구글 답이 잘렸으면 주지 않는다 — 덮어쓰면 글이 사라진다', async () => {
  const fetchImpl = async () =>
    new Response(JSON.stringify({ candidates: [{ finishReason: 'MAX_TOKENS', content: { parts: [{ text: '안녕하' }] } }] }), { status: 200 });
  const res = await handle(generate(), env({ AI_PROVIDER: 'gemini' }), { fetch: fetchImpl, now: () => NOON_KST });
  assert.equal(res.status, 502);
});
