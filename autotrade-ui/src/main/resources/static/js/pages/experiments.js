// 실험 비교 대시보드(UI-02): 실행 중인 실험 진행 상태(jobs.js), 목록, 실험 상세(리더보드 + 자산 곡선), 계좌 드릴다운(체결·왕복·거절 사유).
import { h, table } from '../dom.js';
import { get, post } from '../api.js';
import { jobsSection } from './jobs.js';
import { equityChart, colorSwatches } from '../chart.js';
import { money, number, pct, ratio, signClass, time, shortHash, delta, combinationLabel, STATUS, MARKET, STYLE, REFRESH, VALIDITY, SIDE, INTENT } from '../format.js';

const enc = encodeURIComponent;

export async function experimentsPage() {
  // 폴더 목록이 실패해도 실험 목록은 보인다
  const [first, folders] = await Promise.all([get('/api/experiments?page=0&size=50'),
    get(`/api/experiment-folders?page=0&size=${FOLDER_PAGE}`).catch(err => ({ error: err.message }))]);
  const head = h('div', { class: 'page-head' }, h('div', {},
    h('h1', {}, '실험'),
    h('p', { class: 'muted' }, '같은 기간·시드·비용 모델로 여러 전략 계좌를 돌린 결과입니다. 실험을 열면 계좌끼리 비교합니다.')),
    h('a', { class: 'btn', href: '#/experiments/new' }, '새 실험 등록'));
  const list = first.total === 0
    ? h('div', { class: 'empty' },
      h('p', {}, '화면에 올라온 실험이 없습니다.'),
      h('p', { class: 'muted small' }, '조합 실험은 실행 명령으로 돌린 뒤 아래 실험 폴더에서 불러옵니다. 실험실을 다시 띄우면 목록이 비워지므로 다시 불러옵니다(결과 DB 저장 전).'))
    : pagedSection('/api/experiments', [
      { label: '실험', render: e => h('a', { href: `#/experiments/${enc(e.id)}` }, e.id) },
      { label: '시장', render: e => MARKET[e.market] || e.market },
      { label: '기간', render: e => `${e.from} ~ ${e.to}` },
      { label: '시드머니', num: true, render: e => money(e.seedCapital, e.currency) },
      { label: '시드', num: true, render: e => e.randomSeed },
      { label: '계좌', num: true, render: e => `${e.completedRuns}/${e.runCount}${e.failedRuns ? ` (실패 ${e.failedRuns})` : ''}` },
      { label: '방식', render: e => e.clockMode === 'REPLAY' ? '녹화 리플레이' : '실시간 모의' },
      { label: '데이터', render: e => e.dataSnapshotVersion },
    ], '실험이 없습니다.');
  return h('div', {}, head, jobsSection(), h('h2', {}, '실험 결과'), list, h('h2', {}, '실험 폴더'), folderSection(folders));
}

const FOLDER_PAGE = 200;

const FOLDER_STATE = {
  INVALID: '등록 파일 오류',
  LOADED: '화면에 있음',
  READY: '불러올 수 있음',
  NO_BUNDLE: '결과 묶음 없음',
  NOT_RUN: '아직 실행 안 함',
};

/** 조합 실험 폴더(D-062): 실행 명령이 쓴 결과 묶음을 확인해 화면에 올린다. 실험을 다시 돌리지 않는다. */
function folderSection(res) {
  if (res.error) return h('p', { class: 'notice warn' }, `실험 폴더를 읽지 못했습니다: ${res.error}`);
  const folders = res.items;
  const note = h('p', { class: 'muted small' },
    '조합 실험 폴더(experiments/)입니다. 실행 명령(runCombinationExperiment)이 쓴 결과 묶음을 detail.txt와 대조한 뒤 올립니다. '
    + '결과 묶음이 없으면 같은 명령으로 다시 돌리면 같은 결과로 만들어집니다.');
  if (folders.length === 0) return h('div', {}, note, h('p', { class: 'muted' }, '조합 실험 폴더가 없습니다.'));
  const message = h('div', {});
  const load = async (f, button) => {
    button.disabled = true;
    message.replaceChildren(h('p', { class: 'notice' }, `${f.id} 결과를 확인하는 중입니다.`));
    try {
      const res = await post(`/api/experiment-folders/${enc(f.id)}/load`);
      location.hash = `#/experiments/${enc(res.id)}`;
    } catch (err) {
      button.disabled = false;
      message.replaceChildren(h('p', { class: 'notice warn' }, `${f.id}: ${err.message}`));
    }
  };
  return h('div', {}, note, message, table([
    { label: '실험', render: f => f.state === 'LOADED' ? h('a', { href: `#/experiments/${enc(f.id)}` }, f.id) : f.id },
    { label: '질문', render: f => f.question },
    { label: '기간', render: f => f.period },
    { label: '조합', num: true, render: f => number(f.combinations) },
    { label: '상태', render: f => h('span', {}, FOLDER_STATE[f.state] || f.state,
      f.lastError ? h('span', { class: 'sub warn-text' }, ` ${f.state === 'INVALID' ? '' : '실패: '}${f.lastError}`) : null) },
    { label: '', render: f => f.state === 'READY'
      ? h('button', { class: 'btn secondary', type: 'button', onclick: (ev) => load(f, ev.currentTarget) }, '불러오기')
      : f.state === 'NOT_RUN' || f.state === 'NO_BUNDLE'
        ? h('details', {}, h('summary', {}, '실행 명령'),
          h('p', { class: 'hint' }, '등록 파일을 커밋한 뒤 터미널에서 돌립니다.'),
          h('pre', { class: 'yaml' }, `./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=${f.id} -PverifyDeterminism`))
        : null },
  ], folders), res.total > folders.length ? h('p', { class: 'muted small' }, `최근 ${folders.length}개만 보입니다(전체 ${number(res.total)}개).`) : null);
}

