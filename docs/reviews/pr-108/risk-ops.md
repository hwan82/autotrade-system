# risk-ops 검토: D-085 실전 경로 리플렉션 금지 규칙과 외부 jar 허용 목록
- 대상: PR #108 / 커밋 884d443 / `git diff origin/main...HEAD` 전체(10개 파일: `autotrade-architecture-test/build.gradle.kts`, `RealPathReflectionTest`, archfixture 4개, ADR-002 9.5, D-085, 결정 로그, 기획서 TST-07)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL(EXIT=0). 테스트 결과 XML 집계: `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 37건, `:autotrade-app-engine:test` 50건, 모두 실패·오류·건너뜀 0. `RealPathReflectionTest` 5건 통과.
  - 변조 실험 1종(커밋하지 않음, 실행 뒤 삭제하고 `git status` 깨끗함 확인): 실전 경로 모듈 `autotrade-control`에 `com.autotrade.control.qaprobe.NativeProbe`(`System.loadLibrary`, `java.lang.management.ManagementFactory.getPlatformMBeanServer()`, `javax.tools.ToolProvider.getSystemJavaCompiler()`)를 넣고 `:autotrade-architecture-test:test --tests '*RealPathReflectionTest*'` → **통과(잡지 못함)**.
- 메인 Claude 설명, PR 본문, 커밋 메시지는 근거로 쓰지 않았다. 아래 판정은 코드와 위 실행 결과로만 한다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(diff는 테스트·빌드·문서뿐). `autotrade-risk/src/main/java/com/autotrade/risk/guard/RealTradingGuard.java` 생성자는 상태 인자 없이 모두 OFF(그대로). `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 기존 `ArchitectureTest` 규칙은 수정 없음(diff에 `ArchitectureTest.java` 없음). 새 규칙 `REAL_PATH_HAS_NO_REFLECTION`·`SECOND_CONFIRMATION_IS_NOT_DATA`(RealPathReflectionTest.java)가 바이트코드 밖 우회를 추가로 막는다. 단 남은 우회는 점검표 밖 발견 F1·F2 |
| A3 | 해당 없음 | 주문 라우팅 코드 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS`(RealPathReflectionTest.java)가 실험실·UI·sim·selection·게이트웨이·수집·실험 명령이 실전 경로 런타임 해석 결과에 없음을 고정. 기존 `processIsolation`(autotrade-app-engine/build.gradle.kts) 그대로. `realPathRuntime`은 해석 전용이라 테스트 클래스패스를 바꾸지 않음(build.gradle.kts 주석과 `dependencies`에 `testImplementation` 추가는 Jackson·ArchUnit뿐) |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | archfixture 4개와 테스트에서 키·계좌번호·토큰 문자열 검색 결과 없음(`grep -rniE "appkey|secret|account-?number"`) |
| A11 | 통과 | 삭제 줄은 문서 3줄뿐(ADR-002 한 줄 보강, 기획서 버전·TST-07 행 교체). `@Disabled`·`assume*` 없음. 새 규칙마다 위반 고정 예제 테스트 `규칙은_리플렉션_우회를_실제로_잡아낸다`(ReflectiveRaiser·PolymorphicJson·DataBoundConfirmation)와 허용 예제(ReflectionFreeEngineCode)가 있고 통과. 보호 테스트 강화 |
| A12 | 해당 없음 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 전제를 바꾸는 동작 변경 없음(빌드 시점 정적 규칙만 추가) |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 수정 필요 | D-085 "위험과 한계"는 외부 jar 내부 리플렉션을 (3)`SecondConfirmation` 타입 의존 금지와 (4)허용 목록으로 막는다고 적지만, 허용 목록의 Jackson 자체가 임의 객체 필드 쓰기 수단이 되는 경로(F1)는 적혀 있지 않고, 막는 규칙도 없다. 또 JDK 안의 같은 종류 기능(F2)이 금지 목록과 한계 목록 어디에도 없다. 문서가 실제 보장 범위보다 넓게 읽힌다 |

## 점검표 밖 발견

- **F1 (중대, 수정 필요)** 허용 목록 Jackson을 통한 Guard 내부 상태 쓰기 경로가 규칙에 없다. `[추론]`, 실증 실험은 하지 않음.
  - 근거: `jackson-databind`는 `autotrade-control`·`autotrade-config`·`autotrade-broker-*`·`autotrade-marketdata`의 `implementation`(각 build.gradle.kts)이라 이 모듈들의 운영 코드는 컴파일 시점에 Jackson 매퍼 설정 API를 직접 쓸 수 있다. Jackson은 매퍼 설정(필드 가시성 확대, 기존 객체 갱신 읽기·값 변환 등)만으로 이미 있는 객체의 private 필드를 내부 리플렉션으로 쓸 수 있다. `RealTradingGuard`의 상태는 `AccountGuard`의 필드(스냅샷)로 있고, 모듈 시스템을 쓰지 않으므로(ADR-002 9장 JPMS 보류) 접근 제한도 없다. 이 경로는 `java.lang.reflect`를 우리 바이트코드에 남기지 않고, `SecondConfirmation`에도 의존하지 않으므로 `REAL_PATH_HAS_NO_REFLECTION`·`SECOND_CONFIRMATION_IS_NOT_DATA` 어느 쪽도 잡지 않는다(RealPathReflectionTest.java `reflective()`·`polymorphicJackson()`은 `DefaultTyping`·`@JsonTypeInfo`만 본다).
  - 현재 운영 코드에 해당 API 사용은 없음(`grep setVisibility|readerForUpdating|updateValue|convertValue|findClass` 결과 0건). 즉 지금 구멍이 쓰이고 있지는 않지만, D-085가 막겠다는 위협(실전 경로 코드가 바이트코드 규칙을 우회해 Guard에 닿는 것)과 같은 종류다.
  - 요구: 실전 경로 범위에서 Jackson의 가시성·접근 제어 설정 API(필드/생성자 가시성 변경, `MapperFeature` 중 접근 관련 기능, 기존 객체 갱신 읽기, 클래스 이름 문자열로 타입 찾기)를 금지하거나, Jackson 사용 자체를 정해진 DTO 패키지·매퍼 팩토리 클래스로 한정하는 규칙과 위반 고정 예제를 추가한다. 고정 예제는 Guard 상태를 실제로 바꾸지 않는 형태(규칙 대상 호출만 남기는 형태)로 둔다. D-085 위험과 한계에 이 경로와 처리 방식을 적는다.
- **F2 (보통, 수정 필요)** JDK의 동적 코드·간접 호출 수단 일부가 금지 패키지 목록에 없다. 변조 실험으로 확인: `System.loadLibrary`(JNI 네이티브 코드), `java.lang.management`/`javax.management`(MBean 반영 호출), `javax.tools`(실행 중 컴파일)를 실전 경로 모듈 `autotrade-control`에 넣어도 `RealPathReflectionTest`가 통과했다. `jdk.jshell`, `java.lang.foreign`(Java 21 미리보기)도 목록에 없다(코드 검토). 요구: `System#load·loadLibrary`, `Runtime#load·loadLibrary`, `native` 메서드 선언, `javax.management..`, `javax.tools..`, `jdk.jshell..`, `java.lang.foreign..`, `jdk.dynalink..`를 금지 목록에 넣고 `ReflectiveRaiser`에 대응 예제를 추가한다. 실전 경로에 꼭 필요한 것이 있으면 허용 목록으로 따로 둔다.
- **R1 (참고)** `FIELD_ACCESSORS` 정규식(RealPathReflectionTest.java)은 `Field`의 `get*·set*`만 본다. `AtomicReferenceFieldUpdater`·`AtomicIntegerFieldUpdater`·`AtomicLongFieldUpdater#newUpdater`(필드 이름 문자열로 접근)는 목록에 없다. 같은 클래스·패키지 접근 권한 제약이 있어 Guard 패키지 밖에서는 효과가 제한적이지만 함께 넣는 편이 일관된다.
- **R2 (참고)** `규칙은_리플렉션_우회를_실제로_잡아낸다`는 `noReflectionIn(IN_REAL_PATH_PACKAGE)`만 고정 예제로 시험한다. 모듈 판정(`fromRealPathModule`)은 `범위는_실전_경로_프로젝트의_운영_클래스_전체다`가 따로 보지만, 둘을 합친 실제 규칙 `REAL_PATH_HAS_NO_REFLECTION`이 `com.autotrade` 밖 패키지 위반을 잡는지는 테스트가 없고 D-085 변조 실험 ②(커밋 안 함)에만 기대고 있다. 이번 검토에서는 그 실험을 재현하지 않았다.
- 좋은 점: 범위를 손 목록이 아니라 Gradle 해석 결과로 정하고, 필수·금지 프로젝트와 외부 모듈 정확 일치로 고정한 방식은 PR #21 Q-1·R-8의 교훈을 반영했다. ServiceLoader 허용 목록을 비워 두고 테스트로 고정한 것도 7단계 플러그인 PR을 검토 대상으로 드러낸다.

