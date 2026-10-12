# 독립 QA 검토: 장애 테스트 1-T 시행 조건을 D-039에 맞춤
- 대상: PR #30 / 커밋 84ac8a2 / main 대비 diff (`git diff origin/main...HEAD`, merge-base 798b0eb. 검토 시점 main은 0a4adf4로 앞서 있으나 그 사이 main 변경(#26, #29)은 ADR-006 6장·market-data-limits.md·검토 원문뿐이고 이 PR이 바꾼 파일과 겹치지 않음. `git merge-tree --write-tree origin/main HEAD` 충돌 없음)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 직접 실행한 것:
  - `git diff --name-only origin/main...HEAD`: 3개 파일. `docs/decisions/README.md`(D-036 행 근거 `D-033` → `D-033→D-039`), `docs/team/broker-and-fault-design.md:207`(7.1 표 1-T 시행 조건), `docs/reviews/pr-30/risk-ops.md`(신규, 검토 원문). 커밋 3개(e7c0064, 55d8a96, 84ac8a2).
  - `git diff --name-only origin/main...HEAD -- '*.java' '*.json' '*.sql' '*.kts' '*.gradle' '*.yml' '*.yaml' '*.sh' '*.py'`: 0건. 코드·설정·스키마·빌드·CI·테스트 변경 없음. 보호 테스트(`docs/qa/README.md` 1장) 파일 변경 없음.
  - `./gradlew build --no-daemon` (깨끗한 체크아웃, 커밋 84ac8a2): 처음 10회는 Maven Central `429 Too Many Requests`로 의존성 받기에서 실패(`build.log`의 "What went wrong"이 모두 `Could not resolve ... status code 429`, 테스트 실패 아님). 11번째 시도 **BUILD SUCCESSFUL**(104 tasks). 테스트 결과 XML 집계(테스트/실패/오류/건너뜀): app-engine 48/0/0/0, app-gateway 25/0/0/0, app-lab 97/0/0/0, app-watchdog 1/0/0/0, architecture-test 28/0/0/0, broker-api 1/0/0/0, broker-kis 8/0/0/0, broker-kiwoom 13/0/0/0, config 30/0/0/0, control 20/0/0/0, domain 31/0/0/0, marketdata 65/0/0/0, risk 55/0/0/0, sim 75/0/0/0, strategy 25/0/0/0. 합계 522/0/0/0. 테스트가 없는 모듈: monitor-agent-jar, notification-jar, ui.
  - 보호 테스트 개별 확인: `RealTradingGuardTest` 31/0/0/0, `GuardedOrderGatewayTest` 8/0/0/0, `ArchitectureTest` 15/0/0/0, `ModuleOwnershipTest` 13/0/0/0, `ConfigLoaderTest` 21/0/0/0, `GatewayMainTest` 3/0/0/0(`기본_목록의_토스는_키가_있어도_실시간을_구현하기_전까지_건너뛴다`, `계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다` 포함).
  - PR CI(84ac8a2): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success.
  - 문구의 전제 대조: `autotrade-config/src/main/resources/autotrade-default.json:3` `"priority": ["KIWOOM", "TOSS", "KIS"]`, `GatewayMain.java:86-94`(`reservedForRealTrading` 증권사는 건너뜀), `GatewayMain.java:107-115`(계좌번호까지 설정된 실전 계좌의 증권사), `GatewayMain.java:125` `case TOSS -> Optional.empty()`(토스 실시간 미구현), `docs/decisions/D-039-toss-quotes-free-default-on.md` 결정 2, `docs/spec/autotrade-spec.md:111` MD-02, `docs/config/config-keys.md:15`.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드·설정·테스트 변경 0건(위 `git diff --name-only`). `RealTradingGuardTest` 31건, `ConfigLoaderTest` 21건 직접 실행 통과. 바뀐 문서 문장은 Guard 기본값·재기동 동작을 언급하지 않음 |
