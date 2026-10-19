# QA 검토: 실전 엔진 기동 실패 로그 원인·표준 오류 한 줄 시험(PR #165 QA Q1·Q2·R1)
- 대상: PR #169 / 브랜치 `claude/project-thread-ryxl0y` / 헤드 커밋 7785552 / main 대비 diff 전체(merge-base 7491fd8 = PR #165 머지 커밋, 커밋 4개, 5파일 +89 −3: 시험 `EngineMainTest.java` +12 −1, `D-107-engine-start-failure-kinds.md` +11 −2, `docs/qa/pending.md` +1, 검토 원문 `docs/reviews/pr-169/{architect,risk-ops}.md`). 운영 코드 변경 없음.
- 점검표: A 전체 + B1, B6. `autotrade-app-engine/*`은 보호 경로(scripts/ci/protected-paths.txt:8)라 실전 관련 PR로 본다.
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 7785552): 1~7회째 Maven Central 429(`status code 429`, `:build-logic:compileClasspath` 의존성 받기)로 실패(환경 문제, 코드 무관). 20초 간격 재시도 루프 하나, 8회째 **BUILD SUCCESSFUL in 3m 2s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,566건, 실패 0, 오류 0, 건너뜀 0**. `EngineMainTest` 22, `RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10 통과.
  - GitHub CI: 헤드 7785552에는 워크플로 실행이 없다(PR이 main과 충돌 상태라 `pull_request` CI가 돌지 않음, 아래 R1). 직전 커밋 ac6506e CI(run 980) success, 첫 커밋 ba2897e CI(run 976) success. ac6506e→7785552 차이는 `docs/qa/pending.md` 1줄뿐(`git diff --stat`).
  - 변이 시험(별도 worktree, 운영 `EngineMain.java`를 한 곳씩 바꾸고 `--offline :autotrade-app-engine:test --tests '*EngineMainTest' --rerun-tasks`, 매번 원복, 마지막 `git status` 깨끗, worktree 삭제). PR #165 QA N1~N9와 개발 쪽 N5·N6·N6c·N9·N9b와 겹치지 않게 새로 8개. 6개 잡힘, 2개 생존:

| 변이 | 내용 | 결과 |
|---|---|---|
| P1 | 설정 폴더 거부 원인에서 `+ e.getMessage()` 삭제(EngineMain.java:120) | 잡힘(Q1 시험 `isEqualTo`) |
| P2 | 명령 채널 실패의 표준 오류에서만 원인(detail) 비움 | 잡힘(`진입점은_명령_채널을_열지_못하면_예전_문구로_종료_코드_1로_끝낸다`) |
| P3 | 표준 오류 `println`→`print`(줄 끝 없음) | **생존** |
| P4 | 설정·채널 실패 로그 줄 원인을 `e.toString()`으로 | 잡힘(Q1 시험) |
| P5 | 프로그램 오류의 표준 오류에서 원인 비움 | 잡힘(3건, Q2 시험 포함) |
| P6 | 명령 채널 실패는 로그 ERROR를 남기지 않음(설정 오류만 남김) | 잡힘(Q1 시험 `hasSize(3)`) |
| P7 | 프로그램 오류 로그 줄에 줄바꿈 + `e.toString()` 덧붙임 | 잡힘(Q1 시험 `isEqualTo`) |
| P8 | 표준 오류 원인 뒤에 ` [예외 전체 클래스 이름]` 덧붙임(한 줄 유지) | **생존** |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(`git diff --name-only 7491fd8 HEAD`에 `src/main` 0건). 기동 실패 시 `launch`(EngineMain.java:100-110)는 `exit` 뒤 엔진을 돌려주지 않고, Guard는 `startGuard`에서 모든 계좌 OFF로만 생긴다(:191-201). `RealTradingGuardTest` 31건, `EngineMainTest#진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다` 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-risk`·`autotrade-architecture-test` 변경 없음. `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13·`GuardedOrderGatewayTest` 8 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 프로세스 분리 코드 변경 없음. 두 시험이 첫 엔진을 띄운 채 두 번째 `launch`를 불러 종료 코드 1과 첫 엔진 소켓 유지(`first.server().socket()` 존재)를 확인하고, 이제 로그 원인에 `ControlServer.java:100`의 잠금 문구("다른 실전 엔진이 이미 실행 중입니다")가 실리는지도 본다(EngineMainTest.java:479-480) |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄을 `appkey|secret|token|password|계좌번호`로 찾은 결과는 검토 원문의 A10·B8 설명 문장뿐(값 아님). 새 픽스처는 `"C:\\x"` 경로 예외와 "꾸리기 실패". 설정 오류·채널 실패의 원인 예외 흐름 미기록 단언(`getThrown()` null, :470, :472)은 그대로 남았고, 새 `isEqualTo`는 로그 줄에 다른 값이 덧붙는 변경도 잡는다(P4·P7) |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·약화 없음(`-@Test` 0건, 기존 단언 전부 유지, `InvalidPathException`을 변수 `badPath`로 뺀 것은 같은 값). 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 단언 두 묶음(:473-480, :506-507)이 세 가지 기동 실패를 직접 일으켜 확인하고, 내 변이 8개 중 6개를 잡는다. 생존 2개는 아래 Q1(판정 무관) |
| A12 | 통과 | 종료 코드 2/1/1(`StartFailure.of`, :281-290), 기동 실패 시 엔진 안 뜸, 재기동 시 OFF 전제 무변경. runbook(docs/runbooks/README.md:64)이 안내하는 "로그의 ERROR 줄"에 원인이 실린다는 점을 이제 시험이 글자로 고정한다. 장애 대응표 17종·O1~O8·장애 테스트 8종 경로 변경 없음 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-107 3번 본문 인용(D-107:17)이 코드 상수 `PROGRAM_ERROR_HEADLINE`(EngineMain.java:278-279)과 글자까지 같고, 옛 괄호 문구는 앞 후속 반영 R1(D-107:46)에 남았다(PR #165 QA R1 해소). 새 후속 반영 절(D-107:50-57)의 "판정·종료 코드·Guard·운영 코드 무변경"은 diff와 맞다. 판정 무변경이라 새 D 번호 불필요. "남은 한계"(원인 메시지에 줄바꿈이 있으면 표준 오류가 여러 줄)는 사실이며(`report`가 `detail`을 그대로 출력, :299) 4번 "한 줄" 약속의 예외로 문서에 적혀 있다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,566건 실패 0(429 7회 뒤) |
| B6 | 통과 | PR #165 QA Q1·Q2의 생존 변이 N5·N6·N9를 잡는 단언 추가, 삭제·건너뜀 없음(A11). 변이 P1·P2·P4~P7로 실효 확인 |

## 점검표 밖 발견
- **R1 (머지 전 처리 필요, 판정 무관) main과 충돌·헤드 CI 미실행**: GitHub `mergeable_state: dirty`. main이 PR #166 줄을 `docs/qa/pending.md` 같은 자리(:57)에 넣어 `git merge-tree`에서 이 PR의 #169 줄과 충돌한다. architect·risk-ops 원문의 "충돌 없음"은 main 009b420 이전 기준이라 지금은 맞지 않다. 충돌 때문에 헤드 7785552에 CI가 돌지 않았다(코드 쪽 마지막 CI는 ac6506e success, 그 뒤 차이는 `pending.md` 1줄). 충돌을 풀 때 #166 줄과 #169 줄을 **둘 다** 남기고, 풀고 난 헤드의 CI 통과를 확인한 뒤 머지할 것.
- **Q1 (권고) 표준 오류 줄 끝·원인 뒤 덧붙임은 고정되지 않음**: P3(`print`로 줄 끝 없음)·P8(원인 뒤에 예외 클래스 이름 덧붙임) 생존. Q2 시험은 `lines()` 개수와 첫머리만 보고, 채널·설정 오류의 표준 오류 원인 끝은 보지 않는다. 지금 코드는 맞고 영향은 콘솔 표시뿐이라 판정에 넣지 않는다. 고정하려면 표준 오류를 `headline + "(실전 엔진이 뜨지 않았습니다): " + detail + System.lineSeparator()`와 `isEqualTo`로 비교하면 된다.
- **R2 (참고, 문서에 적힌 한계 확인)**: 기동 단계 예외 메시지에 줄바꿈이 있으면 로그 ERROR 줄과 표준 오류가 여러 줄이 된다(`report`, EngineMain.java:295-299). 예약 실행에서 로그를 줄 단위로 걸러 보는 운영자는 원인 뒷부분을 놓칠 수 있다 `[추론]`. 지금 기동 단계에서 던지는 예외(경로 형식 오류, `ControlServer` 잠금, 알림 채널 이름 검사)의 메시지는 코드상 한 줄이다. D-107이 다음 실전 엔진 변경으로 미뤘다.
- 지침 8장 과금: 유료 호출·외부 서비스 추가 없음(시험과 문서만 변경). 해당 없음.

### 잘못된 주문·중복 주문·보호 공백 시나리오
1. **첫 엔진이 도는 중 두 번째 엔진 기동**: 잠금 `IOException` → 종료 코드 1, 첫머리 "명령 채널을 열지 못해", 로그 원인 "다른 실전 엔진이 이미 실행 중입니다", 첫 엔진 소켓 유지(두 시험이 직접 확인). 두 엔진이 같은 계좌로 주문하는 경로 없음. 문제 없음.
2. **로그 ERROR 줄에서 원인이 빠지는 회귀(예약 실행 운영자가 원인을 잃음)**: 이제 세 경우 모두 시험이 잡는다(개발 쪽 N5·N6, 내 P1·P4·P6·P7). 원인을 못 보고 같은 기동을 반복해도 Guard는 매번 OFF로 시작하므로 주문 영향은 없다.
3. **설정 오류·채널 실패 원인 예외에 비밀값이 실려 로그 스택으로 새는 회귀**: `getThrown()` null 단언 유지, `isEqualTo`가 줄 전체를 고정해 `e.toString()` 등을 덧붙이는 변경도 잡힘(P4). 노출 늘지 않음.
4. **충돌 해소 중 `pending.md` 줄 유실**: R1. 줄이 빠지면 QA "대기 목록 처리" 때 해당 요청이 누락될 수 있다. 주문 경로와 무관.

## 판정
승인

판정 근거: 빌드 직접 통과(1,566건), 점검표 실패·확인 못 함 없음, 운영 코드 무변경, 새 단언이 PR #165 QA Q1·Q2 생존 변이와 내 변이 6개를 잡음. R1(main 충돌·헤드 CI 미실행)은 머지 전에 풀고 CI를 확인해야 하지만 검토 대상 코드의 결함은 아니다. 실전 관련 PR이므로 머지는 사용자가 직접 한다.
