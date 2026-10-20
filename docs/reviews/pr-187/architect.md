# architect 검토: D-119 녹화기 재기동이 그날 녹화를 메모리에 올리지 않음 (PR #187)
- 대상: PR #187 / 커밋 dd0a2aa (브랜치 `claude/project-thread-m7fb2x`) / `git diff origin/main...HEAD` 전체(merge-base 4d98ded). 검토 중 브랜치에 올라온 3715cec는 `docs/reviews/pr-187/risk-ops.md` 한 파일이라 코드 판정에 영향 없음. origin/main(3134329, #185)과 `git merge-tree` 충돌 없음.
- 변경 파일: `DayWriter.java`(+50/−18), 새 `DayWriterRestartStreamTest.java`(시험 4개), D-119 결정 문서, D-117 후속 한 줄, 결정 로그 표, 기획서 v14.55.
- 점검표: B
- 직접 실행한 것(같은 작업 트리의 risk-ops와 겹치지 않게 dd0a2aa를 별도 worktree로 받아 실행):
  - `./gradlew build --offline --no-daemon` → **EXIT 0**. 테스트 결과 XML 합계 1,662개, 실패·오류·건너뜀 0. marketdata 155개(기존 151 + 새 4), `DayWriterRestartStreamTest` 4/4(10.6초), `RecordingReplayTest` 23/23, `JournalWriterTest` 6/6.
  - 변이 1: `DayWriter.java`만 main(4d98ded) 판으로 되돌리고 `DayWriterRestartStreamTest` 실행 → `큰_녹화도_작은_힙에서_재기동한다` **실패(별도 JVM 출력에 `OutOfMemoryError`)**, 나머지 3개 통과. 시험이 고치기 전 결함을 잡는다는 PR 주장을 직접 확인.
  - 변이 2: `rewriteToTmp`의 실패 시 `Files.deleteIfExists(tmp)`를 끈 판 → `앞_녹화를_읽다_실패하면_원본을_그대로_두고_tmp와_보관본을_남기지_않는다` **실패**. 정리 경로가 시험으로 고정됨을 확인.
  - 변이 뒤 worktree는 `git checkout -- .`로 원복.
- CI(운영 정보로 받은 것, 판정 근거로 쓰지 않음): 같은 head Gradle build·Secret scan·DB schema·Lab screens success.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build --offline --no-daemon` EXIT 0(별도 worktree, dd0a2aa), 1,662개 시험 실패 0 |
| B2 | 통과 | 변경은 `autotrade-marketdata` 한 모듈 안, 새 import는 JDK(`SortedSet`·`TreeSet`·`UncheckedIOException`)와 같은 패키지 `RecordedDayStream`뿐(`DayWriter.java:13,25,27,87,127,194`). 모듈 의존·ArchUnit 규칙 변경 없음, `autotrade-architecture-test` 빌드 통과 |
| B3 | 해당 없음 | `DayWriter`는 패키지 전용(`DayWriter.java:44` `final class`), 공개 API·녹화 형식 버전·스키마·설정 키·REST 변경 없음. 결정은 D-119로 기록, 결정 로그 표·기획서 변경 이력(v14.55, MD-05) 갱신 |
| B4 | 통과 | 새 코드에 시계·난수 없음(`openedAt`은 기존대로 주입, `DayWriter.java:72-79`). 종목 모음은 `TreeSet`(이름순)이라 재기동 공백 줄 순서 결정적(`DayWriter.java:86,193,232`). 다시 쓴 줄 바이트 동일성: `정상_종료_녹화를_다시_쓴_줄은_원본_줄과_바이트가_같다` 통과. sim·골든 시험 무변경, 전체 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 없음(시험의 가격은 `BigDecimal`) |
| B6 | 통과 | 새 동작마다 시험: 힙 64MB 별도 JVM 20만 줄 재기동(`큰_녹화도_작은_힙에서_재기동한다`), 읽기 실패 시 원본·tmp·보관본(`앞_녹화를_읽다_실패하면_…`), 앞 날 읽기 실패 시 공백 미인계(`앞_날을_읽다_실패하면_…`), 바이트 동일성. 변이 1·2로 시험이 결함을 잡는 것 확인. 지우거나 끈 시험 없음(diff에 삭제된 시험 파일·`@Disabled` 없음) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt`) → 지침 11장에 따라 실전 관련 PR, 사용자 머지·독립 QA 대상. 실전 주문 경로·Guard·블랙리스트 코드는 건드리지 않음(diff 범위 확인). risk-ops 원문은 `docs/reviews/pr-187/risk-ops.md`(3715cec)로 따로 있음 |
| B8 | 통과 | diff의 Java에 키·계좌·토큰 문자열 없음(grep), 로그·예외 메시지에 비밀값 없음(`DayWriter.java:116,190`은 폴더 경로만). 시험의 소스 이름 `KIWOOM-WS`는 식별자 |

