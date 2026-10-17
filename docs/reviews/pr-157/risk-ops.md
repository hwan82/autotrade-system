# risk-ops 검토: D-108 감시 결측 경고 보강(회복 직후 재결측 즉시 알림, 드라이브 읽기 실패 원인, 기동 실패 출력 시험)
- 대상: PR #157 / 헤드 커밋 970e099(브랜치 `claude/project-thread-133h3h`) / main 대비 diff(`git diff origin/main...HEAD`, 머지 기준 b17c8ff, 파일 11개 +378 −25)
- 점검표: A
- 직접 실행한 것(별도 clone, 다른 검토자 작업 트리와 분리):
  - `./gradlew -I <스크래치>/mirror.gradle build --no-daemon`(헤드 970e099): **BUILD SUCCESSFUL in 4m 6s**, 종료 코드 0. init 스크립트는 Maven Central 429 회피용 공개 미러(`maven-central.storage-download.googleapis.com/maven2`)를 settings 저장소로 둔 것이며 저장소 밖 파일이다(빌드 내용은 바꾸지 않음). `*/build/test-results/test/*.xml` 합계 1,539건, 실패·오류·건너뜀 0. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 55, `:autotrade-app-watchdog` 68, 모두 실패 0.
  - 변이 6개(한 번에 하나, 각 변이 뒤 `git checkout -- autotrade-app-watchdog`로 원복, 마지막에 `git status` 깨끗함 확인). 실행 범위 `:autotrade-app-watchdog:test`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M1 | `afterRecovery = false`(회복 뒤 재결측 즉시 WARN 제거) | 잡힘 4건(`#회복_알림_뒤_다시_빠지면…`, `#간격_안에_두_번째_회복은…`, `#점검마다_오가도…`, `#같은_값이_빠졌다_돌아오기를…`) |
    | M2 | 회복 INFO의 `due(lastRecoveryInfo, now)` 조건 제거 | 잡힘 3건 |
    | M3 | WARN에서 `causes(missing)` 제거 | 잡힘 1건(`#드라이브를_읽지_못한_원인을_결측_경고에_적는다`) |
    | M4 | `DiskFreeSource`가 성공 시 원인을 지우지 않음 | 잡힘 1건(`DiskFreeSourceTest#읽지_못한_경로만_빼고_원인을_남기며_다시_읽으면_원인을_지운다`) |
    | M5 | 알림 조건을 `newName`만으로(회복 뒤 재결측 알림 제거) | 잡힘 2건 |
    | M6 | `lastRecoveryInfo = now` 삭제 | 잡힘 3건 |
  - 탐침 1개(임시 시험 파일, 실행 뒤 삭제): `FreeSpace`가 첫 읽기에 `IOException`, 둘째 읽기에 `Error`(StackOverflowError)를 던지면 둘째 읽기 뒤 `failures()`가 `{disk.free-gb:d=IOException: first}`로 **이전 원인이 남음**(아래 K1).
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음. 머지 기준 뒤 main(75d8aae)에 들어온 변경은 `autotrade-app-lab` 4개 파일과 `docs/reviews/pr-156/architect.md`뿐이라 감시·실전 경로와 겹치지 않아 병합본 빌드는 하지 않았다.

