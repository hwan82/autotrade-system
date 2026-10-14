# architect 검토: PR #104 4단계 결과 파일: 청산 사유 분포와 깔때기 연결 직접 테스트

- 대상: PR #104 / 브랜치 `claude/project-thread-z0h1eg` 머리 `952a539`(커밋 1개) / `git diff origin/main...HEAD` 전체(13개 파일, +333 −36)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 읽기. 바뀐 운영 코드는 `autotrade-sim` 워크포워드(`ExitMix` 신규, `WalkForwardStudy`·`WalkForwardReport`·`WalkForwardResult`·`WindowRun`)와 `autotrade-strategy` 카탈로그(`ExitLog` 신규, `ManagedStrategy` 기록 한 줄), 문서는 ADR-009 2.7 한 문단
  - `./gradlew build` → BUILD SUCCESSFUL(처음 실행은 대부분 UP-TO-DATE라) `./gradlew build --rerun-tasks` 재실행 → BUILD SUCCESSFUL in 1m 55s, 130 tasks 모두 실행, 종료 코드 0. 테스트 결과 XML 합계 1105건, 건너뜀 0, 실패 0, 오류 0. `FunnelCaptureTest` 3, `WalkForwardStudyTest` 7, `PooledCostShareTest` 2, `WalkForwardJudgeEvaluateTest` 4, `DailyCloseLogicsTest` 14, `ArchitectureTest` 19, `ModuleOwnershipTest` 13 모두 통과. 각 모듈 `verifyPureDependencies` 통과
  - 통합 테스트가 `ExitLog` 연결을 실제로 확인하는지 직접 봤다: `WalkForwardStudyTest`에 표 출력을 임시로 넣어 한 번 돌리고 되돌렸다(`git checkout`, 작업 트리 변경 없음). 후보 `none x kr-ma-trend@1`은 로직 청산 19 + 구간 끝 정리 5 = 24, 대조군 `kr-random-daily-swing@1`은 시간·최대 보유 1530 + 구간 끝 정리 72 = 1602, 미상은 둘 다 0. 즉 구간 끝 정리 밖 왕복이 있고 모두 `ExitLog`로 사유가 이어졌다(테스트의 "미상 0" 단언이 공허하지 않다)
  - 날짜 정의 대조: `ManagedStrategy.java:117-118` `today`는 `event.exchangeTime().atZone(market.zone())`의 날짜, `VirtualAccount.java:116` 왕복 청산일은 `fill.exchangeDate()`. 같은 거래소 현지 달력이다
  - 외부 호출자 확인: `WindowRun.of`를 public에서 package-private로 바꿨는데 저장소 전체에서 호출은 `WalkForwardStudy.java:376,395,404,415`뿐이다. `CatalogStrategyFactory`·`ManagedStrategy`는 `autotrade-app-*` 운영 코드에서 쓰지 않는다(grep 결과 없음)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build --rerun-tasks` 직접 실행, BUILD SUCCESSFUL, 1105 테스트 실패·건너뜀 0(위 직접 실행 항목) |
| B2 | 통과 | `ExitLog`는 `autotrade-strategy`(순수 Java, `java.util`·`domain.market.Symbol`만 import, ExitLog.java:3-8), `ExitMix`는 `autotrade-sim`이 이미 의존하던 `strategy.catalog`를 import(ExitMix.java:3-5, 기존 `EntryFunnel` import와 같은 방향). 새 모듈·새 패키지 없음. `ArchitectureTest` 19건·`ModuleOwnershipTest` 13건·`verifyPureDependencies` 통과. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 바뀐 공개 형태는 sim 내부 결과 레코드(`WindowRun`에 `exits` 추가 WindowRun.java:339-342, `NullSummary`에 `exits` WalkForwardResult.java:135-137)와 `ManagedStrategy.exitLog()`(ManagedStrategy.java:104-107) 추가뿐이고 외부 소비자가 없다. 결과 파일 형식 변경(`results.md` 새 표, `runs.tsv` 열 불변)은 ADR-009 2.7(ADR-009-daily-walkforward-runner.md:61)에 반영됐다 |
| B4 | 통과 | 새 코드에 시계·난수 없음. `ExitLog`는 기록만 하고 신호를 바꾸지 않는다(ManagedStrategy.java:219-222, `exitLog.sent`는 사유 결정 뒤 반환 전 기록 한 줄). 분포는 순서 무관 합이고 표 행 순서는 후보 목록 순서와 `nullSummaries` TreeMap 순서다. 백분율은 `HALF_EVEN` 6자리(WalkForwardReport.java:114-115). 기존 골든·결정론 테스트(`WalkForwardStudyTest`의 `determinism: "같음"` 단언 포함) 수정 없이 통과 |
| B5 | 통과 | 새 금액 계산 없음. 개수는 `long`, 비율은 `BigDecimal` 나눗셈(WalkForwardReport.java:114-115). `double` 없음 |
| B6 | 통과 | `ExitLog` 귀속 규칙 단위 테스트(DailyCloseLogicsTest `청산_사유는_왕복의_진입일부터_청산일까지_낸_마지막_매도_신호의_사유다`: 마지막 신호 우선, 청산일 전 신호 없음, 진입일 전 신호 제외, 다른 종목), `FunnelCapture` 직접 테스트 3건(FunnelCaptureTest: 키 불일치 시 예외, 생성 전 실패 Run은 `Probe.ZERO`, 측정 없는 전략은 미상·구간 끝 정리 분리와 합 = 왕복 수. PR #98 architect 권고 m1 반영), 통합 단언(WalkForwardStudyTest.java:155-164: 표 머리, 미상 0, 후보 합 = `runs.tsv` VALIDATION `round_trips` 합). 기존 테스트 3개는 생성자 인자 추가만(PooledCostShareTest.java:21,36-37, WalkForwardJudgeEvaluateTest.java:63) 바뀌었고 단언을 지우거나 약화하지 않았다. `@Disabled`·건너뜀 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동 파일 변경 없음. `ManagedStrategy`는 공용 전략 기반이지만 바뀐 것은 관측 기록뿐이고 신호·상태머신에 영향이 없다. 보호 테스트 변경 없음 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 예외 메시지(WalkForwardStudy.java:321)는 Run ID와 프로필 해시·시드만 담는다(기존 문구 그대로) |

## 점검표 밖 발견

막는 항목은 없다. 아래는 모두 권고다.

- m1 (권고, 범위 밖 사용 시 주의): `ExitLog`는 (종목, 거래일)마다 사유 하나만 남긴다(ExitLog.java:20-22). 일봉 워크포워드에서는 종목당 하루 결정이 한 번이라 맞지만, 분봉·틱 경로에서 같은 날 청산 → 재진입 → 다시 청산이 일어나면 앞 왕복이 뒤 왕복의 사유로 세지고 "미상"으로도 드러나지 않는다. 지금 소비자는 `ExitMix`(워크포워드)뿐이라 현 PR 결과에는 영향이 없다. 장중 전략에 이 표를 쓰게 되면 키를 시각(Instant) 단위로 바꾸거나 왕복 lotId로 잇는 방식을 검토하라. 그때까지 `ExitLog` 주석에 "일 단위 귀속, 같은 날 여러 왕복은 구별하지 못함"을 적어 두기를 권한다.
- m2 (권고, 후속): `ExitLog`는 지우는 경로가 없어 전략 인스턴스 수명 동안 매도 신호 일수만큼 자란다. 백테스트 Run 단위에서는 무시할 크기이고 `FunnelCapture`도 실행기 호출마다 새로 만들어 버리므로(WalkForwardStudy.java:541) 쌓이지 않는다. 다만 `ManagedStrategy`를 실전 엔진처럼 수개월 도는 프로세스에서 쓰게 되면(현재 `autotrade-app-engine` 운영 코드는 카탈로그 전략을 쓰지 않는다) 상한이나 끄는 스위치를 두는 것이 낫다.
- m3 (참고): 구간 중간에 실행기가 전략 밖에서 포지션을 닫는 경로가 생기면(예: 강제 청산), 그 전에 같은 왕복 안에서 거절된 매도 신호가 있었을 때 그 사유로 잘못 세진다. 지금은 전략 밖 청산이 구간 끝 정리뿐이고 그것은 먼저 센다(ExitMix.java:36-39). 새 강제 청산 경로를 만들 때 `windowEndLots`처럼 lotId 집합으로 따로 세도록 함께 고칠 것.
- 참고: 같은 디렉터리에 strategy-researcher 검토 파일(`docs/reviews/pr-104/strategy-researcher.md`)이 미추적 상태로 있다. 이 검토는 그 내용을 근거로 쓰지 않았다.

## 판정
승인
