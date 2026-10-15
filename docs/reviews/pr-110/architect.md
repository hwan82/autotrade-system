# architect 검토: test(arch): 레코드 구성 요소 읽기 허용 클래스 모양 고정과 남은 한계 기록(PR #109 후속)
- 대상: PR #110 / 커밋 c9dacca / main 대비 diff(`git diff origin/main...HEAD`, 3파일 +75 -3: `RealPathReflectionTest.java`, 새 고정 예제 `com/autotrade/config/SamePackageRecordReader.java`(테스트 소스), `D-086-real-path-reflection-ban.md`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (c9dacca, 변경 없는 상태) → BUILD SUCCESSFUL in 3m 21s, 종료 코드 0
  - 변조 실험 4건(모두 되돌림, 커밋하지 않음, 각 `./gradlew :autotrade-architecture-test:test --tests com.autotrade.architecture.RealPathReflectionTest`)
    - M1 `NameToClassBinding.Holder`의 `public T value;`를 `public Class<T> value;`로 바꿈 → PR 코드 그대로면 **통과(검출 안 됨)**. 같은 변조에 main의 단언 위치(`byNameDetails`에 `doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value")`)를 되살리면 `규칙은_리플렉션_우회를_실제로_잡아낸다` 실패("…NameToClassBinding$Holder.value 필드 타입에 Class"). 아래 AR-1
    - M2 `SecretPaths`에 PR #109 V4 탐침(`static Object bind(Class<?> r, Map<String,Object> f, ObjectMapper m) { return m.convertValue(f, r.getRecordComponents()[0].getType()); }`) → 새 시험 실패 3건(서명에 `java.lang.Class`, `java.lang.Object`, private이 아닌데 구성 요소를 읽음). AR-R1 원래 지적은 막힘
    - M3 `RecordComponent` 메서드 검사에서 호출 클래스 조건(`|| !ALLOWED_RECORD_READERS.contains(origin)`)을 지움 → `규칙은_리플렉션_우회를_실제로_잡아낸다` 실패(`SamePackageRecordReader.typeName` 미검출). Q1 고정 예제가 실제로 일함
    - M4 `SecretPaths`에 private 도우미를 거쳐 `static List<?> leak()`(구성 요소의 `getType()` 결과를 담은 목록)과 `static ObjectNode leakNode()`(`putPOJO("t", ...getType())`)를 추가 → **통과(검출 안 됨)**. 아래 AR-2

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 실행 `./gradlew build` BUILD SUCCESSFUL(3m 21s, 종료 0). `RealPathReflectionTest` 8건(main 7건 + `#레코드_구성_요소를_읽는_클래스는_구성_요소를_밖으로_내보내지_않는다`) 포함 |
| B2 | 통과 | 규칙 변경은 강화 방향만: `RecordComponent` 메서드를 허용 클래스 밖에서 부르면 위반(RealPathReflectionTest.java:459-465, M3로 확인), 허용 클래스 모양 고정 시험 추가(:660-693, M2로 확인). 근거 결정 D-086(:50-51, :61) 갱신. 운영 코드·모듈 의존 변경 없음 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음(diff는 테스트 2파일과 결정 문서 1개) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 실패 | 새 동작에는 시험이 있다(M2·M3). 그러나 기존 단언 하나가 의미 없게 바뀌었다: main에서 `byNameDetails`에 걸던 `doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value")`(main RealPathReflectionTest.java:794)가 이 PR에서 `SamePackageRecordReader` 하나만 가져온 `samePackage` 결과로 옮겨졌다(RealPathReflectionTest.java:841). 그 결과에는 `NameToClassBinding`이 아예 없어 항상 참이다. 대신 `byNameDetails`에 붙은 `.doesNotContain("SamePackageRecordReader")`(:834)도 그 결과에 이 클래스가 없으니 항상 참이다. M1로 실제로 약화가 생긴 것을 확인했다. 보호 테스트(D-086 규칙 고정 예제) 단언 약화다. AR-1 |
| B7 | risk-ops 검토 필요 | 보호 테스트(`RealPathReflectionTest`, 실전 경로 리플렉션 금지 규칙) 변경이라 프로젝트 지침 11장에 따라 실전 관련 PR이다. `docs/reviews/pr-110/`에 risk-ops 원문이 아직 없다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(3파일 전체 확인). 고정 예제는 리플렉션 호출만 담는다 |

