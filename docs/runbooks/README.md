# 실행 매뉴얼 (사용자 PC, Windows PowerShell)

이 시스템의 프로그램을 **언제, 어떻게 켜고 끄는지, 끝난 뒤 무엇을 보면 되는지** 한 장에 모은 안내다. 세부 절차는 각 절차서가 단일 원본이고, 여기서는 링크로만 잇는다. 절차서와 이 문서가 다르면 절차서를 따른다.

- 대상: `C:\2.Code\myproject\autotrade-system`에서 PowerShell로 실행하는 경우. 명령은 모두 **저장소 루트**에서 친다.
- PowerShell에서는 `./gradlew` 대신 `.\gradlew`(같은 `gradlew.bat`이 돈다)를 써도 된다. 절차서의 `./gradlew ...`는 그대로 붙여 넣어도 동작한다.
- 실행 전에 항상 `git pull`로 main을 받는다.

## 1. 프로그램 한눈에

| 프로그램 | 종류 | 언제 | 시작 | 끄기 | 정상 신호 | 절차서 |
|---|---|---|---|---|---|---|
| 시세 게이트웨이 | 상주 | 녹화·실시간 모의 하는 거래일 08:40 전 ~ 15:40 뒤 | `.\gradlew :autotrade-app-gateway:installDist` 후 `.\autotrade-app-gateway\build\install\autotrade-gateway\bin\autotrade-gateway.bat` | 15:40 뒤 그 창에서 Ctrl+C | 로그 `시세 배분`, `실험실 시세 전달 소켓을 열었습니다`, 장중 `events.jsonl.gz` 크기 증가 | [2단계 녹화](stage-2-real-recording.md), [6단계 실시간 모의](stage-6-live-session.md) 2장 |
| 실험실(화면) | 상주 | 화면을 볼 때, 실시간 모의 운용 중 | `.\gradlew :autotrade-app-lab:bootRun` | 그 창에서 Ctrl+C | 브라우저 `http://127.0.0.1:8480` 열림 | [6단계 실시간 모의](stage-6-live-session.md), [조합 실험](../strategy/combination-experiments.md) 5장 4번 |
| 수집기 | 단발 | 거래일 서울 18:30~21:30, 또는 주말 | `.\gradlew :autotrade-app-collector:run --args="krx-daily ..."` | 끝나면 스스로 종료 | 스냅샷 ID와 품질 리포트 경로 출력 | [수집기](collector.md) |
| 실험 명령 | 단발 | 장 시간 보호 구간 밖(긴 것은 토요일 아침) | `.\gradlew :autotrade-app-experiment:run<종류> -Pexperiment=<실험ID>` | 끝나면 스스로 종료 | `experiments/<실험ID>/`에 `results.md` 등 생성 | 아래 1.1 |
| 녹화 리플레이 점검 | 단발 | 녹화한 날 서울 16:00~18:30(아래 2장) | `.\gradlew :autotrade-app-gateway:replayCheck --args="<날짜> replay-<날짜>.md"` | 스스로 종료 | 종료 코드 0, 저장소 루트에 보고서 | [2단계 녹화](stage-2-real-recording.md) 7번 |
| 키움 일봉 대조표 | 단발 | 게이트웨이를 끈 뒤 | `.\gradlew :autotrade-app-gateway:kiwoomDaily --args="<날짜> kiwoom-daily-<날짜>.md"` | 스스로 종료 | 종료 코드 0 | [2단계 녹화](stage-2-real-recording.md) 8번 |
| 종목명 표 | 단발 | 처음 한 번, 상장 종목이 바뀌었을 때 | `.\gradlew :autotrade-app-gateway:symbolMaster` | 스스로 종료 | `<설정 폴더>\symbols\KRX.csv` 생성. 실험실 다시 시작 | [설정 키](../config/config-keys.md) 종목명 표 |
| 실전 엔진 | 상주 | **지금은 켤 필요 없음**(아래 4장) | `.\gradlew :autotrade-app-engine:run` | Ctrl+C | 로그 `RealTradingGuard 모두 OFF로 시작` | [ADR-006](../adr/ADR-006-real-control-channel.md) |
| 감시 | 단발(현재) | **지금은 켤 필요 없음**(아래 4장) | `.\gradlew :autotrade-app-watchdog:run` | 설정만 읽고 바로 종료 | 종료 코드 0 | [장애 설계](../team/broker-and-fault-design.md) |

