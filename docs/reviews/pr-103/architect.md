# architect 검토: 실행 매뉴얼 한 장 안내(docs/runbooks/README.md)
- 대상: PR #103 / 커밋 e1cf82d(검토 중 브랜치에 4ba2b0d `docs/reviews/pr-103/risk-ops.md` 추가됨) / `git diff origin/main...HEAD` 전체(origin/main 59e9eb0 기준). 변경 파일: `README.md` +1, `docs/runbooks/README.md` +92(신규), `docs/reviews/pr-103/risk-ops.md`(다른 검토자의 원문이라 내용 판정 대상에서 뺐고, 이 검토 판정에 쓰지 않았다)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 e1cf82d, 작업 트리 깨끗) -> BUILD SUCCESSFUL in 2m 44s, 130 actionable tasks 모두 실행. 테스트 결과 XML 합계 1,101건, 실패 0, 오류 0, 건너뜀 0. (첫 시도 2회는 Maven Central 429 Too Many Requests로 의존성 해석 실패, 코드와 무관. 재시도로 통과.) 4ba2b0d는 검토 원문 md 1개만 더해 빌드 입력이 바뀌지 않는다.
  - 문서 링크 10개가 모두 저장소에 있는지 확인(전부 있음).
  - 문서의 명령·작업 이름·로그 문구·포트·종료 코드·시간 구간을 코드와 대조(아래 근거).
  - Ctrl+C 종료 코드 재현: shutdown hook을 건 최소 Java 프로그램에 SIGINT -> 종료 코드 130(Linux, JDK 21). Windows는 직접 실행 못 함.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 결과(130 tasks 실행, 테스트 1,101건 실패 0) |
| B2 | 해당 없음 | diff에 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(문서 2개와 검토 원문뿐) |
| B3 | 해당 없음 | 계약 변경 없음. 문서가 기존 설정 키(`real-trading.operator-password-hash`, `AUTOTRADE_CONFIG_DIR`)와 Gradle 속성을 인용만 하며 인용은 `docs/config/config-keys.md:31,53`, `autotrade-app-experiment/build.gradle.kts:39-156`와 일치 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음. diff에 테스트 파일 삭제·변경 없음 |
| B7 | 통과 | 코드상 실전 경로 변경은 없지만 문서 4장이 실전 엔진 운용·실전 활성화·내리기·PANIC 절차를 사용자에게 안내하므로 risk-ops 검토 필요. 브랜치에 별도 risk-ops 검토 원문이 있다(4ba2b0d, 그 판정 내용은 이 검토에 쓰지 않음) |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음(정규식 검색: key/secret/token/password, 8자리 이상 숫자, 32자 이상 base64 꼴 -> 설명 문구와 자리표시자만). 설정 폴더 경로는 `<사용자>` 자리표시자 |

### 코드와 맞는 것(대조 근거)
- 게이트웨이 설치본 경로 `autotrade-app-gateway\build\install\autotrade-gateway\bin\autotrade-gateway.bat`: `autotrade-app-gateway/build.gradle.kts:8`(`applicationName = "autotrade-gateway"`). 엔진도 `autotrade-app-engine/build.gradle.kts:9`.
- 로그 문구 `시세 배분`(`MarketHub.java:142`), `실험실 시세 전달 소켓을 열었습니다`(`GatewayMain.java:113`), `RealTradingGuard 모두 OFF로 시작`(`EngineMain.java:98`). 실험실 포트 8480(`autotrade-app-lab/src/main/resources/application.yml:14`).
- Gradle 작업 `replayCheck`·`symbolMaster`·`kiwoomDaily`(`autotrade-app-gateway/build.gradle.kts:29,39,49`), 실험 작업 5종과 `-Pexperiment`·`-PverifyDeterminism`·`-PallowDuringSession`·`-Pheap`(`autotrade-app-experiment/build.gradle.kts:39-156`).
- 종료 코드: 수집기(`CollectorMain.java:35-36`), 실험 명령(`ExperimentMain.java:28`, `CombinationMain`/`RecordingMain`/`WalkForwardMain`/`LiveCheckMain` 주석), `replayCheck`(`ReplayCheck.java:59`), `kiwoomDaily`(`KiwoomDailyMain.java:41`, 키움 실전 계좌가 있으면 코드가 거부 `:77-80`).
- 장 시간 보호 60분 전~30분 뒤(`SessionProtection` `BEFORE_OPEN_MINUTES = 60`, `AFTER_CLOSE_MINUTES = 30`) -> 국내 08:00~16:00, `docs/runbooks/collector.md:51-52`와 일치. 정지 요청 파일 경로(`ExperimentGate.java:31`, `RunGuard.java:16`). 수집기 힙 2g(`autotrade-app-collector/build.gradle.kts:13,33`).
- 비밀번호 15자(`OperatorPasswordHash.java:33`), 콘솔 없으면 종료 코드 2(`EngineMain.java:124-128`), 5회 실패 15분 잠금(`OperatorAuthenticator.java:26-27`), 증권사 연동 전 활성화는 출발 상태로 복귀(`EngineMain.java` `ActivationStepRunner.BROKER_NOT_CONNECTED`, ADR-006 2.6). 감시는 설정만 읽고 0으로 종료(`WatchdogMain.java:18-26`). 화면 경로 `#/real`·`#/live`(`autotrade-ui/src/main/resources/static/js/pages/real.js`, `live.js`).

