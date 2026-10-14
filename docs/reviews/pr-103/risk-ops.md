# risk-ops 검토: 실행 매뉴얼: 프로그램별 실행 시점·명령·종료 코드 한 장 안내 (PR #103)
- 대상: PR #103 / 커밋 e1cf82d5543c1abc23960cebbcd95ab99da3a7dd / main 대비 diff (`git diff origin/main...HEAD`: `README.md` +1, `docs/runbooks/README.md` +92, 코드·설정·스키마·테스트 변경 0건)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build` 1·2차: 의존성 다운로드가 Maven Central `429 Too Many Requests`, `Could not download junit-jupiter-engine-5.12.2.jar`로 실패(환경 네트워크 문제, PR과 무관)
  - `./gradlew build --continue` 3차: **BUILD SUCCESSFUL**. 테스트 결과 XML 집계: `:autotrade-risk:test` 55건 실패 0·오류 0·건너뜀 0, `:autotrade-architecture-test:test` 32건 0·0·0, `:autotrade-app-engine:test` 50건 0·0·0(`EngineMainTest`, `EngineCommandHandlerTest` 포함)
  - 매뉴얼 주장 대조: `EngineMain.java`, `EngineCommandHandler.java`, `OperatorAuthenticator.java`, `OperatorPasswordHash.java`, `RealTradingGuard.java`, `GatewayMain.java`, `WatchdogMain.java`, 각 `build.gradle.kts`의 `applicationName`, ADR-006, broker-and-fault-design.md 5.2·6장
  - JVM Ctrl+C 종료 코드 실측: 종료 훅 + `CountDownLatch.await()` 구조(EngineMain·GatewayMain과 같음)의 최소 프로그램에 SIGINT → **종료 코드 130** (Linux, JDK 21)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 변경 없음. 매뉴얼 4장(docs/runbooks/README.md:70-71) "모두 OFF로 시작, 설정 어디에도 바꾸는 키 없음"은 코드와 일치: EngineMain.java:94-98(`startGuard`, 로그 "RealTradingGuard 모두 OFF로 시작"), RealTradingGuardTest#생성_직후_모든_계좌는_OFF다, #재기동하면_열려_있던_계좌도_OFF로_시작한다, #상태를_받아_열린_채로_만드는_생성_경로가_없다 통과. 매뉴얼이 Guard를 켜는 우회 절차를 안내하지 않음 |
| A2 | 통과 | 코드·ArchUnit 변경 없음. `:autotrade-architecture-test:test` 32건 통과. 매뉴얼은 활성화를 ADR-006 2차 확인 경로로만 안내(README.md:73) |
| A3 | 해당 없음 | 주문 라우팅 코드·문서 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·계좌 격리 변경 없음 |
| A8 | 통과 | 코드 변경 없음. 매뉴얼 5장(README.md:79-80)이 "작업 관리자에서 java.exe 강제 종료 금지(실전 엔진도 같은 java.exe)", "정지 요청 파일은 감시만 지운다"를 안내해 broker-and-fault-design.md 5.2 0번(감시는 프로세스를 죽이지 않고 파일만 만든다)과 일치. `-PallowDuringSession`은 실전 엔진·게이트웨이를 끈 날에만(README.md:34, 83)으로 autotrade-app-experiment/build.gradle.kts:39 주석보다 엄격하게 안내 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음(재기동 시 보유 보호 안내 누락은 점검표 밖 권고 2) |
| A10 | 통과 | diff 전체에 키·계좌번호·토큰·해시 값 없음. 경로는 `<사용자>`, `<설정 폴더>` 자리표시자. README.md:66, 79-85가 키·토큰 마스킹과 업로드 금지를 안내 |
| A11 | 통과 | 테스트 파일 변경 0건(`git diff --name-only origin/main...HEAD`), 보호 테스트 그대로 통과(위 실행 결과) |
| A12 | 통과 | 장애 대응표 6장 17행(직접 집계)·해외 O1~O8·장애 테스트 8종 문서와 코드 변경 없음. 매뉴얼은 실전 활성화 전제로 "장애 테스트 8종 합격"을 명시(README.md:72) |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음(장 시간 보호 구간 시각 설명은 collector.md 3.5를 단일 원본으로 링크) |
| A14 | 해당 없음 | 동작 변경 없음, 새 결정 불필요. 단 매뉴얼 문구 일부가 ADR-006과 어긋남(아래 1) |

## 점검표 밖 발견

