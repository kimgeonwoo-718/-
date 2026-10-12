/**
 * 고객센터 — 앱 안에서 보내는 의견(글·사진).
 *
 * 예전엔 고객센터를 누르면 메일 앱이 열렸다. "메일이라 부담스럽다, 앱에서 글이나 사진을 넣어 바로 보내게" 라는 말에
 * (2026-10-12) 앱 안 화면(FeedbackActivity)에서 여기로 보내 쌓아 두고, 운영자가 `/feedback?token=…` 에서 읽게 했다.
 *
 * **AI 와 상관없는 길이다.** 값이 안 들고, AI 키가 없거나 비상 정지(AI_DISABLED)해도 받는다 — index.js 에서 키 검사보다 앞에 있다.
 *
 * 무료 등급에 맞춘 것:
 * - **CPU 요청당 10ms.** 사진을 여기서 풀거나 줄이지 않는다. 앱이 줄여(긴 변 1280, 300KB 안쪽) base64 로 보내고,
 *   여기서는 앞머리 몇 바이트로 JPEG·PNG 인지만 보고 글자 그대로 넣는다(D1 은 BLOB 을 숫자 배열로 주고받아 비싸다).
 * - **D1 하나에 500MB.** 이 데이터베이스는 AI 한도·계정과 같이 쓴다. 꽉 차면 한도가 안 써져 AI 가 멈춘다. 그래서 사진은
 *   합쳐 [PHOTO_BUDGET] 까지만 받고, 넘으면 **글만 받고** 사진이 빠졌다고 알려 준다. 운영자가 다 본 의견을 지우면 다시 받는다.
 *
 * 남용: 서버 주소는 APK 안에 있어 누구나 두드릴 수 있다. 설치 ID·IP·전체에 하루 횟수를 건다(usage 표 — 이틀 뒤 크론이 치운다).
 */
import { kstDay, validInstallId } from './quota.js';
import { randomToken, sha256Hex } from './account.js';

/** 글 길이(글자). 앱 입력칸도 같은 값으로 막는다(FeedbackActivity.MAX_TEXT). */
export const MAX_TEXT = 2000;
/** 사진 수. 앱도 같다(FeedbackActivity.MAX_PHOTOS). */
export const MAX_PHOTOS = 3;
/** 사진 한 장의 base64 글자 수. 앱은 300KB(글자로 40만) 안쪽으로 줄여 보낸다 — 조금 넉넉히 받는다. */
export const MAX_PHOTO_CHARS = 420_000;
const MAX_REPLY = 120;
const MAX_META = 80;
/** 본문 전체(바이트). 사진 셋 + 글 + 여유. */
const MAX_BODY_BYTES = MAX_PHOTOS * MAX_PHOTO_CHARS + 64 * 1024;

/** 쌓아 둘 사진 글자 수 합계. D1 500MB 가운데 이만큼만 의견 사진에 쓴다. */
export const PHOTO_BUDGET = 150 * 1024 * 1024;

export const PER_INSTALL_PER_DAY = 5;
export const PER_IP_PER_DAY = 20;
export const ALL_PER_DAY = 100;

/** 이만큼 지난 의견은 크론이 지운다. 처리방침 3번의 "180일" 과 같아야 한다. */
export const KEEP_DAYS = 180;

/** 운영자 목록 한 쪽에 보이는 수. */
export const PAGE_SIZE = 30;

const PHOTO_TYPES = new Set(['image/jpeg', 'image/png']);
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/**
 * 의견 하나를 받는다. 설치 ID 는 `X-Install-Id` 머리에, 본문은 JSON —
 * `{ text, reply?, app?, device?, os?, photos?: [base64, …] }`. 글이나 사진 중 하나는 있어야 한다.
 *
 * 답: `{ ok: true, id }`. 사진 자리가 모자라 글만 받았으면 `photosDropped: 장수` 가 붙는다.
 */
