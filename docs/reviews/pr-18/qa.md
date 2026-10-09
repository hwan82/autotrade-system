# QA 검토: 실전 운영 화면: 실전 활성화 2차 확인과 PANIC SELL, 실전 제어 명령 채널 (ADR-006)
- 대상: PR #18 / 커밋 fd1e13f0bb4ddf3967f256eef2e48eddfb8a6f46 / main 대비 diff(merge-base 2dcea43, 57개 파일 +4720 −34). 현재 main(52ad85f)은 QA 보고서 문서 1건만 앞서 있고 병합 충돌 없음(`git merge-tree` 확인)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 검토자: 독립 QA 프로젝트 "자동매매 시스템 QA"
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --max-workers=2`: **BUILD SUCCESSFUL**. 테스트 결과 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0 (lab 97, sim 75, engine 48, risk 41, config 29, domain 27, strategy 25, control 20, architecture-test 13, watchdog 1, gateway 1, broker-api 1). 앞선 시도들은 Maven Central 429(Too Many Requests)로 의존성 다운로드 단계에서 실패했고 코드 실패는 없었다
  - `:autotrade-app-engine:installDist`로 만든 실제 엔진을 임시 설정 폴더(계좌 KIS-1, 자리표시자 계좌번호, 시험용 비밀번호 해시)로 띄우고 Python으로 소켓에 직접 요청(아래 "직접 시험" 표). 시험 후 설정 폴더 삭제
  - GitHub 체크 런(헤드 fd1e13f): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - gitleaks는 이 환경에서 내려받지 못해(릴리스 다운로드 실패) 직접 실행하지 못했다. CI의 같은 헤드 커밋 Secret scan 결과와 diff 수동 검색으로 대신했다(A10 근거)

## 보호 테스트 변경 여부(먼저 확인)
- `autotrade-risk` 모듈 변경 없음(`git diff --stat origin/main...HEAD -- autotrade-risk` 빈 결과). `RealTradingGuard`, `RealTradingGuardTest`, `GuardedOrderGatewayTest` 그대로.
- `ArchitectureTest`: 기존 규칙 삭제·완화 없음. 바뀐 것은 `GUARD_COMMAND_CHANNEL` 주석 한 줄과 규칙 3개 추가(`ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, `ONLY_ENGINE_OPENS_CONTROL_SERVER`, `ONLY_LAB_SENDS_CONTROL_COMMANDS`)와 위반 픽스처 2개(`ForgedConfirmation`, `MisplacedControlChannel`), 규칙이 위반을 잡는지 확인하는 단언 추가. 강화 방향이다.
- 장애 테스트 8종: 아직 자동화 전(7단계). 이 PR이 지우거나 바꾼 것 없음.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 코드·테스트 무변경(위). `EngineMain.startGuard`(EngineMain.java:97-108)는 기존 생성자로 모든 계좌 OFF. 새 설정 키는 `operator-password-hash`뿐이고 활성화 키 없음(autotrade-default.json, RuntimeConfig.java:147). `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다`, `#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과. 직접 시험: 기동 직후 STATUS `state:"OFF"` |
| A2 | 통과 | 실전 주문 출구 규칙(`REAL_ORDERS_ONLY_THROUGH_GUARD` 계열) 무변경. Guard를 올리는 `beginActivation`·`SecondConfirmation` 생성은 명령 채널 패키지 안에서도 `OperatorAuthenticator`만 가능(ArchitectureTest 새 규칙, 픽스처 위반 2건 단언). 이 PR에 증권사 주문 호출 코드 없음: `ActivationStepRunner.BROKER_NOT_CONNECTED`(ActivationStepRunner.java:20), `PanicLiquidator.BROKER_NOT_CONNECTED`(PanicLiquidator.java:28)가 아무 호출 없이 수행 불가를 돌려줌. 직접 시험: 맞는 비밀번호로 ACTIVATE → "AUTHENTICATED 단계를 수행할 수 없습니다 … OFF 상태로 돌아갔습니다" |
| A3 | 통과 | 증권사 선택·주문 전송 코드가 diff에 없음. failover 경로 신설 없음 |
| A4 | 통과 | 주문 재전송 경로 없음. 같은 원칙이 명령 채널에도 적용됨: `ControlClient`는 재시도하지 않고(ControlClient.java:396-433), `RealOpsController.send`는 응답 없음을 503 `ENGINE_UNREACHABLE`과 "다시 보내지 말고 상태를 새로 읽어 확인" 안내로 돌려줌(RealOpsController.java:83-90). 화면도 자동 재전송 없음(real.js `secondConfirmation`) |
| A5 | 해당 없음 | 주문 생성·멱등 키 코드 변경 없음 |
| A6 | 통과 | 이 PR은 매도 주문을 내지 않는다(PANIC 증권사 단계는 `NOT_AVAILABLE`). 블랙리스트·수동 보유 제외는 `PanicLiquidator` 계약으로 7단계 구현에 넘김(PanicLiquidator.java:10-24). 현재 어떤 경로로도 자동 주문 없음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 무변경. PANIC_ACCOUNT의 보호 모드 고정은 해당 계좌만(EngineCommandHandler.java:174-189). 단, 명령 채널이 단일 명령 스레드라 7단계에서 한 계좌의 긴 활성화가 다른 계좌의 PANIC을 막을 수 있다. 지금은 활성화 단계가 즉시 실패해 영향 없음. ADR-006 4장·6장에 "계좌별 비동기 PANIC" 7단계 완료 조건으로 등록됨(아래 위험 1과 함께 7단계 QA에서 다시 확인) |
| A8 | 통과 | 실험실은 클라이언트만 가지며 엔진은 서버만 가짐(ArchUnit 새 규칙 2개, 위반 픽스처 단언). 게이트웨이·감시는 `processIsolation`에 `:autotrade-control` 금지 추가(gateway·watchdog build.gradle.kts). 서버 자원 상한: 연결 8, 읽기 5초, 메시지 64KB, ACTIVATE 대기 2, 스레드 모두 데몬(ControlServer.java:56-78). 실험실이 꺼지거나 응답이 늦어도 엔진 Guard 상태에는 영향 없음. 5회 실패 잠금 뒤에는 해시 계산 전에 거절해 CPU 소모가 묶임(OperatorAuthenticator.java:66-68, 직접 시험 잠금 중 응답 0.005초) |
| A9 | 통과 | PANIC 1단계는 OPEN→PROTECTIVE(손절 유지)이고, Guard OFF는 증권사 단계 4개가 순서대로 모두 DONE일 때만(EngineCommandHandler.java:264-270, 290-294). 끝나지 않으면 보호 모드 유지, 서버 측 손절을 취소한 뒤 멈추면 긴급 표시. `EngineCommandHandlerTest#매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다`, `#서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다` 통과 |
| A10 | 통과 | 테스트 비밀값은 자리표시자(`PLACEHOLDER-NOT-A-REAL-KEY-7f3a`, `PLACEHOLDER-OPERATOR-HASH`), 해시는 테스트 실행 중 생성(diff에 `pbkdf2-sha256$<숫자>` 리터럴 없음). 요청 `toString`이 비밀번호·계좌 칸을 가림(ControlRequest.java:48-52), 감사 로그는 설정에 있는 별칭만 기록(EngineCommandHandler.java:327-332). 직접 시험: 엔진 로그에서 시험 비밀번호·자리표시자 계좌번호 0건. `RealOpsControllerTest#활성화는_확인_문구와_비밀번호를_엔진에_넘기고_응답과_로그에는_남기지_않는다` 통과. CI Secret scan(gitleaks) success(헤드 fd1e13f) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음(위 "보호 테스트 변경 여부"). 새 동작마다 실패 경우 테스트 있음: 틀린 비밀번호·문구, 잠금, 미설정 해시, 형식 오류 해시, 모르는 계좌·필드, 대기열 초과, 오래 기다린 활성화, 연결 상한, 소켓 삭제 후 두 번째 엔진, 심볼릭 링크 폴더, CSRF·교차 출처 거절 등(EngineCommandHandlerTest 22건, OperatorAuthenticatorTest 8건, OperatorPasswordHashTest 5건, ControlChannelTest 20건, RealOpsControllerTest 10건, EngineMainTest 8건) |
| A12 | 통과 | 대응표 #8(엔진 크래시 후 재기동): 잠금은 OS 파일 잠금이라 프로세스가 죽으면 풀리고, 남은 소켓은 듣는 쪽이 없으면 지우고 시작(ControlServer.java:288-296, `ControlChannelTest#죽은_엔진의_소켓은_지우고_시작한다`). 재기동 시 모두 OFF는 그대로. 새로 생긴 기동 실패 조건(소켓 경로 길이, 다른 엔진 실행 중)은 모든 계좌 OFF로 끝나 보호 공백을 새로 만들지 않음: 직접 시험에서 긴 경로 설정 폴더로 띄우면 "Unix domain path too long … AUTOTRADE_CONFIG_DIR을 짧은 경로로" 로그 후 종료 코드 1, 두 번째 엔진도 종료 코드 1. #9(실험실 크래시): 실험실은 클라이언트일 뿐. `run` 폴더 삭제 시 단일 실행 보장이 깨지는 점은 ADR-006 4장에 기재되고 Q-018(장애 테스트 추가 여부)로 사용자 결정 대기 |
| A13 | 해당 없음 | 시장별 계산 없음 |
| A14 | 통과 | 바뀐 동작의 결정은 ADR-006 7장(사용자 결정 8건, 이 PR 머지가 결정)과 결정 로그 Q-016~Q-018로 기록됨. 기획서 RSK-13(docs/spec/autotrade-spec.md:185)은 "둘 다 2차 확인", "장 외 시간에는 … Guard OFF"로 남아 있어 구현(1단계는 확인 전, 매도 미확인 시 보호 모드 유지)과 문구가 다르다. 이 차이는 ADR-006 6장 마지막 줄(머지 직후 pm-lead가 RSK-13 갱신)과 Q-017로 명시돼 있어 "새 결정이 기록되어 있다"를 충족한다고 판단. 머지 후 RSK-13 갱신이 실제로 됐는지는 5단계 완료 판정에서 다시 확인한다 |

