import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import { toUpstageRequest, reasoningOff, UPSTAGE_URL, DEFAULT_UPSTAGE_MODEL } from '../src/upstage.js';
import { KO_SYSTEM_PROMPT } from '../src/openai.js';
import { fakeDb } from './fakeDb.js';

const INSTALL = '3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f';
const GENERATE = 'https://spell.test/v1beta/models/gemini-3.5-flash-lite:generateContent';

/** 앱이 실제로 보내는 모양. 구글 이름 그대로 온다 — 이미 깔린 APK 들이 그렇다. */
const APP_BODY = JSON.stringify({
  system_instruction: { parts: [{ text: '너는 교정기다' }] },
  contents: [{ role: 'user', parts: [{ text: '안녕하세요 반갑읍니다' }] }],
  generationConfig: { temperature: 0, candidateCount: 1, maxOutputTokens: 4096 },
});

function env(overrides = {}) {
  return {
    DB: fakeDb(),
    AI_PROVIDER: 'upstage',
    UPSTAGE_API_KEY: 'up-test\n',
    TEST_INSTALL_IDS: INSTALL,
    ...overrides,
  };
}

function generate(body = APP_BODY) {
  return new Request(GENERATE, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'cf-connecting-ip': '1.2.3.4', 'x-install-id': INSTALL },
    body,
  });
}

function solarReply(text, { finish = 'stop', input = 30, output = 12 } = {}) {
  return JSON.stringify({
    id: 'chatcmpl-1',
    object: 'chat.completion',
    model: 'solar-pro4',
    choices: [{ index: 0, message: { role: 'assistant', content: text }, finish_reason: finish }],
    usage: { prompt_tokens: input, completion_tokens: output, total_tokens: input + output },
  });
}

/** 업스테이지 흉내. 어디로 무엇을 보냈는지 붙잡아 둔다. */
function upstream(reply = solarReply('안녕하세요 반갑습니다'), status = 200) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return new Response(reply, { status, headers: { 'content-type': 'application/json' } });
  };
  return { fetchImpl, calls };
}

test('Solar 본문: 앱 지시문, temperature 0, 숙고 끔, 출력 한도, 사용자 글', () => {
  const sent = JSON.parse(toUpstageRequest(APP_BODY, 'solar-pro4'));
  assert.equal(sent.model, 'solar-pro4');
  assert.deepEqual(sent.messages, [
    { role: 'system', content: '너는 교정기다' },
    { role: 'user', content: '안녕하세요 반갑읍니다' },
  ]);
  assert.equal(sent.temperature, 0);
  assert.equal(sent.reasoning_effort, 'none');
  assert.equal(sent.max_tokens, 4096);
});

test('숙고 끄는 값: pro4 는 none, 그 밖은 minimal', () => {
  assert.equal(reasoningOff('solar-pro4'), 'none');
  assert.equal(reasoningOff('solar-pro4-20260810'), 'none');
  assert.equal(reasoningOff('solar-pro3'), 'minimal');
});

test('Solar 본문: 숙고 세기를 정해 주면 그것을 쓴다', () => {
  const sent = JSON.parse(toUpstageRequest(APP_BODY, 'solar-pro4', { reasoning: 'low' }));
  assert.equal(sent.reasoning_effort, 'low');
});

test('Solar 본문: prompt=server 면 서버 지시문, 번역이면 번역 지시문', () => {
  assert.equal(JSON.parse(toUpstageRequest(APP_BODY, 'm', { prompt: 'server' })).messages[0].content, KO_SYSTEM_PROMPT);
  assert.match(JSON.parse(toUpstageRequest(APP_BODY, 'm', { translateTo: 'en' })).messages[0].content, /번역가/);
});

test('Solar 본문: 앱이 지시문을 안 보내면 서버 지시문으로 채운다', () => {
  const bare = JSON.stringify({ contents: [{ role: 'user', parts: [{ text: '고칠 글' }] }] });
  assert.equal(JSON.parse(toUpstageRequest(bare, 'm')).messages[0].content, KO_SYSTEM_PROMPT);
});

test('Solar 본문: 고칠 글이 없으면 던진다', () => {
  const empty = JSON.stringify({ contents: [{ role: 'user', parts: [{ text: '  ' }] }] });
  assert.throws(() => toUpstageRequest(empty, 'm'));
});

