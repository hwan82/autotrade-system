# QA 검토: 실전 엔진 시세 통로 G1: 엔진 전달 서버·받는 쪽·줄 형식과 경계 규칙(D-148) (PR #257 QA)
- 대상: PR #257 / 커밋 `37c3e0e1` (브랜치 `claude/project-thread-in425x`, 검토 대상 코드 커밋 `f9c09801`) / main 대비 diff 전체(merge-base = main `f085478f`, 22파일 +3,296: `marketdata.enginefeed` 운영 3파일·`enginefeed.server` 운영 3파일과 시험 5파일, 보호 테스트 새 파일 `EngineFeedBoundaryTest`와 위반 예제 4개, ADR-016 8장, D-148, 결정 색인, `pending.md`, 검토 원문 2)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --console=plain` (헤드 `37c3e0e1`): 1회째 **BUILD SUCCESSFUL in 3m 25s, 138 작업 모두 실행**, 미러 없이. 시험 결과 XML 합계 2,086건, 실패·오류·건너뜀 0. `:autotrade-marketdata` 193건, `:autotrade-architecture-test` 50건, `:autotrade-app-engine` 109건, `:autotrade-risk` 57건. 지연 시험 출력: `publish p50=362ns p99=1114ns p99.9=4105ns 최대=163566ns, 콜백→해석 p50=395µs p99=5290µs 최대=36392µs (25600건)`(기준 p99.9 50µs, p99 10ms, 최대 200ms 안).
  - PR 헤드 CI: GitHub check run 0건(실행 기록 없음). `[추론]` main과 충돌해 PR 병합 커밋이 없어 `pull_request` 실행이 돌지 않은 것으로 보인다. 위 직접 빌드로 B1을 판정했다.
  - `git merge-tree --write-tree origin/main HEAD`(main `17193571`): **`docs/decisions/README.md` 충돌**(#256 D-147 행과 이 PR D-148 행, 줄 추가끼리). 임시 작업 트리에서 main에 이 PR을 합치고(README는 PR 쪽) `:autotrade-marketdata:test :autotrade-architecture-test:test :autotrade-app-gateway:test` → BUILD SUCCESSFUL.
  - QA 임시 시험 `QaTmp257Test`(2건, `enginefeed.server` 시험 폴더에 넣어 돌린 뒤 지움, `git status` 깨끗 확인): 아래 발견 1. 출력으로 재현.
  - `grep -rn enginefeed`(운영 코드): 쓰는 곳은 `autotrade-marketdata/.../enginefeed/` 안뿐. 게이트웨이·엔진·실험실 실행부 미연결 확인.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard`·`autotrade-risk`·엔진 실행부 무변경(diff 목록). Guard 시험 통과 |
| A2 | 통과 | 주문 경로·`ArchitectureTest` 무변경. 새 패키지에 주문 포트 의존 없음(import 목록), 기존 규칙 통과 |
| A3 | 해당 없음 | 시세 전달만. 주문·증권사 선택 없음 |
| A4 | 해당 없음 | 주문 타임아웃 경로 없음 |
| A5 | 해당 없음 | 멱등 키 없음 |
| A6 | 해당 없음 | 관심 줄은 구독 요청이고 주문 판단에 쓰이지 않음(`InterestSink`) |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 무변경 |
| A8 | 통과(G1 범위) | 실행부 미연결. `publish`는 잠금 없이 `HandoffQueue`(CAS, 깨우지 않음)에 넣고 예외를 삼킴(EngineFeedServer.java `publish`), 시험 `엔진이_읽지_않아도_publish는_기다리지_않고…`·`publish_안의_예외는_밖으로_나가지_않는다` 통과, 지연 출력 기준 안. 받는 쪽 `interest()`의 막힘·읽기 시간 한도 없음은 risk-ops 발견 1·2와 같은 판단(G4 조건) |
| A9 | **실패** | ADR-016 2.5·D-148 결정 4는 "큐가 차면 그 줄을 버리고 종목마다 처음 버린 시각을 기억했다가 다음에 넣을 수 있을 때 `GAP(CONSUMER_OVERFLOW, ENGINE-FEED)`를 먼저 넣는다"이다. 엔진은 이 줄로 그 종목을 공백(보호 폴링)으로 다룬다(2.6 "공백" 행). 그런데 엔진이 관심을 바꾸면(같은 종목을 계속 넣어도) 서버가 종목 칸을 새로 만들어 기억한 시각을 잃고, 그 종목의 다음 이벤트가 공백 줄 없이 나간다. 아래 **발견 1**(재현) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. 로그는 소켓 파일 이름·예외 클래스 이름·부팅 표지만, 형식 오류 메시지에 받은 원문 없음(`ProtocolException`은 이유 이름만) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`·기존 예제 무변경, 고정 값 갱신 없음. 새 보호 테스트 `EngineFeedBoundaryTest` 규칙 5개와 위반 예제 4개, 예제를 실제로 잡는지와 실제 클래스가 통과하는지 둘 다 단언(통과). 규칙이 덮지 않는 길은 Q3 |
| A12 | 통과(G1 범위) | 장애표·장애 테스트 문서 무변경, 실행부 미연결이라 지금 동작 불변. 장애표 #1·#2(3초)·#15를 엔진이 판단할 입력(생존 줄 1초, 소스별 `lastRx`, 상태 줄)은 시험(`생존_줄은_주기마다_오고…`, `소스가_연결된_채_시세만_멈추면_lastRx가_멈춘다`)으로 확인. 단 공백 줄 계약은 발견 1 |
| A13 | 해당 없음 | 시장별 분기 없음. 종목은 `Market` 열거, 시각은 `Instant`(UTC) |
| A14 | **실패** | D-148 결정 4·ADR-016 8장은 넘침 공백 계약을 지킨다고 적고, 5장 시험 5를 G1 시험으로 넣었다고 하나, 관심 변경 뒤에는 그 계약이 깨진다(발견 1). risk-ops 발견 3("공백 줄은 다음 이벤트가 와야 나간다")은 이 경우를 다루지 않는다 |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(2,086건, 실패 0), main 합친 트리의 관련 3개 모듈 시험도 통과 |
| B6 | 통과(발견 1 시험 없음) | 새 동작마다 시험 있음(줄 형식 5, 서버 18, 받는 쪽 5, 지연 1, 경계 3), 건너뜀·삭제 없음. 관심 변경과 넘침이 겹치는 경우 시험이 없다(발견 1) |

## 발견 1 (A9·A14): 관심을 바꾸면 그 전에 버린 종목의 넘침 공백(`GAP CONSUMER_OVERFLOW`)이 사라진다

`onInterest`는 관심을 받을 때마다 `route = new Route(c, slots(i.all()))`로 모든 종목 칸(`Slot`)을 새로 만든다(EngineFeedServer.java:311, `slots` :334). 넘침 시각은 이 칸의 `since`에만 있다(`offer`, :520·:539). 그래서 큐가 차서 종목 A의 줄을 버린 뒤(옛 칸 `since` 기록) 엔진이 새 관심(A를 그대로 포함)을 보내면, 새 칸은 `since = null`이고 A의 다음 이벤트는 공백 줄 없이 나간다. 버린 구간은 엔진에 종목 단위로 전혀 드러나지 않는다. 이벤트 `seq`는 쓰기 스레드가 매기므로 빈틈도 없다.

- **재현**(QA 임시 시험, 기존 시험 `엔진이_읽지_않아도_publish는…`과 같은 준비: 큐 50, 엔진이 읽지 않는 동안 A·B에 20만 건 `publish`):
  - 대조(관심 그대로): 엔진이 읽기 시작해 큐를 비운 뒤 A·B 한 건씩 → `{"src":"ENGINE-FEED",…,"t":"GAP","sym":"KRX:005930",…,"why":"CONSUMER_OVERFLOW"}` 다음 A 이벤트, B도 같음(`QA-257-0`).
  - 관심 변경(`rev 2`, protect `{A, B, C}`, 버린 뒤·읽기 전): 같은 순서에서 `{"seq":1239,"src":"KIWOOM-WS",…,"sym":"KRX:005930",…}`, `{"seq":1240,…,"sym":"KRX:000660",…}`만 오고 **공백 줄 없음**(`QA-257-1`).
- **언제 생기나** `[추론]`: ADR-016 2.6은 "매수 주문 전에 그 종목을 `protect`에 먼저 넣는다"이므로 엔진은 매수·청산·미체결 변화 때마다 관심을 바꾼다. 큐가 차는 것은 엔진이 밀릴 때(장 시작·급변 구간)라 관심 변경과 겹치기 쉽다.
- **영향** `[추론]`: G4 엔진은 공백 줄을 받은 종목을 "공백"으로 보고 다음 이벤트까지 보호 폴링·신규 진입 금지를 한다(2.6). 공백 줄이 없으면 버린 줄(체결·호가·`STATUS`(VI·거래정지 등)) 없이 그 종목을 정상 실시간으로 이어 본다. 생존 줄 `dropped`는 늘지만 어느 종목인지는 모른다. 엔진이 `dropped` 증가를 전체 의심으로 다루면(risk-ops 발견 3 권고) 덮이지만, 그것은 G4 쪽 권고이고 G1 서버의 종목별 공백 계약(D-148 결정 4, ADR-016 5장 시험 5)이 깨진 것은 그대로다.
- **고칠 것(최소)**: (1) 관심을 바꿀 때 이전 `Route`가 같은 연결이면 계속 남는 종목의 `Slot`을 그대로 넘긴다(새 종목만 새 칸). (2) 시험: 넘침 뒤 관심 변경(남는 종목 포함) → 다시 읽으면 남는 종목마다 `CONSUMER_OVERFLOW` 공백이 다음 이벤트 앞에 온다. (3) 빠졌다가 다시 들어온 종목은 그 사이 이벤트를 원래 받지 않으므로 지금처럼 새 칸이어도 된다(관심 밖 구간은 공백이 아님). 이 판단을 D-148에 한 줄 적는다.

## 점검표 밖 위험 시나리오

- **S1 관심 변경 + 넘침**: 발견 1.
- **S2 `rejected` 상태 줄이 큐가 찬 때 사라짐**: 관심 줄을 거부한 순간 큐가 차 있으면 `sendStatusLocked(why)`가 넣지 못하고 `statusPending`만 세운다(:384-387). 다음 생존 줄 차례에 다시 넣을 때는 `sendStatusLocked(null)`이라(:428) `rejected`가 `null`로 나간다. 엔진은 거부를 모르고(2.8 "관심 줄 거부: 긴급" 알림이 안 남), 보낸 `rev`보다 낮은 상태 줄만 받아 새 종목은 `served` 확인이 안 되므로 매수 보류·보호 폴링(안전 쪽)으로는 간다. 알림 누락이다(Q1).
- **S3 관심 유지 만료와 새 관심의 순서**: `expire`는 잠금 안에서 세대를 확인하고 `applied = null`로 바꾼 뒤 잠금 **밖**에서 `sink.apply(빈 관심)`을 부르고(:365-370), `onInterest`도 잠금 밖에서 `sink.apply(새 관심)`을 부른다(:314). 두 호출은 서로 다른 스레드(타이머·연결 읽기)라 순서가 보장되지 않는다. 엔진이 끊긴 지 정확히 60초쯤 다시 붙어 관심을 보내면 구독 계획이 "새 관심 → 빈 관심" 순으로 받아 보호 종목이 구독에서 빠진 채로 남을 수 있다 `[추론: 코드 경로, 재현 안 함]`. 서버 쪽 보기(`applied`)는 새 관심이라 상태 줄은 그 종목을 `PENDING`(배정 없음)으로 내므로 엔진은 보호 폴링으로 간다(안전 쪽). 다만 엔진은 관심이 바뀌기 전까지 다시 보내지 않으므로 그 상태가 이어진다(Q2). 같은 연결이 바뀌는 경계(앞 연결의 늦은 `sink.apply`)도 같은 모양이다.
- **S4 엔진이 플러그인으로 시세 WebSocket을 엶**: 새 규칙 `ENGINE_IS_NOT_A_GATEWAY`는 `marketdata.live` 패키지 클래스에 대한 **직접** 의존만 본다. 실전 엔진은 `autotrade-broker-api`에 의존하고(엔진 build.gradle.kts), `BrokerPlugin.marketData(Market)`이 `MarketDataSource`(`com.autotrade.marketdata`, 규칙 밖)를 주며 한국투자·키움 플러그인은 그것을 `StreamingMarketDataSource`로 만든다(KisPlugin.java `create`). 그래서 엔진이 `plugin.marketData(KRX).get().subscribe(…)`로 증권사 WebSocket을 열어도 규칙에 걸리지 않는다. ADR-016 2.1 "엔진은 시세용 WebSocket을 직접 열지 않는다"를 지키는 기계 검사가 없다(Q3). 지금 엔진에 그런 코드는 없다(grep).
- **S5 받는 쪽 `interest()` 반환 0**: 연결이 없거나 쓰기 실패면 0을 돌려준다(EngineFeedClient.java:131·137). 엔진이 이 값을 "기다릴 rev"로 그대로 쓰면 `status.rev >= 0`이 늘 참이라 rev 0 상태 줄(앞 연결의 유지된 관심 배정)도 확인으로 받아들인다. ADR-016 5장 시험 16이 막으려는 경우와 같은 모양이다. G4 몫이지만 G1 API가 그 실수를 쉽게 만든다(Q4).
- **S6 한 소스 콜백 안 공백 순서(참고)**: risk-ops 발견 3의 "버림과 `since` 기록 사이 다른 수신 스레드 이벤트가 먼저 들어감"은 코드로 확인했다(:534-542). 공백 줄이 그 이벤트 **뒤**에 나가므로 엔진은 공백을 늦게 안다. 보호 쪽 영향은 `dropped`와 함께 G4에서 다룬다는 risk-ops 판단에 동의한다.

## 권고 (수정 요구 아님)

- **Q1**: 상태 줄을 다시 넣을 때 넣지 못한 `rejected` 이유를 함께 보존한다(예: `pendingRejection` 칸). 시험: 큐가 찬 상태에서 거부 → 다음 차례 상태 줄에 `rejected`가 실림.
- **Q2**: `sink.apply` 호출을 한 줄로 세운다(예: 서버의 단일 실행기로 넘기고 세대 번호를 함께 넘겨 늦은 호출은 버림). G2에서 구독 계획을 붙이기 전에 시험(만료 직전 재접속)과 함께.
- **Q3**: 경계 규칙에 "실전 엔진은 `MarketDataSource.subscribe`·`BrokerPlugin.marketData`를 부르지 않는다"(또는 `LiveMarketDataSource`·`MarketDataSource` 의존 금지, 보호 폴링 `marketdata.polling`은 허용)를 더하고 위반 예제로 고정한다. 보호 테스트 변경이므로 실전 관련 PR로.
- **Q4**: `EngineFeedClient.interest`의 "보내지 못함"을 0이 아닌 값(예: `OptionalLong.empty()` 또는 -1)으로 바꾸거나, 받는 쪽이 "이 연결에서 보낸 rev" 확인을 직접 제공해 G4가 rev 0을 확인으로 쓰지 못하게 한다.
- **Q5**: architect N1(받는 쪽 줄을 다 읽은 뒤 길이 검사, UTF-16 글자 수)에 동의한다. 서버 쪽 `LineReader`처럼 바이트 상한 읽기로.
- **Q6**: `EngineFeedServer` 쓰기 스레드 Javadoc 겹침(architect N6) 정리.

## 그 밖
- **R1**: main(#256 D-147)과 `docs/decisions/README.md`가 충돌한다(줄 추가끼리, 두 줄 모두 남기면 됨). 합친 트리 시험은 위에서 통과했다. 머지 전에 main을 합쳐야 하고, 그 뒤 CI가 도는지 확인한다.
- architect·risk-ops 원문 판정은 둘 다 승인이며, 발견 1은 두 원문에 없다.

## 판정
수정 필요(발견 1: 관심 종목을 바꾸면 그 전에 큐 넘침으로 버린 종목의 `CONSUMER_OVERFLOW` 공백 줄이 사라져, 엔진이 버린 구간을 종목 단위로 알 수 없음. A9·A14 실패, 재현함). 고친 뒤 실패 경우 시험을 넣고 재검토를 요청한다. 실전 관련 PR이므로 머지는 사용자가 직접 한다.

## 재검토 2026-11-02
- 대상: PR #257 / 헤드 `240f4b4d`(브랜치 `claude/project-thread-in425x`). 1차 판정 뒤 커밋: `ce8650c4`(main 합치기), `c2530f00`(QA 수정 필요 반영, 코드), `7a071ff6`·`f6aa5a38`(architect·risk-ops 재검토 원문), `240f4b4d`(main 합치기·pending·처리 기록) / main 대비 diff 전체 다시 읽음(merge-base = main `12e88baa`, 25파일 +3,717: 운영 6파일·시험 5파일, 보호 테스트 `EngineFeedBoundaryTest`와 위반 예제 5개, ADR-016 8장, D-148, 결정 색인, `pending.md`, `qa-out-status.md`, 검토 원문 3)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --console=plain` (헤드 `240f4b4d`): Maven Central 429로 의존성 받기 실패가 이어진 뒤 18회째 **BUILD SUCCESSFUL in 3m 56s, 138 작업 모두 실행**, 미러 없이. 시험 결과 XML 합계 2,111건, 실패·오류·건너뜀 0. `:autotrade-marketdata` 198건, `:autotrade-architecture-test` 50건, `:autotrade-app-engine` 109건, `:autotrade-risk` 57건. 지연 시험 출력: `publish p50=326ns p99=1458ns p99.9=6186ns 최대=3042346ns, 콜백→해석 p50=400µs p99=1665µs 최대=8857µs (25600건)`(기준 p99.9 50µs, p99 10ms, 최대 200ms 안).
  - PR 헤드 CI(`240f4b4d`, 실행 41369948229): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary`·`Lab screens (Playwright)` 4건 모두 success. 1차 때 CI 0건 문제는 풀림.
  - `git merge --no-commit` (지금 main `52d5d365`, #248·#259 머지 뒤): **`docs/qa/pending.md`·`docs/qa-out-status.md` 충돌**(둘 다 표 줄 추가끼리, 코드 충돌 없음). 임시 작업 트리에서 양쪽 줄을 다 남기고 `:autotrade-marketdata:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-gateway:test` → BUILD SUCCESSFUL. 작업 트리는 지움.
  - QA 임시 시험 3건(`EngineFeedServerTest`에 붙여 돌린 뒤 되돌림, `git status` 깨끗 확인). 준비는 1차 재현과 같음(큐 50, 엔진이 읽지 않는 동안 A·B에 20만 건, 관심 1 = protect `{A, B}`), 관심을 바꾼 뒤 읽기 시작해 A·B에 새 이벤트:
    - `QA-257R-1` A를 protect → entry로 옮김(`rev 2` protect `{B}` entry `{A}`): `[KRX:000660:CONSUMER_OVERFLOW, KRX:005930:CONSUMER_OVERFLOW]` 통과.
    - `QA-257R-2` 관심을 세 번 연달아 바꿈(`{A,B,C}` → protect `{A}` entry `{B}` → `{A,B}`): A·B 모두 공백 통과.
    - `QA-257R-3` B를 뺐다가 다시 넣음(`{A}` → `{A,B}`): A만 공백, B 없음(D-148 결정 10의 의도대로) 통과.
  - 되돌림 시험: `slots`를 `m.put(s, new Slot())`(고치기 전)로 바꾸면 위 3건 모두 실패(`QA-257R-1 []`, `QA-257R-2 []`, `QA-257R-3 []`)하고, 개발 쪽 새 시험 `넘침_뒤_관심이_바뀌어도_남는_종목은_다시_읽을_때_공백이_먼저_온다`도 같이 돈다. 원복 뒤 `git status` 깨끗.
  - `git diff origin/main...HEAD | grep -inE "appkey|appsecret|password|accountno|bearer "`: 검토 원문 안의 A10 설명 줄 두 건뿐.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard`·`autotrade-risk`·엔진 실행부 무변경(diff 목록). Guard 시험 포함 `:autotrade-risk` 57건·`:autotrade-app-engine` 109건 통과 |
| A2 | 통과 | 주문 경로·`ArchitectureTest` 무변경. 새 코드(`applyInOrder`·`BoundedLines`·`pendingRejection`)에 주문 포트 의존 없음(import), 기존 규칙 통과 |
| A3 | 해당 없음 | 시세 전달만. 주문·증권사 선택 없음 |
| A4 | 해당 없음 | 주문 타임아웃 경로 없음 |
| A5 | 해당 없음 | 멱등 키 없음 |
| A6 | 해당 없음 | 관심 줄은 구독 요청이고 주문 판단에 쓰이지 않음(`InterestSink`) |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 무변경 |
| A8 | 통과(G1 범위) | 실행부 미연결(`grep -rn enginefeed` 운영 코드 사용처는 `marketdata.enginefeed` 안뿐). `publish`는 여전히 잠금 없이 `route`를 읽고 `offer`(EngineFeedServer.java:180-198), 새 `applyLock`(:330)은 연결 읽기 스레드·타이머만 잡는다. 지연 출력 기준 안. 타이머가 `applyLock`을 기다리며 생존 줄이 밀릴 수 있는 점은 risk-ops R1·architect M1과 같은 판단(G2 조건, 엔진 쪽은 3초 무생존 → 보호 폴링으로 안전 쪽) |
| A9 | 통과 | 1차 발견 1 해소. 같은 연결이면 남는 종목은 앞 `Slot`을 이어 씀(EngineFeedServer.java:316·:361). 위 QA 임시 시험 3건(옮김·연달아 변경·뺐다 다시 넣음)과 개발 쪽 새 시험 통과, 고치기 전 코드로 되돌리면 모두 실패(직접 확인). 연결이 바뀌면 `route = null`(:373-374) 뒤 새 칸이라 앞 연결 칸이 넘어가지 않음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(위 grep). 새 오류 메시지는 상한 바이트 수만(`BoundedLines`, EngineFeedClient.java:272-274), 로그는 예외 클래스 이름만(`applyInOrder`) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. `EngineFeedBoundaryTest`에 규칙 `ENGINE_OPENS_NO_MARKET_STREAM`(:95) 추가와 위반 예제 `EngineOpensBrokerStream` 추가뿐, 실제 클래스 통과와 예제 위반 검출(`marketData`·`MarketDataSource` 잡고 `pollingQuotes`는 안 잡음) 둘 다 단언, 통과. 고정 값(지문·허용 목록·소유 표) 갱신 없음. 기존 시험 2건 변경은 기다리는 상태 줄을 rev 0 → rev 1로 좁힌 것이라 단언 약화가 아님(diff) |
| A12 | 통과(G1 범위) | 장애표·장애 테스트 문서 무변경, 실행부 미연결. 장애표 #15 입력인 종목별 넘침 공백이 관심 변경 뒤에도 남음(A9). 밀린 상태 줄도 거부 이유를 실어(:409-415, 시험 `큐가_차서_밀린_상태_줄도_거부_이유를_싣는다`) ADR-016 2.8 "관심 줄 거부: 긴급" 알림 입력이 남음(1차 S2 해소) |
| A13 | 해당 없음 | 시장별 분기 없음. 위반 예제의 `Market.KRX`는 규칙 고정용 |
| A14 | 통과 | D-148 결정 10~15가 코드와 일치(10 `slots(…, before)`, 11 `pendingRejection`, 12 `applyInOrder` 세대 확인, 13 `OptionalLong`(EngineFeedClient.java:124), 14 `BoundedLines`, 15 `ENGINE_OPENS_NO_MARKET_STREAM`). 검증 절이 Q2 경합 재현 시험 없음을 스스로 적음. ADR-016 8장·결정 색인 ⑨ 갱신 |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(2,111건, 실패 0), PR 헤드 CI 4건 success, 지금 main과 합친 트리의 관련 4개 모듈 시험 통과 |
| B6 | 통과 | 1차 발견 1과 권고 Q1·Q2·Q4·Q5마다 시험 추가(넘침+관심 변경, 밀린 거부, 늦은 apply 버림, `interest()` 빈 값, 바이트 상한 초과·나뉜 줄), 건너뜀·삭제 없음. 발견 1 시험은 고치기 전 코드에서 실패(직접 확인). Q2의 만료·재접속 경합 재현 시험은 없고 D-148이 G2로 넘김(아래 Q8) |

### 1차 발견·권고 처리
- **발견 1**: 해소(A9).
- **Q1**(밀린 상태 줄 거부 이유): 해소. 받아들인 관심 뒤에는 지움(:312).
- **Q2**(`sink.apply` 순서): 해소(G1 범위). 세대를 `applyLock` 안에서 다시 보고 늦은 호출을 버린다. 최신 세대를 정한 스레드는 반드시 자기 `applyInOrder`를 부르므로 마지막 관심이 빠지는 경우는 없다 `[추론: 코드 경로]`. 경합 재현 시험은 G2(Q8).
- **Q3**(엔진의 플러그인 시세 소스): 해소. 남는 범위는 risk-ops R3(순수 모듈을 거친 구독)과 같은 판단, G4에서 확인.
- **Q4**: 해소(`OptionalLong.empty()`). **Q5**: 해소(바이트 상한 읽기, 나뉜 줄 시험). **Q6**: 쓰기 스레드 Javadoc 겹침은 해소. 단 아래 Q9.
- **R1**(main 충돌): `ce8650c4`·`240f4b4d`로 해소됐으나, 그 뒤 main이 `52d5d365`로 나아가 새로 충돌(아래 R2).

### 점검표 밖 위험 시나리오(재검토)
- **S7 넘침 뒤 보호 → 진입으로 옮김**: 남는 종목 판정이 `i.all()`이라 protect·entry 사이 이동에도 칸이 이어진다(`QA-257R-1` 통과).
- **S8 넘침 뒤 관심을 짧은 간격으로 여러 번 바꿈**: 중간 관심에서 잠깐 빠지지 않는 한 칸이 이어진다(`QA-257R-2` 통과). 중간에 한 번이라도 빠지면 S9.
- **S9 넘침 뒤 뺐다가 곧 다시 넣은 종목**: 공백 줄이 없다(`QA-257R-3`, D-148 결정 10 의도). 빼기 전 큐 넘침으로 버린 `STATUS`(VI·거래정지)를 엔진은 끝내 모른다. 새로 든 종목도 들기 전 상태를 모르는 것은 같으므로 G1 계약 위반은 아니다 `[추론]`. G4 엔진이 다시 든 종목을 처음 받는 종목처럼 다뤄 상태를 REST로 확인하고 `served` 전까지 보호 폴링을 유지해야 한다는 risk-ops R2에 동의한다(Q7).
- **S10 만료 직후 재접속과 늦은 연결의 `apply`**: 만료(세대 g+1) → 새 연결 관심(g+2) 순서면 어느 쪽이 먼저 `applyLock`을 잡든 마지막 결과는 새 관심이다(g+1 호출은 세대가 달라 버림, 먼저 돌면 곧 g+2가 덮음) `[추론: 코드 경로, 재현 안 함]`.

### 권고 (수정 요구 아님)
- **Q7**: G4 엔진 PR에서 "관심에 (다시) 든 종목은 `served` 확인과 REST 상태 확인 전까지 진입 금지·보호 폴링"을 시험으로 고정한다(S9, risk-ops R2).
- **Q8**: G2에서 구독 계획을 붙일 때 만료 직전 재접속 경합 시험과 `sink.apply` 소요 상한(엔진 3초 무생존 기준 대비)을 함께 넣는다(risk-ops R1, architect M1·M2와 같음).
- **Q9**(사소): EngineFeedServer.java:775 주석 `/** 시험용: 지금 연결에서 넣지 못한 생존 줄 수. */`가 새 `interestGenerationForTest()` 위로 밀려 `heartbeatsDroppedForTest()`는 주석이 없다. `EngineOpensBrokerStream.java`의 쓰지 않는 `import java.util.Set`(architect M3).

### 그 밖
- **R2**: 지금 main(`52d5d365`)과 `docs/qa/pending.md`·`docs/qa-out-status.md`가 충돌한다(표 줄 추가끼리, 양쪽 줄 다 남기면 됨, 합친 트리 시험 통과). 머지 전에 main을 다시 합치고 CI가 도는지 확인한다.
- architect·risk-ops 재검토 원문 판정은 둘 다 승인이다. 이 보고서는 그 원문과 별개로 위 항목을 다시 확인했다.

### 판정
승인(재검토 2026-11-02, 헤드 `240f4b4d`). 1차 발견 1 해소(직접 재현 시험·되돌림 시험으로 확인), 실패·확인 못 함 항목 없음. 머지 전 R2(main 충돌) 해결 필요. 실전 관련 PR이므로 머지는 사용자가 직접 한다.
