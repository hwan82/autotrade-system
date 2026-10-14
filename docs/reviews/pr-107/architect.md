# architect 검토: 6단계 주간 점검 명령 runLiveWeeklyCheck(D-088)
- 대상: PR #107 / 커밋 24b7ffc / main 대비 diff(`git diff origin/main...HEAD`, 13개 파일 +1095 −25)
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon` 종료 코드 0. 테스트 결과 XML 합계 1,109건, 실패 0, 오류 0, 건너뜀 0(이번 실행 시각 기록). `LiveWeeklyCheckTest` 6건·`ExperimentGateTest` 19건(새 2건 포함)·`autotrade-architecture-test` 스위트 통과. diff에서 비밀값 패턴 grep 결과 없음. 삭제·수정된 기존 테스트 없음(테스트 변경은 추가만, +360줄).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, 종료 코드 0, 테스트 1,109건 실패 0 |
| B2 | 통과 | 새 의존 없음. `autotrade-sim`의 `marketdata.feed`·`marketdata.recording` 참조는 main의 `LiveSession.java:6-10`에 이미 있음. Spring·DB·증권사 SDK 참조 없음(`LiveWeeklyCheck.java:3-45`). 진입점은 `ExperimentMain.java:70` `live-weekly` 분기로 app 모듈에만. ArchUnit 스위트 통과 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST 변경 없음. 새 것은 Gradle 작업·CLI 인자·결과 파일 위치이고 D-088, ADR-010 4·5장, `config-keys.md`(폴더 기본값 문단), 절차서 3장에 반영. `LiveSetup.inPlan`/`onBoards`/`spec(..., to)`는 패키지 내부 메서드이고 `LiveSession`의 기존 판정과 동치(조건 순서만 바뀜, `LiveSetup.java:85-102` 대 main `LiveSession.java` 옛 `inPlan`) |
| B4 | 통과 | `now()` 호출 없음(grep). 대조는 파일 줄 순서 리스트로 순회, `HashMap`은 조회 전용(`counts`/`take`), 재연결은 `TreeSet` 정렬. 보고서에 실행 시각 없음, `writesTheReportUnderWeeklyAndRejectsBadInput`이 같은 입력→같은 보고서 확인. 골든 테스트 포함 전체 빌드 통과 |
| B5 | 통과 | 수익률·비용은 `BigDecimal`, 반올림 `HALF_EVEN` 명시(`LiveWeeklyCheck.java:507-514`). double 없음 |
| B6 | 통과 | 새 동작에 테스트 있음(`LiveWeeklyCheckTest` 6건, `ExperimentGateTest#주간_점검도_정지_요청과_장_시간에는_시작하지_않는다`, `#주간_점검_인자가_틀리면_입력_오류다`). 건너뛰거나 지운 테스트 없음. 단, 아래 R1·R2의 경우는 테스트가 없다 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·게이트웨이 실행 코드 변경 없음. 실험 명령(읽기 전용)과 실험실 `LiveSession` 내부 리팩터링만. D-063 장 시간 보호·정지 요청 게이트를 그대로 통과(`LiveWeeklyMain.java:73`, `LiveWeeklyCheck.java:175,206`) |
| B8 | 통과 | 로그·예외 메시지는 경로·건수·날짜만(`LiveWeeklyMain.java:77-87`, `LiveWeeklyCheck.java:184,238`). diff 비밀값 grep 없음 |

## 점검표 밖 발견

