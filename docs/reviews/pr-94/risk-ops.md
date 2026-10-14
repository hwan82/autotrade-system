# risk-ops 검토: 실시간 모의 세션 날짜를 넘긴 재기동 공백(QA PR #91 R1)
- 대상: PR #94 / 커밋 `bd0efde` / `git diff origin/main...HEAD` 전체(커밋 1개, 파일 7개, +99 −5)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build` (커밋 `bd0efde`를 `git archive`로 따로 풀어 빌드. 작업 폴더에서 처음 돌린 빌드는 같은 폴더에서 다른 검토자의 Gradle이 동시에 돌아 `autotrade-marketdata` 컴파일이 클래스를 못 찾고 실패했다. 소스 문제가 아님을 격리 사본 빌드로 확인) → **BUILD SUCCESSFUL**, 테스트 1060개 실패 0·오류 0·건너뜀 0. 이 중 `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 31, `:autotrade-app-engine:test` 48 모두 통과, `verifyPureDependencies` 통과
  - 새 테스트 실행 확인: `JournalWriterTest#resumingOnALaterDayReturnsRestartGapsFromTheLastDayBeforeTheEvent`, `#resumingOnTheSameDayKeepsTheSameDayRestartGapsOnly`, `LiveSessionTest#crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame`(공백 증가 단언 추가) 통과
  - 격리 사본에만 넣은 탐색 시험(저장소에 반영하지 않음): 새 날 첫 줄을 쓰고 첫 flush 전에 다시 죽는 경우 → 아래 N1

