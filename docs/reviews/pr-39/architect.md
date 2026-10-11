# architect 검토: 수집 일괄 명령 autotrade-app-collector(Q-037, D-051, ADR-008): KRX 일봉·상장주식수, OpenDART 재무 증분 수집
- 대상: PR #39 / 브랜치 claude/project-thread-vip8tu / 커밋 366b771(0720a8f, 26ca46a 포함) / main 대비 diff (`git diff origin/main...HEAD`, 56개 파일, 앱 모듈 신규 1개, marketdata 계약 변경, ArchitectureTest·ModuleOwnershipTest 규칙 추가, 문서 9개)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`(캐시 사용) → BUILD SUCCESSFUL, EXIT 0.
  - `./gradlew clean build --no-build-cache`(작업 트리 깨끗한 상태, HEAD 366b771) → BUILD SUCCESSFUL, EXIT 0, 131 tasks. 전체 테스트 결과 XML 합계 tests=584, skipped=0, failures=0, errors=0. 수집기 9개 클래스 31건, ArchitectureTest 16건, ModuleOwnershipTest 13건, SnapshotFinancialsTest 4건, SnapshotDailyBarSourceTest 9건 포함. 검토 중 브랜치에 366b771(테스트 자리표시자 교체, `.gitleaksignore` 1줄)이 올라와서 그 커밋에서 다시 `./gradlew build`를 돌렸고 결과가 같았다(EXIT 0).
  - 임시 확인 테스트(`ZzReviewProbeTest`, 실행 뒤 지움, 저장소에 남기지 않음)로 아래 F1·F2를 재현했다.
    - F1: `collect(2026-03-01, 2026-03-31)` 뒤 `collect(2016-01-01, 2026-10-09)` → 두 번째 실행의 첫 `bgn_de=20260401`. 2016-01-01~2026-02-28은 훑지 않았다.
    - F2: 어제 접수된 원 공시에 주요계정 응답 `status=013` → `pending=0`, `accounts/<접수번호>.json`의 `valuesFor=''`로 영구 기록.
  - `scripts/ci/check-python-collectors.sh`를 기본 폴더와 임시 폴더에 실행: 기본 EXIT 0. `"x?crtfc_key=abcdef1234567890"`는 잡힘(EXIT 1), `{"crtfc_key": "abcd1234abcd"}`는 안 잡힘(EXIT 0). R4.
  - `git diff origin/main...HEAD --diff-filter=D` → 지운 파일 없음. 추가 줄에 `@Disabled`·`@Ignore`·`assumeTrue` 없음. 추가 줄에서 32자 이상 영숫자 토큰은 `.gitleaksignore`의 커밋 지문뿐.
  - gitleaks는 이 환경에 없어 직접 돌리지 못했다(B8은 diff 검사로 판정, CI가 따로 돈다).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew clean build --no-build-cache` EXIT 0, 584건 실패 0·건너뜀 0(HEAD 366b771) |
| B2 | 통과 | `autotrade-app-collector/build.gradle.kts:13-17` `processIsolation`이 sim·app-lab·ui·risk·strategy·control·broker-api·kiwoom·kis·다른 app-*·Spring을 런타임에서 막음. 의존은 domain·marketdata·config·Jackson뿐(:19-25). ArchUnit: `ArchitectureTest.java` `PLAIN_PROCESSES`에 `com.autotrade.app.collector..` 추가, 새 규칙 `COLLECTOR_READS_ONLY_ITS_SECRETS`(ConfigLoader·RuntimeConfigStore·RuntimeConfigHolder·LoadedConfig·RuntimeConfig·ProcessStartup·`com.autotrade.broker..`·`com.autotrade.domain.order..` 참조 금지)와 위반 고정 예제(`archfixture/LoadsProcessConfig`) 테스트 `수집_일괄_명령은_자기_키_파일만_읽고_증권사_주문을_모른다` 통과. 기존 규칙 삭제·완화 없음(diff는 추가만). `PROCESSES_ARE_INDEPENDENT`가 `com.autotrade.app.(*)..` 슬라이스라 수집기도 자동 포함. `ModuleOwnershipTest.OWNERS`에 `com.autotrade.app.collector` 추가. 규칙 추가 근거 ADR-008 2.2, ADR-001 표 갱신. `ConfigPaths.defaultLocation()`은 경로만 만들고 파일을 열지 않음(`autotrade-config/.../ConfigPaths.java:27-37`) |
| B3 | 통과 | 계약 변경: `SnapshotManifest`에 `coverage` 구성요소 추가(record 생성자 시그니처 변경, 기존 사용처는 테스트 1곳만 갱신), 새 파일 `listed_shares.csv`·`financial_filings.csv`, 공용 읽기 `SnapshotFiles`·`SnapshotListedShares`·`SnapshotFinancials`, 헤더 상수 공개. 모두 ADR-008 2.3~2.5와 ADR-003 2.6 문구 갱신에 있다. 새 비밀 파일 `collector-secrets.json`은 `docs/config/config-keys.md`(키 2개, 형식·기본값·설명, 자리표시자 예시), `.gitignore`, runbook에 반영. 일반 설정은 설정 키가 아니라 명령줄 옵션으로 둔 이유와 대안이 ADR-008 2.2·3장에 있다. 결정 로그 D-051, Q-037 닫음, 기획서 v14.10(ARC-06·MD-11·배치표 1번) 일치 |
| B4 | 통과 | 시각은 주입 `Clock`으로만(`CollectorMain.java:50`의 `Clock.systemUTC()`는 진입점, 나머지는 `LocalDate.now(clock…)`·`ZonedDateTime.now(clock…)`). 난수 없음. 순회 순서 명시: KRX 행은 코드·시장 순 정렬(`KrxDailySnapshotBuilder.java:159-169`), 종목 상태 `TreeMap`(:51), 재무는 `SnapshotFinancials.ORDER`, 공시 목록 정렬(`DartFinancialCollector.java:209`). 스냅샷 ID는 내용 해시 기반이고 같은 캐시면 같은 ID(`CollectorMainTest#krx_daily는_일봉_스냅샷과_품질_리포트를_만든다`, `KrxDailySnapshotBuilderTest#같은_입력이면_같은_행이다`). sim 골든 테스트는 바뀌지 않았고 clean build에서 통과 |
| B5 | 통과 | 가격·금액·계수는 `BigDecimal`(`KrxDailyRow`, `FinancialFiling`, 이벤트 비율 `MathContext.DECIMAL64` `KrxDailySnapshotBuilder.java:36,74,153`), 거래량·상장주식수는 `long`(`longValueExact`). `double`·`float` 없음(grep) |
| B6 | 통과 | 새 동작마다 테스트가 있다(수집기 31건: 키 파일 거부, 가림, 호출 상한 합산·020 중단, 재시도, 캐시 증분, 주말·오늘 미캐시, 수정 계수 손 계산, 거래정지, 상장폐지·소속부 구간, 상장주식수, 원 공시·정정·값 모름·반영 지연, 명령 전체 왕복과 키 평문 부재). 지우거나 끈 테스트 없음. 다만 아래 F1·F2 경로는 테스트가 없고 실제로 틀린 동작을 한다(점검표 밖 F1·F2) |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·Guard·블랙리스트·리스크 한도·장애 대응 코드는 건드리지 않는다. 그러나 A 점검표 대상인 **프로세스 격리**(A8)에 새 실행 단위를 더하고 보호 테스트 `ArchitectureTest`의 프로세스 목록(`PLAIN_PROCESSES`)을 바꾼다(추가만, 약화 아님). 같은 PC에서 실전 엔진과 함께 도는 무거운 단발 프로세스가 지침 4장 6번의 자원 부족 시 정지 순서에 없다(점검표 밖 F3). 이 PR에 risk-ops 검토 원문이 없다 |
| B8 | 통과 | 키는 `Secret`으로만 들고(`CollectorSecrets.java`), URL·예외 메시지는 `Redaction`을 거침(`ApiCaller.java:93,99`, `CollectorMain.java:80`), JDK 예외 원인 메시지를 옮기지 않음, 파서 메시지도 옮기지 않음(`CollectorSecrets.java:41-43`). `CollectorMainTest#assertNoKeyAnywhere`가 캐시·스냅샷·리포트 전체에 키 평문이 없음을 확인. 테스트 픽스처는 자리표시자(`KRXPLACEHOLDERAAAA`, `DARTKEY` 등). 0720a8f의 이전 자리표시자(`dart-placeholder-key-0002`)는 실제 키가 아니며 `.gitleaksignore`에 지문으로 예외 처리됨. `.gitignore`에 `**/collector-secrets.json` |

