# architect 검토: PR #246 독립 QA 권고 Q1~Q3 반영, QA 처리 기록
- 대상: PR #253 / 커밋 `8ba43548`(브랜치 `claude/project-thread-npk3vh`) / `git diff origin/main...HEAD` 전체(merge-base `44480c75` = PR #246 squash 머지). 바뀐 파일 4개, 모두 `docs/`: `docs/qa-out-status.md` +1, `docs/team/broker-and-fault-design.md` ±1, `docs/team/stage-7-readiness-plan.md` ±2, `docs/team/tst-06-checklist-map.md` +5 −4
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (HEAD `8ba43548`를 별도 worktree `/tmp/wt-arch-253`에 꺼내 실행): **BUILD SUCCESSFUL in 6m 8s**, 138 actionable tasks 모두 실행, EXIT 0, 로그에 `FAILED` 0건. 코드 변경이 없으므로 결과는 main과 같아야 하고 그대로다.
  - `git diff origin/main...HEAD` 전문을 읽음. 코드·설정·테스트·보호 경로(`scripts/ci/protected-paths.txt`) 변경 없음.
  - QA 원문 `docs/reviews/pr-246/qa.md` 44~53행의 Q1~Q5·R1·R2와 diff 대조.
  - Q1 문구를 기획서 원문과 대조: `docs/spec/autotrade-spec.md:250` RSK-13 "늘어난 상한이 매도 가능 판정 마감 시각(…)을 넘는 VI 종목은 그 실행에서 매도 불가다"와 새 장애표 #13 구절(`broker-and-fault-design.md:172`)이 뜻이 같음.
  - Q2 근거 대조: `docs/adr/ADR-015-real-engine-store.md`·`docs/decisions/D-141-real-engine-store.md` 존재, main `c1972a03`이 PR #239 머지, `docs/decisions/README.md:178` Q-062 사용자 결정. ADR-015 5장(검증, 196행)·6장(구현 순서)과 1장 7·13행이 B1·B4 구현을 뒤로 넘김 → W-04 해제 조건 "ADR-015 저장소 구현(B1·B4, 5장 실패 테스트), B3 전이표 시험"과 맞음.
  - 추가된 상대 링크(`../adr/ADR-015-real-engine-store.md`, `tst-06-checklist-map.md`, `../research/k10-os-credential-store-review.md`) 모두 존재.
  - `docs/qa-out-status.md` 새 줄의 해시 대조: `44480c75`(PR #246 squash), `fbbf9284`(main 병합), `3a569f12`(QA 대상 헤드)는 저장소에 있음. "보고서 `0c3a5590`"은 저장소에 없는 객체(`git cat-file -t 0c3a5590` → Not a valid object name). QA 원문을 넣은 커밋은 `0c3a5592`(`docs/reviews/pr-246/qa.md`만 추가). 같은 표의 다른 줄(예: PR #238 "보고서 2a17ab5f" = `pr-238/qa.md` 추가 커밋)과 같은 규칙이면 `0c3a5592`가 맞다.
  - 정적 경고 수 대조: `tst-06-checklist-map.md` 5.2에 W-01~W-12(12개), 1장 4번 "12개"와 맞음. `stage-7-readiness-plan.md:77` K9 행은 여전히 "경고 W-01~W-11·W-20~W-24".

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(EXIT 0, 138 tasks) |
| B2 | 해당 없음 | 빌드 스크립트·모듈·ArchUnit 규칙 변경 없음(diff 4개 파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. W-12는 화면 경고 ID 목록(문서 5.2가 원본, 6장 갱신 규칙)이고 계약 아님 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·건너뜀 없음. W-12 해제 조건에 K10-a 판정 시험이 적혀 있음(`tst-06-checklist-map.md:127`) |
| B7 | risk-ops 검토 필요 | 장애 대응 문서 `broker-and-fault-design.md` 장애표 #13(PANIC 매도 VI 규칙) 문구를 바꿈. 규칙은 RSK-13·D-140 ④와 같아 보호를 줄이지 않지만, 지침 10장 "장애 대응"을 건드리는 PR이고 PR #246도 같은 행 때문에 실전 관련 PR로 다뤘다. TST-06 경고 목록 추가(W-12)도 risk-ops 문서(6장 "risk-ops가 갱신") 변경이다 |
| B8 | 통과 | diff `+` 줄에 키·계좌번호·토큰 형태 문자열 없음(커밋 해시·링크만) |

## 점검표 밖 발견
1. **[수정 필요] `docs/qa-out-status.md` 새 줄의 보고서 해시 오기**: "보고서 0c3a5590"은 없는 객체다. QA 원문 커밋은 `0c3a5592`. 처리 기록이 추적용이므로 해시가 틀리면 나중에 원문 시점을 찾지 못한다.
2. **[수정 필요] 준비 계획 K9 행이 W-12를 빠뜨림**: `stage-7-readiness-plan.md:77` "경고 W-01~W-11·W-20~W-24"는 이 PR 뒤 대응표(W-01~W-12)와 어긋난다. 같은 PR이 K10 행(78행)에는 W-12를 적었으므로 K9 행도 "W-01~W-12"로 맞추는 한 단어 수정이다.
3. [참고] D-144 ③과 `docs/decisions/README.md` D-144 줄, 대응표 7장 대안 B(157행)는 "경고 16개"로 남는다. 대응표 1장 4번·W-12 행이 "D-144 ③의 16개 묶음 밖 추가 1개, 원본 밖"이라고 밝혔고 대응표가 경고 목록 원본(5.2 112행)이라 모순은 아니다. 다만 경고 추가는 사용자가 머지로 확정한 D-144 ③ 묶음을 넓히는 것이므로, 이 PR이 실전 관련으로 사용자 머지를 거치면 그 머지를 확정으로 보고, 아니면 결정 로그에 한 줄 남기기를 권한다(지침 2장 "뒤집을 때도 새 번호", 여기서는 뒤집기가 아니라 추가라 권고).
4. [참고] 이 PR이 실전 관련 PR로 분류되면(B7) 지침 11장대로 `docs/qa/pending.md` 줄이 이 브랜치에 있어야 하는데 diff에 없다. 분류와 QA 요청 여부는 risk-ops 검토 뒤 메인이 정한다.
5. [참고] `docs/decisions/D-141-real-engine-store.md:4` 상태가 머지 뒤에도 "제안"이다. 이 PR 범위 밖이고 I-8 근거는 머지 사실(`c1972a03`)로 맞으므로 다음 결정 로그 정리 때 고칠 일.
6. Q4·Q5는 qa-out-status에 "K10-a PR 때"로 넘김을 적었다(K10 문서 대상, 이 PR에서 고치지 않아도 됨). R1(사용자 확인)·R2(충돌)는 PR #246 머지로 끝남.

## 판정
수정 필요(1. qa-out-status 보고서 해시 `0c3a5590` → `0c3a5592`, 2. 준비 계획 K9 행 경고 범위에 W-12 포함). B7: risk-ops 검토 필요

## 재검토 (2026-10-27)
- 대상: PR #253 / 헤드 `24eb2cda`(브랜치 `claude/project-thread-npk3vh`) / `git diff origin/main...HEAD` 전체(merge-base 그대로 `44480c75`). 바뀐 파일 8개, 모두 `docs/`: 1차 4개에 더해 `docs/decisions/D-144-stage7-bundle0-docs.md` +4, `docs/qa/pending.md` +1, 검토 원문 `docs/reviews/pr-253/{architect,risk-ops}.md`
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (HEAD `24eb2cda`를 별도 worktree `/tmp/wt-arch-253b`에 꺼내 실행): **BUILD SUCCESSFUL in 3m 54s**, 138 actionable tasks 모두 실행, EXIT 0, 로그에 `FAILED` 0건.
  - 고친 커밋 `24eb2cda` 전문과 `git diff origin/main...HEAD` 전문을 읽음. 코드·설정·테스트·보호 경로 변경 없음.
  - 1차 수정 필요 1: `docs/qa-out-status.md:113` "보고서 0c3a5592". `git cat-file -t 0c3a5592` → commit, `PR #246 독립 QA 검토 원문: 승인`, 바뀐 파일 `docs/reviews/pr-246/qa.md`만 → **해소**.
  - 1차 수정 필요 2: `docs/team/stage-7-readiness-plan.md:77` K9 행 "경고 W-01~W-12·W-20~W-24(W-12는 2026-10-27 QA Q3로 추가)". 대응표 5.2의 W-01~W-12(`tst-06-checklist-map.md:127` W-12), 계좌별 W-20~W-24(135~139행)와 맞음 → **해소**.
  - 1차 참고 3: D-144에 `## 후속 기록` 절 추가(`D-144-stage7-bundle0-docs.md:38-40`). ③ 본문은 고치지 않고 "16개는 결정 시점 수, 원본은 대응표 5장(정적 12, 계좌별 5)"으로 적음. 12·5는 대응표 5.2와 일치. ⑤ 서술(장애표 #13 VI 마감 시각 초과 매도 불가)도 `broker-and-fault-design.md` #13 새 구절과 같다. 결정을 뒤집지 않는 추가 기록이고, D-144 상태가 "사용자 머지로 확정"이라 이 PR 사용자 머지가 확정 절차가 된다 → 받아들임. `docs/decisions/README.md` D-144 줄은 그대로지만 상세 파일이 원본이라 모순 아님.
  - 1차 참고 4: `docs/qa/pending.md` 끝에 "PR #253 검토해줘 | docs/reviews/pr-253/qa.md | 실전 관련 PR(문서만, 코드 무변경) … 브랜치 `claude/project-thread-npk3vh`" 줄 추가. 표 4열 형식 맞음. 요청 칸에 결론·안심 문구 없음 → 해소.
  - main과의 병합 가능성: `git merge-tree --write-tree origin/main HEAD`(origin/main `f085478f`) → `docs/qa-out-status.md`, `docs/qa/pending.md` 두 파일 **충돌**. main 쪽은 PR #245·#243 줄을 표 끝에 더했고 이 PR도 같은 위치에 한 줄씩 더해 생긴 표 끝 추가 충돌이다. `stage-7-readiness-plan.md`는 자동 병합(main은 D11·D12·I1 행, 이 PR은 K9 행).
  - diff `+` 줄 비밀값 패턴 검색(`appkey`, `secret`, `token`, `password`, 계좌번호 형태): 문서 서술 단어 외 값 0건.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(EXIT 0, 138 tasks) |
| B2 | 해당 없음 | 빌드 스크립트·모듈·ArchUnit 규칙 변경 없음(diff 8개 파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. W-12는 화면 경고 ID 목록(대응표 5.2 원본)이고 계약 아님 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·건너뜀 없음 |
| B7 | risk-ops 검토 필요 | 장애 대응표 #13 문구 변경(지침 10장 "장애 대응"). risk-ops 원문 `docs/reviews/pr-253/risk-ops.md` 판정 "승인"(실전 관련 PR, 사용자 머지, 머지 전 독립 QA). 그 원문의 권고 R1·R2는 위 해소 내용과 같다. 고친 커밋 뒤 risk-ops 재검토를 받을지는 메인이 정한다(바뀐 줄은 기록 정합성뿐) |
| B8 | 통과 | diff `+` 줄에 키·계좌번호·토큰 값 없음(커밋 해시·링크만) |

## 점검표 밖 발견 (재검토)
1. 1차 수정 필요 1·2 해소, 참고 3·4 반영(위).
2. **[머지 전 조건] main과 표 끝 충돌 2건**(`docs/qa-out-status.md`, `docs/qa/pending.md`). 내용 충돌이 아니라 양쪽 줄을 모두 남기면 되는 충돌이다(PR #246·#245 때와 같은 방식). 사용자 머지 전에 main 병합으로 풀어야 하고, 병합 뒤 diff가 두 표에 줄 추가만인지 확인하면 된다. 문서 내용 판정에는 영향 없음.
3. [참고] 1차 참고 5(`D-141-real-engine-store.md:4` 상태 "제안")는 여전히 범위 밖, 다음 결정 로그 정리 때.
4. [참고] QA 대기 줄의 보고서 위치 `docs/reviews/pr-253/qa.md`는 아직 없다(정상, QA가 쓸 파일).

## 판정 (재검토)
승인. B7: risk-ops 검토 필요(실전 관련 PR, 사용자 직접 머지, 머지 전 독립 QA). 머지 전 main 병합으로 표 끝 충돌 2건(`docs/qa-out-status.md`, `docs/qa/pending.md`)을 풀 것.
