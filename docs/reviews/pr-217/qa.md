# QA 검토: 녹화 저장 시험 보강(같은 날 재기동 tmp 비움·sources 불변·비정상 종료 흉내 단언, PR #209 QA 후속)
- 대상: PR #217 / 브랜치 `claude/project-thread-x43usp` / 헤드 커밋 14ed9d4 / 병합 기준 main(c4d6c9c) 대비 diff 전체(커밋 4개, 7파일 +135 −2: 시험 `DayWriterRestartStreamTest.java` +38 −2·`JournalWriterTest.java` +4·`RecordingMetaSourcesOrderTest.java` +18, 문서 D-132 후속 반영 절 +8·`docs/qa/pending.md` 1줄, 검토 원문 `docs/reviews/pr-217/{architect,risk-ops}.md`). 운영 코드(`src/main`) 변경 0줄. 현재 main(3e25539)과 `git merge-tree` 충돌 없음(main이 그 사이 바꾼 4파일은 marketdata 밖).
- 점검표: A 전체 + B1, B6. `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt:14`)라 실전 관련 PR로 본다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 테스트)는 diff에 없다(`git diff --name-only origin/main...pr-217`).
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon`(헤드 14ed9d4): 1~9회째 의존성 받기 실패(Maven Central `429 Too Many Requests`·`Could not GET/resolve`, 환경 문제·코드 무관). 20초 간격 재시도 루프 하나, 10회째 **BUILD SUCCESSFUL in 4m 29s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,879건, 실패 0, 오류 0, 건너뜀 0**. `autotrade-marketdata` 163건(`DayWriterRestartStreamTest` 8, `JournalWriterTest` 6, `RecordingMetaSourcesOrderTest` 4).
  - **변이 시험**(한 곳씩 바꾸고 `:autotrade-marketdata:test --offline`, 매번 `git checkout`으로 되돌림, 마지막 원본 재실행 163건 통과·`git status` 깨끗):

| 변이 | 내용 | 결과 |
|---|---|---|
| P12 | 운영 생성자의 tmp 여는 방법을 `CREATE, APPEND`(PR #209 QA 생존 변이) | 잡힘(`같은_날_재기동은_앞_재기동이_남긴_tmp를_비우고_다시_쓴다`) |
| Q1 | tmp 여는 방법을 `CREATE, WRITE`(자르지 않고 앞에서부터 덮어씀, 남은 tmp 꼬리가 남음) | 잡힘(같은 시험) |
| Q2 | 같은 날 재기동 때 남은 tmp가 있으면 그것을 먼저 `events`로 옮기고 시작(남은 tmp를 믿음) | **생존**(아래 권고 Q1) |
| Q3 | `RecordingMeta` sources를 복사 없이 `Collections.unmodifiableMap(sources)`(만든 쪽 맵의 보기) | 잡힘(`소스_맵은_고칠_수_없다`, `만든_순서와_상관없이_소스는_이름순이다`) |
| T5 | `JournalWriterTest.crash`가 메타를 되돌리지 않음(이벤트만 되돌림) | 잡힘(crash 쓰는 3건) |
| T6 | `crashedDay`가 메타를 되돌리지 않음 | 잡힘(crashedDay 쓰는 2건) |
| T7 | `JournalWriterTest.crash`가 이벤트를 되돌리지 않음(메타만 되돌림) | 잡힘(3건) |

  개발 쪽이 적은 P4(`unmodifiableMap` 삭제)·T2(정상 종료)는 architect·risk-ops 원문이 직접 확인했다고 적었고, 이 검토에서는 같은 성질을 Q3·T5~T7로 다시 확인했다. 7개 중 6개 잡힘.
  - **시나리오 확인(임시 시험 `QaPr217ScenarioTest` 3건, PR 판 모두 통과 후 지움)**:
    - S1 정상 종료 폴더에 **앞 3줄만 담은** 닫힌 gzip tmp를 남기고 같은 날 재기동 → `lastSeq` 5, 원본 5줄 그대로, 순번 1부터 끊김 없음. Q2 변이 판은 `IllegalStateException: 녹화 파일 SHA-256이 메타와 다릅니다`로 녹화기가 열리지 않음(조용한 손실은 아니고 녹화 정지).
    - S2 200KB 무작위 바이트(gzip 아님, 결과보다 큼) tmp를 남기고 재기동 → tmp 없어짐, `truncated=false`, 표시 없는 경계 없음, 순번 연속. Q2 변이 판은 S1과 같은 오류로 정지.
    - S3 비정상 종료 폴더(flush 뒤 사본 되돌림, `complete=false` 확인) + 같은 내용 tmp + 같은 날 두 번 재기동 → 최종 `complete=true`, `truncated=false`, 순번 1~11 연속, 보관본 `events.jsonl.gz.before-segment-2` 하나만 남음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 Guard·설정 로딩·엔진 파일 없음. 빌드에서 `RealTradingGuardTest`·`GuardedOrderGatewayTest` 포함 1,879건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 0줄. 운영 코드 변경 없어 의존 추가 없음(`ArchitectureTest`·`ModuleOwnershipTest` 통과) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음. 녹화 순번 동작 무변경(운영 코드 0줄), 새 시험이 같은 날 재기동 순번 연속을 고정 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 바뀐 것은 `autotrade-marketdata/src/test`뿐이라 엔진·게이트웨이·감시 런타임 클래스패스 무변경 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 무변경 |
| A10 | 통과 | diff 7파일 직접 읽음. 키·계좌번호·토큰 없음(시험 픽스처는 `KIWOOM-WS`·`KRX_REALTIME`·종목 코드·임시 폴더) |
| A11 | 통과 | 보호 테스트 변경 없음, 고정 값 갱신 없음. 바뀐 시험은 단언·시험 추가뿐(−2줄은 주석 교체, `@Disabled` 없음, 건너뜀 0). 새 시험이 P12·Q1·Q3·T5~T7 변이에서 실패함을 직접 확인 |
| A12 | 통과 | 장애 대응표·장애 테스트 관련 코드·문서 변경 없음. 새 시험은 D-119 "tmp 잔존" 때 현재 동작(`DayWriter.java:75` `Files::newOutputStream`이 남은 tmp를 자르고 씀)을 고정할 뿐 |
| A13 | 해당 없음 | 미국 시장 경로·`MarketRules` 변경 없음 |
| A14 | 통과 | D-132 "후속 반영(시험만, 운영 코드 무변경)" 절(`D-132-recording-meta-sources-order.md:60-66`)이 실제 시험·주석 변경과 맞음. 동작 변경 없어 새 결정·기획서 변경 불필요 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 10회째 BUILD SUCCESSFUL, 1,879건 실패 0(앞 9회는 429 받기 실패) |
| B6 | 통과 | 새 시험 2건과 흉내 함수 단언 2곳 추가, 지운 시험·건너뛴 시험 없음. PR #209 QA Q1(P12)·R2(P4)·R3(정상 종료로 바꿔도 생존) 모두 이제 잡힘 |

## 점검표 밖 발견
- 권고 Q1(비차단): 새 tmp 시험은 남은 tmp를 원본과 **같은 바이트**로 만든다(`DayWriterRestartStreamTest.java:196` `Files.copy(events, tmp)`). 그래서 "남은 tmp를 먼저 events로 옮기는" 변경(Q2 변이)이 시험 전체에서 생존한다. 실제로 그런 코드가 들어오면 앞 재기동이 tmp를 쓰다 멈춘 경우(부분 tmp) 녹화기가 SHA-256 불일치로 열리지 않아 그날 녹화가 멈춘다(S1·S2에서 직접 확인). 지금 운영 코드에는 그 분기가 없고, 남은 tmp 앞부분만 담거나 깨진 tmp로 시험하면 잡힌다(S1·S2). architect 원문 1과 같은 지적.
- 권고 Q2(비차단, 범위 밖): `RecordingReplayTest#crash`는 같은 흉내 방식인데 이번 R3 단언(`complete=false`·`truncated`)을 받지 않았다. 세 흉내 함수를 맞추려면 같은 세 줄을 넣으면 된다(architect 원문 3과 같음).
- R1(사용자 확인): 이 검토는 Linux 컨테이너에서만 실행했다. D-132가 적은 대로 사용자 PC Windows에서 `./gradlew :autotrade-marketdata:test` 통과(163건)는 머지 전에 사용자가 확인해야 한다. 새 Q1 시험은 닫힌 파일만 복사하고 자식 JVM을 쓰지 않아 Windows 핸들 문제를 새로 만들지 않는다 `[추론]`.
- 잘못된 주문·중복 주문·보호 공백 시나리오: (1) 시험만 바뀌어 실전 엔진·주문 경로에 닿는 실행 코드가 없다. (2) 녹화 재기동 tmp 잔존(정상·비정상 종료, 부분·깨진 tmp, 두 번 재기동)에서 순번 끊김·중복·잘림 없음(S1~S3). (3) `RecordingMeta.sources()`를 만든 쪽·받은 쪽 어디서도 고칠 수 없어 메타 공유로 인한 순서·내용 변화 없음(Q3 변이 잡힘). 셋 다 문제 없음.

## 판정
승인