## 점검표 밖 발견
1. **[수정 필요] Ctrl+C 종료 코드를 0으로 적음** (`docs/runbooks/README.md:64`, 관련 `:54`). 표는 게이트웨이·실전 엔진의 0을 "Ctrl+C로 정상 종료"라고 하고, 54행은 설치본(.bat)이면 `$LASTEXITCODE`가 곧 종료 코드라고 한다. 그러나 두 진입점은 Ctrl+C에서 `System.exit(0)`을 부르지 않고 shutdown hook만 돈다(`GatewayMain.java:67-73`, `EngineMain.java:57-66`). JVM 기본 SIGINT 처리는 128+2=130으로 끝난다 `[사실]`(직접 재현: SIGINT -> 130, Linux JDK 21). Windows도 Ctrl+C를 SIGINT로 받아 같은 값일 것으로 본다 `[추론]`(직접 확인 못 함), 게다가 .bat은 "일괄 작업을 끝내시겠습니까"를 묻는다. 사용자가 이 표대로 읽으면 정상 종료를 실패로, 혹은 0만 정상으로 오해한다. 고칠 방향: 게이트웨이·엔진 행의 0 칸을 "Ctrl+C 뒤 종료 코드는 0이 아닐 수 있다(예: 130). 정상 종료 판단은 게이트웨이는 녹화 `meta.json`의 정상 종료 표시와 `eventsSha256`([2단계 녹화](stage-2-real-recording.md) 6번), 엔진은 로그"로 바꾸고, 1 칸(기동 실패)만 종료 코드로 판단하게 한다.
2. **[수정 필요] "내리기(Guard OFF)" 표기가 코드와 다름** (`docs/runbooks/README.md:75`). 내리기(`DEACTIVATE`)는 OPEN -> PROTECTIVE(보호 모드: 신규 진입 차단, 청산·손절은 계속), ACTIVATING -> 출발 상태다(`RealTradingGuard.java:236-251`, `docs/adr/ADR-006-real-control-channel.md:59`, ADR-002 39행). OFF로 바로 가지 않는다. 운영자가 "내리면 주문이 모두 멈춘다"로 이해하면 보호 모드 청산 주문을 예상하지 못한다. "내리기(OPEN이면 보호 모드로: 신규 진입 차단, 보유분 청산·손절은 계속)"처럼 적는다.
3. **[수정 필요] `symbolMaster` 종료 코드 2 누락** (`docs/runbooks/README.md:63`). 표는 0·1만 있고 2 칸이 `-`다. 코드는 2를 "내려받기·읽기·쓰기 실패"로 쓴다(`SymbolMasterUpdate.java:29-30`, `:63`, `:93`). 종료 코드 안내가 목적인 표에서 빠지면 사용자가 2를 해석할 근거가 없다. 2 칸에 "내려받기·읽기·쓰기 실패(기존 `KRX.csv`는 그대로)"를 넣는다.
4. **[수정 필요] `replayCheck` 실행 시각이 기존 절차서와 어긋남** (`docs/runbooks/README.md:45`, `:17`). 하루 흐름은 "15:40 뒤 게이트웨이 Ctrl+C. 녹화한 날은 `replayCheck`"라 15:40~16:00 국내 보호 구간 안에서 돌리게 읽힌다. 수집기 절차서는 `replayCheck`가 장 시간 보호·정지 요청을 확인하지 않으므로 16:00(수능일 등은 17:00)~18:30에 돌리라고 한다(`docs/runbooks/collector.md:71`). 이 문서가 "절차서와 다르면 절차서를 따른다"(3행)고 해 두었더라도 한 장 안내가 다른 시각을 주면 안 된다. 15:40 행에서 `replayCheck`를 빼고 46행 16:00~18:30 행에 "녹화한 날 `replayCheck`"를 넣는다. 같은 46행은 수능일·옛 시험일의 17:00 예외를 빠뜨렸으니 "(수능일 등 예외는 수집기 3.6의 6번)"을 붙인다.
5. [권고] **"공통 옵션" 범위** (`docs/runbooks/README.md:36`). `-PverifyDeterminism`은 `runLiveReplayCheck`·`runStrategyStudy`에 없고(`autotrade-app-experiment/build.gradle.kts:97-110`, `:143-160`), `runStrategyStudy`는 `-Pheap`도 받지 않는다(`:102` 고정 4g). 붙여도 조용히 무시되어 해는 없지만 "공통"이라 쓰면 결정론 재확인을 했다고 오해할 수 있다. 적용 작업을 적는다.
6. [참고] `docs/runbooks/README.md:88`의 `guides/stage-2-recording-guide.md`(프로젝트 공유 폴더)는 저장소 밖이라 존재와 내용을 확인하지 못했다. 점검표 항목이 아니라 판정에는 넣지 않았다.
7. [참고] PowerShell에서 `./gradlew`·`.\gradlew`가 `gradlew.bat`을 고른다는 6행 설명은 Windows에서 직접 확인하지 못했다 `[가정]`. 기존 절차서들이 같은 명령을 써 왔으므로 판정에는 넣지 않았다.

