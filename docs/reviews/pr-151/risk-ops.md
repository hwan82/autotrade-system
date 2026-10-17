# risk-ops 검토: D-107 실전 엔진 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R2)
- 대상: PR #151 / 커밋 4e778bd6ec52ed03b4634495e430d810fc8f0cf1 / `git diff origin/main...HEAD` 전체(5파일: `EngineMain.java`, `EngineMainTest.java`, D-107, 결정 로그 README, runbook README)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build` 1회차: `:autotrade-app-collector:test` 의존성 내려받기 실패(Maven Central 응답 실패, 환경 문제)로 중단.
  - `./gradlew build --continue` 2회차: `:autotrade-architecture-test:compileTestJava` 의존성 429(Too Many Requests, 환경 문제), `NotificationQueueTest#큐가_차서_회복_알림을_버려도_다시_멈춤은_억제하지_않는다`(NotificationQueueTest.java:231) 1건 실패. 이 PR은 `autotrade-notification-jar`를 바꾸지 않는다.
  - `:autotrade-architecture-test:test :autotrade-notification-jar:test :autotrade-app-engine:test :autotrade-risk:test --rerun-tasks`: notification-jar 86/86, app-engine 60/60, risk 55/55 통과(위 NotificationQueueTest 재실행 통과 → 시간 의존 간헐 실패로 판단 `[추론]`). architecture-test는 다시 429.
  - `:autotrade-architecture-test:test` 단독 재시도: 통과.
  - `./gradlew build` 최종: **BUILD SUCCESSFUL**(138 tasks). 테스트 결과 XML 집계(실행/건너뜀/실패/오류): app-engine 60/0/0/0(EngineMainTest 18), risk 55/0/0/0(RealTradingGuardTest 31, GuardedOrderGatewayTest 8), architecture-test 46/0/0/0, app-watchdog 53/0/0/0, config 42/0/0/0, control 23/0/0/0, notification-jar 86/0/0/0.

## 변경 요지(코드로 확인한 것)
- `EngineMain.main`(EngineMain.java:64-82)이 새 `launch`(:100-110)를 부른다. `launch`는 `IOException | RuntimeException`을 잡아 `StartFailure.of`(:279-287)로 종류를 정하고 `report`(:290-297)로 로그 ERROR·표준 오류에 남긴 뒤 `exit`(진입점에서는 `System::exit`)를 부르고 null을 돌려준다. `Error`는 잡지 않는다.
- 종류: `EngineRefusal`(설정 폴더 경로의 `IllegalArgumentException`/`InvalidPathException`을 `configPaths`(:116-122)가 바꿈) → 2, `IOException`(명령 채널, `ControlServer` 생성자 ControlServer.java:81-133가 유일한 출처) → 1(기존 문구), 나머지 → "프로그램 오류" 1.
- 기동 순서(설정 로딩 → `startGuard` → 알림 → 명령 채널 → 하트비트, :129-156)는 그대로이고, 알림 설정 옮기기만 `Function`으로 주입 가능하게 바뀌었다(진입점은 `EngineMain::setup`).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성은 `startGuard`(EngineMain.java:191-201, `new RealTradingGuard(...)`) 그대로, 상태를 바꾸는 코드·설정 없음. 실패 경로는 엔진 객체를 돌려주지 않고 종료(:104-109). `EngineMainTest#진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다`(:326), `#진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다`(:340, 실패 뒤 재기동 시 2계좌 모두 `GuardState.OFF`) 통과. `RealTradingGuardTest#재기동하면_열려_있던_계좌도_OFF로_시작한다`(RealTradingGuardTest.java:61) 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 변경 없음(diff에 `autotrade-risk`, `order/` 없음). `commandHandler`의 `ActivationStepRunner.BROKER_NOT_CONNECTED`·`PanicLiquidator.BROKER_NOT_CONNECTED`(:219-220) 그대로. `:autotrade-architecture-test:test` 46건 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 코드 없음(현재 엔진은 증권사 미연결, :218-220). diff가 주문 라우팅을 건드리지 않음 |
| A4 | 해당 없음 | 주문·타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷 코드 변경 없음. 기동 실패는 프로세스 전체가 뜨지 않는 경우라 계좌 간 영향 문제가 아님 |
| A8 | 통과 | 실전 엔진 프로세스 안의 진입점만 바뀜. 다른 프로세스(실험실·게이트웨이·감시) 의존 추가 없음(import 변경은 `java.util.function` 3개뿐, EngineMain.java:34-36). 알림 단계 실패는 명령 채널을 열기 전이라 잠금·소켓을 남기지 않음(`#진입점은_알림_채널_코드_실수…`가 `ControlSocket.path(paths)` 없음과 바로 재기동 성공으로 확인). 명령 채널 연 뒤 예외는 `System.exit(1)`로 끝나 OS가 `FileLock`을 풀고, 남은 소켓은 다음 기동의 `removeStaleSocket`(ControlServer.java:288-293)이 치움 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 없음/변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(테스트 픽스처는 계좌 ID `KIS-US-1`·`KIWOOM-KR-1`과 채널 이름만, EngineMainTest.java:317-323). 새로 생긴 위험: 프로그램 오류 경로가 예외 메시지와 스택을 로그 ERROR에 남긴다(:291-292, 예전엔 JVM 기본 표준 오류 출력만). 기동 경로의 예외 메시지를 확인: 설정 로딩은 값을 싣지 않음(ConfigLoader.java:196-201 원인 미첨부, `InvalidConfigException` 경로·규칙만), 채널 꾸리기의 설정 값 예외는 `built`가 삼켜 이유 코드로 바꿈(NotificationSetup.java:288-296), 남는 예외(이름 검사 :95-101, `named` :302)는 상수 이름만. 설정 폴더 경로 오류 메시지는 경로 문자열(비밀값 아님) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음: `EngineMainTest`는 추가만(diff +107/-0), 기존 `#소켓_파일을_지워도_두_번째_엔진은_잠금_때문에_뜨지_않는다`(:199) 그대로 통과. 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작에 실패 경우 테스트 4건(:340 종료 코드 1·문구·소켓 없음, :365 기타 예외, :376 경로 오류 2, :389 명령 채널 1·예전 문구) |
| A12 | 통과 | 대응표 #8(실전 엔진 크래시) 감지는 하트비트 기준(broker-and-fault-design.md:167). 기동 실패 시 하트비트 파일을 만들기 전에 끝나는 점은 변경 전과 같음(하트비트는 명령 채널 뒤, EngineMain.java:143). CI 기동 점검(.github/workflows/ci.yml:100-103 "두 번째 엔진은 0이 아님")은 명령 채널 실패가 여전히 1이라 그대로. 장애 테스트 8종(7장) 전제(재기동 시 OFF, 중복 주문 0)와 해외 O1~O8에 닿는 동작 없음 |
| A13 | 해당 없음 | 시장별 경로·시간·금액 계산 변경 없음 |
| A14 | 통과 | D-107(docs/decisions/D-107-engine-start-failure-kinds.md) 결정 1~5가 코드와 일치: 2/1/1 분류(:279-287), `IOException` 문구 유지(:275 = 변경 전 main 문구), 로그·표준 오류 각 1줄(:290-297), `Error` 미처리. 결정 로그 README 112행, runbook README 표 갱신. 단, 아래 「점검표 밖」 1번(D-106 링크) 참고 |

