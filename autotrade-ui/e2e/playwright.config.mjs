// 실험실 화면 브라우저 시험 설정(D-101, 5단계 QA 권고 R3). 먼저 저장소 루트에서
//   ./gradlew :autotrade-app-lab:bootJar :autotrade-app-lab:testClasses :autotrade-app-experiment:classes
// 로 빌드한 뒤 이 폴더에서 `npm ci && npx playwright test`로 돌린다. CI는 "Lab screens (Playwright)" 잡이 돌린다.
import fs from 'node:fs';
import path from 'node:path';
import { defineConfig, devices } from '@playwright/test';
import { CONFIG_DIR, DEMO_PORT, EXPERIMENT_ID, FLOW_PORT, GRADLEW, REPO, SNAPSHOT, WORK } from './support/paths.mjs';
import { writeSnapshot } from './support/snapshot.mjs';
import { refuseReason } from './support/guard.mjs';

function labJar() {
  const libs = path.join(REPO, 'autotrade-app-lab', 'build', 'libs');
  const jar = fs.existsSync(libs) && fs.readdirSync(libs).find(f => /^autotrade-app-lab-.*-SNAPSHOT\.jar$/.test(f));
  if (!jar) throw new Error(`실험실 bootJar가 없습니다(${libs}). 먼저 ./gradlew :autotrade-app-lab:bootJar를 돌리세요.`);
  return path.join(libs, jar);
}

// 설정 파일은 작업자 프로세스마다 다시 읽히므로 준비는 주 프로세스에서 한 번만 한다(실험실이 뜨기 전)
if (!process.env.TEST_WORKER_INDEX) {
  // 실전 엔진을 보호하는 확인을 무엇보다 먼저 한다(정지 요청, 장 시간 보호). 걸리면 아무것도 띄우지 않는다
  const reason = refuseReason();
  if (reason) throw new Error(`화면 시험을 시작하지 않습니다: ${reason}`);
  fs.rmSync(WORK, { recursive: true, force: true });
  writeSnapshot(CONFIG_DIR, SNAPSHOT);
  fs.rmSync(path.join(REPO, 'experiments', EXPERIMENT_ID), { recursive: true, force: true });
}

const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';

export default defineConfig({
  testDir: './tests',
  globalTeardown: './support/teardown.mjs',
  // 두 실험실이 상태를 가진다(등록 폴더, 데모 작업 취소). 파일 이름 순서대로 하나씩 돈다: 데모 작업(약 4분 반 뒤 끝남)이
  // 진행 중일 때 진행 상태 시험을 먼저 하고, 실행 명령을 기다리는 흐름 시험을 뒤에 한다
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 360_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    ...devices['Desktop Chrome'],
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      // 흐름 실험실: 배포본을 저장소 루트에서(기본 strategies/·selections/·experiments/), 설정 폴더는 시험용
      command: `"${java}" -jar "${labJar()}" --spring.main.banner-mode=off --server.port=${FLOW_PORT}`,
      cwd: REPO,
      env: { AUTOTRADE_CONFIG_DIR: CONFIG_DIR },
      url: `http://127.0.0.1:${FLOW_PORT}/api/session`,
      timeout: 120_000,
      reuseExistingServer: false,
      stdout: 'pipe',
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
    {
      // 데모 실험실(DemoLabApplication): 진행 상태 데모 작업이 5초마다 진행한다
      command: `"${GRADLEW}" --console=plain :autotrade-app-lab:bootTestRun --args=--server.port=${DEMO_PORT}`,
      cwd: REPO,
      url: `http://127.0.0.1:${DEMO_PORT}/api/session`,
      timeout: 300_000,
      reuseExistingServer: false,
      stdout: 'pipe',
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
  ],
});