const METRIC_COLUMNS = [
  { key: 'totalReturn', label: '총수익률', fmt: pct, signed: true },
  { key: 'excessReturn', label: '초과수익', fmt: pct, signed: true },
  { key: 'cagr', label: 'CAGR', fmt: pct, signed: true },
  { key: 'maxDrawdown', label: 'MDD', fmt: pct, lowerBetter: true },
  { key: 'sharpe', label: '샤프', fmt: ratio },
  { key: 'profitFactor', label: 'PF', fmt: ratio },
  { key: 'winRate', label: '승률', fmt: pct },
  { key: 'roundTripCount', label: '왕복', fmt: v => number(v) },
  { key: 'avgHoldingDays', label: '평균 보유일', fmt: v => number(v, 1) },
  { key: 'turnover', label: '회전율', fmt: ratio },
  { key: 'costShare', label: '비용 비중', fmt: pct, lowerBetter: true },
];

export async function experimentPage(id) {
  const [detail, overlay] = await Promise.all([
    get(`/api/experiments/${enc(id)}`), get(`/api/experiments/${enc(id)}/equity`)]);
  const e = detail.summary;
  const colorIndex = new Map(overlay.series.filter(x => x.points.length > 0).map((x, i) => [x.runId, i]));

  const facts = h('dl', { class: 'facts' }, [
    ['시장·통화', `${MARKET[e.market] || e.market}, ${e.currency}`],
    ['기간', `${e.from} ~ ${e.to}`],
    ['계좌별 시드머니', money(e.seedCapital, e.currency)],
    ['난수 시드', e.randomSeed],
    ['비용 모델', e.costModelId],
    ['비용 표 해시', shortHash(e.costTableHash)],
    ['체결 모델', e.executionModelId],
    ['데이터 스냅샷', e.dataSnapshotVersion],
    ['실행 방식', e.clockMode === 'REPLAY' ? '녹화 리플레이' : '실시간 모의'],
    ['코드', `${shortHash(e.codeCommit)}${e.codeDirty ? ' (커밋 안 된 변경 포함)' : ''}`],
  ].map(([k, v]) => h('div', {}, h('dt', {}, k), h('dd', {}, String(v)))));

  let sortKey = 'totalReturn';
  let desc = true;
  const boardHost = h('div', {});
  const drawBoard = () => boardHost.replaceChildren(leaderboard(id, detail.runs, e.currency, sortKey, desc, colorIndex, (k) => {
    if (sortKey === k) desc = !desc; else { sortKey = k; desc = !(METRIC_COLUMNS.find(c => c.key === k) || {}).lowerBetter; }
    drawBoard();
  }));
  drawBoard();
  const hasSelections = detail.runs.some(r => r.selectionId);

  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/experiments' }, '실험'), ' / ', id),
    h('h1', {}, id),
    e.codeDirty ? h('p', { class: 'notice warn' }, '커밋되지 않은 코드로 돌린 실험입니다. 같은 결과가 다시 나온다는 보장이 없습니다.') : null,
    facts,
    h('h2', {}, '계좌 비교'),
    h('p', { class: 'muted small' }, `${detail.benchmarkNote} 모든 지표는 비용(수수료·세금·슬리피지) 차감 후입니다. 열 제목을 눌러 정렬합니다.`),
    boardHost,
    hasSelections ? h('h2', {}, '선정 × 매매지표 교차표') : null,
    hasSelections ? crossTableSection(id) : null,
    h('h2', {}, '자산 곡선'),
    equityChart(overlay));
}

