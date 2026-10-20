# architect 검토: PR #187 후속 재기동 다시 쓰기 시험 보강 (PR #192)
- 대상: PR #192 / 커밋 0f6bf5f (브랜치 `claude/project-thread-m7fb2x`) / `git diff origin/main...HEAD` 전체(merge-base 94981d9 = origin/main, 커밋 1개)
- 변경 파일: `DayWriter.java`(예외 문구 한 줄), `DayWriterRestartStreamTest.java`(+52/−5, 시험 1개 수정·1개 추가·작은 힙 시험 출력 처리 변경), `docs/decisions/D-119-daywriter-streamed-restart.md`(후속 반영 절), `docs/qa-out-status.md`(PR #187 QA 처리 한 줄)
- 점검표: B
- 직접 실행한 것(같은 작업 트리의 risk-ops와 겹치지 않게 0f6bf5f를 별도 worktree로 받고 `--project-cache-dir`를 따로 둠, 끝난 뒤 worktree 제거):
  - `./gradlew build --offline --no-daemon` → **BUILD SUCCESSFUL, EXIT 0**(3분 45초). 테스트 결과 XML 합계 1,678개, 실패·오류·건너뜀 0. `DayWriterRestartStreamTest` 5/5(무거운 시험 11.5초).
  - 변이 P4(`rewriteToTmp`에서 `withSourceSequence(l)` → `l.event()`): `정상_종료_녹화를_다시_쓴_줄은_원본_줄과_바이트가_같다` **실패**, 나머지 4개 통과. 같은 변이에 main(94981d9) 판 시험 파일을 쓰면 **5개 모두 통과**(EXIT 0) → 고치기 전 시험은 이 변이를 놓쳤고 새 시험이 잡는다는 QA Q1 주장을 직접 확인.
  - 변이 P10(`carryRestartFrom`의 `lastAlive(segs, previous.lastReceivedAt())` → `previous.lastReceivedAt()`): `앞_날_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다` **실패**, 나머지 통과.
  - 변이 N1(Probe가 `lastSeq` 출력 뒤 200만 줄을 더 찍고 600초 잠듦, 시간 제한을 5분 → 10초로 줄임): `큰_녹화도_작은_힙에서_재기동한다`가 14.2초에 `exited` 단언으로 **실패**(메시지에 자식 출력 끝 `spam-1999999`까지 담김), 시험 뒤 남은 Probe 프로세스 없음(`pgrep`). 파이프 막힘 없이 시간 제한이 실제로 듣는 것을 확인.
  - 각 변이 뒤 `git checkout --`로 원복.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build --offline --no-daemon` EXIT 0(별도 worktree, 0f6bf5f), 1,678개 시험 실패 0 |
| B2 | 통과 | 운영 코드 변경은 `DayWriter.java:132` 예외 문구 한 줄. 새 import는 시험 쪽 `DataGap`(domain, marketdata가 이미 의존)·`java.time.Duration`뿐(`DayWriterRestartStreamTest.java:6,20`). 모듈 의존·ArchUnit 규칙 변경 없음, `autotrade-architecture-test` 빌드 통과 |
| B3 | 해당 없음 | `DayWriter`는 패키지 전용 `final class`, 공개 API·녹화 형식·스키마·설정 키·REST 변경 없음. 예외 문구만 바뀜(호출자 `MarketEventRecorder`는 예외 클래스 이름만 로그). 기록은 D-119 "후속 반영" 절(`D-119-daywriter-streamed-restart.md:65-72`) |
| B4 | 통과 | 새 운영 코드에 시계·난수 없음. 새 시험은 고정 `Instant`(`OPEN`, `Duration.ofDays/Hours`)만 쓰고, 공백 순서는 `TreeSet` 이름순이라 `containsExactly(000660, 005930)`이 결정적(`DayWriter.java:86,193`). sim·골든 시험 무변경, 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음(시험 가격은 `BigDecimal.valueOf`) |
| B6 | 통과 | QA Q1·Q2·risk-ops N1마다 시험이 있고 변이 P4·P10·N1로 각각 잡는 것을 직접 확인(위). 기존 시험은 지우거나 끄지 않음: 바이트 같음 시험은 단언을 유지한 채 입력을 강화하고 단언을 추가(`DayWriterRestartStreamTest.java:99-121`), diff에 `@Disabled`·삭제 없음. 예외 문구 변경(architect 2)은 시험이 없음(쓰기 실패 재현이 어려움, 동작 무변경이라 막지 않음, 아래 발견 2) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt:14`) → 지침 11장에 따라 실전 관련 PR, risk-ops 검토·독립 QA·사용자 머지 대상. 실전 주문 경로·Guard·블랙리스트 코드는 건드리지 않음(diff 범위 확인) |
| B8 | 통과 | diff에서 키·계좌·토큰·비밀 문자열 grep 결과 없음. 새 예외 문구는 녹화 폴더 경로만 담음(`DayWriter.java:132`). 시험의 `KIS-WS`·`KIWOOM-WS`는 소스 식별자 |

