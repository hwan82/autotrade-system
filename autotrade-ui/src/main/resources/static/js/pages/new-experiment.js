// 실험 생성(UI-02, D-074): 기간·시드·조합(선정 × 전략)을 골라 조합 실험 등록 파일 experiments/<실험ID>/experiment.yaml을 쓴다.
// 실험실은 실험을 돌리지 않는다(D-062). 등록 파일을 커밋하고 실행 명령으로 돌린 뒤, 실험 목록의 실험 폴더에서 불러와 비교한다.
// 조합은 시장·스타일이 같은 선정·전략 프로필끼리만 고를 수 있다(STR-02). 같은 검사를 서버가 쓰기 전에 다시 한다.
import { h, toast } from '../dom.js';
import { get, getAll, post } from '../api.js';
import { number, MARKET, STYLE } from '../format.js';

const enc = encodeURIComponent;
const MAX_COMBINATIONS = 128;

function today() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`;
}

async function settingValue(key) {
  try {
    const s = await get('/api/settings');
    for (const g of s.groups) for (const i of g.items) if (i.key === key) return i.value;
  } catch { /* 설정을 못 읽으면 빈 칸으로 둔다 */ }
  return '';
}

async function snapshots(kind) {
  try { return await get(`/api/experiment-folders/snapshots?kind=${enc(kind)}`); } catch (e) { return { items: [], error: e.message }; }
}

export async function newExperimentPage() {
  const [seedCapital, bars, financials] = await Promise.all([
    settingValue('lab.default-seed-capital-krw'), snapshots('krx-daily'), snapshots('dart-financials')]);
  const ymd = today();

  const field = (id, attrs = {}) => h('input', { id, autocomplete: 'off', ...attrs });
  const idInput = field('xid', { value: `EXP-${ymd}-CMB01`, pattern: '[A-Z0-9][A-Z0-9-]{0,47}', required: true });
  const question = h('textarea', { id: 'xq', rows: 2, maxlength: 500,
    placeholder: '예: (나) 선정이 같은 스윙 지표의 선정 없음보다 비용 차감 후 나은가(한 문장)' });
  const market = h('select', { id: 'xmarket' }, Object.entries(MARKET).map(([k, v]) =>
    h('option', { value: k, disabled: k !== 'KRX' }, k === 'KRX' ? v : `${v}, 조합 실험 실행 명령이 아직 국내만 돌림`)));
  const style = h('select', { id: 'xstyle' }, Object.entries(STYLE).map(([k, v]) => h('option', { value: k, selected: k === 'SWING' }, v)));
  const from = field('xfrom', { type: 'date' });
  const to = field('xto', { type: 'date' });
  const seed = field('xseed', { class: 'narrow', inputmode: 'numeric', value: ymd });
  const capital = field('xcap', { class: 'narrow', inputmode: 'numeric', value: seedCapital });
  const parallelism = field('xpar', { class: 'narrow', inputmode: 'numeric', value: '2' });
  const benchmark = field('xbench', { class: 'narrow', placeholder: 'none 또는 종목 코드', value: 'none' });
  const blacklist = field('xblack', { placeholder: '쉼표로 구분한 종목 코드(선택)' });

  const snapshotPicker = (id, res, optional) => {
    const sel = h('select', { id }, optional ? h('option', { value: '' }, '없음(재무 조건 선정이 없을 때)') : null,
      res.items.map(s => h('option', { value: s.id, disabled: !!s.problem },
        s.problem ? `${s.id} (${s.problem})` : `${s.id} (${s.firstDate || '?'} ~ ${s.lastDate || '?'})`)));
    if (!optional && res.items.length === 0) {
      // 수집 스냅샷이 이 PC에 없으면 ID를 직접 적는다(다른 PC에서 수집한 경우)
      return field(id, { placeholder: 'krx-daily-<마지막 거래일>-<해시 8자리>' });
    }
    return sel;
  };
  const barsInput = snapshotPicker('xbars', bars, false);
  const finInput = snapshotPicker('xfin', financials, true);
  const coverage = h('p', { class: 'hint' });
  const checkCoverage = () => {
    const s = bars.items.find(x => x.id === barsInput.value);
    if (!s || !s.firstDate || !s.lastDate || !from.value || !to.value) { coverage.textContent = ''; return; }
    const outside = from.value < s.firstDate || to.value > s.lastDate;
    coverage.className = outside ? 'hint warn-text' : 'hint';
    coverage.textContent = outside
      ? `기간이 스냅샷 범위(${s.firstDate} ~ ${s.lastDate}) 밖입니다. 실행 명령이 거절합니다.`
      : `스냅샷 범위 ${s.firstDate} ~ ${s.lastDate} 안입니다.`;
  };
  [barsInput, from, to].forEach(el => el.addEventListener('change', checkCoverage));

  const picker = h('div', {});
  const count = h('p', { class: 'muted small', 'aria-live': 'polite' });
  let cells = [];
  const chosen = () => cells.filter(c => c.box.checked).map(c => `${c.sel()} x ${c.strat()}`);
  const recount = () => {
    const n = chosen().length;
    count.className = n > MAX_COMBINATIONS ? 'small warn-text' : 'muted small';
    count.textContent = `고른 조합 ${number(n)}개(상한 ${MAX_COMBINATIONS}). 조합 순서대로 계좌 번호가 붙습니다.`;
  };

  // 시장·스타일 필터: 고른 시장·스타일의 선정·전략 프로필만 보인다. 다른 것은 조합으로 고를 수 없다(STR-02)
  const drawPicker = async () => {
    picker.replaceChildren(h('p', { class: 'muted' }, '프로필을 읽는 중입니다.'));
    const q = `market=${enc(market.value)}&style=${enc(style.value)}`;
    let sels;
    let strats;
    try {
      [sels, strats] = await Promise.all([getAll(`/api/selections?${q}`), getAll(`/api/profiles?${q}`)]);
    } catch (e) {
      picker.replaceChildren(h('p', { class: 'notice warn' }, `프로필 목록을 읽지 못했습니다: ${e.message}`));
      return;
    }
    cells = [];
    if (strats.length === 0) {
      picker.replaceChildren(h('p', { class: 'muted' }, `${MARKET[market.value]} · ${STYLE[style.value]} 전략 프로필이 없습니다.`));
      recount();
      return;
    }
    const versionSelect = (p, label) => {
      const s = h('select', { 'aria-label': `${p.id} 버전`, title: label },
        [...p.versions].reverse().map(v => h('option', { value: `${p.id}@${v}` }, `v${v}`)));
      s.addEventListener('change', recount);
      return s;
    };
    const stratVersions = strats.map(p => versionSelect(p, '전략 프로필 버전'));
    const rows = [{ id: null }, ...sels];
    const head = h('tr', {}, h('th', { scope: 'col' }, '선정 \\ 전략'),
      strats.map((p, i) => h('th', { scope: 'col' }, h('a', { href: `#/profiles/${enc(p.id)}` }, p.id), ' ', stratVersions[i])));
    const body = rows.map(r => {
      const selVersion = r.id ? versionSelect(r, '선정 프로필 버전') : null;
      const selText = () => (selVersion ? selVersion.value : 'none');
      return h('tr', {},
        h('th', { scope: 'row' }, r.id ? h('span', {}, h('a', { href: `#/selections/${enc(r.id)}` }, r.id), ' ', selVersion)
          : h('span', {}, '선정 없음', h('span', { class: 'sub' }, '같은 매매지표 + 유니버스 전체(대조군)'))),
        strats.map((p, i) => {
          const box = h('input', { type: 'checkbox', 'aria-label': `${r.id || '선정 없음'} x ${p.id}` });
          box.addEventListener('change', recount);
          cells.push({ box, sel: selText, strat: () => stratVersions[i].value });
          return h('td', {}, box);
        }));
    });
    picker.replaceChildren(...[
      sels.length === 0 ? h('p', { class: 'muted small' }, `${MARKET[market.value]} · ${STYLE[style.value]} 선정 프로필이 없어 "선정 없음" 행만 있습니다.`) : null,
      h('div', { class: 'table-wrap' }, h('table', { class: 'param-table' }, h('thead', {}, head), h('tbody', {}, body))),
      h('p', { class: 'hint' }, '대조군 두 축을 함께 고르세요: 같은 선정 + 무작위 진입 지표(예: kr-random-daily-swing), 같은 지표 + 선정 없음. '
        + '조합 수가 많을수록 다중 비교 보정(본페로니)이 엄격해집니다(조합 실험 절차 1장).')].filter(Boolean));
    recount();
  };
  market.addEventListener('change', drawPicker);
  style.addEventListener('change', drawPicker);

  const payload = () => ({
    id: idInput.value.trim(), question: question.value.trim(), market: market.value, style: style.value,
    from: from.value, to: to.value, seed: seed.value.trim(), seedCapital: capital.value.trim(),
    barsSnapshot: barsInput.value.trim(), financialsSnapshot: finInput.value.trim(), benchmark: benchmark.value.trim(),
    parallelism: parallelism.value.trim(), combinations: chosen(),
    blacklist: blacklist.value.split(',').map(x => x.trim()).filter(Boolean),
  });

  const out = h('div', { 'aria-live': 'polite' });
  const previewBtn = h('button', { class: 'btn secondary', type: 'button' }, '등록 파일 미리 보기');
  const createBtn = h('button', { class: 'btn', type: 'button' }, '등록 파일 쓰기');
  const run = async (button, action) => {
    button.disabled = true;
    try { await action(); } catch (e) { out.replaceChildren(h('p', { class: 'notice warn' }, e.message)); } finally { button.disabled = false; }
  };
  previewBtn.addEventListener('click', () => run(previewBtn, async () => {
    const r = await post('/api/experiment-folders/preview', payload());
    out.replaceChildren(h('h2', {}, '미리 보기'), h('p', { class: 'muted small' }, '아직 쓰지 않았습니다. 이 글이 그대로 등록 파일이 됩니다.'),
      h('pre', { class: 'yaml' }, r.yaml));
  }));
  createBtn.addEventListener('click', () => run(createBtn, async () => {
    const r = await post('/api/experiment-folders', payload());
    toast(`${r.id} 등록 파일을 썼습니다`);
    out.replaceChildren(
      h('h2', {}, '등록했습니다. 다음 순서로 돌리세요'),
      h('ol', {},
        h('li', {}, '등록 파일을 커밋합니다(사전 등록. 결과를 본 뒤 고치지 않고, 바꾸려면 새 실험 ID를 씁니다).',
          h('pre', { class: 'yaml' }, `git add experiments/${r.id}/experiment.yaml\ngit commit -m "${r.id} 사전 등록"`)),
        h('li', {}, '터미널에서 실행 명령을 돌립니다(장 시간 보호 구간에는 멈춤, D-063).', h('pre', { class: 'yaml' }, r.command)),
        h('li', {}, '실험 목록의 실험 폴더에서 이 실험을 불러오면 리더보드·교차표·자산 곡선으로 비교합니다. ',
          h('a', { href: '#/experiments' }, '실험 목록 열기'))),
      h('h3', {}, '쓴 등록 파일'), h('pre', { class: 'yaml' }, r.yaml));
  }));

  await drawPicker();
  return h('div', {},
    h('p', { class: 'crumb' }, h('a', { href: '#/experiments' }, '실험'), ' / 새 실험 등록'),
    h('h1', {}, '새 실험 등록'),
    h('p', { class: 'muted' }, '같은 기간·시드·비용 모델로 돌릴 조합(선정 × 매매지표)을 고릅니다. 실험실은 실험을 돌리지 않고 등록 파일만 씁니다. '
      + '등록 파일을 커밋하고 실행 명령으로 돌린 뒤 실험 폴더에서 불러와 비교합니다.'),
    h('div', { class: 'form-grid' },
      h('label', { for: 'xid' }, '실험 ID'), h('div', {}, idInput, h('p', { class: 'hint' }, '폴더 이름이 됩니다. 영문 대문자·숫자·하이픈 48자 이내이고, 이미 있는 ID는 쓸 수 없습니다.')),
      h('label', { for: 'xq' }, '질문'), question,
      h('label', { for: 'xmarket' }, '시장'), market,
      h('label', { for: 'xstyle' }, '매매 스타일'), h('div', {}, style, h('p', { class: 'hint' }, '이 스타일의 선정·전략 프로필만 조합으로 보입니다.')),
      h('label', { for: 'xfrom' }, '시작일'), from,
      h('label', { for: 'xto' }, '종료일'), h('div', {}, to, coverage),
      h('label', { for: 'xseed' }, '시드'), seed,
      h('label', { for: 'xcap' }, '시드머니(원)'), h('div', {}, capital, h('p', { class: 'hint' }, '기본값은 설정의 lab.default-seed-capital-krw입니다.')),
      h('label', { for: 'xbars' }, '일봉 스냅샷'), h('div', {}, barsInput,
        h('p', { class: 'hint' }, bars.items.length ? `수집기가 쓴 스냅샷(${bars.root})` : `이 PC에 일봉 스냅샷이 없습니다${bars.root ? `(${bars.root})` : ''}. ID를 직접 적거나 먼저 수집하세요.`)),
      h('label', { for: 'xfin' }, '재무 스냅샷'), h('div', {}, finInput, h('p', { class: 'hint' }, '재무 지표를 쓰는 선정 프로필이 있으면 필요합니다.')),
      h('label', { for: 'xbench' }, '비교 종목'), benchmark,
      h('label', { for: 'xpar' }, '병렬 수'), parallelism,
      h('label', { for: 'xblack' }, '제외 종목'), blacklist),
    h('h2', {}, '조합 고르기'), count, picker,
    h('div', { class: 'actions' }, previewBtn, createBtn),
    out);
}