function metricValue(run, key) {
  if (!run.metrics) return null;
  const v = run.metrics[key];
  return v === null || v === undefined ? null : Number(v);
}

function leaderboard(id, runs, currency, sortKey, desc, colorIndex, onSort) {
  const sorted = [...runs].sort((a, b) => {
    const va = metricValue(a, sortKey), vb = metricValue(b, sortKey);
    if (va === null && vb === null) return a.runId.localeCompare(b.runId);
    if (va === null) return 1;
    if (vb === null) return -1;
    return (desc ? vb - va : va - vb) || a.runId.localeCompare(b.runId);
  });
  const sortHeader = (key, label) => {
    const active = key === sortKey;
    return h('button', { type: 'button', 'aria-sort': active ? (desc ? 'descending' : 'ascending') : null, onclick: () => onSort(key) },
      label, active ? (desc ? ' ▾' : ' ▴') : '');
  };
  const cols = [
    { label: '순위', num: true, render: r => r.status === 'COMPLETED' ? String(sorted.filter(x => x.status === 'COMPLETED').indexOf(r) + 1) : '–' },
    { label: '계좌', render: r => h('span', {}, colorIndex.has(r.runId) ? h('span', { class: 'swatch', 'data-color': colorIndex.get(r.runId) }) : null,
      h('a', { href: `#/experiments/${enc(id)}/runs/${enc(r.runId)}` }, r.runId)) },
    { label: '조합 (선정 × 전략)', render: r => combinationCell(r) },
    { label: '선정 기록', render: r => selectionStatsCell(r) },
    { label: '상태', render: r => STATUS[r.status] + (r.failureReason ? `: ${r.failureReason}` : '') },
    { label: '최종 평가금액', num: true, render: r => money(r.finalEquity, currency) },
    ...METRIC_COLUMNS.map(c => ({
      label: sortHeader(c.key, c.label), num: true,
      render: r => {
        const v = r.metrics ? r.metrics[c.key] : null;
        return h('span', { class: c.signed ? signClass(v) : null }, c.fmt(v));
      },
    })),
    { label: '비용 합계', num: true, render: r => money(r.metrics && r.metrics.totalCosts, currency) },
    { label: '거절', num: true, render: r => number(r.rejectionCount) },
  ];
  const t = table(cols, sorted, { rowClass: r => r.status === 'FAILED' ? 'failed' : null });
  colorSwatches(t);
  return t;
}

/** 조합 칸: 선정(없으면 "선정 없음") × 전략, 조합 해시. */
function combinationCell(r) {
  return h('span', { class: 'combo' },
    r.selectionId
      ? h('a', { href: `#/selections/${enc(r.selectionId)}/v${r.selectionVersion}` }, `${r.selectionId} v${r.selectionVersion}`)
      : h('span', { class: 'muted' }, '선정 없음'),
    ' × ',
    h('a', { href: `#/profiles/${enc(r.profileId)}/v${r.profileVersion}` }, `${r.profileId} v${r.profileVersion}`),
    h('span', { class: 'hash sub', title: `조합 해시 ${r.combinationHash}` }, shortHash(r.combinationHash)));
}

/** 선정 기록 요약: 결과가 있던 날/전체 날, 결과가 없어 신규 진입을 막은 날, 정정 대체 규칙 적용 수. */
function selectionStatsCell(r) {
  const s = r.selection;
  if (!s) return '–';
  const days = s.validDays + s.invalidDays;
  const title = s.invalidReasons.map(x => `${VALIDITY[x.code] || x.code} ${x.count}일`).join(', ');
  return h('span', {},
    h('span', { title: title || null }, `결과 ${number(s.validDays)}/${number(days)}일`),
    s.invalidDays > 0 ? h('span', { class: 'badge warn', title }, `진입 막은 날 ${number(s.invalidDays)}`) : null,
    s.restatementFallback > 0 ? h('span', { class: 'badge bias', title: '정정 대체 규칙(MD-10 (2))을 쓴 평가 수. 정정 존재를 미리 아는 편향이 있습니다' }, `정정 대체 ${number(s.restatementFallback)}`) : null,
    s.unknownResults > 0 ? h('span', { class: 'badge', title: '결과 본문이 등록되지 않아 정정 대체 수에서 빠진 결과 수' }, `본문 없음 ${number(s.unknownResults)}`) : null);
}

// 교차표 지표: 비율이면 %·%p, 아니면 소수 둘째 자리
const CROSS = {
  totalReturn: ['총수익률', true], excessReturn: ['초과수익', true], cagr: ['CAGR', true], maxDrawdown: ['MDD', true],
  sharpe: ['샤프', false], profitFactor: ['PF', false], winRate: ['승률', true], avgHoldingDays: ['평균 보유일', false],
  turnover: ['회전율', false], costShare: ['비용 비중', true],
};

