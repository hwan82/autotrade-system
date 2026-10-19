# 6단계 시작 점검표(사용자 PC, PowerShell 복사·붙여넣기)

- 계획과 판정 기준: [6단계 계획](../strategy/stage-6-plan.md). 세션 동작 설명은 [6단계 실시간 모의 절차서](stage-6-live-session.md)가 단일 원본이고, 이 문서는 그 절차를 날짜·명령 순서로 묶은 것이다.
- 등록: 시험 운용 `EXP-20261020-LIVE00`(10-20~10-23), 본 운용 `EXP-20261026-LIVE01`(10-26~12-18).
- 저장소 위치는 `C:\2.Code\myproject\autotrade-system`으로 가정한다. 다르면 첫 줄 `cd`만 바꾼다.
- 실전 주문은 없다. 게이트웨이는 시세만 받고 주문 API를 부르지 않는다(D-076). 실전 엔진은 켜지 않는다.
- 키·계좌번호·토큰이 보이는 화면이나 로그는 채팅에 올리지 않는다.

## 0. 모든 창에서 먼저 붙여 넣는 두 줄

```powershell
cd C:\2.Code\myproject\autotrade-system
chcp 65001 > $null; $cfg = if ($env:AUTOTRADE_CONFIG_DIR) { $env:AUTOTRADE_CONFIG_DIR } else { Join-Path $HOME ".autotrade" }
$id = if ((Get-Date) -lt (Get-Date "2026-10-24")) { "EXP-20261020-LIVE00" } else { "EXP-20261026-LIVE01" }
```

`$id`는 날짜로 정한다(10-23까지 LIVE00, 10-24부터 LIVE01). 아래 명령은 이 두 줄을 붙여 넣은 창에서만 동작한다.

## 1. 처음 한 번(LIVE00 시작 전날 또는 첫날 08:30 전)

1. 최신 main 받기와 등록 파일 확인:

   ```powershell
   git switch main; git pull
   Get-Content experiments\EXP-20261020-LIVE00\experiment.yaml -TotalCount 12
   git status --short   # 비어 있어야 결과의 코드 줄에 dirty가 붙지 않는다
   ```

2. 게이트웨이 감시 종목(`watchlist.json`)을 10종목으로 바꾼다. **기존 `candidates`(후보)는 모두 이 10종목으로 덮어쓴다**(2단계 3종목도 이 안에 있다). 원래 파일은 `watchlist.json.before-stage6`로 남기고, `held`(보유) 칸은 그대로 둔다. 게이트웨이는 기동 때만 읽으므로 켜져 있으면 다시 띄운다.

   ```powershell
   $wl = Join-Path $cfg "watchlist.json"
   $codes = "005930","000660","035420","005380","373220","068270","105560","035720","247540","028300"
   $held = @(); if (Test-Path $wl) { Copy-Item $wl "$wl.before-stage6" -Force; $old = Get-Content $wl -Raw | ConvertFrom-Json; if ($old.held) { $held = @($old.held) } }
   $q = { param($x) '"' + $x + '"' }
   $json = '{"held":[' + (($held | ForEach-Object { & $q $_ }) -join ',') + '],"candidates":[' + (($codes | ForEach-Object { & $q "KRX:$_" }) -join ',') + ']}'
   [IO.File]::WriteAllText($wl, $json, (New-Object Text.UTF8Encoding $false))
   Get-Content $wl
   ```

3. 소속부 확인(등록 파일의 KOSPI·KOSDAQ이 맞는지). 종목 마스터를 받는다(키 불필요).

   ```powershell
   .\gradlew :autotrade-app-gateway:symbolMaster
   Import-Csv (Join-Path $cfg "symbols\KRX.csv") | Where-Object { $codes -contains $_.code } | Format-Table code, name, board
   ```

   10줄이 나오고 board가 아래와 같으면 된다. 다르면 시작하지 말고 그 표를 스레드에 올린다(등록 파일을 고쳐 다시 커밋한다).
   `005930 000660 035420 005380 373220 068270 105560 035720` = KOSPI, `247540 028300` = KOSDAQ.

