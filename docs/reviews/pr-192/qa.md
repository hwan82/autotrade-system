# QA 검토: PR #187 후속, 녹화기 재기동 다시 쓰기 시험 보강(QA Q1·Q2, risk-ops N1, architect 2)
- 대상: PR #192 / 브랜치 `claude/project-thread-m7fb2x` / 헤드 커밋 a506e61 / 병합 기준 main(94981d9, 현재 main 끝과 같음) 대비 diff 전체(커밋 4개, 7파일 +138 −5: 운영 `DayWriter.java` 1줄, 시험 `DayWriterRestartStreamTest.java` +52 −5, 문서 D-119 후속 반영 절·`docs/qa-out-status.md` 1줄·`docs/qa/pending.md` 1줄, 검토 원문 `docs/reviews/pr-192/{architect,risk-ops}.md`). `git merge-tree --write-tree origin/main HEAD` 충돌 없음.
- 점검표: A 전체 + B1, B6. `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt`)라 실전 관련 PR로 본다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 테스트)는 diff에 없다(`git diff --name-only 94981d9 HEAD`).
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 a506e61): 1~8회째 의존성 받기 실패(Maven Central `429 Too Many Requests`, 환경 문제·코드 무관). 20초 간격 재시도 루프 하나, 9회째 **BUILD SUCCESSFUL in 3m 34s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,678건, 실패 0, 오류 0, 건너뜀 0**. `DayWriterRestartStreamTest` 5건 통과(큰 녹화 시험 14.1초).
  - **변이 시험**(운영 `DayWriter.java` 한 곳씩 바꾸고 `:autotrade-marketdata:test --offline`, 매번 원본으로 되돌림, 마지막 `git status` 깨끗):

