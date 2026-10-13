# architect 재검토: runbook 3.6 반영 창 끝 시각·18:30 초과 처리, 옛 시험일 폐장 시각 대조 (PR #65, head 1fbc0c1)
- 대상: PR #65 / 브랜치 `claude/project-thread-h3g45k` / 커밋 1fbc0c19d8c8c86837d8ce941e1d8ec42922eafc(GitHub REST `pulls/65` head SHA와 같음, open, mergeable_state `clean`, base a6f26021059c2d71ee90d34b5be743de24308381 = GitHub `main`) / `git diff origin/main...HEAD` 전체. 첫 검토(`docs/reviews/pr-65/architect.md`)는 adea58a 기준이었다
- 점검표: B 전체
- PR 본문, 커밋 메시지, 메인 Claude의 설명, 첫 검토·risk-ops 원문은 판정 근거로 쓰지 않았다. 확인할 주장으로만 보고, 저장소 코드·데이터 문서와 직접 실행한 빌드로 판정했다
- 직접 실행한 것:
  - 공유 작업 트리가 아닌 `/tmp/claude-0/review-pr65-architect-2`에 clone하고 `claude/project-thread-h3g45k`(1fbc0c1)를 checkout했다. 로컬 저장소의 `main` 참조가 GitHub과 달라서 `origin`을 GitHub URL로 바꾸고 `main`을 fetch했다(`git log -1 origin/main` = a6f2602, `ls-remote`와 같음)
  - `git diff origin/main...HEAD --stat`: 5파일. `docs/runbooks/collector.md`(+2 −2), `docs/decisions/D-064-krx-2027-calendar.md`(+1), `docs/decisions/README.md`(+1, Q-050), `docs/reviews/pr-65/architect.md`(+39), `docs/reviews/pr-65/risk-ops.md`(+105). `docs/` 밖 변경 0건(`*.java`·`*.json`·`*.kts`·`*.sql` 없음). 첫 검토 뒤 바뀐 것은 `git diff adea58a 1fbc0c1`로 따로 봤다: collector.md 2번·6번 문구, D-064 2026-10-13 이력 줄 교체, 검토 원문 2개
  - `./gradlew build --no-daemon`: 종료 코드 0. 새 clone이라 테스트가 실제로 돌았다. 테스트 결과 XML 합계 tests 897, failures 0, errors 0, skipped 0
  - CI(head 1fbc0c1, REST check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 검토 원문의 커밋 SHA와 점검표 설명 단어만 나왔다. 비밀값 0건
  - 새 문장 사실 대조:
    - "수능일과 2번에서 남겨 둔 옛 시험일은 17:00"(`collector.md:71`). 미확인 특별 운영일은 정규장과 합친 구간이다(`ExchangeCalendar.java:147-153`, 10:00~16:30 ∪ 09:00~15:30 = 09:00~16:30). 여기에 보호 여유 60/30분(`SessionProtection.java:21-22`, `:64-65`)을 더하면 08:00~17:00이다. 확인된 수능일(`:154`)은 10:00~16:30 + 여유 = 09:00~17:00. 둘 다 17:00에 끝나므로 맞다 `[사실]`
    - "18:30 전에 끝나도록 시작한다", 18:30 = 매일 실행 시작(`collector.md:54`) `[사실]`
    - "`replayCheck`는 미국장 보호 시작(서머타임 중 21:30, 아닐 때 22:30) 전에 멈춘다". `us.json`은 regularOpen 09:30, zone America/New_York다. 09:30 − 60분 = 08:30 ET. 이는 EDT(UTC−4)에서 서울 21:30, EST(UTC−5)에서 22:30이다. 3.5 표(`collector.md:52`)와 같아 맞다 `[사실]`
    - "그날 예약 실행을 반영 작업이 끝난 뒤로 미루고". 미뤄진 수집기·실험 명령이 미국장 보호에 들어가면 `RunGuard`·`ExperimentGate`가 코드로 막는다(종료 코드 4, `collector.md:47`). 미루는 지시가 보호를 깨지 않는다 `[사실]`
    - "녹화의 첫·마지막 체결 시각(녹화가 없으면 HTS 분봉의 첫·마지막 봉 시각)으로 개장과 폐장 시각을 모두 확인". 아래 F1 참조. 우리 녹화·도구의 동작과 맞지 않는다
    - D-064 2026-10-13 이력 줄(`D-064-krx-2027-calendar.md:45`)은 runbook 변경(첫·마지막 체결 시각, 18:30 초과 처리)과 하나씩 맞는다. 같은 PR 안의 줄을 고친 것이라 이력 덮어쓰기 문제는 없다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 clone(1fbc0c1)에서 `./gradlew build --no-daemon` 종료 코드 0, tests 897 / failures 0 / errors 0 / skipped 0. CI Gradle build success |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 0건(diff 5파일 모두 `docs/`). `ArchitectureTest`·`ModuleOwnershipTest`는 빌드에서 통과 |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. runbook·결정 로그·미확정 표·검토 원문만 바뀜 |
| B4 | 해당 없음(코드) | 시뮬레이션 코드와 캘린더 데이터(`krx.json`) 변경 없음. 골든 테스트를 포함한 전체 테스트 통과. 다만 새 절차가 앞으로 만들 캘린더 항목의 정확도(그날 실험 거래 구간)에는 F1이 영향을 준다 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작(코드) 없음. 테스트 삭제·`@Disabled` 추가 0건, skipped 0. `replayCheck` 보호 테스트는 Q-050(`README.md:90`) 추천안의 후속 |
| B7 | 해당 없음(코드 기준) — risk-ops 검토 필요 | 실전 주문 코드 변경은 없다. 그러나 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, D-058·D-063·D-064)의 판정 입력 `krx.json`을 갱신하는 절차다. 이 PR은 반영 작업 시간, 18:30 초과 시 처리, 실행 중 명령의 정지 방법을 바꾸고, 7단계 판정 전에 닫을 Q-050을 더한다. risk-ops 원문(`docs/reviews/pr-65/risk-ops.md`)은 adea58a 기준이다. 1fbc0c1의 변경(18:30 초과 처리, 폐장 시각 대조)에는 risk-ops 재검토가 필요하다. 지침 10장 사용자 직접 머지, 11장 독립 QA 관문 대상이다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(grep 결과는 SHA·설명 단어뿐). CI Secret scan(gitleaks) success |

