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

/**
 * 구글로 나가는 요청을 보내는 자리.
 *
 * Worker 는 사용자와 가까운 데이터센터에서 돈다. 한국 통신사에서 온 요청은 서울이 아니라
 * 홍콩 센터에 붙는 일이 잦은데, 구글은 홍콩 IP 에 "User location is not supported" 로
 * 거절한다 (실기기 진단으로 확인, 2026-09). 미국 CI 에서는 같은 요청이 멀쩡히 됐다.
 * 그래서 구글 호출만 미국에 붙박이인 Durable Object 안에서 한다 — 위치 힌트는 객체를
 * 처음 만들 때 한 번 먹고, 그 뒤로는 그 자리에 머문다.
 */
const RELAY_NAME = 'google-relay';
const RELAY_LOCATION = 'enam';

/** 교정 창이 앞뒤 2000 자에 지시문 1KB 라 16KB 남짓이다. 그 몇 배면 충분하다. */
const MAX_BODY_BYTES = 64 * 1024;

/** Play 확인 결과를 들고 있는 시간. 해지해도 이만큼은 더 쓸 수 있는 셈이다. */
const VERDICT_TTL_MS = 60 * 60 * 1000;

const ACCESS_TOKEN_MARGIN_SEC = 300;

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (_event, env) => sweep(env),
};

/**
 * 구글 호출을 실제로 하는 Durable Object. 하는 일은 키를 붙여 그대로 넘기는 것뿐이다.
 * 키는 여기서 붙인다 — Worker 와 이 객체 사이에도 키가 오갈 이유가 없다.
 */
export class GoogleRelay {
  constructor(_state, env) {
    this.env = env;
  }

  async fetch(request) {
    const body = request.method === 'GET' ? null : await request.text();
    const res = await fetch(request.url, {
      method: request.method,
      headers: googleHeaders(this.env),
      body,
    });
    return passthrough(res);
  }
}

