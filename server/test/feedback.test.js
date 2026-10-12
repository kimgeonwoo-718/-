import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import {
  sweepFeedback,
  MAX_PHOTOS,
  MAX_PHOTO_CHARS,
  MAX_TEXT,
  PHOTO_BUDGET,
  PER_INSTALL_PER_DAY,
  PER_IP_PER_DAY,
  ALL_PER_DAY,
  KEEP_DAYS,
  PAGE_SIZE,
} from '../src/feedback.js';
import { fakeDb } from './fakeDb.js';

const INSTALL = '3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f';
const NOON_KST = Date.UTC(2026, 0, 1, 3, 0);
const DAY = 86_400_000;

function env(overrides = {}) {
  return { DB: fakeDb(), GEMINI_API_KEY: 'server-key', ...overrides };
}

/** 앞머리만 맞춘 가짜 사진. 서버는 앞 12바이트로 종류만 본다. */
function jpeg(size = 24) {
  const bytes = Buffer.alloc(size, 7);
  bytes.set([0xff, 0xd8, 0xff, 0xe0]);
  return bytes.toString('base64');
}

function png(size = 24) {
  const bytes = Buffer.alloc(size, 7);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return bytes.toString('base64');
}

function send(body, { install = INSTALL, ip = '203.0.113.7' } = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const headers = { 'content-type': 'application/json; charset=utf-8', 'cf-connecting-ip': ip };
  if (install !== null) headers['x-install-id'] = install;
  return new Request('https://spell.test/v1/feedback', { method: 'POST', headers, body: text });
}

const at = (ms) => ({ now: () => ms });

// --- 받기 -------------------------------------------------------------------------

test('글만 보낸 의견을 받아 둔다 — 앱 버전·기기·답장 주소와 함께', async () => {
  const e = env();
  const res = await handle(
    send({ text: '  되 돼를 잘못 고쳐요\n두 번째 줄  ', reply: ' me@example.com ', app: '1.0.42', device: 'samsung SM-S918N', os: '14' }),
    e,
    at(NOON_KST)
  );
  assert.equal(res.status, 200);
  const { ok, id } = await res.json();
  assert.equal(ok, true);
  assert.match(id, /^[0-9a-f]{32}$/);
  const row = e.DB.feedback.get(id);
  assert.equal(row.body, '되 돼를 잘못 고쳐요\n두 번째 줄', '앞뒤 공백은 지우고 줄바꿈은 둔다');
  assert.equal(row.reply_to, 'me@example.com');
  assert.equal(row.install_id, INSTALL);
  assert.equal(row.app_version, '1.0.42');
  assert.equal(row.device, 'samsung SM-S918N');
  assert.equal(row.os, '14');
  assert.equal(row.photos, 0);
  assert.equal(row.created_at, NOON_KST);
});

test('빈 칸은 null 로 넣는다 — D1 은 undefined 를 못 받는다', async () => {
  const e = env();
  const res = await handle(send({ text: '좋아요' }), e, at(NOON_KST));
  assert.equal(res.status, 200);
  const row = [...e.DB.feedback.values()][0];
  assert.equal(row.reply_to, null);
  assert.equal(row.app_version, null);
  assert.equal(row.device, null);
  assert.equal(row.os, null);
});

test('사진은 JPEG·PNG 만, 글자(base64) 그대로 넣는다', async () => {
  const e = env();
  const photos = [jpeg(), png(), jpeg(300)];
  const res = await handle(send({ text: '화면 보세요', photos }), e, at(NOON_KST));
  assert.equal(res.status, 200);
  const { id } = await res.json();
  assert.equal(e.DB.feedback.get(id).photos, 3);
  assert.equal(e.DB.feedbackPhotos.get(`${id}|0`).type, 'image/jpeg');
  assert.equal(e.DB.feedbackPhotos.get(`${id}|1`).type, 'image/png');
  assert.equal(e.DB.feedbackPhotos.get(`${id}|2`).data, photos[2]);
  assert.equal(e.DB.feedbackPhotos.get(`${id}|2`).size, photos[2].length);
});