## 검토 범위와 영향
- 운영 코드 변경은 감시 프로세스 두 파일뿐이다: `Watchdog.java`(결측 로그·알림 정책, 원인 붙이기), `DiskFreeSource.java`(경로별 실패 원인 보관). 나머지는 시험과 문서.
- 실전 영향 경로 확인: 정지 요청 판정 `Watchdog.pressure`(Watchdog.java:175-198)와 `stopRequests.update(now, pressure, detail)`(Watchdog.java:129), `StopRequests`는 diff에 없다. `reportMissing`은 로그·알림만 하며 `pressure`·`detail` 계산 전에 불리지만 같은 `try`(Watchdog.java:107-114) 안이다. `causes()`가 던질 일은 없지만(맵 조회), 던져도 그 점검의 `pressure`는 UNKNOWN으로 남아 정지 요청을 풀지 않는 쪽(안전한 쪽)이다.
- `DiskFreeSource.failures`는 `volatile` 불변 맵이고, `MonitorAgent.sampleOnce`(autotrade-monitor-agent-jar/.../MonitorAgent.java:64-84)가 수집원을 같은 스레드에서 차례로 읽으므로 `reportMissing`이 보는 원인은 같은 점검의 읽기 결과다(Error 경우 제외, K1).
- 감시 의존성·빌드 파일·`processIsolation` 설정 변경 없음. 실전 엔진(`autotrade-app-engine`) 코드 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 설정 로딩 코드 변경 없음(diff 11개 파일에 `autotrade-risk`·`autotrade-app-engine` 없음). 회귀 확인: `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. 감시는 주문 API를 부르지 않는다. ArchUnit 규칙 파일 변경 없음, `:autotrade-architecture-test:test` 46건 통과(`ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 포함) |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 자원 부족 시 정지 순서 5.2 0번(broker-and-fault-design.md:144)을 만드는 감시의 판정 경로(`pressure` Watchdog.java:175-198, `stopRequests.update` :129)는 그대로이고, 값이 빠지면 UNKNOWN이라 정지 요청을 풀지 않는 규칙(`WatchdogTest#드라이브를_읽지_못하면_판단_못_함이라_정지_요청을_풀지_않는다` :107 통과)도 그대로다. 바뀐 것은 로그·알림 빈도뿐. 감시는 어떤 프로세스도 죽이지 않으며 실전 엔진에 닿는 경로가 생기지 않았다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 로그 문구에 붙는 원인은 `FileStore` 예외의 종류·메시지(경로 문구)로 160자·한 줄로 자른다(DiskFreeSource.java:74-83). 비밀값이 드나드는 경로가 아니다. 원인은 로그에만 붙고 외부로 나가는 알림 본문(Watchdog.java:242-243)에는 넣지 않는다. 시험 픽스처는 `c-drive`·`d-drive` 같은 가짜 경로뿐(DiskFreeSourceTest.java:15-16). gitleaks는 이 컨테이너에 없어 CI에 맡기고, 대신 `git diff origin/main...HEAD`의 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호 모양` 정규식으로 찾아 0건을 확인했다 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard 테스트, 장애 테스트) 변경 없음. 보호 경로(`autotrade-app-watchdog/*`) 안의 기존 시험 `WatchdogTest#같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_한_번만_알린다`(:218)의 기대값이 1→2, 2→3, 3→4로 바뀌었으나 이는 D-108 1번(회복 뒤 재결측 즉시 WARN)으로 WARN이 하나 늘어난 새 규칙 반영이며, `missingRecoveries() == 1` 단언이 추가돼 오히려 강해졌다(M1·M2·M6이 이 시험으로 잡힘). 새 동작마다 실패 경우 시험이 있다: :415, :446, :479, :508, DiskFreeSourceTest 2건, WatchdogMainTest :81·:95. 직접 변이 6개 모두 잡힘 |
| A12 | 통과 | 장애 대응표 6장 17행 그대로(행 수 확인 17). 5.2 0번 문단(broker-and-fault-design.md:144)의 결측 로그 설명만 "빠지면 바로, 빠진 동안 10분마다 경고 로그, D-096·D-108"로 갱신, 정지 요청 해제 기준은 그대로. 장애 테스트 8종의 전제(감시는 정지 요청만, 실전 엔진은 멈추지 않음)를 바꾸지 않는다. 해외 O1~O8 무관 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-108(docs/decisions/D-108-…md) 새로 기록, README 색인 1줄, 기획서 v14.46 변경 이력·MON-02 근거, ADR-011 3장, runbook stage-6 1줄이 코드 동작(Watchdog.java:213, :224-226, :237, :240)과 일치. D-096 본문은 고치지 않고 D-108이 2번을 대체한다고 명시(지침 2장 "뒤집을 때도 새 번호") |

## 점검표 밖 발견
- **K1(권고, 막지 않음) — Error 시 원인이 이전 읽기 것으로 남음**: `DiskFreeSource.read`는 `IOException | RuntimeException`만 잡고(DiskFreeSource.java:59), `failures = Map.copyOf(failed)`(:64)는 반복문 뒤에 있다. `FreeSpace`가 `Error`를 던지면 `MonitorAgent`가 수집원 전체를 버리고(MonitorAgent.java:72) `failures`는 갱신되지 않아, 다음 WARN에 **이전 점검의 원인**이 붙는다(탐침으로 확인: `{disk.free-gb:d=IOException: first}`). 판정·정지 요청에는 영향이 없고(값은 빠진 것으로 보고 UNKNOWN) WARN 자체는 나므로 안전 쪽이지만, 이 PR의 목적인 "원인 기록"이 틀린 원인을 보여 줄 수 있다. 수정 제안: `read()` 첫머리에서 `failures = Map.of()`로 비우거나 `try/finally`로 갱신, 시험 1건 추가. D-108 "위험과 한계"에 한 줄로 남겨도 된다.
- **K2(참고)** — 생성자에서 지역 변수 `Threshold disk`(Watchdog.java:87)가 새 필드 `this.disk`(DiskFreeSource, :46·:93)를 가린다. 컴파일·동작은 맞다(:94는 `this.disk`, :95는 지역 `disk`). 다음에 손댈 때 지역 이름을 `diskThreshold`로 바꾸면 실수 여지가 준다. architect 영역이라 판정에 넣지 않는다.
- **K3(참고)** — 회복 INFO가 미뤄지는 동안(최대 10분) 회복 INFO 본문의 `warnedReadings`에는 간격 안에서 합쳐진 이전 경고 값도 들어갈 수 있다(Watchdog.java:230-232). 표시 문구 문제이며 안전 영향 없음.
- 알림 증가 상한 검토: 회복 뒤 재결측 알림(`READING_MISSING`)은 회복 INFO가 간격당 하나로 묶이므로 간격당 최대 하나 더 늘고, 알림 큐가 같은 대상·종류를 10분에 한 번만 보내므로 알림 폭주 위험은 없다고 본다 `[추론]`(알림 큐 중복 억제는 이 PR에서 바꾸지 않았고 직접 재시험하지 않음).

## 판정
승인
