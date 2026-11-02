# risk-ops 검토: 한국투자 실전 주문·계좌 조회 클라이언트(D-145, 7단계 묶음 D-H 1차) [실전 관련]
- 대상: PR #247 / 브랜치 `claude/project-thread-truegb` 커밋 `e5b3969b` / main(`53482a56`) 대비 diff 전체(28파일, +1942 −21)
- 점검표: A
- 직접 실행한 것
  - `./gradlew build --no-daemon`(검토자 임시 미러 init 스크립트, 저장소 밖): **BUILD SUCCESSFUL**(6분 11초). 결과 XML 집계: `:autotrade-broker-kis:test` 63건, `:autotrade-risk:test` 57건, `:autotrade-architecture-test:test` 47건(새 `증권사_주문_코드는_플러그인_밖에서_쓰지_않는다`와 위반 예제 단언 포함), `:autotrade-app-engine:test` 109건, 실패·오류·건너뜀 모두 0.
  - 임시 탐침 시험 `ReviewProbeTest`(검토자가 `autotrade-broker-kis` 시험 폴더에 잠시 넣고 `:autotrade-broker-kis:test --tests '*ReviewProbeTest'`로 돌린 뒤 지움, 커밋하지 않음). 결과는 아래 R1~R3 근거에 그대로 옮김.
  - 읽은 것: diff 전체, `KisPlugin`·`KisRestClient`·`KisErrorCode`·`KisTokenClient`(가시성 변경 영향), `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 범위 계산, `autotrade-broker-kis`에 의존하는 모듈(`app-collector`·`app-gateway`·`app-experiment`), D-136 후속 고침 1번(이 PR이 근거로 든 선례).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·`RealOrderGateways`·명령 채널 코드 변경 없음(diff에 `autotrade-risk`·`autotrade-app-engine` 파일 없음). `KisPlugin.orderGateway`는 빈 값 그대로(KisPlugin.java 주석만 바뀜). Guard 관련 시험(`:autotrade-risk:test` 57건, `:autotrade-app-engine:test` 109건) 통과 |
| A2 | 통과 | 기존 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 규칙 본문 불변(주석만 바뀜, ArchitectureTest.java:160-170). 새 규칙 `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN`(ArchitectureTest.java:172-189)이 세 플러그인의 `order..` 패키지에 대한 플러그인 밖 의존을 막고, 위반 예제 `BypassPluginOrderClient`가 생성·호출·`KisSession`·`KisAccountNumber` 의존 모두로 잡힘을 단언(ArchitectureTest.java:525-533) — 실행 통과. 플러그인 패키지를 빌린 다른 모듈 클래스는 `ModuleOwnershipTest` OWNERS(`com.autotrade.broker.kis` → `autotrade-broker-kis`)가 막는다. 주문 클라이언트는 운영 코드 어디서도 생성되지 않음(호출처 0). 남은 한계는 권고 G2 |
| A3 | 통과 | `KisOrderClient`는 생성자에서 받은 계좌(`KisAccountNumber`) 하나로만 보내고(KisOrderClient.java:236-275) 다른 증권사로 넘기는 코드 없음. KRX가 아니면 보내기 전에 거절(`krxCode`, `KisOrderClientTest#KRX가_아니거나_원_단위가_아닌_주문은_보내기_전에_막는다`) |
| A4 | **실패** | 클라이언트 자체는 어떤 결과에도 다시 보내지 않음(`KisOrderClientTest#응답_시간_초과면_모름이고_다시_보내지_않는다`, 토큰 만료·한도 초과도 POST 1회). 그러나 "미접수 확인"을 내는 `KisOrderReconciler.find`와 그 입력 `KisAccountQueries`에 잘못된 "없음(NotFound)"·"찾음(Found)"을 내는 경로가 있고, 주문 응답 해석에 접수됐을 수 있는 응답을 "접수 안 됨(확정)"으로 내리는 경로가 있다 → R1·R2·R3 |
| A5 | 해당 없음 | 이 PR은 멱등 키를 발급·영속하지 않고 어느 프로세스에도 연결하지 않는다(D-145 "안 한 것" 첫 줄, `KisPlugin.orderGateway` 빈 값). 한국투자 주문 입력에 멱등 키 칸이 없어 키↔주문번호 결합은 `claimed` 집합으로 호출자가 넘기는 구조(KisOrderReconciler.java:103-116). 키 영속(B4)과 연결은 묶음 E에서 다시 본다 |
| A6 | 통과(이 PR 범위) | 블랙리스트·수동 보유 검사는 Guard 출구 앞 리스크 체인 몫이고 이 PR은 그 경로를 바꾸지 않음. 단 R1이 고쳐지지 않으면 묶음 E에서 사용자가 HTS로 낸 주문을 우리 주문으로 묶어 취소·청산 대상으로 삼을 수 있어(D-145 위험 2) A6과 이어진다 |
| A7 | 통과(이 PR 범위) | 계좌별 클라이언트는 `synchronized`(KisOrderClient.java:242,261), 앱키 단위 세션이 0.1초 간격을 강제(KisSession.java:125-135, `KisOrderClientTest#호출_사이_0_1초_간격을_지킨다`). 세션 잠금을 HTTP 호출 동안 쥐는 점은 권고 G3 |
| A8 | 통과 | 실전 엔진·게이트웨이·실험실 코드 변경 없음. 새 코드는 어느 프로세스에도 연결되지 않음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음(D10·D2는 범위 밖으로 명시, D-145 "안 한 것") |
| A10 | 통과 | 시험 계좌번호는 `"0".repeat(8)+"-01"`, 키는 `APP-KEY-PLACEHOLDER`(OrderFakes.java:21-24). `KisAccountNumber.toString()`은 가림, 형식 오류 메시지에 값 없음(KisAccountNumber.java). 결과·예외 메시지에 응답 원문·주소·계좌 없음(`KisOrderClientTest#결과와_계좌번호_문자열에_계좌번호와_키가_없다`, `KisAccountQueriesTest#결과_코드_없는_응답은_실패이고_메시지에_계좌번호가_없다`). `git diff`에서 실값 패턴 없음 |
| A11 | 통과 | 보호 테스트(`ArchitectureTest`)는 규칙·시험·위반 예제를 **더하기만** 했고 기존 단언 수(예: 우회 예제 4건, 명령 채널 8건) 불변. 고정 값(지문·허용 목록·소유 표·`EXTERNAL_ALLOWLIST`) 변경 없음. 새 동작에는 실패 경우를 포함한 시험(33건)이 있다. 다만 R1~R3의 실패 경우(불완전 조회의 단일 후보, 반영 대기 전 단일 후보, `output1` 없음, 원주문 `orgn_odno` 채움, `pdno` 형식 차이, 5xx+오류 코드)는 시험이 없다 |
| A12 | **실패** | 장애표 4번(주문 타임아웃·응답 유실: 재주문 금지, 조회로 접수 여부 확인)의 전제는 "조회가 '없음'이라 말하면 정말 없다"이다. R1·R2가 그 전제를 깨는 입력을 보였다. 표·장애 테스트 문서 변경은 필요 없고 코드가 전제를 지키게 고쳐야 한다 |
| A13 | 해당 없음 | 국내(KRX) 전용. 시각은 `Asia/Seoul`로 해석(KisOrderReconciler.java `SEOUL`), 금액은 원 정수(`won`) |
| A14 | 통과 | D-145 결정 기록·결정 로그 README 행·Q-015 확인 항목 추가·`kis-open-api-spec.md` 9장 갱신이 코드와 일치(TR·경로·결정 1~8). 단 D-145 위험 1의 "틀리면 대부분 '모름'으로 기운다"는 R2 입력에서는 성립하지 않음 — 고친 뒤 문장을 맞춘다 |

