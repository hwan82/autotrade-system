# architect 검토: 문서 정리(PR #105 QA 판정 처리 기록, ADR-006 소유 규칙 전제, D-040 각주, v14.30 번호 미사용, KrxMarketRules 주석 정정)
- 대상: PR #111 / 커밋 83b5df2 / main 대비 diff(`git diff origin/main...HEAD`, merge-base = origin/main = d0e9f3f, 7파일 +10 −5)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --continue`(헤드 83b5df2, 별도 worktree): **BUILD SUCCESSFUL**, 130 actionable tasks. 첫 시도 두 번은 환경 문제로 실패(`--offline`: trove4j 캐시 없음, 온라인: Maven Central 429로 archunit-junit5-engine 해석 실패). 코드 실패 아님, 세 번째 실행에서 통과
  - 테스트 결과 XML 집계: 19개 모듈 합계 1,140건, 건너뜀 0, 실패 0, 오류 0(architecture-test 40, marketdata 141, sim 225, app-engine 50 등)
  - `git merge-tree --write-tree HEAD origin/main`: 충돌 없음
  - diff 안 주장 대조: `KrxMarketRules.sessionPhase`(`KrxMarketRules.java:82-96`), `VirtualExchange.java:237-241`(D-087 판정), `ArchitectureTest.java:177-183`(`ONLY_COMMAND_CHANNEL_RAISES_GUARD`), `docs/reviews/pr-105/qa.md:64-88`, `docs/reviews/pr-106/architect.md:31,64`, `docs/reviews/pr-21/risk-ops.md:185-209`, `git log 7ae247c`·`460cad2`, 기획서 `:12`(버전 v14.32)·`:20-23`·`:321`(TST-05), `docs/decisions/README.md:46,93,126`

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. 헤드 83b5df2에서 `./gradlew build --continue` BUILD SUCCESSFUL, 테스트 1,140건 실패 0. 앞선 두 실패는 의존성 내려받기(오프라인 캐시 없음, HTTP 429) 환경 문제로 태스크 실패가 아님을 로그로 확인 |
| B2 | 통과 | 운영 코드 변경은 `KrxMarketRules.java:30-32` Javadoc 3줄뿐, import·의존성·빌드 스크립트 변경 없음. ArchUnit 규칙 변경 없음(`autotrade-architecture-test` diff 없음), `ArchitectureTest`·`ModuleOwnershipTest` 통과(architecture-test 40/40) |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST API 변경 없음. ADR-006 변경은 계약 변경이 아니라 기존 규칙의 전제 서술 추가(`ADR-006-real-control-channel.md:177`). 서술 내용은 사실과 맞다: 네 규칙 모두 패키지 이름으로 허용 대상을 정하고(`ArchitectureTest.java:178` `resideOutsideOfPackages(GUARD_COMMAND_CHANNEL, "com.autotrade.risk.guard..")` 등), 소유 규칙 없이 같은 이름 패키지·FQN 위장이 통과함은 risk-ops가 E2·E3로 실측했다(`docs/reviews/pr-21/risk-ops.md:185-187`, 권고 `:209`). 단 risk-ops 실측은 세 규칙(`ONLY_ENGINE_OPENS_CONTROL_SERVER`·`ONLY_LAB_SENDS_CONTROL_COMMANDS`·`ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`)이고 `ONLY_COMMAND_CHANNEL_RAISES_GUARD`는 같은 구조에서 미루어 본 것이다 `[추론]`(아래 N2) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 골든 테스트 포함 sim 225건 통과. `KrxMarketRules` 주석 변경은 동작 변경 없음(바이트코드 영향 없는 Javadoc). 새 주석의 동작 서술을 코드와 대조: 개장 전 30분은 `OPENING_AUCTION`(`KrxMarketRules.java:89-90`, `OPENING_AUCTION = 30분` `:50`), 가상 거래소는 접수 구간의 `TradeTick`을 그날 첫 정규장 이벤트로 세지 않고(`VirtualExchange.java:240` `counted = !(event instanceof TradeTick && phase.isAuction())`) 접수 구간 체결로 주문을 체결하지 않음(`VirtualExchange.java:68` 주석, D-087 1번). 주석이 PR #106 architect F2의 제안 문구(`pr-106/architect.md:31`)와 일치 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(주석·문서만). 테스트 삭제·`@Disabled` 추가 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·리스크 한도·장애 대응 규칙의 코드·설정 변경 없음. `broker-and-fault-design.md:197`은 기존 문장 뒤에 D-040·TST-05 링크 한 문장 추가로 상한 값을 새로 정하지 않고 확정 결정(D-040, 사용자 결정)을 가리킨다. 링크된 상한은 기획서 TST-05(`autotrade-spec.md:321`)·`decisions/README.md:46`과 같다. ADR-006 추가 문장은 risk-ops 자신의 권고(`pr-21/risk-ops.md:209` "다음 ADR-006 수정 때 한 줄")를 반영한 것이고 보호를 약화하지 않는다. 보호 테스트 변경 없음 |
| B8 | 통과 | diff에 API Key·계좌번호·토큰 없음(diff 전체에서 key/secret/token/account 검색, 해당 단어는 기존 qa-out-status 문맥 줄뿐) |