/**
 * 선정 × 매매지표 교차표(UI-02). 칸마다 지표 값, 조합 해시, 대조군 대비 차이(서버 계산)를 보인다.
 * 행 대조 열은 기본으로 ID에 random이 든 전략 프로필(무작위 진입 대조군, 조합 실험 절차 1장 3번)을 고르고 바꿀 수 있다.
 */
function crossTableSection(id) {
  const host = h('div', {});
  let metric = 'totalReturn';
  let rowControl;
  const draw = async () => {
    const q = `metric=${enc(metric)}${rowControl ? `&rowControl=${enc(rowControl)}` : ''}`;
    const t = await get(`/api/experiments/${enc(id)}/cross-table?${q}`);
    if (rowControl === undefined) {
      const random = t.columns.find(c => c.id && c.id.includes('random'));
      if (random) { rowControl = random.key; return draw(); }
      rowControl = null;
    }
    const [label, isRatio] = CROSS[metric] || [metric, false];
    const fmt = v => (isRatio ? pct(v) : ratio(v));
    const metricSel = h('select', { 'aria-label': '교차표 지표' }, t.metrics.map(m => h('option', { value: m, selected: m === metric }, (CROSS[m] || [m])[0])));
    metricSel.addEventListener('change', () => { metric = metricSel.value; draw(); });
    const ctlSel = h('select', { 'aria-label': '행 대조 열' },
      h('option', { value: '', selected: !rowControl }, '없음'),
      t.columns.map(c => h('option', { value: c.key, selected: c.key === rowControl }, `${c.id} v${c.version}`)));
    ctlSel.addEventListener('change', () => { rowControl = ctlSel.value || null; draw(); });
    const cell = (row, col) => {
      const c = t.cells.find(x => x.row === row.key && x.column === col.key);
      if (!c) return h('td', { class: 'num muted' }, '–');
      return h('td', { class: `num xcell${c.status === 'FAILED' ? ' failed' : ''}` },
        h('a', { href: `#/experiments/${enc(id)}/runs/${enc(c.runId)}`, class: signClass(isRatio ? c.value : null) },
          c.status === 'COMPLETED' ? fmt(c.value) : (STATUS[c.status] || c.status)),
        row.none ? h('div', { class: 'sub' }, '대조군') : h('div', { class: 'sub', title: '같은 매매지표의 선정 없음 칸 대비' }, `선정 없음 대비 ${delta(c.deltaVsNoSelection, isRatio)}`),
        rowControl && col.key !== rowControl ? h('div', { class: 'sub', title: '같은 선정의 대조 열 칸 대비' }, `대조 열 대비 ${delta(c.deltaVsRowControl, isRatio)}`) : null,
        h('div', { class: 'sub hash', title: `조합 해시 ${c.combinationHash}` }, shortHash(c.combinationHash)));
    };
    host.replaceChildren(
      h('div', { class: 'actions' }, h('label', {}, '지표 ', metricSel), h('label', {}, ' 행 대조 열 ', ctlSel)),
      h('div', { class: 'table-wrap' }, h('table', { class: 'cross' },
        h('thead', {}, h('tr', {}, h('th', { scope: 'col' }, `선정 ↓ · 매매지표 → (${label})`),
          t.columns.map(c => h('th', { scope: 'col', class: c.key === rowControl ? 'num control' : 'num' },
            h('a', { href: `#/profiles/${enc(c.id)}/v${c.version}` }, `${c.id} v${c.version}`),
            c.key === rowControl ? h('div', { class: 'sub' }, '행 대조 열') : null)))),
        h('tbody', {}, t.rows.map(r => h('tr', { class: r.none ? 'control' : null },
          h('th', { scope: 'row' }, r.none ? '선정 없음 (대조군)' : h('a', { href: `#/selections/${enc(r.id)}/v${r.version}` }, `${r.id} v${r.version}`)),
          t.columns.map(c => cell(r, c))))))),
      h('p', { class: 'muted small' }, '칸 = 계좌 하나(조합 하나)입니다. 차이는 같은 실험 안의 대조 칸 값을 뺀 것이고 비율 지표는 %p입니다. 비교한 조합이 많을수록 우연히 좋아 보이는 칸이 생기므로 판단은 조합 실험 절차의 다중 비교 기준을 따릅니다.'));
  };
  draw().catch(err => host.replaceChildren(h('p', { class: 'notice warn' }, err.message)));
  return host;
}

