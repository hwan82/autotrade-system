// 실험 진행 상태 화면(D-093 jobs.js, D-098 워크포워드 전체 진행률)을 데모 실험실(DemoLabApplication)로 시험한다(D-101).
// 데모는 실행 명령 대신 상태 파일을 5초마다 쓴다: 조합 작업 DEMO-20261015-RUN01, 워크포워드 DEMO-20261016-WF01(실험 24번),
// 끝난 작업 DONE1(완료)·STOP1(정지 요청으로 멈춤). RUN01을 취소하면 워크포워드 갱신도 멈추므로 취소는 마지막에 한다.
import { expect, test } from '@playwright/test';
import { DEMO_PORT } from '../support/paths.mjs';
import { watchErrors } from '../support/errors.mjs';

const BASE = `http://127.0.0.1:${DEMO_PORT}`;

test.describe.configure({ mode: 'serial' });

const valueNow = async bar => Number(await bar.getAttribute('aria-valuenow'));

test('진행 막대가 실시간 연결로 움직이고 끝난 작업을 펼치고 실행을 취소한다', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto(`${BASE}/#/experiments`);
  const jobs = page.locator('section.jobs');
  await expect(jobs.getByRole('heading', { name: '실행 중인 실험' })).toBeVisible();
  await expect(jobs.locator('.section-head .badge')).toHaveText('실시간 연결');

  // 조합 작업: 진행 막대와 Run·거래일 숫자, 다음 갱신에 값이 커진다
  const run = jobs.getByRole('row').filter({ hasText: 'DEMO-20261015-RUN01' });
  await expect(run).toContainText('실행 중');
  await expect(run).toContainText('조합 실험');
  const runBar = run.getByRole('progressbar');
  await expect(runBar).toHaveAttribute('aria-label', /^DEMO-20261015-RUN01 진행률 \d+\.\d%$/);
  await expect(run).toContainText(/Run \d+\/8 끝/);
  await expect(run).toContainText(/거래일 \d+\/160/);
  const runBefore = await valueNow(runBar);
  await expect.poll(() => valueNow(runBar), { timeout: 20_000 }).toBeGreaterThan(runBefore);

  // 워크포워드(D-098): 막대는 명령 전체 진행률이고 지금 실험의 진행은 아래 줄
  const wf = jobs.getByRole('row').filter({ hasText: 'DEMO-20261016-WF01' });
  await expect(wf).toContainText('워크포워드');
  const wfBar = wf.getByRole('progressbar');
  await expect(wfBar).toHaveAttribute('aria-label', /^DEMO-20261016-WF01 전체 진행률 \d+\.\d%$/);
  await expect(wf).toContainText(/전체 \d+\.\d% · 실험 \d+\/24번째/);
  await expect(wf).toContainText(/지금 실험 DEMO-20261016-WF01-W1-N\d+ \(진행\)/);
  const wfBefore = await valueNow(wfBar);
  await expect.poll(() => valueNow(wfBar), { timeout: 20_000 }).toBeGreaterThan(wfBefore);
  expect(await valueNow(wfBar)).toBeLessThanOrEqual(1000);

  // 끝난 작업은 접혀 있다가 펼친다
  await expect(jobs.getByRole('row').filter({ hasText: 'DEMO-20261013-DONE1' })).toHaveCount(0);
  await jobs.getByRole('button', { name: '끝난 작업 2개 보기' }).click();
  await expect(jobs.getByRole('row').filter({ hasText: 'DEMO-20261013-DONE1' })).toContainText('완료');
  const stopped = jobs.getByRole('row').filter({ hasText: 'DEMO-20261013-STOP1' });
  await expect(stopped).toContainText('정지 요청으로 멈춤');
  await expect(stopped).toContainText('장 시간 보호 구간이라 멈췄습니다');
  await jobs.getByRole('button', { name: '끝난 작업 숨기기' }).click();
  await expect(jobs.getByRole('row').filter({ hasText: 'DEMO-20261013-DONE1' })).toHaveCount(0);

  // 취소: 확인 창을 거쳐 취소 요청 파일만 만들고, 데모 실행기가 다음 확인 지점에서 취소됨으로 끝낸다
  page.once('dialog', d => {
    expect(d.message()).toContain('DEMO-20261015-RUN01 실행을 취소합니다');
    d.accept();
  });
  await run.getByRole('button', { name: '취소' }).click();
  await expect(run).toContainText('취소 요청됨');
  await expect(run.getByRole('button', { name: '취소' })).toHaveCount(0);
  await expect(run).toHaveCount(0, { timeout: 20_000 }); // 끝나면 실행 중 목록에서 빠진다
  await jobs.getByRole('button', { name: /끝난 작업 \d+개 보기/ }).click();
  await expect(jobs.getByRole('row').filter({ hasText: 'DEMO-20261015-RUN01' })).toContainText('취소됨');

  errors.expectNone();
});
