# strategy-researcher 재검토 3: PR #229 EDGE01 (다) 선정 엣지 일봉 사전 검사 결과·리포트

- 대상: PR #229 / 브랜치 `claude/selection-edge-daily-precheck-1e0rqa` 머리 `f2c955a` / `git diff cc5c2aa...f2c955a` 전체(결과 다섯 파일·`report.md`, `docs/strategy/selection-catalog.md`, `docs/decisions/D-125-selection-edge-precheck.md` 한 줄, 이전 검토 원문 둘)와 PR 본문(GitHub REST `pulls/229`, 2026-10-24T01:32:52Z 갱신). 고친 커밋은 `git diff 58ce0ed f2c955a`로 따로 봤다.
- 이전 원문: [strategy-researcher.md](strategy-researcher.md)(머리 `9b80ef3`, 수정 필요: M1), [strategy-researcher-2.md](strategy-researcher-2.md)(머리 `58ce0ed`, 수정 필요: M1′·M2, 참고 n4~n6)
- 기준: 지침 5·6·9·11장, role-guides.md 5·8장, `.claude/agents/strategy-researcher.md`, [D-125](../../decisions/D-125-selection-edge-precheck.md) 4장, [D-137](../../decisions/D-137-pre-open-daily-lag1.md) 6번·되돌릴 조건·사용자에게 물을 것, [LIVE02 계획](../../strategy/live02-preregistration-plan.md) 3.1·3.3
- 직접 확인한 것
  - `58ce0ed → f2c955a` 변경은 `report.md` 4줄과 검토 원문 추가뿐이다 `[사실]`(`git diff --stat`). 결과 다섯 파일·`edge.yaml`은 `9b80ef3` 이후 그대로라 첫 검토의 재계산(판정 구간 50,779건, range 0.086827, net −0.011591, 위쪽 range p = 아래쪽 net p = 0.0010, M1 비율 2.09%·W1 5.86%)이 유효하다.
  - `experiments/EXP-20261022-EDGE04/`에는 여전히 `edge.yaml`만 있다 `[사실]`(`ls`). D-125 4장 집계 단위 고정은 EDGE04 결과 전이다.
  - `origin/main`은 `cc5c2aa`로 PR 기준과 같다 `[사실]`(`git fetch` 후 `rev-parse`).
  - CI(머리 `f2c955a`): 조회 시점 Gradle build·비밀값 스캔·Playwright 진행 중, DB 스키마 대기. 코드 변경이 없는 PR이라 통계 판단에는 영향 없지만, 머지 전 CI 통과는 따로 확인해야 한다.
- 실전 주문 경로·Guard·블랙리스트·리스크 한도·보호 경로 변경은 이 PR에 없다.

## 결론

**판정: 승인** (막는 항목 없음. 참고 n7·n8)

이전 막는 항목 M1′(PR 본문)과 M2(시가 = 고가 방향 서술)가 모두 고쳐졌고, 참고 n4~n6도 반영됐다. 리포트·카탈로그·D-125·PR 본문이 한 틀(EDGE01은 D-137 이후 참고 검사, 원래 판 탈락, LIVE02 C1·C2 판정은 EDGE04로 결과 전까지 미정)로 맞는다. 사전 등록 준수, 리포트 필수 항목, 비용 차감 성과로만 판정, 실패 실험 보존, 검증 구간 재사용 금지 모두 첫 검토 S1~S5 그대로 통과다. EDGE01 탈락(E3) 판정은 대조군 999개 가장 나쁜 net보다 0.38%p 더 나쁘고 11구간 모두 같은 방향이라 흔들리지 않는다.

## 이전 항목 처리

| 항목 | 처리 | 근거 |
|---|---|---|
| M1′ PR 본문을 리포트와 같은 틀로 | 해결 | 본문 After 둘째 문단 "EDGE01은 D-137 6번에 따라 참고 검사 … C1·C2는 EDGE04 결과 전까지 미정 … 배치표 5번 녹화 실험 후보에서 뺍니다", How에 D-125 4장 집계 단위 고정(EDGE04 결과 전), 검토 원문 링크·판정 줄(지침 11장) |
| M2 시가 = 고가 문단의 방향 결론 | 해결 | `report.md` 26행: 사기 어려운 날은 시가 = 상한가인 날로 구분, 방향은 비율만이 아니라 그날 평균 `net`에 달림, "방향도 크기도 확인하지 못했다", 판정은 사전 등록대로 탈락, EDGE04에도 같은 한계 |
| M2 후속(시가 = 고가·시가 = 상한가 왕복 net을 후보·대조군 따로 남기기) | 일부(n7) | 리포트는 한계만 적고 후속 목록 줄은 없다 |
| n4 집계 단위를 EDGE01 결과 뒤에 정했다는 점 | 해결 | `report.md` 34행 "EDGE01 결과를 본 뒤 정한 것이라 EDGE01에는 사전 등록 효력이 없고, EDGE04 이후에만 사전 등록이다" |
| n5 대안 (c) 다시 묻는 조건 | 해결 | `report.md` 41행 `[추론]` 표시, "원래 판을 LIVE02에 넣으려면 새 실험 ID의 새 사전 검사(D-137 되돌릴 조건)" |
| n6 배치표 5번·LIVE02 계획 3.1 | 해결 | `report.md` 33행, 3.1 표 정리는 EDGE04 결과 PR 후속으로 명시 |

## 참고(막지 않음)

- **n7 시가 근사 진단 통계를 후속 목록에 둔다.** 이전 M2의 둘째 요청(다음 코드 후속에서 `results.md` 3장에 시가 = 고가 왕복과 시가 = 상한가 왕복의 평균 `net`을 후보·대조군 따로 남김, 판정에는 쓰지 않는 기술 통계)이 리포트 4장·D-137 후속·runbook 어디에도 없다 `[사실]`(`grep "시가 = 상한가"`). EDGE04는 이미 등록돼 있어 코드를 바꿀 수 없지만, EDGE05·06은 아직 등록 전이라 그 전에 넣으면 같은 질문에 결과로 답할 수 있다. EDGE04가 E3 경계 근처로 나올 때 이 한계가 실제로 문제 된다 `[추론]`. EDGE04 결과 PR이나 EDGE05·06 등록 PR에서 후속 한 줄로 받으면 된다. 이 PR의 결론에는 영향 없다.
- **n8 표현.** `report.md` 26행 "실제로 사기 어려운 날은 그중 시가 = 상한가인 날뿐"은 일봉 근사 안에서의 말이다. 실시간에는 VI 발동·호가 공백으로 상한가가 아닌 날에도 시가 체결이 어긋날 수 있다 `[추론]`. 녹화 실험·LIVE02가 맡을 범위라 고치지 않아도 된다. 34행의 괄호 두 개 연속은 문장으로 풀면 읽기 쉽다.
- 이 결과는 투자 자문이 아니다.

판정: 승인
