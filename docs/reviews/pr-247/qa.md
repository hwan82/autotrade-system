# QA 검토: 한국투자 실전 주문·계좌 조회 클라이언트 (D-145, 7단계 묶음 D-H 1차) (PR #247 QA)
- 대상: PR #247 / 커밋 `db8398a2` (브랜치 `claude/project-thread-truegb`) / main 대비 diff 전체(merge-base = `53482a56`, 34파일 +2,236 −27: 운영 코드 19파일(새 패키지 `com.autotrade.broker.kis.order` 14파일, `KisErrorCode`·`KisTokenClient` 공개 범위, `KisPlugin`·`KisRestClient` 주석, `build.gradle.kts` 주석), 시험 5파일, 보호 시험·고정물 2파일(`ArchitectureTest`, 새 위반 예제 `BypassPluginOrderClient`), 문서 8파일(D-145, 결정 색인, ADR-002, ADR-003, kis-open-api-spec, `pending.md`, 검토 원문 3))
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon` (헤드 `db8398a2`): 1회째 Maven Central 429로 `archunit-junit5:1.4.1` 받기 실패(빌드 실패로 보지 않음), 20초 뒤 2회째 **BUILD SUCCESSFUL in 4m 9s, 138 작업 모두 실행**, 미러 없이. 시험 결과 XML 합계 2,044건, 실패·오류·건너뜀 0. `:autotrade-broker-kis` 68건, `:autotrade-architecture-test` 47건.
  - PR 헤드 CI(GitHub check runs, 2026-10-26T12:09Z): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens (Playwright) 모두 success.
  - `git merge-tree --write-tree origin/main origin/pr-247`: **`docs/qa/pending.md` 충돌**(main이 #239 `c1972a0`을 합친 뒤). 다른 파일은 자동 합침.
  - `scripts/ci/mark-protected-changes.sh main`: 보호 대상 표시됨(실전 관련 PR).
  - `git diff --stat origin/main...HEAD -- autotrade-risk autotrade-app-engine autotrade-app-gateway autotrade-domain autotrade-marketdata autotrade-app-lab`: 변경 0줄.
  - QA 임시 시험 `QaProbeTest`(3건, 커밋하지 않고 지움, `git status` 깨끗 확인): 아래 S1·S2·S3. 모두 단언대로 재현.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·`RealOrderGateways`·명령 채널 무변경(위 `--stat`). `KisPlugin.orderGateway`는 여전히 `Optional.empty()`(KisPlugin.java:94-96), 능력은 `REALTIME_QUOTE`뿐(73-75행). `RealTradingGuardTest`·`GuardedOrderGatewayTest` 직접 빌드에서 통과 |
| A2 | 통과 | `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 본문 무변경(주석만). 새 `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN`(ArchitectureTest.java:178-181, `orderPackageStaysIn` 192-198)과 `KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN`(188-191)이 플러그인 밖에서 `broker.{kis,kiwoom,toss}.order..`·`KisTokenClient` 의존을 막고, 위반 예제의 생성·호출·세션·계좌·토큰 발급 의존을 잡는 단언(537-547)과 운영 코드 검사(432-435)가 통과. 한계: 리플렉션은 못 막고, 이 주문 코드가 실릴 클래스패스는 오히려 주문하면 안 되는 프로세스들이다(`broker-kis` 의존: app-collector·app-experiment·app-gateway, 실전 엔진은 아직 없음). 이들은 `RealPathReflectionTest` 범위 밖이다. risk-ops G2로 연결 전 처리로 이월됨. 아래 Q3 |
| A3 | 통과 | `KisOrderClient`는 생성 때 받은 계좌 하나로만 보낸다(KisOrderClient.java `submit`·`cancel`의 `CANO`/`ACNT_PRDT_CD`), KRX 종목이 아니면 보내기 전에 거절(`krxCode`). 다른 증권사로 바꾸는 경로 없음 |
| A4 | **실패** | 클라이언트 자체는 어떤 결과에서도 다시 보내지 않는다(POST 1회 단언 시험들 통과, KisOrderClient.java:134-152). 그러나 "미접수 확인" 역할의 `KisOrderReconciler.NotFound`("접수되지 않았음이 확정됐다. 새 멱등 키로 1회 재발주할 수 있다", KisOrderReconciler.java:88-91)가 **틀렸을 때 위험한 쪽(중복 주문)으로 기우는 가정 두 개와 호출자가 넘기는 시각 하나**에 기대고, 그 의존을 코드도 문서도 "모름으로 기운다"고 적었다. 아래 **발견 1**(임시 시험 S1, S3) |
| A5 | 해당 없음 | 주문 생성·멱등 키 발급·영속 연결이 없다(D-145 "안 한 것" 1행). 한국투자 주문에는 멱등 키 칸이 없어 `OrderRequest.key()`가 증권사로 가지 않는다는 점이 클래스 주석에 적혀 있다(KisOrderReconciler.java:17-18). 연결 PR에서 다시 본다 |
| A6 | 통과(이 PR 범위) | 블랙리스트·수동 보유 경로 무변경, 주문 클라이언트는 어디에도 연결되지 않음. 특징 대조의 "찾음"이 사용자 HTS 주문을 우리 것으로 묶을 수 있는 위험은 `byOrderNo=false` 표시와 D-145 위험 2로 묶음 E에 넘김 |
| A7 | 통과(이 PR 범위) | 계좌별 `submit`·`cancel` `synchronized`, 세션별 0.1초 간격(`KisSession.pace`, 시험 `호출_사이_0_1초_간격을_지킨다` 통과), 잠금은 간격 계산에만 쥔다. 다른 계좌는 다른 세션이다. 토큰 무효화 경쟁은 아래 Q1(S2) |
| A8 | 통과 | 엔진·게이트웨이·실험실 코드 무변경, 프로세스 구성 무변경 |
| A9 | 통과(이 PR 범위) | 보호 시세·서버 측 손절 코드 무변경. 다만 연결되면 손절 매도가 토큰 관문 60초 동안 `NOT_SENT`가 되는 경로가 있다(아래 Q1, S2) |
| A10 | 통과 | 시험의 계좌번호는 `"0".repeat(8) + "-01"`, 키는 `APP-KEY-PLACEHOLDER`(OrderFakes.java:22-24). diff 추가 줄의 8-2자리 숫자 모양은 주석의 자리 표시자 설명뿐(직접 grep). 주문번호(`0000117057`, `91252`)는 비밀값이 아님. `KisAccountNumber.toString()`은 가림, `Unknown` 사유는 TR·예외 종류·HTTP 상태만. CI Secret scan success |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·단언 완화 없음. `ArchitectureTest`는 규칙 2개·시험 1개·위반 예제 단언을 더했을 뿐이고 기존 고정 값(위반 수 4·8 등) 무변경. 새 동작마다 실패 경우를 포함한 시험이 있다(`KisOrderReconcilerTest` 14건이 "없음"의 조건을 하나씩 깸, `KisOrderClientTest` 15건, `KisAccountQueriesTest` 9건). 다만 발견 1의 두 경계(시계 오차 방향, 실제 POST 시작 시각)를 고정하는 시험은 없다 |
| A12 | **실패** | 장애표 4번(broker-and-fault-design.md:163 "조회 먼저 → **미접수일 때만** 새 키로 1회 재발주")과 장애 테스트 4번 합격 기준(191행 "중복 주문 0건")은 "없음"이 참일 때만 성립한다. 발견 1의 S1(PC 시계가 61초 빠름)·S3(실제 POST가 호출자 기록 시각보다 40초 늦음)에서 실제로 접수됐거나 아직 반영 전인 주문에 `NotFound`가 나온다. 장애 대응 설계 어디에도 PC 시계 정확도 요구가 없다(직접 grep "시계·NTP": 0건) |
| A13 | 통과 | 국내 전용, 시각 계산은 `Asia/Seoul`(KisOrderReconciler.java:36, `Fingerprint`가 보내기 시작 시각의 한국 날짜 = 주문 날짜를 강제 72-74행). 가격은 `BigDecimal`, 원 단위 정수만(`won`) |
| A14 | **실패** | D-145 위험 1(D-145-kis-order-client.md:55)은 "반영 지연 30초, 시계 오차 60초는 공식 문서에 없다. **틀리면 대부분 '모름'으로 기운다**"고 적고, 클래스 주석(KisOrderReconciler.java:20)도 "안전한 쪽으로 기운다"고 적었다. 이 두 가정은 틀리면 항상 "없음"(재발주 허용) 쪽으로 틀린다(S1, S3). 결정 기록이 연결 PR이 기댈 안전 성질을 반대로 적었다. 그 밖에 D-145 "안 한 것" 1행 "실전 엔진 저장소(ADR-015, PR #239)…가 아직 없다"는 main `c1972a0`이 #239를 합쳐 지금은 옛 문구(권고 Q5) |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(2,044건, 실패 0), CI Gradle build success |
| B6 | 통과 | 새 동작에 시험 있음(위 A11 수), 건너뜀·삭제 없음. 발견 1의 경계 시험이 빠진 것은 A11·A4에 적음 |

