# risk-ops 검토: QA 관문(2026-10-09) 후속: 패키지 위장 차단, OrderState 통일, 토스 기본 꺼짐, 기획서 v14
- 대상: PR #21 / 커밋 `4224efb`(브랜치 `claude/project-thread-bl5qbg`) / main(`52ad85f`, 머지 기준점과 같음) 대비 diff 전체(25개 파일). 빌드는 `29323c1`에서 했고, `29323c1..4224efb`는 `docs/reviews/pr-21/architect.md` 추가 1건뿐이라(`git diff --stat`) 코드는 같다. architect 원문은 독립성을 위해 읽지 않았다.
- 점검표: A
- 검토자: risk-ops 서브에이전트, 2026-10-09. 메인 설명·PR 본문·커밋 메시지는 근거로 쓰지 않았다.
- 직접 실행한 것:
  - 분리된 작업 트리에서 `./gradlew build --no-daemon -I <저장소 밖 init 스크립트: maven-central.storage-download.googleapis.com 미러>` → **BUILD SUCCESSFUL**(1분 8초). 테스트 결과 XML 집계: architecture-test 17, risk 41, app-engine 7, config 30, domain 31, sim 75, app-lab 86, strategy 25, gateway 1, watchdog 1, broker-api 1. 실패 0, 오류 0, 건너뜀 0. 보호 테스트: `RealTradingGuardTest` 31/31, `GuardedOrderGatewayTest` 8/8, `RealOrderGatewaysTest` 4/4, `EngineMainTest` 3/3, `ArchitectureTest` 12/12(QA 기준선과 같은 수), 새 `ModuleOwnershipTest` 5/5, `ConfigLoaderTest` 21/21, `OrderStateTest` 4/4.
  - 변조 실험 1(커밋 안 함, 실험 뒤 삭제, `git status` 깨끗함 확인): `autotrade-app-lab` 운영 소스에 `com.autotrade.risk.guard.RoProbe`(`beginActivation`+`completeStep`), `com.autotrade.app.engine.command.RoCmdProbe`(`beginActivation`), 같은 FQN `com.autotrade.app.engine.order.RealOrderGateways`를 넣고 `:autotrade-architecture-test:test` → `ArchitectureTest` 12/12 통과(기존 규칙만으로는 못 막음 재현), `ModuleOwnershipTest#운영_클래스는_패키지를_소유한_모듈에만_있다` **실패, 3건 정확히 검출**. QA F-1의 원래 시나리오는 이 PR로 막힌다.
  - 변조 실험 2(같은 방식으로 원복 확인): 새 Gradle 모듈 `autotrade-rogue`(settings에 include, `autotrade-risk` 의존, `autotrade-architecture-test` 의존 목록에는 **추가하지 않음**)에 `com.autotrade.risk.guard.RogueRaiser`(`beginActivation`)를 넣고 `./gradlew build` 전체 → **BUILD SUCCESSFUL**. 아래 R-1.
  - 이름 바뀐 상태값의 잔존 참조 grep(`SUBMITTED`, `CANCELLED`, `OrderState.`): 운영 코드·SQL·JSON·UI에 주문 상태로 남은 곳 없음. `CANCELLED`는 실행 상태 `RunStatus`(`autotrade-sim/.../RunStatus.java:9`, `V1__lab_baseline.sql:31,41`, `format.js:52`)로 별개.
  - 비밀값: diff에서 앱 키·시크릿·토큰·계좌번호 패턴 grep 0건. 픽스처 계좌는 별칭 `KIS-1`뿐.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 운영 코드 변경 없음(`git diff --stat origin/main...HEAD -- '*/src/main/*'`는 `autotrade-default.json`, `OrderState.java`, `OrderRejectedReason.java`, `VirtualExchange.java` 4개뿐). 초기 상태 `RealTradingGuard.java:332,337` OFF. 기본 설정에 활성화 키 없음(`autotrade-default.json` `real-trading.accounts: []`). `RealTradingGuardTest`(재기동 OFF 포함) 31/31, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과. 이 PR은 A1 쪽을 강화한다(F-1 위장 코드로 Guard를 올리는 경로를 `ModuleOwnershipTest`가 막음, 변조 실험 1) |
