# architect 재검토: 수집 일괄 명령 autotrade-app-collector(Q-037, D-051, ADR-008) 수정본
- 대상: PR #39 / 브랜치 claude/project-thread-vip8tu / 커밋 ab86736(9690af8, 89fae05, ecd2af6 포함, 브랜치 이력이 다시 쓰여 1차 검토의 0720a8f·26ca46a·366b771은 9690af8 하나로 합쳐짐) / main 대비 diff 전체(`git diff origin/main...HEAD`, 64개 파일). 1차 검토 원문: [architect.md](architect.md). risk-ops 1차 원문: [risk-ops.md](risk-ops.md)
- 점검표: B
- 같은 재검토 내용을 docs/reviews/README.md 규칙대로 [architect.md](architect.md) 끝의 `## 재검토 2026-10-10`에도 붙였다(1차 내용은 고치지 않음). 두 곳의 본문은 같다.
- 직접 실행한 것:
  - `./gradlew clean build --no-build-cache --no-parallel --max-workers=1` 1회차 → EXIT 1(`:autotrade-marketdata:compileTestJava`에서 `autotrade-domain/build/classes/.../MarketRules.class` NoSuchFileException). 같은 작업 폴더에서 다른 Gradle 실행(`--max-workers=1`, 다른 검토 세션으로 보임)이 동시에 돌며 빌드 산출물을 지운 탓이다. 코드 원인이 아니다(`ps`로 동시 실행 확인).
  - 그 실행이 끝난 뒤 `./gradlew build --rerun-tasks --no-daemon --no-parallel --max-workers=1`(HEAD ab86736, 작업 트리 깨끗) → **BUILD SUCCESSFUL, EXIT 0**, 112 tasks 모두 실행. 결과 XML 합계 tests=601, skipped=0, failures=0, errors=0. 수집기 48건(RunGuardTest 6, SnapshotRetentionTest 2, CollectorMainTest 9, DartFinancialCollectorTest 8, KrxDailySnapshotBuilderTest 6 포함), ArchitectureTest 16, ModuleOwnershipTest 13. `verifyProcessIsolation`은 collector·engine·gateway·watchdog 모두 통과.
  - 임시 확인 테스트(`ZzReviewProbe2Test`, 실행 뒤 지움)로 1차 지적과 새 기능을 직접 확인했다.
    - F1: `collect(2026-03-01, 2026-03-31)` 뒤 `collect(2016-01-01, 2026-10-09)` → 첫 `bgn_de=20160101`, 이미 훑은 2026-03 창은 다시 부르지 않음. **고쳐짐.**
    - F2: 어제 접수된 원 공시 + 계정 응답 013 → `pending=1`, `accounts/<접수번호>.json` 안 씀(다음 실행에서 다시 부름). **고쳐짐.**
    - 스냅샷 보관: 기본 배치(`<root>/snapshots/krx-daily/<ID>` 3개, 각자의 품질 리포트 `experiments/_data-quality/<ID>.md`)에서 `prune(keep=1)` → **지운 것 0개.** 아래 G1.
  - `bash scripts/ci/check-python-collectors.sh`(기본 폴더) EXIT 0. 바뀐 패턴 `crtfc_key["']?\s*[:=]…`은 1차 R4에서 검토자가 쓴 두 입력을 모두 잡는 모양이다(diff 확인).
  - gitleaks는 이 환경에 없어 직접 돌리지 못했다. `.gitleaks.toml`(기본 규칙 + 정확히 `^abcdef1234567890$` 한 값만 허용)은 내용으로 검토했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 실행 `./gradlew build --rerun-tasks --no-parallel --max-workers=1` EXIT 0, 601건 실패 0·건너뜀 0(HEAD ab86736). 첫 clean build 실패는 동시 Gradle 실행 간섭(위 기록) |
