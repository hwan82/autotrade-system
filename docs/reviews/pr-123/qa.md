# 독립 QA 검토: 감시 자원 값 결측 경고 반복·억제(D-096, PR #122 QA 후속)
- 대상: PR #123 / 헤드 20bb98a (브랜치 claude/project-thread-0ganrn) / main 1006a33 대비 전체 diff(`git diff origin/main...20bb98a`, 커밋 3개 6f4cae7·5f23d8a·20bb98a, 10개 파일 +215 −24). 병합 기준점 = 현재 main 1006a33.
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → `BUILD SUCCESSFUL in 3m 53s`, 종료 코드 0. 시험 결과 XML 집계 1,301건 / 실패 0 / 오류 0 / 건너뜀 0. `:autotrade-app-watchdog:test` 40/0/0/0(`WatchdogTest` 10건 전부 통과, 새 시험 2건 포함), `:autotrade-architecture-test:test` 44/0/0/0, `:autotrade-risk:test` 55/0/0/0, `:autotrade-app-engine:test` 52/0/0/0.
    - 빌드 환경 메모: 이 컨테이너에서 Maven Central이 429를 주고 Gradle이 `kotlin-build-tools-impl-2.0.21.jar`를 "Could not find"로 남겨 처음 여러 번은 의존성 받기에서 실패했다(코드 실패 아님). 저장소 파일은 건드리지 않고, 검토자 임시 폴더의 init 스크립트(`-I`)로 Maven Central 공개 미러(`maven-central.storage-download.googleapis.com/maven2`)를 앞에 두어 받았다. 빌드 스크립트·설정 변경 없음.
  - PR CI(헤드 20bb98a): `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` 모두 success(GitHub check runs 조회).
  - 탐침 시험 P1~P5: 임시 시험 클래스 `QaProbeTest`를 `autotrade-app-watchdog` 시험 폴더에 넣어 `:autotrade-app-watchdog:test --tests '*QaProbeTest'` 실행 뒤 지움(커밋 안 함). 결과는 아래 "시나리오".
  - 변이 W1~W10(개발 쪽 V1~V9와 겹치지 않는 것 위주): `Watchdog.java`를 한 곳씩 고치고 `:autotrade-app-watchdog:test --tests '*WatchdogTest'` 실행, 매번 원본으로 되돌림. 끝난 뒤 `git status --short` 출력 없음.

    | 변이 | 결과 |
    |---|---|
    | W1 결측 WARN 수준을 DEBUG로 | **살아남음**(시험은 계수기만 봄) |
    | W2 경고 분기의 `warnedSinceRecovery = true` 삭제 | 잡힘(2건 실패) |
    | W3 `lastMissingWarning = now` 삭제(매 점검 경고) | 잡힘(2건 실패) |
    | W5 간격이 차서 경고할 때도 이전 경고 값을 합침(`if (!due)` → `if (true)`, 경고 값 집합이 줄지 않음) | **살아남음** |
    | W6 회복 INFO 때 `lastMissingWarning = null`(회복이 간격을 초기화, QA R4 재발) | 잡힘(1건 실패) |
    | W8 `MISSING_REPEAT`를 60분으로 | **살아남음**(시험이 상수를 상대값으로만 씀) |
    | W9 새 값 판정 방향 뒤집기(`!missing.containsAll(warnedReadings)`) | 잡힘(2건 실패) |
    | W10 회복 INFO 수준을 DEBUG로 | **살아남음** |

