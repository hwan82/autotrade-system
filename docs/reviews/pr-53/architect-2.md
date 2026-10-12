# architect 재검토: KRX 2027년 거래소 캘린더 수록과 연간 갱신 절차 (D-064)
- 대상: PR #53 / 브랜치 `claude/project-thread-h3g45k` 커밋 72371af(HEAD) / `git diff origin/main...HEAD`(origin/main 4a87f60 기준, 커밋 2개 666d2c6·72371af, 15파일 +265 −19). 직전 검토(`architect.md`, 666d2c6 기준) 뒤 바뀐 부분은 `git diff 666d2c6 72371af`로 따로 봤다
- 기록 위치: docs/reviews/README.md는 재검토를 같은 파일 아래 `## 재검토`로 덧붙이라고 하지만, 호출자 지시로 새 파일에 썼다. 앞 원문(`architect.md`)은 고치지 않았고, 브랜치에 커밋된 `architect.md`가 이 검토자가 쓴 원문과 바이트 단위로 같음을 `diff`로 확인했다
- 점검표: B
- 직접 실행한 것:
  - 스크래치 폴더에 새 격리 복제본을 만들고 `origin/claude/project-thread-h3g45k`(72371af)를 체크아웃했다(원 저장소에서 `git fetch origin main claude/project-thread-h3g45k` 뒤 복제)
  - `./gradlew build --no-daemon --continue` → **BUILD SUCCESSFUL**(EXIT 0, 130 tasks 모두 실행, 캐시 없음). 테스트 결과 XML 합계 tests 889, failures 0, errors 0, skipped 0. `ArchitectureTest`, `ModuleOwnershipTest` 실행·통과
  - 새·바뀐 테스트 통과 확인: `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`, `#연말_자정에도_장_시간_보호가_판단할_수_있다`, `#KRX_2027_휴장일은_월력요항_공휴일과_연말_휴장_16일이다`, `RunGuardTest#거래소_공지로_확인되지_않은_휴장일은_정규장이_있는_날처럼_막는다`, `#휴장일과_주말은_막지_않는다`, `ExperimentGateTest`(실패 0)
  - PR 본문·커밋 메시지·D-064 설명은 판정 근거로 쓰지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 복제본 전체 빌드 성공(위) |
| B2 | 통과 | 새 코드는 `ExchangeCalendar.protectionSession`(autotrade-marketdata/src/main/java/com/autotrade/marketdata/calendar/ExchangeCalendar.java:137-155)과 `SessionProtection.check`의 호출 교체(SessionProtection.java:59)뿐. 순수 모듈 marketdata 안이고 새 의존 없음. 시계 없이 `LocalDate`만 받는다. ArchUnit 규칙 변경 없음 |
| B3 | 실패 | marketdata의 공개 API에 `protectionSession`이 생겼고 `SessionProtection`의 판정 의미가 바뀌었다(미확인 날짜를 넓게 봄). 결정은 D-064 5번에 있지만 캘린더 계약 문서 ADR-003 2.5는 갱신되지 않았고, 같은 절의 "범위: KRX 2026년"(docs/adr/ADR-003-market-data.md:94)도 그대로다. 결정 로그 색인의 D-064 행(docs/decisions/README.md:69)에도 5번 결정이 없다. 아래 R1·R2 |
| B4 | 통과 | 거래일 계산(`session`, `tradingDays`, `isTradingDay`)은 바뀌지 않아 선정·실험 경로 입력이 그대로다. `protectionSession`은 `SessionProtection`에서만 쓰인다(`grep` 결과 main 코드 호출처 1곳). sim 골든·결정론 테스트 변경 없이 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 시험: 미확인 휴장일 Inside(2027-05-03, 07-19), 미확인 수능일 합친 구간 경계 08:00 Inside·16:59 Inside·17:00 Clear, 미확인 연초 개장일 08:00 경계, 확인된 휴장일(2026-06-03) Clear, 확인된 특별 운영일 시각 유지(ExchangeCalendarTest.java:66-80), 수집기 쪽 미확인 휴장일 거부(RunGuardTest). `휴장일과_주말은_막지_않는다`의 날짜를 2026-10-09(미확인)에서 2026-06-03(확인)으로 바꾼 것은 새 의미에 맞춘 것이고 단언은 같다. `연말_자정...`의 Clear 예시를 12:00에서 18:00 KST로 옮긴 것은 12-31이 미확인 휴장일이라 이제 12:00이 보호 구간이기 때문이다. 둘 다 보호를 넓히는 방향이라 약화 아님. 삭제·`@Disabled` 없음 |
| B7 | risk-ops 검토 필요 | 장 시간 보호(장애 설계 5.2 정지 순서 0번, D-058·D-063)의 판정 코드를 바꾼다. risk-ops 1차 원문(`docs/reviews/pr-53/risk-ops.md`)은 "수정 필요"이고, 72371af에 대한 risk-ops 재검토 원문은 이 검토 시점에 없다. 실전 관련 PR이라 사용자 직접 머지 대상 |
| B8 | 통과 | 새 코드·문서 `+` 줄에 키·토큰·계좌 패턴 없음. 추가된 것은 날짜·공개 URL·라벨 문자열 |

