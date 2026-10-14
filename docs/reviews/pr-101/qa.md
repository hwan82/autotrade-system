# QA 검토: secrets.json·runtime-config.json 거부 경고에서 계좌번호 모양 키 이름 가림(PR #100 QA R4)
- 대상: PR #101 / 커밋 f8f69f984c6a0a64eb5730a5453923f51c21e5fa / main(bc197e1) 대비 diff 전체(merge-base = 현재 origin/main bc197e1)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 실전 엔진 로그와 실전 운영 화면(`configWarning`, ADR-006 2.3)에 나가는 경고 문구를 바꾸고, 계좌번호 노출(A10)을 다룬다. 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-101/architect.md`, `risk-ops.md`)의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 10파일, +352 −5. 운영 코드 3파일(`SecretPaths.java`, `ConfigJson.java`, `ConfigLoader.java`, 모두 `autotrade-config`), 시험 2파일(`ConfigLoaderTest.java` +71, `EngineMainTest.java` 입력 키 이름만 변경), 문서 5파일(ADR-006 2.3 한 문단, `qa-out-status.md` 한 줄, `pending.md` 한 줄, 검토 원문 2)
  - 보호 영역 변경 여부: `autotrade-architecture-test`, `autotrade-risk`, `autotrade-broker*`, `autotrade-app-engine/src/main` diff **0줄**. `ArchitectureTest`·`RealTradingGuardTest`·`GuardedOrderGatewayTest` 변경 없음. 추가된 `@Disabled`·`@Tag` 없음
  - `./gradlew build --no-daemon`(head f8f69f9): Maven Central 429로 5회 실패(재시도 스크립트가 로그에서 429를 확인한 뒤에만 재시도, 루프 하나) 뒤 6회째 **BUILD SUCCESSFUL**. 시험 결과 XML 합계 tests **1,098**, failures 0, errors 0, skipped 0
  - main 병합: merge-base가 현재 origin/main(bc197e1)과 같아 병합 빌드는 위 빌드와 같다. GitHub `mergeable_state: clean`
  - CI(head f8f69f9, check-runs 3건): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - **QA 탐침**(`autotrade-config` 시험 폴더에 임시 시험 `QaProbePr101Test`를 넣어 `ConfigLoader.load` 경고 원문을 출력, 확인 뒤 삭제. 저장소에 남기지 않음. 계좌번호는 가짜 `99990000-0x`, runtime-config.json은 계좌 `KIS-1` 하나):
    - P1 secrets.json `real-trading/operator-password-hash` 자리에 객체 `{"hunterpass":"x"}` → `…비밀값이 아닌 키가 있습니다: [real-trading/operator-password-hash/hunterpass]` (**키 이름 나감**, 아래 R1)
    - P1b `brokers/kis/app-key` 자리에 `{"abcdefsecretword":"x"}` → `[brokers/kis/app-key/abcdefsecretword]` (**키 이름 나감**, R1)
    - P2 `real-trading/account-numbers/99990000-01/{"x":"y"}` → `[real-trading/account-numbers/*/x 1개]` (가려짐)
    - P3 runtime-config.json 계좌 `id` 두 개를 `99990000-01`로 → `ValueInstantiationException at real-trading: real-trading.accounts의 id가 겹칩니다: 99990000-01` (**나감**, 이 PR 전부터, 아래 R2)
    - P4 대문자·숫자 키 `real-trading/KIS1234567` → `[real-trading/* 1개]` (가려짐)
    - P5 정상 계좌번호 + 비밀값 아닌 키 `real-trading/extra` → `[real-trading/extra]`, 계좌번호 값 안 나감, 계좌번호 0개로 기동
    - P6 runtime-config.json 목록 원소 안 숫자 키 → `UnrecognizedPropertyException at real-trading/accounts/0/*` (가려짐)
    - P7 정상(기준) → 경고 빈 문자열, 계좌 1개·계좌번호 1개
    - P8 오타 부모 `acount-numbers` 아래 `{"KIS-1":"99990000-01"}` → `[real-trading/acount-numbers/* 1개]` (값·키 안 나감)
    - P9 맵 키가 소문자 별칭 `main`, 값이 배열 → `MismatchedInputException at real-trading/account-numbers/main` (별칭은 비밀값 아님, 값 안 나감)
    - P10 enum 자리에 `99990000-01` → `InvalidFormatException at real-trading/accounts/0/broker` (값 안 나감)
    - P11 두 파일 모두 문제(runtime `real-trading/99990000-02`, secrets `account-numbers/99990000-01: {}`) → 두 경고 모두 `MismatchedInputException at real-trading/account-numbers/*` (가려짐. runtime 쪽 경고가 실제 원인 대신 합친 바인딩의 첫 오류를 보임, 아래 R3)
    - P12 runtime-config.json 오타 부모 아래 계좌번호 키 → `UnrecognizedPropertyException at real-trading/acount-numbers` (가려짐)
    - P13 secrets.json 맵 값 `null` → 무시 경고 "…1개를 무시했습니다"(키 안 나감)
    - 바인딩 실패·거부가 일어난 P1~P6, P8~P13 모두 secrets.json이 버려지면 계좌번호 0개로 기동(실전 계좌는 계좌번호가 없어 OFF 유지)
  - **변이 탐침**(`:autotrade-config:test :autotrade-app-engine:test`, 하나씩 넣고 확인 뒤 원복, `git status` 깨끗):
    - M1 `KEY_LIKE`를 `[a-z0-9][a-z0-9-]*`로 넓힘 → 36건 중 3건 FAILED(잡힘)
    - M2 `SecretPaths.maskedPath`의 맵 키 자리 갈래(`SCHEMA.contains(any)`)에서 `*` 대신 원래 키 이름을 씀 → **BUILD SUCCESSFUL(살아남음)**. 같은 변이에서 탐침 P2 입력의 경고가 `[real-trading/account-numbers/99990000-01/x]`로 **계좌번호 모양 키가 그대로 나감**을 확인(아래 Q1)
    - M3 구조에 없는 키 아래 `/*` 붙이기 삭제 → 2건 FAILED(잡힘)
    - M4 `ConfigJson.maskedPath`에서 필드 이름을 가리지 않음 → 2건 FAILED(잡힘)
    - M5 구조에 없는 키를 키 이름 모양과 상관없이 그대로 씀 → 1건 FAILED(잡힘)
  - 경고가 나가는 곳 확인: `ProcessStartup.java:20-21`(엔진·게이트웨이·감시 로그), `CoreConfiguration.java:39-40`(실험실 로그), `SettingsController.java:151`(실험실 설정 화면), `EngineMain.java:86` → `EngineCommandHandler` 상태 `configWarning`(실전 운영 화면). 모두 `LoadedConfig.warning()` 하나에서 나오므로 이 PR의 가림이 모든 출구에 같이 적용된다
  - diff 추가 줄에서 키·토큰·계좌번호 값 grep: 시험의 `99990000-0x`와 `PLACEHOLDER-NOT-A-REAL-KEY-7f3a` 자리표시자뿐

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·계좌 활성 상태 코드 diff 0줄. 바뀐 것은 거부 예외 메시지 문자열뿐이고 거부 판단(`ConfigLoader.java:78-86` 비밀값 아닌 키 → `secrets = null`, `:94-127` 바인딩 실패 → 그 파일 버림)은 그대로. 새 `forNonSecretWarning`이 예외를 던져도 같은 catch(`ConfigLoader.java:83`)로 잡힌다. 탐침에서 secrets.json을 버린 모든 경우 계좌번호 0개로 기동. 빌드의 `:autotrade-risk:test`(기본 OFF·재기동 OFF) 통과 |
| A2 | 통과 | 주문 출구·`GuardedOrderGateway`·`ArchitectureTest` 변경 없음. 위 빌드에서 ArchUnit 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진 기동 경로에 더해진 일은 클래스 로딩 때 한 번 만드는 `SCHEMA` 집합(`SecretPaths.java:31, 216-233`, 레코드 반사)과 경고를 만들 때 경로마다 선형 처리뿐. 실험실·게이트웨이 의존 없음. `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다` 통과 |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 이 PR이 목표로 한 계좌번호 모양 키 이름(숫자가 든 키)은 탐침 P2·P4·P6·P8·P11·P12와 새 시험 5건에서 경고에 나가지 않는다. 경고는 모든 출구(로그 3곳, 실험실 설정 화면, 실전 운영 화면)가 같은 `LoadedConfig.warning()`을 쓴다. 시험 값은 자리표시자. 남은 노출: P1·P1b(비밀값 잎 아래 소문자·하이픈만으로 된 키 이름은 보임, R1)는 계좌번호·실제 API 키 형식이 아니고 이 PR 전에는 같은 자리 키 이름이 모양과 상관없이 나갔으므로 이 PR이 만든 노출이 아니다. P3(겹친 `id` 값)은 이 PR 전부터 있던 다른 갈래(R2). 둘 다 이 항목의 실패로 보지 않는다 `[추론]` |
| A11 | 통과 | 보호 시험 삭제·비활성화 0건. `EngineMainTest` 변경은 입력 키 이름만 숫자 → 글자로 바꿈(숫자 키가 한 줄로 묶이면 64KB 전제가 깨지므로). 64KB 초과 단언(`EngineMainTest.java:139`)과 응답 단언 그대로라 약화 아님. 새 동작 시험 5건(`ConfigLoaderTest.java:299, 314, 327, 341, 359`)이 노출 없음·오타 위치 표시를 단언하고 변이 M1·M3·M4·M5에서 실패해 실제로 동작을 지킨다. 다만 맵 키 자리 갈래를 지키는 시험은 없다(변이 M2 살아남음, Q1) |
| A12 | 통과 | 장애 대응표 #1~#17, O1~O8, 장애 테스트 8종의 감지·대응 동작을 바꾸지 않음. 설정 파일을 버리는 조건과 결과(비밀값 없이 기동, 계좌 OFF)는 그대로이고 표시 문자열만 바뀜 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | ADR-006 2.3(`docs/adr/ADR-006-real-control-channel.md:80`)에 새 가림 규칙(구조에 없는 비키 모양 키와 맵 키 자리는 `*`, 아래는 `/*`와 개수, runtime-config.json 경로 표기)이 적혀 있고 탐침 결과와 일치. 문장이 secrets.json 바인딩 실패 갈래(P9·P11)를 명시하지 않지만 같은 `describe`를 쓰므로 동작은 일치한다. 결정 로그·기획서·`config-keys.md`에는 경고 문구 규칙이 없어 바꿀 곳이 없다(grep 확인). 표시 전용 변경이라 새 D 번호는 필요 없다고 본다 `[추론]` |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(1,098건, 실패·오류·건너뜀 0) |
| B6 | 통과 | 새 동작에 시험 있음(A11). 건너뛰거나 지운 시험 없음. 맵 키 자리 갈래 시험 공백은 Q1 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음(`build.gradle.kts` 변경 0건) |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **가림 처리 중 예외로 설정 파일이 잘못 받아들여지는가**: `forNonSecretWarning`은 거부를 결정한 뒤 메시지를 만들 때만 불리고, 예외가 나도 같은 catch에서 `secrets = null`이 된다(`ConfigLoader.java:79-86`). `/`가 든 키·빈 키·한글 키 입력에서도 예외 없이 그 파일만 거부됨(기존 시험 `키_이름에_슬래시가_있어도_예외_없이_그_파일만_거부한다` 통과). 판단이 바뀌어 계좌번호가 잘못 붙는 경로는 찾지 못했다.
2. **secrets.json이 부분적으로 쓰여 다른 계좌의 계좌번호가 붙는가**: P5·P8·P9·P11에서 secrets.json은 통째로 버려지고 계좌번호 0개. 파일 일부만 받아들이는 갈래는 이 PR이 만들지 않았다. 주문 경로·Guard 전이는 diff 밖이다.
3. **경고가 바뀌어 운영자가 거부 사실을 놓치는가**: 경고 앞부분(`secrets.json을(를) 쓰지 않고 비밀값 없이 기동: `)은 그대로라 `ConfigLoader.load`의 사용 여부 판단(`:38`)과 운영 화면 배너 조건(비어 있지 않음)이 그대로다. 오타 키는 소문자·하이픈이면 보여(P8, 시험 `:359`) 고칠 곳을 찾을 수 있다. 대문자·숫자가 든 오타 키는 `*`로만 보여 위치 찾기가 어려워지는 진단성 손실은 있으나 안전 쪽이다.
4. **경고 길이**: 숫자 키 수천 개는 한 줄(`… N개`)로 묶여 오히려 짧아진다. 글자 키 6,000개(82KB 이상)는 기존 2,000자 자르기로 응답 한도 안에 든다(`EngineMainTest` 통과).

