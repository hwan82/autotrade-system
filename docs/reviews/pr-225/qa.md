# QA 검토: D-136 후속: kis-news 결과 코드 먼저·시각 채우지 않음·같은 초/자정 손실 셈 (PR #222 QA)
- 대상: PR #225 / 커밋 `c4811215` (브랜치 `claude/project-thread-jhpj43`) / main 대비 diff 전체(merge-base `0f1e5620`, 21파일 +494 −40: 수집기 운영 코드 5파일(`ApiCaller`, `CollectorMain`, `CollectorReports`, `KisNewsClient`, `KisNewsCollector`), 시험 5파일, `CODEOWNERS`, `protected-paths.txt`, `check-collector-keys.sh`, 문서 8파일)
- 점검표: A 전체 + B1, B6, 프로젝트 지침 8장 과금 검사
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --console=plain` (헤드 `c4811215`): 1회째 **BUILD SUCCESSFUL in 5m 26s, 138 작업 모두 실행**. 시험 결과 XML 합계 1,928건, 실패·오류·건너뜀 0(`autotrade-architecture-test` 46, `autotrade-risk` 55, `autotrade-app-engine` 68). 변이 뒤 되돌린 코드로 `:autotrade-app-collector:test --rerun-tasks` 다시 실행: 144건 실패 0.
  - PR 헤드 CI(GitHub check runs, 2026-10-22T07:02Z): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens (Playwright) 모두 success.
  - `git merge-tree --write-tree origin/main HEAD`(main `9c1ffad7`, PR 뒤에 #226·#227 머지됨): 충돌 없음. #226·#227이 바꾼 파일은 `autotrade-app-collector/build.gradle.kts`(콘솔 UTF-8)와 문서·실험 파일뿐이라 의미 충돌 없음 `[추론]`.
  - `scripts/ci/test-mark-protected-changes.sh`: "표시 스크립트 자체 시험 통과". `scripts/ci/mark-protected-changes.sh main`: 보호 대상 5개 표시(`CODEOWNERS`, 수집기 `ApiCaller`·`CollectorMain`·`KisNewsClient`, `protected-paths.txt`).
  - `git diff --stat origin/main...HEAD -- autotrade-broker-* autotrade-risk autotrade-app-engine autotrade-app-gateway autotrade-domain autotrade-config autotrade-marketdata autotrade-strategy autotrade-architecture-test`: 빈 결과.
  - `bash scripts/ci/check-collector-keys.sh`: 종료 코드 0. 새 패턴을 표본 7줄로 직접 시험(아래 R2).
  - 변이 18개(아래 "변이", 하나씩 넣고 `:autotrade-app-collector:test`, 끝나고 되돌림, `git status` 깨끗 확인)와 QA 임시 시험 2개(아래 S3·S4, 커밋하지 않고 지움).
  - `java` 단독 확인: `DateTimeFormatter.ofPattern("HHmmss")`(SMART)는 "240000"을 00:00으로 읽고, STRICT는 거절. `BASIC_ISO_DATE`는 "20260230" 거절.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-app-engine`·`autotrade-config` 무변경(위 `--stat` 빈 결과). RealTradingGuard 시험 포함 `autotrade-risk` 55·`autotrade-app-engine` 68건 통과(직접 빌드) |
