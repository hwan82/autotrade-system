# architect 재검토 2차: Q-042 ② 사용자 결정 D-059: 토스 토큰은 실전 엔진만 소유
- 대상: PR #47 / 커밋 7fa3111 / `git diff origin/main...HEAD` 전체(52eb906 … 7fa3111). 문서만: `docs/adr/ADR-003-market-data.md`, `docs/config/config-keys.md`, `docs/decisions/D-059-toss-token-owner-engine.md`, `docs/decisions/README.md`, `docs/research/toss-openapi-spec.md`, `docs/spec/autotrade-spec.md`, `docs/team/broker-and-fault-design.md`, `docs/reviews/pr-47/*`
- 점검표: B
- 직접 실행한 것: 별도 복제본에서 `git pull --rebase` 후 `./gradlew build --no-daemon` → BUILD SUCCESSFUL in 16s, 종료 코드 0. `git diff --check origin/main...HEAD` 공백 오류 없음. 58f4c59..7fa3111 diff 대조, 저장소 전체에서 `Q-042` 참조 검색(`docs/reviews/` 제외)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build` 성공(커밋 7fa3111). 코드 변경 없음 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 전부 `docs/`) |
| B3 | 통과 | 설정 키 문서(`docs/config/config-keys.md:15`)는 2차 검토 때와 같이 D-059 반영. 이번 커밋은 Q-042 ①을 `[가정]`에서 `[사실, 사용자 확인]`으로 바꾸고 되돌릴 조건을 "토스가 Client ID를 2개 이상 발급하게 되면"으로 고침(`D-059-toss-token-owner-engine.md:13`, 되돌릴 조건 줄; `ADR-003-market-data.md:166`; `docs/decisions/README.md` D-059 행). 계약 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 데이터 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·비활성화 없음. 연결 PR 테스트 2종은 MD-02 인수 기준에 그대로(`docs/spec/autotrade-spec.md:118`) |
| B7 | 통과(risk-ops 검토 필요) | 이 PR은 장애 테스트 1-T 기준(`docs/team/broker-and-fault-design.md:207`, Q-047)과 감시 긴급 경로 서술(Q-048)을 바꾸는 실전 관련 PR이다(사용자 머지). risk-ops 검토 원문 `docs/reviews/pr-47/risk-ops-2.md`(승인)에 이어 7fa3111 뒤 `docs/reviews/pr-47/risk-ops-3.md`(커밋 361d45c, 판정 "승인")가 있다(이 원문을 push 직전 pull로 받아 확인). A 점검표 판정은 risk-ops 몫이고, 사용자 머지 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 값 없음. 인용된 사용자 문장("설정 화면에서 Client ID를 하나 더 만들 수 없습니다.")에도 비밀값 없음 |

## 이번 커밋 확인
- Q-042가 미확정 표에서 빠지고 닫힌 미확정 표(`docs/decisions/README.md:81` 아래, `:109`)로 옮겨짐. 결과 칸에 ①(사실, 확인 시각)과 ②(D-059)가 모두 있음.
- 기획서 v14.14 이력(`docs/spec/autotrade-spec.md:20`)에 Q-042 전체 닫음 반영. 버전 번호 변화 없음(같은 PR 안 수정).
- `docs/research/toss-openapi-spec.md:159` 표에 ⓐ 불가·ⓑ 결정 연결.
- D-059 결정 내용은 바뀌지 않았고, "Client ID가 하나인 동안" 문구(설정 키·ADR-003·broker-and-fault-design·MD-02)는 되돌릴 조건과 맞는 표현이라 그대로 두어도 된다.

## 점검표 밖 발견
- N6(권고). `docs/adr/ADR-003-market-data.md:200`의 "미결 항목은 Q-042."와 `docs/decisions/D-053-toss-rest-read-only-client.md:13`의 "토큰을 받을 프로세스를 정한 뒤(Q-042) 연결한다"는 이제 닫힌 Q를 가리킨다. 기록 문장이라 틀린 것은 아니나 "(→ D-059로 닫힘)"을 붙이면 다음 연결 PR 작성자가 덜 헤맨다. 판정 사유 아님.
- 2차 권고 N4(Q-047 머지=결정임을 사용자에게 명시), N5(1-T 칸의 기존 문장 앞 한정어)는 이번 커밋에 반영되지 않았다. 판정 사유 아님, 유지.
- 확인 범위 밖: D-059의 사용자 결정 카드(09:23:38Z)와 Q-042 ① 사용자 확인(09:37:41Z) 원문은 저장소 밖 기록이라 대조하지 않았다.

## 판정
승인
