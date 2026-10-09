# risk-ops 검토: ADR-006 6장: PR #18 QA·architect 후속 지적을 7단계 완료 조건으로 등록
- 대상: PR #22 / 커밋 84e69de2fe4ad14dc5dd4d678f126ca0b6740564 / main 대비 diff(merge-base 42acc86 = origin/main, 1개 파일 +4 −0: `docs/adr/ADR-006-real-control-channel.md` 6장 표에 4행 추가)
- 점검표: A
- 직접 실행한 것:
  - 작업 폴더를 건드리지 않으려고 scratchpad의 전용 폴더에 저장소를 복제해 84e69de를 체크아웃하고 `./gradlew build --rerun-tasks --max-workers=2` 실행: **종료 코드 0(BUILD SUCCESSFUL)**. 테스트 결과 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0 (app-engine 48, risk 41, architecture-test 13, control 20, app-lab 97, sim 75, config 29, domain 27, strategy 25, gateway 1, watchdog 1, broker-api 1). 지정 최소 범위 `:autotrade-risk:test`, `:autotrade-architecture-test:test`, `:autotrade-app-engine:test` 포함
  - `git diff --stat origin/main...HEAD -- autotrade-risk autotrade-architecture-test docs/team docs/decisions docs/spec`: 빈 결과(보호 테스트·장애 설계·결정 로그·기획서 무변경)
  - 새 4행이 근거로 든 코드 사실을 직접 대조: `ControlServer.java:236-244`(STALE은 `enqueuedAt` 이후 대기열 시간만 봄), `OperatorAuthenticator.java:48,60,97,107`(`lockedUntil`·`verify`가 같은 `synchronized`), `OperatorPasswordHash.java:28`(MAX_ITERATIONS 10,000,000), `EngineMain.java:134`(hash-password 안내가 "실전 엔진을 다시 시작"만 말함), `docs/config/config-keys.md:30`("실전 엔진과 실험실을 모두 다시 시작"), `ControlServer.java:87`(잠금 파일 `NOFOLLOW_LINKS` 없음), `ControlServer.java:104-107`(truncate 뒤 PID 쓰기). 모두 행 설명과 일치
  - 출처 대조: `docs/reviews/pr-18/qa.md` "점검표 밖 발견" 1~5, `docs/reviews/claude-project-thread-r930sh/architect.md` 재검토 3 참고 1·2(279-282행)
  - diff에 비밀값 형태 문자열 검색: 0건