## 점검표 B (B1, B6)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 "직접 실행한 것": `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 378건 실패 0 |
| B6 | 통과 | 새 동작 테스트는 A11 참조. diff의 테스트 파일에 `@Disabled`·`assume`·`@Ignore` 없음. 기존 테스트 수정은 `RealTrading` 레코드 필드 추가에 따른 생성자 인자·필드 목록 갱신 3곳(ConfigLoaderTest, RuntimeConfigHolderTest, SecretTest)뿐이고 단언은 오히려 늘었다(해시가 secrets.json에만 저장되는지 확인) |

## 과금 검사 (QA 지침 8장)

| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | diff의 Java·JS·Gradle·CI 추가 줄에서 외부 호출(HttpClient, URL, WebSocket, RestTemplate, WebClient, 외부 fetch)을 검색해 0건. 새 통신은 같은 PC의 Unix 도메인 소켓(ControlClient↔ControlServer)과 브라우저→127.0.0.1 실험실 API(`/api/real/*`)뿐이다. CI에 엔진·실험실 기동 점검이 늘었지만(ci.yml, 최대 30초 대기) 외부 유료 API 호출은 없다. 유료 호출 지점 목록: 없음 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음 |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 없음. 실전 활성화 자체도 사람의 2차 확인 없이는 시작되지 않고, 시작돼도 지금은 첫 단계에서 실패한다 |
| 8.4 폭주 방지 | 통과 | 외부 호출 없음. 내부 명령 채널은 연결 8개 상한, ACTIVATE 대기 2개(초과 BUSY), 5회 실패 시 15분 잠금(잠금 중에는 해시 계산 없이 거절), 클라이언트 재전송 없음. `ControlChannelTest#동시_연결_상한을_넘으면_닫고_자리가_나면_다시_받는다`, `#대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다`, `OperatorAuthenticatorTest#다섯_번_연속_실패하면_15분_잠기고_맞는_비밀번호도_거절한다` 통과 |
| 8.5 실주문 비용 | 통과 | 실주문을 내는 코드가 없다(A2). PANIC은 보호 모드 고정까지만 실제로 하고 증권사 단계는 `NOT_AVAILABLE`. 직접 시험에서도 활성화는 OFF로 복귀. 의도치 않은 실주문 경로 없음 |

