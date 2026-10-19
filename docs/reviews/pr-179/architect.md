# architect 검토: 녹화 줄 단위 읽기를 marketdata 한 곳에, 게이트웨이 replayCheck도 줄 단위로(D-117)
- 대상: PR #179 / 커밋 2bcabe68 / main 대비 diff 전체(`git diff origin/main...HEAD`, 운영 코드 4파일·시험 2파일·문서 5파일). 검토 중 더해진 0863bda는 `docs/reviews/pr-179/risk-ops.md` 하나(검토 원문, 판정 대상 아님, 코드 무변경 `git diff 2bcabe6 HEAD --stat`로 확인)
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더에서 다른 프로세스가 같은 시각에 Gradle 빌드를 돌리고 있어(겹치면 출력 폴더 충돌), 커밋 2bcabe68을 `git worktree add --detach`로 스크래치 폴더에 따로 풀어 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(3분 13초, 138 tasks). 22개 모듈 테스트 실패·오류·건너뜀 0: sim 318, app-lab 246, marketdata 148(새 `RecordedDayStreamTest` 7개 포함), watchdog 93, toss 90, notification 86, collector 79, engine 68, risk 55, kiwoom 51, gateway 51(새 `ReplayCheckTest#회차_안에서_녹화가_바뀌면_결과_없이_실패하고_2를_돌려준다` 포함), architecture-test 46(ArchUnit·소유 표), strategy 44, config 42, experiment 40, monitor-agent 36, selection 34, domain 31, kis 30, control 23, monitor-sources 6, broker-api 1.
  - marketdata `RecordedDayStream`과 sim `RecordedDayStream`(D-115 복제본)을 `diff`로 대조: package·import·javadoc 말고 코드가 같음.
  - 옛 `RecordedDay.readLines`와 새 `RecordedDayStream.readLines`·`Counter`를 줄 단위로 대조(아래 "확인한 사실").
  - `RecordedDay.open`·`RecordedReplaySource`·`replay()` 호출처 검색, diff에서 `@Disabled`·시계·난수·`double`·비밀값 패턴 검색.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 2bcabe68 별도 worktree에서 `./gradlew build --no-daemon` BUILD SUCCESSFUL, 전 모듈 실패 0 |
| B2 | 통과 | 새 운영 클래스 `com.autotrade.marketdata.recording.RecordedDayStream`은 marketdata 소유 패키지. 의존은 기존 jackson(옛 `RecordedDay`가 쓰던 것)·domain뿐. 게이트웨이는 marketdata만 import(ReplayCheck.java:13-16), sim 의존을 들이지 않음(D-117 대안 ③을 버린 이유와 일치). ArchUnit 규칙·소유 표 무변경, architecture-test 46개 통과 |
| B3 | 통과 | 공개 계약 변경 두 가지: `RecordedReplaySource.replay()` 반환 `void`→`RecordedDayStream.Summary`(RecordedReplaySource.java:83), 정적 팩터리 `streaming(Path)`(54-57행), 새 공개 클래스 `RecordedDayStream`. ADR-003 2.4 갱신, D-117·README 표·기획서 v14.53 변경 이력 있음. `MarketDataSource` 인터페이스·녹화 형식·스키마·설정 키·REST 무변경 |
| B4 | 통과 | 새 코드에 시스템 시계·난수 없음. 순회 순서는 녹화 순번(파일 순서), 종목 집합은 `TreeSet`(ReplayCheck.java:227). 시뮬레이션 골든 시험(sim 318개) 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음(시험의 가격은 `BigDecimal`) |
| B6 | 통과 | marketdata `RecordedDayStreamTest` 7개(줄·요약이 `RecordedDay`와 같음, 표시 없는 재기동·줄 없는 구간·잘린 꼬리, 해시 불일치 시 0줄, 받는 쪽 예외 뒤 멈춤, 형식 버전 거부, 줄 단위 리플레이 소스 대 읽은 녹화일 소스, 다시 읽어 바뀐 녹화 드러냄). gateway 새 시험 1개. 기존 시험 삭제·약화 없음(diff numstat에서 시험 파일 삭제 줄 0, `@Disabled` 없음). 순번 끊김 거부는 marketdata 시험에 없고 sim `RecordedDayStreamTest#순번이_끊기거나_정상_종료_녹화의_해시가_다르면_둘_다_거부한다`가 `RecordedDay.open`(이제 marketdata `RecordedDayStream`을 거침)으로 고정한다 — 지금은 덮이지만 후속 정리 때 빠질 위험(R2) |
| B7 | risk-ops 검토 필요 | `autotrade-marketdata/*`는 보호 경로(scripts/ci/protected-paths.txt). `RecordedDay.open`은 게이트웨이 실시간 녹화기 재기동(DayWriter.java:83, 176)이 쓰는 경로라 실전 관련 PR. 브랜치에 `docs/reviews/pr-179/risk-ops.md`가 있으나 그 판정은 이 검토가 판단하지 않음 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 예외 문구는 폴더 경로만 담음(RecordedDayStream.java 74, 78, 92, 99, 187, 190행, ReplayCheck.java:251) |

## 점검표 밖 발견

