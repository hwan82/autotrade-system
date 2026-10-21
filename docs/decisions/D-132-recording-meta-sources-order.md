# D-132 녹화·입력 기록 meta.json의 sources를 이름순으로 고정한다

- 날짜: 2026-10-20
- 역할: data-engineer
- 상태: **추천안 채택(자율)**, 보호 경로(`autotrade-marketdata`)라 사용자 머지로 확정
- 관련: MD-09(녹화 메타), D-117·D-119(녹화 읽기·재기동), PR #206 바이트 비교, PR #192 독립 QA 권고 Q1·Q2

## 문제

- `[사실]` `RecordingMeta` 생성자가 `sources = Map.copyOf(sources)`로 복사했다(고치기 전 `RecordingMeta.java:44`). `Map.copyOf`가 돌려주는 맵의 순회 순서는 명세상 정해지지 않았고, JDK 구현은 JVM을 띄울 때마다 바뀌는 SALT로 순서를 섞는다.
- `[사실]` `DayWriter`는 sources를 `TreeMap`(이름순)으로 모으지만(`DayWriter.java` `sources` 필드), 메타를 만들 때 위 복사가 순서를 다시 섞는다. Jackson은 맵을 순회 순서대로 쓰므로 같은 입력에서도 `meta.json` 바이트가 실행마다 달랐다.
- `[사실]` PR #206 바이트 비교에서 공시 없는 등록 결과 22개 중 2개가 main끼리도 다르게 나왔고, 차이는 입력 기록 `meta.json`의 sources 순서뿐이었다(메모리 pr206, `docs/reviews/pr-f9/architect.md`).
- 영향: 결정론 원칙(지침 5장) 위반. 성과 수치는 바뀌지 않지만 결과 폴더 바이트 비교가 흔들려 회귀 확인을 방해한다.

## 대안 비교

| 대안 | 내용 | 판단 |
|---|---|---|
| A. 생성자에서 이름순 불변 맵으로 복사(`Collections.unmodifiableMap(new TreeMap<>(...))`) | 녹화기·입력 기록·Jackson 읽기 모든 경로가 같은 순서 | **채택**. 한 곳만 바꿈, 어떤 호출자가 넘겨도 같음 |
| B. 쓰는 쪽 `ObjectMapper`에 `ORDER_MAP_ENTRIES_BY_KEYS` | `meta.json` 파일만 고정 | 버림. 메모리 안 `sources()` 순회 순서(보고서 등)는 여전히 흔들림, 매퍼를 새로 만드는 곳마다 빠뜨릴 수 있음 |
| C. 읽는 쪽(비교·보고서)에서 정렬 | 지금 `ReplayCheck`처럼 `new TreeMap<>(m.sources())` | 버림. 파일 바이트가 여전히 다름 |

되돌릴 조건: 소스 순서에 의미(우선순위 등)를 담아야 하면 별도 필드로 둔다. sources는 이름 → 범위 표일 뿐 순서에 뜻이 없다.

## 결정

1. `RecordingMeta.sources`는 이름순 불변 맵이다. null 키·값은 예전(`Map.copyOf`)처럼 `NullPointerException`으로 거절한다.
2. 형식 번호(`FORMAT_VERSION`)는 그대로다. 읽기는 키 순서와 무관하므로 예전 `meta.json`도 그대로 읽힌다. 새로 쓰는 `meta.json`(녹화기 기동·재기동·종료, 실험실 입력 기록)만 이름순이 된다.
3. 이벤트 파일(`events.jsonl.gz`)과 `eventsSha256`은 바뀌지 않는다(sources는 이벤트 줄에 없다).

## 검증

- 새 시험 `RecordingMetaSourcesOrderTest`: 이름순이 아닌 순서로 넣은 소스 12개가 이름순으로 나오고, 녹화기가 쓴 `meta.json` 텍스트에서도 이름순이며, 읽어서 다시 쓰면 같은 바이트다. null 키·값 거절 유지.
- 변이: 생성자를 `Map.copyOf`로 되돌리면 이 시험 2개가 실패한다(개발 쪽 직접 실행). 우연히 12개가 이름순이 될 확률은 12!분의 1이다.
- 회귀: `./gradlew build` 전체 통과(PR 본문에 결과). `ReplayCheck` 보고서는 이미 `new TreeMap<>(m.sources())`로 찍으므로 출력 무변경, LIVE01 판정·주간 점검은 sources를 비교하지 않는다 `[사실]`(`grep "\.sources()"`).

## 위험과 한계

- 이 PR 전에 만든 녹화·입력 기록의 `meta.json`은 순서가 섞인 채 남는다. 같은 날 녹화기가 다시 뜨면 메타를 새로 쓰면서 이름순이 된다. 파일 바이트 비교로 예전 결과와 맞춰 볼 때는 sources 순서 차이를 무시해야 한다 `[추론]`.
- `meta.json` 들여쓰기 줄바꿈은 Jackson 기본(OS 줄바꿈)을 따를 수 있어 Linux와 Windows에서 만든 파일은 바이트가 다를 수 있다 `[추론]`(PR #209 architect 2). 이 결정은 같은 OS에서 실행마다 같은 바이트를 보장한다.
- 다른 `Map.copyOf` 사용처(예: `ExchangeCalendar.sources`)는 파일로 쓰지 않거나 읽는 쪽이 정렬한다(`SnapshotSelectionData`) `[사실]`. 이 결정 범위 밖.

## 같은 PR의 시험 보강(PR #192 독립 QA 권고)

- Q1: 같은 날 재기동에서 앞 구간 정상 종료 시각이 마지막 수신보다 늦으면 공백 시작이 정상 종료 시각임을 고정(`DayWriterRestartStreamTest#같은_날_앞_구간_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다`). 변이 P15(공백 시작을 마지막 수신으로)가 잡힌다.
- Q2: 다시 쓰기 중 쓰기 실패 문구 `녹화 재기동 다시 쓰기 실패: <폴더>`를 고정(`…#다시_쓰기_중_쓰기가_실패하면_폴더를_밝힌_문구로_멈추고_원본을_그대로_둔다`). 쓰기 실패를 만들려고 `DayWriter`에 tmp 여는 방법을 받는 패키지 전용 생성자를 더했다(운영 생성자는 `Files::newOutputStream`을 넘김, 동작 무변경). 변이 P13이 잡힌다.
- architect 비차단: 시험 주석 "1000·5000부터"를 실제 값(첫 줄 KIS-WS 5000, 둘째 줄 KIWOOM-WS 1001)으로, "마지막 수신(event(1))"을 실제 이벤트로 고침.
- PR #209 architect 1: 다시 쓰기 중 쓰기 실패 때 위 스트림 닫기가 다시 던지면서 tmp 파일 핸들이 열린 채 남았다(이전부터 있던 동작). 파일 스트림을 따로 try-with-resources 자원으로 잡아 항상 닫는다. Q2 시험이 핸들이 닫혔는지 확인한다(Windows에서 tmp 삭제 실패 방지).