test('사진만 보내도 된다', async () => {
  const e = env();
  const res = await handle(send({ text: '', photos: [jpeg()] }), e, at(NOON_KST));
  assert.equal(res.status, 200);
  const { id } = await res.json();
  assert.equal(e.DB.feedback.get(id).body, '');
  assert.equal(e.DB.feedback.get(id).photos, 1);
});

test('AI 키가 없어도, AI 를 비상 정지해도 의견은 받는다', async () => {
  for (const overrides of [{ GEMINI_API_KEY: '' }, { AI_DISABLED: '1' }]) {
    const res = await handle(send({ text: '안녕하세요' }), env(overrides), at(NOON_KST));
    assert.equal(res.status, 200, JSON.stringify(overrides));
  }
});

test('모양이 틀린 의견은 받지 않고, 그날 횟수도 깎지 않는다', async () => {
  const e = env();
  const cases = [
    [send({ text: '설치 ID 없음' }, { install: null }), 400, 'invalid_install_id'],
    [send({ text: '설치 ID 이상함' }, { install: 'a b' }), 400, 'invalid_install_id'],
    [send('{깨진 JSON'), 400, 'bad_request'],
    [send('[1,2]'), 400, 'bad_request'],
    [send({ text: '   ' }), 400, 'empty'],
    [send({ text: '가'.repeat(MAX_TEXT + 1) }), 413, 'text_too_long'],
    [send({ text: '사진', photos: 'abc' }), 400, 'bad_request'],
    [send({ text: '사진', photos: Array(MAX_PHOTOS + 1).fill(jpeg()) }), 400, 'too_many_photos'],
    [send({ text: '사진', photos: [123] }), 400, 'bad_photo'],
    // 사진이 아닌 것(GIF·HTML)과 base64 가 아닌 것
    [send({ text: '사진', photos: [Buffer.from('GIF89a-------------').toString('base64')] }), 400, 'bad_photo'],
    [send({ text: '사진', photos: [Buffer.from('<html><script>alert(1)</script>').toString('base64')] }), 400, 'bad_photo'],
    [send({ text: '사진', photos: ['/9j/' + '!'.repeat(28)] }), 400, 'bad_photo'],
    [send({ text: '사진', photos: ['/9j/4A'] }), 400, 'bad_photo'],
  ];
  for (const [request, status, code] of cases) {
    const res = await handle(request, e, at(NOON_KST));
    assert.equal(res.status, status, code);
    assert.equal((await res.json()).error.message, code);
  }
  assert.equal(e.DB.feedback.size, 0);
  assert.equal(e.DB.usage.size, 0, '모양이 틀린 요청은 하루 횟수에 안 들어간다');
});

test('사진 한 장이 상한을 넘으면 photo_too_large', async () => {
  const e = env();
  const big = jpeg(Math.floor((MAX_PHOTO_CHARS / 4) * 3) + 3);
  assert.ok(big.length > MAX_PHOTO_CHARS && big.length < MAX_PHOTO_CHARS + 100);
  const res = await handle(send({ text: '큰 사진', photos: [big] }), e, at(NOON_KST));
  assert.equal(res.status, 413);
  assert.equal((await res.json()).error.message, 'photo_too_large');
});