## 직접 시험(실제 엔진, 소켓 직접 요청)

| 시험 | 결과 |
|---|---|
| 소켓 폴더·파일 권한 | `run` 700, `engine-control.sock` 600 |
| STATUS | 0.06초, KIS-1 OFF, 비밀값 없음 |
| 맞는 비밀번호·문구로 ACTIVATE | 0.78초, "AUTHENTICATED 단계를 수행할 수 없습니다 … OFF 상태로 돌아갔습니다" |
| 틀린 비밀번호 1회(ACTIVATE) + PANIC_ALL 4회 | CONFIRMATION_FAILED ×3 후 5번째 LOCKED |
| 잠긴 상태에서 맞는 비밀번호로 PANIC_ACCOUNT | 0.005초 LOCKED, "보호 모드 고정만 했고 청산은 하지 않았습니다" |
| 잠긴 상태에서 DEACTIVATE | 바로 처리("이미 OFF") |
| 모르는 필드가 있는 요청 | BAD_REQUEST, 원문 미포함 |
| 같은 설정 폴더로 두 번째 엔진 | 종료 코드 1 |
| 소켓 경로가 긴 설정 폴더 | 기동 거부, 종료 코드 1, 짧은 경로 안내 |
| 600,000회 해시 검증 중 STATUS | 0.62초 지연(검증이 끝날 때까지 대기) |
| 10,000,000회 해시(파서 허용 상한) 검증 중 STATUS | **10.64초** 지연, ACTIVATE 응답 10.76초. 검증 중 DEACTIVATE는 0.006초 |

