# QA 검토: 실전 경로 리플렉션 금지와 JDK·Jackson·외부 jar 허용 목록(D-086)
- 대상: PR #108 / 커밋 89daece37e4377ee040bd71d5bd4d82e7193e40a / main(4b24550) 대비 diff 전체(merge-base = 현재 origin/main 4b24550)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 보호 테스트(`autotrade-architecture-test`)를 강화하고 ADR-002(9.4·9.5)를 바꾼다(QA README 1장, 지침 11장 보호 테스트). 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-108/architect.md`, `risk-ops.md`), D-086 문서의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 17파일, +1,583 −3. 운영 코드 변경 **0**. 시험 쪽은 `autotrade-architecture-test`의 `build.gradle.kts`(+51), 새 `RealPathReflectionTest.java`(655줄), 위반·허용 고정 예제 7파일(`com.autotrade.app.engine.archfixture`), `qa.probe.OutsideReflective`. 문서는 ADR-002, D-086, 결정 로그 한 줄, 기획서 v14.32 TST-07 (9), `pending.md` 한 줄, 검토 원문 2
  - 보호 시험 변경 여부: `ArchitectureTest.java`·`OurModules.java`·`ModuleOwnershipTest` diff **0줄**. `RealTradingGuardTest`·`GuardedOrderGatewayTest`·장애 시험 변경 없음. `@Disabled`·`@Tag` 추가 없음. 기존 규칙을 지우거나 좁힌 곳 없음(추가만)
  - `./gradlew build --rerun-tasks`(head 89daece, 별도 작업 트리): 1회째 **BUILD SUCCESSFUL**. 시험 결과 XML 합계 tests **1,130**, failures 0, errors 0, skipped 0. head가 main 4b24550을 병합한 상태라 이 빌드가 병합 빌드다(`git merge-tree` 충돌 없음)
  - CI(head 89daece, check-runs 3건): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - **QA 탐침**(하나씩 넣고 `:autotrade-architecture-test:test`, 확인 뒤 원복, 작업 트리 깨끗. 저장소에 남기지 않음):
    - Q1 증권사 플러그인(`autotrade-broker-toss`, 실전 엔진 의존에 아직 없음)의 `getMethod(..).invoke` → `실전_경로_모듈은_리플렉션을_쓰지_않는다` FAILED(잡힘)
    - Q2 `autotrade-monitor-agent-jar`의 `com.autotrade` 밖 패키지 `zz.probe`에서 `Class.forName("…RealTradingGuard")` → 리플렉션 규칙 FAILED(소유 규칙 2건도 FAILED, 잡힘)
    - Q3 감시(`autotrade-app-watchdog`)의 `Runtime.exec(jcmd)` → FAILED(잡힘)
    - Q5 `autotrade-control`의 메서드 참조 `Class::forName` → FAILED(잡힘)
    - Q6 `autotrade-broker-kis`에 `runtimeOnly(libs.assertj.core)` → `실전_경로_런타임_구성은_고정_목록과_같다` FAILED(잡힘)
    - **Q16 이름 문자열로 클래스 찾기 + Jackson 생성**(실전 엔진 명령 채널 패키지 `com.autotrade.app.engine.command`에 임시 클래스, 엔진에 허용 목록 안의 `jackson-databind`를 `implementation`으로 추가. 리플렉션 API 호출 없음):
      ```java
      Class<?> k = M.readValue("\"" + name + "\"", Class.class);   // name = "com.autotrade.risk.guard." + "SecondConfirmation"
      Object o = M.convertValue(fields, k);
      Object f = M.convertValue("REQUIRE_HANDLERS_FOR_JAVA8_TIMES", byName("com.fasterxml.jackson.databind." + "MapperFeature"));
      JsonMapper.builder().configure((com.fasterxml.jackson.databind.MapperFeature) f, false).build();
      ```
      결과: `:autotrade-architecture-test:test` **BUILD SUCCESSFUL**(모든 규칙 통과). 실행하면 `class com.autotrade.risk.guard.SecondConfirmation`을 얻고, `com.autotrade.domain.account.AccountId`를 이름만으로 만들어 `KIS-1`을 돌려준다. `SecondConfirmation` 생성은 `confirmedAt`의 `java.time.Instant`를 Jackson이 만들지 못해 실패했다(첫 시도 "Java 8 date/time type `java.time.Instant` not supported by default", `MapperFeature`를 끈 뒤 "Cannot construct instance of `java.time.Instant` (no Creators…)"). 즉 지금 2차 확인 위조를 막는 것은 규칙이 아니라 `Instant` 필드 하나다(아래 F1·F2)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Guard 운영 코드 변경 0줄. 빌드의 `:autotrade-risk:test`(기본 OFF·재기동 OFF) 통과 |
