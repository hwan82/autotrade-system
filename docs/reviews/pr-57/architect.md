# architect 검토: 2단계 실녹화 2026-10-12 두 번 리플레이 확인 보고서, 게이트웨이 명령 한글 출력 고정
- 대상: PR #57 / 커밋 6d867886a1588c2e809de75c210085ee033208e2 / base main 0d6b3647fac71abf623381c51011b4710e7a2997(GitHub PR base SHA, `origin/main`과 같음) 대비 diff 전체
- 점검표: B
- PR 본문·커밋 메시지는 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 2파일, +100. `autotrade-app-gateway/build.gradle.kts`(+6, 모든 `JavaExec` 작업에 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`), `experiments/stage-2-replay/2026-10-12.md`(새 보고서 94줄). Java 소스·테스트·스키마·설정 키 변경 0건
  - `./gradlew build --no-daemon`: 1·2회차는 Maven Central 429로 `:build-logic:compilePluginsBlocks` 의존성 다운로드 실패(코드 문제 아님), 3회차 **BUILD SUCCESSFUL**(4m 23s, 130 tasks). 테스트 결과 XML 합계 tests 890, failures 0, errors 0, skipped 0
  - init 스크립트로 작업별 `jvmArgs` 출력: 게이트웨이 `run`, `replayCheck`, `symbolMaster`, `kiwoomDaily` 네 개 모두 `[-Dstdout.encoding=UTF-8, -Dstderr.encoding=UTF-8]`. `application.applicationDefaultJvmArgs`는 `[]`(installDist 시작 스크립트에는 안 들어감). 실험 모듈은 `runCombinationExperiment`·`runStrategyStudy`만 같은 값
  - 동작 재현(Linux, `LANG=C LC_ALL=C`로 자식 JVM 기본 출력 인코딩을 UTF-8 아닌 값으로 만들고 인자 없이 `:autotrade-app-gateway:replayCheck`): 이 PR 빌드 스크립트 → `사용법: replayCheck <yyyy-MM-dd | 녹화일 폴더> [보고서 파일]` 정상, main 빌드 스크립트로 되돌리면 → `???: replayCheck <yyyy-MM-dd | ??? ??> [??? ??]`. 확인 뒤 작업 트리 원복(`git status` 깨끗)
  - 보고서 수치 재계산(python): 녹화 비율 84.82% / 83.88% / 88.85%, 차이 445,675 / 2,763,404 / 62,806 모두 본문과 일치. SHA-256 축약은 불일치(아래 F1)
  - 붙인 원문 형식 대조: `ReplayCheck.java:283-298`, `KiwoomDailyMain.java:128,150`이 내는 문구·열 순서와 같다
  - CI(head 6d86788): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(3회차), 890건 실패·오류·건너뜀 0. 앞 두 번의 실패는 429 다운로드 오류 |
| B2 | 통과 | 의존성·`processIsolation` 설정 변경 없음(`autotrade-app-gateway/build.gradle.kts:11-14` 그대로). 추가 블록은 JVM 인자만 바꾼다. `:autotrade-architecture-test:test` 빌드 안에서 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. Gradle 작업 이름·인자도 그대로(실행 명령 계약 불변) |
| B4 | 통과 | 시계·난수 쓰는 코드 변경 없음. `stdout.encoding`은 `System.out/err`의 문자 인코딩만 바꾸며 녹화·보고서 파일 쓰기와 리플레이 다이제스트에는 들어가지 않는다(`ReplayCheck.java:155-172`는 이벤트 값으로 SHA-256 계산). 골든 테스트 포함 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작은 빌드 스크립트 JVM 인자뿐이라 단위 테스트 대상이 아니다. 대신 위 재현으로 효과를 직접 확인했다(Windows 콘솔 자체는 이 컨테이너에서 확인 불가, 보고서 후속 2번이 사용자 PC 재확인을 남김). 지우거나 건너뛴 테스트 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 코드 변경 없음. 게이트웨이 `run`에도 인자가 붙지만 출력 인코딩뿐이고 주문 API 참조와 무관(MD-01, `build.gradle.kts:5`). risk-ops 검토 대상 아님 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(추가 줄의 URL은 공개 접속 경로 `wss://api.kiwoom.com:10000/api/websocket`뿐). 녹화 파일 자체는 커밋하지 않음. CI gitleaks success |

## 점검표 밖 발견
- **F1 (수정 필요, 보고서 사실 오류)**: 결론 첫 줄(`experiments/stage-2-replay/2026-10-12.md:7`)의 축약 `b85109ab…c4f4c4f`가 같은 파일 원문 1의 전체 값 `b85109ab5127…7c4bf4c4f`(`:67-68`)와 맞지 않는다. 끝 7자리는 `4bf4c4f`(끝 8자리 `c4bf4c4f`)다. `[사실]`로 표시한 판정 근거 값이고 QA 2단계 재판정이 이 줄을 인용할 것이므로 `b85109ab…c4bf4c4f`로 고쳐라.
- **F2 (수정 필요, 원본 대조 G-1의 독립성)**: runbook 8번(`docs/runbooks/stage-2-real-recording.md:24`)은 "처음 한 번은 몇 종목을 HTS 일봉과도 비교해 부호·단위 해석을 확인한다"고 한다. 이번이 첫 실녹화인데 보고서는 이를 하지 않았다(`2026-10-12.md:46`, `[가정]`). 그런데 결론(`:9`)은 G-1을 조건 없이 `[사실]` 일치로 적는다. 코드상 두 대조 상대가 독립이 아니다: 녹화 쪽 체결가 해석(`KiwoomStreamProtocol.java:223-224`)과 `kiwoomDaily` 일봉 해석(`KiwoomDailyChart.java:59-63`)이 같은 `KiwoomStreamProtocol.unsigned`(`:252-261`)를 쓰고, 출처도 같은 키움이다. 따라서 이 대조는 수신 누락·시각 문제는 잡지만 부호 제거·단위(자릿수) 해석 같은 공통 오류는 원리상 잡을 수 없다 `[추론]`. 둘 중 하나로 고쳐라: (a) 사용자 HTS 일봉 1~2종목 확인을 받아 보고서에 적는다, (b) 결론 G-1 줄을 "키움 일봉과 일치, HTS 부호·단위 확인은 남음"으로 낮추고 후속 과제에 넣는다. 2단계 완료 판정에 쓰는 보고서라 (a)를 권한다.
- **O1 (관찰, 막지 않음)**: 후속 1번(재기동 공백이 GAP 이벤트로 안 남고 `meta.json` `segments`에만 있음)은 리플레이 소비자 계약 문제다. 시뮬레이션·지표가 74분을 "체결 없음"으로 읽으면 결과가 조용히 바뀐다. 보고서 안의 메모로만 두지 말고 결정 로그 미확정 항목(Q-xxx)이나 data-engineer 작업으로 등록해 추적하길 권한다. 고칠 때 리플레이 계약(이벤트 흐름에 구간 경계 표시를 넣을지)이 바뀌면 ADR-003 갱신이 필요하다.
- **O2 (관찰)**: `tasks.withType<JavaExec>().configureEach`는 게이트웨이 `run`에도 적용된다(확인함). 해롭지 않다. runbook 5번이 쓰는 `installDist` 시작 스크립트에는 들어가지 않지만(`applicationDefaultJvmArgs` 비어 있음), 그 경우 자식 JVM이 콘솔에 직접 붙어 Java 21이 콘솔 코드 페이지를 쓰므로 같은 깨짐은 생기지 않는다 `[추론]`(Windows에서 확인 안 함). 실험 모듈은 작업별로, 게이트웨이는 모듈 전체로 붙여 방식이 다르다. 나중에 `process-conventions`로 모으는 것을 고려할 수 있다.
- **O3 (관찰)**: 보고서의 녹화 구간·첫 seq·GAP 시각·접속 경로는 녹화 파일·로그(저장소 밖, ADR-003)에서 온 값이라 저장소로 검증할 수 없다. 붙인 원문 두 개가 도구 출력 형식과 같고 수치 계산이 맞다는 것까지만 확인했다.
- **O4 (관찰)**: 원본 대조표의 OHLC 일치와 첫·마지막 체결 시각(09:00:03~09:00:24, 15:30:09~15:30:22)은 정규장·종가 단일가 1분 포함 구간과 맞다. "정규장 밖" 005930 18건 / 5,040주는 본문에서 설명이 없다. 시간외 단일가·장전 체결인지 한 줄 적어 두면 QA가 묻지 않는다.

## 판정
수정 필요(F1: 결론의 SHA-256 축약 `c4f4c4f`를 `c4bf4c4f`로 고침, F2: 첫 실녹화의 HTS 부호·단위 확인을 하거나 결론의 G-1 일치를 조건부로 낮추고 후속 과제로 남김). 빌드·테스트·CI 통과, 빌드 스크립트 변경은 효과를 직접 재현해 맞다. B7 해당 없음(risk-ops 검토 대상 아님).

## 재검토 2026-10-12
- 대상: PR #57 / 커밋 3ced316 / base main 0d6b3647fac71abf623381c51011b4710e7a2997 대비 diff 전체(`git diff origin/main...HEAD`: 3파일 +136, 이 검토 원문 포함)
- 점검표: B
- 직접 실행한 것:
  - `git diff d3ca602..3ced316`: `experiments/stage-2-replay/2026-10-12.md`만 +5 −3. 빌드 스크립트 diff는 첫 검토 때와 바이트 단위로 같음(main 대비 diff md5 동일 `98f3c915…`). Java·테스트·스키마·설정 변경 0건
  - `./gradlew build --no-daemon`: **BUILD SUCCESSFUL**(18s, 130 tasks 중 19 실행·111 up-to-date). 코드 입력이 첫 검토 때와 같아 테스트 작업은 up-to-date이며 그때 결과(890건, 실패·오류·건너뜀 0)가 그대로 유효
  - CI(head 3ced316, 이 시점): Secret scan success, Gradle build·DB schema 진행 중. 판정은 위 직접 빌드로 한다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공 |
| B2 | 통과 | 빌드 스크립트 변경 없음(첫 검토와 같음) |
| B3 | 해당 없음 | 계약 변경 없음 |
| B4 | 통과 | 코드 변경 없음, 골든 테스트 포함 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 커밋은 보고서 문구만. 지우거나 건너뛴 테스트 없음 |
| B7 | 해당 없음 | 실전 경로 변경 없음, risk-ops 검토 대상 아님 |
| B8 | 통과 | 추가 줄에 비밀값 없음(종목코드·시각·수량뿐). gitleaks success |

### 지난 지적 처리
- **F1 해소**: 결론 축약이 `b85109ab…c4bf4c4f`(`2026-10-12.md:7`)로 고쳐져 원문 1 전체 값(`:69-70` 부근 `…7c4bf4c4f`)과 맞다.
- **F2 해소(선택지 b)**: 결론 G-1 줄이 "조건부"로 낮아졌고 공통 해석 코드(`KiwoomStreamProtocol.unsigned`)를 근거로 적었으며(`:9`), HTS 확인이 끝나야 G-1 통과로 본다고 명시했다. 후속 과제 4번에 확인 방법(HTS 영웅문, 1~2종목 OHLC)과 해제 조건이 있다. 2단계 완료 판정은 이 조건이 풀린 뒤에야 G-1을 충족한다는 점이 QA에 분명히 보인다.
- **O4 반영**: "정규장 밖" 체결을 15:40:00~15:43:27 시간외 종가 매매로 설명했다. 시각은 녹화 파일에서 온 값이라 저장소로 검증하지 못한다(O3와 같음). 구간은 KRX 시간외 종가(15:40~16:00)와 맞고, 15:41:26~15:43:03.7 GAP·15:43:28 종료와도 모순이 없다 `[추론]`.

### 새 발견
- 없음. O1(재기동 공백의 GAP 미표시를 Q-xxx 등으로 추적)과 O2는 관찰로 그대로 남는다.

### 판정
승인
