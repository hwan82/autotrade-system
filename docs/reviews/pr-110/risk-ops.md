# risk-ops 검토: PR #109 후속 — 레코드 구성 요소 읽기 허용 클래스 모양 고정과 남은 한계 기록
- 대상: PR #110 / 커밋 c9dacca / main(d0e9f3f) 대비 diff (`git diff origin/main...c9dacca`, 파일 3개 +75 -3: `RealPathReflectionTest.java`, 새 고정 예제 `com/autotrade/config/SamePackageRecordReader.java`(테스트 소스), `D-086-real-path-reflection-ban.md`). 운영 코드 변경 0줄
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (c9dacca, 캐시 없는 worktree): BUILD SUCCESSFUL. 테스트 1,141건, 실패·오류·건너뜀 0. 그중 `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 41건(`RealPathReflectionTest` 8건 포함), `:autotrade-app-engine:test` 50건 모두 통과
  - 변조 실험(모두 `:autotrade-architecture-test:test --tests RealPathReflectionTest --rerun-tasks`, 실험 뒤 `git checkout`/파일 삭제로 되돌리고 `git status` 깨끗함 확인)
    - M1: 새 Q1 조건(`|| !ALLOWED_RECORD_READERS.contains(..)`, RealPathReflectionTest.java:464)을 지움 → `규칙은_리플렉션_우회를_실제로_잡아낸다` 실패(`SamePackageRecordReader.typeName` 기대 문자열). 약화가 잡힌다
    - M2: `getRecordComponents` 허용 판정을 "호출 클래스 패키지 + `.SecretPaths`"(= `com.autotrade.config` 패키지 전체)로 바꿈(QA PR #109 M4와 같은 변이) → 같은 시험 실패. QA Q2 해소 확인
    - M3: `classAsData` 필드 검사에 "타입 변수 필드도 `Class`로 보고"를 넣어 규칙을 과잉 차단으로 바꿈 → **PR 코드 그대로면 8건 모두 통과(검출 안 됨)**. 같은 변조에 main 위치의 단언(`byNameDetails`에 `doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value")`)을 되살리면 `규칙은_리플렉션_우회를_실제로_잡아낸다` 실패("NameToClassBinding$Holder.value 필드 타입에 Class"). 아래 F-1
    - P1: `SecretPaths`에 raw `List`를 돌려주는 `leakTypes()`·`leakGeneric()`(private 도우미가 `getType()`·`getGenericType()` 결과를 담음) 추가 → 새 모양 시험이 "서명에 java.lang.Object"로 잡고, private 도우미의 `Class` 반환도 기존 규칙이 잡음(양성 대조). 다만 같은 실행에서 탐침(`com.autotrade.config.QaProbe110`)의 `(ParameterizedType) .. .getActualTypeArguments()[1]` → `(Class<?>)` → `convertValue`는 보고되지 않음(위반 1건만 보고). 아래 R-2
    - P4: `SecretPaths`의 `static {}` → private `void publish()`가 `RuntimeConfig.class.getRecordComponents()[0].getType()`을 **다른 클래스의** `static final List<Object> SINK`(`QaSink110`)에 넣고, 탐침이 `(Class<?>) QaSink110.SINK.get(0)`을 `ObjectMapper.convertValue`에 넘김 → **8건 모두 통과(검출 안 됨)**. `SecretPaths`의 private이 아닌 서명은 깨끗하므로 새 모양 시험이 보지 못한다. 아래 F-2

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff 파일 목록). `RealTradingGuard` 기본 OFF·재기동 OFF 시험은 `:autotrade-risk:test` 55건 통과로 그대로 |
| A2 | 통과 | `ArchitectureTest` 변경 없음(diff 파일 목록). 이번 변경은 `RealPathReflectionTest.classAsData`의 `RecordComponent` 메서드 검사를 허용 클래스 안으로 좁히는 강화(RealPathReflectionTest.java:459-465)와 새 모양 시험(:666-693)이다. M1·M2로 강화가 시험에 고정됐음을 확인 |
| A3 | 해당 없음 | 주문·증권사 연동 코드 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 코드 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·자원 정지 순서 변경 없음. 새 고정 예제는 테스트 소스라 런타임 구성에 들어가지 않는다(`실전_경로_런타임_구성은_고정_목록과_같다` 통과) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 바뀐 3개 파일 diff 전체를 읽음. 키·계좌번호·토큰 없음. gitleaks는 CI 몫이라 로컬에서는 diff 눈 확인 |
| A11 | 실패 | (1) 보호 테스트 단언 약화: main의 `byNameDetails` 단언 끝 `.doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value")`(main RealPathReflectionTest.java:794)가 `SamePackageRecordReader`만 가져온 `samePackage` 결과로 옮겨졌다(RealPathReflectionTest.java:841). 그 결과에는 `NameToClassBinding`이 없으므로 늘 참이고, 대신 붙은 `byNameDetails`의 `.doesNotContain("SamePackageRecordReader")`(:834)도 그 결과에 이 클래스가 없으니 늘 참이다. M3로 실제 약화를 재현했다(F-1). (2) 새 모양 시험 `레코드_구성_요소를_읽는_클래스는_구성_요소를_밖으로_내보내지_않는다`(:666)는 운영 `SecretPaths`에만 돌고, 위반하는 클래스로 실패하는지 보는 고정 예제가 없다. 이 시험의 검사를 약화(예: `Object` 빼기)해도 잡을 시험이 없다. 새 동작에 실패 경우 시험이 없다는 A11 요건 미충족(F-1). Q1 조건(:464)과 Q2 패키지 판정은 M1·M2로 고정 확인 |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음. 실전 경로 정적 검사만 바뀜 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 실패 | D-086 새 줄(D-086-real-path-reflection-ban.md:51)은 "그 클래스는 private이 아닌 서명에 `Class`·`java.lang.reflect`·`Object`를 내보내지 않으며"로 서명 검사 범위를 맞게 적었지만, 시험 이름·설명(RealPathReflectionTest.java:660-667 "구성 요소·클래스 객체·리플렉션 타입을 밖으로 내보내지 않는다")과 D-086 같은 줄의 목적은 "밖으로 내보낼 수 없다"로 읽힌다. P4(다른 클래스의 `Object` 칸에 넣기)는 서명 밖 경로라 잡히지 않고, D-086 위험과 한계에 이 한계가 없다(F-2). 또 D-086 검증 절(:58)은 `RealPathReflectionTest` 7건이라 적었으나 지금 8건이다(main 7건 + 이 PR 1건) |

