# architect 검토: Q-036 사용자 결정 D-055 기록, 기획서 v14.11 (PR #43)
- 대상: PR #43 / 커밋 b75560d2817aefee17ffcfc993a34e5e6efacaf0 (5d82ca1 + b75560d) / `git diff origin/main...HEAD` 전체 (origin/main = cf3759a)
- 변경 파일: `.gitleaks.toml`(신규, +12), `docs/decisions/README.md`, `docs/research/q-007-q-026-collector-licenses.md`, `docs/spec/autotrade-spec.md`. Java·Gradle·SQL·설정 키 변경 없음
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (HEAD b75560d): BUILD SUCCESSFUL, 104 actionable tasks (15 executed, 89 up-to-date). 5d82ca1에서도 한 번 돌려 BUILD SUCCESSFUL. diff에 Gradle 입력 파일이 없어 테스트 캐시 재사용은 정상
  - gitleaks 8.21.2(CI와 같은 버전, 체크섬 `5bc41815…e3ba` 확인)를 로컬에서 CI와 같은 명령 `gitleaks git --redact --exit-code 1 .`로 실행: 204 commits, no leaks, EXIT 0
  - 같은 저장소를 기본 규칙만 쓰는 설정(`[extend] useDefault = true`)으로 실행: 1건. `generic-api-key`, `docs/reviews/pr-39/architect.md:10`, 커밋 89fae05(PR #39 브랜치). 즉 이 PR의 `.gitleaks.toml` 예외가 정확히 그 1건만 없앤다
  - `git log --all -S"abcdef1234567890"`, `git grep`으로 값 위치 확인: PR #39 검토 원문의 가짜 시험 입력(`x?crtfc_key=abcdef1234567890`)뿐, 실제 키 아님
  - `.gitleaks.toml`을 PR #39 브랜치(origin/claude/project-thread-vip8tu)와 diff: 내용 동일
  - 결정 번호·판 번호 충돌 조사: 원격 브랜치 전부의 결정 로그에서 D-051(vip8tu, v14.10), D-052(0bdmgq), D-053(v8cx8m), D-054(r5reos) 사용 중. D-055는 어느 브랜치와도 겹치지 않음. v14.10은 vip8tu가 사용
  - `grep -rn Q-036` 전체: 미확정 표에서 두 곳(결정 로그, 기획서 15장) 모두 빠지고 닫힌 표로 이동, 남은 언급은 조사 문서 본문과 이력뿐

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 결과, HEAD b75560d BUILD SUCCESSFUL |
| B2 | 해당 없음 | Java 소스·빌드 스크립트·ArchUnit 규칙 변경 없음(`git diff --stat origin/main...HEAD` 4파일, 코드 0) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. `.gitleaks.toml`은 CI 스캔 설정이고 런타임 설정 키(docs/config/config-keys.md 대상)가 아님 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·비활성화 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응 코드 변경 없음. D-055는 7단계 실전 선정 입력 데이터의 약관 확인 시점만 정하고 안전 규칙을 바꾸지 않음. 다만 7단계 실전 작업(배치표 5번)의 선행 조건을 만드는 결정이므로 아래 F1 반영 시 risk-ops가 배치표 5번 문구를 확인하길 권함 |
| B8 | 통과 | diff 전문 확인, 키·계좌번호·토큰 없음. gitleaks 전체 이력 스캔 EXIT 0. 새 예외는 `^abcdef1234567890$` 정확 일치 한 값만(앵커 포함, 기본 regexTarget=secret)이라 실제 키를 가릴 여지가 사실상 없음. 전역 allowlist에 paths를 더하면 OR 조건이라 오히려 넓어지므로 값 정확 일치가 가장 좁은 형태 |

## 점검표 밖 발견
1. **F1 (수정 필요, 기획서 단일 원본 불일치)**: D-055(`docs/decisions/README.md:55`)는 "7단계 실전 계좌 조합 지정(배치표 5번) 전까지 답을 받아 둔다"는 선행 조건을 새로 만든다. 그런데 기획서 14장 배치표 5번의 선행 칸(`docs/spec/autotrade-spec.md:327`, "0, 1. 실전 관련이라 사용자 머지")과 STR-19(`:170`)에는 이 조건이 없다. 변경 이력(`:20`)은 영향 요구사항에 STR-19를 적었지만 STR-19 본문은 바뀌지 않았다. 지침 2장(기획 내용은 기획서에만)에 따라 배치표 5번 선행 칸에 "D-055: KRX Open API 비상업 목적 해당 여부 한국거래소 답변(부정적이면 실전용 선정 입력 대체 소스 전환과 같은 품질 검사)"을, STR-19 실전용 선정 결과 생산자 문구에 입력 출처 조건을 한 줄 넣을 것. 안 넣으면 배치표 5번 담당(risk-ops·architect)이 문의 없이 실전 선정 입력을 KRX 데이터로 만들 수 있다.
2. **F2 (수정 필요, 사용자 결정 기록이 선택지 원문과 다름)**: 사용자가 고른 선택지 1(조사 문서 `docs/research/q-007-q-026-collector-licenses.md:127`)은 대체 소스를 "공공데이터포털(2.4)"로만 적고 "실전용 스냅샷도 같은 품질 검사를 통과해야 한다"는 조건을 둔다. D-055는 "(또는 증권사 일봉)"을 덧붙였고 품질 검사 조건은 뺐다. 사용자 결정 행에 사용자가 고르지 않은 대안을 넣으면 안 되고(증권사 일봉의 약관·수정주가 이력 요건은 조사되지 않았음), 고른 조건은 빠지면 안 된다. "(또는 증권사 일봉)"을 지우거나 "추천안 채택(자율)" 별도 결정으로 분리하고, 품질 검사 조건(MD-09)을 D-055에 넣을 것.
3. **F3 (확인 못 함, 판정 근거 밖)**: D-055의 출처 "프로젝트 스레드 사용자 BAE 2026-10-10T13:23:12Z 질문 카드에서 '7단계 전 문의' 선택"은 저장소 밖 기록이라 이 검토에서 확인하지 못했다(이 검토에서는 스레드 조회 도구를 쓰지 않도록 지시받음). Q-036은 지침 8장 6번(법적 우려) 사용자 결정 대상이므로, 머지 전에 메인 또는 독립 QA가 카드 선택 기록을 직접 대조해야 한다. 카드 선택지 문구가 "7단계 전 문의"라면 F2의 "또는 증권사 일봉"은 카드에도 없었을 가능성이 높다 `[추론]`.
4. **참고(수정 불필요)**: `.gitleaks.toml`은 다른 브랜치(PR #39)의 검토 원문 때문에 생긴 CI 실패 대응이다. CI가 `fetch-depth: 0` 체크아웃 뒤 `gitleaks git .`로 모든 ref 이력을 보므로(`.github/workflows/ci.yml:144-154`) 다른 브랜치의 내용이 이 PR 스캔에 잡히는 것은 사실로 확인했다. 같은 파일이 vip8tu·0bdmgq·hwfrnx·r5reos에도 동일 내용으로 있어 어느 순서로 머지돼도 add/add 충돌 없이 합쳐진다. 브랜치마다 예외 파일을 복사하는 방식은 계속 늘어나므로, 근본적으로는 CI 스캔 범위를 PR 대상 커밋 범위(`--log-opts="origin/main..HEAD"` 등)로 좁히는 방안을 별도 ADR·risk-ops 검토(A10, 보안 스캔 약화 여부)로 다룰 것을 권함.
5. **참고**: v14.10 건너뜀은 변경 이력에 이유가 적혀 있고 D-051~D-054 예약과도 겹치지 않음. 이력 표는 위가 최신 순서를 지킴.

## 판정
수정 필요(F1 기획서 배치표 5번·STR-19에 D-055 선행 조건 반영, F2 D-055 문구를 사용자 선택지 1 원문에 맞춤 / F3 사용자 카드 기록 대조는 머지 전 메인·QA 확인)
