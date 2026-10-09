import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import {
  toOpenAiRequest,
  toGeminiReply,
  modelList,
  KO_SYSTEM_PROMPT,
  translatePrompt,
  TRANSLATE_TARGETS,
} from '../src/openai.js';
import { fakeDb } from './fakeDb.js';
// 한도는 코드에서 가져온다. 손으로 적어 두면 값을 바꿀 때마다 시험을 같이 고쳐야 한다.
import { DEFAULT_SUB_DAILY_CHARS } from '../src/quota.js';

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
    // AI 는 구독자 전용이다. 이 파일의 시험은 전부 "그래서 무엇을 어떻게 보내나" 를
    // 보는 것이라 구독자여야 본론까지 간다.
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
  assert.equal(sent.messages.length, 2);
  assert.equal(sent.messages[1].content, '안녕하세요 반갑읍니다');
  assert.equal(sent.reasoning_effort, 'low');
  // gpt-5 계열은 temperature 를 받으면 400 을 돌려준다. 보내지 않는다.
  assert.equal('temperature' in sent, false);
  assert.equal('max_tokens' in sent, false);
});

test('지시문은 앱 것이 아니라 서버 것을 쓴다', () => {
  const sent = JSON.parse(toOpenAiRequest(APP_BODY, 'gpt-5-nano'));
  assert.equal(sent.messages[0].role, 'system');
  assert.equal(sent.messages[0].content, KO_SYSTEM_PROMPT);
  assert.notEqual(sent.messages[0].content, '너는 교정기다');

  // 캐싱은 1024 토큰부터 걸린다. 그 아래로 줄면 입력값이 10배가 된다.
  assert.ok(KO_SYSTEM_PROMPT.length > 900, '지시문이 캐싱 문턱 아래로 짧아졌다');

  const fromApp = JSON.parse(toOpenAiRequest(APP_BODY, 'gpt-5-nano', { prompt: 'app' }));
  assert.equal(fromApp.messages[0].content, '너는 교정기다');
});

test('숙고 세기를 환경변수로 바꾼다', () => {
  const sent = JSON.parse(toOpenAiRequest(APP_BODY, 'gpt-5-nano', { reasoning: 'medium' }));
  assert.equal(sent.reasoning_effort, 'medium');
});