## 보호 테스트 변경 여부(먼저 확인)
- 코드·테스트 변경 없음. `RealTradingGuard`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `ArchitectureTest`, 장애 테스트 모두 무변경이고 전체 빌드에서 통과.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 무변경(diff는 ADR 표 4행). `RealTradingGuard` 생성 경로 그대로(`EngineMain`), `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다`, `#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 포함 app-engine 48건 통과. 새 행 중 기본 OFF·재기동 OFF를 바꾸자는 내용 없음 |
| A2 | 통과 | `ArchitectureTest` 무변경, 13건 통과(`ONLY_COMMAND_CHANNEL_RAISES_GUARD`, `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION` 포함). 새 행 1은 OPEN으로 가는 조건을 더 좁히는 방향 |
| A3 | 해당 없음 | 증권사 선택·주문 전송 코드와 문서 변경 없음 |
| A4 | 해당 없음 | 주문 재전송 경로 변경 없음. 명령 채널 무재전송 원칙(`ControlClient`, `RealOpsController`)도 무변경 |
| A5 | 해당 없음 | 주문 생성·멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유·청산 경로 변경 없음 |
| A7 | 통과 | 큐·토큰 버킷·계좌별 격리 코드 무변경. 새 행 3(잠금 반복 시 PANIC 청산 불가)은 계좌 간 영향을 다루는 후속인데, 완료 조건 문구가 불충분하다(아래 수정 필요 2) |
| A8 | 통과 | 프로세스 분리 코드·Gradle 격리 규칙 무변경. 새 행 2(해시 계산을 모니터 밖으로)는 실험실 STATUS가 엔진 검증에 막히지 않게 하는 방향 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절·PANIC 보호 모드 코드 무변경. `EngineCommandHandlerTest`(PANIC 보호 모드 유지 계열) 통과 |
| A10 | 통과 | diff 4행에 키·계좌번호·토큰·해시 리터럴 없음(직접 검색 0건). 비밀값을 쓰는 코드 변경 없음 |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음(위 "보호 테스트 변경 여부"). 새 동작이 없으므로 새 테스트 요구 없음. 다만 새 7단계 완료 조건 행에 "합격 기준이 되는 테스트"가 적혀 있지 않다(아래 수정 필요 1·2) |
| A12 | 통과 | `docs/team/broker-and-fault-design.md` 무변경. 추가 행은 장애 대응표 14종·O1~O8·장애 테스트 8종의 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 계산 없음 |
| A14 | 통과 | 동작 변경이 없어 결정 로그·기획서와 어긋날 것이 없다. 추가 행은 PR #18 QA 승인 원문의 권고("1번은 6장 후속 표에 7단계 완료 조건으로 올릴 것")와 architect 재검토 3 참고 1을 기록한 것으로 출처와 일치 |

## 점검표 밖 발견

이 PR의 목적은 PR #18에서 "지금은 피해 경로가 없지만 7단계에서 살아나는 결함"을 7단계 완료 조건으로 고정하는 것이다. 7단계 risk-ops·QA는 이 표의 문구를 합격 기준으로 쓰게 되므로, 문구가 결함을 실제로 막지 못하거나 확인할 수 없으면 기록 목적을 이루지 못한다. 아래 1·2는 그런 경우다.

### 수정 필요
1. **새 행 1(늦게 끝나는 활성화)의 조건이 결함을 다 막지 못한다.**
   - (a) "활성화 각 단계 직전"만 확인하면 **마지막 단계 수행 시간이 검사 밖**이다. OPEN 전이는 마지막 단계(`FIRST_RECONCILIATION`)의 `completeStep`에서 일어난다(`RealTradingGuard.java:195-196`, 호출은 `EngineCommandHandler.java:138` `steps.perform` 직후의 `completeStep`). 마지막 대사가 길어지면 단계 직전 검사는 통과하고 그 뒤 실험실이 503을 낸 다음 OPEN이 된다. QA pr-18 1번이 막으려던 시나리오 그대로다. 조건에 "**OPEN으로 올리는 `completeStep` 직전(마지막 단계 수행 후)**"과 "**`beginActivation` 직전(해시 검증 후)**"을 명시해야 한다. 해시 검증은 반복 상한 10,000,000회에서 10초가 넘게 걸린다(`OperatorPasswordHash.java:28`, QA 직접 시험 10.76초).
   - (b) 기준 시각이 맞지 않는다. 행은 "요청 접수 후 경과 시간이 실험실 대기 상한을 넘기면"이라고 쓰는데, 엔진의 `enqueuedAt`은 요청 줄을 다 읽은 뒤 잡힌다(`ControlServer.java:237`). 실험실의 15초(`LabWebConfiguration.java:35`)는 연결·전송 전부터 흐르고, 서버는 요청 줄을 최대 5초 기다린다(`ControlServer.java:58` `READ_TIMEOUT`). 엔진 기준 15초로 자르면 실험실이 이미 "응답 없음"을 보인 뒤 OPEN이 되는 틈이 남는다. 조건을 "실험실 대기 상한보다 **여유를 둔 엔진 쪽 기한**(예: 읽기 상한만큼 뺀 값) 또는 **요청에 실린 절대 기한**을 넘기면 출발 상태로"로 고쳐야 한다.
   - (c) 합격 기준 테스트를 함께 적는다: "단계 수행이 기한을 넘기면 OPEN이 되지 않고 출발 상태로 돌아간다(마지막 단계 포함)", "기한을 넘긴 뒤에는 실험실 응답 여부와 상관없이 OPEN 0건".
2. **새 행 3(잠금 반복으로 PANIC 청산 불가)이 "대책 검토"라 완료 여부를 판정할 수 없다.** 7단계 완료 조건으로 쓰려면 끝났는지 확인할 수 있는 결과물이어야 한다. QA pr-18 3번이 낸 구체안(잠금 발생 시 외부 알림에 포함)과, 잠긴 동안에도 사람이 청산할 수 있는 경로가 있는지(예: 감시 프로세스 긴급 경로 MON-02 또는 터미널 직접 경로)를 정하는 결정을 조건으로 적어야 한다. 함께, **ADR-006 4장 위험 표의 "같은 OS 사용자로 도는 악성 프로그램" 행**(144행)은 영향을 "모두 안전 방향"으로만 적고 있어 이 서비스 거부 영향(15분마다 틀린 확인 5회로 PANIC 청산을 계속 막음, `EngineCommandHandler.java:174-189`)이 빠져 있다. QA가 지적한 내용이므로 4장 행도 같이 고친다.
3. **새 행 2(해시 계산을 모니터 밖으로)에 반복 상한 재검토가 빠졌다.** QA pr-18 2번은 "반복 상한을 실험실 대기 상한과 맞춰 낮추는 것도 함께 검토"를 요구했다. 모니터만 빼면 STATUS는 풀리지만, 10,000,000회 해시는 ACTIVATE 한 건이 STALE 10초 + 검증 10초 이상이 되어 위 1번 결함을 키운다. 행에 "`OperatorPasswordHash.MAX_ITERATIONS`를 실험실 대기 상한 안에 검증이 끝나는 값으로 낮추거나 근거 기록"을 넣는다.

### 참고
1. 출처 표기 "architect 참고 1", "architect 참고 2"는 architect 원문에 1차·재검토 1·재검토 2·재검토 3의 참고 번호가 각각 있어 모호하다. 실제 출처는 재검토 3 참고 1(행 1·2), 재검토 3 참고 2(행 4, 빈 잠금 파일)다. 행 1처럼 "재검토 3"을 붙이면 7단계 검토자가 바로 찾을 수 있다.
2. architect 재검토 1·2에 남은 "프로브 connect 타임아웃 없음"(`ControlServer.java` 프로브)은 architect 재검토 3이 "기록 없음"이라고 했는데 이번 추가에도 없다. 실전 위험은 기동 지연(그동안 모두 OFF)이라 안전 방향이지만 같은 정리 PR이므로 행 4에 넣으면 누락이 없다.
3. ADR-006 머리의 "상태: 제안"은 PR #18 머지 뒤에도 그대로다. 결정 번호 부여(pm-lead)와 함께 갱신할 일이며 이 PR의 판정 사유는 아니다.
4. 이 PR은 문서만 바꾸지만 실전 경로 PR(#18)의 후속 완료 조건과 보호 동작의 합격 기준을 정하는 문서라, 지침 10장에 따라 사용자 확인 후 머지 대상으로 본다.

## 판정
판정은 **수정 필요**

- 항목: 점검표 밖 수정 필요 1(활성화 기한 확인 위치에 OPEN 전이 직전·`beginActivation` 직전 추가, 기준 시각을 실험실 기한보다 앞서게, 합격 테스트 명시), 2(행 3을 판정 가능한 조건으로 바꾸고 4장 위험 표 갱신), 3(행 2에 해시 반복 상한 재검토 추가).
- 점검표 A는 실패 0, 확인 못 함 0, 해당 없음 5(A3·A4·A5·A6·A13). 코드 변경이 없어 계좌 훼손 경로를 새로 만들지는 않는다. 수정 필요 사유는 7단계 합격 기준으로 쓰일 문구가 QA·architect가 지적한 결함을 다 막지 못하는 점이다.
- 고친 뒤 같은 파일 아래 `## 재검토 <날짜>`로 다시 검토한다.

