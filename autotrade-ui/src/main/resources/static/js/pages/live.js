// 실시간 모의 화면(ADR-010 2.4, D-076): 실시간 모의 등록 파일 고르기, 시작·멈춤·끝내기, 계좌별 상태와 최근 공백(DataGap).
// 모의 계좌만 다룬다. 실전 주문 경로와 무관하고 증권사를 직접 부르지 않는다(시세는 게이트웨이 전달 소켓으로만 받음).
// 실시간 갱신은 3초 폴링이다(SSE는 UI-01 이연 항목, D-079). 화면을 떠나면 폴링을 멈춘다.
// 계좌 ID를 누르면 그 계좌의 체결 내역과 거절 사유를 본다(#/live/runs/<계좌>, D-120). 표 모양은 실험 계좌 상세와 같다.
// 선정 조합 계좌(D-124)는 계좌 순서 뒤쪽에 "선정@버전 x 전략@버전"으로 보이고, 그날 적용한 선정(후보 수, 뺀 종목 NOT_SUBSCRIBED·
// BLACKLISTED, SELECTION_MISSING 사유)과 다음 거래일 선정 준비 결과를 따로 보인다. 계산은 서버 준비 스레드가 하고 화면은 읽기만 한다.
import { h, toast, table } from '../dom.js';
import { get, post } from '../api.js';
import { money, number, time } from '../format.js';
import { pagedSection, fillColumns, rejectionSection } from './experiments.js';

const enc = encodeURIComponent;
const POLL_MS = 3000;
const GAP_LIMIT = 100;

const FEED = {
  NEW: { label: '연결 전', tone: '' },
  CONNECTING: { label: '연결 중', tone: 'warn-state' },
  CONNECTED: { label: '받는 중', tone: 'ok' },
  DISCONNECTED: { label: '끊김 · 다시 연결 대기', tone: 'warn-state' },
  CLOSED: { label: '닫힘', tone: '' },
};

/** 공백 사유(DataGap.GapReason). 그 구간 이벤트는 계좌에 가지 않았다. */
export const GAP_REASON = {
  DISCONNECTED: '증권사 연결 끊김',
  CONSUMER_OVERFLOW: '받는 큐 넘침',
  RECORDER_OVERFLOW: '녹화 큐 넘침',
  SOURCE_SWITCH: '시세 소스 전환',
  RECORDER_RESTART: '게이트웨이 재기동',
};

let timer = null;

export async function livePage() {
  stopPolling();
  const host = h('div', { 'aria-live': 'polite' });
  const message = h('div', {});
  let busy = false;
  // 등록 파일 목록은 모든 실험 폴더를 읽으므로 폴링마다 읽지 않는다. 화면에 들어올 때, 새로 읽기, 명령 뒤, 세션이 끝나 목록으로 돌아올 때만
  let regs = null;
  const refresh = async (withRegistrations = true) => {
    const view = await get('/api/live/status');
    const active = view.experimentId !== '';
    const needRegs = !active && (withRegistrations || regs === null);
    const [gaps, fresh] = await Promise.all([
      active && !view.restoring ? get(`/api/live/gaps?limit=${GAP_LIMIT}`).catch(e => ({ error: e.message })) : null,
      needRegs ? get('/api/live/registrations').catch(e => ({ error: e.message })) : null,
    ]);
    if (needRegs) regs = fresh;
    if (active) regs = null;
    host.replaceChildren(active ? sessionView(view, gaps, act) : idleView(view, regs, act));
  };
  // 명령 하나가 끝날 때까지 다른 명령 버튼을 막는다(두 번 누름 방지)
  const act = async (label, fn) => {
    if (busy) return;
    busy = true;
    host.querySelectorAll('button').forEach(b => { b.disabled = true; });
    try {
      const msg = await fn();
      message.replaceChildren(msg ? h('p', { class: 'notice' }, msg) : '');
    } catch (e) {
      message.replaceChildren(h('p', { class: 'notice warn' }, `${label}: ${e.message}`));
    } finally {
      busy = false;
      await refresh().catch(e => toast(e.message, true));
    }
  };
  await refresh();
  timer = setInterval(() => {
    if (!document.body.contains(host)) { stopPolling(); return; }
    if (!busy) refresh(false).catch(() => { /* 다음 주기에 다시 읽는다. 실패를 매번 띄우지 않는다 */ });
  }, POLL_MS);
  return h('div', {},
    h('div', { class: 'page-head' },
      h('div', {},
        h('h1', {}, '실시간 모의'),
        h('p', { class: 'muted' }, '게이트웨이가 받은 실시간 시세로 가상 계좌들을 돌립니다. 모의 계좌만 다루며 실전 주문은 나가지 않습니다.')),
      h('div', { class: 'actions' },
        h('button', { class: 'btn secondary', type: 'button', onclick: () => refresh().catch(e => toast(e.message, true)) }, '새로 읽기'))),
    message, host);
}

