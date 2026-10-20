# risk-ops 검토: D-119 녹화기 재기동이 그날 녹화를 메모리에 올리지 않음(PR #179 risk-ops N2)
- 대상: PR #187 / 커밋 dd0a2aa / main 대비 diff(`git diff origin/main...HEAD`, 6개 파일: `DayWriter.java`, `DayWriterRestartStreamTest.java`, D-117·D-119 결정 문서, 결정 로그 README, 기획서 v14.55)
- 점검표: A (보호 경로 `autotrade-marketdata/*` 변경이라 실전 관련 PR로 취급, `scripts/ci/protected-paths.txt`)
- 직접 실행한 것:
  - `./gradlew --project-cache-dir <scratchpad>/gcache --offline :autotrade-marketdata:test :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-gateway:test --continue` → BUILD SUCCESSFUL, 2026-10-20 01:40 UTC 결과 XML 기준 marketdata 155/0 실패, risk 55/0, architecture-test 46/0, app-engine 68/0, app-gateway 55/0(건너뜀 0). `DayWriterRestartStreamTest` 4개 통과(큰 녹화 시험 10.5초).
  - 전체 `./gradlew build`는 이 컨테이너에서 돌리지 않았다(Maven Central 429·동시 작업 회피). 같은 head의 GitHub CI Gradle build 성공은 메인이 준 운영 정보이며 직접 확인한 것이 아니다. 판정은 위 직접 실행 결과로 한다.
  - 변이 시험(별도 git worktree, 저장소 작업 트리는 건드리지 않음, 끝나고 지움):
    1. `DayWriter`를 main(4d98ded) 판으로 되돌림 → `큰_녹화도_작은_힙에서_재기동한다` 실패(`OutOfMemoryError: Java heap space`). D-119의 "고치기 전 코드는 이 시험에서 OOM" 주장 확인.
    2. `rewriteToTmp`의 `Files.deleteIfExists(tmp)` 무력화 → `앞_녹화를_읽다_실패하면_원본을_그대로_두고_tmp와_보관본을_남기지_않는다` 실패.
    3. `Files.move(tmp, events)`를 보관본 복사 앞으로 옮김 → 기존 `RecordingReplayTest.비정상_종료_뒤_재기동은_다시_쓰기_전_원본을_보관한다` 실패. 즉 "보관 → 교체" 순서는 기존 시험이 고정한다.

