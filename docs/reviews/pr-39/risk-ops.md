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