function stopPolling() {
  if (timer) clearInterval(timer);
  timer = null;
}
window.addEventListener('hashchange', () => { if (!location.hash.startsWith('#/live')) stopPolling(); });

// ---- 세션이 없을 때: 등록 파일 고르기 ----

function idleView(view, regs, act) {
  // 경고 색은 서버가 준 종류로 고른다(문구를 비교하지 않음, PR #96 R5)
  const last = view.stopReason
    ? h('div', { class: `notice${view.stopKind === 'NORMAL' ? '' : ' warn'}` },
      h('p', {}, `마지막 세션: ${view.stopReason}`))
    : null;
  const start = (r) => act(`${r.id} 시작`, async () => {
    await post(`/api/live/sessions/${enc(r.id)}/start`);
    return r.hasJournal
      ? `${r.id}: 입력 기록으로 계좌를 되살리는 중입니다. 끝나면 게이트웨이에 연결합니다.`
      : `${r.id}: 세션을 열고 있습니다.`;
  });
  let list;
  if (regs.error) {
    list = h('p', { class: 'notice warn' }, `실시간 모의 등록 파일을 읽지 못했습니다: ${regs.error}`);
  } else if (regs.length === 0) {
    list = h('div', { class: 'empty' },
      h('p', {}, '실시간 모의 등록 파일이 없습니다.'),
      h('p', { class: 'muted small' }, 'experiments/<실험ID>/experiment.yaml에 input: "live"로 등록하고 커밋한 뒤 여기서 시작합니다. 형식은 docs/runbooks/stage-6-live-session.md를 보세요.'));
  } else {
    list = table([
      { label: '실험', render: r => h('div', {}, h('strong', {}, r.id),
        r.error ? h('div', { class: 'small warn-text' }, `등록 파일 오류: ${r.error}`) : h('div', { class: 'small muted' }, r.question)) },
      { label: '기간', render: r => r.period || '–' },
      { label: '계좌(전략 프로필) · 종목', render: r => r.error ? '–' : `${r.profiles.join(', ')} · 종목 ${number(r.symbols)}개` },
      { label: '기록', render: r => r.finished ? '끝냄(보고서 있음)' : r.hasJournal ? '입력 기록 있음' : '새로 시작' },
      { label: '', render: r => r.error || r.finished ? null : h('button', {
        class: 'btn', type: 'button', onclick: () => start(r),
        title: r.hasJournal ? '입력 기록으로 계좌를 되살린 뒤 이어서 받습니다' : null,
      }, r.hasJournal ? '이어서 시작' : '시작') },
    ], regs);
  }
  return h('div', {}, last,
    h('h2', {}, '실시간 모의 등록 파일'),
    h('p', { class: 'muted small' }, '한 번에 세션 하나만 돕니다. 실험실을 다시 띄우면 자동으로 다시 시작하지 않습니다. 끝낸 실험은 같은 ID로 이어 쓰지 않습니다(리플레이 확인 runLiveReplayCheck로 넘어갑니다).'),
    list);
}

// ---- 세션이 돌 때: 상태, 계좌, 공백 ----

