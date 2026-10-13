# risk-ops 재검토: runbook 3.6 반영 창 끝 시각·18:30 초과 처리, 옛 시험일 폐장 시각 대조 (PR #65, 첫 검토 `risk-ops.md` 후속)
- 대상: PR #65 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `1fbc0c19d8c8c86837d8ce941e1d8ec42922eafc`. GitHub REST `pulls/65`의 head SHA와 같고, PR은 open, base main, mergeable true, mergeable_state clean이다. merge-base `a6f2602`는 GitHub `branches/main` SHA와 같아 main은 움직이지 않았다. 검토 범위는 `git diff origin/main...HEAD` 전체다. 첫 검토(`adea58a`) 뒤의 변화는 따로 대조했다.
  - 바뀐 파일 5개(REST `pulls/65/files`와 같다):
    - `docs/runbooks/collector.md`(+2 −2)
    - `docs/decisions/D-064-krx-2027-calendar.md`(+1)
    - `docs/decisions/README.md`(+1)
    - `docs/reviews/pr-65/architect.md`(+39)
    - `docs/reviews/pr-65/risk-ops.md`(+105)
  - 첫 검토 뒤 커밋은 3개다:
    - `16df65a`, `2062e5f`: 검토 원문 추가만 있다. `risk-ops.md`는 `2062e5f` 하나만 건드렸다.
    - `1fbc0c1`: `collector.md` 2번·6번 문구와 `D-064` 이력 줄을 바꿨다.
  - `src/main`·`src/test`·`*.json`·`*.kts`·`*.sql` 변경은 0건이다.
  - PR 본문, 커밋 메시지, 메인 Claude의 설명은 판정 근거로 쓰지 않았다. 새 문장마다 코드, 테스트, 직접 계산으로 대조했다.
- 점검표: A
- 직접 실행한 것. 공유 작업 트리는 건드리지 않았다. 격리 clone `/tmp/claude-0/review-pr65-riskops-2`에서 `1fbc0c1`을 checkout했다. clone의 `origin/main`이 낡아 있어, 로컬 저장소의 `refs/remotes/origin/main`(`a6f2602`, GitHub와 같음)을 받아 비교 기준으로 썼다.
  - `./gradlew build --no-daemon`: 종료 코드 0, BUILD SUCCESSFUL in 2m 3s, 130 actionable tasks 130 executed. 새 clone이라 테스트가 실제로 돌았다.
    - 결과 XML 합계: tests 897 / failures 0 / errors 0 / skipped 0.
    - 모듈별(테스트/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 89/0/0/0, `:autotrade-app-gateway` 38/0/0/0, `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0, `:autotrade-app-watchdog` 1/0/0/0.
  - 보호 경계 직접 계산. 빌드된 `autotrade-marketdata`·`autotrade-domain` jar와 Jackson 2.21로 작은 프로그램을 돌렸다. `SessionProtection.check(시각, [bundled(KRX), bundled(US)])`를 서울 시각 24개에 대해 호출했다.
    - 2026-10-13(서머타임 중): 15:59 Inside KRX, 16:00 Clear, 18:29 Clear, 21:29 Clear, 21:30 Inside US.
    - 이튿날 10-14: 05:29 Inside US, 05:30 Clear, 07:59 Clear, 08:00 Inside KRX.
    - 2026-11-10(서머타임 아님): 22:29 Clear, 22:30 Inside US.
    - 이튿날 11-11: 06:29 Inside US, 06:30 Clear, 07:59 Clear, 08:00 Inside KRX.
    - 2026-11-19(수능일, `verified: false`): 07:59 Clear, 08:00 Inside KRX, 16:59 Inside KRX, 17:00 Clear, 18:29 Clear, 21:29 Clear, 22:30 Inside US.
    - 2027-11-18(수능일, `verified: false`): 16:59 Inside KRX, 17:00 Clear.
  - CI(head `1fbc0c1`, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16) 모두 completed/success.
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}`를 grep했다. 4건이 걸렸고 모두 검토 원문 안의 grep 설명 문구와 커밋 SHA다. 비밀값은 0건이다.

## 첫 검토 이후 변경의 사실 대조

### 6번: 반영 창에 "남겨 둔 옛 시험일은 17:00" (`collector.md:71`, architect N1)
- [사실] 2번에서 남겨 둔 옛 시험일은 `verified: false` 특별 운영일이다. 그래서 `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137`)이 정규장과 합쳐 08:00~17:00을 보호한다.
- [사실] 2026-11-19·2027-11-18 계산이 16:59 Inside, 17:00 Clear로 이 내용과 같다.
- [사실] 테스트 `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`가 이번 빌드에서 통과했다.
- 문구가 코드 판정과 같아졌다. `replayCheck`·`build`처럼 보호를 보지 않는 반영 작업이 17:00 전에 시작하지 않게 된다.

