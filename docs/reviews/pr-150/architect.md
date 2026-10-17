# architect 검토: 실험 ID 형식 규칙을 ExperimentId 한 곳으로 합침(PR #126 architect N2)
- 대상: PR #150 / 브랜치 `claude/project-thread-cnq7ht` / 커밋 d1cb17d / `git diff origin/main...HEAD` 전체(4파일: `ExperimentRegistrations.java`, `ExperimentRegistrationsTest.java`, `ExperimentId.java`, `ExperimentIdTest.java`, +44 −11). base origin/main 1bb2f3e
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: 1회차 실패(`:autotrade-app-lab` 설정 단계 의존 해석, Maven Central **429 Too Many Requests** `apache-27.pom`). 2회차 같은 원인 실패. 이어 3회 재시도 루프도 429(`spring-boot-starter-test-3.5.16.pom`, `:autotrade-app-lab:compileTestJava` 의존 해석)로 실패. 모두 의존 다운로드 실패이고 컴파일·시험 실패는 없었다.
  - 중간 확인: `./gradlew :autotrade-sim:test --tests com.autotrade.sim.ExperimentIdTest --configure-on-demand` 성공, `./gradlew :autotrade-sim:check --configure-on-demand` 성공.
  - `./gradlew build --continue` 재시도: **BUILD SUCCESSFUL in 1m 51s**(138 tasks). 시험 결과 XML 확인: `autotrade-architecture-test` 46건 실패 0·건너뜀 0, `autotrade-sim` 296건 실패 0·건너뜀 0(`ExperimentIdTest` 31건), `autotrade-app-lab` 242건 실패 0·건너뜀 0(`ExperimentRegistrationsTest` 27건, 새 매개변수 7건과 48자 수용 1건 포함).
  - 참고로 같은 헤드 SHA의 CI: DB schema·Lab screens(Playwright)·Secret scan 성공, Gradle build는 확인 시점에 진행 중. 판정은 로컬 실행 결과로 했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build --continue` BUILD SUCCESSFUL(앞선 실패는 모두 Maven 429 의존 다운로드, 위 기록). architecture-test 46/0 실패 |
| B2 | 통과 | 새 의존 없음. `autotrade-app-lab`은 이미 `com.autotrade.sim.ExperimentId`를 import(ExperimentRegistrations.java:11)하고 쓰던 상태, 이 PR은 `java.util.regex.Pattern` import와 중복 정규식만 지움. `autotrade-sim`(순수 Java) 변경은 예외 문구·Javadoc뿐. ArchUnit·ModuleOwnershipTest 통과. 보호 경로 해당 파일 없음 |
| B3 | 통과 | 허용 집합은 그대로다: 지운 `ExperimentRegistrations.ID`와 `ExperimentId.FORMAT`은 같은 식 `[A-Z0-9][A-Z0-9-]{0,47}`(ExperimentId.java:17, diff의 지운 줄). REST `POST /api/experiments`·`/preview`의 400 응답 문구만 바뀐다(ExperimentFolderController.java:44,55가 `e.getMessage()`를 그대로 400으로). 문구에 기대는 코드·UI·e2e 없음(저장소 검색: `형식이 아닙니다`·`48자`). 계약 변경 아님, ADR 불필요 |
| B4 | 통과 | 시계·난수·순회 순서 변경 없음. ID 수용 집합이 같아 골든·등록 파일 결과 불변. sim 296건(골든 포함) 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | `ExperimentIdTest.형식이_아니면_규칙을_적은_문구로_거절한다`(9건, 옛 코드라면 "48자 이내" 단언에서 실패하는 시험), `영문_대문자_숫자로_시작하고_48자_이내면_받는다`(48자 경계 포함 4건), `ExperimentRegistrationsTest.형식이_아닌_실험_ID는_ExperimentId_문구로_거절하고_폴더를_만들지_않는다`(7건, create·preview 둘 다 거절, 폴더 미생성), `영문_대문자_숫자_하이픈_48자_ID는_받는다`. 기존 시험 이름만 바뀐 1건(`형식이_아니면_받지_않는다` → 단언 강화), 지우거나 건너뛴 시험 없음(skipped 0) |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응·보호 경로 미변경. 실험실 실험 등록 입력 검사만 |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음. 시험 ID는 합성 문자열 |

## 점검표 밖 발견
- **N1 (권고, 비차단) 검사 전 입력을 오류 문구에 그대로 붙임**: 형식 거절 문구가 이제 `": " + value`로 거절된 원문을 담는다(ExperimentId.java:23-25). 옛 화면 경로(지운 `ExperimentRegistrations` 문구)는 값을 싣지 않았다. 형식을 통과 못 한 값이라 길이 제한이 없고(요청 본문 크기만큼), 이 문구는 `ExperimentFolderController`를 거쳐 400 본문으로 나간다. [사실] 화면은 `textContent`로만 넣어(autotrade-ui/.../js/dom.js:1) XSS는 아니고, `ApiExceptionHandler`는 `ApiException` 문구를 로그에 남기지 않는다. 다른 호출부(`WalkForwardPlan`·`CombinationPlan` 등 등록 파일 읽기, `ExperimentJobStatus`)에서도 예전부터 값이 실렸으므로 새 위험은 작다. 다음에 이 파일을 고칠 때 값 부분을 예컨대 앞 64자로 자르는 것을 권한다. `ExperimentController.id()`(ExperimentController.java:114-119)는 일반 문구로 바꿔 내보내므로 영향 없음.
- **N2 (권고, 비차단, 범위 밖 기존 코드) "형식은 이 클래스에만 둔다"는 저장소 전체로는 아직 사실이 아니다**: 새 Javadoc(ExperimentId.java:13)과 달리 더 느슨한 사전 검사 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`이 6곳에 있고, 그 중 3곳은 "128자, `.`·`_` 허용"이라는 실제 규칙과 다른 문구를 낸다: `CombinationMain.java:23,42`, `RecordingMain.java:24,39`, `WalkForwardMain.java:23,42`, `LiveCheckMain.java:22`, `LiveWeeklyMain.java:29,46`, `LiveLabService.java:60,134,398`. 화면 안내 문구도 규칙을 따로 적는다(`new-experiment.js:175`). [추론] 이 검사들은 경로 조각 안전용 1차 거름이고 등록 파일을 읽을 때 `ExperimentId`가 다시 막으므로 잘못된 폴더가 생기지는 않는다. 다만 소문자 ID는 CLI 1차 검사를 통과한 뒤 계획 파싱에서 다른 문구로 거절된다. 후속으로 CLI·LiveLabService 검사를 `ExperimentId` 생성으로 바꾸거나, Javadoc을 "형식의 원본은 이 클래스"로 낮추기를 권한다. 이 PR이 만든 문제는 아니다.
- **N3 (참고) 화면 쪽 새 시험은 회귀 고정용**: `ExperimentRegistrationsTest`의 새 거절 시험은 옛 코드 문구에도 "48자 이내"·"예: EXP-20261014-CMB01"가 들어 있어 옛 코드에서도 통과한다. 이 PR의 동작 변화(문구가 `ExperimentId`에서 옴)를 붉게 만드는 시험은 `ExperimentIdTest` 쪽 문구 단언이다. 앞으로 두 곳이 다시 갈라지는 것을 막는 고정 시험으로서는 맞다.
- PR #126 architect N2 처리 확인: 정규식 한 곳(ExperimentId.FORMAT), 검사 한 번(ExperimentRegistrations.java:147). 경로 구분자·`..` 거절은 형식에서 그대로 막히고(시험 `../X`, `A/B`), 폴더를 만들기 전 `render` 단계에서 거절된다.

## 판정
승인