## 판정
수정 필요(F1 Jackson 매퍼 설정을 통한 객체 필드 쓰기 경로 차단 규칙과 예제, F2 JDK 동적 코드·간접 호출 수단 금지 목록 보강, A14 D-085·ADR-002 9.5 위험과 한계 문서 정정)

## 재검토 2026-10-14
- 대상: PR #108 / 헤드 618ae22 / `git diff origin/main...HEAD` 전체(앞 검토 뒤 변경: `RealPathReflectionTest`에 JDK 패키지·`java.lang.reflect` 구조 타입·Jackson 타입 허용 목록, 금지 메서드 보강, `GUARD_TYPES_ARE_NOT_DATA`, 합친 범위 테스트, 고정 예제 `DynamicCode`·`GuardAsData`·`JacksonObjectAccess`·`PolymorphicJson.ClassIdIntrospector`·`qa.probe.OutsideReflective`, ADR-002 9.5·D-085·결정 로그 정정)
- 점검표: A
- 직접 실행한 것(모두 `git worktree add`로 만든 별도 작업 트리 618ae22에서 실행, 끝난 뒤 작업 트리 제거, 공유 작업 트리의 소스는 손대지 않음):
  - `./gradlew build --no-daemon` → EXIT=0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 38건(`RealPathReflectionTest` 6건), `:autotrade-app-engine:test` 50건, 모두 실패·오류·건너뜀 0.
  - 변조 실험 1종: 실전 경로 모듈 `autotrade-control`에 `com.autotrade.control.qaprobe.LoaderProbe`(`URLClassLoader.newInstance(URL[])`, `ResourceBundle.getBundle(String, Locale, ClassLoader)`)를 넣고 `:autotrade-architecture-test:test --tests '*RealPathReflectionTest*'` → **통과(잡지 못함)**. 실험 파일은 지웠다.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F1 Jackson 객체 필드 쓰기 경로 | 해소 | `JACKSON_OBJECT_ACCESS`(기존 객체 갱신·가시성·주석 해석기·믹스인·수정자·`findClass`)와 `JACKSON_ALLOWLIST`(27개 타입, `MapperFeature`·`AnnotatedField`·`ClassUtil` 등 목록 밖) 이중 차단, `GUARD_TYPES_ARE_NOT_DATA`(클래스 객체·`TypeReference` 인자·주석 값). 고정 예제 `JacksonObjectAccess`·`GuardAsData`가 `규칙은_리플렉션_우회를_실제로_잡아낸다`에서 걸림 확인. 허용 목록 방식이라 앞 검토보다 강하다 |
| F2 JDK 동적 코드 수단 | 대부분 해소, 남은 구멍 F3 | `DYNAMIC_CODE_PACKAGES`에 `java.lang.management`·`javax.management`·`javax.tools`·`jdk.jshell`·`java.lang.foreign`·`jdk.dynalink`·`javax.naming`·`java.rmi` 추가, `System`·`Runtime`의 `load·loadLibrary`, `native` 선언, `Module#add*` 금지. 더해 `ALLOWED_JDK_PACKAGES` 23개 허용 목록. `DynamicCode` 예제 8종 걸림 확인. 앞 검토의 `NativeProbe` 3종은 이제 각각 금지 메서드·금지 패키지·허용 목록 밖으로 걸린다(코드 대조) |
| A14 문서 | 해소 | D-085 "보장하는 것/보장하지 않는 것"과 ADR-002 9.5 남은 한계가 Jackson 기본 동작과 허용 패키지 안 미지 수단을 `[추론]`으로 적음. 단 F3 대상은 문서의 "클래스 로더 생성 금지" 서술과 맞지 않는다 |
| R1 `Atomic*FieldUpdater` | 해소 | `reflective()` 정규식, `DynamicCode#updater` 걸림 |
| R2 합친 범위 테스트 | 해소 | `합친_범위는_실전_경로_모듈의_com_autotrade_밖_패키지도_잡는다`: 가짜 배치표로 모듈 판정만이 `qa.probe.OutsideReflective`를 잡고, 실험실 배치로는 걸리지 않음을 함께 확인 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(diff는 `autotrade-architecture-test`와 문서뿐). `RealTradingGuard` 생성 시 모든 계좌 OFF 그대로, `:autotrade-risk:test` 55건 통과 |
| A2 | 수정 필요 | 기존 `ArchitectureTest` 규칙 변경 없음, 새 규칙이 우회를 넓게 막음. 그러나 F3: 실전 경로 코드가 빌드 밖 코드를 적재하는 경로(클래스 로더 정적 팩토리, 로더를 받는 `ResourceBundle.getBundle`)가 열려 있어, 적재된 코드는 어떤 ArchUnit 규칙도 거치지 않는다 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정 그대로, `processIsolation`(autotrade-app-engine/build.gradle.kts) 변경 없음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 예제 5개에 키·계좌번호·토큰 없음(`DynamicCode`의 `"/x.so"`·`"x"`는 자리 문자열) |
| A11 | 통과 | 기존 단언 삭제 없음. `PolymorphicJson` 단언은 `hasSize(4)`에서 종류별 개수(2+2)와 허용 목록 밖 의존 확인으로 바뀌었고 더 엄격하다. `allowEmptyShould(true)`는 범위가 비는 경우에만 영향이며, 범위가 비지 않음은 `범위는_실전_경로_프로젝트의_운영_클래스_전체다`가 단언한다 |
| A12 | 해당 없음 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 전제를 바꾸는 동작 변경 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 수정 필요 | D-085 결정 1번과 ADR-002 9.5가 "클래스 로더 생성" 금지라 적지만 F3의 정적 팩토리는 잡지 않는다. 고친 뒤 문서와 맞춘다 |

### 점검표 밖 발견
- **F3 (보통, 수정 필요)** 빌드 밖 코드 적재 경로. `reflective()`의 클래스 로더 분기는 `<init>`·`loadClass`·`defineClass`만 본다. `java.net.URLClassLoader.newInstance(..)`(정적 팩토리, `java.net`은 허용 패키지)로 로더를 만들고, `java.util.ResourceBundle.getBundle(String, Locale, ClassLoader)`(이름으로 클래스를 찾아 인스턴스를 만든다, `java.util`은 허용 패키지)로 그 로더의 클래스를 실행할 수 있다. 변조 실험 `LoaderProbe`로 규칙이 두 호출 모두 잡지 못함을 확인했다. 적재되는 클래스는 저장소·빌드에 없으므로 `ArchitectureTest`·`RealPathReflectionTest` 어느 규칙도 거치지 않는다(나머지 단계는 `[추론]`, 실제 적재·실행 실험은 하지 않았다).
  - 요구: `ClassLoader` 하위 타입의 정적 팩토리(`newInstance` 포함, 사실상 `ClassLoader` 하위 타입이 반환·소유인 모든 생성 수단)를 금지하고, `ClassLoader`를 인자로 받는 JDK API(`ResourceBundle.getBundle(.., ClassLoader)`, `ResourceBundle.Control#newBundle`, `ModuleLayer` 정의 메서드, `Thread#setContextClassLoader`)를 금지하거나, 더 단순하게 실전 경로에서 `java.lang.ClassLoader` 하위 타입에 대한 의존 자체를 금지한다(운영 코드에 쓰는 곳이 있으면 허용 목록). `DynamicCode`에 두 예제를 더하고 D-085·ADR-002 9.5 문구를 맞춘다.
- **R3 (참고)** `GUARD_TYPES_ARE_NOT_DATA`는 클래스 리터럴만 본다. 살아 있는 Guard 객체의 `getClass()`로 얻은 클래스 객체를 바인딩에 넘기는 것은 걸리지 않지만, Guard 타입은 기본 생성자·`@JsonCreator`가 없고 살아 있는 객체 덮어쓰기 API는 금지되어 실효는 낮다 `[추론]`. 문서 남은 한계에 한 줄 적어 두기를 권한다.

### 판정
수정 필요(F3 클래스 로더 정적 팩토리와 로더 인자 JDK API를 통한 빌드 밖 코드 적재 차단과 예제, A14 D-085·ADR-002 9.5 "클래스 로더 생성" 문구 정합)

## 재검토 2026-10-14 (2)
- 대상: PR #108 / 헤드 dbdb953 / `git diff origin/main...HEAD` 전체(앞 재검토 뒤 변경: `reflective()`에 클래스 로더 타입 전 메서드·로더 주고받는 API·`ResourceBundle`·`ProcessBuilder`·`Runtime#exec` 금지, `Guard_타입은_Jackson이_만들_수_있는_모양이_아니다` 추가, `DynamicCode` 예제 6종 추가, ADR-002 9.5·D-085 정정)
- 점검표: A
- 직접 실행한 것(별도 작업 트리 `git worktree add .. dbdb953`, 끝난 뒤 제거. 공유 작업 트리 소스는 손대지 않음):
  - `./gradlew build --no-daemon` → EXIT=0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 39건(`RealPathReflectionTest` 7건), `:autotrade-app-engine:test` 50건, 실패·오류·건너뜀 0.
  - 변조 실험(커밋하지 않음, 지움): 앞 재검토의 `LoaderProbe`(`URLClassLoader.newInstance`, `ResourceBundle.getBundle(.., ClassLoader)`)를 `autotrade-control`에 다시 넣음 → 두 호출 모두 `RealPathReflectionTest` 실패로 잡힘(F3 해소 확인). 같은 실험의 `ModulesProbe`(`ObjectMapper#findAndRegisterModules`) → 잡히지 않음(R4).
  - `jackson-databind` jar의 `META-INF/services` 확인: `com.fasterxml.jackson.core.ObjectCodec` 하나뿐, Jackson `Module` 제공 항목 없음.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F3 클래스 로더 정적 팩토리·로더 인자 API | 해소 | RealPathReflectionTest.java `reflective()`의 로더 타입 소유·로더 매개변수·로더 반환 판정과 `ResourceBundle`·`ResourceBundle.Control` 금지. `DynamicCode#outsideCode`·`#contextLoader` 걸림을 `규칙은_리플렉션_우회를_실제로_잡아낸다`가 단언, 변조 실험 재현으로 실패 확인 |
