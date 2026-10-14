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
