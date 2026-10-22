# QA 검토: D-136 뉴스 제목 스냅샷 수집 kis-news (한국투자 종합 시황/공시 제목, Q-055 닫음)
- 대상: PR #222 / 커밋 `a3d06faf` (브랜치 `claude/project-thread-jhpj43`) / main 대비 diff 전체(merge-base `4c48b884`, 30파일 +1,842 −24: 수집기 운영 코드 10파일, 시험 6파일, `ArchitectureTest`, `CODEOWNERS`, `protected-paths.txt`, 문서 11파일)
- 점검표: A 전체 + B1, B6, 프로젝트 지침 8장 과금 검사
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --console=plain` (별도 worktree, 헤드 `a3d06faf`): Maven Central 429로 의존성 받기 실패 10회 뒤 11회째 **BUILD SUCCESSFUL, 138 작업 모두 실행**. 시험 결과 XML 합계 1,910건, 실패·오류·건너뜀 0(`autotrade-app-collector` 130, `autotrade-architecture-test` 46, `autotrade-risk` 55, `autotrade-app-engine` 68). 429 실패는 저장소 밖 네트워크 원인이라 빌드 실패로 보지 않는다.
  - PR 헤드 CI(GitHub check runs): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens 모두 success.
  - `scripts/ci/test-mark-protected-changes.sh`: "표시 스크립트 자체 시험 통과". `scripts/ci/mark-protected-changes.sh main`: 보호 대상 8개 표시(`CODEOWNERS`, 수집기 `ApiCaller`·`HttpGetter`·`JdkHttpGetter`·`KisNewsClient`, `ArchitectureTest`, `checklists.md`, `protected-paths.txt`).
  - `git diff <merge-base> HEAD --stat -- autotrade-broker-* autotrade-risk autotrade-app-engine autotrade-app-gateway autotrade-domain autotrade-config autotrade-marketdata autotrade-strategy`: 빈 결과(실전 엔진·게이트웨이·증권사·리스크 모듈 무변경).
  - diff 추가 줄에서 키·토큰·계좌번호 모양 grep: 결과 없음(자리표시자 `<KIS_APP_KEY>`와 시험용 가짜 값뿐).
  - `git merge-tree --write-tree origin/main pr-222`(main `0a66d55`): `docs/decisions/README.md` 충돌(main의 D-137 행과 같은 자리).
  - QA 임시 시나리오 시험 6개(아래 "시나리오", 커밋하지 않음)와 변이 15개(아래 "변이", 끝나고 모두 되돌림, `git status` 깨끗 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-app-engine`·`autotrade-config` 무변경(위 `--stat` 빈 결과). RealTradingGuard 시험 포함 `autotrade-risk` 55·`autotrade-app-engine` 68건 통과(직접 빌드) |
