/**
 * 맞춤법 키보드 AI 중계 서버.
 *
 * 앱에는 Gemini 키가 없다. 앱은 구글 대신 여기로 보내고, 여기서 키를 붙여 구글로
 * 넘긴다. 그래서 APK 를 뜯어도 가져갈 키가 없다 — 콘솔에서 키를 앱에 묶는 것보다
 * 확실한 방어다.
 *
 * 하는 일은 셋뿐이다.
 *   1. 키 주입   요청을 구글에 그대로 넘기되 x-goog-api-key 를 붙인다.
 *   2. 결제 확인 X-Purchase-Token 이 있으면 Play Developer API 로 구독이 살아 있는지 본다.
 *   3. 무료 한도 구독자가 아니면 설치 ID 당 하루 N 회. **성공한 요청만** 센다.
 *
 * 모델 고르기, 숙고 끄기, 붐빌 때 갈아타기 같은 판단은 전부 앱에 있다. 앱 쪽 테스트
 * 백여 개가 그걸 지키고 있어서, 여기서 다시 만들면 두 벌이 어긋난다. 서버는 얇게 둔다.
 * 경로도 구글과 똑같이 둬서 앱은 호스트만 바꾸면 된다.
 */
import { kstDay, decide, validInstallId } from './quota.js';
import { fetchAccessToken, verifySubscription } from './play.js';
import { cacheGet, cacheSet } from './cache.js';

const UPSTREAM = 'https://generativelanguage.googleapis.com';

/** 교정 창이 앞뒤 2000 자에 지시문 1KB 라 16KB 남짓이다. 그 몇 배면 충분하다. */
const MAX_BODY_BYTES = 64 * 1024;

/** Play 확인 결과를 들고 있는 시간. 해지해도 이만큼은 더 쓸 수 있는 셈이다. */
const VERDICT_TTL_MS = 60 * 60 * 1000;

const ACCESS_TOKEN_MARGIN_SEC = 300;

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (_event, env) => sweep(env),
};

/** 테스트에서 fetch 와 시계를 갈아 끼울 수 있게 진입점을 따로 둔다. */
export async function handle(request, env, deps = {}) {
  const fetchImpl = deps.fetch ?? fetch;
  const now = deps.now ?? (() => Date.now());
  const url = new URL(request.url);

  if (url.pathname === '/health') return json(200, { ok: true });
  if (!env.GEMINI_API_KEY) return fail(503, 'server_not_configured');

  // 모델 목록은 돈이 안 든다. 한도 없이 그대로 넘긴다.
  if (request.method === 'GET' && url.pathname === '/v1beta/models') {
    return proxy(fetchImpl, env, url, 'GET', null);
  }
  const isGenerate = /^\/v1beta\/models\/[^/]+:generateContent$/.test(url.pathname);
  if (request.method === 'POST' && isGenerate) {
    return correct(request, env, url, fetchImpl, now);
  }
  return fail(404, 'not_found');
}

async function correct(request, env, url, fetchImpl, now) {
  const installId = request.headers.get('x-install-id') ?? '';
  if (!validInstallId(installId)) return fail(400, 'invalid_install_id');

  if (Number(request.headers.get('content-length') ?? 0) > MAX_BODY_BYTES) {
    return fail(413, 'too_long');
  }
  const body = await request.text();
  if (body.length > MAX_BODY_BYTES) return fail(413, 'too_long');

  const nowMs = now();
  const day = kstDay(nowMs);
  const limit = Number(env.FREE_DAILY_LIMIT ?? 5);
  const purchaseToken = request.headers.get('x-purchase-token') ?? '';
  const subscriber = await isSubscriber(env, purchaseToken, fetchImpl, nowMs);
  const plan = subscriber ? 'subscriber' : 'free';

  let usage = null;
  let ipKey = null;
  if (!subscriber) {
    // 설치 ID 를 갈아 끼우며 무료를 무한히 쓰는 것을 IP 로 한 번 더 막는다. 완벽하지
    // 않지만(통신사 NAT), 무료 한도를 우회하려는 사람에게 값을 치르게 하는 정도는 된다.
    ipKey = await ipBucket(request);
    if ((await used(env.DB, ipKey, day)) >= Number(env.IP_DAILY_LIMIT ?? 300)) {
      return fail(403, 'too_many_requests');
    }
    usage = decide(await used(env.DB, installId, day), limit);
    // 402 를 쓰는 이유: 앱은 429 와 5xx 를 "붐빔" 으로 보고 다른 모델로 옮겨 다시
    // 보낸다. 한도 초과에 그러면 헛요청 세 번이다. 402 는 그 목록에 없어 바로 멈춘다.
    if (!usage.allowed) return withQuota(fail(402, 'free_daily_limit'), usage, limit, plan);
  }

  const upstream = await proxy(fetchImpl, env, url, 'POST', body);

  // 성공했을 때만 깎는다. 구글이 거절한 요청까지 세면 사용자는 아무것도 못 받고
  // 하루치만 잃는다 — 앱이 예전에 지키던 규칙과 같다.
  if (upstream.status === 200 && !subscriber) {
    await Promise.all([bump(env.DB, installId, day), bump(env.DB, ipKey, day)]);
    usage = { remaining: Math.max(0, usage.remaining - 1) };
  }
  return withQuota(upstream, usage, limit, plan);
}