- **설정 폴더**: 기본 `C:\Users\<사용자>\.autotrade\`(환경 변수 `AUTOTRADE_CONFIG_DIR`로 바꿈). 증권사 키는 `secrets.json`, 수집 키는 `collector-secrets.json`, 감시 종목은 `watchlist.json`. 형식은 [설정 키](../config/config-keys.md)와 각 절차서 준비 절. 키 값은 저장소·채팅에 올리지 않는다.
- 게이트웨이는 `gradlew run` 대신 설치본(`installDist`)으로 띄운다. `run`으로 띄우면 Ctrl+C 때 정상 종료 처리가 돌지 않아 녹화 메타에 정상 종료 표시가 빠질 수 있다([2단계 녹화](stage-2-real-recording.md) 5번).

### 1.1 실험 명령 종류

| 명령(`:autotrade-app-experiment:`) | 하는 일 | 입력 파일(먼저 커밋) | 절차 |
|---|---|---|---|
| `runCombinationExperiment` | 선정 × 매매지표 조합을 수집 스냅샷 위에서 비교 | `experiments/<ID>/experiment.yaml` | [조합 실험](../strategy/combination-experiments.md) 5장 |
| `runWalkForwardStudy` | 일봉 워크포워드 판정(통과·탈락·보류) | `experiments/<ID>/study.yaml` | [4단계 워크포워드](stage-4-walkforward.md) |
| `runRecordingExperiment` | 장중 프로필을 실녹화 위에서 실행 | `experiments/<ID>/experiment.yaml`(`input: recording`) | [D-069](../decisions/D-069-session-phases-recording-experiment.md) |
| `runLiveReplayCheck` | 실시간 모의 세션 결과를 리플레이와 대조 | 끝낸 세션의 `live-detail.txt` | [6단계 실시간 모의](stage-6-live-session.md) 4장 |
| `runStrategyStudy` | 합성 시세 비교(4단계 첫 실험 재현용) | 없음 | `autotrade-app-experiment/build.gradle.kts` 주석 |

옵션: `-PallowDuringSession`(다섯 명령 모두, **실전 엔진과 게이트웨이를 모두 끈 날에만**, 장 시간 보호만 풀림), `-Pheap=8g`(메모리, `runStrategyStudy` 제외), `-PverifyDeterminism`(전체를 한 번 더 돌려 결과가 같은지 확인, 시간 두 배 이상. 조합·녹화·워크포워드 실험만).

## 2. 하루 흐름 (거래일 기준, 서울 시각)

| 시각 | 할 일 |
|---|---|
| 08:00 전 | `git pull`. 장 시간 보호 구간이 08:00에 시작하므로 수집·실험 명령은 이 전에 끝나 있어야 한다 |
| 08:40 전 | 게이트웨이 시작(녹화·실시간 모의를 하는 날). 실시간 모의를 하면 실험실을 띄우고 세션 시작 |
| 장중 | 게이트웨이 창을 닫지 않는다. 실험실 `#/live` 화면에서 상태 확인 |
| 15:40 뒤 | 게이트웨이 Ctrl+C |
| 16:00~18:30 (수능일 등 늦춘 날은 17:00~18:30) | 녹화한 날은 `replayCheck`. 빌드·`installDist` 같은 반영 작업도 이 시간에 한다. 빌드·`installDist`는 장 시간 보호를 스스로 확인하지 않는다([수집기](collector.md) 3.6의 2·6번). `replayCheck`는 장 시간 보호와 정지 요청 파일을 확인해 구간 안이면 종료 코드 4로 시작하지 않지만(D-085), 관문 앞에서 Gradle 컴파일이 돌므로 보호 구간에는 시작하지 않는다 |
| 18:30~21:30 | 수집기 `krx-daily` → `dart-financials`(서머타임이 아니면 22:30 전까지) |
| 주말 | 오래 걸리는 실험 명령(토요일 아침 시작 추천) |

**장 시간 보호 구간**(이 시간에는 수집기·실험 명령이 시작하지 않고, 돌던 중이면 종료 코드 4로 멈춘다): 국내 08:00~16:00, 미국 서머타임 중 21:30~05:30·아닐 때 22:30~06:30(미국 서머타임은 2026-11-01에 끝남). 정확한 규칙과 예외는 [수집기](collector.md) 3.5.

## 3. 끝난 뒤 읽는 법 (종료 코드)