function sessionView(view, gaps, act) {
  const s = view.session;
  const stopBtn = h('button', {
    class: 'btn secondary', type: 'button',
    onclick: () => act('멈춤', async () => {
      await post('/api/live/stop');
      return `${view.experimentId}: 멈췄습니다. 다시 시작하면 입력 기록으로 계좌를 되살린 뒤 이어서 받습니다.`;
    }),
  }, '멈춤');
  const finishBtn = h('button', {
    class: 'btn', type: 'button', disabled: view.restoring,
    title: view.restoring ? '되살리기가 끝난 뒤 할 수 있습니다' : null,
    onclick: () => finishDialog(view, act),
  }, '끝내기');
  const head = h('div', { class: 'panic-all' },
    h('p', {}, h('strong', {}, view.experimentId), ' ', statusBadge(view)),
    h('div', { class: 'row-actions' }, stopBtn, finishBtn));
  if (view.restoring || !s) {
    return h('div', {}, head,
      h('div', { class: 'notice' },
        h('p', {}, '입력 기록으로 계좌를 되살리는 중입니다. 끝나면 게이트웨이에 연결해 이어서 받습니다.'),
        h('p', { class: 'small' }, '기록일 하나씩 낮은 우선순위로 진행하고, 그 사이에도 감시 정지 요청과 디스크 여유를 봅니다.')),
      accountsTable(view, null));
  }
  const warn = [];
  if (s.stopReason) warn.push(s.stopReason);
  if (view.feedState === 'DISCONNECTED' || view.feedState === 'CONNECTING') {
    warn.push(`게이트웨이 시세 전달에 연결돼 있지 않습니다${view.feedError ? `(${view.feedError})` : ''}. 그동안 빠진 구간은 공백으로 남고 계좌에는 가지 않습니다. 게이트웨이(autotrade-app-gateway)가 같은 설정 폴더로 떠 있는지 확인하세요.`);
  }
  if (view.inboxDropped > 0) {
    warn.push(`실험실 받는 큐가 차서 이벤트 ${number(view.inboxDropped)}건을 버렸습니다(그 구간은 공백으로 남음). 실험실이 실전 엔진·녹화보다 먼저 양보한 결과입니다.`);
  }
  const failed = s.failures.filter(f => f).length;
  // 시각은 세션 시장의 현지 시간으로(서버가 시장을 준다, PR #96 R4). 모르면 국내로
  const market = view.market || 'KRX';
  return h('div', {}, head,
    warn.length ? h('div', { class: 'notice warn' }, warn.map(w => h('p', {}, w))) : null,
    h('div', { class: 'tiles' },
      tile('계좌', `${s.runs.length - failed}/${s.runs.length} 정상`, failed ? `실패 ${failed}` : '실패 없음'),
      tile('받은 이벤트', number(s.events), `되살린 ${number(s.restored)}`),
      tile('공백', number(s.dataGaps), '빠진 구간(아래 표)'),
      tile('마지막 이벤트', s.lastEventAt ? time(s.lastEventAt, market) : '–', `기록일 ${s.journalDate || '–'}`)),
    h('h2', {}, '계좌'),
    h('p', { class: 'muted small' }, '계좌 ID를 누르면 그 계좌의 체결 내역과 거절 사유를 봅니다.'),
    accountsTable(view, s),
    selectionsSection(view, s, act),
    h('h2', {}, '최근 공백'),
    gapsSection(gaps, market),
    h('h2', {}, '세션 정보'),
    h('dl', { class: 'facts' },
      fact('기간', `${s.from} ~ ${s.to}`),
      fact('시세 전달', `${(FEED[view.feedState] || { label: view.feedState }).label}${view.feedReconnects ? ` · 재연결 ${number(view.feedReconnects)}회` : ''}`),
      fact('받는 큐', `${number(view.inboxQueued)}건 대기 · 버림 ${number(view.inboxDropped)}건`),
      fact('boards 밖 종목으로 뺀 이벤트', number(s.skippedSymbol)),
      fact('기간 밖으로 뺀 이벤트', number(s.skippedRange)),
      fact('계좌 순서', view.profiles.join(', '))));
}

/** 선정 상태 문구(D-124). 서버 사유 이름은 그대로 보인다. */
const EXCLUSION = { NOT_SUBSCRIBED: 'boards 밖', BLACKLISTED: '블랙리스트' };

