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
