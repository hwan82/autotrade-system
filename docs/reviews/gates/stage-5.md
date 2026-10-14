# 독립 QA 검토: 5단계(UI) 완료 판정
- 대상: main `4e32d75`(PR #84 머지 뒤, 2026-10-14) / 5단계 산출물 전체: PR #13(UI 1차), #18(실전 운영 화면, ADR-006), #33(종목명·계좌 요약), #49(선정 프로필 편집·교차표, D-060), #51(조합 실험 결과 불러오기, D-062), #82(실험 생성 화면, D-074)
- 점검표: C 전체 + 과금 검사(QA 지침 8장)
- 요청: `docs/qa/pending.md` 2026-10-14 "main 기준으로 5단계 완료 판정해줘"(개발 쪽 대조표 `docs/qa/stage-5-readiness.md`는 확인할 주장으로만 썼다)
- 검토자: 독립 QA 프로젝트 "자동매매 시스템 QA", 2026-10-14
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(7회째. 1~6회는 Maven Central 429 Too Many Requests로 의존성 받기 실패, 코드 실패 아님). 같은 컨테이너의 앞선 빌드 결과를 재사용한 작업이 있어 `./gradlew build --rerun-tasks --no-daemon`을 한 번 더 돌림 → 종료 코드 0. 테스트 결과 XML 합계 **1,021건, 실패 0·오류 0·건너뜀 0**(app-lab 140, sim 188, marketdata 114, broker-toss 90, …). 5단계 관련: `ExperimentCreationEndToEndTest` 3, `SelectionEquivalenceTest` 6, `ExperimentControllerTest` 12, `CombinationViewsTest` 6, `ExperimentFolderControllerTest` 2, `RealOpsControllerTest` 11, `SettingsControllerTest` 8, `WebSecurityIntegrationTest` 5, 보호 테스트 `ArchitectureTest` 18·`RealTradingGuardTest` 31·`GuardedOrderGatewayTest` 8 모두 통과.
  - **실제 실험실 화면으로 완료 기준 재현**(아래 "C1 상세"): `AUTOTRADE_CONFIG_DIR=<임시 폴더> ./gradlew :autotrade-app-lab:bootRun`으로 실험실을 띄우고 headless Chromium(Playwright)으로 "새 실험 등록" 화면을 조작 → 터미널에서 화면이 알려 준 실행 명령 실행 → 화면 "실험 폴더"에서 불러오기 → 실험 화면 비교. 입력 일봉 스냅샷은 QA가 만든 합성 데이터(4종목 120거래일, `krx-daily-qa-0001`)다. 끝난 뒤 실험 폴더와 임시 설정 폴더는 지웠고 `git status` 깨끗함.
  - QA 탐침(저장소에 넣지 않음): 실행 중인 실험실에 등록·미리 보기 API로 이상 입력 10종, 다른 Origin, 실전 활성화·전체 PANIC, 설정 화면의 실전 키 저장을 직접 보냄(아래 "위험 시나리오").
  - 저장소 검색과 대조: 결정 로그, 기획서 10·14장, ADR-005·ADR-006, `docs/adr/README.md`, 검토 원문(`docs/reviews/`), GitHub PR #13·#18·#82 본문과 커밋 목록.
  - 하지 않은 것: PostgreSQL 스키마 스모크(5단계 PR은 `db/migration`을 바꾸지 않음, `git log -- autotrade-app-lab/src/main/resources/db/migration` 확인), Windows에서 화면·실행 명령 실행.

## 5단계 완료 기준
`docs/team/team-design.md:155` — 목표 "실험 생성·비교 대시보드, 설정 UI, 조회 화면", 완료 기준 **"실험을 UI로 만들고 비교 가능"**. 역할 지침 완료 기준(`docs/team/role-guides.md` 7장): 실전 관련 버튼은 확인 절차 없이 동작하지 않음, 같은 실험의 계좌들을 한 화면에서 비교, 비밀값은 화면·네트워크 응답·로그에 평문 없음. 기획서 진행표(`docs/spec/autotrade-spec.md:329`)는 5단계 관련 요구사항을 **UI-01~08, MON-05**로 적는다.

| # | 결과 | 근거 |
|---|---|---|
| C1 | 통과(조건 있음) | team-design 완료 기준과 role-guides 완료 기준 세 가지는 실제 화면·실행 명령·테스트로 확인했다(아래 "C1 상세"). 조건: "UI로 만들고"를 "화면은 등록 파일만 쓰고 실행은 터미널 명령"으로 해석한 것은 자율 결정 D-074이고, 기획서가 5단계에 묶은 UI-01~08·MON-05 중 구현되지 않은 항목(대조표 3장 5건 + 대조표에 없는 UI-01 SSE)을 이 단계 판정 범위에서 뺄지는 **사용자 결정 전**이다. 아래 "사용자 결정" 참고 |
| C2 | 통과 | 아래 "C2 검토 원문" |
| C3 | 통과 | 아래 "C3 자율 결정 목록" |
| C4 | 통과 | 아래 "C4 다음 단계를 막는 미확정" |
| C5 | **실패** | 표본 7곳 대조, 4곳 불일치. 아래 "C5 대조" |

### C1 상세
1. [사실] **화면으로 실험 만들기**(실제 실험실, Chromium). `#/experiments/new`에서 실험 ID `EXP-20261014-QA5A`, 질문, 기간 2026-01-02~2026-06-30, 시드 7, 시드머니 10,000,000, 일봉 스냅샷(목록에서 선택), 조합 3개(`kr-vol-surge-quiet@1 x kr-ma-trend@1`, `none x kr-ma-trend@1`, `kr-vol-surge-quiet@1 x kr-random-daily-swing@1`)를 골라 "미리 보기" → "등록 파일 쓰기". `experiments/EXP-20261014-QA5A/experiment.yaml`이 생기고 화면이 커밋 명령과 실행 명령(`./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=EXP-20261014-QA5A -PverifyDeterminism`)을 보여 줬다. 같은 ID로 다시 쓰면 "같은 실험 ID 폴더가 이미 있습니다…"(409, 덮어쓰지 않음). 스타일을 중기로 바꾸면 조합 표가 `kr-fin-quality-growth` × 중기 전략 3개로만 바뀌고 스윙 선정 `kr-vol-surge-quiet`는 보이지 않았다(STR-02). 브라우저 콘솔 오류는 의도한 409 한 건뿐.
2. [사실] **실행**은 화면이 아니라 터미널 명령이다(D-074 ①). 장 시간(10:48 KST)에 그대로 돌리면 "KRX 장 시간 보호 구간(08:00~16:00 Asia/Seoul)입니다"로 멈추고 결과 파일을 쓰지 않았다(D-063 관문 동작). 이 컨테이너에는 실전 엔진이 없어 `-PallowDuringSession`으로 다시 돌렸고 "장 시간 보호 해제됨" 출력 뒤 조합 3개 실행, "결정론 확인: 같음(병렬 2 vs 1, 상세 보고서 바이트 일치)", `result-bundle.jsonl.gz` 작성(`detail.txt` SHA-256 앞자리 `0b1e49a3e0aa33d1`).
3. [사실] **화면으로 비교하기**. 실험 목록의 실험 폴더 행이 "불러올 수 있음"으로 바뀌었고 "불러오기"를 누르자 실험 화면으로 이동했다. 한 화면에 계좌 비교표(계좌 3개, 조합·조합 해시·선정 기록·총수익률·CAGR·MDD·샤프·PF·승률·왕복·비용), 선정 × 매매지표 교차표(칸마다 조합 해시, "선정 없음 대비"·"대조 열 대비" 차이), 자산 곡선 3개 겹쳐 보기가 나왔다. 값은 실행 명령의 `results.md`·`runs.tsv`와 같다(A01 47.33%·₩14,732,930, A02 1.49%·₩10,149,461, A03 0.00%). 콘솔 오류 0.
4. [사실] 같은 흐름이 자동 테스트로도 고정돼 있다: `ExperimentCreationEndToEndTest#화면으로_등록한_실험을_실행하고_불러와_조합끼리_비교한다`(등록 API → `CombinationExperiment.runAndWrite` → 불러오기 → 상세·교차표·자산 곡선 200) 통과. 화면 JS 자체는 자동 테스트가 없다(D-026, 대조표 4장) — 이번 판정의 화면 동작 근거는 위 1·3의 직접 실행이다.
5. [사실] role-guides 완료 기준: ① 실전 관련 버튼 — `RealOpsControllerTest` 11건(확인 문구·비밀번호를 엔진에 넘기고 응답·로그에 남기지 않음, CSRF·출처 검사 실패 시 엔진까지 가지 않음, 엔진 없음·무응답 처리) 통과, 실행 중 실험실에 활성화·전체 PANIC을 직접 보내면 엔진이 없어 503 `ENGINE_UNREACHABLE`("다시 보내지 말고 상태를 새로 읽어 확인")이고 띠는 "실전 엔진이 꺼져 있습니다"로 표시됨. 2차 확인 검증 자체는 실전 엔진이 하며 PR #18 QA가 실제 엔진으로 확인함(`docs/reviews/pr-18/qa.md`). ② 같은 실험 계좌 한 화면 비교 — 위 3. ③ 비밀값 — `SettingsControllerTest` 8건·`WebSecurityIntegrationTest` 5건 통과, 설정 저장 API로 `real-trading.accounts`를 보내면 400 "이 화면에서 바꿀 수 없는 키입니다".
6. [사실] **5단계로 묶였지만 구현되지 않은 것**(기획서 10장 대비 코드): UI-02 진행 상태(실행은 터미널), UI-05 블랙리스트 관리·추천 종목·잔고·거래 내역, UI-07 미국 화면, UI-08 실전·실시간 모의 계좌 조회 필드, MON-05 모니터링 화면(`autotrade-monitor-agent-jar` 빈 모듈) — 대조표 3장과 같다. 여기에 **대조표에 없는 UI-01 "실시간 갱신은 SSE"**가 더 있다: `SseEmitter`·`text/event-stream` 사용처가 없고(`grep -rn SseEmitter autotrade-app-lab` 0건), 화면은 5초 폴링이다(ADR-006 2.7 "SSE는 후속", ADR-005 9장 후속 표). `[추론]` 이 여섯 항목은 데이터를 만드는 쪽(실행기 진행 보고, 실전 엔진 스냅샷 필드, 모니터 에이전트, 미국 실행 명령)이 없어 화면만 먼저 만들 수 없는 것이라 이연 자체는 합리적이지만, 지침 8장 5번(범위 변경)에 해당해 QA가 대신 정할 수 없다.

### 사용자 결정(판정 범위)
- **결정 1**: 기획서 14장 5단계 행의 관련 요구사항 중 위 6항목(UI-01 SSE, UI-02 진행 상태, UI-05 블랙리스트·추천·잔고·거래 내역, UI-07, UI-08 실전·실시간 모의 조회, MON-05)을 5단계 판정 범위에서 빼고 뒤 단계(대조표 3장 "이어서 할 단계", SSE는 ADR-005 9장 조건)로 넘길지. 추천: 넘김 + 기획서 14장에 이연과 담당·단계를 기록.
- **결정 2**: D-074의 해석("화면으로 실험 만들기 = 등록 파일 쓰기, 실행은 사용자가 커밋 뒤 터미널 명령")을 5단계 완료 기준 충족으로 인정할지. 추천: 인정(실행 버튼은 저장소 이력 변경·사전 등록 규칙과 부딪혀 D-074 대안 B 되돌릴 조건으로 미룬 것이 근거 있음).

### C2 검토 원문
| PR | 검토 원문 | 마지막 판정 |
|---|---|---|
| #13 | 없음. 머지 2026-10-09 07:37 KST로 D-030(검토 원문 보존, PR #15 09:34 머지) 이전. PR 본문 "교차 검토"에 architect 차단 0, risk-ops B1 해결 후 승인으로 적힘(1단계 판정 PR #7·#9와 같은 처리) | 해당 없음(원문 제도 이전) |
| #18 | `docs/reviews/claude-project-thread-r930sh/risk-ops.md`(재검토 4), `.../architect.md`(재검토 3), `docs/reviews/pr-18/qa.md` | 모두 승인 |
| #33 | `docs/reviews/pr-33/architect.md` 1차 수정 필요(F1) → 재검토 2026-10-09 | 승인 |
| #49 | `docs/reviews/pr-49/architect.md` 1차 수정 필요(B3) → 재검토 2026-10-11 | 승인 |
| #51 | `docs/reviews/pr-51/architect.md` 1차 수정 필요(F1) → 재검토 2026-10-11 | 승인 |
| #82 | `docs/reviews/pr-82/architect.md` 1차 승인, 재검토 2026-10-14(커밋 전 작업 트리 4파일 기준) | 승인. 재검토가 본 변경(소문자 ID 단언 `ExperimentCreationEndToEndTest.java:215-216`, `docs/config/config-keys.md:41` 실험 생성 화면 문구)이 main에 들어 있음을 확인 |

미해결 "수정 필요" 없음. PR #18 QA가 "5단계 완료 판정에서 다시 확인한다"고 남긴 기획서 RSK-13 갱신은 v14.1(`docs/spec/autotrade-spec.md:41`, RSK-13 `:215`)로 반영됨을 확인했다.

### C3 자율 결정 목록
5단계에서 "추천안 채택(자율)"로 기록된 결정(`docs/decisions/README.md`):

| 결정 | 내용 | 사용자 확인 필요 |
|---|---|---|
| D-020 | 로컬 웹 대시보드(Spring Boot + 경량 프런트, SSE, 127.0.0.1) | 아니오. 단 SSE는 아직 구현되지 않음(결정 1) |
| D-026 | 빌드 없는 정적 프런트, 루프백 Host·CSRF·Origin, 프로필 불변 버전 파일, 비밀값 쓰기 전용 | 아니오 |
| D-060 | 선정 프로필 편집, 조합 교차표·대조군 두 축, 선정 결과 조회 | 아니오 |
| D-062 | 실험실은 실험을 돌리지 않고 결과 묶음을 불러옴(대조 후 등록) | 아니오 |
| D-074 | 실험 생성 = 등록 파일 쓰기, 실행은 터미널 명령, 한 실험 = 한 스타일, 의미 동일 알림 | **예** — 5단계 완료 기준의 해석을 정한 결정(결정 2) |

자율이 아닌 것: D-037(ADR-006 실전 제어 채널·운영 화면, PR #18 사용자 머지로 결정). 미확정으로 남은 실전 관련 Q-016·Q-017·Q-018은 사용자 결정 대상으로 결정 로그에 있다.

### C4 다음 단계를 막는 미확정
다음 단계는 6단계(실시간 모의 운영, 완료 기준 "8주 무사고, 승격 후보 도출"). 막는 미확정과 담당:
- **Q-015** 키움·한국투자 실제 접속 확인 — 담당 data-engineer, 사용자(키 등록·장중 녹화). D-059로 게이트웨이의 토스 실시간이 없어 6단계 실시간 시세는 키움·한국투자 경로에 기댄다 `[추론]`.
- **Q-013** 장중 프로필 기준 — 담당 strategy-researcher(스윙·중기는 D-066·D-073으로 확정).
- 대조표 3장 MON-05(모니터 에이전트, "6단계 운영 전")는 Q 번호가 없는 후속 과제이고 담당 ui-dev·data-engineer가 표에 있다.

모두 담당이 명시되어 있어 통과. 참고: 기획서 14장에서 4단계(전략 탐색)는 아직 "단계 완료 판정 전"이고 6단계 대기 조건은 2단계 실녹화·게이트웨이→실험실 실시간 전달(ADR-003 5장)이다. 5단계 판정과는 별개다.

### C5 대조
| # | 문서 | 코드·실제 | 결과 |
|---|---|---|---|
| 1 | ADR-005 5장 REST 표(`docs/adr/ADR-005-ui-dashboard.md:96-112`): `GET /api/profiles?market=&style=`, `/api/selections?market=&style=`, `POST /api/selections/{id}/versions/{v}/compare`, `POST /api/experiment-folders`(201·400·409·403), `/preview`, `/snapshots?kind=`, `/{id}/load` | `ProfileController`, `SelectionProfileController:107`, `ExperimentFolderController:37,50,60,70,77`. 직접 호출: preview 200·쓰지 않음, 다른 Origin 403, 같은 ID 409 | 일치 |
| 2 | D-074 ② 쓰기 전 검사: 실험 ID = 실행 명령과 같은 모양, 1~128조합, KRX만, 재무 조건 선정이면 재무 스냅샷 필수, `CombinationPlan.parse` 재확인 | `ExperimentRegistrations.java:44`의 패턴 = `ExperimentId.java:9`, `MAX_COMBINATIONS` 128 = `SimulationExperimentRunner.java:103`, `:135-137` KRX만, `:165-167` 재무 스냅샷, `:200-210` 재읽기 | 일치 |
| 3 | UI-01 바인딩·웹 보안(127.0.0.1, Host·Origin·CSRF) | `application.yml:13`, `WebConfiguration.java:36-50`(루프백 아니면 기동 거부), `LocalOnlyRequestFilter`, 직접 호출에서 다른 Origin 403 | 일치 |
| 4 | **UI-01 "실시간 갱신은 SSE"**(`docs/spec/autotrade-spec.md:255`), D-020 2번 | SSE 구현 없음, 5초 폴링(ADR-006 2.7 "SSE는 후속"). 기획서 UI-01 행에는 이연 표시가 없다 | **불일치** |
| 5 | **기획서 14장 5단계 행**(`docs/spec/autotrade-spec.md:329`) "1차 머지(PR #13 실험 화면, #18 실전 운영 화면). 단계 완료 판정 전", 표 머리 "2026-10-09, main `1bd0ddc` 기준" | 5단계 산출물은 #33·#49·#51·#82까지 머지됐고 실험 생성 화면(D-074)으로 완료 기준 증거가 생겼다. 행이 갱신되지 않았다 | **불일치** |
| 6 | **`docs/adr/README.md:12`** ADR-006 상태 "제안(사용자 직접 머지)" | `docs/adr/ADR-006-real-control-channel.md:3` "상태: 채택 (PR #18 머지, 2026-10-09 사용자 결정. 이전 상태: 제안)" | **불일치** |
| 7 | **ADR-005 상태 "제안"**(`docs/adr/ADR-005-ui-dashboard.md:4`, `docs/adr/README.md:11`) | 같은 내용의 결정 D-026이 결정 로그에서 "확정", Q-006을 이 ADR로 닫음(`docs/decisions/README.md:32`), PR #13·#49·#51·#82가 이 ADR 계약대로 머지되어 운영 코드가 됨. 1단계 판정에서 ADR-002 "제안"을 같은 사유로 불일치로 본 것과 같은 기준 | **불일치** |

## 과금 검사
- [사실] 5단계 코드에 외부 유료 호출이 없다. 화면은 외부 스크립트·폰트를 불러오지 않는다(`autotrade-ui/src/main/resources/static`에서 외부 URL은 SVG 이름공간뿐).
- [사실] 유료가 될 수 있는 카카오 BIZ 알림은 기본 꺼짐(`"kakao": { "enabled": false, "mode": "ME" }`)이고, 설정 화면에서 BIZ로 바꾸거나 BIZ로 켤 때 명시 확인을 요구한다(`SettingsController.needsPaidConfirmation`, `SettingsController.java:137-144`). 통과.

## 위험 시나리오(점검표 밖)
"화면이 잘못된 실험·잘못된 주문·보호 공백을 만들 수 있는가"를 실행 중인 실험실로 직접 시도했다.
1. **등록 파일 주입**: 제외 종목·비교 종목·일봉 스냅샷 칸에 줄바꿈과 따옴표를 넣어 등록 파일에 다른 키(`seed`, `parallelism`, `financials-snapshot`)나 목록 항목을 끼우려 함 → 4종 모두 400 "닫는 큰따옴표가 없습니다"(재읽기 단계에서 거절, 폴더 안 만듦). 질문의 따옴표·역슬래시는 이스케이프되어 그대로 들어감. 종료일 < 시작일, 소문자 ID, 조합 공백 변형, 음수 시드도 400. **막힘.**
2. **실전 버튼을 확인 없이 / 다른 출처에서**: 다른 Origin은 403, 엔진이 없을 때 활성화·전체 PANIC은 503(재전송 금지 안내), 띠는 "실전 엔진이 꺼져 있습니다"로 가상으로 바뀌지 않음. 설정 저장으로 실전 계좌 키를 쓰려 하면 400. **막힘.**
3. **장 시간에 화면이 알려 준 명령 실행**: 화면의 실행 명령을 그대로 장중에 돌리면 D-063 관문이 멈추고 결과를 쓰지 않음(실전 엔진과 자원 다툼 없음). **막힘.**
4. **같은 ID 재등록으로 사전 등록 덮어쓰기**: 409, 원래 파일 그대로. **막힘.**

권고(판정에 넣지 않음):
- R1 실험 ID 규칙이 `CON`·`NUL`·`COM1` 같은 Windows 예약 이름을 받는다(미리 보기 200 확인). 사용자 PC가 Windows라 폴더 만들기가 실패하거나 이상 동작할 수 있다 `[추론]`(Windows에서 실행 못 함). `ExperimentId`와 함께 예약 이름 거절을 권한다.
- R2 실험 화면 "실행 방식"이 일봉 스냅샷 조합 실험도 "녹화 리플레이"로 표시한다(`experiments.js:110`, `ClockMode.REPLAY`). 데이터 칸에 `DAILY-PATH-V1`이 따로 나오지만, 녹화 기반 실험과 헷갈리지 않게 "리플레이(일봉 경로)"처럼 구분하기를 권한다.
- R3 화면 JS 자동 테스트가 없다(D-026). 실험 생성·불러오기 화면은 이번처럼 Playwright 한 흐름(등록 → 실행 명령 본체 → 불러오기 → 표 확인)을 CI에 두면 회귀를 잡는다.
- R4 실제 KRX 전체 스냅샷으로 등록·실행·불러오기를 한 적이 아직 없다(대조표 4장). 이번 확인도 합성 4종목 120거래일이다. 4단계 사용자 PC 수집 뒤 첫 조합 실험에서 화면 응답 시간과 결과 묶음 크기를 확인할 것.

## 판정
수정 필요(C5, 사용자 결정 2건)

- C5: 기획서 UI-01의 SSE를 구현 상태에 맞게 이연 표시하고, 기획서 14장 5단계 행을 실제 머지 PR과 상태로 갱신하고, `docs/adr/README.md`의 ADR-006 상태를 본문(채택)과 맞추고, ADR-005 상태를 D-026 확정·머지된 계약과 맞출 것.
- 사용자 결정: 결정 1(UI-01 SSE 포함 6항목의 판정 범위 이연), 결정 2(D-074 해석을 완료 기준 충족으로 인정). 두 결정이 기록되면 C1의 조건이 풀린다.
- 완료 기준 자체("실험을 UI로 만들고 비교 가능")는 실제 화면에서 등록 → 실행 명령 → 불러오기 → 같은 실험 계좌 비교까지 재현됐고, role-guides 완료 기준 세 가지도 확인됐다.

## 사용자 결정 기록 2026-10-14
- 출처: QA 스레드에서 사용자(BAE) 답 "추천방안으로"(2026-10-14T01:59:58Z). 위 "사용자 결정" 두 건 모두 추천안을 고른 것으로 기록한다.
  - 결정 1: UI-01 SSE, UI-02 진행 상태, UI-05 블랙리스트·추천 종목·잔고·거래 내역, UI-07, UI-08 실전·실시간 모의 조회, MON-05를 5단계 판정 범위에서 빼고 뒤 단계로 넘긴다.
  - 결정 2: D-074 해석(화면은 등록 파일 쓰기, 실행은 터미널 명령)을 5단계 완료 기준 충족으로 인정한다.
- 판정은 바뀌지 않는다: 수정 필요(C5). 재판정 때 확인할 것은 ① 두 결정이 결정 로그·기획서 14장에 기록됐는지(이연 항목마다 담당·단계) ② C5 4곳 정정이다. 사용자 결정이 기록되면 C1 조건은 풀린 것으로 본다.

## 재검토 2026-10-14
- 대상: main `5eb0885`(1차 `4e32d75` 뒤 PR #87 실시간 시세 전달·실시간 모의 세션 D-076, #89 1단계 C5 정리, #88 5단계 1차 판정 반영 D-079 머지)
- 점검표: C 전체 + 과금 검사
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL**(첫 시도, 130 작업 모두 실행). 테스트 XML 합계 **1,056건, 실패 0·오류 0·건너뜀 0**. `ExperimentCreationEndToEndTest` 3, `RealOpsControllerTest` 11, `SettingsControllerTest` 8, `WebSecurityIntegrationTest` 5, 새 `LiveLabServiceTest` 5·`LiveSessionTest` 9·`LivePlanTest` 2, 보호 테스트 `ArchitectureTest` 18·`RealTradingGuardTest` 31·`GuardedOrderGatewayTest` 8 통과.
  - 1차와 같은 화면 흐름을 새 main에서 다시 함(실험 `EXP-20261014-QA5B`, 같은 합성 스냅샷): 등록 화면 스타일 필터(중기 6칸·스윙 10칸 같음), 미리 보기 → 등록 → 같은 ID 409, 실행 명령(장 시간 보호 해제, 결정론 확인 같음), 실험 폴더 불러오기 → 계좌 3개 비교표·교차표·자산 곡선. 값이 1차와 같다(A01 47.33% 등), 콘솔 오류 0(의도한 409 제외). 끝난 뒤 실험 폴더 삭제, `git status` 깨끗함.
  - #87이 실험실 웹에 더한 `/api/live` 탐침: CSRF 없음 403 `CSRF`, 다른 Origin 403 `ORIGIN`, 다른 Host 403 `HOST`, 조합 실험 등록 파일로 시작 시도 400(모르는 키, 세션 안 열림), 경로 넘기기 ID 400, 상태 조회 200(세션 없음). 화면 메뉴는 없음(ADR-010 "화면은 후속").

| # | 결과 | 근거 |
|---|---|---|
| C1 | 통과 | 1차 C1 상세 1~5가 새 main에서도 그대로 재현됨(위 화면 흐름). 1차의 조건(판정 범위·D-074 해석)은 사용자 결정 **D-079**로 기록됨: `docs/decisions/README.md` D-079 행, `docs/decisions/D-079-stage-5-scope.md`(출처 QA 스레드 2026-10-14T01:59:58Z "추천방안으로" 포함), 기획서 v14.25 UI-01·02·05·07·08·MON-05 행 이연 표시와 14장 5단계 이연 표(항목마다 담당·단계), 대조표 3장에 UI-01 SSE 추가. 이연 6항목의 내용이 D-079·기획서 14장·대조표 3장에서 서로 같음을 대조함 |
| C2 | 통과 | 1차 표 그대로에 더해 5단계 문서 PR #88 `docs/reviews/pr-88/architect.md` 재검토 승인. #87은 실험실 웹 계층을 바꿨다(`LiveSessionController`, `LabWebConfiguration`): `pr-87/architect.md` 재검토(2차) 승인, `pr-87/risk-ops.md` 재검토(2차) 승인, `pr-87/qa.md` 승인. #89 `architect.md`·`risk-ops.md` 승인. 미해결 "수정 필요" 없음 |
| C3 | 통과 | 1차 목록 그대로. D-074는 D-079 ②로 사용자가 인정해 "사용자 확인 필요" 표시가 해소됨. 이번에 더해진 자율 결정 D-076(실시간 세션)은 6단계 선행이라 5단계 판정 대상이 아니고 사용자 확인 대상도 아님(실전 엔진·Guard·주문 경로 변경 없음, #87 QA 승인) |
| C4 | 통과 | 6단계를 막는 미확정과 담당은 1차와 같다(Q-015 data-engineer·사용자, Q-013 장중 기준 strategy-researcher). 6단계 대기 조건이던 게이트웨이 → 실험실 실시간 전달은 #87(D-076)로 코드가 들어왔고, D-079 이연 표의 "6단계 시작 전" 항목(UI-01 SSE, UI-02 진행 상태)과 "6단계 운영 전" MON-05는 담당이 명시돼 있다 |
| C5 | 통과 | 1차 불일치 4곳 모두 정정 확인, 새 표본 3곳 일치. 아래 표 |

| # | 대조 | 결과 |
|---|---|---|
| 1 | 기획서 UI-01(`docs/spec/autotrade-spec.md:257`) "SSE(**이연, D-079**: 지금은 5초 폴링…)" ↔ 코드 SSE 없음·폴링 | 일치(1차 불일치 해소) |
| 2 | 기획서 14장 5단계 행(`:331`)에 #13·#18·#33·#49·#51·#82와 1차 판정·D-079 기록, 표 머리 "행마다 기준 날짜·커밋" | 일치(해소) |
| 3 | `docs/adr/README.md` ADR-006 "채택(D-037, PR #18 사용자 머지)" ↔ ADR-006 본문 "채택" | 일치(해소) |
| 4 | ADR-005 상태 "채택 (D-026 확정, PR #13 머지…)"(`ADR-005:4`), README "채택(D-026)" ↔ D-026 확정·머지 | 일치(해소) |
| 5 | ADR-005 5장 `/api/live/*` 4행(`ADR-005:113-116`) ↔ `LiveSessionController` `@GetMapping("/status")`, `@PostMapping("/sessions/{id}/start")`, `/stop`, `/finish`, 직접 호출에서 CSRF 403·형식 오류 400 | 일치 |
| 6 | D-079 이연 표 ↔ 기획서 14장 이연 표 ↔ 대조표 3장(6항목, 담당·단계) | 일치 |
| 7 | 1차 표본 1~3(ADR-005 REST 표, D-074 ② 검사, UI-01 바인딩·웹 보안) 새 main에서 해당 코드 변경 없음(`git diff 4e32d75 origin/main -- autotrade-ui autotrade-app-lab/src/main/java/com/autotrade/app/lab/experiment autotrade-app-lab/src/main/java/com/autotrade/app/lab/web` 비어 있음) | 일치 |

과금 검사: 이번 변경에 외부 유료 호출 없음(#87은 로컬 소켓, #88·#89는 문서·SQL 스모크). 통과.

권고(판정에 넣지 않음): 1차 R1~R4는 그대로 남는다(Windows 예약 이름 ID, "녹화 리플레이" 표시, 화면 Playwright CI, 실제 KRX 스냅샷 미사용). 새 권고 R5: `/api/live/sessions/{id}/start`에 조합 실험 등록 파일을 넣으면 400 메시지가 "모르는 키입니다: bars-snapshot"으로 나온다. 실시간 세션 화면(후속)을 만들 때 "실시간 세션 등록 파일(`input: live`)이 아닙니다"처럼 원인을 바로 알리기를 권한다.

### 판정 (재검토)
승인

- 점검표 C 전 항목 통과, 확인 못 함 없음. 1차 수정 필요(C5 4곳)는 모두 정정됐고, 사용자 결정 2건은 D-079로 기록됐다.
- 완료 기준 "실험을 UI로 만들고 비교 가능"은 새 main의 실제 화면에서 다시 재현됐다(D-079 ② 해석 기준). D-079 ①로 넘긴 6항목은 5단계 완료가 아니라 이연이며, 기획서 14장 이연 표의 단계에서 다시 확인한다.
- 5단계 완료 기록(결정 로그)은 이 승인 뒤 개발 쪽이 한다(지침 11장). 머지는 사용자가 한다.
