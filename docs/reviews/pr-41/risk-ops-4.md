판정: 수정 필요(F7 남의 재발급으로 쉬는 동안 늦게 온 성공 응답이 상태를 OK로 되돌려, 10분 동안 모든 호출이 실패하는데 플러그인이 ACTIVE로 보임. D-053 4번·ADR-003 4장 "그동안 DEGRADED" 불일치)

# risk-ops 재검토(4차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 브랜치 `claude/project-thread-v8cx8m` / 커밋 `ebb0df0` / `git diff origin/main...ebb0df0` 전체(29개 파일, 코드 17개는 이전과 같은 목록). 처음 요청은 `7f0a879` 대상이었고, 검토 중 메인이 대상을 `ebb0df0`으로 바꿨다. 이전 원문 `risk-ops-3.md`(대상 `08da864`, 수정 필요 F3) 뒤의 코드 변경은 `7f0a879`(F3·architect F5 반영)와 `ebb0df0`(architect F6 반영) 두 커밋이고, 둘 다 `TossRestClient.java`·`TossRestClientTest.java`·결정 문서만 바꿨다. 그 두 커밋만이 아니라 diff 전체와 호출 경로(`TokenCache`, `TossPlugin.status`, 폴링 소스, 앱 모듈 연결 여부)를 다시 봤다.
- 점검표: A
- 검토자: risk-ops 서브에이전트, 2026-10-10
- 파일:라인은 모두 커밋 `ebb0df0` 기준이다.
- 직접 실행한 것
  - 작업 폴더와 분리한 git worktree(scratchpad)에서 `./gradlew clean build --no-daemon`을 두 번 돌렸다.
    - `7f0a879`: EXIT 0, JUnit XML 합계 633건, 실패·오류·건너뜀 0.
    - `ebb0df0`: EXIT 0, JUnit XML 합계 634건, 실패 0·오류 0·건너뜀 0. ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8 통과. `autotrade-risk` 5개 클래스 55건, `autotrade-app-engine` 5개 클래스 48건, `autotrade-architecture-test` 28건 모두 통과. 토스 86건(TossRestClientTest 67 포함) 통과.
  - GitHub check-run: `ebb0df0` DB schema success, Secret scan and collector boundary success, Gradle build는 이 원문을 쓰는 시점에 진행 중(`in_progress`). `7f0a879`의 Gradle build는 다음 푸시로 취소(cancelled)됐다. 로컬 전체 빌드로 판정했다.
  - `git diff origin/main...ebb0df0 -- '*Test*.java'` 삭제 줄 0. `.java`에 `@Disabled` 없음.
  - 임시 탐침 `ProbeRiskOps4Test`(worktree에만 넣고 돌린 뒤 지움, 커밋 안 함) 두 가지.
    1. 동시 끊김 반복: 가짜 서버는 "마지막으로 발급된 토큰만 유효, 나머지는 401 `token-revoked`". 실제 스레드 2개가 `tok-1`로 동시에 나가 있는 동안 배리어에서 다른 프로세스가 `foreign-1`을 받고, 두 응답에 0~3µs 무작위 지연을 준다. 20,000회씩 3번.
       - `7f0a879`: 상대 토큰을 끊은 경우 15·8·1회(합 24/60,000). 버리기(`dropIfCurrent`)와 쉼 걸기(`startRevokedCooldown`) 사이 틈에 다른 요청이 EMPTY → 쉼 없음 → 새 토큰을 받았다. architect-4 F6과 같은 결함이다.
       - `ebb0df0`: 3번 모두 추가 발급 0, 상대 토큰 끊김 0, 두 요청 모두 `TOKEN_REVOKED`, 끝난 뒤 상태 OK 0. F6 해소를 확인했다.
    2. 늦게 온 성공: 요청 R(`tok-1`)을 서버가 남의 재발급 전에 처리했고, 그 응답이 오기 전에 요청 S(`tok-1`)가 `token-revoked`를 받는 순서(기존 `#동시에_끊긴_…` 테스트와 같은 끼어들기 방식). 3번 모두 같은 결과: S 뒤 health DEGRADED → R의 200 뒤 health **OK** → 다음 호출은 입구에서 `TOKEN_REVOKED`로 실패하는데 health는 계속 **OK**. F7 근거.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·`autotrade-risk`·설정 로딩 변경 없음. RealTradingGuardTest 31/31 통과. 토스 모듈은 Guard를 참조하지 않는다 |
