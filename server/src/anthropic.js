/**
 * Anthropic(Claude) 경로.
 *
 * ## 왜 생겼나 (2026-10-01)
 *
 * 구글은 Gemini API 약관(그리고 구글 클라우드의 생성형 AI 약관)에서 **18세 미만이 쓸 가능성이
 * 높은 앱**에 생성형 AI 를 쓰지 못하게 한다. 서버에서만 부르는 것도, 유료 등급도 예외가 아니다.
 * 맞춤법 키보드는 학생이 쓸 앱이라 걸린다. Anthropic 은 미성년이 쓰는 서비스도 **안전장치를
 * 갖추면** 허용한다(AI 를 쓴다는 고지, 해로운 출력 거르기 등 — "Guidelines for Organizations
 * Serving Minors"). 우리는 사용자가 쓴 글을 고쳐 돌려주기만 하고, 교정이 아닌 답은
 * [tooDifferent] 로 버리므로 그 조건에 맞추기 쉽다.
 *
 * ## 모양
 *
 * 앱은 구글 모양(`system_instruction` + `contents`)으로 보내고 구글 모양 응답을 읽는다.
 * OpenAI 경로와 같이 **여기서 옮겨 주고 옮겨 받는다** — 이미 깔린 APK 는 손대지 않는다.
 */
import { KO_SYSTEM_PROMPT, translatePrompt, userTextOf, tooDifferent } from './openai.js';

export const ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages';
export const ANTHROPIC_VERSION = '2023-06-01';
/** 기본 모델. 맞춤법은 판단이 아니라 패턴이라 가장 작은 급으로 시작한다. `CLAUDE_MODEL` 이 이긴다. */
export const DEFAULT_CLAUDE_MODEL = 'claude-haiku-4-5-20251001';

/**
 * 출력 한도. Claude 는 이 값이 **필수**다. 앱이 보내는 4096 을 기준으로 넉넉히 잡는다 —
 * 잘린 답은 사용자 글을 잘린 채로 덮어 버리므로(앱은 받은 글로 통째로 갈아 끼운다) 아예
 * 실패로 돌린다. 요금은 실제로 쓴 만큼만 나간다.
 */
const MIN_OUTPUT_TOKENS = 2048;
const MAX_OUTPUT_TOKENS = 8192;

/**
 * 앱이 보낸 구글 모양 본문을 Claude 본문으로. 고칠 글이 없으면 던진다(다른 경로와 같은 규약).
 *
 * 지시문은 셋 중 하나:
 * - 번역이면 번역 지시문.
 * - `prompt: 'server'` 면 서버 지시문(예시가 많은 긴 판, [KO_SYSTEM_PROMPT]).
 * - 그 밖에는 **앱이 보낸 지시문 그대로** — 제미나이가 "교정 완벽" 평가를 받은 판이다.
 *   어느 쪽이 Claude 에 맞는지는 `server/bench/` 로 재서 `CLAUDE_PROMPT` 로 고른다.
 */
export function toClaudeRequest(body, model, options = {}) {
  const parsed = JSON.parse(body);
  const user = userTextOf(body);
  if (!user) throw new Error('empty_request');

  const system = options.translateTo
    ? translatePrompt(options.translateTo)
    : options.prompt === 'server'
      ? KO_SYSTEM_PROMPT
      : partsText(parsed.system_instruction ?? parsed.systemInstruction) || KO_SYSTEM_PROMPT;

  const asked = Number(parsed.generationConfig?.maxOutputTokens ?? 0);
  const request = {
    model,
    max_tokens: clamp(asked, MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS),
    // 같은 글을 두 번 고치면 같은 답이 나와야 한다. 구글 경로가 받던 값과 같다.
    temperature: 0,
    messages: [{ role: 'user', content: user }],
  };
  if (system) request.system = system;
  return JSON.stringify(request);
}

/**
 * Claude 응답을 구글 모양으로. 앱의 파서와 서버의 토큰 집계(/stats)가 그대로 돈다.
 *
 * @returns `{ status, text }` — 중계 결과와 같은 모양.
 */
export function fromClaudeReply(status, text, user = '', options = {}) {
  const parsed = safeParse(text);

  if (status !== 200) {
    return errorReply(status, parsed?.error?.message ?? `HTTP ${status}`);
  }

  const corrected = (parsed?.content ?? [])
    .filter((block) => block?.type === 'text')
    .map((block) => block.text ?? '')
    .join('')
    .trim();

  // 잘린 답은 주지 않는다 — 앱이 사용자 글을 잘린 교정문으로 덮어 버린다.
  if (parsed?.stop_reason === 'max_tokens') return errorReply(502, '글이 너무 길어 교정문이 잘렸다');
  if (!corrected) return errorReply(502, `응답이 비었다 (${parsed?.stop_reason ?? 'unknown'})`);
  // 길이로 거르는 것은 교정일 때만이다. 번역문은 원문과 길이가 다른 게 당연하다.
  if (user && !options.translateTo && tooDifferent(user, corrected)) {
    return errorReply(502, '교정 결과가 원문과 너무 달라 버렸다');
  }

  const usage = parsed?.usage ?? {};
  return {
    status: 200,
    text: JSON.stringify({
      candidates: [
        { content: { role: 'model', parts: [{ text: corrected }] }, finishReason: 'STOP' },
      ],
      usageMetadata: {
        // 캐시로 읽은 입력도 입력이다. /stats 는 요금 짐작에 쓰므로 빠뜨리지 않는다.
        promptTokenCount:
          num(usage.input_tokens) + num(usage.cache_read_input_tokens) + num(usage.cache_creation_input_tokens),
        candidatesTokenCount: num(usage.output_tokens),
        thoughtsTokenCount: 0,
      },
    }),
  };
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
