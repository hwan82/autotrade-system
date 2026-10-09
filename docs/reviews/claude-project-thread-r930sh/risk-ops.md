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

## 재검토 2026-10-09

# risk-ops 검토: 실전 운영 화면(ADR-006) 1차 검토 반영본
- 대상: 브랜치 claude/project-thread-r930sh, 커밋 a3f31de / `git diff origin/main...HEAD`(origin/main c50ab6f 기준, 57개 파일). 1차 검토 대상 4ab3553 이후 변경(a3f31de)을 포함해 diff 전체를 다시 봤다
- 점검표: A
- 직접 실행한 것:
  - `./gradlew --offline build --rerun-tasks`: BUILD SUCCESSFUL, 91개 작업 모두 실행. 테스트 359건, 실패 0, 오류 0, 건너뜀 0(app-engine 47, control 18, architecture-test 13, risk 41, config 29, app-lab 97, 나머지 모듈 114). 처음 `./gradlew --offline build`는 캐시로 테스트가 UP-TO-DATE라 다시 강제 실행했다
  - 엔진 기동 점검(CI 단계와 같은 내용을 로컬에서): `:autotrade-app-engine:installDist` 후 짧은 설정 폴더로 엔진 실행. `run` 700, `run/engine-control.sock` 600 확인. 같은 폴더로 두 번째 엔진은 종료 코드 1. 소켓 파일을 지운 뒤 세 번째 엔진도 종료 코드 1(engine.lock). 이때 첫 엔진은 계속 살아 있었다(아래 F2의 근거). SIGTERM 후 소켓 삭제 확인
  - 긴 설정 폴더 경로(약 100바이트 초과)에서는 엔진이 "Unix domain path too long"으로 기동을 거부하는 것을 실제로 확인했다(ADR 2.2와 일치)
  - 비밀값: diff 전체에서 `@Disabled`·`assume*`, 테스트 파일 삭제, 키·계좌번호·토큰·실제 해시 모양 문자열을 grep으로 찾았다. 결과 없음. gitleaks 바이너리가 로컬에 없어 gitleaks는 돌리지 못했다(CI에서 실행)
