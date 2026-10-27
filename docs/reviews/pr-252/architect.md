# architect 검토: PR #252 4단계 판정 실험 EXP-20261027-WF01 사전 등록
- 대상: PR #252 / 커밋 1c89bab06e1cf094a3caa6d1ac0795378f068b1a / `git diff origin/main...claude/project-thread-pb3vv4` (origin/main 393ad55f 기준)
- 점검표: B
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD`: 3파일 +77줄. `experiments/EXP-20261027-WF01/study.yaml`(신규), `experiments/EXP-20261027-WF01/registration.md`(신규), `experiments/README.md`(표 1행 추가). Java·Gradle·스키마·설정 키·보호 경로 변경 없음. 브랜치 이력의 나머지 커밋(PR #137·#241·#250)은 이미 squash 머지되어 main 대비 diff에 나타나지 않는다.
  - `./gradlew build --no-daemon -q`: 종료 코드 0(전체 컴파일, 테스트, 순수 모듈 금지 의존 검사, ArchUnit).
  - `sha256sum experiments/EXP-20261027-WF01/study.yaml` = `c111aa6d70a1bc9169663eb8a77111e5df2dcf2a74862dde00de0e54d0f6ae7b`. registration.md에 적힌 값과 같다. 파일에 CR이 없어(`file`: UTF-8 text, CRLF 표시 없음) `WalkForwardStudy.studySha256`(autotrade-sim/.../walkforward/WalkForwardStudy.java:184, CRLF→LF 후 계산)이 내는 값과도 같다.
  - 빌드 산출 클래스패스로 `WalkForwardPlan.parse(study.yaml)`를 직접 호출: 예외 없이 읽힘. id=EXP-20261027-WF01, market=KRX, bars=krx-daily-2026-10-16-a6df4719, financials=dart-financials-2026-10-26-9134a847, disclosures=없음(선택 키, WalkForwardPlan.java:33·189), seed=20261013, seed-capital=10000000 KRW, parallelism=4(허용 1~64, WalkForwardPlan.java:109), first-test-from=2022-04-01, 3/1/4구간, warmup 300, null-seeds 400, 조합 10개, 대조군 SWING·POSITION 각 1.
  - 절차서 `docs/runbooks/stage-4-walkforward.md` 4장 등록 틀과 줄 단위로 대조: 자리표시자 4개(id, 두 스냅샷 ID, parallelism)만 다르고 question·시드·구간·조합 10개·대조군은 글자까지 같다.
  - 참조 프로필 존재 확인: `strategies/{kr-ma-trend/v1, kr-donchian-swing/v1, kr-short-reversal-swing/v2, kr-ma-trend-position/v1, kr-52w-high-position/v1, kr-random-daily-swing/v1, kr-random-daily-position/v1}.yaml`, `selections/{kr-vol-surge-quiet/v1, kr-fin-quality-growth/v1}.yaml` 모두 있음.
  - 스냅샷 보존 주장 확인: `SnapshotRetention.referencedIds`(autotrade-app-collector/.../SnapshotRetention.java:82-103)는 `experiments/` 아래 깊이 4까지 텍스트 파일에 스냅샷 ID 문자열이 나오면 지우지 않는다. study.yaml·registration.md 모두 두 ID를 담고 있어 PC에 pull된 뒤에는 `--keep-snapshots` 정리 대상에서 빠진다.
  - 실데이터 스냅샷은 이 환경에 없어 판정 명령은 돌리지 않았다(이 PR은 실행 전 사전 등록이라 실행이 대상 아님).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | `./gradlew build --no-daemon -q` 종료 코드 0(커밋 1c89bab0) |
| B2 | 해당 없음 | Java 코드·모듈·ArchUnit 규칙 변경 없음(diff 3파일 모두 `experiments/` 문서·등록 파일) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 등록 파일은 기존 계약(`WalkForwardPlan` 형식, WalkForwardPlan.java:23-52)을 그대로 따르고, 실제 파서로 읽힘을 확인 |
| B4 | 통과 | 시드 20261013 고정(study.yaml:6), 대조군 시드 수 400 고정. 실행 코드 변경 없음, 골든 테스트 포함 빌드 통과. parallelism은 결과에 영향 없고 `-PverifyDeterminism`으로 병렬 1과 비교(절차서 5장) |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음. seed-capital은 정수 문자열 "10000000"이고 파서가 KRW 금액으로 읽음 |
| B6 | 해당 없음 | 새 동작(코드) 없음. 테스트 삭제·건너뛰기 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응·보호 경로(`scripts/ci/protected-paths.txt`) 변경 없음 |
| B8 | 통과 | diff 전체를 읽음: API 키·계좌번호·토큰 없음. 들어 있는 64자리 16진수는 데이터·리포트 파일 SHA-256이며 비밀값 아님 |

## 점검표 밖 발견
- (참고, 수정 요구 아님) `parallelism: "4"`는 절차서 틀의 "PC 코어 수 - 2" 대신 실험실 기본 병렬 상한(D-094 결정 3의 기본 4)과 맞춘 값이다. 실제 동시 Run 수는 `min(상한, parallelism)`이므로 사용자 PC 코어가 6 미만이면 상한 쪽이 결정하고, 결과 바이트는 병렬 수와 무관하다(D-089·결정론 확인). 등록 후 이 값을 바꾸면 study-sha256이 달라져 새 실험 ID가 필요하므로, 코어 수가 많아 더 빨리 돌리고 싶어도 `-PmaxParallelRuns`로는 4를 넘길 수 없다는 점만 사용자 안내에 남겨 두면 좋다.
- (참고) 품질 리포트 두 개는 저장소에 없고 사용자 PC 미추적 파일이다. registration.md가 그 SHA-256을 남겼으므로 나중 대조는 가능하나, 리포트 내용 자체(빈 평일 판정, UNADJUSTED_JUMP 1,407건 등)는 이 검토에서 확인할 수 없었다. 이는 실험 해석(strategy-researcher) 범위이며 이 PR의 코드·계약 판정에는 영향이 없다.
- (참고) 스냅샷 보존은 이 PR이 main에 머지되고 사용자 PC가 `git pull`한 뒤부터 유효하다. 그 전에 수집기를 `--keep-snapshots`로 돌리면 `krx-daily-2026-10-16-a6df4719`가 정리될 수 있다(재무 스냅샷은 원래 정리하지 않음, CollectorMain.java:268). registration.md 입력 스냅샷 절에 "main에 들어가면"으로 적혀 있어 문서상 맞다.

## 판정
승인
