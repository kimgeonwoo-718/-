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
 */
export const DEFAULT_SUB_DAILY_CHARS = 10_000;
const MIN_CHARGE = 50;

export function chargeFor(chars) {
  return Math.max(MIN_CHARGE, Math.floor(Number(chars) || 0));
}

/** 오늘 [used] 자를 썼을 때 [charge] 자짜리를 하나 더 써도 되는가. */
export function decideChars(used, charge, limit) {
  return { allowed: used + charge <= limit, remaining: Math.max(0, limit - used) };
}
