# QA 검토: 설정 경고 가림 보강, 값 자리 아래 키와 겹친 계좌 id(PR #101 QA Q1·R1·R2)
- 대상: PR #102 / 커밋 7d53eef88e445ff880e9504c7eaf9cb874fe0b81 / main(5dbaead) 대비 diff 전체(merge-base = 현재 origin/main 5dbaead)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 실전 엔진이 기동 때 읽는 설정 검증(`RuntimeConfig.RealTrading`)과, 엔진 로그·실전 운영 화면(`configWarning`, ADR-006 2.3)에 나가는 경고 문구를 바꾼다. 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-102/architect.md`, `risk-ops.md`)의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 8파일, +157 −15. 운영 코드 2파일(`RuntimeConfig.java`, `SecretPaths.java`, 모두 `autotrade-config`), 시험 1파일(`ConfigLoaderTest.java` +47 −2), 문서 5파일(ADR-006 2.3 한 문단, `qa-out-status.md` 한 줄, `pending.md` 한 줄, 검토 원문 2)
  - 보호 영역 변경 여부: `autotrade-architecture-test`, `autotrade-risk`, `autotrade-broker*`, `autotrade-app-engine` diff **0줄**. `ArchitectureTest`·`RealTradingGuardTest`·`GuardedOrderGatewayTest` 변경 없음. 추가된 `@Disabled`·`@Tag` 없음. 바뀐 기존 시험은 `범위를_벗어난_값은_키_이름과_함께_경고한다` 하나로, 입력을 0에서 99990001로 바꾸고 값 미노출 단언을 더했다(범위 밖 입력이라 같은 갈래, 단언은 늘었고 줄지 않음)
  - `./gradlew build --no-daemon`(head 7d53eef): 1회째 **BUILD SUCCESSFUL**. 시험 결과 XML 합계 tests **1,101**, failures 0, errors 0, skipped 0
  - main 병합: merge-base가 현재 origin/main(5dbaead)과 같아 병합 빌드는 위 빌드와 같다. GitHub `mergeable_state: clean`
  - CI(head 7d53eef, check-runs 3건): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - **QA 탐침**(`autotrade-config` 시험 폴더에 임시 시험 `QaProbePr102Test`를 넣어 `ConfigLoader.load` 경고 원문을 출력, 확인 뒤 삭제. 저장소에 남기지 않음. 계좌번호는 가짜 `99990000-0x`, runtime-config.json은 계좌 `KIS-1`·`KIW-1` 둘):
    - PR #101 QA 탐침 재실행: P1 `operator-password-hash/{"hunterpass":..}` → `[real-trading/operator-password-hash/* 1개]`, P1b `brokers/kis/app-key/{"abcdefsecretword":..}` → `[brokers/kis/app-key/* 1개]`, P2 `account-numbers/99990000-01/{"x":..}` → `[real-trading/account-numbers/*/* 1개]`, P3 겹친 id(`99990000-01` 두 번) → `ValueInstantiationException at real-trading: real-trading.accounts의 id가 겹칩니다(0번과 1번, 0부터)`. **넷 다 키 이름·값이 나가지 않음**(1차의 Q1·R1·R2 해소)
    - P1c `notification/slack/webhook-url/{"hooksecret":..}` → `[notification/slack/webhook-url/* 1개]`, P1d 잎 아래 두 단계 → `[real-trading/operator-password-hash/*/* 1개]`, P8 목록 잎(`market-data/priority`) 아래 객체 → `[market-data/priority/* 1개]` (가려짐)
    - P2b 맵 키 자리가 소문자 별칭(`main`)이고 그 아래 키 → `[real-trading/account-numbers/*/* 1개]` (가려짐)
    - P3b 겹친 id가 0번과 2번 → `(0번과 2번, 0부터)` (위치 맞음, 값 없음)
    - P3c 겹친 id + secrets.json 계좌번호 → runtime-config.json 거부, 계좌 0개·계좌번호 0개, 무시 경고에 값 없음
    - 진단성 회귀 확인: P4 레코드 아래 오타 `real-trading/acount-numbers` → `[real-trading/acount-numbers/* 1개]`, P4b 최상위 오타 `brokerz` → `[brokerz/* 1개]`, P4c `brokers/kis/app-kye` → `[brokers/kis/app-kye]` (레코드 안 오타 키 이름은 그대로 보임)
    - P5 `lab.max-parallel-runs: 99990001` → `…lab.max-parallel-runs는 1~64여야 합니다` (값 없음), P7 `default-seed-capital-krw: 0` → 값 없는 메시지
    - P6 정상 계좌 둘 + accounts에 없는 별칭 하나 → 계좌번호 `[KIS-1, KIW-1]`만 붙고 무시 경고 1개(별칭 거르기 동작이 `ids` → `firstIndex` 교체 뒤에도 같음)
  - **변이 탐침**(`:autotrade-config:test :autotrade-app-engine:test`, 하나씩 넣고 확인 뒤 원복, `git status` 깨끗):
    - N1 R1 되돌림(`underRecord &&` 제거) → 2건 FAILED(잡힘)
    - N2 맵 키 자리 갈래에서 `*` 대신 원래 키(PR #101 QA의 M2와 같음) → 1건 FAILED(잡힘, 1차 Q1 해소)
    - N3 겹친 id 메시지에 id 값을 다시 붙임 → 1건 FAILED(잡힘)
    - N4 구조에 없는 키를 모두 가림(`underRecord` 항상 거짓) → config 2건·engine 1건 FAILED(잡힘, 오타 힌트 진단성을 시험이 지킴)
    - N5 범위 메시지에 값을 다시 붙임 → 1건 FAILED(잡힘)
    - N6 별칭 거르기 제거(`firstIndex.containsKey` 삭제) → 2건 FAILED(잡힘)
    - N7 겹친 위치의 첫 번호를 항상 0으로 → **BUILD SUCCESSFUL(살아남음)**. 시험 입력이 0번과 1번뿐이라서다(아래 Q1, 진단 문구만의 문제)
  - 설정 레코드의 다른 검증 메시지 전수 확인(`RuntimeConfig.java` `IllegalArgumentException` 7곳, `requireNonNull` 키 이름): 값을 싣는 메시지 0건. `ConfigJson.isOwnValidation`은 스택 맨 위가 `RuntimeConfig`(중첩 레코드 포함)일 때만 메시지를 통과시키므로 JDK 예외 메시지는 실리지 않는다(`ConfigJson.java:66-69`)
  - `RealTradingGuard.java:69`(보호 영역, 이 PR이 건드리지 않음)의 `"실전 계좌 별칭이 겹칩니다: " + ref.id()`: 엔진의 유일한 생성처 `EngineMain.java:97`이 `RuntimeConfig.RealTrading.accountRefs()`를 넘기고, `AccountId`는 문자열 그대로 비교(`AccountId.java`, 대소문자 정규화 없음)라 설정 단계에서 걸러진 목록에 겹침이 생길 수 없다. 정상 경로로는 닿지 않는다고 본다 `[추론]`

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·계좌 활성 상태 코드 diff 0줄. `RuntimeConfig.RealTrading`의 바뀐 부분은 겹침 판정 자료구조(`Set` → `Map<id, 첫 위치>`)와 메시지뿐이고, 겹치면 예외로 runtime-config.json 전체를 거부하는 결과는 같다(P3·P3b·P3c: 계좌 0개). 계좌번호 붙이기 조건도 같은 집합을 본다(`RuntimeConfig.java:167`, P6, 변이 N6 잡힘). 빌드의 `:autotrade-risk:test`(기본 OFF·재기동 OFF) 통과 |
| A2 | 통과 | 주문 출구·`GuardedOrderGateway`·`ArchitectureTest` 변경 없음. 위 빌드에서 ArchUnit 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. 계좌 id 겹침 거부가 그대로라 Guard에 같은 별칭 두 계좌가 들어가 상태를 공유하는 경로가 생기지 않는다(위 `RealTradingGuard.java:69` 확인) |
| A8 | 통과 | 기동 경로에 더해진 일은 경고를 만들 때 구조에 없는 키마다 `SCHEMA` 선형 탐색 한 번(`SecretPaths.java:164`, 구조 경로 수십 개)뿐. 실험실·게이트웨이 의존 없음. `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다`(글자 키 6,000개) 통과 |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | PR #101 QA가 남긴 노출 둘(R1 값 자리 아래 소문자 키, R2 겹친 id 값)이 탐침 P1·P1b·P1c·P1d·P3·P3b에서 사라졌고, 범위 메시지 값(P5)도 빠졌다. 각각 시험(`ConfigLoaderTest.java:371, 383, 399`, `:143`)이 값·키 이름이 **없어야** 함을 단언하고 변이 N1·N3·N5에서 실패한다. 설정 레코드 검증 메시지 7곳 모두 값 없음. 시험 값은 `99990000-0x` 자리표시자 |
| A11 | 통과 | 보호 시험 삭제·비활성화 0건. 기존 시험 한 건은 입력만 바뀌고 단언이 늘었다(위). 새 동작 시험 3건 + 단언 1건이 변이 N1·N2·N3·N5를 잡는다. 1차 Q1(맵 키 자리 갈래 시험 공백)은 `:371`로 해소(N2 잡힘). 겹친 위치 번호의 정확성은 시험이 일부만 지킨다(N7, Q1) |
| A12 | 통과 | 장애 대응표 #1~#17, O1~O8, 장애 테스트 8종의 감지·대응 동작을 바꾸지 않음. 설정 파일을 버리는 조건과 결과(default 또는 비밀값 없이 기동, 계좌 OFF)는 그대로이고 문구만 바뀜 |
| A13 | 해당 없음 | 시장별 코드 변경 없음(P3의 두 번째 계좌 `market: US`는 겹침 판정과 무관) |
| A14 | 통과 | ADR-006 2.3(`docs/adr/ADR-006-real-control-channel.md:80`)에 잎 아래 키 가림, secrets.json 바인딩 실패 경고 포함, 설정 레코드 검증 메시지 값 없음(겹친 id는 위치만)이 적혀 있고 탐침 결과와 일치(PR #101 risk-ops F6도 해소). 결정 로그·기획서·`config-keys.md`에는 경고 문구 규칙이 없어 바꿀 곳이 없다. 표시 전용 변경이라 새 D 번호는 필요 없다고 본다 `[추론]` |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(1,101건, 실패·오류·건너뜀 0) |
| B6 | 통과 | 새 동작에 시험 있음(A11). 건너뛰거나 지운 시험 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음(`build.gradle.kts` 변경 0건) |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **겹친 id 판정이 바뀌어 같은 별칭 두 계좌가 엔진에 들어가는가**: `putIfAbsent`가 null이 아니면 바로 예외를 던져 runtime-config.json 전체를 거부한다(P3·P3b, 계좌 0개). 두 번째 계좌의 계좌번호가 첫 계좌에 붙거나 Guard가 한 별칭으로 두 계좌를 다루는 경로는 없다. Guard 생성처도 이 목록만 받는다(`EngineMain.java:97`).
2. **별칭 거르기 교체(`ids` → `firstIndex`)로 accounts에 없는 별칭의 계좌번호가 붙는가**: P6에서 `ORPHAN`은 버려지고 무시 경고 1개. 변이 N6이 기존 시험 2건에 잡혀 이 조건을 시험이 지킨다.
3. **가림을 넓혀 운영자가 거부 원인을 못 찾는가**: 레코드 안 오타 키(P4·P4b·P4c)는 그대로 보이고, 가려지는 것은 값 자리 아래(구조상 있을 수 없는 키)뿐이다. 변이 N4(모두 가림)가 config·engine 시험 3건에 잡혀 진단성도 시험이 지킨다. 경고 앞부분(`…을(를) 쓰지 않고 …`)은 그대로라 운영 화면 배너 조건과 `ConfigLoader.load`의 secrets.json 사용 판단(`ConfigLoader.java:38`)이 바뀌지 않는다.
4. **두 파일이 다 문제일 때**: P3c에서 runtime-config.json은 겹친 id로 거부, secrets.json은 쓰였지만 붙을 계좌가 없어 무시 경고만 남는다. 어느 쪽에도 값이 나가지 않는다.

## 점검표 밖 발견
- **Q1 겹친 id 위치 번호를 지키는 시험이 0번과 1번뿐**(권고, 낮음): 변이 N7(첫 위치를 항상 0으로)이 살아남는다. 첫 겹침이 0번이 아닌 경우(예: P3b와 반대로 1번과 2번) 시험 한 건이면 막힌다. 잘못돼도 경고 문구의 위치만 틀릴 뿐 거부 판단·노출에는 영향이 없다.
- **R1 `RealTradingGuard.java:69` 별칭 중복 메시지에 id 값**(참고, 보호 영역, 이 PR 범위 밖): 위 확인대로 정상 경로로는 닿지 않는다 `[추론]`. 나중에 Guard를 설정 외 경로(예: 계좌 추가 명령)로 만들게 되면 그때 같은 규칙을 맞추길 권한다. PR 본문 후속과 risk-ops F8·architect 발견 1과 같은 지적이다.
- PR #101 QA R3(두 파일이 다 버려질 때 runtime-config.json 경고가 합친 바인딩의 첫 오류를 보임)은 이 PR 범위가 아니며 그대로다(참고).

## 판정
승인 (권고 Q1, 참고 R1, 차단 아님. 실전 관련이라 머지는 사용자)
