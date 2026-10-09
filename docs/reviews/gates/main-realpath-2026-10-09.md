# QA 검토: main 실전 경로 전체 (PR #8·#9 중심)
- 대상: main 커밋 `0ea575d` 전체. 실전 경로 코드(`autotrade-risk` `risk.guard`, `autotrade-app-engine` `order`·`EngineMain`, `autotrade-domain` `order`, `autotrade-broker-api`, `autotrade-config` 실전 계좌 키, `ArchitectureTest`)와 PR #8(기획서 v13·D-023, 문서만), PR #9(RealTradingGuard 골격, ADR-002)
- 점검표: A 전체 + B1, B6 + 프로젝트 지침 8장 과금 검사(조정 세션 전달, 아래 "과금 검사" 참고)
- 검토자: 독립 QA 프로젝트("자동매매 시스템 QA"), 2026-10-09
- 직접 실행한 것:
  - `./gradlew build` → **BUILD SUCCESSFUL**(1분 25초). 테스트 289건, 실패 0, 오류 0, 건너뜀 0. 보호 테스트 결과: `RealTradingGuardTest` 31/31, `GuardedOrderGatewayTest` 8/8, `RealOrderGatewaysTest` 4/4, `EngineMainTest` 3/3, `ArchitectureTest` 12/12, `ConfigLoaderTest` 20/20, `RuntimeConfigHolderTest` 6/6 통과.
    - 실행 환경 메모: 이 컨테이너에서 Maven Central이 `429 Too Many Requests`를 돌려 첫 10회 시도는 의존성 해석 단계에서 실패했다. 저장소 파일을 고치지 않고 Gradle init 스크립트(`-I`, 저장소 밖)로 Maven Central 공식 미러(`maven-central.storage-download.googleapis.com`)를 앞에 두고 같은 버전을 받아 빌드했다. 코드·테스트 결과와는 무관하다.
  - GitHub Actions `CI` run 28(`0ea575d`, push): success (교차 확인용, 판정 근거는 위 직접 실행)
  - ArchUnit 우회 실험(커밋하지 않음, 실험 뒤 삭제하고 `git status` 깨끗함 확인): 아래 "점검표 밖 발견" F-1
  - 비밀값: 작업 트리 grep(앱 키·시크릿·계좌번호·토큰 패턴)과 `git log -p --all` grep → 0건. 테스트 픽스처는 `<ACCOUNT_NUMBER>`·`FAKE_SECRET` 자리표시자만 쓴다

