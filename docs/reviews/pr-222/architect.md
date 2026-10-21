# architect 검토: D-136 뉴스 제목 스냅샷 수집 kis-news(한국투자 종합 시황/공시 제목, Q-055 닫음)
- 대상: PR #222 / 커밋 65878307c414b479359fcd615bf959436b8d0951 / main(62d81a7) 대비 diff (`git diff origin/main...HEAD`, merge-base 4c48b88: 25개 파일, +1,681, −23). main과의 병합은 충돌 없음(`git merge-tree` 확인, main 쪽 새 커밋은 PR #223 문서 2개)
  - 운영 코드: `autotrade-app-collector` 9개(새 `KisNewsClient`·`KisNewsCollector`·`KisNewsSnapshotBuilder`, `ApiCaller.post`, `HttpGetter.post` 기본 메서드, `JdkHttpGetter`, `CollectorMain` `kis-news` 명령, `CollectorReports.writeNews`, `CollectorSecrets` `kis-news` 칸, `Redaction.add`)
  - 시험: 새 `KisNewsClientTest`(7)·`KisNewsCollectorTest`(9)·`KisNewsCommandTest`(6)·`KisNewsFixtures`, `CollectorSecretsTest`·`FakeHttp` 보강
  - 보호 테스트: `autotrade-architecture-test/.../ArchitectureTest.java` 수집기 규칙의 javadoc 한 줄과 `because` 문구만
  - 문서: ADR-008, ADR-012, config-keys, D-136(새), 결정 로그 README, qa/pending, research/q-055(새), runbook collector
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**, 138 tasks, 종료 0. 결과 XML 합계 1,910건, 실패 0, 오류 0, 건너뜀 0. 같은 헤드에서 앞서 돈 결과가 있어 test 작업 다수가 UP-TO-DATE였으므로 아래를 따로 다시 돌림.
  - `./gradlew :autotrade-app-collector:test :autotrade-architecture-test:test --rerun` → 종료 0, 176건 실패·건너뜀 0(새로 실행). `KisNewsClientTest` 7, `KisNewsCollectorTest` 9, `KisNewsCommandTest` 6, `CollectorSecretsTest` 6 모두 통과. `verifyProcessIsolation`(collector)·`verifyPureDependencies`는 전체 빌드에서 실행·통과.
  - 변이 시험(`:autotrade-app-collector:test --tests 'com.autotrade.app.collector.Kis*'`, 끝나고 `git checkout`으로 원복, `git status` 깨끗함 확인):
    - M1 `KisNewsClient.token()`의 `redaction.add(token)` 삭제 → **죽음**(1건 실패, `받은_토큰도_오류_메시지에서_가린다`)
    - M2 `KisNewsSnapshotBuilder.build`의 종목코드 없는 행 거르기 무력화 → **죽음**(1건 실패)
    - M3 `KisNewsCollector.walk`의 멈출 시각 도달 판정 무력화 → **죽음**(5건 실패)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공, 수집기·아키텍처 시험은 `--rerun`으로 새로 실행해 통과 |
