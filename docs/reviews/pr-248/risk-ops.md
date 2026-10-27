# risk-ops 검토: 실전 엔진 저장소 B1: 원장 모듈·쓰기 잠금·Guard 두 단계 올림 (D-146, ADR-015)
- 대상: PR #248 / 커밋 `14ea305a` (브랜치 `claude/project-thread-zoyak3`) / `origin/main...origin/claude/project-thread-zoyak3` diff 전체(55개 파일)
- 점검표: A
- 직접 실행한 것:
  - 작업 폴더 빌드는 다른 검토자의 동시 빌드와 결과 파일이 겹쳐 `:autotrade-real-store:test`가 "Could not write XML test results"로 끊겼다(코드 실패 아님). 그래서 `git archive 14ea305a`로 떠 낸 별도 폴더에서 다시 돌렸다.
  - `./gradlew build --offline`(별도 폴더, 커밋 `14ea305a`): **BUILD SUCCESSFUL**. 테스트 결과 XML 합계 2,050건, 실패 0·오류 0·건너뜀 0. 그중 `:autotrade-risk:test` 68건, `:autotrade-real-store:test` 28건, `:autotrade-architecture-test:test` 47건, `:autotrade-app-engine:test` 114건, `:autotrade-notification-jar:test` 86건 모두 통과.
  - 임시 재현 시험(커밋 안 함, 별도 폴더): 한 계좌(`KIS-KR-1`)의 `audit` 폴더에 날짜 원장 이름의 폴더(`2026-10-27.jsonl`)를 만들어 읽기 오류를 낸 뒤 `EngineStore.open` → `java.io.IOException: Is a directory`로 저장소 열기 전체가 실패(발견 F-2).
  - 이 검토는 커밋 `14ea305a` 기준이다. 검토 중 작업 폴더에 커밋되지 않은 `LedgerWriter.java`·`RealTradingGuard.java` 수정이 생겼는데, 이 검토 대상이 아니다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard.java:447` `Snapshot.INITIAL = (OFF, OFF, 0, 0)`, `:470` 모든 계좌가 INITIAL로 시작. 저장소는 Guard를 되살리지 않음: `EngineStore`에 Guard 참조 없음, ArchUnit `REAL_STORE_DOES_NOT_DRIVE_GUARD`(`ArchitectureTest`)와 위반 예제 `GuardRestorer`로 고정. `EngineStoreTest#원장에_OPEN_전이가_있어도_재기동하면_모든_계좌는_OFF다` 통과. 올림은 여전히 2차 확인 필요(`accept` 호출 유지). |
| A2 | 통과 | 주문 출구(`GuardedOrderGateway`·`RealOrderGateways`) 변경 없음. 기존 ArchUnit 규칙은 지우거나 바꾸지 않았고 규칙 2개(`ONLY_ENGINE_USES_REAL_STORE`, `REAL_STORE_DOES_NOT_DRIVE_GUARD`)를 더함. `ArchitectureTest` 전체 통과, 위반 예제가 실제로 위반으로 잡히는 단언 포함. |
| A3 | 해당 없음 | 증권사 플러그인·주문 경로를 건드리지 않음(diff에 broker 모듈 없음). |
| A4 | 해당 없음 | 주문 전송·재전송 경로 변경 없음. 주문 원장(B4)은 이 PR에 없음(D-146 위험과 한계). |
| A5 | 해당 없음 | 멱등 키 발급 변경 없음. `keyFloor`는 기동 기록에 빈 자리만(`EngineStore.writeBootRecord`, B4 몫). 부팅 번호는 모든 원장 `boot` 최댓값 + 1(`EngineStore.java` ⑤)로 B4의 기반만 둠. 부팅 번호 겹침 가능성은 architect 발견 2(B4 후속)와 같은 의견. |
| A6 | 통과 | 블랙리스트 판정 코드 변경 없음. 블랙리스트 원장 손상이면 모든 계좌 올림 거절: `EngineStore.openLocked` `globalBlocks.add("블랙리스트 원장 손상…")`, `EngineStoreTest#블랙리스트_원장이_깨지면_모든_계좌를_전면_차단한다` 통과. |
| A7 | **실패** | (1) 좋은 점: 계좌마다 쓰기 스레드가 따로(`LedgerWriter` 계좌별), `EngineStoreTest#한_계좌의_감사_기록이_멈춰도_다른_계좌는_상한_안에_올리고_내린다`, `RealTradingGuardTest#한_계좌의_올림_감사가_멈춰도_다른_계좌_킬스위치는_바로_끝난다` 통과. (2) 그러나 **한 계좌 원장의 읽기 오류(`IOException`)가 저장소 열기 전체를 실패시켜 엔진 기동이 거부된다**(발견 F-2, 재현함). ADR-015 2.7 ③ "그 원장의 계좌만 복구 실패… 다른 계좌는 계속 복구"와 어긋난다. |
| A8 | 통과 | 저장소는 실전 엔진만 의존: 게이트웨이·감시·수집기·실험 명령 `processIsolation.forbiddenProjects`에 `:autotrade-real-store` 추가(각 `build.gradle.kts`), 그 밖 모든 코드는 ArchUnit `ONLY_ENGINE_USES_REAL_STORE`로 막음. `RealPathReflectionTest`의 실전 경로 범위에 이 모듈을 넣어 리플렉션 금지 검사도 받음. 원장 쓰기 대기는 Guard 잠금 밖이고 주문 판정(`check`·`mayCancel`·허가 검사)은 잠금 없이 스냅샷만 읽음(`RealTradingGuardTest` "상향 전이가 감사 기록에서 멈춘 동안에도 판정·취소 허용·허가 검사는 바로 돌아온다" 통과). |
| A9 | 통과 | 내림(킬스위치·PANIC 1단계·활성화 실패·보유 0)은 감사 기록을 기다리지 않음: `StoreGuardAuditLog.lower`가 대기열에 넣고 바로 돌아오고 실패는 알림만, `RealTradingGuard.audit`는 예외를 삼킴. 올림 대기 중 같은 계좌 내림이 즉시 끝나는 것은 `RealTradingGuardTest#올림_감사가_멈춘_동안_같은_계좌의_내림은_바로_끝나고_올림은_적용되지_않는다`(종류별) 통과. 보호 시세·서버 측 손절 경로 변경 없음. 단, 발견 F-2가 고쳐지지 않으면 주문 경로가 생긴 뒤(B4~) 한 계좌 파일 문제로 엔진 전체가 뜨지 못해 다른 계좌 보호도 잃는다. |
| A10 | 통과 | 시험 값은 자리표시자(`StoreFixtures.FAKE_ACCOUNT_NUMBER = "00000000-99"`). 원장 기록 직전 가리기(`SecretMasker`, `EngineMain.secretValues`가 계좌번호·증권사 키·운영자 비밀번호 해시·알림 비밀값을 넘김), `SecretMaskerTest`·`EngineStoreTest#원장_문자열에_설정된_계좌번호가_들어오면_가려서_기록한다`·`EngineStoreWiringTest#가릴_비밀값은_설정의_계좌번호를_담고_빈_값은_뺀다` 통과. diff 전체에서 키·토큰 모양 값 검색 결과 없음. 알림·로그 문구에는 경로 이름만 나옴(`WriterLock.check`). |
| A11 | 통과 | 지운 시험 없음. `RealTradingGuardTest`에서 바뀐 단언은 `감사_기록에_실패하면_올라가지_않는다`의 `IllegalStateException` → `RaiseNotAppliedException`(이유·원인 예외·문구까지 단언, 보호 모드·OPEN 경우 추가)로 **더 강해짐**. 고정 값 갱신(`ModuleOwnershipTest.OWNERS`에 `com.autotrade.realstore`, `RealPathReflectionTest` 프로젝트·패키지 목록, `protected-paths.txt`·`CODEOWNERS`)은 새 모듈 추가에 따른 것이고 대상 코드(이 모듈 전체)를 검토함. 새 동작에 실패 경우 시험 있음(감사 실패·상한 초과·내림이 먼저·ABA·두 올림 겹침·원장 손상·잠금 교체·기동 기록 실패). 다만 발견 F-1·F-2·F-3의 실패 경우는 시험이 없다(아래 수정 요구에 포함). |
| A12 | 통과 | 장애 대응표 #8 문구 갱신("DB 상태 복구" → "엔진 저장소 복구")은 ADR-015 7장에 risk-ops 후속으로 이미 잡혀 있고 이 PR은 대응을 약하게 하지 않음. 장애 테스트 3-R(쓰기 주체 둘)의 전제는 `writer.lock`·부팅 번호 검사로 더 강해짐. 7단계 절차서에 P11(강제 기록 실측)·P12(Windows 잠금 교체 감지) 추가. 단 P12의 Windows 동작에는 발견 F-3의 거짓 차단 가능성이 있다. |
| A13 | 통과 | 계좌 감사 원장 날짜는 계좌 시장 규칙의 거래소 현지 거래일: `EngineMain.java:251` `BundledMarketRules.of(ref.market())` → `EngineStore.java:283` `LedgerFile.dated(…, rules::tradingDate)`. 공통 원장만 UTC. 금액 계산 없음. (미국 계좌 날짜 경계 시험은 없음, 주문 원장이 생기는 B4에서 시험을 권함.) |
| A14 | 통과 | D-146(결정 1~12)·ADR-015 13장·ADR-002/006 갱신이 코드와 일치함을 대조함(두 단계 올림, 상태가 안 바뀌는 내림도 올림 무효, 같은 계좌 두 번째 올림 거절, (가) 차단을 감사 어댑터로 집행, 잠금 순서, 닫는 순서). D-140 ① "거부" 확정 반영. 단 2.7 ③ "다른 계좌는 계속 복구"는 읽기 오류 경우에 지켜지지 않음(F-2, A7에서 실패로 셈). |

