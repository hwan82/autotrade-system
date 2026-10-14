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
