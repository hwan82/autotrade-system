# architect 검토: 실시간 모의 세션 날짜를 넘긴 재기동 공백(QA PR #91 R1)
- 대상: PR #94 / 커밋 `bd0efde` / `git diff origin/main...HEAD` 전체(커밋 1개, 파일 7개: DayWriter, JournalWriter, JournalWriterTest, LiveSession, LiveSessionTest, ADR-010, qa-out-status)
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더에서 `./gradlew build` 첫 실행은 컴파일 오류(같은 패키지의 `RecordingLayout`·`RecordingMeta`를 못 찾음)로 실패했다. 같은 폴더에서 다른 Gradle 프로세스 5개가 동시에 돌고 있었고(동시 검토), 파일은 모두 있었다. 빌드 폴더 경합으로 판단해 `bd0efde`를 별도 worktree로 꺼내 다시 실행했다.
  - worktree에서 `./gradlew build`: **BUILD SUCCESSFUL**(4분, 130 task). 테스트 결과 XML 합계 1060건, 건너뜀 0, 실패 0, 오류 0. `ArchitectureTest` 18건·`ModuleOwnershipTest` 13건 통과. `JournalWriterTest` 6건, `LiveSessionTest` 10건 통과.
  - 변이 시험: `LiveSession.java:203`의 `journal.resumeAfter(...)` 호출을 막고 `:autotrade-sim:test --tests '*LiveSessionTest'` 실행 → `crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame` 실패(10건 중 1건). 새 단언(`dataGaps` 증가)이 수정 전 동작을 실제로 잡는다. 시험 데이터 `twoDays()`에는 공백 이벤트가 없어 단언이 우연히 통과할 수 없다(LiveSessionTest.java:99-125).
  - 경계 시험(임시, 지움): 앞 프로세스가 새 날 파일을 연 뒤 첫 줄을 디스크에 내리기 전에 멈춘 경우를 `JournalWriter`로 재현 → 아래 R1.
  - worktree는 지웠고 저장소에는 이 파일만 새로 생겼다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 별도 worktree(`bd0efde`)에서 `./gradlew build` BUILD SUCCESSFUL, 테스트 1060건 실패·건너뜀 0. 공유 작업 폴더의 첫 실패는 동시 Gradle 실행 경합(소스 파일 존재 확인)이며 코드 문제가 아님 |
| B2 | 통과 | `autotrade-sim`(순수)은 기존 의존인 `autotrade-marketdata`의 공개 메서드만 새로 부름(LiveSession.java:203). 새 의존·새 패키지 없음. `ArchitectureTest`·`ModuleOwnershipTest` 통과 |
| B3 | 통과 | 새 공개 메서드 `JournalWriter.resumeAfter(LocalDate)`(JournalWriter.java:96)는 모듈 사이 계약 추가. ADR-010 2.3(재기동 줄)과 6장(검증 수)을 같은 PR에서 갱신(ADR-010:58, :117, :119). 게이트웨이 녹화기는 쓰지 않는다고 명시해 D-065와 충돌 없음. 스키마·설정 키·REST 변경 없음 |
| B4 | 통과 | 공백 시각은 앞 날 기록의 마지막 수신·구간 종료 시각(DayWriter.java:181, 기존 `lastAlive`)과 새 이벤트 `receivedAt`(DayWriter.java:195-197)에서만 나온다. 시스템 시계·난수 없음. 종목 순서는 `TreeSet`(이름순). 공백 줄이 기록에 먼저 쓰이고 같은 줄을 계좌에 주므로 실시간 = 리플레이가 유지됨(`crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame`에서 `LiveReplayCheck` 바이트 일치 통과). 골든 테스트 포함 전체 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 시험 `JournalWriterTest#resumingOnALaterDayReturnsRestartGapsFromTheLastDayBeforeTheEvent`(공백 종목·시작·끝·순번, 새 날 파일 줄, 앞 날 파일 불변), `#resumingOnTheSameDayKeepsTheSameDayRestartGapsOnly`(같은 날 중복 공백 없음, 첫 줄 뒤 호출 거부), `LiveSessionTest#crashAndResume` 공백 증가 단언(변이 시험으로 판별력 확인). 지우거나 끈 테스트 없음 |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응, 증권사 주문 연동 변경 없음. `DayWriter`는 게이트웨이 녹화기와 공유하지만 새 메서드 `carryRestartFrom`은 `JournalWriter`에서만 부른다(호출처 grep: JournalWriter.java:76 하나) |
| B8 | 통과 | diff에 비밀값·계좌번호·토큰 없음. 예외 메시지는 폴더 경로만 담음(DayWriter.java:174) |

