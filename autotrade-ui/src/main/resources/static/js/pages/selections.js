// 선정 프로필 편집(UI-03, ADR-007 2.1): 목록, 버전 보기, 조건 행 편집(항상 새 버전과 새 해시), 새 프로필·복제.
// 조건 행 선택지(지표·연산·갱신 주기·상품 유형)는 서버 카탈로그(/api/selection-catalog)에서 받는다. 키 해석과 검증은 저장할 때 서버가 한다.
import { h, table, toast } from '../dom.js';
import { pagedSection } from './experiments.js';
import { get, getAll, post } from '../api.js';
import { shortHash, MARKET, STYLE, REFRESH } from '../format.js';

const enc = encodeURIComponent;
let cloneSource = null;
let catalogCache = null;

const OP = { GE: '≥ 이상', GT: '> 초과', LE: '≤ 이하', LT: '< 미만' };
const KIND = { BARS: '일봉', SHARES: '상장주식수', FINANCIAL: '재무', DISCLOSURE: '공시' };
const PERIOD = { WINDOW: { key: 'window', label: '거래일' }, YEARS: { key: 'years', label: '사업연도' } };
// 지표 이름(카탈로그 키 → 화면 이름). 모르는 키는 키 그대로 보인다.
const METRIC = {
  'op-margin-growth': '영업이익률 증가율', 'total-assets-growth': '자산총계 증가율', 'revenue-growth': '매출액 증가율',
  'op-margin': '영업이익률', 'debt-ratio': '부채비율', 'market-cap': '시가총액(원)', 'volume-ratio': '거래량 배율',
  return: '수익률', 'avg-turnover': '평균 거래대금(원)',
  'day-range': '전일 변동폭', 'close-strength': '전일 종가 강도', 'volume-spike': '전일 거래량 급증 배율',
  'disclosure-count': '공시 건수',
};
const GATE_KEY = /^gate\.(\d{2})\.([a-z-]+)$/;
const KNOWN = new Set(['hypothesis', 'refresh', 'max-symbols', 'filter.kinds', 'filter.min-price', 'filter.min-bar-days',
  'score.metric', 'score.window', 'score.years', 'score.order']);

async function catalog() {
  if (!catalogCache) catalogCache = await get('/api/selection-catalog');
  return catalogCache;
}

export async function selectionsPage() {
  const first = await get('/api/selections?page=0&size=50');
  const head = h('div', { class: 'page-head' },
    h('div', {}, h('h1', {}, '선정 프로필'),
      h('p', { class: 'muted' }, '어떤 종목을 매매 후보로 고를지 정한 조건 묶음입니다. 전략 프로필(매매지표)과 시장·스타일이 같으면 조합으로 실험합니다. 저장한 버전은 바뀌지 않고, 고치면 새 버전과 새 해시가 생깁니다.')),
    h('a', { class: 'btn', href: '#/selections/new', onclick: () => { cloneSource = null; } }, '새 선정 프로필 만들기'));
  if (first.total === 0) {
    return h('div', {}, head, h('div', { class: 'empty' },
      h('p', {}, '저장된 선정 프로필이 없습니다.'),
      h('p', { class: 'muted small' }, '새 프로필을 만들면 selections/<선정ID>/v1.yaml 파일로 저장됩니다.')));
  }
  return h('div', {}, head, pagedSection('/api/selections', [
    { label: '선정 프로필', render: p => h('a', { href: `#/selections/${enc(p.id)}` }, p.id) },
    { label: '시장', render: p => MARKET[p.market] || p.market },
    { label: '스타일', render: p => STYLE[p.style] || p.style },
    { label: '최신 버전', num: true, render: p => `v${p.versions[p.versions.length - 1]}` },
    { label: '버전 수', num: true, render: p => p.versions.length },
    { label: '최신 선정 해시', render: p => h('span', { class: 'hash', title: p.latestHash }, shortHash(p.latestHash)) },
  ], '선정 프로필이 없습니다.'));
}

