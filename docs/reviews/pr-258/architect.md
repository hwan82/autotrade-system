# architect 검토: PR #253 독립 QA 권고 Q1·Q2 반영(D-144 색인 W-12, QA 점검표 D9), QA 처리 기록 (PR #258)
- 대상: PR #258 / 브랜치 `claude/project-thread-npk3vh` 헤드 6f708526 / main(12e88baa, 머지 기준점과 같음) 대비 diff
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체: 문서 5개, +5 −2 (`docs/decisions/README.md`, `docs/qa-out-status.md`, `docs/qa/checklists.md`, `docs/qa/pending.md`, `docs/team/tst-06-checklist-map.md`). 코드·빌드 스크립트·설정·스키마 변경 없음
  - `./gradlew build --no-daemon` (헤드 6f708526 워크트리): 종료 코드 0, BUILD SUCCESSFUL
  - 대조: `docs/reviews/pr-253/qa.md` 권고 Q1·Q2(35~36행), 대응표 5장 경고 행(116~139행), D-144 후속 기록(40행), `scripts/ci/protected-paths.txt`·`.github/CODEOWNERS`

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 6f708526에서 `./gradlew build` 직접 실행, EXIT 0 |
| B2 | 해당 없음 | Java·Gradle·ArchUnit 규칙 파일 변경 없음(diff stat 문서 5개뿐) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 결정 색인 D-144 행은 새 결정이 아니라 이미 D-144 후속 기록(D-144-stage7-bundle0-docs.md:40)에 있는 W-12 추가를 색인에 옮긴 것이라 새 ADR·결정 번호가 필요 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작(코드) 없음. 테스트 삭제·건너뛰기 없음 |
| B7 | 통과(risk-ops 검토 필요) | `docs/qa/checklists.md`는 보호 경로(scripts/ci/protected-paths.txt:28, .github/CODEOWNERS:23)이고 실전 활성화 판정 점검표 D에 D9를 더하므로 실전 관련 PR이다. risk-ops 검토와 사용자 머지, 독립 QA(pending.md에 PR #258 줄 있음)가 따로 필요 |
| B8 | 통과 | diff 추가 줄에 키·계좌번호·토큰 없음(`kis-news`·`collector-secrets.json`은 문맥 줄의 파일 이름) |

### 내용 대조
- Q1 ①(색인 D-144 행): "2026-10-27 정적 경고 W-12 추가(D-144 후속 기록, 경고 목록 원본은 대응표 5장)" — D-144-stage7-bundle0-docs.md:40 후속 기록과 대응표 5장 W-12 행(tst-06-checklist-map.md:127)과 일치.
- Q1 ②(대응표 7장 B행): "16개(D-144 결정 당시 수, … 지금은 17개, 5장이 원본)" — 5장 실제 경고 ID 수: 정적 W-01~W-12 12개 + 계좌별 W-20~W-24 5개 = 17개. D-144 후속 기록 "정적 12, 계좌별 5"와도 맞음.
- Q2(점검표 D9): QA 권고가 "절차서 I1 또는 점검표 D 근거 목록" 중 하나를 요구했고 점검표 D를 골랐다. 해제 조건 "K10-a 머지와 판정 시험"이 W-12 해제 조건(tst-06-checklist-map.md:127)과 같고, "경고만, 차단 아님"으로 TST-06 규칙(차단은 TST-01/B-01뿐)을 바꾸지 않는다. D1~D8 번호를 바꾸지 않고 끝에 덧붙여 기존 참조(예: 준비 계획 "점검표 D8")가 깨지지 않음.
- qa-out-status 줄: PR #253 QA 첫 판정 "승인", 권고 Q1·Q2 내용이 qa.md 35~36행과 일치. 머지 sha 12e88baa는 origin/main 헤드와 일치.
- pending.md 줄: 요청일·문구·보고서 위치·브랜치가 지침 11장 형식에 맞음.

## 점검표 밖 발견
- (참고, 수정 요구 아님) `docs/team/stage-7-readiness-plan.md`의 표 행 ID에도 "D9"(묶음 D 9번 항목, 92행)가 있어 점검표 D9와 이름이 같다. 준비 계획은 점검표를 가리킬 때 "점검표 D8"처럼 접두어를 붙이는 관례(89행)라 혼동 위험은 낮다. 이후 문서에서 새 점검표 항목을 인용할 때 "점검표 D9"로 쓰면 된다.
- (참고) 준비 계획 K10 행이나 절차서 I1에서 점검표 D9를 가리키는 링크는 이 PR에 없다. QA Q2가 둘 중 하나만 요구했으므로 결함은 아니다.

## 판정
승인
