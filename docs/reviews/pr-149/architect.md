# architect 검토: 수집기 게시 전 한 줄 후속(PR #143 architect N1·N2, strategy-researcher R1·R2)
- 대상: PR #149 / 커밋 49d48c2 / main(1bb2f3e) 대비 diff (`git diff origin/main...HEAD`: 3파일, +27 −11)
- 점검표: B
- 직접 실행한 것 (별도 복제본, 작업 폴더와 분리):
  - `./gradlew build`: 처음 4회는 Maven Central 429(Too Many Requests)로 의존성 해석 단계에서 실패(코드 원인 아님), 5회째 `BUILD SUCCESSFUL in 2m 5s`. 22개 모듈 시험 결과 합계 tests=1506, skipped=0, failures=0, errors=0.
  - 변이 시험: `KrxDailyCollector.java:111`의 `if (empty && !calendarSaysHoliday(d))`를 `if (empty)`로 바꾸고 `:autotrade-app-collector:test --tests '*KrxDailyCollectorTest'` 실행 → `여러_시장_중_최근_날_한_시장만_빈_목록이면_멈추지_않고_그_시장_날짜만_게시_전으로_알린다()` 실패(11개 중 1개). 원복 후 통과.
  - CI(49d48c2 check-runs): Gradle build, DB schema, Secret scan and collector boundary, Lab screens 모두 success (참고만, 판정 근거는 위 직접 실행).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. `./gradlew build` BUILD SUCCESSFUL, 1506 시험 0 실패·0 건너뜀 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-app-collector`의 `CollectorMain` 한 곳, import·의존 추가 없음. ArchUnit(`autotrade-architecture-test`) 빌드 안에서 통과 |
| B3 | 해당 없음 | `notPublishedLine`은 패키지 내부 `static` 도우미(CollectorMain.java:226)로 계약 아님. 호출처는 운영 CollectorMain.java:123 한 곳과 시험뿐(grep). CLI 옵션·설정 키·스키마·REST 변경 없음. 콘솔 문구 변경은 runbook(docs/runbooks/collector.md:44)에 반영 |
| B4 | 통과 | 시계·난수 사용 추가 없음. 문구 분기는 `--no-snapshot` 여부만 입력. 스냅샷 생성 경로(CollectorMain.java:127~148)와 KrxDailyCollector는 바뀌지 않아 스냅샷 ID·내용 영향 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 시험 `스냅샷을_만들지_않는_실행의_게시_전_한_줄은_이번_스냅샷이라고_쓰지_않는다()`(N2), 기존 시험의 10-05 휴장일 빈 응답과 `r.uncached()` 단언 추가(R1) — 변이 시험으로 휴장일 제외 분기를 실제로 지킴을 확인. 기존 단언은 인자 `true` 추가 외 약화·삭제 없음, `@Disabled` 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응·보호 경로(`scripts/ci/protected-paths.txt`) 변경 없음. 수집기는 주문 API와 무관 |
| B8 | 통과 | diff에 키·계좌·토큰 없음. 시험의 `"KEY"`는 기존 자리표시자(KrxDailyCollectorTest.java:36), 이번 diff에서 추가되지 않음. CI 비밀값 스캔 success |

## 후속 항목 반영 확인
- PR #143 architect N1: 떠 있던 `uncachedDates` Javadoc을 CollectorMain.java:251(메서드 바로 위)로 옮겼다. `notPublishedLine` 앞에는 Javadoc 하나만 남음. 해결.
- PR #143 architect N2: `--no-snapshot`이면 "지금 스냅샷을 만들면"으로 적는다(CollectorMain.java:123, :247). 해결.
- PR #143 strategy-researcher R1: 10-05(시험 캘린더 휴장일, KrxFixtures.java:19)에 두 시장 모두 빈 목록을 주고, 그날이 캐시 안 한 쪽(`uncached`)을 지났음을 단언한 뒤 `notPublished`에 없음을 `containsExactly`로 확인. 변이 시험으로 확인. 해결.
- PR #143 strategy-researcher R2: runbook에 "모든 시장이 빈 날은 스냅샷이 앞 거래일에서 끝나므로 부분이든 전체든 이 한 줄이 보이면 그날 선정에 쓰지 말라" 추가. 문구가 코드와 맞음을 확인 `[사실]`(KrxDailySnapshotBuilder.java:117~119: 빈 날은 `emptyWeekdays`로 가고 `tradingDates`에서 빠짐). 해결.

## 점검표 밖 발견
- M1 (권고, 막지 않음): 스냅샷 모드라도 한 줄은 스냅샷을 만들기 전(CollectorMain.java:123)에 찍히므로, 뒤에서 장 시간 보호·디스크 검사(:127~128)로 멈추거나 "기간 안에 거래일이 없어 스냅샷을 만들지 않습니다"(:139)로 끝나면 "이번 스냅샷의"라는 표현이 남는다. 그 경우 다음 줄에 멈춤·미생성 메시지가 따로 나오므로 오해 소지는 작고 영향은 표시뿐이다 `[추론]`. 고치려면 한 줄을 커밋 뒤로 옮기거나 미생성 경로에서도 `false`로 찍으면 된다.
- 로컬 빌드는 Maven Central 429로 여러 번 실패했다. 코드 문제는 아니지만 의존성 캐시가 빈 환경에서는 재시도가 필요하다 `[사실]`.

## 판정
승인
