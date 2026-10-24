# QA 검토: D-136 후속 고침 4: kis-news 토큰 발급 거절 메시지에 한국투자 결과 코드 (첫 실행 HTTP 403)
- 대상: PR #234 / 커밋 `f835c71b` (브랜치 `claude/project-thread-jhpj43`) / main 대비 diff 전체(merge-base = main `fc28786d`, 10파일 +163 −10: 수집기 운영 코드 2파일(`ApiCaller`, `KisNewsClient`), 시험 2파일(`ApiCallerTest`, `KisNewsClientTest`), 문서 6파일(D-136, 결정 색인, `pending.md`, runbook `collector.md`, 검토 원문 2))
- 점검표: A 전체 + B1, B6, 프로젝트 지침 8장 과금 검사
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon` (헤드 `f835c71b`): 1~8회째 의존성 받기 실패(Maven Central 429/"Could not" 줄이 있을 때만 20초 뒤 다시 돌리는 루프, 빌드 실패로 보지 않음), 9회째 **BUILD SUCCESSFUL in 5m 19s**, 미러 없이. 시험 결과 XML 합계 1,936건, 실패·오류·건너뜀 0.
  - PR 헤드 CI(GitHub check runs, 2026-10-24T06:47~06:51Z): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens (Playwright) 모두 success.
  - `git merge-tree --write-tree origin/main HEAD`: 충돌 없음(merge-base가 현재 main `fc28786d`).
  - `scripts/ci/mark-protected-changes.sh main`: 보호 대상 2개 표시(`ApiCaller.java`, `KisNewsClient.java`, `protected-paths.txt` 22·19행).
  - `git diff --stat origin/main...HEAD -- autotrade-broker-* autotrade-risk autotrade-app-engine autotrade-app-gateway autotrade-domain autotrade-config autotrade-marketdata autotrade-strategy autotrade-architecture-test .github scripts`: 빈 결과.
  - `bash scripts/ci/check-collector-keys.sh`: 종료 코드 0. diff 추가 줄의 `appkey|secret|token` 줄을 모두 읽음(시험 값은 MARKER*·`KIS-APPKEY`·`X`뿐).
  - 변이 11개(아래 "변이", 하나씩 넣고 `:autotrade-app-collector:test --offline`, 실패한 시험 이름을 결과 XML에서 확인, 끝나고 되돌림, `git status` 깨끗 확인).
  - `EGW00133` 뜻을 웹 검색으로 확인: 2차 자료만 나옴(아래 Q2).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-app-engine`·`autotrade-config` 무변경(위 `--stat` 빈 결과). RealTradingGuard 시험 포함 전체 1,936건 통과(직접 빌드) |
