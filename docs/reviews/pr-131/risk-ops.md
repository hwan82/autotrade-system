# risk-ops 검토: 화면 시험 보호 확인 보강(D-102, PR #129 독립 QA 권고 Q1·R1~R3)
- 대상: PR #131 / 커밋 6a4728c / main 대비 diff(`git diff origin/main...HEAD`, 10개 파일 +200 −15: `autotrade-ui/e2e/**`, `autotrade-ui/README.md`, `docs/decisions/D-102…`, `docs/decisions/README.md`, `docs/qa-out-status.md`)
- 점검표: A
- 직접 실행한 것:
  - `node --test support/guard.test.mjs`(autotrade-ui/e2e, Node v22.22.0): 14건 통과, 실패 0.
  - `./gradlew --no-daemon :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test`(3회, 1회는 `--refresh-dependencies`): **BUILD FAILED**. 원인은 코드가 아니라 의존성 받기 실패(`repo.maven.apache.org` … `kotlin-stdlib-2.0.21.pom` "Received status code 429 … Too Many Requests"). 이번 세션에는 사용자 허락이 없어 미러 init 스크립트를 쓰지 않았다. 따라서 Gradle 시험은 직접 돌리지 못했다.
  - 보조 근거(직접 실행 아님): 헤드 6a4728c의 CI 체크 4개 모두 success(Gradle build, Lab screens (Playwright), DB schema (PostgreSQL 16), Secret scan and collector boundary; actions run 38859619403). `gh api repos/hwan82/autotrade-system/commits/6a4728c/check-runs`로 확인.
  - 보호 경로 대조: 바뀐 파일 10개를 `scripts/ci/protected-paths.txt` 패턴 전체와 bash 패턴으로 대조, 걸리는 파일 0개. diff에 `*.java`, `*.kts`, `.github/**` 변경 0줄.
  - 탐침(저장소 밖 스크래치 스크립트로 `guard.mjs`의 `workDirReason` 호출): 아래 F1 재현.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로딩·그 시험 파일 변경 없음(diff 범위가 `autotrade-ui/e2e`와 문서뿐, `*.java` 0줄). 보호 경로 대조 0건 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 없음(보호 경로 대조 0건). ArchUnit 규칙 그대로 |
