# architect 검토: 실시간 모의 화면: 폴링이 같은 내용을 다시 그리지 않음(이어서 시작 클릭·스크롤)
- 대상: PR #249 / 커밋 f04deedb5c728975840067a305dd992c477f53ac / `git diff origin/main...HEAD`
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 2개 파일만 바뀜. `autotrade-ui/src/main/resources/static/js/pages/live.js`(+11/-1), `autotrade-ui/e2e/tests/04-live-selections.spec.mjs`(+26). 브랜치의 나머지 커밋(fe419015~08adb6d9)은 이미 main에 squash된 PR #231 내용이라 diff에 나오지 않는다.
  - `./gradlew build --no-daemon`: BUILD SUCCESSFUL in 4m 4s(종료 코드 0).
  - 화면 Playwright 시험은 로컬에서 돌리지 않았다. 검토 시각이 KST 2026-10-27 08:51로 장 시작 전 60분 보호 구간이라 `autotrade-ui/e2e/support/guard.mjs`가 막는다. 대신 같은 커밋 f04deedb의 CI 결과를 근거로 쓴다: "Lab screens (Playwright)" 잡(check run 121919758547) success, 단계 "Lab screen tests" success. `playwright.config.mjs:41` `retries: 0`이라 재시도로 통과한 것이 아니고, 새 시험에 `test.skip`/`test.only`/`test.fixme`가 없다. 잡 로그 본문은 이 환경에서 내려받지 못해(로그 저장소 주소가 프록시에서 막힘) 시험별 줄은 보지 못했다.
  - 같은 커밋의 CI "Gradle build", "DB schema (PostgreSQL 16)", "Secret scan and collector boundary" 모두 success.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 로컬 `./gradlew build` BUILD SUCCESSFUL(종료 코드 0), CI "Gradle build" success |
| B2 | 해당 없음 | Java 모듈·의존·ArchUnit 규칙 변경 없음. 정적 화면 JS와 e2e 시험만 바뀜 |
| B3 | 해당 없음 | REST API·스키마·설정 키 변경 없음. 화면은 기존 `/api/live/status`, `/api/live/registrations`, `/api/live/gaps`만 읽는다(live.js:46-51), 서버 `LiveSessionController`·`LiveLabService.View` 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 엔진·전략 코드 변경 없음. 화면 표시 갱신 조건만 바뀜 |
| B5 | 해당 없음 | 금액 계산 없음. 비교 키는 서버 응답을 `JSON.stringify`한 문자열(live.js:55) |
| B6 | 통과 | 새 시험 `04-live-selections.spec.mjs` "세션이 없을 때 3초 폴링이 같은 등록 목록을 다시 그리지 않아 이어서 시작 버튼과 스크롤이 그대로다": 12행으로 스크롤을 만들고 마지막 "이어서 시작" 버튼에 표시를 단 뒤, 폴링이 두 번 이상 돈 것을 `statusReads`로 확인하고 같은 요소가 남았는지(`data-e2e-mark`)와 `scrollY`가 그대로인지 단언. [추론] 고치기 전 코드(폴링마다 `host.replaceChildren`)라면 버튼이 새 요소로 바뀌어 표시가 사라지므로 이 시험은 실패한다. CI Playwright 잡 success(retries 0). 지우거나 건너뛴 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응 변경 없음. 바뀐 두 경로는 `scripts/ci/protected-paths.txt`, `.github/CODEOWNERS`에 없다. 화면은 모의 계좌만 다룸(live.js:1-2) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 시험 픽스처는 가짜 실험 ID(`EXP-20261026-E2EIDLE00` 등)만 씀. CI gitleaks("Secret scan and collector boundary") success |

## 점검표 밖 발견
변경 자체는 맞다. `shown` 키로 같은 응답이면 다시 그리지 않고(live.js:55-57), 명령 뒤(live.js:75)와 "새로 읽기"(live.js:90)에서는 키를 비워 막았던 버튼을 반드시 다시 그려 푼다. `busy` 중에는 폴링이 아예 돌지 않으므로(live.js:82) 명령 중 버튼이 풀리는 새 경로는 생기지 않았다. 아래는 머지를 막지 않는 후속 과제다.

- n1(후속): 세션이 도는 동안(`sessionView`)에는 `view.session`의 처리 건수, `inboxQueued`, 공백 목록 같은 값이 장중에는 거의 매 폴링 바뀌므로 지금도 3초마다 다시 그린다. 그래서 "멈춤"·"끝내기"·"선정 준비" 버튼(live.js:144, 151, 249)은 누르는 도중 요소가 바뀌어 클릭이 사라질 수 있는 같은 종류의 문제가 남는다. 이번 PR은 보고된 증상(세션 없을 때 "이어서 시작")만 고치고 스크롤 위치 보존(live.js:59-61)은 두 화면 모두에 적용된다. 버튼 영역을 값 표시와 분리해 다시 그리지 않거나, 값 노드만 갱신하는 방식을 후속으로 검토할 것.
- n2(경미): 세션이 없는 화면에서 세션 화면으로 넘어갈 때(시작 직후)도 `window.scrollTo(0, y)`로 이전 스크롤 위치를 지킨다. 화면 종류가 바뀌면 맨 위로 보내는 편이 자연스러울 수 있다. 동작 오류는 아니다.
- n3(기존 문제, 이번 변경으로 악화되지 않음): 폴링 `refresh(false)`가 이미 나가 있는 동안 명령이 시작되면, 늦게 돌아온 폴링 응답이 키가 다를 경우 명령 중인데 버튼을 다시 그려 풀 수 있다. 고치기 전에는 항상 다시 그렸으므로 이번 변경으로 오히려 빈도가 줄었다. 요청 순번으로 늦은 응답을 버리는 방식을 후속으로 고려할 것.

## 판정
승인
