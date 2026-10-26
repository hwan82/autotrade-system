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