### R1 (수정 필요) 세션 범위를 "그날 기록의 첫·마지막 이벤트 수신 시각"으로 잡아, 장중 무음 정지가 "세션 밖"으로 분류되고 종료 코드 0이 된다
- [사실] `compareDay`는 실험실 공백이 아닌 기록 줄의 최소·최대 `receivedAt`을 `first`/`last`로 잡고(`LiveWeeklyCheck.java:266-283`), `missingCause`는 그 범위 밖이면 무조건 `OUTSIDE_SESSION`을 돌려준다(`:338-341`). `defect`는 `UNEXPLAINED`와 `extraUnexplained`만 본다(`:211`).
- [추론] 세션이 열린 채 전달이 공백 표시 없이 멈추면(예: 받는 쪽 스레드가 멈춤, 전달 결함으로 13시 이후 아무 줄도 안 옴), 그 뒤 녹화 줄은 모두 `last` 뒤라 "세션 밖"이 되고 판정은 "설명되지 않는 차이 없음"이다. 이 점검의 목적(D-088 2번 "MD-05가 깨졌는지")에서 가장 중요한 결함 유형을 놓친다. 날 시작 쪽도 같다.
- 고칠 방향: 입력 기록 메타의 구간(`RecordingMeta.segments`: `DayWriter.java:109` 열림 시각, `:258` 닫힘 시각)으로 세션 범위를 정하고, 구간 안인데 공백이 덮지 않는 빠짐은 `UNEXPLAINED`로 센다. 구간 밖만 "세션 밖". 열린 채 끝난 구간(`complete=false`)은 보고서에 표시. 테스트: 세션을 연 채 i=N 이후 전달을 공백 없이 끊고 마지막에 닫음 → `defect=true`. 구간으로도 못 가르는 경계(날 파일이 첫 이벤트 때 열리는 경우 등)는 D-088 "위험과 한계"에 적는다.

### R2 (수정 필요) 녹화는 있는데 입력 기록이 없는 날(실험실을 하루 통째로 안 켠 날)이 보고서와 계좌 대조에서 통째로 빠진다
- [사실] 점검 날짜 목록은 입력 기록이 있는 날로만 만든다(`LiveWeeklyCheck.java:149-154`, `:163`). 녹화 리플레이 데이터도 그 날들의 녹화만 모은다(`:174-179`, `:302`).
- [추론] 실험실이 하루 꺼진 날은 1장에 행이 없고, 3장의 "녹화 리플레이"에도 그날이 없어 "같은 날 게이트웨이 녹화 리플레이"가 아니게 된다. 빠진 이벤트가 성과에 준 영향(SIM-13, D-088 4번)을 가장 크게 만드는 경우가 가장 안 보인다. D-088 4번은 "녹화가 없는 기록일"만 다루고 반대 경우를 다루지 않는다.
- 고칠 방향: 운용 기간·점검 마지막 날까지 녹화가 있는 날도 날짜 목록에 넣고, 기록 없는 날은 "기록 없음(실험실 꺼짐)" 행으로 boards 줄 전부를 빠짐(세션 밖)으로 센다. 계좌 대조는 그날 녹화도 녹화 리플레이에 넣어 그 영향을 보이게 하든지, 아니면 대조를 하지 않고 이유를 적는다(어느 쪽인지 D-088에 적음). 테스트 1건 추가.

### 권고(머지 막지 않음)
- N1: 게이트웨이 전달 큐 넘침(`FEED`) 원인 경로는 테스트가 0건만 확인한다(`LiveWeeklyCheckTest.java` `missingEventsAreCountedByTheLabGapThatCoversThem`에서 `FeedServer.SOURCE_ID` 0 단언). 그 종목만 덮는 공백으로 빠짐이 분류되는 경우를 하나 넣으면 좋다.
- N2: `openStable`은 events 파일만 전후 해시를 본다(`:232-245`). meta가 읽는 사이 바뀌면 `complete` 칸이 어긋날 수 있다. 영향은 표시 칸뿐이라 낮음.
- N3: 기록의 `RECORDER` 재기동 공백은 종목별 줄인데 모든 종목 공백으로 다룬다(`:78`). 실험실이 통째로 멈춘 구간이라 결과는 맞지만, 주석에 근거(재기동 = 전 종목 수신 중단)를 적어 두면 좋다.
- N4: 8주 누계 리플레이에서 녹화 이벤트 전체를 메모리에 올린다(`recordedForReplay`). D-088 되돌릴 조건에 적혀 있으므로 6단계 첫 2주 실측 때 힙 사용량을 기록할 것.

## 판정
수정 필요(R1 세션 범위 판정이 무음 정지를 "세션 밖"으로 숨김, R2 기록 없는 녹화일 누락)