export async function runPage(id, runId) {
  const detail = await get(`/api/experiments/${enc(id)}`);
  const run = detail.runs.find(r => r.runId === runId);
  if (!run) throw new Error('이 실험에 없는 계좌입니다');
  const e = detail.summary;
  const base = `/api/experiments/${enc(id)}/runs/${enc(runId)}`;
  const summary = await get(`${base}/summary`);

  const fills = pagedSection(`${base}/fills`, [
    { label: '체결 시각', render: f => time(f.fillTime, e.market) },
    { label: '종목코드', render: f => f.symbol },
    { label: '종목명', render: f => nameCell(f.symbolName) },
    { label: '구분', render: f => `${SIDE[f.side] || f.side}·${INTENT[f.intent] || f.intent}` },
    { label: '수량', num: true, render: f => number(f.quantity) },
    { label: '체결가', num: true, render: f => money(f.price, f.currency) },
    { label: '수수료', num: true, render: f => money(f.commission, f.currency) },
    { label: '세금', num: true, render: f => money(f.tax, f.currency) },
    { label: '슬리피지', num: true, render: f => money(f.slippage, f.currency) },
    { label: '실현손익', num: true, render: f => h('span', { class: signClass(f.realizedPnl) }, f.realizedPnl === null ? '–' : money(f.realizedPnl, f.currency)) },
    { label: '멱등 키', render: f => h('span', { class: 'hash' }, `${f.key} #${f.fillSeq}`) },
    { label: '신호→체결 순번', render: f => `${f.signalEventSeq} → ${f.fillEventSeq}` },
  ], '체결이 없습니다.');

  const trips = pagedSection(`${base}/round-trips`, [
    { label: '로트', render: t => h('span', { class: 'hash' }, t.lotId) },
    { label: '종목코드', render: t => t.symbol },
    { label: '종목명', render: t => nameCell(t.symbolName) },
    { label: '진입일', render: t => t.entryDate },
    { label: '청산일', render: t => t.exitDate },
    { label: '손익(비용 차감)', num: true, render: t => h('span', { class: signClass(t.pnl) }, money(t.pnl, e.currency)) },
  ], '청산까지 끝난 왕복 거래가 없습니다.');

  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/experiments' }, '실험'), ' / ', h('a', { href: `#/experiments/${enc(id)}` }, id), ' / ', runId),
    h('h1', {}, runId),
    summaryCard(summary),
    e.market === 'US' ? h('p', { class: 'muted small' }, '시각은 미국 동부(ET)와 한국(KST)을 함께 적습니다.') : null,
    summary.selectionId ? h('h2', {}, '날짜별 선정 기록') : null,
    summary.selectionId ? selectionDaysSection(id, base) : null,
    h('h2', {}, '체결 내역'), fills,
    h('h2', {}, '왕복 거래'), trips,
    h('h2', {}, '거절 사유'), rejectionSection(base));
}

function nameCell(name) {
  return name === null || name === undefined ? '–' : name;
}