| A2 | 통과 | `ArchitectureTest`의 출구·Guard 상향·2차 확인 생성 규칙 변경 없음(diff 0줄), 빌드에서 통과. 새 규칙은 추가만이다 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스·정지 순서 변경 없음 |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 새 파일에 키·계좌번호·토큰 없음(고정 예제의 계좌는 `AccountId`·자리표시자) |
| A11 | 통과 | 보호 시험 삭제·약화 0건. 새 시험 7건이 있고 위반 고정 예제로 실패하는 경우를 단언한다. QA 탐침 Q1~Q6이 모두 잡힌다. 다만 Q16은 잡지 못한다(F1·F2, 새 규칙의 빈틈이지 약화는 아님) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음. 외부 명령 허용 목록이 비어 있어 #8·#9 감시 자동 재기동은 감시 구현 PR에서 목록에 넣어야 한다(D-086 1번, 대응표 문구는 그대로) |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | **실패** | 문서가 규칙이 보장한다고 적은 것과 실제 동작이 다르다. (1) `RealPathReflectionTest.java:423`(GUARD_TYPES_ARE_NOT_DATA 설명) "문자열로 클래스를 찾는 API는 실전 경로에서 금지", D-086 결정 1번 "문자열로 클래스를 찾는 API" 금지, 위험과 한계 "보장하는 것: … 리플렉션·동적 코드 수단을 직접 쓰지 않고" — Q16에서 `ObjectMapper.readValue(.., Class.class)`(`Class.forName`과 같은 일, 정적 초기화 포함)가 통과한다(F1). (2) D-086 1번·ADR-002 9.5 5항 "허용 목록의 … Jackson 타입에만 의존한다" — Q16에서 목록 밖 `MapperFeature`로 형변환하고 `configure(MapperFeature, boolean)`을 불러도 통과한다(F2) |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행 성공(1,130건, 실패·오류·건너뜀 0) |
| B6 | 통과 | 새 동작(규칙)에 시험 있음(A11). 건너뛰거나 지운 시험 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음. `testImplementation(jackson-databind)`은 기존 무료 라이브러리 |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **E8처럼 실전 엔진 코드가 리플렉션으로 Guard를 OPEN까지 올리는가**: `Method.invoke`·`Class.forName`·메서드 참조·`MethodHandle`·`Proxy`·`ServiceLoader`가 엔진·control·증권사 플러그인·감시·`com.autotrade` 밖 패키지 어디에 있어도 잡힌다(Q1·Q2·Q3·Q5와 고정 예제). 규칙이 막는 범위에서는 E8 경로가 닫혔다.
2. **명령 채널이 비밀번호 검증 없이 2차 확인을 데이터로 만드는가**: 타입 이름을 쓰는 방식(DTO 필드, `SecondConfirmation.class`, `TypeReference`)은 막혔다. 그러나 이름 문자열로 클래스를 얻는 Q16 방식은 `SecondConfirmation`에 대한 정적 의존이 없어 `SECOND_CONFIRMATION_IS_NOT_DATA`·`GUARD_TYPES_ARE_NOT_DATA`가 보지 못한다. 실제 생성은 `Instant` 필드를 Jackson이 만들지 못해 실패했다. Guard에 넘기는 호출 `guard.beginActivation(x)`는 명령 채널 패키지 안이면 기존 규칙상 허용되고, 넘기는 값의 정적 타입은 의존으로 잡히지 않는다(`EngineCommandHandler.java:132`가 같은 모양으로 통과함) `[추론]`. 따라서 `confirmedAt`을 `Instant`가 아닌 타입으로 바꾸거나, 실전 경로 Jackson에 날짜 처리(jsr310 모듈 또는 직접 만든 역직렬화기)를 넣는 순간 비밀번호 없이 2차 확인이 만들어진다 `[추론]`.
3. **허용 목록 밖 Jackson 기능으로 접근 제한을 푸는가**: Q16에서 목록 밖 `MapperFeature`를 형변환·메서드 인자로 쓰는 것은 잡히지 않았다. `MapperFeature`에는 접근 제한·final 필드 쓰기·생성자 추론을 바꾸는 항목이 있다. Guard 패키지 타입은 Jackson이 만들 수 있는 모양이 아님을 시험이 고정하므로(`#Guard_타입은_Jackson이_만들_수_있는_모양이_아니다`) 지금 Guard 객체 생성 경로는 찾지 못했다.
4. **새 외부 jar·파일 의존으로 범위를 벗어나는가**: Q6(외부 모듈)이 잡혔고, 파일 의존은 고정 시험이 "OTHER"로 잡는다(시험 코드 확인).