export async function receiveFeedback(request, env, nowMs) {
  const installId = request.headers.get('x-install-id') ?? '';
  if (!validInstallId(installId)) return refuse(400, 'invalid_install_id');

  const raw = await readCapped(request, MAX_BODY_BYTES);
  if (raw === null) return refuse(413, 'too_large');

  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    return refuse(400, 'bad_request');
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) return refuse(400, 'bad_request');

  const text = typeof body.text === 'string' ? clean(body.text).trim() : '';
  if (text.length > MAX_TEXT) return refuse(413, 'text_too_long');

  const list = body.photos ?? [];
  if (!Array.isArray(list)) return refuse(400, 'bad_request');
  if (list.length > MAX_PHOTOS) return refuse(400, 'too_many_photos');
  let photos = [];
  for (const data of list) {
    if (typeof data !== 'string') return refuse(400, 'bad_photo');
    if (data.length > MAX_PHOTO_CHARS) return refuse(413, 'photo_too_large');
    const type = photoType(data);
    if (!type) return refuse(400, 'bad_photo');
    photos.push({ type, data });
  }
  if (!text && photos.length === 0) return refuse(400, 'empty');

  // 하루 횟수. 모양이 맞는 것만 센다 — 잘못 만든 요청 때문에 그날 한도를 잃지 않게.
  // IP 는 그대로 두지 않고 해시로 센다(처리방침 3번, correct() 와 같은 방식).
  const day = kstDay(nowMs);
  const ip = request.headers.get('cf-connecting-ip') ?? '';
  if (ip && (await bump(env.DB, 'fb:ip:' + (await sha256Hex('ip:' + ip)), day)) > PER_IP_PER_DAY) {
    return refuse(429, 'feedback_limit');
  }
  if ((await bump(env.DB, 'fb:in:' + installId, day)) > PER_INSTALL_PER_DAY) return refuse(429, 'feedback_limit');
  if ((await bump(env.DB, 'fb:all', day)) > ALL_PER_DAY) return refuse(429, 'feedback_limit');

  // 사진 자리. 넘으면 글만 받는다 — 글까지 버리면 하려던 말을 잃는다.
  let dropped = 0;
  if (photos.length > 0) {
    const used = await env.DB.prepare('SELECT COALESCE(SUM(size), 0) AS chars FROM feedback_photos').first();
    const incoming = photos.reduce((sum, p) => sum + p.data.length, 0);
    if (Number(used?.chars ?? 0) + incoming > PHOTO_BUDGET) {
      dropped = photos.length;
      photos = [];
    }
  }
  // 사진만 보냈는데 그 사진이 빠지면 남는 것이 없다. 받았다고 하면 거짓말이 된다.
  if (!text && photos.length === 0) return refuse(507, 'photo_storage_full');

  const id = randomToken(16);
  const replyTo = shortText(body.reply, MAX_REPLY);
  await env.DB.batch([
    env.DB.prepare(
      'INSERT INTO feedback (id, created_at, install_id, body, reply_to, app_version, device, os, photos) ' +
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
    ).bind(id, nowMs, installId, text, replyTo, shortText(body.app, MAX_META), shortText(body.device, MAX_META),
      shortText(body.os, MAX_META), photos.length),
    ...photos.map((p, i) =>
      env.DB.prepare('INSERT INTO feedback_photos (feedback_id, idx, type, size, data) VALUES (?, ?, ?, ?, ?)')
        .bind(id, i, p.type, p.data.length, p.data)
    ),
  ]);
  return respond(200, dropped > 0 ? { ok: true, id, photosDropped: dropped } : { ok: true, id });
}

