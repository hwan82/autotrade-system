# architect 검토: D-114 장중 8주 승격 후보 기준 L1~L11 사용자 확정(Q-013 장중 부분)
- 대상: PR #176 / 브랜치 `claude/project-thread-m8gp5f` 헤드 `f161d169faf4eeea9bcc35a6dd4b58edd96265da` / `git diff origin/main...HEAD` 전체(커밋 1개, 4개 파일, +34 −8): `docs/decisions/D-114-intraday-live-promotion-criteria.md`(새 파일), `docs/decisions/D-113-stage-6-live-operation.md`, `docs/decisions/README.md`, `docs/strategy/stage-6-plan.md`. 코드·빌드 스크립트·스키마·설정·보호 경로(`scripts/ci/protected-paths.txt`) 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(헤드 `f161d16`, 작업 트리 깨끗) 첫 실행: BUILD FAILED. `:autotrade-app-collector:compileJava`가 `com.autotrade.marketdata.daily.SnapshotManifest` 등을 못 찾음(46 errors). 원인은 이 PR이 아니라 로컬 빌드 산출물: `:autotrade-marketdata:compileJava`가 UP-TO-DATE로 건너뛰었는데 소스(`autotrade-marketdata/src/main/java/com/autotrade/marketdata/daily/SnapshotManifest.java:20`)는 있다. 이 PR은 Java 파일을 하나도 바꾸지 않는다.
  - `./gradlew clean build --no-daemon`: **BUILD SUCCESSFUL in 3m 56s**, 161 actionable tasks(136 executed), EXIT=0. ArchUnit·순수 모듈 금지 의존 검사 포함.
  - 사용자 결정 원문 대조: 프로젝트 스레드 "6단계 실시간 모의 운영 준비"의 결정 카드 `cmsg_01Du6VLttqgn6f15sF8UYTuUNmfUa56r4wdBaSFPQgfrjj`(질문 "장중 프로필 8주 승격 후보 기준을 추천안(L1~L11)으로 확정할까요?", 0번 선택지 "추천안 확정")와 선택 기록 `cmsg_01Du6VLttqgn6f15sF8UYTuU1MiBxsHtwk5psMs2izLnBg`(2026-10-19T13:05:40Z, `option_index` 0, `chosen_by` `user_01Vc7USeKzFq1SwmNQt6Je3E`)을 직접 읽었다. D-114 4행(상태)의 시각·질문·선택이 원문과 같다. 표시 이름 "BAE"는 기록에 이름이 없어 확인 못 했으나, 같은 사용자 표기가 D-035·D-039·D-040에 이미 있다.
  - 수치 대조: D-114 1~5번을 `docs/strategy/stage-6-plan.md` 4.2 표(L1~L11, 103~115행)와 D-027 C1~C10(`docs/decisions/D-027-strategy-lab-protocol.md:14`)에 하나씩 맞춰 봤다. PF ≥ 1.3, MDD ≤ 20%, 샤프 ≥ 1.0, 왕복 ≥ 30, ±20% 변형, 최고 1주 제외, 지연 2초, L10 p = (k+1)/(N+1) ≤ 0.05/m·m = 6·N + 1 < 20m 판정 불가, L11 2%p·10%가 모두 일치한다.
  - 남은 "결정 대기" 문구 grep(`docs/`, `experiments/`): D-113·6단계 계획·결정 로그 안에는 남은 곳이 없다. 기획서에는 남아 있다(R1).
  - diff 전체 비밀값 패턴 grep(appkey, secret, token, 계좌번호 형식): diff에 걸리는 것은 바뀌지 않은 Q-015 문맥 줄의 공개 오류 코드뿐이다.
  - CI 상태는 `gh pr checks`가 GraphQL 403으로 막혀 보지 못했다. 판정은 위 로컬 빌드로 한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | `./gradlew clean build` BUILD SUCCESSFUL(EXIT=0). 첫 실행 실패는 증분 빌드 산출물 문제였고 clean 뒤 같은 헤드에서 통과. 이 PR의 변경 파일은 모두 `docs/`의 Markdown |
| B2 | 해당 없음 | 모듈·의존·ArchUnit 규칙 파일 변경 없음(`git diff --stat`의 4개 파일 모두 문서) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 바뀐 것은 판정 기준 문서이고, L1~L11은 실험 판정 규칙이지 모듈 간 계약이 아니다. 판정 명령(T2) 구현은 후속 PR의 일 |
| B4 | 해당 없음 | 코드 변경 없음. 기준 자체는 사전 등록 원칙(결과 전 확정)을 지키고, 결과를 본 뒤 바꾸려면 새 실험 ID에만 적용하는 새 결정으로 한다고 적었다(D-114 25행) |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 코드·테스트 변경 없음. 테스트 삭제·`@Disabled` 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트·수동 보유 종목, 리스크 한도, 장애 대응, 증권사 주문 연동, 보호 경로를 건드리지 않는다. D-114 "범위"(20~21행)가 Guard·블랙리스트·리스크 한도·실전 활성화 절차를 바꾸지 않고 실전 활성화는 그 뒤에도 사용자 결정이라고 명시한다. 승격 기준은 실전 활성화로 이어지는 결정이지만 이번 것은 실시간 모의 8주 후보 선별 기준이고 사용자가 직접 정했다 |
| B8 | 통과 | diff grep에서 API 키·계좌번호·토큰 없음. 사용자 식별은 표시 이름과 시각뿐 |

