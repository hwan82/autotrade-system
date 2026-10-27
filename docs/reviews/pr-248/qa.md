# QA 검토: 실전 엔진 저장소 B1: 원장 모듈·쓰기 잠금·Guard 두 단계 올림 (D-146, ADR-015) (PR #248 QA)
- 대상: PR #248 / 커밋 `2af97c8b` (브랜치 `claude/project-thread-zoyak3`) / main 대비 diff 전체(merge-base = `c1972a03`, 59파일 +4,270 −72: 새 모듈 `autotrade-real-store` 운영 14파일·시험 6파일, `RealTradingGuard`·`GuardTransition`·`GuardClosedException`·새 `RaiseNotAppliedException`과 시험 2파일, 실전 엔진 배선(`EngineMain`, `EngineCommandHandler`, 새 `StoreCommandAuditLog`, `LoggingGuardAuditLog` 삭제, 새 `EngineStoreWiringTest`), 알림 종류 `engine-store`, 보호 시험·고정물 5파일(`ArchitectureTest`, `ModuleOwnershipTest`, `RealPathReflectionTest`, 위반 예제 2), `processIsolation` 금지 목록 4개, `protected-paths.txt`·`CODEOWNERS`, 문서(D-146, 색인, D-141, ADR-001·002·006·015, 7단계 절차서 P11·P12, `pending.md`, 검토 원문 2))
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --console=plain` (헤드 `2af97c8b`): 1~6회째 Maven Central 429로 의존성 받기 실패(빌드 실패로 보지 않음), 20초 간격 재시도 7회째 **BUILD SUCCESSFUL in 4m 39s, 143 작업 모두 실행**, 미러 없이. 시험 결과 XML 합계 2,056건, 실패·오류·건너뜀 0. `:autotrade-real-store` 34건, `:autotrade-risk` 68건, `:autotrade-architecture-test` 47건, `:autotrade-app-engine` 114건.
  - PR 헤드 CI(GitHub check runs, 2026-10-26T17:02Z): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens (Playwright) 모두 success.
  - `git merge-tree --write-tree origin/main HEAD`: **`docs/decisions/README.md`·`docs/qa/pending.md` 충돌**(main이 #247 `393ad55f`를 합친 뒤). 코드 파일은 자동 합침. 임시 작업 트리에서 main + 이 PR을 합치고(충돌 두 문서는 PR 쪽으로) `:autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-real-store:test :autotrade-risk:test` → BUILD SUCCESSFUL, 48·114·34·68건 실패 0(#247의 ArchUnit 규칙과 함께 통과). 작업 트리는 지움.
  - QA 임시 시험 `QaTmp248Test`(2건, `autotrade-real-store` 시험 폴더에 넣어 돌린 뒤 지움, `git status` 깨끗 확인): 아래 S3·S4. 둘 다 출력으로 재현.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 초기값 `Snapshot.INITIAL`(OFF, RealTradingGuard.java 스냅샷 레코드)은 그대로이고 원장에서 Guard 상태를 되살리는 코드가 없다. 저장소 패키지는 `RealTradingGuard`·`SecondConfirmation`에 의존하지 못한다(새 ArchUnit `REAL_STORE_DOES_NOT_DRIVE_GUARD`, 위반 예제 `GuardRestorer`를 잡는 단언 통과). `EngineStoreTest#원장에_OPEN_전이가_있어도_재기동하면_모든_계좌는_OFF다` 통과. 저장소가 붙기 전 Guard는 올림을 모두 거절(`EngineStoreWiringTest#저장소를_붙이지_않은_Guard는_올리는_전이를_거절한다`) |
