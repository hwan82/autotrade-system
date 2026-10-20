# architect 검토: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험
- 대상: PR #209 / 커밋 d167717269c6c4986fb9e000db020e1a65694d91 / main 대비 diff(`git diff origin/main...HEAD`, 8개 파일 +242 −6)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL(138 tasks, `:autotrade-marketdata:test`·`:autotrade-architecture-test:test`·`:autotrade-sim:test` 실행). marketdata 결과 XML 합계 tests=161, failures=0, errors=0, skipped=0. `RecordingMetaSourcesOrderTest` 3/3, `DayWriterRestartStreamTest` 7/7 통과.
  - 변이 3종(각각 되돌린 뒤 두 시험 클래스만 실행, 끝나고 원복 후 재실행 통과):
    - M1 `RecordingMeta.java:51`을 `Map.copyOf(sources)`로 되돌림 → 10개 중 2개 실패(새 시험 2개).
    - M2(P15) `DayWriter` `restartGapStart = previous.lastReceivedAt()` → 1개 실패: `같은_날_앞_구간_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다`.
    - M3(P13) 다시 쓰기 실패 문구에서 `+ dir` 제거 → 1개 실패(Q2 시험).
  - JDK 21 실측(작은 프로그램): `BufferedWriter(OutputStreamWriter(GZIPOutputStream(실패 스트림)))`를 try-with-resources로 닫을 때 쓰기 실패가 나면 맨 아래 스트림의 `close()`가 불리지 않는다(아래 "점검표 밖 발견" 1).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 실패·건너뜀 0 |
| B2 | 통과 | 새 운영 타입은 `DayWriter.TmpOpener`(패키지 전용 중첩 인터페이스, `DayWriter.java:158-161`)뿐, 기존 `com.autotrade.marketdata.recording` 패키지 안. 새 의존 없음(`java.util.Collections`·`TreeMap`만). `autotrade-architecture-test` 통과, ArchUnit·소유 표 무변경 |
| B3 | 통과 | 공개 계약 변경 없음. `RecordingMeta` 레코드 구성 요소·`FORMAT_VERSION` 그대로, `meta.json`은 JSON 객체 키 순서만 달라지고 읽기(`RecordedDay`·`RecordedDayStream`·`ReplayCheck`·`RecordingDayAdapter`·`LiveWeeklyCheck`)는 맵 조회라 영향 없음. 새 생성자는 패키지 전용(`DayWriter.java:82`). 결정은 D-132·결정 로그 표·기획서 v14.58에 기록. ADR 불필요로 판단 |
| B4 | 통과 | 이 PR의 목적 자체가 결정론 보강: `RecordingMeta.java:51` 이름순 불변 맵, `RecordingMetaSourcesOrderTest#녹화기가_쓴_meta_json의_소스_줄이_이름순이고_다시_읽어도_같다`가 파일 텍스트 순서와 재직렬화 바이트 동일성 고정. 시스템 시계·무시드 난수 추가 없음. 기존 골든·바이트 시험 전부 통과(build) |
| B5 | 해당 없음 | 금액 계산 변경 없음(시험의 가격은 기존처럼 `BigDecimal`) |
| B6 | 통과 | 새 동작 시험 3개(`RecordingMetaSourcesOrderTest`)와 QA Q1·Q2 시험 2개(`DayWriterRestartStreamTest:154`, `:181`). 변이 M1~M3이 모두 잡힘. 지운·건너뛴 시험 없음(diff의 시험 변경은 추가와 주석 2줄 수정뿐) |
| B7 | risk-ops 검토 필요 | `autotrade-marketdata/*`는 `scripts/ci/protected-paths.txt`의 보호 경로(실전 경로 런타임 모듈). 주문 경로·Guard·블랙리스트 코드는 건드리지 않지만, 지침 11장에 따라 risk-ops A 점검표 검토·사용자 머지·독립 QA 대상. 현재 `docs/reviews/pr-209/`에 risk-ops 원문 없음 |
| B8 | 통과 | diff에 키·계좌·토큰 없음. 소스 이름은 `KIWOOM-WS` 같은 식별자와 시험용 가짜 이름뿐 |

## 점검표 밖 발견
1. (비차단, 기존 동작) 다시 쓰기 중 쓰기 실패 때 tmp 파일 핸들이 닫히지 않는다. `rewriteToTmp`(`DayWriter.java:136-153`)의 try-with-resources가 `BufferedWriter`를 닫을 때 `StreamEncoder`·`DeflaterOutputStream`이 같은 실패로 다시 던지며 아래 스트림 `close()`를 건너뛴다(JDK 21.0.12에서 직접 확인: 아래 스트림 closed=false). Linux에서는 `Files.deleteIfExists(tmp)`가 성공하고, Windows도 tmp를 `Files.newOutputStream`(NIO, 기본 FILE_SHARE_DELETE)으로 열어 삭제는 될 것으로 `[추론]`하지만 Windows에서 직접 확인하지 못했다. 이 PR이 만든 문제가 아니고(운영 경로는 PR 전과 같음) 새 Q2 시험이 이 경로를 처음 실행하므로, 사용자 PC(Windows)에서 `./gradlew :autotrade-marketdata:test`로 Q2 시험이 통과하는지 한 번 확인하기를 권한다. 고친다면 `tmpOpener.open(tmp)` 결과를 별도 try-with-resources 자원으로 잡아 실패 때도 닫히게 하는 것이 간단하다(후속 marketdata PR 후보).
2. (비차단, 범위 밖) `meta.json`은 `INDENT_OUTPUT`이라 Jackson 기본 들여쓰기가 시스템 줄바꿈(Windows CRLF)을 쓴다 `[추론]`(Jackson `DefaultIndenter.SYSTEM_LINEFEED_INSTANCE`). 같은 OS 안에서는 이 PR로 바이트가 고정되지만 Linux CI와 Windows PC 결과 폴더를 바이트로 맞대면 여전히 다를 수 있다. D-132 "위험과 한계"에 OS 간 비교는 줄바꿈 차이를 무시해야 한다는 한 줄을 더하면 좋다.
3. (비차단) Q2 시험의 실패 시점은 "gzip 머리 10바이트 뒤 첫 쓰기"인데, 실제로 줄 쓰기 중(소비자 람다 안)에서 터지는지는 2,000줄 분량이 Deflater 버퍼를 넘긴다는 데 기대고 있다. 시험이 `hasMessage("녹화 재기동 다시 쓰기 실패: " + dir)`로 문구를 직접 고정하므로, 만약 닫을 때 실패로 바뀌면 문구가 `녹화 파일을 열 수 없습니다`가 되어 시험이 실패해 드러난다. 조용히 틀릴 위험은 없다.
4. 확인: `DayWriter`가 이미 `TreeMap`으로 sources를 모으므로(`DayWriter.java:58`) 이번 수정은 `RecordingMeta` 한 곳에서 순서를 정하는 대안 A가 맞다. null 키·값 거절은 `RecordingMeta.java:47-50`에서 명시적으로 유지되고 `소스_이름이나_범위가_비면_예전처럼_거절한다`가 고정한다. `ReplayCheck.java:375`는 이미 `new TreeMap<>(m.sources())`라 보고서 출력 무변경.

## 판정
승인 (단, B7: 보호 경로 변경이므로 risk-ops 검토·사용자 머지·독립 QA를 거쳐야 하며 이 판정만으로 Claude가 머지하지 않는다)