4. 디스크 여유(설정 폴더 드라이브). 8주 동안 녹화와 입력 기록이 함께 쌓인다. LIVE00에서 하루 크기를 재고 8주 분량을 다시 계산한다.

   ```powershell
   $drive = (Get-Item $cfg).PSDrive.Name; "{0}: 여유 {1:N0}GB" -f $drive, ((Get-PSDrive $drive).Free / 1GB)
   ```

5. 게이트웨이 설치(코드를 받을 때마다 다시):

   ```powershell
   .\gradlew :autotrade-app-gateway:installDist
   ```

## 2. 매 거래일

### 2.1 08:30~08:40 시작(창 세 개)

창 1 — 게이트웨이(15:40 뒤 이 창에서 Ctrl+C):

```powershell
.\autotrade-app-gateway\build\install\autotrade-gateway\bin\autotrade-gateway.bat
```

로그에 `시세 배분`과 `실험실 시세 전달 소켓을 열었습니다`가 나와야 한다. `열지 못해 녹화만 합니다`가 나오면 [절차서](stage-6-live-session.md) 2장 1번을 본다(녹화는 그대로 된다).

창 2 — 실험실(화면 `http://127.0.0.1:8480/#/live`):

```powershell
.\gradlew :autotrade-app-lab:bootRun
```

창 3 — 세션 시작(0장 두 줄 먼저). 실험실을 다시 띄운 뒤에도 이 블록을 다시 붙여 넣는다(실험실은 세션을 자동으로 다시 열지 않는다).

```powershell
$s = Invoke-RestMethod http://127.0.0.1:8480/api/session -SessionVariable web
$h = @{ "X-CSRF-Token" = $s.csrfToken }
$t0 = Get-Date
Invoke-RestMethod -Method Post -Headers $h -WebSession $web "http://127.0.0.1:8480/api/live/sessions/$id/start"
```

### 2.2 상태 확인(시작 직후와 장중 아무 때나)

```powershell
$st = Invoke-RestMethod http://127.0.0.1:8480/api/live/status
$st | Select-Object experimentId, restoring, feedState, feedReconnects, inboxDropped, stopReason | Format-List
$st.session | Select-Object events, dataGaps, lastEventAt, journalDate | Format-List
$bad = @($st.session.failures | Where-Object { $_ }); "실패 계좌: $($bad.Count)"; $bad
```

- `restoring`이 `False`로 바뀐 뒤 `feedState`가 `CONNECTED`, 장중에 `events`가 늘면 정상이다.
- 되살리기 시간(재기동한 날만 적는다): `restoring`이 `False`가 된 뒤 `"{0:N0}초" -f ((Get-Date) - $t0).TotalSeconds`. 실험실 로그 `실시간 모의 세션 연결: … 되살리기 N초` 줄과 같은 값이다.
- **바로 알릴 것**: `실패 계좌`가 0이 아님(계좌 실패, 계획 4.1 I1. `failures`는 계좌마다 한 칸이고 정상 계좌는 빈 칸이라 개수로 본다), `stopReason`에 `시작 실패`·`호가 단위`·`기록` 문구, 장중인데 `events`가 몇 분째 그대로.

### 2.3 15:40 뒤 마감

1. 창 1(게이트웨이)에서 Ctrl+C. 정상 종료해야 녹화 `meta.json`에 정상 종료 표시가 남는다.
2. 실험실은 켜 두어도 되고 꺼도 된다(끄면 다음 날 2.1 창 2·3을 다시). 단 **운용 마지막 날(10-23, 12-18)은 끄지 않는다**. 끝내기(4장)는 세션이 열려 있어야 한다.
   - LIVE00 기간(10-20~10-23)에는 하루 한 번 장중(예: 11시)에 창 2에서 Ctrl+C로 실험실을 끄고 다시 `bootRun` → 창 3 블록 → 2.2에서 되살리기 초를 적는다. 재기동 되살리기를 8주 전에 재 보려는 것이다(계획 3장).