## 변경 요약(코드로 확인)
- `DayWriter` 생성자(autotrade-marketdata/.../recording/DayWriter.java:83-109): `RecordedDay.open`(전체 줄을 List에 적재) 대신 `rewriteToTmp`(:124-144)가 `RecordedDayStream.read`로 한 줄씩 읽으며 `events.jsonl.gz.tmp`에 같은 인코딩으로 쓰고 종목만 `TreeSet`에 모은다. 순서: tmp 완성 → (비정상 종료면) 원본 보관(:98-105, 이미 있으면 덮지 않음) → 원자적 교체(:106). 읽기·쓰기 실패 시 tmp 삭제 후 예외 재전파(:136-143), 원본·보관본 무변경.
- `carryRestartFrom`(:188-198): 앞 날을 줄 단위로 읽어 종목만 모으고, 다 읽은 뒤에만 `restartSymbols`에 대입(실패 시 넘겨받지 않음).
- 호출처: `MarketEventRecorder.loop`(게이트웨이 녹화 쓰기 스레드, MarketEventRecorder.java:133-176)와 `JournalWriter.append`(실험실 입력 기록, JournalWriter.java:69-77). 오류 처리·큐·flush 로직은 바뀌지 않았다.
- 실전 엔진·감시 프로세스는 녹화 패키지를 쓰지 않는다: `grep -rln "MarketEventRecorder|DayWriter|RecordedDay|RecordedDayStream"`의 운영 코드 결과는 marketdata·app-gateway·sim뿐, `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`에 `recording` 참조 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정 로딩·Guard 테스트 변경 없음(`git diff --name-only origin/main...HEAD`에 autotrade-risk·config·engine 파일 없음). `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 코드 변경 없음. ArchUnit 규칙 파일 변경 없음, `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 바뀐 코드는 녹화 파일 쓰기뿐이며 주문 API 호출 없음(DayWriter.java 전체에 broker 의존 없음) |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·주문 순번 변경 없음. 바뀐 순번은 녹화 줄 순번(`seq`)이며 주문 멱등 키와 무관(DayWriter.java:88, 210) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 주문 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 68개 통과 |
| A8 | 통과 | 바뀐 코드는 게이트웨이 녹화 쓰기 스레드와 실험실 입력 기록에서만 돈다(위 grep). 생산자 `MarketEventRecorder.offer`는 기다리지 않고 큐가 차면 버리고 RECORDER_OVERFLOW 공백(MarketEventRecorder.java:89-112, 무변경)이라 재기동 다시 쓰기(D-119 실측 약 65초) 동안에도 게이트웨이 수신·전달 스레드를 막지 않는다. 오히려 게이트웨이 힙 OOM 위험(D-119 실측 220만 줄 힙 1~3GB OOM)을 없애 같은 PC의 실전 엔진과의 메모리 경쟁을 줄인다(절대 규칙 6 방향과 일치). 변이 1로 고치기 전 코드가 64MB 힙에서 OOM, 고친 코드는 통과함을 직접 확인 |
| A9 | 해당 없음 | 보호 시세 경로(REST 폴링)·서버 측 손절 코드 변경 없음. 녹화는 실전 엔진 시세 경로가 아니다 |
| A10 | 통과 | `git diff origin/main...HEAD`에 appkey·secret·token·account·password·계좌번호 문자열 없음(grep 결과 0줄). 시험 픽스처는 공개 종목코드와 합성 가격만 사용(DayWriterRestartStreamTest.java:41-42, 59-73). 예외 메시지는 폴더 경로만 포함(DayWriter.java:116) |
| A11 | 통과 | 보호 테스트(architecture-test 모듈, Guard·장애 테스트) 삭제·비활성화·고정 값 변경 없음. 새 동작에 실패 경우 포함 시험 4개(`DayWriterRestartStreamTest`: 바이트 동일, 순번 끊김 시 원본 보존·tmp/보관본 없음, 앞 날 읽기 실패 시 공백 미승계, 64MB 힙 별도 JVM 20만 줄 재기동). 변이 1·2·3이 각각 시험에 걸림을 직접 확인. 기존 `RecordingReplayTest`의 재기동·보관본 시험(152, 165, 300, 331, 355, 364행 등) 그대로 통과 |
| A12 | 통과 | 대응표 #9(게이트웨이 크래시 → 실전 영향 없음, 재기동 후 공백 표시, broker-and-fault-design.md 6장)의 전제 유지: 재기동 공백(RECORDER_RESTART) 생성·시작 시각 로직 무변경(DayWriter.java:107-108, 229-245), `큰_녹화도_작은_힙에서_재기동한다`가 공백 10줄·표시 없는 경계 없음을 확인. #10(PC 자원 고갈)·5.2 정지 순서 3번(녹화 축소)과 충돌 없음, 메모리 사용이 줄어든다. 디스크는 원본·tmp·보관본 동시 존재로 고치기 전과 같다(D-119 위험 절). 장애 테스트 8종(7장) 대상 코드 변경 없음. 해외 O1~O8: 녹화 시간대·형식 무변경이라 영향 없음 |
| A13 | 해당 없음 | 시장별 분기 추가 없음. 날짜 판정은 기존 `zone`(거래소 시간대) 그대로(MarketEventRecorder.java:143, 무변경). 금액 계산 없음 |
| A14 | 통과 | D-119 결정 문서·결정 로그 README 행·기획서 v14.55(MD-05)·D-117 후속 줄이 코드 동작(줄 단위 다시 쓰기, 순서, 실패 시 tmp 삭제, carryRestartFrom 무승계)과 일치. 결정 문서가 보호 경로라 사용자 머지로 확정한다고 명시 |

## 점검표 밖 발견
- N1(경미, 머지 차단 아님): `큰_녹화도_작은_힙에서_재기동한다`(DayWriterRestartStreamTest.java:151-153)가 `p.getInputStream().readAllBytes()`를 `p.waitFor(5, MINUTES)` 앞에서 부른다. 자식 JVM이 멈추면 readAllBytes가 끝나지 않아 5분 제한이 효과가 없고 CI가 Gradle 시간 제한까지 걸린다. 출력 읽기를 별도 스레드로 하거나 `@Timeout`을 붙이는 것을 후속으로 권한다.
- N2(경미): `rewriteToTmp`의 정리 catch는 `IOException | RuntimeException`만 잡아 `Error`(OOM 등)에서는 tmp가 남는다(DayWriter.java:136). 다음 재기동이 같은 이름을 덮어쓰고(`Files.newOutputStream` 기본 TRUNCATE) 리플레이는 tmp를 읽지 않으므로 녹화 무결성 영향은 없다. 최대 녹화 크기만큼 디스크가 일시적으로 남을 수 있다는 점만 기록.
- N3(운영, 기존 동작): 재기동 다시 쓰기(약 1~2분) 중 게이트웨이를 정상 종료하면 `MarketEventRecorder.close`가 30초 뒤 쓰기 스레드를 interrupt하지만 파일 입출력은 끊기지 않아 프로세스 종료 시 tmp가 반쯤 남는다. 원본은 교체 전이라 그대로이고 다음 재기동에서 tmp를 다시 만든다. 고치기 전과 같은 끝 상태. D-119 후속(6단계 문서에 재기동 1~2분 안내)에 동의한다.
- 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응 규칙 변경 없음. 실전 엔진 프로세스는 이 코드를 싣지 않는다.

## 판정
승인