| 변이 | 내용 | 결과 |
|---|---|---|
| P4 | 다시 쓰기에서 원래 소스 순번 복원(`withSourceSequence`) 뺌(PR #187 QA Q1) | 잡힘(`정상_종료_녹화를_다시_쓴_줄은_원본_줄과_바이트가_같다`) |
| P10 | `carryRestartFrom` 공백 시작을 앞 날 마지막 구간 끝 무시하고 마지막 수신 시각만(PR #187 QA Q2) | 잡힘(`앞_날_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다`) |
| P13 | 다시 쓰기 쓰기 실패 예외 문구 빼고 `new UncheckedIOException(e)`(이 PR의 운영 변경을 되돌림) | **생존**(Q2) |
| P14 | `lastAlive`가 늦은 쪽 대신 이른 쪽을 고름 | 잡힘(4건, 새 Q2 시험·`RecordingReplayTest` 같은 날 재기동 3건) |
| P15 | 같은 날 재기동 공백 시작을 마지막 구간 끝 무시하고 마지막 수신 시각만(`DayWriter.java:108`) | **생존**(Q1, main부터 같음) |

  - **risk-ops N1 확인(시간 제한이 실제로 듣는가)**: 시험 파일을 임시로 바꿔 자식 `Probe`가 `lastSeq=` 출력 뒤 영원히 잠들게 하고 `waitFor` 한도를 15초로 줄여 돌렸다(돌린 뒤 되돌림).
    - PR 판: 약 26초 만에 `큰_녹화도_작은_힙에서_재기동한다` 실패(단언 메시지에 자식 출력 `lastSeq=200000` 포함), 끝난 뒤 남은 `Probe` JVM 0개(`ps`로 확인).
    - main(94981d9) 판 시험 하네스에 같은 변경: Gradle 시험이 180초 동안 끝나지 않아 `timeout`으로 끊김(종료 코드 124). `readAllBytes`가 자식 종료를 기다려 시간 제한이 듣지 않던 PR #187 risk-ops N1을 재현했고, 이 PR이 고쳤음을 확인.
  - 운영 변경 경로 확인: 새 문구는 `rewriteToTmp`의 sink 람다 안 `UncheckedIOException`에만 붙는다(`DayWriter.java:131-133`). 바깥 `catch (IOException | RuntimeException e)`가 tmp를 지우고 같은 객체를 다시 던지며(`:136-143`), 생성자 `catch (IOException e)`(`:115-116`)는 잡지 않는다. 호출처 `MarketEventRecorder`의 `catch (IOException | RuntimeException e)`(`MarketEventRecorder.java:161`, 무변경)가 받는 예외 종류는 고치기 전과 같다. 이 문구를 해석하는 코드는 없다(grep).
  - 호출처·의존: `DayWriter`를 만드는 운영 코드는 PR #187 QA 때와 같다(`MarketEventRecorder`, `JournalWriter`). `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-risk`·`autotrade-broker-*` 운영 코드는 `marketdata.recording`을 쓰지 않고, 이 diff는 의존을 늘리지 않는다(새 import는 시험의 `DataGap`·`Duration`뿐).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 Guard·설정 로딩·엔진 파일 없음. 빌드에서 `RealTradingGuardTest`·`GuardedOrderGatewayTest` 포함 1,678건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 0줄. 새 클래스·모듈 의존 없음(`ModuleOwnershipTest`·`ArchitectureTest` 통과) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음. 운영 변경은 녹화 예외 문구 한 줄 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음. 녹화 순번·소스 순번 다시 쓰기 동작 무변경, 이제 P4로 고정됨 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진은 바뀐 클래스를 싣지 않음(위 호출처). 게이트웨이 녹화 쓰기 스레드의 예외 종류·흐름 무변경 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 무변경 |
| A10 | 통과 | diff 추가 줄의 `appkey|secret|token|password|계좌번호|account` 일치는 architect 원문의 설명 문장 1줄뿐(값 아님). 새 예외 문구는 녹화 폴더 경로만 담고, 같은 폴더를 담는 기존 문구(`DayWriter.java:116`)와 같은 수준. 시험 픽스처는 공개 종목코드·합성 가격·시각 |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·약화 없음, 고정 값 변경 없음. 시험 diff에서 지운 단언은 `assertThat(p.waitFor(5, MINUTES)).isTrue()` 한 줄이고 같은 한도의 `exited` 단언으로 바뀜(`DayWriterRestartStreamTest.java:199-204`), 시간 초과 때는 더 강해짐(위 N1 확인). 바이트 같음 시험은 단언이 늘었고 픽스처가 소스 순번 복원을 구별하게 됨(P4 잡힘). 새 시험 1건(P10 잡힘) |
| A12 | 통과 | 대응표 #9(게이트웨이 크래시 뒤 재기동 공백)의 공백 시작 규칙이 날짜 넘김 쪽에서 새로 고정됨(P10). 운영 동작 무변경이라 장애 테스트 8종·O1~O8 전제 그대로 |
| A13 | 해당 없음 | 시장 분기 추가 없음 |
| A14 | 통과 | D-119 "후속 반영" 절이 코드·시험과 맞음(Q1 소스 둘 1000·5000부터 `:103-104`, Q2 `:124-148`, N1 `:195-204`, architect 2 `DayWriter.java:132`). 남은 것(tmp 잔존) 기록과 `docs/qa-out-status.md` PR #187 처리 줄(머지 94981d9, 보고서 db86f15)이 저장소 이력과 맞음 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 9회째 성공, 1,678건 실패 0 |
| B6 | 통과 | 운영 변경(예외 문구)에 대한 시험은 없음(Q2, 비차단). 보강 대상 시험은 변이로 효과 확인. 지운 시험·건너뛴 시험 없음(XML 건너뜀 0) |

## 점검표 밖 발견
시나리오(잘못된 주문·중복·보호 공백으로 이어질 수 있는가):
- S1 장중 재기동 다시 쓰기 중 디스크 가득 참: 새 문구가 붙은 `UncheckedIOException`이 나가고 tmp는 지워지며 원본·보관본은 그대로(`앞_녹화를_읽다_실패하면_…` 경로와 같은 catch). 예외 종류가 같아 `MarketEventRecorder`의 "녹화 중단" 처리도 같다. 주문 경로와 무관. 결함 없음.
- S2 날짜를 넘긴 재기동에서 앞 날이 정상 종료를 마지막 수신보다 늦게 남김: 공백 시작이 정상 종료 시각이 됨을 이제 시험이 고정한다(P10·P14 잡힘). 운영 코드는 그대로라 기존 녹화·리플레이 결과는 바뀌지 않는다. 결함 없음.
- S3 Windows PC나 CI에서 작은 힙 자식 JVM이 멈춤: 고치기 전 하네스는 시험 작업을 무기한 붙잡았다(직접 재현, 180초 넘게). 이제 한도 뒤 자식을 강제 종료하고 출력과 함께 실패한다. 시험 전용 변경이며 운영 영향 없음. 결함 없음.
- S4 같은 날 재기동에서 앞 구간 정상 종료 시각이 마지막 수신보다 늦은 경우: 운영 코드는 정상 종료 시각부터 공백을 잡지만 이 경우를 고정하는 시험이 없다(P15 생존, 아래 Q1). 잘못 바뀌어도 공백이 더 일찍 시작해 "녹화 없음" 구간이 넓어지는 쪽이라 녹화를 잃지는 않는다 `[추론]`. 주문 경로와 무관.

권고·정보:
- **Q1(권고, 비차단, main부터 같음)**: 같은 날 재기동 공백 시작을 마지막 구간 끝 무시하고 마지막 수신 시각으로 바꿔도(P15, `DayWriter.java:108`) 저장소 시험이 하나도 실패하지 않는다. 이 PR이 날짜 넘김 쪽(`:197`)에 넣은 Q2 시험의 같은 날 짝이 없다. `RecordingReplayTest#앞_구간_정상_종료_시각이_마지막_수신보다_앞이면_마지막_수신부터_공백을_잡는다`는 반대 경우만 본다. 이 PR의 시험 집합은 main 시험을 모두 포함하므로 main에서도 같은 공백이다 `[추론]`. 같은 날 정상 종료 시각이 마지막 수신보다 늦은 녹화를 다시 열 때 공백 시작이 정상 종료 시각인지 확인하는 시험을 권한다.
- **Q2(권고, 비차단)**: 이 PR의 유일한 운영 변경인 다시 쓰기 쓰기 실패 문구(`녹화 재기동 다시 쓰기 실패: <폴더>`)를 고정하는 시험이 없다(P13 생존). 기존 실패 시험은 읽기 실패(순번 끊김)만 다룬다. 동작 무변경이라 판정에는 영향이 없다. 쓰기 실패를 만드는 시험이 번거로우면 지금처럼 두어도 된다.
- R1(정보): 이 보고서 시점에 PR 브랜치는 main 끝(94981d9) 위에 있어 충돌이 없다. 머지 전에 main이 더 나가면 `docs/qa-out-status.md`·`docs/decisions/D-119-…`에서 다시 확인할 것.

## 판정
승인