## 점검표 밖 발견

### F-1. [수정 필요] 같은 묶음에서 뒤 줄 쓰기가 실패하면 앞 줄이 원장에서 지워졌는데 "기록됨"으로 끝난다 → 올림이 기록 없이 적용될 수 있음 (architect 발견 1을 독립 확인)
- [사실] `LedgerFile.append` 실패 시 `rollback()`이 `channel.truncate(durableSize)`로 마지막 강제 기록 길이까지 자르고 `dirty = false`로 둔다(`LedgerFile.java` `append`·`rollback`). 같은 묶음에서 앞서 붙인(아직 강제 기록 전) 줄도 함께 잘린다.
- [사실] `LedgerWriter.write`는 앞 줄을 이미 `written`에 넣었고, 이어지는 `ledger.force()`는 `dirty == false`라 아무것도 하지 않으며, `failed`에 들지 않은 앞 줄은 `p.done.complete(p.rseq)`로 성공 처리된다.
- [추론] 앞 줄이 Guard 올림 감사라면 `StoreGuardAuditLog.raise`가 성공을 받고 Guard가 올림을 적용한다. "올라가는 전이는 감사 기록이 성공해야 적용"(ADR-015 2.5, D-146 결정 6, ADR-002 불변식)이 깨지고 원장에는 그 전이가 없다. 계기는 같은 묶음 뒤 줄의 디스크 가득 참·백신 차단 등 `IOException`. 재현은 architect 원문에 있고, 나는 코드 경로를 읽어 같은 결론을 확인했다(재현 시험은 따로 돌리지 않음).
- 요구: 한 원장에서 `append`가 실패하면 그 묶음에서 같은 원장에 붙인 `written` 항목을 모두 실패로 끝내거나, 줄 시작 위치로만 되돌린다. 재현 시험을 남기고, Guard 올림이 이 경우 `RaiseNotAppliedException(AUDIT_NOT_RECORDED)`로 끝나는 것까지 단언한다.

