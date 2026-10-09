// 해시 라우터와 상단 상태 표시. 페이지 모듈은 view 함수(params) → Promise<Element>.
import { h, mount, toast } from './dom.js';
import { loadSession } from './api.js';
import { experimentsPage, experimentPage, runPage } from './pages/experiments.js';
import { profilesPage, profilePage, newProfilePage } from './pages/profiles.js';
import { settingsPage } from './pages/settings.js';

const routes = [
  [/^#\/experiments$/, 'experiments', () => experimentsPage()],
  [/^#\/experiments\/([A-Z0-9-]+)$/, 'experiments', m => experimentPage(m[1])],
  [/^#\/experiments\/([A-Z0-9-]+)\/runs\/([A-Za-z0-9-]+)$/, 'experiments', m => runPage(m[1], m[2])],
  [/^#\/profiles$/, 'profiles', () => profilesPage()],
  [/^#\/profiles\/new$/, 'profiles', () => newProfilePage()],
  [/^#\/profiles\/([a-z0-9-]+)(?:\/v(\d+))?$/, 'profiles', m => profilePage(m[1], m[2] ? Number(m[2]) : null)],
  [/^#\/settings$/, 'settings', () => settingsPage()],
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
    : `실전 계좌 ${n}개가 설정돼 있습니다. 실전 활성화와 상태는 실전 엔진 운영 화면에서만 다룹니다.`;
}

window.addEventListener('hashchange', render);
loadSession().then(header).catch(e => toast(`실험실 서버에 연결하지 못했습니다: ${e.message}`, true)).finally(render);