## 재검토 2026-10-09
- 대상: PR #22 / 커밋 60d66e6008140ccf02008dac3f9ae251e14db2c5 / main 대비 diff(merge-base 42acc86, origin/main 9eab2e6). 3개 파일: `docs/adr/ADR-006-real-control-channel.md`(+7 −2: 머리 상태 줄, 4장 위험 표 144행, 6장 표 196-200행), `docs/reviews/pr-22/architect.md`(신규), `docs/reviews/pr-22/risk-ops.md`(신규, 직전 검토 원문)
- 점검표: A
- 직접 실행한 것:
  - scratchpad의 전용 폴더에 저장소를 새로 복제해 60d66e6을 체크아웃하고 `./gradlew build --rerun-tasks --max-workers=2` 실행: **종료 코드 0**. 테스트 결과 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0 (app-engine 48, risk 41, architecture-test 13, control 20, app-lab 97, sim 75, config 29, domain 27, strategy 25, gateway 1, watchdog 1, broker-api 1). `RealTradingGuardTest`, `GuardedOrderGatewayTest`, `ArchitectureTest`, `EngineMainTest`, `EngineCommandHandlerTest` 결과 파일 존재·통과
  - `git diff --stat origin/main...HEAD -- autotrade-* docs/team docs/decisions docs/spec scripts .github`: 빈 결과(코드·테스트·장애 설계·결정 로그·기획서·CI 무변경)
  - diff 전체에서 키·토큰·비밀번호·계좌번호·긴 base64 형태 검색: 비밀값 0건(걸린 줄은 모두 `hash-password` 명령 이름, "secrets.json" 파일 이름, 검토 원문 인용)
  - GitHub REST로 PR #18 상태 확인: `merged: true`, `merged_at 2026-10-09T06:12:40Z`. PR #22는 open, head 60d66e6
  - 새 문구가 근거로 든 코드 사실 대조: `OperatorAuthenticator.java:26-27`(`MAX_FAILURES` 5, `LOCKOUT` 15분), `EngineCommandHandler.java:174-189`(PANIC은 `lock` 뒤 `confirm`, 거절이면 `lockOnly`로 보호 모드 고정만 하고 청산 생략), `EngineCommandHandler.java:120,138,143,149`(`beginActivation` → `steps.perform` → `failActivation`/`completeStep`), `OperatorPasswordHash.java:28`(`MAX_ITERATIONS` 10,000,000), `ControlServer.java:58,60,153-213,237`(READ_TIMEOUT 5초, STALE_AFTER 10초, 읽기 기한은 `serve` 시작부터, `enqueuedAt`은 요청 읽은 뒤), `ControlServer.java:298-300`(`isListening` 프로브 connect 시간 상한 없음), `LabWebConfiguration.java:35`(실험실 15초), `ControlClient.java:56-66`(실험실 기한은 connect 전부터 흐름). 모두 새 문구와 일치