| A2 | 통과 | 주문 코드 추가 없음. 바뀐 호출은 토큰 발급 POST 한 곳(KisNewsClient.java:213 `postAnswered`), 주소(`TOKEN_PATH`)·헤더·본문·HTTP 메서드 무변경. ArchUnit 규칙·`autotrade-architecture-test` 무변경, 전체 빌드에서 통과 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음 |
| A4 | 해당 없음 | 주문 재전송 로직 변경 없음. 토큰 발급도 재시도 없음 유지: `postAnswered`는 `http.post` 한 번(ApiCaller.java:98~109), 시험 `ApiCallerTest#postAnswered는_거절도_한_번만_보내고_...`(posts 1건)·`KisNewsClientTest#토큰_발급_거절은_...`(posts 1건), 비200이면 한 번 더 보내는 변이 M7 잡힘 |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음. 뉴스 제목은 아직 선정 지표에 연결되지 않음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 무변경. 수집기 호출 상한 `budget.acquire()`와 간격 `pause(0)`이 `postAnswered`에 그대로 있음(ApiCaller.java:99~101). 상한 제거 변이 M9 잡힘(`KisNewsCollectorTest#호출_상한에_닿으면_...`) |
| A8 | 통과 | 수집기는 별도 단발 프로세스. 장 시간 보호 `beforeCall`이 토큰 발급 전에 그대로 돎(ApiCaller.java:100), 제거 변이 M8 잡힘(`ApiCallerTest#토큰_발급_POST도_보내기_전에_장_시간을_확인한다`). 실전 앱키 공유 위험은 PR #222와 같음(점검표 D8) |
| A9 | 통과 | 게이트웨이·`autotrade-broker-*`·서버 측 손절 코드 무변경 |
| A10 | 통과 | 거절 메시지에 실리는 것은 HTTP 상태 숫자, `resultCode` 모양(`[A-Za-z0-9]{1,20}`, KisNewsClient.java:268~271)을 통과한 코드, 고정 안내 문구뿐(KisNewsClient.java:235~252). 본문·`error_description`을 싣는 변이 M1, 파싱 실패 때 본문을 코드로 쓰는 변이 M11, 코드 모양 거름을 뺀 변이 M4 모두 잡힘. 최종 출력은 `CollectorMain.java:130`에서 `redaction.clean`을 한 번 더 거침. diff 추가 줄에 키·토큰·계좌번호 모양 없음, CI Secret scan success |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled`·약화 없음, `autotrade-architecture-test`·`protected-paths.txt`·`CODEOWNERS` 무변경, 고정 값 변경 없음. 기존 시험 기대값 변경 없음(`KisNewsClientTest#토큰_발급_실패는_재시도하지_않고_1분_뒤_안내를_한다` 97~104행 그대로 통과). 새 동작에 실패 경우 시험 있음(EGW00133, 다른 코드 EGW00103, 코드 모양 아님, JSON 아님, `post` 예외 유지). 변이 11개 모두 잡힘 |
| A12 | 통과 | 장애 테스트 8종 대상 코드 무변경. 토큰 발급 실패는 이전처럼 `IOException`으로 그 실행만 종료 코드 1(CollectorMain.java:129~131). 예외 종류가 바뀌지 않아 종료 코드 3(상한)·4(장 시간 거절) 분기 무영향 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음 |
| A14 | 통과 | D-136 "후속 고침 4" 1·2번과 결정 색인 D-136 행이 코드와 일치(`post`는 `postAnswered`를 감싸 동작 같음 = ApiCaller.java:86~92, `error_code` 키가 있으면 그 값·없으면 `msg_cd`, 코드 모양만, `EGW00133` 안내 분기 = KisNewsClient.java:240~250). "추천안 채택(자율)" 표시 있음. runbook 3.9의 `EGW00133` 단정 문구는 `[가정]` 표시가 빠짐(아래 Q2, 판정 영향 없음) |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(1,936건, 실패 0), CI Gradle build success |
| B6 | 통과 | 새 시험 2건(`ApiCallerTest` 1, `KisNewsClientTest` 1, 거절 4가지 경우), 건너뜀·삭제 없음. 변이 11개 모두 잡힘 |
| 과금(지침 8장 1번) | 통과 | 새 호출·새 주소 없음. 토큰 발급 횟수 그대로(재시도 없음). 한국투자 Open API 무료는 사용자 확인(2026-10-09). `kis-news`는 사용자가 키를 넣고 손으로 돌릴 때만 동작 |

## 변이 (하나씩 넣고 `:autotrade-app-collector:test`)
| # | 변이 | 결과(실패한 시험) |
|---|---|---|
| M1 | 거절 메시지 끝에 응답 본문 덧붙임 | 잡힘(`KisNewsClientTest#토큰_발급_거절은_결과_코드만_싣고_설명과_본문은_싣지_않는다`) |
| M2 | 안내를 항상 "1분 뒤 다시"로 | 잡힘(같은 시험) |
| M3 | `error_code` 분기 제거(`msg_cd`만 봄) | 잡힘(같은 시험) |
| M4 | `error_code` 값의 코드 모양 거름 제거 | 잡힘(같은 시험) |
| M5 | 토큰 발급 비200 확인 제거(거절 본문을 토큰 응답으로 읽음) | 잡힘(위 시험 + `토큰_발급_실패는_재시도하지_않고_1분_뒤_안내를_한다`) |
| M6 | `post`의 비200 예외 제거 | 잡힘(`ApiCallerTest#postAnswered는_거절도_한_번만_보내고_돌려주며_post는_그대로_예외다`) |
| M7 | `postAnswered`가 비200이면 한 번 더 보냄 | 잡힘(`ApiCallerTest` 위 시험 + `KisNewsClientTest` 위 시험) |
| M8 | `postAnswered`에서 `beforeCall`(장 시간 확인) 제거 | 잡힘(`ApiCallerTest#토큰_발급_POST도_보내기_전에_장_시간을_확인한다`) |
| M9 | `postAnswered`에서 `budget.acquire()` 제거 | 잡힘(`KisNewsCollectorTest#호출_상한에_닿으면_훑은_데까지만_구간으로_남기고_다음_실행이_빈틈을_메운다`) |
| M10 | 메시지에서 HTTP 상태 뺌 | 잡힘(`KisNewsClientTest` 두 시험) |
| M11 | JSON 파싱 실패 때 본문을 결과 코드로 씀 | 잡힘(`KisNewsClientTest#토큰_발급_거절은_...`) |