export async function selectionPage(id, version) {
  const [versions, cat] = await Promise.all([
    get(`/api/selections/${enc(id)}`), catalog()]);
  const latest = versions[versions.length - 1];
  const current = version ? versions.find(v => v.version === version) : latest;
  if (!current) throw new Error(`${id}에 v${version}이 없습니다`);
  const isLatest = current.version === latest.version;

  const history = table([
    { label: '버전', render: v => v.version === current.version ? h('strong', {}, `v${v.version}`) : h('a', { href: `#/selections/${enc(id)}/v${v.version}` }, `v${v.version}`) },
    { label: '선정 해시', render: v => h('span', { class: 'hash', title: v.hash }, shortHash(v.hash)) },
    { label: '원본', render: v => v.basedOn || '–' },
    { label: '저장 시각(UTC)', render: v => v.createdAt.slice(0, 19).replace('T', ' ') },
    { label: '메모', render: v => v.note || '–' },
  ], [...versions].reverse());

  // 조합할 수 있는 전략 프로필: 시장·스타일이 같아야 한다(STR-02). 서버가 시장·스타일로 걸러 끝까지 준다
  const partners = await getAll(`/api/profiles?market=${enc(current.market)}&style=${enc(current.style)}`);

  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/selections' }, '선정 프로필'), ' / ', id),
    h('div', { class: 'page-head' },
      h('div', {}, h('h1', {}, `${id} v${current.version}`),
        h('p', { class: 'muted' }, `${MARKET[current.market]}, ${STYLE[current.style] || current.style}, 갱신 ${REFRESH[current.refresh] || current.refresh}, 최대 ${current.maxSymbols}종목. 선정 해시 `,
          h('span', { class: 'hash' }, current.hash))),
      h('button', { class: 'btn secondary', type: 'button', onclick: () => { cloneSource = current; location.hash = '#/selections/new'; } }, '복제해 새 프로필 만들기')),
    current.catalogProblem ? h('p', { class: 'notice warn' }, `지금 카탈로그로 해석되지 않는 버전입니다: ${current.catalogProblem}`) : null,
    isLatest ? null : h('p', { class: 'notice' }, `옛 버전을 보고 있습니다. 고치려면 최신 버전(v${latest.version})에서 시작하세요. `,
      h('a', { href: `#/selections/${enc(id)}` }, '최신 버전 열기')),
    h('h2', {}, isLatest ? '선정 조건 편집' : '선정 조건'),
    isLatest ? conditionEditor(current, current.style, cat, async (parameters, note) => {
      // 원본과 선정 결과가 같은 새 버전이면 저장 전에 알린다(gate 재번호·상품 유형 순서·값 표기만 바뀐 경우, D-074)
      const same = await post(`/api/selections/${enc(id)}/versions/${current.version}/compare`, { parameters });
      if (same.sameSelection && !same.identical && !window.confirm(
        `이 변경으로는 선정 결과(고르는 종목·순위)가 v${current.version}과 같습니다. 바뀐 것: ${same.differences.join(', ')}.\n`
        + '그래도 저장하면 새 선정 해시가 생겨 같은 선정이 교차표에서 따로 집계됩니다. 저장할까요?')) return;
      const saved = await post(`/api/selections/${enc(id)}/versions`, { baseVersion: current.version, parameters, note });
      toast(`v${saved.version}을 저장했습니다. 새 선정 해시 ${shortHash(saved.hash)}`);
      location.hash = `#/selections/${enc(id)}/v${saved.version}`;
    }, '새 버전으로 저장') : readOnly(current.parameters),
    h('h2', {}, '조합할 수 있는 전략 프로필'),
    h('p', { class: 'muted small' }, '선정과 매매지표는 시장·스타일이 같을 때만 조합이 됩니다(STR-02). 선정 결과는 신규 진입 후보만 정하고, 선정에서 빠진 보유 종목을 청산하지 않습니다(D-044).'),
    partners.length === 0
      ? h('p', { class: 'muted' }, `${MARKET[current.market]} · ${STYLE[current.style] || current.style} 전략 프로필이 없습니다.`)
      : table([
        { label: '전략 프로필', render: p => h('a', { href: `#/profiles/${enc(p.id)}` }, p.id) },
        { label: '최신 버전', num: true, render: p => `v${p.versions[p.versions.length - 1]}` },
        { label: '최신 해시', render: p => h('span', { class: 'hash', title: p.latestHash }, shortHash(p.latestHash)) },
      ], partners),
    h('h2', {}, '버전 이력'), history);
}