### 직전 지적 처리 확인
| 직전 지적 | 처리 | 근거 |
|---|---|---|
| 수정 필요 1(a) 기한 확인 위치 | **해소** | ADR-006:196 ① 해시 검증 뒤 `beginActivation` 직전, ② 각 단계 직전, ③ 마지막 단계 뒤 OPEN 전이(`completeStep`) 직전. 코드 순서(`EngineCommandHandler.java:120,138,149`)와 맞는다 |
| 수정 필요 1(b) 기한 기준 시각 | **해소** | ADR-006:196 (가) 요청에 절대 기한, (나) "엔진 기한 + 요청 읽기 상한(5초) < 실험실 대기 상한" 규칙 중 7단계 설계에서 택일. 지금 `STALE`이 `enqueuedAt` 기준이라는 한계도 적었다. 남은 세부는 아래 참고 1 |
| 수정 필요 1(c) 합격 테스트 | **해소(부분 보완 권고)** | ADR-006:196 "단계·해시 검증·마지막 단계를 각각 기한 너머로 늦추면 OPEN이 되지 않고 출발 상태로 돌아온다". 실험실 쪽에서 본 끝-끝 테스트는 아래 참고 1 |
| 수정 필요 2 행 3 판정 가능성, 4장 표 | **해소** | ADR-006:198 (1) 잠금 시 외부 알림, (2) 잠긴 동안 청산 경로 결정(Q 번호로 사용자 질문), 합격 기준 "잠금 상태에서 알림이 나가고 정해진 청산 경로가 장애 테스트로 확인됨". ADR-006:144 영향 칸에 "틀린 확인 5번으로 15분 잠금 반복 시 PANIC은 보호 모드 고정까지만(청산 서비스 거부)" 추가. 코드(`OperatorAuthenticator.java:26-27`, `EngineCommandHandler.java:182-185`)와 일치 |
| 수정 필요 3 해시 반복 상한 | **해소** | ADR-006:197 "`MAX_ITERATIONS` 10,000,000, 직접 시험 약 10.8초를 활성화 기한 안에 들도록 낮출지 재검토" |
| 참고 1 출처 표기 | **해소** | 196·197·199행에 `../reviews/claude-project-thread-r930sh/architect.md` 링크와 "재검토 3 참고 1/2" 명시 |
| 참고 2 프로브 connect 상한 | **해소** | ADR-006:200 신규 행(`ControlServer.isListening`). 출처 "architect 재검토 2 참고"는 링크가 없으나 architect 원문 178·232행에 해당 내용 있음 |
| 참고 3 ADR 상태 "제안" | **해소** | ADR-006:3 "채택 (PR #18 머지, 2026-10-09 사용자 결정. 이전 상태: 제안)". REST 조회로 PR #18 머지 확인. 결정 로그 번호는 여전히 pm-lead 후속 |
| 참고 4 사용자 머지 대상 | 유지 | 아래 참고 4 |

