# architect 재검토: 수집기: KRX 거래일 빈 목록 수집 중단, 품질 리포트 공백 검사(D-104)
- 대상: PR #141 / 헤드 커밋 3742cbd / main(45ee853) 대비 diff 전체(커밋 f981fe2, 3742cbd). 앞 검토 원문: [architect.md](architect.md)(헤드 f981fe2, 수정 필요 F1·B6)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD`(origin/main = 45ee853, GitHub PR base와 같음 확인): 17파일 +722/−19. 그중 이번 수정 커밋 3742cbd는 `git diff f981fe2 3742cbd`로 따로 다시 읽었다.
  - `./gradlew build --no-daemon`(/home/claude/rev-arch, HEAD 3742cbd): **BUILD SUCCESSFUL in 59s**, 종료 코드 0. 바뀌지 않은 모듈은 앞 실행의 Gradle 결과를 재사용했고, 수집기 모듈은 다시 컴파일하고 시험했다. 결과 XML: `CollectorReportsTest` 4건, `KrxDailyCollectorTest` 8건, `KrxDailySnapshotBuilderTest` 9건, `CollectorMainTest` 17건, 모두 실패·건너뜀 0. 실행 뒤 작업 트리 깨끗함.
  - 커밋된 `docs/reviews/pr-141/architect.md`가 앞 검토 원문과 같은지 봤다. 45줄이고 F1·B6과 판정 줄이 그대로였다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 HEAD 3742cbd에서 직접 `./gradlew build`를 돌려 BUILD SUCCESSFUL(위). ArchUnit·금지 의존 검사 포함 |
| B2 | 통과 | 운영 코드 변경은 계속 `autotrade-app-collector` 안뿐이다. 3인자 생성자를 지워 `Market` import가 빠졌다(`KrxDailyCollector.java`). 새 모듈 의존은 없다 |
| B3 | 통과 | 품질 리포트 형식(게시 전 칸, 다음 날 이벤트 칸, 직전 공백 전부)이 바뀐 것을 ADR-008 2.6과 D-104 결정 2·3번, 검증 절에 반영했다. `Stats`에 `eventsAfterEmpty`가 붙었고 `AdjustmentDay.gapsBefore`는 List로 바뀌었다. 둘 다 소비자가 수집기 모듈 안뿐이다(`CollectorMain`, `CollectorReports`, 시험의 `KrxScaleProbe.java:60`). 설정 키·스키마·REST·스냅샷 형식 변경은 없다 |
| B4 | 통과 | 새 집계는 `TreeMap`(`eventsAfterEmpty`, `dayEvents.higherEntry`), `TreeSet`(`CollectorMain.uncachedDates`)으로 순서가 정해진다. `Map.copyOf`는 조회에만 쓰고, 리포트 순회는 `emptyWeekdays` 리스트 순서를 따른다(`CollectorReports.gapSections`). 시계는 주입 `Clock`. 스냅샷 파일에는 영향이 없다(`CollectorMainTest#krx_daily는_일봉_스냅샷과_품질_리포트를_만든다`의 같은 스냅샷 ID 단언 통과) |
| B5 | 통과 | 금액 계산 변경 없음. 비율은 표시용 `double` 백분율 그대로 |
| B6 | 통과 | F1 시험: `CollectorReportsTest#게시_전으로_넘긴_최근_빈_날은_오류로_세지_않는다`(pending이면 경고 없음, pending이 아니면 오류 1). 명령 전체 시험 `CollectorMainTest#오늘_자료가_아직_없으면_게시_전으로_적고_오류로_세지_않는다`는 앞 검토의 재현 조건(10-06·10-07 자료, 10-08 빈 목록)과 같고 `10-08[-](게시 전)`, `(거래일!)` 없음을 단언한다. R1 시험: `CollectorMainTest#캘린더상_거래일에_KRX가_빈_목록을_주면_종료_코드_1로_멈추고_그날을_캐시하지_않는다`(종료 코드 1, 메시지, 키 없음, 캐시 없음). M1 시험: `KrxDailySnapshotBuilderTest#직전_공백은_가장_가까운_하나가_아니라_직전_거래일까지_전부다`. 지우거나 건너뛴 시험 없음. 기존 단언은 새 형식으로만 바꿨다 |
| B7 | 해당 없음 | 바뀐 파일 중 `scripts/ci/protected-paths.txt` 패턴에 걸리는 것이 없다(수집기 모듈, docs/adr, docs/decisions, docs/runbooks, docs/reviews). 실전 경로·Guard·블랙리스트·주문을 건드리지 않는다 |
| B8 | 통과 | 새 메시지와 리포트에는 시장·날짜·건수만 들어간다. 새 명령 시험이 stderr와 설정 폴더 전체에 키가 없음을 단언한다(`doesNotContain(KRX_KEY)`, `assertNoKeyAnywhere()`) |