## 점검표 밖 발견
- **F-1 (수정 필요, A11)** 보호 테스트 단언 위치 복원과 새 모양 시험의 실패 경우 시험.
  - `byNameDetails` 단언 끝을 main처럼 `.doesNotContain("NameToClassBinding$Holder의", "NameToClassBinding$Holder.value")`로 되돌린다. `samePackage` 쪽 `.doesNotContain(...)`과 `byNameDetails`의 `.doesNotContain("SamePackageRecordReader")`는 늘 참이라 의미가 없다(지우거나 의미 있는 단언으로 바꾼다).
  - 모양 검사를 `static List<String> recordReaderShape(JavaClass)` 같은 함수로 빼고, 운영 `SecretPaths`에는 빈 결과를, 위반 예제(이미 있는 `SamePackageRecordReader`가 `public RecordComponent first(Class<?>)`·`public String typeName(Object)`와 private 아닌 `getRecordComponents` 호출을 갖고 있어 그대로 쓸 수 있다)에는 "서명에 java.lang.reflect.RecordComponent", "서명에 java.lang.Object", "private이 아닌데 구성 요소를 읽는다"가 나오는지 단언한다.
  - 고친 뒤 M3 변조로 `규칙은_리플렉션_우회를_실제로_잡아낸다`가 실패하는지, 모양 검사에서 `Object`를 빼는 변조로 새 단언이 실패하는지 확인해 PR에 적는다.
- **F-2 (수정 필요, A14) 모양 시험은 서명만 본다. 결정 문서와 시험 설명을 기계 검사 범위에 맞춘다.** P4처럼 `SecretPaths`가 private 메서드 안에서 구성 요소 타입의 클래스 객체를 다른 클래스의 `Object` 칸(필드·컬렉션)에 넣으면, 받는 쪽은 `(Class<?>)` 형변환 뒤 Jackson에 넘길 수 있고 빌드가 통과한다. `SecretPaths.extract`가 이미 `ObjectNode`를 돌려주므로 `putPOJO`로 담아 내보내는 길도 같은 성격이다[추론, 직접 실행하지 않음]. 지금 운영 `SecretPaths`에 이런 코드는 없어 열린 길은 없고(빌드 위반 0건, `SecretPaths.java:219-256`은 경로 문자열만 모은다), 길을 열려면 `SecretPaths`를 고쳐야 한다. 고칠 것(둘 중 하나, 앞이 낫다):
  - (a) 시험을 넓힌다: `SecretPaths`가 접근하는 `com.autotrade` 타입을 고정 목록(자기 자신, `RuntimeConfig`·`Secret` 등 지금 쓰는 것)으로 묶고, Jackson 노드에 객체를 담는 메서드(`putPOJO`, `POJONode`, `pojoNode`) 호출을 금지한다. P4 모양 고정 예제를 함께 둔다.
  - (b) D-086 위험과 한계에 "모양 시험은 private이 아닌 서명만 본다. 다른 클래스의 칸이나 JSON 노드에 담아 내보내는 길은 막지 않으므로 `SecretPaths` 변경은 이 규칙의 신뢰 경계이고 risk-ops 검토 대상"을 적고, 시험 이름·설명을 "서명으로 내보내지 않는다"로 고친다.
  - 같이 D-086 검증 절의 `RealPathReflectionTest` 건수를 8건으로 고친다.
