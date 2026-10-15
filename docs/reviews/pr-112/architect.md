# architect 검토: SecretPaths 지문 고정과 모양 검사 보강(PR #110 QA 권고 후속, D-086)
- 대상: PR #112 / 헤드 c650772 (merge-base fa1996b = 현재 origin/main) / main 대비 diff 전체(`git diff origin/main...HEAD`, 커밋 1개, 파일 4개 +128 −15: `RealPathReflectionTest.java`, 테스트 소스 `com.autotrade.config.SamePackageRecordReader`, `ADR-002-real-trading-guard.md`, `D-086-real-path-reflection-ban.md`). 운영 코드 변경 0줄
- 점검표: B (보호 테스트 `RealPathReflectionTest` 변경이라 실전 관련 PR, A는 risk-ops)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 c650772): `BUILD SUCCESSFUL in 3m 26s`. 결과 XML 합계 1,142건, 실패·오류·건너뜀 0. `RealPathReflectionTest`의 `@Test`는 main 8개 → 헤드 9개(새 시험 `레코드_구성_요소를_읽는_클래스의_지문은_고정_값과_같다`)
  - `git merge-base --is-ancestor origin/main HEAD` 참. CI(헤드 c650772, REST check-runs): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - 변이·변조 실험 9종(각각 `./gradlew --offline :autotrade-architecture-test:test --tests '*RealPathReflectionTest'`, 실험 뒤 원문 복원, `git status` 깨끗함 확인). 아래 표