| B2 | 통과 | 빌드 스크립트·의존성 변경 없음(diff에 `build.gradle*` 없음). 수집기는 `autotrade-broker-*`에 의존하지 않고 자체 `KisNewsClient`(java.net.http + Jackson)만 씀. `COLLECTOR_READS_ONLY_ITS_SECRETS`의 조건식은 그대로이고 javadoc·`because` 문구만 바뀜(ArchitectureTest.java:234, 245~246). `verifyProcessIsolation`·ArchUnit 통과. 규칙 내용 변경이 아니므로 새 ADR은 필요 없고, 경계 설명은 ADR-008 2.1(34행)에 반영됨 |
| B3 | 통과 | 설정 키 `kis-news.app-key`·`app-secret`(collector-secrets.json) 추가: config-keys.md 80~81행, ADR-008 2.2, runbook 1장·3.9, `CollectorSecrets.ALLOWED`(CollectorSecrets.java:31~34)와 일치. 새 스냅샷 형식(`news_headlines.csv`·`news_coverage.csv`, 질의 규칙 `kis-news-title-iscd@1`)은 D-136 5번과 `KisNewsSnapshotBuilder` 상수(27~33행)가 같음. 4개 프로세스 계약·DB 스키마·REST 무변경. 단 점검표 밖 1번(조사 문서의 키 위치 안내가 이 계약과 어긋남) |
| B4 | 통과 | 시뮬레이션 코드 무변경. 수집기는 주입한 `Clock`만 씀(`KisNewsCollector.collect` 95행 `LocalDateTime.now(clock.withZone(SEOUL))`). 스냅샷 ID는 두 파일 해시(`SnapshotWriter.commit` 해시 파일 목록, CollectorMain `kisNews`)로, `같은_캐시와_구간이면_같은_ID다` 시험이 같은 입력 → 같은 ID를 고정. 골든 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음(건수·시각·문자열만). 품질 리포트 비율 표시만 `double` 포맷(`CollectorReports.pct`, 기존 함수) |
| B6 | 통과 | 새 동작마다 시험: 두 주소만 부름·키를 URL에 안 실음·계좌 헤더 없음, 토큰 만료 1회 재발급, 거절 메시지에 응답 문구·토큰 없음, 토큰 발급 실패 무재시도, 커서·겹침·빈틈·백필 바닥·같은 초 가득 찬 쪽·구간 합치기·서울 시각, 스냅샷 내용·리포트에 제목 없음·같은 ID·장 시간 거절·키 없음·옵션 검사·기본 간격. 변이 M1~M3 모두 죽음. 시험 삭제·`@Disabled`·건너뜀 없음 |
| B7 | 해당 없음(단, 보호 경로) | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트·리스크 한도·게이트웨이·`autotrade-broker-*` 변경 없음. 다만 보호 테스트 `ArchitectureTest` 파일을 고쳤고(문구만), 증권사(한국투자) 앱키를 실전 엔진 밖 프로세스(수집기)에 두고 한국투자 API를 부르므로 지침 11장상 실전 관련 PR이다. D-054 조회 호출기 조건("실전 계좌가 설정된 한투 키로는 조회 호출기를 만들지 않음 + 시험")은 코드로 강제되지 않고 runbook 권고로만 남는다(수집기가 `secrets.json`을 못 읽는 경계 때문, D-136 위험 1). 이 판단은 risk-ops 몫이다. **risk-ops 검토 필요** |
| B8 | 통과 | diff에 실제 키·계좌번호·토큰 없음(직접 확인: 문서는 `<KIS_APP_KEY>` 자리표시자, 시험은 `KISKEYAAAAAAAAAA` 같은 가짜 값). 앱키·시크릿·토큰은 헤더·POST 본문으로만 나가고(KisNewsClient.java:158~176), 오류는 `Redaction`을 거친 URL·결과 코드만 남김(ApiCaller.post, KisNewsClient 179~180·205~209행), 받은 토큰은 실행 중 `Redaction`에 더함(188행, M1으로 시험이 잡음). 파서 메시지·`msg1` 원문을 옮기지 않음. 품질 리포트에 제목·일련번호 없음(`KisNewsCommandTest` 첫 시험). gitleaks는 로컬에서 돌리지 않았고 CI 결과에 맡김 |

## 점검표 밖 발견
1. **(수정 필요)** `docs/research/q-055-news-source.md` 80행 "6. 사용자 할 일": "키는 `secrets.json`의 `brokers.kis.app-key`·`app-secret` 자리에만 넣는다". D-136 3번·ADR-008 2.2·runbook 3.9·config-keys는 `collector-secrets.json`의 `kis-news` 칸이고, 수집기는 `secrets.json`을 읽지 않는다. 사용자가 이 안내를 따르면 수집기는 키가 없어 실패하고, 더 나쁘게는 뉴스용 앱키가 4개 프로세스의 한국투자 증권사 설정(`brokers.kis`)에 들어가 게이트웨이·실전 엔진 설정을 바꾸게 된다. 사용자에게 직접 하라고 적은 절이므로 결정 뒤 내용(`collector-secrets.json`의 `kis-news`, 실전 앱키와 다른 키 권장)으로 고치거나, 결정 전 기록임을 밝히고 runbook 3.9로 안내해야 한다.
2. (사소, 고치지 않아도 됨) `CollectorSecrets.load` javadoc(CollectorSecrets.java:36) "파일이 없으면 둘 다 빈 값"은 이제 네 값이다.
3. (참고, 판정 영향 없음) `KisNewsClient`는 `ApiCaller.get`을 쓰므로 HTTP 200이 아닌 응답의 본문 결과 코드를 보지 않는다. kis-open-api-spec.md 87행은 `EGW00201`이 HTTP 500으로 올 수 있다고 적고(`[가정]`) 증권사 호출기는 상태와 상관없이 본문 코드를 먼저 본다. 이 경우 수집기는 1초·2초 뒤 두 번 다시 부른 뒤 "HTTP 500"으로 종료 코드 1을 낸다(받은 캐시·구간은 `finally`에서 저장, KisNewsCollector.java:141~144). 안전 쪽 실패이고 호출이 초당 한도에 영향 줄 만큼 늘지 않지만, 안내 문구("--interval-ms를 늘려")가 나오지 않는다. 첫 실행 결과를 보고 필요하면 다룰 일이다.
4. (참고) 이미 캐시에 있는 행만 담긴 쪽도 `added == 0`으로 세어 "새 제목 없는 쪽" 연속 5번이면 걸음을 '끝까지 아니오'로 남긴다(KisNewsCollector.java:209~212). 겹침 10분 구간에 제목이 200건을 넘으면(장 마감 직후 재실행 등) 커서가 걸리지 않았는데도 그렇게 보고된다. 구간은 앞 구간과 합쳐지므로 자료 손실은 없다 `[추론]`(코드 읽기, 실행 확인 안 함). 수집기는 장 시간 보호 구간 밖에서만 돌아 겹침 구간 건수가 적을 것으로 본다.
5. 문서 주장 확인: "수집기는 증권사 모듈에 의존하지 않는다"(D-136 2번)는 diff에 빌드 스크립트 변경이 없고 `verifyProcessIsolation`·ArchUnit 통과로 확인. "부르는 주소는 두 개뿐, 경로·TR을 인자로 받지 않는다"는 `KisNewsClient` 34~36행 상수와 공개 메서드(`page(date,time)`만)로 확인, 시험 `뉴스_제목_조회와_토큰_발급_두_주소만_부르고_…`가 고정. "계좌번호 없음"은 `CollectorSecrets.ALLOWED`에 계좌 칸이 없어 넣으면 파일 전체 거부로 확인.

