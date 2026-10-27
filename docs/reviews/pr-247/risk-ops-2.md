# risk-ops 재검토: 한국투자 실전 주문·계좌 조회 클라이언트(D-145, 7단계 묶음 D-H 1차) [실전 관련]
- 대상: PR #247 / 브랜치 `claude/project-thread-truegb` 커밋 `fe587475` / main(`53482a56`) 대비 diff 전체. 1차 원문은 [risk-ops.md](risk-ops.md)(그대로 둠). 이 재검토는 요청에 따라 같은 파일에 덧붙이지 않고 새 파일로 쓴다
- 점검표: A
- 직접 실행한 것
  - `./gradlew build --no-daemon`(검토자 임시 미러 init 스크립트, 저장소 밖): **BUILD SUCCESSFUL**. `:autotrade-broker-kis:test` 68건(1차 63건 + 5건), `:autotrade-architecture-test:test` 47건(새 규칙 `KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN` 검사·위반 예제 단언 포함) 다시 실행해 통과. `:autotrade-risk:test` 57건, `:autotrade-app-engine:test` 109건은 입력이 1차와 같아 Gradle `UP-TO-DATE`(1차 실행 결과 그대로 통과). 실패·오류·건너뜀 0.
  - 1차 탐침 `ReviewProbeTest`를 같은 그대로 다시 넣어 돌리고 지움(커밋하지 않음). 결과
    - P1 불완전 조회의 단일 후보 → `Indeterminate(조회가 불완전함…)`(1차: `Found`)
    - P2 반영 대기 전 단일 후보 → `Indeterminate(반영 대기 시간 전 조회)`(1차: `Found`)
    - P3 원주문 `orgn_odno`=자기 번호 → `Found`(1차: `NotFound`)
    - P4 `pdno`=`A005930` → `Found`(1차: `NotFound`)
    - P5 `output1` 없는 일별 체결 → `complete=false`, 잔고 → `complete=false, unreadableRows=2`(1차: 둘 다 `complete=true`)
    - P6 HTTP 502 + `EGW00203` → `Unknown`(1차: `NotAccepted(BROKER_REJECTED)`)
  - 1차 뒤 바뀐 것: `git diff 33e950cf fe587475`(코드 8파일, 시험 3파일, 문서 4파일). 그리고 diff 전체를 다시 봄.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·`RealOrderGateways`·명령 채널 변경 없음. `KisPlugin.orderGateway` 빈 값 유지. risk·engine 시험 통과 |
