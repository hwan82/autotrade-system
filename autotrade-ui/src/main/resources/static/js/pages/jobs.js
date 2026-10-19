// 실험 진행 상태(UI-02, D-089·D-093): 실행 명령(터미널)이 쓰는 상태 파일을 실험실이 읽어 보여 준다. 새 상태는 SSE
// (/api/experiment-jobs/stream, 이벤트 jobs)로 받고, 연결이 안 되면 5초마다 조회한다(UI-01). 화면을 떠나면 연결과 조회를 멈춘다.
// 이 화면이 하는 일은 취소 요청 파일을 만드는 것뿐이다. 실행 명령이 다음 Run 시작 전이나 거래일 마감에 보고 결과 없이 끝난다.
import { h, table, toast } from '../dom.js';
import { get, post } from '../api.js';
import { number, time } from '../format.js';

const enc = encodeURIComponent;
const POLL_MS = 5000;

const COMMAND = {
  combination: '조합 실험', recording: '녹화 실험', study: '합성 시세 비교', walkforward: '워크포워드',
  'live-check': '실시간 모의 점검', 'live-weekly': '주간 점검', 'live-judge': '녹화 판정',
};

// 상태 이름과 배지. NEEDS_REVIEW는 결과는 썼지만 결정론 재실행 등 확인할 것이 남은 끝
export const JOB_STATE = {
  RUNNING: { label: '실행 중', cls: 'badge ok' },
  UNRESPONSIVE: { label: '응답 없음', cls: 'badge warn-state' },
  COMPLETED: { label: '완료', cls: 'badge' },
  NEEDS_REVIEW: { label: '확인 필요', cls: 'badge warn-state' },
  INPUT_ERROR: { label: '입력 오류', cls: 'badge critical' },
  CANCELLED: { label: '취소됨', cls: 'badge' },
  STOPPED: { label: '정지 요청으로 멈춤', cls: 'badge warn-state' },
  FAILED: { label: '실패', cls: 'badge critical' },
};

const PHASE = { STARTED: '시작', RUNNING: '진행', FINISHED: '마무리' };

let es = null;
let timer = null;

function stopUpdates() {
  if (es) { es.close(); es = null; }
  if (timer) { clearInterval(timer); timer = null; }
}

// 다른 화면으로 가면 바로 끊는다(바뀐 것이 없으면 이벤트가 오지 않아 화면이 떠난 것을 스스로 알 수 없다)
window.addEventListener('hashchange', () => {
  if (location.hash !== '#/experiments') stopUpdates();
});

function bar(permille, label) {
  const fill = h('span', {});
  fill.style.width = `${Math.min(100, Math.max(0, permille / 10))}%`; // CSP 'self': style 속성 대신 CSSOM
  return h('div', { class: 'job-bar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': 1000, 'aria-valuenow': permille,
    'aria-label': label }, fill);
}

const pct = permille => `${(permille / 10).toFixed(1)}%`;

/**
 * 진행 막대와 숫자. 첫 실험 시작 전(진행 필드 null)이면 문구만. 명령이 전체 실험 수를 미리 알렸으면(워크포워드, D-098)
 * 막대는 명령 전체 진행률이고 지금 실험의 진행은 아래 줄에 붙인다.
 */
