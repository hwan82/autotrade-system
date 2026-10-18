# QA 검토: 실전 엔진 기동 실패 시험 보강(PR #151 QA Q1·Q2·R1)
- 대상: PR #165 / 커밋 5f8564ff62f3f1b1119a289f776f746de3ae17ac(브랜치 `claude/project-thread-vvq2up` 헤드, base main 9346538) / `git diff origin/main...HEAD` 전체(6파일: `EngineMain.java` +2 −1, `EngineMainTest.java` +75, `D-107-engine-start-failure-kinds.md` +9 −1, `docs/qa/pending.md` +1, `docs/reviews/pr-165/{architect,risk-ops}.md`)
- 점검표: A 전체 + B1, B6. `autotrade-app-engine/*`은 보호 경로(scripts/ci/protected-paths.txt:8)라 실전 관련 PR로 본다.
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`: 1회차 Maven Central 429(Too Many Requests, `jackson-bom-2.21.4.pom`)로 의존성 받기 실패. 20초 뒤 2회차 → **BUILD SUCCESSFUL in 2m 59s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 1,566건 / 실패 0 / 오류 0 / 건너뜀 0. `EngineMainTest` 22건 통과(새 시험 2건 포함).
  - GitHub 체크(헤드 5f8564f): Gradle build, DB schema, Lab screens, Secret scan 모두 success. `mergeable_state` clean.
  - 변이 시험(별도 worktree, `--offline :autotrade-app-engine:test --tests '*EngineMainTest'`, 확인 뒤 원복). 개발 쪽 architect·risk-ops가 이미 넣어 본 M4·M5·M10·M11 계열과 겹치지 않게 새로 9개를 넣었다. 9개 중 6개 잡힘:

| 변이 | 내용 | 결과 |
|---|---|---|
| N1 | `IOException`(명령 채널 실패)을 `programError=true`로(로그에 원인 예외 흐름이 실림) | 잡힘(2건, Q1 시험 포함) |
| N2 | `EngineRefusal`(설정 오류)을 `programError=true`로 | 잡힘(2건) |
| N3 | 표준 오류에서 "(실전 엔진이 뜨지 않았습니다)" 삭제 | 잡힘(1건) |
| N4 | 프로그램 오류 로그 수준 ERROR→WARNING | 잡힘(1건) |
| N5 | 설정 오류·명령 채널 실패의 로그 줄에서 `": " + detail`(실제 원인) 삭제 | **생존** |
| N6 | 프로그램 오류의 로그 줄에서 `": " + detail` 삭제(예외 흐름은 남김) | **생존** |
| N7 | 프로그램 오류 첫머리에서 "설정 파일 문제가 아니니" 삭제 | 잡힘(1건) |
| N8 | 프로그램 오류 첫머리를 다시 괄호로 끝냄(`...(코드 문제)`) | 잡힘(1건, `)(` 검사) |
| N9 | 표준 오류에 예외 `toString()` 둘째 줄 추가 | **생존** |

## 점검표

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경은 `EngineMain.java:277-279` 상수 문자열과 주석뿐. `startGuard`(EngineMain.java:191-201, 모든 계좌 OFF), `StartFailure.of`(:281-290) 판정·종료 코드, `launch`(:100-110, 실패 시 `exit` 뒤 null) 그대로. `EngineMainTest#진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다`, `#진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다` 통과(빌드 결과) |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-risk`·`autotrade-architecture-test` 변경 없음(diff 파일 목록). 엔진 활성화·PANIC 증권사 단계는 `BROKER_NOT_CONNECTED` 그대로(EngineMain.java:219-220). ArchUnit 시험 빌드에서 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 코드 없음/변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 통과 | 진입점 표시 문구만 바뀜, 새 의존·import 없음. 새 시험 두 건이 첫 엔진을 띄운 채 두 번째 `launch`를 불러 `EXIT_START_FAILED`와 첫 엔진 소켓 유지(`first.server().socket()` 존재, EngineMainTest.java:455, :488)를 확인 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 코드 없음/변경 없음 |
| A10 | 통과 | diff(픽스처 포함)에 키·계좌번호·토큰 없음(`"C:\\x"` 경로 예외, "꾸리기 실패"뿐). Q1 시험이 설정 오류·명령 채널 실패의 ERROR 기록에 원인 예외가 실리지 않음(`getThrown()` null, EngineMainTest.java:468-471)을 고정하고 N1·N2로 실효 확인. 프로그램 오류의 `detail`에 예외 메시지가 실리는 것은 이 PR 전부터(:288-289) |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·GuardedOrderGateway 테스트, 장애 테스트) 변경 없음. 보호 경로의 `EngineMainTest`는 2건 추가만, 삭제·`@Disabled`·약화 없음(diff). 고정 값 변경 없음. 새 시험은 세 가지 기동 실패를 직접 일으키며 위 변이 6개와 개발 쪽 변이 M4·M5·M10·M11을 잡음. 생존 N5·N6·N9는 아래 Q1·Q2(판정 무관 권고) |
| A12 | 통과 | 종료 코드 2/1/1, 기동 실패 시 엔진 안 뜸, 재기동 시 OFF 전제 그대로. runbook이 기대는 낱말 "명령 채널을 열지 못해"·"프로그램 오류"(docs/runbooks/README.md:64)는 첫머리에 남고 Q2 시험이 글자로 고정(EngineMainTest.java:491-495). 바뀐 옛 문구 "설정 파일 문제가 아닙니다"를 읽는 엔진 쪽 스크립트·CI·runbook 없음(`grep -rn` .github, scripts, docs: 남은 곳은 감시 `WatchdogMain`·D-106·collector.md, 감시 문구라 이 PR과 무관) |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-107에 "후속 반영(2026-10-18)" 절(D-107:42-48)과 결정 3번 줄 끝 변경 표시(:17). 판정·종료 코드 무변경이라 새 D 번호 불필요. 다만 3번 본문 따옴표 안은 옛 괄호 문구 그대로라 아래 R1 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 1,566건 실패 0 |
| B6 | 통과 | 새 동작(문구 변경)과 PR #151 QA Q1·Q2·R1에 시험 2건 추가, 삭제·건너뜀 없음. 변이로 실효 확인 |

