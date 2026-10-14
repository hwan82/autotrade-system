# architect 검토: D-085 실전 경로 리플렉션 금지 규칙과 외부 jar 허용 목록
- 대상: PR #108 / 커밋 884d443 / main 대비 diff(`git diff origin/main...884d443`, 10개 파일 +592 −3)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (884d443, 작업 트리 깨끗한 상태에서 시작) → `BUILD SUCCESSFUL in 27s`, 종료 코드 0. 130 태스크 중 19 실행, 111 up-to-date. `:autotrade-architecture-test:test`는 UP-TO-DATE(입력이 같은 앞선 성공 결과를 Gradle이 재사용). 결과 XML 합계 테스트 1,106건, 실패·오류·건너뜀 0(`RealPathReflectionTest` 5건 실패 0).
  - `./gradlew build --rerun-tasks`와 우회 변조 실험(아래 AR-1·AR-2의 프로브 클래스를 운영 소스에 넣고 아키텍처 테스트 실행)은 이 환경의 권한 분류기가 거부해 실행하지 못했다. 그래서 AR-1·AR-2는 규칙 코드를 읽은 정적 판정(`[사실]`: 규칙이 그 호출을 보지 않는다)과 API 동작 지식(`[추론]`: 그 호출로 실제 Guard에 닿는다)으로 적는다.
  - 주의: 검토 중 같은 작업 트리를 다른 세션이 동시에 고쳤다(13:59경 `autotrade-control/.../control/qaprobe/` 생성·삭제, 커밋 56f0d31 risk-ops 원문 추가, `RealPathReflectionTest.java` 미커밋 수정과 archfixture 3개·`qa/probe/OutsideReflective.java` 미추적 추가). 이 검토의 대상은 884d443의 코드다. 미커밋 수정은 보지 않았고 재검토 대상이다. 빌드 출력에 그 세션이 남긴 `autotrade-control/build/classes/java/main/com/autotrade/control/qaprobe/NativeProbe.class`가 남아 있다. 재검토 때 `./gradlew clean build`로 확인해야 한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, 테스트 1,106건 실패 0. 아키텍처 테스트는 UP-TO-DATE(입력 동일 재사용)라 이번 실행에서 다시 돌지 않았다. `--rerun-tasks`는 권한 거부로 못 함. 재검토 때 강제 재실행 결과를 붙인다 |
| B2 | 통과 | 새 규칙은 테스트 모듈(`autotrade-architecture-test`) 안에만 있고 운영 모듈 의존 방향은 그대로. `build.gradle.kts`의 `realPathRuntime` 구성은 해석 전용(`isCanBeConsumed=false`, 테스트 클래스패스에 안 올림, build.gradle.kts:14-19). 규칙 추가에 ADR-002 9.5와 D-085가 있다. 기존 `ArchitectureTest`·`ModuleOwnershipTest` 규칙은 바꾸지 않았다(diff에 없음) |
| B3 | 통과 | 운영 계약(인터페이스·스키마·설정 키·REST) 변경 없음. 새 시스템 속성 `autotrade.realPathComponents`는 테스트 실행 내부 값이고 ADR-002 9.5 1항에 적혀 있다. 기획서 v14.29 TST-07 (9), 결정 로그 D-085 행 갱신 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 새 규칙마다 위반 고정 예제와 허용 예제가 있다: `RealPathReflectionTest#규칙은_리플렉션_우회를_실제로_잡아낸다`(`ReflectiveRaiser` E8 형태·메서드 참조, `PolymorphicJson` 4건, `DataBoundConfirmation` 3종, `ReflectionFreeEngineCode` 허용), 범위 판정 `#범위는_실전_경로_프로젝트의_운영_클래스_전체다`, 구성 고정 `#실전_경로_런타임_구성은_고정_목록과_같다`. 지우거나 건너뛴 테스트 없음. 다만 규칙이 막아야 할 경로 중 예제가 없는 것이 있다(AR-1·AR-2) |
| B7 | risk-ops 검토 필요 | 보호 테스트(`ArchitectureTest` 계열) 강화이고 실전 경로(Guard·2차 확인·실전 엔진 런타임 외부 jar)를 다룬다. 프로젝트 지침 10·11장에 따라 risk-ops 검토 뒤 사용자 머지. 브랜치에 `docs/reviews/pr-108/risk-ops.md`(56f0d31)가 있으나 이 검토는 그 내용을 근거로 쓰지 않았다 |
| B8 | 통과 | diff에서 `appkey|secret|password|token|account-number` 검색 결과는 문서 설명 문장뿐이고 실제 값 없음. 픽스처에 키·계좌번호 없음 |

## 점검표 밖 발견

### AR-1 (수정 필요) 금지 목록 방식이라 JDK 안의 다른 반사 호출 경로가 남는다
- `[사실]` 884d443 `RealPathReflectionTest.java:149-170`의 `reflective()`는 소유 타입이 `java.lang.invoke.*`, `java.lang.reflect.{Proxy,Method,Constructor,Field,AccessibleObject}`, `java.lang.Class`(`forName`·`newInstance`만), `ClassLoader`, `ObjectInputStream`, `ServiceLoader`일 때만 걸린다. 패키지 금지(`:177-179`)는 `java.beans`, `javax.script`, `java.lang.instrument`, `com.sun.tools.attach`, `sun.misc`, `sun.reflect`, `jdk.internal`뿐이다.
- 그래서 다음 코드는 규칙에 걸리지 않는다 `[사실]`(위 판정식에 해당하는 소유 타입·이름이 없음):
  1. `Class#getMethods()`(금지 아님)로 `Method`를 얻고 `new javax.management.modelmbean.ModelMBeanOperationInfo("x", m)` → `RequiredModelMBean#setManagedResource(guard, "ObjectReference")` → `RequiredModelMBean#invoke("beginActivation", ..)`. 호출은 JDK 내부에서 리플렉션으로 일어난다. 이것으로 E8과 같은 일을 할 수 있다고 본다 `[추론]`(변조 실험은 권한 거부로 못 함).
  2. `System.load`·`System.loadLibrary`·`Runtime.load*`와 `native` 메서드 선언(JNI의 `CallVoidMethod` 등으로 아무 메서드나 부른다) `[추론]`.
  3. 같은 종류: `java.lang.management`·`javax.management`(MXBean 프록시), `javax.naming`(객체 팩토리), `java.rmi`, `javax.tools`·`jdk.jshell`(실행 중 컴파일·실행), `java.lang.foreign`(네이티브 호출) `[추론]`.
- 고칠 방향(추천순):
  - 가. 실전 경로 운영 클래스의 JDK 의존을 **허용 패키지 목록**으로 바꾼다(예: `java.lang`(위 금지 메서드 제외)·`java.util..`·`java.time..`·`java.math..`·`java.nio..`·`java.io`(직렬화 제외)·`java.net..`·`java.security..`·`javax.crypto..`·`java.util.concurrent..`). 새 JDK 패키지를 쓰려면 표를 고치는 보호 테스트 변경이 되어 ServiceLoader·외부 jar 허용 목록과 같은 방식이 된다. 이 PR이 외부 jar에 쓴 "정확히 일치" 원칙을 JDK에도 적용하는 것이다.
  - 나. 최소한 위 1~3의 패키지·메서드를 금지 목록에 더하고 `native` 메서드 선언을 금지한다. 그리고 `Method`·`Field`·`Constructor`·`RecordComponent#getAccessor` 객체를 얻는 호출(`Class#getMethod(s)`·`getDeclared*`)도 실전 경로에서 금지하고, 구조만 읽는 `SecretPaths` 같은 클래스는 허용 클래스 목록으로 둔다. 리플렉션 객체를 얻지 못하면 1번처럼 JDK API에 넘길 것이 없어진다.
  - 어느 쪽이든 1·2번 형태의 위반 고정 예제를 `규칙은_리플렉션_우회를_실제로_잡아낸다`에 더한다.

