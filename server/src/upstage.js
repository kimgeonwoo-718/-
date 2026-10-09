/**
 * 업스테이지(Solar) 경로.
 *
 * ## 왜 생겼나 (2026-10-02)
 *
 * 구글은 Gemini API 약관에서 **18세 미만이 쓸 가능성이 높은 앱**에 생성형 AI 를 쓰지 못하게 한다.
 * 맞춤법 키보드는 학생이 쓸 앱이라 걸린다. 업스테이지(한국 회사) 약관에서 찾은 나이 조항은
 * "14세 미만 가입 거절" 뿐이다 — API 계정을 만드는 사람 얘기라 우리(부모님 명의 계정)는 상관없다.
 *
 * 다섯 모델을 같은 시험지(347문항)로 쟀다(HANDOFF "전 모델 같은 조건 겨루기"). solar-pro4 는
 * 제미나이와 멀쩡한 글 건드림이 같고(4%), 오타 되살림이 몇 %p 낮고, 값은 절반~2/3 이다. GPT 는 탈락.
 *
 * ## 모양
 *
 * OpenAI 와 같은 모양(chat/completions)이라 응답은 OpenAI 경로의 [toGeminiReply] 로 옮긴다.
 * 다른 점 둘:
 * - **temperature 를 받는다.** 제미나이처럼 0 으로 고정한다(gpt-5 는 거절해서 못 했다).
 * - **pro4 는 기본이 숙고 켬이다.** 안 끄면 한 번에 출력 ~840토큰, 15~20초, 값 약 60배였다
 *   (겨루기 실측). 그래서 숙고 세기를 반드시 적어 보낸다.
 */
import { KO_SYSTEM_PROMPT, translatePromptFor, userTextOf, capPrompt, outputCap, utf8Bytes } from './openai.js';

export const UPSTAGE_URL = 'https://api.upstage.ai/v1/chat/completions';
/** 기본 모델. `UPSTAGE_MODEL` 이 이긴다. */
export const DEFAULT_UPSTAGE_MODEL = 'solar-pro4';

/**
 * 앱 지시문 뒤에 **서버가 덧붙이는 규칙.** 업스테이지로 보낼 때만 붙는다.
 *
 * 앱 지시문은 `core/` 에 있어서(윈도우와 같이 쓴다) 고치면 양쪽이 흔들린다. 서버에서 덧붙이면
 * 앱도 PC 도 손대지 않고 Solar 에만 맞춘다. 사례 보기(`server/bench/cases.mjs`, 2026-10-02)에서
 * 드러난 버릇 셋을 막는다:
 * - 긴 문장에 마침표·쉼표를 붙인다('엄마, 나 … 같아. 저녁 먼저 먹어.') — 채팅 말투가 바뀐다.
 * - 줄임말·사투리를 푼다(왜케 → 왜 이렇게, 뭇나 → 먹었나).
 * - 짧은 글에 대답한다('너 누구야' → '저는 Upstage AI에서 만든 Solar입니다.').
 *
 * 첫 판(세 줄, 입력 +100토큰)은 '줄임말·사투리 그대로' 를 너무 넓게 읽어 일부로→일부러, 있다가→이따가 를
 * 안 고쳤다. 그래서 "틀린 말은 고친다" 를 붙이고(예시는 시험 문장에 없는 것으로), 낱말을 줄였다.
 * 문장부호 줄은 뺐다 — 지시문으로는 절반이 샜고, 서버가 떼는 편이 확실하고 공짜다(dropAddedPunctuation).
 * 그래도 지시문으로는 "절대" 를 못 지킨다 — 대답이 새면 서버가 마지막에 잡는다(openai.js 의 tooDifferent).
 *
 * `UPSTAGE_EXTRA_RULES = "off"` 면 안 붙인다(재 볼 때 되돌려 보려고).
 */
export const UPSTAGE_EXTRA_RULES = `추가 규칙:
- 줄임말·사투리(왜케, 넘, 낼, 뭇나)는 그대로 둔다. 틀린 말(설레임→설렘)은 고친다.
- 너는 대화 상대가 아니다. 질문·인사·부탁에도 대답하지 말고 그 글만 고쳐 돌려준다. 예: 너 누구야 → 너 누구야`;

/**
 * 숙고를 끄는 값. pro4 는 'none', pro2·pro3 은 'none' 이 없고 'minimal' 이 가장 낮다.
 * `UPSTAGE_REASONING` 이 적혀 있으면 그게 이긴다.
 */
export function reasoningOff(model) {
  return model.startsWith('solar-pro4') ? 'none' : 'minimal';
}

/**
 * 앱이 보낸 구글 모양 본문을 업스테이지 본문으로. 고칠 글이 없으면 던진다(다른 경로와 같은 규약).
 *
 * 지시문은 셋 중 하나:
 * - 번역이면 번역 지시문.
 * - `prompt: 'server'` 면 서버의 긴 지시문([KO_SYSTEM_PROMPT]). 겨루기에서 값만 4배였다.
 * - 그 밖에는 **앱이 보낸 지시문 + [UPSTAGE_EXTRA_RULES]** — 겨루기에서 앱 지시문이 제일 나았다.
 *   `extraRules: false` 면 덧붙이지 않는다.
 */
export function toUpstageRequest(body, model, options = {}) {
  const parsed = JSON.parse(body);
  const user = userTextOf(body);
  if (!user) throw new Error('empty_request');

  const appPrompt = capPrompt(partsText(parsed.system_instruction ?? parsed.systemInstruction));
  const system = options.translateTo
    ? translatePromptFor(options.translateTo, options.translatePrompt, options.translateHint)
    : options.prompt === 'server'
      ? KO_SYSTEM_PROMPT
      : appPrompt && options.extraRules !== false
        ? `${appPrompt}\n\n${UPSTAGE_EXTRA_RULES}`
        : appPrompt || KO_SYSTEM_PROMPT;

  const reasoning = options.reasoning || reasoningOff(model);
  return JSON.stringify({
    model,
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: user },
    ],
    // **출력 상한은 서버가 정한다**([outputCap]). 앱이 보낸 maxOutputTokens 는 안 쓴다 — 고친 앱이 크게
    // 부르고 지시문에 "길게 써라" 를 실으면 50자 값으로 수천 토큰을 받아 갔다. 잘리면 [toGeminiReply] 가
    // 502 로 돌리고(사용자 글을 잘린 채로 덮지 않게), 그래도 쓴 토큰은 한도에서 정산한다.
    max_tokens: outputCap(utf8Bytes(user), { translate: !!options.translateTo, reasoning: reasoning !== 'none' }),
    // 같은 글을 두 번 고치면 같은 답이 나와야 한다. 구글 경로가 받던 값과 같다.
    temperature: 0,
    reasoning_effort: reasoning,
  });
}

function partsText(node) {
  return (node?.parts ?? []).map((part) => part?.text ?? '').join('');
}