## 변이·변조 실험
| # | 바꾼 것 | 결과 |
|---|---|---|
| FM1 | 운영 `SecretPaths.collect`에서 `paths.add(path)`를 `((Set) paths).add(t)`로(구성 요소 타입의 클래스 객체를 공개 `all()`이 돌려주는 집합에 넣음. 메서드에 `@SuppressWarnings`(소스 보존 주석)만 더함) | **살아남음**. 9건 모두 통과, 지문 불변. 아래 AR-1 |
| FM2 | 운영 `SecretPaths.collect`의 `args[1] == Secret.class`를 `args[0] == ...`로 | **살아남음**(지문 불변). AR-1의 같은 원인 |
| FM3 | 운영 `SecretPaths`에 private 메서드 `qaProbe()` 추가 | 잡힘 `레코드_구성_요소를_읽는_클래스의_지문은_고정_값과_같다` FAILED (양성 대조) |
| FM4 | 운영 `SecretPaths`에 주석 한 줄 추가 | 통과(지문 불변, 의도대로) |
| MM1 | 새 규칙 "허용 목록 밖 리플렉션 타입 메서드"의 보고를 끔 | 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다` FAILED |
| MM2 | 와일드카드·타입 변수 검사를 끔 | 잡힘 `레코드_구성_요소를_읽는_클래스는_구성_요소를_밖으로_내보내지_않는다` FAILED |
| MM3 | 와일드카드 검사를 반환 타입에만 적용(매개변수 제외, `types.stream().limit(1)`) | **살아남음**. 아래 AR-2 |
| MM4 | `REFLECT_TYPE_OWNERS`에서 `java.lang.reflect.Type` 제거 | **살아남음**. 아래 AR-2 |
| MM5 | 고정 타입 목록 검사를 중첩 클래스에 적용하지 않음(`List.of(k)`) | 잡힘 모양 시험 FAILED |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 c650772 `./gradlew build --no-daemon` 직접 실행 성공, 1,142건 실패·오류·건너뜀 0. CI 3개 success |
| B2 | 통과 | 모듈 의존·소유 변경 없음(테스트 소스 2개와 문서 2개). ArchUnit 규칙 변경은 강화뿐이다: `Type`·`ParameterizedType` 메서드 호출을 허용 클래스 안으로(RealPathReflectionTest.java:407, :469-473), 서명 와일드카드·타입 변수 금지(:803-806), 중첩 클래스 금지와 중첩 클래스 타입 검사(:777-787), 지문 고정(:707-743). 삭제 줄은 서명 변경 1줄, 기존 타입 목록 검사 4줄(중첩 클래스까지 넓힌 :779-787로 옮김), 단언 1줄(같은 내용에 단언을 더한 줄로 교체)뿐이다. 규칙 변경은 D-086:51·:58·:61과 ADR-002:242에 반영했다. 다만 D-086:51의 지문 설명이 기계 검사보다 넓다(AR-1) |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 새 지문 계산은 `TreeSet`으로 정렬해 순서에 의존하지 않는다(:726-735). 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 위반 예제와 기대 문자열이 있다(`SamePackageRecordReader`의 `lastType`·`leak()`·`Sink`·`typeArgument`, RealPathReflectionTest.java:681-698, :978-981). 운영 `SecretPaths`에 대해 위반 0건과 지문 일치를 단언한다. MM1·MM2·MM5·FM3이 시험에서 잡힌다. 지우거나 건너뛴 테스트 없음(1,141 → 1,142). MM3·MM4 생존은 권고(AR-2) |
| B7 | risk-ops 검토 필요 | 보호 테스트 `RealPathReflectionTest` 변경이라 실전 관련 PR이다(프로젝트 지침 11장). 헤드 c650772에 `docs/reviews/pr-112/risk-ops.md`가 없다. 머지는 사용자, 머지 전 독립 QA 관문 |
| B8 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음(지문 값은 `SecretPaths` 구조의 SHA-256이고 비밀값이 아니다). CI `Secret scan and collector boundary` success |

## 점검표 밖 발견
- **AR-1 (수정 필요, 문서 또는 시험) 지문은 "SecretPaths 코드 변경"을 다 잡지 않는데, D-086과 시험 설명은 다 잡는다고 쓴다.**
  - [사실] 지문(RealPathReflectionTest.java:725-743)은 클래스 수정자, 멤버 이름·수정자·원시 반환 타입, 접근의 `출발 메서드 -> 대상` 쌍(줄 번호 없음, `TreeSet`이라 중복 제거), 클래스 파일 상수 타입 이름만 묶는다. 메서드 본문의 데이터 흐름(어떤 값을 넘기는지), 문자열·숫자 상수, 분기 조건은 들어가지 않는다.
  - [사실] FM1: 이미 있는 접근(`collect -> Set.add(Object)`)에 넘기는 값만 구성 요소 타입의 클래스 객체로 바꾸면 9건이 모두 통과한다. 이것은 D-086:51이 바로 앞 문장에서 "정적 모양 검사로 다 닫지 못한다"고 든 예 "목록 안 컬렉션의 기존 접근을 다른 값으로 바꾸기"와 같은 모양이다. FM2(조건 상수 변경)도 지문이 같다.
  - [사실] 그런데 D-086:51은 "그래서 `SecretPaths`를 신뢰 경계로 보고 ... 지문을 보호 테스트에 고정한다. `SecretPaths`의 코드를 바꾸면 지문을 고쳐야 하므로 그 PR은 보호 테스트 변경 ... 이 된다"고 쓰고, 시험 Javadoc(:702-706)도 "그 클래스를 바꾸면 이 값을 고쳐야 하고"라고 쓴다. 다음 검토자가 이 문장을 믿으면 지문이 그대로인 `SecretPaths` 변경 PR을 실전 관련 PR이 아닌 일반 PR로 넘길 수 있다. PR #110 AR-2와 같은 종류(결정 문서가 기계 검사보다 넓게 주장)다.
  - [추론] FM1 그대로는 `TreeSet`에 `Class`를 넣어 클래스 초기화 때 `ClassCastException`이 나므로 실행되는 누출은 아니다. 정렬 안 하는 컬렉션에 이미 있는 접근을 쓰면 같은 방식으로 실행되는 누출을 만들 수 있다고 본다. 지금 운영 `SecretPaths`에는 열린 길이 없다.
  - 고칠 것(둘 중 하나, (a)가 낫다): (a) 지문에 메서드 본문 바이트코드를 넣는다. 예: 클래스 파일의 각 메서드 `Code` 속성의 `code` 배열(상수 풀 색인 포함)을 묶고 `LineNumberTable`·`LocalVariableTable`·`SourceFile` 같은 디버그 속성은 뺀다. 주석·서식 변경은 바이트코드와 상수 풀을 바꾸지 않으므로 FM4 성질은 유지된다. FM1·FM2가 지문 시험에서 실패하는지 변조로 확인하고 D-086 검증 절에 적는다. (b) D-086:51과 시험 Javadoc을 기계 검사 범위에 맞춘다: "지문은 멤버·접근 쌍·클래스 파일 타입만 본다. 이미 있는 접근에 넘기는 값이나 상수만 바꾸는 변경은 지문을 바꾸지 않으므로, `SecretPaths`를 고치는 PR은 지문 변화와 상관없이 실전 관련 PR로 다룬다"로 쓰고, 이를 강제할 장치가 없다는 것을 남은 한계에 적는다.
- **AR-2 (권고, 낮음) 변이 MM3·MM4가 살아남는다.** 위반 예제의 와일드카드는 반환 타입(`leak()`)뿐이라 매개변수 쪽(PR #110 QA S8 `Predicate<?>` 콜백 모양)을 빼도 통과한다. `typeArgument`는 `ParameterizedType`과 `Type` 메서드를 둘 다 부르는데 기대 문자열이 메서드 이름 접두어만 보아 `Type`을 목록에서 빼도 통과한다. 예제에 `static void sink(java.util.function.Predicate<?> p)`와 `Type`만 부르는 메서드(예: `typeNameOf(java.lang.reflect.Type t)`의 `t.getTypeName()`)를 두고 각각 기대 문자열을 단언하기를 권한다.
- **AR-3 (권고, 낮음) `REFLECT_TYPE_OWNERS`가 `java.lang.reflect`의 다른 제네릭 타입 인터페이스(`WildcardType#getUpperBounds`, `TypeVariable#getBounds`, `GenericArrayType#getGenericComponentType`)를 빼 둔다.** [추론] 실전 경로가 이 객체를 얻으려면 `ParameterizedType#getActualTypeArguments`(이제 허용 클래스 안만)나 소스에 적은 `TypeReference`를 거쳐야 해서 지금 새 길은 없다. 같은 이유로 묶는 것이라 목록에 함께 넣으면 다음 검토에서 따로 따질 필요가 없다. 넣을 때 운영 코드 무위반은 이번 빌드로 확인된 `Type`·`ParameterizedType`과 같이 다시 확인한다.
- 참고: 지문은 같은 major 버전 javac의 출력에 기대어 있다(툴체인 `JavaLanguageVersion.of(21)`, `options.release = 21`, build-logic/src/main/kotlin/autotrade.java-conventions.gradle.kts). 합성 멤버(람다) 이름 규칙이 바뀌는 JDK 갱신 때 지문이 바뀔 수 있다. 실패 방향이라 보호가 약해지지는 않는다. JDK를 올리는 PR에서 지문 갱신이 실전 관련 PR이 된다는 점만 알아 두면 된다.
- 참고: 헤드 c650772의 `docs/qa/pending.md`에 PR #112 줄이 없다. 보호 테스트 변경이라 머지 전 독립 QA 관문이며, 메인이 요청 줄을 이 PR 브랜치에 더해야 한다(지침 11장).
- [확인 범위] 커밋 메시지·D-086의 "QA Q1·R1·R2, risk-ops R-2 반영", "주석만 더하면 통과, private 메서드를 더하면 실패"는 사실로 받지 않았다. FM3·FM4와 MM1·MM2·MM5로 직접 확인했고, 해당 주장은 맞다.

