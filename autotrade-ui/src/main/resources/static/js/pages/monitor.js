// 모니터링 화면(MON-05, UI-05 모니터링, D-090): PC 전체(CPU·메모리·디스크·네트워크)와 실험실 JVM(힙·GC·스레드) 지표,
// 임계 수준과 최근 임계 이벤트. 새 표본은 SSE(/api/monitor/stream)로 받고, 연결이 안 되면 수집 주기마다 조회한다.
// 보기만 하는 화면이다: 무엇도 멈추거나 바꾸지 않는다. 화면을 떠나면 연결과 조회를 멈춘다.
import { h, s, table } from '../dom.js';
import { get } from '../api.js';
import { number } from '../format.js';

const LABEL = {
  'cpu.system': 'CPU 사용률',
  'memory.used': '메모리 사용률',
  'memory.used-gb': '메모리 사용량',
  'disk.used': '사용률',
  'disk.free-gb': '여유 공간',
  'net.rx': '수신',
  'net.tx': '송신',
  'jvm.cpu': '실험실 프로세스 CPU',
  'jvm.heap.used': '힙 사용률',
  'jvm.heap.used-mb': '힙 사용량',
  'jvm.gc-time': 'GC 시간',
  'jvm.gc-count': 'GC 횟수(주기당)',
  'jvm.threads': '스레드',
};

// 화면 묶음. 지표 이름 순서가 묶음 안 순서다. 묶음에 없는 지표는 "기타"로 간다
const GROUPS = [
  { title: 'PC 전체', metrics: ['cpu.system', 'memory.used', 'memory.used-gb'] },
  { title: '드라이브', metrics: ['disk.free-gb', 'disk.used'], byTarget: true },
  { title: '네트워크(루프백 제외 합계)', metrics: ['net.rx', 'net.tx'] },
  { title: '실험실 JVM', metrics: ['jvm.heap.used', 'jvm.heap.used-mb', 'jvm.gc-time', 'jvm.gc-count', 'jvm.threads', 'jvm.cpu'] },
];

const LEVEL = {
  OK: { label: '정상', cls: 'ok' },
  WARN: { label: '경고', cls: 'warn-state' },
  CRITICAL: { label: '위험', cls: 'critical' },
};

const DIRECTION = { ABOVE: '이상', BELOW: '이하' };

/** 값 표시. 바이트/초는 KB/s·MB/s로 줄인다. */
export function formatValue(value, unit) {
  if (value === null || value === undefined) return '–';
  if (unit === 'B/s') {
    const v = Number(value);
    if (v >= 1024 * 1024) return `${number(v / (1024 * 1024), 1)} MB/s`;
    if (v >= 1024) return `${number(v / 1024, 1)} KB/s`;
    return `${number(v, 0)} B/s`;
  }
  const count = unit === '개' || unit === '회';
  return `${number(value, count ? 0 : 1)}${unit === '%' || count ? unit : ` ${unit}`}`;
}

export function thresholdText(t, unit) {
  if (!t) return '임계 없음';
  const word = DIRECTION[t.direction] || t.direction;
  return `경고 ${formatValue(t.warn, unit)} ${word} · 위험 ${formatValue(t.critical, unit)} ${word} · ${t.sustain}회 연속`;
}

let es = null;
let timer = null;
let staleTimer = null;
/** 수집 멈춤으로 보는 기준(서버 MonitorService.STALE_INTERVALS와 같음). */
const STALE_INTERVALS = 3;

function stopUpdates() {
  if (es) { es.close(); es = null; }
  if (timer) { clearInterval(timer); timer = null; }
  if (staleTimer) { clearInterval(staleTimer); staleTimer = null; }
}

/**
 * 마지막 표본(첫 표본 전이면 수집 시작)이 주기의 3배보다 오래됐는지. SSE가 조용히 끊기거나 수집이 멈추면 새 이벤트가 없으므로
 * 화면이 스스로 잰다.
 */
export function isStale(state, nowMs) {
  const since = state.latest ? state.latest.at : state.startedAt;
  if (!since) return false;
  return nowMs - Date.parse(since) > STALE_INTERVALS * state.intervalSeconds * 1000;
}

function staleText(state) {
  const limit = `수집 주기 ${state.intervalSeconds}초의 ${STALE_INTERVALS}배 넘게 새 표본 없음`;
  if (!state.latest) {
    return `수집 멈춤: ${new Date(state.startedAt).toLocaleTimeString('en-GB')}에 수집을 시작했지만 첫 표본이 없습니다(${limit}). 실험실 로그를 확인하세요.`;
  }
  return `수집 멈춤: 마지막 표본이 ${new Date(state.latest.at).toLocaleTimeString('en-GB')}입니다(${limit}). 아래 값은 지금 값이 아닙니다. 실험실 로그를 확인하세요.`;
}

