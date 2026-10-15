# architect 검토: PR #108 독립 QA 재검토 R7 후속(레코드 구성 요소 읽기는 SecretPaths만)
- 대상: PR #109 / 커밋 79f6d51 / main 대비 diff(`git diff origin/main...79f6d51`, merge-base 711b027, 5개 파일 +37 −3)
- 점검표: B
- 직접 실행한 것(별도 worktree `/tmp/claude-0/wt-architect-109`, 깨끗한 체크아웃):
  - `./gradlew build --no-daemon` → `BUILD SUCCESSFUL in 3m 8s`, 종료 코드 0. 결과 XML 합계 테스트 1,130건, 실패·오류·건너뜀 0(`RealPathReflectionTest` 7건 실패 0).
  - 변조 실험 V1~V4(아래). 각 실험 뒤 `git checkout`/파일 삭제로 원복했고 `git status` 깨끗함을 확인했다.
  - 허용 목록 안 타입에서 `Class`를 돌려주는 공개 메서드 전수 조사: 테스트 소스의 `ALLOWED_JDK_CLASSES`·`JACKSON_ALLOWLIST`·`ALLOWED_REFLECT_TYPES`와 `ALLOWED_JDK_PACKAGES`의 모든 JDK 클래스(jrt)를 Jackson 2.21.4 클래스패스에서 읽어 반환 제네릭 타입에 `java.lang.Class`가 나오는 공개 메서드를 나열(조사 프로그램은 저장소 밖 임시 파일).

