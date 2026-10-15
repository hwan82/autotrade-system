# risk-ops 검토: PR #108 독립 QA 재검토 R7 후속 (레코드 구성 요소 읽기는 SecretPaths만)
- 대상: PR #109 / 커밋 79f6d51 / main(711b027) 대비 diff (`git diff origin/main...HEAD`, 파일 5개 +37 -3)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build` (새 worktree, 캐시 없음): 성공. 테스트 1,130건, 실패·오류·건너뜀 0
  - `./gradlew :autotrade-architecture-test:test :autotrade-risk:test :autotrade-app-engine:test --rerun-tasks`: 성공. architecture-test 39건, risk 55건, app-engine 50건, 실패 0
  - 변조 실험(실험 뒤 모두 되돌림, `git status` 깨끗함 확인)
    - V1: `autotrade-config`에 탐침 `QaR7Probe` 추가. (e1) `R.class.getRecordComponents()[0].getType()`을 `convertValue`에 넘김, (e2) `Class::getRecordComponents` 메서드 참조, (e3) `RecordComponent::getDeclaringRecord` 메서드 참조 → `실전_경로_모듈은_리플렉션을_쓰지_않는다` FAILED, 세 건 모두 "허용 목록 밖 레코드 구성 요소 읽기"·"허용 목록 밖 RecordComponent 메서드"로 보고됨(호출과 메서드 참조 모두 잡힘)
    - V2: 같은 탐침에서 (e4) `SecretPaths`에 `static RecordComponent[] leak(Class<?>)`를 더하고 탐침이 `SecretPaths.leak(R.class)[0].getType()`을 Jackson에 넘김 → **잡히지 않음**(아래 R-1)
    - V3: 시험 쪽 규칙 약화(`getRecordComponents` 검사 조건을 `false`로, `getDeclaringRecord`를 허용 메서드에 추가) → `규칙은_리플렉션_우회를_실제로_잡아낸다` FAILED(고정 예제 `NameToClassBinding.componentType`·`declaringRecord` 단언이 약화를 잡음)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff는 시험 2개·문서 3개). `RealTradingGuard` 관련 시험은 위 `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음(diff 파일 목록). 이번 변경은 `RealPathReflectionTest.classAsData`에 검사 2개를 더하는 강화뿐(RealPathReflectionTest.java:454-462), 빌드 통과 |
| A3 | 해당 없음 | 주문·증권사 연동 코드 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 코드 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·자원 정지 순서 변경 없음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 바뀐 5개 파일에 키·계좌번호·토큰 없음(diff 전체 확인). 빌드의 gitleaks는 CI 몫이라 로컬에서는 diff 눈 확인 |
| A11 | 통과 | 보호 테스트 삭제·약화 0건. 새 동작에 위반 고정 예제(NameToClassBinding.java:96-104)와 단언(RealPathReflectionTest.java:792-793), 허용 목록 고정 단언(RealPathReflectionTest.java:663-664)이 있다. V1로 실제 코드에서 잡히는 것, V3로 단언이 규칙 약화를 잡는 것을 확인 |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음. 실전 경로 정적 검사만 강화 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | D-086 1번(D-086-real-path-reflection-ban.md:23)과 ADR-002 9.5 6항(ADR-002-real-trading-guard.md:239)이 "레코드 구성 요소 읽기는 `SecretPaths`만, `RecordComponent`는 `getName`·`getType`·`getGenericType`만"으로 적고 시험 상수(RealPathReflectionTest.java:401,404)와 같다. 운영 코드의 `getRecordComponents` 호출은 `SecretPaths.java:221,240` 두 곳뿐이고(grep) 빌드가 위반 0건 |

## 점검표 밖 발견
- **R-1 (권고, 낮음) 허용 클래스 밖으로 `RecordComponent`가 넘어가면 규칙이 보지 못한다.** 새 검사는 `getRecordComponents`를 부르는 클래스만 `SecretPaths`로 묶고, `RecordComponent#getType`·`getGenericType`과 `ParameterizedType#getActualTypeArguments`는 실전 경로 어디서나 허용한다. V2처럼 `SecretPaths`가 `RecordComponent[]`(또는 `Type`)를 돌려주는 메서드를 하나 두면 다른 클래스가 리터럴 없이 구성 요소 타입의 클래스 객체를 얻어 Jackson에 넘길 수 있고 빌드가 통과한다. 지금 `SecretPaths`는 `Set<String>`·`List<String>`·`ObjectNode`만 돌려주므로 열린 길은 없다. 다음 중 하나를 후속으로 권한다: (a) `RecordComponent`·`ParameterizedType` 메서드 호출도 `ALLOWED_RECORD_READERS` 안에서만 허용, (b) `ALLOWED_REFLECT_TYPES` 타입 의존(필드·매개변수·반환)을 `ALLOWED_RECORD_READERS`로 한정. 어느 쪽이든 고정 예제에 V2 모양을 넣는다. 이 PR 머지를 막을 정도는 아니다(이전보다 엄격해졌고, 남은 길은 허용 클래스 자체를 고쳐야 열리며 그 변경은 diff에 드러난다).
- 참고: 새 검사는 `REAL_PATH_HAS_NO_REFLECTION` 범위(실전 경로 런타임 14개 프로젝트)에만 걸리고 `GUARD_TYPES_ARE_NOT_DATA`처럼 운영 코드 전체에는 걸리지 않는다. 실험실 등 실전 경로 밖 프로세스는 엔진의 Guard 객체에 닿을 수 없으므로 문제로 보지 않는다 `[추론]`.
- 참고: `docs/qa-out-status.md`의 "사용자 지시 '머지하십시오'" 기록은 스레드 원문을 볼 수 없어 확인하지 않았다. 처리 기록 문장일 뿐 실전 동작과 무관하다.

## 판정
승인 (권고 R-1. 보호 테스트 변경이라 머지는 사용자 확인)
