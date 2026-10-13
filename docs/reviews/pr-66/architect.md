# architect 검토: 비정상 종료 뒤 녹화 재기동이 줄을 지우던 문제 수정, GatewayMain 주석 D-059 반영 (2단계 재검토 H-4·3·5)
- 대상: PR #66 / 브랜치 `claude/project-thread-jdynjl` 커밋 2fd2c8b147496d38c75a4f58581808abd9aca667 / `git diff origin/main...HEAD`(merge-base 1fba92e, 커밋 1개, 7파일 +220 −12). origin/main은 그 뒤 a6f2602까지 나갔고 `git merge-tree`로 충돌 없음 확인
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline` → 실패(오프라인 캐시에 junit 런처 없음, 코드 무관)
  - `./gradlew build --continue` → `:autotrade-architecture-test:compileTestJava` 만 Maven Central 429로 의존성 해석 실패, 나머지 모든 모듈 test 실행·통과
  - `./gradlew :autotrade-architecture-test:test` 재시도 → BUILD SUCCESSFUL(31건)
  - `./gradlew build` → **BUILD SUCCESSFUL**(130 tasks). 테스트 결과 XML 합계 tests 902, failures 0, errors 0, skipped 0(marketdata 94, sim 115, architecture-test 31 포함). `RecordingReplayTest` 20건, 새 테스트 5건 통과
  - 탐침 1(임시 테스트, 실행 뒤 삭제): `DayWriter`로 N줄 쓰고 `flush()` 뒤 닫지 않고 `RecordedDay.open` → 재기동. N=10,000 → 읽힘 10,000 / 재기동 뒤 10,002(공백 1 + 새 줄 1), N=100,000 → 100,000 / 100,002. flush 뒤 안 내려간 쓰기가 섞인 경우(N=100 + 미flush 5,000 → 4,048줄, N=1 + 미flush 20,000 → 19,725줄)도 순번 연속·`truncated=true`·보관본 생성. QA 탐침(나)의 손실은 재현되지 않음
  - 탐침 2(임시 테스트, 실행 뒤 삭제, merge-base 1fba92e 작업 트리와 이 브랜치에서 같은 코드 실행): 메타 없음/미완료 + `events.jsonl.gz`가 0바이트·5바이트·64바이트 0으로 채움(첫 gzip 머리 손상). **merge-base: 6경우 모두 `lines=0 truncated=true`, 재기동 성공. 이 브랜치: 6경우 모두 `UncheckedIOException`(원인 `EOFException` 또는 `ZipException: Not in GZIP format`), `DayWriter` 생성 실패** → 아래 F1

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL, 902건 실패·오류·건너뜀 0. 앞선 실패는 오프라인 캐시 부족과 429 다운로드 오류(재시도에서 해소) |
| B2 | 통과 | 새 의존 없음. `RecordedDay.java`가 더한 import는 `java.io.FilterInputStream`·`java.io.InputStream`뿐, `DayWriter.java`는 기존 `StandardCopyOption`만 사용. `autotrade-architecture-test` 31건 통과. 규칙 변경 없음 |
| B3 | 통과 | 녹화 폴더 배치(계약)에 보관본 `events.jsonl.gz.before-segment-<n>`이 생기는 변경을 ADR-003 2.x "중단 후 이어 쓰기"(`docs/adr/ADR-003-market-data.md:81`)에 반영. 녹화 형식 버전·`meta.json` 키·설정 키·REST 변경 없음. 운영 절차서 반영은 권고 O2 |
| B4 | 통과 | 새 코드에 시계·난수 없음(`DayWriter.java:94~97`, `RecordedDay.java:111~165`). 정상 종료 녹화는 `tolerate=false`라 예외를 그대로 올려 기존 경로와 같다(`RecordedDay.java:153~156`). `RecordingReplayTest`의 기존 리플레이 동일성 테스트와 sim 골든 테스트(115건) 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음(테스트 가격은 `BigDecimal`) |
| B6 | 통과 | 새 동작마다 테스트: `비정상_종료_전_flush한_줄은_줄_수와_무관하게_모두_읽힌다`(N=1·2·10·100·1,000), `비정상_종료_뒤_재기동하면_flush한_줄을_지우지_않고_재기동_공백을_남긴다`(N=1·100), `비정상_종료_뒤_재기동은_다시_쓰기_전_원본을_보관한다`, `정상_종료_뒤_재기동은_보관본을_만들지_않는다`, `비정상_종료가_두_번_이어져도_세_번째_기동까지_줄과_재기동_공백이_모두_남는다`. 지우거나 끈 테스트 없음. 단 F1의 퇴행 경로는 테스트가 없다 |
| B7 | 해당 없음 | 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·증권사 주문 연동 변경 없음. 바뀐 것은 시세 녹화(`autotrade-marketdata` recording)와 `GatewayMain` 주석 한 줄. 시세 게이트웨이는 주문 API를 호출하지 않는 조건(MD-01) 그대로 |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음(`git diff` 직접 확인). 새 파일 `2026-10-12.meta.json`은 건수·해시·구간·출처·이용 조건 문구만 있고 시세 값·식별 정보 없음 |

## 점검표 밖 발견

- **F1 (수정 필요) 첫 gzip 머리가 깨진 미완료 녹화를 이제 읽지 못해, 그날 녹화가 재기동 때마다 바로 멈춘다(퇴행).**
  - [사실] 바뀌기 전에는 `new GZIPInputStream(...)`이 try-with-resources의 자원 선언 안에 있어, 생성자가 머리를 읽다 던진 `EOFException`·`ZipException`도 `catch`로 잡혀 미완료 녹화면 `truncated=true`로 끝났다(JLS 14.20.3.2). 이 PR은 생성을 try 밖(`RecordedDay.java:81~82`)으로 옮기고 끝 처리를 `EndTolerantInputStream.read`에만 두어, 생성자에서 나는 예외가 그대로 `UncheckedIOException`이 된다(`RecordedDay.java:72~73`). 이 경우 `Files.newInputStream`도 닫히지 않는다.
  - [사실] 탐침 2에서 0바이트·짧은 머리·0으로 채운 파일 모두, merge-base는 재기동 성공, 이 브랜치는 `DayWriter` 생성 실패. `MarketEventRecorder.loop`(`MarketEventRecorder.java:148`, `:161`)는 이 예외를 잡고 쓰기 스레드를 끝내므로 그 시장의 녹화가 멈추고, 같은 날 다시 기동해도 같은 파일에서 또 멈춘다.
  - [추론] 발생 조건: `DayWriter`가 파일을 만든 직후(`DayWriter.java:106~107`, 생성과 머리 10바이트 쓰기 사이) 죽거나, 전원 차단으로 파일 크기·이름만 남고 내용이 0이거나 비어 있는 경우. 이 PR이 다루는 "창 닫힘·전원 꺼짐" 경로 안의 일이고, 장 시작 직후 첫 기동에 생기면 그날 녹화를 통째로 잃는다.
  - 고치는 방향: 미완료(`!complete`)일 때 `GZIPInputStream` 생성 예외(`EOFException`·`ZipException`)도 끝으로 보고 `lines=0, truncated=true`를 돌려준다(예: 생성을 try 안에 두고 미완료일 때만 잡기, 실패 시 원 스트림 닫기). 완료 녹화는 지금처럼 예외. 원본은 이미 `DayWriter.java:94~97`이 보관하므로 이 경우도 보관본이 남는다. 테스트: 메타 없음·미완료 + 0바이트/깨진 머리 파일에서 `RecordedDay.open`이 0줄·`truncated`이고, 재기동이 성공하며 보관본이 원본과 같은지.

- **F2 (수정 필요, 문서) `experiments/stage-2-replay/2026-10-12.md:59` "2026-10-12 녹화는 정상 종료(`complete: true`)라 해당 없다"는 사실과 다르다.**
  - [사실] 같은 문서 `:23`과 `meta.json` 구간 1이 보여 주듯 1구간은 13:05:41 KST에 게이트웨이 창이 닫혀 끝났고(비정상 종료), 14:19:47 재기동 때 수정 전 `DayWriter`가 수정 전 `RecordedDay`로 읽은 줄만으로 파일을 다시 썼다. `complete: true`는 2구간 끝의 정상 종료만 뜻한다.
  - [추론] 그래서 H-4 손실(QA 탐침 기준 마지막 flush 이전 수십 줄, 1초 안팎)이 이 녹화의 1구간 끝에서 일어났을 수 있고 보관본이 없어 확인·복구할 수 없다. 1구간 `endedAt`(04:05:41.655Z)은 살아남은 마지막 줄 기준(`DayWriter.closeOpenSegments`)이라 74분 공백 판정에는 덮이므로 리플레이 동일성 결론은 바뀌지 않는다.
  - 고치는 방향: "해당 없다"를 "1구간 끝(13:05:41)에서 재기동 때 마지막 수 초 분량 이하가 지워졌을 수 있으나 확인할 수 없고, 구간 끝을 남은 줄 기준으로 잡아 공백에 포함된다"처럼 사실대로 고친다. 2단계 관문 증거 문서라 QA가 그대로 읽는다.

- **O1 (권고) 보관본을 덮어쓸 수 있다.** `DayWriter.java:96`은 `REPLACE_EXISTING`이다. 재기동이 `rewrite`(`:98`) 뒤, 새 메타 기록(`:108`) 전에 다시 죽으면 다음 재기동도 같은 구간 번호를 계산해, 원본 보관본을 이미 다시 쓴 파일로 덮는다. 창은 좁지만 보관본의 목적(읽기 코드가 틀렸을 때 원본 보존)에 어긋나므로, 이미 있으면 건너뛰거나 접미 번호를 붙이는 쪽이 낫다.
- **O2 (권고) 보관본 정리 절차가 코드 주석에만 있다.** "사용자가 녹화를 확인한 뒤 지운다"(`DayWriter.java:151`)가 `docs/runbooks/stage-2-real-recording.md`에 없다. 비정상 종료마다 그날 파일 전체 사본(실녹화 기준 약 10MB)이 쌓이므로 절차서에 확인·삭제 단계를 한 줄 넣는다(삭제는 사용자 작업, 지침 8장 3번).
- **O3 (권고) 새 테스트가 닫지 않은 `DayWriter`의 파일 핸들을 남긴다.** `crashAfterFlush`(`RecordingReplayTest.java:252~261`)는 핸들을 연 채로 같은 파일을 다시 열고, 원자 이동으로 바꾸고(`DayWriter.java:123`), `deleteDay`로 지운다. Linux에서는 통과했다. [추론] Windows(사용자 PC에서 `./gradlew build`를 돌릴 때)에서는 열린 파일 삭제·교체 동작이 달라 실패할 수 있다(확인 못 함, 판정에는 넣지 않음). `flush()` 뒤 디스크 내용을 다른 폴더로 복사해 그것을 "죽은 뒤 파일"로 쓰고 원래 쓰기는 닫으면 실제 비정상 종료와 더 같고 핸들도 남지 않는다.
- 확인함: `GatewayMain.java:33` 새 주석은 D-059 ②("게이트웨이·실험실은 토스 API를 호출하지 않는다, 실시간·REST 보정 시세는 키움·한국투자로만")와 일치(지난 관문 C5 ⑤ 해소). `DayWriter.java:34~37` 새 주석은 탐침 1 결과와 일치(flush까지 내려간 줄은 모두 읽힘, 그 뒤 쓰기는 일부만 남을 수 있음; `Gateway.java:29` `FLUSH_INTERVAL` 1초). `2026-10-12.meta.json` 818바이트와 문서의 건수·해시·구간·끊김 값 일치. 리플레이·`replayCheck`는 `events.jsonl.gz`와 `meta.json`만 읽고(`RecordedDay.java:53~54`), 녹화 폴더를 나열하는 운영 코드가 없어 보관본이 리플레이 결과에 끼어들지 않음.

## 판정
수정 필요(F1, F2). B 점검표에 실패·확인 못 함은 없으나, F1은 이 PR이 고치려는 비정상 종료 경로에서 전에 되던 재기동을 막는 퇴행이고(탐침으로 재현), F2는 관문 증거 문서의 사실 오류다. 고친 뒤 같은 검토자에게 재검토를 맡긴다. O1~O3은 머지를 막지 않는 권고다. 실전 경로 변경이 없어 risk-ops 검토 대상은 아니다(B7 해당 없음).
