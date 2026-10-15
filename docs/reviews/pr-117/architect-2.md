# architect 재검토: 실험 진행 상태 화면과 SSE(UI-02·UI-01, D-093)
- 대상: PR #117 / 브랜치 `claude/project-thread-4e7xy8` / 커밋 39b10370f1164b16a4559879f1851f4fa4417975(8d5604d 권고 반영 + origin/main 6fe3101 병합) / `git diff origin/main...HEAD` 전체(16파일, +865 −13)
- 점검표: B
- 1차 검토: [architect.md](architect.md)(c01c67d, 승인, 머지 전 main 충돌 해소 조건, 권고 2건)
- 직접 실행한 것:
  - `./gradlew build` (HEAD 39b1037): BUILD SUCCESSFUL, 종료 코드 0. 대부분 최신 상태로 건너뛰어(2초) 다시 `./gradlew build :autotrade-app-lab:test --rerun :autotrade-architecture-test:test --rerun`을 돌림: **BUILD SUCCESSFUL in 49s**, 21 tasks executed. 결과 파일 시각 2026-10-15T11:15Z(실행 시각 11:16Z 직전): `ExperimentJobControllerTest` 3/3, `ExperimentJobStreamShutdownTest` 1/1, `ExperimentJobStreamTest` 6/6, 실험실 시험 결과 33개 파일 모두 실패 0, 건너뜀 0.
  - `git merge-tree --write-tree origin/main HEAD`: 충돌 없음(CLEAN).
  - `git diff 6fe3101 39b1037`(main 대비 병합 결과): PR 자체 16파일만 다르다. 병합 해소에서 `docs/decisions/README.md`는 D-093 행 추가뿐(D-092 행 유지, 지운 줄 0), `autotrade-spec.md`는 버전 v14.35→v14.36, v14.36 이력 행 추가, UI-01·UI-02 행과 14장 이연 표 두 행 교체뿐. 병합으로 코드 파일이 PR 쪽에서 바뀐 것 없음.
  - `git diff origin/main...HEAD -- '*Test*.java'`에서 지운 줄 0줄. 바뀐 파일과 `scripts/ci/protected-paths.txt` 대조: 겹침 없음.
  - 1차 검토 원문 `architect.md`는 8d5604d에 그대로 커밋됨(판정 줄 포함, 32줄).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 HEAD 39b1037(main 병합 포함)에서 `./gradlew build`와 실험실·ArchUnit 시험 강제 재실행 모두 BUILD SUCCESSFUL |
| B2 | 통과 | 1차와 같음. 새 변경은 `ExperimentJobStream`(app-lab) 내부 메서드 분리와 시험 소스 `DemoLabApplication`(autotrade-app-lab/src/test/java/com/autotrade/app/lab/DemoLabApplication.java)뿐. 순수 모듈·ArchUnit 규칙 변경 없음, `autotrade-architecture-test:test` 재실행 통과 |
| B3 | 통과 | 1차와 같음(ADR-005:114 REST 계약 행, D-093, 결정 로그, 기획서 v14.36). 권고 반영 커밋은 계약을 바꾸지 않는다(SSE 이벤트·본문·주기 그대로). 병합 뒤 결정 로그·기획서 이력 번호(D-092·v14.35 main, D-093·v14.36 이 PR)가 이어진다 |
| B4 | 통과 | 운영 코드의 시각은 주입 `Clock`만(ExperimentJobStream.java `tick()`의 `clock.instant()`). `DemoLabApplication`의 `Instant.now()`는 시험 소스의 화면 데모 실행기(`bootTestRun`)로 배포본에 들어가지 않고 실험 결과에 쓰이지 않는다. 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 1차의 시험 8건 그대로 통과. 권고 반영으로 생긴 "작업 폴더 읽기 실패 때도 주석 줄 판정" 분기(ExperimentJobStream.java `tick()`의 catch에서 `keepalive(at)` 뒤 재던짐)에는 전용 시험이 없다 — 아래 발견 1(권고). 정상 경로의 keepalive는 `조용하면_주석_줄을_보낸다`가 지킨다. 지운·건너뛴 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·보호 테스트·보호 경로 변경 없음. main 병합으로 들어온 엔진·게이트웨이·CI 변경은 PR #116(이미 risk-ops·사용자 머지)의 것이며 이 PR의 diff(`origin/main...HEAD`)에 없다 |
| B8 | 통과 | 새 문자열·로그에 비밀값 없음. 데모 상태 파일의 `pid`는 데모 프로세스 자신의 값 |

## 1차 권고 반영 확인
- 권고 2(`.warn` 규칙 없음): jobs.js 두 곳과 experiments.js:71이 `sub warn-text`로 바뀜(8d5604d). `.warn-text`는 app.css:178에 있다. 해결.
- 권고 3(읽기 실패 때 keepalive 건너뜀): `tick()`이 `jobs.list()` 예외를 잡아 `keepalive(at)`를 부른 뒤 다시 던지고, `tickSafely`가 그 예외를 debug로 남긴다. `keepalive` 안의 `send`는 IOException·IllegalStateException을 잡아 연결을 빼므로 catch 안에서 새 예외가 나지 않는다[추론, 코드 근거]. 해결(시험은 발견 1).
- 추가 변경: 끝난 작업에는 "n개 진행 중"을 쓰지 않음(jobs.js `progress`, `j.state === 'RUNNING'` 조건). 화면 표시만 바뀌며 맞는 수정이다.

## 점검표 밖 발견
1. (권고) 읽기 실패 분기 시험: `ExperimentJobs`가 예외를 내도록(예: 작업 폴더 자리를 파일로 바꿔 두기) 만든 뒤 `KEEPALIVE`만큼 시계를 넘겨 `tick()`을 부르면 주석 줄이 가고 예외가 다시 던져지는지 확인하는 시험 1건을 후속으로 권한다. 동작 자체는 위 코드 확인으로 판단했고 차단 사유로 보지 않는다.
2. (참고) `DemoLabApplication`의 데모 스레드는 데몬이고 임시 폴더에만 쓰며 취소 요청 파일을 보고 `CANCELLED`로 끝난다. 시험 소스라 배포본(bootJar)에 들어가지 않는다. 화면 확인 경로가 생겨 D-093 "위험과 한계"의 수동 확인 근거가 된다.

## 판정
승인
