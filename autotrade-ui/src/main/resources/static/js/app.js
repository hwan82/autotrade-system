// 해시 라우터와 상단 상태 표시. 페이지 모듈은 view 함수(params) → Promise<Element>.
import { h, mount, toast } from './dom.js';
import { loadSession, get } from './api.js';
import { experimentsPage, experimentPage, runPage, selectionResultPage } from './pages/experiments.js';
import { newExperimentPage } from './pages/new-experiment.js';
import { selectionsPage, selectionPage, newSelectionPage } from './pages/selections.js';
import { profilesPage, profilePage, newProfilePage } from './pages/profiles.js';
import { settingsPage } from './pages/settings.js';
import { realPage } from './pages/real.js';
import { livePage, liveRunPage } from './pages/live.js';
import { monitorPage } from './pages/monitor.js';

const routes = [
  [/^#\/experiments$/, 'experiments', () => experimentsPage()],
  [/^#\/experiments\/new$/, 'experiments', () => newExperimentPage()],
  [/^#\/experiments\/([A-Z0-9-]+)$/, 'experiments', m => experimentPage(m[1])],
  [/^#\/experiments\/([A-Z0-9-]+)\/runs\/([A-Za-z0-9-]+)$/, 'experiments', m => runPage(m[1], m[2])],
  [/^#\/experiments\/([A-Z0-9-]+)\/selection-results\/([0-9a-f]{64})$/, 'experiments', m => selectionResultPage(m[1], m[2])],
  [/^#\/live$/, 'live', () => livePage()],
  [/^#\/live\/runs\/([A-Za-z0-9-]+)$/, 'live', m => liveRunPage(m[1])],
  [/^#\/selections$/, 'selections', () => selectionsPage()],
  [/^#\/selections\/new$/, 'selections', () => newSelectionPage()],
  [/^#\/selections\/([a-z0-9-]+)(?:\/v(\d+))?$/, 'selections', m => selectionPage(m[1], m[2] ? Number(m[2]) : null)],
  [/^#\/profiles$/, 'profiles', () => profilesPage()],
  [/^#\/profiles\/new$/, 'profiles', () => newProfilePage()],
  [/^#\/profiles\/([a-z0-9-]+)(?:\/v(\d+))?$/, 'profiles', m => profilePage(m[1], m[2] ? Number(m[2]) : null)],
  [/^#\/monitor$/, 'monitor', () => monitorPage()],
  [/^#\/settings$/, 'settings', () => settingsPage()],
  [/^#\/real$/, 'real', () => realPage()],
];

const BROKER = { KIS: '한국투자', KIWOOM: '키움', TOSS: '토스' };

async function render() {
  const hash = location.hash || '#/experiments';
  for (const [re, nav, page] of routes) {
    const m = hash.match(re);
    if (!m) continue;
    document.querySelectorAll('[data-nav]').forEach(a => {
      if (a.dataset.nav === nav) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    try {
      mount(await page(m));
    } catch (e) {
      mount(h('div', { class: 'empty' }, h('h1', {}, '화면을 열지 못했습니다'), h('p', {}, e.message)));
    }
    return;
  }
  location.hash = '#/experiments';
}

function header(session) {
  const brokers = document.getElementById('brokers');
  brokers.replaceChildren(...session.brokers.map(b => h('span', {
    class: `chip${b.keyStatus === 'CONFIGURED' ? '' : ' off'}`,
    title: '키 설정 여부입니다. 연결 상태(정상·저하)는 운영 화면에서 보여 줍니다',
  }, `${BROKER[b.id] || b.id} ${b.keyStatus === 'CONFIGURED' ? '키 설정됨' : '미설정'}`)));
  const n = session.realAccounts.length;
  document.getElementById('real-accounts').textContent = n === 0
    ? '실전 계좌가 설정되지 않았습니다.'
    : `실전 계좌 ${n}개가 설정돼 있습니다. 실전 활성화와 PANIC SELL은 실전 운영 화면에서 2차 확인으로만 합니다.`;
}

// 모드 띠: 실전 계좌가 하나라도 꺼짐이 아니면 실전 표시로 바꾼다(UI-05: 실전·모의 상태를 착각할 수 없게).
// 실전 엔진 스냅샷만 읽는다(UI-06). 5초마다 다시 읽고, 운영 화면이 새로 읽으면 바로 반영한다.
// 엔진이 답하지 않으면(UNKNOWN) 실전 계좌가 열려 있을 수 있으므로 "가상"으로 바꾸지 않고 마지막 확인 상태를 경고와 함께 둔다.
let lastUp = null;

function liveAccounts(view) {
  return view && view.status ? view.status.accounts.filter(a => a.state !== 'OFF') : [];
}

function describe(live) {
  return live.map(a => `${a.id} ${a.state === 'OPEN' ? '실전 매매 중' : a.state === 'PROTECTIVE' ? '보호 모드' : '여는 중'}`).join(', ');
}

function modeBand(view) {
  const band = document.getElementById('mode-band');
  const title = document.getElementById('mode-title');
  const detail = document.getElementById('mode-detail');
  const state = view ? (view.engineState || (view.engineReachable ? 'UP' : 'UNKNOWN')) : 'UNKNOWN';
  if (state === 'UP') lastUp = view;
  if (state === 'UNKNOWN') {
    const live = liveAccounts(lastUp);
    const at = lastUp && lastUp.asOf ? new Date(lastUp.asOf).toLocaleTimeString('en-GB') : '없음';
    band.classList.toggle('real', live.length > 0);
    band.classList.add('unknown');
    title.textContent = `실전 상태 확인 불가 · 마지막 확인 ${at}`;
    detail.textContent = live.length > 0
      ? `마지막 확인 때 실전 계좌 ${live.length}개가 열려 있었습니다(${describe(live)}). 실전 엔진은 계속 돌고 있을 수 있습니다.`
      : '실전 엔진이 응답하지 않습니다. 실전 계좌가 열려 있을 수 있으니 실전 엔진 터미널을 확인하세요.';
    return;
  }
  band.classList.remove('unknown');
  const live = state === 'UP' ? liveAccounts(view) : [];
  band.classList.toggle('real', live.length > 0);
  if (live.length > 0) {
    title.textContent = `실전 가동 중 · 실전 계좌 ${live.length}개`;
    detail.textContent = describe(live);
  } else {
    title.textContent = '실험실 · 가상 계좌만';
    detail.textContent = state === 'NOT_RUNNING'
      ? '실전 엔진이 꺼져 있습니다. 이 화면에서는 실전 주문이 나가지 않습니다.'
      : '실전 계좌는 모두 꺼져 있습니다. 이 화면에서는 실전 주문이 나가지 않습니다.';
  }
}

async function pollRealState() {
  // 실험실 서버가 답하지 않아도 엔진 상태는 모른다. 가상으로 바꾸지 않는다
  try { modeBand(await get('/api/real/status')); } catch { modeBand(null); }
}
document.addEventListener('real-status', e => modeBand(e.detail));
setInterval(pollRealState, 5000);
pollRealState();

window.addEventListener('hashchange', render);
loadSession().then(header).catch(e => toast(`실험실 서버에 연결하지 못했습니다: ${e.message}`, true)).finally(render);
