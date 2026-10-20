# architect 재검토: 공시 스냅샷 수집 dart-disclosures·지표 disclosure-count·선정 프로필 (라)(ADR-012 배치표 3번, D-123)
- 대상: PR #196 / 브랜치 `claude/project-thread-s7vbig` / 헤드 커밋 a0d6841(코드 마지막 커밋. 그 뒤 b0a8ae5는 `docs/reviews/pr-196/strategy-researcher-2.md` 1파일만 더함) / `git diff origin/main...a0d6841` 전체(56파일 +2,805 −78, 병합 기준 main e9a7ee2 = 현재 origin/main)
- 앞선 원문: [architect.md](architect.md)(a22663c 기준, 수정 필요 F1~F3). 이번에는 앞선 검토가 보지 못한 473d19e(strategy-researcher M1·M2 대응)와 a0d6841(F1~F3 대응)을 포함해 PR 전체를 다시 봤다.
- 점검표: B
- 직접 실행한 것:
  - `git diff a22663c a0d6841` 전체(코드·시험·문서)를 읽고, PR 전체 diff는 앞선 검토 범위와 겹치지 않는 부분 위주로 다시 대조.
  - 깨끗한 detached worktree(a0d6841)에서 `./gradlew build --rerun-tasks --no-daemon`: **BUILD SUCCESSFUL in 4m 1s**, 시험 XML 합계 1,721건 실패·오류·건너뜀 0(순수 모듈 금지 의존 검사·ArchUnit 포함). 공유 작업 트리에는 커밋되지 않은 `CollectorReports.java`·`DartDisclosureSnapshotBuilder.java` 수정이 있었으나 보지 않았다(판정 대상 아님).
  - 보호 경로 대조: 바뀐 56파일을 `scripts/ci/protected-paths.txt` 패턴(bash glob)과 대조 → 해당 0건.
  - 비밀값: diff에서 `crtfc_key=` 값·키 형태 문자열 검색 0건.
  - 재현 시험(검토용 임시 시험, 커밋하지 않고 지움): 접수일 1999-12-10 행 하나가 든 창 파일로 `DartDisclosureSnapshotBuilder.build(…)` → `IndexOutOfBoundsException: bitIndex < 0`(`DartDisclosureSnapshotBuilder.java:119`). 아래 F4.

## 앞선 지적 처리 확인

| 지적 | 결과 | 근거 |
|---|---|---|
| F1 창 파일 겹침 중복 | 해결 | `DartDisclosureCollector.windowFiles`가 훑은 구간 안 창만 고르고 겹치면 `IOException`, `collect` 시작에 `pruneOrphans`로 훑은 구간 밖 창 파일을 지움(쪽 단위 진행 파일은 `partial/`에 있어 영향 없음). 빌더는 정렬 뒤 인접 행 접수번호 중복을 거부. 시험 `DartDisclosureCollectorTest#훑은_구간을_적기_전에_끝난_창_파일은_두_번_세지_않는다`(남은 파일·state.json 분실·겹침 실패), `DartDisclosureSnapshotBuilderTest#같은_접수번호가_두_번이면_스냅샷을_만들지_않는다` |
| F2-1 ADR-007 `inputs.disclosures` | 해결 | ADR-007 2.6 예시와 설명 문단(키 생략 규칙, `sha256` = `disclosures.csv` 해시, `covered_through`, 7단계 `SelectionValidity`는 risk-ops 검토) |
| F2-2 ADR-008 호출 상한 | 해결 | ADR-008 호출 상한·호출 한도 단위 두 줄에 몫 규칙과 여유 감소(기본 합 12,000, 최대 20,000) |
| F2-3 ADR-012 경로·manifest 서술 | 해결 | ADR-012 3.2·3.4에 구현 경로(`snapshots/dart-disclosures/<ID>/`)·품질 리포트 서술, 빌더 클래스 주석 고침 |
| F3 Javadoc 자리 | 해결 | `CollectorReports.gapSections` 위로 옮김 |
| R1 수집 구간 겹침 거부 | 반영 | `SnapshotDisclosures.load`, `SnapshotDisclosuresTest` 한 줄 |
| R3 순위 목록에서 `disclosure-count` 제외 | 반영(시험 없음) | `selections.js` `metricOptions` 필터 |