## 앞 검토 지적 처리
- **F1 해결.** `CollectorMain.uncachedDates(r)`가 이번 실행에서 캐시하지 않은 날짜를 모은다(`CollectorMain.java:218-225`). `EmptyDayKind.of`는 이 날짜를 `PENDING`으로 먼저 거르고(`CollectorReports.java:39-41`), `warnings`의 오류 수에서도 뺀다. 아래 둘을 확인했다.
  - [사실] 최근 빈 응답은 날짜와 관계없이 캐시하지 않는다(`KrxDailyCollector.java` `maybeNotPublished`). 따라서 모든 시장이 빈 최근 날은 모든 시장 키가 `uncached`에 들어가 날짜 단위 판정이 맞다. 7일보다 오래된 빈 날은 캐시되거나 중단되므로 `pending`에 들어오지 않는다. 앞 검토가 말한 진짜 오류(이 변경 전에 캐시된 2026년 거래일 빈 목록)는 그대로 `TRADING`으로 남는다(`CollectorReportsTest`의 `Set.of()` 단언).
  - [사실] 표 칸 수가 맞다. 머리는 연도, 빈 평일, 종류 4, 다음 날 이벤트, 날짜로 8칸이고 구분선은 `"---|".repeat(kinds + 4)`다. 행은 2 + (kinds + 1) + 1 = 8칸이고, 빈 표의 행도 8칸이다.
- **R1 해결.** 명령 전체 시험이 있다(위 B6).
- **R2 해결.** 3인자 생성자를 지웠다. 수집기 단위 시험은 `KrxFixtures.calendar()`(`ExchangeCalendar.parse`로 만든 2026년 시험 캘린더)를 쓴다. `CollectorMainTest`의 새 종료 코드 시험만 운영 경로와 같이 번들 캘린더를 쓰고, 그 사실을 주석에 적었다(2026-09-22 거래일). 운영 경로가 번들을 쓰므로 이 결합은 맞다.
- **R3 해결.** 시장 출현을 날마다 `distinct()`로 한 번만 기록한다(`KrxDailySnapshotBuilder.java`).

## 점검표 밖 발견
- strategy-researcher 지적 반영분(M1 `gapsBefore` 전부, R1 `eventsAfterEmpty`)도 코드로 확인했다. `gapsBefore`는 직전 자료 있는 날에서 멈추되, 그날이 시장 공백이면 넣고 멈춘다. 그래서 "가짜 빈 거래일 → 실제 휴장일 → 이 날" 순서에서 둘 다 나온다(시험 통과). `eventsAfterEmpty`는 `dayEvents.higherEntry`로 다음 자료 있는 날의 모든 시장 이벤트 수를 쓴다. 연속 빈 날은 같은 다음 날 값을 함께 쓴다. 이 휴리스틱이 통계적으로 맞는지는 strategy-researcher 판단 영역이라 여기서 판정하지 않는다.
- (참고, 판정 영향 없음) 최근 7일 안의 캘린더 휴장일(예: 10-05)도 캐시되지 않으므로 리포트에서 "캘린더 휴장일"이 아니라 "게시 전"으로 분류된다. 경고 수에는 영향이 없고 다음 실행에서 7일이 지나면 캐시되어 휴장일로 바뀌므로 고칠 필요는 없다.
- (참고) 리포트를 쓰는 다른 경로 `KrxScaleProbe`(시험 보조)는 `Set.of()`를 넘긴다. 운영 경로의 호출은 `CollectorMain.java:145` 하나다.

## 판정
승인
