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
import { kstDay, validInstallId, chargeFor, settleChars, holdChars, DEFAULT_SUB_DAILY_CHARS } from './quota.js';
import {
  accountForGoogle,
  attachPurchase,
  deviceCount,
  issueDevice,
  purchaseForDevice,
  purchaseOfAccount,
  revokeDevice,
  sha256Hex,
  validDeviceToken,
  deleteAccount,
} from './account.js';
import { allowedClientIds, fetchJwks, verifyIdToken } from './google.js';
import {
  fetchAccessToken,
  verifySubscription,
  acknowledgeSubscription,
  DEFAULT_PRODUCT_ID,
  CANCELED_STATE,
} from './play.js';
import { cacheGet, cacheSet } from './cache.js';
import {
  geminiUrl,
  toGeminiRequest,
  fromGeminiReply,
  DEFAULT_GEMINI_MODEL,
  GEMINI_MODELS_URL,
  GEMINI_UPSTREAM,
  parseGeminiModels,
  pickGeminiModel,
} from './gemini.js';
import {
  OPENAI_URL,
  DEFAULT_OPENAI_MODEL,
  toOpenAiRequest,
  toGeminiReply,
  modelList,
  userTextOf,
  withoutReasoning,
  rejectsReasoning,
  utf8Bytes,
  TRANSLATE_TARGETS,
  hangulFragments,
  billedError,
  DEFAULT_TRANSLATE_PROMPT,
} from './openai.js';
import {
  ANTHROPIC_URL,
  ANTHROPIC_VERSION,
  DEFAULT_CLAUDE_MODEL,
  toClaudeRequest,
  fromClaudeReply,
} from './anthropic.js';
import { UPSTAGE_URL, DEFAULT_UPSTAGE_MODEL, toUpstageRequest } from './upstage.js';

/**
 * 바깥으로 나가는 요청을 보내는 자리.
 *
 * Worker 는 사용자와 가까운 데이터센터에서 돈다. 한국 통신사에서 온 요청은 서울이 아니라
 * 홍콩 센터에 붙는 일이 잦은데, 구글은 홍콩 IP 에 "User location is not supported" 로
 * 거절한다 (실기기 진단으로 확인, 2026-09). 미국 CI 에서는 같은 요청이 멀쩡히 됐다.
 * OpenAI 도 홍콩을 지원 지역에서 빼 놓았으니 사정이 같다. 그래서 바깥 호출은 전부
 * 미국에 붙박이인 Durable Object 안에서 한다 — 위치 힌트는 객체를 처음 만들 때 한 번
 * 먹고, 그 뒤로는 그 자리에 머문다.
 */
//
// 어느 미국이냐는 대기 시간에 그대로 붙는다. 우리 사용자는 한국에 있고, 요청은
// 폰 → 서울 엣지 → 이 객체 → 모델 → 되돌아오기로 태평양을 두 번 건넌다.
// 미국 동부(enam)는 서울에서 가장 먼 축이라 서부(wnam)로 옮긴다 — 구글도 OpenAI 도
// 지원하는 지역이라 위의 홍콩 문제는 그대로 피한다.
//
// **이름도 같이 바꿔야 한다.** 위치 힌트는 객체를 처음 만들 때만 먹는다. 이름이 같으면
// 이미 미국 동부에 만들어진 그 객체를 계속 쓰고, 이 값만 고치면 아무 일도 안 일어난다.
// 이름이 바뀌면 새 객체가 새 자리에 생긴다. 이 객체는 저장하는 것이 없어서(키를 붙여
// 넘기기만 한다) 버리고 새로 만들어도 잃을 상태가 없다.
const RELAY_NAME = 'relay-wnam';
const RELAY_LOCATION = 'wnam';

/** 교정 창이 앞뒤 2000 자에 지시문 1KB 라 16KB 남짓이다. 그 몇 배면 충분하다. */
const MAX_BODY_BYTES = 64 * 1024;

/** Play 확인 결과를 들고 있는 시간. 해지해도 이만큼은 더 쓸 수 있는 셈이다. */
const VERDICT_TTL_MS = 60 * 60 * 1000;

const ACCESS_TOKEN_MARGIN_SEC = 300;

/** 구글에 "지금 무슨 모델 있냐" 고 다시 묻는 주기. */
const MODEL_TTL_MS = 24 * 60 * 60 * 1000;

/**
 * 고른 모델을 넣어 두는 자리. **고르는 규칙을 바꾸면 이 이름의 끝 번호를 올려라.**
 *
 * 안 올리면 배포해도 하루 동안 예전 규칙으로 고른 이름이 그대로 나간다. 실제로
 * 규칙을 되돌려 배포하고도 로그에 옛 모델이 찍혀서 한 번 헷갈렸다. 이름을 바꾸면
 * 옛 줄은 아무도 안 읽고 남았다가 크론이 치운다.
 */
const MODEL_KEY = 'gemini:model:v3';

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (_event, env) => sweep(env),
};

/**
 * 바깥 호출을 실제로 하는 Durable Object. 하는 일은 키를 붙여 그대로 넘기는 것뿐이다.
 * 키는 여기서 붙인다 — Worker 와 이 객체 사이에도 키가 오갈 이유가 없다.
 *
 * 이름이 GoogleRelay 인 것은 처음 만들 때 구글만 있었기 때문이다. 클래스 이름을 바꾸면
 * Durable Object 이전(migration)을 해야 하고 그 사이 배포가 깨진다. 이름값보다 안전이 낫다.
 */
export class GoogleRelay {
  constructor(_state, env) {
    this.env = env;
  }

