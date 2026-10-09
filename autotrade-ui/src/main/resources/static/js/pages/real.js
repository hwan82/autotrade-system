// 실전 운영 화면(UI-05, ADR-006): 실전 계좌 Guard 상태, 실전 활성화 2차 확인, 내리기, PANIC SELL.
// 이 프로세스(실험실)에는 실전 주문 경로가 없다. 버튼은 실전 엔진 명령 채널로 명령만 보내고,
// 비밀번호·확인 문구 검증과 Guard 전이는 엔진이 한다. 비밀번호는 보낸 뒤 입력란에서 지우고 어디에도 저장하지 않는다.
import { h, toast, table } from '../dom.js';
import { get, post } from '../api.js';

const BROKER = { KIS: '한국투자', KIWOOM: '키움', TOSS: '토스' };
const MARKET = { KRX: '국내', US: '미국' };
export const STATE = {
  OFF: { label: '꺼짐', note: '실전 주문 차단' },
  ACTIVATING: { label: '여는 중', note: '인증·세션·첫 대사 진행' },
  OPEN: { label: '실전 매매 중', note: '신규 진입 허용' },
  PROTECTIVE: { label: '보호 모드', note: '청산·손절만' },
};
const STEP = {
  AUTHENTICATED: '인증', ORDER_SESSION: '주문 세션', FIRST_RECONCILIATION: '첫 대사',
  PROTECTIVE_LOCK: '보호 모드 고정', CANCEL_OPEN_ORDERS: '미체결 취소', CANCEL_SERVER_STOPS: '서버 측 손절 취소',
  SELL: '매도', CONFIRM_FILLS: '체결 확인', GUARD_OFF: 'Guard 끄기',
};
const OUTCOME = { DONE: '완료', SKIPPED: '건너뜀', NOT_AVAILABLE: '수행 불가', FAILED: '실패' };
const PANIC_ALL_PHRASE = '전체 청산';
// 엔진이 답하지 않을 때 보여 줄 마지막 확인 상태
let lastUp = null;

export async function realPage() {
  const host = h('div', {});
  const result = h('div', { id: 'real-result', 'aria-live': 'polite' });
  const draw = (view) => {
    host.replaceChildren(body(view, refresh, result));
    document.dispatchEvent(new CustomEvent('real-status', { detail: view }));
  };
  const refresh = async () => draw(await get('/api/real/status'));
  await refresh();
  return h('div', {},
    h('div', { class: 'page-head' },
      h('div', {},
        h('h1', {}, '실전 운영'),
        h('p', { class: 'muted' }, '실전 엔진의 계좌별 RealTradingGuard 상태입니다. 이 화면의 명령은 실전 엔진이 검증한 뒤에만 반영됩니다.')),
      h('div', { class: 'actions' },
        h('button', { class: 'btn secondary', type: 'button', onclick: () => refresh().catch(e => toast(e.message, true)) }, '상태 새로 읽기'))),
    host, result);
}