### F-2. [수정 필요] 한 계좌 원장의 읽기 오류가 엔진 기동 전체를 막는다 (A7)
- [사실] `EngineStore.openLocked`의 계좌 원장 검사(`LedgerScan.dated`·`single` → `readAll`·`Files.size`·`Files.list`)는 `IOException`을 그대로 던지고, `EngineStore.open`이 잠금을 놓고 다시 던지며, `EngineMain`은 이를 기동 실패로 처리한다.
- [사실] 재현: `KIS-KR-1`의 `audit` 폴더에 `2026-10-27.jsonl` 이름의 폴더를 만들고 열면 `java.io.IOException: Is a directory`로 `EngineStore.open` 실패. `KIWOOM-KR-1`은 정상인데도 저장소가 열리지 않는다.
- [추론] 운영 PC(Windows)에서는 백신이 원장 파일을 잠깐 잡아 읽기가 실패하는 일이 흔하다. 그러면 한 계좌 문제로 실전 엔진이 뜨지 않고, 주문 경로가 생긴 뒤에는 다른 계좌의 손절·청산·PANIC도 못 한다(절대 규칙 6 "실전 계좌끼리도 서로 영향을 주지 않는다", ADR-015 2.7 ③).
- 요구: 계좌 원장 검사 중 `IOException`(심볼릭 링크가 아닌 일반 읽기 오류 포함)은 그 계좌만 "계좌 원장 손상(읽기 실패)"으로 (가) 전면 차단하고 다른 계좌는 계속 연다. 블랙리스트 원장 읽기 실패는 손상과 같이 모든 계좌 (가)로. 기동 거부는 `writer.lock`·폴더 심볼릭 링크처럼 ADR이 정한 경우로 한정한다. 위 재현을 시험으로 남긴다.

### F-3. [수정 필요] Windows에서 `writer.lock` 내용 대조가 자기 잠금 때문에 실패해 5초 뒤 모든 계좌가 거짓 전면 차단될 수 있다
- [사실] `WriterLock.acquire`는 `channel.tryLock()`(JDK 기본 범위 0 ~ `Long.MAX_VALUE`, 배타)으로 잡고, `WriterLock.check`는 **새 `FileChannel`을 따로 열어** 내용을 읽는다. 읽기 `IOException`이면 "읽지 못했습니다"를 돌려주고 `EngineStore.checkLock` → `block(...)`으로 모든 계좌 (가) 전면 차단과 긴급 알림.
- [추론] Windows의 `LockFileEx` 잠금은 잠근 핸들에 묶이는 강제 잠금이라, 같은 프로세스라도 다른 핸들로 잠긴 범위를 읽으면 `ERROR_LOCK_VIOLATION`으로 실패한다(Win32 `LockFileEx` 문서: 잠근 프로세스가 파일을 두 번째로 열면 잠금을 풀 때까지 두 번째 핸들로는 그 범위에 접근할 수 없음). 리눅스는 권고 잠금이라 시험이 통과한다. 운영 PC에서 확인하지 못했다.
- [추론] 영향: 운영 PC에서 엔진을 켠 지 5초 만에 "쓰기 잠금 교체 감지"로 모든 실전 계좌의 활성화·보호 모드 상향·PANIC 상향이 막힌다. 더 막는 쪽이라 안전 약화는 아니지만, 실전 활성화가 불가능해지고 거짓 긴급 알림이 매번 나서 진짜 교체 감지를 무디게 한다.
- 요구: 내용 대조는 잠금을 쥔 채널로 읽거나(`channel.read(buf, 0)`), Windows에서는 존재·속성 확인으로 바꾸는 등 자기 잠금과 충돌하지 않게 한다. POSIX에서는 지금처럼 `fileKey`로 교체를 잡으므로, 자기 채널 읽기로 바꿀 때 교체 감지가 약해지지 않는지 시험으로 확인한다. Windows 실기 확인은 P12에 그대로 둔다.

### 참고(수정 요구 아님)
- N-1. 올림 대기 중 같은 계좌의 **모든** 내림 호출이 올림을 무효로 한다(`interruptRaise`). 안전 쪽 선택이라 동의한다. 다만 B6에서 `holdingsCleared`를 주기적으로 부르게 되면 OFF 계좌의 보호 모드 상향·활성화 시작이 이유 없이 "내림이 먼저"로 실패할 수 있다. 그때는 상태가 바뀌는 `holdingsCleared`만 무효로 하는 것을 risk-ops와 다시 정한다.
- N-2. 올림 감사가 상한을 넘겼는데 줄이 이미 쓰이는 중이면 보정 기록(`guard-raise-not-applied`)을 따로 넣는다. 보정 기록이 대기열 가득 참 등으로 빠지면 원장에는 적용되지 않은 올림 줄만 남는다. Guard는 원장으로 되살리지 않으므로(A1) 지금 영향은 없지만, 원장을 읽는 화면·대사(H·B6)는 `guard-transition` 줄을 "적용됨"의 근거로 쓰지 않아야 한다.
- N-3. 저장소가 열리기 전(명령 채널을 연 뒤) 들어온 내림 감사는 프로세스 로그에만 남는다. D-146에 적힌 대로이며 이 사이 올림은 모두 거절되므로 받아들인다.
- N-4. `LedgerScan.dated`는 가장 최근 날짜 파일과 그 직전 파일의 연결만 본다. 체크포인트 설계(ADR-015 2.4)대로라 받아들이지만, 그 이전 파일의 손상은 기동 때 드러나지 않는다는 점을 I1 절차에 적기를 권한다.
- N-5. `RealTradingGuard.java` `PendingRaise` 위 Javadoc 두 개 중복(architect 발견 3과 같음).

## 판정
수정 필요(F-1 같은 묶음 뒤 줄 실패 때 앞 줄이 "기록됨"으로 끝나 올림이 기록 없이 적용될 수 있음, F-2 한 계좌 원장 읽기 오류가 엔진 기동 전체를 막음(A7 실패), F-3 Windows에서 `writer.lock` 내용 대조가 자기 잠금과 충돌해 거짓 전면 차단 가능). 세 건 모두 재현 또는 실패 경우 시험을 함께 넣고 같은 검토자에게 재검토를 맡긴다. 실전 관련 PR이므로 고친 뒤에도 독립 QA와 사용자 머지가 필요하다.

