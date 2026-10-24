# architect 검토: 선정 지표 turnover: 실제 거래대금 ACC_TRDVAL w거래일 평균 (D-139)
- 대상: PR #233 / 브랜치 `claude/project-thread-rpic4o` / 커밋 9da5d384c1e3ff8c785d2af34970cf4e1ab655b8 / `git diff origin/main...HEAD` 전체(25개 파일, +978 −28)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL(캐시로 테스트 UP-TO-DATE).
  - 캐시를 믿지 않으려고 `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL in 3m 14s, 138 tasks 모두 실행. 테스트 결과 XML 합계 1,949건, skipped 0, failures 0, errors 0.
  - 새 테스트: `TurnoverSelectionCalculatorTest` 5건, `SnapshotTurnoverTest` 6건, `SnapshotInputsTurnoverTest` 2건, `SnapshotSelectionDataTest` 5건(새 1건 포함), `ResultBundleReaderTest` 8건(새 1건 포함) 모두 통과.
  - 바뀐 파일 목록을 `scripts/ci/protected-paths.txt` 패턴과 대조 → 걸리는 파일 없음.
  - 테스트 코드에서 지운 줄 3줄은 모두 `SampleReports` 인자 추가에 따른 시그니처 변경. `@Disabled`·시스템 시계·난수·double 추가 없음(diff grep).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build --rerun-tasks` 성공, 1,949 테스트 0 실패 0 건너뜀 |
| B2 | 통과 | 새 클래스 `SnapshotTurnover`는 기존 패키지 `com.autotrade.selection.calc`(autotrade-selection 소유)에 있고 `com.autotrade.marketdata.daily.SnapshotFiles/SnapshotManifest`만 쓴다(SnapshotTurnover.java:3-4, 같은 모듈 `SnapshotDisclosures`와 같은 방향). 수집기 모듈에 의존하지 않으려고 파일 이름을 상수로 복제(SnapshotTurnover.java:44-46). `verifyPureDependencies`·`autotrade-architecture-test` 통과, ArchUnit 규칙·소유 표 변경 없음 |
| B3 | 통과 | 계약 변경 두 가지: (1) 선정 결과 `inputs.turnover` 칸 — ADR-007 2.6 예시·설명(ADR-007:159, 171) 갱신, 조건이 없으면 키를 쓰지 않아 기존 해시 불변(SelectionResult.java:197-199, 테스트 `근사_거래대금과_따로_계산하고_쓰지_않는_선정의_결과에는_turnover_칸이_없다`가 파일 없을 때와 내용 해시 같음까지 확인). 읽는 쪽 두 곳(`SelectionResultFile.java:101-111`, `ResultBundleReader.java:235-245`)을 모두 고쳤다(`"disclosures"`를 읽는 저장소 내 다른 운영 코드 없음, grep). (2) 지표 카탈로그 새 키 `turnover`·Kind `TURNOVER` — selection-catalog.md 1장 0번·5번, 2장 표, 3장 갱신, 화면 이름표(selections.js:13,19), 카탈로그 REST는 `Metric.values()`로 자동 노출(SelectionCatalogController.java:24). 결정 D-139와 README 표 추가. 공시 칸(D-123)과 같은 방식으로 ADR-007을 덧붙여 고친 것이라 새 ADR 번호는 필요 없다고 판단 `[추론]`. 문서 한 줄 불일치는 점검표 밖 F1 |
| B4 | 통과 | 계산은 `TreeMap` 결과·종목 순 정렬 그대로, `SnapshotTurnover`의 `HashMap`은 조회만 하고 반복 순서가 결과에 들어가지 않음(SnapshotTurnover.java:459-464, 561-569). 늦게 읽기(lazy)는 `synchronized`이고 읽은 값이 같아 결과 불변(SnapshotSelectionData.java:225-239, 테스트 `실제_거래대금은_쓰는_선정에서_처음_한_번만_읽는다`). 미래 정보: 창은 c에서 끝나는 w거래일(`tail(ctx.window, w)`, SelectionCalculator.java:480), 골든이 T 당일 1조 행을 무시함을 확인(`실제_거래대금_평균_골든` 700050). 기존 골든 테스트 전부 재실행 통과 |
| B5 | 통과 | 원 금액은 `long`으로 읽고(SnapshotTurnover.java:479-488, `[0-9]{1,19}`만 받음) 합·나눗셈은 `BigDecimal` `WIDE`(DECIMAL128)(SelectionCalculator.java:479-487). 경계 골든: 30억 GE 통과, 2,999,999,999.666667 탈락 |
| B6 | 통과 | 정의·결측 규칙마다 테스트가 있다: 파일 없음 → 전체 실패이고 `STALE_INPUT`으로 시작하지 않음(`거래대금_파일이_없으면_근사로_대신하지_않고_계산_전체가_실패한다`), 마지막 날짜 < c → `STALE_INPUT:`(SelectionPreparer.java:147의 접두어 판정과 맞음), 행 없음 → `GATE_01_NO_DATA`, 정지일 0원 평균 포함, KRX만, manifest SHA·행 수 불일치·형식 오류 8종·중복 행·헤더 거부, 스냅샷 ID 불일치 거부, 실제 스냅샷 폴더 → 계산 → 결과 파일 되읽기 해시 같음(`SnapshotInputsTurnoverTest`). 건너뛰거나 지운 테스트 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·보호 경로를 바꾸지 않음(protected-paths 대조 0건, 바뀐 모듈은 selection·sim·app-lab·ui·docs). 7단계 `SelectionValidity`는 이 칸을 보지 않고 바뀌지 않음(ADR-007:171에 risk-ops 결정으로 남김) |
| B8 | 통과 | 비밀값 없음. 새 코드는 로컬 스냅샷 파일만 읽고 네트워크·키를 쓰지 않음. 테스트 SHA는 `"4".repeat(64)` 같은 자리값 |