## 점검표 밖 발견
1. (문서, 머지 순서) D-107 본문과 결정 로그 README 112행이 `D-106-watchdog-start-failure-kinds.md`를 링크하지만 이 브랜치와 `origin/main` 어디에도 그 파일이 없다(커밋 82e2cb5, 다른 브랜치·PR #148에만 있음). #148보다 먼저 머지되면 main에 깨진 링크가 남는다. "감시 D-106과 같은 기준"이라는 주장도 이 PR 안에서는 대조할 수 없다. 실전 동작과는 무관해 판정을 막지 않는다. #148 머지 뒤에 머지하거나, 먼저 머지하면 #148 머지 때 링크가 살아나는지 확인할 것.
2. (시험 보강 권고) `#진입점은_설정_폴더_경로가_틀리면…`(:376)은 공급자가 `InvalidPathException`을 직접 던지게 해 판정만 본다. 진짜 `ConfigPaths.defaultLocation()`에 `autotrade.config-dir` 시스템 속성(예: NUL 문자 포함)을 넣는 시험이 있으면 진입점 연결까지 덮는다. 코드상 `Path.of`(ConfigPaths.java:31,35)의 `InvalidPathException`은 `IllegalArgumentException`이라 `configPaths`가 잡는 것을 읽어서 확인했다.
3. (기존 한계, 후속 제안) 기동 실패는 로그·표준 오류에만 남고 외부 알림(Slack·카카오)으로 가지 않는다. 하트비트도 생기지 않아 감시는 "꺼짐(ABSENT)"으로 볼 뿐 긴급 알림을 내지 않는다(변경 전과 같음). 7단계 자동 재기동(#8) 설계 때 "기동 실패" 알림 경로를 함께 정할 것을 제안한다. 이 PR의 범위 밖.
4. (기존 시험 간헐 실패) `NotificationQueueTest#큐가_차서_회복_알림을_버려도_다시_멈춤은_억제하지_않는다`(NotificationQueueTest.java:231)가 1회 실패, 재실행 2회 통과. 보내는 스레드와 큐 가득 상태의 경합으로 보인다 `[추론]`. 이 PR과 무관하지만 보호 경로 모듈의 시험이라 별도 과제로 원인 확인을 권한다.
5. (참고) `run`(EngineMain.java:85-94)은 여전히 `IOException`만 잡고 나머지는 던진다(주석에 명시). 진입점은 `launch`만 쓰므로 운영 영향 없음.

## 판정
승인
