# architect 재검토 2: KRX 2027년 거래소 캘린더 수록과 연간 갱신 절차 (D-064)
- 대상: PR #53 / 브랜치 `claude/project-thread-h3g45k` 커밋 0f7d4e1(HEAD) / `git diff origin/main...HEAD`(origin/main 4a87f60 기준, 커밋 3개 666d2c6·72371af·0f7d4e1, 17파일 +358 −20, 검토 원문 4개 포함). 직전 검토(`architect-2.md`, 72371af 기준) 뒤 바뀐 부분은 `git diff 72371af 0f7d4e1`로 따로 봤다
- 기록 위치: 호출자 지시로 새 파일에 썼다(docs/reviews/README.md는 같은 파일에 덧붙이기를 권함). 앞 원문은 고치지 않았다. 브랜치에 커밋된 `architect.md`, `architect-2.md`가 이 검토자가 쓴 원문과 바이트 단위로 같음을 `diff`로 확인했다
- 점검표: B
- 직접 실행한 것:
  - 스크래치 폴더에 새 격리 복제본을 만들고 `origin/claude/project-thread-h3g45k`(0f7d4e1)를 체크아웃했다(원 저장소에서 `git fetch origin main claude/project-thread-h3g45k` 뒤 복제)
  - `./gradlew build --no-daemon --continue` → **BUILD SUCCESSFUL**(EXIT 0, 130 tasks 모두 실행, 캐시 없음). 테스트 결과 XML 합계 tests 889, failures 0, errors 0, skipped 0. `ArchitectureTest`, `ModuleOwnershipTest` 실행·통과
  - `git diff --stat 72371af 0f7d4e1 -- '*.java' '*.json' '*.kts'` → 변경 없음. 72371af 뒤 커밋은 문서(ADR-003, 결정 로그 색인)와 검토 원문(architect-2.md, risk-ops-2.md)뿐이다
  - PR 본문·커밋 메시지는 판정 근거로 쓰지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 복제본 전체 빌드 성공(위) |
| B2 | 통과 | 운영 코드는 72371af와 같다. 변경은 순수 모듈 marketdata 안 `ExchangeCalendar.protectionSession`(ExchangeCalendar.java:137)과 `SessionProtection.check` 호출 교체(SessionProtection.java:59)뿐. 새 의존·ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 직전 R1·R2 반영. ADR-003 2.5(docs/adr/ADR-003-market-data.md:94)가 "KRX 2026~2027년(2027 `[가정]`, D-064)"으로 바뀌었고, :96에 `session`(거래일 계산용)과 `protectionSession`(장 시간 보호 전용, 미확인 날짜를 넓게 봄) 두 질의의 의미와 "보호 판단에는 `session`을 쓰지 않는다"가 들어갔다. 코드(ExchangeCalendar.java:137-155)와 일치한다. 결정 로그 색인 D-064 행(docs/decisions/README.md:69)에 보호 판정 확대와 공지 전 휴장일 낮 실행 불가가 들어갔다. 설정 키·스키마·REST 변경 없음 |
| B4 | 통과 | 거래일 계산(`session`, `tradingDays`, `isTradingDay`)은 바뀌지 않았고, `protectionSession`은 `SessionProtection`에서만 쓴다. 시계·난수 변경 없음. sim 골든·결정론 테스트 변경 없이 통과. 다이제스트 범위의 한계는 runbook 3.6 2번(출처 키 교체) 절차와 D-064 위험과 한계에 정확히 적혀 있다(직전 F1 해결) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 시험: `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`(미확인 휴장일 Inside, 미확인 수능일 08:00·16:59 Inside·17:00 Clear, 확인된 휴장일 Clear, 확인된 특별 운영일 시각 유지), `#KRX_2027_휴장일은_월력요항_공휴일과_연말_휴장_16일이다`, `#연말_자정에도_장_시간_보호가_판단할_수_있다`, `RunGuardTest#거래소_공지로_확인되지_않은_휴장일은_정규장이_있는_날처럼_막는다`. 바뀐 기존 테스트 날짜(`RunGuardTest`·`ExperimentGateTest` 범위 밖 예시를 2028로, 휴장일 예시를 확인된 2026-06-03으로, 연말 Clear 예시를 18:00 KST로)는 모두 같은 단언을 유지하거나 보호를 넓히는 방향이다. 삭제·`@Disabled` 없음 |
| B7 | risk-ops 검토 필요 | 장 시간 보호(장애 설계 5.2 정지 순서 0번, D-058·D-063)의 판정 코드와 입력을 바꾼다. risk-ops 재검토 원문 `docs/reviews/pr-53/risk-ops-2.md`가 72371af 기준 "승인"이다. 그 뒤 0f7d4e1은 문서만 바꿨고 코드·캘린더 데이터는 같다(위 `git diff --stat`). 실전 관련 PR이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장 독립 QA 관문 대상이다 |
| B8 | 통과 | diff `+` 줄에 키·토큰·계좌 패턴 없음. 추가된 것은 날짜·공개 URL·라벨 문자열·문서 |

## 직전 지적 반영 확인
- R1 해결: ADR-003 2.5 범위 문구와 `protectionSession` 계약 항목(위 B3).
- R2 해결: 결정 로그 색인 D-064 행 보완(위 B3).
- 1차 F1·F2는 `architect-2.md`에서 해결 확인했고 이번 커밋에서 되돌려지지 않았다.

## 점검표 밖 발견
- 없음(수정 요구 없음).
- 기록: `calendar_digest`에 휴장일 날짜를 넣는 일(ADR-007 계약 변경)은 후속 과제로 남아 있다(D-064 위험과 한계). 지금은 runbook 3.6 2번 절차로 막는다.

## 판정
승인. 실전 관련 PR(장 시간 보호 판정)이라 머지는 risk-ops 승인(`risk-ops-2.md`)과 독립 QA 뒤 사용자가 직접 한다.
