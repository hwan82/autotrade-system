# risk-ops 재검토: runbook 3.6 6번 리플레이 점검은 작업 트리로, 게이트웨이 설치본 재생성 단계 삭제, 08:00 전 시작 실행 금지 (PR #62 1차 F1·N1 반영)
- 대상: PR #62 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `951fef0793a355380e9e8bc00290c6071dfd2999`(GitHub REST `pulls/62`의 head SHA와 같음, 상태 open, base main, mergeable true) / merge-base `0423523` 대비 `git diff origin/main...HEAD` 전체. 바뀐 파일 4개(`docs/runbooks/collector.md` +2 −2, `docs/decisions/D-064-krx-2027-calendar.md` +1, 검토 원문 `docs/reviews/pr-62/architect.md`·`risk-ops.md` 신규), 커밋 2개(`7f50448`, `951fef0`). 1차 검토 뒤 바뀐 것은 `7f50448..951fef0`의 runbook 2번·6번 문장과 D-064 이력 줄이다. PR 본문·커밋 메시지·1차 검토 원문은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다.
  - [사실] origin/main은 merge-base 뒤로 `f621667`(#59), `681236e`(#60)가 더 들어갔다. 두 커밋이 바꾼 파일(D-066, decisions/README, strategy 문서, stage-2-replay 보고서, 검토 원문)은 이 PR 파일과 겹치지 않는다.
- 점검표: A
- 직접 실행한 것(격리된 clone `scratchpad/pr62`, detached `951fef0`, 작업 트리 변경 없음):
  - `./gradlew build --no-daemon -q` → 종료 코드 0.
  - 문서만 바뀌어 up-to-date가 많으므로 모듈별 `./gradlew :<모듈>:cleanTest :<모듈>:test` 강제 재실행, 결과 XML 집계(테스트/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 89/0/0/0, `:autotrade-app-gateway` 38/0/0/0, `:autotrade-app-collector` 61/0/0/0. 모두 종료 코드 0.
  - CI(head `951fef0`, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 completed/success.
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 걸린 것은 검토 원문 안의 커밋 SHA와 검색어 설명뿐이고 키·계좌번호·토큰 값은 0건. `src/main`·`src/test`·`.json`·`.kts` 변경 0건.
  - 새 문장의 사실 주장을 코드와 대조(아래).

## 1차 지적 반영 대조
- **F1 (해소)**: 6번이 "리플레이 점검(`./gradlew :autotrade-app-gateway:replayCheck`)은 main을 받은 작업 트리에서 돌린다", "게이트웨이 설치본은 다시 만들지 않는다", "장중에 돌고 있는 게이트웨이의 설치 폴더를 건드리지 않는다"로 바뀌었다(`docs/runbooks/collector.md:71`).
  - [사실] `replayCheck`는 `JavaExec`, `classpath = sourceSets["main"].runtimeClasspath`(`autotrade-app-gateway/build.gradle.kts:29-35`)라 작업 트리 소스로 돈다. `ReplayCheck.java:139`가 `ExchangeCalendar.bundled(market).session(date)`를 읽으므로 작업 트리가 main이어야 바뀐 날짜가 반영된다. 문장과 맞다.
  - [사실] 게이트웨이 설치본 진입점은 `GatewayMain` 하나(`build.gradle.kts:6-9`)이고, 게이트웨이 운영 코드에서 캘린더를 쓰는 곳은 `Gateway.java:63`의 `ExchangeCalendar.bundled(market).zone()`과 `ReplayCheck` 둘뿐이다(`autotrade-app-gateway/src/main` grep). 게이트웨이가 쓰는 `MarketHub`·`MarketEventRecorder`에는 캘린더 참조가 없다. "시간대만 읽는다"는 문장과 맞다. 운영 중 게이트웨이를 재설치하게 만드는 단계가 사라져 1차에서 지적한 장애표 #9 자기 유발 경로가 없어졌다.
  - [사실] D-064 이력 줄(`D-064-krx-2027-calendar.md:43`)도 "게이트웨이는 캘린더 시간대만 써서 설치본 재생성 불필요"로 고쳐져 runbook과 하나씩 맞는다.
- **N1 (해소)**: 2번에 "08:00 전에 시작해 그 시간대로 이어질 수동 실행도 하지 않는다"가 들어갔다(`collector.md:67`). 확인된 옛 시험일의 보호 구간 시작이 09:00(`SessionProtection.java:21`, `:64`; `ExchangeCalendar.java:148`은 미확인일 때만 정규장과 합침)이라 08:00 전에 시작한 실행이 08:00~09:00을 지나가는 경우를 사람 절차로 막는다. 매일 예약 실행(18:30 이후)과 겹치지 않는다.
- 1차의 나머지 대조(08:00~09:00 공백 설명, 실험 명령의 작업 트리 반영, 수집기 설치본)는 `951fef0`에서도 그대로 맞다. 판정 코드 `ExchangeCalendar.java:137-155`, `SessionProtection.java:64-67`은 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard 코드·설정·테스트 변경 없음(diff는 `docs/` 4개 파일). `:autotrade-risk` 55/0/0/0 강제 재실행 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test` 31/0/0/0 강제 재실행 통과 |
| A3 | 해당 없음 | 주문 라우팅·증권사 전환 코드 변경 없음. 새 문장은 캘린더 반영 대상 실행본과 단발 명령 실행 시간만 다룬다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine` 48/0/0/0 |
| A8 | 통과 | 판정 입력(`krx.json`)과 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection.java:64-67`) 변경 없음. 실전 엔진은 캘린더를 읽지 않는다(`autotrade-app-engine/src/main`에 `ExchangeCalendar` 참조 0건). 2번 새 구절은 확인된 옛 시험일 08:00~09:00과 그 앞에서 이어지는 실행까지 단발 명령을 막아 보호를 넓히기만 한다. 1차 F1의 "운영 중 게이트웨이 설치본 재생성" 단계가 빠지고 "장중 게이트웨이 설치 폴더를 건드리지 않는다"가 들어가 시세 게이트웨이 가용성 위험도 없어졌다. 리플레이 점검 재실행에 시간 조건이 없는 점은 N1(권고) |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. 장애표 #9(`broker-and-fault-design.md:168`, 게이트웨이 크래시 → #1·#2) 전제 그대로이고, 절차가 게이트웨이 장애를 스스로 만드는 단계가 없어졌다 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(위 검색). 예시는 Gradle 작업 이름과 소스 위치뿐 |
| A11 | 통과 | 테스트 파일 변경 없음. 보호 테스트(`ArchitectureTest`, Guard·Gateway, 장애 테스트) 삭제·비활성화 없음. 새 동작(코드)이 없어 새 테스트 요구 없음 |
| A12 | 통과 | 장애표 17행(6장 #1~#17)·해외 O1~O8 행 수와 장애 테스트 8종(`broker-and-fault-design.md` 7장)의 전제를 바꾸지 않는다. 새 문장은 #10(PC 자원 고갈, 5.2 0번 단발 명령) 보호의 판정 입력이 늦게 반영될 때의 사람 절차만 넓힌다. 대응표·테스트 갱신 필요 없음 |
| A13 | 해당 없음 | 미국 캘린더·`MarketRules` 변경 없음. 새 문장의 시각은 모두 "서울"로 현지 시간대를 밝힌다 |
| A14 | 통과 | D-064(수능일 출처, 미확인 특별 운영일 합침, 갱신 절차 runbook 3.6)와 충돌 없음. `D-064-krx-2027-calendar.md:43` 이력(옛 시험일 08:00~09:00 임시 중지, 6번 머지 뒤 반영 단계, 리플레이 점검은 작업 트리, 게이트웨이 설치본 재생성 불필요, 데이터·동작 변경 없음)이 runbook diff와 하나씩 맞는다. 동작 변경이 없어 새 결정 번호 불필요 |

## 실전 관련 PR 여부
**실전 관련 PR이다(사용자 직접 머지 대상, 독립 QA 관문).** 운영 코드·데이터·테스트는 바뀌지 않지만, runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 유일한 판정 입력 `krx.json`의 갱신 절차이고, 이 PR은 반영 전 수동·예약 실행 중지라는 장애 대응 운영 규칙을 넓힌다. 지침 10장 "장애 대응을 건드리는 PR"에 해당한다(1차 판정, 같은 절차를 바꾼 PR #53~#56과 같음).

## 점검표 밖 발견
- **N1 (권고, 절차 — 머지를 막지 않음)**: 6번 "날짜가 바뀐 날의 녹화가 있으면 리플레이 점검을 다시 돌린다"에 실행 시간 조건이 없다. [사실] `ReplayCheck`는 `SessionProtection`·`RunGuard`·정지 요청 파일 어느 것도 확인하지 않는다(`autotrade-app-gateway/src/main` grep 0건). 5.2 0번의 정지 요청 대상 목록(`broker-and-fault-design.md:144`)에도 없다. 녹화를 두 번 풀어 리플레이하는 단발 명령이라 장중에 돌리면 실전 엔진과 같은 PC의 CPU·디스크를 아무 보호 없이 쓴다. [사실] 이 공백은 이 PR 전부터 있다(`stage-2-real-recording.md:23`도 시간 조건이 없다). 이 PR은 실행 계기를 하나 더할 뿐이고 아직 실전 계좌가 활성화되지 않아 머지를 막지 않는다. "장 마감 뒤(보호 구간 밖)에" 한 구절을 더하기를 권하고, `replayCheck`를 5.2 0번 대상(시간 보호·정지 요청 확인)에 넣는 것은 data-engineer 후속 과제로 남긴다(7단계 실전 활성화 전 정리 필요).
- **N2 (권고 유지, 1차 F1의 권고 부분)**: 수집기 설치본 재생성(`installDist`)에도 "예약 실행이 돌지 않는 시간에"가 없다. 수집기 예약 실행 중에 설치 폴더 jar를 바꾸면 [추론] Windows에서 잠긴 jar 때문에 작업이 실패하거나 설치 폴더가 일부만 바뀌어 다음 예약 실행이 깨질 수 있다. 실전 엔진에는 영향이 없고 실패는 종료 코드로 드러나 머지를 막지 않는다.
- **N3 (기록)**: 6번은 "캘린더를 쓰는 실행본"을 수집기 설치본·실험 명령·리플레이 점검으로 닫아 적었다. 지금 코드에서는 맞다(캘린더 사용처 grep: 수집기, 실험 명령, 게이트웨이 `zone()`·`ReplayCheck`, 순수 모듈들). 다만 `KrxMarketRules`(`KrxMarketRules.java:41`, `:61` `isRegularSession`)가 캘린더로 장 시간을 판정하므로, 실전 엔진이나 게이트웨이가 나중에 `MarketRules`를 쓰기 시작하면 그 설치본도 반영 대상이 된다. 그때 6번 목록을 함께 고쳐야 한다(엔진 설치본 재생성은 엔진 정지·장 마감 뒤 조건 필수).
- **N4 (기록)**: 6번 끝의 근거 괄호는 "(PR #56 QA Q1·Q2, risk-ops-2 N1·N2, architect-2 N2)"라 이번 수정의 출처인 PR #62 1차 F1·N1을 적지 않았고, `risk-ops-2`가 PR #56의 원문이라는 점도 문장만으로는 알 수 없다. 동작과 무관한 추적성 문제다. 커밋 메시지에는 PR #62 F1·N1이 적혀 있다.
- **N5 (기록, 이전 지적 유지)**: `ExchangeCalendar` 생성자가 항목의 `source` 키 존재를 검사하지 않는 점, 캘린더 수록 범위 만료 자동 경보 없음(다음 마감 서울 2027-12-31 00:00), 거래소가 연기된 옛 시험일을 정규장으로 되돌린다는 전제의 1차 자료 미확인 `[가정]`(되돌리지 않아도 새 조치는 보호를 넓히기만 함)은 그대로다(PR #55·#56 N3, PR #62 1차 N2·N3).
- **기록(검토 형식)**: `docs/reviews/README.md`는 재검토를 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이게 하지만, 이번 재검토는 요청에 따라 1차 원문을 그대로 두고 이 파일에 새로 썼다(PR #56의 `risk-ops-2.md`와 같은 방식).

## 판정
승인. 점검표 A에 실패·확인 못 함이 없다. 1차 F1(게이트웨이 설치본 재생성 사실 오류)과 N1(08:00 전 시작 실행)이 diff에서 해소된 것을 코드(`autotrade-app-gateway/build.gradle.kts:29-35`, `Gateway.java:63`, `ReplayCheck.java:139`, `SessionProtection.java:64-67`)와 격리 clone 빌드·테스트로 확인했다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차)이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장의 독립 QA 관문 대상이다. N1·N2는 머지를 막지 않는 권고이고, N1의 `replayCheck` 보호 공백은 7단계 실전 활성화 전 정리할 후속 과제로 남긴다.