export async function monitorPage() {
  stopUpdates();
  const state = await get('/api/monitor');
  const host = h('div', { 'aria-live': 'off' });
  const link = h('span', { class: 'badge' });
  const at = h('span', { class: 'muted small' });
  const staleBox = h('div', { role: 'alert' });
  const checkStale = () => {
    staleBox.replaceChildren(state.enabled && (state.stale || isStale(state, Date.now()))
      ? h('p', { class: 'notice warn' }, staleText(state))
      : '');
  };

  const setLink = (mode) => {
    const m = {
      sse: ['실시간 연결', 'badge ok'],
      poll: [`주기 조회(${state.intervalSeconds}초)`, 'badge warn-state'],
      off: [state.problem ? '사용 불가' : '수집 꺼짐', state.problem ? 'badge warn-state' : 'badge'],
      wait: ['연결 중', 'badge'],
    }[mode];
    link.textContent = m[0];
    link.className = m[1];
  };
  const draw = () => {
    at.textContent = state.latest ? `마지막 수집 ${new Date(state.latest.at).toLocaleTimeString('en-GB')}` : '첫 수집 전';
    host.replaceChildren(body(state));
    checkStale();
  };
  const alive = () => document.body.contains(host);

  // 화면에 들어올 때는 이미 그린 상태로 돌려준다. 이후 갱신은 SSE 또는 주기 조회
  draw();
  if (state.enabled) {
    staleTimer = setInterval(() => { if (!alive()) { stopUpdates(); return; } checkStale(); }, 1000);
  }
  if (!state.enabled) {
    setLink('off');
  } else if (typeof EventSource === 'function') {
    setLink('wait');
    es = new EventSource('/api/monitor/stream');
    es.addEventListener('open', () => { if (alive()) setLink('sse'); });
    es.addEventListener('sample', (ev) => {
      if (!alive()) { stopUpdates(); return; }
      apply(state, JSON.parse(ev.data));
      draw();
    });
    es.addEventListener('error', () => {
      // 서버가 거절(409)하거나 끊으면 EventSource가 닫힌다. 그때는 주기 조회로 바꾼다(다시 붙기는 브라우저가 알아서 하는 경우만)
      if (!alive()) { stopUpdates(); return; }
      if (es && es.readyState === EventSource.CLOSED) {
        es = null;
        startPolling();
      } else {
        setLink('wait');
      }
    });
  } else {
    startPolling();
  }

  function startPolling() {
    setLink('poll');
    if (timer) return;
    timer = setInterval(async () => {
      if (!alive()) { stopUpdates(); return; }
      try {
        Object.assign(state, await get('/api/monitor'));
        draw();
      } catch { /* 다음 주기에 다시 읽는다 */ }
    }, Math.max(1, state.intervalSeconds) * 1000);
  }

  return h('div', {},
    h('div', { class: 'page-head' },
      h('div', {},
        h('h1', {}, '모니터링'),
        h('p', { class: 'muted' }, 'PC 전체 자원과 실험실 프로세스(JVM) 지표입니다. 실전 엔진·게이트웨이·감시 프로세스의 JVM 지표는 여기 나오지 않습니다. 이 화면은 보기만 하고 아무것도 멈추지 않습니다.')),
      h('div', { class: 'monitor-link' }, link, at)),
    staleBox, host);
}

/** SSE 표본을 화면 상태에 더한다(기록은 서버 기록 크기와 같은 수까지). */
export function apply(state, update) {
  const cap = Math.max(1, state.historySize || 1);
  state.latest = update.latest;
  state.stale = false;
  state.history = state.history.concat([update.point]).slice(-cap);
  state.events = state.events.concat(update.events).slice(-200);
}

function body(state) {
  if (!state.enabled && state.problem) {
    return h('div', { class: 'empty' },
      h('h2', {}, '모니터링을 쓸 수 없습니다'),
      h('p', {}, state.problem));
  }
  if (!state.enabled) {
    return h('div', { class: 'empty' },
      h('h2', {}, '모니터링 수집이 꺼져 있습니다'),
      h('p', {}, '실험실 기동 속성 autotrade.lab.monitor.enabled=true로 켭니다(docs/config/config-keys.md).'));
  }
  if (!state.latest) {
    return h('p', { class: 'muted' }, `첫 수집을 기다리는 중입니다(${state.intervalSeconds}초 주기).`);
  }
  const thresholds = new Map(state.thresholds.map(t => [t.metric, t]));
  const used = new Set();
  const sections = [];
  for (const g of GROUPS) {
    const readings = state.latest.readings.filter(r => g.metrics.includes(r.metric));
    readings.forEach(r => used.add(r.name));
    if (readings.length === 0) continue;
    readings.sort((a, b) => (g.byTarget ? a.target.localeCompare(b.target) : 0)
      || g.metrics.indexOf(a.metric) - g.metrics.indexOf(b.metric));
    sections.push(h('section', {}, h('h2', {}, g.title),
      h('div', { class: 'tiles' }, readings.map(r => tile(r, thresholds.get(r.metric), state.history, g.byTarget)))));
  }
  const rest = state.latest.readings.filter(r => !used.has(r.name));
  if (rest.length > 0) {
    sections.push(h('section', {}, h('h2', {}, '기타'),
      h('div', { class: 'tiles' }, rest.map(r => tile(r, thresholds.get(r.metric), state.history, true)))));
  }
  const failed = state.latest.failedSources.length > 0
    ? h('p', { class: 'notice warn' }, `이번 수집에서 읽지 못한 수집원: ${state.latest.failedSources.join(', ')}. 다음 주기에 다시 읽습니다.`)
    : null;
  return h('div', {}, alerts(state), failed, sections, events(state));
}

