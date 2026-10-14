# architect 검토: LiveLabServiceTest: boards 밖 종목 처리까지 기다려 경합 없앰(risk-ops PR #94 F2)
- 대상: PR #95 / 커밋 926aec5 / main(eb46a95) 대비 diff (`git diff origin/main...HEAD`)
- 점검표: B
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD` → 1파일(`autotrade-app-lab/src/test/java/com/autotrade/app/lab/live/LiveLabServiceTest.java`), +3 −1. 운영 코드·빌드 스크립트·문서·스키마·설정 변경 없음. merge-base = origin/main 머리 eb46a95.
  - 격리 사본(`git archive 926aec5`를 scratchpad에 풀어 저장소 밖에서)에서 `./gradlew build --no-daemon` → EXIT 0. 테스트 결과 XML 합계 1060개, 실패 0, 오류 0, 건너뜀 0. `autotrade-architecture-test` 31개 통과. `LiveLabServiceTest` 6개 통과.
  - 같은 사본에서 `./gradlew :autotrade-app-lab:test --tests '*LiveLabServiceTest' --rerun-tasks` 5회 연속 → 5회 모두 통과.

## 변경 내용 확인
- [사실] 바뀐 줄은 `LiveLabServiceTest.java:110-112`. 기다리는 조건을 `events == 20`에서 `events == 20 && skippedSymbol == 1`로 넓혔다. 단언(`:113`)과 나머지 시험 본문은 그대로다.
- [사실] 조건이 맞는지 운영 코드로 확인했다. `LiveSession.accept`(autotrade-sim/.../LiveSession.java:244-252)는 boards 밖 종목이면 `skippedSymbol++`만 하고 돌아가며 `position`(= `Status.events`)을 올리지 않는다. 따라서 20건 + 뺄 종목 1건의 최종 상태는 정확히 `events == 20 && skippedSymbol == 1`이고, 이 조건이 성립하면 21번째 이벤트 처리가 끝난 것이다.
- [사실] `status()`와 `accept()`는 둘 다 `synchronized`(LiveSession.java:244, :352)라 두 값이 한 스냅샷에서 읽힌다. 조건 안에서 `status()`를 두 번 부르지만 두 값 모두 단조 증가이고 목표값에서 더 오르지 않으므로(추가 이벤트 없음) 두 번 읽어도 거짓 성립은 없다.
- [추론] risk-ops PR #94 F2가 지적한 경합(20건 처리 직후 21번째 처리 전에 단언)은 이 변경으로 없어진다. 조건이 끝내 안 맞으면 `await`가 10초 뒤 `AssertionError`로 실패하므로 결함을 숨기지 않는다(LiveLabServiceTest.java:83-91).
- 시험을 약화하지 않았다: 단언 `skippedSymbol == 1`이 남아 있고 기대값도 같다. `:113`의 단언은 이제 대기 조건과 겹쳐 사실상 항상 참이지만, 해가 없고 의도를 드러내므로 그대로 둬도 된다(참고).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 사본에서 `./gradlew build` EXIT 0, 테스트 1060개 실패·건너뜀 0 |
| B2 | 통과 | 운영 코드·의존성·ArchUnit 규칙 변경 없음(diff는 시험 1파일). `autotrade-architecture-test` 31개 통과, 빌드에 포함된 순수 모듈 금지 의존 검사 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음(diff 1파일, 시험 코드) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 시험의 `Clock.systemUTC()`는 기존 줄(LiveLabServiceTest.java:72)로 이 PR이 바꾸지 않았다. 골든 테스트 포함 `autotrade-sim` 시험 전부 통과(빌드 결과) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 없음(시험 안정화). 시험을 지우거나 건너뛰지 않았고 단언을 유지했다(LiveLabServiceTest.java:111-113). 5회 반복 실행 모두 통과 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응·보호 테스트(`ArchitectureTest`, Guard 시험, 장애 시험) 변경 없음. 바뀐 것은 실험실(`autotrade-app-lab`) 시험 1개 |
| B8 | 통과 | diff에 비밀값·계좌번호·토큰 없음(추가 3줄 전부 확인) |

## 점검표 밖 발견
- 없음. (참고: `:113` 단언이 대기 조건과 겹친다. 차단 사유 아님.)
- 빌드는 저장소 밖 격리 사본에서 돌렸다. 반복 실행은 부하 없는 상태였으므로, 원래 경합이 났던 고부하 조건을 재현한 것은 아니다. 다만 대기 조건이 최종 상태 자체를 기다리므로 부하와 무관하게 경합이 없다고 본다[추론].

## 판정
승인
