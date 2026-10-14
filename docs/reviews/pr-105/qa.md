# QA 검토: 리플레이 점검의 장 시간 보호와 정지 요청 파일 관문(D-085, Q-050)
- 대상: PR #105 / 커밋 0a23620e0089e119c18dfd3068b3897ea5c29476 / main 대비 diff 전체(merge-base 59e9eb0, 현재 origin/main 4b24550)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. broker-and-fault-design 5.2 정지 순서 0번과 장애표 #10, 기획서 RSK-07을 바꾼다(지침 8장 2번). 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-105/architect.md`, `risk-ops.md`), D-085 문서의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 15파일, +533 −36. 운영 코드 3파일(`ReplayCheck.java`, 새 `ReplayCheckGate.java`, 새 `ReplayCheckMain.java`, 모두 `autotrade-app-gateway`), `autotrade-app-gateway/build.gradle.kts`, 시험 2파일(`ReplayCheckTest.java` +173 −8, 새 `StopRequestPathTest.java`), 문서 9파일
  - 보호 영역 변경 여부: `autotrade-risk`, `autotrade-broker*`, `autotrade-app-engine` diff **0줄**. `autotrade-architecture-test`는 새 파일 `StopRequestPathTest.java` 추가뿐(`ArchitectureTest`·`ModuleOwnershipTest` 변경 없음). `RealTradingGuardTest`·`GuardedOrderGatewayTest` 변경 없음. 추가된 `@Disabled`·`@Tag` 없음. 바뀐 기존 시험 4건은 진입 경로를 고정 시각(토요일)·임시 설정 폴더로 부르게 바꾼 것이고 기대값(종료 코드 0·2·3, 보고서 내용)은 그대로
  - `./gradlew build --rerun-tasks`(head 0a23620, 별도 작업 트리): Maven Central 429로 의존성 받기 실패 12회 뒤 13회째 **BUILD SUCCESSFUL**(429는 빌드 실패로 보지 않음). 시험 결과 XML 합계 tests **1,111**, failures 0, errors 0, skipped 0
  - CI(head 0a23620, check-runs 3건): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - **main 병합 시도**(`git merge origin/main` 4b24550, 작업 트리에서 시도 후 abort): **충돌 2파일** `docs/decisions/README.md`(main의 D-087·D-088 행과 이 PR의 D-085 행), `docs/spec/autotrade-spec.md`(머리 버전 main v14.31 대 이 PR v14.29, 변경 이력 맨 윗줄 v14.31 대 v14.29). 그래서 병합 빌드는 돌리지 못했다
  - **QA 탐침 A(실제 Gradle 작업, `AUTOTRADE_CONFIG_DIR`=임시 폴더, 2026-10-14 17:04Z = 뉴욕 13:04 EDT 미국 정규장)**:
    - A1 `replayCheck --args="2026-10-12"` → `리플레이 점검을 시작하지 않습니다: US 장 시간 보호 구간(08:30~16:30 America/New_York)`, Gradle 출력 `finished with non-zero exit value 4`. 정지 요청 파일 확인 위치(임시 폴더)가 표준 오류에 출력됨
    - A2 `-PallowDuringSession=false` → `--allow-during-session은 값이 없거나 true여야 합니다` (입력 오류, 해제되지 않음)
    - A3 `-PallowDuringSession` → `장 시간 보호 해제됨` 출력 뒤 녹화 없음으로 `녹화 확인 실패`(관문 통과 확인)
    - A4 `experiment-state/stop-request` 파일을 만들고 `-PallowDuringSession` → `감시 프로세스가 자원 부족으로 정지를 요청했습니다(…, 만든 시각 …)`로 시작하지 않음. 파일은 남아 있음
    - A5 저장소 루트 `gradle.properties`에 `allowDuringSession=true` 한 줄 → 명령줄 옵션 없이도 `장 시간 보호 해제됨`(표준 오류 한 줄뿐, 아래 R1). 탐침 뒤 파일 삭제
  - **QA 탐침 B(임시 시험 `QaProbe105Test`, 실제 `ReplayCheckGate`·실제 파일, 확인 뒤 삭제, 저장소에 남기지 않음)**: 합성 녹화 500,000줄(정상 종료)로 전체 점검 7,057ms, `RecordedDay.open` 한 번 1,834ms. 실행 3,528ms 시점에 다른 스레드가 정지 요청 파일을 만들자 4,910ms에 종료 코드 4로 멈추고 보고서 파일이 생기지 않음(멈춤 지연 약 1.4초, 가장 긴 무확인 구간은 `open` 한 번)
  - **변이 탐침**(`:autotrade-app-gateway:test`, 하나씩 넣고 확인 뒤 원복, 작업 트리 깨끗):
    - M1 종목 수집 루프의 줄 간격 확인 제거 → 1건 FAILED(잡힘)
    - M2 리플레이 콜백의 줄 간격 확인 제거 → 1건 FAILED(잡힘)
    - M3 `!Files.notExists` → `Files.exists`(판단 불가를 요청 없음으로) → 1건 FAILED(잡힘)
    - M4 해제 옵션이면 정지 요청 파일도 무시 → 2건 FAILED(잡힘)
    - M5 `--allow-during-session=false`도 해제로 받음 → 1건 FAILED(잡힘)
    - M6 명령 시작 때 관문 확인 제거 → 1건 FAILED(잡힘)
    - M7 종목 수집 뒤 확인 제거 → 2건 FAILED(잡힘)
    - M8 회차 시작 확인 제거 → 2건 FAILED(잡힘)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Guard·실전 엔진 코드 변경 없음(diff 0줄) |