## 점검표 밖 발견

### F1. (수정 필요) OpenDART 공시 훑기 기준점이 하나뿐이라 `--from`을 앞당기면 그 앞 기간을 조용히 영영 건너뛴다
- `[사실]` `DartFinancialCollector.java:154-155`: `start = done == null || done.isBefore(from) ? from : done.plusDays(1)`. `state.json`에는 `scannedThrough` 하나만 있고 "어디서부터" 훑었는지는 남지 않는다.
- `[사실]` runbook이 이 순서를 그대로 지시한다: 2장 첫 실행 `dart-financials --from 2026-03-01 --to 2026-03-31` → 3장 전체 수집 `dart-financials --from 2016-01-01`(`docs/runbooks/collector.md:416,426`). 검토자 재현에서 두 번째 실행의 첫 요청은 `bgn_de=20260401`이었다. 2016-01-01~2026-02-28 사업보고서는 한 건도 받지 않고, 오류나 경고 없이 종료 코드 0으로 스냅샷이 만들어진다.
- 영향: 배치표 2번 (가) 재무 4조건 선정이 10년 치 재무 없이 돌아간다. 품질 리포트의 "첫 접수일"을 사람이 눈여겨보지 않으면 드러나지 않는다. 같은 방식으로 `done.isBefore(from)`이면 `done+1 ~ from-1` 구간도 기록 없이 비지만, 이건 사용자가 고른 범위라 문제는 덜하다.
- 고칠 것(하나 고름): (가) 훑은 구간 목록(`[{from,to}]`)을 `state.json`에 두고 요청 범위에서 빠진 구간만 훑는다(추천, KRX 쪽 날짜별 캐시와 같은 성질). (나) 최소한 `from`이 처음 훑은 시작일보다 앞이면 그 앞 구간을 훑거나, 멈추고 안내한다. 어느 쪽이든 "첫 실행 뒤 `--from`을 앞당긴 두 번째 실행이 앞 구간을 훑는다" 테스트를 더한다.