## 판정
수정 필요(1 Ctrl+C 종료 코드, 2 내리기 표기, 3 symbolMaster 종료 코드 2, 4 replayCheck 시각)

## 재검토 2026-10-14
- 대상: PR #103 / 커밋 3bf034c(지적 반영) / `git diff origin/main...HEAD` 전체(origin/main 59e9eb0 기준, 커밋 4개: e1cf82d, 4ba2b0d risk-ops 원문, 4434d3b 이 파일, 3bf034c). 3bf034c는 `docs/runbooks/README.md` 1파일 +10/-8
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 3bf034c, 작업 트리 깨끗) -> BUILD SUCCESSFUL in 2m 54s, 130 actionable tasks 모두 실행. 테스트 결과 XML 합계 1,101건, 실패 0, 오류 0, 건너뜀 0. (첫 시도는 Maven Central 429로 의존성 해석 실패, 재시도 통과.)
  - 고친 문장마다 코드·ADR과 다시 대조(아래).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 결과 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 변경 없음 |
| B3 | 해당 없음 | 계약 변경 없음. 옵션 범위 설명이 `autotrade-app-experiment/build.gradle.kts:41,56,82,102,126` 및 `runLiveReplayCheck`(`:143-160`, verify 없음)와 일치 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 변경 없음 |
| B7 | 통과 | 문서 4장 실전 운영 안내를 고쳤으므로 risk-ops 검토 필요. 브랜치에 risk-ops 원문 있음(그 판정은 이 검토에 쓰지 않음) |
| B8 | 통과 | 3bf034c diff에 키·토큰·계좌번호 없음(정규식 검색 결과 설명 문구뿐) |

### 앞 지적 처리 확인
1. Ctrl+C 종료 코드: 해결. `docs/runbooks/README.md:64`의 0 칸을 `-`로 바꾸고 `:66`에 "0이 아니다(130 실측, Windows `[추론]`), 게이트웨이는 `meta.json` 정상 종료 표시나 `replayCheck`가 3이 아닌지, 엔진은 로그로 본다"를 더함. `ReplayCheck.java:59`(3 = 녹화 불완전)와 일치.
2. 내리기 표기: 대부분 해결, 새 부정확 1건(아래 발견 1). OPEN -> PROTECTIVE, ACTIVATING -> 원래 상태, 신규 진입만 차단·손절 계속은 `RealTradingGuard.java:236-251`, ADR-002 표(33-39행), ADR-006 2.5 6단계 "보호 모드 유지(손절·서버 측 손절 계속)"와 일치.
3. symbolMaster 종료 코드 2: 해결(`:63`, `SymbolMasterUpdate.java:29-30`).
4. replayCheck 시각: 해결. `:17`, `:45-46`이 16:00~18:30(늦춘 날 17:00~)로 옮겨졌고 `docs/runbooks/collector.md:71`과 일치.
5. [권고였음] 옵션 범위: 반영됨(`:36`), 코드와 일치.
6. 저장소 밖 안내서: "저장소 밖"으로 명시(`:90`). 존재는 여전히 확인 못 함(점검표 항목 아님).

