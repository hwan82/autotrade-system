# risk-ops 검토: PR #187 후속 재기동 다시 쓰기 시험 보강(QA Q1·Q2, risk-ops N1, architect 2)
- 대상: PR #192 / 커밋 0f6bf5f / main(94981d9) 대비 diff(`git diff origin/main...HEAD`, 4개 파일: `DayWriter.java` 1줄, `DayWriterRestartStreamTest.java`, `D-119-daywriter-streamed-restart.md` 후속 반영 절, `docs/qa-out-status.md` 1행)
- 점검표: A (보호 경로 `autotrade-marketdata/*` 변경, `scripts/ci/protected-paths.txt:14`, 실전 관련 PR로 취급)
- 직접 실행한 것(별도 git worktree, `--project-cache-dir` 분리, `--offline`. 저장소 작업 트리는 건드리지 않음):
  - `./gradlew --offline build --continue` → BUILD SUCCESSFUL(2분 14초). 전체 빌드(컴파일, 테스트, 순수 모듈 의존 검사, ArchUnit) 통과.
  - `:autotrade-marketdata:test :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-gateway:test` 결과 XML: marketdata 156/0 실패, risk 55/0, architecture-test 46/0, app-engine 68/0, app-gateway 55/0(건너뜀 0). `DayWriterRestartStreamTest` 5개 통과.
  - 변이 시험(끝나고 원복, worktree 깨끗함 확인):
    - P4: `rewriteToTmp`에서 `withSourceSequence(l)`를 `l.event()`로 바꿈 → marketdata 전체에서 `정상_종료_녹화를_다시_쓴_줄은_원본_줄과_바이트가_같다` 하나만 실패(기대 `"src":"KIS-WS","srcSeq":5000`). 같은 변이에 main(94981d9)의 시험 파일은 통과 → QA Q1의 빈틈을 이 PR 시험이 메움을 확인.
    - P10: `lastAlive`가 정상 종료 시각을 무시하고 마지막 수신 시각을 쓰게 바꿈 → marketdata 전체에서 `앞_날_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다` 하나만 실패(gapStart 2026-10-20T00:00:10Z). main 시험 파일은 통과 → QA Q2 빈틈 메움 확인.
    - N1: Probe가 `lastSeq` 출력 뒤 600초 잠들게 하고 제한을 10초로 줄임 → 시험이 약 10초 만에 출력과 함께 실패, 남은 Probe 프로세스 없음(`pgrep` 0건). 자식이 멈춰도 시간 제한이 듣고 강제 종료됨을 확인.

## 변경 요약(코드로 확인)
- 운영 코드 변경은 `DayWriter.java:132` 예외 문구 한 줄(`"녹화 재기동 다시 쓰기 실패: " + dir`)뿐. 예외 종류(UncheckedIOException)·원인 체인·정리 catch(tmp 삭제, :136-143)·순서 무변경. 이 문구를 해석하는 코드 없음(grep: marketdata·app-gateway에 해당 문자열은 이 줄뿐).
- 시험: Q1(소스 둘·소스 순번을 녹화 순번과 다르게, 다시 읽은 줄의 src·srcSeq 확인), Q2(앞 날 정상 종료가 마지막 수신보다 늦을 때 날짜 넘긴 재기동 공백 시작=정상 종료 시각, 종목 정렬 순서·RECORDER_RESTART 확인), N1(자식 JVM 출력 파일 리다이렉트, `waitFor` 시간 초과 시 `destroyForcibly`).
- 실전 엔진·감시는 녹화 패키지를 싣지 않는다: `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`에 `recording` 참조 없음(grep 0건).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정 로딩·Guard 테스트 변경 없음(`git diff --name-only`: marketdata 2파일·문서 2개). `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 바뀐 코드는 녹화 예외 문구뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 무관. 시험이 다루는 순번은 녹화 줄 순번·소스 순번(DayWriterRestartStreamTest.java:101-104, 117-120) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 68개 통과 |
| A8 | 통과 | 동작 변경 없음(예외 문구만). 녹화 코드는 게이트웨이·실험실에서만 돌고 실전 엔진은 싣지 않음(위 grep). N1 수정으로 시험 자식 JVM이 멈춰도 10초 축소 변이에서 강제 종료·정리됨을 직접 확인, CI 자원 점유 위험 감소 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 appkey·secret·token·account·password·계좌 문자열 없음(grep 0줄). 새 예외 문구는 녹화 폴더 경로(`<root>/<market>/<date>`)만 담음(DayWriter.java:132). 시험 픽스처는 공개 종목코드·합성 가격·임의 소스 이름(KIS-WS, KIWOOM-WS) |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·장애 테스트) 삭제·비활성화·고정 값 변경 없음. 기존 시험 단언은 지우지 않고 강화(바이트 같음 단언 유지 + src/srcSeq 단언 추가, DayWriterRestartStreamTest.java:113-120). 새 시험이 변이 P4·P10을 각각 유일하게 잡고 main 시험은 놓침을 직접 확인. N1 시간 제한 동작도 변이로 확인 |
| A12 | 통과 | 대응표 #9(broker-and-fault-design.md:168, 게이트웨이 크래시 → 재기동 후 공백 표시)의 재기동 공백 시작 규칙(`lastAlive`, DayWriter.java:160-167)을 이 PR이 날짜 넘김 경우까지 시험으로 고정. 동작 무변경이라 장애 테스트 8종·해외 O1~O8 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 시험은 기존 SEOUL 시간대·KRX 그대로 |
| A14 | 통과 | D-119 "후속 반영" 절(Q1·Q2·N1·architect 2·남은 것)이 diff 내용과 일치. 동작 변경이 없어 새 결정·기획서 버전 불필요. `docs/qa-out-status.md` 새 행의 머지 커밋 94981d9는 `git log`와 일치 |

## 점검표 밖 발견
- 앞 검토(PR #187) N1은 이 PR로 닫힘(위 변이 확인). N2·N3(Error·종료 시 tmp 잔존)는 D-119 "남은 것"에 그대로 둔다고 기록됨. 다음 재기동이 덮어쓰고 리플레이가 tmp를 읽지 않아 녹화 무결성 영향 없음 — 동의.
- 참고(판정 무관): Q2 시험은 `resumed`(OPEN+5초)가 `ended`보다 늦은 경우만 본다. `ended`가 재개 시각보다 늦으면 재개 시각으로 자르는 분기(DayWriter.java:230)는 기존 시험 몫이며 이 PR 범위 밖.
- 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응 규칙 변경 없음.

## 판정
승인
