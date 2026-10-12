# risk-ops 재검토: KRX 2027년 거래소 캘린더 수록과 연간 갱신 절차 (D-064) — 72371af 반영
- 대상: PR #53 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `72371af`(이전 검토 `666d2c6` 뒤 1커밋) / main(`4a87f60`) 대비 diff 전체 (15개 파일, +265 −19. 검토 원문 2개 포함)
- 점검표: A
- 첫 검토 원문: [risk-ops.md](risk-ops.md) (판정: 수정 필요 F1·F2·F3). 브랜치에 커밋된 `docs/reviews/pr-53/risk-ops.md`가 내가 쓴 원문과 바이트 단위로 같은 것을 `diff`로 확인했다(메인이 고치지 않음).
- 직접 실행한 것:
  - 새 clone(스크래치 폴더)에서 `./gradlew build --no-daemon --continue` → **BUILD SUCCESSFUL**(130 tasks). 이번에는 429 없음.
  - 테스트 결과(XML 집계, 실패·오류·건너뜀 모두 0): `:autotrade-risk` 55, `:autotrade-architecture-test` 31, `:autotrade-app-engine` 48, `:autotrade-marketdata` 83(+1), `:autotrade-app-collector` 61(+1), `:autotrade-app-experiment` 11, `:autotrade-selection` 34, `:autotrade-sim` 115.
  - jshell로 번들 캘린더(KRX+US)에 `SessionProtection.check`를 돌렸다. 첫 검토에서 `Clear`였던 시각이 모두 `Inside`로 바뀌었다. 서울 2027-05-03 09:30 → `Inside 08:00~16:00`, 07-19 09:30 → `Inside`, 01-04 08:30 → `Inside 08:00~16:00`, 11-18 08:30 → `Inside 08:00~17:00`. 2026-11-19 수능일 08:30 → `Inside 08:00~17:00`, 2026-12-25 10:00 → `Inside`. 경계: 2027-05-03 16:00 KST(07:00Z) → `Clear`. 확인된 휴장일 2026-06-03 10:00 → `Clear`. 거래일 계산은 그대로다(`isTradingDay(2027-05-03)=false`, 2027 거래일 245일).
  - 판정 진입점 grep: 장 시간 보호를 쓰는 운영 코드는 `RunGuard.java:68`, `ExperimentGate.java:60` 두 곳이고, 둘 다 `SessionProtection.check`만 부른다. `protectionSession`은 `SessionProtection.java:59`에서만 쓴다. `session`·`isRegularSession`을 쓰는 다른 곳(`ReplayCheck.java:137`, `KrxMarketRules`/`UsMarketRules`)은 거래일·정규장 판단용이라 바뀌지 않았다. 실전 엔진·감시는 여전히 캘린더를 쓰지 않는다.
  - `us.json`의 `"verified": false` 0건. 그래서 미국 보호 구간은 이번 변경으로 바뀌지 않는다.