## 점검표 밖 발견

- **R1 (권고, 막지 않음) 빈 새 날 파일이 남은 경우 공백이 빠진다.** `LiveSession.journalDays()`는 `events.jsonl.gz`가 있기만 하면 그 날을 넣고(LiveSession.java:214-216), `resumeAfter`에는 그중 마지막 날을 준다(LiveSession.java:203). 앞 프로세스가 2일차 파일을 연 뒤(DayWriter 생성자가 gzip 머리와 meta를 바로 씀) 첫 줄을 내리기 전에 멈추면 2일차 파일은 줄 0개로 남고, 다음 기동은 `resumeAfter(2일차)` → 같은 날 이어 쓰기 경로 → 앞 줄이 없어 공백 0개가 된다. 임시 시험으로 재현했다: 1일차 1줄 기록 → 2일차 이벤트 쓰고 flush 없이 버림 → `RecordedDay` 2일차 줄 0 → 새 기록기가 `resumeAfter(2026-10-15)` 뒤 10분 뒤 이벤트를 쓰면 돌려준 줄 1개(공백 없음). 실제로 이 상태가 되는 길은 좁다: 새 날 첫 이벤트를 받은 `accept` 한 번 안에서 죽거나(그 호출에서 바로 flush, LiveSession.java:269-272), 새 날 파일을 연 직후 쓰기 오류(디스크 등)로 세션이 멈춘 경우다. 결정론(실시간 = 리플레이)은 깨지지 않고 공백 표시만 빠진다. 고치는 방법(한 줄 수준): `restore`에서 줄이 있는 마지막 날(`data.events().size() > 0`)을 `resumeAfter`에 준다. 그러면 빈 2일차 파일은 `lastSeq()==0`이라 `carryRestartFrom(1일차)`가 그대로 동작한다. 시험 하나(빈 새 날 파일 뒤 재개)를 함께 넣기를 권한다. ADR-010 6장의 "두 경우 모두 재기동 공백이 남음"은 지금 시험 범위에서는 맞다.
- **R2 (권고) 앞 날 기록을 한 번 더 읽는다.** `carryRestartFrom`이 `RecordedDay.open(previousDayDir)`로 앞 날 전체를 다시 읽는다(DayWriter.java:176). `restore`가 바로 앞에서 같은 날을 이미 읽었으므로(LiveSession.java:192) 하루치가 큰 날에는 되살리기 시간이 마지막 날 한 번만큼 늘어난다(QA PR #87 R6 되살리기 시간 기록과 관련). 지금 크기에서는 문제 없다고 본다[추론]. 6단계 주간 점검에서 되살리기 시간이 문제가 되면 종목 집합·마지막 생존 시각을 `restore`에서 넘기는 쪽으로 바꾼다.
- **참고**: 앞 날 파일이 비정상 종료로 끝난 경우(gzip 멤버 미종료, meta `complete=false`) 이 변경은 앞 날 파일을 다시 쓰지 않는다. 읽기 쪽(`RecordedDay`, `RecordedMarketDataSet.fromDirs`)이 이를 허용하고 리플레이 확인이 통과하는 것은 PR #87 R5 시험과 이번 시험으로 확인됐다.
- **참고**: 정상 종료 뒤 다음 날 다시 시작해도 앞 날 종목마다 공백이 남는다(앞 날 구간 종료 시각부터). 같은 날 재기동이 정상·비정상 구분 없이 공백을 남기는 D-065와 같은 규칙이라 일관적이다.

## 판정
승인
