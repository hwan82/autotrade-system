# architect 검토: 수능일 출처를 교육부 시행일 보도자료로 바꾸고 봄 재대조 단계 추가 (PR #54 QA Q1·R1)
- 대상: PR #55 / 커밋 557746463036a1673656ac6ec4e6e53ddb163b1f / base main a0f41ccfe5c3cd41ad47e81fe2b84df4e1b0c488(GitHub PR base SHA, `origin/main`과 같음) 대비 diff 전체
- 점검표: B
- PR 본문·커밋 메시지는 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - 스크래치 폴더 별도 clone(`arch55`)에서 `git diff a0f41cc...HEAD --stat`: 2파일, +11 −9. `autotrade-marketdata/src/main/resources/com/autotrade/marketdata/calendar/krx.json`(출처 키 2개 교체, `checkedOn` 2026-10-11→2026-10-12), `docs/runbooks/collector.md`(3.6 1번 문구 수정, 새 2번 삽입, 기존 2~5번을 3~6번으로 번호 이동). Java 코드·테스트·스키마·설정 키 변경 0건
  - `./gradlew build --no-daemon`: **BUILD SUCCESSFUL**(3m 1s, 130 tasks, 429 없이 1회). 테스트 결과 XML 합계 tests 890, failures 0, errors 0, skipped 0(marketdata 84, sim 115, selection 34, architecture-test 31 포함)
  - `krx.json` 출처 참조 무결성(python으로 계산): 휴장일·특별 운영일이 쓰는 출처 키 중 `sources`에 없는 키 0개. 어떤 날짜도 쓰지 않는 키는 `KRX-CSAT` 1개(새 두 키의 값이 "관행(KRX-CSAT)"으로 글로 참조함)
  - 날짜 불변 확인: diff의 `specialSessions` 두 줄은 `source` 값만 바뀌고 `date`·`open`·`close`·`verified`는 그대로(`krx.json:56,58`)
  - 1차 출처 대조(WebFetch): `eiec.kdi.re.kr/policy/materialView.do?num=255989` → 교육부 인재선발제도과, 2024-08-16, "2027학년도 대학수학능력시험은 2026년 11월 19일(목)에 시행됩니다". `num=267335` → 같은 과, 2025-06-02, "2028학년도 … 2027년 11월 18일(목)". 헤럴드경제 `2026033018340941404` → 2026-03-30 18:34 게재, 평가원이 2026-03-31 시행기본계획 발표, 11월 19일. 출처 문구와 일치(curl은 프록시 403으로 실패, WebFetch로 확인)
  - CI(head 5577464): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success, mergeable_state `clean`

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공, 890건 실패·오류·건너뜀 0 |
| B2 | 통과 | Java 소스·빌드 스크립트·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 31건 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `krx.json` 형식(필드)은 그대로이고 `FAIL_ON_UNKNOWN_PROPERTIES` 파서(`ExchangeCalendar.java:95-97`)가 빌드에서 읽어 통과. 출처 표 값 변경은 데이터 변경이며 `calendar_digest`가 바뀌는 것은 runbook 3.6 6번과 D-064 "위험과 한계"에 적힌 의도된 입력 변경(ADR-007 계약 변경 아님). 다만 번호 이동으로 생긴 문서 불일치는 아래 F1 |
| B4 | 통과 | 시계·난수 사용 코드 변경 없음. `calendarDigest`(`SnapshotSelectionData.java:93-99`)는 출처 표를 `TreeMap` 키 정렬로 해시하므로 키 교체 뒤에도 결정론적. `checkedOn`은 다이제스트에 들어가지 않는다. 저장소에 고정된 다이제스트 값(골든) 없음(grep: `.java` 밖 `sources_digest|calendar_digest`는 문서·검토 원문뿐), 테스트는 `SnapshotSelectionData.calendarDigest(KRX)`를 실행 시 계산해 비교(`SnapshotSelectionDataTest.java:132`, `CalculatedSelectionSourceTest.java:124`) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 없음(출처 문자열과 절차 문서만). 날짜·`verified`가 그대로라 `session`·`protectionSession` 결과가 바뀌지 않는다. 지우거나 건너뛴 테스트 없음 |
| B7 | 통과(risk-ops 검토 필요) | 실전 주문 경로 코드 변경은 없다. 그러나 `krx.json`은 장 시간 보호(`SessionProtection`, 5.2 정지 순서 0번·장애표 #10, D-058·D-063·D-064)의 입력이고 runbook 3.6은 그 입력의 갱신 절차다. PR #54도 같은 이유로 실전 관련으로 다뤄졌다. **risk-ops 검토 필요**. 이 시점 `docs/reviews/pr-55/`에 risk-ops 원문은 없다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(추가 줄은 공개 URL과 날짜뿐). CI gitleaks success |

## 점검표 밖 발견
- **F1 (수정 필요, 문서 상호 참조 깨짐)**: 이 PR이 runbook 3.6에 새 2번을 끼워 넣어 기존 2번(공지 대조와 "날짜나 `verified`를 바꿀 때는 출처 키를 새 키로" 규칙)이 3번이 됐다(`docs/runbooks/collector.md:67-68`). 그런데 `docs/decisions/D-064-krx-2027-calendar.md:36`은 여전히 그 규칙을 "runbook 3.6 2번, PR #53 architect F1"로 가리킨다. 이제 2번은 봄 수능일 재대조 단계라, 다이제스트 우회 위험(`calendar_digest`가 날짜를 해시하지 않음)을 막는 핵심 규칙의 근거 링크가 엉뚱한 단계를 가리킨다. D-064:36을 "3.6 3번"으로 고치거나, 새 단계를 번호 이동 없이 끝에 붙이거나, 번호 대신 단계 이름으로 참조하라. (`ADR-008:83`, `ADR-003:147`, `decisions/README.md:69`, `D-064:21`은 "3.6"만 가리켜 영향 없음.)
- **F2 (수정 필요, 자기 절차 미준수)**: runbook 3.6 6번(`collector.md:71`)은 캘린더를 "바꾼 사실을 결정 로그에 남긴다"고 한다. 이 PR은 `krx.json`의 출처 표(따라서 선정 결과 `calendar_digest`)를 바꾸지만 결정 로그·D-064에 아무 줄도 더하지 않았다. D-064 "위험과 한계"나 본문 3번(수능일 `[가정]` 근거, `D-064:18`은 아직 "교육 당국 시행 계획"이라고만 함)에 한 줄(출처 키 교체, 1차 출처 확인 날짜, 다이제스트 변경, PR #55)을 더하라. 새 결정 번호까지는 필요 없다고 본다(날짜·동작 불변) `[추론]`.
- **O1 (관찰, 막지 않음)**: QA PR #54 Q1(7월 시점에 시행기본계획이 없음)과 R1(수능일 두 줄이 2차 자료)은 이 diff로 해소된다. 새 1번은 2년 전 발표되는 교육부 시행일 보도자료를 쓰므로 7월에 다음 해 날짜를 확인할 수 있고(2028학년도분 2025-06-02로 실제 확인), 새 2번이 봄 시행기본계획으로 재대조한다. 2026년분은 이미 재대조 결과가 출처 문구에 들어 있다(`krx.json:16`).
- **O2 (관찰, 막지 않음)**: `krx.json` 특별 운영일 출처 키 이름이 `KRX-CSAT-2026-MOE`/`KRX-CSAT-2027-MOE`로 연도+기관 접미사를 쓴다. 다음 해 봄 재대조에서 날짜가 바뀌면 또 새 키가 필요하므로(3번 규칙), `-MOE`, `-KICE` 같은 접미사 관례를 runbook 예시에 적어 두면 좋다.
- **O3 (관찰, 기존 결함, 이 PR 범위 밖)**: `ExchangeCalendar` 생성자는 날짜 항목의 `source`가 `sources` 표에 있는지 검사하지 않는다(`ExchangeCalendar.java:40-70`, `sources`는 복사만 함). 이 PR처럼 출처 키를 손으로 바꾸는 절차에서 오타가 나면 조용히 통과한다. 지금 데이터는 무결(위 계산). 로더 검사 또는 테스트 1건 추가를 data-engineer 후속 과제로 권한다.
- **O4 (관찰)**: `KRX-CSAT`는 어떤 날짜도 직접 쓰지 않지만 새 두 키가 "관행(KRX-CSAT)"으로 참조하는 시각 변경 근거라 남겨 두는 것이 맞다.

## 판정
수정 필요(F1: `D-064-krx-2027-calendar.md:36`의 "runbook 3.6 2번" 참조를 번호 이동에 맞게 고침, F2: runbook 3.6 6번대로 이 출처 변경을 D-064(또는 결정 로그)에 기록). 빌드·테스트·CI 통과, 데이터 날짜와 1차 출처는 직접 대조해 맞다. B7은 risk-ops 검토 필요.
