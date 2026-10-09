# architect 검토: QA 관문(2026-10-09) 후속: 패키지 소유 모듈 규칙(F-1), OrderState를 ORD-07에 맞춤, 토스 시세 기본 꺼짐(D-033), 기획서 v14 (#21)
- 대상: PR #21 / 브랜치 `claude/project-thread-bl5qbg` HEAD 29323c1 / `git diff origin/main...HEAD` 전체(merge-base = origin/main 52ad85f, 커밋 5개(머지 1 포함), 25개 파일, +501 −38). `git merge-tree --write-tree origin/main HEAD` 충돌 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon -I <저장소 밖 미러 init 스크립트> --console=plain` (작업 트리 = HEAD 29323c1, 깨끗함): BUILD SUCCESSFUL, EXIT=0. `verifyPureDependencies`(순수 모듈 9개), `verifyProcessIsolation`(engine·gateway·watchdog), ArchUnit 포함.
  - 테스트 XML 집계(전 모듈): 315건, 건너뜀·실패·오류 0. 이 PR 관련: `ArchitectureTest` 12/12, `ModuleOwnershipTest` 5/5, `OrderStateTest` 4/4, `ConfigLoaderTest` 21/21, `GuardedOrderGatewayTest` 8/8, `RealTradingGuardTest` 31/31, `RealOrderGatewaysTest` 4/4, `VirtualExchangeTest` 18/18, sim 전체 75/75(골든 `SimulationExperimentRunnerTest` 포함).
  - 변이 검사(검토 후 원복, `git status` 깨끗함 확인): 실험실 운영 소스에 `autotrade-app-lab/src/main/java/com/autotrade/risk/guard/ArchProbe.java`와 같은 FQN `com.autotrade.app.engine.order.RealOrderGateways`를 임시로 넣고 `:autotrade-architecture-test:test` 실행 → EXIT=1, `ModuleOwnershipTest.운영_클래스는_패키지를_소유한_모듈에만_있다` 실패, 위반 2건(두 클래스 모두 "(autotrade-app-lab): 패키지 …은 autotrade-risk/autotrade-app-engine 소유")을 정확히 보고. 같은 FQN을 다른 모듈에 둔 경우도 위치별 가져오기로 잡힘을 확인.
  - 보호 테스트 `@Test` 수 main 대비: `GuardedOrderGatewayTest` 8→8, `RealOrderGatewaysTest` 4→4, `ArchitectureTest` 12→12(파일 무변경), `VirtualExchangeTest` 18→18, `ConfigLoaderTest` 20→21. `@Disabled` 0.
  - 남은 옛 이름 grep(`SUBMITTED|CANCELLED`, java·sql·ts·tsx·json·md): `OrderState` 관련은 문서의 이력 설명뿐. `CANCELLED`는 `RunStatus`(실험 Run 상태)와 V1 마이그레이션의 Run 상태 CHECK에만 있고 `OrderState`와 무관. 주문 상태를 저장하는 스키마·UI 코드 없음.
  - diff 전체에서 키·토큰·계좌번호 패턴 grep: 걸린 것 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(EXIT=0), 315건 통과 |
