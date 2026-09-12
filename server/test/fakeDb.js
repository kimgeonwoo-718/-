/**
 * D1 흉내. index.js 가 실제로 쓰는 문장 여섯 개만 알아듣는다.
 * 모르는 SQL 이 오면 죽는다 — 서버 쪽 쿼리가 바뀌면 여기서 바로 드러나게.
 */
export function fakeDb() {
  const usage = new Map();
  const cache = new Map();
  const tokens = new Map();

  const key = (id, day) => `${id}|${day}`;

  return {
    usage,
    cache,
    tokens,
    prepare(sql) {
      // D1 은 bind 없이도 first/all/run 을 부를 수 있다 (인자 없는 문장).
      const bound = (args) => ({
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
            async all() {
              if (sql.startsWith('SELECT day, requests, prompt, output, thoughts FROM tokens')) {
                const results = [...tokens.entries()]
                  .sort((a, b) => (a[0] < b[0] ? 1 : -1))
                  .map(([day, v]) => ({ day, ...v }));
                return { results };
              }
              throw new Error(`fakeDb: unexpected all(): ${sql}`);
            },
            async run() {
              if (sql.startsWith('INSERT INTO tokens')) {
                const [day, prompt, output, thoughts] = args;
                const cur = tokens.get(day) ?? { requests: 0, prompt: 0, output: 0, thoughts: 0 };
                tokens.set(day, {
                  requests: cur.requests + 1,
                  prompt: cur.prompt + prompt,
                  output: cur.output + output,
                  thoughts: cur.thoughts + thoughts,
                });
                return;
              }
              if (sql.startsWith('DELETE FROM tokens')) {
                for (const k of [...tokens.keys()]) if (k < args[0]) tokens.delete(k);
                return;
              }
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
      });
      return {
        bind: (...args) => bound(args),
        first: () => bound([]).first(),
        all: () => bound([]).all(),
        run: () => bound([]).run(),
      };
    },
  };
}