## 발견 1 (A4·A12·A14): "없음(NotFound)"이 시계 오차·반영 지연·호출자 시각이 틀리면 중복 주문 쪽으로 틀린다

`find`(KisOrderReconciler.java:108-143)는 ① 조회 시작 ≥ `sendStartedAt + requestTimeout + SETTLE(30초)`(119-121행)이고 ② 창 `ord_tmd ≥ sendStartedAt − CLOCK_SKEW(60초)`(123-128행) 안에 묶이지 않은 행이 0건이면 `NotFound`를 낸다. 세 값 모두 이 클래스가 확인하지 않는다.

- **S1 시계 오차(재현)**: 우리 PC 시계가 한국투자보다 61초 빠르다. 09:30:00(우리 시계)에 보낸 지정가 매수가 실제로 접수됐고 한국투자 `ord_tmd`는 09:28:59다. 반영 대기 뒤 완전한 조회에 그 행 하나만 있다 → `find` = `NotFound`. 우리 주문이 창 밖으로 빠져 "접수 안 됨 확정"이 나오고, 연결되면 새 키로 재발주 = **중복 주문**. 반대 방향(PC가 느림)은 창이 넓어질 뿐 안전하다. 즉 `CLOCK_SKEW` 가정은 한쪽으로만 틀리고, 그쪽이 위험한 쪽이다. 개인 PC(Windows)는 시간 동기화 주기·실패에 따라 수십 초 이상 어긋날 수 있다 `[추론]`.
- **S2(아래 Q1)는 별건.**
- **S3 호출자 시각(재현)**: `KisOrderResult.Unknown`(KisOrderResult.java:32)에는 사유 문자열만 있고 **실제 POST를 시작한 시각이 없다**. `Fingerprint.sendStartedAt`과 `requestTimeout`은 호출자가 따로 넣는다. 그런데 `submit`은 POST 전에 계좌 잠금(`synchronized`)을 기다리고 토큰을 받는다(KisOrderClient.java:134, 토큰 발급 HTTP는 시간 상한까지 걸릴 수 있음). S3: 호출자가 `submit` 직전 시각 T를 기록, 토큰 발급 40초, POST는 T+40에 시작해 10초 뒤 시간 초과, 곧바로 조회(T+50, 아직 반영 안 돼 0행) → 반영 대기 조건 `T+50 ≥ T+10+30` 통과 → `NotFound`. 실제 POST 기준으로는 반영 대기가 30초가 아니라 0초다. `Fingerprint`는 `requestTimeout = 0`도 받는다(69-71행).
- **SETTLE 30초**: 한국투자가 접수한 주문이 일별 체결 조회에 30초 넘게 늦게 보이면(장 시작·혼잡) 같은 식으로 `NotFound`. D-145가 `[가정]`·Q-015로 적었지만 "모름으로 기운다"고 잘못 적었다.

