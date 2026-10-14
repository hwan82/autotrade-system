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
