# ADR-002 RealTradingGuard 골격과 실전 계좌 설정 구조

- 상태: 채택 (PR #9 머지, 2026-10-08 사용자 결정. 이전 상태: 제안 (risk-ops)). 실전 안전장치라 **사용자가 직접 확인하고 머지**한다(지침 10장, D-018). 7장 결정의 결정 로그 번호: [D-078](../decisions/README.md)(2026-10-14 부여, 1단계 QA C5 ②)
- 날짜: 2026-10-08
- 단계: 로드맵 1단계 후속(ADR-001 5장 "이 PR에 넣지 않은 것" 첫 항목). 7단계 실전 연동의 바닥
- 기획서: v12 RSK-01, CFG-02, CFG-04, TST-03. 보강 기준은 PR #8의 기획서 v13·D-023(사용자 결정, 아래 "근거" 참고)
- 근거 결정: D-010(주문은 계좌의 증권사로만), D-013(프로세스 4개), D-016(Guard 기본 OFF·재기동 OFF·계좌별), D-023 질문 1(OFF 시 보호 모드)·공통 원칙 1~3
- 확정하는 것: ADR-001 2.6.1 실전 계좌 키 **제안**을 수정해 확정(2.4절)
- 영향 역할: architect(설정 계약, ArchUnit), ui-dev(활성화 2차 확인 화면), sim-engine(가상 계좌는 Guard를 쓰지 않음)

## 1. 배경

[사실] 1단계 뼈대(PR #7)는 실전 주문 경로를 비워 두고 `OrderGateway`·`BrokerPlugin` Javadoc에 "엔진은 RealTradingGuard를 거친 출구만 쓴다, 연결은 risk-ops 작업"이라고 남겼다(`autotrade-domain/.../OrderGateway.java`, `autotrade-broker-api/.../BrokerPlugin.java`).

[사실] 사용자 결정 D-023(PR #8, 2026-10-08 "추천안대로 해줘")이 RSK-01을 보강했다. Guard OFF 계좌는 주문용 세션·토큰을 만들지 않고, 활성화는 인증 → 주문 세션 → 첫 대사 → OPEN 순서, OFF 전환 시 보호 모드, 재기동 시에는 보호 모드로도 자동 진입하지 않는다.

이 ADR은 그 규칙을 **코드 구조로 막는 최소 골격**을 정한다. 증권사 연동, 활성화 명령 채널(UI), 2차 확인 비밀번호 검증, 영속 감사 로그, 서킷브레이커, 블랙리스트, PANIC SELL은 범위 밖이다(6장).

## 2. 결정

### 2.1 상태와 전이 (`autotrade-risk`, `com.autotrade.risk.guard`)

```
            beginActivation(2차 확인)            AUTHENTICATED → ORDER_SESSION → FIRST_RECONCILIATION
   OFF ─────────────────────────▶ ACTIVATING ───────────────────────────────────────────▶ OPEN
    ▲  ▲                              │ failActivation / deactivate: 출발 상태로              │ deactivate (자동·수동)
    │  │ holdingsCleared / shutdown   ▼                                                      ▼
    │  └──────────────────────── PROTECTIVE ◀─────────────────────────────────────────────┘
    └── enterProtective(2차 확인, 재기동 후 보유가 있을 때)
    shutdown: 어떤 상태에서든 OFF (PANIC SELL 마지막 단계, 킬스위치)
```

| 상태 | 신규 진입(ENTRY) | 청산·손절(EXIT, STOP_LOSS) | 취소 | 주문 세션 허가 |
|---|---|---|---|---|
| OFF | 차단 | 차단 | 차단 | 발급 안 함 |
| ACTIVATING (OFF에서 출발) | 차단 | 차단 | 허용 | AUTHENTICATED 통과 후 |
| ACTIVATING (PROTECTIVE에서 출발) | 차단 | 허용 | 허용 | AUTHENTICATED 통과 후 |
| OPEN | 허용 | 허용 | 허용 | 발급 |
| PROTECTIVE (보호 모드) | 차단 | 허용 | 허용 | 발급 |

- 올라가는 전이(OFF·PROTECTIVE → ACTIVATING, OFF → PROTECTIVE)는 그 계좌의 `SecondConfirmation`이 있어야 한다. 확인은 계좌가 일치하고, 60초 안이며(`CONFIRMATION_TTL`, `[가정]`), 그 계좌에서 쓴 적 없는 것만 받는다. ACTIVATING → OPEN은 같은 확인의 연속이며 세 단계를 순서대로 통과해야 한다.
- 내려가는 전이(보호 모드, 출발 상태로 복귀, OFF)는 확인 없이 즉시 일어난다. **자동으로 올라가는 경로는 없다**(D-023 공통 원칙 2).
- 감사 로그: 올라가는 전이는 기록이 성공해야 일어나고, 내려가는 전이는 기록 실패와 상관없이 먼저 일어난다(안전 쪽 전이를 저장소 장애가 막지 않게).
- 판정은 `RiskDecision`(규칙 ID `REAL_TRADING_GUARD`)으로 돌려줘 실전 검증 체인의 맨 앞에 다른 규칙과 같은 방식으로 놓인다(D-023 공통 원칙 3).
- 계좌별 독립 상태. 전이는 계좌별 잠금으로 직렬화하고, 계좌 상태(상태, 활성화 출발 상태, 세대)는 불변 스냅샷 하나를 volatile로 바꿔 끼운다. 주문마다 지나는 판정·취소 허용·허가 유효성 검사는 잠금 없이 한 번 읽고 끝나므로, 감사 로그 저장이 느려도(7단계 DB) 손절이 기다리지 않는다. 내려가는 전이의 감사 기록은 잠금 밖에서 한다. 한 계좌 전이는 다른 계좌에 영향이 없다(지침 4장 6항).
- `OrderIntent.isProtective()`는 허용 목록(`EXIT`, `STOP_LOSS`)으로 바꿨다. 나중에 진입성 의도를 추가해도 보호 모드에서 기본으로 막힌다.

### 2.2 "OFF 계좌는 주문 세션을 만들지 않는다"를 구조로 강제

```
EngineMain ─▶ RealTradingGuard(설정의 실전 계좌, 전부 OFF)
                   │ issueSessionPermit(계좌)   ← OFF면 GuardClosedException
                   ▼
RealOrderGateways.open(guard, plugin, account)   ← 유일한 출구 생성 지점 (ArchUnit)
   1) plugin.brokerId() == account.broker()  (D-010)
   2) permit = guard.issueSessionPermit(...)   ← 플러그인 호출 전에 막힘
   3) raw = plugin.orderGateway(account)       ← 이때 비로소 주문 세션·토큰
   4) return new GuardedOrderGateway(guard, permit, raw)
```

- `OrderSessionPermit`은 발급 당시 세대(epoch)에 묶인다. 계좌가 OFF로 내려가면 세대가 바뀌어 그전 허가와 그 허가로 만든 출구는 **다시 열어도 살아나지 않는다**. 새로 열면 새 출구를 받아야 한다.
- `GuardedOrderGateway`는 호출마다 허가 유효성과 Guard 판정을 다시 본다. 막힌 주문은 증권사로 나가지 않고 `REJECTED`(사유 접두 `REAL_TRADING_GUARD`)로 돌아온다. 막힌 취소는 원주문 상태를 모르므로 `UNKNOWN`(호출자는 조회부터, 지침 4장 2항), 막힌 조회는 `Indeterminate`(재발주 금지).
- ArchUnit `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`: `RealOrderGateways` 외에는 `BrokerPlugin`을 구현·상속한 어떤 타입의 `orderGateway`도 호출하거나 메서드 참조로 넘길 수 없다. 위반 픽스처(`archfixture/BypassRealTradingGuard`)의 네 가지 우회(인터페이스·구체 타입·하위 인터페이스 호출, 메서드 참조)를 모두 잡는지 테스트한다.
- ArchUnit `ONLY_COMMAND_CHANNEL_RAISES_GUARD`: Guard를 올리는 조작(`SecondConfirmation` 생성, `beginActivation`, `enterProtective`, `completeStep`)은 7단계 명령 채널 패키지(`com.autotrade.app.engine.command`, 아직 없음)에서만 호출하거나 메서드·생성자 참조로 넘길 수 있다. 지금은 운영 코드 어디서도 실전을 열 수 없다. 위반 픽스처 6건(호출 2, 참조 4)을 잡는지 테스트한다.

### 2.3 "재기동 시 OFF"를 구조로 강제 (CFG-02)

- `RealTradingGuard`의 공개 생성자는 (계좌 목록, Clock, 감사 로그)뿐이고 상태를 받는 생성 경로가 없다. 테스트가 생성자 시그니처와 정적 팩토리 부재를 고정한다(인자를 추가하면 실패).
- 상태는 메모리에만 있고 저장하지 않는다. 재기동 = 새 Guard = 모든 계좌 OFF.
- 설정에는 활성화 키가 없다. `enabled`, `guard` 같은 키를 어느 파일에 넣어도 모르는 키라서 **그 파일 전체를 쓰지 않고** 경고를 남긴다(기존 로더 동작, 테스트 5종). 그 결과 실전 계좌 목록도 비어 열 계좌가 없다.
- 환경 변수로는 설정 폴더 위치만 바꿀 수 있고 값은 읽지 않는다(ADR-001 2.6).

### 2.4 실전 계좌 설정 키 (CFG-04, ADR-001 2.6.1 제안 수정 확정)

```
runtime-config.json:  { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
secrets.json:         { "real-trading": { "account-numbers": { "KIS-US-1": "<ACCOUNT_NUMBER>" } } }
```

| 키 | 위치 | 규칙 |
|---|---|---|
| `real-trading.accounts[]` | runtime-config | `id`(영문·숫자·`_`·`-` 1~64자, 중복 불가), `broker`(KIS·KIWOOM·TOSS), `market`(KRX·US). 이 셋 외 키는 파일 거부 |
| `real-trading.account-numbers.<id>` 🔒 | secrets | `accounts`에 있는 id만 쓴다. 목록에 없는 항목은 **그 항목만 버리고** 건수만 경고한다(키도 값도 남기지 않음: 별칭 자리에 계좌번호를 잘못 적었을 수 있음). 파일 전체를 거부하지 않는 이유: runtime-config.json 하나가 깨지거나 계좌를 지웠을 때 secrets.json의 증권사 키까지 잃어 시세 게이트웨이가 멈추면 안 된다(D-013). 값이 없는 계좌는 활성화 불가 경고 |

- 실시간 변경 불가(`RuntimeConfigHolder.update`가 거부). 계좌 추가·교체는 CFG-05 절차(Guard OFF, 보유 0, 미체결 0)를 구현하는 작업에서 연다.
- **ADR-001 제안과 다른 점**: 제안은 secrets에 `real-trading.accounts.<id>.account-number`(객체)를 두었다. runtime-config의 `accounts`는 배열이라 로더의 병합 규칙(객체는 재귀 병합, 배열은 통째 교체)상 secrets의 객체가 배열을 덮어써 계좌 목록이 깨진다. 그래서 별도 키 `account-numbers`(별칭 → 계좌번호 맵)로 바꿨다.
- 이를 위해 `SecretPaths`가 `Map<String, Secret>` 필드를 와일드카드 경로(`real-trading/account-numbers/*`)로 인식하게 했다. 목록 안 비밀값은 여전히 허용하지 않는다.

### 2.5 위치

| 코드 | 모듈 | 이유 |
|---|---|---|
| `RealTradingGuard`, `GuardState`, `ActivationStep`, `SecondConfirmation`, `OrderSessionPermit`, `GuardedOrderGateway`, `GuardAuditLog`, `GuardTransition` | autotrade-risk (`risk.guard`) | 순수 Java, domain만 의존. 실험실·게이트웨이 런타임에는 실리지만 실험실은 `BrokerPlugin` 주문 출구를 열 수 없다(ArchUnit) |
| `RealOrderGateways`, `LoggingGuardAuditLog` | autotrade-app-engine (`app.engine.order`) | 플러그인(broker-api)과 Guard(risk)를 함께 아는 곳은 실전 엔진뿐 |
| 실전 계좌 키 | autotrade-config | 4개 프로세스 공통 설정 체계(ADR-001 2.6) |

## 3. 대안 검토

**A. Guard 상태를 어디에 두나**

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A1. 메모리만, 생성 시 항상 OFF | 재기동 OFF가 구조로 보장. 파일 손상·편집으로 열릴 길이 없음 | 재기동 직후 보유 계좌는 사람이 다시 골라야 함 | **채택**(D-016, D-023 "재기동 시 자동 보호 모드도 없음"과 일치) |
| A2. 마지막 상태를 저장하고 기동 시 OFF로 덮어씀 | UI가 "직전에 열려 있었음"을 보여 줄 수 있음 | 덮어쓰기 코드 한 줄이 빠지면 실전이 켜진 채 기동. 지키는 장치가 코드 리뷰뿐 | 버림. 직전 상태 표시는 감사 로그 조회로 대신 |

**B. OFF 계좌의 주문 세션 차단 방식**

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| B1. 허가 토큰(epoch) + 유일한 출구 팩토리 + ArchUnit | 플러그인 호출 전에 막힘, 꺼졌다 켜져도 옛 출구 재사용 불가, 우회 코드는 빌드 실패 | 클래스 몇 개 추가 | **채택** |
| B2. 플러그인이 Guard 상태를 조회 | 클래스가 적음 | 플러그인 3개가 각자 맞게 구현해야 함, 플러그인이 risk에 의존(ADR-001 의존 방향 위반) | 버림 |
| B3. 엔진 주문 큐에서만 Guard 확인 | 단순 | 큐를 거치지 않는 코드(복구, 감시 경로)가 생기면 그대로 우회 | 버림. 큐 확인은 7단계에서 추가로 둘 수 있음(이중 방어) |

**C. 계좌번호 키 모양**: (C1) 별도 맵 `account-numbers` **채택**, (C2) ADR-001 제안대로 `accounts`를 객체로 바꾸고 id를 키로(`accounts.KIS-US-1.broker`) — 순서가 사라지고 화면·로그 순서가 JSON 구현에 기대게 됨, 기존 제안 문서와 테스트 예시를 모두 바꿔야 함. 버림.

되돌릴 조건: 7단계에서 활성화 명령 채널이 프로세스 밖(UI)에서 오면서 2차 확인 위조 위험이 커지면 `SecondConfirmation`을 검증기만 만들 수 있는 타입(서명 또는 패키지 비공개 생성자)으로 바꾼다.

## 4. 위험과 한계

| 위험 | 영향 | 대응 |
|---|---|---|
| 증권사 호출 중 Guard가 내려가도 그 주문은 진행됨 | 최대 1건(계좌별 직렬 큐) | 계좌별 직렬 큐 가정(OrderGateway 계약). 잠금을 잡고 네트워크 호출하면 OFF 전환이 늦어져 더 위험하다고 판단 `[추론]` |
| `SecondConfirmation`은 public record | 명령 채널 안 코드는 비밀번호 검증 없이 만들 수 있음 | 명령 채널 밖 생성·상향 호출은 ArchUnit이 막는다. 7단계 명령 채널 작업에서 비밀번호 검증기만 만들 수 있게 바꾸는 것을 완료 기준에 넣는다(3장 되돌릴 조건) |
| 플러그인 출구 구현을 직접 생성하는 우회(`new KisOrderGateway(...)`) | 플러그인 모듈이 생기면 `orderGateway` 호출 없이 출구를 얻을 수 있음 | 2단계 플러그인 작업에 규칙 추가: `OrderGateway` 구현 생성자는 그 플러그인 패키지 밖에서 호출 금지, 엔진은 플러그인 내부 패키지에 의존 금지(data-engineer 작업 범위로 전달) |
| Guard OFF 뒤에는 조회도 막힘 | 킬스위치 직전 타임아웃으로 UNKNOWN이 된 주문은 다음 활성화의 첫 대사(ORD-07)까지 확정할 수 없음 | OFF면 주문 세션이 없다는 D-023과 일관. 재기동·재활성화 시 ORD-07 절차가 확정한다 |
| 상향 전이 감사 기록 중에는 같은 계좌의 하향 전이가 잠금을 기다림 | 감사 저장이 멈추면 OFF 전환이 늦어짐 | 주문 경로는 잠금을 잡지 않으므로 손절은 영향 없음. 7단계 영속 감사 로그에 쓰기 시간 상한을 둔다 |
| 감사 로그가 프로세스 로그뿐 | 재기동 후 조회 불가 | 실전 엔진 저장소 작업에서 영속 구현으로 교체. 인터페이스(`GuardAuditLog`)는 그대로 |
| PROTECTIVE에서 다시 여는 중 실패하면 PROTECTIVE로 복귀 | 세션이 깨진 채 보호 모드일 수 있음 | 보호 모드 손절이 계속돼야 한다는 D-023 원칙 1을 우선. 세션 상태는 장애 매트릭스 #3·#7 처리(7단계)가 DEGRADED로 다룬다 |
| ACTIVATING(OFF 출발) 중에는 취소가 허용됨 | 대사 단계에서 남은 미체결 정리가 필요할 수 있어 허용 | 취소는 보호 동작(D-023 원칙 1). 신규 주문은 막힘 |
| 2차 확인 유효 시간 60초 | 값이 출처 없음 | `[가정]`, Q-008에서 함께 정한다 |
| 실험실 프로세스 런타임에 risk.guard 클래스가 실림 | 실험실에서 Guard를 만들 수 있음 | 만들어도 주문 출구를 열 수 없다(`BrokerPlugin.orderGateway`는 엔진 팩토리만, ArchUnit). 가상 계좌는 Guard 대상이 아니다 |

## 5. 검증 (TST-03 실패 테스트)

| 규칙 | 테스트 |
|---|---|
| 기본 OFF, 재기동 OFF | `RealTradingGuardTest` 생성 직후 OFF, 열린 뒤 새 Guard는 OFF, 상태를 받는 생성 경로 없음 / `EngineMainTest` 실전 계좌가 있어도 기동 직후 모두 OFF |
| OFF에서 실전 주문 차단 | OFF에서 ENTRY·EXIT·STOP_LOSS 모두 거절, 등록 안 된 계좌 거절 / `GuardedOrderGatewayTest` OFF 후 같은 출구 주문·취소·조회가 증권사로 안 나감 |
| OFF 계좌 주문 클라이언트 생성 실패 | OFF 허가 발급 거부, 인증 전 허가 거부 / `RealOrderGatewaysTest` OFF면 플러그인 `orderGateway` 호출 0회 |
| 활성화 단계별 실패 시 OFF 유지 | 세 단계 각각 실패 주입(파라미터화), 단계 건너뛰기 거부, 활성화 없이 단계 통과 거부 |
| 2차 확인 | 만료, 미래 시각, 재사용 거부 |
| 보호 모드 | OFF 전환 시 신규 매수 차단·손절 매도·취소 통과, 보유 0이면 OFF, 재기동 후 2차 확인으로만 진입, 보호 모드에서 다시 여는 중 손절 계속·실패 시 보호 모드 복귀 |
| 자동 상향 없음 | 확인 없는 공개 메서드를 모두 호출해도 OFF 이상으로 안 올라감, 감사 기록 실패 시 상향 안 됨, 하향은 됨 |
| 계좌 격리 | 한 계좌 OFF가 다른 계좌 OPEN에 영향 없음 |
| D-010 | 다른 증권사 플러그인으로 출구 생성 거부, 다른 계좌 허가·주문 거부 |
| CFG-02 | `ConfigLoaderTest` `enabled`·`guard` 등 5종이 든 파일 거부, `EngineMainTest` enabled 파일로 기동해도 열 계좌 없음 |
| CFG-03·04 | 계좌번호가 runtime-config에 있으면 거부, 목록에 없는 별칭은 그 항목만 무시(값 미노출), 저장 시 계좌번호는 secrets로만, 실시간 변경 거부 |
| 우회 금지 | ArchUnit 규칙 2개 + 위반 픽스처(출구 우회 4건, 명령 채널 밖 상향 6건) 검출 |
| 감사 로그 지연 | 상향 감사 기록이 멈춘 동안 판정·취소 허용·허가 검사가 바로 돌아옴 |
| secrets 독립성 | runtime-config가 깨지거나 목록에 없는 계좌번호가 있어도 증권사 키 유지, 무시 건수만 경고(값·별칭 미노출), 키에 `/`가 있어도 예외 없이 그 파일만 거부 |

## 6. 범위 밖 (후속 작업)

| 항목 | 담당 | 단계 |
|---|---|---|
| 활성화 명령 채널(UI → 엔진), 2차 확인 비밀번호 해시 검증(해시만 비밀 저장소), 계좌 표시명 확인 화면 | ui-dev + risk-ops | 5·7단계 |
| 활성화 단계 실제 수행(인증, 세션, 첫 대사, ORD-07 UNKNOWN 확정) | risk-ops + data-engineer | 7단계 |
| 보호 모드 진입 시 미체결 매수 취소·보유 0 감지·세션 종료 실행 | risk-ops | 7단계 |
| 영속 감사 로그 | risk-ops + architect | 7단계 |
| 블랙리스트(RSK-12, 전역), 서킷브레이커(RSK-04), PANIC SELL(RSK-13), 주문 상태머신(ORD-07), 멱등 키 발급기 | risk-ops | 7단계 전 |
| CFG-05 계좌·키 교체 절차 | risk-ops | 7단계 |

## 7. 추천안 채택(자율) 목록

실전 영향 항목이라 이 PR 머지가 곧 사용자 결정이다(지침 8장 2번). 결정 로그 번호는 머지 후 pm-lead가 부여한다. (2026-10-14 부여: [D-078](../decisions/README.md))

1. Guard 상태는 메모리에만 두고 저장하지 않는다(대안 A1).
2. 주문 세션 차단은 허가 토큰 + 유일한 출구 팩토리 + ArchUnit(대안 B1).
3. 실전 계좌 키는 `accounts` 배열 + `account-numbers` 맵(대안 C1, ADR-001 2.6.1 제안 수정).
4. PROTECTIVE에서 다시 열다 실패하면 PROTECTIVE로 돌아간다(OFF가 아님). D-023 원칙 1(보호 동작은 막지 않음)에서 끌어낸 해석 `[추론]`.
5. ACTIVATING 중 취소 허용, OFF 출발 활성화 중 청산·손절은 차단(보유가 없다고 확인된 상태에서 연 것이므로).
6. 2차 확인 유효 시간 60초 `[가정]`.
7. 목록에 없는 계좌번호는 secrets 파일 전체가 아니라 그 항목만 버린다(architect R-1 대안 a).
8. `OrderIntent.isProtective()`를 허용 목록 방식으로 바꾼다(architect R-5).

## 8. 교차 검토 반영 (architect, 2026-10-08)

| 지적 | 등급 | 반영 |
|---|---|---|
| B-1 ArchUnit 규칙이 구체 타입·하위 인터페이스 호출과 메서드 참조를 못 잡음(실측 0건) | 차단 | 규칙을 `orderGateway` 이름 + 소유 타입 `assignableTo(BrokerPlugin)` 접근으로 바꾸고 픽스처 4건 고정 |
| R-1 계좌번호 교차 검증이 secrets.json 전체를 버려 증권사 키 손실 | 권장 | 그 항목만 버리고 건수 경고(2.4) |
| R-2 주문 경로가 계좌 잠금을 잡고, 잠금 안에서 감사 기록 | 권장 | 불변 스냅샷 volatile 읽기, 하향 감사는 잠금 밖(2.1) |
| R-3 Guard 상향 경로를 아무 코드나 부를 수 있음 | 권장 | ArchUnit `ONLY_COMMAND_CHANNEL_RAISES_GUARD`(2.2) |
| R-4 플러그인 출구 직접 생성 우회 | 권장 | 2단계 플러그인 작업으로 넘김(4장) |
| R-5 `isProtective()`가 새 값을 기본 허용 | 권장 | 허용 목록 방식 |
| N-1 키에 `/`가 있으면 NPE 경고 | 참고 | 경로 가드 복원, 테스트 추가 |
| N-3 OFF 뒤 조회 불가 | 참고 | 4장에 한계 기록 |
| N-2·N-4·N-5 | 참고 | 변경 없음(안전한 방향, 4장 범위 안) |

재검토(같은 날, 승인): 명령 채널 규칙이 메서드·생성자 참조를 못 잡는 점(권장 1)을 같은 방식(`accessTargetWhere`)으로 고치고 픽스처 4건 추가. 느린 감사 로그 테스트가 다른 Guard의 허가를 검사하던 점(N-6)과 secrets 사용 판정의 문자열 의존(N-8)도 고쳤다. 감사 기록 순서(N-7)는 7단계 영속 감사 로그에 계좌별 일련번호를 넣는 것으로 넘긴다.

## 9. 보강: 패키지 이름 위장 차단 (독립 QA F-1, 2026-10-09)

- 문제: 2.2의 두 ArchUnit 규칙은 허용 범위를 패키지 이름·FQN으로 정한다. JPMS가 없어 다른 모듈(예: 실험실)이 `com.autotrade.risk.guard`나 `com.autotrade.app.engine.command` 패키지에 클래스를 두면 허용 범위로 위장해 빌드를 통과했다([QA 보고서](../reviews/gates/main-realpath-2026-10-09.md) F-1 실측). 그래서 2.2의 "지금은 운영 코드 어디서도 실전을 열 수 없다"는 이 보강 전에는 성립하지 않았다.
- 결정: 패키지 소유 모듈 규칙 `ModuleOwnershipTest`(`autotrade-architecture-test`)를 더한다. 클래스패스의 `com.autotrade` 위치를 모듈별로 따로 가져와 위치 경로에서 모듈을 알아내고, 패키지 루트별 소유 모듈 표(가장 긴 접두어 우선)와 다르면 실패한다. 표에 없는 패키지 루트, 모듈을 알 수 없는 위치도 실패한다. 위치별로 가져오므로 같은 FQN(`RealOrderGateways`)을 다른 모듈에 둔 경우도 잡는다. 이 규칙이 패키지 이름을 모듈과 같게 만들어 2.2 규칙들의 전제가 된다. 첫 판의 이 결정만으로는 범위가 모자랐다(아래 9.1~9.3에서 R-1, Q-1·Q-2, R-7을 닫고 9.4에 최종 보장 범위와 남은 한계를 적는다).
- 대안: JPMS(`module-info.java`) 도입은 가장 강하지만 전 모듈·Spring Boot 실험실·테스트 설정을 함께 바꿔야 해 버렸다. 7단계 플러그인 모듈이 늘어 표 관리 비용이 커지면 다시 검토한다. 규칙마다 출처 기반 술어로 바꾸는 안은 새 규칙이 또 패키지 이름만 쓰면 같은 구멍이 생겨 버렸다.
- 검증: 위반 픽스처(테스트 소스의 `com.autotrade.risk.guard.SplitPackageGuardRaiser`, `app.engine.command.SplitPackageCommand`, `app.engine.order.SplitPackageGatewayOpener`, 표 밖 `com.autotrade.unowned`) 4건 검출, 실험실 운영 소스에 `com.autotrade.risk.guard.QaProbe`·`app.engine.command.QaCommandProbe`·같은 FQN `RealOrderGateways`를 임시로 넣으면 `ModuleOwnershipTest`가 3건으로 실패(그때 `ArchitectureTest`는 12/12 통과, 즉 기존 규칙만으로는 못 막음을 재현). 임시 파일은 커밋하지 않았다.
- 운영 규칙: 새 모듈을 만들면 `ModuleOwnershipTest.OWNERS` 표를 고친다(클래스를 만들지 않는 자원 전용 모듈은 `MODULES_WITHOUT_PACKAGES`). `autotrade-architecture-test` 의존은 자동이다(CLAUDE.md). 우리 모듈의 운영 클래스는 모두 그 모듈이 소유한 `com.autotrade` 패키지 안에 둔다. 같은 이름의 Gradle 프로젝트를 두 개 두지 않는다.

### 9.1 보강 1: 아무도 의존하지 않는 새 모듈 (risk-ops PR #21 검토 R-1)
- 문제: 처음 판은 `autotrade-architecture-test`의 의존 목록이 손 목록이라, 어떤 모듈도 의존하지 않는 새 모듈(새 프로세스·명령 채널 모듈 등)은 클래스패스에 오지 않아 `ArchitectureTest`·`ModuleOwnershipTest` 둘 다를 피했다(검토자 실측: settings에만 include한 모듈의 `com.autotrade.risk.guard.RogueRaiser`가 `beginActivation`을 불러도 `./gradlew build` 통과).
- 고친 방식: (1) 의존 목록을 settings의 모든 Java 프로젝트(중첩 포함, java 플러그인 적용, 자기 자신 제외)에서 자동으로 만든다(`autotrade-architecture-test/build.gradle.kts`, `pluginManager.withPlugin("java")`). (2) 클래스패스와 따로 settings의 프로젝트 경로 → 폴더 표를 시스템 속성 `autotrade.projectDirs`(9.3에서 `autotrade.projectLayout`으로 대체)로 넘기고, `ModuleOwnershipTest#클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다`가 클래스패스에 없는 프로젝트, 표에 없는 프로젝트, 표에만 남은 모듈, 같은 이름 프로젝트를 실패로 본다. 속성이 없으면(Gradle 밖 실행) 닫힌 쪽으로 실패한다. (1)을 누가 손 목록으로 되돌려도 (2)가 잡는다.
- 대안: (가) 빌드 단계 검사 태스크만 두고 손 목록 유지: 매번 두 곳을 고쳐야 하고, 태스크를 `check`에서 빼면 조용히 사라진다. (나) 자동 목록만: 손 목록 회귀를 못 잡는다. (다) 자동 목록 + 테스트 안 교차 확인(채택): 보호 테스트 자체가 범위를 검증하므로 보호 테스트를 지우거나 약화해야만 구멍이 다시 열린다(지침 11장).

### 9.2 보강 2: `com.autotrade` 밖 패키지와 같은 이름 중첩 프로젝트 (독립 QA PR #21 Q-1·Q-2)
- 문제: (Q-1) `ArchitectureTest.PRODUCTION`은 `importPackages("com.autotrade")`, `ModuleOwnershipTest`는 `Locations.ofPackage("com.autotrade")`만 봤다. 그래서 우리 모듈 안이라도 `qa.probe` 같은 밖 패키지 클래스는 두 테스트 모두 보지 않았다(QA 실측 E2: 실험실 모듈에서 Guard를 사람 확인 없이 OPEN까지, E3: 실전 엔진 모듈에서 Guard 팩토리 밖 `BrokerPlugin.orderGateway` 호출, 둘 다 `./gradlew build` 통과). (Q-2) 모듈을 경로의 폴더 이름으로 알아내 `:tools:autotrade-risk`(폴더 `tools/autotrade-risk`)가 `com.autotrade.risk.guard`를 써도 `autotrade-risk`로 인정됐다(E5).
- 고친 방식:
  1. 모듈 → 클래스 방향 검사: 우리 프로젝트가 만든 운영 클래스패스 항목(클래스 폴더·자원 폴더·jar)을 하나씩 **패키지 필터 없이** 전부 가져와, 모든 클래스가 그 프로젝트 소유 패키지 안에 있어야 한다. 밖 패키지는 "표에 없는 패키지 루트"로 실패한다. 패키지 → 위치 방향(모든 `com.autotrade` 위치는 우리 프로젝트여야 한다, 외부 jar 차단)은 그대로 둔다.
  2. `ArchitectureTest.PRODUCTION`을 우리 프로젝트 운영 항목 전체 + 예전 범위(클래스패스의 모든 `com.autotrade` 위치)의 합으로 넓혔다(`OurModules.architectureScope()`). 보호 테스트 강화이며, `ModuleOwnershipTest#ArchitectureTest_범위는_우리_모듈_운영_클래스_전체를_덮는다`가 새 범위가 우리 모듈 운영 클래스 전체와 예전 범위를 모두 포함하는지 확인한다. 이로써 밖 패키지 클래스는 소유 규칙과 실전 경로 규칙 양쪽에 걸린다(이중 방어).
  3. 위치 → 모듈 매핑을 폴더 이름 대신 Gradle 프로젝트 경로 → `projectDir` 표(가장 안쪽 폴더 우선, 폴더 경계 준수)로 한다. `OWNERS`의 모듈은 최상위 프로젝트(`:<이름>`)만 뜻하므로 같은 이름 중첩 프로젝트는 소유 모듈이 아니고, settings에 같은 이름 프로젝트가 둘 이상이면 실패한다.
- 대안: (가) `ArchitectureTest`는 두고 소유 규칙만 넓힘: 두 범위가 같아지지만 소유 테스트 하나에만 기대게 된다. 실전 경로 규칙이 스스로 전체를 보게 하는 쪽이 방어가 겹치므로 (나) 둘 다 넓힘(채택). 변경은 `PRODUCTION` 정의 한 줄이라 열린 PR #18·#19와의 충돌도 작다. (다) JPMS: 9장 대안과 같은 이유로 보류.

### 9.3 보강 3: 출력 경로를 다른 프로젝트 폴더로 돌리는 위장 (risk-ops PR #21 재검토 2 R-7)
- 문제: 9.2의 위치 → 프로젝트 매핑은 "어느 프로젝트 폴더 안에 있나"(가장 안쪽 `projectDir` 접두어)만 봤다. 검토자 실측 M7: 새 모듈 `autotrade-zz`를 `MODULES_WITHOUT_PACKAGES`에 "자원 전용"으로 등록하고 컴파일 출력을 `autotrade-risk/build/classes/java/zz`로 돌린 뒤 `com.autotrade.risk.guard.X`가 `beginActivation`을 부르게 하자 `./gradlew build`가 통과했다. 같은 종류로, 운영 출력을 `build/classes/test` 같은 테스트 경로 모양으로 돌리면 경로 패턴(`DoNotIncludeTests`)이 그 항목을 운영 코드로 보지 않았다.
- 고친 방식:
  1. `autotrade-architecture-test/build.gradle.kts`가 프로젝트마다 폴더와 **선언된 운영 출력**(main `classesDirs`, main 자원 폴더, `jar` 산출물), 클래스·자원·묶음 태스크(`AbstractCompile`, `ProcessResources`, `AbstractArchiveTask`)의 실제 출력, 저장소 루트, 외부 허용 위치(Gradle 사용자 홈, Gradle 배포판)를 시스템 속성 `autotrade.projectLayout`으로 넘긴다(이전 `autotrade.projectDirs` 대체).
  2. `OurModules`는 클래스패스 항목을 폴더 접두어가 아니라 선언 출력과 **정확히 일치**로 프로젝트에 매핑하고, 운영·테스트 구분도 경로 모양이 아니라 선언 종류로 한다(외부 jar만 예전 경로 패턴). `ArchitectureTest.PRODUCTION`의 가져오기 판정도 같은 기준이다(`OurModules.architectureImportOption()`).
  3. 실패 조건(`ModuleOwnershipTest#클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다`): 선언 출력이나 태스크 출력이 자기 프로젝트 폴더 밖이거나 더 안쪽 다른 프로젝트 폴더 안, 같은 출력을 두 프로젝트가 선언, 저장소 안인데 선언 출력이 아닌 클래스패스 항목(선언하지 않은 출력, 저장소 안 파일 jar 의존), 외부 허용 위치 밖 항목(임의 경로 jar), `MODULES_WITHOUT_PACKAGES`·`NO_CLASSES_YET` 모듈 운영 출력의 `.class`(선언을 믿지 않고 실제 출력을 센다). 두 예외 목록은 테스트가 지금 값을 고정해, 바꾸면 테스트도 같이 고쳐야 한다(risk-ops 검토 대상).
- 대안: (가) 한계로만 적고 리뷰 체크 항목에 "출력 경로 변경, 예외 목록 추가는 risk-ops 검토"를 넣음: 보호 테스트 표 수정은 정상 절차와 겉보기가 같아 리뷰에만 기대기 어렵다(검토자 의견과 같음). (나) 선언 출력 정확 일치 + 배치 검사(채택). (다) 클래스마다 자기 프로젝트 소스 파일이 있는지 대조: 아래 남은 한계까지 닫지만 소스 세트·생성 코드·자원 클래스 처리가 커서 지금은 버림. 되돌릴 조건: 아래 한계가 실제 위험이 되는 구성(빌드 단계 코드 생성, 다른 프로젝트 출력 합치기)이 들어오면 다시 본다.

### 9.4 지금의 보장 범위와 남은 한계
- 보장: settings에 include한 Gradle Java 프로젝트가 자기 프로젝트 폴더 안의 선언 출력으로 만든 운영 클래스는, 패키지 이름·출력 경로 모양과 상관없이 (1) 그 프로젝트 소유 패키지 안에 있어야 하고(`ModuleOwnershipTest`), (2) 2.2의 실전 경로 규칙을 받는다(`ArchitectureTest`). 새 모듈·중첩 프로젝트도 자동으로 들어오며, 표를 고치기 전까지 빌드가 실패한다. 아키텍처 테스트 클래스패스에 선언 출력도 외부 허용 위치도 아닌 항목이 있으면 실패한다. 그러므로 2.2의 "지금은 운영 코드 어디서도 실전을 열 수 없다"는 이 범위의 빌드 산출물에 대해 성립한다.
- 같은 종류의 남은 한계(빌드 스크립트 변경이 필요해 리뷰에서 보인다 `[추론]`, 실측하지 않음):
  - 한 프로젝트의 태스크가 **다른 프로젝트의 선언 출력 안으로** 클래스를 써 넣거나, 어떤 프로젝트의 `jar`가 다른 프로젝트 출력을 함께 묶으면(`from(...)`), 그 클래스는 받는 쪽 프로젝트 것으로 판정된다. 위 세 태스크 종류가 아닌 사용자 정의 태스크(`Copy`, `Exec` 등)의 출력 위치는 검사하지 않는다. 클래스와 소스 파일 대조(대안 다)가 필요하다.
  - 외부 허용 위치(Gradle 사용자 홈·배포판) 안의 jar와 Maven 의존성은 신뢰한다. 외부 라이브러리가 우리 Guard API를 부르는 코드를 담아도 `com.autotrade` 패키지가 아니면 보지 않는다. 새 의존성 추가는 빌드 파일 변경으로 보인다. 실전 프로세스(`autotrade-app-engine`)에 새 외부 의존성이 들어오는 PR은 risk-ops 검토 대상으로 본다(risk-ops 재검토 R-6과 같은 판단).
  - `includeBuild`로 붙인 다른 빌드는 이 프로젝트 목록에 없다. 그 산출물이 클래스패스에 오면 저장소 안 항목이면 실패, 밖이면 위 외부 규칙을 따른다.
  - 배포 시 런타임 클래스패스(설치본)와 리플렉션·`MethodHandle`처럼 바이트코드 호출로 드러나지 않는 접근은 보지 않는다. 런타임 split package 방어가 필요해지면 JPMS를 다시 검토한다. (리플렉션은 9.5에서 실전 경로 모듈에 대해 금지 규칙으로 닫았다. 설치본은 그대로 한계다.)
- 검증(테스트): `ModuleOwnershipTest` 13건. 위반 픽스처 `com.autotrade.risk.guard.SplitPackageGuardRaiser`·`app.engine.command.SplitPackageCommand`·`app.engine.order.SplitPackageGatewayOpener`·`com.autotrade.unowned.UnownedPackage`(4건), 밖 패키지 `qa.probe.OutsideRaiser`·`OutsideOpener`(소유 규칙 2건과 두 실전 경로 규칙 위반, `#com_autotrade_밖_패키지의_운영_클래스도_잡아낸다`), 중첩 프로젝트(`#같은_이름의_중첩_프로젝트는_소유_모듈로_인정하지_않는다`), 새 모듈 누락·손 목록 회귀·표에만 남은 모듈(`#아무도_의존하지_않는_새_모듈이_빠지면_잡아낸다`), M7 가짜 표·태스크 출력만 돌린 경우·같은 출력 공유·중첩 폴더(`#출력을_다른_프로젝트_폴더로_돌리면_잡아낸다`), 선언되지 않은 항목·테스트 모양 운영 출력·저장소 안 파일 jar·허용 위치 밖 jar(`#선언되지_않은_클래스패스_항목과_테스트처럼_보이는_운영_출력을_잡아낸다`), 클래스 없는 모듈의 `.class`(폴더·jar, `#클래스가_없다고_선언한_모듈에_클래스가_있으면_잡아낸다`), 경로 매핑(`#위치를_프로젝트_경로_표로_매핑한다`), 범위 포함(`#ArchitectureTest_범위는_...`).
- 검증(변조 실험, 커밋하지 않음): R-1 `autotrade-rogue` → 실패, 손 목록 회귀 → 실패. E2 실험실 `qa.probe.OutsideRaiser` → 실패(`ArchitectureTest` 명령 채널 규칙, `ModuleOwnershipTest`). E3 실전 엔진 `qa.probe.OutsideOpener` → 실패(`ArchitectureTest` 팩토리 규칙, `ModuleOwnershipTest`). E5 `:tools:autotrade-risk` → 실패. M7(자원 전용 등록 + `sourceSets.main.java.destinationDirectory`를 `autotrade-risk/build/classes/java/zz`로) → 실패(소유 위반 `X (:autotrade-zz)`, "선언 출력이 자기 프로젝트 폴더 밖"). M7 변형(`compileJava` 태스크 출력만 돌림) → 실패(BUILT 출력이 폴더 밖). 테스트 경로 변형(자원 전용 등록 + 클래스 출력 `build/classes/java/test`, `jar`를 `build/classes/test`로) → 실패(소유 위반, 클래스 없는 모듈의 `.class` 2건). 저장소 안 파일 jar 의존(`implementation(files("../qa-libs/evil.jar"))`) → 실패("저장소 안인데 선언 출력이 아닌 클래스패스 항목").

### 9.5 보강 4: 실전 경로의 리플렉션 금지와 외부 jar 허용 목록 (독립 QA PR #21 Q-4, risk-ops R-8, D-086)
- 문제: 2.2의 실전 경로 규칙은 바이트코드 호출만 본다. 실전 엔진 운영 코드가 `Class.forName`과 `getMethod("begin" + "Activation").invoke(..)`로 2차 확인 생성과 3단계 통과를 부르면 두 테스트가 통과하고 실제 Guard가 OPEN이 된다(QA 실측 E8). Jackson 같은 외부 라이브러리도 리플렉션으로 생성자를 부르므로 명령 DTO 필드로 `SecondConfirmation`을 받으면 검증기를 건너뛴다(같은 메커니즘 `[추론]`, 변조 실험 ③으로 규칙이 잡는 것만 확인).
- 고친 방식(`RealPathReflectionTest`, 자세한 목록은 [D-086](../decisions/D-086-real-path-reflection-ban.md)):
  1. 범위: `autotrade-architecture-test`의 `realPathRuntime` 구성이 실전 엔진·감시·증권사 플러그인 전체를 실제로 해석하고, 나온 우리 프로젝트와 외부 모듈을 시스템 속성 `autotrade.realPathComponents`로 넘긴다. 클래스는 패키지 이름이 아니라 9.3과 같은 선언 출력 매핑으로 그 프로젝트 것인지 판정한다(패키지 이름 판정도 함께, 이중 방어). 꼭 있어야 할 프로젝트 14개와 들어오면 안 되는 프로젝트를 테스트가 고정한다.
  2. 규칙: 범위의 운영 클래스는 리플렉션 호출·메서드 참조(`Method#invoke`, `Constructor#newInstance`, `Field` 읽기쓰기, `setAccessible`, `Class#forName`, 클래스 로더와 로더를 주고받는 API, `ResourceBundle`), 외부 명령 실행(`ProcessBuilder`, `Runtime#exec`), 시스템 속성 쓰기(`System#setProperty·setProperties·clearProperty`), `java.lang.invoke`, `Proxy`, Java 직렬화, 같은 일을 하는 패키지(`java.beans` 등), Jackson의 클래스 지정 다형 역직렬화, 허용 목록 밖 `ServiceLoader`(지금 비어 있음)를 쓰지 않는다.
  3. 운영 코드 전체에서 `SecondConfirmation` 타입 의존은 `com.autotrade.risk.guard`와 `OperatorAuthenticator`만. Guard 패키지 타입을 클래스 객체·제네릭 상위 타입 인자·주석 값으로 넘기는 것도 Guard 패키지 밖에서는 금지.
  4. 실전 경로 런타임의 외부 모듈은 허용 목록(지금 Jackson 4개)과 정확히 같고, 파일 의존 같은 다른 구성 요소는 없어야 한다. 9.4의 "외부 jar는 신뢰한다"는 실전 경로에서는 "허용 목록의 외부 jar만 신뢰한다"로 좁아진다.
  5. (PR #108 검토 반영) 범위의 운영 클래스는 허용 목록의 JDK 클래스(`java.lang`·`java.util`·`java.security`·`java.net`·`java.io`는 클래스 단위)·JDK 패키지(그 밖)·`java.lang.reflect` 구조 타입·Jackson 타입에만 의존한다. 외부 명령 실행 허용 목록은 비어 있고, 장애 대응표 #8·#9의 감시 자동 재기동 구현 PR에서 감시 실행기 하나만 넣는다. 허용 목록 안의 위험 API(네이티브 적재, `Module#add*`, Jackson 기존 객체 갱신·가시성·믹스인·문자열 클래스 찾기)는 따로 금지.
- 대안: 9장 대안과 같이 JPMS는 보류. QA 제안의 모듈 4개 패키지 목록은 엔진 런타임에 함께 실리는 domain·config·control의 리플렉션 유틸로 우회되어 버렸다(9.2 Q-1과 같은 종류). 리플렉션 대상을 Guard·출구로만 좁히는 안은 대상 이름이 문자열이라 정적 분석으로 알 수 없어 버렸다.
- 남은 한계: 허용 목록 Jackson의 기본 동작 안의 리플렉션(새 객체는 Guard 타입을 넘겨야 하므로 3이, 살아 있는 객체 덮어쓰기는 5가 막는다. 허용 타입 안에 모르는 객체 접근 API가 있으면 열려 있다 `[추론]`), 허용 패키지 `java.lang`·`java.util` 안의 아직 모르는 간접 호출 수단 `[추론]`, `Object`로 받은 Guard 객체의 `getClass()`(정적으로 못 잡아 Guard 타입 모양을 테스트로 고정), 빌드 밖에서 설치본에 끼워 넣은 jar와 `-javaagent` 같은 JVM 옵션(7단계 실행 절차서의 설치본 대조로 넘김, `-XX:+DisableAttachMechanism`과 `-D` 실행 옵션 대조 포함). 자세한 목록은 D-086 위험과 한계.
- 검증: 테스트 7건과 변조 실험 7종(D-086 검증 절). 운영 코드는 지금 위반 0건이다(`autotrade-config`의 `SecretPaths`는 `RecordComponent`·`Type`으로 구조만 읽고 호출하지 않는다).

