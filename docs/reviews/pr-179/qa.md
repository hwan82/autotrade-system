# QA 검토: 녹화 줄 단위 읽기를 marketdata 한 곳에, 게이트웨이 replayCheck도 줄 단위로(D-117)
- 대상: PR #179 / 브랜치 `claude/project-thread-ikus29` / 헤드 커밋 48a03d5 / 병합 기준 main(e5b04df) 대비 diff 전체(커밋 5개, 16파일 +802 −172: 운영 `RecordedDayStream.java` 신규 +255, `RecordedDay.java` +13 −147, `RecordedReplaySource.java` +45 −9, `ReplayCheck.java` +22 −12, 시험 marketdata `RecordedDayStreamTest.java` 신규 +275·gateway `ReplayCheckTest.java` +46, 문서 D-117 신규·D-115·결정 로그·ADR-003 2.4·runbook 2곳·기획서 v14.53·`docs/qa/pending.md`, 검토 원문 `docs/reviews/pr-179/{architect,risk-ops}.md`). GitHub `mergeable_state: dirty`(아래 R1).
- 점검표: A 전체 + B1, B6. `autotrade-marketdata/*`는 보호 경로(`scripts/ci/protected-paths.txt`의 `autotrade-marketdata/*`)라 실전 관련 PR로 본다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 테스트)는 diff에 없다.
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 48a03d5): 1~3회째 의존성 받기 실패(`Could not download kotlin-script-runtime-2.0.21.jar`, plugins.gradle.org, 환경 문제·코드 무관). 20초 간격 재시도 루프 하나, 4회째 **BUILD SUCCESSFUL in 3m 55s, 138 actionable tasks: 138 executed**(미러 init 스크립트 쓰지 않음). 시험 결과 XML 합계 **1,651건, 실패 0, 오류 0, 건너뜀 0**.
  - GitHub CI(헤드 48a03d5, run 39273372999): Gradle build, Lab screens (Playwright), DB schema (PostgreSQL 16), Secret scan and collector boundary 4종 success.
  - **구·신 읽기 차등 시험**(커밋하지 않은 임시 시험, marketdata 시험 폴더에 두고 돌린 뒤 지움): main(e5b04df)의 `RecordedDay.java`를 이름만 바꿔(`QaOldRecordedDay`) 옆에 두고, 무작위 녹화 폴더 **2만 개(시드 0~19,999)**를 만들어 구 `open`과 PR의 `RecordedDay.open`·`RecordedDayStream.read`·`RecordedReplaySource.streaming(dir).replay()`·`events()`·`sourceId()`를 비교했다. 만든 녹화: 줄 0~24개(체결·끊김 공백·재기동 공백), 순번 건너뜀·되돌림, 가운데 줄 반쪽·빈 줄, 마지막 줄 자름, 끝 쓰레기, gzip 한 멤버·두 멤버, 정상 닫힘·sync flush만·바이트 자름·빈 파일·머리만·gzip 아님·끝 덧붙임·비트 뒤집기, 파일 없음, 메타 없음·미완료·정상 종료(해시 맞음·틀림·없음)·형식 버전 2·깨진 메타, 구간 경계 무작위(중복·줄 수 초과 포함).
    - 결과: 받아들인 5,963개는 줄·잘림·메타·건수·공백 수·마지막 순번·표시 없는 재기동 경계·마지막 수신 시각이 **모두 같음**, `Summary`가 `RecordedDay.summary()`와 같고, streaming 소스의 이벤트·요약·소스 이름도 같음. 거부한 14,037개는 **구·신이 모두 거부**(받기/거부 불일치 0). 거부 문구가 다른 경우 412개는 모두 **정상 종료 메타**(해시 있음 277, 해시 없음 135)에서 구 `UncheckedIOException("녹화를 읽을 수 없습니다")` → 신 `IllegalStateException("녹화 N번째 줄 형식 오류")`로, D-117 위험과 한계 1번이 적은 차이와 같다. 메타 없음·미완료 녹화는 문구까지 같음. 이 두 예외를 구분해 잡는 호출처는 없다(`grep "catch (UncheckedIOException|catch (IllegalStateException"` marketdata·gateway·sim 운영 코드 0건).
  - **변이 시험**(운영 코드 한 곳씩 바꾸고 `:autotrade-marketdata:test :autotrade-app-gateway:test :autotrade-sim:test`, 매번 `git checkout`으로 원복, 마지막 `git status` 깨끗). 13개 중 **9개 잡힘**:

| 변이 | 내용 | 결과 |
|---|---|---|
| M1 | `ReplayCheck` 두 읽기 요약 비교 없앰 | 잡힘(`ReplayCheckTest#회차_안에서_녹화가_바뀌면_…`) |
| M2 | 요약 비교를 건수 비교로만 | **생존**(Q1) |
| M3 | 형식 오류 줄 버림을 마지막 줄만이 아니라 모든 줄로(`!complete && last`→`!complete`) | 잡힘(`가운데_줄_형식이_깨지면_…`) |
| M4 | `return gz.cutShort()`→`false` | 잡힘(`RecordingReplayTest#비정상_종료_전_flush한_줄은_…`) |
| M5 | 정상 종료 SHA-256 검사 없앰 | 잡힘(3건) |
| M6 | 공백 수 세기 없앰 | 잡힘(3건) |
| M7 | 마지막 수신 시각을 첫 줄 것으로 | 잡힘(3건) |
| M8 | 줄 없는 새 구간도 표시 없는 경계로 | 잡힘(`표시_없는_재기동_경계와_잘린_마지막_줄을_…`) |
| M9 | 경계 첫 줄이 **아무 공백**이면 표시로 봄(RECORDER_RESTART 조건 뺌) | **생존**(Q1) |
| M10 | 순번 검사 `!=`→`<` | 잡힘(3건) |
| M11 | 읽은 녹화일 소스도 `replay()` 때 디스크를 다시 읽음 | 생존(파일이 그대로면 같은 동작, 판정에 쓰지 않음) |
| M12 | 정상 종료 녹화에서도 gzip 끝 오류를 끝으로 봄(`EndTolerantInputStream.cut`의 `!tolerate` 무시) | **생존**(Q2, 옮긴 코드라 main부터 같음) |
| M13 | 리플레이 요약을 수집 요약 자리에 덮어써 비교가 늘 같게 | 잡힘(`ReplayCheckTest#회차_안에서_…`) |

  - 호출처 확인: `RecordedDay`·`RecordedReplaySource`·`RecordedDayStream`을 쓰는 운영 코드는 marketdata(`DayWriter.java:83,176`), gateway(`ReplayCheck`), sim(자기 복제본 `RecordedDayStream`, `RecordedMarketDataSet`)뿐. `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-risk`·`autotrade-broker-*` 운영 코드에 `marketdata.recording` 사용 0건(엔진은 `autotrade-marketdata`에 의존하지만 `recording` 패키지를 쓰지 않음, `autotrade-app-engine/build.gradle.kts:20`). `.replay()` 운영 호출은 `ReplayCheck.java:249` 하나(sim `LiveCheckMain`의 `replay()`는 다른 타입).
  - `EndTolerantInputStream`은 main `RecordedDay`의 것과 글자 단위로 같음(`diff` 결과 동일).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 Guard·설정 로딩·엔진 파일 없음(`git diff --name-only e5b04df HEAD` 운영 파일은 marketdata 3개·gateway `ReplayCheck.java`뿐). 빌드에서 `RealTradingGuardTest`·`GuardedOrderGatewayTest` 포함 1,651건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 변경 0줄. 새 클래스는 기존 패키지 `com.autotrade.marketdata.recording` 안(소유 표 변경 불필요, `ModuleOwnershipTest` 통과). 새 의존 없음(jackson은 기존 `RecordedDay`가 쓰던 것) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음. 녹화 `seq`는 주문 키와 무관하고, 녹화기 재기동의 `seq` 이어 쓰기 값(`DayWriter.java:83-86` `lastSeq`·`eventCount`·`gapCount`)은 차등 시험 2만 개에서 구 읽기와 같음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진은 바뀐 클래스를 쓰지 않음(위 호출처). `replayCheck`는 별도 JVM 명령이고 장 시간 보호·정지 요청 파일 관문(D-085) 코드 무변경(`ReplayCheck.java:102-129`), 정지 확인은 수집 읽기 5만 줄마다·리플레이 5만 건마다로 그대로(`:229-245`), 기존 정지 횟수 고정 시험 통과. 이전엔 `RecordedDay.open`이 하루를 다 올리는 동안 정지 확인이 없었고 이제 읽는 중에도 확인하므로 멈춤이 늦어지지 않는다. PR의 힙 4GB→128MB 측정은 직접 재측정하지 않았고 판정 근거로 쓰지 않음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절·게이트웨이 실시간 수신·녹화 쓰기(`DayWriter`·`MarketEventRecorder`) 코드 무변경. 녹화기 재기동이 읽는 값은 차등 시험으로 구 동작과 같음 |