## 점검표 밖 발견

### 수정 필요

**R1. 특징 대조의 "찾음"이 조회 완전성·반영 대기를 보지 않는다** (KisOrderReconciler.java `find`, 후보 하나면 바로 `Found`를 돌려주고 `complete`·반영 대기 검사는 그 뒤에만 있음)
- 탐침 결과: `complete=false, unreadableRows=3`인 조회에 후보 하나 → `Found(byOrderNo=false)`. 조회 시작이 보내기 시작 1초 뒤(반영 대기 전)인데 창 안(시계 오차 60초)에 같은 특징 주문 하나 → `Found`.
- 문제: 이 클래스의 원칙은 "후보 둘 이상이면 모름"이다. 읽지 못한 행·못 받은 쪽에 두 번째 후보가 있을 수 있고, 반영 대기 전이면 우리 주문이 아직 안 보이는 사이 창 안의 다른 주문(사용자 HTS 주문, 시계 오차 창 앞쪽의 직전 주문)이 유일한 후보가 된다. 그러면 남의 주문을 우리 멱등 키에 묶고, 우리 주문은 어디에도 묶이지 않은 채 살아 있다(추적 밖 포지션, 묶음 E에서 취소·청산 대상 오인 → A6).
- 고칠 것: 특징 대조로 `Found`를 내려면 `NotFound`와 같은 조건(조회 완전, 같은 날, 조회 시작 ≥ 보내기 시작 + 요청 시간 상한 + `SETTLE`)을 요구하고, 아니면 `Indeterminate`. 두 경우의 시험을 더한다.

