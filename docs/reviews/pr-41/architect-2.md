판정: 수정 필요(F4 자기 재발급이 진행 중 요청을 token-revoked로 만들어 10분 막힘, F3 잔여 ADR-003 두 곳)

# architect 재검토: 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `cbf16e29e117022281a0294efd02bbecf5d83a0c`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD` 전체(24개 파일). 이전 원문 `docs/reviews/pr-41/architect.md`(커밋 `0518a94` 기준, 수정 필요 F1·F2·F3·B7 대기) 뒤의 코드 변경은 `cbf16e2` 한 커밋이다. 검토 도중 브랜치에 `22b9f03`(`docs/reviews/pr-41/risk-ops-2.md` 한 파일 추가)이 올라왔다. 코드·빌드에 영향 없는 검토 원문이라 다시 빌드하지 않았고 B7 근거로만 읽었다.
- 점검표: B
- 검토자: architect 서브에이전트, 2026-10-10
- 파일 위치: 호출자 지시에 따라 이전 원문 아래에 덧붙이지 않고 `architect-2.md`로 따로 썼다(docs/reviews/README.md "재검토는 같은 파일 아래" 규칙과 다름. 이전 원문은 고치지 않았다).
- 파일:라인은 모두 `cbf16e2` 기준이다.
- 직접 실행한 것
  - 작업 폴더에서 `./gradlew clean build --no-daemon`: BUILD SUCCESSFUL(1m 20s, 128 tasks), EXIT 0. 같은 폴더에서 다른 검토자의 Gradle이 동시에 `clean`을 돌려 JUnit XML이 지워졌으므로, `cbf16e2`를 별도 git worktree(scratchpad)에 꺼내 `./gradlew clean build --no-daemon`을 다시 돌렸다: BUILD SUCCESSFUL(1m 13s), EXIT 0. JUnit XML 합계 630건, 실패 0·오류 0·건너뜀 0. 이 중 토스 82건(TossRestClientTest 63, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 4, TossPluginTest 3), ArchitectureTest 15, ModuleOwnershipTest 13. `:autotrade-broker-toss:verifyPureDependencies` 실행·통과.
  - GitHub check-run(head `cbf16e2`): Gradle build success, DB schema (PostgreSQL 16) success, Secret scan and collector boundary success.
  - 임시 탐침 테스트 1건(`ProbeArchitectTest`)을 worktree의 토스 테스트 폴더에 넣어 돌린 뒤 지우고 worktree도 지웠다(커밋 안 함, 작업 폴더 `git status` 깨끗함). 결과는 F4 근거.
  - `git diff origin/main...HEAD -- '*Test*.java'`의 삭제 줄 0, `@Disabled` 추가 0.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 2회 성공(작업 폴더, 격리 worktree), 630건 실패 0. CI check-run 3종 success(`cbf16e2`) |