## 재검토 2026-10-26
- 대상: PR #248 / 커밋 `1123e80c`(수정 커밋 `34fd5870`·`1123e80c` 포함, 검토 원문 커밋 제외) / `origin/main...1123e80c` diff 전체. 앞 검토 뒤 바뀐 코드는 `LedgerWriter.java`·`EngineStore.java`·`WriterLock.java`·`RealTradingGuard.java`(주석만)와 시험 `LedgerWriterTest`(새 파일)·`EngineStoreTest`(2건 추가), 문서 `D-146`.
- 점검표: A
- 직접 실행한 것:
  - `git archive 1123e80c`로 떠 낸 별도 폴더에서 `./gradlew build --offline`: **BUILD SUCCESSFUL**. 테스트 XML 합계 2,053건, 실패 0·오류 0·건너뜀 0. `:autotrade-risk:test` 68건, `:autotrade-real-store:test` 31건(앞 검토보다 3건 늘어남), `:autotrade-architecture-test:test` 47건, `:autotrade-app-engine:test` 114건.
  - 임시 재현 시험(커밋 안 함, 별도 폴더, 커밋 `1123e80c`): 공통 원장 자리에 같은 이름의 폴더를 만들어 읽기 오류를 낸 뒤 `EngineStore.open`. `blacklist/blacklist.jsonl`·`audit/commands/2026-10-26.jsonl`·`boot/boot.jsonl` 세 경우 모두 `java.io.IOException: Is a directory`로 저장소 열기 전체가 실패(아래 R-1).

### 앞 발견의 처리
| 발견 | 결과 | 근거 |
|---|---|---|
| F-1 같은 묶음 뒤 줄 실패 때 앞 줄이 "기록됨"으로 끝남 | 해결 | `LedgerWriter.write`: `append`가 실패하면 그 묶음에서 같은 원장에 먼저 붙인 `written` 항목을 빼고 실패로 끝낸다. 되돌리기 뒤에 같은 원장에 붙는 줄은 `durableSize`·`resyncFromDisk` 기준으로 다시 붙고 강제 기록된다. 다른 원장 줄은 영향 없음. `LedgerWriterTest#같은_묶음의_뒤_줄_붙이기가_실패하면_되돌려진_앞_줄도_기록됨으로_끝나지_않는다` 통과(앞 줄 실패, 파일에 없음, 다음 줄 `rseq` 2, 다시 검사 OK). Guard까지 이어지는 경로는 코드로 확인함: 실패한 `done` → `StoreGuardAuditLog.raise`의 `ExecutionException` → `IllegalStateException` → `RealTradingGuard.finishRaise`가 `RaiseNotAppliedException(AUDIT_NOT_RECORDED)`로 올리지 않음. 이 연결을 끝까지 묶은 시험은 없다(참고 R-3). |
| F-2 한 계좌 원장 읽기 오류가 엔진 기동 전체를 막음 | **계좌 원장은 해결, 공통 원장은 남음** | 계좌 원장: `EngineStore.openLocked`의 계좌 검사와 원장 준비가 `IOException`·`RuntimeException`을 잡아 그 계좌만 "계좌 원장 읽기 실패"/"준비 실패"로 (가) 전면 차단한다. `EngineStoreTest#한_계좌_원장을_읽지_못해도_저장소는_열리고_그_계좌만_전면_차단한다` 통과(막힌 계좌 올림 거절, 다른 계좌 올림 가능, 막힌 계좌 내림 가능). 공통 원장: 앞 원문 F-2 요구의 "블랙리스트 원장 읽기 실패는 손상과 같이 모든 계좌 (가)로"는 반영되지 않았다(R-1). |
| F-3 Windows에서 `writer.lock` 내용 대조가 자기 잠금과 충돌 | 해결(Windows 실기는 미검증, P12) | `WriterLock.check`: 존재·`fileKey` 확인 뒤 경로로 읽고, 경로 읽기가 실패하면 잠금을 쥔 채널로 위치 지정 읽기(`channel.read(buf, pos)`)를 해서 대조한다. 쓰기 위치는 바꾸지 않는다. POSIX에서는 경로 읽기가 먼저 성공하므로 `fileKey`·내용 대조가 앞과 같아 교체 감지가 약해지지 않는다(기존 `실행_중_잠금_파일_내용이_바뀌면…`·`…지워지면…` 시험 통과). [추론] Windows에서 다른 프로세스가 잠긴 파일 내용을 바꾸는 쓰기는 강제 잠금으로 막히고, 파일을 지우고 새로 만들면 새 파일은 잠기지 않아 경로 읽기가 성공해 대조에서 걸린다. `EngineStoreTest#잠금을_쥔_채널로도_잠금_파일_내용을_대조할_수_있다`는 자기 채널 읽기만 직접 시험하고, `check()`가 경로 읽기 실패 때 이 길로 넘어가는 분기는 시험하지 않는다(참고 R-4). |
| N-1~N-5 | 기록됨 | N-1·N-2·N-4·architect 권고 2는 D-146 "위험과 한계"에 후속으로 적힘. N-5 Javadoc 중복은 하나로 합쳐짐. |

### 점검표 A (다시 확인)
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 앞 검토와 같음. 수정 커밋은 Guard 초기 상태·2차 확인을 건드리지 않음(`RealTradingGuard.java` 변경은 주석만). `EngineStoreTest#원장에_OPEN_전이가_있어도_재기동하면_모든_계좌는_OFF다` 통과. |
| A2 | 통과 | 주문 출구·ArchUnit 규칙 변경 없음(수정 커밋에 `autotrade-architecture-test` 변경 없음). `ArchitectureTest` 통과. |
| A3 | 해당 없음 | 증권사·주문 경로 변경 없음. |
| A4 | 해당 없음 | 주문 전송·재전송 변경 없음. |
| A5 | 해당 없음 | 키 발급 없음(B4). 참고 R-2(부팅 번호). |
| A6 | 통과 | 블랙리스트 원장 손상 → 모든 계좌 전면 차단(`EngineStoreTest#블랙리스트_원장이_깨지면_모든_계좌를_전면_차단한다` 통과). 블랙리스트 원장 **읽기 오류**는 차단이 아니라 기동 거부로 끝나므로 블랙리스트를 모른 채 주문하는 경로는 생기지 않는다(R-1은 A7·A14 쪽 문제). |
| A7 | **실패** | 계좌 원장 쪽은 해결(위 F-2). 그러나 공통 원장(`blacklist`·`boot`·`audit/commands`) 읽기 오류 하나로 저장소 열기 전체가 실패해 실전 엔진이 뜨지 않는다(R-1, 재현함). 운영 PC 백신이 공통 원장 파일 하나를 잠깐 잡아도 모든 실전 계좌가 엔진 없이 남는다. |
| A8 | 통과 | 앞 검토와 같음. 수정 커밋은 프로세스 격리 설정을 건드리지 않음. |
| A9 | 통과 | 내림은 감사 기록을 기다리지 않음(변경 없음). 계좌 원장 읽기 실패 계좌에서도 내림이 되는 것을 새 시험이 확인. 보호 시세·서버 측 손절 경로 변경 없음. R-1은 주문 경로가 생긴 뒤 A9에도 영향을 주므로 A7과 함께 고친다. |
| A10 | 통과 | 새 차단 사유 문구는 예외 클래스 이름과 메시지만 싣는다. `IOException` 메시지는 경로·OS 오류 문구이고 비밀값을 담지 않는다. 새 시험 값은 자리표시자. |
| A11 | 통과 | 지운 시험·약한 단언 없음. 보호 테스트·고정 값 변경 없음. 새 동작(F-1·F-2·F-3)에 실패 경우 시험이 생김(분기 범위는 참고 R-3·R-4). |
| A12 | 통과 | 앞 검토와 같음. 3-R·P12 전제를 약하게 하지 않음. |
| A13 | 통과 | 앞 검토와 같음(`rules::tradingDate`는 계좌 원장 준비 `try` 안으로 옮겨졌을 뿐). |
| A14 | **실패** | D-146 "위험과 한계"에 계좌 원장 읽기 실패 처리가 적혔다. 그러나 ADR-015 2.7 ③("`boot`·`audit/commands` 손상은 활성화를 막지 않고 경고", "`blacklist` 손상이면 모든 계좌 전면 차단")과 달리 이 원장들의 읽기 오류는 기동 거부로 끝나고, D-146에도 그 차이가 적혀 있지 않다(R-1). |