| A2 | 통과 | 주문 출구·`GuardedOrderGateway`·`ArchitectureTest` 변경 없음. 빌드에서 ArchUnit 통과. `ReplayCheckMain`은 `NO_SYSTEM_TIME` 예외 이름 규칙(`*Main`) 안에서 `Clock.systemUTC()`를 만든다(빌드 통과로 확인) |
| A3 | 해당 없음 | 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 리플레이 점검이 장 시간 보호 구간(국내·미국 정규장 60분 전~폐장 30분 뒤, 캘린더 범위 밖도)과 정지 요청 파일에서 시작하지 않고 실행 중에도 멈춘다(탐침 A1·A4·B, 시험 9건, 변이 M1~M8 모두 잡힘). 정지 요청은 해제 옵션으로 풀리지 않고(A4, M4), 판단 불가도 멈춘다(M3). 남는 공백: `RecordedDay.open` 한 번 동안 확인 없음(탐침 B에서 500k줄 1.8초, D-085 위험 절에 적힘), Gradle 컴파일은 관문 앞에서 돈다(D-085에 적힘), 해제 값이 `gradle.properties`로 조용히 들어온다(R1) |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 새 코드·시험·문서에 키·계좌번호·토큰 없음. 출력은 설정 폴더 경로와 파일 시각뿐 |
| A11 | 통과 | 보호 시험 삭제·약화 0건. 새 시험 9건 + `StopRequestPathTest` 1건, 변이 M1~M8을 모두 잡는다 |
| A12 | 통과 | 장애표 #10 감지 수단에 "리플레이 점검 4"를 더하고 5.2 0번에 리플레이 점검과 확인 지점을 적었다(`docs/team/broker-and-fault-design.md` diff). 장애 테스트 8종의 전제(실전 엔진·Guard 동작)는 바뀌지 않음 |
| A13 | 통과 | 장 시간 판정은 기존 `SessionProtection`(거래소 현지 시간대)을 그대로 쓴다. 미국 장 탐침 A1(08:30~16:30 America/New_York)과 시험 `미국_장_시간과_캘린더_수록_범위_밖도_멈춘다` 통과 |
| A14 | **실패** | (1) **main과 충돌**: `docs/decisions/README.md`(D-087·D-088 행 자리), `docs/spec/autotrade-spec.md`(머리 버전 v14.31 대 v14.29, 변경 이력 첫 줄). (2) **충돌 없이 어긋나는 곳**: PR 브랜치가 갈라진 뒤 main에 들어온 `docs/runbooks/README.md`(PR #103)가 리플레이 점검을 옛 동작으로 적는다. `:46` "녹화한 날은 `replayCheck` … 이 명령들은 장 시간 보호를 스스로 확인하지 않는다", `:61` 종료 코드 표의 `replayCheck` 4 칸 "-". 이 PR을 그대로 병합하면 D-085(종료 코드 4, 장 시간 보호 확인)와 실행 매뉴얼이 다르다. D-085 결정 문서·`collector.md`·`stage-2-real-recording.md`·장애 설계·기획서 RSK-07 사이는 서로 일치함을 대조했다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행 성공(1,111건, 실패·오류·건너뜀 0) |
| B6 | 통과 | 새 동작에 시험 있음(A11). 건너뛰거나 지운 시험 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음(`build.gradle.kts` 변경은 진입 클래스와 인자 전달뿐, 의존 추가 0건) |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **장중에 실수로 리플레이 점검을 돌리면 실전 엔진과 자원을 다투는가**: 옵션 없이 실행하면 녹화·종목명 표를 읽기 전에 종료 코드 4로 끝난다(탐침 A1, 시험, 변이 M6). Gradle 데몬·컴파일 부하는 관문 앞이라 남는다(D-085 위험 절, runbook에 "보호 구간에는 시작하지 않는다"). 주문 경로와 무관하다.
2. **감시가 정지를 요청했는데 점검이 계속 도는가**: 해제 옵션이 있어도 시작하지 않고(A4·M4), 실행 중이면 다음 확인 지점에서 멈춘다(탐침 B: 파일 생성 뒤 약 1.4초, 보고서 안 씀). 가장 긴 무확인 구간은 회차마다 `RecordedDay.open` 한 번(합성 50만 줄 1.8초)이다. 파일 존재를 판단할 수 없을 때도 멈춘다(M3).
3. **해제 옵션이 의도와 달리 켜져 보호가 꺼지는가**: `-PallowDuringSession=false`는 입력 오류(A2·M5)라 1차 #52 F2 같은 존재 판정 문제는 없다. 다만 `gradle.properties`(저장소 루트나 사용자 홈 `~/.gradle/`)의 `allowDuringSession=true`는 매 실행 조용히 보호를 끈다(A5). 실험 명령(D-063)과 같은 성질이고 D-085 위험 절에 적혀 있다(R1).
4. **정지 요청 파일 위치가 어긋나 감시 요청을 못 보는가**: `StopRequestPathTest`가 실험 명령·실험실 세션·리플레이 점검 세 상수가 같음을 고정한다. 감시 쪽 생성 코드는 아직 없어(D-058 ④) 감시 상수와의 대조는 감시 구현 PR 몫이다.

## 점검표 밖 발견
- **R1 `gradle.properties`의 `allowDuringSession=true`가 매 실행 장 시간 보호를 조용히 끈다**(권고, 탐침 A5): 해제 알림은 표준 오류 한 줄뿐이고 보고서 파일에 남지 않는다. 실험 명령(D-063)과 공통이다. 해제했을 때 보고서 머리에 "장 시간 보호 해제 실행"을 남기거나, 해제 값의 출처가 명령줄(`-P`)이 아니면 거부하는 방안을 7단계 절차서 전에 검토하길 권한다.
- **R2 보고서 계산이 끝난 뒤 파일을 쓰기 전에는 확인하지 않는다**(참고): 그 사이 정지 요청이 와도 보고서를 쓰고 정상 코드로 끝난다. 남은 일이 파일 쓰기 하나라 자원 영향은 없다.
- **R3 `RecordedDay.open` 구간 무확인**(참고, D-085에 적힘): 실녹화 52만 줄 첫 점검 때 시간을 재 두길 권한다.

## 판정
수정 필요 (A14: main 충돌 `docs/decisions/README.md`·`docs/spec/autotrade-spec.md`, 병합 뒤 `docs/runbooks/README.md:46`·`:61`이 D-085와 어긋남. 코드·시험은 위 확인 범위에서 결함을 찾지 못했다. 권고 R1, 참고 R2·R3. 실전 관련이라 머지는 사용자)
