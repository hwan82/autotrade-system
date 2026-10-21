# architect 검토: 녹화 사전 검사 후속: ADR-014 종료 코드·참고 계좌 문구, 작업 화면 이름표
- 대상: PR #216 / 커밋 db20df1c / main 대비 diff (`git diff origin/main...claude/project-thread-ia2v6h`, 병합 기준 c4d6c9c2, 파일 3개 +48 −3)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`: 실패(build-logic Kotlin 플러그인 캐시 없음). `./gradlew build` 3회: 실패(repo.maven.apache.org `429 Too Many Requests`, 예: `kotlin-assignment-compiler-plugin-embeddable-2.0.21.jar`). 코드 원인이 아닌 컨테이너 환경 문제다. CI 결과를 근거로 쓴다.
  - GitHub CI(커밋 db20df1cbc7e, run 39539108707): `Gradle build` success, `DB schema (PostgreSQL 16)` success, `Secret scan and collector boundary` success, `Lab screens (Playwright)` success. 작업 로그 본문은 컨테이너에서 내려받지 못했다(blob 저장소 차단).
  - 새 시험의 판정 논리 재현: 같은 정규식(`const COMMAND = \{(.*?)\};`, `'?([a-z][a-z-]*)'?\s*:\s*'`)을 Python으로 `jobs.js`에 적용해 키 11개를 얻었고 `ExperimentJobStatus.COMMANDS` 11개(`ExperimentJobStatus.java:58-60`)와 빠짐없이 같다.
  - 보호 경로 대조: 바뀐 파일 3개 중 `scripts/ci/protected-paths.txt` 패턴에 걸리는 것 없음.
  - ADR 문구와 코드 대조(아래 표).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI 근거) | 컨테이너 빌드는 Maven 429로 불가(위). CI `Gradle build` success, 커밋 db20df1c. `./gradlew build`는 `:autotrade-app-lab:test`를 포함하고, 새 시험의 판정 논리는 위처럼 따로 재현해 통과 조건을 확인했다 |
| B2 | 통과 | 운영 코드 의존 변경 없음. 새 시험은 `autotrade-app-lab` 시험 패키지 `com.autotrade.app.lab.experiment`에 있고, `autotrade-sim`의 `ExperimentJobStatus`(기존 `implementation` 의존)와 `autotrade-ui` 정적 자원(기존 `testRuntimeOnly(project(":autotrade-ui"))`, `autotrade-app-lab/build.gradle.kts`)만 쓴다. 새 모듈·새 패키지 없음 |
| B3 | 통과 | 계약 변경은 ADR-014 5.4 종료 코드 2를 계약에 올린 것뿐이고 ADR 자체를 고쳤다(머리 "갱신(2026-10-21)" 줄, 182~183행). 코드는 이미 그렇게 동작한다: `PrecheckMain.java:65-68` 실패 Run·결정론 불일치 → 2, `:71` → 0, `:74` `e.exitCode()`(취소 `ExperimentCancelledException.java:17` → 3, 정지 `ExperimentStoppedException.java:17` → 4), 입력 오류 → 1. `ExperimentJobStatus.State.ofExitCode` 2 → `NEEDS_REVIEW`와 ADR 문구 일치. 런북 `docs/runbooks/recording-precheck.md:40,48`과도 일치. 3.2 문구: 기준선·대조군은 `verdicts`에 남고 참고 계좌만 빠지는 것이 `LiveJudgement.java:201-208`과 일치(PR #208 architect m1 해소) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 새 시험은 시계·난수를 쓰지 않는다 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작(이름표 한 줄)에 `ExperimentJobCommandLabelTest#모든_실행_명령에_화면_이름표가_있다`가 있고, 앞으로 `COMMANDS`에 명령을 더하고 이름표를 빠뜨리면 실패한다(PR #210 architect m2 해소). 지우거나 건너뛴 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크·게이트웨이·보호 경로 변경 없음(위 대조). risk-ops 검토 불필요 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. CI 비밀값 스캔 success |

## 점검표 밖 발견
- n1(권고, 판정 영향 없음): 새 시험의 키 정규식 `[a-z][a-z-]*`은 숫자가 든 명령 키(예: `live-02`)를 잡지 못한다. 그런 명령이 생기면 이름표가 있어도 시험이 실패하는 쪽(안전한 방향)이라 막지 않는다. 그때 `[a-z0-9-]`로 넓히면 된다.
- n2(권고, 판정 영향 없음): 시험이 `autotrade-app-lab`에서 `autotrade-ui`의 `jobs.js` 원문을 정규식으로 읽어 화면 코드 형태(`const COMMAND = {...};`)에 묶인다. 형태가 바뀌면 "COMMAND 표" 단언으로 바로 드러나므로 조용히 통과하는 위험은 없다.
- n3(확인): `jobs.js`의 이름표 사용처는 `COMMAND[j.command] || j.command`(147행) 하나이고, 다른 화면에 명령 이름표 표는 없다(`selection-edge` 검색 결과 jobs.js 14행만).

## 판정
승인
