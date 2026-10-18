# architect 검토: 실전 엔진 기동 실패 시험 보강(PR #151 QA Q1·Q2·R1)
- 대상: PR #165 / 커밋 66a8aae5 / `git diff origin/main...HEAD`(3파일: `EngineMain.java` +2 −1, `EngineMainTest.java` +75, `D-107-engine-start-failure-kinds.md` +8)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL(3m 27s, 138 tasks). `EngineMainTest` 22건 실패·건너뜀 0(build/test-results XML), 새 시험 2건 포함.
  - 변이 4개를 `EngineMain.java`에 임시로 넣고 `:autotrade-app-engine:test --tests '*EngineMainTest'` 실행 후 원복(작업 트리 깨끗함 확인):
    - M4 `report`가 늘 예외 흐름 기록 → 1건 실패(잡힘)
    - M5 `report`가 예외 흐름 기록 안 함 → 1건 실패(잡힘)
    - M10 프로그램 오류 첫머리를 "실전 엔진이 기동 중 멈췄습니다"로 → 1건 실패(잡힘)
    - M11 명령 채널 첫머리를 "실전 엔진 기동 실패"로 → 1건 실패(잡힘)
    - PR #151 QA(docs/reviews/pr-151/qa.md:15-21)에서 생존이던 4개가 모두 잡힌다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, BUILD SUCCESSFUL. `EngineMainTest` tests=22 failures=0 errors=0 skipped=0 |
| B2 | 통과 | 의존성·모듈 변경 없음. 운영 코드 변경은 `EngineMain.java:277-279` 상수 문자열과 주석뿐. ArchUnit·`verifyPureDependencies` 빌드에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 표준 오류·로그 문구(운영자 계약 성격)는 D-107 "후속 반영" 절(D-107:42-48)에 기록, runbook이 기대는 낱말 "프로그램 오류"·"명령 채널을 열지 못해"(docs/runbooks/README.md:64)는 유지되고 Q2 시험이 글자로 고정(EngineMainTest.java:491-495) |
| B4 | 해당 없음 | 시뮬레이션 코드 무관. 시험은 기존 `FIXED` Clock 사용 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 시험 추가만, 삭제·`@Disabled`·약화 없음. Q1: `#진입점은_프로그램_오류만_로그에_예외_흐름을_남기고_설정_오류와_명령_채널_실패는_한_줄만_남긴다`(EngineMainTest.java:425, 단언 :467-471)가 JUL 처리기로 `System.Logger` 기록의 `getThrown()`을 직접 본다. Q2: `#기동_실패_표준_오류_첫머리는_runbook이_안내하는_낱말로_시작한다`(:475, `)(` 없음 :496). 위 변이 4개로 실효 확인 |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-engine/*`은 보호 경로(scripts/ci/protected-paths.txt:8). 바뀐 것은 기동 실패 표시 문구와 시험뿐이고 판정(`StartFailure.of`), 종료 코드, Guard 기동(모든 계좌 OFF), 주문 경로는 diff에 없음. 그래도 보호 경로 변경이므로 **risk-ops 검토 필요**, 머지는 사용자 직접(지침 10·11장) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 픽스처는 `"C:\\x"` 경로, `"꾸리기 실패"` 메시지뿐. Q1 시험이 설정 오류·명령 채널 실패의 원인 예외가 로그에 실리지 않음을 고정해 비밀값 유출 방향(M4)을 막는다 |

## 점검표 밖 발견
- (권고, 판정 무관) 감시도 같은 괄호 이중 문제가 남아 있다: `WatchdogMain.java`의 `PROGRAM_ERROR_HEADLINE`이 ")"로 끝나고 표준 오류 줄이 `"(감시 없이 실전·실험을 돌리지 마세요): "`를 붙인다. 이 PR 범위 밖이지만 D-106 후속으로 같은 정리를 권한다.
- (권고) D-107 결정 3번 본문(D-107:17)은 옛 문구를 그대로 둔 채 "후속 반영" 절에서 바꿨다고 적었다. 기록 방식으로는 문제없으나 3번 줄에 "(2026-10-18 문구 변경, 아래 후속 반영)" 표시를 달면 읽는 사람이 옛 문구를 현행으로 오인하지 않는다.
- (참고) Q1 시험은 JUL 백엔드(`System.Logger` 기본 구현)에 기댄다. 엔진 모듈에 SLF4J 등 다른 System.LoggerFinder가 들어오면 이 시험이 기록을 못 받아 실패한다(조용히 통과하지는 않음, `hasSize(3)`). 지금 의존성에서는 문제없다.
- (참고) 테스트가 같은 JVM의 로거에 처리기를 붙인다. 이 저장소에 JUnit 병렬 실행 설정이 없어(junit-platform.properties의 parallel 없음) 다른 시험의 SEVERE가 섞일 위험은 지금 없다.

## 판정
승인
