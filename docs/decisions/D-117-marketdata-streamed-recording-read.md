# D-117 녹화 줄 단위 읽기를 marketdata 한 곳에 두고, 게이트웨이 리플레이 확인도 줄 단위로 읽는다

- 날짜: 2026-10-19
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 보호 경로(`autotrade-marketdata`) 변경이라 실전 관련 PR로 다룬다(architect·risk-ops 검토, 독립 QA, 사용자 머지). 녹화 형식, 녹화 쓰기(`DayWriter`·`MarketEventRecorder`), 읽기 검사 규칙, 정지 확인 관문(D-085)은 바꾸지 않는다. `replayCheck` 보고서·종료 코드는 다 쓰인 녹화에서 그대로다(아직 쓰이는 녹화는 아래 4번·위험과 한계).
- 담당: data-engineer 결정·구현. 교차 검토: architect(코드), risk-ops(보호 경로)
- 근거: [D-115](D-115-stream-recordings-in-live-checks.md) 대안 ⑤와 후속(변경 제안), [D-085](D-085-replay-check-gate.md) 리플레이 점검 관문, [ADR-003](../adr/ADR-003-market-data.md) 2.4

## 문제

- 녹화 읽기 규칙이 두 벌이었다 `[사실]`: `RecordedDay`(marketdata, 하루를 목록으로 올림)와 `RecordedDayStream`(sim, D-115에서 줄 단위로 복제). 한쪽만 고치면 어긋난다.
- 게이트웨이 `replayCheck`(D-085)는 회차마다 `RecordedDay.open`으로 하루를 통째로 올렸다 `[사실]`(`ReplayCheck.replayOnce`). 10단계 호가 위주 100만 줄(3종목) 녹화에서 고치기 전 코드는 힙 2GB에서 `OutOfMemoryError`가 났고 4GB에서야 끝났다(아래 검증). 10종목 하루 약 176만 줄 `[추론]`(D-115)이면 PC 기본 힙(실제 메모리의 1/4)을 넘을 수 있다.

## 결정

1. **줄 단위 읽기를 marketdata로**: `com.autotrade.marketdata.recording.RecordedDayStream`(`read(dir, sink)`, `readMeta(dir)`, `Summary`)을 둔다. API와 검사 규칙은 sim의 `RecordedDayStream`(D-115)과 같다.
2. **읽기 규칙은 한 벌**: `RecordedDay.open`은 `RecordedDayStream.read`로 줄을 모아 만든다. 그래서 순번 연속·정상 종료 녹화의 SHA-256·비정상 종료의 잘린 마지막 줄·깨진 gzip 머리 규칙은 `RecordedDayStream`에만 있다. `RecordedDay.summary()`는 줄 단위로 읽은 요약과 같은 값을 낸다.
3. **리플레이 소스의 줄 단위 모드**: `RecordedReplaySource.streaming(dayDir)`는 만들 때 메타만 읽고, `replay()`·`events()` 때마다 녹화를 줄 단위로 다시 읽는다. 소스 이름은 읽은 녹화일로 만든 것과 같다. `replay()`는 이번에 보낸 녹화의 요약(`RecordedDayStream.Summary`)을 돌려준다(이전에는 `void`, 기존 호출은 그대로 컴파일된다).
4. **게이트웨이 `replayCheck`는 줄 단위로**: 회차마다 녹화를 두 번 읽는다. 첫 읽기로 종목을 모으고(정지 확인 5만 줄마다, 그대로), 두 번째 읽기로 리플레이 소스가 이벤트를 보낸다(정지 확인 5만 건마다, 그대로). 두 읽기의 요약(메타·잘림·건수·공백 수·마지막 순번·표시 없는 재기동 경계·마지막 수신 시각)이 다르면 읽는 사이 녹화가 바뀐 것이라 결과 없이 "녹화 확인 실패"(종료 코드 2)로 끝낸다. 보고서·정지 확인 횟수는 바뀌지 않고, 종료 코드도 다 쓰인 녹화에서는 같다.
5. **sim 쪽 정리는 나중에**: sim의 `RecordedDayStream`·jackson 의존 삭제는 "6단계 녹화 판정 명령" 작업 PR이 머지된 뒤 따로 한다(같은 파일을 동시에 고치지 않음, 지침 10장). 그때까지 sim의 `RecordedDayStreamTest`가 sim 복제본과 marketdata(`RecordedDay`)가 같은 결과인지 계속 고정한다.

## 대안