**R2. "없음"이 해석 가정 칸이 틀리거나 비었을 때 나온다** (KisAccountQueries.java `dailyOrders`·`balance`의 `body.path("output1")`, KisOrderReconciler.java `find`의 `isCancelOrModify` 선행 필터와 종목코드 비교)
- 탐침 결과
  - 응답에 `output1` 칸이 없으면(이름이 다르거나 빠짐) `dailyOrders`는 `complete=true, rows=0`. 잔고도 `output1` 없이 `output2`만 있으면 `holdings=[]`, `complete=true`. 이 조회로 `find`를 부르면 반영 대기 뒤 `NotFound` → 재발주 허용(중복 주문). 빈 잔고 "완전"은 묶음 E 대사·PANIC에서 "보유 없음"으로 읽힌다.
  - 원주문 행의 `orgn_odno`가 자기 주문번호로 채워져 오면(공식 문서에 없는 `[가정]`, D-145 위험 1이 스스로 Q-015로 올린 항목) 우리 주문이 취소 행으로 분류돼 걸러지고, 결정 5 ④(같은 종목·방향 새 주문 0건)도 취소 행을 먼저 뺀 뒤 세므로 `NotFound`.
  - 행의 `pdno`가 다른 형식(예: `A005930`)으로 오면 같은 종목 행이 0건으로 세져 `NotFound`.
- 문제: 결정 5 ④는 "칸 해석이 틀려도 모름으로 돌린다"는 안전장치인데, ④가 바로 그 가정 칸(`orgn_odno`, `pdno`)과 `output1` 존재에 기대고 있어 해석이 틀리면 안전장치도 함께 꺼진다. 틀린 "없음"은 지침 4장 2항 위반(중복 주문)이다.
- 고칠 것: ① `output1`(잔고는 `output1`·`output2` 모두)이 배열·객체가 아니면 그 쪽을 읽지 못한 것으로 세어 `complete=false`. ② ④의 판단은 가정 칸에 기대지 않게: 예컨대 창 안에 묶이지 않은 행이 **종목·취소 여부와 상관없이** 하나라도 있으면 `Indeterminate`로 두거나, 최소한 ④를 셀 때 취소 행 분류를 쓰지 않고 `orgn_odno == odno` 행은 원주문으로 보며, 종목코드는 끝 6자리 같은 정규화로 비교한다(어느 쪽이든 고른 이유를 D-145에 적는다). ③ 각 입력의 시험을 더한다. 실측(Q-015)으로 칸 형식이 확인되면 그때 좁힌다.