## 동작 확인(코드로)
- `[사실]` 새 예외 문구는 sink 람다 안의 `UncheckedIOException`에만 붙고(`DayWriter.java:131-133`), `rewriteToTmp`의 `catch (IOException | RuntimeException e)`가 tmp를 지운 뒤 같은 객체를 다시 던진다(`:136-142`). 생성자의 `catch (IOException e)`(`:115-116`)는 이 예외를 잡지 않으므로 이중 감싸기 없이 그대로 올라간다. 예외 종류·흐름은 고치기 전과 같다.
- `[사실]` Q1 시험: `i % 3 == 0`이면 `KIS-WS` 소스 순번 5000+i, 아니면 `KIWOOM-WS` 1000+i(`DayWriterRestartStreamTest.java:103-104`). 녹화 순번(1부터)과 소스 순번이 모든 줄에서 다르므로, 소스 순번 복원이 빠지면 `srcSeq` 필드(`RecordingCodec.java:46`)가 달라져 바이트 비교와 새 단언 둘 다 깨진다.
- `[사실]` Q2 시험: 앞 날 정상 종료 시각(`prevOpen+7h`)이 마지막 수신(`prevOpen+10s`)보다 늦고 재개 시각보다 이르므로 `writeRestartGaps`의 "재개보다 늦으면 재개 시각" 조정(`DayWriter.java:230`)에 걸리지 않고 `lastAlive`의 종료 시각 분기만 검증한다.
- `[사실]` N1: `redirectErrorStream(true).redirectOutput(file)`로 표준 오류까지 파일로 가서 부모가 파이프를 읽느라 막히지 않는다. 시간 초과면 `destroyForcibly().waitFor()` 뒤 `exited` 단언이 먼저 실패하므로 `exitValue()`의 `IllegalThreadStateException`도 없다(`DayWriterRestartStreamTest.java:197-205`).

## 점검표 밖 발견(막지 않음)
1. Q2 시험 주석 "마지막 수신(event(1))"(`DayWriterRestartStreamTest.java:133`)은 실제 코드와 맞지 않는다. 마지막 수신은 `event(1)`이 아니라 직접 만든 두 번째 `TradeTick`(000660, `lastRx`)이다. Q1 주석의 "1000·5000부터"도 실제로는 KIWOOM 첫 줄이 1001이다. 다음 손질 때 주석만 고치면 된다.
2. architect 2(예외 문구)에는 시험이 없다. 쓰기 실패를 재현하려면 sink 주입이나 파일 시스템 조작이 필요해 비용이 커서 요구하지 않는다. 동작 무변경은 위 코드 확인으로 `[추론]` 갈음.
3. N1 시간 초과 때는 자식 출력 파일 전체를 `Files.readString`으로 테스트 JVM에 올려 단언 메시지로 쓴다. 변이 N1에서 약 30MB가 문제없이 처리됐지만, 폭주 출력이 5분 동안 이어지면 커질 수 있다 `[추론]`. 원하면 끝 N KB만 읽도록 다음 손질 때 검토.
4. tmp 잔존(보관·옮기기 실패 때, PR #187 architect 1)은 D-119 후속 반영 절에 "그대로 둔다"로 남았다. 다음 재기동이 덮어쓰고 리플레이가 tmp를 읽지 않으므로 손실 없음 `[추론]`, 동의.
5. 보호 경로 PR인데 이 커밋 기준 `docs/qa/pending.md`에 PR #192 독립 QA 요청 줄이 없다. 지침 11장 절차에 따라 메인이 추가해야 한다(점검표 B 항목 아님, 판정 무관).
6. 무거운 시험의 Windows 실행은 확인 못 함(점검표 항목 아님, D-119 후속 반영 절도 사용자 PC 확인으로 둠).

## 판정
승인 (architect 코드 검토 기준. 보호 경로 변경이므로 지침 10·11장에 따라 risk-ops 원문·독립 QA 뒤 사용자 머지)
