/**
 * 구글 Gemini 경로.
 *
 * ## 왜 따로 있나
 *
 * 한동안 OpenAI 로만 갔는데 실기기 평가가 "제미나이 때보다 정확도도 속도도 못하다"
 * 였다. 이유가 셋 있고 셋 다 모델을 바꾸면 사라진다.
 *
 * 1. `temperature: 0` 을 못 준다. gpt-5 계열은 이 항목을 받으면 400 이라 빼고 보냈고,
 *    그래서 같은 글을 두 번 고치면 답이 달랐다. 구글은 받는다.
 * 2. 숙고 토큰이 답보다 **먼저** 나온다. 그 시간이 고스란히 대기 시간이었다.
 * 3. 애초에 사용자가 100% 라고 평가한 모델이 이쪽이다.
 *
 * ## 교정은 앱 본문 그대로 넘긴다
 *
 * 되돌리려는 것이 "제미나이 시절" 이고, 그 시절 교정은 **앱이 보낸 지시문**으로 돌았다.
 * 서버 지시문(KO_SYSTEM_PROMPT)은 작은 OpenAI 모델이 글자를 지어내는 것을 막으려고
 * 쓴 것이라 여기에 그대로 끼얹으면 되돌리는 게 아니라 또 다른 조합이 된다.
 * 그래서 교정은 앱 본문을 손대지 않는다 — temperature 0 도 그 안에 들어 있다.
 *
 * 번역만 우리 지시문을 쓴다. 앱은 번역용 지시문을 아예 안 보내기 때문이다
 * (`buildRequest(text, withPrompt = false)`).
 */
import { translatePrompt, userTextOf, tooDifferent } from './openai.js';

export const GEMINI_UPSTREAM = 'https://generativelanguage.googleapis.com';
export const DEFAULT_GEMINI_MODEL = 'gemini-3.5-flash-lite';

export function geminiUrl(model) {
  return `${GEMINI_UPSTREAM}/v1beta/models/${model}:generateContent`;
}

/**
 * 보낼 본문. 교정이면 앱 것 그대로, 번역이면 우리 지시문으로 새로 짠다.
 * 고칠 글이 없으면 던진다 — OpenAI 쪽과 같은 규약이다.
 */
export function toGeminiRequest(body, options = {}) {
  const user = userTextOf(body);
  if (!user) throw new Error('empty_request');
  if (!options.translateTo) return body;

  return JSON.stringify({
    system_instruction: { parts: [{ text: translatePrompt(options.translateTo) }] },
    contents: [{ role: 'user', parts: [{ text: user }] }],
    generationConfig: { temperature: 0, candidateCount: 1 },
  });
}

/**
 * 구글 응답을 그대로 돌려주되 **그냥 통과시키지는 않는다.**
 *
 * 예전 구글 경로는 받은 것을 손대지 않고 넘겼는데, 그러면 OpenAI 쪽에만 있던 안전장치
 * 둘이 여기엔 없게 된다. 앱은 받은 글로 입력란을 **통째로 덮으므로** 이 둘이 없으면
 * 사용자가 쓴 글이 사라지거나, 교정이 아닌 답(요약·지시문 따라가기)이 그대로 들어간다.
 */
export function fromGeminiReply(status, text, user = '', options = {}) {
  const parsed = safeParse(text);
  if (status !== 200) {
    return errorReply(status, parsed?.error?.message ?? `HTTP ${status}`);
  }

  const candidate = parsed?.candidates?.[0];
  const corrected = partsText(candidate?.content).trim();

  // 잘린 답은 주지 않는다. 뒷부분이 잘린 교정문으로 덮으면 그만큼이 사라진다.
  if (candidate?.finishReason === 'MAX_TOKENS') {
    return errorReply(502, '글이 너무 길어 교정문이 잘렸다');
  }
  if (!corrected) return errorReply(502, `응답이 비었다 (${candidate?.finishReason ?? 'unknown'})`);
  // 길이 검사는 교정일 때만. 번역문은 원문과 길이가 다른 게 당연하다.
  if (user && !options.translateTo && tooDifferent(user, corrected)) {
    return errorReply(502, '교정 결과가 원문과 너무 달라 버렸다');
  }

  // 구글 모양 그대로라 다시 짤 것이 없다. 사용량은 저쪽이 실어 준 것을 그대로 쓴다.
  return { status: 200, text };
}

function partsText(content) {
  return (content?.parts ?? []).map((part) => part?.text ?? '').join('');
}

function errorReply(status, message) {
  return {
    status,
    text: JSON.stringify({ error: { code: status, message, status: 'ERROR' } }),
  };
}

function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * 구글이 지금 내주는 모델 목록 주소.
 *
 * 앱이 예전에 직접 물어보던 그 자리다(`GeminiCorrector.listModels`).
 */
export const GEMINI_MODELS_URL = `${GEMINI_UPSTREAM}/v1beta/models?pageSize=200`;

/** 교정에 쓸 수 없는 것들. 이름만 보고 거른다. */
const NOT_FOR_TEXT = ['embedding', 'aqa', 'imagen', 'vision', 'tts', 'image', 'audio', 'video', 'live'];

/**
 * 목록 응답에서 교정에 쓸 만한 이름만 뽑는다.
 *
 * `supportedGenerationMethods` 에 `generateContent` 가 있어야 한다 — 목록에는 임베딩처럼
 * 아예 다른 일을 하는 것도 같이 온다.
 */
export function parseGeminiModels(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    return [];
  }
  const models = Array.isArray(parsed?.models) ? parsed.models : [];
  return models
    .filter((model) => (model?.supportedGenerationMethods ?? []).includes('generateContent'))
    .map((model) => String(model?.name ?? '').replace(/^models\//, ''))
    .filter((name) => {
      const lower = name.toLowerCase();
      return lower.startsWith('gemini') && !NOT_FOR_TEXT.some((bad) => lower.includes(bad));
    });
}

/**
 * 고를 만한 정도. 클수록 먼저다.
 *
 * **lite 가 먼저다.** 맞춤법·띄어쓰기는 패턴이지 판단이 아니라서 큰 모델이 필요 없다.
 * 실제로 3.5-flash-lite 로 "교정 완벽하다" 는 평가를 받았다. lite 는 그러면서
 * 훨씬 빠르고(기다리는 시간), 훨씬 싸고(구독료로 API 값을 대는 구조라 곧 마진),
 * 훨씬 덜 붐빈다(503 을 덜 만난다).
 *
 * 세대는 **같은 등급 안에서만** 따진다. 세대를 먼저 보게 하면 flash 가 lite 를 이겨서
 * 값이 몇 배로 뛴다 — 한 번 그렇게 해 봤다가 되돌렸다. 교정에는 어느 쪽이든 넘친다.
 *
 * pro 는 교정에 쓰기엔 느리고 비싸다. 미리보기는 예고 없이 사라지므로 뒤로 민다.
 */
export function rankGeminiModel(name) {
  const lower = name.toLowerCase();
  const version = Math.round((Number(/\d+(?:\.\d+)?/.exec(lower)?.[0]) || 0) * 10);
  const tier = lower.includes('lite') ? 3000 : lower.includes('flash') ? 2000 : lower.includes('pro') ? 0 : 1000;
  const preview = lower.includes('preview') || lower.includes('exp') ? 1500 : 0;
  return tier + version - preview;
}

/** 목록에서 교정에 쓸 것 하나. 없으면 null. */
export function pickGeminiModel(names) {
  let best = null;
  for (const name of names) {
    if (best === null || rankGeminiModel(name) > rankGeminiModel(best)) best = name;
  }
  return best;
}