### AR-2 (수정 필요) 허용 목록 외부 jar(Jackson)에 리플렉션을 맡기는 경로가 남는다
- `[사실]` Jackson 관련 검사(`:193-226`)는 이름에 `DefaultTyping`이 든 호출과 `@JsonTypeInfo(use = CLASS·MINIMAL_CLASS·CUSTOM)`만 본다. `SECOND_CONFIRMATION_IS_NOT_DATA`(`:234-238`)는 `SecondConfirmation` 한 타입만 본다. 실전 경로 런타임에 `jackson-databind`가 있고(허용 목록), `autotrade-config`·`autotrade-control`·`autotrade-broker-kis`가 이미 쓴다.
- 그래서 다음은 규칙에 걸리지 않는다 `[사실]`:
  1. `new ObjectMapper().setVisibility(PropertyAccessor.FIELD, Visibility.ANY).readerForUpdating(liveObject).readValue(json)` 또는 `updateValue(liveObject, map)`. Jackson이 `setAccessible`로 **이미 있는 객체의 private 필드를 덮어쓴다**(기본값 `CAN_OVERRIDE_ACCESS_MODIFIERS`·`ALLOW_FINAL_FIELDS_AS_MUTATORS` 켜짐) `[추론]`.
  2. `convertValue(map, <Guard 패키지 다른 타입 또는 출구 타입>.class)`, `com.fasterxml.jackson.databind.util.ClassUtil#createInstance(cls, true)`(인자 없는 생성자를 접근 제한을 풀고 부른다).
- D-085 "위험과 한계"는 "Guard 패키지의 다른 타입을 데이터 바인딩으로 **만드는** 경로"만 적었다. 1번처럼 살아 있는 Guard·출구·허가 객체의 상태를 **바꾸는** 경로는 적혀 있지 않고, 2장 전제("생성은 Guard 안에서만")로도 막히지 않는다. 지금 `RealTradingGuard`의 내부 구조(`AccountGuard`의 `volatile Snapshot`, RealTradingGuard.java:331-337)로 JSON 덮어쓰기가 실제로 성공하는지는 확인하지 못했다(변조 실험 권한 거부).
- 고칠 방향(추천순):
  - 가. 실전 경로에서 `com.fasterxml.jackson.databind..` 접근을 **허용 클래스 목록**(지금 쓰는 `ConfigJson`·`ConfigLoader`·`RuntimeConfig`·`RuntimeConfigStore`·`Secret`·`SecretPaths`·`ControlCodec`·`KisTokenClient`·`KisStreamProtocol`·`KisApprovalClient` 등)으로 한정하고(ServiceLoader와 같은 방식), 그 클래스들이 `com.autotrade.risk.guard..`와 출구 타입(`OrderGateway`·`BrokerPlugin`)에 의존하지 않는다는 규칙을 더한다.
  - 나. 최소한 `setVisibility`·`readerForUpdating`·`updateValue`·`convertValue`·`databind.util.ClassUtil`·`databind.introspect..` 접근을 실전 경로에서 금지한다.
  - 1번 형태의 위반 고정 예제를 더한다.

### AR-3 (수정 필요, 작음) 주석 밖에서 클래스 지정 타입 ID를 쓰는 경우
- `[사실]` `@JsonTypeInfo` 주석만 보므로, 실전 경로 클래스가 `AnnotationIntrospector#findTypeResolver`를 재정의해 `StdTypeResolverBuilder().init(JsonTypeInfo.Id.CLASS, null)`을 돌려주고 `setAnnotationIntrospector`로 끼우면 "DefaultTyping" 이름 검사와 주석 검사를 모두 피한다.
- 고칠 방향: 실전 경로에서 `JsonTypeInfo$Id`의 `CLASS`·`MINIMAL_CLASS`·`CUSTOM` 상수 읽기(정적 필드 접근)를 금지한다. AR-2 가안을 택하면 허용 클래스 밖에서는 자동으로 막히지만, 허용 클래스 안에서도 이 상수 읽기는 막는 것이 맞다.

### 참고 (판정에 넣지 않음)
- 범위 계산을 Gradle 해석 결과로 한 것과 클래스 출처(선언 출력) 판정 + 패키지 이중 판정은 PR #21 Q-1의 범위 우회를 되풀이하지 않는 좋은 설계다. 증권사 플러그인을 엔진 의존 전부터 범위에 넣은 것도 맞다.
- `FORBIDDEN_PROJECTS`는 `processIsolation.forbiddenProjects`(autotrade-app-engine/build.gradle.kts)와 같은 목록을 두 군데 손으로 들고 있다. 한쪽만 늘면 어긋난다. 다음에 고칠 때 빌드 쪽 값을 시스템 속성으로 넘겨 대조하는 것을 권한다.

## 판정
수정 필요(AR-1, AR-2, AR-3)

## 재검토 2026-10-14
- 대상: PR #108 / 커밋 618ae22 / main 대비 diff(`git diff origin/main...618ae22`). 앞 검토(884d443) 뒤 바뀐 것은 `git diff 884d443 618ae22`(테스트 1개 파일 수정, 고정 예제 4개 추가, ADR-002 9.5·D-085·결정 로그 갱신)
- 점검표: B
- 직접 실행한 것(공유 작업 트리를 건드리지 않으려고 `git worktree add <스크래치>/wt 618ae22`로 만든 별도 작업 트리에서 실행했고, 끝난 뒤 `git worktree remove`로 지웠다):
  - `./gradlew clean build --no-daemon` → `BUILD SUCCESSFUL in 4m 23s`, 종료 코드 0. `:autotrade-architecture-test:test`가 실제로 실행됐다(UP-TO-DATE 아님). 결과 XML 합계 테스트 1,107건, 실패·오류·건너뜀 0. `RealPathReflectionTest`는 6건, 실패 0
  - 변조 실험(커밋하지 않음, 별도 작업 트리에서만): `autotrade-config`에 `com.autotrade.config.qaprobe`의 4개 클래스를 넣고 `:autotrade-architecture-test:test --tests '*RealPathReflectionTest'` 실행 → 종료 코드 1, `실전_경로_모듈은_리플렉션을_쓰지_않는다`만 실패(위반 12건). 결과는 아래 표와 같다
    | 프로브 | 내용 | 결과 |
    |---|---|---|
    | `JmxInvoker` | `getMethods()` → `ModelMBeanOperationInfo` → `RequiredModelMBean#invoke` (앞 검토 AR-1) | 잡힘 |
    | `JacksonWriter` | `new ObjectMapper().readerForUpdating(target).readValue(json)` (앞 검토 AR-2) | 잡힘 |
    | `ProcessSpawner` | `new ProcessBuilder("jcmd", pid, "JVMTI.agent_load", jar).start()`, `Runtime.getRuntime().exec(..)` | **안 잡힘** |
    | `CloneByClass` | `new ObjectMapper().readValue(json, live.getClass())` | **안 잡힘** |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 종료 코드 0, 테스트 1,107건 실패 0. 아키텍처 테스트도 실제로 실행됨 |