## 점검표 밖 발견
- **AR-1 (수정 필요)** 위 B6. 단언이 옮겨지며 뒤바뀐 것으로 보인다. 고칠 모양: `byNameDetails` 단언 끝을 main처럼 `.doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value")`로 되돌리고, `samePackage` 단언에는 필요하면 `SamePackageRecordReader`의 허용 동작 쪽 단언을 따로 둔다(지금 `.doesNotContain("SamePackageRecordReader")`는 지워도 된다). 고친 뒤 M1 변조로 `규칙은_리플렉션_우회를_실제로_잡아낸다`가 실패하는지 확인해 결과에 적는다.
- **AR-2 (수정 필요, 문서)** 새 모양 시험은 private이 아닌 서명의 원시 타입만 본다(`getAllInvolvedRawTypes`, RealPathReflectionTest.java:682-684). 와일드카드·컨테이너로 감싼 반환(`List<?>`, `Map<String, ?>`)과 Jackson 노드에 담아 내보내기(`ObjectNode.putPOJO`, `SecretPaths.extract`가 이미 `ObjectNode`를 돌려준다)는 잡지 못한다(M4 통과). 그런데 D-086 새 줄(:51)은 "그 클래스는 private이 아닌 서명에 `Class`·`java.lang.reflect`·`Object`를 내보내지 않으며"라고 써서 클래스 객체를 내보낼 수 없다고 읽힌다. [추론] 받는 쪽이 `Object`를 `Class`로 형변환해 Jackson에 넘기는 경로는 이 PR 범위 밖 규칙이 판정하므로 지금 바로 열린 길이라고 단정하지 않는다. 다만 결정 문서가 기계 검사보다 넓게 주장하면 다음 검토가 이 문장을 믿고 `SecretPaths` 변경을 넘길 수 있다. 고칠 것(둘 중 하나, (a)가 낫다): (a) 시험에 와일드카드 경계 없는 타입 인자(`?`, `? super`)와 `JsonNode` 계열 반환도 금지로 넣거나, (b) D-086 위험과 한계에 "모양 시험은 서명의 원시 타입만 본다. 컨테이너·`JsonNode`에 담아 내보내는 길은 막지 않으므로 `SecretPaths` 변경은 이 규칙의 신뢰 경계로 risk-ops 검토 대상"을 적는다.
- **AR-3 (권고)** D-086:58의 "`RealPathReflectionTest` 7건"이 그대로다. 이 PR로 8건이 됐다(main `@Test` 7개, HEAD 8개). :61의 PR #109 후속 줄에 개수를 함께 적으면 된다.
- **AR-4 (권고, 낮음)** 모양 시험의 "구성 요소를 읽는 메서드는 private" 검사는 `getMethodCallsFromSelf`만 본다(:687-690). 메서드 참조(`Class::getRecordComponents`)는 호출이 아니라 빠진다. 본 규칙(:454-458)은 `getAccessesFromSelf`라 참조도 본다. 서명 검사가 내보내는 길을 따로 막으므로 영향은 작다. `getAccessesFromSelf`로 맞추기를 권한다.
- [확인] 고정 예제 `SamePackageRecordReader`는 `autotrade-architecture-test` 테스트 소스라 운영 범위(`ArchitectureTest.PRODUCTION`)에 들어가지 않고, 운영 코드 무위반 시험이 그대로 통과한다(B1 빌드).
- [확인 범위] 커밋 메시지·PR 본문의 "QA Q1·Q2, risk-ops R-1 반영" 서술은 사실로 받지 않았다. Q1·Q2 고정 예제가 일하는지는 M3, AR-R1이 막히는지는 M2로만 판정했다.

## 판정
수정 필요(AR-1: 옮겨진 `Holder` 오탐 방지 단언을 `byNameDetails`로 되돌리고 M1 변조로 확인, AR-2: D-086 한계 문장을 기계 검사 범위에 맞추거나 시험을 넓힘. B7 risk-ops 검토 필요)

