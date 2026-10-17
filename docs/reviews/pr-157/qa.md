# QA 검토: D-108 감시 결측 경고 보강(회복 직후 재결측 즉시 경고, 드라이브 읽기 실패 원인, 기동 실패 출력 시험)
- 대상: PR #157 / 헤드 커밋 6a4031567925a545b810e9aec11c2495cb2c1f9f(브랜치 `claude/project-thread-133h3h`) / main 대비 diff(`git diff origin/main...HEAD`, 머지 기준 b17c8ff, 파일 14개 +571 −26)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 6a40315, 미러 init 스크립트 없이): 1·2회째는 Maven Central 429(`jackson-core:2.21.4` pom 등)로 의존성 받기 실패 → 20초 간격 재시도 3회째에 BUILD SUCCESSFUL in 2m 54s. `build/test-results/test/*.xml` 합계 1,541건, 실패 0·오류 0·건너뜀 0. `:autotrade-app-watchdog` 70건, `:autotrade-architecture-test` 46건 통과. 429 실패는 빌드 실패로 보지 않았다(의존성 서버 응답).
  - GitHub CI(헤드 6a40315 check run) 4개 모두 success: Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary, Lab screens (Playwright).
  - `git merge-tree --write-tree origin/main HEAD`: **`docs/qa/pending.md`에서 충돌**(GitHub `mergeable_state: dirty`). 머지 기준 뒤 main(2090658)에 들어온 파일 11개(실험실 `ExperimentJobStream`·`LiveLabService`와 시험, `NotificationQueueTest`, `docs/reviews/pr-154`·`pr-156`, `pending.md`)는 `pending.md` 말고는 이 PR 파일과 겹치지 않는다. 병합본 빌드는 하지 않았다(코드 파일 교집합 없음).
  - 변이 13개(QA가 새로 고름. 각 변이 뒤 원본으로 되돌리고 `git status` 깨끗함 확인). 실행 `./gradlew :autotrade-app-watchdog:test --offline`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M1 | `reportMissing` 건너뛰기 조건에서 `!afterRecovery` 삭제(회복 뒤 재결측 즉시 WARN 제거) | 잡힘(4건 실패) |
    | M2 | 알림 조건 `newName \|\| afterRecovery` → `newName`(재결측 알림 제거) | 잡힘(2건) |
    | M3 | 회복 INFO 조건에서 `&& due(lastRecoveryInfo, now)` 삭제 | 잡힘(3건) |
    | M4 | `lastRecoveryInfo = now;` 삭제 | 잡힘(3건) |
    | M5 | `afterRecovery`에서 `&& lastMissingWarning != null` 삭제 | 생존(같은 동작: 처음엔 `warnedReadings`가 비어 `newName`이 늘 참) |
    | M6 | WARN 문구에서 `causes(missing)` 삭제 | 잡힘(1건) |
    | M7 | `DiskFreeSource.read` 첫머리 `failures = Map.of()` 삭제(risk-ops K1 되돌림) | 잡힘(1건) |
    | M8 | `failures = Map.copyOf(failed)` 삭제 | 잡힘(3건) |
    | M9 | 서로게이트 검사 삭제(`end = CAUSE_MAX`) | 잡힘(1건) |
    | M10 | 길이 경계 `<=` → `<` | 잡힘(1건) |
    | M11 | CR·LF 치환 삭제 | 잡힘(1건) |
    | M12 | 공용 `due`에서 시계 역행 처리(`now.isBefore(last)`) 삭제 | 잡힘(1건, 기존 시계 역행 시험) |
    | M13 | `causes`가 이름과 상관없이 실패 맵의 첫 원인을 붙임 | **생존**(Q1) |
  - 탐침 시험 5개(임시 파일 `QaProbe157Test`, 실행 뒤 지움, 저장소에 남기지 않음). 드라이브 둘(`disks=<a>,<b>`) 설정으로:
    - P1 드라이브 b만 실패 + CPU 결측: WARN `자원 값을 읽지 못함: [cpu.system, disk.free-gb:…/b] (원인: disk.free-gb:…/b ← IOException: fail b)`. 원인은 b에만, a·CPU에는 붙지 않음(통과).
    - P2 회복 INFO를 미루는 동안 다른 값(드라이브 a)이 새로 빠짐: 바로 WARN(3회째)과 `READING_MISSING` 알림, 마지막 결측 로그가 "읽지 못함"(통과).
    - P3 회복 INFO를 미루는 동안 시계가 60초 뒤로 감: 다음 점검에 회복 INFO를 바로 남김(`missingRecoveries` 1→2). `due`가 역행을 "찼음"으로 보는 기존 규칙과 같다.
    - P4 첫 점검 a가 `IOException`, 10분 뒤 `FreeSpace`가 `Error`: 둘째 WARN은 a·b 모두 빠짐으로 나오고 "원인:"이 붙지 않음(이전 원인이 남지 않음, 통과).
    - P5 `cause(new IOException("a\tb\u001b[31mc d"))` → 탭과 ESC(ANSI 색 문자열)가 그대로 남음(R2).

