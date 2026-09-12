import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import { toOpenAiRequest, toGeminiReply, modelList } from '../src/openai.js';
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
    OPENAI_API_KEY: 'sk-test\n',
    FREE_DAILY_LIMIT: '5',
    IP_DAILY_LIMIT: '300',
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

/** OpenAI 흉내. */
function openAi({ status = 200, content = '안녕하세요 반갑습니다', finish = 'stop' } = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (status !== 200) {
      return new Response(JSON.stringify({ error: { message: 'rate limited' } }), { status });
    }
    return new Response(JSON.stringify({
      choices: [{ message: { content }, finish_reason: finish }],
      usage: { prompt_tokens: 200, completion_tokens: 90, completion_tokens_details: { reasoning_tokens: 10 } },
    }), { status: 200 });
  };
  return { fetchImpl, calls };
}

// --- 번역 ---------------------------------------------------------------------

test('구글 모양을 OpenAI 모양으로 옮긴다', () => {
  const sent = JSON.parse(toOpenAiRequest(APP_BODY, 'gpt-5-nano'));
  assert.equal(sent.model, 'gpt-5-nano');
  assert.deepEqual(sent.messages, [
    { role: 'system', content: '너는 교정기다' },
    { role: 'user', content: '안녕하세요 반갑읍니다' },
  ]);
  assert.equal(sent.reasoning_effort, 'minimal');
  // gpt-5 계열은 temperature 를 받으면 400 을 돌려준다. 보내지 않는다.
  assert.equal('temperature' in sent, false);
  assert.equal('max_tokens' in sent, false);
});

test('숙고 토큰도 출력 한도에서 깎이므로 앱이 부른 값보다 넉넉히 준다', () => {
  const sent = JSON.parse(toOpenAiRequest(APP_BODY, 'gpt-5-nano'));
  assert.equal(sent.max_completion_tokens, 8192);

  const small = JSON.stringify({ contents: [{ parts: [{ text: '가' }] }], generationConfig: { maxOutputTokens: 16 } });
  assert.equal(JSON.parse(toOpenAiRequest(small, 'gpt-5-nano')).max_completion_tokens, 2048);
});

test('고칠 글이 없으면 보내지 않는다', () => {
  assert.throws(() => toOpenAiRequest('{"contents":[]}', 'gpt-5-nano'));
});

test('OpenAI 응답을 구글 모양으로 되돌린다', () => {
  const reply = toGeminiReply(200, JSON.stringify({
    choices: [{ message: { content: ' 고침 ' }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 200, completion_tokens: 90, completion_tokens_details: { reasoning_tokens: 10 } },
  }));
  assert.equal(reply.status, 200);
  const body = JSON.parse(reply.text);
  assert.equal(body.candidates[0].content.parts[0].text, '고침');
  // completion_tokens 에는 숙고가 들어 있다. 빼서 넣어야 합계가 두 번 세지 않는다.
  assert.deepEqual(body.usageMetadata, {
    promptTokenCount: 200,
    candidatesTokenCount: 80,
    thoughtsTokenCount: 10,
  });
});

test('잘린 교정문은 주지 않는다 — 사용자 글이 잘린 채로 덮인다', () => {
  const reply = toGeminiReply(200, JSON.stringify({
    choices: [{ message: { content: '앞부분만' }, finish_reason: 'length' }],
  }));
  assert.equal(reply.status, 502);
  assert.match(JSON.parse(reply.text).error.message, /잘렸다/);
});

test('빈 응답과 오류는 구글 오류 모양으로 옮긴다', () => {
  assert.equal(toGeminiReply(200, JSON.stringify({ choices: [{ message: { content: '' } }] })).status, 502);

  const failed = toGeminiReply(429, JSON.stringify({ error: { message: 'rate limited' } }));
  assert.equal(failed.status, 429);
  assert.equal(JSON.parse(failed.text).error.message, 'rate limited');

  const broken = toGeminiReply(500, '<html>bad gateway</html>');
  assert.equal(broken.status, 500);
  assert.equal(JSON.parse(broken.text).error.message, 'HTTP 500');
});

test('모델 목록은 지금 쓰는 이름 하나만 알려 준다', () => {
  assert.deepEqual(modelList('gpt-5-nano'), {
    models: [{ name: 'models/gpt-5-nano', supportedGenerationMethods: ['generateContent'] }],
  });
});

// --- 서버 전체 ----------------------------------------------------------------

test('OpenAI 키가 있으면 그쪽으로 보내고, 앱이 부른 모델 이름은 무시한다', async () => {
  const up = openAi();
  const res = await handle(generate(), env(), { fetch: up.fetchImpl });

  assert.equal(res.status, 200);
  assert.equal((await res.json()).candidates[0].content.parts[0].text, '안녕하세요 반갑습니다');
  assert.equal(up.calls.length, 1);
  assert.equal(up.calls[0].url, 'https://api.openai.com/v1/chat/completions');
  // 붙여넣다 딸려 온 줄바꿈은 헤더에 들어가면 fetch 가 예외를 던진다.
  assert.equal(up.calls[0].init.headers.authorization, 'Bearer sk-test');
  assert.equal(JSON.parse(up.calls[0].init.body).model, 'gpt-5-nano');
});

test('OPENAI_MODEL 로 모델을 갈아 끼운다', async () => {
  const up = openAi();
  await handle(generate(), env({ OPENAI_MODEL: 'gpt-5-mini' }), { fetch: up.fetchImpl });
  assert.equal(JSON.parse(up.calls[0].init.body).model, 'gpt-5-mini');
});

test('모델 목록은 물어보지 않고 바로 답한다', async () => {
  const up = openAi();
  const res = await handle(new Request('https://spell.test/v1beta/models?pageSize=200'), env(), { fetch: up.fetchImpl });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).models[0].name, 'models/gpt-5-nano');
  assert.equal(up.calls.length, 0);
});

