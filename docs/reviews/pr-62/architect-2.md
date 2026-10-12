# architect 재검토: runbook 3.6 확인된 수능일 연기 시 옛 날짜 임시 중지와 머지 뒤 반영 단계 (PR #56 QA Q1·Q2)
- 대상: PR #62 / 브랜치 `claude/project-thread-h3g45k` / 커밋 951fef0793a355380e9e8bc00290c6071dfd2999(GitHub REST `pulls/62` head SHA와 같음, open, mergeable_state `clean`) / `git diff origin/main...HEAD` 전체(merge-base 04235231a57d1cf75ca51e65d5a8c9a87bc67dde)
- 이전 원문: `docs/reviews/pr-62/architect.md`(판정 수정 필요 F1). 이 파일은 재검토 원문이며, 메인 Claude 지시로 같은 파일에 덧붙이지 않고 새 파일로 쓴다. 이전 원문은 고치지 않았다
- 점검표: B 전체
- PR 본문, 커밋 메시지, 메인 Claude의 설명, 이전 검토 원문의 주장은 판정 근거로 쓰지 않았다. 저장소 코드와 직접 실행한 빌드로만 판정했다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 4파일 +93 −2. `docs/runbooks/collector.md`(+2 −2, 3.6의 2번·6번), `docs/decisions/D-064-krx-2027-calendar.md`(+1, 갱신 이력), `docs/reviews/pr-62/architect.md`·`risk-ops.md`(새 검토 원문). REST `pulls/62/files`도 같은 4개. `*.java`·`*.json`·`*.kts`·스키마·설정 키 변경 0건
  - origin/main은 merge-base 뒤로 2커밋(`f621667` #59, `681236e` #60) 앞서 있다. 바뀐 파일은 `docs/`·`experiments/` 7개로 이 PR과 겹치지 않고, `git merge-tree --write-tree origin/main HEAD` 충돌 없음
  - `./gradlew build --no-daemon`: 공용 작업 트리가 아닌 스크래치 폴더에 따로 clone해 `git checkout 951fef0` 뒤 실행. **BUILD SUCCESSFUL in 3m 44s, 130 actionable tasks: 130 executed**, 종료 코드 0. 테스트 결과 XML 합계 tests 897, failures 0, errors 0, skipped 0
  - CI(head 951fef0, REST check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success
  - diff에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 일치는 새 검토 원문 두 파일이 검색 패턴과 커밋 SHA를 인용한 줄뿐(`architect.md`의 SHA 7f50448706cd…, 패턴 설명 줄). 키·계좌번호·토큰 0건
  - 새 문장 사실 대조(코드):
    - 6번 "리플레이 점검(`./gradlew :autotrade-app-gateway:replayCheck`)은 main을 받은 작업 트리에서 돌린다": `replayCheck`는 `JavaExec`, `classpath = sourceSets["main"].runtimeClasspath`(`autotrade-app-gateway/build.gradle.kts:28-35`)이고 `ReplayCheck`는 클래스패스 리소스 캘린더의 `ExchangeCalendar.bundled(market).session(date)`를 읽는다(`ReplayCheck.java:139`) → 작업 트리의 `krx.json`이 반영된다. 맞다
    - 6번 "시세 게이트웨이는 캘린더에서 시간대만 읽으므로(`Gateway.java:63`) … 게이트웨이 설치본은 다시 만들지 않는다": `Gateway.java:63`은 `ExchangeCalendar.bundled(market).zone()`만 쓴다. 게이트웨이 `src/main`에서 캘린더를 부르는 곳은 이 줄과 `ReplayCheck.java:139`뿐이고(grep), 게이트웨이가 의존하는 `autotrade-broker-kis`·`autotrade-broker-kiwoom`·`autotrade-broker-api`·`autotrade-config`·`autotrade-domain`의 `src/main`에는 `ExchangeCalendar`·`.session(` 참조가 없다. `autotrade-marketdata` 안의 캘린더 사용처(`KrxMarketRules`, `UsMarketRules`, `DailyBarQualityCheck`, `SessionProtection`)는 게이트웨이 `src/main`에서 참조하지 않는다(grep 0건). 설치본 `installDist`는 `application` `mainClass` `GatewayMain` 하나(`build.gradle.kts:6-9`) → 맞다
    - 6번 "수집기 설치본(`:autotrade-app-collector:installDist`)을 다시 만들고": 예약 실행은 설치본을 돈다(`collector.md:79-82`) → 맞다
    - 6번 "실험 명령은 … main을 받은 작업 트리에서": 실험 명령은 `JavaExec`·`sourceSets["main"].runtimeClasspath`(이전 원문 대조 그대로, `autotrade-app-experiment/build.gradle.kts`) → 맞다
    - 2번 "옛 시험일이 이미 `verified: true`였다면 … 08:00~09:00": `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137-155`)은 미확인 특별 운영일만 정규장과 합치고(`:148-153`), 확인된 날은 `session()`(10:00~16:30)을 그대로 돌려준다. `SessionProtection.java:21-22,64-65`가 60분 전·30분 뒤를 더해 09:00~17:00. 거래소가 정규장(09:00~15:30)으로 되돌리면 필요한 보호는 08:00~16:00이라 빠지는 구간은 08:00~09:00뿐 → 맞다. 현재 수능일 두 날(`krx.json:56,58`)은 모두 `verified: false`라 지금 당장 해당하는 날은 없다
    - 2번 "08:00 전에 시작해 그 시간대로 이어질 수동 실행도 하지 않는다": 확인된 옛 날짜에는 캘린더 판정상 09:00부터 멈추므로 사람 절차가 필요하다는 전제와 맞다(아래 N1에 남은 틈)
    - `D-064-krx-2027-calendar.md:43` 갱신 이력: "수집기 설치본 재생성, 실험 명령과 리플레이 점검은 main 작업 트리, 리플레이 점검 재실행. 게이트웨이는 캘린더 시간대만 써서 설치본 재생성 불필요"가 runbook 6번과 하나씩 맞고, "데이터·동작 변경 없음"도 diff(코드·데이터 0건)와 맞다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 clone(951fef0)에서 `./gradlew build --no-daemon` 직접 실행, BUILD SUCCESSFUL, 130 tasks 모두 실행, 테스트 897건 실패·오류·건너뜀 0. CI 3종 success |
| B2 | 해당 없음 | 빌드 스크립트·모듈 의존·ArchUnit 규칙 변경 없음(diff는 `docs/` 4파일). `:autotrade-architecture-test:test`는 위 빌드에서 실행·통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 운영 절차 문서·결정 로그 갱신 이력·검토 원문만 바뀜 |
| B4 | 해당 없음 | 코드·캘린더 데이터 변경 없음. 출처 표·수록 범위가 그대로라 `calendar_digest`도 그대로. 골든 테스트 포함 전체 테스트 통과(위 빌드) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 코드 동작 없음(절차 문서뿐). 테스트 파일 diff 0줄, 건너뛰거나 지운 테스트 없음(`@Disabled` grep 0건, 테스트 897건 skipped 0) |
| B7 | 해당 없음(코드 기준) — risk-ops 검토 필요 | 실전 주문 코드는 바뀌지 않지만 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10, D-058·D-063·D-064)의 판정 입력 갱신 절차이고, 이 PR은 반영 전 수동·예약 실행 중지라는 장애 대응 운영 규칙을 넓힌다. 이번 커밋에서 바뀐 부분(6번, 2번 08:00 전 시작 금지)은 risk-ops F1·N1에 대한 수정이므로 risk-ops 재검토가 따로 있어야 한다. 지침 10장 사용자 직접 머지, 11장 독립 QA 관문 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(grep 일치는 검토 원문의 패턴·SHA 인용뿐). CI gitleaks success |

