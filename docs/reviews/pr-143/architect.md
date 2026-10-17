# architect 검토: 수집기: 게시 전으로 보고 넘긴 시장·날짜를 콘솔에 한 줄로 표시(PR #141 R3)
- 대상: PR #143 / 커밋 ee4287744c75ee742afc22b5cae678b77882ca57 (브랜치 claude/project-thread-qp8y1u) / `git diff origin/main...HEAD` 전체 (origin/main 1352889, 브랜치가 main을 포함함을 `git merge-base --is-ancestor`로 확인)
  - 처음 요청받은 머리는 4113e29였으나 검토 도중 ee42877이 올라와 최종 대상으로 삼았다. 두 커밋 모두 직접 봤다.
- 점검표: B
- 직접 실행한 것:
  - 4113e29에서 `./gradlew build` → **실패**. `:autotrade-app-collector:test`의 `CollectorMainTest#최근_날_한_시장만_빈_목록이면_콘솔에_게시_전_시장_날짜를_한_줄로_알린다` 가 종료 코드 1(기대 0)로 실패. 원인은 시험 자료가 KOSPI·KOSDAQ 두 시장에 같은 종목 `111111`을 넣어 스냅샷 단계 `KrxDailySnapshotBuilder.java:288` "같은 날 같은 종목이 두 번 나왔습니다: 111111 2026-10-06" 예외. 같은 머리의 CI `Gradle build`도 failure(check-runs API로 확인).
  - ee42877(시험 자료만 시장마다 다른 종목으로 바꿈, CollectorMainTest.java:184-186)에서 `./gradlew build` → 이 컨테이너에서 `:autotrade-app-lab:compileJava`가 repo.maven.apache.org **429 Too Many Requests**로 의존성을 받지 못해 실패(코드 원인 아님). `:autotrade-architecture-test:test`도 같은 429로 실행 불가.
  - ee42877에서 `./gradlew :autotrade-app-collector:build` 성공, `:autotrade-app-collector:test --rerun-tasks` 성공: 전체 78건 실패 0·건너뜀 0, CollectorMainTest 18/18, KrxDailyCollectorTest 10/10.
  - 전체 빌드 근거는 사용자 결정(2026-10-16, 같은 head의 GitHub CI 결과를 근거로 쓸 수 있음)에 따라 CI로 대신: `gh api repos/hwan82/autotrade-system/commits/ee42877/check-runs` → Gradle build success(run 39018114590, job 117354327927, head_sha ee42877 일치), DB schema success, Secret scan and collector boundary success, Lab screens (Playwright) success.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 로컬 전체 빌드는 Maven 429로 확인 못 함(코드 무관). 같은 head ee42877의 CI `Gradle build` success(job 117354327927, `./gradlew build` 전체: ArchUnit·금지 의존 검사 포함). 변경 모듈 `:autotrade-app-collector:build`는 직접 실행 성공. 4113e29는 실패했고 ee42877이 시험 자료로 고쳤음을 직접 재현·확인 |
| B2 | 통과 | 변경은 `autotrade-app-collector` 안(com.autotrade.app.collector)뿐. 새 import는 java.util(Optional, TreeMap)만. 새 모듈·패키지·의존 없음. ArchUnit은 CI Gradle build에서 통과 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. `KrxDailyCollector.Result`에 칸 추가(KrxDailyCollector.java:42)와 `NotPublished` 레코드(:45)는 수집기 모듈 내부 타입이며 생성처는 :128 하나뿐(저장소 grep). 콘솔 문구는 runbook `docs/runbooks/collector.md` 3.4에 반영 |
| B4 | 통과 | 오늘 날짜는 주입 Clock으로만(KrxDailyCollector.java:55, CollectorMain.java:111). 목록 순서는 날짜 바깥 루프·`EnumSet.copyOf(boards)` 안 루프로 고정, 한 줄 조립은 `TreeMap`(CollectorMain.java:230)이라 결정론적. 스냅샷 내용·ID 계산 경로는 바뀌지 않음(새 값은 콘솔 출력에만 쓰임) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 시험 3건: `KrxDailyCollectorTest#여러_시장_중_최근_날_한_시장만_빈_목록이면_멈추지_않고_그_시장_날짜만_게시_전으로_알린다`(휴장일 10-05 제외, KOSDAQ 캐시·KOSPI 미캐시, 문구 전체 단언), `#게시_전_한_줄은_모든_시장이_빈_날을_빠지는_시장으로_적지_않고_넘긴_날이_없으면_비어_있다`, `CollectorMainTest#최근_날_한_시장만_빈_목록이면_콘솔에_...`(종단 콘솔 출력). 기존 시험 `최근_빈_날...`에 `notPublished` 단언 추가(약화 없음). 삭제·@Disabled 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응·보호 테스트 미변경. 수집기 경로는 `scripts/ci/protected-paths.txt`·`.github/CODEOWNERS`에 없음(grep) |
| B8 | 통과 | 새 출력은 날짜·시장 이름뿐. 인증키는 기존 Redaction 경로 그대로. CI Secret scan success |

## 점검표 밖 발견
- [사실] 동작 확인: `notPublished`는 캐시하지 않은 분기에서 빈 목록이면서 캘린더 휴장일이 아닌 경우만 담는다(KrxDailyCollector.java:111). 자료 있는 오늘 응답은 `empty=false`라 빠지고, 오래된 거래일 빈 목록은 앞(:96-100)에서 예외로 멈추므로 섞이지 않는다. "그날 일봉에서 빠지는 시장" 주장은 `source().rows()`가 빈 본문을 그대로 파싱해 해당 시장 행이 0건이 되는 것(KrxDailyCollector.java 189-197행 부근)과 일치한다.
- N1 (권고, 막지 않음): CollectorMain.java:221의 기존 `uncachedDates` Javadoc("이번 실행에서 캐시하지 않은 응답의 날짜")이 새 `notPublishedLine` Javadoc 앞에 남아, `uncachedDates`(:248)는 문서를 잃고 :221은 떠도는 문서 주석이 됐다. Java 21에서는 경고가 아니지만 JDK 23+ `-Xlint:all -Werror`(build-logic java-conventions:20)로 올리면 `dangling-doc-comments`로 빌드가 깨진다 [추론]. :221 줄을 :248 위로 옮기면 된다.
- N2 (권고): `--no-snapshot` 실행에서도 한 줄이 "이번 스냅샷의 그날 일봉에서 빠지는 시장"이라고 쓴다(CollectorMain.java:123이 :124 분기보다 앞). 스냅샷을 만들지 않으니 문구가 사실과 어긋난다. 영향은 표시뿐.
- N3 (참고): 캘린더 수록 범위(`coverageTo`) 밖의 최근 휴장일은 `calendarSaysHoliday`가 거짓이라 게시 전으로 표시된다(:137). 보수적 방향(넘긴 날을 더 알림)이고 캘린더 갱신 시 사라진다.

## 판정
승인
