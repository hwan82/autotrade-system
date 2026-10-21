# risk-ops 검토: 선정 계좌 받지 않은 확인 신호도 일 손실 한도 확인(D-135)·주문 전 거름·순위 전 대기일 시험
- 대상: PR #218 / 커밋 b53a3c411652814039bf26423ad9307665a13821 (브랜치 claude/project-thread-8xacpv) / main 대비 diff(`git diff origin/main...HEAD`, 8개 파일 +160 −12: 운영 코드 `ManagedStrategy.java`(보호 경로 `autotrade-strategy/*`), `RunSelectionDay.java`(javadoc만), 시험 `CatalogStrategyTest`·`SelectionEntryQuotaTest`, D-135 새 문서, 결정 로그 표, ADR-013 한 줄, `docs/qa-out-status.md` 한 줄)
- 점검표: A (보호 경로 `autotrade-strategy/*`, `scripts/ci/protected-paths.txt` 실전 경로 런타임 모듈 항목 → 실전 관련 PR로 취급)
- 범위 밖: 검토 시점 저장소 작업 트리에 커밋 안 된 변경(`SelectionEntryQuotaTest.java` 1줄, `D-135` 문서 2줄)이 있었다. 이 원문은 b53a3c4 커밋만 판정하며 그 변경은 보지 않았다. 커밋되면 재검토 대상이다.
- 직접 실행한 것(별도 git worktree, b53a3c4 분리 체크아웃, 저장소 작업 트리 무변경, 끝나고 worktree 제거):
  - `./gradlew --offline :autotrade-strategy:test … :autotrade-app-engine:test` → 실패(캐시에 Spring Boot 플러그인 의존성 없음). `--offline` 없이 → 실패(`repo.maven.apache.org … jackson-annotations-2.21.jar` "Received status code 429 … Too Many Requests"). 코드와 무관한 환경 문제.
  - `./gradlew --no-daemon -I <저장소 밖 scratchpad의 미러 init 스크립트> build --console=plain`(Maven Central 주소만 Google 미러 `maven-central.storage-download.googleapis.com`으로 바꿈, 저장소 파일·`~/.gradle` 설정 무변경) → **EXIT 0, BUILD SUCCESSFUL in 5m 8s**. 시험 XML 합계 1,881건, 실패 0·오류 0·건너뜀 0. 모듈별(시험/실패): strategy 50/0, sim 476/0, risk 55/0, architecture-test 46/0, app-engine 68/0. 새 시험 4개(`CatalogStrategyTest` 2, `SelectionEntryQuotaTest` 2)와 `선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다`, `블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다` 통과.
  - 변이 시험(파일 하나씩 바꾸고 해당 시험 클래스만 실행, 매번 `git checkout`으로 원복·`git status` 깨끗함 확인):
    - M1 `ManagedStrategy.entry`의 D-135 분기(`if (!haltedToday) dailyLossReached(account);`) 삭제 = 고치기 전 동작 → `실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다` 실패("후보 밖 신호 때 닿은 손실로 그날 멈춤" 기대에 BUY 신호). **잡힘**
    - M2 `RunStepper`가 전략에 `seq - 1`을 넘김(QA N6) → `실시간_모의_순위_전_대기일_신호는…` 실패(매수 0건). **잡힘**
    - M3 `RunStepper.admits`가 대기일(`awaiting()`)이면 받음(QA N5) → 같은 시험 실패(매수 0건). **잡힘**
    - M4 `RunStepper.onEvent`의 주문 전 거름 조건을 `if (false)`로 = 거름 제거 → `전략이_진입_여부를_묻지_않아도_주문_전_거름이_후보_밖_매수를_막는다` 실패(후보 밖 005930 BUY 체결). **잡힘**
  - 보조(직접 실행 아님): 같은 헤드 GitHub CI 4종 success(Gradle build job 119024517642, DB schema, Lab screens, Secret scan; run 39539743832). `mcp__github__pull_request_read get_check_runs`로 확인.