| A2 | 통과 | 코드·ArchUnit 변경 0건. `ArchitectureTest` 15건, `ModuleOwnershipTest` 13건, `GuardedOrderGatewayTest` 8건 직접 실행 통과 |
| A3 | 통과 | 바뀐 문장은 게이트웨이 시세 연결 시험(1-T)의 시행·기록 조건뿐이고 주문 라우팅을 다루지 않음. broker-and-fault-design.md 4.2 "주문은 계좌의 증권사로만" 변경 없음. 코드 변경 0건 |
| A4 | 통과 | 코드 변경 0건. 장애표 4번·장애 테스트 4번 문구 변경 없음(diff 전문 확인). app-engine 48건 통과 |
| A5 | 통과 | 코드 변경 0건. 멱등 키 관련 문서·코드 변경 없음. app-engine·risk 테스트 통과 |
| A6 | 통과 | 코드 변경 0건. 블랙리스트·수동 보유 관련 문서 변경 없음. risk 55건 통과 |
| A7 | 통과 | 코드 변경 0건. 계좌별 큐·토큰 버킷 문구 변경 없음. `RealTradingGuardTest` 통과 |
| A8 | 통과 | 5.1·5.2(프로세스 분리·정지 순서) 변경 없음. 정상 설정에서 게이트웨이가 실전 계좌 증권사를 쓰지 않는 규칙(`GatewayMain.java:86-94`, `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다` 통과)이 그대로이고, 7.1 본문의 "정상 운영 설정에서 우회하지 않습니다"(:203)도 그대로 |
| A9 | 통과 | 보호 시세 경로(HELD_ONLY)·서버 측 손절(6.1) 문구 변경 없음. 코드 변경 0건 |
| A10 | 통과 | 바뀐 줄은 모두 문서 문장. 키·계좌번호·토큰 값 없음(diff 전문 확인). CI Secret scan success |
| A11 | 통과 | 테스트 파일 변경 0건. 보호 테스트 전부 직접 실행 통과(위 목록). 새 코드 동작 없음(문서 변경) |
| A12 | 통과 | 장애표 17종·장애 테스트 8종의 개수와 합격 지표(7.1 "공통 전제와 합격 지표") 변경 없음. 1-T 시행 조건 본문(토스 실전 계좌, `TOSS` 포함 설정, 다른 토스 키, 계정 전체 보유·미체결 없음) 유지. 이전 문구 "D-033 기본값 포함 해당 없음"은 D-039 뒤로 기본값(`autotrade-default.json:3`)과 맞지 않았고, 새 문구는 토스 실시간 미구현(`GatewayMain.java:125`) 동안 1-T를 "해당 없음"이 아닌 "시행 불가"로 남겨 시험을 건너뛸 여지를 줄이는 방향이다. 1-TK(:209)는 "1-T와 1-K가 각각 합격한 뒤에만"이라 1-T가 시행 불가인 동안 1-TK도 시행되지 않아 서로 모순 없음. 단 문장 적용 범위 모호(아래 Q1) |
| A13 | 해당 없음 | 미국 시장 경로·`MarketRules`·시간대·통화를 건드리지 않음(diff 전문 확인) |
| A14 | 통과 | D-039 결정 2, MD-02(`autotrade-spec.md:111`), config-keys.md:15, `autotrade-default.json:3`이 `["KIWOOM","TOSS","KIS"]`로 일치하고, 이 PR로 7.1과 D-036 행 근거(`D-033→D-039`)가 맞춰짐. `grep -n D-033 docs/team/broker-and-fault-design.md` 0건. "시행 불가(미구현)" 기록 규칙은 D-036 결정 내용(토스 항목은 토스 시세를 켠 설정에서만)을 바꾸지 않고 기록 방식을 구체화한 것이라 새 결정 번호가 꼭 필요하다고 보지 않음 `[추론]`. D-036 행은 기존 행 수정이지만 근거 표기만 바꾼 것이고 결정 문장은 그대로 |
| B1 | 통과 | `./gradlew build` 직접 실행 BUILD SUCCESSFUL(84ac8a2, 104 tasks, 테스트 522/0/0/0). 앞선 실패 10회는 모두 Maven Central 429 의존성 받기 실패이며 컴파일·테스트 실패가 아님 |
| B6 | 통과 | 테스트 파일 변경 0건, 건너뛰기(`@Disabled`) 추가 없음, 건너뜀 0건. 새 코드 동작 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 식별 | 통과 | diff에 코드·설정 변경 0건이라 새 외부 API 호출 지점 없음. 바뀐 문장이 언급하는 토스 시세는 D-039(사용자 확인)로 무료, 키움·KIS 시세·토큰 발급은 무료(사용자 확인 2026-10-09). 토스 실시간은 아직 호출 코드 자체가 없음(`GatewayMain.java:125`) |
| 8.2 필요성과 대체 수단 | 통과 | 새 유료 호출 없음. 1-T 시험이 요구하는 "다른 토스 키"는 D-036부터 있던 조건이고 이 PR이 새로 만든 것이 아님 |
| 8.3 사용자 옵션 제어 | 통과 | 새 호출 경로 없음. 기본 목록의 TOSS는 키가 있어도 건너뜀(`GatewayMainTest#기본_목록의_토스는_키가_있어도_실시간을_구현하기_전까지_건너뛴다` 통과) |
| 8.4 폭주 방지 | 통과 | 반복·재시도·루프 코드 변경 없음 |
| 8.5 실주문 비용 | 통과 | 주문 경로 변경 없음. 7.1 "실주문이 필요하면 TST-05처럼 횟수·비용 상한을 사용자에게 승인받은 뒤에만"(공통 전제) 변경 없음. 바뀐 문장은 실주문을 요구하지 않음 |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. D-033 기간에 설정을 저장해 `priority`가 `["KIWOOM","KIS"]`로 남은 사용자(D-039 "영향과 남은 위험" 3번째 항목)가 토스 실전 계좌를 가진 경우: 1-T는 "TOSS 없음 → 해당 없음"으로 기록되고 활성화가 진행될 수 있다. 이후 사용자가 TOSS를 다시 넣어도 정상 설정에서는 `reservedForRealTrading`이 게이트웨이의 토스 연결을 막는다(`GatewayMain.java:86-94`, 테스트 통과). 그래서 이 순서로 실전 엔진 조회 공백이 생기지는 않는다. 결과: 공백 없음.
2. 토스 실전 계좌가 이미 활성화된 상태에서 토스 실시간을 연결하는 코드 PR이 머지되는 경우: 새 문장은 "그 변경 뒤에 1-T를 시행해야 활성화 조건을 채운다"고만 하고, 이미 활성화된 계좌를 어떻게 할지와 이를 강제하는 장치(코드·CI)가 없다. 다만 정상 설정에서는 위 1과 같이 게이트웨이가 실전 토스 계좌 증권사에 연결하지 않으므로 주입 상황 자체가 정상 운영에서 생기지 않는다 `[추론]`. 결과: 즉시 위험 없음, 절차 공백 남음(risk-ops N3와 같은 지점, 아래 Q2).
3. 키움 실전 계좌만 있고 토스 실전 계좌는 없으며 기본 설정(`TOSS` 포함)인 사용자: 첫 문장으로는 "토스 실전 계좌 없음 → 해당 없음"이지만, 둘째 문장 "토스 실시간이 아직 구현되지 않았으면 '시행 불가(미구현)'로 기록하고 해당 없음으로 치지 않으며 … 1-T를 시행해야 실전 활성화 조건을 채운다"에는 적용 범위(시행 조건에 해당할 때만인지)가 적혀 있지 않다. 글자 그대로 읽으면 키움 단독 활성화도 토스 실시간 구현 전까지 막힌다. 막히는 쪽이라 주문·보호 공백은 아니지만, 활성화 판정(D1) 때 해석이 갈려 "확인 못 함"이 되거나 판정자가 임의로 해석하게 된다. 결과: 공백 없음, 문구 모호(아래 Q1).
4. 이 PR이 주문을 만들거나 바꾸는 경로: 코드·설정 변경 0건이라 잘못된 주문·중복 주문을 만들 경로 없음.