- 코드는 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` 변경 없음(diff에 risk·domain·broker·sim·strategy·order 경로 없음). EngineMain.java:95-105가 설정 계좌로 Guard를 만들고 모두 OFF. 설정에 올리는 키 없음(autotrade-default.json에 `operator-password-hash`만 추가). 엔진 잠금은 메모리에만 있고 재기동 시 OFF(OperatorAuthenticator.java:17). EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다, #설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다, EngineCommandHandlerTest#기동_직후_상태는_모든_계좌_OFF이고_비밀값을_담지_않는다 통과 |
| A2 | 통과 | ArchitectureTest.java:136 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 변경 없음(diff는 149행 주석 한 줄만). 새 규칙 161 `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, 169·175 명령 채널 서버·클라이언트 위치 규칙과 위반 픽스처(ForgedConfirmation 2건, MisplacedControlChannel) 통과. `RealOrderGateways.open`을 부르는 운영 코드 없음. 연동 전 활성화는 AUTHENTICATED에서 실패(ActivationStepRunner.java:169-170, EngineMain.java:116-117), EngineMainTest#실제_배선으로_활성화하면_증권사_연동_전이라_첫_단계에서_OFF로_돌아간다 통과 |
| A3 | 통과 | 이 변경에 주문 전송 코드가 없다. PanicLiquidator·ActivationStepRunner는 `AccountRef`(계좌의 broker 포함)만 받고 증권사를 고르는 인자가 없다(PanicLiquidator.java:141). 다른 증권사로 넘기는 경로 없음 |
| A4 | 통과 | 주문 타임아웃 경로는 바뀌지 않았다. 명령 채널에도 같은 원칙이 적용됐다: ControlClient·RealOpsController·real.js에 재전송 없음(RealOpsController.java:87-90은 "다시 보내지 말고 상태를 새로 읽으라"로 503), 오래 기다린 ACTIVATE는 STALE로 버림(ControlServer.java:223-226). ControlChannelTest#오래_기다린_활성화는_버리고_PANIC은_기다렸어도_실행한다, RealOpsControllerTest#엔진이_꺼져_있으면_상태는_연결_안_됨으로_보이고_명령은_503이다 통과 |
| A5 | 해당 없음 | 주문을 만드는 코드가 없다. 멱등 키 생성·검증 코드(domain) 변경 없음. 7단계 PanicLiquidator 매도 구현 때 다시 본다 |
| A6 | 통과 | 이번 변경으로 생긴 자동 주문 경로가 없다(PANIC 증권사 단계는 NOT_AVAILABLE, PanicLiquidator.java:144-148). 블랙리스트·수동 보유 제외는 PanicLiquidator 계약(127행)과 ADR 2.5 4단계에 명시돼 7단계 구현 조건으로 남았다 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 변경 없음. PANIC 1단계가 계좌별 Guard 전이만 하고(EngineCommandHandler.java:228-240) 다른 계좌 상태를 바꾸지 않음. 계좌별 순차 PANIC이 서로 기다리는 문제는 ADR 4장·6장에 7단계 완료 조건으로 기록됨. 단 대기열 상한이 PANIC도 거절할 수 있는 점은 F1로 따로 적는다 |
| A8 | 통과 | 실험실은 클라이언트만, 엔진은 서버만(ArchUnit 169·175), 게이트웨이·감시는 control 모듈 금지(build.gradle.kts processIsolation). 서버 스레드는 모두 데몬, 명령 채널 장애는 Guard 판정에 영향 없음(Guard 변경 없음). 입력 크기 64KB·읽기 5초·연결 8개·대기열 2개 제한과 테스트 있음(ControlChannelTest 18건). Windows·macOS 소켓 권한은 ADR 5장에 "검증하지 못한 것"으로 명시됨 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. PANIC은 증권사 단계가 모두 DONE일 때만 OFF, 아니면 보호 모드 유지(EngineCommandHandler.java:261-266). 서버 측 손절 취소 후 매도 미완료는 긴급 표시(274-284), PanicLiquidator 계약에 재설정 의무(133-134). EngineCommandHandlerTest#서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다, #매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다 통과 |
| A10 | 통과 | 해시는 🔒 비밀값 경로에만(ConfigLoaderTest 비밀값 경로 목록, RuntimeConfigHolderTest 해시는 secrets.json으로만, SecretTest toString 마스킹). 테스트 픽스처는 `PLACEHOLDER-*`와 `<ACCOUNT_NUMBER>`만 쓰고 해시는 실행 중 생성(EngineMainTest.java:127-136). 요청 toString이 비밀번호·계좌 칸을 가림(ControlChannelTest#요청_문자열에는_비밀번호와_계좌_칸이_보이지_않는다), 감사 로그는 설정 별칭만(EngineCommandHandler.java:323-328), 실험실 로그는 명령 이름과 결과 코드만(RealOpsController.java:88·92, RealOpsControllerTest#활성화는_확인_문구와_비밀번호를_엔진에_넘기고_응답과_로그에는_남기지_않는다). 수동 grep으로 diff에 실제 값 없음. gitleaks는 로컬 미실행 |
| A11 | 통과 | diff에 `@Disabled`·assume 없음, 삭제된 테스트 파일 없음. 바뀐 기존 테스트 줄은 생성자 인자 추가와 단언 강화(RuntimeConfigHolderTest의 `doesNotContain(FAKE_HASH)`, `contains(FAKE_HASH)` 추가)뿐이다. 새 동작에 실패 경우 테스트가 있다(틀린 확인, 잠금, 미설정, Error·감사 로그 실패, BUSY, STALE, 잠금 파일, 심볼릭 링크) |
| A12 | 통과 | 장애 대응표 #8(엔진 크래시 후 재기동): 잠금은 프로세스 종료 시 OS가 풀고 남은 소켓은 지우고 시작(ControlChannelTest#죽은_엔진의_소켓은_지우고_시작한다) → 재기동을 막지 않는다. #9(실험실·UI 크래시): 실험실은 클라이언트일 뿐이라 엔진 영향 없음. 장애 테스트 8종의 전제(주문 경로, 큐, 대사)를 바꾸는 코드 없음. 해외 O1~O8 영향 없음(시장별 코드 없음) |
| A13 | 해당 없음 | 시장별 분기·시간·금액 계산이 없다. 시간은 주입 Clock만(OperatorAuthenticator 잠금), 대기열 경과는 `System.nanoTime` 상대값(ControlServer.java:219-223)으로 ArchUnit `NO_SYSTEM_TIME` 통과 |
| A14 | 통과 | ADR-006 7장에 사용자 결정 8건, Q-016·Q-017이 결정 로그 미확정 표에 추가됨(docs/decisions/README.md). 기획서 RSK-13(autotrade-spec.md:185)과의 차이 두 가지(1단계를 2차 확인 전에 함, 장 외 시간 Guard 유지)는 ADR 7장 4번과 Q-017에 명시돼 있다. 머지로 결정되면 autotrade-spec-keeper로 RSK-13 문구를 고쳐야 한다(F6) |

### 1차 검토 지적 처리 확인
| 항목 | 결과 | 근거 |
|---|---|---|
| M1 응답 없음을 가상으로 표시 | 반영 | RealOpsController.java:51-61 `UP/NOT_RUNNING/UNKNOWN`+asOf, real.js:54-67 마지막 상태 유지·경고, RealOpsControllerTest#엔진이_답하지_않으면_상태를_알_수_없음으로_보여_준다. 남은 틈은 F2 |
| M2 잠기면 PANIC 불가 | 반영 | EngineCommandHandler.java:176-183, 189-200. 테스트 3건(확인 실패·잠금·미설정) 통과 |
| M3 서버 측 손절 문구 | 반영 | EngineCommandHandler.java:274-284, PanicStepResult urgent, 테스트 2건 |
| M4 ACTIVATING 잔류, 배선 테스트 | 반영 | EngineCommandHandler.java:120-127(finally), 감사 로그 실패 74-80. EngineMainTest 배선 테스트 |
| M5 ADR 보안 주장·알림·RSK-13 | 반영 | ADR 3장 B1, 4장 위험 표, 비밀번호 15자, Q-017 |
| M6 단일 인스턴스 | 반영 | ControlServer.java:83-98, 로컬 실행으로 확인 |
| M7 계좌별 순차 PANIC | 7단계 조건으로 기록 | ADR 4장, 6장 |
| M8 서버 측 손절 재설정 계약 | 반영(계약)·7단계 테스트 조건 | PanicLiquidator.java:130-136, ADR 6장 |
| M9 상태·내리기 대기 | 부분 반영 | STATUS·DEACTIVATE 직행(ControlServer.java:60, 212-214). PANIC 1단계는 여전히 대기열 뒤(ADR 4장 7단계 조건). 하향 명령용 연결 슬롯 예약은 하지 않음(F5) |
| M10 늦은 ACTIVATE | 반영 | ControlServer.java:219-226, 테스트 |
| R1~R10 | 반영 | R1 OperatorAuthenticator.java:97-109, R2 MAX_ITERATIONS, R3 감사 로그 try/catch, R4 serve 198행, R5 acceptLoop가 RuntimeException 포함, R6 toString 마스킹, R7 엄격 파싱, R8 심볼릭 링크 거부, R9 ADR 7장 Q-016 조건, R10 CI trap |

## 점검표 밖 발견
- **F1 (중요, 수정 필요) PANIC이 BUSY로 거절될 수 있다.**
  - 근거: ControlServer.java:215-218. 대기열 세마포어 2개는 실행 중인 명령도 센다(MAX_QUEUED=2, 54행). ACTIVATE 하나가 실행 중이고 하나가 기다리면 PANIC_ACCOUNT·PANIC_ALL은 `BUSY`로 즉시 거절되고 1단계(보호 모드 고정)도 일어나지 않는다. ControlChannelTest#대기열이_차면_BUSY로_거절한다는 반대 방향(PANIC이 차 있을 때 ACTIVATE 거절)만 확인한다.
  - 1차 수정 전에는 PANIC이 기다렸다가 실행됐는데, 대기열 상한을 넣으면서 거절로 바뀌었다. ADR 2.2는 "PANIC은 기다렸어도 실행한다"고 적어 이 경우를 다루지 않는다.
  - 지금은 증권사 연동 전이라 OPEN 계좌가 생길 수 없어 실제 피해는 없다. 하지만 ACTIVATE 한 건은 PBKDF2 60만 회 검증 때문에 짧지 않고, 7단계에서는 더 길어진다.
  - 수정 제안: PANIC은 BUSY로 거절하지 않는다(올리는 명령과 별도 상한을 두거나, 대기열에 넣기 전에 1단계 보호 모드 고정을 연결 스레드에서 바로 한다. 1단계는 DEACTIVATE와 같은 내려가는 전이라 스레드 안전 조건이 같다). 테스트: ACTIVATE 2건으로 대기열을 채운 상태에서 PANIC을 보내면 거절되지 않고 OPEN 계좌가 PROTECTIVE로 내려간다. 코드로 고치지 않을 경우 최소한 ADR 2.2·4장에 "대기열이 차면 PANIC도 BUSY, 그때는 내리기를 쓴다"를 적고 화면 BUSY 안내에 내리기를 명시하며, 7단계 완료 조건에 넣는다.
- **F2 (중요, 수정 필요) 엔진이 살아 있는데 소켓 파일이 없으면 화면이 "가상 계좌만"으로 보인다.**
  - 근거: ControlClient.java:50-52는 소켓 파일이 없으면 바로 `NOT_RUNNING`. real.js:47-53은 이때 버튼 없이 "실행 중이 아닙니다"를 보이고, 모드 띠는 NOT_RUNNING일 때만 가상으로 바꾼다(ADR 2.7). 실행 중에 `run/engine-control.sock`이 지워지면 engine.lock 덕분에 두 번째 엔진은 뜨지 않지만 첫 엔진은 계속 돈다(로컬 실행으로 확인: 소켓 삭제 후 첫 엔진 살아 있음). 이 상태에서 화면은 실전 계좌가 열려 있어도 "가상"으로 보이고, 내리기·PANIC을 보낼 길이 없다. ADR 2.3의 "NOT_RUNNING: 엔진 프로세스 없음"이 이 경우에는 틀리다. 1차 M1(UI-05 착각 방지)의 남은 틈이다.
  - 지금은 열 수 있는 계좌가 없어 실제 피해는 없다.
  - 수정 제안(택1 이상): (a) 엔진이 주기적으로 소켓 파일을 확인하고 없으면 다시 만들며 ERROR 로그를 남긴다. (b) 실험실은 소켓이 없더라도 `run/engine.lock`이 다른 프로세스에 잡혀 있으면 `UNKNOWN`으로 본다. 단, 실험실이 배타 잠금을 잡아 보면 그 순간 엔진 기동을 막을 수 있으므로 잠금을 잡지 않는 확인 방법을 쓴다. 최소한 ADR 2.3·4장 문구를 고치고 7단계 완료 조건에 넣는다.
- **F3 (참고) DEACTIVATE가 동시에 진행 중인 ACTIVATE의 검증을 지울 수 있다.** EngineCommandHandler.java:71-73은 DEACTIVATE(연결 스레드에서 바로 실행)에서도 `discardVerification()`을 부른다. ACTIVATE가 `confirm()` 직후 `confirmationFor()` 전이면 활성화가 INVALID_STATE로 실패한다. 안전한 쪽(열리지 않음)이지만 실패 횟수는 이미 초기화됐고 사용자에게는 이유가 불분명하다. `verify`가 일회용 확인 객체를 돌려주게 하거나, 검증을 쓰는 명령(ACTIVATE·PANIC)에서만 버리게 하는 것을 권한다.
- **F4 (참고) 화면 문구 불일치.** real.js:69 "비밀번호가 설정되지 않아 실전 활성화와 PANIC SELL을 받을 수 없습니다"는 실제 동작(PANIC 1단계는 됨, 82행·191행 안내)과 다르다. 급박할 때 PANIC을 누르지 않게 만들 수 있으니 "PANIC SELL은 보호 모드 고정까지만 됩니다"로 맞추는 것을 권한다.
- **F5 (참고) 하향 명령용 연결 슬롯 예약 없음(1차 M9 일부).** ControlServer.java:154-158. 같은 OS 사용자 프로그램이 요청 없는 연결 8개를 5초씩 계속 열면 DEACTIVATE·PANIC 연결도 닫힌다. 공격 범위는 같은 사용자라 등급은 참고. 7단계 감시 프로세스(MON) 점검 항목에 넣는 것을 권한다.
- **F6 (참고) 머지 후 기획서 갱신.** RSK-13 원문 "둘 다 2차 확인"과 "장 외 시간 Guard OFF"는 ADR 7장 4번과 Q-017 결정 결과에 따라 autotrade-spec-keeper로 고쳐야 한다.

## 판정
수정 필요(F1, F2). 점검표 A는 실패 0, 확인 못 함 0이다(gitleaks는 CI에 맡기고 수동 grep으로 확인). 1차 지적 M1~M6, M8, M10, R1~R10은 반영을 확인했고 M7·M9 일부는 ADR에 7단계 조건으로 남았다. F1·F2는 지금 열 수 있는 계좌가 없어 현재 계좌 피해는 없지만, F1은 이번 수정으로 생긴 PANIC 경로 회귀이고 F2는 1차 M1이 막으려던 "실전을 가상으로 착각"의 남은 경로다. 둘 다 코드로 고치거나, 최소한 ADR 2.2·2.3·4장 정정과 7단계 완료 조건 등록(F1은 화면 BUSY 안내 포함)을 하면 승인한다. 실전 경로 PR이므로 승인 후에도 머지는 사용자가 직접 한다(지침 10장).

## 재검토 2 2026-10-09

# risk-ops 검토: 실전 운영 화면(ADR-006) 재검토 반영본
- 대상: 브랜치 claude/project-thread-r930sh, 커밋 c7e158b / `git diff origin/main...HEAD`(origin/main 2dcea43, merge-base 같음, 57개 파일). 직전 재검토(a3f31de) 이후 변경은 c7e158b(ControlServer·ControlClient·EngineCommandHandler·real.js·ADR-001·ADR-006·테스트 3개)와 main 반영 4f36e2a(실험 문서·ScreeningCriteriaTest, 이미 main에 있어 branch diff에는 안 잡힘)다. diff 전체와 영향받는 실전 경로(EngineMain, EngineCommandHandler, OperatorAuthenticator, RealTradingGuard 호출부, ControlServer/Client, RealOpsController, real.js·app.js)를 다시 봤다
- 점검표: A
- 직접 실행한 것(같은 폴더의 다른 검토자 빌드와 겹치지 않게 HEAD를 scratchpad에 clone해서 실행):
  - `./gradlew --offline build --rerun-tasks`: 첫 실행은 `:autotrade-config:compileTestJava`에서 "package com.autotrade.domain.account does not exist" 28건으로 실패했다. 바로 다시 돌린 `--rerun-tasks --continue` 1회, `--rerun-tasks` 1회, `git clean -xfd` 후 `--rerun-tasks` 1회는 모두 BUILD SUCCESSFUL(91개 작업 모두 실행)이었고 재현되지 않았다. autotrade-config는 `api(project(":autotrade-domain"))`로 의존이 선언돼 있어 코드 결함으로 보기 어렵다. 같은 Gradle 사용자 홈을 쓰는 동시 빌드와의 간섭으로 본다 `[추론]`
  - 마지막 성공 빌드의 테스트 결과(XML 집계): 377건, 실패 0, 오류 0, 건너뜀 0. app-engine 48, control 19, architecture-test 13, risk 41, config 29, app-lab 97, sim 75, domain 27, strategy 25, gateway 1, watchdog 1, broker-api 1
  - 실제 프로세스 점검: `:autotrade-app-engine:installDist`로 엔진을 짧은 설정 폴더에서 띄우고, 별도 JVM에서 `ControlClient`로 상태를 읽었다. (1) 정상: OK, `run/engine.lock` 내용 = 엔진 PID(19923), 폴더 700·소켓 600. (2) 실행 중 소켓 파일 삭제: `UNKNOWN "실전 엔진 프로세스는 살아 있지만 명령 채널에 닿지 못했습니다"`. (3) SIGTERM 후: `NOT_RUNNING`(잠금 파일 PID는 남지만 프로세스 없음). 직전 재검토 F2는 리눅스에서 프로세스 간에도 해결됐다
  - gitleaks 8.21.2: `gitleaks git --redact --log-opts=origin/main..HEAD`(5 commits) no leaks found, 저장소 전 이력(54 commits) no leaks found
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - diff에 추가된 `@Disabled`·`assume*` 없음(검토 원문 인용 줄만 걸림), 삭제된 파일 없음(`git diff --diff-filter=D` 결과 없음)
- 코드는 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` 변경 없음. EngineMain.java:97이 설정 계좌로 Guard를 만들고 모두 OFF로 시작. 설정에 올리는 키 없음(autotrade-default.json은 `operator-password-hash` 자리만). 잠금 파일 PID 기록(ControlServer.java:103-111)은 Guard 상태와 무관. EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다, #설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다, EngineCommandHandlerTest#기동_직후_상태는_모든_계좌_OFF이고_비밀값을_담지_않는다 통과 |
| A2 | 통과 | ArchitectureTest.java:136 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`, 149 `ONLY_COMMAND_CHANNEL_RAISES_GUARD`, 161 `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, 169·175 명령 채널 위치 규칙과 위반 픽스처(283-295행) 통과, c7e158b는 ArchitectureTest를 바꾸지 않음. `RealOrderGateways.open` 운영 호출 없음. EngineMain.java:117 `BROKER_NOT_CONNECTED` 배선, EngineMainTest#실제_배선으로_활성화하면_증권사_연동_전이라_첫_단계에서_OFF로_돌아간다 통과 |
| A3 | 통과 | 주문 전송 코드 없음. PanicLiquidator·ActivationStepRunner는 `AccountRef`만 받고 증권사 선택 인자 없음. 증권사 전환 경로 없음 |
| A4 | 통과 | 주문 타임아웃 경로 변경 없음. 명령 채널은 재전송 없음(ControlClient.java:64-80, RealOpsController 503 안내). ACTIVATE는 오래 기다리면 STALE(ControlServer.java:239-244). PANIC은 이제 대기열 상한 없이 기다렸다가 실행(228-232). ControlChannelTest#오래_기다린_활성화는_버리고_PANIC은_기다렸어도_실행한다, #대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다 통과 |
| A5 | 해당 없음 | 주문을 만드는 코드 없음. 멱등 키 코드(domain) 변경 없음. 7단계 PanicLiquidator 매도 구현 때 다시 본다 |
| A6 | 통과 | 새 자동 주문 경로 없음(PANIC 증권사 단계는 `BROKER_NOT_CONNECTED` 구현으로 수행 불가). 블랙리스트·수동 보유 제외는 PanicLiquidator 계약과 ADR 2.5에 7단계 조건으로 남음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 변경 없음. PANIC 1단계는 계좌별 Guard 전이만(EngineCommandHandler.java:230-243). 계좌별 순차 PANIC은 ADR 4·6장 7단계 조건. 직전 F1(PANIC이 BUSY로 거절)은 ControlServer.java:228-232로 해결, 테스트로 확인 |
| A8 | 통과 | 실험실은 클라이언트만, 엔진은 서버만(ArchUnit 169·175), 게이트웨이·감시 런타임에 control 금지(verifyProcessIsolation, build 통과). 실험실의 PID 확인(ControlClient.java:88-101)은 실험실 쪽 읽기만 하고 엔진에 영향 없음. 명령 채널 장애는 Guard 판정에 영향 없음. 연결 8개·ACTIVATE 대기열 2개·입력 64KB·읽기 5초 제한과 테스트(ControlChannelTest 19건) 있음. PANIC 대기 연결이 연결 자리를 차지할 수 있는 점은 G2(참고) |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. PANIC은 증권사 단계 4개가 모두 DONE일 때만 OFF(EngineCommandHandler.java:262-268, 287-291), 서버 측 손절 취소 후 매도 미확정은 긴급 표시(275-285). EngineCommandHandlerTest#서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다, #매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다 통과 |
| A10 | 통과 | gitleaks 브랜치 5커밋·전 이력 54커밋 no leaks. 잠금 파일에는 PID만(ControlServer.java:105-106). 해시는 secrets.json 경로에만, 요청 toString 마스킹(ControlChannelTest#요청_문자열에는_비밀번호와_계좌_칸이_보이지_않는다), 실험실 로그는 명령 이름과 결과 코드만(RealOpsControllerTest#활성화는_확인_문구와_비밀번호를_엔진에_넘기고_응답과_로그에는_남기지_않는다) |
| A11 | 통과 | 삭제·비활성화된 테스트 없음. c7e158b가 바꾼 기존 테스트는 `대기열이_차면_BUSY로_거절한다` → `대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다`(동작 변경에 맞춰 BUSY 단언 유지 + PANIC 성공·ACTIVATE 2건 실행 단언 추가, 약화 아님), 죽은 소켓 테스트에 "잠금 파일 PID 죽음" 전제 추가, RealOpsControllerTest @AfterEach 잠금 파일 정리. 새 동작 테스트: ControlChannelTest#엔진이_살아_있는데_소켓만_없어지면_실행_중이_아님이_아니라_알_수_없음이다, EngineCommandHandlerTest#활성화_검증_직후_들어온_내리기는_그_검증을_지우지_않는다 |
| A12 | 통과 | 대응표 #8(엔진 크래시 후 재기동): 남은 잠금 파일 PID가 죽어 있으면 NOT_RUNNING, 재기동 시 잠금 재획득·PID 덮어쓰기(ControlServer.java:104 truncate) → 재기동을 막지 않음(실제 프로세스로 확인). #9(실험실·UI 크래시): 실험실은 클라이언트일 뿐. 장애 테스트 8종의 전제(주문 경로, 큐, 대사) 변경 없음. 해외 O1~O8 영향 없음 |
| A13 | 해당 없음 | 시장별 분기·시간·금액 계산 없음. 대기 경과는 `System.nanoTime` 상대값(ControlServer.java:237-240)으로 ArchUnit `NO_SYSTEM_TIME`(105행) 통과 |
| A14 | 통과 | ADR-006 2.2·2.4·5·6장이 c7e158b 동작(PANIC은 BUSY 없음, 잠금 파일 PID, DEACTIVATE는 검증을 지우지 않음)과 일치. 7단계 완료 조건에 소켓 재생성·하향 명령 연결 자리 예약·RSK-13 문구 갱신 추가(ADR-006 6장 표). Q-016·Q-017 결정 로그에 있음. 문구 불일치 두 곳은 G3(참고) |

### 직전 재검토 지적 처리 확인
| 항목 | 결과 | 근거 |
|---|---|---|
| F1 PANIC이 BUSY로 거절 | 반영 | ControlServer.java:228-232에서 PANIC은 세마포어를 거치지 않고 명령 스레드에 넣는다. ControlChannelTest#대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다(ACTIVATE 2건으로 대기열을 채운 상태에서 ACTIVATE는 BUSY, PANIC_ALL은 ok) 통과 |
| F2 엔진이 살아 있는데 소켓 없음 → 가상 | 리눅스에서 반영, Windows 미검증 | ControlServer.java:103-111 PID 기록, ControlClient.java:88-101 PID 생존 확인. 실제 프로세스로 UP/UNKNOWN/NOT_RUNNING 확인. Windows에서는 G1 |
| F3 DEACTIVATE가 진행 중 검증을 지움 | 반영 | EngineCommandHandler.java:71-76. DEACTIVATE는 검증을 만들지 않고, ACTIVATE·PANIC은 모두 단일 명령 스레드에서 돌고 끝에 검증을 버리므로 남는 검증이 생기지 않는다. 테스트 통과 |
| F4 비밀번호 미설정 문구 | 반영 | real.js:69 |
| F5 하향 명령 연결 자리 | 7단계 조건으로 기록 | ADR-006 6장 표 |
| F6 RSK-13 갱신 | 머지 직후 과제로 기록 | ADR-006 6장 표(pm-lead, autotrade-spec-keeper) |

## 점검표 밖 발견
- **G1 (중요, 수정 필요) Windows에서는 F2 수정이 동작하지 않고 "가상"으로 떨어질 수 있다.**
  - 근거: ControlClient.java:90-99는 잠금 파일을 읽다가 `IOException`이 나면 무조건 `NOT_RUNNING`으로 본다. 엔진은 같은 파일을 `FileChannel.tryLock()`(전체 구간 배타 잠금)으로 쥐고 있다(ControlServer.java:87-90). Windows의 파일 잠금은 강제 잠금이라, 엔진이 살아 있는 동안 다른 프로세스가 잠긴 구간을 읽으면 잠금 위반 `IOException`이 난다 `[추론: FileLock Javadoc "advisory or mandatory is platform-dependent", Windows LockFileEx 동작. 리눅스에서만 실행해 직접 확인 못 함]`. 그러면 F2가 막으려던 경우(엔진은 살아 있는데 소켓만 없음)에 화면이 다시 "실험실 · 가상 계좌만"이 되고 버튼이 사라진다. ADR-006 5장 "검증하지 못한 것"에는 소켓 권한과 경로 길이만 있고 이 경우가 없다. 실사용 PC가 Windows일 수 있다(ADR-006 154·190행)
  - 판단 기준: 확인할 수 없는 경우의 기본값이 위험 쪽(NOT_RUNNING)이다. 직전 F2의 수정 방향("확인 못 하면 가상으로 보이지 않게")과 맞지 않는다
  - 수정 제안: `NoSuchFileException`(잠금 파일 없음)만 `NOT_RUNNING`으로 두고, 그 밖의 `IOException`(읽기 실패·잠금 위반)은 `UNKNOWN`으로 본다. 빈 파일·숫자 아님은 지금처럼 `NOT_RUNNING`이어도 된다(기동 직후 PID 기록 전 순간이라 모든 계좌가 OFF). 테스트: 잠금 파일을 읽을 수 없게(권한 000 등) 만든 상태에서 소켓이 없으면 `UNKNOWN`. 코드로 고치지 않으면 최소한 ADR-006 5장 "검증하지 못한 것"과 6장 표(실사용 PC 확정 후, 6단계 전)에 "Windows에서 잠금 파일 PID 읽기"를 넣는다
  - 지금은 열 수 있는 계좌가 없어 현재 계좌 피해는 없다
- **G2 (참고) PANIC 대기 연결이 연결 자리 8개를 채울 수 있다.** ControlServer.java:228-232는 PANIC에 시간 상한 없이 `get()`으로 기다린다. 긴 ACTIVATE 뒤에 PANIC이 여러 건(여러 탭·여러 클라이언트) 쌓이면 연결 자리를 쥐고 있어 STATUS·DEACTIVATE 연결이 즉시 닫힌다(167-170행). 화면은 보내는 동안 버튼을 막아(real.js:155·220) 한 탭에서는 생기지 않는다. 직전 F5(하향 명령 연결 자리 예약, 7단계 조건)에 "PANIC 대기 수 상한 또는 같은 계좌 중복 PANIC 합치기"를 같이 적는 것을 권한다. 7단계에서 PANIC 매도가 붙으면 같은 계좌 PANIC이 연달아 실행돼 매도 단계가 두 번 도는 경우도 이때 함께 정한다(PanicLiquidator 계약에 재실행 시 보유 재조회 후 매도 명시)
- **G3 (참고) 문구 불일치 두 곳.** (a) ADR-006 2.3(78행)의 `UNKNOWN` 정의가 아직 "연결은 됐지만 2초 안에 답이 없거나 끊김"뿐이다. 2.2(48행)에 추가된 "소켓은 없지만 엔진 PID가 살아 있음"을 2.3에도 넣는다. (b) real.js:66 UNKNOWN 안내 "PANIC SELL과 내리기는 보낼 수 있지만"은 소켓이 없어진 경우에는 명령이 엔진에 닿지 않는다. 메시지(ControlClient.java:95)가 이 경우를 구분하므로, 그때는 "엔진 터미널에서 확인하세요(명령 채널 소켓 없음)"로 안내하는 것을 권한다. 소켓 재생성(7단계 조건)이 들어가면 해소된다
- **G4 (참고) 빌드 첫 실행 실패 1회.** 위 "직접 실행한 것" 참고. 3회 연속 재현되지 않았고 의존 선언도 맞아 이 PR의 결함으로 보지 않는다. CI 결과로 한 번 더 확인하면 된다

## 판정
수정 필요(G1). 점검표 A는 실패 0, 확인 못 함 0, 해당 없음 2(A5, A13)다. 직전 지적 F1·F3·F4는 반영을 확인했고, F2는 리눅스에서 실제 프로세스로 해결을 확인했다. F5·F6은 ADR-006 6장에 7단계 조건·머지 직후 과제로 남았다. G1은 F2 수정이 Windows에서 위험 쪽 기본값(NOT_RUNNING → "가상" 표시)으로 떨어지는 문제로, 현재 계좌 피해는 없지만 F2와 같은 UI-05 착각 방지 항목이다. 한 줄 수정(그 밖의 IOException → UNKNOWN)과 테스트 1건, 또는 최소한 ADR-006 5장·6장에 Windows 미검증과 6단계 전 확인 조건을 적으면 승인한다. G2~G4는 참고다. 실전 경로 PR이므로 승인 후에도 머지는 사용자가 직접 한다(지침 10장).

## 재검토 3 2026-10-09

# risk-ops 검토: 실전 운영 화면(ADR-006) 재검토 2 반영본
- 대상: 브랜치 claude/project-thread-r930sh, 커밋 c4e1a18 / `git diff origin/main...HEAD`(origin/main 2dcea43 = merge-base, 9커밋, 57개 파일, +4577 −34). 직전 재검토(c7e158b) 이후 코드 변경은 c4e1a18(ControlClient·ControlChannelTest·real.js·ADR-006)이고 51387cb는 검토 원문 보존뿐이다. diff 전체와 영향받는 실전 경로(EngineMain, EngineCommandHandler, OperatorAuthenticator, RealTradingGuard 호출부, ControlServer/Client, RealOpsController, real.js·app.js)를 다시 봤다
- 점검표: A
- 직접 실행한 것(다른 작업과 겹치지 않게 HEAD를 scratchpad에 새로 clone해서 실행):
  - `./gradlew build --rerun-tasks`(`git clean -xfd` 후): BUILD SUCCESSFUL, 91개 작업 모두 실행. 테스트 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0. app-engine 48, control 20, architecture-test 13, risk 41, config 29, app-lab 97, sim 75, domain 27, strategy 25, gateway 1, watchdog 1, broker-api 1. 새 테스트 `ControlChannelTest#잠금_파일을_읽지_못하거나_내용이_PID가_아니면_알_수_없음이다` 통과
  - 참고: 그 전 1회는 내가 `-Dorg.gradle.project.buildDir=x`를 잘못 넣어 빌드 폴더 이름이 바뀌었고, 그 때문에 ArchUnit `DO_NOT_INCLUDE_TESTS`가 테스트 픽스처를 운영 코드로 읽어 ArchitectureTest 5건이 실패했다. 검토자 실행 오류이며 위의 정상 실행으로 대체한다
  - 실제 프로세스 점검(`:autotrade-app-engine:installDist`, 소켓 경로 길이 때문에 짧은 임시 설정 폴더 사용): 엔진 1 기동 → `run` 700, 소켓 600, `engine.lock`=PID. 이어서 **`run` 폴더의 소켓과 `engine.lock`을 지우고 같은 설정 폴더로 엔진 2를 띄우자 엔진 2가 정상 기동했고 엔진 1도 계속 살아 있었다**(두 PID 모두 `kill -0` 성공, 새 `engine.lock`에는 엔진 2 PID). 아래 H1의 근거. 점검 뒤 두 엔진을 종료하고 임시 폴더를 지웠다
  - gitleaks 8.21.2: `gitleaks git --redact --log-opts=origin/main..HEAD` 7 commits no leaks found, 저장소 전 이력 58 commits no leaks found
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - diff에 추가된 `@Disabled`·`@Ignore`·`assume*` 없음(검토 원문 인용 줄만 걸림), 삭제된 파일 없음(`git diff --diff-filter=D` 결과 없음)
- 코드는 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` 변경 없음. EngineMain.java:85·95-97이 설정 계좌로 Guard를 만들고 모두 OFF로 시작. 설정에 올리는 키 없음(autotrade-default.json은 `operator-password-hash` 자리만). c4e1a18은 실험실 쪽 판정만 바꾸고 Guard와 무관. EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다, #설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다, EngineCommandHandlerTest#기동_직후_상태는_모든_계좌_OFF이고_비밀값을_담지_않는다 통과. 엔진이 둘 떠도(H1) 각 엔진은 OFF로 시작한다 |
| A2 | 통과 | ArchitectureTest의 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`, `ONLY_COMMAND_CHANNEL_RAISES_GUARD`, `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, 명령 채널 위치 규칙과 위반 픽스처 13건 통과. c4e1a18은 ArchitectureTest를 바꾸지 않음. `RealOrderGateways.open` 운영 호출 없음. 연동 전 활성화는 AUTHENTICATED에서 실패(EngineMain `BROKER_NOT_CONNECTED` 배선), EngineMainTest#실제_배선으로_활성화하면_증권사_연동_전이라_첫_단계에서_OFF로_돌아간다 통과 |
| A3 | 통과 | 주문 전송 코드 없음. PanicLiquidator·ActivationStepRunner는 `AccountRef`만 받고 증권사 선택 인자 없음. 증권사 전환 경로 없음 |
| A4 | 통과 | 주문 타임아웃 경로 변경 없음. 명령 채널은 재전송 없음(ControlClient.java:64-80, RealOpsController 503 안내). ACTIVATE는 오래 기다리면 STALE(ControlServer.java:233-244), PANIC은 BUSY 없이 실행(228-232). ControlChannelTest#대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다(222행), #오래_기다린_활성화는_버리고_PANIC은_기다렸어도_실행한다(258행) 통과 |
| A5 | 해당 없음 | 주문을 만드는 코드 없음. 멱등 키 코드(domain) 변경 없음. 7단계 PanicLiquidator 매도 구현 때 다시 본다 |
| A6 | 통과 | 새 자동 주문 경로 없음(PANIC 증권사 단계는 `BROKER_NOT_CONNECTED` 구현으로 수행 불가). 블랙리스트·수동 보유 제외는 PanicLiquidator 계약과 ADR-006 2.5에 7단계 조건으로 남음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 변경 없음. PANIC 1단계는 계좌별 Guard 전이만(EngineCommandHandler.java:242 부근). 계좌별 순차 PANIC, 기다리는 PANIC 시간 상한은 ADR-006 6장 7단계 조건 |
| A8 | 통과 | 실험실은 클라이언트만, 엔진은 서버만(ArchUnit), 게이트웨이·감시 런타임에 control 금지(verifyProcessIsolation, build 통과). c4e1a18의 잠금 파일 읽기(ControlClient.java:90-107)는 실험실 쪽 읽기 전용이라 엔진에 영향 없음. 리눅스에서는 엔진의 `tryLock`이 권고 잠금이라 실험실 읽기가 엔진 잠금을 방해하지 않는다(실제 프로세스로 UP 확인). 명령 채널 장애는 Guard 판정에 영향 없음. 엔진 단일 인스턴스의 남은 틈은 H1(점검표 밖)으로 따로 적는다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. PANIC은 증권사 단계 4개가 모두 DONE일 때만 OFF(EngineCommandHandler.java:262-268, 278-281), 서버 측 손절 취소 후 매도 미확정은 긴급 표시(291-301). EngineCommandHandlerTest#서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다, #매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다 통과 |
| A10 | 통과 | gitleaks 브랜치 7커밋·전 이력 58커밋 no leaks. 새 예외 메시지(ControlClient.java:97·99-100·103-104)는 고정 문구만이고 경로·PID·계좌를 넣지 않는다. 새 테스트는 빈 문자열과 폴더만 쓴다. 요청 toString 마스킹(ControlChannelTest#요청_문자열에는_비밀번호와_계좌_칸이_보이지_않는다), 실험실 로그는 명령 이름과 결과 코드만(RealOpsControllerTest#활성화는_확인_문구와_비밀번호를_엔진에_넘기고_응답과_로그에는_남기지_않는다) |
| A11 | 통과 | 삭제·비활성화된 테스트 없음. c4e1a18이 바꾼 기존 테스트 줄은 죽은 프로세스를 `true` 대신 현재 JVM 실행 파일 `-version`으로 만드는 것(ControlChannelTest.java:309-310, OS 무관하게 하려는 변경, 단언 그대로)뿐이다. 새 동작(빈 파일·읽기 오류 → UNKNOWN)에 테스트 추가(321행). 단, 이 테스트는 읽기 오류를 "폴더"로 흉내 내므로 Windows 강제 잠금 자체는 검증하지 않는다(ADR-006 5장 "검증하지 못한 것"에 명시됨) |
| A12 | 통과 | 대응표 #8(엔진 크래시 후 재기동): 남은 잠금 파일의 PID가 죽어 있으면 NOT_RUNNING, 재기동 시 잠금 재획득·PID 덮어쓰기 → 재기동을 막지 않음. 잠금 파일은 있지만 비었거나 읽지 못하면 이제 UNKNOWN인데, 이는 화면 표시만 보수적으로 바꾸고 재기동을 막지 않는다. #9(실험실·UI 크래시): 실험실은 클라이언트일 뿐. 장애 테스트 8종의 전제(주문 경로, 큐, 대사) 변경 없음. 해외 O1~O8 영향 없음 |
| A13 | 해당 없음 | 시장별 분기·시간·금액 계산 없음. 대기 경과는 `System.nanoTime` 상대값으로 ArchUnit `NO_SYSTEM_TIME` 통과 |
| A14 | 통과 | ADR-006 2.2(48행)·2.3(78행)의 판정 규칙("잠금 파일이 없을 때만 NOT_RUNNING, 판단할 수 없으면 언제나 UNKNOWN")이 ControlClient.java:90-107과 일치. 5장에 Windows 잠금 파일 읽기 미검증 추가, 6장 표에 기다리는 PANIC 시간 상한 추가. Q-016·Q-017 결정 로그에 있음. 다만 2.2(44행) "소켓 파일을 누가 지워도 두 번째 엔진이 뜨지 않는다"는 맞지만 "엔진은 설정 폴더당 하나"는 H1 때문에 무조건 참은 아니다(H1에서 정정 요구) |

### 직전 재검토 지적 처리 확인
| 항목 | 결과 | 근거 |
|---|---|---|
| G1 Windows에서 잠금 파일 읽기 실패 → NOT_RUNNING | 반영 | ControlClient.java:94-101: `NoSuchFileException`만 NOT_RUNNING, 그 밖의 `IOException`·`NumberFormatException`은 UNKNOWN. ControlChannelTest#잠금_파일을_읽지_못하거나_내용이_PID가_아니면_알_수_없음이다 통과. Windows 실기 확인은 ADR-006 5장 "검증하지 못한 것"과 6장(실사용 PC 확정 후) 조건으로 남음 |
| G2 PANIC 대기 연결이 연결 자리를 채움 | 7단계 조건으로 기록 | ADR-006 6장 표 "기다리는 PANIC의 시간 상한" |
| G3 문구 불일치 | (a) 반영, (b) 부분 반영 | (a) ADR-006 2.3 UNKNOWN 정의 갱신. (b) real.js:57·66 제목 문구는 바뀌었지만 67행 "PANIC SELL과 내리기는 보낼 수 있지만"은 그대로라, 소켓이 없어진 경우에는 여전히 명령이 엔진에 닿지 않는다. 참고 등급이라 승인 조건으로 삼지 않는다. 7단계 소켓 재생성으로 해소 |
| G4 빌드 첫 실행 실패 | 해당 없음 | 이번 정상 실행에서 재현 없음(위 참고의 실패는 검토자 실행 오류) |

## 점검표 밖 발견
- **H1 (중요, 수정 필요) `run` 폴더(또는 `engine.lock`)를 지우면 같은 설정 폴더로 엔진이 둘 뜬다. 1차 M6이 막으려던 경우 그대로다.**
  - 근거: 위 "직접 실행한 것"의 실제 프로세스 점검. ControlServer.java:87은 `run/engine.lock`을 `CREATE`로 열고 `tryLock`한다. 리눅스(POSIX)에서는 잠금이 걸린 파일도 지울 수 있고, 엔진 1은 지워진 inode의 잠금을 계속 쥐고 있으므로 엔진 2가 같은 경로에 새 파일을 만들어 잠금을 얻는다. 1차 M6의 시나리오가 "사용자가 폴더를 정리하는 등"이었고, 잠금 파일은 소켓과 같은 `run` 폴더에 있어 폴더 정리 한 번으로 둘 다 사라진다
  - 영향: (1) 같은 실전 계좌의 Guard가 두 벌 생긴다(7단계에서는 같은 계좌 주문 세션 두 개, 계좌별 단일 직렬 큐 붕괴). (2) 실험실은 엔진 2(모두 OFF, `UP`)와만 대화하므로, 엔진 1에서 계좌가 열려 있어도 화면은 "모두 OFF"로 보이고 내리기·PANIC은 엔진 2로 간다(UI-05 착각 방지와 D-023 원칙 2 위반). (3) 엔진 2를 띄우지 않더라도, 실행 중에 `run` 폴더만 지워지면 c4e1a18의 새 규칙에 따라 잠금 파일 없음 → `NOT_RUNNING` → 화면 "가상"이 된다(G1에서 고친 경로의 남은 틈). (4) 한 엔진이 종료하며 `Files.deleteIfExists(socket)`(ControlServer.java:295·345)로 같은 경로의 다른 엔진 소켓을 지울 수 있다
  - 지금은 증권사 연동 전이라 열 수 있는 계좌가 없어 현재 계좌 피해는 없다. 하지만 ADR-006 2.2의 "엔진은 설정 폴더당 하나"가 사실과 다르고, M6을 "반영"으로 판정한 재검토 1의 근거(소켓만 지운 시험)가 이 경우를 다루지 않았다
  - 수정 제안(택1 이상): (a) 엔진이 주기적으로(예: 2초) 잠금 파일 경로의 `fileKey`(inode)와 소켓 파일이 자기 것인지 확인하고, 바뀌었거나 없으면 ERROR 로그와 함께 다시 만들거나(잠금 재획득 실패 시 다른 엔진이 있다는 뜻이므로 모든 계좌를 내리기 방향으로 전이) 알린다. 7단계 조건 "엔진이 지워진 소켓을 스스로 다시 만들기"에 잠금 파일을 합친다. (b) 잠금 파일을 지우기 어려운 위치·이름으로 분리(예: 설정 폴더 바로 아래 `engine.lock`, `run`과 분리)해 폴더 정리 한 번에 사라지지 않게 한다(완전한 해결은 아님). (c) 엔진 기동 시 기존 소켓 경로에 연결을 시도하는 것 외에, 같은 설정 폴더를 쓰는 다른 엔진 프로세스를 PID 목록으로 확인한다(보조 수단). 테스트: 잠금 파일(또는 `run` 폴더)을 지운 뒤 두 번째 엔진이 거부되거나, 첫 엔진이 이를 감지해 ERROR와 함께 복구하는지
  - 코드로 고치지 않을 경우 최소 조건: ADR-006 2.2 "엔진은 설정 폴더당 하나" 문구를 "`run/engine.lock`이 지워지지 않는 한"으로 정정하고, 4장 위험 표와 6장 7단계 완료 조건(실전 활성화 전)에 "잠금 파일 삭제 시 단일 인스턴스 보장 상실, 감지·복구"를 넣으며, 장애 테스트 목록에 이 시나리오를 추가한다
- **H2 (참고) ControlClient 클래스 Javadoc(ControlClient.java:26-29)의 NOT_RUNNING/UNKNOWN 정의가 c4e1a18 규칙(잠금 파일을 읽지 못하면 UNKNOWN)으로 갱신되지 않았다.** 메서드 Javadoc(84-89행)과 ADR은 맞다. 문서 정합성만의 문제다
- **H3 (참고) 새 테스트의 Windows 이식성.** ControlChannelTest.java:309의 `ProcessHandle.current().info().command().orElseThrow()`는 일부 환경에서 비어 있을 수 있다. 안전과 무관하고 Windows에서 실행할 때 확인하면 된다

## 판정
수정 필요(H1). 점검표 A는 실패 0, 확인 못 함 0, 해당 없음 2(A5, A13)다. 직전 지적 G1은 코드와 테스트로 반영을 확인했고(Windows 실기는 ADR 5·6장 조건), G2는 7단계 조건으로 기록됐으며, G3은 참고 수준의 문구 하나가 남았다. H1은 실제 프로세스로 재현한 단일 인스턴스 보장의 틈으로, 1차 M6과 같은 위험(같은 계좌 Guard 두 벌, 화면이 엉뚱한 엔진과 대화)이다. 현재 계좌 피해는 없으므로, 코드 수정과 테스트 1건 또는 최소한 ADR-006 2.2 문구 정정·4장 위험 표·6장 7단계 완료 조건과 장애 테스트 목록 등록을 하면 승인한다. H2·H3은 참고다. 실전 경로 PR이므로 승인 후에도 머지는 사용자가 직접 한다(지침 10장).

## 재검토 4 2026-10-09

# risk-ops 검토: 실전 운영 화면(ADR-006) 재검토 3 반영본
- 대상: 브랜치 claude/project-thread-r930sh, 커밋 b554eba / `git diff origin/main...HEAD`(origin/main 2dcea43 = merge-base, 11커밋, 57개 파일, +4632 −34). 직전 재검토(c4e1a18) 이후 변경은 8017d53(검토 원문 보존뿐)과 b554eba(ControlClient.java Javadoc, real.js 안내 문구 1줄, ADR-006 2.2·4장·6장, config-keys.md 안내 1줄)다. b554eba에는 실행 코드 변경이 없다(ControlClient는 클래스 Javadoc만, real.js는 문자열만). diff 전체와 영향받는 실전 경로(EngineMain, EngineCommandHandler, OperatorAuthenticator, RealTradingGuard 호출부, ControlServer/Client, RealOpsController, real.js·app.js, ArchitectureTest)를 다시 봤다
- 점검표: A
- 직접 실행한 것(다른 작업과 겹치지 않게 HEAD를 scratchpad의 새 폴더 `rr4-1791508918`에 clone해서 실행):
  - `./gradlew --offline build --rerun-tasks`: BUILD SUCCESSFUL, 91개 작업 모두 실행(91 executed). 테스트 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0. app-engine 48, control 20, architecture-test 13, risk 41, config 29, app-lab 97, sim 75, domain 27, strategy 25, gateway 1, watchdog 1, broker-api 1. 직전 재검토와 건수가 같다(b554eba는 테스트를 바꾸지 않음)
  - gitleaks 8.21.2(scratchpad/gl/gitleaks): `gitleaks git --redact --log-opts=origin/main..HEAD` 9 commits no leaks found, 저장소 전 이력 60 commits no leaks found
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - diff에 추가된 `@Disabled`·`@Ignore`·`assume*` 없음(검토 원문 인용 줄만 걸림), 삭제된 파일 없음(`git diff --diff-filter=D` 결과 없음)
  - 실제 프로세스 점검은 이번에 다시 하지 않았다. ControlServer·EngineMain은 c4e1a18 이후 바뀌지 않았으므로(`git diff --stat 8017d53 HEAD`에 없음) 재검토 3의 H1 재현 결과가 그대로 유효하고, ADR-006에 새로 적힌 한계 서술(44·156행)이 그 재현 결과와 일치한다
- 코드는 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` 변경 없음. EngineMain.java:85·95-98이 설정 계좌로 Guard를 만들고 모두 OFF로 시작. 설정에 올리는 키 없음(autotrade-default.json은 `operator-password-hash` 자리만). b554eba는 Guard와 무관. EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다, #설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다, EngineCommandHandlerTest#기동_직후_상태는_모든_계좌_OFF이고_비밀값을_담지_않는다 통과 |
| A2 | 통과 | ArchitectureTest.java:136 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`, 149 `ONLY_COMMAND_CHANNEL_RAISES_GUARD`, 161 `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, 169 `ONLY_ENGINE_OPENS_CONTROL_SERVER`, 175 `ONLY_LAB_SENDS_CONTROL_COMMANDS` 포함 13건 통과. b554eba는 ArchitectureTest를 바꾸지 않음. `RealOrderGateways.open` 운영 호출 없음. EngineMain.java:117 `BROKER_NOT_CONNECTED` 배선, EngineMainTest#실제_배선으로_활성화하면_증권사_연동_전이라_첫_단계에서_OFF로_돌아간다 통과 |
| A3 | 통과 | 주문 전송 코드 없음. PanicLiquidator·ActivationStepRunner는 `AccountRef`만 받고 증권사 선택 인자 없음. 증권사 전환 경로 없음 |
| A4 | 통과 | 주문 타임아웃 경로 변경 없음. 명령 채널은 재전송 없음(ControlClient.java:64-80). ACTIVATE는 오래 기다리면 STALE(ControlServer.java:233-244), PANIC은 BUSY 없이 실행(228-232). ControlChannelTest#대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다, #오래_기다린_활성화는_버리고_PANIC은_기다렸어도_실행한다 통과. real.js:67 새 문구는 "닿지 못하면 반영되지 않는다, 엔진 응답으로 확인"이라 재전송을 유도하지 않는다 |
| A5 | 해당 없음 | 주문을 만드는 코드 없음. 멱등 키 코드(domain) 변경 없음. 7단계 PanicLiquidator 매도 구현 때 다시 본다 |
| A6 | 통과 | 새 자동 주문 경로 없음(PANIC 증권사 단계는 `BROKER_NOT_CONNECTED` 구현으로 수행 불가). 블랙리스트·수동 보유 제외는 PanicLiquidator 계약과 ADR-006 2.5·4장에 7단계 조건으로 남음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷 코드 변경 없음. PANIC 1단계는 계좌별 Guard 전이만. 두 번째 엔진에 의한 계좌별 단일 직렬 큐 붕괴 가능성(H1)은 ADR-006 4장(156행) 위험 표와 6장(193행) 7단계 완료 조건으로 등록됨. 지금은 주문 경로가 없어 실제 영향 없음 |
| A8 | 통과 | 실험실은 클라이언트만, 엔진은 서버만(ArchUnit 169·175), 게이트웨이·감시 런타임에 control 금지(verifyProcessIsolation, build 통과). ControlClient의 잠금 파일 읽기(ControlClient.java:90-104)는 실험실 쪽 읽기 전용. 명령 채널 장애는 Guard 판정에 영향 없음. b554eba는 실행 코드를 바꾸지 않음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. PANIC은 증권사 단계 4개가 모두 DONE일 때만 OFF, 서버 측 손절 취소 후 매도 미확정은 긴급 표시. EngineCommandHandlerTest#서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다, #매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다 통과 |
| A10 | 통과 | gitleaks 브랜치 9커밋·전 이력 60커밋 no leaks. b554eba의 새 문구에 경로·PID·계좌·비밀값 없음. 요청 toString 마스킹(ControlChannelTest#요청_문자열에는_비밀번호와_계좌_칸이_보이지_않는다), 실험실 로그는 명령 이름과 결과 코드만(RealOpsControllerTest#활성화는_확인_문구와_비밀번호를_엔진에_넘기고_응답과_로그에는_남기지_않는다) 통과 |
| A11 | 통과 | 삭제·비활성화된 테스트 없음. b554eba는 테스트 파일을 바꾸지 않음. 브랜치 전체에서 바뀐 기존 테스트는 재검토 1~3에서 확인한 대로 단언 유지·강화뿐. 새 동작의 실패 경우 테스트(틀린 확인, 잠금, 미설정, BUSY, STALE, 잠금 파일 읽기 실패 → UNKNOWN 등) 있음 |
| A12 | 통과 | 대응표 #8(엔진 크래시 후 재기동), #9(실험실·UI 크래시) 전제 유지(재검토 3과 같음, 실행 코드 변경 없음). broker-and-fault-design.md는 이 브랜치에서 바뀌지 않았고, 장애 테스트 8종의 전제(주문 경로, 큐, 대사)를 깨는 변경 없음. 새로 드러난 "run 폴더 삭제 → 두 번째 엔진" 시나리오는 ADR-006 6장(194행)에 장애 테스트 후보로 올리고 broker-and-fault-design.md 7장 반영은 사용자 결정으로 남겼다. 장애 테스트 8종 구성 변경은 지침 8장 2번(실전 영향)이라 이 처리가 맞다. 해외 O1~O8 영향 없음 |
| A13 | 해당 없음 | 시장별 분기·시간·금액 계산 없음. 대기 경과는 `System.nanoTime` 상대값으로 ArchUnit `NO_SYSTEM_TIME`(105행) 통과 |
| A14 | 통과 | ADR-006 2.2(44행)가 "엔진은 설정 폴더당 하나(잠금 파일이 남아 있는 동안)"로 정정되고 한계를 명시, 2.2(48행)·2.3의 판정 규칙이 ControlClient.java:90-104 및 고친 클래스 Javadoc(26-31행)과 일치. 4장 위험 표(156행), 6장 7단계 완료 조건(193행)·장애 테스트 후보(194행) 추가. config-keys.md:11에 "엔진 실행 중 run 폴더를 지우지 말 것" 안내. Q-016·Q-017 결정 로그(docs/decisions/README.md:48-49)에 있음. 기획서 RSK-13 문구 갱신은 6장 표에 머지 직후 과제로 남음 |

### 직전 재검토 지적 처리 확인
| 항목 | 결과 | 근거 |
|---|---|---|
| H1 run 폴더·engine.lock 삭제 시 엔진 두 개 | 최소 조건 충족(문서화), 코드 수정은 7단계 완료 조건 | 재검토 3에서 "코드로 고치지 않을 경우 최소 조건"으로 적은 4가지를 확인했다. (1) ADR-006 2.2 문구 정정(44행), (2) 4장 위험 표(156행, 영향 4가지 모두 서술), (3) 6장 7단계 완료 조건(193행, 소켓·잠금 파일 자기 확인·재생성·긴급 알림·지울 수 없는 단일 실행 보장), (4) 장애 테스트 목록: ADR-006 6장 194행에 후보로 등록, broker-and-fault-design.md 7장 반영은 사용자 결정. config-keys.md 안내 추가는 덤. 지금은 열 수 있는 계좌가 없어 현재 계좌 피해 없음 |
| H2 ControlClient 클래스 Javadoc | 반영 | ControlClient.java:26-31이 "잠금 파일이 없거나 PID 프로세스 없음 → NOT_RUNNING, 그 밖의 모든 경우 → UNKNOWN"으로 메서드 동작(94-104행)과 일치 |
| H3 테스트 Windows 이식성 | 변경 없음(참고) | Windows 실행 때 확인. 안전과 무관 |
| G3(b) real.js UNKNOWN 안내 | 반영 | real.js:67 "엔진에 닿지 못하면 반영되지 않고 … 급하면 실전 엔진 터미널과 증권사 화면을 직접 확인"으로 소켓 없음 경우에 오해 소지 없음 |

## 점검표 밖 발견
- **I1 (참고) 사용자 QA 요청에 H1 관련 사용자 결정을 함께 넣을 것.** ADR-006 6장 194행은 "broker-and-fault-design.md 7장 목록 반영은 사용자 결정"이라고만 적고 결정 로그에 Q 번호가 없다. 실전 경로 PR이라 어차피 사용자에게 QA 요청이 가므로, 그 문구에 "장애 테스트 목록에 'run 폴더 삭제 → 두 번째 엔진 차단' 추가 여부(risk-ops 추천: 추가, 7단계 실전 활성화 전 합격 조건)"를 넣거나 Q 번호로 등록하면 잊히지 않는다. 승인 조건으로 삼지 않는다
- **I2 (참고) 7단계 완료 조건이 쌓였다.** ADR-006 6장에 risk-ops 소관 7단계 완료 조건이 소켓·잠금 자기 확인, 하향 명령 연결 자리, 기다리는 PANIC 시간 상한, PANIC 매도의 블랙리스트·장 외 처리, 감시 프로세스 긴급 경로 등으로 늘었다. 7단계 착수 시 이 표를 그대로 실전 활성화 전 점검 목록(장애 테스트 8종 합격과 함께)으로 옮겨야 한다. 지금 할 일은 없다

## 판정
승인. 점검표 A는 실패 0, 확인 못 함 0, 해당 없음 2(A5, A13)다. 직전 지적 H1은 재검토 3에서 정한 최소 조건(ADR-006 2.2 정정·4장 위험 표·6장 7단계 완료 조건·장애 테스트 후보 등록)을 모두 충족했고, H2와 G3(b)는 반영을 확인했다. b554eba에는 실행 코드 변경이 없고 전체 빌드(378건, 실패 0)·gitleaks·Python 경계 검사가 통과했다. 남은 위험(H1 코드 수정, Windows 실기 확인, PANIC 매도 구현)은 모두 ADR-006 6장에 7단계 실전 활성화 전 조건으로 묶여 있고, 지금은 증권사 주문 연동이 없어 열 수 있는 실전 계좌가 없다. 실전 경로(RealTradingGuard 제어, 장애 대응) PR이므로 이 승인 후에도 사용자가 직접 확인하고 머지한다(지침 10장·11장). I1·I2는 참고다.