## 이번 PR이 실전 경로에 닿는 곳
- 운영 코드 변경은 `autotrade-app-watchdog/.../Watchdog.java` 한 파일. `autotrade-app-watchdog`는 `scripts/ci/protected-paths.txt`의 보호 경로라 실전 관련 PR이다.
- 바뀐 것은 결측 로그 정책 `reportMissing`(Watchdog.java:137-165)과 상태 필드·상수(:45-56), 시험용 계수기 `missingRecoveries`(:172-175)뿐이다. 정지 요청 판정 `pressure`(Watchdog.java:108-131), `StopRequests`, `HeartbeatWatch`, `WatchdogSettings`, `PlatformOsReadings`는 diff에 없다.
- `reportMissing`은 `pressure`보다 먼저 같은 `try` 안에서 불린다(Watchdog.java:89). 여기서 던지면 그 점검의 압력은 `UNKNOWN`이 된다(새 정지 요청 못 만듦, 켜진 요청 유지). 새 코드에서 던질 수 있는 지점을 찾았으나 `Reading` 이름은 생성자에서 `requireNonNull`(Reading.java:16)이고 `Instant.plus(10분)`은 넘치지 않아 새 경로는 찾지 못했다.
- 보호 테스트 변경 없음: `autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험 diff 0건. 고정 값(지문·허용 목록·소유 표) 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` diff 없음. `:autotrade-risk:test` 55/0/0/0 직접 실행. Guard 기본값·재기동 OFF에 닿는 설정·코드·시험 변경 없음 |
| A2 | 통과 | 새 import는 `java.time.Duration` 하나(Watchdog.java:12). 주문 출구·리플렉션·관리 API 사용 추가 없음. `:autotrade-architecture-test:test` 44/0/0/0(`ArchitectureTest`, `ModuleOwnershipTest`, `RealPathReflectionTest`) |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진 diff 없음, `:autotrade-app-engine:test` 52/0/0/0. 감시의 정지 요청 판정은 그대로이고 로그만 늘었다. 탐침 P2: CPU 위험으로 정지 요청 파일 생성 뒤 CPU가 30분 동안 결측이어도 파일이 매 점검 남아 있음(`keptDuring30minMissing=true`). 탐침 P1: 기동부터 CPU 결측이면 60분 동안 WARN 7건(0·10·20·30·40·50·60분), 이전 동작(1건)에서 QA R1이 해소됨. 로그량은 같은 값 결측 지속 시 10분에 1줄이라 감시 루프 부하 문제 없음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff 전문에서 키·계좌번호·토큰·URL 패턴 검색 0건(`git diff ... \| grep -iE 'api[_-]?key\|secret\|token\|account\|password\|appkey\|http'` 추가 줄 0). 새 로그 문구는 지표 이름 집합과 결정 번호·간격 분만 낸다(Watchdog.java:146, :163-164). CI `Secret scan and collector boundary` success |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음, 고정 값 갱신 없음. 기존 시험 `값이_빠지기_시작하면_한_번만_알리고_돌아오면_다시_알릴_수_있다`는 동작 변경(같은 결측 지속 시 1회 → 10분마다)에 맞춰 `값이_빠지면_바로_알리고_계속_빠지면_간격마다_다시_알린다`(WatchdogTest.java:172)로 바뀌었고, 옛 시험이 보던 것(첫 결측 즉시, 값이 늘면 다시, 회복)을 모두 단언하며 간격 경계(직전 없음·정확히 10분에 있음)를 더 본다. 오가는 결측은 `같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_한_번만_알린다`(:204). 변이 W2·W3·W6·W9 잡힘. 살아남은 W1·W5·W8·W10은 로그 수준·간격 상수값·경고 값 집합 축소 시험 공백으로, 정지 요청 판정과 무관해 실패로 두지 않고 권고 Q1·Q2로 적는다 |
| A12 | 통과 | 장애표 #10과 5.2 0번(broker-and-fault-design.md:144)의 발동·해제 조건은 `pressure`·`StopRequests` 불변으로 그대로다. 장애 테스트 8종의 전제(감시가 정지 요청 파일을 만들고 지우는 조건)도 그대로다. 5.2 0번의 "(빠지기 시작하면 경고 로그)" 문구에 10분 반복이 빠져 있으나 틀린 서술은 아니다(참고 R3) |
| A13 | 해당 없음 | 시장별 코드 변경 없음. 간격은 `Instant`·`Duration` 경과 시간이라 시간대와 무관 |
| A14 | 통과 | D-096 결정 1·2가 코드와 맞다: 새 값 즉시(Watchdog.java:150), 지속·되풀이 시 10분(:49, :151), 간격 안 경고 값 합치기(:155-158), 경고 뒤 회복 INFO 한 번(:142-147). 결정 로그 D-096 행, 기획서 v14.39(main v14.38 다음)와 MON-02 근거 열, ADR-011 3장, runbook 5장(stage-6-live-session.md:77-79)이 같은 내용. `ALL_ABSENT_REPEAT` 10분 주장은 HeartbeatWatch.java:48과 맞다. D-096 번호는 main과 열린 브랜치(`docs/stage-5-qa-request`)에 겹치는 것 없음. "추천안 채택(자율)"과 사용자 머지 표시 있음. D-096 결정 2의 "간격마다 WARN 하나와 INFO 하나가 최대"는 같은 값 기준일 때만 맞다(참고 R2, 개발 쪽 risk-ops N2와 같은 지적) |
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, `BUILD SUCCESSFUL`, 1,301건 실패 0(위 "직접 실행한 것") |
| B6 | 통과 | 새 동작에 시험 2건(WatchdogTest.java:172, :204)이 있고 건너뛴 시험 0건(`skipped` 0), 지운 시험은 같은 동작을 더 넓게 보는 시험으로 대체됨(A11) |

