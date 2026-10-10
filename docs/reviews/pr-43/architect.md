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

## 재검토 2026-10-10
- 대상: PR #43 / 커밋 91ffdae7c3e1ee8942f3c25520110ce6d5613989 (5d82ca1 + b75560d + 91ffdae) / `git diff origin/main...HEAD` 전체 (origin/main = cf3759a)
- 변경 파일: 1차와 같은 4개 + `docs/reviews/pr-43/architect.md`(1차 검토 원문). 91ffdae의 원문 외 변경은 `docs/decisions/README.md` D-055 한 행, `docs/spec/autotrade-spec.md` 변경 이력 v14.11 행·STR-19·배치표 5번 선행·15장 닫은 항목
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (HEAD 91ffdae): BUILD SUCCESSFUL, 104 actionable tasks (15 executed, 89 up-to-date). 91ffdae도 Gradle 입력 변경 없음
  - gitleaks 8.21.2, CI와 같은 명령(`gitleaks git --redact --exit-code 1 .`): no leaks, EXIT 0. 기본 규칙만으로는 2건이고 둘 다 같은 가짜 값 `abcdef1234567890`(`docs/reviews/pr-39/architect.md`, 89fae05와 이 원문 파일, 91ffdae). 이 원문이 그 값을 인용했기 때문이며 `.gitleaks.toml`의 정확 일치 예외로 처리됨
  - `git diff --word-diff b75560d 91ffdae -- . ':!docs/reviews'`로 수정 내용 확인
  - 1차 원문 무결성: 91ffdae에 들어간 `docs/reviews/pr-43/architect.md`는 이 검토자가 쓴 1차 내용(33줄, F1~F5, 판정 줄)과 같음. 메인이 고친 흔적 없음
  - F3 대조: 지시에 따라 읽기 도구만 사용(`list_thread_sessions`, `fetch_project_timeline`, `fetch_thread` 스레드 cmsg_01Du6VLttqgn6f15sF8UYTuUNX33euHDFz6RDC3rZhkw75 "수집기 라이선스·약관 확인")

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | HEAD 91ffdae `./gradlew build` BUILD SUCCESSFUL |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 변경 없음 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음(`.gitleaks.toml`은 CI 설정) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 파일 변경 없음 |
| B7 | 해당 없음 | 코드상 실전 경로 변경 없음. 91ffdae가 STR-19(`docs/spec/autotrade-spec.md:170`)와 배치표 5번 선행(`:327`)에 실전용 선정 입력의 출처 조건을 넣었으나 안전 규칙(Guard·주문·블랙리스트·한도·장애 대응)을 약화하지 않고 실전 작업에 선행 조건만 더함. risk-ops가 같은 PR을 검토 중이라는 스레드 상태(13:30:08Z)는 메인 주장이며 이 판정의 근거로 쓰지 않음 |
| B8 | 통과 | diff 전문에 키·계좌번호·토큰 없음. gitleaks EXIT 0 |

### 1차 지적 처리
- **F1 해소**: STR-19 끝에 "실전용 선정 결과의 입력 데이터(D-055)" 문단 추가(`docs/spec/autotrade-spec.md:170`): 한국거래소 답(비상업 목적 해당)이 먼저 있어야 하고, 부정적이면 실전용 입력만 공공데이터포털로 바꾸며 같은 품질 검사(MD-09) 통과. 배치표 5번 선행 칸이 "0, 1, D-055(KRX Open API 실전 이용에 대한 한국거래소 답, 사용자 문의)"로 바뀜(`:327`). 변경 이력 v14.11 영향 칸에 "14장 배치표 5번" 추가(`:20`).
- **F2 해소**: D-055(`docs/decisions/README.md:55`)에서 "(또는 증권사 일봉)" 삭제, "그 실전용 스냅샷도 같은 품질 검사(MD-09)를 통과해야 한다" 추가, 출처에 "조사 4장 선택지 1, 추천안" 명시. 기획서 변경 이력·15장 닫은 항목의 "대체 소스로"도 "공공데이터포털로"로 맞춤.
- **F3 해소(대조 완료)**: 스레드 기록 [사실]
  - 08:14:55Z 메시지 cmsg_01Du6VLttqgn6f15sF8UYTuU1rgVcJYrcdoz1qH27LQFAG(`ask_decision`): 선택지 0 "7단계 전 문의", 결과 "실험실은 지금 진행하고, 7단계 전에 직접 한국거래소에 묻습니다. 답이 부정적이면 실전용 입력만 공공데이터포털 시세로 바꿉니다.", 추천 0.
  - 13:23:12Z 메시지 cmsg_01Du6VLttqgn6f15sF8UYTuU6PJmDVptpp2jVQBbM3fDCa(`decide`): `decision_id`가 위 카드, `option_index: 0`, `chosen_by: user_01Vc7USeKzFq1SwmNQt6Je3E`. 같은 author_id의 사용자 메시지(13:20:38Z)에 author_name "BAE".
  - D-055의 시각·사용자·선택지와 일치. 카드 문구에 "증권사 일봉"은 없었으므로 F2 수정이 맞다. 카드 context는 "(Q-035)"로 적혀 있으나, 08:15:46Z 상태 메시지("다른 스레드와 겹친 번호를 D-050·Q-036으로 조정")와 08:45:24Z 안내("카드에는 Q-035로 적혀 있습니다")로 같은 질문의 번호 조정임이 기록돼 있음.
  - 카드 결과 문구에는 품질 검사 조건이 없다. D-055의 MD-09 품질 검사는 조사 문서 선택지 1 원문(`docs/research/q-007-q-026-collector-licenses.md:127`)에 있던 조건이고, 이미 실험 데이터에 적용되는 MD-09를 실전용 스냅샷에도 적용하는 것이라 사용자 선택을 넘어서는 새 결정으로 보지 않음 `[추론]`.

### 점검표 밖 발견(재검토)
1. 참고(수정 불필요): STR-19 문단은 "답이 부정적이면"만 규정하고 "7단계까지 답이 오지 않은 경우"는 따로 쓰지 않았다. 배치표 5번 선행에 D-055 답이 들어가 있어 답이 없으면 5번이 시작되지 않으므로 안전 쪽으로 막힌다. 배치표 5번 착수 때 무응답 시 대체 소스로 갈지 사용자에게 물을 항목으로 남기면 충분.
2. 참고: 1차 원문 4번(브랜치마다 `.gitleaks.toml` 복사 대신 스캔 범위 조정 ADR) 권고는 그대로 유효. 이번 원문 파일 자체도 같은 가짜 값을 인용해 예외에 기대고 있다.

### 판정
승인
