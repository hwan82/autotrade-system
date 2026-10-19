# architect 검토: sim 녹화 읽기 복제본 정리, replayCheck 요약 비교 시험 보강(D-117 후속, PR #179 QA Q1·Q2)
- 대상: PR #182 / 커밋 77d1b95 / main 대비 diff(`git diff origin/main...HEAD`, 8개 파일 +136 −424)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → 종료 코드 0(전체 컴파일·테스트·금지 의존 검사·ArchUnit 통과). `ReplayCheckTest` 22건, marketdata `RecordedDayStreamTest` 10건, sim `LiveWeeklyCheckTest` 10건 모두 실패·건너뜀 0(테스트 결과 XML 확인).
  - 지운 sim `RecordedDayStream`과 marketdata `RecordedDayStream`의 본문 diff: 패키지·import 머리와 `Summary` Javadoc 한 줄 말고 같음(같은 공개 API `Summary`, `readMeta`, `read`).
  - 변이 4종을 직접 넣고 `:autotrade-app-gateway:test --tests ReplayCheckTest` 실행(실행 뒤 원복, 원복 상태로 gateway·marketdata 테스트 재통과 확인):
    - M2 `ReplayCheck` 요약 비교를 건수만 → 새 시험 2건 실패(`회차_안에서_건수가_같아도_메타가_바뀌면_결과_없이_실패한다`, `회차_안에서_건수와_마지막_순번이_같아도_마지막_수신_시각이_바뀌면_결과_없이_실패한다`)
    - M2b 건수+마지막 순번+메타만 비교 → `…마지막_수신_시각이_바뀌면…` 1건 실패
    - M9 marketdata `Counter.accept` 재기동 표시 판정에서 사유 검사 뺌 → `구간_경계_첫_줄이_재기동_표시가_아닌_공백이면_표시_없는_경계로_적는다` 실패
    - M12 `EndTolerantInputStream(gzip, true)`(정상 종료에도 잘린 끝 허용) → `정상_종료_표시가_있는데_해시가_비고_gzip_끝이_잘렸으면_불완전이_아니라_읽기_실패다` 실패

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build --no-daemon` 직접 실행, 종료 코드 0 |
| B2 | 통과 | sim은 `implementation(project(":autotrade-marketdata"))`(autotrade-sim/build.gradle.kts:9)로 marketdata `RecordedDayStream`을 씀, 의존 방향 변화 없음. 같은 이름 클래스 두 개가 사라져 D-117의 architect R3(자동 import 혼동) 해소. sim `com.autotrade.sim.recording` 패키지에 다른 클래스가 남아 `ModuleOwnershipTest.OWNERS` 갱신 불필요, ArchUnit 통과 |
| B3 | 해당 없음 | 공개 계약 변경 없음. 지운 클래스는 marketdata 쪽과 같은 API의 복제본이며 사용처 2곳(LiveWeeklyCheck.java:11, RecordingDayAdapter.java:7)은 import만 바뀜. sim의 `jackson-databind` 제거는 D-117 후속 과제(D-117 문서 53행)대로이고 sim 소스에 jackson 사용이 남지 않음(grep 0건) |
| B4 | 통과 | 운영 코드 동작 변화 없음(import만). 시스템 시계·난수 추가 없음, 새 시험은 고정 `OPEN`·`Clock.fixed`. sim 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 추가 없음(시험 데이터는 `BigDecimal` 가격) |
| B6 | 통과 | 새 시험 4건(ReplayCheckTest.java 396~446행)이 QA Q1·Q2를 고정하고, 검토자가 직접 넣은 변이 M2·M2b·M9·M12를 모두 잡음. 지운 sim `RecordedDayStreamTest` 5건의 규칙은 marketdata 쪽에 있음: 정상 종료·재기동 줄/요약(`RecordedDayStreamTest#줄_단위로_읽은_줄과_요약은_하루를_올려_읽은_것과_같다`, `RecordingReplayTest#같은_날_재기동하면_순번을_이어_쓰고_구간을_나눈다`), 표시 없는 경계·잘린 마지막 줄(`RecordedDayStreamTest#표시_없는_재기동_경계와_잘린_마지막_줄을_요약에_같게_남긴다`), 깨진 gzip 머리(`#깨진_gzip_머리는_비정상_종료면_빈_녹화이고_정상_종료면_거부한다`), 순번 끊김(`#순번이_끊기면_거부한다`), 해시 불일치(`#정상_종료_녹화의_해시가_다르면_줄을_하나도_넘기지_않고_거부한다`), 받는 쪽 예외(`#받는_쪽이_예외를_던지면_그대로_올라가고_그_뒤_줄은_읽지_않는다`). sim 시험의 `RecordedDay.open` 대조는 marketdata `RecordedDay`가 이제 `RecordedDayStream`으로 읽어(marketdata RecordedDayStream.java Summary Javadoc) 같은 코드끼리 비교라 지워도 잃는 검출력 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응 변경 없음. `scripts/ci/protected-paths.txt`의 경로를 하나도 건드리지 않음(변경 파일: app-gateway 시험, autotrade-sim, docs/decisions D-117, docs/qa-out-status.md). 운영 코드 변경은 sim의 import 두 줄과 빌드 의존 제거뿐 |
| B8 | 통과 | diff에 키·계좌·토큰 문자열 없음(grep 0건). 시험 데이터의 "본인 연구용, 외부 공유 금지"는 이용 조건 문구 |

## 점검표 밖 발견
- 참고 R1(판정 무관): `정상_종료_표시가_있는데_해시가_비고_gzip_끝이_잘렸으면…`의 두 번째 단언은 종료 코드 2만 본다. 2는 사용법 오류(ReplayCheck.java:110, 134)와도 같은 값이라, 표준 오류에 "녹화 확인 실패"가 찍혔는지까지 단언하면 실패 이유를 더 좁게 고정한다. 현재 경로에서는 인자가 정상이라 2가 읽기 실패에서만 나오는 것을 M12 변이(3으로 바뀌어 실패)로 확인했다.
- 참고 R2(판정 무관): `changeAfterFirstCollect`는 "6번째 정지 확인"이라는 위치에 기대므로 `recordDay()`의 줄 수(9줄)나 간격을 바꾸면 변경 시점이 회차 경계로 밀린다. 그때는 시험이 예외 없이 끝나 **시끄럽게 실패**하므로 거짓 통과 위험은 없다. 주석(ReplayCheckTest.java:384)에 9줄 전제를 적어 두면 고치는 사람이 덜 헤맨다.
- 참고 R3(판정 무관, 스타일): 새 시험 보조 메서드 `Files_readString`은 Java 이름 규칙에 어긋나고, `java.util.zip.GZIPOutputStream` 등을 import 없이 전체 이름으로 씀. 동작 영향 없음.
- D-117 "후속 반영" 절과 `docs/qa-out-status.md` 한 줄은 저장소 상태와 맞음(PR #179 QA 판정 처리 기록, Q1·Q2 반영 위치). 개발 쪽 변이 확인 주장(M2·M9·M12)은 검토자가 직접 재현해 확인함.

## 판정
승인