| B2 | 통과 | 1차 근거 유지. 추가로 engine·gateway·watchdog `build.gradle.kts`의 `processIsolation.forbiddenProjects`에 `:autotrade-app-collector`를 넣었다(빌드 수준 격리 강화, 줄 추가만). 수집기는 `autotrade-marketdata` 거래소 캘린더(`ExchangeCalendar.bundled`)만 새로 쓴다. ArchUnit 규칙 변경 없음(1차 이후) |
| B3 | 통과 | 새 명령줄 옵션(`--min-free-gb`, `--keep-snapshots`, `--allow-during-session`)과 종료 코드 4, `state.json` 형식 변경(`scannedThrough` → `scanned[{from,to}]`)이 ADR-008 2.5·2.7과 runbook 3.5·4·5.1장에 적혀 있다. 설정 파일 키는 늘지 않았다. 결정 로그 D-051 ⑤와 Q-041 추가. `state.json`은 수집기 내부 캐시라 외부 계약이 아니다. 예전 형식 파일은 무시되어 처음부터 다시 훑는다(호출만 더 쓰고 결과는 같음, 아직 배포 전) |
| B4 | 통과 | `RunGuard`는 주입 `Clock`만 쓴다(`RunGuard.java` `clock.instant()`). 보관 규칙의 정렬은 manifest 생성 시각, 같으면 이름(`SnapshotRetention.java`). 수정 계수 코드 재사용 판정은 거래일 순번 기준이라 결정론적. 골든 테스트 변경 없음, 통과 |
| B5 | 통과 | 1차 근거 유지. 새 코드에 금액 계산 없음 |
| B6 | 통과 | 1차 F1·F2와 risk-ops F1·F2, R1·R3·R4·N1에 테스트가 붙었다(`DartFinancialCollectorTest#첫_실행_뒤_from을_앞당기면_앞_구간만_더_훑는다`, `#훑지_않은_구간은_요청_범위에서_훑은_구간을_뺀_것이다`, `#새_공시의_계정이_아직_없으면_013_기한_안에는_대기한다`, `RunGuardTest` 6건, `CollectorMainTest#국내_장_시간에는_시작하지_않고_종료_코드_4`·`#실행_중_보호_구간에_들어서면_다음_호출_전에_멈춘다`·`#디스크_여유가_기준_미만이면_시작하지_않는다`·`#주소는_https만_받는다`, `KrxDailySnapshotBuilderTest#오래_비었다가_다시_나온_코드는_옛_종가로_수정_비율을_잡지_않는다`). 지우거나 끈 테스트 없음. 다만 보관 규칙 테스트(`SnapshotRetentionTest`)는 품질 리포트가 실제로 놓이는 자리를 빼고 만들어 G1을 잡지 못한다. `CallBudget` 잠금에는 동시 실행 테스트가 없다(권고) |
| B7 | risk-ops 검토 필요 | 이 PR은 실전 엔진·게이트웨이·감시의 빌드 격리 설정을 바꾸고(강화), 장애 대응 정지 순서와 관련된 Q-041을 올리며, ADR-008 머리말도 "risk-ops 검토 후 사용자 머지 대상"으로 고쳤다. risk-ops 1차 판정은 수정 필요였고, 이 재검토를 쓰는 동안 [risk-ops.md](risk-ops.md) 끝에 risk-ops 재검토(`## 재검토 2026-10-10`, 판정 **승인**)가 작업 트리에 덧붙었다(아직 커밋 전, 검토자가 `git diff`로 확인). 그 원문이 커밋되어 PR에 들어가야 B7이 갖춰진다. 지침 10장에 따라 머지는 사용자가 한다 |
| B8 | 통과 | 1차 근거 유지. 새로 `https://`만 받는 주소 옵션(`CollectorMain.java` `https()`), 리다이렉트 안 따름(`JdkHttpGetter.java` `Redirect.NEVER`). `RunGuard`·`SnapshotRetention` 메시지에 키 없음. `.gitleaks.toml` 허용 값은 검토 원문에 인용된 가짜 시험 입력 하나이고 실제 키가 아니다 |

## 1차 지적 처리
| 1차 항목 | 결과 | 근거 |
|---|---|---|
| F1 공시 훑기 기준점 | 해결 | `DartFinancialCollector.java` `scannedRanges`·`gaps`·`merge`, 위 재현 확인과 테스트 2건 |
| F2 013 대기 누락 | 해결 | `DartFinancialCollector.java` `notYet = answered.isEmpty() \|\| answered < no`, 재현 확인과 테스트 |
| F3 / B7 risk-ops 검토 | 진행 중 | risk-ops 1차 원문 있음(수정 필요). 재검토 대기 |
| R1 코드 재사용 | 반영 | 20거래일 넘게 비면 전일 종가를 잇지 않고 건수 기록(`KrxDailySnapshotBuilder.java` `REAPPEAR_GAP_DAYS`), 테스트 |
| R2 평일 빈 응답 캐시 | 미반영 | 그대로. 권고로 남김 |
| R3 리다이렉트 | 반영 | `Redirect.NEVER` |
| R4 CI 패턴 | 반영 | `check-python-collectors.sh:14` |
| R5 호출 기록 잠금 | 반영 | `CallBudget.locked`(`FileChannel.lock`). 테스트 없음 |
| R6 대체 규칙 묶음 집계 | 미반영 | 그대로. 권고로 남김 |
| R7 주소 옵션 | 반영 | `https://`만 허용 |

## 점검표 밖 발견