| B2 | 통과 | 운영 코드 의존 변경 없음(build.gradle.kts·settings 무변경). `OrderState`는 `autotrade-domain`(순수 Java, `java.*`만), `VirtualExchange` 변경은 enum 값 교체뿐. ArchUnit 규칙은 **추가만** 했다: `ModuleOwnershipTest`(autotrade-architecture-test/.../ModuleOwnershipTest.java:68-111)는 기존 `ArchitectureTest`(무변경)의 패키지 이름 기반 허용 범위가 모듈과 같다는 전제를 강제한다. 규칙 추가의 ADR은 ADR-002 9장(대안 JPMS·출처 기반 술어 비교, 되돌릴 조건 포함)과 D-034. 픽스처 4개는 architecture-test 테스트 소스라 `DO_NOT_INCLUDE_TESTS`로 `ArchitectureTest.PRODUCTION`(ArchitectureTest.java:59-61)과 소유 검사 운영 위치 목록 양쪽에서 빠진다(빌드 통과로 확인) |
| B3 | 통과 | 계약 변경 2건. (1) `OrderState` 집합·`isTerminal()` 변경(OrderState.java:22-36): 기획서 ORD-07(D-023, 사용자 결정)에 코드를 맞춘 것이고 D-034에 원인·대안(C안 "기획서를 코드에 맞춤" 버림)·남은 위험(열린 PR 이름 충돌) 기록, ADR-004 41행에 가상 거래소 매핑(DAY_END→EXPIRED, 현금 부족·cancel→CANCELED) 갱신. ADR-001 44행은 타입 이름만 적어 갱신 불필요. (2) 설정 키 `market-data.priority` 기본값 변경(autotrade-default.json:3): D-033(대안 3개), config-keys.md 14행, 기획서 MD-02 갱신. 스키마·REST 변경 없음. ADR-001 4장 문구 미갱신은 아래 N2(권고) |
| B4 | 통과 | 새 코드에 시계·난수 없음. `ModuleOwnershipTest`의 위치 순회는 URI 문자열로 정렬(ModuleOwnershipTest.java:182-187), 위반 목록도 정렬. 가상 거래소 상태 이름만 바뀌었고 골든 테스트(`SimulationExperimentRunnerTest`, `krx-golden.events`)와 기대 스냅샷은 무변경으로 통과. `EXPIRED`도 종결이라 `isTerminal()`을 쓰는 두 곳(VirtualExchange.java:182, :513)의 동작은 DAY_END 이전 `CANCELLED`와 같다 |
| B5 | 통과 | 금액 계산 변경 없음. `VirtualExchange` 변경은 상태 enum 값만(:183, :191, :442, :489) |
| B6 | 통과 | 새 동작마다 테스트: ORD-07 집합·순서(`OrderStateTest.상태_집합은_기획서_ORD_07과_같다`), 종결 판정 전수(`체결_취소_거절_만료만_종결이다`), UNKNOWN·CANCEL_REQUESTED 비종결(`미확정과_취소_요청_중은_종결이_아니다`), 장 마감 EXPIRED(VirtualExchangeTest.java:241-242), 기본 priority에 TOSS 없음(ConfigLoaderTest.java:42-43), 사용자가 넣으면 그 순서(`사용자가_토스를_넣으면_적은_순서대로_쓴다`), 소유 규칙 위반 픽스처 4건·대조군·경계(`규칙은_패키지_이름_위장을_실제로_잡아낸다`, `패키지_소유는_가장_긴_접두어가_이기고_경계를_지킨다`, `위치_경로에서_모듈_이름을_알아낸다`), 표 최소 조건(`실전_경로_패키지는_정해진_모듈만_소유한다`). 내 변이 검사에서 실제 운영 소스 위장 2건을 잡았다. 보호 테스트(`GuardedOrderGatewayTest`, `RealOrderGatewaysTest`) 변경은 스텁 반환값과 그 통과 확인 값의 이름 교체뿐(SUBMITTED→ACCEPTED, CANCELLED→CANCEL_REQUESTED)이고 단언 수·차단 단언(REJECTED, `submitted` 크기, intent 목록)은 그대로다. 삭제·`@Disabled` 없음 |
| B7 | risk-ops 검토 필요 | 실전 경로를 건드린다: 보호 테스트 2개 수정(GuardedOrderGatewayTest.java:54, 98, 156, 169; RealOrderGatewaysTest.java:76, 150, 160), `GuardedOrderGateway`가 반환하는 `OrderState` 계약 변경, 실전 경로 보호용 ArchUnit 규칙 추가, 시세 소스 기본값 변경, 기획서 RSK-01·RSK-08·TST-05·TST-07 변경. 지침 11장상 실전 관련 PR이므로 risk-ops A 점검표 검토, 사용자 머지, 머지 전 독립 QA가 필요하다. 이 검토 시점에 `docs/reviews/pr-21/risk-ops.md`는 없다 |
| B8 | 통과 | diff grep에서 비밀값 없음. 픽스처의 계좌 ID `KIS-1`, 사용자 `nobody`는 가상 값. 문서의 secrets 예시는 기존 자리표시자(`<ACC…>`) 그대로 |

