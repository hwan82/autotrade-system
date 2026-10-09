# architect 검토: 2단계 재판정 후속(실녹화 두 번 리플레이 확인 도구, C5 불일치 수정, 일봉 범위 D-038)
- 대상: PR #27 / 커밋 `25a3188` / `git diff origin/main...HEAD` 전체(11개 파일, +389 −19, main `1bd0ddc` 기준)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL(캐시 사용). 다시 `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL, 104개 태스크 모두 실행, 테스트 결과 XML 합계 519건(건너뜀 0, 실패 0, 오류 0). `ReplayCheckTest` 3건 통과.
  - `./gradlew :autotrade-app-gateway:installDist` → `autotrade-app-gateway/build/install/autotrade-gateway/bin/autotrade-gateway` 생성 확인(실행 절차 5번 경로와 일치).
  - `./gradlew :autotrade-app-gateway:replayCheck --args="2026-01-02"`(녹화 없음) → 종료 코드 2.
  - 임시 탐침 테스트(검토 후 삭제, 커밋 안 함): `MarketEventRecorder`에 체결 1건을 넣고 `close()` 없이(비정상 종료 흉내) `ReplayCheck.check` 실행 → `identical=true`, 보고서에 "정상 종료 아니오, 건수 0, … eventsSha256 `-` (파일과 일치 확인됨)", "마지막 줄 잘림: 예", "이벤트 수: 0", "판정: **동일**". R-1의 근거.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 519건 실패 0 |
| B2 | 통과 | `ReplayCheck`는 `com.autotrade.app.gateway`(게이트웨이 소유 패키지)에 있고, 새 의존은 기존 `autotrade-marketdata`·`autotrade-config`뿐(autotrade-app-gateway/build.gradle.kts 변경은 JavaExec 태스크 추가만). processIsolation 금지 프로젝트 목록 변경 없음. ArchitectureTest·ModuleOwnershipTest 통과. 시계 생성 없음(GatewayMain 주석의 "시스템 시계는 진입점뿐" 규칙 유지) |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 새 Gradle 태스크와 확인 도구는 ADR-003 5장 표(docs/adr/ADR-003-market-data.md:150)와 실행 절차(docs/runbooks/stage-2-real-recording.md)에 기록. 결정 D-038은 docs/decisions/README.md:42에 기록, 번호 충돌 없음(원격 브랜치 전체에서 D-038은 이 브랜치뿐, v14.2·D-036은 `claude/project-thread-89zdzi`가 사용해 건너뛴 것 확인) |
| B4 | 통과 | `ReplayCheck`는 `Clock`·난수를 쓰지 않고, 종목 집계는 `TreeMap`·`TreeSet`(ReplayCheck.java:103,134)으로 정렬 순서 고정. 기존 골든 테스트 포함 전체 재실행 통과 |
| B5 | 통과 | 가격은 `BigDecimal`(ReplayCheck.java:148-151, `max`/`min`/`toPlainString`), 수량 합은 `long`. 금액 계산 없음 |
| B6 | 통과 | 새 동작에 `ReplayCheckTest` 3건(동일 판정·요약 손 계산 값, 보고서 파일·종료 코드 0, 손상·없음·인자 오류 종료 코드 2). diff에서 테스트 삭제·`@Disabled` 없음. 단 비정상 종료·0건 녹화 경우의 테스트가 없다(R-1에서 함께 요구) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응 코드 변경 없음. GatewayMain.java는 클래스 주석 1줄만 변경(GatewayMain.java:33), `reservedForRealTrading` 등 코드 변경 없음. `ReplayCheck`는 녹화 파일만 읽고 주문·네트워크 타입을 참조하지 않음 |
| B8 | 통과 | 실행 절차의 `secrets.json` 예시는 자리표시자(`<KIWOOM_APP_KEY>` 등)만 사용. `ReplayCheck` 출력은 메타의 출처·건수·해시와 종목 요약뿐이며 비밀값 경로 없음. diff 전체에서 키·토큰 값 문자열 없음(직접 grep). gitleaks는 로컬에 없어 CI 결과에 맡김 |

## 점검표 밖 발견