### F2. (수정 필요) 새 공시에 주요계정 응답이 아직 없으면(013) 대기하지 않고 영구 "값 모름"으로 굳는다
- `[사실]` `DartFinancialCollector.java:230-242`: 대기(`pending`)는 응답 `rcept_no`가 비어 있지 않고 그 공시보다 앞일 때만이다. `status=013`(자료 없음)이면 `answered`가 빈 문자열이라 곧바로 `valuesFor=""`로 `accounts/<접수번호>.json`에 쓰고, 그 파일이 있으면 다시 부르지 않는다(:225). 검토자 재현: 어제 접수된 원 공시 + 013 → `pending=0`, `valuesFor=''`.
- `[추론]` 회사의 첫 사업보고서이거나 그 사업연도에 앞 공시가 없을 때, 재무 API 반영이 늦으면 응답은 "앞 공시를 가리킴"이 아니라 013이다. ADR-008 2.5가 반영 지연을 14일 다시 부르는 이유가 그대로 적용되는데 이 경우만 빠졌다. 매일 증분 수집으로 원 공시 값을 확보한다는 D-051 ③의 핵심 경로가 반영 지연 한 번에 대체 규칙(편향 표시) 대상으로 떨어진다.
- 고칠 것: 접수 뒤 `PENDING_DAYS` 안의 013(또는 빈 목록)도 대기로 두고, 기한이 지나면 값 모름으로 기록한다. 테스트를 더하고 ADR-008 2.5 문구에 이 경우를 적는다.

### F3. (risk-ops 확인 필요) 수집기 자원 사용이 실전 엔진 보호 규칙 밖에 있다
- `[사실]` runbook 3장은 품질 리포트 단계 메모리 부족 시 `JAVA_OPTS=-Xmx6g`를 권하고(`docs/runbooks/collector.md:431`), 4장은 매일 장 마감 뒤 18:30 이후 OS 예약 작업 실행을 권한다(:438-440). ADR-008 4장도 전 기간 스냅샷은 힙 수 GB가 필요할 수 있다고 적는다.
- `[사실]` 지침 4장 6번은 "실험실·UI·녹화는 자원 부족 시 먼저 멈춘다"고 정하는데, 수집기는 그 목록에도 감시 프로세스의 정지 대상에도 없다. 미국 정규장은 한국 시간 22:30(서머타임)부터라 18:30에 시작한 전체 수집·리포트가 해외 실전 운용 시간과 겹칠 수 있다 `[추론]`.
- 제안: runbook에 "실전 엔진 운용 중에는 전 기간 리포트 생성을 하지 않는다 / 힙 상한 기본값(예: `-Xmx2g`)과 낮은 OS 우선순위로 실행" 같은 운영 규칙을 두고, risk-ops가 A8 관점에서 수집기를 자원 부족 시 정지 순서에 넣을지 판정한다. 코드 변경이 필요 없을 수도 있지만 판정은 risk-ops 몫이다.

### 권고 (머지를 막지 않음, 후속 과제로 남겨도 됨)
- **R1. 코드 재사용 때 가짜 수정 이벤트**: `KrxDailySnapshotBuilder.java:67-79`는 종목 코드가 한동안 사라졌다가(상장폐지 뒤 같은 코드로 다른 회사 상장) 다시 나와도 `lastClose`를 이어 써서, 새 회사 첫날 기준가 ÷ 옛 회사 마지막 종가를 수정 비율로 잡고 옛 회사의 과거 일봉 전체에 곱한다. ADR-008 4장은 구간이 갈린다는 것만 적었다. 코드가 N거래일 이상 비었다가 다시 나오면 `lastClose`를 버리고 품질 리포트에 센다.
- **R2. 평일 빈 응답의 영구 캐시**: `KrxDailyCollector.java:150-155`는 평일 빈 목록을 휴장일로 보고 캐시한다. 자료 게시 전 빈 응답이었다면 그 거래일이 영원히 빠진다. `autotrade-marketdata`의 거래소 캘린더와 대조하거나, 최근 며칠의 빈 응답은 캐시하지 않는다.
- **R3. 리다이렉트로 키 헤더 전달**: `JdkHttpGetter.java:17` `Redirect.NORMAL`. JDK HttpClient는 리다이렉트 요청에 사용자 헤더를 다시 붙이므로 `AUTH_KEY`가 리다이렉트 대상 호스트로 갈 수 있다 `[추론]`(JDK 동작 원문 미대조). 두 API는 리다이렉트가 필요 없으니 `Redirect.NEVER`로 두고 3xx는 오류로 멈춘다.
- **R4. CI `crtfc_key` 패턴 누락**: `scripts/ci/check-python-collectors.sh:14` 패턴은 `crtfc_key=값`만 잡고 Python에서 흔한 `{"crtfc_key": "값"}`(따옴표가 `:` 앞에 옴)는 못 잡는다(검토자 실행으로 확인). `crtfc_key["']?\s*[:=]\s*["']?[A-Za-z0-9]{8,}`로 넓힌다.
- **R5. 호출 기록 동시 실행**: `CallBudget.acquire`(`CallBudget.java:64-73`)는 잠금 없는 읽기-쓰기라 같은 소스 명령을 동시에 두 번 돌리면 합산이 빠져 상한을 넘을 수 있다. 파일 잠금을 두거나 runbook에 "동시에 하나만"을 적는다.
- **R6. 대체 규칙 묶음 집계**: `DartSnapshotBuilder.java:364-371`은 묶음 첫 행이 정정 공시(원 공시가 `--from` 전에 접수돼 목록에 없음)여도 값이 있으면 대체 규칙 대상으로 세지 않는다. 첫 행이 `correction=Y`인 묶음을 따로 센다.
- **R7. 기본 주소 덮어쓰기 옵션**: `--krx-base-url`·`--dart-base-url`(`CollectorMain.java:231-232`)은 키를 임의 호스트로 보낼 수 있다. 테스트용이면 `https`만 허용하거나 사용법에서 빼고 패키지 내부 생성자로 넣는다.

