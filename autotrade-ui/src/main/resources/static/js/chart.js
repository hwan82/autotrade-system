// 자산 곡선 겹쳐 보기(SVG). 같은 실험의 계좌만 그리므로 시드머니·통화·날짜 축이 같다.
import { h, s } from './dom.js';
import { money } from './format.js';

export const SERIES_COLORS = ['#2f4ba0', '#c77d1a', '#1e8c6e', '#8a3fa0', '#b33a3a', '#4d6b7a', '#7a8a1e', '#c0478a'];

export function equityChart(overlay) {
  const W = 880, H = 320, L = 84, R = 36, T = 14, B = 34;
  const series = overlay.series.filter(x => x.points.length > 0);
  const wrap = h('div', { class: 'chart' });
  if (series.length === 0) {
    wrap.append(h('p', { class: 'muted' }, '자산 곡선 점이 없습니다. 완료되지 않은 계좌만 있는 실험입니다.'));
    return wrap;
  }
  const dates = [...new Set(series.flatMap(x => x.points.map(p => p.date)))].sort();
  const xi = new Map(dates.map((d, i) => [d, i]));
  const seed = Number(overlay.seedCapital);
  const values = series.flatMap(x => x.points.map(p => Number(p.equity))).concat([seed]);
  let lo = Math.min(...values), hi = Math.max(...values);
  if (lo === hi) { lo -= 1; hi += 1; }
  const pad = (hi - lo) * 0.06; lo -= pad; hi += pad;
  const x = i => L + (dates.length === 1 ? (W - L - R) / 2 : (i * (W - L - R)) / (dates.length - 1));
  const y = v => T + ((hi - v) * (H - T - B)) / (hi - lo);

  const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': '계좌별 자산 곡선(비용 차감 후)' });
  for (let k = 0; k <= 4; k++) {
    const v = lo + ((hi - lo) * k) / 4;
    svg.append(s('line', { class: 'gridline', x1: L, x2: W - R, y1: y(v), y2: y(v) }));
    svg.append(s('text', { class: 'axis', x: L - 8, y: y(v) + 4, 'text-anchor': 'end' }, money(v, overlay.currency)));
  }
  const ticks = Math.min(6, dates.length);
  for (let k = 0; k < ticks; k++) {
    const i = ticks === 1 ? 0 : Math.round((k * (dates.length - 1)) / (ticks - 1));
    svg.append(s('text', { class: 'axis', x: x(i), y: H - 10, 'text-anchor': 'middle' }, dates[i].slice(2)));
  }
  svg.append(s('line', { class: 'baseline', x1: L, x2: W - R, y1: y(seed), y2: y(seed) }));

  const paths = series.map((ser, idx) => {
    const d = ser.points.map((p, j) => `${j === 0 ? 'M' : 'L'}${x(xi.get(p.date)).toFixed(1)},${y(Number(p.equity)).toFixed(1)}`).join(' ');
    const path = s('path', { class: 'series', d, stroke: SERIES_COLORS[idx % SERIES_COLORS.length] },
      s('title', {}, `${ser.profileId} v${ser.profileVersion} (${ser.runId})`));
    svg.append(path);
    return path;
  });

  const legend = h('div', { class: 'legend', role: 'group', 'aria-label': '계좌 선택' },
    series.map((ser, idx) => {
      const btn = h('button', { type: 'button', 'aria-pressed': 'true' },
        h('span', { class: 'swatch', 'data-color': idx }), `${ser.profileId} v${ser.profileVersion}`);
      btn.addEventListener('click', () => {
        const on = btn.getAttribute('aria-pressed') !== 'true';
        btn.setAttribute('aria-pressed', String(on));
        paths[idx].classList.toggle('dim', !on);
      });
      return btn;
    }));
  colorSwatches(legend);
  wrap.append(svg, legend, h('p', { class: 'hint' }, `점선은 시드머니 ${money(seed, overlay.currency)}입니다. 범례를 눌러 계좌를 흐리게 하거나 다시 켭니다.`));
  return wrap;
}

/** CSP가 인라인 style 속성을 막으므로 색은 CSSOM으로 넣는다. */
export function colorSwatches(root) {
  root.querySelectorAll('.swatch[data-color]').forEach(el => {
    el.style.backgroundColor = SERIES_COLORS[Number(el.dataset.color) % SERIES_COLORS.length];
  });
}