| B2 | 통과 | 새 규칙은 테스트 모듈 안에만 있고 운영 모듈 의존 방향은 그대로. 규칙 변경은 ADR-002 9.5 5항과 D-085 1·4항에 기록됨. 운영 코드에 대한 기존 규칙과 새 규칙이 모두 위반 0건으로 통과(위 빌드) |
| B3 | 통과 | 운영 계약 변경 없음. 허용 목록(`ALLOWED_JDK_PACKAGES` 23개, `ALLOWED_REFLECT_TYPES` 3개, `JACKSON_ALLOWLIST` 27개)과 규칙 `GUARD_TYPES_ARE_NOT_DATA`가 D-085·ADR-002 9.5에 적혀 있다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 새 금지 항목마다 고정 예제가 있다: `JacksonObjectAccess`(5건), `GuardAsData`(3건), `DynamicCode`(8종), `PolymorphicJson.ClassIdIntrospector`, `qa.probe.OutsideReflective`(`#합친_범위는_실전_경로_모듈의_com_autotrade_밖_패키지도_잡는다`). 지우거나 약화한 테스트는 없다. `규칙은_리플렉션_우회를_실제로_잡아낸다`에서 `doesNotContain("ByName")` 단언이 빠졌는데, `@JsonTypeInfo(use = NAME)`이 이제 허용 목록 밖 의존으로 걸리기 때문이다. 대신 `doesNotContain("@JsonTypeInfo(use = NAME)")`로 다형 검사가 NAME을 잡지 않는 것은 계속 확인한다(RealPathReflectionTest.java 618ae22 기준 443행). 약화가 아니라 규칙이 넓어진 결과다 |
| B7 | risk-ops 검토 필요 | 보호 테스트 강화이고 실전 경로(Guard·2차 확인·실전 엔진 런타임)를 다룬다. risk-ops 재검토 뒤 사용자 머지 |
| B8 | 통과 | 추가 고정 예제와 문서에 키·계좌번호·토큰 값 없음(`/x.so`, `"x"` 같은 자리 값뿐) |

### 앞 검토 지적의 처리
- AR-1: 해소. JDK 의존이 허용 패키지 목록(`ALLOWED_JDK_PACKAGES`)으로 바뀌었다. 금지 목록(`DYNAMIC_CODE_PACKAGES`)에는 `javax.management`·`java.lang.management`·`javax.naming`·`java.rmi`·`javax.tools`·`jdk.jshell`·`java.lang.foreign`·`jdk.dynalink`이 더해졌다. `System`·`Runtime`의 `load·loadLibrary`, `native` 선언, `Module#add*`, `Atomic*FieldUpdater`도 금지된다. 변조 실험 `JmxInvoker`가 잡혔다.
- AR-2: 해소. Jackson은 허용 타입 27개만 쓸 수 있고, 그 안의 위험 메서드(`JACKSON_OBJECT_ACCESS`)도 막힌다. Guard 타입을 바인딩 대상으로 넘기는 표지는 `GUARD_TYPES_ARE_NOT_DATA`가 막는다. 변조 실험 `JacksonWriter`가 잡혔다.
- AR-3: 해소. `JsonTypeInfo`와 `JsonTypeInfo$Id`가 허용 목록 밖이라, 상수 읽기와 `JacksonAnnotationIntrospector` 상속이 모두 걸린다(고정 예제 `ClassIdIntrospector`).

### 새 지적

#### AR-4 (수정 필요) 허용 패키지 `java.lang`의 외부 프로세스 실행으로 자기 JVM에 에이전트를 붙일 수 있다
- `[사실]` 변조 실험 `ProcessSpawner`(`ProcessBuilder#start`, `Runtime#exec`)가 규칙에 걸리지 않았다. `reflective()`의 `java.lang.Runtime` 분기는 `load`·`loadLibrary`만 보고, `ProcessBuilder`에 대한 분기는 없다. `java.lang`은 허용 패키지다.
- `[추론]` `jcmd <자기 pid> JVMTI.agent_load <jar>`(또는 `jattach` 같은 도구)를 실행하면 실전 엔진 JVM 안에 에이전트가 올라간다. 에이전트는 `java.lang.instrument`로 아무 코드나 실행하고 Guard 상태를 바꿀 수 있다. Java 21은 동적 에이전트 적재를 경고만 내고 허용한다(JEP 451). 저장소에는 `-XX:-EnableDynamicAgentLoading`이나 `-XX:+DisableAttachMechanism` 설정이 없다(`grep` 결과는 collector의 `-Xmx2g`뿐). D-085 "보장하지 않는 것"의 "`java.lang` 안의 아직 모르는 간접 호출 수단"에 해당하지만, 이제는 알려진 경로이고 막는 비용이 작다.
- 실전 경로 운영 코드는 지금 `ProcessBuilder`·`Runtime#exec`를 쓰지 않는다(618ae22 `grep` 결과 없음).
- 고칠 방향:
  - (가) `reflective()`에 `java.lang.ProcessBuilder`의 `start`·`startPipeline`과 `java.lang.Runtime#exec`를 더하고, `DynamicCode` 고정 예제에 두 건을 더한다.
  - (나) 실전 엔진·감시 프로세스 시작 옵션에 `-XX:+DisableAttachMechanism`을 넣는 것을 7단계 실행 절차서 설치본 대조 항목에 함께 적는다(빌드 밖 경로까지 막는다).
  - (가)는 이 PR에서 해야 한다. (나)는 후속 과제로 기록하면 된다.

#### AR-5 (수정 필요, 작음) `GUARD_TYPES_ARE_NOT_DATA`의 설명이 사실보다 강하다
- `[사실]` 변조 실험 `CloneByClass`(`readValue(json, live.getClass())`)가 걸리지 않았다. 규칙의 근거 주석(RealPathReflectionTest.java 618ae22 기준 360-364행 "이것이 없으면 Jackson 같은 라이브러리가 Guard 타입 객체를 새로 만들 수 없다")과 D-085 "보장하지 않는 것" 첫 항("Guard 객체를 새로 만들려면 Guard 타입을 넘겨야 하고(4번이 막음)")은 이 경로를 막는다고 적었다. 그러나 이미 받은 Guard 타입 객체의 `getClass()`로도 클래스 객체를 넘길 수 있다.
- `[추론]` 지금 Guard 패키지 타입(`OrderSessionPermit`은 package-private 생성자에 `@JsonCreator`가 없고, `RealTradingGuard`는 인자 있는 생성자뿐이며, 매개변수 이름 모듈도 실전 경로 런타임에 없다)은 기본 설정 Jackson으로 만들어지지 않을 가능성이 높다. 그래서 실제 위험은 낮다.
- 고칠 방향: 주석과 D-085의 설명을 "클래스 객체 표지와 Guard 타입에 Jackson이 쓸 생성자가 없다는 것에 함께 기댄다"로 바로잡고, `getClass()` 경로를 남은 한계에 적는다. 할 수 있으면 "Guard 패키지 타입에 `@JsonCreator`·`@JsonProperty`·인자 없는 생성자가 없다"는 규칙을 더해 그 전제를 테스트로 고정한다.

## 판정
수정 필요(AR-4, AR-5)

