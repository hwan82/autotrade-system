# architect 검토: 단타 종목 선정 설계 ADR-012·D-121, Q-054·Q-055 (#191)
- 대상: PR #191(번호는 메인 전달 기준) / 브랜치 `claude/project-thread-qgen8y` 커밋 c12bc55 / `git diff origin/main...HEAD` 전체(커밋 1개, 4개 파일, +231 −0: `docs/adr/ADR-012-intraday-selection.md` 새 파일, `docs/adr/README.md`, `docs/decisions/D-121-intraday-selection.md` 새 파일, `docs/decisions/README.md`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`: 처음 세 번은 Maven Central `429 Too Many Requests`로 의존성(spring-boot-dependencies 3.5.16, archunit 1.4.1) 내려받기 실패(코드 문제 아님, 환경). 네 번째 실행 BUILD SUCCESSFUL in 1m 55s, 138 actionable tasks, EXIT=0
  - 테스트 결과 XML 집계(전 모듈): 1673건, 실패 0, 오류 0, 건너뜀 0
  - 바뀐 경로를 `scripts/ci/protected-paths.txt`와 대조: 걸리는 경로 없음(문서 4개, 보호 목록의 docs 항목은 project-instructions·qa·D-086·ADR-002뿐)
  - ADR이 `[사실]`로 든 코드·문서 위치를 저장소에서 직접 확인: `SelectionProfile.java:70`·`SelectionRefresh.java:6-19`(단타 ⇔ PRE_OPEN_DAILY), `CollectorMain.java:49`(`KRX_SAME_DAY_FROM` 18:00), `KrxOpenApiClient.java:19,61-62`(`ACC_TRDVAL` 미수집), `LiveLabService.java:57`(`INBOX_CAPACITY = 100_000`), ADR-010:47("선정 프로필 없음"), ADR-008:63(`list.json` 3개월·100건), `experiments/EXP-20261026-LIVE01/experiment.yaml`(`boards` 10종목, `blacklist` 키 있음), `RunStepper.java:139`(선정 밖 신규 진입 차단), selection-catalog 2·3장(키·지표 정의). 모두 ADR 서술과 맞다
  - 결정·미확정 번호 충돌: main의 마지막 결정 D-120, 미확정 Q-053까지. 다른 원격 브랜치 diff에서 D-121·ADR-012·Q-054·Q-055를 쓰는 곳 없음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(EXIT=0), 테스트 1673건 실패·건너뜀 0. 문서만 바꾼 PR이라 빌드 결과는 main과 같아야 하고 그렇다 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음. 설계 내용도 모듈 경계를 지킨다: 계산은 실험실 프로세스의 `CalculatedSelectionSource`(sim), 실전 선정 생산자(`autotrade-app-selection`)·엔진 경로는 범위 밖(ADR-012:90, 149) |
