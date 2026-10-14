// 실시간 모의 화면(ADR-010 2.4, D-076): 실시간 모의 등록 파일 고르기, 시작·멈춤·끝내기, 계좌별 상태와 최근 공백(DataGap).
// 모의 계좌만 다룬다. 실전 주문 경로와 무관하고 증권사를 직접 부르지 않는다(시세는 게이트웨이 전달 소켓으로만 받음).
// 실시간 갱신은 3초 폴링이다(SSE는 UI-01 이연 항목, D-079). 화면을 떠나면 폴링을 멈춘다.
import { h, toast, table } from '../dom.js';
import { get, post } from '../api.js';
import { money, number, time } from '../format.js';

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
  const refresh = async () => {
    const view = await get('/api/live/status');
    const active = view.experimentId !== '';
    const [gaps, regs] = await Promise.all([
      active && !view.restoring ? get(`/api/live/gaps?limit=${GAP_LIMIT}`).catch(e => ({ error: e.message })) : null,
      active ? null : get('/api/live/registrations').catch(e => ({ error: e.message })),
    ]);
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
    if (!busy) refresh().catch(() => { /* 다음 주기에 다시 읽는다. 실패를 매번 띄우지 않는다 */ });
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
  const last = view.stopReason
    ? h('div', { class: `notice${view.stopReason === '끝냄' || view.stopReason === '화면에서 멈춤' ? '' : ' warn'}` },
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
  return h('div', {}, head,
    warn.length ? h('div', { class: 'notice warn' }, warn.map(w => h('p', {}, w))) : null,
    h('div', { class: 'tiles' },
      tile('계좌', `${s.runs.length - failed}/${s.runs.length} 정상`, failed ? `실패 ${failed}` : '실패 없음'),
      tile('받은 이벤트', number(s.events), `되살린 ${number(s.restored)}`),
      tile('공백', number(s.dataGaps), '빠진 구간(아래 표)'),
      tile('마지막 이벤트', s.lastEventAt ? time(s.lastEventAt, 'KRX') : '–', `기록일 ${s.journalDate || '–'}`)),
    h('h2', {}, '계좌'),
    accountsTable(view, s),
    h('h2', {}, '최근 공백'),
    gapsSection(gaps),
    h('h2', {}, '세션 정보'),
    h('dl', { class: 'facts' },
      fact('기간', `${s.from} ~ ${s.to}`),
      fact('시세 전달', `${(FEED[view.feedState] || { label: view.feedState }).label}${view.feedReconnects ? ` · 재연결 ${number(view.feedReconnects)}회` : ''}`),
      fact('받는 큐', `${number(view.inboxQueued)}건 대기 · 버림 ${number(view.inboxDropped)}건`),
      fact('boards 밖 종목으로 뺀 이벤트', number(s.skippedSymbol)),
      fact('기간 밖으로 뺀 이벤트', number(s.skippedRange)),
      fact('계좌 순서', view.profiles.join(', '))));
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
    { label: '계좌', render: r => r.run ? r.run.runId : '–' },
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

function gapsSection(gaps) {
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
      { label: '빠진 구간', render: g => `${time(g.gapStart, 'KRX')} ~ ${time(g.gapEnd, 'KRX')}` },
      { label: '받은 시각', render: g => time(g.receivedAt, 'KRX') },
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
