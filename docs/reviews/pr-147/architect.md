# architect 검토: 주간 점검 4장: 6단계 계좌 무관 표시, 지난 주 재점검 표시·시험, 옛 manifest 따로 적기(PR #145 권고 R2·R3·N1)
- 대상: PR #147 / 브랜치 `claude/project-thread-ca9bpm` / 커밋 e8840ac(코드), e381663(strategy-researcher 검토 원문, 검토 중 추가됨) / `git diff origin/main...HEAD` 전체(5파일: `CollectionFreshness.java`, `CollectionFreshnessTest.java`, `D-105-...md`, `stage-6-live-session.md`, `docs/reviews/pr-147/strategy-researcher.md`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`: 실패. 원인은 코드가 아니라 캐시에 없는 의존(`oshi-core:6.12.0`).
  - `./gradlew build`: 1회차 실패. Maven Central **429 Too Many Requests**(`archunit-junit5-engine-api-1.4.1.jar` 다운로드, `:autotrade-architecture-test:test` 의존 해석 단계, 컴파일·시험 실패 아님).
  - `./gradlew build --continue` 재시도: **BUILD SUCCESSFUL in 2m 16s**. 시험 결과 XML 확인: `autotrade-architecture-test` 46건 실패 0·건너뜀 0, `autotrade-sim` 288건 실패 0·건너뜀 0, `CollectionFreshnessTest` 10건 통과, `LiveWeeklyCheckTest` 8건 통과.
  - 빌드 도중 원격 브랜치에 e381663(검토 원문 문서만)이 추가됐다. 코드·빌드 입력은 바뀌지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build --continue` BUILD SUCCESSFUL(첫 시도는 Maven 429로 의존 다운로드 실패, 재시도 통과). architecture-test 46/0 실패 |
| B2 | 통과 | 바뀐 운영 클래스는 `autotrade-sim/.../sim/live/CollectionFreshness.java` 하나, import 추가 없음(기존 `SnapshotFiles`·`SnapshotManifest`는 이미 쓰던 것). ArchUnit·ModuleOwnershipTest 통과. 보호 경로(`scripts/ci/protected-paths.txt`) 해당 파일 없음 |
| B3 | 통과 | `CollectionFreshness.Status`는 public record라 구성 요소 2개(`noCoverage`, `newerThanEnd`)가 늘었지만 생성자 호출은 같은 클래스 안 3곳뿐(CollectionFreshness.java:97,144,161), 모듈 간 계약·스키마·설정 키·REST 아님. manifest 스키마는 읽기만 하고 바꾸지 않음(`coverage`가 선택 칸인 것은 SnapshotManifest.java 주석·ADR-008 그대로). D-105에 후속 절로 기록, runbook 갱신. 새 ADR 불필요 |
| B4 | 통과 | 시계·난수 사용 없음. `newerThanEnd`는 `best.isAfter(end)`(CollectionFreshness.java:161)로 입력만으로 결정. 같은 마지막 거래일 동률 처리(ID 비교)는 그대로. 폴더 순회 순서와 무관하게 같은 보고서인지 보던 기존 시험 통과. 시뮬레이션·골든 경로 미변경, sim 288건 통과 |
| B5 | 해당 없음 | 금액 계산 없음(날짜·목록 표시만) |
| B6 | 통과 | 새 동작마다 시험: `recheckingAnEarlierWeekSeesNewerSnapshotsAsNormal`(newerThanEnd 참·거짓과 4장 문구), `oldManifestWithoutCoverageIsListedApartFromBrokenOnes`(coverage 없음 → noCoverage, unreadable 아님, NO_SNAPSHOT 유지, 정상 스냅샷과 함께면 OK), `sectionSaysTheLiveAccountsDoNotReadDailySnapshots`. 기존 7건은 지우거나 건너뛰지 않음(10건 모두 실행, skipped 0) |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응·보호 경로 미변경. 주간 점검(실험실 쪽 오프라인 보고서) 표시 변경뿐 |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음. 시험 manifest는 합성 ID·빈 sources |

## 점검표 밖 발견
- [사실] 4장 새 문구 "6단계 실시간 모의 계좌는 일봉 스냅샷을 입력으로 쓰지 않으므로(선정 프로필 미연결)"는 코드와 맞다: `LivePlan.java:46` "선정 프로필은 받지 않는다(녹화 실험과 같음, 후속)". 다만 선정 프로필이 `LivePlan`에 연결되는 후속 PR에서는 이 문구가 거짓이 된다. 그 PR이 이 문구와 시험 `sectionSaysTheLiveAccountsDoNotReadDailySnapshots`를 함께 고쳐야 한다(시험이 문구를 고정하고 있어 놓칠 가능성은 낮지만, 시험은 문구를 지키는 쪽이지 사실을 확인하는 쪽이 아니다). 낮음, 후속 메모.
- [사실] PR #145 architect N1 처리 확인: `coverage == null || lastTradingDate == null`이면 `noCoverage`(CollectionFreshness.java:125-128), 날짜 파싱 실패·`snapshotId` 없음은 여전히 `unreadable`(129-135). 분기 순서가 맞다. `coverage`는 있는데 `lastTradingDate`만 없는 경우(재무 스냅샷 형식이 잘못 섞인 경우 등)는 시험이 없다 `[추론]` 같은 조건식의 다른 쪽이라 위험은 낮다. 선택 보강.
- [사실] `newerThanEnd`일 때 머리·콘솔 한 줄(`summary()`, CollectionFreshness.java:72-80)은 "정상(일봉 스냅샷 마지막 거래일 …)"만 나오고 "지난 주 멈춤은 안 보임" 표시는 4장에만 있다. 마지막 거래일 날짜가 점검 끝 날짜보다 뒤라 읽는 사람이 알아챌 수는 있다. strategy-researcher P2(옛 형식만 있을 때 머리 문구)와 같은 계열의 선택 권고.
- [사실] PR #145 architect N2는 "문서로 처리됨"이었는데 이 PR이 표시까지 더했다. 판정 논리(OK/BEHIND/NO_SNAPSHOT, `warn()`, 종료 코드)는 바뀌지 않았다(CollectionFreshness.java:66-69, 160).
- 결함 아님: `Status` 압축 생성자가 `noCoverage`를 `List.copyOf`로 불변화(널 거부)한다. 호출부 3곳 모두 널이 아닌 목록을 넘긴다.

## 판정
승인