/** 다음 거래일 파일 상태(D-124·D-126, 서버 SessionSelections.Prepared.State). */
const PREPARED = {
  NONE: '아직 준비 안 함',
  READY: '결과 파일 있음',
  PROBLEM: '못 만듦',
  APPLIED: '적용함',
  APPLIED_MISSING: '적용함 · SELECTION_MISSING',
  APPLIED_UNREADABLE: '적용 기록을 읽을 수 없음',
};

/** 종목 코드 목록을 짧게(앞 몇 개만, 나머지는 개수). */
function codes(list, max = 10) {
  if (!list || !list.length) return '–';
  return list.length > max ? `${list.slice(0, max).join(', ')} 외 ${number(list.length - max)}개` : list.join(', ');
}

/**
 * 마지막 준비가 쓴 입력 스냅샷(서버 LiveSelectionPrep.Report). 공시는 공시 조건 장전 선정이 있을 때만 있다(ADR-014 F9, PR #206 N3).
 * 등록 파일이 latest면 준비할 때 고른 실제 스냅샷 ID가 보인다.
 */
function prepInputs(prep) {
  return [['일봉', prep.dailySnapshot], ['재무', prep.financialsSnapshot], ['공시', prep.disclosuresSnapshot]]
    .filter(([, id]) => id).map(([label, id]) => ` · ${label} ${id}`).join('');
}

/** 사유와 자세한 내용을 한 칸에. 경고 색. */
function reasonCell(label, detail) {
  return h('span', { class: 'warn-text' }, detail ? `${label} · ${detail}` : label);
}

function selectionsSection(view, s, act) {
  const hasSelections = view.profiles.some(p => p.includes(' x '));
  if (!hasSelections) return null;
  const intraday = s.intraday || [];
  return h('div', {},
    h('h2', {}, '선정 조합'),
    h('p', { class: 'muted small' }, '선정 계좌는 그날 후보이면서 boards에 있는 종목만 새로 삽니다. 뺀 자리를 다음 순위로 메우지 않습니다. 결과가 없는 날(SELECTION_MISSING)이나 순위 시각 전에는 새로 사지 않고 청산만 합니다.'),
    view.dailySelections ? dailySelections(view, s, act) : null,
    intraday.length ? intradaySelections(intraday, s) : null);
}

/** 장전 선정(PRE_OPEN_DAILY, D-124): 그날 적용 기록과 다음 거래일 준비. */
function dailySelections(view, s, act) {
  const applied = s.selections;
  const prep = view.prepare;
  const next = view.nextDay;
  const prepareBtn = h('button', {
    class: 'btn secondary', type: 'button',
    title: '장 밖 시간이 아니어도 다음 거래일 선정 결과를 지금 한 번 만듭니다(실험실 준비 스레드, 낮은 우선순위)',
    onclick: () => act('선정 준비', async () => {
      await post('/api/live/selections/prepare');
      return '선정 준비를 요청했습니다. 끝나면 아래 "다음 거래일 준비"가 바뀝니다.';
    }),
  }, '지금 준비');
  const today = applied
    ? table([
      { label: '선정', render: it => it.selection },
      { label: '상태', render: it => it.reason ? reasonCell(`SELECTION_MISSING · ${it.reason}`, it.detail) : 'APPLIED' },
      { label: '후보', render: it => it.candidates
        ? `${number(it.candidates.candidates.length)}종목 (boards 안 ${number(it.candidates.onBoards)}/${number(it.candidates.selectedCount)})` : '–' },
      { label: '후보 종목', render: it => it.candidates ? codes(it.candidates.candidates) : '–' },
      { label: '뺀 종목', render: it => it.candidates && it.candidates.excluded.length
        ? it.candidates.excluded.map(x => `${x.code} ${x.reason}(${EXCLUSION[x.reason] || x.reason})`).join(', ') : '–' },
    ], applied.items)
    : h('p', { class: 'muted small' }, '아직 이번 세션에서 적용한 거래일이 없습니다(그날 첫 이벤트를 받을 때 적용합니다).');
  // 다음 거래일: 세션 폴더에 놓인 파일 상태(실험실을 다시 띄워도 보임). 마지막 준비 요약은 이번 실행에서 준비했을 때만 있다
  const repeated = next && next.items.some(p => p.state === 'PROBLEM' && p.inputs);
  const nextView = next
    ? h('div', {},
      prep && prep.date === next.date
        ? h('p', { class: 'small' }, `마지막 준비: ${prep.note}${prepInputs(prep)}`)
        : h('p', { class: 'muted small' }, '이번 실험실 실행에서는 아직 준비하지 않았습니다. 아래는 세션 폴더에 남은 파일입니다. 장 밖 시간(16:00 ~ 다음 날 08:30)에 5분마다 준비하고, "지금 준비"로 바로 할 수 있습니다.'),
      table([
        { label: '선정', render: p => p.selection },
        { label: '상태', render: p => (p.state === 'PROBLEM' || p.state === 'APPLIED_MISSING' || p.state === 'APPLIED_UNREADABLE')
          ? reasonCell(`${PREPARED[p.state]}${p.reason ? ` · ${p.reason}` : ''}`, p.detail) : (PREPARED[p.state] || p.state) },
        { label: '계산에 쓴 입력', render: p => p.inputs || '–' },
      ], next.items),
      repeated ? h('p', { class: 'muted small' }, '"계산에 쓴 입력"이 있는 사유는 같은 스냅샷으로 다시 계산하지 않습니다. 새 스냅샷이 수집되면 다시 계산합니다. 코드를 고친 뒤 같은 스냅샷으로 다시 만들려면 세션 폴더 selections/<날짜>/의 .problem.json을 지우세요.') : null)
    : prep
      ? h('p', { class: 'small' }, `${prep.date}: ${prep.note}`)
      : h('p', { class: 'muted small' }, '다음 거래일이 운용 기간 밖이거나 아직 세션에 연결하지 않았습니다.');
  return h('div', {},
    h('h3', {}, `장전 선정 · 적용${applied ? ` ${applied.date}` : ''}`),
    today,
    h('div', { class: 'row-actions' }, h('h3', {}, `다음 거래일 준비${next ? ` · ${next.date}` : ''}`), prepareBtn),
    nextView);
}

