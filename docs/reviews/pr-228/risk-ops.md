# risk-ops 검토: D-136 후속 고침 2 (PR #225 QA Q1~Q3·R2, EGW00215 안내)
- 대상: PR #228 / 커밋 `20b55067`(행 번호는 이 커밋 기준. 검토 중 작업 트리에 커밋 안 된 수정이 생겼으나 검토 대상 아님) / `git diff origin/main...HEAD` 전체(9개 파일: `KisNewsClient`·`KisNewsCollector`, 시험 3개, D-136·결정 README, `docs/qa/pending.md`, `scripts/ci/check-collector-keys.sh`)
- 점검표: A
- 직접 실행한 것:
  - `20b55067`을 별도 worktree에 꺼내 `./gradlew build --no-daemon`: **EXIT 0**. 시험 1,930건, 실패·오류·건너뜀 0(`autotrade-app-collector` 146, `autotrade-architecture-test` 46, `autotrade-risk` 55, `autotrade-app-engine` 68).
  - `bash scripts/ci/check-collector-keys.sh`: exit 0(저장소 전체 통과). 새 패턴을 표본 문자열로 직접 시험: `"app-key": "<29자>"`, Java 이스케이프 `\"app-secret\": \"…\"`, `'appkey': '…'`, `"appsecret":"…"`(값 안 `_`·`-` 포함)은 잡음, 짧은 값은 통과. 못 잡는 모양: `APPKEY`(대문자), 따옴표 없는 `appkey: …`(YAML), `.header("appkey", "<값>")`(아래 R2).
  - 변이(worktree에서, 수집기 시험만, 매번 되돌림):
    - M1 `000001` 한 번 더 받기 제거(`next = dayStart.minusSeconds(1)` 고정) → `새_행_없는_쪽이_00시_00분_01초에서_끝나면_000001로_한_번_더_받아_00시_행을_본다` 실패. 잡힘
    - M2 `midnightCuts++` 삭제 → `자정_행이_한_쪽보다_많으면_…`, `백필이_보관_한도에_닿으면_…`, `거른_행과_자정_확인_못_함이_0이_아니면_…` 실패. 잡힘
    - M3 조건에서 `current.isAfter(oneSecond)` 삭제 → **기존 시험 모두 통과(생존)**
    - M4 조건에서 `pageOldest.isAfter(dayStart)` 삭제 → `자정_행이_한_쪽보다_많으면_…`(호출 수 1) 실패. 잡힘
    - M5 셈 조건 `!pageOldest.isBefore(dayStart)` → `pageOldest.equals(dayStart)`(이전 판의 좁은 셈) → **기존 시험 모두 통과(생존)**
    - M6 `EGW00215` 안내 제거 → `KisNewsClientTest` 실패. 잡힘
  - 생존 변이 확인용 탐침 시험(worktree에만, 커밋 안 함): 그날 00:00:05 행 1, 00:00:01 행 4, 00:00:00 행 1, 전날 23:00 행 1, 쪽 크기 2, 실행 00:00:10.
    - PR 코드: 호출 `/ → 000001 → 전날 235959 → …`, `000001`은 1번, 지금 걸음 `midnightCuts=1`·`crowdedSeconds=1`. 00:00:00 행은 못 받지만 셈으로 드러남. 끝남.
    - M3: 같은 커서 `000001`을 **10번** 되풀이(지금 걸음 6번 + 백필 걸음 4번, 각 걸음 `MAX_EMPTY_PAGES`=5에서 "커서가 움직이지 않음"으로 멈춤), `midnightCuts=0`.
    - M5: 호출은 같고 `midnightCuts=0`(손실이 조용히 묻힘).
  - CI(REST 조회, 커밋 `20b55067`): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 모두 success.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-app-engine`·`autotrade-config`·`autotrade-domain` 변경 없음(diff 파일 목록). `autotrade-risk` 55·`autotrade-app-engine` 68건 통과 |
| A2 | 통과 | 수집기 한국투자 주소·호출 경로 변경 없음. `KisNewsClient` 변경은 `KisRejected` 메시지 문구 한 줄(KisNewsClient.java 60행). 보호 경로 목록·CODEOWNERS 그대로(protected-paths.txt 19~24행), `ArchitectureTest` 46건 통과 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음 |
| A4 | 해당 없음 | 주문 재전송 변경 없음. `EGW00215`는 안내 문구만 바뀌고 재시도하지 않는 동작은 그대로(D-054 4번과 일치) |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 통과 | 계좌별 직렬 큐·토큰 버킷·수집기 간격·하루 상한 변경 없음. 새 커서 규칙은 날짜 경계에서 호출을 최대 1번 더 함(`000001`). PR 코드에서는 커서가 엄격히 줄어 끝남을 탐침으로 확인. 다만 그 끝남을 지키는 `current.isAfter(oneSecond)` 갈래에 시험이 없음(M3 생존, 아래 A11·R1) |
| A8 | 통과 | 프로세스 경계·장 시간 보호 구간 확인(`beforeCall`) 변경 없음. 실전 앱키 공유는 PR #222 사용자 결정(D-054 R1 수집기 예외, 점검표 D8) 그대로이고 넓히지 않음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음(`autotrade-broker-*`, `autotrade-app-gateway` 무변경) |
| A10 | 통과 | 새 시험 값에 키·토큰 없음(`EGW00215` 응답 본문만). 오류 메시지는 결과 코드와 안내 문구뿐. CI 키 패턴이 대시 없는 이름·작은따옴표·`_`/`-` 값까지 넓어졌고 저장소 전체 통과, CI Secret scan success |
| A11 | **실패** | 보호 테스트 변경 없음, 시험 삭제·`@Disabled` 없음, 기존 시험은 늘기만 함(호출 수 고정 단언 2곳 추가). 그러나 새 동작 두 갈래에 실패를 잡는 시험이 없다: (1) `000001`로 받은 쪽이 00:00:01 행으로 가득 찼을 때 같은 커서를 되풀이하지 않게 하는 `current.isAfter(oneSecond)`(KisNewsCollector.java 241행, M3 생존: 지우면 같은 커서 10번 호출), (2) 셈 조건을 "쪽이 전날 행까지 닿지 않음"으로 넓힌 부분(243행, M5 생존: 이전 판의 좁은 셈으로 되돌려도 통과). (2)는 D-136 후속 고침 2의 1번이 적은 바뀐 동작 그 자체다 |
| A12 | 통과 | 장애표 #14(한도 초과 시 재시도 없이 호출자 백오프)와 같은 방향: `EGW00215`도 같은 안내. #17(실전 키 공유)은 PR #222 결정 그대로. 장애 테스트 8종 대상 코드 변경 없음 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. 수집기 시각은 서울 기준 그대로 |
| A14 | 통과 | D-136 "후속 고침 2" 1~4번이 코드와 일치: 1 `walk`의 `dayStart`·`oneSecond`·셈 조건(커밋 `20b55067`의 KisNewsCollector.java 236~247행)과 Walk 설명 "넉넉하게 셈", 2 시험 2개·단언 2개, 3 CI 패턴, 4 `KisRejected`. 결정 README 행 갱신. "추천안 채택(자율)"은 실전 키 규칙을 완화하지 않는 고침이라 지침 8장 2·4번에 걸리지 않음 |

## 점검표 밖 발견
- R1 (A11 수정 항목) 위 두 갈래를 한 시험으로 잡을 수 있다. 예: 00:00:05 행 1, 00:00:01 행이 한 쪽보다 많고, 00:00:00 행 1, 전날 행 1, 쪽 크기 2에서 (가) `000001` 커서 호출이 1번뿐(또는 2번 이하), (나) 지금 걸음 `midnightCuts`가 1 이상. 위 탐침으로 PR 코드는 통과, M3·M5는 각각 실패함을 확인했다. 계좌 위험은 낮다(되풀이는 걸음마다 `MAX_EMPTY_PAGES`=5에서 멈추고 하루 상한·장 시간 확인이 그대로) `[추론]`. 그러나 실전과 같은 앱키를 나눠 쓰는 호출기의 호출 수를 정하는 갈래이고, 셈은 손실을 드러내는 유일한 표시라 시험으로 고정해야 한다.
- R2 (참고, 판정 영향 없음) CI 키 패턴은 이름-값이 `:`로 이어지고 따옴표가 있는 모양만 본다. 대문자 `APPKEY`, 따옴표 없는 YAML·properties, `.header("appkey", "<값>")`처럼 쉼표로 이어진 Java 리터럴은 잡지 않고 gitleaks에 기댄다. 지금 저장소에는 그런 값이 없다. 다음에 고칠 때 `-i`와 `[:=,]` 정도를 검토할 만하다.
- R3 (참고) 탐침에서 00:00:00 행을 못 받은 지금 걸음도 `complete=true`로 훑은 구간에 들어간다. 이는 앞 판부터의 설계(손실은 `midnightCuts`·`crowdedSeconds`로 리포트에 드러냄)이고, 남은 위험으로 D-136 끝에 적혀 있다. 뉴스 지표를 실전 판단에 쓰기 전 이 셈이 0이 아닌 날을 어떻게 다룰지 정해야 한다.
- 앞 검토(PR #225) R1(`EGW00215` 안내) 해결 확인.

## 판정
수정 필요(A11: `KisNewsCollector.walk`의 `current.isAfter(oneSecond)` 되풀이 방지 갈래와 넓힌 자정 셈 조건에 실패를 잡는 시험 추가, R1)

## 재검토 2026-10-23
- 대상: PR #228 / 코드 커밋 `9aa81081`(행 번호는 이 커밋 기준, 이후 `9ffa5ad7`은 architect 원문만 추가) / `git diff origin/main...HEAD` 전체(12개 파일). 앞 검토(`20b55067`) 뒤 바뀐 것: `KisNewsCollector.walk`의 자정 판단(다음 커서의 날 기준)·셈 조건, `CollectorReports` 안내 문구, 시험 2개, D-136 1·3번 문구, `check-collector-keys.sh`의 `grep -i`
- 점검표: A
- 직접 실행한 것:
  - `9aa81081`을 별도 worktree에서 `./gradlew build --offline --no-daemon`: **BUILD SUCCESSFUL, EXIT 0**. 시험 1,932건, 실패·오류·건너뜀 0(`autotrade-app-collector` 148, `autotrade-architecture-test` 46, `autotrade-risk` 55, `autotrade-app-engine` 68).
  - `bash scripts/ci/check-collector-keys.sh`: exit 0(저장소 전체, `-i` 추가 뒤에도 거짓 양성 없음).
  - 변이(수집기 시험만, 매번 되돌림). 모두 잡힘:
    - M3 `current.isAfter(oneSecond)` 삭제 → `자정_1초_행이_한_쪽보다_많으면_000001을_되풀이하지_않고_자정을_센다`(000001 호출 2번 이하 단언) 실패
    - M4 `pageOldest.isAfter(dayStart)` 삭제 → `자정_행이_한_쪽보다_많으면_확인_못_한_것을_세어_알린다`, `쪽이_커서보다_앞선_날의_00시_행에서_끝나도_000000을_보내지_않고_센다` 실패
    - M5 셈을 좁힘(쪽이 정확히 00:00:00에서 끝날 때만) → `자정_1초_행이_…_자정을_센다`(`midnightCuts` ≥ 1) 실패
    - M7 자정 판단을 앞 판(`next.equals(current의 날 00:00:00)`)으로 되돌림 → `쪽이_커서보다_앞선_날의_00시_행에서_끝나도_…` 실패(000000 전송)
    - M8 셈을 항상 함 → `백필이_보관_한도에_닿으면_…`, `KisNewsCommandTest.거른_행과_자정_확인_못_함이_…` 실패
    - M9 `midnightCuts++` 삭제 → 시험 5개 실패
  - 끝남 확인(코드 읽기, KisNewsCollector.java 216~247행): `next`는 229~231행에서 `current`보다 엄격히 앞서고, 자정 갈래(236~241행)는 `oneSecond`를 `current.isAfter(oneSecond)`일 때만 고르며 아니면 `dayStart - 1초`로 더 앞선다. 커서가 매 쪽 엄격히 줄어 같은 커서를 되풀이하지 않는다. `000000`은 어느 날이든 보내지 않는다(236행 조건이 시각만 봄).
  - CI(REST 조회, 커밋 `9aa81081`): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 모두 success.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-app-engine`·`autotrade-config`·`autotrade-domain` 변경 없음(diff 파일 목록). `autotrade-risk` 55·`autotrade-app-engine` 68건 통과 |