## 판정
수정 필요(F1 공시 훑기 기준점, F2 013 대기 누락, B7 risk-ops 검토 원문 필요(F3 자원 격리))

## 재검토 2026-10-10

- 대상: PR #39 / 브랜치 claude/project-thread-vip8tu / 커밋 ab86736(9690af8, 89fae05, ecd2af6 포함, 브랜치 이력이 다시 쓰여 1차 검토의 0720a8f·26ca46a·366b771은 9690af8 하나로 합쳐짐) / main 대비 diff 전체(`git diff origin/main...HEAD`, 64개 파일). 1차 검토 원문: [architect.md](architect.md). risk-ops 1차 원문: [risk-ops.md](risk-ops.md)
- 점검표: B
- 같은 재검토 내용을 docs/reviews/README.md 규칙대로 [architect.md](architect.md) 끝의 `## 재검토 2026-10-10`에도 붙였다(1차 내용은 고치지 않음). 두 곳의 본문은 같다.
- 직접 실행한 것:
  - `./gradlew clean build --no-build-cache --no-parallel --max-workers=1` 1회차 → EXIT 1(`:autotrade-marketdata:compileTestJava`에서 `autotrade-domain/build/classes/.../MarketRules.class` NoSuchFileException). 같은 작업 폴더에서 다른 Gradle 실행(`--max-workers=1`, 다른 검토 세션으로 보임)이 동시에 돌며 빌드 산출물을 지운 탓이다. 코드 원인이 아니다(`ps`로 동시 실행 확인).
  - 그 실행이 끝난 뒤 `./gradlew build --rerun-tasks --no-daemon --no-parallel --max-workers=1`(HEAD ab86736, 작업 트리 깨끗) → **BUILD SUCCESSFUL, EXIT 0**, 112 tasks 모두 실행. 결과 XML 합계 tests=601, skipped=0, failures=0, errors=0. 수집기 48건(RunGuardTest 6, SnapshotRetentionTest 2, CollectorMainTest 9, DartFinancialCollectorTest 8, KrxDailySnapshotBuilderTest 6 포함), ArchitectureTest 16, ModuleOwnershipTest 13. `verifyProcessIsolation`은 collector·engine·gateway·watchdog 모두 통과.
  - 임시 확인 테스트(`ZzReviewProbe2Test`, 실행 뒤 지움)로 1차 지적과 새 기능을 직접 확인했다.
    - F1: `collect(2026-03-01, 2026-03-31)` 뒤 `collect(2016-01-01, 2026-10-09)` → 첫 `bgn_de=20160101`, 이미 훑은 2026-03 창은 다시 부르지 않음. **고쳐짐.**
    - F2: 어제 접수된 원 공시 + 계정 응답 013 → `pending=1`, `accounts/<접수번호>.json` 안 씀(다음 실행에서 다시 부름). **고쳐짐.**
    - 스냅샷 보관: 기본 배치(`<root>/snapshots/krx-daily/<ID>` 3개, 각자의 품질 리포트 `experiments/_data-quality/<ID>.md`)에서 `prune(keep=1)` → **지운 것 0개.** 아래 G1.
  - `bash scripts/ci/check-python-collectors.sh`(기본 폴더) EXIT 0. 바뀐 패턴 `crtfc_key["']?\s*[:=]…`은 1차 R4에서 검토자가 쓴 두 입력을 모두 잡는 모양이다(diff 확인).
  - gitleaks는 이 환경에 없어 직접 돌리지 못했다. `.gitleaks.toml`(기본 규칙 + 정확히 `^abcdef1234567890$` 한 값만 허용)은 내용으로 검토했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build --rerun-tasks --no-parallel --max-workers=1` EXIT 0, 601건 실패 0·건너뜀 0(HEAD ab86736). 첫 clean build 실패는 동시 Gradle 실행 간섭(위 기록) |
| B2 | 통과 | 1차 근거 유지. 추가로 engine·gateway·watchdog `build.gradle.kts`의 `processIsolation.forbiddenProjects`에 `:autotrade-app-collector`를 넣었다(빌드 수준 격리 강화, 줄 추가만). 수집기는 `autotrade-marketdata` 거래소 캘린더(`ExchangeCalendar.bundled`)만 새로 쓴다. ArchUnit 규칙 변경 없음(1차 이후) |
| B3 | 통과 | 새 명령줄 옵션(`--min-free-gb`, `--keep-snapshots`, `--allow-during-session`)과 종료 코드 4, `state.json` 형식 변경(`scannedThrough` → `scanned[{from,to}]`)이 ADR-008 2.5·2.7과 runbook 3.5·4·5.1장에 적혀 있다. 설정 파일 키는 늘지 않았다. 결정 로그 D-051 ⑤와 Q-041 추가. `state.json`은 수집기 내부 캐시라 외부 계약이 아니다. 예전 형식 파일은 무시되어 처음부터 다시 훑는다(호출만 더 쓰고 결과는 같음, 아직 배포 전) |
| B4 | 통과 | `RunGuard`는 주입 `Clock`만 쓴다(`RunGuard.java` `clock.instant()`). 보관 규칙의 정렬은 manifest 생성 시각, 같으면 이름(`SnapshotRetention.java`). 수정 계수 코드 재사용 판정은 거래일 순번 기준이라 결정론적. 골든 테스트 변경 없음, 통과 |
| B5 | 통과 | 1차 근거 유지. 새 코드에 금액 계산 없음 |
| B6 | 통과 | 1차 F1·F2와 risk-ops F1·F2, R1·R3·R4·N1에 테스트가 붙었다(`DartFinancialCollectorTest#첫_실행_뒤_from을_앞당기면_앞_구간만_더_훑는다`, `#훑지_않은_구간은_요청_범위에서_훑은_구간을_뺀_것이다`, `#새_공시의_계정이_아직_없으면_013_기한_안에는_대기한다`, `RunGuardTest` 6건, `CollectorMainTest#국내_장_시간에는_시작하지_않고_종료_코드_4`·`#실행_중_보호_구간에_들어서면_다음_호출_전에_멈춘다`·`#디스크_여유가_기준_미만이면_시작하지_않는다`·`#주소는_https만_받는다`, `KrxDailySnapshotBuilderTest#오래_비었다가_다시_나온_코드는_옛_종가로_수정_비율을_잡지_않는다`). 지우거나 끈 테스트 없음. 다만 보관 규칙 테스트(`SnapshotRetentionTest`)는 품질 리포트가 실제로 놓이는 자리를 빼고 만들어 G1을 잡지 못한다. `CallBudget` 잠금에는 동시 실행 테스트가 없다(권고) |
| B7 | risk-ops 검토 필요 | 이 PR은 실전 엔진·게이트웨이·감시의 빌드 격리 설정을 바꾸고(강화), 장애 대응 정지 순서와 관련된 Q-041을 올리며, ADR-008 머리말도 "risk-ops 검토 후 사용자 머지 대상"으로 고쳤다. risk-ops 1차 판정은 수정 필요였고, 이 재검토를 쓰는 동안 [risk-ops.md](risk-ops.md) 끝에 risk-ops 재검토(`## 재검토 2026-10-10`, 판정 **승인**)가 작업 트리에 덧붙었다(아직 커밋 전, 검토자가 `git diff`로 확인). 그 원문이 커밋되어 PR에 들어가야 B7이 갖춰진다. 지침 10장에 따라 머지는 사용자가 한다 |
| B8 | 통과 | 1차 근거 유지. 새로 `https://`만 받는 주소 옵션(`CollectorMain.java` `https()`), 리다이렉트 안 따름(`JdkHttpGetter.java` `Redirect.NEVER`). `RunGuard`·`SnapshotRetention` 메시지에 키 없음. `.gitleaks.toml` 허용 값은 검토 원문에 인용된 가짜 시험 입력 하나이고 실제 키가 아니다 |