test('출력 한도는 서버가 글 길이로 정하고, 숙고가 켜져 있으면 숙고 몫을 얹는다', () => {
  // OpenAI 는 숙고 토큰도 이 한도에서 깎는다. 기본 숙고(low)라 2048 을 얹는다: 31바이트 → 256 + 2048 + 2×31.
  const sent = JSON.parse(toOpenAiRequest(APP_BODY, 'gpt-5-nano'));
  assert.equal(sent.max_completion_tokens, 2366);

  // 앱이 부른 값(16 이든 99999 든)은 안 쓴다.
  const small = (max) => JSON.stringify({ contents: [{ parts: [{ text: '가' }] }], generationConfig: { maxOutputTokens: max } });
  assert.equal(JSON.parse(toOpenAiRequest(small(16), 'gpt-5-nano')).max_completion_tokens, 2310);
  assert.equal(JSON.parse(toOpenAiRequest(small(99999), 'gpt-5-nano')).max_completion_tokens, 2310);
  assert.equal(JSON.parse(toOpenAiRequest(small(99999), 'gpt-5-nano', { reasoning: 'none' })).max_completion_tokens, 262);
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

test('빈 응답과 오류는 구글 오류 모양으로 옮기되 저쪽 문구는 가린다', () => {
  assert.equal(toGeminiReply(200, JSON.stringify({ choices: [{ message: { content: '' } }] })).status, 502);

  // 상태 코드는 앱이 쓰므로 그대로. 저쪽 문구(키 조각이 섞일 수 있다)는 넘기지 않고 짧은 표시로 바꾼다.
  const failed = toGeminiReply(429, JSON.stringify({ error: { message: 'sk-secret-leak' } }));
  assert.equal(failed.status, 429);
  assert.doesNotMatch(failed.text, /sk-secret-leak/);
  assert.equal(JSON.parse(failed.text).error.message, 'rate_limited');

  const broken = toGeminiReply(500, '<html>bad gateway</html>');
  assert.equal(broken.status, 500);
  assert.equal(JSON.parse(broken.text).error.message, 'upstream_unavailable');
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

test('토큰을 쌓고, 한도는 글자 수와 실제 토큰 중 큰 쪽으로 정산한다', async () => {
  const shared = env();
  const up = openAi();
  const res = await handle(generate(), shared, { fetch: up.fetchImpl });
  // '안녕하세요 반갑읍니다' 는 11 자라 글자로는 최소 50 자다. 그런데 이 흉내는 입력 200·출력 80·숙고 10 을
  // 돌려준다 — ⌈(200 + 4×(80+10)) / 10⌉ = 56 이 더 크므로 56 을 깎는다(윈도우 점검: 실제 값으로 정산).
  assert.equal(res.headers.get('x-quota-remaining'), String(DEFAULT_SUB_DAILY_CHARS - 56));

  const days = await shared.DB.prepare('SELECT day, requests, prompt, output, thoughts FROM tokens ORDER BY day DESC LIMIT 31').all();
  assert.equal(days.results[0].prompt, 200);
  assert.equal(days.results[0].output, 80);
  assert.equal(days.results[0].thoughts, 10);
});

test('OpenAI 가 거절하면 한도를 깎지 않는다', async () => {
  const shared = env();
  const up = openAi({ status: 429 });
  const res = await handle(generate(), shared, { fetch: up.fetchImpl });
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('x-quota-remaining'), String(DEFAULT_SUB_DAILY_CHARS));
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
  assert.equal(calls[0].reasoning_effort, 'low');
  assert.equal('reasoning_effort' in calls[1], false);
});

test('숙고와 무관한 401 은 한 번만 보내고 상태를 전한다 (키 문구는 가린다)', async () => {
  let sent = 0;
  const fetchImpl = async () => {
    sent += 1;
    // 저쪽이 키 조각을 오류에 실어 보내도 앱까지 가면 안 된다.
    return new Response(JSON.stringify({ error: { message: 'Incorrect API key: sk-live-ABCD1234' } }), { status: 401 });
  };
  const res = await handle(generate(), env(), { fetch: fetchImpl });
  assert.equal(res.status, 401);
  assert.equal(sent, 1);
  const msg = (await res.json()).error.message;
  assert.doesNotMatch(msg, /sk-live-ABCD1234/);
  assert.equal(msg, 'upstream_rejected');
});

test('교정이 아닌 답은 길이로 걸러낸다', () => {
  const user = '내가 그 사람한테 몇 번이나 말했잖아 너 혼자서만 잘 산다고 해서 전혀 행복해지는 게 아니라고';

  const ok = toGeminiReply(200, JSON.stringify({
    choices: [{ message: { content: user }, finish_reason: 'stop' }],
  }), user);
  assert.equal(ok.status, 200);

  // 본문에 섞인 지시문을 따라가 버린 경우. 우리 글 대신 딴 글이 온다 — 원문을 돌려준다.
  const hijacked = toGeminiReply(200, JSON.stringify({
    choices: [{ message: { content: '네, 알겠습니다!' }, finish_reason: 'stop' }],
  }), user);
  assert.equal(hijacked.status, 200);
  assert.equal(hijacked.kept, true);
  assert.equal(JSON.parse(hijacked.text).candidates[0].content.parts[0].text, user);

  // 요약이 아니라 늘려 쓴 경우도 교정이 아니다.
  const padded = toGeminiReply(200, JSON.stringify({
    choices: [{ message: { content: user + user }, finish_reason: 'stop' }],
  }), user);
  assert.equal(padded.kept, true);
});

test('짧은 글은 길이로 재지 않는다', () => {
  const reply = toGeminiReply(200, JSON.stringify({
    choices: [{ message: { content: '네' }, finish_reason: 'stop' }],
  }), '네네네');
  assert.equal(reply.status, 200);
});

test('지시문은 백틱과 치환식을 담지 않는다', () => {
  // 프롬프트가 백틱 문자열이라, 안쪽에 백틱이나 ${} 가 들어가면 문자열이 끊기거나
  // 엉뚱한 값이 끼어든다. 실제로 한 번 깨뜨렸다.
  assert.equal(KO_SYSTEM_PROMPT.includes('`'), false);
  assert.equal(KO_SYSTEM_PROMPT.includes('${'), false);
  assert.match(KO_SYSTEM_PROMPT, /오죽하겠냐마는/);
});

test('걸린 시간을 헤더로 알려 준다', async () => {
  const up = openAi();
  const res = await handle(generate(), env(), { fetch: up.fetchImpl });
  const took = res.headers.get('x-upstream-ms');
  assert.notEqual(took, null);
  assert.ok(Number(took) >= 0, `숫자가 아니다: ${took}`);
});

// --- 번역 -------------------------------------------------------------------

test('번역이면 번역 지시문을 쓴다', () => {
  const body = JSON.stringify({ contents: [{ parts: [{ text: '밥 먹었어' }] }] });
  const sent = JSON.parse(toOpenAiRequest(body, 'gpt-5-mini', { translateTo: 'en' }));
  assert.match(sent.messages[0].content, /번역가/);
  assert.match(sent.messages[0].content, /영어/);
  assert.equal(sent.messages[1].content, '밥 먹었어');
});

test('언어마다 지시문이 다르다', () => {
  assert.match(translatePrompt('ja'), /일본어/);
  assert.match(translatePrompt('zh'), /중국어/);
  // 지시문에 템플릿 문법이 새면 통째로 깨진다. 전에 한 번 그랬다.
  for (const code of Object.keys(TRANSLATE_TARGETS)) {
    const prompt = translatePrompt(code);
    assert.ok(!prompt.includes('`'), code + ' 지시문에 백틱이 있다');
    assert.ok(!prompt.includes('${'), code + ' 지시문에 ${ 가 있다');
    assert.ok(prompt.length > 400, code + ' 지시문이 너무 짧다');
  }
});

test('번역은 길이가 달라도 안 버린다', () => {
  // 교정이면 길이가 확 달라진 답을 버린다. 번역은 원래 길이가 다르다.
  // 공백을 뺀 20자가 넘어야 길이 검사가 돈다. 짧은 글은 원래 재지 않는다.
  const korean = '오늘 날씨가 정말 좋아서 친구랑 공원에 산책을 다녀왔어요';
  const english = 'The weather was so nice today that I went out for a walk in the park with my friend.';
  const raw = JSON.stringify({
    choices: [{ message: { content: english }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 10, completion_tokens: 20 },
  });
  const asCorrection = toGeminiReply(200, raw, korean);
  assert.equal(asCorrection.kept, true, '교정이면 원문으로 바꿔 준다');

  const asTranslation = toGeminiReply(200, raw, korean, { translateTo: 'en' });
  assert.equal(asTranslation.status, 200);
  assert.match(asTranslation.text, /went out for a walk/);
});

// ── 번역문 정리·검사 (2026-10-09) ──────────────────────────────────────────────────────────────────

import { cleanTranslation, translationProblem, translatePromptV2 } from '../src/openai.js';

test('cleanTranslation: 머리말·코드 울타리·통째로 감싼 따옴표를 뗀다', () => {
  assert.equal(cleanTranslation('Translation: Hello there.'), 'Hello there.');
  assert.equal(cleanTranslation('번역: 안녕'), '안녕');
  assert.equal(cleanTranslation('```\nHello\n```'), 'Hello');
  assert.equal(cleanTranslation('"Did you eat?"'), 'Did you eat?');
  assert.equal(cleanTranslation('「ご飯食べた？」'), 'ご飯食べた？');
  // 원문에 있던 인용은 건드리지 않는다
  assert.equal(cleanTranslation('He said "hi" to me.'), 'He said "hi" to me.');
  assert.equal(cleanTranslation('"Yes," she said, "I know."'), '"Yes," she said, "I know."');
  assert.equal(cleanTranslation('  Hello  '), 'Hello');
});

test('translationProblem: 엉뚱한 언어를 가려낸다', () => {
  // 맞는 것
  assert.equal(translationProblem('I\'m on my way home.', 'en'), null);
  assert.equal(translationProblem('今、家に帰ってるところ。', 'ja'), null);
  assert.equal(translationProblem('我现在正在回家的路上。', 'zh'), null);
  assert.equal(translationProblem('はい', 'ja'), null);
  assert.equal(translationProblem('😂', 'en'), null);
  assert.equal(translationProblem('010-1234-5678', 'zh'), null);
  // 고유명사 몇 글자가 한글로 남은 것은 괜찮다
  assert.equal(translationProblem('Please call 김민수 at 3 p.m. tomorrow, thank you very much.', 'en'), null);
  // 틀린 것
  assert.equal(translationProblem('안녕하세요 저는 건우입니다', 'en'), 'untranslated');
  assert.equal(translationProblem('我现在正在回家的路上。', 'ja'), 'wrong_language');
  assert.equal(translationProblem('今、家に帰ってるところ。', 'zh'), 'wrong_language');
  assert.equal(translationProblem('今日は天気がいいね', 'en'), 'wrong_language');
});

test('translatePromptV2: 언어마다 규칙과 보기가 들어 있다', () => {
  for (const [code, mark] of [['en', 'won'], ['ja', 'ウォン'], ['zh', '韩元']]) {
    const prompt = translatePromptV2(code);
    assert.match(prompt, new RegExp(mark));
    assert.match(prompt, /보기/);
    assert.ok(prompt.length > 1500, `${code}: ${prompt.length}자`);
  }
  assert.match(translatePromptV2('ja'), /タメ口/);
  assert.match(translatePromptV2('zh'), /您/);
});

test('translatePromptFor: 기본은 2판이고 환경변수로 1판으로 되돌릴 수 있다', async () => {
  const { translatePromptFor, translatePrompt, translatePromptV2 } = await import('../src/openai.js');
  assert.equal(translatePromptFor('ja', ''), translatePromptV2('ja'));
  assert.equal(translatePromptFor('ja', undefined), translatePromptV2('ja'));
  assert.equal(translatePromptFor('ja', 'v2'), translatePromptV2('ja'));
  assert.equal(translatePromptFor('ja', 'V1'), translatePrompt('ja'));
});

test('translatePromptV2: 일본어·중국어 요청에 영어 보기만 있으면 안 된다', () => {
  // 1판이 영어 보기만 들고 있어서 solar-pro4 가 일본어·중국어 요청에 영어로 답했다(2026-10-09 시험지 114/121·97/121).
  for (const code of ['ja', 'zh']) {
    const prompt = translatePromptV2(code);
    const shots = prompt.split('보기')[1];
    const ascii = (shots.match(/[A-Za-z]/g) ?? []).length;
    assert.ok(ascii < shots.length * 0.1, `${code}: 보기가 영어로 쏠려 있다`);
  }
  assert.ok(translatePromptV2('ja', { shots: 0 }).indexOf('보기') === -1);
  assert.ok(translatePromptV2('ja', { shots: 3 }).split('\n- ').length < translatePromptV2('ja').split('\n- ').length);
});

test('translatePromptV3: 한글 금지와 풀이·보기 스무 개가 들어 있다', async () => {
  const { translatePromptV3, translatePromptFor } = await import('../src/openai.js');
  for (const code of ['en', 'ja', 'zh']) {
    const prompt = translatePromptV3(code);
    assert.match(prompt, /한글을 한 글자도 남기지 마라/);
    assert.match(prompt, /글자대로 읽으면 틀리는 한국어/);
    assert.equal(prompt.split('보기')[1].split('\n- ').length - 1, 20, `${code}: 보기는 스무 개`);
    assert.equal(translatePromptFor(code, 'v3'), prompt);
  }
  assert.match(translatePromptV3('ja'), /カカオトーク/);
  // 2판은 그대로다 — 겨루기에서 견주는 기준이라 몰래 바뀌면 안 된다.
  assert.ok(!translatePromptV2('ja').includes('글자대로 읽으면 틀리는'));
  assert.ok(translatePromptV3('ja', { shots: 5 }).split('보기')[1].split('\n- ').length - 1 === 5);
});

test('translatePromptV3: 보기와 풀이가 시험지 B(부풀지 않은 값을 재는 곳)의 문장을 담지 않는다', async () => {
  const { readFileSync } = await import('node:fs');
  const { translatePromptV3 } = await import('../src/openai.js');
  const sheetB = readFileSync(new URL('../bench/translate-cases-b.tsv', import.meta.url), 'utf8')
    .split('\n').slice(1).filter(Boolean).map((line) => line.split('\t')[2]);
  assert.equal(sheetB.length, 60);
  for (const code of ['en', 'ja', 'zh']) {
    const prompt = translatePromptV3(code);
    for (const ko of sheetB) assert.ok(!prompt.includes(ko), `시험지 B 문장이 지시문에 있다: ${ko}`);
  }
});
