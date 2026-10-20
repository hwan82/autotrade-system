# QA 검토: D-119 녹화기 재기동이 그날 녹화를 메모리에 올리지 않음(PR #179 risk-ops N2)
- 대상: PR #187 / 브랜치 `claude/project-thread-m7fb2x` / 헤드 커밋 536e78d / 병합 기준 main(4d98ded) 대비 diff 전체(커밋 4개, 9파일 +362 −18: 운영 `DayWriter.java` +50 −18, 시험 `DayWriterRestartStreamTest.java` 신규 +182, 문서 D-119 신규·D-117 한 줄·결정 로그 표·기획서 v14.55·`docs/qa/pending.md`, 검토 원문 `docs/reviews/pr-187/{architect,risk-ops}.md`). GitHub `mergeable_state: dirty`(아래 R1).
- 점검표: A 전체 + B1, B6. `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt`)라 실전 관련 PR로 본다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 테스트)는 diff에 없다(`git diff --name-only 4d98ded HEAD`).
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 536e78d): 1~16회째 의존성 받기 실패(Maven Central `429 Too Many Requests`, `Could not download …jar`, 환경 문제·코드 무관, 회차마다 받은 것이 늘어 실패 지점이 뒤로 감). 20초 간격 재시도 루프 하나, 17회째 **BUILD SUCCESSFUL in 2m 57s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,662건, 실패 0, 오류 0, 건너뜀 0**. `DayWriterRestartStreamTest` 4건 통과(큰 녹화 시험 12.6초).
  - GitHub CI(헤드 536e78d, run 39355302892): Gradle build, Lab screens (Playwright), DB schema (PostgreSQL 16), Secret scan and collector boundary 4종 success. 운영 정보로만 적고 판정은 위 직접 실행으로 한다.
  - **고치기 전 결함 재현**: `DayWriter.java`만 main(4d98ded) 판으로 되돌리고 `DayWriterRestartStreamTest` 실행 → `큰_녹화도_작은_힙에서_재기동한다` 실패, 자식 JVM 출력 `OutOfMemoryError: Java heap space`. 원복 뒤 통과. PR의 "고치기 전 코드는 64MB 힙에서 OOM" 주장 확인. 220만 줄·힙 1~4GB 실측 표와 SHA-256 `603f9f52…`는 저장소 밖 측정이라 재현하지 않았고 판정 근거로 쓰지 않음.
  - **구·신 `DayWriter` 차등 시험**(커밋하지 않은 임시 시험, marketdata 시험 폴더에 두고 돌린 뒤 지움): main(4d98ded)의 `DayWriter.java`를 이름만 바꿔(`QaOldDayWriter`) 옆에 두고, 무작위 녹화 폴더 **2만 개(시드 0~19,999)**를 같은 바이트로 두 벌 복사해 구·신에 같은 순서로 같은 일을 시켰다. 만든 녹화: 구간 1~3개, 구간마다 0~24줄(체결·DISCONNECTED 공백, 소스 2개), 끝은 정상 `close(끝 시각)`·`close(null)`·비정상 종료(flush 뒤 몇 줄 더 쓰고 그 시점 바이트로 되돌림, 반쯤 내려간 바이트 포함). 그 뒤 손상 하나: 바이트 자름, 끝 쓰레기, 비트 뒤집기, 메타 삭제, 빈 events 파일, 낡은 `events.jsonl.gz.tmp`, 이미 있는 보관본(`before-segment-1~3`), 깨진 메타 JSON. 시드 3분의 1은 날짜를 넘긴 재기동(앞 날 폴더만 위처럼 만들고 새 날 `carryRestartFrom`).
    - 비교한 것: 생성자 예외(클래스·문구·원인, 폴더 경로만 자리표시), `lastSeq`, `carryRestartFrom` 예외, 새 이벤트 0~3줄의 `write` 반환(재기동 공백 줄), flush 직후와 `close` 뒤 녹화 뿌리 아래 **모든 파일의 바이트**(events·meta·보관본·tmp), 그다음 한 번 더 재기동·한 줄 쓰기·닫기 결과와 모든 파일 바이트.
    - 결과: 연 녹화 16,385개, 생성자 거부 3,615개, 날짜 넘김 6,667개(그중 앞 날 읽기 거부 1,763개). **불일치 0**(예외 문구·파일 바이트·보관본·두 번째 재기동까지 같음).
  - **변이 시험**(운영 `DayWriter.java` 한 곳씩 바꾸고 `:autotrade-marketdata:test`, 매번 원본으로 되돌림, 마지막 `git status` 깨끗). 저장소 시험 기준 12개 중 **9개 잡힘**(위 임시 차등 시험은 12개 모두 잡음, 판정에는 저장소 시험 기준만 씀):

