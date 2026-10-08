/**
 * 무료 한도 계산. 안드로이드 쪽 AiQuota 와 같은 규칙을 서버에서 다시 세운다 —
 * 폰에 저장된 숫자는 누구나 고칠 수 있어서, 돈이 걸린 판단은 여기서만 한다.
 */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/**
 * 한국 시간 기준 오늘. UTC 로 자르면 한국에서는 오전 9 시에 하루가 바뀌어 "하루 5 회" 가
 * 말이 안 된다.
 */
export function kstDay(nowMs = Date.now()) {
  return new Date(nowMs + KST_OFFSET_MS).toISOString().slice(0, 10);
}

/** 지금까지 [used] 번 썼을 때 한 번 더 써도 되는가. */
export function decide(used, limit) {
  return { allowed: used < limit, remaining: Math.max(0, limit - used) };
}

/**
 * 앱이 만든 설치 ID 인가. UUID 하나면 되고, 남이 아무 문자열이나 넣어 키를 만들지
 * 못하게 모양을 좁혀 둔다.
 */
export function validInstallId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9-]{8,64}$/.test(id);
}

/**
 * 구독자는 횟수가 아니라 **글자 수**로 센다.
 *
 * 요금은 글자 수에 붙는다(교정문이 원문만큼 나오므로). 횟수로 세면 카톡 한 줄 고치는
 * 사람과 A4 한 장 고치는 사람이 같은 값을 치르게 되고, 한도를 어느 쪽에 맞춰도 한쪽은
 * 손해다.
 *
 * ## 왜 1만인가
 *
 * **2026-09-25 실측**(bench/cost.mjs, 앱과 같은 요청, 청구 토큰 기준, 환율 1,400원): 한 번 고정분
 * **0.095원 + 글자당 0.00213원**. 한글이 1자 ≈ 0.55토큰이라 아래 추정(1.21토큰)의 절반이 안 된다.
 * 아래 표는 옛 추정 그대로 두었다 — 실제 값은 대략 그 **절반~6할**이다(가장 나쁜 경우 월 1,212원).
 *
 * **어떻게 쓰든 구독 하나가 적자를 못 내게.** 한 번 값은 지시문 고정분 0.12원 + 글자당
 * 0.0046원(gemini-3.5-flash-lite, 환율 1,350원)이라 짧게 여러 번 보낼수록 비싸다.
 * 한도 1만 자를 매일 꽉 채웠을 때 그 사람한테서 나가는 돈(월):
 *
 *     50자씩 200번 (가장 나쁜 경우)   2,100원
 *     200자씩 50번                    1,560원
 *     500자씩 20번                    1,452원
 *     2,000자씩 5번                   1,398원
 *
 * 받는 돈은 2,990원(수수료 떼면 2,691원). **가장 나쁜 경우에도 남는다.** 2만이었을
 * 때는 덩어리 크기와 상관없이 꽉 채우면 적자였다(글자당 값만으로 하루 92원 > 89.7원).
 *
 * 보통 사용자는 하루 5천 자쯤 쓰고(월 780원) 이 한도를 못 느낀다. 카톡 한 줄(100자)이면
 * 하루 100번, 긴 문자(500자)는 20번, A4 반 장(2,000자)은 5번이다.
 *
 * ## 1만 5천으로 (2026-10-02)
 *
 * 업스테이지 solar-pro4 로 옮겼다. 정가로 한 번 고정분 약 0.11원(앱 지시문 + 서버가 덧붙이는 두 줄) +
 * 1,000자에 약 0.84원(사례 보기·겨루기 실측 토큰, 환율 1,400원). 1만 5천을 매일 꽉 채우면(월):
 *
 *     50자씩 300번 (가장 나쁜 경우)   약 1,370원
 *     200자씩 75번                    약 630원
 *     2,000자씩 8번                   약 400원
 *
 * 가장 나쁜 경우에도 실수령 2,691원 안에 든다. 실제 한도는 wrangler.toml 의 SUB_DAILY_CHARS 가 정하고,
 * 이 값은 그게 없을 때의 기본값이다.
 */