## 판정
수정 필요(AR-1: 지문이 메서드 본문 데이터 흐름·상수 변경을 잡지 못하는데 D-086:51과 시험 Javadoc은 `SecretPaths` 코드 변경이면 지문이 바뀐다고 쓴다. 지문에 바이트코드를 넣거나(권장) 문서를 기계 검사 범위에 맞춘다. 권고 AR-2·AR-3. B7 risk-ops 검토 필요, 보호 테스트 변경이라 머지는 사용자)

## 재검토 2026-10-15
- 대상: PR #112 / 헤드 b85bede (1차 원문 커밋 e2b78f0 뒤 코드 커밋 1개) / main 대비 diff 전체(`git diff origin/main...HEAD`, 6파일: `RealPathReflectionTest.java`, 테스트 소스 `SamePackageRecordReader`, `ADR-002`, `D-086`, `docs/qa/pending.md`, 이 원문). 운영 코드 변경 0줄. merge-base fa1996b, origin/main은 그 뒤 d6fb6e7(PR #111, 문서·`KrxMarketRules` 주석)로 나아갔고 `git merge-tree --write-tree origin/main HEAD` 충돌 없음, 겹치는 파일 없음
- 점검표: B
- 직접 실행한 것:
  - `./gradlew clean build --no-daemon` (헤드 b85bede): `BUILD SUCCESSFUL in 3m 6s`. 결과 XML 합계 1,142건, 실패·오류·건너뜀 0. `RealPathReflectionTest` 9건
  - CI(헤드 b85bede, REST check-runs): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - 1차 변이·변조를 같은 방식으로 다시 돌리고 몇 가지를 더함(각각 `--tests '*RealPathReflectionTest'`, 실험 뒤 원문 복원, `git status` 깨끗함 확인)
  - [확인 범위] 이 작업 트리는 같은 브랜치를 다른 작업 트리가 앞당겨 처음 빌드 한 번이 e2b78f0 내용으로 돌았다. 그 결과는 버리고, 작업 트리를 HEAD(b85bede)로 맞춘 뒤 clean build와 변이를 처음부터 다시 돌렸다. 아래는 모두 b85bede 기준이다

