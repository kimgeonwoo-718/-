import { test } from 'node:test';
import assert from 'node:assert/strict';
import { kstDay, decide, validInstallId } from '../src/quota.js';

test('하루 경계는 한국 시간 자정이다', () => {
  // UTC 14:59 = KST 23:59 (같은 날), UTC 15:00 = KST 00:00 (다음 날)
  assert.equal(kstDay(Date.UTC(2026, 0, 1, 14, 59)), '2026-01-01');
  assert.equal(kstDay(Date.UTC(2026, 0, 1, 15, 0)), '2026-01-02');
});

test('한도까지 쓰고 나면 막힌다', () => {
  assert.deepEqual(decide(0, 5), { allowed: true, remaining: 5 });
  assert.deepEqual(decide(4, 5), { allowed: true, remaining: 1 });
  assert.deepEqual(decide(5, 5), { allowed: false, remaining: 0 });
  assert.deepEqual(decide(9, 5), { allowed: false, remaining: 0 }, '넘겨 세어도 음수가 되면 안 된다');
});

test('설치 ID 모양을 좁힌다', () => {
  assert.ok(validInstallId('3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f'));
  assert.ok(!validInstallId(''));
  assert.ok(!validInstallId('short'));
  assert.ok(!validInstallId('has space in it'));
  assert.ok(!validInstallId('x'.repeat(65)));
  assert.ok(!validInstallId(null));
});
