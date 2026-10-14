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
