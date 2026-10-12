# architect 검토: Q-013 중기 선발 기준 수치 사용자 결정 (D-066)
- 대상: PR #59 / 커밋 4008292e24e20ded78fe352a508f1db0d47d91dd / base main 5b26e95a8cb6ff8f917b82a5366bf523564747bb(GitHub PR base SHA, `origin/main`과 같음) 대비 diff 전체
- 점검표: B
- PR 본문·커밋 메시지는 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 3파일 +42 −4. `docs/decisions/D-066-position-selection-thresholds.md`(새 파일 37줄), `docs/decisions/README.md`(D-066 행 추가, Q-013 행 수정), `docs/strategy/combination-experiments.md`(3장 제목·머리말·표 머리 "제안"→"기준"). Java 소스·테스트·빌드 스크립트·스키마·설정 키·REST 변경 0건
  - `./gradlew build --console=plain`: **BUILD SUCCESSFUL**(2m 16s, 130 tasks, 78 실행·52 up-to-date). 테스트 결과 XML 합계 tests 890, failures 0, errors 0, skipped 0
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - CI(head 4008292): Gradle build, Secret scan and collector boundary, DB schema(PostgreSQL 16) 세 작업 모두 **failure**. 체크 주석 원문: "The job was not started because recent account payments have failed or your spending limit needs to be increased." 즉 작업이 시작되지 않았다(코드 결과가 아님). gitleaks는 이 컨테이너에 없어 직접 돌리지 못했다
  - 내용 대조: D-066 1~5번과 `combination-experiments.md` 3장 표 다섯 행(main과 이 PR 모두)의 수치를 줄 단위로 비교 — 같다. `autotrade-strategy-lab` 스킬 5장 원문(`.claude/skills/autotrade-strategy-lab/SKILL.md` 5장)과 비교 — 아래 F2
  - 옆 PR 충돌: `git merge-tree --write-tree origin/claude/project-thread-38mgco origin/claude/project-thread-5jfdgg` → `CONFLICT (content): Merge conflict in docs/decisions/README.md`(PR #58의 D-065 행과 같은 자리, 아래 O1)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공, 890건 실패·오류·건너뜀 0. (CI는 결제 문제로 시작되지 않음 — 점검표 밖 C1) |
| B2 | 해당 없음 | 모듈·의존성·ArchUnit 규칙 변경 없음(diff에 `.gradle.kts`·Java 파일 없음). `:autotrade-architecture-test:test`는 빌드 안에서 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. 바뀐 것은 실험 판정 기준(결정 로그 문서)이며 코드 계약이 아니다. 이 기준을 코드(리포트 판정)로 옮길 때 계약이 생기면 그때 ADR-007 갱신 대상 |
| B4 | 해당 없음 | 코드 변경 없음. 골든 테스트 포함 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 지우거나 건너뛴 테스트 없음(diff에 테스트 파일 없음, 890건 skipped 0) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 코드·문서 변경 없음. D-066 "범위"(`D-066-position-selection-thresholds.md:24`)도 이를 명시. 승격 기준 변경이라 지침 8장 5번 사용자 결정 대상이며 문서상 사용자 결정으로 기록됨(O3). 10장 사용자 머지 대상 목록에는 들지 않음. risk-ops 검토 대상 아님 |
| B8 | 통과 | 추가 42줄을 직접 읽음: 키·토큰·계좌번호 없음, 8자리 이상 숫자열·32자 이상 영숫자열 grep 0건. 사용자 표시 이름 `BAE`는 기존 결정 문서들과 같은 관례. CI gitleaks는 시작되지 않아 기계 검사 결과는 없음(C1) |

## 점검표 밖 발견
- **F1 (수정 필요, 문서 내부 모순)**: `docs/strategy/combination-experiments.md:3` 머리말이 아직 "중기 워크포워드·괴리 수치는 **제안**이고 Q-013(사용자 결정 포함)으로 남는다"라고 한다. 같은 파일 3장(`:32-34`)은 이번 PR로 "D-066으로 확정"이 됐다. 문서 첫 줄과 본문이 서로 다른 상태를 말한다. 머리말에 D-066을 더하고 "제안" 문구를 "D-066으로 확정(사용자 결정)"으로 고쳐라.
- **F2 (수정 필요, 판정 기준 모호)**: D-066 3번(`D-066-position-selection-thresholds.md:14`)은 "`autotrade-strategy-lab` 스킬 5장 기준(비용 차감 후 벤치마크 초과, PF ≥ 1.3, MDD ≤ 20%, 검증/학습 ≥ 60%)"이라고 적는데, 스킬 5장 원문에는 **샤프 ≥ 1.0**과 **주요 파라미터 ±20% 변경 시 성과 급락 없음**이 더 있다. 괄호가 요약인지(5장 전 항목 적용) 열거인지(샤프·민감도는 중기에서 뺌) 문서로 판단할 수 없고, 리포트 판정을 만드는 sim-engine·strategy-researcher가 서로 다르게 구현할 수 있다. 확정 수치 문서라 한 가지로 정해 적어야 한다. 사용자가 승인한 문구가 "스킬 5장 기준"이므로 (a) "5장 전 항목(샤프 ≥ 1.0, ±20% 민감도 포함)"으로 괄호를 완성하는 것을 권한다. (b) 샤프·민감도를 중기에서 빼는 뜻이었다면 그것은 승격 기준 변경이라 사용자 확인을 다시 받아야 한다. 같은 괄호가 `combination-experiments.md` 3장 표 "선발 판정" 행에도 있으니 함께 고친다. "구간 합 30건"(2번)과 스킬 5장 "검증 구간 거래 30건 이상"의 관계(합산으로 대체)도 같은 자리에서 한 줄로 밝히면 좋다.
- **C1 (머지 차단 조건, 코드 문제 아님)**: 이 커밋의 CI 세 작업이 GitHub 결제·지출 한도 문제로 **시작되지 않았다**. 지침 10장 Claude 머지 조건 "CI 통과"를 충족하지 못한다. 내 직접 빌드가 B1을 대신하지만 gitleaks·PostgreSQL 스키마 검사는 대신하지 못했다(이 PR은 문서뿐이라 스키마 영향은 없음). 결제 문제는 지침 8장 1번(과금) 사용자 확인 대상이다. 이 판정은 코드·문서 내용에 대한 것이며, 머지는 CI가 다시 돌아 통과한 뒤에 한다.
- **O1 (관찰)**: D-065는 열린 PR #58(`claude/project-thread-38mgco`)에 있고, 두 PR 모두 `docs/decisions/README.md` 70행에 행을 넣어 내용 충돌이 난다(위 merge-tree로 확인). 번호 건너뜀 자체는 맞다. 나중에 머지하는 쪽이 D-064, D-065, D-066 순서로 맞추면 된다.
- **O2 (관찰)**: 기획서는 그대로다. `docs/spec/autotrade-spec.md:174`(STR-18 "워크포워드 구간·괴리 허용치 수치는 Q-013"), `:353`(15장 Q-013), `:332`(배치표 2번)가 D-066 이후에는 낡은 문구가 된다. D-066 "영향"(`:37`)이 pm-lead의 다음 기획서 판으로 넘겼으니 추적만 하면 된다. 지침 2장(기획서 단일 원본)상 다음 판에서 빠지지 않게 pm-lead 할 일로 남겨 두길 권한다.
- **O3 (관찰, 저장소로 검증 불가)**: "사용자 결정(BAE, 2026-10-12T14:08:16Z 프로젝트 스레드 '추천안대로 해주십시오.')"는 저장소 밖 대화에서 온 사실이라 이 검토에서 확인하지 못했다(이번 검토는 프로젝트 스레드 도구를 쓰지 않았다). 점검표 B 항목은 아니지만, 승격 기준 변경을 사용자 결정으로 기록하는 문서이므로 독립 QA나 사용자가 원문과 대조하길 권한다.
- **O4 (관찰)**: D-066 상태 줄(`:4`)에 다른 사용자 결정 문서들의 "확정," 표기가 없다(D-035·D-039·D-040·D-056은 "확정, **사용자 결정**"). 형식만의 차이. 또 최소 3구간일 때 "검증 구간의 3/4 이상"은 3구간 모두를 뜻한다(4구간이면 3구간). 의도와 같다면 한 줄로 적어 두면 해석 차이가 없다.

## 판정
수정 필요(F1: `combination-experiments.md:3` 머리말의 "제안·Q-013으로 남는다"를 D-066 확정으로 고침, F2: D-066 3번과 조합 실험 절차 3장의 "스킬 5장 기준" 괄호가 샤프 ≥ 1.0·±20% 민감도를 빠뜨려 적용 범위가 모호함 — 5장 전 항목으로 완성하거나, 빼는 뜻이면 사용자 재확인). 직접 빌드 통과(890건), 코드·계약·실전 경로 변경 없음, B7 해당 없음(risk-ops 검토 대상 아님). 별도로 CI가 결제 문제로 시작되지 않아(C1) 고친 뒤에도 CI 통과 전에는 머지하지 않는다.
