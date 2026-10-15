# architect 검토: test(experiment): PR #113 비차단 권고 3건(결정론 확인 안 함 시험, run→runControlled 고정, Windows 경로 시험)
- 대상: PR #120 / 커밋 6c6d405925debf7f785c39716ba8f22ffd9afbef / main(6fe3101, 병합 기준과 같음) 대비 diff
- 점검표: B
- 직접 실행한 것:
  - 별도 clone(6c6d405 체크아웃)에서 `./gradlew build --no-daemon` → 종료 코드 0(BUILD SUCCESSFUL). 바뀐 묶음 결과 XML: `ExperimentJobTest` 18건, `CombinationExperimentTest` 6건, `WalkForwardStudyTest` 8건, 모두 실패·오류·건너뜀 0. 전체 결과 XML에 `skipped>0` 묶음 없음.
  - `git diff origin/main...HEAD` 전체(5개 파일, +126/−16) 확인. 보호 대상 경로(`scripts/ci/protected-paths.txt`) 일치 0건.
  - `gh api .../pulls/120`: `mergeable_state: clean`. 헤드 6c6d405 check-run 3건(Gradle build, DB schema, Secret scan and collector boundary) 모두 success.
  - 변이 실험 7건(별도 clone, 하나씩 넣고 해당 시험 묶음만 실행 후 되돌림). 결과는 아래 표.

| 변이 | 바꾼 곳 | 결과(실패한 시험) |
|---|---|---|
| M1 | `ExperimentMain.java:125` `runControlled(...)`를 `int c = body.run(..., control); control.finish(c); return c;`로(= risk-ops 3차 M7b, `afterCancel` 건너뜀) | 잡힘: `실행_명령_진입점은_본체를_작업_제어_아래에서_돌린다` (expected 4, was 3) |
| M2 | 같은 줄을 `return body.run(..., control);`로(`afterCancel`·`finish` 모두 건너뜀) | 잡힘: 새 시험 포함 4건 |
| M3 | `CombinationExperiment.java:166` 실제 병렬 수를 `plan.parallelism()`로(상한 무시) | 잡힘: `CombinationExperimentTest#실행_명령_병렬_상한이_1이면_결정론_확인을_같음으로_적지_않는다` |
| M4 | `WalkForwardStudy.java:144` 같은 변이 | 잡힘: `WalkForwardStudyTest#실행_명령_병렬_상한이_1이면_...` |
| M5 | `CombinationExperiment.java:172` 생략 조건을 `plan.parallelism() == 1`로(로그는 병렬 1, 비교 판단만 등록 값) | 잡힘: 같은 새 시험(inputs.yaml에 "같음(") |
| M6 | `WalkForwardStudy.java:149` 같은 변이 | 잡힘: 같은 새 시험(`deterministic()` expected null, was true) |
| M7 | `ExperimentMain.java:70` `.normalize()` 제거 | 잡힘: `설정_폴더는_상대_경로와_점점이_섞여도_...`의 **절대 비정규 경로** 경우(`[/tmp/junit-…/sub/..]`). 상대 경로 경우가 빠지는 Windows 다른 드라이브 환경에서도 이 변이는 잡힌다 `[추론]`(절대 경우는 조건 없이 늘 돈다) |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 별도 clone에서 `./gradlew build` 종료 코드 0, 위 세 묶음 실패·건너뜀 0. CI 3건 success(헤드 6c6d405) |
| B2 | 통과 | 운영 코드 변경은 `autotrade-app-experiment`의 `ExperimentMain` 한 파일(같은 패키지 안 패키지 비공개 `Body` 인터페이스와 4인자 `run` 추가). 새 의존 없음. `autotrade-sim` 변경은 시험 파일뿐. ArchUnit(`autotrade-architecture-test`) 빌드 안에서 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 계약 변경 없음. `ExperimentMain.Body`는 패키지 비공개 시험 이음새(`ExperimentMain.java:62-66`)이고 모듈 밖에 공개되지 않음. 기존 3인자 `run`·`main`의 동작은 `dispatch`로 위임해 그대로(`ExperimentMain.java:58-60`) |
| B4 | 통과 | 새 시험은 `Clock.fixed(WEEKEND, UTC)`·고정 등록 파일만 씀. 시스템 시계·난수 없음. 결정론 확인 생략 경로를 조합·워크포워드에도 고정(M3~M6이 잡힘). 골든·결정론 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 시험 3건(`ExperimentJobTest#실행_명령_진입점은_본체를_작업_제어_아래에서_돌린다`, 조합·워크포워드 `실행_명령_병렬_상한이_1이면_...`). 시험 삭제 줄 14줄은 모두 `설정_폴더는_상대_경로와_...` 시험을 반복문으로 옮긴 것으로, 같은 단언(`진행 상태 파일`, `정지 요청 파일 확인 위치`, `sub/..` 없음)이 경우마다 그대로 남고 절대 비정규 경우가 늘었다. Linux CI에서는 상대 경우도 돈다(M2 실패 메시지에 `[../../…]` 경우 확인). 약화 아님. 건너뛴 시험 없음 |
| B7 | 해당 없음 | 보호 대상 경로 일치 0건. 바뀐 운영 코드는 실험 실행 명령 진입점(실험실 측 단발 명령)의 시험 이음새뿐이고 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·증권사 주문 연동을 건드리지 않음. 정지 요청·장 시간 보호 동작(broker-and-fault-design 5.2 0번)은 바뀌지 않았고 오히려 `run`→`runControlled` 연결이 시험으로 고정됨(M1·M2) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(직접 확인). CI Secret scan success |

## 점검표 밖 발견
- PR #113 권고 반영 확인
  - architect R6(조합·워크포워드 "확인 안 함" 시험): 반영됨. M3~M6을 새 시험이 잡음. 생략 조건만 바꾸는 미묘한 변이(M5·M6)까지 잡는다.
  - architect 3차 R9(Windows 드라이브 다를 때 `relativize` 예외): 반영됨. 절대 비정규 경우를 늘 보고 상대 경우는 같은 루트일 때만 봄(`ExperimentJobTest.java:341-350`). Windows에서 돌려 보지는 못함 `[추론]`: `Path.getRoot()`가 드라이브 문자를 포함하므로 다른 드라이브면 상대 경우를 건너뛴다.
  - risk-ops 3차 R9(M7b): 반영됨. 같은 모양의 변이(M1)를 새 시험이 잡음.
- 관찰(권고, 막지 않음)
  - O1: 정규화 시험에서 상대 경로 경우를 조건으로 건너뛸 때 아무 흔적도 남기지 않는다. 절대 경우가 M7을 잡으므로 위험은 낮다. 다른 드라이브에서 상대 경우가 빠진 것을 보이게 하려면 그 경우만 별도 시험으로 떼어 `Assumptions.assumeTrue`를 쓰면 보고서에 "건너뜀"으로 남는다.
  - O2: 운영 클래스에 시험용 이음새(`Body`, 4인자 `run`)가 생겼다. 패키지 비공개이고 javadoc에 목적이 적혀 있어 받아들일 만하다. 같은 형태가 늘어나면 본체 선택(`dispatch`)을 별도 패키지 비공개 클래스로 빼는 편이 낫다.
  - O3: D-089 검증 절은 `ExperimentJobTest`(17) 줄을 그대로 두고 아래에 "후속 … (18)" 줄을 덧붙였다. 이력으로 읽히므로 문제는 없지만, 검증 절 수치를 결과 XML과 맞춰 보는 독립 QA가 혼동하지 않게 다음에 손댈 때 위 줄 수치를 18로 정리해도 된다.

## 판정
승인