## 전제: main에 실제로 있는 실전 코드
- [사실] 실전 경로 코드는 Guard 골격뿐이다. 증권사 플러그인 구현, 실전 엔진의 계좌 워커·주문 큐·멱등 키 발급기·주문 상태머신·블랙리스트·서킷브레이커·PANIC SELL·보호 시세 경로·장애 대응은 **없다**(`EngineMain.java:24-26` 주석, ADR-002 6장). 감시 프로세스는 설정 로딩만 한다(`WatchdogMain.java:8`).
- [사실] PR #8은 문서만 바꿨다(`git show --stat a8932f7`: 결정 로그, Q-014 검토안, 기획서, 장애 설계 2줄). "13건"은 요구사항으로 기획서에 들어갔고 코드로는 거의 구현되지 않았다(아래 표).
- 그래서 "미구현이라 증명할 수 없는" 항목은 점검표 규칙대로 **확인 못 함**으로 적었다. 확인 못 함은 통과가 아니다.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard.java:62-73` 생성자는 상태 인자를 받지 않고 모든 계좌를 `Snapshot.INITIAL`(OFF, `:332`)로 만든다. 상태는 메모리만(`:337`). 설정에 활성화 키 없음(`RuntimeConfig.RealTrading`, `autotrade-default.json`), `enabled`가 든 runtime-config는 파일째 거부. 테스트: `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `#상태를_받아_열린_채로_만드는_생성_경로가_없다`, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 모두 통과. 단, **런타임에 사람 확인 없이 Guard를 여는 코드를 빌드가 막는다는 주장은 깨진다**(F-1) |
| A2 | 통과(조건 있음) | 출구 생성은 `RealOrderGateways.open`(`RealOrderGateways.java:47-63`)뿐이고, 허가를 플러그인 호출 전에 받는다(`:59`). `GuardedOrderGateway`는 호출마다 허가·판정을 다시 본다(`GuardedOrderGateway.java:61-68`). ArchUnit `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`(`ArchitectureTest.java:130-134`)와 위반 픽스처 4건 검출(`#규칙은_위반을_실제로_잡아낸다`) 통과, PR #9 이후 규칙 변경 없음(`git diff ae4343d 0ea575d -- autotrade-architecture-test` 비어 있음). 조건: `OrderGateway` 구현을 직접 `new` 하는 우회는 규칙이 못 잡는다(ADR-002 4장이 2단계로 미룸). 지금은 플러그인 구현이 없어 우회할 대상이 없다. 플러그인 모듈이 생기는 PR에서 규칙이 같이 들어오지 않으면 그 PR은 A2 실패다 |
| A3 | 통과 | `RealOrderGateways.java:51-54` 플러그인 증권사 ≠ 계좌 증권사면 거부, `GuardedOrderGateway.java:41-44` 출구 계좌 ≠ 허가 계좌면 거부, `:58-60` 다른 계좌 주문 거부. 다른 증권사로 넘기는 코드 경로 없음. `RealOrderGatewaysTest#다른_증권사_플러그인으로는_출구를_만들_수_없다`, `GuardedOrderGatewayTest#다른_계좌의_출구에_허가를_붙일_수_없다`, `#다른_계좌_주문을_이_출구로_보낼_수_없다` 통과 |
| A4 | 확인 못 함 | 타임아웃→조회→미접수 확정 후 새 키로 1회 재발주 로직이 없다. 있는 것은 계약 문서(`OrderGateway.java:18-20`)와 결과 타입(`OrderQueryResult` NotFound/Indeterminate 구분)뿐. 재발주 횟수 제한·발급기는 "risk-ops 별도 작업"(`IdempotencyKey.java:17-18`). 실패 테스트 없음 |
| A5 | 확인 못 함 | 키 형식 `{date}-{code}-{side}-{seq}`는 `IdempotencyKey.java:20-58`에 있고 `OrderRequest`가 키와 종목·방향 일치를 검사(`OrderRequest.java:30-32`). 그러나 계좌별 영속 발급기·전송 키 영속 기록이 없어 "재기동 후 중복 없음"을 확인할 코드·테스트가 없다(TST-01 #3 미구현) |
| A6 | 확인 못 함 | 블랙리스트·수동 보유 필터 코드가 실전 경로에 없다(`grep -i blacklist` 결과 sim·risk 계약 주석뿐, `RiskPolicy.java:51`은 "묶음 구성은 risk-ops 작업"). 유일한 실전 출구 `GuardedOrderGateway`에 블랙리스트 검사가 없고, 보호 모드에서는 모든 매도가 통과한다(F-2). RSK-12·13 실패 테스트 없음 |
| A7 | 확인 못 함 | 통과한 부분: Guard 상태가 계좌별 독립(`RealTradingGuard.java:55, 335-346`), `#한_계좌의_전이는_다른_계좌에_영향이_없다` 통과. 확인 못 한 부분: 계좌별 단일 직렬 큐·토큰 버킷 구현이 없다(`OrderGateway.java:24-25`는 "가정"으로만 적음). 장애 테스트 #8(한 계좌 킬스위치) 미구현. 같은 계좌 하향 전이가 상향 감사 기록을 기다리는 구조는 F-4 |
| A8 | 확인 못 함 | 통과한 부분: 엔진 런타임 클래스패스에서 sim·lab·ui·gateway·Spring 금지(`autotrade-app-engine/build.gradle.kts:12-15`, `processIsolation` 검사가 build에 포함), `ArchitectureTest#실험실_외_프로세스는_Spring을_쓰지_않는다`, `#프로세스_진입점끼리_참조하지_않는다` 통과. 확인 못 한 부분: 자원 부족 정지 순서(RSK-07), 부하·강제 종료 시험(TST-01 #2, ARC-03 7단계 인수 조건) 미구현 |
| A9 | 확인 못 함 | 보호 시세 경로(RSK-10), 서버 측 손절(RSK-06), 종목 단위 시세 공백 처리(#15) 코드 없음. Guard의 보호 모드 판정(청산·손절 통과)만 있음(`RealTradingGuard.java:106-111`, `#끄면_보호_모드가_되어_신규_진입은_막고_청산_손절은_통과한다` 통과) |
| A10 | 통과 | 작업 트리·전체 커밋 이력 grep 0건. 설정 예시와 테스트는 자리표시자(`EngineMainTest.java:48` `<ACCOUNT_NUMBER>`). 계좌번호는 secrets.json 전용 경로(`SecretPaths` `real-trading/account-numbers/*`), runtime-config에 있으면 거부(`ConfigLoaderTest` 통과). 감사 로그 `LoggingGuardAuditLog.java:17-18`은 계좌 별칭·상태·주체·사유만 남김. CI에 gitleaks(`ci.yml:95-108`) |
| A11 | 통과 | 보호 테스트(`ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`)와 규칙이 PR #9 이후 바뀌지 않았다(`git diff ae4343d 0ea575d`에 해당 파일 없음). `@Disabled`·`assume` 0건, 건너뜀 0건. 새 동작(PR #9)에는 실패 경우 테스트가 있다(OFF 주문 거절, 허가 거부, 단계 건너뛰기 거부, 만료·재사용 확인 거부, 감사 실패 시 상향 금지). 단, F-1의 우회를 잡는 테스트는 없다 |
| A12 | 확인 못 함 | PR #9 동작은 장애 대응표 #3·#4·#8의 전제(주문 세션 차단, 조회 우선)를 깨지 않는다고 읽힌다 `[추론]`. 그러나 장애 테스트 8종은 자동화·수동 기록이 모두 없다(`docs/qa/README.md` 1장도 "7단계에서"). 또 대응표 종 수가 문서마다 다르다: 점검표 A12와 `role-guides.md:135`는 14종, 기획서 RSK-08(`autotrade-spec.md:180`)은 16종, 실제 `broker-and-fault-design.md` 6장은 #17까지 17종(D-024가 #17 추가). 무엇을 기준으로 깨지 않는지 판정할 기준표가 하나로 정해져 있지 않다 |
| A13 | 해당 없음 | 지금 실전 경로 코드(Guard, 출구)는 시장별 분기·시각 계산·금액 계산을 하지 않는다. Guard가 쓰는 시각은 2차 확인 유효 시간(Instant, 주입 Clock)뿐(`RealTradingGuard.java:287-289`). 멱등 키 날짜 규칙은 `MarketRules.tradingDate`(거래소 시간대)로 문서화(`IdempotencyKey.java:12`, `MarketRules.java:53-54`)됐지만 실전 발급기가 없다 |
| A14 | 실패 | 기획서·결정 로그와 코드가 어긋난 곳 3건. (1) **ORD-07 상태 집합**: 기획서 v13은 `NEW, SUBMITTING, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, CANCELED, REJECTED, EXPIRED, UNKNOWN`(`autotrade-spec.md:201`), 코드 `OrderState`는 `SUBMITTED, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED, UNKNOWN`(`OrderState.java:9-16`). `EXPIRED`·`CANCEL_REQUESTED`·`NEW`가 없고 `isTerminal()`이 만료를 모른다. 가상·실전이 같이 쓰는 계약이라 7단계 전에 하나로 맞춰야 한다. (2) **RSK-01 문구**: 기획서는 "주문 클라이언트 생성 자체가 OPEN 이후에만 가능"(`autotrade-spec.md:173`), 코드는 ACTIVATING(인증 통과 후)·PROTECTIVE에서도 허가·출구를 준다(`RealTradingGuard.java:133-137`). ADR-002 2.1 표가 이유를 설명하지만 기획서는 고치지 않았다(기획서 문장 자체도 "인증 → 주문 세션 → 첫 대사 → OPEN"과 모순). (3) 장애 대응표 종 수 불일치(A12) |

## 점검표 B (B1, B6)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 "직접 실행한 것": `./gradlew build` BUILD SUCCESSFUL, 289건 실패 0·건너뜀 0 |
| B6 | 통과 | PR #9의 새 동작마다 테스트가 있고(ADR-002 5장 표와 실제 테스트 이름 대조), 테스트를 지우거나 건너뛴 흔적 없음. PR #8은 문서라 해당 테스트 없음(요구사항 TST-03 목록만 늘림) |

## 과금 검사 (프로젝트 지침 8장, 조정 세션 전달)
- 기준: 이 세션에서는 QA 프로젝트 설정 원문(8장)을 직접 읽는 도구가 없어, 조정 세션이 전달한 항목(유료 API 호출 경로 식별, 필요성과 대체 수단, 사용자 옵션 제어 기본 꺼짐, 폭주 방지, 실주문 비용)과 사용자 메시지("유료 API 호출 경로가 꼭 필요하다고 하면 사용자가 옵션으로 제어해서 사용할 수 없도록 하는 것도 검토대상… 1회라도 필요하면 호출경로부터 필요한 이유/대체 방안")로 판정했다. 이 장은 확인 못 함도 실패로 친다.

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| P1 | 유료 API 호출 경로 식별 | 통과 | main 운영 코드에 외부로 나가는 HTTP·WebSocket 호출이 없다(`HttpClient`·`WebClient`·`RestTemplate`·`URLConnection`·`WebSocket`·외부 URL grep 결과: 실험실 루프백 Origin 검사 `LocalOnlyRequestFilter.java:105,121`뿐). 증권사 플러그인·알림 발송(`autotrade-notification-jar`는 `package-info.java`만) 구현 없음. Python 수집기는 README만 있고 CI가 실시간·주문·인증 코드를 막는다(`scripts/ci/check-python-collectors.sh`) |
| P2 | 필요성과 대체 수단 | 실패(확인 못 함) | 설계상 쓰기로 한 외부 API 중 **토스 미국 실시간 시세 요금이 미확인**이다: `docs/research/market-data-limits.md:9` "토스도 추가 비용이 없는 것으로 보인다(`[추론]`, 요금 조항 미확인)", `:106` "유료면 과금 멈춤 조건, 사용자 확인". 그런데 D-024는 토스를 국내·미국 시세 2순위로 확정했다. 유료일 때의 대체 수단(키움·KIS만으로 운용)은 문서에 적혀 있지 않다. 카카오 알림(MON-06)·Slack 요금도 문서에서 확인 기록을 찾지 못했다 |
| P3 | 사용자 옵션 제어(기본 꺼짐) | 실패(확인 못 함) | 유료 가능성이 있는 호출을 끄고 켜는 설정 키가 없다. `market-data.priority` 기본값에 TOSS가 들어 있다(`autotrade-default.json` `"priority": ["KIWOOM", "KIS", "TOSS"]`). 요금이 확인되지 않은 소스가 **기본으로 켜지는** 구조다. 알림은 `notification.*.enabled=false` 기본이라 기본 꺼짐 조건을 만족(`autotrade-default.json`) |
| P4 | 폭주 방지 | 실패(확인 못 함) | 호출 수 상한·토큰 버킷(A7), 주문 실패 3회 정지(ORD-08), 레이트리밋 장애 #14 처리 코드가 없다. 유료 과금이 호출 수 기반이면 막는 장치가 없다 |
| P5 | 실주문 비용 | 실패(확인 못 함) | 실주문 수수료가 `[가정]`이다(D-021 국내 0.015%·미국 0.25%, Q-011 미확정). TST-05는 "토스는 최소 수량 실전 주문으로(사용자 승인 후)" 연동 시험을 한다(`autotrade-spec.md:279`). 이 시험의 예상 비용(수수료·세금·스프레드 손실)과 횟수 상한이 문서에 없다. 실전 주문 경로는 Guard OFF 기본이라 지금 main에서 실주문이 나갈 길은 없다(A1) |

## 실전 안전장치 보강 13건(D-023) 구현 상태
PR #8은 요구사항을 기획서에 넣은 문서 PR이다. main 코드 기준 상태:

| 항목 | main 코드 상태 | 근거 |
|---|---|---|
| ORD-06 신호 경과 폐기 | 미구현 | 관련 코드·키(`order.max-signal-age`) 없음 |
| ORD-07 상태 집합·재시작 UNKNOWN | 미구현, 상태 집합이 기획서와 다름 | A14 (1). Guard는 `FIRST_RECONCILIATION` 통과 알림만 받고 UNKNOWN 확정 여부는 검사하지 않음(`RealTradingGuard.java:195-197`) |
| ORD-08 판정 기준·실패 3회 정지 | 미구현 | 코드 없음 |
| RSK-01 보강(보호 모드) | 부분 | 상태·판정·허가는 구현·시험됨. 미체결 매수 취소, 보유 0 감지, 재기동 후 긴급 알림은 미구현(ADR-002 6장). F-3 |
| RSK-04 서킷 3단계 | 미구현 | |
| RSK-08 #15·#16 | 미구현 | |
| RSK-11 세션 구간표 | 미구현 | |
| RSK-12 블랙리스트 전역 | 미구현 | A6, F-2 |
| RSK-13 PANIC SELL | 미구현 | `shutdown()`만 있음(`RealTradingGuard.java:266-273`). F-5 |
| CFG-05 교체 규칙 | 부분 | `real-trading.*` 실시간 변경 거부만(`RuntimeConfigHolder` update, `RuntimeConfigHolderTest` 통과). 교체 절차 없음 |
| CFG-06 설정 3분류 | 부분 | 전략 프로필 불변 버전 파일(D-026)은 있음. 실전 한도 분류·2차 확인 없음(한도 키 자체가 없음) |
| STR-05 보강 | 해당 없음(운영 절차) | 6단계 운영 규칙 |
| TST-06 체크리스트 매핑 | 미구현 | 7단계 전 산출물 |

## 점검표 밖 발견 (잘못된 주문·중복 주문·보호 공백 시나리오)

**F-1 [높음, 실측] 패키지 이름만 맞추면 사람 확인 없이 Guard를 OPEN으로 올리는 코드가 빌드를 통과한다.**
`ONLY_COMMAND_CHANNEL_RAISES_GUARD`(`ArchitectureTest.java:143-149`)는 허용 범위를 **패키지 이름**(`com.autotrade.app.engine.command..`, `com.autotrade.risk.guard..`)으로만 정한다. JPMS(`module-info.java`)가 없어 다른 모듈도 같은 이름의 패키지를 만들 수 있다.
- 실험: `autotrade-app-lab` 모듈에 (a) `com.autotrade.risk.guard.QaSplitPackageProbe`(`new SecondConfirmation(..., "nobody", clock.instant())` → `beginActivation` → `completeStep` 3회, 패키지 비공개 `new OrderSessionPermit(...)`까지), (b) `com.autotrade.app.engine.command.QaCommandPackageProbe`(같은 호출)를 넣고 `./gradlew :autotrade-architecture-test:test` → **12/12 통과(우회 성공)**. 대조군으로 같은 코드를 `com.autotrade.app.lab.qaprobe`에 두면 `Guard를_올리는_조작은_명령_채널에서만_한다` 실패(5건 검출). 실험 파일은 삭제했다.
- 영향: ADR-002 2.2의 "지금은 운영 코드 어디서도 실전을 열 수 없다", "엔진 코드가 사람의 확인 없이 실전을 여는 것을 빌드로 막는다"는 주장이 성립하지 않는다. 실험실·UI 같은 다른 프로세스 모듈 코드가 Guard 상향 코드를 가질 수 있다. 지금은 실험실이 Guard 인스턴스·플러그인을 얻을 길이 없어 실제 실전 주문으로 이어지지는 않는다 `[추론]`. 그러나 7단계 명령 채널이 생기면 이 규칙이 유일한 구조적 방어다.
- 고칠 점(제안): 허용 범위를 패키지 이름이 아니라 **클래스 출처(모듈 jar 위치)**로 제한(ArchUnit `resideInAnyPackage` 대신 `JavaClass.getSource()` 기반 술어, 또는 `com.autotrade.risk.guard`·`app.engine.command` 패키지를 정의한 모듈이 각각 risk·app-engine 하나뿐인지 검사하는 규칙 추가), 또는 JPMS 도입. 우회 픽스처(다른 모듈·같은 패키지 이름)를 `#규칙은_위반을_실제로_잡아낸다`에 추가. 같은 방식으로 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`의 FQN 예외(`doNotHaveFullyQualifiedName(RealOrderGateways)`)도 다른 모듈에 같은 FQN 클래스를 두는 경우를 막는지 확인 필요.

**F-2 [높음, 미구현 결합] 유일한 실전 출구에 블랙리스트·수동 보유 검사가 없고, 보호 모드에서는 모든 매도가 통과한다.**
`OrderRequest`는 `SELL`이면 의도를 `EXIT`/`STOP_LOSS`만 허용(`OrderRequest.java:33-36`)하므로 모든 매도는 `isProtective()=true`(`OrderIntent.java:19-21`). Guard는 PROTECTIVE·ACTIVATING(보호 모드 출발)에서 의도만 보고 통과시킨다(`RealTradingGuard.java:105-109`). 블랙리스트 필터는 "검증 체인 맨 앞"에 둘 예정이라고만 되어 있고(`RiskPolicy.java:51`) 출구에는 없다.
- 시나리오: 사용자가 같은 계좌에 수동으로 산 종목(AUTO_ASSET 대상)이 있다 → 보호 모드 또는 PANIC SELL(RSK-13) 구현이 "보유 전량 매도"를 만들 때 원장 대조를 빠뜨림 → 매도 주문이 Guard와 출구를 모두 통과해 **수동 보유 종목이 팔린다**(지침 4장 3번 위반). 재기동 복구·감시 프로세스 긴급 경로처럼 "큐를 거치지 않는 코드"가 생길 때 같은 위험(ADR-002 3장 B3이 스스로 든 이유).
- 고칠 점(제안): 7단계 전에 블랙리스트·수동 보유 검사를 `GuardedOrderGateway.submit`(유일한 출구)에도 두는 이중 방어, 그리고 RSK-12·13 실패 테스트(보호 모드·PANIC에서 수동 보유 매도 거절).

**F-3 [중간] OPEN → 보호 모드 전환 시 이미 접수된 매수 주문은 그대로 체결될 수 있다.**
`deactivate()`(`RealTradingGuard.java:240-252`)는 상태만 바꾼다. 미체결 매수 취소는 엔진 몫인데 구현이 없다(ADR-002 6장). 시나리오: 사용자가 장중에 실전을 끔 → 지정가 매수 미체결이 남아 있음 → 보호 모드에서도 체결되어 **끈 뒤에 새 보유가 생긴다**. RSK-01 인수 조건("미체결 매수 취소")에 대한 테스트가 없다. 7단계 구현 시 "보호 모드 진입 → 미체결 매수 취소 확인" 실패 테스트가 필요하다.

**F-4 [중간, ADR-002가 인지] 상향 전이 감사 기록이 멈추면 같은 계좌의 킬스위치(`shutdown`)가 잠금에서 무기한 대기한다.**
`raise()`가 계좌 잠금 안에서 `audit.record`를 부른다(`RealTradingGuard.java:303-307`), `shutdown()`도 같은 잠금을 잡는다(`:269`). 7단계 영속 감사 로그(DB)가 멈추면 킬스위치 호출 스레드가 멈춘다. 킬스위치를 여러 계좌에 차례로 부르는 구현이면 **다른 계좌의 정지까지 늦어진다**(A7·지침 4장 6번). 주문 판정은 잠금을 안 잡아 손절은 영향 없음(`#감사_기록이_느려도_주문_판정은_기다리지_않는다` 통과). ADR-002 4장의 "쓰기 시간 상한"이 7단계 완료 기준에 들어가야 하고, 킬스위치 경로 대기 상한 테스트가 필요하다.

**F-5 [중간, 설계 공백] 감시 프로세스 긴급 PANIC 경로(RSK-13, MON-02)는 지금 규칙상 실전 출구를 얻을 수 없다.**
실전 출구는 `com.autotrade.app.engine.order.RealOrderGateways`만 열 수 있고(`ArchitectureTest.java:130-134`), 프로세스 진입점끼리 참조 금지(`:152-154`), 감시 프로세스는 broker·risk에 의존하지 않는다(`autotrade-app-watchdog/build.gradle.kts`). 기획서 RSK-13은 "엔진이 죽어 있으면 감시 프로세스 긴급 경로로 매도·취소"를 요구한다. 구현하려면 규칙을 바꾸거나 두 번째 출구를 만들어야 하는데, 그 결정(ADR)이 없다. 두 번째 출구가 Guard·블랙리스트 없이 생기면 F-2와 같은 위험이 생긴다. 구현 전에 ADR과 risk-ops 검토가 필요하다.

**F-6 [낮음, ADR-002 인지] Guard OFF 뒤에는 조회도 막혀 킬스위치 직전 UNKNOWN 주문을 확정할 수 없다.**
`GuardedOrderGateway.query`가 허가 무효면 `Indeterminate`(`GuardedOrderGateway.java:74-76`). 다음 활성화의 첫 대사까지 미확정. 재발주 금지 규칙(Indeterminate면 재발주 금지)과 일관이라 중복 주문은 안 생긴다 `[추론]`. 다만 보호 모드 없이 바로 OFF한 계좌에 보유가 남으면 상태를 모르는 채 방치된다. 재기동 긴급 알림(RSK-01) 구현 때 "OFF 계좌의 미확정 주문"도 알림 대상에 넣을 것을 권한다.

## 판정
**수정 필요** (A14 실패, 과금 검사 P2·P3·P4·P5 실패, A4·A5·A6·A7·A8·A9·A12 확인 못 함, 점검표 밖 F-1)

- 지금 main에서 실전 주문이 나갈 수 있는 경로는 없다(A1 통과, 플러그인 구현 없음). 이 판정은 "현재 main이 실전 주문을 잘못 낸다"는 뜻이 아니라, 실전 경로 전체로서는 증명된 것보다 증명되지 않은 것이 많고, 구현된 골격에도 고칠 곳이 있다는 뜻이다.
- 지금 바로 고칠 것: F-1(ArchUnit 패키지 이름 위장 우회와 그 픽스처), A14(ORD-07 상태 집합과 `OrderState` 통일, RSK-01 기획서 문구 정정, 장애 대응표 종 수를 17종으로 문서 정리), P2·P3(토스 미국 실시간 요금 공식 확인과, 확인 전에는 유료 가능 소스를 기본 꺼짐으로 두는 설정).
- 7단계 전 완료 기준에 넣을 것: F-2(출구 블랙리스트 이중 방어), F-3, F-4, F-5, P4·P5, 그리고 확인 못 함 항목들의 실패 테스트(TST-01 8종, TST-03 목록).