/**
 * 운영자 쪽: 받은 의견 목록(`GET /feedback`), 사진(`GET /feedback/photo`), 지우기(`GET` 은 묻는 쪽, `POST` 가 지운다).
 *
 * 열쇠는 `FEEDBACK_TOKEN` 비밀값, 없으면 `/stats` 의 `STATS_TOKEN`. 둘 다 없거나 틀리면 /stats 와 같이 없는 길인 척한다(404).
 * 받은 글에는 사용자 개인 정보(답장 주소·사진)가 들어 있다 — 열쇠를 남에게 보이지 마라.
 */
export async function feedbackAdmin(request, env, url) {
  const token = adminToken(env);
  if (!token) return refuse(404, 'not_found');
  let form = null;
  if (request.method === 'POST') {
    // 지우기 양식은 짧다. 큰 본문은 읽지도 않는다.
    if (Number(request.headers.get('content-length') ?? 0) > 4096) return refuse(404, 'not_found');
    form = await request.formData().catch(() => null);
    if (!form) return refuse(404, 'not_found');
  }
  const given = form ? String(form.get('token') ?? '') : url.searchParams.get('token');
  if (given !== token) return refuse(404, 'not_found');

  if (request.method === 'GET' && url.pathname === '/feedback') return listPage(env, token, url);
  if (request.method === 'GET' && url.pathname === '/feedback/photo') return photo(env, url);
  if (request.method === 'GET' && url.pathname === '/feedback/delete') return confirmPage(env, token, url);
  if (request.method === 'POST' && url.pathname === '/feedback/delete') return remove(env, token, form);
  return refuse(404, 'not_found');
}

/** [KEEP_DAYS] 지난 의견을 사진째 지운다. 크론(sweep)이 하루 한 번 부른다. */
export async function sweepFeedback(db, nowMs) {
  const cutoff = nowMs - KEEP_DAYS * 86_400_000;
  await db
    .prepare('DELETE FROM feedback_photos WHERE feedback_id IN (SELECT id FROM feedback WHERE created_at < ?)')
    .bind(cutoff)
    .run();
  await db.prepare('DELETE FROM feedback WHERE created_at < ?').bind(cutoff).run();
}

// --- 운영자 화면 -------------------------------------------------------------------

function adminToken(env) {
  return String(env.FEEDBACK_TOKEN ?? '').trim() || String(env.STATS_TOKEN ?? '').trim();
}

async function listPage(env, token, url) {
  const before = Number(url.searchParams.get('before')) || Number.MAX_SAFE_INTEGER;
  const { results = [] } = await env.DB.prepare(
    'SELECT id, created_at, install_id, body, reply_to, app_version, device, os, photos FROM feedback ' +
      'WHERE created_at < ? ORDER BY created_at DESC LIMIT ?'
  )
    .bind(before, PAGE_SIZE)
    .all();
  const count = await env.DB.prepare('SELECT COUNT(*) AS n FROM feedback').first();
  const used = await env.DB.prepare('SELECT COALESCE(SUM(size), 0) AS chars FROM feedback_photos').first();

  const key = encodeURIComponent(token);
  const megabytes = (chars) => (chars / 1024 / 1024).toFixed(1);
  const items = results.map((row) => itemHtml(row, key)).join('\n');
  const more =
    results.length === PAGE_SIZE
      ? `<p><a href="/feedback?token=${key}&amp;before=${Number(results[results.length - 1].created_at)}">더 보기 ›</a></p>`
      : '';
  return page(
    '받은 의견',
    `<h1>받은 의견</h1>
<p class="muted">모두 ${Number(count?.n ?? 0)}개 · 사진 ${megabytes(Number(used?.chars ?? 0))}MB / ${megabytes(PHOTO_BUDGET)}MB
(다 차면 새 사진은 빼고 글만 받는다 — 다 본 의견은 지워 주는 것이 좋다) · ${KEEP_DAYS}일 지나면 저절로 지워진다</p>
${items || '<p class="muted">아직 받은 의견이 없다.</p>'}
${more}`
  );
}

