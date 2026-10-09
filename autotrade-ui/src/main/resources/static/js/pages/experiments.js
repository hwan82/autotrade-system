// 실험 비교 대시보드(UI-02): 목록, 실험 상세(리더보드 + 자산 곡선), 계좌 드릴다운(체결·왕복·거절 사유).
import { h, table } from '../dom.js';
import { get } from '../api.js';
import { equityChart, colorSwatches } from '../chart.js';
import { money, number, pct, ratio, signClass, time, shortHash, STATUS, MARKET, SIDE, INTENT } from '../format.js';

const enc = encodeURIComponent;

export async function experimentsPage() {
  const first = await get('/api/experiments?page=0&size=50');
  const head = h('div', { class: 'page-head' }, h('div', {},
    h('h1', {}, '실험'),
    h('p', { class: 'muted' }, '같은 기간·시드·비용 모델로 여러 전략 계좌를 돌린 결과입니다. 실험을 열면 계좌끼리 비교합니다.')));
  if (first.total === 0) {
    return h('div', {}, head, h('div', { class: 'empty' },
      h('p', {}, '아직 실행한 실험이 없습니다.'),
      h('p', { class: 'muted small' }, '실험은 녹화 데이터와 전략 구현이 준비되면 이 화면에서 만들 수 있습니다. 지금은 실험 실행기가 결과를 등록하면 여기에 나타납니다. 실험실을 다시 띄우면 목록이 비워집니다(결과 DB 저장 전).')));
  }
  return h('div', {}, head, pagedSection('/api/experiments', [
    { label: '실험', render: e => h('a', { href: `#/experiments/${enc(e.id)}` }, e.id) },
    { label: '시장', render: e => MARKET[e.market] || e.market },
    { label: '기간', render: e => `${e.from} ~ ${e.to}` },
    { label: '시드머니', num: true, render: e => money(e.seedCapital, e.currency) },
    { label: '시드', num: true, render: e => e.randomSeed },
    { label: '계좌', num: true, render: e => `${e.completedRuns}/${e.runCount}${e.failedRuns ? ` (실패 ${e.failedRuns})` : ''}` },
    { label: '방식', render: e => e.clockMode === 'REPLAY' ? '녹화 리플레이' : '실시간 모의' },
    { label: '데이터', render: e => e.dataSnapshotVersion },
  ], '실험이 없습니다.'));
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

  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/experiments' }, '실험'), ' / ', id),
    h('h1', {}, id),
    e.codeDirty ? h('p', { class: 'notice warn' }, '커밋되지 않은 코드로 돌린 실험입니다. 같은 결과가 다시 나온다는 보장이 없습니다.') : null,
    facts,
    h('h2', {}, '계좌 비교'),
    h('p', { class: 'muted small' }, `${detail.benchmarkNote} 모든 지표는 비용(수수료·세금·슬리피지) 차감 후입니다. 열 제목을 눌러 정렬합니다.`),
    boardHost,
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
    { label: '프로필', render: r => h('a', { href: `#/profiles/${enc(r.profileId)}/v${r.profileVersion}` }, `${r.profileId} v${r.profileVersion}`) },
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
    ['프로필', h('span', {}, h('a', { href: `#/profiles/${enc(s.profileId)}/v${s.profileVersion}` }, `${s.profileId} v${s.profileVersion}`),
      ' ', h('span', { class: 'hash', title: s.profileHash }, shortHash(s.profileHash)))],
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

