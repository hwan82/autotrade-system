# risk-ops 검토: 실전 엔진 기동 실패 원인 한 줄로 접기·표준 오류 전체 고정 시험(D-112, PR #169 QA Q1·R2)
- 대상: PR #172 / 브랜치 `claude/project-thread-blfvr9` / 커밋 `15569524b25b542958f0c773f8d1c553077e12ab` / main 대비 diff(`git diff origin/main...HEAD`, 5파일 +145 −4)
- 점검표: A
- 직접 실행한 것(별도 clone `/home/claude/review-pr172-riskops`):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(4m 7s, 종료 0). 시험 결과 XML 집계: `:autotrade-app-engine:test` 67건 실패 0·오류 0·건너뜀 0, `:autotrade-risk:test` 55건 0/0/0, `:autotrade-architecture-test:test` 46건 0/0/0. 새 시험 3건(`EngineMainTest#기동_실패_표준_오류는_첫머리부터_줄_끝까지_정해진_한_줄이다`, `#기동_실패_원인에_줄바꿈이_있어도_로그_ERROR와_표준_오류는_한_줄이고_예외_흐름은_원문을_남긴다`, `#기동_실패_원인의_줄바꿈은_세_종류_모두_한_줄로_접는다`)이 실제로 돌고 통과함을 XML에서 확인.
  - 변이 시험(확인 뒤 원복, 작업 트리 깨끗함 확인): (M1) `oneLine`이 줄바꿈을 접지 않고 원문을 돌려주게 바꿈 → `EngineMainTest` 25건 중 2건 실패. (M2) `report`의 `System.err.println`을 `print`로 바꿈(QA 변이 P3) → 2건 실패. 두 변이 모두 새 시험이 잡는다.
  - `oneLine` 경계 입력 직접 실행(JDK 21 단일 파일 실행, 같은 정규식): `a\u2028\u2028b`→`a /  / b`, `a\u0085`→`a / `, `\n`→빈 문자열, `a\u000Bb`·`a\fb`→`a / b`, `a\r\n\tb`→`a / b`, `x\u2029`→`x`. 모든 결과가 한 줄이고 `\R`이 남지 않음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff는 `EngineMain.StartFailure`의 `detail` 문자열 만들기(`EngineMain.java:282-305`)만 바꾼다. Guard 생성(`EngineMain.java:134`, `startGuard` 192-202, 모든 계좌 OFF)·`RealTradingGuard` 코드·설정 로딩 무변경. 실패 시 `launch`는 엔진을 돌려주지 않고 null(`EngineMain.java:105-110`)이라 Guard가 메모리와 함께 사라진다(D-107 5번). 기존 `EngineMainTest#진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다`(고친 뒤 다시 뜨면 모든 계좌 OFF) 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 변경 없음(diff 5파일 중 운영 코드는 `EngineMain.java` 1개, 출력 문자열만). `ArchitectureTest`·`RealPathReflectionTest`·`ModuleOwnershipTest` 등 `:autotrade-architecture-test:test` 46건 통과, 이 모듈 diff 없음 |