| A2 | 통과(조건 있음) | `ArchitectureTest.java` 변경 없음(diff에 없음), `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`(`:130`)·`ONLY_COMMAND_CHANNEL_RAISES_GUARD`(`:143`) 그대로 12/12. 새 `ModuleOwnershipTest`가 같은 FQN `RealOrderGateways`를 다른 모듈에 두는 우회를 잡는다(변조 실험 1, `#규칙은_패키지_이름_위장을_실제로_잡아낸다`). 조건: `autotrade-architecture-test` 클래스패스 밖 모듈에는 두 테스트 모두 닿지 않는다(R-1, 이 PR 이전부터의 구멍이지만 이 PR 문서가 막혔다고 적음) |
| A3 | 통과 | 라우팅 코드 변경 없음. `RealOrderGateways.java:31-33` 플러그인 증권사 ≠ 계좌 증권사 거부 그대로. `RealOrderGatewaysTest` 4/4(바뀐 것은 가짜 증권사 응답 상태 이름 `SUBMITTED→ACCEPTED`, `CANCELLED→CANCEL_REQUESTED`뿐, 단언 대상 동작은 같음) |
| A4 | 통과(이 PR 범위) | 이 PR은 A4가 기대는 상태 계약을 바꾼다. 확인 결과 `UNKNOWN`은 여전히 비종결(`OrderState.java:34-36`, `OrderStateTest#미확정과_취소_요청_중은_종결이_아니다`), 새 `CANCEL_REQUESTED`도 비종결이라 "취소 요청했으니 끝" 판단으로 조회를 건너뛸 여지가 없다. `GuardedOrderGateway.java:85` Guard OFF 취소는 `UNKNOWN` 반환 그대로. 재발주 로직 자체는 main에도 없음(QA 관문 A4 확인 못 함, 이 PR이 TST-07 (7)로 7단계 완료 조건에 묶음) |
| A5 | 해당 없음 | `IdempotencyKey`·발급기 변경 없음. 미구현 상태는 TST-07 (7)로 추적 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음. 출구 이중 방어(F-2)는 D-034 대안 B로 미룸, TST-07 (1)에 실패 테스트 요구로 남음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 코드 변경 없음. Guard 계좌별 독립 테스트 그대로 통과 |
| A8 | 해당 없음 | 프로세스 런타임 의존·`processIsolation` 변경 없음. 바뀐 설정 키 `market-data.priority`는 시세 게이트웨이용(`config-keys.md`)이고 실전 엔진 코드가 읽지 않음(grep: 운영 코드 사용처는 `SettingsController.java:276` 표시뿐) |
| A9 | 통과 | 토스를 `market-data.priority` 기본값에서 뺐지만 보유 종목 보호 폴링은 별도 키 `market-data.toss-polling-scope` 기본 `HELD_ONLY` 유지(`autotrade-default.json:5`), 설계도 순위와 별개로 유지(`broker-and-fault-design.md:91`), 장애표 #2 대응은 "REST 폴링(가능한 증권사 아무거나)"(`:160`)으로 순위 목록과 무관. 문서가 이 분리를 명시(`config-keys.md` priority 행). 서버 측 손절(RSK-06) 변경 없음. 남은 위험은 R-3 |
| A10 | 통과 | diff grep 0건. 픽스처는 별칭 `KIS-1`, `"nobody"`만 |
| A11 | 통과 | 보호 테스트 삭제·비활성화 없음(`@Disabled`·`assume` 0건), 테스트 수 QA 기준선과 같음(31·8·4·3·12). 보호 테스트 안의 변경은 가짜 증권사 응답 상태 이름 교체 3곳(`GuardedOrderGatewayTest`)·3곳(`RealOrderGatewaysTest`)으로, 통과·차단 단언은 그대로다. 새 동작마다 실패 경우 테스트: 위장 픽스처 4건 검출, 실험실 모듈 가정 4건·같은 FQN 2건 검출, 진짜 소유 모듈 대조군(`ModuleOwnershipTest#규칙은_패키지_이름_위장을_실제로_잡아낸다`), 접두어 경계(`#패키지_소유는_가장_긴_접두어가_이기고_경계를_지킨다`), `OrderStateTest` 4건, `ConfigLoaderTest` 토스 미포함·사용자 지정 순서. 단, R-1을 잡는 테스트는 없다 |
| A12 | 통과 | 장애 대응표(`broker-and-fault-design.md` 6장)와 장애 테스트 8종 문서·전제 변경 없음. 기본 시세 소스가 3곳에서 2곳으로 줄어도 #1(1순위 끊김→다음 순위)·#2(전부 끊김→보호 폴링) 처리 규칙은 그대로 성립. 종 수 표기는 17종으로 정리됨(`checklists.md` A12, `role-guides.md:135`, 기획서 RSK-08). 용량 감소 영향은 D-033 "영향과 남은 위험"에 기록 |
| A13 | 해당 없음 | 시장별 분기·시각·금액 계산 변경 없음. `VirtualExchange.endOfDay`는 호출 시점·대상이 같고 최종 상태 이름만 `EXPIRED`로 바뀜 |
| A14 | 실패 | 맞춰진 것: ORD-07 상태 집합과 `OrderState` 순서까지 일치(`autotrade-spec.md:202`, `OrderStateTest#상태_집합은_기획서_ORD_07과_같다`), RSK-01 문구가 ADR-002 2.1과 일치, D-033·D-034·Q-020·Q-021 기록, MD-02·config-keys 일치. 어긋난 것: (1) D-034 "남은 위험"의 "표에 없는 패키지 루트는 테스트가 실패하므로 빠뜨릴 수는 없다"(`D-034-qa-gate-followup.md:32`)와 `ModuleOwnershipTest.java:44` "새 모듈을 만들면 이 표를 같이 고쳐야 빌드가 통과한다"는 **사실이 아니다**(변조 실험 2, R-1). (2) `broker-and-fault-design.md:68` 설정 예시는 여전히 `priority: [KIWOOM, TOSS, KIS]`로 새 기본값과 다르고 D-033을 언급하지 않는다(R-2) |