### 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 무변경(위 diff --stat 빈 결과). `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다`, `#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다`, `RealTradingGuardTest` 포함 전체 통과. 새 문구 중 기본 OFF·재기동 OFF를 바꾸는 내용 없음. 196행은 OPEN 전이 조건을 더 좁힌다 |
| A2 | 통과 | `ArchitectureTest` 무변경, 13건 통과. 196행 ①~③은 OPEN으로 가는 경로에 확인을 더하는 방향이며 우회 경로를 만들지 않는다 |
| A3 | 해당 없음 | 증권사 선택·주문 전송 코드·문서 변경 없음. 198행의 "잠긴 동안 청산 경로"는 7단계 결정 항목이고 이 PR은 경로를 정하지 않는다 |
| A4 | 해당 없음 | 주문 재전송 경로 변경 없음 |
| A5 | 해당 없음 | 주문 생성·멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유·청산 코드 변경 없음 |
| A7 | 통과 | 큐·토큰 버킷·계좌 격리 코드 무변경. 계좌 간 영향이 걸린 잠금 남용(모든 계좌의 PANIC 청산이 한 잠금에 묶임, `OperatorAuthenticator` 단일 인스턴스)이 4장 144행 위험과 6장 198행 완료 조건(알림 + 청산 경로 + 장애 테스트)으로 기록됨 |
| A8 | 통과 | 프로세스 분리 코드 무변경. 197행(해시 계산을 모니터 밖으로)과 196행 (나) 규칙은 엔진이 실험실 설정을 읽지 않고 상수 관계로만 맞추는 방향이라 프로세스 독립을 해치지 않는다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절·PANIC 보호 모드 코드 무변경, `EngineCommandHandlerTest` 통과. 144행 갱신은 보호 모드 고정이 잠금과 상관없이 된다는 사실(`EngineCommandHandler.java:180-181`)을 유지해 적었다 |
| A10 | 통과 | diff 비밀값 검색 0건(위). 해시 리터럴·계좌번호·키 없음 |
| A11 | 통과 | 테스트 파일 변경 없음, 보호 테스트 전부 통과. 새 동작 없음. 7단계 완료 조건 3행(196·197·198)에 합격 기준이 적혀 있다(197행은 재검토 항목이라 테스트 대신 결과 기록) |
| A12 | 통과 | `docs/team/broker-and-fault-design.md` 무변경. 198행은 장애 테스트를 추가하는 방향이며 대응표 14종·O1~O8·장애 테스트 8종의 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 계산 없음 |
| A14 | 통과 | 동작 변경 없음. 상태 "채택"은 PR #18 머지 사실과 일치(REST 확인). 결정 로그 번호 미부여는 ADR 문구대로 pm-lead 후속이고, 198행은 새 결정을 Q 번호로 묻겠다고 적어 결정 로그 절차와 맞다 |