## 변조 실험
| # | 변조 | 결과 |
|---|---|---|
| V1 | `classAsData`에서 이번 PR이 넣은 두 검사(레코드 구성 요소 읽기, `RecordComponent` 메서드) 삭제 | `규칙은_리플렉션_우회를_실제로_잡아낸다` FAILED. 새 고정 예제 `NameToClassBinding.componentType`·`declaringRecord`는 새 검사로만 잡힌다(규칙이 실제로 문다) |
| V2 | 실전 범위 운영 클래스(`autotrade-app-engine`의 `ZzProbeV17`)에 QA V17 재현: `record Holder(GuardTransition t)`, `Holder.class.getRecordComponents()[0].getType()` | `실전_경로_모듈은_리플렉션을_쓰지_않는다` FAILED, "허용 목록 밖 레코드 구성 요소 읽기 ... direct" |
| V3 | 같은 클래스에서 메서드 참조 `Class::getRecordComponents`, 람다 `k -> k.getRecordComponents()`, `RecordComponent::getDeclaringRecord` 메서드 참조 | 셋 다 검출("references method"/"calls method", 람다 본문은 바깥 메서드 `viaLambda`로 귀속) |
| V4 | 허용된 `SecretPaths` 자체에 `static Object bind(Class<?> r, Map f, ObjectMapper m) { return m.convertValue(f, r.getRecordComponents()[0].getType()); }` 추가 | 아키텍처 테스트 BUILD SUCCESSFUL(검출 안 됨). 아래 AR-R1 |
| V5 | 같은 프로브에서 `GuardState.values()[0].getDeclaringClass()`(리터럴 없이 Guard 패키지 enum의 클래스 객체) | 검출 안 됨. 아래 AR-R2 |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, 종료 코드 0, 테스트 1,130건 실패·오류·건너뜀 0 |
| B2 | 통과 | 운영 모듈 의존 방향 변경 없음(diff는 `autotrade-architecture-test` 테스트 소스와 문서뿐). 규칙 강화가 ADR-002 9.5 6항(docs/adr/ADR-002-real-trading-guard.md:239)과 D-086 1번(docs/decisions/D-086-real-path-reflection-ban.md:23)에 반영됨. 새 검사는 실전 범위 규칙 `noReflectionIn`의 `classAsData` 조건 안(RealPathReflectionTest.java:454-462, 조건 연결 :483-488)이라 적용 범위가 V17의 대상(엔진 레코드)과 같다 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 바뀐 것은 보호 테스트 규칙과 그 결정 문서(ADR-002·D-086 갱신 있음) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 위반 고정 예제 2건(NameToClassBinding.java:96-104)과 기대 문자열(RealPathReflectionTest.java:792-793), 허용 목록 고정 단언(RealPathReflectionTest.java:663-664) 추가. V1로 새 예제가 새 검사에만 걸림을 확인. 지우거나 건너뛴 테스트 없음(테스트 수 1,130으로 PR #108 QA 재검토 기록과 같음, 단언만 추가) |
| B7 | 통과(risk-ops 검토 필요) | 실전 경로 리플렉션 금지 규칙(보호 테스트 `RealPathReflectionTest`) 변경이다. 강화 방향이지만 프로젝트 지침 11장 보호 테스트 항목과 D-086에 따라 실전 관련 PR로 다루므로 risk-ops A 점검표 검토와 사용자 머지가 필요하다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(추가 줄에서 key/token/account/secret 검색 결과는 클래스 이름 `SecretPaths`와 설명문뿐) |

## 점검표 밖 발견
- [사실] 운영 코드에서 `getRecordComponents`/`RecordComponent`를 쓰는 곳은 `SecretPaths` 하나(autotrade-config/src/main/java/com/autotrade/config/SecretPaths.java:221,240)이고, 부르는 `RecordComponent` 메서드는 `getName`·`getType`·`getGenericType`뿐이라 새 허용 목록과 정확히 맞는다. `SecretPaths`는 패키지 전용 클래스이고, 레코드를 읽는 `schema`·`collect`는 `private`이며 입력은 `RuntimeConfig.class` 고정(:30-32), 외부로 `Class`·`RecordComponent`·`Type`을 내보내는 메서드가 없다.
- [사실] `ParameterizedType`·`Type`은 허용 목록 타입이지만 이를 얻는 길(`RecordComponent#getGenericType`, `Class#getGeneric*`)이 각각 `SecretPaths` 한정, 목록 밖 메서드라 다른 클래스는 `getActualTypeArguments`로 클래스 객체에 닿을 수 없다.
- **AR-R1 (권고, 낮음)** 허용 목록 신뢰 경계: 허용은 클래스 단위라 `SecretPaths` 안의 코드 모양은 고정되지 않는다(V4). `SecretPaths`에 임의 `Class` 인자를 받아 구성 요소 타입으로 바인딩하는 메서드를 넣으면, 엔진 쪽은 Guard 클래스 리터럴 없이(자기 레코드 리터럴만 넘겨) R7 길을 다시 연다. 이 변경은 보호 테스트 diff에 나타나지 않는다. 다른 허용 목록(`ALLOWED_SERVICE_LOADERS` 등)과 같은 성격이라 차단 사유로 보지 않는다. 권고: (a) `SecretPaths`에서 `getRecordComponents`를 부르는 메서드는 `private`이고, `private`이 아닌 메서드의 서명에 `Class`·`RecordComponent`·`Type`이 없음을 단언하거나, (b) D-086 위험과 한계에 "`SecretPaths` 변경은 이 규칙의 신뢰 경계라 risk-ops 검토 대상"을 한 줄 적는다. (a)가 기계 검사라 낫다.
- **AR-R2 (권고, 낮음, 기록용)** 허용 목록 안에서 리터럴 없이 클래스 객체를 주는 메서드가 더 있다(전수 조사 결과): `java.lang.Enum#getDeclaringClass`(모든 enum, Guard 패키지 `GuardState`·`ActivationStep` 포함, V5), `Annotation#annotationType`(`JsonCreator`·`JsonIgnore`·`FunctionalInterface`), `JsonSerializer`/`StdSerializer#handledType`, `ObjectMapper#findMixInClassFor`, `SerializerProvider#getActiveView`, `WatchEvent.Kind#type`. [추론] 지금은 위험하지 않다: enum 클래스 객체로 Jackson이 만들 수 있는 것은 이미 공개된 enum 상수뿐이고, 그 클래스 객체에서 부를 수 있는 `Class` 메서드는 허용 목록(`getRecordComponents`는 이번 PR로 `SecretPaths` 한정)뿐이며, 나머지는 Guard 타입을 돌려줄 등록·주석이 운영 코드에 없다. 다만 D-086 위험과 한계에 이 목록을 적어 두면, 허용 목록을 늘리거나 Guard 패키지에 enum 아닌 타입이 생길 때 다시 볼 기준이 된다. `GUARD_TYPES_ARE_NOT_DATA` 설명(RealPathReflectionTest.java:560-563)의 "이것이 없으면 ... 새로 만들 수 없다"는 문장도 QA R7이 지적한 그대로 남아 있으니 "실전 범위에서는 레코드 구성 요소 읽기를 `SecretPaths`로 한정한다"를 덧붙이길 권한다.
- [확인 범위] `docs/qa-out-status.md`의 사용자 지시 인용("머지하십시오", 스레드 시각)은 저장소 밖 사실이라 확인하지 않았다. 판정에 영향 없는 기록 줄이다. 머지 커밋 `711b027`이 이 PR의 merge-base인 것은 확인했다.

## 판정
승인 (권고 AR-R1·AR-R2. 보호 테스트 변경이라 risk-ops 검토와 사용자 머지 필요)
