# QA 검토: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험
- 대상: PR #209 / 브랜치 `claude/project-thread-astsat` / 헤드 커밋 71a6178 / 병합 기준 main(f571a38) 대비 diff 전체(커밋 7개, 13파일 +395 −7: 운영 `DayWriter.java` +22 −4·`RecordingMeta.java` +11 −1, 시험 `DayWriterRestartStreamTest.java` +79 −2·새 `RecordingMetaSourcesOrderTest.java` +98, 문서 D-132 새 파일·D-119 1줄·결정 로그 표 1줄·기획서 v14.58·`docs/qa/pending.md` 1줄, 검토 원문 `docs/reviews/pr-209/{architect,architect-2,risk-ops,risk-ops-2}.md`).
- 점검표: A 전체 + B1, B6. `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt:14`)라 실전 관련 PR로 본다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 테스트)는 diff에 없다(`git diff --name-only f571a38 HEAD`).
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 71a6178): 1~13회째 의존성 받기 실패(Maven Central `429 Too Many Requests`, 환경 문제·코드 무관). 20초 간격 재시도 루프 하나, 14회째 **BUILD SUCCESSFUL in 3m 32s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,842건, 실패 0, 오류 0, 건너뜀 0**. `RecordingMetaSourcesOrderTest` 3건, `DayWriterRestartStreamTest` 7건 통과.
  - **JVM 사이 바이트 결정론**: 같은 입력(이름순이 아닌 소스 8개)으로 녹화기를 열고 한 줄 쓰고 닫아 `meta.json`을 남기는 임시 시험을 별도 Gradle 실행(매번 새 JVM)으로 5번 돌려 SHA-256 비교. PR 판 5회 모두 같은 해시(`49b864c13b4e…`). `RecordingMeta`만 `Map.copyOf`로 되돌린 판은 5회 중 4가지 해시. 고치기 전 문제(D-132 "문제")를 재현했고 이 PR이 고쳤음을 확인. 임시 시험은 지움.
  - **변이 시험**(운영 코드 한 곳씩 바꾸고 `:autotrade-marketdata:test`, 매번 원본으로 되돌림, 마지막 `git status` 깨끗):