## 검토 범위와 영향
- [사실] 보호 경로 `autotrade-app-watchdog/*`(`scripts/ci/protected-paths.txt`)와 `docs/team/broker-and-fault-design.md` 변경이라 실전 관련 PR이다. 보호 테스트(`autotrade-architecture-test` 모듈, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험)는 diff에 없다. 감시 시험(`WatchdogTest`·`WatchdogMainTest`)은 보호 테스트 목록(`docs/qa/README.md` 1장) 밖이며, 기존 시험 하나(`같은_값이_빠졌다_돌아오기를_되풀이해도_…`)의 이름과 기대값이 바뀌었다(아래 A11).
- [사실] 운영 코드 변경은 두 파일이다. `Watchdog.reportMissing`(`Watchdog.java:207`)에 회복 INFO 간격(`:213`, `lastRecoveryInfo`)과 회복 뒤 재결측 조건(`:224`, `:226`, `:240`)을 더했고, 간격 계산을 `due`(`:248`)로 뽑았으며, WARN에 `causes`(`:253`)를 붙였다. `DiskFreeSource.read`(`DiskFreeSource.java:55-66`)가 읽기마다 실패 원인 맵을 새로 만들고 `cause`(`:79`)가 한 줄·160자로 줄인다.
- [사실] 정지 요청 판정 경로는 바뀌지 않았다. `tick`은 같은 `try` 안에서 `reportMissing`(`Watchdog.java:109`) 뒤 `pressure`(`:110`, 정의 `:175`)를 실제 스냅숏으로 계산하고 `stopRequests.update`(`:129`)에 넘긴다. `reportMissing`의 로그 미루기는 `pressure`에 쓰이는 상태를 건드리지 않는다(`warnedReadings`·`lastMissingWarning`·`lastRecoveryInfo`·`warnedSinceRecovery`는 `reportMissing`만 읽고 씀). `StopRequests`·`HeartbeatWatch`·`WatchdogSettings` 무변경.
- [사실] `DiskFreeSource.failures`는 `MonitorAgent.sampleOnce`(`MonitorAgent.java:64-75`, 같은 점검 스레드에서 수집원을 차례로 부름)가 끝난 뒤 같은 스레드의 `causes`가 읽는다. `Watchdog`은 `agent.start()`를 부르지 않으므로(`grep agent\.` 결과 `sampleOnce`·`close`뿐) 다른 스레드가 `read`를 동시에 부르는 경로가 없다.
- [사실] 원인 문구는 로그(WARN)에만 붙고 알림 본문(`Watchdog.java:241-242`)에는 붙지 않는다. 원인은 OS가 준 `IOException`·`RuntimeException` 메시지로, 감시 설정의 드라이브 경로를 담는다. 같은 경로는 이전부터 값 이름(`disk.free-gb:<경로>`)으로 로그에 있었다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·`autotrade-risk`·`autotrade-app-engine` 무변경(diff 파일 목록). 전체 빌드에서 `autotrade-risk`·`EngineMainTest` 시험 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 무변경. 감시는 주문 API를 부르지 않는다. 전체 빌드에서 ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest 통과(새 JDK 사용은 `Map`·`TreeMap`·`Character.isHighSurrogate`로 감시 모듈 안, 허용 검사 통과) |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재전송 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음. 감시는 계좌를 다루지 않는다 |
| A8 | 통과 | 5.2 0번 정지 요청 판정(`Watchdog.pressure` `:175`, `stopRequests.update` `:129`)은 무변경이고 실제 값으로 점검마다 계산된다(위 영향 3번째). 값이 빠지면 UNKNOWN이라 정지 요청을 풀지 않는 규칙은 `WatchdogTest#드라이브를_읽지_못하면_판단_못_함이라_정지_요청을_풀지_않는다` 통과로 그대로. 탐침 P4에서 `Error`가 나도 값은 빠짐으로 보고 WARN이 남는다. 감시가 다른 프로세스를 죽이거나 엔진에 닿는 경로는 생기지 않았다. 바뀐 것은 로그·알림 빈도와 문구뿐이다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(눈으로 확인, CI gitleaks success). 새 로그 원인 문구는 OS 경로 메시지이고 알림 본문에는 들어가지 않는다(`Watchdog.java:241-242`) |
| A11 | 통과 | 보호 테스트 삭제·약화·고정 값 변경 없음. 기존 `WatchdogTest#같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_한_번만_알린다`는 `…_정해진_횟수만_알린다`로 이름이 바뀌고 WARN 기대값이 1→2, 2→3, 3→4로 올랐다. 이는 D-108 1번(회복 뒤 재결측 즉시 WARN)의 의도된 결과이고 INFO 기대값(1)은 그대로이며 끝에 `missingRecoveries()==1` 단언이 더해졌다(약화 아님). 새 동작마다 실패 경우 시험이 있다: 회복 뒤 재결측(`#회복_알림_뒤_다시_빠지면_간격을_기다리지_않고_바로_알린다`), 회복 미룸 경계(`#간격_안에_두_번째_회복은_…`), 60분 오감 상한과 "마지막 로그가 읽지 못함"(`#점검마다_오가도_…`), 원인 기록(`#드라이브를_읽지_못한_원인을_결측_경고에_적는다`, `DiskFreeSourceTest` 4건), 기동 실패 출력(`WatchdogMainTest` 1건 보강·1건 추가). 변이 13개 중 11개 잡힘, 생존 2개는 같은 동작(M5)과 드라이브 여럿일 때의 원인 짝짓기(M13, Q1) |
| A12 | 통과 | 장애 대응표(6장)·장애 테스트 8종의 전제(정지 요청 생성·유지·해제, 감시 독립)는 바뀌지 않았다. 5.2 0번 문장(`broker-and-fault-design.md:144`)은 "빠지면 바로, 빠진 동안 10분마다 경고 로그, D-096·D-108"로 바뀌어 코드와 맞다 |
| A13 | 해당 없음 | 시장별 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-108 결정 1~4번이 코드와 맞다: 1번 `Watchdog.java:224-226,240`, 2번 `:213`(회복 INFO 간격, 탐침 P2·P3 포함 동작 확인), 3번 `DiskFreeSource.java:55-66,79-91`·`Watchdog.java:253`(160자 `CAUSE_MAX`, 시험이 경계 확인), 4번 `WatchdogMainTest`. D-096 2번 대체를 D-108에 적었고 결정 로그 표(README D-108 줄), 기획서 v14.46 MON-02, ADR-011 3장, runbook 6단계 점검 문구가 같은 내용이다. D-107 건너뜀은 열린 브랜치 `claude/project-thread-4l8sa4`에 D-107 파일이 있어 사실이다 |
| B1 | 통과 | `./gradlew build --rerun-tasks` BUILD SUCCESSFUL(429 재시도 3회째), 1,541건 실패 0. CI Gradle build success(헤드 6a40315) |
| B6 | 통과 | 위 A11. 건너뛴(`@Disabled`)·지운 시험 없음(diff에서 `-    @Test` 없음, 건너뜀 0건) |

