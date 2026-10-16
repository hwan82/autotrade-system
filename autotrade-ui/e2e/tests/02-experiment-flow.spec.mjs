// 5단계 QA 권고 R3(D-101): 실험 생성 화면으로 등록 → 화면이 알려 준 실행 명령 → 실험 폴더에서 불러오기 → 계좌 비교표·교차표·자산 곡선.
// 실행 명령은 화면에 나온 글을 그대로 돌린다. CI에는 실전 엔진이 없어 장 시간 보호(D-063)만 -PallowDuringSession으로 푼다.
// PC에서 돌릴 때는 풀지 않는다(장 시간에는 실행 명령이 멈추고 이 시험이 실패한다).
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { expect, test } from '@playwright/test';
import { CONFIG_DIR, EXPERIMENT_ID, FLOW_PORT, GRADLEW, REPO, SNAPSHOT } from '../support/paths.mjs';
import { tradingDays } from '../support/snapshot.mjs';
import { watchErrors } from '../support/errors.mjs';

const BASE = `http://127.0.0.1:${FLOW_PORT}`;
const DAYS = tradingDays('2026-01-02', 80);

test('화면으로 등록한 조합 실험을 실행 명령으로 돌리고 불러와 비교한다', async ({ page }) => {
  const errors = watchErrors(page);

  // 1. 새 실험 등록 화면
  await page.goto(`${BASE}/#/experiments/new`);
  await expect(page.getByRole('heading', { level: 1, name: '새 실험 등록' })).toBeVisible();
  await page.locator('#xid').fill(EXPERIMENT_ID);
  await page.locator('#xq').fill('화면 시험: (나) 선정이 같은 스윙 지표의 선정 없음보다 나은가');
  await expect(page.locator('#xstyle')).toHaveValue('SWING');
  await page.locator('#xfrom').fill(DAYS[0]);
  await page.locator('#xto').fill(DAYS[DAYS.length - 1]);
  await page.locator('#xseed').fill('7');
  await page.locator('#xcap').fill('10000000');
  await page.locator('#xbars').selectOption(SNAPSHOT);
  await expect(page.getByText(`스냅샷 범위 ${DAYS[0]} ~ ${DAYS[DAYS.length - 1]} 안입니다.`)).toBeVisible();

  // 스윙 선정·전략 프로필만 조합 표에 나온다(STR-02): 중기 선정 kr-fin-quality-growth는 없다
  await expect(page.getByRole('link', { name: 'kr-vol-surge-quiet' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'kr-fin-quality-growth' })).toHaveCount(0);
  await page.getByRole('checkbox', { name: 'kr-vol-surge-quiet x kr-ma-trend', exact: true }).check();
  await page.getByRole('checkbox', { name: '선정 없음 x kr-ma-trend', exact: true }).check();
  await expect(page.getByText(/고른 조합 2개/)).toBeVisible();

  // 2. 미리 보기는 쓰지 않는다
  await page.getByRole('button', { name: '등록 파일 미리 보기' }).click();
  await expect(page.getByRole('heading', { name: '미리 보기' })).toBeVisible();
  await expect(page.locator('pre.yaml')).toContainText('- "kr-vol-surge-quiet@1 x kr-ma-trend@1"');
  const folder = path.join(REPO, 'experiments', EXPERIMENT_ID);
  expect(fs.existsSync(folder)).toBe(false);

  // 3. 등록 파일 쓰기: 화면이 커밋 명령과 실행 명령을 알려 준다
  await page.getByRole('button', { name: '등록 파일 쓰기' }).click();
  await expect(page.getByRole('heading', { name: '등록했습니다. 다음 순서로 돌리세요' })).toBeVisible();
  const command = (await page.locator('ol li').nth(1).locator('pre').textContent()).trim();
  expect(command).toBe(`./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=${EXPERIMENT_ID} -PverifyDeterminism`);
  expect(fs.readFileSync(path.join(folder, 'experiment.yaml'), 'utf8')).toContain(`bars-snapshot: "${SNAPSHOT}"`);

  // 같은 ID로 다시 쓰면 덮어쓰지 않는다(409)
  await page.getByRole('button', { name: '등록 파일 쓰기' }).click();
  await expect(page.locator('.notice.warn')).toContainText('이미 있습니다');
  errors.expectHttp(409);

  // 실험 목록의 실험 폴더: 아직 실행 안 함
  await page.goto(`${BASE}/#/experiments`);
  const row = page.getByRole('row').filter({ hasText: EXPERIMENT_ID });
  await expect(row).toContainText('아직 실행 안 함');

  // 4. 화면이 알려 준 실행 명령을 그대로 돌린다
  const [, ...args] = command.split(/\s+/);
  if (process.env.CI) args.push('-PallowDuringSession');
  args.push('--console=plain');
  const run = spawnSync(GRADLEW, args, {
    cwd: REPO, env: { ...process.env, AUTOTRADE_CONFIG_DIR: CONFIG_DIR }, encoding: 'utf8',
    shell: process.platform === 'win32', timeout: 300_000,
  });
  test.info().attachments.push({ name: 'experiment-command.log', contentType: 'text/plain', body: Buffer.from(`${run.stdout}\n${run.stderr}`) });
  expect(run.status, `실행 명령 종료 코드(${run.error || ''})\n${run.stdout}\n${run.stderr}`).toBe(0);
  expect(fs.readFileSync(path.join(folder, 'results.md'), 'utf8')).toContain('같음(병렬 2 vs 1');

  // 5. 불러오기 → 실험 화면
  await page.reload();
  await expect(row).toContainText('불러올 수 있음');
  // 실행 명령의 진행 상태 파일이 "실행 중인 실험"에 끝난 작업으로 보인다(D-093)
  await page.getByRole('button', { name: /끝난 작업 \d+개 보기/ }).click();
  await expect(page.locator('section.jobs').getByRole('row').filter({ hasText: EXPERIMENT_ID })).toContainText('완료');
  await row.getByRole('button', { name: '불러오기' }).click();
  await expect(page).toHaveURL(new RegExp(`#/experiments/${EXPERIMENT_ID}$`));
  await expect(page.getByRole('heading', { level: 1, name: EXPERIMENT_ID })).toBeVisible();

  // 계좌 비교표: 같은 실험의 두 조합 계좌
  const board = page.locator('h2:has-text("계좌 비교") ~ div').first().locator('table');
  await expect(board.locator('tbody tr')).toHaveCount(2);
  await expect(board).toContainText('kr-vol-surge-quiet v1');
  await expect(board).toContainText('선정 없음');
  await expect(board).toContainText('kr-ma-trend v1');
  await expect(page.getByText(`${DAYS[0]} ~ ${DAYS[DAYS.length - 1]}`)).toBeVisible();
  await expect(page.getByText(SNAPSHOT, { exact: false }).first()).toBeVisible();
  // 교차표와 자산 곡선
  await expect(page.getByRole('heading', { name: '선정 × 매매지표 교차표' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '자산 곡선' })).toBeVisible();
  await expect(page.locator('svg').last()).toBeVisible();

  errors.expectNone();
});
