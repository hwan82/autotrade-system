# D-101 실험실 화면 브라우저 시험 CI 잡(5단계 QA 권고 R3)

- 날짜: 2026-10-16
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). CI에 새 잡 "Lab screens (Playwright)"을 더한다. 기존 잡(Gradle build, DB schema, Secret scan)은 바꾸지 않는다. `.github/workflows/`는 보호 경로라 이 결정의 PR은 실전 관련 PR로 다룬다(지침 11장: risk-ops·architect 검토, 독립 QA, 사용자 머지). 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 규칙과 보호 테스트는 바꾸지 않는다.
- 담당: ui-dev. 교차 검토: architect, risk-ops(보호 경로)
- 근거: [5단계 QA 판정](../reviews/gates/stage-5.md) 권고 R3("실험 생성·불러오기 화면은 Playwright 한 흐름을 CI에 두면 회귀를 잡는다"), [D-080](README.md) 후속 목록, [D-093](D-093-experiment-progress-screen-sse.md)·[D-098](D-098-walkforward-command-progress.md) 위험과 한계("화면 JS 자동 시험은 화면 Playwright CI 때"), D-026(빌드 없는 정적 화면이라 JS 단위 시험 도구가 없음)

## 문제

화면 JS(`autotrade-ui/src/main/resources/static/js`)는 자동 시험이 없다 `[사실]`(D-026, 5단계 QA C1 상세 4번). 서버 API는 `ExperimentCreationEndToEndTest` 등이 고정하지만, 화면이 API를 잘못 부르거나 진행 막대가 그려지지 않는 회귀는 사람이 브라우저를 열어야만 보인다. 5단계 판정은 QA가 손으로 돌린 headless Chromium 흐름이 근거였고, 워크포워드 전체 진행률 화면(D-098)은 브라우저로 확인하지 못한 채 머지됐다.

## 결정

1. **새 CI 잡 `ui-e2e`("Lab screens (Playwright)")**. 저장소 빌드(`bootJar`, 실험실 테스트 클래스, 실험 명령 클래스) 뒤 `autotrade-ui/e2e/`에서 `npm ci`와 `npx playwright test`를 돌린다. 실패하면 HTML 보고서와 trace를 7일 보관한다.
2. **실험실 둘을 띄운다**(Playwright `webServer`).
   - 흐름 실험실: 배포본 bootJar를 저장소 루트에서 띄운다(저장소의 `strategies/`·`selections/`·`experiments/`). 설정 폴더는 임시 폴더이고 합성 일봉 스냅샷 `krx-daily-e2e-0001` 하나만 둔다(`ExperimentCreationEndToEndTest`와 같은 2종목 80거래일, 거래일은 `krx.json`에서 계산).
   - 데모 실험실: `bootTestRun`(DemoLabApplication). 진행 상태 데모 작업이 5초마다 진행한다.
3. **시험 두 개**(파일 이름 순서로 하나씩).
   - `01-jobs-progress`: SSE "실시간 연결" 표시, 조합 작업 진행 막대 값이 다음 갱신에 커짐, 워크포워드 막대가 명령 전체 진행률이고 "실험 n/24번째"·"지금 실험 …" 줄이 나옴(D-098), 끝난 작업 펼치기·숨기기(완료·정지 요청으로 멈춤), 취소 확인 창 → "취소 요청됨" → 취소됨.
   - `02-experiment-flow`(R3): 새 실험 등록 화면에서 스윙 조합 2개(`kr-vol-surge-quiet@1 x kr-ma-trend@1`, `none x kr-ma-trend@1`) 고르기 → 미리 보기(파일 안 씀) → 등록 파일 쓰기 → 같은 ID 다시 쓰기 409 → **화면이 알려 준 실행 명령 글을 그대로** Gradle로 돌림 → 실험 폴더 "불러올 수 있음" → 끝난 작업에 이 실행이 "완료" → 불러오기 → 계좌 비교표 2행, 교차표, 자산 곡선.
   - 두 시험 모두 화면 예외(pageerror)와 콘솔 오류가 없어야 통과한다(시험이 일부러 만든 409 한 건만 허용).
