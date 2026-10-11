# architect 재검토 5차: Q-042 ② 사용자 결정 D-059: 토스 토큰은 실전 엔진만 소유
- 대상: PR #47 / 커밋 66ce88b(검토 원문 커밋 40add62 위에서 확인) / `git diff origin/main...HEAD` 전체(origin/main = e8cc529). 문서만 9개(검토 원문 제외, architect-4.md 목록과 같음). 직전 판정(architect-5.md 정정, 475e33b 기준 수정 필요) 뒤 바뀐 것은 66ce88b: `docs/adr/ADR-003-market-data.md` 1행, `docs/team/broker-and-fault-design.md` 1행, `docs/reviews/pr-47/risk-ops-4.md` 추가
- 점검표: B
- 직접 실행한 것: 별도 복제본에서 `git pull --rebase` 후 `./gradlew build --no-daemon` → BUILD SUCCESSFUL in 17s, 종료 코드 0(문서만 변경, 5dc6c44에서 전체 빌드 1m 48s 성공). `git diff --check origin/main...HEAD` 공백 오류 없음. 기획서 변경 이력 대조: main의 `^| 2026` 행 전부가 HEAD에 그대로 있고 추가는 v14.14 한 행뿐(`diff` 결과 `0a1 >` 한 줄). 저장소 전체(`docs/reviews/` 제외)에서 "감시는 토스를" 류 옛 문구 검색 0건

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build` 성공(HEAD 40add62 = 66ce88b + 검토 원문) |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 전부 `docs/`) |
| B3 | 통과 | 직전 지적 해소: `docs/adr/ADR-003-market-data.md:203`이 "게이트웨이·실험실은 토스를 호출하지 않고, 감시도 평상시에는 호출하지 않는다(엔진 정지 시 감시 긴급 경로는 Q-048)"로 바뀌어 D-059 결정 2(`docs/decisions/D-059-toss-token-owner-engine.md:18`), 결정 로그 D-059 행, 기획서 v14.14 이력·MON-02와 같다. 설정 키·ADR·기획서 갱신은 이전 차수와 같음. v14.12 이력 행 유지 |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 데이터 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·비활성화 없음 |
| B7 | 통과(risk-ops 검토 필요) | 장애 테스트 1-0·1-T·1-TR·1-TK를 바꾸는 실전 관련 PR(사용자 머지). 66ce88b는 1-TR 시행 선행 조건에 R7(아주 짧은 토큰 수명 처리)을 더함(`docs/team/broker-and-fault-design.md:208`). 마지막 risk-ops 원문 `risk-ops-4.md`는 5dc6c44 기준 수정 필요이고 66ce88b가 그 지적을 반영한 커밋이다. 66ce88b 뒤 risk-ops 재검토 원문은 이 검토 시점에 없다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 값 없음 |

## 점검표 밖 발견
- N9(권고, 판정 사유 아님). architect-5 정정에서 함께 적은 "생성 금지 강제 문장에 감시 긴급 경로 예외 단서"는 반영되지 않았다(`ADR-003-market-data.md:203` 뒷문장, D-059 결정 3, MD-02 인수 기준, 1-0이 모두 "실전 엔진 밖 토스 호출기 생성 불가"). 판정 사유로 두지 않는 이유: Q-048은 "7단계 토스 연결 전 사용자 결정"(`docs/decisions/README.md:82`)이고 강제 규칙은 그 토스 연결 PR에서 처음 구현되므로, 구현 시점에는 Q-048 결과가 이미 있다. 같은 문장이 네 곳에 일관되게 있어 문서끼리 모순은 아니다. Q-048이 추천안(감시가 엔진 사망 뒤 토큰 발급)으로 결정되면 그 결정 PR에서 네 곳을 함께 고친다.
- 1-TR 칸의 하네스 위치 이중 서술은 4차 때와 같음(모순 아님).
- 확인 범위 밖: D-059의 사용자 결정 카드와 Q-042 ① 사용자 확인 원문은 저장소 밖 기록이라 대조하지 않았다.

## 판정
승인