| A3 | 해당 없음 | 증권사 플러그인·주문 라우팅 코드 변경 없음(`autotrade-broker-*` 대조 0건) |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 실패 | 이 PR은 실전 엔진이 도는 PC를 시험이 해치지 않게 하는 확인(장 시간 보호, 정지 요청, 작업 폴더 삭제, Gradle 데몬)을 바꾼다. 장 시간·수록 범위·정지 요청·데몬 쪽은 강해졌다(통과 부분 아래). 그러나 새로 넣은 작업 폴더 거절 `workDirReason`에 우회가 있다: `guard.mjs:138` `!rel.startsWith('..')`가 이름이 `..`로 시작하는 하위 폴더를 "밖"으로 본다. 실제 설정 폴더 `<X>/..cfg`, `E2E_WORK_DIR=<X>`이면 `workDirReason`이 null을 돌려(탐침 P1 재현) `playwright.config.mjs:26` `fs.rmSync(WORK, {recursive:true})`가 실제 설정 폴더(감시 정지 요청 `experiment-state/stop-request`, 운영자 비밀번호 해시, 카카오 토큰, `secrets.json`)를 통째로 지운다. 정지 요청이 지워지면 자원 부족 중에도 실험실 작업이 다시 돌 수 있어 broker-and-fault-design.md 5.2 정지 순서의 전제가 깨진다. 설정 폴더 이름이 `..`로 시작해야 하는 드문 조건이지만, 이 확인이 막겠다고 적은 바로 그 사고("같거나 그 위·아래면 거절", D-102 결정 2)이므로 실패로 둔다. 통과 부분: 수록 범위 밖 거절 `uncoveredDate`(`guard.mjs:62`)가 `SessionProtection.check`(`SessionProtection.java:56-61`, 전날·당일·다음날, `CalendarCoverageException`→`Unknown`)와 같은 날짜 집합을 보고, 주말·평일과 무관하게 먼저 거절해 Java보다 넓거나 같음; 해제는 `allowDuringSession`(GITHUB_ACTIONS와 전용 변수 둘 다)일 때만(`guard.mjs:110-111`); 정지 요청은 해제와 무관하게 먼저 보고 `ENOENT`·`ENOTDIR` 외 오류는 있는 쪽(`guard.mjs:104-109`, 시험 "정지 요청 파일 확인이 없음 외의 오류로 실패하면 거절하고…"); 시험 안 Gradle 두 호출 `--no-daemon`(`playwright.config.mjs` 데모 webServer, `02-experiment-flow.spec.mjs:69`), `--stop`은 teardown에 넣지 않고 README에 Gradle `run`으로 띄운 실전 PC에서 쓰지 말라고 적음(D-102 대안 R3-B 버림 이유 확인) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff 추가 줄에서 키·토큰·계좌번호 형태 문자열 검색 0건, 시험 픽스처는 `mkdtemp` 빈 폴더만 씀(`guard.test.mjs`), CI "Secret scan and collector boundary" success(보조) |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 시험, 장애 테스트) 변경 없음(보호 경로 대조 0건), 고정 값 갱신 없음. `guard.test.mjs`는 기존 7건을 지우거나 고치지 않고 7건 추가(diff에 `-` 시험 줄 없음), 새 동작마다 거절 경우 포함(수록 범위 밖 거절·해제 통과, lstat 오류 4종 거절·2종 통과, 겹침 거절 7경우·비겹침 2경우, 링크 따라감). 단, F1의 `..` 접두 경우를 잡는 시험이 없다(A8 실패와 같은 원인, 수정 시 함께 추가 필요). Gradle 보호 테스트 자체는 로컬 직접 실행 실패(429), CI Gradle build success로만 확인 |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 대상 코드 변경 없음. 바뀐 것은 시험 하네스의 사전 확인뿐이며 수록 범위 밖 거절은 Java `Unknown` 정지와 같은 방향(더 보수적). F1은 대응표 항목이 아닌 시험 하네스 결함으로 A8에 적음 |
| A13 | 통과 | 실전 경로 시장 분기 변경 없음. 시험 쪽 시간 판단은 캘린더 `zone`(Asia/Seoul, America/New_York) 현지 날짜·분으로 함(`guard.mjs` `local(now, c.zone)`, `uncoveredDate`), 미국 조기 폐장일(2026-11-27) 합침 시험 추가. 금액 계산 없음 |
| A14 | 통과 | D-102 문서와 결정 로그 표 한 줄이 코드 동작과 일치(결정 1~4를 코드·시험으로 대조). README의 "수록 범위 안에서는 실제 보호보다 넓거나 같음, 범위 밖은 판단할 수 없음으로 막음" 문구가 `refuseReason` 순서와 일치. 단 D-102 결정 2의 "같거나 그 위·아래면 거절"은 F1 때문에 현재 코드에서 참이 아님 |

## 점검표 밖 발견
- F1(수정 필요, A8·A11·A14와 같은 원인): `autotrade-ui/e2e/support/guard.mjs:138`
  ```js
  return !rel.startsWith('..') && !path.isAbsolute(rel);
  ```
  `path.relative('/foo', '/foo/..cfg') === '..cfg'`라 `..`로 시작하는 정상 하위 이름을 상위 탈출로 오인한다. 탐침 P1: `base=mkdtemp`, 설정 폴더 `base/..cfg`, `workDirReason(base, {env:{AUTOTRADE_CONFIG_DIR: base/..cfg}, repo:...})` → `null`(거절 안 함). 고칠 방향: `rel === '..' || rel.startsWith('..' + path.sep)`일 때만 밖으로 보고(또는 `rel.split(path.sep)[0] === '..'`), 그 경우(`<X>`와 `<X>/..cfg`, 반대 방향 `<X>/..cfg/sub`)를 `guard.test.mjs`에 넣고, 원래 코드로 되돌리면 그 시험이 실패하는지 확인한다.
