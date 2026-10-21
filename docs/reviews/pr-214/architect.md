# architect 검토: 결정 로그 D-134 LIVE02 판정 기준·시작 시점 사용자 결정(Q-056 닫음)
- 대상: PR #214 / 커밋 71e86439250330424bb8fdaaa71a6ba25382b143 / `git diff origin/main...HEAD` 전체(3개 파일, +30 −3)
  - `docs/decisions/D-134-live02-criteria-and-start.md`(새 파일)
  - `docs/decisions/README.md`(D-134 행 추가, Q-056 미확정 → 닫힌 미확정 이동)
  - `docs/strategy/live02-preregistration-plan.md`(9장 결정됨 안내, 동시 세션 제안 번호 F7 → F8 두 곳)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`: 실패(build-logic 의존성 캐시 없음, `kotlinx-coroutines-core-jvm-1.6.4` 오프라인 캐시 없음).
  - `./gradlew build`: 실패(repo.maven.apache.org `429 Too Many Requests`, 예: `commons-compress-1.27.1.pom`, `spring-core-6.2.19.module`). 코드 원인이 아닌 환경 문제다. 사용자 허용 방식대로 CI 결과를 근거로 쓴다(문서만 바뀐 PR).
  - CI(커밋 71e86439 check-runs, `gh api .../commits/<sha>/check-runs`): Gradle build success(https://github.com/hwan82/autotrade-system/actions/runs/39529302772/job/118991259334), Secret scan and collector boundary success, DB schema (PostgreSQL 16) success, Lab screens (Playwright) success.
  - `git diff --name-only origin/main...HEAD | grep -Ff scripts/ci/protected-paths.txt`: 일치 없음(보호 경로 변경 없음).
  - 결정 번호 확인: 모든 원격 브랜치의 `docs/decisions/` 파일명에서 D-13x를 셈. D-132는 다른 브랜치 1곳, D-133은 main 등, D-134는 이 브랜치만. 겹침 없음.
  - 인용 사실 대조: `LiveLabService.java` 53행 "세션은 한 번에 하나다" 확인. D-114 3번 "p = (k+1)/(N+1) ≤ 0.05/m, m = 6" 확인. 계획 5장 표 A01 = `kr-bh-baseline@3`(116행) 확인. D-128 1번(LIVE01 연장 먼저)·5번(판정) 내용과 D-134가 일치. 계획 6.1(L1 A01 v3, L10 같은 선정 무작위 진입·m = 판정 대상 수)·6.2(N_k)·7.1(선정 효과 참고 칸)과 D-134 결정 1번이 일치.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI 근거) | 컨테이너 빌드는 Maven 429로 불가(위 로그). CI `Gradle build` success, 커밋 71e86439. 바뀐 파일이 모두 `docs/` Markdown이라 컴파일·테스트 입력이 없다 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 3개 파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API·등록 파일 키 변경 없음. D-134 결정은 기존 D-114·D-131(ADR-014) 계약 안에서 값을 고르는 것이고 새 키를 만들지 않는다(D-134 결정 1번은 계획 6.2·ADR-014 F3 `group-null-seeds`를 그대로 따름) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 결정 내용도 결과 보기 전 고정(D-134 되돌릴 조건: 등록 파일 커밋 뒤 변경 금지, D-128 사전 등록)이라 결정론·사전 등록 원칙과 맞다 |
| B5 | 해당 없음 | 금액 계산 코드 없음 |
| B6 | 해당 없음 | 새 동작 없음. 테스트 삭제·건너뛰기 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 경로·보호 경로 변경 없음(`scripts/ci/protected-paths.txt` 대조 일치 없음). D-134 ②는 동시 세션(실험실 자원 증가, 지침 4장 6번 검토 대상)을 고르지 않았으므로 risk-ops 검토 사유가 생기지 않는다 |
| B8 | 통과 | 새 내용에 키·계좌번호·토큰 없음. CI Secret scan success. 결정자 표기 "BAE"는 이름 표기이고 비밀값이 아니다 |

## 점검표 밖 발견
- n1(권고, 판정 영향 없음): `docs/strategy/live02-preregistration-plan.md` 3행 상태 줄이 여전히 "사용자 확인 항목은 [Q-056](../decisions/README.md)(9장)"이다. Q-056이 닫혔으므로 "결정: D-128, D-134(Q-056 닫음)"처럼 D-134 링크를 상태 줄에도 두면 문서 첫 줄만 읽는 사람이 열린 질문으로 오해하지 않는다. 9장 머리에는 결정됨 안내가 있어 단일 원본 간 모순은 아니다.
- n2(권고, 판정 영향 없음): F7 → F8 바꾸기는 옳다(8장 F7은 이미 "막힌 신호가 하루 진입 한도·대기 금액을 쓰지 않게"로 쓰였고 D-130으로 머지됨. 같은 번호가 두 뜻을 갖던 충돌을 없앰). 다만 8장 변경 제안 표(214~224행)에 F8 행이 없어 F8이 정의되지 않은 번호로 남는다. 사용자가 선택지 2를 고르지 않아 실행될 일은 없으므로, 9.1·10장 문구를 "(F8, 8장 표에 없음, 고르면 새로 제안)"으로 두거나 8장 표에 "F8 동시 세션(채택 안 함, D-134)" 한 줄을 두는 것을 권한다.
- 확인: D-128 본문(21행 "Q-056 답에 따라 새 번호로 바꾼다")을 고치지 않고 새 번호 D-134로 기록한 것은 결정 로그 규칙(뒤집거나 확정할 때 새 번호)과 맞다.
- 확인: README 미확정 표에서 Q-056 행을 지우고 닫힌 미확정 표에 날짜·결과와 함께 옮겼다. 다른 Q 행은 바뀌지 않았다.

## 판정
승인