| A2 | 통과 | `RealOrderGateways`·`GuardedOrderGateway` 무변경. ArchitectureTest diff는 추가뿐(규칙 2개 `ONLY_ENGINE_USES_REAL_STORE`·`REAL_STORE_DOES_NOT_DRIVE_GUARD`, 시험 1개, 위반 예제 단언 3개). 기존 규칙·고정 값 삭제·완화 없음 |
| A3 | 해당 없음 | 주문 전송 경로 변경 없음(저장소는 감사만 기록) |
| A4 | 해당 없음 | 주문·재전송 코드 없음 |
| A5 | 해당 없음 | 키 발급 없음(B4). 부팅 번호는 모든 원장의 `boot` 최댓값 + 1(EngineStore.java `openLocked`)이고, 읽지 못한 원장의 `boot`가 빠지는 한계는 D-146 위험과 한계에 B4 후속으로 적힘 |
| A6 | 통과(이 PR 범위) | 블랙리스트 판정 코드 무변경. 블랙리스트 원장 손상·읽기 실패면 모든 계좌 올림 거절(`EngineStoreTest#블랙리스트_원장이_깨지면_모든_계좌를_전면_차단한다`·`#블랙리스트_원장을_읽지_못하면_기동은_하고_모든_계좌를_전면_차단한다` 통과). 다만 `engine-store/blacklist` 폴더 자체가 폴더가 아니면 차단이 아니라 기동 거부가 된다(아래 Q1, S4) |
| A7 | 통과 | 계좌마다 쓰기 스레드 하나(`LedgerWriter`), 올림 감사는 계좌 잠금 밖에서 기다리고(RealTradingGuard.java `finishRaise`) 같은 계좌의 내림·다른 계좌 킬스위치가 기다리지 않음(`RealTradingGuardTest#올림_감사가_멈춘_동안_같은_계좌의_내림은_바로_끝나고_올림은_적용되지_않는다`(3종), `#한_계좌의_올림_감사가_멈춰도_다른_계좌_킬스위치는_바로_끝난다`, `EngineStoreTest#한_계좌의_감사_기록이_멈춰도_다른_계좌는_상한_안에_올리고_내린다` 통과). 한 계좌 원장 손상·읽기 실패는 그 계좌만 차단(`#중간_줄_CRC_불일치면_그_계좌만…`, `#한_계좌_원장을_읽지_못해도…` 통과) |
| A8 | 통과 | 게이트웨이·감시·수집기·실험 명령 `processIsolation.forbiddenProjects`에 `:autotrade-real-store` 추가(각 build.gradle.kts), 그 밖 코드는 `ONLY_ENGINE_USES_REAL_STORE`(위반 예제 `MisplacedRealStoreUse` 잡힘). `RealPathReflectionTest` 범위에 이 모듈 추가. 주문 판정은 스냅샷만 읽음(`RealTradingGuardTest#감사_기록이_느려도_주문_판정은_기다리지_않는다` 통과) |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 코드 무변경. 내림(보호 모드)은 저장소 차단과 상관없이 됨(시험마다 `shutdown` 확인) |
| A10 | 통과 | 시험 계좌번호는 자리표시자 `00000000-99`(StoreFixtures.java), 엔진 배선 시험은 `<ACCOUNT_NUMBER>`. diff 추가 줄 키·토큰 모양 직접 grep: `.reveal()` 호출(가리기 목록용, 로그에 안 씀)과 자리표시자뿐. 원장 기록 직전 가리기(`SecretMasker`, `EngineMain.secretValues`) 시험 통과. 알림·로그 문구는 파일 이름만. CI Secret scan success |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·단언 완화 없음. `RealTradingGuardTest`에서 바뀐 단언은 `IllegalStateException` → `RaiseNotAppliedException`(이유·원인까지)으로 더 좁아짐. 고정 값 갱신(`ModuleOwnershipTest.OWNERS`, `RealPathReflectionTest` 목록, `protected-paths.txt`, `CODEOWNERS`)은 새 모듈 추가에 따른 것이고 대상 코드 전체를 읽음. 새 동작에 실패 경우 시험 있음(감사 실패·상한 초과·내림이 먼저·ABA·두 올림 겹침·원장 손상·잠금 교체·기동 기록 실패·뒤 줄 실패). 단 `WriterLock.check`의 경로 읽기 실패 분기와 엔진 처리기의 `completeStep` `RaiseNotAppliedException` 분기는 시험이 없다(발견 1, Q3) |
| A12 | **실패** | ADR-015 2.9 (가)와 시험 13·장애 테스트 3-R의 전제는 "실행 중 엔진은 `writer.lock`이 지워지거나 바뀌면 감지한다, Windows는 `fileKey`가 없으므로 **내용 대조가 기본**"이다. 이 PR이 넣은 대체 분기(WriterLock.java:94-105)는 경로로 읽지 못하면 **자기 채널(지워진 옛 파일)을 읽어 자기 도장과 대조**하므로 늘 같다. 경로의 새 파일을 다른 프로세스가 잠근 경우, 곧 두 번째 쓰기 주체가 잠금을 잡은 바로 그 경우에 감지하지 못한다. 아래 **발견 1**(S3 재현). 절차서 P12는 "지우거나 내용을 바꾸면"만 시행해 이 경우를 잡지 못한다 |
| A13 | 통과 | 계좌 감사 원장 날짜는 `MarketRules.tradingDate`(EngineMain `BundledMarketRules.of(ref.market())`), 공통 명령 원장은 UTC 날짜. 시계가 되돌아가도 앞 날짜 파일을 다시 열지 않음(LedgerFile `ensureFile`). 금액 계산 없음 |
| A14 | **실패** | D-146 위험과 한계 52행 "경로의 파일이 바뀌었다면 새 파일은 잠기지 않아 경로 읽기가 성공하고 대조에서 걸린다"는 새 파일을 다른 프로세스가 잠근 경우에 거짓이다(발견 1). risk-ops 재검토 F-3 "해결" 판단도 같은 가정에 기댄다. 같은 절 50행 "기동 거부는 `writer.lock`을 못 잡거나 저장소 폴더가 심볼릭 링크일 때뿐"도 사실과 다르다(Q1, S4). ADR-015 2.7 "복구 시간 목표 … B1 PR에서 하루치 최대 기록 수 가정으로 시험한다"는 시험이 없고 D-146에 미룬다는 기록도 없다(Q2) |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(2,056건, 실패 0), main 합친 트리의 관련 4개 모듈 시험도 통과, CI Gradle build success |
| B6 | 통과 | 새 동작에 시험 있음(A11), 건너뜀·삭제 없음. 빠진 분기 시험은 발견 1·Q3에 적음 |

