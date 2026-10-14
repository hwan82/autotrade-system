# architect 검토: secrets.json 거부 경고에서 위치가 틀린 키 아래 키 이름 숨김(PR #100 QA R4)
- 대상: PR #101 / 커밋 c3c2e6b / `git diff origin/main...HEAD` 전체(코드 2개, 시험 2개, ADR-006, 5파일 +98/-3)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` -> BUILD SUCCESSFUL (130 tasks, 테스트·verifyPureDependencies·ArchUnit 포함). 테스트 결과 XML 합계 1,095건, 건너뜀 0, 실패 0, 오류 0. 새 시험 `ConfigLoaderTest#secrets의_위치가_틀린_키_아래_키_이름은_경고에_남기지_않는다`, `#secrets의_숫자가_든_키_이름은_구조에_없으면_가린다`, 바뀐 `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다` 실행·통과 확인.
  - 임시 탐침 시험(커밋하지 않고 지움)으로 `ConfigLoader.load` 경고를 직접 뽑아 봤다. 결과는 아래 "탐침".
  - PR 본문·커밋 메시지는 확인할 주장으로만 봤고 코드와 실행 결과로 대조했다. 보호 영역(autotrade-architecture-test, autotrade-risk, autotrade-broker*) diff 0줄, 추가된 `@Disabled`·`@Tag` 없음.

## 탐침 (secrets.json, 값은 모두 가짜)
| 입력 | 경고에 나온 경로 |
|---|---|
| `real-trading/account-numbers/99990000-01/x` (맵 값을 객체로) | `real-trading/account-numbers/*/x 1개` |
| `real-trading/accounts/99990000-01` (목록 자리에 객체) | `real-trading/accounts/* 1개` |
| `notification/slack/<웹훅 URL을 키로>` | `notification/slack/*/* 1개` |
| `brokers/kis/app-key/PSabc123` (비밀값 자리에 객체) | `brokers/kis/app-key/* 1개` |
| 빈 키 `""` 아래 숫자 키 | `* 1개` |
| `real-trading/accounts` 목록 원소 안 숫자 키 | `real-trading/accounts` (목록은 값 하나로 봄) |

모두 숫자·대문자·URL이 든 키 이름이 나오지 않았다. 구조 밖 키를 만나면 그 자리에서 멈추고 아래를 `/*`로 줄이므로(SecretPaths.java:142-165의 `break`), `/`가 든 키 이름도 앞 조각에서 끊긴다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 직접 실행, 성공(1,095건, 실패·건너뜀 0) |
| B2 | 통과 | 변경은 autotrade-config 안(SecretPaths.java, ConfigLoader.java:77-82)과 엔진 시험뿐. 새 모듈·의존 없음. ArchUnit·verifyPureDependencies 빌드에서 통과 |
| B3 | 통과 | 설정 키·스키마·REST·인터페이스 변경 없음. 바뀐 것은 경고 문구 형식(제어 채널 `configWarning`의 내용)이며 ADR-006 2.3에 표시 규칙 한 문장이 추가됐다(docs/adr/ADR-006-real-control-channel.md:80). 문장 내용은 코드 동작(SCHEMA·KEY_LIKE·`/*`·개수)과 일치함을 탐침으로 확인 |
| B4 | 해당 없음 | 시뮬레이션 변경 없음. `now()`·난수 추가 없음. 경고 순서는 LinkedHashMap 처음 나온 순서(SecretPaths.java:132-140), Jackson ObjectNode 삽입 순서를 따르므로 결정적 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | 새 동작에 ConfigLoaderTest 2건(QA P4 재현: 오타 부모 아래 계좌번호 키 2개 → `real-trading/acount-numbers/* 2개`, 계좌번호·비밀값 미노출; 숫자 키 가림과 오타 키 이름 노출). EngineMainTest 변경은 숫자 접미사 키 6,000개가 이제 하나로 묶여 경고가 짧아지므로 글자 접미사(aaa~)로 바꾼 것. 단언(`warning().length() > 64KB`, status·deactivate 응답)은 그대로이고 입력도 여전히 서로 다른 6,000개 경로라 시험이 약해지지 않았다. 삭제·비활성화 없음 |
| B7 | risk-ops 검토 필요 | 실전 엔진이 기동 때 읽는 설정 검사 경고(EngineMain.java:83-86, ProcessStartup 로그와 `configWarning`)를 바꾼다. 받아들임·거부 판단(`nonSecretLeaves`가 비어 있지 않으면 거부)은 그대로이고 메시지만 바뀐 것을 확인(ConfigLoader.java:78-82). Guard·주문·블랙리스트 코드는 diff에 없다. 실전 관련 PR이므로 risk-ops 검토, 사용자 머지, 독립 QA가 따로 필요 |
| B8 | 통과 | 이 PR의 목적이 비밀값 노출 축소이며 secrets.json 쪽은 탐침으로 확인. 시험 값은 `99990000-0x`·기존 `FAKE_SECRET` 자리표시자. 커밋에 비밀값 없음. 남은 다른 경로는 아래 발견 1 |