1. **[수정 필요] "내리기(Guard OFF)" 표기가 실제 동작과 다르다.** docs/runbooks/README.md:75
   - `[사실]` 내리기(DEACTIVATE)는 OPEN → **PROTECTIVE**(보호 모드: 신규 진입 차단, 손절·익절과 서버 측 손절 유지), ACTIVATING → 출발 상태다. OFF로 가지 않는다: EngineCommandHandler.java:30, ADR-006 2.3 표(ADR-006-real-control-channel.md:59), RealTradingGuard.java:31.
   - 영향: 운영 매뉴얼은 7단계에 사람이 위급할 때 읽는 문서다. "내리기 = Guard OFF"로 알면 (가) 내린 뒤에도 보유 종목 손절 주문이 나가는 것을 오동작으로 오해하거나, (나) 반대로 완전 정지가 필요할 때 내리기만으로 끝났다고 판단할 수 있다. 실전 계좌 상태를 사람이 잘못 이해하게 하는 문구라 고쳐야 한다.
   - 고칠 방향: "내리기(OPEN → 보호 모드. 신규 진입만 막고 보유 손절은 계속)"로 쓰고, Guard OFF는 PANIC SELL 증권사 단계가 모두 끝났을 때만 된다는 점(ADR-006 2.5 6단계)을 한 줄 덧붙인다.

2. **[수정 필요] 게이트웨이·실전 엔진의 종료 코드 "0 = Ctrl+C로 정상 종료"가 틀렸다.** docs/runbooks/README.md:64
   - `[사실]` EngineMain.java:58-67, GatewayMain.java:68-74는 종료 훅에서 정리한 뒤 `System.exit(0)`을 부르지 않는다. 같은 구조의 프로그램에 SIGINT를 보내 실측한 종료 코드는 **130**(128+SIGINT)이다(Linux, JDK 21). `[추론]` Windows의 JVM도 Ctrl+C를 SIGINT로 처리해 같은 130을 낼 것으로 보지만 Windows 실측은 못 했다. 설치본 `.bat`로 띄우면 cmd가 "일괄 작업을 끝내시겠습니까 (Y/N)?"를 물어 종료 코드가 더 달라질 수 있다 `[추론]`.
   - 영향: 이 표는 "끝난 뒤 읽는 법"이 목적이다. 사용자가 정상 종료 후 130을 보고 이상으로 보고하거나, 반대로 "0이 아니면 이상"이라는 규칙을 버리게 된다. 실전 엔진의 이상 종료(1: 명령 채널을 못 열어 기동 중단, EngineMain.java:54-56) 신호를 흐리게 한다.
   - 고칠 방향: Ctrl+C 종료는 130(정상), 1은 기동 실패로 적고, 정상 여부는 종료 코드보다 로그(게이트웨이는 녹화 메타의 정상 종료 표시)로 판단하라고 쓴다. Windows에서 실제 값을 한 번 확인해 `[사실]`로 바꾼다.

3. **[권고, 비차단] 재기동과 보유 종목 보호.** docs/runbooks/README.md:71, 74
   - `[사실]` 재기동하면 보유가 있어도 모든 계좌가 OFF이고, 보호 모드로 올리는 것은 사람이 2차 확인으로 해야 한다(RealTradingGuard.java:204-206 `enterProtective`). OFF 계좌는 PANIC으로도 매도할 수 없다(ADR-006 4장 표, Q-016).
   - 매뉴얼 74행 "실전 엔진과 실험실을 다시 시작"은 첫 활성화 전(보유 없음) 절차라 지금은 위험이 없다. 7단계 매뉴얼에서는 "보유가 있는 날 실전 엔진을 재기동하면 엔진 손절 감시가 멈추고 서버 측 손절만 남는다. 재기동 직후 #/real에서 보호 모드로 올린다"를 넣을 것을 권고한다(7단계 risk-ops 작업으로 추적).

4. 확인한 나머지 주장(모두 코드와 일치): 활성화 시도 시 ACTIVATING → OFF(EngineMain.java:117-120 `BROKER_NOT_CONNECTED`, ADR-006 2.6), `hash-password`는 콘솔이 없으면 종료 코드 2(EngineMain.java:124-128)이고 비밀번호 15자 이상(OperatorPasswordHash.java:33), 5번 연속 실패 시 15분 잠금(OperatorAuthenticator.java:26-27), 설치본 이름 `autotrade-engine`·`autotrade-gateway`(각 build.gradle.kts `applicationName`), 감시는 설정만 읽고 0으로 종료(WatchdogMain.java:23-25), 링크한 문서 10개 모두 존재.

## 판정
수정 필요(1: README.md:75 "내리기(Guard OFF)"를 보호 모드 전이로 정정, 2: README.md:64 게이트웨이·실전 엔진 Ctrl+C 종료 코드 0을 실제 값(130)으로 정정)