## 재검토 2026-10-14 (2)
- 대상: PR #108 / 커밋 dbdb953 / main 대비 diff(`git diff origin/main...dbdb953`). 앞 재검토(618ae22) 뒤 바뀐 것은 `git diff 618ae22 dbdb953`이다: `RealPathReflectionTest.java`(클래스 로더·`ResourceBundle`·`ProcessBuilder`·`Runtime#exec` 금지, `#Guard_타입은_Jackson이_만들_수_있는_모양이_아니다` 추가), `DynamicCode.java` 고정 예제, ADR-002 9.5, D-085, 검토 원문 2개
- 점검표: B
- 직접 실행한 것(별도 작업 트리 `git worktree add <스크래치>/wt2 dbdb953`에서 실행했고, 끝난 뒤 `git worktree remove`로 지웠다. 공유 작업 트리 소스는 건드리지 않았다):
  - `./gradlew clean build --no-daemon` → `BUILD SUCCESSFUL in 4m 3s`, 종료 코드 0. 결과 XML 합계 테스트 1,108건, 실패·오류·건너뜀 0. `RealPathReflectionTest`는 7건, 실패 0
  - 변조 실험(커밋하지 않음): `autotrade-config`에 `com.autotrade.config.qaprobe`의 3개 클래스를 넣고 `:autotrade-architecture-test:test --tests '*RealPathReflectionTest'` 실행 → 종료 코드 1, 위반 5건
    | 프로브 | 내용 | 결과 |
    |---|---|---|
    | `ProcessSpawner` | `new ProcessBuilder("jcmd", pid, "JVMTI.agent_load", jar).start()`, `Runtime.getRuntime().exec(..)` (AR-4) | 잡힘 |
    | `LoaderProbe` | `ClassLoader.getSystemClassLoader()`, `o.getClass().getClassLoader()` | 잡힘 |
    | `ProviderProbe` | `java.security.Provider` 익명 하위 클래스에 `put("MessageDigest.Y", className)` → `getService(..).newInstance(null)` | **안 잡힘** |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 종료 코드 0, 테스트 1,108건 실패 0 |
| B2 | 통과 | 규칙 변경은 테스트 모듈 안에만 있다. ADR-002 9.5 2항과 D-085 1·4항이 갱신됐다. 운영 코드는 넓어진 규칙에서도 위반 0건이다(위 빌드) |
| B3 | 통과 | 운영 계약 변경 없음. 새 고정 목록 `GUARD_RECORDS`(3개)가 D-085 4항에 적혀 있다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 새 금지 항목은 `DynamicCode` 고정 예제와 `규칙은_리플렉션_우회를_실제로_잡아낸다`의 단언으로 확인된다(`URLClassLoader.newInstance`, `ResourceBundle.getBundle`, `Thread.getContextClassLoader`, `ProcessBuilder.<init>`·`start`, `Runtime.exec`). Guard 타입 모양 고정은 `#Guard_타입은_Jackson이_만들_수_있는_모양이_아니다`가 맡는다. 이 테스트는 레코드 집합을 정확히 일치시키므로, 거르는 조건(`/autotrade-risk/`)이 모든 클래스를 빼 버려도 통과하지 못한다. 기존 `default` 분기의 클래스 로더 조건은 앞쪽의 더 넓은 조건(로더 타입의 모든 메서드)으로 옮겨졌을 뿐이라 약화가 아니다. 지우거나 건너뛴 테스트는 없다 |
| B7 | risk-ops 검토 필요 | 보호 테스트 강화이고 실전 경로(Guard)를 다룬다. risk-ops 재검토 뒤 사용자가 머지한다 |
| B8 | 통과 | 고정 예제와 문서에 비밀값 없음 |

### 앞 재검토 지적의 처리
- AR-4: 해소. `ProcessBuilder`의 모든 접근과 `Runtime#exec`가 금지됐고(RealPathReflectionTest.java dbdb953 기준 191-193행), 변조 실험 `ProcessSpawner`가 잡혔다. 후속 `-XX:+DisableAttachMechanism`은 D-085 "보장하지 않는 것"에 7단계 설치본 대조 항목으로 적혔다.
- AR-5: 해소. 잘못된 설명은 D-085 4항, ADR-002 9.5, 테스트 주석에서 바로잡혔다. `getClass()` 경로는 남은 한계로 적혔다. 전제("Guard 타입에 Jackson 주석과 인자 없는 생성자가 없고, 레코드는 3개뿐")는 테스트로 고정됐다.
  - 직접 확인: Guard 패키지에서 인자가 하나인 생성자는 `AccountGuard(AccountRef)`(private 중첩 클래스, 스칼라 인자가 아님)와 `GuardClosedException(String)`(예외라 제외)뿐이다. 매개변수 이름 모듈과 `MapperFeature`·`ConstructorDetector`는 실전 경로 런타임 또는 허용 목록 밖이다. 그래서 기본 설정 Jackson이 이 클래스들을 만들 길은 없다 `[추론]`.

### 새 지적

#### AR-6 (수정 필요, 작음) `java.security.Provider.Service#newInstance`로 이름 문자열의 클래스를 만들 수 있다
- `[사실]` 변조 실험 `ProviderProbe`가 걸리지 않았다. 동작: `Provider` 하위 클래스에 `put("MessageDigest.Y", <클래스 이름 문자열>)`을 하고 `getService("MessageDigest", "Y").newInstance(null)`을 부른다. `java.security`는 허용 패키지이고, `reflective()`에는 `Provider`에 대한 분기가 없다.
- `[추론]` JDK가 그 이름으로 클래스를 적재해 public 생성자를 부른다. 이는 `Class#forName` + `Constructor#newInstance`(금지)와 같은 일이다. 앞 재검토 반영에서 같은 이유("이름으로 클래스를 찾아 만든다")로 `ResourceBundle`을 막았으므로, 같은 기준을 적용해야 한다. 지금 Guard 패키지에는 public 인자 없는 생성자가 없어 Guard 객체가 직접 만들어지지는 않는다. 다만 그 밖의 우리 클래스나 JDK 클래스는 이름 문자열로 생성할 수 있다.
- 실전 경로 운영 코드는 지금 `Provider`를 쓰지 않는다(빌드 위반 0건). 그래서 막아도 깨지는 코드는 없다.
- 고칠 방향: `reflective()`에 `java.security.Provider$Service#newInstance`를 더하고 `java.security.Provider`를 상속하거나 생성하지 못하게 한다. `Security#addProvider`·`insertProviderAt`도 함께 막는다. 이 경로를 `DynamicCode` 고정 예제에 더한다.

### 참고 (판정에 넣지 않음)
- 이번에 금지 목록에 들어간 항목들(`ResourceBundle`, `ProcessBuilder`, `Provider`)은 모두 허용 패키지 `java.lang`·`java.util`·`java.security` 안에서 나왔다. D-085가 이미 "허용 패키지 안의 아직 모르는 간접 호출 수단"을 한계로 적고 있다. 7단계 전에 `java.util`·`java.security`를 클래스 단위 허용 목록으로 좁히는 것을 검토하길 권한다. 패키지 단위 허용으로 남겨 두면 이런 발견이 계속 하나씩 나온다.

## 판정
수정 필요(AR-6)

## 재검토 2026-10-14 (3)
- 대상: PR #108 / 커밋 4006964 / main 대비 diff(`git diff origin/main...4006964`). 앞 재검토(dbdb953) 뒤 바뀐 것은 `git diff dbdb953 4006964`(`RealPathReflectionTest.java`, `DynamicCode.java`, `JacksonObjectAccess.java`, ADR-002 9.5, D-085, 검토 원문 2개)
  - JDK 5개 패키지(`java.lang`·`java.util`·`java.security`·`java.net`·`java.io`)를 클래스 단위로 허용하도록 바꿨다(`CLASS_LEVEL_JDK_PACKAGES`, `ALLOWED_JDK_CLASSES`)
  - 외부 명령 실행 허용 목록 `ALLOWED_PROCESS_LAUNCHERS`를 비어 있는 채로 추가하고, 비어 있음을 테스트가 고정한다
  - Jackson 모듈 발견(`findAndRegisterModules` 등)을 금지했다
