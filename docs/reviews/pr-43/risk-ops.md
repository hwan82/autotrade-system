# risk-ops 검토: Q-036 사용자 결정 D-055 기록, 기획서 v14.11 (PR #43)
- 대상: PR #43 / 커밋 7db31d3 (5d82ca1 + b75560d + 91ffdae + 7db31d3) / `git diff origin/main...HEAD` 전체 (origin/main = cf3759a)
- 변경 파일: `.gitleaks.toml`(신규), `docs/decisions/README.md`, `docs/research/q-007-q-026-collector-licenses.md`, `docs/spec/autotrade-spec.md`, `docs/reviews/pr-43/architect.md`. Java·Gradle·SQL·설정 키·테스트 파일 변경 없음
- 점검표: A
- 직접 실행한 것:
  - 검토 도중 head가 91ffdae에서 7db31d3으로 바뀌었다는 메인 메시지를 받음. 메인 설명을 쓰지 않고 `git diff --stat 91ffdae origin/claude/project-thread-ngnjxp`로 직접 확인: `docs/reviews/pr-43/architect.md` 1파일 +38줄뿐. 로컬 브랜치를 7db31d3으로 fast-forward(커밋·푸시 없음)
  - `./gradlew build` (91ffdae, 7db31d3과 Gradle 입력 동일): BUILD SUCCESSFUL, 104 actionable tasks (15 executed, 89 up-to-date)
  - 캐시 재사용을 피하려고 `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test --rerun-tasks`: BUILD SUCCESSFUL, 52 tasks 모두 실행. 결과 XML 집계: autotrade-risk 55건/실패 0/오류 0/건너뜀 0, autotrade-architecture-test 28/0/0/0, autotrade-app-engine 48/0/0/0
  - gitleaks 8.21.2를 CI 고정 버전·체크섬(`.github/workflows/ci.yml:141-142`, sha256 `5bc41815…e3ba` 일치 확인)으로 받아 CI와 같은 명령 `gitleaks git --redact --exit-code 1 .`(`ci.yml:154`) 실행: 210 commits, no leaks found
  - 같은 저장소를 기본 규칙만(`[extend] useDefault = true`)으로 실행: 2건, 둘 다 `generic-api-key`, `docs/reviews/pr-39/architect.md`(89fae05)와 `docs/reviews/pr-43/architect.md`(91ffdae). 두 곳 모두 PR #39 검토의 CI 패턴 시험용 가짜 값 `x?crtfc_key=abcdef1234567890`을 인용한 줄임을 직접 확인. 즉 새 예외가 없애는 것은 이 가짜 값뿐
  - 사용자 결정 출처 대조(읽기 도구 `fetch_messages`만 사용): 메시지 cmsg_01Du6VLttqgn6f15sF8UYTuU1rgVcJYrcdoz1qH27LQFAG(2026-10-10T08:14:55Z, 스레드 cmsg_01Du6VLttqgn6f15sF8UYTuUNX33euHDFz6RDC3rZhkw75, `ask_decision`)의 선택지 0 "7단계 전 문의", 결과 "실험실은 지금 진행하고, 7단계 전에 직접 한국거래소에 묻습니다. 답이 부정적이면 실전용 입력만 공공데이터포털 시세로 바꿉니다.", 추천 0. 메시지 cmsg_01Du6VLttqgn6f15sF8UYTuU6PJmDVptpp2jVQBbM3fDCa(13:23:12Z, `decide`)에서 decision_id가 위 카드, option_index 0, chosen_by user_01Vc7USeKzFq1SwmNQt6Je3E. 이 author_id가 사용자 BAE임은 다른 스레드(cmsg_01Du6VLttqgn6f15sF8UYTuUG4CcRQ4pjMMhmSvuRPuvo5)의 author_name으로 확인. D-055(`docs/decisions/README.md:55`)의 시각·선택지·대체 소스와 일치. 카드 context의 번호는 "Q-035"이고 저장소는 Q-036인데, 번호 조정 기록은 architect 재검토 원문이 인용한 스레드 메시지(08:15:46Z, 08:45:24Z)이며 그 두 메시지는 이 검토에서 직접 읽지 않았다(질문 문구·선택지가 Q-036 조사 4장과 같아 같은 질문임은 내용으로 확인)
  - `git diff --word-diff origin/main...HEAD -- docs/spec docs/decisions docs/research`: STR-19와 배치표 5번은 문구 추가만 있고 삭제 단어 없음(배치표 5번 선행 칸 "0, 1." → "0, 1, D-055(…)."). Guard·손절·블랙리스트·한도 관련 문구 삭제 없음

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 코드·설정·테스트 변경 없음(diff 5파일, 코드 0). `autotrade-risk` 테스트 55건 전부 재실행 통과. STR-19(`docs/spec/autotrade-spec.md:170`)는 "RealTradingGuard, 블랙리스트, 리스크 한도, 실전 활성화 절차(7장)는 바꾸지 않는다" 문구를 그대로 유지 |
| A2 | 통과 | 주문 출구 코드·ArchUnit 규칙 변경 없음. `autotrade-architecture-test` 28건 재실행 통과 |
| A3 | 통과 | 주문 라우팅 변경 없음. D-055가 바꾸는 것은 실전용 선정 결과의 입력 데이터 출처(KRX Open API ↔ 공공데이터포털)이며 시세·주문 증권사 선택과 무관 |
| A4 | 통과 | 주문 상태머신·타임아웃 처리 코드·문구 변경 없음(`autotrade-app-engine` 48건 재실행 통과) |
| A5 | 통과 | 멱등 키 관련 코드·문구 변경 없음 |
| A6 | 통과 | 블랙리스트·수동 보유 필터 변경 없음. STR-19의 수동 보유·외부 매도 처리 문구는 삭제 없이 유지(word-diff 확인) |
| A7 | 통과 | 계좌 직렬 큐·토큰 버킷 코드·문구 변경 없음 |
| A8 | 통과 | 프로세스 구조 변경 없음. 선정 결과 생산자가 실험실 밖 일괄 명령이고 실전 엔진은 파일만 읽는다는 STR-19 문구 유지. D-055는 그 일괄 명령의 입력 출처 조건만 더함 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 관련 변경 없음. 입력 출처가 바뀌어도 기존 보유 손절 감시는 STR-19 "불변 조건"으로 유지되고 선정 탈락은 청산 사유가 아님(STR-15, 변경 없음) |
| A10 | 통과 | diff 전문에 API 키·계좌번호·토큰 없음. CI와 같은 gitleaks 명령 no leaks. 새 예외 `.gitleaks.toml:11` `'''^abcdef1234567890$'''`는 앵커가 있는 정확 일치 한 값이고, 기본 규칙만 돌린 결과 이 예외가 없애는 2건이 모두 가짜 시험 값임을 직접 확인. paths·커밋 단위 예외가 아니라 값 단위라 다른 실제 키를 가리지 않음 |
| A11 | 통과 | 테스트 파일 변경 없음(보호 테스트 삭제·비활성화 없음). 새 동작 코드 없음 |
| A12 | 통과 | `docs/team/broker-and-fault-design.md` 변경 없음. 장애표 행 추가 계획(선정 결과 없음·만료·해시 불일치·생산 실패·오래된 입력)은 배치표 5번(`docs/spec/autotrade-spec.md:327`)에 기존대로 있고, D-055는 그 전제를 깨지 않음. 실전용 입력 출처가 바뀌는 경우에도 같은 행(오래된 입력·생산 실패)과 MD-09 품질 검사가 적용되도록 STR-19에 명시 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. D-055는 국내 KRX 데이터만 다룸 |
| A14 | 통과 | 결정 로그 D-055(`docs/decisions/README.md:55`), Q-036 닫힘(`:99`), 기획서 v14.11(`docs/spec/autotrade-spec.md:12,20`), MD-07(`:121`), STR-19(`:170`), 배치표 5번 선행(`:327`), 15장 닫힌 항목(`:353`), 조사 문서 4장 결정 표시가 서로 일치. 사용자 카드 선택 기록과 일치(위 출처 대조). 법적 해석 사안(지침 8장 6번)이 "사용자 결정"으로 기록되었고 자율 채택으로 섞이지 않음 |

