# architect 검토: 실전 엔진 저장소 B1: 원장 모듈·쓰기 잠금·Guard 두 단계 올림 (D-146, ADR-015)
- 대상: PR #248 / 커밋 `14ea305a79cd6b875b57b67035b3d62bc5fd19a7` / `origin/main...origin/claude/project-thread-zoyak3` diff 전체(55개 파일, +3763 −72)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`: BUILD SUCCESSFUL (143 tasks, 처음 실행 때 대부분 UP-TO-DATE). 그래서 바뀐 모듈은 `cleanTest`로 시험을 다시 돌렸다.
  - `./gradlew --offline :autotrade-real-store:cleanTest :autotrade-real-store:test :autotrade-risk:cleanTest :autotrade-risk:test :autotrade-app-engine:cleanTest :autotrade-app-engine:test :autotrade-architecture-test:cleanTest :autotrade-architecture-test:test :autotrade-notification-jar:cleanTest :autotrade-notification-jar:test`: BUILD SUCCESSFUL. JUnit XML 집계: real-store 28건, risk 68건, app-engine 114건, architecture-test 47건, notification-jar 86건, 실패·오류·건너뜀 모두 0.
  - 결함 재현용 임시 시험(`ZzReviewProbeTest`, 커밋하지 않고 지움): 아래 발견 1의 재현 결과를 그대로 옮긴다.
  - 추가된 `@Disabled`·`assume*`·지운 `@Test`를 diff에서 찾았고 없었다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --offline` 통과와 바뀐 모듈 5개 `cleanTest` 재실행 통과(검토자가 직접 실행) |
| B2 | 통과 | `autotrade-real-store/build.gradle.kts:2` `autotrade.pure-java-conventions`(build에서 `verifyPureDependencies` 실행), 의존 `risk`(api)·Jackson만. `ArchitectureTest`에 `ONLY_ENGINE_USES_REAL_STORE`·`REAL_STORE_DOES_NOT_DRIVE_GUARD`와 위반 고정 예제 `MisplacedRealStoreUse`·`GuardRestorer` 추가, 통과. `ModuleOwnershipTest.OWNERS`에 `com.autotrade.realstore`, `RealPathReflectionTest`에 프로젝트·패키지 추가. 게이트웨이·감시·수집기·실험 명령 `processIsolation` 금지 목록에 추가. ADR-001 모듈 표(`docs/adr/ADR-001-module-structure.md:105`)와 ADR-015 13장·D-146에 근거 있음. 실험실은 `processIsolation`이 없지만 ArchUnit 규칙이 실험실 코드도 막는다 |
| B3 | 통과 | 원장 줄 형식·폴더 구조·잠금은 ADR-015 2.3·2.4·2.9(이미 머지된 설계)대로이고 13장에 구현 반영표, D-146 신설, ADR-002 2.x 코드 위치 표와 6장, ADR-006 후속 표 갱신. 새 알림 종류 `engine-store`는 `EventRoutes.java`와 `EventRoutesTest`에 등록. 새 명령행 인자 `store-probe`는 7단계 절차서 P11에 적음. 설정 키·REST API·DB 스키마 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 새 코드는 시각을 주입받은 `Clock`으로만 얻고(`EngineStore.java:167`, `LedgerWriter` 생성자), `System::nanoTime`은 진입점 `EngineMain.storeProbe`에서만 넘긴다. 난수 없음. ArchitectureTest의 시계·난수 규칙 통과 |
| B5 | 해당 없음 | 금액을 다루는 코드 없음 |
| B6 | 통과(단, 발견 1의 경우는 시험이 없음) | real-store 시험 28건(ADR-015 시험 1·2·6·13·14, 기동 원장 손상, `degraded`, 실측 도구), `RealTradingGuardTest` 두 단계 올림 시험(감사 실패 미적용, 같은 계좌 내림 무효화, OFF 출발 올림 중 킬스위치, ABA, 두 올림 거절, 다른 계좌 지연 없음), `GuardTransitionTest`, `EngineStoreWiringTest` 5건. 지운 시험·약화한 단언은 diff에서 찾지 못함. 묶음 안 중간 줄 쓰기 실패 시험은 없다(발견 1) |
| B7 | risk-ops 검토 필요 | `RealTradingGuard`(두 단계 올림), `GuardClosedException`을 `final`에서 풀고 하위 타입 `RaiseNotAppliedException` 추가, Guard 감사 어댑터 교체, 보호 테스트(`ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`·`RealTradingGuardTest`)와 `protected-paths.txt`·`CODEOWNERS` 변경. 지침 10·11장의 실전 관련 PR이라 A 점검표 검토와 독립 QA, 사용자 머지가 필요하다 |
| B8 | 통과 | diff에 실제 키·계좌번호 없음(시험은 자리표시 `00000000-99`). `EngineMain.secretValues`는 가리기용으로 메모리에서만 쓰고 로그에 싣지 않음. 원장 기록 직전 `SecretMasker`로 가림(`SecretMaskerTest`, EngineStoreTest 시험 14). gitleaks는 이 컨테이너에 없어 CI 결과에 맡긴다 |

## 점검표 밖 발견

### 1. [수정 필요] 한 묶음 안에서 뒤 줄 쓰기가 실패하면 앞 줄도 지워지는데, 앞 줄은 "기록됨"으로 끝난다 (올림 감사 불변식 위반 가능)
- [사실] `LedgerFile.append`는 실패하면 `rollback()`(`LedgerFile.java:197-211`)을 부르고, `rollback`은 **마지막 강제 기록 길이(`durableSize`)**까지 잘라 낸다(`:202`). 같은 묶음에서 앞서 붙였지만 아직 강제 기록 전인 줄도 함께 잘린다.
- [사실] 그런데 `LedgerWriter.write`(`LedgerWriter.java:115-157`)는 앞 줄을 `written`에 넣어 둔 채(`:127`) 계속 진행한다. 되돌리기로 `dirty`가 false가 되어 `LedgerFile.force()`는 아무것도 하지 않고 돌아오고(`LedgerFile.java:117`), 앞 줄은 `p.done.complete(p.rseq)`(`LedgerWriter.java:154`)로 성공 처리된다.
- [사실] 재현(임시 시험, 커밋 안 함): 줄 C를 쓰는 중에 강제 기록 문을 막아 두고, A(정상)와 B(직렬화 중 예외)를 같은 묶음에 넣었다. 결과: `C=1`, `A=2`(성공으로 끝남), `B failed=true`, 파일에는 C 한 줄만 있음(`A_IN_FILE=false`). 그 뒤 `resyncFromDisk`가 `lastRseq`를 1로 되돌리므로 다음 줄이 A에게 돌려준 `rseq` 2를 다시 쓴다.
- [추론] 영향: A가 Guard 올림 감사라면 `StoreGuardAuditLog.raise`(`StoreGuardAuditLog.java:69`)는 성공을 받고 Guard는 올림을 적용한다. ADR-015 2.5·D-146 결정 6의 "올라가는 전이는 감사 기록이 성공해야 적용"이 깨지고, 원장에는 그 전이가 없다. 현실적인 계기는 같은 묶음의 뒤 줄 쓰기 중 디스크 가득 참(ENOSPC)·백신 차단 같은 `IOException`이다. 거래일이 바뀌는 경우는 `ensureFile`이 앞 파일을 먼저 강제 기록하므로 해당하지 않는다.
- 고칠 방향(어느 쪽이든): (가) `append` 실패 때 그 줄을 쓰기 직전 위치까지만 자르도록 줄 시작 위치를 기억해 되돌린다, 또는 (나) `write`에서 한 원장의 `append`가 실패하면 그 묶음에서 같은 원장에 이미 붙인 `written` 항목을 모두 실패로 끝낸다(되돌리기가 그 줄들을 지웠으므로). (나)가 단순하고 "기록됨 = 강제 기록됨" 불변식을 직접 지킨다. 어느 쪽이든 위 재현을 시험으로 남긴다(`EngineStoreTest` 또는 `LedgerWriter` 단위 시험).

### 2. [권고] 기동 원장 중간 손상 때 부팅 번호가 겹칠 수 있다
- [사실] `LedgerScan.scanFile`은 손상 줄에서 멈추고 그 앞까지의 `maxBoot`만 돌려준다(`LedgerScan.java:153·156·169`). 기동 원장이 손상이면 옮기고 새로 시작한다(`EngineStore.java:236`).
- [추론] 손상 줄 뒤에 더 큰 부팅 번호의 기동 기록이 있었고 그 부팅이 계좌·명령 원장에 아무것도 쓰지 않았다면(활성화·명령 없이 켰다 끈 경우), 새 부팅 번호가 앞 부팅 번호와 같아질 수 있다. 지금(B1)은 "쓰기 주체 둘" 판정이 `<` 비교라 거짓 차단은 없고 키 발급도 없어 실전 영향은 없다. 그러나 ADR-015 시험 5("`boot` 원장이 깨져도 부팅 번호가 겹치지 않음")를 B4에서 지키려면 손상 원장의 뒤쪽 줄에서도 `boot` 칸을 최선으로 읽거나, 기동 기록 사본을 계좌 원장에도 남기는 방법을 B4 설계에 넣어야 한다. B4 후속 과제로 적어 두기를 권한다.

### 3. [사소] 표기
- `RealTradingGuard.java:450-451` `PendingRaise` 위에 Javadoc 주석 두 개가 연달아 있다(앞 것은 쓰이지 않음). 하나로 합친다.
- 결정 번호가 D-142 다음 D-146이다. D-143~145가 다른 열린 PR에 예약된 것인지 [가정]이며, 머지 순서에 따라 README 표 순서를 확인한다.

### 확인한 설계 판단(문제 없음)
- Guard 두 단계 올림: ③에서 상태가 아니라 전이 번호(`transitions`)와 `raiseInterrupted`로 비교해 ABA와 "상태가 안 바뀌는 내림(OFF 계좌 킬스위치)"을 모두 잡는다. 같은 계좌 두 번째 올림 거절, 감사 실패 시 `raising` 해제(`finally`)도 맞다. `GuardClosedException`을 `final`에서 풀었지만 생성자가 패키지 전용이라 바깥 패키지가 Guard 판정처럼 보이는 예외를 만들 수 없다.
- (가) 전면 차단을 Guard API 변경 없이 감사 어댑터가 올림 감사를 거절해 집행하는 방식은 ADR-015 2.10("Guard 변경은 올림 감사 이동만")과 맞고, 저장소가 붙기 전 올림 거절도 같은 경로로 막힌다.
- 잠금 순서 `engine.lock` → `writer.lock`, 실패 시 역순 닫기(`EngineMain` 기동 실패 경로에서 저장소를 채널보다 먼저 닫음), 정상 종료 순서(처리기 → 채널 → 저장소 → 하트비트 → 알림 큐)가 D-146 결정 10과 일치한다.
- 원장 코덱은 CRC 대상 바이트를 줄에서 직접 되살려 대조하므로 재직렬화 차이로 인한 거짓 손상이 없다. 트리(`JsonNode`)만 써서 D-086 허용 목록을 늘리지 않았고 `RealPathReflectionTest`가 통과한다.

## 판정
수정 필요(발견 1: 같은 묶음 안 뒤 줄 쓰기 실패 때 앞 줄이 지워진 채 "기록됨"으로 끝나는 결함 수정과 재현 시험 추가). 발견 2는 B4 후속 과제로 기록, 발견 3은 고치면 좋음. B7에 따라 risk-ops 검토가 따로 필요하다.

## 재검토 2026-10-26
- 대상: PR #248 / 커밋 `1123e80c` (앞 검토 `14ea305a` 뒤 수정 커밋 `34fd5870`·`1123e80c`, 검토 원문 커밋 제외) / `origin/main...HEAD` diff 전체를 다시 보고, 수정분은 `git diff 14ea305a 1123e80c`로 따로 봄
- 점검표: B
- 직접 실행한 것: `git archive HEAD`로 다른 폴더(새 Gradle 출력, 캐시 없음)에 풀어 `./gradlew build --offline` → BUILD SUCCESSFUL(4분 30초, UP-TO-DATE 3개뿐). JUnit XML 집계: real-store 31건(앞 28 + 새 3), risk 68건, app-engine 114건, architecture-test 47건, notification-jar 86건, 실패·오류·건너뜀 0. 수정분에서 지운 `@Test`·추가된 `@Disabled` 없음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 새 폴더 `./gradlew build --offline` 통과(검토자가 직접 실행) |
| B2 | 통과 | 수정분은 `autotrade-real-store`·`autotrade-risk` 안에서만 바뀜. 새 의존·패키지 없음. ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest 통과 |
| B3 | 통과 | 공개 계약 변경 없음(`WriterLock.readByLockedChannel`은 패키지 전용). D-146 위험과 한계·검증에 F-2·F-3·발견 1·권고 2 반영 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. `LedgerWriterTest.awaitBacklog`의 `System.nanoTime`은 시험 코드의 대기 기한이며 운영 코드는 주입 `Clock`만 씀 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | 발견 1 재현 시험 `LedgerWriterTest.같은_묶음의_뒤_줄_붙이기가_실패하면_되돌려진_앞_줄도_기록됨으로_끝나지_않는다`(A 실패, 파일에 A 없음, 다음 줄 rseq 2, 재검사 OK)가 앞 검토의 재현과 같은 조건이고 통과. F-2·F-3 시험 2건(`EngineStoreTest`) 통과 |
| B7 | risk-ops 검토 필요 | 수정분도 `RealTradingGuard.java`(주석만)와 실전 엔진 저장소(보호 경로 `autotrade-real-store/*`)를 바꿈. 실전 관련 PR이라 risk-ops 재검토·독립 QA·사용자 머지 필요 |
| B8 | 통과 | 새 차단 사유 문구는 예외 종류와 메시지(파일 경로)만 실음. 경로에는 계좌 별칭만 있고 비밀값 없음. gitleaks는 CI 결과에 맡김 |

### 앞 발견 처리
- **발견 1(수정 필요) → 해결.** `LedgerWriter.java:128-139`: 붙이기가 실패하면 그 묶음에서 같은 원장에 먼저 붙인 `written` 항목을 모두 빼고 실패로 끝낸다. 되돌리기(`durableSize`까지 자르기)가 지우는 범위는 "마지막 강제 기록 뒤에 붙인 줄"이고, 강제 기록은 묶음 끝에서만 일어나므로 지워지는 줄은 이번 묶음의 같은 원장 줄로 한정된다. 그래서 이 처리로 "기록됨 = 강제 기록됨"이 지켜진다. 같은 묶음에서 실패가 두 번 나도(가운데에 붙인 줄 포함) 같은 반복으로 모두 실패 처리된다.
  - [추론, 사소] 거래일이 바뀌는 묶음에서는 `ensureFile`이 앞 날짜 파일을 먼저 강제 기록하므로, 그 앞 줄은 실제로는 디스크에 있는데 실패로 돌려질 수 있다. 올림은 적용되지 않는 쪽(더 막는 쪽)이고 원장에 보정 기록 없는 올림 줄이 남는다. D-146에 새로 적은 "원장의 Guard 전이 줄은 적용됨의 근거가 아니다"(risk-ops N-2)의 범위 안이라 고칠 필요는 없다.
- **권고 2 → 기록됨.** D-146 위험과 한계에 B4 후속으로 적힘.
- **사소 3 → `PendingRaise` 중복 Javadoc 합침.** 결정 번호 간격(D-143~145)은 그대로이며 머지 때 확인할 사항으로 남김.

### 수정분의 다른 변경(risk-ops F-2·F-3)
- F-2 `EngineStore.java`: 계좌 원장 검사·준비 중 `IOException`·`RuntimeException`이 나면 그 계좌만 `accountBlocks`에 넣고 계속 진행한다. 복구 결과 생성을 쓰기 스레드 준비 뒤로 옮겨 준비 실패 차단도 결과에 들어간다. 바깥 catch는 `RuntimeException`만 남았고 컴파일·시험 통과. 저장소가 열린 채 그 계좌의 올림은 감사 어댑터에서 거절되고 다른 계좌는 정상이다(시험 확인). 절대 규칙 6(계좌끼리 영향 없음)과 맞다.
- F-3 `WriterLock.java`: 경로로 읽기가 실패하면 잠금을 쥔 채널의 위치 지정 읽기(`read(buf, pos)`, 쓰기 위치를 바꾸지 않음)로 대조한다. 존재 확인과 `fileKey` 확인은 그 앞에서 그대로 한다. [추론] 경로 읽기가 권한 변경 같은 다른 이유로 실패해도 내용이 같으면 알림이 나지 않지만, 쓰기 주체 둘을 가리는 목적(파일 교체·내용 변경 감지)은 유지된다. Windows 실제 동작은 검증하지 못했고 절차서 P12에 남아 있다(D-146에 적힘). 실전 안전 판단은 risk-ops 몫이다.

## 판정
승인

## 재검토 2 2026-10-26
- 대상: PR #248 / 커밋 `e2d237d0` (앞 재검토 `13b60780` 뒤 코드 변경은 `e2d237d0` 하나, `b5cc9a80`은 risk-ops 원문) / `origin/main...HEAD` diff 전체를 다시 보고, 변경분은 `git diff 13b60780 e2d237d0`로 따로 봄
- 점검표: B
- 직접 실행한 것: `git archive HEAD`로 새 폴더에 풀어 `./gradlew build --offline` → BUILD SUCCESSFUL(4분 29초, UP-TO-DATE 3개뿐). JUnit XML 집계: real-store 34건(앞 31 + 새 3), risk 68건, app-engine 114건, architecture-test 47건, notification-jar 86건, 실패·오류·건너뜀 0. 변경분에서 지운 `@Test`·추가된 `@Disabled` 없음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 새 폴더 빌드 통과(검토자가 직접 실행) |
| B2 | 통과 | 변경은 `autotrade-real-store` 안(`EngineStore`·`LedgerScan`)뿐. 새 의존 없음, ArchUnit 3종 통과 |
| B3 | 통과 | 공개 계약 변경 없음(`LedgerScan.unreadable`는 패키지 전용). 기동 거부 조건이 "`writer.lock` 못 잡음·폴더 심볼릭 링크"로 좁혀진 것은 D-146 위험과 한계에 적힘. ADR-015 2.7 ③("손상이면 차단·경고, 기동은 함")과 맞음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음, 시계·난수 새 사용 없음 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | 새 시험 3건(`블랙리스트_원장을_읽지_못하면_기동은_하고_모든_계좌를_전면_차단한다`, `기동_원장을_읽지_못하면_옮겨_두고_경고만_하고_기동한다`, `명령_원장을_읽지_못하면_폴더째_옮겨_두고_경고만_하고_기동한다`) 통과. 명령 원장 시험은 다시 열 때 경고 0건까지 확인. R-3·R-4 시험을 넣지 못한 이유는 D-146 검증에 적힘. R-3 연결은 앞 검토에서 확인한 `LedgerWriterTest`와 Guard의 감사 실패 시 전이 미적용 시험이 나눠 맡는 것이 맞다 |
| B7 | risk-ops 검토 필요 | 실전 엔진 저장소(보호 경로)의 차단 조건을 바꿈. risk-ops 재검토·독립 QA·사용자 머지 필요 |
| B8 | 통과 | 새 경고·차단 문구는 예외 종류와 메시지(경로)만 실음. 비밀값 없음 |

### 변경분 확인
- 공통 원장 읽기 오류(`scanCommon`, `EngineStore.java:611-617`)를 손상(`LedgerScan.unreadable`)으로 바꾼다. 블랙리스트는 모든 계좌 전면 차단, 기동·명령은 옮기고 경고. 앞 코드에서는 이 `IOException`이 `open` 밖으로 나가 기동 거부가 됐으므로, 블랙리스트 읽기 실패에서도 기동은 하되 올림을 모두 막는 쪽이 ADR-015 2.7 (가)와 맞다.
- 기동 원장을 옮기거나 새로 열지 못하면 `bootLedger`가 null이고 "기동 기록 실패"로 모든 계좌 전면 차단한다. `writeBootRecord`·`close`·`closeQuietly`가 null을 모두 처리한다. 명령 원장은 null이면 `recordCommand`가 프로세스 로그로만 남긴다. null 처리 빠진 곳을 찾지 못했다.
- 명령 원장 읽기 실패 때 `commandScan.file()`은 폴더이므로 폴더째 `commands.corrupt-<부팅>`으로 옮기고 `prepareDir`로 다시 만든다. 이름 접두로 폴더째 옮긴 경우를 가리는 방식은 날짜 파일 이름(`YYYY-MM-DD.jsonl.corrupt-…`)과 겹치지 않는다. 새 원장 첫 체크포인트의 `restartAfter`로 다음 기동 검사가 통과한다(시험 확인).
- [추론] 기동 원장을 읽지 못하면 그 원장의 `boot` 값이 빠져 부팅 번호가 겹칠 수 있다. 앞 권고 2·risk-ops R-2와 같은 문제로, D-146에 B4 후속으로 적혀 있어 이 PR에서 막을 일은 아니다.

### 사소(고치면 좋음, 판정에 영향 없음)
- `EngineStore.java:610`: `quarantine`의 Javadoc("손상 파일을 같은 폴더의 다른 이름으로 옮긴다")이 새로 끼운 `scanCommon` 위에 남아 `scanCommon`의 설명처럼 보인다. `scanCommon`에 맞는 주석을 달고 원래 주석은 `quarantine`(`:619`) 위로 옮긴다.

## 판정
승인

## 재검토 3 2026-10-27
- 대상: PR #248 / 커밋 `baf23da2` (앞 재검토 2 `b07350bf` 뒤: 독립 QA 원문 `f566b668`, main 병합 `54fc1a8c`, 반영 `baf23da2`) / `origin/main...HEAD` diff 전체를 다시 보고, 반영분은 `git show baf23da2`로 따로 봄. 병합 `54fc1a8c`는 main 쪽 변경(#245·#246·#252 등)을 가져온 것이며, 충돌 해소한 `docs/decisions/README.md`·`docs/qa/pending.md`는 main 줄을 지우지 않고 D-146·PR #248 줄만 더했음을 `git diff df22830b HEAD`로 확인
- 점검표: B
- 직접 실행한 것: `git archive HEAD`로 새 폴더에 풀어 `./gradlew build --offline` → BUILD SUCCESSFUL(4분 49초, UP-TO-DATE 3개뿐). JUnit XML 집계: real-store 44건, risk 68건, app-engine 115건, architecture-test 48건(main #247 규칙 포함), notification-jar 86건, 실패·오류·건너뜀 0. `git merge-tree --write-tree origin/main HEAD`(main 헤드 `de766487`): **`docs/qa/pending.md`·`docs/runbooks/stage-7-real-trading-tests.md` 충돌**(#243·#255가 병합 `54fc1a8c` 뒤에 main에 들어감). 코드 파일 충돌 없음

### 앞 검토의 잘못 인정
- 재검토 1에서 risk-ops F-3 반영(경로로 못 읽으면 잠금을 쥔 채널로 대조)을 "쓰기 주체 둘을 가리는 목적은 유지된다"고 판단했다. 이 판단은 틀렸다. 쥔 채널은 처음 잡은 옛 파일이라 늘 자기 도장과 같고, 다른 프로세스가 새 파일을 잠근 바로 그 경우를 놓친다. 독립 QA 발견 1이 맞다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 새 폴더 빌드 통과(검토자가 직접 실행) |
| B2 | 통과 | 반영분은 `autotrade-real-store`·app-engine 시험 안에서만 바뀜. 새 의존 없음. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 통과(main 병합 뒤 48건) |
| B3 | 통과 | 공개 계약 추가는 `StoreRefusal(String, Throwable)` 생성자 하나. `writer.lock` 잠금 구간(2^62 위치 1바이트)은 ADR-015 2.9 "쓰기 주체 하나"를 지키는 구현 선택으로 D-146 결정 7에 적힘. 묶음 F 감시 긴급 경로는 같은 `WriterLock`을 써야 같은 구간으로 서로 막힌다(같은 모듈이라 자연히 그렇게 됨, 아래 권고) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 복구 시간 시험의 `System.nanoTime`은 시험 코드의 측정 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | 새 시험: 잠금 구간 위치·파일 전체 잠금과 겹침, `fileKey` 없음 + 경로 읽기 실패 → 차단(발견 1 재현과 같은 조건), 주기 확인 예외에도 계속·차단(Q4), 공통 원장 폴더 자리에 파일(Q1), 하루치 복구 시간(Q2), 처리기 OPEN 미적용 분기(Q3, `EngineCommandHandlerTest`), 앞 날짜 파일에 강제 기록된 줄 유지(Q5, `LedgerWriterTest`), 긴 비밀값부터(Q6). 지운 시험은 `잠금을_쥔_채널로도_잠금_파일_내용을_대조할_수_있다` 하나이며, 결함이던 분기(`readByLockedChannel`)를 지우면서 함께 지운 것이라 통과시키려 지운 것이 아니다(D-146 검증에 적힘) |
| B7 | risk-ops 검토 필요 | 보호 경로(`autotrade-real-store/*`)의 쓰기 잠금·차단 조건과 실전 엔진 처리기 시험을 바꿈. risk-ops 재검토·독립 QA 재검토·사용자 머지 필요 |
| B8 | 통과 | 새 차단·거부 문구는 예외 종류·메시지(경로)만 실음. 절차서 P12 예시 경로는 `<경로>` 자리표시자 |

### 반영분 확인
- **발견 1**: `WriterLock.acquire`가 `tryLock(LOCK_OFFSET, 1, false)`로 내용 밖 구간만 잡고, `check`는 경로 읽기 실패를 곧 문제로 본다(쥔 채널 대조 분기 삭제). 엔진끼리는 같은 구간을 잡으려 해서 서로 막히고, 다른 프로세스의 전체 잠금(`tryLock()`)도 겹쳐 실패한다(시험 확인). 지운 뒤 같은 이름 새 파일은 ① 잠금·공유 없이 열면 경로 읽기 실패 → 차단, ② 읽히면 내용 대조 → 차단. [추론] Windows `LockFileEx`가 파일 끝 너머 큰 위치 잠금을 허용하고 그 구간 밖 읽기를 막지 않는다는 점은 운영 PC 미검증이며 절차서 P12 ①·④로 확인하게 적혔다.
- **Q1**: 공통 원장 폴더 준비 실패를 손상으로 다룬다(블랙리스트 → 모든 계좌 차단, 기동 → 기동 기록 실패로 차단, 명령 → 옮기고 경고). 그 밖의 입출력 실패는 `StoreRefusal`로 감싸 "실전 엔진 저장소를 열지 못해" 문구로 보인다. `open`의 catch 순서(`StoreRefusal`을 먼저 다시 던짐)도 맞다.
- **Q4**: `checkLockSafely`가 `Throwable`을 잡고 차단 쪽으로 둔다. `block` 안 알림 실패까지 삼켜 다음 주기가 이어진다.
- **Q5**: 되돌린 뒤 원장의 `lastRseq`(디스크 기준, `resyncFromDisk`)보다 큰 줄만 실패로 돌린다. `rseq`는 날짜 파일 사이에 체크포인트로 이어지므로 비교가 성립하고, 거래일 전환 중 새 파일을 열지 못해 `channel == null`로 되돌리기를 건너뛴 경우에도 `lastRseq`는 앞 파일에 강제 기록된 마지막 줄이라 맞다. 되돌리기 실패(`broken`)면 모두 실패(안전 쪽). 재검토 1에서 사소로 적은 경우가 해소됐다.
- **Q2·Q3·Q6**: 시험·코드 확인, 문제 없음.

### 권고(판정 영향 없음)
- **main 다시 병합 필요**: 지금 main(`de766487`)과 `docs/qa/pending.md`·`docs/runbooks/stage-7-real-trading-tests.md`가 충돌한다. 절차서는 이 PR의 P11·P12와 #243의 I1 보완이 같은 문서를 고친 것이라, 병합 때 양쪽 줄을 모두 살렸는지 확인해야 한다. 병합 커밋은 문서만 바뀌어야 하며, 코드가 바뀌면 다시 검토를 받는다.
- 복구 시간 시험(`하루치_최대_기록_가정으로…`)은 벽시계 3초 상한이다. 이 환경 0.7~0.8초라 여유는 있으나 CI가 느릴 때 흔들리면 상한을 늘리거나 측정만 출력하는 쪽으로 바꾼다.
- 묶음 F에서 감시가 저장소를 잡을 때 `WriterLock`을 그대로 써서 같은 잠금 구간을 쓰도록 묶음 F ADR에 적는다(다른 구간을 잡으면 서로 막히지 않는다).

## 판정
승인