function itemHtml(row, key) {
  const id = encodeURIComponent(row.id);
  const meta = [
    kstTime(row.created_at),
    row.app_version ? `앱 ${row.app_version}` : '',
    row.device ?? '',
    row.os ? `Android ${row.os}` : '',
  ]
    .filter(Boolean)
    .map(escapeHtml)
    .join(' · ');
  const reply = row.reply_to ? `<div class="reply">답장: ${contactHtml(row.reply_to)}</div>` : '';
  const photos = Array.from({ length: Number(row.photos) || 0 }, (_, n) => {
    const src = `/feedback/photo?token=${key}&amp;id=${id}&amp;n=${n}`;
    return `<a href="${src}" target="_blank" rel="noreferrer"><img src="${src}" loading="lazy" alt="사진 ${n + 1}"></a>`;
  }).join('');
  return `<article>
<div class="meta">${meta}</div>${reply}
<p class="body">${row.body ? escapeHtml(row.body) : '<span class="muted">(글 없음)</span>'}</p>
${photos ? `<div class="photos">${photos}</div>` : ''}
<div class="foot"><span>설치 ID ${escapeHtml(row.install_id)}</span><a class="del" href="/feedback/delete?token=${key}&amp;id=${id}">지우기</a></div>
</article>`;
}

/** 답장 주소. 메일 주소 꼴이면 누르면 메일이 열리게, 아니면 글자 그대로. */
function contactHtml(value) {
  if (/^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$/.test(value)) {
    return `<a href="mailto:${encodeURIComponent(value)}">${escapeHtml(value)}</a>`;
  }
  return escapeHtml(value);
}

