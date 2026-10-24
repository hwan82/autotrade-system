# architect 검토: D-136 후속 고침 4 (kis-news 토큰 발급 거절 메시지에 한국투자 결과 코드)
- 대상: PR #234 / 커밋 ecf8071d906f9a74e77e5f0d21b11eb8268acb8e / `git diff origin/main...HEAD` 전체(8개 파일, +93 −10)
- 점검표: B
- 직접 실행한 것(모두 PR 머리 커밋을 따로 꺼낸 detached `git worktree`에서):
  - `./gradlew build --offline` → BUILD SUCCESSFUL in 6m 52s, 138 tasks
  - `bash scripts/ci/check-collector-keys.sh` → exit 0
  - 변이 시험 4종(`:autotrade-app-collector:test`, 152개, 변이마다 원본으로 되돌림, 끝난 뒤 `git status` 깨끗):
    - M1 거절 메시지 끝에 `r.body()`를 붙임 → 1개 실패, 잡힘
    - M2 안내 문구를 늘 `EGW00133` 갈래로 → 1개 실패, 잡힘
    - M3 `error_code`를 코드 모양 검사 없이 그대로 실음 → 1개 실패, 잡힘
    - M4 `error_code`를 무시하고 `msg_cd`만 읽음 → 1개 실패, 잡힘

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --offline` BUILD SUCCESSFUL(머리 커밋 ecf8071d, 별도 worktree) |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-app-collector` 안 `ApiCaller.java`, `KisNewsClient.java` 두 파일뿐. import·모듈 의존 변화 없음(`HttpGetter.Response`는 같은 패키지). ArchUnit(`autotrade-architecture-test`)은 빌드에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `ApiCaller.postAnswered`는 수집기 모듈 안 공개 메서드 추가(모듈 간 계약 아님, 호출자는 `KisNewsClient.java:213` 하나). `post`는 `postAnswered`를 감싸 동작 그대로(ApiCaller.java:86-91, `ApiCallerTest.postAnswered는_거절도_한_번만_보내고_돌려주며_post는_그대로_예외다`). 동작 변경은 D-136 "후속 고침 4" 절(docs/decisions/D-136-kis-news-snapshot.md:82-87)·README 행·runbook 3.9(docs/runbooks/collector.md:120-121)에 기록 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 다루는 코드 없음 |
| B6 | 통과 | 새 동작에 시험 2개(`KisNewsClientTest.토큰_발급_거절은_결과_코드만_싣고_설명과_본문은_싣지_않는다`, `ApiCallerTest.postAnswered는_…`). 변이 M1~M4 모두 잡음. 기존 시험 `토큰_발급_실패는_재시도하지_않고_1분_뒤_안내를_한다`(KisNewsClientTest.java:96-104)는 수정 없이 통과. 지운·건너뛴 시험 없음 |
| B7 | 통과(risk-ops 검토 필요) | 보호 경로 `autotrade-app-collector/.../ApiCaller.java`, `KisNewsClient.java` 수정(scripts/ci/protected-paths.txt). 실전 관련 PR이므로 risk-ops 검토와 사용자 머지·독립 QA 대상(docs/qa/pending.md 끝 줄 추가 확인) |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음. 시험 값은 `MARKERTOKEN`, `KIS-APPKEY` 같은 자리표시. 거절 메시지는 본문·`error_description`을 싣지 않고 `[A-Za-z0-9]{1,20}` 코드만 싣는다(KisNewsClient.java:235-252, `resultCode` 268-271 재사용) — 한국투자 앱키(36자)·토큰(JWT)은 이 모양에 맞지 않음 `[추론]`. CI 키 검사 exit 0 |

## 점검표 밖 발견
1. (권고, 막지 않음) `tokenRejected`는 `error_code` 키가 있으면 그 값만 본다(KisNewsClient.java:240-242). `error_code`가 빈 문자열이거나 코드 모양이 아니고 `msg_cd`가 따로 있으면 "결과 코드 없음"이 된다. 실제 응답에서 두 키가 같이 오는지는 모르므로 `[가정]`이며, 안전한 쪽(덜 싣기)으로 틀린다. 첫 실행에서 "결과 코드 없음"이 보이면 `error_code`가 비었을 때 `msg_cd`로 넘어가게 고칠 만하다.
2. (권고, 막지 않음) runbook 3.9(docs/runbooks/collector.md:120)는 "`EGW00133`은 1분 1회 발급 제한"을 단정으로 쓰지만 코드 값은 `docs/research/kis-open-api-spec.md:83,147`에서 `[가정]`이다. 코드 쪽 안내는 다른 코드여도 "1분 발급 제한이면 1분 뒤 다시"를 함께 주므로 오판 위험은 작다. 문서에 `[가정]` 표시를 붙이면 일관된다. 사용자 첫 실행 결과 코드로 확인하면 이 가정을 닫을 수 있다.
3. (참고, 기존 동작) HTTP 200인데 `access_token`이 없는 응답은 여전히 결과 코드 없이 "access_token이 없습니다"만 낸다(KisNewsClient.java:221-224). 이 PR 범위 밖.
4. (참고, 기존 동작) 연결 실패·대기 중 중단(`IOException`)은 여전히 "1분에 1번만 발급되므로 1분 뒤 다시 실행" 문구로 감싸진다(KisNewsClient.java:214-217). 장 시간 보호(`RunGuard`)·하루 상한(`CallBudget.BudgetExhausted`)은 `RuntimeException`이라 이 catch에 걸리지 않음을 확인(CallBudget.java:187). 이 PR로 바뀐 것은 없다.
5. (참고) `ApiCaller.post`는 이제 운영 코드에서 부르는 곳이 없다(시험만). 다른 수집기가 쓸 일반 POST로 남겨 둔 것으로 보며 막지 않는다.

## 판정
승인
