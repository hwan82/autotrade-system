# architect 재검토 2: 공시 스냅샷 수집 dart-disclosures·지표 disclosure-count·선정 프로필 (라)(ADR-012 배치표 3번, D-123)
- 대상: PR #196 / 브랜치 `claude/project-thread-s7vbig` / 헤드 커밋 5e61356 / `git diff origin/main...HEAD` 전체(58파일 +2,995 −79, 병합 기준 e9a7ee2). 그 사이 origin/main은 db58c00(#195)로 나아갔다.
- 앞선 원문: [architect.md](architect.md)(a22663c, F1~F3), [architect-2.md](architect-2.md)(a0d6841, F4~F6). 이번에는 a0d6841 뒤 커밋 cbcedbc(strategy-researcher N1·n1 대응)·5e61356(F4·F6 대응)을 줄 단위로 읽고, PR 전체 diff를 다시 대조했다.
- 점검표: B
- 직접 실행한 것:
  - `git diff a0d6841 5e61356`(코드·시험·문서 7파일) 전체 읽음. 커밋 메시지·PR 본문의 설명은 근거로 쓰지 않았다.
  - 깨끗한 detached worktree(5e61356)에서 `./gradlew build --rerun-tasks --no-daemon`: **BUILD SUCCESSFUL in 4m 20s**(138 tasks 모두 실행), 시험 XML 합계 1,723건 실패·오류·건너뜀 0(순수 모듈 금지 의존 검사·ArchUnit 포함). 새 시험 2건이 결과에 들어 있음(`DartDisclosureSnapshotBuilderTest`).
  - 병합 시험: 별도 worktree에서 5e61356에 origin/main(db58c00)을 병합 → `docs/decisions/README.md`·`docs/strategy/selection-catalog.md`·`selections/README.md` 세 곳이 **표 행 추가끼리 충돌**(D-122 행 대 D-123 행, (감시 후보) 행 대 (라) 행, `kr-watchlist-turnover` 행 대 `kr-intraday-news` 행). 양쪽 행을 모두 남기는 해결로 `./gradlew build --no-daemon`: **BUILD SUCCESSFUL in 4m 2s**, 시험 1,732건 실패·오류·건너뜀 0. 이 병합은 검토용이며 커밋·push하지 않았다. GitHub API로 본 PR 상태: open, `mergeable: false`, `mergeable_state: dirty`.
  - 보호 경로 대조: 바뀐 58파일을 `scripts/ci/protected-paths.txt` 패턴(bash glob)과 대조 → 해당 0건.
  - 비밀값: diff에서 `crtfc_key=` 값·키 형태 문자열 검색 0건.
  - `@Disabled`·`@Ignore` 추가: 0건(검토 원문 인용 줄 제외).

## 앞선 지적 처리 확인

| 지적 | 결과 | 근거 |
|---|---|---|
| F4 2000년 이전 접수일 빌드 예외 | 해결 | `BitSet`·`- 2000` 기준을 없애고 종목 코드 → 접수일 `TreeSet<LocalDate>`로 바꿈(`DartDisclosureSnapshotBuilder.java:98,125`). 수집기 안에 다른 `- 2000` 기준 0건(grep). 연도별 표는 `TreeMap<Integer,…>`라 음수 첨자 없음(`:119`). 시험 `DartDisclosureSnapshotBuilderTest#이천년_이전_접수일도_빌드가_멈추지_않는다`(1999-07-05 행 → 행 1·연도 1999·폐지 1·붙음 1) 통과 |
| F5 상장폐지 집계가 이름·소속부 변경 이력 행을 셈 | 해결 | 종목 코드 단위로 묶어 열린 구간이 하나라도 있으면 상장 유지, 모두 닫혔으면 마지막 끝날로 폐지(`:149-158`). 상장 기간 대조는 날짜 단위 `ceiling(from)` ≤ `to`(`:173-175`)라 권고 R1(같은 해 폐지 뒤 공시)도 함께 고쳐짐. 시장 칸을 묶지 않아 코스닥→코스피 이전상장도 폐지로 세지 않음 `[추론]`(`CollectorMain.listings`는 KRX 행 전체를 넘김). 기준선(상장 유지 종목 비율) 추가(`CollectorReports.java:161-164`). 시험 `#상장폐지는_종목_코드_단위로_보고_상장_기간은_날짜로_대조한다`(이름 변경 666666 → 유지, 폐지 뒤 공시 777777 → 붙지 않음), 기존 첫 시험 기대값(폐지 2·붙음 1) 유지 + 기준선 단언 추가 |
| F6 ADR-012 3.2 `disclosure-count` 정의 칸 | 해결 | ADR-012 47행이 [w거래일 첫날, T−1](달력일)과 "D-123으로 바뀜" 표시, 실패 조건도 "coverage가 T−1 미만"으로 맞춤. D-123 6번·카탈로그·runbook과 일치. 결정표 ③(22행 "접수일 ≤ c")의 바뀜 표시는 앞선 원문에서도 권고였고 아직 없다(아래 R1) |

