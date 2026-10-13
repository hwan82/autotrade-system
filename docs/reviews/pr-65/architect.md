# architect 검토: runbook 3.6 반영 시간 숫자로, 옛 시험일 정리 절차, 정지 오조작 방지, Q-050 replayCheck 보호 (PR #63 QA Q1·R1~R3)
- 대상: PR #65 / 브랜치 `claude/project-thread-h3g45k` / 커밋 adea58a6d646b9d8f4a5a1dc56baf94b34c597e3(GitHub REST `pulls/65` head SHA와 같음, open, mergeable_state `clean`) / `git diff origin/main...HEAD` 전체(merge-base a6f26021059c2d71ee90d34b5be743de24308381 = 현재 origin/main = PR base)
- 점검표: B 전체
- PR 본문, 커밋 메시지, 메인 Claude의 설명은 판정 근거로 쓰지 않았다. 저장소 코드와 직접 실행한 빌드로만 판정했다
- 직접 실행한 것:
  - 공유 작업 트리가 아닌 `/tmp/claude-0/review-pr65-architect`에 따로 clone(로컬 저장소가 shallow라 merge-base가 없어 GitHub에서 `--unshallow`·`origin/main` fetch) 뒤 `git checkout adea58a`
  - `git diff origin/main...HEAD --stat`: 3파일 +4 −2. `docs/runbooks/collector.md`(3.6의 2번·6번 각 1줄 교체), `docs/decisions/D-064-krx-2027-calendar.md`(갱신 이력 +1), `docs/decisions/README.md`(미확정 표에 Q-050 +1). REST `pulls/65/files`도 같은 3개. `docs/` 밖 파일 변경 0건(`*.java`·`*.json`·`*.kts`·스키마·설정 키 변경 없음)
  - `./gradlew build --no-daemon`: **BUILD SUCCESSFUL in 2m 32s**, 종료 코드 0, 130 actionable tasks 130 executed. 테스트 결과 XML 합계 tests 897, failures 0, errors 0, skipped 0
  - CI(head adea58a, REST check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success
  - diff에서 `appkey|secret|password|계좌번호 형식` grep: 0건
  - 새 문장 사실 대조(코드):
    - 반영 시간 "서울 16:00(수능일은 17:00)~18:30": KRX 보호 = 세션 개장 60분 전~마감 30분 뒤(`SessionProtection.java:21-22`, `:64-65`). 정규장 15:30 마감 → 16:00, 수능 특별 운영 16:30 마감 → 17:00. 미확인 특별 운영일은 정규장과 합친 구간(`ExchangeCalendar.java:137-155`)이라 남겨 둔 옛 시험일도 17:00. 18:30은 3.5 매일 실행 시작(`collector.md:54`). 맞다 `[사실]`
    - "미국장 보호가 끝난 뒤(서머타임 중 05:30, 아닐 때 06:30)~08:00": NYSE 16:00 ET = 서울 05:00(EDT)/06:00(EST), +30분 = 05:30/06:30. 3.5 표(`collector.md:52`)와 같다. 08:00은 KRX 보호 시작. 맞다 `[사실]`
    - "`replayCheck`는 장 시간 보호와 정지 요청 파일을 확인하지 않는다": `ReplayCheck.java`에 `SessionProtection`·`stop-request`·`RunGuard`·`ExperimentGate` 참조 0건(grep). Q-050 본문의 `RunGuard`(autotrade-app-collector)·`ExperimentGate`(autotrade-app-experiment) 클래스와 각 테스트 존재 확인. `replayCheck` 태스크는 `autotrade-app-gateway/build.gradle.kts:29`. 맞다 `[사실]`
    - `Gateway.java:63` 인용: 그 줄이 여전히 `ExchangeCalendar.bundled(market).zone()`만 읽는다. 맞다 `[사실]`
    - Q-050 번호: Q-049는 닫힌 표(`README.md:125`, D-063)에 있고 Q-050은 다른 곳에 쓰이지 않음. 참조한 점검표 D7·A8은 `docs/qa/checklists.md`에 있다 `[사실]`
    - "PR #63 QA Q1·R1~R3": `docs/reviews/pr-63/qa.md:55-58`의 권고 Q1(반영 시간 숫자), R1(옛 시험일 정리), R2(`java.exe` 오조작), R3(replayCheck Q 항목 등록)와 diff가 하나씩 대응한다. R1의 "HTS 일봉"은 "HTS 분봉의 첫 봉 시각"으로 바뀌었는데 개장 시각 확인에는 분봉이 맞다 `[추론]`

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 clone에서 `./gradlew build --no-daemon` BUILD SUCCESSFUL(2m 32s), tests 897 / failures 0 / errors 0 / skipped 0. CI Gradle build success |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 0건(diff 3파일 모두 `docs/`). `ModuleOwnershipTest`·`ArchitectureTest`는 빌드에서 통과 |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 운영 절차(runbook)와 결정 로그·미확정 표만 바뀜. D-064 갱신 이력에 "데이터·동작 변경 없음" 기록(`D-064-krx-2027-calendar.md:45`), 사실과 같다 |
| B4 | 해당 없음 | 시뮬레이션 코드·데이터·캘린더(`krx.json`) 변경 없음. 골든 테스트 포함 전체 테스트 통과 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작(코드) 없음. 테스트 삭제·`@Disabled` 추가 0건, skipped 0. `replayCheck` 보호의 실패 테스트는 Q-050 추천안에 후속으로 적혀 있다 |
| B7 | 해당 없음(코드 기준) — risk-ops 검토 필요 | 실전 주문 코드는 바뀌지 않지만 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, D-058·D-063·D-064)의 판정 입력 `krx.json` 갱신 절차이고, 이 PR은 반영 작업 시간, 실행 중 명령 정지 방법(실전 엔진·게이트웨이·감시 JVM 오종료 방지), 옛 시험일 정리라는 장애 대응 운영 규칙을 바꾸며 7단계 실전 활성화 판정 전 닫을 항목(Q-050)을 더한다. 같은 성격의 PR #63은 QA가 실전 관련으로 봤다(`docs/reviews/pr-63/qa.md:4`). risk-ops 검토가 따로 있어야 하고, 지침 10장 사용자 직접 머지와 11장 독립 QA 관문 대상이다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(grep 0건). CI Secret scan(gitleaks) success |