## 점검표 밖 발견
- **R1(머지 전 처리 필요, 판정 영향 없음) — main과 `docs/qa/pending.md` 충돌**: PR #154·#156이 main에 들어온 뒤 같은 표 끝에 줄을 더해 GitHub에서 `mergeable_state: dirty`다. 코드 파일은 겹치지 않으므로 표 줄을 둘 다 남기는 병합이면 된다. 병합 커밋이 생기면 감시 코드가 그대로인지(병합본 `git diff 6a40315 <병합> -- autotrade-app-watchdog` 비었는지)만 확인하면 된다.
- **R2(참고) — 원인 문구에 CR·LF 말고 제어 문자가 남는다**: 탐침 P5에서 탭과 ESC(ANSI 색 문자열)가 그대로 로그에 들어간다. 원인은 OS 경로 메시지이고 경로는 운영자 자신의 감시 설정이라 외부 입력 경로는 찾지 못했다 `[추론]`. 콘솔에서 줄 모양이 깨질 수 있는 정도다. 다음에 손댈 때 `Character.isISOControl`을 공백으로 바꾸는 정도를 검토할 만하다.
- **R3(참고) — PR 본문의 CI 커밋 표기**: 본문 "CI 4종 통과(970e099)"는 현재 헤드 6a40315가 아니다. 헤드 6a40315의 check run 4개도 success라 사실 판단에는 영향이 없다.
- **R4(참고, D-108 위험과 한계에 이미 있음)**: 값이 점검마다 오가면 회복 INFO가 최대 10분 늦고, 그동안 마지막 로그·알림은 "읽지 못함"(긴급)이다. 정지 요청 판정은 실제 값으로 하므로 해제·생성이 늦어지지 않는다(위 영향 3번째). 운영자가 "판단 못 함"으로 잘못 아는 쪽이라 안전 쪽이다.

