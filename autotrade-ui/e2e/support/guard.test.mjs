// PC 보호 확인 단위 시험(D-101, PR #129 risk-ops M4). 의존성 없이 `node --test support/`로 돌고, CI는 Playwright보다 먼저 돌린다.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { allowDuringSession, insideSession, refuseReason } from './guard.mjs';

const at = iso => new Date(iso);
const emptyConfig = () => fs.mkdtempSync(path.join(os.tmpdir(), 'guard-test-'));

test('국내 장 시간 보호 구간 경계(08:00 이상 16:00 미만 KST)', () => {
  assert.equal(insideSession(at('2026-10-15T22:59:00Z')), null); // 금 07:59 KST(뉴욕 목 18:59)
  assert.equal(insideSession(at('2026-10-15T23:00:00Z')), 'KRX'); // 08:00 KST
  assert.equal(insideSession(at('2026-10-16T06:59:00Z')), 'KRX'); // 15:59 KST
  assert.equal(insideSession(at('2026-10-16T07:00:00Z')), null); // 16:00 KST(뉴욕 03:00)
});

test('미국 장 시간 보호 구간 경계와 서머타임(08:30 이상 16:30 미만 뉴욕)', () => {
  assert.equal(insideSession(at('2026-10-16T12:29:00Z')), null); // EDT 08:29
  assert.equal(insideSession(at('2026-10-16T12:30:00Z')), 'US'); // EDT 08:30
  assert.equal(insideSession(at('2026-10-16T20:29:00Z')), 'US'); // EDT 16:29
  assert.equal(insideSession(at('2026-10-16T20:30:00Z')), null); // EDT 16:30
  assert.equal(insideSession(at('2026-11-09T13:30:00Z')), 'US'); // EST 08:30(서머타임 끝난 뒤 월요일)
  assert.equal(insideSession(at('2026-11-09T13:29:00Z')), null); // EST 08:29
});

test('주말은 막지 않는다', () => {
  assert.equal(insideSession(at('2026-10-17T01:00:00Z')), null); // 토 10:00 KST
  assert.equal(insideSession(at('2026-10-18T15:00:00Z')), null); // 일 11:00 뉴욕
});

test('특별 운영일은 정규장과 합친 구간: 수능일 2026-11-19 국내 10:00~16:30이면 08:00~17:00 KST', () => {
  assert.equal(insideSession(at('2026-11-19T07:10:00Z')), 'KRX'); // 16:10 KST
  assert.equal(insideSession(at('2026-11-19T07:59:00Z')), 'KRX'); // 16:59 KST
  assert.equal(insideSession(at('2026-11-19T08:00:00Z')), null); // 17:00 KST
  assert.equal(insideSession(at('2026-11-18T07:10:00Z')), null); // 전날 16:10 KST
});

test('장 시간 보호 해제는 GITHUB_ACTIONS와 전용 변수가 함께 true일 때만', () => {
  assert.equal(allowDuringSession({ CI: 'true' }), false);
  assert.equal(allowDuringSession({ GITHUB_ACTIONS: 'true' }), false);
  assert.equal(allowDuringSession({ AUTOTRADE_E2E_ALLOW_DURING_SESSION: 'true' }), false);
  assert.equal(allowDuringSession({ GITHUB_ACTIONS: 'true', AUTOTRADE_E2E_ALLOW_DURING_SESSION: '1' }), false);
  assert.equal(allowDuringSession({ GITHUB_ACTIONS: 'true', AUTOTRADE_E2E_ALLOW_DURING_SESSION: 'true' }), true);
});

test('장중에는 거절하고, CI 변수만으로는 풀리지 않는다', () => {
  const dir = emptyConfig();
  const now = at('2026-10-16T01:00:00Z'); // 10:00 KST
  assert.match(refuseReason({ now, env: { AUTOTRADE_CONFIG_DIR: dir } }), /KRX 장 시간 보호/);
  assert.match(refuseReason({ now, env: { AUTOTRADE_CONFIG_DIR: dir, CI: 'true' } }), /KRX 장 시간 보호/);
  assert.equal(refuseReason({ now, env: { AUTOTRADE_CONFIG_DIR: dir, GITHUB_ACTIONS: 'true',
    AUTOTRADE_E2E_ALLOW_DURING_SESSION: 'true' } }), null);
  assert.equal(refuseReason({ now: at('2026-10-17T01:00:00Z'), env: { AUTOTRADE_CONFIG_DIR: dir } }), null);
});

test('정지 요청이 있으면 장 시간 보호를 풀어도 거절한다', () => {
  const dir = emptyConfig();
  fs.mkdirSync(path.join(dir, 'experiment-state'));
  fs.writeFileSync(path.join(dir, 'experiment-state', 'stop-request'), '');
  const env = { AUTOTRADE_CONFIG_DIR: dir, GITHUB_ACTIONS: 'true', AUTOTRADE_E2E_ALLOW_DURING_SESSION: 'true' };
  assert.match(refuseReason({ now: at('2026-10-17T01:00:00Z'), env }), /정지 요청/);
});
