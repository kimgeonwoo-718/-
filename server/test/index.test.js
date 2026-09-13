import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle, GoogleRelay } from '../src/index.js';
import { fakeDb } from './fakeDb.js';
import { testKeyPair } from './play.test.js';

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
    ...overrides,
  };
}

/** 구글 흉내. 어디로 무엇을 보냈는지 붙잡아 둔다. */
function upstream({ status = 200, activeToken = null } = {}) {
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
      return new Response(JSON.stringify({
        candidates: [{ content: { parts: [{ text: '고침' }] } }],
        usageMetadata: { promptTokenCount: 120, candidatesTokenCount: 30, thoughtsTokenCount: 7 },
      }), { status: 200 });
    }
    throw new Error(`unexpected upstream ${url}`);
  };
  return { fetchImpl, calls };
}

function generate(headers = {}, body = '{"contents":[]}') {
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
  assert.equal(call.url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent');
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
  assert.equal(res.headers.get('x-quota-remaining'), '100000', '깎이지 않았다');
  assert.equal(e.DB.usage.size, 0);
});

test('자정을 넘기면 되살아난다', async () => {
  const e = env({ SUB_DAILY_CHARS: '120' });
  const up = upstream();
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

test('구독자는 횟수 대신 글자 수로 센다 — 확인은 Play 에 한 번만 물어본다', async () => {
  const { pem } = await testKeyPair();
  const e = env({
    // 진짜 결제 확인 길을 보는 시험이라 시험용 목록을 비운다.
    TEST_INSTALL_IDS: '',
    PLAY_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'svc@x', private_key: pem }),
    SUB_DAILY_CHARS: '1000',
  });
  const up = upstream({ activeToken: 'paid-token' });
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
  const deps = { fetch: upstream({ activeToken: 'paid-token' }).fetchImpl, now: () => NOON_KST };
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

test('모델 목록은 한도 없이 넘긴다', async () => {
  const up = upstream();
  const req = new Request('https://spell.test/v1beta/models?pageSize=200');
  const res = await handle(req, env(), { fetch: up.fetchImpl });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).models[0].name, 'models/gemini-x');
  assert.equal(up.calls[0].init.headers['x-goog-api-key'], 'server-key');
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

test('중계 객체가 있으면 구글 호출은 그 안에서 나간다 — 미국 서부 위치 힌트로', async () => {
  const relay = fakeRelay(() => new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '고침' }] } }] }), { status: 200 }));
  const direct = upstream();
  const res = await handle(generate(), env({ RELAY: relay }), { fetch: direct.fetchImpl, now: () => NOON_KST });

  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-quota-remaining'), String(100000 - 50));
  assert.equal(relay.calls.length, 1);
  assert.equal(relay.calls[0].url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent');
  assert.equal(relay.calls[0].init.method, 'POST');
  assert.equal(relay.calls[0].init.body, '{"contents":[]}');
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
      ? new Response('{"candidates":[]}', { status: 200 })
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
