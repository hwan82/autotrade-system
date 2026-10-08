// 전략 프로필 편집(UI-03): 목록, 버전 보기, 편집(항상 새 버전과 새 해시), 새 프로필·복제.
import { h, table, toast } from '../dom.js';
import { pagedSection } from './experiments.js';
import { get, post } from '../api.js';
import { shortHash, MARKET, STYLE } from '../format.js';

const enc = encodeURIComponent;
let cloneSource = null;

export async function profilesPage() {
  const first = await get('/api/profiles?page=0&size=50');
  const head = h('div', { class: 'page-head' },
    h('div', {}, h('h1', {}, '전략 프로필'),
      h('p', { class: 'muted' }, '매매 기법 하나의 파라미터 묶음입니다. 저장한 버전은 바뀌지 않고, 고치면 새 버전과 새 해시가 생깁니다.')),
    h('a', { class: 'btn', href: '#/profiles/new', onclick: () => { cloneSource = null; } }, '새 프로필 만들기'));
  if (first.total === 0) {
    return h('div', {}, head, h('div', { class: 'empty' },
      h('p', {}, '저장된 전략 프로필이 없습니다.'),
      h('p', { class: 'muted small' }, '새 프로필을 만들면 strategies/<프로필ID>/v1.yaml 파일로 저장됩니다.')));
  }
  return h('div', {}, head, pagedSection('/api/profiles', [
    { label: '프로필', render: p => h('a', { href: `#/profiles/${enc(p.id)}` }, p.id) },
    { label: '시장', render: p => MARKET[p.market] || p.market },
    { label: '스타일', render: p => STYLE[p.style] || p.style },
    { label: '최신 버전', num: true, render: p => `v${p.versions[p.versions.length - 1]}` },
    { label: '버전 수', num: true, render: p => p.versions.length },
    { label: '최신 해시', render: p => h('span', { class: 'hash', title: p.latestHash }, shortHash(p.latestHash)) },
  ], '프로필이 없습니다.'));
}

export async function profilePage(id, version) {
  const versions = await get(`/api/profiles/${enc(id)}`);
  const latest = versions[versions.length - 1];
  const current = version ? versions.find(v => v.version === version) : latest;
  if (!current) throw new Error(`${id}에 v${version}이 없습니다`);
  const isLatest = current.version === latest.version;

  const history = table([
    { label: '버전', render: v => v.version === current.version ? h('strong', {}, `v${v.version}`) : h('a', { href: `#/profiles/${enc(id)}/v${v.version}` }, `v${v.version}`) },
    { label: '해시', render: v => h('span', { class: 'hash', title: v.hash }, shortHash(v.hash)) },
    { label: '원본', render: v => v.basedOn || '–' },
    { label: '저장 시각(UTC)', render: v => v.createdAt.slice(0, 19).replace('T', ' ') },
    { label: '메모', render: v => v.note || '–' },
  ], [...versions].reverse());

  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/profiles' }, '전략 프로필'), ' / ', id),
    h('div', { class: 'page-head' },
      h('div', {}, h('h1', {}, `${id} v${current.version}`),
        h('p', { class: 'muted' }, `${MARKET[current.market]}, ${current.currency}, ${STYLE[current.style]}. 해시 `,
          h('span', { class: 'hash' }, current.hash))),
      h('button', { class: 'btn secondary', type: 'button', onclick: () => { cloneSource = current; location.hash = '#/profiles/new'; } }, '복제해 새 프로필 만들기')),
    isLatest ? null : h('p', { class: 'notice' }, `옛 버전을 보고 있습니다. 고치려면 최신 버전(v${latest.version})에서 시작하세요. `,
      h('a', { href: `#/profiles/${enc(id)}` }, '최신 버전 열기')),
    h('h2', {}, isLatest ? '파라미터 편집' : '파라미터'),
    isLatest ? editor(current, async (parameters, note) => {
      const saved = await post(`/api/profiles/${enc(id)}/versions`, { baseVersion: current.version, parameters, note });
      toast(`v${saved.version}을 저장했습니다. 새 해시 ${shortHash(saved.hash)}`);
      location.hash = `#/profiles/${enc(id)}/v${saved.version}`;
    }, '새 버전으로 저장') : readOnlyParams(current.parameters),
    h('h2', {}, '버전 이력'), history);
}