- 점검표: B
- 직접 실행한 것(별도 작업 트리 `git worktree add <스크래치>/wt3 4006964`에서 실행하고 끝난 뒤 `git worktree remove`로 지웠다. 공유 작업 트리 소스는 건드리지 않았다):
  - `./gradlew clean build --no-daemon` → `BUILD SUCCESSFUL in 3m 25s`, 종료 코드 0. 결과 XML 합계 테스트 1,108건, 실패·오류·건너뜀 0. `RealPathReflectionTest`는 7건, 실패 0
  - 변조 실험(커밋하지 않음): `autotrade-config`에 `com.autotrade.config.qaprobe`의 2개 클래스를 넣고 `:autotrade-architecture-test:test --tests '*RealPathReflectionTest'` 실행 → 종료 코드 1, 위반 5건
    | 프로브 | 내용 | 결과 |
    |---|---|---|
    | `ProviderProbe` | `Provider` 익명 하위 클래스, `put`, `getService(..).newInstance(null)` (AR-6) | 잡힘(`ProviderProbe$1`·`byName`) |
    | `PropertyProbe` | `System.setProperty("java.util.logging.config.class", className)` 뒤 `System.getLogger("x").log(..)` | **안 잡힘** |
  - 별도 확인(저장소 밖 스크래치, 실행 후 지움): 같은 두 줄을 넣은 단독 Java 21 프로그램이 `Main$Hook`을 이름으로 생성했다(생성자의 `HOOK CONSTRUCTED by name` 출력 확인). JUL `LogManager`가 `java.util.logging.config.class` 속성의 클래스를 리플렉션으로 적재·생성하기 때문이다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 종료 코드 0, 테스트 1,108건 실패 0 |
| B2 | 통과 | 규칙 변경은 테스트 모듈 안에만 있다. ADR-002 9.5와 D-085가 갱신됐다. 운영 코드는 클래스 단위 허용에서도 위반 0건이다(위 빌드). 허용 클래스 목록이 실제 사용 클래스로 채워져 있어 빈 목록으로 통과하는 일은 없다 |
| B3 | 통과 | 운영 계약 변경 없음. 새 목록 `ALLOWED_JDK_CLASSES`·`ALLOWED_PROCESS_LAUNCHERS`가 D-085에 적혔다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 새 항목마다 고정 예제와 단언이 있다: `DynamicCode#viaProvider`(`허용 목록 밖 타입 java.security.Provider`, `Provider$Service.newInstance`, `java.security.Security`), `JacksonObjectAccess#discoverModules`(`findAndRegisterModules`), `ALLOWED_PROCESS_LAUNCHERS` 비어 있음 단언. 기존 단언은 하나도 빠지지 않았다 |
| B7 | risk-ops 검토 필요 | 보호 테스트 강화이고, 7단계 감시 재기동(외부 명령 허용 목록)과 연결된다. risk-ops 재검토 뒤 사용자가 머지한다 |
| B8 | 통과 | 비밀값 없음 |

### 앞 재검토 지적의 처리
- AR-6: 해소. `java.security`가 클래스 단위 허용으로 바뀌어 `Provider`·`Provider$Service`·`Security` 의존이 걸린다. 변조 실험 `ProviderProbe`가 잡혔다.
- 참고(클래스 단위 허용): 반영됐다. 5개 패키지를 클래스 단위로 좁혀서, SPI 구현(`CharsetProvider`, `System$LoggerFinder` 등)도 허용 목록 밖 타입으로 걸린다. `java.util.concurrent`·`java.nio.*`·`javax.crypto` 등은 패키지 단위로 남았다. 이 패키지들에서 로더나 `Provider`를 주고받는 API는 로더 인자 규칙과 `Provider` 타입 금지로 걸린다고 본다 `[추론]`.

### 새 지적

#### AR-7 (수정 필요, 작음) `System#setProperty`로 JDK가 이름 문자열의 클래스를 만들게 할 수 있다
- `[사실]` `java.lang.System`은 허용 클래스이고, `reflective()`의 `System` 분기는 `load`·`loadLibrary`만 막는다(RealPathReflectionTest.java 4006964 기준 196-197행). 변조 실험 `PropertyProbe`가 걸리지 않았다. 단독 실행에서 `System.setProperty("java.util.logging.config.class", <이름>)` + `System.getLogger(..)` 호출로 그 이름의 클래스가 생성되는 것을 확인했다.
- AR-6(`Provider`)·`ResourceBundle`과 같은 종류다("이름으로 클래스를 찾아 만든다"). 같은 방식으로 JDK 동작을 바꾸는 속성이 더 있다 `[추론]`: `java.util.logging.config.file`의 `handlers=`, `java.rmi.server.codebase`, 프록시·TLS 속성.
- 실전 경로 운영 코드는 지금 `System.setProperty`·`setProperties`·`clearProperty`를 쓰지 않는다(4006964에서 `grep` 결과 없음). 그래서 막아도 깨지는 코드는 없다.
- 고칠 방향: `reflective()`의 `java.lang.System` 분기에 `setProperty`·`setProperties`·`clearProperty`를 더하고, `DynamicCode`에 고정 예제를 더한다. 설정은 `RuntimeConfigHolder`로 읽으므로 실전 경로가 JVM 속성을 바꿀 이유는 없다. 실행 옵션으로 줄 속성은 7단계 실행 절차서의 설치본 대조 항목에서 다룬다.

## 판정
수정 필요(AR-7)

## 재검토 2026-10-14 (4)
- 대상: PR #108 / 커밋 87863e8 / main 대비 diff(`git diff origin/main...87863e8`). 앞 재검토(4006964) 뒤 바뀐 것(`git diff 4006964 87863e8`):
  - `RealPathReflectionTest.java`의 `System` 분기에 `setProperty`·`setProperties`·`clearProperty` 금지를 더했다(196-199행)
  - `DynamicCode#viaSystemProperty` 고정 예제와 그 단언을 더했다
  - ADR-002 9.5 2항과 남은 한계, D-085 1항과 남은 한계를 고쳤다(`-D` 실행 옵션 대조를 7단계 설치본 대조로 넘김)
  - 검토 원문 2개가 추가됐다
- 점검표: B
- 직접 실행한 것(별도 작업 트리 `git worktree add <스크래치>/wt4 87863e8`에서 실행했고, 끝난 뒤 `git worktree remove`로 지웠다. 공유 작업 트리 소스는 건드리지 않았다):
  - `./gradlew clean build --no-daemon`: `BUILD SUCCESSFUL in 3m 42s`, 종료 코드 0. 결과 XML 합계 테스트 1,108건, 실패·오류·건너뜀 0. `RealPathReflectionTest`는 7건 실행, 실패 0
  - 변조 실험(커밋하지 않음): `autotrade-config`에 `com.autotrade.config.qaprobe`의 2개 클래스를 넣고 `:autotrade-architecture-test:test --tests '*RealPathReflectionTest'`를 실행했다. 종료 코드 1, 위반 2건
    | 프로브 | 내용 | 결과 |
    |---|---|---|
    | `PropertyProbe` | `System.setProperty("java.util.logging.config.class", className)` + `System.getLogger(..)` (AR-7) | 잡힘 |
    | `PropsViaGet` | `System.getProperties().put("java.util.logging.config.class", className)` (금지 메서드를 피해 `Properties`를 직접 고치는 우회) | 잡힘(`java.util.Properties`가 허용 클래스 목록 밖) |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew clean build` 종료 코드 0, 테스트 1,108건 실패 0 |
| B2 | 통과 | 규칙 변경은 테스트 모듈 안에만 있다. ADR-002 9.5와 D-085가 갱신됐다. 운영 코드 위반 0건(위 빌드) |
| B3 | 통과 | 운영 계약 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 새 금지 항목에 고정 예제(`DynamicCode#viaSystemProperty`)와 단언(`"System.setProperty", "System.setProperties", "System.clearProperty"`)이 있다. 기존 단언 삭제나 약화는 없다 |
| B7 | risk-ops 검토 필요 | 보호 테스트 강화이고 실전 경로(Guard)를 다룬다. risk-ops 검토 뒤 사용자가 머지한다 |
| B8 | 통과 | 비밀값 없음 |