| 변이 | 내용 | 결과(저장소 시험) |
|---|---|---|
| P1 | `rewriteToTmp` 정리 catch를 `IOException`만 | 잡힘(`앞_녹화를_읽다_실패하면_원본을_그대로_두고_tmp와_보관본을_남기지_않는다`) |
| P2 | 같은 날 재기동에서 `restartSymbols = symbols` 뺌 | 잡힘(13건, `큰_녹화도_…`·`JournalWriterTest` 등) |
| P3 | `carryRestartFrom`이 읽는 중에 바로 `restartSymbols`에 넣음(실패 시 일부 넘겨받음) | 잡힘(`앞_날을_읽다_실패하면_재기동_공백을_넘겨받지_않는다`) |
| P4 | 다시 쓰기에서 원래 소스 순번 복원(`withSourceSequence`) 뺌 | **생존**(Q1) |
| P5 | 보관본을 원본 대신 tmp에서 복사 | 잡힘(`RecordingReplayTest#비정상_종료_뒤_재기동은_다시_쓰기_전_원본을_보관한다`) |
| P6 | 원본 교체를 보관 앞으로 | 잡힘(같은 시험) |
| P7 | 실패 시 tmp 지우기 없앰 | 잡힘(`앞_녹화를_읽다_실패하면_…`) |
| P8 | 다시 쓰기에서 종목 모으기 뺌 | 잡힘(13건) |
| P9 | 이미 있는 보관본을 덮어씀 | 잡힘(`RecordingReplayTest#재기동이_다시_쓰기_뒤_메타_기록_전에_또_멈춰도_처음_보관한_원본을_덮지_않는다`) |
| P10 | `carryRestartFrom` 공백 시작을 앞 날 마지막 구간 끝 무시하고 마지막 수신 시각만 | **생존**(Q2, main부터 같음) |
| P11 | 정상 종료 녹화는 다시 쓰지 않고 그대로 이어 붙임(tmp 버림) | 생존(파일 바이트만 다르고 읽기 결과는 같음, D-119 대안 C. 판정에 쓰지 않음) |
| P12 | tmp를 버리고 원본을 다시 쓰지 않음(끝나지 않은 gzip 멤버 뒤에 바로 이어 씀) | 잡힘(6건, `RecordingReplayTest#비정상_종료_뒤_재기동하면_flush한_줄을_지우지_않고_…` 등) |

  - 호출처 확인: `DayWriter`를 만드는 운영 코드는 `MarketEventRecorder.loop`(게이트웨이 녹화 쓰기 스레드, `MarketEventRecorder.java:148`)와 `JournalWriter.append`(실험실 입력 기록, `JournalWriter.java:74-76`)뿐. 두 호출처와 그 오류 처리(`MarketEventRecorder.java:161-162`의 `IOException | RuntimeException`)는 diff 밖이고 무변경. `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-risk`·`autotrade-broker-*` 운영 코드는 `marketdata.recording`을 쓰지 않음(PR #179 QA와 같은 grep, 이 diff는 의존을 늘리지 않음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 Guard·설정 로딩·엔진 파일 없음(운영 파일은 `DayWriter.java` 하나). 빌드에서 `RealTradingGuardTest`·`GuardedOrderGatewayTest` 포함 1,662건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 0줄. 새 import는 JDK와 같은 패키지 `RecordedDayStream`뿐, 새 클래스·모듈 의존 없음(`ModuleOwnershipTest`·`ArchitectureTest` 통과) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음. 바뀐 코드는 녹화 파일 쓰기뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음. 녹화 `seq` 이어 쓰기 값은 차등 시험 2만 개에서 구 동작과 같음(`lastSeq`·두 번째 재기동 포함) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진은 바뀐 클래스를 싣지 않음(위 호출처). 게이트웨이 쪽에선 재기동 다시 쓰기의 힙 사용이 녹화 크기와 무관해짐을 직접 확인(main 판 64MB에서 `OutOfMemoryError`, PR 판 통과). 다시 쓰기 동안 생산자 `offer`는 기다리지 않고 큐가 차면 버리며 RECORDER_OVERFLOW 공백(`MarketEventRecorder.java:89-112`, 무변경)이라 수신 스레드를 막지 않음. 같은 PC의 실전 엔진과의 메모리 경쟁은 줄어드는 방향(절대 규칙 6) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절·게이트웨이 실시간 수신 코드 무변경. 녹화는 실전 엔진 시세 경로가 아님 |
| A10 | 통과 | diff 추가 줄에서 `appkey|secret|token|password|계좌번호|account` 일치는 검토 원문의 설명 문장 2줄뿐(값 아님). 시험 픽스처는 공개 종목코드·합성 가격·시각뿐(`DayWriterRestartStreamTest.java:40-72`). 예외 문구는 폴더 경로만 |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·약화 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 시험 diff에 지운 줄 0(새 파일 하나). 새 동작에 실패 경우 포함 시험 4건(읽기 실패 시 원본·tmp·보관본, 앞 날 읽기 실패 시 미승계, 64MB 별도 JVM 20만 줄, 바이트 같음). 고치기 전 판이 큰 녹화 시험에서 OOM으로 실패함을 직접 확인. 변이 12개 중 9개 잡힘, 생존 P4·P10은 Q1·Q2(비차단) |
| A12 | 통과 | 대응표 #9(게이트웨이 크래시 → 실전 영향 없음, 재기동 뒤 공백 표시)의 전제인 재기동 공백(RECORDER_RESTART) 줄·시작 시각·보관본(QA H-4)이 차등 시험 2만 개에서 구 동작과 바이트까지 같음. #10(자원 고갈)은 메모리 사용이 줄어드는 쪽. 장애 테스트 8종·O1~O8 대상 코드 변경 없음, 녹화 형식·시간대 무변경 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 날짜 판정은 기존 `zone`(무변경) |
| A14 | 통과 | D-119 결정 1~4가 코드와 맞음(줄 단위 읽기로 tmp 쓰기 `DayWriter.java:124-144`, 순서 tmp → 보관 → 교체 `:87-106`, 실패 시 tmp 삭제 `:136-143`, `carryRestartFrom` 다 읽은 뒤 대입 `:193-197`). 결정 로그 표 D-119 행, 기획서 v14.55(MD-05), D-117 후속 줄 있음. 결정 2의 "tmp만 남지 않음"은 읽기·쓰기 실패 한정 문장이고 보관·교체 실패와 `Error`에서는 tmp가 남음(architect 1·risk-ops N2가 적은 대로, 다음 재기동이 덮음) |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 17회째 성공, 1,662건 실패 0 |
| B6 | 통과 | 새 동작 시험 `DayWriterRestartStreamTest` 4건 추가, 지운 시험·건너뛴 시험 없음(XML 건너뜀 0). 시험 공백은 Q1(비차단) |

## 점검표 밖 발견
시나리오(잘못된 주문·중복·보호 공백으로 이어질 수 있는가):
- S1 장중 게이트웨이 재기동, 그날 녹화가 큼(대응표 #9): 고치기 전엔 녹화기 쓰기 스레드가 OOM으로 죽고(`Error`라 `MarketEventRecorder.loop`의 catch에 안 걸림) 같은 JVM의 시세 전달도 흔들릴 수 있었다. 고친 뒤 64MB 힙에서 20만 줄 재기동 통과를 직접 확인. 다시 쓰는 동안 들어온 이벤트는 큐에서 기다리고 넘치면 공백 표시로 남는다(무변경). 주문 경로와 무관. 결함 없음.
- S2 재기동 다시 쓰기 중 읽기 실패(순번 끊김·형식 오류·해시 불일치)나 쓰기 실패(디스크 부족): 원본은 교체 전이라 바이트 그대로, tmp 삭제, 보관본 안 만듦(시험·P1·P7). 예외는 고치기 전과 같은 종류로 올라가 녹화기가 "녹화 중단"을 남기고 멈춤(main과 같은 끝 상태, 차등 시험 생성자 거부 3,615개 문구까지 같음). 쓰기 실패 문구에 폴더가 빠지는 점은 architect 2와 같음. 결함 없음.
- S3 재기동이 단계 사이에서 또 멈춤(tmp 완성 뒤·보관 뒤·교체 뒤 메타 기록 전): 교체 전엔 원본이 그대로라 다음 재기동이 처음부터 다시 함. 보관 뒤 교체 전이면 보관본과 원본이 같고, 교체 뒤 메타 전이면 메타 구간 수가 그대로라 같은 보관본 이름이 이미 있어 덮지 않음(`DayWriter.java:102`, P9로 고정). 낡은 tmp는 `Files.newOutputStream` 기본 TRUNCATE로 덮임(차등 시험의 낡은 tmp 경우 불일치 0). 결함 없음.
- S4 날짜를 넘긴 재기동에서 앞 날 녹화가 손상: `carryRestartFrom`이 예외를 올리고 아무 종목도 넘겨받지 않음(시험·P3). `JournalWriter.append`는 그 예외를 그대로 올려 세션을 멈추는데, 고치기 전과 같은 끝 상태(차등 시험 앞 날 거부 1,763개 같음). 결함 없음.

권고·정보:
- **Q1(권고, 비차단, 시험 공백)**: 다시 쓰기에서 원래 소스 순번 복원(`withSourceSequence`)을 빼도(P4) 저장소 시험이 하나도 실패하지 않는다. 새 시험 `정상_종료_녹화를_다시_쓴_줄은_원본_줄과_바이트가_같다`는 픽스처 이벤트의 소스 순번이 녹화 순번과 우연히 같아서(`event(i)`의 sequence가 `i + 1`, 녹화 순번도 `i + 1`, `DayWriterRestartStreamTest.java:58-71,99-100`) 이 복원을 구별하지 못한다. 이 변이면 재기동 뒤 다시 쓴 줄의 `srcSeq`가 모두 녹화 순번으로 바뀌어 원래 소스 순번이 사라진다(녹화 손상, 주문 경로와는 무관). 기존 시험 집합은 main 시험을 모두 포함하므로 main에서도 같은 공백이다 `[추론]`. 바이트 시험의 픽스처 sequence를 녹화 순번과 다르게(예: 소스 2개, 소스 순번 1000부터) 바꾸기를 권한다.
- **Q2(권고, 비차단, main부터 같음)**: `carryRestartFrom`의 공백 시작이 앞 날 마지막 구간 끝(정상 종료 시각)을 반영하는지 시험이 고정하지 않는다(P10 생존). 이 PR은 그 줄(`lastAlive(segs, …)`)을 옮기기만 했다. 앞 날이 정상 종료 시각을 마지막 수신보다 늦게 남긴 경우의 공백 시작 시험을 권한다.
- R1(머지 전 필요): PR 브랜치가 현재 main(998bbe8)과 `docs/decisions/README.md`·`docs/spec/autotrade-spec.md`에서 충돌한다(`git merge-tree --write-tree origin/main HEAD` 결과 그 두 파일, main의 D-120 행·v14.56 줄과 이 PR의 D-119 행·v14.55 줄). 코드 충돌은 없다. main은 D-119 번호와 v14.55를 이 PR 몫으로 비워 두었다(main D-120 행·v14.56 줄의 "D-119는 PR #187이 씀"). main을 병합해 두 쪽 줄을 모두 남기고(기획서 버전 칸은 v14.56 유지) CI가 다시 돈 뒤 머지해야 한다.
- R2(정보): 읽기가 실패하면 이제 같은 이름의 낡은 tmp도 지워진다(`rewriteToTmp`가 tmp를 먼저 새로 열고 실패 시 지움). tmp는 다시 쓰기 중간 산물이라 잃는 녹화는 없다. risk-ops N1(큰 녹화 시험의 `readAllBytes`가 `waitFor` 앞이라 5분 제한이 안 듦, `DayWriterRestartStreamTest.java:159-160`)도 코드로 확인했고 동의한다.

## 판정
승인