/** 테스트에서 fetch 와 시계를 갈아 끼울 수 있게 진입점을 따로 둔다. */
export async function handle(request, env, deps = {}) {
  const fetchImpl = deps.fetch ?? fetch;
  const now = deps.now ?? (() => Date.now());
  const url = new URL(request.url);

  if (url.pathname === '/health') return json(200, { ok: true });
  // Play 는 키보드 앱에 개인정보 처리방침 주소를 요구한다. 따로 호스팅할 데가 없어 여기서 낸다.
  if (request.method === 'GET' && url.pathname === '/privacy') return privacyPage(env);
  if (!env.GEMINI_API_KEY) return fail(503, 'server_not_configured');

  // 모델 목록은 돈이 안 든다. 한도 없이 그대로 넘긴다.
  if (request.method === 'GET' && url.pathname === '/v1beta/models') {
    return proxy(fetchImpl, env, url, 'GET', null);
  }
  // 날짜별 토큰 사용량. 개인 정보는 없고 합계뿐이라 열어 둔다 — 요금이 얼마나 나가는지
  // 구글 콘솔을 안 열고도 보려고. 숙고 토큰(thoughts)이 따로 찍히니 그게 새는지도 보인다.
  if (request.method === 'GET' && url.pathname === '/stats') {
    return json(200, { days: await tokenStats(env.DB) });
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

  const reply = await relay(fetchImpl, env, url, 'POST', body);

  if (reply.status === 200) {
    // 구글이 알려 준 토큰 수를 날짜별로 쌓는다. 실패한 요청은 안 세고 돈도 안 나간다.
    await recordTokens(env.DB, day, usageOf(reply.text));
    // 성공했을 때만 깎는다. 구글이 거절한 요청까지 세면 사용자는 아무것도 못 받고
    // 하루치만 잃는다 — 앱이 예전에 지키던 규칙과 같다.
    if (!subscriber) {
      await Promise.all([bump(env.DB, installId, day), bump(env.DB, ipKey, day)]);
      usage = { remaining: Math.max(0, usage.remaining - 1) };
    }
  }
  return withQuota(asResponse(reply), usage, limit, plan);
}

/**
 * 구글로 그대로 넘긴다. 클라이언트 헤더는 하나도 넘기지 않는다 — 설치 ID 나 구매
 * 토큰이 구글로 새어 나갈 이유가 없고, 우리 키만 붙이면 된다.
 */
async function proxy(fetchImpl, env, url, method, body) {
  return asResponse(await relay(fetchImpl, env, url, 'GET' === method ? 'GET' : method, body));
}

/** 구글에 보내고 상태와 본문 문자열만 받는다. 본문을 읽어야 토큰 수를 셀 수 있다. */
async function relay(fetchImpl, env, url, method, body) {
  const target = UPSTREAM + url.pathname + url.search;
  let res;
  if (env.RELAY) {
    const stub = env.RELAY.get(env.RELAY.idFromName(RELAY_NAME), { locationHint: RELAY_LOCATION });
    res = await stub.fetch(target, { method, body });
  } else {
    // 중계 객체가 없는 환경(테스트)에서는 여기서 바로 보낸다.
    res = await fetchImpl(target, { method, headers: googleHeaders(env), body });
  }
  return { status: res.status, text: await res.text() };
}

function asResponse(reply) {
  return new Response(reply.text, {
    status: reply.status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** 구글 응답의 usageMetadata. 없거나 깨졌으면 0 으로. */
function usageOf(text) {
  try {
    const meta = JSON.parse(text)?.usageMetadata ?? {};
    return {
      prompt: Number(meta.promptTokenCount ?? 0),
      output: Number(meta.candidatesTokenCount ?? 0),
      thoughts: Number(meta.thoughtsTokenCount ?? 0),
    };
  } catch {
    return { prompt: 0, output: 0, thoughts: 0 };
  }
}

async function recordTokens(db, day, usage) {
  await db
    .prepare(
      'INSERT INTO tokens (day, requests, prompt, output, thoughts) VALUES (?, 1, ?, ?, ?) ' +
        'ON CONFLICT(day) DO UPDATE SET requests = requests + 1, prompt = prompt + excluded.prompt, ' +
        'output = output + excluded.output, thoughts = thoughts + excluded.thoughts'
    )
    .bind(day, usage.prompt, usage.output, usage.thoughts)
    .run();
}

async function tokenStats(db) {
  const { results } = await db
    .prepare('SELECT day, requests, prompt, output, thoughts FROM tokens ORDER BY day DESC LIMIT 31')
    .all();
  return results ?? [];
}

function googleHeaders(env) {
  return {
    'content-type': 'application/json; charset=utf-8',
    // 사람이 붙여넣은 비밀값이라 끝에 줄바꿈이 딸려 온 적이 있다. 헤더에 줄바꿈이
    // 들어가면 fetch 가 예외를 던져 500 이 된다.
    'x-goog-api-key': env.GEMINI_API_KEY.trim(),
  };
}

/** 구글 응답의 상태와 본문만 넘긴다. 구글 쪽 헤더는 우리 것과 섞이지 않게 버린다. */
async function passthrough(res) {
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
  await env.DB.prepare('DELETE FROM tokens WHERE day < ?').bind(kstDay(nowMs - 90 * 86_400_000)).run();
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

/** 개인정보 처리방침. 앱이 실제로 하는 것만 적는다 — 과장도, 누락도 없이. */
function privacyPage(env) {
  const contact = env.CONTACT_EMAIL ? `<p>문의: ${escapeHtml(env.CONTACT_EMAIL)}</p>` : '';
  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>맞춤법 키보드 개인정보 처리방침</title>
<style>body{font-family:sans-serif;max-width:680px;margin:40px auto;padding:0 20px;line-height:1.7;color:#191f28}h1{font-size:22px}h2{font-size:17px;margin-top:28px}</style>
</head><body>
<h1>맞춤법 키보드 개인정보 처리방침</h1>
<p>맞춤법 키보드는 타이핑하는 글을 기기 안에서 고쳐 주는 안드로이드 키보드입니다. 이 문서는 앱이 어떤 정보를 어디까지 다루는지 설명합니다.</p>
<h2>1. 타이핑한 글</h2>
<p>실시간 맞춤법·띄어쓰기 교정은 <strong>전부 기기 안에서</strong> 처리됩니다. 타이핑한 글은 어디로도 전송되거나 저장되지 않습니다. 비밀번호·이메일·URL 입력란에서는 교정이 자동으로 꺼집니다.</p>
<h2>2. AI 전체 교정</h2>
<p>키보드 위 ✦ 버튼을 <strong>직접 누를 때만</strong>, 그 입력란의 글이 앱 서버를 거쳐 Google Gemini API 로 전송되어 교정된 결과가 돌아옵니다. 서버는 글을 저장하지 않으며, 교정 요청 횟수만 셉니다. Google 의 처리에 대해서는 Google 의 개인정보 처리방침이 적용됩니다.</p>
<h2>3. 서버가 보관하는 것</h2>
<ul>
<li><strong>설치 식별자</strong>: 앱을 설치할 때 만들어지는 무작위 값입니다. 무료 사용 횟수를 세는 데만 쓰이며, 사용자 계정이나 기기 정보와 연결되지 않습니다.</li>
<li><strong>일별 사용 횟수</strong>: 설치 식별자·접속 IP 별 하루 요청 수. 이틀 뒤 삭제됩니다.</li>
<li><strong>구독 확인</strong>: 구독한 경우 Google Play 구매 토큰을 Google Play 에 조회해 구독 상태만 확인합니다. 결제 정보는 Google Play 가 처리하며 앱과 서버는 카드 정보 등을 다루지 않습니다.</li>
</ul>
<h2>4. 기기에만 저장되는 것</h2>
<p>클립보드 기록, 배경 사진, 테마·자판 설정은 기기 안에만 저장되며 앱을 삭제하면 함께 사라집니다. 클립보드에서 "민감" 으로 표시된 내용(비밀번호 등)은 기록하지 않습니다.</p>
<h2>5. 권한</h2>
<p>인터넷 권한은 AI 전체 교정과 구독 확인에만 쓰입니다. 그 외 권한은 요구하지 않습니다.</p>
<h2>6. 변경</h2>
<p>이 방침이 바뀌면 이 페이지에 갱신합니다.</p>
${contact}
</body></html>`;
  return new Response(html, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