/** 장중 순위 선정(INTRADAY_AT, D-127): 오늘 순위 시각 전·후. 순위 대상은 boards뿐이라 boards 밖(NOT_SUBSCRIBED)은 없다. */
function intradaySelections(items, s) {
  return h('div', {},
    h('h3', {}, `장중 순위 선정${s.journalDate ? ` · ${s.journalDate}` : ''}`),
    h('p', { class: 'muted small' }, '정규장 시작부터 순위 시각 전까지 받은 boards 체결로 순위를 매기고, 순위를 매긴 이벤트부터 후보만 새로 삽니다. 그 구간에 시세가 빠진 종목(SESSION_GAP)은 순위에서 뺍니다. 장중에 실험실을 처음 띄웠거나 다시 띄운 날은 모든 종목이 SESSION_GAP이라 그날은 새로 사지 않습니다.'),
    table([
      { label: '선정', render: it => it.selection },
      { label: '순위 시각', render: it => it.at },
      { label: '상태', render: it => it.state === 'RANKED'
        ? 'RANKED'
        : h('span', { class: 'muted' }, 'AWAITING · 순위 시각 전(신규 진입 없음)') },
      { label: '후보', render: it => it.state === 'RANKED' ? `${number(it.candidates.length)}종목 (선정 ${number(it.selectedCount)})` : '–' },
      { label: '후보 종목', render: it => codes(it.candidates) },
      { label: '뺀 종목', render: it => it.blacklisted.length
        ? it.blacklisted.map(c => `${c} BLACKLISTED(블랙리스트)`).join(', ') : '–' },
      { label: '시세 누락', render: it => it.state !== 'RANKED' ? '–'
        : it.sessionGap ? reasonCell(`SESSION_GAP ${number(it.sessionGap)}/${number(it.universe)}`,
          it.sessionGap === it.universe ? '모든 종목, 그날 신규 진입 없음' : '') : `0/${number(it.universe)}` },
      { label: '적용 시작', render: it => it.state === 'RANKED' ? `이벤트 ${number(it.activeFrom)}번부터` : '–' },
    ], items));
}

function statusBadge(view) {
  if (view.restoring) return h('span', { class: 'badge badge-activating' }, '되살리는 중');
  const f = FEED[view.feedState] || { label: view.feedState, tone: '' };
  return h('span', { class: `badge${f.tone ? ` ${f.tone}` : ''}` }, `시세 ${f.label}`);
}

