# architect 재검토 1: 비정상 종료 뒤 녹화 재기동이 줄을 지우던 문제 수정, GatewayMain 주석 D-059 반영 (2단계 재검토 H-4·3·5)
- 대상: PR #66 / 브랜치 `claude/project-thread-jdynjl` 커밋 fa3ea9b2d12ec22c533ec829887238353d8bd40a / `git diff origin/main...HEAD` 전체(커밋 3개: 2fd2c8b 본 수정, f29cf31 지난 검토 원문, fa3ea9b 검토 반영). 지난 검토: [architect.md](architect.md) 판정 수정 필요(F1, F2)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → **BUILD SUCCESSFUL**(1m 35s, 429 없이 1회). 테스트 결과 XML 합계 tests 905, failures 0, errors 0, skipped 0(marketdata 97, architecture-test 31 포함). `RecordingReplayTest` 새 테스트 3건(`gzip_머리도_다_쓰기_전에_멈춘_녹화는_빈_녹화로_읽고_이어_쓴다`, `정상_종료_녹화의_gzip_머리가_깨졌으면_손상으로_본다`, `재기동이_다시_쓰기_뒤_메타_기록_전에_또_멈춰도_처음_보관한_원본을_덮지_않는다`) 포함 통과. UP-TO-DATE로 건너뛴 모듈(domain, risk, strategy, config, control, watchdog)은 이 PR이 바꾸지 않은 모듈이며 같은 브랜치 직전 실행에서 통과
  - 지난 검토의 탐침 2를 이 커밋에서 다시 실행(임시 테스트, 실행 뒤 삭제): 메타 없음/미완료 × events 0바이트·5바이트·64바이트 0으로 채움 6경우 모두 `lines=0 truncated=true`. 재기동 후 새 줄 1, `complete=true`, 보관본은 메타 없음이면 `before-segment-1`, 미완료 메타(구간 1개)면 `before-segment-2`가 생김. 지난 검토의 퇴행은 재현되지 않음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공, 905건 실패·오류·건너뜀 0 |
| B2 | 통과 | 새 의존 없음(`RecordedDay.java` import는 `java.io`·`java.util.zip`만). `autotrade-architecture-test` 31건 통과, 규칙 변경 없음 |
| B3 | 통과 | 녹화 폴더 배치에 생기는 보관본을 ADR-003 `:81`에 반영, 운영 절차서 `docs/runbooks/stage-2-real-recording.md:31`에 확인·삭제 단계 추가. 형식 버전·메타 키·설정 키·REST 변경 없음 |
| B4 | 통과 | 새 코드에 시계·난수 없음. 정상 종료 녹화는 머리 손상(`RecordedDay.java:87`)과 꼬리 손상(`EndTolerantInputStream`, `tolerate=false`) 모두 예외로 올려 기존과 같다. 리플레이 동일성 테스트와 sim 골든 테스트 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 지난 F1 경로에 테스트 추가(`RecordingReplayTest.java:276`, 머리 0·1·4·10바이트, 읽기와 재기동 둘 다). O1 경로 테스트 `:300`. 기존 테스트는 지우거나 끄지 않았고, `crashAfterFlush`·세 번 기동 테스트는 `crash`(`:266`)로 바꿔 열린 핸들 없이 같은 상황을 만든다 |
| B7 | 해당 없음 | 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·증권사 주문 연동 변경 없음. 시세 녹화 모듈, 게이트웨이 주석, 문서만 바뀜 |
| B8 | 통과 | diff 전체에 키·토큰·계좌번호 없음(직접 확인). `2026-10-12.meta.json`은 건수·해시·구간·출처만 있음 |

## 지난 발견 처리 결과
- **F1 해소.** `RecordedDay.java:81~90`: 원 파일 스트림을 try-with-resources로 열고 `GZIPInputStream` 생성 예외(`EOFException`·`ZipException`)를 잡아, 미완료면 0줄·`truncated=true`, 완료면 그대로 던진다. 생성 실패 때도 원 스트림이 닫힌다. 테스트와 탐침으로 확인.
- **F2 해소.** `experiments/stage-2-replay/2026-10-12.md:59`가 1구간 비정상 종료, 14:19:47 재기동 때 수정 전 코드의 다시 쓰기, 확인할 수 없는 손실 가능성(`[추론]`), 1구간 끝 13:05:41이 남은 마지막 줄 기준이라는 점, 결론이 바뀌지 않는 이유를 사실대로 적는다. 1구간 끝이 마지막 수신 시각이라는 설명은 `DayWriter.closeOpenSegments`와 맞다.
- **O1 반영.** `DayWriter.java:98~99`: 보관본이 이미 있으면 덮지 않는다. 쓰기 스레드 하나만 이 생성자를 부르므로 검사 뒤 복사 사이 경쟁은 없다. 같은 이름은 메타의 구간 수가 늘지 않은 같은 재기동 시도일 때만 생기므로, 다른 재기동의 보관본을 잘못 건너뛰지 않는다.
- **O2 반영.** 절차서 `:31`에 보관본의 뜻과 지워도 되는 조건(종료 코드 3이 아니고 이벤트 수가 맞을 때), 이상하면 지우지 말고 알리라는 내용이 있다. 삭제는 사용자 작업으로 남아 지침 8장 3번과 맞다.
- **O3 반영.** `crash`(`RecordingReplayTest.java:266`)가 flush 직후 두 파일을 복사해 두고 쓰기를 닫은 뒤 되돌린다. 열린 핸들이 남지 않는다. Windows 실행은 이번에도 확인하지 못했지만, 지난 우려의 원인(열린 파일의 삭제·교체)은 없어졌다.

## 점검표 밖 발견
- N1 (참고, 머지 막지 않음) `정상_종료_녹화의_gzip_머리가_깨졌으면_손상으로_본다`(`RecordingReplayTest.java:293`)는 SHA-256 불일치(`RecordedDay.java:63~66`)에서 먼저 실패해, 새 분기 `RecordedDay.java:87`(완료 녹화의 머리 손상은 던짐)까지 가지 않는다. 정상 종료는 늘 해시를 쓰므로(`DayWriter.java:212~213`) 실제로 그 분기에 닿는 경우는 `eventsSha256`이 빈 완료 메타뿐이다. 테스트 이름이 말하는 동작(손상으로 봄)은 맞으므로 판정에는 넣지 않는다.

## 판정
승인. B 점검표에 실패·확인 못 함이 없고 지난 수정 필요 항목 F1·F2가 해소됐다. O1~O3도 반영됐다. 실전 경로 변경이 없어 risk-ops 검토 대상이 아니다(B7 해당 없음). 2단계 관문 재검토(H-4) 관련이라 독립 QA 관문 진행 여부는 메인이 지침 11장으로 판단한다.