/** 계좌 상세 머리: 계좌·기간·시드, 마지막 평가금액·수익률·누적 실현손익·비용, 기간 끝 보유 종목. 값은 서버가 계산해 준다. */
function summaryCard(s) {
  const cur = s.currency;
  const failed = s.status === 'FAILED';
  const tile = (label, value, sub, cls) => h('div', { class: 'tile' },
    h('div', { class: 'tile-label' }, label),
    h('div', { class: `tile-value ${cls || ''}`.trim() }, value),
    sub ? h('div', { class: 'tile-sub' }, sub) : null);
  const tiles = h('div', { class: 'tiles' },
    tile('마지막 평가금액', money(s.lastEquity, cur),
      s.lastEquityDate ? `${s.lastEquityDate} 마감 · 현금 ${money(s.lastCash, cur)}` : '자산 곡선 기록 없음'),
    tile('총수익률 (평가금액 기준)', s.totalReturn === null || s.totalReturn === undefined ? '–' : pct(s.totalReturn),
      `시드머니 ${money(s.seedCapital, cur)} 대비`, signClass(s.totalReturn)),
    tile('누적 실현손익', money(s.realizedPnl, cur),
      `매도 체결의 손익 합(비용 차감 후) · 왕복 ${number(s.roundTripCount)}건`, signClass(s.realizedPnl)),
    tile('비용 합계 (수수료+세금)', money(s.costs.feesTotal, cur),
      `수수료 ${money(s.costs.commission, cur)} · 세금 ${money(s.costs.tax, cur)} · 슬리피지 ${money(s.costs.slippage, cur)}(체결가에 반영, 합계 제외)`));
  const facts = h('dl', { class: 'facts' }, [
    ['계좌 ID', s.accountId],
    ['선정 프로필', s.selectionId
      ? h('span', {}, h('a', { href: `#/selections/${enc(s.selectionId)}/v${s.selectionVersion}` }, `${s.selectionId} v${s.selectionVersion}`),
        ' ', h('span', { class: 'hash', title: s.selectionHash }, shortHash(s.selectionHash)))
      : '선정 없음(유니버스 전체, 실험 대조군 전용)'],
    ['전략 프로필', h('span', {}, h('a', { href: `#/profiles/${enc(s.profileId)}/v${s.profileVersion}` }, `${s.profileId} v${s.profileVersion}`),
      ' ', h('span', { class: 'hash', title: s.profileHash }, shortHash(s.profileHash)))],
    ['조합 해시', h('span', { class: 'hash', title: s.combinationHash }, shortHash(s.combinationHash))],
    ['상태', failed ? '실패' : (STATUS[s.status] || s.status)],
    ['실험 기간', `${s.from} ~ ${s.to} (거래일 ${number(s.tradingDays)}일 기록)`],
    ['시장·통화', `${MARKET[s.market] || s.market}, ${cur}`],
    ['시드머니', money(s.seedCapital, cur)],
    ['체결', `${number(s.fillCount)}건`],
  ].map(([k, v]) => h('div', {}, h('dt', {}, k), h('dd', {}, v))));

  let holdings;
  if (s.holdings === null || s.holdings === undefined) {
    holdings = h('p', { class: 'muted' }, '체결 원장에서 보유 종목을 계산하지 못했습니다.');
  } else if (s.holdings.length === 0) {
    holdings = h('p', { class: 'muted' }, failed ? '실패 직전까지 보유한 종목이 없습니다.' : '기간 끝에 보유한 종목이 없습니다.');
  } else {
    holdings = table([
      { label: '종목코드', render: x => x.symbol },
      { label: '종목명', render: x => nameCell(x.symbolName) },
      { label: '수량', num: true, render: x => number(x.quantity) },
      { label: '평균 단가', num: true, render: x => money(x.averagePrice, cur) },
      { label: '보유 원가', num: true, render: x => money(x.costBasis, cur) },
    ], s.holdings);
  }
  return h('section', { class: 'summary-card', 'aria-label': '계좌 요약' },
    failed ? h('p', { class: 'notice warn' }, `실패한 계좌입니다. 아래 값은 실패 직전까지의 기록입니다.${s.failureReason ? ` 사유: ${s.failureReason}` : ''}`) : null,
    tiles, facts,
    h('h2', {}, failed ? '실패 직전 보유 종목' : '기간 끝 보유 종목'),
    holdings,
    h('p', { class: 'muted small' }, '평균 단가와 보유 원가는 매수 수수료를 포함한 원가 기준입니다. 평가손익은 종가 기록이 없어 표시하지 않습니다.'),
    s.symbolNameCount === 0 ? h('p', { class: 'muted small' },
      '종목명은 설정 폴더 symbols/KRX.csv가 있어야 보입니다. gradlew :autotrade-app-gateway:symbolMaster로 만듭니다.') : null);
}

export function pagedSection(url, columns, emptyText, size = 50, extra = () => '') {
  const host = h('div', {});
  const load = async (page) => {
    const res = await get(`${url}?page=${page}&size=${size}${extra()}`);
    const p = res.items ? res : res.page;
    host.replaceChildren(...[p.total === 0 ? h('p', { class: 'muted' }, emptyText) : table(columns, p.items), pager(p, load)].filter(Boolean));
    return res;
  };
  host.load = load;
  load(0).catch(err => host.replaceChildren(h('p', { class: 'notice warn' }, err.message)));
  return host;
}

function pager(p, load) {
  const pages = Math.max(1, Math.ceil(p.total / p.size));
  if (pages <= 1) return null;
  return h('div', { class: 'pager' },
    h('button', { class: 'btn secondary', type: 'button', disabled: p.page === 0, onclick: () => load(p.page - 1) }, '이전'),
    `${p.page + 1} / ${pages}쪽 (전체 ${number(p.total)}건)`,
    h('button', { class: 'btn secondary', type: 'button', disabled: p.page + 1 >= pages, onclick: () => load(p.page + 1) }, '다음'));
}

