# risk-ops 검토: 실전 엔진 시세 통로 G1(엔진 전달 서버·받는 쪽·줄 형식과 경계 규칙, D-148)
- 대상: PR #257 / 코드 커밋 `f9c09801`, 결정 번호 변경 `ba5209c3`(문서 3줄만, 코드 무변경 확인) / main(`f085478f`) 대비 diff 전체(19개 파일, 코드 커밋 기준)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(PR 머리 `f9c09801`, 코드는 `ba5209c3`와 같음): **BUILD SUCCESSFUL**. 테스트 결과 XML 합계 2,086건, 실패 0·오류 0·건너뜀 0. 모듈별 `autotrade-marketdata` 193, `autotrade-architecture-test` 50, `autotrade-risk` 57, `autotrade-app-engine` 109건 모두 통과.
  - 새 시험만 `--rerun-tasks`로 한 번 더 실행(`EngineFeedServerTest` 18, `EngineFeedClientServerTest` 5, `EngineFeedLatencyTest` 1, `EngineFeedProtocolTest` 5, `EngineFeedBoundaryTest` 3): 통과.
  - `grep -rln enginefeed --include=*.java`(src/main): 쓰는 곳이 `autotrade-marketdata/.../enginefeed/` 6개 파일뿐. 게이트웨이·엔진·실험실 실행부와 Gradle 파일은 바뀌지 않음(diff 목록).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·`autotrade-risk`·`autotrade-app-engine`·설정 파일 변경 없음(diff 목록). `:autotrade-risk:test` 57건·`:autotrade-app-engine:test` 109건 통과 |
