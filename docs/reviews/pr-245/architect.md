# architect 검토: ADR-016·D-143 실전 엔진 시세 전달 설계(7단계 묶음 G: D12, D11 자리, D13 선택지)
- 대상: PR #245 / 브랜치 `claude/project-thread-3fzv07` 커밋 `df8f23a6`(설계), 검토 시점 HEAD `533d36c1`(risk-ops 검토 원문 추가만) / main(`53482a56`) 대비 diff 전체
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...origin/claude/project-thread-3fzv07`: 문서 8개만(ADR-003·ADR-010 표 행, ADR-016 신규, ADR README, D-143 신규, 결정 로그 README, 7단계 준비 계획 D11·D12 행, `docs/reviews/pr-245/risk-ops.md`). `docs/` 밖 변경 없음.
  - `./gradlew build`(HEAD `533d36c1`, 코드는 main과 같음): 앞 6번은 Maven Central `429 Too Many Requests`로 의존성 내려받기 실패(환경 문제, 코드 무관), 7번째 `BUILD SUCCESSFUL in 2m 22s`, 138 tasks. 테스트 결과 XML 합계 2005건, 실패 0·오류 0·건너뜀 0.
  - ADR-016 1장 `[사실]`을 코드와 대조: `GatewayMain.java:96`(WatchList 1회 로드)·`:97`(`reservedForRealTrading`), `MarketHub.java:135-138`(`unservedHeld` ERROR 로그만), `StreamingMarketDataSource.java:454-457`(`SUBSCRIBE_REJECTED` 건수·WARNING만, 종목 미통지), `FeedServer.java:49·51·53`(받는 쪽 4·큐 50,000·30초), `:114-126`(`publish` 받는 쪽마다 `offer`), `:241-259`(`synchronized offer`, `new Item`), `autotrade-app-engine` 운영 클래스(`EngineMain`·`EngineSelfCheck`·`command`·`order`만), `autotrade-app-engine/build.gradle.kts:20`(marketdata 의존) → 모두 일치.
  - 줄 형식이 ADR-010과 같은 규칙인지: `FeedProtocol.java:21-22`(이벤트 줄은 녹화 형식, `seq`는 연결마다 1부터 빈틈 없는 전달 순번, `src`·`srcSeq`는 녹화와 같음) → ADR-016 2.3과 일치.
  - 상대 링크: ADR-016·D-143의 링크 대상 파일 모두 있음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 테스트 2005건 실패·건너뜀 0. 이 PR은 코드 변경이 없다 |
| B2 | 해당 없음 | 모듈 의존·ArchUnit 규칙 변경 없음(`autotrade-architecture-test` diff 없음). 계획된 규칙(5장 ArchUnit 행)의 범위는 아래 R2 |
| B3 | 실패(경미) | 새 계약(엔진 전달 소켓 규약, 구독 순위)은 ADR-016·D-143·ADR README·결정 로그 README에 있음. 그러나 ① ADR-003 5장 `docs/adr/ADR-003-market-data.md:169` "게이트웨이 → 실전 엔진·실험실 실시간 전달(IPC)" 행이 아직 "실전 엔진은 녹화까지만 / 7단계(엔진)에서 방식 결정"으로 남아 ADR-016을 가리키지 않음(R1) ② 줄 형식의 `rev` 의미가 연결 사이 60초 유지와 함께 쓰일 때 덜 정해짐(R3). `run/engine-feed.sock`의 `config-keys.md` 등재는 코드가 생기는 G1 PR 몫으로 본다(ADR-016 6장 3번이 새 설정 키 없음을 밝힘) |
| B4 | 해당 없음 | 시뮬레이션 코드·녹화 형식 변경 없음. 설계상 엔진 없음일 때 녹화·실험실 줄 바이트 동일 시험을 G1·G2에 넣기로 함(ADR-016 2.9, 5장 9번) |
| B5 | 해당 없음 | 금액 계산 코드 없음 |
| B6 | 해당 없음 | 문서만. 테스트 파일 변경·삭제 없음. 구현 PR별 먼저 실패하는 시험 15개가 5장에 정해져 있음 |
| B7 | risk-ops 검토 필요 | 실전 엔진의 시세 입력과 보호 시세 경로 전환 조건, 보호 테스트(ArchUnit) 추가 계획을 정함. 브랜치에 `docs/reviews/pr-245/risk-ops.md`가 있음(그 판정 내용은 이 검토에서 평가하지 않음). 사용자 머지 대상 |
| B8 | 통과 | diff 추가 줄에서 키·토큰·계좌번호 형태 검색 결과 없음. 문서에 비밀값 없음 |

## 점검표 밖 발견

**R1 (수정 필요, 문서) ADR-003 5장 IPC 행이 낡음.** `ADR-003-market-data.md:169` 행은 "실전 엔진은 녹화까지만, 7단계(엔진)에서 방식 결정 (architect, risk-ops)"로 남아 있다. 이 PR은 같은 표의 `unservedHeld` 행(`:174`)만 고쳤고, ADR-016 머리말 "닫는 것"에도 이 행이 없다. 실전 엔진 쪽 방식이 ADR-016(D-143)으로 정해졌으므로 이 행의 "지금" 또는 "남은 일" 칸에 `[ADR-016](ADR-016-real-engine-market-feed.md)` 링크를 달고, ADR-016 "닫는 것"에 이 행을 더한다(단일 원본 원칙, 지침 2장).

**R2 (수정 필요, 설계·architect 확정 항목) 패키지와 ArchUnit 범위를 받는 쪽/서버로 나눠 적는다.** 2.2는 "엔진은 이 패키지(`enginefeed`)와 `RecordingCodec`만 쓴다", 5장 ArchUnit 행은 "엔진 밖은 `enginefeed` 서버 쪽만(게이트웨이)"이라 적었다. 이대로면 엔진이 `enginefeed`의 **서버 클래스**도 쓸 수 있다(엔진이 `engine-feed.sock`을 직접 열어 게이트웨이 행세를 하거나, 서버 쪽 큐·스레드 코드가 엔진에서 실행될 수 있음). 또 `RecordingCodec`이 있는 `recording` 패키지에는 쓰기 클래스(`MarketEventRecorder`·`DayWriter`·`JournalWriter`)가 같이 있어 패키지 단위가 아니라 클래스 단위 허용이어야 한다. architect 확정안:
- `com.autotrade.marketdata.enginefeed`: 줄 형식·인사·관심·상태·생존 줄 코덱, 받는 쪽(엔진 클라이언트), 소켓 경로 도우미.
- `com.autotrade.marketdata.enginefeed.server`: 서버(큐·쓰기 스레드·관심 스냅샷). `com.autotrade.app.gateway..`와 같은 패키지 안에서만 사용.
- 규칙: ① `com.autotrade.app.engine..`은 `marketdata.feed..`·`marketdata.live..`·`marketdata.enginefeed.server..`와 `marketdata.recording..` 중 `RecordingCodec` 밖의 클래스에 의존하지 않는다 ② `marketdata.enginefeed..`(받는 쪽 포함)는 `app.engine`·`app.gateway`와 자기 패키지 밖에서 쓰지 않는다(실험실·수집기·실험 명령 금지) ③ `enginefeed.server..`는 `app.gateway`만.
- `marketdata`는 실전 경로 모듈이라(`RealPathReflectionTest.java:90·137`) 새 패키지도 리플렉션 금지·외부 jar 허용 목록 규칙을 그대로 받는다. Jackson 사용은 `RecordingCodec`·`FeedProtocol`과 같은 방식(트리 모델, 데이터 바인딩 리플렉션 없음)으로 한다고 2.3에 한 줄 적는다.
2.2·5장 ArchUnit 행을 위처럼 고친다.

**R3 (수정 필요, 프로토콜) 연결 사이에서 `rev`와 상태 줄의 대응을 정한다.** 2.3은 "`rev`는 연결마다 1부터, 줄어든 `rev`는 무시"이고 2.4는 "엔진 끊김 뒤 마지막 관심을 60초 유지"다. 두 규칙을 합치면 다음이 정해지지 않았다.
1. 재접속한 엔진의 첫 관심 `rev:1`이 유지 중인 앞 연결의 관심(예: `rev:12`)보다 작다고 무시되면 안 된다. "줄어든 `rev` 무시"는 **같은 연결 안에서만** 비교한다고 적는다.
2. 재접속 직후 게이트웨이가 앞 연결의 관심으로 상태 줄을 보내면 엔진이 그 `served`를 새 관심의 확인으로 오인할 수 있다. 2.6의 "관심 → `served` 확인 → 주문" 순서가 이 대응에 기대므로, 상태 줄의 `rev`는 **이 연결에서 적용한 관심의 `rev`**(이 연결에서 아직 관심을 받지 않았으면 0)라고 적고, 엔진은 `status.rev ≥ 그 종목을 넣어 보낸 rev`인 상태 줄만 그 종목의 구독 확인으로 쓴다고 적는다.
3. 5장 실패 테스트에 "끊김 60초 안 재접속 → 새 연결 `rev:1` 관심 적용(무시되지 않음), 첫 관심 전 상태 줄 `rev:0`" 한 줄을 더한다.

**N1 (참고, G2 구현) 허브가 만드는 공백도 엔진으로.** 2.5는 수신 콜백(`MarketHub.java:235-236`) 순서만 적었다. 허브가 배정 변경 때 남기는 공백(`MarketHub.recordGap`, `:217-219`, `SOURCE_SWITCH`·`DISCONNECTED`)도 관심 종목이면 엔진 전달에 넣어야 2.6 "공백" 상태가 동작한다. G2 시험 7·11번이 이것을 보게 한다.

**N2 (참고) 미병합 ADR-015 의존.** 2.2(D-142 C6·ADR-015 `writer.lock`), 2.3(`boot`의 부팅 번호), 2.6·6장 2번(ADR-015 B4)이 PR #239의 ADR-015를 가리키는데 main에는 아직 ADR-015 파일이 없다. 링크가 아니라 깨진 곳은 없지만, ADR-015가 바뀌면 이 세 곳을 같이 본다.

**N3 (참고) 차분 등록.** 2.4 마지막 줄의 지적은 맞다. 지금 `MarketHub.apply`(`:147-180`)는 소스별 집합이 바뀔 때마다 전체 집합으로 새 `Subscription`을 만들고 옛 것을 닫는다. 장중 P0가 자주 바뀌면 이것이 증권사 등록 요청 수와 남는 종목 수신 끊김에 직결되므로 G2에서 "남는 종목 수신 공백 0"을 시험으로 고정한다.

설계 방향(대안 A: 엔진 전용 소켓·관심 전체 교체·상태·생존 줄, 엔진 쪽 보호 폴링, 게이트웨이는 기다리지 않음)은 D-013 프로세스 분리와 ADR-010 형식 하나 원칙을 지키고, 실험실 소켓의 자리·버림 정책과 섞이지 않아 모듈 경계 면에서 타당하다. 버린 대안 B~H의 이유와 되돌릴 조건도 적혀 있다.

## 판정
수정 필요(R1 ADR-003 IPC 행 갱신, R2 `enginefeed`/`enginefeed.server` 분리와 ArchUnit 범위 클래스 단위로, R3 연결 사이 `rev`·상태 줄 대응과 시험 추가)
