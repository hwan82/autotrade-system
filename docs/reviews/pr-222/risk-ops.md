# risk-ops 검토: D-136 뉴스 제목 스냅샷 수집 kis-news (한국투자 종합 시황/공시 제목, Q-055 닫음)
- 대상: PR #222 / 커밋 `4ea3b026` (코드 동작 검증은 `65878307`, 그 뒤 `fb120ccd` architect 원문, `4ea3b026` javadoc 한 줄·조사 문서 한 줄만 바뀜) / `git diff origin/main...HEAD` 전체(수집기 운영 코드 9파일, 시험 6파일, `ArchitectureTest`, 문서 9파일)
- 점검표: A
- 직접 실행한 것:
  - 공유 작업 트리에서 `./gradlew build` 통과. 이어서 `:autotrade-app-collector:test --rerun`을 되풀이하자 3번 중 2번 실패(`KisNewsClientTest.받은_토큰도_오류_메시지에서_가린다`, `KisNewsCommandTest.종목코드_붙은_제목과_…`). 원인은 같은 작업 트리에서 다른 검토자가 `KisNewsCollector.java`를 변이 시험 중이었음(`git status`에 ` M KisNewsCollector.java`, 실패 시각에 XML 결과 쓰기 충돌). 코드 결함이 아니다.
  - 그래서 `65878307`을 별도 worktree에 꺼내 `./gradlew build` 다시 실행: **BUILD SUCCESSFUL, 138 작업 모두 실행**, 시험 1,910건 실패·오류·건너뜀 0(`autotrade-app-collector` 130, `autotrade-architecture-test` 46, `autotrade-risk` 55, `autotrade-app-engine` 68, `autotrade-app-gateway` 55, `autotrade-broker-kis` 30). 같은 worktree에서 `:autotrade-app-collector:test --rerun` 2번 더 통과.
  - PR 머리 `4ea3b026`의 CI(REST 조회): Gradle build, Secret scan and collector boundary(gitleaks), DB schema, Lab screens 모두 success.
  - diff 전체에서 키·토큰처럼 보이는 문자열 grep: 자리표시자(`<KIS_APP_KEY>`)와 가짜 값(`KISKEYAAAAAAAAAA`, `TOKEN-1`, `K`/`S`)뿐.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`, `autotrade-app-engine`, `autotrade-domain`, `autotrade-config` 변경 없음(`git diff --stat`). RealTradingGuard 관련 시험 포함 `autotrade-risk` 55건·`autotrade-app-engine` 68건 통과(worktree 전체 빌드) |
| A2 | 통과 | 주문 코드 추가 없음. 수집기 한국투자 호출은 `KisNewsClient`의 상수 두 주소뿐(`TOKEN_PATH`·`NEWS_PATH`·`TR_ID`, KisNewsClient.java 34~36행), 공개 메서드는 `page(date,time)` 하나로 경로·TR을 인자로 받지 않음. 시험 `뉴스_제목_조회와_토큰_발급_두_주소만_부르고_키는_주소에_싣지_않는다`가 부른 주소 집합과 계좌 헤더 없음을 고정. `ArchitectureTest.COLLECTOR_READS_ONLY_ITS_SECRETS` 규칙 본문(금지 대상 클래스·패키지)은 그대로이고 `because` 문구만 바뀜(diff 확인), 46건 통과. 다만 이 경계를 기계적으로 지키는 장치가 없다는 점은 아래 발견 2 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음 |
| A4 | 해당 없음 | 주문 재전송 로직 변경 없음 |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음. 뉴스 제목은 아직 어떤 선정 지표에도 연결되지 않음(D-136 6번, `autotrade-selection` 무변경) |
| A7 | 통과 | 계좌별 직렬 큐·토큰 버킷 코드 변경 없음. 수집기는 자기 호출 간격(기본 350ms, 100ms 미만 거부: CollectorMain `parse`, `KisNewsCommandTest` 옵션 시험)만 가짐. 같은 앱키를 실전 계좌와 나눠 쓸 때의 영향은 A8 |
| A8 | **실패** | 수집기는 장 시간 보호 구간 밖에서만 돌고(`ApiCaller.post`도 `beforeCall.run()` = `RunGuard.checkSession`을 먼저 부름, ApiCaller.java 77행), 힙·디스크 보호는 기존 그대로라 자원 측면은 문제없다. 그러나 **수집기가 실전 계좌의 한국투자 앱키로 토큰 발급·REST 호출을 하는 것을 코드로 막지 않는다**. broker-and-fault-design.md 101행("실전 계좌의 앱키·토큰은 실전 엔진 전용이며, 게이트웨이·실험실은 그 키로 토큰 발급… REST 호출을 하지 않습니다")과 D-054 "조회 호출기 연결 PR 승인 조건" R1(33행, "계좌번호까지 설정된 실전 계좌가 있는 한국투자 키로는 조회 호출기를 만들지 않는다… 이 검사가 막는 것을 확인하는 테스트를 함께 넣는다")은 같은 키의 `tokenP` 1분 1회 제한과 초당 20건 한도를 실전 엔진과 나누지 않게 하려는 규칙이다. 게이트웨이(`GatewayMain.reservedForRealTrading`, GatewayMain.java 134행)와 키움 일봉 일괄 명령(`KiwoomDailyMain` 76행)은 코드로 막는다. 이 PR은 runbook 3.9·ADR-008 2.2·config-keys에 "다른 앱키 권장"으로만 두고 D-136 위험 1에서 "코드로 비교하지 않는다"고 적었다. 아침 실행 07:00~08:00은 실전 엔진이 장 전 토큰을 받는 시간대와 겹칠 수 있다 `[추론]`. → 발견 1 |
| A9 | 통과 | 보호 시세 경로(게이트웨이·`autotrade-broker-*`)와 서버 측 손절 코드 변경 없음. 수집기는 장 시간 보호 구간에 호출하지 않아(`RunGuard.checkSession`, 매 GET·POST 전) 장중 보호 폴링과 한도를 다투지 않음. `KisNewsCommandTest`의 장 시간 거절 시험 통과 |
| A10 | 통과 | 키 값은 `collector-secrets.json`에만, 앱키·시크릿·토큰은 헤더·POST 본문으로만(KisNewsClient `getNews`·`token`), 오류는 `Redaction`을 거친 URL·결과 코드만(ApiCaller.post, KisNewsClient `KisRejected`·`read`), 받은 토큰은 실행 중 `Redaction.add`. 시험 `받은_토큰도_오류_메시지에서_가린다`, `거절은_결과_코드만_남기고_…`, `토큰_발급_실패는_재시도하지_않고_…` 통과(worktree). 계좌 칸을 넣으면 파일 전체 거부(`CollectorSecretsTest`, `"account-no"` 시험). 품질 리포트에 제목·일련번호 없음. CI gitleaks success |
| A11 | 통과 | 보호 테스트 `ArchitectureTest`는 javadoc 한 줄과 `because` 문구만 바뀜(규칙 조건 무변경, 직접 diff 확인). 삭제·`@Disabled`·약화 없음. 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작에 실패 경우 시험 있음: 토큰 만료 1회만 재발급, 거절 코드, 토큰 발급 실패 무재시도, 키 없음·https 아님 거부, 커서 형식 거부, 호출 상한 종료 코드 3, 장 시간 거절 |
| A12 | **실패** | 장애표 #17(연결 수·레이트리밋 공유로 실전 조회가 막힘, broker-and-fault-design.md 176행)은 실전 키를 실전 엔진만 쓴다는 101행 전제 위에 서 있다. 이 PR은 실전 키를 쓸 수 있는 새 프로세스(수집기)를 더하면서 그 전제를 코드로 지키지 않고, 대응표·장애 테스트 쪽 보완(감지 방법 또는 활성화 전 확인 절차)도 넣지 않았다. D-136 위험 1은 "7단계 활성화 점검 항목 후보"라고만 적었고 점검표 D나 장애 설계 문서에는 항목이 없다. → 발견 1 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. 수집기 시각은 서울 기준(`CallBudget.SEOUL`), 미국 장 보호는 기존 `SessionProtection` 그대로 |
| A14 | **실패** | D-136은 출처를 사용자 결정으로, 나머지를 "추천안 채택(자율)"으로 기록했다. 그런데 실전 키 분리를 코드 검사 대신 권고로 두는 것은 확정 결정 D-054 R1·D-024 키 분리 규칙(broker-and-fault-design.md 101행)을 수집기에 한해 완화하는 것이고, 지침 8장 2번(실전 영향)·4번(확정 결정과 충돌)에 해당해 자율 채택 대상이 아니다. D-136에는 이 완화가 D-054 R1의 예외라는 기록과 사용자 확인이 없다. → 발견 1 |

## 점검표 밖 발견
1. **(수정 필요, A8·A12·A14) 실전 계좌 한국투자 앱키를 수집기가 쓰지 못하게 하는 장치가 없다.** 아래 중 하나가 필요하다.
   - (가) 코드 검사 + 시험: 계좌번호까지 설정된 한국투자 실전 계좌가 있으면 그 앱키로는 `kis-news`가 돌지 않게 한다. 수집기는 `secrets.json`을 읽지 않는 경계(D-051)가 있으므로, 예를 들어 `autotrade-config`에 "실전 예약 키 지문만 돌려주는" 좁은 함수를 두고 ArchUnit 규칙에서 그것만 허용하거나, 실전 엔진이 기동 때 예약 키 지문 파일을 써 두고 수집기가 그 파일만 대조하는 방식. 막는 것을 확인하는 시험을 함께 넣는다(D-054 R1과 같은 수준). 보호 테스트·보호 경로 변경이 따르므로 이 PR 안에서 risk-ops 재검토.
   - (나) 지금은 한국투자 실전 주문 경로가 없으므로(`autotrade-broker-kis`에 주문 클래스 없음, 실전 엔진에 한국투자 연결 없음) 코드 검사를 7단계 한국투자 실전 계좌 연결 PR로 미루는 안. 이 경우 ① D-054 R1의 수집기 예외를 새 결정(또는 D-136 개정)으로 적고 **사용자 확인**을 받으며(지침 8장 2·4번), ② 한국투자 실전 계좌를 켜기 전 확인 절차를 문서 후보가 아닌 실제 관문 항목으로 넣는다(예: `docs/qa/checklists.md` D 또는 broker-and-fault-design.md 7단계 활성화 조건에 "수집기 `kis-news` 앱키 ≠ 실전 계좌 앱키, 또는 그 연결 PR이 (가)의 코드 검사를 갖춤"). 이 두 문서는 보호 경로라 이 PR이 이미 실전 관련 PR인 범위 안에서 고칠 수 있다.
   - risk-ops 추천: (나)로 이 PR을 마치고, 한국투자 실전 계좌 연결 PR의 승인 조건에 (가)를 박아 둔다. 이유: 지금 실전 계좌에 닿는 경로가 없어 당장의 계좌 위험은 없고, (가)는 D-051 경계와 보호 테스트를 함께 건드려 범위가 크다. 되돌릴 조건: 사용자가 한국투자 앱키를 하나만 갖고 있고 그 계좌를 7단계 후보로 쓸 계획이면 (가)를 먼저 한다.
2. **(수정 필요, A2 보강) 증권사 자격 증명을 다루는 수집기 파일이 보호 경로 밖이다.** 지금까지 수집기는 증권사 키가 없어 주문을 낼 수 없는 구조였고 그래서 보호 경로(`scripts/ci/protected-paths.txt`)에 들어 있지 않다. 이 PR 뒤로는 한국투자 앱키·토큰과 POST를 보낼 수 있는 HTTP 구현이 수집기 안에 있어, 다음 PR이 `KisNewsClient`에 다른 경로(주문·잔고)를 더해도 "실전 관련 PR" 경고 없이 일반 PR로 머지될 수 있다. ArchUnit은 문자열 경로를 보지 않으므로 현재 규칙으로는 못 막는다. 최소한 `autotrade-app-collector/src/main/java/com/autotrade/app/collector/KisNewsClient.java`(와 POST를 내보내는 `HttpGetter.java`·`JdkHttpGetter.java`·`ApiCaller.java`)를 `protected-paths.txt`와 `.github/CODEOWNERS`에 더한다. 더 단단하게는 `autotrade-architecture-test`에 "수집기 패키지 안에서 한국투자 주소 문자열(`koreainvestment`, `/uapi/`, `/oauth2/`)은 `KisNewsClient`에만, 그 값은 두 주소만" 같은 상수 검사를 두는 안도 있다(선택).
3. (참고, 판정 영향 없음) D-054 R2(토큰 재발급 실패 뒤 최소 60초 간격): `KisNewsClient.page`는 만료·무효 응답이면 바로 한 번만 재발급하고, 발급 실패는 재시도 없이 종료한다(시험 `토큰_만료_응답이면_한_번만_…`, `토큰_발급_실패는_재시도하지_않고_…`). 한 실행의 발급은 최대 2번으로 묶여 알림·제한 응답이 쌓이지 않는다. 초당 한도 초과(`EGW00201`)도 재시도 없이 멈춘다. R2의 취지는 충족한다고 본다.
4. (참고) 검토 중 공유 작업 트리의 시험이 간헐 실패한 것은 다른 검토자의 변이 시험 때문이었다(위 "직접 실행한 것"). 같은 브랜치 동시 검토 때는 worktree를 나누는 편이 결과를 믿을 수 있다.

## 판정
수정 필요(A8·A12·A14: 발견 1 — 실전 계좌 한국투자 앱키를 수집기가 쓰지 못하게 하는 코드 검사, 또는 사용자 확인을 받은 D-054 R1 예외 결정과 7단계 관문 항목; 발견 2 — `KisNewsClient` 등 수집기 한국투자 호출 파일을 보호 경로·CODEOWNERS에 추가)
