# architect 검토: 설정 경고 가림 보강(PR #101 QA Q1·R1·R2)
- 대상: PR #102 / 커밋 ec4b030 / `git diff origin/main...HEAD` 전체(origin/main 5dbaead 기준, 커밋 1개, 4파일 +61/-12: RuntimeConfig.java, SecretPaths.java, ConfigLoaderTest.java, ADR-006)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 ec4b030) -> BUILD SUCCESSFUL in 4m 5s, 130 actionable tasks 실행(테스트, verifyPureDependencies, ArchUnit 포함). 테스트 결과 XML 합계 1,101건, 실패 0, 오류 0, 건너뜀 0. 새 시험 3건 `ConfigLoaderTest#secrets의_계좌번호_맵_키_자리는_아래에_키가_있어도_가린다`, `#secrets의_값_자리_아래_키는_모양과_상관없이_가린다`, `#겹친_계좌_id는_값_없이_위치만_알린다`가 결과 XML에 있고 통과.
  - 변이 시험(스크래치 스크립트로 소스를 잠시 바꾸고 `:autotrade-config:test --tests *ConfigLoaderTest` 실행 후 원복, 작업 트리 깨끗함 확인):
    | 변이 | 결과 |
    |---|---|
    | M2: `SecretPaths.maskedPath` 맵 키 자리 갈래에서 `*` 대신 원래 키(QA Q1이 살아남는다고 한 변이) | 잡힘(BUILD FAILED) |
    | R1 되돌림: `underRecord`를 항상 true | 잡힘 |
    | R2 되돌림: 겹친 id 메시지에 `accounts.get(i).id()` 덧붙임 | 잡힘 |
    | `lab.max-parallel-runs` 메시지에 값 덧붙임 | **살아남음**(아래 발견 2) |
  - PR 본문·커밋 메시지는 확인할 주장으로만 봤고 코드와 실행 결과로 대조했다. 보호 영역(autotrade-architecture-test, autotrade-risk, autotrade-broker-api, autotrade-broker-kis, autotrade-app-engine) diff 0줄. 바뀐 파일에 `@Disabled`·`assume` 추가 없음.