## 점검표 밖 발견
- **F1 (수정 필요, 절차 사실 오류)**: 2번의 옛 시험일 정리 방법(`collector.md:67` 끝)은 "그날 녹화의 첫·마지막 체결 시각(녹화가 없으면 HTS 분봉의 첫·마지막 봉 시각)으로 개장과 폐장 시각을 모두 확인"하라고 한다. 우리 녹화와 도구에서는 이 방법으로 운영 시간을 판별할 수 없고, 아래 경로를 따르면 잘못 확정할 수 있다.
  - 녹화에 시간외 체결이 섞이고, 구분 정보가 없다 `[사실]`. 키움 `0B`는 KRX만 걸러 녹화한다(`KiwoomStreamProtocol.java:219-222`). 장구분(290)은 버리고 `RawQuote.Trade`에 시각·가격·수량만 남긴다(`:223-229`). 2026-10-12 녹화에는 15:40:00~15:43:27 장후 시간외 체결이 들어 있다(`experiments/stage-2-replay/2026-10-12.md:51`). 그래서 "마지막 체결 시각"은 정규장 종가(15:30) 대신 시간외 시각이 된다. 게이트웨이를 오래 켜 두면 더 늦은 시각이 된다. 장전 시간외도 같은 문제로 "첫 체결"을 앞당긴다(`ReplayCheckTest.java:57`이 08:30 장전 시간외를 녹화 이벤트로 다룬다).
  - 리플레이 점검의 첫·마지막 체결 칸은 확인하려는 캘린더 항목 자체로 구간을 자른다 `[사실]`. `ReplayCheck`는 `ExchangeCalendar.session()`으로 정규장을 잡는다(`ReplayCheck.java:128`, `:139`). 남겨 둔 미확인 옛 시험일은 `session()`이 10:00~16:30을 돌려준다(`ExchangeCalendar.java:124-126`). 거래소가 그날을 정규장(09:00 개장)으로 되돌렸어도 09:00~10:00 체결은 "정규장 밖"으로 빠지고, 보고서의 첫 체결은 10:00 무렵으로 찍힌다. 15:40~16:00 시간외 종가는 10:00~16:31 안이라 정규장으로 집계되어 마지막 체결이 16:00 무렵이 된다. 운영자가 3.6 6번이 돌리라고 하는 그 보고서로 판단하면 "늦춘 시간 유지"로 보고 `verified: true`(10:00~16:30)를 넣게 된다. 실제와 반대 결론이고, 확인된 값으로 굳는다 `[추론]`.
  - HTS 분봉도 기본이 KRX+NXT 통합이다 `[사실]`(영웅문 기본 일봉이 통합 시세였다, `experiments/stage-2-replay/2026-10-12.md:50`). 통합 분봉의 첫·마지막 봉은 NXT 프리·애프터마켓(08:00, 20:00 무렵)이라 KRX 운영 시간을 보여 주지 않는다. 기존 절차도 KRX 단독을 명시한다(`docs/runbooks/stage-2-real-recording.md:24`).
  - 영향: 보호(안전)에는 없다. 그날이 지난 뒤의 정리이기 때문이다. 대신 그날 실험의 거래 구간(`session()`)과 `calendar_digest`, 그날 녹화를 실험에 다시 써도 되는지의 판단이 틀어진다. 잘못된 `verified: true`는 뒤 절차(3번 대조)에서도 다시 의심받지 않는다.
  - 고칠 방향(한 구절이면 된다). 판별 기준을 시간외가 아닌 정규장 단일가 체결로 바꾼다. 정규장이면 09:00 시가 단일가와 15:30 종가 단일가, 늦춘 시간이면 10:00과 16:30이다. 장전·장후 시간외 체결(정규장 기준 08:30~08:40, 15:40~18:00, 늦춘 날은 각 1시간 뒤 `[가정]`, KRX 공식 안내로 확인 필요)은 보지 않는다. 리플레이 점검 보고서의 첫·마지막 체결 칸은 확인 대상 캘린더 항목으로 잘린 값이라 쓰지 않는다. 녹화 원본의 09:00~10:00 체결 유무를 보는 것이 가장 단순하다. HTS는 KRX 단독 분봉으로 본다.