### 잘못된 주문·중복 주문·보호 공백 시나리오 시도
1. **로그 미루기가 정지 요청 해제를 앞당기거나 막는가**: `reportMissing`이 쓰는 상태와 `pressure`·`StopRequests`가 쓰는 상태가 겹치지 않고(코드 읽기), `pressure`는 점검마다 스냅숏으로 계산한다. 회복 INFO를 미루는 동안에도 값이 있으면 실제 수준으로 판정한다. 공백 없음.
2. **원인 기록이 틀린 원인·이전 원인을 보여 운영자가 잘못 조치하는가**: `Error`로 수집원이 버려진 점검(탐침 P4, M7 잡힘)과 성공 뒤(`DiskFreeSourceTest`)에는 원인이 지워진다. 드라이브 둘 중 하나만 실패하면 원인이 그 드라이브에만 붙는다(탐침 P1). 다만 이 짝짓기를 지키는 저장소 시험은 없다(Q1, M13 생존).
3. **회복 직후 재결측 알림이 알림 큐를 막아 다른 긴급 알림(엔진 멈춤 등)을 밀어내는가**: 재결측 알림은 회복 INFO 한 번당 하나뿐이고 회복 INFO가 10분에 한 번이라 `readings` 대상 긴급 알림은 10분에 최대 하나 더 늘어난다(D-108 2번, `#점검마다_오가도_…`가 60분 WARN ≤ 12·INFO ≤ 6 확인). 알림 큐의 대상별 억제·전체 상한(D-100)은 무변경이다. 엔진 멈춤 알림은 대상이 달라 대상별 억제와 겹치지 않는다 `[추론]`(전체 상한 소진까지는 시험하지 않음).
4. **회복 미룸 중 다른 값이 빠지거나 시계가 거꾸로 가면 경고가 사라지는가**: 새 값은 바로 WARN·알림(탐침 P2), 시계 역행은 회복을 바로 남김(탐침 P3). 경고가 빠지는 순서는 찾지 못했다.

## 권고(판정에 넣지 않음)
- **Q1**: 드라이브가 둘 이상이고 하나만 실패할 때 원인이 그 드라이브 이름에만 붙고 CPU·메모리 결측에는 안 붙는 시험이 없다(M13 생존: 실패 맵의 첫 원인을 아무 이름에나 붙여도 통과). 현재 `WatchdogTest#드라이브를_읽지_못한_원인을_결측_경고에_적는다`는 드라이브 하나로만 본다. 탐침 P1과 같은 시험 한 건이면 된다.

## 판정
승인

머지는 사용자가 한다. 머지 전 R1(`docs/qa/pending.md` 충돌)을 풀어야 한다.