## 점검표 밖 발견
- N1 (권고, 문구): 6번의 "수능일은 17:00"에 남겨 둔 옛 시험일(미확인 특별 운영일, 보호 08:00~17:00)이 들어가는지가 문장만으로는 드러나지 않는다. "수능일과 2번에서 남겨 둔 옛 시험일은 17:00"처럼 적으면 연기 직후 반영 작업 때 헷갈리지 않는다. 코드 판정은 이미 17:00까지 막으므로 수집기·실험 명령에는 영향이 없고, 보호를 보지 않는 `replayCheck`·`build`에만 해당한다.
- N2 (권고, 문구): 16:00~18:30 창은 시작 시각만이 아니라 끝나는 시각도 18:30 전이어야 매일 예약 실행(18:30~)과 겹치지 않는다. "18:30 전에 끝나도록 시작"을 덧붙이기를 권한다.
- N3 (권고, 절차 한계): 2번의 "정리 전에는 그날 녹화를 실험에 쓰지 않는다"는 사람 절차로만 지켜진다. 미확인 특별 운영일은 `session()`이 10:00~16:30으로 보므로(`ExchangeCalendar.java:125-129`) 실제로 09:00 개장했다면 그날 실험의 거래 구간이 틀어진다. 녹화 리플레이나 실험 명령이 미확인 특별 운영일을 경고하는 코드는 지금 없다 `[사실]`(grep). 7단계 전 필수는 아니나 Q-050과 함께 data-engineer 후속 후보로 둘 만하다.
- N4 (절차, 메인 Claude 몫): 이 PR은 실전 관련(B7)이라 독립 QA 관문 대상인데, PR 브랜치의 `docs/qa/pending.md`에 대기 줄이 없다. `pending.md` 규칙("PR 검토 요청은 그 PR 브랜치의 이 파일에")에 따라 QA 요청 전에 한 줄을 더해야 한다. architect 판정과는 별개다.

## 판정
승인. B 전 항목에 실패·확인 못 함이 없다(B2~B6 해당 없음, B7은 코드 기준 해당 없음이며 risk-ops 검토 필요). 새 시간 숫자(16:00/17:00~18:30, 05:30/06:30~08:00)는 `SessionProtection`·`ExchangeCalendar.protectionSession`과 3.5 표에 맞고, Q-050 등록과 `replayCheck` 무보호 서술은 코드와 같다. N1~N3은 머지를 막지 않는 권고, N4는 메인 Claude의 절차 항목이다. 다만 이 PR은 장 시간 보호 판정 입력의 갱신 절차와 실행 중 명령 정지 규칙을 바꾸는 실전 관련 PR이므로 architect 승인만으로 Claude가 머지하지 않는다: risk-ops 검토 판정, 지침 10장의 사용자 직접 머지, 11장의 독립 QA 관문을 따른다.

판정: 승인