| A10 | 통과 | diff 추가 줄에서 `appkey|secret|token|password|계좌번호` 일치는 검토 원문의 설명 문장뿐(값 아님). 시험 픽스처는 합성 종목코드·가격·시각뿐 |
| A11 | 통과 | 보호 시험 삭제·`@Disabled`·약화 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 `RecordingReplayTest`·`ReplayCheckTest`·sim `RecordedDayStreamTest` 그대로 통과. 새 동작에 실패 경우 시험 있음(해시 불일치 시 줄 0개, 형식 버전 거부, 받는 쪽 예외 전파, 순번 끊김, 가운데 줄 형식 오류, 깨진 gzip 머리, 회차 안 변경 시 2·보고서 없음). 변이 13개 중 9개 잡힘, 생존 M2·M9·M12는 Q1·Q2(비차단) |
| A12 | 통과 | 장애 대응표 #9(게이트웨이 크래시 뒤 이어 녹화)는 `DayWriter` 재기동이 `RecordedDay.open`에 기대는데 받기/거부와 값이 구 동작과 같음(차등 시험). #10(자원 고갈, 리플레이 점검 정지 요청·종료 코드 4) 경로 무변경. 바뀐 종료 코드는 아직 쓰이는 녹화의 1·3→2뿐이고 셋 다 "완료 증거 아님"이라 대응표·장애 테스트 8종·O1~O8 전제를 깨지 않음. runbook 2곳(`docs/runbooks/README.md` 표, `stage-2-real-recording.md` 7번)에 반영됨 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 시간대는 메타 `zone` 또는 캘린더 기존 방식(`ReplayCheck.java:236-237`) |
| A14 | 통과 | D-117 결정 1~5가 코드와 맞음(`RecordedDayStream.read/readMeta/Summary`, `RecordedDay.open`이 `read`로 모음 `RecordedDay.java:31-35`, `streaming`·`replay()` 반환형, `ReplayCheck` 두 읽기·요약 비교 `:225-252`, sim 복제본 유지). 결정 로그 표 D-117 행, ADR-003 2.4, 기획서 v14.53 이력 있음. D-117 위험 1번(정상 종료 녹화의 거부 문구 차이)은 차등 시험의 412개와 같은 꼴로 확인 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 4회째 성공, 1,651건 실패 0 |
| B6 | 통과 | 새 동작 시험 marketdata `RecordedDayStreamTest` 10건·gateway `ReplayCheckTest` 1건 추가, 지운 시험·건너뛴 시험 없음(시험 diff에 `-` 줄 없음, XML 건너뜀 0) |