## 점검표 밖 발견
- **R-1 [중간, 실측] 아키텍처 테스트 클래스패스 밖의 새 모듈은 패키지 위장을 해도 빌드를 통과한다.** `ModuleOwnershipTest`는 `autotrade-architecture-test`의 테스트 클래스패스에 올라온 위치만 본다(`productionLocations()`, `Locations.ofPackage`). 그 의존 목록은 손으로 적은 목록이다(`autotrade-architecture-test/build.gradle.kts`의 `listOf(...)`). 다른 모듈의 의존으로 딸려 오는 모듈은 잡히지만(그 경우 표에 없는 모듈 이름 → 위반, `seenModules` 불일치 → 실패, 닫힌 쪽으로 실패함을 코드로 확인), **어떤 기존 모듈도 의존하지 않는 새 모듈**(예: 새 프로세스·명령 채널 모듈, 열린 PR #18의 `autotrade-control`이 이 경우가 될 수 있음)은 `ArchitectureTest`와 `ModuleOwnershipTest` 둘 다에서 보이지 않는다. 변조 실험 2에서 `com.autotrade.risk.guard` 패키지로 Guard를 올리는 코드를 가진 새 모듈이 `./gradlew build` 전체를 통과했다. 이 구멍은 이 PR 이전부터 있었지만, 이 PR은 F-1을 닫았다고 하면서 "빠뜨릴 수 없다"고 문서화해(A14 (1)) 다음 검토자가 이 경로를 확인하지 않게 만든다.
  - 고칠 점(이번 PR에서): `autotrade-architecture-test`의 의존 목록을 손 목록 대신 `rootProject.subprojects`(자기 자신 제외)에서 만들거나, 빌드 단계에서 `settings`의 모든 Java 모듈이 아키텍처 테스트 클래스패스에 있는지 검사해 빠지면 실패하게 한다. 그러면 새 모듈은 자동으로 클래스패스에 오고, 표에 없으면 기존 `seenModules`·"표에 없는 패키지 루트" 검사로 실패한다. 이 실패를 재현하는 테스트(또는 빌드 검사의 실패 확인)를 함께 둔다. 문서(D-034:32, `ModuleOwnershipTest.java:44`, ADR-002 9장)는 실제로 그렇게 된 뒤의 동작에 맞춘다. 문구만 고쳐 구멍을 인정하는 방식은 F-1 후속으로 부족하다고 본다(7단계 명령 채널이 생기면 이 규칙이 유일한 구조적 방어라는 QA 판단에 동의).
- **R-2 [낮음] 장애 설계 문서의 설정 예시가 새 기본값과 다르다.** `broker-and-fault-design.md:68` `priority: [KIWOOM, TOSS, KIS]`. 사용자가 이 예시를 복사하면 요금 미확인 소스가 켜진다. "D-024 순서, 토스는 D-033에 따라 사용자가 넣을 때만" 주석이나 기본값 예시로 고칠 것. 같은 맥락으로 `.claude/agents/risk-ops.md:18`의 "장애 대응표 14종"도 17종 기준과 다르다(에이전트 설정 파일이라 이 검토에서 고치라고 하지 않고 사용자에게 알린다).
- **R-3 [정보, 실전 영향] 토스 보호 폴링의 기본 켜짐은 유지해야 한다.** D-033 결정 1은 "요금이 공식 확인되지 않은 외부 소스는 기본 꺼짐"인데, 같은 문서가 보호 폴링 요금도 미확인이라고 적는다(D-033 "영향과 남은 위험"). 지금은 실전 계좌 기본값이 비어 있어(`accounts: []`) 기본 설정으로는 호출이 없고, 사용자가 토스 실전 계좌를 켤 때만 그 계좌 자격으로 쓰이므로 risk-ops는 `HELD_ONLY` 기본 유지를 지지한다. 나중에 "일관성" 이유로 이 키를 기본 OFF로 바꾸면 장애표 #2·#15의 보유 종목 보호가 약해진다(A9). D-033에 "보유 종목 보호 폴링은 결정 1의 예외이며, 끄거나 유료 판정으로 막는 변경은 risk-ops 검토와 사용자 결정"을 한 줄 명시할 것을 권한다(이번 PR 필수 아님).
- **R-4 [정보] MD-02 인수 조건 "기본 설정으로 기동 시 요금 미확인 소스에 연결 0건 테스트"는 지금 설정 수준(`ConfigLoaderTest` 기본 목록에 TOSS 없음)으로만 확인된다.** 시세 게이트웨이 소스 선택이 구현되는 2단계 PR에서 실제 연결 0건 테스트가 있어야 한다. 그때 "보호 폴링은 priority와 무관하게 동작" 테스트도 함께 요구한다(R-3).
- **R-5 [정보] `ModuleOwnershipTest`의 모듈 판별은 빌드 출력 경로의 폴더 이름이다**(`MODULE_DIR` 정규식). 출력 폴더를 다른 모듈 폴더로 돌리는 식의 조작은 `seenModules` 일치 검사로 닫힌 쪽 실패가 되는 것을 코드로 확인했다. 런타임 배포 클래스패스는 검사하지 않는다는 한계는 테스트 주석에 적혀 있다. 추가 조치 요구 없음.
- 확인한 좋은 점: `OrderState` 이름 변경은 열린 PR이 옛 이름을 쓰면 컴파일 실패로 드러나므로 조용한 의미 변화가 생기지 않는다. 시뮬레이션 실행기는 상태가 아니라 사유 코드(`DAY_END`, `CASH_SHORT_AT_FILL`)로 처리해(`SimulationExperimentRunner.java:259-260`) `CANCELLED→EXPIRED` 변경의 영향이 없다.

## 판정
수정 필요(A14, R-1)

- 고칠 것: R-1(새 모듈이 아키텍처 테스트 클래스패스에 자동으로 들어오거나 빠지면 빌드가 실패하게 하고, 그 실패를 확인하는 검사를 둔다), 그 결과에 맞춰 D-034:32·`ModuleOwnershipTest.java:44`·ADR-002 9장 문구 정정(A14 (1)), `broker-and-fault-design.md:68` 예시 정정(R-2, A14 (2)).
- 권고(이번 PR 필수 아님): R-3 D-033 예외 문구, R-4 2단계 테스트 요구.
- 이 PR은 실전 관련 PR(보호 테스트 `ArchitectureTest` 계열 추가, `GuardedOrderGatewayTest`·`RealOrderGatewaysTest` 수정, 실전·가상 공용 `OrderState` 계약 변경)이므로 재검토 승인 뒤에도 사용자 확인 머지와 독립 QA 관문 대상이다(지침 10·11장).

## 재검토 2026-10-09
- 대상: PR #21 / 커밋 `7dd0d78`(브랜치 `claude/project-thread-bl5qbg`) / main(`52ad85f`, 머지 기준점과 같음) 대비 diff 전체. 앞 검토(`4224efb`) 뒤 추가 커밋 `20f514a`(이 원문), `f2ffbab`(R-2·R-3 문서), `7dd0d78`(R-1 수정). `4224efb..7dd0d78` 비교 대상(검토 원문 제외): `CLAUDE.md`, `autotrade-architecture-test/build.gradle.kts`, `ModuleOwnershipTest.java`, ADR-001, ADR-002, D-033, D-034, `broker-and-fault-design.md`. 운영 코드(`*/src/main/*`) 추가 변경 없음.
- 점검표: A
- 검토자: risk-ops 서브에이전트. 커밋 메시지·ADR의 "검증" 서술은 근거로 쓰지 않고 아래 실험으로 다시 확인했다.
- 직접 실행한 것:
  - 새 분리 작업 트리에서 `./gradlew build --no-daemon -I <저장소 밖 미러 init 스크립트>` → **BUILD SUCCESSFUL**(57초). 실패 0, 오류 0, 건너뜀 0. architecture-test 20(ArchitectureTest 12, ModuleOwnershipTest 8), risk 41, app-engine 7, config 30, domain 31, sim 75, app-lab 86, strategy 25, gateway 1, watchdog 1, broker-api 1. 보호 테스트: `RealTradingGuardTest` 31/31, `GuardedOrderGatewayTest` 8/8, `RealOrderGatewaysTest` 4/4, `EngineMainTest` 3/3, `ArchitectureTest` 12/12, `OrderStateTest` 4/4, `ConfigLoaderTest` 21/21.
  - 변조 실험 E1(앞 검토 실험 2와 같은 조건): 새 모듈 `autotrade-rogue`를 settings에만 include하고(`autotrade-risk` 의존), `com.autotrade.risk.guard.RogueRaiser`가 `beginActivation`을 부르게 함 → `./gradlew build` **실패**. `#운영_클래스는_패키지를_소유한_모듈에만_있다`(RogueRaiser 소유 위반)와 `#클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다`("소유 모듈 표(OWNERS)에 없는 모듈: autotrade-rogue")가 실패. **R-1 구멍이 닫혔다.**
  - 변조 실험 E2(손 목록 회귀): rogue를 자기 패키지 `com.autotrade.rogue`로 두고 OWNERS에 등록한 뒤, `autotrade-architecture-test/build.gradle.kts`를 `4224efb` 판(손 목록)으로 되돌림 → **실패**. 시스템 속성이 없어 `#클래스패스는_settings의_...`가 닫힌 쪽으로 실패하고, `#운영_클래스는_...`의 `seenModules` 불일치로도 실패.
  - 변조 실험 E3: E2 상태에서 빌드 파일만 PR 판으로 복원(rogue가 자동으로 클래스패스에 들어옴) → `ArchitectureTest#Guard를_올리는_조작은_명령_채널에서만_한다` **실패**(rogue의 `beginActivation` 호출 검출). 즉 기존 보호 규칙도 이제 새 모듈에 닿는다. (E2·E3에서 `#아무도_의존하지_않는_새_모듈이_빠지면_잡아낸다`도 실패했는데, 실험에서 내가 OWNERS에 `autotrade-rogue`를 넣어 그 테스트의 가정 이름과 겹친 탓이다. 결함 아님.)
  - 실험 파일은 모두 지우고 `git status` 깨끗함 확인. 커밋·push 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 운영 코드 변경 없음(앞 검토 이후 `*/src/main/*` 변경 0). `RealTradingGuard.java:332,337` 초기 OFF, `RealTradingGuardTest` 31/31, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과. 새 모듈에서 Guard를 올리는 코드는 이제 빌드가 막는다(E1, E3) |
| A2 | 통과 | `ArchitectureTest.java` 변경 없음, 12/12. 아키텍처 테스트 클래스패스가 settings의 모든 Java 모듈에서 자동으로 만들어짐(`autotrade-architecture-test/build.gradle.kts:24`), 범위 교차 확인 `ModuleOwnershipTest.java:137`. 앞 검토의 조건(클래스패스 밖 모듈)은 E1·E2·E3로 해소 확인. 남는 한계는 아래 R-6 |
| A3 | 통과 | 라우팅 코드 변경 없음. `RealOrderGateways.java:31-33`, `RealOrderGatewaysTest` 4/4 |
| A4 | 통과(이 PR 범위) | 앞 검토와 같음: `UNKNOWN`·`CANCEL_REQUESTED` 비종결(`OrderStateTest#미확정과_취소_요청_중은_종결이_아니다`), `GuardedOrderGateway.java:85` 그대로. 재발주 로직은 미구현이고 TST-07 (7)로 7단계 완료 조건에 묶여 있음 |
| A5 | 해당 없음 | `IdempotencyKey`·발급기 변경 없음(TST-07 (7)로 추적) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음(F-2는 TST-07 (1)로 추적) |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 해당 없음 | 프로세스 런타임 의존·`processIsolation` 변경 없음. 바뀐 빌드 파일은 테스트 전용 모듈의 `testImplementation`뿐이라 실행 프로세스 클래스패스에 영향 없음 |
| A9 | 통과 | 보호 폴링 `toss-polling-scope` 기본 `HELD_ONLY` 유지(`autotrade-default.json:5`). R-3 반영: D-033에 "보유 종목 보호 폴링은 결정 1의 예외, 끄거나 막는 변경은 risk-ops 검토와 사용자 결정"(`D-033-unpriced-source-default-off.md:27`). 설계 문서 예시도 `toss-polling-scope: HELD_ONLY`와 "실시간 순위와 별개" 주석 유지(`broker-and-fault-design.md:68-71`) |
| A10 | 통과 | 추가 diff grep 0건. 실험 픽스처 이름·별칭(`autotrade-rogue`, `com.autotrade.rogue`)만 테스트 문자열로 쓰임 |
| A11 | 통과 | 보호 테스트 삭제·약화 없음(`ArchitectureTest` 12, Guard 계열 수 그대로). `ModuleOwnershipTest` 5→8로 늘었고 새 테스트에 실패 경우 포함: `#아무도_의존하지_않는_새_모듈이_빠지면_잡아낸다`(클래스패스 누락, 표 누락, 손 목록 회귀, 표에만 남은 모듈, 대조군), `#클래스패스_항목에서_모듈_이름을_알아낸다`(외부 jar 불인정 포함). Gradle 밖 실행 시 속성 없음으로 실패(닫힌 쪽, E2에서 확인) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음. 설정 예시 정정(R-2)은 #1·#2 처리 규칙과 무관 |
| A13 | 해당 없음 | 시장·시각·금액 계산 변경 없음 |
| A14 | 통과 | 앞 검토 실패 사유 해소: (1) D-034:32는 "settings에 include한 모듈은 빠뜨릴 수 없다"와 남는 한계(settings 밖 폴더, Gradle 밖 jar, 런타임 클래스패스)로 고쳐졌고 E1·E2로 사실임을 확인. `ModuleOwnershipTest` 클래스 주석, ADR-002 9장 보강, ADR-001 2.2·위험표, CLAUDE.md가 같은 동작을 적음. (2) `broker-and-fault-design.md:68` 예시가 기본값 `[KIWOOM, KIS]`와 D-033·Q-020 주석으로 정정. ORD-07·RSK-01·17종·D-033·D-034 일치는 앞 검토와 같음 |

## 점검표 밖 발견 (재검토)
- R-1: 해소(E1·E2·E3).
- R-2: 해소. 다만 `.claude/agents/risk-ops.md:18`의 "장애 대응표 14종"은 그대로다(에이전트 설정 파일, 사용자에게 알릴 사항으로 유지).
- R-3: 반영됨(권고 사항이었음).
- R-4: 미반영. 이번 PR 필수 아님. 2단계 시세 게이트웨이 소스 선택 PR에서 "기본 설정 시 요금 미확인 소스 연결 0건"과 "보호 폴링은 priority와 무관" 테스트를 요구한다.
- **R-6 [낮음, 남은 한계, 조치 요구 없음]** 범위 검사는 `rootProject.subprojects` 가운데 `java` 플러그인을 쓴 모듈만 본다. `includeBuild`로 붙인 다른 빌드, 저장소 안 파일 jar 의존, `java` 플러그인 없이 직접 만든 컴파일 태스크는 대상 밖이다. 이런 구성은 `settings.gradle.kts`나 모듈 빌드 파일을 바꿔야 생기므로 리뷰에서 보인다. 실행 중인 클래스패스는 여전히 검사하지 않는다. D-034:32에 이 한계가 적혀 있다. 실전 프로세스에 외부 jar나 included build가 들어오는 PR은 risk-ops 검토 대상으로 본다.
- 확인한 점: 모듈 판별을 Gradle 프로젝트 이름(settings 목록)과 빌드 출력 폴더 이름(클래스패스) 두 갈래로 하므로 둘이 다르게 꾸며지면 불일치로 실패한다(닫힌 쪽). 손 목록 회귀는 시스템 속성 누락이나 범위 차이로 잡힌다(E2).

## 판정 (재검토)
승인

- 앞 검토의 수정 필요 항목(A14, R-1)이 해소된 것을 직접 실행한 빌드와 변조 실험 E1~E3로 확인했다. 확인 못 함 항목은 없다(A5~A8·A13은 이 PR이 건드리지 않아 해당 없음, 미구현 상태는 TST-07로 7단계 완료 조건에 묶여 있다).
- 이 PR은 실전 관련 PR이다(보호 테스트 `ArchitectureTest` 계열 추가·확장, `GuardedOrderGatewayTest`·`RealOrderGatewaysTest` 수정, 실전·가상 공용 `OrderState` 계약 변경, 장애 설계 문서 수정). 이 승인은 risk-ops 역할 검토 결과일 뿐이다. 머지는 독립 QA 관문을 거친 뒤 사용자가 직접 한다(지침 10·11장).