## 점검표 밖 발견
- Q1(권고, 머지 차단 아님): broker-and-fault-design.md:207 둘째 문장의 적용 범위가 모호하다(시나리오 3). "위 시행 조건(토스 실전 계좌가 있고 `TOSS`가 있는 설정)에 해당하는데 게이트웨이의 토스 실시간이 아직 구현되지 않았으면 …"처럼 범위를 문장에 넣을 것을 권한다. 지금 문구도 막히는 쪽으로만 틀리므로 안전 공백은 아니다.
- Q2(권고, 머지 차단 아님): "토스 실시간을 연결하는 변경 뒤에 1-T 재시행"을 사람의 기억에만 맡긴다(시나리오 2). 토스 실시간 연결 PR(`GatewayMain.java:125`의 `case TOSS -> Optional.empty()`를 바꾸는 PR)의 risk-ops 검토 항목과 TST-07 대응표에 "이미 활성화된 토스 계좌가 있으면 그 PR 머지 전 또는 계좌 재활성화 전에 1-T 시행"을 넣을 것을 권한다(risk-ops N3과 같은 지점).
- Q3(정보): 장애 테스트 1번 합격을 계좌별로 보는지 전체로 보는지 정해져 있지 않다. 토스·키움 실전 계좌가 모두 있으면 1-T가 시행 불가인 동안 1-TK도 시행되지 않아, 키움 계좌만 먼저 활성화할 수 있는지 판정이 갈린다. 이 PR이 만든 문제는 아니며 7단계 절차서에서 정할 일이다.
- Q4(정보): risk-ops 원문은 토스 미구현 근거를 `GatewayMain.java:126`으로 적었으나 84ac8a2 기준 실제 줄은 :125(`case TOSS -> Optional.empty();`)다. 내용은 같다.
- 분류: 장애 대응 문서(7장 장애 테스트)를 바꾸는 실전 관련 PR이므로 사용자가 직접 확인하고 머지한다.

## 판정
승인