- 참고(수정 요구 아님): 아직 없는 작업 폴더가 설정 폴더로 가는 링크 아래 있으면(`<link>/sub`, `link → 실제 설정 폴더`) `realpathSync`가 실패해 비교가 링크 경로 그대로 된다(탐침 P2 → null). 이때 지우는 대상은 아직 없는 `sub`이고 그 뒤 시험이 만드는 것도 `sub` 아래뿐이라 기존 파일은 지워지지 않는다. 다음 실행부터는 `sub`가 있어 실제 경로로 풀려 거절된다. 위험이 낮아 기록만 남긴다. 고친다면 가장 가까운 있는 조상을 실제 경로로 푼 뒤 나머지를 붙이는 방식이다.
- 참고: 국내 캘린더 수록 끝(2027-12-31) 때문에 2027-12-31부터(다음날 규칙) PC 실행이 거절된다. 보수 방향이며 Java 실행 명령도 같은 날 멈추므로 받아들인다. 캘린더 갱신 작업을 data-engineer 후속으로 두기를 권한다.
- 참고: Windows 대소문자·정션 비교는 Linux에서만 돌려 봤다(정션 시험은 Linux에서 symlink로 통과). D-102 위험과 한계에 이미 적혀 있다.
- 확인 못 한 것: 로컬 Gradle 시험(의존성 429). CI Gradle build success를 보조 근거로 적었고, diff에 Gradle이 돌리는 코드 변경이 없어 판정에 쓰는 점검 항목은 diff 대조로 결정했다.

## 판정
수정 필요(A8: `workDirReason` 겹침 판정의 `..` 접두 우회 F1과 그 시험 추가, A11 연동)

