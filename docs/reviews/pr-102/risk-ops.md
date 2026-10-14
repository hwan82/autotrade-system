# risk-ops 검토: 설정 경고 가림 보강(PR #101 QA Q1·R1·R2)
- 대상: PR #102 / 커밋 `ec4b030` (브랜치 `claude/project-thread-gayfxk`) / main(`5dbaead`) 대비 diff 4개 파일(+61 −12)
- 점검표: A
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 확인. 바뀐 실행 코드는 `RuntimeConfig.java:57-60`(`Lab` 범위 검증 메시지에서 값 뺌), `RuntimeConfig.java:152-169`(겹친 계좌 id 검사를 `HashSet` → 위치 맵으로, 메시지에서 id 값 대신 위치), `SecretPaths.java:160-169`(`maskedPath`의 구조 밖 키 갈래에서 부모가 구조의 잎이면 모양과 상관없이 `*`)뿐. 나머지는 시험 3건(`ConfigLoaderTest.java:370-409`)과 ADR-006 2.3 한 문단(`docs/adr/ADR-006-real-control-channel.md:80`). 보호 영역(`autotrade-risk`, `autotrade-architecture-test`, `autotrade-app-engine`, `autotrade-broker*`) diff 0줄, 추가된 `@Disabled`·`@Tag` 0건
  - 영향 받는 실전 경로 확인: `RealTrading` 생성자 결과(`accounts`, `accountNumbers`)는 `EngineMain.java:94-103` `startGuard`가 `RealTradingGuard` 계좌 목록과 "계좌번호 없음" 판단(`isConfigured`)에 쓴다. 경고 문자열은 `ProcessStartup.java:20-21`(엔진 로그)과 엔진 상태 `configWarning`(ADR-006 2.3)으로 나간다. `forNonSecretWarning` 호출처는 `ConfigLoader.java:81` 한 곳(경고 문자열만, 거부 판단은 `:78-80`의 `nonSecret` 비어 있음 여부로 그대로)
  - `./gradlew build --no-daemon` 성공(BUILD SUCCESSFUL, 130 작업, exit 0). 시험 결과 XML 집계 1,101건 실패 0 오류 0 건너뜀 0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 32건, `:autotrade-app-engine:test` 50건, `:autotrade-config:test` 39건 통과. 새 시험 `ConfigLoaderTest#secrets의_계좌번호_맵_키_자리는_아래에_키가_있어도_가린다`, `#secrets의_값_자리_아래_키는_모양과_상관없이_가린다`, `#겹친_계좌_id는_값_없이_위치만_알린다` 통과. 기존 `#실전_계좌_별칭이_겹치면_거부한다`(거부 결과 `DEFAULT_FILE_INVALID`) 통과
  - 변이 탐침(하나씩 넣고 `:autotrade-config:test` 실행 뒤 `git checkout -- autotrade-config`로 원복, 마지막 `git status` 깨끗):
    - M1 `SecretPaths.java:164` `underRecord = true`(R1 되돌림) → 39건 중 2건 FAILED(`#secrets의_값_자리_아래_키는_모양과_상관없이_가린다`, `#secrets의_계좌번호_맵_키_자리는_아래에_키가_있어도_가린다`), 잡힘
    - M2 `SecretPaths.java:158` 맵 키 자리 갈래에서 `*` 대신 원래 키(PR #101 QA 변이 M2와 같음) → 1건 FAILED(`#secrets의_계좌번호_맵_키_자리는_아래에_키가_있어도_가린다`), 잡힘. PR #101 QA Q1 공백 해소
    - M3 `RuntimeConfig.java:158` 메시지에 `accounts.get(i).id()`를 다시 붙임 → 1건 FAILED(`#겹친_계좌_id는_값_없이_위치만_알린다`), 잡힘
    - M4 `RuntimeConfig.java:59` 메시지에 `maxParallelRuns` 값을 다시 붙임 → **전부 통과(살아남음)**. 아래 F7
  - 적대 입력 탐침(임시 시험 파일 `ZzProbe102Test`, 실행 뒤 삭제, 커밋 안 함). `ConfigLoader.load` 결과(계좌 수, 계좌번호 수, 비밀번호 해시 유무)와 경고 원문:
    - P1 secrets `real-trading/operator-password-hash/{"hunterpass":"x"}` → 0/0/없음, `[real-trading/operator-password-hash/* 1개]` (가려짐, PR #101 QA P1 해소)
    - P2 secrets `brokers/kis/app-key/{"abcdefsecretword":"x"}` → `[brokers/kis/app-key/* 1개]` (가려짐, QA P1b 해소)
    - P3 runtime `id` 두 개 `99990000-01` → 0/0/없음, `ValueInstantiationException at real-trading: real-trading.accounts의 id가 겹칩니다(0번과 1번, 0부터)` (값 안 나감, QA P3 해소. runtime-config.json을 버리고 default로 기동 → 실전 계좌 0개)
    - P4 `account-numbers/99990000-01/{"x":"y"}` → `[real-trading/account-numbers/*/* 1개]`, P5 `account-numbers/kis-main/{"abc":"y"}` → 같은 꼴 (가려짐)
    - P6 오타 부모 `acount-numbers/99990000-01` → `[real-trading/acount-numbers/* 1개]`, P7 최상위 오타 `brokrs` → `[brokrs/* 1개]`, P8 레코드 안 오타 `brokers/kis/app-kye` → `[brokers/kis/app-kye]` (오타 위치 힌트 유지, 진단성 회귀 없음)
    - P9 목록 잎 아래 `real-trading/accounts/{"abc-def":..}`, P10 enum 잎 아래 `market-data/mode/{"abc-def":..}`, P16 `notification/kakao/mode/{"99990000":..}` → 모두 `.../* 1개` (가려짐)
    - P11 비밀값 잎 아래 두 단계 `notification/slack/webhook-url/abc/def` → `[notification/slack/webhook-url/*/* 1개]` (가려짐)
    - P12 runtime `lab/max-parallel-runs: 99990000` → `ValueInstantiationException at lab: lab.max-parallel-runs는 1~64여야 합니다` (값 안 나감)
    - P13 정상 설정(계좌 1, 계좌번호 1, 해시) → 1/1/있음, 경고 없음 (계좌번호 연결 동작 그대로)
    - P14 계좌 `A, B, A` + secrets 계좌번호 `A` → runtime 버림 → 0/0/없음, `...겹칩니다(0번과 2번, 0부터); ...없거나 값이 빈 항목 1개를 무시했습니다` (첫 위치를 정확히 보임, 결과는 안전 쪽)
    - P15 runtime `operator-password-hash/{"hunterpass":"x"}` → `MismatchedInputException at real-trading/operator-password-hash` (키 이름 안 나감)
    - P17 runtime `lab/default-seed-capital-krw: 0` → 값 없는 메시지

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·계좌 활성 상태를 만드는 코드 변경 없음. `RealTrading` 생성자 변경(`RuntimeConfig.java:152-169`)은 겹침 판단(`putIfAbsent`가 null 아님 ⇔ 앞의 `HashSet.add`가 false)과 계좌번호 걸러내기(`firstIndex.containsKey` ⇔ `ids.contains`)가 같은 의미이고, 겹치면 여전히 예외 → runtime-config.json을 버리고 default(실전 계좌 0개)로 기동(P3·P14, `#실전_계좌_별칭이_겹치면_거부한다`). `EngineMain.java:94-103`은 이 목록으로 모든 계좌 OFF인 Guard를 만들고, 설정에 활성 키가 없다(`RuntimeConfig.java:139-140`). `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 diff 0줄. `:autotrade-architecture-test:test` 32건 통과 |
| A3 | 통과 | 증권사 선택·주문 라우팅 코드 diff 0줄. `RealAccount.broker` 바인딩 변경 없음(`RuntimeConfig.java:193-203`). 대체 경로 추가 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직을 건드리지 않음 |
| A5 | 해당 없음 | 멱등 키 생성·저장을 건드리지 않음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터를 건드리지 않음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치를 건드리지 않음. 계좌 별칭 유일성은 그대로 지켜져 계좌별 상태가 섞이지 않음(A1) |
| A8 | 통과 | 엔진 기동 경로(`EngineMain.java:83` `ProcessStartup.start` → `ConfigLoader.load`)에 더해진 일은 경고를 만들 때 구조 밖 키마다 `SCHEMA`(수십 개 경로) 한 번 훑기(`SecretPaths.java:164`)와 계좌 수만큼의 맵 삽입뿐. 실험실·게이트웨이 의존 없음. `EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다` 통과(경로당 한 번이고 `maskedPath`는 첫 구조 밖 키에서 멈춤 `break`) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절을 건드리지 않음 |
| A10 | 통과 | PR #101 QA가 남긴 노출 갈래 P1·P1b(R1)와 P3(R2)가 이번 헤드에서 가려짐(위 탐침 P1·P2·P3). 맵 키 자리 갈래도 시험으로 고정됨(M2 잡힘). 새 시험 3건이 `99990000`·`abc-def`·`ghi`·`jkl`이 **없어야** 함을 단언하고 변이 M1·M2·M3에서 실패. `RuntimeConfig`의 남은 검증 메시지(`:43, 59, 62, 78, 88, 158, 201`)를 모두 읽어 값이 실리는 것이 없음을 확인. 시험 값은 `99990000-01` 자리표시자(diff 직접 확인, gitleaks는 CI에서 돎). 탐침 17개 입력 어디서도 비밀값 키 이름·값이 경고에 나가지 않음 |
| A11 | 통과 | 보호 시험 삭제·비활성화·약화 0건(기존 `#실전_계좌_별칭이_겹치면_거부한다`의 `contains("id가 겹칩니다")`는 새 메시지에도 맞고 거부 결과 단언 그대로). 새 동작 시험 3건(`ConfigLoaderTest.java:370, 382, 398`)이 변이 M1·M2·M3을 잡음. 다만 `Lab` 메시지에서 값을 뺀 변경은 지키는 시험이 없음(M4 살아남음, F7, 권고) |
| A12 | 통과 | 장애 대응표 #1~#17, O1~O8, 장애 테스트 8종의 감지·대응 동작을 바꾸지 않음. 설정 파일을 버리는 조건과 결과(default·비밀값 없이 기동, 계좌 OFF)는 그대로(P3·P12·P14). 표시 문자열만 바뀜 |
| A13 | 해당 없음 | 시장별 경로·시간·금액 계산 변경 없음 |
| A14 | 통과 | ADR-006 2.3(`docs/adr/ADR-006-real-control-channel.md:80`)에 "구조의 잎 아래 키는 모양과 상관없이 `*`"(R1), "secrets.json 값 바인딩 실패 경고도 같은 경로 규칙"(앞 검토 F6), "설정 레코드 검증 메시지는 값을 싣지 않음, 겹친 id는 위치만"(R2)이 적혔고 코드·탐침과 일치. 표시 전용 변경이라 새 결정 번호는 필요 없다고 본다 `[추론]`. 참고: `docs/qa-out-status.md:50`은 Q1·R1·R2를 아직 "사용자 결정 대기"로 적고 있어 이 PR로 처리했다는 기록은 메인이 따로 남겨야 한다(이 PR diff에 없음) |

## 점검표 밖 발견
- **F7(권고, 차단 아님)**: `RuntimeConfig.java:59`에서 `maxParallelRuns` 값을 뺀 변경을 지키는 시험이 없다(M4: 값을 다시 붙여도 39건 모두 통과). 기존 `ConfigLoaderTest.java:145-149`는 `contains("lab.max-parallel-runs")`만 본다. 값은 `int`이고 runtime-config.json의 일반 키라 비밀값 노출 위험은 낮지만, ADR-006 2.3에 새로 적은 "검증 메시지도 값을 싣지 않는다"를 지키려면 범위 밖 값(예: `99990000`)이 경고에 없어야 한다는 단언 한 줄이면 된다.
- **F8(참고, 이 PR 범위 밖)**: `RealTradingGuard.java:69`의 `"실전 계좌 별칭이 겹칩니다: " + ref.id()`는 id 값을 싣는다. 설정 경로에서는 `RuntimeConfig.RealTrading`이 먼저 겹침을 거부해 이 줄에 닿지 않으므로(`EngineMain.java:97`은 이미 걸러진 목록을 넘김) 지금 노출 경로는 아니다. `id`는 화면에 원래 보이는 공개 식별자이기도 하다. 보호 영역 파일이라 이 PR에서 바꾸지 않은 것이 맞다.
- **F9(참고)**: R1 판단(`SecretPaths.java:164`)은 "부모 경로로 시작하는 구조 경로가 있는가"이므로, 레코드가 아닌 맵 값(`Map<String, Secret>`의 `*`)·목록·enum·숫자·비밀값 잎이 모두 "잎"으로 묶인다(P4·P9·P10·P16). 앞으로 설정 구조에 `Map<String, 레코드>`를 넣으면 `schema()`(`:229-231`)가 그 아래 경로를 만들어 레코드처럼 다뤄지므로 규칙이 자동으로 따라간다. 문제 아님.
- 앞 검토 F3(소문자·하이픈만으로 된 구조 밖 키 이름은 레코드 바로 아래일 때 보임, P7·P8)은 그대로이며 진단성을 위해 받아들인다 `[추론]`. 이번 변경으로 그 범위는 레코드 바로 아래로 좁아졌다(잎 아래는 이제 가림).
- 최악의 경우 추정: 이 PR이 틀렸다면 영향은 경고 문자열에 키 이름이 다시 나가는 것이고, 주문·Guard·계좌 활성에는 영향이 없다(A1 동치 확인, P13 정상 연결). 탐침은 표본이며 Jackson 바인딩 실패 경고 갈래(`ConfigJson.describe`)는 이 PR이 바꾸지 않았고 PR #101 재검토에서 확인한 성질 그대로다.

## 판정
승인 (F7은 권고, F8·F9는 참고. 실전 관련 PR이므로 머지는 사용자 확인 후, 독립 QA 관문은 별도)