## 시나리오(잘못된 주문·중복 주문·보호 공백 시도)
- **S1 처음부터 CPU를 못 읽는 PC(QA R1 원래 상황)**: 탐침 P1. 60분 동안 WARN 7건, 첫 줄은 기동 첫 점검(0초). 그동안 압력은 `UNKNOWN`이라 CPU로는 정지 요청이 생기지 않는 상태가 10분마다 로그에 드러난다. 보호 공백 자체(결측이면 CPU 위험 판정 불가)는 D-095 그대로이며 이 PR이 넓히지 않는다. 주문 경로 영향 없음.
- **S2 위험 중 결측으로 정지 요청이 풀리는가**: 탐침 P2. CPU 99%로 정지 요청 파일 생성 → CPU 결측 30분 동안 파일 유지, WARN 3건. 결측이 해제를 일으키지 않는다(D-092 3번 그대로). 감시는 실전 엔진에 주문을 보내지 않으므로 중복·잘못된 주문 경로 없음.
- **S3 회복 INFO 직후 다시 계속 빠짐**: 탐침 P3. 5초 WARN → 10초 회복 INFO → 15초부터 계속 결측. 다음 WARN은 10분 5초(INFO 뒤 595초 동안 로그 없음, 마지막 로그가 "자원 값을 다시 읽음"). D-096 위험과 한계 첫 줄에 적힌 설계상 한계와 같다. 그동안에도 압력 `UNKNOWN`이라 켜진 정지 요청은 풀리지 않는다(안전한 쪽). 운영자가 마지막 로그만 보고 "회복됨"으로 오판할 수 있는 시간이 최대 10분이다 `[사실, 탐침 P3]`.
- **S4 벽시계 역행**: 탐침 P4. 경고 직후 시계를 1시간 뒤로 돌리면 다음 반복 WARN이 벽시계 기준 70분 뒤에 나온다. 운영 시계는 `Clock.systemUTC()`(WatchdogMain.java:34). 새 값 즉시 경고와 정지 요청 판정은 영향 없음. 개발 쪽 risk-ops N3의 `[추론]`을 이 탐침으로 사실 확인했다. `HeartbeatWatch.ALL_ABSENT_REPEAT`도 같은 구조다.
- **S5 서로 다른 값이 돌아가며 빠짐**: 탐침 P5. CPU → 드라이브 → 정상 순환 10분 동안 WARN 2, INFO 1, 그 뒤 같은 간격 안 순환은 조용하다. 간격당 WARN 상한은 필수 값 수(드라이브 경로 수 + 2)다.

## 점검표 밖 발견
- **Q1(시험 공백, 권고) 경고 값 집합이 간격마다 줄어드는 동작이 시험에 묶이지 않음.** 변이 W5(간격이 차서 경고할 때도 이전 경고 값을 계속 합침)가 살아남는다. 이 변이에서는 한 번이라도 경고한 값은 그 뒤 다시 빠져도 "새 값 즉시 경고"를 받지 못하고 다음 간격(최대 10분)까지 조용하다. 예: 0분 {cpu, 드라이브} 경고 → 10분 {cpu}만 결측으로 반복 경고 → 11분 드라이브가 다시 빠짐: 원본은 11분에 바로 WARN, 변이는 20분까지 없음. 현재 코드는 맞으나(Watchdog.java:155-158) 시험이 지켜 주지 않는다. 위 순서를 단언하는 시험 1건을 권한다.
- **Q2(시험 공백, 권고) 간격 상수와 로그 수준이 시험에 묶이지 않음.** W8(`MISSING_REPEAT` 60분)이 살아남는다: 시험이 `Watchdog.MISSING_REPEAT`를 상대값으로만 써서 10분이라는 값(D-096·ADR-011·runbook이 운영자에게 약속한 값)이 바뀌어도 통과한다. `assertThat(Watchdog.MISSING_REPEAT).isEqualTo(HeartbeatWatch.ALL_ABSENT_REPEAT)` 또는 `Duration.ofMinutes(10)` 단언 한 줄을 권한다. W1·W10(WARN·INFO를 DEBUG로)도 살아남는다(개발 쪽 risk-ops N5의 `[추론]`을 변이로 사실 확인). 운영자가 보는 것은 로그뿐이라 수준을 로그 수집으로 단언하는 시험을 권한다.
- **R1(남은 위험, 설계상 한계) 회복 INFO 뒤 최대 10분 침묵.** S3. D-096에 적혀 있고 안전 쪽(정지 요청 유지)이다. 알림(MON-06) 때 "마지막 상태"를 로그 줄이 아닌 상태 값으로 보여 주면 해소된다.
- **R2(참고, 문서 정밀도) 상한 문구.** D-096 결정 2 "간격마다 WARN 하나와 INFO 하나가 최대", 대안 ① "하루 WARN 144줄 이하"는 같은 값 기준이다. S5처럼 다른 값이 돌아가며 빠지면 간격당 필수 값 수까지 WARN이 난다. 개발 쪽 risk-ops N2와 같고 PR 본문이 후속으로 적었다.
- **R3(참고, 문서) 5.2 0번 문구.** broker-and-fault-design.md:144 "(빠지기 시작하면 경고 로그)"에 10분 반복이 없다. 개발 쪽 risk-ops N1과 같다.
- **R4(남은 위험) 벽시계 역행.** S4. 역행 폭만큼 반복 경고가 늦어진다. 로그만의 문제이고 `HeartbeatWatch`와 같은 성질이라 막지 않는다. 고칠 때는 두 곳을 함께(역행이면 바로 due, 또는 `System.nanoTime` 기반) 권한다.
- **과금 검사(지침 8장)**: 유료 시세·API·라이브러리 추가 없음. 새 외부 호출 없음(diff는 JDK `java.time`만 추가). 통과.

## 판정
승인

점검표 A·B1·B6 실패 0건, 확인 못 함 0건. Q1·Q2는 시험 공백 권고, R1~R4는 남은 위험·참고로 판정에 넣지 않았다. 실전 관련 PR(보호 경로 `autotrade-app-watchdog`)이므로 머지는 사용자가 직접 한다.
