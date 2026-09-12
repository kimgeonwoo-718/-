/**
 * 구글 모양 요청을 OpenAI 로 옮긴다.
 *
 * 앱은 구글 모양(`system_instruction` + `contents`)으로 보내고, 구글 모양 응답을
 * 읽는다. 이미 깔린 APK 를 그대로 두고 뒤에 있는 모델만 갈아 끼우려면 번역이 여기
 * 있어야 한다 — 앱은 우리 주소만 알면 되고, 그 뒤에 누가 있는지는 서버 사정이다.
 *
 * 왜 옮기는가: gemini-3.5-flash-lite 는 출력 100만 토큰에 $2.50 인데 gpt-5-nano 는
 * $0.40 이다. 맞춤법 교정은 원문을 그대로 다시 뱉는 일이라 출력이 비용의 8할이고,
 * 그래서 이 한 줄이 요금을 6분의 1로 만든다. 교정은 판단이 아니라 패턴이라 nano 급
 * 으로 충분하다.
 */

export const OPENAI_URL = 'https://api.openai.com/v1/chat/completions';
export const DEFAULT_OPENAI_MODEL = 'gpt-5-nano';

/**
 * 출력 한도. **OpenAI 는 숙고 토큰도 이 한도에서 깎는다.** 앱이 부르는 4096 을 그대로
 * 주면 긴 글에서 답이 중간에 끊긴다. 끊긴 답은 사용자 글을 잘린 채로 덮어 버리므로
 * (앱은 받은 것으로 통째로 갈아 끼운다) 넉넉히 준다. 실제 요금은 쓴 만큼만 나간다.
 */
const MIN_OUTPUT_TOKENS = 2048;
const MAX_OUTPUT_TOKENS = 16384;

/**
 * 숙고 항목을 빼고 다시 만든다.
 *
 * 모델이 `reasoning_effort` 를 안 받으면 400 이 온다. 한 번 빼고 다시 보내 본다 —
 * 앱이 구글의 thinkingBudget 에 대해 하던 것과 같은 수법이다. 왕복 한 번이 전부고,
 * 그 대신 모델이 바뀌어도 교정이 멈추지 않는다.
 */
export function withoutReasoning(request) {
  const parsed = JSON.parse(request);
  delete parsed.reasoning_effort;
  return JSON.stringify(parsed);
}

/** 숙고 항목 때문에 거절당했는가. */
export function rejectsReasoning(status, text) {
  if (status !== 400) return false;
  return /reasoning|unsupported|invalid/i.test(text);
}

/** 앱이 보낸 구글 모양 본문을 OpenAI 본문으로. 고칠 글이 없으면 던진다. */
export function toOpenAiRequest(body, model) {
  const parsed = JSON.parse(body);
  const system = partsText(parsed.system_instruction ?? parsed.systemInstruction);
  const user = (parsed.contents ?? []).map(partsText).join('\n').trim();
  if (!user) throw new Error('empty_request');

  const asked = Number(parsed.generationConfig?.maxOutputTokens ?? 0);
  const messages = [];
  if (system) messages.push({ role: 'system', content: system });
  messages.push({ role: 'user', content: user });

  return JSON.stringify({
    model,
    messages,
    max_completion_tokens: clamp(asked * 2, MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS),
    // 맞춤법 교정에 숙고는 필요 없다. 켜 두면 몇 초씩 더 걸리고, 숙고 토큰은 출력
    // 요금으로 청구된다. 구글 쪽 thinkingBudget:0 과 같은 자리다.
    reasoning_effort: 'minimal',
    // temperature 는 보내지 않는다. gpt-5 계열은 이 항목을 받으면 400 을 돌려준다.
  });
}

/**
 * OpenAI 응답을 구글 모양으로. 앱의 파서와 토큰 집계가 그대로 돌아간다.
 *
 * @returns `{ status, text }` — 중계 결과와 같은 모양.
 */
export function toGeminiReply(status, text) {
  const parsed = safeParse(text);

  if (status !== 200) {
    return errorReply(status, parsed?.error?.message ?? `HTTP ${status}`);
  }

  const choice = parsed?.choices?.[0];
  const corrected = (choice?.message?.content ?? '').trim();

  // **잘린 답은 주면 안 된다.** 앱은 받은 글로 입력란을 통째로 덮으므로, 뒷부분이
  // 잘린 교정문을 주면 사용자가 쓴 글이 그만큼 사라진다. 차라리 실패로 알린다.
  if (choice?.finish_reason === 'length') return errorReply(502, '글이 너무 길어 교정문이 잘렸다');
  if (!corrected) return errorReply(502, `응답이 비었다 (${choice?.finish_reason ?? 'unknown'})`);

  const usage = parsed?.usage ?? {};
  const reasoning = num(usage.completion_tokens_details?.reasoning_tokens);
  return {
    status: 200,
    text: JSON.stringify({
      candidates: [
        { content: { role: 'model', parts: [{ text: corrected }] }, finishReason: 'STOP' },
      ],
      usageMetadata: {
        promptTokenCount: num(usage.prompt_tokens),
        // OpenAI 의 completion_tokens 에는 숙고 토큰이 들어 있고, 구글의
        // candidatesTokenCount 에는 없다. 빼서 넣어야 /stats 합계가 두 번 세지 않는다.
        candidatesTokenCount: Math.max(0, num(usage.completion_tokens) - reasoning),
        thoughtsTokenCount: reasoning,
      },
    }),
  };
}

/**
 * 앱에 알려 줄 모델 목록. 앱은 이 목록으로 "쓸 수 있는 이름" 을 판단하고, 붐빌 때
 * 옮겨 갈 후보를 고른다. 우리는 서버가 모델을 정하므로 지금 쓰는 하나만 알려 준다.
 */
export function modelList(model) {
  return { models: [{ name: `models/${model}`, supportedGenerationMethods: ['generateContent'] }] };
}

function partsText(node) {
  return (node?.parts ?? []).map((part) => part?.text ?? '').join('');
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

function num(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function clamp(value, min, max) {
  if (!Number.isFinite(value) || value < min) return min;
  return Math.min(value, max);
}