| A2 | 통과 | 주문 코드 추가 없음. 수집기 한국투자 호출은 그대로 `KisNewsClient`의 토큰 발급 POST·뉴스 GET 두 주소뿐(`getNews`가 `caller.get` 대신 `caller.getAnswered`를 부르는 것만 바뀜, KisNewsClient.java 186행). `ApiCaller.get`은 `getAnswered(uri, headers, r -> false).body()`로 바뀌어 KRX·OpenDART 호출의 재시도·예외 동작은 전과 같음(ApiCaller.java 46~80행, 기존 `ApiCallerTest` 통과). ArchUnit 규칙 무변경, 46건 통과 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음 |
| A4 | 해당 없음 | 주문 재전송 로직 변경 없음 |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음. 뉴스 제목은 아직 선정 지표에 연결되지 않음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 무변경. 이 PR은 한국투자 호출 수를 줄이기만 함: 본문에 `rt_cd`가 있는 비 200 응답은 재시도하지 않음(`초당_한도_초과가_HTTP_500으로_와도_다시_부르지_않고_결과_코드로_멈춘다` 호출 1번, 변이 M1·M3 잡힘). 결과 코드 없는 5xx·429·연결 실패만 전처럼 2번 재시도(`결과_코드_없는_5xx는_전처럼_…` 3번). 간격 350ms·`--interval-ms` 100 하한 무변경 |
| A8 | 통과 | 수집기는 별도 단발 프로세스. 매 GET·POST 전 `beforeCall`(장 시간 보호·감시 정지) 유지, 이번에 POST 쪽 시험 추가(`토큰_발급_POST도_보내기_전에_장_시간을_확인한다`: POST·GET 모두 0건). 실전 앱키 공유 위험은 PR #222와 같음(점검표 D8, D-136 "D-054 R1 수집기 예외", D-054 R1 아래 연결 줄 추가) |
| A9 | 통과 | 게이트웨이·`autotrade-broker-*`·서버 측 손절 코드 무변경 |
| A10 | 통과 | diff 추가 줄에 키·토큰·계좌번호 모양 없음(시험 값은 `KISKEYCCCCCCCCCC` 같은 가짜 16자). 가림 목록을 `CollectorMain.redaction(secrets)`로 빼 네 키 모두 가리는 시험 추가(`수집기가_읽은_키는_모두_가린다`, 시크릿을 뺀 변이 M13 잡힘). 토큰 응답에 `access_token`이 없을 때 본문을 메시지에 싣지 않는 시험 추가. 비 200 + 성공 코드 거절 메시지는 상태 코드만(`KisRejected("(HTTP 500)")`). CI 키 검사에 `app-key`·`app-secret` 패턴 추가(빈틈은 R2) |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled`·약화 없음, `autotrade-architecture-test` 무변경. 고정 값 변경 없음. 보호 경로는 늘기만 함(`CollectorSecrets.java`·`CollectorMain.java`, `protected-paths.txt`·`CODEOWNERS` 양쪽 같은 2줄). 기존 시험 기대값 변경 2곳은 이전 결함 고정을 바로잡은 것: `행_해석_…`가 입력 "9301"→기대 "930100"(93시)을 고정하던 것을 입력 "093010"→기대 "093010"으로, 백필 `235959` 호출 수 7→8(시작 커서 00:00:00을 23:59:59로 보내는 새 동작, `000000` 0건 단언 추가). 변이 18개 중 14개 잡힘, 생존 4개는 같은 동작 1·시험 빈틈 3(아래) |
| A12 | 통과 | 장애 테스트 8종 대상 코드 무변경. 한국투자 "결과 코드 먼저" 순서는 D-054 4번(자동 재시도 안 함)과 공식 `KisRestClient`의 판단 순서에 맞춰짐(PR #222 QA S1·S1b 해소: EGW00201 + HTTP 500 → 1번, 토큰 만료 + HTTP 500 → 재발급 1번 후 성공, 변이 M17 잡힘) |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. 수집기 시각은 서울 기준 그대로 |
| A14 | 통과 | D-136 "후속 고침" 1~7번, 결정 색인 D-136 행, D-054 R1 연결 줄, runbook collector.md 3.9·6번, `qa-out-status.md` PR #222 줄이 코드와 일치(결과 코드 먼저 `KisNewsClient.answered`, 6자리·엄격 해석 `headline`, 같은 초 `pageOldest.isBefore(stopAt)`·`next.isBefore(stopAt)`, 자정 셈 `midnightCuts`, 시작 커서 00:00:00 → 1초 앞, 리포트 두 칸, 보호 경로 2줄, CI 패턴). D-136 3번의 "자정 확인 못 함"은 "쪽 끝이 00:00:00 행"인 경우로 적혀 있고 코드도 그 경우만 센다. 그 밖의 자정 건너뜀은 아래 Q1 |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(1,928건, 실패 0), CI Gradle build success |
| B6 | 통과 | 새 시험 14건(`ApiCallerTest` 3, `KisNewsClientTest` 5, `KisNewsCollectorTest` 6)과 `KisNewsCommandTest` 리포트 새 칸 단언 2줄, 건너뜀·삭제 없음. 생존 변이는 아래 Q1·Q2·Q3 권고 |
| 과금(지침 8장 1번) | 통과 | 새 유료 호출 없음. 호출 주소·TR 그대로(한국투자 Open API, 무료는 사용자 확인 2026-10-09, 뉴스 TR 무료는 PR #222 때와 같이 `[추론]`). 이 PR은 재시도를 줄여 호출 수가 같거나 적다. `kis-news`는 사용자가 키를 넣고 손으로 돌릴 때만 동작(기본 꺼짐) |

## 변이 (하나씩 넣고 `:autotrade-app-collector:test`)
| # | 변이 | 결과 |
|---|---|---|
| M1 | `getAnswered`에서 `answered.test(r)` 무시 | 잡힘 |
| M2 | 비 200 + `rt_cd` "0" 거절 제거 | 잡힘 |
| M3 | `answered` 항상 참 | 잡힘 |
| M4 | `headline`의 시각 엄격 해석을 SMART로 | 잡힘 |
| M5 | `HMS`의 STRICT 제거 | 잡힘 |
| M6 | 완료 조건을 `!pageOldest.isAfter(stopAt)`로 되돌림 | 잡힘 |
| M7 | `next.isBefore(stopAt)` 완료(242~245행) 제거 | **생존**: 다음 쪽에서 `pageOldest.isBefore(stopAt)`로 끝나 결과가 같고 호출만 1번 늘어남. 같은 동작에 가까움(Q3) |
| M8 | 시작 커서 00:00:00 → 1초 앞 조정 제거 | 잡힘 |
| M9 | `midnightCuts++` 제거 | 잡힘 |
| M10 | crowded 조건에서 `next.isBefore(first)` 제거 | 잡힘 |
| M11 | crowded를 새 행 없는 쪽에서만 셈 | 잡힘 |
| M12 | `badRows++` 제거 | 잡힘 |
| M13 | 가림 목록에서 `kisNewsAppSecret` 제거 | 잡힘 |
| M14 | 리포트 "거른 행" 줄 제거 | 잡힘 |
| M15 | 시각 자릿수 검사 `\d{6}` → `\d{1,6}` | **생존**: 6자리가 아니면 엄격 해석이 어차피 거절해 같은 동작 |
| M16 | `midnightCuts`를 쪽 끝이 자정일 때만이 아니라 자정 조정마다 셈(`if (true)`) | **생존**: 시험이 구별하지 못함. 오히려 이 변이 쪽이 아래 S3 손실까지 센다(Q1) |
| M17 | 토큰 만료 재발급 제거 | 잡힘 |
| M18 | 콘솔 "거른 행" 줄 제거 | **생존**: 콘솔 출력 시험 없음(Q2) |

## 시나리오 (점검표 밖, 잘못된 주문·중복 주문·보호 공백을 만들 수 있는지)
수집기는 주문을 내지 않으므로 "실전 한도 공유"와 "선정 입력이 될 자료의 조용한 오류"를 봤다.

- **S1 한국투자가 결과 코드를 담아 HTTP 4xx로 답할 때**(코드 읽기): 전에는 `ApiCaller`가 4xx를 바로 `IOException("HTTP 4xx")`로 던졌고, 지금은 본문에 `rt_cd`가 있으면 돌려받아 `KisRejected(코드)`로 멈추거나 토큰 만료면 1번 재발급한다. 호출 수는 전과 같거나(1번) 재발급 1번이 늘 뿐이고, 재발급은 쪽마다 최대 1번이며 1분 1회 제한에 걸리면 `post`가 재시도 없이 예외로 끝난다(ApiCaller.java 86~100행). 한도 공유 위험이 늘지 않는다 `[추론]`.
- **S2 성공 코드인데 HTTP 500**(시험 `결과_코드_없는_5xx는_…`의 `odd`): 1번 부르고 `KisRejected("(HTTP 500)")`로 멈춤, 행을 쌓지 않음. 정상.
- **S3 자정 직전 쪽이 00:00:01에서 끝나고 그 쪽에 새 행이 없을 때**(QA 임시 시험, 쪽 크기 2): 앞 실행(10-21 00:00:05)이 00:00:04~00:00:01 행 4건을 받은 뒤, 00:00:00 행 Z가 늦게 실림. 다음 실행(00:05:00)은 커서 `""` → `000002` → (새 행 없는 쪽이라 가장 옛 행 00:00:01에서 1초 뺀 00:00:00 → 자정 조정) `20261020 235959`로 넘어가 **Z를 한 번도 요청하지 않는다**. 결과: `complete=true`, 훑은 구간 `2026-10-20T23:50:05 ~ 2026-10-21T00:05`(Z의 시각을 덮음), `midnightCuts=0`, `crowdedSeconds=0`, 캐시에 Z 없음. 즉 리포트 어느 칸에도 드러나지 않는 손실이다. 쪽 끝이 00:00:00 행일 때만 세는 조건(KisNewsCollector.java 238행) 때문이며, 커서 `000001`로 한 번 더 받거나 자정 조정마다 세면(변이 M16) 드러난다. 앞 실행이 00:00~00:10 사이에 돌고 그 뒤 00:00:00 행이 실려야 생기는 드문 경우다 `[추론]`(runbook 실행 시간대는 저녁·07~08시).
- **S4 PR #222 판으로 이미 쌓인 캐시에 시각 "240000" 행이 있을 때**(QA 임시 시험): PR #222 판은 SMART 해석이라 "240000"을 같은 날 00:00으로 읽고 캐시에 그대로 "240000"을 적는다. 이 PR의 STRICT `KisNewsCollector.at`은 캐시를 읽는 `KisNewsSnapshotBuilder.build`에서 `DateTimeParseException`(검사 안 하는 예외)을 던진다. 수집 걸음 자체는 통과하지만 스냅샷 만들기에서 **매 실행이 실패**하고, 캐시의 그날 파일을 손으로 고치기 전까지 풀리지 않는다. PR #222 판으로 `kis-news`를 돌린 적이 없으면 생기지 않는다(PR 본문은 "사용자의 첫 kis-news 실행 전에"라고 적음, 확인은 사용자 몫, R1). 같은 이유로 PR #222 판이 "1"→"100000"처럼 잘못 채운 행은 엄격 해석을 통과해 틀린 시각으로 남는다.
- **S5 비 200 응답 본문이 크거나 JSON이 아닐 때**: `answered`가 예외를 삼키고 거짓 → 전처럼 5xx·429만 재시도, 4xx는 바로 예외. 가림 없는 본문이 메시지에 실리는 경로 없음(코드 읽기).

## 머지 전 확인
- **R1** PR #222가 머지된 2026-10-22 06:25Z 뒤 이 PR 머지 전까지 `kis-news`를 한 번이라도 돌렸는지 사용자가 확인한다. 돌렸다면 `collector-cache/kis-news/days/*.csv`의 `time` 칸에 6자리 실제 시각이 아닌 값("240000" 등)이나 PR #222 판이 채운 값이 있는지 보고, 있으면 캐시를 지우고 다시 받는다(S4). 돌리지 않았다면 할 일 없음.

## 권고 (막지 않음)
- **Q1** S3: 자정 조정이 일어날 때 쪽 끝이 00:00:00 행이 아니어도 그 초를 확인하지 못한 것이므로 `midnightCuts`를 세거나(변이 M16과 같은 동작), 새 행 없는 쪽에서 다음 커서가 00:00:00이 될 때는 `000001`로 한 번 더 받아 00:00:00 행을 보게 한다. S3 모양 시험(늦게 실린 00:00:00 행, 앞 쪽이 00:00:01에서 끝남)을 넣는다.
- **Q2** 콘솔 "날짜·시각을 읽을 수 없어 거른 행" 줄 시험이 없다(M18). 리포트 칸은 0만 확인하므로, 0이 아닌 값이 리포트·콘솔에 찍히는 시험(architect 남은 권고와 같음)을 함께 넣는다.
- **Q3** 걸음별 호출 수 시험이 없어 `next.isBefore(stopAt)` 조기 완료를 지워도 잡히지 않는다(M7, 하루 400회 몫을 1번씩 더 씀). 대표 걸음의 `http.calls` 수를 고정하면 잡힌다.
- **R2** `check-collector-keys.sh` 새 패턴은 `"app-key": "…"`(JSON)와 `\"app-secret\":\"…\"`(Java 이스케이프)를 잡지만, 직접 시험한 표본 중 `"appkey": "PS…36자"`(대시 없음, 한국투자 헤더·토큰 본문의 실제 칸 이름), 작은따옴표 `'app-key': '…'`, 값 중간에 `-`가 있는 경우는 놓친다. 키 파일 칸 이름이 `app-key`라 주 경로는 막히지만, 문서·시험에 헤더 이름 `appkey`로 실제 값이 붙여 넣어지는 경우를 위해 `appkey`·`appsecret`도 패턴에 넣는 것을 권한다.

## 판정
**승인**

머지 전 R1(사용자가 PR #222 판으로 `kis-news`를 돌린 적 있는지 확인)만 확인하면 된다. 권고 Q1~Q3·R2는 다음 수집기 PR. 머지는 사용자가 한다.
