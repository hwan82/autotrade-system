# architect 검토: 실전 엔진 기동 실패 로그 원인·표준 오류 한 줄 시험(PR #165 QA Q1·Q2·R1)
- 대상: PR #169 / 브랜치 `claude/project-thread-ryxl0y` / 커밋 `ba2897e` / main 대비 diff(`git diff origin/main...HEAD`, 병합 기준 `7491fd8`. 이후 main `009b420`과 겹치는 파일 없음, `git merge-tree` 충돌 없음)
- 점검표: B
- 직접 실행한 것:
  - 별도 clone에서 `./gradlew clean build --no-daemon --continue` → **BUILD SUCCESSFUL**(3m 24s). 시험 결과 XML 합계 1566건, 실패 0, 오류 0, 건너뜀 0. 바뀐 두 시험 `EngineMainTest#진입점은_프로그램_오류만_로그에_예외_흐름을_남기고_설정_오류와_명령_채널_실패는_한_줄만_남긴다`, `#기동_실패_표준_오류_첫머리는_runbook이_안내하는_낱말로_시작한다` 통과(EngineMainTest 22건 모두 통과).
    - 참고: 같은 clone에서 앞서 돌린 빌드 두 번은 내가 실수로 겹쳐 돌려(같은 build 폴더를 두 Gradle이 동시에 씀) "Could not write XML test results"·컴파일 실패가 났다. 코드 탓이 아니어서 `clean` 뒤 단독으로 다시 돌린 위 결과로 판정한다.
  - 변이 시험(운영 코드 `EngineMain.StartFailure.report`를 직접 바꾸고 `:autotrade-app-engine:test --tests EngineMainTest`, 매번 원복):
    - N5(설정 오류·명령 채널 실패 로그 줄에서 `": " + detail` 삭제) → **잡힘**: Q1 시험 실패(설정 오류 줄 `isEqualTo` 불일치)
    - N6(프로그램 오류 로그 줄에서 `": " + detail` 삭제, 예외 흐름은 남김) → **잡힘**: Q1 시험 실패
    - N9(표준 오류에 `System.lineSeparator() + e` 둘째 줄 추가) → **잡힘**: Q2 시험 실패(`lines()` 1줄 단언)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 성공, 1566건 실패 0 |
| B2 | 통과 | 운영 코드·빌드 스크립트·의존 변경 없음(diff는 `EngineMainTest.java`, D-107 두 파일뿐). ArchUnit(`autotrade-architecture-test`)·`verifyProcessIsolation` 빌드 안에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 운영자 출력 문구도 바뀌지 않음(`EngineMain.java:275-279,293-299` 무변경). D-107 3번 본문 인용을 현행 문구로 고치고 옛 문구는 R1 후속 절에 보존(D-107:17, :46), 새 "후속 반영(2026-10-18, PR #165 독립 QA Q1·Q2·R1)" 절 추가. 판정·종료 코드 무변경이라 새 D 번호 불필요(PR #165 관행과 같음) |
| B4 | 해당 없음 | 시뮬레이션 코드 무관. 시험은 기존 `FIXED` Clock 사용 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 단언 추가만, 삭제·`@Disabled`·약화 없음(diff에서 기존 `startsWith`·`getThrown()` 단언 유지, EngineMainTest.java:466-472). Q1: ERROR 줄 전체 비교(프로그램 오류 `PROGRAM_ERROR_HEADLINE + ": IllegalStateException: 꾸리기 실패"`, 설정 오류 `SETTINGS_HEADLINE + ": 설정 폴더 경로가 틀렸습니다(...): " + badPath.getMessage()`), 명령 채널은 `ControlServer.java:100`의 잠금 원인 문구 포함(EngineMainTest.java:475-480). Q2: 세 표준 오류가 각각 한 줄(:507). 위 변이 N5·N6·N9 모두 잡힘으로 실효 확인 |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-engine/*`은 보호 경로(scripts/ci/protected-paths.txt). 바뀐 것은 시험 단언과 결정 문서뿐이고 운영 코드·판정(`StartFailure.of`)·종료 코드·Guard 기동(모든 계좌 OFF)·주문 경로는 diff에 없음. 그래도 보호 경로 변경이므로 **risk-ops 검토 필요**, 머지는 사용자 직접, 머지 전 독립 QA(지침 10·11장) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 픽스처는 `"C:\\x"` 경로 예외와 "꾸리기 실패" 메시지뿐. 커밋 메시지에도 비밀값 없음 |

## 점검표 밖 발견
- (참고, 판정 무관) D-107 새 후속 절의 "남은 한계"(예외 메시지에 줄바꿈이 있으면 표준 오류·로그 줄이 여러 줄)는 사실이다(`EngineMain.java:288,299`가 `e.getMessage()`를 그대로 붙임). Q2 시험은 한 줄 메시지 픽스처로만 보므로 이 경우를 막지 않는다. 출력 동작 변경이라 이 PR 밖으로 둔 판단에 동의한다. 다음 실전 엔진 변경 때 줄바꿈 접기와 시험을 함께 넣기를 권한다.
- (참고) Q1 시험의 명령 채널 단언은 `contains("다른 실전 엔진이 이미 실행 중입니다")`로 `autotrade-control`의 `ControlServer` 문구에 기댄다. 그 문구가 바뀌면 이 시험이 실패한다(조용히 통과하지 않음). 모듈 간 문구 결합이지만 runbook 표가 같은 상황을 안내하므로 지금은 받아들일 만하다 `[추론]`.
- (참고) PR #165 QA 판정 처리 기록은 이미 main `docs/qa-out-status.md`에 있다(PR #167). 이 PR은 그 기록이 "다음 실전 엔진 변경 PR"로 미룬 Q1·Q2·R1을 다룬다.

## 판정
승인