`gradlew`로 돌리면 프로그램의 종료 코드가 0이 아닐 때 Gradle이 `Process 'command ...' finished with non-zero exit value N`을 출력하고 실패로 끝난다. **이 줄의 N이 프로그램의 종료 코드**다(PowerShell의 `$LASTEXITCODE`는 Gradle 자신의 값이라 1로 나온다). 설치본(`.bat`)으로 직접 돌렸으면 `$LASTEXITCODE`가 곧 종료 코드다.

| 프로그램 | 0 | 1 | 2 | 3 | 4 | 원본 |
|---|---|---|---|---|---|---|
| 수집기 | 완료 | 오류(키·형식·네트워크) | 사용법 오류 | 하루 호출 한도. **다음 날 같은 명령 다시** | 장 시간 보호·정지 요청·디스크 부족. **구간이 끝난 뒤 같은 명령 다시** | [수집기](collector.md) 4장 |
| 실험 명령 | 완료 | 입력 오류(같은 실험 ID가 이미 실행 중일 때 포함, D-089) | Run 실패나 결정론 불일치(결과를 판정에 쓰지 않음) | 실험실에서 취소(결과 파일 없음, D-089) | 장 시간 보호·정지 요청으로 멈춤(결과 파일 없음) | `ExperimentMain` 주석, [4단계 워크포워드](stage-4-walkforward.md) 5장 |
| `runLiveReplayCheck` | 실시간과 리플레이 같음(합격) | 입력 오류 | 실시간 경로 결함이나 실패 계좌 | 실험실에서 취소 | 장 시간 보호·정지 요청 | [6단계 실시간 모의](stage-6-live-session.md) 4장 |
| `replayCheck` | 두 번 같고 녹화 완전 | 두 결과 다름 | 녹화를 읽지 못함·입력 오류 | 같지만 녹화 불완전(다른 날 다시 녹화) | 장 시간 보호·정지 요청으로 멈춤(보고서 없음, D-085). **구간이 끝난 뒤 같은 명령 다시** | [2단계 녹화](stage-2-real-recording.md) 7번 |
| `kiwoomDaily` | 표 작성 | - | 설정·인자 오류나 조회 실패 | - | - | `KiwoomDailyMain` 주석 |
| `symbolMaster` | 성공 | 받은 파일 검증 실패(파일을 쓰지 않음) | 내려받기·읽기·쓰기 실패(기존 `KRX.csv`는 그대로) | - | - | `SymbolMasterUpdate` 주석 |
| 게이트웨이·실전 엔진 | - | 기동 실패(로그의 ERROR 줄을 본다) | - | - | - | `GatewayMain`, `EngineMain` |

게이트웨이·실전 엔진을 Ctrl+C로 끄면 종료 코드가 0이 아니다(Linux JDK 21 실측 130, Windows는 확인 전 `[추론]`). 정상 종료는 종료 코드로 보지 않고, 게이트웨이는 녹화 `meta.json`의 정상 종료 표시(또는 `replayCheck`가 3이 아닌지)로, 실전 엔진은 로그로 본다.

0이 아니면 출력 마지막 20줄쯤을 이 스레드에 붙여 준다. 키·토큰·계좌번호가 보이면 지우고 붙인다.

## 4. 실전 엔진·감시·실전 활성화 (설명만)

- **지금 단계에서는 실전 엔진과 감시를 띄울 필요가 없다.** 실전 엔진은 띄우면 설정의 실전 계좌를 모두 OFF로 시작하고 실전 제어 명령 채널만 연다. 증권사 주문 연동은 7단계 작업이라 아직 없어서, 실험실 `#/real`(실전 운영) 화면에서 활성화를 끝까지 시도해도 **ACTIVATING → OFF로 돌아온다**([ADR-006](../adr/ADR-006-real-control-channel.md) 2.6). 감시는 현재 설정만 읽고 종료한다(하트비트 감시·긴급 명령은 risk-ops 후속 작업).
- 실전 엔진은 재기동할 때마다 모든 계좌가 OFF로 시작하고, 설정 어디에도 이것을 바꾸는 키가 없다(프로젝트 지침 4장 1번, [ADR-002](../adr/ADR-002-real-trading-guard.md)).
- **실전 활성화는 7단계에서 다음이 모두 끝난 뒤에만 가능하다**: 증권사 주문 연동 구현과 risk-ops 검토, 장애 테스트 8종 합격([장애 설계](../team/broker-and-fault-design.md)), 승격 후보 정리, 독립 QA 판정, 그리고 사용자 결정(지침 10장). 활성화는 어떤 경우에도 자동으로 진행하지 않는다.
- 그때의 화면 절차(참고): 운영자 비밀번호 해시를 만들어 `secrets.json`에 넣고 → 실전 엔진과 실험실을 다시 시작 → `#/real`에서 계좌별 "실전 활성화"를 누르고 2차 확인(계좌 별칭 그대로 입력 + 운영자 비밀번호)을 통과해야 한다. 5번 연속 실패하면 15분 잠긴다. 세부는 [ADR-006](../adr/ADR-006-real-control-channel.md) 2.4·2.7과 [설정 키](../config/config-keys.md) `real-trading.operator-password-hash`.
  - 비밀번호 해시는 **설치본을 터미널에서 직접** 실행해 만든다(`gradlew run`으로는 비밀번호 입력 창이 없어 종료 코드 2로 끝난다): `.\gradlew :autotrade-app-engine:installDist` 후 `.\autotrade-app-engine\build\install\autotrade-engine\bin\autotrade-engine.bat hash-password`. 비밀번호는 15자 이상, 출력된 해시만 `secrets.json`에 넣는다.
