# architect 검토: D-105 주간 점검에 수집 멈춤 여부(PR #141 strategy-researcher R4)
- 대상: PR #145 / 브랜치 `claude/project-thread-ryg37d` / 커밋 f91a122 / main 1352889 대비 diff(`git diff 1352889...f91a122`, 커밋 1개, 10개 파일 +412 −17)
- 점검표: B
- 직접 실행한 것:
  - 별도 복제본에서 f91a122를 받아 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL in 3m 43s**, 138 tasks. 전체 테스트 결과 XML 합계 1497건, 건너뜀 0, 실패 0, 오류 0. `CollectionFreshnessTest` 7/7, `LiveWeeklyCheckTest` 8/8, `autotrade-architecture-test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest 등) 통과, 순수 모듈 `verifyPureDependencies` 통과.
  - 바뀐 파일을 `scripts/ci/protected-paths.txt` 패턴과 대조 → 보호 대상 일치 없음.
  - 임시 시험(커밋하지 않고 지움)으로 경계 확인: (1) `coverage`가 없는 manifest → `NO_SNAPSHOT`, 그 폴더는 `unreadable`에 들어감, (2) 스냅샷 마지막 거래일이 점검 마지막 날보다 뒤(`-Pto`로 지난 주 재점검) → `OK`, 뒤처짐 없음.
  - 수집기 쪽 계약과 대조: 폴더 `<설정 폴더>/snapshots/krx-daily/<ID>/`(autotrade-app-collector/.../CollectorMain.java:127,363, ADR-008 2.4), ID `krx-daily-<마지막 거래일>-<sha8>`과 임시 폴더 `.tmp-krx-daily-*`(SnapshotWriter.java:38,67), `coverage.lastTradingDate` 기록(CollectorMain.java:141-143). 점검의 기본 폴더(LiveWeeklyMain.java `SNAPSHOTS_DIR`)와 접두사 필터(CollectionFreshness.java:146)가 이와 맞다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 복제본 `./gradlew build` BUILD SUCCESSFUL, 테스트 1497/실패 0/건너뜀 0 |
| B2 | 통과 | 새 클래스 `com.autotrade.sim.live.CollectionFreshness`는 순수 모듈 autotrade-sim 안, 기존 의존 `autotrade-marketdata`(autotrade-sim/build.gradle.kts `implementation(project(":autotrade-marketdata"))`)의 `SnapshotFiles`·`SnapshotManifest`·`ExchangeCalendar`만 씀. Spring·DB·증권사 의존 없음. 빌드 의존 변경 없음, ArchUnit·ModuleOwnershipTest·verifyPureDependencies 통과 |
| B3 | 통과 | 모듈 간 계약(인터페이스·DB 스키마·runtime-config 키·REST) 변경 없음. 바뀐 것은 sim 내부 `LiveWeeklyCheck.run/runAndWrite` 인자와 `Outcome` record(호출자는 같은 모듈 `LiveWeeklyMain`과 테스트뿐), 명령 인자 `--snapshots-dir`/`-PsnapshotsDir`. 결정 D-105와 decisions/README 한 줄, docs/config/config-keys.md 설정 폴더 설명, runbook stage-6 갱신이 있다. 읽는 manifest는 기존 D-019/ADR-008 2.4 계약을 그대로 읽기만 함 |
| B4 | 통과 | `CollectionFreshness.check`는 벽시계를 읽지 않고 점검 마지막 날(`end`)과 번들 캘린더로만 셈(CollectionFreshness.java:128-185). 폴더 순회는 `sorted()`, 같은 날 스냅샷은 ID 큰 쪽으로 순서와 무관하게 고름(:160). 난수 없음. 같은 폴더면 같은 보고서(`picksTheLatestReadableSnapshotAndListsBrokenOnes`), 기존 "같은 입력 같은 보고서" 시험 유지. 결과 파일이 점검 시점의 스냅샷 폴더에 의존하는 재현성 예외는 D-105 "위험과 한계"에 적혀 있음. 골든 테스트 변경 없음·통과 |
| B5 | 해당 없음 | 금액 계산 없음(날짜·개수만) |
| B6 | 통과 | 새 동작 시험 `CollectionFreshnessTest` 7건(스냅샷 없음, 1/2거래일 경계, 주말, 한글날 휴장, 최신 선택·깨진 manifest·임시 폴더 무시, 캘린더 범위 밖 평일 셈, KRX 밖 해당 없음), `LiveWeeklyCheckTest#`깨끗한 세션 시험에 머리 줄·4장·결함과 분리(종료 코드 2 조건 아님) 덧붙임. 기존 시험은 인자 추가로만 바뀌었고 단언 삭제·`@Disabled` 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·보호 경로 변경 없음(보호 경로 대조 일치 0). 실험실 주간 점검(오프라인 명령)과 문서만 바뀜 |
| B8 | 통과 | 비밀값 없음. 보고서에는 스냅샷 ID·폴더 이름과 상대 표기 `krx-daily/`만 쓰고 절대 경로(`Status.snapshotsRoot`)는 결과 파일에 쓰지 않음(CollectionFreshness.java:198). 시험 manifest는 가짜 해시·날짜 |

## 점검표 밖 발견
- N1(낮음, 후속 선택): `coverage`가 없는 manifest(ADR-008 이전 형식, SnapshotManifest 주석상 허용)는 `m.coverage()` NPE로 "읽지 못해 건너뛴 스냅샷 폴더"에 들어간다(임시 시험 PROBE1). 판정 자체는 보수적(NO_SNAPSHOT=확인 필요)이라 놓치는 쪽은 아니지만 이유 표시가 "깨짐"으로 보인다. 지금 수집기는 항상 `coverage`를 쓰므로(CollectorMain.java:143) 실제 영향은 옛 스냅샷이 남은 PC뿐 `[추론]`. 고칠 때는 "마지막 거래일 없음"을 따로 적는 정도면 된다.
- N2(낮음, 문서로 처리됨): `-Pto`로 지난 주를 다시 점검하면 그 뒤 수집된 스냅샷 때문에 "정상(마지막 거래일 <점검 마지막 날보다 뒤>)"으로 나온다(PROBE2). D-105 위험과 한계 1번과 보고서 4장 문구("점검을 돌린 때의 스냅샷 폴더를 본다")에 적혀 있어 수정 필요는 아니다.
- N3(정보): 종료 코드에 넣지 않고 콘솔·결과 파일로만 알리는 선택(D-105 결정 4)은 D-088 종료 코드 2의 뜻을 지키므로 타당하다. 스냅샷 폴더를 읽다 IO 오류가 나면 `UncheckedIOException` → `LiveWeeklyMain` `RuntimeException` 처리로 종료 코드 1, 결과 파일 미작성이 되는데, 이는 같은 명령의 다른 입력 읽기 오류와 같은 처리라 일관된다.
- PR 본문·커밋 메시지의 설명은 근거로 쓰지 않았고 위 판정은 저장소 코드와 직접 실행한 빌드·시험으로만 했다.

## 판정
승인
