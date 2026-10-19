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