**고칠 것(최소)**:
1. D-145 위험 1과 클래스 주석을 사실대로 고친다: 반영 지연·시계 오차·보내기 시작 시각이 틀리면 "없음"이 틀리고 중복 주문이 된다. 연결 PR(묶음 E)이 `NotFound`로 재발주하기 전 충족할 조건을 D-145에 적는다(예: Q-015 실측으로 반영 지연 확인, 시계 오차를 실행 중에 재거나 막기).
2. 보내기 시작 시각을 호출자에게 맡기지 않는다. `KisOrderClient`가 POST 직전 세션 시계로 잰 시각(그리고 실제 HTTP 시간 상한)을 `Unknown`에 담고, `Fingerprint`가 그것을 쓰게 한다. 아니면 최소한 계약으로 "sendStartedAt은 POST 직전보다 앞설 수 없다"를 강제할 방법을 둔다.
3. 시계 오차를 한쪽으로만 여유 두는 문제를 다룬다. 예: 일별 체결 응답의 HTTP `Date` 헤더나, 같은 날 우리가 접수 응답을 받은 주문의 `ORD_TMD`와 우리 시계를 견줘 차이가 여유를 넘으면 `Indeterminate`. 또는 근거를 갖고 여유를 늘린다.
4. 위 경계(S1, S3)를 고정하는 시험을 더한다.

