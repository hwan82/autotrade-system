# architect 재검토: runbook 3.6 수능 급변경 단계에 설치본 재생성·반영 전 수동 중지, D-064 갱신 이력 (PR #56)
- 대상: PR #56 / 커밋 b45d9cf19d7c1086308f36bee89af6b8e30702dd (GitHub REST `pulls/56`의 head SHA와 같음) / main `0d6b364`(merge-base와 같음) 대비 `git diff origin/main...HEAD` 전체
  - 바뀐 파일 4개(로컬 diff와 GitHub REST `pulls/56/files`가 같음): `docs/runbooks/collector.md`(+2 −2), `docs/decisions/D-064-krx-2027-calendar.md`(+1), `docs/reviews/pr-56/architect.md`(신규, 1차 원문), `docs/reviews/pr-56/risk-ops.md`(신규). `docs/` 밖 변경 0건
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`: BUILD SUCCESSFUL(13s, 130 tasks 중 19 실행·111 up-to-date). 문서만 바뀌어 테스트 대부분이 재사용됐으므로 아래로 다시 돌렸다
  - `./gradlew --no-daemon --continue build --rerun-tasks`: 종료 코드 0, BUILD SUCCESSFUL(2m 24s). 결과 XML 전체 집계 890건, 실패 0·오류 0·건너뜀 0, 깨진 XML 0
  - 캘린더를 읽는 모듈 강제 재실행(`cleanTest test`, marketdata·app-collector·app-experiment·architecture-test 함께) 12회: 10회 연속 통과(marketdata 84, app-experiment 11, architecture-test 31, app-collector 61, 모두 실패 0), 처음 2회는 실패(아래 N1)
  - GitHub REST `commits/b45d9cf…/check-runs`: Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - diff에서 `@Disabled`·key·secret·token·8자리 이상 숫자 grep: 매치는 risk-ops 원문 안의 "검색 0건" 문장 1줄뿐
  - 새 문장을 `ExchangeCalendar.java:137-155`, `krx.json:16-17·56·58`, `collector.md` 4장(라인 79-82), `autotrade-app-collector/build.gradle.kts:8`·`autotrade-app-experiment/build.gradle.kts:9`와 대조
- PR 본문·커밋 메시지·1차 검토 원문·risk-ops 원문은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build`와 `build --rerun-tasks`를 직접 실행해 둘 다 성공, 890건 실패·오류·건너뜀 0. CI 3종 success. 강제 재실행 중 2회 실패는 원인 미확인이나 이 PR 변경(문서뿐)과 무관하다(N1) |
| B2 | 해당 없음 | 코드·빌드 설정·ArchUnit 규칙 변경 없음(diff 4개 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 캘린더 데이터 `krx.json`도 바뀌지 않음. runbook 절차 문구와 결정 로그 갱신 이력 한 줄뿐 |
| B4 | 해당 없음 | 운영 코드·골든 데이터 변경 없음. `--rerun-tasks` 빌드에서 골든 테스트 포함 전부 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 없음(절차 문서). 테스트 파일 diff 0줄, 건너뛰거나 지운 테스트 없음 |
| B7 | 해당 없음(코드 기준). risk-ops 검토 필요 | 실전 주문 경로·Guard·블랙리스트 코드 변경 없음. 다만 runbook 3.6은 장 시간 보호(D-058·D-063·D-064, 장애표 #10)의 판정 입력 갱신 절차이고 이번 diff가 "반영 전 수동·예약 실행 중지"라는 장애 대응 운영 규칙을 새로 넣는다. A 점검표 검토(`docs/reviews/pr-56/risk-ops.md`)는 b45d9cf 이전 커밋 `0d53ae1` 기준이라 새 문장에 대한 risk-ops 재검토가 따로 있어야 한다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 내용은 날짜, 명령 이름, 문장뿐 |

## 내용 대조 (1차 지적 반영 여부)
- [사실] 1차 N1(설치본 재생성): 2번에 "PR·사용자 머지를 거쳐 `./gradlew :autotrade-app-collector:installDist`로 설치본을 다시 만들어야 예약 실행에 반영된다"가 들어갔다. 예약 실행이 설치본을 쓴다는 것은 `collector.md:79-82`와 맞고, 작업 이름은 `autotrade-app-collector/build.gradle.kts:8`의 `application` 플러그인과 맞다. 실험 명령은 Gradle 작업(`:autotrade-app-experiment:runCombinationExperiment` 등, D-063)으로 돌아 머지 뒤 빌드에서 자동으로 새 리소스를 쓰므로 설치본 단계가 따로 필요 없다. 해소
- [사실] 1차 N2(`verified: false` 명시): 2번에 "(`"verified": false` 유지)"가 들어갔다. `ExchangeCalendar.java:148-153`에서 미확인 특별 운영일은 정규장과 합친 구간(09:00~16:30)이 되어 보호 08:00~17:00이 걸린다. 해소
- [사실] risk-ops N1(반영 전 임시 조치): "반영이 새 시험일까지 끝나지 않으면 그날 서울 16:00~17:00에는 수집기·실험 명령을 수동으로 시작하지 않고 그 시간대 예약 실행도 끈다"가 들어갔다. 캘린더에 없는 날은 `protectionSession`이 정규장(08:00~16:00 보호)을 돌려주므로 빠지는 구간이 정확히 16:00~17:00이다. 시각은 "서울"로 명시돼 있다. 해소
- [사실] risk-ops N2(결정 로그 이력): `D-064-krx-2027-calendar.md:42`에 PR #56 갱신 이력 한 줄이 들어갔고, 적힌 내용(날짜 이동, 새 출처 키, 설치본 재생성, 반영 전 16:00~17:00 수동·예약 실행 중지, 1번 발표 시점 문구)이 runbook diff와 하나씩 맞는다. "데이터·동작 변경 없음"도 diff와 맞다
- [사실] 1번 발표 시점 "약 2년 3~5개월 전, 예: 2025-06-02, 2024-08-16": `krx.json:16`(2024-08-16 → 2026-11-19, 약 2년 3개월), `krx.json:17`(2025-06-02 → 2027-11-18, 약 2년 5개월 반)과 맞다
- [추론] 반영 전 옛 시험일에는 미확인 특별 운영일이 남아 보호가 08:00~17:00으로 넓게 걸린다. 과보호 쪽이라 안전하다
- 단계 번호는 그대로(1~6)라 D-064의 "runbook 3.6 3번" 참조가 깨지지 않는다

## 점검표 밖 발견
- **N1 (기록, 원인 확인 못 함 — 이 PR과 무관)**: 캘린더 관련 4개 모듈을 `cleanTest test`로 함께 강제 재실행한 첫 2회에서 `:autotrade-marketdata:test`·`:autotrade-app-collector:test`가 실패했다(1회차는 collector 결과 XML 하나가 빈 파일). 같은 명령 10회 연속, collector 단독 1회, `build --rerun-tasks` 1회는 모두 통과했다. 실패 회차의 출력은 다음 회차 `cleanTest`가 지워 실패한 테스트 이름을 남기지 못했다. 이 PR은 `docs/`만 바꿔 컴파일·테스트 입력이 같으므로 이 PR의 판정에는 영향이 없다고 본다. 다만 불안정 테스트(시간 의존·대기열 시간 제한을 쓰는 `RecordingReplayTest#녹화_큐가_넘치면_기다리지_않고_버린_뒤_누락을_표시한다`의 `Duration.ofMillis(50)`·`Thread.sleep(5)` 등이 후보, `[가정]`) 또는 테스트 JVM 중단 가능성이 있으므로, 실패 시 결과를 보존하는 반복 실행으로 원인을 찾는 후속 과제를 data-engineer에게 남기기를 권한다
- **N2 (권고, 절차 일관성 — 머지를 막지 않음)**: 설치본 재생성은 2번 급변경 문장에만 있고, 1번·3번(연간·공지 대조 갱신) 뒤의 공통 검사 단계인 6번은 여전히 `./gradlew build`까지만 적는다. 1번·3번 갱신도 예약 실행에 반영하려면 같은 `installDist`가 필요하므로 6번에 한 번 적는 편이 중복이 없다
- **N3 (기록)**: `docs/reviews/README.md`는 재검토를 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이라고 정한다. 이번 재검토는 요청에 따라 1차 원문을 그대로 두고 `architect-2.md`로 따로 썼다. 원문 보존 취지는 같지만 위치 규칙과 다르므로 README에 별도 파일 방식을 허용하는 문구를 넣거나 다음부터 한 방식으로 맞추기를 권한다
- **N4 (기록)**: 1차 N3(2017·2020년 연기 사례를 1차 자료로 대조하지 않음)는 그대로다. 예시 문구라 판정 입력에 영향 없음

## 판정
승인. B 전 항목에 실패·확인 못 함이 없다(B2~B5 해당 없음, B7은 코드 기준 해당 없음이나 risk-ops 검토 필요). 1차 N1·N2와 risk-ops N1·N2가 diff에서 해소된 것을 코드·데이터와 대조해 확인했다. N1(원인 미확인 간헐 실패)은 이 PR 변경과 무관한 기존 테스트 문제로 기록하며, N2·N3는 머지를 막지 않는 권고다. 이 PR은 장 시간 보호 갱신 절차를 바꾸므로 `b45d9cf` 기준 risk-ops 재검토와 지침 10장·11장의 사용자 머지·독립 QA 관문을 따른다.
