# architect 검토: 실험실: 실시간 세션 시작 원인 문구, 진행 SSE 읽기 실패 분기 시험
- 대상: PR #156 / 커밋 0377ac9 / `git diff origin/main...HEAD` 전체(5파일, +87 −4, 모두 `autotrade-app-lab`)
- 점검표: B
- 직접 실행한 것: `./gradlew build` 첫 두 번은 Maven Central 429(Too Many Requests)로 의존성 해석 실패(코드와 무관한 환경 문제), 세 번째에 `BUILD SUCCESSFUL in 3m 18s`(138 tasks). 새 시험 3개 결과 확인: `ExperimentJobStreamTest` 7/0 skipped/0 fail, `LiveLabServiceTest` 13/0/0, `LiveSessionControllerTest` 4/0/0. 전체 모듈 test-results에서 skipped>0인 suite 0개.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. 재시도 사이에 소스는 바꾸지 않음(커밋 0377ac9 그대로) |
| B2 | 통과 | 변경은 `autotrade-app-lab`(Spring 허용 모듈)의 기존 패키지 `com.autotrade.app.lab.experiment`·`.live` 안에서만. 새 import는 `java.util.function.Supplier`뿐. ArchUnit·`ModuleOwnershipTest`가 빌드에서 통과 |
| B3 | 통과 | 공개 계약 변화 없음. 공개 생성자 `ExperimentJobStream(ExperimentJobs, Clock)`은 그대로(ExperimentJobStream.java:54), 새 생성자는 package-private(:59)이고 운영 사용처는 `LabWebConfiguration.java:103`의 공개 생성자 하나. `POST /api/live/sessions/{id}/start`의 상태 코드는 전과 같이 `IllegalArgumentException` → 400 `INVALID`이며 ADR-005:120의 "등록 파일 형식 오류면 400 INVALID"에 들어감(오류 문구만 바뀜). 설정 키·스키마 변경 없음 |
| B4 | 통과 | 시간은 주입 `Clock`(`clock.instant()`, ExperimentJobStream.java:124)만 사용. diff에 `now()`·`Random`·`double` 없음. 시뮬레이션 모듈(`autotrade-sim`) 변경 없고 골든 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 시험: `LiveLabServiceTest#otherRegistrationKindIsRefusedWithTheCause`(조합·녹화 등록 파일은 새 문구, `input: live`인데 형식 틀리면 기존 줄 번호 문구 유지, 세션 미시작 확인), `LiveSessionControllerTest#comboRegistrationIsBadRequestNamingTheCause`(400 + 문구). 기존 분기 보강: `ExperimentJobStreamTest#작업_폴더를_못_읽어도_주석_줄은_보내고_예외는_다시_던진다`(주기 전 주석 0, 주기 도달 시 1, 같은 예외 재던짐, 떠난 연결 제거). 시험 삭제·`@Disabled`·assume 없음(diff 검색) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크·장애 대응 변경 없음. `scripts/ci/protected-paths.txt`의 경로에 걸리는 변경 파일 없음 |
| B8 | 통과 | 새 문구는 `experiments/<id>/experiment.yaml` 상대 경로와 실험 ID만 담음(LiveLabService.java:159). 비밀값·절대 경로 없음 |

## 점검표 밖 발견
- [사실] 사전 검사 기준은 `registrations()`의 필터와 같은 `LIVE_INPUT` 정규식·`strip` 줄 검사(LiveLabService.java:158 ↔ :413)라, 목록에 보이는 파일과 시작이 받는 파일의 기준이 어긋나지 않는다. 사전 검사를 통과했지만 `LivePlan.parse`가 거절하는 경우(예: 들여쓴 `input: live`)는 기존처럼 형식 검사 문구로 400이 되며, 그 반대(파서는 받는데 사전 검사가 막는 경우)는 목록에도 안 보이므로 화면 동작과 일관된다.
- [사실] 동작 변화: `input` 키가 없거나 `input: recording`인 파일은 전에는 `LivePlan.parse`의 "input은 \"live\"이어야 합니다…"(LivePlan.java:111-112) 또는 "모르는 키" 문구였고 이제는 "실시간 세션 등록 파일(input: live)이 아닙니다"로 통일된다. 상태 코드는 같고, 이 문구에 의존하는 UI·시험은 저장소 검색에서 없음.
- 권고(판정에 넣지 않음) R1: 같은 술어 `yaml.lines().map(String::strip).noneMatch(l -> LIVE_INPUT.matcher(l).matches())`가 두 곳(:158, :413)에 있다. `private static boolean isLiveRegistration(String yaml)`로 묶으면 다음에 기준을 바꿀 때 한쪽만 바뀌는 일을 막는다.
- 권고 R2: 시험용 package-private 생성자가 공개 생성자와 인자 수가 같아 `new ExperimentJobStream(null, clock)`은 모호해 컴파일되지 않는다. 지금 그렇게 부르는 곳은 없어 문제는 없음.
- [사실] 빌드 첫 두 번 실패는 저장소 원격 `repo.maven.apache.org`의 429 응답이었다(빌드 로그 "Received status code 429 from server: Too Many Requests"). 코드 문제가 아니다.

## 판정
승인