## 점검표 밖 발견 (권고, 판정에 넣지 않음)

- **Q1 (S2 재현, risk-ops G4와 같은 원인이나 결과 평가가 다름)**: 조회가 만료 응답을 받아 새 토큰 T2를 받은 뒤, 그 전에 옛 토큰 T1으로 보낸 주문의 만료 응답이 늦게 오면 `interpret`이 `invalidateToken()`으로 **T2를 버린다**(KisOrderClient.java:163, KisSession.java:79는 무엇이 실패했는지 보지 않고 캐시를 비움). 이어 5초 뒤 시장가 **손절 매도**(`STOP_LOSS`)는 `KisTokenGate`(직전 시도 60초 안, KisTokenGate.java:17·34) 때문에 POST 0건, `NotAccepted(NOT_SENT)`. risk-ops 재검토는 "안전한 쪽"이라 했지만 중복이 없을 뿐 **보호 매도가 최대 60초 막히는 보호 공백**이다(A9). 연결 전에 "실패한 토큰 값이 지금 캐시 값과 같을 때만 버림"과 시험을 넣을 것.
- **Q2**: 토큰 만료·한도 초과 코드를 HTTP 상태와 상관없이 "접수 안 됨 확정"으로 본다(KisOrderClient.java `interpret`). `EGW00121`·`EGW00122`는 `[가정]` 코드(KisErrorCode.java 주석)이고, `EGW00215`는 이름이 "**원장**에서 허용 가능한 초당 거래건수 초과"라 게이트웨이 앞단 거절인지 원장에 닿은 뒤인지 공식 근거가 없다. Q-015 확인 항목에 "이 코드들이 온 주문이 일별 체결 조회에 없는지"를 더할 것.
- **Q3 (risk-ops G2 이월 재확인)**: 주문 코드가 실릴 프로세스(수집기·실험·게이트웨이)는 리플렉션 규칙 밖이고, 수집기는 `kis-news` 한국투자 키를 갖는다(D8·D-136 검사 아직 없음). 정적 의존은 ArchUnit이 막지만 연결 PR 전에 주문 패키지를 실전 엔진만 의존하는 모듈로 나누거나 리플렉션 범위를 넓히는 것을 승인 조건으로 둘 것.
- **Q4**: `KisSession.invalidateToken`·`KisTokenGate`의 60초는 조회 재호출에도 걸려, 만료 직후 PANIC 대사 조회가 `IOException`으로 실패할 수 있다(KisAccountQueries.java:177-178). 연결 때 장애표 7번(토큰 만료)과 함께 처리 순서를 정할 것.
- **Q5**: D-145 "안 한 것" 1행 "ADR-015, PR #239가 아직 없다"는 main에 #239가 합쳐져 옛 문구. `docs/qa/pending.md`가 main과 충돌한다(줄 합치기만 필요).

## 점검표 밖 위험 시나리오 (지침 5장 5)
1. 시계 오차로 접수된 주문을 "없음" → 재발주(S1, 발견 1).
2. 토큰 발급·잠금 대기로 실제 POST가 늦어져 반영 전 조회가 "없음" → 재발주(S3, 발견 1).
3. 토큰 무효화 경쟁으로 손절 매도 60초 `NOT_SENT`(S2, Q1).
4. 사용자 HTS 주문 하나만 창 안에 있고 우리 주문은 미접수 → "찾음"으로 남의 주문을 우리 것으로 묶음(D-145 위험 2, 묶음 E로 넘김. 코드로 재확인만, 시험 `반영_대기_뒤_완전한_조회에_같은_특징의_새_주문_하나뿐이면_찾음이다`와 같은 입력).

## 판정
수정 필요(A4, A12, A14: 발견 1)