3. 하루 크기(LIVE00 동안 매일, 그 뒤로는 금요일만):

   ```powershell
   $d = (Get-Date).ToString("yyyy-MM-dd")
   foreach ($p in @("recordings\KRX\$d", "live-sessions\$id\journal\KRX\$d")) { $f = Join-Path $cfg $p; if (Test-Path $f) { "{0}: {1:N0}MB" -f $p, ((Get-ChildItem $f -Recurse -File | Measure-Object Length -Sum).Sum / 1MB) } }
   ```

## 3. 주간 점검(매주 금요일 장 마감 뒤, 게이트웨이를 끈 다음)

```powershell
$gc = Join-Path (Get-Location) "build\weekly-gc"; New-Item -ItemType Directory -Force $gc > $null   # 저장소 경로는 공백·한글이 없어야 한다(0장 가정)
$env:JAVA_TOOL_OPTIONS = "-Xlog:gc:file=$gc\weekly-gc-%p.log"
$t0 = Get-Date
.\gradlew :autotrade-app-experiment:runLiveWeeklyCheck -Pexperiment=$id
"종료 코드: $LASTEXITCODE"
"걸린 시간: {0:N0}분" -f ((Get-Date) - $t0).TotalMinutes
Remove-Item Env:JAVA_TOOL_OPTIONS
$max = 0; Get-ChildItem "$gc\weekly-gc-*.log" | Get-Content | ForEach-Object { if ($_ -match '(\d+)M->\d+M\(\d+M\)') { $max = [Math]::Max($max, [int]$matches[1]) } }
"주간 점검 최대 힙 사용: ${max}MB"; Remove-Item "$gc\weekly-gc-*.log"
Get-ChildItem "experiments\$id\weekly"
```

- 종료 코드 0: 설명되지 않는 차이가 없다. 2: "설명 없음"이 있다(계획 4.1 I4, 바로 알린다). 4: 장 시간 보호·정지 요청(장 마감 뒤에 다시).
- 걸리는 시간: 매주 운용 시작일부터 다시 읽어 주마다 길어진다. 첫 주 약 20분, 8주차 약 2~3시간 `[추론]`(계획 7장, D-115). 힙은 기간과 상관없다. 도중에 끄지 말고 PC가 잠들지 않게 둔다.
- `OutOfMemoryError`로 멈추면 D-115 뒤에는 예상 밖이다. `-Pheap=8g`를 붙여 한 번 더 돌리고 결과와 함께 알린다. 입력 기록과 녹화는 디스크에 남으므로 고친 뒤 몰아서 돌릴 수 있다.
- 결과 `experiments\<ID>\weekly\<날짜>.md`와 위 세 줄(종료 코드, 걸린 시간, 최대 힙)을 이 프로젝트 스레드에 올린다. Claude가 커밋하고 요약한다. 최대 힙 줄에는 Gradle 자체 메모리도 섞여 있어 조금 크게 나온다.

## 4. LIVE00 끝(10-23 금요일 장 마감 뒤, 3장 주간 점검 다음)

끝내기는 실험실에 세션이 열려 있고 되살리기가 끝난 상태에서만 된다(아니면 409 오류, `live-detail.txt`가 생기지 않아 리플레이 확인을 못 한다). 실험실을 껐다면 먼저 2.1 창 2(실험실)와 창 3(세션 시작)을 다시 하고, 아래 첫 블록이 `준비됨`을 낼 때까지 기다린다.

```powershell
do { Start-Sleep 5; $st = Invoke-RestMethod http://127.0.0.1:8480/api/live/status; "restoring=$($st.restoring) 세션=$($st.experimentId) 멈춘 이유=$($st.stopReason)" } while (($st.restoring -or -not $st.session) -and -not $st.stopReason)
if ($st.stopReason) { "멈춤: $($st.stopReason) — 끝내지 말고 이 줄을 스레드에 올린다" } else { "준비됨" }
```

