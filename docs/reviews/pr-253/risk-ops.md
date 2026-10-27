# risk-ops 검토: PR #253 PR #246 독립 QA 권고 Q1~Q3 반영(장애표 #13 VI 마감, 대응표 W-12), QA 처리 기록 [실전 관련]
- 대상: PR #253 / 브랜치 `claude/project-thread-npk3vh` 헤드 `8ba43548` / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base `44480c75`). 바뀐 파일 4개: `docs/qa-out-status.md` +1, `docs/team/broker-and-fault-design.md` ±1(6장 #13), `docs/team/stage-7-readiness-plan.md` ±2(B0, K10), `docs/team/tst-06-checklist-map.md` +5/-4(머리말, 1장 4번, I-8, W-04, W-12 신규). 코드·설정·테스트 변경 0
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree `/tmp/wt-risk-253`(헤드 `8ba43548`)에서 `./gradlew build` → BUILD SUCCESSFUL(EXIT 0, 6분 8초). 테스트 결과 XML 집계: `:autotrade-risk:test` 57건, `:autotrade-architecture-test:test` 47건, `:autotrade-app-engine:test` 109건, 모두 실패 0·오류 0·건너뜀 0
  - `scripts/ci/protected-paths.txt` 대조: 4개 파일 모두 보호 경로 아님
  - diff `+` 줄 비밀값 패턴 검색(`appkey`, `secret[:=]`, 8자리-2자리 숫자, 32자 이상 영숫자): 0건
  - `git merge-tree --write-tree HEAD origin/main`: 충돌 없음(main은 merge-base 뒤 #245 `df22830b`가 들어왔고, 같은 `stage-7-readiness-plan.md`의 D11·D12 행만 바꿔 이 PR의 B0·K10 행과 겹치지 않음)
  - 근거 대조: 장애표 #13 새 구절을 기획서 RSK-13(`docs/spec/autotrade-spec.md:250` "늘어난 상한이 매도 가능 판정 마감 시각(…)을 넘는 VI 종목은 그 실행에서 매도 불가다")와 D-140 ④(`docs/decisions/D-140-stage7-panic-and-first-broker.md:19`)에 대조 → 같은 규칙. PR #246 QA 권고 Q1~Q3(`docs/reviews/pr-246/qa.md:44-46`) 대조. ADR-015·D-141 파일 있음, #239 머지 `c1972a03` 확인. K10-a 정의 `docs/research/k10-os-credential-store-review.md:99` 대조

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard 코드·설정·테스트 변경 0(diff 4개 문서). 문서 문구도 Guard 기본 OFF·재기동 OFF를 언급하거나 바꾸지 않음. 빌드에서 Guard 관련 테스트 포함 `:autotrade-risk:test` 57건 통과 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 변경 0. `:autotrade-architecture-test:test` 47건 통과 |
| A3 | 해당 없음 | 증권사 라우팅 변경 0. 바뀐 문구에 주문 증권사 전환 언급 없음 |
| A4 | 해당 없음 | 타임아웃·재전송 규칙 변경 0 |
| A5 | 해당 없음 | 멱등 키 변경 0. I-7·W-03 행은 바뀌지 않음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 규칙 변경 0. #13 행 PANIC 문구는 대상 범위(자동 매매 포지션만)를 건드리지 않음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷 변경 0 |
| A8 | 해당 없음 | 프로세스 분리·정지 순서 변경 0 |
| A9 | 통과 | #13 새 구절은 VI 종목도 마감 시각을 넘기면 "그 실행에서 매도 불가"로 두어, 매도 불가 종목은 서버 측 손절 취소를 하지 않는 RSK-13 규칙(`autotrade-spec.md:250` "매도 불가 종목은 ③ 서버 측 손절 취소를 하지 않고")의 범위에 들어간다. 보호를 줄이는 문장 없음. 오히려 #13만 읽은 구현자가 마감 뒤 VI 매도를 내 서버 측 손절 재설정 시간을 잃는 위험(PR #246 QA 시나리오 1)을 줄임 |
| A10 | 통과 | diff `+` 줄 비밀값 패턴 0건. W-12 문구는 비밀 파일 권한 검사를 말할 뿐 경로·값 예시 없음 |
| A11 | 해당 없음 | 보호 테스트·고정 값 변경 0. 새 동작(코드) 없음 |
| A12 | 통과 | 장애표 #13 변경은 기획서 RSK-13·D-140 ④에 이미 확정된 규칙을 옮긴 것이라 대응표 행 수(17종)·해외 O1~O8·장애 테스트 8종의 전제를 바꾸지 않음. RSK-13 인수 조건 "마감 시각 초과 시 매도 불가 테스트"(`autotrade-spec.md:250` 인수 조건 열)가 이미 있어 테스트 목록 변경도 필요 없음 |
| A13 | 해당 없음 | 시장별 규칙·시간대·통화 변경 0. #13 문구의 LULD는 기존 문구 그대로 |
| A14 | 통과(권고 R1·R2) | #13 문구가 RSK-13·D-140 ④와 일치. I-8·W-04·B0 행이 ADR-015·D-141(#239 머지)과 일치하고 상태 "부분"·W-04 표시는 구현이 없으므로 맞음. W-12 추가는 경고(차단 아님)라 TST-06 "차단 조건은 TST-01만"(대응표 1장 5번)을 지킴. 다만 D-144 ③ "원인별 16개(정적 W-01~W-11…)"와 준비 계획 K9 행 "경고 W-01~W-11·W-20~W-24"는 갱신되지 않음(R1). `qa-out-status.md` 새 줄의 보고서 커밋 `0c3a5590`은 저장소에 없고 같은 제목 커밋은 `0c3a5592`(R2) |

## 점검표 밖 발견
- 실전 안전에 영향을 주는 발견 없음. 아래는 기록 정합성 권고다(머지를 막지 않음).
- **R1** W-12 추가로 정적 경고가 12개, 전체 17개가 됐지만 D-144 ③(`docs/decisions/D-144-stage7-bundle0-docs.md:14` "원인별 16개(정적 W-01~W-11, 계좌별 W-20~W-24)")과 준비 계획 K9 행(`docs/team/stage-7-readiness-plan.md:77` "경고 W-01~W-11·W-20~W-24")이 그대로다. 묶음 H(ui-dev)가 D-144나 K9 행만 보고 화면 경고 ID를 만들면 W-12가 빠질 수 있다 [추론]. 대응표 5장이 원본이라고 적혀 있으므로(`tst-06-checklist-map.md:112`) 규칙 충돌은 아니다. K9 행에 W-12를 더하고, D-144 ③에는 결정 로그 규칙대로 "대응표 갱신으로 W-12 추가(PR #253, PR #246 QA Q3)" 메모를 남기거나 다음 결정 번호에 기록하기를 권한다. 대응표 7장 권고(화면 경고 ID와 문서 5장 대조 시험)가 묶음 H에 들어가면 이 위험은 닫힌다.
- **R2** `docs/qa-out-status.md` 새 줄 "보고서 0c3a5590"은 `git rev-parse`로 찾을 수 없고, `PR #246 독립 QA 검토 원문: 승인` 커밋은 `0c3a5592`다. 오타로 보인다 [추론]. 처리 기록의 추적용 값이므로 다음 기록 PR에서 고치기를 권한다.
- 참고: 이 PR은 보호 경로를 바꾸지 않지만 `broker-and-fault-design.md` 6장 장애 대응표 행을 바꾸므로 지침 10장 "장애 대응을 건드리는 PR"에 해당한다. 규칙 내용은 기존 확정 결정(D-140 ④)의 옮겨 적기이고 새 규칙은 없다.

## 판정
승인 (실전 관련 PR: 장애 대응표 #13 문구 변경으로 지침 10장 실전 항목에 해당, 사용자가 직접 확인하고 머지. 머지 전 독립 QA. 권고 R1·R2는 머지를 막지 않음)