| 대안 | 판단 |
|---|---|
| ① marketdata에 줄 단위 읽기를 두고 `RecordedDay`가 그것을 씀(채택) | 읽기 규칙 한 벌. 하루 목록이 필요한 쪽(녹화기 재기동, 시험)은 그대로 `RecordedDay`를 쓴다 |
| ② `RecordedDay`를 지연 목록으로(줄을 필요할 때 디스크에서) | `lines()`를 쓰는 곳(녹화기 재기동 다시 쓰기, sim 어댑터)이 여러 번 순회해 파일을 거듭 풀고, 목록 계약(`get(i)`)을 흉내 내기 어렵다. 버림 |
| ③ marketdata를 고치지 않고 게이트웨이가 sim의 `RecordedDayStream`을 씀 | 게이트웨이가 sim에 의존하게 되어 모듈 경계(ADR-001: 시세 게이트웨이는 실험 코드를 모름)를 깨고, 규칙도 여전히 두 벌. 버림 |
| ④ `replayCheck` 회차마다 한 번만 읽기(종목 모으기 없이 모든 종목 구독) | 읽기가 반으로 줄지만 리플레이 소스가 구독 종목 집합을 받는 계약(`MarketDataSource.subscribe`)을 우회해야 하고, 지금 정지 확인 횟수(D-085 시험)가 바뀐다. 시간은 아래처럼 이미 빨라져 버림 |

되돌릴 조건: 녹화 형식 버전이 바뀌면 `RecordedDayStream`만 고친다. 하루 녹화를 읽는 시간이 문제가 되면 ④를 다시 본다.

## 위험과 한계

- `RecordedDay.open`이 줄을 바로 디코드한다. 이전에는 줄 문자열을 다 모은 뒤 디코드해서, 정상 종료 녹화의 파일 끝이 깨지면 디코드 전에 읽기 예외가 났다. 지금은 앞 줄을 디코드하다 같은 읽기 예외가 난다. 정상 종료 녹화는 그 전에 SHA-256 검사로 거부되므로(해시가 메타에 있으면) 결과는 같다 `[추론]`. 거부 여부·문구는 `RecordingReplayTest`·sim `RecordedDayStreamTest`로 확인했다.
- `replayCheck`는 회차마다 녹화를 두 번 푼다(이전 한 번). 그래도 100만 줄에서 54.6초 → 37.8초로 빨라졌다(줄 목록을 만들고 GC하는 비용이 없어짐, 컨테이너 실측 1회).
- 녹화기 재기동(`DayWriter`, 게이트웨이 실시간 경로)은 여전히 그날 녹화를 `RecordedDay.open`으로 통째로 올린다(재기동 때 한 번). 10종목 장중 재기동이면 게이트웨이 힙이 빠듯할 수 있다 `[추론]`. 녹화 쓰기 경로라 이번에 바꾸지 않고 후속으로 둔다(아래).
- **아직 쓰이는 녹화에서는 종료 코드가 달라질 수 있다**(architect R1, risk-ops N1): 게이트웨이가 줄을 붙이는 그날 폴더를 점검하면(장 마감 뒤 게이트웨이를 아직 안 끔, `-PallowDuringSession`) 예전에는 1(다름)이나 3(불완전)이었지만, 이제 한 회차의 두 읽기 사이에 줄이 붙으면 2(녹화 확인 실패)다. 어느 쪽도 완료 증거가 아니고 결과를 쓰지 않는 쪽이라 받아들인다. runbook(2단계 녹화 7번, runbook README 3장)에 "게이트웨이를 끈 뒤 다시"를 적었다.
- 읽는 중 녹화가 바뀌는 것은 두 읽기의 요약으로만 잡는다. 같은 건수·같은 마지막 줄로 가운데만 바뀌면 정상 종료 녹화는 SHA-256으로 잡지만, 쓰이는 중인(메타 미완료) 녹화는 못 잡을 수 있다. 그런 녹화는 원래 판정이 "불완전"이라 완료 증거로 쓰지 않는다.

## 검증

- 시험(`./gradlew :autotrade-marketdata:test :autotrade-app-gateway:test :autotrade-sim:test` 통과)
  - marketdata `RecordedDayStreamTest`(새로): 순번 끊김 거부, 깨진 gzip 머리(비정상 종료는 빈 녹화·정상 종료는 거부), 정상 종료 녹화 가운데 줄 형식 오류 거부(architect R2), 줄·요약이 `RecordedDay`와 같음(재기동 포함), 표시 없는 재기동 경계·줄 없는 구간·잘린 마지막 줄, 해시가 다르면 줄을 하나도 넘기지 않음, 받는 쪽 예외 뒤 읽기 멈춤, 형식 버전 거부, 줄 단위 리플레이 소스가 읽은 녹화일 소스와 같은 이벤트·요약·소스 이름(두 번 리플레이 포함), 부를 때마다 다시 읽어 바뀐 녹화를 요약으로 드러냄.
  - 기존 `RecordingReplayTest`(읽기 규칙·녹화기 재기동) 그대로 통과. sim `RecordedDayStreamTest`(D-115 복제본 대 `RecordedDay`) 통과.
  - gateway `ReplayCheckTest`: 기존 시험 전부(정지 확인 횟수 고정 포함) 그대로 통과. 새로 회차 안에서 녹화가 바뀌면 결과 없이 실패하고 명령은 2를 돌려주며 보고서를 쓰지 않음.