test('AI_PROVIDER=upstage 면 업스테이지로 보내고 키를 붙이고, 구글 모양으로 돌려준다', async () => {
  const { fetchImpl, calls } = upstream();
  const res = await handle(generate(), env(), { fetch: fetchImpl });
  assert.equal(res.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, UPSTAGE_URL);
  // 붙여넣은 비밀값 끝의 줄바꿈은 잘라 보낸다.
  assert.equal(calls[0].init.headers.authorization, 'Bearer up-test');
  assert.equal(JSON.parse(calls[0].init.body).model, DEFAULT_UPSTAGE_MODEL);
  const body = await res.json();
  assert.equal(body.candidates[0].content.parts[0].text, '안녕하세요 반갑습니다');
  assert.deepEqual(body.usageMetadata, { promptTokenCount: 30, candidatesTokenCount: 12, thoughtsTokenCount: 0 });
});

test('"solar" 라고 적어도 업스테이지다', async () => {
  const { fetchImpl, calls } = upstream();
  await handle(generate(), env({ AI_PROVIDER: 'solar' }), { fetch: fetchImpl });
  assert.equal(calls[0].url, UPSTAGE_URL);
});

test('UPSTAGE_MODEL, UPSTAGE_PROMPT, UPSTAGE_REASONING 이 기본값을 이긴다', async () => {
  const { fetchImpl, calls } = upstream();
  await handle(
    generate(),
    env({ UPSTAGE_MODEL: 'solar-pro3', UPSTAGE_PROMPT: 'server', UPSTAGE_REASONING: 'low' }),
    { fetch: fetchImpl }
  );
  const sent = JSON.parse(calls[0].init.body);
  assert.equal(sent.model, 'solar-pro3');
  assert.equal(sent.messages[0].content, KO_SYSTEM_PROMPT);
  assert.equal(sent.reasoning_effort, 'low');
});

test('모델 목록은 업스테이지 모델 하나', async () => {
  const res = await handle(
    new Request('https://spell.test/v1beta/models', { headers: { 'x-install-id': INSTALL } }),
    env()
  );
  const body = await res.json();
  assert.deepEqual(body.models.map((m) => m.name), ['models/solar-pro4']);
});

test('잘린 답은 주지 않는다', async () => {
  const { fetchImpl } = upstream(solarReply('앞부분만', { finish: 'length' }));
  const res = await handle(generate(), env(), { fetch: fetchImpl });
  assert.equal(res.status, 502);
});

test('업스테이지로 정했는데 키가 없으면 503 — 다른 회사 키가 있어도', async () => {
  const { fetchImpl, calls } = upstream();
  const res = await handle(generate(), env({ UPSTAGE_API_KEY: '', GEMINI_API_KEY: 'g' }), { fetch: fetchImpl });
  assert.equal(res.status, 503);
  assert.equal(calls.length, 0);
});

test('업스테이지는 미국 중계를 거치지 않는다 — 서울에 있다', async () => {
  const { fetchImpl, calls } = upstream();
  let relayed = 0;
  const RELAY = {
    idFromName: () => 'id',
    get: () => ({ fetch: async () => { relayed++; return new Response('{}'); } }),
  };
  const res = await handle(generate(), env({ RELAY }), { fetch: fetchImpl });
  assert.equal(res.status, 200);
  assert.equal(relayed, 0);
  assert.equal(calls.length, 1);
});

test('health 와 방침 페이지가 업스테이지를 적는다', async () => {
  const health = await (await handle(new Request('https://spell.test/health'), env())).json();
  assert.equal(health.provider, 'upstage');
  const page = await (await handle(new Request('https://spell.test/privacy'), env())).text();
  assert.match(page, /업스테이지 Solar API/);
  assert.match(page, /주식회사 업스테이지 \(대한민국\)/);
  assert.ok(!page.includes('Google Gemini API'), '업스테이지로 보내는데 구글 AI 라고 적혀 있다');
  assert.ok(!/Google \(미국\)<\/strong> — AI 교정/.test(page), '구글 줄에 AI 처리가 남아 있다');
});