## 점검표 밖 발견
- **F1 (경미, 문서)**: `docs/adr/ADR-012-intraday-selection.md:49` 같은 문장 안에 "지표 `turnover`는 2026-10-24 D-139로 넣었다"를 넣었는데, 뒤 문장 "수집 필드 추가를 data-engineer 변경 제안으로 남긴다(6장 후속 F1). 추가되면 새 지표 `turnover`로 넣고…"가 그대로 남아 이미 끝난 일을 앞으로 할 일처럼 읽힌다. 뒤 두 문장을 처음 설계 기록임을 밝히거나 지우면 된다. 동작 영향 없음.
- **F2 (경미, 코드)**: `SnapshotSelectionData.of(...)` 정적 팩터리 두 개(SnapshotSelectionData.java:103-114)는 거래대금 읽기 함수를 넘기지 않는다. 지금 운영 코드 호출자는 없고 모든 운영 경로(조합·워크포워드·엣지·녹화·실시간 모의 준비·LiveReplayCheck·감시 후보)가 `SnapshotInputs.load`를 거쳐 거래대금을 잇는다(grep 확인). 나중에 `of`로 만들면 파일이 있어도 "daily_turnover.csv가 없습니다"로 실패한다(조용히 틀리지는 않고 크게 멈춤). 다음에 손댈 때 `of`에도 잇거나 javadoc에 적기를 권한다.
- **F3 (참고, 힙 추정)**: D-139 "위험과 한계"의 약 120MB 추정은 최종 배열만 센다. 읽는 중에는 `Builder`의 2배 확장 여유와 `build()`의 복사본, `HashMap<String, Builder>` 키가 함께 살아 순간 최대는 그 2~3배일 수 있다 `[추론]`. 일봉 9.7GB 추정에 비하면 여전히 작아 결론(따로 힙 검사 불필요)은 바뀌지 않는다. 실측 계획(v2 EDGE 때)이 있으니 그때 순간 최대도 보기를 권한다.
- **F4 (참고)**: 파일 이름·헤더 상수가 수집기(`KrxDailySnapshotBuilder.java:109-111`)와 선정 모듈(`SnapshotTurnover.java:45-46`)에 복제돼 있고 둘이 같은지 묶는 테스트가 없다. 수집기 실제 writer(`SnapshotWriter`: `Csv.join` + `\n`, 헤더 제외 행 수)와 읽는 쪽 형식은 코드로 대조해 맞음을 확인했다. 한쪽만 바뀌면 manifest 항목이 없어 "파일 없음"으로 크게 멈추므로 위험은 낮다.

## 판정
승인