## 점검표 밖 발견
- **Q1 맵 키 자리 가림을 지키는 시험 없음**(권고, 우선): 변이 M2(`SecretPaths.maskedPath`의 `SCHEMA.contains(any)` 갈래에서 `*` 대신 원래 키를 씀)가 모든 시험을 통과하고, 그 상태에서 P2 입력(`real-trading/account-numbers/99990000-01/{"x":"y"}`)이 계좌번호 모양 키를 경고에 그대로 낸다. 새 시험의 입력은 모두 오타 부모(`acount-numbers`) 아래이거나 바인딩 실패 갈래(`ConfigJson`)라 이 갈래를 지나지 않는다. P2 입력에 `99990000`이 없어야 한다는 시험 한 건이면 막힌다.
- **R1 비밀값 잎 아래 키는 모양과 상관없이 가리기**(권고): P1·P1b. 구조에 있는 잎(`operator-password-hash`, `app-key` 등) 아래 키는 구조상 있을 수 없어 오타 힌트로서 가치가 없는데, 소문자·하이픈만으로 된 이름이면 그대로 나간다. 사용자가 비밀값을 키 자리에 잘못 적는 경우를 막으려면 `maskedPath`의 else 갈래에서 바로 위 부모가 구조의 잎(또는 맵 키 자리)일 때는 `isKeyLike`와 상관없이 `*`를 쓰는 편이 맞다. 실제 증권사 키·토큰·해시 형식은 숫자·대문자·기호를 포함하므로 지금 노출 가능성은 낮다 `[추론]`.
- **R2 겹친 계좌 `id` 경고에 값이 실림**(권고, 이 PR 전부터): P3. `RealAccount` 형식 검증(`RuntimeConfig.java:197`, 영문·숫자·_·- 1~64자)이 `99990000-01`을 `id`로 받아들이고, 겹치면 `RuntimeConfig.java:154`가 그 값을 경고에 싣는다. `id`는 화면에 원래 보이는 공개 식별자라 이 경고만의 새 노출은 아니지만, ADR-006 2.3의 "값 없음"과 맞지 않는다. PR 본문 후속과 risk-ops F4도 같은 지적이다.
- **R3 두 파일이 다 버려질 때 runtime-config.json 경고가 실제 원인을 보이지 않음**(참고, 이 PR 전부터): P11. 결과(둘 다 버리고 default·비밀값 없이 기동)는 안전 쪽이고 노출도 없다. 진단성만의 문제(risk-ops F5와 같음).

## 판정
승인 (권고 Q1·R1·R2·R3, 차단 아님. 실전 관련이라 머지는 사용자)
