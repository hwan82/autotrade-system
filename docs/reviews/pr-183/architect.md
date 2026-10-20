# architect 검토: docs: 6단계 시작 점검표에 JAVA_HOME(Java 21) 설정 단계
- 대상: PR #183 / 커밋 9a4899341fdf4900c07436e690931d3243b61253 / `git diff origin/main...HEAD` 전체(1개 파일, `docs/runbooks/stage-6-start-checklist.md` +14줄)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon -q` → 종료 코드 0(컨테이너에서 직접 실행, 실패·건너뜀 없음)
  - `./gradlew :autotrade-app-gateway:startScripts` 후 생성된 `autotrade-app-gateway/build/scripts/autotrade-gateway.bat` 확인: 42행 `if defined JAVA_HOME goto findJavaFromJavaHome`, 44행 PATH의 `java.exe`, 58행 `%JAVA_HOME%/bin/java.exe` → 문서의 "bat은 `JAVA_HOME`이나 PATH의 Java를 쓴다"는 설명이 사실과 맞다.
  - `build-logic/src/main/kotlin/autotrade.java-conventions.gradle.kts:12-13` toolchain `JavaLanguageVersion.of(21)` → 클래스 파일 버전 65.0(Java 21)이고, 오류 문구의 `up to 61.0`은 Java 17 런타임이다. 문서의 증상 설명이 맞다.
  - CI(REST 조회, 같은 커밋): Secret scan and collector boundary 성공, DB schema 성공, Gradle build·Playwright는 조회 시점에 진행 중. 판정은 직접 실행한 빌드로 한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build` 종료 코드 0 (커밋 9a489934) |
| B2 | 해당 없음 | 코드·빌드 스크립트 변경 없음. diff는 runbook 문서 1개 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. `JAVA_HOME`은 OS 환경 변수로 시스템 설정 키가 아니다(`docs/config/config-keys.md` 대상 아님) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 동작 변경 없음(문서만). 삭제·건너뛴 테스트 없음 |
| B7 | 해당 없음 | `scripts/ci/protected-paths.txt`의 어느 경로에도 걸리지 않는다(`docs/runbooks/*`는 목록에 없음). 문서는 게이트웨이(시세 전용)와 실험실만 다루고, 6장 "실전 엔진을 켜지 않는다"는 그대로다. JAVA_HOME 변경은 실전 엔진 기동 경로·RealTradingGuard에 영향 없음 |
| B8 | 통과 | 추가 줄에 키·계좌번호·토큰 없음. 경로는 자리표시자 `"여기에 Location 경로"` |

## 점검표 밖 발견 (모두 비차단)
1. 문구 정확도: 19행 "결과가 `21`로 시작하지 않으면" — 실제 `java -version` 첫 줄은 `openjdk version "21.0.x" ...` 또는 `java version "21..."`라 글자 그대로 `21`로 시작하지 않는다. "버전 번호가 `21`로 시작하지 않으면"으로 고치면 오해가 없다.
2. `JAVA_HOME`이 비어 있으면 22행 명령은 `\bin\java`를 찾지 못한다는 오류를 낸다. bat은 이때 PATH의 `java.exe`를 쓰므로 실제 실행 Java와 확인 결과가 다를 수 있다. 다만 문서가 "21이 아니면 1장 0번"으로 보내므로 안전한 쪽으로 실패한다. 한 줄 설명("오류가 나도 1장 0번")을 더하면 좋다.
3. `[Environment]::SetEnvironmentVariable(... "User")`로 바꾼 JAVA_HOME은 `gradlew`가 Gradle 자체를 띄우는 JVM도 바꾼다. Gradle 8.14.3은 Java 21에서 동작하므로 문제는 없다 `[추론]`. `javaToolchains`에 21이 여러 개 나오면 아무것이나 되지만, Gradle 자동 내려받기 경로(`%USERPROFILE%\.gradle\jdks\...`)도 유효하다.
4. 일관성: `docs/runbooks/README.md:14` 게이트웨이 행과 2단계 녹화 절차서에는 같은 Java 21 전제가 없다. 다음 runbook 정리 때 한 곳(예: README 표 각주)에 모으면 같은 오류를 다른 절차에서 다시 겪지 않는다.

## 판정
승인