## 발견 1 (A12·A14): Windows에서 `writer.lock` 내용 대조가 "경로로 못 읽으면 자기 채널과 대조"로 바뀌어, 다른 프로세스가 새 잠금을 잡은 경우를 감지하지 못한다

`WriterLock.check`(WriterLock.java:80-108)는 ① 경로가 있고 심볼릭 링크가 아닌지, ② `key != null`이면 `fileKey`가 같은지, ③ 경로로 읽은 내용이 도장과 같은지를 본다. ③에서 경로 읽기가 `IOException`이면(95행) 잠금을 쥔 자기 채널로 읽어(100행) 대조한다. 자기 채널은 지금 경로의 파일이 아니라 **처음 잡은 파일**(지워졌을 수 있음)을 가리키고, 그 내용은 엔진 자신만 쓰므로 언제나 도장과 같다. 그래서 경로 읽기가 실패하는 순간 ③은 아무것도 확인하지 않는다.

- Windows에서는 `fileKey`가 null이라(ADR-015 2.9, D-146 결정 7) ②가 없다. 남는 것은 ①과 ③뿐이다.
- `[추론]` Windows 바이트 범위 잠금(`LockFileEx`, JDK `tryLock()`은 0~끝 전체 배타)은 핸들 단위라 **다른 프로세스가 잠근 파일은 경로로 읽으면 실패**한다. 이 PR이 대체 분기를 넣은 이유(risk-ops F-3)와 같은 성질이다.
- **S3 시나리오**: 엔진 A가 실행 중이다. 누군가(또는 다른 프로세스) `engine-store\writer.lock`을 지우고(JDK는 FILE_SHARE_DELETE로 열어 지울 수 있다는 것이 ADR-015 2.9의 전제), 두 번째 쓰기 주체 B가 같은 이름으로 새 파일을 만들고 `tryLock`에 성공해 자기 도장을 쓴다. A의 5초 확인: ① 경로 있음, ② 없음(Windows), ③ 경로 읽기 → B의 잠금 때문에 실패 → A 자기 채널(옛 파일) 읽기 → 도장 같음 → **문제 없음**. 전면 차단·긴급 알림 둘 다 없다. 반대로 B가 잠그지 않은 새 파일이면 경로 읽기가 성공해 잡힌다(그래서 기존 시험·P12는 통과한다).
- **재현(리눅스에서 Windows 흉내)**: 임시 시험에서 저장소를 연 뒤 리플렉션으로 `WriterLock.key`를 null로 두고(Windows의 `fileKey` 없음), `writer.lock`을 지우고 같은 이름의 폴더를 만들어 경로 읽기를 `IOException`으로 만들었다. `store.checkLock()` 뒤 출력 `QA-S3 fullBlock=Optional.empty alerts=[]`(감지 못 함). 같은 시험에서 읽히는 새 파일(`boot=99 pid=1`)로 바꾸면 `fullBlock=Optional[쓰기 잠금 교체 감지: … 내용이 바뀌었습니다]`로 잡힌다. 리눅스에는 강제 잠금이 없어 "다른 프로세스가 잠근 새 파일"을 그대로 만들 수 없으므로, 경로 읽기 실패를 폴더로 대신 만들었다. 대체 분기가 경로 읽기 실패를 통과로 바꾼다는 코드 성질은 이것으로 확인했고, Windows에서 다른 프로세스 잠금이 경로 읽기를 막는다는 부분은 `[추론]`이다.
- **영향**: ADR-015 2.9는 Windows에서 `engine.lock` 교체를 감지하지 못하므로(main `ControlServer.java:374`, `lockKey == null`이면 통과) `writer.lock` 내용 대조를 Windows 방어로 세웠다. 이 분기로 그 방어가 "지우기만 하고 아무도 다시 잠그지 않은 경우"로 줄었다. 엔진 둘끼리는 하트비트(기동 때 `otherEngine` 거부, 실행 중 `EngineSelfCheck.checkOtherEngine`이 늦은 엔진을 멈춤)가 따로 막으므로 지금 당장 이중 주문으로 이어지지는 않는다 `[추론]`. 그러나 ADR-015 2.9가 정한 감시 긴급 청산 경로(묶음 F, "엔진이 멈춰 `writer.lock`이 풀렸을 때만 잡는다")는 하트비트를 쓰는 엔진이 아니라서, 지워진 잠금 파일 자리를 감시가 잡으면 엔진과 감시가 같은 원장에 함께 쓰고 엔진은 알아채지 못한다. 그 경로는 이 감지에 기대게 된다.
- **고칠 것(최소)**: (1) 경로 읽기 실패를 "자기 채널과 같음"으로 통과시키지 않는다. 예: 잠금 구간을 도장 바이트 밖(예: 파일 끝 뒤 한 바이트)으로만 잡아 Windows에서도 도장을 경로로 읽을 수 있게 하고 대체 분기를 없애거나, 대체 분기에서는 경로 쪽 파일을 따로 열어 `tryLock`이 겹침 없이 실패하는지(다른 프로세스 잠금) 확인해 문제로 본다. 고른 방법의 Windows 동작은 `[추론]`으로 두고 절차서에서 확인한다. (2) "경로로 못 읽음"인데 통과하는 일이 없음을 시험으로 고정한다(위 재현처럼 `key` 없음 + 경로 읽기 실패 → 차단). (3) D-146 52행 문구를 고치고, 절차서 P12에 "엔진 실행 중 `writer.lock`을 지운 뒤 다른 프로세스가 같은 이름으로 새 파일을 만들어 잠금(예: 두 번째 PowerShell에서 `[IO.File]::Open(…,'OpenOrCreate','ReadWrite','None')`) → 5초 안 전면 차단 알림" 단계를 넣는다.