## 점검표 밖 발견
1. **R1 (권고, 배치표 5번 착수 때 반영, 이 PR 판정에는 넣지 않음)**: D-055대로 한국거래소 답이 부정적이어서 실전용 선정 입력만 공공데이터포털로 바꾸면, 8주 모의에서 검증된 조합(KRX 입력 선정 결과)과 실전 계좌가 실제로 쓰는 선정 결과(공공데이터포털 입력)가 다른 데이터에서 나온다. MD-09 품질 검사는 결측·0원·시점 위반을 걸러낼 뿐 두 출처의 선정 결과가 같다는 보장은 아니다. 시가총액(STR-16, 시점별 상장주식수)·수정 계수·소속부 필드가 출처마다 다르면 선정 종목이 달라질 수 있고, D-047 병행 모의가 KRX 입력으로 돌면 사고 연동(D-046 ③)의 비교 대상도 어긋난다 `[추론]`. 배치표 5번 설계 때 (a) 출처 전환 시 같은 기준일들에 두 출처로 선정 결과를 만들어 종목 일치율을 비교하고 기준 미달이면 사용자에게 알림, (b) 실전과 같은 조합의 병행 모의는 실전용 입력 출처의 선정 결과를 쓰는 것을 검토 항목으로 넣길 권함. 안전 규칙(Guard·손절·블랙리스트)을 약하게 하는 문제는 아니고, 승격 검증의 대표성 문제다.
2. **R2 (참고)**: STR-19는 "한국거래소 답(비상업 목적에 해당)이 먼저 있어야 한다"로, D-055는 "답이 부정적이면 바꾼다"로 적혀 있어 무응답·애매한 답의 처리가 명시되지 않았다. 배치표 5번 선행에 D-055 답이 들어가 있어 답이 없으면 5번이 시작되지 않으므로 안전 쪽으로 막힌다. 5번 착수 시 무응답이면 대체 소스로 갈지 사용자에게 물을 항목으로 남기면 충분하다(architect 재검토 1번과 같은 견해).
3. **R3 (참고)**: 이 결정은 문서상 선행 조건이고, 실전 엔진이 "KRX 출처 선정 결과를 확인 없이 읽는 것"을 막는 장치는 아직 없다. 배치표 5번 구현 때 선정 결과 파일(STR-15 형식) 메타데이터에 입력 출처·라이선스(MD-09 메타데이터)를 넣고, 실전 엔진이 허용 출처가 아니면 그 계좌 신규 진입을 막는(기존 손절·서버 측 손절은 유지) 검사를 두는 것을 권함. 차단 방향이 신규 진입이므로 보유 보호에는 영향이 없다.
4. **R4 (참고)**: `.gitleaks.toml` 예외는 다른 브랜치 검토 원문의 가짜 값 때문에 생긴 것이고, 이번 PR의 architect 원문도 같은 값을 인용해 이 예외에 기댄다. 예외가 늘어나는 것을 막는 방안(스캔 범위 조정 등)은 비밀값 스캔 약화 여부가 걸리므로 따로 낼 때 risk-ops 검토(A10) 대상이다. 현재 예외는 값 정확 일치 한 개라 위험이 낮다.
5. **확인 범위 메모**: 카드 번호 Q-035 → Q-036 조정 메시지(08:15:46Z, 08:45:24Z)는 직접 읽지 않았다. 카드의 질문·선택지 문구가 저장소 Q-036 조사 4장 선택지와 같아 같은 질문임은 내용으로 확인했으므로 A14 판정에는 영향이 없다.

## 판정
승인