## 점검표 밖 발견

아래 위험은 모두 지금 코드로는 실전 계좌를 열 수 없어서(활성화 첫 단계 실패, 주문 코드 없음) **현재 피해 경로가 없다**. 그래서 승인을 막지 않지만, 7단계에서 활성화 단계와 PANIC 증권사 단계를 실제로 채우는 순간 살아나는 결함이므로 7단계 QA에서 해결 여부를 확인한다.

1. **실험실이 "응답 없음"으로 처리한 뒤 활성화가 끝날 수 있다(가장 큼).** `STALE` 판정은 대기열에서 기다린 시간만 본다(ControlServer.java:239-244). 대기 9.9초로 통과한 ACTIVATE가 그 뒤 해시 검증(600,000회 약 0.7초, 파서가 허용하는 10,000,000회는 직접 시험 10.76초)과 활성화 단계(7단계 증권사 인증·세션·대사)를 하는 동안 실험실 대기 상한 15초(LabWebConfiguration)를 넘기면, 화면은 503 "반영됐는지 알 수 없음"을 보이는데 엔진은 계속 진행해 OPEN이 될 수 있다. 사람이 결과를 보지 못한 채 실전이 열리는 시나리오다. 띠는 5초 폴링으로 "실전 가동 중"을 보이겠지만 ADR-006 2.2의 의도("사람이 모르는 사이에 실전이 열리면 안 된다")를 기한 하나로만 지킨다. architect 참고(PR 본문 "기한 넘긴 활성화를 처리기에서도 버리기")로만 남아 있고 ADR-006 6장 후속 표에는 이 항목이 따로 없다. 제안: 6장 표에 7단계 완료 조건으로 올리고, 처리기가 `beginActivation` 직전과 각 단계 직전에 요청 접수 후 경과 시간을 다시 확인해 기한을 넘기면 출발 상태로 되돌리게 한다.
2. **해시 검증이 검증기 잠금을 쥔 채 돌아 상태 조회를 막는다.** `verify`와 `lockedUntil`이 같은 `synchronized`라(OperatorAuthenticator.java:48, 60), 검증 중 STATUS는 검증이 끝날 때까지 기다린다(EngineCommandHandler.java:97). 파서가 반복 횟수를 10,000,000까지 받으므로(OperatorPasswordHash.java:28) 그런 해시를 넣으면 검증마다 STATUS가 10초 넘게 막혀(직접 시험 10.64초) 실험실 상태 조회(2초 상한)가 UNKNOWN으로 떨어진다. 화면은 "확인 불가"로 안전한 쪽이고 DEACTIVATE의 Guard 전이는 막히지 않지만(직접 시험 0.006초), 7단계에서 같은 잠금이 PANIC 경로 응답도 늦춘다. architect 참고와 같은 내용이며 반복 상한을 실험실 대기 상한과 맞춰 낮추는 것도 함께 검토할 것.
3. **같은 OS 사용자로 도는 프로그램이 잠금을 계속 걸어 PANIC 청산을 막을 수 있다.** 틀린 확인 5번이면 15분 잠기고 잠긴 동안 PANIC은 보호 모드 고정까지만 한다(EngineCommandHandler.java:174-189). 15분마다 5번 틀린 요청을 보내면 사람이 PANIC 청산을 계속 못 한다. 보호 모드 고정과 서버 측 손절은 남으므로 손실 확대 방향은 아니지만, ADR-006 4장 위험 표의 "같은 OS 사용자로 도는 악성 프로그램" 행은 이 영향을 적지 않았다. 7단계에서 잠금 발생 시 외부 알림(이미 "실전 활성화 외부 알림" 후속이 있음)에 잠금도 포함할 것을 제안.
4. **해시 도구 안내와 설정 문서가 다르다(경미).** `hash-password` 출력은 "실전 엔진을 다시 시작하세요"(EngineMain.java:134)만 말하고, config-keys.md는 "실전 엔진과 실험실을 모두 다시 시작"을 요구한다. 실험실을 재시작하지 않으면 설정 저장이 `CONFIG_CHANGED_ON_DISK` 409로 막혀 해시가 덮어써지지는 않으므로 안전 문제는 아니다.
5. **잠금 파일을 심볼릭 링크를 따라 연다(경미).** `FileChannel.open(dir.resolve(LOCK_FILE), CREATE, WRITE)`(ControlServer.java:87)에 `NOFOLLOW_LINKS`가 없어 `run/engine.lock`이 링크면 대상 파일을 잘라 PID를 쓴다. `run`이 700이라 같은 사용자만 만들 수 있어 영향은 작다.

