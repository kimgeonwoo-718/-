/**
 * 계정과 기기. **안드로이드·아이폰·윈도우가 구독 하나를 같이 쓰게** 하는 부분이다.
 *
 * ## 왜 있나
 *
 * 구독은 구글 플레이 구매 토큰으로 돌아간다. 안드로이드는 Play 에 직접 물어보면 되지만,
 * 아이폰은 스토어가 다르고 **윈도우에는 스토어가 아예 없다.** 물어볼 데가 없는 기기에
 * "이 사람 구독자다" 를 알려 주려면 그 사이를 이어 줄 것이 필요하다.
 *
 * ## 무엇을 하고 무엇을 안 하나
 *
 * **계정은 구매를 가리키는 포인터일 뿐이다.** 구독 상태의 진실은 계속 Play 가 갖는다.
 *
 *     계정 ──가리킴──> 구매 토큰 ──확인──> Google Play
 *
 * 그래서 환불·해지·만료를 여기서 관리하지 않는다. 물어볼 때마다 Play 에 확인한다
 * (그 확인은 [cache] 가 몇 분 기억한다). 계정 표가 통째로 날아가도 안드로이드는
 * 멀쩡하다 — 거긴 Play 에 직접 묻기 때문이다.
 *
 * 하루 한도도 그대로다. 기기 토큰은 계정을 거쳐 **같은 구매 토큰**으로 풀리므로,
 * 윈도우에서 쓰든 폰에서 쓰든 "결제 하나에 한도 하나" 가 저절로 지켜진다.
 *
 * ## 신원 증명은 여기 없다
 *
 * 기기 토큰을 **어떻게 받아 가느냐**(연결 코드냐 소셜 로그인이냐)는 이 파일 밖의 일이다.
 * 어느 쪽을 고르든 끝은 같다 — 기기가 토큰을 들고 다니고, 그 토큰이 계정을 가리킨다.
 * 그래서 여기를 먼저 지어 두면 나중에 한 겹만 얹으면 된다. docs/ACCOUNTS.md 참고.
 */

/** 기기 토큰 길이(바이트). 32 바이트면 찍어 맞히는 것은 생각할 필요가 없다. */
const TOKEN_BYTES = 32;

export function randomToken(bytes = TOKEN_BYTES) {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return [...buffer].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** 기기 토큰의 모양. 남이 아무 문자열이나 넣어 열쇠를 만들지 못하게 좁혀 둔다. */
export function validDeviceToken(token) {
  return typeof token === 'string' && /^[0-9a-f]{64}$/.test(token);
}

/**
 * 이 구매를 가진 계정을 찾고, 없으면 만든다.
 *
 * **구매 하나에 계정 하나다**(`accounts_purchase` 유일 색인). 같은 구매로 다시 부르면
 * 새로 만들지 않고 있던 계정을 준다 — 안드로이드에서 앱을 지웠다 깔아도 계정이 갈리지
 * 않아야 하고, 갈리면 기기 연결이 통째로 끊긴다.
 */
export async function accountForPurchase(db, purchaseToken, nowMs, store = 'play') {
  const found = await db
    .prepare('SELECT id FROM accounts WHERE purchase_token = ?')
    .bind(purchaseToken)
    .first();
  if (found) {
    await db.prepare('UPDATE accounts SET updated_at = ? WHERE id = ?').bind(nowMs, found.id).run();
    return found.id;
  }
  const id = randomToken(16);
  await db
    .prepare(
      'INSERT INTO accounts (id, purchase_token, store, created_at, updated_at) VALUES (?, ?, ?, ?, ?)'
    )
    .bind(id, purchaseToken, store, nowMs, nowMs)
    .run();
  return id;
}

/**
 * 계정에 기기를 붙이고 **토큰을 한 번만** 돌려준다.
 *
 * 우리는 해시만 저장한다. 비밀번호와 같은 이유다 — 데이터베이스가 새도 그것만으로는
 * 남의 기기 행세를 할 수 없어야 한다. 그래서 잃어버리면 다시 발급받는 수밖에 없다.
 */
export async function issueDevice(db, accountId, label, nowMs) {
  const token = randomToken();
  await db
    .prepare(
      'INSERT INTO devices (token_hash, account_id, label, created_at, seen_at) VALUES (?, ?, ?, ?, ?)'
    )
    .bind(await sha256Hex(token), accountId, label ?? null, nowMs, nowMs)
    .run();
  return token;
}

/**
 * 기기 토큰으로 그 계정의 **구매 토큰**을 찾는다. 없으면 null.
 *
 * 돌려주는 것은 호출한 쪽이 Play 에 물어보는 데만 쓴다. **기기로 다시 내보내지 않는다** —
 * 구매 토큰은 그 자체가 구독 증명이라, 윈도우에 내려보내면 그게 새는 통로가 된다.
 */
export async function purchaseForDevice(db, deviceToken, nowMs) {
  if (!validDeviceToken(deviceToken)) return null;
  const hash = await sha256Hex(deviceToken);
  const row = await db
    .prepare(
      'SELECT a.purchase_token AS purchase_token, a.id AS account_id FROM devices d ' +
        'JOIN accounts a ON a.id = d.account_id WHERE d.token_hash = ?'
    )
    .bind(hash)
    .first();
  if (!row) return null;
  await db.prepare('UPDATE devices SET seen_at = ? WHERE token_hash = ?').bind(nowMs, hash).run();
  return { purchaseToken: row.purchase_token ?? '', accountId: row.account_id };
}

/** 이 계정에 붙은 기기 수. 코드를 퍼뜨리는 것을 막는 데 쓴다. */
export async function deviceCount(db, accountId) {
  const row = await db
    .prepare('SELECT COUNT(*) AS n FROM devices WHERE account_id = ?')
    .bind(accountId)
    .first();
  return Number(row?.n ?? 0);
}

/** 기기를 뗀다. 사용자가 "이 기기 연결 끊기" 를 눌렀을 때. */
export async function revokeDevice(db, deviceToken) {
  if (!validDeviceToken(deviceToken)) return false;
  await db.prepare('DELETE FROM devices WHERE token_hash = ?').bind(await sha256Hex(deviceToken)).run();
  return true;
}