async function photo(env, url) {
  const n = Number(url.searchParams.get('n'));
  if (!Number.isInteger(n) || n < 0 || n >= MAX_PHOTOS) return refuse(404, 'not_found');
  const row = await env.DB.prepare('SELECT type, data FROM feedback_photos WHERE feedback_id = ? AND idx = ?')
    .bind(url.searchParams.get('id') ?? '', n)
    .first();
  if (!row || !PHOTO_TYPES.has(row.type)) return refuse(404, 'not_found');
  const binary = atob(row.data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Response(bytes, {
    status: 200,
    headers: {
      'content-type': row.type,
      // 사용자가 올린 파일이다. 브라우저가 내용을 보고 HTML 로 바꿔 읽지 못하게.
      'x-content-type-options': 'nosniff',
      'content-security-policy': "default-src 'none'",
      'cache-control': 'private, max-age=3600',
      'referrer-policy': 'no-referrer',
    },
  });
}

async function confirmPage(env, token, url) {
  const row = await env.DB.prepare('SELECT id, created_at, body FROM feedback WHERE id = ?')
    .bind(url.searchParams.get('id') ?? '')
    .first();
  if (!row) return refuse(404, 'not_found');
  const preview = row.body.length > 200 ? row.body.slice(0, 200) + '…' : row.body;
  return page(
    '의견 지우기',
    `<h1>이 의견을 지울까?</h1>
<p class="muted">${escapeHtml(kstTime(row.created_at))} · 사진도 같이 지워진다. 되돌릴 수 없다.</p>
<p class="body">${escapeHtml(preview)}</p>
<form method="post" action="/feedback/delete">
<input type="hidden" name="token" value="${escapeHtml(token)}">
<input type="hidden" name="id" value="${escapeHtml(row.id)}">
<button type="submit">지우기</button> <a href="/feedback?token=${encodeURIComponent(token)}">그만두기</a>
</form>`
  );
}

async function remove(env, token, form) {
  const id = String(form.get('id') ?? '');
  await env.DB.batch([
    env.DB.prepare('DELETE FROM feedback_photos WHERE feedback_id = ?').bind(id),
    env.DB.prepare('DELETE FROM feedback WHERE id = ?').bind(id),
  ]);
  return new Response(null, {
    status: 303,
    headers: { location: '/feedback?token=' + encodeURIComponent(token), 'referrer-policy': 'no-referrer' },
  });
}

function page(title, body) {
  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>${title}</title>
<style>body{font-family:sans-serif;max-width:760px;margin:24px auto;padding:0 16px;line-height:1.6;color:#191f28}h1{font-size:20px}
article{border:1px solid #e5e8eb;border-radius:12px;padding:14px 16px;margin:12px 0}.meta,.muted,.foot,.reply{color:#6b7684;font-size:13px}
.body{white-space:pre-wrap;word-break:break-word;margin:8px 0;font-size:15px}.photos img{max-width:150px;max-height:240px;border-radius:8px;margin:4px 8px 0 0;border:1px solid #e5e8eb}
.foot{display:flex;justify-content:space-between;gap:12px;margin-top:8px;word-break:break-all}.del{color:#f04452;white-space:nowrap}
button{background:#f04452;color:#fff;border:0;border-radius:10px;padding:10px 18px;font-size:15px}</style>
</head><body>
${body}
</body></html>`;
  return new Response(html, {
    status: 200,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
      // 사용자가 쓴 글이 들어가는 화면이다. 이스케이프가 한 군데 새더라도 스크립트는 안 돈다.
      'content-security-policy':
        "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
      // 주소에 열쇠가 들어 있다. 다른 곳으로 넘어갈 때 따라가지 않게.
      'referrer-policy': 'no-referrer',
      'x-robots-tag': 'noindex',
    },
  });
}

// --- 작은 것들 --------------------------------------------------------------------

/**
 * 본문을 [max] 바이트까지만 읽는다. 넘으면 null.
 *
 * content-length 가 있으면 먼저 보고, 없어도(나눠 보내기) 읽으면서 센다 — 머리에 적힌 값만 믿으면 머리 없이 큰 본문을 끝까지 받는다.
 */
async function readCapped(request, max) {
  if (Number(request.headers.get('content-length') ?? 0) > max) return null;
  if (!request.body) return '';
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const all = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    all.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(all);
}

/** base64 사진의 종류. 앞 12바이트로 JPEG·PNG 만 알아본다. 다른 것이거나 base64 가 아니면 null. */
function photoType(data) {
  if (data.length < 16 || data.length % 4 !== 0 || !BASE64.test(data)) return null;
  const head = atob(data.slice(0, 16));
  if (head.charCodeAt(0) === 0xff && head.charCodeAt(1) === 0xd8 && head.charCodeAt(2) === 0xff) return 'image/jpeg';
  if (head.startsWith('\x89PNG\r\n\x1a\n')) return 'image/png';
  return null;
}

/** 줄바꿈·탭 말고 제어문자를 뺀다. */
function clean(text) {
  return text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '');
}

/** 짧은 칸(답장 주소·앱 버전·기기). 없거나 비면 null — D1 은 undefined 를 못 받는다. */
function shortText(value, max) {
  if (typeof value !== 'string') return null;
  const trimmed = clean(value).replace(/\s+/g, ' ').trim().slice(0, max);
  return trimmed || null;
}

/** 그날의 횟수를 1 올리고 올린 뒤의 값. index.js 의 reserve() 와 같은 문장이다. */
async function bump(db, id, day) {
  const row = await db
    .prepare(
      'INSERT INTO usage (id, day, used) VALUES (?, ?, ?) ' +
        'ON CONFLICT(id, day) DO UPDATE SET used = used + excluded.used RETURNING used'
    )
    .bind(id, day, 1)
    .first();
  return Number(row?.used ?? 1);
}

function kstTime(ms) {
  return new Date(Number(ms) + 9 * 3_600_000).toISOString().slice(0, 16).replace('T', ' ') + ' (한국)';
}

/** index.js 의 json()·fail() 과 같은 모양이다. 앱은 error.message 를 읽는다. */
function respond(status, value) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

function refuse(status, code) {
  return respond(status, { error: { code: status, message: code, status: code.toUpperCase() } });
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
