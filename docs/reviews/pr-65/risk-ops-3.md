# risk-ops 재검토 3: runbook 3.6 옛 시험일 확인을 09:00~09:29 정규장 체결로, 반영 작업 두 창 모두 끝 시각·멈춤 방법 (PR #65, `risk-ops-2.md` 후속)
- 대상: PR #65 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `3ca5290438da99b682e302be9a77f452f2b76814` / main 대비 diff
  - head SHA가 맞는지: 로컬 저장소에서 `git ls-remote origin`으로 `refs/heads/claude/project-thread-h3g45k`와 `refs/pull/65/head`가 모두 `3ca5290`임을 확인했다.
  - PR 상태(GitHub REST `pulls/65`): open, base `a6f2602`, mergeable true. 조회한 시점에 CI Gradle build가 진행 중이어서 mergeable_state는 `unstable`이었다. 뒤에 다시 조회해 세 검사가 모두 success인 것을 확인했다(아래).
  - main 기준: GitHub `branches/main`은 `a6f2602`이고 merge-base도 `a6f2602`라 main은 움직이지 않았다.
  - 검토 범위는 `git diff origin/main...HEAD` 전체다. 재검토 2(`1fbc0c1`) 뒤의 변화는 `git diff 1fbc0c1 3ca5290`로 따로 대조했다.
  - 바뀐 파일은 7개이고 REST `pulls/65/files`와 같다:
    - `docs/runbooks/collector.md`(+2 −2)
    - `docs/decisions/D-064-krx-2027-calendar.md`(+1)
    - `docs/decisions/README.md`(+1, Q-050)
    - `docs/reviews/pr-65/architect.md`(+39), `architect-2.md`(+46)
    - `docs/reviews/pr-65/risk-ops.md`(+105), `risk-ops-2.md`(+126)
  - 재검토 2 뒤의 커밋은 3개다:
    - `fa3ed44`, `61f7b74`: 검토 원문만 추가했다.
    - `3ca5290`: `collector.md` 2번 끝 문장과 6번 문장, `D-064` 2026-10-13 이력 줄을 바꿨다.
  - `src/main`, `src/test`, `*.json`, `*.kts`, `*.sql` 변경은 0건이다.
  - PR 본문, 커밋 메시지, 메인 Claude의 설명, 이전 검토 원문은 판정 근거로 쓰지 않았다. 새 문장은 하나씩 코드, 빌드, 직접 계산으로 대조했다.