### 앞 재검토 지적의 처리
- AR-7: 해소. 변조 실험에서 `PropertyProbe`가 잡혔고, `Properties`를 직접 고치는 우회도 클래스 단위 허용 목록 때문에 잡혔다.

### 전체 지적 상태
- AR-1~AR-7은 모두 해소됐다.
- 남은 한계는 D-085 "보장하지 않는 것"과 ADR-002 9.5 "남은 한계"에 적혀 있다:
  - 허용 클래스·패키지 안의 아직 모르는 간접 호출 수단, Jackson 기본 동작 안의 리플렉션 `[추론]`
  - `Object`로 받은 객체의 `getClass()`
  - 설치본·JVM 옵션(`-javaagent`, `-D`, `-XX:+DisableAttachMechanism`). 7단계 설치본 대조로 넘겼다
- 이 한계들은 정적 규칙으로 닫을 수 없거나 빌드 밖의 문제다. 이 PR의 범위(바이트코드 규칙)에서 더 요구할 것은 없다.

### 새 지적
- 없음.

## 판정
승인

## 재검토 2026-10-14 (헤드 743545c)
- 대상: PR #108 / 커밋 743545c / main 대비 diff 전체(`git diff origin/main...743545c`, 21파일 +1,932 −3). 직전 재검토(87863e8) 뒤 바뀐 것은 main 병합(89daece, 결정 번호 D-086·기획서 v14.32), 독립 QA 원문(679c916), 그리고 QA F1·F2 반영 커밋 743545c다. 743545c가 바꾼 것(`git show 743545c --stat`):
  - 새 `ClassFileTypes.java`(클래스 파일 상수 풀의 타입 이름 수집)와 `outsideAllowlist`의 확장(호출 대상 매개변수·반환 타입, 상수 풀 전체. `RealPathReflectionTest.java:357`)
  - 새 `classAsData`(`Class.class` 클래스 객체, `Class`가 들어간 필드·바인딩 매개변수·제네릭 인자, 목록 밖 `Class` 메서드. `RealPathReflectionTest.java:403`)와 `ALLOWED_CLASS_METHODS`(`:390`), `JAVAC_BOOTSTRAP_TYPES`(`:347`)
  - 허용 목록 추가: JDK 패키지 `java.security.spec`·`java.time.chrono`·`java.time.temporal`, JDK 클래스 13개, Jackson 타입 7개
  - 고정 예제 `NameToClassBinding`(Q16 모양), 허용 예제 `ReflectionFreeEngineCode.parse`, ADR-002 9.5 6항·남은 한계, D-086 1항·위험과 한계·검증
- 점검표: B
- PR 본문, 커밋 메시지, D-086의 검증 주장은 사실로 받지 않고 아래 실행으로만 판정했다
- 직접 실행한 것(별도 작업 트리 `/tmp/claude-0/wt-architect-5`, `origin/claude/project-thread-l44wj7` = 743545c. 공유 작업 트리는 건드리지 않았다):
  - `./gradlew build --rerun-tasks`: `BUILD SUCCESSFUL in 3m 4s`, 130개 작업 모두 실행, 종료 코드 0. 결과 XML 합계 테스트 1,130건, 실패·오류·건너뜀 0. `RealPathReflectionTest`는 7건 실행, 실패 0
  - 변조 실험(커밋하지 않음, 끝난 뒤 원복하고 `git status` 깨끗함 확인):

    | 프로브 | 위치 | 내용 | 규칙 결과 | 단독 실행 결과(Jackson 2.21.4) |
    |---|---|---|---|---|
    | Q16 재현 | `autotrade-app-engine`의 `com.autotrade.app.engine.command.QProbe`, 엔진에 `jackson-databind` `implementation` 추가 | QA Q16과 같은 `readValue(.., Class.class)` + `convertValue(fields, k)` + `(MapperFeature) f` + `configure(..)` | **잡힘**(`Class.class를 참조`, `MapperFeature`의 매개변수·반환 타입) | 해당 없음 |
    | 배열 Class 필드 | 같은 패키지 `record QArrField(Class<?>[] types)` | 레코드 구성 요소가 `Class[]` | **잡힘**(`QArrField.types 필드 타입에 Class`) | 해당 없음 |
    | P1 배열 클래스 객체 | `autotrade-control`의 `com.autotrade.control.zprobe.ArrProbe` | `Class<?>[] ks = M.readValue("[\"" + name + "\"]", Class[].class)` | **안 잡힘**. `:autotrade-control:build :autotrade-architecture-test:test` `BUILD SUCCESSFUL`(리플렉션·소유·ArchitectureTest 규칙 모두 통과) | `class java.util.concurrent.atomic.AtomicLong`을 이름 문자열에서 얻음 |
    | P2 `Class`의 `getClass()` | 같은 패키지 `GetClassProbe` | `Class<?> cc = String.class.getClass(); M.readValue("\"" + name + "\"", cc)` | **안 잡힘**(위와 같은 실행) | `class java.util.concurrent.atomic.AtomicLong`을 얻음 |
    | P3 주석 없는 `valueOf(Class)` 정적 팩토리 | 단독 실행만 | `static ValueOfProbe valueOf(Class<?> c)` 위임 생성자 | 확인 안 함(아래 이유) | Jackson이 위임 생성자로 쓰지 않음("no String-argument constructor/factory method"). 경로가 아니다 |

  - 바이트코드 확인: `javap -c`로 P1이 `ldc class "[Ljava/lang/Class;"` 뒤 `ObjectMapper.readValue:(Ljava/lang/String;Ljava/lang/Class;)`를 부르는 것, P2와 `Class<?> c; c.getClass()` 모두 `invokevirtual java/lang/Object.getClass`로 나오는 것을 확인했다(D-086 위험과 한계의 javac 설명은 사실이다)
  - 새 허용 목록 항목의 근거: `grep`으로 실전 경로 운영 코드에서 `java.security.spec`(`OperatorPasswordHash`), `Thread.ofPlatform`(`FeedServer`·`ControlServer`), `ProtocolFamily`(`FeedServer` 등), `PropertyNamingStrategy`·`JsonSerializer`·`StdSerializer`(`ConfigJson`), `TreeNode`·`readTree`(`RecordingCodec`·`KisTokenClient` 등) 사용을 확인했다. 상수 풀 검사로 새로 드러난 서술자 타입을 넣은 것이고, 위험 API를 여는 항목은 찾지 못했다 `[추론]`(`databind.Module`은 `Module$SetupContext`가 목록 밖이라 직접 만든 모듈의 `setupModule`이 걸린다)
  - 실전 경로 운영 코드의 `getClass()` 20곳은 모두 바로 뒤에 `getSimpleName()`·`getName()`을 부른다(`grep -rn "getClass()"`에서 그 밖의 모양 0건)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 종료 코드 0, 테스트 1,130건 실패·오류·건너뜀 0 |