## 재검토 2026-10-15
- 대상: PR #110 / 헤드 c3f65ac(요청 시점 헤드. 검토 중 같은 브랜치에 risk-ops 재검토 원문 커밋 933410e가 올라왔고, 이 커밋은 `docs/reviews/pr-110/risk-ops.md`만 바꿔 코드는 c3f65ac와 같다) / main 대비 diff(`git diff origin/main...HEAD`, 5파일: `RealPathReflectionTest.java`, `SamePackageRecordReader.java`(테스트 소스), `D-086-real-path-reflection-ban.md`, 검토 원문 2개). 1차 이후 코드 변경은 fd2f153(AR-1~AR-4 반영)과 c3f65ac(risk-ops F-1·F-2, AR-2 반영)이다. 운영 코드 변경은 0줄이다
- 점검표: B
- 직접 실행한 것:
  - `./gradlew clean build --no-daemon` (c3f65ac 코드): BUILD SUCCESSFUL in 2m 10s, 종료 0. `RealPathReflectionTest`는 tests=8, failures=0, errors=0, skipped=0이다
  - 변조 실험 4건(모두 되돌렸고 `git status`가 깨끗함을 확인. 위 clean build는 되돌린 뒤 돌렸다)
    - RM1: `NameToClassBinding.Holder`의 `public T value;`를 `public Class<T> value;`로 바꿈 → `규칙은_리플렉션_우회를_실제로_잡아낸다`가 실패한다(`doesNotContain "NameToClassBinding$Holder.value"`). 1차 M1과 같은 변조이고 이번에는 잡힌다. AR-1 해소
    - RM2: 모양 검사에서 서명 금지 타입 `java.lang.Object`를 빼고 `putPOJO` 검사를 끔 → `레코드_구성_요소를_읽는_클래스는_구성_요소를_밖으로_내보내지_않는다`가 실패한다(`typeName(java.lang.Object)의 서명에 java.lang.Object`, `stash(..)가 임의 객체를 JSON 노드에 넣는다: putPOJO`를 찾지 못함). 새 모양 검사에도 실패 경우 시험이 생겼다
    - RM3: `SecretPaths`에 1차 M4의 `leakNode()`(private 도우미에서 `putPOJO("t", ..getType())`)를 넣음 → 모양 시험이 실패한다("SecretPaths.leakNode0()가 임의 객체를 JSON 노드에 넣는다: putPOJO"). 1차 AR-2에서 지적한 JSON 노드 길은 이제 기계 검사로 막힌다
    - RM4: `SecretPaths`에 private `stash()`가 구성 요소 타입을 중첩 클래스 `SecretPaths.Leak.put(Object)`(내부 `ConcurrentLinkedQueue<Object>`)로 넘기게 함 → **시험 8건이 모두 통과해 검출되지 않는다**. 아래 AR-5

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 실행한 `./gradlew clean build`가 성공했다(2m 10s, 종료 0). 위 실행 기록 참고 |
| B2 | 통과 | 규칙은 강화 방향으로만 바뀌었다. `RecordComponent` 메서드를 허용 클래스 안에서만 부르게 했고(RealPathReflectionTest.java:459-465), 모양 검사 `recordReaderShape`(:709-741)를 추가했다. 그 안에 서명 원시 타입 검사, `getAccessesFromSelf` 기반 private 읽기 검사(AR-4 반영), `POJO_NODE_METHODS` 금지(:706), 클래스 파일 상수 고정 목록 `RECORD_READER_TYPES`(:689-703)가 있다. 근거 결정 D-086:51을 갱신했다. 운영 코드와 모듈 의존은 바뀌지 않았다. 변경 범위는 이 규칙이 실제로 판정하는 범위와 맞는다(RM1~RM3) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경이 없다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경이 없다 |
| B5 | 해당 없음 | 금액 계산 변경이 없다 |
| B6 | 통과 | 1차 실패 사유(AR-1)를 해소했다. `byNameDetails`에 `Holder` 오탐 방지 단언이 돌아왔고(:881), 늘 참이던 `.doesNotContain("SamePackageRecordReader")`와 `samePackage` 쪽 `doesNotContain`은 없어졌다. RM1로 다시 잡히는 것을 확인했다. 새 모양 검사는 위반 예제 `SamePackageRecordReader`(`first`, `typeName`, private `stash`)로 실패 경우를 단언하고(:671-680), RM2로 확인했다. 지우거나 건너뛴 시험은 없다(main 7건 → 8건) |
| B7 | risk-ops 검토 필요 | 보호 테스트(`RealPathReflectionTest`)를 바꾼 실전 관련 PR이다. 같은 브랜치에 `docs/reviews/pr-110/risk-ops.md`(1차와 재검토)가 있다. 그 판정 내용은 이 검토의 근거로 쓰지 않았다. 머지는 사용자가 하고, 머지 전에 독립 QA 관문을 거친다 |
| B8 | 통과 | 1차 이후 바뀐 diff를 모두 읽었고 키·계좌번호·토큰은 없다. 위반 예제에는 리플렉션 호출과 Jackson 노드 호출만 있다 |

