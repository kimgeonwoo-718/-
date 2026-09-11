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
