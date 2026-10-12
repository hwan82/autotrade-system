# architect 검토: 장 시간 보호 폐장 쪽 합치기 테스트와 수능일 확인 절차 (PR #53 QA Q1·R1)
- 대상: PR #54 / 커밋 92a181a8abde9985de7e23d5ae6613b61692b0dd / main(1b32205aad43ca9b087553fdad1af8db225122df) 대비 diff
- 점검표: B
- 직접 실행한 것:
  - GitHub REST로 PR 머리 SHA(92a181a)와 base SHA(1b32205)를 확인하고, 별도 clone에서 `git diff origin/main...HEAD` 전체를 봤다. 바뀐 파일은 2개다: `autotrade-marketdata/src/test/java/com/autotrade/marketdata/calendar/ExchangeCalendarTest.java`(+19), `docs/runbooks/collector.md`(1줄 수정). 운영 코드 변경 0건.
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL(3m 15s, 130 tasks). 테스트 결과 XML 합계 890건, 실패 0·오류 0·건너뜀 0(PR #53 QA 기준 889건 + 새 테스트 1건). 새 테스트 `ExchangeCalendarTest#확인_안_된_조기_폐장일도_보호는_정규장_폐장까지_간다` 실행·통과 확인.
  - 변이 M5(PR #53 QA가 살아남는다고 적은 변이): `ExchangeCalendar.java:152`의 `close.isAfter(regularClose) ? close : regularClose`를 `close`로 바꿔 `:autotrade-marketdata:test` 실행 → 84건 중 1건 실패, 실패한 것이 새 테스트(`expected: 15:30 but was: 12:00`). 변이를 되돌리고 작업 트리가 깨끗함을 확인했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 890건 실패·오류·건너뜀 0 |
| B2 | 통과 | 테스트 파일은 `com.autotrade.marketdata.calendar` 패키지(marketdata 소유) 안이고 새 의존이 없다(`ExchangeCalendar`, `SessionProtection`은 같은 패키지). ArchUnit·순수 모듈 검사(`verifyPureDependencies`) 포함 빌드 통과. 규칙 변경 없음 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. runbook은 운영 절차 문서이고 캘린더 JSON 형식(`ExchangeCalendar.Data`, `ExchangeCalendar.java:217-231`)을 바꾸지 않는다 |
| B4 | 통과 | 새 테스트는 고정 `Instant.parse(...)`와 인라인 JSON만 쓰고 시스템 시계·난수가 없다. `SessionProtection.check`는 시각을 인자로 받는 순수 계산(`SessionProtection.java:51-71`). 골든 테스트 포함 전체 테스트 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 단언이 PR #53 QA Q1 공백(폐장 쪽 합치기)을 실제로 지킨다: 변이 M5에서 실패(위). 단언 4개가 서로 다른 것을 본다 — `session` 폐장 12:00(거래일 계산은 그대로, `ExchangeCalendar.java:125-127`), `protectionSession` 폐장 15:30(`:151-152`), 경계 직전 06:59Z `Inside`, 경계 07:00Z `Clear`(서울 16:00 = 15:30+30분, `SessionProtection.java:23,64`의 반열린 구간 `now.isBefore(to)`). 지운 테스트·`@Disabled` 추가 없음 |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·보호 테스트(`ArchitectureTest`, Guard·GuardedOrderGateway 테스트, 장애 테스트)는 바뀌지 않았다. 다만 바뀐 두 파일이 장애 대응 5.2의 장 시간 보호(D-063·D-064, PR #53을 실전 관련으로 다룸)의 테스트와 입력 갱신 절차라, 지침 10장 "장애 대응"에 해당하는지 risk-ops가 판정해야 한다. 변경 방향은 약화가 아니라 보강이다(테스트 추가, 절차에 확인 단계 추가) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 URL·출처 값 추가 없음(runbook은 기관 이름만 적음) |

## 점검표 밖 발견
- **O1 (기록, 정확성 확인)**: runbook 새 문장 "캘린더에 없는 날의 특별 운영은 보호가 넓어지지 않아(날짜가 틀리면 그날 16:00~17:00 보호가 빠짐)"을 코드로 대조했다. 목록에 없는 날은 `session`의 정규장 09:00~15:30(`ExchangeCalendar.java:129`) → 보호 08:00~16:00이고, 실제 수능일 장이 16:30까지면 보호 끝은 17:00이어야 하므로 16:00~17:00 공백이 맞다. 목록에 잘못 들어간 날은 합친 구간 09:00~16:30 → 08:00~17:00으로 넓어질 뿐이라 안전 쪽이다. 문장은 정확하다.
- **O2 (권고, 후속 데이터)**: 새 절차는 "수능일 출처를 교육부(한국교육과정평가원) 시행기본계획 공고로 둔다"고 하지만, 지금 데이터는 아직 이 절차를 따르지 않는다. `krx.json:55` 2026-11-19의 출처 `KRX-CSAT`(`krx.json:13`)는 2025-11-13 사례 기사이고, `krx.json:57` 2027-11-18의 출처 `KRX-CSAT-2027`(`krx.json:16`)은 2차 자료(sanedu.com)다. 2026-11-19는 약 5주 뒤이므로, 평가원 2027학년도 시행기본계획 공고와 대조해 출처 키를 새로 바꾸는(runbook 2번 규칙) 데이터 작업을 후속 과제로 두기를 권한다. 이 PR은 절차 문서만 고치므로 막지 않는다. `[가정]` 2027학년도·2028학년도 수능 날짜는 이 검토에서 원문 공고로 확인하지 않았다(컨테이너 밖 자료 미조회).
- **O3 (사소)**: 새 테스트가 `java.util.List`를 완전한 이름으로 쓴다(`ExchangeCalendarTest.java:94,96`). 파일 위 import에 `java.util.List`를 넣는 편이 같은 파일 다른 테스트와 일관되지만 동작 영향은 없다.
- **O4 (기록)**: 새 테스트는 KRX 조기 폐장으로 합치기를 지킨다. 미국 `us.json`은 미확인 날짜가 0건이라(PR #53 QA 기록) 지금 영향은 없고, 같은 `protectionSession` 코드를 시장 구분 없이 쓰므로 별도 미국 테스트는 필요하지 않다고 본다.

## 판정
승인. 점검표 B에 실패·확인 못 함이 없다(B7은 risk-ops 판정 대상으로 표시). 장애 대응(장 시간 보호) 관련 변경으로 분류되면 지침 10장에 따라 risk-ops 검토 후 사용자가 머지한다. 권고 O2(수능일 데이터 출처 갱신)는 머지를 막지 않는 후속 과제다.