export const DEFAULT_SUB_DAILY_CHARS = 15_000;
const MIN_CHARGE = 50;

export function chargeFor(chars) {
  return Math.max(MIN_CHARGE, Math.floor(Number(chars) || 0));
}

/** 오늘 [used] 자를 썼을 때 [charge] 자짜리를 하나 더 써도 되는가. */
export function decideChars(used, charge, limit) {
  return { allowed: used + charge <= limit, remaining: Math.max(0, limit - used) };
}

/**
 * ## 글자 대신 실제 값으로 정산 (2026-10-08, 윈도우 점검)
 *
 * 한도는 사용자 글자 수로 세는데 값은 모델이 쓴 토큰으로 나간다. 보통은 둘이 비슷하지만, 고친 앱이 지시문에
 * "길게 써라" 를 실으면 50자만 깎이고 수천 토큰이 나갔다(구독 하나가 월 12만~17만 원). 그래서 **값이 나간
 * 답은 실제 토큰으로 정산**한다. 단위는 그대로 "글자":
 *
 *     정산 = max(글자 수(최소 50), ⌈(입력 토큰 + 4 × (출력 토큰 + 숙고 토큰)) / 10⌉)
 *
 * 4 는 Solar 출력값이 입력값의 네 배라서(0.30 / 1.20 달러), 10 은 보통 사용이 지금과 똑같이 깎이게 맞춘 값이다.
 * 실측 토큰(앱 지시문 고정분 약 262 + 한글 1자 약 0.4)으로 50자 요청은 ⌈(282 + 80) / 10⌉ = 37 → 50,
 * 2,000자는 ⌈(1,062 + 3,200) / 10⌉ = 427 → 2,000 — **보통 사용은 글자 수가 이긴다.** 길게 쓰게 시킨 요청만
 * 토큰이 이겨 더 깎인다. 1만 5천 "글자" 한도 = 하루 값 15만 단위 ≈ 출력 3만 7천 토큰 ≈ 하루 63원(월 약 1,900원)이
 * 공격의 천장이다.
 *
 * 토큰 수가 안 오면(0) 글자 수로만 깎인다 — /stats 에 입력 0 인 날이 있으면 정산이 안 된 것이다.
 *
 * [tokens] 는 `{ prompt, output, thoughts }` — /stats 에 쌓는 것과 같은 모양(index.js 의 usageOf).
 */
export function settleChars(charge, tokens) {
  const prompt = nonNegative(tokens?.prompt);
  const output = nonNegative(tokens?.output) + nonNegative(tokens?.thoughts);
  return Math.max(charge, Math.ceil((prompt + 4 * output) / 10));
}

/**
 * 보내기 **전에** 잡아 둘 최악값. 정산은 답이 온 뒤라, 미리 글자 수만 잡고 들여보내면 동시에 여럿을
 * 보내거나 남은 자리 끝에 끼워 넣어 정산 전에 한도를 뚫는다. 그래서 그 요청이 쓸 수 있는 최대를 통째로 잡는다:
 *
 *     예약 = max(정산의 바닥(글자 수), ⌈(보내는 요청 바이트 + 4 × 출력 상한) / 10⌉)
 *
 * 토큰 하나는 1바이트 이상이라 입력 토큰은 요청 바이트를 못 넘고, 출력은 출력 상한을 못 넘는다 — 정산값의 위쪽
 * 경계다. 끝나면 실제 정산값과의 차이만큼 돌려주거나 더 깎는다.
 */
export function holdChars(charge, requestBytes, maxOutputTokens) {
  return Math.max(charge, Math.ceil((nonNegative(requestBytes) + 4 * nonNegative(maxOutputTokens)) / 10));
}

function nonNegative(value) {
  const n = Number(value ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
}
