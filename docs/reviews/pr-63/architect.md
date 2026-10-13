# architect 검토: runbook 3.6 수능 연기 때 옛 날짜는 미확인 특별 운영일로 남기기, 실행 중 명령 정지, 반영 작업 시간 제한 (PR #62 QA Q1~Q3)
- 대상: PR #63 / 브랜치 `claude/project-thread-h3g45k` / 커밋 575d995a9a19b63cc25c1b067b9ed3852e7e7682(GitHub REST `pulls/63` head SHA와 같음, open) / `git diff origin/main...HEAD` 전체(merge-base 633eea202e5b52e9163b59ead5255cd284dcddc1 = 현재 origin/main)
- 점검표: B 전체
- PR 본문, 커밋 메시지, 메인 Claude의 설명은 판정 근거로 쓰지 않았다. 저장소 코드와 직접 실행한 빌드로만 판정했다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 2파일 +3 −2. `docs/runbooks/collector.md`(3.6의 2번·6번 각 1줄 교체), `docs/decisions/D-064-krx-2027-calendar.md`(갱신 이력 +1). REST `pulls/63/files`도 같은 2개. `*.java`·`*.json`·`*.kts`·스키마·설정 키 변경 0건. base가 origin/main과 같아 머지 결과 트리 = PR head 트리
  - `./gradlew build --no-daemon`: 공용 작업 트리가 아닌 스크래치 폴더에 따로 clone해 `git checkout 575d995` 뒤 실행
    - 1회차: **BUILD FAILED** (2m 2s), `:autotrade-app-collector:test`에서 "Could not write XML test results for com.autotrade.app.collector.CollectorMainTest" — 테스트 실패가 아니라 결과 XML 파일 쓰기 실패. 같은 clone에서 `:autotrade-app-collector:test --stacktrace`를 다시 돌리니 BUILD SUCCESSFUL(27s). 이 PR은 코드·테스트를 바꾸지 않으므로 검토 환경의 일시적 파일 쓰기 오류로 본다 `[추론]`
    - 2회차: `./gradlew clean` 뒤 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL in 2m 56s**, 종료 코드 0, 130 tasks(107 executed, 23 up-to-date는 build-logic과 일부 compileJava/jar). 22개 모듈 `:test` 태스크 모두 실행(UP-TO-DATE 아님). 테스트 결과 XML 합계 tests 897, failures 0, errors 0, skipped 0
  - CI(head 575d995, REST check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 0건
  - 새 문장 사실 대조(코드):
    - 2번 "미확인 특별 운영일은 정규장과 합쳐 보호하므로(`ExchangeCalendar.protectionSession`) … 08:00~17:00이 덮인다": `ExchangeCalendar.java:137-155`에서 `verified: false` 특별 운영일은 개장 = min(특별 개장, 정규 09:00), 폐장 = max(특별 폐장, 정규 15:30)로 합친다(`:148-153`). 수능일 10:00~16:30이면 09:00~16:30, `SessionProtection.java:21-22,64-65`의 60분 전·30분 뒤를 더해 08:00~17:00. `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`(08:00 Inside, 16:59 Inside, 17:00 Clear)가 같은 값을 지키고 위 빌드에서 통과. 거래소가 옛 날짜를 정규장(필요 보호 08:00~16:00)으로 되돌리든 10:00~16:30(필요 09:00~17:00)을 유지하든 08:00~17:00이 둘 다 덮는다 → 맞다
    - 2번 "옛 시험일은 지우지 않고": 캘린더 생성자는 같은 날짜 중복·휴장일 겹침만 거부하고(`ExchangeCalendar.java:56-61`), 특별 운영일 두 개(옛·새)를 함께 두는 데 제약이 없다 → 데이터로 표현 가능
    - 2번 "(이미 `true`였으면 `false`로 되돌린다)", "두 날짜 모두 출처 키를 … 새 키로": `verified`를 바꿀 때 출처 키를 바꿔야 `calendar_digest`가 달라진다는 3번 규칙(`collector.md:69`, `SnapshotSelectionData.calendarDigest`)과 맞다
    - 2번 "공지 때 이미 돌고 있는 수집기·실험 명령(예약 실행 포함)은 옛 날짜 08:00 전에 멈춘다": 실행 중 판정은 `RunGuard.checkSession`(`RunGuard.java:58-77`, "실행 중 반복해서 부른다")과 `ExperimentGate`(`ExperimentGate.java:60`)가 하지만, 둘 다 프로세스 시작 때 읽은 캘린더(`CollectorMain.java:70`, `ExperimentMain.java:64`의 `ExchangeCalendar.bundled`)로 판정한다. 확인된 옛 시험일이면 실행 중인 프로세스는 09:00에야 멈추므로 사람 절차가 필요하다는 전제가 맞다
    - 6번 "`replayCheck`는 장 시간 보호와 정지 요청 파일을 확인하지 않는다": `autotrade-app-gateway/src/main`에서 `SessionProtection`·`stop-request`·`RunGuard` 참조 0건(grep), `ReplayCheck.java:139`는 `session()`만 쓴다 → 맞다
    - `D-064-krx-2027-calendar.md:44` 갱신 이력: runbook 두 변경과 하나씩 맞고, "데이터·동작 변경 없음"은 diff(코드·데이터 0건)와 맞다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 clone(575d995)에서 `./gradlew clean` 뒤 `./gradlew build --no-daemon` 직접 실행, BUILD SUCCESSFUL, 22개 모듈 test 태스크 모두 실행, 테스트 897건 실패·오류·건너뜀 0. 1회차의 XML 결과 쓰기 실패는 위 "직접 실행한 것" 참고(코드 변경 없는 PR, 단독 재실행·전체 재실행 모두 통과). CI 3종 success |
| B2 | 해당 없음 | 빌드 스크립트·모듈 의존·ArchUnit 규칙 변경 없음(diff는 `docs/` 2파일). `:autotrade-architecture-test:test`는 위 빌드에서 실행·통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 운영 절차 문서와 결정 로그 갱신 이력만 바뀜. 캘린더 데이터 형식(`krx.json` 스키마)도 그대로이고, 옛·새 특별 운영일을 함께 두는 것은 현재 형식으로 표현된다 |
| B4 | 해당 없음 | 코드·캘린더 데이터 변경 없음. 출처 표·수록 범위가 그대로라 `calendar_digest`도 그대로. 골든 테스트 포함 전체 테스트 통과(위 빌드). 절차를 실제로 밟을 때의 결정론 영향은 아래 N1 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 코드 동작 없음(절차 문서뿐). 테스트 파일 diff 0줄, 건너뛰거나 지운 테스트 없음(`@Disabled` grep 0건, 테스트 897건 skipped 0). 절차가 기대는 합침 규칙은 기존 `ExchangeCalendarTest`가 지킨다 |
| B7 | 해당 없음(코드 기준) — risk-ops 검토 필요 | 실전 주문 코드는 바뀌지 않지만 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, D-058·D-063·D-064)의 판정 입력 갱신 절차이고, 이 PR은 연기 공지 때 캘린더를 고치는 방법(옛 날짜 유지·미확인 전환)과 실행 중 명령 정지라는 장애 대응 운영 규칙을 바꾼다. risk-ops 검토가 따로 있어야 하고, 지침 10장 사용자 직접 머지와 11장 독립 QA 관문 대상이다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(grep 0건). CI gitleaks success |

