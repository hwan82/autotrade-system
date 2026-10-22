# architect 검토: D-136 후속 고침(PR #222 QA Q1~Q4·R2·R3, risk-ops 재검토 R1·R2)
- 대상: PR #225 / 커밋 5b7fa65a7c75b93f33b18ff36ccd10fa10547ed1 / main(0f1e562) 대비 diff (`git diff origin/main...HEAD`: 18개 파일, +319, −39, 커밋 7505c958·5b7fa65a)
  - 운영 코드(`autotrade-app-collector`): `ApiCaller.getAnswered`(새 공개 메서드, `get`은 이 메서드로 위임), `KisNewsClient`(결과 코드 먼저 `answered`, 성공 코드+비 200 거절, 시각 6자리·실제 값 검사, `badRows`), `KisNewsCollector.walk`(시작 커서 자정 이동, 멈출 시각 같은 초 재확인, `crowdedSeconds` 조건 변경, `midnightCuts`), `CollectorMain.redaction`(추출), `CollectorReports.writeNews`(열·줄 추가)
  - 시험: `ApiCallerTest` +3, `KisNewsClientTest` +5(기존 1건 기대값 "930100"→"093010"), `KisNewsCollectorTest` +6(기존 1건 기대값 235959 호출 7→8, `000000` 미전송·`midnightCuts` 단언 추가), `KisNewsFixtures`(커서 000000을 그날 끝으로 흉내)
  - 보호 경로·CI: `protected-paths.txt`·CODEOWNERS에 `CollectorSecrets.java`·`CollectorMain.java`, `check-collector-keys.sh`에 `app-key`·`app-secret` 패턴
  - 문서: D-136 "후속 고침" 절, D-054 R1 예외 연결 줄, 결정 로그 README D-136 행, runbook collector 3.9, qa-out-status, qa/pending
- 점검표: B
- 직접 실행한 것(공유 작업 트리와 겹치지 않게 `git worktree add --detach`로 5b7fa65a를 두 벌 꺼냄: 빌드용 1, 변이·탐침용 1. 끝나고 `git checkout`으로 원복, `git status` 깨끗함 확인):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**, 138 tasks 모두 executed(새 worktree라 test 작업 UP-TO-DATE·FROM-CACHE 0건), 종료 0. 결과 XML 합계 1,928건, 실패 0, 오류 0, 건너뜀 0. `KisNewsCollectorTest` 15, `KisNewsClientTest` 12, `KisNewsCommandTest` 6, `ApiCallerTest` 6 통과
  - 변이 시험(`:autotrade-app-collector:test --tests 'com.autotrade.app.collector.*'`, 하나씩 넣고 원복):