## 판정
수정 필요(점검표 밖 1: `docs/research/q-055-news-source.md` 6장 키 위치 안내를 `collector-secrets.json`의 `kis-news`로 바로잡기)

## 재검토 2026-10-21
- 대상: PR #222 / 커밋 a1af54045be5075fe30b096256a424c50be0d60b / main(62d81a7) 대비 diff (`git diff origin/main...HEAD`: 30개 파일, +1,774, −24). 앞 검토(65878307) 뒤 새 커밋은 4ea3b026(architect 발견 1·2 반영), a1af5404(risk-ops 발견 1·2 반영), 검토 원문 2건. main과 병합 충돌 없음(`git merge-tree --write-tree` 성공, main 쪽 새 커밋 #223은 문서 2개)
  - 앞 검토 뒤 바뀐 것: `docs/research/q-055-news-source.md` 6장 키 위치, `CollectorSecrets.load` javadoc, D-136 "위험 1" 문구와 새 절 "D-054 R1 수집기 예외"(사용자 확인 대기), `docs/qa/checklists.md` D8, `scripts/ci/protected-paths.txt`·`.github/CODEOWNERS`에 수집기 파일 4개(`KisNewsClient`·`HttpGetter`·`JdkHttpGetter`·`ApiCaller`). 운영 코드 동작 변경 없음(javadoc 한 줄뿐)