## 점검표 밖 위험 시나리오

- **S1 OFF 계좌 올림 감사 중 킬스위치(안전)**: OFF 계좌 `enterProtective`가 감사 기록을 기다리는 사이 킬스위치(`shutdown`)가 오면 상태는 OFF 그대로라 전이 번호가 안 바뀐다. `interruptRaise`가 `raiseInterrupted`를 세워 ③에서 버리고 `LOWERED_FIRST`로 끝난다(RealTradingGuard.java `interruptRaise`·`finishRaise`). `RealTradingGuardTest#OFF에서_시작한_올림을_기록하는_중_킬스위치가_오면…`(4종) 통과. 내렸다가 같은 상태로 되돌아온 경우(ABA)도 전이 번호로 막힘(`#OPEN_올림을_기록하는_사이_내렸다가_다시_올려도…` 통과). 잘못 올라가는 길을 찾지 못했다.
- **S2 올림 줄이 쓰이는 중 상한 초과(안전, 기록만 어긋남)**: 2초 상한이 지나면 `Pending.cancel()`이 실패해(이미 집음) "전이 미적용(기록 상한 초과)" 보정 기록을 붙이고 던진다(StoreGuardAuditLog.java `notApplied`). Guard는 올리지 않는다(`EngineStoreTest#올림_줄이_이미_쓰이는_중에_상한을_넘기면…` 통과). 보정 기록이 대기열 가득 참으로 빠지면 원장에는 적용 안 된 올림 줄만 남는다(risk-ops N-2, D-146 위험과 한계). 비슷하게, 한 묶음 안에서 거래일이 바뀌어 앞 날짜 파일을 강제 기록하고 닫은 뒤 새 날짜 파일 붙이기가 실패하면, 앞 날짜 파일에 이미 강제 기록된 같은 원장의 앞 줄도 "실패"로 돌려준다(LedgerWriter.java `write`의 되돌림 고리는 원장 객체만 비교). 그 줄이 올림이면 Guard는 안 올리지만 원장에는 올림 줄이 보정 기록 없이 남는다. 화면·대사가 원장 줄을 "적용됨"으로 쓰지 않는 한 주문 영향은 없다(Q5).
- **S3 Windows 잠금 교체 + 다른 프로세스 잠금**: 발견 1.
- **S4 공통 원장 폴더가 폴더가 아님(재현)**: `engine-store/blacklist`를 일반 파일로 두고 `EngineStore.open` → `java.io.IOException: 엔진 저장소 경로가 폴더가 아닙니다: blacklist`(임시 시험 출력 `QA-S4`). `openLocked`가 원장 검사보다 먼저 `boot`·`audit/commands`·`blacklist` 폴더를 `prepareDir`(EngineStore.java:168-169)로 준비하므로, 이 폴더들이 폴더가 아니거나 만들기·권한 설정이 실패하면 R-1이 정한 "손상과 같이 다룸(블랙리스트는 전면 차단, 기동·명령은 옮기고 경고)"이 아니라 기동 거부다. 이 예외는 `StoreRefusal`이 아니라서 `EngineMain.StartFailure`가 `CHANNEL_HEADLINE` "실전 제어 명령 채널을 열지 못해 기동을 중단합니다"로 보인다(EngineMain.java:465-466). 기동 거부 자체는 모든 계좌 OFF라 주문 쪽으로 위험하지 않지만, 운영자가 명령 채널을 고치러 가게 되고 D-146 50행 서술과 다르다(Q1).
- **S5 같은 묶음 뒤 줄 실패(안전)**: 뒤 줄 붙이기 실패로 원장이 마지막 강제 기록 길이로 되돌려지면 같은 원장의 앞 줄도 실패로 돌려준다(architect 발견 1 수정, `LedgerWriterTest#같은_묶음의_뒤_줄_붙이기가_실패하면…` 통과). 되돌린 뒤 `rseq`·`crc`를 디스크에서 다시 맞추므로(LedgerFile `resyncFromDisk`) 다음 기동에 `rseq` 건너뜀 손상이 생기지 않는다. 체크포인트까지 되돌린 경우도 빈 파일을 건너뛰고 직전 파일을 이어 쓸 기준으로 삼아(LedgerScan `dated`) 체크포인트 연결이 끊기지 않음을 코드로 따라가 확인했다.