test('본문이 상한을 넘으면 끝까지 읽지 않고 413 — 길이 머리가 없어도', async () => {
  const e = env();
  const huge = JSON.stringify({ text: '', photos: [jpeg(400_000), jpeg(400_000), jpeg(400_000), jpeg(400_000)] });
  // 길이 머리 없이 나눠 보내는 본문(ReadableStream)
  const stream = new ReadableStream({
    start(controller) {
      const bytes = new TextEncoder().encode(huge);
      for (let i = 0; i < bytes.length; i += 65536) controller.enqueue(bytes.slice(i, i + 65536));
      controller.close();
    },
  });
  const request = new Request('https://spell.test/v1/feedback', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-install-id': INSTALL },
    body: stream,
    duplex: 'half',
  });
  const res = await handle(request, e, at(NOON_KST));
  assert.equal(res.status, 413);
  assert.equal((await res.json()).error.message, 'too_large');

  // 길이 머리가 상한을 넘게 적혀 있으면 읽기 전에 막는다.
  const declared = new Request('https://spell.test/v1/feedback', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-install-id': INSTALL, 'content-length': String(10 * 1024 * 1024) },
    body: '{}',
  });
  assert.equal((await handle(declared, e, at(NOON_KST))).status, 413);
});

test(`설치 ID 하나는 하루 ${PER_INSTALL_PER_DAY}번까지, 다음 날 다시 된다`, async () => {
  const e = env();
  for (let i = 0; i < PER_INSTALL_PER_DAY; i++) {
    assert.equal((await handle(send({ text: `의견 ${i}` }), e, at(NOON_KST))).status, 200);
  }
  const over = await handle(send({ text: '하나 더' }), e, at(NOON_KST));
  assert.equal(over.status, 429);
  assert.equal((await over.json()).error.message, 'feedback_limit');
  assert.equal((await handle(send({ text: '다음 날' }), e, at(NOON_KST + DAY))).status, 200);
});

test(`IP 하나는 하루 ${PER_IP_PER_DAY}번까지 — 설치 ID 를 바꿔 가며 보내도`, async () => {
  const e = env();
  for (let i = 0; i < PER_IP_PER_DAY; i++) {
    const install = `install-${String(i).padStart(4, '0')}`;
    assert.equal((await handle(send({ text: `의견 ${i}` }, { install }), e, at(NOON_KST))).status, 200);
  }
  const over = await handle(send({ text: '하나 더' }, { install: 'install-9999' }), e, at(NOON_KST));
  assert.equal(over.status, 429);
  // IP 는 해시로만 센다(처리방침 3번).
  for (const id of [...e.DB.usage.keys()]) assert.ok(!id.includes('203.0.113.7'), `IP 가 그대로 남았다: ${id}`);
});

test(`전체는 하루 ${ALL_PER_DAY}번까지`, async () => {
  const e = env();
  for (let i = 0; i < ALL_PER_DAY; i++) {
    const install = `install-${String(i).padStart(4, '0')}`;
    const ip = `198.51.100.${i % 250}`;
    assert.equal((await handle(send({ text: `의견 ${i}` }, { install, ip }), e, at(NOON_KST))).status, 200);
  }
  const over = await handle(send({ text: '하나 더' }, { install: 'install-9999', ip: '192.0.2.1' }), e, at(NOON_KST));
  assert.equal(over.status, 429);
});

test('사진 자리가 다 차면 글만 받고 빠진 장수를 알려 준다', async () => {
  const e = env();
  e.DB.feedbackPhotos.set('old|0', { feedback_id: 'old', idx: 0, type: 'image/jpeg', size: PHOTO_BUDGET - 10, data: 'x' });
  const res = await handle(send({ text: '사진 둘', photos: [jpeg(), jpeg()] }), e, at(NOON_KST));
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.photosDropped, 2);
  assert.equal(e.DB.feedback.get(body.id).body, '사진 둘');
  assert.equal(e.DB.feedback.get(body.id).photos, 0);
  assert.equal(e.DB.feedbackPhotos.size, 1);

  // 사진만 보냈는데 자리가 없으면 받았다고 하지 않는다.
  const only = await handle(send({ photos: [jpeg()] }), e, at(NOON_KST));
  assert.equal(only.status, 507);
  assert.equal((await only.json()).error.message, 'photo_storage_full');
});

// --- 운영자 화면 ------------------------------------------------------------------

