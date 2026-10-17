# architect 검토: 주간 점검 머리 줄: 옛 형식만 있을 때·지난 주 재점검 표시, lastTradingDate만 없는 시험(PR #147 선택 권고)
- 대상: PR #152 / 브랜치 claude/project-thread-h282rp / 코드 커밋 2cf16ab(검토 시점 HEAD 6656f33은 strategy-researcher 검토 원문만 더함) / `git diff origin/main...HEAD`
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon` → 종료 코드 0(BUILD SUCCESSFUL). 결과 XML: `CollectionFreshnessTest` 12개 통과(실패·오류·건너뜀 0), `LiveWeeklyCheckTest` 8개 통과. diff 전체(4파일, +50 −5) 직접 읽음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, 종료 코드 0 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-sim/.../sim/live/CollectionFreshness.java`의 `Status.summary()` 문자열 조립뿐. 새 import·의존 없음. ArchUnit·순수 모듈 금지 의존 검사는 위 빌드에서 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. `Status` 레코드 필드 그대로. 보고서 문구 변경은 D-105 "2차 후속"과 runbook(stage-6-live-session.md:58)에 반영됨 |
| B4 | 통과 | 시계·난수 미사용. `summary()`는 `Status` 필드만 읽는 순수 함수. 판정(`kind`, `warn()`)과 종료 코드 불변(CollectionFreshness.java:67-69, 155-157). 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 정확한 문자열 기대값: `recheckingAnEarlierWeekSeesNewerSnapshotsAsNormal`(newerThanEnd 있음/없음 둘 다, Test:119-123), `oldManifestWithoutCoverageIsListedApartFromBrokenOnes`(옛 형식만, Test:144-145), 새 `coverageWithoutLastTradingDateIsOldFormatToo`, 새 `noSnapshotSummaryWithoutOldFormatStaysShort`. 지우거나 약화한 시험 없음(main 10개 → 12개). 기존 `LiveWeeklyCheckTest:152` "일봉 스냅샷이 없음" 단언도 그대로 통과 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응·보호 경로(`scripts/ci/protected-paths.txt`) 변경 없음. 주간 점검 보고 문구만 |
| B8 | 통과 | 새 문자열·시험 데이터에 비밀값 없음(스냅샷 ID·날짜뿐) |

## 점검표 밖 발견
- [사실] PR #147 architect 2·3, strategy-researcher P2 처리 확인. 3: `newerThanEnd`면 OK 요약에 표시(CollectionFreshness.java:79-81). 콘솔 줄은 `LiveWeeklyCheck.java:233`에서 판정과 무관하게 항상 `summary()`를 찍으므로 runbook 문구("머리 줄과 4장")와 코드가 맞고 콘솔에도 나온다. OK 분기에서 `behind` 비어 있지 않음과 `newerThanEnd`는 동시에 참일 수 없다(`best.isAfter(end)`면 루프가 돌지 않음) — 두 꼬리가 겹친 문장은 생기지 않는다.
- [사실] D-105 시험 수 "2차 후속으로 12"는 실제 `@Test` 12개와 맞다. runbook의 "수집기를 다시 돌리면 새 형식이 생긴다"는 `KrxDailySnapshotBuilder`가 `lastTradingDate`를 적는 것(KrxDailySnapshotBuilder.java:54-69)과 맞다.
- 낮음, 선택: 옛 형식만 있을 때 4장 "할 일"(CollectionFreshness.java:211-212)은 여전히 "수집기를 아직 돌리지 않았거나 폴더가 다르다"만 적는다. 머리 줄과 runbook은 "옛 형식 N개, 다시 돌리면 새 형식"이라 원인 설명이 조금 어긋난다. 판정·행동(수집기를 돌린다)은 같으므로 결함 아님.
- 낮음, 선택: NO_SNAPSHOT에서 옛 형식과 깨진 폴더가 함께 있으면 머리 줄은 옛 형식 수만 적고 깨진 폴더 수는 4장에만 있다. 4장에 둘 다 나오므로 오해 위험은 작다.
- PR #147 architect 1(선정 프로필 연결 시 4장 문구·시험 동반 수정)은 D-105 "남은 것"에 기록됨. 이 PR 범위 밖.

## 판정
승인