export async function newSelectionPage() {
  const src = cloneSource;
  const cat = await catalog();
  const idInput = h('input', { id: 'sid', required: true, pattern: '[a-z0-9][a-z0-9-]{0,63}', autocomplete: 'off',
    value: src ? `${src.id}-copy` : '' });
  const market = h('select', { id: 'smarket' }, Object.entries(MARKET).map(([k, v]) => h('option', { value: k, selected: src && src.market === k }, v)));
  const style = h('select', { id: 'sstyle' }, Object.entries(STYLE).map(([k, v]) => h('option', { value: k, selected: src ? src.style === k : k === 'SWING' }, v)));
  const host = h('div', {});
  const base = { parameters: src ? src.parameters : {} };
  const draw = () => host.replaceChildren(conditionEditor(base, style.value, cat, async (parameters, note) => {
    const saved = await post('/api/selections', {
      id: idInput.value.trim(), market: market.value, style: style.value, parameters, note,
      basedOn: src ? `${src.id}@${src.version}` : null,
    });
    cloneSource = null;
    toast(`${saved.id} v1을 저장했습니다`);
    location.hash = `#/selections/${enc(saved.id)}`;
  }, '선정 프로필 저장'));
  // 스타일을 바꾸면 갱신 주기 선택지가 바뀐다(단타는 장전, 스윙·중기는 장 마감 뒤). 입력한 값은 그대로 옮긴다
  style.addEventListener('change', () => { base.parameters = host.firstChild.collect(); draw(); });
  draw();
  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/selections' }, '선정 프로필'), ' / 새 프로필'),
    h('h1', {}, src ? `${src.id} v${src.version} 복제` : '새 선정 프로필'),
    h('p', { class: 'muted' }, '시장과 스타일은 버전 사이에 바꿀 수 없습니다. 다른 시장·스타일로 쓰려면 이렇게 새 프로필로 만듭니다.'),
    h('div', { class: 'form-grid' },
      h('label', { for: 'sid' }, '선정 ID'), h('div', {}, idInput, h('p', { class: 'hint' }, '영문 소문자, 숫자, 하이픈. 64자 이내이고 나중에 바꿀 수 없습니다.')),
      h('label', { for: 'smarket' }, '시장'), market,
      h('label', { for: 'sstyle' }, '매매 스타일'), style),
    host);
}

function readOnly(params) {
  const rows = Object.entries(params);
  return table([{ label: '키', render: r => h('span', { class: 'hash' }, r[0]) }, { label: '값', render: r => r[1] }], rows);
}

function metricLabel(key) {
  return METRIC[key] ? `${METRIC[key]} (${key})` : key;
}

function metricOptions(cat, selected, withNone) {
  return [
    withNone ? h('option', { value: '', selected: !selected }, '순위 없음') : null,
    // 순위에는 공시 건수를 쓰지 않는다(자격 조건에만, D-123). 저장된 값이면 보이게 둔다
    ...cat.metrics.filter(m => !withNone || m.kind !== 'DISCLOSURE' || m.key === selected).map(m => h('option', { value: m.key, selected: m.key === selected }, `${metricLabel(m.key)} · ${KIND[m.kind] || m.kind}`)),
  ];
}

