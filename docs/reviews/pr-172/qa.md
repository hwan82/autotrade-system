# QA 검토: 실전 엔진 기동 실패 원인 한 줄로 접기·표준 오류 전체 고정 시험(D-112, PR #169 QA Q1·R2)
- 대상: PR #172 / 브랜치 `claude/project-thread-blfvr9` / 헤드 커밋 2f2e42f / main(dc41d07) 대비 diff 전체(커밋 10개, 8파일 +397 −4: 운영 `EngineMain.java` +61 −4, 시험 `EngineMainTest.java` +112, `D-112-engine-start-failure-one-line-detail.md` 신규, `D-107-engine-start-failure-kinds.md` +4, `docs/decisions/README.md` +1, `docs/qa/pending.md` +1, 검토 원문 `docs/reviews/pr-172/{architect,risk-ops}.md`). GitHub `mergeable_state: clean`.
- 점검표: A 전체 + B1, B6. `autotrade-app-engine/*`은 보호 경로(scripts/ci/protected-paths.txt:8)라 실전 관련 PR로 본다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 테스트)는 diff에 없다.
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 2f2e42f): 1~3회째 Maven Central 429(`:autotrade-config:compileClasspath` jackson-annotations 받기, `Received status code 429`)로 실패(환경 문제, 코드 무관). 20초 간격 재시도 루프 하나, 4회째 **BUILD SUCCESSFUL in 4m 24s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,589건, 실패 0, 오류 0, 건너뜀 0**. `EngineMainTest` 26, `RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10 통과.
  - GitHub CI(헤드 2f2e42f, run 39249754270): Gradle build, Lab screens (Playwright), DB schema (PostgreSQL 16), Secret scan and collector boundary 4종 success.
  - 무작위 입력 탐침(저장소 밖 단독 프로그램, 빌드된 `EngineMain$StartFailure.oneLine`을 리플렉션으로 호출): 글자 묶음 19종(`a`, 스페이스, 탭, `\n`, `\r`, `\r\n`, VT, FF, U+0085, U+2028, U+2029, U+3000, U+001C, `/`, `" / "`, 서로게이트 쌍, NBSP, ESC 시퀀스)으로 0~29개를 이어 붙인 입력 **20만 개(시드 0~199,999)**. 결정 1번을 따로 짠 기준 구현(소유 한정자 정규식, 재귀 없음)과 **불일치 0**, 결과에 줄바꿈 글자가 남거나 `lines()`가 2줄 이상인 경우 **0**, 줄바꿈이 든 입력에서 공백 아닌 글자 순서 유실 0. 큰 입력: 2,000,001자 2ms, 20,000,001자 21ms에 `"x"`(던지지 않음, `-Xss512k`). 줄바꿈 없는 500만 자는 같은 객체를 그대로 돌려줌.
  - 경계 값 직접 확인: `"\n"`·`" \n "`·`"\r\n"`·`"\n\n\n"` → `""`, `"a\n"` → `"a"`, `""` → `""`, `" "` → `" "`, `"a \t\n\t b"` → `"a / b"`, `"a  b\nc"` → `"a  b / c"`, `"x\u2028"` → `"x"`.
  - 변이 시험(별도 worktree, 운영 `EngineMain.java`를 한 곳씩 바꾸고 `--offline :autotrade-app-engine:test --tests '*EngineMainTest' --rerun-tasks`, 실패 시험 이름은 결과 XML로 확인, 매번 원복, 원본 기준 26건 통과 확인, 마지막 `git status` 깨끗). architect X1~X6과 겹치지 않게 새로 12개. **12개 모두 잡힘**:

| 변이 | 내용 | 결과(실패 시험 수) |
|---|---|---|
| M1 | 명령 채널(`IOException`) 원인에서 `oneLine` 빼기 | 잡힘(2: 긴 공백 시험, 세 종류 시험) |
| M2 | 설정 오류(`EngineRefusal`) 원인에서 `oneLine` 빼기 | 잡힘(2) |
| M3 | 프로그램 오류 원인에서 `oneLine` 빼기 | 잡힘(3, 로그 ERROR 시험 포함) |
| M4 | `isLineBreak`에서 U+2028 빼기 | 잡힘(2) |
| M5 | `isLineBreak`에서 VT 빼기(`\n`·`\f`·`\r`만) | 잡힘(1) |
| M6 | 앞 공백·줄바꿈 걷기 반복문 삭제 | 잡힘(2) |
| M7 | 구분자 `" / "`→`" \| "` | 잡힘(3) |
| M8 | 줄바꿈 유무 검사 없앰(늘 앞뒤 걷기) | 잡힘(1, `" a  b "` 원문 유지 단언) |
| M9 | 묶음의 줄바꿈 여부를 마지막 글자로만 판단(`\|=`→`=`) | 잡힘(1, `"\r\n  셋째 줄"`) |
| M10 | 프로그램 오류 로그 ERROR 줄에 접지 않은 원문 메시지 | 잡힘(1) |
| M11 | `isLineBreak`에서 U+2029 빼기 | 잡힘(1) |
| M12 | `null` 메시지를 `""`로 | 잡힘(1) |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성 경로 무변경: `startGuard`(EngineMain.java:191)는 diff에 없고 모든 계좌 OFF로만 만든다. 기동 실패 시 `launch`(:100-110)는 `exit` 뒤 null을 돌려 엔진이 뜨지 않는다. 바뀐 곳은 `StartFailure.of`의 `detail` 만들기(:281-290)와 `oneLine`·`isLineBreak`·`isBlank`(:298-347)뿐. `RealTradingGuardTest` 31건, `EngineMainTest#진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다` 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-risk`·`autotrade-architecture-test` 변경 없음(`git diff --name-only`에 운영 파일은 `EngineMain.java` 하나). `java.util.regex` 의존이 빠지고 새 의존 없음. `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13·`GuardedOrderGatewayTest` 8 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 기동 순서·명령 채널·잠금·하트비트·알림 큐 무변경. 새 코드는 기동 실패 때 한 번 도는 선형 반복문(2천만 자 21ms, 재귀 없음). `#기동_실패_표준_오류는_첫머리부터_줄_끝까지_정해진_한_줄이다`(EngineMainTest.java:511)가 첫 엔진을 띄운 채 두 번째 `launch`로 종료 코드 1과 첫 엔진 소켓 유지를 다시 확인 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄을 `appkey\|secret\|token\|password\|계좌번호`로 찾은 결과는 검토 원문의 A10·B8 설명 문장뿐(값 아님). 새 픽스처는 `"첫 줄\n둘째 줄..."`, `"C:\\x"`, `"앞/뒤"`, `"꾸리기/실패"`, 공백·줄바꿈 반복. `oneLine`은 글자를 더하지 않고(탐침에서 공백 아닌 글자 순서 유실 0) 설정 오류·명령 채널 실패는 여전히 예외 흐름을 남기지 않는다(`report`, :350-357, `programError=false`) |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·약화 없음(시험 diff는 추가뿐, `-` 줄 0). 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 시험 4건(:511, :540, :581, :607)이 실패 경우(긴 공백·줄바꿈 원인에서의 종료 코드 2·1, 유니코드 줄바꿈 연속·끝, 표준 오류 줄 끝)를 포함하고 내 변이 12개를 모두 잡는다. PR #169 QA Q1의 생존 변이 P3·P8은 `isEqualTo` 전체 비교(:530-536)로 잡히는 꼴이다 |
| A12 | 통과 | 판정(`StartFailure.of` 분기)·종료 코드 2/1/1·첫머리 문구·예외 흐름 기록 범위 무변경. 원인 길이와 상관없이 `exit`가 불린다(:581 시험, 탐침 2천만 자). 장애 대응표 17종·O1~O8·장애 테스트 8종 중 기동 실패 출력 글자에 기대는 행은 없고(엔진 정지 감지는 하트비트), runbook(docs/runbooks/README.md)의 "로그의 ERROR 줄을 본다" 안내는 이제 원인에 줄바꿈이 있어도 맞다 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-112 결정 1번(줄바꿈 글자 목록 `\R` 집합, 앞뒤 걷기, 묶음 하나로 `" / "`, 줄바꿈 없으면 원문, `null`→"null")이 코드(:298-347)와 일치함을 기준 구현 20만 개 비교로 확인. 결정 2·3번(세 종류 같은 규칙, 프로그램 오류 예외 흐름 원문)은 :281-290, :350-357과 시험 :540(`getThrown()` 같은 객체·원래 메시지)으로 확인. D-107 후속 반영 절과 README D-112 줄이 내용과 맞다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,589건 실패 0(429 3회 뒤). CI 4종 success |
| B6 | 통과 | 새 동작(접기)에 시험 4건, 기존 단언 삭제·건너뜀 없음(A11). 변이 12개 모두 잡혀 실효 확인 |

