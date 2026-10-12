# QA 검토: 수능일 출처를 교육부 시행일 보도자료로 바꾸고 봄 재대조 단계 추가 (PR #54 QA Q1·R1)
- 대상: PR #55 / 커밋 7f2cfc2c0af6a8fdf0ff41867d607140de811091 / main(a0f41ccfe5c3cd41ad47e81fe2b84df4e1b0c488) 대비 diff 전체
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 운영 코드는 바꾸지 않지만 `krx.json`은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10, D-058·D-063·D-064)의 판정 입력이고, runbook 3.6은 그 입력의 갱신 절차다. 지침 10장 "장애 대응을 건드리는 PR"에 해당하므로 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-55/architect.md`, `architect-2.md`, `risk-ops.md`)은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...origin/pr55 --stat`: 6파일, +130 −10. `krx.json`(+5 −4), `docs/decisions/D-064-krx-2027-calendar.md`(+5 −1), `docs/runbooks/collector.md`(+6 −5), 검토 원문 3개(신규). Java 소스·테스트·빌드 스크립트·스키마·설정 키 변경 0건. base는 현재 main HEAD(a0f41cc), GitHub mergeable_state `clean`
  - `./gradlew build`: Maven Central 429로 9회 실패(실패 로그마다 429 확인) 뒤 10회째 **BUILD SUCCESSFUL**(130 tasks). 재시도 루프는 하나만 돌렸다. 테스트 결과 XML 합계 tests 890, failures 0, errors 0, skipped 0. 모듈별: marketdata 84, app-collector 61, app-experiment 11, risk 55, architecture-test 31, app-engine 48, selection 34, sim 115, app-lab 131 등
  - `krx.json` 데이터 동등성(python, base `a0f41cc`와 head 비교): 최상위 키 중 바뀐 것은 `checkedOn`, `sources`, `specialSessions`뿐. 휴장일 33건은 `source`까지 완전히 같다. 특별 운영일 4건은 `source`를 뺀 모든 필드(`date`, `open`, `close`, `name`, `verified`)가 같다. 출처 표에서 `KRX-CSAT-2027`이 빠지고 `KRX-CSAT-2026-MOE`, `KRX-CSAT-2027-MOE`가 들어왔다. 항목이 쓰는 출처 키 중 `sources`에 없는 키 0개, 쓰이지 않는 키 `KRX-CSAT` 1개(새 출처 문구가 관행 근거로 글로 참조). 2026-11-19, 2027-11-18 모두 목요일
  - 1차 출처 대조(WebFetch, 교육부 사이트 원본이 아닌 KDI 경제정책정보 게재본): `eiec.kdi.re.kr/policy/materialView.do?num=255989` 제목 "2027학년도 대학수학능력시험은 2026년 11월 19일(목)에 시행됩니다", 교육부 인재정책실 인재정책기획관 인재선발제도과, 2024.08.16. `num=267335` 제목 "2028학년도 대학수학능력시험은 2027년 11월 18일(목)에 시행됩니다", 같은 과, 2025.06.02. 헤럴드경제 `2026033018340941404`(2026-03-30 18:34 입력) "평가원은 31일 … '2027학년도 대학수학능력시험 시행기본계획'을 발표", 수능일 11월 19일. `krx.json` 출처 문구와 모두 일치한다
  - 영향 범위 grep(main 소스): `ExchangeCalendar`·`SessionProtection` 참조가 `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-risk`의 `src/main`에 0건. 보호 판정 호출은 수집기 `RunGuard`, 실험 실행 명령 `ExperimentGate`
  - 다이제스트 영향: `SnapshotSelectionData.calendarDigest`(`SnapshotSelectionData.java:93-99`)는 시장·수록 범위·출처 표(키 정렬)만 해시하므로 이 PR로 값이 바뀐다. 저장된 다이제스트를 현재 캘린더와 대조하는 운영 코드는 없다(`ResultBundleReader.java:238`은 읽기만, `CombinationExperiment`의 `--verify-determinism`은 같은 실행 안에서 두 번 돌려 비교). 저장소의 실험 결과(`experiments/EXP-20261008-STR01`)에 다이제스트 값 없음(grep)
  - diff에서 `@Disabled`·키·토큰·계좌 문자열: 검토 원문 안의 서술 문장 외 0건
  - CI(head 7f2cfc2): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 모두 success

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 관련 코드·설정·테스트 변경 없음(바뀐 파일은 JSON 리소스 1개와 문서). `:autotrade-risk:test`, `:autotrade-app-engine:test` 통과(위 빌드) |
| A2 | 통과 | 주문 출구 코드와 ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 통과(위 빌드) |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음(운영 코드 변경 0건) |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 장 시간 보호 판정 입력(특별 운영일의 `date`/`open`/`close`/`verified`, 휴장일 전부)이 base와 같아(위 python 대조) `ExchangeCalendar.protectionSession`(`ExchangeCalendar.java:137-155`)과 `SessionProtection.check`의 결과가 바뀌지 않는다. 출처 키·문구는 판정 코드가 읽지 않는다(`ExchangeCalendar.java:41-70`은 `sources`를 복사만 함). 수능일 두 날은 계속 `verified: false`라 정규장과 합친 구간(서울 08:00~17:00)으로 보호된다. 날짜가 이제 1차 자료 게재본으로 확인돼 "날짜가 틀려 16:00~17:00 보호가 빠지는" 위험(PR #53 QA R1)이 줄었다. 프로세스 분리 변경 없음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 변경 없음. 실전 엔진은 캘린더를 읽지 않음(grep 0건) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. 새로 넣은 것은 공개 URL(eiec.kdi.re.kr 2건, heraldk.com 1건)과 날짜·문장뿐. CI gitleaks success |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `RunGuardTest`, `ExperimentGateTest`, `ExchangeCalendarTest`, 장애 테스트 그대로. 동작 변경이 없어 새 테스트는 필요 없다 |
| A12 | 통과 | 장애표 17종, O1~O8, 장애 테스트 8종의 문구·전제 변경 없음. 장애표 #10·5.2 0번 전제("장 시간에는 단발 명령이 돌지 않는다")의 입력 날짜 근거가 1차 자료로 강해졌고, runbook 새 2번(봄 재대조)은 그 전제를 지키는 확인 단계를 늘린다. 다만 시험일 급변경은 절차에 없다(아래 Q1) |
| A13 | 통과 | 시장 분기 코드·금액 계산 변경 없음. 캘린더 시각은 `"zone": "Asia/Seoul"` 현지 시각 그대로 |
| A14 | 통과 | D-064 결정 3번("수능일은 교육 당국 시행 계획, 시각 변경은 관행")과 바뀐 데이터·runbook이 충돌하지 않는다. 출처 표 변경과 `calendar_digest` 변화가 `D-064-krx-2027-calendar.md:39-41` "갱신 이력"에 기록됐고, 적힌 사실(키 두 개, 출처 날짜, 날짜·시각·`verified` 불변, runbook 번호 이동)을 `krx.json`·runbook과 대조해 맞다. `D-064:36`의 runbook 참조 "3.6 3번 "한국거래소 공지 대조""는 `collector.md:68`(공지 대조, 출처 키 교체 규칙)과 맞다. 동작 변경이 없어 새 결정 번호는 필요 없다. PR #54 QA Q1(7월 시점에 시행기본계획 없음)은 새 1번(2년 전 교육부 시행일 보도자료, 2028학년도분 2025-06-02로 실제 확인)과 새 2번(봄 재대조)으로 해소, R1(수능일 두 줄이 2차 자료)은 위 1차 출처 대조로 해소됐다 |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(429 재시도 후), 890건 실패·오류·건너뜀 0 |
| B6 | 통과 | 새 동작 없음(출처 문자열, 절차·결정 문서뿐). 건너뛰거나 지운 테스트 없음(테스트 diff 0줄) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | diff에 외부 호출 코드가 없다(운영 코드 변경 0건). 새로 생긴 호출 지점 0건. 캘린더는 번들 리소스(`ExchangeCalendar.bundled`)로 읽고 네트워크를 쓰지 않는다 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. runbook 절차가 쓰는 확인 출처(교육부 보도자료, 평가원 시행기본계획, 한국천문연구원 월력요항, 거래소 공지)는 사람이 보는 무료 공개 자료다 |
| 8.3 사용자 옵션 제어 | 통과 | 켜고 끌 유료 경로가 없다. 새 설정 키 없음 |
| 8.4 폭주 방지 | 통과 | 새 반복·재시도 경로 없음 |
| 8.5 실주문 비용 | 통과 | 주문 경로 변경 없음. 실전 엔진이 캘린더를 읽지 않아 이 변경이 실주문으로 이어질 경로가 없다 |

## 위험 시나리오 시도 (지침 5장 5번)
1. **출처 키만 바꾸고 날짜를 잘못 고친 경우(이 PR 형태의 갱신이 틀릴 때)**: 이 PR은 `specialSessions`의 `source`만 바꿨고 다른 필드는 base와 같다(python 대조). 보호 판정은 `source`를 읽지 않으므로 이 PR로 생기는 보호 공백은 없다. 다만 다음 갱신에서 출처 키를 표에 없는 이름으로 오타 내도 로더가 거부하지 않는다(`ExchangeCalendar.java:41-70`). 그러면 출처 표가 바뀌지 않아 `calendar_digest`도 그대로 남아, runbook 3번이 막으려는 "거래일이 달라져도 같은 다이제스트" 경우가 생긴다. 개발 쪽 risk-ops N2·architect O2와 같은 후속 과제이며 이 PR의 데이터는 무결하다.
2. **수능이 공고 뒤 급히 연기되는 경우**: 실제로 2017학년도 대체로 2018학년도 수능(포항 지진, 전날 1주 연기)과 2021학년도 수능(코로나19, 12-03으로 연기)처럼 시험일이 바뀐 적이 있다 `[추론: 일반 공개 사실, 이 검토에서 1차 자료로 대조하지 않음]`. 그러면 캘린더의 옛 날짜는 정규장과 합친 08:00~17:00로 더 넓게 막혀 안전 쪽이지만, 새 시험일은 캘린더에 없어 정규장 보호(08:00~16:00)만 적용되고, 거래소가 그날 1시간 늦추면 서울 16:00~17:00에 수집기·실험 명령이 시작될 수 있다(실전 엔진과 같은 PC 자원 공백, 지침 4장 6번). 새 runbook 2번은 3~4월 1회 재대조뿐이고 3번의 "연중 추가 공지"는 선거일·임시공휴일만 예로 든다. → Q1
3. **봄 재대조가 빠진 경우**: runbook 2번을 놓쳐도 1번에서 넣은 날짜는 2년 전 교육부 보도자료가 근거라, 시행기본계획과 다를 가능성은 낮다 `[추론]`(2026년분은 두 자료가 같은 날로 확인됨). 틀리면 시나리오 2와 같은 16:00~17:00 공백이 생긴다. 사람 절차라 자동 경보는 없다(개발 쪽 risk-ops N4: 수록 범위 만료 경보도 없음).
4. **다이제스트 변경이 기존 결과 재현을 깨는 경우**: 이 머지 뒤 같은 입력으로 선정을 다시 돌리면 `inputs.calendar.sources_digest`가 이전 결과와 다르다. 저장된 다이제스트를 현재 값과 대조해 멈추는 운영 코드는 없어(위 grep) 실행이 멈추지는 않고, 결과 비교 시 차이 원인은 D-064 갱신 이력에 적혀 있다. 결정론 위반이 아니라 입력 변경이다.

## 점검표 밖 발견
- **Q1 (권고, 절차)**: runbook 3.6에 "수능일 변경·연기 공지(교육부·평가원)가 나오면 즉시 날짜를 고치고 출처 키를 새 키로 바꾼다"를 3번의 연중 추가 공지 예시나 별도 줄로 넣기를 권한다(위 시나리오 2). 공고 뒤 급변경은 드물고 주문 경로가 아니라 머지를 막지 않는다.
- **R1 (권고, 문서 정확도)**: runbook 1번 "보통 시험 2년 전 발표"는 실제 사례 기준 약 2년 3개월(2024-08-16 → 2026-11-19), 약 2년 5개월(2025-06-02 → 2027-11-18) 전이다. 7월 갱신 시점에 다음 해 날짜가 이미 나와 있다는 결론은 맞으므로 동작 영향은 없다.
- **R2 (기록)**: 개발 쪽 risk-ops 원문은 커밋 5577464 기준이다. 그 뒤 30c6110(D-064 갱신 이력·참조 정정)과 7f2cfc2(검토 원문)는 risk-ops가 다시 보지 않았다. 이 검토에서 두 커밋의 diff를 직접 읽었고 동작 변경은 없다.

## 판정
승인. 점검표 A 전체·B1·B6과 과금 검사에 실패·확인 못 함이 없다. 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력과 갱신 절차)이라 머지는 사용자가 직접 한다. PR #54 QA Q1·R1은 이 PR로 해소됐다. 권고 Q1(수능 급변경·연기 공지 반영 단계), R1(“2년 전” 문구)은 머지를 막지 않는 후속 과제다.
