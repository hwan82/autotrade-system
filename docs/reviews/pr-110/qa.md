# 독립 QA 검토: PR #110 레코드 구성 요소 읽기 허용 클래스 모양 고정(PR #109 후속, D-086)
- 대상: PR #110 / 헤드 7e76619 (merge-base d0e9f3f = 현재 main) / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 6개 +288 −4: `RealPathReflectionTest.java`, 테스트 소스 `com.autotrade.config.SamePackageRecordReader`, `D-086`, `pending.md`, 개발 쪽 검토 원문 2개)
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 테스트 `RealPathReflectionTest` 변경)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 7e76619): Maven Central 429로 4회 실패 뒤 5회째 `BUILD SUCCESSFUL`. 결과 XML 합계 1,141건, 실패·오류·건너뜀 0. `RealPathReflectionTest`의 `@Test`는 main 7개 → 헤드 8개
  - 병합 확인: `git merge-base --is-ancestor origin/main HEAD` 참, `git merge-tree --write-tree origin/main HEAD` 충돌 없음(검토 끝 시점 main d0e9f3f, 헤드 불변 확인)
  - CI(헤드 7e76619): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - 허용 클래스 누출 탐침 S1~S5·S8: `SecretPaths`(운영 코드)에 메서드를 더하고, 같은 패키지 공개 클래스 `QaLeak110`, 실전 엔진 패키지 테스트 소스 `com.autotrade.app.engine.qaprobe110.QaLeakProbe`를 두어 `noReflectionIn(IN_REAL_PATH_PACKAGE)`와 `recordReaderShape(SecretPaths)`로 평가하고 실제 실행. 실험 뒤 모두 되돌리고 `git status` 깨끗함 확인
  - 변이 시험 M1~M9(`RealPathReflectionTest`만 고쳐 `--tests '*RealPathReflectionTest'`, 각 실험 뒤 원문 복원)

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| S1 | `SecretPaths`에 private `qaRead(Iterable<?>)`(넘겨받은 레코드 클래스의 구성 요소 `getType()`을 `List<Object>`에 담음) + 비공개 아닌 `static List<?> qaS1Types(Iterable<?>)` | **안 잡힘**(모양 시험·실전 범위 규칙 모두). 아래 R1 |
| S2 | 원시 타입 `static List qaS2Raw()` | 잡힘 "qaS2Raw()의 서명에 java.lang.Object" |
| S3 | `static <T> T qaS3First()` | 잡힘 "qaS3First()의 서명에 java.lang.Object" |
| S4 | `static Stream<?> qaS4Stream()` | **안 잡힘**(`Stream`은 `RECORD_READER_TYPES` 안) |
| S8 | `static void qaS8Push(Predicate<?> sink)`가 받은 콜백에 구성 요소 타입을 밀어 넣음 | **안 잡힘**(`Predicate`는 목록 안) |
| S5 | 중첩 클래스 `SecretPaths.QaSink`의 `static final List<Object> SINK`에 private 메서드가 구성 요소 타입을 넣음 | **안 잡힘**(architect AR-5와 같은 지점) |
| E2E | 엔진 패키지 `record Holder(GuardTransition t)`, `(Class<?>) QaLeak110.types(List.of(Holder.class)).get(0)` → `new ObjectMapper().convertValue(Map.of(), k)` | 규칙 위반 **0건**. 실행하면 Jackson이 `GuardTransition` 정규 생성자까지 가서 `IllegalArgumentException: Cannot construct instance of com.autotrade.risk.guard.GuardTransition, problem: account`(빈 맵이라 null 검사에서 멈춤). D-086:51의 `[추론]` "목록 안 컬렉션을 `List<?>`처럼 내보내는 길"이 엔진에서 Jackson 생성까지 실제로 이어짐을 확인 |
| M1 | `RecordComponent` 메서드 검사에서 새 조건 `|| !ALLOWED_RECORD_READERS.contains(호출 클래스)` 제거 | 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다` FAILED |
| M2 | 구성 요소 읽기 허용을 "호출 클래스 패키지가 `com.autotrade.config`"로(PR #109 QA M4) | 잡힘 같은 시험 FAILED. PR #109 Q2 해소 |
| M3 | 모양 검사의 "private이 아닌데 구성 요소를 읽는다" 끔 | 잡힘 `레코드_구성_요소를_읽는_클래스는_...` FAILED |
| M4 | 고정 타입 목록 검사 끔 | 잡힘 같은 시험 FAILED |
| M5 | `POJO_NODE_METHODS`에서 `putPOJO` 제거 | 잡힘 같은 시험 FAILED |
| M6 | 서명 검사에서 `java.lang.reflect` 패키지 조건 제거 | **살아남음**(전 시험 통과). 아래 Q1 |
| M7 | 서명 검사에서 `java.lang.Object` 조건 제거 | 잡힘 같은 시험 FAILED |
| M8 | 서명 검사에서 필드 타입 갈래(`types.add(f.getType())`) 제거 | **살아남음**(전 시험 통과). 아래 Q1 |
| M9 | `RECORD_READER_TYPES`에 `ConcurrentLinkedQueue` 추가 | 잡힘 같은 시험 FAILED |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff는 테스트 2개·문서 4개). RealTradingGuard 관련 시험은 전체 빌드 1,141건에 포함돼 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. `RealPathReflectionTest`의 변경은 강화뿐이다: `RecordComponent` 메서드 호출을 허용 클래스 안으로 좁힘(RealPathReflectionTest.java:460-465), 허용 클래스 모양 시험 추가(:660-741). 삭제 줄은 조건식 한 줄 교체와 주석 2줄뿐. M1·M2로 새 조건이 시험에서 물린다 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음(`Secret`·`SecretPaths`는 클래스 이름). CI `Secret scan and collector boundary` success |
| A11 | 통과 | 보호 테스트 삭제·약화 0건. 새 검사마다 위반 고정 예제 `SamePackageRecordReader`(first·typeName·stash)와 기대 문자열(RealPathReflectionTest.java:672-680, :882-887)이 있고, 운영 `SecretPaths`에 대해 위반 0건을 단언한다(:668-670). M1~M5·M7·M9 잡힘. M6·M8은 살아남음(Q1, 권고) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음. 정적 검사 강화뿐이라 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | main 포함, 충돌 없음. D-086:51이 시험이 실제로 보는 범위(서명 원시 타입, 클래스 파일 상수 고정 목록, `putPOJO` 류 금지, private 읽기, `RecordComponent` 메서드는 허용 클래스 안에서만)와 남는 길(`List<?>` 모양, `RuntimeConfig` 정적 필드, `[추론]`)을 맞게 적는다. D-086:58 시험 8건은 헤드 `@Test` 8개와 같다. E2E 탐침으로 D-086:51의 `[추론]`이 사실로 확인됐다(R1). 참고: ADR-002:242 "테스트 7건"은 갱신되지 않았다(R3) |
| B1 | 통과 | 헤드 7e76619 `./gradlew build` 직접 실행 성공(1,141건 실패 0). CI 3개 success |
| B6 | 통과 | 새 동작마다 위반 예제·기대 문자열·운영 코드 무위반 단언이 있다. 지우거나 건너뛴 테스트 없음(1,140 → 1,141, 새 `@Test` 1건) |

## 과금 검사 (QA 지침 8장)
해당 없음. 유료 API·서비스 호출을 더하거나 바꾸는 코드가 없다(시험 코드와 문서뿐).

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **같은 패키지의 다른 클래스가 구성 요소를 읽는다(PR #109 Q2).** M2(허용을 패키지 단위로 약화)가 이제 시험에서 잡힌다. 해소.
2. **허용 클래스가 구성 요소를 `Object`로 넘기고 받는 쪽이 `getType()`을 부른다(PR #109 Q1).** `SamePackageRecordReader.typeName`이 "허용 목록 밖 RecordComponent 메서드"로 잡히고, M1이 시험에서 잡힌다. 해소.
3. **허용 클래스가 클래스 객체 자체를 컨테이너로 내보낸다.** 서명에 `Object`가 드러나는 모양(S2 원시 타입, S3 타입 변수)은 잡히지만, 경계 없는 와일드카드(`List<?>`·`Stream<?>`·`Predicate<?>`, S1·S4·S8)와 중첩 클래스(S5)는 안 잡힌다. 엔진 패키지까지 이어 붙이면 리터럴 없이 얻은 `GuardTransition` 클래스 객체로 Jackson이 생성자를 부른다(E2E). 지금 이 길로 닿는 타입은 엔진이 이름을 댈 수 있는 레코드의 구성 요소뿐이고, 운영 코드에 `SecondConfirmation`을 구성 요소로 가진 레코드는 없으며(grep: `SecondConfirmation`은 `OperatorAuthenticator`·`RealTradingGuard`·자기 파일에만), `GuardTransition`은 공개 생성자 레코드라 Jackson이 만들어도 직접 `new`보다 더 할 수 있는 일이 없다 `[추론]`. 열려면 `autotrade-config` 운영 코드(`SecretPaths`)를 고쳐야 한다. 그래서 잘못된 주문·보호 공백으로 바로 이어지지 않는다 `[추론]`.
4. **고정 타입 목록 자체를 넓힌다.** M9(목록에 타입 추가)는 위반 예제가 그 타입을 쓰고 있어 잡힌다. 다른 타입을 추가하는 변경은 예제가 없으면 시험이 모른다 `[추론]`. 목록 변경이 보호 테스트 diff로 드러나므로 리뷰로 걸린다.

## 점검표 밖 발견
- **Q1 (권고, 낮음) 변이 M6·M8 살아남음.** 모양 시험이 서명의 `java.lang.reflect` 검사와 필드 서명 검사를 지워도 통과한다. 위반 예제 `SamePackageRecordReader.first`는 `RecordComponent`를 반환하지만 기대 문자열이 `Class` 매개변수 쪽만 단언하고(RealPathReflectionTest.java:675), 비공개 아닌 필드 예제는 없다. `RecordComponent` 반환은 M1 규칙이 받는 쪽에서 막지만, `Type`·`ParameterizedType` 반환(받는 쪽 호출은 R-2대로 막히지 않음)은 M6이 적용되면 열린다. 기대 문자열에 "first(java.lang.Class)의 서명에 java.lang.reflect.RecordComponent"를 더하고, 예제에 `static java.lang.reflect.Type T;` 같은 비공개 아닌 필드 하나를 두기를 권한다.
- **R1 (권고, 낮음) D-086의 남는 길 `[추론]`은 실제로 엔진의 Jackson 생성까지 이어진다(S1·S4·S8 + E2E).** D-086:51과 PR 본문이 이미 한계로 적었고 `SecretPaths`·`RECORD_READER_TYPES` 변경을 risk-ops 검토 경계로 정했다. 다만 `SecretPaths` 변경은 보호 테스트 diff에 나타나지 않고 이를 기계로 강제하는 장치(CODEOWNERS, 파일 지문 시험 등)는 없다. 개발 쪽 architect가 (a)안으로 낸 "경계 없는 와일드카드 타입 인자 금지"를 시험에 넣으면 S1·S4·S8이 막힌다(지금 `SecretPaths`의 비공개 아닌 서명에는 와일드카드가 없다: `all()`·`isKeyLike`·`isSecret`·`extract`·`presentIn`·`forWarning`·`forNonSecretWarning`·`nonSecretLeaves`, `ANY_KEY`). D-086:51의 `[추론]`은 이 보고서로 사실 확인됐으니 근거를 옮겨 적기를 권한다.
- **R2 (권고, 낮음) 중첩 클래스 길(S5).** architect AR-5·risk-ops R-3과 같은 결론을 QA가 따로 재현했다. 지금 `SecretPaths`에 중첩 클래스가 없어 열린 길은 없다.
- **R3 (참고) ADR-002-real-trading-guard.md:242 "테스트 7건과 변조 실험 7종"이 그대로다.** 이 PR로 `RealPathReflectionTest`는 8건이 됐다. D-086을 가리키는 문장이라 판정에는 넣지 않는다.

## 판정
승인 (빌드 1,141건 통과, CI 통과, main 충돌 없음. PR #109 QA Q1·Q2는 M1·M2로 해소 확인. 권고 Q1(변이 M6·M8 살아남음), R1(와일드카드 컨테이너 누출이 엔진 Jackson 생성까지 이어짐, 지금은 `SecretPaths` 변경이 있어야 열림), R2(중첩 클래스), 참고 R3은 머지를 막지 않는다. 실전 관련(보호 테스트 변경)이라 머지는 사용자)