## 점검표 밖 발견
- **N1 (권고) 소유 검사도 architecture-test 클래스패스 밖 모듈은 못 본다.** `운영_클래스는_패키지를_소유한_모듈에만_있다`의 마지막 검사(ModuleOwnershipTest.java:109-111)는 "OWNERS의 모듈이 모두 클래스패스에 보인다"만 확인한다. 새 모듈을 `settings.gradle.kts`에 넣고 architecture-test 의존(autotrade-architecture-test/build.gradle.kts)과 OWNERS 둘 다에 빠뜨리면 그 모듈의 split package는 이 테스트와 `ArchitectureTest` 모두에 안 보인다. ADR-001 4장 위험표(221행)에 이미 있는 기존 한계라 막지 않는다. 다음 손볼 때 `settings.gradle.kts`의 include 목록(빌드 전용 `autotrade-architecture-test`, `autotrade-ui` 제외)과 OWNERS 값 집합을 비교하는 단언을 더하면 "표에 없으면 실패"가 실제로 닫힌다. 열린 PR #18의 `autotrade-control` 모듈이 첫 적용 대상이다.
- **N2 (권고) ADR-001 4장 미갱신.** `ModuleOwnershipTest` Javadoc(ModuleOwnershipTest.java:44)과 CLAUDE.md가 "ADR-001 4장"을 근거로 들지만 ADR-001의 91행·221행은 여전히 "settings와 architecture-test 의존에 함께 추가"만 말하고 OWNERS 표를 언급하지 않는다. ADR-002 9장에 결정이 있으므로 계약 누락은 아니지만, 새 모듈 절차의 단일 원본이 ADR-001이므로 한 줄 링크를 더한다.
- **N3 (참고) MD-02 인수 조건 "기본 설정으로 기동 시 요금 미확인 소스에 연결 0건"은 지금 설정 수준까지만 검증된다.** `market-data.priority`를 읽는 운영 코드는 실험실 설정 화면(SettingsController.java:276)뿐이고 게이트웨이 소스 선택은 아직 없다. 2단계 게이트웨이가 priority를 소비할 때 "기본 설정 → TOSS 연결 시도 0건" 테스트를 넣어야 한다. 또 `market-data.toss-polling-scope` 기본 `HELD_ONLY`(실전 엔진 보호 폴링)는 D-033 범위 밖으로 남았고 요금 확인은 Q-020에 묶였다. 실전 계좌가 토스일 때만 쓰는 경로라 지금 과금 위험은 없다고 본다 `[추론]`.
- **N4 (참고) `OrderRejectedReason` 이름.** 이제 `DAY_END`는 `EXPIRED`, `CASH_SHORT_AT_FILL`은 `CANCELED`에 붙는 사유인데 enum 이름은 "Rejected"다(OrderRejectedReason.java). 동작 문제는 없고 Javadoc이 상태를 적어 두었다. 7단계 상태머신·전이표 작업에서 `OrderCloseReason` 같은 이름으로 정리할지 ADR에서 정한다.
- **N5 (참고) 실전 스텁의 cancel 응답이 `CANCEL_REQUESTED`로 바뀐 것은 맞는 방향이다.** 실제 증권사 취소는 비동기 확인이므로 출구가 즉시 `CANCELED`를 돌려주는 스텁보다 정확하다. 가상 거래소만 즉시 확정(VirtualExchange.java:172-175 Javadoc)이라 두 경로의 cancel 반환 상태가 다르다. 공유 상태머신(SIM-12, ORD-07)이 들어올 때 "cancel 응답은 CANCEL_REQUESTED 또는 종결 상태"를 공통 계약으로 적어 둔다.
- **N6 (참고) 열린 PR 이름 충돌.** D-034가 적은 대로 `SUBMITTED`/`CANCELLED`를 쓰는 열린 PR(#18, #19)이 나중에 머지되면 컴파일 오류로 드러나므로 조용한 오동작 위험은 없다.

## 판정
승인. 점검표 B 전 항목이 통과이고(B7은 "risk-ops 검토 필요"), 확인 못 함은 없다. N1·N2는 막지 않는 권고다. 이 판정은 architect 관점(B 점검표)에 한한다. 이 PR은 실전 관련 PR(보호 테스트 수정, 실전 출구 계약 변경)이므로 머지에는 risk-ops 검토 원문, 사용자 직접 확인·머지, 머지 전 독립 QA가 따로 필요하다(지침 10·11장).

## 재검토 2026-10-09
- 대상: PR #21 / 브랜치 `claude/project-thread-bl5qbg` HEAD 7dd0d78 / `git diff origin/main...HEAD` 전체(merge-base = origin/main 52ad85f). 첫 검토(29323c1) 뒤 커밋 3개: 20f514a(risk-ops 원문), f2ffbab(R-2·R-3 문서), 7dd0d78(R-1 클래스패스 자동 구성). 그 사이 바뀐 파일 9개(+211 −15): `autotrade-architecture-test/build.gradle.kts`, `ModuleOwnershipTest.java`, CLAUDE.md, ADR-001, ADR-002 9장, D-033, D-034, broker-and-fault-design.md, risk-ops.md. 첫 검토 범위의 나머지 파일은 그대로다.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon -I <저장소 밖 미러 init 스크립트> --console=plain` (HEAD 7dd0d78, 작업 트리 깨끗함): BUILD SUCCESSFUL, EXIT=0, 86 tasks. 테스트 XML 집계 318건(첫 검토 315 + `ModuleOwnershipTest` 3건), 건너뜀·실패·오류 0. `ArchitectureTest` 12/12, `ModuleOwnershipTest` 8/8.
  - `./gradlew :autotrade-architecture-test:test --rerun --warning-mode all`: EXIT=0. 경고는 내 미러 init 스크립트의 Groovy 문법 경고뿐이고 새 build.gradle.kts에서 나온 경고 없음.
  - 변이 검사(별도 git worktree, 검토 후 `git worktree remove`로 제거, 본 작업 트리 깨끗함 확인):
    1. settings에만 `autotrade-rogue`를 include(`autotrade-risk` 의존, `com.autotrade.risk.guard.RogueRaiser`가 `beginActivation` 호출, 의존 목록·OWNERS 손대지 않음) → `./gradlew build` EXIT=1. `운영_클래스는_패키지를_소유한_모듈에만_있다`(RogueRaiser 패키지 소유 위반)와 `클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다`("소유 모듈 표(OWNERS)에 없는 모듈: autotrade-rogue") 2건 실패. risk-ops R-1 실측(BUILD SUCCESSFUL)이 닫혔다.
    2. 같은 상태에서 `build.gradle.kts`를 첫 검토 시점 판(손 목록, 시스템 속성 없음)으로 되돌림 → EXIT=1, 시스템 속성 없음으로 닫힌 쪽 실패.
    3. 부분 회귀: 시스템 속성 전달은 남기고 의존만 손 목록으로 되돌린 뒤 OWNERS에 `com.autotrade.rogue → autotrade-rogue`를 더함(표만 고친 경우) → EXIT=1, `클래스패스는_settings의…`가 클래스패스 누락으로 실패.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(EXIT=0), 318건 통과 |
| B2 | 통과 | 운영 코드 의존 방향 변경 없음. architecture-test의 testImplementation을 `rootProject.subprojects`(자기 제외) 중 java 플러그인이 적용된 모듈로 자동 구성(build.gradle.kts:15, :24). 이 모듈은 테스트 전용이고 다른 모듈이 의존하지 않으므로 순환 없음. `autotrade-ui`(정적 자원만, java-conventions 적용)가 새로 클래스패스에 들어오지만 `MODULES_WITHOUT_PACKAGES`(ModuleOwnershipTest.java:102)로 명시하고, 그 모듈에 `com.autotrade` 클래스가 생기면 소유 위반으로 실패한다. 규칙 강화의 ADR은 ADR-002 9장 보강(대안 가·나·다 비교, 되돌릴 조건 JPMS)과 ADR-001 91·221행 갱신 |
| B3 | 통과 | 새 계약은 빌드→테스트 시스템 속성 `autotrade.settingsJavaModules`(빌드 내부, 운영 설정 키 아님)뿐이고 ADR-002 9장·build.gradle.kts 주석·테스트 Javadoc에 적혀 있다. 첫 검토의 N2(ADR-001 미갱신)는 ADR-001 91·221행 갱신으로 해소. 설정 예시(broker-and-fault-design.md:68)도 기본값 `[KIWOOM, KIS]`와 일치 |
| B4 | 통과 | 새 코드에 시계·난수 없음. 모듈 목록은 Gradle 쪽에서 `sorted()`, 테스트 쪽은 `TreeSet`, `coverageGaps` 결과도 정해진 순서(ModuleOwnershipTest.java:286 이하). 시뮬레이션 코드 변경 없음, 골든 테스트 통과 |
| B5 | 해당 없음 | 이번 변경분에 금액 계산 없음(첫 검토 B5 통과 유지) |
| B6 | 통과 | 새 동작 테스트: `아무도_의존하지_않는_새_모듈이_빠지면_잡아낸다`(클래스패스·표 누락, 표만 고친 경우, 표에만 남은 모듈, 대조군), `클래스패스_항목에서_모듈_이름을_알아낸다`(자원 폴더·jar·Windows 경로·외부 jar), 실제 클래스패스 교차 확인 `클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다`(실전 경로 모듈 존재로 빈 손 통과 방지). 내 변이 3건 모두 실패로 잡힘. 기존 테스트 삭제·`@Disabled` 없음, `ArchitectureTest` 무변경 |
| B7 | risk-ops 검토 필요 | 실전 경로 보호 테스트(`ModuleOwnershipTest`, 지침 11장 보호 테스트 범주인 아키텍처 규칙)와 그 범위를 정하는 빌드 스크립트를 바꿨다. risk-ops 첫 판정이 "수정 필요(A14, R-1)"(docs/reviews/pr-21/risk-ops.md 39행)이므로 같은 검토자의 재검토 원문이 필요하다. 이 재검토 시점에 risk-ops 재검토 절은 없다 |
| B8 | 통과 | 변경분 grep에서 비밀값 없음. 픽스처 모듈 이름(`autotrade-rogue`)과 가상 경로뿐 |

### 점검표 밖 발견(재검토)
- 첫 검토 N1(클래스패스 밖 새 모듈)과 N2(ADR-001 미갱신)는 해소됐다. 위 변이 1·3이 그 근거다.
- **RR1 (참고) 설정 캐시 비호환.** build.gradle.kts의 `provider { settingsJavaModules.filter { it.pluginManager.hasPlugin("java") } }`는 실행 시점에 `Project` 객체를 읽는다. 지금은 configuration cache를 켜지 않아(`gradle.properties` 없음, CI `./gradlew build --no-daemon`) 문제없다. 나중에 켜면 이 부분이 먼저 깨지므로, 그때 구성 단계에서 이름 목록을 문자열로 확정해 넘기는 방식으로 바꾼다.
- **RR2 (참고) 남은 한계는 문서에 적힌 그대로다.** settings에 include하지 않은 폴더, Gradle 밖에서 만든 jar, 런타임 배포 클래스패스는 대상이 아니다(D-034 남은 위험, 테스트 Javadoc). java 플러그인을 적용하지 않은 모듈은 클래스를 만들 수 없으므로 대상 밖이어도 위장 경로가 되지 않는다 `[추론]`.
- 첫 검토 N3~N6은 상태 변화 없음(N3은 risk-ops R-4와 같은 2단계 과제, R-3 예외는 D-033에 문구로 반영됨).

### 판정(재검토)
승인. 점검표 B 전 항목이 통과 또는 해당 없음이고(B7은 "risk-ops 검토 필요"), 확인 못 함은 없다. 이 판정은 architect 관점(B 점검표)에 한한다. risk-ops 첫 판정이 "수정 필요"였으므로 머지 전에 risk-ops 재검토 승인, 사용자 직접 확인·머지, 독립 QA가 따로 필요하다(지침 10·11장).
