# 설정 키 목록

설정 체계와 결정 배경은 [ADR-001](../adr/ADR-001-module-structure.md) 2.6절. 기본값 원본은 `autotrade-config/src/main/resources/autotrade-default.json`이며, 이 표와 다르면 그 파일이 맞고 이 표를 고친다.

- 기동 순서: 내장 default → 외부 `runtime-config.json`(일반 키, 바꾼 키만 둬도 됨) → 외부 `secrets.json`(🔒 키만) → 운영 중 UI 변경.
- 설정 폴더: `-Dautotrade.config-dir` → 환경 변수 `AUTOTRADE_CONFIG_DIR` → `~/.autotrade/`. 두 파일 모두 이 폴더에 두고 저장소 안에 두지 않는다. 저장 시 폴더 700, 파일 600(POSIX).
- 🔒 키는 `secrets.json`에만 둔다(CFG-03). `runtime-config.json`에 🔒 키가 있거나 `secrets.json`에 일반 키가 있으면 그 파일을 쓰지 않는다.
- 외부 파일이 깨지면(문법 오류, 모르는 키, 범위 위반) 그 파일 없이 기동하고 어느 파일이 문제인지 경고한다(값은 경고에 남기지 않음).
- 실시간 변경: **즉시** = 다음 틱부터 반영, **재기동** = 해당 프로세스 재기동 필요, **재연결 절차** = 미체결·보유 확인 → 세션 종료 → 재연결(해당 작업에서 구현 전까지 UI 변경 불가).
- 비밀값(🔒)은 화면·로그·REST 응답에 `****`로만 나온다.
- 실전 엔진은 설정 폴더 안 `run/engine-control.sock`에 실전 제어 명령 채널을 열고 `run/engine.lock`을 잡는다(ADR-006). 같은 설정 폴더로 엔진을 두 번 띄우면 두 번째는 뜨지 않는다. 단, 엔진이 도는 중에 `run` 폴더나 `engine.lock`을 지우면 이 보호가 사라지므로 지우지 않는다(ADR-006 4장). 실험실 운영 화면은 같은 설정 폴더를 써야 엔진에 닿는다. 소켓 경로가 약 100자를 넘으면 엔진이 뜨지 않으니 설정 폴더는 짧은 경로에 둔다.
- 시세 게이트웨이는 설정 폴더 안 `run/market-feed.sock`에 실험실 실시간 시세 전달 소켓을 연다(D-076, [ADR-010](../adr/ADR-010-live-feed-and-session.md)). 못 열면 경고만 남기고 녹화만 한다. 실험실 실시간 모의 세션은 같은 설정 폴더의 이 소켓에 붙고, 입력 기록을 설정 폴더 `live-sessions/<실험ID>/journal/`(녹화 형식, 저장소 밖, 재배포 금지)에, 세션 잠금을 `live-sessions/<실험ID>/session.lock`에 둔다. 리플레이 확인 명령(`runLiveReplayCheck`)과 주간 점검 명령(`runLiveWeeklyCheck`, D-088)도 기본으로 이 `live-sessions/`를 읽는다(`-PsessionsDir`로 바꿈). 주간 점검은 같은 설정 폴더의 게이트웨이 녹화 `recordings/`도 읽는다(`-PrecordingsDir`).

