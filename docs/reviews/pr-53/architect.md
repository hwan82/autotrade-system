# architect 검토: KRX 2027년 거래소 캘린더 수록과 연간 갱신 절차 (D-064)
- 대상: PR #53(호출자 지정 번호) / 브랜치 `claude/project-thread-h3g45k` 커밋 666d2c6846c4c4dc5890f27122252cc61d26c55d / `git diff origin/main...HEAD`(origin/main 4a87f60 기준, 커밋 1개, 9파일 +114 −13)
- 점검표: B
- 직접 실행한 것:
  - 스크래치 폴더에 격리 복제본(`git clone` 뒤 666d2c6 체크아웃)을 만들어 빌드했다. 복제본의 `origin/main`은 원 저장소의 `refs/remotes/origin/main`(4a87f60, `git fetch origin main`으로 GitHub와 맞춘 뒤)으로 맞췄다. 원 저장소의 로컬 `main` 브랜치는 이 이력과 공통 조상이 없는 별개 커밋(157420b)이라 기준으로 쓰지 않았다
  - 1차 `./gradlew build --no-daemon` → BUILD FAILED. 원인은 Maven Central 429(`archunit-junit5-engine:1.4.1` POM 내려받기 실패, `:autotrade-architecture-test:test` 의존성 해석 단계). PR 코드와 무관
  - 2차 `./gradlew build --no-daemon --continue` → **BUILD SUCCESSFUL**(EXIT 0, 130 tasks, 45 실행·85 최신). 테스트 결과 XML 합계 tests 887, failures 0, errors 0, skipped 0(19개 모듈). `ArchitectureTest`, `ModuleOwnershipTest` 실행·통과
  - 새·바뀐 테스트 통과 확인: `ExchangeCalendarTest#KRX_2027_휴장일은_월력요항_공휴일과_연말_휴장_16일이다`, `#연말_자정에도_장_시간_보호가_판단할_수_있다`, `#수록_범위_밖은_추측하지_않고_실패한다`, `RunGuardTest#캘린더_수록_범위_밖이면_판단할_수_없어_막는다`, `ExperimentGateTest#캘린더_수록_범위_밖이면_판단할_수_없어_멈춘다`
  - 2027년 날짜 독립 대조: 요일 계산(신정 금, 3/1 월, 5/5 수, 8/15 일, 10/3 일, 10/9 토, 12/25 토, 12/31 금)과 대체공휴일 규칙으로 16일을 다시 셌고 일치. PR이 출처로 든 세계일보 기사(2026-06-29)를 직접 열어 연휴 범위(설 2/6~2/9, 노동절 연휴 3일, 제헌절 연휴 3일, 8/14~16, 10/2~4, 10/9~11, 12/25~27)가 PR 날짜와 맞는 것을 확인했다. 부처님오신날 5/13, 추석 9/14~16은 기사에 없어 이 검토에서 1차 자료로 확인하지 못했다(모두 `verified:false`로 남아 있어 판정에 영향 없음)
  - PR 본문·커밋 메시지·D-064 설명은 판정 근거로 쓰지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 복제본 2차 빌드 성공(위). 1차 실패는 Maven Central 429로 의존성 해석 단계 실패이며 재시도에서 재현되지 않음 |
| B2 | 통과 | 코드 변경은 번들 리소스(`krx.json`, `us.json`)와 테스트뿐. 새 의존·새 모듈·ArchUnit 규칙 변경 없음. 실전 엔진 운영 코드는 캘린더를 읽지 않는다(`autotrade-app-engine/src/main`에 `ExchangeCalendar`·`KrxMarketRules` 참조 없음, 게이트웨이는 `zone()`만 씀 Gateway.java:63) |
| B3 | 해당 없음 | 캘린더 JSON 형식·`ExchangeCalendar` API·설정 키·스키마·REST 변경 없음(내용 데이터만 추가). 문서는 ADR-003 남은 위험·후속 표, 결정 로그 D-064, 수집기 runbook 3.6을 갱신. 다만 같은 사실을 적은 다른 현행 문서 2곳이 갱신되지 않음(아래 F2) |
| B4 | 통과 | 시계·난수 코드 변경 없음. 새 테스트는 고정 `Instant`로 `SessionProtection.check`를 부름. sim 골든·결정론 테스트 변경 없이 통과. 캘린더 출처·수록 범위가 바뀌어 `calendarDigest`가 바뀌는 것은 입력 변경이다. 단, 갱신 절차 문서가 다이제스트에 대해 사실과 다른 전제를 둔다(아래 F1) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 2027 휴장 수·대체공휴일·연휴 다음 거래일·연초 10:00 개장·수능일 16:30 폐장·미확인 표시(`ExchangeCalendarTest` :31-46), 연말 자정 보호 판정 경계 4건(:48-63)을 새로 시험. `RunGuardTest`·`ExperimentGateTest`·`수록_범위_밖은_추측하지_않고_실패한다`의 날짜 변경은 수록 범위가 늘어 "범위 밖" 예시를 2028로 옮긴 것으로, 같은 단언(`Refused`/`stopReason` "수록 범위 밖", `CalendarCoverageException`)을 유지한다(약화 아님). 테스트 삭제·`@Disabled` 없음 |
| B7 | risk-ops 검토 필요 | 캘린더는 수집기(`RunGuard`)·실험 실행 명령(`ExperimentGate`)의 장 시간 보호(`SessionProtection`) 입력이고, 이는 장애 설계 5.2 정지 순서 0번(D-058·D-063)에 해당한다. 2027년 휴장일이 `[가정]`이라 "거래일을 휴장으로 잘못 보면 그날 보호가 빠지는" 방향의 위험(D-064 위험과 한계)이 있다. 이 검토 시점에 `docs/reviews/pr-53/risk-ops.md`는 없다 |
| B8 | 통과 | diff `+` 줄은 날짜·이름·공개 URL뿐. 키·토큰·계좌 패턴 없음 |

