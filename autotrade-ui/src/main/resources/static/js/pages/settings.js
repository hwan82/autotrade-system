// 설정 화면(UI-04): default 값 표시, 즉시 반영 키 편집, 비밀값 마스킹 입력(쓰기 전용).
import { h, toast } from '../dom.js';
import { get, put } from '../api.js';

export async function settingsPage() {
  const host = h('div', {});
  const draw = (view) => host.replaceChildren(form(view, draw));
  draw(await get('/api/settings'));
  return h('div', {},
    h('h1', {}, '설정'),
    h('p', { class: 'muted' }, '저장하면 설정 파일에 먼저 쓰고, 성공하면 바로 반영합니다. 비밀값은 화면과 응답에 나오지 않으며, 새 값을 넣을 때만 바뀝니다.'),
    host);
}

function form(view, redraw) {
  const controls = [];
  const groups = view.groups.map(g => h('section', { 'aria-labelledby': `g-${g.id}` },
    h('h2', { id: `g-${g.id}` }, g.title),
    g.id === 'brokers' ? h('p', { class: 'muted small' }, '증권사 키는 미체결·보유 확인, 세션 종료, 재연결 절차가 필요해 이 화면에서 바꾸지 않습니다. secrets.json에서 바꾸고 해당 프로세스를 다시 띄웁니다.') : null,
    g.id === 'real-trading' ? h('p', { class: 'muted small' }, '실전 계좌 설정은 읽기만 합니다. 실전 활성화는 설정이 아니며 실전 엔진 운영 화면에서 2차 확인으로만 켭니다.') : null,
    g.items.map(item => row(item, controls))));
  const save = h('button', { class: 'btn', type: 'button' }, '변경 저장');
  save.addEventListener('click', async () => {
    const values = {};
    const clearSecrets = [];
    for (const c of controls) c.collect(values, clearSecrets);
    if (Object.keys(values).length === 0 && clearSecrets.length === 0) { toast('바뀐 값이 없습니다'); return; }
    let acknowledgePaid = false;
    if (values['notification.kakao.mode'] === 'BIZ') {
      acknowledgePaid = window.confirm('카카오 알림톡(BIZ)은 건당 비용이 드는 유료 서비스입니다. 비용을 확인했고 바꾸겠습니까?');
      if (!acknowledgePaid) return;
    }
    save.disabled = true;
    try {
      const next = await put('/api/settings', { values, clearSecrets, acknowledgePaid });
      toast(`저장했습니다: ${[...Object.keys(values), ...clearSecrets].join(', ')}`);
      redraw(next);
    } catch (e) {
      toast(e.message, true);
    } finally {
      save.disabled = false;
    }
  });
  return h('div', {}, groups, h('div', { class: 'actions' }, save));
}

function row(item, controls) {
  const id = `s-${item.key.replace(/[^a-z0-9]/gi, '-')}`;
  const meta = h('div', { class: 'meta' },
    item.secret ? null : h('div', { class: item.value !== item.defaultValue ? 'changed-from-default' : null },
      `기본값 ${item.defaultValue === '' ? '(없음)' : item.defaultValue}${item.value !== item.defaultValue ? ', 바뀐 값' : ''}`),
    h('div', {}, `반영: ${item.live}`));
  const label = h('div', {}, h('label', { for: id, class: 'key' }, item.key), item.note ? h('p', { class: 'hint' }, item.note) : null);
  return h('div', { class: 'setting' }, label, control(item, id, controls), meta);
}

function control(item, id, controls) {
  if (item.secret) return secretControl(item, id, controls);
  if (!item.editable) return h('input', { id, readonly: true, value: item.value });
  let input;
  if (item.type === 'bool') {
    input = h('select', { id }, ['true', 'false'].map(v => h('option', { value: v, selected: item.value === v }, v === 'true' ? '켬' : '끔')));
  } else if (item.type === 'enum') {
    input = h('select', { id }, item.options.map(v => h('option', { value: v, selected: item.value === v }, v)));
  } else {
    input = h('input', { id, type: 'number', step: 1, value: item.value, inputmode: 'numeric' });
  }
  controls.push({ collect: (values) => { if (input.value !== item.value) values[item.key] = input.value; } });
  return input;
}

/** 비밀값: 설정 여부만 보이고, 새 값은 쓰기 전용 입력으로만 받는다. */
function secretControl(item, id, controls) {
  const state = h('span', { class: `chip${item.configured ? '' : ' off'}` }, item.configured ? `설정됨 ${item.value}` : '미설정');
  if (!item.editable) return h('div', { class: 'secret-state', id }, state);
  const input = h('input', { id, type: 'password', autocomplete: 'new-password', spellcheck: 'false',
    placeholder: item.configured ? '바꿀 때만 새 값 입력' : '값 입력' });
  const clear = item.configured ? h('input', { type: 'checkbox', id: `${id}-clear` }) : null;
  controls.push({
    collect: (values, clearSecrets) => {
      if (clear && clear.checked) clearSecrets.push(item.key);
      else if (input.value.trim() !== '') values[item.key] = input.value;
    },
  });
  return h('div', {}, h('div', { class: 'secret-state' }, state,
    clear ? h('label', { for: `${id}-clear`, class: 'small' }, clear, ' 지우기') : null), input);
}
