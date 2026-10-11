# architect 재검토 3차: Q-042 ② 사용자 결정 D-059: 토스 토큰은 실전 엔진만 소유
- 대상: PR #47 / 커밋 5dc6c44(main e8cc529 병합 포함) / `git diff origin/main...HEAD` 전체(origin/main = e8cc529). 문서만 9개(검토 원문 제외): `docs/adr/ADR-003-market-data.md`, `docs/config/config-keys.md`, `docs/decisions/D-053-toss-rest-read-only-client.md`, `docs/decisions/D-059-toss-token-owner-engine.md`, `docs/decisions/README.md`, `docs/research/toss-openapi-spec.md`, `docs/spec/autotrade-spec.md`, `docs/team/broker-and-fault-design.md`, `docs/team/overseas-design.md`
- 점검표: B
- 직접 실행한 것: 별도 복제본에서 `git pull --rebase` 후 `./gradlew build --no-daemon` → BUILD SUCCESSFUL in 1m 48s, 종료 코드 0. `git diff --check origin/main...HEAD` 공백 오류 없음. 충돌 표시(`<<<<<<<`) 잔존 없음(이 PR 파일). 병합 손실 확인: `git show origin/main:docs/spec/autotrade-spec.md`와 HEAD 비교, `git diff origin/main HEAD --numstat`. 독립 QA 원문 `docs/reviews/pr-47/qa.md`의 F1·F2·R1~R4 반영 대조

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build` 성공(커밋 5dc6c44, main 병합 후) |
| B2 | 해당 없음 | 이 PR의 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(main에서 온 D-054 코드는 PR #42 등에서 이미 검토·머지됨, 이 PR diff에 없음) |
| B3 | 실패 | 계약 문서 갱신 자체(config-keys `:15`, ADR-003 `:141`·`:166`·`:200`, MD-02·MON-02)는 맞다. 그러나 병합 과정에서 **기획서 변경 이력의 v14.12 행이 지워졌다**. `git show origin/main:docs/spec/autotrade-spec.md` 20행은 `\| 2026-10-11 \| v14.12 \| 사용자 결정 D-056(2026-10-10T14:15:45Z, Q-043~Q-045 모두 추천안) 반영: … \| OVS-09, OVS-10, OVS-11, STR-16 \|`인데, HEAD에는 `\| v14.12 \|` 행이 0건이고 `git diff origin/main...HEAD -- docs/spec`이 이 행을 `-`로 지우고 v14.14 행을 `+`로 넣는다. 새 v14.14 행 문구는 "v14.12는 PR #38(D-056)이 main에 반영"이라고 쓰면서 그 이력을 지운다. 기획서는 단일 원본이고 변경 이력은 요구사항 ID(OVS-09~11, STR-16) 추적의 근거라 손실이다(독립 QA 위험 시나리오 1이 경고한 바로 그 경우. 본문 OVS-09~11 행 `:285-287`과 결정 로그 D-056·Q-046 행은 남아 있음). 고칠 것: v14.12 행을 main 문구 그대로 v14.14 행 아래(v14.11 위)에 되살린다 |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 데이터 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·비활성화 없음 |
| B7 | 통과(risk-ops 검토 필요) | 5dc6c44가 장애 테스트를 다시 바꾼다: 1-0 범위를 "실전 엔진 밖 모든 프로세스"로 넓힘(`docs/team/broker-and-fault-design.md` 1-0 줄), 1-T를 D-059 동안 해당 없음으로 한정하고 기존 조건을 "D-059가 아닐 때만"으로 묶음(`:207`), 새 시행 항목 1-TR 토큰 무효화 주입 추가(`:208`), 1-TK 해당 없음 연동. 실전 관련 PR(사용자 머지). `risk-ops-3.md`(승인)는 7fa3111 기준이라 1-TR 추가 뒤 risk-ops 검토 원문은 아직 없다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 값 없음 |

## 이전 지적·독립 QA 지적 반영 확인
- QA F1 main 충돌: 결정 로그는 정상 병합(D-056 `:61`, Q-046 `:80` 유지, Q-047·Q-048 추가, Q-042 닫힌 표). **기획서 이력은 위 B3대로 v14.12 행 손실.**
- QA F1 overseas-design 6장: 반영(표 2순위 단서와 D-059 문단, 미국 장외 세션 `[추론]`과 확인 방법). D-059 위험 칸에도 같은 항목.
- QA F1 MON-02: 반영(`docs/spec/autotrade-spec.md:262` Q-048 단서).
- QA F2 1-T 모순(내 2차 권고 N5를 판정 사유로 올린 것): 반영. 앞뒤 문장이 "D-059가 유지되는 동안"/"D-059가 아닐 때만"으로 갈려 같은 상황에 두 판정이 나오지 않는다.
- QA F2 대체 확인 범위·토큰 무효화 주입: 반영(1-0 확장, 1-TR).
- QA R1(Q-048 조건), R2(Q-018 D-059 영향), R3(1-0 문구), R4(Q-042 닫힘 표시, 내 N6): 반영.
- 판정을 내렸던 내 2차 검토에서 N2(overseas-design)·N5(1-T 문장 공존)를 "판정 사유 아님"으로 넘긴 것은 잘못이었다. 해외 기준 문서와 실전 활성화 합격 기준의 모순은 판정 사유였다.

## 점검표 밖 발견
- N7(권고, 다음 연결 PR 조건). 1-TR의 시험 하네스는 "실전 엔진 밖에서" 같은 Client ID로 토큰을 받는다. 한편 MD-02 인수 기준과 D-059 결정 3은 "실전 엔진 밖에서 토스 호출기를 만들 수 없게" ArchUnit·설정 검사로 강제한다. 하네스를 운영 프로세스 모듈 밖(테스트 전용 소스나 별도 도구)에 두어야 두 규칙이 함께 선다. 연결 PR에서 강제 규칙의 적용 범위(운영 모듈 `autotrade-app-{gateway,lab,watchdog,collector}`)와 하네스 위치를 함께 정할 것.
- N8(권고). 1-TR 합격은 "토큰 주인 정책(R1: 즉시 재발급)"을 전제로 하는데 현재 `TossRestClient` 동작은 D-053 ④(`token-revoked`면 10분 쉼)다. R1은 연결 PR 전 조건이라 모순은 아니지만, 1-TR은 R1 구현 뒤에만 시행 가능하다는 한 줄을 1-TR 시행 조건에 두면 오판이 없다.
- 확인 범위 밖: D-059의 사용자 결정 카드와 Q-042 ① 사용자 확인 원문은 저장소 밖 기록이라 대조하지 않았다.

## 판정
수정 필요(B3: main 병합에서 기획서 변경 이력 v14.12 행(D-056, OVS-09~11·STR-16) 삭제, main 문구 그대로 복원)
