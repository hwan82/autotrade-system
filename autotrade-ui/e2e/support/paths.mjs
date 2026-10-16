// 시험이 쓰는 경로와 포트(D-101). 실험실 둘을 띄운다.
// - 흐름 실험실: 배포본(bootJar)을 저장소 루트에서 띄운다. strategies/·selections/·experiments/는 저장소 것을 읽고 쓰며,
//   설정 폴더는 시험용 임시 폴더(합성 일봉 스냅샷 하나)다.
// - 데모 실험실: bootTestRun(DemoLabApplication). 진행 상태 데모 작업(DEMO-20261015-RUN01, 워크포워드 DEMO-20261016-WF01)이 돈다.
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

export const REPO = path.resolve(here, '..', '..', '..');
/** 시험 작업 폴더. 시험 앞뒤로 통째로 지운다(E2E_WORK_DIR로 바꿀 때 다른 것이 든 폴더를 주지 않는다). */
export const WORK = process.env.E2E_WORK_DIR || path.join(os.tmpdir(), 'autotrade-ui-e2e');
export const CONFIG_DIR = path.join(WORK, 'config');
export const FLOW_PORT = Number(process.env.E2E_FLOW_PORT || 18481);
export const DEMO_PORT = Number(process.env.E2E_DEMO_PORT || 18482);
export const SNAPSHOT = 'krx-daily-e2e-0001';
/** 시험이 저장소 experiments/ 아래에 쓰는 등록 폴더. 시험 앞뒤로 이 폴더 하나만 지운다. */
export const EXPERIMENT_ID = 'EXP-20261016-E2E01';
export const GRADLEW = path.join(REPO, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
