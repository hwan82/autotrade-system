# 독립 QA 검토: PR #112 SecretPaths 지문 고정과 모양 검사 보강(PR #110 QA 권고 후속, D-086)
- 대상: PR #112 / 헤드 380c612 (merge-base fa1996b) / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 7개 +300 −15: `RealPathReflectionTest.java`, 테스트 소스 `com.autotrade.config.SamePackageRecordReader`, `ADR-002`, `D-086`, `docs/qa/pending.md`, 개발 쪽 검토 원문 2개). 운영 코드 변경 0줄
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 테스트 `RealPathReflectionTest` 변경)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 380c612): Maven Central 429로 4회 실패 뒤 5회째 `BUILD SUCCESSFUL in 2m 41s`. 결과 XML 합계 1,142건, 실패·오류·건너뜀 0. `RealPathReflectionTest` 9건 통과(main 8건 → 9건)
  - 병합 확인: origin/main은 d6fb6e7(PR #111 머지)로 앞서 있다. `git merge-tree --write-tree HEAD origin/main` 충돌 없음. PR #111이 바꾼 파일 9개와 이 PR의 파일은 겹치지 않는다(`git diff --stat fa1996b origin/main`)
  - CI(헤드 380c612): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - 탐침 P1~P4와 변이 N3(각각 `./gradlew --offline --no-daemon :autotrade-architecture-test:test --tests 'com.autotrade.architecture.RealPathReflectionTest'`, P4는 `:autotrade-config:test`도). 실험 뒤 모두 되돌리고 `git status` 깨끗함 확인
  - 개발 쪽 원문(architect 1차·재검토, risk-ops)의 변이 결과(FM1~FM7, MM1~MM6, K1~K6 등)는 사실로 받지 않았다. 판정은 아래 직접 실행 결과와 코드로만 냈다

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| P1 | 운영 `SecretPaths`에 빈 private 중첩 클래스 `Holder` 추가 | 잡힘. 모양 시험("SecretPaths가 중첩 클래스 com.autotrade.config.SecretPaths$Holder를 둔다", "고정 목록 밖 타입 ...$Holder를 쓴다")과 지문 시험 둘 다 FAILED. 운영 범위(`ArchitectureTest.PRODUCTION`)에서도 중첩 클래스 검사가 동작한다 |
| P2 | 운영 `SecretPaths.isKeyLike(String name)`의 매개변수 이름만 `keyName`으로(빌드는 `-parameters`, build-logic/src/main/kotlin/autotrade.java-conventions.gradle.kts:20) | 통과(지문 불변). `SKIP_DEBUG`가 `MethodParameters` 속성도 뺀다. 동작이 같은 변경이라 문제는 아니다 |
| P3 | 운영 `SecretPaths.presentIn`의 지역 변수 `parts` → `segs` | 통과(지문 불변, Javadoc 설명과 일치) |
| P4 | 운영 `SecretPaths.nonSecretLeaves`(공개 반환 `List<String>`)에 private 도우미 `first()`(`RecordComponent` 반환)로 얻은 구성 요소 타입 클래스 객체를 원시 타입 형변환으로 넣음(`@SuppressWarnings` 소스 보존 주석) | 1단계: 지문 시험**만** FAILED(나머지 8건 통과, 실제 값 `b04a9135…0695`). 2단계: 시험의 `RECORD_READER_FINGERPRINTS` 값 한 줄만 그 값으로 바꾸면 `RealPathReflectionTest` 9건 **모두 통과**. 이 조잡한 판은 `:autotrade-config:test`의 `ConfigLoaderTest` 등 10건이 `ClassCastException` 성격으로 실패했지만, 이는 내가 문자열 목록에 섞어 넣었기 때문이고 기능 시험이 누출을 막는 장치는 아니다. 아래 F1 |
| N3 | `hasWildcardOrTypeVariable`의 `JavaGenericArrayType` 갈래를 항상 거짓으로 | **살아남음**(9건 통과). `List<?>[]` 같은 위반 예제가 없다. 아래 R2 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄. RealTradingGuard 관련 시험은 전체 빌드 1,142건에 포함돼 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. `RealPathReflectionTest` 변경은 강화다: 리플렉션 타입 메서드를 허용 클래스 안으로(RealPathReflectionTest.java:407-408, :470-474), 서명 와일드카드·타입 변수 금지(:810-813, :826-838), 중첩 클래스 금지와 중첩 클래스 타입 검사(:782-794), 지문 고정(:710-750). 삭제 줄은 기존 타입 목록 검사 4줄(중첩 클래스까지 넓혀 옮김), 메서드 서명 1줄, 단언 1줄 교체뿐. P1이 운영 범위에서 물린다 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음. 위반 예제(중첩 `Sink` 포함)는 테스트 소스라 런타임 구성 시험 `실전_경로_런타임_구성은_고정_목록과_같다` 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음. 지문 값은 클래스 파일 해시. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 0건(1,141 → 1,142). 새 검사마다 위반 예제와 기대 문자열이 있다(`lastType`·`leak()`·`drain`·`Sink`·`typeArgument`·`plainTypeName`, :680-701, :983-988). N3 생존과 세 타입(`WildcardType`·`TypeVariable`·`GenericArrayType`) 예제 없음은 권고(R2) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 문서와 시험 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | **실패** | 아래 F1. D-086:6·:51, PR 본문, 시험 단언 메시지(:79)는 "`SecretPaths`를 바꾸면 지문을 고쳐야 하고 그 PR은 보호 테스트 변경, 곧 실전 관련 PR이 된다"고 쓰지만, 실전 관련 PR을 정하는 규칙 문서는 이것을 담지 않는다. 그 밖의 문서 대조는 맞다: D-086:58·ADR-002:242 "9건" = 헤드 `@Test` 9개, D-086:51 지문 설명 = P2·P3·P4 결과, `pending.md:31` 요청 줄 있음 |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공, 1,142건 |
| B6 | 통과 | 새 동작마다 실패하는 위반 예제와 단언이 있다. 운영 `SecretPaths`에 대해 위반 0건과 지문 일치를 단언한다(:677-679, :714-720). 건너뛰거나 지운 시험 없음 |

## 발견
- **F1 (수정 필요, A14) 지문의 보호 효과는 "지문 값 갱신 = 실전 관련 PR"이라는 절차에만 기대는데, 그 절차를 정하는 규칙 문서에 이 시험과 이 변경 종류가 없다.**
  - [사실] P4: 운영 `SecretPaths`가 공개 메서드 반환 목록에 구성 요소 타입 클래스 객체를 섞는 변경은 지문 시험 하나로만 잡히고, 시험 파일의 지문 값 한 줄(:72)을 바꾸면 `RealPathReflectionTest` 9건이 모두 통과한다. PR #110 QA E2E가 보인 대로 `SecretPaths`에서 엔진으로 클래스 객체가 나가면 Jackson이 `GuardTransition` 생성까지 간다. 그래서 이 PR 뒤로 그 길을 막는 것은 "지문 값을 고친 PR을 사람이 실전 관련 PR로 분류하는 것"이다.
  - [사실] 실전 관련 PR 분류 규칙은 보호 테스트를 이름으로 정한다: `docs/team/project-instructions.md:114-116`("`ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트, 장애 테스트를 지우거나 약화하는 변경"), `docs/qa/README.md:12-15`(같은 목록, "지우거나 약화하는 변경"), `docs/qa/checklists.md:10`(A 대상 "보호 테스트"). `RealPathReflectionTest`는 어느 목록에도 없고(`grep -rn RealPathReflectionTest docs .claude CLAUDE.md`는 D-086·ADR-002·기획서만 나옴), 지문 **값 갱신**은 겉보기에 "삭제·약화"가 아니다. CODEOWNERS나 CI의 경로 검사처럼 이 파일 변경을 기계로 표시하는 장치도 없다(`.github/`에는 `workflows/`만).
  - [추론] 개발 쪽이 설정 경고 기능 때문에 `SecretPaths`를 고치면(PR #100~#102가 실제로 이 클래스를 고쳤다) 지문 시험이 실패하고, 실패 메시지대로 값을 고친다. 그 PR이 `autotrade-config`만 건드린 "설정 경고 개선"으로 보이면 프로젝트 지침 10장의 Claude 머지 대상과 구분할 근거가 규칙 문서에 없다. D-086:51이 스스로 든 이유(ADR-002:219 "보호 테스트 표 수정은 정상 절차와 겉보기가 같아 리뷰에만 기대기 어렵다")가 그대로 적용된다.
  - 고칠 것(하나 이상): (a) 보호 테스트 정의(`project-instructions.md` 11장 보호 테스트, `docs/qa/README.md` 1장, `checklists.md` A 대상)에 `RealPathReflectionTest`(또는 `autotrade-architecture-test` 전체)를 넣고, "고정 값(지문·허용 목록·고정 타입 목록) 갱신을 포함한 모든 변경"을 실전 관련 PR로 적는다. (b) 기계 장치: CI가 보호 테스트 파일이나 `SecretPaths.java`가 바뀐 PR을 표시(라벨 요구 또는 실패)하게 하거나 CODEOWNERS로 사용자 리뷰를 강제한다. 프로젝트 지침 원문을 바꾸면 사용자가 개발 프로젝트 설정의 지침도 갱신해야 한다.

## 점검표 밖 발견(권고)
- **R1 (권고) 지문 함수 자체의 성질 시험이 약하다.** 자기 확인은 서로 다른 두 클래스의 지문이 다르다는 것뿐이라(:722-725) `SKIP_CODE`를 더하는 식의 약화를 함께 고친 지문 값과 내면 통과한다. "본문만 다른 두 예제는 다르고, 줄 번호·매개변수 이름만 다른 두 예제는 같다"를 단언하면 함수 약화를 시험으로 잡는다. F1이 해소되면 그 diff도 실전 관련 PR로 간다.
- **R2 (권고, 낮음) 변이 N3 생존, `WildcardType`·`TypeVariable`·`GenericArrayType` 위반 예제 없음.** `hasWildcardOrTypeVariable`의 배열 갈래와 `REFLECT_TYPE_OWNERS`의 세 타입은 예제가 없어 빼도 시험이 모른다. 실전 경로 타입 허용 목록(`ALLOWED_REFLECT_TYPES`, :286-287)이 세 타입을 따로 막고, `SecretPaths`의 변경은 지문이 잡으므로 지금 열린 길은 없다[추론].
- **R3 (참고) 지문은 javac 출력과 ArchUnit 안의 ASM에 기댄다.** 툴체인은 JDK 21(`languageVersion 21`, `release 21`)이고, 이 컨테이너(OpenJDK 21.0.12)와 CI(Temurin 21) 모두 같은 값으로 통과했다. 사용자 PC의 JDK 21 배포판·패치가 다르면 값이 다를 수 있다[추론, 확인 못 함: 다른 JDK 없음]. 실패 방향이지만, 그때 "값만 고치는" 습관이 F1의 구멍을 넓힌다. 7단계 실행 절차서에 사용자 PC에서 `RealPathReflectionTest`가 고친 값 없이 통과하는지 확인하는 줄을 권한다.
- **R4 (참고) main 병합.** origin/main d6fb6e7과 충돌은 없지만 헤드에 병합되지 않았다. 머지 전 main 병합 뒤 CI 재확인을 권한다.

## 시나리오 시도(잘못된 주문·중복 주문·보호 공백)
1. `SecretPaths`에 중첩 클래스 칸을 둬 클래스 객체를 내보냄(P1): 모양 시험과 지문 둘 다 잡음. 공백 없음.
2. `SecretPaths`의 기존 공개 반환 목록에 클래스 객체를 섞음(P4): 지문만 잡음. 지문 값 갱신 한 줄로 기계 검사 전부 통과. 이후 엔진이 리터럴 없이 Guard 타입 클래스 객체를 얻어 Jackson으로 Guard 객체를 만드는 길(PR #110 QA E2E)의 유일한 문은 PR 분류 절차다. F1.
3. JDK·ArchUnit 갱신으로 지문이 바뀜(R3): 실패 방향이라 보호가 약해지지는 않으나, 값 갱신이 일상화되면 2번과 구분이 어렵다.

## 판정
수정 필요(A14/F1: 지문 값 갱신을 실전 관련 PR로 만드는 규칙이 보호 테스트 정의에 없음. 권고 R1~R4)
