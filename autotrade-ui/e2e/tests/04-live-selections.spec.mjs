// #/live 선정 조합 표(PR #201 architect N2, PR #206 N3): 장전 선정의 "다음 거래일 준비"(마지막 준비 줄의 일봉·재무·공시 스냅샷,
// 파일 상태 표)와 장중 순위 선정 표. 실시간 세션은 게이트웨이 시세가 있어야 돌므로 /api/live 응답만 가짜로 준다. 가짜 응답의
// 이름은 서버 레코드(LiveLabService.View)와 같고, 이름이 맞는지는 LiveLabServiceTest#assertScreenFields가 확인한다.
// 실험 생성 화면의 공시 스냅샷 칸도 스냅샷 목록 응답만 가짜로 줘서 본다. 흐름 실험실(배포본)을 쓰고 아무것도 쓰지 않는다.
import { expect, test } from '@playwright/test';
import { FLOW_PORT } from '../support/paths.mjs';
import { watchErrors } from '../support/errors.mjs';

const BASE = `http://127.0.0.1:${FLOW_PORT}`;
const ID = 'EXP-20261020-E2ELIVE';
const DISC = 'dart-disclosures-2026-10-19-0a1b2c3d';
const won = amount => ({ amount, currency: 'KRW' });

function run(n, tradingDay) {
  return { runId: `${ID}-A0${n}`, cash: won(10000000), equity: won(10000000), positions: 0, fills: 0, rejections: 0, tradingDay };
}

function view({ prepareDate = '2026-10-21', disclosures = DISC } = {}) {
  return {
    experimentId: ID, market: 'KRX',
    profiles: ['kr-orb-30@1', 'kr-intraday-news@1 x kr-orb-30@1', 'kr-intraday-hot-at@1 x kr-orb-30@1'],
    restoring: false,
    session: {
      experimentId: ID, from: '2026-10-20', to: '2026-12-18', events: 1200, restored: 0, skippedSymbol: 0, skippedRange: 0,
      dataGaps: 0, lastEventAt: '2026-10-20T06:30:00Z', journalDate: '2026-10-20', stopReason: '', closed: false,
      runs: [run(1, '2026-10-20'), run(2, '2026-10-20'), run(3, '2026-10-20')], failures: ['', '', ''],
      selections: null,
      intraday: [{
        selection: 'kr-intraday-hot-at@1', selectionHash: 'h2', at: '09:30', state: 'RANKED', candidates: ['005930', '000660'],
        blacklisted: ['035720'], selectedCount: 3, activeFrom: 812, resultHash: 'r1', universe: 100, sessionGap: 4,
      }],
    },
    feedState: 'CONNECTED', feedError: '', feedReconnects: 0, inboxQueued: 0, inboxDropped: 0, stopReason: '', stopKind: 'NONE',
    prepare: {
      experimentId: ID, date: prepareDate, dailySnapshot: 'krx-daily-2026-10-20-0a1b2c3d',
      financialsSnapshot: 'dart-financials-2026-10-19-0a1b2c3d', disclosuresSnapshot: disclosures,
      outcomes: [], note: '선정 1개 결과 파일 있음, 1개 못 만듦',
    },
    dailySelections: true,
    nextDay: {
      date: '2026-10-21',
      items: [
        { selection: 'kr-intraday-news@1', state: 'READY', reason: null, detail: '', inputs: '' },
        { selection: 'kr-intraday-quiet@1', state: 'PROBLEM', reason: 'STALE_INPUT', detail: '공시 스냅샷이 T−1보다 오래됨',
          inputs: `daily=krx-daily-2026-10-20-0a1b2c3d;financials=;disclosures=${DISC}` },
      ],
    },
  };
}

/** 선정 이름 칸이 정확히 이 이름인 줄(계좌 표의 "선정 x 전략" 줄은 고르지 않는다). */
function row(page, selection) {
  return page.getByRole('row').filter({ has: page.getByRole('cell', { name: selection, exact: true }) });
}

async function fakeLive(page, body) {
  await page.route('**/api/live/status', r => r.fulfill({ json: body }));
  await page.route('**/api/live/gaps**', r => r.fulfill({ json: { total: 0, items: [] } }));
}

test('다음 거래일 준비가 마지막 준비에 쓴 공시 스냅샷과 파일 상태를 보인다', async ({ page }) => {
  const errors = watchErrors(page);
  await fakeLive(page, view());
  await page.goto(`${BASE}/#/live`);
  await expect(page.getByRole('heading', { name: '선정 조합' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '다음 거래일 준비 · 2026-10-21' })).toBeVisible();

  // 마지막 준비 줄: 일봉·재무·공시 순서
  await expect(page.getByText(/^마지막 준비: /)).toHaveText('마지막 준비: 선정 1개 결과 파일 있음, 1개 못 만듦'
    + ' · 일봉 krx-daily-2026-10-20-0a1b2c3d · 재무 dart-financials-2026-10-19-0a1b2c3d'
    + ` · 공시 ${DISC}`);

  // 파일 상태 표: 결과 파일 있음, 못 만듦(사유·입력 키)
  await expect(row(page, 'kr-intraday-news@1')).toContainText('결과 파일 있음');
  const problem = row(page, 'kr-intraday-quiet@1');
  await expect(problem).toContainText('못 만듦 · STALE_INPUT · 공시 스냅샷이 T−1보다 오래됨');
  await expect(problem).toContainText(`disclosures=${DISC}`);
  await expect(page.getByText('"계산에 쓴 입력"이 있는 사유는 같은 스냅샷으로 다시 계산하지 않습니다', { exact: false })).toBeVisible();

  // 장중 순위 선정 표
  const ranked = row(page, 'kr-intraday-hot-at@1');
  await expect(ranked).toContainText('RANKED');
  await expect(ranked).toContainText('2종목 (선정 3)');
  await expect(ranked).toContainText('035720 BLACKLISTED(블랙리스트)');
  await expect(ranked).toContainText('SESSION_GAP 4/100');
  await expect(ranked).toContainText('이벤트 812번부터');
  errors.expectNone();
});

