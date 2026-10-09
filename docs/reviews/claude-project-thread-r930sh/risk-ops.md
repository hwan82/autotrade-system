# risk-ops 검토: 실전 운영 화면(ADR-006) 1차
- 대상: 커밋 4ab3553
- 비고: 프로젝트 지침 11장 도입(2026-10-09 #15) 전에 받은 검토라 점검표 형식이 아니다. 아래는 risk-ops 서브에이전트의 최종 보고를 줄 들여쓰기만 빼고 그대로 옮긴 원문이다. 재검토는 11장 형식으로 이 파일 아래에 덧붙인다.

## 1차 검토 원문 (2026-10-09)

## risk-ops 교차 검토: 커밋 4ab3553 (ADR-006 실전 제어 명령 채널·운영 화면 1차)

**판정: 조건부 승인.** 차단 등급은 없다. 아래 중요 M1~M5는 머지 전에 고치고, M6~M10은 ADR-006의 "7단계 실전 활성화 전 완료 조건"으로 명시하는 조건이다. 실전 경로 PR이므로 risk-ops 검토 후에도 머지는 사용자가 직접 한다(지침 10장).

**검증 실행 결과:** `./gradlew --offline :autotrade-control:test :autotrade-app-engine:test :autotrade-architecture-test:test :autotrade-config:test`와 `:autotrade-app-lab:test --tests '*RealOpsControllerTest*'`가 모두 통과했다(control 11, engine 36, architecture 12, 실패 0, 건너뜀 0). 코드는 고치지 않았다.

### 질문별 결론
1. **2차 확인 없이 Guard가 올라가는 경로: 없다.**
   - `SecondConfirmation`을 만드는 곳은 `OperatorAuthenticator.confirmationFor`(OperatorAuthenticator.java:92)뿐이다. 호출하는 곳도 `confirm()` 직후의 `activate`(EngineCommandHandler.java:98-103) 하나다.
   - ArchUnit 규칙 2개가 이를 고정한다. `enterProtective`를 운영 코드에서 호출하는 곳은 없다.
   - 실험실(UI)이 웹 계층에서 뚫린 경우(CSRF 우회, XSS)에는 비밀번호 없이 올릴 수 없다.
   - 다만 실험실 **프로세스**가 장악되면 막지 못한다. 근거는 M5에 적었다. 그래서 ADR의 "실험실이 뚫려도 비밀번호 없이는 올릴 수 없다"는 주장은 과장이다.
2. **증권사 연동 전 주문 세션 허가 발급: 불가능하다.**
   - `BROKER_NOT_CONNECTED`가 첫 단계(AUTHENTICATED)에서 실패한다(ActivationStepRunner.java:20).
   - `issueSessionPermit`은 ACTIVATING 상태에서 AUTHENTICATED를 통과해야만 허가를 낸다(RealTradingGuard.java:129-142).
   - `RealOrderGateways.open`을 호출하는 운영 코드가 없다.
   - PROTECTIVE 상태에도 도달할 수 없다(OFF → ACTIVATING → OFF만 가능).
   - 다만 EngineMain의 실제 배선을 고정하는 테스트가 없다(M4).
3. **PANIC 순서와 Guard OFF 조건: 대체로 RSK-13·D-023과 맞다.**
   - 순서는 보호 모드 고정 → 미체결 취소 → 서버 측 손절 취소 → 매도 → 체결 확인 → OFF다.
   - 증권사 단계 4개가 이 순서로 모두 DONE일 때만 OFF로 내린다(EngineCommandHandler.java:229-233).
   - 전체 PANIC은 모든 계좌를 먼저 잠근다(EngineCommandHandler.java:166-169).
   - 다만 장 외 시간 처리가 기획서 RSK-13 원문과 충돌하고, 서버 측 손절을 취소한 뒤 매도가 실패하는 경우가 빠져 있다(M3, M8).
4. **비밀번호 없는 내리기와 잠금 중 내리기 허용: 맞다.** 내려가는 전이는 즉시라는 D-023 공통 원칙 2와 같다. 다만 잠긴 동안 PANIC이 15분간 완전히 막히는 점은 고쳐야 한다(M2).
5. **명령 채널 장애가 주문 판정에 영향을 주는지: 주지 않는다.**
   - 주문 판정은 Guard 스냅샷을 잠금 없이 읽는다.
   - 큰 요청(64KB 초과), 느린 연결(5초 읽기 제한, 연결 4개), 처리기 예외(INTERNAL로 응답)는 처리되고 테스트도 있다.
   - 엔진 중복 기동 방지는 소켓 파일에만 기대고 있어 약하다(M6).
   - 상태 조회(STATUS)도 단일 명령 스레드 뒤에 줄을 서기 때문에, 7단계에서 명령이 길어지면 화면 상태가 틀려진다(M1, M9).
   - 엔진이 계속 도는 수명 변경 자체는 문제없다. 채널을 열지 못하면 기동을 거부하는데, 이때 모든 계좌가 OFF라 안전 방향이다.
6. **비밀번호·해시·계좌번호 노출: 응답·로그·예외·감사 로그에서 찾지 못했다.**
   - 엔진은 요청 원문을 기록하지 않고, 감사 대상은 설정에 있는 별칭만 남긴다.
   - 실험실 쪽은 `ApiExceptionHandler`가 예외 메시지를 숨기고, `ConfirmRequest.toString`이 비밀번호를 가린다.
   - 설정 화면에서 해시 키는 편집 불가이고, 저장 경로는 `RuntimeConfigHolder`가 거부한다.
   - 참고 항목 R6에 작은 정리 사항만 있다.
7. **응답 없음 시 재전송 금지: 지켜진다.** ControlClient, RealOpsController, real.js 어디에도 재시도가 없고, 503 응답이 "다시 보내지 말고 상태를 새로 읽으라"고 안내한다. 다만 서버는 클라이언트가 포기한 뒤에도 줄에 남은 명령을 처리하므로, 늦게 실행되는 ACTIVATE가 남는다(M10).
8. **ADR 7장 결정 항목과 Q-016:**
   - 1, 2, 3, 5번은 승인한다.
   - 4번 잠금, 6번 PANIC, 7번 대기 상한은 수정안이 있다(M2, M3, M1).
   - Q-016 추천안은 조건부로 동의한다(R9).

### 중요: 머지 전 수정

**M1. 엔진 응답이 없을 때 모드 띠가 "가상 계좌만"으로 표시된다 (UI-05 인수 조건 위반)**
- 근거:
  - app.js:58: 엔진에 닿지 못하면 실전 계좌 목록이 비어 일반 띠가 나온다.
  - app.js:65-66: 문구가 "실전 엔진이 꺼져 있습니다"다.
  - ControlClient는 소켓 없음, 연결 거부, 15초 타임아웃을 모두 같은 `EngineUnreachableException`으로 던진다(ControlClient.java:241-264).
- 엔진이 살아서 OPEN인데 명령 스레드가 바쁘거나 연결 슬롯이 찬 경우에도 화면이 "가상 계좌만"을 보여 준다. 이는 "실전·모의 상태를 착각할 수 없는 표시"(UI-05)에 어긋난다.
- 수정 제안:
  - `StatusView`에 `engineState` = `NOT_RUNNING`(소켓 파일 없음) / `UNKNOWN`(그 밖의 실패)을 둔다.
  - `UNKNOWN`이면 가상 띠가 아니라 별도 경고 띠 "실전 상태 확인 불가 · 마지막 확인 hh:mm:ss"를 보여 주고, 마지막으로 받은 실전 상태를 유지한다.
  - 띠가 상태 판정과 마지막 상태를 유지하는지 확인하는 테스트를 추가한다.

**M2. 2차 확인이 잠기면 PANIC이 15분간 완전히 막힌다**
- 근거:
  - OperatorAuthenticator.java:62-64: 잠금 중이면 모든 2차 확인을 거절한다.
  - real.js:50, 64, 84: 잠기면 PANIC 버튼이 비활성화된다.
- 급박한 상황에서 비밀번호를 5번 틀리면 매도 수단이 15분 사라진다.
- 수정 제안:
  - PANIC_ACCOUNT·PANIC_ALL은 2차 확인 결과와 상관없이 **1단계(보호 모드 고정)를 먼저 수행**하고, 확인을 통과한 경우에만 2~6단계를 한다.
  - 1단계는 내리기와 같은 하향 전이라, 비밀번호 없이 허용해도 새로 생기는 위험이 없다.
  - 잠긴 동안 화면은 PANIC 버튼을 "보호 모드 고정만 됩니다"라는 안내와 함께 켜 둔다.
  - ADR 7장 4번에 이 내용을 넣어 사용자 결정 대상으로 만든다.
  - 테스트: 잠금 상태와 확인 실패 상태에서 PANIC을 보내면 OPEN이 PROTECTIVE로 내려가고, 증권사 단계는 실행되지 않는다.

**M3. "보호 모드 유지(서버 측 손절 계속)" 문구가 사실과 다를 수 있다**
- 근거: EngineCommandHandler.java:222-224는 증권사 단계 결과와 상관없이 같은 문구를 쓴다.
- 서버 측 손절 취소(CANCEL_SERVER_STOPS)가 DONE이고 매도(SELL)가 실패하면, 서버 측 손절은 이미 없어진 상태다.
- 수정 제안:
  - 문구를 실제 단계 결과에서 만든다. 서버 측 손절 취소가 DONE인데 매도가 미완료면 "서버 측 손절이 취소된 상태입니다. 엔진 손절 감시만 동작합니다"라고 적고 긴급 표시를 한다.
  - `PanicLiquidator` Javadoc 계약에 "서버 측 손절 취소 후 매도가 확정되지 않으면 서버 측 손절을 다시 걸거나 그렇지 못했음을 결과에 FAILED로 남긴다"를 넣는다(M8로 이어짐).

**M4. 활성화 도중 예외가 나면 출발 상태로 돌아가지 않는 경로가 있다**
- 근거: EngineCommandHandler.java:107-127.
  - `steps.perform`은 RuntimeException만 잡는다. Error는 잡지 않는다.
  - `completeStep`은 `GuardClosedException`만 잡는다. 그런데 OPEN으로 올리는 전이에서 감사 기록이 RuntimeException으로 실패하면(RealTradingGuard의 `raise`), 예외가 그대로 처리기 밖으로 나간다. 계좌는 ACTIVATING에 남고, AUTHENTICATED를 통과했다면 주문 세션 허가도 발급 가능하다.
  - 지금은 첫 단계에서 실패하므로 실제 영향은 없지만, 7단계 구현이 이 루프를 그대로 쓴다.
- 수정 제안:
  - `beginActivation` 뒤 전체를 `try { ... } finally { if (state == ACTIVATING) guard.failActivation(...) }`로 감싼다(Throwable까지 포함).
  - 테스트 2건: 감사 기록 실패, 단계 처리기의 Error.
- 같은 묶음으로 EngineMain 배선 고정 테스트도 추가한다. `EngineMain.start`에 유효 해시(하한 반복 횟수로 미리 만든 문자열)와 계좌번호를 넣고, 명령 채널로 ACTIVATE를 보내 OFF와 "AUTHENTICATED 수행 불가"를 확인한다. 지금 테스트는 처리기 단위로 주입한 runner만 본다(EngineCommandHandlerTest.java:115-125). 운영 배선이 `BROKER_NOT_CONNECTED`라는 사실(EngineMain.java:116-117)을 고정하는 테스트가 없다.

**M5. ADR 보안 주장 정정과 남은 위험 명시**
- 근거:
  - ADR 3장 B1의 "실험실이 뚫려도 비밀번호 없이는 올릴 수 없다"는 웹 계층 공격에만 성립한다.
  - 실험실은 secrets.json 전체를 읽는다. SettingsController.java:303은 해시를 들고 있다가 저장할 때 다시 쓴다. 같은 OS 사용자 권한이라 파일에 쓰기도 가능하다.
  - 따라서 실험실 프로세스를 장악하면 (a) 해시를 빼내 오프라인 대입을 하거나, (b) 해시를 바꾼 뒤 엔진 재기동을 기다리거나, (c) 정상 입력 때 평문 비밀번호를 가로챌 수 있다.
- 수정 제안:
  - ADR 4장 위험 표에 "실험실 프로세스 장악 = 2차 확인 무력화 가능, 탐지 통제로 대응"을 적는다.
  - 7단계 완료 조건에 "ACTIVATE 성공, PANIC, 잠금, 확인 실패 시 notification-jar로 긴급 알림(Slack·카카오)"을 넣는다.
  - 비밀번호 최소 길이를 8자에서 12자 이상으로 올리는 것을 권한다. 해시가 실험실 메모리에도 있기 때문이다.
- 기획서 RSK-13 원문과의 충돌도 ADR 7장 질문에 넣는다.
  - RSK-13 원문(autotrade-spec.md:185)은 "장 외 시간에는 미체결 취소와 Guard OFF만 하고 매도는 개장 후 사용자가 다시 실행"이다. 이 구현(D1)은 장 외 시간에 매도가 SKIPPED라 보호 모드를 유지한다.
  - 나는 D1이 더 안전하다고 본다. RSK-13대로 장 외 시간에 Guard OFF를 하면 개장 때 보유분을 지킬 것이 없다.
  - 추천안: 장 외 시간에는 미체결 매수만 취소하고, 서버 측 손절은 유지하며, 보호 모드에 남긴다.
  - 확정 기획서와 다르므로 지침 8장 4번(확정 결정과 충돌)에 해당하는 사용자 결정이다. 승인되면 autotrade-spec-keeper로 RSK-13을 고친다.

### 중요: 7단계 실전 활성화 전 완료 조건 (ADR-006 6장 또는 4장에 명시)

**M6. 엔진 단일 인스턴스 보장이 소켓 파일에만 기대고 있다**
- 근거: ControlServer.java:158-175.
- 실행 중에 `run/engine-control.sock` 파일이 지워지면(사용자가 폴더를 정리하는 등) 두 번째 엔진이 정상 기동한다.
  - 같은 계좌로 Guard 두 벌이 생겨 중복 주문 위험이 생긴다.
  - 화면은 새 엔진(모두 OFF)과 대화하므로, 옛 엔진에 보내야 할 내리기와 PANIC이 엉뚱한 엔진으로 간다.
- 수정 제안:
  - `run/engine.lock`에 `FileChannel.tryLock()`을 걸고 프로세스가 끝날 때까지 쥐고 있는 것을 1차 수단으로 한다. OS가 잠금을 풀어 주므로 남는 파일 문제도 없다.
  - 소켓 검사는 2차 수단으로 남긴다.
  - 테스트: 소켓 파일을 지운 뒤 두 번째 엔진이 거부되는지.

**M7. PANIC이 계좌별로 순차 실행돼 계좌끼리 영향을 준다 (지침 4장 6번)**
- 근거: EngineCommandHandler.java:170-172, 211-217.
- 계좌 A의 증권사가 느리면 계좌 B의 매도가 그만큼 늦어진다. 전체가 단일 명령 스레드와 15초 대기 상한 안에서 돈다.
- 수정 제안:
  - 1단계 잠금은 지금처럼 동기로 먼저 한다.
  - 2~6단계는 계좌별 비동기 작업(계좌별 직렬 큐)으로 넘기고 단계별 시간 상한을 둔다.
  - 즉시 응답 후 화면은 상태를 폴링한다.

**M8. 서버 측 손절 재설정 계약이 없다**
- M3을 `PanicLiquidator` 구현의 완료 기준으로 둔다.
- 장애 테스트 목록에 "서버 측 손절 취소 후 매도 거부, 타임아웃" 시나리오를 넣는다.

**M9. 상태 조회와 하향 명령이 상향 명령 뒤에 줄을 선다**
- 근거:
  - ControlServer.java:118: STATUS를 포함해 모든 명령이 단일 스레드를 쓴다.
  - ADR 4장은 "긴 활성화 중 PANIC 대기"만 적었다.
- 수정 제안:
  - STATUS는 명령 스레드를 거치지 않고 스냅샷을 바로 읽는다(Guard는 이미 스레드 안전하다).
  - DEACTIVATE와 PANIC 1단계는 진행 중인 활성화를 기다리지 않는 경로로 처리한다.
  - 연결 슬롯 4개 중 1개는 하향 명령용으로 예약한다.

**M10. 오래된 ACTIVATE가 늦게 실행될 수 있다**
- 클라이언트가 15초 후 포기해도 서버는 줄에 남은 ACTIVATE를 실행한다.
- 수정 제안: 서버가 요청 수신 시각을 기록하고, 꺼낼 때 클라이언트 대기 상한을 넘었으면 **상향 명령만** 거절한다(INVALID_STATE "요청이 오래돼 실행하지 않음"). 하향 명령과 PANIC은 늦어도 실행한다.

### 참고
- **R1. 2차 확인이 대상에 묶여 있지 않다.** `OperatorAuthenticator.verified`(OperatorAuthenticator.java:32, 92-98)는 계좌나 명령과 무관한 플래그다. PANIC 검증 성공 뒤에도 true로 남는다. 지금은 매번 `verify`가 먼저 초기화하므로 안전하다. Q-016 구현이나 처리기 변경 때 실수가 생기지 않도록 `verify`가 대상 계좌에 묶인 확인을 원자적으로 돌려주게 바꾸는 것을 권한다.
- **R2. 해시 반복 횟수에 상한이 없다.** OperatorPasswordHash.java:157-160에 상한이 없어, 손으로 잘못 넣은 큰 값이면 검증 한 번에 수십 초가 걸리고 명령 스레드가 막힌다. `MAX_ITERATIONS`(예: 10,000,000)를 두는 것을 권한다.
- **R3. 감사 기록 실패가 성공한 명령을 INTERNAL로 바꾼다.** `CommandAuditLog.record`가 실패하면 이미 반영된 명령이 INTERNAL 응답으로 나간다(EngineCommandHandler.java:67). try/catch로 감싸 경고만 남기는 것을 권한다. 예외로 빠진 명령은 감사 기록 자체가 없는 점도 같이 고친다.
- **R4. 읽기 시간 초과 경합.** ControlServer.java:117에서 `deadline.cancel(false)`가 false를 돌려줘도 처리를 계속한다. false면 처리하지 않도록 하면, 응답 없이 실행되는 경우가 줄어든다.
- **R5. 명령 채널 수락 스레드가 죽어도 감지되지 않는다.** acceptLoop(ControlServer.java:79-103)는 IOException 외 예외에서 죽고 감지 수단이 없다. 모든 예외를 잡게 하고, 7단계 감시 프로세스(MON)가 STATUS로 엔진 상태를 확인하게 한다.
- **R6. 요청 문자열에 계좌 값이 그대로 나온다.** `ControlRequest.toString`(ControlRequest.java:47-51)이 account를 그대로 넣는다. 실험실의 형식 검사(`[A-Za-z0-9_-]{1,64}`)는 계좌번호 모양도 통과시킨다. 지금은 아무 곳에서도 이 문자열을 기록하지 않지만, account도 가리는 것을 권한다.
- **R7. 요청 JSON의 중복 키를 허용한다.** ControlCodec.java:312-315에 `STRICT_DUPLICATE_DETECTION`을 켜는 것을 권한다.
- **R8. 소켓 폴더가 심볼릭 링크면 권한 변경이 따라간다.** ControlServer.java:137-148에서 `run`이 심볼릭 링크면 권한 변경이 링크 대상에 적용된다. `NOFOLLOW_LINKS`로 링크를 거부하는 것을 권한다. 같은 OS 사용자 공격 범위라 등급은 참고다.
- **R9. Q-016 추천안(OFF 계좌 PANIC 시 OFF → PROTECTIVE로 올려 매도)에 조건부 동의한다.** 조건은 세 가지다.
  - (a) 매도 전에 인증 → 세션 → 첫 대사(ORD-07 UNKNOWN 확정)를 반드시 거친다. `enterProtective`는 이 단계를 건너뛰므로, 대사 없이 매도하면 미확정 주문과 겹쳐 이중 매도가 날 수 있다.
  - (b) 전체 PANIC은 확인 한 번으로 여러 계좌의 확인이 필요하다. 지금의 "한 번만 생성" 구조로는 안 되므로 R1과 함께 설계한다.
  - (c) 매도가 실패하면 PROTECTIVE로 남는 것을 사용자 결정 문구에 명시한다.
- **R10. CI의 엔진 정리.** 중간 `exit 1` 경로에서 엔진 프로세스가 남는다. 일회용 러너라 실제 영향은 없지만 `trap`으로 정리하는 것을 권한다.

### ADR 7장 항목별 의견
| 항목 | 의견 |
|---|---|
| 1 Unix 도메인 소켓 | 승인. M6 잠금 파일 추가 조건 |
| 2 엔진 검증, PBKDF2 60만 회 | 승인. M5 문구 정정, 비밀번호 12자 이상 권장 |
| 3 확인 문구 = 계좌 별칭 | 승인 |
| 4 5회 실패 15분 잠금, 잠금 중 내리기 허용 | 수정. M2(잠금 중에도 PANIC 1단계 수행) |
| 5 내리기는 비밀번호 없음 | 승인(D-023 원칙 2와 일치) |
| 6 증권사 단계가 모두 끝나야 OFF, 아니면 보호 모드 | 승인. 장 외 시간 충돌(M5)과 M3·M8 조건 |
| 7 대기 상한 15초, 재전송 없음 | 승인. M1, M9, M10 조건 |
| Q-016 | 조건부 동의(R9) |

장애 대응표 14종, 해외 O1~O8, 실전 활성화 전 장애 테스트 8종 조건에는 이 변경이 영향을 주지 않는다. 증권사 경로가 비어 있기 때문이다. 7단계 실전 활성화 게이트는 그대로 유지해야 한다.

**판정: 조건부 승인.** M1~M5를 고치고 M6~M10을 ADR-006에 7단계 완료 조건으로 적으면 승인한다. 실전 경로 PR이므로 머지는 사용자가 직접 한다.

관련 파일:
- /home/claude/autotrade-system/docs/adr/ADR-006-real-control-channel.md
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/command/EngineCommandHandler.java
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/command/OperatorAuthenticator.java
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/command/OperatorPasswordHash.java
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/EngineMain.java
- /home/claude/autotrade-system/autotrade-control/src/main/java/com/autotrade/control/ControlServer.java
- /home/claude/autotrade-system/autotrade-control/src/main/java/com/autotrade/control/ControlClient.java
- /home/claude/autotrade-system/autotrade-ui/src/main/resources/static/js/app.js
- /home/claude/autotrade-system/autotrade-ui/src/main/resources/static/js/pages/real.js
- /home/claude/autotrade-system/docs/spec/autotrade-spec.md (RSK-13, 185행)