/** 지표의 기간 입력: WINDOW(거래일)·YEARS(사업연도)면 켜고, 기간 없는 지표면 끈다. */
function periodInput(cat, metricSelect, value) {
  const input = h('input', { class: 'narrow', inputmode: 'numeric', value: value || '', 'aria-label': '기간', autocomplete: 'off' });
  const unit = h('span', { class: 'muted small' });
  const sync = () => {
    const m = cat.metrics.find(x => x.key === metricSelect.value);
    const p = m && PERIOD[m.param];
    input.disabled = !p;
    if (!p) input.value = '';
    unit.textContent = p ? p.label : '기간 없음';
  };
  metricSelect.addEventListener('change', sync);
  sync();
  return { el: h('span', { class: 'period inline-fields' }, input, unit), input, key: () => {
    const m = cat.metrics.find(x => x.key === metricSelect.value);
    return m && PERIOD[m.param] ? PERIOD[m.param].key : null;
  } };
}

/**
 * 조건 행 편집기(UI-03 "조건 행(지표, 연산, 기준값, 기간)"). 평평한 파라미터 맵 ↔ 폼. 자격 조건은 화면 순서대로
 * gate.01, gate.02 … 로 다시 번호를 붙인다. 카탈로그에 없는 키(notes.* 등)는 아래 "그 밖의 키"에 그대로 둔다.
 * 반환 요소의 collect()는 지금 폼 값을 파라미터 맵으로 준다(스타일 전환 때 값 옮기기용).
 */
