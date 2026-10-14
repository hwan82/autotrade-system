# D-085 실전 경로의 리플렉션 금지와 외부 jar 허용 목록

- 날짜: 2026-10-14
- 상태: 추천안 채택(자율). 보호 테스트 강화라 실전 관련 PR로 다루며 risk-ops 검토 뒤 사용자가 머지한다
- 담당: architect
- 근거: 독립 QA [PR #21 검토](../reviews/pr-21/qa.md) Q-4(E8 실측: `Class.forName`·`Method.invoke`로 Guard를 사람 확인 없이 OPEN까지 올리는 실전 엔진 코드가 빌드 통과), risk-ops [PR #21 검토](../reviews/pr-21/risk-ops.md) R-8(실전 엔진 런타임 외부 jar 허용 목록), [ADR-002](../adr/ADR-002-real-trading-guard.md) 9.4·9.5, 기획서 TST-07 (9)

## 결정

1. **리플렉션 금지 규칙** `RealPathReflectionTest.REAL_PATH_HAS_NO_REFLECTION`(`autotrade-architecture-test`). 실전 경로 모듈의 운영 클래스는 다음을 부르지도, 메서드 참조로 넘기지도 않는다.
   - `Method#invoke`, `Constructor#newInstance`, `Field#get*·set*`, `setAccessible·trySetAccessible`, `Class#forName·newInstance`, 클래스 로더 생성·`loadClass`·`defineClass`
   - `java.lang.invoke` 전체(`MethodHandles.Lookup#find*`, `MethodHandle#invoke*`, `LambdaMetafactory` 등), `java.lang.reflect.Proxy`, Java 직렬화(`ObjectInputStream`)
   - 같은 일을 하는 패키지 의존: `java.beans`, `javax.script`, `java.lang.instrument`, Attach API, `sun.misc`, `sun.reflect`, `jdk.internal`
   - Jackson이 JSON 내용으로 만들 클래스를 고르게 하는 설정: 기본 타입 지정(`*DefaultTyping*`), `@JsonTypeInfo(use = CLASS·MINIMAL_CLASS·CUSTOM)`. `NAME`·`SIMPLE_NAME`·`DEDUCTION`은 허용(미리 정한 하위 타입만 고른다)
   - JNI 네이티브 코드 적재(`System`·`Runtime`의 `load·loadLibrary`)와 `native` 메서드 선언, `Module#add*`(깊은 리플렉션 열기), 필드 이름 문자열로 접근하는 `Atomic*FieldUpdater`(PR #108 risk-ops F2·R1)
   - Jackson으로 이미 있는 객체를 덮어쓰거나(`readerForUpdating`, `updateValue`), 가시성·주석 해석기·믹스인·직렬화기 수정자를 바꾸거나, 문자열로 클래스를 찾는 API(PR #108 risk-ops F1, architect AR-2)
   - **허용 목록 밖 타입 의존**(PR #108 architect AR-1·AR-2·AR-3): JDK는 지금 운영 코드가 쓰는 패키지 23개(`java.lang`, `java.util`, `java.time`, `java.net.http`, `javax.crypto` 등, `ALLOWED_JDK_PACKAGES`)와 `java.lang.reflect`의 구조 타입 3개(`Type`, `ParameterizedType`, `RecordComponent`)만, Jackson은 지금 쓰는 타입 27개(`JACKSON_ALLOWLIST`, 매퍼·트리·기능 플래그·직렬화기)만 쓸 수 있다. 금지 목록으로는 반사 호출을 대신하는 JDK·Jackson API(`RequiredModelMBean`, JNDI, `ClassUtil`, `AnnotatedField`, `AnnotationIntrospector` 재정의 등)를 다 셀 수 없어서다. 허용 패키지 안의 위험 API는 위 항목들이 따로 막는다. 목록을 늘리는 PR은 보호 테스트 변경이다
   - `ServiceLoader`는 허용 목록(`ALLOWED_SERVICE_LOADERS`) 클래스만. 지금은 비어 있고 테스트가 비어 있음을 고정한다. 7단계에 플러그인을 불러오는 클래스 하나를 넣는 PR은 보호 테스트 변경이다.
2. **범위는 손 목록이 아니라 Gradle 해석 결과**. 실전 엔진(`:autotrade-app-engine`)·감시(`:autotrade-app-watchdog`) 프로세스와 증권사 플러그인 전체(`:autotrade-broker-*`, 7단계에 엔진이 불러온다)의 런타임 클래스패스를 아키텍처 테스트 모듈의 `realPathRuntime` 구성으로 해석해, 나온 우리 프로젝트 전부(지금 14개: 두 프로세스, domain, marketdata, strategy, risk, broker-api·kis·kiwoom·toss, config, control, notification-jar, monitor-agent-jar)를 범위로 한다. 클래스 파일이 나온 프로젝트로 판정하므로 그 모듈 안 `com.autotrade` 밖 패키지도 걸리고, 같은 이름 패키지도 이중으로 넣는다. 해석 결과에 꼭 있어야 할 14개(`REQUIRED_PROJECTS`)와 들어오면 안 되는 프로젝트(실험실·UI·sim·selection·게이트웨이·수집·실험 명령)를 테스트가 고정한다.
3. **2차 확인은 데이터가 아니다** `SECOND_CONFIRMATION_IS_NOT_DATA`(운영 코드 전체). `SecondConfirmation` 타입에 의존(필드·매개변수·반환·클래스 객체·제네릭 인자)하는 곳은 `com.autotrade.risk.guard`와 `OperatorAuthenticator`뿐이다. Jackson 같은 외부 라이브러리는 리플렉션으로 생성자를 부르므로, 명령 DTO 필드나 `readValue(.., SecondConfirmation.class)`로 검증 없이 확인을 만들 수 있었다(기존 생성 규칙은 `new`만 본다).
4. **Guard 타입은 데이터가 아니다** `GUARD_TYPES_ARE_NOT_DATA`(운영 코드 전체, PR #108 risk-ops F1): `com.autotrade.risk.guard` 밖은 Guard 패키지 타입을 클래스 객체(`RealTradingGuard.class`), 제네릭 상위 타입 인자(`new TypeReference<GuardState>() {}`), 주석 값(`@JsonDeserialize(as = ...)`)으로 넘기지 않는다. Guard 인터페이스 구현(`LoggingGuardAuditLog implements GuardAuditLog`)은 허용.
5. **외부 jar 허용 목록**(risk-ops R-8): 실전 경로 런타임의 외부 라이브러리는 `EXTERNAL_ALLOWLIST`(group:name, 버전 무관)와 정확히 같아야 한다. 지금은 Jackson 4개(`jackson-bom`, `jackson-annotations`, `jackson-core`, `jackson-databind`). 파일 의존(`files(...)`)처럼 프로젝트도 외부 모듈도 아닌 구성 요소가 있으면 실패한다. 버전 올림은 목록을 바꾸지 않는다.

## 대안

| 선택 | 대안 | 버린 이유 | 되돌릴 조건 |
|---|---|---|---|
| 해석한 런타임 클래스패스로 범위 계산 | QA 제안 그대로 4개 모듈(app-engine, risk, broker-*, watchdog) 패키지 목록 | 엔진 런타임에 함께 실리는 domain·config·control에 리플렉션 유틸을 두고 엔진이 부르면 우회된다. 패키지 목록은 PR #21의 범위 우회(Q-1)를 되풀이한다 | 없음 |
| 리플렉션 호출 자체 금지 | 리플렉션 대상이 Guard·출구일 때만 금지 | 대상 이름이 문자열이라 정적 분석으로 알 수 없다(E8이 `"begin" + "Activation"`) | 실전 경로에 리플렉션이 꼭 필요한 라이브러리 통합이 생기면 그 클래스만 허용 목록으로, risk-ops 검토 |
| ServiceLoader 허용 목록을 비워 둠 | ServiceLoader 전면 허용 | 지금 운영 코드에 쓰는 곳이 없다. 플러그인 로딩이 들어오는 7단계 PR에서 한 클래스를 넣으면 그 PR이 검토 대상으로 드러난다 | 7단계 플러그인 로딩 PR |
| 외부 jar 정확 일치 | JPMS(`module-info.java`)로 실전 엔진 런타임 봉인 | 전 모듈·Spring Boot 실험실·테스트 설정을 바꿔야 한다(ADR-002 9장과 같은 이유) | 7단계 플러그인 모듈이 늘어 표 관리 비용이 커질 때 |
| `SecondConfirmation` 타입 의존 금지 | Jackson 매퍼 설정으로 해당 타입 역직렬화 거부 | 매퍼마다 설정을 빠뜨리면 구멍이고, Jackson 밖 바인딩 라이브러리는 못 막는다 | 명령 채널이 확인을 보관해야 하는 설계(예: 단계 사이 보류)가 생기면 그 클래스만 허용, risk-ops 검토 |

## 위험과 한계

- 보장하는 것: 실전 경로 범위의 우리 바이트코드가 리플렉션·동적 코드 수단을 직접 쓰지 않고, 허용 목록의 JDK 패키지와 Jackson 타입만 쓰며, 그 안의 위험 메서드(위 1번)도 쓰지 않는다. 운영 코드 어디서도 `SecondConfirmation`을 데이터로 다루거나 Guard 타입을 바인딩 대상으로 넘기지 않는다.
- 보장하지 않는 것(남은 한계)
  - 허용 목록 Jackson의 기본 동작 안의 리플렉션. 기본 `readValue`는 우리 DTO의 생성자·setter를 리플렉션으로 부른다. Guard 객체를 새로 만들려면 Guard 타입을 넘겨야 하고(4번이 막음), 살아 있는 객체를 덮어쓰는 API는 1번이 막는다. 허용 목록 타입 안에 아직 모르는 객체 접근 API가 있으면 그 경로는 열려 있다 `[추론]`. Jackson 버전을 올리는 PR은 이 목록과 함께 검토한다.
  - 허용 패키지 `java.lang`·`java.util` 안의 위험 API는 금지 목록으로 막는다(`Class#forName`, 클래스 로더, `System#loadLibrary`, `Module#add*`, `ServiceLoader`). 이 패키지 안에 다른 간접 호출 수단이 있으면 열려 있다 `[추론]`. `Class#getMethods`처럼 `Method` 객체를 얻는 것 자체는 막지 않는다(호출 수단인 `Method#invoke`와 그것을 대신 부르는 API를 막는다).
  - Guard 패키지의 다른 타입(`OrderSessionPermit` 등)을 실전 경로 밖(실험실 등)에서 만드는 것은 그 프로세스가 엔진 메모리에 닿지 못한다는 프로세스 분리(D-013)에 기댄다.
  - 배포 설치본의 런타임 클래스패스(빌드 밖에서 jar를 끼워 넣는 것)와 JVM 옵션(`-javaagent`)은 보지 않는다. 7단계 실행 절차서의 설치본 대조 항목으로 넘긴다.
  - 실험실(`autotrade-app-lab`)·게이트웨이는 리플렉션 규칙의 범위가 아니다. 실전 엔진과 다른 프로세스라 리플렉션으로 엔진 메모리의 Guard에 닿을 수 없다(D-013). 명령 채널은 2차 확인 규칙(ADR-006)이 막는다.
  - 범위 계산의 "들어오면 안 되는 프로젝트" 목록(`FORBIDDEN_PROJECTS`)은 프로세스 격리 검사(`processIsolation.forbiddenProjects`)와 따로 적는다. 둘이 어긋나도 각자 실패 방향이라 보호가 약해지지는 않는다(PR #108 architect 참고).

## 검증

- 테스트: `RealPathReflectionTest` 6건(PR #108 검토 반영 뒤. 합친 범위가 실전 경로 모듈의 `com.autotrade` 밖 패키지를 잡는지 가짜 배치표로 확인하는 `#합친_범위는_...` 포함, risk-ops R2). 처음 판은 5건(구성 고정, 범위 판정, 운영 코드 무위반 2건, 위반 고정 예제). 위반 예제 `ReflectiveRaiser`(E8 형태 포함 11종, 메서드 참조 `Method::invoke`), `PolymorphicJson` 4건(허용 `Id.NAME` 제외), `DataBoundConfirmation`(DTO 필드·클래스 객체·`TypeReference`), 허용 예제 `ReflectionFreeEngineCode`(람다·레코드·enum·`getSimpleName`).
- 변조 실험(커밋하지 않음): ① 실전 엔진 운영 코드 `com.autotrade.app.engine.qaprobe.E8Raiser`(QA E8과 같은 `getMethod("begin"+"Activation").invoke`) → 실패 ② 리스크 모듈의 `qa.probe.OutsideReflect`(`Method.invoke`) → 모듈 판정으로 실패(소유 규칙도 실패) ③ 같은 엔진 클래스의 `record Dto(SecondConfirmation c)` → 3건 실패 ④ 엔진의 `ServiceLoader.load` → 실패 ⑤ 토스 플러그인에 `runtimeOnly(libs.archunit.junit5)` → 허용 목록 밖 외부 모듈 10개로 실패 ⑥ 실전 엔진에 `runtimeOnly(files("../gradle/wrapper/gradle-wrapper.jar"))` → "OTHER" 구성 요소로 실패(소유 규칙도 실패). 검토 반영 뒤 ⑦ `autotrade-control`에 `System.loadLibrary`, `javax.tools.ToolProvider`, `readerForUpdating(live)`, `RequiredModelMBean`, `ClassUtil.createInstance` → 모두 실패(호출 금지와 허용 목록 밖 타입).
- 고정 예제(검토 반영): `JacksonObjectAccess`(기존 객체 갱신 2·가시성·믹스인·`findClass`), `GuardAsData`(클래스 객체·`TypeReference`·`@JsonDeserialize(as)`), `DynamicCode`(네이티브 적재 2·native·MBean·`javax.tools`·JNDI·필드 갱신기·`Module.addOpens`), `PolymorphicJson.ClassIdIntrospector`(주석 해석기 재정의와 `JsonTypeInfo.Id.CLASS` 상수), `qa.probe.OutsideReflective`.