| B3 | 실패 | 이 PR이 바로 계약 ADR이므로 "ADR 먼저"는 지켰다. 그러나 ADR이 제안하는 계약 변경 중 기존 계약과 부딪히는 것을 적지 않았다. (1) 새 갱신 주기 `PRE_OPEN_DAILY_LAG1`(ADR-012:104)·`INTRADAY_AT`(:139)은 단타 스타일에 쓰이는데, 기존 계약은 "단타 ⇔ `PRE_OPEN_DAILY`"를 ADR-007:57·129, `SelectionRefresh.java:19`, DB CHECK `V2__selection_combination.sql:27,32`에 고정한다. 마이그레이션·ADR-007 2.1 개정이 필요하다는 것이 배치표(ADR-012 6장)에 없다. (2) 선정 계산 시점(3.5 "계산 시점")이 ADR-007 2.7(ADR-007:228, 292)의 "단타 `PRE_OPEN_DAILY`도 기본은 전날 장 마감 뒤 미리 만든다, 장전 실행은 시간 하한·마감 사이에서만"과 다른데 차이와 이유를 적지 않았다(아래 F1). (3) ADR 상태가 "제안"(ADR-012:3, ADR README 표)인데 D-121은 "확정"(D-121:4)이다. 선례는 확정 결정이면 ADR "채택(D-xxx)"(ADR-009·010), 사용자 머지 대기면 "제안(D-xxx, 사용자 머지)"(ADR-011) |
| B4 | 실패(설계) | 코드 변경은 없지만 설계가 결정론 원칙을 약하게 한다(F2): 녹화 실험(`RecordingPlan`)에도 `selection-input: latest`를 허용하는데(ADR-012:72-81, 95), 기존 일괄 실험 등록은 스냅샷 ID를 고정한다(`CombinationPlan.java:31-32` `bars-snapshot`, `financials-snapshot`). 녹화 실험을 나중에 다시 돌리면 "최신" 스냅샷이 달라져 같은 등록 파일이 다른 결과를 낼 수 있다. 리플레이 확인이 고정 파일을 읽는 규칙(3.6)은 실시간 세션에만 명시돼 있다 |
| B5 | 해당 없음 | 금액 계산 코드 없음. 설계의 지표 계산은 selection-catalog 3장 `DECIMAL128` 규칙을 그대로 쓴다고 명시(ADR-012:52) |
| B6 | 해당 없음 | 테스트 대상 코드 없음. 구현 PR 완료 기준(ADR-012 9장)에 경계값·미래 정보·고정 파일 해시 불일치·`SELECTION_MISSING`·선정 없는 계좌 불변 시험이 열거돼 있다. 지우거나 끈 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 코드·리스크 한도·장애 대응·증권사 연동·보호 테스트·보호 경로 변경 없음. 실전 영향 항목(2차-b 게이트웨이 변경)은 Q-054로 사용자 결정에 남김(ADR-012:146, 164). 단 F4 참고 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 키(뉴스)는 수집기 전용 비밀 파일에 둔다고 명시(ADR-012:129) |

## 점검표 밖 발견