### 재검토 발견
#### R-1. [수정 필요] 공통 원장 읽기 오류가 엔진 기동 전체를 막는다 (F-2의 남은 부분, A7·A14)
- [사실] `EngineStore.openLocked`의 공통 원장 검사 `LedgerScan.single(paths.bootFile())`·`LedgerScan.dated(paths.commandsDir())`·`LedgerScan.single(paths.blacklistFile())`는 `try` 밖에 있어 `IOException`이 그대로 `EngineStore.open`을 실패시킨다. `quarantine(...)`(`Files.move`) 실패도 같다.
- [사실] 재현(커밋 `1123e80c`): 세 공통 원장 자리에 같은 이름의 폴더를 하나씩 두면 세 경우 모두 `IOException: Is a directory`로 저장소가 열리지 않는다.
- 요구: ADR-015 2.7 ③대로 맞춘다. `blacklist` 읽기 실패는 손상과 같이 **모든 계좌 (가) 전면 차단**(기동은 함), `boot`·`audit/commands` 읽기 실패는 그 파일을 옮기지 못하면 새 이름으로 시작하는 등 **경고만** 하고 기동한다. 옮기기(`quarantine`)가 실패하는 경우도 같은 방향으로 처리한다. 세 경우의 시험을 남긴다. ADR과 다르게 기동 거부를 고르려면 D-146에 이유와 함께 적고 사용자 확인 항목으로 올린다.

#### 참고(수정 요구 아님)
- R-2. 계좌 원장을 읽지 못한 계좌의 `boot` 값은 부팅 번호 계산(최댓값 + 1)에 들어가지 않는다. 그 계좌에는 쓰지 않으므로 지금은 "쓰기 주체 둘" 오판이나 겹침이 없지만, B4에서 `keyFloor`·부팅 번호를 계좌 원장에 기대게 되면 "읽지 못한 계좌가 있으면 부팅 번호를 어떻게 정하나"를 architect 권고 2와 함께 정한다.
- R-3. F-1 수정은 `LedgerWriter` 단위로 시험했다. "같은 묶음 뒤 줄 실패 → Guard 올림 `RaiseNotAppliedException(AUDIT_NOT_RECORDED)`, 상태 그대로"를 저장소·Guard를 함께 묶은 시험으로도 남기기를 권한다(R-1을 고칠 때 함께).
- R-4. `WriterLock.check()`가 경로 읽기 실패 때 잠금 채널로 넘어가는 분기 자체는 시험이 없다. 경로 읽기를 실패시키는 시험 훅(예: 패키지 전용 읽기 함수 주입)으로 그 분기와 "그 분기에서도 내용이 다르면 차단"을 시험하기를 권한다. Windows 실기 확인은 P12에 그대로 둔다.

### 판정
수정 필요(R-1: 공통 원장 `blacklist`·`boot`·`audit/commands` 읽기 오류가 엔진 기동 전체를 막음. ADR-015 2.7 ③과 어긋남, A7·A14 실패). F-1·F-3은 해결, F-2는 계좌 원장 부분만 해결. R-1을 고치고 시험을 넣은 뒤 같은 검토자에게 재검토를 맡긴다. 실전 관련 PR이므로 그 뒤에도 독립 QA와 사용자 머지가 필요하다.

## 재검토 2 2026-10-26
- 대상: PR #248 / 커밋 `e2d237d0`(R-1 수정) / `origin/main...e2d237d0` diff 전체. 재검토 1 뒤 바뀐 코드는 `EngineStore.java`·`LedgerScan.java`와 `EngineStoreTest`(3건 추가), 문서 `D-146`.
- 점검표: A
- 직접 실행한 것: `git archive e2d237d0`로 떠 낸 별도 폴더에서 `./gradlew build --offline` → **BUILD SUCCESSFUL**. 테스트 XML 합계 2,056건, 실패 0·오류 0·건너뜀 0. `:autotrade-risk:test` 68건, `:autotrade-real-store:test` 34건(재검토 1보다 3건 늘어남), `:autotrade-architecture-test:test` 47건, `:autotrade-app-engine:test` 114건.