## 점검표 밖 발견
시나리오(잘못된 주문·중복·보호 공백으로 이어질 수 있는가):
- S1 게이트웨이 장중 재기동(대응표 #9): `DayWriter`가 비정상 종료된 그날 녹화를 `RecordedDay.open`으로 읽어 `seq`를 이어 쓴다. 읽기 규칙이 바뀌어 마지막 순번이 달라지면 녹화 순번 중복·공백이 생긴다. → 차등 시험 2만 개에서 받기/거부·마지막 순번·줄이 모두 같음. 결함 없음.
- S2 장 마감 뒤 게이트웨이를 켠 채 그날 `replayCheck`(`-PallowDuringSession`): 한 회차의 두 읽기 사이에 줄이나 `meta.json`이 바뀌면 이제 종료 코드 2, 보고서 없음(`ReplayCheckTest#회차_안에서_…`, M1·M13으로 확인). 결과를 쓰지 않는 쪽이라 안전 쪽. 결함 없음.
- S3 점검 중 정지 요청(대응표 #10): 수집 읽기·리플레이 모두 5만 줄마다 정지 확인, 회차 시작·수집 뒤 확인 그대로. 받는 쪽에서 `StoppedException`이 나면 `RecordedDayStream.read`의 try-with-resources로 파일을 닫고 올라감(`IOException`만 감싸므로 그대로 4). 결함 없음.

권고·정보:
- **Q1(권고, 비차단, 시험 공백)**: 두 읽기 요약 비교가 건수 말고 다른 값(메타·마지막 수신 시각·표시 없는 경계)으로도 바뀜을 잡는지 gateway 시험이 고정하지 않는다(M2 생존: 시험은 마지막 줄을 지워 건수가 바뀌는 경우만 본다). 또 구간 경계 첫 줄이 RECORDER_RESTART가 아닌 공백(DISCONNECTED 등)인 녹화가 시험에 없어, `Counter`가 아무 공백이나 재기동 표시로 보는 변이(M9)가 산다. 이 변이면 `replayCheck` 보고서의 "표시 없는 재기동 경계" 경고가 빠질 수 있다(주문 경로와는 무관). marketdata `RecordedDayStreamTest`에 "경계 첫 줄이 DISCONNECTED 공백이면 표시 없는 경계로 남는다"와 gateway에 "건수는 같고 메타만 바뀌면 2" 시험을 권한다.
- **Q2(권고, 비차단, main부터 같음)**: 정상 종료 녹화에서도 gzip 끝 오류를 끝으로 받아들이는 변이(M12)가 산다. 정상 종료 녹화는 보통 SHA-256 검사(M5로 고정)가 먼저 잡지만, `eventsSha256`이 빈 정상 종료 메타면 잘린 gzip을 조용히 받는 회귀를 시험이 못 잡는다. 코드는 main `RecordedDay`의 것을 글자 그대로 옮겼다. 후속(D-117 sim 정리 PR 등)에서 "정상 종료·해시 없음·gzip 끝 잘림이면 거부" 시험을 권한다.
- R1(머지 전 필요): PR 브랜치가 main(a63dcb1, #181)과 `docs/spec/autotrade-spec.md` 변경 이력 표에서 충돌한다(`git merge-tree` 결과 그 파일 하나, #181의 v14.52 줄과 이 PR의 v14.53 줄). 코드 충돌은 없다. main을 병합해 두 줄을 모두 남기고 CI가 다시 돈 뒤 머지해야 한다.
- R2(정보, 기존): 미완료 녹화에서 gzip 비트가 뒤집혀도 풀린 줄이 형식 검사를 통과하면(차등 시험에서 종목코드 `000660`이 `001660`으로 바뀐 줄) 구·신 모두 정상 줄로 받는다. 미완료 녹화는 CRC 끝 오류를 끝으로 보기 때문이다. 이 PR이 만든 것이 아니고 미완료 녹화는 판정이 "불완전"이지만, 녹화기 재기동은 그런 줄을 그대로 다시 쓴다. 참고로 적는다.
- R3(정보, `[추론]`, 확인 못 함): 줄 단위 읽기로 `replayCheck`가 녹화 파일을 여는 시간이 회차마다 두 번, 하루 크기에 비례해 길어진다(이전엔 하루를 다 올린 뒤 닫음). Windows에서 같은 날 게이트웨이가 재기동하며 `DayWriter.rewrite`의 `Files.move(… REPLACE_EXISTING, ATOMIC_MOVE)`를 하는 순간 점검이 그 파일을 열고 있으면 바꾸기가 실패할 수 있는지는 이 컨테이너(Linux)에서 확인하지 못했다. runbook이 "게이트웨이를 끈 뒤"를 안내하고 이전 코드에도 같은 창이 있었으므로 판정에 쓰지 않는다.

## 판정
승인