/**
 * 구글로 그대로 넘긴다. 클라이언트 헤더는 하나도 넘기지 않는다 — 설치 ID 나 구매
 * 토큰이 구글로 새어 나갈 이유가 없고, 우리 키만 붙이면 된다.
 */
async function proxy(fetchImpl, env, url, method, body) {
  const res = await fetchImpl(UPSTREAM + url.pathname + url.search, {
    method,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      // 사람이 붙여넣은 비밀값이라 끝에 줄바꿈이 딸려 온 적이 있다. 헤더에 줄바꿈이
      // 들어가면 fetch 가 예외를 던져 500 이 된다.
      'x-goog-api-key': env.GEMINI_API_KEY.trim(),
    },
    body,
  });
  return new Response(await res.text(), {
    status: res.status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** 남은 횟수를 헤더로 실어 준다. 앱은 이걸 읽어 "오늘 3회 남음" 을 보여준다. */
function withQuota(response, usage, limit, plan) {
  const headers = new Headers(response.headers);
  headers.set('x-plan', plan);
  if (usage) {
    headers.set('x-quota-remaining', String(usage.remaining));
    headers.set('x-quota-limit', String(limit));
  }
  return new Response(response.body, { status: response.status, headers });
}

/**
 * 구독자인가.
 *
 * 토큰이 없거나 서비스 계정이 설정돼 있지 않으면 무료다. 확인 자체가 실패하면(구글
 * 장애, 인증 오류) 그 순간만 무료로 보고 **캐시하지 않는다** — 돈 낸 사람을 한 시간
 * 동안 잘못 막는 것보다, 다음 요청에서 다시 물어보는 편이 낫다.
 */
async function isSubscriber(env, purchaseToken, fetchImpl, nowMs) {
  if (!purchaseToken || !env.PLAY_SERVICE_ACCOUNT || !env.PLAY_PACKAGE) return false;

  const key = 'play:' + (await sha256Hex(purchaseToken));
  const cached = await cacheGet(env.DB, key, nowMs);
  if (cached) return cached.active === true;

  let verdict;
  try {
    const accessToken = await playAccessToken(env, fetchImpl, nowMs);
    verdict = await verifySubscription({
      pkg: env.PLAY_PACKAGE,
      purchaseToken,
      accessToken,
      fetchImpl,
    });
  } catch {
    return false;
  }
  await cacheSet(env.DB, key, { active: verdict.active, state: verdict.state }, VERDICT_TTL_MS, nowMs);
  return verdict.active;
}

async function playAccessToken(env, fetchImpl, nowMs) {
  const cached = await cacheGet(env.DB, 'play:access', nowMs);
  if (cached?.token) return cached.token;

  const account = JSON.parse(env.PLAY_SERVICE_ACCOUNT);
  const { token, expiresInSec } = await fetchAccessToken(account, fetchImpl, Math.floor(nowMs / 1000));
  const ttlMs = Math.max(60, expiresInSec - ACCESS_TOKEN_MARGIN_SEC) * 1000;
  await cacheSet(env.DB, 'play:access', { token }, ttlMs, nowMs);
  return token;
}

// --- 사용량 ---------------------------------------------------------------------

async function used(db, id, day) {
  const row = await db.prepare('SELECT used FROM usage WHERE id = ? AND day = ?').bind(id, day).first();
  return row?.used ?? 0;
}

async function bump(db, id, day) {
  await db
    .prepare('INSERT INTO usage (id, day, used) VALUES (?, ?, 1) ON CONFLICT(id, day) DO UPDATE SET used = used + 1')
    .bind(id, day)
    .run();
}

/** 이틀 지난 사용량과 만료된 캐시를 치운다. 크론이 하루 한 번 부른다. */
async function sweep(env) {
  const nowMs = Date.now();
  await env.DB.prepare('DELETE FROM usage WHERE day < ?').bind(kstDay(nowMs - 2 * 86_400_000)).run();
  await env.DB.prepare('DELETE FROM cache WHERE expires_at < ?').bind(nowMs).run();
}

async function ipBucket(request) {
  const ip = request.headers.get('cf-connecting-ip') ?? 'unknown';
  return 'ip:' + (await sha256Hex(ip)).slice(0, 32);
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

// --- 응답 -----------------------------------------------------------------------

function json(status, value, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...extraHeaders },
  });
}

/** 구글 오류와 같은 모양(`error.message`)으로 돌려준다. 앱이 그 자리를 읽어 문구를 만든다. */
function fail(status, code) {
  return json(status, { error: { code: status, message: code, status: code.toUpperCase() } });
}