function alerts(state) {
  const bad = state.latest.readings.filter(r => r.level === 'WARN' || r.level === 'CRITICAL');
  if (bad.length === 0) return h('p', { class: 'notice' }, '모든 임계 지표가 정상입니다.');
  return h('p', { class: 'notice warn', role: 'status' },
    `임계를 넘은 지표 ${bad.length}개: `,
    bad.map(r => `${name(r)} ${formatValue(r.value, r.unit)}(${LEVEL[r.level].label})`).join(', '));
}

function name(r) {
  const label = LABEL[r.metric] || r.metric;
  return r.target ? `${r.target} ${label}` : label;
}

function tile(r, threshold, history, withTarget) {
  const level = r.level ? LEVEL[r.level] : null;
  return h('div', { class: `tile${level ? ` level-${r.level.toLowerCase()}` : ''}` },
    h('div', { class: 'tile-label' }, withTarget && r.target ? `${r.target} · ${LABEL[r.metric] || r.metric}` : (LABEL[r.metric] || r.metric),
      level ? h('span', { class: `badge ${level.cls} tile-badge` }, level.label) : null),
    h('div', { class: 'tile-value' }, formatValue(r.value, r.unit)),
    spark(history.map(p => p.values[r.name]), threshold),
    h('div', { class: 'tile-sub' }, thresholdText(threshold, r.unit)));
}

/** 작은 추세선. 경고·위험 경계가 그래프 범위 안이면 점선으로 그린다. */
export function spark(values, threshold) {
  const W = 200, H = 36, P = 2;
  const pts = values.map((v, i) => [i, v]).filter(([, v]) => v !== undefined && v !== null);
  const svg = s('svg', { class: 'spark', viewBox: `0 0 ${W} ${H}`, 'aria-hidden': 'true', preserveAspectRatio: 'none' });
  if (pts.length < 2) return svg;
  const ys = pts.map(([, v]) => Number(v));
  let lo = Math.min(...ys), hi = Math.max(...ys);
  if (threshold) {
    // 경계가 값 근처(범위의 두 배 안)면 같이 보이게 범위를 넓힌다
    for (const b of [threshold.warn, threshold.critical]) {
      if (b >= lo - (hi - lo || 1) * 2 && b <= hi + (hi - lo || 1) * 2) { lo = Math.min(lo, b); hi = Math.max(hi, b); }
    }
  }
  if (lo === hi) { lo -= 1; hi += 1; }
  const n = values.length - 1 || 1;
  const x = i => P + (i * (W - 2 * P)) / n;
  const y = v => P + ((hi - v) * (H - 2 * P)) / (hi - lo);
  if (threshold) {
    for (const [b, cls] of [[threshold.warn, 'warn-line'], [threshold.critical, 'critical-line']]) {
      if (b >= lo && b <= hi) svg.append(s('line', { class: cls, x1: P, x2: W - P, y1: y(b), y2: y(b) }));
    }
  }
  svg.append(s('path', { class: 'spark-line', d: pts.map(([i, v], k) => `${k === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(Number(v)).toFixed(1)}`).join(' ') }));
  return svg;
}

function events(state) {
  const rows = state.events.slice().reverse();
  return h('section', {}, h('h2', {}, '최근 임계 이벤트'),
    rows.length === 0
      ? h('p', { class: 'muted' }, '실험실이 뜬 뒤 임계 수준이 바뀐 적이 없습니다.')
      : table([
        { label: '시각', render: e => new Date(e.at).toLocaleString('ko-KR', { hour12: false }) },
        { label: '지표', render: e => eventName(e.reading) },
        { label: '변화', render: e => h('span', { class: `badge ${LEVEL[e.to].cls}` }, `${LEVEL[e.from].label} → ${LEVEL[e.to].label}`) },
        { label: '값', num: true, render: e => formatValue(e.value, e.unit) },
        { label: '경계', num: true, render: e => formatValue(e.limit, e.unit) },
      ], rows, { rowClass: e => (e.to === 'CRITICAL' ? 'urgent' : null) }),
    h('p', { class: 'hint' }, '이벤트는 실험실 메모리에만 있고(최근 200건) 실험실을 다시 시작하면 비워집니다. 임계 값은 실험실 기동 속성 autotrade.lab.monitor.thresholds로 바꿉니다.'));
}

function eventName(reading) {
  const colon = reading.indexOf(':');
  const metric = colon < 0 ? reading : reading.slice(0, colon);
  const target = colon < 0 ? '' : reading.slice(colon + 1);
  return name({ metric, target });
}