### R-1 처리: 해결
- [사실] 공통 원장 검사가 `scanCommon`으로 바뀌어 `IOException`·`RuntimeException`을 `LedgerScan.unreadable`(상태 `CORRUPT`, 문구 "읽기 실패")로 돌려준다(`EngineStore.openLocked` ③, `LedgerScan.unreadable`).
  - 블랙리스트: `CORRUPT` → `globalBlocks`에 "블랙리스트 원장 손상" → 모든 계좌 (가) 전면 차단. 원장은 옮기지 않고 그대로 둔다. `EngineStoreTest#블랙리스트_원장을_읽지_못하면_기동은_하고_모든_계좌를_전면_차단한다` 통과(두 계좌 모두 차단, 보호 모드 상향 거절, 내림 가능).
  - 기동 원장: 옮기고 새로 시작, 경고만. 옮기기·새로 열기도 실패하면 `globalBlocks`에 "기동 기록 실패"를 넣고 `writeBootRecord`는 쓰지 않는다. ADR-015 2.5 기동 기록 행(모든 계좌 차단)과 맞다. 차단 사유는 `RecoveryReport`를 만들기 전에 들어가므로(`report` 생성이 계좌 원장 준비 뒤로 옮겨져 있음) 실제로 적용된다. `EngineStoreTest#기동_원장을_읽지_못하면_옮겨_두고_경고만_하고_기동한다` 통과.
  - 명령 원장: 읽기 실패면 폴더째 `commands.corrupt-<부팅>`으로 옮기고 다시 만든 뒤 `restartAfter` 체크포인트로 새로 시작, 경고만. 그마저 실패하면 명령 감사는 프로세스 로그에만 남기고(`recordCommand`의 `commandsLedger == null` 분기) 경고. 명령 감사는 Guard 감사와 겹치고 복구 입력이 아니므로 ADR-015 2.7 ③과 맞다. `EngineStoreTest#명령_원장을_읽지_못하면_폴더째_옮겨_두고_경고만_하고_기동한다` 통과(다음 기동에서 경고 없음까지 확인).
  - 종료 때 `bootLedger == null`이면 정상 종료 기록을 쓰지 않으므로 다음 기동은 비정상 종료로 본다(더 막는 쪽). `closeQuietly(null)` 처리 있음.
- [사실] 이제 기동 거부는 `writer.lock`을 못 잡을 때, 저장소 폴더가 심볼릭 링크일 때, 그리고 `StorePaths.prepareDir`가 저장소 폴더 자리에서 폴더가 아닌 것을 만났을 때뿐이다(참고 R2-1).

### 점검표 A (다시 확인)
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 수정 커밋은 Guard를 건드리지 않음. 저장소는 Guard를 되살리지 않음(ArchUnit `REAL_STORE_DOES_NOT_DRIVE_GUARD`). `EngineStoreTest#원장에_OPEN_전이가_있어도_재기동하면_모든_계좌는_OFF다` 통과. |
| A2 | 통과 | 주문 출구·ArchUnit 규칙 변경 없음. `ArchitectureTest` 통과. |
| A3 | 해당 없음 | 증권사·주문 경로 변경 없음. |
| A4 | 해당 없음 | 주문 전송·재전송 변경 없음. |
| A5 | 해당 없음 | 키 발급 없음(B4). 부팅 번호 겹침 가능성은 D-146 위험과 한계에 B4 후속으로 적힘(architect 권고 2, R-2). |
| A6 | 통과 | 블랙리스트 원장 손상·읽기 실패 모두 모든 계좌 전면 차단(위 시험 2건 통과). 블랙리스트 판정 코드 변경 없음. |
| A7 | 통과 | 계좌 원장 읽기·준비 실패는 그 계좌만 차단(`EngineStoreTest#한_계좌_원장을_읽지_못해도_저장소는_열리고_그_계좌만_전면_차단한다`), 공통 원장 읽기 실패도 기동을 막지 않음(위 3건). 계좌별 쓰기 스레드와 다른 계좌 지연 없음 시험 통과. |
| A8 | 통과 | 프로세스 격리 설정·ArchUnit 변경 없음. |
| A9 | 통과 | 내림은 감사 기록을 기다리지 않음. 차단된 계좌·모든 계좌 차단에서도 내림이 되는 것을 새 시험들이 확인. 보호 시세·서버 측 손절 경로 변경 없음. |
| A10 | 통과 | 새 경고·차단 문구는 예외 클래스 이름·OS 오류 문구·파일 이름만 싣는다. 명령 감사가 프로세스 로그로만 갈 때의 로그 줄은 명령 이름·대상·결과 코드뿐이고, 원장으로 갈 때와 달리 가리기를 거치지 않지만 그 값은 기존 `CommandAuditLog.LOGGING`이 이미 남기는 값과 같다. 시험 값은 자리표시자. |
| A11 | 통과 | 지운 시험·약한 단언 없음. 보호 테스트·고정 값 변경 없음. R-1의 세 경우 모두 실패 경우 시험이 있다. |
| A12 | 통과 | 장애 대응을 약하게 하지 않음(읽기 오류 때 기동 거부 → 손상과 같은 차단·경고로, ADR이 정한 쪽). 3-R·P11·P12 전제 유지. Windows 잠금 동작은 P12로 운영 PC에서 확인(D-146에 미검증으로 적힘). |
| A13 | 통과 | 계좌 원장 날짜는 거래소 현지 거래일(`rules::tradingDate`), 공통 원장은 UTC. 변경 없음. |
| A14 | 통과 | D-146 "위험과 한계"에 공통 원장 읽기 오류 처리와 남은 기동 거부 조건이 적혔고 코드와 일치. ADR-015 2.5·2.7 ③과 맞다. |

### 참고(수정 요구 아님)
- R2-1. 저장소 하위 폴더 자리(`boot`, `audit/commands`, `blacklist` 폴더 자체)에 일반 파일이 있으면 `prepareDir`가 `IOException`을 던져 기동이 거부된다. 백신의 일시 잠금 같은 흔한 장애가 아니라 폴더 구조가 망가진 경우라 받아들인다. I1 절차서에 "저장소 기동 거부 문구별 조치"로 적기를 권한다.
- R2-2. 기동 원장을 읽지 못하면 부팅 번호는 계좌·명령·블랙리스트 원장의 `boot` 최댓값으로만 정해진다. 지금은 키 발급이 없어 영향이 없고, B4에서 다시 정하는 것으로 D-146에 적혀 있다(R-2와 같음).
- R-3(저장소와 Guard를 묶은 "지워진 올림" 시험)·R-4(경로 읽기 실패 때 잡은 채널로 넘어가는 분기 시험)는 넣지 않았고, 그 이유가 D-146 검증 절에 적혔다. 각 조각은 시험되어 있고(`LedgerWriterTest`, 감사 실패 시 전이 미적용, 잡은 채널 읽기), 연결은 코드로 확인했다. B4에서 저장소 장애 주입 장치가 생기면 넣기를 권한다.