## 점검표 밖 발견
- **R1 (수정 필요) 기획서(단일 원본)가 이 결정을 반영하지 않는다.** 지침 2장은 기획 내용을 `docs/spec/autotrade-spec.md`에만 쓰게 하고, 승격·선발 기준은 기획 내용(STR-04·05·14)이다. 지금 기획서는 15장 미확정 표 Q-013 행(`docs/spec/autotrade-spec.md:404`)이 여전히 "장중 프로필 기준"을 열린 항목으로 두고, STR-14(`:203`)도 "장중 프로필은 녹화 기간이 짧아 별도 기준 … (Q-013)"에서 멈춰 있다. 이 PR이 결정 로그 Q-013 행(`docs/decisions/README.md:129`)만 닫아서 두 원본이 서로 다른 말을 한다. 같은 성격의 사용자 결정 D-073(Q-051 닫힘)은 기획서 v14.22로 변경 이력과 STR-14·18에 반영했다(`docs/spec/autotrade-spec.md:46`). 고칠 것: 기획서 버전을 올려(v14.51) 변경 이력에 D-114(앞 PR #175에서 빠진 D-113 등록도 같이)를 적고, Q-013 행에서 "장중 프로필 기준"을 닫힌 것으로(D-114 링크), STR-14 근거·인수 칸에 D-114 또는 6단계 계획 4.2 링크를 단다. `autotrade-spec-keeper` 스킬 절차.
- **N1 (참고) D-114 수치는 6단계 계획 4.2를 "그대로" 링크하면서 1번에 요약을 다시 적었다.** 지금은 일치하지만 한쪽만 고치면 어긋난다. 4.2 표를 바꾸려면 새 결정이 필요하다고 D-114 25행이 이미 정했으므로 막지 않는다.
- **N2 (참고) 로컬 증분 빌드가 `autotrade-marketdata` 산출물을 최신으로 잘못 봤다.** 이 PR과 무관하고 clean 뒤 통과했다. 같은 체크아웃을 다른 세션이 공유하면 생길 수 있는 일이라, 검토 빌드는 `clean build`로 돌리는 편이 낫다.

## 판정
수정 필요(R1). 점검표 B는 통과 또는 해당 없음이고 확인 못 함은 없다. 다만 사용자 결정을 기획서(단일 원본)에 반영하지 않아 기획서 Q-013·STR-14가 결정 로그와 어긋난다. R1을 고친 뒤 재검토한다. 실전 경로를 건드리지 않아 risk-ops 검토 대상이 아니다.

## 재검토 2026-10-19
- 대상: PR #176 / 헤드 `627dbc511006ee855f3dedb60353f0e45b9fd024` / `git diff origin/main...HEAD` 전체(커밋 3개, 6개 파일). 첫 검토 뒤 바뀐 것은 `docs/spec/autotrade-spec.md` 하나(커밋 `627dbc5`, +4 −3)와 이 검토 원문.
- 점검표: B
- 직접 실행한 것:
  - `git pull` 후 `./gradlew clean build --no-daemon`(헤드 `627dbc5`, 작업 트리 깨끗): **BUILD SUCCESSFUL in 3m 46s**, 161 actionable tasks, EXIT=0.
  - `git diff 33bd539b..HEAD --name-only`: `docs/spec/autotrade-spec.md`뿐. 코드·보호 경로 변경 없음.
  - 기획서 변경을 결정 로그·D-114·6단계 계획과 줄 단위로 대조했다.
  - diff 전체 비밀값 패턴 grep: 새로 걸리는 것 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `clean build` BUILD SUCCESSFUL(EXIT=0) |
| B2 | 해당 없음 | 모듈·의존·ArchUnit 관련 파일 변경 없음 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. 기획서 변경은 결정 반영 |
| B4 | 해당 없음 | 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 코드·테스트 변경 없음, 테스트 삭제 없음 |
| B7 | 해당 없음 | 실전 경로·보호 경로 변경 없음. 기획서 STR-05의 Guard·실전 활성화 문구는 바뀌지 않았다 |
| B8 | 통과 | 기획서 diff에 키·계좌번호·토큰 없음 |

### 첫 검토 지적 처리
| 항목 | 결과 | 근거 |
|---|---|---|
| R1 기획서 미반영 | 해결 | 버전 v14.51(`docs/spec/autotrade-spec.md:12`), 변경 이력에 D-113·D-114 행(`:20`), STR-14 인수 칸에 D-114·6단계 계획 4.2 링크(`:204`), 15장 Q-013 행이 "중기 D-066, 스윙 D-073, 장중 8주 실시간 모의 D-114로 확정. 남은 것: 스타일별 KPI 전반"(`:405`)으로 결정 로그 Q-013 행(`docs/decisions/README.md:129`)과 같은 말을 한다. 링크 대상 `../strategy/stage-6-plan.md`가 있다 |

### 새로 본 것
- **N3 (권고) 기획서 0장 `갱신일`이 그대로 2026-10-18이다**(`docs/spec/autotrade-spec.md:13`). 버전은 v14.51(2026-10-19)로 올랐다. 이전 판들은 갱신일을 마지막 변경 이력 날짜와 맞춰 왔다(v14.50 = 2026-10-18). 다음 기획서 변경 때 함께 고친다. 판정에 영향 없음.
- **N4 (권고) 14장 상태표 6단계 행이 "대기: 2단계 실녹화와 게이트웨이 → 실험실 실시간 전달 뒤"로 남아 있다**(`:356`). D-113(PR #175) 때부터 남은 것이고 이 PR의 범위(D-114)와는 별개다. LIVE00 시작(10-20) 기록 때 "진행: D-113 등록"으로 고친다.
- N1·N2는 첫 검토 그대로(참고).

### 판정
승인. R1이 해결됐고 점검표 B는 통과 또는 해당 없음이며 확인 못 함은 없다. N3·N4는 막지 않는 권고다. 실전 경로를 건드리지 않아 risk-ops 검토 대상이 아니다.