4. **장 시간 보호(D-063)는 CI에서만 푼다.** 실행 명령에 `-PallowDuringSession`을 붙이는 것은 환경 변수 `CI`가 있을 때뿐이다. GitHub 러너에는 실전 엔진이 없다. PC에서 이 시험을 돌리면 풀지 않으므로 장 시간에는 실행 명령이 멈추고 시험이 실패한다(실전 엔진 자원 보호가 시험보다 먼저, 절대 규칙 6).
5. **시험이 저장소에 쓰는 것은 `experiments/EXP-20261016-E2E01/` 하나**다. 시험 앞뒤로 이 폴더와 임시 설정 폴더만 지운다.
6. **의존성**: `@playwright/test` 1.56.1(Apache-2.0, 무료) 하나를 `package-lock.json`(무결성 해시 포함)으로 고정한다. 실험실 배포본·화면에는 들어가지 않는다(테스트 도구). 브라우저는 CI에서 `npx playwright install --with-deps chromium`으로 받는다.

## 대안

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. Node Playwright 시험 + 새 CI 잡(채택) | QA가 손으로 한 흐름과 같은 도구. 화면 JS를 바꾸지 않음. 실패 trace가 남음 | Node 도구와 잠금 파일이 저장소에 생김. 잡 시간이 늘어남(빌드 + 브라우저 설치) | **채택** |
| B. Java Playwright(테스트 의존성으로 Gradle 빌드 안에) | 언어 하나 | 실험실 테스트 클래스패스에 큰 의존성이 들어가고, `./gradlew build`(Gradle build 잡)가 브라우저를 받아야 해서 기존 잡이 바뀜 | 버림(조건: 기존 잡 무변경) |
| C. 화면 JS를 모듈 단위 시험(jsdom 등) | 빠름 | 실제 서버·SSE·라우팅을 지나지 않아 R3가 말한 흐름을 잡지 못함 | 버림. 나중에 순수 함수(`isStale` 등) 시험에 보탤 수 있음 |
| D. 흐름 시험에서 실행 명령 대신 실행 본체를 직접 호출 | Gradle 두 번째 호출이 없어 빠름 | 화면이 알려 주는 명령 글이 틀려도 통과함 | 버림 |

되돌릴 조건: 잡이 자주 흔들려(같은 커밋 재실행에서 다른 결과) 리뷰를 막으면, 흔들리는 단계를 고친다. 고칠 수 없으면 그 단계를 지우지 않고 사용자 결정으로 옮긴다(지침 4장 5번).

## 위험과 한계

- 데모 작업은 실험실이 뜬 뒤 약 4분 반 뒤 끝난다. 진행 상태 시험을 먼저 돌리게 파일 이름에 순서를 붙였다. 빌드가 미리 끝나 있어야 하므로(CI 앞 단계) 데모 실험실은 뜨는 데 오래 걸리지 않는다 `[추론]`.
- 흐름 시험은 데모 실험실(Gradle `bootTestRun`)이 도는 동안 두 번째 Gradle 명령(실행 명령)을 같은 저장소에서 부른다. Gradle은 동시 빌드를 허용하지만 잠금 경합으로 느려질 수 있다 `[가정]`. 시험 시간 상한은 6분이다.
- 콘솔 오류 판정은 Chromium의 "status of NNN" 문구에 기대어 의도한 409만 허용한다. 문구가 바뀌면 거짓 실패가 난다(거짓 통과는 아님).
- 모니터링 화면 `isStale`(architect PR #115 A4, D-091), 실시간 모의 화면, 실전 운영 화면은 이번 범위가 아니다. 같은 틀에 시험 파일을 더하면 된다.
- Windows PC에서 돌리는 경로(`gradlew.bat`, `shell`)는 코드만 있고 이 PR에서 돌려 보지 않았다.

## 검증

- CI 잡 "Lab screens (Playwright)"이 이 PR에서 통과해야 한다(이 PR의 근거). 클라우드 작업 컨테이너는 Maven Central 429로 빌드할 수 없어 로컬 실행은 못 했다 `[사실]`(2026-10-16).
- 합성 스냅샷 거래일 계산: 2026-01-02부터 80거래일 = 2026-01-02 ~ 2026-04-29, 설 연휴(02-16~18)·삼일절 대체(03-02) 제외를 node로 확인했다.