- 점검표: B
- 직접 실행한 것(공유 작업 트리와 겹치지 않게 `git worktree add --detach`로 a1af5404를 따로 꺼내 실행):
  - `./gradlew build --no-daemon` → 종료 0. 새 worktree라 빌드 폴더가 비어 모든 test 작업이 새로 실행됨(로그에 UP-TO-DATE·FROM-CACHE 0건). 결과 XML 합계 1,910건, 실패 0, 오류 0, 건너뜀 0
  - 변이 M1 재실행: `KisNewsClient.java:188` `redaction.add(token)` 주석 처리 → `:autotrade-app-collector:test --tests 'com.autotrade.app.collector.Kis*'` 22건 중 1건 실패(**죽음**). `git checkout`으로 원복, `git status` 깨끗함 확인
  - `bash scripts/ci/test-mark-protected-changes.sh` → "표시 스크립트 자체 시험 통과", 종료 0
  - `bash scripts/ci/mark-protected-changes.sh main` → 보호 대상 8개 표시: `.github/CODEOWNERS`, 수집기 4개 파일, `ArchitectureTest.java`, `docs/qa/checklists.md`, `scripts/ci/protected-paths.txt`. 변이 M4: `protected-paths.txt`에서 `KisNewsClient.java` 줄을 지우면 표시 목록에서 그 파일이 빠짐(0건) → 새 줄이 실제로 경고를 만든다. 원복 확인
  - CI(헤드 a1af5404, `gh api .../check-runs`): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 모두 success

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `./gradlew build` 직접 실행 성공, 1,910건 새로 실행·전부 통과 |
| B2 | 통과 | 앞 검토 뒤 빌드 스크립트·의존성·ArchUnit 규칙 변경 없음. `ArchitectureTest` 변경은 여전히 javadoc·`because` 문구뿐(조건식 무변경). `verifyProcessIsolation`·`verifyPureDependencies`·ArchUnit 전체 빌드에서 통과 |
| B3 | 통과 | 앞 검토 발견 1 해소: q-055 6장(80행)이 `collector-secrets.json`의 `kis-news` 칸을 안내하고 `secrets.json` `brokers.kis`에 넣지 말라고 적음 → D-136 3번·ADR-008 2.2·config-keys 80~81행·runbook 3.9·`CollectorSecrets.ALLOWED`(31~34행)와 일치. 새 계약 변경 없음(D8은 관문 점검 항목, 보호 경로 목록은 CI 입력이며 코드 계약 아님). 단 점검표 밖 1(결정 로그 색인) |
| B4 | 통과 | 시뮬레이션 코드 무변경. 수집기 주입 `Clock` 사용과 스냅샷 ID 결정성 시험(`같은_캐시와_구간이면_같은_ID다`)은 앞 검토와 같고 이번 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 시험 삭제·`@Disabled`·건너뜀 없음(건너뜀 0). 앞 검토 뒤 운영 동작 변경이 없어 새 시험 필요 없음. 보호 경로 추가는 표시 스크립트 자체 시험 통과와 M4로 확인. M1 다시 죽음 |
| B7 | 해당 없음(단, 보호 경로) | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트·리스크 한도 코드 변경 없음. 그러나 보호 테스트 파일·보호 경로 목록·CODEOWNERS·점검표 D를 바꾸고, D-054 R1을 수집기에 한해 미루는 결정(사용자 확인 대기)을 담으므로 실전 관련 PR이다. risk-ops 원문 판정이 "수정 필요"로 남아 있어 **risk-ops 재검토 필요**, 머지는 D-136 "D-054 R1 수집기 예외" 사용자 확인과 독립 QA 뒤 사용자가 한다 |
| B8 | 통과 | 앞 검토 뒤 diff에 키·계좌번호·토큰 없음(직접 확인: 추가분은 문서 문구·경로 목록·javadoc). 토큰 가림은 M1으로 다시 확인. gitleaks는 로컬 미실행, CI "Secret scan and collector boundary" success로 확인 |

## 점검표 밖 발견
1. (권고, 판정 영향 없음) `docs/decisions/README.md` 142행 D-136 색인 줄은 여전히 "나머지는 **추천안 채택(자율)**"이라고만 적고, D-136 본문 새 절의 "D-054 R1 수집기 예외 — **사용자 확인 대기**"가 보이지 않는다. 지침 8장은 사용자가 결정 로그에서 한눈에 검토하게 하라고 하므로, 색인 줄에 "D-054 R1 수집기 예외는 사용자 확인 대기"를 한 마디 더하는 편이 낫다. 사용자 확인 결과가 나오면 그때 같이 고쳐도 된다.
2. (권고, 판정 영향 없음) `docs/qa/pending.md` 73행의 PR #222 요청 설명은 처음 커밋 기준이다. 이제 보호 경로·CODEOWNERS·점검표 D8 변경과 D-054 R1 예외(사용자 확인 대기)도 QA 범위이므로 설명에 더하면 QA가 범위를 좁게 잡지 않는다(QA는 diff 전체를 보므로 필수는 아님).
3. 앞 검토 점검표 밖 2(`CollectorSecrets.load` javadoc)는 "모든 값이 빈 값"으로 고쳐짐(CollectorSecrets.java:36). 3·4(비200 응답 본문 코드 미확인, 겹침 구간 200건 초과 시 '끝까지 아니오' 보고)는 참고 사항 그대로이며 코드 변경 없음.
4. 보호 경로 추가 범위 확인: 수집기에서 한국투자 주소 상수는 `KisNewsClient`에만 있고(34~36행), 생성은 `CollectorMain.kisNews`(363행)가 `o.kisBase`와 `collector-secrets.json` 값으로만 한다. POST는 `HttpGetter.post`(13행 설명: 토큰 발급 전용)→`JdkHttpGetter`→`ApiCaller.post` 경로뿐이라, 새로 보호한 4개 파일이 한국투자 호출과 POST 경로를 덮는다. `CollectorMain`은 보호 밖이지만 주소·TR을 바꿀 수 없다(공개 메서드 `page(date,time)`만). ArchUnit 상수 검사(risk-ops 발견 2의 선택안)는 넣지 않았고, 경로 표시로 충분한지는 risk-ops 판단 몫이다.

## 판정
승인
