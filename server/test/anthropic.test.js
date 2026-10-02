import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import { toClaudeRequest, fromClaudeReply, ANTHROPIC_URL, DEFAULT_CLAUDE_MODEL } from '../src/anthropic.js';
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
    AI_PROVIDER: 'anthropic',
    ANTHROPIC_API_KEY: 'sk-ant-test\n',
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

function claudeReply(text, { stop = 'end_turn', input = 30, output = 12 } = {}) {
  return JSON.stringify({
    id: 'msg_1',
    type: 'message',
    role: 'assistant',
    content: [{ type: 'text', text }],
    stop_reason: stop,
    usage: { input_tokens: input, output_tokens: output },
  });
}

/** Claude 흉내. 어디로 무엇을 보냈는지 붙잡아 둔다. */
function upstream(reply = claudeReply('안녕하세요 반갑습니다'), status = 200) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return new Response(reply, { status, headers: { 'content-type': 'application/json' } });
  };
  return { fetchImpl, calls };
}

test('Claude 본문: 앱 지시문, temperature 0, 출력 한도, 사용자 글', () => {
  const sent = JSON.parse(toClaudeRequest(APP_BODY, 'claude-haiku-4-5-20251001'));
  assert.equal(sent.model, 'claude-haiku-4-5-20251001');
  assert.equal(sent.system, '너는 교정기다');
  assert.equal(sent.temperature, 0);
  assert.equal(sent.max_tokens, 4096);
  assert.deepEqual(sent.messages, [{ role: 'user', content: '안녕하세요 반갑읍니다' }]);
});

test('Claude 본문: prompt=server 면 서버 지시문을 쓴다', () => {
  const sent = JSON.parse(toClaudeRequest(APP_BODY, 'm', { prompt: 'server' }));
  assert.equal(sent.system, KO_SYSTEM_PROMPT);
});

test('Claude 본문: 번역이면 번역 지시문', () => {
  const sent = JSON.parse(toClaudeRequest(APP_BODY, 'm', { translateTo: 'en' }));
  assert.match(sent.system, /번역가/);
});

test('Claude 본문: 고칠 글이 없으면 던진다', () => {
  const empty = JSON.stringify({ contents: [{ role: 'user', parts: [{ text: '  ' }] }] });
  assert.throws(() => toClaudeRequest(empty, 'm'));
});

test('Claude 응답을 구글 모양으로 옮기고 토큰을 센다', () => {
  const out = fromClaudeReply(200, claudeReply('안녕하세요 반갑습니다', { input: 30, output: 12 }), '안녕하세요 반갑읍니다');
  assert.equal(out.status, 200);
  const body = JSON.parse(out.text);
  assert.equal(body.candidates[0].content.parts[0].text, '안녕하세요 반갑습니다');
  assert.deepEqual(body.usageMetadata, { promptTokenCount: 30, candidatesTokenCount: 12, thoughtsTokenCount: 0 });
});

test('Claude 응답: 잘린 답은 주지 않는다', () => {
  const out = fromClaudeReply(200, claudeReply('앞부분만', { stop: 'max_tokens' }), '앞부분만 있는 글');
  assert.equal(out.status, 502);
});

test('Claude 응답: 교정이 아닌 답(길이가 확 다름)은 원문으로 돌려준다', () => {
  const user = '오늘 회의는 세 시로 미뤄졌다고 방금 연락이 왔어요 그러니까 천천히 와도 돼';
  const out = fromClaudeReply(200, claudeReply('알겠습니다.'), user);
  assert.equal(out.status, 200);
  assert.equal(out.kept, true);
  assert.equal(JSON.parse(out.text).candidates[0].content.parts[0].text, user);
});

test('Claude 응답: 저쪽 오류는 상태와 메시지를 그대로', () => {
  const err = JSON.stringify({ type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } });
  const out = fromClaudeReply(529, err, '글');
  assert.equal(out.status, 529);
  assert.match(out.text, /Overloaded/);
});

test('AI_PROVIDER=anthropic 이면 Claude 로 보내고 키·버전 헤더를 붙인다', async () => {
  const { fetchImpl, calls } = upstream();
  const res = await handle(generate(), env(), { fetch: fetchImpl });
  assert.equal(res.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, ANTHROPIC_URL);
  // 붙여넣은 비밀값 끝의 줄바꿈은 잘라 보낸다.
  assert.equal(calls[0].init.headers['x-api-key'], 'sk-ant-test');
  assert.equal(calls[0].init.headers['anthropic-version'], '2023-06-01');
  assert.equal(JSON.parse(calls[0].init.body).model, DEFAULT_CLAUDE_MODEL);
  const body = await res.json();
  assert.equal(body.candidates[0].content.parts[0].text, '안녕하세요 반갑습니다');
});

test('CLAUDE_MODEL 이 기본 모델을 이긴다', async () => {
  const { fetchImpl, calls } = upstream();
  await handle(generate(), env({ CLAUDE_MODEL: 'claude-sonnet-5' }), { fetch: fetchImpl });
  assert.equal(JSON.parse(calls[0].init.body).model, 'claude-sonnet-5');
});

test('Claude 로 정했는데 키가 없으면 503 — 다른 회사 키가 있어도', async () => {
  const { fetchImpl, calls } = upstream();
  const res = await handle(generate(), env({ ANTHROPIC_API_KEY: '', GEMINI_API_KEY: 'g' }), { fetch: fetchImpl });
  assert.equal(res.status, 503);
  assert.equal(calls.length, 0);
});

test('health 와 방침 페이지가 Claude 를 적는다', async () => {
  const health = await (await handle(new Request('https://spell.test/health'), env())).json();
  assert.equal(health.provider, 'anthropic');
  const page = await (await handle(new Request('https://spell.test/privacy'), env())).text();
  assert.match(page, /Anthropic Claude API/);
  assert.match(page, /Anthropic \(미국\)/);
  assert.ok(!page.includes('Google Gemini API'), 'Claude 로 보내는데 구글 AI 라고 적혀 있다');
});