## 권고 (수정 요구 아님)

- **Q1 (A14)**: 공통 원장 폴더 준비(`prepareDir`) 실패를 R-1처럼 다루거나(블랙리스트는 전면 차단, 기동·명령은 경고), 기동 거부로 둘 거라면 `StoreRefusal`로 바꿔 `STORE_HEADLINE`으로 보이고 D-146 50행을 고친다(S4).
- **Q2 (A14)**: ADR-015 2.7 "복구 시간 1초 목표를 B1에서 하루치 최대 기록 수로 시험" 항목이 시험에도 D-146에도 없다. 시험을 넣거나 D-146에 미룬 묶음과 이유를 적는다. 참고로 `LedgerScan.readAll`은 파일 전체를 메모리로 읽고, `boot.jsonl`·`risk.jsonl`은 날짜로 나뉘지 않아 계속 커진다.
- **Q3 (A11·B6)**: `EngineCommandHandler.runActivationSteps`의 새 `catch (RaiseNotAppliedException e)` 분기(마지막 단계 OPEN 올림이 미적용이면 `failActivation`, "2차 확인을 다시 받아야 합니다")를 고정하는 시험이 없다(app-engine 시험에서 `RaiseNotApplied` 검색: `EngineStoreWiringTest`의 `enterProtective` 한 건뿐). Guard 자체는 `AUDIT_NOT_RECORDED` 뒤 같은 활성화의 `completeStep(FIRST_RECONCILIATION)` 재호출로 새 2차 확인 없이 OPEN이 되는 것을 허용하고, 이를 `RealTradingGuardTest#감사_기록에_실패하면_올라가지_않는다`가 단언한다. D-146 결정 6 "적용되지 않으면 다시 받아야 한다(활성화 실패로 OFF)"는 처리기만 지키므로, 처리기 분기 시험을 넣는다(지금은 분기를 지워도 `finishActivation`이 ACTIVATING을 내려 결과는 같다 `[추론]`).
- **Q4**: 쓰기 잠금 확인은 `scheduleWithFixedDelay(this::checkLock, …)`(EngineStore.java:364)로 감싸지 않고 돈다. 한 번이라도 런타임 예외가 나면 다음 주기가 조용히 취소된다(같은 이유로 `EngineSelfCheck.checkQuietly`는 `Throwable`을 잡는다). 같은 방식으로 감싼다.
- **Q5**: S2의 "앞 날짜 파일에 강제 기록된 줄을 실패로 돌려줌" 경우를 D-146 위험과 한계(risk-ops N-2 줄)에 더하거나, 되돌림 고리가 실제로 되돌려진 줄(같은 파일, `durableSize` 뒤)만 실패로 돌리게 한다.
- **Q6**: `SecretMasker.replace`는 비밀값을 주어진 순서대로 바꾼다. 한 비밀값이 다른 비밀값의 앞부분이면(짧은 것이 먼저) 긴 값의 나머지가 남는다. 긴 값부터 바꾼다.

## 그 밖

- **R1**: main과 `docs/decisions/README.md`·`docs/qa/pending.md`가 충돌한다(#247 머지 뒤). 줄 합치기만이고, 합친 트리의 관련 시험은 위에서 통과했다.
- D-140 ① 범위("거부")와 D-141 확정 줄은 #239 머지 사실과 맞다(main `c1972a03`).

## 판정
수정 필요(발견 1: Windows에서 `writer.lock` 내용 대조의 대체 분기가 경로 읽기 실패를 자기 채널 대조로 통과시켜, 다른 프로세스가 새 잠금을 잡은 "쓰기 주체 둘" 경우를 감지하지 못함. A12·A14 실패, D-146 52행·risk-ops F-3 해결 판단의 전제가 거짓). 고친 뒤 실패 경우 시험과 P12 단계를 넣고 재검토를 요청한다. 실전 관련 PR이므로 머지는 사용자가 직접 한다.