- **R-2 (권고, 낮음) `ParameterizedType#getActualTypeArguments`는 여전히 어디서나 허용.** 독립 QA PR #109 Q1은 이 메서드도 허용 클래스 안으로 묶기를 권했다. 이 PR은 `RecordComponent` 메서드만 묶었다. P1에서 탐침의 `getActualTypeArguments()[1]` → `(Class<?>)` → `convertValue`가 보고되지 않았다. 다만 실전 경로에서 `ParameterizedType`을 얻는 길은 `RecordComponent#getGenericType`(이제 `SecretPaths` 안만)과 소스에 타입을 적는 `TypeReference#getType`(Guard 타입은 `GUARD_TYPES_ARE_NOT_DATA`가 제네릭 상위 타입 인자로 잡음)뿐이라 지금 새 길은 없다고 본다[추론]. F-1·F-2를 고치는 김에 `java.lang.reflect.ParameterizedType`·`Type` 메서드 호출도 `ALLOWED_RECORD_READERS`로 묶기를 권한다.
- 참고: 메서드 서명 검사(`getAllInvolvedRawTypes`)는 raw `List` 반환도 원소 타입 변수 경계(`Object`)로 잡는다(P1). 와일드카드 `List<?>`가 빠지는지는 직접 실행하지 않았다.
- 참고: 이 검토 중 같은 브랜치에 architect 검토 원문 커밋(8a90ecb)과 수정 커밋 fd2f153("PR #110 architect 검토 반영(AR-1~AR-4)")이 올라왔다. 이 판정은 요청받은 c9dacca 코드와 위 실행 결과로만 했고, fd2f153은 검토하지 않았다. F-1·F-2 해소 여부는 재검토에서 fd2f153 이후 헤드로 판정한다.
- 참고: 실전 관련 PR(보호 테스트 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 관문이다. 이 PR 브랜치에 `docs/qa/pending.md` 요청 줄은 아직 없다(diff 파일 목록).

## 판정
수정 필요(A11·F-1: 옮겨진 `Holder` 오탐 방지 단언 복원과 새 모양 시험의 위반 고정 예제, A14·F-2: 모양 시험이 서명만 본다는 한계를 시험 확대나 D-086·시험 설명으로 맞추고 D-086 시험 건수 정정. 권고 R-2. 보호 테스트 변경이라 머지는 사용자)

## 재검토 2026-10-15
- 대상: PR #110 / 헤드 c3f65ac / main(d0e9f3f) 대비 diff (`git diff origin/main...c3f65ac`). 1차(c9dacca) 이후 코드 변경은 fd2f153(architect 반영)과 c3f65ac(risk-ops 반영) 두 커밋으로, `RealPathReflectionTest.java`, `SamePackageRecordReader.java`, `D-086-real-path-reflection-ban.md`를 고쳤다. 운영 코드 변경은 여전히 0줄이다
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (c3f65ac): BUILD SUCCESSFUL
  - `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test --rerun-tasks`: 성공. 모듈별로 risk 55건, architecture-test 41건(`RealPathReflectionTest` 8건 포함), app-engine 50건이고 실패는 0이다. 전체 결과 파일 합계는 1,141건이며 실패·오류·건너뜀은 0이다
  - 변조 실험(실험 뒤 모두 되돌렸고 `git status`가 깨끗함을 확인)
    - M3 재실행: `classAsData`가 타입 변수 필드도 `Class`로 보고하는 과잉 차단을 넣음 → `규칙은_리플렉션_우회를_실제로_잡아낸다`가 실패해 잡힌다("NameToClassBinding$Holder.value 필드 타입에 Class"). F-1의 단언 복원이 확인됐다
    - MS: 모양 검사에서 서명 금지 타입 `java.lang.Object`를 빼고 `putPOJO` 검사를 끔 → `레코드_구성_요소를_읽는_클래스는_구성_요소를_밖으로_내보내지_않는다`가 실패해 잡힌다(위반 예제의 `typeName(Object)`, `stash .. putPOJO` 기대 문자열). 새 모양 검사에 실패 경우 시험이 생겼다
    - P4 재실행: 1차와 같은 탐침이다. `SecretPaths`가 private `publish()`로 구성 요소 타입을 같은 패키지 다른 클래스(`QaSink110`)의 `List<Object>`에 넣고, 탐침이 그것을 `(Class<?>)`로 바꿔 `convertValue`에 넘김 → 모양 시험이 실패해 잡힌다("SecretPaths가 고정 목록 밖 타입 com.autotrade.config.QaSink110를 쓴다")
    - P5(새 변형): P4와 같지만 칸을 `SecretPaths`의 중첩 클래스 `SecretPaths.Sink`(`static final List<Object> ITEMS`)에 둠 → **시험 8건이 모두 통과해 검출되지 않는다**. 아래 R-3

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff 파일 목록). `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. `RecordComponent` 메서드를 허용 클래스 안으로 좁힌 검사(RealPathReflectionTest.java:459-465)와 모양 시험(:666-741)은 강화뿐이다. 1차 M1·M2와 이번 M3·MS로 시험에 고정됐음을 확인 |
| A3 | 해당 없음 | 주문·증권사 연동 코드 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 코드 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·자원 정지 순서 변경 없음. 위반 예제 `SamePackageRecordReader`는 테스트 소스라 `실전_경로_런타임_구성은_고정_목록과_같다`가 통과한다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 1차 이후 바뀐 diff 전체를 읽었고 키·계좌번호·토큰은 없다. gitleaks는 CI 몫이다 |
| A11 | 통과 | 1차 F-1 해소. (1) `Holder` 오탐 방지 단언이 `byNameDetails`로 돌아왔다(RealPathReflectionTest.java:881). 늘 참이던 `.doesNotContain("SamePackageRecordReader")`는 없어졌고, M3로 다시 잡힘을 확인했다. (2) 모양 검사를 `recordReaderShape`(:709)로 분리했고, 위반 예제(`SamePackageRecordReader.first`·`typeName`·`stash`)로 서명·private 읽기·`putPOJO`·고정 목록 밖 타입이 보고되는지 단언한다(:671-680). MS로 약화가 잡힘을 확인했다. 지워지거나 비활성화된 보호 테스트는 없다 |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | 1차 F-2 해소. D-086(D-086-real-path-reflection-ban.md:51)은 이제 검사 범위를 "private이 아닌 서명의 원시 타입 + 클래스 파일 상수 고정 목록 `RECORD_READER_TYPES` + `putPOJO` 류 금지"로 맞게 적는다. 남는 길을 `[추론]`으로 적고, `SecretPaths`와 `RECORD_READER_TYPES` 변경을 "신뢰 경계로 risk-ops 검토 대상"으로 못박았다. 시험 상수(RealPathReflectionTest.java:689-703)와 문서가 일치한다. 검증 절의 건수는 8건으로 고쳐졌다(:58). 다만 남는 길 목록에 P5(중첩 클래스 칸)가 빠졌다. 문서가 정한 신뢰 경계 안의 길이라 실패로 보지 않고 R-3 권고로 둔다 |

## 점검표 밖 발견 (재검토)
- **R-3 (권고, 낮음) 고정 타입 목록 검사가 `SecretPaths`의 중첩 클래스를 통째로 뺀다.** `recordReaderShape`는 `k.getName() + "$"`로 시작하는 타입을 목록 검사에서 빼고(RealPathReflectionTest.java:736), 모양 검사는 `SecretPaths` 자신의 멤버에만 돈다. 그래서 P5처럼 중첩 클래스에 `List<Object>` 칸을 두면, 그 칸을 거쳐 구성 요소 타입의 클래스 객체가 다른 클래스로 나가고 빌드가 통과한다. 지금 운영 `SecretPaths`에는 중첩 클래스가 없어 열린 길은 없다. 이 길을 열려면 `SecretPaths`를 고쳐야 하는데, D-086은 그 변경을 risk-ops 검토 대상으로 정했다. 다음 중 하나를 권한다.
  - (a) `ALLOWED_RECORD_READERS` 클래스의 중첩 클래스(람다는 자기 클래스 안이라 해당 없음)에도 `recordReaderShape`를 적용하거나, 중첩 클래스 자체를 금지한다.
  - (b) D-086 남는 길 목록에 "중첩 클래스 칸"을 더한다.
  - 기계 검사인 (a)가 낫다.
- **R-2 (권고, 1차에서 이어짐, 낮음)** `ParameterizedType#getActualTypeArguments`·`Type` 메서드 호출은 여전히 허용 클래스 밖에서도 막지 않는다. 지금 새로 열린 길은 없다는 1차 추론이 그대로다.
- 참고: 다른 리뷰어인 architect의 원문(`docs/reviews/pr-110/architect.md`)은 판정에 쓰지 않았다. 이 판정은 c3f65ac 코드와 위 실행 결과에만 근거한다.
- 참고: 실전 관련 PR(보호 테스트 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 관문이다. 이 브랜치의 `docs/qa/pending.md`에 요청 줄은 아직 없다(diff 파일 목록).

## 판정 (재검토)
승인 (1차 F-1·F-2 해소. 권고 R-2·R-3은 머지를 막지 않는다. 보호 테스트 변경이라 머지는 사용자가 하고, 머지 전 독립 QA 관문)