export async function newProfilePage() {
  const src = cloneSource;
  const idInput = h('input', { id: 'pid', required: true, pattern: '[a-z0-9][a-z0-9-]{0,63}', autocomplete: 'off',
    value: src ? `${src.id}-copy` : '' });
  const market = h('select', { id: 'pmarket' }, Object.entries(MARKET).map(([k, v]) => h('option', { value: k, selected: src && src.market === k }, v)));
  const style = h('select', { id: 'pstyle' }, Object.entries(STYLE).map(([k, v]) => h('option', { value: k, selected: src && src.style === k }, v)));
  const base = { parameters: src ? src.parameters : {} };
  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/profiles' }, '전략 프로필'), ' / 새 프로필'),
    h('h1', {}, src ? `${src.id} v${src.version} 복제` : '새 프로필'),
    h('p', { class: 'muted' }, '시장과 스타일은 버전 사이에 바꿀 수 없습니다. 다른 시장·스타일로 쓰려면 이렇게 새 프로필로 만듭니다.'),
    h('div', { class: 'form-grid' },
      h('label', { for: 'pid' }, '프로필 ID'), h('div', {}, idInput, h('p', { class: 'hint' }, '영문 소문자, 숫자, 하이픈. 64자 이내이고 나중에 바꿀 수 없습니다.')),
      h('label', { for: 'pmarket' }, '시장'), market,
      h('label', { for: 'pstyle' }, '매매 스타일'), style),
    h('h2', {}, '파라미터'),
    editor(base, async (parameters, note) => {
      const saved = await post('/api/profiles', {
        id: idInput.value.trim(), market: market.value, style: style.value, parameters, note,
        basedOn: src ? `${src.id}@${src.version}` : null,
      });
      cloneSource = null;
      toast(`${saved.id} v1을 저장했습니다`);
      location.hash = `#/profiles/${enc(saved.id)}`;
    }, '프로필 저장'));
}

function readOnlyParams(params) {
  const rows = Object.entries(params);
  if (rows.length === 0) return h('p', { class: 'muted' }, '파라미터가 없습니다.');
  return table([{ label: '키', render: r => h('span', { class: 'hash' }, r[0]) }, { label: '값', render: r => r[1] }], rows);
}

/** 키·값 행 편집기. 원본과 달라진 행은 표시한다. 저장은 onSave(parameters, note). */
function editor(base, onSave, saveLabel) {
  const original = base.parameters || {};
  const body = h('tbody', {});
  const addRow = (k = '', v = '') => {
    const key = h('input', { value: k, 'aria-label': '파라미터 키', placeholder: 'exit.stop-loss-pct', autocomplete: 'off' });
    const val = h('input', { value: v, 'aria-label': '파라미터 값', autocomplete: 'off' });
    const tr = h('tr', {}, h('td', {}, key), h('td', {}, val),
      h('td', {}, h('button', { class: 'link', type: 'button', onclick: () => { tr.remove(); mark(); } }, '삭제')));
    const mark = () => body.querySelectorAll('tr').forEach(row => {
      const [kk, vv] = row.querySelectorAll('input');
      row.classList.toggle('changed', original[kk.value.trim()] !== vv.value.trim());
    });
    key.addEventListener('input', mark);
    val.addEventListener('input', mark);
    body.append(tr);
    mark();
  };
  Object.entries(original).forEach(([k, v]) => addRow(k, v));
  if (Object.keys(original).length === 0) addRow();
  const note = h('input', { id: 'pnote', maxlength: 500, placeholder: '무엇을 왜 바꿨는지', autocomplete: 'off' });
  const save = h('button', { class: 'btn', type: 'button' }, saveLabel);
  save.addEventListener('click', async () => {
    const parameters = {};
    for (const row of body.querySelectorAll('tr')) {
      const [k, v] = [...row.querySelectorAll('input')].map(i => i.value.trim());
      if (!k && !v) continue;
      if (!k || !v) { toast('키와 값을 모두 채우거나 행을 지우세요', true); return; }
      if (k in parameters) { toast(`같은 키가 두 번 있습니다: ${k}`, true); return; }
      parameters[k] = v;
    }
    save.disabled = true;
    try { await onSave(parameters, note.value.trim()); } catch (e) { toast(e.message, true); } finally { save.disabled = false; }
  });
  return h('div', {},
    h('div', { class: 'table-wrap' }, h('table', { class: 'param-table' },
      h('thead', {}, h('tr', {}, h('th', { scope: 'col' }, '키'), h('th', { scope: 'col' }, '값'), h('th', { scope: 'col' }, ''))), body)),
    h('p', { class: 'hint' }, '키는 영문 소문자·숫자·점·하이픈입니다(예: entry.signal, sizing.risk-per-trade-pct). 값은 문자열로 저장되고 해시에 들어갑니다. 바뀐 행은 노란색입니다.'),
    h('div', { class: 'actions' }, h('button', { class: 'btn secondary', type: 'button', onclick: () => addRow() }, '파라미터 추가')),
    h('div', { class: 'form-grid' }, h('label', { for: 'pnote' }, '변경 메모'), note),
    h('div', { class: 'actions' }, save));
}