- 점검표: A
- 직접 실행한 것. 공유 작업 트리는 건드리지 않았다.
  - 작업 위치: 격리 clone `/tmp/claude-0/review-pr65-riskops-3`에서 `3ca5290`을 checkout했다. clone의 `origin/main`이 낡아 있어(`157420b`, 공통 조상 없음), 로컬 저장소의 `refs/remotes/origin/main`(`a6f2602`, GitHub와 같음)을 받아 비교 기준으로 썼다.
  - `./gradlew build --no-daemon`: 종료 코드 0, BUILD SUCCESSFUL in 1m 58s, 130 actionable tasks: 130 executed. 새 clone이라 테스트가 실제로 돌았다.
    - 결과 XML 합계: tests 897 / failures 0 / errors 0 / skipped 0.
    - 모듈별(테스트/실패/오류/건너뜀):

      | 모듈 | 결과 |
      |---|---|
      | `:autotrade-risk` | 55/0/0/0 |
      | `:autotrade-architecture-test` | 31/0/0/0 |
      | `:autotrade-app-engine` | 48/0/0/0 |
      | `:autotrade-marketdata` | 89/0/0/0 |
      | `:autotrade-app-gateway` | 38/0/0/0 |
      | `:autotrade-app-collector` | 61/0/0/0 |
      | `:autotrade-app-experiment` | 11/0/0/0 |
      | `:autotrade-app-watchdog` | 1/0/0/0 |

    - `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다()`가 통과했다.
  - 보호 경계를 직접 계산했다.
    - 방법: 이번 빌드의 `autotrade-marketdata`·`autotrade-domain` jar와 Jackson 2.21.4로 작은 프로그램(`Calc.java`)을 돌렸다. 서울 시각 24개마다 `SessionProtection.check(시각, [bundled(KRX), bundled(US)])`를 호출했다.
    - 2026-10-13(서머타임 중): 15:59 Inside KRX, 16:00 Clear, 18:29 Clear, 21:29 Clear, 21:30 Inside US.
    - 이튿날 10-14: 05:29 Inside US, 05:30 Clear, 07:59 Clear, 08:00 Inside KRX.
    - 2026-11-10(서머타임 아님): 22:29 Clear, 22:30 Inside US.
    - 이튿날 11-11: 06:29 Inside US, 06:30 Clear, 07:59 Clear, 08:00 Inside KRX.
    - 2026-11-19(수능일, `verified: false`): 07:59 Clear, 08:00 Inside KRX, 16:59 Inside KRX, 17:00 Clear, 18:29 Clear, 22:29 Clear, 22:30 Inside US.
    - 2027-11-18: 16:59 Inside KRX, 17:00 Clear.
    - 같은 프로그램에서 2026-11-19와 2027-11-18을 따로 보았다. `session()`은 10:00~16:30, `protectionSession()`은 09:00~16:30이다.
  - CI(head `3ca5290`, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16) 세 검사 모두 completed/success.
  - 비밀값 grep: diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}`를 찾았다. 걸린 줄은 모두 검토 원문 안의 커밋 SHA와 grep 설명 문구다. 비밀값은 0건이다.

## 재검토 2 이후 변경의 사실 대조

### 2번: 공지 없이 정리할 때 녹화 원본의 09:00:00~09:29:59 정규장 체결 유무로 판단 (`collector.md:67` 끝, risk-ops-2 R3, architect-2 F1)
- [사실] 녹화에는 장구분 없이 시간외 체결이 섞인다. 그래서 첫·마지막 체결 시각을 쓰지 않는다는 근거가 맞다.
  - 키움 `0B`는 `9081`로 KRX만 남긴다(`KiwoomStreamProtocol.java:219-222`).
  - 장구분은 버리고 가격·수량·시각만 `RawQuote.Trade`로 만든다(`:223-229`).
  - 2026-10-12 녹화의 "정규장 밖" 체결은 모두 15:40:00~15:43:27이다(`experiments/stage-2-replay/2026-10-12.md:51`).
- [사실] `replayCheck` 보고서 칸을 쓰지 않는다는 근거가 맞다.
  - `ReplayCheck.java:128`은 `regularSession` → `ExchangeCalendar.session()`으로 구간을 잡는다.
  - 남겨 둔 미확인 옛 시험일에서 `session()`은 10:00~16:30이다(위 계산). 그래서 보고서가 09:00~09:59 체결을 "정규장 밖"으로 뺀다.
- [사실] 판별 창 09:00:00~09:29:59가 맞는 이유:
  - 정규장일에는 09:00 시가 단일가 체결부터 이 창에 들어간다.
  - 장전 시간외는 정규장일 08:30~08:40이고, 늦춘 날에는 1시간 뒤인 09:30~09:40으로 문서에 적혀 있다(`[가정]` 표시 있음). 어느 쪽이든 이 창 밖이다. 늦춘 날의 장전 시간외가 옮겨지지 않고 08:30~08:40에 남아도 창 밖이다.
  - 그래서 판별 결과는 이 `[가정]`이 틀려도 바뀌지 않는다.
- [사실] NXT 체결이 섞이지 않는다.
  - 키움은 6자리 코드로 KRX 단독 시세를 받는다(`KiwoomStreamProtocol.java:48`).
  - 한국투자는 `H0STCNT0`(KRX, `KisStreamProtocol.java:54`)를 쓴다.
  - 그래서 NXT 메인마켓(09:00:30~) 체결이 판별 창을 오염시키지 않는다.
- [사실] HTS로 볼 때는 KRX 단독 분봉으로 보라는 단서가 붙었다. 통합 분봉이 NXT 시간대를 보여 주는 문제(architect-2 F1 셋째 항목)가 닫혔다.
- [사실] 세 번째 결과("둘 다 아닌 시각")에 처리가 생겼다: 확인한 시각으로 고치고 `verified: true`, 새 출처 키에 확인 방법을 적는다. risk-ops-2 R3 둘째 항목이 반영됐다.
- [사실] 안전 영향은 없다. 정리는 그날이 지난 뒤에만 하고, 정리 전에는 미확인 특별 운영일이라 08:00~17:00 보호가 그대로다(위 계산 2026-11-19). "정리 전에는 그날 녹화를 실험에 쓰지 않는다"도 남아 있다.
- 남은 빈 곳 두 가지(녹화 시각이 UTC, 녹화 공백)는 아래 R1·R2에 적었다. 둘 다 데이터 정확도 문제이고 보호에는 영향이 없다.

### 6번: 반영 작업 두 창의 끝 시각과 멈춤 방법 (`collector.md:71`, risk-ops-2 R1·R2, architect-2 N1·N2)
- [사실] 반영 단계 목록과 시간 조건이 나뉘었다. 반영 단계 목록이 "반영 단계는 다음과 같다:"로 시작하고, 시간 조건은 별도 문단 "반영 작업(`build`, `installDist`, `replayCheck`)을 하는 시간"으로 옮겨졌다(architect-2 N1).
- [사실] 멈춤 대상이 셋 모두로 넓어졌다. 문장이 "셋 다 장 시간 보호와 정지 요청 파일을 확인하지 않으므로"로 `build`·`installDist`·`replayCheck` 모두에 적용된다(risk-ops-2 R1 첫째 항목). `autotrade-app-gateway/src/main`을 다시 grep해도 `SessionProtection|RunGate|stop-request` 참조는 0건이다.
- [사실] 두 창의 시각이 위 계산 경계와 하나씩 맞는다:
  - 저녁 창 16:00(수능일과 남겨 둔 옛 시험일은 17:00)~18:30
  - 새벽 창: 서머타임 중 05:30, 아닐 때 06:30~08:00
  - 다음 보호 구간 시작: 저녁 21:30/22:30, 새벽 08:00
- [사실] 새벽 창에도 "08:00 전에 끝나도록 시작"과 "끝나지 않으면 08:00 전에 멈춤"이 생겼다(risk-ops-2 R1 둘째 항목, architect-2 N2). 07:59 Clear, 08:00 Inside KRX와 맞는다.
- [사실] 멈추는 방법이 적혔다: "2번처럼 그 명령의 콘솔에서 중단하고 작업 관리자에서 `java.exe`를 골라 끝내지 않는다"(risk-ops-2 R2). 실전 엔진 JVM을 잘못 끝내는 조작을 막는다.
- [사실] "18:30을 넘기면 그날 예약 실행은 반영 작업이 끝난 뒤로 미룬다"는 재검토 2에서 확인한 대로 보호를 깨지 않는다. 미뤄진 수집기와 실험 명령은 미국장 보호 구간에 들어서면 `RunGuard`·`ExperimentGate`가 멈춘다.
- [추론] 콘솔에서 `./gradlew build`를 Ctrl+C로 중단하면 테스트 워커는 끝나지만 Gradle 데몬 JVM은 유휴 상태로 남을 수 있다. CPU는 거의 쓰지 않고 메모리만 남으므로 실전 엔진 지연 위험은 낮다. 아래 N2에 적었다.

### D-064 갱신 이력 (`D-064-krx-2027-calendar.md:45`)
- [사실] 이력 줄이 runbook 변경과 하나씩 맞는다: "09:00~09:29 정규장 체결 유무", "첫·마지막 체결 시각과 `replayCheck` 보고서 칸은 쓰지 않음", "두 창 모두 끝나도록 시작하고 다음 보호 구간 전에 콘솔에서 멈춤", "18:30 초과 시 예약 실행 연기".
- [사실] 같은 PR 안에서 고친 줄이므로 머지된 이력을 덮어쓰지 않는다. "데이터·동작 변경 없음"도 사실이다(코드·데이터 diff 0건).

### 이전 확인 사항의 재확인
- Q-050(`README.md:90`): 문구가 그대로이고, "7단계 실전 활성화 판정(D7·A8) 전에 닫는다"도 남아 있다.
- 2번의 `java.exe` 구절, 옛 시험일 정리(정규장이면 지움, 늦춘 시간 유지면 `verified: true`, 새 출처 키, 6번 절차): 재검토 2와 같다.
- 실전 엔진·감시·리스크 `src/main`에 `ExchangeCalendar`·`SessionProtection` 참조가 0건이다(grep). 캘린더 정리가 실전 주문·보호 경로로 이어지지 않는다.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard 코드·설정·테스트 변경이 없다(diff는 `docs/` 7개 파일). `:autotrade-risk` 55/0/0/0 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경이 없다. `:autotrade-architecture-test` 31/0/0/0 통과 |
| A3 | 해당 없음 | 주문 라우팅·증권사 전환 코드 변경이 없다. 새 문장은 캘린더 항목 정리와 단발 명령의 실행 시간·정지 방법만 다룬다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경이 없다 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경이 없다 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경이 없다 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경이 없다. `:autotrade-app-engine` 48/0/0/0 통과 |
| A8 | 통과 | 판정 코드(`ExchangeCalendar.java:137` `protectionSession`, `SessionProtection.java:21-22`·`:52-65`, `RunGuard`, `ExperimentGate`)와 판정 입력(`krx.json`, `us.json`)은 그대로다. 이번 변경은 사람 절차로 지키는 범위를 넓힌다. ① 코드 보호가 없는 반영 명령 셋(`build`·`installDist`·`replayCheck`)을 저녁·새벽 두 창 모두에서 "끝나도록 시작, 넘기면 다음 보호 구간(21:30/22:30, 08:00) 전에 멈춤"으로 묶었다. 경계는 직접 계산 24개 시각과 맞는다. ② 멈출 때 `java.exe`를 골라 끝내지 않도록 했다. main보다 보호가 줄어든 곳은 없다. 근본 해결(코드 관문)은 Q-050(`README.md:90`)이며 7단계 판정 전에 닫아야 한다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경이 없다. 실전 엔진·감시 `src/main`에 `ExchangeCalendar`·`SessionProtection` 참조가 0건(grep)이라 캘린더 정리가 실전 보호로 이어지지 않는다. "장중에 돌고 있는 게이트웨이의 설치 폴더를 건드리지 않는다"(`collector.md:71`)도 그대로다 |
| A10 | 통과 | diff 추가 줄에 키·계좌번호·토큰이 없다. grep에 걸린 줄은 검토 원문의 커밋 SHA와 grep 설명 문구뿐이다. CI Secret scan(head `3ca5290`) success |
| A11 | 통과 | 테스트 파일 변경 0건, skipped 0. `ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트, `RunGuardTest`·`ExperimentGateTest`·`ExchangeCalendarTest`, 장애 테스트 가운데 삭제·비활성화·약화된 것이 없다. 코드 동작 변경이 없어 새 테스트 요구도 없다. 새 문장이 기대는 경계는 `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다()`와 직접 계산으로 확인했다 |
| A12 | 통과 | `broker-and-fault-design.md` diff 0줄. 장애표 17행(#1~#17), 해외 O1~O8, 장애 테스트 8종의 문구와 전제가 그대로다. 새 문장은 #10(PC 자원 고갈)과 5.2 정지 순서 0번의 전제("장 시간 보호 구간에는 단발 명령이 돌지 않는다")를 사람 절차로 반영 명령 셋 전체에 넓힌다. `replayCheck`(와 반영 명령)를 5.2 0번 코드 대상에 넣는 일은 Q-050에서 정하고, 정하면 대응표도 함께 바꿔야 한다 |
| A13 | 통과 | 미국 캘린더·`MarketRules`·금액 계산 변경이 없다. 새 숫자 21:30/22:30, 05:30/06:30은 서울 시각이고, `America/New_York` 서머타임 전환이 반영된 값이다(2026-10-13·14는 서머타임 중, 2026-11-10·11은 아님, 계산으로 확인) |
| A14 | 통과 | D-064(미확인 특별 운영일은 정규장과 합침, 갱신 절차는 runbook 3.6)와 충돌이 없다. `D-064-krx-2027-calendar.md:45` 이력이 diff와 맞고, Q-050(`README.md:90`)이 그대로 있다. 동작·데이터 변경이 없어 새 D 번호는 필요 없고 기획서도 변경 대상이 아니다 |

## 실전 관련 PR 여부
**실전 관련 PR이다(사용자 직접 머지 대상, 독립 QA 관문).** 첫 검토, 재검토 2와 같은 판단이다.
- runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 판정 입력인 `krx.json`을 갱신하는 절차다.
- 이 PR은 그 절차, 실행 중 명령과 반영 작업의 정지 방법, 반영 작업 시간 제한을 바꾼다. 또 7단계 실전 활성화 판정을 막는 Q-050을 건다.

## 점검표 밖 발견
- **R1 (권고, 데이터 정확도. 실전과 무관하고 머지를 막지 않는다)**: 녹화 원본의 시각은 UTC인데 runbook은 서울 시각으로만 적는다.
  - [사실] 녹화는 `events.jsonl.gz`(`RecordingLayout.java:14`, gzip)이다. 체결 줄은 `"t":"TRADE"`이고, 시각 `ex`는 `Instant.toString()`이라 `…Z`(UTC)로 저장된다(`RecordingCodec.java:49`, 형식 예시 `:26`).
  - [사실] runbook은 "녹화 원본에 그날 서울 09:00:00~09:29:59 체결이 있으면"이라고만 적고, 원본을 보는 도구나 시간대 변환을 안내하지 않는다.
  - [추론] 운영자가 `ex`에서 `T09:`를 찾으면 서울 18:00 무렵을 보게 된다. 그 시각에는 체결이 없으므로 정규장으로 돌아간 날을 "늦춘 시간 유지"로 보고 `verified: true`(10:00~16:30)로 굳힐 수 있다. architect-2 F1과 같은 종류의 오판이다.
  - 권하는 한 구절: "`t`가 `TRADE`인 줄의 `ex`가 UTC 그날 `00:00:00Z`~`00:29:59Z`(서울 09:00:00~09:29:59)에 있는지 본다. 10:00은 `01:00:00Z`다."
  - 그날이 지난 뒤의 정리라 보호에는 영향이 없다. 영향은 그날 실험 거래 구간(`session()`)과 `calendar_digest`에 그친다.
- **R2 (권고, 데이터 정확도. 실전과 무관하고 머지를 막지 않는다)**: 녹화 공백과 "체결 없음"을 구분하는 단서가 없다.
  - [사실] 녹화에는 끊김 표시(`"t":"GAP"`, `RecordingCodec.java:29`·`:62-63`)와 재기동 구간(`RecordingMeta.Segment`, `ReplayCheck`의 `unmarkedRestarts`)이 있다.
  - [추론] 게이트웨이가 09:00~09:30에 녹화하지 않았으면(늦게 켬, 재기동, 연결 끊김) "09:00~09:29 체결 없음"은 늦춘 운영의 증거가 되지 못한다. 같은 이유로 "둘 다 아닌 시각"이라는 판단을 녹화만으로 내리면 공백을 운영 시간으로 오인할 수 있다.
  - 권하는 한 구절: "녹화가 그날 09:00 전에 시작했고 09:00~10:00에 `GAP`·재기동 구간이 없을 때만 녹화로 판단한다. 아니면 HTS KRX 단독 분봉으로 본다. '둘 다 아닌 시각'은 거래소 공지나 HTS로만 확정한다."
- **N1 (절차, 메인 Claude 몫. risk-ops-2 N1·architect-2 N4 미해결)**: PR 브랜치의 `docs/qa/pending.md` 표에 PR #65 대기 줄이 아직 없다(표 머리만 있다). `pending.md` 규칙("PR 검토 요청은 그 PR 브랜치의 이 파일에")에 따라 독립 QA 요청 전에 한 줄을 더해야 한다. risk-ops 판정과는 별개다.
- **N2 (기록, `[추론]`)**: 6번의 "콘솔에서 중단"을 `./gradlew build`에 적용하면 Gradle 데몬 JVM이 유휴로 남을 수 있다. 반영 작업을 끝낸 뒤 `./gradlew --stop`을 하라는 말을 더하면 메모리 점유도 남지 않는다. Windows 실측은 없다.
- **N3 (기록, 이전 지적 유지)**: 아래는 이번 diff 범위 밖이고 머지를 막지 않는다.
  - Q-050의 "7단계 판정 전에 닫는다"는 결정 로그 미확정 표에만 있다. 7단계 관문 검토자(risk-ops, QA)가 D7·A8에서 확인한다.
  - `ExchangeCalendar` 생성자가 `source` 키가 있는지 검사하지 않는다.
  - 수록 범위 만료 자동 경보가 없다(다음 마감 서울 2027-12-31 00:00).
  - 작업 스케줄러 "끝내기"가 하위 `java.exe`까지 끝내는지 Windows 실측이 없다 `[가정]`.
- **참고**: architect-2 판정은 "수정 필요(F1)"였다. 이번 `3ca5290`의 2번 문장이 F1의 세 항목(시간외 혼입, `replayCheck` 칸이 확인 대상 항목으로 잘림, HTS 통합 분봉)을 문구로 다룬 것은 확인했다. 다만 F1의 해소 판정은 architect 재검토의 몫이다. 지침 11장에 따라 architect 원문이 "승인"이 되기 전에는 머지하지 않는다.

## 판정
승인.
- 점검표 A에 실패와 확인 못 함이 없다(A1~A7 해당 없음, A8~A14 통과).
- 재검토 2의 권고는 모두 반영됐고, 새 문장의 사실 주장을 모두 확인했다:
  - R1: 반영 명령 셋 전체와 새벽 창의 끝 시각·멈춤 조건
  - R2: 멈추는 방법과 `java.exe` 금지
  - R3: 시간외를 뺀 정규장 체결 기준과 세 번째 결과의 처리
- 확인한 근거:
  - 코드: `KiwoomStreamProtocol.java:48`·`:219-229`, `KisStreamProtocol.java:54`, `ReplayCheck.java:128`, `ExchangeCalendar.java:116-153`, `SessionProtection.java:21-22`·`:52-65`, `RecordingCodec.java:26-29`·`:49`
  - 기록: `experiments/stage-2-replay/2026-10-12.md:51`
  - 테스트: `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다()`
  - 번들 캘린더로 직접 계산한 보호 경계 24개 시각
  - 격리 clone의 `./gradlew build`: 897건, 실패 0
  - CI 세 검사 success
- main보다 보호를 줄이는 문장은 없다.
- 이 PR은 실전 관련이다(장애 대응: 장 시간 보호 판정 입력의 갱신 절차, 반영 작업 시간 제한·정지 방법, 7단계 차단 항목 Q-050). 지침 10장에 따라 사용자가 직접 확인하고 머지하고, 지침 11장의 독립 QA 관문을 거친다.
- R1·R2는 머지를 막지 않는 데이터 정확도 권고다. N1(`pending.md` 대기 줄)은 QA 요청 전에 메인 Claude가 처리할 절차다. architect F1 해소는 architect 재검토로 확인해야 한다. Q-050은 7단계 실전 활성화 전에 닫혀야 한다.

판정: 승인