## 변경 범위와 실전 경로 영향
- 바뀐 운영 코드: `autotrade-marketdata` `DayWriter.carryRestartFrom`(DayWriter.java:172), `JournalWriter.resumeAfter`(JournalWriter.java:96)와 첫 날 파일 열 때 호출(JournalWriter.java:73-77), `autotrade-sim` `LiveSession.restore`의 호출 한 줄(LiveSession.java:203).
- [사실] `JournalWriter`·`resumeAfter`·`carryRestartFrom`의 운영 코드 호출처는 `LiveSession`(autotrade-sim)뿐이다(`grep` 결과). `autotrade-sim`은 실전 엔진의 금지 의존이다(autotrade-app-engine/build.gradle.kts:13). 게이트웨이 녹화기 `MarketEventRecorder`는 `DayWriter` 생성자만 쓰고(MarketEventRecorder.java:148) 새 메서드는 부르지 않으므로 게이트웨이 녹화 동작(D-065)은 그대로다.
- [사실] 실전 엔진(`autotrade-app-engine`)·감시(`autotrade-app-watchdog`) 소스는 `marketdata.recording`을 쓰지 않는다.
- [추론] 이 PR은 실전 주문 경로, Guard, 블랙리스트, 리스크 한도, 증권사 주문 연동, 보호 테스트를 건드리지 않는다. 영향은 실험실 실시간 모의 세션의 입력 기록(장애표 #9의 실험실 열)에 한정된다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정 로딩 변경 없음(`git diff --stat origin/main...HEAD`: marketdata recording 3파일, sim live 2파일, 문서 2파일). 보호 테스트 포함 `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. ArchUnit 규칙 파일 변경 없음, `:autotrade-architecture-test:test` 31개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 코드 변경 없음(broker-* 모듈 diff 없음) |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음. 바뀐 "순번"은 입력 기록 줄 번호(녹화 순번)로 주문 멱등 키와 무관(DayWriter.java `seq`) |
| A6 | 해당 없음 | 블랙리스트 경로 변경 없음. 실시간 모의 세션의 블랙리스트 시험 `LiveSessionTest#blacklistedSymbolIsNeverBoughtInTheLiveSession` 그대로 통과 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 바뀐 코드는 실험실 프로세스 안(`LiveSession`)에서만 돈다. 엔진은 `autotrade-sim` 의존 금지(autotrade-app-engine/build.gradle.kts:13, `verifyPureDependencies` 통과). 새 동작이 추가하는 비용은 재기동 뒤 첫 이벤트에서 앞 날 기록 하루치를 한 번 읽는 것(DayWriter.java:176 `RecordedDay.open`)이며 실험실 힙·디스크 안의 일이다. 읽기 실패 시 `append`가 예외를 내고 세션이 멈춘다(LiveSession.java `accept`의 `stopReason`), 엔진·게이트웨이에는 전달되지 않는다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff 전체에 키·계좌번호·토큰 없음. 테스트 픽스처는 종목코드(005930, 000660)와 시각만 사용(JournalWriterTest.java 추가분) |
| A11 | 통과 | 보호 테스트(`ArchitectureTest`, Guard·GuardedOrderGateway, 장애 테스트) 변경 없음. 기존 테스트 삭제·약화 없음(JournalWriterTest·LiveSessionTest는 추가만). 새 동작에 시험 2개와 단언 1개 추가, 실패하는 경우로 `resumeAfter`를 첫 줄 뒤에 부르면 `IllegalStateException`(JournalWriterTest#resumingOnTheSameDayKeepsTheSameDayRestartGapsOnly) |
| A12 | 통과 | 장애표 #9 실험실 열("실험실 재기동 구간을 `RECORDER_RESTART` 공백으로 남기고 … 입력 기록으로 되살린다", broker-and-fault-design.md:168)은 날짜를 가리지 않으므로 이번 변경이 그 문장을 더 정확히 만든다. 대응표 17종·O1~O8·장애 테스트 8종(broker-and-fault-design.md:185-195)의 전제는 실전 엔진 기준이며 바뀐 것 없음 |
| A13 | 통과 | 날짜는 `JournalWriter`가 받은 `zone`(세션의 `MarketRules` 시간대, LiveSession.java:114 `setup.rules().zone()`)으로 수신 시각을 현지 날짜로 바꿔 정한다(JournalWriter.java:68). 시장 분기 추가 없음. 금액 계산 없음 |
| A14 | 통과 | ADR-010 2장 재기동 문단과 6장 검증 목록이 새 동작과 시험 수(JournalWriterTest 6)와 일치(실제 시험 6개 확인). D-065(게이트웨이 녹화 공백)는 바뀌지 않았고 ADR-010이 "게이트웨이 녹화기는 이 동작을 쓰지 않는다"고 명시. 실험실 내부 기록 동작이라 새 D 번호는 필요 없다고 판단 [추론]. qa-out-status.md에 PR #91 처리 줄 추가 |

## 점검표 밖 발견
- **N1 (실험실 데이터 정확성, 실전 영향 없음, 차단 아님)** 새 동작에 남은 좁은 구멍. 재기동한 세션이 새 날(2일차) 파일을 열고 공백+첫 이벤트를 쓴 뒤 첫 flush(최대 1초, LiveSession.java:72 `FLUSH_INTERVAL`) 전에 다시 비정상 종료하면, 2일차 폴더에 읽을 줄 0개인 `events.jsonl.gz`만 남는다. 다음 재기동에서 `journalDays()`(LiveSession.java:208)는 이 빈 2일차를 마지막 날로 보고 `resumeAfter(2일차)`를 부르므로 같은 날 이어 쓰기 경로로 가고, `DayWriter` 생성자는 앞 줄이 없어 공백을 남기지 않는다. 결과적으로 1일차 마지막 수신 ~ 2일차 재개 사이가 공백 표시 없이 빠진다(이 PR이 막으려던 QA PR #91 R1과 같은 결과).
  - 재현(격리 사본 탐색 시험): 1일차 1줄 flush → 두 번째 writer `resumeAfter(10-14)`, 2일차 append(돌려준 줄 2개) 후 flush 없이 버림 → 2일차 파일 존재·읽힌 줄 0 → 세 번째 writer `resumeAfter(10-15)`, 2일차 append → 돌려준 줄 1개(공백 없음).
  - 계좌 상태와 기록은 여전히 일치한다(되살리기는 기록만 따름). 영향은 실험 결과 해석(그 구간을 끊김으로 다루지 못함)에 한정.
  - 고칠 방향(제안): `LiveSession.restore`가 `resumeAfter`에 넘길 날을 "읽힌 줄이 1개 이상인 마지막 기록일"로 고르면 기존 `date.isAfter(resumedAfter) && lastSeq()==0` 조건으로 앞 날 공백이 남는다. 이 경우를 시험으로 추가 권장. 담당은 sim-engine, 실험 정확성 판단은 architect·strategy-researcher.
- **N2 (참고)** `carryRestartFrom`이 예외를 내면 `day`는 이미 새 날로 바뀐 뒤라 같은 프로세스의 다음 `append`는 공백 없이 쓴다. 다만 `LiveSession.accept`가 첫 예외에서 세션을 멈추고 다시 받지 않으므로(`stopReason`) 실제로는 이어 쓰지 않는다. 재시작하면 N1과 같은 상태(빈 새 날 파일)가 될 수 있어 N1 수정이 이 경우도 덮는다.
- 작업 폴더에서 두 검토자가 동시에 `./gradlew build`를 돌리면 빌드 산출물이 서로 꼬여 거짓 컴파일 실패가 난다. 검토 빌드는 격리 사본에서 돌렸다.

## 판정
승인

(근거: 실전 주문 경로·Guard·블랙리스트·장애 대응·프로세스 격리에 영향 없음을 코드 호출처와 금지 의존 검사로 확인했고, A 전 항목에 확인 못 함이 없다. N1은 실험실 입력 기록의 정확성 문제로 risk-ops 차단 사유가 아니며 후속 수정을 권고한다.)
