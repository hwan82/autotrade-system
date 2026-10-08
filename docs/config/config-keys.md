# 설정 키 목록

설정 체계와 결정 배경은 [ADR-001](../adr/ADR-001-module-structure.md) 2.6절. 기본값 원본은 `autotrade-config/src/main/resources/autotrade-default.json`이며, 이 표와 다르면 그 파일이 맞고 이 표를 고친다.

- 기동 순서: 내장 default → 외부 `runtime-config.json`(바꾼 키만 둬도 됨) → 운영 중 UI 변경.
- 외부 파일 위치: `-Dautotrade.config` → 환경 변수 `AUTOTRADE_CONFIG` → `~/.autotrade/runtime-config.json`. 저장소 안에 두지 않는다.
- 외부 파일이 깨지면(문법 오류, 모르는 키, 범위 위반) 내장 default로 기동하고 경고한다.
- 실시간 변경: **즉시** = 다음 틱부터 반영, **재기동** = 해당 프로세스 재기동 필요, **재연결 절차** = 미체결·보유 확인 → 세션 종료 → 재연결(해당 작업에서 구현 전까지 UI 변경 불가).
- 비밀값(🔒)은 화면·로그·REST 응답에 `****`로만 나온다.

| 키 | 타입 | 기본값 | 범위·허용값 | 실시간 변경 | 읽는 곳 | 근거 |
|---|---|---|---|---|---|---|
| `market-data.priority` | 증권사 배열 | `["KIWOOM","KIS","TOSS"]` | KIS·KIWOOM·TOSS, 비어 있지 않고 중복 없음. 실시간 소스는 이 순서에서 REALTIME_QUOTE 능력이 있는 곳만 고르므로 토스는 폴링에만 쓰인다 | 재기동 | 시세 게이트웨이 | D-008(잠정, Q-001 후 확정) |
| `market-data.mode` | enum | `AUTO` | AUTO·FAILOVER·SHARDED. AUTO는 실시간 소스 두 곳이 설정되어 있으면 SHARDED, 아니면 FAILOVER(D-009). 운용 중 한쪽이 DEGRADED가 되면 남은 쪽으로 넘기는 동작은 2단계 ADR에서 정한다 | 재기동 | 시세 게이트웨이 | D-009 |
| `market-data.toss-polling-scope` | enum | `HELD_ONLY` | HELD_ONLY·OFF. ⚠️ OFF는 전 시세 끊김 시 보유 종목 폴링 손절 감시(장애표 2번)를 끈다. 실전 보유 종목이 있을 때의 검증은 risk-ops 검토 후 추가 | 재기동 | 실전 엔진(보호 폴링) | D-008 |
| `lab.max-parallel-runs` | int | `4` | 1~64 | 즉시(새 Run부터) | 실험실 | ADR-001 3장 예산 `[가정]` |
| `lab.default-seed-capital-krw` | long(원) | `10000000` | 1 이상 | 즉시 | 실험 생성 화면 | team-design.md 8장 D6 `[가정]`, 결정 기록 대기 |
| `notification.slack.enabled` | bool | `false` | true면 webhook-url 필수 | 즉시 | 알림 | 원본 기획 v2.0 |
| `notification.slack.webhook-url` 🔒 | 문자열 | 빈 값 | Slack Incoming Webhook URL | 즉시 | 알림 | |
| `notification.kakao.enabled` | bool | `false` | true면 access-token 필수 | 즉시 | 알림 | |
| `notification.kakao.mode` | enum | `ME` | ME(나에게 보내기, 무료)·BIZ(알림톡, 유료: 켜기 전 사용자 확인) | 즉시 | 알림 | 원본 기획 v4.0 |
| `notification.kakao.access-token` 🔒 | 문자열 | 빈 값 | | 즉시 | 알림 | |
| `brokers.{kis,kiwoom,toss}.app-key` 🔒 | 문자열 | 빈 값 | 비어 있으면 그 증권사는 NOT_CONFIGURED | 재연결 절차 | 증권사 플러그인 | broker-and-fault-design.md 3장 |
| `brokers.{kis,kiwoom,toss}.app-secret` 🔒 | 문자열 | 빈 값 | 키움은 secret-key, 토스는 secret을 여기에 넣는다 | 재연결 절차 | 증권사 플러그인 | |

## 아직 없는 키 (담당 작업에서 추가)
- 실전 계좌(`real-trading.accounts`), RealTradingGuard, 리스크 한도, 블랙리스트: risk-ops. 설정 파일 로딩이 실전 활성화를 켤 수 없어야 한다.
- 비용 모델(수수료·거래세·슬리피지): strategy-researcher 제안(Q-004) → architect 검토.
- 감시 에이전트 임계치: risk-ops(monitor-agent-jar 자체 설정).

## runtime-config.json 예시 (자리표시자)
```json
{
  "lab": { "max-parallel-runs": 2 },
  "brokers": {
    "kiwoom": { "app-key": "<KIWOOM_APP_KEY>", "app-secret": "<KIWOOM_SECRET_KEY>" }
  }
}
```