function body(view, refresh, result) {
  const engine = view.engineState || (view.engineReachable ? 'UP' : 'UNKNOWN');
  if (engine === 'UP') lastUp = view;
  if (engine === 'NOT_RUNNING') {
    lastUp = null;
    return h('div', { class: 'empty' },
      h('h2', {}, '실전 엔진이 실행 중이 아닙니다'),
      h('p', {}, view.message),
      h('p', { class: 'muted small' }, '실전 엔진(autotrade-engine)을 같은 설정 폴더로 실행하면 여기서 상태를 볼 수 있습니다. 엔진을 다시 시작하면 모든 실전 계좌는 꺼진 상태로 시작합니다. 실험실이 꺼져 있거나 이 화면이 닫혀도 실전 엔진은 영향받지 않습니다.'));
  }
  const unknown = engine === 'UNKNOWN';
  if (unknown && !lastUp) {
    return h('div', { class: 'notice warn' },
      h('h2', {}, '실전 엔진이 응답하지 않습니다'),
      h('p', {}, `${view.message}. 엔진은 살아 있을 수 있고 실전 계좌가 열려 있을 수 있습니다. 실전 엔진 터미널을 확인하세요.`));
  }
  const st = unknown ? lastUp.status : view.status;
  const ready = !unknown && st.passwordConfigured && !st.lockedUntil;
  const open = st.accounts.filter(a => a.state !== 'OFF').length;
  const lockOnly = !(st.passwordConfigured && !st.lockedUntil);
  return h('div', {},
    unknown ? h('div', { class: 'notice warn' },
      h('p', {}, `실전 엔진이 응답하지 않습니다(${view.message}). 아래는 ${new Date(lastUp.asOf).toLocaleTimeString('en-GB')}에 마지막으로 확인한 상태입니다.`),
      h('p', { class: 'small' }, '지금 상태와 다를 수 있습니다. 활성화는 막아 두었고, PANIC SELL과 내리기는 보낼 수 있지만 반영 여부는 엔진이 다시 응답해야 알 수 있습니다.')) : null,
    !st.passwordConfigured ? h('div', { class: 'notice warn' },
      h('p', {}, '운영자 비밀번호가 설정되지 않아 실전 활성화와 PANIC SELL을 받을 수 없습니다.'),
      h('p', { class: 'small' }, '터미널에서 실전 엔진을 hash-password 인자로 실행해 해시를 만들고, secrets.json의 real-trading.operator-password-hash에 넣은 뒤 엔진을 다시 시작하세요. 비밀번호 자체는 어디에도 저장되지 않습니다.')) : null,
    st.lockedUntil ? h('div', { class: 'notice warn' },
      h('p', {}, `2차 확인을 여러 번 틀려 ${new Date(st.lockedUntil).toLocaleTimeString('ko-KR')}까지 잠겼습니다. 내리기는 그대로 할 수 있고, PANIC SELL은 보호 모드 고정(신규 주문 차단)까지만 됩니다.`)) : null,
    st.accounts.length === 0
      ? h('div', { class: 'empty' }, h('p', {}, '실전 계좌가 설정되지 않았습니다(runtime-config.json의 real-trading.accounts).'))
      : h('div', {},
        h('div', { class: 'panic-all' },
          h('p', {}, `열린 실전 계좌 ${open}개 / 전체 ${st.accounts.length}개`),
          h('button', {
            class: 'btn danger', type: 'button',
            onclick: () => panicDialog(null, refresh, result, lockOnly),
          }, '전체 PANIC SELL'),
          lockOnly ? h('p', { class: 'small warn-text' }, '비밀번호가 없거나 잠겨 있어 보호 모드 고정만 됩니다') : null),
        table([
          { label: '계좌', render: a => h('strong', {}, a.id) },
          { label: '증권사·시장', render: a => `${BROKER[a.broker] || a.broker} · ${MARKET[a.market] || a.market}` },
          { label: 'Guard', render: a => stateBadge(a) },
          { label: '계좌번호', render: a => a.accountNumberConfigured ? '설정됨' : h('span', { class: 'warn-text' }, '미설정') },
          { label: '명령', render: a => h('div', { class: 'row-actions' },
            h('button', {
              class: 'btn', type: 'button',
              disabled: !(ready && a.accountNumberConfigured && (a.state === 'OFF' || a.state === 'PROTECTIVE')),
              title: a.accountNumberConfigured ? null : '계좌번호가 없으면 활성화할 수 없습니다',
              onclick: () => activateDialog(a, refresh, result),
            }, '실전 활성화'),
            h('button', {
              class: 'btn secondary', type: 'button', disabled: !(a.state === 'OPEN' || a.state === 'ACTIVATING'),
              onclick: () => deactivateDialog(a, refresh, result),
            }, '내리기'),
            h('button', {
              class: 'btn danger', type: 'button',
              onclick: () => panicDialog(a, refresh, result, lockOnly),
            }, 'PANIC SELL')) },
        ], st.accounts, { rowClass: a => `state-${a.state.toLowerCase()}` })),
    h('h2', {}, '상태 설명'),
    h('dl', { class: 'facts' }, Object.entries(STATE).map(([k, v]) => h('div', {},
      h('dt', {}, `${v.label} (${k})`), h('dd', {}, v.note)))));
}

export function stateBadge(a) {
  const s = STATE[a.state] || { label: a.state, note: '' };
  const steps = a.state === 'ACTIVATING' && a.completedSteps.length
    ? ` · ${a.completedSteps.map(x => STEP[x] || x).join(', ')} 통과` : '';
  return h('span', { class: `badge badge-${a.state.toLowerCase()}`, title: s.note }, `${s.label}${steps}`);
}

