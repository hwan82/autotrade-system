# risk-ops 검토: PR #234 D-136 후속 고침 4 (kis-news 토큰 발급 거절 메시지에 한국투자 결과 코드)
- 대상: PR #234 / 커밋 ecf8071d906f9a74e77e5f0d21b11eb8268acb8e / main(570fe223) 대비 diff (`git diff origin/main...HEAD`, 커밋 2개, 파일 8개)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --offline` (detached worktree, HEAD ecf8071d): BUILD SUCCESSFUL. JUnit 결과 XML 집계 `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 46건, `:autotrade-app-engine:test` 68건, `:autotrade-app-collector:test` 152건 모두 실패 0·오류 0·건너뜀 0. 새 시험 `ApiCallerTest#postAnswered는_거절도_한_번만_보내고_돌려주며_post는_그대로_예외다`, `KisNewsClientTest#토큰_발급_거절은_결과_코드만_싣고_설명과_본문은_싣지_않는다` 통과
  - `bash scripts/ci/check-collector-keys.sh`: "수집기 키 검사 자체 시험 통과", 종료 코드 0
  - 바뀐 메서드의 호출자 검색(`grep postAnswered|.post(`): `ApiCaller.postAnswered`를 부르는 운영 코드는 `ApiCaller.post`와 `KisNewsClient.token()` 둘뿐. 실전 경로 `autotrade-broker-kis/KisTokenClient`·`KisApprovalClient`는 별도 `HttpPort`를 쓰고 이 PR에서 바뀌지 않음