| A2 | 통과 | 주문 경로 코드 변경 없음. `ArchitectureTest`는 고치지 않았고(diff 목록) `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 등 기존 규칙 통과. 새 패키지에 주문 포트 의존 없음(`EngineFeedServer.java`·`EngineFeedClient.java` import 목록) |
| A3 | 해당 없음 | 시세 전달만 다룸. 주문·증권사 선택 코드 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재전송 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·보관 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 판정 경로 변경 없음. 관심 종목 줄(`protect`·`entry`)은 구독 요청일 뿐 주문 판단에 쓰이지 않음(`EngineFeedServer.java:300-323`, `InterestSink`) |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 코드 변경 없음 |
| A8 | 통과(G1 범위) | 어느 프로세스에도 연결하지 않음(사용처 grep). 서버 `publish`는 잠금 없이 큐에 넣기만 하고 예외를 삼킴(`EngineFeedServer.java:177-198`, `HandoffQueue.java:45-52`), 시험 `엔진이_읽지_않아도_publish는_기다리지_않고_…`·`publish_안의_예외는_밖으로_나가지_않는다`·`EngineFeedLatencyTest` 통과. 엔진 쪽 받는 쪽은 데몬 스레드에서 돌고 인사 시간 초과 5초(`EngineFeedClient.java:198-218`). 단, 엔진에 연결할 때(G4) 막힘 위험 2건은 아래 발견 1·2 |
| A9 | 통과(G1 범위) | 보호 시세 경로(REST 폴링)·서버 측 손절 코드 변경 없음. 큐가 차면 생존 줄을 버려 엔진이 3초 뒤 보호 폴링으로 가는 방향(보호 쪽, `EngineFeedServer.java:422-434`, 시험 `큐가_찬_동안_생존_줄은_버리고_…`), 버린 종목은 다음 이벤트 앞 `DataGap(CONSUMER_OVERFLOW)`(`EngineFeedServer.java:519-544`), 생존 줄에 `dropped` 누계. 엔진 쪽 판단은 G4라 아직 검증 대상 아님. G4 조건은 발견 3 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(`git diff … \| grep -iE "appkey\|secret\|token\|password\|account"` 결과는 시험 문자열 `secret-like` 한 건과 결정 로그 기존 행뿐). 형식 오류 메시지에 받은 원문을 넣지 않음(`EngineFeedProtocol.java:39`, `ProtocolException`) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음: `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`·기존 픽스처 변경 없음(diff 목록). 고정 값(지문·허용 목록·소유 표) 갱신 없음(`com.autotrade.marketdata`는 기존 소유 표 `ModuleOwnershipTest.java:106`에 이미 있음). 추가만 있음: `EngineFeedBoundaryTest` 규칙 5개와 위반 픽스처 4개. 위반을 실제로 잡는지 `규칙은_위반을_실제로_잡아낸다`가 확인하고, 실제 클래스가 통과하는지(모두 거절하는 오류 방지)도 확인(`EngineFeedBoundaryTest.java:101-132`). 새 동작 시험에 실패 경우 포함(형식 오류·상한 초과·인사 불일치·두 번째 연결·멈춤 끊기·소켓 파일 탈취) |
| A12 | 통과(G1 범위) | 장애 대응표·장애 테스트 8종 코드·문서 변경 없음. 실행부에 연결하지 않아 지금 동작은 바뀌지 않음. 전달 끊김 → 보호 폴링 전제(#15 symbol-gap, D-143)를 서버 쪽에서 깨지 않음(생존 줄은 앞지르지 않고 버림). 엔진 쪽 감지는 G4 |
| A13 | 해당 없음 | 시장별 분기 없음. 종목 표기는 `Market` 열거로만 읽음(`EngineFeedProtocol.java:405-415`), 시각은 `Instant`(UTC) |
| A14 | 통과 | D-148(`docs/decisions/D-148-engine-feed-g1.md`)의 결정 1~9가 코드와 일치: ② `HandoffQueue`, ③ `SourceState{state,lastRx}`(`EngineFeedProtocol.java:361-371`), ④ 생존 줄 버림·상태 줄 다음 차례(`EngineFeedServer.java:379-389,422-434`), ⑤ `lastRx`는 `DataGap` 제외(`:179-186`), ⑥ 60초 유지·`PENDING`(`:341-374,396-408`), ⑦ 16KB 거부·연결 유지(`:579-582,662-700`), ⑧ 경계 규칙, ⑨ 파일 키(`EngineFeedListener.java:166-199`). ADR-016 8장 구현 기록 추가. 결정 README 행 추가 |

## 점검표 밖 발견

모두 G1 머지를 막지 않는다. 지금은 어느 프로세스에도 연결되지 않으므로 실전 영향이 없고, 엔진에 연결하는 G4(그리고 G2) PR에서 risk-ops가 확인할 조건으로 남긴다.

1. **[G4 조건] `EngineFeedClient.interest()`가 호출 스레드를 막을 수 있다.** `writeLock` 아래 blocking `SocketChannel.write`(`EngineFeedClient.java:124-146,242-247`). 게이트웨이가 멈춰 읽지 않으면 소켓 버퍼가 차는 순간 호출한 엔진 스레드가 무기한 멈춘다. 같은 잠금을 `loop()`도 인사 보낼 때 잡는다(`:159-166`). G4에서 엔진의 계좌 큐·판단 스레드가 이 메서드를 직접 부르면 절대 규칙 6(실전 엔진은 게이트웨이 장애에 영향받지 않음)에 걸린다. G4는 전용 스레드에서 부르거나 쓰기 시간 한도를 두고, "게이트웨이가 읽지 않음" 시험을 넣어야 한다.
2. **[G4 조건] 받는 쪽에 인사 뒤 읽기 시간 한도가 없다.** 인사 뒤 `in.readLine()`은 무기한 기다린다(`EngineFeedClient.java:224`). 게이트웨이 프로세스가 소켓을 연 채 멈추면 받는 쪽은 다시 붙지 않는다. 엔진이 생존 줄 3초 없음으로 보호 폴링에 가는 것(D-143)은 G4 몫이지만, 그 뒤 "끊고 다시 붙기"를 할 방법이 `close()`(받는 쪽 전체 종료)뿐이다. G4에서 생존 줄 끊김 시 현재 연결만 끊는 수단(또는 읽기 시간 한도)을 넣고 시험해야 한다.
3. **[G4 조건] 넘침 공백 줄은 그 종목의 다음 이벤트가 와야 나간다.** `Slot.since`는 다음 `offer` 때만 `DataGap`으로 바뀐다(`EngineFeedServer.java:519-533`). 버린 뒤 그 종목에 이벤트가 더 오지 않으면 엔진은 그 종목 공백을 줄로 받지 못한다. 또 버림(`queue.offer` 실패)과 `slot.since` 기록 사이에 다른 수신 스레드의 같은 종목 이벤트가 공백 표시 없이 먼저 들어갈 수 있다(`:534-542`, 공백은 그 다음 이벤트 앞에 나감). 엔진은 공백 줄만 믿지 말고 생존 줄 `dropped` 증가와 이벤트 `seq`를 함께 보고 해당 연결 전체 종목을 의심(보호 폴링)해야 한다. G4 설계·시험에 넣는다.
4. **[참고] 인사 전 연결이 자리를 5초 차지한다.** 받는 쪽은 하나라 인사 전 연결도 `current`가 되어(`EngineFeedServer.java:283-291`) 그동안 진짜 엔진의 연결은 인사 없이 닫힌다. 엔진은 1초 뒤 다시 붙으므로 최대 약 5초 늦어진다. 같은 OS 사용자 프로세스 위험(ADR-016 4장) 범위라 G1에서 고칠 것은 아니고, `EngineFeedListener.isListening`의 탐침 연결도 곧바로 닫혀 영향이 짧다.
5. **[참고] 게이트웨이 수신 스레드가 서버 잠금을 잡는 곳.** `publish`는 잠금이 없지만 `sourceState`·`assignment`는 `synchronized(this)`를 잡는다(`EngineFeedServer.java:201-232`). 잠금 안 작업은 상태 줄 만들기·큐 넣기뿐이고 `InterestSink.apply`는 잠금 밖이라 지금은 짧다. G2에서 게이트웨이 소스 콜백에 연결할 때 이 메서드들을 수신 스레드에서 부르는지와 지연을 확인한다.
6. **[참고] 검증하지 못한 것.** 사용자 PC(Windows)의 소켓 권한·파일 키·`Thread.sleep(0, 500_000)` 실제 간격과 지연 기준(D-148 위험, 7단계 절차서 I1). 컨테이너 Linux에서만 시험했다. G1은 실행부 연결이 없으므로 이번 판정에는 영향이 없다.

## 판정
승인

## 재검토 2026-11-02
- 대상: PR #257 / 머리 `c2530f00`(독립 QA 수정 필요 반영 커밋, 그 앞 `ce8650c4`는 main 합치기) / main 대비 diff 전체(`git diff origin/main...HEAD`, 24개 파일). 이번에 바뀐 코드는 `EngineFeedServer.java`·`EngineFeedClient.java`·`EngineFeedBoundaryTest.java`·새 위반 예제 `EngineOpensBrokerStream.java`와 시험 2파일, 문서는 D-148(결정 10~15)·ADR-016 8장·결정 README
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon --console=plain`(머리 `c2530f00`): **BUILD SUCCESSFUL in 5m 14s, 138 작업 모두 실행**. 시험 결과 XML 합계 2,111건, 실패·오류·건너뜀 0. `autotrade-marketdata` 198, `autotrade-architecture-test` 50, `autotrade-risk` 57, `autotrade-app-engine` 109건. 지연 시험 출력 `publish p50=303ns p99=1133ns p99.9=5083ns 최대=50045ns, 콜백→해석 p50=329µs p99=1508µs 최대=15991µs (25600건)`(기준 안).
  - 고친 것을 되돌려 새 시험이 실제로 잡는지 확인(작업 트리에서만 바꾸고 원복, `git status` 깨끗 확인): `slots`를 늘 `new Slot()`으로, `sendStatusLocked`의 `c.pendingRejection` 승계를 뺀 코드로 `:autotrade-marketdata:test --tests '*EngineFeedServerTest'` → **21건 중 2건 실패**: `넘침_뒤_관심이_바뀌어도_남는_종목은_다시_읽을_때_공백이_먼저_온다`, `큐가_차서_밀린_상태_줄도_거부_이유를_싣는다`. 원복 뒤 전체 빌드는 위와 같이 통과.
  - `git diff origin/main...HEAD | grep -inE "appkey|appsecret|password|accountno|bearer "`: 앞 검토 원문의 A10 줄 한 건뿐.
  - `grep -rn "marketData(" autotrade-app-engine/src/main`: 없음. 엔진 Gradle 의존에 증권사 플러그인 구현 모듈 없음(`autotrade-app-engine/build.gradle.kts:19-28`).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·`autotrade-risk`·엔진 실행부·설정 변경 없음(diff 목록). `:autotrade-risk:test` 57건·`:autotrade-app-engine:test` 109건 통과 |