### 6번: "18:30 전에 끝나도록 시작한다" (architect N2)
- [사실] 18:30은 매일 예약 실행 시작 시각이다(`collector.md` 3.5 "서울 18:30~21:30 사이"). 끝나는 시각 조건이 더해져 예약 실행과 겹치는 경우를 줄인다.

### 6번: "18:30까지 끝나지 않으면 예약 실행을 미루고, `replayCheck`는 미국장 보호 시작(서머타임 중 21:30, 아닐 때 22:30) 전에 멈춘다" (첫 검토 N2)
- [사실] 21:30·22:30은 위 계산의 US Inside 시작 경계와 같다(2026-10-13 21:30, 2026-11-10 22:30). 미국장 보호는 `us.json` 09:30~16:00 ET에 `SessionProtection.BEFORE_OPEN_MINUTES=60`·`AFTER_CLOSE_MINUTES=30`(`SessionProtection.java:21-22`)을 더한 구간이다.
- [사실] 미룬 예약 실행은 수집기·실험 명령이다. 둘 다 코드 관문이 있어, 미뤄진 실행이 미국장 보호 구간에 걸려도 스스로 멈춘다:
  - 수집기: `RunGuard`. runbook 3.5에 "실행 중 들어서면 다음 호출 전에 멈춘다(종료 코드 4)"가 있다.
  - 실험 명령: `ExperimentGate`(`ExperimentGate.java` 클래스 주석 "Run 시작 전과 거래일마다 묻는다", D-063).
  - 그래서 "미룬다"는 지시가 실전 엔진과 자원을 다툴 위험을 새로 만들지 않는다.
- [사실] `replayCheck`는 여전히 코드 보호가 없다(`autotrade-app-gateway/src/main`에서 `SessionProtection`·`RunGate`·`stop-request` 참조 0건). 이 구절은 첫 검토 N2가 지적한 사람 절차의 공백을 미국장 쪽에서 닫는다. 근본 해결은 Q-050(`README.md:90`)으로 그대로 남아 있다.
- 남은 공백 두 가지는 아래 R1에 적었다:
  - `build`·`installDist`도 코드 보호가 없는데 멈춤 대상에 없다.
  - 대체 창 05:30/06:30~08:00에는 끝 시각과 멈춤 조건이 없다.

### 2번: 공지 없이 정리할 때 "첫·마지막 체결 시각으로 개장과 폐장 시각을 모두 확인" (`collector.md:67` 끝, 첫 검토 N1)
- [사실] 바뀌기 전에는 첫 체결 시각만 보았다. 이제 폐장(정규장 15:30, 늦춘 시간 16:30)도 대조하므로 첫 검토 N1의 지적이 반영됐다. HTS 분봉 대체 경로도 첫·마지막 봉으로 함께 고쳤다.
- [사실] 이 정리는 그날이 지난 뒤에만 한다. 정리 전에는 미확인 특별 운영일이라 08:00~17:00 보호가 그대로이고, "정리 전에는 그날 녹화를 실험에 쓰지 않는다"도 유지된다. 그래서 보호(안전)에는 영향이 없고 데이터 정확도만 다룬다.
- [추론] 녹화에 시간외 체결이 섞이면 "마지막 체결 시각"이 폐장보다 늦게 찍힐 수 있다. `ReplayCheck.java:52`도 정규장 밖 체결(시간외 등)이 녹화에 있을 수 있다고 보고 따로 센다. 아래 R3에 적었다.