| B2 | 통과 | `cbf16e2`는 `autotrade-broker-toss` 안 소스·테스트와 문서만 바꿨다. 의존(`autotrade-broker-toss/build.gradle.kts`)·`ModuleOwnershipTest`·ArchUnit 규칙 변경 없음. `verifyPureDependencies`, ArchitectureTest 15/15, ModuleOwnershipTest 13/13 통과. 앱 모듈이 이 모듈에 의존하지 않는 것은 그대로(`settings.gradle.kts:24`뿐) |
| B3 | 통과(잔여 있음) | 공용 인터페이스·설정 키·스키마·REST API 변경 없음. 새 공개 메서드 `TossRestClient.get(String, Map, Duration)`(`TossRestClient.java:141`)은 모듈 내부 클래스의 추가이고 D-053 3번에 기록됐다. 이전 F3: ADR-003 2.1 토큰 문단(`docs/adr/ADR-003-market-data.md:33`)·토스 폴링 문단 뒤(`:47`)·4장(`:197`)에 D-053 날짜 메모가 붙었다. 다만 이전 F3이 지정한 3장 대안 표 "토스 폴링" 줄(`:134` "이 PR에서 빼고 연결 PR로")과 머리말 `:17`("토스 클라이언트는 … 연결 PR에서 넣는다")에는 메모가 없다(F3 잔여) |
| B4 | 통과 | 시뮬레이션·전략 코드 변경 없음, 골든 테스트 포함 `autotrade-sim:test` 통과. 새 코드의 시각은 주입 `Clock`만(`TossRateLimiter.java:91,108`, `TossRestClient.java:147,219,239,254`). 난수 없음 |
| B5 | 통과 | 금액 처리 변경 없음(`TossPollingQuoteSource.java:124-133` 문자열 decimal만 `BigDecimal`). `cbf16e2`의 폴링 소스 변경은 `MAX_RATE_WAIT` 전달(`:44,89`)뿐 |
| B6 | 통과 | 이전 지적마다 테스트가 생겼다: `TossRestClientTest#명세의_GET_조회_경로_표`(27건, `KR_BOND_3Y`·`USD_KRW`·`KR_SEMI-01` 포함), `#경로_변수_형식이_틀리면_거절한다`(7건), `TossRateLimiterTest#한_그룹이_쉬는_동안_잠금을_쥐지_않아_다른_그룹은_바로_지나간다`(실제 스레드·래치), `#대기_상한을_넘으면_자리를_잡지_않고_바로_실패한다`, `TossRestClientTest#다른_그룹의_한도_대기는_현재가_조회를_막지_않고_…`, `#수명이_짧은_토큰은_…`, `#반감기_조정은_…`. 삭제·`@Disabled` 없음. 단 F4(이번 수정이 만든 동시성 결함)를 잡는 테스트는 없다. 고칠 때 함께 넣는다 |
| B7 | 통과(수정 뒤 risk-ops 재검토 필요) | **risk-ops 검토 필요.** `docs/reviews/pr-41/risk-ops-2.md`(커밋 `22b9f03`, 대상 `cbf16e2`) 판정 "승인", A 점검표 14개 항목 실패·확인 못 함 없음. 다만 아래 F4는 보호 폴링(D-024, 장애표 #2) 가용성 결함이고 risk-ops 재검토에서 다뤄지지 않았다. F4 수정 커밋은 risk-ops에게 다시 맡겨야 한다. 지침 10장에 따라 이 PR은 사용자가 직접 확인하고 머지한다 |
| B8 | 통과 | `cbf16e2`에 비밀값·로그 호출 추가 없음. 새 예외(`TossRateLimiter.java:95-96`)는 그룹 대기 사실과 고정 코드 `local-wait-exceeded`만 싣는다. 테스트 토큰 값은 `tok-1` 같은 시험값. CI Secret scan success. `.gitleaks*`는 이전 검토 뒤 바뀌지 않았다 |

## 이전 발견 처리

- **F1(허용 목록 패턴): 해소.** 경로 변수별 패턴(`TossRestClient.java:58-62`: 종목 `(?!\.+(?:/|$))[A-Za-z0-9.\-]+`, 시장 지표 `[A-Za-z0-9_]+`, 섹터 `[A-Za-z0-9_\-]{1,40}`)이 명세 path pattern과 같다. 점만 있는 마디는 종목 패턴의 부정 전방 탐색으로, 지표·섹터는 점 자체를 받지 않아 막힌다. 허용 경로 목록(19개 패턴)과 그룹 매핑은 이전 검토에서 명세 JSON의 계좌 없는 GET 25개와 대조한 그대로이고 이번에 바뀐 것은 경로 변수 패턴뿐이다(`git show cbf16e2`). 계좌·주문 경로 거절 테스트 15건은 그대로 통과.
- **F2(한도 제한기 잠금): 해소.** `TossRateLimiter.acquire`는 `synchronized (this)` 블록 안에서 자리만 예약하고 잠자기는 블록 밖(`TossRateLimiter.java:87-103`). `TossRestClient.get`의 메서드 `synchronized`가 없어지고(`0518a94`의 `:124` → `cbf16e2`의 `:141`), 한도 대기와 HTTP 호출이 잠금 밖(`:201-210`)이다. 내가 추천한 (가)안 그대로다. 보호 폴링은 대기 상한 1초를 넘으면 자리를 잡지 않고 `RATE_LIMITED`로 실패한다(`TossPollingQuoteSource.java:44,89`).
- **F3(ADR-003 갱신): 대부분 해소, 잔여 2곳**(B3 참조).
- **N3(토큰 429 60초 하한 주석): 반영**(`TossRestClient.java:232-236`). **N6(엔드포인트 41개): 반영**(`docs/research/toss-openapi-spec.md:4`).
- **N1(토큰 소유 프로세스 외 생성 금지 강제)**은 Q-042(`docs/decisions/README.md:73`)와 ADR-003 `:197` 연결 전 조건에 아직 없다. 이번 판정 사유는 아니다(아래 N7).

## 점검표 밖 발견

### 고쳐야 할 것

**F4. 같은 프로세스의 정상 재발급이 진행 중인 다른 요청을 `token-revoked`로 만들고, 호출기가 그것을 "다른 곳의 재발급"으로 보고 10분 동안 모든 호출(보호 폴링 포함)을 막는다. 이번 수정(F2, 잠금 축소)이 새로 만든 결함이다.**
- `[사실]` 이전(`0518a94`)에는 `TossRestClient.get` 전체가 `synchronized`라 한 프로세스 안에서 HTTP 요청이 진행 중인 동안 토큰 재발급이 일어날 수 없었다. `cbf16e2`는 요청을 잠금 밖으로 옮겨 여러 스레드가 동시에 요청한다(그것이 F2 수정의 목적이다).
- `[사실]` 토스는 재발급 시 이전 토큰을 즉시 무효로 하고, 무효가 된 토큰은 401 `token-revoked`로 구분된다(`docs/research/toss-openapi-spec.md:12,37`).
- `[사실]` 호출기는 401 `token-revoked`를 받으면 그 요청이 어느 토큰을 썼는지 보지 않고 캐시를 무조건 비우고(`TossRestClient.java:217`, `TokenCache.invalidate`는 무조건 `cached = null`, `TokenCache.java:55-57`), 10분 동안 모든 호출을 막고(`:218-220`, `:145-152`), `health`를 DEGRADED로 둔다(`:221`).
- `[사실]` 탐침 테스트(서버가 "마지막에 발급한 토큰만 유효, 그 밖은 401 `token-revoked`"로 응답하는 가짜 HTTP): 스레드 A가 `tok-1`로 `rankings`를 보낸 채 응답을 기다리는 동안, 시계를 재발급 여유(만료 10분 전) 안으로 옮기고 다른 스레드가 `prices`(상한 1초)를 부르면 `TokenCache`가 정상 갱신으로 `tok-2`를 받고 성공한다. 그 뒤 A의 응답이 401 `token-revoked`로 돌아오자 A는 `TOKEN_REVOKED` 예외, `health`=DEGRADED, 이어지는 `prices` 호출은 유효한 `tok-2`가 있는데도 HTTP를 보내지 않고 `TOKEN_REVOKED`로 실패(9분 뒤에도 실패), 10분 뒤에야 `tok-3`을 새로 받았다(발급 3회). 모든 단언 통과.
- `[추론]` 토큰 수명 24시간이면 정상 갱신이 하루 1번 이상 일어나고, 그 순간 다른 그룹 요청이 하나라도 진행 중이면 보호 폴링이 10분 끊기고 플러그인이 DEGRADED가 된다. 수명이 짧은 토큰(반감기 재발급)이면 더 잦다. 같은 원인으로 401 `expired-token`·`invalid-token` 재시도 경로도 경합한다: 두 스레드가 같은 낡은 토큰으로 401을 받으면 A가 `tok-2`를 받은 뒤 B의 `invalidate()`가 `tok-2`를 버리고 `tok-3`을 받아 A의 재요청을 `token-revoked`로 만든다(`:156-158`). 정상 갱신 뒤 바로 버려지는 토큰이 같은 Client ID의 다른 프로세스 토큰도 불필요하게 끊는다(D-053이 막으려던 것).
- `[사실]` 지금은 어느 프로세스에도 연결되지 않아 실행 영향은 없다. 그러나 이 모듈의 목적이 보호 폴링(`POLLING_QUOTE`)이고 클래스 계약이 "그룹끼리 막지 않는 동시 호출"이 됐으므로, 잘못된 동작이 main에 계약으로 들어간다. 이전 F2와 같은 이유로 연결 PR로 미루지 않는다.
- 수정(대안 2개):
  - (가, 추천) 요청마다 보낸 토큰 값을 기억하고, 401(`token-revoked`·`expired-token`·`invalid-token`)을 받으면 "지금 캐시의 토큰이 보낸 토큰과 같을 때만" 버린다. 캐시가 이미 다른 토큰(이 프로세스가 그 사이 새로 받은 것)이면 `token-revoked`도 남이 밀어낸 것이 아니므로 쉬지 않고 현재 토큰으로 한 번만 다시 보낸다. 구현은 `TokenCache`에 `invalidateIf(String expected)` 같은 비교 후 비우기를 더하거나(`autotrade-marketdata` 공용 클래스에 메서드 추가, 기존 호출은 그대로), `guarded` 발급기가 마지막으로 발급한 토큰과 발급 순번을 `lock` 아래 기록하고 `send`가 그 순번을 함께 돌려받아 비교한다. 테스트: 위 탐침과 같은 시나리오에서 A는 `tok-2`로 한 번 다시 보내 성공하고, 10분 막힘·DEGRADED·추가 발급이 없어야 한다. 다른 스레드 동시 `expired-token` 두 건이면 발급 1회.
  - (나) 토큰 재발급 순간에만 진행 중 요청이 끝나기를 기다린다(읽기·쓰기 잠금: 요청은 읽기, 재발급은 쓰기). 경합은 사라지지만 느린 요청(타임아웃) 하나가 재발급과 그 뒤 모든 호출을 붙잡아 이전 F2와 비슷한 막힘을 되살린다. (가)보다 못하다.
  - 버린 대안: 그대로 두고 연결 PR에서 고치기. risk-ops 연결 조건 R1(역할별 `token-revoked` 정책)과도 겹치지만, R1은 "다른 프로세스가 밀어낸 경우"의 정책이고 F4는 자기 재발급을 남의 것으로 잘못 판정하는 판정 오류라 정책과 무관하게 지금 고쳐야 한다.

**F3 잔여. ADR-003 두 곳에 D-053 날짜 메모.** `docs/adr/ADR-003-market-data.md:17`("토스 클라이언트(보호 폴링·토큰)는 … 연결 PR에서 넣는다")과 3장 대안 표 `:134`("이 PR에서 빼고 연결 PR로")에 "(2026-10-10 D-053: 읽기 전용 모듈을 미연결 상태로 먼저 넣음, 연결은 Q-042 뒤)"를 덧붙인다. 앞 내용은 지우지 않는다.

### 권고(이번 PR에서 안 고쳐도 됨)
- N7. 이전 N1(토큰 소유 프로세스 밖에서 `TossPlugin.create`·`TossRestClient.create` 생성 금지를 ArchUnit 또는 기동 검사로 강제)을 Q-042 또는 ADR-003 `:197` 연결 전 조건에 한 줄로 남길 것.
- N8. `withRefreshAtHalfLife` 주석(`TossRestClient.java:176-181`)의 "늦춘 시각이 실제 만료보다 뒤가 되지는 않는다"는 캐시에 넘기는 `expiresAt` 값에 대해서는 틀리다(수명 20분 미만이면 늘 `발급 + 수명/2 + 10분` > `발급 + 수명`). 뜻은 "다시 받는 시점이 실제 만료보다 앞선다"이므로 그렇게 고쳐 쓸 것. `TokenCache`가 `expiresAt`을 재발급 판정에만 쓰므로 동작 문제는 아니다.
- N9. 결정 로그 요약 줄 D-053(`docs/decisions/README.md:55`)에 이번에 D-053 본문 3·4번에 더한 내용(그룹 간 독립 대기, 보호 폴링 대기 상한 1초, 짧은 토큰 반감기 재발급)이 없다. 요약 줄과 본문을 맞출 것.
- risk-ops 재검토의 R7(극단적으로 짧은 토큰 수명)·R8(같은 그룹 안 보호 폴링 굶음)·N1(폴링 소스가 상한을 넘기는지 확인하는 단위 테스트)에 동의한다. 특히 R8은 F4 (가)안과 같은 곳(`send`)을 고치므로 연결 PR 설계 때 함께 본다.

### 확인한 좋은 점(근거)
- 제한기 수정이 자리 예약과 잠자기를 정확히 나눠, 상한 초과 시에는 자리를 잡지 않는다(`TossRateLimiter.java:94-98`). 그래서 빠른 실패가 같은 그룹의 다음 호출 간격을 밀어내지 않는다.
- 경로 패턴 수정이 명세 패턴 그대로만 넓혔고 27건 표 테스트로 고정했다.

## 판정
수정 필요(F4 자기 재발급이 진행 중 요청을 `token-revoked`로 만들어 보호 폴링 포함 전체 10분 막힘, F3 잔여 ADR-003 `:17`·`:134` 날짜 메모). 이전 F1·F2는 해소됐고 B 점검표에 실패와 확인 못 함은 없다. F4 수정 커밋은 risk-ops 재검토도 받아야 하고(B7), 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다.