## 점검표 밖 발견

### F1 (수정 필요, 문서 사실 오류): "캘린더를 바꾸면 `calendar_digest`가 바뀐다"는 날짜만 고친 경우 사실이 아니다
- [사실] `SnapshotSelectionData.calendarDigest`(autotrade-selection/src/main/java/com/autotrade/selection/calc/SnapshotSelectionData.java:93-99)는 시장, `coverageFrom~coverageTo`, `sources` 표만 해시한다. 휴장일·특별 운영일 날짜와 `verified`는 들어가지 않는다. 이 값이 `SnapshotSelectionData` 경로(:82)에서 선정 결과 `inputs.calendar_digest`로 그대로 쓰인다(SelectionCalculator.java:138).
- [사실] 조합 실험 경로의 `SnapshotTradingDays` 다이제스트(autotrade-sim/src/main/java/com/autotrade/sim/daily/SnapshotTradingDays.java:98-103)는 거래일 목록 전체를 넣으므로 날짜 변경이 반영된다. 즉 두 경로가 다르다.
- 문제: runbook 3.6 2번(docs/runbooks/collector.md:66 부근)은 "공지와 다르면 공지를 따른다"로 날짜만 고치는 일을 연간 절차로 만들면서, 5번(:69)과 D-064 위험과 한계(docs/decisions/D-064-krx-2027-calendar.md:34)는 "캘린더를 바꾸면 `calendar_digest`가 바뀐다"고 적는다. 출처 키를 그대로 두고 날짜만 고치면 `SnapshotSelectionData` 경로의 선정 결과는 거래일이 달라져도 같은 `calendar_digest`로 기록된다. 재현 기록이 다른 입력을 같은 입력으로 보이게 하는 경우다.
- 고칠 방법(둘 중 하나, 추천 ①):
  ① 문서만 고친다: runbook 3.6 2번에 "날짜를 고치거나 `verified`를 바꿀 때는 반드시 해당 날짜의 `source` 키를 새 키(예: `KRX-2027-NOTICE`)로 바꾸고 `sources`에 추가한다(다이제스트는 출처 표와 수록 범위만 본다)"를 넣고, D-064:34와 runbook :69의 문장을 "출처 표나 수록 범위가 바뀌면"으로 정확히 고친다. 다이제스트 범위 보강은 후속 과제로 남긴다.
  ② `calendarDigest`에 휴장일·특별 운영일(날짜·시각)을 넣는다. 선정 결과 `inputs` 계약 값이 바뀌므로 ADR-007 갱신이 먼저 필요하고 기존 선정 결과의 다이제스트가 모두 바뀐다. 이 PR 범위를 넘으므로 이번에는 버린다.

### F2 (수정 필요, 현행 문서 불일치): "KRX 캘린더는 2026년까지"가 두 곳에 남아 있다
- docs/adr/ADR-008-collector.md:83 "KRX 캘린더는 2026-12-31까지라 연말 전에 갱신해야 한다" → 2027-12-31까지와 runbook 3.6 링크로.
- autotrade-sim/src/main/java/com/autotrade/sim/daily/SnapshotTradingDays.java:22 Javadoc "번들 거래소 캘린더는 2026년만 담고 있어" → 연도를 박지 않는 표현(예: "수록 범위가 최근 연도뿐이라")으로.
- D-061·D-063 본문의 같은 문장은 당시 결정 기록이라 고치지 않아도 된다.

### N1 (권고): 노동절 대체공휴일(2027-05-03)은 안전 반대 방향의 가정이다
- 거래소 업무규정 휴장 기준은 "근로자의 날"이고, 관공서 공휴일 대체공휴일을 거래소가 그대로 휴장으로 둘지는 공지 전이다(D-064도 같은 지적). 실제로 개장하면 그날 장 시간 보호가 빠지는 방향이다. risk-ops가 이 1일(그리고 7/19 제헌절 대체공휴일)을 `[가정]` 휴장으로 둘지, 거래소 공지 전까지 보수적으로 거래일로 둘지 판단하도록 risk-ops 검토 요청에 명시하기를 권한다. 판정은 risk-ops 몫이라 이 검토의 수정 요구에는 넣지 않는다.

### 확인한 것(문제 없음)
- `ExchangeCalendar` 생성자가 휴장일·특별 운영일 중복과 겹침, 수록 범위 밖 항목을 거부하므로(ExchangeCalendar.java:47-67) 새 항목 형식 오류는 로드 시 바로 드러난다. 빌드에서 로드 성공.
- 2027-01-04 특별 운영일과 휴장일 겹침 없음. 2027년 선거일 없음(지방선거 2026, 총선 2028).
- `SessionProtection` 판정: 서울 2026-12-31 12:00 Clear, 2027-01-04 08:59 Clear·09:00 Inside, 서울 2027-12-31 00:00 Unknown을 테스트로 확인. 다음 마감 시점(서울 2027-12-31 00:00) 서술이 코드와 맞다.

## 판정
수정 필요(F1, F2). B7은 risk-ops 검토가 별도로 필요하다.