function progress(j) {
  const whole = j.commandPermille !== null && j.commandPermille !== undefined;
  if (j.permille === null || j.permille === undefined) {
    if (!whole) return h('span', { class: 'muted small' }, '첫 실험 시작 전');
    return h('div', { class: 'job-progress' }, bar(j.commandPermille, `${j.job} 전체 진행률 ${pct(j.commandPermille)}`),
      h('span', { class: 'small' }, `전체 ${pct(j.commandPermille)} · 실험 0/${number(j.experimentsPlanned)}`),
      h('span', { class: 'sub muted small' }, '첫 실험 시작 전'));
  }
  const runs = `Run ${number(j.runsFinished)}/${number(j.runsTotal)} 끝`
    + (j.runsFailed ? ` (실패 ${number(j.runsFailed)})` : '')
    + (j.state === 'RUNNING' && j.runsStarted > j.runsFinished ? `, ${number(j.runsStarted - j.runsFinished)}개 진행 중` : '');
  const days = j.daysTotal ? `거래일 ${number(j.daysDone)}/${number(j.daysTotal)}` : null;
  const current = `${pct(j.permille)} · ${runs}${days ? ` · ${days}` : ''}`;
  const phase = PHASE[j.phase] || j.phase;
  if (whole) {
    return h('div', { class: 'job-progress' },
      bar(j.commandPermille, `${j.job} 전체 진행률 ${pct(j.commandPermille)}`),
      h('span', { class: 'small' }, `전체 ${pct(j.commandPermille)} · 실험 ${number(j.experimentsStarted)}/${number(j.experimentsPlanned)}번째`),
      h('span', { class: 'sub muted small' }, `지금 실험 ${j.experiment} (${phase}) ${current}`));
  }
  return h('div', { class: 'job-progress' },
    bar(j.permille, `${j.job} 진행률 ${pct(j.permille)}`),
    h('span', { class: 'small' }, current),
    j.experiment && j.experiment !== j.job
      ? h('span', { class: 'sub muted small' }, `지금 실험 ${j.experiment} (${phase}, ${number(j.experimentsStarted)}번째)`)
      : null);
}

function stateCell(j) {
  const s = JOB_STATE[j.state] || { label: j.state, cls: 'badge' };
  return h('span', {},
    h('span', { class: s.cls }, s.label),
    j.cancelRequested && j.state === 'RUNNING' ? h('span', { class: 'sub warn-text' }, ' 취소 요청됨(다음 확인 지점에서 끝남)') : null,
    j.state === 'UNRESPONSIVE'
      ? h('span', { class: 'sub warn-text' }, ' 60초 넘게 갱신 없음. 터미널이 닫혔거나 강제 종료됐을 수 있습니다') : null);
}

/**
 * 실행 중인 실험 목록 영역. 실험 목록 화면 위에 붙는다. 처음 응답을 기다리지 않고 바로 돌려주며, 첫 내용은 SSE 첫 이벤트나
 * 첫 조회로 채운다.
 */
