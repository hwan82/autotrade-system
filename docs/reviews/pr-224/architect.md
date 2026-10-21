# architect 검토: D-137 하루 늦춘 장전 선정 PRE_OPEN_DAILY_LAG1 (KRX 일봉 게시 시각 확인 결과)
- 대상: PR #224 / 커밋 cadc7403 / main(62d81a7) 대비 diff `git diff origin/main...cadc7403` 전체(32파일, +479 −56)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --init-script <mirror.gradle> build` (cadc7403 별도 worktree, 새로 받은 상태) → `BUILD SUCCESSFUL in 3m 19s`, 종료 코드 0. 테스트 태스크 22개 실행.
    - 새 시험 결과 파일 확인: `SelectionProfileTest`(8건)·`DisclosureSelectionCalculatorTest`(8)·`EdgePlanTest`(9)·`LiveSelectionSessionTest`(12)·`SessionSelectionsTest`(7) 실패·건너뜀 0. 새 시험 `하루_늦춘_장전_선정은_단타만이고_기준_데이터일이_하루_앞이다`, `하루_늦춘_판은_전_거래일_원래_판_결과와_같은_입력으로_고른다`, `lagOneRegistrationDiffersFromEdge01OnlyInIdQuestionAndSelection`, `lagOneSelectionUsesDayBeforeCutoffAndMatchesPreviousDayResult` 통과.
    - 보호 시험 `ArchitectureTest`(19)·`ModuleOwnershipTest`(13) 실패 0.
  - 로컬 PostgreSQL 16에 V1~V4 마이그레이션 순서대로 적용(`ON_ERROR_STOP=1`, 실패 0) 뒤 `scripts/ci/schema-smoke.sql` 실행 → 종료 코드 0, `ok: pre-open lag1 selection accepted`, `ok: swing pre-open lag1 rejected`, `ok: at on pre-open lag1 rejected` 출력, 기존 항목 모두 그대로 `ok`.
  - `scripts/ci/protected-paths.txt` 패턴과 변경 파일 대조: 보호 경로 0건.
  - 추가 줄에서 `appkey|secret|token|password|account` 검색: 0건.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL. 스키마는 PostgreSQL 16 + schema-smoke로 따로 확인 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-selection`(순수, `java.time`·`java.util.function`만 추가 import, `SelectionRefresh.java:4-5`)과 `autotrade-sim`뿐. 새 모듈·새 패키지 없음, 의존 방향 변경 없음. `ArchitectureTest`·`ModuleOwnershipTest` 통과, ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 계약 변경 세 가지에 문서 갱신이 같이 있다. ① 선정 프로필 `refresh` 값 추가: ADR-007 2.1 표·2.6 적용 범위 표(`docs/adr/ADR-007-selection-combination.md:57,170,176`), ADR-012 3.7·배치표 1·3b, D-137, `selections/README.md`, `docs/strategy/selection-catalog.md` ② DB: 새 `V4__selection_refresh_pre_open_lag1.sql`(V2·V3 불변, CHECK 두 개 교체), ADR-007 이전 표(`:130`), schema-smoke 3건 ③ REST: 카탈로그 API가 `SelectionRefresh.values()`를 그대로 내고(`SelectionCatalogController.java:27`) 화면 이름표 `format.js:55`·안내 `selections.js:293` 갱신. 갱신 주기 목록은 스타일로 거르는 기존 `selections.js:182`가 `allows`를 따른다. Java `SelectionRefresh.intraday()`(`:33`)와 DB CHECK `(style='INTRADAY') = (refresh IN (...))`가 같은 집합. 내부 API `SelectionPreparer.prepare` 서명 변경(`LocalDate cutoff` → `UnaryOperator<LocalDate> previousTradingDay`)은 호출부 3곳(`LiveSelectionPrep.java:206`, `RecordingExperiment.java:313`, 시험) 모두 갱신 |
| B4 | 통과 | 새 계산은 `SelectionRefresh.infoDate/cutoff`(`SelectionRefresh.java:50-65`)가 거래소 캘린더 함수만 받는다. 시스템 시계·난수 없음. `PRE_OPEN_DAILY`의 `infoDate`는 `applyFrom` 그대로라 `SelectionCalculator.calculate`(`:104-105`)의 기존 경로는 계산이 같다(`cutoff = days.previous(applyFrom)`). 골든·기존 시험 파일 변경은 `SessionSelectionsTest`의 서명 맞춤(같은 값 `d -> d.minusDays(1)`)뿐이고 모두 통과. 미래 정보: LAG1은 asOf = T′(T의 직전 거래일)로 일봉 c = T′의 직전 거래일, 공시 끝날 T′−1, 재무 `FinancialsAt(asOf)`(`SelectionCalculator.java:340`)라 원래 판보다 정보가 하루 더 적다. `DisclosureSelectionCalculatorTest`가 T 당일 공시 제외와 "원래 판의 T′ 결과와 선정·평가·입력이 같음"을 확인 |
| B5 | 해당 없음 | 금액 계산 추가·변경 없음(edge의 `net`은 그대로, `c` 계산만 바뀜 `SelectionEdgeStudy.java:267`) |
| B6 | 통과 | 새 동작마다 시험: 갱신 주기 규칙·해시 분리·`infoDate/cutoff`(주말 건너기, `INTRADAY_AT` 예외) `SelectionProfileTest`, 계산 동치·STALE `DisclosureSelectionCalculatorTest`, 준비 작업에서 원래 판 STALE·LAG1 WRITTEN과 날짜 전환 지점 판정(`SessionSelections.apply`) `LiveSelectionSessionTest`, EDGE04 등록이 EDGE01과 키가 같음 `EdgePlanTest`, DB CHECK schema-smoke. 시험 삭제·`@Disabled` 없음(diff 확인) |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 장애 대응, 증권사 연동, 보호 시험·보호 경로 변경 없음(protected-paths 대조 0건). 바뀐 것은 실험실 선정 계산·준비와 문서뿐 |
| B8 | 통과 | 추가 줄에 키·계좌·토큰 없음(위 검색 0건). 선정 프로필·등록 파일은 공개 수치만 |

## 점검표 밖 발견
설계 판단과 경계(차단 아님):
- 정의를 "일봉만 하루 앞당김"이 아니라 "정보 시점 자체를 T′로 옮김"(`infoDate`)으로 둔 것은 맞다. 공시 `disclosure-count` w1 구간이 이틀로 늘어 같은 공시를 두 번 세는 문제를 피하고, 결과가 "T′에 적용할 원래 판 결과 + 적용일만 T"로 정확히 정의돼 시험으로 고정된다.
- 기준 데이터일을 등록 단위 하나에서 선정마다로 바꾼 범위가 빠짐없다. `previousTradingDay(date)`로 c를 하나로 정하던 곳을 모두 찾았다: `SelectionPreparer.java:182`, `SessionSelections.java:283`, `SelectionEdgeStudy.java:267`, `LiveSelectionPrep.java:153-155`(`latest` 스냅샷 관문은 가장 이른 c로), `RecordingExperiment.java:313`. 남은 `previous(t)` 사용처 `WatchlistCandidatesMain.java:165`은 `kr-watchlist-turnover@1`(PRE_OPEN_DAILY) 전용이라 영향 없음.
- `LiveSelectionPrep.java:155`의 `.orElseThrow()`는 `files.selections()`가 비면 던지지만, 앞의 `setup.hasSelections()`(`:135`)가 비어 있지 않음을 보장하고 `RecordingExperiment.selectionProfiles`(`:405`)는 `INTRADAY_AT`만 빼며 `selectionCombinations`(`:382`)가 `preOpen()` 아닌 일봉 갱신 주기를 거부하므로 `cutoff()`가 `INTRADAY_AT` 예외를 낼 길도 없다.
- 섞인 등록(원래 판 + LAG1)에서 원래 판만 STALE이고 LAG1은 결과가 있을 때 `failedWithSameInputs`(`SelectionPreparer.java:102-121`)가 같은 입력 키로 다시 읽지 않는다. 5분 준비 루프가 힙을 반복해 쓰지 않는다(architect PR #197 R2 유지).

사소함(이 PR에서 고치지 않아도 됨, 다음에 그 파일을 만질 때):
- m1 `SelectionEdgeStudy.java:59` 클래스 주석 "기준 데이터일 c = T의 직전 거래일"이 LAG1(T−2)을 반영하지 않는다. 코드(`:267`)와 보고서 `c` 열은 맞다.
- m2 `autotrade-ui/.../pages/live.js:234` 주석 "장전 선정(PRE_OPEN_DAILY, D-124)"에 LAG1이 빠져 있다(동작 무관).
- m3 기준 데이터일 판정은 거래소 캘린더(`ExchangeCalendar.previousTradingDay`)로, 계산기는 스냅샷 `TradingDays.previous`로 한다. 둘이 어긋나면 `SessionSelections.apply`가 잡는 구조는 D-124부터 그대로다. LAG1은 직전 거래일을 두 번 거치므로 어긋남이 생기면 영향 날짜가 하루 더 늘 뿐이고 새 위험은 아니다.
- 확인하지 않은 것(판정에 쓰지 않음): EDGE04 실행 자체(사용자 PC 몫, 결과 전 등록이라 실행하지 않는 것이 맞다), PR 본문·커밋 메시지의 KRX 게시 시각 주장(사용자 PC 콘솔 사실로 D-137에 적힘. 코드 판정과 무관).

## 판정
승인
