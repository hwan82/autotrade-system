# architect 검토: 실시간 화면에 장중 순위 선정·다음 거래일 준비 사유 표시, 실험 생성 화면 공시 스냅샷 칸 (PR #201)
- 대상: PR #201 / 브랜치 `claude/live-screen-selections-x4b84t` / 커밋 9b27a4cd3fa061c236ed88e49abba04d66ac628e / `git diff origin/main...HEAD` 전체(17개 파일, +361 −66)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`: 실패(build-logic의 kotlin-gradle-plugin 의존성이 캐시에 없음). `./gradlew build`(온라인): 실패, Maven Central `429 Too Many Requests`(repo.maven.apache.org). 코드 원인이 아닌 컨테이너 네트워크 문제다. 저장소 주소를 바꾸는 초기화 스크립트는 쓰지 않았다.
  - 사용자 결정에 따라 GitHub CI 결과를 B1 근거로 썼다: 워크플로 `CI` run 39442491140(event `pull_request`, head_sha 9b27a4c 일치) — `Gradle build` 잡 118695705870의 `Build and test`(`./gradlew build --no-daemon`) success, `Start each process once (ARC-03)` success; `Lab screens (Playwright)` success; `DB schema (PostgreSQL 16)` success; `Secret scan and collector boundary` success. (잡 로그 원문은 컨테이너에서 blob 저장소 접근이 막혀 내려받지 못했고, 단계별 결론만 API로 확인했다.)
  - 변경 파일을 `scripts/ci/protected-paths.txt`와 대조: 보호 경로 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 로컬 빌드는 Maven Central 429로 불가(코드 무관). 같은 커밋 CI `Gradle build` → `./gradlew build --no-daemon` success(run 39442491140, job 118695705870). ArchUnit·순수 모듈 금지 의존 검사 포함 |
| B2 | 통과 | 새 의존은 `autotrade-sim` → `com.autotrade.selection.calc.SessionSelectionCalculator`(LiveSession.java:25)뿐이고 sim은 이미 `api(project(":autotrade-selection"))`(autotrade-sim/build.gradle.kts:8). app-lab → sim 방향 그대로. Spring·SDK·DB 의존 추가 없음. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | REST 계약 변경 3건이 모두 ADR-005 표에 반영됨: `POST /api/experiment-folders` 요청에 `disclosuresSnapshot`과 400 조건(ADR-005 106행 부근), `GET /api/experiment-folders/snapshots?kind=dart-disclosures`, `GET /api/live/status`의 `session.intraday[].universe·sessionGap`, `dailySelections`, `nextDay`. 모두 추가형이라 기존 클라이언트는 깨지지 않음(빠진 `disclosuresSnapshot`은 null → 빈 값 처리, ExperimentRegistrations.java:167). D-123 8번·D-124 11번·D-127 후속·런북 갱신. 설정 키·DB 스키마 변경 없음 |
| B4 | 통과 | 새 시각 사용은 주입 `clock.instant()`뿐(LiveLabService.java:356). `IntradayStatus.universe/sessionGap`, `SessionSelections.prepared`는 화면용 읽기 값이라 결과·기록·해시에 들어가지 않음. 순회 순서는 선정 해시순 TreeMap(SessionSelections.java:76). 골든 테스트는 CI 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음(종목 수·상태 표시만) |
| B6 | 통과 | `SessionSelectionsTest#preparedShowsFilesOnDiskWithoutThrowing`(NONE·PROBLEM+inputs·깨진 사유 파일·READY·APPLIED·APPLIED_MISSING·깨진 적용 기록), `LiveLabServiceTest#selectionAccountsAreListedAfterProfilesAndPreparedOffTheEventThread`(nextDay 날짜·STALE_INPUT 사유), `#prepareNowNeedsSelectionAccounts`(선정 없으면 dailySelections false·nextDay 빈 값), `LiveIntradaySessionTest`(AWAITING 0/0, RANKED universe 2, 재기동 공백 2/2), `ExperimentRegistrationsTest` 3건(스냅샷 없음 거절·폴더 미생성, 고르면 `disclosures-snapshot` 기록 후 `CombinationPlan.parse` 왕복, 공시 선정 없는데 고르면 거절). 지워진 테스트 `공시_지표_선정_프로필은_화면_등록을_거절하고_폴더를_만들지_않는다`는 의도된 동작 변경(D-123 후속)으로 거절 경로가 새 테스트로 대체됨 — 약화 아님. `@Disabled`·assume 없음. 화면 JS 변경은 자동 시험이 없음(아래 N2) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 판정·리스크 한도·장애 대응·증권사 주문 연동 변경 없음. 보호 경로 변경 없음. 블랙리스트는 화면 표시(`blacklisted` 목록)만 바뀌고 판정 코드는 그대로 |
| B8 | 통과 | 비밀값 없음, CI gitleaks success. 새 로그 없음. 화면에 나가는 `detail`·`inputs`는 기존 사유 파일 내용(스냅샷 ID 키)이며 절대 경로·키를 담지 않음 |