| 변이 | 내용 | 결과 |
|---|---|---|
| MA | `ApiCaller` `|| answered.test(r)` 제거(결과 코드 있는 5xx도 재시도) | 죽음 |
| MB | `KisNewsClient.getNews` 성공 코드+비 200 거절 제거 | 죽음 |
| MC | `headline` `LocalTime.parse` 검사 제거 | 죽음 |
| MD | `badRows++` 제거 | 죽음 |
| ME | 완료 조건 `pageOldest.isBefore(stopAt)` → 옛 `!isAfter` | 죽음 |
| MF | `next.isBefore(stopAt)` 조기 완료 블록 제거 | **생존**(다음 쪽 한 번 더 받고 같은 결과로 끝남: 호출 1건 차이뿐인 사실상 동등 변이) |
| MG | `midnightCuts++` 제거 | 죽음 |
| MH | 걸음 시작 커서 자정 → 23:59:59 이동 제거 | 죽음 |
| MI | `crowded` 조건 `next.isBefore(first)` → `added == 0`(옛 조건) | 죽음 |
| MJ | `CollectorMain.redaction`에서 `kisNewsAppSecret` 제거 | 죽음 |
| MK | `KisNewsClient.answered`의 `rt_cd` 비지 않음 조건 제거(아무 JSON 객체면 답한 것으로) | **생존** |
| ML | 품질 리포트 "날짜·시각을 읽을 수 없어 거른 행" 줄 제거 | **생존** |

  - 탐침(시험 파일에 임시 시험 추가 후 원복): `data_tm` "240000" 행 → 아래 점검표 밖 1
  - `bash scripts/ci/check-collector-keys.sh` → 종료 0(저장소에 걸리는 값 없음). 새 패턴을 표본 4줄에 직접 적용: 평문 JSON `"app-key": "<36자>"`·Java 이스케이프 `app-secret\": \"<base64>\"`는 잡고, `<KIS_APP_KEY>` 자리표시자·16자 시험 값 `KISKEYCCCCCCCCCC`는 통과
  - `bash scripts/ci/test-mark-protected-changes.sh` → 자체 시험 통과. `bash scripts/ci/mark-protected-changes.sh main` → 보호 대상 5개 표시(CODEOWNERS, `ApiCaller`, `CollectorMain`, `KisNewsClient`, `protected-paths.txt`)
  - CI(헤드 5b7fa65a, `gh api .../check-runs`): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 모두 success

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew build` 직접 실행, 138 tasks 새로 실행, 1,928건 전부 통과 |
| B2 | 통과 | 빌드 스크립트·의존성·ArchUnit 규칙 변경 없음(diff에 `build.gradle*`·`autotrade-architecture-test` 없음). 바뀐 코드는 모두 `autotrade-app-collector` 안(`com.autotrade.app.collector`)이고 새 import는 `java.time`·`java.util.function`뿐. `verifyProcessIsolation`·`verifyPureDependencies`·ArchUnit 전체 빌드에서 통과 |
| B3 | 통과 | 프로세스 간 계약·DB 스키마·설정 키·REST 무변경. `ApiCaller.getAnswered`는 수집기 모듈 안 호출기 API 추가(기존 `get` 동작은 `r -> false` 위임으로 그대로, `ApiCallerTest` 기존 시험 통과). 품질 리포트 새 열 "자정 확인 못 함"·새 줄 "거른 행"은 runbook collector.md 3.9 ③④에 반영. 동작 변경 근거는 D-136 "후속 고침" 1~7번에 있고 코드와 일치(단 2번 문장 "실제 값이 아니면 거른다"는 점검표 밖 1의 예외가 있음). ADR 필요 없음 |
| B4 | 통과 | 시뮬레이션 코드 무변경. 수집기는 계속 주입 `Clock`만 씀(`KisNewsCollector.collect` 97행). 골든 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음(건수·시각·문자열) |
| B6 | 통과 | 새 동작마다 시험이 있고 변이 MA~ME·MG~MJ가 죽음. 기존 시험 2건의 기대값 변경은 동작 변경에 따른 것이고 약화가 아님(`KisNewsClientTest` 시각 기대값은 있지도 않은 93시에서 실제 시각으로, `KisNewsCollectorTest` 235959 호출 수는 시작 커서 이동으로 1건 늘었고 `000000` 미전송·`midnightCuts` 단언이 더해짐). 삭제·`@Disabled`·건너뜀 없음(건너뜀 0). 생존 MF·MK·ML은 점검표 밖 2·3 |
| B7 | 해당 없음(단, 보호 경로) | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트·리스크 한도·`autotrade-broker-*` 변경 없음. 그러나 보호 경로 `ApiCaller`·`KisNewsClient`·`CollectorMain`·`protected-paths.txt`·CODEOWNERS를 바꾸고 증권사(한국투자) 응답 처리·재시도 규칙을 바꾸므로 지침 11장상 실전 관련 PR이다. **risk-ops 검토 필요**, 머지는 독립 QA 뒤 사용자 |
| B8 | 통과 | diff에 실제 키·계좌번호·토큰 없음(시험 값은 `KRXKEYAAAAAAAAAA` 같은 16자 가짜 값, CI 키 검사 24자 하한 아래). 새 오류 메시지는 결과 코드·HTTP 상태만(`KisRejected("(HTTP " + status + ")")`, KisNewsClient.java:189), 응답 본문은 싣지 않음(`토큰_응답에_access_token이_없으면_본문을_메시지에_싣지_않는다`). 가림 목록 네 키는 `수집기가_읽은_키는_모두_가린다`가 고정(MJ 죽음). gitleaks는 로컬 미실행, CI "Secret scan and collector boundary" success |

## 점검표 밖 발견
1. **(수정 필요) `data_tm` "240000"이 걸러지지 않고 그날 00:00:00으로 읽혀, 구간을 끝까지 훑은 것으로 잘못 기록한다.** `KisNewsClient.headline`(KisNewsClient.java:163)은 `DateTimeFormatter.ofPattern("HHmmss")`로 검사하는데 이 형식기의 기본 해석은 SMART라 "240000"을 예외 없이 `00:00`으로 받는다(직접 확인: `LocalTime.parse("240000", ofPattern("HHmmss"))` → `00:00`, "240001"·"235960"은 예외). `KisNewsCollector.at`(261행, 같은 SMART 형식기 `HMS`)도 그 행을 **같은 날 00:00:00**으로 본다. 탐침: 07:00 첫 실행 뒤 같은 날 10:00~17:00 기사 8건과 `data_tm` "240000" 행 1건을 더하고 18:00에 실행(쪽 4건) → 첫 쪽(X24, 17:00, 16:00, 15:00)의 가장 옛 시각이 00:00으로 잡혀 멈출 시각(06:50)보다 앞이므로 `complete=true`, 쪽 1번에 끝남. 결과 구간 `[00:00, 18:00]` 완료, **10:00~14:00 기사 5건을 받지 않았는데** `crowdedSeconds=0`·`midnightCuts=0`·`badRows=0`이라 리포트에 아무 표시가 없다. D-136 후속 고침 2번("날짜·시각이 실제 값이 아니면 그 행을 거르고 센다")과 QA Q2·Q4(없는 시각, 조용한 손실)의 바로 그 부류이고, 시험의 거를 값 목록(`여섯_자리가_아니거나_없는_시각_날짜는_채우지_않고_거르고_센다`)에 "250000"은 있으나 "240000"은 없다. 한국투자가 24시를 실제로 보내는지는 모른다 `[가정]`. 고침: 검사 형식기에 `.withResolverStyle(ResolverStyle.STRICT)`(직접 확인: STRICT는 "240000" 거절, "000000"·"235959"·"093010" 통과)를 쓰고, 일관성을 위해 `KisNewsCollector.HMS`도 같게, 시험 목록에 "240000"을 더한다.
2. (권고) 변이 MK 생존: `KisNewsClient.answered`가 `rt_cd`가 비지 않았는지 보는 조건을 지워도(아무 JSON 객체면 "답함") 시험이 통과한다. 그러면 `rt_cd` 없는 JSON 5xx(예: 앞단 게이트웨이 `{"error":...}`)를 재시도하지 않고 바로 "(결과 코드 없음)" 거절로 끝난다. `결과_코드_없는_5xx는_…` 시험에 HTML 본문 말고 `rt_cd` 없는 JSON 본문 5xx도 하나 더하면 고정된다. 변이 MF(조기 완료 블록)는 호출 1건만 달라지는 사실상 동등 변이라 시험 필요성은 낮다(원하면 호출 수 단언으로 고정).
3. (권고) 변이 ML 생존: 품질 리포트의 새 줄 "날짜·시각을 읽을 수 없어 거른 행"과 새 열 "자정 확인 못 함"을 보는 시험이 없다(`KisNewsCommandTest`는 리포트 내용 일부만 봄). runbook 3.9 ③④가 사용자에게 이 값을 확인하라고 하므로 리포트에 실제로 찍히는지 한 줄 단언을 권한다. 콘솔 줄(CollectorMain.java:374~376)도 같다.
4. (사소) `docs/decisions/README.md` 142행 D-136 행: "점검표 D8)  후속 고침(…"에서 구분 기호가 빠지고 공백 두 칸이다(". " 또는 "; "). 
5. (사소) runbook collector.md 3장 6번(123행) "결과 코드가 없는 연결 실패·5xx만 1초·2초 뒤 두 번 다시 부른다": 코드(ApiCaller.java:74)는 429도 다시 부른다. "연결 실패·429·5xx"로 맞추면 정확하다.
6. 문서 주장 확인(코드·실행으로): "EGW00201이 HTTP 500으로 와도 1번에 멈춤"(`초당_한도_초과가_HTTP_500으로_와도_…`, 호출 1건), "토큰 만료가 비 200이어도 한 번만 다시 받음"(`토큰_만료가_HTTP_500으로_와도_…`, 토큰 2·호출 2), "결과 코드 없는 5xx는 전처럼 두 번 다시"(호출 3건), "성공 코드인데 200 아니면 믿지 않음"(호출 1건, `KisRejected`), "000000 커서를 보내지 않음"(MH 죽음), "멈출 시각 같은 초 재확인"(ME 죽음) 모두 확인. 기존 `get` 사용처(KRX·OpenDART)는 `r -> false` 위임이라 동작 무변경(`ApiCallerTest` 기존 3건 통과). 자정 손실 셈은 쪽 끝이 00:00:00일 때만 세며, 이미 캐시에 있는 행뿐인 쪽(`added == 0`)이 00:00:01에서 끝나 커서가 전날로 넘어가는 경우는 세지 않는다. 이는 겹침 재확인 구간의 기존 설계(이미 훑은 초는 1초 건너뜀)와 같은 가정이고, 한 쪽이 모두 같은 초면 `crowdedSeconds`로 잡히므로 판정에 넣지 않는다 `[추론]`(코드 읽기).

## 판정
수정 필요(점검표 밖 1: `KisNewsClient.headline` 시각 검사를 STRICT로 바꿔 "240000"을 거르고 시험에 더하기)

## 재검토 2026-10-22
- 대상: PR #225 / 커밋 811699e12b7247080e80d3b2de2f42eaff984491 / main 대비 diff (`git diff origin/main...HEAD`: 21개 파일, +457, −40). 위 검토(5b7fa65a) 뒤 바뀐 것은 `git diff 5b7fa65a HEAD`로 따로 봄: 운영 코드 `KisNewsCollector.HMS`를 `ResolverStyle.STRICT`로, `KisNewsClient.headline`이 그 `HMS`로 시각 검사(KisNewsClient.java:162~163, KisNewsCollector.java:55). 시험 `KisNewsClientTest`(거를 값에 "240000", 통과 값 "000000"·"235959", `rt_cd` 없는 JSON 500은 두 번 다시 부름), `KisNewsCommandTest`(리포트 새 열·새 줄 단언). 문서 D-136 후속 2번, 결정 로그 README D-136 행 구분 기호, runbook collector.md 3장 6번 "5xx·429". 검토 원문 `pr-225/risk-ops.md`(승인·재검토 승인)
- 점검표: B
- 직접 실행한 것(공유 작업 트리와 겹치지 않게 `git worktree add --detach`로 811699e1을 꺼냄, 끝나고 `git checkout`으로 원복 뒤 `git worktree remove`):
  - `./gradlew build --no-daemon` → **종료 0**(새 worktree). 결과 XML 합계 1,928건, 실패 0, 오류 0, 건너뜀 0. `KisNewsClientTest` 12, `KisNewsCollectorTest` 15, `KisNewsCommandTest` 6, `ApiCallerTest` 6 통과
  - 변이 시험(`:autotrade-app-collector:test --tests 'com.autotrade.app.collector.*'`, 하나씩 넣고 원복):

| 변이 | 내용 | 결과 |
|---|---|---|
| R-M1 | `HMS`에서 `.withResolverStyle(ResolverStyle.STRICT)` 제거 | 죽음 |
| R-M2 | `KisNewsClient.answered`의 `rt_cd` 비지 않음 조건 제거(앞 검토 MK) | 죽음 |
| R-M3 | 품질 리포트 "날짜·시각을 읽을 수 없어 거른 행" 줄 제거(앞 검토 ML) | 죽음 |
| R-M4 | 같은 줄 값 `badRows` → 상수 `0` | **생존** |
| R-M5 | `headline`만 옛 SMART 형식기 `ofPattern("HHmmss")`로 되돌림 | 죽음 |
| R-M6 | 리포트 "자정 확인 못 함" 칸 값 `w.midnightCuts()` → `0` | **생존** |

  - `bash scripts/ci/check-collector-keys.sh` → 종료 0. `bash scripts/ci/mark-protected-changes.sh main` → 보호 대상 5개 표시(앞 검토와 같음, 이번 고침 커밋은 보호 대상 중 `KisNewsClient.java`만 다시 바꿈)
  - CI(헤드 811699e1, `gh api .../check-runs`): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 모두 success

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew build` 직접 실행, 종료 0, 1,928건 전부 통과 |
| B2 | 통과 | 이번 고침은 `java.time.format.ResolverStyle` import 하나뿐(KisNewsCollector.java:20). 빌드 스크립트·ArchUnit 무변경, 전체 빌드의 ArchUnit·순수 모듈 검사 통과 |
| B3 | 통과 | 계약 무변경. `HMS`는 수집기 패키지 안 상수. D-136 후속 2번 문구("엄격 해석, 240000도 거름")가 코드와 일치. STRICT `HHmmss`는 연도 칸이 없어 `uuuu/yyyy` 문제 없음. `HMS`의 다른 사용처(KisNewsCollector.java:166·228·247·263)는 자기가 `HMS.format`으로 만든 커서나 이미 `headline`을 통과한 6자리 값만 읽으므로 STRICT로 새로 실패할 입력 없음 `[추론]`(코드 읽기) |
| B4 | 통과 | 시뮬레이션 코드 무변경, 수집기는 주입 `Clock`만 씀, 골든 시험 포함 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 앞 판정 항목(240000)은 `여섯_자리가_아니거나_없는_시각_날짜는_채우지_않고_거르고_센다`가 고정(R-M1·R-M5 죽음). 앞 권고 2·3(MK·ML)도 고정(R-M2·R-M3 죽음). 기존 시험은 늘기만 하고 삭제·`@Disabled`·건너뜀 없음. 생존 R-M4·R-M6은 점검표 밖 1(권고) |
| B7 | 해당 없음(단, 보호 경로) | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도 변경 없음. 보호 경로 `KisNewsClient` 등을 바꾸는 실전 관련 PR이므로 **risk-ops 검토 필요**: `pr-225/risk-ops.md` 재검토(c7bca9b7) 판정 "승인" 확인. 머지는 독립 QA 뒤 사용자 |
| B8 | 통과 | 새 시험 값에 키·토큰 없음(`{"error":"busy"}` 등), 메시지 형식 변경 없음. CI 비밀값 스캔 success |

## 재검토 점검표 밖 발견
1. (권고, 판정 영향 없음) 변이 R-M4·R-M6 생존: 품질 리포트의 "거른 행" 값과 "자정 확인 못 함" 칸 값이 실제 셈(`badRows`, `w.midnightCuts()`)과 연결됐는지는 시험이 보지 않는다(`KisNewsCommandTest`는 둘 다 0인 경우만 봄). 셈 자체는 `KisNewsClientTest`·`KisNewsCollectorTest`가 고정하므로 남는 위험은 리포트 배선 실수뿐이다. 다음 수집기 PR에서 0이 아닌 값이 리포트에 찍히는 단언 하나를 권한다. 콘솔 줄(CollectorMain.java:373~375)도 같다.
2. 앞 점검표 밖 1(수정 필요, 240000) 해결 확인: STRICT 형식기로 "240000" 거절·"000000"·"235959" 통과를 시험이 단언하고, 변이로 되돌리면 죽는다. 앞 4(README 구분 기호 ". ")·5(runbook "5xx·429") 반영 확인. 앞 6의 자정 손실 셈 설명은 그대로 유효.

## 재검토 판정
승인