### 권고(머지 막지 않음)
- **R1 (문서 주장 과장, 종료 코드)**: D-117 머리말·결정 4는 "보고서·종료 코드 바뀌지 않는다"고 하지만, 아직 쓰이는 녹화(게이트웨이가 장 마감 뒤에도 돌며 시간외 이벤트를 붙이는 그날 폴더, 또는 `--allow-during-session`)에서는 달라진다 `[추론]`. 고치기 전에는 회차마다 한 번 읽어 두 회차가 다르면 "다름"(1), 같으면 "불완전"(3)이었다. 지금은 한 회차 안 두 읽기 사이에 줄이 붙으면 `IllegalStateException` → "녹화 확인 실패"(2)다(ReplayCheck.java:249-252, 147-149: 예외는 `catch (IOException | RuntimeException e)`에서 2로). 새 동작이 더 정확하다(“다름”은 결정론 위반으로 읽힐 수 있음). D-117 "위험과 한계"에 "쓰이는 중인 녹화는 1·3 대신 2가 날 수 있다"를 한 줄 적고, 같은 문장을 리플레이 점검 절차(D-085 관련 런북)에 넣는다.
- **R2 (후속 정리 때 시험이 빠질 위험)**: marketdata 시험에는 순번 끊김 거부, 깨진 gzip 머리(정상 종료가 아니면 빈 녹화로 열림), 정상 종료 녹화의 가운데 줄 형식 오류 거부가 없다(`git grep "순번이 끊겼"` 결과는 sim 시험뿐). 지금은 sim `RecordedDayStreamTest`가 `RecordedDay.open`으로 marketdata 규칙을 고정하지만, D-117 후속은 그 시험을 "marketdata 시험이 맡으므로 정리"한다고 적었다. 정리 PR 전에(또는 이 PR에) marketdata `RecordedDayStreamTest`로 이 세 규칙을 옮기고, D-117 후속 문장에 그 조건을 적는다.
- **R3 (같은 단순 이름 두 클래스)**: 후속 정리 전까지 `com.autotrade.sim.recording.RecordedDayStream`과 `com.autotrade.marketdata.recording.RecordedDayStream`이 함께 있다. sim 코드가 IDE 자동 import로 엉뚱한 쪽을 잡을 수 있다. 정리 PR을 오래 미루지 않는다(D-117 후속에 이미 있음, 기록만).
- **R4 (결정 번호)**: D-115 다음 D-117(D-116은 병행 작업이 쓴다고 README·기획서에 적힘). 그 작업이 닫히면 빈 번호가 남는다(문제 아님, 기록만).

### 확인 못 함(점검표 밖, 판정에 쓰지 않음)
- D-117 "검증"의 메모리·시간 실측(100만 줄, 고치기 전 힙 4GB에서야 성공, 고친 뒤 128MB 37.8초, 보고서 바이트 일치)은 직접 재현하지 않았다. 문서 주장으로만 남는다. 구조상 회차마다 줄 목록을 들지 않는 것은 코드로 확인했다(ReplayCheck.java:225-254, 들고 있는 것은 종목 집합·종목별 요약·해시 상태).

### 확인한 사실(설계 판단 근거)
- 읽기 규칙 동일성: 옛 코드는 줄 문자열을 다 모은 뒤 디코드했고, 새 코드는 한 줄 미리 읽고(`next`) 앞 줄을 디코드한다(RecordedDayStream.java 175-196행). "마지막 줄"의 정의(`i == raw.size()-1` ↔ `next == null`), 비정상 종료의 잘린 마지막 줄 → `truncated=true`, 디코드 성공 시 `gz.cutShort()`, 순번 검사 순서, gzip 머리 실패 처리(161-171행 부근, 163행 `new GZIPInputStream`)가 같다. 정상 종료 녹화에서 gzip 오류가 나는 경우 옛 코드는 0줄에서, 새 코드는 앞 줄을 넘긴 뒤 같은 `UncheckedIOException`을 낸다 — 해시 검사가 먼저라 해시가 있으면 도달하지 않고, `RecordedDay.open`은 예외 때 목록을 버리므로 호출처 결과는 같다(D-117 위험 1과 일치).
- 요약 동일성: `Counter`의 `unmarkedRestarts`는 구간 첫 순번 줄을 지날 때 표시 여부를 기록한다(127-128행). 옛 `RecordedDay.unmarkedRestarts`(`lines.get(first-1)`)와 같은 줄을 본다. 두 구간이 같은 첫 순번이어도 둘 다 같은 값을 얻어 결과가 같다. `Summary` 동등 비교는 record 성분(`RecordingMeta`는 record, `Segment`도 record, `Instant` null 허용)이라 `replayed.equals(day)`(ReplayCheck.java:250)가 값 비교로 동작한다.
- 정지 확인 횟수: 첫 읽기 수집에서 `checkEvery`줄마다, 리플레이에서 `checkEvery`건마다, 시작·수집 뒤 한 번씩 — 옛 구조와 같은 자리. 기존 횟수 고정 시험(`ReplayCheckTest` 기존 전부) 통과.
- 실시간 녹화 경로: `DayWriter`는 `RecordedDay.open`을 그대로 쓴다(재기동 때 하루를 올림). 결과 값(`lastSeq`·`eventCount`·`gapCount`·`lastReceivedAt`·`lines`)은 같은 읽기 규칙으로 나오고 `RecordingReplayTest`(재기동·깨진 꼬리·이어 쓰기) 그대로 통과. 메모리 위험은 D-117 후속(data-engineer)에 남아 있음.
- 시세 게이트웨이의 주문 API 미호출(불변식 8): 바뀐 게이트웨이 파일은 `ReplayCheck`(오프라인 명령)뿐이고 증권사 플러그인 의존 없음.

## 판정
승인
