# 6단계 실시간 모의 세션 운용 절차(사용자 PC)

- 근거: [D-076](../decisions/D-076-live-feed-and-session.md), [ADR-010](../adr/ADR-010-live-feed-and-session.md)
- 대상: 장중에 게이트웨이 실시간 시세로 실험실의 모의 계좌 여러 개를 돌리고, 끝난 뒤 리플레이와 같은지 확인한다. 실전 주문은 없다(게이트웨이는 주문 API를 부르지 않음, MD-01).
- 실험실 화면의 **실시간 모의** 메뉴(http://127.0.0.1:8480/#/live)에서 등록 파일을 골라 시작·멈춤·끝내기를 하고, 계좌별 상태와 최근 공백을 본다(3초마다 갱신). 아래 PowerShell API 호출은 화면 없이 할 때 쓴다.

## 1. 운용 전(장 시작 전날까지)

1. 등록 파일을 만들고 **커밋**한다(사전 등록, 운용 중 고치지 않음). 예: `experiments/EXP-20261015-LIVE01/experiment.yaml`

   ```yaml
   id: "EXP-20261015-LIVE01"
   question: "장중 프로필들이 실시간 시세에서 리플레이와 같은 결과를 내는가"
   input: "live"
   market: "KRX"
   from: "2026-10-15"
   to: "2026-12-11"
   seed: "20261015"
   seed-capital: "10000000"
   benchmark: "005930"
   profiles:
     - "kr-bh-baseline@1"
     - "kr-random-control@1"
   boards:              # 게이트웨이 감시 종목 중 받을 종목. 여기 없는 종목은 세션에 넣지 않는다
     - "005930 KOSPI"
   ```

2. `boards` 종목이 게이트웨이 감시 목록에 들어 있는지 확인한다(없으면 세션에 이벤트가 오지 않음).
3. 설정 폴더(`AUTOTRADE_CONFIG_DIR`, 기본 `~/.autotrade/`) 디스크 여유를 본다. 입력 기록(`live-sessions/<ID>/journal/`)이 게이트웨이 녹화와 비슷한 크기로 하루치씩 쌓인다.

## 2. 장 시작 전

1. 게이트웨이: `./gradlew :autotrade-app-gateway:run`. 로그에 `실험실 시세 전달 소켓을 열었습니다`가 나와야 한다. `열지 못해 녹화만 합니다`가 나오면 설정 폴더 경로가 길거나(약 100자) 다른 게이트웨이가 떠 있는 것이다. 녹화는 그대로 된다.
2. 실험실: `./gradlew :autotrade-app-lab:bootRun`.
3. 세션 시작(CSRF 토큰은 `GET /api/session`의 응답에서 읽는다):

   ```powershell
   $s = Invoke-RestMethod http://127.0.0.1:8480/api/session -SessionVariable web
   $h = @{ "X-CSRF-Token" = $s.csrfToken }
   Invoke-RestMethod -Method Post -Headers $h -WebSession $web http://127.0.0.1:8480/api/live/sessions/EXP-20261015-LIVE01/start
   Invoke-RestMethod http://127.0.0.1:8480/api/live/status
   ```

4. 상태에서 `restoring`이 `false`가 된 뒤(입력 기록으로 계좌를 되살리는 중에는 `true`, 게이트웨이에 아직 붙지 않음) `feedState`가 `CONNECTED`, 장중에 `session.events`가 늘어나는지 본다. 시작이 실패하면 `stopReason`에 `시작 실패: …`가 남는다.
5. 설정 폴더 디스크 여유가 20GB(`autotrade.lab.live.min-free-gb`)보다 적으면 시작하지 않고, 운용 중이면 멈춘다(`stopReason`). 이 값은 1GB 이상이어야 하고, 0이나 음수면 실험실이 뜨지 않는다(0이면 하한이 꺼져 실전 엔진 기록과 같은 디스크를 채울 수 있음, risk-ops PR #87 P1, QA R2).

## 3. 운용 중

- 실험실·게이트웨이를 껐다 켜도 된다. 실험실은 세션을 자동으로 다시 열지 않으니 2-3을 다시 한다. 입력 기록으로 계좌를 되살리고 끊긴 구간은 공백으로 남는다.
- 감시 프로세스가 정지 요청 파일을 만들면 세션이 멈춘다(`stopReason`). 요청이 사라진 뒤 다시 시작한다.
- 주간 점검(D-085): 주마다(마지막 거래일 장 마감 뒤, 수신이 멈춘 다음) 아래 명령을 돌리고 생긴 `experiments/<ID>/weekly/<날짜>.md`를 커밋한다. 상태 화면의 `dataGaps`·`inboxDropped`·`feedReconnects`를 손으로 옮기지 않아도 된다(같은 값을 입력 기록과 게이트웨이 녹화에서 다시 센다).

  ```powershell
  ./gradlew :autotrade-app-experiment:runLiveWeeklyCheck -Pexperiment=EXP-20261015-LIVE01
  # 지난 주를 다시 볼 때: -Pto=2026-10-23, 폴더가 기본과 다르면 -PsessionsDir=… -PrecordingsDir=…
  ```

  - 종료 코드 0이면 설명되지 않는 차이가 없다. 2면 표의 "설명 없음" 칸(공백 없이 빠진 이벤트)이나 "녹화에 없는 기록 줄"이 있으니 그 날짜와 함께 알린다(전달·기록 경로 결함일 수 있음).
  - 1장 "빠짐"의 원인 칸: 세션 밖(실험실이 늦게 켜졌거나 일찍 꺼짐), 끊김, 재기동, 게이트웨이 큐, 실험실 큐. 2장의 재연결 수가 `feedReconnects`, 1장 실험실 큐 칸이 `inboxDropped`에 해당한다.
  - 3장 계좌 대조는 운용 시작부터 누계다. 게이트웨이 녹화가 빠진 날이 하나라도 있으면 하지 않는다(그날도 게이트웨이를 켜 두면 녹화된다).
  - 장 시간에는 시작하지 않는다(D-063). 읽는 동안 기록이나 녹화 파일이 바뀌면 "아직 쓰이는 중"으로 멈추니 수신이 멈춘 뒤 다시 돌린다.
- 실험실을 재기동한 날은 되살리기에 걸린 시간(시작 요청부터 상태의 `restoring`이 false가 될 때까지)도 적는다. 되살리는 중의 정지 요청은 기록일 하나를 다 읽은 뒤에 반영되므로, 하루치가 길면 그만큼 늦게 멈춘다(QA PR #87 R6).

## 4. 끝내기와 확인(운용 기간이 끝난 뒤, 장 밖에)

1. `Invoke-RestMethod -Method Post -Headers $h -WebSession $web http://127.0.0.1:8480/api/live/finish` → `experiments/<ID>/live-detail.txt`가 생긴다.
2. `./gradlew :autotrade-app-experiment:runLiveReplayCheck -Pexperiment=EXP-20261015-LIVE01`
   - 종료 코드 0이면 실시간 결과와 입력 기록 리플레이가 같다(합격). `inputs.yaml`, `results.md`, `detail.txt`가 등록 파일 옆에 생긴다.
   - 2면 실시간 경로 결함이거나 실패한 계좌가 있다. `results.md`를 보고 알린다.
   - 장 시간에는 시작하지 않는다(D-063). 꼭 필요하면 `-PallowDuringSession`.
3. 결과 세 파일과 `live-detail.txt`는 커밋한다. 입력 기록(설정 폴더)은 커밋하지 않는다(녹화와 같은 재배포 금지).

## 5. 첫 운용일 확인 항목(Windows `[가정]` 확인)

- 게이트웨이 로그에 소켓을 열었다는 줄이 나오는지, 실험실 상태 `feedState`가 `CONNECTED`인지.
- 설정 폴더 `run` 폴더를 다른 사용자 계정이 열 수 없는지(폴더 속성 > 보안).
