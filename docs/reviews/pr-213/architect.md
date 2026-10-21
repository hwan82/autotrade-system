# architect 검토: 실험실 진행 스트림 끊김 오류 정리 + bootRun 콘솔 UTF-8
- 대상: PR #213 / 커밋 fe14b6a1 (a108e3ba, fe14b6a1) / `git diff origin/main...HEAD` 전체(3개 파일, +48)
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon` → BUILD SUCCESSFUL(138 tasks, 종료 코드 0). 새 테스트 `ApiExceptionHandlerTest` tests=1 skipped=0 failures=0 errors=0(autotrade-app-lab/build/test-results/test/TEST-com.autotrade.app.lab.web.ApiExceptionHandlerTest.xml)

## 변경 요약(코드로 확인)
1. `autotrade-app-lab/src/main/java/com/autotrade/app/lab/web/ApiExceptionHandler.java:58-62` — `AsyncRequestNotUsableException` 전용 `@ExceptionHandler`(void 반환, debug 로그에 예외 클래스 이름만). 이전에는 `unexpected`(같은 파일 :64-74)가 ERROR 로그를 남기고 `text/event-stream` 응답에 JSON `ErrorView`를 쓰려다 두 번째 경고를 냈다.
2. `autotrade-app-lab/build.gradle.kts:47-48` — 실험실 `bootRun`에 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`.
3. `autotrade-app-lab/src/test/java/com/autotrade/app/lab/web/ApiExceptionHandlerTest.java` — MockMvc standalone으로 SSE 컨트롤러가 이 예외를 던질 때 처리됨(resolvedException)과 빈 본문을 확인.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build --no-daemon` 직접 실행, BUILD SUCCESSFUL, EXIT=0(커밋 fe14b6a1) |
| B2 | 통과 | 변경은 `autotrade-app-lab`(Spring 앱 모듈) 안에서만. 새 의존은 이미 쓰는 spring-web의 `org.springframework.web.context.request.async.AsyncRequestNotUsableException`(Spring Boot 3.5.16, gradle/libs.versions.toml:3)뿐. 순수 모듈·ArchUnit 규칙·`ModuleOwnershipTest.OWNERS` 변경 없음. 빌드의 verifyPureDependencies·architecture-test 통과 |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키 변경 없음. REST API 측면: 오류 본문 계약 `{code,message}`(ApiExceptionHandler.java:14-16)은 그대로이고, 받는 쪽이 이미 끊긴 경우에만 본문을 쓰지 않는다. 클라이언트가 볼 수 있는 응답이 없으므로 계약 변경 아님 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음(시계·난수·골든 테스트 무관). 빌드에서 sim·strategy 테스트 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작에 `ApiExceptionHandlerTest#화면이_스트림을_먼저_닫으면_응답_없이_조용히_끝낸다` 추가, 통과. 삭제·@Disabled된 테스트 없음(diff에 테스트 삭제 없음). 한계는 아래 발견 1 |
| B7 | 해당 없음 | 실전 경로·보호 경로를 건드리지 않음. 바뀐 3개 파일 모두 `scripts/ci/protected-paths.txt`의 어느 패턴에도 맞지 않음(autotrade-app-lab은 목록에 없음). RealTradingGuard·블랙리스트·주문 경로 무관 |
| B8 | 통과 | diff에 키·계좌·토큰 문자열 없음(grep key/secret/token/password/account 결과 없음). 새 로그는 `e.getClass().getSimpleName()`만 남겨 예외 메시지(입력값이 섞일 수 있음)를 로그에 내보내지 않는 기존 원칙(ApiExceptionHandler.java:14-16, :70-71)과 일치 |

## 점검표 밖 발견
1. (참고, 차단 아님) 테스트는 동기 경로(컨트롤러가 직접 던짐)만 확인한다. 실제 증상 경로는 `ExperimentJobStream.send`(autotrade-app-lab/.../experiment/ExperimentJobStream.java:166-174)와 `MonitorService.send`(.../monitor/MonitorService.java:141-148)에서 `emitter.send`가 `IOException`(Spring 6.1+에서는 `AsyncRequestNotUsableException`으로 감쌈)을 받아 `completeWithError(e)` → async dispatch → 예외 해석기로 가는 흐름이다. 같은 `ExceptionHandlerExceptionResolver`를 타므로 처리 대상은 같다고 [추론]하지만, async dispatch 경로를 직접 재현한 테스트는 없다. 후속으로 `asyncDispatch`를 쓰는 MockMvc 테스트를 두면 회귀 방지가 더 확실하다.
2. (참고) `AsyncRequestNotUsableException`이 아닌 일반 `IOException`(예: 래핑되지 않은 Broken pipe)은 여전히 `unexpected`로 가서 ERROR 로그가 남는다. Spring 6.1+의 서블릿 비동기 응답 래퍼가 쓰기 실패를 이 예외로 바꾸므로 현재 증상은 막힌다고 [추론]. 사용자 PC에서 다른 형태의 경고가 계속 보이면 그때 넓힌다.
3. (확인) bootRun UTF-8 인자는 기존 `autotrade-app-gateway/build.gradle.kts:68`, `autotrade-app-experiment/build.gradle.kts`(55 외 10곳)와 같은 방식이다. 실험실 실행에만 영향, 실전 엔진(`autotrade-app-engine`) 실행 설정은 바뀌지 않음.
4. void 반환 핸들러는 응답 상태를 바꾸지 않고 요청을 처리됨으로 끝낸다. 받는 쪽이 이미 떠난 상황이라 상태 코드가 의미 없고, SSE 응답에 JSON을 쓰려던 두 번째 경고(HttpMediaTypeNotAcceptable 계열)가 생기지 않는다. 테스트에서 빈 본문으로 확인.

## 판정
승인
