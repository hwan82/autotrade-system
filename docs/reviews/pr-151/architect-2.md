# architect 재검토: D-107 실전 엔진 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R2)
- 대상: PR #151 / 커밋 a210bf33fccf63544c8af2c905fe1baaf4c6a3f0(gh api pulls/151 head와 같음) / `git diff origin/main...HEAD` 전체(merge-base 1bb2f3e, 커밋 4e778bd·32f0655·319f4b1·a210bf3, 8파일: `EngineMain.java`, `EngineMainTest.java`, `D-107-engine-start-failure-kinds.md`, `docs/decisions/README.md`, `docs/qa/pending.md`, `docs/runbooks/README.md`, `docs/reviews/pr-151/{architect,risk-ops}.md`). 1차 원문: [architect.md](architect.md)
- 점검표: B
- 직접 실행한 것:
  - a210bf3을 별도 worktree로 떼어 `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL in 4m 39s**(138 tasks 모두 실행). 테스트 결과 XML 합계 1512건, 실패 0, 오류 0, 건너뜀 0(1차 1510건 + 새 시험 2건). `EngineMainTest` 20건 통과(새 `진입점은_실제_설정_폴더_값이_경로_형식이_아니면_설정_오류로_종료_코드_2로_끝낸다`, `프로그램_오류만_예외_흐름을_남기는_종류로_판정한다` 포함). `:autotrade-architecture-test:test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest, NotificationSetupMappingTest, StopRequestPathTest) 통과.
  - `git merge-tree --write-tree origin/main HEAD`: 충돌 없음(main은 0a4b692까지 진행, 겹치는 파일 없음).
  - `gh api repos/hwan82/autotrade-system/pulls/148`: open, 미머지.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 worktree에서 `./gradlew build --rerun-tasks` 성공, 1512 tests / 0 failures / 0 errors / 0 skipped |
| B2 | 통과 | a210bf3의 코드 변경은 `autotrade-app-engine` 안 레코드 필드 추가와 시험 2건뿐. 새 import 없음, ArchUnit 규칙 무변경, architecture-test 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST API 변경 없음. `StartFailure`는 패키지 전용 레코드(EngineMain.java:273)라 모듈 밖 계약이 아님. 종료 코드 2와 문구는 D-107과 runbook 종료 코드 표에 반영(1차 판단 유지) |
| B4 | 해당 없음 | 시뮬레이션 코드 무변경. 새 시험도 `FIXED` 시계 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 1차 권고 2 반영: 예외 흐름 여부를 문구 비교 대신 `programError` 필드로(EngineMain.java:273, 282-287, 293), 세 갈래를 `프로그램_오류만_예외_흐름을_남기는_종류로_판정한다`(EngineMainTest.java:425)가 고정. risk-ops 권고 2 반영: 운영과 같은 `ConfigPaths::defaultLocation`에 NUL 문자 시스템 속성을 넣어 `InvalidPathException`→`EngineRefusal`→종료 코드 2와 문구를 확인(EngineMainTest.java:403-422). 시스템 속성이 환경 변수보다 먼저 읽히므로(ConfigPaths.java:29-35) CI의 `AUTOTRADE_CONFIG_DIR` 설정과 무관하게 같은 경로를 타고, finally에서 원래 값을 되돌린다. 이 저장소에 JUnit 병렬 실행 설정(junit-platform.properties·maxParallelForks)이 없어 속성 오염 경합도 없음. 기존 시험 삭제·약화·`@Disabled` 없음 |
| B7 | 통과(조건) — **risk-ops 검토 필요** | `autotrade-app-engine/*`는 보호 경로(scripts/ci/protected-paths.txt). a210bf3도 실전 엔진 진입점 코드를 바꾸므로 risk-ops 재검토(A 점검표)가 a210bf3 기준으로 따로 있어야 한다. 현재 `docs/reviews/pr-151/risk-ops.md`는 4e778bd(a210bf3 이전) 기준. Guard 생성·OFF 시작, 명령 채널·잠금 순서는 a210bf3에서 바뀌지 않음을 diff로 확인. 사용자 머지·독립 QA 별도 필요 |
| B8 | 통과 | 새 출력 없음(같은 `report` 경로). 새 시험의 설정 폴더 값은 가짜 문자열. diff에 비밀값 없음 |

## 1차 지적 처리 확인
1. (권고, 머지 순서) D-106 링크: D-107 문서에 "PR #148 뒤에 머지한다" 줄을 추가(D-107-engine-start-failure-kinds.md:5), pending.md 행에도 "PR #148 뒤 머지"를 적음. #148은 여전히 open이라 이 브랜치·main에서는 링크가 깨진 상태로 남지만 머지 순서로 해결하는 방식은 적절하다. **머지하는 사람이 순서를 지켜야 한다**(코드 판정과 무관).
2. (권고) 문구 비교 → `programError` 필드: 반영 확인(위 B6).
3~5. (확인 항목) 코드 무변경, 1차 판단 유지.

## 점검표 밖 발견
1. (참고, 비차단) `programError`는 `of`에서만 정해지는 값이라 `exitCode`·`headline`과 서로 맞지 않는 조합(예: 설정 문구에 `programError=true`)을 생성자가 막지는 않는다. 생성처가 `of` 하나(EngineMain.java:282-287, 다른 `new StartFailure` 없음)이고 패키지 전용이라 지금은 문제 없다.
2. (참고, 비차단) 새 판정 시험은 `programError()` 값만 보고, `report`가 실제로 예외 흐름을 로그에 넘기는지는 보지 않는다. `report`는 한 줄 분기라 위험은 낮다.
3. (참고) `docs/reviews/README.md`는 재검토를 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이라고 하나, 요청에 따라 별도 파일(architect-2.md)로 남긴다. 저장소에 같은 방식의 선례가 있다. 1차 원문은 고치지 않았다.

## 판정
승인 (코드·모듈 경계 기준. 보호 경로 변경이라 a210bf3 기준 risk-ops A 점검표 재검토, 독립 QA, PR #148 뒤 사용자 머지는 별도로 필요)