  async fetch(request) {
    const body = request.method === 'GET' ? null : await request.text();
    const res = await fetch(request.url, {
      method: request.method,
      headers: upstreamHeaders(this.env, request.url),
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

  // 어느 AI 로 보내는지도 싣는다. 비밀이 아니고(방침 페이지에도 적힌다), 배포 기록에서
  // "OpenAI 키가 남아 있어 그쪽으로 새는가" 를 값 한 푼 안 들이고 확인하는 자리다.
  if (url.pathname === '/health') {
    const translate = translateProvider(env);
    return json(200, translate === provider(env) ? { ok: true, provider: provider(env) } : { ok: true, provider: provider(env), translate });
  }
  // Play 는 키보드 앱에 개인정보 처리방침 주소를 요구한다. 따로 호스팅할 데가 없어 여기서 낸다.
  if (request.method === 'GET' && url.pathname === '/privacy') return privacyPage(env);
  if (request.method === 'GET' && url.pathname === '/terms') return termsPage(env);
  // 지금 쓰기로 한 쪽의 키가 있어야 한다. 둘 중 아무거나 있으면 되는 게 아니다 —
  // 구글로 돌려 놓고 구글 키가 없으면 교정마다 401 을 받으면서 이유는 안 보인다.
  if (!env[providerKeyName(env)]) {
    return fail(503, 'server_not_configured');
  }

  // 모델 목록은 돈이 안 든다. 어느 쪽이든 **서버가 모델을 정하므로** 물어볼 것 없이
  // 지금 쓰는 이름 하나만 알려 준다. 앱은 이 목록으로 "쓸 수 있는 이름" 을 판단한다.
  if (request.method === 'GET' && url.pathname === '/v1beta/models') {
    const name =
      provider(env) === 'openai'
        ? openAiModel(env)
        : provider(env) === 'anthropic'
          ? claudeModel(env)
          : provider(env) === 'upstage'
            ? upstageModel(env)
            : await resolveGeminiModel(env, fetchImpl, now());
    return json(200, modelList(name));
  }
  // 날짜별 토큰 사용량. 개인 정보는 없고 합계뿐이라 열어 둔다 — 요금이 얼마나 나가는지
  // 구글 콘솔을 안 열고도 보려고. 숙고 토큰(thoughts)이 따로 찍히니 그게 새는지도 보인다.
  if (request.method === 'GET' && url.pathname === '/stats') {
    // 로그인 없이 하루 사용량(합계)을 보여 주던 자리다. 개인 정보는 없지만 서비스 규모가 드러나므로
    // **기본으로 숨긴다.** `STATS_TOKEN` 비밀값을 넣고 `?token=` 이 맞을 때만 연다. 없으면 404 —
    // 틀렸다고 알려 주면 있다는 것만 들키므로 없는 길인 척한다.
    const token = env.STATS_TOKEN;
    if (!token || url.searchParams.get('token') !== token) return fail(404, 'not_found');
    return json(200, { days: await tokenStats(env.DB) });
  }
  // 계정 쪽 길들. 돈이 안 드는 길이라 한도도 안 깎는다.
  if (request.method === 'POST' && url.pathname === '/v1/account/signin') {
    return signIn(request, env, fetchImpl, now);
  }
  if (request.method === 'POST' && url.pathname === '/v1/account/signout') {
    return signOut(request, env);
  }
  if (request.method === 'POST' && url.pathname === '/v1/account/subscription') {
    return subscriptionOf(request, env, fetchImpl, now);
  }
  if (request.method === 'POST' && url.pathname === '/v1/account/attach') {
    return attachPurchaseOf(request, env, fetchImpl, now);
  }
  if (request.method === 'POST' && url.pathname === '/v1/account/delete') {
    return deleteAccountOf(request, env, now);
  }
  const isGenerate = /^\/v1beta\/models\/[^/]+:generateContent$/.test(url.pathname);
  if (request.method === 'POST' && isGenerate) {
    return correct(request, env, url, fetchImpl, now);
  }
  return fail(404, 'not_found');
}

/**
 * 구글 로그인으로 들어와 **기기 토큰**을 받아 간다. 계정 쪽의 유일한 입구다.
 *
 * 안드로이드는 구매 토큰을 같이 보낸다 — 그러면 그 구매가 이 계정에 붙는다.
 * 윈도우·아이폰은 로그인만 한다 — 이미 붙어 있는 구매를 찾아 쓴다.
 *
 * 돌려주는 것은 기기 토큰과 지금 구독 상태뿐이다. **구매 토큰은 절대 안 내보낸다.**
 */
async function signIn(request, env, fetchImpl, now) {
  const nowMs = now();
  const clientIds = allowedClientIds(env);
  if (clientIds.length === 0) return fail(503, 'google_not_configured');

  let body;
  try {
    body = await request.json();
  } catch {
    return fail(400, 'bad_request');
  }

  let keys;
  try {
    keys = await fetchJwks({ cacheGet, cacheSet, db: env.DB, fetchImpl, nowMs });
  } catch {
    return fail(503, 'google_unreachable');
  }

  const subHash = await verifyIdToken({ idToken: body?.idToken, clientIds, keys, nowMs });
  // 왜 틀렸는지는 알려 주지 않는다 — 찍어 보는 쪽에 단서를 주지 않으려고.
  if (!subHash) return fail(401, 'bad_id_token');

  const accountId = await accountForGoogle(env.DB, subHash, nowMs);

  // 구매를 들고 왔으면 Play 에 확인한 뒤에만 붙인다.
  //
  // **이건 보안 경계가 아니다.** 접근을 막는 것은 교정할 때마다 다시 하는 확인이라,
  // 엉터리 토큰이 붙어 있어도 구독자가 되지는 않는다. 여기서 거르는 이유는 둘이다 —
  // 쓰레기를 표에 안 쌓으려고, 그리고 붙이기가 **옛 계정에서 떼어 오는 동작**이라
  // 살아 있지도 않은 토큰으로 남의 자리를 흔들 여지를 아예 없애려고.
  const purchaseToken = typeof body?.purchaseToken === 'string' ? body.purchaseToken : '';
  // Play 가 고장이면 붙이지 않는다(확인 못 한 구매는 안 붙인다). 로그인 자체는 막지 않는다.
  if (purchaseToken && (await isSubscriberOrFalse(env, purchaseToken, fetchImpl, nowMs))) {
    await attachPurchase(env.DB, accountId, purchaseToken, nowMs);
  }

  // 기기를 무한정 붙이게 두면 로그인 하나로 여럿이 나눠 쓴다. 한도는 어차피 구매
  // 하나에 하나라 손해는 안 나지만, 쓸 일 없는 기기가 쌓이는 것을 막는다.
  if ((await deviceCount(env.DB, accountId)) >= MAX_DEVICES) return fail(409, 'too_many_devices');

  const deviceToken = await issueDevice(env.DB, accountId, labelOf(body?.label), nowMs);
  // 기기 토큰은 이미 발급했다 — 여기서 503 을 주면 그 토큰을 잃는다. Play 가 고장이고 기댈 기록도 없으면 'unknown' 으로
  // 알린다. 'free' 라고 하면 장애 중에 처음 로그인한 PC 가 다음 확인까지 '구독 없음' 으로 보였다(2026-10-10 윈도우 세션 보고).
  // 기기는 나중에 /subscription 으로 다시 묻는다. 안드로이드는 'subscriber' 만 구독자로 보므로 'unknown' 도 무료와 같이 다룬다.
  let plan;
  try {
    plan = (await isSubscriber(env, await purchaseOfAccount(env.DB, accountId), fetchImpl, nowMs)) ? 'subscriber' : 'free';
  } catch (err) {
    if (!(err instanceof PlayUnavailable)) throw err;
    plan = 'unknown';
  }
  return json(200, { deviceToken, plan });
}

/** 기기가 스스로 연결을 끊는다. 사용자가 "이 기기 빼기" 를 눌렀을 때. */
async function signOut(request, env) {
  const device = request.headers.get('x-device-token') ?? '';
  if (!validDeviceToken(device)) return fail(400, 'invalid_device_token');
  await revokeDevice(env.DB, device);
  return json(200, { ok: true });
}

/**
 * 이미 로그인한 폰이 **나중에** 산 구독을 계정에 붙인다.
 *
 * 로그인할 때(`signIn`)만 구매를 붙였더니 "로그인 먼저, 구독 나중" 순서에서 구매가 계정에 안
 * 붙었다 — PC 에서는 구독이 없는 걸로 보이고, 로그아웃했다 다시 들어와야 붙었다. 앱은 구매를
 * 받자마자(그리고 켤 때마다, 아직 안 붙였으면) 여기로 보낸다.
 *
 * 붙이기 전에 Play 에 살아 있는지 확인한다. 이유는 `signIn` 과 같다 — 보안 경계는 교정할 때마다
 * 하는 확인이지만, 붙이기가 옛 계정에서 떼어 오는 동작이라 죽은 토큰으로 남의 자리를 흔들 여지를
 * 없앤다.
 */
async function attachPurchaseOf(request, env, fetchImpl, now) {
  const device = request.headers.get('x-device-token') ?? '';
  if (!validDeviceToken(device)) return fail(400, 'invalid_device_token');

  let body;
  try {
    body = await request.json();
  } catch {
    return fail(400, 'bad_request');
  }
  const purchaseToken = typeof body?.purchaseToken === 'string' ? body.purchaseToken : '';
  if (!purchaseToken) return fail(400, 'bad_request');

  const nowMs = now();
  const found = await purchaseForDevice(env.DB, device, nowMs);
  if (!found) return fail(404, 'device_not_linked');

  let active;
  try {
    active = await isSubscriber(env, purchaseToken, fetchImpl, nowMs);
  } catch (err) {
    // Play 고장. "구독 아님" 이라고 하면 앱이 붙이기를 포기한다 — 다음에 다시 하게 503 으로.
    if (err instanceof PlayUnavailable) return fail(503, 'subscription_check_unavailable');
    throw err;
  }
  if (!active) return fail(402, 'not_subscribed');
  await attachPurchase(env.DB, found.accountId, purchaseToken, nowMs);
  return json(200, { plan: 'subscriber' });
}

/**
 * 회원 탈퇴. 이 기기가 붙은 계정과, 그 계정에 붙은 **모든** 기기를 지운다.
 *
 * 기기 토큰 하나로 된다 — 그 토큰이 곧 이 계정에 로그인한 증거다. 되묻는 것은 앱이
 * 한다(확인 창). 구독은 Play 에 있으므로 여기서 해지되지 않는다([deleteAccount] 참고).
 */
async function deleteAccountOf(request, env, now) {
  const device = request.headers.get('x-device-token') ?? '';
  if (!validDeviceToken(device)) return fail(400, 'invalid_device_token');
  const found = await purchaseForDevice(env.DB, device, now());
  if (!found) return fail(404, 'device_not_linked');
  await deleteAccount(env.DB, found.accountId);
  return json(200, { ok: true });
}

/** 비밀값·설정이 "켜짐" 인가. 빈 값·0·false·off 는 꺼짐, 그 밖의 값은 켜짐으로 본다. */
function isOn(value) {
  const v = String(value ?? '').trim().toLowerCase();
  return v !== '' && v !== '0' && v !== 'false' && v !== 'off' && v !== 'no';
}

/** 기기가 붙인 이름. 사용자가 보낸 글자라 길이를 자르고 그대로는 안 믿는다. */
function labelOf(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim().slice(0, 40);
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * 이 요청이 어느 구매에 딸린 것인가.
 *
 * 스토어가 있는 기기는 구매 토큰을 그대로 싣는다. 스토어가 없는 기기(윈도우)나 다른
 * 스토어를 쓰는 기기(아이폰)는 서버가 발급한 기기 토큰을 싣고, 그것을 계정을 거쳐
 * 구매 토큰으로 푼다.
 *
 * **구매 토큰 쪽이 먼저다.** 둘 다 실려 오면 스토어에 직접 물어볼 수 있는 쪽을 믿는다.
 */
async function identify(env, request, nowMs) {
  const direct = request.headers.get('x-purchase-token') ?? '';
  if (direct) return direct;

  const device = request.headers.get('x-device-token') ?? '';
  if (!validDeviceToken(device)) return '';
  const found = await purchaseForDevice(env.DB, device, nowMs);
  return found?.purchaseToken ?? '';
}

/**
 * 기기 토큰 하나로 "지금 구독 중인가" 만 답한다.
 *
 * 윈도우·아이폰이 켤 때 한 번 물어 화면을 정하는 데 쓴다. **구매 토큰은 절대 내보내지
 * 않는다** — 그 자체가 구독 증명이라, 내려보내는 순간 그게 새는 통로가 된다.
 */
async function subscriptionOf(request, env, fetchImpl, now) {
  const device = request.headers.get('x-device-token') ?? '';
  if (!validDeviceToken(device)) return fail(400, 'invalid_device_token');

  const nowMs = now();
  const found = await purchaseForDevice(env.DB, device, nowMs);
  if (!found) return fail(404, 'device_not_linked');

  let active;
  try {
    active = await isSubscriber(env, found.purchaseToken, fetchImpl, nowMs);
  } catch (err) {
    // Play 고장이고 기댈 기록도 없다. "무료" 라고 하면 PC 가 구독자를 무료 화면으로 바꾼다 — 모른다고 한다.
    if (err instanceof PlayUnavailable) return fail(503, 'subscription_check_unavailable');
    throw err;
  }
  return json(200, { plan: active ? 'subscriber' : 'free' });
}

/** 계정 하나에 붙일 수 있는 기기 수. 로그인 하나로 여럿이 나눠 쓰는 것을 막는다. */
const MAX_DEVICES = 5;

/**
 * 분당 요청 상한. 분 버킷으로 센다 — usage 표를 재사용하되 id 앞가지(`rl:`)로 글자 한도(`chars:`)와
 * 안 섞인다. sweep 이 이틀 지난 버킷을 치운다.
 *
 * - IP: 가짜 구매 토큰을 매번 바꿔 Play·D1 을 두드리는 것을 막는다(⑤). CGNAT 를 감안해 넉넉히.
 * - 구독자: 한도가 남아 있어도 1분에 몰아치지 못하게 하는 버스트 천장(④).
 */
const IP_REQUESTS_PER_MIN = 60;
const SUB_REQUESTS_PER_MIN = 20;

/** 분 단위 버킷 문자열 'YYYY-MM-DDTHH:MM'. */
function minuteBucket(nowMs) {
  return new Date(nowMs).toISOString().slice(0, 16);
}

/** 이 분 버킷에서 [id] 를 1 올리고 올린 뒤의 값을 돌려준다. 넘으면 호출한 쪽이 429 로 막는다. */
async function hitRate(db, id, nowMs) {
  return reserve(db, 'rl:' + id, minuteBucket(nowMs), 1);
}

async function correct(request, env, url, fetchImpl, now) {
  // 비상 정지. `AI_DISABLED` 비밀값을 넣으면 AI 교정·번역이 통째로 멈춘다 — 값이 샜거나 요금이
  // 터질 때 배포를 기다리지 않고 한 번에 끈다(비밀값 하나로 즉시, 코드 배포 불필요). 온디바이스
  // 교정은 서버를 안 거치므로 그대로 돈다. 402 라 앱은 바로 멈추고 다른 모델로 되쏘지 않는다.
  if (isOn(env.AI_DISABLED)) return fail(402, 'ai_unavailable');

  const installId = request.headers.get('x-install-id') ?? '';
  if (!validInstallId(installId)) return fail(400, 'invalid_install_id');

  if (Number(request.headers.get('content-length') ?? 0) > MAX_BODY_BYTES) {
    return fail(413, 'too_long');
  }
  const body = await request.text();
  // 바이트로 잰다 — `body.length` 는 글자 수라, 한글은 글자당 3바이트라서 그걸로 재면 상한의 세 배까지
  // 새어 든다(실측: 글자 수로 6만이 바이트로 18만이었다). content-length 는 없거나 거짓일 수 있어 믿지 않는다.
  if (new TextEncoder().encode(body).length > MAX_BODY_BYTES) return fail(413, 'too_long');

  // ?translate=en 이면 교정이 아니라 번역이다. 한도는 교정과 같은 통을 쓴다 —
  // 값이 글자당 똑같이 매겨지므로 따로 셀 이유가 없다.
  const translateTo = url.searchParams.get('translate');
  if (translateTo && !TRANSLATE_TARGETS[translateTo]) return fail(400, 'unknown_language');

  const nowMs = now();
  const day = kstDay(nowMs);

  // **IP 당 분당 상한.** 처음 보는 구매 토큰마다 Play 확인과 D1 쓰기가 생긴다. 구매 토큰을 매번
  // 바꿔 보내면 로그인 없이도 Play 확인 한도를 바닥낼 수 있다(⑤). Play 를 두드리기 **전에** 막는다.
  // cf-connecting-ip 는 Cloudflare 가 끝단에서 박으므로 앱이 위조하지 못한다.
  // IP 는 그대로 저장하지 않고 해시로 바꿔 센다(방침 3번). 분 버킷이라 이틀 뒤 sweep 이 지운다.
  const ip = request.headers.get('cf-connecting-ip') ?? '';
  if (ip && (await hitRate(env.DB, 'ip:' + (await sha256Hex('ip:' + ip)), nowMs)) > IP_REQUESTS_PER_MIN) {
    return fail(429, 'rate_limited');
  }

  // 구매 토큰은 **스토어가 있는 기기**(안드로이드)만 들고 있다. 윈도우에는 스토어가
  // 아예 없고 아이폰은 스토어가 달라서, 그쪽은 서버가 발급한 기기 토큰을 들고 온다.
  // 기기 토큰은 계정을 거쳐 **같은 구매 토큰**으로 풀리므로, 어디서 쓰든 한도는 하나다.
  const purchaseToken = await identify(env, request, nowMs);
  // 시험 ID 는 Play 를 안 거치고 구독자로 친다. 그때 같이 실려 온 구매 토큰은 **확인한 적이 없다**.
  const viaTestList = isTestSubscriber(env, installId);
  let subscriber;
  try {
    subscriber = viaTestList || (await isSubscriber(env, purchaseToken, fetchImpl, nowMs));
  } catch (err) {
    // Play 가 고장 났고 예전에 확인한 기록도 없다. "구독자 전용" 이라고 하면 돈 낸 사람에게 거짓말이
    // 된다(윈도우 점검 ⑦④). 잠깐 뒤 다시 하라고 503. **앱은 이 503 을 스스로 다시 보내지 않는다**(PC·폰 core 둘 다,
    // 2026-10-10) — 다시 보내면 Play 만 또 두드린다. 사용자가 다시 누르면 그때 다시 묻는다.
    if (err instanceof PlayUnavailable) return fail(503, 'subscription_check_unavailable');
    throw err;
  }
  const plan = subscriber ? 'subscriber' : 'free';

  const unit = 'chars';
  const limit = subscriber ? Number(env.SUB_DAILY_CHARS ?? DEFAULT_SUB_DAILY_CHARS) : 0;

  // 무료는 AI 를 쓰지 않는다. 실시간 온디바이스 교정은 그대로 무제한이고, 서버로 오는
  // 것만 막는다.
  //
  // 왜 이렇게까지 하냐면: 무료 한 명이 한도를 꽉 채우면 한 달에 468원이 나간다. 받는
  // 돈은 0원이라 **쓰는 사람이 늘수록 손해가 는다**. 100만 명이면 월 4.7억이다. 한도를
  // 낮춰도 방향은 그대로고, 서버 주소는 APK 안에 있어 누구나 두드릴 수 있다. 유일하게
  // 확실한 천장은 "돈 낸 사람만" 이다.
  //
  // 402 를 쓰는 이유: 한도 초과는 다시 보내도 소용이 없다. 앱(core GeminiCorrector)은 바깥 모델이 거절한
  // 것(upstream_unavailable·rate_limited)과 연결 실패만 한 번 더 보내고, 402 는 바로 멈춘다.
  if (!subscriber) {
    return withQuota(fail(402, 'subscribers_only'), { remaining: 0 }, limit, plan, unit);
  }

  // 구독자는 횟수가 아니라 글자 수로 센다 — 요금이 글자 수에 붙기 때문이다.
  //
  // **한도는 결제 하나에 하나다.** 설치 ID 로 세면 앱을 지웠다 깔거나 같은 구글 계정을
  // 여러 폰에 넣는 것만으로 한도가 새로 난다. 그러면 구독 하나로 다섯이 쓸 때 원가는
  // 다섯 배인데 받는 돈은 그대로다 — 한 사람이 꽉 써서 남는 것이 690원뿐이라 둘만
  // 나눠 써도 바로 적자다.
  //
  // 구매 토큰으로 세면 몇 대에 깔든 합쳐서 하루치 하나다. 본인이 폰 두 대에 깔면 둘이
  // 나눠 쓰게 되는데, 그게 맞는 동작이다.
  //
  // 토큰을 그대로 열쇠로 쓰지 않고 해시한다. 열쇠는 로그나 덤프에 섞여 나오기 쉽고,
  // 구매 토큰은 그 자체가 구독 증명이라 새면 남이 쓸 수 있다.
  //
  // **시험 ID 로 들어왔으면 설치 ID 로만 센다(윈도우 점검 ⑥).** 이때 실린 구매 토큰은 Play 에 확인하지
  // 않았다 — 그걸 이름표로 쓰면 토큰을 아무렇게나 지어 바꿔 가며 한도를 매번 새로 받는다.
  const subId = viaTestList ? installId : purchaseToken ? await sha256Hex(purchaseToken) : installId;

  // **구독자 하나가 분당 너무 많이 보내는 것도 막는다(④).** 글자 한도는 하루치 천장이고,
  // 이건 버스트 천장이다 — 한도가 아직 남아 있어도 1분에 수십 번씩 몰아치지 못하게 한다.
  if ((await hitRate(env.DB, 'sub:' + subId, nowMs)) > SUB_REQUESTS_PER_MIN) {
    return withQuota(fail(429, 'rate_limited'), { remaining: Math.max(0, limit) }, limit, plan, unit);
  }

  const charsKey = 'chars:' + subId;
  const charge = chargeFor(safeUserLength(body));

  // 보낼 요청을 먼저 짓는다 — 한도를 미리 잡으려면 보낼 바이트와 출력 상한을 알아야 한다.
  const call = await buildAiCall(env, fetchImpl, body, translateTo, nowMs);
  if (call.reply) {
    // 지을 수 없는 요청(고칠 글이 없음 등). 값이 안 나가므로 한도는 건드리지 않고 남은 양만 알려 준다.
    const current = await reserve(env.DB, charsKey, day, 0);
    return withQuota(asResponse(call.reply), { remaining: Math.max(0, limit - current) }, limit, plan, unit);
  }

  // 한도는 **미리 원자적으로**, 그리고 **그 요청이 쓸 수 있는 최악값을 통째로** 잡는다.
  //
  // 원자적이어야 하는 이유: 읽고 나서 더하면 동시에 온 요청이 전부 같은 "남았다" 를 보고 통과한다 — 1만 5천 자
  // 한도에 1만 4천 자짜리를 병렬로 50번 보내 하루 70만 자가 나갔다(실측). D1 이 더하기 한 문장을 직렬화하므로
  // 더한 뒤의 값으로 판단한다.
  //
  // 최악값이어야 하는 이유: 값은 실제 토큰으로 정산하는데(아래) 정산은 답이 온 뒤다. 글자 수만 잡고 들여보내면
  // "길게 써라" 를 실은 요청 여럿을 동시에 보내거나 남은 자리 끝에 끼워 넣어 정산 전에 한도를 뚫는다.
  // 그래서 [holdChars] 만큼 통째로 잡고, **들여보낼지는 지금과 똑같이 글자 수로 판단**한다(before + charge).
  const hold = holdChars(charge, call.bytes, call.maxTokens);
  const reserved = await reserve(env.DB, charsKey, day, hold);
  const before = reserved - hold;
  if (before + charge > limit) {
    await refund(env.DB, charsKey, day, hold);
    return withQuota(fail(402, 'sub_daily_limit'), { remaining: Math.max(0, limit - before) }, limit, plan, unit);
  }

  const startedAt = Date.now();
  let reply;
  try {
    reply = await call.send();
  } catch (err) {
    // 바깥에 닿지도 못했으면(통신 끊김 등) 청구가 없다. 잡아 둔 것을 전부 돌려주고 올린다.
    await refund(env.DB, charsKey, day, hold);
    throw err;
  }
  reply.tookMs = Date.now() - startedAt;

  let remaining;
  if (reply.status === 200 || reply.billed === true) {
    // **값이 나갔다**(성공, 교정이 아니라 원문을 돌려준 것, 잘렸다·비었다로 502 를 준 것 모두). 실제 토큰으로
    // 정산한다 — 보통은 글자 수가 이겨 지금과 똑같이 깎이고, 길게 쓰게 시킨 요청만 토큰만큼 더 깎인다.
    // 잡아 둔 것과의 차이만큼만 맞춘다. 남은 양은 정산 뒤의 값이다.
    const tokens = usageOf(reply);
    await recordTokens(env.DB, day, tokens);
    const after = await adjust(env.DB, charsKey, day, settleChars(charge, tokens) - hold);
    remaining = Math.max(0, limit - after);
  } else {
    // 바깥이 거절했다(429·5xx 등) — 청구가 없으니 잡아 둔 것을 전부 돌려준다.
    await refund(env.DB, charsKey, day, hold);
    remaining = Math.max(0, limit - before);
  }
  return withQuota(asResponse(reply), { remaining }, limit, plan, unit);
}

/**
 * 어느 쪽으로 보낼 것인가.
 *
 * `AI_PROVIDER` 가 정하고, 안 적혀 있으면 예전처럼 "OpenAI 키가 있으면 OpenAI" 다.
 *
 * 예전에는 키 존재만으로 갈렸는데, 그러면 구글로 되돌리려고 **멀쩡한 키를 지워야**
 * 했다. 배포 일감은 비밀값을 지우지 않으므로(되묻기라 CI 에서 조용히 실패한다) 사람이
 * 손으로 wrangler 를 쳐야 하고, 되돌릴 때는 키를 다시 붙여넣어야 한다. 어느 모델을
 * 쓰느냐는 설정이지 비밀이 아니다. wrangler.toml 한 줄로 갈리게 한다.
 */
function provider(env) {
  const asked = (env.AI_PROVIDER ?? '').trim().toLowerCase();
  if (asked === 'gemini' || asked === 'google') return 'gemini';
  if (asked === 'openai') return 'openai';
  // Claude. 구글 약관의 18세 조항 때문에 생긴 길이다(anthropic.js 머리말).
  if (asked === 'anthropic' || asked === 'claude') return 'anthropic';
  // 업스테이지 Solar. 같은 이유로 생겼고, 겨루기에서 대체 후보 중 제일 나았다(upstage.js 머리말).
  if (asked === 'upstage' || asked === 'solar') return 'upstage';
  return env.OPENAI_API_KEY ? 'openai' : 'gemini';
}

/** 지금 쓰기로 한 쪽의 키 이름. 그 키가 없으면 서버는 AI 를 503 으로 막는다. */
function providerKeyName(env) {
  return keyNameOf(provider(env));
}

function keyNameOf(p) {
  if (p === 'openai') return 'OPENAI_API_KEY';
  if (p === 'anthropic') return 'ANTHROPIC_API_KEY';
  if (p === 'upstage') return 'UPSTAGE_API_KEY';
  return 'GEMINI_API_KEY';
}

/**
 * **번역만** 다른 업체로 보낼 수 있다 — `TRANSLATE_PROVIDER` (openai | anthropic | upstage | gemini).
 *
 * 비워 두면 교정과 같은 업체다(지금 그렇다). 겨루기(2026-10-09)에서 번역은 업체마다 결과가 꽤 갈렸다 — 교정에서 고른 업체가 번역에서도
 * 제일이라는 법이 없다. 업체를 갈아 끼우려고 교정까지 흔들지 않게 따로 열어 둔 자리다. 모델은 `TRANSLATE_MODEL` 로 바꾼다.
 *
 * **그 업체의 키가 없으면 교정 업체로 돌아간다.** 번역이 통째로 401·503 이 되는 것보다 낫다.
 * 개인정보 처리방침이 실제로 보내는 곳을 따라가므로(privacyPage), 값을 바꾸기 전에 방침 문구가 맞는지 본다.
 */
function translateProvider(env) {
  const asked = (env.TRANSLATE_PROVIDER ?? '').trim().toLowerCase();
  const name = { google: 'gemini', claude: 'anthropic', solar: 'upstage' }[asked] ?? asked;
  if (!['gemini', 'openai', 'anthropic', 'upstage'].includes(name)) return provider(env);
  return env[keyNameOf(name)] ? name : provider(env);
}

function upstageModel(env, override = '') {
  return override || (env.UPSTAGE_MODEL ?? '').trim() || DEFAULT_UPSTAGE_MODEL;
}

function claudeModel(env, override = '') {
  return override || (env.CLAUDE_MODEL ?? '').trim() || DEFAULT_CLAUDE_MODEL;
}

function openAiModel(env, override = '') {
  return override || (env.OPENAI_MODEL ?? '').trim() || DEFAULT_OPENAI_MODEL;
}

function geminiModel(env, override = '') {
  return override || (env.GEMINI_MODEL ?? '').trim() || DEFAULT_GEMINI_MODEL;
}

/**
 * 어느 제미나이로 갈 것인가 — **구글에 물어서** 정한다.
 *
 * ## 왜 물어보나
 *
 * 예전에는 앱이 물어봤다. 앱이 구글 목록을 받아 `pickModel` 로 제일 좋은 lite 를 골랐고,
 * 구글이 새 모델을 내면 앱이 저절로 갈아탔다. OpenAI 로 갔다가 되돌아오면서 그 자리를
 * **이름 하나로 못박아** 버렸는데, 그러면 구글이 새 모델을 내도 영영 낡은 것에 머문다.
 * 실기기에서 "예전 제미나이보다 못하다" 는 말이 나온 자리가 여기다.
 *
 * ## 그래도 앱한테 안 시키는 이유
 *
 * 앱이 물으면 **교정 한 번에 왕복이 두 번**이 된다(목록 한 번, 교정 한 번). 그 한 번이
 * 첫 교정의 대기 시간으로 그대로 붙었다. 서버가 물으면 하루에 한 번이고, 그 결과를
 * D1 에 넣어 두므로 사용자가 기다리는 시간은 0 이다.
 *
 * ## 못 물었을 때
 *
 * 아는 이름([DEFAULT_GEMINI_MODEL])으로 간다. **실패는 캐시하지 않는다** — 구글이 잠깐
 * 삐끗한 것 때문에 하루를 낡은 모델로 보내면 고치는 의미가 없다.
 *
 * `GEMINI_MODEL` 이 적혀 있으면 그게 이긴다. 특정 모델에 묶어 두고 재 보고 싶을 때가 있다.
 */
async function resolveGeminiModel(env, fetchImpl, nowMs) {
  const pinned = (env.GEMINI_MODEL ?? '').trim();
  if (pinned) return pinned;

  const cached = await cacheGet(env.DB, MODEL_KEY, nowMs);
  if (cached?.name) return cached.name;

  let picked = null;
  try {
    const raw = await relayTo(fetchImpl, env, GEMINI_MODELS_URL, 'GET', null);
    if (raw.status === 200) picked = pickGeminiModel(parseGeminiModels(raw.text));
  } catch {
    picked = null;
  }
  if (!picked) return DEFAULT_GEMINI_MODEL;

  await cacheSet(env.DB, MODEL_KEY, { name: picked }, MODEL_TTL_MS, nowMs);
  return picked;
}

/**
 * 보낼 AI 요청을 **먼저 짓는다.** 한도를 미리 잡으려면 보낼 바이트와 출력 상한을 알아야 해서, 짓기와 보내기를
 * 가른다. 돌려주는 것은 `{ bytes, maxTokens, send }` — `send()` 가 실제로 보내고 구글 모양 답을 준다.
 * 지을 수 없는 요청(고칠 글이 없음 등)이면 `{ reply }` 로 400 — 그때는 한도를 잡지 않는다.
 *
 * 어느 모델로 갈지는 **서버가 정한다** — 앱이 주소에 실은 이름은 쓰지 않는다(이미 깔린 APK 들이 저마다 다르다).
 * 지시문과 숙고 세기는 환경변수로 바꿀 수 있다(코드를 고쳐 배포하는 대신 값만 바꿔 재 보려고 열어 둔 자리).
 * - 업스테이지: `UPSTAGE_PROMPT`·`UPSTAGE_REASONING`·`UPSTAGE_EXTRA_RULES` (upstage.js). 응답은 OpenAI 모양이라
 *   [toGeminiReply] 를 같이 쓴다(잘린 답, 빈 답, 교정이 아닌 답을 거른다).
 * - Claude: `CLAUDE_PROMPT` (anthropic.js). OpenAI: `OPENAI_PROMPT`·`OPENAI_REASONING` — 숙고 항목을 안 받는
 *   모델이면 빼고 한 번 더 보낸다. 구글: 교정은 앱 지시문, 번역은 우리 지시문(gemini.js).
 */
async function buildAiCall(env, fetchImpl, body, translateTo, nowMs) {
  // 번역은 따로 정한 업체·모델로 갈 수 있다([translateProvider]). 비워 두면 교정과 같다.
  const which = translateTo ? translateProvider(env) : provider(env);
  const modelOverride = translateTo ? (env.TRANSLATE_MODEL ?? '').trim() : '';
  // 번역 지시문 판("v1"~"v4"). 비면 업체에 맞는 기본값 — OpenAI 모델은 영어 규칙으로 줄인 4판이 3판과 같은 점수를 반값에 내고,
  // Solar 는 한국어 지시문(3판)이 낫다(겨루기 2026-10-09). 배포 없이 값만 바꿔 되돌린다.
  const translatePrompt = (env.TRANSLATE_PROMPT ?? '').trim() || (which === 'openai' ? 'v4' : DEFAULT_TRANSLATE_PROMPT);
  // 구글 모델 고르기는 짓기 실패와 섞이면 안 된다(저장소 오류를 "잘못된 요청" 으로 바꿔 버린다). 밖에서 한다.
  const geminiModelName = which === 'gemini' ? modelOverride || (await resolveGeminiModel(env, fetchImpl, nowMs)) : null;
  let target;
  let request;
  let build; // (translateHint) => 보낼 본문. 번역에 한글이 남았을 때 힌트를 얹어 한 번 다시 짓는다.
  let maxTokens;
  let finish;
  try {
    const user = userTextOf(body);
    if (which === 'upstage') {
      target = UPSTAGE_URL;
      build = (translateHint) =>
        toUpstageRequest(body, upstageModel(env, modelOverride), {
          prompt: (env.UPSTAGE_PROMPT ?? '').trim(),
          reasoning: (env.UPSTAGE_REASONING ?? '').trim(),
          extraRules: (env.UPSTAGE_EXTRA_RULES ?? '').trim().toLowerCase() !== 'off',
          translateTo,
          translatePrompt,
          translateHint,
        });
      request = build('');
      maxTokens = JSON.parse(request).max_tokens;
      finish = (raw) => toGeminiReply(raw.status, raw.text, user, { translateTo });
    } else if (which === 'anthropic') {
      target = ANTHROPIC_URL;
      build = (translateHint) =>
        toClaudeRequest(body, claudeModel(env, modelOverride), { prompt: (env.CLAUDE_PROMPT ?? '').trim(), translateTo, translatePrompt, translateHint });
      request = build('');
      maxTokens = JSON.parse(request).max_tokens;
      finish = (raw) => fromClaudeReply(raw.status, raw.text, user, { translateTo });
    } else if (which === 'openai') {
      target = OPENAI_URL;
      build = (translateHint) =>
        toOpenAiRequest(body, openAiModel(env, modelOverride), {
          reasoning: (env.OPENAI_REASONING ?? '').trim(),
          prompt: (env.OPENAI_PROMPT ?? '').trim(),
          translateTo,
          translatePrompt,
          translateHint,
        });
      request = build('');
      maxTokens = JSON.parse(request).max_completion_tokens;
      finish = (raw) => toGeminiReply(raw.status, raw.text, user, { translateTo });
    } else {
      target = geminiUrl(geminiModelName);
      build = (translateHint) => toGeminiRequest(body, { translateTo, translatePrompt, translateHint });
      request = build('');
      maxTokens = JSON.parse(request).generationConfig.maxOutputTokens;
      finish = (raw) => fromGeminiReply(raw.status, raw.text, user, { translateTo });
    }
  } catch {
    return { reply: { status: 400, text: JSON.stringify({ error: { code: 400, message: 'invalid_request', status: 'ERROR' } }) } };
  }
  return {
    bytes: utf8Bytes(request),
    maxTokens,
    async send() {
      const once = async (requestBody) => {
        let raw = await relayTo(fetchImpl, env, target, 'POST', requestBody);
        // OpenAI 만: 숙고 항목을 안 받는 모델이면 빼고 한 번 더(첫 번은 거절이라 값이 안 나갔다).
        if (which === 'openai' && rejectsReasoning(raw.status, raw.text)) {
          raw = await relayTo(fetchImpl, env, target, 'POST', withoutReasoning(requestBody));
        }
        return billedBy(finish(raw), raw.status);
      };
      const reply = await once(request);
      if (!translateTo) return reply;

      // **번역에 한글이 남았으면 한 번 다시 옮기게 한다.** 작은 모델은 가끔 낯선 말 하나를 못 옮기고 둔다 — 쓰는 사람은 번역문을 그대로
      // 보내므로 상대에게 한글이 섞여 간다. 같은 요청을 다시 보내면 temperature 0 이라 같은 답이 나오니, 남은 말을 짚어 준 지시문으로 다시 짓는다.
      // 다시 해도 남으면 502 로 막는다(앱이 기기 번역으로 대신한다). 두 번 값이 나갔으니 토큰은 합쳐서 정산한다.
      let hint = '';
      if (reply.status === 200) hint = hangulFragments(translationOf(reply)).join(' · ');
      // 한글이 글자의 3분의 1 을 넘게 남아 이미 502 로 막힌 답([translationProblem])도 한 번은 다시 옮겨 본다.
      else if (reply.billed && reply.status === 502 && /\(untranslated\)/.test(reply.text)) hint = '문장 거의 전부';
      if (!hint) return reply;
      const retry = await once(build(hint));
      const usage = sumUsage(usageOf(reply), usageOf(retry));
      if (retry.status === 200 && !hangulFragments(translationOf(retry)).length) {
        retry.usage = usage;
        return retry;
      }
      return billedBy(billedError(502, '번역에 한글이 남았다', usage), 200);
    },
  };
}

/** 구글 모양 답에서 번역문 한 줄. 깨졌으면 빈 문자열. */
function translationOf(reply) {
  try {
    return JSON.parse(reply.text)?.candidates?.[0]?.content?.parts?.[0]?.text ?? '';
  } catch {
    return '';
  }
}

/** [usageOf] 두 개를 구글 모양 `usageMetadata` 하나로 합친다. */
function sumUsage(a, b) {
  return {
    promptTokenCount: a.prompt + b.prompt,
    candidatesTokenCount: a.output + b.output,
    thoughtsTokenCount: a.thoughts + b.thoughts,
  };
}

/**
 * 바깥이 200 을 줬으면(=값이 나갔으면) 그 표시를 답에 붙인다. 우리가 그 답을 잘렸다·비었다로
 * 502 로 바꾸더라도 한도는 정산해야 하기 때문이다([correct] 참고).
 */
function billedBy(reply, rawStatus) {
  reply.billed = rawStatus === 200;
  return reply;
}

/** 바깥에 보내고 상태와 본문 문자열만 받는다. 본문을 읽어야 토큰 수를 셀 수 있다. */
async function relayTo(fetchImpl, env, target, method, body) {
  let res;
  // 업스테이지는 **미국 중계를 거치지 않는다.** api.upstage.ai 는 서울(AWS ap-northeast-2)에 있다.
  // 중계를 타면 폰 → 서울 엣지 → 미국 서부 → 서울 → 미국 서부 → 서울로 태평양을 네 번 건넌다.
  // 중계가 생긴 이유(구글·OpenAI 가 홍콩 IP 를 거절)는 한국 회사인 업스테이지에는 해당하지 않는다.
  if (env.RELAY && !target.startsWith(UPSTAGE_URL)) {
    const stub = env.RELAY.get(env.RELAY.idFromName(RELAY_NAME), { locationHint: RELAY_LOCATION });
    res = await stub.fetch(target, { method, body });
  } else {
    // 중계 객체가 없는 환경(테스트)에서는 여기서 바로 보낸다.
    res = await fetchImpl(target, { method, headers: upstreamHeaders(env, target), body });
  }
  return { status: res.status, text: await res.text() };
}

function asResponse(reply) {
  const headers = { 'content-type': 'application/json; charset=utf-8' };
  // 느리다는 말만으로는 어디를 손볼지 알 수 없다. 이 숫자는 **모델이 쓴 시간**이라,
  // 앱이 재는 전체 시간에서 이걸 빼면 한국-미국 왕복이 얼마인지도 나온다.
  if (reply.tookMs != null) headers['x-upstream-ms'] = String(reply.tookMs);
  // 모델이 교정이 아닌 답(대답·요약)을 해서 원문을 돌려준 경우. 앱은 안 보고, 진단할 때 본다.
  if (reply.kept) headers['x-correction-kept'] = 'original';
  return new Response(reply.text, { status: reply.status, headers });
}

/**
 * 답이 알려 준 토큰 수. 성공한 답은 본문의 usageMetadata 에, 잘렸다·비었다로 502 를 준 답은 `reply.usage` 에
 * 실려 온다(openai.js 의 billedError). 없거나 깨졌으면 0 — 그러면 정산은 글자 수로만 된다.
 */
function usageOf(reply) {
  let meta = reply?.usage;
  if (!meta) {
    try {
      meta = JSON.parse(reply?.text)?.usageMetadata;
    } catch {
      meta = null;
    }
  }
  const n = (value) => {
    const parsed = Number(value ?? 0);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
  };
  return { prompt: n(meta?.promptTokenCount), output: n(meta?.candidatesTokenCount), thoughts: n(meta?.thoughtsTokenCount) };
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

/** 어디로 가느냐에 따라 붙이는 키가 다르다. 키는 이 함수 밖으로 나가지 않는다. */
function upstreamHeaders(env, target) {
  const headers = { 'content-type': 'application/json; charset=utf-8' };
  // 사람이 붙여넣은 비밀값이라 끝에 줄바꿈이 딸려 온 적이 있다. 헤더에 줄바꿈이
  // 들어가면 fetch 가 예외를 던져 500 이 된다.
  if (target.startsWith(OPENAI_URL)) {
    headers.authorization = `Bearer ${(env.OPENAI_API_KEY ?? '').trim()}`;
  } else if (target.startsWith(ANTHROPIC_URL)) {
    headers['x-api-key'] = (env.ANTHROPIC_API_KEY ?? '').trim();
    headers['anthropic-version'] = ANTHROPIC_VERSION;
  } else if (target.startsWith(UPSTAGE_URL)) {
    headers.authorization = `Bearer ${(env.UPSTAGE_API_KEY ?? '').trim()}`;
  } else if (target.startsWith(GEMINI_UPSTREAM)) {
    headers['x-goog-api-key'] = (env.GEMINI_API_KEY ?? '').trim();
  }
  // 아는 곳이 아니면 **키를 붙이지 않는다.** 지금 target 은 전부 서버가 정한 상수라 여기 올 일이
  // 없지만, 혹시 주소가 잘못 설정돼도 키가 엉뚱한 곳으로 가지 않게 막는다(예전엔 모르는 곳에도
  // 구글 키를 붙였다).
  return headers;
}

/** 바깥 응답의 상태와 본문만 넘긴다. 저쪽 헤더는 우리 것과 섞이지 않게 버린다. */
async function passthrough(res) {
  return new Response(await res.text(), {
    status: res.status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/**
 * 남은 양을 헤더로 실어 준다. 앱은 이걸 읽어 "오늘 3회 남음" / "오늘 87,000자 남음" 을
 * 보여준다. 단위(`x-quota-unit`)가 없으면 옛 앱은 횟수로 읽는다.
 */
function withQuota(response, usage, limit, plan, unit = 'calls') {
  const headers = new Headers(response.headers);
  headers.set('x-plan', plan);
  if (usage) {
    headers.set('x-quota-remaining', String(usage.remaining));
    headers.set('x-quota-limit', String(limit));
    headers.set('x-quota-unit', unit);
  }
  return new Response(response.body, { status: response.status, headers });
}

/**
 * Play 를 거치지 않고 구독자로 쳐 주는 설치 ID 들.
 *
 * 개발하는 사람이 자기 폰에서 유료 기능(AI 번역)을 확인하려면 진짜 구독이 있어야 하는데,
 * Play Console 등록 전에는 그게 없다. 그래서 **비밀값**으로 설치 ID 를 몇 개 적어 두면
 * 그 기기만 구독자로 본다. 쉼표로 나눠 적는다.
 *
 * 저장소가 공개라 이 목록은 코드에도 wrangler.toml 에도 두지 않는다 —
 * `wrangler secret put TEST_INSTALL_IDS` 로만 들어간다. 설치 ID 는 앱이 만든 UUID 라
 * 남이 맞힐 수 없고, 값이 새더라도 잃는 것은 그 기기 하나의 무료 한도뿐이다.
 * 출시 뒤에는 비워 두는 것이 맞다: `wrangler secret delete TEST_INSTALL_IDS`.
 */
function isTestSubscriber(env, installId) {
  const listed = env.TEST_INSTALL_IDS;
  if (!listed || !installId) return false;
  return String(listed)
    .split(',')
    .map((one) => one.trim())
    .some((one) => one.length > 0 && one === installId);
}

/** Play 가 답을 못 했고 기댈 예전 확인 기록도 없다. "구독 아님" 과 다르다 — 모르는 것이다. */
class PlayUnavailable extends Error {}

/** Play 장애 때 기댈 "마지막으로 확인한 결과" 를 들고 있는 기간. 한 달 구독 주기보다 조금 길게. */
const LAST_VERDICT_TTL_MS = 35 * 24 * 60 * 60 * 1000;
/** 장애 때 그 마지막 결과를 믿어 주는 기간. 이보다 오래된 기록이면 믿지 않고 503. */
const LAST_VERDICT_TRUST_MS = 3 * 24 * 60 * 60 * 1000;
/** 서버 승인까지 실패한 미승인 구매를 봐주는 시간. 정상 앱은 결제 몇 초 안에 승인한다. */
const ACK_GRACE_MS = 60 * 60 * 1000;
/** 승인에 실패한 결과를 들고 있는 시간. 짧게 — 곧 다시 물어 다시 승인해 본다. */
const UNACKED_TTL_MS = 5 * 60 * 1000;

function playProductId(env) {
  return (env.PLAY_PRODUCT_ID ?? '').trim() || DEFAULT_PRODUCT_ID;
}

/**
 * 구독자인가. 토큰이 없거나 서비스 계정이 설정돼 있지 않으면 무료다.
 *
 * Play 에 묻고 결과를 1시간 기억한다. 판단 규칙은 [interpretSubscription](play.js) — 우리 상품인가,
 * 해지 예약이면 만료 전인가, 시험 결제인가. 여기서는 셋을 더 한다(윈도우 점검 ⑦):
 *
 * - **승인 안 된 구매는 서버가 승인한다(③).** 뜯어고친 앱이 승인을 일부러 안 하면 3일 쓰고 자동 환불을
 *   되풀이할 수 있다. 서버 승인까지 실패하면 결제 뒤 1시간까지만 봐준다.
 * - **Play 가 고장 나면 마지막으로 확인한 결과를 쓴다(④).** 예전에는 "구독 아님" 으로 보아 돈 낸 사람에게
 *   "구독자 전용" 을 띄웠다. 사흘 안에 확인한 기록이 있으면 그걸 믿고, 없으면 [PlayUnavailable] 을 던진다.
 * - 해지 예약이면 결과를 만료 시각 넘어서까지 들고 있지 않는다.
 */
async function isSubscriber(env, purchaseToken, fetchImpl, nowMs) {
  if (!purchaseToken || !env.PLAY_SERVICE_ACCOUNT || !env.PLAY_PACKAGE) return false;

  const hash = await sha256Hex(purchaseToken);
  const key = 'play:' + hash;
  const lastKey = 'playlast:' + hash;
  const cached = await cacheGet(env.DB, key, nowMs);
  if (cached) return cached.active === true;

  const productId = playProductId(env);
  let verdict;
  let accessToken;
  try {
    accessToken = await playAccessToken(env, fetchImpl, nowMs);
    verdict = await verifySubscription({
      pkg: env.PLAY_PACKAGE,
      purchaseToken,
      accessToken,
      fetchImpl,
      productId,
      nowMs,
      allowTest: isOn(env.ALLOW_TEST_PURCHASES),
    });
  } catch {
    return lastKnownVerdict(await cacheGet(env.DB, lastKey, nowMs), nowMs);
  }

  let ttl = VERDICT_TTL_MS;
  if (verdict.active && verdict.needsAck) {
    const acked = await acknowledgeSubscription({ pkg: env.PLAY_PACKAGE, productId, purchaseToken, accessToken, fetchImpl });
    if (!acked) {
      ttl = UNACKED_TTL_MS;
      if (verdict.startMs != null && nowMs - verdict.startMs > ACK_GRACE_MS) {
        verdict = { ...verdict, active: false, state: 'UNACKNOWLEDGED' };
      }
    }
  }
  if (verdict.active && verdict.state === CANCELED_STATE && verdict.expiryMs != null) {
    ttl = Math.max(60_000, Math.min(ttl, verdict.expiryMs - nowMs));
  }

  const record = { active: verdict.active, state: verdict.state, expiryMs: verdict.expiryMs };
  await cacheSet(env.DB, key, record, ttl, nowMs);
  await cacheSet(env.DB, lastKey, { ...record, checkedAt: nowMs }, LAST_VERDICT_TTL_MS, nowMs);
  return verdict.active;
}

/**
 * Play 가 답을 못 했을 때 마지막 확인 결과로 판단한다. 사흘 넘은 기록이거나 기록이 없으면 모른다(던진다).
 * 해지 예약이었던 사람은 그때 알려 준 만료 시각까지만. 정상 구독은 그사이 갱신됐을 것으로 본다.
 */
function lastKnownVerdict(last, nowMs) {
  if (!last || typeof last.checkedAt !== 'number' || nowMs - last.checkedAt > LAST_VERDICT_TRUST_MS) {
    throw new PlayUnavailable();
  }
  if (!last.active) return false;
  if (last.state === CANCELED_STATE) return last.expiryMs != null && last.expiryMs > nowMs;
  return true;
}

/**
 * Play 장애를 "구독 아님" 으로 읽는다. 로그인처럼, 장애로 통째로 막는 것이 더 나쁜 길에서만 쓴다 —
 * 그 길은 구독을 붙이거나 화면에 보여 줄 뿐 돈 드는 AI 를 열어 주지 않는다.
 */
async function isSubscriberOrFalse(env, purchaseToken, fetchImpl, nowMs) {
  try {
    return await isSubscriber(env, purchaseToken, fetchImpl, nowMs);
  } catch (err) {
    if (err instanceof PlayUnavailable) return false;
    throw err;
  }
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

/**
 * 하루치에서 [amount] 를 **원자적으로 예약하고 예약 뒤의 총량을 돌려준다.**
 *
 * 더하기(`used = used + excluded.used`)는 한 문장이라 D1 이 직렬화한다 — 동시에 와도 하나씩 더해지고,
 * `RETURNING` 으로 저마다 자기가 더한 뒤의 값을 받는다. 호출한 쪽은 그 값이 한도를 넘었는지만 보면 된다.
 * 읽고-판단하고-쓰기로 나누면 그 사이에 끼어든 요청이 같은 값을 읽어 한도가 뚫린다(correct 참고).
 */
async function reserve(db, id, day, amount) {
  const row = await db
    .prepare(
      'INSERT INTO usage (id, day, used) VALUES (?, ?, ?) ' +
        'ON CONFLICT(id, day) DO UPDATE SET used = used + excluded.used RETURNING used'
    )
    .bind(id, day, amount)
    .first();
  return Number(row?.used ?? amount);
}

/**
 * 잡아 둔 것을 실제 정산값으로 맞춘다 — [delta] 만큼 더하거나(양수) 돌려주고(음수) 맞춘 뒤의 총량을 돌려준다.
 * 한 문장이라 동시에 와도 섞이지 않는다. 0 밑으로는 안 내려간다.
 */
async function adjust(db, id, day, delta) {
  const row = await db
    .prepare('UPDATE usage SET used = MAX(0, used + ?) WHERE id = ? AND day = ? RETURNING used')
    .bind(delta, id, day)
    .first();
  return Number(row?.used ?? 0);
}

/** 예약을 돌려준다 — 한도를 넘어 막혔거나, 바깥이 거절해 돈이 안 나갔을 때. 0 밑으로는 안 내려간다. */
async function refund(db, id, day, amount) {
  await db
    .prepare('UPDATE usage SET used = MAX(0, used - ?) WHERE id = ? AND day = ?')
    .bind(amount, id, day)
    .run();
}

/** 고칠 글의 길이. 본문이 깨져 있으면 0 — 어차피 바깥에서 거절당한다. */
function safeUserLength(body) {
  try {
    return userTextOf(body).length;
  } catch {
    return 0;
  }
}

/** 이틀 지난 사용량과 만료된 캐시를 치운다. 크론이 하루 한 번 부른다. */
async function sweep(env) {
  const nowMs = Date.now();
  await env.DB.prepare('DELETE FROM usage WHERE day < ?').bind(kstDay(nowMs - 2 * 86_400_000)).run();
  await env.DB.prepare('DELETE FROM cache WHERE expires_at < ?').bind(nowMs).run();
  await env.DB.prepare('DELETE FROM tokens WHERE day < ?').bind(kstDay(nowMs - 90 * 86_400_000)).run();
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
/**
 * 개인정보 처리방침과 이용약관은 **여기 하나에만** 있다.
 *
 * Play Console 에 적는 주소도, 앱의 더보기 메뉴가 여는 주소도 이 페이지다. 앱 안에 글을 따로
 * 넣으면 두 벌이 생기고, 언젠가 하나만 고쳐져서 서로 다른 말을 하게 된다.
 *
 * 문의처(`CONTACT_EMAIL`)와 운영자 이름(`OPERATOR_NAME`)은 설정에서 읽는다. 비어 있으면 그
 * 줄을 아예 안 만든다 — "문의: " 만 덩그러니 있는 것보다 낫다.
 */
function docPage(env, title, body, effective) {
  const operator = env.OPERATOR_NAME ? `<p>운영자: ${escapeHtml(env.OPERATOR_NAME)}</p>` : '';
  const contact = env.CONTACT_EMAIL ? `<p>문의: ${escapeHtml(env.CONTACT_EMAIL)}</p>` : '';
  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>맞춤법 키보드 ${title}</title>
<style>body{font-family:sans-serif;max-width:680px;margin:40px auto;padding:0 20px;line-height:1.7;color:#191f28}h1{font-size:22px}h2{font-size:17px;margin-top:28px}li{margin:4px 0}.muted{color:#6b7684;font-size:14px}</style>
</head><body>
<h1>맞춤법 키보드 ${title}</h1>
${body}
${operator}${contact}
<p class="muted">시행일: ${effective}</p>
</body></html>`;
  return new Response(html, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
}

/** 문서별 시행일. 그 문서의 내용을 바꾸면 그 날짜만 올린다. */
const PRIVACY_EFFECTIVE = '2026-10-10';
const TERMS_EFFECTIVE = '2026-09-26';

function privacyPage(env) {
  // 어느 회사로 보내는지는 방침의 핵심이라 실제 설정을 그대로 따라가게 둔다.
  // **실제로 보내는 곳(provider)** 을 봐야 한다. 예전에는 openAiModel(env) 를 봤는데, 그건
  // 기본 모델 이름이 늘 있어서 참이다 — 구글로 보내면서 방침에는 OpenAI 라고 적혀 있었다.
  // 교정과 번역이 다른 업체로 갈 수 있다([translateProvider]). 같으면 예전 문구 그대로이고, 다르면 두 곳을 다 적는다.
  const which = provider(env);
  const translateWhich = translateProvider(env);
  const info = (p) => {
    const openai = p === 'openai';
    const claude = p === 'anthropic';
    const upstage = p === 'upstage';
    return {
      api: openai ? 'OpenAI API' : claude ? 'Anthropic Claude API' : upstage ? '업스테이지 Solar API' : 'Google Gemini API',
      name: openai ? 'OpenAI' : claude ? 'Anthropic' : upstage ? '업스테이지' : 'Google',
      abroad: openai
        ? '<li><strong>OpenAI (미국)</strong> — AI 교정·번역 처리. 누를 때 그 입력란의 글. 처리 즉시 결과만 돌려받습니다.</li>'
        : claude
          ? '<li><strong>Anthropic (미국)</strong> — AI 교정·번역 처리. 누를 때 그 입력란의 글. 처리 즉시 결과만 돌려받습니다.</li>'
          : '',
      // 업스테이지는 한국 회사지만 자기 처리방침에 미국 업체(추론 인프라 포함)로의 이전을 적어 둔다.
      // 그래서 "국내라 이전 없음" 이라고 단정하지 않고 그대로 옮긴다.
      processor: upstage
        ? '<li><strong>주식회사 업스테이지 (대한민국)</strong> — AI 교정·번역 처리. 누를 때 그 입력란의 글. 처리 즉시 결과만 돌려받습니다. 업스테이지는 처리를 위해 미국 등 해외 클라우드 업체를 이용할 수 있습니다.</li>'
        : '',
      google: !openai && !claude && !upstage,
    };
  };
  const main = info(which);
  const split = translateWhich !== which;
  const second = split ? info(translateWhich) : null;
  const providerApi = split ? `${main.api}(교정)·${second.api}(번역)` : main.api;
  const providerName = main.name;
  const providerNames = split ? `${main.name}·${second.name}` : main.name;
  const aiAbroad = main.abroad + (second?.abroad ?? '');
  const aiProcessor = main.processor + (second?.processor ?? '');
  // Google 줄에서 "AI 교정·번역 처리" 를 빼는 것은 AI 가 구글이 아닐 때다.
  const googleDoesAi = main.google || (second?.google ?? false);
  // PC 프로그램 내용은 윈도우 세션이 실제 동작을 확인해 넘겨준 답(2026-10-08)을 그대로 옮겼다.
  // PC 동작이 바뀌면 그쪽(desktop/)에서 알려 줘야 여기도 고친다.
  return docPage(env, '개인정보 처리방침', `
<p>맞춤법 키보드는 <strong>안드로이드 키보드 앱</strong>과 <strong>윈도우 PC 프로그램</strong>(이하 "PC 프로그램")으로 제공되며, 둘은 같은 서비스 서버와 계정을 씁니다. 이 문서는 두 프로그램이 어떤 정보를 어디까지 다루는지 설명합니다.</p>

<h2>1. 입력한 글</h2>
<ul>
<li><strong>휴대폰</strong>: 실시간 맞춤법·띄어쓰기 교정과 전체 교정(짧게 누르기)은 <strong>전부 기기 안에서</strong> 처리됩니다. 입력한 글은 어디로도 전송되거나 저장되지 않습니다. 비밀번호·이메일·URL 입력란에서는 교정이 자동으로 꺼집니다.</li>
<li><strong>PC 프로그램</strong>: 교정은 PC 프로그램 창 안에 입력한 글에서만 이루어지며, 실시간 교정과 전체 교정 모두 PC 안에서 처리됩니다. "전체번역"(받아 둔 번역 언어 묶음 또는 AI 킷으로 옮기는 것)도 PC 안에서만 이루어집니다. 입력한 글과 고친 글은 PC에 저장되지 않고 외부로 보내지지 않습니다. <strong>다른 프로그램에 입력한 글은 읽지 않습니다.</strong></li>
</ul>

<h2>2. AI 교정·번역 (프리미엄)</h2>
<p>다음 경우에만 글이 서비스 서버를 거쳐 ${providerApi} 로 전송되고 결과가 돌아옵니다.</p>
<ul>
<li><strong>휴대폰</strong>: 자판 위 전체 교정 버튼을 <strong>직접 꾹 누를 때</strong>, 또는 번역 창의 <strong>"AI정밀번역"을 누를 때</strong> — 그 입력란의 글(커서 앞뒤 최대 2,000자). 프리미엄 구독자가 번역 입력줄에서 <strong>보내기(엔터)를 누를 때</strong> — 그 입력줄에 쓴 글. 번역 창의 "전체번역"과 실시간 번역은 휴대폰 안에서만 처리되며 전송하지 않습니다.</li>
<li><strong>PC 프로그램</strong>: 사용자가 <strong>"AI 교정" 또는 "AI정밀번역"을 직접 누를 때</strong> — PC 프로그램 창에 입력된 글(교정 최대 3,000자, 번역 최대 2,000자). 로그인하지 않았으면 보내지 않습니다.</li>
</ul>
<p>치는 동안 저절로 보내는 일은 없습니다. AI 처리가 실패하면 휴대폰·PC 안의 번역기로 대신하며, 이때는 글을 다시 보내지 않습니다. 휴대폰에서는 비밀번호·이메일·URL 입력란의 글을 보내지 않습니다. PC 프로그램 창에 직접 입력한 글은 걸러 내지 않고 그대로 보내므로, 비밀번호 같은 민감한 내용은 넣지 마십시오. 글과 함께 설치 식별자(그리고 로그인한 경우 기기 토큰)가 전송되며, 이름·이메일은 전송되지 않습니다. 서버는 글을 저장하지 않으며, 하루 사용량(글자 수)만 셉니다. ${split ? `${providerNames}의 처리에는 각 회사의 개인정보 처리방침이 적용됩니다.` : `${providerName}의 처리에는 ${providerName}의 개인정보 처리방침이 적용됩니다.`}</p>

<h2>3. 서버가 보관하는 것</h2>
<ul>
<li><strong>설치 식별자</strong>: 앱이나 PC 프로그램을 설치할 때 만들어지는 무작위 값입니다. 요청을 구분하는 데 쓰이며, 이름·전화번호 같은 개인 정보와 연결되지 않습니다. 하루 사용량 기록에 쓰인 경우 그 기록과 함께 이틀 뒤 삭제됩니다.</li>
<li><strong>하루 사용량</strong>: 구독 하나당 하루에 쓴 글자 수. 이틀 뒤 삭제됩니다.</li>
<li><strong>접속 IP 주소</strong>: 짧은 시간에 요청이 몰리는 남용을 막기 위해 분 단위 요청 횟수를 셉니다. IP 주소는 그대로 저장하지 않고 바꾼 값(해시)으로 세며, 이틀 뒤 삭제됩니다.</li>
<li><strong>구독 확인</strong>: 구독한 경우 Google Play 구매 토큰으로 Google Play 에 구독 상태를 조회합니다. 조회 결과는 1시간 동안만 보관합니다. 결제는 Google Play 가 처리하며 앱과 서버는 카드 정보 등을 다루지 않습니다.</li>
<li><strong>계정 (로그인한 경우에만)</strong>: 구글 계정 고유 번호를 <strong>되돌릴 수 없게 바꾼 값(해시)</strong>, 계정에 연결된 구매 토큰, 로그인한 기기 목록(기기 이름표 — 휴대폰은 모델명, PC는 "Windows" — 와 마지막 사용 시각). 한 구독을 휴대폰·PC 등 여러 기기에서 같이 쓰게 하는 데만 쓰입니다. 로그인할 때 구글이 서명한 로그인 증명에는 이메일도 들어 있지만, 서버는 계정 고유 번호만 읽어 해시로 바꿔 저장합니다. <strong>이름·이메일·프로필 사진은 서버에 저장하지 않습니다.</strong> 회원 탈퇴하면 바로 삭제됩니다.</li>
</ul>

<h2>4. 휴대폰에만 저장되는 것</h2>
<p>클립보드 기록(최근 20개까지, 암호화해 저장하며 담은 지 하루가 지나면 자동으로 지워짐), 배경 사진, 테마·자판 설정, 설치 식별자·구매 토큰·기기 토큰, 그리고 로그인한 경우 구글 계정 이름과 프로필 사진(더보기 화면에 보여 주는 용도)은 휴대폰 안에만 저장되며, 로그아웃하거나 앱을 삭제하면 함께 사라집니다. 클립보드에서 "민감" 으로 표시된 내용(비밀번호 등)은 기록하지 않습니다. 설치 식별자와 토큰은 클라우드 백업이나 기기 간 이전으로 다른 기기에 복사되지 않게 막혀 있습니다.</p>

<h2>5. PC 프로그램에 저장되는 것과 동작</h2>
<ul>
<li><strong>로그인</strong>: 브라우저에 열리는 구글 로그인 화면에서 이루어지므로 PC 프로그램은 비밀번호를 볼 수 없습니다. 구글에는 계정 확인과 이메일만 요청하며, 이름과 사진은 요청하지 않아 받지 않습니다.</li>
<li><strong>설정</strong>(윈도우 레지스트리): 단축키, 키 소리 종류·크기·범위, 창 위치, 자동 시작 여부.</li>
<li><strong>계정 정보</strong>(윈도우 레지스트리): 설치 식별자, 서버가 발급한 기기 토큰, 로그인한 구글 이메일(어느 계정으로 로그인했는지 화면에 보여 주는 용도), 구독 상태와 마지막 확인 시각, 서버에 아직 알리지 못한 로그아웃 기록(최대 5개). 기기 토큰·로그아웃 기록·이메일은 윈도우 자체 암호화(DPAPI)로 보호해 저장하며, 그 PC 의 같은 윈도우 사용자만 풀 수 있습니다. <strong>로그아웃하면 기기 토큰·이메일·구독 상태를 PC에서 지웁니다.</strong></li>
<li><strong>그 밖의 파일</strong>: 맞춤법 사전 파일, 프로그램이 두 번 켜지는 것을 막는 작은 파일, 사용자가 받기를 눌렀을 때만 저장하는 번역 언어 묶음과 AI 킷(번역 모델, 약 3GB — 사용자 폴더 %LOCALAPPDATA%\\SpellDesktop\\llm 에 저장하며 설정에서 지울 수 있습니다). 윈도우 시작 시 자동 실행은 그 설정을 켰을 때만 등록합니다.</li>
<li><strong>저장하지 않는 것</strong>: 입력한 글, 고친 글, 클립보드 기록, 배경 사진, 비밀번호는 PC에 저장하지 않습니다.</li>
<li><strong>키 소리 "모든 프로그램에서"</strong>: 기본으로 꺼져 있습니다. 켜면 다른 프로그램에서 누른 키의 <strong>종류</strong>(글자·띄어쓰기·지우기·엔터)만 받아 소리를 내고 바로 버립니다. 입력한 글자는 읽지 않으며 저장하거나 전송하지 않습니다.</li>
<li><strong>다른 프로그램에 붙여 넣기</strong>: 단축키를 누르면 그때 앞에 있던 창이 어느 것인지만 기억하며, 그 창의 글이나 제목은 읽지 않습니다. 사용자가 확정하면 그 창에 고친 글을 붙여 넣습니다. 이때 윈도우 클립보드를 쓰므로, 윈도우의 "클립보드 기록(Win+V)"이나 클라우드 동기화를 켜 둔 경우 윈도우가 그 내용을 보관할 수 있습니다(이 보관에는 Microsoft의 방침이 적용됩니다).</li>
</ul>

<h2>6. 국외 이전</h2>
<p>서비스 제공을 위해 아래와 같이 정보가 국외(또는 국외 클라우드)에서 처리됩니다. 모든 전송은 암호화된 통신(HTTPS)으로 이루어집니다.</p>
<ul>
<li><strong>Cloudflare, Inc. (미국 등 Cloudflare 데이터센터 소재국)</strong> — 이용 목적: 서비스 서버·데이터베이스 운영. 이전 항목: 위 3번의 정보, AI 요청에 실린 글(중계만 하고 저장하지 않음), 접속 정보. 이전 시기: 서비스를 이용할 때마다. 보관 기간: 위 3번과 같음.</li>
<li><strong>Google (미국)</strong> — ${googleDoesAi ? 'AI 교정·번역 처리(누를 때 그 입력란의 글), ' : ''}구독 확인(구매 토큰), 로그인 확인(구글이 발급한 로그인 증명). 이전 시기: 로그인하거나 구독을 확인할 때${googleDoesAi ? ', AI 버튼을 누를 때' : ''}. 보관: Google의 개인정보 처리방침에 따름.</li>
${aiAbroad}${aiProcessor}
<li><strong>GitHub, Inc. (미국)</strong> — 휴대폰에서 ‘고성능 번역’ 모델 받기를, PC 프로그램에서 ‘AI 킷’ 받기를 사용자가 눌렀을 때 그 파일(약 3GB, 둘이 같은 파일)을 내려받습니다. 받은 뒤 번역은 휴대폰·PC 안에서만 이루어집니다. 입력한 글이나 계정 정보는 보내지 않습니다(내려받는 과정에서 접속 IP 주소가 전달될 수 있습니다).</li>
<li><strong>Mozilla (미국)</strong> — PC 프로그램에서 사용자가 번역 언어 묶음 받기를 누를 때 그 파일을 내려받습니다. 입력한 글이나 계정 정보는 보내지 않습니다(내려받는 과정에서 접속 IP 주소가 전달될 수 있습니다).</li>
</ul>
<p><strong>거부 방법과 그 결과</strong>: AI 버튼을 누르지 않으면 AI 처리 업체로 글이 전송되지 않고, 로그인하지 않으면 로그인 정보가, 구독하지 않으면 구매 토큰이 전송되지 않습니다. 서비스 서버(Cloudflare)로의 이전은 AI·로그인·구독 기능에 필수이므로, 거부하시려면 그 기능을 쓰지 않으시면 됩니다. 이 경우에도 기기 안 교정은 그대로 쓸 수 있습니다.</p>

<h2 id="delete">7. 이용자의 권리와 행사 방법</h2>
<p>이용자(만 14세 미만이면 법정대리인)는 언제든 자기 개인정보의 <strong>열람, 정정, 삭제, 처리 정지</strong>를 요구할 수 있습니다.</p>
<ul>
<li><strong>계정 삭제(회원 탈퇴)</strong>: 앱 → 오른쪽 위 ☰ → 더보기 → <strong>회원 탈퇴</strong>, 또는 PC 프로그램에서 교정 창의 <strong>계정</strong> 단추(또는 알림 영역 아이콘 우클릭 → 계정) → 계정 창의 <strong>회원 탈퇴</strong>(로그인했을 때만 보입니다)를 누르면 서버의 계정 정보(3번의 계정 항목)가 즉시 삭제됩니다.</li>
<li><strong>그 밖의 요구</strong>: 앱의 더보기 → 고객센터로 메일을 보내 주십시오. 메일 밑에 붙는 설치 식별자로 해당 기기의 기록을 찾아 <strong>10일 안에</strong> 처리하고 결과를 알려 드립니다. 앱을 쓸 수 없으면 아래 문의처로 직접 보내셔도 됩니다.</li>
<li><strong>AI 전송을 멈추려면</strong>: AI 교정·번역 버튼을 누르지 않으면 글은 전송되지 않습니다. 기기 안 교정만으로도 쓸 수 있습니다.</li>
</ul>
<p>본인 확인이 필요한 경우 요청한 기기에서 보낸 메일인지(설치 식별자) 또는 로그인한 구글 계정인지 확인할 수 있습니다.</p>
<p><strong>구독은 회원 탈퇴로 해지되지 않습니다.</strong> 해지는 Google Play 스토어 → 결제 및 정기 결제 → 정기 결제에서 해 주십시오.</p>

<h2>8. 파기</h2>
<p>보관 기간(3번)이 끝나거나 탈퇴·삭제 요청을 받으면 서버 데이터베이스에서 <strong>지체 없이</strong> 지웁니다. 다만 서버 업체(Cloudflare)가 장애 복구를 위해 데이터베이스의 지난 상태를 자동으로 보관하므로, 지운 내용이 그 복구용 기록에 <strong>최대 30일</strong> 동안 남았다가 자동으로 사라집니다. 이 기록은 장애 복구 외의 목적으로 쓰지 않습니다. 하루 사용량 기록은 이틀 뒤 자동으로 지워집니다. 기기·PC에 저장된 정보는 로그아웃하거나 프로그램을 삭제할 때 지워집니다. 종이로 보관하는 개인정보는 없습니다.</p>

<h2>9. 안전성 확보 조치</h2>
<ul>
<li>앱·PC 프로그램과 서버 사이의 모든 통신은 암호화(HTTPS)됩니다.</li>
<li>AI 로 보낸 글은 서버에 저장하지 않습니다.</li>
<li>구글 계정 고유 번호는 되돌릴 수 없는 값(해시)으로 바꿔 저장하고, 기기 토큰도 서버에는 바꾼 값으로만 둡니다.</li>
<li>PC 프로그램은 기기 토큰·로그아웃 기록·이메일을 윈도우 암호화로 보호해 저장하고, 휴대폰의 토큰은 백업·기기 이전으로 복사되지 않습니다.</li>
<li>휴대폰의 클립보드 기록은 기기의 키 저장소 열쇠로 암호화해 저장하고, 하루가 지나면 지웁니다.</li>
<li>짧은 시간에 요청이 몰리는 남용은 요청 횟수 제한으로 막습니다.</li>
<li>서버의 비밀 키와 데이터베이스에는 운영자만 접근할 수 있습니다.</li>
</ul>

<h2>10. 만 14세 미만 아동</h2>
<p>서비스는 이름·연락처·생년월일을 받지 않으며, 로그인과 구독은 Google 계정과 Google Play 를 통해서만 이루어집니다. <strong>만 14세 미만 아동은 법정대리인의 동의를 받은 경우에만 로그인·구독 기능을 이용할 수 있습니다.</strong> 만 14세 미만 아동의 Google 계정은 Google 정책에 따라 법정대리인의 동의를 받아 만들어지고 법정대리인이 관리(Google 가족 링크)하며, 서비스는 이 절차로 법정대리인의 동의를 확인합니다. 법정대리인의 동의 없이 만 14세 미만 아동의 개인정보가 처리된 사실을 알게 되면 지체 없이 파기합니다. 법정대리인은 7번의 권리를 아동을 대신해 행사할 수 있습니다.</p>

<h2>11. 권한</h2>
<ul>
<li><strong>휴대폰</strong>: 인터넷 권한은 AI 교정·번역, 구독 확인, 로그인, 고성능 번역 모델 받기에만 쓰입니다. 네트워크 상태 권한은 그 모델을 와이파이로 받는지 가리는 데만 쓰입니다. 그 외에 연락처·위치·마이크 같은 권한은 요구하지 않습니다.</li>
<li><strong>PC 프로그램</strong>: 인터넷은 AI 교정·AI정밀번역, 구독 확인, 로그인, 번역 언어 묶음 내려받기, AI 킷(번역 모델) 내려받기(사용자가 받기를 누를 때만, GitHub)에만 쓰입니다. 키 소리 범위를 "모든 프로그램에서"로 켠 경우에만 윈도우에서 어느 키가 눌렸는지 신호를 받으며(5번), 고친 글을 붙여 넣을 때 클립보드를 씁니다.</li>
</ul>

<h2>12. 개인정보 보호책임자</h2>
${'' /* 법(개인정보 보호법 30조)은 책임자 "성명 또는 담당 부서의 명칭" 과 연락처를 요구한다.
      OPERATOR_NAME 이 없으면 담당 이름으로 적는다 — 사용자가 실명을 싣지 않기로 했다(2026-09-26). */}
<p>개인정보 처리에 관한 문의, 불만, 피해 구제는 아래로 연락해 주십시오.</p>
<ul>
<li>개인정보 보호책임자: ${env.OPERATOR_NAME ? escapeHtml(env.OPERATOR_NAME) : '맞춤법 키보드 고객지원 담당'}</li>
${env.CONTACT_EMAIL ? `<li>연락처: ${escapeHtml(env.CONTACT_EMAIL)}</li>` : ''}
</ul>
<p>개인정보 침해에 대한 신고나 상담은 아래 기관에도 할 수 있습니다.</p>
<ul>
<li>개인정보침해신고센터: (국번 없이) 118, privacy.kisa.or.kr</li>
<li>개인정보분쟁조정위원회: 1833-6972, www.kopico.go.kr</li>
<li>경찰청 사이버수사국: (국번 없이) 182, ecrm.police.go.kr</li>
</ul>

<h2>13. 변경</h2>
<p>이 방침이 바뀌면 이 페이지에 갱신하고 시행일을 고칩니다.</p>
<ul>
<li>2026-10-10: 휴대폰의 ‘고성능 번역’ 모델 받기(GitHub)와 권한, 번역 창의 "AI정밀번역"(누를 때만 전송)과 "전체번역"(휴대폰 안에서만), PC 프로그램의 회원 탈퇴 경로와 암호화해 저장하는 항목, PC 단추 이름("AI정밀번역")과 선택해서 받는 AI 킷(번역 모델)을 적었습니다.</li>
<li>2026-10-08: PC 프로그램 내용, 접속 IP 주소 처리, 국외 이전 세부(목적·항목·시기·거부 방법), 파기와 복구용 기록, 만 14세 미만 동의 절차를 보완하고, 휴대폰 클립보드 기록의 암호화·하루 뒤 삭제를 적었습니다.</li>
<li>2026-09-26: 처음 시행.</li>
</ul>`, PRIVACY_EFFECTIVE);
}

function termsPage(env) {
  return docPage(env, '서비스 이용약관', `
<h2>1. 서비스</h2>
<p>맞춤법 키보드(이하 "앱")는 한국어 맞춤법·띄어쓰기를 고쳐 주는 안드로이드 키보드입니다. 실시간 교정과 기기 안 전체 교정은 무료로 제공되며, AI 교정·번역은 프리미엄 구독자에게 제공됩니다.</p>

<h2>2. 프리미엄 구독</h2>
<ul>
<li>요금은 월 2,990원이며 Google Play 로 결제되고 해지할 때까지 매달 자동으로 갱신됩니다.</li>
<li>해지는 Google Play 스토어 → 결제 및 정기 결제 → 정기 결제에서 언제든 할 수 있습니다. 해지해도 이미 결제한 기간이 끝날 때까지는 계속 쓸 수 있습니다.</li>
<li>환불은 Google Play 환불 정책을 따릅니다.</li>
<li>과도한 사용으로 서비스 운영이 어려워지는 것을 막기 위해 하루 사용량 한도가 있습니다. 한도에 닿으면 다음 날까지 AI 기능이 제한될 수 있습니다.</li>
</ul>

<h2>3. 계정</h2>
<ul>
<li>로그인은 구독을 여러 기기에서 같이 쓰기 위한 선택 기능입니다. 로그인하지 않아도 앱과 구독을 쓸 수 있습니다.</li>
<li>한 계정에 연결할 수 있는 기기는 5대까지입니다. 계정을 다른 사람과 나눠 쓰면 안 됩니다.</li>
<li>회원 탈퇴는 앱의 더보기 화면에서 언제든 할 수 있으며, 탈퇴해도 구독은 해지되지 않습니다.</li>
</ul>

<h2>4. AI 결과</h2>
<p>AI 교정·번역 결과는 틀릴 수 있습니다. 계약서·공문처럼 중요한 글은 보내기 전에 직접 확인해 주십시오. 앱은 AI 결과로 생긴 손해에 대해 법이 허용하는 범위에서 책임지지 않습니다.</p>

<h2>5. 하지 말아야 할 것</h2>
<ul>
<li>앱이나 서버를 뜯어 고쳐 결제 없이 프리미엄 기능을 쓰는 행위</li>
<li>자동화된 방법으로 서버에 대량으로 요청하는 행위</li>
<li>다른 사람의 계정이나 구매 정보를 쓰는 행위</li>
</ul>
<p>이런 행위가 확인되면 해당 계정이나 구독의 AI 기능 이용을 제한할 수 있습니다.</p>

<h2>6. 서비스 변경과 중단</h2>
<p>기능이나 요금이 바뀌면 적용 전에 앱이나 이 페이지로 알립니다. 요금이 오르면 Google Play 가 구독자에게 따로 동의를 받습니다.</p>

<h2>7. 준거법</h2>
<p>이 약관은 대한민국 법을 따릅니다.</p>`, TERMS_EFFECTIVE);
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

