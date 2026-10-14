# risk-ops 검토: secrets.json 거부 경고에서 위치가 틀린 키 아래 키 이름 숨김(PR #100 QA R4)
- 대상: PR #101 / 커밋 `c3c2e6b` (브랜치 `claude/project-thread-gayfxk`) / main(`bc197e1`) 대비 diff 5개 파일(+98 −3)
- 점검표: A
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 확인. 바뀐 실행 코드는 `ConfigLoader.java:80-81`(경고 문자열 생성)과 `SecretPaths.java:30-33, 126-165, 211-228`(경고용 경로 가림, 설정 구조 경로 목록)뿐. 나머지는 시험 2건 추가, `EngineMainTest` 입력 키 이름 변경, ADR-006 2.3 한 문장
  - `./gradlew build` 성공(BUILD SUCCESSFUL, 130 작업). 시험 결과 XML 집계 전체 1,095건 실패 0 건너뜀 0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 32건, `:autotrade-app-engine:test` 50건, `:autotrade-config:test` 33건 모두 통과. 새 시험 `ConfigLoaderTest#secrets의_위치가_틀린_키_아래_키_이름은_경고에_남기지_않는다`, `#secrets의_숫자가_든_키_이름은_구조에_없으면_가린다`, 바뀐 `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다` 통과
  - 변이 탐침(확인 뒤 원복, `git status` 깨끗): `ConfigLoader.java:81`에서 `SecretPaths.forNonSecretWarning(nonSecret)`를 원래 목록 `nonSecret`으로 되돌림 → `:autotrade-config:test` 33건 중 2건 FAILED(새 시험 2건, 잡힘)
  - 적대 입력 탐침(임시 시험 파일, 실행 뒤 삭제, 커밋 안 함). `ConfigLoader.load` 경고 원문:
    - S3 `{"real-trading":{"account-numbers":{"99990000-01":{"a":"b"}}}}` → `비밀값이 아닌 키가 있습니다: [real-trading/account-numbers/*/a 1개]` (가려짐)
    - S6 키에 `/`(`"a/99990000-01"`) → `[real-trading/a/* 1개]`, 예외 없음 (가려짐)
    - S7 빈 키 `{"":{"99990000-01":"x"}}` → `[* 1개]` (가려짐)
    - S8 대문자 오타 `Account-Numbers` 아래 → `[real-trading/*/* 1개]` (가려짐)
    - S9 스칼라 키 아래 객체 `lab/max-parallel-runs/{"99990000-01":..}` → `[lab/max-parallel-runs/* 1개]` (가려짐)
    - S10 secrets.json의 `real-trading/accounts` 목록 → `[real-trading/accounts]` (목록 안 값·키 안 나감)
    - **S1** `{"real-trading":{"account-numbers":{"99990000-01":["x"]}}}` → `MismatchedInputException at com.autotrade.config.RuntimeConfig["real-trading"]->...RealTrading["account-numbers"]->java.util.LinkedHashMap["99990000-01"]` (**키 이름 노출**)
    - **S2** 같은 자리 값 `{}` → 위와 같은 경로로 `["99990000-01"]` **노출**
    - **S11** 같은 자리 값 숫자 `12345` → 위와 같은 경로로 `["99990000-01"]` **노출**
    - **S4** `{"real-trading":{"99990000-01":{}}}` → `UnrecognizedPropertyException at ...RealTrading["99990000-01"]` (**노출**)
    - **S5** `{"99990000-01":{}}` → `UnrecognizedPropertyException at com.autotrade.config.RuntimeConfig["99990000-01"]` (**노출**)
    - **S15** `{"brokers":{"kis":{"99990000-01":{}}}}` → `...BrokerKeys["99990000-01"]` (**노출**)
    - R1(runtime-config.json) `{"real-trading":{"99990000-01":"x"}}` → `runtime-config.json을(를) 쓰지 않고 default로 기동: UnrecognizedPropertyException at ...RealTrading["99990000-01"]` (**노출**, 이 PR 전부터)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·계좌 활성 상태를 만드는 코드 변경 없음. 바뀐 것은 거부 예외의 메시지 문자열뿐이고 거부 자체(`ConfigLoader.java:79-86`: 비밀값 아닌 키가 있으면 throw → `secrets = null`)는 그대로라 secrets.json을 버리면 계좌번호 없이 기동 → 모든 계좌 OFF(`EngineMain.java:98-101`). `forNonSecretWarning`이 예외를 던져도 같은 catch(`ConfigLoader.java:83`)로 잡혀 같은 결과. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 변경 없음(diff에 `autotrade-risk`·`autotrade-broker-*`·주문 경로 파일 없음). `:autotrade-architecture-test:test` 32건 통과 |