| A2 | 통과 | 수집기 한국투자 주소·호출 경로 변경 없음(`KisNewsClient`는 `KisRejected` 문구 한 줄). 보호 경로 목록·CODEOWNERS 무변경, `ArchitectureTest` 등 46건 통과 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음 |
| A4 | 해당 없음 | 주문 재전송 변경 없음. `EGW00215`는 안내 문구만 바뀌고 재시도하지 않음(D-054 4번과 일치) |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음 |
| A7 | 통과 | 계좌별 직렬 큐·토큰 버킷·수집기 간격·하루 상한 변경 없음. 자정 경계에서 더 부르는 것은 날마다 `000001` 최대 1번이고, 같은 커서 되풀이 방지가 시험으로 고정됨(M3 잡힘, KisNewsCollectorTest `자정_1초_행이_…`) |
| A8 | 통과 | 프로세스 경계·장 시간 보호 구간 확인(`beforeCall`) 변경 없음. 실전 앱키 공유는 PR #222 사용자 결정(D-054 R1 수집기 예외) 그대로이고 넓히지 않음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음(`autotrade-broker-*`, `autotrade-app-gateway` 무변경) |
| A10 | 통과 | 새 시험 값에 키·토큰 없음(가짜 일련번호·종목코드뿐). CI 키 검사가 대소문자 무시로 넓어졌고 저장소 전체 통과, CI Secret scan success |
| A11 | 통과 | 보호 테스트 변경 없음, 시험 삭제·`@Disabled` 없음. 앞 검토 A11 실패 두 갈래(되풀이 방지, 넓힌 셈)와 이번에 바뀐 다음 커서 날 기준 자정 판단에 실패를 잡는 시험이 생김: 위 M3·M5·M7 모두 잡힘 |
| A12 | 통과 | 장애표 #14(한도 초과 시 재시도 없이 호출자 백오프)와 같은 방향, #17(실전 키 공유)은 PR #222 결정 그대로. 장애 테스트 8종 대상 코드 변경 없음 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. 수집기 시각은 서울 기준 그대로 |
| A14 | 통과 | D-136 후속 고침 2의 1번(다음 커서의 날로 00:00:00 판단, 넉넉한 셈)·3번(대소문자 무시)이 코드(KisNewsCollector.java 236~247행, check-collector-keys.sh 18행)와 일치. 리포트 안내 문구(CollectorReports.java 273행)도 "넉넉하게 셈"과 일치. 결정 README 행 갱신 |

## 점검표 밖 발견 (재검토)
- 앞 검토 R1 해결 확인: 제안한 탐침과 같은 모양의 시험이 들어왔고, 단언은 `000001` 호출 2번 이하·`midnightCuts` ≥ 1이다. 정확한 값(1번·1) 대신 범위 단언이라 느슨하지만 M3(10번)·M5(0)를 잡기에 충분하다.
- R2(CI 키 패턴) 일부 해결: 대소문자는 이제 잡는다. 따옴표 없는 YAML·properties, `.header("appkey", "<값>")`처럼 쉼표로 이어진 Java 리터럴은 여전히 gitleaks에 기댄다(참고, 판정 영향 없음).
- R3(00:00:00 행을 못 받은 걸음도 `complete=true`)은 앞 판과 같은 설계이고 D-136 "남은 위험"에 적혀 있다. 뉴스 지표를 실전 판단에 쓰기 전 이 셈이 0이 아닌 날의 처리를 정해야 한다(참고).

## 판정 (재검토 2026-10-23)
승인