## 점검표 밖 발견
- **N1(낮음, 권고)** `LiveSession.selectionFiles()`(LiveSession.java:423)가 세션의 `SessionSelections` 인스턴스 자체를 공개한다. 이 객체에는 `apply`·`writeResult`·`writeProblem` 같은 쓰기 메서드가 있는데 세션 잠금 없이 app-lab에 넘어가고, "읽기만"은 주석 약속뿐이다. 지금 호출처(LiveLabService.nextDay → `prepared`)는 읽기만 하고, 적용 기록은 하드 링크로 한 번만 놓이며(`writeAtomic(..., false)`) `appliedCache`는 ConcurrentHashMap이라 경쟁 시에도 화면 값이 잠깐 늦을 뿐 기록이 어긋나지는 않는다. 다음에 이 영역을 고칠 때 `LiveSession.prepared(LocalDate)`처럼 읽기 전용 결과만 돌려주는 메서드로 좁히기를 권한다. 차단 사유 아님.
- **N2(낮음, 권고)** `live.js`의 새 표(장중 순위 선정, 다음 거래일 파일 상태)와 `new-experiment.js`의 공시 스냅샷 칸은 Playwright 시험이 없다(e2e는 `#/experiments/new` 흐름만 있고 공시 칸 단정 없음, `#/live` 없음). `SnapshotList`도 종류별 시험이 원래 없어 `dart-disclosures` 목록이 시험되지 않는다. 서버 데이터는 Java 단위 시험으로 덮였으므로 차단 사유 아님. ui-dev 후속으로 `SnapshotList` 단위 시험(세 종류, manifest 깨짐) 정도는 더할 것을 권한다.
- **N3(낮음, 참고)** `LiveLabService.status()`는 서비스 모니터를 잡은 채 `nextDay`에서 파일 존재 확인과 작은 JSON 읽기를 한다(화면이 3초마다 부름). 선정 수만큼의 작은 파일 IO라 실험실 프로세스 안에서는 문제가 될 규모가 아니고, 이벤트 처리 스레드는 이 모니터를 잡지 않는다(정지는 `stopFromPump`가 별도 스레드). 캘린더를 못 읽으면 `r.calendar`가 null로 남아 매 호출 다시 읽는 점만 참고. 실전 엔진과는 프로세스가 달라 실전 영향 없음.
- 확인한 것(문제 없음): 공시 스냅샷 ID는 등록 파일 왕복 해석(`CombinationPlan.snapshot` 형식 정규식)으로 검증돼 경로 문자를 넣을 수 없다. "공시 선정 없는데 스냅샷 있으면 거절"은 `SelectionEdgeStudy`(SelectionEdgeStudy.java:148)와 같은 규칙. `nextDay`의 대상일은 준비 스레드와 같은 `LiveSelectionPrep.targetDate`·같은 기간 검사(LiveLabService.java:356 ↔ prepareOnce)를 쓴다. `selectionFiles()`는 장전 선정 프로필만 담으므로(LiveSetup.java:49) 장중 순위만 있는 세션에서는 `nextDay`가 비어 있다. `sessionGap`은 `SessionSelectionCalculator.SESSION_GAP` 사유를 가진 평가 수이고 `universe`는 boards 종목 수(`codes.size()`)라 화면 "n/n" 표시와 맞는다.

## 판정
승인