| A2 | 통과 | `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 본문 불변. `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN` 유지, 새 `KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN`(ArchitectureTest.java:183-191)이 플러그인 밖의 `KisTokenClient` 의존을 막고 위반 예제 `BypassPluginOrderClient.token`의 `KisTokenClient.fetcher`를 잡음(ArchitectureTest.java:542-544) — 실행 통과. 운영 코드에서 플러그인 밖의 `KisTokenClient`·`order` 사용 0건(grep). 리플렉션 한계는 아래 G2(이월) |
| A3 | 통과 | 1차와 같음(계좌 하나·KRX만, 다른 증권사 전환 없음) |
| A4 | 통과 | 클라이언트는 어떤 결과에도 다시 보내지 않음(POST 1회 시험 유지, 새 `KisOrderClientTest#그_밖의_오류_코드가_HTTP_200이_아닌_응답에_실리면_모름이다`도 POST 1회 단언). R3: 그 밖의 오류 코드는 HTTP 200일 때만 `NotAccepted`, 아니면 `Unknown`(KisOrderClient.java `interpret` default 분기). R1: `find`가 조회 완전·같은 날·반영 대기 뒤를 찾음·없음 모두의 전제로 먼저 검사(KisOrderReconciler.java `find`), `KisOrderReconcilerTest#반영_대기_전이거나_불완전한_조회에서는_후보가_하나여도_찾음을_내지_않는다`. R2: `output1`이 배열이 아니면 불완전(KisAccountQueries.java `dailyOrders`·`balance`, `KisAccountQueriesTest#응답에_행_칸이_없으면_0행_완전으로_읽지_않는다`), "없음"은 창 안에 묶이지 않은 행이 종목·방향·취소 여부와 상관없이 0건일 때만(`…창_안에_묶이지_않은_행이_없을_때만_없음이다`, `…원주문번호_칸에_자기_번호가_오거나_종목코드에_접두가_붙어도_우리_주문을_놓치지_않는다`, `…취소_주문_행은_후보가_아니고_묶이지_않았으면_확정을_막는다`). 탐침 P1~P6 모두 안전한 쪽으로 바뀜 |
| A5 | 해당 없음 | 1차와 같음(멱등 키 발급·영속과 연결은 묶음 E). 새 판정 규칙상 호출자가 `claimed`에 이 계좌의 우리 주문번호(취소 주문 포함)를 빠짐없이 넘겨야 함이 클래스 주석·D-145 위험 3에 적힘 — 빠뜨리면 모름이 늘 뿐 잘못된 확정은 나오지 않음(창 안 묶이지 않은 행이 있으면 확정 안 함) |
| A6 | 통과(이 PR 범위) | 특징 대조의 찾음은 이제 "완전한 조회, 반영 대기 뒤, 창 안 묶이지 않은 행이 정확히 하나"일 때만 나와, 우리 주문과 사용자 HTS 주문이 함께 있으면 모름. 남은 경우(우리 주문이 실제로 접수되지 않았고 같은 특징의 HTS 주문 하나만 창 안)는 `byOrderNo=false`로 표시하고 묶음 E에서 확인 조건을 정한다고 D-145 위험 2에 적힘 |
| A7 | 통과(이 PR 범위) | G3 반영: 세션 잠금은 `pace()`(보내기 간격 계산)에만, HTTP 응답 대기 중에는 쥐지 않음(KisSession.java). 토큰 캐시 `TokenCache.get/invalidate`는 `synchronized`, `KisTokenGate.fetch`도 `synchronized`라 동시 호출에 안전. 계좌별 주문은 `KisOrderClient`의 `synchronized`로 직렬 |
| A8 | 통과 | 실전 엔진·게이트웨이·실험실 코드 변경 없음, 연결 없음 |
| A9 | 통과 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 시험도 자리 표시자(`0`×8-01, `APP-KEY-PLACEHOLDER`)만 씀. 새 `Unknown` 사유 문자열은 TR·결과 코드·HTTP 상태만 담음(응답 원문·계좌 없음) |
| A11 | 통과 | 보호 테스트는 규칙·위반 예제·단언을 더하기만 함(기존 단언 수 불변, 고정 값 변경 없음). 1차에서 지적한 실패 경우마다 시험이 생김(위 A4) |
| A12 | 통과 | 장애표 4번 전제("없음"이면 정말 없음)를 1차의 R1·R2 입력에서 지킴(탐침 P1~P5). 대응표·장애 테스트 문서 변경 필요 없음 |
| A13 | 해당 없음 | 국내 전용, 시각 `Asia/Seoul`. 새 `Fingerprint` 검사가 보내기 시작 시각의 한국 날짜와 주문 날짜 일치를 강제(`…보낸_시각의_한국_날짜와_주문_날짜가_다르면_특징을_만들지_않는다`) |
| A14 | 통과 | D-145 결정 3·5·8, 위험 2·3, "검토 반영" 절, 결정 로그 README 행, ADR-002 2.2·4장·검증 표, ADR-003 2장·6장이 바뀐 코드와 일치. 1차 A14 지적(위험 1의 "모름으로 기운다" 문장)은 결정 5가 가정 칸에 기대지 않게 바뀌어 성립 |

## 1차 지적 처리 확인
| 1차 항목 | 처리 | 확인 |
|---|---|---|
| R1 찾음의 완전성·반영 대기 | 고침 | 코드·시험·탐침 P1·P2 |
| R2 `output1` 없음·`orgn_odno`·`pdno` 가정의 "없음" | 고침 | 코드·시험·탐침 P3·P4·P5. "없음"이 종목코드·원주문번호 해석에 더는 기대지 않음 |
| R3 5xx 기타 오류 코드의 확정 | 고침 | 코드·시험·탐침 P6 |
| G1 `KisTokenClient` 공개 | 고침 | ArchUnit `KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN` + 위반 예제 |
| G2 주문 코드가 게이트웨이·수집기·실험 클래스패스에 있음(리플렉션) | 이월(D-145 검토 반영 절에 묶음 E 연결 전 architect 비교로 기록) | 권고 유지. 연결 PR(묶음 E)의 risk-ops 검토 조건으로 다시 본다 |
| G3 세션 잠금 | 고침 | 코드 확인 |