## 점검표 밖 발견
1. (중간, 이 PR 전부터 있던 것, 권고) **runtime-config.json의 모르는 키 경고에는 같은 숨김이 없다.** 탐침:
   - `{"real-trading":{"99990000-01":"x"}}` → `runtime-config.json을(를) 쓰지 않고 default로 기동: UnrecognizedPropertyException at com.autotrade.config.RuntimeConfig["real-trading"]->com.autotrade.config.RuntimeConfig$RealTrading["99990000-01"]`
   - `real-trading/accounts[0]` 안에 `"99990000-01"` 키 → 경고 끝에 `RealAccount["99990000-01"]`
   원인은 `ConfigJson.describe`가 `JsonMappingException.getPathReference()`를 그대로 붙이는 것(ConfigJson.java:38-43). R4와 같은 부류(키 자리에 계좌번호를 적으면 엔진 로그와 실전 운영 화면으로 나감)지만 R4는 secrets.json만 지목했고 이 PR 범위 밖이다. 이 PR이 만든 문제가 아니고 사용자 실수가 필요하므로 차단하지 않는다. 후속으로 `describe`의 경로도 `maskedPath`와 같은 규칙(구조에 있는 이름만, 나머지 `*`)으로 바꾸고 ADR-006 2.3 문장을 두 파일 모두에 맞추기를 권한다. 둘 다 실패할 때 `secretsWarning(paths, combinedError)`(ConfigLoader.java:112, 120, 125)도 같은 문자열을 실으므로 함께 다뤄야 한다.
2. (낮음) 구조 밖 키 중 소문자·하이픈으로만 된 이름은 그대로 보인다(KEY_LIKE, SecretPaths.java:33). 계좌번호·토큰·URL은 숫자·대문자·`:`가 들어 가려지지만, 소문자만으로 된 비밀 문자열을 키 자리에 적으면 보인다. 오타를 찾게 해 주는 이득과 바꾼 선택이며 ADR에 적혀 있다. 받아들일 만하다고 본다.
3. (낮음, 정리) `java.util.regex.Pattern`·`java.util.LinkedHashMap`을 import 없이 정규화 이름으로 쓴다(SecretPaths.java:33, 133). 기존 `forWarning`과 같은 방식이라 일관성은 있다. `schema()`와 `collect()`가 같은 레코드 순회를 두 번 한다. 동작 문제는 없다.
4. 대안 검토 확인: (a) 구조에 없는 경로는 모두 `*`로 가리기 — 오타 위치를 찾을 수 없어 운영자가 고치기 어려움, (b) 첫 구조 밖 키 이름까지만 보이고 숫자 든 이름은 가리기(채택). (b)는 QA R4가 권한 예시(`real-trading/acount-numbers/* 1개`)와 같다. 되돌릴 조건: 소문자만으로 된 비밀값이 키 자리에서 발견되면 (a)로.

## 판정
승인 (B7은 risk-ops 검토와 사용자 머지·독립 QA가 별도로 필요. 발견 1은 후속 권고이며 차단 아님. 이 승인은 architect 범위 한정)