**R3. HTTP 5xx에 실린 "그 밖의" 오류 코드를 "접수 안 됨(확정)"으로 내린다** (KisOrderClient.java `interpret`: `rt_cd`가 0이 아니면 HTTP 상태와 상관없이 `NotAccepted`, `default -> BROKER_REJECTED`)
- 탐침 결과: HTTP 502, 본문 `{"rt_cd":"1","msg_cd":"EGW00203"}`(게이트웨이 라우팅 오류 꼴) → `NotAccepted(BROKER_REJECTED)`.
- 문제: 한도 초과(`EGW00201`·`EGW00215`)·토큰 오류(`EGW00121~123`)는 게이트웨이가 원장 앞에서 막은 것이라 "확정"이 맞다. 그러나 그 밖의 게이트웨이 5xx 오류(라우팅·내부 오류·처리 시간 초과)는 원장에 닿은 뒤일 수 있어 사실상 응답 유실이다. 이를 "접수 안 됨"으로 확정하면 상태가 거부로 닫혀 실제 살아 있는 주문이 추적 밖에 남거나, 묶음 E가 새 키로 다시 보내 중복이 된다. 근거로 든 D-136 후속 고침 1번은 뉴스 **조회**(GET) 재시도 규칙이라 주문 POST의 "확정 판정" 근거가 되지 않는다.
- 고칠 것: `rt_cd`가 0이 아니고 HTTP 상태가 200이 아니며 종류가 `RATE_LIMITED`·`TOKEN_EXPIRED`가 아니면 `Unknown`(조회 먼저). HTTP 200의 오류 코드(잔고 부족·호가 단위 등 원장 거절)는 지금처럼 `NotAccepted`. 결정 3 표의 "버린 대안" 줄도 이 구분으로 고친다(한도 초과 500이 조회를 쌓는 문제는 생기지 않는다). 시험을 더한다.

### 권고(이 PR 판정에 넣지 않음, 묶음 E 전 처리)
- **G1.** `KisTokenClient`(실전 키로 토큰을 발급하는 클래스)와 `fetcher(...)`가 `public`이 됐다(KisTokenClient.java). 패키지 `com.autotrade.broker.kis`는 새 ArchUnit 규칙의 보호 범위(`order..`) 밖이라, 이 모듈에 의존하는 게이트웨이·수집기·실험 프로세스가 60초 하한(`KisTokenGate`) 없이 같은 앱키 토큰을 발급할 수 있다(재발급 1분 1회 제한에 엔진 발급이 걸려 `NOT_SENT`, 알림톡 누적). `fetcher`를 `order` 패키지 쪽으로 옮기거나, 플러그인 밖에서 `KisTokenClient`를 쓰지 못하게 규칙에 더한다.
- **G2.** 실전 주문 코드가 이제 `app-gateway`·`app-collector`·`app-experiment`의 클래스패스에 있다(세 모듈이 `autotrade-broker-kis`에 의존). 바이트코드 의존은 새 규칙이 막지만, 이 프로세스들은 `RealPathReflectionTest` 범위 밖이라 리플렉션 생성은 막히지 않는다. 묶음 E에서 주문 패키지를 실전 엔진만 의존하는 별도 모듈로 나누는 안을 architect와 비교한다(D-145 결정 8 대안에 없음).
- **G3.** `KisSession.get/post`는 HTTP 요청이 끝날 때까지 세션 잠금을 쥔다(KisSession.java:88-101). 같은 앱키에서 일별 체결 10쪽 조회나 응답 없는 호출이 진행 중이면 PANIC 매도·손절 주문이 그 뒤에 줄 선다. 묶음 E 연결 때 잠금은 간격 계산에만 쓰고, 주문을 조회보다 먼저 보내는 우선순위와 대기 상한을 정한다.

## 판정
수정 필요(R1 특징 대조 "찾음"의 완전성·반영 대기 조건, R2 `output1` 없음·`orgn_odno`·`pdno` 해석 가정에서 나오는 "없음", R3 HTTP 5xx 기타 오류 코드의 "접수 안 됨" 확정 — 점검표 A4·A12 실패)