| # | 바꾼 것 | 1차(c650772) | 재검토(b85bede) |
|---|---|---|---|
| FM1 | `SecretPaths.collect`의 `paths.add(path)` → `((Set) paths).add(t)` | 살아남음 | 잡힘 `레코드_구성_요소를_읽는_클래스의_지문은_고정_값과_같다` FAILED |
| FM2 | `args[1] == Secret.class` → `args[0]` | 살아남음 | 잡힘(지문 시험) |
| FM7 | 정규식 상수 `[a-z][a-z-]*` → `[a-z][a-z0-9-]*` | (안 돌림) | 잡힘(지문 시험) |
| FM3 | private 메서드 추가 | 잡힘 | 잡힘(지문 시험) |
| FM4 | 주석 한 줄 추가 | 통과 | 통과(의도대로) |
| FM5 | 메서드 줄바꿈 서식 변경(`isKeyLike` 본문을 한 줄로) | (안 돌림) | 통과(줄 번호만 바뀜, 의도대로) |
| FM6 | 지역 변수 이름 변경(`presentIn`의 `found` → `hits`) | (안 돌림) | 통과(지역 변수 이름은 디버그 정보, 동작 같음) |
| MM1 | 리플렉션 타입 메서드 규칙 보고 끔 | 잡힘 | 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다` |
| MM2 | 와일드카드 검사 끔 | 잡힘 | 잡힘(모양 시험) |
| MM3 | 와일드카드 검사를 반환 타입에만 | 살아남음 | 잡힘(모양 시험, `drain(List<? super Object>)`) |
| MM4 | `REFLECT_TYPE_OWNERS`에서 `Type` 제거 | 살아남음 | 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다`(`plainTypeName`) |
| MM5 | 중첩 클래스 타입 검사 끔 | 잡힘 | 잡힘(모양 시험) |
| MM6 | 새로 넣은 `WildcardType`·`TypeVariable`·`GenericArrayType` 세 개를 목록에서 뺌 | - | 살아남음(아래 AR-R1) |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 b85bede `./gradlew clean build --no-daemon` 직접 실행 성공, 1,142건 실패·오류·건너뜀 0. CI 3개 success |
| B2 | 통과 | 모듈 의존·소유 변경 없음. 규칙 변경은 강화뿐이다: 지문을 디버그 정보만 뺀 바이트코드의 SHA-256으로 바꿈(RealPathReflectionTest.java:728-750, `ClassReader.SKIP_DEBUG` :744), 리플렉션 타입 목록 확대(:407-408). 1차 대비 삭제 줄은 이전 지문 계산 줄(같은 메서드를 바이트코드 방식으로 교체)과 지문 값·단언 줄 교체뿐이다. D-086:51의 지문 설명이 이제 기계 검사와 맞는다: 메서드 본문의 데이터 흐름과 상수가 지문에 들어감(FM1·FM2·FM7로 확인), 서식·주석만 바꾸면 지문이 같음(FM4·FM5로 확인), JDK 갱신 때 실패 방향으로 바뀔 수 있음 |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 지문은 같은 입력에 대해 결정적이다(ASM `ClassWriter(0)`로 상수 풀을 방문 순서대로 다시 만든다). clean build에서 고정 값과 일치. 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 1차 AR-2의 생존 변이 MM3·MM4가 이제 잡힌다(위반 예제 `drain`·`plainTypeName`과 기대 문자열, RealPathReflectionTest.java:692, :988). 지우거나 건너뛴 테스트 없음(1,142건 유지). MM6 생존은 권고(AR-R1) |
| B7 | risk-ops 검토 필요 | 보호 테스트 `RealPathReflectionTest` 변경이라 실전 관련 PR이다. 헤드 b85bede에 `docs/reviews/pr-112/risk-ops.md`가 없다. 머지는 사용자, 머지 전 독립 QA 관문(`docs/qa/pending.md`:31에 요청 줄 추가됨) |
| B8 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음(지문 값은 바이트코드 해시). CI `Secret scan and collector boundary` success |