## 재검토 2026-10-14
- 대상: PR #101 / 헤드 b7b49fe / `git diff origin/main...HEAD` 전체(main `bc197e1` 대비 9파일 +254/-5: 코드 3개, 시험 2개, ADR-006, qa-out-status, 검토 원문 2개). 앞선 검토(c3c2e6b) 뒤 커밋은 837f777(architect 원문), 42a9907(발견 1 처리), 19fda2c(risk-ops 원문), b7b49fe(risk-ops F1 시험, qa-out-status 갱신)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` -> 성공(종료 코드 0). 시험 결과 XML 합계 1,098건(앞선 검토 1,095건 + 새 시험 3건), 실패 0, 오류 0, 건너뜀 0. 새 시험 `ConfigLoaderTest#runtime_config의_모르는_키_이름도_숫자가_들면_가린다`(ConfigLoaderTest.java:327), `#secrets의_값_바인딩이_실패해도_키_자리의_계좌번호는_경고에_남기지_않는다`(:341), `#runtime_config의_모르는_키가_키_이름_모양이면_고칠_곳을_보여_준다`(:359) 실행·통과 확인.
  - 임시 탐침 시험(커밋하지 않고 지움, `git status` 깨끗)으로 `ConfigLoader.load` 경고를 직접 뽑았다. 결과는 아래 표.
  - 변이 탐침(`maskedPath`를 `getPathReference()`로 되돌려 새 시험이 실패하는지)은 실행 환경의 권한 거부로 돌리지 못했고 소스는 즉시 원복했다. 새 시험의 단언(`doesNotContain("99990000")`, `doesNotContain("java.util")`)이 risk-ops 원문의 수정 전 출력(`LinkedHashMap["99990000-01"]`)과 맞부딪히므로 잡을 것이라고 본다 `[추론]`. 점검표 항목 판정에는 쓰지 않았다.
  - 보호 영역(autotrade-architecture-test, autotrade-risk, autotrade-broker*) diff 0줄, 추가된 `@Disabled`·`@Tag`·`Assumptions` 없음. 커밋 메시지·PR 본문은 확인할 주장으로만 봤다.

### 바뀐 코드
- `ConfigJson.describe`가 `JsonMappingException.getPathReference()`(클래스 이름과 원래 키 이름) 대신 `maskedPath`를 쓴다(ConfigJson.java:42, 55-64). 필드 이름이 `SecretPaths.isKeyLike`(`[a-z][a-z-]*`, SecretPaths.java:33, 43-45)에 맞으면 그대로, 아니면 `*`, 배열 원소는 번호. `describe`는 runtime-config.json 1단계 거부(ConfigLoader.java:71, 181), secrets.json 1단계 거부(:85), 값 바인딩 실패(`bind`, :200 → `combinedError` :98 → :104, :112, :118, :120, :125) 모든 갈래가 지나는 한 곳이라, 앞선 발견 1과 risk-ops F1·F2가 한 번에 막힌다.
- 설정 구조의 실제 키 이름은 모두 소문자·하이픈 kebab-case여서(RuntimeConfig 레코드 구성 요소에 숫자 든 이름 없음, `@JsonProperty` 없음) 정상 키가 `*`로 바뀌어 진단성을 잃는 일은 없다.

### 탐침 (값은 모두 가짜)
| 파일 | 입력 | 경고에 나온 경로 |
|---|---|---|
| runtime | `real-trading/99990000-01` | `UnrecognizedPropertyException at real-trading/*` |
| runtime | `accounts[0]` 안 `99990000-01` | `... at real-trading/accounts/0/*` |
| runtime | 최상위 `99990000-01` | `... at *` |
| runtime | `real-trading/a/99990000-01`, 빈 키 `""` | `... at real-trading/*` |
| runtime | `accounts[0].broker = "99990000"`(enum 값 오류) | `InvalidFormatException at real-trading/accounts/0/broker` (값 안 나감) |
| secrets | S1 `account-numbers/99990000-01: ["x"]`, S2 `{}` | `MismatchedInputException at real-trading/account-numbers/*` |
| secrets | `account-numbers/kis-main: ["99990000-01"]` | `... at real-trading/account-numbers/kis-main` (별칭만, 값 안 나감) |
| secrets | S4 `real-trading/99990000-01: {}`, S5 최상위, S15 `brokers/kis/99990000-01: {}` | `real-trading/*`, `*`, `brokers/kis/*` |
| secrets | `brokers/kis/app-key: ["PS...123"]`, `app-key: {"PS...123": {}}` | `... at brokers/kis/app-key` (값·키 안 나감) |
| secrets | `brokers/PS...123: {}` | `... at brokers/*` |
| 둘 다 | 같은 `real-trading/99990000-01: {}` | 두 경고 모두 `real-trading/*` |

