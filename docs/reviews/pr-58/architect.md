# architect 검토: 녹화 재기동 공백을 끊김 표시(RECORDER_RESTART)로 남기기 (D-065, PR #57 후속 1)
- 대상: PR #58 / 커밋 35ff4cb685cb22ff490a015abaea0576e6aafef8 / base main 5b26e95a8cb6ff8f917b82a5366bf523564747bb(GitHub PR base SHA, `origin/main`·merge-base와 같음) 대비 diff 전체
- 점검표: B
- PR 본문·커밋 메시지·D-065 본문은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 커밋 1개, 11파일 +244 −13. 운영 코드 5개(`DataGap.java` enum 값 추가, `DayWriter.java`, `RecordedDay.java`, `MarketEventRecorder.java` 상수 치환, `ReplayCheck.java`), 테스트 2개, 문서 4개(ADR-003, D-065 신규, 결정 로그 색인, 2026-10-12 보고서). 스키마·설정 키·REST·빌드 스크립트 변경 0건
  - `./gradlew build --no-daemon`: 1회차는 같은 작업 폴더에서 다른 프로세스가 동시에 돌린 `./gradlew build`와 겹쳐 `:autotrade-broker-kis:test` XML 결과 파일 쓰기 실패(코드 문제 아님, 동시 실행 프로세스 확인함). 그 빌드가 끝난 뒤 `./gradlew build --no-daemon --rerun-tasks`로 모든 작업을 다시 실행: **BUILD SUCCESSFUL**(2m 9s). 테스트 결과 XML 합계 tests 895, failures 0, errors 0, skipped 0(PR #57 시점 890 + 새 테스트 5). `RecordingReplayTest` 13건, `ReplayCheckTest` 8건 모두 통과
  - 변이 확인(별도 git worktree, 확인 뒤 삭제): `DayWriter.writeRestartGaps`의 시작 시각 자르기(`restartGapStart.isAfter(resumedAt) ? resumedAt`)를 지우면 `ReplayCheckTest#같은_날_재기동한_녹화는_구간_경계에_종목마다_끊김이_세지고_표시_없음_경고가_없다`가 실패(gapCount 기대 3, 실제 1: `DataGap` 생성자가 끝<시작을 거부해 쓰기 스레드가 멈춤). 자르기는 테스트로 잡힌다(아래 O1)
  - CI(head 35ff4cb): Gradle build, DB schema, Secret scan 세 작업 모두 `failure`이지만 annotation이 "The job was not started because recent account payments have failed or your spending limit needs to be increased"로, 작업이 시작되지 않았다(코드 판정 근거 아님). 대신 같은 검사를 로컬에서 돌렸다: gitleaks v8.21.2(CI와 같은 버전, SHA-256 `5bc41815…16e3ba` 일치 확인) `gitleaks git --redact --exit-code 1 .` → 281 commits, **no leaks found**. `scripts/ci/check-python-collectors.sh` 종료 0, `scripts/ci/check-collector-keys.sh` 종료 0. DB 스키마 작업은 마이그레이션 변경이 없어 대상 없음
  - D-065 [사실] 주장 대조: `RecordedReplaySource`는 `day.lines()`만 순회하고 메타 구간을 읽지 않는다(`RecordedReplaySource.java:60,73`). sim 운영 코드에 `RecordedDay`/`RecordedReplaySource` 참조 없음(`MarketDataSet.java:15`는 주석의 후속 어댑터 언급뿐). `VirtualExchange.java:131`은 DataGap을 체결에 쓰지 않는다. 미국 주간거래는 1단계 범위 밖(`overseas-design.md:98,119`). 모두 맞다
  - `GapReason` 소비처 전수(grep, Java·TS·SQL·JSON): switch로 전 값을 다루는 곳 없음. 문자열 직렬화는 `RecordingCodec.java:64`(`name()`)와 `:94`(`valueOf`) 하나뿐이라 새 값은 왕복된다. DB·REST·UI에 원인 값 목록 없음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --no-daemon --rerun-tasks` 직접 실행 성공, 895건 실패·오류·건너뜀 0. 1회차 실패는 동시 실행 빌드와의 결과 파일 충돌 |
| B2 | 통과 | domain 변경은 enum 값 하나(`DataGap.java:47-52`), 새 의존 없음. marketdata는 domain만 새로 import(`DayWriter.java:6` `Symbol`). gateway → marketdata 방향 그대로(`ReplayCheck.java`가 `RecordingMeta.Segment` 사용). `:autotrade-architecture-test:test`, 순수 모듈 `verifyPureDependencies` 빌드 안에서 통과 |
| B3 | 통과 | 계약 변경 두 가지: ① domain `DataGap.GapReason`에 `RECORDER_RESTART` 추가, ② 녹화 파일 의미(같은 날 이어 쓸 때 새 구간 첫 줄들이 공백 표시, `segments.firstSeq`가 그 줄을 가리킴). ADR-003 2.2 원인 표(`ADR-003-market-data.md:61,69`)와 2.3 이어 쓰기 항목(`:81`)을 갱신했고 D-065와 결정 로그 색인(`docs/decisions/README.md:70`)이 있다. 형식 버전 1 유지 근거(줄 구조 같음, 더 오래된 읽기 코드 없음)는 `RecordingCodec.java:94` `valueOf` 하나뿐이라 맞다. 공개 메서드 `RecordedDay.unmarkedRestarts()`(`RecordedDay.java:146-165`)도 ADR-003 `:81`에 적혔다 |
| B4 | 통과 | 시각은 모두 이벤트의 `receivedAt`과 메타 값에서 온다(`DayWriter.java:92,145,161`). `now()`·난수 없음(diff grep). 종목 순서는 `TreeSet<Symbol>`(`DayWriter.java:63`)이고 `Symbol`은 명시 비교자로 `Comparable`(`Symbol.java:11-14,34-36`). 같은 입력이면 같은 줄이 나온다. 리플레이 순번 계약(줄 순번 = 녹화 순번, 1부터 빈틈없음)은 공백 줄도 `seq++`로 받으므로 유지(`DayWriter.java:163`, `RecordedDay.java:102-104` 검사 통과). 골든 테스트 포함 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음. 테스트 가격은 `BigDecimal` |
| B6 | 통과 | 새 동작마다 테스트: `RecordingReplayTest#같은_날_재기동하면_녹화가_멈췄던_구간을_앞_구간_종목마다_공백으로_남긴다`(종목 순서·시작·끝·순번·gapCount), `#공백이_시작보다_앞서는_시계라도_끝을_넘지_않게_자른다`(정상 종료 시각과 마지막 수신 중 늦은 쪽 선택), `#재기동_공백_표시_이전_형식_녹화는_표시_없는_구간_경계를_알려_준다`, `ReplayCheckTest` 2건. 기존 `#같은_날_재기동하면_순번을_이어_쓰고_구간을_나눈다`의 기대값 502→504 변경은 동작 변경(공백 줄 2개)에 맞춘 것이고 단언을 줄이지 않았다(`unmarkedRestarts().isEmpty()` 단언 추가). 지우거나 건너뛴 테스트 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 코드 변경 없음. 새 enum 값은 녹화 파일 쓰기(`DayWriter`, 게이트웨이 프로세스의 녹화 스레드)에서만 만들어지고 실시간 배포 경로(`MarketHub`)와 실전 엔진은 만들지도 분기하지도 않는다(grep). 보호 테스트 변경 없음. risk-ops 검토 대상 아님 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(종목코드·시각·수량·고정 해시 자리표시 `"a".repeat(64)`뿐). 로컬 gitleaks(CI와 같은 고정 버전) no leaks. 새 로그·예외 메시지 없음 |

## 점검표 밖 발견
- **O1 (관찰, 테스트 이름과 실제 검증 대상이 엇갈림)**: 시작>끝일 때 자르는 분기(`DayWriter.java:161`)는 쓰기 스레드가 `DataGap` 생성자 예외(`DataGap.java:33-35`)로 죽어 그날 녹화가 멈추는 것을 막는 중요한 분기다. 그런데 이름이 이 분기를 말하는 `RecordingReplayTest#공백이_시작보다_앞서는_시계라도_끝을_넘지_않게_자른다`는 실제로는 이 분기를 타지 않는다(고정 시계 OPEN < 마지막 수신 < 재개 시각이라 `lastAlive`가 마지막 수신을 고르는 것만 확인). 분기는 다른 모듈의 `ReplayCheckTest` 재기동 테스트가 우연히 탄다(앞 녹화 마지막 수신 15:45 > "재기동 뒤 첫 체결" 13:00:10, `ReplayCheckTest.java:56-61,153`). 변이로 잡히는 것은 확인했으므로 막지 않는다. 다음에 marketdata 쪽에 "앞 구간 마지막 수신보다 이른 재개 → 길이 0 공백, 녹화 계속" 테스트를 직접 두고, 지금 테스트 이름은 "정상 종료 시각과 마지막 수신 중 늦은 쪽을 시작으로 쓴다" 쪽으로 바꾸길 권한다. `ReplayCheckTest` 주석 "13:00:10 재기동 뒤 첫 체결"도 시계가 거꾸로 간 경우라는 점을 적으면 읽는 사람이 헷갈리지 않는다.
- **O2 (관찰, 범위 밖이라 막지 않음)**: 같은 프로세스 안에서도 날짜가 바뀐 뒤 앞 날짜 수신 시각의 이벤트가 늦게 큐에 들어오면 `MarketEventRecorder.loop`가 앞 날짜 `DayWriter`를 다시 연다(`MarketEventRecorder.java:143-150`, 이 PR 전부터 있던 동작). 이제 그때 프로세스가 멈추지 않았는데도 그날 모든 종목에 길이 0인 `RECORDER_RESTART`가 붙는다(자르기 분기로 예외는 안 남). 거래소 현지 자정에 이벤트가 오는 경우는 1단계 범위(KRX 정규장, 미국 정규장, 주간거래 제외 `overseas-design.md:98,119`)에서 없으므로 실제 영향은 없다고 본다 `[추론]`. 미국 주간거래·야간 세션을 넣을 때 녹화기 날짜 전환 규칙과 함께 다시 볼 항목으로 남긴다.
- **O3 (관찰)**: 메타 `sources`의 `RECORDER` 값이 순서에 따라 달라진다. 그날 큐 넘침 표시가 먼저 쓰이면 `write`가 `"UNKNOWN"`을 넣고(`DayWriter.java:157`), 재기동 표시가 먼저면 `"녹화기 공백 표시"`가 된다(`:170`, 둘 다 `putIfAbsent`). 다른 값은 시세 범위 코드(`KRX_REALTIME` 등)인데 이것만 한글 설명이다. 결정론은 깨지지 않지만(같은 입력 같은 결과), 보고서 출처 줄이 녹화마다 달라 보일 수 있다. 녹화기 소스는 생성 시 고정 값으로 넣는 편이 낫다.
- **O4 (관찰)**: D-065가 sim-engine에 남긴 변경 제안(어댑터가 `unmarkedRestarts()` 비어 있지 않으면 경계를 공백으로 다루거나 실험 거부, `RECORDER_RESTART`를 다른 원인과 같게 처리)은 결정 문서 안에만 있다. ADR-004 4장 후속 어댑터 항목이나 미확정 목록에 연결해 두지 않으면 어댑터 작업 때 빠질 수 있다. 이 PR을 막을 사항은 아니다.
- **O5 (관찰)**: D-065와 보고서의 "2026-10-12 녹화는 경계 1개, seq 410641"은 녹화 파일(저장소 밖)에서 온 값이라 저장소로 검증하지 못했다. 테스트(`ReplayCheckTest#재기동_공백_표시_이전_녹화는…`)는 이 값을 고정 입력으로 쓸 뿐이다.

## 판정
승인