test('공시 입력이 없는 준비는 공시를 보이지 않고, 다른 날 준비면 마지막 준비 줄 대신 안내를 보인다', async ({ page }) => {
  const errors = watchErrors(page);
  await fakeLive(page, view({ disclosures: '' }));
  await page.goto(`${BASE}/#/live`);
  const line = page.getByText(/^마지막 준비: /);
  await expect(line).toContainText('재무 dart-financials-2026-10-19-0a1b2c3d');
  await expect(line).not.toContainText('공시');

  await page.unroute('**/api/live/status');
  await page.route('**/api/live/status', r => r.fulfill({ json: view({ prepareDate: '2026-10-20' }) }));
  await page.getByRole('button', { name: '새로 읽기', exact: true }).click();
  await expect(page.getByText(/^마지막 준비: /)).toHaveCount(0);
  await expect(page.getByText('이번 실험실 실행에서는 아직 준비하지 않았습니다.', { exact: false })).toBeVisible();
  errors.expectNone();
});

test('실험 생성 화면의 공시 스냅샷 칸이 dart-disclosures 목록을 보인다', async ({ page }) => {
  const errors = watchErrors(page);
  const isDisclosures = url => url.pathname.endsWith('/api/experiment-folders/snapshots')
    && url.searchParams.get('kind') === 'dart-disclosures';
  await page.route(isDisclosures, r => r.fulfill({ json: {
    root: '/tmp/snapshots',
    items: [
      { id: DISC, firstDate: '2015-01-02', lastDate: '2026-10-19T22:10:00Z', problem: null },
      { id: 'dart-disclosures-broken', firstDate: null, lastDate: null, problem: 'manifest.json을 읽을 수 없습니다' },
    ],
  } }));
  await page.goto(`${BASE}/#/experiments/new`);
  const disc = page.locator('#xdisc');
  await expect(disc).toHaveValue('');
  await expect(disc.locator('option')).toHaveText([
    '없음(공시 조건 선정이 없을 때)',
    `${DISC} (2015-01-02 ~ 2026-10-19T22:10:00Z)`,
    'dart-disclosures-broken (manifest.json을 읽을 수 없습니다)',
  ]);
  await expect(disc.locator('option').nth(2)).toBeDisabled();
  await disc.selectOption(DISC);
  await expect(disc).toHaveValue(DISC);
  errors.expectNone();
});

test('세션이 없을 때 3초 폴링이 같은 등록 목록을 다시 그리지 않아 이어서 시작 버튼과 스크롤이 그대로다', async ({ page }) => {
  // LIVE01 2026-10-27: 폴링마다 목록을 새로 그려 스크롤이 튀고 "이어서 시작"을 못 눌렀다
  const errors = watchErrors(page);
  await page.setViewportSize({ width: 900, height: 400 });
  const idle = { experimentId: '', restoring: false, session: null, feedState: 'NEW', feedError: '', feedReconnects: 0,
    inboxQueued: 0, inboxDropped: 0, stopReason: '', stopKind: 'NONE', prepare: null, dailySelections: false, nextDay: null };
  const regs = Array.from({ length: 12 }, (_, i) => ({
    id: `EXP-20261026-E2EIDLE${String(i).padStart(2, '0')}`, question: '스크롤 시험용 등록', period: '2026-10-26 ~ 2026-12-18',
    profiles: ['kr-orb-30@1'], symbols: 10, hasJournal: true, finished: false, error: null,
  }));
  let statusReads = 0;
  await page.route('**/api/live/status', r => { statusReads++; return r.fulfill({ json: idle }); });
  await page.route('**/api/live/registrations', r => r.fulfill({ json: regs }));
  await page.goto(`${BASE}/#/live`);
  const last = page.getByRole('button', { name: '이어서 시작' }).last();
  await last.scrollIntoViewIfNeeded();
  await last.evaluate(b => { b.dataset.e2eMark = 'kept'; });
  const y = await page.evaluate(() => window.scrollY);
  expect(y).toBeGreaterThan(0);
  const before = statusReads;
  await expect.poll(() => statusReads, { timeout: 10000 }).toBeGreaterThan(before + 1); // 폴링이 두 번 이상 돈 뒤
  await expect(last).toHaveAttribute('data-e2e-mark', 'kept'); // 같은 버튼 요소가 남아 있다
  expect(await page.evaluate(() => window.scrollY)).toBe(y);
  errors.expectNone();
});
