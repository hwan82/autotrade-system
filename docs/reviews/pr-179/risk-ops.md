# risk-ops 검토: 녹화 줄 단위 읽기를 marketdata 한 곳에, 게이트웨이 replayCheck도 줄 단위로(D-117)
- 대상: PR #179 / 커밋 2bcabe6886b2c806ac6687ebe37b1527bc2a96b8 / `git diff origin/main...HEAD` 전체(11개 파일, +658 −170)
- 점검표: A (보호 경로 `autotrade-marketdata/*` 변경, `scripts/ci/protected-paths.txt:14`)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → 종료 코드 0 (컴파일, 전체 테스트, 순수 모듈 검사, ArchUnit 포함)
  - 테스트 결과 XML 집계(실패·오류·건너뜀 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 46, `:autotrade-app-engine:test` 68, `:autotrade-marketdata:test` 148(새 `RecordedDayStreamTest` 7, 기존 `RecordingReplayTest` 23), `:autotrade-app-gateway:test` 51(`ReplayCheckTest` 18), `:autotrade-sim:test` 318(sim `RecordedDayStreamTest` 5 — sim 복제본 대 새 `RecordedDay` 대조)
  - 호출처 확인: `grep -rn "marketdata.recording\|RecordedReplaySource"`를 `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-risk`·`autotrade-broker-*`에 돌려 녹화 읽기 클래스를 쓰는 실전 경로 코드가 없음을 확인(엔진은 marketdata에 의존하지만 `recording` 패키지는 쓰지 않음, `autotrade-app-engine/build.gradle.kts:20`)
  - diff 비밀값 패턴 검색(appkey·secret·token·password·계좌번호 형식): 일치 없음

## 변경 요약(코드로 확인한 것)
- [사실] 읽기 규칙(형식 버전, 정상 종료 녹화 SHA-256, 순번 연속, 비정상 종료 gzip 끝 허용, 잘린 마지막 줄 버림)이 `RecordedDayStream`으로 옮겨졌고(`autotrade-marketdata/.../RecordedDayStream.java:83-101, 158-197`) 구 `RecordedDay.open`의 규칙과 줄 단위로 대조해 같다. 차이는 "원시 줄을 다 모은 뒤 디코드" → "한 줄 앞을 보며 바로 디코드"뿐이고, 잘린 마지막 줄 판정(`:184`)과 `cutShort` 반환(`:196`)은 구 코드와 같은 결과를 낸다.
- [사실] `RecordedDay.open`은 `RecordedDayStream.read`로 목록을 채운다(`RecordedDay.java:31-35`). 예외가 나면 목록을 버리므로 부분 결과가 밖으로 나가지 않는다. 게이트웨이 실시간 녹화 재기동 경로 `DayWriter`(`DayWriter.java:83`)는 그대로 `RecordedDay.open`을 쓰며, 쓰는 값(`lastSeq`·`eventCount`·`gapCount`·`meta`·`lastReceivedAt`·`lines`)의 계산은 바뀌지 않았다. 재기동·이어 쓰기·깨진 꼬리 시험(`RecordingReplayTest`, 미변경)이 통과.
- [사실] `ReplayCheck`는 별도 JVM 명령(`ReplayCheckMain`, `autotrade-app-gateway/build.gradle.kts:34-38`)이고 게이트웨이 상주 프로세스 안에서 돌지 않는다. 회차마다 두 번 읽고 두 요약이 다르면 `IllegalStateException` → 종료 코드 2, 보고서 미작성(`ReplayCheck.java:229-253`, `ReplayCheckTest#회차_안에서_녹화가_바뀌면_결과_없이_실패하고_2를_돌려준다`). 정지 확인(D-085)은 두 읽기 모두 5만 줄마다 유지, 기존 정지 횟수 고정 시험 통과.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 Guard·설정 로딩 파일 없음(`git diff --name-only`에 Guard/risk/engine/architecture 경로 0건). `RealTradingGuardTest`·`GuardedOrderGatewayTest`(autotrade-risk 55건) 통과 |
| A2 | 통과 | 주문 출구 코드 무변경. ArchUnit(`autotrade-architecture-test` 46건) 통과, 해당 모듈 diff 0줄 |
| A3 | 해당 없음 | 주문·증권사 연동 코드를 건드리지 않음. 변경은 녹화 읽기와 오프라인 점검 명령뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 무변경 |
| A5 | 해당 없음 | 멱등 키 생성 경로 무변경. 녹화 `seq`는 주문 키와 무관 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 무변경(`autotrade-risk` diff 0줄, `BlacklistSnapshotTest` 통과) |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 코드 무변경 |
| A8 | 통과 | 실전 엔진은 `marketdata.recording`을 쓰지 않음(위 grep). `replayCheck`는 별도 JVM이고 장 시간 보호·정지 요청 파일 관문(D-085, 5.2 정지 순서 0번)이 그대로(`ReplayCheck.java:110-125`, `ReplayCheckTest` 정지 시험 통과). 점검 힙이 4GB→128MB로 줄었다는 PR 측정은 [추론]으로만 받아들이며(직접 재측정 안 함) 판정에 쓰지 않음. 어느 쪽이든 PC 메모리 압박은 줄어드는 방향 |
| A9 | 해당 없음 | 보호 시세 경로(REST 폴링)·서버 측 손절 코드 무변경. 게이트웨이 실시간 수신 경로(`live.*`) 무변경 |
| A10 | 통과 | diff 비밀값 패턴 검색 0건. 시험 픽스처는 합성 종목코드·가격만 사용(`RecordedDayStreamTest.java`) |
| A11 | 통과 | 보호 테스트 삭제·비활성화·고정 값 변경 없음(`autotrade-architecture-test` diff 0줄). 기존 `RecordingReplayTest`·`ReplayCheckTest` 미삭제·미완화. 새 동작에 실패 경우 시험 있음: 해시 불일치 시 줄 0개 전달(`정상_종료_녹화의_해시가_다르면_줄을_하나도_넘기지_않고_거부한다`), 형식 버전 거부(`메타만_읽고_형식_버전이_다르면_거부한다`), 받는 쪽 예외 전파(`받는_쪽이_예외를_던지면_...`), 회차 안 변경 시 종료 코드 2·보고서 미작성(`ReplayCheckTest#회차_안에서_녹화가_바뀌면_...`) |
| A12 | 통과 | 대응표 #9(게이트웨이 크래시)·#10(자원 고갈, 리플레이 점검 정지 요청·종료 코드 4)의 전제 유지: 정지 확인 간격·종료 코드 4 경로 무변경. 녹화기 재기동(#9 후 이어 녹화) 결과 동일(`RecordingReplayTest` 통과). 해외 O1~O8·장애 테스트 8종은 주문·시세 전환 경로라 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. `ReplayCheck`의 시간대는 메타 `zone`/캘린더에서 얻는 기존 방식 그대로(`ReplayCheck.java:236-237`) |
| A14 | 통과 | D-117(`docs/decisions/D-117-...md`), 결정 로그 표(`docs/decisions/README.md` D-117 행), ADR-003 2.4, 기획서 v14.53 변경 이력이 코드와 일치. 회차 안 변경 시 종료 코드 2는 D-117 결정 4에 적혀 있음. 아래 N1 문구 정정 권고(비차단) |

## 점검표 밖 발견
- N1(권고, 비차단): D-117 상태 줄의 "`replayCheck` 보고서·종료 코드 … 바꾸지 않는다"는 정상 녹화에만 맞다. 쓰이는 중인 녹화(`-PallowDuringSession`)를 점검하다 1회차 두 읽기 사이에 녹화기가 줄을 더하면, 이전에는 두 회차 해시 불일치로 1(다름)이던 것이 이제 2(읽기 실패)로 나온다. 둘 다 "완료 증거로 쓰지 않음"이라 안전 쪽(fail-closed)이지만, `docs/runbooks/stage-2-real-recording.md:23`의 "2면 녹화를 읽지 못한 것"에 "읽는 사이 녹화가 바뀜(쓰이는 중)"을 덧붙이면 운영자가 원인을 헷갈리지 않는다.
- N2(권고, 기존 위험, 이번 PR이 만든 것 아님): `DayWriter` 재기동은 여전히 그날 녹화를 통째로 힙에 올린다(`DayWriter.java:83`). 10종목 장중 재기동 시 게이트웨이 OOM이면 대응표 #9(게이트웨이 크래시 → 실전 영향 없음, 공백 표시)로 처리되나 녹화가 이어지지 않는다. D-117 후속(data-engineer)에 이미 있으므로 6단계 본 운용(10-26) 전에 장중 재기동 메모리 실측을 권한다.
- N3(정보): 회차 안 변경 감지는 요약 값(건수·마지막 순번·마지막 수신 시각 등) 비교라, 쓰이는 중인 녹화의 중간만 바뀌고 요약이 같으면 못 잡는다. 정상 종료 녹화는 매 읽기마다 SHA-256 대조(`RecordedDayStream.java:89-94`)로 잡히고, 미완료 녹화는 판정이 "불완전"(3) 이하라 완료 증거가 되지 않으므로 수용 가능.
- N4(정보): `RecordedReplaySource.replay()` 반환형이 `void` → `Summary`로 바뀌었다. 저장소 내 호출처(`ReplayCheck`, sim 시험)는 모두 컴파일·통과. 실전 엔진·증권사 플러그인은 이 클래스를 쓰지 않음.

## 판정
승인