## 점검표 밖 발견
- **R1 (참고, 판정 무관) 공백·줄바꿈만으로 된 원인은 빈 원인으로 표시**: 원인 메시지가 `"\n"`, `" \r\n "`처럼 공백·줄바꿈뿐이면 `detail`이 `""`가 되어 표준 오류가 `...(실전 엔진이 뜨지 않았습니다): `로 끝난다(프로그램 오류는 `IllegalStateException: `). 원래도 읽을 글자가 없던 경우라 잃는 정보는 없고 프로그램 오류는 로그 예외 흐름에 원문이 있다. 이 경우를 고정하는 단언은 없다.
- **R2 (참고, 판정 무관, main부터) 줄바꿈 아닌 제어 문자는 그대로**: ESC(`\u001B[1A` 같은 커서 이동)나 U+001C~U+001F는 접지 않아 `String.lines()` 기준 한 줄이지만, 터미널에서는 커서를 움직여 앞 줄을 덮어 보이게 할 수 있다 `[추론]`. D-112 "위험과 한계"에 "탭 등 다른 제어 문자는 바꾸지 않는다"로 적혀 있고, 지금 기동 단계 원인 메시지(경로 형식 오류, 잠금, 알림 채널 이름 검사)에는 이런 글자가 들어올 길이 없다. 로그 파일·`grep` 기준 한 줄 약속은 지켜진다.
- **Q1 (권고, 판정 무관) 명령 채널·설정 오류의 접기는 `detail()` 단위로만 끝까지 본다**: M1·M2는 `StartFailure.of(...).detail()` 시험과 긴 공백 시험(설정 오류는 `launch`까지)이 잡지만, 명령 채널 실패를 `launch`로 일으키는 시험은 한 줄 잠금 메시지만 쓴다. 지금은 세 경로가 같은 `oneLine`을 부르므로 실효 차이는 없다.
- architect N5(탭·줄바꿈 없는 공백 묶음 고정 단언 없음, X2·X5 생존)는 그대로다. 내 탐침에서 두 동작 모두 결정 1번대로임을 확인했다(`"a \t\n\t b"`→`"a / b"`, `"a  b\nc"`→`"a  b / c"`). 판정 무관.
- 지침 8장 과금: 유료 호출·외부 서비스·새 라이브러리 추가 없음(출력 문자열 처리와 시험·문서만). 해당 없음.

