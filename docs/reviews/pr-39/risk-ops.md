# risk-ops 검토: 수집 일괄 명령 autotrade-app-collector(Q-037, D-051, ADR-008): KRX 일봉·상장주식수, OpenDART 재무 증분 수집

- 대상: PR #39 / 브랜치 `claude/project-thread-vip8tu` / 커밋 89fae05로 요청받았으나 검토 중 ecd2af6(`.gitleaks.toml` 추가)이 올라와 **ecd2af6까지** 검토 / main(cf3759a) 대비 `git diff origin/main...HEAD` 전체(56개 파일)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-parallel --max-workers=1` → BUILD SUCCESSFUL, EXIT 0. 테스트가 모두 UP-TO-DATE(캐시)여서 판정 근거로 쓰지 않음.
  - `./gradlew build --rerun-tasks --no-parallel --max-workers=1` → BUILD SUCCESSFUL in 1m 42s, 112 actionable tasks 모두 실행, EXIT 0. 결과 XML 합계(tests/failures/errors/skipped): `:autotrade-risk:test` 55/0/0/0(`RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `BlacklistSnapshotTest` 5 포함), `:autotrade-architecture-test:test` 29/0/0/0(`ArchitectureTest` 16, `ModuleOwnershipTest` 13), `:autotrade-app-engine:test` 48/0/0/0(`RealOrderGatewaysTest` 4 포함), `:autotrade-app-collector:test` 35/0/0/0, `:autotrade-marketdata:test` 76/0/0/0. `verifyProcessIsolation`은 collector·engine·gateway·watchdog 모두 통과.
  - `git diff --name-status origin/main...HEAD`에서 risk·engine·gateway·watchdog·broker·control·config·domain 운영 코드 변경 없음 확인. ArchitectureTest diff는 추가만 있고 삭제 줄은 `ModuleOwnershipTest` 표의 마지막 항목 쉼표 이동 1줄뿐.
  - 실전 프로세스 운영 코드(`autotrade-app-{engine,gateway,watchdog,lab}/src/main`)에서 `SnapshotDailyBarSource|SnapshotManifest|SnapshotFiles|SnapshotFinancials|SnapshotListedShares|marketdata.daily|marketdata.financial` grep → 0건. engine·gateway는 `autotrade-marketdata`에 의존하지만 바뀐 클래스를 부르지 않는다.
  - gitleaks는 이 환경에 없어 직접 돌리지 못했다(`.gitleaks.toml`은 내용 검토, A10은 diff·픽스처 검사로 판정).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 관련 코드·설정·테스트 변경 없음(diff에 `autotrade-risk`, `autotrade-config/src/main` 없음). `RealTradingGuardTest` 31건 강제 재실행 통과. 수집기는 `runtime-config.json`·`secrets.json`을 읽지 않으므로 Guard 값에 닿을 경로가 없다: `ArchitectureTest.java:222` `COLLECTOR_READS_ONLY_ITS_SECRETS`(ConfigLoader·RuntimeConfigStore·RuntimeConfigHolder·LoadedConfig·RuntimeConfig·ProcessStartup 참조 금지), 위반 예제로 규칙이 실제로 잡는지 `ArchitectureTest#수집_일괄_명령은_자기_키_파일만_읽고_증권사_주문을_모른다`(:313) 통과 |
| A2 | 통과 | 기존 Guard 팩토리·출구 규칙 삭제·완화 없음(ArchitectureTest diff는 추가만). 수집기는 `com.autotrade.broker..`·`com.autotrade.domain.order..` 참조 금지(같은 규칙), 런타임 클래스패스에서 broker-api·kiwoom·kis·risk·control·app-engine 제외(`autotrade-app-collector/build.gradle.kts:14-17`, `verifyProcessIsolation` 통과). `GuardedOrderGatewayTest` 8건, `RealOrderGatewaysTest` 4건 통과 |
| A3 | 통과 | 주문 라우팅 코드 변경 없음. 수집기가 부르는 곳은 `KrxOpenApiClient.DEFAULT_BASE`(`https://data-dbg.krx.co.kr`)와 `OpenDartClient.BASE`뿐이고 증권사 플러그인이 클래스패스에 없다(A2 근거). failover 경로 신설 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재발주 로직(`autotrade-risk`, 엔진 주문 경로) 변경 없음. 수집기의 HTTP 재시도(`ApiCaller.java` 일시 오류 2회)는 공개 데이터 GET이라 주문과 무관 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음. 수집기는 주문을 만들지 않는다 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음(`BlacklistSnapshotTest` 5건 통과). 수집 결과(일봉·재무 스냅샷)는 후속 선정 입력이며 주문 경로가 아니다 |
| A7 | 통과 | 계좌별 큐·토큰 버킷·킬스위치 코드 변경 없음, engine 테스트 48건 통과. 수집기의 하루 호출 상한(`CallBudget`)은 KRX·OpenDART 몫이고 증권사 레이트리밋 버킷과 공유하지 않는다(증권사 API를 부르지 않음, 장애표 #17 전제 유지) |
| A8 | **실패** | 프로세스·클래스패스 분리는 지켜졌다(`PROCESSES_ARE_INDEPENDENT` 슬라이스 규칙이 `com.autotrade.app.collector`도 포함, collector `verifyProcessIsolation`). 그러나 **자원 측면 격리가 없다**. ① 수집기는 감시 프로세스가 알지도 멈추지도 못하는 새 작업인데, 5.2 정지 순서와 장애표 #10에 없다. broker-and-fault-design.md 5.1이 실전 외 작업에 요구하는 "OS 프로세스 우선순위 낮춤, 힙·스레드 상한 고정"도 없다. runbook은 오히려 힙 상한을 올리라고 안내하고(`docs/runbooks/collector.md:41` `JAVA_OPTS=-Xmx6g`), 근거 없이 "실전 엔진·게이트웨이가 켜져 있어도 서로 영향이 없다"고 적었다(`docs/runbooks/collector.md:3`). 실행 시간대 제한도 없어 미국장 세션(한국 22:30~06:00, 서머타임 따라 다름)이나 국내장 중에 전체 수집·품질 리포트(전 기간 일봉을 힙에 올림, ADR-008 4장)가 돌 수 있다. ② 디스크 무제한 증가: 아래 F2. 상세와 고칠 내용은 "점검표 밖 발견" F1·F2 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. 수집기는 실시간 시세·증권사 연결을 열지 않는다(연결 수 공유 없음). 단 자원 고갈 시 보유 종목 보호가 마지막까지 남는다는 전제는 A8 F1·F2를 고쳐야 유지된다 |
| A10 | 통과 | 테스트 픽스처 값은 자리표시자·가짜 값(`KRX-PLACEHOLDER`, `DARTKEY`, `SECRETKEY` 등, `CollectorSecretsTest.java:27`, `ApiCallerTest.java:25`). 문서 예시는 `<KRX_OPEN_API_AUTH_KEY>`·`<OPENDART_CRTFC_KEY>`(`docs/config/config-keys.md`). `.gitignore`에 `**/collector-secrets.json`. 키는 `Secret`으로만 보관(`toString` 마스킹), 오류 메시지는 `Redaction`을 거친다: `ApiCaller`가 JDK 예외 메시지를 옮기지 않고 URL을 가림(`ApiCallerTest#인증_오류는_다시_부르지_않고_메시지에_키가_없다`, `#연결_실패_메시지에도_키가_없고_재시도도_상한에_든다`), 파서 메시지를 옮기지 않음(`CollectorSecretsTest#깨진_JSON은_값_없이_거부한다`), 실행 뒤 출력·캐시·스냅샷·리포트 전체에 키 없음(`CollectorMainTest#assertNoKeyAnywhere`, :150-158), 020 응답 경로(`DartFinancialCollectorTest#요청_제한_020이면_그날_멈추고_키는_어디에도_남지_않는다`). 리다이렉트 추종 끔(`JdkHttpGetter`, 키 헤더가 다른 호스트로 가지 않음). ecd2af6의 `.gitleaks.toml`은 기본 규칙 유지(`useDefault = true`) + 가짜 값 하나(`^abcdef1234567890$`) 정확히 일치 예외라 스캔을 넓게 약화하지 않는다. 권고 N1 참고 |
| A11 | 통과 | 지운 테스트·`@Disabled` 없음(diff 삭제 줄 확인). 보호 테스트(`ArchitectureTest`, Guard·GuardedOrderGateway 테스트)는 규칙 추가만. 새 규칙에 위반 고정 예제(`archfixture/LoadsProcessConfig.java`)로 실패하는 경우를 검증. 수집기 새 동작에 실패 경우 테스트 있음: 모르는 키 거부(`CollectorSecretsTest#모르는_키가_있으면_파일_전체를_거부하고_값은_메시지에_없다`), `secrets.json` 미로딩(`#프로세스용_secrets_json은_읽지_않는다`), 상한 초과 차단(`CallBudgetTest#상한에_닿으면_막고_같은_날_다른_실행도_합산한다`, `#한도_초과_응답_뒤에는_그날_더_부르지_않는다`), 응답 모양 이상 시 멈춤(`KrxOpenApiClientTest#모양이_다른_응답은_멈춘다`), 접수일 없는 재무 행 거부(`SnapshotFinancialsTest#접수일이_없는_행이_하나라도_있으면_거부한다`). F1·F2를 고칠 때 그 동작의 실패 경우 테스트가 함께 필요하다 |
| A12 | **실패** | 장애표 #10 "PC 자원 고갈 → 5.2 정지 순서"는 실전 외 작업을 감시 프로세스가 먼저 멈출 수 있다는 전제다. 이 PR이 들여온 수집기는 그 순서에 없고, 감시 프로세스가 멈출 수단도 문서화돼 있지 않으며, 장애표·5.2·장애 테스트(7장) 어디에도 반영되지 않았다(diff에 `docs/team/broker-and-fault-design.md` 변경 없음). 대응표를 바꾸는 것은 지침 8장 2번(장애 대응 규칙) 사용자 결정 대상이므로 이 PR에서는 실행 규칙(F1)으로 전제를 지키고, 대응표 반영은 미확정 항목으로 올려야 한다 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음(수집기는 국내 KRX·OpenDART만). 참고: 수집기 날짜 계산은 `Asia/Seoul`(`CallBudget.SEOUL`)로 거래소 현지 기준이고 금액은 원 단위 `BigDecimal` |
| A14 | 통과 | 결정 로그 D-051(추천안 채택(자율), Q-037 닫음)과 기획서 v14.10(ARC-06, MD-11, 배치표 1번), ADR-001 표, ADR-003, ADR-008, config-keys.md가 코드와 일치(키 파일 이름·항목, 금지 의존, 호출 상한 9,000/10,000, 종료 코드). 단 F1·F2를 고치면 ADR-008·runbook과 미확정 표에 함께 반영해야 한다 |

## 점검표 밖 발견

### 수정 필요
- **F1 (A8, A12) 수집기를 실전 보호 정지 순서 밖에 둔 채 "영향 없음"으로 안내.** [사실] 5.1 원칙(실전 외 작업은 OS 우선순위를 낮추고 힙·스레드 상한을 고정, 감시가 먼저 멈춤)과 5.2 순서에 수집기가 없다. runbook `:3`의 "서로 영향이 없다"는 검증 없는 주장이고 `:41`은 `-Xmx6g`를 권한다. [추론] 같은 PC에서 수 GB 힙과 장시간 HTTP·파일 I/O를 쓰는 작업이 미국장 세션이나 국내장과 겹치면 실전 엔진의 지연 목표(role-guides 6장 완료 기준)를 해칠 수 있다. 고칠 내용(이 PR 범위, 실전 경로 코드 변경 없이):
  1. runbook·ADR-008에서 "영향 없음" 문장을 고치고, **실행 시간대 제한**을 규칙으로 둔다: 실전 계좌가 하나라도 활성(Guard ON)일 수 있는 동안은 그 시장의 세션 준비~종료 구간(국내, 미국 각각 거래소 캘린더·서머타임 기준)에 돌리지 않는다. 가능하면 코드로 막는다: `CollectorMain`이 `autotrade-marketdata` 거래소 캘린더로 KRX·미국 세션 구간이면 기본 거부하고 `--allow-during-session` 같은 명시 옵션으로만 허용(수집기는 Guard 상태를 읽을 수 없으므로 시간 기준이 맞다). 그 거부 동작의 테스트를 둔다.
  2. 힙 상한을 고정한다: `application { applicationDefaultJvmArgs = listOf("-Xmx…") }`로 기본 상한을 두고 runbook의 `-Xmx6g` 권고를 "기간을 줄여 실행"으로 바꾼다. OS 낮은 우선순위로 실행하는 방법(Windows `start /low` 또는 작업 스케줄러 우선순위, Linux `nice -n 19`)을 runbook 4장 예약 작업 예시에 넣는다.
  3. 결정 로그 미확정 표에 risk-ops 항목을 하나 올린다: "수집 일괄 명령(과 ADR-007 선정 일괄 명령 등 단발 명령)을 5.2 정지 순서 1번 앞에 두고 장애표 #10에 '감시 프로세스가 단발 명령을 종료'를 넣을지(risk-ops 추천: 넣음). 장애 대응 규칙 변경이라 **사용자 결정**". 대응표 자체는 사용자 결정 뒤 바꾼다.
- **F2 (A8) 디스크 무제한 증가, 공간 확인 없음.** [사실] `KrxDailyCollector`는 날짜·시장별 원문 응답을 지우지 않고 쌓고(`<설정 폴더>/collector-cache/krx/...`), `SnapshotWriter.commit`은 ID가 "마지막 거래일 + 해시"라 매일 실행마다 새 전 기간 스냅샷 폴더를 만들며 옛 폴더를 지우는 규칙이 없다(`SnapshotWriter.java` commit, `CollectorMain.java:246-247` 기본 위치가 설정 폴더 아래). ADR-008·runbook에 용량·보관 규칙이 없다(grep "보관|삭제|용량|디스크" 0건). [추론] 2010년부터 전체 일봉 CSV는 약 1,100만 행(ADR-008 3장)으로 스냅샷 하나가 수백 MB, 원문 캐시는 수 GB가 될 수 있고, 매일 돌리면 스냅샷만 한 달에 수 GB~수십 GB가 쌓인다. 이 폴더는 실전 프로세스의 설정 폴더와 같은 디스크다. 디스크가 차면 실전 엔진의 상태 기록·DB 쓰기가 실패할 수 있다(장애표 #8 재기동 복구 전제). 고칠 내용:
  1. 실행 시작 때 대상 디스크 여유 공간을 확인하고 기준(예: 설정 폴더 디스크 여유 N GB) 미만이면 종료 코드 1로 멈춘다. 그 거부 테스트를 둔다.
  2. 스냅샷 보관 규칙(예: 소스별 최근 K개만 남김, 실험 결과가 참조하는 ID는 보호)을 코드 또는 runbook 절차로 정하고, 용량 추정치를 `[추론]`으로 runbook에 적는다.

### 권고 (판정에 영향 없음)
- **N1** `--krx-base-url`·`--dart-base-url`(`CollectorMain.java:231-232`)이 아무 주소나 받아 `http://`나 다른 호스트로 인증키(헤더·쿼리)를 보낼 수 있다. `https`만 허용하거나 테스트용임을 명시한 옵션으로 제한하기를 권한다.
- **N2** `CallBudget` 기록 파일에 잠금이 없어 동시 실행 시 합산이 빠진다(runbook 4장에 "동시에 두 개 돌리지 않는다"로만 안내). `FileChannel.lock`으로 막기를 권한다. 증권사 레이트리밋과는 무관하다.
- **N3** engine·gateway·watchdog의 `processIsolation.forbiddenProjects`에 `:autotrade-app-collector`가 없다. 클래스 수준은 `PROCESSES_ARE_INDEPENDENT`가 막지만, 빌드 의존 수준에서도 막도록 넣기를 권한다(architect 영역, 변경 제안).
- **N4** ADR-008 머리말은 "risk-ops(참고: 실전 경로 변경 없음)"이라 적었지만, 이 PR은 프로세스 격리(새 실행 단위)와 자원 정지 순서에 영향을 주므로 A 점검표 대상이다. 앞으로 단발 명령(ADR-007 선정 일괄 명령 포함)을 들일 때는 F1·F2 기준을 같이 적용하기를 권한다.

## 판정
수정 필요(A8, A12: F1 실행 시간대 제한·힙 상한·낮은 우선순위와 미확정 항목 등록, F2 디스크 여유 확인·스냅샷 보관 규칙. 각 거부 동작에 실패 경우 테스트 포함)

## 재검토 2026-10-10
- 대상: PR #39 / 커밋 ab86736 / main(cf3759a) 대비 `git diff origin/main...HEAD` 전체. 앞 검토 뒤 바뀐 것은 `git diff ecd2af6..ab86736`(16개 파일: `RunGuard`·`SnapshotRetention` 신규, `CollectorMain`·`ApiCaller`·`CallBudget`·collector `build.gradle.kts`, engine·gateway·watchdog `build.gradle.kts`, ADR-008 2.7, runbook, 결정 로그 Q-041, 테스트 3개).
- 점검표: A
- 위치: 재검토 원문을 새 파일(`risk-ops-2.md`)로 쓰라는 요청을 받았다. 그러나 `docs/reviews/README.md`가 재검토는 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이라고 정하고 있어 그 규칙을 따랐다. 앞 내용(1~47행, ab86736에 커밋된 원문)은 고치지 않았다.
- 직접 실행한 것:
  - 공유 작업 트리(`/home/claude/autotrade-system`)에서 `./gradlew build --rerun-tasks --no-parallel --max-workers=1`를 두 번 돌렸고 두 번 다 EXIT 1이었다. 첫 번째는 `RealOrderGatewaysTest` 4건이 `NoClassDefFoundError: RealOrderGateways`로 실패했고 `output.bin.idx` 파일이 없었다. 두 번째는 `MarketRulesImplTest` 컴파일 오류가 났고, 로그에 다른 빌드의 "BUILD SUCCESSFUL"이 섞여 있었다. 같은 트리에서 다른 Gradle 빌드가 함께 돌았다(`ps`로 확인). 그래서 코드 결함이 아니라 빌드 간섭으로 판단했고, 판정 근거로 쓰지 않았다.
  - 간섭을 없애려고 ab86736을 스크래치 폴더에 따로 복제해 `./gradlew build --no-parallel --max-workers=1 --no-build-cache`를 돌렸다. BUILD SUCCESSFUL in 2m 11s, 112개 작업 모두 실행, EXIT 0. 모듈별 결과(tests/failures/errors/skipped): `:autotrade-risk:test` 55/0/0/0(`RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8), `:autotrade-architecture-test:test` 29/0/0/0(`ArchitectureTest` 16), `:autotrade-app-engine:test` 48/0/0/0(`RealOrderGatewaysTest` 4), `:autotrade-app-collector:test` 48/0/0/0(`RunGuardTest` 6, `SnapshotRetentionTest` 2, `CollectorMainTest` 9, `CallBudgetTest` 2), 나머지 모듈도 실패 0·건너뜀 0. `verifyProcessIsolation`은 engine·gateway·watchdog·collector 4개 모두 통과.
  - 실전 프로세스 운영 코드 변경은 이번에도 없다. engine·gateway·watchdog은 `build.gradle.kts`의 `forbiddenProjects`에 `:autotrade-app-collector`를 더한 것뿐이라 격리가 강화됐다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 앞 검토와 같다(Guard 코드·설정·테스트 변경 없음, `RealTradingGuardTest` 31건 통과, `COLLECTOR_READS_ONLY_ITS_SECRETS` 유지). 새 `RunGuard`는 Guard 상태를 읽지 않고 시간·디스크만 본다(`RunGuard.java` 클래스 주석) |
| A2 | 통과 | ArchitectureTest 변경 없음(16건 통과). `GuardedOrderGatewayTest` 8건, `RealOrderGatewaysTest` 4건 통과. 수집기 의존은 그대로이고, 세 실전 프로세스의 빌드 격리 목록에 수집기를 더해 반대 방향도 막았다(`autotrade-app-engine/build.gradle.kts:13`, gateway·watchdog 같은 줄) |
| A3 | 통과 | 주문 라우팅 변경 없음. 수집기 주소 옵션은 이제 `https://`만 받는다(`CollectorMain.Options.https`, `CollectorMainTest#주소는_https만_받는다`) |
| A4 | 해당 없음 | 주문 타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음(`BlacklistSnapshotTest` 통과) |
| A7 | 통과 | 계좌 큐·토큰 버킷·킬스위치 변경 없음(engine 48건 통과). `CallBudget`은 이제 파일 잠금 안에서 읽고 쓴다(`CallBudget.locked`). KRX·OpenDART 몫이라 증권사 버킷과 무관하다 |
| A8 | 통과 | 앞 검토 F1·F2를 반영했다. ① 장 시간 보호 구간 거부(`RunGuard.checkSession`): 국내·미국 정규장 개장 60분 전부터 폐장 30분 뒤까지는 시작할 때(`CollectorMain.java:68`), API 호출마다(`ApiCaller.java:49`, `beforeCall` → `guard::checkSession`, `CollectorMain.java:109,153`), 스냅샷 쓰기 전에(`:117,162`) 확인한다. 캘린더로 서머타임·휴장일을 처리하고, 캘린더 수록 범위 밖이면 막는다(실패 쪽으로 닫힘). 테스트: `RunGuardTest#국내_정규장_앞뒤_보호_구간은_막는다`(07:59 통과, 08:00 거부, 15:59 거부, 16:00 통과 경계), `#미국_정규장은_서머타임을_따라_막는다`(EDT·EST 경계), `#휴장일과_주말은_막지_않는다`, `#캘린더_수록_범위_밖이면_판단할_수_없어_막는다`, `#명시_옵션이면_장_시간에도_통과한다`, `CollectorMainTest#국내_장_시간에는_시작하지_않고_종료_코드_4`, `#미국_장_시간에는_시작하지_않는다`, `#실행_중_보호_구간에_들어서면_다음_호출_전에_멈춘다`. 이 범위는 기획서 시장 범위(정규장만, 프리·애프터 제외, `docs/spec/autotrade-spec.md:59`, overseas-design OV2)와 맞다. ② 힙 상한 2GB 고정(`autotrade-app-collector/build.gradle.kts:13` 설치본, `:33` `run`), runbook에서 `-Xmx6g` 권고를 지웠다. ③ 낮은 OS 우선순위 실행 절차(runbook 4장, Windows `start /low`, `nice -n 19`). ④ 디스크 여유 확인(`RunGuard.checkDisk`, 기본 20GB `[가정]`, 시작할 때와 스냅샷 쓰기 전): `RunGuardTest#디스크_여유가_기준_미만이면_막고_없는_폴더는_있는_상위_폴더로_본다`, `CollectorMainTest#디스크_여유가_기준_미만이면_시작하지_않는다`. ⑤ runbook 첫 문단의 "서로 영향이 없다"를 "CPU·메모리·디스크를 나눠 쓴다"로 고쳤다. 남은 위험은 아래 R1·R2 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음. 자원 보호가 위 A8대로 생겨서, 자원 고갈 시 보유 종목 보호를 끝까지 남긴다는 전제가 수집기 때문에 약해지지 않는다 |
| A10 | 통과 | 앞 검토 근거가 그대로 유효하다. 새 테스트 픽스처에도 실제 키가 없다(diff 확인). 주소 옵션이 https만 받게 되어 키가 평문으로 나갈 경로가 닫혔다(N1 반영) |
| A11 | 통과 | 지운 테스트·`@Disabled` 없음. `CollectorMainTest`의 기존 시계 값을 `2026-03-20T03:00:00Z`에서 `10:00:00Z`로 바꿨다. 03:00Z는 서울 12:00이라 새 보호 구간에 걸리기 때문이고, 그 테스트가 원래 검증하던 내용은 줄지 않았다(diff 확인). 새 거부 동작마다 실패 경우 테스트가 있다(A8 목록) |
| A12 | 통과 | 장애표 #10·5.2의 전제(실전 외 작업이 실전 자원을 먼저 내준다)는 수집기가 스스로 물러나는 방식으로 지켰다(ADR-008 2.7). 표 자체를 바꾸는 일은 장애 대응 규칙 변경이라 Q-041로 사용자 결정 대기다(`docs/decisions/README.md:71`). 표와 장애 테스트를 바꾸지 않은 상태에서 전제가 깨지지 않으므로 통과로 본다 |
| A13 | 해당 없음 | 미국 주문 경로 변경 없음. 참고: 미국 보호 구간은 `America/New_York` 캘린더 세션으로 계산하고, 서머타임 경계를 테스트로 확인했다(A8) |
| A14 | 통과 | ADR-008 2.7·4장, runbook 3.5·4·5.1장, 결정 로그 Q-041이 코드와 일치한다(보호 구간 60/30분, 2GB, 20GB, 종료 코드 4, `--keep-snapshots` 기본 꺼짐). ADR-008 상태 줄도 "risk-ops 검토 후 사용자 머지 대상"으로 고쳤다(N4 반영) |

### 남은 위험 (판정에 영향 없음, 후속 권고)
- **R1 스냅샷·품질 리포트 단계는 도중에 멈추지 않는다.** 보호 구간 확인은 그 단계를 시작하기 전에만 한다(`CollectorMain.java:117,162`). 보호 구간 직전에 시작하면 2GB 힙 안에서 전 기간 읽기가 구간 안까지 이어질 수 있다 `[추론]`. 앞쪽 60분 여유, 힙 상한, 낮은 우선순위로 영향은 제한된다. 단계 시작 전에 "남은 여유 시간이 예상 소요보다 짧으면 거부"를 넣는 것을 권한다. 또 디스크 여유는 시작할 때와 스냅샷 전에만 보므로, 한 번 실행이 캐시로 수 GB를 쓰면 여유가 20GB 아래로 내려갈 수 있다 `[추론]`. 호출 N회마다 다시 확인하는 것을 권한다.
- **R2 KRX 캘린더 수록 범위가 2026-12-31까지다.** 2027-01-01부터 수집기는 모든 시각에 종료 코드 4로 멈춘다(실패 쪽으로 닫혀 안전하다). 같은 캘린더를 쓰는 실전·실험실 쪽 영향도 함께 보고 연말 전에 갱신해야 한다(data-engineer). runbook 3.5에 적혀 있다.
- **R3 (risk-ops 영역 밖, architect·sim-engine 참고) 스냅샷 보관 규칙이 실험실 DB의 참조를 보지 않는다.** `SnapshotRetention`은 `experiments/` 아래 텍스트 파일만 훑는다. 실험실 DB는 실험이 쓴 데이터 스냅샷 ID를 `data_snapshot_version` 칸에 둔다(`autotrade-app-lab/src/main/resources/db/migration/V1__lab_baseline.sql:27`). runbook이 매일 예약 실행에 `--keep-snapshots 3`을 권하므로, DB에만 기록된 실험의 스냅샷이 지워질 수 있다. 캐시로 같은 기간을 다시 만들면 복원되지만, 결정론 재현과 나중에 ADR-007 실전용 선정 결과의 입력 추적에 영향이 있다. 실전 주문 안전과는 무관해서 이 판정에는 넣지 않는다.

## 판정 (재검토)
승인

참고: 이 PR은 프로세스 격리와 자원 정지 순서(장애 대응)에 걸리는 실전 관련 PR이다. 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 머지 전에 독립 QA를 받아야 한다(지침 11장). Q-041은 사용자 결정 대기다.

## 재검토 2026-10-10 (2차)
- 대상: PR #39 / 커밋 7cc5941 / main(cf3759a) 대비 `git diff origin/main...HEAD` 전체. 앞 재검토(ab86736) 뒤 바뀐 것은 `git diff ab86736..7cc5941`이다. 코드는 `CallBudget`, `CollectorMain`, `DartSnapshotBuilder`, `KrxDailyCollector`, `SnapshotRetention`과 그 테스트 4개가 바뀌었고, 문서는 ADR-008, runbook, architect 원문 2개, 이 파일(앞 재검토 절, 추가만)이 바뀌었다.
- 점검표: A
- 직접 실행한 것:
  - architect 검토와 겹치지 않도록 7cc5941을 스크래치 폴더에 따로 복제해 `./gradlew build --no-parallel --max-workers=1 --no-build-cache`를 돌렸다. BUILD SUCCESSFUL in 1m 44s, 112개 작업 모두 실행, EXIT 0. 모듈별 결과(tests/failures/errors/skipped): `:autotrade-risk:test` 55/0/0/0(`RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8), `:autotrade-architecture-test:test` 29/0/0/0(`ArchitectureTest` 16), `:autotrade-app-engine:test` 48/0/0/0(`RealOrderGatewaysTest` 4), `:autotrade-app-collector:test` 53/0/0/0(`CollectorMainTest` 11, `SnapshotRetentionTest` 3, `CallBudgetTest` 3, `RunGuardTest` 6). 다른 모듈도 실패 0·건너뜀 0.
  - main 대비 실전 쪽 모듈에서 바뀐 것은 engine·gateway·watchdog의 `build.gradle.kts`(수집기를 빌드 격리 목록에 추가)뿐이다. 지운 파일은 없고, 추가 줄의 `@Disabled`·`@Ignore`·`assumeTrue`는 검토 원문 인용에만 있다.
  - 이 파일의 앞 내용(1~83행)은 커밋 과정에서 바뀌지 않았다(`git diff ab86736..7cc5941`에 이 파일의 삭제 줄 없음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 이번 변경은 수집기 안에서만 일어났다. Guard 코드·설정·테스트 변경 없음, `RealTradingGuardTest` 31건 통과, `COLLECTOR_READS_ONLY_ITS_SECRETS` 유지 |
| A2 | 통과 | ArchitectureTest 변경 없음(16건 통과). `GuardedOrderGatewayTest` 8건, `RealOrderGatewaysTest` 4건 통과. 수집기 의존과 세 실전 프로세스의 빌드 격리 목록은 앞 재검토 그대로다 |
| A3 | 통과 | 주문 라우팅 변경 없음. 주소 옵션 https 제한도 그대로다 |
| A4 | 해당 없음 | 주문 타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 통과 | 계좌 큐·버킷·킬스위치 변경 없음. `CallBudget`은 파일 잠금 위에 같은 프로세스 안의 스레드끼리도 순서를 맞추는 잠금(`IN_PROCESS`)을 더했다(`CallBudgetTest#동시에_돌아도_합산이_빠지지_않는다`). KRX·OpenDART 몫이라 증권사 버킷과 무관하다 |
| A8 | 통과 | 자원 보호가 강화됐다. 앞 재검토 R1의 앞부분(스냅샷 쓰기 단계는 도중에 멈추지 않음)을 반영했다. 일봉 스냅샷을 쓰는 동안 하루 읽을 때마다 보호 구간을 확인하고(`CollectorMain.guarded`, `KrxDaySource.rows`에서 `guard.checkSession()`), 걸리면 `RunGuard.Refused`를 던진다. 그러면 `SnapshotWriter.close()`가 임시 폴더를 지우고 종료 코드 4로 끝난다(`CollectorMainTest#스냅샷을_쓰는_중_보호_구간에_들어서면_멈추고_임시_폴더를_남기지_않는다`). 품질 리포트 단계(전 기간 읽기)는 여전히 도중에 멈추지 않지만 ADR-008 2.7에 한계로 적혀 있고, 2GB 힙과 낮은 우선순위가 영향을 묶는다. `KrxDailyCollector`의 최근 7일 평일 빈 응답 재호출은 호출 수만 조금 늘린다(하루 상한 안, 디스크·메모리 영향 미미). 보관 규칙은 더 보수적으로 바뀌었다. 실험 폴더가 없으면 아무것도 지우지 않고(`SnapshotRetention.prune` 첫 줄, `CollectorMain.prune`), 품질 리포트는 참조로 세지 않는다(`SnapshotRetentionTest#품질_리포트는_참조로_보지_않고_실험_폴더가_없으면_지우지_않는다`, `CollectorMainTest#기본_배치에서_keep_snapshots가_옛_스냅샷을_정리한다`). 그래서 F2(디스크 증가)에 대한 정리가 실제 예약 실행에서 동작한다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음. A8대로 수집기가 실전 자원을 먼저 내주는 전제가 유지된다 |
| A10 | 통과 | 새 테스트 픽스처에 실제 키 없음(diff 확인). 키 가림·주소 제한·리다이렉트 끔은 그대로다 |
| A11 | 통과 | 지운 테스트·건너뛰는 테스트 없음. 바뀐 기대값 하나를 확인했다: `CollectorMainTest#krx_daily는_일봉_스냅샷과_품질_리포트를_만든다`의 재실행 호출 수가 `hasSize(3)`에서 `hasSize(3 + 6)`이 됐다. 최근 평일 빈 응답을 캐시하지 않는 새 동작(`KrxDailyCollector.RECENT_EMPTY_DAYS`)의 직접 결과이고, 같은 스냅샷 ID 확인은 남아 있어 약화가 아니다. 새 동작마다 테스트가 있다(위 A7·A8, `DartFinancialCollectorTest#원_공시가_수집_범위_앞이라_정정만_있으면_대체_규칙_묶음으로_센다`) |
| A12 | 통과 | 장애표·5.2·장애 테스트 변경 없음. 전제(실전 외 작업이 먼저 물러남)는 수집기 자체 보호로 유지되고, 표 반영은 Q-041 사용자 결정 대기다(`docs/decisions/README.md` Q-041 행) |
| A13 | 해당 없음 | 미국 주문 경로 변경 없음. 보호 구간 계산은 앞 재검토와 같다 |
| A14 | 통과 | ADR-008 2.3(최근 7일 빈 응답 재호출 `[가정]`)·2.7(쓰기 중 확인, 보관 규칙의 실험 폴더·품질 리포트·DB 한계)과 runbook 4·5.1장이 코드와 일치한다 |

### 남은 위험 (판정에 영향 없음)
- 앞 재검토 R1의 뒷부분: 품질 리포트 단계는 도중에 멈추지 않고, 디스크 여유는 시작할 때와 스냅샷 전에만 본다. 그대로 남아 있다.
- 앞 재검토 R2: KRX 캘린더가 2026-12-31까지다. 그대로 남아 있고 runbook에 적혀 있다.
- 앞 재검토 R3: 실험실 DB에만 적힌 참조는 보관 규칙이 보지 못한다. 이번에 ADR-008 2.7과 runbook 5.1에 한계와 운영 규칙("실험은 `experiments/<실험ID>/`에 ID를 남긴다")으로 적혔다. 코드로 막지는 않았다. 실전 주문 안전과는 무관하다(architect·sim-engine 참고).

## 판정 (2차 재검토)
승인

참고: 실전 관련 PR(프로세스 격리, 자원 정지 순서)이다. 지침 10·11장에 따라 독립 QA를 받은 뒤 사용자가 직접 확인하고 머지한다. Q-041은 사용자 결정 대기다.

## 재검토 2026-10-10 (3차)
- 대상: PR #39 / 커밋 8deb727 / main(cf3759a) 대비 diff 전체. 2차 재검토(7cc5941) 뒤 바뀐 것은 `git diff 7cc5941..8deb727`(21개 파일)이다. 독립 QA 보고서 `docs/reviews/pr-39/qa.md`의 F1·F2와 R3~R5를 반영했다. 주요 변경: `DailyBarQualityCheck.checkSnapshot`(품질 리포트를 날짜 순으로 읽어 일봉 전체를 메모리에 올리지 않음), `CollectorMain.finishKrxSnapshot`(정리를 리포트보다 먼저, 재무 스냅샷은 정리 대상에서 뺌), 호출 기록 위치를 `<설정 폴더>/collector-state`로 옮김, `--max-calls` 상한을 약관 한도로 묶음, `KrxDailySnapshotBuilder` 구간별 수정 계수, CI `scripts/ci/check-collector-keys.sh`.
- 점검표: A
- 직접 실행한 것:
  - 작업 폴더를 함께 쓰지 않도록 `/tmp/claude-0/review-riskops`에 복제해 8deb727을 체크아웃하고 `./gradlew build --no-parallel --max-workers=1 --no-build-cache`를 돌렸다. BUILD SUCCESSFUL in 3m 32s, 112개 작업 모두 실행, EXIT 0. 모듈별 결과(tests/failures/errors/skipped): `:autotrade-risk:test` 55/0/0/0(`RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8), `:autotrade-architecture-test:test` 29/0/0/0(`ArchitectureTest` 16), `:autotrade-app-engine:test` 48/0/0/0(`RealOrderGatewaysTest` 4), `:autotrade-app-collector:test` 56/0/0/0(`CollectorScaleTest` 1, `CollectorMainTest` 13, `RunGuardTest` 6), `:autotrade-marketdata:test` 79/0/0/0(`SnapshotDailyBarSourceTest` 12). 다른 모듈도 실패 0·건너뜀 0.
  - 같은 복제본에서 `scripts/ci/check-collector-keys.sh`는 EXIT 0, `scripts/ci/check-python-collectors.sh`도 EXIT 0이었다.
  - `git diff --name-only cf3759a...8deb727`에서 실전 쪽 모듈 변경은 engine·gateway·watchdog의 `build.gradle.kts`(수집기를 빌드 격리 목록에 추가)뿐이고, 지운 파일은 없다. 실전 프로세스 운영 코드는 `marketdata.daily`·`marketdata.financial`·`DailyBarQualityCheck`를 참조하지 않는다(grep 0건). `SnapshotDailyBarSource`의 행 파싱을 `bar`·`periods`로 뽑아낸 것은 동작을 바꾸지 않는 리팩터링이고 기존 테스트를 통과했다.
  - 이 파일의 앞 내용은 바뀌지 않았다(`git diff 7cc5941..8deb727`에서 이 파일의 삭제 줄 0).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 코드·설정·테스트 변경 없음, `RealTradingGuardTest` 31건 통과, `COLLECTOR_READS_ONLY_ITS_SECRETS` 유지. 새 호출 기록 위치 `collector-state`는 설정 폴더 아래 별도 폴더이고 `runtime-config.json`·`secrets.json`을 열지 않는다(`CollectorMain.Options.budgetDir`) |
| A2 | 통과 | ArchitectureTest 변경 없음(16건 통과). `GuardedOrderGatewayTest` 8건, `RealOrderGatewaysTest` 4건 통과. 의존과 빌드 격리 목록은 그대로다 |
| A3 | 통과 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 통과 | 계좌 큐·버킷·킬스위치 변경 없음. 수집기 하루 상한은 `--cache-dir`를 바꿔도 합산되고(`budgetDir`), `--max-calls`가 약관 한도를 넘지 못한다(`CollectorMain.Options.parse`, KRX 10,000·OpenDART 20,000). 증권사 버킷과 무관하다 |
| A8 | 통과 | QA F1 해소를 확인했다. 앞 검토들에서 내가 "남은 위험"(품질 리포트 전 기간 적재)으로만 둔 부분이 실측상 2GB 힙에서 OOM이었고, 그 때문에 F2 대응(스냅샷 정리)이 기본 배치에서 한 번도 돌지 않았다는 QA 지적이 맞았다. 수정: ① 정리를 리포트보다 먼저 한다(`finishKrxSnapshot`). ② 리포트는 날짜 순 스트리밍 검사라 메모리가 행 수와 무관하다(`DailyBarQualityCheck.checkSnapshot`). ③ 하루를 넘길 때마다 `guard::checkSession`을 불러, 앞 재검토 R1의 "리포트 단계는 도중에 멈추지 않음"도 해소됐다. 검증: `CollectorScaleTest#전_기간_규모에서도_정리와_품질_리포트가_힙_상한_안에서_끝난다`가 별도 JVM `-Xmx128m`에서 120만 행으로 정리와 리포트 완료를 확인하고, 같은 힙에서 예전 방식이 `OutOfMemoryError`인 것도 대조로 확인한다(통과). 장 시간 보호, 힙 2GB, 디스크 20GB 확인은 그대로다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음. A8대로 수집기가 실전 자원을 먼저 내주는 전제가 기본 배치에서도 실제로 성립한다 |
| A10 | 통과 | 테스트 가짜 값을 `SECRETKEY`에서 `TESTKEY`로 바꿨고 단언은 같다(`ApiCallerTest`). 새 CI 검사 `check-collector-keys.sh`가 저장소 전체(검토 원문 제외)에서 `crtfc_key`·`crtfc-key`·`auth-key`·`AUTH_KEY` 값 패턴을 막고, 직접 실행해 EXIT 0이었다. `.github/workflows/ci.yml`에 단계를 추가했다 |
| A11 | 통과 | 지운 테스트·건너뛰는 테스트 없음. 바뀐 기대값은 강화 쪽이다. `KrxDailySnapshotBuilderTest`는 재사용 코드의 새 회사 분할이 옛 회사 일봉에 곱해지지 않는지 행 단위로 더 확인하고(QA R5), `adjustmentEvents` 기대값이 0에서 1이 된 것은 테스트 입력에 분할을 더했기 때문이다. 새 동작마다 테스트가 있다(`CollectorScaleTest`, `CollectorMainTest` 2건 추가, `SnapshotDailyBarSourceTest` 3건 추가) |
| A12 | 통과 | 장애표·5.2·장애 테스트 변경 없음. 전제는 수집기 자체 보호로 유지되고 Q-041은 사용자 결정 대기다 |
| A13 | 해당 없음 | 미국 주문 경로 변경 없음 |
| A14 | 통과 | D-051 ⑤(정리는 일봉만, 리포트 스트리밍), ADR-008, runbook, 기획서 MD-11(CI 검사 추가)이 코드와 일치한다 |

### 남은 위험 (판정에 영향 없음)
- KRX 캘린더가 2026-12-31까지라는 점(앞 재검토 R2)과, 실험실 DB에만 적힌 일봉 스냅샷 참조를 보관 규칙이 보지 못한다는 점(R3, 운영 규칙으로만 막음)은 그대로다.
- 디스크 여유는 여전히 시작할 때와 스냅샷 쓰기 전에만 본다. 이번 수정으로 기본 배치에서 정리가 실제로 돌게 되어 누적 위험은 줄었다.
- 검토자 메모: 1~2차 재검토에서 품질 리포트 메모리를 실측하지 않고 "영향은 제한된다"로 판단했는데, 그 판단이 틀렸다. 앞으로 자원 보호 항목(A8)은 실제 규모 테스트나 실측을 근거로만 통과를 준다.

## 판정 (3차 재검토)
승인

참고: 실전 관련 PR(프로세스 격리, 자원 정지 순서)이다. 독립 QA의 재판정을 받은 뒤 사용자가 직접 확인하고 머지한다(지침 10·11장). Q-041은 사용자 결정 대기다.
