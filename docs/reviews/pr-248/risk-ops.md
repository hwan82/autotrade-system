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
