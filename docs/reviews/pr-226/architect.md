# architect 검토: 수집기 실행 콘솔 한글 UTF-8(실험실 bootRun과 같은 설정)
- 대상: PR #226 / 커밋 ee022fd4 / main 대비 diff (`git diff origin/main...HEAD`: `autotrade-app-collector/build.gradle.kts` 1개 파일, +2줄)
- 점검표: B
- 직접 실행한 것: `./gradlew build` → 종료 코드 0(컴파일, 전체 테스트, 순수 모듈 금지 의존 검사, ArchUnit 포함 통과)

## 변경 내용 [사실]
`autotrade-app-collector/build.gradle.kts:35-36` — `tasks.named<JavaExec>("run")`에 주석 1줄과 `jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")`를 더했다. 같은 인자가 이미 `autotrade-app-lab/build.gradle.kts:48`(bootRun), `autotrade-app-gateway/build.gradle.kts:67-68`(모든 JavaExec), `autotrade-app-experiment/build.gradle.kts:55,82,109,130,158`에 있어 저장소의 기존 방식과 같다. `stdout.encoding`/`stderr.encoding`은 JDK 19+ 시스템 속성으로 Java 21 툴체인에서 유효하다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, EXIT=0 |
| B2 | 통과 | 의존성 블록·`processIsolation` 금지 목록(`autotrade-app-collector/build.gradle.kts:16-21`) 변경 없음. JVM 인자만 추가. ArchUnit·`ModuleOwnershipTest` 빌드에서 통과 |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 콘솔 출력 인코딩은 계약이 아님 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 출력 인코딩은 계산 결과에 영향 없음(파일 출력 경로는 그대로) |
| B5 | 해당 없음 | 금액 처리 코드 변경 없음 |
| B6 | 해당 없음 | 동작 코드 변경 없이 Gradle `run` 태스크의 표준 출력 인코딩만 바꾼다. 같은 설정의 기존 모듈들도 테스트 없이 빌드 스크립트로만 둔다. 테스트 삭제·건너뛰기 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응을 건드리지 않음. 변경 파일 `autotrade-app-collector/build.gradle.kts`는 `scripts/ci/protected-paths.txt`에 없음(목록의 collector 항목은 `KisNewsClient.java` 등 Java 파일뿐). 수집기는 증권사 주문 API를 쓰지 않는 단발 명령(같은 파일 4-6줄 주석, `processIsolation`에서 broker·engine 모듈 금지) |
| B8 | 통과 | 추가된 2줄에 비밀값 없음 |

## 점검표 밖 발견
- [추론, 차단 아님] 이 설정은 Gradle `run` 태스크에만 걸린다. `installDist`로 만든 시작 스크립트(`applicationDefaultJvmArgs = listOf("-Xmx2g")`, 같은 파일 13줄)로 수집기를 돌리면 UTF-8 인자가 들어가지 않는다. 런북이 `./gradlew :autotrade-app-collector:run`을 안내하는 한 문제없고, 실험실·게이트웨이도 같은 범위이므로 이 PR의 범위로는 적절하다. 배포 스크립트로 돌리는 경로가 생기면 `applicationDefaultJvmArgs`에도 넣는 것을 후속으로 검토할 수 있다.
- [확인 못 함, 판정 영향 없음] Windows PowerShell(chcp 65001) 콘솔에서 실제로 한글이 바로 찍히는지는 이 리눅스 환경에서 재현할 수 없다. 같은 인자가 실험실·게이트웨이에서 이미 쓰이고 있다는 사실로 갈음한다. 점검표 B 항목 판정에는 영향이 없다.

## 판정
승인
