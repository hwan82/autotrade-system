# architect 검토: 선정 계좌 받지 않은 확인 신호도 일 손실 한도 확인(D-135)·주문 전 거름·순위 전 대기일 시험
- 대상: PR #218 / 커밋 `b53a3c411652814039bf26423ad9307665a13821`(브랜치 `claude/project-thread-8xacpv`) / main(`3e25539`) 대비 diff(`git diff origin/main...HEAD`, 8개 파일 +160 −12)
- 점검표: B
- 직접 실행한 것:
  - 별도 git worktree(헤드 b53a3c4, 작업 트리 깨끗)에서 `./gradlew build --no-daemon` → **BUILD FAILED, 의존성 해석 단계**: `repo.maven.apache.org` 429 Too Many Requests(`spring-boot-buildpack-platform:3.5.16`, `spring-core:6.2.19` 등, `:autotrade-app-lab` 플러그인 classpath). 컴파일·테스트에 들어가지 못함. 코드와 무관한 컨테이너 네트워크 문제 `[추론]`.
  - `./gradlew build --offline` → `jackson-annotations-2.21.jar` 캐시 없음으로 configure 단계 실패.
  - `./gradlew :autotrade-strategy:test :autotrade-sim:test --offline --configure-on-demand` → `junit-jupiter`·`assertj-core:3.27.7` 캐시 없음으로 실패. 테스트는 한 건도 돌리지 못했다.
  - `./gradlew :autotrade-strategy:compileJava --offline --configure-on-demand` → BUILD SUCCESSFUL(`autotrade-domain`·`autotrade-strategy` 본 코드 컴파일만 확인). `:autotrade-sim:compileJava`는 의존성 캐시 부족으로 실패.
  - CI(GitHub API로 직접 조회): 워크플로 `CI` run 39539743832(event `pull_request`, `head_sha` b53a3c4 일치, conclusion success). check run `Gradle build`(119024517642) success, `DB schema (PostgreSQL 16)`(119024517602) success, `Lab screens (Playwright)`(119024517429) success, `Secret scan and collector boundary`(119024517658) success. 잡 로그 본문은 보지 않았다.
  - PR #218 댓글(GitHub API `get_comments`): 없음. 이 PR에 "CI 결과를 B1 근거로 인정"하는 사용자 기록이 없다.
  - 코드 대조: D-130 전(`613ef94^`) `ManagedStrategy.entry`와 현재 판을 비교, `RunStepper.admits`·주문 전 거름(`RunStepper.java:144-166`), `StrategyContext` 구현 위치 검색.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 직접 실행한 `./gradlew build`는 Maven Central 429로 의존성 해석 단계에서 실패, 테스트 0건 실행. 같은 헤드 CI `Gradle build` success(run 39539743832, job 119024517642)는 직접 확인했으나, CI로 B1을 대신한다는 사용자 결정이 이 PR(댓글 없음)이나 저장소(`docs/qa/checklists.md:32` B1은 "검토자가 직접 실행", 결정 로그에 예외 없음)에 없다. 전달받은 기록(PR #124 결정)은 스스로 "실전 관련 PR에도 같은지는 따로 묻지 않았다"고 적고 있고, 이 PR은 보호 경로(`autotrade-strategy/*`, `scripts/ci/protected-paths.txt`)를 바꾸는 실전 관련 PR이다 |
| B2 | 통과(코드 읽기) / ArchUnit은 CI로만 | 새 import·의존 없음. `autotrade-strategy`는 순수 Java 그대로(`ManagedStrategy.java` 변경은 같은 클래스 안 private 메서드 추출). `autotrade-sim` 시험이 쓰는 `SelectionDay`는 같은 모듈. ArchUnit·`ModuleOwnershipTest`는 직접 돌리지 못함(CI `Gradle build` success) |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `blocked-entries` 값의 뜻(D-130부터)을 `RunSelectionDay.java:10-12` javadoc, ADR-013 결과 절(`docs/adr/ADR-013-strategy-entry-admission.md:46`), D-135 "`blocked-entries` 뜻" 절에 적었다. 결정 로그 색인 D-135 행 추가(`docs/decisions/README.md:141`) |
| B4 | 통과(코드 읽기) / 골든은 CI로만 | 새 코드에 시스템 시계·난수 없음(`dailyLossReached`는 `dayStartEquity`·`AccountSnapshot`만 씀). 선정 없는 계좌는 `RunStepper.admits`가 `selectionDay == null`이면 늘 true(`RunStepper.java:160-166`)라 새 분기를 타지 않는다. `allowedByLimits`의 일 손실 확인은 같은 식·같은 순서(마지막 단계)로 메서드만 뽑았다(`ManagedStrategy.java:288-322`, 이전 판과 대조). 골든·`선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다`는 직접 돌리지 못함(CI success) |
| B5 | 통과 | 일 손실 계산은 `BigDecimal`·`MathContext.DECIMAL64` 그대로(`ManagedStrategy.java:316`) |
| B6 | 확인 못 함 | 새 시험 4건: `CatalogStrategyTest#실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다`·`#손실이_없으면_받지_않는_확인_신호는_뒤_후보_진입을_막지_않는다`, `SelectionEntryQuotaTest#전략이_진입_여부를_묻지_않아도_주문_전_거름이_후보_밖_매수를_막는다`·`#실시간_모의_순위_전_대기일_신호는_한도를_쓰지_않고_순위_이벤트_자신의_후보_신호는_들어간다`. 손 계산으로는 첫 시험이 고치기 전 코드에서 실패한다(09:11 A 신호 때 평가금액 10,000,000 → 손실 0 → 매수 1건, 기대 empty) `[추론]`. 지우거나 끈 시험 없음(diff는 추가뿐). 그러나 직접 실행하지 못했고 변이도 돌리지 못했다(CI success만) |
| B7 | risk-ops 검토 필요 | 보호 경로 `autotrade-strategy/*` 변경(`ManagedStrategy.java`), 전략 내부 일 손실 한도(리스크 한도) 평가 시점 변경. 실전 주문 경로·RealTradingGuard·`RiskChain`·블랙리스트 판정 코드는 diff에 없다. 실전 관련 PR이므로 risk-ops 검토(A), 독립 QA, 사용자 머지 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(diff 문자열 검색), CI `Secret scan and collector boundary` success |

## 점검표 밖 발견
1. **수정 필요(문서 정확성, D-135 대안 표 안 1 칸)**: D-135는 안 1이 "확인 시점이 '확인 신호가 온 순간'으로 D-130 전과 같아진다"고 적었지만 코드와 맞지 않는다. D-130 전(`613ef94^` `ManagedStrategy.entry` → `allowedByLimits`)에는 일 손실 확인이 `haltedToday`·하루 진입 수·재진입 금지 시간·보유 수 확인을 **모두 통과한 뒤에만** 일어났다. 새 분기(`ManagedStrategy.java:254-256`)는 받지 않은 신호면 이 앞 단계와 상관없이 확인한다. 그래서 받지 않은 신호가 온 순간 보유 수가 꽉 찼거나 재진입 금지 시간 안이었어도 손실이 닿았으면 그날 멈추고, 뒤에 자리가 비어 후보 신호가 와도 진입하지 않는다(D-130 전보다 일찍 멈추는 쪽, 보수적). 같은 조건의 **받은** 후보 신호는 여전히 손실을 보지 않으므로 받은 신호와 받지 않은 신호의 확인 범위도 다르다. 위험 방향은 더 엄격한 쪽이라 안전 결함은 아니다 `[추론]`. 다만 사용자가 안 1·2·3을 고르는 근거 문서이므로 둘 중 하나로 맞춘다: (a) D-135 문구를 "받지 않은 신호는 앞 한도 단계와 상관없이 일 손실을 본다(D-130 전보다 같거나 일찍 멈춤)"로 고치거나, (b) 코드를 D-130 전과 같은 범위(앞 단계를 통과할 때만 확인)로 맞추고 그 경우를 시험으로 고정한다. 어느 쪽이든 risk-ops 검토 대상.
2. 권고: `SelectionEntryQuotaTest.java:30` 새 import `com.autotrade.sim.selection.SelectionDay`가 `DailySelection`과 `IntradayUniverse` 사이에 있어 이름순이 아니다(빌드는 영향 없음).
3. 기록: D-135 "실전 엔진은 아직 전략을 직접 돌리지 않는다"는 `StrategyContext` 구현이 `RunStepper.java`(sim)와 시험(`SelectionEntryQuotaTest`, `Tape`)뿐임을 검색으로 확인했다. `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-risk`에 `ManagedStrategy`·`CatalogStrategyFactory` 사용 없음.
4. 기록: D-135 상태가 "제안 · 사용자 결정 대기"다. 머지 전에 사용자 결정(안 1·2·3)이 있어야 결정 로그 상태와 맞는다(지침 8장 2번).

## 판정
수정 필요(B1·B6 확인 못 함: 이 컨테이너에서 `./gradlew build`가 Maven Central 429로 의존성 해석 단계에서 실패해 테스트를 한 건도 직접 돌리지 못했다. 같은 헤드 CI `Gradle build` success는 직접 확인했으므로, 사용자가 이 PR에 "CI 결과를 B1 근거로 인정"을 남기면 B1·B2·B4·B6은 CI로 판단할 수 있다. / 점검표 밖 1: D-135의 "D-130 전과 같아진다" 문구가 코드와 다르므로 문구 또는 코드를 맞춘다. 그 밖에 코드 결함으로 인한 수정 요구는 없다)

## 재검토 2026-10-21
- 대상: PR #218 / 커밋 `da64e0afb960d3484509096adf89432bc82251e6`(원격 브랜치 head와 일치) / main(`3e25539`) 대비 diff. 1차(b53a3c4) 뒤 커밋 `38bdb46`·`da64e0a`. 바뀐 것(`git diff b53a3c4 da64e0a`): `SelectionEntryQuotaTest.java` import 순서, D-135 상태·대안 표 안 1 칸·위험과 한계 첫 항목·후속, ADR-013 결과 절 "(제안)"→"(사용자 결정)", 결정 로그 색인 D-135 행, `docs/qa/pending.md` 한 줄, `docs/reviews/pr-218/architect.md`·`risk-ops.md` 원문. 운영 코드(`ManagedStrategy.java`, `RunSelectionDay.java`)는 1차와 같다. 커밋된 1차 원문은 이 검토자가 쓴 파일과 바이트 단위로 같다(md5 일치).
- 점검표: B
- 직접 실행·확인한 것:
  - 별도 git worktree(헤드 da64e0a)에서 `./gradlew build --no-daemon` → **BUILD FAILED in 1m 7s, 의존성 해석 단계**(`junit-platform-launcher:1.12.2`, `junit-jupiter-engine:5.12.2` 등 `repo.maven.apache.org` 429). 테스트는 이번에도 직접 돌리지 못했다. worktree는 끝난 뒤 지웠다.
  - **사용자 댓글(GitHub API `get_comments`)**: id 6307770592, 작성자 `hwan82`(author_association OWNER), 2026-10-21T04:05:39Z, 수정 없음, 본문 "CI 결과를 B1 근거로 인정, D-135 결정 확인", PR #218의 유일한 댓글.
  - **한계(기록)**: PR #124 4차와 같다. 에이전트도 같은 저장소 소유자 계정으로 GitHub에 쓸 수 있는 환경이면, API 응답만으로는 사용자가 직접 쓴 댓글과 에이전트가 쓴 댓글을 구별할 수 없다. 이 검토는 저장소 소유자 계정의 PR 댓글을 사용자 결정으로 본다. 사용자가 이 댓글을 직접 쓰지 않았다면 아래 B1·B2·B4·B6 판단과 승인은 무효다.
  - CI(직접 조회): 워크플로 `CI` run 39541171961(event `pull_request`, `head_sha` da64e0a 일치, conclusion success). check run `Gradle build`(119029173680), `DB schema (PostgreSQL 16)`(119029173706), `Lab screens (Playwright)`(119029173787), `Secret scan and collector boundary`(119029173492) 모두 success. 잡 로그 본문은 보지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(사용자 결정에 따라 CI로) | 사용자 댓글 6307770592 "CI 결과를 B1 근거로 인정"에 따라 같은 헤드 CI `Gradle build` success(run 39541171961, job 119029173680)를 근거로 씀. 직접 실행은 Maven Central 429로 의존성 해석 실패(코드 무관 `[추론]`) |
| B2 | 통과 | 코드 읽기 통과(1차와 같음, 이후 코드 변경은 import 순서뿐). ArchUnit·`ModuleOwnershipTest`·`RealPathReflectionTest`는 CI `Gradle build`의 `autotrade-architecture-test`로 통과(B1과 같은 근거) |
| B3 | 통과 | 1차와 같음. ADR-013 결과 절과 결정 로그 색인이 D-135 상태(사용자 결정)와 맞는다(`docs/adr/ADR-013-strategy-entry-admission.md:46`, `docs/decisions/README.md:141`) |
| B4 | 통과 | 1차 코드 읽기(시계·난수 없음, 선정 없는 계좌는 새 분기를 타지 않음)에 더해, 골든·`선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다`는 CI로 통과(B1과 같은 근거) |
| B5 | 통과 | 1차와 같음 |
| B6 | 통과 | 새 시험 4건(1차 목록)은 CI로 통과(B1과 같은 근거). 첫 시험이 고치기 전 코드에서 실패한다는 것은 손 계산 `[추론]`이며 직접 변이 실행은 하지 못했다. 지우거나 끈 시험 없음(`38bdb46`·`da64e0a`의 시험 파일 변경은 import 한 줄 이동뿐) |
| B7 | risk-ops 검토 필요 | 1차와 같음. 보호 경로 `autotrade-strategy/*` 운영 코드 변경. risk-ops 검토(A), 독립 QA, 사용자 머지 |
| B8 | 통과 | 1차와 같음. 새 커밋에도 키·계좌번호·토큰 없음, CI `Secret scan and collector boundary` success |

## 점검표 밖 발견(재검토)
1. 1차 점검표 밖 1(D-135 "D-130 전과 같아진다" 문구): **해소**. 대안 표 안 1 칸이 "확인 시점은 D-130 전처럼, 단 범위는 다르다"로 바뀌었다. 위험과 한계 첫 항목은 받지 않은 신호가 하루 진입 수·재진입 금지·보유 수 확인과 상관없이 일 손실을 보므로 D-130 전보다 일찍 멈출 수 있다는 점, 받은 신호와의 비대칭, 범위를 맞추지 않은 이유를 적었다. 코드(`ManagedStrategy.java:250-257`, `:288-305`)와 맞는다. 코드를 바꾸지 않고 문서를 맞추는 쪽(1차 선택지 a)을 골랐다.
2. 1차 권고 2(import 순서): 해소(`SelectionEntryQuotaTest.java:30-31`이 `IntradayUniverse`, `SelectionDay` 순).
3. 권고(새로 발견): `docs/qa/pending.md`의 PR #218 줄이 아직 "D-135 확인 방식은 사용자 결정 대기"라고 적혀 있어 `da64e0a`의 D-135 상태(사용자 결정)와 다르다. QA가 읽는 요청 줄이라 다음 커밋 때 맞추면 좋다. 판정을 막지 않는다.
4. 기록: D-135 상태의 근거인 "스레드 결정 카드 2026-10-21T03:54:38Z"는 이 검토자가 볼 수 없다. 같은 뜻의 기록은 사용자 댓글 6307770592 "D-135 결정 확인"뿐이다(위 한계와 같다).

## 판정
승인(사용자 hwan82의 PR #218 댓글 6307770592 "CI 결과를 B1 근거로 인정"에 따라 B1·B2·B4·B6을 같은 헤드 CI `Gradle build` success(da64e0a, run 39541171961)로 판단함. 그 댓글이 사용자 본인 것이 아니면 이 승인은 무효. B7: 보호 경로 변경이므로 risk-ops 검토·독립 QA·사용자 머지를 거쳐야 하며 이 판정만으로 Claude가 머지하지 않는다)
