# QA 검토: PR #87 QA 권고 후속(디스크 하한 1GB 미만 거부, 날짜 넘긴 비정상 종료 시험, ADR-010 채택)
- 대상: PR #91 / 커밋 a0c3feaef00fb2e7ab852c95672e75b3880a1c14 / main 대비 diff 전체(merge-base 40388a4 = PR #87 머지 커밋. 현재 origin/main은 5eb0885로 #89·#88 두 커밋 앞서 있음)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 실전 주문 경로·Guard·블랙리스트 코드는 바뀌지 않았지만, 장애 대응(장애표 #10, 5.2 2번 정지 순서)의 실험실 디스크 하한 동작을 바꾼다. 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-91/architect.md`, `risk-ops.md`)의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 11파일, +118 −12. 운영 코드는 `LiveLabService.java` 한 파일(생성자 범위 검사). 시험 2파일, 문서 8파일
  - 보호 영역 변경 여부: `autotrade-architecture-test`, `autotrade-risk`, `autotrade-broker*`, `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-app-gateway`, `autotrade-control`, `autotrade-domain` diff **0줄**
  - **main과 병합 시험**: `git merge-tree --write-tree origin/main HEAD` → `docs/qa-out-status.md`, `docs/qa/pending.md` **CONFLICT (content)**. GitHub `mergeable_state: dirty`. 두 파일 모두 표 끝에 main(#88)과 이 PR이 각각 한 줄씩 덧붙여 생긴 충돌이다
  - `./gradlew build --no-daemon`(head a0c3fea): Maven Central 429로 12회 실패(재시도 스크립트가 실패 로그마다 429를 확인한 뒤에만 재시도, 루프 하나) 뒤 13회째 **BUILD SUCCESSFUL**(130 tasks). 테스트 결과 XML 합계 tests **1,058**, failures 0, errors 0, skipped 0. `LiveSessionTest` 10건·`LiveLabServiceTest` 6건 포함
  - CI(head a0c3fea, check-runs): **0건**. main과 충돌이라 병합 참조가 만들어지지 않아 PR CI가 이 헤드에서 돌지 않은 것으로 본다 `[추론]`. PR 본문의 "CI도 통과했다"는 이 헤드에서 확인되지 않는다
  - QA 변이 탐침(`LiveLabService` 생성자 검사, `:autotrade-app-lab:test --tests '*LiveLabServiceTest'`, 확인 뒤 원복):
    - M1 원래 `minFreeGb < 0`으로 되돌림 → `diskFloorMustBeAtLeastOneGb()` FAILED(잡힘)
    - M2 상한 검사 삭제(`minFreeGb < 1`만) → 같은 시험 FAILED(잡힘)
    - M3 하한 경계 `<= 1`(1GB도 거부) → **살아남음**
    - M4 상한 경계 `>= MAX_MIN_FREE_GB`(1,000,000도 거부) → **살아남음**
  - QA 탐침(`crashAndResume`에 출력만 넣어 실행, 확인 뒤 원복): 세 경우 모두 2일차 파일 생성, 결과
    - 같은 날 재개(crashAt 1,011): restored 1,010, 1일차 파일 다시 열림(바뀜), **dataGaps 2**(RECORDER_RESTART 2종목)
    - 1일차 중간 종료 → 2일차 재개(crashAt 762, resumeFrom 1,525): restored 761, 1일차 파일 그대로, **dataGaps 0**
    - 1일차 끝 종료 → 2일차 재개(crashAt 1,525): restored 1,524, 1일차 파일 그대로, dataGaps 0
  - diff 추가 줄에서 `appkey|secret|password|token\s*=|계좌번호` grep: 걸린 것은 검토 원문 설명 줄뿐, 값 0건

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` diff 0줄. `RealTradingGuardTest`·`GuardedOrderGatewayTest` 위 빌드에서 통과 |
| A2 | 통과 | 주문 출구·`ArchitectureTest` 변경 없음(diff 0줄), 위 빌드 통과. 바뀐 운영 코드는 실험실 생성자 검사뿐이고 주문 호출 없음 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 통과 | 블랙리스트 코드 변경 없음. `LiveSessionTest.blacklistedSymbolIsNeverBoughtInTheLiveSession` 위 빌드에서 통과 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `LiveLabService.java:108-113`: 1 미만·1,000,000 초과면 `IllegalArgumentException`. 0으로 하한을 끄는 길이 막혀 실험실이 먼저 물러나는 순서(5.2 2번)가 강해진다. 상한 1e6 × 2^30 ≈ 1.07e15 < `Long.MAX_VALUE`라 바이트 환산 넘침 없음(계산). 빈은 `LabWebConfiguration.java:100-111`에서 `@Value("${autotrade.lab.live.min-free-gb:20}") long`으로 만들어지므로 범위 밖이면 실험실 프로세스만 뜨지 않는다 `[추론]`(컨텍스트 기동 시험 없음). 실전 엔진은 실험실에 의존하지 않는다(엔진 diff 0줄, 위 빌드의 ArchUnit 통과). 기본값 20과 기본 생성자(`:87-91`) 그대로. 변이 M1·M2 잡힘 |
| A9 | 통과 | 실전 엔진·게이트웨이·보호 시세 경로 diff 0줄 |
| A10 | 통과 | 추가 줄에 키·계좌번호·토큰 없음(위 grep). 새 예외 메시지는 설정 키 이름과 숫자뿐(`LiveLabService.java:110-111`) |
| A11 | 통과 | 보호 테스트 파일 변경 0건, `@Disabled` 추가 0건. 기존 `crashWithLostTailAndUnclosedMetaStillReplaysTheSame`은 `crashAndResume(events, crashAt, crashAt)`로 옮겨졌고 꼬리 20바이트 자르기·`restored` 범위·`same` 확인이 그대로이며, 이어 받기마다 `ACCEPTED` 확인이 더해져 강해졌다(`LiveSessionTest.java:173-230`). 새 동작에 실패 경우 시험 있음(`diskFloorMustBeAtLeastOneGb`: 0·−1·상한+1 거부). 경계값 1·1,000,000 허용 시험은 없다(M3·M4 생존, 아래 R2) |
| A12 | 통과 | 장애표 #10과 5.2 2번 문구("하한 미만이면 시작 안 함·운용 중 멈춤, 기본 20GB")의 전제는 그대로이고 0으로 끄는 경우만 막았다. 대응표 행·장애 테스트 8종 전제 변경 없음. 범위 1~1,000,000은 config-keys(`config-keys.md:43`), 절차서(`stage-6-live-session.md:45`), ADR-010 2.7에 적혀 있다 |
| A13 | 해당 없음 | 시장별 코드 변경 없음. 새 시험 날짜 경계는 `Asia/Seoul` |
| A14 | 통과 | ADR-010 상태 "채택(D-076, PR #87 머지 `40388a4`)"과 ADR 목록 표 일치, `40388a4`가 PR #87 머지 커밋임을 `git log`로 확인. D-076 7번("디스크 여유(기본 20GB)")과 바뀐 동작이 어긋나지 않아 새 결정은 필요 없다 `[추론]`. ADR-010 6장 시험 목록(`LiveSessionTest` 10, `LiveLabServiceTest` 6)이 빌드 결과 수와 일치. 절차서 3장 새 줄의 "되살리는 중 정지 요청은 기록일 하나를 다 읽은 뒤 반영"은 `LiveSession.restore`의 기록일 단위 확인(`LiveSession.java:183-202`)과 일치. QA PR #87 R2·R4·R5·R6 처리 기록이 `docs/qa-out-status.md`에 있다. 다만 이 파일과 `docs/qa/pending.md`가 main과 충돌한다(아래 F1) |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(429 재시도 후), 1,058건 실패·오류·건너뜀 0 |
| B6 | 통과 | 새 동작에 시험 있음(A11). 건너뛰거나 지운 테스트 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음(`build.gradle.kts` 변경 0건, 운영 코드 변경은 생성자 검사 한 곳) |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **`min-free-gb=0`(또는 음수)로 설정해 디스크 보호를 끔**: 생성자에서 거부, 실험실이 뜨지 않음(`diskFloorMustBeAtLeastOneGb`, M1 잡힘). 실전 엔진은 별도 프로세스라 영향 없음. 보호 공백 없음.
2. **아주 큰 값(예: 999,999)으로 설정**: 허용되지만 모든 실시간 세션이 디스크 부족으로 시작 거부(`LiveLabService.java:282`) → 실험실 쪽만 멈추는 안전한 방향. 1,000,001 이상은 거부(M2 잡힘). 정수가 아닌 값은 Spring 변환 실패로 실험실 기동 실패 `[추론]`.
3. **실험실이 1일차 장중에 죽고 2일차에 다시 시작**: 계좌 되살리기·실시간 = 리플레이(same=true)는 새 시험과 QA 탐침으로 확인. 그러나 **1일차 남은 구간(탐침에서 763~1,525번째, 약 반나절)이 빠졌는데 `DataGap`이 하나도 남지 않는다**(dataGaps 0). 재기동 공백(`RECORDER_RESTART`)은 같은 날 파일을 다시 열 때만 남기 때문이다(`JournalWriter.java:66-72`, `DayWriter.java:83-109,172-208`). 1일차 파일은 메타 미종료로만 남는다. 실전 주문으로 이어지지는 않지만, 6단계 모의 성과(승격 근거)에 반나절 데이터 공백이 공백 표시 없이 섞이고 절차서 3장이 주간 점검에 옮기라는 `dataGaps`에도 나타나지 않는다(아래 R1). 이 PR이 만든 동작이 아니라 PR #87부터 있던 동작이고, 이 PR의 새 시험은 결정론(same)만 고정한다.
4. **충돌 해소 중 표 줄 유실**: 충돌 두 파일 모두 표 끝 덧붙이기라 양쪽 줄을 다 남기는 것이 맞다. 해소 때 main의 5단계 재판정 요청 줄(pending.md)이나 이 PR의 PR #91 요청 줄 중 하나가 빠지면 QA 대기 목록에서 사라진다(F1).

## 점검표 밖 발견
- **F1 main과 충돌(머지 불가)**: `docs/qa-out-status.md`, `docs/qa/pending.md`가 main(5eb0885, #88)과 충돌한다(`git merge-tree` CONFLICT, GitHub `mergeable_state: dirty`). 이 헤드 그대로는 머지할 수 없고, 해소하면 QA가 보지 않은 새 헤드가 된다. 같은 이유로 이 헤드에서 PR CI가 돌지 않았다(check-runs 0건). main을 병합해 두 표에 양쪽 줄을 모두 남기고, CI 통과를 확인한 뒤 재검토를 요청할 것. 코드·시험 내용에는 이 검토에서 실패 항목이 없으므로 재검토는 병합 커밋 diff와 빌드 확인이 중심이 된다.
- **R1 날짜를 넘긴 재기동은 공백 표시가 없음**(권고, 6단계 시작 전): 위 시나리오 3. 6단계 운용 결과를 승격 근거로 쓰기 전에, 재기동 때 마지막 기록일이 오늘이 아니고 그 날 메타가 미종료면 그 날 끝(또는 장 마감)까지를 `RECORDER_RESTART` 공백으로 남기거나, 최소한 `status`에 "미종료 기록일"을 드러내 주간 점검 항목으로 넣을 것. 2단계 판정 MD-05(재기동 공백을 meta segments만이 아니라 DataGap 이벤트로)와 같은 종류다.
- **R2 경계값 시험 없음**(권고): 1GB와 1,000,000GB가 허용되는지 보는 시험이 없어 경계를 잘못 옮긴 변이(M3 `<= 1`, M4 `>= MAX`)가 살아남는다. `diskFloorMustBeAtLeastOneGb` 옆에 1과 `MAX_MIN_FREE_GB`로 생성이 성공하는 경우를 더할 것.
- **R3 기동 실패 시험 없음**(참고, risk-ops N2와 같음): "범위 밖이면 실험실이 뜨지 않는다"는 즉시 생성 빈이라는 코드로만 뒷받침된다. 실전에는 영향이 없어 차단하지 않는다.

## 판정
수정 필요(F1: main과 충돌로 이 헤드는 머지 불가, 이 헤드의 CI 없음)

## 재검토 2026-10-14
- 대상: PR #91 / 커밋 81ecd6d70753cfe9f7baddb018b65107f1e2e0aa / main 대비 diff 전체. 새 커밋은 병합 커밋 74f7937(부모 9a24fe5, c21faa9)과 81ecd6d(R2 경계값 시험)
- 요청 문구("F1은 74f7937로 처리, R2는 81ecd6d, 대상 헤드 81ecd6d")는 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git show --remerge-diff 74f7937`: 자동 병합과 다른 곳은 `docs/qa-out-status.md`, `docs/qa/pending.md` 두 표뿐이고, 두 곳 모두 main 줄과 이 PR 줄을 **둘 다 남겼다**(main 줄 다음에 이 PR 줄). 그 밖에 병합 커밋이 새로 넣은 내용 없음. `git diff c21faa9 74f7937 --stat`은 1차 검토한 PR 내용(12파일, QA 보고서 포함)과 같다
  - `git show 81ecd6d`: `LiveLabServiceTest.diskFloorMustBeAtLeastOneGb`에 1과 `MAX_MIN_FREE_GB`로 생성·close가 성공하는 반복을 더했고, ADR-010 6장 시험 설명에 경계값 허용을 적었다. 운영 코드 변경 없음
  - **main과 병합 시험(다시)**: 현재 origin/main은 `5f7c2a1`(#92 "5단계(UI) 완료 기록(D-080)", 2026-10-14 03:22:38Z 머지)이다. 이 PR 병합 커밋의 main 부모 `c21faa9`보다 한 커밋 앞서 있다. `git merge-tree --write-tree origin/main 81ecd6d` → `docs/qa-out-status.md` **CONFLICT (content)**. #92가 같은 표 끝에 "stage-5.md 재검토 … PASS | D-080 5단계 완료 기록" 줄을 더했기 때문이다. `pending.md`는 충돌 없음. #92는 74f7937 병합과 CI 실행(03:18~03:20Z) 뒤, 재검토 요청(03:29Z) 전에 머지됐다
  - `./gradlew build --no-daemon`(head 81ecd6d): 1회째 **BUILD SUCCESSFUL**(130 tasks). 테스트 결과 XML 합계 tests 1,058, failures 0, errors 0, skipped 0(경계값 확인은 기존 시험 메서드 안에 들어가 건수는 같음)
  - CI(head 81ecd6d, check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success(03:18~03:20Z). 1차의 "이 헤드에서 CI 없음"은 해소
  - QA 변이 탐침(같은 방식, 확인 뒤 원복): M1 `< 0`, M2 상한 삭제, M3 `<= 1`, M4 `>= MAX` → **넷 다 `diskFloorMustBeAtLeastOneGb()` FAILED로 잡힘**. 1차의 M3·M4 생존 해소

| 항목 | 결과 | 근거 |
|---|---|---|
| A1~A13, B1, B6, 과금 | 1차와 같음(통과·해당 없음) | 1차 이후 바뀐 코드는 시험 한 곳(81ecd6d)뿐이고 운영 코드·보호 영역 변경 없음(`git diff 9a24fe5 81ecd6d --stat`에서 병합으로 들어온 main 내용을 빼면 시험 1파일·ADR-010 1줄). 빌드·CI 위와 같이 통과 |
| A14 | 통과 | ADR-010 6장 `LiveLabServiceTest`(6) 설명이 81ecd6d 시험과 일치. 병합으로 들어온 main 문서(D-079, ADR-001·002·005, 기획서)는 이 PR의 변경과 겹치는 줄 없음(remerge-diff) |
| F1 | **해소 안 됨** | 74f7937의 충돌 해소 자체는 올바르다(위 remerge-diff). 그러나 그 뒤 main에 #92가 머지되어 `docs/qa-out-status.md`가 다시 충돌하고, 이 헤드는 지금 머지할 수 없다(`git merge-tree` CONFLICT) |
| R2 | 해소 | 81ecd6d 경계값 허용 시험, 변이 M3·M4 잡힘 |
| R1 | 열림(권고, 이 PR을 막지 않음) | 날짜를 넘긴 재기동의 1일차 남은 구간 공백 표시는 이번 커밋에 없음. 6단계 결과를 승격 근거로 쓰기 전 과제로 남음 |

### 점검표 밖 발견(재검토)
- F1 재발 원인은 문서 표 끝 덧붙이기가 여러 PR에서 동시에 일어나는 것이다. 다시 병합할 때 `qa-out-status.md`에 #92의 D-080 줄과 이 PR의 PR #87 줄을 둘 다 남겨야 한다. 병합 커밋이 표 두 곳 외 다른 줄을 바꾸지 않으면, 다음 재검토는 remerge-diff·빌드·CI 확인으로 끝난다.

### 판정
수정 필요(F1: main `5f7c2a1`과 `docs/qa-out-status.md` 충돌로 이 헤드는 머지 불가)

## 재검토 2026-10-14 (2)
- 대상: PR #91 / 커밋 64509415ca5f960e62fae0d12c973dc557dc8447 / main `8f73cde` 대비 diff 전체. 새 커밋은 병합 커밋 6450941(부모 f894715, 8f73cde) 하나
- 요청 문구("main 8f73cde를 병합한 6450941로 처리, CI 통과")는 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git show --remerge-diff 6450941`: 자동 병합과 다른 곳은 `docs/qa-out-status.md` 한 곳뿐이고, main의 #92·#86·#93 줄 4개(D-080 5단계 재판정, 1단계 첫 판정·재검토 두 번)를 모두 남기고 그 뒤에 이 PR의 PR #87 줄을 남겼다. 그 밖에 병합 커밋이 새로 넣은 내용 없음
  - `git diff 81ecd6d 6450941`에서 이 PR의 코드·시험·ADR·config-keys·절차서·pending.md 변경 0줄. 바뀐 것은 main에서 들어온 문서와 이 QA 보고서뿐이다. 따라서 이전 재검토의 코드 확인(변이 M1~M4 모두 잡힘)은 그대로 유효하다
  - `git diff origin/main...HEAD --stat`: 12파일 +208 −12, 1차·재검토 때 본 PR 내용과 같은 파일 목록(+ QA 보고서)
  - **main과 병합 시험**: `git fetch` 뒤 origin/main = `8f73cde`(이 병합 커밋의 main 부모). `git merge-tree --write-tree origin/main 6450941` → **충돌 없음**. GitHub `mergeable_state: clean`, base sha 8f73cde
  - `./gradlew build --no-daemon`(head 6450941): 1회째 **BUILD SUCCESSFUL**(130 tasks, 그중 21개 실행·109개 최신 상태, 코드가 81ecd6d와 같아 테스트 작업 대부분 이전 실행 결과 재사용). 테스트 결과 XML 합계 tests 1,058, failures 0, errors 0, skipped 0
  - CI(head 6450941, check-runs): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 completed/success(03:43~03:46Z)

| 항목 | 결과 | 근거 |
|---|---|---|
| A1~A13, B1, B6, 과금 | 1차·재검토와 같음(통과·해당 없음) | PR 코드 변경 없음(위 diff 0줄), 빌드·CI 통과 |
| A14 | 통과 | 병합으로 들어온 main 문서(D-080·D-081·D-082, 기획서 v14.27, stage-1·stage-5 보고서)는 이 PR이 바꾼 줄과 겹치지 않음(remerge-diff에 qa-out-status.md 외 없음). ADR-010 상태·시험 목록은 재검토 때와 같음 |
| F1 | **해소** | 현재 main `8f73cde`와 충돌 없음, `mergeable_state: clean`, 이 헤드의 CI 성공 |
| R2 | 해소(재검토 때) | 변경 없음 |
| R1 | 열림(권고, 이 PR을 막지 않음) | PR 본문은 "이 PR 머지 뒤 별도 PR로 다룬다"고 한다. 6단계 결과를 승격 근거로 쓰기 전에 처리됐는지 확인할 것 |

### 점검표 밖 발견(재검토 2)
- 새 발견 없음. main이 다시 움직여 표 끝이 또 충돌하면 머지 전 같은 확인(remerge-diff에 표 줄 외 변경 없음, CI 통과)이 다시 필요하다.

### 판정
승인
