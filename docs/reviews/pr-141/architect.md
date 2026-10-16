# architect 검토: 수집기: KRX 거래일 빈 목록 수집 중단, 품질 리포트 공백 검사(D-104)
- 대상: PR #141 / 헤드 커밋 f981fe2 / main(45ee853) 대비 diff
  - 참고: 이 복제본의 `origin/main` 참조가 7dfe46d(PR #118 머지)로 낡아 있어, GitHub REST(`repos/hwan82/autotrade-system/pulls/141`)의 base SHA 45ee853로 `origin/main`을 맞춘 뒤 `git diff origin/main...HEAD`를 봤다. 커밋 1개(f981fe2), 13파일 +461/−12.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(/home/claude/rev-arch): **BUILD SUCCESSFUL in 3m 40s**, 종료 코드 0. 수집기 시험 결과 XML: `CollectorReportsTest` 3건, `KrxDailyCollectorTest` 8건, `KrxDailySnapshotBuilderTest` 8건, `CollectorMainTest` 15건, 모두 실패·건너뜀 0.
  - 확인용 임시 시험(`ZzReviewProbeTest`, 실행 뒤 지움, 작업 트리 깨끗함 확인): 서울 2026-10-08 19:00, KOSPI 10-06·10-07 자료 있음, 오늘 10-08만 빈 목록(게시 전)으로 `collect` → `source` → `KrxDailySnapshotBuilder.build` → `CollectorReports`를 돌림. 결과 `DailyWarnings[emptyTradingDays=1, boardGaps=0, massAdjustmentDays=0]`, 표 줄 `| 2026 | 1 | 0 | 1 | 0 | 10-08(거래일!) |`. 아래 F1의 근거.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, BUILD SUCCESSFUL(위). 순수 모듈 금지 의존 검사·ArchUnit 포함 |
| B2 | 통과 | 변경은 `autotrade-app-collector`(main 4파일, test 4파일)뿐. 새 의존은 `com.autotrade.marketdata.calendar.ExchangeCalendar`·`com.autotrade.domain.market.Market`이고, 수집기는 이미 같은 클래스를 썼다(`CollectorMain.java:5,70`). 새 모듈·소유 표 변경 없음, `architecture-test` 통과 |
| B3 | 통과 | 바뀐 계약은 수집기 동작(빈 목록 캐시 규칙)과 품질 리포트 형식(MD-09). 둘 다 `docs/adr/ADR-008-collector.md` 2.3·2.6에 반영, 결정 D-104와 결정 로그 표 행, runbook 3.7·4단계 runbook 3장 4번 추가. 설정 키·DB 스키마·REST·스냅샷 형식 변경 없음. `KrxDailySnapshotBuilder.Stats` 레코드 구성 요소 추가는 수집기 모듈 안 소비자뿐(`CollectorMain.java:129,153`, `CollectorReports`)이고 10인자 생성자를 남겼다(`KrxDailySnapshotBuilder.java:309`) |
| B4 | 통과 | 오늘 날짜는 주입 `Clock`(`KrxDailyCollector.java:54`, 기존). 새 집계는 순서가 정해진 자료구조(`EnumMap`, `TreeMap`, `TreeSet`, `boardGaps.sort(date, board)` `KrxDailySnapshotBuilder.java:342-388`, `CollectorReports.java` `TreeMap`)만 쓰고 `HashSet`은 포함 검사에만 쓴다(`:378`). 통계는 스냅샷 파일·매니페스트에 들어가지 않아 스냅샷 ID가 그대로다(`CollectorMainTest#krx_daily는_일봉_스냅샷과_품질_리포트를_만든다`의 재실행 같은 ID 단언 통과). 난수 없음 |
| B5 | 통과 | 금액 계산 변경 없음. 리포트 비율 표시에 `double`(`CollectorReports.java:164`)을 쓰지만 금액이 아닌 표시용 백분율이다 |
| B6 | 수정 필요 | 새 동작마다 시험이 있다: `KrxDailyCollectorTest#캘린더상_거래일의_오래된_빈_목록은_휴장일로_캐시하지_않고_멈춘다`, `#캘린더_휴장일과_캘린더_범위_밖의_빈_목록은_휴장일로_캐시한다`, `#최근_거래일_빈_목록은_게시_전으로_보되_뒤_거래일에_자료가_있으면_멈춘다`, `KrxDailySnapshotBuilderTest#빈_평일과_시장_하나만_빈_날과_수정_이벤트가_몰린_날을_모은다`, `#공백_없는_자료에는_경고가_없다`, `CollectorReportsTest` 3건. 건너뛰거나 지운 시험 없음. 다만 최근 게시 전 빈 날의 리포트 분류(F1)는 시험이 없고 실제로 틀리게 나온다 |
| B7 | 해당 없음 | 바뀐 파일이 `scripts/ci/protected-paths.txt`의 어떤 패턴에도 걸리지 않는다(수집기 모듈·docs만). 실전 엔진·감시·증권사 플러그인·Guard·블랙리스트·주문 경로를 건드리지 않는다. 수집기는 장 시간 보호(`RunGuard`)를 그대로 쓴다 |
| B8 | 통과 | 새 예외 메시지는 시장과 날짜만 담는다(`KrxDailyCollector.java:97-99,119-120`). 출력은 기존 `redaction.clean`을 거친다(`CollectorMain.java:102`). 리포트는 날짜·건수만(D-050). 시험의 키는 자리표시자(`KEY`, `KRXPLACEHOLDERAAAA`) |

## 점검표 밖 발견

### F1 (수정 필요) 게시 전 최근 빈 날이 "캘린더상 거래일(오류)"로 분류되고 콘솔 경고가 난다
- [사실] `KrxDailyCollector.collect`는 최근 7일 안(오늘 포함) 거래일 빈 목록을 캐시하지 않고 `uncached`로 넘긴다(`KrxDailyCollector.java:93-109`). `source`는 이 빈 응답도 날짜에 넣고(`:155-159`), `KrxDailySnapshotBuilder.build`는 모든 시장이 빈 날을 `emptyWeekdays`에 넣는다(`KrxDailySnapshotBuilder.java:347-350`). `CollectorReports.EmptyDayKind.of`는 캘린더상 거래일이면 무조건 `TRADING`("**캘린더상 거래일(오류)**", javadoc "거래일이 휴장일로 캐시된 것이다")으로 분류하고(`CollectorReports.java:58-77`), `warnings`가 이를 세어 콘솔에 "품질 리포트의 해당 칸을 확인해 주세요"를 붙인다(`CollectorMain.java:162-165`).
- [사실] 검토자 임시 시험으로 재현: 오늘(10-08)만 빈 목록이면 `emptyTradingDays=1`, 표에 `10-08(거래일!)`.
- [추론] 매일 운영(runbook 3.5, 18:30~)에서 KRX가 그날 자료를 아직 게시하지 않았으면 매일 "오류"가 1건 이상 나온다. runbook 3.7은 "캘린더상 거래일(오류)은 0이어야 한다", 3번은 "그 날짜의 캐시 파일을 지우고 다시 돌린다"고 안내하지만 그날은 캐시 파일이 없다. 진짜 오류(이 변경 전에 캐시된 2026년 거래일 빈 목록, D-104 위험과 한계 4번)를 가리려고 만든 칸이 상시 오탐으로 무뎌진다. 데이터 자체는 틀리지 않으므로 실험 입력 오염은 없다.
- 고칠 방향(대안 둘): ① 최근 `RECENT_EMPTY_DAYS` 안이면서 캐시되지 않은 빈 날을 별도 분류 "최근·게시 전(캐시 안 함)"으로 나누고 경고 수에서 뺀다. 판단 근거는 `Result.uncached()`에 그 날 키가 있는지(빌더 밖에서 알 수 있음) — 추천. ② 스냅샷 끝쪽(마지막 자료일 뒤)의 빈 날은 분류에서 빼기 — 간단하지만 "오래된 캐시된 끝쪽 빈 날"을 놓칠 수 있어 버림. 어느 쪽이든 이 경우를 시험으로 고정한다(위 재현 조건 그대로).

### R1 (권고) 새 중단 경로의 종료 코드 시험
- D-104 결정 1번이 "종료 코드 1"을 약속하지만 `CollectorMainTest`에는 이 경로의 시험이 없다. 지금은 일반 `catch (IOException | RuntimeException)`(`CollectorMain.java:101-103`)로 1이 나온다 [추론]. 명령 전체에서 1과 메시지(`캘린더상 거래일에 빈 목록`, 비밀값 없음)를 단언하는 시험 하나를 권한다.

### R2 (권고) 시험이 실제 `krx.json` 내용에 묶임
- 3인자 생성자(`KrxDailyCollector.java:32-34`)가 번들 캘린더를 숨겨 넣어 `KrxDailyCollectorTest`·`CollectorReportsTest`가 실제 `krx.json`(2026-09-22 거래일, 09-24 휴장, 수록 시작 2026-01-01)에 기대고 있다. 캘린더(보호 경로)를 고치면 수집기 시험이 함께 깨질 수 있다. 새 시험은 `ExchangeCalendar.parse`로 만든 작은 시험용 캘린더를 4인자 생성자로 넣는 편이 경계가 분명하다. 운영 경로에서 3인자 생성자를 쓰는 곳은 없다(시험만, `grep new KrxDailyCollector(`).

### R3 (권고, 사소) 행마다 TreeSet 추가
- `boardDays.computeIfAbsent(...).add(idx)`를 행마다 부른다(`KrxDailySnapshotBuilder.java:358-359`). 전체 약 1,100만 행에서 결과는 같지만 하루·시장마다 한 번이면 충분하다. 메모리는 거래일 수 × 시장 수라 ADR-008 2.6의 "행 수와 무관" 주장은 지켜진다.

### 확인한 것(문제 없음)
- 7일 경계: `!d.isAfter(today-7)`(중단)와 `maybeNotPublished = d.isAfter(today-7)`(캐시 안 함)가 서로 맞물려 경계 날(정확히 7일 전)은 중단 쪽으로 간다.
- 재호출 분기(`:75-91`)에서 두 번째 응답이 빈 목록이면 이미 멈추므로 새 검사와 겹치지 않는다.
- `gapBefore`는 시장 공백 날(거래일이라 `dayEvents`에 있음)을 `gapDays` 먼저 보고 돌려주므로 PR #137 사고 모양(03-04 코스닥 공백 → 03-05 경고, 직전 공백 03-04)이 맞게 나온다(빌더 시험).
- 시장 공백은 그 시장의 첫·마지막 등장 사이만 본다. 코넥스(2013-07 개장) 앞 기간은 공백으로 잡히지 않는다.
- 표 머리 6칸과 `c[0..2]`가 `EmptyDayKind` 선언 순서(HOLIDAY, TRADING, UNCOVERED)와 맞는다.

## 판정
수정 필요(F1, B6)