## 점검표 밖 발견
- **F1 (이전 원문, 해소 확인)**: 6번이 리플레이 점검 반영을 게이트웨이 `installDist`로 적던 오류는 "리플레이 점검은 main을 받은 작업 트리에서 `replayCheck`로 돌린다"로 바뀌었고, 게이트웨이 설치본 재생성 단계는 빠졌으며 그 이유(시간대만 읽음, `Gateway.java:63`)와 "장중에 돌고 있는 게이트웨이의 설치 폴더를 건드리지 않는다"가 붙었다. 위 코드 대조로 모두 맞다. D-064 갱신 이력도 같이 고쳐졌다. 이전 원문의 권고 문구와 내용이 같다.
- **N1 (권고, 머지를 막지 않음 — 이미 돌고 있는 실행)**: 2번 새 구절은 "08:00 전에 시작해 그 시간대로 이어질 수동 실행도 하지 않는다"라 **시작하지 않는 것**만 말한다. 확인된 옛 시험일 08:00에 이미 돌고 있는 실행은 멈추라는 문장이 없다. [사실] 전체 수집은 이틀 이상 걸린다(`collector.md:42`)고, 수집기는 보호 구간 판정을 캘린더로만 하므로(`RunGuard` → `SessionProtection.check`, 확인된 날 `from` = 10:00−60분) 전날 저녁 시작한 수동 전체 수집이나 밀린 `krx-daily` 예약 실행(18:30 시작, 구간 밖이라 "그 시간대 예약 실행도 끈다"에 걸리지 않음)이 08:00~09:00에도 계속 돈다. risk-ops 원문 N1이 권한 "(돌고 있으면 08:00 전에 멈춘다)"가 빠져 있다. 확인된 수능일이 연기되는 드문 경우에만 해당하고 main보다 나빠지지 않으므로 머지를 막지 않는다. 다음 runbook 갱신 때 "그날 08:00에 돌고 있는 수집기·실험 명령은 08:00 전에 멈춘다(Ctrl+C, 다시 돌리면 캐시에서 이어 받음)"를 더하기를 권한다. 대안: (가) 문장 추가 — 비용 없음, 사람 절차 의존은 그대로. (나) 연기 공지 반영 전 임시로 옛 날짜를 `verified: false`로 되돌리는 데이터 PR — 합침 규칙으로 08:00~17:00이 자동 보호되지만 출처 키·다이제스트가 바뀌고 PR·머지·재설치를 거쳐야 해 "그날 바로"에 못 맞춘다. (가)를 권한다.
- **N2 (권고, 머지를 막지 않음 — 수집기 재설치 시점)**: 6번 수집기 `installDist` 재생성에 실행 시점 조건이 없다. 예약 실행이 도는 중(서울 18:30~21:30)에 설치 폴더를 바꾸면 [추론] Windows에서는 잠긴 jar로 작업이 실패하거나 폴더가 일부만 바뀔 수 있다. risk-ops 원문 F1의 권고("예약 실행이 돌지 않는 시간에")가 반영되지 않았다. 수집기는 실전 경로가 아니고 실패는 다음 실행에서 드러나므로 기록만 한다.
- **N3 (기록, 문서 형식)**: `docs/reviews/README.md`는 재검토를 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이라고 한다. 이번 재검토는 메인 Claude 지시로 `architect-2.md` 새 파일에 썼다(PR #56의 `architect-2`와 같은 관행). 두 방식이 섞이고 있으므로 README를 실제 관행에 맞추거나 관행을 README에 맞추는 정리를 pm-lead에 권한다.
- **N4 (기록, 기준 커밋)**: origin/main이 이 PR의 base보다 2커밋 앞서지만 겹치는 파일이 없고 merge-tree 충돌이 없다. 둘 다 문서만 바꿔 빌드 결과에 영향이 없다고 본다 [추론]. 머지 결과 트리 자체의 빌드는 돌리지 않았다(PR head 빌드만 직접 실행).

## 판정
승인. B 전 항목에 실패·확인 못 함이 없다(B2~B5 해당 없음, B7은 코드 기준 해당 없음이며 risk-ops 검토 필요). 이전 원문 F1은 코드와 맞게 고쳐졌다. N1·N2는 머지를 막지 않는 권고다. 다만 이 PR은 장 시간 보호 갱신 절차를 바꾸는 실전 관련 PR이므로 architect 승인만으로 Claude가 머지하지 않는다: risk-ops 재검토 판정, 지침 10장의 사용자 직접 머지, 11장의 독립 QA 관문을 따른다.