## 점검표 밖 발견
- **F1 Jackson의 `Class` 역직렬화로 이름 문자열 클래스 찾기가 열려 있다**(차단): `ObjectMapper.readValue("\"<FQN>\"", Class.class)`·`convertValue(name, Class.class)`는 `Class.forName`과 같은 일을 하는데 규칙의 금지 목록(`JACKSON_OBJECT_ACCESS`의 `findClass` 등 메서드 이름)과 허용 목록(대상 타입 `java.lang.Class`·`ObjectMapper`는 허용)을 모두 통과한다. 이렇게 얻은 클래스는 `convertValue(.., k)`로 Jackson이 만들 수 있는 모든 타입(레코드·인자 없는 생성자)을 정적 의존 없이 만든다(Q16의 `AccountId`). 제안: 실전 경로에서 `java.lang.Class`의 클래스 객체 참조(`Class.class`)와 Jackson 읽기 API의 대상 타입이 `Class`인 호출을 금지하고, Q16과 같은 위반 고정 예제를 더한다.
- **F2 허용 목록 검사가 형변환과 호출한 메서드의 매개변수·반환 타입을 보지 않는다**(차단): `outsideAllowlist`는 `getDirectDependenciesFromSelf()`만 보는데, Q16의 `(MapperFeature) f`와 `JsonMapper.Builder#configure(MapperFeature, boolean)` 호출은 걸리지 않았다. D-086·ADR-002 9.5의 "허용 목록 타입에만 의존" 보장이 성립하지 않는다. 제안: 접근 대상(`AccessTarget.CodeUnitAccessTarget`)의 매개변수·반환 타입과 형변환 대상도 허용 목록으로 대조하고, 고정 예제를 더한다.
- **R1 2차 확인 위조 방어가 `Instant` 필드에 기대고 있다**(권고, F1·F2를 고치면 대부분 해소): 시나리오 2. 날짜 처리를 실전 경로 Jackson에 넣는 PR(외부 jar 허용 목록 변경, 보호 테스트 변경)이 오면 이 경로를 함께 검토하길 권한다.
- 참고: 엔진 모듈은 지금 Jackson을 컴파일 의존으로 갖지 않는다. Q16은 허용 목록 안의 같은 jar를 `implementation`으로 더해 만들었고, 이 변경은 `실전_경로_런타임_구성은_고정_목록과_같다`를 통과한다(외부 모듈 집합이 같으므로).

## 판정
수정 필요 (A14, F1·F2: 이름 문자열 클래스 찾기와 허용 목록 밖 타입 사용이 규칙을 통과해 D-086·ADR-002 9.5의 보장과 다르다. 권고 R1. 실전 관련이라 머지는 사용자)