## 바뀐 것 (코드로 확인)
- `autotrade-app-collector/.../ApiCaller.java:86-109`: `post`를 `postAnswered`(한 번 호출, 재시도 없음, `pause`·`beforeCall`(장 시간 `RunGuard`)·`budget.acquire()` 순서 그대로) + 200 아닌 상태 예외로 나눔. `post`의 동작·메시지(`HTTP <상태>: <가린 URL>`)는 그대로
- `KisNewsClient.java:210-229`: 토큰 발급이 `postAnswered`를 부르고 200이 아니면 `tokenRejected` 메시지로 `IOException`. 연결 실패 등 `IOException`은 기존 메시지 그대로(215-216)
- `KisNewsClient.java:235-252` `tokenRejected`: `error_code`(없으면 `msg_cd`)를 기존 `resultCode`(268-271, `[A-Za-z0-9]{1,20}`만)로 걸러 싣고, `error_description`·본문은 싣지 않음. JSON 아님·객체 아님·코드 모양 아님은 "결과 코드 없음"
- 문서: D-136 후속 고침 4 절, 결정 로그 README D-136 행, `docs/qa/pending.md` PR #234 줄, runbook `collector.md` 3.9 안내 문구

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff가 `autotrade-app-collector` 2개 파일·시험 2개·문서 4개뿐. `RealTradingGuard`·설정 기본값 코드 변경 없음(`git diff --stat origin/main...HEAD`). `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. 수집기 `KisNewsClient`는 고정 두 주소(`TOKEN_PATH`, `NEWS_PATH`, KisNewsClient.java:38-40)만 부르고 경로·TR을 인자로 받지 않음(변경 없음). `ArchitectureTest`·`RealPathReflectionTest` 포함 `:autotrade-architecture-test:test` 46건 통과, 해당 모듈 변경 없음 |
| A3 | 해당 없음 | 주문 라우팅 코드 변경 없음. 수집기는 주문 API를 부르지 않음(KisNewsClient.java:21-23, 변경 없음) |
| A4 | 해당 없음 | 주문 타임아웃 처리 변경 없음. 참고로 토큰 발급 POST는 바뀐 뒤에도 재시도 0회(ApiCaller.java:98-109 반복문 없음), `ApiCallerTest#postAnswered는_...` 가 `http.posts` 1건·`post` 호출 뒤 2건으로 고정 |
| A5 | 해당 없음 | 멱등 키 생성 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 코드 변경 없음. 수집기는 주문하지 않음 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷 코드 변경 없음. 수집기 호출 상한 `CallBudget.acquire()`는 `postAnswered`에서도 매 호출 전에 그대로 돎(ApiCaller.java:101) |
| A8 | 통과 | 수집기는 별도 프로세스(`autotrade-app-collector`)이고 장 시간 보호 `beforeCall`(=`RunGuard::checkSession`, CollectorMain.java:368)이 토큰 발급 전에 그대로 돎(ApiCaller.java:100). 호출 간격 `pause(0)` 유지(ApiCaller.java:99). 모듈 의존 변경 없음(빌드 build.gradle 무변경), `:autotrade-app-engine:test` 68건 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 거절 메시지에는 상태 코드와 20자 이하 영숫자 결과 코드만 실림(KisNewsClient.java:240-251, `resultCode` 268-271). 앱키(36자 `[가정]`)·토큰(JWT, `.` 포함)은 이 모양을 통과할 수 없음 `[추론]`. 본문·`error_description` 미포함은 `KisNewsClientTest#토큰_발급_거절은_...`이 MARKERDESC·MARKERTOKEN·MARKERHTML·`<b>KIS-APPKEY</b>`로 확인. 최종 출력은 `CollectorMain.java:129-130`에서 `redaction.clean`을 한 번 더 거침. 시험 픽스처는 자리표시자(MARKER*, KIS-APPKEY, X)뿐이고 `check-collector-keys.sh` 통과. 새 커밋에 키·계좌번호 없음(diff 전체 확인) |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 시험, 장애 테스트) 변경 없음. 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 `KisNewsClientTest#토큰_발급_실패는_재시도하지_않고_1분_뒤_안내를_한다`(90-104행대)는 그대로 통과(완화 없음). 새 동작에 실패 경우 시험 있음: 다른 코드(EGW00103) 안내, 코드 모양 아님(HTTP 500 `<b>..</b>`), JSON 아님, 재시도 없음(posts 1건), `post`가 여전히 예외이며 본문 코드를 싣지 않음. 보호 경로(`scripts/ci/protected-paths.txt`의 `ApiCaller.java`·`KisNewsClient.java`) 수정이라 실전 관련 PR로 다룸 |
| A12 | 해당 없음 | 장애 대응표 #1~#17·O1~O8과 장애 테스트 8종은 실전 엔진·게이트웨이·증권사 플러그인 대상이고 이 diff는 그 코드를 건드리지 않음. 수집기 토큰 발급 실패는 기존처럼 그 실행만 종료 코드 1로 끝남(CollectorMain.java:129-131) |
| A13 | 해당 없음 | 미국 시장 경로·`MarketRules`·시간대 계산 변경 없음 |
| A14 | 통과 | D-136 후속 고침 4 절 1·2번이 코드와 일치(재시도 없음·`post` 동작 유지 = ApiCaller.java:86-109, `error_code` 우선·없으면 `msg_cd`·코드 모양만·설명 미포함·EGW00133 안내 분기 = KisNewsClient.java:235-252). 결정 로그 README D-136 행·`pending.md` 줄·runbook 3.9 안내가 같은 내용. "추천안 채택(자율)" 표시 있음 |

## 점검표 밖 발견
- (정보, 수정 요구 아님) `EGW00133` 코드 값은 `docs/research/kis-open-api-spec.md:83,147`에서 `[가정]`인데 D-136 후속 고침 4 절은 "기존 시험 고정값"으로만 적었다. 코드 값이 틀려도 그 밖 코드 안내가 "1분 발급 제한이면 1분 뒤 다시 실행"을 함께 주므로(KisNewsClient.java:249) 안전한 쪽으로 틀린다. 사용자 첫 실행에서 나온 실제 코드로 spec 5장 `[가정]`을 확인해 두기를 권한다.
- (정보) `tokenRejected` 반환값은 그 자리에서 `redaction.clean`을 거치지 않지만, 싣는 값이 상태 코드·20자 이하 영숫자·고정 문구뿐이고 최종 출력이 `CollectorMain.java:130`에서 가려지므로 누출 경로는 보이지 않는다.
- (정보) runbook의 콘솔 한글 깨짐 안내(`chcp 65001` 등)는 `[추론]`으로 표시돼 있고 실전 경로와 무관하다.

## 판정
승인
