# architect 검토: 실험실: 실시간 등록 검사 한 곳으로, 작업 SSE 시험용 생성자 모호성 없앰(PR #156 architect R1·R2)
- 대상: PR #159 / 커밋 822558a(822558ad3d9cb65ad84c21f6a539e8ba3454fddc) / `git diff origin/main...HEAD` 전체(3파일, +28 −5)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` 7회. 1~5회는 의존성 내려받기에서 Maven Central 429(Too Many Requests)로 실패(소스와 무관, 예: `slf4j-api-1.7.36.jar` 429). 재시도 사이에 소스는 바꾸지 않았다(작업 트리 깨끗, 커밋 822558a 그대로). 6회째 `BUILD SUCCESSFUL in 1m 43s`, 종료 코드 0.
  - 6회째에서 `:autotrade-app-lab:test`는 UP-TO-DATE였다. 같은 커밋으로 3회째 시도(13:33 UTC)에 이미 돌아 결과가 남아 있었기 때문이다. 결과 파일: `ExperimentJobStreamTest` 8건, `LiveLabServiceTest` 13건, `LiveSessionControllerTest` 4건 모두 실패·건너뜀 0. `:autotrade-architecture-test:test`는 6회째에 실제로 실행되어 통과.
  - 전 모듈 테스트 결과 합계: 1536건, 건너뜀 0, 실패 0, 오류 0.
  - 참고(근거로 쓰지 않음): 같은 head의 GitHub CI 4건(Gradle build, DB schema, Lab screens, Secret scan) 모두 success(run 39036508431).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. 6회째 `./gradlew build` 종료 코드 0, 전체 테스트 실패·건너뜀 0 |
| B2 | 통과 | 변경은 `autotrade-app-lab`(Spring 허용 모듈)의 기존 패키지 `com.autotrade.app.lab.experiment`·`.live` 안에서만. 새 import·새 의존 없음. ArchUnit·`ModuleOwnershipTest`·`RealPathReflectionTest`가 `:autotrade-architecture-test:test`로 통과 |
| B3 | 해당 없음 | 공개 계약 변화 없음. `ExperimentJobStream`의 공개 생성자 `(ExperimentJobs, Clock)`는 그대로이고(ExperimentJobStream.java:54-56), 바뀐 것은 package-private 시험용 생성자를 `private`로 숨기고 package-private 정적 팩터리 `reading`(:62)을 연 것뿐. 운영 호출 `LabWebConfiguration.java:103`은 공개 생성자를 그대로 쓴다. `LiveLabService`는 private 메서드 추출(:584-586)로 REST 응답·오류 문구 그대로(:158-161) |
| B4 | 통과 | 시계·난수 사용 변경 없음. 새 메서드 `isLiveRegistration`은 순수 문자열 판정. 골든 테스트 포함 전체 테스트 통과(B1) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | R2: 새 시험 `ExperimentJobStreamTest#작업_폴더_읽기가_null이면_공개_생성자가_바로_거절한다`(ExperimentJobStreamTest.java:101-106)가 `new ExperimentJobStream(null, clock)`이 컴파일되고 공개 생성자의 `requireNonNull(jobs, "jobs")`로 NPE("jobs")가 나는지 확인. 기존 읽기 실패 시험은 `reading` 팩터리로 바꿔 그대로 유지(:114). R1: `noneMatch(p)` → `!anyMatch(p)`는 논리적으로 같은 식이고, 두 호출 지점은 기존 시험이 덮는다 — 시작 거절 `LiveSessionControllerTest#comboRegistrationIsBadRequestNamingTheCause`, 목록 필터 `LiveLabServiceTest#listsLiveRegistrationsOnly`. 지운·끈 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응·보호 경로(`scripts/ci/protected-paths.txt`) 변경 없음. 변경 파일 3개 모두 `autotrade-app-lab` |
| B8 | 통과 | diff와 커밋 메시지에 키·토큰·계좌번호 없음. 새 예외 문구 없음. CI gitleaks success(참고) |

## 점검표 밖 발견
- 없음. `ExperimentJobStream` 클래스 안에서는 두 생성자가 모두 보이므로 클래스 내부에서 `new ExperimentJobStream(null, clock)`을 쓰면 여전히 모호하지만, 그런 호출은 없고(`reading`은 `Supplier` 타입 변수로 넘긴다) 주석(:67)도 "클래스 밖"으로 범위를 밝혀 두었다. 권고 수준에도 못 미친다.

## 판정
승인