## 재검토 2026-10-15
- 대상: PR #108 / 커밋 282c5a9215aa96de04360b261205341849644df0 / 1차 판정 헤드 89daece(보고서 커밋 679c916) 뒤 변경 전체(743545c F1·F2 반영, 4f44ccc AR-8·AR-9, af0a8e3 AR-10, 검토 원문·`qa-out-status.md`·`pending.md`). main 대비 diff 전체도 다시 봤다(merge-base 4b24550 = 현재 origin/main, `git merge-tree` 충돌 없음)
- 점검표: A 전체 + B1, B6 + 과금 검사. 개발 쪽 검토 원문(architect·risk-ops 재검토 승인)과 D-086·ADR-002 9.5의 주장은 확인할 주장으로만 봤다
- 직접 실행한 것:
  - 1차 뒤 변경 11파일 +872 −15. 운영 코드 변경 **0**. 시험 쪽은 `RealPathReflectionTest` 규칙 보강(`classAsData`, 상수 풀 대조), 새 도우미 `ClassFileTypes`(클래스 파일 상수 풀의 클래스·서술자 타입 수집), `GetClassUses`(ASM으로 `getClass()` 바로 다음 명령 검사), 위반 고정 예제 `NameToClassBinding`, 허용 예제 보강. `ArchitectureTest.java`·`OurModules.java` diff 0줄, 기존 단언 삭제 없음
  - 허용 목록 변경 대조: 상수 풀까지 보게 되면서 JDK 클래스 10개(`java.io.File`·`Reader`·`Writer`·`EOFException`, `java.lang.Iterable`·`ArithmeticException`·`NumberFormatException`·`NoSuchFieldError`·`Thread$Builder`, `java.net.ProtocolFamily`·`SocketAddress`, `java.security.GeneralSecurityException`·`NoSuchAlgorithmException`)와 패키지 3개(`java.security.spec`, `java.time.chrono`, `java.time.temporal`), Jackson 7개(`JsonParser`, `JsonProcessingException`, `TreeNode`, `JsonSerializer`, `Module`, `PropertyNamingStrategy`, `cfg.MapperBuilder`)가 늘었다. 이름으로 클래스를 만들거나 코드를 불러오는 API는 찾지 못했다. `MapperBuilder`의 위험 메서드(`addMixIn`·`visibility`·`annotationIntrospector`·`activateDefaultTyping`·`findModules`)는 이름 금지 목록에, `configure(MapperFeature, ..)`·`typeFactory`·`handlerInstantiator`·`injectableValues`는 목록 밖 매개변수 타입으로 걸린다(상수 풀 대조)
  - `./gradlew build --rerun-tasks`(head 282c5a9, 별도 작업 트리): 1회째 **BUILD SUCCESSFUL**. 시험 결과 XML 합계 tests **1,130**, failures 0, errors 0, skipped 0(새 검사는 기존 시험 안에 들어가 건수는 1차와 같음). head가 현재 main을 포함해 이 빌드가 병합 빌드다
  - CI(head 282c5a9, check-runs 3건): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - **QA 탐침 재실행**(하나씩 넣고 `:autotrade-architecture-test:test`, 확인 뒤 원복, 작업 트리 깨끗. 저장소에 남기지 않음):
    - V1 = 1차 Q16 그대로(엔진 명령 채널 패키지, 엔진에 허용 목록 안 `jackson-databind` 추가, `readValue(.., Class.class)` + `(MapperFeature)` 형변환·`configure`) → `실전_경로_모듈은_리플렉션을_쓰지_않는다` FAILED. 상세: `QaR16.byName … 클래스 객체 Class.class를 참조`, `반환 타입에 Class`, `허용 목록 밖 타입 com.fasterxml.jackson.databind.MapperFeature … JsonMapper$Builder.configure` (**F1·F2 잡힘**)
    - V3 람다 `x -> x.getClass()`로 `Class` 객체의 클래스를 얻어 `readValue(.., cc)` → FAILED(`lambda$go$0의 getClass() 결과를 이름 읽기 밖에서 쓴다`, 잡힘)
    - V16 엔진 레코드 `record QaV16(List<SecondConfirmation> xs)`(제네릭 인자로만 의존) → `이차_확인은_검증기_밖에서_데이터로_다루지_않는다` FAILED(필드·생성자·접근자 3건, 잡힘)
    - V17 엔진 레코드 `record QaV17(GuardTransition t)`에서 `QaV17.class.getRecordComponents()[0].getType()`으로 `GuardTransition` 클래스 객체를 얻어 `convertValue(fields, k)` → **BUILD SUCCESSFUL(통과)**. 클래스 리터럴·`getClass()` 없이 Guard 패키지 타입의 클래스 객체를 Jackson에 넘길 수 있다(아래 R7)
    - 1차 범위 탐침 재실행: Q2(`autotrade-monitor-agent-jar`의 `zz.probe`에서 `Class.forName`) → 리플렉션 규칙과 소유 규칙 2건 FAILED, Q6(`autotrade-broker-kis`에 `runtimeOnly(libs.assertj.core)`) → `실전_경로_런타임_구성은_고정_목록과_같다` FAILED (그대로 잡힘)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Guard 운영 코드 변경 0줄. 빌드의 `:autotrade-risk:test` 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음, 빌드 통과. 새 규칙은 추가·강화뿐 |