## 점검표 밖 발견
- **Q1(권고) 로그 줄의 실제 원인이 시험으로 고정되지 않음**: N5·N6(로그 ERROR 줄에서 `": " + detail` 삭제)이 생존. 새 Q1 시험은 `getMessage()`를 `startsWith(headline)`로만 본다(EngineMainTest.java:466-470). runbook은 기동 실패 때 "로그의 ERROR 줄을 본다"(docs/runbooks/README.md:64)고 안내하고 D-107 4번(:18)은 "로그 ERROR 한 줄…실제 원인"을 약속하므로, 원인이 빠져도 시험이 통과하면 콘솔을 못 본 운영자(예약 실행)가 원인을 잃는다 `[추론]`. `getMessage()`가 `headline + ": " + detail`과 같음(또는 원인 문구 포함)을 보는 단언 한 줄을 권고.
- **Q2(권고) 표준 오류 "한 줄"이 고정되지 않음**: N9(예외 `toString()` 둘째 줄 추가) 생존. D-107 4번은 표준 오류 한 줄을 약속한다. `captureErr` 결과의 줄 수 단언을 권고. 프로그램 오류 예외 메시지가 그대로 콘솔에 나가는 경로가 늘어나는 변경을 막는 효과도 있다 `[추론]`.
- **R1(참고) D-107 3번 본문의 따옴표 문구가 옛 문구**: D-107:17은 옛 괄호 문구를 인용한 채 "(2026-10-18 문구를 괄호 없이 바꿈, 아래 후속 반영 R1)"만 붙였다. 문구로 검색하면 옛 문구가 먼저 걸린다. 결정 본문 보존 관례라면 그대로 둬도 되며 판정 무관.
- **R2(참고, main도 같음) 명령 채널을 연 뒤 실패 시 서버를 닫지 않음**: `start`의 catch(EngineMain.java:152-155)는 알림 큐만 닫고 `ControlServer`는 닫지 않는다. 운영에선 `launch`가 곧바로 `System.exit(1)`을 불러 OS가 소켓·잠금을 푼다 `[추론]`. 이 PR이 바꾼 곳이 아니고 지금 그 뒤 단계(`HeartbeatWriter.start`, `offer`)에서 런타임 예외가 날 경로를 찾지 못했다.

### 잘못된 주문·중복 주문·보호 공백 시나리오
1. **첫 엔진이 도는데 두 번째 엔진 기동**: 명령 채널 잠금으로 `IOException` → 종료 코드 1, "명령 채널을 열지 못해" 첫머리, 첫 엔진 소켓 유지(새 시험 두 건이 직접 확인). 첫 엔진 Guard·명령 처리에 영향 없음. 문제 없음.
2. **운영 스크립트가 옛 문구로 실패 종류를 가르는 경우**: 옛 문구 "설정 파일 문제가 아닙니다"를 읽는 엔진 쪽 소비자를 저장소에서 찾지 못함(A12 근거). CI 기동 점검은 종료 코드(0이 아님)만 본다(.github/workflows/ci.yml). 저장소 밖 사용자 스크립트는 확인 못 하나 runbook이 안내하는 낱말은 유지됨. 실전 경로 영향 없음.
3. **기동 실패 원인 예외에 비밀값이 실려 로그로 새는 경우**: 설정 오류·명령 채널 실패는 원인 예외 흐름이 로그에 실리지 않음이 이제 시험으로 고정(N1·N2 잡힘). 프로그램 오류는 이전과 같이 예외 흐름을 남김(D-107 3번 결정). 이 PR로 노출이 늘지 않음.
4. **Q1 시험의 로그 처리기에 다른 스레드 기록이 섞여 흔들리는 경우**: 처리기는 `com.autotrade.engine`에 붙어 자식 로거(`com.autotrade.engine.guard-audit`, `.command-audit`)의 기록도 받는다. 시험 중 명령을 보내지 않고 Guard 상태 변화도 없어 SEVERE가 섞일 경로 없음. 하트비트·알림 큐 로거는 다른 이름(`HeartbeatWriter`, `NotificationQueue` 클래스 이름). 지금은 안정적 `[추론]`.

## 판정
승인

판정 근거: 빌드 통과, 점검표 실패·확인 못 함 없음. 생존 변이 N5·N6·N9는 이 PR이 고치려던 Q1·Q2·R1 밖의 표시 세부라 권고(Q1·Q2)로 둔다. 실전 관련 PR이므로 머지는 사용자가 직접 한다.
