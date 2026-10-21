# architect 재검토 3: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험·Windows 시험 고침 (재검토 2026-10-21)
- 대상: PR #209 / 커밋 f18cb6b74d9a74a40278f2b79e74ed9f2e83afee / main 대비 diff(`git diff origin/main...HEAD` 전체, 합류 기준 5326d94). 이전 재검토(`architect-2.md`, de8b381) 뒤 올라온 것: 병합 커밋 4a3e4e4(main 5326d94 병합, 충돌 해결은 `docs/decisions/README.md`·`docs/qa/pending.md` 표 줄뿐), QA 원문 221934c, f18cb6b(시험 파일 2개만: `DayWriterRestartStreamTest.java` +5 −5, `JournalWriterTest.java` +25 −3). 운영 코드(`src/main`)는 de8b381 이후 무변경(`DayWriter.java`·`RecordingMeta.java`의 main 대비 diff는 이전 검토와 같음). 검토 중 들어온 183735d(risk-ops-3 원문)는 문서만이라 판정 대상 밖.
- 점검표: B
- 직접 실행한 것(별도 `git worktree`에서 f18cb6b 체크아웃, JDK 21.0.12, 끝난 뒤 worktree 지움):
  - `./gradlew build --no-daemon` 1회차 → 종료 코드 1. 실패 1건은 이 PR이 건드리지 않은 `:autotrade-app-lab:test`의 `ExperimentJobControllerTest.진행_상태_실시간_연결은_비동기로_열리고_루프백이_아니면_거절한다`(`ConcurrentModificationException`, MockMvc `PrintingResultHandler.getResponseHeaders`에서 비동기 응답 헤더를 출력하다 경합). 나머지 모듈은 통과, marketdata 시험 결과 XML 합계 tests=161, failures=0, errors=0, skipped=0.
  - 같은 worktree에서 `./gradlew build --no-daemon` 2회차 → 종료 코드 0(BUILD SUCCESSFUL, `:autotrade-architecture-test` 포함).
  - 변이 M5: `DayWriterRestartStreamTest.java:272`의 `CLASSPATH` 환경 변수 넣기를 지움 → 7개 중 1개 실패 `큰_녹화도_작은_힙에서_재기동한다`("Could not find or load main class ...DayWriterRestartStreamTest$Probe", 종료 코드 1). 자식 JVM 클래스패스가 실제로 환경 변수로 전달됨을 확인. 원복.
  - 변이 M6: `DayWriter.java:118`·`:214`의 `restartSymbols = symbols;`를 빈 집합으로 → `JournalWriterTest` 6개 중 3개 실패. `crash()`로 바꾼 뒤에도 재기동 공백 회귀를 잡음. 원복.
  - 변이 M7: `JournalWriterTest.crash()`에서 사본 되돌리기 두 줄을 지움(= 정상 종료 파일 상태) → `JournalWriterTest` 전부 통과. 아래 발견 3 참고. 원복 후 작업 트리 깨끗함.
  - `git merge-tree --write-tree origin/main HEAD` → 현재 main(566f977, PR #212)과 `docs/qa/pending.md`에서 충돌(표 끝 줄 두 개가 서로 붙음). 코드 충돌 없음.
  - 직접 하지 못한 것: Windows 실행. 사용자 PC에서 실패한 시험 3개가 f18cb6b로 통과하는지는 이 검토에서 확인하지 못했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 2회차 종료 코드 0. 1회차 실패는 PR diff 밖 `autotrade-app-lab` 시험의 경합성 실패이고(PR은 app-lab 파일을 바꾸지 않음, `git diff --stat origin/main...HEAD`), 같은 코드로 재실행 통과. marketdata 161/161 |
| B2 | 통과 | f18cb6b는 시험 파일만 바꾸고 새 의존 없음(JDK `ProcessBuilder.environment`, `java.nio.file.Files`만). 병합 4a3e4e4는 main 코드를 그대로 가져옴. ArchUnit·소유 표 무변경, `autotrade-architecture-test` 통과 |
| B3 | 통과 | 공개 계약 변경 없음. f18cb6b가 쓰는 `JournalWriter.currentDate()`는 기존 공개 메서드(`JournalWriter.java:116`). 운영 코드·형식·설정 키 무변경. ADR 불필요 |
| B4 | 통과 | 시스템 시계·무시드 난수 추가 없음. `crash()`는 flush 직후 바이트를 떠 두고 닫은 뒤 되돌리므로(`JournalWriterTest.java:45`) 결과 파일은 이전 "닫지 않고 버림" 상태와 같은 바이트(동기 flush까지의 gzip, 트레일러 없음, `complete=false` 메타). 기존 `RecordingReplayTest.crash`(`:266`)·`DayWriterRestartStreamTest.crashedDay`와 같은 방식. 골든·바이트 시험 통과(build) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 지운·건너뛴 시험 없음. f18cb6b는 시험 3개의 준비 단계만 바꾸고 단언은 그대로. M5로 바뀐 클래스패스 전달이 실제로 쓰이는 것, M6로 바뀐 시험이 재기동 공백 회귀를 여전히 잡는 것을 확인. 이전 M1~M4 대상 시험·단언 그대로 |
| B7 | risk-ops 검토 필요 | `autotrade-marketdata/*`는 `scripts/ci/protected-paths.txt`의 보호 경로. f18cb6b는 그 경로의 시험 파일을 바꿨으므로 실전 관련 PR 취급이 이어진다. 이후 사용자 머지·독립 QA 대상 |
| B8 | 통과 | f18cb6b diff에 키·계좌·토큰·사용자 폴더 경로 없음(주석에 "한글 사용자 폴더"라는 설명만) |

## 점검표 밖 발견
1. (비차단, Windows 확인 필요) Windows에서 고쳐졌는지는 직접 확인하지 못했다. 근거로 본 것 `[추론]`: 자식 JVM이 jackson을 못 찾은 원인은 `@인자 파일`이 UTF-8로 쓰였는데 Windows 런처가 시스템 코드 페이지(한국어 Windows는 CP949)로 읽어 한글 경로가 깨진 것으로 보이고, 런처는 `CLASSPATH` 환경 변수를 같은 시스템 코드 페이지로 받으므로 한국어 코드 페이지에서는 경로가 보존된다. 시스템 코드 페이지로 표현되지 않는 문자가 사용자 폴더에 있으면 `-cp`·`CLASSPATH`·인자 파일 모두 깨지므로 남은 위험이다. 머지 전에 사용자 PC에서 `./gradlew :autotrade-marketdata:test`를 다시 돌려 3개 통과를 확인해야 한다.
2. (비차단, 주석 정확성) `DayWriterRestartStreamTest.java:269` 주석 "명령 줄 길이 한도를 피하고"는 정확하지 않다 `[추론]`. Windows에서 환경 변수 하나의 최대 길이(32,767자)는 `CreateProcess` 명령 줄 한도와 같아, 인자 파일과 달리 길이 여유를 더 주지 않는다. 지금 marketdata 시험 클래스패스는 의존 항목 40개 안팎이라 한도에 한참 못 미치므로 실패 위험은 낮다. 다음 marketdata PR에서 주석을 "인코딩 문제를 피한다"로 고치길 권한다.
3. (비차단, 시험 의미) M7 결과대로 `JournalWriterTest`의 같은 날·다음 날 재기동 시험 3개는 앞 기록기가 비정상 종료했는지 정상 종료했는지와 상관없이 같은 단언으로 통과한다. 즉 이 시험들이 지키는 것은 재기동 공백(D-065, QA PR #91 R1)이지 비정상 종료 파일 복구가 아니다. 비정상 종료 파일(트레일러 없는 gzip·보관본)은 `RecordingReplayTest`·`DayWriterRestartStreamTest`가 지킨다. f18cb6b로 약해진 것은 없다(바꾸기 전에도 같은 성질).
4. (참고, 운영 코드 무변경) 사용자 PC 실패는 같은 JVM에서 열린 핸들이 남은 채 같은 날 녹화를 다시 열면 Windows가 `Files.move(tmp, events, REPLACE_EXISTING, ATOMIC_MOVE)`(`DayWriter.java:117`)를 거부한다는 것을 보여 준다. 실제 비정상 종료는 프로세스가 죽어 핸들이 없으므로 운영 재기동에는 해당하지 않는다. 다만 실험실 한 프로세스 안에서 `LiveSession`의 기록기를 닫지 못한 채 같은 날 세션을 다시 열면 같은 예외("녹화 파일을 열 수 없습니다")로 멈춘다 `[추론]`. 조용히 틀리는 게 아니라 크게 실패하므로 이 PR의 차단 사유는 아니고, 이 PR 전부터 있던 성질이다.
5. (머지 전 처리) 현재 main(566f977)과 `docs/qa/pending.md` 표 끝 줄이 충돌한다. 두 줄 모두 남기면 되는 문서 충돌이다. 해결 커밋이 문서만 바꾸면 이 판정은 그대로 유효하다.
6. (참고) 1회차 build의 `ExperimentJobControllerTest` 경합 실패는 이 PR과 무관하지만 CI를 가끔 빨갛게 만들 수 있다. 응답 헤더 출력(`print()`)과 비동기 디스패치가 겹치는 문제로 보이며 `[추론]`, app-lab 담당 후속으로 남긴다.
7. (참고) caller 지시대로 앞 원문(`architect.md`, `architect-2.md`)은 고치지 않고 새 파일로 남겼다.

## 판정
승인 (단, B7: 보호 경로 시험 파일 변경이라 risk-ops 검토·사용자 머지·독립 QA를 거쳐야 하며, 발견 1의 Windows 재실행 확인과 발견 5의 문서 충돌 해결은 머지 전에 한다. 이 판정만으로 Claude가 머지하지 않는다)