function rejectionSection(base) {
  const host = h('div', {});
  let filter = null;
  const draw = async (page = 0) => {
    const q = filter ? `&stage=${enc(filter.stage)}&code=${enc(filter.code)}` : '';
    const res = await get(`${base}/rejections?page=${page}&size=50${q}`);
    if (res.counts.length === 0) {
      host.replaceChildren(h('p', { class: 'muted' }, '거절되거나 취소된 신호·주문이 없습니다.'));
      return;
    }
    const max = Math.max(...res.counts.map(c => c.count));
    const bars = h('div', { class: 'bars' }, res.counts.flatMap(c => {
      const bar = h('div', { class: 'bar' });
      bar.style.width = `${(c.count / max) * 100}%`;
      return [
        h('button', { type: 'button', onclick: () => { filter = c; draw(0); } }, `${c.stage === 'RISK' ? '리스크' : '거래소'}: ${c.code}`),
        bar, h('span', {}, `${number(c.count)}건`)];
    }));
    const p = res.page;
    host.replaceChildren(...[
      h('p', { class: 'muted small' }, 'RISK는 리스크 검증(블랙리스트 포함)에서, EXCHANGE는 가상 거래소에서 거절·취소된 것입니다. 사유를 누르면 그 사유만 봅니다.'),
      bars,
      filter ? h('p', { class: 'small' }, `${filter.stage}:${filter.code}만 보는 중 `, h('button', { class: 'link', type: 'button', onclick: () => { filter = null; draw(0); } }, '전체 보기')) : null,
      table([
        { label: '이벤트 순번', num: true, render: j => j.eventSeq },
        { label: '종목코드', render: j => j.symbol },
        { label: '종목명', render: j => nameCell(j.symbolName) },
        { label: '단계', render: j => j.stage },
        { label: '사유', render: j => j.code },
        { label: '상세', render: j => j.detail || '–' },
        { label: '멱등 키', render: j => h('span', { class: 'hash' }, j.key) },
      ], p.items),
      pager(p, draw)].filter(Boolean));
  };
  draw(0).catch(err => host.replaceChildren(h('p', { class: 'notice warn' }, err.message)));
  return host;
}


/** 선정 있는 계좌의 날짜별 선정 기록(run_selection_day). 결과가 없던 날은 그 계좌 신규 진입이 0건이다. */
function selectionDaysSection(id, base) {
  return h('div', {},
    h('p', { class: 'muted small' }, '선정 결과는 신규 진입 후보만 정합니다. 결과가 없는 날은 신규 진입을 막고 보유 관리·청산은 그대로 합니다(STR-19, D-044). 날짜를 누르면 그날 쓴 선정 결과를 봅니다.'),
    pagedSection(`${base}/selection-days`, [
      { label: '거래일', render: d => d.resultAvailable
        ? h('a', { href: `#/experiments/${enc(id)}/selection-results/${d.resultHash}` }, d.date) : d.date },
      { label: '판정', render: d => h('span', { class: d.validity === 'VALID' ? null : 'badge warn' }, VALIDITY[d.validity] || d.validity) },
      { label: '선정 종목', num: true, render: d => number(d.symbolCount) },
      { label: '막은 신규 진입', num: true, render: d => number(d.blockedEntries) },
      { label: '정정 대체 규칙', num: true, render: d => d.restatementFallback === null || d.restatementFallback === undefined
        ? (d.resultHash ? '본문 없음' : '–')
        : (d.restatementFallback > 0 ? h('span', { class: 'badge bias' }, number(d.restatementFallback)) : '0') },
      { label: '결과 해시', render: d => d.resultHash ? h('span', { class: 'hash', title: d.resultHash }, shortHash(d.resultHash)) : '–' },
    ], '선정 기록이 없습니다.'));
}

const FILTERS = { all: '전체', passed: '선정', failed: '탈락', fallback: '정정 대체 규칙 적용' };

