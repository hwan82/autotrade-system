# ADR-005 로컬 웹 대시보드 1차: 프런트 방식, 웹 보안, REST 계약, 전략 프로필 저장

- 날짜: 2026-10-08
- 상태: 채택 (D-026 확정, PR #13 머지 2026-10-09로 계약이 운영 코드가 됨. 이후 PR #49·#51·#82·#87과 D-089가 5장 REST 계약을 넓힘(D-060·D-062·D-074·D-076·D-089). 이전 상태: 제안, ui-dev 작성, architect·risk-ops 교차 검토). Q-006 닫음
- 요구사항: UI-01, UI-02, UI-03, UI-04, UI-07([기획서](../spec/autotrade-spec.md) 표), [D-020](../decisions/D-020-ui-web-dashboard.md), [D-026](../decisions/D-026-ui-frontend.md)
- 범위: 실험 비교 대시보드, 전략 프로필 편집, 설정 화면(비밀값 마스킹). **실전 운영 화면(UI-05·06: 모드 전환, 실전 활성화 2차 확인, PANIC SELL, 블랙리스트, 잔고)은 이 ADR 밖이다.** 실전 경로라서 별도 PR과 risk-ops 검토, 사용자 머지로 다룬다(지침 10장).

## 1. 프로세스와 모듈

| 항목 | 결정 |
|---|---|
| 웹 서버 위치 | 실험실 프로세스(`autotrade-app-lab`, Spring Boot, 내장 Tomcat). 실전 엔진·게이트웨이·감시에는 웹 의존이 없다(`verifyProcessIsolation`이 Spring·`:autotrade-ui` 유입을 막음) |
| 화면 위치 | `autotrade-ui` 모듈의 `src/main/resources/static/**`(HTML·CSS·ES 모듈). 실험실이 `runtimeOnly`로 의존해 클래스패스에서 서빙 |
| 주소 | `server.address=127.0.0.1`, 포트 `AUTOTRADE_UI_PORT`(기본 8480). 루프백이 아니거나 빈 주소면 기동 거부(`LoopbackBindingCheck`) |

UI 프로세스가 죽어도 실전 엔진에는 영향이 없다(UI-06). 실험실이 자원 부족으로 먼저 멈추면 대시보드도 함께 멈추는데, 이는 D-013 정지 순서와 맞다.

## 2. 프런트 방식 (Q-006)

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| **A. 빌드 없는 정적 HTML + ES 모듈(바닐라 JS), JSON REST** | Node·npm 빌드가 없어 CI·개인 PC 설치가 그대로, 외부 라이브러리 0개(공급망·CDN 위험 없음, 오프라인 동작), CSP를 `'self'`로 좁힐 수 있음 | 컴포넌트·상태 관리를 직접 작성, 화면이 커지면 코드 정리가 필요 | **채택** |
| B. 서버 렌더링(Thymeleaf) + htmx | 서버 코드 하나로 화면 생성, JS 적음 | 템플릿 엔진 의존 추가, REST 계약과 화면이 섞여 운영 화면(실전 엔진 스냅샷 조회)에서 같은 API를 다시 만들어야 함, htmx 파일 반입 필요 | 버림 |
| C. 경량 SPA(Preact·Vite 등) | 컴포넌트 구조, 생태계 | Node 빌드 체인과 npm 의존 추가(D-020 대안 C에서 버린 이유와 같음), CI 시간 증가 | 버림 |

되돌릴 조건: 화면 모듈이 커져(JS 3,000줄 이상 또는 화면 10개 이상) 직접 만든 DOM 도우미로 유지가 어려워지면 B나 C로 옮기는 ADR을 새로 쓴다. REST 계약은 그대로 두므로 화면만 바뀐다.

화면 규칙
- 서버·사용자 문자열은 항상 `textContent`로 넣는다(`innerHTML` 쓰지 않음, `js/dom.js`).
- 금액·비율은 서버가 BigDecimal 문자열로 준다. 화면은 표시만 하고 계산하지 않는다(차트 좌표 변환만 Number 사용).
- 이익은 빨강, 손실은 파랑(국내 관례). 통화 코드를 금액과 함께 표시. 미국 실험의 체결 시각은 ET와 KST 병기(UI-07).
- 인라인 `style` 속성은 CSP에 막히므로 색 등은 CSSOM으로 넣는다.

## 3. 웹 보안 (UI-01, D-020 3번)

`LocalOnlyRequestFilter`가 모든 요청에 적용된다.

| 위협 | 대응 | 테스트 |
|---|---|---|
| 같은 PC 밖에서 접속 | 127.0.0.1 바인딩, 비루프백 주소면 기동 거부 | `LocalOnlyRequestFilterTest`, CI 기동 단계 |
| DNS rebinding(외부 도메인이 127.0.0.1로 풀려 같은 출처처럼 접근) | Host 헤더가 `127.0.0.1`·`localhost`·`[::1]`(포트 선택)이 아니면 403. 조회도 막는다 | `WebSecurityIntegrationTest`, CI의 `Host: evil.example` 403 확인 |
| CSRF(다른 사이트가 폼·fetch로 변경 요청) | GET·HEAD·OPTIONS 외 요청은 `X-CSRF-Token` 필수(기동마다 SecureRandom 256비트, 상수 시간 비교). Origin이 있으면 Host와 같은 루프백 출처(포트 포함)여야 하고 `Sec-Fetch-Site: cross-site`는 거부 | 같은 테스트 |
| 토큰 탈취 | 토큰은 같은 출처 `GET /api/session`으로만 읽힌다(CORS 헤더 없음). Host 검사로 rebinding 페이지도 못 읽음 | |
| 클릭재킹·스니핑 | `Content-Security-Policy: default-src 'self' ...; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, API는 `Cache-Control: no-store` | 같은 테스트 |
| 오류로 값 누출 | 오류 응답은 `{code, message}`만. 프레임워크 예외 메시지·스택은 내보내지 않고(Boot `server.error.*` 끔), 로그에도 예외 종류와 위치만. 없는 경로·메서드·형식은 404·405·415(ERROR 로그 아님) | `SettingsControllerTest`, `ExperimentControllerTest` |

Spring Security를 쓰지 않은 이유: 인증 사용자가 없는 로컬 단일 사용자 도구라 필요한 것은 Host·Origin·CSRF 검사뿐이고, 필터 하나(약 150줄)로 동작을 테스트로 고정할 수 있다. 되돌릴 조건: 외부 접근이나 로그인(D-020 되돌릴 조건)이 생기면 Spring Security로 옮긴다.

## 4. 전략 프로필 저장 (UI-03)

선정 프로필(ADR-007 2.1)도 같은 규칙과 같은 파일 모양으로 `<selections-dir>/<선정ID>/v<버전>.yaml`(속성 `autotrade.lab.selections-dir`, 기본 `selections`)에 저장한다. 두 저장소는 버전 파일 읽기·쓰기를 한 구현(`VersionedProfileFiles`)으로 공유한다(D-060).

파일 배치: `<strategies-dir>/<프로필ID>/v<버전>.yaml`. `strategies-dir` 기본값은 실행 폴더 기준 `strategies`(bootRun은 저장소 루트에서 돈다), Spring 속성 `autotrade.lab.strategies-dir`로 바꾼다.

```yaml
# 전략 프로필(ADR-005 4장). 저장된 버전 파일은 고치지 않는다. 바꾸려면 새 버전을 만든다.
id: breakout
version: 2
market: KRX
style: SWING
based-on: breakout@1
created-at: 2026-10-08T01:02:03Z
note: 손절 완화
parameters:
  entry.signal: range-breakout
  exit.stop-loss-pct: "4"
```

규칙
- 버전 파일은 append-only다. 편집은 항상 새 버전(= 새 `profileHash`)을 만든다. 실험 결과가 해시로 프로필을 가리키므로 옛 버전을 고치면 추적이 깨진다.
- 새 버전은 "최신 버전을 기준으로 고친 것"만 받는다(`baseVersion` 불일치 → 409). 같은 버전 파일이 이미 있으면 덮어쓰지 않는다(`REPLACE_EXISTING` 없이 이동). 읽지 못하는 다음 버전 파일이 있으면 그 사실을 409 메시지로 알린다.
- 문자열은 항상 따옴표로 쓰고, 옮기기 전에 임시 파일을 다시 읽어 해시가 같은지 확인한다(`.inf`·`.nan`·`null` 같은 값이 숫자·null로 바뀌어 저장 후 못 읽는 일을 막음, architect 검토 1).
- 공백을 떼면 같아지는 파라미터 키는 거절한다.
- 버전 사이에 시장·스타일은 못 바꾼다. 바꾸려면 복제해 새 ID로 만든다(D-006 한 프로필 한 스타일).
- 파라미터는 평평한 `키: 문자열 값`이다(`StrategyProfile` 계약). 해시에 들어가지 않는 `based-on`·`created-at`·`note`는 이력 정보다.
- 읽지 못하는 파일은 목록에서 빼고 경로만 경고한다.

대안: (A) **ID별 폴더 + 버전별 불변 YAML 채택**, (B) `strategies/<ID>.yaml` 한 파일에 최신만 두고 이력은 git(실험이 가리키는 옛 해시를 UI에서 열 수 없고 사용자가 커밋을 관리해야 함, 버림), (C) DB `strategy_profile` 표(실험실 DB 저장이 아직 없음, 사람이 읽고 리뷰하기 어려움, 버림). DB 저장 작업에서 이 파일을 `strategy_profile`에 동기화하는 방식으로 잇는다.

**변경 제안(strategy-researcher)**: `autotrade-strategy-lab` 스킬 1장 3번의 `strategies/<프로필ID>.yaml`을 `strategies/<프로필ID>/v<버전>.yaml`로 바꾸고, 스킬이 말하는 묶음(`universe`, `selection`, `entry` …)은 점으로 이은 파라미터 키(`entry.signal`)로 쓴다. 스킬 파일은 이 PR에서 고치지 않는다.

## 5. REST 계약 (`/api`, JSON)

공통: 금액·비율은 문자열(BigDecimal plain), 비율은 소수(0.05 = 5%), 시각은 UTC ISO-8601, 날짜는 거래소 현지 `YYYY-MM-DD`. 목록(실험·프로필·체결·왕복·거절·실험 폴더)은 모두 `{items, page, size, total}` 페이징(`page` 0부터, `size` 1~200, 기본 50). 오류는 `{code, message}`(`INVALID` 400, `NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `UNSUPPORTED_MEDIA_TYPE` 415, `CONFLICT`·`CONFIG_FILE_PROBLEM`·`CONFIG_CHANGED_ON_DISK` 409, `PAID_CONFIRMATION_REQUIRED` 400, `HOST`·`ORIGIN`·`CSRF` 403, 알림 시험의 `TEST_RUNNING` 409·`TEST_TOO_SOON` 429). 실전 운영 API(`/api/real/*`, ADR-006 2.3)는 여기에 `UNKNOWN_ACCOUNT` 404, `CONFIRMATION_FAILED` 403, `LOCKED` 423, `PASSWORD_NOT_CONFIGURED`·`INVALID_STATE`·`STALE` 409, `BUSY`·`ENGINE_UNREACHABLE` 503, `INTERNAL` 502를 더한다).

