# 6단계 실시간 모의 세션 운용 절차(사용자 PC)

- 근거: [D-076](../decisions/D-076-live-feed-and-session.md), [ADR-010](../adr/ADR-010-live-feed-and-session.md)
- 대상: 장중에 게이트웨이 실시간 시세로 실험실의 모의 계좌 여러 개를 돌리고, 끝난 뒤 리플레이와 같은지 확인한다. 실전 주문은 없다(게이트웨이는 주문 API를 부르지 않음, MD-01).
- 화면은 아직 없다(후속 ui-dev). 아래는 PowerShell 기준 API 호출이다.

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

4. 상태에서 `feedState`가 `CONNECTED`, 장중에 `session.events`가 늘어나는지 본다.

## 3. 운용 중

- 실험실·게이트웨이를 껐다 켜도 된다. 실험실은 세션을 자동으로 다시 열지 않으니 2-3을 다시 한다. 입력 기록으로 계좌를 되살리고 끊긴 구간은 공백으로 남는다.
- 감시 프로세스가 정지 요청 파일을 만들면 세션이 멈춘다(`stopReason`). 요청이 사라진 뒤 다시 시작한다.
- 매일 장 마감 뒤 상태의 `dataGaps`, `inboxDropped`, `feedReconnects`를 주간 점검에 옮긴다.

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