function accountsTable(view, s) {
  const rows = view.profiles.map((profile, i) => ({
    profile, run: s ? s.runs[i] : null, failure: s ? s.failures[i] : '',
  }));
  const cur = r => r.run ? r.run.equity.currency : 'KRW';
  return table([
    { label: '계좌', render: r => r.run
      ? h('a', { href: `#/live/runs/${enc(r.run.runId)}`, title: '체결 내역과 거절 사유 보기' }, r.run.runId) : '–' },
    { label: '전략 프로필', render: r => r.profile },
    { label: '평가금액', num: true, render: r => r.run ? money(r.run.equity.amount, cur(r)) : '–' },
    { label: '현금', num: true, render: r => r.run ? money(r.run.cash.amount, cur(r)) : '–' },
    { label: '보유 종목', num: true, render: r => r.run ? number(r.run.positions) : '–' },
    { label: '체결', num: true, render: r => r.run ? number(r.run.fills) : '–' },
    { label: '거절', num: true, render: r => r.run ? number(r.run.rejections) : '–' },
    { label: '거래일', render: r => r.run && r.run.tradingDay ? r.run.tradingDay : '–' },
    { label: '상태', render: r => r.failure ? h('span', { class: 'warn-text', title: r.failure }, `실패: ${r.failure}`) : (r.run ? '정상' : '대기') },
  ], rows, { rowClass: r => r.failure ? 'urgent' : null });
}

// ---- 계좌 하나: 체결 내역과 거절 사유(D-120) ----

/**
 * 돌고 있는 세션의 계좌 하나. 체결·거절은 최근 것부터이고, 되살린 기록으로 다시 만든 것도 들어 있다.
 * 자동으로 다시 읽지 않는다(쪽을 넘기는 중에 표가 바뀌지 않게). "새로 읽기"로 다시 읽는다.
 */
export async function liveRunPage(runId) {
  stopPolling();
  const body = h('div', {});
  const load = async () => {
    const view = await get('/api/live/status');
    const s = view.session;
    const i = s ? s.runs.findIndex(r => r.runId === runId) : -1;
    if (i < 0) {
      body.replaceChildren(h('div', { class: 'notice warn' },
        h('p', {}, view.experimentId
          ? (view.restoring ? '입력 기록으로 계좌를 되살리는 중입니다. 끝나면 다시 읽어 주세요.' : `돌고 있는 세션(${view.experimentId})에 이 계좌가 없습니다.`)
          : '돌고 있는 실시간 모의 세션이 없습니다.'),
        h('p', { class: 'small' }, '끝낸 세션의 체결은 실험 폴더의 live-detail.txt에 계좌별 fill 줄로 남아 있습니다.')));
      return;
    }
    const run = s.runs[i];
    const failure = s.failures[i];
    const market = view.market || 'KRX';
    const cur = run.equity.currency;
    const base = `/api/live/runs/${enc(runId)}`;
    body.replaceChildren(...[
      failure ? h('div', { class: 'notice warn' }, h('p', {}, `이 계좌는 실패로 멈췄습니다: ${failure}`),
        h('p', { class: 'small' }, '멈추기 전까지의 체결과 거절만 있습니다.')) : null,
      h('div', { class: 'tiles' },
        tile('평가금액', money(run.equity.amount, cur), '마지막 체결가 기준'),
        tile('현금', money(run.cash.amount, cur), `보유 종목 ${number(run.positions)}개`),
        tile('체결', `${number(run.fills)}건`, `거절 ${number(run.rejections)}건`),
        tile('거래일', run.tradingDay || '–', '마지막으로 처리한 이벤트 기준')),
      h('h2', {}, '체결 내역'),
      h('p', { class: 'muted small' }, '최근 것부터입니다. 체결가에는 슬리피지가 들어 있고, 실현손익은 수수료·세금을 뺀 값입니다. 실험실을 다시 띄워 되살린 계좌도 같은 체결이 보입니다.'),
      pagedSection(`${base}/fills`, fillColumns(market), '아직 체결이 없습니다.'),
      h('h2', {}, '거절 사유'),
      rejectionSection(base)].filter(Boolean));
  };
  await load().catch(e => body.replaceChildren(h('p', { class: 'notice warn' }, e.message)));
  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/live' }, '실시간 모의'), ' / ', runId),
    h('div', { class: 'page-head' },
      h('div', {},
        h('h1', {}, runId),
        h('p', { class: 'muted' }, '모의 계좌 하나의 체결 내역과 거절 사유입니다. 실전 주문과 무관합니다.')),
      h('div', { class: 'actions' },
        h('button', { class: 'btn secondary', type: 'button', onclick: () => load().catch(e => toast(e.message, true)) }, '새로 읽기'))),
    body);
}