## 1차 발견 처리
- **AR-1 해소.** 지문을 디버그 정보만 뺀 클래스 파일 바이트코드로 바꿨다. 1차에서 살아남은 FM1(기존 접근에 넘기는 값만 바꾸기)·FM2(조건 상수)와 새 FM7(문자열 상수)이 모두 지문 시험에서 실패한다. 주석(FM4)·서식(FM5)만 바꾸면 통과해 Javadoc(:704-709, :728-733)과 D-086:51의 설명이 맞다. D-086:61의 변조 기록("문자열 상수 하나 바꾸기와 ... FM1은 실패")도 직접 재현했다.
- **AR-2 해소.** MM3·MM4가 잡힌다.
- **AR-3 반영.** 목록에 세 타입을 넣었고, 운영 코드 위반 0건은 clean build로 확인했다.

## 점검표 밖 발견(재검토)
- **AR-R1 (권고, 낮음) MM6이 살아남는다.** AR-3으로 넣은 `WildcardType`·`TypeVariable`·`GenericArrayType`은 위반 예제가 없어 목록에서 다시 빼도 시험이 모른다. 1차 AR-3에서 적은 대로 지금 새로 열린 길은 없고 목록 변경은 보호 테스트 diff로 드러난다[추론]. 다음에 이 예제를 고칠 때 `WildcardType#getUpperBounds` 같은 호출 하나를 위반 예제에 두고 단언하면 된다.
- **참고 1.** 지문은 ArchUnit 안에 재배치된 ASM(`com.tngtech.archunit.thirdparty.org.objectweb.asm`, :741-744)을 쓴다. 공개 API가 아니라 ArchUnit을 올리면 컴파일이 깨지거나 ASM 출력이 달라져 지문이 바뀔 수 있다. 둘 다 실패 방향이라 보호가 약해지지는 않는다. ArchUnit을 올리는 PR도 지문 갱신 때문에 실전 관련 PR이 될 수 있다는 점만 D-086에 한 줄 더하면 좋다(JDK 갱신 문장 옆).
- **참고 2.** 지역 변수 이름 변경(FM6)은 지문을 바꾸지 않는다. 동작이 같은 변경이라 문제는 아니고, Javadoc의 "디버그 정보(줄 번호·지역 변수 이름·소스 파일 이름)만 뺀다"는 설명과도 맞는다.
- [확인 범위] 커밋 메시지의 "AR-1~AR-3 반영"은 사실로 받지 않았다. 해소 여부는 위 변이 표와 clean build로만 판정했다.