| A14 문구 | 해소 | D-085 결정 1번과 ADR-002 9.5 규칙 문구가 "로더 타입의 모든 메서드와 생성, 정적 팩토리, 로더를 돌려주는·받는 API, `ResourceBundle`"로 실제 규칙과 일치 |
| R3 `getClass()` | 해소 | `Guard_타입은_Jackson이_만들_수_있는_모양이_아니다`가 Guard 패키지 운영 타입에 Jackson 주석·인자 없는 생성자가 없고 레코드는 3개로 고정됨을 단언. 남은 `SecondConfirmation` 레코드 경로는 D-085 남은 한계에 `[추론]`으로 기록 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(diff는 `autotrade-architecture-test`와 문서뿐). `RealTradingGuard` 생성 시 전 계좌 OFF 그대로, `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 기존 `ArchitectureTest` 규칙 변경 없음. 새 규칙이 리플렉션·동적 코드·로더·외부 명령·Jackson 객체 접근을 막고 각각 고정 예제로 걸림을 확인(`규칙은_리플렉션_우회를_실제로_잡아낸다`, `합친_범위는_...`). R4는 빌드 밖 jar가 있어야만 효과가 있어 D-085가 넘긴 설치본 한계와 같다 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정과 `processIsolation`(autotrade-app-engine/build.gradle.kts) 그대로 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | `DynamicCode` 새 예제의 `"jcmd"`·`"/x.jar"`는 자리 문자열, 비밀값 없음 |
| A11 | 통과 | 기존 단언 삭제·약화 없음(`DynamicCode` 단언은 6개 항목 추가). 새 테스트 1건 추가 |
| A12 | 수정 필요 | F4: 장애 대응표 #8(실전 엔진 크래시 → 감시 프로세스가 "자동 재기동", broker-and-fault-design.md 6장 167행)과 #9(실험실·UI·게이트웨이 자동 재기동)는 감시 프로세스가 다른 프로세스를 띄우는 것을 전제한다. 이번 커밋은 실전 경로 범위(감시 `:autotrade-app-watchdog` 포함)에서 `ProcessBuilder`·`Runtime#exec`를 허용 목록 없이 전면 금지했고, D-085·ADR-002에 #8·#9를 어떻게 구현할지(감시 안 실행기 한 클래스 허용 목록, 또는 OS 서비스 관리자에게 맡김)가 없다. 지금 감시 코드에 재기동 구현이 없어 빌드는 통과하지만, 대응표 전제와 테스트가 함께 정리되지 않았다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 수정 필요 | F4와 같은 이유로 D-085 위험과 한계·되돌릴 조건에 장애 #8·#9 재기동 경로가 빠져 있다 |

### 점검표 밖 발견
- **F4 (보통, 수정 필요)** 외부 명령 금지와 장애 #8·#9 자동 재기동 설계의 정합. 금지 자체는 AR-4(자기 JVM 에이전트 부착) 방어로 맞고 약화를 요구하지 않는다. 요구: (a) `ServiceLoader`와 같은 방식으로 `ALLOWED_PROCESS_LAUNCHERS`(지금 비어 있음, 테스트로 고정)를 두고 7단계 감시 재기동 구현 PR에서 감시 프로세스의 실행기 클래스 하나만 넣는다(그 클래스는 고정된 실행 파일과 인자만 쓰고, 실전 엔진 PID를 대상으로 하는 `jcmd`류를 부를 수 없음을 테스트), 또는 (b) 재기동은 OS 서비스 관리자(Windows 서비스·작업 스케줄러)에 맡긴다고 D-085와 broker-and-fault-design.md #8·#9 처리 칸에 적는다. 어느 쪽이든 D-085 "되돌릴 조건"에 적는다. (b)를 고르면 대응표 변경이라 사용자 확인 대상이다(지침 8장 2항).
- **R4 (참고)** `ObjectMapper#findAndRegisterModules`·`ObjectMapper.findModules`는 `JACKSON_OBJECT_ACCESS`에 없고 내부에서 `ServiceLoader`를 쓴다. 실전 경로 런타임 외부 jar가 Jackson 4개와 정확히 같고(`실전_경로_런타임_구성은_고정_목록과_같다`) 그 안에 Jackson `Module` 제공 항목이 없어 빌드 안에서는 효과가 없다. 설치본에 jar를 끼워 넣는 경우에만 의미가 있어 D-085가 넘긴 한계와 같다. 일관성을 위해 금지 목록에 넣기를 권한다.

