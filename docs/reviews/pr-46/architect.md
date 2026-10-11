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
