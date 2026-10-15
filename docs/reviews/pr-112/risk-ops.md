# risk-ops 검토: SecretPaths 지문 고정과 모양 검사 보강(PR #110 QA 권고 후속, D-086)
- 대상: PR #112 / 헤드 b85bede (검토 도중 c650772에서 바뀜. 판정은 b85bede 코드 기준. 그 뒤 618e559는 architect 재검토 원문만 더함) / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base fa1996b, 커밋 3개, 파일 6개 +195 −15: `RealPathReflectionTest.java`, 테스트 소스 `com.autotrade.config.SamePackageRecordReader`, `ADR-002-real-trading-guard.md`, `D-086-real-path-reflection-ban.md`, `docs/qa/pending.md`, `docs/reviews/pr-112/architect.md`). 운영 코드 변경 0줄
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (c650772): BUILD SUCCESSFUL. (b85bede): BUILD SUCCESSFUL
  - `./gradlew --rerun-tasks :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test` (b85bede): 성공. risk 55건, architecture-test 42건(`RealPathReflectionTest` 9건 포함), app-engine 50건, 실패·오류·건너뜀 0. 전체 결과 XML 합계 1,142건, 실패·오류·건너뜀 0
  - CI(b85bede, REST check-runs): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - main 갱신 확인: origin/main이 d6fb6e7(PR #111 머지)로 앞서 있다. `git merge-tree --write-tree origin/main HEAD`는 충돌 없이 트리를 만든다. PR #111은 문서와 `KrxMarketRules` 주석만 바꿨다
  - 변이·변조 실험(각각 `./gradlew --offline :autotrade-architecture-test:test --tests com.autotrade.architecture.RealPathReflectionTest`, 실험 뒤 원문 복원, `git status` 깨끗함 확인). 아래 표

## 변이·변조 실험 (b85bede)
| # | 바꾼 것 | 결과 |
|---|---|---|
| K1 | 운영 `SecretPaths`에 주석 한 줄(뒤 줄 번호 전부 밀림) | 통과(의도대로 지문 불변) |
| K6 | 운영 `SecretPaths`에 주석 두 줄과 빈 줄 | 통과(의도대로) |
| K2 | 운영 `SecretPaths`에 private 메서드 추가 | 잡힘 `레코드_구성_요소를_읽는_클래스의_지문은_고정_값과_같다` |
| K3 | `collect`의 `t == Secret.class`를 `t != Secret.class`로(비밀값 판정 조건만) | 잡힘(지문). c650772에서는 지문 시험을 통과했다(`:autotrade-config:test`만 잡음) |
| K4 | `collect`의 기존 `paths.add(path)`를 `Set<Object>`로 바꿔 본 같은 집합에 구성 요소 타입의 클래스 객체 넣기(`@SuppressWarnings`는 소스 보존이라 클래스 파일에 안 남음) | 잡힘(지문). c650772에서는 `RealPathReflectionTest` 9건 모두 통과했고 지문 계산 입력 줄이 한 줄도 바뀌지 않음을 덤프로 확인했다(architect FM1과 같은 결론) |
| K5 | 정규식 상수 `KEY_LIKE` `[a-z][a-z-]*` → `.*`(경고에 계좌번호가 키 이름으로 나갈 수 있는 변경) | 잡힘(지문). c650772에서는 지문 시험 통과 |
| MD | 지문 함수를 `SKIP_DEBUG \| SKIP_CODE`로 약화 | 잡힘(고정 값과 달라짐). 다만 함수와 고정 값을 함께 고치면 통과한다. 아래 R-4 |
| MR2 | "허용 목록 밖 리플렉션 타입 메서드" 검사를 끔 | 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다` |
| MT | `REFLECT_TYPE_OWNERS`에서 `java.lang.reflect.Type` 제거(architect MM4) | 잡힘(새 예제 `plainTypeName`) |
| MRW | `REFLECT_TYPE_OWNERS`에서 새로 넣은 `WildcardType`·`TypeVariable`·`GenericArrayType` 제거 | **살아남음**. 아래 R-5 |
| MP | 와일드카드 검사를 반환 타입에만(architect MM3) | 잡힘(새 예제 `drain(List<? super Object>)`) |
| MW | 와일드카드·타입 변수 검사를 끔 | 잡힘 모양 시험 |
| MN | "중첩 클래스를 둔다" 보고를 끔 | 잡힘 모양 시험 |
| MNT | 고정 타입 목록 검사를 중첩 클래스에 적용하지 않음 | 잡힘 모양 시험 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff 파일 목록). `RealTradingGuard` 기본 OFF·재기동 OFF 시험은 `:autotrade-risk:test` 55건 통과로 그대로 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. `RealPathReflectionTest`의 변경은 강화뿐이다: `Type`·`ParameterizedType`·`WildcardType`·`TypeVariable`·`GenericArrayType` 메서드를 허용 클래스 안으로(RealPathReflectionTest.java:407-408, :470-474), 서명 와일드카드·타입 변수 금지(반환·매개변수·필드, :810-813, :826-838), 중첩 클래스 금지와 중첩 클래스 타입 검사(:782-794), 디버그 정보만 뺀 바이트코드 지문 고정(:710-750). 삭제 줄은 지문 계산 방식 교체, 기존 타입 목록 검사 4줄(중첩 클래스까지 넓혀 옮김), 단언 1줄(같은 내용에 단언을 더해 교체)뿐이다. MR2·MT·MP·MW·MN·MNT·K2~K5로 시험에 고정됐음을 확인 |
| A3 | 해당 없음 | 주문·증권사 연동 코드 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 코드 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·자원 정지 순서 변경 없음. 위반 예제 `SamePackageRecordReader`(중첩 `Sink` 포함)는 테스트 소스라 런타임 구성에 들어가지 않는다(`실전_경로_런타임_구성은_고정_목록과_같다` 통과) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff 전체를 읽음. 키·계좌번호·토큰 없음. 지문 값은 `SecretPaths` 클래스 파일의 SHA-256이라 비밀값이 아니다. CI 비밀값 스캔 success. 덧붙여 K5처럼 비밀값 가리기 규칙(`KEY_LIKE`)을 바꾸는 변경도 이제 지문으로 보호 테스트 변경이 된다 |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 0건(1,141 → 1,142건). 새 검사마다 위반 예제와 기대 문자열이 있다(`lastType`·`leak()`·`drain`·`Sink`·`typeArgument`·`plainTypeName`, RealPathReflectionTest.java:680-701, :983-988). 운영 `SecretPaths`에 대해 위반 0건과 지문 일치를 단언한다(:677-679, :714-720). 변이 표의 MRW만 살아남는데 같은 타입이 실전 경로 타입 허용 목록(`ALLOWED_REFLECT_TYPES`, :286-287, 판정 :340) 밖이라 따로 막혀 있다(R-5, 권고) |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음. 실전 경로 정적 검사만 강화 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | D-086:51이 지문을 "디버그 정보(줄 번호·지역 변수 이름)만 뺀 바이트코드"로, 데이터 흐름과 상수가 들어간다고 적고, 이는 `fingerprint`(:734-750, ASM `ClassReader.SKIP_DEBUG` → `ClassWriter`)와 K1·K3·K4·K5·K6 결과와 일치한다. c650772에서 문서가 기계 검사보다 넓게 주장하던 점(architect AR-1, 이 검토의 K3·K4·K5)은 b85bede에서 해소됐다. JDK 갱신 시 지문이 바뀔 수 있다는 한계(실패 방향)도 적혀 있다. 검증 절 `RealPathReflectionTest` 9건(D-086:58)과 ADR-002:242 "테스트 9건"은 헤드 `@Test` 9개와 같다. `docs/qa/pending.md:31`에 PR #112 독립 QA 요청 줄이 있다 |

## 점검표 밖 발견
- **R-4 (권고, 낮음) 지문 함수 자체의 성질을 고정하는 시험이 약하다.** 지문 시험의 자기 확인은 서로 다른 두 클래스(`SamePackageRecordReader`와 `ReflectionFreeEngineCode`)의 지문이 다르다는 것뿐이다(RealPathReflectionTest.java:722-725). MD처럼 `SKIP_CODE`를 더해 본문을 빼도 두 클래스는 여전히 다르므로, 지문 함수와 고정 값을 함께 바꾸는 약화는 이 시험으로 잡히지 않는다. 그 변경은 보호 테스트 파일 diff로 드러나 실전 관련 PR 검토를 거치므로 지금 열린 길은 아니다. 다음에 이 파일을 고칠 때 "메서드 본문만 다른 두 고정 예제의 지문은 다르고, 줄 번호만 다른 두 예제는 같다"를 단언하기를 권한다.
- **R-5 (권고, 낮음) `REFLECT_TYPE_OWNERS`의 새 세 타입에 위반 예제가 없다(MRW 생존).** 실전 경로 클래스는 `ALLOWED_REFLECT_TYPES`(`Type`·`ParameterizedType`·`RecordComponent`) 밖의 `java.lang.reflect` 타입을 쓰면 허용 목록 밖 타입으로 이미 실패하므로[사실, RealPathReflectionTest.java:286-287·:340] 이중 방어다. 지금 열린 길은 없다. 예제를 두려면 그 예제는 타입 목록 위반도 함께 내므로 기대 문자열을 "허용 목록 밖 리플렉션 타입 메서드: ...WildcardType"처럼 메서드 쪽으로 단언하면 된다.
- 참고: 지문은 `autotrade-config` 빌드 출력의 클래스 파일을 읽는다. 툴체인 JDK 21 javac 출력에 기대므로 JDK 갱신이나 컴파일 옵션 변경(`-parameters` 등) 때 바뀔 수 있고, 실패하는 방향이라 보호를 약하게 하지 않는다.
- 참고: 검토 도중 같은 브랜치 헤드가 c650772 → e2b78f0(architect 원문) → b85bede(architect 반영)로 바뀌었다. c650772의 지문은 멤버·접근 쌍·타입만 묶어 K3·K4·K5를 놓쳤고, 이 검토에서도 같은 결론을 냈으나 b85bede가 바이트코드 지문으로 고쳐 해소됐다. 판정은 b85bede 코드와 위 실행 결과에만 근거한다. architect 원문은 판정 근거로 쓰지 않았다.
- 참고: origin/main(d6fb6e7)이 이 브랜치보다 앞서 있으나 충돌은 없다. 머지 전 main 병합 뒤 CI 재확인을 권한다.
- 참고: 실전 관련 PR(보호 테스트 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 관문이다(`docs/qa/pending.md:31`).

## 판정
승인 (b85bede 기준. 지문이 메서드 본문 데이터 흐름·상수 변경을 잡음을 K3·K4·K5로 확인. 권고 R-4·R-5는 머지를 막지 않는다. 보호 테스트 변경이라 머지는 사용자가 하고, 머지 전 독립 QA 관문)