| 메서드·경로 | 설명 |
|---|---|
| `GET /api/session` | 모드(`LAB`), `realOrderPath=false`, 증권사 키 설정 여부, 실전 계좌 별칭·계좌번호 설정 여부, CSRF 토큰 |
| `GET /api/experiments` | 실험 목록(실험 ID 문자열 내림차순. `EXP-YYYYMMDD-NN` 형식이면 최근 실험이 위): 시장·통화·기간·시드·시드머니·비용 모델·비용 표 해시·체결 모델·데이터 버전·실행 방식·코드·계좌 수 |
| `GET /api/experiments/{id}` | 요약 + 계좌별 행(프로필 ID·버전·해시, 상태, 실패 사유, 최종 평가금액, 지표 14개, 거절 건수). 조합(D-060): `selectionId`·`selectionVersion`(선정 없음이면 null), `selectionHash`(선정 없음이면 `"none"`), `combinationHash`, `selection`(선정 있는 계좌만: 결과 있던 날·없던 날과 사유별 일수, 막은 신규 진입 수, 쓴 결과 수, 정정 대체 규칙 적용 수, 본문 없는 결과 수) |
| `GET /api/experiments/{id}/equity` | 계좌별 일별 평가금액(비용 차감 후)과 시드머니. 계열마다 `selectionId`·`selectionVersion`(선정 없음이면 null, D-060) |
| `GET /api/experiments/{id}/runs/{runId}/summary` | 계좌 상세 머리 요약: 계좌·프로필·상태·실패 사유, 실험 기간·거래일 수·시드머니·통화, 마지막 평가금액·현금(자산 곡선 마지막 점)·총수익률, 누적 실현손익(매도 체결 `realizedPnl` 합, 비용 차감 후), 비용(수수료·세금·합계·슬리피지), 기간 끝(실패면 직전) 보유 종목(종목코드·종목명·수량·평균 단가·보유 원가), `symbolNameCount`, 조합(D-060) `selectionId`·`selectionVersion`(선정 없음이면 null)·`selectionHash`(선정 없음이면 `"none"`)·`combinationHash`. 실패한 Run은 곡선이 없으면 평가금액·수익률이 없다 |
| `GET /api/experiments/{id}/runs/{runId}/fills` | 체결 내역(종목명, 멱등 키, 체결 순번, 신호·체결 이벤트 순번과 시각, 비용 항목, 실현 손익) |
| `GET /api/experiments/{id}/runs/{runId}/round-trips` | 왕복 거래(종목명 포함) |
| `GET /api/experiments/{id}/runs/{runId}/rejections?stage=&code=` | 거절 사유 로그 + 거르기 전 사유별 건수(`counts`) |
| `GET /api/profiles?market=&style=` | 프로필 목록(ID 이름순, 최신 버전·해시, 버전 목록). `market`·`style`을 주면 그 시장·스타일만(D-074) |
| `GET /api/profiles/{id}` · `/versions/{v}` | 버전 이력 · 한 버전 |
| `POST /api/profiles` | 새 프로필 v1. `basedOn: "id@v"`면 복제 |
| `POST /api/profiles/{id}/versions` | `{baseVersion, parameters, note}` → 새 버전 |
| `GET /api/selections?market=&style=` · `/{id}` · `/{id}/versions/{v}` | 선정 프로필(ADR-007 2.1) 목록(`market`·`style` 거르기는 프로필 목록과 같음, D-074) · 버전 이력 · 한 버전. 한 버전은 `refresh`, `maxSymbols`, 선정 해시(`hash`), `catalogProblem`(지금 카탈로그로 해석되지 않는 이유, 통과면 없음)을 더 준다. D-060 |
| `POST /api/selections` · `/{id}/versions` | 전략 프로필과 같은 모양. 저장 전에 예약 키(`SelectionProfile`)와 카탈로그(`SelectionCriteria.parse`) 검사를 모두 통과해야 하고, 걸리면 400 `INVALID`(파일을 남기지 않음) |
| `POST /api/selections/{id}/versions/{v}/compare` | `{parameters}`로 저장하면 원본 `v`와 선정 결과가 같은 새 버전이 되는지(D-074): `identical`(글자까지 같음, 저장하면 거절됨), `sameSelection`, `differences`(선정 결과에 영향 없는 차이 문구), `problem`(해석 불가 이유, 없으면 null). 파일을 쓰지 않는다 |
| `GET /api/selection-catalog` | 조건 행 선택지: 지표(`key`, 입력 `kind`, 기간 `param`), 연산, 갱신 주기별 허용 스타일, 상품 유형. 순수 모듈 enum에서 읽는다 |
| `GET /api/experiments/{id}/cross-table?metric=&rowControl=` | 선정 × 매매지표 교차표(UI-02): 행(선정, "선정 없음" 행은 맨 아래), 열(전략 프로필 버전), 칸(Run ID, 조합 해시, 상태, 지표 값, `deltaVsNoSelection` 같은 열 선정 없음 칸 대비, `deltaVsRowControl` 같은 행 대조 열 대비). 차이는 서버가 `BigDecimal`로 뺀다. `metric`은 리더보드 지표 10개 중 하나(기본 `totalReturn`) |
| `GET /api/experiments/{id}/runs/{runId}/selection-days` | 날짜별 선정 기록(`run_selection_day`): 판정(`VALID`·사유), 결과 해시, 선정 종목 수, 막은 신규 진입 수, 결과 본문 유무, 정정 대체 규칙 적용 수 |
| `GET /api/experiments/{id}/selection-results/{hash}` | 선정 결과 하나(ADR-007 2.6 내용): 적용 범위, 입력 기준일, 평가·선정 종목 수, `restatementFallback`(MD-10 (2) 적용 수), 탈락 사유별 종목 수, 선정 종목(순위·종목명) |
| `GET /api/experiments/{id}/selection-results/{hash}/evaluations?filter=&reason=` | 평가 행(종목 코드순, 페이징): 통과·순위, 탈락 사유, 조건별 값, 정정 대체 규칙 표시. `filter`는 `all`·`passed`·`failed`·`fallback` |
| `GET /api/experiment-folders` | 조합 실험 폴더(`experiments-dir` 아래 `combinations:` 키가 있는 `experiment.yaml`, 실험 ID 내림차순, 페이징, D-062): `id`, `question`, `period`(`from ~ to`), `combinations`(조합 수), `state`(`LOADED` 화면에 있음·`READY` 불러올 수 있음·`NO_BUNDLE` 결과 묶음 없음·`NOT_RUN` 결과 파일 없음·`INVALID` 등록 파일 오류), `lastError`(마지막 불러오기 실패 사유나 등록 파일 오류, 없으면 null). `INVALID`면 `question`·`period`가 null |
| `POST /api/experiment-folders` | 새 실험 등록(UI-02, D-074): `{id, question, market, style, from, to, seed, seedCapital, barsSnapshot, financialsSnapshot, benchmark, parallelism, combinations[], blacklist[]}`로 `experiments/<id>/experiment.yaml`(조합 실험 등록 파일)을 새 폴더에 쓴다. 응답 201 `{id, yaml, command, written}`(`command`는 실행 명령). 실험을 돌리지 않는다. 입력이 틀리거나 조합의 시장·스타일이 실험과 다르거나(STR-02) 재무 조건 선정에 재무 스냅샷이 없거나 시장이 KRX가 아니면 400 `INVALID`(폴더를 만들지 않음), 같은 ID 폴더가 있으면 409 `CONFLICT`(덮어쓰지 않음), CSRF 토큰이 없으면 403 |
| `POST /api/experiment-folders/preview` | 등록과 같은 검사를 하고 쓰지 않는다. 응답 `{id, yaml, command, written=false}` |
| `GET /api/experiment-folders/snapshots?kind=` | 수집 스냅샷(`<설정 폴더>/snapshots/<kind>/<ID>/manifest.json`, `kind`는 `krx-daily`·`dart-financials`, ID 내림차순): `{root, items[{id, firstDate, lastDate, problem}]}`. 일봉 `lastDate`는 마지막 거래일, 재무는 마지막 수집 시각. 파일 해시는 실행 명령이 확인 |
| `POST /api/experiment-folders/{id}/load` | 결과 묶음(`result-bundle.jsonl.gz`)을 `detail.txt`·`selection-days.tsv`와 대조한 뒤 실험 목록에 올린다(실험을 다시 돌리지 않음, D-062). 응답 `{id}`. 실험 ID 형식이 아니거나 묶음이 깨졌거나 대조가 다르면 400 `INVALID`(사유는 목록 `lastError`에도 남음), 폴더·묶음·`detail.txt`가 없으면 404 `NOT_FOUND`, 이미 올라가 있으면 409 `CONFLICT`, CSRF 토큰이 없으면 403 `CSRF` |
| `GET /api/experiment-jobs` | 실험 실행 명령의 진행 상태(D-089, `<설정 폴더>/experiment-state/jobs/*.status`, 갱신 시각 내림차순, 페이징): `{items, page, size, total, unreadable, truncated}`. 항목 `{job, command, state, stale, pid, startedAt, updatedAt, maxParallelRuns, experimentsStarted, experimentsPlanned, commandPermille, experiment, phase, runsTotal, runsStarted, runsFinished, runsFailed, daysPerRun, daysDone, daysTotal, permille, message, cancelRequested}`. `state`는 `RUNNING`·`COMPLETED`·`NEEDS_REVIEW`·`INPUT_ERROR`·`CANCELLED`·`STOPPED`·`FAILED`, 실행 중인데 60초 넘게 갱신이 없으면 `UNRESPONSIVE`(`stale` true). 진행 필드는 첫 실험 시작 전 null. `experimentsPlanned`는 명령이 미리 알린 실험 수(모르면 0), `commandPermille`은 명령 전체 진행률(모르면 null, D-098). 읽지 못한 파일은 빼고 `unreadable`에 개수, 500개 넘으면 `truncated` |
| `GET /api/experiment-jobs/stream` | 진행 상태 SSE([D-093](../decisions/D-093-experiment-progress-screen-sse.md)). 이벤트 이름 `jobs`, 본문은 `GET /api/experiment-jobs?page=0&size=50`과 같은 모양 `{items, page, size, total, unreadable, truncated}`. 연결이 있을 때만 실험실이 2초마다 작업 폴더를 다시 읽어 첫 페이지가 바뀌면 보내고, 새 연결에는 바로 지금 상태를 보낸다. 바뀐 것이 없으면 15초마다 주석 줄(`:keepalive`). 연결은 8개까지, 수명 30분, 넘치거나 종료 중이면 본문 없는 409(모니터링 SSE와 같은 이유)이고 화면은 5초 주기 조회로 바꾼다. 실험실 종료 때 graceful shutdown보다 먼저 닫는다 |
| `GET /api/experiment-jobs/{id}` | 작업 하나(같은 항목). 실험 ID 형식이 아니면 400 `INVALID`, 상태 파일이 없으면 404 `NOT_FOUND` |
| `POST /api/experiment-jobs/{id}/cancel` | 취소 요청 파일(`<id>.cancel-request`)을 만든다. 실행 명령이 다음 Run 시작 전이나 거래일 마감에 보고 결과 없이 끝난다(종료 코드 3, `CANCELLED`). 응답 202와 작업 항목(`cancelRequested` true). 이미 요청돼 있어도 202. 실행 중이 아니거나 응답 없음이면 409 `CONFLICT`(파일을 만들지 않음), 없으면 404, CSRF 토큰이 없으면 403. 프로세스에 신호를 보내거나 종료하지 않는다 |
| `GET /api/live/status` | 실시간 모의 세션(D-076, [ADR-010](ADR-010-live-feed-and-session.md)) 상태 `View`: `experimentId`(돌거나 되살리는 중인 세션, 없으면 빈 문자열), `market`(그 세션의 시장 `KRX`·`US`, 없으면 빈 문자열. 화면은 이 시장의 현지 시간으로 시각을 보인다), `restoring`(입력 기록으로 계좌를 되살리는 중, 아직 게이트웨이에 붙지 않음), `session`(되살리기가 끝난 세션의 상태, 없으면 null: 실험 ID, 기간, 받은 이벤트·되살린 이벤트·뺀 종목·기간 밖·공백 수, 마지막 수신 시각, 계좌별 현금·평가액·포지션·체결·거절, 계좌별 실패 사유), `feedState`(게이트웨이 전달 연결), `feedError`, `feedReconnects`, `inboxQueued`, `inboxDropped`, `stopReason`(감시 정지 요청·디스크 부족·기록 실패·`시작 실패(예외 종류)`·`시작 실패: <정지 사유>`·`끝냈지만 보고서를 쓰지 못했습니다…` 등, 절대 경로는 담지 않는다), `stopKind`(`NONE` 멈춘 적 없음, `NORMAL` 사용자가 끝냄·멈춤·실험실 종료, `PROBLEM` 문제로 멈춤. 화면은 문구가 아니라 이것으로 경고 색을 고른다), `profiles`(계좌 순서대로 전략 프로필 `id@버전`, `session.runs`의 같은 자리 계좌, 세션이 없으면 빈 목록). 실시간 모의 화면(`#/live`)이 3초마다 읽는다 |
| `GET /api/live/registrations` | 실시간 모의 등록 파일(`experiments-dir` 아래 `input: live`인 `experiment.yaml`, 실험 ID 내림차순): `id`, `question`, `period`(`from ~ to`), `profiles`(`id@버전`), `symbols`(boards 종목 수), `hasJournal`(입력 기록 있음: 시작하면 되살린 뒤 이어 받음), `finished`(`live-detail.txt` 있음), `error`(등록 파일 오류, 정상이면 null이고 있으면 `question`·`period`가 null). 다른 입력의 등록 파일은 빼고, 읽을 수 없는 파일도 뺀다(시작하면 같은 사유로 409). 실험 폴더 목록을 못 읽으면 409 `CONFLICT` |
| `GET /api/live/gaps?limit=` | 도는 세션이 계좌에 준 공백(되살린 기록 포함) 중 최근 `limit`개(1~200, 기본 100), 최근 것부터: `{total, items[{symbol, reason, gapStart, gapEnd, receivedAt}]}`. `reason`은 `DataGap.GapReason`. 세션이 없거나 되살리는 중이면 `{0, []}`, `limit`이 범위 밖이면 400 `INVALID` |
| `GET /api/live/runs/{runId}/fills?page=&size=` | 도는 세션의 계좌 하나의 체결 내역([D-120](../decisions/D-120-live-account-fills-view.md)), **최근 것부터**, 페이징 `{items, page, size, total}`. 행 모양은 `GET /api/experiments/{id}/runs/{runId}/fills`와 같다. 세션 메모리의 원장 사본만 읽고(되살린 기록으로 다시 만든 체결 포함, 실패로 멈춘 계좌는 멈추기 전까지) 결과·기록에 쓰지 않는다. 세션이 없거나 되살리는 중이거나 그 세션에 없는 계좌면 404 `NOT_FOUND`, `page`가 음수이거나 `size`가 1~200 밖이면 400 `INVALID`. 세션이 도는 중 새 체결이 들어오면 쪽 경계가 한 칸씩 밀려 쪽을 넘길 때 같은 행이 다시 보이거나 하나를 건너뛸 수 있다(화면은 자동으로 다시 읽지 않고 "새로 읽기"로 처음 쪽부터 읽음, 커서 방식은 필요해지면 후속) |
| `GET /api/live/runs/{runId}/rejections?stage=&code=&page=&size=` | 같은 계좌의 거절·취소 기록(D-120), 최근 것부터: `{page{items, page, size, total}, counts[{stage, code, count}]}`. 행·`counts` 모양은 `GET /api/experiments/{id}/runs/{runId}/rejections`와 같고 `counts`는 거르기 전 전체다. 404·400 조건과 쪽 밀림은 위 `fills`와 같다 |
| `POST /api/live/sessions/{id}/start` | `experiments/<id>/experiment.yaml`(`input: "live"`)을 검사하고 되살리기를 시작한다. 응답은 위와 같은 `View`(`restoring=true`). 되살리기와 게이트웨이 연결은 뒤에서 하고, 실패하면 상태 `stopReason`에 남는다. 이미 도는(되살리는 중 포함) 세션이 있거나, 앞서 멈춘 세션을 정리하는 중이거나, 감시 정지 요청 중이거나, 입력 기록 디스크 여유가 하한 미만이거나, 등록 파일을 읽을 수 없으면(다른 프로그램이 잡음·권한·UTF-8 아님) 409 `CONFLICT`. ID 형식·등록 파일 형식 오류면 400 `INVALID`, CSRF 토큰이 없으면 403 |
| `POST /api/live/stop` | 세션을 멈춘다(되살리는 중이면 그만둠, 기록을 닫음, 다시 시작 가능). 응답 `View`. 도는 세션이 없으면 아무것도 하지 않는다 |
| `POST /api/live/finish` | 세션을 끝내고 `experiments/<id>/live-detail.txt`를 쓴다. 응답 `{experimentId, detailFile, accounts, failedAccounts}`. 도는 세션이 없거나 아직 되살리는 중이면 409 `CONFLICT`. 마감과 입력 기록 닫기는 끝났는데 보고서 파일만 못 쓰면 500 `REPORT_NOT_WRITTEN`(메시지에 저장소 상대 경로와 다시 할 방법, 상태 `stopReason`에도 남음, 기록이 정상 종료로 닫혔으므로 다시 시작하면 되살린 뒤 끝내기를 다시 할 수 있음). 마감이나 기록 닫기 자체가 실패하면 공통 500 `INTERNAL`(메시지 없음, 상태 `stopReason`에 `끝내기 실패(예외 종류)`) |
| `GET /api/monitor` | 모니터링(MON-05, [D-090](../decisions/D-090-monitor-agent-and-screen.md)) 상태 `View`: `enabled`(꺼졌거나 설정이 잘못됐거나 수집원을 만들지 못했으면 false이고 나머지는 빈 값), `problem`(설정 오류 문구나 수집원을 만들지 못한 이유, 아니면 null. 이때도 실험실은 뜬다), `intervalSeconds`, `historySize`, `startedAt`(수집 시작 시각, 꺼졌으면 null), `stale`(마지막 표본, 첫 표본 전이면 `startedAt`이 수집 주기의 3배보다 오래됨 = 수집 멈춤, D-091. 화면도 1초마다 같은 기준으로 다시 잰다), `latest`(첫 수집 전이면 null: `at`, `readings[{name, metric, target, value, unit, level}]`(이름순, `level`은 임계가 있는 지표만 `OK`·`WARN`·`CRITICAL`, 없으면 null), `failedSources`), `thresholds[{metric, direction, warn, critical, sustain}]`, `history[{at, values{지표[:대상] → 값}}]`(오래된 것부터 `historySize`개까지), `events[{at, reading, from, to, value, limit, unit}]`(최근 200건, 오래된 것부터). 지표 값은 비율·크기 같은 자원 측정값이라 금액·비율 문자열 규칙의 예외로 숫자(소수 둘째 자리 반올림)로 준다. 조회만 한다 |
| `GET /api/monitor/stream` | 새 표본 SSE. 이벤트 이름 `sample`, 본문 `{latest, point, events}`(`latest`·`events`는 위와 같은 모양, `point`는 `history` 한 칸). 연결은 동시에 8개까지, 한 연결의 수명은 30분(끝나면 브라우저 `EventSource`가 다시 붙음). 꺼져 있거나 8개가 이미 열려 있으면 **본문 없는 409**다: 공통 오류 형식 `{code, message}`의 예외로, 요청이 `Accept: text/event-stream`이라 JSON 오류 본문을 쓸 수 없고 `EventSource`는 응답 본문을 읽지 않는다. 409를 받으면 화면은 `GET /api/monitor` 주기 조회로 바꾼다. 보내기는 전용 스레드 하나이고 밀린 표본이 2개를 넘으면 가장 오래된 것을 버리므로 화면 그래프에 빈 칸이 생길 수 있다(architect PR #114 A5). 실험실 종료 때는 웹 서버 graceful shutdown보다 먼저 연결을 닫는다 |
| `GET /api/settings` | 그룹별 키: 현재 값, 내장 default 값, 비밀값 여부·설정 여부, 편집 가능 여부, 반영 시점 |
| `PUT /api/settings` | `{values, clearSecrets, acknowledgePaid}` |
| `POST /api/settings/notification-test` | 알림 시험 보내기([D-103](../decisions/D-103-settings-notification-test-send.md)). 본문 없음, 실험실 메모리의 저장된 `notification.*`로 켜진 Slack·카카오톡에 시험 알림 한 건씩 따로 보낸다(대체 발송 안 함, 로그 채널 제외). 응답 `{sentAt, channels[{channel, label, status, detail, message}]}`: `channel`은 `slack`·`kakao` 순서, `status`는 `SENT`·`FAILED`·`SKIPPED`, `detail`은 짧은 사유 코드(`ok`, `disabled`, `invalid-webhook-url`, `paid-mode`, `missing-values`, `invalid-values`, `http-<상태>`, `timeout`, `io-error`, `kakao-refresh-*`, `kakao-reconsent-required`, `kakao-result-code`, `interrupted` 등, 비밀값 없음), `message`는 화면용 한국어 설명. 보내는 중이면 409 `TEST_RUNNING`, 끝난 뒤 10초 안이면 429 `TEST_TOO_SOON`, CSRF 토큰이 없으면 403. 최대 약 40초 걸린다(HTTP 기한 10초 × Slack 1번·카카오 3번) |

같은 실험의 계좌만 한 화면에서 비교한다(같은 기간·시드·비용 모델·체결 모델, 지침 5장). 실험 사이 비교 화면은 만들지 않았다.

체결·왕복·거절 행과 보유 종목 행의 `symbolName`은 설정 폴더 `symbols/<MARKET>.csv`(표시용 이름표, 없으면 필드 생략)에서 붙인다. 이름은 계산·식별에 쓰지 않는다. 요약을 기존 상세 응답에 넣지 않고 별도 경로로 둔 이유: 상세는 실험 안 모든 계좌를 한 번에 주는데 보유 종목 계산은 계좌마다 체결 원장을 다시 반영해야 하므로, 계좌 화면이 열릴 때 그 계좌 것만 계산한다(기존 응답 필드는 바뀌지 않음). 보유 종목은 `VirtualAccount`에 원장을 같은 순서로 다시 반영해 얻는다(평균 원가 규칙을 따로 구현하지 않음).

### 실험 결과 출처
`ExperimentCatalog` 인터페이스로 읽는다. 조합 실험은 `register(spec, report, selectionResults)`로 실험이 쓴 선정 결과 본문(실험실은 `CalculatedSelectionSource.results()`)을 함께 넘기고, 카탈로그는 내용 해시로 찾는다. 실험 조합에 없는 선정의 결과는 거절한다. 본문 없이 등록하면 날짜별 기록만 보이고 본문 조회는 404다(D-060). 메서드는 화면 질의 단위(목록 한 쪽, 상세, 자산 곡선, 체결·왕복·거절 한 쪽)라서 DB 구현은 메서드마다 질의 하나로 옮기면 되고 실험 전체를 메모리로 되살릴 필요가 없다(architect 검토 3). 지금 구현은 `InMemoryExperimentCatalog`(프로세스 메모리)라서 실험실을 다시 띄우면 비어 있다. DB 구현으로 바꿔도 REST 응답은 바뀌지 않는다. 조합 실험 결과는 실행 명령이 쓴 결과 묶음(`experiments/<실험ID>/result-bundle.jsonl.gz`)을 "실험 폴더"에서 불러와 `detail.txt`와 대조한 뒤 등록한다(`ExperimentFolders`, `/api/experiment-folders`, D-062). 실험실은 실험을 다시 돌리지 않는다.

## 6. 설정 화면 (UI-04)

| 키 | 화면 |
|---|---|
| `lab.*` | 편집 가능(실시간 변경 "즉시" 키). `RuntimeConfigHolder.update`로 디스크 먼저, 성공하면 메모리 |
| `notification.*` | 편집 가능. 저장은 `lab.*`와 같지만 반영은 "실전 엔진·감시 재기동"(두 프로세스가 기동 때 읽음, [D-100](../decisions/D-100-notification-wiring-watchdog-engine.md)). `notification.kakao.link-url`은 카카오 앱 등록 도메인과 맞춰야 해서 화면에서 바꾸지 않고 `runtime-config.json`에서 고친다. 카카오 ME를 켜거나 카카오 값을 고칠 때 `rest-api-key`·`refresh-token`·`link-url`이 모자라면 거부한다(파일은 받아들이고 엔진·감시가 카카오만 끔) |
| `notification.slack.webhook-url`, `notification.kakao.{access-token,rest-api-key,refresh-token,client-secret}` 🔒 | 쓰기 전용 입력(`type=password`). 빈 입력은 그대로 두기, 지우기는 `clearSecrets`로 명시. 응답에는 `****`와 설정 여부만 |
| `notification.kakao.mode=BIZ` | 유료라서 BIZ로 바꾸거나 BIZ 상태에서 켤 때 `acknowledgePaid=true`가 필요(서버가 판단, 화면은 확인 대화상자 후 재전송, 지침 8장 1번) |
| 알림 시험 보내기 | "알림" 묶음 아래 버튼([D-103](../decisions/D-103-settings-notification-test-send.md)). 누를 때만 저장된 값으로 보낸다(입력만 하고 저장하지 않은 값은 쓰지 않음). 채널별 결과 표(채널, 결과, 설명). 카카오 갱신 토큰은 엔진·감시와 같은 `notification-state/kakao-token.json`에 남긴다 |
| `market-data.*` | 읽기 전용(재기동 필요) |
| `brokers.*` 🔒 | 설정 여부만, 읽기 전용(재연결 절차 구현 전, `RuntimeConfigHolder`도 막음) |
| `real-trading.accounts`, `account-numbers.*` 🔒 | 읽기 전용. 실전 활성화는 설정이 아니며 이 화면에 없다 |

비밀값은 일부 글자(끝 4자리 등)도 보여 주지 않는다. 로그에는 바뀐 키 이름만 남긴다.

**저장 안전장치(risk-ops 검토 B1)**: `RuntimeConfigStore.save`는 메모리 스냅샷 전체를 두 파일에 다시 쓴다. 그래서 화면에서 `lab.*` 하나만 바꿔도 `real-trading.*`·`brokers.*`가 함께 쓰인다. 메모리가 디스크와 다르면 실전 계좌·증권사 키·계좌번호를 옛 값(또는 빈 값)으로 덮어써, 실전 엔진이 재기동할 때 보유 계좌를 잃을 수 있다. 이를 막으려고 저장 전에(Holder의 직렬화 안에서) 다음을 확인하고 하나라도 걸리면 409로 거절하며 파일을 건드리지 않는다.
- 기동할 때 설정 파일 경고가 있었다(파일을 버림, 고아 계좌번호를 버림 등) → `CONFIG_FILE_PROBLEM`
- 지금 디스크를 다시 읽은 결과에 경고가 있다 → `CONFIG_FILE_PROBLEM`
- 지금 디스크를 다시 읽은 설정이 메모리와 다르다(실행 중 파일을 직접 고침) → `CONFIG_CHANGED_ON_DISK`

검사와 저장 사이에 다른 프로세스가 파일을 바꾸는 아주 짧은 경쟁은 남는다 `[추론]`. 근본 해결은 "바뀐 키만 파일 트리에 반영"하는 저장 방식이며, `autotrade-config`(architect 소유) 변경이라 후속 과제로 둔다.

## 7. 검증

- `./gradlew build` 통과. 실험실 테스트 64개(새 테스트 7개 클래스: 보안 필터 단위·통합, 실험 API 필드 계약·페이징, 프로필 저장소·API, 설정 API 비밀값 누출 검사, 설정 저장 안전장치).
- 교차 검토: architect(차단 0, 중요 3건 모두 반영), risk-ops(차단 1건 B1 반영, 사소 S1·S2·S4 반영, S3는 후속). 반영 후 판정은 PR에 적는다.
- 비밀값 누출 검사: 조회·변경·오류 응답 본문과 테스트 중 표준 출력(로그)에 자리표시자 비밀값 문자열이 없음을 확인(`SettingsControllerTest`).
- 화면: `./gradlew :autotrade-app-lab:bootTestRun`(테스트 소스의 샘플 데이터, 배포본에는 없음)으로 띄워 Chromium에서 각 화면 열기, 프로필 새 버전 저장, 설정 저장(저장 후 DOM에 비밀값 없음), 거절 사유 필터, 390px 폭 화면을 확인했다. 콘솔 오류 0건.
- 검증하지 못한 것: 실제 실험 실행기 출력으로 채운 화면(실험 생성·실행이 아직 없음), Windows에서의 실행, 대량 이력(수만 건) 페이징 응답 시간(UI-08 `[가정]` 기준 미정).

## 8. 위험과 한계

- 실험 목록은 메모리뿐이라 재기동하면 사라진다. DB 저장 전까지는 결과 요약 파일(`experiments/<ID>/`)이 원본이다.
- 같은 PC의 다른 프로그램(악성 코드 포함)은 루프백으로 접근할 수 있다 `[추론]`. 토큰은 같은 출처에서 읽히므로 로컬 악성 프로그램은 막지 못한다. 실전 제어(UI-05)에는 2차 확인과 명령 채널 검증을 따로 둔다(risk-ops).
- `strategies/` 폴더를 사용자가 직접 고치면 append-only가 깨질 수 있다. 저장소에 커밋된 버전 파일이 바뀌면 리뷰에서 막는다(파일 머리 주석).
- 실험 생성 화면(D-074)은 조합 실험 등록 파일을 쓰고 실행 명령을 보여 주는 데까지다. 실행은 사용자가 등록 파일을 커밋한 뒤 터미널에서 하고(D-062·D-063), 진행 상태·취소는 화면에 없다(실행기 진행 보고·취소, ADR-004 4장).

## 9. 후속 작업

| 항목 | 담당 | 조건 |
|---|---|---|
| 실험 생성 화면 | ui-dev | D-074로 처리(등록 파일 쓰기, 실행은 실행 명령) |
| 화면 실행(커밋된 등록 파일만) | ui-dev, sim-engine | 결과 DB 저장 후(D-074 대안 B 되돌릴 조건). 실행기 진행 보고·취소와 REST `/api/experiment-jobs`는 D-089, 진행 상태 화면·SSE·취소 버튼은 D-093으로 끝남 |
| `ExperimentCatalog` DB 구현, `strategy_profile` 동기화 | sim-engine, architect | 실험 DB 저장 작업 |
| 실전 운영 화면(모드 표시, 실전 활성화 2차 확인, PANIC SELL, 블랙리스트, 잔고·거래 내역, 증권사 연결 상태) | ui-dev, risk-ops | 별도 PR, 사용자 머지(지침 10장) |
| 증권사 키 변경 화면(재연결 절차) | ui-dev, risk-ops | 재연결 절차 구현 후 |
| 실험 간 비교(같은 기간·시드·비용 해시일 때만) | ui-dev, strategy-researcher | 4단계 전략 비교 시작 시 |
| 지표 보강 표시(관측 일수, 판정 불가 표시, 최장 회복 기간 등) | ui-dev | ADR-004 4장 지표 보강 후 |
| `RuntimeConfigStore`에 "바뀐 키만 반영" 저장 추가, 실험실이 증권사 키·계좌번호 평문을 들지 않게(설정 여부만) | architect, risk-ops | 7단계 전(risk-ops 검토 B1·S3) |
| 실전 계좌가 있을 때 마지막 알림 채널을 끄면 경고, 감시 프로세스가 "긴급 채널 없음"을 이상으로 보고(MON-06) | ui-dev, risk-ops | 7단계 전 |