| A2 | 통과 | 주문 코드 추가 없음. 수집기의 한국투자 호출은 `KisNewsClient` 상수 `TOKEN_PATH`·`NEWS_PATH`·`TR_ID`(KisNewsClient.java 34~36행) 두 주소뿐이고 공개 호출 메서드는 `page(date,time)` 하나, 계좌번호 입력·헤더 없음(시험 `뉴스_제목_조회와_토큰_발급_두_주소만_부르고_키는_주소에_싣지_않는다` 통과). `ArchitectureTest.COLLECTOR_READS_ONLY_ITS_SECRETS`는 javadoc 한 줄과 `because` 문구만 바뀌고 금지 조건(`com.autotrade.broker..`, `com.autotrade.domain.order..`, 설정 로더 6개)은 그대로(diff 직접 확인), 46건 통과. 리다이렉트 금지(`JdkHttpGetter` `Redirect.NEVER`) 유지로 키 헤더가 다른 호스트로 가지 않음 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음 |
| A4 | 해당 없음 | 주문 재전송 로직 변경 없음 |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음. 뉴스 제목은 어떤 선정 지표에도 아직 연결되지 않음(`autotrade-selection` 무변경, D-136 6번) |
| A7 | 통과 | 계좌별 직렬 큐·토큰 버킷 코드 무변경. 수집기 자체 간격 기본 350ms, `--interval-ms` 100 미만 거부(CollectorMain.java `parse`, 변이 M7을 `KisNewsCommandTest.옵션_검사`가 잡음) |
| A8 | 통과 | 수집기는 별도 단발 프로세스, 매 GET·POST 전 `RunGuard.checkSession`(장 시간 보호 구간·감시 정지 요청, ApiCaller.java `get`·`post`), 힙·디스크 보호 기존 그대로. 실전 앱키 공유 위험: 지금 한국투자 실전 주문 경로가 없다는 주장을 직접 확인(`autotrade-broker-kis` 운영 클래스에 주문 클래스 없음, `KisRestClient`·`KisTokenClient`를 쓰는 운영 코드는 그 모듈 안뿐 — `grep -rl` 결과, 게이트웨이는 `KisApprovalClient`(실시간 접속키 `/oauth2/Approval`)만 씀). 7단계 쪽 장치가 저장소에 있음: 점검표 D8(`docs/qa/checklists.md` 60행), D-136 "D-054 R1 수집기 예외" 절 1번(한국투자 실전 연결 PR 승인 조건). 이 예외가 사용자 결정이라는 기록(D-136, 결정 색인)은 있으나 결정 카드 원본은 이 QA 프로젝트에서 열 수 없어 직접 보지 못했다(아래 R4) |
| A9 | 통과 | 게이트웨이·`autotrade-broker-*`·서버 측 손절 코드 무변경. 수집기는 장 시간 보호 구간에 호출하지 않아 장중 보호 시세 폴링과 한도를 다투지 않음(`KisNewsCommandTest.장_시간_보호_구간에는_시작하지_않는다` 통과) |
| A10 | 통과 | 키는 `collector-secrets.json`(`.gitignore` 5행 `**/collector-secrets.json`)에만, 앱키·시크릿·토큰은 헤더·POST 본문으로만. 예외 메시지는 `Redaction`을 거친 URL과 결과 코드만(시나리오 S1·S2 메시지에 키·토큰 없음 확인), 받은 토큰은 `Redaction.add`(변이 M3을 `받은_토큰도_오류_메시지에서_가린다`가 잡음). `CollectorSecrets`는 모르는 칸(예: 계좌번호) 있으면 파일 전체 거부. 품질 리포트는 집계·코드만, 제목·일련번호 없음(`KisNewsCommandTest` 통과). diff grep·CI gitleaks 이상 없음. 다만 가림 장치 두 곳은 시험이 없다(변이 M10·M14 생존, 아래 Q3) |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled`·약화 없음. `ArchitectureTest`는 설명 문구만 변경(규칙 조건 동일). 고정 값(지문·허용 목록·소유 표) 변경 없음. 보호 경로는 늘기만 함(수집기 4파일, `protected-paths.txt`·`CODEOWNERS` 양쪽 같은 4줄), 표시 스크립트가 잡음(위 실행). 새 동작에 실패 경우 시험 있음(토큰 만료 1회 재발급, 거절 코드, 토큰 발급 무재시도, 키 없음·https 아님, 호출 상한 종료 코드 3, 장 시간 거절). 변이 15개 중 9개 잡힘(아래) |
| A12 | 통과 | 장애표 #17(레이트리밋 공유)의 전제(실전 키는 실전 엔진 전용)는 지금 한국투자 실전 경로가 없어 깨지지 않고, 연결 시점은 D8·연결 PR 승인 조건으로 막음. 장애 테스트 8종 대상 코드 무변경 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. 수집기 시각은 서울(`CallBudget.SEOUL`, 시험 `실행_시각은_서울_기준이다`), 미국 장 보호는 기존 `SessionProtection` |
| A14 | 통과 | D-136(결정·대안 4개·되돌릴 조건·위험 6개), 결정 색인 D-136 행, Q-055 닫힘, ADR-008 2.1·2.2, ADR-012 4.2·배치표 6번, config-keys, runbook 3.9가 코드와 일치(주소·TR, 키 칸 두 개, 기본 400회·최대 2,000회 `CollectorMain.KIS_NEWS_DEFAULT/MAX`, 간격 350ms, 겹침 10분 `OVERLAP_MINUTES`, 빈 날 7일 `MAX_EMPTY_DAYS`, 스냅샷 두 파일·질의 규칙 `kis-news-title-iscd@1`). 한 곳 불일치: risk-ops 원문 발견 3·D-054 4번은 "EGW00201은 재시도 없이 멈춘다"인데, EGW00201이 HTTP 500으로 오면(kis-open-api-spec.md 87행 2차 자료) 수집기는 1초·2초 뒤 두 번 더 부른다(시나리오 S1). 판정 영향은 아래 Q1 |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(1,910건, 실패 0), CI Gradle build success |
| B6 | 통과 | 새 동작 시험 22건(`KisNewsClientTest` 7, `KisNewsCollectorTest` 9, `KisNewsCommandTest` 6)과 `CollectorSecretsTest` 추가, 건너뜀·삭제 없음. 생존 변이는 아래 Q1~Q4 권고 |
| 과금(지침 8장 1번) | 통과 | 유료 호출 없음. 한국투자 Open API 토큰 발급·국내 시세 조회 무료는 사용자 확인(2026-10-09), 이 뉴스 TR이 무료인 것은 `[추론]`(요금 항목 근거 없음). 그래도 `kis-news`는 사용자가 `collector-secrets.json`에 키를 넣고 명령을 손으로(또는 직접 만든 예약 작업으로) 돌릴 때만 동작하고 저장소 안에 자동 실행 경로가 없다(`git grep kis-news`가 문서·Java 밖에서 0건) = 기본 꺼짐. 결제 수단 등록이 필요한 네이버 API HUB는 쓰지 않음 |

## 시나리오 (점검표 밖, 잘못된 주문·중복 주문·보호 공백을 만들 수 있는지)
수집기는 주문을 내지 않으므로 아래는 "실전 한도 공유"와 "선정 입력이 될 자료의 조용한 오류"를 봤다. 모두 합성 서버(`FakeHttp`·`KisNewsFixtures`)로 직접 실행한 결과다.

- **S1 초당 한도 초과가 HTTP 500으로 올 때**: `{"rt_cd":"1","msg_cd":"EGW00201"}` + HTTP 500 → GET **3번**(1초·2초 대기 재시도), 예외는 `IOException("HTTP 500: …")`로 `KisRejected`의 "EGW00201 … --interval-ms를 늘려" 안내가 사라짐. HTTP 200으로 오면 1번에 멈춤(기존 시험). 실전 앱키를 같이 쓰는 경우에도 추가 호출은 2건이고 장 시간 밖이라 지금 실전 영향은 없다 `[추론]`. 다만 D-054 4번 "자동 재시도하지 않음"·R2 "지수 백오프"와 다르고, 공식 `KisRestClient`는 HTTP 상태와 상관없이 본문 결과 코드를 먼저 본다(KisRestClient.java 116~136행).
- **S1b 토큰 만료가 HTTP 500·401로 올 때**: 500 → 같은 만료 토큰으로 3번 부르고 실패, 401 → 1번에 실패. 재발급하지 않아 실행이 끝난다(다음 실행이 새 토큰을 받음). 알림·발급 제한이 쌓이지는 않음.
- **S2 걸음 중간 연결 실패**: 두 쪽 받은 뒤 세 번째 쪽 연결 실패(재시도 포함) → 예외로 끝나되 캐시 7건은 저장되고 훑은 구간은 `03:30:01~07:00`(가장 옛 행 다음 초부터)로만 남음. 구간을 넘겨 적지 않음(정상).
- **S3 시각이 5자리로 올 때**(`data_tm` "93010", 앞 0이 빠진 09:30:10이라는 가정): `headline()`이 오른쪽에 0을 채워 "930100"(93시)을 만들고 `KisNewsCollector.at`에서 `DateTimeParseException` → 첫 쪽에서 실행 실패, 훑은 구간 0. 같은 행이 목록 맨 위에 있는 동안 **매 실행이 같은 곳에서 실패**한다. 기존 시험 `행_해석_시각_여섯_자리_…`가 "9301"→"930100"(93시, 존재하지 않는 시각)을 기대값으로 고정하고 있다. 한국투자가 늘 6자리를 주면 일어나지 않는다 `[가정]`.
- **S4 한 초에 쪽 크기보다 많은 제목**(같은 초 100건, 쪽 40건): 41건만 캐시, 59건 잃음, `crowdedSeconds`=1로 리포트에 표시, 구간은 그 초를 덮은 것으로 기록. D-136 위험 4대로 알려지는 손실.
- **S5 00:00:00 제목이 쪽 크기보다 많을 때**(00:00:00 10건, 쪽 4건, 첫 실행): 그날 11건 중 4건만 캐시, **`crowdedSeconds`=0**, 구간은 `00:00:00~07:00`으로 그 초를 덮은 것으로 기록 → **알림 없이** 7건을 잃음. 원인: 쪽의 가장 옛 행이 멈출 시각(00:00:00)과 같으면 `complete`로 끝나고(KisNewsCollector.java `!pageOldest.isAfter(stopAt)`), 매일 자정을 건너는 걸음도 커서 00:00:00을 전날 23:59:59로 바꿔(같은 파일 `LocalTime.MIDNIGHT` 처리) 남은 00:00:00 행을 건너뛰지만 셈하지 않는다. 00:00:00 행이 실제로 많은지는 모른다 `[가정]`.

## 변이 (수집기 시험 `:autotrade-app-collector:test`, 하나씩 넣고 되돌림)
| # | 변이 | 결과 |
|---|---|---|
| M1 | `ApiCaller.post`에서 장 시간 확인(`beforeCall.run()`) 제거 | 생존 |
| M2 | `ApiCaller.post`에서 호출 상한(`budget.acquire()`) 제거 | 잡힘 |
| M3 | 받은 토큰 `redaction.add` 제거 | 잡힘 |
| M4 | 예외·상한으로 멈춘 걸음의 구간을 멈출 시각까지로 넘겨 기록 | 잡힘 |
| M5 | 실행 시각(`top`) 뒤 행 거름 제거 | 생존 |
| M6 | 겹침 `OVERLAP_MINUTES` 10 → 0 | 생존 |
| M7 | `--interval-ms` 100 하한 제거 | 잡힘 |
| M8 | 종목코드 없는 제목도 스냅샷에 넣음 | 잡힘 |
| M9 | 스냅샷 구간 끝 배타(`at.isBefore(i.to())`) | 생존 |
| M10 | `CollectorMain` 가림 목록에서 `kisNewsAppSecret` 제거 | 생존 |
| M11 | 빈 날 한도 7 → 2 | 잡힘 |
| M12 | `rt_cd` ≠ 0 거절을 결과 코드 모양에 따라 건너뜀 | 잡힘 |
| M13 | 토큰 재사용 안 함(쪽마다 발급) | 잡힘 |
| M14 | `access_token` 없을 때 응답 본문을 예외 메시지에 넣음 | 생존 |
| M15 | 백필 바닥(`floor`) 미기록 | 잡힘 |

## 점검표 밖 발견
- **Q1 (권고) 한국투자 결과 코드를 HTTP 상태보다 먼저 보지 않는다.** `KisNewsClient.page`는 `ApiCaller.get`을 거쳐 5xx·429를 일반 일시 오류로 재시도하고 상태 200일 때만 본문 `msg_cd`를 본다. EGW00201(초당 한도)이 HTTP 500이면 재시도 2회(S1), 토큰 만료가 비 200이면 재발급 없이 실패(S1b). D-054 4번·R2와 risk-ops 원문 발견 3("EGW00201도 재시도 없이 멈춘다")의 전제와 다르다. 지금은 실전 한국투자 경로가 없고 추가 호출이 2건이라 판정을 막지 않지만, D8(같은 앱키 금지)이 지켜지지 않은 채 7단계로 가면 의미가 생긴다. 권고: 한국투자 응답은 상태와 상관없이 본문을 읽어 `EGW00201`·`EGW00215`면 재시도 없이 `KisRejected`, 토큰 코드면 1회 재발급(공식 `KisRestClient`와 같은 순서), HTTP 500+EGW00201 시험 추가. 이 파일들은 보호 경로라 실전 관련 PR로 다룬다.
- **Q2 (권고) 짧은 시각 처리와 그 시험 기대값.** `KisNewsClient.headline`의 오른쪽 0 채우기는 4자리 `HHMM`에는 맞지만 5자리(앞 0 빠짐)면 존재하지 않는 시각을 만들어 실행이 매번 같은 곳에서 멈춘다(S3). 시험 `행_해석_시각_여섯_자리_종목코드_중복과_빈_값_필수_칸`은 "9301"→"930100"(93시)을 기대값으로 고정한다. 권고: 6자리가 아니거나 `LocalTime`으로 읽을 수 없으면 그 행을 null(세지 않음)로 거르고 리포트에 건수를 남기며, 기대값을 유효한 시각으로 고친다.
- **Q3 (권고) 시험 없는 동작**: M1(토큰 발급 전 장 시간 확인, 시작 확인이 있어 영향 작음), M5(실행 시각 뒤 행 거름), M6(늦게 실린 기사 겹침 10분 — 기존 시험의 06:55 기사는 겹침 없이도 첫 쪽에 들어와 잡히지 않음), M9(스냅샷 구간 끝 포함), M10·M14(가림 방어선 두 곳, 지금 값이 메시지에 실리는 경로는 없음). M6은 D-136 위험 3의 근거라 시험이 있어야 한다.
- **Q4 (권고) 00:00:00 행의 조용한 손실**(S5): 쪽 끝이 멈출 시각과 같은 초이거나 자정 커서를 건너뛸 때 남은 같은 초 행을 셈하지 않는다. `crowdedSeconds`처럼 세어 리포트에 남기거나, 멈출 시각과 같은 초면 한 쪽 더 받아 확인하기를 권한다. 첫 실행 품질 리포트에서 `data_tm` 000000 행 비율을 확인할 항목을 runbook 3.9에 더하면 크기를 알 수 있다.
- **R1 main과 충돌**: `docs/decisions/README.md`(main `0a66d55`의 D-137 행). 머지 전 main 병합 필요. 운영 코드 충돌은 없다.
- **R2 CI 키 검사 범위**: `scripts/ci/check-collector-keys.sh`는 KRX·OpenDART 키 모양만 본다. 이제 `collector-secrets.json`에 한국투자 앱키·시크릿이 들어가므로 `"app-key"`·`"app-secret"` 값 모양(예: 16자 이상) 패턴을 더하는 것을 권한다(gitleaks가 일반 검사는 함). 이 스크립트는 보호 경로가 아니다.
- **R3 보호 경로의 남은 틈**: risk-ops 재검토 R1과 같은 의견. `CollectorSecrets.java`(계좌 칸 거부가 사실상 안전 장치)와 `CollectorMain.java`(키를 호출기에 넘기는 곳)가 보호 경로 밖이다. 다음 수집기 PR에서 더하기를 권한다.
- **R4 사용자 확인 요청**: D-136의 "D-054 R1 수집기 예외는 사용자 결정(2026-10-21, 결정 카드 '7단계 연결 때 넣기')" 기록은 개발 프로젝트 스레드의 결정 카드라 이 QA 프로젝트에서 원본을 열 수 없었다. A8·A12·A14는 이 결정이 없어도 코드 사실(지금 한국투자 실전 경로 없음, D8 관문 존재)로 통과로 봤지만, 결정 자체가 사용자 본인 것인지는 머지 전에 사용자가 확인해야 한다.
- 참고: 한국투자 이용약관의 저장 조항은 PR도 확인하지 못했다(D-136 위험 5, runbook 3.9 2번). 첫 실행 전 사용자 확인 항목으로 그대로 남는다.

## 판정
승인

실패·확인 못 함 항목 없음. Q1~Q4, R1~R4는 판정을 막지 않는 권고(R1은 머지 전 main 병합 필요, R4는 사용자 확인).