### 판정
수정 필요(F4 외부 명령 전면 금지와 장애 #8·#9 감시 자동 재기동 설계의 정합: 비어 있는 실행기 허용 목록과 테스트 또는 OS 서비스 관리자 위임 기록, A12·A14 해당 문서 정리)

## 재검토 2026-10-14 (3)
- 대상: PR #108 / 헤드 4006964 / `git diff origin/main...HEAD` 전체. 2차 재검토 뒤 바뀐 것은 다음과 같다.
  - `ALLOWED_PROCESS_LAUNCHERS`를 두었다(비어 있고 테스트로 고정).
  - `java.lang`·`java.util`·`java.security`·`java.net`·`java.io`는 클래스 단위로만 허용한다(`ALLOWED_JDK_CLASSES`).
  - `JACKSON_OBJECT_ACCESS`에 모듈 자동 발견을 더했다.
  - 고정 예제 `DynamicCode#viaProvider`·`JacksonObjectAccess#discoverModules`를 더했다.
  - ADR-002 9.5와 D-085를 정정했다.
  - 4006964는 이 파일을 바꾸지 않았다(`git diff 8258105 4006964 -- docs/reviews`에 변경 없음).
- 점검표: A
- 직접 실행한 것: 모두 별도 작업 트리(4006964)에서 했고, 끝난 뒤 작업 트리를 지웠다. 공유 작업 트리 소스는 건드리지 않았다. 공유 작업 트리에 다른 사람의 커밋 안 된 변경이 있어서, 이 원문은 origin 브랜치에서 만든 별도 작업 트리에서 커밋했다.
  - `./gradlew build --no-daemon`: EXIT=0. 테스트 결과는 다음과 같고, 모두 실패·오류·건너뜀 0이다.
    - `:autotrade-risk:test` 55건
    - `:autotrade-architecture-test:test` 39건(이 중 `RealPathReflectionTest` 7건)
    - `:autotrade-app-engine:test` 50건
  - 변조 실험 ①: 감시 모듈 `autotrade-app-watchdog`에 `com.autotrade.app.watchdog.qaprobe.LaunchProbe`를 넣었다. 네 호출 모두 실패로 잡혔다. 감시 프로세스도 실제 규칙 범위 안이라는 뜻이다.
    - `new ProcessBuilder(..).start()`: 외부 명령 금지 규칙
    - `ServiceLoader.load(Class, ClassLoader)`: ServiceLoader 금지
    - `URLClassLoader.getSystemClassLoader()`: 로더 금지
    - `URL#getContent()`: 허용 목록 밖 타입 `java.net.URL`
  - 변조 실험 ②: `autotrade-control`에 `PropProbe`(`System.setProperty(k, v)`)를 넣었다. `RealPathReflectionTest`가 그대로 통과했다(잡지 못함). 실험 파일은 모두 지웠다.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F4 외부 명령 금지와 #8·#9 재기동 정합 | 해소((a)안) | RealPathReflectionTest.java `ALLOWED_PROCESS_LAUNCHERS = Set.of()`. `reflective()`의 `Runtime#exec`·`ProcessBuilder` 분기는 이 목록에 든 클래스만 예외로 둔다. 목록이 비어 있음은 `실전_경로_런타임_구성은_고정_목록과_같다`가 단언한다. D-085 결정 1번과 ADR-002 9.5에 구현 경로가 적혀 있다(감시 실행기 클래스 하나만 허용, 실행 명령도 그 PR에서 고정, 보호 테스트 변경). 대응표(broker-and-fault-design.md 6장)는 바뀌지 않았다 |
| A12 | 해소 | #8·#9의 전제(감시 프로세스가 재기동한다)는 그대로이고, 구현 경로는 보호 테스트 변경으로 정해졌다 |
| A14 (2차) | 해소 | D-085와 ADR-002 9.5가 클래스 단위 허용과 외부 명령 허용 목록을 실제 규칙대로 적었다 |
| R4 Jackson 모듈 자동 발견 | 해소 | `JACKSON_OBJECT_ACCESS`에 `findAndRegisterModules|findModules|findAndAddModules`를 더했다. `JacksonObjectAccess#discoverModules`가 걸리는 것을 `규칙은_리플렉션_우회를_실제로_잡아낸다`가 확인한다 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(diff는 `autotrade-architecture-test`, 문서, 검토 원문뿐). `RealTradingGuard`는 생성 시 전 계좌 OFF 그대로이고 `:autotrade-risk:test` 55건이 통과했다 |
| A2 | 수정 필요 | 기존 `ArchitectureTest` 규칙은 바뀌지 않았고, 새 규칙도 변조 실험 ①과 고정 예제로 걸리는 것을 확인했다. 다만 F5가 남아 있다. 허용 클래스 `java.lang.System`의 `setProperty`·`setProperties`·`clearProperty`가 막혀 있지 않다(4006964 RealPathReflectionTest.java:197은 `load`·`loadLibrary`만 막는다). 변조 실험 ②로 실제로 잡지 못함을 확인했다 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정과 `processIsolation`(autotrade-app-engine/build.gradle.kts)이 그대로다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. 감시 쪽 `ProcessHandle`(조회·종료)은 허용 목록에 있어서, 자원 부족 시 정지 순서를 구현하는 데 걸리지 않는다 |
| A10 | 통과 | 새 예제에 비밀값 없음(`"X"`, `"MessageDigest.Y"`는 자리 문자열) |
| A11 | 통과 | 기존 단언 삭제·약화 없음. 허용 범위는 줄었고(5개 패키지를 클래스 단위로) 단언은 늘었다 |
| A12 | 통과 | 장애 대응표 17종, O1~O8, 장애 테스트 8종의 전제를 바꾸는 동작 변경이 없다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 수정 필요 | F5를 고친 뒤 D-085 결정 1번(허용 클래스 안의 위험 메서드 목록)과 ADR-002 9.5에 JVM 속성 변경 금지를 더해야 한다 |

### 점검표 밖 발견
- **F5 (작음, 수정 필요)**: 실전 경로 코드가 JVM 시스템 속성을 바꿀 수 있다. architect 3차 재검토 AR-7과 같은 지적이고, risk-ops가 따로 확인했다. 확인 방법은 두 가지다. 하나는 위 규칙 코드 197행을 직접 읽은 것이고, 다른 하나는 변조 실험 ②로 규칙이 잡지 못함을 본 것이다.
  - JDK에는 이름 문자열로 클래스를 만들게 하는 속성이 있다. 예를 들면 `java.util.logging.LogManager`가 처음 초기화될 때 `java.util.logging.config.class`에 적힌 클래스를 만든다(LogManager API 문서). 그래서 이 수단은 `ResourceBundle`·`Provider`와 같은 종류다 `[사실]`.
  - 실제로 그 클래스가 만들어지는지 실행해 보는 실험은 하지 않았다.
  - 실전 경로 운영 코드는 지금 `System.setProperty`·`setProperties`·`clearProperty`를 쓰지 않는다(`git grep` 결과 0건). 그래서 막아도 깨지는 코드가 없다. 설정은 RuntimeConfigHolder로 읽게 되어 있으므로, 실전 경로가 JVM 속성을 바꿀 이유도 없다.
  - 요구:
    - `reflective()`의 `java.lang.System` 분기에 세 메서드를 더한다.
    - `DynamicCode`에 고정 예제를 더하고 그 예제가 걸리는지 단언한다.
    - 실행 옵션으로 주는 `-D` 속성은 7단계 실행 절차서의 설치본 대조 항목에 넣는다.

### 판정
수정 필요(F5 `System#setProperty`·`setProperties`·`clearProperty` 금지와 고정 예제, A14 D-085·ADR-002 9.5 문구 반영)

## 재검토 2026-10-14 (4)
- 대상: PR #108 / 헤드 87863e8 / `git diff origin/main...HEAD` 전체.
  - 3차 재검토 뒤 바뀐 것:
    - `reflective()`의 `java.lang.System` 분기에 `setProperty`·`setProperties`·`clearProperty`를 더했다.
    - 고정 예제 `DynamicCode#viaSystemProperty`를 더하고, 그 예제가 걸리는지 단언했다.
    - D-085 결정 1번, 위험과 한계(`-D` 실행 옵션을 설치본 대조 항목으로), ADR-002 9.5를 정정했다.
  - 87863e8은 이 파일(risk-ops.md)을 바꾸지 않았다.
- 점검표: A
- 직접 실행한 것:
  - 실행 장소: 별도 작업 트리(87863e8)에서 하고 끝난 뒤 지웠다. 공유 작업 트리 소스는 손대지 않았다.
  - `./gradlew build --no-daemon` → EXIT=0. 실패·오류·건너뜀 0.
    - `:autotrade-risk:test` 55건
    - `:autotrade-architecture-test:test` 39건(`RealPathReflectionTest` 7건 포함)
    - `:autotrade-app-engine:test` 50건
  - 변조 실험(커밋하지 않음, 지움): `autotrade-control`에 `PropProbe`를 넣었다. `System.setProperty(k, v)`와 우회형 `System.getProperties().put(k, v)` 두 가지다.
    - 결과: 둘 다 `RealPathReflectionTest` 실패로 잡혔다.
    - `setProperty`는 금지 메서드에 걸렸다.
    - `getProperties()`는 반환 타입 "허용 목록 밖 타입 java.util.Properties"로 걸렸다.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F5 시스템 속성 쓰기 | 해소 | RealPathReflectionTest.java `reflective()`의 `java.lang.System` 분기, `규칙은_리플렉션_우회를_실제로_잡아낸다`가 `System.setProperty`·`setProperties`·`clearProperty`를 단언, 변조 실험 2종이 실패로 잡힘 |
| A14 | 해소 | D-085 결정 1번과 위험과 한계(허용 클래스 안 위험 메서드 목록에 `setProperty`, `-D` 실행 옵션은 7단계 설치본 대조), ADR-002 9.5 규칙 문구에 시스템 속성 쓰기 금지가 들어감 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(diff는 `autotrade-architecture-test`, 문서, 검토 원문뿐). `RealTradingGuard`를 만들면 모든 계좌가 OFF인 것은 그대로다. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 기존 `ArchitectureTest` 규칙 변경 없음. 실전 경로 운영 클래스에서 막는 것은 다음과 같다. 수단마다 고정 예제와 1~4차 변조 실험으로 걸리는 것을 확인했다(앞 절들).<br>- 리플렉션, 동적 코드, 클래스 로더, `ResourceBundle`<br>- 외부 명령(허용 목록 비어 있음), 보안 제공자, 시스템 속성 쓰기<br>- Jackson 객체 접근·모듈 발견, Guard 타입·2차 확인의 데이터 바인딩<br>- 허용 목록 밖 JDK·외부 타입 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정과 `processIsolation`(autotrade-app-engine/build.gradle.kts)이 그대로다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. 감시 쪽 `ProcessHandle`(조회·종료)은 허용되어 있다 |
| A10 | 통과 | 새 예제에 비밀값 없음 |
| A11 | 통과 | 기존 단언 삭제·약화 없음, 단언 3개 추가 |
| A12 | 통과 | 장애 대응표 17종, O1~O8, 장애 테스트 8종의 전제를 바꾸는 동작 변경이 없다. #8·#9 감시 재기동은 `ALLOWED_PROCESS_LAUNCHERS` 경로로 정해져 있다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 통과 | D-085, 결정 로그, ADR-002 9.5, 기획서 TST-07 (9)이 규칙과 일치한다. 남은 한계는 `[추론]`으로 적혀 있다:<br>- 허용 범위 안의 아직 모르는 수단<br>- `Object`로 받은 확인의 `getClass()`<br>- 설치본·JVM 옵션(7단계 절차서로 넘김) |

### 점검표 밖 발견
- 차단 사항은 없다.
- 남은 위험은 D-085 "보장하지 않는 것"에 적힌 그대로다. 그중 다음 셋은 7단계 실행 절차서를 검토할 때 risk-ops가 다시 확인한다.
  - 설치본 대조
  - `-D` 실행 옵션
  - `-XX:+DisableAttachMechanism`
- 이 PR은 보호 테스트를 강화하므로 실전 관련 PR이다. 지침 10장·11장에 따라 머지 전에 사용자 확인과 독립 QA 관문을 거쳐야 한다.

### 판정
승인

## 재검토 2026-10-14 (헤드 743545c)
- 대상: PR #108 브랜치 `claude/project-thread-l44wj7`, 헤드 743545c. `git diff origin/main...HEAD` 전체(21파일, +1,932 −3)를 다시 봤다. 이번 재검토의 새 커밋은 743545c(독립 QA `docs/reviews/pr-108/qa.md` F1·F2 반영) 하나다.
  - 운영 코드 변경은 여전히 0줄이다. 바뀐 것은 다음과 같다.
    - `ClassFileTypes.java` 새 파일(클래스 파일 상수 풀 읽기)
    - `RealPathReflectionTest.java`의 `outsideAllowlist` 확장, `classAsData`·`ALLOWED_CLASS_METHODS`·`JAVAC_BOOTSTRAP_TYPES` 추가, 허용 목록 추가
    - 고정 예제 `NameToClassBinding`, 허용 예제 `ReflectionFreeEngineCode.parse`
    - D-086, ADR-002 9.5 6항, `qa-out-status.md` 한 줄
  - 커밋 메시지와 D-086의 주장은 확인할 주장으로만 봤다.
- 점검표: A
- 직접 실행한 것:
  - 실행 장소: 별도 작업 트리 2개(검토용, 변조 실험용, 둘 다 743545c)에서 하고 끝난 뒤 지웠다. 공유 작업 트리 소스는 손대지 않았다.
  - `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL, EXIT=0. 시험 결과 XML 합계 1,130건, 실패·오류·건너뜀 0.
    - `:autotrade-risk:test` 55건
    - `:autotrade-architecture-test:test` 39건(`RealPathReflectionTest` 7건 포함)
    - `:autotrade-app-engine:test` 50건
  - 변조 실험(커밋하지 않음, 지움). 실전 경로 범위인 `autotrade-control`(Jackson 컴파일 의존이 이미 있음)에 탐침을 넣고 `:autotrade-architecture-test:test`를 돌렸다.
    - P1 `Class<?>[] ks = M.readValue("[\"" + name + "\"]", Class[].class); M.convertValue(fields, ks[0])` → **통과(잡히지 않음)**
    - P2 `String.class.getClass()`를 `readValue`의 대상 타입으로 → 통과. javap로 보면 javac는 `Object.getClass`로 부른다. D-086 위험과 한계에 적힌 그대로다
    - P3 `Object o = String.class; readValue(.., o.getClass())` → 통과. P2와 같은 한계
    - P5 `record P5ArrayField(Class<?>[] types)` → 잡힘(필드·생성자 매개변수 2건)
  - P1 실제 동작: 같은 클래스를 Jackson 2.21.4로 직접 돌렸다.
    - `"com.autotrade.domain.account.AccountId"`라는 이름만으로 `KIS-1` 객체가 만들어졌다.
    - `"com.autotrade.risk.guard.SecondConfirmation"`는 `java.time.Instant` 미지원으로 실패했다. QA가 Q16에서 본 것과 같은 상태다. 지금 2차 확인 위조를 막는 것은 규칙이 아니라 `Instant` 필드 하나다.
  - 수정안 검증(커밋하지 않음): `classAsData`의 클래스 객체 필터를 `r.getRawType().getBaseComponentType().getName().equals("java.lang.Class")`로 바꿨다.
    - P1이 "클래스 객체 Class.class를 참조 (P1ArrayClass.java:11)"로 잡혔다.
    - 탐침을 지운 뒤 `:autotrade-architecture-test:test`는 BUILD SUCCESSFUL이었다. 운영 코드 오탐은 없다.

### 앞 지적(독립 QA) 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| QA F1 이름 문자열 클래스 찾기 | **일부 해소** | `RealPathReflectionTest.java:403-427` `classAsData`가 `Class.class` 리터럴, `Class` 필드·생성자·설정자·Jackson 주석 메서드 매개변수, 제네릭 인자, 목록 밖 `Class` 메서드를 잡는다. `규칙은_리플렉션_우회를_실제로_잡아낸다`(`:736-750`)가 `NameToClassBinding`으로 단언한다. 그러나 `RealPathReflectionTest.java:405-406`의 필터가 클래스 객체의 원래 타입 이름을 `java.lang.Class`와 그대로 비교한다. 그래서 배열 클래스 객체 `Class[].class`(이름 `[Ljava.lang.Class;`)가 빠진다(P1, 아래 F6) |
| QA F2 허용 목록 밖 타입(형변환·호출 서명) | 해소 | `outsideAllowlist`(`:357-386`)가 호출 대상의 매개변수·반환 타입, 필드 접근 타입, `ClassFileTypes` 상수 풀(클래스 상수, 서술자, 제네릭 서명)을 대조한다. `JAVAC_BOOTSTRAP_TYPES`는 상수 검사에서만 뺀다. 소스에서 쓰면 `java.lang.invoke` 금지가 따로 잡는다. 시험이 `MapperFeature`가 잡히는 것과 형변환만 한 메서드가 상수 풀에서 잡히는 것을 단언한다(`:739-750`). `ClassFileTypes`의 상수 태그 길이(3·4·5·6·7·8·9~12·15~20)는 JVMS 4.4와 맞다. 문자열 상수의 utf8은 빼되, 같은 utf8이 클래스 상수이기도 하면 넣는다. 그래서 문자열 리터럴로 형변환 대상을 숨길 수 없다 |
| QA R1 `Instant` 의존 | 기록됨 | D-086 위험과 한계(:47)에 날짜 처리 Jackson 모듈 PR 때 함께 검토한다고 적혔다. 다만 F6 때문에 지금도 `Instant` 하나가 마지막 방어선이다 |
| QA A14 | **미해소** | F1 부분은 F6 때문에 아래 A14 그대로다 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 0줄. `RealTradingGuard` 기본 OFF·재기동 OFF가 그대로다. `:autotrade-risk:test` 55건 통과 |
| A2 | **실패** | 기존 `ArchitectureTest` 출구·Guard 규칙 변경은 없다(diff 0줄). 새 규칙이 막겠다고 한 경로 하나는 열려 있다. 실전 경로 모듈 코드가 `readValue(.., Class[].class)`로 이름 문자열에서 클래스를 얻고, 정적 의존 없이 Jackson이 만들 수 있는 아무 타입이나 만든다(P1, F6). 2차 확인(`SecondConfirmation`) 위조는 지금 `Instant` 필드 하나가 막고 있을 뿐이다 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정과 엔진 `processIsolation`이 그대로다. 빌드에서 통과했다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 파일(`ClassFileTypes`, `NameToClassBinding`)에 키·계좌번호·토큰 없음 |
| A11 | 통과 | 보호 시험 삭제·약화 없음. 허용 목록에 더한 JDK·Jackson 타입은 확인했다(`java.time.chrono`·`temporal`, `java.security.spec`, `JsonParser`, `TreeNode`, `Module`, `JsonSerializer`, `PropertyNamingStrategy`, `MapperBuilder` 등). 모두 운영 코드가 이미 쓰던 타입이 넓어진 대조(상수 풀)에 새로 나온 것이고, 이름으로 클래스를 만드는 API는 아니다. `Module`은 `findAndRegisterModules`·`findModules` 금지가 그대로다. 새 규칙에는 실패하는 경우를 포함한 시험이 있다. 다만 F6의 배열 경우를 단언하는 예제는 없다 |
| A12 | 통과 | 장애 대응표 17종, O1~O8, 장애 테스트 8종의 전제를 바꾸는 동작 변경이 없다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | **실패** | 문서가 보장한다고 적은 것과 규칙의 실제 동작이 다르다.<br>- ADR-002 9.5 6항(`ADR-002-real-trading-guard.md:239`): "Jackson의 `Class` 역직렬화로 이름 문자열에서 클래스를 얻는 길(`Class.class` 클래스 객체, …) … 금지"<br>- D-086 결정 1번(`D-086-real-path-reflection-ban.md:23`): "Jackson은 `Class` 타입 값을 이름 문자열에서 `Class.forName`처럼 만든다 … 그래서 `Class.class` 클래스 객체 … 를 쓰지 않는다"<br>- `guardTypeAsData` 주석(`RealPathReflectionTest.java:526`): "Jackson의 `Class` 역직렬화는 실전 경로에서 금지"<br>그런데 `Class[].class`로 같은 일을 하는 코드가 규칙을 통과한다(P1). `getClass().getClass()` 경로는 D-086:47에 한계로 적혀 있어 문서와 어긋나지 않는다 |

### 점검표 밖 발견
- **F6 배열 클래스 객체 `Class[].class`로 Jackson `Class` 역직렬화가 열려 있다**(차단, QA F1의 남은 부분)
  - 위치: `RealPathReflectionTest.java:405-406`
    ```java
    c.getReferencedClassObjects().stream()
            .filter(r -> r.getRawType().getName().equals("java.lang.Class"))
    ```
  - 원인: `Class[].class`의 원래 타입 이름은 `[Ljava.lang.Class;`이라 이 필터에 걸리지 않는다. 다른 검사도 모두 통과한다.
    - `outsideAllowlist`는 원소 타입 `java.lang.Class`를 허용 클래스로 본다.
    - `ClassFileTypes`도 원소 타입으로 줄여 허용으로 본다.
    - 이 메서드에는 `Class` 매개변수도 필드도 없다.
  - 결과: P1과 직접 실행으로 확인했다. 이름 문자열에서 클래스를 얻고(정적 초기화 포함), `convertValue(fields, ks[0])`로 정적 의존 없이 객체를 만든다. `Class[][].class`, `treeToValue(.., Class[].class)`, `convertValue(.., Class[].class)`도 같은 원인이다 `[추론]`.
  - 수정 제안:
    1. 필터를 `r.getRawType().getBaseComponentType().getName().equals("java.lang.Class")`로 바꾼다. 이렇게 바꾸면 P1이 잡히고, 운영 코드 오탐이 없음을 확인했다(위 수정안 검증).
    2. `NameToClassBinding`에 `readValue(.., Class[].class)` 예제를 더하고 `규칙은_리플렉션_우회를_실제로_잡아낸다`에서 단언한다.
    3. D-086 결정 1번과 ADR-002 9.5 6항 문구를 "`Class`와 그 배열의 클래스 객체"로 맞춘다.
- **R5 `getClass()`로 얻은 `Class` 타입 객체**(권고, 차단 아님)
  - P2·P3는 리터럴 없이 F1과 같은 일을 한다. D-086:47이 "리뷰로 막는다 `[추론]`"으로 적어 두었으므로 A14와는 어긋나지 않는다.
  - 다만 위력은 F1과 같고, 앞서 받아들인 "`Object`로 받은 Guard 객체의 `getClass()`" 한계보다 넓다. 그 한계는 Guard 객체가 이미 손에 있어야 하지만, 이 경로는 아무 클래스 객체에서나 시작할 수 있다.
  - 정적 대안을 제안한다. 실전 경로 범위에서 Jackson 읽기 API(`readValue`·`convertValue`·`treeToValue`·`readerFor`·`forType`)의 `Class` 인자가 클래스 리터럴(`ldc`)이나 메서드의 `Class<T>` 매개변수에서 오지 않으면 실패시킨다. 바이트코드에서 인자 출처를 짧게 추적하면 된다.
  - 이 대안을 하지 않는다면, 7단계에서 실전 경로 Jackson에 날짜 처리를 넣는 PR(QA R1) 전까지는 꼭 막아야 한다. 그 PR이 오면 risk-ops는 이 경로가 막혔는지를 승인 조건으로 본다.
- 이 PR은 보호 테스트를 강화하므로 실전 관련 PR이다. 지침 10장·11장에 따라 머지 전에 사용자 확인과 독립 QA 재검토를 거쳐야 한다.

### 판정
수정 필요 (A2·A14, F6: `Class[].class`로 이름 문자열 클래스 찾기가 규칙을 통과해 독립 QA F1이 일부만 해소됨. 권고 R5)

## 재검토 2026-10-14 (헤드 4f44ccc)
- 대상: PR #108 브랜치 `claude/project-thread-l44wj7`, 헤드 4f44ccc. `git diff origin/main...HEAD` 전체(22파일, +2,211 −3)를 다시 봤다. 새 커밋은 4f44ccc 하나다(architect AR-8·AR-9, risk-ops F6·R5 반영).
  - 운영 코드 변경은 여전히 0줄이다. 바뀐 것은 다음과 같다.
    - `GetClassUses.java` 새 파일(바이트코드 명령 순서로 `getClass()` 결과 사용처 검사)
    - `RealPathReflectionTest.java` `classAsData`의 클래스 객체 필터를 원소 타입 기준(`getBaseComponentType`)으로 바꾸고 `GetClassUses`를 붙임, 단언 3개 추가
    - 고정 예제 `NameToClassBinding#byNames`·`#viaGetClass`·`#classOf`
    - D-086 결정 1번·위험과 한계, ADR-002 9.5 6항·남은 한계, `qa-out-status.md` 한 줄
  - 커밋 메시지와 D-086의 주장은 확인할 주장으로만 봤다.
  - 4f44ccc는 이 파일(risk-ops.md)을 바꾸지 않았다.
- 점검표: A
- 직접 실행한 것:
  - 실행 장소: 별도 작업 트리 2개(검토용, 변조 실험용, 둘 다 4f44ccc)에서 하고 끝난 뒤 지웠다. 공유 작업 트리 소스는 손대지 않았다.
  - `./gradlew build --no-daemon --rerun-tasks` → BUILD SUCCESSFUL, EXIT=0, 130개 작업 실행. 시험 결과 XML 합계 1,130건, 실패·오류·건너뜀 0.
    - `:autotrade-risk:test` 55건
    - `:autotrade-architecture-test:test` 39건(`RealPathReflectionTest` 7건 포함)
    - `:autotrade-app-engine:test` 50건
  - 변조 실험(커밋하지 않음, 지움). 실전 경로 범위인 `autotrade-control`에 탐침을 넣고 `:autotrade-architecture-test:test`를 돌렸다.
    - Q0 `return Class.class;` → 잡힘("클래스 객체 Class.class를 참조"). 이 실험 배치에서 규칙이 `autotrade-control`을 보고 있음을 확인한 대조군이다.
    - Q1 상위 타입 인자: `final class Names extends ArrayList<Class<?>> {}`, `Names n = M.readValue("[\"" + name + "\"]", Names.class); M.convertValue(fields, n.get(0))` → **통과(잡히지 않음)**
    - Q2 게터를 설정자로 쓰기: `Bag`에 `List<Object> store` 필드와 `public List<Class<?>> getItems() { return (List) store; }`, `M.readValue("{\"items\":[\"" + name + "\"]}", Bag.class)` 뒤 `M.convertValue(fields, b.getItems().get(0))` → **통과(잡히지 않음)**
  - Q1·Q2 실제 동작: 빌드된 `autotrade-control` 클래스를 Jackson 2.21.4(`jackson-databind-2.21.4.jar`)로 직접 돌렸다.
    - 둘 다 `"com.autotrade.domain.account.AccountId"`라는 이름 문자열만으로 `AccountId` 객체(`KIS-1`, `KIS-2`)가 만들어졌다. 실전 경로 모듈 코드가 정적 의존 없이 이름으로 클래스를 얻고 객체를 만든다.
    - `"com.autotrade.risk.guard.SecondConfirmation"`는 Q1에서 `IllegalArgumentException`(생성자 검증 `account`)으로 실패했다. 클래스 찾기 자체는 됐고, 필드 값과 `Instant` 처리만 남은 상태다.
  - 수정안 검증(커밋하지 않음): `classAsData`에 두 검사를 더했다.
    - 상위 클래스·인터페이스의 실제 타입 인자에 `Class`가 들어가면 위반
    - 메서드 반환 타입이 매개변수화 타입이고(원래 타입이 `Class`인 경우 제외) 타입 인자에 `Class`가 들어가면 위반(기존 매개변수 `classArgument` 판정과 같은 모양)
    - 결과: Q1(`Q1SuperArg$Names 상위 타입 인자에 Class`)과 Q2(`Q2GetterAsSetter$Bag.getItems() 반환 타입에 Class`)만 잡혔다. 탐침을 지운 뒤 `:autotrade-architecture-test:test`는 BUILD SUCCESSFUL이었다. 운영 코드 오탐은 없다.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F6 `Class[].class` | 해소 | `RealPathReflectionTest.java:405-408` 필터가 `getRawType().getBaseComponentType()`으로 원소 타입을 본다. `규칙은_리플렉션_우회를_실제로_잡아낸다`가 `NameToClassBinding.byNames(java.lang.String)가 클래스 객체 Class[].class를 참조`를 단언한다(`:749`). `Class[][].class`도 원소 타입이 같으므로 잡힌다 `[추론]` |
| R5 `getClass()`로 얻은 `Class` 타입 객체 | 해소 | `GetClassUses.java:77-94`가 이름·서술자 `getClass()Ljava/lang/Class;` 호출(소유 타입 무관, `invokespecial` 포함) 바로 다음 명령이 `Class.getName()`·`getSimpleName()`이 아니면 위반으로 적는다. 다른 모든 명령 방문자가 대기 상태를 끊고(`:96-110`), `invokedynamic` 인자에 `getClass` 핸들이 있으면 메서드 참조로 잡는다. 람다 본문은 같은 클래스 파일의 합성 메서드라 함께 읽힌다. 단언은 `:750-751`(`viaGetClass`·`classOf`)과 허용 예제 `:753`(`ReflectionFreeEngineCode` 위반 0건)이다. 운영 코드 전체가 이 규칙을 통과했으므로(빌드) 오탐도 없다 |
| A2(직전) | **미해소** | F6 경로는 닫혔지만 같은 결과(이름 문자열 → 클래스 → 객체)를 내는 다른 바인딩 대상 두 가지가 열려 있다(아래 F7) |
| A14(직전) | **미해소** | F6 문구는 맞춰졌지만 아래 A14 그대로 문서가 규칙보다 넓게 보장한다 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 0줄. `RealTradingGuard` 기본 OFF·재기동 OFF가 그대로다. `:autotrade-risk:test` 55건 통과 |
| A2 | **실패** | 기존 `ArchitectureTest` 출구·Guard 규칙 변경은 없다(diff 0줄). 새 규칙이 막겠다고 한 경로가 아직 열려 있다. 실전 경로 모듈 코드가 상위 타입 인자(`extends ArrayList<Class<?>>`, Q1)나 게터 반환 타입(`List<Class<?>> getItems()`, Q2)으로 Jackson에 `Class` 값을 바인딩시켜, 이름 문자열에서 클래스를 얻고 정적 의존 없이 객체를 만든다. 실제 실행으로 확인했다(F7). 2차 확인 위조는 지금 생성자 검증과 `Instant` 처리만 막고 있다 |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정과 엔진 `processIsolation`이 그대로다. 빌드에서 통과했다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 파일(`GetClassUses`)과 예제 추가분에 키·계좌번호·토큰 없음 |
| A11 | 통과 | 보호 시험 삭제·약화 없음. 단언 3개와 허용 예제 단언 1개가 늘었다. 새 규칙(`GetClassUses`)에는 실패하는 경우(`viaGetClass`·`classOf`)와 허용 경우(`ReflectionFreeEngineCode`)가 함께 있다. 다만 F7의 두 경우를 단언하는 예제는 없다 |
| A12 | 통과 | 장애 대응표 17종, O1~O8, 장애 테스트 8종의 전제를 바꾸는 동작 변경이 없다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | **실패** | 문서가 보장한다고 적은 것과 규칙의 실제 동작이 다르다.<br>- ADR-002 9.5 6항(`ADR-002-real-trading-guard.md:239`): "`Class` 속성을 가진 바인딩 대상 … 금지". Q1의 원소 타입, Q2의 게터 속성은 Jackson이 보는 `Class` 속성인데 규칙을 통과한다<br>- D-086 위험과 한계(`D-086-real-path-reflection-ban.md:47`): "`Instant` … 사실에 기대지 않는다(위 1번이 `Class` 값 바인딩을 막는다)". Q1·Q2에서 `Class` 값 바인딩이 실제로 된다<br>- D-086 결정 1번(`:23`)이 나열한 대상(필드·생성자 매개변수·매개변수 제네릭 인자·설정자·Jackson 주석 메서드)에는 상위 타입 인자와 게터 반환 타입이 없다. 나열은 사실이지만, 그 나열로 `Class` 값 바인딩이 닫힌다는 결론(:47)이 틀렸다 |

### 점검표 밖 발견
- **F7 상위 타입 인자와 게터 반환 타입으로 Jackson `Class` 역직렬화가 열려 있다**(차단, 독립 QA F1의 남은 부분)
  - 위치: `RealPathReflectionTest.java:403-431` `classAsData`. 필드(`:409-411`), 코드 단위 매개변수(`:413-422`), 클래스 객체(`:405-408`), `getClass()`(`:429`)만 본다. 상위 타입의 타입 인자와 메서드 반환 타입은 보지 않는다. `outsideAllowlist`·`ClassFileTypes`는 `java.lang.Class`를 허용 타입으로 보므로 거기서도 걸리지 않는다.
  - Q1: Jackson은 `Names.class`의 상위 타입 `ArrayList<Class<?>>`에서 원소 타입 `Class`를 풀어 이름 문자열마다 클래스를 만든다. `Names`에는 `Class` 필드·매개변수·리터럴이 없다. 같은 모양으로 `extends HashMap<String, Class<?>>`, 제네릭 실전 경로 클래스 `Holder<T>`의 `Holder<Class<?>>` 하위 클래스도 열려 있다 `[추론]`.
  - Q2: Jackson 기본 설정 `MapperFeature.USE_GETTERS_AS_SETTERS`(기본 켜짐)는 설정자 없는 컬렉션·맵 속성을 게터로 받아 원소를 넣는다. 원소 타입은 게터 반환 타입 `List<Class<?>>`에서 정해진다. 필드는 `List<Object>`라 필드 검사에 걸리지 않는다.
  - 결과: 위 직접 실행대로 이름 문자열만으로 `AccountId` 객체가 만들어졌다.
  - 수정 제안:
    1. `classAsData`에 상위 클래스·인터페이스의 실제 타입 인자 검사를 더한다(`guardTypeAsData`의 `:536-544`와 같은 방식, 판정만 `involvesClass`).
    2. 메서드 반환 타입이 매개변수화 타입이고 원래 타입이 `Class`가 아니며 타입 인자에 `Class`가 들어가면 위반으로 한다(매개변수 `classArgument`와 같은 모양). 위 수정안 검증에서 두 검사 모두 운영 코드 오탐이 없었다.
    3. `NameToClassBinding`에 두 예제(상위 타입 인자, 컬렉션 게터)를 더하고 `규칙은_리플렉션_우회를_실제로_잡아낸다`에서 단언한다.
    4. D-086 결정 1번과 ADR-002 9.5 6항에 "상위 타입 인자, 반환 타입의 제네릭 인자"를 더한다. D-086:47의 "위 1번이 `Class` 값 바인딩을 막는다"는 고친 뒤에도 "알려진 바인딩 대상 모양을 막는다 `[추론]`" 정도로 낮춘다. Jackson이 타입을 푸는 곳은 위 둘 말고도 더 있을 수 있다(예: 타입 변수 경계, 위임 생성자) `[추론]`. 이번 검토에서는 확인하지 못했다.
- 근본 원인(참고): 지금 방식은 "Jackson이 `Class` 타입을 알아낼 수 있는 자리"를 하나씩 나열해 막는다. 다섯 차례 재검토에서 같은 종류의 구멍이 계속 나왔다(F1·F6·R5·F7). 다음 수정에서 함께 검토할 대안 두 가지를 적는다. 어느 쪽을 택할지는 architect·작성자 판단이며, 이번 판정 조건은 아니다.
  - 대안 1: 실전 경로 운영 코드 전체에서 `Class`가 시그니처(필드·매개변수·반환·상위 타입·타입 인자)에 나오는 곳을 기본 금지하고, `ControlCodec.parse`·`ProfileParams.choice` 같은 `Class<T>` 매개변수만 허용 목록에 둔다.
  - 대안 2: 실전 경로가 쓰는 `ObjectMapper`를 한 곳에서 만들고, 그 매퍼에 `Class` 역직렬화기를 금지하는 모듈(역직렬화 시 `Class`·`Class[]` 타입을 만나면 예외)을 등록한다. 이 경우 정적 규칙은 "그 공장 밖에서 `ObjectMapper`·`JsonMapper`를 만들지 않는다"로 줄어든다. 실행 시 방어라 테스트로 고정해야 한다.
- 이 PR은 보호 테스트를 강화하므로 실전 관련 PR이다. 지침 10장·11장에 따라 머지 전에 사용자 확인과 독립 QA 재검토를 거쳐야 한다.

### 판정
수정 필요 (A2·A14, F7: 상위 타입 인자 `extends ArrayList<Class<?>>`와 게터 반환 타입 `List<Class<?>> getItems()`로 이름 문자열 클래스 찾기가 규칙을 통과해 독립 QA F1이 아직 일부만 해소됨. F6·R5는 해소)

## 재검토 2026-10-14 (헤드 af0a8e3)
- 대상: PR #108 브랜치 `claude/project-thread-l44wj7`, 헤드 af0a8e3. `git diff origin/main...HEAD` 전체(22파일, +2,379 −3)를 다시 봤다. 직전 재검토(4f44ccc) 뒤 새 운영·시험 커밋은 af0a8e3 하나다. 그 사이 3759fb4·043332f·a8a3c7d는 검토 원문 커밋이다.
  - 운영 코드 변경은 여전히 0줄이다. af0a8e3가 바꾼 것은 다음과 같다.
    - `RealPathReflectionTest.java` `classAsData`: 상위 클래스·인터페이스 타입 인자(`:416-419`), 클래스 타입 변수 경계(`:420-421`), 모든 코드 단위의 반환 타입(`:425-427`), 메서드 타입 변수 경계(`:428-429`) 검사를 더했다. 매개변수는 "바인딩이 아닌 메서드의, 제네릭 인자에 `Class`가 없는 `Class<T>` 바로 그 타입"만 허용하도록 바꿨다(`:430-437`).
    - 고정 예제 `NameToClassBinding$SuperProbe`·`Holder`·`TypeVarProbe`·`GetterProbe`와 단언 3개, 허용 예제 단언 1개(`Holder`는 잡히지 않음, `:771`)
    - D-086 결정 1번·위험과 한계 47행, ADR-002 9.5 6항(`:239`), `qa-out-status.md` 한 줄
  - 커밋 메시지와 D-086의 주장은 확인할 주장으로만 봤다.
  - af0a8e3는 이 파일(risk-ops.md)을 바꾸지 않았다.
- 점검표: A
- 직접 실행한 것:
  - 실행 장소: 별도 작업 트리(af0a8e3)에서 하고 끝난 뒤 지웠다. 공유 작업 트리 소스는 손대지 않았다.
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL, EXIT=0, 130개 작업 실행. 시험 결과 XML 합계 1,130건, 실패·오류·건너뜀 0.
  - 탐침을 지운 뒤 `./gradlew :autotrade-architecture-test:test :autotrade-risk:test :autotrade-app-engine:test --rerun-tasks` → EXIT=0.
    - `:autotrade-risk:test` 55건
    - `:autotrade-architecture-test:test` 39건(`RealPathReflectionTest` 7건 포함)
    - `:autotrade-app-engine:test` 50건
  - 변조 실험(커밋하지 않음, 지움). 실전 경로 범위인 `autotrade-control`에 `rprobe` 패키지로 10개 탐침을 넣고 `:autotrade-architecture-test:test`를 돌렸다. `실전_경로_모듈은_리플렉션을_쓰지_않는다`만 실패했고 나머지 6건은 통과했다.
    - R0 `return Class.class;` → 잡힘. 이 배치에서 규칙이 `autotrade-control`을 보고 있음을 확인한 대조군이다.
    - R1 직전 Q1(`Names extends ArrayList<Class<?>>` + `readValue` + `convertValue`) → 잡힘("상위 타입 … 에 Class")
    - R2 직전 Q2(`List<Object>` 필드 + `List<Class<?>> getItems()` 게터) → 잡힘("반환 타입에 Class")
    - R3 익명 클래스 `new ArrayList<Class<?>>() {}` → 잡힘(`R3Anon$1`의 상위 타입)
    - R4 `extends HashMap<String, Class<?>>` → 잡힘
    - R5 `Map<String, Class<?>> getM()` 게터 → 잡힘
    - R6 실전 경로 제네릭 클래스 `H<T>`(설정자 `setV(T)`)의 하위 클래스 `extends H<Class<?>>` → 잡힘
    - R7 `implements Supplier<Class<?>>` → 잡힘(상위 타입과 `get()` 반환 타입 2건)
    - R9 `class R9<T extends Class<?>>`, `static <U extends Class<?>> U id(U)` → 잡힘(클래스·메서드 타입 변수 경계, 반환 타입 3건)
    - R8 `public static R8ValueOf valueOf(Class<?> k)` → 통과(허용 모양). 아래 Jackson 직접 실행으로 이 모양이 바인딩 경로가 아님을 확인했다.
  - Jackson 2.21.4 직접 실행(작업 트리 밖 임시 폴더, 탐침과 같은 모양):
    - T1 허용 목록 타입 `ObjectMapper`를 바인딩 대상으로(`readValue("{\"mixIns\":{..}}", ObjectMapper.class)` 뒤 `findMixInClassFor`로 `Class` 값 꺼내기) → `InvalidDefinitionException`(`setDefaultPropertyInclusion` 설정자 충돌)로 매퍼 자체를 만들지 못한다. `JsonMapper`도 같다.
    - T2 허용 목록의 JDK 클래스 전부(`ALLOWED_JDK_CLASSES`)와 Jackson 타입 전부(`JACKSON_ALLOWLIST`)를 Jackson의 역직렬화 속성 분석(`introspect(..).findProperties()`)에 넣었다. `Class`가 들어간 속성은 `java.lang.Class` 자신(읽기 전용 게터만, 리터럴은 따로 금지)과 `ObjectMapper`·`JsonMapper`의 `mixIns`·`mixInAnnotations`(T1에서 만들 수 없음)뿐이다. 그래서 허용 목록 타입을 바인딩 대상으로 써서 `Class` 값을 얻는 길은 지금 Jackson 버전에서 없다.
    - T3 `valueOf(Class<?>)` 정적 메서드(R8 모양)를 가진 타입에 `"<이름>"`과 `["<이름>"]`을 읽힘 → 둘 다 "no String-argument constructor/factory method"로 실패. Jackson은 주석 없는 `valueOf`를 이 모양의 생성기로 쓰지 않는다.
    - `TypeFactory`·`JavaType`·`TypeReference`·`ObjectReader`·`MismatchedInputException`은 허용 목록 밖이라 `JavaType`·`Type`을 거치는 바인딩도 `outsideAllowlist`(호출 서명 대조)가 막는다(`RealPathReflectionTest.java:295-330`, `:357-386`).

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| F7 상위 타입 인자·게터 반환 타입 | 해소 | `RealPathReflectionTest.java:416-419`(상위 클래스·인터페이스), `:425-427`(반환 타입), `:420-421`·`:428-429`(타입 변수 경계). `involvesClass`(`:448-450`)가 모든 관련 원래 타입의 원소 타입을 보므로 맵 값·중첩 제네릭·배열도 걸린다. 변조 실험 R1~R7·R9가 모두 잡혔다. `규칙은_리플렉션_우회를_실제로_잡아낸다`(`:689`)가 `SuperProbe`·`TypeVarProbe`·`GetterProbe`를 단언하고(`:768-770`), 타입 변수만 가진 `Holder`가 잡히지 않음도 단언한다(`:771`) |
| F7 수정 제안 4(문구) | 해소 | D-086 결정 1번(`:23`)과 ADR-002 9.5 6항(`:239`)이 허용 모양 하나와 금지 목록(상위 타입 인자, 반환 타입, 타입 변수 경계)을 적었다. D-086:23에 이 모양으로 또 빈틈이 나오면 실행 시 방어(`Class` 역직렬화기 차단)로 간다는 되돌릴 조건이 생겼다. D-086:47 "`Class`가 나오는 서명 모양을 하나로 줄여 `Class` 값 바인딩을 막는다"는 이번 실험(R0~R9, T1~T3)과 맞는다 |
| A2(직전) | 해소 | 아래 A2 |
| A14(직전) | 해소 | 아래 A14 |
| 근본 원인 대안(직전, 판정 조건 아님) | 반영 | 대안 1(서명의 `Class` 기본 금지, `Class<T>` 매개변수만 허용)이 채택됐다. 대안 2(실행 시 방어)는 D-086:23에 되돌릴 조건으로 남았다 |

### 점검표 A
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 0줄. `RealTradingGuard` 기본 OFF·재기동 OFF가 그대로다. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 기존 `ArchitectureTest` 출구·Guard 규칙 변경은 없다(diff 0줄). 새 규칙이 막겠다고 한 경로(이름 문자열 → `Class` → 정적 의존 없는 객체 생성)가 이번에 시험한 모양에서 모두 막힌다: 직전 Q1·Q2(R1·R2)와 변형 R3~R7·R9, 허용 목록 타입을 바인딩 대상으로 쓰는 길(T1·T2), 허용 모양 `Class<T>` 매개변수가 Jackson 생성기로 쓰이는 길(T3) |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재발주 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `FORBIDDEN_PROJECTS` 고정과 엔진 `processIsolation`이 그대로다. 빌드에서 통과했다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 예제 추가분(`NameToClassBinding.java`)과 문서 변경분에 키·계좌번호·토큰 없음 |
| A11 | 통과 | 보호 시험 삭제·약화 없음. 매개변수 판정은 직전보다 좁아졌다(일반 메서드의 `Class<?>[]`·`Class<List<..>>` 같은 모양도 이제 금지, `:432-435`). 단언 3개와 허용 단언 1개가 늘었다. 운영 코드 전체가 넓어진 규칙을 통과했으므로(빌드) 운영 코드 오탐도 없다 |
| A12 | 통과 | 장애 대응표 17종, O1~O8, 장애 테스트 8종의 전제를 바꾸는 동작 변경이 없다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음 |
| A14 | 통과 | ADR-002 9.5 6항(`:239`), D-086 결정 1번(`:23`)·위험과 한계(`:47`), `classAsData` 주석(`RealPathReflectionTest.java:410-412`)이 규칙의 실제 동작(허용 모양 하나, 나머지 서명의 `Class` 금지)과 맞는다. 남은 한계(허용 목록 타입 안의 모르는 객체 접근 API, 설치본 jar·JVM 옵션)는 D-086:44-50에 `[추론]`과 함께 그대로 적혀 있다 |

### 점검표 밖 발견
- 차단 발견 없음.
- R6(권고, 차단 아님): 허용 모양 `Class<T>` 매개변수가 안전한 근거는 "Jackson이 주석 없는 일반 메서드를 생성기로 쓰지 않는다"는 Jackson 동작이다(T3로 2.21.4에서 확인). 그런데 이를 고정하는 시험은 없다. Jackson 버전을 올리는 PR에서 T3과 같은 실행 시험(주석 없는 `valueOf(Class<?>)`·`of(Class<?>)`가 이름 문자열로 불리지 않음)을 함께 넣기를 권한다. D-086:44가 이미 "Jackson 버전을 올리는 PR은 이 목록과 함께 검토한다"고 적고 있어 문서와는 어긋나지 않는다.
- 직전 R5와 같은 이유로, 7단계에서 실전 경로 Jackson에 날짜 처리(jsr310)를 넣는 PR이 오면 risk-ops는 `SecondConfirmation` 위조 경로가 이 규칙으로 막혔는지를 다시 승인 조건으로 본다(QA R1).
- 이 PR은 보호 테스트를 강화하므로 실전 관련 PR이다. 지침 10장·11장에 따라 머지 전에 사용자 확인과 독립 QA 재검토를 거쳐야 한다.

### 판정
승인 (F7·A2·A14 해소, 권고 R6. 실전 관련 PR이므로 머지는 사용자 확인과 독립 QA 재검토 뒤)
