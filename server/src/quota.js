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
 * 손해다. 글자로 세면 하루 10만 자 안에서 100자짜리는 1,000번, 2,000자짜리는 50번이다.
 *
 * 호출마다 붙는 고정 비용(지시문)이 있어서 너무 짧은 호출은 최소 [MIN_CHARGE] 자로
 * 친다 — 안 그러면 한 글자짜리를 만 번 보내는 것이 공짜가 된다.
 */
export const DEFAULT_SUB_DAILY_CHARS = 100_000;
const MIN_CHARGE = 50;

export function chargeFor(chars) {
  return Math.max(MIN_CHARGE, Math.floor(Number(chars) || 0));
}

/** 오늘 [used] 자를 썼을 때 [charge] 자짜리를 하나 더 써도 되는가. */
export function decideChars(used, charge, limit) {
  return { allowed: used + charge <= limit, remaining: Math.max(0, limit - used) };
}