risk-ops가 지목한 S1·S2·S4·S5·S11·S15와 R1이 모두 가려졌고, 클래스 이름(`com.autotrade`, `java.util`)도 나가지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 직접 실행, 성공(1,098건, 실패·오류·건너뜀 0) |
| B2 | 통과 | 바뀐 코드는 autotrade-config 안(ConfigJson.java, SecretPaths.java, ConfigLoader.java)과 엔진 시험뿐. 같은 패키지 내부 `isKeyLike`·`ANY_KEY` 사용이고 새 모듈·의존 없음. ArchUnit·verifyPureDependencies 빌드에서 통과 |
| B3 | 통과 | 설정 키·스키마·REST·인터페이스 변경 없음. 경고 문구 형식(`configWarning` 내용)이 바뀌었고 ADR-006 2.3에 runtime-config.json 경로 표기 규칙 문장이 추가됐다(docs/adr/ADR-006-real-control-channel.md:80). 문장(`real-trading/accounts/0/id` 꼴, 키 이름 모양 아니면 `*`)이 탐침 결과와 일치. 다만 같은 문단의 "값 없음"은 아래 발견 R1과 어긋난다(이 PR 전부터) |
| B4 | 해당 없음 | 시뮬레이션 변경 없음. `now()`·난수 추가 없음. `maskedPath`는 Jackson 경로 목록 순서 그대로라 결정적 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | 새 동작에 시험 3건: runtime 모르는 숫자 키 가림(:327, `doesNotContain("99990000")`·`doesNotContain("com.autotrade")`), secrets 바인딩 실패 6갈래(:341, risk-ops S1·S2·S11·S4·S5·S15 그대로), 키 이름 모양 오타는 경로 그대로 보임(:359, 진단성 회귀 방지). 기존 시험 삭제·약화 없음. 앞선 검토의 EngineMainTest 판단 유지 |
| B7 | risk-ops 검토 필요 | 실전 엔진이 기동 때 읽는 설정 경고(EngineMain → ProcessStartup → `configWarning`)를 바꾼다. 받아들임·거부 판단은 그대로이고 메시지만 바뀜(ConfigLoader.java:79-86, 95-127 분기 변경 없음). risk-ops 원문의 A10 "수정 필요"에 대한 재검토, 사용자 머지, 독립 QA가 따로 필요 |
| B8 | 통과 | 이 PR의 목적(계좌번호 모양 키 이름 비노출)을 탐침으로 확인. 시험 값은 `99990000-0x` 자리표시자. 커밋에 비밀값 없음. 남은 값 노출 경로는 아래 R1(이 PR 전부터, 비밀값 경로 아님) |

### 점검표 밖 발견
1. 앞선 발견 1: 해소(42a9907). risk-ops F1·F2도 같은 수정으로 해소됨을 탐침과 시험 :341로 확인.
2. R1 (낮음, 이 PR 전부터, 권고) **설정 레코드 자체 검증 메시지 두 개는 값을 싣는다.** `describe`가 "키 이름과 범위만 담으므로 그대로 보여 준다"(ConfigJson.java:44-47)며 붙이는 `IllegalArgumentException` 메시지 중 `real-trading.accounts의 id가 겹칩니다: <id>`(RuntimeConfig.java:154)와 `lab.max-parallel-runs는 1~64여야 합니다: <값>`(RuntimeConfig.java:58)은 값을 넣는다. 탐침: 두 계좌 `id`를 `99990000-01`로 겹치면 경고에 `... id가 겹칩니다: 99990000-01`. `id`는 runtime-config.json의 공개 별칭(비밀값 경로 아님)이라 정상 사용에서는 문제가 아니지만, id 자리에 계좌번호를 적는 실수는 R2·R4와 같은 부류이고 ADR-006 2.3의 "값 없음"과 주석이 사실과 다르다. 후속으로 두 메시지에서 값을 빼거나(겹친 개수만), ADR 문장을 고치기를 권한다. 차단하지 않는다.
3. 앞선 발견 2(소문자·하이픈만으로 된 구조 밖 키는 보임)는 이제 runtime-config.json 경로에도 같은 규칙으로 적용된다. 규칙이 두 파일에서 하나로 맞춰졌으므로 일관성은 나아졌다. 판단 유지.
4. (정리) `maskedPath`의 `"?"` 갈래(필드 이름도 번호도 없는 Reference)는 시험이 없지만 값이 실리지 않는 안전한 기본값이다.

### 판정
승인 (B7은 risk-ops 재검토와 사용자 머지·독립 QA가 별도로 필요. R1은 이 PR 전부터 있던 후속 권고이며 차단 아님. 이 승인은 architect 범위 한정)
