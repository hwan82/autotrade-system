# 독립 QA 검토: PR #109 PR #108 QA 권고 R7: 레코드 구성 요소 읽기를 SecretPaths만 허용
- 대상: PR #109 / 헤드 16b13b5 (merge-base 711b027) / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 8개 +110 −3). 검토 중 main이 460cad2(#105 머지)로 움직여 그 기준 병합도 확인
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 테스트 `RealPathReflectionTest` 변경)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 16b13b5): Maven Central 429로 4회 실패 뒤 5회째 `BUILD SUCCESSFUL`. 결과 XML 합계 1,130건, 실패·오류·건너뜀 0
  - 병합 확인: `git merge-tree --write-tree origin/main(460cad2) HEAD` → **`CONFLICT (content): Merge conflict in docs/qa/pending.md`**. 별도 worktree에서 두 줄 모두 남기는 방식으로 풀어 `./gradlew build` 재실행: 1회째 `BUILD SUCCESSFUL`, 1,140건(#105 테스트 10건 포함) 실패·오류·건너뜀 0. 코드 쪽 병합 문제는 없고 충돌은 문서 표 한 곳뿐
  - 우회 탐침(테스트 소스 `com.autotrade.app.engine.qaprobe109.QaR7Probe`를 `noReflectionIn(IN_REAL_PATH_PACKAGE)`로 평가, 실험 뒤 삭제·`git status` 깨끗함 확인)
  - 변이 시험 M1~M4(`RealPathReflectionTest`만 고쳐 `--tests '*RealPathReflectionTest'`, 각 실험 뒤 `git checkout`)
  - 허용 클래스 누출 탐침 L1·L2(`SecretPaths`에 메서드 하나 + `com.autotrade.config.QaLeak109` 추가, 실험 뒤 되돌림)

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| E1 | 엔진 패키지 `record Holder(GuardTransition t)`, `Holder.class.getRecordComponents()[0].getType()` → `convertValue` (QA R7 V17 모양) | 잡힘 "허용 목록 밖 레코드 구성 요소 읽기 ... calls method Class.getRecordComponents()" |
| E2 | 메서드 참조 `Class::getRecordComponents` | 잡힘 ("references method") |
| E3 | 람다 `k -> k.getRecordComponents()[0].getType()` | 잡힘 (바깥 메서드 `e3Lambda`로 귀속) |
| E4 | 메서드 참조 `RecordComponent::getDeclaringRecord` | 잡힘 "허용 목록 밖 RecordComponent 메서드" |
| E5 | `RecordComponent#getAccessor()` | 잡힘 (RecordComponent 메서드 목록 밖 + 반환 타입 `java.lang.reflect.Method` 허용 목록 밖, 두 번) |
| E6 | `GuardState.values()[0].getDeclaringClass()` → `convertValue("OPEN", k)` | **안 잡힘**(실전 범위 규칙). 아래 R2 |
| E7 | 익명 클래스 `new Object(){ go() { Holder.class.getRecordComponents()... } }` | 잡힘 (`QaR7Probe$1.go()`, 허용 이름은 정확히 `SecretPaths`라 중첩 클래스도 막힌다) |
| L1 | `SecretPaths`에 `static Class<?> qaComponentType(Class<?> r, int i)`(구성 요소 타입 반환), 같은 패키지 `QaLeak109`가 받아 `convertValue` | 잡힘 "SecretPaths.qaComponentType(java.lang.Class, int) 반환 타입에 Class" (기존 서명 규칙) |
| L2 | `SecretPaths`가 `RecordComponent`를 `Object`로 돌려주고 `QaLeak109`가 `(RecordComponent)` 형변환 뒤 `getType()` → `convertValue` | **안 잡힘, BUILD SUCCESSFUL**. 아래 Q1 |
| M1 | 레코드 읽기 검사 조건을 `&& false`로 | `규칙은_리플렉션_우회를_실제로_잡아낸다` FAILED (잡힘) |
| M2 | `ALLOWED_RECORD_COMPONENT_METHODS`에 `getDeclaringRecord` 추가 | 같은 시험 FAILED (잡힘) |
| M3 | `ALLOWED_RECORD_READERS`에 `NameToClassBinding` 추가 | 같은 시험 + `실전_경로_런타임_구성은_고정_목록과_같다` FAILED (잡힘) |
| M4 | 허용 판정을 "호출 클래스 패키지 + `.SecretPaths`가 목록에 있음"으로(= `com.autotrade.config` 패키지 전체 허용) | **살아남음**(전 시험 통과). 아래 Q2 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff는 테스트 2개·문서 6개). RealTradingGuard 관련 시험은 전체 빌드 1,130건에 포함돼 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. `RealPathReflectionTest.classAsData`에 검사 2개 추가(RealPathReflectionTest.java:454-462)는 강화뿐이고, 기존 검사·단언은 그대로다(diff에 삭제 줄은 기대 문자열 목록 끝 `)` 한 줄과 문서 2줄 교체뿐). 탐침 E1~E5·E7로 실제 우회 모양에서 문다 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음(`SecretPaths`는 클래스 이름). CI `Secret scan and collector boundary` success(헤드 16b13b5) |
| A11 | 통과 | 보호 테스트 삭제·약화 0건. 새 동작에 위반 고정 예제 `NameToClassBinding.componentType`·`declaringRecord`(NameToClassBinding.java:96-104)와 기대 문자열(RealPathReflectionTest.java:792-793), 허용 목록 고정 단언(:663-664)이 있다. M1~M3로 규칙 약화가 시험에서 잡힘을 확인. M4는 살아남음(Q2, 권고) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음. 정적 검사 강화뿐이라 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | **실패** | PR 헤드 16b13b5는 리뷰 시작 시점 main(711b027)을 포함하지만, 검토 중 main이 460cad2(#105 머지)로 움직였고 `git merge-tree` 결과 **`docs/qa/pending.md` 충돌**(main은 #105 두 줄, 이 PR은 #109 한 줄을 같은 표 끝에 추가). 이 저장소 QA는 main 충돌을 A14 실패로 다뤄 왔다(#43·#91·#105). 내용 쪽은 일치: D-086 1번(D-086-real-path-reflection-ban.md:23)·ADR-002 9.5 6항(:239)이 "레코드 구성 요소 읽기는 `SecretPaths`만, `RecordComponent`는 `getName`·`getType`·`getGenericType`만"으로 적고 시험 상수(RealPathReflectionTest.java:401,404)와 같다. 운영 코드의 `getRecordComponents`·`RecordComponent` 사용은 `SecretPaths.java` 안뿐(grep, 다른 운영 파일 0건) |
| B1 | 통과 | 헤드 16b13b5 `./gradlew build` 직접 실행 성공(1,130건 실패 0). CI `Gradle build`·`DB schema`·`Secret scan` 모두 success. main 병합본: 충돌을 풀면 1,140건 통과(QA 시험 병합, 커밋하지 않음) |
| B6 | 통과 | 새 검사 2개마다 위반 고정 예제와 기대 문자열이 있고 허용 목록 크기를 단언한다. 지우거나 건너뛴 테스트 없음(1,130건, PR #108 재검토 때와 같은 수, 단언만 추가) |

## 과금 검사 (QA 지침 8장)
해당 없음. 유료 API·서비스 호출을 더하거나 바꾸는 코드가 없다(시험 코드와 문서뿐).

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **엔진 코드가 리터럴 없이 Guard 타입 클래스 객체를 얻어 Jackson으로 만든다(R7 원형).** E1~E3·E7 모두 빌드 실패로 막힌다. 이 PR의 목적은 달성했다.
2. **허용 클래스 `SecretPaths`가 구성 요소를 밖으로 넘긴다.** 반환 타입이 `Class`면 기존 서명 규칙이 막지만(L1), `Object`·`RecordComponent[]`로 넘기면 받는 쪽의 `RecordComponent#getType()`은 어디서나 허용이라 통과한다(L2, risk-ops V2와 같은 결론). 지금 `SecretPaths`는 `private` 메서드에서 `RuntimeConfig.class`만 읽고 `Set<String>`·`List<String>`·`ObjectNode`만 돌려주므로 열린 길은 없다. 열려면 `autotrade-config` 운영 코드를 고쳐야 하고, 그 diff는 보호 테스트 diff가 아니라 리뷰에서 보호 테스트 변경으로 걸리지 않는다는 점이 남은 위험이다 `[추론]`.
3. **enum `getDeclaringClass()`로 Guard 패키지 클래스 객체를 얻는다.** E6은 통과하지만 Jackson이 만들 수 있는 것은 이미 공개된 `GuardState` 상수뿐이라 보호 공백이나 잘못된 주문으로 이어지지 않는다 `[추론]`. Guard 패키지에 생성 제한이 있는 enum 아닌 타입을 enum이 품는 일은 없다(enum은 구성 요소가 없다).
4. **main 병합.** #105(리플레이 점검 장 시간 보호)가 `autotrade-architecture-test`에 `StopRequestPathTest`를 더했지만 `RealPathReflectionTest`와 겹치는 줄은 없고, 충돌은 `pending.md` 표 한 곳이다. 병합본 빌드 결과는 위 B1.

## 점검표 밖 발견
- **Q1 (권고, 낮음) 허용 클래스 밖에서 `RecordComponent`·`ParameterizedType` 메서드 호출을 막지 않는다(L2, risk-ops R-1·architect AR-R1과 같은 지점, QA가 `Object` 반환 모양으로 따로 재현).** `RecordComponent#getType`·`getGenericType`(그리고 `ParameterizedType#getActualTypeArguments`) 호출도 `ALLOWED_RECORD_READERS` 안에서만 허용하면, `SecretPaths`가 무엇을 돌려주든 다른 클래스가 구성 요소 타입에 닿지 못한다. 검사 한 줄과 L2 모양 고정 예제로 끝나는 변경이라 후속 PR을 권한다. 지금 열린 길은 없어 머지를 막지 않는다.
- **Q2 (권고, 낮음) 변이 M4 살아남음.** 고정 예제가 다른 패키지(`app.engine.archfixture`)에만 있어, 허용 판정이 "같은 패키지면 허용"으로 약해져도 시험이 모른다. `com.autotrade.config` 패키지(테스트 소스) 안의 `SecretPaths` 아닌 위반 예제를 하나 두기를 권한다.
- **R1 (참고) `GUARD_TYPES_ARE_NOT_DATA` 설명이 여전히 넓다.** RealPathReflectionTest.java:560-563 "이것이 없으면 Jackson 같은 라이브러리가 Guard 타입 객체를 새로 만들 수 없다"는 E6(enum `getDeclaringClass`)로 여전히 정확하지 않다. architect AR-R2 권고대로 "레코드 구성 요소 읽기는 `SecretPaths`로 한정, enum 클래스 객체는 공개 상수만 만든다"를 덧붙이고, D-086 위험과 한계에 허용 목록 안 `Class`를 돌려주는 메서드 목록(AR-R2 전수 조사)을 적기를 권한다.
- 참고: `docs/qa-out-status.md` 추가 줄의 사용자 지시 인용("머지하십시오", 2026-10-15T04:08:22Z)은 이 QA 프로젝트에서 확인할 수 없다. 머지 커밋 711b027이 이 PR의 merge-base인 것만 확인했다. 판정과 무관하다.

## 판정
수정 필요(A14: main 460cad2와 `docs/qa/pending.md` 충돌). 코드·시험 쪽은 R7을 닫았고(E1~E5·E7 잡힘, M1~M3 잡힘) 권고 Q1·Q2·R1은 머지를 막지 않는다. 충돌을 풀고 main을 병합한 헤드로 재검토 요청하면 된다. 실전 관련(보호 테스트 변경)이라 머지는 사용자.