// ---- 대화상자 ----

function dialog(title, tone, content, actions) {
  const dlg = h('dialog', { class: `confirm ${tone}`, 'aria-labelledby': 'dlg-title' },
    h('h2', { id: 'dlg-title' }, title), content, h('div', { class: 'actions' }, actions));
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  return dlg;
}

/**
 * 2차 확인 폼. 확인 문구를 정확히 입력하고 비밀번호를 넣어야 버튼이 켜진다.
 * 보낸 뒤에는 결과와 상관없이 비밀번호 입력란을 비운다.
 */
function secondConfirmation({ title, tone, intro, phrase, submitLabel, send, refresh, result, passwordOptional = false }) {
  const typed = h('input', { id: 'dlg-typed', autocomplete: 'off', spellcheck: 'false', autocapitalize: 'off' });
  const password = h('input', { id: 'dlg-password', type: 'password', autocomplete: 'off' });
  const submit = h('button', { class: `btn ${tone === 'danger' ? 'danger' : ''}`, type: 'button', disabled: true }, submitLabel);
  const cancel = h('button', { class: 'btn secondary', type: 'button' }, '취소');
  const update = () => {
    submit.disabled = !(typed.value.trim().normalize('NFC') === phrase && (passwordOptional || password.value.length > 0));
  };
  typed.addEventListener('input', update);
  password.addEventListener('input', update);
  const dlg = dialog(title, tone, h('div', {},
    intro,
    h('div', { class: 'form-grid narrow' },
      h('label', { for: 'dlg-typed' }, '확인 문구'),
      h('div', {}, typed, h('p', { class: 'hint' }, '아래 문구를 그대로 입력하세요: ', h('strong', { class: 'phrase' }, phrase))),
      h('label', { for: 'dlg-password' }, '운영자 비밀번호'),
      h('div', {}, password, h('p', { class: 'hint' }, passwordOptional
        ? '지금은 비밀번호 검증을 할 수 없어 보호 모드 고정까지만 됩니다. 비밀번호를 넣어도 청산은 하지 않습니다.'
        : '실전 엔진이 검증합니다. 5번 연속 틀리면 15분 동안 잠깁니다.')))),
  [cancel, submit]);
  cancel.addEventListener('click', () => dlg.close());
  submit.addEventListener('click', async () => {
    const pw = password.value;
    password.value = '';
    submit.disabled = true;
    try {
      const r = await send(typed.value, pw);
      dlg.close();
      showResult(result, r);
    } catch (e) {
      toast(e.message, true);
      if (e.code === 'ENGINE_UNREACHABLE' || e.code === 'LOCKED' || e.code === 'BUSY' || e.code === 'STALE') dlg.close();
    } finally {
      refresh().catch(() => {});
    }
  });
  typed.focus();
}

function activateDialog(a, refresh, result) {
  secondConfirmation({
    title: `${a.id} 실전 활성화`, tone: 'real', phrase: a.id, submitLabel: '실전 활성화',
    intro: h('div', {},
      h('p', {}, `${BROKER[a.broker] || a.broker} ${MARKET[a.market] || a.market} 실전 계좌에서 자동 매매를 엽니다. 인증, 주문 세션, 첫 대사를 차례로 통과해야 열리고, 하나라도 실패하면 원래 상태로 돌아갑니다.`),
      h('ul', { class: 'small' },
        h('li', {}, '열리면 이 계좌로 실제 주문이 나갑니다. 실전 엔진을 다시 시작하면 항상 꺼진 상태로 돌아갑니다.'),
        h('li', {}, 'PC나 인터넷 장애 때 보호 수단(서버 측 손절) 지원 여부는 증권사 연동 후 이 화면에 표시합니다.'),
        a.state === 'PROTECTIVE' ? h('li', {}, '보호 모드에서 다시 여는 중에도 손절·청산은 계속됩니다.') : null)),
    send: (typed, password) => post(`/api/real/accounts/${encodeURIComponent(a.id)}/activate`, { typedConfirmation: typed, password }),
    refresh, result,
  });
}