## 재검토 2026-10-16 (헤드 ed0727b)
- 대상: PR #131 / 커밋 ed0727b / main 대비 diff 전체(앞 검토 6a4728c 대비 새 변경은 ed0727b 하나: `autotrade-ui/e2e/support/guard.mjs`, `guard.test.mjs`, `docs/decisions/D-102-lab-e2e-guard-followups.md`. 그 밖의 diff는 검토 원문 `docs/reviews/pr-131/architect.md`·`risk-ops.md` 추가뿐)
- 점검표: A
- 직접 실행한 것:
  - `node --test support/guard.test.mjs`: 14건 통과, 실패 0.
  - 앞 검토 탐침 다시 실행: P1(설정 폴더 `<X>/..cfg`, 작업 폴더 `<X>`) → 거절 문구 반환(앞 검토 null). P2(링크 아래 아직 없는 `link/sub`) → 거절 문구 반환(앞 검토 null). P3(기본 작업 폴더 `os.tmpdir()/autotrade-ui-e2e`) → null(정상 통과 유지).
  - 변이 2개를 작업 트리에서 직접 넣고 시험 뒤 되돌림(`git status` 깨끗 확인): M1 `inside`를 원래 `!rel.startsWith('..')`로 되돌림 → 시험 13 "시험 작업 폴더가 실제 설정 폴더·저장소와 같거나 그 위·아래면 거절한다" 실패(1건). M2 `norm`의 위 폴더 올라가기 제거(없는 경로 그대로 비교) → 시험 14 "작업 폴더 확인은 링크를 따라가 실제 경로로 본다" 실패(1건). 두 수정 모두 시험이 고정한다.
  - `./gradlew --no-daemon :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test`: 다시 BUILD FAILED. 원인은 같음(의존성 받기 실패, `kotlin-stdlib:2.0.21` "Could not find/resolve", 앞 시도에서 Maven Central 429). 사용자 허락이 없어 미러는 쓰지 않았다. 보조 근거(직접 실행 아님): 헤드 ed0727b CI 4개 잡 모두 success(Gradle build, Lab screens (Playwright), DB schema (PostgreSQL 16), Secret scan and collector boundary; actions run 38860195658).
  - 보호 경로 대조: 바뀐 파일 12개 중 `scripts/ci/protected-paths.txt` 패턴에 걸리는 파일 0개. `*.java`·`*.kts`·`.github/**` 변경 0줄(앞 검토와 같음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 앞 검토와 같음. ed0727b도 `autotrade-ui/e2e`와 D-102 문서만 바꿈, RealTradingGuard·설정 로딩 변경 없음 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 없음(보호 경로 대조 0건) |
| A3 | 해당 없음 | 증권사 플러그인·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | F1 해소: `guard.mjs` `inside`가 `rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel)`로 바뀌어 `..cfg` 같은 안쪽 이름을 안쪽으로 본다(탐침 P1 거절, 변이 M1을 시험 13이 잡음). `norm`이 없는 경로를 있는 가장 가까운 위 폴더까지 실제 경로로 푼 뒤 나머지를 붙여, 링크 아래 새 폴더도 실제 설정 폴더 안으로 판정(탐침 P2 거절, 변이 M2를 시험 14가 잡음). 루프는 `path.dirname(head) === head`(루트)에서 끝나 무한 반복 없음. Windows 구분자는 `path.sep`을 써서 `path.relative`의 출력과 맞음. 앞 검토에서 통과로 본 장 시간 보호·수록 범위 밖 거절·정지 요청 오류 시 거절·`--no-daemon`·`--stop` 안내는 ed0727b가 건드리지 않음(diff 확인) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | ed0727b 추가 줄에 키·토큰·계좌번호 형태 없음, 시험 픽스처는 `mkdtemp` 빈 폴더와 `..cfg`·`..e2e` 하위 폴더뿐. CI Secret scan success(보조) |
| A11 | 통과 | 보호 테스트·고정 값 변경 없음. `guard.test.mjs`는 기존 단정을 지우거나 고치지 않고 단정 5개 추가(`..e2e` 안쪽, F1 `..cfg` 위쪽, 링크 아래 없는 폴더, `coverageFrom` 경계 null, 미국만 범위 밖). 새 동작 두 곳 모두 변이로 시험이 실패함을 직접 확인(M1·M2). Gradle 보호 테스트는 로컬 직접 실행 실패(환경), diff에 그 대상 코드 변경 없음, CI Gradle build success(보조) |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종 대상 코드 변경 없음. 바뀐 것은 시험 하네스의 사전 확인뿐 |
| A13 | 통과 | 실전 경로 시장 분기 변경 없음. 추가된 수록 범위 시험(`2026-01-02T03:00:00Z` → `{ market: 'US', date: '2025-12-31' }`)이 시장별 현지 날짜(Asia/Seoul 01-02, America/New_York 01-01)로 판단함을 고정 |
| A14 | 통과 | D-102 결정 2가 "없는 경로는 있는 가장 가까운 위 폴더를 실제 경로로" 문구를 더해 코드와 일치, 검증 절에 추가 시험과 변이 3개를 적음. 앞 검토 A14의 단서("같거나 그 위·아래면 거절"이 F1 때문에 참이 아님)는 해소 |

## 점검표 밖 발견(재검토)
- 앞 검토 F1: 해소(위 A8).
- 앞 검토 참고(링크 아래 없는 폴더): 이번 수정으로 거절된다. 해소.
- 참고(남은 위험, 수정 요구 아님): D-102 문서 검증 절의 "14건 통과(이전 7건 + 7건)"은 시험 함수 수 기준으로 지금도 14건이다(이번 추가는 기존 시험 안의 단정). 국내 캘린더 수록 끝(2027-12-31) 관련 갱신 권고와 Windows 실기 미확인은 앞 검토와 같다.
- 확인 못 한 것: 로컬 Gradle 시험(의존성 받기 실패, 환경 문제). 이 PR은 Gradle이 컴파일·시험하는 코드를 바꾸지 않아 점검 항목은 diff 대조와 node 시험·변이·탐침으로 판정했고, CI Gradle build success를 보조 근거로 적었다.

## 판정(재검토)
승인
