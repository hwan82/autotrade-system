# architect 검토: #/live 마지막 준비 줄에 공시 스냅샷, 선정 파일 읽기 전용으로 좁힘, 화면·SnapshotList 시험 (PR #207)
- 대상: PR #207 / 브랜치 `claude/project-thread-xn3x5o` / 커밋 a173ab2cdce4dca22675cf12ff648988a9f0e07a / `git diff origin/main...HEAD` 전체(7개 파일, +281 −8)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: BUILD SUCCESSFUL(138 tasks, 종료 코드 0). 컴파일·테스트·순수 모듈 금지 의존 검사·ArchUnit 포함.
  - 캐시 결과에 기대지 않으려고 `./gradlew :autotrade-sim:cleanTest :autotrade-sim:test :autotrade-app-lab:cleanTest :autotrade-app-lab:test :autotrade-architecture-test:cleanTest :autotrade-architecture-test:test` 재실행: BUILD SUCCESSFUL. 결과 XML 집계 — autotrade-sim 442건, autotrade-app-lab 264건, autotrade-architecture-test 46건, 건너뜀·실패·오류 모두 0. 이 중 `SnapshotListTest` 6건, `LiveLabServiceTest` 16건, `LiveSelectionSessionTest` 11건 통과.
  - `autotrade-ui/e2e`에서 `npx playwright test tests/04-live-selections.spec.mjs`: 3건 모두 통과(공시 스냅샷 표시, 공시 없음·다른 날 준비 안내, 실험 생성 화면 `#xdisc` 목록). 이 스펙은 CI `ui-e2e` 잡의 `npx playwright test`(testDir `./tests`)에 자동으로 들어간다.
  - 변경 파일을 `scripts/ci/protected-paths.txt`와 대조: 보호 경로 변경 없음(autotrade-sim, autotrade-app-lab, autotrade-ui만).
  - diff에서 키·토큰·계좌번호 모양 문자열 검색: 없음(gitleaks는 컨테이너에 없어 직접 돌리지 못함, CI 단계가 따로 있음).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, BUILD SUCCESSFUL. 변경 모듈 테스트는 cleanTest 후 재실행해 실패 0 |
| B2 | 통과 | 새 의존 없음. `LiveSession`(autotrade-sim)의 공개 메서드가 `Optional<SessionSelections> selectionFiles()` → `Optional<List<SessionSelections.Prepared>> preparedSelections(LocalDate)`(LiveSession.java:427)로 바뀌었고 호출처는 app-lab `LiveLabService.nextDay`(LiveLabService.java:359) 하나뿐(grep 확인). app-lab → sim 방향 그대로. 테스트의 Jackson 사용은 app-lab 테스트 클래스패스 안이다. ArchUnit 규칙·소유 표 변경 없음 |
| B3 | 통과 | REST·스키마·설정 키 변경 없음. `prepare.disclosuresSnapshot`, `nextDay` 등은 이미 main의 응답 필드이고 이번 PR은 화면(live.js:213 `prepInputs`)이 그 값을 보이도록만 했다. 바뀐 Java 메서드는 sim↔app-lab 내부 API로 ADR 대상 계약이 아니며, ADR·문서에 `selectionFiles()`를 적은 곳은 없다(검토 원문 pr-201 제외) |
| B4 | 통과 | 새 시각·난수 사용 없음. `preparedSelections`는 화면용 읽기 결과라 결과·기록·해시에 들어가지 않고, 순서는 기존 `SessionSelections.prepared`(SessionSelections.java:380)의 선정 해시순 그대로. sim 골든 테스트 포함 autotrade-sim 442건 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 테스트가 있다: 읽기 전용 결과 — `LiveSelectionSessionTest`에 READY/NONE(적용 전), APPLIED/APPLIED_MISSING(적용 후), 선정 없는 세션은 빈 값(LiveSelectionSessionTest.java:114-117, 139-142, 180). 화면 JSON 이름 — `LiveLabServiceTest#assertScreenFields`. SnapshotList — 세 종류 기간, 깨진 manifest, 건너뛸 폴더, 모르는 종류·경로 문자 거절(`SnapshotListTest` 6건). 화면 — e2e 04 3건. 지우거나 건너뛴 테스트 없음(`@Disabled`·삭제 없음) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동 변경 없음. 보호 경로 변경 없음 |
| B8 | 통과 | diff에 비밀값 없음. 테스트 자료는 가짜 스냅샷 ID·실험 ID뿐 |

## 점검표 밖 발견
- **N1(정보, 차단 아님)** PR #201 architect N1 해소 확인: `SessionSelections`(쓰기 메서드 `apply`·`writeResult`·`writeProblem` 보유) 인스턴스가 더 이상 세션 밖으로 나가지 않는다. 돌려주는 `Prepared`는 String·enum 필드만 가진 record(SessionSelections.java:418)이고 목록은 호출마다 새로 만든다. `nextDay`의 `hasSelections()` 검사(LiveSession.java:416, `selections != null`)는 예전 `selectionFiles().isEmpty()`와 같은 조건이고, `prepared` 호출은 예전처럼 `try { … } catch (RuntimeException)` 안에 있어 동작이 같다.
- **N2(낮음, 권고)** `LiveLabServiceTest#assertScreenFields`는 테스트 안에서 직접 만든 `JsonMapper`(`findAndAddModules`, `WRITE_DATES_AS_TIMESTAMPS` 끔)로 `View`를 직렬화한다. 실제 응답은 Spring Boot가 구성한 `ObjectMapper`가 만든다. 지금은 Boot 기본값(JSR-310·Jdk8 모듈, 날짜 ISO 문자열)과 같아 필드 이름 검사로는 충분하지만, 나중에 app-lab에서 Jackson 설정(이름 전략, Optional 처리 등)을 바꾸면 이 테스트는 그 차이를 잡지 못한다. 다음에 이 영역을 고칠 때 Spring 컨텍스트의 `ObjectMapper`나 `/api/live/status` MockMvc 응답으로 확인하기를 권한다. 차단 사유 아님.
- **N3(정보)** `assertScreenFields`는 `prepare`의 `dailySnapshot·financialsSnapshot·disclosuresSnapshot`을 `has(key)`로만 본다. 이 시험 상황에서 값이 null이어도 키는 있으므로 이름 검사 목적에는 맞다. 값 표시는 e2e 04가 가짜 응답으로 확인한다.

## 판정
승인