## 코드 확인
- R1(SecretPaths.java:160-169): 구조에 없는 키를 만나면, 바로 위 경로(`schemaPrefix`)가 비어 있거나(최상위) SCHEMA 안에 그 경로 아래 자식이 있을 때(=레코드)만 키 이름 모양을 보이고, 그 밖(구조의 잎: `Secret`·숫자·불리언·목록, 그리고 `Map<String, Secret>`의 `*` 자리)은 `*`로 가린다. `startsWith(parent)`의 `parent`는 `/`로 끝나므로 `real-trading/accounts`와 `real-trading/accounts-x` 같은 접두 충돌이 없다. 목록(`real-trading/accounts`) 아래 객체 키도 이제 모양과 상관없이 `*`가 되는데, 목록 자리의 객체 키는 오타 힌트로 쓸 일이 없어 손실이 없다. 레코드 안 오타(`real-trading/accounts/0/nmae` 등 기존 시험)는 그대로 보인다.
- Q1(ConfigLoaderTest.java:370): 입력 `account-numbers/99990000-01/x`는 `nonSecretLeaves`에서 비밀값 패턴(길이 3)과 길이가 달라 비밀값 아닌 키로 잡히고 맵 키 자리 갈래(`SCHEMA.contains(any)`)를 지난다. 기대값 `real-trading/account-numbers/*/* 1개`는 위 코드 추적과 일치하고, M2 변이로 실제로 이 갈래를 지키는 것을 확인했다.
- R2(RuntimeConfig.java:152-160): 첫 위치를 `HashMap<String,Integer>`로 기억해 "(i번과 j번, 0부터)"만 싣는다. 별칭 필터(RuntimeConfig.java:167)는 `firstIndex.containsKey`로 이전 `Set` 의미와 같다. 이 메시지는 `ConfigJson.describe`(ConfigJson.java:45)의 `isOwnValidation`을 거쳐 경고에 그대로 실리므로, 값이 빠진 것이 곧 경고·엔진 로그·운영 화면에서 빠진 것이다. `RuntimeConfig` 안 다른 `IllegalArgumentException` 메시지에도 값 연결(`+`)은 남아 있지 않음(grep으로 확인).
- ADR-006 2.3(docs/adr/ADR-006-real-control-channel.md:80): 추가된 세 문장(잎 아래 키 `*`, secrets.json 값 바인딩 실패 경고도 같은 경로 규칙, 레코드 검증 메시지 값 없음)이 코드 동작과 맞는다. PR #101 risk-ops F6 지적과 일치.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --no-daemon` 직접 실행, BUILD SUCCESSFUL, 1,101건 실패·오류·건너뜀 0 |
| B2 | 통과 | 변경은 autotrade-config 안(SecretPaths.java, RuntimeConfig.java)과 그 시험, ADR 문서뿐. 새 의존·새 모듈 없음(import는 `HashMap` 추가, `Set` 제거). ArchUnit·verifyPureDependencies 빌드에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 제어 채널 `configWarning`의 문구 규칙이 바뀌었고 ADR-006 2.3이 같은 커밋에서 갱신됨(docs/adr/ADR-006-real-control-channel.md:80). 설정 키 받아들임·거부 판단은 그대로(메시지만 바뀜), config-keys.md 갱신 대상 아님 |
| B4 | 해당 없음 | 시뮬레이션 변경 없음. `now()`·난수 추가 없음. `HashMap`은 조회에만 쓰고 순회하지 않으며(RuntimeConfig.java:152-167), 메시지의 위치는 목록 순서로 정해져 결정적 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | Q1·R1·R2 각각 새 시험 1건(ConfigLoaderTest.java:370, 382, 398), 변이 3개를 잡음. 지우거나 건너뛴 시험 없음. 다만 `lab.max-parallel-runs` 메시지 변경에는 시험 없음(발견 2, 차단 아님) |
| B7 | risk-ops 검토 필요 | 실전 엔진이 기동 때 남기는 설정 경고(엔진 로그, 운영 화면 `configWarning`)의 내용을 바꾼다. Guard·주문·블랙리스트 코드 diff 0줄이고 설정 거부 판단은 그대로임을 확인. PR #101과 같은 부류로 실전 관련 PR이므로 risk-ops 검토, 사용자 머지, 독립 QA가 따로 필요 |
| B8 | 통과 | 이 PR은 노출 축소다. 시험 값은 `99990000-01`, `abc-def` 같은 자리표시자. 커밋에 비밀값 없음 |

## 점검표 밖 발견
1. (낮음, 이 PR 범위 밖) `RealTradingGuard.java:69`의 `"실전 계좌 별칭이 겹칩니다: " + ref.id()`는 여전히 값을 싣는다. 설정 경로에서는 `RealTrading` 생성자가 먼저 겹친 id를 거부하므로 정상 기동 경로로는 닿지 않는다 `[추론]`(근거: RuntimeConfig.java:152-160이 바인딩 단계에서 예외). 보호 영역이라 이 PR에서 고치지 않은 것이 맞고, 같은 규칙으로 맞출지는 risk-ops 판단 사항으로 남긴다.
2. (낮음) `lab.max-parallel-runs` 메시지에서 값을 뺀 변경(RuntimeConfig.java:59)을 지키는 시험이 없다(변이 살아남음). 이 필드는 `int`라 문자열 비밀값이 들어올 수 없어 노출 위험은 사실상 없다 `[추론]`. 원하면 범위 밖 값으로 경고에 숫자가 없는지 보는 시험 한 줄을 더하면 된다.
3. (참고) 대안 확인: R1에 대해 (a) 잎 이름 목록을 따로 두고 비교, (b) SCHEMA에 자식이 있는지로 레코드 여부를 판정(채택). (b)는 `RuntimeConfig`에서 SCHEMA를 생성하므로 새 키가 생겨도 따로 고칠 목록이 없다. 비용은 구조 밖 키 하나마다 SCHEMA 전체 훑기(수십 개)라 무시할 만하다. 되돌릴 조건: 맵 값이 레코드인 `Map<String, Record>` 필드가 생기면 `schema()`가 `*/` 아래 자식을 만들므로 그 경우 맵 값 레코드 안 오타가 보이게 되는데, 이는 의도에 맞다.
4. (참고, 절차) 이 브랜치에 `docs/qa/pending.md` 독립 QA 요청 줄이 아직 없다. 실전 관련 PR이므로 프로젝트 지침 11장대로 메인이 추가해야 한다. 코드 판정과는 별개.

## 판정
승인