### 문서 주장 대조(점검표 보조)
- `qa-out-status.md:58`: PR #105 QA 1차 판정 "수정 필요(A14)"(`pr-105/qa.md:65`), 재검토 "승인"(`:88`), 권고 R1·참고 R2·R3·R4(`:60`, `:84-85`)와 일치. FAIL/PASS 표기는 이 표의 기존 관례(`:57` PR #109 행, 원문은 "수정 필요")와 같다. `7ae247c`(main 병합·실행 매뉴얼 수정), `460cad2`(Merge pull request #105) 커밋 존재 확인
- 기획서 `:20` v14.30 번호 미사용 행: `:21` v14.32 행이 "v14.29·v14.30은 다른 PR이 쓴다"고 적고, 변경 이력에 v14.30 행이 없었으며(`:23` v14.29만 있음), 머리 버전은 v14.32(`:12`) 그대로다. QA R4(`pr-105/qa.md:84`) 권고 그대로
- D-034 부록 제목·D-039 `:26`: 원문을 지우지 않고 "→ D-040으로 확정"만 덧붙였다. 결정 로그 원칙(뒤집거나 확정할 때 새 번호, 기존 기록 보존)에 맞다. D-040 파일 존재, `decisions/README.md:126` Q-021 → D-040 확정과 일치

## 점검표 밖 발견
- **N1 (낮음, 막지 않음)** `KrxMarketRules.java:30` 새 문장 "장전 시간외 종가 매매(08:30~08:40)"에 출처 표시가 없다. 같은 단락 앞 문장이 `[사실]`(업무규정 제4조·제23조)이라 읽는 사람이 이 시각도 `[사실]`로 받을 수 있는데, 저장소의 같은 정보는 `[가정]`이다(`docs/runbooks/collector.md:67` "장전 시간외(정규장일 08:30~08:40 … `[가정]`, KRX 공식 안내로 확인)", D-087 배경 2번). 다음 `KrxMarketRules` 변경 때 `[가정]`을 붙이거나 KRX 공식 안내로 확인해 출처를 달기를 권한다. 동작에는 영향이 없다(판정은 30분 상수로만 한다).
- **N2 (참고)** ADR-006 추가 문장이 `ONLY_COMMAND_CHANNEL_RAISES_GUARD`까지 "소유 규칙 없이는 위장이 통과한다"고 적는데, 근거로 단 risk-ops 재검토 4의 실측(E2·E3)은 나머지 세 규칙 대상이다. 이 규칙도 `resideOutsideOfPackages`로 허용 범위를 정하므로(`ArchitectureTest.java:178`) 같은 결론이 맞다고 본다 `[추론]`. 직접 변조 실험은 하지 않았다. 문서 정확성 문제로 막을 정도는 아니다.
- 이 PR은 운영 동작을 바꾸지 않으므로 ADR·결정 로그 새 번호가 필요 없다는 판단에 동의한다.

## 판정
승인 (B1~B8 중 실패·확인 못 함 없음. 권고 N1(주석 시각 출처 표시), 참고 N2. 실전 경로 코드 변경 없음)
