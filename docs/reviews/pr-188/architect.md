# architect 검토: 실시간 모의 계좌별 체결·거절 내역 화면(D-120)
- 대상: PR #188 / 브랜치 `claude/project-thread-w0jyik` / 커밋 049c4e855be6cd56ad07e68c1d627aeb28fb35d2 / `git diff origin/main...HEAD` 전체(15파일, +338 −49)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL (같은 HEAD, 작업 트리 깨끗)
  - `./gradlew :autotrade-sim:test :autotrade-app-lab:test :autotrade-architecture-test:test --rerun`, 이어서 `:autotrade-app-lab:test --rerun` → 모두 성공. 결과 XML 집계: sim 355건·app-lab 249건·architecture-test 46건, 실패·오류·건너뜀 0
  - 새 테스트 3개 통과 확인: `LiveSessionTest#activityShowsEachAccountsFillsAndRejectionsWhileRunningAndAcrossRestart`, `LiveSessionControllerTest#accountActivityIsNotFoundWithoutARunningSession`, `LiveSessionControllerTest#accountActivityOfTheRunningSession`
  - `node --check` app.js·pages/live.js·pages/experiments.js → 문법 오류 없음
  - 바뀐 파일과 `scripts/ci/protected-paths.txt` 대조 → 보호 경로 변경 없음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 관련 모듈 테스트 강제 재실행 성공 |
| B2 | 통과 | sim 변경은 순수 Java(`AppliedFill`·`OrderRejection` 사본만, LiveSession.java:375-391, RunStepper.java:199-206). Spring 연결은 app-lab에만(LabWebConfiguration.java:43-56 `SymbolNames` 빈 분리, LiveSessionController.java 생성자 주입). app-lab `live` → `experiment.ExperimentViews` 참조는 같은 모듈 안. ArchitectureTest·ModuleOwnershipTest 통과. 규칙 변경 없음 |
| B3 | **실패** | 새 REST 계약 `GET /api/live/runs/{runId}/fills?page&size`, `GET /api/live/runs/{runId}/rejections?stage&code&page&size`(LiveSessionController.java:62-79)가 결정 문서(D-120 결정 3번)에만 있고, REST 계약 원본인 ADR-005 5장 API 표(docs/adr/ADR-005-ui-dashboard.md:117-122, `/api/live/*` 행들)와 ADR-010 2.4 엔드포인트 목록(docs/adr/ADR-010-live-feed-and-session.md:49 "`GET /status`, `GET /registrations`, `GET /gaps`, …")에 없다. 기존 `/api/live/gaps`는 ADR-005 표에 응답·오류(400/404)까지 적혀 있으므로 같은 수준으로 두 행을 더해야 한다(최근 것부터 정렬, 404 조건 3가지, size 1~200 → 400 `INVALID`, 행 모양은 실험 계좌 `fills`·`rejections`와 같음, 세션 잠금 안 사본). 설정 키·DB 스키마 변경 없음. 기획서 v14.56·결정 색인 D-120 행은 있음(docs/spec/autotrade-spec.md 변경 이력, docs/decisions/README.md:125) |
| B4 | 통과 | 사본 메서드는 결과·보고서·입력 기록에 쓰지 않음(RunStepper.java:199-206 읽기만, `List.copyOf`). 시스템 시계·난수 추가 없음. `LiveSessionTest` 새 테스트가 도는 중 사본 = 재기동 뒤 사본 = `finish` 보고서의 fills·rejections 일치를 확인. 기존 리플레이 일치·골든 테스트 포함 sim 355건 통과. 사유별 건수 정렬은 건수 내림차순→stage→code로 명시(ExperimentViews.java:257-268, 기존 `InMemoryExperimentCatalog.counts`를 그대로 옮김) |
| B5 | 통과 | 새 금액 계산 없음. 행 변환은 기존 `FillRow.of`(BigDecimal)를 그대로 씀 |
| B6 | 통과(권고 있음) | 새 동작마다 테스트가 있고 지운·끈 테스트 없음(diff에 테스트 삭제 없음). 다만 컨트롤러 테스트는 체결 0건 세션만 다뤄 API 계약의 "최근 것부터"(`reversed()`, LiveSessionController.java:66·78)와 종목명 채움이 테스트로 고정되지 않았다 → 아래 R1 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 테스트 무변경. 모의 계좌 메모리 읽기 전용 GET뿐. risk-ops 검토 불필요 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 404 메시지는 고정 문구이고 경로·runId 외 값 없음(LiveSessionController.java:81-84) |

## 점검표 밖 발견
- R1(권고, 차단 아님): `LiveSessionControllerTest`에 체결·거절이 있는 계좌로 첫 행이 가장 최근 체결인지, `stage`·`code` 거르기 뒤에도 `counts`가 전체인지 확인하는 단언을 더하면 B3에 적을 계약이 테스트로 고정된다(sim 쪽 `LiveSessionTest`는 오래된 것부터 순서만 확인).
- R2(권고): 최근 것부터 쪽 번호를 쓰므로 세션이 도는 중 새 체결이 들어오면 다음 쪽 요청에서 행이 한 칸씩 밀려 중복·누락처럼 보일 수 있다. 화면은 자동 갱신을 끄고 "새로 읽기"로 한 번에 다시 읽게 했지만(live.js 계좌 상세 주석) 쪽 넘김 사이 변동은 남는다. ADR-005 행에 이 한계를 적거나 후속으로 `beforeSeq` 커서를 검토할 것.
- 확인한 것: `LiveLabService.activity`는 `gaps`와 같은 잠금 순서(서비스 → 세션)이고 `running.session` 대입도 서비스 잠금 안이라(LiveLabService.java:203-208) 경합 없음. `VirtualAccount.ledger()`가 이미 사본을 돌려주므로 `fillsSnapshot`의 `List.copyOf`는 이중 복사지만 동작상 문제 없음. `SymbolNames` 빈은 하나뿐이고 `@SpringBootTest` 컨텍스트 테스트들이 통과해 배선이 확인됨. import 순서(LiveSessionController.java:5-6, RunStepper.java:24)가 알파벳순이 아닌 것은 사소함.

## 판정
수정 필요(B3: ADR-005 5장 API 표와 ADR-010 2.4 엔드포인트 목록에 `GET /api/live/runs/{runId}/fills`·`/rejections` 추가)
