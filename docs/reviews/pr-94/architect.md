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

## 재검토 2026-10-14
- 대상: PR #94 / head `8e861a8` / `git diff origin/main...HEAD` 전체(커밋 2개 `bd0efde`·`8e861a8`, 파일 9개: DayWriter, JournalWriter, JournalWriterTest, LiveSession, LiveSessionTest, ADR-010, qa-out-status, 검토 원문 2개). 이번 커밋 `8e861a8`의 코드 변경은 `LiveSession.restore`(LiveSession.java:190-212)와 `LiveSessionTest.crashAndResume`(인자 `emptyDayTwo` 추가, LiveSessionTest.java:186-235), ADR-010:58 한 줄.
- 점검표: B
- 직접 실행한 것:
  - `git archive 8e861a8`로 스크래치 폴더에 꺼낸 사본에서 `./gradlew build --no-daemon`: **BUILD SUCCESSFUL**(3분 4초, 130 task). 테스트 결과 XML 합계 1060건, 실패 0·오류 0·건너뜀 0. `ArchitectureTest` 18, `ModuleOwnershipTest` 13, `LiveSessionTest` 10, `JournalWriterTest` 6 모두 통과.
  - 변이 시험(사본에서만, 되돌림): `LiveSession.java:210`을 수정 전처럼 마지막 기록일(`days.get(days.size() - 1)`)로 되돌리고 `:autotrade-sim:test --tests '*LiveSessionTest'` → `crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame` 실패(10건 중 1건, 공백 증가 단언 LiveSessionTest.java:235). 새 `emptyDayTwo=true` 경우가 R1 수정을 실제로 판별한다.
  - 실제 상태 재현(사본에서만, 되돌림): PR 시험은 2일차에 0바이트 `events.jsonl.gz`만 둔다. 실제로 남는 상태는 `DayWriter` 생성자가 쓴 gzip 머리(10바이트)와 `meta.json`(complete=false, 끝 없는 구간)이다. 시험 안에서 `JournalWriter`로 `resumeAfter(10-14)` 뒤 2일차 첫 이벤트를 `append`하고 flush·close 없이 버려 그 상태를 만들었다(2일차 폴더: `meta.json`, `events.jsonl.gz` 10바이트, `RecordedDay` 읽힌 줄 0). 이 상태에서도 수정된 코드는 통과(공백 증가, `LiveReplayCheck` 같음), 변이(마지막 기록일 사용)는 같은 단언에서 실패. 즉 0바이트 픽스처가 실제 경우를 대표한다.
  - 저장소 작업 폴더는 이 파일 외에 바꾸지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 사본(`8e861a8`) `./gradlew build` BUILD SUCCESSFUL, 1060건 실패·오류·건너뜀 0 |
| B2 | 통과 | 이번 커밋은 `autotrade-sim` 안의 기존 호출 인자만 바꿈(LiveSession.java:190-212). 새 의존·패키지 없음. `verifyPureDependencies`, `ArchitectureTest` 18, `ModuleOwnershipTest` 13 통과 |
| B3 | 통과 | 공개 계약은 1차와 같음(`JournalWriter.resumeAfter(LocalDate)`, JournalWriter.java:96). 이번 커밋은 계약 변경 없이 호출 쪽 기준 날만 바꿨고 ADR-010:58에 "읽을 줄이 있는 마지막 기록일" 규칙을 같은 PR에서 적음. 스키마·설정 키·REST 변경 없음 |
| B4 | 통과 | 기준 날 선택은 `journalDays()`의 날짜 오름차순 순회(LiveSession.java:214-224)와 각 날 읽힌 줄 수로만 정해짐. 시스템 시계·난수 없음. 공백 줄은 기록에 먼저 쓰이고 같은 줄을 계좌에 주므로 실시간 = 리플레이 유지(`crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame` 3경우 모두 `LiveReplayCheck.same()` 참) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 기존 `crashAndResume` 두 호출은 `emptyDayTwo=false`로 그대로 유지(LiveSessionTest.java:176, 187-188), 새 경우 1개 추가(:190). 지우거나 약화한 단언 없음. 변이 시험으로 판별력 확인, 실제 상태(gzip 머리+meta) 재현에서도 통과 |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응, 증권사 주문 연동 변경 없음(broker·risk·app-engine·architecture-test 경로 diff 없음). 바뀐 코드는 실험실 실시간 모의 세션(`autotrade-sim`)뿐이고 엔진은 `autotrade-sim` 의존 금지 |
| B8 | 통과 | diff에 비밀값·계좌번호·토큰 없음. 새 로그·예외 문자열 없음 |

### 이전 권고 처리
- **R1 (빈 새 날 파일이면 공백 빠짐)**: 해소. `restore`가 읽힌 줄이 1개 이상인 마지막 날을 `resumeAfter`에 준다(LiveSession.java:194-196, 209-211). 빈 2일차가 남아도 `JournalWriter`의 `date.isAfter(resumedAfter) && day.lastSeq()==0` 조건(JournalWriter.java:75)으로 `carryRestartFrom(1일차)`가 돈다. 시험·변이·실제 상태 재현으로 확인.
- **R2 (앞 날 기록 두 번 읽기)**: 그대로. 권고 유지(6단계 주간 점검의 되살리기 시간으로 판단).

### 점검표 밖 발견
- **R3 (참고, 막지 않음) 모든 기록일이 비어 있으면 `resumeAfter`를 부르지 않는다**(LiveSession.java:209). 이때 이어 쓰기는 기존 같은 날 경로이고, 앞서 본 종목이 없으므로 남길 공백도 없다. 동작은 맞으나 이 분기를 직접 거치는 시험은 없다[사실: LiveSessionTest에 해당 경우 없음]. 영향이 없어 추가는 선택.
- **R4 (참고) 빈 2일차의 meta에 끝 없는 구간이 남는다.** 실제 상태 재현에서 2일차 `meta.json`은 앞 프로세스 구간의 `endedAt`이 비어 있고, 다음 기동이 열 때 `closeOpenSegments`는 마지막 수신 시각이 없어 그 구간을 닫지 못한다(DayWriter.java:89, 135-141). 같은 폴더에 `events.jsonl.gz.before-segment-2` 보관본도 생긴다[추론: meta complete=false 경로, DayWriter.java:94-99. 파일 생성 자체는 확인하지 않음]. 리플레이와 공백 판정에는 영향 없음(재현 시험에서 `LiveReplayCheck` 같음). 녹화 점검 도구가 "끝 없는 구간"을 이상으로 셀 경우에만 의미가 있다[추론].
- ADR-010 6장 `LiveSessionTest` 설명(ADR-010:119)은 빈 새 날 경우를 따로 적지 않지만 2장 규칙(ADR-010:58)에 있고 시험 수(10)는 맞다. 고칠 필요 없음.

## 판정
승인