## 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 깨끗한 worktree a0d6841 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 1,721건 실패·오류·건너뜀 0 |
| B2 | 통과 | 473d19e·a0d6841은 새 의존을 더하지 않음. `autotrade-selection` 변경은 `SelectionCalculator`·`Metric`·`SelectionCriteria`·`SnapshotDisclosures` 안쪽뿐, 수집기 변경은 `autotrade-app-collector` 안쪽뿐. ArchUnit·`ModuleOwnershipTest` 통과 |
| B3 | **실패(F6)** | 계약 문서는 앞선 F2 항목이 모두 맞춰졌다. 새로 바뀐 계약 "공시 건수 시점 접수일 < T, 구간 [w거래일 첫날, T−1]"(D-123 6번, D-121 ③ 대체)은 D-123·README·spec v14.57·카탈로그·ADR-012 3.1(36행)·51행 주석·runbook에 반영됐다. 그러나 ADR-012 3.2 지표 표의 `disclosure-count` 정의(47행 "앞 거래일 다음 날 ~ c, 휴장일 접수분은 다음 거래일 구간에 포함")는 옛 정의 그대로이고 바뀜 표시가 없다 |
| B4 | 통과 | 시점 변경(473d19e): `disclosureFrom` = c에서 끝나는 w거래일 첫날, `disclosureTo` = `apply_from − 1`(달력일), 계산과 신선도 검사(`checkDisclosures`)·`covered_through`가 같은 끝날을 씀(`SelectionCalculator.java` `checkDisclosures`·`disclosuresThrough`·`disclosureCount`). T 당일 접수는 쓰지 않으므로 미래 정보 없음(MD-10 "접수일 다음 거래일부터"와 같음). `lookbackDays` w+1 → w는 구간 첫날이 창 안에 있으므로 맞음. 시험: `DisclosureSelectionCalculatorTest#휴장일_접수_공시는_그_뒤_첫_선정에_한_번만_넣는다`(연휴 접수분이 정확히 한 번), `#T_당일과_T_뒤_접수_공시는_결과를_바꾸지_않는다`, `#공시_스냅샷이_구간을_다_담지_않으면_0건으로_내지_않고_실패한다`(c 뒤 주말 미수록 → STALE_INPUT). 순회는 `TreeMap`·정렬 목록. 단 품질 리포트 계산에 1999년 이전 접수일에서 터지는 결함이 있다(F4) |
| B5 | 통과 | 금액 계산 변경 없음. 리포트 비율 `double` 서식은 표시용(`CollectorReports.writeDisclosures`, 계산 결과에 들어가지 않음) |
| B6 | 통과(F5 별도) | 새 동작마다 시험이 있다(위 표). 기존 시험 수정은 시점 규칙 변경에 맞춘 기대값 갱신이며 손 계산 주석이 같이 바뀌었다(`DisclosureSelectionCalculatorTest` 클래스 주석·`types_키_규칙` w → w). 삭제·`@Disabled` 0건. 상장폐지 집계 시험(`DartDisclosureSnapshotBuilderTest` 첫 시험 `delisted()`)은 이력 행 끝 = 상장폐지로 가정해 F5를 잡지 못한다. UI 필터는 시험 없음(권고) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동·보호 경로 변경 없음(대조 0건). OpenDART만 부르고 주문 API 없음. 선정 결과 `inputs.disclosures`를 7단계 `SelectionValidity`가 쓸지는 ADR-007에 risk-ops 검토 대상으로 적혀 있다 |
| B8 | 통과 | 새 코드는 키를 다루지 않음. 새 예외 메시지는 파일 경로·접수번호만 담음(`windowFiles`, `build`). diff 비밀값 검색 0건 |

## 점검표 밖 발견

### F4 (수정 필요, 작음) 2000년 이전 접수일이 있으면 스냅샷 만들기가 `IndexOutOfBoundsException`으로 멈춘다
- [사실] `DartDisclosureSnapshotBuilder.build`는 `mappedYears…set(date.getYear() - 2000)`로 연도 비트를 세운다(`DartDisclosureSnapshotBuilder.java:119` 근처). `CollectorMain` 옵션 해석은 `--from` 하한을 검사하지 않는다(`--to`가 앞인지만 봄). 그래서 `dart-disclosures --from 1999-…`로 받은 창에 1999년 행이 하나라도 있으면 빌드가 터진다.
- [사실] 검토용 임시 시험으로 재현: 접수일 1999-12-10 행 1건 → `IndexOutOfBoundsException: bitIndex < 0`.
- 영향 [추론]: 창 파일은 이미 캐시와 `state.json`에 남으므로 그 뒤 실행마다 스냅샷을 못 만든다(사람이 캐시를 지워야 풂). 같은 식의 `lo = … - 2000`은 `lo >= 0` 검사로 조용히 건너뛰어 상장폐지 집계도 빠진다.
- 고칠 방향(하나): ① `--from` 하한을 두고(예: OpenDART 공시검색이 주는 가장 이른 날, 모르면 2000-01-01 `[가정]`) 앞이면 옵션 단계에서 거부, 또는 ② 비트 기준을 수집 구간 첫 해(`first`)로 바꾸기(`BitSet` 대신 `TreeSet<Integer>`도 됨). 실패하는 시험부터.