### 1차 지적 처리
| 1차 항목 | 결과 | 근거 |
|---|---|---|
| F1 공시 훑기 기준점 | 해결 | `DartFinancialCollector.java` `scannedRanges`·`gaps`·`merge`, 위 재현 확인과 테스트 2건 |
| F2 013 대기 누락 | 해결 | `DartFinancialCollector.java` `notYet = answered.isEmpty() \|\| answered < no`, 재현 확인과 테스트 |
| F3 / B7 risk-ops 검토 | 진행 중 | risk-ops 1차 원문 있음(수정 필요). 재검토 대기 |
| R1 코드 재사용 | 반영 | 20거래일 넘게 비면 전일 종가를 잇지 않고 건수 기록(`KrxDailySnapshotBuilder.java` `REAPPEAR_GAP_DAYS`), 테스트 |
| R2 평일 빈 응답 캐시 | 미반영 | 그대로. 권고로 남김 |
| R3 리다이렉트 | 반영 | `Redirect.NEVER` |
| R4 CI 패턴 | 반영 | `check-python-collectors.sh:14` |
| R5 호출 기록 잠금 | 반영 | `CallBudget.locked`(`FileChannel.lock`). 테스트 없음 |
| R6 대체 규칙 묶음 집계 | 미반영 | 그대로. 권고로 남김 |
| R7 주소 옵션 | 반영 | `https://`만 허용 |