## 점검표 밖 발견
- 수정 필요 없음.
- 권고(판정에 넣지 않음)
  - **G4.** 동시 호출에서 한 스레드가 토큰 만료 응답으로 `invalidateToken()`을 부르는 사이 다른 스레드가 새 토큰을 받았으면, 새 토큰을 버리게 되고 `KisTokenGate` 60초 하한 동안 주문이 `NOT_SENT`가 될 수 있다(안전한 쪽, 재발주·중복 없음). 묶음 E 연결 때 "버릴 토큰 값이 지금 캐시 값과 같을 때만 버림" 같은 조건을 검토한다.
  - **G5.** 새 판정은 같은 계좌에서 다른 종목 주문이나 사용자 HTS 주문이 창(보내기 시작 60초 전 이후) 안에 있으면 모름이 된다. 안전한 쪽이지만 묶음 E에서 모름 처리(사람 확인 흐름·재조회 주기)가 실제로 돌아야 타임아웃 뒤 매매가 막히지 않는다. 묶음 E의 장애 테스트(장애표 4번)에 "모름이 이어질 때의 처리"를 넣는다.

## 판정
승인

## 재검토 2026-10-27
- 대상: PR #247 / 브랜치 `claude/project-thread-truegb` 헤드 `4283eb6f`(독립 QA 반영 `9027e0de` + main 합침) / main 대비 diff 전체. 앞 원문(위, [risk-ops.md](risk-ops.md))과 독립 QA 원문 [qa.md](qa.md)(판정 수정 필요, 발견 1 S1·S3, 권고 Q1~Q5)를 보고 다시 봄
- 점검표: A
- 직접 실행한 것
  - `./gradlew build --rerun-tasks --no-daemon`(공개 미러 init 스크립트, 사용자 결정 2026-10-16): **BUILD SUCCESSFUL in 5m 53s, 138 작업 모두 실행**. 결과 XML: `:autotrade-broker-kis:test` 74건(직전 68 + 6), `:autotrade-risk:test` 57건, `:autotrade-architecture-test:test` 47건, `:autotrade-app-engine:test` 109건, `:autotrade-marketdata:test` 164건(새 `TokenCacheTest#실패한_요청의_토큰이_지금_토큰일_때만_버린다` 포함). 실패·오류·건너뜀 0.
  - `git show 9027e0de`(코드 6파일, 시험 5파일, D-145·README), `4283eb6f` 합침 내용(main 쪽 #239·#249 문서·UI, `pending.md` 줄 합침). `git diff --stat origin/main...HEAD -- autotrade-risk autotrade-app-engine autotrade-app-gateway autotrade-domain autotrade-app-lab autotrade-app-collector`: 0줄. `autotrade-marketdata`는 `TokenCache.invalidateIfCurrent` 더하기(+12)와 그 시험뿐.
  - 임시 탐침 `RiskOpsProbeTest`(커밋하지 않고 지움, `git status` 깨끗): 아래 N1의 P1·P2.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·`RealOrderGateways`·명령 채널 무변경(위 `--stat` 0줄). `KisPlugin.orderGateway` 빈 값 유지. `:autotrade-risk:test`·`:autotrade-app-engine:test` 재실행 통과 |
| A2 | 통과 | `ArchitectureTest` 이번 커밋 무변경. `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN`·`KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN` 유지, 재실행 통과. 운영 코드에서 `KisOrderResult.Unknown`·`Fingerprint` 생성은 플러그인 안 2곳뿐(KisOrderClient.java:165, KisOrderReconciler.java:98, grep). 리플렉션 한계는 D-145 연결 조건 4로 기록(G2 이월 유지) |
| A3 | 통과 | 앞과 같음(생성 때 받은 계좌 하나, KRX만) |
| A4 | **실패** | QA S3는 고쳐짐: 보내기 시작 시각을 `KisSession.post`가 `pace()` 뒤 POST 직전에 알리고(KisSession.java `post`의 `started.accept`), `Unknown`이 시작·끝 시각을 담고, `Fingerprint`는 공개 생성자 없이 `Fingerprint.of(주문, Unknown)`으로만 만든다. 반영 대기는 응답 받은 시각부터(`KisOrderReconciler.find` `sendEndedAt().plus(SETTLE)`). 간격 대기 중 실패는 `NOT_SENT`. 시험 `KisOrderClientTest#모름_결과의_보내기_시작_시각은_토큰_발급_뒤_실제_POST_직전이다` 통과. QA S1(**일정한** 시계 차이)도 고쳐짐: 일별 체결 첫 쪽 `Date`로 차이 하한을 재 창을 옮기고 `Date`가 없으면 모름(`우리_시계가_61초_빠르면_…`, `서버_시각을_읽지_못한_…`, `시계_차이_하한은_…` 통과, 하한 계산 `ServerClock.offsetLowerBound` = `serverDate − receivedAt`이 절사·왕복 시간에서 보수적임을 직접 확인). 그러나 시계 차이를 **보낸 시점이 아니라 조회 시점에** 재므로, 보내기와 조회 사이에 PC 시계가 바로잡히면(시간 동기화의 단계 보정) 창이 틀리고 접수된 주문에 `NotFound`가 나온다 → 아래 N1(탐침 P1) |
| A5 | 해당 없음 | 멱등 키 발급·영속·연결은 묶음 E(D-145 "안 한 것" 1행, ADR-015가 main에 들어온 사실로 갱신됨) |
| A6 | 통과(이 PR 범위) | 블랙리스트·수동 보유 경로 무변경, 연결 없음. 특징 대조 "찾음"의 남의 주문 묶기 위험은 `byOrderNo=false`·D-145 위험 2로 묶음 E에 넘김(앞과 같음) |
| A7 | 통과(이 PR 범위) | QA Q1·앞 G4 고쳐짐: `TokenCache.invalidateIfCurrent`(synchronized, 값이 같을 때만 비움), 주문(`KisOrderClient.interpret`)·조회(`KisAccountQueries.call`) 모두 실패한 요청에 쓴 토큰을 넘김. 시험 `KisOrderClientTest#옛_토큰의_늦은_만료_응답은_그_사이_받은_새_토큰을_버리지_않아_손절_매도가_막히지_않는다`, `TokenCacheTest#실패한_요청의_토큰이_지금_토큰일_때만_버린다` 통과. 시세 경로(`KisRestClient` 등)의 기존 `invalidate()`는 그대로(이 PR 범위 밖) |
| A8 | 통과 | 엔진·게이트웨이·실험실 코드 무변경, 연결 없음 |
| A9 | 통과(이 PR 범위) | 보호 시세·서버 측 손절 무변경. QA가 짚은 손절 매도 60초 `NOT_SENT` 경로(S2)는 위 A7대로 막힘. 토큰 무효 뒤 PANIC 조회 순서(QA Q4)는 D-145 연결 조건 3 |
| A10 | 통과 | 새 시험도 자리 표시자만, 새 `Unknown` 사유 문자열은 TR·종류·HTTP 상태만. `Date` 헤더 값은 결과·로그에 싣지 않음 |
| A11 | 통과 | 보호 시험 무변경(이번 커밋에 `autotrade-architecture-test` 없음), 고정 값 변경 없음. 새 동작마다 시험이 있다(S1·S3·S2·`Date` 없음·하한 계산). 단 N1의 경계(보내기와 조회 사이 시계 단계 보정)는 시험이 없다 |
| A12 | **실패** | 장애표 4번(broker-and-fault-design.md:163 "미접수일 때만 새 키로 1회 재발주")과 장애 테스트 4번 "중복 주문 0건"의 전제("없음"이면 정말 없음)가 N1 입력에서 깨진다. D-145 연결 조건 1·2(반영 지연, 게이트웨이 `Date`↔`ORD_TMD` 시계 차이 실측)는 이 경우를 덮지 않는다 |
| A13 | 통과 | 국내 전용, 날짜·시각 `Asia/Seoul`(주문 날짜는 이제 실제 POST 시작 시각의 한국 날짜로 정함), 가격 원 정수 |
| A14 | **실패** | D-145 위험 1(D-145-kis-order-client.md "시계 차이 자체와 보내기 시각은 이제 재서 쓰므로 PC 시계가 몇 분 어긋나도 창이 따라 옮겨진다")과 클래스 주석("시계 오차는 가정하지 않고 잰다", KisOrderReconciler.java 18-30행)은 차이가 보내기~조회 사이 **일정할 때만** 참이다. 안전 성질을 실제보다 넓게 적었다(N1). 그 밖에 QA A14의 "모름으로 기운다" 문장은 사실대로 고쳐졌고, Q5(ADR-015 옛 문구)·`pending.md` 충돌은 해소됨 |

## QA·앞 검토 지적 처리 확인
| 항목 | 처리 | 확인 |
|---|---|---|
| QA 발견 1 S3 호출자 시각 | 고침 | 코드·시험(위 A4) |
| QA 발견 1 S1 시계 오차 | 일정한 차이는 고침, 보내기~조회 사이 차이 변화는 남음 | 탐침 P2(일정 차이 → `Found`), P1(뒤로 단계 보정 → `NotFound`), 아래 N1 |
| QA 발견 1 SETTLE 30초 | 근거 없음을 사실대로 적고 실측 전 `NotFound` 재발주 금지(연결 조건 1) | 문서 확인 |
| QA Q1(S2)·앞 G4 | 고침 | 위 A7 |
| QA Q2 | Q-015 확인 항목에 더함 | 문서 확인 |
| QA Q3·앞 G2 | 연결 조건 4 | 문서 확인, 연결 PR 승인 조건으로 다시 본다 |
| QA Q4 | 연결 조건 3 | 문서 확인 |
| QA Q5 | 고침 | 문서·합침 확인 |

## 점검표 밖 발견

### 수정 필요
**N1. 시계 차이를 조회 때 한 번만 재서, 보낸 뒤 PC 시계가 바로잡히면 "없음"이 틀린다** (KisOrderReconciler.java `find`: `windowStart = sendStartedAt + day.serverClock().offsetLowerBound() − CLOCK_SKEW`, `settled = sendEndedAt + SETTLE`)
- `sendStartedAt`·`sendEndedAt`은 보낼 때의 벽시계, 차이 하한은 조회 때의 벽시계로 잰다. 둘 사이 차이가 같다는 가정이 코드·문서 어디에도 확인되지 않는다. 우리 시계가 61초 빠른 PC(QA S1과 같은 PC)는 바로 그 때문에 시간 동기화가 언젠가 시계를 단계로 되돌린다 `[추론]`(Windows 시간 서비스는 차이가 크면 서서히가 아니라 한 번에 맞춘다 `[가정]`, 확인 못 함).
- 탐침(임시 시험, 지움): 우리 시계 61초 빠른 상태에서 09:30:00(우리)에 보냄, 실제 접수 `ord_tmd` 09:28:59, 10초 뒤 시간 초과. 조회 전에 시계가 바로잡혀 조회 응답 `Date`로 잰 차이가 0.
  - P1: 그 접수 행 하나만 있는 완전한 조회(우리 시계 09:30:40) → **`NotFound`**. 창 시작 = 09:30:00 + 0 − 60초 = 09:29:00이라 09:28:59 행이 창 밖. 재발주하면 중복 주문.
  - P2(대조): 시계가 그대로 61초 빠르면 같은 입력이 `Found`.
  - 반대 방향(계산으로 확인, 탐침 없음): PC가 느렸다가 앞으로 바로잡히면 창은 넓어지지만, 반영 대기 비교(`queriedFrom ≥ sendEndedAt + SETTLE`)가 보낼 때와 조회 때의 서로 다른 벽시계를 견주게 되어 실제 경과가 보정 폭만큼 줄어든다(예: 30초 이상 앞으로 바로잡히면 실제로는 응답 직후 조회여도 통과). 그때 아직 반영 안 된 주문의 0행 조회는 `NotFound`.
- 즉 뒤로 바로잡히면 창이, 앞으로 바로잡히면 반영 대기가 틀리고, 둘 다 "없음"(중복 주문) 쪽으로 틀린다. `KisOrderClient.unknown`이 `now`가 `startedAt`보다 앞서면 조용히 `startedAt`으로 맞추는 것(KisOrderClient.java:165)도 같은 단계 보정을 감춘다.
- 고칠 것(최소, 하나를 고르고 이유를 D-145에 적는다):
  1. 단조 시계로 단계 보정을 감지한다: `Unknown`에 POST 시작 때의 `System.nanoTime()`(또는 주입 가능한 단조 시계) 값을 함께 담고, 조회 때 "벽시계 경과 − 단조 경과"가 허용치(예: 1~2초)를 넘으면 `Indeterminate`. 반영 대기도 단조 경과로 센다.
  2. 또는 보낼 때도 차이를 잰다(직전 조회·토큰 응답의 `Date`로 보낸 시점 하한을 남겨 두고, 조회 때 잰 값과 크게 다르면 `Indeterminate`, 같으면 둘 중 작은 하한을 쓴다).
  - 어느 쪽이든 P1과 앞으로 바로잡히는 경우를 고정하는 시험을 더하고, D-145 위험 1·클래스 주석을 "보내기~조회 사이 시계 차이가 바뀌면 …, 그래서 …로 막는다"로 사실대로 고친다.
- 연결 전이라 지금 실제 피해는 없고 D-145 연결 조건 1로 `NotFound` 재발주도 실측 전까지 막혀 있다. 그러나 연결 조건 1·2를 채워도 이 경우는 남고, 이 PR이 연결 PR이 기댈 안전 성질을 문서로 확정하므로 이 PR에서 고친다(QA 발견 1과 같은 판단).

### 권고(판정에 넣지 않음)
- **G6.** `ServerClock`은 첫 쪽 응답 하나로 잰다. 토큰 만료 재호출이 끼면 `receivedAt`이 늦어져 하한이 더 작아질 뿐(안전 쪽)이다. 문제 없음, 기록만.
- **G7.** `KisDailyOrders`는 공개 record라 연결 코드가 `serverClock`을 임의로 넣어 만들 수 있다. 연결 PR에서 `find`에 넘기는 값은 `KisAccountQueries.dailyOrders`가 만든 것만 쓰는지 다시 본다.

## 판정
수정 필요(N1 보내기~조회 사이 PC 시계 단계 보정 시 "없음"이 틀림 — 점검표 A4·A12·A14 실패)

## 재검토 2026-10-27 (2)
- 대상: PR #247 / 브랜치 `claude/project-thread-truegb` 헤드 `7c94601e`(N1 수정) / main 대비 diff 전체. 앞 원문(위 두 절, [risk-ops.md](risk-ops.md))과 독립 QA 원문 [qa.md](qa.md)를 보고 다시 봄
- 점검표: A
- 직접 실행한 것
  - `./gradlew build --no-daemon`(공개 미러 init 스크립트, 사용자 결정 2026-10-16): **BUILD SUCCESSFUL in 5m 13s**. 결과 XML: `:autotrade-broker-kis:test` 75건(직전 74 + 1), `:autotrade-risk:test` 57건, `:autotrade-architecture-test:test` 47건, `:autotrade-app-engine:test` 109건, `:autotrade-marketdata:test` 164건 모두 이번에 실행. 실패·오류·건너뜀 0.
  - `git diff 4283eb6f 7c94601e`: 코드 7파일(`Stamp` 새 파일, `KisSession`·`KisOrderClient`·`KisOrderResult`·`KisOrderReconciler`·`KisDailyOrders`·`KisAccountQueries`), 시험 3파일, D-145, 검토 원문 2(architect 복구·risk-ops 재검토). 검토 원문 `architect.md`·`qa.md`·`risk-ops.md`는 `fb4c245c` 대비 0줄, `risk-ops-2.md`는 `c7b34a1e` 대비 0줄(지워진 내용 없음). `git diff --stat origin/main...HEAD -- autotrade-risk autotrade-app-engine autotrade-app-gateway autotrade-domain autotrade-app-lab autotrade-app-collector`: 0줄.
  - 임시 탐침 `RiskOpsProbe2Test`(커밋하지 않고 지움, `git status` 깨끗): 단위 시험이 `Stamp`를 직접 만드는 것과 달리, 같은 `KisSession`을 쓰는 `KisOrderClient.submit`(시간 초과) → `MutableClock.stepWall` → `KisAccountQueries.dailyOrders`(응답 `Date` = 한국투자 시각) → `find`를 실제 경로로 돌림. 우리 시계 61초 빠름, 09:30:00(우리) 보냄, 실제 접수 09:28:59.
    - E0 시계 그대로 → `Found`(대조)
    - E1 보낸 뒤 조회 전 뒤로 61초 보정(직전 N1 탐침 P1과 같은 입력) → **`Indeterminate(PC 시계가 -61초 바뀜)`**(직전: `NotFound`)
    - E2 보낸 뒤 앞으로 30초, 실제 경과 5초, 0행 조회 → **`Indeterminate`**(직전: 반영 대기 통과 뒤 `NotFound`)
    - E3 첫 쪽 요청 **도중** 뒤로 61초 보정 → `Found`(`ServerClock`이 `receivedAt < requestedAt`을 `requestedAt`으로 맞춰 하한 −60초)
    - E4 첫 쪽 요청 20초 도중 뒤로 15초 보정 → `Found`, 그러나 하한이 −46초로 실제(−61초)보다 15초 크게 잡힘 → 아래 G8

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·`RealOrderGateways`·명령 채널 무변경(위 `--stat` 0줄). `KisPlugin.orderGateway` 빈 값 유지. `:autotrade-risk:test`·`:autotrade-app-engine:test` 실행 통과 |
| A2 | 통과 | `ArchitectureTest`·위반 예제 이번 커밋 무변경, 실행 통과. 새 `Stamp`는 `com.autotrade.broker.kis.order` 안이라 `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN` 범위. 운영 코드에서 `Stamp`·`Unknown` 생성은 `KisSession.stamp`(KisSession.java:80)·`KisOrderClient.unknown`(KisOrderClient.java:163)뿐(grep). 리플렉션 한계는 D-145 연결 조건 4(G2 이월 유지) |
| A3 | 통과 | 앞과 같음(생성 때 받은 계좌 하나, KRX만) |
| A4 | 통과 | 클라이언트는 어떤 결과에도 다시 보내지 않음(POST 1회 시험 유지). N1 고쳐짐: `find`가 보내기 시작부터 조회 시작까지 벽시계 경과 − 단조 경과가 `MAX_CLOCK_STEP` 2초를 넘으면 `Indeterminate`(KisOrderReconciler.java `find`, `Stamp.wallMinusMonoUntil`), 반영 대기는 응답 받은 순간부터 단조 경과로 셈(`sendEnded().monoUntil(day.queried())`). `KisOrderClient.unknown`의 조용한 시각 맞춤이 없어지고 `Unknown`·`Fingerprint`는 단조 순서만 검사. 시험 `KisOrderReconcilerTest#보낸_뒤_조회_전에_PC_시계가_단계로_바뀌면_찾음도_없음도_내지_않는다` 통과, 실제 경로 탐침 E1·E2 모두 모름. 2초 허용치는 틀려도 모름이 늘 뿐이고, 허용 안 차이(≤2초)는 60초 여유 안. 남은 경계(첫 쪽 요청 도중의 보정)는 탐침 E3·E4로 오차가 요청 시간 이하로 묶임을 확인(아래 G8, 판정에 넣지 않음) |
| A5 | 해당 없음 | 멱등 키 발급·영속·연결은 묶음 E. `Stamp` 단조 값의 영속·재기동 뒤 사용은 아래 G9 |
| A6 | 통과(이 PR 범위) | 블랙리스트·수동 보유 경로 무변경, 연결 없음. 특징 대조의 남의 주문 묶기 위험은 `byOrderNo=false`·D-145 위험 2(앞과 같음) |
| A7 | 통과(이 PR 범위) | `KisSession.stamp`는 잠금 없이 시계 두 개를 읽을 뿐, 세션 잠금은 여전히 `pace()`에만. `invalidateIfCurrent` 등 앞 재검토 내용 그대로 |
| A8 | 통과 | 엔진·게이트웨이·실험실 코드 무변경, 연결 없음 |
| A9 | 통과(이 PR 범위) | 보호 시세·서버 측 손절 무변경 |
| A10 | 통과 | 새 시험도 자리 표시자만. 새 `Indeterminate` 사유는 바뀐 초 수만 담음 |
| A11 | 통과 | 보호 시험 무변경, 고정 값 변경 없음. N1 두 방향(뒤로·앞으로)과 허용치 안 경우를 고정하는 시험이 생김. 다만 `OrderFakes.MutableClock.stepWall`을 만들고 쓰는 시험이 없어 실제 경로(세션 → 클라이언트 → 조회) 시험은 없다(탐침 E1·E2로 대신 확인, 아래 G10) |
| A12 | 통과 | 장애표 4번(broker-and-fault-design.md:163 "미접수일 때만 재발주")의 전제("없음"이면 정말 없음)가 N1 입력(보내기~조회 사이 PC 시계 단계 보정)에서 지켜짐(탐침 E1·E2). 남은 전제(반영 지연 30초, 게이트웨이 `Date`↔주문시각 시계 차이)는 D-145 연결 조건 1·2로 실측 전 `NotFound` 재발주 금지 그대로 |
| A13 | 통과 | 국내 전용, 날짜 `Asia/Seoul`(`Fingerprint.tradingDate` = POST 시작 벽시계의 한국 날짜), 가격 원 정수 |
| A14 | 통과 | D-145 위험 1이 "PC 시계가 보낸 때와 조회한 때 같은 만큼 어긋나 있으면 창이 따라 옮겨지고, 그 사이 단계로 바뀌면 단조 시계와 견줘 모름"으로, 클래스 주석·`MAX_CLOCK_STEP` 주석도 같은 뜻으로 고쳐져 코드와 일치. "검토 반영" 절에 N1과 고친 방법·시험·2초 `[추론]`이 적힘. 첫 쪽 요청 도중 보정의 한계(G8)는 문서에 없으나 오차가 60초 여유 안에 묶여 안전 성질 서술을 뒤집지 않음 |

## 앞 지적 처리 확인
| 항목 | 처리 | 확인 |
|---|---|---|
| N1 보내기~조회 사이 PC 시계 단계 보정 | 고침 | 코드, 시험, 실제 경로 탐침 E1(뒤로)·E2(앞으로) |
| G6 | 기록만(변경 필요 없음) | — |
| G7 `KisDailyOrders` 공개 생성 | 이월 | 이번에 `Stamp`·`KisOrderResult.Unknown`도 공개 생성자라 연결 코드가 임의 시각을 넣을 수 있다. 연결 PR에서 `find`·`Fingerprint.of`에 넘기는 값이 `KisAccountQueries`·`KisOrderClient`가 만든 것뿐인지 다시 본다 |
| G2·QA Q3·Q4 | D-145 연결 조건 3·4 | 연결 PR 승인 조건으로 다시 본다 |

## 점검표 밖 발견

### 수정 필요
- 없음.

### 권고(판정에 넣지 않음)
- **G8. 첫 쪽 요청 도중의 시계 보정.** 단계 검사는 `sendStarted`~`day.queried()`(첫 쪽 요청 직전)만 본다. 시계 차이 하한은 첫 쪽 응답 받은 벽시계(`receivedAt`, KisAccountQueries.java `pages`의 `session.clock().instant()`)로 재므로, 그 사이(토큰 발급·만료 재호출 포함, HTTP 시간 상한 10초 × 몇 번) 뒤로 보정되면 하한이 min(보정 폭, 요청 시간)만큼 크게 잡혀 창 시작이 그만큼 늦어진다(탐침 E4: 15초). 지금은 `ServerClock`의 `requestedAt` 맞춤과 요청 시간 상한 덕에 60초 여유 안이지만, 그 여유는 게이트웨이↔주문시각 시계 차이용 `[가정]`이라 둘이 겹치면 모자랄 수 있다. 연결 전에 `receivedAt`도 `Stamp`로 남겨 `sendStarted`~`receivedAt` 단계도 같은 2초로 검사하기를 권한다(한 줄 수준).
- **G9. 단조 값은 한 JVM 안에서만 뜻이 있다**(`System.nanoTime` 명세, `Stamp` 주석에도 적힘). 묶음 E가 `Unknown`·`Fingerprint`를 저장소에 남겨 재기동 뒤 PANIC 대사에 쓰면 단조 경과가 무의미해진다. 재부팅 뒤라면 대개 큰 차이로 모름이 되지만(안전 쪽), 같은 부팅 안 재기동은 OS 구현에 따라 우연히 맞을 수도 있다 `[추론]`. 연결 PR에서 "다른 프로세스에서 만든 `Stamp`로는 `NotFound`를 내지 않음"(예: 프로세스 식별값을 함께 남기고 다르면 모름)을 정하고 시험한다.
- **G10.** `OrderFakes.MutableClock.stepWall`을 쓰는 실제 경로 시험(탐침 E1·E2와 같은 입력)을 더하면 `KisSession.stamp`·`KisAccountQueries`의 `Stamp` 전달이 바뀌어도 잡힌다.

## 판정
승인