| 키 | 타입 | 기본값 | 범위·허용값 | 실시간 변경 | 읽는 곳 | 근거 |
|---|---|---|---|---|---|---|
| `market-data.priority` | 증권사 배열 | `["KIWOOM","TOSS","KIS"]` | KIS·KIWOOM·TOSS, 비어 있지 않고 중복 없음. 게이트웨이는 이 순서대로 시세 소스를 고르고, 목록에 없는 증권사는 시세 소스로 쓰지 않는다(아직 구현하지 않은 소스는 건너뜀). 기본값은 D-024 순서다: 토스 시세 조회는 무료로 사용자가 확인했다(Q-020, D-039). 무료여도 증권사별 호출 한도는 지킨다(`docs/research/market-data-limits.md` 1장). 토스 보유 종목 보호 폴링은 이 키가 아니라 `market-data.toss-polling-scope`가 정한다. **D-059(2026-10-11)**: 토스 Client ID가 하나인 동안 게이트웨이는 토스를 호출하지 않으므로 `TOSS` 항목을 건너뛴다(토스 연결 PR에서 기동 경고와 함께 강제, 기본값을 `["KIWOOM","KIS"]`로 바꿀지도 그 PR에서 정함) | 재기동 | 시세 게이트웨이 | D-024, D-039(D-033 되돌림), D-059 |
| `market-data.mode` | enum | `AUTO` | AUTO·FAILOVER·SHARDED. AUTO는 실시간 소스 두 곳이 설정되어 있으면 SHARDED, 아니면 FAILOVER(D-009). 운용 중 한쪽이 DEGRADED가 되면 남은 쪽으로 넘기는 동작은 2단계 ADR에서 정한다 | 재기동 | 시세 게이트웨이 | D-009 |
| `market-data.toss-polling-scope` | enum | `HELD_ONLY` | HELD_ONLY·OFF. ⚠️ OFF는 전 시세 끊김 시 보유 종목 폴링 손절 감시(장애표 2번)를 끈다. 실전 보유 종목이 있을 때의 검증은 risk-ops 검토 후 추가 | 재기동 | 실전 엔진(보호 폴링) | D-008, D-024(HELD_ONLY 유지) |
| `lab.max-parallel-runs` | int | `4` | 1~64 | 다음 실험 실행부터 | 실험실이 기동 때와 설정 화면 저장 때 `<설정 폴더>/experiment-state/limits`(experiment-limits-v1)로 내보내고, 실험 실행 명령은 `-PmaxParallelRuns`가 없을 때 그 파일 값을 병렬 Run 상한으로 쓴다(D-094, 실행 명령은 설정 로더를 읽지 않음 D-063). 실행 중인 실험은 그대로 | ADR-001 3장 예산 `[가정]` |
| `lab.default-seed-capital-krw` | long(원) | `10000000` | 1 이상 | 즉시 | 실험 생성 화면(국내) | D-021 |
| `lab.default-seed-capital-usd` | long(달러) | `10000` | 1 이상 | 즉시 | 실험 생성 화면(미국) | D-021 |
| `notification.slack.enabled` | bool | `false` | true면 webhook-url 필수 | 즉시 | 알림 | 원본 기획 v2.0 |
| `notification.slack.webhook-url` 🔒 | 문자열 | 빈 값 | Slack Incoming Webhook URL | 즉시 | 알림 | |
| `notification.kakao.enabled` | bool | `false` | true면 access-token 필수 | 즉시 | 알림 | |
| `notification.kakao.mode` | enum | `ME` | ME(나에게 보내기, 무료)·BIZ(알림톡, 유료: 켜기 전 사용자 확인) | 즉시 | 알림 | 원본 기획 v4.0 |
| `notification.kakao.access-token` 🔒 | 문자열 | 빈 값 | | 즉시 | 알림 | |
| `brokers.{kis,kiwoom,toss}.app-key` 🔒 | 문자열 | 빈 값 | 비어 있으면 그 증권사는 NOT_CONFIGURED | 재연결 절차 | 증권사 플러그인 | broker-and-fault-design.md 3장 |
| `brokers.{kis,kiwoom,toss}.app-secret` 🔒 | 문자열 | 빈 값 | 키움은 secret-key, 토스는 secret을 여기에 넣는다 | 재연결 절차 | 증권사 플러그인 | |
| `real-trading.accounts` | 배열 | `[]` | 항목은 `id`(영문·숫자·`_`·`-` 1~64자, 중복 불가)·`broker`(KIS·KIWOOM·TOSS)·`market`(KRX·US)만. **활성화 키(`enabled` 등)는 없다**: 넣으면 그 파일 전체를 쓰지 않는다(CFG-02). RealTradingGuard는 기동 시 모든 계좌 OFF | 계좌 교체 절차(CFG-05, 구현 전까지 UI 변경 불가) | 실전 엔진 | [ADR-002](../adr/ADR-002-real-trading-guard.md), D-010, D-016 |
| `real-trading.account-numbers.<id>` 🔒 | 문자열 맵 | `{}` | 키는 `accounts`의 id만 쓴다. 목록에 없는 항목은 그 항목만 무시하고 건수만 경고한다(값·별칭은 남기지 않음). 값이 없는 계좌는 활성화할 수 없다 | 계좌 교체 절차 | 실전 엔진(증권사 플러그인에 전달) | [ADR-002](../adr/ADR-002-real-trading-guard.md) 2.4 |
| `real-trading.operator-password-hash` 🔒 | 문자열 | 빈 값 | `pbkdf2-sha256$<반복>$<salt>$<해시>`(반복 100,000 이상, 기본 600,000). `autotrade-engine hash-password`로 만든다(비밀번호 15자 이상). 반복은 100,000~10,000,000. 평문 비밀번호는 어디에도 두지 않는다. 비었거나 형식이 틀리면 실전 활성화와 PANIC 청산을 거절한다(PANIC의 보호 모드 고정은 한다). 모든 프로세스가 secrets.json을 함께 읽으므로 이 값도 4개 프로세스 메모리에 올라간다. **바꾼 뒤에는 실전 엔진과 실험실을 모두 다시 시작한다**: 실험실은 기동 때 읽은 값과 디스크가 다르면 설정 화면 저장을 `CONFIG_CHANGED_ON_DISK`(409)로 막는다(ADR-005 저장 안전장치) | 재기동(실전 엔진, 실험실) | 실전 엔진(2차 확인 검증) | [ADR-006](../adr/ADR-006-real-control-channel.md) 2.4, RSK-01 |