| A3 | 통과 | 증권사 선택·주문 라우팅 코드 변경 없음(diff 범위). 시험 실행만 근거이며 대체 경로 추가 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직을 건드리지 않음 |
| A5 | 해당 없음 | 멱등 키 생성·저장을 건드리지 않음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터를 건드리지 않음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치를 건드리지 않음 |
| A8 | 통과 | 엔진 기동 경로(`EngineMain.java:83` `ProcessStartup.start` → `ConfigLoader.load`)에서 추가되는 일은 클래스 로딩 때 한 번 만드는 `SCHEMA` 집합(`SecretPaths.java:31, 211-228`, 레코드 반사)과 경고 생성 때 경로마다 선형 처리뿐. 실험실·게이트웨이 의존 없음. 6,000키 입력 `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다` 통과(명령 채널 응답 유지) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절을 건드리지 않음 |
| A10 | 실패 | 이 PR이 고친 경로(비밀값 아닌 키 목록, `ConfigLoader.java:78-81`)는 가려진다(S3·S6~S10, 새 시험 2건, 변이 탐침). 그러나 **같은 secrets.json 거부 경고의 다른 갈래**가 그대로 계좌번호 모양 키 이름을 내보낸다: secrets.json이 1단계 검사를 통과한 뒤 값 바인딩에서 실패하면 `ConfigLoader.java:95-98, 124-125`가 `ConfigJson.describe`(`ConfigJson.java:39-49`)의 Jackson 경로 참조(`getPathReference()`, 맵 키·모르는 키 이름 포함)를 경고에 그대로 넣는다. 1단계를 통과하는 이유: (a) 비밀값 맵 자리 값이 문자열이 아니어도(배열·빈 객체·숫자) 경로가 `real-trading/account-numbers/*`와 맞아 비밀값으로 쳐짐(S1·S2·S11), (b) 빈 객체 `{}`는 `collectLeaves`(`SecretPaths.java:182-190`)에서 잎이 0개라 검사 대상이 되지 않음(S4·S5·S15). 이 경고는 엔진 로그(`ProcessStartup.java:20-21`)와 엔진 상태 `configWarning` → 실전 운영 화면으로 나간다(ADR-006 2.3). PR 커밋 메시지의 "둘 다 막힌다"와 ADR-006 2.3 새 문장의 취지(위치가 틀린 키 아래 키 이름이 로그·화면에 나가지 않음)가 S4·S5·S15에서 성립하지 않는다(위치가 틀린 키 이름 자체가 계좌번호 모양이고 값만 `{}`) |
| A11 | 통과 | 보호 시험 삭제·비활성화 없음. `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다`는 입력 키 이름만 숫자에서 글자로 바뀌었고(새 가림 규칙에서 6,000개가 `lab/* 6000개` 한 줄로 묶이지 않게), 64KB 초과 전제 단언(`EngineMainTest.java:139`)과 자르기 단언은 그대로라 약화 아님. 새 동작 시험 2건(`ConfigLoaderTest.java:299, 314`)이 계좌번호 모양 문자열이 **없어야** 한다를 단언하고, 변이 탐침에서 둘 다 실패해 잡음. 다만 A10의 남은 갈래(S1·S2·S4·S5·S11·S15)를 막는 시험은 없다 |
| A12 | 통과 | 장애 대응표 #1~#17, O1~O8, 장애 테스트 8종의 전제(감지·대응 동작)를 바꾸지 않음. 표시 문자열만 바뀜 |
| A13 | 해당 없음 | 시장별 경로·시간·금액 계산 변경 없음 |
| A14 | 통과 | ADR-006 2.3(`docs/adr/ADR-006-real-control-channel.md:80`)에 새 가림 규칙과 예시가 적혀 있고 코드(`SecretPaths.forNonSecretWarning`, `KEY_LIKE`)와 일치. 표시 전용 변경이라 새 결정 번호는 필요 없다고 본다 `[추론]`. 참고: `docs/qa-out-status.md:49`는 R4를 "사용자 결정 대기"로 적고 있어, 이 PR로 처리했다는 기록은 메인이 따로 남겨야 한다 |