### R-1 (수정 필요) 확인 도구가 확인하지 않은 것을 "확인됨"으로 쓰고, 비정상 종료·0건 녹화도 "동일"(종료 코드 0)로 판정한다
- [사실] `RecordedDay.open`은 메타가 `complete=true`이고 `eventsSha256`이 비어 있지 않을 때만 파일 해시를 검사한다(autotrade-marketdata/.../RecordedDay.java:59-64). `DayWriter`는 녹화 시작 때 `complete=false`, 해시 빈 값으로 메타를 먼저 쓴다(DayWriter.java:80).
- [사실] 그런데 `ReplayCheck.Report.toMarkdown`은 메타가 있으면 조건 없이 "`(파일과 일치 확인됨)`"을 붙인다(ReplayCheck.java:210-214). 판정 `identical()`은 두 해시와 건수만 보고(ReplayCheck.java:200-202) 정상 종료 여부, 마지막 줄 잘림, 이벤트 0건을 보지 않는다.
- [사실] 위 탐침 실행에서 비정상 종료 녹화가 "eventsSha256 `-` (파일과 일치 확인됨)", "이벤트 수: 0", "판정: **동일**"로 나왔다.
- 영향: 이 도구의 출력은 2단계 완료 증거(MD-06, QA C1)로 쓰인다. Ctrl+C 정상 종료가 안 된 녹화(실행 절차 5번이 경고하는 경우)나 구독 거절로 0건인 녹화(QA 재판정 "가장 큰 위험" 2번)가 "동일·확인됨"으로 올라가면 증거가 거짓이 된다. 두 번 리플레이 동일성은 같은 코드가 같은 파일을 읽으니 거의 항상 참이라, 이 도구에서 실제로 의미 있는 검사는 무결성·완전성 쪽이다.
- 요구: (1) "파일과 일치 확인됨"은 `complete && !eventsSha256.isEmpty()`일 때만 쓰고, 아니면 "검사 안 됨(정상 종료 아님)"으로 쓴다. (2) 정상 종료 아님, 마지막 줄 잘림, 이벤트 0건(또는 체결 0건 종목)이면 판정을 "동일"과 구분되는 값으로 내고 종료 코드도 0이 아니게 한다(예: 3 = 리플레이는 동일하나 완료 증거로 쓸 수 없음). (3) 이 두 경우의 테스트를 더한다.

### R-2 (수정 필요) 원본 대조(G-1) 기준이 KRX 정규장 일봉과 맞지 않을 가능성이 크다
- [사실] 실행 절차 8번은 보고서의 시가·고가·저가·종가·체결 수량 합을 HTS 그날 일봉과 비교하고, "체결 수량 합은 끊김이 0이고 개장부터 마감까지 녹화했을 때만 일봉 거래량과 같아야 한다"고 쓴다(docs/runbooks/stage-2-real-recording.md:24). 녹화 시작은 08:40 전, 종료는 15:40 뒤다(같은 파일 18-19행).
- [사실] `ReplayCheck.SymbolSummary`는 시각·세션 구분 없이 받은 체결 전부로 OHLC와 수량 합을 만든다(ReplayCheck.java:159-174). 키움·한투 플러그인에도 장전·장후 시간외나 거래소(NXT) 구분 처리가 없다(`grep -rn "시간외\|NXT\|session" autotrade-broker-kiwoom autotrade-broker-kis` 결과 없음).
- [추론] KRX 일봉 거래량에는 장전·장후 시간외 거래분이 들어가는데 녹화는 15:40에 끝나므로, 끊김이 0이어도 수량 합은 일봉 거래량보다 작게 나올 수 있다. 장전 시간외(08:30~08:40) 체결이 실시간으로 오면 "시가"가 전일 종가로 잡힌다. 2025년 이후 HTS 차트가 KRX·NXT 통합 값을 보여 주는 설정이면 고가·저가·거래량이 KRX 단독과 다르다. 이 경우 정상 수신을 "해석 오류"로 오판하거나, 반대로 차이를 "시간외 탓"으로 넘겨 실제 해석 오류를 놓친다. 실제 메시지로 확인하지 못했다(Q-015).
- 요구: 실행 절차 8번에 대조 기준을 못박는다. KRX 단독(통합 아님) 일봉과 대조, 정규장(09:00~15:30, 시가 단일가 포함) 체결만 비교, 수량 합은 "같아야 한다" 대신 "정규장 거래량과 비교하고 차이를 적는다"로 바꾼다. 도구는 거래소 현지 시각 기준 정규장 안·밖 체결을 나눠 요약하거나(가능하면 기존 거래소 캘린더의 세션 시간 사용), 최소한 정규장 밖 체결 수를 별도 열로 낸다. 보고서에 첫·마지막 체결 시각이 있어 사람이 알아챌 수는 있지만, 완료 증거의 판정 기준은 문서에 있어야 한다.