### G1. (수정 필요) `--keep-snapshots`가 기본 배치에서 스냅샷을 하나도 지우지 못한다
- `[사실]` `SnapshotRetention.prune`은 `experimentsDir` 아래 모든 텍스트 파일(깊이 4, 4MB 이하)에 ID가 나오면 "실험이 참조"로 보고 남긴다. `CollectorMain.prune`은 `experimentsDir = reportDir.toAbsolutePath().getParent()`로 정한다. 기본 `--report-dir`은 `experiments/_data-quality`이고, 수집기는 스냅샷마다 그 폴더에 `<ID>.md`를 쓰며 첫 줄이 `# 데이터 품질 리포트: <ID>`다(`QualityReport.java:68,96-98`, 재무는 `CollectorReports.writeFinancial`). 그래서 수집기가 만든 모든 스냅샷이 자기 품질 리포트 때문에 "참조됨"이 된다.
- `[사실]` 검토자 재현: 스냅샷 3개와 각 품질 리포트를 기본 배치로 두고 `prune(keep=1)` → 지운 것 0개. `SnapshotRetentionTest`는 품질 리포트를 두지 않은 `experiments` 폴더로만 시험해서 이 경우를 잡지 못한다. `CollectorMainTest`에는 `--keep-snapshots` 왕복 테스트가 없다.
- 영향: risk-ops 1차 F2(디스크 무제한 증가)의 대책으로 runbook 4장이 매일 예약 작업에 `--keep-snapshots 3`을 권하지만 실제로는 정리되지 않는다. 디스크 여유 검사(20GB)가 마지막 방어선으로 남아 실전 엔진 쪽 위험은 "수집기가 종료 코드 4로 멈춤"까지로 막히지만 `[추론]`, ADR-008 2.7·runbook 5.1이 적은 동작과 코드가 다르다.
- 함께 볼 것(risk-ops 재검토 R3과 같은 지적, 검토자 확인): 실험실 DB는 실험이 쓴 스냅샷 ID를 `experiment.data_snapshot_version`에 둔다(`autotrade-app-lab/src/main/resources/db/migration/V1__lab_baseline.sql:27`, `ExperimentViews.java:43,53`). G1을 고쳐 정리가 실제로 돌기 시작하면 DB에만 기록된 실험의 스냅샷이 지워질 수 있다. 수정 때 DB 참조를 함께 막거나(수집기는 DB 드라이버를 쓰지 않으므로 실험실이 참조 ID 목록 파일을 `experiments/` 아래 남기는 방식 등), runbook에 그 한계를 적는다.
- 함께 볼 것: `--report-dir`을 저장소 밖(예: `D:\reports`)으로 주면 그 상위 폴더(드라이브 루트)를 깊이 4까지 읽는다. 참조 검사 범위가 사용자의 다른 파일로 번진다.
- 고칠 것: 참조 검사에서 품질 리포트 폴더(`reportDir`)를 빼거나 `<ID>.md` 자기 리포트를 제외한다. 실험 폴더는 `reportDir`의 상위를 추정하지 말고 저장소 `experiments/`(또는 명시 옵션 `--experiments-dir`)로 정한다. "품질 리포트가 기본 자리에 있어도 최근 K개 밖은 지운다"와 "실험 설정에 ID가 적힌 것은 남긴다"를 함께 시험하는 테스트(가능하면 `CollectorMainTest` 왕복)를 더한다.

### 권고 (머지를 막지 않음)
- **H1. 스냅샷 단계 중 보호 구간 진입**: `checkSession`은 API 호출 전과 스냅샷 단계 시작 때만 본다. 전 기간 스냅샷·품질 리포트(2GB 힙)가 길어지면 미국 보호 구간(서울 21:30, 서머타임) 안까지 이어질 수 있다. `KrxDailySnapshotBuilder` 두 번째 훑기의 날짜 반복마다 확인하거나, runbook 3.5에 "전 기간 스냅샷은 18:30 바로 시작" 같은 안내를 둔다.
- **H2. 훑은 구간 사이 빈틈이 스냅샷에 드러나지 않음**: `scanned`가 여러 구간(예: 2016~2017, 2026)일 때 스냅샷 `coverage`에는 첫 접수일과 마지막 날만 있다. 품질 리포트에 훑은 구간 목록을 적는다.
- **H3. 1차 R2·R6 미반영**: 평일 빈 응답을 휴장일로 영구 캐시하는 것(`KrxDailyCollector`), 첫 행이 정정 공시인 묶음을 대체 규칙 대상으로 세지 않는 것(`DartSnapshotBuilder`)은 그대로다. 후속 과제로 남겨도 된다.
- **H4. 캘린더 수록 범위**: KRX 캘린더가 2026-12-31까지라 2027-01-01부터 수집기가 모두 종료 코드 4로 멈춘다(안전한 쪽 실패, ADR-008·runbook에 적혀 있음). 연말 전 캘린더 갱신 작업을 결정 로그 후속 과제나 Q 항목으로 올려 두기를 권한다.

## 판정
수정 필요(G1 스냅샷 보관 규칙이 기본 배치에서 동작하지 않음, B7 risk-ops 재검토 원문 필요)