### 판정
승인. 실전 관련 PR이므로 머지 전 독립 QA와 사용자의 직접 확인·머지가 필요하다.

## 재검토 3 2026-10-27
- 대상: PR #248 / 커밋 `baf23da2`(독립 QA [qa.md](qa.md) "수정 필요" 반영, 앞에 main 병합 `54fc1a8c`) / `origin/main...baf23da2` diff 전체(61파일). 재검토 2 뒤 바뀐 코드는 `WriterLock.java`·`EngineStore.java`·`LedgerWriter.java`·`SecretMasker.java`·`StoreRefusal.java`와 시험 `EngineStoreTest`·`LedgerWriterTest`·`SecretMaskerTest`·`StoreFixtures`·`EngineCommandHandlerTest`, 문서 D-146·절차서 P12·`pending.md`다. main 병합 `54fc1a8c`가 이 PR 쪽으로 가져온 코드 변경은 없다. 병합 상대 `df22830b` 이후 main(`de766487`)에도 코드 변경은 없고 문서만 바뀌었다.
- 점검표: A
- 직접 실행한 것:
  - `git archive baf23da2`로 떠 낸 별도 폴더에서 `./gradlew build --offline` → **BUILD SUCCESSFUL**. 테스트 XML 합계 2,116건, 실패 0·오류 0·건너뜀 0. `:autotrade-risk:test` 68건, `:autotrade-real-store:test` 44건, `:autotrade-architecture-test:test` 48건(#247 규칙 포함), `:autotrade-app-engine:test` 115건.
  - `git merge-tree --write-tree origin/main baf23da2`(main `de766487`): `docs/qa/pending.md`·`docs/runbooks/stage-7-real-trading-tests.md` **문서 충돌**. 코드 파일 충돌은 없다(아래 조건 1).

### 내 앞 판단의 정정 (F-3)
- 재검토 1에서 F-3을 "해결"로, 재검토 2에서 이를 바탕으로 "승인"을 냈다. 그 근거였던 "경로의 파일이 바뀌었다면 새 파일은 잠기지 않아 경로 읽기가 성공한다"는 **다른 프로세스가 새 파일을 잠근 경우에 거짓**이다. 독립 QA 발견 1이 옳다.
- 경로 읽기가 실패할 때 엔진이 쥔 채널(처음 잡은 옛 파일)로 대조하면 늘 같으므로, Windows(`fileKey` 없음)에서 "쓰기 주체 둘"의 바로 그 경우를 놓친다. 내가 F-3 요구에 "자기 채널 읽기로 바꿀 때 교체 감지가 약해지지 않는지 시험으로 확인한다"고 적고도, 그 시험이 없는 것을 참고(R-4)로만 두고 승인했다. 확인하지 못한 것을 통과로 친 잘못이다. 앞 원문은 고치지 않고 여기에 기록한다.

### 독립 QA 발견·권고의 처리
| 항목 | 결과 | 근거 |
|---|---|---|
| 발견 1 (`writer.lock` 대체 분기) | 해결(Windows 실기는 미검증, P12 ①·④) | `WriterLock.acquire`는 이제 파일 전체가 아니라 위치 `LOCK_OFFSET = 2^62`의 1바이트만 잠근다(`tryLock(LOCK_OFFSET, 1, false)`). 그래서 내용(`boot=N pid=P`)은 잠금 구간 밖이다. `check()`는 경로로 읽지 못하면 **문제로 보고 차단**하고, 자기 채널로 대조하는 분기는 지웠다. 서로 막는 성질은 그대로다. 같은 코드를 쓰는 다른 쓰기 주체는 같은 바이트를 잠그려다 실패하고, 파일 전체 잠금(`tryLock()`)도 겹쳐 실패한다(`EngineStoreTest#쓰기_잠금은_내용_밖_구간을_잡아_내용은_경로로_읽히고_파일_전체_잠금과는_겹친다` 통과). 놓쳤던 경우의 시험: `#fileKey가_없고_경로로_잠금_파일을_읽지_못하면_다른_쓰기_주체로_보고_막는다`, `#fileKey가_없어도_같은_이름의_새_파일로_바뀌면_내용_대조로_막는다` 통과. [추론] Windows `LockFileEx`는 파일 끝 뒤 구간을 잠글 수 있고 잠금은 그 구간에만 걸리므로, 다른 핸들로 내용을 읽을 수 있다. JDK의 파일 전체 잠금이 0~`Long.MAX_VALUE`를 잠그는 것과 같은 범위 안이다. 운영 PC 확인은 P12 ①(내용 읽힘)·④(다른 프로세스가 새 파일을 쥠)에 들어갔다. |
| Q1 (공통 원장 폴더 준비 실패) | 해결 | `prepareCommon`이 실패를 예외로 던지지 않고 손상과 같이 넘긴다. 블랙리스트는 모든 계좌 (가) 전면 차단하고 옮기지 않는다. 기동 원장 폴더를 준비하지 못하면 기동 기록 실패로 모든 계좌를 막는다. 명령 원장은 옮기고 경고만 한다. 시험 3건 통과. 남은 입출력 실패는 `StoreRefusal`로 저장소 문구를 보인다(`#저장소를_여는_중_남은_입출력_실패는_기동_거부로_보인다`). D-146 문구가 코드와 맞다. |
| Q2 (복구 시간) | 해결(운영 PC 실측은 P11) | `#하루치_최대_기록_가정으로_다시_열어도_복구가_목표_시간_안에_끝난다` 통과(CI 여유 3초). D-146에 가정 값과 이 환경 0.71~0.83초 기록, B4에서 `boot`·`risk` 원장 분할을 다시 보는 조건이 적혔다. |
| Q3 (처리기 OPEN 미적용 분기) | 해결 | `EngineCommandHandlerTest#마지막_단계의_OPEN_올림이_적용되지_않으면_활성화_실패로_OFF가_되고_2차_확인을_다시_받아야_한다` 통과. 새 2차 확인 없이 다시 OPEN이 되는 길을 처리기가 막는다(D-146 결정 6). |
| Q4 (주기 확인 예외) | 해결 | `checkLockSafely`가 `Throwable`을 잡아 **차단 쪽으로** 두고 다음 주기를 계속 돈다. `#잠금_확인_중_예외가_나도_주기_확인은_멈추지_않고_막는다` 통과. |
| Q5 (앞 날짜 파일 줄) | 해결 | 되돌림 고리가 실제로 지워진 줄만 실패로 돌린다. 기준은 되돌린 뒤 디스크 기준 `lastRseq`보다 큰 `rseq`다. 되돌리기 자체가 실패한 경우(`broken`)는 같은 원장 앞 줄을 모두 실패로 둔다(안전 쪽). 날짜 파일 체크포인트가 `prevRseq + 1`에서 시작하므로 오늘 파일의 지워진 줄은 늘 기준보다 크다. `LedgerWriterTest#거래일이_바뀌며_앞_날짜_파일에_강제_기록된_줄은_뒤_줄이_실패해도_기록됨으로_끝난다`와 기존 같은 묶음 시험 둘 다 통과. |
| Q6 (긴 비밀값부터) | 해결 | `SecretMasker.mask`가 길이 내림차순으로 정렬한다. `SecretMaskerTest#짧은_비밀값이_긴_비밀값의_앞부분이어도_긴_값의_나머지가_남지_않는다` 통과. |

### 점검표 A (다시 확인)
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 코드는 재검토 2 이후 바뀌지 않았다(`git diff 54fc1a8c baf23da2`에 `autotrade-risk` 없음). `Snapshot.INITIAL` OFF, 원장으로 되살리지 않음(ArchUnit `REAL_STORE_DOES_NOT_DRIVE_GUARD`). `EngineStoreTest#원장에_OPEN_전이가_있어도_재기동하면_모든_계좌는_OFF다` 통과. |
| A2 | 통과 | 주문 출구·ArchUnit 규칙은 이 PR의 추가 2개 그대로. 병합된 main 규칙(#247)과 함께 `ArchitectureTest` 48건 통과. |
| A3 | 해당 없음 | 증권사·주문 경로 변경 없음. |
| A4 | 해당 없음 | 주문 전송·재전송 변경 없음. |
| A5 | 해당 없음 | 키 발급 없음(B4). 부팅 번호의 남은 한계는 D-146에 B4 후속으로 적혀 있다. |
| A6 | 통과 | 블랙리스트 원장 손상·읽기 실패·폴더 자리 파일 모두 모든 계좌 전면 차단(시험 통과). 블랙리스트 판정 변경 없음. |
| A7 | 통과 | 한 계좌 원장 문제는 그 계좌만 막는다. 공통 원장 문제는 ADR-015 2.7 ③대로 처리하고 기동을 막지 않는다. 계좌별 쓰기 스레드와 다른 계좌 지연 없음 시험 통과. |
| A8 | 통과 | 프로세스 격리 설정 변경 없음(재검토 2와 같음). |
| A9 | 통과 | 내림은 감사 기록과 저장소 차단에 상관없이 즉시 된다(새 시험들도 차단 상태 `shutdown` 확인). 보호 경로 변경 없음. |
| A10 | 통과 | 새 문구는 예외 클래스 이름·OS 메시지·파일 이름뿐. 시험 값은 자리표시자. 가리기 강화(Q6). |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test` 모듈·Guard 시험)의 삭제·약화 없음. 지운 시험 `EngineStoreTest#잠금을_쥔_채널로도_잠금_파일_내용을_대조할_수_있다`는 보호 테스트가 아니고, 결함이었던 대체 분기를 고정하던 시험이다. 그 분기를 지우며 함께 지웠고, 더 강한 시험(경로로 못 읽으면 차단)으로 바뀌었다. 새 동작마다 실패 경우 시험이 있다. |
| A12 | 통과 | ADR-015 2.9 (가)의 Windows 내용 대조 전제가 회복됐다. "경로로 못 읽음 = 차단"이라 3-R 감지는 앞보다 약해지지 않았다. 절차서 P12에 ①~④(다른 프로세스가 새 파일을 쥔 경우 포함)가 들어갔다. 다만 아래 조건 1의 절차서 충돌을 풀 때 P11·P12 줄이 남아야 한다. |
| A13 | 통과 | 날짜 계산 변경 없음. 거래일 경계 되돌림 경우가 시험에 더해졌다(Q5). |
| A14 | 통과 | D-146 결정 7·9, 위험과 한계, 검증 절이 바뀐 코드와 맞다(대체 분기 서술 삭제, 남은 기동 거부 조건, 복구 시간 가정). ADR-015 2.5·2.7 ③·2.9와 어긋나는 곳을 찾지 못했다. |

### 머지 전 조건과 참고(수정 요구 아님)
- **조건 1 (머지 전)**: main(`de766487`, #243 절차서 I1 보완)과 `docs/runbooks/stage-7-real-trading-tests.md`·`docs/qa/pending.md`가 충돌한다. 충돌을 풀 때 이 PR의 P11·P12 줄(P12 ①~④)과 #243의 I1 보완이 **둘 다** 남는지 확인한다. 코드는 충돌하지 않는다.
- R3-1. 경로로 못 읽는 것을 바로 전면 차단으로 보므로, Windows 백신이 `writer.lock`을 공유 없이 잠깐 여는 경우 **거짓 전면 차단**이 날 수 있다. 차단은 풀리지 않아 엔진을 다시 띄워야 한다. 막는 쪽이라 안전 약화는 아니다. P12 시행 때 알림 빈도를 같이 기록하고, 잦으면 "연속 2회 실패 때 차단" 같은 완화를 risk-ops와 다시 정한다(완화는 실전 관련 변경).
- R3-2. Windows에서 2^62 위치 잠금과 그 밖 구간 읽기는 [추론]이다. P12 ①이 실패하면(내용이 읽히지 않으면) 엔진이 켜질 때마다 5초 뒤 모든 계좌가 막힌다. 실전 활성화 전 P12 시행이 필수다(7단계 활성화 판정에서 확인).
- 재검토 2의 참고 R2-1(폴더 자리 파일)은 Q1로 처리됐다.

### 판정
승인. 조건 1(main 충돌 해소 때 절차서 P11·P12 보존)은 머지 직전 확인한다. 실전 관련 PR이므로 독립 QA 재검토와 사용자의 직접 확인·머지가 필요하다.