### 권고(비차단)
- R-3 메모리: `check`는 1회차의 `RecordedDay`·이벤트 목록을 들고 있는 채로 2회차를 읽어(ReplayCheck.java:89-96, `Run` 레코드가 `day`와 `events`를 모두 보관) 하루치 줄과 이벤트가 두 벌 이상 동시에 메모리에 있다. 호가(`OrderBookSnapshot`, 10단 × 양방향)가 많은 실제 하루에서 기본 힙으로 OOM이 날 수 있다 `[추론]`. 1회차는 해시·건수·요약만 남기고 놓은 뒤 2회차를 읽게 하거나, 실행 절차에 `-Xmx` 안내를 둔다. 녹화 파일은 읽기만 하므로 데이터 손실 위험은 없다.
- R-4 해시 입력: 이벤트 해시가 레코드 `toString()`에 기대고 있다(ReplayCheck.java:113). 같은 JVM 실행 안의 두 회차 비교에는 문제없지만, 보고서의 SHA-256 값을 다른 날·다른 버전과 비교하는 용도로는 쓸 수 없다. 보고서에 "같은 실행 안 비교용"이라고 적거나 `RecordingCodec.encode` 기반 정규 표현으로 바꾸기를 권한다.
- R-5 남은 D-008 근거: QA C5 표본 밖에서 대체된 D-008을 근거로 드는 주석이 남아 있다. 특히 autotrade-config/.../TossPollingScope.java:3 "토스는 실시간이 없어 보유 종목 보호용으로만 쓴다(D-008)"는 D-024(토스 실시간 2순위)와 어긋난다. 그 밖에 SourcePlanner.java:15, BrokerPlugin.java:40, LastPrice.java:9, PollingQuoteSource.java:9. 보호 폴링 근거로서는 D-008 내용이 유지되는지 확인 후 D-024로 바꾸기를 권한다(다음 C5 재대조에서 같은 지적이 다시 나올 수 있다).
- 참고(QA 판단 사항): D-038은 MD-09 인수 조건("품질 리포트가 `experiments/_data-quality/`에 남는다")의 실데이터 충족을 6단계 전까지로 미룬다. 2단계 완료 기준(team-design.md:152)은 MD-06뿐이라 완료 판정과 직접 충돌하지는 않지만, 기획서 14장 2단계 행의 요구사항 열은 여전히 MD-01~10이다. 단계 완료 판정 때 QA가 이 범위 조정을 받아들일지 판단할 몫이다.
- 확인한 C5 수정 5곳은 모두 diff에 반영되어 있다: 기획서 14장 상태표(PR 번호를 `git log origin/main`의 머지 커밋과 대조해 일치), ADR-004 1장(`3ffa851`이 #10 머지 커밋임을 확인), MarketDataSet 주석 ADR-003, GatewayMain 주석(D-024·D-028·D-033 내용과 일치), 시세 조사 1.5절과 Q-015(사용자 확인 근거를 QA pr-10 재검토로 연결).

## 판정
수정 필요(R-1, R-2)

## 재검토 2026-10-09
- 대상: PR #27 / 커밋 `ab20acf` / `git diff origin/main...HEAD` 전체(main `1bd0ddc` 기준). 앞 검토 뒤 바뀐 것은 `git diff 2e6ee04 ab20acf`(9개 파일)로 따로 확인
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL, 104개 태스크 모두 실행, 테스트 결과 XML 합계 521건(건너뜀 0, 실패 0, 오류 0). `ReplayCheckTest` 5건 통과(앞 검토보다 2건 늘어남).
  - 앞 검토의 임시 탐침을 다시 실행(검토 뒤 삭제, 커밋 안 함): 체결 1건을 넣고 `close()` 없이 끝낸 녹화(메타 `complete=false`, 해시 빈 값) → `verdict=INCOMPLETE`, 보고서는 "eventsSha256 `-` (파일 대조 안 함: 정상 종료 녹화만 대조)", "판정: **불완전**…". 앞 검토에서 재현한 거짓 "확인됨"·"동일"이 더는 나오지 않음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 521건 실패 0 |
| B2 | 통과 | `ReplayCheck`의 새 의존은 `com.autotrade.marketdata.calendar`(기존 의존 모듈 `autotrade-marketdata`)뿐. build.gradle.kts 의존 변경 없음. ArchitectureTest·ModuleOwnershipTest 통과. 다른 모듈 변경(SourcePlanner, BrokerPlugin, TossPollingScope, LastPrice, PollingQuoteSource)은 주석 한 줄씩만 바뀜 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `BrokerPlugin`은 Javadoc만 바뀜(BrokerPlugin.java:40). config-keys.md는 `market-data.toss-polling-scope` 근거 열에 D-024만 더함(키·기본값·범위 그대로) |
| B4 | 통과 | 시계·난수 없음. 정규장 판정은 녹화 날짜와 내장 캘린더(`ExchangeCalendar.bundled`)로만 정한다(ReplayCheck.java:110-126). 집계는 `TreeMap`(ReplayCheck.java:139) |
| B5 | 통과 | 가격 `BigDecimal`(ReplayCheck.java:171-174), 수량 `long` |
| B6 | 통과 | 새 동작 테스트 추가: 메타 없음 → 불완전·종료 코드 3(`정상_종료_메타가_없으면_두_회차가_같아도_불완전으로_보고_3을_돌려준다`), 잘림·0건 → 불완전, 해시 다름 → 다름·종료 코드 1(`잘림이나_0건이면_불완전이고_회차가_다르면_다름이다`), 정규장 밖 체결 분리(기존 테스트에 08:30·15:45 체결 추가, 손 계산 값 `2 / 150`). 기존 단언은 바뀐 요약 열에 맞춰 갱신됐고 약화되지 않음(`hashVerified`·"일치 확인됨" 단언 추가). 삭제·`@Disabled` 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응 코드 변경 없음. 바뀐 운영 코드는 `ReplayCheck`(녹화 파일 읽기 전용)와 주석뿐 |
| B8 | 통과 | 추가된 출력은 판정·정규장 구간·종목 요약뿐. diff에 키·토큰 값 없음(직접 확인). gitleaks는 로컬에 없어 CI에 맡김 |

### 앞 지적 처리
- R-1 해결: `hashVerified()`가 `complete && !eventsSha256.isEmpty()`일 때만 참(ReplayCheck.java:239-241)이고, 그때만 "(파일과 일치 확인됨)"을 쓴다(ReplayCheck.java:263). `verdict()`는 두 회차가 다르면 DIFFERENT(1), 같아도 비정상 종료·잘림·0건이면 INCOMPLETE(3)(ReplayCheck.java:243-251). 실행 절차 7번이 종료 코드 3의 뜻과 "다른 날 다시 녹화"를 적었다. 테스트와 위 탐침으로 확인했다.
- R-2 해결: 시가~수량은 캘린더 정규장 구간(마감 뒤 1분 유예, `[가정]`으로 표시, ReplayCheck.java:58-59, 183-186) 체결만 집계하고, 그 밖은 "정규장 밖" 열에 건수 / 수량으로 낸다. 보고서와 실행 절차 8번이 대조 상대를 KRX 단독 정규장 일봉으로 정했고, 수량은 "같아야 한다" 대신 "차이를 적는다"로 바뀌었다. 캘린더에 날짜가 없으면 전부 집계하고 그렇게 했다고 적는다.
- R-3 해결: `Run`이 `RecordedDay`와 이벤트 목록을 들고 있지 않아(ReplayCheck.java:128-129) 1회차 줄 목록은 2회차를 읽기 전에 해제될 수 있다. 회차 하나의 줄 목록(`RecordedDay` 전체 적재)은 여전히 메모리에 올라가는데, 이는 기존 `RecordedDay` 설계의 한계라 이 PR 범위 밖이다.
- R-4 해결: "같은 실행 안의 두 회차 비교에만 쓴다"를 클래스 주석에 적었다(ReplayCheck.java:44-45).
- R-5 해결: 대체된 D-008을 근거로 들던 주석 5곳을 D-024로 바꿨다. config-keys.md는 D-008을 남기고 D-024를 더했는데, 보호 폴링의 원래 근거라 맞다.

### 점검표 밖 발견(비차단)
- 이벤트가 모두 `DataGap`이거나 호가뿐이고 체결이 0건이면, `eventCount > 0`이라 판정은 SAME이 된다(ReplayCheck.java:247). 종목 표의 체결 수 0과 끊김 열로 사람이 알아볼 수는 있다. 완료 증거로 올릴 때 QA가 종목별 체결 수를 함께 보면 된다. 다음에 고칠 때 "체결 0건 종목이 있으면 불완전"을 검토하기를 권한다.
- 마감 뒤 1분 유예와 정규장 판정에 `exchangeTime`을 쓰는 방식은 실제 메시지 형식이 `[가정]`인 동안의 추정이다(Q-015). 실제 녹화에서 종가 단일가 체결의 시각이 15:31 뒤로 찍히면 종가가 "정규장 밖"으로 빠지니, 첫 실녹화 보고서에서 함께 확인하기를 권한다.

## 판정
승인

## 재검토 2026-10-09 (2)
- 대상: PR #27 / 커밋 `199044e` / `git diff origin/main...HEAD` 전체(main `1bd0ddc` 기준). 앞 재검토(`ab20acf`) 뒤 바뀐 것은 `git diff f9ebaa4 199044e`로 확인: `docs/research/market-data-limits.md` 1줄(1.5절 "최종 판정" 항목 끝에 사용자 보충 문장 추가)뿐, 코드 변경 없음
- 점검표: B
- 직접 실행한 것: `./gradlew build` → BUILD SUCCESSFUL(104개 태스크, 16개 실행·88개 최신). 코드 변경이 없어 컴파일·테스트 결과는 앞 재검토의 `--rerun-tasks` 결과(521건, 실패 0)와 같은 입력이다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL |
| B2 | 통과 | 이번 변경은 문서뿐. 모듈·의존 변경 없음, ArchitectureTest 통과 |
| B3 | 통과 | 계약 변경 없음. 추가 문장이 가리키는 근거를 대조함: "이 문서 1장"은 market-data-limits.md:13 "1. 국내 실시간 구독 한도와 REST 레이트리밋", "ADR-003 2.1, 발급 60초 하한·거부 뒤 30분까지"는 docs/adr/ADR-003-market-data.md:33(2.1절 토큰 항목: 발급 실패 뒤 60초, 거부 판정 뒤 2분부터 두 배씩 30분에서 멈춤)과 일치 |
| B4 | 통과 | 코드 변경 없음, 앞 재검토의 전체 재실행 결과 유지 |
| B5 | 통과 | 코드 변경 없음 |
| B6 | 통과 | 테스트 변경·삭제 없음 |
| B7 | 해당 없음 | 실전 경로 변경 없음 |
| B8 | 통과 | 추가 문장에 키·계좌번호·토큰 없음(사용자 발언 인용과 시각뿐) |

## 점검표 밖 발견
- 없음. 사용자 보충("호출은 무료, 호출 횟수 제한 있음, 요금은 매매 수수료만")은 같은 절의 최종 판정(무료)과 어긋나지 않고, 제한 초과가 비용이 아니라 거절로 나타난다는 해석은 `[추론]` 표시가 없지만 사용자 진술과 ADR-003 2.1의 재시도 하한 설계를 잇는 설명이라 판정에 영향이 없다.

## 판정
승인
