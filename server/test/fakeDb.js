/**
 * D1 흉내. index.js 가 실제로 쓰는 문장 여섯 개만 알아듣는다.
 * 모르는 SQL 이 오면 죽는다 — 서버 쪽 쿼리가 바뀌면 여기서 바로 드러나게.
 */
export function fakeDb() {
  const usage = new Map();
  const cache = new Map();

  const key = (id, day) => `${id}|${day}`;

  return {
    usage,
    cache,
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.startsWith('SELECT used')) {
                const value = usage.get(key(args[0], args[1]));
                return value == null ? null : { used: value };
              }
              if (sql.startsWith('SELECT value')) {
                return cache.get(args[0]) ?? null;
              }
              throw new Error(`fakeDb: unexpected first(): ${sql}`);
            },
            async run() {
              if (sql.startsWith('INSERT INTO usage')) {
                const k = key(args[0], args[1]);
                usage.set(k, (usage.get(k) ?? 0) + 1);
                return;
              }
              if (sql.startsWith('INSERT OR REPLACE INTO cache')) {
                cache.set(args[0], { value: args[1], expires_at: args[2] });
                return;
              }
              if (sql.startsWith('DELETE FROM usage')) {
                for (const k of [...usage.keys()]) if (k.split('|')[1] < args[0]) usage.delete(k);
                return;
              }
              if (sql.startsWith('DELETE FROM cache')) {
                for (const [k, v] of [...cache]) if (v.expires_at < args[0]) cache.delete(k);
                return;
              }
              throw new Error(`fakeDb: unexpected run(): ${sql}`);
            },
          };
        },
      };
    },
  };
}