## 동작 비교(고치기 전 ↔ 고친 뒤, 코드로 확인)
- `[사실]` 요약 값 동일: 고치기 전 `RecordedDay.open`도 `RecordedDayStream.read(dir, lines::add)`로 읽고 같은 요약을 낸다(`RecordedDay.java:31-33,93-95`). 그래서 `seq`·`eventCount`·`gapCount`·`lastReceivedAt`·메타는 같은 값이다(`DayWriter.java:88-95`).
- `[사실]` 순서: 고치기 전 "전부 읽기 → 보관 → tmp 쓰기 → 옮기기", 고친 뒤 "읽으며 tmp 쓰기 → 보관 → 옮기기"(`DayWriter.java:87,98-106`). 원본은 옮기기 전까지 바뀌지 않으므로 보관본 내용은 같고, 읽기 실패 시 끝 상태(원본 그대로·보관본 없음)도 같다. 추가로 tmp를 지운다(`DayWriter.java:136-143`).
- `[사실]` try-with-resources에서 `return` 뒤 `close()`가 gzip 꼬리를 쓰고, `close()` 실패도 `catch`로 와서 tmp를 지운다(`DayWriter.java:125-143`). 읽기 중 예외면 자원이 먼저 닫힌 뒤 지운다.
- `[사실]` `carryRestartFrom`: 지역 집합에 모은 뒤 읽기가 끝나야 `restartSymbols`에 넣으므로 실패 시 아무것도 넘겨받지 않는다(`DayWriter.java:193-197`). 고치기 전과 같은 끝 상태.
- `[사실]` 호출자(`MarketEventRecorder.loop`의 `RuntimeException` 처리 `MarketEventRecorder.java:161-162`, `JournalWriter.append` `JournalWriter.java:74-76`)는 무변경이며, 고치기 전에도 생성자에서 `IllegalStateException`(순번 끊김)이 올라왔으므로 예외 종류 계약이 넓어지지 않았다.

## 점검표 밖 발견(막지 않음)
1. 보관본 복사(`Files.copy`, `DayWriter.java:103`)나 원자적 옮기기(`:106`)가 실패하면 tmp가 남는다. 고치기 전에는 보관 복사 실패 시 tmp가 아직 없었으므로 이 한 경우만 끝 상태가 달라졌다. 다음 재기동이 tmp를 덮어쓰고(`Files.newOutputStream` 기본 TRUNCATE) 리플레이는 tmp를 읽지 않아 녹화 손실·오판은 없다 `[추론]`. D-119 결정 2의 "tmp만 남지 않음"은 읽기·쓰기 실패에 한정된 문장이라 거짓은 아니나, 원하면 보관·옮기기까지 같은 정리 범위에 넣는 것을 다음 손질 때 검토.
2. 다시 쓰기 중 쓰기 실패는 sink 안에서 메시지 없는 `UncheckedIOException`으로 올라가 생성자의 "녹화 파일을 열 수 없습니다: <dir>" 감싸기(`DayWriter.java:115-116`)를 거치지 않는다. 녹화기는 예외 클래스 이름만 로그에 남기므로(`MarketEventRecorder.java:162`) 운영 차이는 없지만, 진단 문맥(폴더)이 빠진다. 선택 사항.
3. D-119의 220만 줄 메모리 실측·결과 SHA-256 일치(`603f9f52…`)는 저장소 밖 측정 코드라 검토자가 재현하지 않았다. 대신 같은 결함을 20만 줄·64MB 시험과 변이 1로 확인했다. 비정상 종료 녹화의 다시 쓴 바이트가 고치기 전과 같다는 주장은 같은 `codec.encode(l.seq(), l.sourceId(), withSourceSequence(l))` 식을 쓰는 것(`DayWriter.java:129`, 고치기 전 `rewrite`와 동일)으로 `[추론]` 확인.
4. 무거운 시험은 별도 JVM과 20만 줄(무압축 약 300MB 생성)을 쓰고 이 환경에서 약 10초다. 사용자 PC(Windows)에서는 클래스패스를 인자 파일로 넘기고 역슬래시를 이스케이프하므로(`DayWriterRestartStreamTest.java:153-157`) 명령 줄 길이 한도 문제는 피한다 `[추론]`, Windows 실행은 확인 못 함(점검표 항목 아님).

## 판정
승인 (architect 코드 검토 기준. 보호 경로 변경이므로 지침 10·11장에 따라 risk-ops 원문·독립 QA 뒤 사용자 머지)