function gapsSection(gaps, market) {
  if (!gaps) return h('p', { class: 'muted' }, '공백 정보를 아직 읽지 않았습니다.');
  if (gaps.error) return h('p', { class: 'notice warn' }, `공백을 읽지 못했습니다: ${gaps.error}`);
  if (gaps.total === 0) return h('p', { class: 'muted' }, '공백이 없습니다. 받은 시세가 빠짐없이 계좌에 갔습니다.');
  return h('div', {},
    h('p', { class: 'muted small' }, gaps.total > gaps.items.length
      ? `전체 ${number(gaps.total)}건 중 최근 ${gaps.items.length}건입니다(최근 것부터). 되살린 기록의 공백도 들어 있습니다.`
      : `${number(gaps.total)}건, 최근 것부터. 되살린 기록의 공백도 들어 있습니다.`),
    table([
      { label: '종목', render: g => g.symbol },
      { label: '사유', render: g => GAP_REASON[g.reason] || g.reason },
      { label: '빠진 구간', render: g => `${time(g.gapStart, market)} ~ ${time(g.gapEnd, market)}` },
      { label: '받은 시각', render: g => time(g.receivedAt, market) },
    ], gaps.items));
}

function tile(label, value, sub) {
  return h('div', { class: 'tile' },
    h('div', { class: 'tile-label' }, label), h('div', { class: 'tile-value' }, value), h('div', { class: 'tile-sub' }, sub));
}

function fact(label, value) {
  return h('div', {}, h('dt', {}, label), h('dd', {}, value || '–'));
}

// 끝내기는 되돌릴 수 없다(같은 세션을 이어 쓰지 않음). 실험 ID를 다시 입력해야 버튼이 열린다.
function finishDialog(view, act) {
  const input = h('input', { type: 'text', autocomplete: 'off', spellcheck: 'false', 'aria-label': '실험 ID 확인' });
  const ok = h('button', { class: 'btn', type: 'button', disabled: true }, '끝내기');
  const cancel = h('button', { class: 'btn secondary', type: 'button' }, '취소');
  const dlg = h('dialog', { class: 'confirm lab', 'aria-labelledby': 'dlg-title' },
    h('h2', { id: 'dlg-title' }, `${view.experimentId} 끝내기`),
    h('p', {}, '마지막 거래일을 마감하고 상세 보고서(live-detail.txt)를 실험 폴더에 씁니다. 끝낸 세션은 같은 실험 ID로 이어 받지 않습니다. 잠시 멈추려면 "멈춤"을 쓰세요.'),
    h('p', { class: 'small' }, '끝낸 뒤에는 리플레이 확인(runLiveReplayCheck)으로 입력 기록을 다시 돌려 결과가 같은지 봅니다.'),
    h('label', {}, '확인을 위해 실험 ID를 입력하세요: ', h('span', { class: 'phrase' }, view.experimentId)),
    input,
    h('div', { class: 'actions' }, cancel, ok));
  input.addEventListener('input', () => { ok.disabled = input.value.trim() !== view.experimentId; });
  cancel.addEventListener('click', () => dlg.close());
  ok.addEventListener('click', () => {
    dlg.close();
    act('끝내기', async () => {
      const done = await post('/api/live/finish');
      return `${done.experimentId}: 끝냈습니다. 계좌 ${done.accounts}개${done.failedAccounts ? `(실패 ${done.failedAccounts})` : ''}, 보고서 ${done.detailFile}`;
    });
  });
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  input.focus();
}