/** 선정 결과 하나(ADR-007 2.6): 적용 범위, 입력 기준일, 선정 종목, 탈락 사유별 건수, 정정 대체 규칙 표시, 평가 행. */
export async function selectionResultPage(id, hash) {
  const base = `/api/experiments/${enc(id)}/selection-results/${hash}`;
  const r = await get(base);
  const sel = r.selection;
  const inp = r.inputs;
  const facts = h('dl', { class: 'facts' }, [
    ['선정 프로필', h('span', {}, h('a', { href: `#/selections/${enc(sel.id)}/v${sel.version}` }, `${sel.id} v${sel.version}`),
      ' ', h('span', { class: 'hash', title: sel.hash }, shortHash(sel.hash)))],
    ['시장·스타일', `${MARKET[sel.market] || sel.market}, ${STYLE[sel.style] || sel.style}`],
    ['갱신 주기', REFRESH[sel.refresh] || sel.refresh],
    ['적용 거래일', r.applyFrom === r.applyTo ? r.applyFrom : `${r.applyFrom} ~ ${r.applyTo}`],
    ['일봉 기준(마지막 거래일)', `${inp.barsLastTradingDate} · ${inp.barsSnapshotId}`],
    ['상장주식수 기준일', inp.sharesAsOfDate ? `${inp.sharesAsOfDate} · ${inp.sharesSnapshotId}` : '쓰지 않음'],
    ['재무 마지막 수집', inp.financialsSnapshotId ? `${inp.financialsLastCollectedAt || '모름'} · ${inp.financialsSnapshotId}` : '쓰지 않음'],
    ['평가 종목 / 선정', `${number(r.universeCount)} / ${number(r.selectedCount)}`],
    ['내용 해시', h('span', { class: 'hash', title: r.contentHash }, shortHash(r.contentHash))],
  ].map(([k, v]) => h('div', {}, h('dt', {}, k), h('dd', {}, v))));

  let filter = 'all';
  let reason = '';
  const evalHost = h('div', {});
  const filterSel = h('select', { 'aria-label': '평가 행 거르기' }, Object.entries(FILTERS).map(([k, v]) => h('option', { value: k }, v)));
  const drawEvals = () => {
    const note = reason ? h('p', { class: 'small' }, `탈락 사유 ${reason}만 보는 중 `,
      h('button', { class: 'link', type: 'button', onclick: () => { reason = ''; drawEvals(); } }, '전체 사유')) : null;
    evalHost.replaceChildren(...[note, pagedSection(`${base}/evaluations`, [
      { label: '종목코드', render: e => e.code },
      { label: '종목명', render: e => e.name || '–' },
      { label: '결과', render: e => e.passed ? h('strong', {}, `선정 ${e.rank}위`) : '탈락' },
      { label: '탈락 사유', render: e => e.reasons.length === 0 ? '–' : e.reasons.join(', ') },
      { label: '조건별 값', render: e => h('span', { class: 'kv' }, Object.entries(e.values).map(([k, v]) => h('span', {}, `${k}=${v}`))) },
      { label: '정정 대체', render: e => e.restatementFallback ? h('span', { class: 'badge bias', title: 'MD-10 (2): 정정 전 값을 구하지 못해 직전 기간 값을 썼습니다' }, '대체 규칙') : '–' },
    ], '조건에 맞는 평가 행이 없습니다.', 50,
    () => `&filter=${enc(filter)}${reason ? `&reason=${enc(reason)}` : ''}`)].filter(Boolean));
  };
  filterSel.addEventListener('change', () => { filter = filterSel.value; drawEvals(); });
  drawEvals();

  const max = Math.max(1, ...r.reasonCounts.map(c => c.count));
  const bars = r.reasonCounts.length === 0 ? h('p', { class: 'muted' }, '탈락 종목이 없습니다.') : h('div', { class: 'bars' }, r.reasonCounts.flatMap(c => {
    const bar = h('div', { class: 'bar' });
    bar.style.width = `${(c.count / max) * 100}%`;
    return [h('button', { type: 'button', onclick: () => { reason = c.code; filter = 'failed'; filterSel.value = 'failed'; drawEvals(); } }, c.code), bar, h('span', {}, `${number(c.count)}종목`)];
  }));

  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/experiments' }, '실험'), ' / ', h('a', { href: `#/experiments/${enc(id)}` }, id), ' / 선정 결과 ', shortHash(hash)),
    h('h1', {}, `${sel.id} v${sel.version} · ${r.applyFrom}`),
    r.restatementFallback > 0
      ? h('p', { class: 'notice warn' }, `정정 대체 규칙을 쓴 종목 ${number(r.restatementFallback)}개가 있습니다. 정정 전 값을 구하지 못해 정정 접수일까지 직전 기간 값을 쓴 경우로, 정정이 있다는 사실을 미리 아는 편향이 있습니다(MD-10 (2)). 이 결과로 낸 성과는 그만큼 낙관적일 수 있습니다. "정정 대체 규칙 적용"으로 걸러 볼 수 있습니다.`)
      : h('p', { class: 'muted small' }, '정정 대체 규칙(MD-10 (2))을 쓴 종목이 없습니다.'),
    facts,
    h('h2', {}, '선정 종목'),
    r.selected.length === 0 ? h('p', { class: 'muted' }, '선정된 종목이 없습니다. 이 결과를 쓴 날은 신규 진입이 없습니다.')
      : table([
        { label: '순위', num: true, render: x => x.rank },
        { label: '종목코드', render: x => x.code },
        { label: '종목명', render: x => x.name || '–' },
      ], r.selected),
    r.symbolNameCount === 0 ? h('p', { class: 'muted small' }, '종목명은 설정 폴더 symbols/KRX.csv가 있어야 보입니다.') : null,
    h('h2', {}, '탈락 사유별 종목 수'),
    h('p', { class: 'muted small' }, '한 종목이 여러 사유로 탈락하면 사유마다 셉니다. 사유를 누르면 그 사유의 종목만 봅니다.'),
    bars,
    h('h2', {}, '평가 행'),
    h('div', { class: 'actions' }, h('label', {}, '거르기 ', filterSel)),
    evalHost);
}