```powershell
$s = Invoke-RestMethod http://127.0.0.1:8480/api/session -SessionVariable web
$h = @{ "X-CSRF-Token" = $s.csrfToken }
Invoke-RestMethod -Method Post -Headers $h -WebSession $web http://127.0.0.1:8480/api/live/finish
.\gradlew :autotrade-app-experiment:runLiveReplayCheck -Pexperiment=EXP-20261020-LIVE00
"종료 코드: $LASTEXITCODE"
Get-ChildItem experiments\EXP-20261020-LIVE00
```

- 종료 코드 0이면 실시간 결과와 입력 기록 리플레이가 같다(합격). 2면 실시간 경로 결함이나 실패 계좌다.
- 올릴 것: `experiments\EXP-20261020-LIVE00\` 안의 `live-detail.txt`, `inputs.yaml`, `results.md`, `detail.txt`, `weekly\2026-10-23.md`, 그리고 메모(날마다 녹화·입력 기록 MB, 되살리기 초, 주간 점검 최대 힙, 소켓·`feedState` 이상 여부). 입력 기록·녹화 파일 자체는 올리지 않는다(재배포 금지).
- 이어서 녹화 판정을 한 번 돌려 걸리는 시간을 잰다(D-116). LIVE00은 4일이라 짧게 끝나며, 이 시간으로 LIVE01(40일) 판정 시간을 다시 추정한다. 판정 내용은 성과 판단에 쓰지 않는다(LIVE00은 성과 판정 안 함).

```powershell
$t0 = Get-Date
.\gradlew :autotrade-app-experiment:runLiveJudgement -Pexperiment=EXP-20261020-LIVE00
"종료 코드: $LASTEXITCODE"
"걸린 시간: {0:N0}분" -f ((Get-Date) - $t0).TotalMinutes
```

- 종료 코드 0이면 판정 파일 `judgement.md`가 생긴다. 2면 무사고 I1~I3이 깨진 것이라 바로 알린다. 1·3·4면(입력 오류·취소·장 시간 보호) 출력 마지막 줄을 올린다. 위 두 줄(종료 코드, 걸린 시간)과 `judgement.md`도 함께 올린다.
- 이 결과로 LIVE01 하루 디스크·메모리를 다시 계산한다. 디스크가 모자라면 10-26 전에 LIVE01 등록을 고친다(계획 2.2 되돌릴 조건).

## 5. LIVE01(10-26 월요일부터 12-18까지)

- 매 거래일 2장, 매주 금요일 3장. 11-19(목)은 수능일이라 10:00 개장·16:30 마감이다. 게이트웨이는 평소처럼 08:40 전에 켜고 16:40 뒤에 끈다.
- PC를 못 켜는 날이 있으면 그날을 스레드에 적는다(인프라 공백은 사고가 아니다. 40거래일 중 36일 이상이 필요하다, 계획 4.1 I5).
- 12-18 장 마감 뒤: 3장 주간 점검 → 4장과 같은 순서(세션이 열려 있는지 먼저 확인)로 끝내기·리플레이 확인(`-Pexperiment=EXP-20261026-LIVE01`) → 4장 녹화 판정 블록을 `-Pexperiment=EXP-20261026-LIVE01`로(약 1.5~2시간 `[추론]`, 4코어 기준, PC가 잠들지 않게. 하룻밤 약 12시간을 넘기면 멈추지 말고 걸린 시간을 알린다: D-116 되돌릴 조건대로 묶음 수 상한을 올리거나 대조군 레인 결과를 다시 쓰게 고친다) → 결과 파일과 `judgement.md`를 올린다. 판정 리포트는 Claude가 쓴다.

## 6. 하지 말 것

- 운용 중 등록 파일(`experiment.yaml`)이나 `strategies/` 프로필 파일을 고치지 않는다(사전 등록).
- `-PallowDuringSession`을 주간 점검·리플레이 확인에 붙이지 않는다. 장 마감 뒤에 돌린다.
- 실전 엔진(`autotrade-app-engine`)을 켜거나 실전 계좌 설정을 바꾸지 않는다. 6단계는 모의만이다.
