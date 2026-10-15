# architect 검토: 실험 진행 상태 화면과 SSE(UI-02·UI-01, D-093)
- 대상: PR #117 / 브랜치 `claude/project-thread-4e7xy8` / 커밋 c01c67d6056cf9ac6994388f053d01447611307b / `git diff origin/main...HEAD` 전체(14파일, +767 −11)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (HEAD c01c67d): **BUILD SUCCESSFUL in 2m 24s**, 136 tasks(98 executed), 종료 코드 0. ArchUnit(`autotrade-architecture-test:test`), 순수 모듈 `verifyPureDependencies` 포함.
  - 새 시험 결과 파일 확인(`autotrade-app-lab/build/test-results/test`): `ExperimentJobStreamTest` 6/6, `ExperimentJobStreamShutdownTest` 1/1(1.921초), `ExperimentJobControllerTest` 3/3, 건너뜀 0.
  - `git diff origin/main...HEAD -- '*Test*.java'`에서 지운 줄 0줄(시험 삭제·약화 없음).
  - `scripts/ci/protected-paths.txt`와 바뀐 파일 목록 대조: 겹치는 경로 없음.
  - `git merge-tree --write-tree origin/main HEAD`(origin/main 6fe3101, PR #116 머지 후): **충돌** `docs/decisions/README.md`, `docs/spec/autotrade-spec.md`. 코드 파일 충돌 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 HEAD c01c67d에서 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 종료 코드 0. 단, 이 결과는 PR 브랜치 기준이며 현재 main(6fe3101)과 합친 트리의 빌드는 아니다(아래 "점검표 밖 발견" 1) |
| B2 | 통과 | 새 운영 클래스 `ExperimentJobStream`은 `autotrade-app-lab`의 `com.autotrade.app.lab.experiment` 패키지(ExperimentJobStream.java:1), Spring 의존(`SseEmitter`, `SmartLifecycle`)은 app-lab 안에만 있다(ExperimentJobStream.java:16, LabWebConfiguration.java:26). 순수 모듈(domain·strategy·sim·risk) 변경 없음. 새 모듈 없음, ArchUnit 규칙 변경 없음. 빌드의 `autotrade-architecture-test:test` 통과 |
| B3 | 통과 | 새 REST `GET /api/experiment-jobs/stream`이 ADR-005 REST 계약 표에 추가(ADR-005-ui-dashboard.md:114, 이벤트 이름·본문 모양·주기·상한·409·종료 순서), 이연 표 갱신(ADR-005:175). 결정 D-093 신설과 결정 로그 색인(docs/decisions/README.md:98), 기획서 UI-01·UI-02 행과 14장 이연 표 갱신(autotrade-spec.md:267-268, 358-359). 설정 키·DB 스키마 변경 없음. 본문 `Snapshot`(ExperimentJobStream.java:296)은 목록 API(ExperimentJobController.java:48-55, `Page.of`)와 같은 필드 이름·의미(`total`=읽은 전체 수, `Page.java:26,29`와 같음) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음(골든 시험은 빌드에서 그대로 통과). 새 코드의 현재 시각은 주입된 `Clock`만 씀(ExperimentJobStream.java:235, `clock.instant()`), `now()` 직접 호출·난수 없음. 시험은 `MutableClock`으로 시각을 고정(ExperimentJobStreamTest.java:585) |
| B5 | 해당 없음 | 금액 계산 없음. 진행률은 정수 `permille`을 화면에서 표시용으로만 나눔(jobs.js:715) |
| B6 | 통과 | 서버 새 동작마다 시험: 바뀔 때만 전송·새 연결 첫 상태(`바뀌었을_때만_보내고_새_연결에는_지금_상태를_준다`), 시간 경과로 UNRESPONSIVE 전파(`갱신이_멈춘_실행은_응답_없음으로_바뀌어_간다`), 주석 줄(`조용하면_주석_줄을_보낸다`), 실패 연결 제거(`보내기에_실패한_연결은_빠진다`), 상한과 종료 후 거절(`연결_수_상한과_종료`), 50개 첫 페이지(`첫_페이지는_50개까지`), 루프백 아닌 Host 403(`진행_상태_실시간_연결은_비동기로_열리고_루프백이_아니면_거절한다`), 실제 웹 서버 첫 이벤트·종료 10초 이내(`첫_이벤트를_바로_보내고_열린_연결이_종료를_늦추지_않는다`). 화면 JS(jobs.js)는 자동 시험 없음 — D-093 "위험과 한계"에 명시, 기존 화면과 같은 수준(Playwright CI는 5단계 QA 권고 R3 후속). 지운·건너뛴 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·보호 테스트·보호 경로 변경 없음(바뀐 14파일 모두 app-lab 실험 패키지, autotrade-ui 실험 화면, 문서). 실험실 종료 순서 변경(SmartLifecycle, LabWebConfiguration.java:84-104)은 실전 운영 화면을 싣는 실험실의 재기동 시간에만 관계하며, 기존 D-091 모니터링 SSE와 같은 방식(MonitorConfiguration.java:88)이다. 실전 엔진·명령 채널 코드는 건드리지 않는다 |
| B8 | 통과 | diff에서 키·계좌번호·토큰 패턴 없음(`grep -iE 'appkey\|secret\|password\|token='` 0건). 새 로그는 `log.debug("실험 작업 상태를 읽지 못했습니다: {}", e.toString())`(ExperimentJobStream.java:224) 하나로, 상태 파일 읽기 예외만 남긴다. SSE 본문은 기존 목록 API와 같은 필드(경로·비밀값 없음) |

## 점검표 밖 발견

1. **(머지 전 처리, 차단 아님) 현재 main과 문서 충돌.** origin/main이 PR #116(D-092, 기획서 v14.35)을 머지해 `docs/decisions/README.md`, `docs/spec/autotrade-spec.md`가 충돌한다. PR이 v14.35·D-092를 비워 둔 대로 표 행을 둘 다 남기면 되는 기계적 해소다. main의 `AutotradeLabApplication` 변경(하트비트, `main()`에서만 시작)은 이 PR의 `SpringApplicationBuilder` 기동 시험과 경로가 겹치지 않는다고 [추론]하지만 합친 트리로는 빌드하지 않았다. 해소 커밋 뒤 CI 통과를 확인하고, 해소가 문서 표 행 외의 파일을 바꾸면 재검토를 받는다.
2. (권고) jobs.js:66, 68의 `class: 'sub warn'`에 대응하는 `.warn` 규칙이 app.css에 없다(`.warn-text`, `.notice.warn`만 있음). "취소 요청됨"·"60초 넘게 갱신 없음" 문구가 경고색 없이 보인다. 응답 없음은 줄 배경(`row-warn`)과 배지로 이미 구분되므로 기능 문제는 아니다. 기존 experiments.js:71도 같은 패턴이라 함께 `warn-text`로 바꾸는 것을 권한다.
3. (권고) `tick()`에서 `jobs.list()`가 예외를 내면(ExperimentJobStream.java:234 → 219-225) 그 주기의 keepalive도 건너뛴다. 작업 폴더를 계속 읽지 못하는 동안에는 떠난 연결이 정리되지 않아 상한 8개를 차지할 수 있다(30분 수명으로 결국 풀림). 읽기 실패 때도 keepalive 판정은 하도록 순서를 바꾸는 것을 권한다.
4. (참고) 409나 연결 닫힘 뒤 화면은 5초 조회로 바뀐 채 SSE로 돌아가지 않는다(jobs.js:833-842). 화면을 다시 열면 SSE를 다시 시도하므로 D-093 5번과 맞고, 모니터링 화면과 같은 정책이다.
5. (참고) 동시성: `register`(streams 잠금)와 `tick`(this 잠금)이 겹쳐도 새 연결은 ① 변경 분기의 `streams` 순회에 들어가거나 ② `fresh`에 남아 `register`가 예약한 즉시 tick에서 받으므로 첫 이벤트를 놓치지 않는다. 최악은 같은 스냅샷 중복 1회(무해). `closeStreams`는 `closing` 설정 후 새 등록을 막고, `stop()`과 `destroyMethod=close`가 둘 다 불러도 멱등이다.
6. (참고) 연결이 있으면 2초마다 상태 파일 최대 500개 × 16KB를 다시 읽는다. D-093 "되돌릴 조건"(MON-05 지표에 보이면 주기 늘림·3-B 재검토)에 적혀 있어 받아들인다.

## 판정
승인 (커밋 c01c67d 기준. 머지 전 발견 1의 main 충돌을 문서 표 행만으로 해소하고 CI 통과를 확인할 것. 해소가 코드 파일을 바꾸면 재검토. 발견 2·3은 권고)