- N1 (권고, 문구 구조): `collector.md:71`에서 새 문장 "18:30까지 끝나지 않으면 … 멈춘다(PR #65 architect N1·N2, risk-ops N2)" 뒤에 원래의 콜론과 반영 단계 목록(": 수집기 설치본…을 다시 만들고, …")이 붙었다. 그래서 반영 단계가 "18:30을 넘겼을 때 할 일"처럼 읽힌다. 18:30 초과 처리 문장을 반영 단계 목록 뒤로 옮기거나, 목록 앞에 "반영 단계는 다음과 같다"를 두기를 권한다.
- N2 (권고, 대칭 공백): 새벽 창(05:30/06:30~08:00)에는 저녁 창의 "끝나도록 시작"과 초과 처리가 없다. 08:00은 KRX 보호 시작이다. 새벽에 시작한 `replayCheck`·`build`가 08:00을 넘기면 코드 보호가 없어(Q-050) 국내장 직전·장전 실전 엔진과 자원을 다툰다. "08:00 전에 끝나도록 시작하고, 넘기면 08:00 전에 멈춘다"를 같이 적기를 권한다. 저녁 초과 처리도 `replayCheck`만 멈추게 하고 `build`·`installDist`는 언급하지 않는다. 이 둘은 짧지만(이번 `build` 수 분) 코드 보호가 없기는 마찬가지다.
- N3 (첫 검토 N3 유지, 후속 후보): 미확인 특별 운영일을 녹화 리플레이나 실험 명령이 경고하는 코드는 여전히 없다. F1의 두 번째 항목이 그 결과다. Q-050과 함께 data-engineer 후속 후보로 둔다.
- N4 (첫 검토 N4 유지, 메인 Claude 절차): PR 브랜치의 `docs/qa/pending.md` 표에 이 PR의 독립 QA 대기 줄이 아직 없다(표 머리만 있음). 실전 관련 PR이므로 QA 요청 전에 더해야 한다. architect 판정과는 별개다.
- 첫 검토 지적 반영 확인: N1(옛 시험일 17:00 명시)과 N2(18:30 전에 끝나도록 시작)는 `collector.md:71`에 반영됐고, 코드·문서 사실과 맞다.

## 판정
수정 필요(F1). B 전 항목에 실패와 확인 못 함은 없다. B2~B6은 해당 없음이고, B7은 코드 기준 해당 없음이며 risk-ops 재검토가 필요하다. 첫 검토의 N1·N2 반영과 새 시간 숫자(17:00, 18:30, 21:30/22:30)는 `SessionProtection`·`ExchangeCalendar.protectionSession`·`us.json`과 맞는다. 그러나 이 PR이 새로 정한 옛 시험일 정리 방법(녹화·HTS 분봉의 첫·마지막 시각)은 우리 녹화에 시간외 체결이 섞이고, 리플레이 점검이 확인 대상 항목으로 구간을 자르며, HTS 기본이 통합 시세라는 사실과 맞지 않는다. 그대로 따르면 정규장으로 돌아간 날을 "늦춘 시간 유지"로 `verified: true` 확정할 수 있다. F1을 고친 뒤 같은 검토자에게 재검토를 맡긴다. N1·N2는 함께 고치기를 권하나 머지를 막지 않는다. 이 PR은 실전 관련(장 시간 보호 판정 입력의 갱신 절차)이므로 architect 승인이 나더라도 risk-ops 재검토, 지침 10장 사용자 직접 머지, 11장 독립 QA 관문을 따른다.

판정: 수정 필요
