# risk-ops 재검토 3: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험 + Windows 시험 고침
- 대상: PR #209 / 커밋 f18cb6b74d9a74a40278f2b79e74ed9f2e83afee / 병합 기준 5326d94(main 병합 커밋 4a3e4e4의 둘째 부모) 대비 diff(`git diff origin/main...HEAD`, 15개 파일: 운영 코드 `DayWriter.java`·`RecordingMeta.java`, 시험 `DayWriterRestartStreamTest`·`JournalWriterTest`·`RecordingMetaSourcesOrderTest`, D-119·D-132·결정 로그 README·`docs/qa/pending.md`·기획서 v14.58, 검토 원문 5개). origin/main은 그 뒤 566f977로 더 나갔지만 merge-base 기준 diff로 본다.
- 앞 검토: `docs/reviews/pr-209/risk-ops.md`(d167717, 승인), `risk-ops-2.md`(de8b381, 승인). 두 파일은 고치지 않는다. 그 뒤 새 커밋은 원문 커밋 221934c(QA 원문), main 병합 4a3e4e4, 시험 고침 f18cb6b.
- 점검표: A (보호 경로 `autotrade-marketdata/*`, `scripts/ci/protected-paths.txt:14`, 실전 관련 PR로 취급)
- 직접 실행한 것(별도 git worktree `/home/user/rv209-risk3`, f18cb6b 체크아웃, 저장소 작업 트리는 건드리지 않음, 끝나고 지움):
  - `./gradlew build --no-daemon` → EXIT 0, `BUILD SUCCESSFUL in 4m 3s, 138 actionable tasks: 138 executed`. 결과 XML 합계 1,875건, 실패 0·오류 0·건너뜀 0. 모듈별(tests/건너뜀/실패/오류): marketdata 161/0/0/0, risk 55/0/0/0, architecture-test 46/0/0/0, app-engine 68/0/0/0, app-gateway 55/0/0/0, sim 473/0/0/0. `DayWriterRestartStreamTest` 7개, `JournalWriterTest` 6개, `RecordingMetaSourcesOrderTest` 3개 통과(`큰_녹화도_작은_힙에서_재기동한다` 13.4초).
  - 변이 M5(새 커밋 대상): `큰_녹화도_작은_힙에서_재기동한다`에서 `pb.environment().put("CLASSPATH", …)`를 빼고 대신 클래스패스 길이를 찍음 → 그 시험 실패(`Could not find or load main class …DayWriterRestartStreamTest$Probe`, `ClassNotFoundException`). 즉 자식 JVM은 이 환경 변수로만 클래스를 찾고, Gradle 시험 프로세스 환경에 `CLASSPATH`가 원래 없다(`ENV=false`). 이때 찍힌 클래스패스는 Linux에서 2,344자·18개 항목.
  - 변이 M6(새 커밋 대상): `JournalWriterTest.crash()`에서 닫은 뒤 사본으로 되돌리는 두 줄을 지움(= 비정상 종료 대신 정상 종료) → `JournalWriterTest` 6개 모두 통과(생존). 아래 "점검표 밖 발견" N1.
  - 두 변이 모두 파일 사본으로 원복, 매번 `git status --short` 깨끗함 확인.
  - Windows 실행: 확인 못 함(Linux만). 아래 N2·N3.