### F5 (수정 필요) 상장폐지 집계가 이름·소속부 변경으로 끝난 이력 행도 상장폐지로 센다
- [사실] `CollectorMain.listings`는 `instrument_history.csv` 행마다 `Listing`을 만든다. 이 파일은 `market,code,name,kind,board,valid_from,valid_to`로, 이름·소속부가 바뀌면 같은 코드의 앞 행이 `valid_to`로 끝난다(`SnapshotDailyBarSource.HISTORY_HEADER`). `build`는 `l.to() != null`인 행을 모두 "수집 구간 안에 상장폐지된 KRX 종목 구간"으로 센다.
- 영향 [추론]: 상장을 이어 가는 회사의 이름 변경 구간은 공시가 잘 붙으므로 비율을 끌어올려, 이 표가 잡으려는 상장폐지 회사의 낮은 비율을 가린다. runbook 4장은 이 표를 "확인 전에는 과거 구간 (라) 결과를 선정 엣지 근거로 쓰지 않는" 관문으로 쓰므로 수치가 사실과 다르면 관문이 약해진다. strategy-researcher 재검토 원문(N1)과 같은 지적이다.
- 고칠 방향: 같은 시장·코드의 이력 행 중 **마지막 행**이 `valid_to`를 가진 경우만 상장폐지로 보고, 상장 구간은 그 코드 첫 행 `valid_from` ~ 마지막 `valid_to`로 잡는다. 시험: 같은 코드 두 행(이름 변경) + 마지막 행 열림 → 상장폐지 0.

### F6 (수정 필요, 문서) ADR-012 3.2 지표 표의 `disclosure-count` 정의 동기화
- [사실] ADR-012 47행 표 칸이 옛 구간 "(앞 거래일 다음 날 ~ c, 휴장일 접수분은 다음 거래일 구간에 포함)"을 그대로 둔다. 같은 ADR 36·51행과 D-123 6번·카탈로그는 [w거래일 첫날, T−1]이라 한 ADR 안에서 정의가 둘이다. 표 칸에 "(D-123에서 바뀜: [w거래일 첫날, T−1], 정의는 selection-catalog)"처럼 바뀜 표시를 단다. 22·23행 결정표 ③ "접수일 ≤ c"에도 같은 표시를 다는 것을 권한다.

### 권고(판정에 넣지 않음)
- R1 연도 단위 판정이라 "상장 구간 안 공시"는 같은 해 상장폐지 뒤 공시도 셀 수 있다(표시용 근사). F5를 고칠 때 날짜 단위로 바꾸면 단단해진다(메모리는 코드별 첫·마지막 접수일 두 칸이면 됨).
- R2 D-123 상태 줄에 "D-121 ③을 대체하므로 사용자 확인 전에는 머지하지 않음"이 적혀 있다(지침 8장 4번). 이 검토가 승인으로 바뀌어도 그 확인이 머지 조건으로 남는다.
- R3 운영: 공시 끝날이 T−1로 바뀌어, T 00:00 전에 돌린 수집으로는 T 선정이 늘 `STALE_INPUT`이다(runbook에 적힘). 장전 예약 작업을 만들 때 krx-daily → dart-financials → dart-disclosures를 같은 아침 창에 넣는 일정 시험을 첫 실운영에서 확인한다.
- R4 UI `metricOptions` 필터는 Lab screens 시험이 있으면 한 건 더한다(앞선 R3 후속).

## 판정
수정 필요(F4 2000년 이전 접수일에서 스냅샷 빌드 예외 — `--from` 하한 또는 연도 기준 수정과 시험, F5 상장폐지 집계가 이름·소속부 변경 이력 행을 셈 — 코드 마지막 행 기준과 시험, F6 ADR-012 3.2 `disclosure-count` 정의 칸 바뀜 표시)