## 점검표 밖 발견
잘못된 주문·중복 주문·보호 공백 시나리오(수집기는 주문하지 않으므로 "중복 호출·키 노출·보호 우회"로 바꿔 시도):
- S1 (거절 뒤 바로 다시 실행): 403 `EGW00133`이면 메시지가 "1분 뒤 다시"를 안내하고 자동 재시도는 없음(M7 잡힘). 사용자가 곧바로 다시 돌려도 호출은 1번씩이고 하루 상한에 셈(M9 잡힘). 중복 발급·상한 우회 없음.
- S2 (거절 본문에 비밀값이 섞임): 본문의 `access_token`·`error_description`·HTML은 싣지 않음(M1·M11 잡힘). `error_code` 자리에 키 조각이 와도 20자 이하 영숫자만 통과, 한국투자 앱키는 36자 `[가정]`(risk-ops 원문과 같음)·토큰은 `.` 포함 JWT라 통과 못 함 `[추론]`. 20자 이하 영숫자 비밀값이 `error_code`에 그대로 오는 경우만 남지만 서버가 키를 되돌려 줄 이유가 없음 `[추론]`, 최종 출력도 `redaction.clean`을 거침.
- S3 (장 시간 보호 우회): 토큰 발급 경로가 `post`에서 `postAnswered`로 바뀌었지만 `beforeCall`·`budget`·`pause`가 새 메서드 안에 있어 우회 없음(M8·M9 잡힘). `post`는 이제 운영 코드 호출자가 없음(grep: `ApiCaller.java:87` 자기 자신뿐) — 동작 변화 없음.

권고(판정 영향 없음):
- Q1 (HTTP 200인데 토큰이 없는 거절) `[추론]`: 결과 코드는 비200 응답에서만 읽는다. 한국투자가 발급 제한을 HTTP 200 + `error_code` 본문으로 답하면(이번 첫 실행은 403이었음) 메시지는 여전히 "access_token이 없습니다"뿐이라 원인을 모른다(KisNewsClient.java:222~224). 같은 `tokenRejected` 문구를 이 경우에도 쓰면 일관된다.
- Q2 (`EGW00133` 근거): `docs/research/kis-open-api-spec.md`에서 `[가정]`이고, QA 웹 검색에서도 2차 자료만 나옴([AlgoLab 오류 코드 정리](https://algolab.co.kr/blog/kis-api-error-codes), [wikidocs](https://wikidocs.net/335909)). runbook 3.9(collector.md:120)가 단정으로 적음. 다른 코드에도 "1분 발급 제한이면 1분 뒤 다시"가 함께 나가므로 잘못 안내될 위험은 작다. architect 권고 2와 같음.
- Q3 (`error_code`가 비거나 모양이 아니면 `msg_cd`로 넘어가지 않음): architect 권고 1과 같음. 덜 싣는 쪽으로 틀린다.
- R1 (연결 실패 문구, 이 PR 전부터): 토큰 발급이 연결 실패(`IOException`)면 여전히 "1분에 1번만 발급되므로 1분 뒤 다시 실행"이라고 안내한다(KisNewsClient.java:215). 실제로 서버에 닿지 않았을 수 있어 1분 제한과 무관한데 원인을 잘못 짐작하게 한다. 다음 고침 때 "연결 실패" 문구로 나누기를 권한다.

## 판정
승인