- **내리기와 PANIC SELL의 보호 모드 고정**은 비밀번호 없이도, 잠금 중에도 된다(ADR-006 2.4·2.5). 내리기는 Guard를 OFF로 만들지 않는다: 열린(OPEN) 계좌는 **보호 모드(PROTECTIVE)**가 되어 신규 진입만 막히고 보유 손절·서버 측 손절은 계속 돌며, 활성화 중(ACTIVATING)이면 원래 상태로 돌아간다. 보호 모드 계좌는 보유·미체결이 0이 되면 OFF가 된다. 보유가 남은 채 OFF가 되는 길은 PANIC SELL 증권사 단계(미체결 취소, 서버 측 손절 취소, 매도, 체결 확인)를 모두 마치는 것과 킬스위치뿐이고, 엔진 재기동 때도 모든 계좌가 OFF로 시작한다([ADR-002](../adr/ADR-002-real-trading-guard.md) 2.1, ADR-006 2.5).

## 5. 하지 말 것

- 작업 관리자에서 `java.exe`를 골라 끝내지 않는다. 실전 엔진·게이트웨이·감시도 같은 `java.exe`로 돈다. 멈출 때는 **그 명령의 창에서 Ctrl+C**, 예약 작업이면 작업 스케줄러에서 그 작업만 끝낸다([수집기](collector.md) 3.6의 2번).
- 감시의 정지 요청 파일(`<설정 폴더>\collector-state\stop-request`, `<설정 폴더>\experiment-state\stop-request`)을 직접 지우지 않는다. 감시만 지운다(D-058, D-063). 오래 남아 있으면 [수집기](collector.md) 3.5 마지막 항목을 본다.
- 수집기 힙(2GB)을 `JAVA_OPTS`로 올리지 않는다. 메모리가 모자라면 기간(`--from`)을 줄이고 메시지를 스레드에 붙인다.
- `-PallowDuringSession`·`--allow-during-session`을 `gradle.properties`에 넣어 두지 않는다. 실전 엔진(과 실험이면 게이트웨이)을 끈 날, 그 실행에만 붙인다.
- 등록 파일(`experiment.yaml`, `study.yaml`)을 결과를 본 뒤 고치지 않는다. 바꾸려면 새 실험 ID.
- 녹화 파일, 수집 원자료, `detail.txt`, 실시간 세션 입력 기록은 저장소·채팅에 올리지 않는다(재배포 금지, D-050·ADR-003).

## 6. 막혔을 때

- 처음 설치(Java 21, Git, 저장소 받기)는 저장소 밖 프로젝트 공유 폴더의 `guides/stage-2-recording-guide.md` 1단계에 그림처럼 풀어 두었다.
- 증권사 키 거절·연결 실패: [2단계 녹화](stage-2-real-recording.md) "실패했을 때"와 5번(키움 `return_code` 뜻).
- 수집 오류(`HTTP 401/403` 등): [수집기](collector.md) 2장.
- 실시간 모의가 붙지 않음(`feedState`, `stopReason`): [6단계 실시간 모의](stage-6-live-session.md) 2장 4·5번, 5장.
- 그 밖에는 명령, 종료 코드, 출력 마지막 부분을 프로젝트의 "프로그램 실행·동작 매뉴얼" 스레드에 붙여 묻는다.
