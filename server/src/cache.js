/** D1 한 테이블로 만든 만료 있는 캐시. Play 확인 결과와 구글 액세스 토큰을 둔다. */

export async function cacheGet(db, key, nowMs) {
  const row = await db
    .prepare('SELECT value, expires_at FROM cache WHERE key = ?')
    .bind(key)
    .first();
  if (!row || row.expires_at <= nowMs) return null;
  try {
    return JSON.parse(row.value);
  } catch {
    return null;
  }
}

export async function cacheSet(db, key, value, ttlMs, nowMs) {
  await db
    .prepare('INSERT OR REPLACE INTO cache (key, value, expires_at) VALUES (?, ?, ?)')
    .bind(key, JSON.stringify(value), nowMs + ttlMs)
    .run();
}