### 점검표 밖 발견

#### 참고 (판정 사유 아님, 7단계 설계·검토 때 반영)
1. **196행 (나) 규칙의 기준점과 끝-끝 합격 기준.** `[사실]` 실험실 15초는 connect 전부터 흐르고(`ControlClient.java:56-66`), 엔진의 읽기 기한 5초는 `serve` 시작(수락 뒤)부터다(`ControlServer.java:199-200`). 수락 전 대기(커널 backlog, 수락 스레드 지연)와 OPEN 뒤 응답 쓰기 시간은 "엔진 기한 + 5초" 식에 들어 있지 않다. `[추론]` (나)를 고르면 엔진 기한의 시작점을 "연결 수락 시각"으로 명시하고 실험실 상한과의 차이에 여유(예: 수 초)를 두어야 한다. (가)를 고르면 두 프로세스가 같은 기준 시계를 써야 하는데, 벽시계는 NTP 보정으로 뛸 수 있으니 엔진이 받은 즉시 남은 시간으로 바꿔 단조 시계로 재는 방식이 안전하다. 또한 합격 테스트에 "실험실 클라이언트가 시간 초과(`EngineUnreachableException`)로 끝난 뒤에는 엔진 쪽 OPEN 0건"을 끝-끝으로 하나 넣으면 위 틈까지 한 번에 확인된다. 7단계 risk-ops 검토에서 이 두 가지를 합격 조건으로 본다.
2. **architect 승인 원문의 대상 커밋.** `docs/reviews/pr-22/architect.md`의 승인은 84e69de(4행 추가) 기준이고, 60d66e6에서 바뀐 머리 상태 줄·4장 144행·6장 196-200행에 대한 architect 재검토는 저장소에 없다. 문서뿐이고 내용은 위에서 대조했지만, 지침 11장대로 architect 재검토를 받아 같은 파일에 덧붙이는 것이 맞다.
3. **200행 출처 링크 없음.** "architect 재검토 2 참고"에 원문 링크가 없다(실제 위치: `docs/reviews/claude-project-thread-r930sh/architect.md` 178·232행). 다음에 표를 고칠 때 붙이면 된다.
4. 이 PR은 실전 경로(#18)의 7단계 합격 기준과 위험 표를 정하는 문서라 지침 10장에 따라 사용자 확인 후 머지 대상으로 본다. 이 판정은 그 확인을 대신하지 않는다.

### 판정
판정은 **승인**

- 직전 수정 필요 1·2·3과 참고 1·2·3이 모두 해소됐다(위 표). 점검표 A는 실패 0, 확인 못 함 0, 해당 없음 5(A3·A4·A5·A6·A13). 코드·테스트·장애 설계 변경이 없어 계좌 훼손 경로를 새로 만들지 않고, 추가 문구는 OPEN 전이 조건을 좁히고 PANIC 청산 방해 위험을 기록하는 방향이다. 전체 빌드 378건 통과.
- 참고 1~4는 승인 조건이 아니다. 단, 참고 2(architect 재검토)는 지침 11장 절차 항목이다.
