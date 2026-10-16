// PC 보호 확인(D-101, PR #129 risk-ops M2~M4, architect F2·R1). 실험실·Gradle·Chromium을 띄우기 전에 멈춘다.
// 흐름 시험의 실행 명령은 임시 설정 폴더를 쓰므로 감시가 실제 설정 폴더에 만드는 정지 요청 파일을 보지 못한다. 그래서 여기서
// 실제 설정 폴더의 정지 요청과 장 시간 보호 구간을 먼저 본다. 장 시간 보호는 GitHub Actions 러너에서 워크플로가 전용 변수를
// 켤 때만 푼다(러너에는 실전 엔진이 없다). 범용 CI 변수만으로는 풀지 않는다. 자동 시험: guard.test.mjs(node --test).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CALENDARS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'autotrade-marketdata', 'src', 'main',
  'resources', 'com', 'autotrade', 'marketdata', 'calendar');
/** SessionProtection.BEFORE_OPEN_MINUTES, AFTER_CLOSE_MINUTES와 같다. */
const BEFORE_OPEN = 60;
const AFTER_CLOSE = 30;

/** 실행 명령에 -PallowDuringSession을 붙여도 되는지: GitHub Actions 러너이고 워크플로가 전용 변수를 켰을 때만. */
export function allowDuringSession(env = process.env) {
  return env.GITHUB_ACTIONS === 'true' && env.AUTOTRADE_E2E_ALLOW_DURING_SESSION === 'true';
}

/**
 * 사용자의 실제 설정 폴더: AUTOTRADE_CONFIG_DIR, 없으면 ~/.autotrade. 감시·실험실을 -Dautotrade.config-dir로 띄웠다면 그 폴더를
 * AUTOTRADE_CONFIG_DIR로 주고 돌린다(README).
 */
export function realConfigDir(env = process.env) {
  return env.AUTOTRADE_CONFIG_DIR || path.join(os.homedir(), '.autotrade');
}

const minutes = hhmm => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));

function loadCalendar(file) {
  const c = JSON.parse(fs.readFileSync(path.join(CALENDARS, file), 'utf8'));
  return {
    name: c.market, zone: c.zone, open: minutes(c.regularOpen), close: minutes(c.regularClose),
    specials: new Map((c.specialSessions || []).map(s => [s.date, { open: minutes(s.open), close: minutes(s.close) }])),
  };
}

/** 거래소 현지 날짜(YYYY-MM-DD), 요일, 분. */
function local(now, zone) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(now).map(x => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, weekday: p.weekday, minutes: Number(p.hour) * 60 + Number(p.minute) };
}

/**
 * 장 시간 보호 구간 안이면 시장 이름을 돌려준다. ExchangeCalendar.protectionSession보다 넓거나 같게 본다:
 * 평일이면 휴장일도 정규장이 있는 날로 보고, 특별 운영일은 확인 여부와 관계없이 정규장과 합친 구간(이른 개장·늦은 폐장 중
 * 넓은 쪽)으로 본다. 그 구간의 개장 60분 전부터 폐장 30분 뒤까지 막는다.
 */
export function insideSession(now = new Date(), calendars = [loadCalendar('krx.json'), loadCalendar('us.json')]) {
  for (const c of calendars) {
    const t = local(now, c.zone);
    if (t.weekday === 'Sat' || t.weekday === 'Sun') continue;
    const sp = c.specials.get(t.date);
    const open = sp ? Math.min(c.open, sp.open) : c.open;
    const close = sp ? Math.max(c.close, sp.close) : c.close;
    if (t.minutes >= open - BEFORE_OPEN && t.minutes < close + AFTER_CLOSE) return c.name;
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

/** 시험을 시작하면 안 되는 이유. 없으면 null. 정지 요청은 장 시간 보호를 풀어도 본다. */
export function refuseReason({ now = new Date(), env = process.env } = {}) {
  const stop = path.join(realConfigDir(env), 'experiment-state', 'stop-request');
  if (!notExists(stop)) {
    return `감시 프로세스의 정지 요청이 있습니다(${stop}). 자원이 회복돼 감시가 요청을 거둔 뒤 돌리세요.`;
  }
  if (allowDuringSession(env)) return null;
  const market = insideSession(now);
  if (market) {
    return `${market} 장 시간 보호 구간(개장 60분 전 ~ 폐장 30분 뒤)입니다. 실전 엔진과 자원을 다투지 않게 구간 밖에서 돌리세요(D-063).`;
  }
  return null;
}