## 실험실 프로세스 속성 (Spring, `application.yml`)
설정 파일 계층(runtime-config.json)이 아니라 실험실 프로세스 기동 속성이다. 실행 중에 바뀌지 않는다.

| 속성 | 기본값 | 설명 | 근거 |
|---|---|---|---|
| `server.address` | `127.0.0.1` | 대시보드 바인딩 주소. 루프백이 아니거나 비우면 기동을 거부한다 | D-020, [ADR-005](../adr/ADR-005-ui-dashboard.md) |
| `server.port` | `8480` (환경 변수 `AUTOTRADE_UI_PORT`) | 대시보드 포트 | ADR-005 |
| `autotrade.lab.strategies-dir` | `strategies` (실행 폴더 기준, bootRun은 저장소 루트) | 전략 프로필 YAML 폴더 | ADR-005 4장 |
| `autotrade.lab.selections-dir` | `selections` (실행 폴더 기준, bootRun은 저장소 루트) | 선정 프로필 YAML 폴더. 실험 실행기가 읽는 `selections/`와 같은 폴더를 가리켜야 화면에서 만든 선정이 실험에 쓰인다 | ADR-005 4·5장, ADR-007 2.1, D-060 |
| `autotrade.lab.experiments-dir` | `experiments` (실행 폴더 기준, bootRun은 저장소 루트) | 조합 실험 폴더. 실행 명령(`runCombinationExperiment`)이 결과 묶음을 쓰는 `experiments/`와 같은 폴더를 가리켜야 실험 화면에서 불러올 수 있다. 실험 생성 화면도 이 폴더에 새 실험 폴더와 등록 파일(`experiment.yaml`)을 쓴다. 실시간 모의 세션(D-076)도 이 폴더의 `input: "live"` 등록 파일을 읽고 끝낼 때 `live-detail.txt`를 쓴다 | D-062, D-074, D-076 |
| `autotrade.lab.live.min-free-gb` | `20` | 실시간 모의 입력 기록 폴더(설정 폴더 `live-sessions/`) 디스크 여유 하한(GB). 미만이면 세션을 시작하지 않고, 운용 중이면 1초 안에 멈춘다(실전 엔진 기록과 같은 디스크일 수 있음). 1~1,000,000, 범위 밖이면 실험실이 뜨지 않는다(0이면 하한이 꺼지므로 거부) | D-076, risk-ops PR #87 R1, QA PR #87 R2 |
| `autotrade.lab.monitor.enabled` | `true` | 모니터링 수집(MON-05). 끄면 수집하지 않고 화면은 꺼짐을 보여 준다. `autotrade.lab.monitor.*` 값이 하나라도 틀리면(true·false가 아님, 정수가 아님, 범위 밖, 아래 임계 오류) 실험실은 뜨고 모니터링만 "사용 불가"로 오류 문구를 보여 준다(QA PR #114 R1) | D-090, [D-091](../decisions/D-091-monitor-settings-error-and-shutdown.md) |
| `autotrade.lab.monitor.interval-seconds` | `5` | 수집 주기(초). 1~3600, 범위 밖이면 모니터링 사용 불가 | D-090, [D-091](../decisions/D-091-monitor-settings-error-and-shutdown.md) |
| `autotrade.lab.monitor.history-size` | `720` | 화면 그래프용으로 들고 있을 표본 수(5초 주기면 1시간). 1~10000, 범위 밖이면 모니터링 사용 불가 | D-090, [D-091](../decisions/D-091-monitor-settings-error-and-shutdown.md) |
| `autotrade.lab.monitor.disks` | 빈 값 | 지표를 낼 드라이브(마운트) 쉼표 목록(예: `C:\,D:\`, 대소문자 무시). 비면 모든 로컬 드라이브 | D-090 |
| `autotrade.lab.monitor.thresholds` | 빈 값 | 기본 임계 덮어쓰기. `지표=방향:경고:위험:연속`을 `;`로 잇는다(예: `cpu.system=ABOVE:70:90:3;disk.free-gb=BELOW:100:30:1`). 방향은 ABOVE·BELOW. 기본 임계와 지표 이름은 D-090 5번과 `MetricNames`. 형식이 틀리거나, 같은 지표가 두 번이거나, 지표 이름이 `MetricNames.ALL`에 없으면(오타, 대상까지 쓴 이름) 모니터링 사용 불가 | D-090, [D-091](../decisions/D-091-monitor-settings-error-and-shutdown.md) |
| `autotrade.code-commit` | `unknown` (bootRun은 `git rev-parse HEAD`를 JVM 속성으로 넘김) | 실시간 모의 세션 상세 보고서의 `code` 줄(커밋). 리플레이 확인은 이 줄을 비교에서 뺀다 | D-076 |
| `autotrade.code-dirty` | `true` (bootRun은 `git status --porcelain`이 비었는지로 넘김) | 같은 보고서의 작업 트리 변경 여부 | D-076 |

## 감시 프로세스 설정 파일 (`<설정 폴더>/watchdog.properties`)
감시 프로세스(D-013 ④)만 읽는 별도 파일이다(D-092). `키=값` 한 줄에 하나, `#`으로 시작하면 주석. 파일이 없으면 모두 기본값이다. **모르는 키(오타), 같은 키 두 번, 형식·범위 오류, 없는 폴더, 4KB를 넘는 파일 크기가 하나라도 있으면 감시는 문제를 모두 출력하고 종료 코드 2로 뜨지 않는다**(PR #115 risk-ops K2). 바꾸면 감시를 다시 시작한다. 비밀값은 넣지 않는다.

| 키 | 기본값 | 설명 | 근거 |
|---|---|---|---|
| `interval-seconds` | `5` | 점검 주기(초), 1~60 | D-092 |
| `heartbeat-stale-seconds` | `15` | 하트비트가 이보다 오래되면 멈춤(초), 10~600, `interval-seconds` 이상. 각 프로세스는 5초마다 쓴다 | D-092 |
| `processes` | `engine,gateway,lab` | 하트비트를 볼 프로세스(쉼표, 이 셋 중에서). 비우면 기동 거부 | D-092 |
| `disks` | 설정 폴더 | 여유 공간을 볼 폴더(쉼표). 드라이브마다 그 드라이브의 폴더 하나면 된다. 없는 폴더·같은 폴더 두 번이면 기동 거부. 빼면 설정 폴더(없으면 감시가 만든다) | D-092 |
| `disk.free-gb` | `50:20:1` | `경고GB:위험GB:연속`. 위험이면 단발 일괄 명령 정지 요청 파일을 만든다. 0 < 위험 ≤ 경고 ≤ 100000, 연속 1~10. 값은 `[가정]` | D-092, D-090 5번 |
| `release-hold-seconds` | `300` | 정지 요청 파일을 지우려면 모든 값(드라이브 여유, CPU, 메모리)이 정상(경고 경계 밖)으로 이어져야 하는 시간(초), 0~3600. `[가정]` | D-092, D-095, ADR-007 2.7 |
| `cpu.system` | `85:95:3` | PC 전체 CPU 사용률 `경고%:위험%:연속`. 위험이면 정지 요청 파일을 만든다. 0 < 경고 < 위험 ≤ 100(같으면 해제 여유가 없어 거부), 연속 1~10. 값은 `[가정]`(실험실 모니터링 기본과 같음) | D-095, D-090 5번 |
| `memory.used` | `85:95:3` | PC 전체 물리 메모리 사용률 `경고%:위험%:연속`. 범위는 `cpu.system`과 같다. 값은 `[가정]` | D-095, D-090 5번 |

예시(값만, 비밀값 없음):
```
# <설정 폴더>/watchdog.properties
interval-seconds=5
disks=C:\Users\<사용자>\.autotrade,D:\recordings
disk.free-gb=50:20:1
cpu.system=85:95:3
memory.used=85:95:3
```

## 아직 없는 키 (담당 작업에서 추가)
- 리스크 한도(Q-008), 블랙리스트(RSK-12, DB 영속): risk-ops. RealTradingGuard 상태는 설정 키가 아니며 앞으로도 추가하지 않는다(CFG-02, ADR-002 2.3).
- 비용 모델 표(수수료·거래세·슬리피지, 적용 시작일): 3단계는 D-021 값을 `CostModels` 코드 기본값으로 둔다. 설정 키(`cost.profiles.*`, Q-004 조사 4장)는 architect 검토 후 추가([ADR-004](../adr/ADR-004-simulation-engine.md) 4장).
- 가상 체결 주문 지연(`ExecutionSettings`, 기본 0.5초): 실험 설정으로 받을 키는 실험 생성 화면 작업에서 추가(D-025).
- 감시 에이전트 임계치: risk-ops(monitor-agent-jar 자체 설정). 감시 프로세스의 드라이브·CPU·메모리 임계는 위 `watchdog.properties`로 먼저 정했다(D-092, D-095). 실험실 모니터링 화면은 위 `autotrade.lab.monitor.*` 기동 속성으로 먼저 정했다(D-090). 감시 프로세스가 같은 에이전트를 쓸 때 공통 키 `monitor.interval-seconds`, `monitor.thresholds.<지표>.{direction,warn,critical,sustain}`(재기동, 감시·실험실)을 두자는 변경 제안이 D-090에 있다(architect 결정).
- 시세 녹화 폴더, 감시 종목 파일, 무응답 판단 시간: data-engineer 제안 [ADR-003 6장](../adr/ADR-003-market-data.md). 2단계는 설정 폴더 아래 `recordings/`, `watchlist.json`, 90초로 고정.
- 종목명 표 `<설정 폴더>/symbols/<MARKET>.csv`(예: `KRX.csv`, UTF-8, 헤더 `code,name,board`): 화면·보고서 표시용이고 계산·식별에 쓰지 않는다(결과 해시에 영향 없음). 없으면 이름만 비고 기동에는 영향 없다. 깨진 파일은 그 시장만 빼고 경고 로그를 남긴다. `./gradlew :autotrade-app-gateway:symbolMaster`가 한국투자 공개 종목 마스터로 `KRX.csv`를 만든다(키 불필요). 실험실은 기동 때 읽으므로 새로 만든 뒤 실험실을 다시 시작한다.
- 참고: DEGRADED 시 재배분 동작(설정 키 아님)은 ADR-003 2.2.

## 수집 일괄 명령 전용 파일 (`collector-secrets.json`)
4개 프로세스는 이 파일을 읽지 않고, 수집 명령(`autotrade-app-collector`)은 `runtime-config.json`·`secrets.json`을 읽지 않는다(D-050 ⑤, D-051, [ADR-008](../adr/ADR-008-collector.md) 2.2). 같은 설정 폴더에 둔다. 모르는 항목이 있으면 수집 명령이 파일 전체를 거부한다. 기간·호출 상한·폴더는 설정 키가 아니라 명령줄 옵션이다([수집기 runbook](../runbooks/collector.md)).

| 키 | 형식 | 기본값 | 설명 |
|---|---|---|---|
| `krx.auth-key` 🔒 | 문자열 | 빈 값 | KRX Open API 인증키(`AUTH_KEY` 헤더). 비면 `krx-daily`가 오류로 끝난다 |
| `opendart.crtfc-key` 🔒 | 문자열 | 빈 값 | OpenDART 인증키(쿼리 `crtfc_key`, 로그·오류 메시지에서 가림). 비면 `dart-financials`가 오류로 끝난다 |

## 예시 (자리표시자)
`runtime-config.json`
```json
{
  "lab": { "max-parallel-runs": 2 },
  "notification": { "slack": { "enabled": true } },
  "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] }
}
```
`secrets.json`
```json
{
  "notification": { "slack": { "webhook-url": "<SLACK_WEBHOOK_URL>" } },
  "brokers": {
    "kiwoom": { "app-key": "<KIWOOM_APP_KEY>", "app-secret": "<KIWOOM_SECRET_KEY>" }
  },
  "real-trading": {
    "account-numbers": { "KIS-US-1": "<ACCOUNT_NUMBER>" },
    "operator-password-hash": "<OUTPUT_OF_autotrade-engine_hash-password>"
  }
}
```
`collector-secrets.json`
```json
{ "krx": { "auth-key": "<KRX_OPEN_API_AUTH_KEY>" }, "opendart": { "crtfc-key": "<OPENDART_CRTFC_KEY>" } }
```