## 바뀐 동작(코드로 확인)
- `ManagedStrategy.java:250-258`: `admitsEntry`가 false면 그날 아직 멈추지 않았을 때 `dailyLossReached(account)`만 부르고 빈 신호를 돌려준다. `funnel.signal`, `entriesToday`, `pendingEntryNotional`은 건드리지 않는다(D-130 그대로).
- `ManagedStrategy.java:288-322`: `allowedByLimits`의 일 손실 계산을 `dailyLossReached`로 뽑았다. 계산식(`equity − dayStartEquity) / dayStartEquity`, DECIMAL64, `≤ −dailyLossLimit`이면 `haltedToday = true`)과 순서(마지막 단계)는 이전 코드와 같다(diff 대조).
- `haltedToday`가 쓰이는 곳은 `:128`(날 바뀌면 false), `:171`(연속 손절), `:254`, `:289`(신규 진입 한도), `:318`뿐이다. 청산 `exit()`(`:180-224`)는 `haltedToday`를 보지 않으므로 손절·청산은 영향 없다.
- 선정 없는 조합은 `RunStepper.admits`(`RunStepper.java:160-166`)가 늘 true라 새 분기를 타지 않는다. 바이트 동일 시험 통과로 확인.
- 실전 프로세스 영향: `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`, `autotrade-risk/src/main`에 `com.autotrade.strategy` 참조 0건(grep). `StrategyContext` 운영 구현은 `autotrade-sim`의 `RunStepper.Context`뿐. 즉 이 변경은 지금 실험실 실행기에서만 동작한다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정 로딩·Guard 시험 변경 없음. `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 운영 코드 변경은 전략 내부 진입 한도 판단(`ManagedStrategy.java:250-322`)뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성 변경 없음. 받지 않은 신호는 이전처럼 주문을 만들지 않아 키를 쓰지 않음(`RunStepper.java:146-151` 주석·동작 그대로) |
| A6 | 통과 | 블랙리스트는 `RiskChain`에서 그대로 거절(`블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다` 통과). 새 분기는 신호를 더 내지 않는 방향(빈 목록 반환)이라 자동 주문 경로를 늘리지 않는다. 주문 전 거름이 `admitsEntry`와 따로 남아 있음을 새 시험이 고정(M4로 거름 제거 시 실패 확인) |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 68개 통과 |
| A8 | 통과 | 바뀐 전략 코드는 실전 엔진·감시 프로세스 운영 코드에서 참조되지 않음(grep 0건). 실험실 프로세스 안 동작이며 실전 엔진은 별도 프로세스(D-013) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음. 전략 청산·손절 경로(`exit()`)는 `haltedToday`를 보지 않아 영향 없음 |
| A10 | 통과 | diff 추가 줄에서 appkey·secret·token·password·계좌번호 형태 0건(grep). 시험 데이터는 금액·가상 종목 코드뿐 |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·GuardedOrderGateway, 장애 테스트) 변경 없음, 고정 값 갱신 없음. 기존 시험 삭제·약화 없음(두 시험 파일 diff는 추가 줄만, `-` 줄 0). 새 동작에 실패 경우 포함 시험 있음: 손실 중 받지 않은 신호 뒤 그날 진입 0건·다음 날 진입(M1로 고치기 전 코드에서 실패 확인), 한도 안이면 막지 않음(역방향), 주문 전 거름(M4), 순위 전 대기일·순위 이벤트 순번(M2·M3) |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8의 감지·대응 코드 무변경. 일 손실 한도는 모의 전략 프로필 파라미터이며 실전 계좌 한도(`RiskChain`, Q-008)와 별개. 장애 테스트 8종 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 손실 계산은 계좌 평가금액 비율이라 통화 무관, 날짜는 기존 `market.zone()` 기준(`ManagedStrategy.java:118-130`) 그대로 |
| A14 | 통과 | D-135 문서와 결정 로그 행이 코드와 일치(결정 1~3, `blocked-entries` 뜻, 시험 목록). 리스크 한도 평가 방식이라 상태 "제안·사용자 결정 대기"로 지침 8장 2번에 맞게 표시. ADR-013 영향 절에 후속 링크. `RunSelectionDay` javadoc이 D-135 `blocked-entries` 뜻과 일치. 기획서는 이 수준(전략 내부 확인 시점)을 적지 않아 고칠 곳 없음(`autotrade-spec.md:86, 218`은 프로필 파라미터라고만 씀). 표현 차이는 N1 |

## 점검표 밖 발견
- N1(참고, 비차단): D-135 문서 대안표 안 1의 "확인 시점이 '확인 신호가 온 순간'으로 D-130 전과 같아진다"는 정확히 같지는 않다. D-130 전에는 일 손실 확인이 `allowedByLimits` 마지막 단계라 하루 진입 수 상한·재진입 대기·보유 수 상한에 먼저 걸리면 평가되지 않았다(`ManagedStrategy.java:289-304`). 새 분기는 받지 않은 신호에서 그 앞 단계와 무관하게 평가한다. 그래서 선정 계좌는 D-130 전보다 **더 자주** 확인해 더 일찍 멈출 수 있다(예: 보유 수가 차 있던 중 후보 밖 신호 때 손실 닿음 → 뒤에 자리가 나고 회복해도 그날 진입 없음). 신규 진입만 줄이는 보수적 방향이라 안전 문제는 아니다. 사용자에게 안 1을 설명할 때 "D-130 전과 같거나 더 엄격"으로 고쳐 적기를 권한다.
- N2(참고): D-135 "위험과 한계"대로 신호가 없는 동안 손실이 닿았다 회복하면 멈추지 않는다. 이것은 모의 전략 내부 규칙의 한계이고, 실전 계좌의 일일 손실 한도는 7단계에서 실전 엔진의 계좌 단위 한도(RSK-04, STR-19 "계좌 단위 한도는 계좌 전체 포지션 합산")로 따로 지켜야 한다. 전략 내부 확인에 실전 일일 손실 보호를 맡기지 않는다는 점을 7단계 설계에서 다시 확인할 것.
- N3(참고): D-135가 남긴 7단계 조건(실전 엔진이 전략을 돌리게 되는 PR은 실전 문맥에서도 주문 전 블랙리스트·선정 거름이 `admitsEntry`와 따로 남아 있음을 시험)에 동의한다. 이번 시험(M4로 확인)은 실험실 실행기 근거일 뿐이다.
- N4(절차): `docs/qa/pending.md`에 PR #218 줄이 아직 없다. 보호 경로 변경이라 지침 10·11장대로 사용자 머지·독립 QA 대상이고, D-135 사용자 결정(안 1·2·3)도 머지 전에 받아야 한다.
- 실전 주문 경로, RealTradingGuard, 블랙리스트 규칙, 실전 리스크 한도(`RiskChain`), 장애 대응 규칙 변경 없음.

## 판정
승인