## 점검표 밖 발견
- **F1(A10과 같은 원인, 수정 필요)**: secrets.json을 버리는 경고 중 바인딩 실패 갈래가 Jackson 경로 참조로 키 이름을 그대로 싣는다(위 S1·S2·S4·S5·S11·S15). 고치는 방법 제안(어느 쪽이든 시험으로 위 6개 입력이 `99990000`을 경고에 남기지 않음을 단언):
  1. 1단계 검사 강화: `collectLeaves`가 빈 객체도 잎으로 넣고, 비밀값 경로의 잎이라도 값이 문자열(또는 null)이 아니면 비밀값 아닌 키로 분류해 `forNonSecretWarning`으로 가린다. 그러면 secrets.json 바인딩 실패는 레코드 검증 메시지만 남는다.
  2. 또는(함께) secrets.json 경고의 `combinedError`에서 `JsonMappingException.getPath()`의 각 `Reference.getFieldName()`을 `maskedPath`와 같은 규칙으로 가린 경로로 바꿔 쓴다(`describe`에 파일 종류를 넘기거나 secrets 전용 describe).
- **F2(권고, 이 PR 전부터, 범위 밖)**: runtime-config.json의 모르는 키도 같은 `describe` 경로 참조로 키 이름이 나간다(R1, `{"real-trading":{"99990000-01":"x"}}` → `RealTrading["99990000-01"]`). QA PR #100 P5는 "첫 모르는 키까지만"이라 괜찮다고 봤지만, 첫 모르는 키 자체가 계좌번호 모양이면 노출된다. F1의 2번 방법을 두 파일에 함께 쓰면 같이 막힌다. 이 PR 판정의 조건으로 넣지는 않으나 같은 후속에서 처리하길 권한다.
- **F3(참고)**: `KEY_LIKE`(`[a-z][a-z-]*`)에 맞는 구조 밖 키 이름은 그대로 보인다(예: S13 `acount-numbers` 아래 `kis-main`은 `*`로 가려지지만, `real-trading` 바로 아래 글자만 된 오타 키는 보임). 숫자 없는 계좌번호는 없다고 보면 문제없다 `[추론]`. 대문자·밑줄이 든 오타 키는 `*`로 바뀌어 고칠 곳을 찾기 어려워지는 진단성 손실이 있으나 안전 쪽이라 받아들인다.
- 최악의 경우 추정: 노출되는 것은 계좌번호(또는 사용자가 키 자리에 적은 문자열)이고, 비밀번호·API 키 값은 어느 갈래에서도 나가지 않는다(값은 경고에 실리지 않음, S1~S15 전부 확인). 주문·Guard에는 영향 없음. 노출 범위는 로컬 엔진 로그와 로컬 실전 운영 화면(실험실 API 경유)이다.

## 판정
수정 필요(A10: secrets.json 거부 경고의 바인딩 실패 갈래가 계좌번호 모양 키 이름을 엔진 로그·실전 운영 화면에 그대로 냄. F1을 고치고 S1·S2·S4·S5·S11·S15 입력에 대한 시험 추가)