### 점검표 밖 발견

#### G1. (수정 필요) `--keep-snapshots`가 기본 배치에서 스냅샷을 하나도 지우지 못한다
- `[사실]` `SnapshotRetention.prune`은 `experimentsDir` 아래 모든 텍스트 파일(깊이 4, 4MB 이하)에 ID가 나오면 "실험이 참조"로 보고 남긴다. `CollectorMain.prune`은 `experimentsDir = reportDir.toAbsolutePath().getParent()`로 정한다. 기본 `--report-dir`은 `experiments/_data-quality`이고, 수집기는 스냅샷마다 그 폴더에 `<ID>.md`를 쓰며 첫 줄이 `# 데이터 품질 리포트: <ID>`다(`QualityReport.java:68,96-98`, 재무는 `CollectorReports.writeFinancial`). 그래서 수집기가 만든 모든 스냅샷이 자기 품질 리포트 때문에 "참조됨"이 된다.
- `[사실]` 검토자 재현: 스냅샷 3개와 각 품질 리포트를 기본 배치로 두고 `prune(keep=1)` → 지운 것 0개. `SnapshotRetentionTest`는 품질 리포트를 두지 않은 `experiments` 폴더로만 시험해서 이 경우를 잡지 못한다. `CollectorMainTest`에는 `--keep-snapshots` 왕복 테스트가 없다.
- 영향: risk-ops 1차 F2(디스크 무제한 증가)의 대책으로 runbook 4장이 매일 예약 작업에 `--keep-snapshots 3`을 권하지만 실제로는 정리되지 않는다. 디스크 여유 검사(20GB)가 마지막 방어선으로 남아 실전 엔진 쪽 위험은 "수집기가 종료 코드 4로 멈춤"까지로 막히지만 `[추론]`, ADR-008 2.7·runbook 5.1이 적은 동작과 코드가 다르다.
- 함께 볼 것(risk-ops 재검토 R3과 같은 지적, 검토자 확인): 실험실 DB는 실험이 쓴 스냅샷 ID를 `experiment.data_snapshot_version`에 둔다(`autotrade-app-lab/src/main/resources/db/migration/V1__lab_baseline.sql:27`, `ExperimentViews.java:43,53`). G1을 고쳐 정리가 실제로 돌기 시작하면 DB에만 기록된 실험의 스냅샷이 지워질 수 있다. 수정 때 DB 참조를 함께 막거나(수집기는 DB 드라이버를 쓰지 않으므로 실험실이 참조 ID 목록 파일을 `experiments/` 아래 남기는 방식 등), runbook에 그 한계를 적는다.
- 함께 볼 것: `--report-dir`을 저장소 밖(예: `D:\reports`)으로 주면 그 상위 폴더(드라이브 루트)를 깊이 4까지 읽는다. 참조 검사 범위가 사용자의 다른 파일로 번진다.
- 고칠 것: 참조 검사에서 품질 리포트 폴더(`reportDir`)를 빼거나 `<ID>.md` 자기 리포트를 제외한다. 실험 폴더는 `reportDir`의 상위를 추정하지 말고 저장소 `experiments/`(또는 명시 옵션 `--experiments-dir`)로 정한다. "품질 리포트가 기본 자리에 있어도 최근 K개 밖은 지운다"와 "실험 설정에 ID가 적힌 것은 남긴다"를 함께 시험하는 테스트(가능하면 `CollectorMainTest` 왕복)를 더한다.

#### 권고 (머지를 막지 않음)
- **H1. 스냅샷 단계 중 보호 구간 진입**: `checkSession`은 API 호출 전과 스냅샷 단계 시작 때만 본다. 전 기간 스냅샷·품질 리포트(2GB 힙)가 길어지면 미국 보호 구간(서울 21:30, 서머타임) 안까지 이어질 수 있다. `KrxDailySnapshotBuilder` 두 번째 훑기의 날짜 반복마다 확인하거나, runbook 3.5에 "전 기간 스냅샷은 18:30 바로 시작" 같은 안내를 둔다.
- **H2. 훑은 구간 사이 빈틈이 스냅샷에 드러나지 않음**: `scanned`가 여러 구간(예: 2016~2017, 2026)일 때 스냅샷 `coverage`에는 첫 접수일과 마지막 날만 있다. 품질 리포트에 훑은 구간 목록을 적는다.
- **H3. 1차 R2·R6 미반영**: 평일 빈 응답을 휴장일로 영구 캐시하는 것(`KrxDailyCollector`), 첫 행이 정정 공시인 묶음을 대체 규칙 대상으로 세지 않는 것(`DartSnapshotBuilder`)은 그대로다. 후속 과제로 남겨도 된다.
- **H4. 캘린더 수록 범위**: KRX 캘린더가 2026-12-31까지라 2027-01-01부터 수집기가 모두 종료 코드 4로 멈춘다(안전한 쪽 실패, ADR-008·runbook에 적혀 있음). 연말 전 캘린더 갱신 작업을 결정 로그 후속 과제나 Q 항목으로 올려 두기를 권한다.