### 잘못된 주문·중복 주문·보호 공백 시나리오 시도
- (가) 응답 시간 초과 뒤 활성화 반영: 위 1번. 지금은 첫 단계에서 실패해 재현되지 않음. 7단계 위험.
- (나) 한 계좌 활성화가 멈춘 동안 다른 계좌 PANIC: 단일 명령 스레드라 PANIC이 뒤에서 기다리고(ControlServer.java:229-231, 시간 상한 없음), 사용자가 PANIC을 반복하면 기다리는 연결이 8개 자리를 채워 STATUS·DEACTIVATE도 받지 못한다. 지금은 활성화가 즉시 실패해 발생하지 않음. ADR-006 4장·6장에 "계좌별 비동기 PANIC", "하향 명령용 연결 자리 예약, 기다리는 PANIC의 시간 상한"으로 7단계 완료 조건 등록됨.
- (다) 브라우저 교차 출처 요청으로 내리기·PANIC 1단계 유도: 기존 `LocalOnlyRequestFilter`(Origin·Sec-Fetch-Site·X-CSRF-Token)가 `/*` 전체에 걸려(WebConfiguration.java:24-28) 엔진까지 가지 않음. `RealOpsControllerTest#CSRF_토큰이나_같은_출처가_아니면_엔진까지_가지_않는다` 통과.
- (라) 검증 재사용으로 다른 계좌 열기: 검증은 그 확인 문구에 묶이고 한 번 쓰면 사라지며 명령 끝에 버려짐(OperatorAuthenticator.java:97-109). `PANIC_문구로_통과한_검증으로는_계좌_2차_확인을_만들_수_없다`, `검증은_그_계좌에만_쓸_수_있고_명령이_끝나면_버린다` 통과. 계좌 별칭 정규식(`[A-Za-z0-9_-]`)이라 "전체 청산"과 겹칠 수 없음.
- (마) 두 번째 엔진으로 Guard 두 벌: 소켓을 지워도 `engine.lock`으로 거부(직접 시험). `run` 폴더 자체를 지우면 깨지는 점은 ADR-006 4장과 Q-018에 기재.

## 판정
승인

점검표 A·B1·B6·과금 검사에 실패와 확인 못 함이 없다. 점검표 밖 발견 1~3은 지금 실전 계좌를 열 수 없어 피해 경로가 없지만, 7단계 활성화·PANIC 구현 PR과 7단계 실전 활성화 판정에서 해결 여부를 확인한다. 특히 1번은 ADR-006 6장 후속 표에 7단계 완료 조건으로 올릴 것을 권고한다. 머지는 사용자가 한다.
