# QA 검토: runbook 3.6 수능 급변경·연기 즉시 반영 단계와 보도자료 발표 시점 문구 (PR #55 QA Q1·R1)
- 대상: PR #56 / 커밋 b90e52eb304daf5e04f4f2f610e8dd21d067af6f / main 대비 diff 전체(merge-base 0d6b3647fac71abf623381c51011b4710e7a2997)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 운영 코드는 바꾸지 않지만 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10, D-058·D-063·D-064)의 판정 입력 `krx.json`을 갱신하는 절차이고, 이 PR은 "반영 전 수동·예약 실행 중지"라는 장애 대응 운영 규칙을 새로 넣는다. 지침 10장에 따라 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-56/architect.md`, `architect-2.md`, `risk-ops.md`, `risk-ops-2.md`)은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...origin/pr56 --stat`: 6파일, +170 −2. `docs/runbooks/collector.md`(+2 −2), `docs/decisions/D-064-krx-2027-calendar.md`(+1), 검토 원문 4개(신규). `*.java`·`*.json`·`*.kts`·스키마·설정 키 변경 0건
  - main 이동 확인: PR base(0d6b364) 뒤 main에 `5b26e95`(#57, gateway build.gradle.kts·검토 원문·실험 보고서)가 들어왔다. `git merge-tree --write-tree origin/main origin/pr56` 충돌 없음, GitHub mergeable_state `clean`. 겹치는 파일 없음
  - `./gradlew build`(head b90e52e): Maven Central 429로 10회 실패(실패 로그마다 429 확인) 뒤 11회째 **BUILD SUCCESSFUL**(130 tasks). 재시도 루프는 하나만 돌렸다. 테스트 결과 XML 합계 tests 890, failures 0, errors 0, skipped 0
  - runbook 새 문장의 사실 대조(코드):
    - "캘린더는 jar에 묶인 리소스": `ExchangeCalendar.java:81-84` `getResourceAsStream("krx.json")`로 클래스패스에서 읽는다. 외부 덮어쓰기 경로 없음 → 맞다
    - "`installDist`로 설치본을 다시 만들어야 예약 실행에 반영": 예약 작업은 `autotrade-app-collector/build/install/autotrade-collector/bin/...`를 실행한다(`collector.md:79-81`), `autotrade-app-collector/build.gradle.kts:8-13` application 플러그인 → 맞다
    - "캘린더에 없는 새 시험일은 정규장 보호만 받아 거래소가 1시간 늦추면 서울 16:00~17:00 보호가 빠진다": `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137-155`)은 특별 운영일이 없으면 정규장(09:00~15:30, `krx.json:6-7`)을 돌려주고, `SessionProtection`은 개장 60분 전·폐장 30분 뒤를 더한다(`SessionProtection.java:21-22`, `:64-65`) → 08:00~16:00. 10:00~16:30 실제 장이면 보호가 17:00까지여야 하므로 16:00~17:00 공백 → 맞다
    - "`verified: false` 유지": 미확인 특별 운영일은 정규장과 합쳐(`ExchangeCalendar.java:148-152`) 08:00~17:00 보호 → 새 날짜에 대해 넓은 쪽이라 맞다
    - 1번 "약 2년 3~5개월 전": 2024-08-16 → 2026-11-19 약 2년 3개월, 2025-06-02 → 2027-11-18 약 2년 5개월. 두 보도자료 날짜는 PR #55 QA에서 KDI 게재본으로 1차 대조한 값과 같다(`krx.json` 출처 `KRX-CSAT-2026-MOE`, `KRX-CSAT-2027-MOE`) → 맞다
  - 캘린더 소비자 grep(`src/main`, `ExchangeCalendar` 참조): 수집기(`CollectorMain`, `RunGuard`), 실험(`ExperimentMain`, `ExperimentGate`), 게이트웨이(`Gateway`는 `zone()`만, `ReplayCheck.java:135-141`은 `session(date)`), sim·selection·marketdata 내부. `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-app-lab`, `autotrade-risk` 0건
  - diff에서 `@Disabled`·key·secret·token·8자리 이상 숫자 grep: 매치는 검토 원문 안의 "검색 0건" 서술 3줄뿐
  - CI(head b90e52e): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(바뀐 파일은 문서뿐). `:autotrade-risk:test`, `:autotrade-app-engine:test` 통과(위 빌드) |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 통과(위 빌드) |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음(운영 코드 변경 0건) |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 판정 입력(`krx.json`)과 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection`, `RunGuard`, `ExperimentGate`) 변경 없음. 새 단계는 PR #55 QA Q1의 공백(공고 뒤 연기된 새 시험일 서울 16:00~17:00에 단발 명령이 실전 엔진과 같은 PC 자원을 씀)을 날짜 이동 + 반영 전 수동·예약 실행 중지로 막는다. 문장의 공백 설명이 위 코드 대조와 맞다. 남은 공백(옛 날짜가 이미 `verified: true`일 때 08:00~09:00)은 아래 Q1. main보다 보호가 줄어드는 부분은 없다. 프로세스 분리 변경 없음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음. 실전 엔진·감시는 캘린더를 읽지 않음(grep 0건) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(위 grep). CI gitleaks success |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `RunGuardTest`, `ExperimentGateTest`, `ExchangeCalendarTest`, 장애 테스트 그대로. 동작 변경이 없어 새 테스트는 필요 없다 |
| A12 | 통과 | `broker-and-fault-design.md` diff 0줄. 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음. 장애표 #10·5.2 0번 전제("장 시간에는 단발 명령이 돌지 않는다")를 지키는 임시 조치가 늘었다 |
| A13 | 통과 | 시장 분기 코드·금액 계산 변경 없음. 새 문장의 시각은 "서울" 현지 시각으로 명시, 캘린더 `"zone": "Asia/Seoul"` 그대로 |
| A14 | 통과 | D-064 결정(수능일은 교육 당국 시행 계획, 미확인 특별 운영일은 정규장과 합침, 연간 갱신 절차는 runbook 3.6)과 충돌 없음. 새 단계도 3번의 출처 키 교체 규칙을 따른다. `D-064-krx-2027-calendar.md:42` 갱신 이력 한 줄의 사실(2번에 변경·연기 즉시 반영, 1번 "약 2년 3~5개월 전", 데이터·동작 변경 없음)이 diff와 맞다. 동작 변경이 없어 새 결정 번호는 필요 없다. PR #55 QA Q1은 새 시험일 쪽으로 해소, R1은 해소 |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(429 재시도 후), 890건 실패·오류·건너뜀 0 |
| B6 | 통과 | 새 코드 동작 없음(절차·결정 문서뿐). 건너뛰거나 지운 테스트 없음(테스트 diff 0줄) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | diff에 외부 호출 코드가 없다(운영 코드 변경 0건). 새 호출 지점 0건. 캘린더는 번들 리소스로 읽고 네트워크를 쓰지 않는다 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. 절차가 쓰는 확인 출처(교육부·평가원 공지, 거래소 공지)는 사람이 보는 무료 공개 자료다 |
| 8.3 사용자 옵션 제어 | 통과 | 켜고 끌 유료 경로가 없다. 새 설정 키 없음 |
| 8.4 폭주 방지 | 통과 | 새 반복·재시도 경로 없음 |
| 8.5 실주문 비용 | 통과 | 주문 경로 변경 없음. 실전 엔진이 캘린더를 읽지 않아 이 변경이 실주문으로 이어질 경로가 없다 |

## 위험 시나리오 시도 (지침 5장 5번)
1. **거래소 시간 공지가 이미 반영돼 `verified: true`가 된 뒤 전날 연기되는 경우**: 확인된 수능일(10:00~16:30)은 `protectionSession`이 특별 구간 그대로 돌려줘 보호가 09:00~17:00이다(`ExchangeCalendar.java:147-153`, 합치기는 미확인일 때만). 2018학년도 수능처럼 전날 저녁 연기되면 `[추론: 일반 공개 사실, 1차 자료로 대조하지 않음]` 옛 날짜가 바로 다음 날이라 캘린더 반영(PR·QA·사용자 머지·installDist)은 절대 제때 끝나지 않는다. 거래소가 옛 날짜를 정규장(09:00 개장)으로 되돌리면 옛 날짜 서울 **08:00~09:00**에 수집기·실험 명령이 시작될 수 있다(장전 준비 시간과 같은 PC 자원). 새 임시 조치 문장은 "새 시험일 16:00~17:00"만 다룬다. 개발 쪽 risk-ops-2 N1과 같은 결론이다. 매일 예약 실행(18:30 이후)에는 걸리지 않고 main보다 나빠지지 않는다 → Q1
2. **반영 경로가 수집기 설치본에만 적힌 경우**: 실험 명령은 작업 트리 소스로 돈다(`autotrade-app-experiment/build.gradle.kts`의 run 태스크, risk-ops-2 N2와 같음). 게이트웨이 설치본(`stage-2-real-recording.md:19`의 `:autotrade-app-gateway:installDist`)에 든 `ReplayCheck`는 `session(date)`로 그날 정규장 구간을 잡는다(`ReplayCheck.java:127`, `:135-141`). 연기된 새 시험일의 녹화를 옛 설치본으로 리플레이 점검하면 09:00~15:30 기준으로 요약해, 10:00~16:30 실제 장의 마지막 1시간이 구간 밖으로 잡힐 수 있다 `[추론: SymbolSummary의 구간 사용 방식은 이 검토에서 끝까지 추적하지 않음]`. 실전 주문·보호 판정과 무관한 점검 도구 결과라 보호 공백은 아니지만, 2단계 OHLC 대조 같은 판정 증거가 틀릴 수 있다 → Q2
3. **"그날 바로" 반영 절차와 관문 순서**: 캘린더 변경은 장 시간 보호 입력이라 실전 관련 PR(risk-ops·독립 QA·사용자 머지)이다. 연기 공지 후 시험일까지 1주(2017) 또는 2주(2020) `[추론]`라 보통은 끝나지만, 끝나지 않을 때의 임시 조치가 문장에 있어 새 시험일은 덮인다. 임시 조치는 사람이 지켜야 하는 규칙이라 코드가 강제하지 않는다(예약 실행을 끄는 것도 수동). 이 PR 범위에서 더 할 수 있는 것은 없다
4. **옛 날짜를 지우지 않고 새 날짜만 더하는 실수**: 미확인 옛 날짜는 08:00~17:00으로 넓게 막혀 보호 쪽이라 공백은 없다. 반대로 옛 날짜만 지우고 새 날짜 추가를 빠뜨리면 runbook이 설명한 16:00~17:00 공백 그대로다. 출처 키 존재 검사가 없는 점(PR #55 QA·risk-ops N3)은 그대로다

## 점검표 밖 발견
- **Q1 (권고, 절차 — 머지 안 막음)**: runbook 3.6 2번 임시 조치에 "옛 시험일이 이미 `verified: true`면 반영 전 옛 날짜 서울 08:00~09:00에도 수집기·실험 명령을 수동으로 시작하지 않는다"를 더하기를 권한다(위 시나리오 1). 넓히기만 하는 구절이라 거래소가 옛 날짜를 되돌리지 않아도 안전하다.
- **Q2 (권고, 절차)**: 같은 문장의 반영 범위에 "실험 명령은 main을 받은 작업 트리에서 돌리고, 게이트웨이 설치본(`:autotrade-app-gateway:installDist`)도 다시 만든 뒤 바뀐 날짜의 리플레이 점검을 다시 돌린다"를 더하기를 권한다(위 시나리오 2). runbook 64줄이 이미 "리플레이 점검도 이 파일을 읽는다"고 적어 소비자 목록과 반영 단계가 어긋난다.
- **R1 (기록)**: 개발 쪽 architect-2 N1(캘린더 관련 모듈 강제 재실행 때 간헐 실패 2회, 원인 미확인)은 이 검토의 빌드 1회에서는 재현되지 않았다(890/0/0/0). 이 PR은 문서만 바꿔 판정에 영향 없다.

## 판정
승인. 점검표 A 전체·B1·B6과 과금 검사에 실패·확인 못 함이 없다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차)이라 머지는 사용자가 직접 한다. PR #55 QA Q1(새 시험일 16:00~17:00 공백)·R1은 이 PR로 해소됐다. 권고 Q1(이미 확인된 옛 날짜 08:00~09:00 임시 조치), Q2(실험 작업 트리·게이트웨이 설치본 반영과 리플레이 점검 재실행)는 머지를 막지 않는 후속 과제다.