### 판정
수정 필요(G1 스냅샷 보관 규칙이 기본 배치에서 동작하지 않음, B7 risk-ops 재검토 원문 필요)

## 재검토 2026-10-10 (2차)
- 대상: PR #39 / 브랜치 claude/project-thread-vip8tu / 커밋 7cc5941 / main 대비 diff 전체(`git diff origin/main...HEAD`). 앞 재검토(ab86736, 이 파일의 `## 재검토 2026-10-10` 절과 [architect-2.md](architect-2.md)) 뒤 바뀐 것은 `git diff ab86736..7cc5941`: `CallBudget`, `CollectorMain`, `DartSnapshotBuilder`, `KrxDailyCollector`, `SnapshotRetention`과 테스트 4개, ADR-008, runbook, 검토 원문(architect.md·architect-2.md·risk-ops.md, 추가만).
- 점검표: B
- 원문 위치: docs/reviews/README.md 규칙대로 이 파일 아래에 덧붙였다. 앞 내용은 고치지 않았다.
- 직접 실행한 것:
  - risk-ops 검토와 겹치지 않게 작업 폴더가 아닌 별도 복제본(`git clone` 뒤 `git checkout 7cc5941`, 스크래치 폴더)에서 `./gradlew build --rerun-tasks --no-daemon --no-parallel --max-workers=1` → **BUILD SUCCESSFUL, EXIT 0**, 112 tasks 모두 실행. 결과 XML 합계 tests=606, failures=0, errors=0, skipped=0. 수집기 53건(새 테스트 `CallBudgetTest#동시에_돌아도_합산이_빠지지_않는다`, `CollectorMainTest#기본_배치에서_keep_snapshots가_옛_스냅샷을_정리한다`, `#스냅샷을_쓰는_중_보호_구간에_들어서면_멈추고_임시_폴더를_남기지_않는다`, `DartFinancialCollectorTest#원_공시가_수집_범위_앞이라_정정만_있으면_대체_규칙_묶음으로_센다`, `SnapshotRetentionTest#품질_리포트는_참조로_보지_않고_실험_폴더가_없으면_지우지_않는다` 포함), ArchitectureTest 16, ModuleOwnershipTest 13, risk 55, sim 75.
  - 커밋된 검토 원문 확인: 7cc5941의 `architect.md`·`architect-2.md` 재검토 절이 검토자가 쓴 내용과 같고(판정 줄, G1, `data_snapshot_version` 지적 포함), 1차 절은 바뀌지 않았다.
  - G1 수정은 새 왕복 테스트 `CollectorMainTest#기본_배치에서_keep_snapshots가_옛_스냅샷을_정리한다`를 읽어 확인했다. 품질 리포트를 기본 자리(`<experiments>/_data-quality`)에 두고 `--keep-snapshots 1`로 세 번 돌린다. 그 결과 실험이 참조한 10-06과 최근 10-08만 남고 10-07이 지워지며, 품질 리포트 3개는 남는다. 앞 재검토의 재현 조건(품질 리포트가 모든 스냅샷을 참조로 만드는 배치)과 같은 조건이고 통과했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 별도 복제본에서 직접 실행 `./gradlew build --rerun-tasks --no-parallel --max-workers=1` EXIT 0, 606건 실패 0·건너뜀 0(7cc5941) |
| B2 | 통과 | 앞 재검토 근거 유지. 이번 변경에 모듈 의존·ArchUnit·`processIsolation` 변경 없음 |
| B3 | 통과 | 새 명령줄 옵션 `--experiments-dir`(기본 `experiments`)과 보관 규칙 변경(품질 리포트 폴더 제외, 실험 폴더 없으면 정리 안 함, DB 참조 미확인 한계), 최근 7일 평일 빈 응답 미캐시가 ADR-008 2.3·2.7과 runbook 4·5.1장에 반영. 설정 파일 키·스키마·외부 계약 변경 없음 |
| B4 | 통과 | 새 판정은 모두 주입 `Clock`·명시 정렬 위에서 돈다(`KrxDailyCollector`의 `today`는 `clock` 기준, `SnapshotRetention` 정렬 규칙 그대로). 최근 빈 응답을 캐시하지 않아도 스냅샷 입력은 그 실행의 응답(`uncached`)을 쓰므로 같은 응답이면 같은 결과다. 골든 테스트 변경 없음, 통과 |
| B5 | 통과 | 금액 계산 변경 없음 |
| B6 | 통과 | G1·H1·H3(R2·R6)·R5 각각에 실패 경우를 포함한 테스트가 붙었다(위 목록). 지우거나 끈 테스트 없음 |
| B7 | risk-ops 검토 필요 | 이 PR은 실전 엔진·게이트웨이·감시의 빌드 격리 설정과 자원 보호(A8·A12, Q-041)를 건드린다. risk-ops 원문: ab86736 재검토 판정 **승인**은 7cc5941에 커밋되어 있다. 7cc5941 대상 2차 재검토(판정 **승인**)는 이 검토를 쓰는 동안 작업 트리의 [risk-ops.md](risk-ops.md)에 덧붙어 있고 아직 커밋 전이다(검토자가 `git diff`로 확인). 그 절이 커밋되어야 7cc5941 기준 A 점검표 검토가 갖춰진다. 지침 10장에 따라 머지는 사용자가 한다 |
| B8 | 통과 | 새 출력 메시지(`CollectorMain.prune`의 실험 폴더 안내)에 키 없음. 앞 재검토 근거 유지 |