| A2 | 통과 | `TossPlugin.java:100-104` `orderGateway`는 항상 `Optional.empty()`. `TossRestClient`는 GET만 보낸다(`TossRestClient.java:244-253` `send`의 `http.get`), `http.post`는 토큰 발급(`TossTokenClient.java:57`)뿐. ArchUnit 규칙 변경 없음(`ModuleOwnershipTest.OWNERS` 한 줄 추가뿐), ArchitectureTest 15/15·GuardedOrderGatewayTest 8/8 통과 |
| A3 | 통과 | 능력은 `POLLING_QUOTE`만(`TossPlugin.java:76`), 주문 출구와 증권사 간 주문 전환 코드 없음 |
| A4 | 해당 없음 | 주문 코드 없음 |
| A5 | 해당 없음 | 주문 코드 없음 |
| A6 | 해당 없음 | 주문·청산 경로 없음. 계좌·자산·주문 경로는 허용 목록 밖이라 요청 전에 거절(`TossRestClientTest#계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다` 15건 통과). 이번 두 커밋은 허용 목록을 바꾸지 않았다 |
| A7 | 해당 없음 | 계좌 주문 큐·계좌별 토큰 버킷을 건드리지 않는다. `TossRateLimiter`는 시세 조회 그룹 제한기이고 이번 두 커밋에서 바뀌지 않았다 |
| A8 | 통과 | 어느 앱 모듈도 이 모듈에 의존하지 않는다(`settings.gradle.kts:24`, `ModuleOwnershipTest.java:113`뿐, `META-INF/services` 없음). 엔진·게이트웨이 변경 없음, `:autotrade-app-engine:test` 48건 통과. 새 잠금 `tokenLock`은 이 호출기 안에서만 쓰이고 잠금 순서가 한 방향(tokenLock → TokenCache → lock, `TossRestClient.java:111-115`, `lock`을 잡은 채 `tokenLock`을 잡는 코드 없음)이라 교착 경로를 찾지 못했다 |
| A9 | 실패 | 남의 재발급 때 상대 토큰을 끊는 문제(이전 F3, architect F5·F6)는 해소됐다(탐침 1, 아래 이전 발견 처리). 그러나 쉬는 시간이 시작된 뒤 늦게 온 성공 응답이 `health = OK`(`TossRestClient.java:207`)로 되돌리고, 쉬는 동안 입구 거절(`:165-172`)은 상태를 DEGRADED로 다시 바꾸지 않는다. 그래서 10분 동안 현재가 폴링이 모두 `TOKEN_REVOKED`로 실패하는데 `TossPlugin.status()`(`TossPlugin.java:67-71`)는 ACTIVE를 낸다. 소스 선택은 DEGRADED만 뺀다(ADR-003 `:51`). 연결 뒤 이 플러그인이 보호 폴링(D-024, 장애표 #2)으로 골라지면 10분 공백이 생기고 다른 소스로 바뀌지 않는다(F7) |
| A10 | 통과 | 새 테스트의 토큰은 `tok-1`·`tok-2` 시험값. `currentToken`(`TossRestClient.java:116-117`)·`Sent.token`은 비교에만 쓰이고 로그·예외에 넣는 코드가 없다. 발급기 쉼 차단 예외(`:327-331`)도 토큰 값을 싣지 않는다. 로깅 코드 없음. `.gitleaks*`는 이번 두 커밋에서 바뀌지 않음, CI Secret scan success |
| A11 | 실패 | 보호 테스트 삭제·`@Disabled` 없음. 새 동작 테스트: `#동시에_끊긴_요청_둘은_한_번만_쉬고_쉬는_동안_새_토큰을_받지_않는다`(`TossRestClientTest.java:302`), `#쉬는_동안에는_발급기가_새_토큰을_받지_않는다`(`:352`), 기존 `#자기_재발급으로_끊긴_…`(`:245`)·`#새_토큰으로_다시_보낸_요청도_끊기면_…`(`:367`) 통과. 다만 `:302` 테스트는 한 스레드 안 끼어들기라 F6 틈(버리기와 쉼 걸기 사이)을 직접 재현하지 않는다(탐침 1로 따로 확인, 아래 N6). 그리고 "쉬는 동안 성공 응답이 와도 DEGRADED 유지" 실패 경우 테스트가 없다(F7) |
| A12 | 실패 | 장애표 6장 17종·O1~O8·장애 테스트 8종 문서는 바뀌지 않았고 플러그인은 미연결이라 지금 실행 동작은 같다. 그러나 이 모듈의 상태 보고는 연결 뒤 장애표 #2(보호 폴링)·#15(증권사 DEGRADED 전환) 감지의 입력이다. 쉬는 동안 ACTIVE로 보고하는 것은 그 감지 전제를 깬다(F7). 대응표·테스트는 함께 바뀌지 않았다 |
| A13 | 통과 | 시장·시간·금액 처리 변경 없음. 폴링 소스는 이번 두 커밋에서 바뀌지 않았다 |
| A14 | 실패 | 결정 로그 요약(`docs/decisions/README.md:55`)과 D-053 4번(`docs/decisions/D-053-toss-rest-read-only-client.md:12`)은 F5 문구("쉬는 동안 어떤 경로로도 새 토큰을 받지 않고, 같은 토큰으로 진행 중이던 다른 요청이 끊겨도 다시 보내지 않는다")를 반영해 코드와 맞는다(이전 F3의 A14 사유 해소). 그러나 D-053 4번 "그동안 플러그인은 DEGRADED다"와 ADR-003 4장(`docs/adr/ADR-003-market-data.md:33` "10분 쉬고 … 그동안 DEGRADED")은 코드와 다르다(F7) |

## 이전 발견 처리

- **이전 F3(risk-ops-3.md, 남의 재발급 때 동시 진행 요청이 쉼 중 재발급)**: 해소. `7f0a879`가 `dropIfCurrent`를 DROPPED·REPLACED·EMPTY 세 갈래로 나누고(`TossRestClient.java:266-281`), EMPTY면 쉬는 중일 때 다시 보내지 않으며(`:189-197`), 발급기가 쉬는 동안 발급을 막는다(`:327-331`). `7f0a879`에는 버리기와 쉼 걸기 사이 틈이 남아 있었고(탐침 1에서 24/60,000회 상대 토큰 끊김, architect-4 F6과 같은 결함), `ebb0df0`이 쉼 걸기를 `tokenLock` 안 버리기 앞으로 옮겨(`:274-276`) 닫았다(탐침 1에서 0/60,000회). risk-ops-3 수정안 (가)의 세 갈래·재전송 전 쉼 확인·쉬는 중 발급 금지는 반영됐고, "쉬는 동안 성공 응답이 health를 OK로 되돌리지 않게"는 반영되지 않았다(F7).
- **이전 N4(TokenCache 내부 잠금 의존)**: 해소. `tokenLock`을 따로 두고(`:111-115`) `send`·`dropIfCurrent`가 그 잠금을 쓴다.
- **이전 F1·F2**: 이번 두 커밋은 `TossRateLimiter`·`TossPollingQuoteSource`·`withRefreshAtHalfLife`를 바꾸지 않았다. 해소 상태 유지.
- **R7~R9·N5(연결 전 조건 기록)**, **N1(폴링 소스 `MAX_RATE_WAIT` 단위 테스트)**: 아직 반영 안 됨. 유지(이번 판정 사유 아님).

## 점검표 밖 발견

### 고쳐야 할 것

**F7. 남의 재발급으로 쉬는 동안 늦게 온 성공 응답이 상태를 OK로 되돌리고, 쉬는 10분 내내 ACTIVE로 보인다.**
- `[사실]` 쉼은 `startRevokedCooldown`(`TossRestClient.java:290-295`)에서 `health = DEGRADED`로 건다. 성공 응답은 쉼 여부를 보지 않고 `health = Health.OK`(`:207`)로 바꾼다. 쉬는 동안 입구 거절(`:165-172`)은 예외만 던지고 상태를 바꾸지 않는다.
- `[사실]` 탐침 2(`ebb0df0`, 3회 같은 결과): R(`tok-1`, 서버가 남의 재발급 전에 처리) 응답 전에 S(`tok-1`)가 `token-revoked` → DEGRADED. 이어 R의 200 → OK. 다음 호출은 `TOKEN_REVOKED`로 실패하는데 상태는 OK. `TossPlugin.status()`(`TossPlugin.java:67-71`)는 OK를 ACTIVE로 바꾼다.
- `[추론]` 같은 그룹 요청이 동시에 나가면 응답 순서는 서버 처리 순서와 다를 수 있으므로 드문 경우가 아니다(한 요청이 느리기만 하면 된다). 연결 뒤 소스 선택(ADR-003 `:51`, DEGRADED만 뺀다)이 토스를 계속 골라 보호 폴링이 최대 10분 비고, 장애표 #15의 DEGRADED 전환 감지가 일어나지 않는다. 지금은 미연결이라 실행 영향은 없지만 D-053·ADR-003이 약속한 계약("그동안 DEGRADED")을 어기고 이전 F1·F2·F3과 같은 이유(이 클래스의 계약이 main에 들어간다)로 이 PR에서 고친다. risk-ops-3 F3 수정안 (가)에 이미 포함됐던 항목이다.
- 수정(대안 2개):
  - (가, 추천) 상태를 계산식으로 바꾼다: `health()`가 쉬는 중(`revokedUntil`이 지금 이후)이면 DEGRADED를 돌려준다. 성공 응답의 `health = OK`는 그대로 둬도 쉬는 동안에는 DEGRADED로 보인다. 쉼이 끝나고 새 토큰으로 성공하면 OK. `health()`가 `lock`을 잡지 않도록 `revokedUntil`을 `volatile`로 하거나 별도 volatile 사본을 둔다(지금 "블로킹하지 않는다" 계약 유지).
  - (나) 성공 경로(`:207`)에서 쉬는 중이면 OK로 바꾸지 않고, 입구 거절(`:165-172`)에서도 DEGRADED를 다시 건다. 바꾸는 곳이 둘이라 빠뜨리기 쉽다.
  - 테스트: 기존 `#동시에_끊긴_…`과 같은 끼어들기로 "끊긴 응답 뒤 성공 응답" 순서를 만들고, 성공 뒤와 다음 입구 거절 뒤 모두 `health()`가 DEGRADED, 쉼이 끝나고 새 토큰 성공 뒤 OK인지 확인한다. `TossPluginTest`에 같은 순서에서 `status()`가 DEGRADED인지 한 건.
  - 버린 대안: 연결 PR(R1 역할별 정책)로 미루기. 상태 보고는 역할과 무관한 이 클래스의 계약이고 문서가 이미 DEGRADED라고 적었다.

### 권고(이번 PR에서 안 고쳐도 됨)
- N6. F6 틈(버리기와 쉼 걸기 사이)은 한 스레드 끼어들기 테스트로는 재현되지 않는다. 지금 구조(쉼 걸기가 `tokenLock` 안, 발급도 `tokenLock` 안)는 코드 읽기와 탐침 1로 맞다고 봤지만, 회귀 방지로 실제 스레드 2개와 배리어를 쓰는 반복 테스트(예: 2,000회, 상대 토큰 끊김 0)를 두길 권한다. 탐침 1 구조를 그대로 쓰면 된다.
- N7. EMPTY 갈래(`:189-197`)는 "먼저 버린 요청이 `expired-token`·`invalid-token`이었고 이 요청은 `token-revoked`"인 경우 쉬지 않고 새 토큰을 받는다. 같은 토큰이 동시에 만료와 남의 재발급으로 끊기는 경우라 드물지만, 그때는 상대 토큰을 끊는다. 이 요청 자신이 `token-revoked`면 EMPTY여도 쉼을 거는 쪽(예: `token-revoked`면 EMPTY에서 다시 보내지 않고 실패)이 D-053 4번 취지에 더 가깝다. 연결 PR 전 R1 정책과 함께 정해도 된다.
- N5·N1(이전 권고) 유지.

### 확인한 좋은 점
- 이전 F3 수정안을 세 갈래 판정과 발급기 쉼 차단 두 겹으로 넣었고, architect F6 지적에 쉼 걸기를 같은 잠금 안으로 옮겨 실제 동시 실행에서 상대 토큰 끊김이 0이 됐다.
- `TokenCache` 내부 잠금 의존을 없앴고 잠금 순서를 주석으로 적었다.
- 401 재처리는 여전히 최대 한 번이고 429는 자동 재시도하지 않는다. 계좌·주문 경로 거절과 GET 전용 경계는 그대로다.

## 판정
수정 필요(F7: 남의 재발급으로 쉬는 동안 늦게 온 성공 응답이 상태를 OK로 되돌려 10분 동안 ACTIVE로 보임, 테스트 없음, D-053 4번·ADR-003 4장과 불일치. A9·A11·A12·A14 실패). 확인 못 함 항목은 없다. F7 수정 커밋은 같은 검토자에게 다시 맡긴다. 이 PR은 지침 10장에 따라 risk-ops 승인 뒤에도 독립 QA를 거쳐 사용자가 직접 확인하고 머지한다.