function conditionEditor(base, style, cat, onSave, saveLabel) {
  const p = { ...(base.parameters || {}) };
  const hypothesis = h('textarea', { id: 'shyp', rows: 3, maxlength: 500, placeholder: '이 조건을 통과한 종목은 왜 같은 매매지표의 선정 없음보다 비용 차감 후 성과가 나은가(한 문장)' });
  hypothesis.value = p.hypothesis || '';
  const allowed = cat.refresh.filter(r => r.styles.includes(style));
  const refresh = h('select', { id: 'srefresh' }, allowed.map(r => h('option', { value: r.name, selected: p.refresh === r.name }, REFRESH[r.name] || r.name)));
  const maxSymbols = h('input', { id: 'smax', class: 'narrow', inputmode: 'numeric', value: p['max-symbols'] || '', autocomplete: 'off' });

  const kindSet = new Set((p['filter.kinds'] || '').split(',').map(x => x.trim()).filter(Boolean));
  const kindBoxes = cat.kinds.map(k => {
    const box = h('input', { type: 'checkbox', value: k, checked: kindSet.has(k) });
    return { k, box, el: h('label', { class: 'check' }, box, ` ${k}`) };
  });
  const minPrice = h('input', { id: 'sminprice', class: 'narrow', inputmode: 'decimal', value: p['filter.min-price'] || '', autocomplete: 'off' });
  const minBars = h('input', { id: 'sminbars', class: 'narrow', inputmode: 'numeric', value: p['filter.min-bar-days'] || '', autocomplete: 'off' });

  // 자격 조건(gate.<nn>.*)
  const gates = {};
  for (const [k, v] of Object.entries(p)) {
    const m = k.match(GATE_KEY);
    if (m) (gates[m[1]] = gates[m[1]] || {})[m[2]] = v;
  }
  const gateBody = h('tbody', {});
  const gateRows = [];
  const addGate = (g = {}) => {
    const metric = h('select', { 'aria-label': '지표' }, metricOptions(cat, g.metric, false));
    const op = h('select', { 'aria-label': '연산' }, cat.ops.map(o => h('option', { value: o, selected: o === (g.op || 'GE') }, OP[o] || o)));
    const value = h('input', { class: 'narrow', inputmode: 'decimal', value: g.value || '', 'aria-label': '기준값', autocomplete: 'off' });
    const period = periodInput(cat, metric, g.window || g.years);
    // 공시 유형(gate.<nn>.types, disclosure-count만, D-123): 공시 지표를 고르면 켠다
    const types = h('input', { class: 'narrow', value: g.types || '', 'aria-label': '공시 유형', placeholder: 'B,I', autocomplete: 'off' });
    const typesEl = h('span', { class: 'inline-fields' }, h('span', { class: 'muted small' }, ' 유형 '), types);
    const syncTypes = () => {
      const m = cat.metrics.find(x => x.key === metric.value);
      const on = !!m && m.kind === 'DISCLOSURE';
      typesEl.hidden = !on;
      if (!on) types.value = '';
    };
    metric.addEventListener('change', syncTypes);
    syncTypes();
    const row = { metric, op, value, period, types };
    const tr = h('tr', {}, h('td', { class: 'num seq' }), h('td', {}, metric), h('td', {}, op), h('td', {}, value), h('td', {}, period.el, typesEl),
      h('td', {}, h('button', { class: 'link', type: 'button', onclick: () => { tr.remove(); gateRows.splice(gateRows.indexOf(row), 1); renumber(); } }, '삭제')));
    row.tr = tr;
    gateRows.push(row);
    gateBody.append(tr);
    renumber();
  };
  const renumber = () => gateRows.forEach((r, i) => { r.tr.querySelector('.seq').textContent = `gate.${String(i + 1).padStart(2, '0')}`; });
  Object.keys(gates).sort().forEach(n => addGate(gates[n]));

  // 순위(score.*)
  const scoreMetric = h('select', { id: 'sscore', 'aria-label': '순위 지표' }, metricOptions(cat, p['score.metric'], true));
  const scorePeriod = periodInput(cat, scoreMetric, p['score.window'] || p['score.years']);
  const scoreOrder = h('select', { 'aria-label': '순위 방향' },
    h('option', { value: 'DESC', selected: p['score.order'] !== 'ASC' }, '큰 값이 앞(DESC)'),
    h('option', { value: 'ASC', selected: p['score.order'] === 'ASC' }, '작은 값이 앞(ASC)'));

  // 그 밖의 키(notes.* 등). 카탈로그에 없는 키는 저장할 때 서버가 거절하므로 notes.* 만 남는 것이 정상이다
  const extraBody = h('tbody', {});
  const addExtra = (k = '', v = '') => {
    const key = h('input', { value: k, 'aria-label': '키', placeholder: 'notes.source', autocomplete: 'off' });
    const val = h('input', { value: v, 'aria-label': '값', autocomplete: 'off' });
    const tr = h('tr', {}, h('td', {}, key), h('td', {}, val),
      h('td', {}, h('button', { class: 'link', type: 'button', onclick: () => tr.remove() }, '삭제')));
    extraBody.append(tr);
  };
  Object.entries(p).filter(([k]) => !KNOWN.has(k) && !GATE_KEY.test(k)).forEach(([k, v]) => addExtra(k, v));

  const collect = () => {
    const out = {};
    const put = (k, v) => { const t = (v || '').trim(); if (t) out[k] = t; };
    put('hypothesis', hypothesis.value);
    put('refresh', refresh.value);
    put('max-symbols', maxSymbols.value);
    put('filter.kinds', kindBoxes.filter(x => x.box.checked).map(x => x.k).join(','));
    put('filter.min-price', minPrice.value);
    put('filter.min-bar-days', minBars.value);
    gateRows.forEach((r, i) => {
      const n = `gate.${String(i + 1).padStart(2, '0')}`;
      put(`${n}.metric`, r.metric.value);
      put(`${n}.op`, r.op.value);
      put(`${n}.value`, r.value.value);
      const pk = r.period.key();
      if (pk) put(`${n}.${pk}`, r.period.input.value);
      put(`${n}.types`, r.types.value.replace(/\s+/g, ''));
    });
    if (scoreMetric.value) {
      put('score.metric', scoreMetric.value);
      const pk = scorePeriod.key();
      if (pk) put(`score.${pk}`, scorePeriod.input.value);
      put('score.order', scoreOrder.value);
    }
    for (const row of extraBody.querySelectorAll('tr')) {
      const [k, v] = [...row.querySelectorAll('input')].map(i => i.value.trim());
      if (!k && !v) continue;
      if (!k || !v) throw new Error('그 밖의 키는 키와 값을 모두 채우거나 행을 지우세요');
      if (k in out) throw new Error(`같은 키가 두 번 있습니다: ${k}`);
      out[k] = v;
    }
    return out;
  };

  const note = h('input', { id: 'snote', maxlength: 500, placeholder: '무엇을 왜 바꿨는지', autocomplete: 'off' });
  const save = h('button', { class: 'btn', type: 'button' }, saveLabel);
  save.addEventListener('click', async () => {
    let parameters;
    try { parameters = collect(); } catch (e) { toast(e.message, true); return; }
    save.disabled = true;
    try { await onSave(parameters, note.value.trim()); } catch (e) { toast(e.message, true); } finally { save.disabled = false; }
  });

  const root = h('div', { class: 'cond-editor' },
    h('div', { class: 'form-grid' },
      h('label', { for: 'shyp' }, '가설'), hypothesis,
      h('label', { for: 'srefresh' }, '갱신 주기'), h('div', {}, refresh, h('p', { class: 'hint' }, '단타는 매일 장전·매일 장전(하루 늦춘 일봉)·장중 순위 시각, 스윙·중기는 장 마감 뒤 갱신만 쓸 수 있습니다.')),
      h('label', { for: 'smax' }, '최대 종목 수'), maxSymbols),
    h('h3', {}, '하드 필터'),
    h('div', { class: 'form-grid' },
      h('span', {}, '상품 유형'), h('div', {}, h('div', { class: 'kinds' }, kindBoxes.map(x => x.el)), h('p', { class: 'hint' }, '고르지 않으면 상품 유형으로 거르지 않습니다.')),
      h('label', { for: 'sminprice' }, '최소 가격(원)'), h('div', {}, minPrice, h('p', { class: 'hint' }, '직전 거래일 종가(원가격) 이상. 비우면 없음.')),
      h('label', { for: 'sminbars' }, '최소 일봉 거래일'), h('div', {}, minBars, h('p', { class: 'hint' }, '직전 N거래일 일봉이 모두 있어야 합니다. 비우면 없음.'))),
    h('h3', {}, '자격 조건 (모두 만족, AND)'),
    h('div', { class: 'table-wrap' }, h('table', { class: 'param-table' },
      h('thead', {}, h('tr', {}, ['순번', '지표', '연산', '기준값', '기간', ''].map(x => h('th', { scope: 'col' }, x)))), gateBody)),
    h('p', { class: 'hint' }, '비율·증가율은 소수(0.5 = 50%), 금액은 원입니다. 경계값은 이상·이하가 포함, 초과·미만이 제외합니다. 재무 지표는 국내(KRX)만 됩니다.'),
    h('div', { class: 'actions' }, h('button', { class: 'btn secondary', type: 'button', onclick: () => addGate() }, '조건 추가')),
    h('h3', {}, '순위 (선택)'),
    h('div', { class: 'form-grid' },
      h('label', { for: 'sscore' }, '순위 지표'), h('div', {}, h('div', { class: 'inline-fields' }, scoreMetric, scorePeriod.el, scoreOrder),
        h('p', { class: 'hint' }, '조건을 통과한 종목이 최대 종목 수보다 많으면 이 순서로 자릅니다.'))),
    h('h3', {}, '그 밖의 키'),
    h('div', { class: 'table-wrap' }, h('table', { class: 'param-table' },
      h('thead', {}, h('tr', {}, h('th', { scope: 'col' }, '키'), h('th', { scope: 'col' }, '값'), h('th', { scope: 'col' }, ''))), extraBody)),
    h('p', { class: 'hint' }, '메모용 notes.* 키만 둘 수 있습니다. 값은 해시에 들어갑니다.'),
    h('div', { class: 'actions' }, h('button', { class: 'btn secondary', type: 'button', onclick: () => addExtra() }, '키 추가')),
    h('div', { class: 'form-grid' }, h('label', { for: 'snote' }, '변경 메모'), note),
    h('div', { class: 'actions' }, save));
  root.collect = () => { try { return collect(); } catch { return p; } };
  return root;
}