| 변이 | 내용 | 결과 |
|---|---|---|
| P1 | `RecordingMeta` sources를 `Map.copyOf`로 되돌림 | 잡힘(새 시험 2건) |
| P2 | sources 값 null 확인 삭제(`TreeMap`은 null 값을 받음) | 잡힘(`소스_이름이나_범위가_비면_예전처럼_거절한다`) |
| P3 | sources 키 null 확인 삭제 | 생존(같은 동작: `TreeMap`이 null 키에 `NullPointerException`, 시험은 예외 종류만 봄) |
| P4 | `Collections.unmodifiableMap` 감싸기 삭제(고칠 수 있는 `TreeMap` 노출) | **생존**(R2) |
| P5 | 역순 정렬 `TreeMap` | 잡힘(새 시험 2건) |
| P6 | `rewriteToTmp`가 `tmpOpener` 무시하고 `Files.newOutputStream` | 잡힘(Q2 시험) |
| P7 | 파일 스트림 따로 닫기 되돌림(architect 1 이전 판) | 잡힘(Q2 시험 `fileClosed`) |
| P8 | 같은 날·날짜 넘김 공백 시작을 마지막 수신 시각만(`lastAlive`, PR #192 QA P15) | 잡힘(새 Q1 시험·기존 날짜 넘김 시험) |
| P9 | 다시 쓰기 실패 문구에서 폴더 뺌(PR #192 QA P13) | 잡힘(Q2 시험) |
| P10 | 실패 때 tmp 안 지움 | 잡힘(Q2 시험) |
| P11 | `rewriteToTmp`가 `RuntimeException`을 잡지 않음 | 잡힘(Q2 시험·읽기 실패 시험) |
| P12 | 운영 생성자의 여는 방법을 `CREATE, APPEND`로(남은 tmp 뒤에 이어 씀) | **생존**(Q1) |
| P13 | 다시 쓰기 실패를 `UncheckedIOException` 대신 `IllegalStateException`으로 | 잡힘(Q2 시험) |

  13개 중 11개 잡힘, P3은 같은 동작.
  - **P12가 실제로 위험한지 확인**: 정상 종료 녹화 폴더에 앞 재기동이 남긴 것처럼 `events.jsonl.gz.tmp`를 둔 뒤 같은 날 재기동하는 임시 시험. PR 판 통과(7줄, 마지막 순번 7). P12 판은 다시 읽을 때 `IllegalStateException: 녹화 순번이 끊겼습니다: 기대 6, 실제 1`로 그날 녹화를 읽을 수 없게 됨. 임시 시험은 지움.
  - **실패 경로 추가 확인(임시 시험 4건, 모두 통과 후 지움)**: (a) 줄이 버퍼에 다 들어가 읽기 콜백은 성공하고 닫기(gzip 꼬리)에서 쓰기 실패 → `녹화 파일을 열 수 없습니다`로 멈추고 파일 스트림 닫힘·tmp 없음·원본 바이트 그대로. (b) gzip 머리 쓰기에서 바로 실패 → 같은 결과(파일 스트림 닫힘). 이 경우는 고치기 전 판에서 스트림이 열린 채 남았다 `[추론]`(risk-ops-2 원문과 같은 판단, 코드 구조로 확인). (c) 실패 뒤 같은 날 다시 기동하면 정상으로 이어 씀(5줄 + 재기동 공백 1줄 + 1줄 = 순번 7), 새 메타 sources가 이름순(`A-WS`, `AAA-WS`, `B-WS`…).
  - 호출처·의존: `RecordingMeta`를 만드는 운영 코드는 `DayWriter.writeMeta` 한 곳(`DayWriter.java:294`). `DayWriter`를 만드는 곳은 게이트웨이 녹화 `MarketEventRecorder`와 실험실 입력 기록 `JournalWriter`(PR #192 QA 때와 같음). `sources()`를 읽는 운영 코드는 `DayWriter.java:103`(`TreeMap`에 담음)과 `ReplayCheck.java:375`(`new TreeMap<>(…)`로 찍음)뿐. `RecordingDayAdapter`의 데이터 지문은 이벤트 파일 SHA-256만 쓰고(`RecordingDayAdapter.java:171-177`), 메타 비교는 `equals`라 순서 무관. `LiveWeeklyCheck`의 메타 파일 해시는 한 실행 안 읽기 전·후 비교용(`LiveWeeklyCheck.java:348-353`). `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-risk`·`autotrade-broker-*` 운영 코드는 `marketdata.recording`을 쓰지 않고, 이 diff는 의존을 늘리지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 Guard·설정 로딩·엔진 파일 없음. 빌드에서 `RealTradingGuardTest`·`GuardedOrderGatewayTest` 포함 1,842건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 0줄. 새 타입은 `DayWriter` 안 패키지 전용 `TmpOpener`(`DayWriter.java:160`)뿐, 모듈 의존 추가 없음(`ArchitectureTest`·`ModuleOwnershipTest` 통과) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음. 운영 변경은 녹화 메타 복사와 녹화 tmp 열기·닫기 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음. 녹화 순번·이벤트 파일·`eventsSha256` 무변경(바이트 같음 시험 통과) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진·감시는 바뀐 클래스를 싣지 않음(위 호출처). 게이트웨이 녹화 스레드가 받는 예외 종류 무변경(`MarketEventRecorder.java:161`의 `IOException | RuntimeException`), 실패 때 파일 핸들 누수가 줄어드는 방향(위 (a)(b)) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 무변경 |
| A10 | 통과 | diff 추가 줄의 `appkey|secret|token|password|계좌번호|account`(대소문자 무시) 일치는 risk-ops 원문의 A10 근거 문장 2줄뿐(값 아님). 코드·시험 픽스처 0줄. 시험 픽스처는 공개 종목코드·합성 가격·임의 소스 이름 |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·약화 없음, 고정 값 변경 없음. 시험 diff에서 지운 줄은 주석 2줄뿐(`DayWriterRestartStreamTest.java:103, 136`의 설명 수정). 새 동작(이름순, null 거절, 실패 때 핸들 닫기, 같은 날 공백 시작, 실패 문구)에 실패하는 경우를 포함한 시험이 있고 변이 P1·P2·P5·P7·P8·P9·P13으로 효과 확인 |
| A12 | 통과 | 대응표 #9(게이트웨이·실험실 크래시 뒤 `RECORDER_RESTART` 공백)의 공백 시작 규칙과 다시 쓰기 실패 때 원본·보관본 보존이 그대로이고 이제 같은 날 쪽도 시험이 고정(P8). 장애 테스트 8종·O1~O8 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음 |
| A14 | 통과 | D-132 결정 1~3(이름순 불변 맵, null 거절, 형식 번호·이벤트 파일 무변경)이 `RecordingMeta.java:47-51`과 맞고, "같은 PR의 시험 보강"의 Q1·Q2·architect 1 내용이 `DayWriter.java:137-138`·시험 `:155`, `:182`와 맞음. 결정 로그 표 D-132 행, 기획서 v14.58(MD-09), D-119 후속 줄 일치. D-132의 "실험실 입력 기록도 이름순"은 `JournalWriter`가 같은 `DayWriter`를 쓰므로 맞음 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 14회째 성공, 1,842건 실패 0 |
| B6 | 통과 | 새 동작마다 시험 있음(위 A11). 지운 시험·건너뛴 시험 없음(XML 건너뜀 0). 남은 시험 공백은 Q1·R2(비차단) |

## 점검표 밖 발견
시나리오(잘못된 주문·중복·보호 공백으로 이어질 수 있는가):
- S1 장중 게이트웨이 재기동 다시 쓰기 중 디스크 가득 참: 콜백 중 실패(Q2 시험), 닫기 중 실패(위 (a)), gzip 머리 실패(위 (b)) 모두 tmp를 지우고 원본을 그대로 두며 파일 스트림을 닫는다. `MarketEventRecorder`는 예전과 같은 예외 종류를 받아 "녹화 중단"으로 처리. 다시 기동하면 정상으로 이어 씀(위 (c)). 주문 경로와 무관. 결함 없음.
- S2 이 PR 전에 만든(섞인 순서) `meta.json`이 있는 날 녹화기가 다시 뜸: `DayWriter`가 앞 메타 sources를 `TreeMap`에 담고 새 메타를 이름순으로 다시 쓴다(위 (c)에서 확인). 이벤트 파일·해시·리플레이 결과는 그대로이고, 데이터 지문은 이벤트 파일만 본다(`RecordingDayAdapter.java:171-177`). 예전 결과와 메타 파일 바이트를 비교하면 다를 수 있음은 D-132 "위험과 한계"에 적혀 있다. 결함 없음.
- S3 앞 재기동이 `events.jsonl.gz.tmp`를 남긴 채 멈춘 뒤 같은 날 재기동(D-119 "tmp 잔존"이 이미 인정한 상황): 운영 여는 방법 `Files::newOutputStream`(`DayWriter.java:75`)은 옵션 없이 열어 기존 tmp를 비우고 쓰므로 PR 판은 정상(임시 시험 통과). 그러나 이 성질을 고정하는 시험이 없고, 여는 방법이 이어 쓰기로 바뀌면 그날 녹화가 순번 끊김으로 읽히지 않는다(P12 생존, 위 직접 확인). 이번 PR이 여는 방법을 생성자 인자로 뺀 만큼 바뀌기 쉬운 자리가 생겼다 `[추론]`. 아래 Q1.
- S4 null 소스 이름·범위: 예전과 같이 `NullPointerException`으로 거절(P2 잡힘, P3 같은 동작). 녹화기 `write`는 `sourceId`를 그대로 `putIfAbsent`하므로 null 소스는 다음 `writeMeta`에서 예외 — 이 PR 전과 같은 동작이다(`Map.copyOf`도 null 거절) `[추론]`.

권고·정보:
- **Q1(권고, 비차단)**: 같은 날 재기동 때 남아 있던 tmp를 비우고 다시 쓰는지 고정하는 시험이 없다. 운영 생성자의 여는 방법을 `CREATE, APPEND`로 바꿔도(P12) 저장소 시험이 하나도 실패하지 않지만, 실제로는 그날 녹화가 `녹화 순번이 끊겼습니다: 기대 6, 실제 1`로 읽히지 않게 된다(직접 확인). 고치기 전에는 여는 방법이 `rewriteToTmp` 안에 박혀 있어 같은 공백이 main부터 있었다 `[추론]`. 정상 종료 녹화 폴더에 오래된 `events.jsonl.gz.tmp`를 두고 재기동한 뒤 줄 수·순번이 맞는지 보는 시험을 권한다.
- **R2(정보, 비차단)**: `RecordingMeta.sources()`가 고칠 수 없는 맵이라는 성질은 시험이 고정하지 않는다(P4 생존). 지금 운영 호출처는 읽기만 하므로 동작 영향은 없다. 시험 하나(`sources().put` → `UnsupportedOperationException`)면 충분하다.
- R1(정보): PR이 `mergeable_state: dirty`다. `git merge-tree --write-tree origin/main HEAD`(main 5326d94)에서 `docs/decisions/README.md`, `docs/qa/pending.md` 두 곳이 충돌한다(둘 다 표에 줄 추가끼리). 운영 코드 충돌은 없다. 충돌을 풀면 두 줄을 모두 남기고, 푼 뒤 CI를 다시 확인할 것.
- 확인 못 함(판정 밖): Windows 실행. 이 검토는 Linux에서만 돌렸다. 사용자 PC에서 `./gradlew :autotrade-marketdata:test` 한 번 실행을 권한다(개발 쪽 원문도 같은 권고).

## 판정
승인
