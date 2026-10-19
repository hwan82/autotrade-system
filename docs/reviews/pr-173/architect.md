# architect 검토: 감시 운영 배선이 알림 큐 받음 여부를 넘기는지 시험(PR #170 QA Q1)
- 대상: PR #173 / 브랜치 claude/project-thread-0ssuqw / 커밋 3c05a92 / `git diff origin/main...HEAD`(merge-base f81f2f7, 비교한 origin/main dc41d07)
- 점검표: B
- 직접 실행한 것:
  - `LC_ALL=C.UTF-8 ./gradlew -I <저장소 밖 mirror.gradle> build` (헤드 3c05a92) → 종료 코드 0. `WatchdogMainTest` 16건 실행, 실패·건너뜀 0, 새 시험 `운영_배선은_알림_큐가_받았는지를_그대로_감시에_넘긴다` 포함(build/test-results XML로 확인).
  - 변이 2종(검토자 직접, 한 번에 하나씩 바꾸고 `git checkout`으로 원복): `WatchdogMain.java`의 `notifications::offer`를 `(k,s,t,b) -> { notifications.offer(k,s,t,b); return true; }` / `return false;`로 바꿈 → 둘 다 `:autotrade-app-watchdog:test --tests '*WatchdogMainTest'`에서 1건 실패(always-false는 새 시험 실패로 XML 확인, always-true도 16건 중 1건 실패). 원복 후 작업 트리 깨끗.
  - `git merge-tree --write-tree origin/main HEAD` → **충돌**(`docs/qa-out-status.md`, 아래 N1).

변경 범위(diff 전체, 4파일 +32): `Watchdog.java` 패키지 전용 읽기 `alerts()` 추가(운영 동작 변화 없음), `WatchdogMainTest` 시험 1건, D-111 후속 반영 절, `docs/qa-out-status.md` 한 줄.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 3c05a92에서 `./gradlew build` 직접 실행, 종료 코드 0(순수 모듈 금지 의존 검사·ArchUnit 포함). main과 합친 상태는 문서 한 줄 충돌만 있어 코드가 헤드와 같다(N1) |
| B2 | 통과 | 새 의존 없음. `alerts()`는 패키지 전용(`Watchdog.java:417`), 기존 `notifications()`(`Watchdog.java:412`)와 같은 시험용 읽기. ArchUnit·`ModuleOwnershipTest` 빌드에서 통과 |
| B3 | 해당 없음 | 공개 계약(인터페이스·스키마·설정 키·REST) 변경 없음. `Alerts`는 패키지 전용 인터페이스이고 바뀌지 않음 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 새 시험은 고정 시계(`WatchdogMainTest.fixed`)로 같은 순간에 넣어 `NotificationQueue.QUIET` 억제가 시간에 기대지 않음(`NotificationQueue.java:152-157`) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 시험 `WatchdogMainTest#운영_배선은_알림_큐가_받았는지를_그대로_감시에_넘긴다`가 실제 기동(`WatchdogMain.start`)한 감시의 `alerts`로 받음 true → 같은 대상·종류 억제 false → 종류 바뀜 true → 닫은 뒤 false를 본다. 변이 "늘 true"·"늘 false" 둘 다 검토자 실행으로 잡힘. 지우거나 건너뛴 시험 없음 |
| B7 | risk-ops 검토 필요 | `autotrade-app-watchdog/*`는 보호 경로(`scripts/ci/protected-paths.txt`)이고 감시는 장애 대응(D-013 ④) 경로다. 운영 코드는 읽기 메서드 추가뿐이지만 프로젝트 지침 10·11장에 따라 risk-ops 검토와 사용자 머지 대상 |
| B8 | 통과 | diff에 토큰·키·계좌번호·웹훅 값 없음(키워드 검색 결과 0). 시험 값은 대상 이름 `wiring-probe`와 한글 문구뿐. gitleaks는 로컬에서 돌리지 않았고 CI가 본다 |

## 점검표 밖 발견
- **N1(머지 전 필수, 절차)**: 브랜치가 main(dc41d07 "PR #169 독립 QA 판정 처리 기록")과 `docs/qa-out-status.md` 마지막 줄에서 충돌한다. 둘 다 표 끝에 한 줄을 더한 것이라 두 줄을 모두 남기면 된다(날짜 순서 2026-10-18 #169 → 2026-10-19 #170). 충돌 상태에서는 GitHub가 머지 ref CI를 돌리지 않으니, main을 합친 뒤 CI 통과를 확인하고 머지한다. 코드 영향 없음.
- **N2(참고)**: 새 시험은 `Watchdog`이 `alerts` 필드를 그대로 받는지와 큐 반환값 전달을 고정한다. `Watchdog.alert()`가 그 필드 반환값을 쓰는지(`Watchdog.java:253`)와 false일 때 재전송 표시(`Watchdog.java:335`)는 기존 `WatchdogTest#큐가_억제한_결측_알림은_간격이_찬_반복_경고_때_한_번_다시_넣는다`·`#큐가_받은_결측_알림은_반복_경고_때_다시_넣지_않는다`(D-111)가 고정한다. 둘을 합쳐 QA Q1의 사슬이 닫힌다.
- **N3(참고, 안정성)**: 점검 스레드가 같은 큐에 넣는 정보 알림이 같은 10분 창에서 `MAX_PER_WINDOW`(20, `NotificationQueue.java:42`)를 채우면 세 번째 단언(`READING_RECOVERED`, 정보)이 false가 될 수 있다. 고정 시계라 점검 스레드의 같은 대상·종류는 억제되고, 기본 설정의 대상 수(프로세스·`stop-request`·`readings`)가 적어 지금은 일어나지 않는다[추론]. 지키는 대상을 크게 늘리는 시험 설정이 생기면 이 시험의 세 번째 단언을 긴급 종류로 바꾸는 것을 검토.
- D-111 후속 절의 "운영 동작 변경은 없다"는 diff와 일치한다(운영 코드 변화는 읽기 메서드 하나).

## 판정
승인 (N1 main 합치기와 머지 ref CI 통과는 머지 전 조건, B7에 따라 risk-ops 검토와 사용자 머지 필요)