## 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 깨끗한 worktree 5e61356 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 1,723건 실패·오류·건너뜀 0. main(db58c00) 병합 시험 상태도 BUILD SUCCESSFUL, 1,732건 0 |
| B2 | 통과 | cbcedbc·5e61356 변경은 `autotrade-app-collector` 안쪽(`DartDisclosureSnapshotBuilder`·`CollectorReports`·시험)과 문서뿐, 새 의존 없음. PR 전체로도 순수 모듈(`autotrade-selection`·`autotrade-sim`)에 Spring·DB 의존 없음, ArchUnit·`ModuleOwnershipTest` 통과 |
| B3 | 통과 | 앞선 F2·F6 계약 문서가 모두 맞춰졌다(ADR-007 2.6 `inputs.disclosures`, ADR-008 호출 상한, ADR-012 3.1·3.2·3.4, D-123, config-keys, 카탈로그, runbook). 이번 변경의 `Stats` 칸 추가(`listed`·`listedWithDisclosure`)는 수집기 패키지 안 record와 품질 리포트(사람이 읽는 md)뿐이라 모듈 간 계약이 아니다. 품질 리포트 서술 변경은 D-123 위험란·runbook 4장 2·4번에 반영 |
| B4 | 통과 | 선정 계산·결과 해시 경로는 a0d6841 뒤 바뀌지 않음(앞선 원문 B4 근거 유효). 이번 변경은 품질 리포트 집계만: 순회는 `TreeMap spans`(코드 순), 접수일 `TreeSet`이라 결과가 순서에 따르지 않음. 스냅샷 행 출력·정렬·중복 거부(`:132-143`)는 무변경. 현재 시각 사용 없음 |
| B5 | 통과 | 금액 계산 없음. `pct`의 `double`은 리포트 표시용(`CollectorReports.java:188-190`), 0 나눗셈은 "-"로 막음 |
| B6 | 통과 | F4·F5 각각 실패하던 경우를 재현하는 새 시험 2건(위 표). 기존 시험은 단언 추가뿐, 기대값 변경·삭제·`@Disabled` 0건. UI `metricOptions` 필터는 여전히 시험 없음(권고 R4, 앞선 원문과 같음) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동·보호 경로 변경 없음(대조 0건). 수집기는 OpenDART만 부름. 선정 결과 `inputs.disclosures`를 7단계 `SelectionValidity`가 쓸지는 ADR-007에 risk-ops 검토 대상으로 적혀 있음 |
| B8 | 통과 | 이번 변경은 키를 다루지 않음. 새 예외·로그 문자열 없음. diff 비밀값 검색 0건 |

## 점검표 밖 발견
새 수정 필요 항목 없음.

### 머지 조건(판정과 별개로 남김)
- M1 **main 반영**: PR이 지금 `mergeable_state: dirty`다. 충돌 세 곳은 모두 표 행 추가끼리이고, 양쪽 행을 남긴 병합 상태의 전체 빌드가 통과함을 확인했다(위 병합 시험). 이 승인은 5e61356 + "그 세 표에 양쪽 행을 모두 남기는 해결"까지 덮는다. 해결이 그 밖의 줄을 바꾸거나 ADR-012 자동 병합 결과를 손대면 다시 검토를 맡긴다. 결정 로그 표는 D-122 행 뒤에 D-123 행을 둔다.
- M2 D-123 상태 줄대로 D-121 ③ 대체(지침 8장 4번)에 대한 **사용자 확인**이 머지 조건으로 남는다. 이 승인은 그 확인을 대신하지 않는다.

### 권고(판정에 넣지 않음)
- R1 ADR-012 결정표 ③(22행 "접수일 ≤ c인 공시만 쓴다")에도 "(D-123으로 바뀜: 접수일 ≤ T−1)" 표시를 단다. 47행 정의 칸과 한 ADR 안에서 다르게 읽힌다.
- R2 기준선 비율은 노출 기간이 다르다: 상장 유지 종목은 [수집 첫날 또는 상장일, 끝 없음], 폐지 종목은 [같은 시작, 폐지일]이라 폐지 종목은 구조적으로 공시가 붙을 기간이 짧다 `[추론]`. 또 `first`는 가장 이른 창 시작일이라 유형별 수집 구간이 다르면 넓은 쪽 기준이다. 두 비율 차이를 생존편향으로 읽기 전에, 폐지 종목은 "폐지 전 1년 안 공시", 유지 종목은 "같은 길이 임의 구간"처럼 맞춘 비교를 strategy-researcher가 정하면 판단이 단단해진다. runbook 4번 손 확인이 있어 관문은 유지된다.
- R3 메모리: 종목 코드별 접수일 `TreeSet<LocalDate>`는 전 기간 공시일을 모두 담는다. 2010년~ B·I 전체면 코드당 수백~수천 날짜 × 수천 코드로 수백만 항목(항목당 수십 바이트) `[추론]`이며 `-Xmx2g`(`autotrade-app-collector/build.gradle.kts:13`) 안이라 지금은 문제 없다고 본다. 클래스 주석의 "전 기간을 메모리에 올리지 않음"과 성격이 다르므로, 첫 전체 수집 때 힙을 보고 부족하면 코드별 `long[]` 정렬 배열(epochDay)로 바꾼다.
- R4 UI `selections.js` `metricOptions` 필터는 Lab screens 시험이 있으면 한 건 더한다(앞선 원문 R4와 같음).

## 판정
승인