### D-064 갱신 이력 (`D-064-krx-2027-calendar.md:45`)
- "첫·마지막 체결 시각", "반영 작업이 18:30을 넘기면 예약 실행을 미루고 `replayCheck`는 미국장 보호 시작 전에 멈춘다"가 runbook diff와 하나씩 맞는다. "데이터·동작 변경 없음"도 사실이다(코드·데이터 diff 0건).

### 첫 검토에서 확인한 내용의 재확인
- 반영 창 16:00~18:30, 05:30/06:30~08:00 경계: 위 계산으로 다시 확인했다.
- 옛 시험일 정리 절차(정규장이면 지움, 늦춘 시간 유지면 `verified: true`, 새 출처 키, 6번 절차): 첫 검토와 문구가 같다.
- `java.exe`를 골라 끝내지 않는 구절: 첫 검토와 문구가 같다. 모든 프로세스가 Gradle `application` 플러그인으로 도는 Java 프로그램이라는 근거도 그대로다.
- Q-050 등록(`README.md:90`): 첫 검토와 문구가 같다.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard 코드·설정·테스트 변경이 없다(diff는 `docs/` 5개 파일). `:autotrade-risk` 55/0/0/0 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경이 없다. `:autotrade-architecture-test` 31/0/0/0 통과 |
| A3 | 해당 없음 | 주문 라우팅·증권사 전환 코드 변경이 없다. 새 문장은 캘린더 항목 정리와 단발 명령의 실행 시간·정지 조건만 다룬다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경이 없다 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경이 없다 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경이 없다 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경이 없다. `:autotrade-app-engine` 48/0/0/0 통과 |
| A8 | 통과 | 판정 코드(`ExchangeCalendar.java:137`, `SessionProtection.java:21-22`·`:52`, `RunGuard`, `ExperimentGate`)와 판정 입력(`krx.json`, `us.json`)은 그대로다. 이번 변경은 사람 절차로 지키는 범위를 넓힌다: ① 반영 창 시작을 남겨 둔 옛 시험일에도 17:00으로 맞춤(계산 경계와 일치) ② 18:30 전에 끝나도록 시작 ③ 18:30을 넘기면 예약 실행을 미루고, 코드 보호가 없는 `replayCheck`를 미국장 보호 시작(21:30/22:30, 계산으로 확인) 전에 멈춤. 미룬 예약 실행은 `RunGuard`·`ExperimentGate`가 보호 구간에서 멈춘다. main보다 보호가 줄어든 곳은 없다. 남은 공백(`build`·`installDist` 미포함, 아침 창의 08:00 멈춤 조건)은 R1, 근본 해결은 Q-050 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경이 없다. 실전 엔진·감시 `src/main`에 `ExchangeCalendar`·`SessionProtection` 참조가 0건(grep)이어서 캘린더 정리가 실전 보호로 이어지지 않는다. 게이트웨이 설치 폴더를 건드리지 않는다는 문장(`collector.md:71`)은 그대로다 |
| A10 | 통과 | diff 추가 줄에 키·계좌번호·토큰이 없다(grep에 걸린 4건은 검토 원문의 grep 설명 문구와 커밋 SHA). CI Secret scan(head `1fbc0c1`) success |
| A11 | 통과 | 테스트 파일 변경 0건, skipped 0. `ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트, `RunGuardTest`·`ExperimentGateTest`·`ExchangeCalendarTest`, 장애 테스트 중 삭제·비활성화·약화된 것이 없다. 코드 동작 변경이 없어 새 테스트 요구도 없다. 새 문장이 기대는 경계는 `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`와 직접 계산 24개 시각으로 확인했다 |
| A12 | 통과 | `broker-and-fault-design.md` diff 0줄. 장애표 17행(#1~#17)·해외 O1~O8·장애 테스트 8종의 문구와 전제가 그대로다. 새 문장은 #10(PC 자원 고갈)과 5.2 0번의 전제("장 시간 보호 구간에는 단발 명령이 돌지 않는다")를 사람 절차로 지키는 범위를 넓힌다. `replayCheck`를 5.2 0번 대상에 넣을지는 Q-050에서 정하고, 정하면 대응표를 함께 바꿔야 한다 |
| A13 | 통과 | 미국 캘린더·`MarketRules`·금액 계산 변경이 없다. 새 숫자 21:30/22:30은 서울 현지 시각이고, `America/New_York` 캘린더의 서머타임 전환을 반영한 값이다(2026-10-13 서머타임 중, 2026-11-10 아님 계산으로 확인) |
| A14 | 통과 | D-064(미확인 특별 운영일은 정규장과 합침, 갱신 절차는 runbook 3.6)와 충돌이 없다. `D-064-krx-2027-calendar.md:45` 이력이 diff와 맞다. 후속 과제 Q-050(`README.md:90`)이 그대로 있다. 동작·데이터 변경이 없어 새 D 번호는 필요 없고, 기획서도 변경 대상이 아니다 |

## 실전 관련 PR 여부
**실전 관련 PR이다(사용자 직접 머지 대상, 독립 QA 관문).** 첫 검토와 같은 판단이다.
- runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 판정 입력 `krx.json`을 갱신하는 절차다.
- 이 PR은 그 절차, 실행 중 명령의 정지 방법, 반영 작업의 시간 제한을 바꾼다. 또 7단계 실전 활성화 판정을 막는 미확정 항목 Q-050을 새로 건다.

## 점검표 밖 발견
- **R1 (권고, 절차 — 머지를 막지 않음)**: 멈춤 조건이 `replayCheck`와 저녁 창에만 걸려 있다.
  - `build`(전체 테스트, 이번 실행 2분 3초)와 `installDist`도 장 시간 보호·정지 요청을 확인하지 않는데 멈춤 대상에 없다.
  - 대체 창 "05:30/06:30~08:00"에는 저녁 창의 "끝나도록 시작"과 "보호 시작 전에 멈춤"에 해당하는 말이 없다. KRX 보호는 08:00에 시작한다(계산 07:59 Clear, 08:00 Inside KRX).
  - 권하는 문구: "반영 작업(`build`·`installDist`·`replayCheck`)이 다음 보호 구간 시작(21:30/22:30 또는 08:00)까지 끝나지 않으면 그 전에 멈추고 다음 창에 이어 한다".
  - 지금도 `build`는 짧고 저녁 창이 길어 실제 위험은 낮다 `[추론]`. 근본 해결은 Q-050의 범위를 반영 명령 전체로 넓히는 것이다.
- **R2 (권고, 절차 — 머지를 막지 않음)**: 6번의 "`replayCheck`를 멈춘다"에 멈추는 방법이 없다.
  - 2번에는 "그 명령의 콘솔 창이나 그 예약 작업만 끝낸다. 작업 관리자에서 `java.exe`를 골라 끝내지 않는다"가 있다. `replayCheck`도 Gradle·JavaExec라 같은 `java.exe`로 보인다.
  - 6번에서도 "멈출 때는 2번처럼 그 콘솔에서 중단한다(`java.exe`를 골라 끝내지 않는다)"를 참조하기를 권한다. 실전 엔진 JVM을 잘못 끝내는 사람 조작을 막는 같은 이유다.
- **R3 (권고, 데이터 정확도 — 실전 무관, 머지를 막지 않음)**: 2번의 공지 없는 정리 절차에 빈 곳이 두 가지 있다.
  - 체결 범위: "녹화의 첫·마지막 체결 시각"이 정규장 체결만인지 정해 두지 않았다. `ReplayCheck.java:52`는 녹화에 정규장 밖 체결(시간외 등)이 있을 수 있다고 보고 따로 센다. 섞이면 마지막 체결이 16:30 뒤로 찍혀 폐장 시각을 잘못 읽을 수 있다 `[추론]`. KIS `H0STCNT0`(`KisStreamProtocol.java:54`)가 시간외 체결을 보내는지는 실측이 없다 `[가정]`. "시간외 체결을 뺀 정규장·종가 단일가 체결 기준"을 적기를 권한다.
  - 세 번째 결과: 확인한 시각이 정규장(09:00~15:30)도 늦춘 시간(10:00~16:30)도 아닐 때의 처리가 없다. 이때는 특별 운영일 항목을 확인한 시각으로 고치고 `verified: true`, 새 출처 키를 쓰게 적기를 권한다.
  - 둘 다 그날이 지난 뒤의 일이라 보호에는 영향이 없다.
- **N1 (절차, 메인 Claude 몫 — architect N4 미해결)**: PR 브랜치의 `docs/qa/pending.md` 표에 PR #65 대기 줄이 없다(표 머리만 있다). `pending.md` 규칙("PR 검토 요청은 그 PR 브랜치의 이 파일에")에 따라 독립 QA 요청 전에 한 줄을 더해야 한다. risk-ops 판정과는 별개다.
- **N2 (기록, 이전 지적 유지)**: 첫 검토 N3·N4는 바뀌지 않았다. 모두 이번 diff 범위 밖이고 머지를 막지 않는다.
  - Q-050의 "7단계 판정 전에 닫는다"는 결정 로그 미확정 표에만 있다. 7단계 관문 검토자(risk-ops, QA)가 D7·A8에서 확인한다.
  - `ExchangeCalendar` 생성자가 `source` 키가 있는지 검사하지 않는다.
  - 수록 범위 만료 자동 경보가 없다(다음 마감 서울 2027-12-31 00:00).
  - 작업 스케줄러 "끝내기"가 하위 `java.exe`까지 끝내는지 Windows 실측이 없다 `[가정]`.

## 판정
승인.
- 점검표 A에 실패와 확인 못 함이 없다(A1~A7 해당 없음, A8~A14 통과).
- 첫 검토 뒤 바뀐 문장 네 개의 사실 주장을 모두 확인했다:
  - ① 남겨 둔 옛 시험일 17:00
  - ② 18:30 전에 끝나도록 시작
  - ③ 18:30 초과 시 예약 실행 연기와 21:30/22:30 전 `replayCheck` 정지
  - ④ 첫·마지막 체결로 개장·폐장 확인
- 확인한 근거:
  - 코드: `ExchangeCalendar.java:137`, `SessionProtection.java:21-22`, `ExperimentGate` 클래스 주석, 게이트웨이 grep 0건, `ReplayCheck.java:52`
  - 테스트: `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`
  - 번들 캘린더로 직접 계산한 보호 경계 24개 시각
  - 격리 clone `./gradlew build`: 897건, 실패 0
- 첫 검토 N1·N2는 반영됐다. main보다 보호를 줄이는 문장은 없다.
- 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차, 반영 작업 시간 제한, 7단계 차단 항목 Q-050)이다. 지침 10장에 따라 사용자가 직접 확인하고 머지하고, 지침 11장의 독립 QA 관문을 거친다.
- R1~R3은 머지를 막지 않는 권고다. N1(`pending.md` 대기 줄)은 QA 요청 전에 메인 Claude가 처리할 절차다. Q-050은 7단계 실전 활성화 전에 닫혀야 한다.

판정: 승인
