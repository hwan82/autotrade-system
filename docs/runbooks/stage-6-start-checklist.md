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

게이트웨이 창은 하나 더 확인한다. bat 실행 파일은 `JAVA_HOME`의 Java를 쓰고 프로그램은 Java 21로 빌드되어 있다. 아래 출력의 버전 번호가 `21`이 아니거나 명령이 오류를 내면(`JAVA_HOME`이 비어 있음) 1장 0번을 먼저 한다(`UnsupportedClassVersionError ... class file version 65.0 ... up to 61.0`이 그 증상이다).

```powershell
& "$env:JAVA_HOME\bin\java" -version
```

## 1. 처음 한 번(LIVE00 시작 전날 또는 첫날 08:30 전)

0. Java 21을 `JAVA_HOME`으로 둔다(실험실·주간 점검은 Gradle이 21을 직접 찾지만, 게이트웨이 bat은 `JAVA_HOME`이나 PATH의 Java를 쓴다). 아래 첫 줄 출력에서 `Language Version: 21` 항목의 `Location`을 복사해 둘째 줄에 넣는다. 사용자 환경 변수라 **새로 여는 창부터** 적용된다(지금 창은 셋째 줄로 바로 적용).

   ```powershell
   .\gradlew -q javaToolchains
   [Environment]::SetEnvironmentVariable("JAVA_HOME", "여기에 Location 경로", "User")
   $env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User"); & "$env:JAVA_HOME\bin\java" -version
   ```

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

창 2 — 실험실(화면 `http://127.0.0.1:8480/#/live`, 계좌 ID를 누르면 체결 내역·거절 사유를 최근 것부터 본다. D-120이 들어간 코드로 띄운 뒤부터. 끝낸 세션은 `live-detail.txt`의 `fill` 줄):

```powershell
.\gradlew :autotrade-app-lab:bootRun
```

창 3 — 세션 시작(0장 두 줄 먼저). 실험실을 다시 띄운 뒤에도 이 블록을 다시 붙여 넣는다(실험실은 세션을 자동으로 다시 열지 않는다).