function panicDialog(a, refresh, result, lockOnly) {
  const all = a === null;
  secondConfirmation({
    title: all ? '전체 PANIC SELL' : `${a.id} PANIC SELL`, tone: 'danger',
    phrase: all ? PANIC_ALL_PHRASE : a.id, submitLabel: lockOnly ? '보호 모드 고정' : 'PANIC SELL 실행',
    passwordOptional: lockOnly,
    intro: h('div', {},
      lockOnly ? h('p', { class: 'notice warn' }, '운영자 비밀번호가 없거나 잠겨 있어 1단계(보호 모드 고정)까지만 합니다. 신규 주문은 바로 막히고, 손절·익절과 서버 측 손절은 계속됩니다.') : null,
      h('p', {}, all ? '모든 실전 계좌의 자동 매매 포지션을 정리합니다.' : `${a.id} 계좌의 자동 매매 포지션을 정리합니다.`),
      h('ol', { class: 'small' },
        h('li', {}, '보호 모드로 고정해 신규 주문을 바로 막습니다. 이 단계는 비밀번호 확인 전에 하므로 확인이 실패해도 일어납니다.'),
        h('li', {}, '미체결 주문과 서버 측 손절 주문을 모두 취소하고 확인합니다.'),
        h('li', {}, '매도합니다. 국내는 시장가, 미국은 지정가에 여유폭을 둡니다. 장 외 시간에는 매도하지 않습니다.'),
        h('li', {}, '체결을 확인한 뒤에만 Guard를 끕니다. 확인되지 않으면 보호 모드에 남아 손절이 계속됩니다.')),
      h('p', { class: 'small muted' }, '블랙리스트와 수동 보유 종목은 매도하지 않습니다.')),
    send: (typed, password) => all
      ? post('/api/real/panic', { typedConfirmation: typed, password })
      : post(`/api/real/accounts/${encodeURIComponent(a.id)}/panic`, { typedConfirmation: typed, password }),
    refresh, result,
  });
}

function deactivateDialog(a, refresh, result) {
  const reason = h('input', { id: 'dlg-reason', maxlength: '200', placeholder: '예: 장중 점검' });
  const submit = h('button', { class: 'btn', type: 'button' }, '내리기');
  const cancel = h('button', { class: 'btn secondary', type: 'button' }, '취소');
  const dlg = dialog(`${a.id} 내리기`, 'real', h('div', {},
    h('p', {}, a.state === 'OPEN'
      ? '보호 모드로 내립니다. 신규 진입을 멈추고, 보유분의 손절·익절과 서버 측 손절만 계속합니다. 보유가 0이 되면 꺼집니다.'
      : '활성화를 멈추고 원래 상태로 돌아갑니다.'),
    h('p', { class: 'small muted' }, '내리는 명령은 비밀번호를 묻지 않습니다. 다시 열 때는 2차 확인이 필요합니다.'),
    h('div', { class: 'form-grid narrow' },
      h('label', { for: 'dlg-reason' }, '사유(선택)'), h('div', {}, reason))),
  [cancel, submit]);
  cancel.addEventListener('click', () => dlg.close());
  submit.addEventListener('click', async () => {
    submit.disabled = true;
    try {
      showResult(result, await post(`/api/real/accounts/${encodeURIComponent(a.id)}/deactivate`, { reason: reason.value }));
      dlg.close();
    } catch (e) {
      toast(e.message, true);
      submit.disabled = false;
    } finally {
      refresh().catch(() => {});
    }
  });
}

function showResult(host, r) {
  if (r.ok === false) toast(r.message, true);
  host.replaceChildren(h('section', { class: `result${r.ok === false ? ' rejected' : ''}` },
    h('h2', {}, '마지막 명령 결과'),
    h('p', {}, r.message),
    r.steps && r.steps.length ? table([
      { label: '계좌', render: s => s.account },
      { label: '단계', render: s => STEP[s.step] || s.step },
      { label: '결과', render: s => h('span', { class: `outcome-${s.outcome.toLowerCase()}` }, OUTCOME[s.outcome] || s.outcome) },
      { label: '설명', render: s => s.urgent ? h('strong', {}, `긴급: ${s.note}`) : s.note },
    ], r.steps, { rowClass: s => s.urgent ? 'urgent' : '' }) : null));
}