## 점검표 밖 발견
- **N1 (권고, 머지를 막지 않음 — 옛 시험일 항목을 언제 어떻게 정리하나)**: 2번은 옛 시험일을 "거래소 공지로 그날 운영 시간이 확인될 때까지" 미확인 특별 운영일로 남기라고만 하고, 확인된 뒤의 처리를 말하지 않는다. [사실] 특별 운영일 항목은 보호뿐 아니라 거래일 구간 계산 `session()`에도 들어가(`ExchangeCalendar.java:118-130`) `KrxMarketRules.isRegularSession`(`KrxMarketRules.java:60-61` → `ExchangeCalendar.isRegularSession`)과 `ReplayCheck.java:139`가 그 날을 10:00~16:30으로 본다. [가정] 연기된 옛 시험일은 거래소가 정규장(09:00~15:30)으로 되돌리는 경우가 많다(1차 자료 미확인, PR #62 QA 원문도 같은 `[가정]`). 그 경우 정리하지 않으면 그 날 녹화·시뮬레이션에서 09:00~10:00이 정규장 밖으로 판정된다. 또 거래소가 "정규장으로 되돌림"을 따로 공지하지 않으면 "확인될 때까지"가 끝나지 않아 항목이 영구히 남을 수 있다. 다음 runbook 갱신 때 "거래소가 옛 날짜를 정규장으로 운영한다고 확인되면 그 특별 운영일 항목을 지우고(출처 키 새 키), 늦춘 시간을 유지하면 `verified: true`로 바꾼다. 날짜가 지나도록 공지가 없으면 그날 녹화의 첫 체결 시각이나 HTS 일봉으로 확인해 같은 방법으로 정리한다"를 더하기를 권한다. 대안: (가) 위 문장 추가 — 비용 없음. (나) 보호용과 거래일 계산용 항목을 데이터 형식에서 나누기(예: `protectionOnly` 플래그) — 미확인 기간 동안 `session()` 오판도 없애지만 캘린더 형식·코드·ADR 변경이 필요해 수능 연기처럼 드문 경우엔 과하다. (가)를 권한다. 미확인 기간 동안의 `session()` 오판은 새 위험이 아니라 기존 미확인 특별 운영일(현재 수능일 두 날, `krx.json:56,58`)과 같은 성격이다.
- **N2 (권고, 머지를 막지 않음 — "장 마감 뒤"의 뜻)**: 6번 "반영 작업은 장 시간 보호 구간 밖(장 마감 뒤)에"의 괄호가 국내 장 마감만 떠올리게 한다. [사실] 수집기·실험 명령의 보호 구간은 국내와 미국 캘린더를 모두 본다(`CollectorMain.java:70`, `ExperimentMain.java:64`, runbook 3.5 표: 미국 서울 21:30~05:30 / 22:30~06:30). 매일 예약 실행은 서울 18:30~21:30(`collector.md:54`)이다. 그래서 두 조건(보호 구간 밖, 예약 실행 없음)을 함께 만족하는 시간은 국내 보호 끝(평소 16:00, 미확인 수능일 17:00)부터 18:30 전, 또는 미국 보호 끝부터 08:00 전뿐이다. "장 마감 뒤"만 읽고 예약 실행 뒤 밤(미국 장중)에 `build`(테스트 약 900건)를 돌릴 수 있다. 실전 계좌가 아직 없고(2단계) 주문 경로와 무관해 머지를 막지 않는다. "(국내·미국 모두 보호 구간 밖, 예: 국내 보호 구간이 끝난 뒤 18:30 전)"처럼 고치기를 권한다.
- **N3 (권고, 추적)**: "`replayCheck`를 장 시간 보호·정지 요청 대상에 넣는 일"은 7단계 전 후속 과제라고 D-064 갱신 이력(`D-064-krx-2027-calendar.md:44`)과 PR 본문에만 있고, 결정 로그 미확정 항목(Q-xxx)이나 후속 과제 목록에는 없다(`docs/` grep: 갱신 이력 한 줄뿐). 갱신 이력 줄은 다음 작업을 고를 때 보는 목록이 아니므로(지침 10장 "미확정 항목(Q-xxx), 직전 작업이 남긴 후속 과제") 놓칠 수 있다. pm-lead에 Q 번호 등록을 권한다. 함께 넣을 대상: `replayCheck`는 실행 중 판정 대상도 아니고 broker-and-fault-design 5.2 0번 정지 대상 목록에도 없다.
- **N4 (기록, 문장 배치)**: 2번에서 괄호 설명 "(거래소가 옛 날짜를 정규장으로 되돌리면 확인된 10:00 개장 기준 보호가 08:00~09:00을 덮지 못한다)"가 새로 들어간 "공지 때 이미 돌고 있는 … 08:00 전에 멈춘다" 문장 뒤로 밀려 무엇의 이유인지 읽기 어렵다. 또 새 문장은 확인된 옛 날짜 조건 문장 바로 뒤에 있어 그 조건에만 걸리는지 모호하다. 미확인 옛 날짜라면 실행 중 판정이 08:00부터 멈추므로 어느 쪽으로 읽어도 보호가 줄지 않는다. 다음 갱신 때 괄호를 08:00~09:00 문장 바로 뒤로 옮기기를 권한다.
- **N5 (기록, 빌드 환경)**: 1회차 빌드가 결과 XML 쓰기 오류로 실패했다. 재실행은 모두 통과했고 CI도 success라 이 PR의 문제는 아니라고 본다 `[추론]`. 같은 증상이 다시 보이면 검토 환경(스크래치 경로·동시 파일 접근)을 먼저 의심한다.

## 판정
승인. B 전 항목에 실패·확인 못 함이 없다(B2~B5 해당 없음, B7은 코드 기준 해당 없음이며 risk-ops 검토 필요). 2번의 새 절차가 기대는 합침 보호(08:00~17:00)와 6번의 `replayCheck` 설명은 코드와 테스트로 맞다. N1~N3은 머지를 막지 않는 권고다. 다만 이 PR은 장 시간 보호 판정 입력의 갱신 절차와 실행 중 명령 정지 규칙을 바꾸는 실전 관련 PR이므로 architect 승인만으로 Claude가 머지하지 않는다: risk-ops 검토 판정, 지침 10장의 사용자 직접 머지, 11장의 독립 QA 관문을 따른다.