| B2 | 실패 | 규칙 변경에 ADR-002 9.5 6항이 있으나, 그 6항과 D-086 1항이 금지한다고 적은 "Jackson의 `Class` 역직렬화로 이름 문자열에서 클래스를 얻는 길"이 P1(`Class[].class`)로 그대로 열려 있다. `classAsData`가 클래스 객체를 `r.getRawType().getName().equals("java.lang.Class")`로만 비교해 배열 `[Ljava.lang.Class;`를 놓친다(`RealPathReflectionTest.java:406`). AR-8 |
| B3 | 통과 | 운영 계약 변경 없음. 규칙 계약 변경은 ADR-002 9.5 6항과 D-086에 적혀 있다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | 새 규칙마다 고정 예제와 단언이 있다(`규칙은_리플렉션_우회를_실제로_잡아낸다`의 `NameToClassBinding` 단언 6개와 `ClassFileTypes` 단언). 기존 단언 삭제·약화, `@Disabled` 없음. 다만 배열 클래스 객체와 `getClass()` 경로의 고정 예제는 없다(AR-8·AR-9) |
| B7 | risk-ops 검토 필요 | 보호 테스트(`autotrade-architecture-test`) 변경이고 2차 확인 위조(Guard) 경로를 다룬다. risk-ops 재검토와 독립 QA 재검토 뒤 사용자가 머지한다 |
| B8 | 통과 | 새 파일·예제에 키·계좌번호·토큰 없음 |

### 독립 QA 지적의 처리
- F2(형변환·호출 서술자의 허용 목록 밖 타입): 해소. Q16 재현이 `MapperFeature`로 잡혔고, `NameToClassBinding.castOnly`(형변환만)도 상수 풀로 잡힌다(단언 확인). 상수 풀 검사는 문자열 상수를 빼고 클래스 상수·서술자·서명을 모두 보므로, 목록 밖 타입을 이름 없이 쓰는 경로는 찾지 못했다.
- F1(이름 문자열 클래스 찾기): **부분 해소**. `Class.class` 리터럴, `Class` 필드·레코드 구성 요소(배열 포함)·설정자·제네릭 인자, 목록 밖 `Class` 메서드는 잡힌다. 그러나 아래 AR-8·AR-9 두 길이 남아 Q16과 같은 일(이름 문자열 → 클래스 → `convertValue(fields, k)`)을 한 줄 바꿔 그대로 할 수 있다.
- R1: D-086 위험과 한계에 반영됐다. 그런데 AR-8·AR-9가 남아 있는 동안은 2차 확인 위조를 막는 것이 여전히 `Instant` 필드 하나다(QA 시나리오 2와 같다).

### 새 지적

#### AR-8 (수정 필요) 배열 클래스 객체 `Class[].class`로 이름 문자열에서 클래스를 얻는다
- `[사실]` 위 P1. `M.readValue("[\"<이름>\"]", Class[].class)`가 규칙을 모두 통과하고, 실행하면 이름의 클래스(정적 초기화 포함)를 돌려준다. `classAsData`의 클래스 객체 검사가 배열을 비교하지 않고(`:406`), `outsideAllowlist`는 배열의 원소 타입 `java.lang.Class`가 허용이라 통과한다. `Class[][]`·`convertValue(.., Class[].class)`·`treeToValue(.., Class[].class)`도 같은 모양이다 `[추론]`.
- ADR-002 9.5 6항과 D-086 1항("`Class.class` 클래스 객체 … 를 쓰지 않는다")이 막는다고 적은 것과 다르다.
- 고칠 방향: 클래스 객체 검사를 `getRawType().getBaseComponentType()`으로 비교한다. 고정 예제 `NameToClassBinding`에 `Class[].class` 읽기를 더하고 단언한다.

#### AR-9 (수정 필요) `Class`의 `getClass()`로 리터럴 없이 `Class` 타입 객체를 얻는다
- `[사실]` 위 P2. `String.class.getClass()`는 `Class<Class>` 값을 돌려주고, javac가 `Object.getClass`로 부르므로 `ALLOWED_CLASS_METHODS` 검사(`:424`, 대상 소유자가 `java.lang.Class`일 때만)와 `Class.class` 검사를 모두 비켜 간다. 이 값을 `readValue`에 넘기면 F1과 같다.
- D-086 위험과 한계와 ADR-002 9.5 남은 한계는 이것을 "리뷰로 막는다 `[추론]`"로 남겼다. 문서는 정직하지만, QA가 차단으로 본 F1의 핵심 경로가 한 줄 바꿔 다시 열리는 것이라 리뷰에 맡길 수 있는 한계로 보지 않는다. 그리고 "정적으로 못 잡는다"는 전제가 틀렸다: 실전 경로 운영 코드의 `getClass()` 20곳은 모두 바로 뒤에 `getSimpleName()`·`getName()`을 부르므로 닫는 비용이 작다.
- 고칠 방향(둘 중 하나, 추천 ①):
  - ① 바이트코드 규칙: 이미 클래스 파일을 읽는 `ClassFileTypes`처럼 `Code` 속성을 읽어, `invokevirtual java/lang/Object.getClass` 바로 다음 명령이 `java/lang/Class.getSimpleName|getName`인 경우만 허용한다(또는 `getClass()`를 이름 문자열을 돌려주는 도우미 하나에서만 부르게 하고 ArchUnit으로 호출 위치를 고정). 버린 안: `getClass()` 전면 금지는 예외 이름 기록 20곳을 바꿔야 해 비용 대비 이득이 같다(①이 같은 효과).
  - ② 실행 시 방어: 실전 경로 `ObjectMapper`를 한 생성 지점(지금 18곳)으로 모으고 `java.lang.Class` 역직렬화기를 늘 실패하는 것으로 등록, 그 밖의 `new ObjectMapper`·`JsonMapper.builder`를 규칙으로 금지. 버린 이유: 생성 지점 18곳을 옮기는 운영 코드 변경이 커서 이 PR(시험만 바꾸는 PR)의 범위를 넘는다. ①로 부족해지면 되돌릴 조건으로 둔다.
- 고정 예제 `NameToClassBinding`에 `String.class.getClass()` 읽기를 더하고, 허용 예제에 `e.getClass().getSimpleName()`를 둔다. D-086·ADR-002의 해당 한계 문장은 고친 뒤 지운다.

### 그 밖
- 직전 재검토까지의 AR-1~AR-7은 해소 상태 그대로다(이번 빌드에서 해당 고정 예제 단언 통과).

## 판정
수정 필요(AR-8, AR-9)

## 재검토 2026-10-14 (헤드 4f44ccc)
- 대상: PR #108 / 커밋 4f44ccc / main 대비 diff 전체(`git diff origin/main...4f44ccc`, 22파일 +2,211 −3, 병합 기준 4b24550). 직전 재검토(743545c) 뒤 바뀐 것은 검토 원문 커밋 2개(a8907ec·3911b88)와 그 병합(389f201, 원문 파일 2개만 바뀜을 `git diff --stat 743545c 389f201`로 확인), 그리고 수정 커밋 4f44ccc다. 4f44ccc가 바꾼 것:
  - `classAsData`의 클래스 객체 검사와 `involvesClass`를 `getBaseComponentType()`으로 비교(`RealPathReflectionTest.java:407`, `:434`)
  - 새 `GetClassUses.java`(ASM으로 명령 순서를 읽어 `getClass()` 바로 다음이 `Class.getSimpleName|getName`이 아니면 위반, `getClass` 메서드 핸들도 위반)와 `classAsData`에서의 호출(`:429`)
  - 고정 예제 `NameToClassBinding.byNames`·`viaGetClass`·`classOf`와 단언 3개, 허용 예제 `ReflectionFreeEngineCode`의 `GetClassUses` 무위반 단언(`:752`)
  - D-086 1항·위험과 한계·검증, ADR-002 9.5 6항·남은 한계 문구, `docs/qa-out-status.md` 한 줄