| A2 | 통과 | 주문 경로·`ArchitectureTest` 변경 없음. 새 코드에 주문 포트 의존 없음(`EngineFeedServer.java`·`EngineFeedClient.java` import), 기존 규칙 통과 |
| A3 | 해당 없음 | 시세 전달만. 주문·증권사 선택 코드 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재전송 경로 없음 |
| A5 | 해당 없음 | 멱등 키 코드 없음 |
| A6 | 해당 없음 | 관심 줄은 구독 요청일 뿐 주문 판단에 쓰이지 않음(`InterestSink`) |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 무변경 |
| A8 | 통과(G1 범위) | 실행부 미연결(사용처는 `marketdata.enginefeed` 안뿐). `publish`는 여전히 잠금 없이 `route` 읽고 `offer`(`EngineFeedServer.java:180-198`), 지연 시험 기준 안. 새 `applyLock`은 연결 읽기 스레드·타이머만 잡고 수신 스레드는 잡지 않음(`:330-343`). 받는 쪽 `interest()`의 막힘·읽기 시간 한도 없음(앞 발견 1·2)은 그대로 G4 조건. 새 G2 조건은 아래 재검토 발견 R1 |
| A9 | 통과(G1 범위) | QA 발견 1 고침 확인: 같은 연결이면 남는 종목은 앞 `Slot`을 이어 씀(`EngineFeedServer.java:316`, `slots` `:359-364`), `Slot`은 연결의 큐와 짝이라(`Route(connection, slots)` `:741`) 다른 연결로 넘어가지 않음. 시험 `넘침_뒤_관심이_바뀌어도_남는_종목은_다시_읽을_때_공백이_먼저_온다` 통과, 고치기 전 코드에서 실패(위 직접 확인). 빠졌다 다시 든 종목을 새 칸으로 두는 판단(D-148 결정 10)은 받아들인다: 관심 밖 구간은 엔진에 원래 안 오므로 공백이 아니다. 단 남는 위험은 R2. 보호 폴링·서버 측 손절 코드 무변경 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(위 grep). 새 오류 메시지는 상한 바이트 수만 담음(`EngineFeedClient.java` `BoundedLines.readLine`), 로그는 예외 클래스 이름만(`applyInOrder`) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. `EngineFeedBoundaryTest`에 규칙 `ENGINE_OPENS_NO_MARKET_STREAM` 추가만(`EngineFeedBoundaryTest.java:95-100`), 실제 클래스 검사(`실전_엔진은_게이트웨이_부품을_쓰지_않는다`)와 위반 예제 `EngineOpensBrokerStream`을 잡고 허용(`pollingQuotes`)은 잡지 않는지(`규칙은_위반을_실제로_잡아낸다`) 둘 다 단언, 통과. 고정 값(지문·허용 목록·소유 표) 갱신 없음, `com.autotrade.app.engine.archfixture`는 기존 예제 패키지. 기존 시험 2건 변경(`publish_안의_예외는…`, `이벤트_줄_순번은…`)은 기다리는 상태 줄을 rev 0 → rev 1로 좁힌 것으로 단언을 약하게 하지 않음(diff). 새 동작마다 실패 경우 시험 있음(넘침+관심 변경, 밀린 거부, 늦은 apply 버림, 바이트 상한 초과, 나뉜 줄, `interest()` 빈 값). Q2 경합 재현 시험이 없음은 D-148 검증 절에 스스로 적음 |
| A12 | 통과(G1 범위) | 장애표·장애 테스트 문서·코드 무변경, 실행부 미연결이라 지금 동작 불변. 넘침 공백 계약(#15 symbol-gap 입력)이 관심 변경 뒤에도 지켜짐(A9). 거부 이유가 밀려도 사라지지 않아 ADR-016 2.8 "관심 줄 거부: 긴급" 알림 입력이 남음(`sendStatusLocked` `:404-418`, 시험 `큐가_차서_밀린_상태_줄도_거부_이유를_싣는다`, 고치기 전 실패 확인) |
| A13 | 해당 없음 | 시장별 분기 없음. 위반 예제의 `Market.KRX`는 규칙 고정용 |
| A14 | 통과 | D-148 결정 10~15가 코드와 일치: 10 `slots(…, before)`, 11 `pendingRejection`(`:537`, 승계 `:409-415`, 받아들인 관심에서 지움 `:312`), 12 `applyInOrder`(세대 확인 뒤 버림), 13 `OptionalLong`(`EngineFeedClient.interest`), 14 `BoundedLines`(`MAX_LINE` 바이트 상한), 15 `ENGINE_OPENS_NO_MARKET_STREAM`. 상태 줄 "결정 1~15", ADR-016 8장 구현 기록, 결정 README ⑨ 갱신. 결정 README는 main과 합쳐 D-147·D-148 두 행이 함께 있음(QA R1) |

## 재검토 점검표 밖 발견

모두 이번 머지를 막지 않는다(어느 프로세스에도 연결되지 않아 실전 영향 없음). 연결하는 G2·G4 PR에서 risk-ops가 확인한다. 앞 검토 발견 1~3(G4 조건)은 이번 커밋이 다루지 않았고 그대로 남는다.

- **R1 [G2 조건] `applyLock` 아래에서 `sink.apply`가 느리면 타이머가 기다린다.** `expire`는 단일 타이머 스레드(`EngineFeedServer.java:110-111`)에서 돌고, 같은 타이머가 생존 줄(`tick`)·소켓 확인·멈춤 확인을 한다(`:150-154`). 만료와 새 관심이 겹쳐 연결 읽기 스레드가 `applyLock`을 잡고 느린 `sink.apply`를 하는 동안 `expire`의 `applyInOrder`가 그 잠금을 기다리면 생존 줄이 그만큼 멈춘다. 엔진은 생존 줄 3초 없음으로 보호 폴링으로 가므로 안전 쪽이고, 고치기 전에도 `expire`가 타이머에서 `sink.apply`를 직접 불렀으므로 새로 생긴 위험은 좁다. G2에서 구독 계획을 붙일 때 `sink.apply` 소요 상한(또는 D-148 결정 12의 되돌릴 조건대로 실행기)과 "만료 직전 재접속" 시험을 함께 넣는다.
- **R2 [G4 조건] 빠졌다 곧 다시 든 종목은 앞 넘침과 놓친 `STATUS`를 모른다.** 결정 10대로 다시 든 종목은 새 칸이라, 빠지기 전 버린 체결·`STATUS`(VI·거래정지)에 대한 공백 줄이 없다. 관심 밖 구간을 공백으로 보지 않는 것은 맞지만, G4 엔진은 "다시 든 종목"을 처음 받는 종목과 같이 다뤄 상태(VI 등)를 REST로 다시 확인한 뒤 진입하고, 보호 종목이면 `served` 확인 전까지 보호 폴링을 유지해야 한다.
- **R3 [참고] `ENGINE_OPENS_NO_MARKET_STREAM`의 범위.** 규칙은 `com.autotrade.app.engine..`의 직접 의존·`BrokerPlugin.marketData` 호출만 본다. 구현 플러그인 타입으로 부르는 길은 엔진이 구현 모듈에 의존하지 않아 지금 없고(엔진 `build.gradle.kts:19-28`), 얻은 소스를 쓰면 `MarketDataSource` 의존으로 잡힌다. 엔진이 쓰는 순수 모듈(`autotrade-strategy`·`autotrade-risk`)을 거쳐 `MarketDataSource`를 구독하는 길은 이 규칙 밖이다. G4에서 엔진이 순수 모듈에 시세 소스를 넘기는지 확인한다.

## 판정
승인
