# architect 검토: Q-041 결정(D-058): 단발 일괄 명령을 자원 부족 정지 순서 0번에, 수집기는 감시의 정지 요청 파일로 멈춤
- 대상: PR #46 / 브랜치 `claude/project-thread-vip8tu` / 커밋 16bf953 / main 대비 diff(`git diff origin/main...HEAD`, 병합 기준 78288bd, 12개 파일 +130 −14)
- 점검표: B
- 직접 실행한 것 (별도 복제본 `/tmp/claude-0/review-architect-46`, 16bf953 체크아웃):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(117 tasks). `ArchitectureTest` 17건·`ModuleOwnershipTest` 13건·수집기 테스트 전부 실패 0, 건너뜀 0. 새 테스트 `ArchitectureTest#감시는_프로세스를_죽이지_않는다`, `RunGuardTest#정지_요청_파일은_해제_옵션과_상관없이_막고_지우지_않는다`, `CollectorMainTest#감시의_정지_요청이_있으면_장_시간_해제_옵션을_줘도_시작하지_않고_파일을_지우지_않는다`, `CollectorMainTest#실행_중_정지_요청이_생기면_다음_호출_전에_멈추고_파일을_지우지_않는다` 통과.
  - 변이 확인: 복제본의 `WatchdogMain`에 `ProcessHandle.of(p).ifPresent(h -> h.destroy())`를 넣고 `:autotrade-architecture-test:test` 실행 → `감시는_프로세스를_죽이지_않는다` 실패(`WatchdogMain.kill(long) calls method java.lang.ProcessHandle.destroy()`). 규칙이 고정 예제뿐 아니라 실제 감시 모듈(PRODUCTION 범위)에서도 잡는 것을 확인. 원복 후 작업 트리 깨끗.
  - `git merge-tree --write-tree origin/main HEAD` → 현재 main(53254d9, #41 병합 후)과 충돌 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 결과 BUILD SUCCESSFUL |
| B2 | 통과 | 운영 코드 변경은 `autotrade-app-collector` 안(`RunGuard.java`, `CollectorMain.java:71,280-283`)뿐이고 새 의존 없음(`java.nio.file`만). 새 ArchUnit 규칙 `WATCHDOG_KILLS_NO_PROCESS`(`ArchitectureTest.java:237-247`)는 규칙 추가(강화)이며 근거 결정 D-058과 ADR-007 2.7(구현 PR 테스트 항목 "감시 모듈에 `ProcessHandle.destroy*`·`Process.destroy*` 호출 0건")이 있다. 위반 고정 예제 `KillsBatchProcess`는 `autotrade-architecture-test` 테스트 소스라 `OurModules.architectureImportOption()`이 PRODUCTION에서 뺀다(빌드에서 `check(PRODUCTION)` 통과로 확인). 같은 방식의 선례 `com.autotrade.app.collector.archfixture.LoadsProcessConfig`가 있다. `ModuleOwnershipTest` 통과 |
| B3 | 통과 | 바뀐 계약은 수집기 CLI 동작(정지 요청 파일 경로 `<설정 폴더>/collector-state/stop-request`, 종료 코드 4 사유 추가)과 감시↔단발 명령 파일 계약. ADR-008 2.7(`docs/adr/ADR-008-collector.md:77-83`), 결정 D-058, 장애 설계 5.2 0번·장애표 #10, 기획서 RSK-07(v14.12), runbook 3장·4장 종료 코드가 함께 갱신됐다. 경로는 `Options.budgetDir()`(= `configDir/collector-state`)에서 유도되고 `--config-dir`만 따르므로 문서의 `<설정 폴더>/collector-state/stop-request`와 일치. 인터페이스·DB 스키마·`runtime-config.json` 설정 키·REST API 변경 없음. 작은 누락은 점검표 밖 N1 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. `RunGuard`는 주입된 `Clock`을 그대로 쓰고 새 시각 호출이 없다. 골든 테스트는 전체 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 테스트가 있다: 시작 전 거부(두 명령 모두, `--allow-during-session`으로도 안 풀림, HTTP 호출 0건, 스냅샷 폴더 없음, 파일 남음, 파일 삭제 후 재실행 성공), 실행 중 생성 시 다음 호출 전 멈춤(호출 1건에서 멈춤, 종료 코드 4), `RunGuard` 단위(해제 옵션 무관, 반복 호출에도 거부, 파일 유지), ArchUnit 규칙의 위반 고정 예제(메서드 참조 `ProcessHandle::destroyForcibly`와 `Process.destroy()` 둘 다 잡음). 기존 테스트 삭제·`@Disabled` 없음(diff 확인) |
| B7 | risk-ops 검토 필요 | 이 PR은 장애 대응 규칙(`broker-and-fault-design.md` 5.2 정지 순서에 0번 추가, 6장 장애표 #10 실전 대응 문구)을 바꾸고 보호 테스트 `ArchitectureTest`에 규칙을 더한다(추가, 약화 아님). 지침 10장 머지 권한의 "장애 대응" 항목에 해당하므로 risk-ops의 A 점검표 검토가 필요하고 머지는 사용자가 한다. 이 검토 시점에 `docs/reviews/pr-46/risk-ops.md`는 저장소(브랜치·작업 트리)에 없다 |
| B8 | 통과 | 새 메시지는 정지 요청 파일 경로만 담고(`RunGuard.java:71-72`) 키·계좌·토큰이 없다. diff의 `+` 줄에 비밀값 패턴 없음(테스트는 기존 `writeKeys()` 자리표시자 사용) |

## 점검표 밖 발견
모두 머지를 막지 않는 권고다.

- **N1 (문서 누락, 경미)**: `CollectorMain.java:36` 클래스 Javadoc의 종료 코드 4 설명이 "장 시간 보호 구간, 디스크 여유 부족"에 머물러 있다. runbook 4장과 ADR-008은 "감시의 정지 요청"을 넣었으니 같은 문구로 맞추길 권한다.
- **N2 (기획서 판 번호 충돌)**: 이 PR은 기획서를 v14.10 → **v14.12**로 올린다. 열려 있는 다른 브랜치 `claude/project-thread-2w1z9y`(PR #38 쪽, D-056 반영)도 **v14.12**를 쓰고, `claude/project-thread-ngnjxp`는 v14.11을 쓴다(각 브랜치의 `docs/spec/autotrade-spec.md` 머리 표 확인). 지금 main과는 충돌이 없지만 둘 중 나중에 머지하는 쪽은 판 번호와 변경 이력 줄을 다시 매겨야 한다. 머지 순서가 정해지면 정리하길 권한다.
- **N3 (fail-open 가능성, 경미)**: `RunGuard.checkSession`은 `Files.exists(stopRequest)`를 쓴다. `Files.exists`는 존재 여부를 판단하지 못할 때(권한 오류 등) false를 돌려줘 정지 요청을 못 본 채 진행한다. 같은 폴더에 `CallBudget`이 기록하므로 실제로는 그 단계에서 실패할 가능성이 높아 위험은 낮다. 안전 쪽으로 닫으려면 `!Files.notExists(stopRequest)`(판단 불가 시 정지)를 권한다. `[추론]`
- **N4 (생성자)**: 5인자 생성자는 `stopRequest = null`로 정지 요청 확인을 끄고 Javadoc에 "테스트 전용"이라고만 적었다. 지금 운영 호출은 6인자뿐(`CollectorMain.java:70`, `grep "new RunGuard("` 확인)이지만, 컴파일러가 막지 않으므로 나중에 운영 코드가 5인자를 쓰면 정지 요청이 조용히 꺼진다. 테스트 쪽 헬퍼로 옮기거나 운영 경로에 non-null 검사를 두길 권한다.
- **N5 (규칙 범위)**: `WATCHDOG_KILLS_NO_PROCESS`는 D-058·ADR-007 2.7이 정한 대로 `Process`·`ProcessHandle`의 `destroy*`만 막는다. `ProcessBuilder`/`Runtime.exec`로 `kill`·`taskkill`을 띄우는 우회는 잡지 않는다. 감시는 장애표 #8 자동 재기동 때문에 프로세스 실행 API가 필요할 수 있어 지금 일괄 금지는 맞지 않다. 감시 쪽 자원 임계치·정지 요청 구현(D-058 ④ 후속 과제) PR에서 외부 명령 인자 허용 목록 같은 보강을 검토하길 권한다.
- **N6 (사실 정정 확인)**: ADR-008 2.7의 예전 문구 "품질 리포트 단계는 중간에 멈추지 않는다"를 "리포트를 만드는 동안 하루 읽을 때마다 멈춘다"로 고쳤다. main의 코드가 이미 `DailyBarQualityCheck.checkSnapshot(dir, guard::checkSession)`(`CollectorMain.java:155`)이라 새 문구가 코드와 맞다. 정지 요청도 같은 `checkSession`을 타므로 ADR의 확인 지점 서술과 일치한다.
- 작은 정리: `ArchitectureTest.java:15` 새 import가 알파벳 순서(engine 앞)를 벗어난다. 동작 영향 없음.

## 판정
수정 필요(B7: 장애 대응 규칙 변경이므로 risk-ops 검토 원문 `docs/reviews/pr-46/risk-ops.md`가 필요. 그 밖의 B 항목은 통과 또는 해당 없음이며 아키텍처 관점의 코드 수정 요구는 없다. 지침 10장에 따라 머지는 사용자가 한다)

## 재검토 2026-10-11
- 대상: PR #46 / 커밋 4e69072(앞 검토 뒤 05cb14b main 병합, 4e69072 검토 반영) / `git diff origin/main...HEAD` 전체(병합 기준 51eb6ef = 현재 main, 15개 파일 +220 −21). 16bf953 → 05cb14b 사이 나머지 변경은 main(#41·#43) 병합분이며 `origin/main...HEAD` diff에 나타나지 않음을 확인
- 점검표: B
- 직접 실행한 것 (새 복제본 `/tmp/claude-0/review-architect-46-r2`, 4e69072 체크아웃):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(122 tasks 모두 실행). 결과 XML 합계 804/0/0/0(tests/failures/errors/skipped). 새 테스트 4건(`ArchitectureTest#감시는_프로세스를_죽이지_않는다`, `RunGuardTest#정지_요청_파일은_해제_옵션과_상관없이_막고_지우지_않는다`, `CollectorMainTest`의 정지 요청 2건) 통과.
  - 변이 확인 ①: `autotrade-monitor-agent-jar`에 `com.autotrade.monitor.ProbeKill`(`Process.destroyForcibly()` 호출)을 넣고 `ArchitectureTest` 실행 → `감시는_프로세스를_죽이지_않는다` 실패(`ProbeKill.k(Process) calls method Process.destroyForcibly()`). 확장된 대상 패키지가 실제로 검사된다.
  - 변이 확인 ②: 임시 테스트로 정지 요청 경로의 부모가 일반 파일인 경우(판단 불가)를 만들어 확인 → `Files.exists`=false, `Files.notExists`=false, `RunGuard.checkSession`은 `Refused`(멈춤 쪽). 예전 코드였다면 그냥 진행했을 경우가 이제 닫힌다.
  - 두 변이 모두 지우고 작업 트리 깨끗함 확인.
  - 브랜치에 커밋된 `docs/reviews/pr-46/architect.md`가 앞 검토 원문과 같음을 `diff`로 확인(고쳐지지 않음).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL, 804건 실패 0·건너뜀 0 |
| B2 | 통과 | `WATCHDOG_KILLS_NO_PROCESS` 대상이 `com.autotrade.app.watchdog..`, `com.autotrade.monitor..`로 넓어짐(`ArchitectureTest.java:242`, 강화). D-058 ①에 같은 범위가 적혀 있다. `com.autotrade.monitor`는 `ModuleOwnershipTest.OWNERS`(`:118`)에서 `autotrade-monitor-agent-jar` 소유. 규칙은 의존을 더하지 않아 monitor-agent-jar의 도메인 무지 원칙과 무관. 운영 코드 새 의존 없음 |
| B3 | 통과 | 앞 근거 유지. 이번에 더한 것: 장애표 #10 감지 칸(남은 정지 요청 파일, `broker-and-fault-design.md:169`), runbook 3장 복구 절차와 "감시와 같은 설정 폴더로 실행", D-058 ① 문구, 클래스 Javadoc 종료 코드 4(`CollectorMain.java:36`). 기획서는 main의 v14.11(#43) 다음 **v14.13**으로, 열린 `claude/project-thread-2w1z9y`의 v14.12와 겹치지 않는다(앞 N2 해소. 단 v14.12가 먼저 머지되지 않으면 판 번호 순서가 잠시 건너뛴다. 기능 영향 없음) |
| B4 | 해당 없음 | 시뮬레이션 변경 없음. `RunGuard.modified`는 파일 시각을 읽을 뿐 시계를 부르지 않는다 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 앞 테스트 유지, 삭제·`@Disabled` 없음. 5인자 생성자 제거에 따라 테스트 호출부는 `null`을 명시하도록 바뀜(`RunGuardTest.java:24,67,70`, `CollectorMainTest.java:324`, `KrxScaleProbe.java:57`), 검사 약화 아님. 이번 커밋의 새 동작 두 가지(판단 불가 시 멈춤, 확인 위치 출력)에는 전용 테스트가 없다. 판단 불가 시 멈춤은 검토자가 위 변이 ②로 직접 확인했고, 출력은 안내 문구라 막는 항목으로 보지 않는다(아래 R1 권고) |
| B7 | 통과(risk-ops 검토 있음, 재검토 필요) | 장애 대응 규칙 변경이므로 A 점검표 대상. `docs/reviews/pr-46/risk-ops.md`가 브랜치에 있다(16bf953 대상, 판정 수정 필요 F1·F2). 4e69072가 F1(장애표 #10 감지·runbook 복구)·F2(규칙 대상에 monitor 포함)를 반영했으나 **risk-ops 재검토 원문은 아직 없다**. risk-ops 재검토 판정 "승인" 전에는 머지 조건이 갖춰지지 않는다. 지침 10장에 따라 머지는 사용자가 한다 |
| B8 | 통과 | 새 출력은 정지 요청 경로(`<설정 폴더>/collector-state/stop-request`)와 파일 시각뿐이다(`CollectorMain.java:72-73`, `RunGuard.java:67-69`). 설정 폴더 경로는 비밀값이 아니다. diff `+` 줄에 키·토큰·계좌 없음 |

### 앞 검토 권고 처리
| # | 처리 | 근거 |
|---|---|---|
| N1 Javadoc 종료 코드 4 | 반영 | `CollectorMain.java:36` |
| N2 기획서 판 번호 충돌 | 반영 | v14.13으로 다시 매김(위 B3) |
| N3 fail-open | 반영 | `!Files.notExists(stopRequest)`(`RunGuard.java:66`), 변이 ② 확인 |
| N4 null 생성자 | 일부 반영 | 5인자 생성자는 없앴다. 6인자 생성자는 여전히 `null`이면 확인을 끄고 운영 경로에서 막는 장치가 없다(`RunGuard.java:53-56`). 운영 호출은 `CollectorMain.java:70` 하나라 지금 위험은 없다 |
| N5 ProcessBuilder 우회 | 후속 과제로 유지 | 감시 쪽 구현 PR에서 검토(앞 검토와 같은 이유) |
| N6 | 해당 없음 | 사실 확인 항목 |

### 재검토 점검표 밖 발견 (권고, 머지 막지 않음)
- **R1**: 판단 불가 시 멈춤 동작을 회귀 테스트로 고정하길 권한다. 정지 요청 경로의 부모를 일반 파일로 만들면 OS와 권한에 상관없이 `Files.notExists`=false·`Files.exists`=false가 되어 재현된다(검토자 변이 ②와 같은 방법). 지금은 누가 `Files.exists`로 되돌려도 테스트가 잡지 못한다.
- **R2**: N4 잔여. 운영 경로에서 `null`을 못 넘기게 하려면 `RunGuard`가 `Objects.requireNonNull`을 하고, 테스트에서는 존재하지 않는 임시 경로를 넘기는 방식(risk-ops N2 ② 제안과 같음)을 권한다.

### 재검토 판정
수정 필요(B7: risk-ops 재검토 원문 필요. architect가 요구하는 코드·문서 수정은 없다. R1·R2는 권고다. risk-ops 재검토가 "승인"이면 architect 쪽 막는 항목은 없다. 머지는 지침 10장에 따라 사용자가 한다)

## 재검토 2026-10-11 (2)
- 대상: PR #46 / 커밋 3cbb3e2 / `git diff origin/main...HEAD` 전체(병합 기준 51eb6ef). 4e69072 → 3cbb3e2 변경은 `RunGuard.java`, 테스트 3개, D-058 ④, runbook 3.5, 장애표 #10, 검토 원문 2개(pr-46/architect.md, risk-ops.md)
- 점검표: B
- 직접 실행한 것 (새 복제본 `/tmp/claude-0/review-architect-46-r3`, 3cbb3e2 체크아웃):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(122 tasks 모두 실행). 결과 XML 합계 805/0/0/0(tests/failures/errors/skipped). 새 테스트 `RunGuardTest#정지_요청이_있는지_판단할_수_없으면_멈춘다` 포함 정지 요청 관련 5건과 `ArchitectureTest#감시는_프로세스를_죽이지_않는다` 통과.
  - 변이 확인: `RunGuard.checkSession`의 `!Files.notExists(stopRequest)`를 `Files.exists(stopRequest)`로 되돌리고 `:autotrade-app-collector:test --tests '*RunGuardTest'` → `정지_요청이_있는지_판단할_수_없으면_멈춘다` 실패. 새 테스트가 판단 못 하면 멈추는 동작을 실제로 고정한다. 원복 후 작업 트리 깨끗.
  - main이 9100cb3(#42)으로 앞서 있어 복제본에서 `origin/main`을 병합(충돌 없음, `docs/decisions/README.md` 자동 병합)하고 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**.
  - 브랜치에 커밋된 `docs/reviews/pr-46/architect.md`가 앞 재검토까지의 원문과 같음을 `diff`로 확인.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, 브랜치 단독과 main 병합본 모두 BUILD SUCCESSFUL |
| B2 | 통과 | 이번 커밋에 의존·ArchUnit 규칙 변경 없음. 앞 재검토 근거 유지(`WATCHDOG_KILLS_NO_PROCESS` 대상 watchdog·monitor) |
| B3 | 통과 | 운영 절차 계약 변경: runbook 3.5 ③에서 "사람이 파일을 지우는 예외"를 없애 D-058 ①·5.2 0번·ADR-008 2.7의 "파일은 감시만 지운다"와 문서가 다시 일치한다(`docs/runbooks/collector.md:59`, `docs/decisions/README.md:59`, `broker-and-fault-design.md:144`, ADR-008 2.7). 사용자 결정 문구를 바꾸지 않고 절차를 결정에 맞춘 쪽이라 새 결정 번호가 필요 없다. 장애표 #10 감지 칸은 실제 감지 수단(일괄 명령 거부 메시지·종료 코드, 사용자가 예약 작업 결과로 확인)만 적고 감시 생존 감지가 없음을 명시하며 D-058 ④ 후속 과제(기획서 MON-02, `autotrade-spec.md:262`)로 넘겼다. 설정 키·스키마·REST 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 변경 없음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 앞 재검토 R1 반영: 판단 불가 시 멈춤 회귀 테스트 추가(`RunGuardTest.java:89-96`, 부모가 일반 파일), 위 변이 확인으로 효력 확인. 테스트 삭제·`@Disabled` 없음. `null` 대신 없는 경로 `RunGuardTest.NO_STOP`(`build/no-such-dir/stop-request`, Gradle 테스트 작업 폴더 기준 상대 경로)을 넘겨 정지 요청 확인을 끄지 않고 "요청 없음"으로 돈다 |
| B7 | 통과(risk-ops 재검토 진행 중) | 장애 대응 규칙 변경이므로 A 점검표 대상. `docs/reviews/pr-46/risk-ops.md`의 4e69072 재검토 판정은 수정 필요(R1 #10 감지 칸, R2 사람이 지우는 예외)이고, 3cbb3e2가 R1은 감지 칸을 사실대로 고쳐, R2는 예외를 결정 쪽에 넣는 대신 절차에서 없애는 방식으로 대응했다. R2 대응이 risk-ops 권고(예외를 D-058에 반영하고 사용자 확인)와 다른 선택이므로 받아들일지는 risk-ops가 판단한다. 3cbb3e2 대상 risk-ops 원문은 이 검토 시점에 없다(동시 검토 중). 머지는 지침 10장에 따라 사용자가 한다 |
| B8 | 통과 | 이번 커밋에 새 출력 없음. 앞 근거 유지 |

### 앞 재검토 권고 처리
| # | 처리 | 근거 |
|---|---|---|
| R1 판단 불가 회귀 테스트 | 반영 | `RunGuardTest#정지_요청이_있는지_판단할_수_없으면_멈춘다`, 변이로 효력 확인 |
| R2 null로 확인 끄기 | 반영 | `RunGuard.java:55` `Objects.requireNonNull(stopRequest, "stopRequest")`, `checkSession`의 null 분기 제거(`:65`). 이제 정지 요청 확인을 끄는 생성 경로가 없다 |
| N5 ProcessBuilder 우회 | 후속 과제로 유지 | 감시 쪽 구현 PR(D-058 ④) |

### 점검표 밖 발견 (권고, 머지 막지 않음)
- **S1 (운영 위험 기록)**: 사람이 지우는 예외를 없앴으므로, 감시가 고장 난 채 파일이 남으면 고칠 때까지 수집과 선정 생산이 멈춘다. 실전 경로에는 영향이 없고(멈추는 쪽), 캐시에서 이어 받으므로 데이터 손실도 없다. 다만 선정 생산이 멈추면 다음 거래일 선정 결과가 `MISSING`이 되는 경로(ADR-007 2.7)가 생긴다. D-058 ④ 후속 과제에서 감시 생존 감지를 정할 때 이 경로의 알림도 함께 다루길 권한다.
- **S2 (경미)**: `RunGuardTest.NO_STOP`과 `KrxScaleProbe`의 없는 경로는 테스트 작업 폴더 기준 상대 경로라, 누가 그 위치에 `stop-request`를 만들면 테스트가 엉뚱하게 멈출 수 있다. `@TempDir` 아래 경로를 쓰면 더 단단하다. 지금 동작에는 문제가 없다.

### 재검토 판정 (2)
승인(architect 범위. B 전 항목이 통과 또는 해당 없음이고 확인 못 한 항목이 없다. B7은 risk-ops의 3cbb3e2 재검토 원문 판정이 "승인"이어야 머지 조건이 갖춰지며, 장애 대응 변경이므로 지침 10장·11장에 따라 독립 QA 뒤 사용자가 머지한다)
