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
