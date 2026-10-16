// PC 보호 확인(D-101, PR #129 risk-ops M2·M3, architect F2). 실험실·Gradle·Chromium을 띄우기 전에 멈춘다.
// 흐름 시험의 실행 명령은 임시 설정 폴더를 쓰므로 감시가 실제 설정 폴더에 만드는 정지 요청 파일을 보지 못한다. 그래서 여기서
// 실제 설정 폴더의 정지 요청과 장 시간 보호 구간을 먼저 본다. 장 시간 보호는 GitHub Actions 러너에서 워크플로가 전용 변수를
// 켤 때만 푼다(러너에는 실전 엔진이 없다). 범용 CI 변수만으로는 풀지 않는다.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** 실행 명령에 -PallowDuringSession을 붙여도 되는지: GitHub Actions 러너이고 워크플로가 전용 변수를 켰을 때만. */
export function allowDuringSession() {
  return process.env.GITHUB_ACTIONS === 'true' && process.env.AUTOTRADE_E2E_ALLOW_DURING_SESSION === 'true';
}

/** 사용자의 실제 설정 폴더(실험실·감시와 같은 규칙: AUTOTRADE_CONFIG_DIR, 없으면 ~/.autotrade). */
export function realConfigDir() {
  return process.env.AUTOTRADE_CONFIG_DIR || path.join(os.homedir(), '.autotrade');
}

/** 거래소 현지 시각(요일, 분). */
function local(now, zone) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: zone, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(now).map(p => [p.type, p.value]));
  return { weekday: parts.weekday, minutes: Number(parts.hour) * 60 + Number(parts.minute) };
}

/**
 * 장 시간 보호 구간 안이면 시장 이름을 돌려준다(SessionProtection과 같은 개장 60분 전 ~ 폐장 30분 뒤).
 * 휴장일은 보지 않고 평일이면 막는다(실제 규칙보다 넓게, 안전한 쪽).
 */
export function insideSession(now = new Date()) {
  const markets = [
    { name: 'KRX', zone: 'Asia/Seoul', open: 9 * 60, close: 15 * 60 + 30 },
    { name: 'US', zone: 'America/New_York', open: 9 * 60 + 30, close: 16 * 60 },
  ];
  for (const m of markets) {
    const t = local(now, m.zone);
    if (t.weekday === 'Sat' || t.weekday === 'Sun') continue;
    if (t.minutes >= m.open - 60 && t.minutes < m.close + 30) return m.name;
  }
  return null;
}

/** 없다고 확인될 때만 true(ExperimentGate와 같이, 권한 오류 등 판단할 수 없으면 있는 쪽). */
function notExists(p) {
  try {
    fs.lstatSync(p);
    return false;
  } catch (e) {
    return e.code === 'ENOENT' || e.code === 'ENOTDIR';
  }
}

/** 시험을 시작하면 안 되는 이유. 없으면 null. */
export function refuseReason(now = new Date()) {
  const stop = path.join(realConfigDir(), 'experiment-state', 'stop-request');
  if (!notExists(stop)) {
    return `감시 프로세스의 정지 요청이 있습니다(${stop}). 자원이 회복돼 감시가 요청을 거둔 뒤 돌리세요.`;
  }
  if (allowDuringSession()) return null;
  const market = insideSession(now);
  if (market) {
    return `${market} 장 시간 보호 구간(개장 60분 전 ~ 폐장 30분 뒤)입니다. 실전 엔진과 자원을 다투지 않게 구간 밖에서 돌리세요(D-063).`;
  }
  return null;
}
