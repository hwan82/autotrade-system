# QA 검토: 실시간 시세 전달(게이트웨이 → 실험실)과 실시간 모의 세션 (D-076, ADR-010)
- 대상: PR #87 / 커밋 e3c76527b9468ff2474f1a2b4c574d052757c835 / main 대비 diff 전체(merge-base 4e32d75865e0b251c7261617d553570d20a33d0a = 현재 origin/main, main 뒤처짐 없음)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 실전 주문 경로·RealTradingGuard·블랙리스트 코드는 바뀌지 않았지만, 7단계에 실전 엔진에 시세를 줄 게이트웨이 수신 경로(`MarketHub`)에 새 출구(`FeedServer.publish`)가 생기고, 장애 대응 문서(broker-and-fault-design 5.2 2번, 장애표 #9·#10)가 바뀐다. 지침 10장에 따라 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-87/architect.md`, `risk-ops.md`), D-076·ADR-010의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 이 PR은 1단계 C5 문서 4건(Q-009, ADR-002 상태, ADR-001 2.4·2.7) 수정 PR이 아니다. 그 4건 파일은 diff에 없다(`git diff --stat`에 ADR-001·ADR-002·결정 로그 Q-009 행 변경 0건)
- 직접 실행한 것:
  - `git diff origin/main...pr87 --stat`: 47파일, +4,559 −256. 운영 코드: `autotrade-marketdata`(`feed` 패키지 신규 5개, `recording` 3개), `autotrade-app-gateway`(Gateway·GatewayMain·MarketHub), `autotrade-sim`(`live` 패키지 신규 5개, `run.RunStepper` 신규, `SimulationExperimentRunner`·`RecordingExperiment` 정리), `autotrade-app-lab`(LiveLabService·LiveSessionController·설정), `autotrade-app-experiment`(live-check 진입점·Gradle 작업)
  - 보호 영역 변경 여부: `git diff --stat origin/main...pr87 -- autotrade-architecture-test autotrade-risk autotrade-broker* autotrade-app-engine autotrade-app-watchdog autotrade-control autotrade-domain` → **0줄**
  - `./gradlew build --no-daemon`(head e3c7652): Maven Central 429로 12회 실패(재시도 스크립트가 실패 로그마다 429를 확인한 뒤에만 재시도, 루프 하나) 뒤 13회째 **BUILD SUCCESSFUL**(130 tasks). 테스트 결과 XML 합계 tests 1,056, failures 0, errors 0, skipped 0
  - CI(head e3c7652, check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success
  - QA 탐침(별도 작업 트리, 저장소에 넣지 않음):
    - P1 `QaFeedProbeTest`(marketdata `feed` 패키지): 실제 `FeedServer.open`(큐 50,000·30초)에 구독만 하고 **읽지 않는** 받는 쪽 4개를 붙이고, 수신 스레드 2개(SHARDED 흉내)가 각 30만 건 `publish`. 결과: 다섯 번째 연결은 상한으로 닫힘, 버림 2,198,888·4,397,776건(두 실행), `publish` 1회 지연 **p99.9 = 39.7µs, 최대 14.1~51.8ms**. 받는 쪽 0개 기준선은 p99.9 0.8µs, 최대 4.2ms. 닫은 뒤 소켓 파일 삭제 확인. → 기다림(블로킹)은 없고 꼬리 지연만 있다(아래 R1)
    - P2 `QaLiveProbeTest`(sim `live` 패키지, `LiveSessionTest` 픽스처 재사용): 1일차 중간(762번째)·끝(1,525번째)에서 비정상 종료(닫지 않은 메타, gz 꼬리 20바이트 자름) 뒤 **2일차 이벤트로 다시 시작**(1일차 파일은 다시 열리지 않음 — 기존 `crashWithLostTailAndUnclosedMetaStillReplaysTheSame`은 같은 날 재개만 본다). 되살린 이벤트 761·1,524, 2일차 전부 ACCEPTED, `LiveReplayCheck.run` **same=true** 두 경우 모두
  - diff 추가 줄에서 `appkey|secret|password|token\s*=|계좌번호` grep: 걸린 것은 검토 원문 표의 설명 줄뿐. 값 0건

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 코드·설정·테스트 변경 없음(`autotrade-risk` diff 0줄). `RealTradingGuardTest`·`GuardedOrderGatewayTest` 위 빌드에서 통과 |
| A2 | 통과 | 주문 출구·`ArchitectureTest` 변경 없음(`autotrade-architecture-test` diff 0줄), 위 빌드 통과. 새 `feed` 패키지는 주문 포트를 모른다(import는 domain market·recording만, `FeedServer.java:3-32`). 받는 쪽이 보낼 수 있는 것은 구독 줄 하나뿐(`FeedProtocol.parseSubscribe`, 1KB·`[a-z0-9-]{1,32}`, `FeedProtocol.java:32,35`)이고 그 뒤 무엇이든 오면 끊는다(`FeedServer.java:312-319`) |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음. 게이트웨이는 여전히 주문 API를 부르지 않는다(`GatewayMain.java:34-35` 주석, MD-01) |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 통과 | 실전 블랙리스트 경로 변경 없음. 실시간 모의 세션도 리플레이와 같은 `RunStepper`(리스크 체인·블랙리스트 그대로)를 쓰고, `LiveSessionTest.blacklistedSymbolIsNeverBoughtInTheLiveSession` 통과(위 빌드) |
| A7 | 해당 없음 | 실전 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 게이트웨이: `publish`는 받는 쪽마다 `ArrayBlockingQueue.offer`만 하고 예외를 삼킨다(`FeedServer.java:114-126`, `:241-259`). 쓰기는 받는 쪽 전용 데몬 스레드(`:175`), 한 줄 쓰기가 30초 넘게 막히면 타이머가 채널을 닫아 끊는다(`:189-195`). 받는 쪽 4개 상한(`:168-172`), 연결 받기 실패 백오프 10ms→1초(`AcceptBackoff`). 소켓을 못 열면 경고 뒤 녹화만(`GatewayMain.java:110-119`, `GatewayFeedTest.전달_소켓을_열지_못해도_게이트웨이는_녹화만_한다` 통과). 녹화가 먼저, 전달이 다음(`MarketHub.java:227-238`). QA 탐침 P1: 읽지 않는 받는 쪽 4개·수신 스레드 2개에서도 막힘 없음(꼬리 지연은 R1). 실험실: 감시 정지 요청 파일·디스크 하한으로 시작 거부·1초 안 정지(`LiveLabService.java:265-282`, `:352-359`), 되살리기는 최저 우선순위 스레드·기록일 단위 정지 확인(`:149`, `LiveSession.java:190-191`), `bootRun` 힙 4g(`autotrade-app-lab/build.gradle.kts`). `LiveLabServiceTest` 5개 통과. 실전 엔진은 sim·lab·gateway 의존 없음(엔진 build 변경 0줄) |
| A9 | 통과 | 실전 엔진·보호 시세 경로·서버 측 손절 코드 변경 없음. 게이트웨이 수신·녹화 순서·녹화기 큐는 그대로이고 전달은 그 뒤에 붙기만 한다 |
| A10 | 통과 | 추가 줄에 키·계좌번호·토큰 없음(위 grep), CI Secret scan success. 소켓 폴더 700·파일 600(`FeedSocket.java:29-50`, `FeedServerTest` 소켓 권한 시험 통과). 로그에는 소켓 경로·받는 쪽 이름·예외 클래스 이름만 |
| A11 | 통과 | 보호 테스트 파일 변경 0건(위 보호 영역 diff 0줄, `@Disabled` 추가 0건). 새 동작마다 실패 경우 포함 시험: `FeedServerTest`(느린·멈춘 받는 쪽, 상한, 틀린 구독 줄, 두 번째 게이트웨이 거부), `LiveSessionTest`(호가 단위 밖 가격 멈춤, 리플레이 불일치 검출, 잠금), `LiveLabServiceTest`(정지 요청·디스크 하한), `ExperimentGateTest` 2개(장 시간·정지 요청에 live-check 거부, 인자 오류) |
| A12 | 통과 | broker-and-fault-design 5.2 2번·장애표 #9·#10 새 문구를 코드와 대조: 정지 요청 경로 `experiment-state/stop-request`(`LiveLabService.java:52`), 디스크 기본 20GB(`:55`, `LabWebConfiguration` `min-free-gb:20`), 30초 끊기(`FeedServer.java:53`), `CONSUMER_OVERFLOW`·`DISCONNECTED`·`RECORDER_RESTART` 공백 종류, 자동 재시작 없음(`LiveLabService` 재기동 시 세션 미개시), 힙 4g → 일치. 장애 테스트 8종 전제(실전 엔진 격리, 시세 전환, 엔진 재기동) 변경 없음 |
| A13 | 해당 없음 | 실시간 세션은 KRX만(`LivePlan`), 시간대는 캘린더 `zone`, 금액은 기존 실행기 BigDecimal |
| A14 | 통과 | D-076 1~7번 숫자·동작을 코드와 대조: 소켓 경로 `run/market-feed.sock`(`FeedSocket.java:19-20`), 큐 50,000(`FeedServer.java:51`), 구독 5초(`:52`), 받는 쪽 4(`:49`), 받는 큐 100,000(`LiveLabService.java:53`), 리플레이 불일치 종료 코드 2·정지 4(`LiveCheckMain.java:63,69`), 세션에 장 시간 보호 없음·live-check에는 있음(`ExperimentGateTest`) → 일치. 결정 로그 README 행·기획서 v14.24·ADR-003 5장 행·ADR-004·ADR-005 API 표·config-keys 3키 갱신 확인. ADR-010 상태 "제안(D-076)"은 README 표와 같다(머지 뒤 처리는 R4) |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(429 재시도 후), 1,056건 실패·오류·건너뜀 0 |
| B6 | 통과 | 새 동작에 시험 있음(A11). 건너뛰거나 지운 테스트 없음. 실행기 공통부 분리 뒤 골든(`krx-golden.report`) 시험 그대로 통과 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출 없음. 전달은 같은 PC 로컬 소켓이고, 게이트웨이의 증권사 연결·구독은 그대로(이미 무료 확인된 키움·한국투자 실시간 시세). 유료 라이브러리·서비스 추가 없음(`build.gradle.kts` 의존 추가 0건) |

## 위험 시나리오 시도 (점검표 밖, 지침 5장 5번)
1. **실험실이 멈추거나 읽지 않음 → 게이트웨이 수신 지연**: P1 탐침으로 재현. 막힘 없음, 버림과 공백으로 끝난다. 최대 지연 14~52ms(기준선 4ms)는 R1.
2. **실험실이 날짜를 넘겨 비정상 종료 → 결정론 깨짐·복구 실패**: P2 탐침. 1일차 꼬리 잘림·메타 미종료 상태로 2일차에 재개해도 되살리기 성공, 실시간 결과 = 입력 기록 리플레이(same=true). 잘못된 모의 결과가 승격 근거가 되는 경로는 찾지 못했다.
3. **`run/` 폴더 공유(실전 제어 소켓·`engine.lock`과 같은 폴더)**: `FeedSocket.removeStaleSocket`은 `market-feed.sock` 하나만, 누가 듣고 있지 않을 때만 지운다(`FeedSocket.java:53-61`). `market-feed.sock`이 실전 제어 소켓을 가리키는 심볼릭 링크면 연결 탐침이 성공해 "이미 쓰는 중"으로 거부하고, `engine.lock`을 가리키면 링크 자체만 지운다(`deleteIfExists`는 링크를 따라가지 않음). `run` 폴더 자체가 심볼릭 링크면 거부(`FeedSocket.java:30-32`). 실전 제어 채널(`ControlServer`)의 정리 코드는 `engine-control.sock`만 지운다(`ControlServer.java:126,295,345`) `[추론, 코드 읽기, 실행 탐침 없음]`. 엔진 잠금·제어 소켓을 지우는 경로는 찾지 못했다.
4. **감시 정지 요청·디스크 부족 중 세션**: 1초 주기 확인, 되살리기는 기록일마다(`LiveLabServiceTest` 통과). 하한을 0으로 두면 디스크 보호가 꺼진다(R2).

## 점검표 밖 발견 (권고, 차단 아님)
- **R1 게이트웨이 수신 스레드 꼬리 지연**: 읽지 않는 받는 쪽 4개에서 `publish` 최대 14~52ms(p99.9 40µs, 기준선 최대 4ms). 막힘은 아니고 버린 이벤트마다 `DataGap`·`Item`을 새로 만드는 할당과 `Subscriber.offer`의 `synchronized` 경합으로 본다 `[추론]`. 지금은 녹화 지연뿐이지만 7단계에 같은 수신 스레드가 실전 엔진에 시세를 주면 그 지연이 엔진 입력에 들어간다. 7단계 엔진 전달 설계 때 받는 쪽이 꽉 찬 상태의 수신 지연을 측정 기준(예: 최대 지연 상한)으로 넣을 것.
- **R2 디스크 하한 0 허용**: `autotrade.lab.live.min-free-gb=0`이면 하한이 꺼진다(`LiveLabService.java:105`, 0 이상 허용). 막는 것은 절차서 한 줄(`stage-6-live-session.md` 2-5)뿐이다. 실전 엔진 기록과 같은 디스크일 수 있으므로 코드에서 1 미만을 거부하거나 시작 때 경고 로그를 남길 것.
- **R3 받는 쪽 자리와 인코딩 비용**: 같은 OS 사용자의 아무 프로세스나 4자리를 차지할 수 있고(N6), 받는 쪽마다 JSON을 따로 인코딩해 게이트웨이 CPU가 받는 쪽 수에 비례한다. 7단계 엔진 전달 설계 때 같이 볼 것(ADR-010 5장 N5·N6 행과 묶음).
- **R4 ADR-010 상태**: 머지 뒤 "제안"으로 남으면 1단계 판정 C5의 ADR-002와 같은 불일치가 된다. 머지 때(또는 직후) "채택(D-076)"으로 바꿀 것.
- **R5 날짜 넘긴 비정상 종료 시험**: P2 경우(1일차 꼬리 잘림 뒤 2일차 재개)는 QA 탐침으로만 확인됐다. `LiveSessionTest`에 시험으로 넣을 것.
- **R6 되살리기 중 정지 확인 간격**: 기록일 하나를 다 읽은 뒤에야 정지 요청을 본다(`LiveSession.java:190-198`). 하루치 기록이 크면 그만큼 늦게 멈춘다. 6단계 첫 주 하루치 되살리기 시간을 주간 점검에 기록할 것.

## 판정
승인