export function jobsSection() {
  stopUpdates();
  const host = h('div', { 'aria-live': 'off' }, h('p', { class: 'muted' }, '진행 상태를 읽는 중…'));
  const link = h('span', { class: 'badge' }, '연결 중');
  const message = h('div', {});
  const alive = () => document.body.contains(host);
  let showDone = false;
  let last = null;

  const setLink = (mode) => {
    const m = { sse: ['실시간 연결', 'badge ok'], poll: ['주기 조회(5초)', 'badge warn-state'], wait: ['연결 중', 'badge'] }[mode];
    link.textContent = m[0];
    link.className = m[1];
  };

  const cancel = async (j, button) => {
    if (!window.confirm(`${j.job} 실행을 취소합니다. 실행 명령이 다음 Run 시작 전이나 거래일 마감에 결과 없이 끝납니다(종료 코드 3). 취소할까요?`)) return;
    button.disabled = true;
    try {
      const res = await post(`/api/experiment-jobs/${enc(j.job)}/cancel`);
      if (last) {
        last.items = last.items.map(x => (x.job === res.job ? res : x));
        draw();
      }
      toast(`${j.job} 취소를 요청했습니다.`);
    } catch (err) {
      button.disabled = false;
      message.replaceChildren(h('p', { class: 'notice warn' }, `${j.job}: ${err.message}`));
    }
  };

  const draw = () => {
    if (!last) return;
    const active = last.items.filter(j => j.state === 'RUNNING' || j.state === 'UNRESPONSIVE');
    const done = last.items.filter(j => !active.includes(j));
    const rows = showDone ? last.items : active;
    const notes = [];
    if (last.unreadable) notes.push(h('p', { class: 'notice warn' }, `읽지 못한 상태 파일 ${number(last.unreadable)}개는 빼고 보여 줍니다(쓰는 중이었으면 다음 갱신에 나옵니다).`));
    if (last.truncated) notes.push(h('p', { class: 'muted small' }, '상태 파일이 500개를 넘어 오래된 작업은 읽지 않았습니다.'));
    if (last.total > last.items.length) notes.push(h('p', { class: 'muted small' }, `최근 ${number(last.items.length)}개만 보입니다(전체 ${number(last.total)}개).`));
    const toggle = done.length > 0
      ? h('button', { class: 'btn secondary', type: 'button', onclick: () => { showDone = !showDone; draw(); } },
        showDone ? '끝난 작업 숨기기' : `끝난 작업 ${number(done.length)}개 보기`)
      : null;
    const body = rows.length === 0
      ? h('p', { class: 'muted' }, active.length === 0 && last.items.length === 0
        ? '아직 실행 기록이 없습니다. 실험 실행 명령을 터미널에서 돌리면 여기에 진행 상태가 나옵니다.'
        : '실행 중인 실험이 없습니다.')
      : table([
        { label: '작업', render: j => h('span', {}, j.job, h('span', { class: 'sub muted small' }, COMMAND[j.command] || j.command)) },
        { label: '상태', render: stateCell },
        { label: '진행', render: progress },
        { label: '병렬', num: true, render: j => number(j.maxParallelRuns) },
        { label: '시작·갱신', render: j => h('span', { class: 'small' }, time(j.startedAt),
          h('span', { class: 'sub muted' }, `갱신 ${time(j.updatedAt)}`)) },
        { label: '메모', render: j => j.message ? h('span', { class: 'small' }, j.message) : '' },
        { label: '', render: j => j.state === 'RUNNING' && !j.cancelRequested
          ? h('button', { class: 'btn secondary', type: 'button', onclick: (ev) => cancel(j, ev.currentTarget) }, '취소')
          : null },
      ], rows, { rowClass: j => (j.state === 'UNRESPONSIVE' ? 'row-warn' : null) });
    host.replaceChildren(...notes, body, toggle ? h('p', {}, toggle) : '');
  };

  const apply = (data) => {
    last = data;
    draw();
  };

  const startPolling = () => {
    setLink('poll');
    if (timer) return;
    const once = async () => {
      if (!alive()) { stopUpdates(); return; }
      try { apply(await get('/api/experiment-jobs?page=0&size=50')); } catch (err) {
        if (!last) host.replaceChildren(h('p', { class: 'notice warn' }, `진행 상태를 읽지 못했습니다: ${err.message}`));
      }
    };
    once();
    timer = setInterval(once, POLL_MS);
  };

  if (typeof EventSource === 'function') {
    es = new EventSource('/api/experiment-jobs/stream');
    es.addEventListener('open', () => { if (alive()) setLink('sse'); });
    es.addEventListener('jobs', (ev) => {
      if (!alive()) { stopUpdates(); return; }
      setLink('sse');
      apply(JSON.parse(ev.data));
    });
    es.addEventListener('error', () => {
      // 서버가 거절(409)하거나 닫으면 EventSource가 닫힌다. 그때는 주기 조회로 바꾼다
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

  return h('section', { class: 'jobs' },
    h('div', { class: 'section-head' }, h('h2', {}, '실행 중인 실험'), link),
    h('p', { class: 'muted small' }, '터미널에서 돌린 실험 실행 명령의 진행 상태입니다. 실험실은 상태 파일을 읽기만 하고, 취소는 취소 요청 파일을 만드는 것뿐입니다. 끝난 실험의 결과는 아래 실험 폴더에서 불러옵니다.'),
    message, host);
}