## 직전 지적 반영 확인
- **F1 해결.** runbook 3.6 2번(docs/runbooks/collector.md:67)이 "날짜나 `verified`를 바꿀 때 출처 키를 새 키로 바꾼다"와 그 이유(다이제스트는 출처 표와 수록 범위만 해시)를 적었다. 5번(:70)과 D-064 위험과 한계(D-064-krx-2027-calendar.md:36)가 "출처 표나 수록 범위가 바뀌면"으로 정정됐고, 다이제스트에 날짜를 넣는 일은 ADR-007 후속 과제로 남겼다. 코드(SnapshotSelectionData.java:93-99, SnapshotTradingDays.java:98-103)와 일치한다.
- **F2 해결.** ADR-008-collector.md:83이 "2027-12-31까지, 서울 2027-12-31 00:00 전에 갱신, runbook 3.6"으로, SnapshotTradingDays.java:22 Javadoc이 "최근 연도만(krx.json 2026~2027, D-064)"으로 바뀌었다.
- **N1(권고)** 은 risk-ops F1로 다뤄져 `protectionSession`으로 반영됐다.

## 새 코드 검토 (`protectionSession`)
- 주말은 원래 세션을 그대로 돌려준다. 미확인 휴장일은 정규장 세션, 확인된 휴장일은 빈 값, 미확인 특별 운영일은 `min(open)`~`max(close)`로 정규장과 합치고, 확인된 특별 운영일과 평일은 원래 세션이다. 수록 범위 검사는 `session(date)`를 먼저 불러 그대로 유지된다(범위 밖 → 예외 → `Unknown`).
- 휴장일과 특별 운영일이 겹치지 않음은 생성자가 보장한다(ExchangeCalendar.java:54-59). 미국 캘린더 항목은 모두 `verified: true`라 미국 판정은 바뀌지 않는다(`us.json`에 `"verified": false` 없음).
- 운영 영향: 2026년 KRX 휴장일 대부분이 `verified:false`라, 남은 2026-12-25·12-31과 2027년 휴장일 낮(서울 08:00~16:00)에는 수집기·실험 명령이 시작하지 않는다. D-064 5번과 위험과 한계, runbook에 적혀 있다. 비용은 운영 시간 제약뿐이고 안전 쪽이다.

## 점검표 밖 발견

### R1 (수정 필요, B3): ADR-003 2.5를 새 계약에 맞춘다
- docs/adr/ADR-003-market-data.md:94 "범위: KRX 2026년" → "KRX 2026~2027년(2027은 `[가정]`, D-064)". (직전 검토에서 놓친 줄이다.)
- 같은 절에 `protectionSession` 의미를 한 항목으로 적는다: "`session`·`tradingDays`는 수록된 그대로, 장 시간 보호용 `protectionSession`은 `verified:false` 휴장일을 정규장이 있는 날로, `verified:false` 특별 운영일을 정규장과 합친 구간으로 본다(D-064 5번). `SessionProtection`만 쓴다." 두 메서드가 같은 날짜에 다른 답을 주므로, 다음 사용자가 보호 판단에 `session`을 쓰는 실수를 막으려면 계약 문서에 있어야 한다.

### R2 (수정 필요, B3): 결정 로그 색인 D-064 행에 5번 결정을 넣는다
- docs/decisions/README.md:69 요약에 "장 시간 보호는 미확인 날짜를 넓게 봄(미확인 휴장일 = 정규장 있는 날, 미확인 특별 운영일 = 정규장과 합침, 거래일 계산은 그대로), 공지 전 휴장일 낮에는 수집기·실험 명령 불가"를 더한다. 사용자가 색인으로 자율 결정을 훑어보는 구조(지침 8장)에서, 운영 동작을 바꾸는 결정이 빠져 있다.

## 판정
수정 필요(R1, R2). 직전 F1·F2는 해결됐다. B7은 risk-ops 재검토가 따로 필요하다.
