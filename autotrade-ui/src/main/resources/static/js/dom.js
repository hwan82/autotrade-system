// DOM 도우미. 서버·사용자 문자열은 항상 textContent로 넣는다(innerHTML 금지, XSS 방지).
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  append(el, children);
  return el;
}

const SVG_NS = 'http://www.w3.org/2000/svg';
export function s(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined) continue;
    el.setAttribute(k, String(v));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function mount(el) {
  const view = document.getElementById('view');
  view.replaceChildren(el);
  view.focus({ preventScroll: true });
}

let toastTimer;
export function toast(message, isError = false) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.toggle('error', isError);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, isError ? 8000 : 4000);
}

export function table(columns, rows, { rowClass } = {}) {
  return h('div', { class: 'table-wrap' },
    h('table', {},
      h('thead', {}, h('tr', {}, columns.map(c => h('th', { class: c.num ? 'num' : null, scope: 'col' }, c.label)))),
      h('tbody', {}, rows.map(r => h('tr', { class: rowClass ? rowClass(r) : null },
        columns.map(c => h('td', { class: c.num ? 'num' : null }, c.render(r))))))));
}
