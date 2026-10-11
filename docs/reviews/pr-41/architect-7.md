판정: 승인

# architect 재검토(7차): 토스 REST 명세 반영: 읽기 전용 토스 플러그인(토큰·공식 한도·현재가 폴링), 명세 요약, D-053·Q-042
- 대상: PR #41 / 커밋 `89f59f6`(브랜치 `claude/project-thread-v8cx8m`) / `git diff origin/main...HEAD` 전체(34개 파일, +3082 −2, merge-base `cf3759a`). 이전 원문 `architect-6.md`(대상 `7da5364`, 승인) 뒤의 코드 변경은 `89f59f6` 한 커밋(`TossTokenClient.java`, `TossPollingQuoteSourceTest.java`, 문서 ADR-003·D-053·결정 로그·명세 요약)이다. 그 사이 `038643c`·`b6b2b84`·`c724e09`는 검토 원문 추가뿐이다. 그 커밋만이 아니라 diff 전체와 호출 경로(`TossRestClient.send`→`token()`→`guarded`→`TossTokenClient.fetch`→`TossRateLimiter.acquire`)를 다시 봤다.
- 점검표: B
- 검토자: architect 서브에이전트, 2026-10-10
- 파일 위치: 호출자 지시에 따라 `architect-7.md`로 따로 썼다(이전 원문은 고치지 않았다).
- 파일:라인은 모두 `89f59f6` 기준이다.
- 직접 실행한 것
  - `89f59f6`을 별도 git worktree(scratchpad)에 꺼내 `./gradlew build --no-daemon --console=plain`: EXIT 0(BUILD SUCCESSFUL, 109 tasks). JUnit XML 합계 638건, 실패 0·오류 0·건너뜀 0. 이 중 토스 90건(TossRestClientTest 69, TossRateLimiterTest 7, TossTokenClientTest 5, TossPollingQuoteSourceTest 6, TossPluginTest 3), ArchitectureTest 15, ModuleOwnershipTest 13, RealTradingGuardTest 31, GuardedOrderGatewayTest 8.
  - **현재 `origin/main`은 `8680fde`로 앞서 있다**(`b1571f0` PR #40 키움, `8680fde` PR #37 ADR-007). `git merge-tree --write-tree origin/main 89f59f6`: `docs/decisions/README.md` 한 곳 충돌(main의 D-049·D-052 행 추가와 이 PR의 D-053 행 변경이 같은 자리). 다른 파일(`.gitleaks.toml`·`.gitleaksignore`·ADR-003)은 자동 병합된다. GitHub REST `pulls/41`: `mergeable=false`, `mergeable_state=dirty`.
  - 병합 결과 확인: 별도 worktree에서 `origin/main`에 `89f59f6`를 병합하고 충돌은 두 행을 모두 남기는 것(D-052 다음 D-053)으로만 풀어 `./gradlew build --no-daemon`: EXIT 0, 680건 실패 0·오류 0·건너뜀 0. 커밋·푸시하지 않았다.
  - **CI가 `89f59f6`(와 `c724e09`)에 돌지 않았다.** `commits/89f59f6/check-runs` 0건, `actions/runs?branch=...` 마지막 실행은 `b6b2b84`(success). 워크플로가 `pull_request` 이벤트라 충돌 상태에서는 병합 ref가 없어 실행되지 않은 것으로 본다 `[추론]`. 그래서 CI 작업을 로컬에서 재현했다: gitleaks 8.21.2(CI와 같은 버전, SHA-256 일치 확인) `gitleaks git --redact --exit-code 1 .` EXIT 0 "no leaks found", `scripts/ci/check-python-collectors.sh` EXIT 0. DB 스키마 작업은 이 PR이 마이그레이션을 바꾸지 않아 다시 돌리지 않았다(`b6b2b84` CI success, 그 뒤 스키마 변경 없음).
  - 변이 확인 3건(각각 `:autotrade-broker-toss:test`, 매번 `git checkout --`로 되돌림, 커밋 안 함):
    - M6: `TossTokenClient.java:62`를 `limiter.acquire(GROUP)`(상한 없음)로 → 1건 실패 `TossPollingQuoteSourceTest#토큰_재발급이_발급_한도에_막혀도_폴링은_기다리지_않고_실패한다()`. QA F1 수정이 테스트로 고정됨.
    - M7: `TossPollingQuoteSource.java:89`의 `MAX_RATE_WAIT`를 `null`로 → 1건 실패 `TossPollingQuoteSourceTest#폴링은_조회_한도_대기_상한을_넘기면_보내지_않고_실패한다()`. risk-ops N1(폴링 단위 상한 테스트) 해소.
    - M9: `TossRestClient.java:362-366`의 발급 실패 60초 하한을 `TossApiException`(로컬 대기 초과·발급 429)에는 걸지 않게 → 실패 0(N18).
  - `git diff origin/main...HEAD --diff-filter=D` 결과 없음(삭제 파일 0). `.java` 추가 줄에 `@Disabled` 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 격리 worktree `./gradlew build` EXIT 0, 638건 실패 0. `origin/main` 병합 결과도 EXIT 0, 680건 실패 0. 단 PR head에 CI가 돌지 않았다(충돌 상태). 로컬에서 CI 작업(빌드·gitleaks·수집기 경계)을 재현했다 |
| B2 | 통과 | `89f59f6`은 토스 모듈 안 소스 한 줄·상수 하나와 테스트만 바꿨다. 의존·`ModuleOwnershipTest`·ArchUnit 규칙 변경 없음. ArchitectureTest 15/15, ModuleOwnershipTest 13/13 통과 |
| B3 | 통과 | 공용 인터페이스·설정 키·스키마·REST API 변경 없음. `AUTH_MAX_WAIT`는 패키지 내부 상수(`TossTokenClient.java:42`). 동작 변경(발급 그룹 대기 1초 상한)을 D-053 ③(`docs/decisions/D-053-toss-rest-read-only-client.md:11`), 결정 로그 요약(`docs/decisions/README.md:55`), ADR-003(`docs/adr/ADR-003-market-data.md:47`), 명세 요약(`docs/research/toss-openapi-spec.md:102`) 네 곳에 같은 뜻으로 적었다. QA F1이 지적한 문서·코드 불일치가 해소됐다 |
| B4 | 통과 | 시뮬레이션·전략 코드 변경 없음, 골든 테스트 포함 `autotrade-sim:test` 통과. 새 상한 판정은 주입 `Clock`으로 한다(`TossRateLimiter.java:91`). 새 테스트도 `MutableClock`·주입 sleeper(`clock::advance`)만 쓴다 |
| B5 | 통과 | 금액 처리 변경 없음 |
| B6 | 통과 | 새 동작(발급 그룹 대기 상한)과 폴링 상한에 테스트가 있고 각각 되돌림 변이에서 실패한다(M6·M7). 새 테스트는 대기 시간(`≤ MAX_RATE_WAIT`), 호출 수 3(막힌 발급 POST를 보내지 않음), 남은 응답 0을 단언한다(`TossPollingQuoteSourceTest.java:94-113`). 실패 뒤 "다음 발급은 60초 하한"은 테스트로 고정되지 않는다(M9, N18, 차단 사유 아님). 삭제·`@Disabled` 없음 |
| B7 | 통과(risk-ops 검토 필요) | **risk-ops 검토 필요.** 보호 폴링(장애 대응 입력)의 토큰 경로 동작을 바꾼 커밋이다. `risk-ops-5.md`는 `b6b2b84` 대상이라 `89f59f6`에 대한 risk-ops 재검토와 독립 QA 재검토가 따로 있어야 하고, 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다 |
| B8 | 통과 | 새 예외는 기존 `local-wait-exceeded` 문구(`TossRateLimiter.java:95`)를 그대로 쓰고 자격 증명·토큰을 담지 않는다. 테스트 자격 증명은 `id-placeholder`·`secret-placeholder`, 토큰은 `tok-1`. gitleaks 8.21.2 로컬 실행 "no leaks found" |

## 이전 발견 처리

- **QA F1(보호 폴링 1초 상한이 토큰 재발급 경로에서 깨짐): 해소.** `[사실]` 발급기가 `AUTH` 그룹을 `AUTH_MAX_WAIT`(1초)까지만 기다리고(`TossTokenClient.java:62`), 넘으면 `TossRateLimiter.acquire`가 자리를 잡지 않고 `RATE_LIMITED`를 던진다(`TossRateLimiter.java:94-96`). `TossApiException`은 `IOException`이라(`TossApiException.java:15`) `guarded`의 `IOException` 분기에서 `nextAuthAllowed = now + 60초`가 걸린다(`TossRestClient.java:362-366`). 그 뒤 60초 동안의 발급 시도는 HTTP 없이 바로 실패한다(`:337-341`). QA가 추천한 (가)안이다. 탐침 P1 시나리오(발급 응답 Remaining 0·Reset 30 뒤 `expired-token`)를 새 테스트가 그대로 재현하고, M6에서 실패한다.
  - `[추론]` 상한 판정은 `tokenLock` 안에서 일어나므로 다른 조회가 그 잠금 때문에 기다리는 시간도 최대 1초(+ 발급 HTTP 자체의 타임아웃)로 줄었다. 발급 HTTP 타임아웃 동안 `tokenLock`을 쥐는 문제는 남는다(QA F1 [추론]에 적힌 그대로, 연결 PR에서 HTTP 타임아웃 값과 함께 볼 것).
- **risk-ops N1(폴링 단위 상한 테스트): 해소.** `#폴링은_조회_한도_대기_상한을_넘기면_보내지_않고_실패한다`(`TossPollingQuoteSourceTest.java:117`), M7에서 실패.
- **QA N1·N2, risk-ops R7~R10: 연결 PR 전 조건으로 기록됨.** `docs/adr/ADR-003-market-data.md:199`에 R7(짧은 수명 토큰), R8(보호 폴링 몫), R9(엔진 쪽 공백 0 테스트), R10(쉼 뒤 회복 계기), QA N1(시장별 부분 결과·400 종목 격리), QA N2(재발급 실패 시 기존 토큰 사용, `TokenCache` 공용이라 architect·risk-ops 검토)가 들어갔고, Q-042(`docs/decisions/README.md`)가 그 위치를 가리킨다. 이전 architect N16·N17은 여기 적히지 않았다(아래 N20).

## 점검표 밖 발견

### 머지 전 조건(코드 수정 사유 아님)
- **M-1. `origin/main`과 충돌, PR head에 CI 없음.** `[사실]` `docs/decisions/README.md` 결정 표에서 main의 D-049·D-052 행과 이 PR의 D-053 행이 충돌한다. 두 행을 모두 남기면(D-050, D-052, D-053 순) 빌드 680건 통과를 확인했다. 충돌을 풀어 올린 새 head에서 CI 세 작업이 모두 success인지 확인해야 한다. 풀이가 결정 표 행 순서 외의 것을 바꾸면(예: ADR-003 자동 병합 결과 손질, Q-042·Q-015 문구 변경) architect 재검토 대상이다. 자동 병합된 ADR-003은 main의 키움 관련 줄과 이 PR의 토스 줄이 서로 다른 단락이라 내용 충돌은 없다(병합본 확인).

### 고쳐야 할 것
없음.

### 권고(이번 PR에서 안 고쳐도 됨, Q-042 연결 PR 전에 정리)
- **N18. 상한 초과·발급 429 뒤의 "다음 발급은 60초 하한"이 테스트로 고정되지 않는다.** `[사실]` M9(그 경우 하한을 안 걸게 함)에서 실패 0. `[추론]` 지금은 리미터가 같은 그룹을 계속 막고 있어 하한이 없어도 POST는 나가지 않는다(두 겹 중 한 겹). 그래서 위험은 낮지만 D-053 ③이 "다음 발급은 60초 하한을 따른다"고 약속하므로, 새 테스트 끝에 "1초 뒤·59초 뒤 poll도 POST 0건, 60초 뒤 한 번 발급" 단언을 붙일 것.
- **N19. 상한 초과 뒤 하한(60초)이 실제 막힘(최대 60초, 흔히 `Reset` 수 초)보다 길다.** `[사실]` `AUTH`가 `Reset 2`로 2초만 막혀도 상한 초과 한 번이면 60초 동안 모든 조회가 발급 없이 실패한다(`TossRestClient.java:337-341, 362-366`). 보호 폴링 공백 관점에서는 "30초 기다림" 대신 "60초 실패"가 되는 경우가 있다. 실패가 빨리 나 엔진이 다른 소스로 넘어갈 수 있으므로 fail-fast 자체는 맞다(D-053 ③의 의도). 대안: (가) 로컬 대기 초과일 때는 `nextAuthAllowed = now + 남은 대기`로 두고, 실제 HTTP 실패에만 60초 하한, (나) 그대로 두고 연결 PR의 엔진 쪽 공백 테스트(R9)에 이 경우를 넣기. 연결 PR에서 (가)를 권한다. ADR-003 4장 "발급 실패는 원인과 상관없이 60초"와 맞추려면 (가)는 ADR 문구 보완이 필요하다.
- N20. 이전 architect N16(쉬는 동안 DEGRADED 두 겹 중 성공 경로 쪽 비원자성)·N17(단언 없는 `clock.advance`)이 ADR-003 연결 PR 전 조건 목록에 없다. QA N3도 같은 지적이다. 연결 PR 전 조건에 함께 적을 것.
- N21. 보호 폴링 한 번의 최악 대기는 이제 `AUTH` 1초 + `MARKET_DATA` 1초 + 발급·조회 HTTP 타임아웃이다(그룹별 상한). 문서의 "1초 상한"은 그룹별 대기 상한이라는 뜻으로 읽히고 코드도 그렇다. 연결 PR에서 엔진이 기대하는 폴링 한 번의 총 시간 예산을 정할 때 이 합을 쓸 것.

### 확인한 좋은 점(근거)
- 수정이 기존 `acquire(group, maxWait)`(`TossRateLimiter.java:87`)를 재사용한 한 줄이라 새 경로를 만들지 않았고, 상한 초과 시 자리를 예약하지 않아(`:94-97`) 실패한 시도가 그룹 한도를 소모하지 않는다.
- QA 탐침 시나리오를 운영 코드 시험용 훅 없이 테스트 이음새(`FakeHttp` 응답 헤더, `MutableClock`)만으로 결정론적으로 재현했고, 되돌림 변이에서 실제로 실패하는 것을 직접 확인했다.

## 판정
승인. QA F1(A14)과 risk-ops N1은 코드와 테스트로 해소됐고(M6·M7 변이 실패 확인), `89f59f6` 단독 빌드와 `origin/main` 병합 빌드 모두 통과, CI 작업은 로컬 재현으로 통과, 확인 못 함 항목은 없다. N18~N21은 Q-042 연결 PR 전 권고다. 이 승인은 `89f59f6` 내용에 한한다: 머지 전에 M-1(main 충돌 해소, 새 head CI success)이 필요하고, 풀이가 결정 표 행 병합 외의 것을 바꾸면 다시 검토한다. 또 `89f59f6`은 risk-ops 재검토와 독립 QA 재검토를 받아야 하며(B7), 이 PR은 지침 10장에 따라 사용자가 직접 확인하고 머지한다.
