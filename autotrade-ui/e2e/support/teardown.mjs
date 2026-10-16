// 시험이 저장소 experiments/ 아래에 만든 등록 폴더 하나와 임시 설정 폴더를 지운다(다른 실험 폴더는 건드리지 않는다).
import fs from 'node:fs';
import path from 'node:path';
import { EXPERIMENT_ID, REPO, WORK } from './paths.mjs';

export default async function teardown() {
  fs.rmSync(path.join(REPO, 'experiments', EXPERIMENT_ID), { recursive: true, force: true });
  fs.rmSync(WORK, { recursive: true, force: true });
}
