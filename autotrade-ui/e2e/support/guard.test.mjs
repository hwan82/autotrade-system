// PC 보호 확인 단위 시험(D-101, PR #129 risk-ops M4). 의존성 없이 `node --test support/`로 돌고, CI는 Playwright보다 먼저 돌린다.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { allowDuringSession, insideSession, refuseReason, uncoveredDate, workDirReason } from './guard.mjs';

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

// PR #129 QA Q1(변이 G5·G6): 합침의 개장 쪽과 폐장 쪽을 각각 고정한다
test('특별 운영일 합침의 개장 쪽: 수능일 개장 10:00이어도 정규장 09:00 기준 08:00 KST부터 막는다', () => {
  assert.equal(insideSession(at('2026-11-18T22:59:00Z')), null); // 수능일 07:59 KST(뉴욕 전날 17:59)
  assert.equal(insideSession(at('2026-11-18T23:00:00Z')), 'KRX'); // 08:00 KST
  assert.equal(insideSession(at('2026-11-18T23:30:00Z')), 'KRX'); // 08:30 KST(특별 구간만 보면 09:00부터)
});

test('특별 운영일 합침의 폐장 쪽: 미국 조기 폐장일(2026-11-27 13:00)도 정규장 16:00 기준 16:30 뉴욕까지 막는다', () => {
  assert.equal(insideSession(at('2026-11-27T19:00:00Z')), 'US'); // EST 14:00(조기 폐장만 보면 13:30까지)
  assert.equal(insideSession(at('2026-11-27T21:29:00Z')), 'US'); // EST 16:29
  assert.equal(insideSession(at('2026-11-27T21:30:00Z')), null); // EST 16:30
});

// PR #129 QA R2(architect N11, risk-ops K1): 수록 범위 밖은 SessionProtection처럼 판단할 수 없음으로 멈춘다
test('캘린더 수록 범위 밖 날짜는 전날·당일·다음날 중 하나라도 벗어나면 판단할 수 없음', () => {
  assert.equal(uncoveredDate(at('2027-12-30T01:00:00Z')), null); // 국내 2027-12-30 목, 다음날까지 수록
  assert.deepEqual(uncoveredDate(at('2027-12-31T01:00:00Z')), { market: 'KRX', date: '2028-01-01' }); // 다음날이 범위 밖
  assert.deepEqual(uncoveredDate(at('2030-01-05T01:00:00Z')), { market: 'KRX', date: '2030-01-04' }); // 토 10:00 KST
  assert.deepEqual(uncoveredDate(at('2026-01-01T00:00:00Z')), { market: 'KRX', date: '2025-12-31' }); // 수록 시작 전날
});

test('수록 범위 밖이면 주말이어도 거절하고, 장 시간 보호를 풀 때만 통과한다', () => {
  const dir = emptyConfig();
  const now = at('2030-01-05T01:00:00Z'); // 토 10:00 KST
  assert.equal(insideSession(now), null);
  assert.match(refuseReason({ now, env: { AUTOTRADE_CONFIG_DIR: dir } }), /KRX 캘린더 수록 범위 밖\(2030-01-04\)/);
  assert.equal(refuseReason({ now, env: { AUTOTRADE_CONFIG_DIR: dir, GITHUB_ACTIONS: 'true',
    AUTOTRADE_E2E_ALLOW_DURING_SESSION: 'true' } }), null);
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

// PR #129 QA Q1(변이 G7): 정지 요청 파일이 있는지 판단할 수 없으면 있는 쪽으로 본다
test('정지 요청 파일 확인이 없음 외의 오류로 실패하면 거절하고, 없음이면 통과한다', () => {
  const now = at('2026-10-17T01:00:00Z'); // 토 10:00 KST
  const env = { AUTOTRADE_CONFIG_DIR: emptyConfig() };
  const failing = code => () => {
    throw Object.assign(new Error(code), { code });
  };
  for (const code of ['EACCES', 'EPERM', 'EIO', 'ENAMETOOLONG']) {
    assert.match(refuseReason({ now, env, lstat: failing(code) }), /정지 요청이 있거나 있는지 확인할 수 없습니다/, code);
  }
  for (const code of ['ENOENT', 'ENOTDIR']) {
    assert.equal(refuseReason({ now, env, lstat: failing(code) }), null, code);
  }
});

// PR #129 QA R1: 작업 폴더는 통째로 지우므로 실제 설정 폴더·저장소와 겹치면 거절한다
test('시험 작업 폴더가 실제 설정 폴더·저장소와 같거나 그 위·아래면 거절한다', () => {
  const real = emptyConfig();
  const repo = emptyConfig();
  const env = { AUTOTRADE_CONFIG_DIR: real };
  const opts = { env, repo };
  assert.match(workDirReason(real, opts), /같거나 그 위·아래/);
  assert.match(workDirReason(path.join(real, 'e2e'), opts), /같거나 그 위·아래/);
  assert.match(workDirReason(path.dirname(real), opts), /같거나 그 위·아래/);
  assert.match(workDirReason(path.join(real, '..', path.basename(real)), opts), /같거나 그 위·아래/);
  assert.match(workDirReason(repo, opts), /같거나 그 위·아래/);
  assert.match(workDirReason(path.join(os.homedir(), '.autotrade'), opts), /같거나 그 위·아래/); // 기본 설정 폴더도
  assert.match(workDirReason(os.homedir(), opts), /같거나 그 위·아래/);
  assert.equal(workDirReason(path.join(os.tmpdir(), 'autotrade-ui-e2e'), opts), null); // 기본 작업 폴더
  assert.equal(workDirReason(`${real}-e2e`, opts), null); // 이름만 비슷한 옆 폴더
});

test('작업 폴더 확인은 링크를 따라가 실제 경로로 본다', () => {
  const real = emptyConfig();
  const link = path.join(emptyConfig(), 'link');
  fs.symlinkSync(real, link, 'junction'); // Windows는 관리자 권한 없이 되는 디렉터리 정션, 다른 OS는 종류를 무시한다
  assert.match(workDirReason(link, { env: { AUTOTRADE_CONFIG_DIR: real } }), /같거나 그 위·아래/);
});
