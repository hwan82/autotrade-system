# risk-ops 검토: 수능일 출처를 교육부 시행일 보도자료로 바꾸고 봄 재대조 단계 추가 (PR #54 QA Q1·R1)
- 대상: PR #55 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `5577464` / main(`a0f41cc`, GitHub REST로 확인한 PR base SHA) 대비 diff 전체. 바뀐 파일 2개(PR 본문·커밋 메시지는 판정 근거로 쓰지 않음).
  - `autotrade-marketdata/src/main/resources/com/autotrade/marketdata/calendar/krx.json` (+5 −4: `checkedOn`, 출처 키 `KRX-CSAT-2027` 삭제, `KRX-CSAT-2026-MOE`·`KRX-CSAT-2027-MOE` 추가, 수능일 두 줄의 `source`만 교체)
  - `docs/runbooks/collector.md` (3.6: 1번 수능일 출처 문장 수정, 새 2번 "매년 3~4월 재대조" 추가, 뒤 번호 하나씩 밀림)
- 점검표: A
- 직접 실행한 것(스크래치 폴더의 별도 clone, `5577464`):
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(130 tasks, 3분 4초). Maven Central 429 없음.
  - 테스트 결과 XML 집계(테스트 / 실패 / 오류 / 건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 84/0/0/0, `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0, `:autotrade-selection` 34/0/0/0, `:autotrade-sim` 115/0/0/0. 건수는 PR #54 검토 때와 같다(테스트 변경 없음).
  - 데이터 동등성 확인(python으로 base·head의 `krx.json` 비교): 특별 운영일 4건의 `(date, open, close, verified)`가 base와 head에서 같다(`2026-11-19 10:00~16:30 false`, `2027-11-18 10:00~16:30 false` 포함). 휴장일 33건 그대로. 바뀐 것은 출처 키와 출처 문구, `checkedOn`뿐이다. 두 날짜 모두 목요일.
  - 출처 키 참조 확인: 항목이 쓰는 키 7개가 모두 `sources`에 있다(없는 키 0). 항목이 쓰지 않는 키는 `KRX-CSAT` 1개(새 출처 문구가 관행 근거로 글로 참조).
  - 출처 원문 확인(WebFetch): KDI 경제정책정보 `materialView.do?num=255989` 제목 "2027학년도 대학수학능력시험은 2026년 11월 19일(목)에 시행됩니다", 2024-08-16. `num=267335` 제목 "2028학년도 대학수학능력시험은 2027년 11월 18일(목)에 시행됩니다", 2025-06-02. heraldk 2026-03-30 기사: 평가원 2027학년도 시행기본계획 3/31 발표, 수능 11/19. 즉 두 날짜는 이 검토에서 1차 자료 게재본으로 확인했다(교육부 사이트 원본은 접근 못 함, 게재본 기준). 1시간 늦춤 자체는 여전히 관행이고 거래소 공지 전이다.
  - 영향 범위 grep: 운영 코드 변경 0건. 이 데이터를 읽는 운영 코드는 `ExchangeCalendar.bundled`을 거쳐 `SessionProtection.check`(호출처 `RunGuard`·`ExperimentGate`), `KrxMarketRules`, `ReplayCheck`, `Gateway`(시간대만), 선정·실험 거래일 계산이다. `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-risk` 소스에 `ExchangeCalendar`·`SessionProtection`·`MarketRules` 참조 0건.
  - 출처 표가 바뀌므로 `SnapshotSelectionData.calendarDigest`(`SnapshotSelectionData.java:93-99`, 출처 표+수록 범위 해시) 값이 바뀐다. 번들 캘린더의 다이제스트 값을 고정한 테스트는 없다(grep).
  - diff에서 `@Disabled`·키·토큰·계좌 문자열 검색 0건.

## 실전 관련 PR 여부 (지침 10장)
**실전 관련 PR이다(사용자 직접 머지 대상).** 운영 코드는 바꾸지 않지만 `krx.json`은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 유일한 입력이고, runbook 3.6은 그 입력을 갱신하는 운영 절차다. 10장은 "장애 대응을 건드리는 PR"을 사용자 머지로 둔다. 같은 파일·같은 절차를 바꾼 PR #53·#54가 같은 이유로 실전 관련으로 판정됐다. 이번 변경은 보호 판정 결과를 바꾸지 않는(날짜·시각·`verified` 그대로) 출처 정정과 확인 단계 추가라 위험은 낮지만, 닫힌 쪽(사용자 머지)으로 둔다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(diff 파일 2개, Guard 관련 0). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 48건 통과 |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 31건 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅을 건드리지 않음. 운영 코드 변경 0건 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·계좌별 킬스위치 변경 없음 |
| A8 | 통과 | 장 시간 보호 판정 입력(`specialSessions`의 date/open/close/verified)이 base와 같아 `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137-155`)과 `SessionProtection.check`(`SessionProtection.java:52-73`)의 결과가 바뀌지 않는다. 수능일 두 날은 계속 `verified: false`라 정규장과 합친 08:00~17:00(서울)로 보호된다. 기존 테스트 `ExchangeCalendarTest.java:74-75`(2027-11-18 16:59 `Inside`, 17:00 `Clear`)와 `:153`(2026-11-19 미확인 목록 포함) 통과. 날짜가 이제 1차 자료 게재본으로 확인돼(위) PR #54 N1에서 남긴 "날짜가 틀려 16:00~17:00 보호가 빠지는" 위험이 줄었다. 프로세스 분리 변경 없음 |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절, 5.2의 6번 변경 없음. 실전 엔진은 캘린더를 읽지 않음(grep 0건) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. 새로 넣은 것은 공개 URL(eiec.kdi.re.kr 2건, heraldk.com 1건)과 날짜뿐(diff 전문 검토) |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `RunGuardTest`, `ExperimentGateTest`, `ExchangeCalendarTest` 그대로. 동작 변경이 없어 새 테스트는 필요 없다 |
| A12 | 통과 | 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음. 6장 #10·5.2 0번의 전제("장 시간에는 단발 명령이 돌지 않는다")의 입력 날짜 근거가 강해졌다. runbook 새 2번(3~4월 재대조)은 이 전제를 지키는 확인 단계를 늘리는 방향이다 |
| A13 | 통과 | 시장 분기 코드 추가 없음. 캘린더 시각은 `"zone": "Asia/Seoul"` 현지 시각 그대로. 금액 계산 변경 없음 |
| A14 | 통과 | D-064(`docs/decisions/D-064-krx-2027-calendar.md` 결정 3번 "수능일은 교육 당국 시행 계획, 시각 변경은 관행")와 바뀐 데이터·runbook이 충돌하지 않는다. 동작이 바뀌지 않아 새 결정은 필요 없다. 단 runbook 자기 절차의 기록 단계는 아래 N1 |

## 점검표 밖 발견
- **N1 (권고, 문서 — 머지를 막지 않음)**: runbook 3.6 마지막 단계(head 6번, base 5번)는 "바꾼 사실을 결정 로그에 남긴다"인데 이 PR은 `krx.json` 출처 표를 바꾸면서 결정 로그(D-064 또는 README 표)에 아무것도 남기지 않았다. 출처 표가 바뀌어 선정 결과 `inputs.calendar_digest`가 이 커밋을 경계로 달라지므로, 나중에 다이제스트가 다른 두 결과를 대조할 때 이유를 찾을 곳이 커밋 이력뿐이다. D-064에 "갱신 이력" 한 줄(날짜, 바뀐 출처 키, 날짜·`verified` 변화 없음, PR #55)을 더하기를 권한다. 안전 판정에는 영향이 없다.
- **N2 (권고, 후속 코드 — architect·data-engineer)**: `ExchangeCalendar` 생성자(`ExchangeCalendar.java:41-70`)는 항목의 `source` 키가 `sources` 표에 있는지 검사하지 않는다. 이번 PR은 직접 확인한 결과 빠진 키가 없지만, runbook이 "날짜·`verified`를 바꿀 때 출처 키를 새 키로" 바꾸게 하므로 키 오타가 나면 조용히 들어가고, 출처 표에 새 키가 없으면 `calendar_digest`도 바뀌지 않는다(runbook이 막으려는 바로 그 경우). 로딩 시 없는 키를 예외로 거부하는 검사와 실패 테스트를 후속 과제로 권한다.
- **N3 (기록)**: `KRX-CSAT` 키는 이제 어떤 항목도 쓰지 않고 새 출처 문구가 관행 근거로 글로 참조한다. 지워도 되지만 남겨 두는 편이 근거 추적에 낫다. 문제 아님.
- **N4 (기록, PR #54 N3 유지)**: 캘린더 수록 범위 만료 자동 경보 없음(다음 마감 서울 2027-12-31 00:00). 수능일 개장 지연 자체는 여전히 관행(`verified: false`)이라 거래소 공지 뒤 runbook 3번 절차로 바꿔야 한다. 2026-11-19 공지는 보통 직전에 나오므로 그 전까지 보호는 합친 구간으로 유지된다.

## 판정
승인. 점검표 A에 실패·확인 못 함이 없다. 실전 관련 PR(장애 대응: 장 시간 보호 판정의 입력 데이터와 갱신 절차)이라 지침 10장에 따라 사용자가 직접 확인하고 머지하며, 지침 11장의 독립 QA 관문 대상이다. N1·N2는 머지를 막지 않는 권고다.