async function seed(e, items) {
  const ids = [];
  for (const [i, item] of items.entries()) {
    const res = await handle(send(item, { install: `install-${String(i).padStart(4, '0')}`, ip: `192.0.2.${i}` }), e, at(NOON_KST + i * 1000));
    assert.equal(res.status, 200);
    ids.push((await res.json()).id);
  }
  return ids;
}

test('열쇠가 없거나 틀리면 운영자 화면은 없는 길인 척한다(404)', async () => {
  const e = env();
  await seed(e, [{ text: '비밀 의견' }]);
  for (const [url, environment] of [
    ['https://spell.test/feedback', e],
    ['https://spell.test/feedback?token=', e],
    ['https://spell.test/feedback?token=anything', e],
    ['https://spell.test/feedback?token=nope', { ...e, FEEDBACK_TOKEN: 's3cret' }],
    ['https://spell.test/feedback/photo?token=nope&id=x&n=0', { ...e, FEEDBACK_TOKEN: 's3cret' }],
  ]) {
    const res = await handle(new Request(url), environment, at(NOON_KST));
    assert.equal(res.status, 404, url);
    assert.ok(!(await res.text()).includes('비밀 의견'));
  }
});

test('FEEDBACK_TOKEN 이 맞으면 목록을 보여 준다 — 사용자 글은 이스케이프', async () => {
  const e = { ...env(), FEEDBACK_TOKEN: 's3cret' };
  await seed(e, [
    { text: '<script>alert(1)</script> 되 돼', reply: 'me@example.com', app: '1.0.42', device: 'Pixel 8', os: '15', photos: [jpeg(), png()] },
    { text: '두 번째', reply: '카톡 아이디 abc"><img src=x>' },
  ]);
  const res = await handle(new Request('https://spell.test/feedback?token=s3cret'), e, at(NOON_KST));
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /text\/html/);
  assert.match(res.headers.get('content-security-policy'), /default-src 'none'/);
  assert.equal(res.headers.get('referrer-policy'), 'no-referrer');
  const html = await res.text();
  assert.ok(!html.includes('<script>alert(1)</script>'), '사용자 글이 그대로 들어갔다');
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt; 되 돼'));
  assert.ok(!html.includes('"><img src=x>'), '답장 칸이 그대로 들어갔다');
  assert.ok(html.indexOf('두 번째') < html.indexOf('되 돼'), '새것이 위에');
  assert.match(html, /mailto:me%40example\.com/);
  assert.match(html, /앱 1\.0\.42 · Pixel 8 · Android 15/);
  assert.match(html, /모두 2개/);
  assert.equal((html.match(/<img src="\/feedback\/photo\?/g) ?? []).length, 2);
});

test('STATS_TOKEN 만 있으면 그것으로 연다', async () => {
  const e = { ...env(), STATS_TOKEN: 'stats' };
  await seed(e, [{ text: '하나' }]);
  const res = await handle(new Request('https://spell.test/feedback?token=stats'), e, at(NOON_KST));
  assert.equal(res.status, 200);
  assert.match(await res.text(), /하나/);
});

test('사진은 받은 그대로 풀어 그 종류로 내준다', async () => {
  const e = { ...env(), FEEDBACK_TOKEN: 's3cret' };
  const photo = png(40);
  const [id] = await seed(e, [{ text: '사진', photos: [jpeg(), photo] }]);
  const res = await handle(new Request(`https://spell.test/feedback/photo?token=s3cret&id=${id}&n=1`), e, at(NOON_KST));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'image/png');
  assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
  assert.deepEqual(Buffer.from(await res.arrayBuffer()), Buffer.from(photo, 'base64'));
  for (const n of ['2', '-1', 'x', '1.5']) {
    const missing = await handle(new Request(`https://spell.test/feedback/photo?token=s3cret&id=${id}&n=${n}`), e, at(NOON_KST));
    assert.equal(missing.status, 404, n);
  }
});

test('지우기는 묻고 나서(POST) 사진째 지운다', async () => {
  const e = { ...env(), FEEDBACK_TOKEN: 's3cret' };
  const [keep, gone] = await seed(e, [{ text: '남길 것' }, { text: '지울 것', photos: [jpeg(), jpeg()] }]);

  const ask = await handle(new Request(`https://spell.test/feedback/delete?token=s3cret&id=${gone}`), e, at(NOON_KST));
  assert.equal(ask.status, 200);
  const askHtml = await ask.text();
  assert.match(askHtml, /지울 것/);
  assert.match(askHtml, /method="post"/);
  assert.equal(e.DB.feedback.size, 2, '묻는 화면만으로는 안 지운다');

  const wrong = await handle(
    new Request('https://spell.test/feedback/delete', { method: 'POST', body: new URLSearchParams({ token: 'nope', id: gone }) }),
    e,
    at(NOON_KST)
  );
  assert.equal(wrong.status, 404);
  assert.equal(e.DB.feedback.size, 2);

  const done = await handle(
    new Request('https://spell.test/feedback/delete', { method: 'POST', body: new URLSearchParams({ token: 's3cret', id: gone }) }),
    e,
    at(NOON_KST)
  );
  assert.equal(done.status, 303);
  assert.equal(done.headers.get('location'), '/feedback?token=s3cret');
  assert.ok(e.DB.feedback.has(keep));
  assert.ok(!e.DB.feedback.has(gone));
  assert.equal([...e.DB.feedbackPhotos.keys()].filter((k) => k.startsWith(gone)).length, 0);
});

test(`목록은 ${PAGE_SIZE}개씩, 더 보기로 넘긴다`, async () => {
  const e = { ...env(), FEEDBACK_TOKEN: 's3cret' };
  for (let i = 0; i < PAGE_SIZE + 2; i++) {
    e.DB.feedback.set(`id${i}`, { created_at: NOON_KST + i, install_id: INSTALL, body: `의견 ${i}번`, reply_to: null, app_version: null, device: null, os: null, photos: 0 });
  }
  const first = await (await handle(new Request('https://spell.test/feedback?token=s3cret'), e, at(NOON_KST))).text();
  assert.match(first, /의견 31번/);
  assert.ok(!first.includes('의견 1번<'), '맨 처음 것은 다음 쪽에');
  const more = first.match(/href="(\/feedback\?token=s3cret&amp;before=\d+)"/);
  assert.ok(more, '더 보기 고리가 없다');
  const second = await (await handle(new Request('https://spell.test' + more[1].replaceAll('&amp;', '&')), e, at(NOON_KST))).text();
  assert.match(second, /의견 0번/);
  assert.match(second, /의견 1번/);
  assert.ok(!second.includes('의견 31번'));
});

test(`크론은 ${KEEP_DAYS}일 지난 의견을 사진째 지운다`, async () => {
  const e = env();
  const now = NOON_KST + 400 * DAY;
  const [old, recent] = await seed(e, [{ text: '오래된 것', photos: [jpeg()] }, { text: '새것', photos: [jpeg()] }]);
  e.DB.feedback.get(old).created_at = now - (KEEP_DAYS + 1) * DAY;
  e.DB.feedback.get(recent).created_at = now - (KEEP_DAYS - 1) * DAY;
  await sweepFeedback(e.DB, now);
  assert.ok(!e.DB.feedback.has(old));
  assert.ok(e.DB.feedback.has(recent));
  assert.deepEqual([...e.DB.feedbackPhotos.keys()], [`${recent}|0`]);
});

test('처리방침에 의견 보관 기간이 같은 값으로 적혀 있다', async () => {
  const body = await (await handle(new Request('https://spell.test/privacy'), env())).text();
  assert.match(body, /고객센터 의견/);
  assert.ok(body.includes(`받은 날로부터 ${KEEP_DAYS}일 뒤 삭제`));
});