## 판정
승인 (1차 AR-1 해소, AR-2 해소, AR-3 반영. 권고 AR-R1(MM6)과 참고 1은 머지를 막지 않는다. B7 risk-ops 검토 필요, 보호 테스트 변경이라 머지는 사용자, 머지 전 독립 QA 관문)

## 재검토 2026-10-15 (2차, 헤드 c14578d)
- 대상: PR #112 / 헤드 c14578d / main 대비 diff 전체(`git diff origin/main...HEAD`, 17파일 +602 −18: `RealPathReflectionTest.java`, 테스트 소스 `SamePackageRecordReader`, `.github/CODEOWNERS`, `.github/workflows/ci.yml`, `scripts/ci/mark-protected-changes.sh`·`protected-paths.txt`·`test-mark-protected-changes.sh`, `project-instructions.md`, `qa/README.md`, `qa/checklists.md`, `qa/pending.md`, `qa-out-status.md`, `ADR-002`, `D-086`, 검토 원문 3개). 운영 코드 변경 0줄. 직전 architect 재검토(618e559, 헤드 b85bede) 뒤 코드 변경: 654b20e(QA F1·R1~R4), c14578d(main b4ed7fc 병합, risk-ops F-3·F-4·R-6)
- 점검표: B
- [확인 범위] 요청을 받을 때 헤드가 654b20e였고 그 기준으로 검토하던 중 c14578d로 바뀌었다. 654b20e 기준 원문은 커밋하지 않았다. 아래는 모두 c14578d 기준이다. 이 작업 트리는 다른 작업 트리가 같은 브랜치를 앞당겨 파일이 옛 커밋에 머물러 있었기 때문에, `origin/claude/project-thread-l44wj7`(c14578d)로 맞춘 뒤 모든 실행을 했다
- 직접 실행한 것:
  - `./gradlew clean` 뒤 `./gradlew build --no-daemon` (헤드 c14578d): `BUILD SUCCESSFUL in 3m 1s`. 결과 XML 합계 1,178건(main b4ed7fc의 PR #114 시험 포함), 실패·오류·건너뜀 0
  - CI(헤드 c14578d, REST check-runs): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success. guards 잡 단계(REST `actions/jobs/116320243284`): "Protected change marker self-test" success, "Protected change marker" success. 그 check-run 주석에 `warning 보호 대상 변경(실전 관련 PR) 보호 대상 파일 12개를 바꾼다 ...`가 실제로 남았다
  - 표시 스크립트 로컬: `scripts/ci/test-mark-protected-changes.sh` → "표시 스크립트 자체 시험 통과". `scripts/ci/mark-protected-changes.sh main` → 보호 대상 12개(CI 주석과 같은 수). 변이: 표시 스크립트에서 `--no-renames`를 빼면 자체 시험이 `::error::보호 시험을 밖으로 옮긴 변경을 표시하지 않았다`로 exit 1(원문 복원 확인)
  - `RealPathReflectionTest` 변이(각각 `--tests '*RealPathReflectionTest'`, 원문 복원): FM1(`SecretPaths` 기존 접근에 클래스 객체) 잡힘(지문), FM4(주석) 통과, N3(제네릭 배열 갈래 끔) 잡힘(모양), MM6(새 세 리플렉션 타입 제거) 잡힘(`규칙은_리플렉션_우회를_실제로_잡아낸다`), SK1P(지문 함수에 `SKIP_CODE` + 고정 값 비교 끔) 잡힘 "본문 상수만 다르면 지문이 달라야 한다", SK2P(`SKIP_DEBUG` 제거 + 고정 값 비교 끔) 잡힘 "줄 번호만 다르면 지문이 같아야 한다"
  - **main 충돌**: 검토 끝 시점 origin/main은 88212d3(PR #115 머지)으로 다시 나아갔다. `git merge-tree --write-tree origin/main HEAD` → `CONFLICT (content): Merge conflict in docs/qa-out-status.md`. PR #115가 바꾼 나머지 파일(실험실 모니터 코드·시험, `monitor.js`, ADR-005, config-keys, D-090·D-091, 결정 README, PR #115 원문)은 이 PR과 겹치지 않는다
  - 저장소 설정: `branches/main/protection` → 404 "Branch not protected"

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 c14578d clean build 직접 실행 성공(1,178건 실패 0). CI 3개 success |
| B2 | 통과 | 모듈 의존·소유 변경 없음. `autotrade-architecture-test`의 이 PR 변경은 `RealPathReflectionTest`·`SamePackageRecordReader` 두 파일뿐이다(`git diff b4ed7fc HEAD -- autotrade-architecture-test`). 강화뿐이다: 리플렉션 타입 메서드 5종을 허용 클래스 안으로(:407-408), 서명 와일드카드·타입 변수 금지와 제네릭 배열(`hasWildcardOrTypeVariable`, :857), 중첩 클래스 금지, 디버그 정보만 뺀 바이트코드 지문(:743-780)과 그 함수의 자체 시험(:724-729). 규칙 변경은 D-086:51·:58·:61 이하, ADR-002:242에 반영했다 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST 계약 변경 없음. CI 단계와 표시 스크립트, CODEOWNERS는 개발 절차이고, 절차 문서(project-instructions.md 11장 보호 테스트, qa/README.md 1장, checklists.md A11)와 D-086:51을 함께 갱신했다. 표시가 보조라는 점("경고가 없다고 실전 관련이 아닌 것은 아니다")과 이름 바꾸기 처리, CODEOWNERS가 브랜치 보호 없이는 강제되지 않는다는 점이 qa/README.md:17과 스크립트 머리 주석에 있다 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 지문은 같은 입력에 결정적이고 자체 시험의 합성 클래스는 고정 입력이다. 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 실패하는 시험이 있다: 위 변이 6종 중 의도된 통과(FM4) 밖은 모두 잡힘. 표시 스크립트에는 임시 저장소 자체 시험(수정·밖으로 옮기기는 표시, 보호 밖 변경은 표시 안 함)이 CI 단계로 들어갔고, `--no-renames` 제거 변이를 잡는다. 지우거나 건너뛴 시험 없음 |
| B7 | risk-ops 검토 필요 | 보호 테스트·CI 워크플로·규칙 문서 변경이라 실전 관련 PR이다. 헤드의 risk-ops 원문(`docs/reviews/pr-112/risk-ops.md`) 마지막 판정은 654b20e 기준 "수정 필요(F-3·F-4)"이고, c14578d의 F-3·F-4·R-6 반영에 대한 risk-ops 재검토가 필요하다. 머지는 사용자, 머지 전 독립 QA 재검토 |
| B8 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음. 자체 시험의 `ci@example.invalid`는 임시 저장소 커밋용 자리표시자다. CI `Secret scan and collector boundary` success |

## 직전 발견 처리
- 1차 재검토(b85bede) **AR-R1 해소**: MM6 잡힘. 참고 1(ArchUnit·JDK 배포판 차이) 반영: D-086:51, 지문 실패 메시지.
- 독립 QA **F1**: 보호 테스트 정의 확대(문서 세 곳)와 기계 표시(CI 경고, 실제 CI에서 경고가 남는 것 확인)가 들어갔다. 해소 판정은 QA 몫이다. **R1**(SK1P·SK2P 잡힘), **R2**(N3·MM6 잡힘), **R3**(문구) 반영 확인.
- risk-ops **F-4** 반영 확인(`--no-renames`, 자체 시험이 변이를 잡음). **R-6** 반영: 보호 대상 경로에 `REQUIRED_PROJECTS`의 실전 경로 모듈 전체(`autotrade-config`·`control`·`domain`·`marketdata`·`strategy`·`notification-jar`·`monitor-agent-jar`)와 규칙 문서 5개를 넣었고, 표시가 보조라는 문장을 넣었다. 새 모듈 `autotrade-monitor-sources`(PR #114)는 실험실만 쓰므로(autotrade-app-lab/build.gradle.kts:20) 목록 밖이 맞다. **F-3**은 b4ed7fc 병합으로 풀렸으나 main이 다시 나아가 같은 종류가 다시 생겼다(아래 AR-R5).

## 점검표 밖 발견(2차 재검토)
- **AR-R5 (수정 필요) origin/main 88212d3과 `docs/qa-out-status.md`가 충돌한다.** PR #115가 같은 표 끝에 줄을 더했다. 표 줄이라 두 쪽을 다 남기면 풀린다. 겹치는 코드 파일은 없어 병합 결과의 동작 차이는 없다고 본다[추론, 병합 결과 빌드는 하지 않음]. 병합 뒤 새 헤드의 CI 3개 success를 확인한다. 이 PR 쪽 내용(시험·스크립트·문서)에는 더 고칠 것이 없다.
- **AR-R4 (참고, 직전과 같음) CODEOWNERS는 지금 아무것도 강제하지 않는다.** main 브랜치 보호가 없고 PR 작성자와 소유자가 같은 `hwan82`다. 문서가 이 점을 이제 그대로 적는다(qa/README.md:17). 켤지는 사용자 결정이다.
- **참고: 절차 범위 확대는 사용자 확인 사항이다.** project-instructions.md 11장이 바뀌어 사용자가 프로젝트 설정의 지침 사본도 갱신해야 한다. 또 보호 대상이 실전 경로 런타임 모듈 전체와 `autotrade-architecture-test` 전체로 넓어져, 새 모듈 추가(`ModuleOwnershipTest.OWNERS` 수정)나 `autotrade-domain`·`marketdata`·`strategy`를 고치는 PR도 CI 경고가 붙고 실전 관련 PR(사용자 머지, 독립 QA)로 가게 된다. 안전 쪽 변경이지만 사용자 머지 부담이 늘어나므로 메인이 사용자에게 알리기를 권한다.
- [확인 범위] 커밋 메시지·`qa-out-status.md`의 "F-3·F-4·R-6 반영", "F1·R1~R4 반영"은 사실로 받지 않았다. 위 빌드·CI 단계 기록·변이·로컬 실행으로만 판정했다.

## 판정
수정 필요(AR-R5: origin/main 88212d3과 `docs/qa-out-status.md` 충돌 해소 후 새 헤드 CI 확인. 그 밖의 B 항목은 통과, QA F1·R1~R3과 risk-ops F-4·R-6 반영 확인. 참고 AR-R4·절차 범위 확대 사용자 알림. B7 risk-ops 재검토 필요, 보호 테스트 변경이라 머지는 사용자)