## 점검표 밖 발견 (재검토)
- **AR-1 해소**: 위 B6과 RM1.
- **AR-2 해소**: D-086:51이 이제 검사 범위를 맞게 적는다(서명 원시 타입, 클래스 파일 상수 고정 목록, `putPOJO` 류 금지). 남는 길은 `[추론]`으로 적었고, `SecretPaths`와 `RECORD_READER_TYPES` 변경을 risk-ops가 검토할 신뢰 경계로 정했다. JSON 노드 길은 기계 검사로 막혔다(RM3). 목록 안 컬렉션을 `List<?>` 모양으로 내보내는 길은 남아 있고 문서에 적혀 있다.
- **AR-3 해소**: D-086:58이 8건으로 고쳐졌다. **AR-4 해소**: private 읽기 검사가 `getAccessesFromSelf`를 쓴다(:731-734).
- **AR-5 (권고, 낮음)** 고정 타입 목록 검사가 허용 클래스의 중첩 클래스를 뺀다(`!n.startsWith(k.getName() + "$")`, :736). 모양 검사도 `SecretPaths` 자신의 멤버에만 돈다.
  - 그래서 RM4처럼 중첩 클래스가 `Object`를 받아 목록 밖 컬렉션에 담으면, 구성 요소 타입의 클래스 객체가 `SecretPaths` 밖으로 나가도 빌드가 통과한다.
  - D-086:51의 "남는 길은 … 것이다" 목록에도 이 길이 없다.
  - 승인을 막지 않는 이유: [사실] clean build 뒤 운영 `SecretPaths`에는 중첩 클래스가 없다(`autotrade-config/build/classes/java/main/com/autotrade/config/`에 `SecretPaths.class` 하나뿐). 이 길을 열려면 `SecretPaths.java`를 고쳐야 하고, D-086이 그 변경을 신뢰 경계로 보고 risk-ops 검토 대상으로 못박았다. [추론] `SecretPaths`가 package-private이라, 받는 쪽도 `com.autotrade.config` 패키지 안에 있어야 한다.
  - 권고: (a) `recordReaderShape`를 `ALLOWED_RECORD_READERS` 클래스의 중첩 클래스에도 적용하거나 중첩 클래스 자체를 금지한다(한 줄 수준의 변경). 또는 (b) D-086 남는 길 목록에 "중첩 클래스 칸"을 더한다. 기계 검사인 (a)가 낫다. 다음 `SecretPaths`·D-086 변경 때 함께 처리해도 된다.
- 참고(1차 R-2와 같은 성격, 권고 유지): `ParameterizedType#getActualTypeArguments`는 허용 클래스 밖에서도 막지 않는다. 실전 경로에서 `ParameterizedType`을 얻는 길이 `RecordComponent#getGenericType`(이제 `SecretPaths` 안에서만)과 소스에 적힌 `TypeReference`뿐이라, 새로 열린 길은 없다고 본다[추론].
- [확인 범위] 커밋 메시지의 "AR-1~AR-4 반영", "F-1, F-2, AR-2 반영"은 사실로 받지 않았다. 해소 여부는 위 diff 읽기, RM1~RM3, clean build로만 판정했다.
- [작업 기록] 검토 중 같은 브랜치 ref가 다른 세션의 커밋으로 네 번 앞으로 갔다(fd2f153, a5cab4d, c3f65ac, 933410e). 그때 이 작업 트리의 파일이 ref보다 뒤처졌다. 그래서 매번 ref 기준으로 파일을 맞춘 뒤 다시 실행했다. 판정에 쓴 clean build와 RM1~RM4는 모두 c3f65ac 코드에서 돌렸다.

## 판정 (재검토)
승인 (1차 AR-1·AR-2 해소, AR-3·AR-4 반영. 권고 AR-5(허용 클래스의 중첩 클래스를 모양 검사에 포함)와 R-2 성격 권고는 머지를 막지 않는다. 보호 테스트 변경이라 risk-ops 검토, 사용자 머지, 머지 전 독립 QA 관문 필요)
