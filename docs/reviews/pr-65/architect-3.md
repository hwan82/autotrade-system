# architect 재검토 3: runbook 3.6 옛 시험일 확인을 09:00~09:29 정규장 체결로, 반영 작업 두 창 모두 끝 시각·멈춤 방법 (PR #65, head 3ca5290)
- 대상: PR #65 / 브랜치 `claude/project-thread-h3g45k` / 커밋 3ca5290438da99b682e302be9a77f452f2b76814. GitHub REST `pulls/65` 결과: head SHA 같음, open, base a6f26021059c2d71ee90d34b5be743de24308381 = GitHub `main`, mergeable_state `unstable`(CI 진행 중). 검토 범위는 `git diff origin/main...HEAD` 전체다. 앞선 검토는 `architect.md`(adea58a)와 `architect-2.md`(1fbc0c1, 수정 필요 F1)다
- 점검표: B 전체
- PR 본문, 커밋 메시지, 메인 Claude의 설명, 앞선 검토·risk-ops 원문은 판정 근거로 쓰지 않았다. 확인할 주장으로만 보고, 저장소 코드·데이터 문서와 직접 실행한 빌드로 판정했다
- 직접 실행한 것:
  - 공유 작업 트리가 아닌 `/tmp/claude-0/review-pr65-architect-3`에 clone하고 3ca5290을 checkout했다. 로컬 `origin`이 GitHub과 어긋나 merge base가 없었으므로, `origin`을 GitHub URL로 바꾸고 `main`을 fetch했다(`origin/main` = a6f2602)
  - `git diff origin/main...HEAD --stat`: 7파일. `docs/runbooks/collector.md`(+2 −2), `docs/decisions/D-064-krx-2027-calendar.md`(+1), `docs/decisions/README.md`(+1, Q-050), 검토 원문 4개(`architect.md`, `architect-2.md`, `risk-ops.md`, `risk-ops-2.md`). `docs/` 밖 변경은 0건이다(`*.java`·`*.json`·`*.kts`·`*.sql` 없음). 직전 검토 원문 커밋 뒤 바뀐 것은 `git diff fa3ed44 3ca5290`로 따로 봤다: collector.md 2번·6번, D-064 2026-10-13 이력 줄, risk-ops-2 원문
  - `./gradlew build --no-daemon`: 종료 코드 0. 새 clone이라 테스트가 실제로 돌았다. 테스트 결과 XML 합계는 tests 897, failures 0, errors 0, skipped 0
  - CI(head 3ca5290, REST check-runs): Secret scan and collector boundary success, DB schema (PostgreSQL 16) success, Gradle build **in_progress**(검토 시점). B1은 로컬 실행으로 판정했다. 머지 전에 CI success 확인이 필요하다
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{10,}`를 grep했다. 걸린 것은 검토 원문의 grep 설명 문구와 점검표 단어뿐이고 비밀값은 0건이다
- F1 수정 사실 대조(`collector.md:67`, 2번 끝):
  - "녹화 원본에 그날 서울 09:00:00~09:29:59 체결이 있으면 정규장, 없고 10:00부터 체결이 이어지면 늦춘 시간": 판별 기준이 첫·마지막 체결 시각에서 정규장에만 있는 구간의 체결 유무로 바뀌었다. 정규장일의 09:00~09:29는 정규장 연속매매 구간이다. 늦춘 날에는 시가 단일가 전 구간이고, 장전 시간외는 1시간 뒤인 09:30~09:40으로 잡혀(`[가정]` 표시, KRX 공식 안내로 확인하라는 지시 있음) 이 창에 들지 않는다. 지난 검토에서 내가 제안한 "09:00~10:00"보다 늦춘 날의 장전 시간외를 더 확실히 피하는 범위다 `[사실]`/`[가정]`(시간외 시각)
  - 녹화가 KRX 단독인지 확인했다. 키움 `0B`는 9081 거래소구분이 KRX가 아니면 버린다(`KiwoomStreamProtocol.java:219-222`). 한국투자는 KRX 체결 TR `H0STCNT0`만 쓴다(`KisStreamProtocol.java:54`). 그래서 NXT 메인마켓(09:00:30~) 체결이 09:00~09:29 판단을 오염시키지 않는다 `[사실]`
  - 장구분이 녹화에 없다는 설명이 맞는지 확인했다. `RawQuote.Trade`에는 시각·가격·수량만 남는다(`KiwoomStreamProtocol.java:223-229`). 15:40~15:43 시간외 체결이 녹화에 들어간 사례도 실제로 있다(`experiments/stage-2-replay/2026-10-12.md:51`) `[사실]`
  - "`replayCheck` 보고서의 첫·마지막 체결 칸은 쓰지 않는다(`ReplayCheck.java:128`)": `:128`은 `regularSession(market, date)`이고, `:139`에서 `ExchangeCalendar.session(date)`로 확인 대상 항목 구간을 잡는다. 인용이 맞다 `[사실]`
  - HTS는 **KRX 단독** 분봉으로 보라고 명시했다. 기존 실녹화 절차(`docs/runbooks/stage-2-real-recording.md:24`)와 맞는다
  - "둘 다 아닌 시각이면 확인한 시각으로 고치고 `verified: true`", "새 출처 키 값에 확인 방법을 적는다", "정리 전에는 그날 녹화를 실험에 쓰지 않는다"는 `calendar_digest`가 출처 키로 바뀌는 규칙(3번)과 맞는다
- 지난 N1·N2 반영 확인(`collector.md:71`, 6번):
  - N1: 반영 단계 목록 앞에 "반영 단계는 다음과 같다"를 두고, 시간·초과 처리 문장을 목록 뒤로 옮겼다. 이제 반영 단계가 "초과 시 할 일"로 읽히지 않는다
  - N2: 새벽 창에도 "08:00 전에 끝나도록 시작"이 있다. 초과 처리 대상이 `build`·`installDist`·`replayCheck` 셋 모두로 넓어졌다. 다음 보호 구간 시작 시각은 저녁 21:30/22:30, 새벽 08:00이다. 21:30/22:30은 `us.json` 09:30 ET − 60분(`SessionProtection.java:21-22`)을 EDT·EST로 서울 시각으로 바꾼 값이다. 3.5 표(`collector.md:52`)와 같다 `[사실]`
  - 새벽 창 시작 05:30/06:30은 미국 16:00 ET + 30분을 서울 시각으로 바꾼 값이다. 미국 조기폐장일에는 보호가 더 일찍 끝나므로 이 시각을 쓰면 안전 쪽이다. 08:00은 정규장일 KRX 보호 시작이고, 남겨 둔 미확인 옛 시험일의 보호 시작(08:00)과도 같다. 연초 첫 거래일·수능일의 보호 시작(09:00)보다는 이르다. 셋 다 안전 쪽이다 `[사실]`
  - 멈출 때 `java.exe`를 골라 끝내지 않는다는 문구는 2번을 따르게 했다. 실전 엔진·게이트웨이·감시를 잘못 끄는 일을 막는 쪽이다
- D-064 2026-10-13 이력 줄(`D-064-krx-2027-calendar.md:45`)은 이번 runbook 변경(09:00~09:29 기준, 두 창 끝 시각·멈춤, 18:30 초과 시 예약 실행 미룸)과 하나씩 맞는다. 같은 PR 안의 줄을 고친 것이라 이력 덮어쓰기 문제는 없다. Q-050(`README.md:90`)은 바뀌지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 clone(3ca5290)에서 `./gradlew build --no-daemon` 종료 코드 0, tests 897 / failures 0 / errors 0 / skipped 0. CI Gradle build는 검토 시점에 in_progress였다(머지 전 success 확인 필요) |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 0건(diff 7파일 모두 `docs/`). `ArchitectureTest`·`ModuleOwnershipTest`는 로컬 빌드에서 통과 |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. runbook·결정 로그·미확정 표·검토 원문만 바뀜 |
| B4 | 해당 없음(코드) | 시뮬레이션 코드와 캘린더 데이터(`krx.json`) 변경 없음. 골든 테스트를 포함한 전체 테스트 통과. 직전 검토에서 결정론 입력(그날 `session()`, `calendar_digest`)에 영향을 주던 F1은 위 대조대로 고쳐졌다 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작(코드) 없음. 테스트 삭제·`@Disabled` 추가 0건, skipped 0. `replayCheck` 보호 테스트는 Q-050(`README.md:90`) 추천안의 후속이다 |
| B7 | 해당 없음(코드 기준) — risk-ops 검토 필요 | 실전 주문 코드 변경은 없다. 그러나 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, D-058·D-063·D-064)의 판정 입력 `krx.json`을 갱신하는 절차다. 이 PR은 보호 밖 작업 시간, 실행 중 명령의 정지 방법(`java.exe` 오조작 방지 포함)을 바꾸고, 7단계 판정 전에 닫을 Q-050을 더한다. 3ca5290 변경에는 risk-ops 재검토가 필요하다. 지침 10장 사용자 직접 머지, 11장 독립 QA 관문 대상이다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(grep 결과는 설명 문구뿐). CI Secret scan(gitleaks) success |

## 점검표 밖 발견
- N1 (권고, 머지 비차단): 새 판별 기준은 녹화가 그날 09:00~10:00을 덮었다는 전제를 둔다. 게이트웨이가 10:00 뒤에 켜졌거나, 이 구간에 재접속·재기동 공백(`GAP`, `RECORDER_RESTART`, D-065)이 있으면 문제가 생긴다. "09:00~09:29 체결 없음"이 아무것도 보여 주지 않는데, 첫 체결이 10:00 뒤면 "늦춘 시간"으로 읽힐 수 있다 `[추론]`. "10:00부터 체결이 이어지면" 조건이 늦게 켜진 경우 일부(09:30~09:59 체결 존재)를 걸러 주지만, 10:00 뒤에 시작한 녹화는 거르지 못한다. "녹화 meta의 구간과 GAP으로 그날 08:50~10:10이 끊김 없이 녹화됐을 때만 이 기준을 쓰고, 아니면 HTS KRX 단독 분봉으로 본다"는 한 구절을 더하기를 권한다. 그날이 지난 뒤의 정리라 보호(안전) 영향은 없다
- N2 (첫 검토 N3 유지, 후속 후보): 미확인 특별 운영일을 녹화 리플레이나 실험 명령이 경고하는 코드는 여전히 없다. Q-050과 함께 data-engineer 후속 후보로 둔다
- N3 (메인 Claude 절차, architect 판정과 별개): PR 브랜치의 `docs/qa/pending.md` 표에 이 PR의 독립 QA 대기 줄이 아직 없다(표 머리만 있음). 실전 관련 PR이므로 QA 요청 전에 더해야 한다
- 앞선 검토 지적 반영 상태: architect F1은 해결됐다(위 대조). architect-2 N1·N2도 해결됐다. 첫 검토의 N1·N2는 이미 반영돼 있었다

## 판정
승인. B 전 항목에 실패와 확인 못 함은 없다. B1·B8은 통과다. B2~B6은 해당 없음이다. B7은 코드 기준 해당 없음이고 risk-ops 재검토가 필요하다. 직전 판정의 근거였던 F1이 고쳐졌다. 새 판별 기준(녹화 원본의 09:00~09:29 체결 유무, HTS는 KRX 단독, `replayCheck` 첫·마지막 칸 사용 금지)은 녹화의 KRX 단독 필터(`KiwoomStreamProtocol.java:219-222`, `KisStreamProtocol.java:54`), 장구분 없는 `RawQuote.Trade`, `ReplayCheck`의 구간 자르기(`:128`, `:139`)와 맞는다. 6번의 시간 숫자(16:00/17:00~18:30, 05:30/06:30~08:00, 21:30/22:30)는 `SessionProtection`·`ExchangeCalendar.protectionSession`·`us.json`·3.5 표와 맞는다. N1은 권고이고 머지를 막지 않는다. 머지 전에 CI Gradle build success를 확인해야 한다. 이 PR은 실전 관련(장 시간 보호 판정 입력의 갱신 절차)이므로 risk-ops 재검토, 지침 10장 사용자 직접 머지, 11장 독립 QA 관문을 따른다.

판정: 승인