## 새 커밋 확인(코드로 확인)
- 4a3e4e4(main 병합): 충돌 해결은 `docs/decisions/README.md`(D-130·D-132·D-133 행 모두 남김)와 `docs/qa/pending.md`(PR #205·#209 줄 모두 남김) 두 곳뿐(`git show 4a3e4e4 --cc -- docs`). merge-base 대비 PR diff에 main 쪽 코드(collector·sim·strategy 등)는 들어오지 않음(`git diff 5326d94...HEAD --name-only` 15개 파일). 운영 코드 `autotrade-marketdata/src/main`은 de8b381과 f18cb6b 사이 diff 0줄.
- f18cb6b(시험만 2개 파일, 운영 코드 0줄):
  - `DayWriterRestartStreamTest.java:270-273`: 자식 JVM 클래스패스를 인자 파일(`@probe.args`, UTF-8로 씀) 대신 `CLASSPATH` 환경 변수로 넘김. 시험 단언(종료 코드 0, `lastSeq=`, 재기동 뒤 순번·`unmarkedRestarts` 비어 있음·보관본 존재)은 그대로. 힙 제한 `SMALL_HEAP`·5분 시간 제한·출력 파일·강제 종료(PR #187 risk-ops N1)도 그대로.
  - `JournalWriterTest.java:45-57`: `crash()`가 flush → 그날 `events.jsonl.gz`·`meta.json` 사본 → `close()` → 사본으로 되돌림. `:86`, `:110`, `:140` 세 시험이 `first.flush()` 대신 이것을 씀. `RecordingReplayTest.java:266-273`의 `crash`, `DayWriterRestartStreamTest.java:80-93`의 `crashedDay`와 같은 방식. 지운 단언 없음.
  - 운영 경로 영향: 같은 JVM에서 같은 날 `DayWriter`를 앞 기록기를 닫지 않고 다시 여는 운영 경로는 없다. `JournalWriter.append`(`JournalWriter.java:69-74`)와 `MarketEventRecorder.loop`(`MarketEventRecorder.java:143-149`)는 날짜가 바뀔 때만 앞 날을 닫고 새로 연다. 그래서 사용자 PC의 `AccessDeniedException`(열린 핸들 위로 `Files.move`)은 시험이 만든 상황이며, 실제 비정상 종료는 프로세스가 죽어 핸들이 없다 `[추론]`.
  - 실패가 나도 실전과 무관: `DayWriter` 생성 실패는 게이트웨이 녹화 쓰기 스레드의 `catch (IOException | RuntimeException e)`(`MarketEventRecorder.java:161-162`)가 "녹화 중단"으로 받고 그 스레드만 끝난다. `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`에 `recording` 참조 0건(grep 다시 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff(15개 파일)에 RealTradingGuard·설정 로딩·Guard 시험 변경 없음. `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 운영 코드 변경은 녹화 메타 복사(`RecordingMeta.java:47-51`)와 녹화 tmp 열기·닫기(`DayWriter.java:73-84, 135-162`)뿐이며 새 커밋에서 바뀌지 않음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 무관. 시험의 순번은 녹화 줄·소스 순번 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 68개 통과 |
| A8 | 통과 | 녹화 코드는 실전 엔진·감시 프로세스에 실리지 않음(grep 0건). 녹화기 생성·재기동 실패는 게이트웨이 녹화 스레드 안에서 끝남(`MarketEventRecorder.java:161-162`). 새 커밋은 시험만 바꿈. `:autotrade-app-gateway:test` 55개 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | f18cb6b 추가 줄에 appkey·secret·token·password·account·계좌번호(대소문자 무시) 0줄. PR 전체 diff 추가 줄 일치 3줄은 모두 앞 검토 원문들의 A10 근거 문장(값 아님). 새 시험은 사용자 폴더 경로를 코드에 적지 않고 `java.class.path` 시스템 속성을 그대로 넘김 |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·장애 테스트) 삭제·비활성화·고정 값 변경 없음. 새 커밋은 시험 준비 방식만 바꾸고 단언은 그대로(지운 단언 0). 큰 녹화 시험은 환경 변수가 실제로 쓰임을 M5로 확인(빼면 실패). `JournalWriterTest` 세 시험이 비정상 종료와 정상 종료를 가르지 못함은 M6로 확인했으나, 고치기 전 판(flush만)도 같은 단언이라 약화가 아니고, 비정상 종료 고유 동작(보관본·열린 구간 닫기)은 `RecordingReplayTest`·`DayWriterRestartStreamTest`가 고정한다(N1) |
| A12 | 통과 | 대응표 #9(게이트웨이·실험실 크래시 → 재기동 후 `RECORDER_RESTART` 공백) 동작 코드 무변경. 새 커밋은 그 시험을 Windows에서도 돌 수 있게 하려는 준비 변경. 장애 테스트 8종·해외 O1~O8 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 시험은 KRX·Asia/Seoul 그대로 |
| A14 | 통과 | 운영 동작 변경이 없어 새 결정 불필요. 병합 뒤 결정 로그 표에 D-130·D-132·D-133 행이 모두 있고 D-132 본문·기획서 v14.58·D-119 후속 줄은 de8b381 때와 같으며 코드와 일치. `pending.md` PR #209 줄 내용도 diff와 일치 |

## 점검표 밖 발견
- N1(참고, 비차단): `JournalWriterTest`의 `crash()`를 정상 종료로 바꿔도(M6) 세 시험이 통과한다. 같은 날 재기동 공백(D-065)은 정상·비정상 종료 모두 남고, 이 시험들은 공백·순번만 보기 때문이다. 이름("비정상 종료")만큼 고정하지는 못하지만 이 PR 전부터 그랬고, 보관본·구간 끝 처리 같은 비정상 종료 고유 동작은 다른 시험이 맡는다. 원하면 `crash()` 뒤 `meta.json`의 `complete=false`를 단언하는 한 줄로 고정할 수 있다.
- N2(확인 요청): 사용자 PC 실패 보고의 `DayWriter.<init>(DayWriter.java:106)`은 `Files.move` 줄 번호가 main(5326d94·origin/main)과 같고, PR 헤드에서는 `DayWriter.java:117`이다. 사용자가 돌린 코드는 PR 헤드가 아니라 main(또는 d167717 이전 판)이었을 가능성이 크다 `[추론]`. 따라서 이 세 시험의 Windows 실패는 이 PR이 만든 것이 아니고 main에도 있는 것으로 보이며, f18cb6b가 Windows에서 실제로 고치는지는 아직 아무도 확인하지 않았다. 사용자 PC에서 이 브랜치(f18cb6b 이상)를 받아 `./gradlew :autotrade-marketdata:test`를 한 번 더 돌려 확인하기를 권한다.
- N3(참고, `[추론]`): `CLASSPATH` 환경 변수는 Windows 실행기가 ANSI 코드 페이지로 받는다. 시스템 로캘이 한국어(CP949)면 한글 경로가 그대로 전달되지만, 시스템 로캘이 한글을 못 담는 경우(예: 영어 로캘 + 한글 사용자 이름)에는 여전히 깨질 수 있다. 커밋 주석의 "명령 줄 길이 한도를 피한다"도 환경 변수 하나의 한도(32,767자)가 명령 줄 한도와 같아 정확하지 않다. 다만 이 모듈 시험 클래스패스는 Linux에서 2,344자·18개 항목이라 Windows Gradle 캐시 경로로 늘어도 한도와 거리가 멀다. 시험 준비 문제일 뿐 운영·실전 영향 없음.
- N4(참고): 운영 쪽에서 Windows `Files.move(tmp, events, REPLACE_EXISTING, ATOMIC_MOVE)`는 다른 프로세스(예: 실험실 리플레이, 백신)가 그날 `events.jsonl.gz`를 열고 있으면 `AccessDeniedException`으로 실패할 수 있다 `[추론]`. 그때는 원본·보관본은 남고 tmp가 남으며 녹화만 멈춘다(위 A8). 이 PR 전부터 있는 성질이고 이 PR이 바꾸지 않았다. 녹화 손실 위험이라 data-engineer 후속 과제로 남길 만하다.
- 앞 검토들의 참고 사항(기획서·README에 핸들 닫기 미기재, 이 PR 전 meta.json 순서, "메모리 pr206" 출처)과 QA 권고 Q1·R2는 그대로다.
- 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응 규칙 변경 없음. 보호 경로 변경이라 지침 10·11장대로 사용자 머지·독립 QA 대상(QA 승인은 71a6178 기준이므로 4a3e4e4·f18cb6b 반영 여부는 QA 쪽에서 판단).

## 판정
승인