### 잘못된 주문·중복 주문·보호 공백 시나리오
1. **긴 공백·줄바꿈이 든 원인으로 기동 실패(K4 회귀)**: 정규식 판에서는 `StackOverflowError`로 `exit`가 불리지 않아 예약 실행이 종료 코드 없이 끝났다. 지금 구현은 2천만 자에도 21ms에 끝나고 던지지 않으며, 시험(:581)이 설정 오류 2·프로그램 오류 1을 본다. 어느 경우든 엔진은 뜨지 않고 Guard는 만들어지지 않으므로 주문 영향은 없다.
2. **첫 엔진이 도는 중 두 번째 엔진 기동**: 잠금 `IOException` → 종료 코드 1, 표준 오류 전체가 `"...다른 실전 엔진이 이미 실행 중입니다(<LOCK_FILE> 잠금)"` 한 줄, 첫 엔진 소켓 유지(:511 시험). 두 엔진이 같은 계좌로 주문하는 경로 없음. 문제 없음.
3. **접기가 원인 글자를 빠뜨려 운영자가 다른 문제로 오인**: 20만 개 무작위 입력에서 공백 아닌 글자 순서 유실 0, 기준 구현과 불일치 0. 원래 `" / "`가 든 메시지(알림 채널 이름 검사 `"kakao / kakao-x"`)와 접은 줄바꿈은 글자로 가를 수 없으나(D-112 위험 1번) 표시용이고, 프로그램 오류는 예외 흐름에 원문이 있다.
4. **설정 오류·채널 실패 원인의 비밀값이 예외 흐름으로 새는 회귀**: `report`의 `programError` 분기 무변경, 기존 `getThrown()` null 단언 유지. `oneLine`은 글자를 더하지 않으므로 노출이 늘지 않는다.

## 판정
승인

판정 근거: 빌드 직접 통과(1,589건 실패 0), CI 4종 success, 점검표 실패·확인 못 함 없음. 운영 변경은 기동 실패 표시 문자열 접기 하나이고 판정·종료 코드·Guard OFF 시작·명령 채널·주문 경로는 그대로다. 무작위 입력 20만 개가 결정 1번 기준 구현과 모두 일치하고 2천만 자 입력에서도 던지지 않으며, 새 시험이 내 변이 12개를 모두 잡는다. 실전 관련 PR이므로 머지는 사용자가 직접 한다.