## 실전 관련 PR 여부 (지침 10장)
**실전 관련 PR이다(사용자 직접 머지 대상).** 첫 검토와 같은 이유다. 장 시간 보호(5.2 정지 순서 0번, 장애표 #10, D-058·D-063)의 입력 데이터를 바꾼다. 이번 커밋으로 그 판정 코드(`SessionProtection.check`)까지 바뀌었고, 보호 판정 테스트(`RunGuardTest`)의 단언도 바뀌었다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(diff 파일 15개 중 Guard 관련 0). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 통과 |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 31건 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅을 건드리지 않는다. 운영 코드 변경은 `ExchangeCalendar.java`(메서드 추가), `SessionProtection.java`(호출 1줄), `SnapshotTradingDays.java`(주석)뿐이다 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음. 실전 엔진·리스크 모듈 main에 캘린더 참조 0건(첫 검토 grep 그대로) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·계좌별 킬스위치 변경 없음 |
| A8 | 통과 | F1 해소. 장 시간 보호가 `ExchangeCalendar.protectionSession`을 쓴다(`SessionProtection.java:59`). 이 메서드는 확인 안 된 휴장일을 정규장이 있는 날로 본다(`ExchangeCalendar.java:137-148`). 확인 안 된 특별 운영일은 정규장 구간과 특별 운영 구간을 감싸는 구간(min 개장, max 폐장)으로 본다(`:149-154`). 확인된 날짜와 주말은 `session` 그대로다. 그래서 이 PR이 넣은 `[가정]` 날짜가 틀려도 보호 구간이 빠지지 않는다(위 jshell 결과). 거래일 계산(`session`, `tradingDays`, `SnapshotTradingDays`, 선정)은 바뀌지 않아, 실험 결과 쪽 동작은 첫 커밋과 같다. 대가는 확인 안 된 휴장일 서울 08:00~16:00에 수집기·실험 명령이 돌지 않는 것뿐이다. 수집기 정규 실행 시각(18:30~21:30)과 겹치지 않는다. 프로세스 분리는 그대로다(`autotrade-app-engine/build.gradle.kts:13`) |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절, 5.2의 6번은 변경 없음. 실전 엔진은 캘린더를 읽지 않아 이 변경이나 수록 범위 만료가 보유 종목 손절 감시에 영향을 주지 않는다 |
| A10 | 통과 | diff(문서·JSON·Java·검토 원문)에 키·계좌번호·토큰 없음. 공개 URL과 날짜만 있다 |
| A11 | 통과 | `RunGuardTest#휴장일과_주말은_막지_않는다`의 휴장일이 2026-10-09(미확인)에서 2026-06-03(`verified:true`, `krx.json:27`)으로 바뀌었다. 약화가 아니다. 미확인 휴장일은 이제 막는 것이 의도된 동작이고, 그 반대 단언이 새 테스트 `RunGuardTest#거래소_공지로_확인되지_않은_휴장일은_정규장이_있는_날처럼_막는다`(2026-12-25 → `Refused`)로 들어왔다. "확인된 휴장일은 막지 않는다"는 단언은 남았다. `ExchangeCalendarTest#연말_자정에도_장_시간_보호가_판단할_수_있다`는 시각을 옮겼는데, 12-31(미확인 휴장)이 이제 보호 구간이라서다. 01-04 경계 단언은 23:00Z `Inside`, 22:59Z `Clear`로 더 엄격해졌다. 새 테스트 `#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`가 실패해야 할 경우를 다룬다. 미확인 휴장 5/3·7/19는 `Inside`, 미확인 수능일은 08:00·16:59 `Inside`, 17:00 `Clear`, 확인된 휴장일은 `Clear`, 확인된 특별 운영일은 시각 그대로다. `protectionSession`을 `session`으로 되돌리면 이 테스트와 새 `RunGuardTest`가 실패한다(단언 내용으로 확인). 수록 범위 밖 테스트 3곳(2028 날짜)은 첫 검토 그대로다 |
| A12 | 통과 | 장 시간 보호가 전제("장 시간에는 단발 명령이 돌지 않는다")를 `[가정]` 데이터로 깨지 않게 됐다(A8). 장애표 17종, O1~O8, 장애 테스트 8종의 문구와 전제는 바뀌지 않았다. 미국 캘린더는 미확인 날짜가 없어 O3(서머타임)·O7(조기 폐장) 판정이 그대로다 |
| A13 | 통과 | 보호 구간은 캘린더 현지 시간대로 만든다(`protectionSession`의 `TradingSession(date, zone, …)`, `SessionProtection.java:51`). 금액 계산 변경 없음 |
| A14 | 통과 | F2 해소: D-064 "위험과 한계"가 위험 쪽 경우(5/3 대체공휴일 개장, 특별 운영일 개장 미지연)를 적고, 결정 5번이 그 둘을 막는다고 적었다. 남는 위험(넣지 않은 특별 운영)과 대가(미확인 휴장일 낮 실행 불가)도 적었다(`D-064-krx-2027-calendar.md` 결정 5, 위험과 한계 1·2번째 줄). 이는 코드(`ExchangeCalendar.java:137-155`)와 맞다. F3 해소: `ADR-008-collector.md:83`이 "2027-12-31까지, 서울 2027-12-31 00:00 전 갱신, runbook 3.6, D-064"로 바뀌었다. runbook 3.5에 미확인 날짜 처리 줄이 더해졌다. 결정 로그 README의 D-064 행에는 결정 5번(보호는 넓게)이 아직 없다. 상세 파일로 연결되고 행이 거짓은 아니라 실패로 치지 않는다(N3) |

## 첫 검토 지적 처리
| 지적 | 처리 | 근거 |
|---|---|---|
| F1 미확인 날짜가 보호를 여는 쪽 | 해소 | `ExchangeCalendar.protectionSession`, `SessionProtection.java:59`, 새 테스트 2건, jshell 재확인 |
| F2 D-064 위험 서술 | 해소 | D-064 결정 5번, 위험과 한계 정정 |
| F3 ADR-008 문구 | 해소 | `ADR-008-collector.md:83` |
| N1 수록 범위 만료 자동 경보 | 남음(기록) | 감시에 `coverageTo` 참조 0건. 만료는 닫힌 쪽이라 판정과 무관 |
| N2 엔진이 캘린더를 쓰게 될 때 | 남음(기록) | 7단계 과제 |

## 점검표 밖 발견
- **N1 (기록, 첫 검토 N1 유지)**: 수록 범위 만료 자동 경보가 없다. 다음 마감은 서울 2027-12-31 00:00이다. 감시 구현 때 30일 전 경보를 함께 넣기를 권한다.
- **N2 (기록, 첫 검토 N2 유지)**: 7단계에 실전 엔진이 장 시간 통제를 캘린더로 하게 되면 보호용 판정(`protectionSession`, 넓게)과 거래용 판정(`session`)을 구분한다. 신규 진입은 확실한 정규장에서만, 보유 종목 손절은 어느 판정에도 막히지 않게 해야 한다. 이번 커밋이 두 판정을 나눈 것은 그때도 쓸 수 있는 구조다.
- **N3 (권고, 문서)**: `docs/decisions/README.md`의 D-064 요약 행에 "장 시간 보호는 확인 안 된 날짜를 넓게 봄(결정 5)"을 한 구절 더하면 사용자가 요약만 보고도 미확인 휴장일 낮에 단발 명령이 거부되는 이유를 안다. 머지를 막지 않는다.
- **N4 (기록)**: architect F1(`calendar_digest`가 휴장일 날짜를 해시하지 않음)은 선정 결과의 재현성 문제이고 실전 경로와 무관하다. 지금은 runbook 3.6 2번 절차(출처 키 교체)로 막고, 다이제스트 계약 변경은 후속으로 남았다. risk-ops 판정에는 영향이 없다.
- **N5 (기록)**: 미확인 특별 운영일 처리는 두 구간을 감싸는 구간(hull)이다. 지금 데이터(10:00 개장, 16:30 폐장)에서는 합집합과 같다. 정규장과 겹치지 않는 특별 구간이 생겨도 hull이 더 넓으니 닫힌 쪽이다.

## 판정
승인. 실전 관련 PR(장애 대응: 장 시간 보호·정지 순서 0번의 판정 코드와 입력)이라 지침 10장에 따라 사용자가 직접 확인하고 머지한다. 지침 11장의 독립 QA 관문 대상이다.
