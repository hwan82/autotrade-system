# architect 재검토: 수능일 출처를 교육부 시행일 보도자료로 바꾸고 봄 재대조 단계 추가 (PR #54 QA Q1·R1)
- 대상: PR #55 / 브랜치 `claude/project-thread-h3g45k` / 커밋 30c61101a33e3bb114246835760c9ab0ec3bb2c2 / base main a0f41ccfe5c3cd41ad47e81fe2b84df4e1b0c488(GitHub REST `pulls/55`의 base SHA, `origin/main`과 같음) 대비 diff 전체
- 이전 원문: `docs/reviews/pr-55/architect.md`(커밋 5577464 기준, 판정 수정 필요 F1·F2). 이 파일은 그 뒤 커밋 30c6110을 포함한 전체 diff 재검토다
- 점검표: B
- PR 본문·커밋 메시지는 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD`: 5파일 +92 −10. `krx.json`(+5 −4), `docs/decisions/D-064-krx-2027-calendar.md`(+5 −1), `docs/runbooks/collector.md`(+6 −5), `docs/reviews/pr-55/architect.md`·`risk-ops.md`(새 검토 원문). Java 소스·테스트·빌드 스크립트·스키마·설정 키 변경 0건
  - `git diff --stat 5577464 30c6110`: 이전 검토 뒤 바뀐 것은 D-064(+5 −1)와 검토 원문 2개뿐. `krx.json`·runbook은 이전 검토 때와 같다
  - `./gradlew build --no-daemon`(작업 트리 30c6110): **종료 코드 0(BUILD SUCCESSFUL)**. 테스트 결과 XML 합계 tests 890, failures 0, errors 0, skipped 0(이전 검토와 같은 건수)
  - `krx.json` 대조(python, base `a0f41cc`와 HEAD): 특별 운영일 4건의 `(date, open, close, verified)`와 휴장일 33건이 같다. HEAD에서 항목이 쓰는 출처 키 중 `sources`에 없는 키 0개, 쓰이지 않는 키는 `KRX-CSAT` 1개(새 키 문구가 관행 근거로 참조)
  - runbook 단계 번호 참조 grep(`3\.6 [0-9]번` 등, `docs/reviews` 제외): 남은 참조는 `D-064:36`의 "3.6 3번 "한국거래소 공지 대조"" 하나이고 `collector.md:69`의 3번(공지 대조, 출처 키 교체 규칙)과 맞다
  - diff에서 `@Disabled`·키·토큰·계좌 문자열 검색: 검토 원문 안의 서술 문장 외 0건
  - CI(GitHub REST, head 30c6110 check-runs): Secret scan and collector boundary success, DB schema(PostgreSQL 16) success, Gradle build **in_progress**(이 검토 시점, mergeable_state `unstable`). 판정은 직접 실행한 빌드로 했다. 머지 전 Gradle build success를 확인해야 한다
  - 1차 출처(교육부 보도자료 KDI 게재본 `num=255989`·`num=267335`, 헤럴드경제 평가원 기사)는 이전 원문에서 WebFetch로 대조했고 이후 출처 문구 변경이 없어 다시 가져오지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 종료 코드 0, 890건 실패·오류·건너뜀 0 |
| B2 | 통과 | Java 소스·빌드 스크립트·ArchUnit 규칙 변경 없음. `autotrade-architecture-test` 31건 포함 전체 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `krx.json` 필드 형식 그대로이고 엄격 파서(`ExchangeCalendar.java:95-97`)가 빌드에서 읽어 통과. 출처 표 변경에 따른 `calendar_digest` 변화가 이제 `D-064-krx-2027-calendar.md:38-41` "갱신 이력"에 기록됐다(이전 F2 해소). runbook 단계 번호 이동에 맞춰 `D-064:36` 참조가 "3.6 3번 "한국거래소 공지 대조""로 정정됐다(이전 F1 해소, 단계 이름을 함께 적어 다음 번호 이동에도 덜 깨진다) |
| B4 | 통과 | 시계·난수 코드 변경 없음. `calendarDigest`(`SnapshotSelectionData.java:93-99`)는 출처 표를 키 정렬로 해시해 결정론적이고, 고정된 다이제스트 골든 값은 없다(테스트가 실행 시 계산해 비교, `SnapshotSelectionDataTest.java:132`, `CalculatedSelectionSourceTest.java:124`). 다이제스트가 이 커밋부터 바뀌는 것은 입력 변경이며 D-064 갱신 이력에 적혔다 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 없음(출처 문자열, 절차·결정 문서뿐). 날짜·시각·`verified`가 base와 같아 `session`·`protectionSession` 결과 불변(위 python 대조). 지우거나 건너뛴 테스트 없음 |
| B7 | 통과(risk-ops 검토 필요) | 실전 주문 경로 코드 변경은 없지만 `krx.json`은 장 시간 보호(`SessionProtection`, 5.2 정지 순서 0번·장애표 #10)의 입력이고 runbook 3.6은 그 갱신 절차다. **risk-ops 검토 필요**. `docs/reviews/pr-55/risk-ops.md`가 있다(커밋 5577464 기준 A 점검표, 실전 관련 PR·사용자 직접 머지 판정). 30c6110은 그 원문의 N1 권고를 반영한 문서 변경뿐이라 A 판정 근거를 바꾸지 않는다고 본다 `[추론]`. risk-ops 재확인 여부는 메인이 정한다 |
| B8 | 통과 | 추가 줄은 공개 URL, 날짜, 문서 문장뿐. CI 비밀값 스캔 success(head 30c6110) |

## 이전 지적 처리
- **F1 해소**: `D-064-krx-2027-calendar.md:36`이 "runbook 3.6 3번 "한국거래소 공지 대조""를 가리키고, `collector.md:69` 3번이 바로 그 공지 대조·출처 키 교체 규칙이다. 다른 번호 참조는 저장소에 없다(grep).
- **F2 해소**: `D-064:38-41` "갱신 이력"에 날짜(2026-10-12), PR #55, 바뀐 출처 키 두 개와 1차 자료, 날짜·시각·`verified` 불변, `calendar_digest` 변경 경계, runbook 번호 이동이 적혔다. 적힌 사실을 `krx.json`·runbook과 대조해 모두 맞다.

## 점검표 밖 발견
- **O1 (관찰, 막지 않음)**: `D-064:18` 결정 3번 본문은 여전히 "수능일은 교육 당국 시행 계획"이라고만 쓴다. 갱신 이력이 구체 출처를 담아 추적은 되므로 고칠 필요는 없다.
- **O2 (관찰, 이전 O3·risk-ops N2 유지)**: `ExchangeCalendar` 생성자(`ExchangeCalendar.java:40-70`)는 항목 `source`가 `sources` 표에 있는지 검사하지 않는다. runbook이 출처 키 교체를 다이제스트 갱신 수단으로 쓰므로 로더 검사와 실패 테스트를 data-engineer 후속 과제로 둔다. 이 PR의 데이터는 무결(위 대조).
- **O3 (관찰)**: 결정 로그 색인 `docs/decisions/README.md:69`의 D-064 행은 갱신 이력을 언급하지 않는다. 동작 불변 출처 정정이라 색인까지 고칠 필요는 없다고 본다 `[추론]`.
- **O4 (확인 사항)**: 이 검토 시점 head 30c6110의 CI Gradle build가 진행 중이다. 머지 전 success를 확인할 것.

## 판정
승인. 이전 F1·F2가 해소됐고, 직접 실행한 `./gradlew build` 통과(890건 실패 0), 점검표 B에 실패·확인 못 함이 없다. B7: 실전 관련(장 시간 보호 입력) PR이라 risk-ops 검토 대상이며 지침 10장에 따라 사용자가 직접 머지한다. 머지 전 head 30c6110의 CI Gradle build 결과를 확인한다.