## 재검토 2026-10-14
- 대상: PR #101 / 헤드 `b7b49fe` (브랜치 `claude/project-thread-gayfxk`) / main(`bc197e1`) 대비 diff 9개 파일(+254 −5). 앞 검토(`c3c2e6b`) 뒤 바뀐 실행 코드는 `42a9907`의 `ConfigJson.java:42, 51-64`(`describe`의 Jackson 경로를 가린 경로로 바꿈)와 `SecretPaths.java:42-45, 162`(`isKeyLike` 공개, 같은 규칙 재사용)뿐. `b7b49fe`는 시험 1건과 `docs/qa-out-status.md:49` 한 줄
- 점검표: A
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체와 `git diff 19fda2c b7b49fe`, `git diff 837f777 42a9907` 확인. `ConfigJson.describe` 호출처는 `ConfigLoader.java:181, 200` 두 곳뿐(`grep`), `getPathReference` 사용은 저장소에 남지 않음. 보호 영역(`autotrade-risk`, `autotrade-architecture-test`, `autotrade-broker*`, `autotrade-app-engine/src/main`) diff 0줄, 추가된 `@Disabled`·`@Tag` 없음
  - `./gradlew build --no-daemon` 성공(exit 0). 시험 결과 XML 집계 1,098건 실패 0 오류 0 건너뜀 0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 32건, `:autotrade-app-engine:test` 50건, `:autotrade-config:test` 36건 통과. 새 시험 `ConfigLoaderTest#runtime_config의_모르는_키_이름도_숫자가_들면_가린다`(:327), `#secrets의_값_바인딩이_실패해도_키_자리의_계좌번호는_경고에_남기지_않는다`(:341), `#runtime_config의_모르는_키가_키_이름_모양이면_고칠_곳을_보여_준다`(:359) 통과
  - 변이 탐침(확인 뒤 원복, `git status` 깨끗): `ConfigJson.java:42`의 `maskedPath(jme)`를 원래 `jme.getPathReference()`로 되돌림 → `:autotrade-config:test` 36건 중 3건 FAILED(위 새 시험 3건, 잡힘)
  - 적대 입력 탐침(임시 시험 파일 `ZzProbeTest`, 실행 뒤 삭제, 커밋 안 함). `ConfigLoader.load` 경고 원문(계좌번호 모양 `99990000`, `com.autotrade`, 가짜 비밀값 문자열 포함 여부):
    - 앞 검토 S1·S2·S11 → `secrets.json을(를) 쓰지 않고 비밀값 없이 기동: MismatchedInputException at real-trading/account-numbers/*` (가려짐, 계좌번호 0개로 기동)
    - S4 → `UnrecognizedPropertyException at real-trading/*`, S5 → `at *`, S15 → `at brokers/kis/*` (가려짐)
    - S16 비밀값 자리에 객체 `brokers/kis/app-key/{"99990000-01":"<가짜>"}` → `[brokers/kis/app-key/* 1개]` (키·값 안 나감)
    - S17 두 파일 모두 문제(runtime `real-trading/99990000-02`, secrets `account-numbers/99990000-01: {}`) → 두 경고 모두 `at real-trading/account-numbers/*` (가려짐. 아래 F5)
    - S18 runtime 정상 + secrets 바인딩 실패 → 계좌 1개, 계좌번호 0개, 경고 가려짐 (해당 계좌는 계좌번호 없음 → OFF 유지)
    - S19 오타 부모 아래 빈 객체 → `at real-trading/acount-numbers` (오타 키만 보임)
    - 앞 검토 R1 → `at real-trading/*`, 목록 원소 안 모르는 키 → `at real-trading/accounts/0/*`, `/`가 든 키·한글 섞인 키·대문자 키 → `*` (가려짐)
    - 형식 오류 `lab/max-parallel-runs: "99990000-01"` → `InvalidFormatException at lab/max-parallel-runs`, 증권사 enum 자리 → `at real-trading/accounts/0/broker` (값 안 나감), 문법 오류 → `JsonParseException`만
    - runtime-config.json 맵 비밀값 → `[real-trading/account-numbers/* 1개]` (앞 PR R2 동작 유지)
    - R11 같은 `id` 두 번(`id`를 `99990000-01`로 적음) → `...at real-trading: real-trading.accounts의 id가 겹칩니다: 99990000-01` (**나감**, 아래 F4)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·계좌 활성 상태를 만드는 코드 변경 없음. 새 변경은 경고 문자열의 경로 표기뿐이고 거부 판단(`ConfigLoader.java:78-86` 비밀값 아닌 키 → 거부, `:94-127` 바인딩 실패 → 그 파일 버림)은 그대로. 바인딩 실패 탐침 S1·S2·S4·S5·S11·S15·S18 모두 계좌번호 0개로 기동 → 실전 계좌 OFF(`EngineMain.java:83` 이후 Guard 생성). `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 diff 0줄. `:autotrade-architecture-test:test` 32건 통과 |
| A3 | 통과 | 증권사 선택·주문 라우팅 코드 diff 0줄. 대체 경로 추가 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직을 건드리지 않음 |
| A5 | 해당 없음 | 멱등 키 생성·저장을 건드리지 않음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터를 건드리지 않음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치를 건드리지 않음 |
| A8 | 통과 | 엔진 기동 경로(`EngineMain.java:83` `ProcessStartup.start` → `ConfigLoader.load`)에 더해진 일은 예외 하나의 경로 원소 수만큼 정규식 검사(`ConfigJson.java:57-62`)뿐. 실험실·게이트웨이 의존 없음. `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다` 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절을 건드리지 않음 |
| A10 | 통과 | 앞 검토의 실패 원인(F1: 바인딩 실패 갈래의 Jackson 경로 참조)이 `ConfigJson.java:42, 55-64`에서 고쳐짐. 경로의 필드 이름은 `SecretPaths.isKeyLike`(`[a-z][a-z-]*`, `SecretPaths.java:33, 43-45`)에 맞을 때만 보이고 나머지는 `*`, 목록 위치는 번호, 클래스 이름은 더 이상 실리지 않음. 앞 검토가 지목한 S1·S2·S4·S5·S11·S15와 F2(R1) 모두 위 탐침에서 계좌번호 모양 문자열이 나가지 않음. 고정 시험 `ConfigLoaderTest:327, 341`이 같은 입력에서 `99990000`·`com.autotrade`·`java.util`이 **없어야** 함을 단언하고 변이 탐침에서 잡힘. 시험 값은 `99990000-0x`·`PLACEHOLDER-NOT-A-REAL-KEY-7f3a` 자리표시자(diff 직접 확인, gitleaks는 CI에서 돎). 남은 노출 R11은 이 PR 전부터 있던 다른 갈래이고 노출 대상이 비밀값 자리(계좌번호 맵)가 아니라 `runtime-config.json`의 공개 식별자 `id` 자리여서 이 항목의 실패로 보지 않음(F4) |
| A11 | 통과 | 보호 시험 삭제·비활성화 없음. 새 동작 시험 3건(`ConfigLoaderTest:327, 341, 359`) 추가, 그중 2건은 노출이 없어야 함을, 1건은 키 이름 모양의 오타 위치가 보여야 함(진단성)을 단언. 변이 탐침에서 3건 모두 실패해 실제로 동작을 지킴. 앞 검토가 지적한 "남은 갈래를 막는 시험 없음"은 `:341`로 해소 |
| A12 | 통과 | 장애 대응표 #1~#17, O1~O8, 장애 테스트 8종의 감지·대응 동작을 바꾸지 않음. 설정 파일을 버리는 조건과 결과(비밀값 없이 기동, 계좌 OFF)는 그대로 |
| A13 | 해당 없음 | 시장별 경로·시간·금액 계산 변경 없음 |
| A14 | 통과 | ADR-006 2.3(`docs/adr/ADR-006-real-control-channel.md:80`)에 runtime-config.json 경고 경로 규칙 한 문장이 더해졌고 코드·탐침과 일치(`real-trading/accounts/0/id` 꼴, 키 이름 모양이 아니면 `*`). 다만 이 문장은 "runtime-config.json의 모르는 키·형식 오류 경고"만 적고, 같은 규칙이 secrets.json 바인딩 실패 경고에도 쓰인다는 것은 적지 않음(동작은 같은 `describe`라 일치, 문서 범위만 좁음, 아래 F6). 앞 검토가 짚은 R4 처리 기록은 `docs/qa-out-status.md:49`에 반영됨. 표시 전용 변경이라 새 결정 번호는 필요 없다고 본다 `[추론]` |

## 점검표 밖 발견 (재검토)
- **F1 해소**: 앞 판정의 수정 조건(바인딩 실패 갈래 가림 + S1·S2·S4·S5·S11·S15 시험)을 충족. 고친 방법은 앞 검토 제안 2번(경로 참조를 같은 규칙으로 가림)이며 두 파일 공통이라 F2도 함께 해소. 제안 1번(1단계 검사 강화)은 하지 않았지만 2번만으로 탐침 전 입력이 가려지므로 필요 조건은 아니라고 본다.
- **F4(권고, 이 PR 전부터, 범위 밖)**: `RuntimeConfig.java:154` 검증 메시지 `real-trading.accounts의 id가 겹칩니다: <id>`가 `describe`의 자체 검증 메시지 통과 규칙(`ConfigJson.java:45-47`)으로 경고에 그대로 실린다(R11). `id`는 `runtime-config.json`의 일반 키이고 운영 화면 계좌 목록에도 원래 보이는 공개 식별자라 비밀값 경로는 아니다. 그러나 사용자가 `id` 자리에 계좌번호를 적으면 그 값은 겹치든 말든 원래 화면·로그에 보이므로 이 경고만의 새 노출은 아니다. 근본 대책이 필요하면 `id` 형식 검증에서 계좌번호 모양(숫자 8자리 이상 등)을 거부하는 쪽이 맞고, 그것은 설정 계약 변경이라 별도 결정이 필요하다. 이 PR 판정 조건으로 넣지 않는다.
- **F5(참고, 이 PR 전부터)**: 두 파일이 모두 버려질 때 `ConfigLoader.java:118-121`은 두 경고 모두에 합친 바인딩의 첫 오류(`combinedError`)를 싣는다. S17에서 runtime-config.json 경고가 실제 원인(모르는 키 `real-trading/*`) 대신 secrets.json 쪽 경로를 보인다. 노출은 없고 결과(둘 다 버리고 default·비밀값 없이 기동 → 계좌 OFF)는 안전 쪽이다. 진단성만의 문제라 후속 정리로 권한다.
- **F6(참고, 문서)**: ADR-006 2.3 새 문장에 "secrets.json 값 바인딩 실패 경고도 같은 규칙"을 덧붙이면 문서와 코드 범위가 맞는다. 차단 아님.
- F3(앞 검토, 소문자·하이픈만으로 된 구조 밖 키는 보임)은 `maskedPath`가 같은 `isKeyLike`를 써서 runtime-config.json 경로에도 같은 성질로 넓어졌다. 숫자 없는 계좌번호·토큰은 없다고 보고 받아들인다 `[추론]`. 되돌릴 조건은 architect 원문 발견 4와 같다.
- 최악의 경우 추정: 이번 헤드 기준으로 설정 파일 거부 경고에서 비밀값 경로(계좌번호 맵, 증권사 키, 알림 토큰, 비밀번호 해시)의 키 이름·값이 나가는 갈래를 탐침 21개 입력에서 찾지 못했다. 주문·Guard에는 영향 없음. 탐침은 표본이며 Jackson이 다른 예외 종류에서 경로 원소의 `getFieldName()` 대신 다른 곳에 키를 싣는 경우(`describe`는 예외 메시지를 버리므로 경로 외에는 실리지 않음, `ConfigJson.java:39-48`)는 코드 읽기로 배제했다.

## 판정 (재검토)
승인 (A10 해소, F4~F6은 차단 아님. 실전 관련 PR이므로 머지는 사용자 확인 후, 독립 QA 관문은 별도)