## 점검표 밖 발견
1. **[수정 필요] "Guard OFF는 PANIC SELL의 증권사 단계가 모두 끝났을 때만 된다"가 계약과 다름** (`docs/runbooks/README.md:77`). Guard 계약에는 PROTECTIVE -> OFF 전이가 PANIC 6단계 말고도 있다: 보유·미체결이 0이 되면 `holdingsCleared`(`RealTradingGuard.java:254-262`), 킬스위치 `shutdown`(`:266`, ADR-002 2.1 그림 25-30행 "shutdown: 어떤 상태에서든 OFF (PANIC SELL 마지막 단계, 킬스위치)"), 그리고 엔진 재기동(모든 계좌 OFF로 시작, 같은 문서 4장 둘째 줄). "만"이라고 쓰면 운영자가 보호 모드 계좌가 PANIC 없이는 OFF가 되지 않는다고 오해하고, 문서 안에서도 재기동 OFF 설명과 어긋난다. 고칠 예: "보호 모드 계좌는 보유·미체결이 0이 되면 OFF가 된다. 보유가 남은 채 OFF로 만드는 길은 PANIC SELL 증권사 단계를 모두 마치는 것과 킬스위치뿐이고, 엔진 재기동 때도 OFF로 시작한다(ADR-002 2.1)."

## 판정
수정 필요(재검토 발견 1: 77행 Guard OFF 전이 설명)

## 재검토 2026-10-14 (2차)
- 대상: PR #103 / 커밋 424c0c3(재검토 발견 반영) / `git diff origin/main...HEAD` 전체(origin/main 59e9eb0 기준, 커밋 7개. 코드·문서 본문 변경은 e1cf82d, 3bf034c, 424c0c3이고 나머지는 검토 원문). 424c0c3은 `docs/runbooks/README.md` 1행 변경(+1/-1)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 424c0c3, 작업 트리 깨끗) -> BUILD SUCCESSFUL in 2m 48s, 130 actionable tasks 모두 실행. 테스트 결과 XML 합계 1,101건, 실패 0, 오류 0, 건너뜀 0. (첫 시도는 Maven Central 429로 실패, 재시도 통과.)
  - 바뀐 77행을 `RealTradingGuard.java`와 ADR-002·ADR-006에 다시 대조

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 결과 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 변경 없음 |
| B3 | 해당 없음 | 계약 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 변경 없음 |
| B7 | 통과 | 문서 4장 실전 운영 안내 변경이라 risk-ops 검토 필요. 브랜치에 risk-ops 원문 있음(그 판정은 이 검토에 쓰지 않음) |
| B8 | 통과 | 424c0c3 diff에 비밀값 없음 |

### 앞 재검토 발견 처리 확인
1. Guard OFF 전이 설명: 해결. `docs/runbooks/README.md:77`이 이제 보유·미체결 0이면 OFF(`RealTradingGuard.java:254-262` `holdingsCleared`), 보유가 남은 채 OFF는 PANIC 증권사 단계 완료(ADR-006 2.5 6단계)와 킬스위치(`RealTradingGuard.java:266` `shutdown`, ADR-002 2.1 30행), 재기동 시 모두 OFF(`EngineMain.java:98`, ADR-002)로 적는다. PANIC 증권사 단계 목록(미체결 취소, 서버 측 손절 취소, 매도, 체결 확인)도 ADR-006 2.5 2~5단계와 일치. ADR-002 링크 대상 파일 있음.

앞 두 차례에서 해결 확인한 항목(Ctrl+C 종료 코드, 내리기 표기, symbolMaster 종료 코드 2, replayCheck 시각, 옵션 범위)은 424c0c3에서 바뀌지 않았다(diff 1행).

## 점검표 밖 발견
- 없음. (참고: 77행 "보유가 남은 채 OFF가 되는 길"에 PANIC 완료를 넣은 것은 매도·체결 확인을 마치면 보유가 없어지므로 표현이 엄밀하지는 않지만, 뜻은 "보호 모드에서 OFF로 가는 명시적 경로"로 읽히고 계약과 어긋나지 않아 지적하지 않는다. 저장소 밖 안내서 존재와 Windows에서의 Ctrl+C 종료 코드는 여전히 직접 확인 못 했고, 문서에도 저장소 밖·`[추론]`으로 표시돼 있다. 점검표 항목이 아니다.)

## 판정
승인