- 결과 동일·메모리 실측(컨테이너 JDK 21, 개발 쪽 직접 실행, 합성 녹화: 3종목 100만 줄, 10단계 호가 90%·체결 10%, gzip 55MB, 정상 종료·해시 있음)
  - 고치기 전(main 59e225a) `ReplayCheck.check`: 힙 256MB·1GB·2GB에서 `OutOfMemoryError: Java heap space`, 4GB에서 54.6초.
  - 고친 뒤: **힙 128MB 37.8초**, 256MB 38.5초. 보고서 Markdown이 고치기 전 4GB 실행과 **바이트 일치**.
  - 실녹화(2026-10-12)로는 돌리지 않았다(녹화 파일은 사용자 PC에만 둔다, PR #72 architect O2). 사용자 PC에서 `replayCheck 2026-10-12`를 돌려 보고서가 [2단계 보고서](../../experiments/stage-2-replay/2026-10-12.md)와 같은지 보면 확인된다.
- `./gradlew build`(ArchUnit·순수 모듈 검사 포함): 결과는 PR 본문에 적는다.

## 후속(변경 제안)

- **sim-engine(“6단계 녹화 판정 명령” PR 머지 뒤)**: sim `RecordedDayStream`을 지우고 import를 marketdata 것으로 바꾼다. sim `RecordedDayStreamTest`를 정리하기 전에 거기서만 고정하던 규칙(순번 끊김 거부, 깨진 gzip 머리, 정상 종료 녹화 가운데 줄 형식 오류 거부)이 marketdata 시험에 있는지 확인한다(이 PR에서 marketdata `RecordedDayStreamTest`에 옮겨 둠, architect R2). 같은 이름 클래스가 두 모듈에 있는 동안 IDE 자동 import에 주의한다(architect R3). sim `build.gradle.kts`의 `jackson-databind`(D-115에서 더함)를 뺀다.
- **data-engineer(녹화 쓰기 경로, 실전 관련)**: `DayWriter` 재기동이 그날 녹화를 통째로 올리지 않게(요약은 줄 단위로, 다시 쓰기는 읽으며 바로 tmp 파일에) 바꾼다. 장중 재기동 메모리를 실측한 뒤 판단한다.

## 후속 반영

- sim 복제본 정리(PR #179 머지 뒤 PR): sim `RecordedDayStream`·sim `RecordedDayStreamTest`를 지우고 `LiveWeeklyCheck`·`RecordingDayAdapter`가 marketdata `RecordedDayStream`을 쓴다. sim `build.gradle.kts`의 `jackson-databind`를 뺐다. 지운 sim 시험이 고정하던 규칙(정상 종료·재기동·표시 없는 경계·잘린 마지막 줄·깨진 gzip 머리·순번 끊김·해시 불일치·받는 쪽 예외)은 marketdata `RecordedDayStreamTest`·`RecordingReplayTest`에 있다.
- 독립 QA(PR #179) 권고: Q1은 gateway `ReplayCheckTest`에 시험 셋(건수가 같아도 메타가 바뀌면 실패, 건수·마지막 순번이 같아도 마지막 수신 시각이 바뀌면 실패, 구간 경계 첫 줄이 재기동 표시가 아닌 공백이면 표시 없는 경계로 적음). Q2는 같은 파일에 정상 종료 표시가 있는데 해시가 비고 gzip 끝이 잘렸으면 종료 코드 2(정상 종료 아님이면 불완전)를 고정했다. 세 변이(M2 요약 비교를 건수만, M9 재기동 표시 판정에서 사유 검사 뺌, M12 정상 종료에도 gzip 끝 허용)를 손으로 넣어 각각 이 시험이 잡는지 확인했다(개발 쪽 직접 실행).
- data-engineer 녹화 쓰기 경로 후속은 [D-119](D-119-daywriter-streamed-restart.md)로 반영: `DayWriter` 재기동이 줄 단위로 읽으며 다시 쓴다(220만 줄 힙 3GB OOM → 64MB).