test('토큰과 무료 한도는 그대로 센다', async () => {
  const shared = env();
  const up = openAi();
  const res = await handle(generate(), shared, { fetch: up.fetchImpl });
  assert.equal(res.headers.get('x-quota-remaining'), '4');

  const days = await shared.DB.prepare('SELECT day, requests, prompt, output, thoughts FROM tokens ORDER BY day DESC LIMIT 31').all();
  assert.equal(days.results[0].prompt, 200);
  assert.equal(days.results[0].output, 80);
  assert.equal(days.results[0].thoughts, 10);
});

test('OpenAI 가 거절하면 횟수를 깎지 않는다', async () => {
  const shared = env();
  const up = openAi({ status: 429 });
  const res = await handle(generate(), shared, { fetch: up.fetchImpl });
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('x-quota-remaining'), '5');
});

test('OpenAI 키가 없으면 예전처럼 구글로 간다', async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: '고침' }] } }] }), { status: 200 });
  };
  const res = await handle(generate(), env({ OPENAI_API_KEY: '', GEMINI_API_KEY: 'g' }), { fetch: fetchImpl });
  assert.equal(res.status, 200);
  assert.match(calls[0], /generativelanguage\.googleapis\.com/);
});

test('숙고 항목을 거절하면 빼고 한 번 더 보낸다', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push(JSON.parse(init.body));
    if (calls.length === 1) {
      return new Response(JSON.stringify({
        error: { message: "Unsupported parameter: 'reasoning_effort'" },
      }), { status: 400 });
    }
    return new Response(JSON.stringify({
      choices: [{ message: { content: '고침' }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 10, completion_tokens: 5 },
    }), { status: 200 });
  };

  const res = await handle(generate(), env(), { fetch: fetchImpl });
  assert.equal(res.status, 200);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].reasoning_effort, 'minimal');
  assert.equal('reasoning_effort' in calls[1], false);
});

test('숙고와 무관한 400 은 두 번 보내도 그대로 전한다', async () => {
  let sent = 0;
  const fetchImpl = async () => {
    sent += 1;
    return new Response(JSON.stringify({ error: { message: 'invalid_api_key' } }), { status: 401 });
  };
  const res = await handle(generate(), env(), { fetch: fetchImpl });
  assert.equal(res.status, 401);
  assert.equal(sent, 1);
  assert.equal((await res.json()).error.message, 'invalid_api_key');
});