- 점검표: B
- PR 본문, 커밋 메시지, D-086의 주장은 사실로 받지 않고 아래 실행으로만 판정했다
- 직접 실행한 것(별도 작업 트리 `/tmp/claude-0/wt-architect-6`, `origin/claude/project-thread-l44wj7` = 4f44ccc. 공유 작업 트리는 건드리지 않았다):
  - `./gradlew build --rerun-tasks`: 종료 코드 0. 결과 XML 합계 테스트 1,130건, 실패·오류·건너뜀 0. `RealPathReflectionTest` 7건 실행, 실패 0
  - 변조 실험(커밋하지 않음, 끝난 뒤 지우고 `git status` 깨끗함 확인). 위치는 모두 실전 경로 범위인 `autotrade-control`의 `com.autotrade.control.zprobe`, 실행은 `:autotrade-control:build :autotrade-architecture-test:test`. 단독 실행은 Jackson 2.21.4 기본 `new ObjectMapper()`, 이름 `java.util.concurrent.atomic.AtomicLong`:

    | 프로브 | 내용 | 규칙 결과 | 단독 실행 결과 |
    |---|---|---|---|
    | AR-8 확인 | `return Class[].class;` | **잡힘**(`Sanity.k()가 클래스 객체 Class[].class를 참조`). 범위가 이 패키지를 보는 것도 이것으로 확인 | 해당 없음 |
    | AR-9 확인 | 람다 안 `String.class.getClass()`, 허용 모양 `o.getClass().getSimpleName()` | **잡힘**(`lambda$k$0의 getClass() 결과를 이름 읽기 밖에서 쓴다`), 허용 모양은 위반 없음 | 해당 없음 |
    | P4 상위 클래스 제네릭 인자 | `final class SuperProbe extends ArrayList<Class<?>>` + `M.readValue("[\"<이름>\"]", SuperProbe.class).get(0)` | **안 잡힘**(`BUILD SUCCESSFUL`) | `class java.util.concurrent.atomic.AtomicLong`(타입 `java.lang.Class`) |
    | P5 상위 클래스 타입 변수 | `class Holder<T> { public T value; }`, `final class TypeVarProbe extends Holder<Class<?>>` + `readValue("{\"value\":\"<이름>\"}", TypeVarProbe.class).value` | **안 잡힘** | `class java.util.concurrent.atomic.AtomicLong` |
    | P6 설정자 없는 컬렉션 게터 | 필드 `List<Object> raw`, `public List<Class<?>> getTypes() { return (List) raw; }` + `readValue("{\"types\":[\"<이름>\"]}", GetterProbe.class)` (`USE_GETTERS_AS_SETTERS` 기본값) | **안 잡힘** | `class java.util.concurrent.atomic.AtomicLong` |

  - 운영 코드 확인: 실전 경로 14개 모듈 `src/main`에서 `Class`를 상위 타입 제네릭 인자로 쓰거나 메서드 반환 타입에 쓰는 곳을 `grep`으로 찾았다. 0건(걸린 것은 매개변수 `ProfileParams.choice(String, Class<E>)` 하나뿐이고 이것은 허용 모양이다)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 종료 코드 0, 테스트 1,130건 실패·오류·건너뜀 0 |
| B2 | 실패 | 규칙 변경에 ADR-002 9.5 6항과 D-086 1항이 있으나, 두 문서가 막는다고 적은 "`Class` 속성을 가진 바인딩 대상"이 P4·P5·P6으로 열려 있다. 그리고 4f44ccc의 D-086 위험과 한계는 "R1(`Instant`)에 기대지 않는다(위 1번이 `Class` 값 바인딩을 막는다)"로 바뀌어, 문서가 코드보다 강하게 주장한다. AR-10 |
| B3 | 통과 | 운영 계약 변경 없음. 규칙 계약 변경은 ADR-002 9.5 6항과 D-086에 적혀 있다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 통과 | AR-8·AR-9 고정 예제와 단언 3개, 허용 예제 단언 1개 추가(`RealPathReflectionTest.java:748`~`:754`). 기존 단언 삭제·약화, `@Disabled` 없음. 다만 P4~P6 모양의 고정 예제는 없다(AR-10) |
| B7 | risk-ops 검토 필요 | 보호 테스트(`autotrade-architecture-test`) 변경이고 2차 확인 위조(Guard) 경로를 다룬다. risk-ops 재검토와 독립 QA 재검토 뒤 사용자가 머지한다 |
| B8 | 통과 | 새 파일·예제에 키·계좌번호·토큰 없음 |

### 직전 지적의 처리
- AR-8(`Class[].class`): **해소**. 위 확인 프로브가 잡히고, `involvesClass`도 배열 원소로 비교한다.
- AR-9(`Class`의 `getClass()`): **해소**. `GetClassUses`는 `getClass` 이름·서술자만 보고 소유자를 가리지 않으므로 `invokevirtual`·`invokespecial`(`super.getClass()`) 모두 걸리고, 람다 본문(합성 메서드)과 메서드 참조 핸들도 본다. 이름 읽기 뒤 결과는 `String`이라 클래스 객체로 돌아갈 길이 없다 `[추론]`.
- AR-1~AR-7: 해소 상태 그대로(이번 빌드에서 해당 고정 예제 단언 통과).

### 새 지적

#### AR-10 (수정 필요) `Class`를 상위 타입 제네릭 인자나 게터 반환 타입에 두면 Jackson이 이름 문자열로 클래스를 만든다
- `[사실]` 위 P4·P5·P6. 세 모양 모두 규칙을 통과하고, 기본 설정 `ObjectMapper`로 실행하면 이름의 클래스(정적 초기화 포함)를 돌려준다. Q16과 같은 다음 단계(`convertValue(fields, k)`)로 그대로 이어진다.
- 원인: `classAsData`(`RealPathReflectionTest.java:403`)가 `Class`를 찾는 곳이 클래스 객체 리터럴, 필드 타입, 바인딩 메서드(생성자·`set*`·Jackson 주석)의 매개변수, 매개변수의 제네릭 인자뿐이다. Jackson은 이 밖에 (1) 상위 클래스·인터페이스의 제네릭 인자로 컬렉션 원소 타입과 상속한 필드의 타입 변수를 풀고(P4·P5), (2) 설정자 없는 `Collection`·`Map` 게터를 설정자로 쓴다(`MapperFeature.USE_GETTERS_AS_SETTERS` 기본 켜짐, P6). 둘 다 검사 대상이 아니다.
- 고칠 방향(추천 ①):
  - ① `Class`가 나올 수 있는 모양을 허용 목록으로 뒤집는다: "바인딩이 아닌 메서드의 매개변수 바로 그 타입이 `Class<T>`"만 허용하고, 그 밖에 클래스·필드·메서드 서명(상위 클래스·인터페이스 제네릭 인자, 모든 메서드의 반환 타입과 그 제네릭 인자, 타입 변수 경계)에 `java/lang/Class`가 나오면 위반으로 한다. 이미 클래스 파일을 읽는 `ClassFileTypes`가 `Signature` 속성을 보므로 같은 자리에서 서명 단위로 판정하면 빠뜨림이 적다. 지금 운영 코드 위반 0건이라 비용이 작다(위 grep).
  - ② 실행 시 방어: 실전 경로 `ObjectMapper` 생성 지점을 하나로 모으고 `java.lang.Class` 역직렬화기를 늘 실패하는 것으로 등록, 그 밖의 매퍼 생성을 규칙으로 금지. 정적 규칙이 Jackson의 타입 해석 규칙을 하나씩 따라가는 일을 끝내는 확실한 방법이지만 운영 코드 18곳을 옮기는 변경이라 이 PR의 범위를 넘는다. ①로 또 빈틈이 나오면 ②로 가는 것을 되돌릴 조건으로 D-086에 적는다.
- 고정 예제 `NameToClassBinding`(또는 새 예제)에 P4·P5·P6 세 모양을 넣고 단언한다. D-086 위험과 한계의 "위 1번이 `Class` 값 바인딩을 막는다" 문장은 고친 뒤에야 사실이 된다.

## 판정
수정 필요(AR-10)