| A3~A9 | 해당 없음 | 운영 코드·주문·시세·프로세스 변경 없음(1차와 같음) |
| A10 | 통과 | 새 파일에 키·계좌번호·토큰 없음 |
| A11 | 통과 | 보호 시험 삭제·약화 0건. 1차 F1·F2 탐침(V1)과 변형(V3·V16)이 새 규칙에 잡히고, 고정 예제 `NameToClassBinding`이 같은 모양들을 단언한다(`규칙은_리플렉션_우회를_실제로_잡아낸다`) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | 1차 실패 두 곳이 해소됐다. (1) 이름 문자열 클래스 찾기: D-086 1번 "이름 문자열로 클래스를 얻는 데이터 바인딩"과 ADR-002 9.5 6항이 `Class.class`·`Class[].class`·서명의 `Class`(바인딩 아닌 메서드의 `Class<T>` 매개변수만 허용)·이름 읽기 밖 `getClass()`·목록 밖 `Class` 메서드 금지로 적고, V1·V3에서 그대로 동작한다. (2) 허용 목록: D-086·ADR-002 9.5 6항 "부른 메서드의 매개변수·반환 타입, 클래스 파일 상수 풀의 모든 타입 이름(형변환 포함)"이 V1의 `MapperFeature` 검출과 일치한다. 1차 R1(`Instant`에 기댄 방어)은 D-086 위험과 한계에 "이 사실에 기대지 않는다"와 jsr310 도입 PR 검토 조건으로 적혔다. R7은 문서가 보장한다고 적지 않은 경로라 불일치로 보지 않는다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행 성공(1,130건, 실패·오류·건너뜀 0) |
| B6 | 통과 | 새 규칙에 위반 고정 예제와 허용 예제가 있다. 건너뛰거나 지운 시험 없음 |

### 과금 검사 (재검토)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음. `GetClassUses`는 ArchUnit에 이미 들어 있는 ASM(`com.tngtech.archunit.thirdparty`)을 시험에서만 쓴다 |

### 시나리오 재시도
1. **1차 Q16 경로로 비밀번호 없이 2차 확인을 만드는가**: `Class.class`·`Class[].class`·`getClass()`·`Object::getClass`·`Class` 필드·생성자·설정자·제네릭 인자 어느 쪽으로도 `Class` 값 바인딩을 열면 규칙이 실패한다(V1·V3, 고정 예제). `SecondConfirmation`을 제네릭 인자로만 담아도 잡힌다(V16).
2. **리터럴 없이 클래스 객체를 얻는 다른 길**: 허용된 `Class#getRecordComponents` + `RecordComponent#getType()`로 레코드 구성 요소 타입의 클래스 객체를 얻어 Jackson에 넘길 수 있다(V17 통과). 이 길로 닿는 Guard 패키지 타입은 우리 레코드가 구성 요소로 둘 수 있는 공개 타입뿐이다. `SecondConfirmation`은 구성 요소로 두면 V16 규칙이 잡고, `RealTradingGuard$Snapshot`은 private이라 구성 요소로 둘 수 없으며 중첩 클래스 조회(`getDeclaredClasses` 등)는 금지다. 남는 `GuardTransition`은 지금도 Guard 밖에서 `new`로 만들 수 있는 감사 값이다(생성 제한 규칙 없음 확인). 그래서 Guard 상향이나 2차 확인 위조로 이어지는 경로는 찾지 못했다.
3. **허용 목록에 더해진 Jackson `Module`·`MapperBuilder`로 접근 제한을 푸는가**: 위험 메서드는 이름 금지 목록이나 목록 밖 매개변수 타입(상수 풀 대조)에 걸린다(위 직접 실행 항목). 모듈 자동 발견은 그대로 금지다.

### 점검표 밖 발견 (재검토)
- **R7 `RecordComponent#getType()`·`getGenericType()`·`getDeclaringRecord()`로 리터럴 없이 클래스 객체를 얻는 길**(권고, 낮음, V17): `GUARD_TYPES_ARE_NOT_DATA` 설명(`RealPathReflectionTest`)은 클래스 객체·상위 타입 인자·주석 값이 없으면 Jackson이 Guard 타입 객체를 만들 수 없다고 적는데, V17처럼 레코드 구성 요소로 Guard 패키지 공개 타입의 클래스 객체에 닿는다. 지금은 시나리오 2대로 위험한 대상이 없다. Guard 패키지에 Jackson이 만들 수 있는 공개 레코드가 늘거나 생성 제한이 있는 타입이 생기면 열린다. `ALLOWED_CLASS_METHODS`처럼 `RecordComponent`도 허용 메서드 목록(`getName`·`getAccessor` 금지 유지)으로 좁히거나, 이 길을 D-086 위험과 한계에 적기를 권한다.
- 1차 R1은 문서 반영으로 닫는다(위 A14). 개발 쪽 R6(Jackson 2.21.4 실행 동작에 기댄 근거, 실행 시험 없음)은 D-086에 적힌 대로 남는다(참고).

### 판정 (재검토)
승인 (1차 A14·F1·F2 해소. 권고 R7. 실전 관련(보호 테스트 변경)이라 머지는 사용자)
