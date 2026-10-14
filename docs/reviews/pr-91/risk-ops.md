# risk-ops 검토: PR #87 QA 권고 후속(디스크 하한 1GB 미만 거부, 날짜 넘긴 비정상 종료 시험, ADR-010 채택)
- 대상: PR #91 / 브랜치 `claude/project-thread-go4oua` / 커밋 `0e1ba9d` / `git diff origin/main...HEAD` 전체(main `40388a4` 대비 8파일, +53 −9)
- 점검표: A
- 직접 실행한 것:
  - 저장소 폴더 `./gradlew build --no-daemon`: `:autotrade-app-lab:compileJava`가 Maven Central 429(Too Many Requests)로 의존성을 받지 못해 실패. 코드 문제가 아닌 환경 오류다. 다른 검토자의 빌드와 `build/` 출력이 겹치지 않도록 `0e1ba9d`를 검토용 폴더(scratchpad)에 복제해 `./gradlew --no-daemon build --rerun-tasks --continue`를 돌렸다. 첫 번째는 `:autotrade-architecture-test`에서 같은 429로 실패했고, 60초 뒤 두 번째는 **BUILD SUCCESSFUL, 129개 작업 모두 실행**. 저장소 밖 미러 init 스크립트는 쓰지 않았다(권한 거부).
  - 모듈별 테스트(XML 결과 집계, 실패 0, 오류 0, 건너뜀 0): risk 55(RealTradingGuardTest 31, GuardedOrderGatewayTest 8), architecture-test 31(ArchitectureTest 18, ModuleOwnershipTest 13), app-engine 48, app-gateway 40, app-lab 146(LiveLabServiceTest 6), sim 200(LiveSessionTest 10), marketdata 129, app-collector 61, app-experiment 17, app-watchdog 1, broker-kis 30, broker-kiwoom 51, broker-toss 90, broker-api 1, config 30, control 20, domain 31, selection 34, strategy 43.
  - 변이 확인: 복제본에서 `LiveLabService`의 새 검사를 원래 `if (minFreeGb < 0)`로 되돌리고 `:autotrade-app-lab:test --tests '*LiveLabServiceTest'`를 돌렸다 → `diskFloorMustBeAtLeastOneGb()` FAILED(6개 중 1개 실패). 새 테스트가 회귀를 잡는다. 확인 뒤 복제본을 되돌렸다.
  - 코드 확인: diff 전체, `LiveLabService` 생성자 두 개와 호출처(`LabWebConfiguration.java:99-112`, `@Bean` 즉시 생성, `autotrade-app-lab/src/main`에 lazy 설정 없음), `LiveSessionTest` 바뀐 시험과 도우미, 감시(`WatchdogMain`, 실험실 재기동 코드 없음), 문서(broker-and-fault-design 5.2 2번·장애표 #9·#10, D-076, ADR-010 2.7·6장, config-keys, 절차서, qa-out-status), PR #87 risk-ops·QA 검토 원문.
  - 추가 줄에서 appkey/secret/token/password/account/계좌번호/계좌번호 형식 grep → 0건. 바뀐 파일 중 risk·engine·architecture-test·broker·watchdog·gateway·control 경로 0건.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·RealTradingGuard·설정 로더 변경 없음(`git diff --name-only`). `RealTradingGuardTest` 31건 직접 실행 통과 |
| A2 | 통과 | 실전 주문 출구·Guard 팩토리·ArchUnit 규칙 파일 변경 없음. ArchitectureTest 18건·ModuleOwnershipTest 13건 통과. 바뀐 운영 코드는 `LiveLabService` 생성자 검사 한 곳뿐이고 주문 호출 없음 |
| A3 | 해당 없음 | 증권사 선택·주문 라우팅 코드 변경 없음(바뀐 운영 코드는 실험실 설정 검사뿐) |
| A4 | 해당 없음 | 실전 주문 송신·타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음. 실시간 세션 블랙리스트 시험 `blacklistedSymbolIsNeverBoughtInTheLiveSession` 그대로 통과 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 통과 | 이 PR은 정지 순서 5.2 2번(실험실이 먼저 물러남)을 강화한다: `autotrade.lab.live.min-free-gb`가 1 미만이면 하한이 꺼져 실험실 입력 기록이 실전 엔진 기록과 같은 디스크를 채울 수 있었는데, 이제 생성자에서 거부한다(`LiveLabService.java:107-113`). 위 한도 1,000,000GB는 `minFreeGb * 1024^3` long 넘침을 막는다(1e6×2^30 ≈ 1.07e15 < Long.MAX_VALUE) `[사실]` 계산. `LabWebConfiguration.java:99-112`의 빈은 즉시 생성이라 범위 밖 값이면 실험실 프로세스만 뜨지 않는다 `[추론]`(Spring 기본 즉시 초기화, lazy 설정 0건. 컨텍스트 기동 시험은 없음). 실전 엔진은 실험실을 의존하지 않고(`verifyProcessIsolation` 통과), 감시는 실험실을 재기동하지 않아(`WatchdogMain`) 기동 실패가 재기동 반복으로 번지지 않는다. 기본값 20과 기본 생성자(`LiveLabService.java:88-91`)는 그대로. `diskFloorMustBeAtLeastOneGb`(0, −1, 상한+1 거부) 통과, 변이 확인으로 실패 검출 확인 |
| A9 | 통과 | 실전 엔진·보호 시세 경로·서버 측 손절·게이트웨이 코드 변경 없음(바뀐 파일 목록) |
| A10 | 통과 | 추가 줄 비밀값 grep 0건. 새 예외 메시지에는 설정 키 이름과 숫자만 들어간다(`LiveLabService.java:110-111`) |
| A11 | 통과 | 보호 테스트(ArchitectureTest, RealTradingGuardTest, GuardedOrderGatewayTest, 장애 테스트) 변경 없음. 기존 `crashWithLostTailAndUnclosedMetaStillReplaysTheSame`은 도우미 `crashAndResume(events, crashAt, crashAt)`로 옮겨졌고 같은 절차(꼬리 20바이트 자르기, `restored`가 1 이상·crashAt 이하, 리플레이 확인 `same`)를 그대로 거친다. 이어 받기마다 `accept == ACCEPTED`를 새로 확인해 오히려 강해졌다. 임시 폴더를 `createTempDirectory`로 바꾸고 원래 세션 폴더를 지우는 것은 한 시험 안에서 두 번 돌리기 위한 정리이며, 비교 대상은 여전히 복사본(`copy`)이다. 새 동작에는 실패 경우 시험이 있다(`diskFloorMustBeAtLeastOneGb`, `crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame`: 1일차 중간·끝 종료 뒤 2일차 재개) |
| A12 | 통과 | 장애표 #10·5.2 2번 문구("하한 미만이면 멈춤, 기본 20GB")의 전제를 깨지 않고 0으로 끄는 길만 막았다. 대응표 행 수·장애 테스트 8종 전제 변경 없음. 대응표에 범위(1~)를 더 적지 않았지만 설정 키 문서(`config-keys.md:43`)와 절차서(`stage-6-live-session.md:45`)에 적혀 있어 운영 기준이 비지 않는다 |
| A13 | 해당 없음 | 시장별 코드 변경 없음. 새 시험의 날짜 경계는 `Asia/Seoul`(`LiveSessionTest` `SEOUL`)로 계산 |
| A14 | 통과 | ADR-010 상태 "채택(D-076, PR #87 머지 `40388a4`)"과 ADR 목록 표가 맞고, `git rev-parse origin/main` = `40388a4`로 머지 커밋 일치. QA PR #87 R2·R4·R5·R6 처리가 `docs/qa-out-status.md`에 기록됨. D-076 7번("디스크 여유(기본 20GB)")과 바뀐 동작이 어긋나지 않아 새 결정은 필요 없다 `[추론]`(설정 검사 강화, 동작 기준 불변) |

## 점검표 밖 발견
- N1 (문서, 차단 아님): ADR-010 6장 검증 목록의 시험 수가 낡았다. `LiveSessionTest`(9) → 실제 10, `LiveLabServiceTest`(5) → 실제 6(`docs/adr/ADR-010-live-feed-and-session.md:119,120`). 새 시험 두 개(`crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame`, `diskFloorMustBeAtLeastOneGb`)를 목록에 넣고, 2.7(`:72`)에도 "1~1,000,000GB, 범위 밖이면 실험실이 뜨지 않음"을 한 줄 적을 것을 권한다. 다음 문서 PR에서 처리해도 된다.
- N2 (참고): "범위 밖이면 실험실이 뜨지 않는다"(config-keys, 절차서)는 즉시 생성 빈이라는 코드로만 뒷받침되고 Spring 컨텍스트 기동 시험은 없다. 실험실 기동 실패는 실전에 영향이 없으므로 차단하지 않는다.
- N3 (참고): 절차서 3장에 추가된 "되살리기 시간 기록"(QA R6)은 운영 기록일 뿐 정지 지연 자체를 줄이지 않는다. 6단계 첫 주 측정값이 크면(예: 하루치 되살리기가 수십 초 이상) 기록일 안에서도 정지 요청을 보도록 바꾸는 것을 그때 다시 검토한다.

## 판정
승인
