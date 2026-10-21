# architect 검토: 녹화 저장 시험 보강(같은 날 재기동 tmp 비움·sources 불변·비정상 종료 흉내 단언, PR #209 QA Q1·R2·R3)
- 대상: PR #217 / 커밋 c3f3a45(검토 대상 코드), 브랜치 끝 5cfb19c(risk-ops 원문 추가뿐) / main(c4d6c9c) 대비 diff `git diff origin/main...HEAD`
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: 첫 실행은 Maven Central에서 `archunit-1.4.1.jar` 내려받기 실패(`Could not HEAD ...`)로 `:autotrade-architecture-test:compileTestJava` 실패(코드와 무관한 내려받기 오류). 다시 실행해 **BUILD SUCCESSFUL in 1m 55s**. `autotrade-marketdata` 시험 결과 XML 합계 163개, 건너뜀 0·실패 0·오류 0(D-132의 "161개" + 이번 새 시험 2개와 맞음)
  - 변이 직접 확인(각각 넣고 해당 시험 클래스만 돌린 뒤 되돌림, 작업 트리 깨끗함 확인):
    - P12 `DayWriter.java:75` 운영 여는 방법을 `CREATE, APPEND`로 → `같은_날_재기동은_앞_재기동이_남긴_tmp를_비우고_다시_쓴다` 실패(`IllegalStateException: 녹화 순번이 끊겼습니다: 기대 6, 실제 1`). 다른 시험은 그대로 통과
    - P4 `RecordingMeta.java:51` `Collections.unmodifiableMap` 감싸기 삭제 → `소스_맵은_고칠_수_없다` 실패(예외 없음)
    - T2 `JournalWriterTest#crash`가 되돌리지 않음(=정상 종료) → JournalWriterTest 3개 실패
    - T4 `crash`가 메타만 되돌림 → 3개 실패. 반대로 이벤트만 되돌림 → JournalWriterTest 3개와 `crashedDay`를 쓰는 DayWriterRestartStreamTest 2개 실패

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 재실행 BUILD SUCCESSFUL(첫 실패는 Maven Central 내려받기 오류) |
| B2 | 통과 | 운영 코드·빌드 파일·모듈 구성 변경 없음(diff는 `autotrade-marketdata/src/test` 3개 파일과 `docs/decisions/D-132-...md`). ArchUnit(`autotrade-architecture-test`) 빌드 안에서 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 결정 문서 D-132에 "후속 반영(시험만, 운영 코드 무변경)" 절만 추가했고 내용이 diff와 맞음(`D-132-recording-meta-sources-order.md:60-66`) |
| B4 | 통과 | 새 시험은 고정 시각(`OPEN.plusSeconds(...)`)과 고정 입력(`event(i)`)만 쓰고 시스템 시계·난수 없음(`DayWriterRestartStreamTest.java:186-211`). 골든·재현 시험 포함 전체 통과 |
| B5 | 통과 | 새 시험 금액은 `BigDecimal.valueOf(70_000)`(`DayWriterRestartStreamTest.java:202`) |
| B6 | 통과 | 새 시험 2개(`같은_날_재기동은_앞_재기동이_남긴_tmp를_비우고_다시_쓴다`, `소스_맵은_고칠_수_없다`)와 흉내 함수 단언 2곳(`JournalWriterTest.java:57-60`, `DayWriterRestartStreamTest.java:92-95`) 추가. 지운 단언·시험, 건너뛴 시험 없음(XML 건너뜀 0). QA가 생존으로 적은 P12·P4·T2·T4를 직접 넣어 모두 잡히는 것을 확인(위) |
| B7 | risk-ops 검토 필요 | `autotrade-marketdata/*`는 `scripts/ci/protected-paths.txt`의 보호 경로라 실전 관련 PR로 다룬다(운영 코드는 무변경). risk-ops 원문은 `docs/reviews/pr-217/risk-ops.md`로 따로 있음(이 검토는 그 내용을 판정 근거로 쓰지 않음) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 시험 소스 이름(`KIWOOM-WS`)·범위(`KRX_REALTIME`)·종목 코드만 있음 |

## 점검표 밖 발견
1. (정보, 비차단) Q1 시험에서 남겨 둔 tmp는 정상 종료 원본과 **같은 내용**이다(`DayWriterRestartStreamTest.java:196`, `Files.copy(events, tmp)`). 그래서 "tmp가 이미 있으면 다시 쓰지 않고 그 tmp를 그대로 옮기는" 식의 변경은 이 시험이 잡지 못한다 `[추론]`(직접 변이로 확인하지 않음). 지금 운영 코드에는 그런 분기가 없고(`DayWriter.java:95-117`), QA Q1이 요구한 성질(이어 쓰기 금지)은 고정됐다. 더 단단히 하려면 tmp를 원본과 다른 줄(예: 앞 3줄만)로 두는 방법이 있다.
2. (정보, 비차단) `assertThat(tmp).doesNotExist()`(`DayWriterRestartStreamTest.java:200`)는 성공 경로에서 `Files.move`가 늘 tmp를 없애므로 이 시험의 핵심 판별에는 기여하지 않는다. 판별은 뒤의 순번 단언이 한다(P12 변이가 거기서 실패함을 확인).
3. (정보, 비차단, 범위 밖) `RecordingReplayTest#crash`(`RecordingReplayTest.java:266-273`)는 같은 흉내 방식이지만 이번 R3 단언을 받지 않았다. 호출하는 시험 중 일부는 뒤에서 `truncated()`를 단언하나(`:325`, `:341` 등) 전부는 아니다. QA R3는 `JournalWriterTest`만 지목했고 D-132 후속 절도 두 파일만 적었으므로 이번 PR의 결함은 아니다. 나중에 같은 한 줄을 넣으면 세 흉내 함수가 같아진다.
4. (정보) 큰 녹화 시험은 이제 부모 JVM에서 20만 줄을 한 번 더 읽는다(`crashedDay(HEAVY_LINES)` 안의 새 단언). `RecordedDayStream.read`는 줄을 쌓지 않는 읽기라 힙 문제는 없고, 이 실행에서 `DayWriterRestartStreamTest` 8개 전체 14.1초였다.
5. (정보) 주석 수정(`DayWriterRestartStreamTest.java:302-304`)은 앞 검토(architect-3 2·risk-ops-3 N3)의 지적대로 "명령 줄 길이 한도를 피하고"를 지우고 `[추론]` 표시를 달았다. 사용자 PC Windows에서의 `:autotrade-marketdata:test` 통과는 이 컨테이너(Linux)에서 확인할 수 없어 D-132가 적은 대로 머지 전 사용자 확인이 남아 있다(이 검토 판정의 범위 밖).
6. (작업 환경) 검토 중 다른 검토자 작업 트리가 같은 로컬 브랜치 이름을 써서 이 작업 트리의 HEAD가 5cfb19c로 움직였다. 검토 대상 코드(c3f3a45)와 5cfb19c는 `docs/reviews/pr-217/risk-ops.md` 추가만 다르므로 위 실행 결과에 영향 없음.

## 판정
승인