- **F1 (수정 필요) 선정 계산을 실시간 세션의 이벤트 처리 경로에서 하도록 설계했다.** [사실] ADR-012:91은 "날짜가 바뀌는 지점(`JournalWriter`가 새 날 파일을 여는 지점)에서 계산, 그동안 이벤트는 받는 큐(100,000)에 쌓인다, 60초 상한"이다. [사실] 그 지점은 처리 스레드의 `LiveSession.accept` 안이고(`LiveSession.java:249,271` `journal.append`), 받는 큐가 차면 이벤트를 버리고 `CONSUMER_OVERFLOW` 공백을 남긴다(`LiveLabService.java:515-529`, ADR-010:58). 입력 기록 자체가 처리 스레드에서 쓰이므로 버린 이벤트는 기록에도 없다. 그래서 (a) 60초 상한은 큐가 넘치지 않는다는 보장이 아니다(넘침은 이벤트 속도에 달림, 장전 동시호가 묶음·넓힌 `boards` 150종목 `[가정]`이면 속도가 크다), (b) 넘치면 선정 조합 계좌뿐 아니라 **선정 없는 계좌의 입력까지 공백**이 생겨 "선정 없는 계좌는 지금과 같다(시드·결과 불변)"(ADR-012:85, 9장)가 깨질 수 있다, (c) ADR-007 2.7은 이미 단타 선정도 "전날 장 마감 뒤 미리 만든다"를 기본으로 정했다(ADR-007:228, 292). 요구: 그날 선정 결과는 이벤트 경로 밖에서 미리 만든다(예: 저녁 수집 뒤 실험실 일괄 명령 또는 세션 시작·전날 마감 시점에 별도 저우선 스레드로 계산해 고정 파일을 먼저 씀). 날짜 전환 지점에서는 고정 파일을 읽기만 하고, 없으면 그날 `SELECTION_MISSING`. 그대로 둘 이유가 있으면 넘침 영향과 ADR-007 2.7과 다른 이유를 ADR에 적는다.
- **F2 (수정 필요) 녹화 실험의 입력 스냅샷을 고정한다.** B4 근거. 요구: `selection-input: latest`는 `input: live`에만 허용하고, `RecordingPlan`은 기존 일괄 실험처럼 스냅샷 ID(`daily`, `disclosures`)를 적게 하거나, 녹화 실험 재실행도 결과 폴더의 고정 선정 파일을 읽는다고 ADR에 명시한다. 어느 쪽이든 결과 메타에 스냅샷 ID·sha256이 남는다는 규칙(SIM-10)을 같이 적는다.
- **F3 (수정 필요) B3의 (1)·(3)**: 배치표 1번(LAG1 결정)과 7번(INTRADAY_AT)에 "ADR-007 2.1·DB CHECK(`V2__selection_combination.sql:27,32`)·`SelectionRefresh.allows` 변경, 새 마이그레이션 필요"를 적고, ADR 상태와 D-121 상태를 맞춘다.
- **F4 (권고) watchlist 확대의 실전 영향 판단이 5장과 엇갈린다.** ADR-012:166은 게이트웨이 watchlist를 150종목으로 넓혀도 LIVE01에는 영향이 없다고만 보지만, 5.1(ADR-012:134)은 게이트웨이 구독·호출 여유를 실전 엔진과 함께 쓴다는 이유로 2차-b를 실전 관련으로 분류한다. watchlist 확대도 같은 구독 한도를 쓴다. 지금 실전 계좌가 없어 당장 위험은 없지만, 7단계 전에 watchlist 크기 상한을 risk-ops가 정한다는 줄을 남기기를 권한다. 또 키움 200종목이 체결·호가를 따로 세는지는 `[가정]`이다(market-data-limits.md:139, 따로 세면 100종목). ADR-012:137의 "200종목 `[사실]`"에 이 단서를 붙인다.
- **F5 (권고) `disclosure-count`의 정정 공시 규칙이 구현 가능할 만큼 정의돼 있지 않다.** "정정 공시는 원공시 1건으로 친다"(ADR-012:47)인데 `list.json` 응답에는 원공시 접수번호 연결 필드가 없다(ADR-008:63, 69는 보고서명 `[` 접두로 정정 여부만 판정). 원공시가 창 밖이고 정정만 창 안일 때 세는지 정하지 않았다. 예: "정정 공시(보고서명 `[` 시작)는 세지 않는다" 같은 결정 가능한 규칙으로 적는다. 또 공시 시점 규칙 "접수일 ≤ c"는 재무의 "접수일 < T"(selection-catalog:21, MD-10)보다 엄격하다(c와 T 사이 휴장일 접수분). 미래 정보 문제는 없으나 차이를 한 줄 적는다.
- **F6 (권고) 호출 상한 수치가 수집기와 다르다.** ADR-012:66은 OpenDART "개인 20,000회"를 쓰지만 수집기는 하루 10,000회를 기본 상한으로 둔다(ADR-008:68). 2010년부터 전체 회사 공시 목록을 받으면 재무 수집과 같은 할당을 나눠 쓰므로, 유형(`B`·`I`)으로 좁혀 부르는 것과 수집 순서를 배치표 3번에 적기를 권한다.
- **F7 (권고) `BLACKLISTED`로 뺀 자리를 다음 순위로 메우는지 명시한다.** `NOT_SUBSCRIBED`는 메우지 않는다고 적었지만(ADR-012:86) 블랙리스트는 적지 않았다(:88). 같은 이유로 메우지 않는 쪽이 자연스럽다.
- [사실] 그 밖의 근거 위치·수치는 직접 확인한 범위에서 맞다(위 "직접 실행한 것"). 대안 비교(7장), 위험(8장), 되돌릴 조건, 미확정 Q-054·Q-055 분리, LIVE00·LIVE01 불변은 지침 9장 수준을 갖췄다.

## 판정
수정 필요(F1 선정 계산을 이벤트 처리 경로 밖으로·ADR-007 2.7과 맞춤, F2 녹화 실험 입력 스냅샷 고정, F3 갱신 주기 추가의 계약 충돌(ADR-007 2.1·DB CHECK) 명시와 ADR·D-121 상태 일치 / B3·B4). F4~F7은 권고.