| A3 | 해당 없음 | 증권사 주문 연동 코드 변경 없음(엔진은 아직 `ActivationStepRunner.BROKER_NOT_CONNECTED`·`PanicLiquidator.BROKER_NOT_CONNECTED`, `EngineMain.java:220-221`, 무변경) |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 기동 순서(`EngineMain.java:130-157`)·명령 채널 잠금(`ControlServer`)·하트비트·알림 큐 무변경. 새 코드는 기동 실패 시 한 번 도는 정규식 치환뿐이라 실행 중 엔진 부하·타 프로세스 의존이 없다. `Pattern`은 클래스 상수로 한 번 컴파일(`EngineMain.java:282-283`). 기존 `#소켓_파일을_지워도_두_번째_엔진은_잠금_때문에_뜨지_않는다` 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 시험 픽스처는 `"첫 줄\n둘째 줄..."`, `"a\nb"`, `"\"C:\\x\""` 같은 자리값뿐, 키·계좌번호·토큰 없음(`EngineMainTest.java` 추가분 510-589). `oneLine`은 글자를 더하지 않고 줄바꿈·둘레 공백만 바꾸므로 원인 메시지에 새 값이 실리지 않는다. 설정 오류·명령 채널 실패는 예전처럼 예외 흐름을 로그에 남기지 않는다(`EngineMain.java:308-313`, `programError=false`, D-107) |
| A11 | 통과 | 보호 경로(`autotrade-app-engine/*`) 변경이지만 기존 시험 삭제·`@Disabled`·완화 없음(diff의 테스트 변경은 추가 81줄뿐). 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작에 실패를 잡는 시험이 있음: 변이 M1·M2가 각각 2건씩 실패로 잡힘(위 직접 실행) |
| A12 | 통과 | 장애 대응표 6장 #1~#17, 해외 O1~O8, 장애 테스트 8종(`broker-and-fault-design.md:160-195`) 중 기동 실패 출력 형식에 기대는 행 없음. #8(엔진 크래시) 감지는 하트비트 파일(D-092)이라 출력 문구와 무관. 종료 코드(2/1/1)와 판정 무변경(`EngineMain.java:285-294`), 새 시험 `#기동_실패_표준_오류는_첫머리부터_줄_끝까지_정해진_한_줄이다`가 종료 코드 순서도 확인 |
| A13 | 해당 없음 | 시장별 경로·시간대·금액 계산 변경 없음 |
| A14 | 통과 | 새 결정 D-112 기록(`docs/decisions/D-112-engine-start-failure-one-line-detail.md`), 결정 표 추가(`docs/decisions/README.md:118`), D-107 "남은 한계"에 후속 반영 링크(`D-107-engine-start-failure-kinds.md:59-61`). D-112 1~4번의 동작(줄바꿈 `\R` 접기, 줄바꿈 없으면 원문, `null`→"null", 세 종류 공통, 프로그램 오류 예외 흐름은 원래 예외 객체)이 코드(`EngineMain.java:285-315`)와 시험(`#..._예외_흐름은_원문을_남긴다`: `getThrown()` 같은 객체·원래 메시지)과 일치. runbook(`docs/runbooks/README.md:64` "로그의 ERROR 줄을 본다")과 충돌 없음. 기획서는 출력 형식을 다루지 않아 변경 불필요 |

## 점검표 밖 발견
- K1(권고, 막지 않음): 연속된 유니코드 줄 구분자(U+2028·U+2029·U+0085)는 `\s`에 들지 않아 `a /  / b`처럼 구분자가 두 번 나오고, 끝의 U+0085는 `strip()`이 걷지 않아 `a / `로 끝난다(직접 실행). 한 줄 약속은 지켜지고 표시용이라 실전 영향 없음. 고친다면 `Pattern.UNICODE_CHARACTER_CLASS` 또는 `[\s\R]*` 묶음을 쓰는 정도다.
- K2(후속, 막지 않음): 감시 `WatchdogMain`의 같은 출력(`WatchdogMain.java:199-211`)은 원인을 접지 않는다. D-112 "위험과 한계"가 범위 밖으로 적어 두었으므로 다음 감시 PR에서 같은 규칙을 맞출 것.
- K3(관찰, 이 PR 이전부터): `[추론]` JUL 기본 설정(`SimpleFormatter`, 루트 `ConsoleHandler`는 표준 오류로 냄)이면 운영 프로세스의 표준 오류에는 `println` 한 줄 외에 로그 기록(기록당 머리 줄 + `SEVERE:` 줄)과 프로그램 오류의 스택도 함께 나온다. D-107·D-112의 "표준 오류 한 줄"은 `report`의 `println` 줄을 뜻하고, 시험의 `captureErr`가 전체를 `isEqualTo`로 비교해도 통과하는 것은 로그 처리기가 시험 전에 원래 `System.err`에 묶였기 때문으로 보인다. 엔진 프로세스를 실제로 띄워 표준 오류 전체를 보지는 않았다(검증 못 함). 판정에는 영향 없으나, runbook·D-107 문구를 "표준 오류의 '(실전 엔진이 뜨지 않았습니다)' 줄"처럼 좁히거나 엔진 로그 설정을 정할 때 함께 본다.

