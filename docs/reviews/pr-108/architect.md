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