```powershell
do { try { $s = Invoke-RestMethod http://127.0.0.1:8480/api/session -SessionVariable web; $ok = $true } catch { "실험실 기다리는 중..."; $ok = $false; Start-Sleep 5 } } until ($ok)   # 창 2가 다 뜰 때까지(처음엔 1~3분)
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
3. 하루 크기(LIVE00 동안 매일, 그 뒤로는 금요일만). 입력 기록은 실험실을 끈 뒤 잰다. 실험실이 파일을 열어 둔 동안에는 Windows가 크기를 늦게 갱신해 0MB로 보일 수 있다(2026-10-21 실측). 실험실을 끄지 않는 마지막 날은 녹화 크기만 본다:

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

## 4.1 감시 종목 넓히기(10-24~25 주말, LIVE01 시작 전, 사용자 결정 2026-10-20)

단타 선정(ADR-012) 시험용 녹화를 위해 게이트웨이 감시 종목을 **LIVE01 10종목 + 최근 20거래일 평균 거래대금 상위 주식(`STOCK`, 우선주 포함), 모두 100종목**으로 넓힌다(배치표 0번). LIVE01 `boards` 10종목은 그대로 받으므로 LIVE01 계좌·입력 기록은 바뀌지 않는다 `[추론]`(실험실은 `boards` 밖 종목을 기록하지 않고 세기만 한다, ADR-010). 100은 키움이 체결·호가를 종목마다 따로 세도(100 × 2 = 200) 세션 한도 200 안에 드는 값이다(`KiwoomStreamProtocol` 종목 단위 계산은 `[가정]`). 넓히는 이유와 크기는 [ADR-012](../adr/ADR-012-intraday-selection.md) 6장 0번.

1. 최신 일봉 스냅샷이 있어야 한다. 금요일(10-23) 저녁이나 주말에 평소 수집(`krx-daily`)을 한 번 돌린 뒤 한다. 아래 첫 줄이 스냅샷 폴더 이름을 보여 준다.

   ```powershell
   git pull
   $snap = (Get-ChildItem (Join-Path $cfg "snapshots\krx-daily") -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name; $snap
   $keep = "005930,000660,035420,005380,373220,068270,105560,035720,247540,028300"
   $cand = Join-Path $cfg "watchlist-candidates.txt"
   .\gradlew :autotrade-app-experiment:runWatchlistCandidates "-Psnapshot=$snap" "-Pkeep=$keep" -Ptotal=100 "-Pout=$cand"
   "종료 코드: $LASTEXITCODE"
   ```

   - 출력의 `선정 기준일`이 10-23(또는 그 앞 거래일)이고 `후보 100종목`이면 된다. 종료 코드 4면 장 시간 보호 구간이니 주말에 다시 돌린다. 1이면 마지막 줄을 스레드에 올린다.
   - 이 명령은 `watchlist.json`을 바꾸지 않는다. 목록 파일만 쓴다.
   - 우선주(코드 끝자리가 0이 아닌 종목, 대개 5·7·9)가 몇 개 들었는지 함께 본다: `(Get-Content $cand | Where-Object { $_ -notmatch '0$' }).Count`. 그 숫자를 스레드에 올리면 D-122에 적는다(목록은 그대로 쓴다).

2. `watchlist.json`을 이 목록으로 바꾼다. 지금 파일(LIVE00 10종목)은 `watchlist.json.before-wide`로 남기고, `held`(보유) 칸은 그대로 둔다.

   ```powershell
   $wl = Join-Path $cfg "watchlist.json"
   Copy-Item $wl "$wl.before-wide" -Force; $old = Get-Content $wl -Raw | ConvertFrom-Json; $held = @(); if ($old.held) { $held = @($old.held) }
   $codes = Get-Content $cand | Where-Object { $_ -match '^[0-9A-Z]{6}$' }
   $q = { param($x) '"' + $x + '"' }
   $json = '{"held":[' + (($held | ForEach-Object { & $q $_ }) -join ',') + '],"candidates":[' + (($codes | ForEach-Object { & $q "KRX:$_" }) -join ',') + ']}'
   [IO.File]::WriteAllText($wl, $json, (New-Object Text.UTF8Encoding $false))
   "후보 {0}종목, LIVE01 10종목 포함 {1}" -f $codes.Count, (($keep -split ',') | Where-Object { $codes -contains $_ }).Count
   ```

   `후보 100종목, LIVE01 10종목 포함 10`이 나와야 한다.

3. 디스크: 녹화가 대략 종목 수에 비례해 늘어난다 `[추론]`. LIVE00 하루 녹화 21MB(10종목)면 100종목은 하루 약 200MB, 8주 약 8~9GB다. 1장 4번 명령으로 여유를 보고, 녹화·입력 기록 합계 8주 분량(약 10GB `[추론]`)에 20GB를 더한 것보다 적으면 넓히지 않고 `watchlist.json.before-wide`를 되돌린다(`Copy-Item "$wl.before-wide" $wl -Force`).

4. LIVE01 첫날(10-26) 게이트웨이를 켠 뒤 창 1 로그에 `구독 거절`이 나오면 **09:00 전에** 게이트웨이를 Ctrl+C로 끄고 `Copy-Item "$wl.before-wide" $wl -Force`로 10종목으로 되돌린 뒤 다시 켠다. 그 로그 줄을 스레드에 올린다(키는 나오지 않는다). LIVE01 결과를 지키는 쪽이 먼저다.

   거절 줄 없이 일부 종목만 빠질 수도 있어(risk-ops PR #195 N1), 첫날 **09:30쯤** 새 PowerShell 창에서 LIVE01 10종목이 그날 녹화에 모두 들어왔는지 한 번 본다. 쓰는 중인 파일을 읽기만 한다. 게이트웨이를 다시 켜는 중에는 돌리지 않는다(읽는 몇 초 동안 녹화 파일 이름 바꾸기가 막힐 수 있다, architect PR #195 n2).

   ```powershell
   chcp 65001 > $null; $cfg = if ($env:AUTOTRADE_CONFIG_DIR) { $env:AUTOTRADE_CONFIG_DIR } else { Join-Path $HOME ".autotrade" }
   $keep = "005930,000660,035420,005380,373220,068270,105560,035720,247540,028300"
   $f = Join-Path $cfg ("recordings\KRX\" + (Get-Date -Format "yyyy-MM-dd") + "\events.jsonl.gz"); $seen = @{}
   $r = New-Object IO.StreamReader(New-Object IO.Compression.GZipStream([IO.File]::Open($f, 'Open', 'Read', 'ReadWrite'), [IO.Compression.CompressionMode]::Decompress))
   try { while ($null -ne ($l = $r.ReadLine())) { if ($l -match '"sym":"KRX:(\w{6})"') { $seen[$Matches[1]] = 1 } } } catch { "(쓰는 중인 파일의 끝이라 읽기를 멈춤, 정상)" } finally { $r.Close() }
   "녹화된 종목 수: $($seen.Count)"; $keep.Split(',') | Where-Object { -not $seen.ContainsKey($_) } | ForEach-Object { "안 들어온 LIVE01 종목: $_" }
   ```

   - `녹화된 종목 수`가 100 가까이고 `안 들어온 LIVE01 종목` 줄이 없으면 된다.
   - `안 들어온 LIVE01 종목`이 하나라도 있으면 장중이라도 위와 같이 10종목으로 되돌리고 게이트웨이를 다시 켠다(재기동 공백은 녹화에 `RECORDER_RESTART`로 남는다, D-065). 출력 두 줄을 스레드에 올린다.

5. 넓힌 뒤 늘어나는 부하 두 가지를 본다. 실험실은 게이트웨이가 보내는 KRX 이벤트를 모두 받은 뒤 `boards` 밖 종목을 버리므로 받는 이벤트가 약 10배가 되고(받는 큐 100,000건, `LiveLabService.INBOX_CAPACITY`), 주간 점검·녹화 판정은 그날 녹화 전체를 줄마다 읽은 뒤 `boards`만 고르므로 읽는 줄이 약 10배가 된다(`LiveWeeklyCheck.BoardsRecording`) `[추론]`.
   - 매 거래일 2.2 상태 확인에서 `inboxDropped`가 0이 아니면 그날 줄을 스레드에 올리고, 다음 거래일부터 `Copy-Item "$wl.before-wide" $wl -Force`로 10종목으로 되돌린다.
   - 첫 주간 점검(10-30)의 걸린 시간을 LIVE00 주간 점검(10-23)과 함께 올린다. 녹화 읽기가 늘어난 만큼 길어지며, 8주차 예상이 하룻밤(약 12시간)을 넘으면 Claude가 되돌릴지 정해 알린다.

## 5. LIVE01(10-26 월요일부터 12-18까지)

- 매 거래일 2장, 매주 금요일 3장. 11-19(목)은 수능일이라 10:00 개장·16:30 마감이다. 게이트웨이는 평소처럼 08:40 전에 켜고 16:40 뒤에 끈다.
- PC를 못 켜는 날이 있으면 그날을 스레드에 적는다(인프라 공백은 사고가 아니다. 40거래일 중 36일 이상이 필요하다, 계획 4.1 I5).
- 12-18 장 마감 뒤: 3장 주간 점검 → 4장과 같은 순서(세션이 열려 있는지 먼저 확인)로 끝내기·리플레이 확인(`-Pexperiment=EXP-20261026-LIVE01`) → 4장 녹화 판정 블록을 `-Pexperiment=EXP-20261026-LIVE01`로(약 1.5~2시간 `[추론]`, 4코어 기준, PC가 잠들지 않게. 하룻밤 약 12시간을 넘기면 멈추지 말고 걸린 시간을 알린다: D-116 되돌릴 조건대로 묶음 수 상한을 올리거나 대조군 레인 결과를 다시 쓰게 고친다) → 결과 파일과 `judgement.md`를 올린다. 판정 리포트는 Claude가 쓴다.

## 6. 하지 말 것

- 운용 중 등록 파일(`experiment.yaml`)이나 `strategies/` 프로필 파일을 고치지 않는다(사전 등록).
- `-PallowDuringSession`을 주간 점검·리플레이 확인에 붙이지 않는다. 장 마감 뒤에 돌린다.
- 실전 엔진(`autotrade-app-engine`)을 켜거나 실전 계좌 설정을 바꾸지 않는다. 6단계는 모의만이다.