### 앞 재검토 지적 처리
| 항목 | 결과 | 근거 |
|---|---|---|
| G1 보관 규칙이 기본 배치에서 동작 안 함 | 해결 | `SnapshotRetention.referencedIds`가 `reportDir` 아래 파일을 참조에서 뺌(정규화한 절대 경로 `startsWith`). 실험 폴더는 `reportDir` 상위를 추정하지 않고 `--experiments-dir`(기본 `experiments`)로 정하며, 없으면 정리하지 않음(`CollectorMain.prune`, `SnapshotRetention.prune` 첫 줄). 왕복 테스트로 확인 |
| G1 곁가지: 저장소 밖 `--report-dir`이면 드라이브 루트를 읽음 | 해결 | 실험 폴더를 따로 받으므로 더 이상 `reportDir` 상위를 읽지 않음 |
| G1 곁가지: 실험실 DB 참조 | 문서로 처리 | 코드로 막지 않고 ADR-008 2.7·runbook 5.1에 한계와 운영 규칙("실험은 `experiments/<실험ID>/`에 ID를 남긴다")을 적음. 아래 I1 |
| H1 스냅샷 단계 중 보호 구간 | 해결(일봉 쓰기 단계) | `CollectorMain.guarded`가 두 번 훑기의 하루마다 `checkSession`, 걸리면 `SnapshotWriter.close()`가 임시 폴더를 지움. 품질 리포트 단계는 그대로이고 ADR-008 2.7에 한계로 적음 |
| H2 훑은 구간 빈틈 표시 | 미반영 | 권고로 남김 |
| H3 R2 평일 빈 응답 | 반영 | 최근 7일 안 평일 빈 응답은 캐시하지 않음(`KrxDailyCollector.RECENT_EMPTY_DAYS`, `[가정]`) |
| H3 R6 대체 규칙 묶음 | 반영 | 묶음 첫 행이 정정 공시면 대체 규칙 대상으로 셈(`DartSnapshotBuilder`), 테스트 |
| H4 캘린더 수록 범위 | 그대로 | 안전한 쪽 실패, runbook·ADR에 적힘. 후속 과제 권고 유지 |
| R5 동시 실행 | 강화 | 같은 JVM 안 스레드는 `synchronized`로, 프로세스 사이는 파일 잠금으로 맞춤. 동시 실행 테스트 추가 |

### 점검표 밖 발견 (모두 권고, 머지를 막지 않음)
- **I1. 실험실이 지금은 `experiments/`에 스냅샷 ID를 남기지 않는다.** `[사실]` 실험실 운영 코드에서 `experiments/` 아래로 파일을 쓰는 곳을 찾지 못했다(`autotrade-app-lab/src/main` grep). 실험의 스냅샷 ID는 DB `experiment.data_snapshot_version`(`V1__lab_baseline.sql:27`)에만 있다. 그래서 runbook 4장이 권하는 매일 `--keep-snapshots 3`을 그대로 쓰면, 사람이 손으로 ID를 적지 않은 실험의 스냅샷은 사흘 뒤 지워진다. 같은 `--from`·`--to`로 다시 만들면 같은 ID로 복원되고(내용 해시), 문서에 한계가 적혀 있어 막지 않는다. 다만 복원하려면 그 범위의 원문 캐시가 남아 있어야 한다. 배치표 2·3번(sim-engine)에서 실험 실행 때 `experiments/<실험ID>/`에 입력 스냅샷 ID를 남기게 하는 것을 변경 제안으로 권한다. 그 전까지는 runbook의 매일 권고에 "실험실 실험을 돌리는 동안은 `--keep-snapshots`를 빼거나 ID를 적는다"를 덧붙이기를 권한다.
- **I2. `--report-dir`과 `--experiments-dir`이 같으면 모든 실험 기록이 참조에서 빠진다.** `referencedIds`가 `reportDir` 아래를 모두 빼기 때문이다. 두 옵션이 같거나 `experimentsDir`이 `reportDir` 안이면 사용법 오류(종료 코드 2)로 막기를 권한다.
- **I3. 앞 재검토 H2(훑은 구간 빈틈 표시)와 H4(캘린더 갱신 후속 과제)는 그대로다.**

### 판정
승인