## 판정
승인

## 재검토 2026-10-19
- 대상: PR #172 / 커밋 `c201bc70c6cfeba105da3514758edd744da75285`(검토 반영 커밋 `c201bc7`, 그 사이 `ca9d379`는 architect 원문만) / main 대비 diff 전체(7파일 +223 −4)
- 점검표: A
- 직접 실행한 것(같은 clone `/home/claude/review-pr172-riskops`):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(종료 0). `:autotrade-app-engine:test` 67건(새로 실행), `:autotrade-architecture-test:test` 46건(새로 실행) 실패·오류·건너뜀 0. `:autotrade-risk:test`는 이 빌드에서 UP-TO-DATE라 `--rerun`으로 다시 돌려 55건 0/0.
  - 새 정규식 경계 실행(JDK 21, 기본 스레드 스택, 같은 패턴 문자열): `c201bc7`의 `EDGE_BREAKS`(`^(?:\s|\R)+|(?:\s|\R)+$`)는 공백·줄바꿈 연속이 **약 2000자**를 넘으면 `StackOverflowError`(500·1000자는 정상, 2000·3000·5000·10000·100000자는 모두 SOE). 문자열 가운데 공백 묶음(`"a\nb" + " "×2000 + "c"`)과 줄바꿈 묶음(`"a" + "\n"×2000 + "b"`) 둘 다 해당. 같은 입력에서 `LINE_BREAK_RUN`(`(?:\s*\R)+\s*`)과 첫 구현(`\s*\R\s*`, 첫 검토 커밋 `15569524`)은 100000자까지 정상. 원인은 `(?:\s|\R)+`가 대체(`|`) 묶음 반복이라 글자마다 재귀하는 것이다.
  - 실제 코드로 확인(임시 시험 `ZzProbeTest`를 만들어 돌린 뒤 지움, 작업 트리 깨끗함 확인): `StartFailure.of(new IllegalStateException("첫 줄\n" + " "×5000 + "끝"))` → `StackOverflowError`. `EngineMain.launch(..., setup이 위 예외를 던짐, exits::add)` → `launch`가 `StackOverflowError`를 던지고 `exit`는 불리지 않음(exits=[]). 설정 폴더 경로 거부(`ConfigPaths` 공급자가 `"bad\n" + "\t"×5000 + "x"` 메시지의 `IllegalArgumentException`을 던짐) → 마찬가지로 `StackOverflowError`, exits=[].
  - 첫 검토의 변이 M1·M2 결론은 시험 코드가 그대로라 유지된다(이번에 다시 돌리지 않음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·설정 로딩 무변경(`EngineMain.java:134`, `startGuard` 195-205 근처, diff 밖). 실패 경로에서 `StackOverflowError`가 나도 `start`가 엔진을 돌려주지 않으므로 엔진은 뜨지 않고 Guard는 모든 계좌 OFF 메모리 상태로 사라진다. `EngineMainTest#진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다` 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 변경 없음. `:autotrade-architecture-test:test` 46건 통과, 이 모듈 diff 없음 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음(`EngineMain.java` diff는 `StartFailure` 정규식과 `oneLine` 본문뿐) |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 기동 순서·명령 채널·하트비트·알림 큐 무변경. 새 정규식은 기동 실패 때만 돌고 실행 중 엔진 경로에 없다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 시험 값은 `"a  b"` 같은 자리값뿐(`EngineMainTest.java` 추가 4줄). `oneLine`은 글자를 더하지 않는다 |
| A11 | **실패** | 보호 시험 삭제·약화는 없고 새 경계 시험 4줄이 추가됐다. 그러나 `c201bc7`의 `EDGE_BREAKS`(`EngineMain.java:286`)가 새 실패 경우(공백·줄바꿈 약 2000자 이상 → `StackOverflowError`)를 만들었고, 이를 잡는 시험이 없다. 시험 입력은 모두 10자 이하다. 아래 K4 |
| A12 | **실패** | 대응표 #1~#17·O1~O8·장애 테스트 8종의 전제는 깨지 않는다(기동 실패 출력에 기대는 행 없음, #8 감지는 하트비트). 그러나 이 PR이 고치려는 D-107 기동 실패 처리 자체가 위 입력에서 깨진다. `launch`의 `catch (IOException | RuntimeException e)`(`EngineMain.java:105`) 안에서 `StartFailure.of`가 `Error`를 던져 (1) 표준 오류 안내 줄과 로그 ERROR 줄이 나가지 않고, (2) `exit`가 불리지 않아, 설정 폴더 경로 거부도 D-107 1번의 종료 코드 2가 아니라 JVM의 처리되지 않은 예외(1)와 스택 출력으로 끝난다. 실패 알림 경로가 원인 메시지 때문에 실패해서는 안 된다. 아래 K4 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 통과 | D-112 결정 1번에 VT·FF 추가, "검토 반영" 절 추가(`D-112-engine-start-failure-one-line-detail.md:15`, 42-44). 기록된 의도는 코드와 일치한다. 다만 K4를 고치면 D-112의 위험과 한계에 입력 길이 처리를 함께 적을 것 |

## 점검표 밖 발견(재검토)
- K1(첫 검토): 해소. `a  b`→`a / b`, ` a\nb\u0085`→`a / b`를 직접 실행과 시험(`EngineMainTest#기동_실패_원인의_줄바꿈은_세_종류_모두_한_줄로_접는다` 추가 줄)으로 확인.
- K2·K3(첫 검토): 그대로. 막지 않음.
- **K4(수정 필요)**: `EDGE_BREAKS = Pattern.compile("^(?:\\s|\\R)+|(?:\\s|\\R)+$")`(`EngineMain.java:286`)는 공백·줄바꿈이 약 2000자 이어진 원인 메시지에서 `StackOverflowError`를 던진다(직접 실행 결과는 위). 문자열 가운데의 공백 묶음도 `$` 쪽 대체 분기가 시작 위치마다 끝까지 재귀해서 걸린다. 지금 기동 단계 예외의 메시지는 짧지만, D-112가 이 변경의 근거로 든 "앞으로 기동 단계에 들어올 예외(JSON 파서, 운영체제 오류 등)"가 바로 원문 덩어리·들여쓰기를 메시지에 실을 수 있는 경우다 `[추론]`. 고칠 방향(택1, 구현은 작성자 판단):
  1. 재귀하지 않는 글자 묶음으로 바꾼다. `\R`의 글자는 `\r\n` 말고는 모두 한 글자이고 `\r`·`\n`은 `\s`에 들므로, 앞뒤 걷기는 `^[\s\u0085  ]+|[\s\u0085  ]+$`로 같은 뜻이다. 묶음 접기도 같은 글자 묶음을 찾아 그 안에 줄바꿈이 있을 때만 " / "로 바꾸면 된다(`Matcher.replaceAll(Function)`). 같은 패턴을 쓰는 `LINE_BREAK_RUN`도 이 방식으로 맞추면 좋다(지금은 10만 자까지 정상).
  2. 정규식 없이 한 번 훑는 반복문으로 접는다.
  - 어느 쪽이든 `oneLine`은 어떤 입력에서도 던지지 않아야 한다. `StartFailure.of`가 던져 진입점의 판정·종료 코드가 사라지지 않게, 시험에 수천~수만 자 공백·줄바꿈 원인을 넣어(가운데 묶음, 앞뒤 묶음, 줄바꿈만 반복) `launch`가 정해진 종료 코드를 부르고 표준 오류가 한 줄인지 확인할 것. 설정 오류 경로(종료 코드 2)도 하나 넣을 것.

## 판정(재검토)
수정 필요(A11·A12: K4 `EDGE_BREAKS` 정규식이 긴 공백·줄바꿈 원인에서 `StackOverflowError`를 던져 기동 실패 판정·종료 코드·안내 줄이 사라짐. 재귀 없는 처리로 바꾸고 긴 입력 시험 추가)
