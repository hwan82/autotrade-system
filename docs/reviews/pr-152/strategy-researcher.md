# strategy-researcher 검토: PR #152 주간 점검 머리 줄 2차 후속(PR #147 선택 권고 P2·architect 2·3)

- 대상: PR #152 / 브랜치 `claude/project-thread-h282rp` 머리 `2cf16ab`(커밋 1개) / main `1bb2f3e` 대비 diff(파일 4개, +50 −5. 코드 1개, 시험 1개, 문서 2개)
- 점검표: 해당 없음(A·B·C·D는 risk-ops·architect·QA용). 지침 5장(결정론), 9장 교차 검토 "실험·통계는 strategy-researcher", role-guides.md 5·8장, 그리고 이 PR의 출처인 [PR #147 strategy-researcher 검토](../pr-147/strategy-researcher.md) P1·P2를 기준으로 본다. 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 경로 변경은 없다
- 직접 실행한 것:
  - `git diff origin/main...origin/claude/project-thread-h282rp` 전체 읽기. `CollectionFreshness.Status.summary()`, `CollectionFreshnessTest` 추가·보강, D-105 "2차 후속" 절, runbook 6단계 문단
  - `summary()` 호출처 확인(`grep`): `CollectionFreshness.render`(4장 판정 줄), `LiveWeeklyCheck.java:233`(콘솔 `수집:`), `LiveWeeklyCheck.java:532`(머리 `- 수집:`), `LiveWeeklyMain.java:84`(`수집 확인 필요` 콘솔 줄). 바뀐 것은 문자열뿐이고 `warn()`·`kind`·종료 코드 조건은 그대로다
  - `./gradlew :autotrade-sim:test --tests '*CollectionFreshnessTest' --tests '*LiveWeeklyCheckTest'` → 성공. 결과 XML: `CollectionFreshnessTest` 12건, `LiveWeeklyCheckTest` 8건, 실패·오류·건너뜀 0. 전체 `./gradlew build`는 돌리지 않았다
  - 실데이터(사용자 PC 스냅샷 폴더)로는 돌리지 않았다

| # | 결과 | 근거 |
|---|---|---|
| P2 반영(옛 형식만 있을 때 머리 줄) | 통과 | `NO_SNAPSHOT`이고 `noCoverage`가 비어 있지 않으면 "셀 수 있는 일봉 스냅샷이 없음(마지막 거래일 칸 없는 옛 형식 N개, 아래 4장)", 비어 있으면 기존 문구. 두 갈래 모두 시험이 정확한 문자열로 고정한다(`oldManifestWithoutCoverageIsListedApartFromBrokenOnes`, `noSnapshotSummaryWithoutOldFormatStaysShort`). `warn()`은 참 그대로라 경고가 약해지지 않는다 |
| architect 3 반영(지난 주 재점검 머리 줄) | 통과 | `OK`이고 `newerThanEnd`면 "점검 마지막 날보다 뒤라 그 주의 멈춤은 보이지 않음"이 붙는다. `newerThanEnd`이면 `behind`가 반드시 비므로 "아직 수집 전일 수 있음" 문구와 함께 나오는 경우는 없다. 같은 날이면 붙지 않음까지 시험이 고정한다. 머리만 보는 사람이 지난 주 "정상"을 그 주의 사실로 오해할 위험이 줄었다 |
| architect 2 반영(`lastTradingDate`만 없는 manifest) | 통과 | 기존 분기(`coverage == null || lastTradingDate == null`)가 이미 옛 형식으로 보냈고, 이번 시험 `coverageWithoutLastTradingDateIsOldFormatToo`가 그것을 고정한다. "읽지 못함"으로 새지 않음(`unreadable` 비어 있음)도 확인한다 |
| 결정론·재현성 | 통과 | 벽시계·순서 의존이 새로 생기지 않았다. 문자열은 `Status` 필드만의 함수라 같은 폴더·같은 `end`면 같은 보고서다. 1~3장 대조와 종료 코드에 영향 없음 |
| 기록 | 통과 | D-105에 새 번호 없이 "2차 후속" 절로 남겼고 결정 내용(판정 기준)은 바뀌지 않는다. 시험 수(12)가 실제 실행 결과와 맞다. 남은 것(architect 1, 이 역할의 P1, PR #145 R1)을 버리지 않고 적었다 |

## 권고(머지를 막지 않음)

- Q1. 4장에서 지난 주 재점검이면 같은 뜻이 두 번 나온다("- 판정:" 줄의 새 문구와 아래 "스냅샷이 점검 마지막 날보다 뒤…" 줄). 해석에 해는 없고 머리 줄과 4장의 판정 줄을 같은 함수로 맞춘 결과라 그대로 둬도 된다. 거슬리면 4장 판정 줄만 짧은 문구를 쓰는 방법이 있다
- Q2. `NO_SNAPSHOT`에서 옛 형식과 깨진 폴더(`unreadable`)가 함께 있으면 머리 줄은 옛 형식 수만 말한다. 4장에 둘 다 나오고 판정(확인 필요)도 같아 실해는 작다. 필요하면 "읽지 못한 폴더 M개"를 덧붙일 수 있다
- 이 역할의 P1(`createdAt` 기준 당시 스냅샷 재구성)과 PR #145 R1(KRX 게시 시각 T+1 `[가정]`이면 매주 거짓 멈춤 의심)은 여전히 열려 있다. R1은 첫 실데이터 주간 점검에서 스냅샷 `lastTradingDate`와 실행 시각을 대조해 닫기를 다시 권한다. 매주 뜨는 경고가 무시되는 위험은 이 PR로 바뀌지 않는다

## 한계

- 실제 스냅샷 폴더로는 확인하지 않았다. 옛 형식·반쪽 manifest 처리는 합성 manifest 시험으로만 확인했다.
- 전체 빌드(ArchUnit 포함)는 돌리지 않았다. 바뀐 운영 코드는 기존 클래스의 문자열 조립 한 곳이고 의존 추가가 없어 영향은 낮다고 본다 `[추론]`.

## 판정

승인. PR #147에서 이 역할이 낸 P2와 architect 2·3이 판정 논리·`warn()`·종료 코드를 바꾸지 않고 머리·콘솔 한 줄과 시험으로 반영됐다. 옛 형식만 있는 경우와 지난 주 재점검 모두 머리 줄만으로 오해 없이 읽힌다. 결정론과 1~3장 대조에는 영향이 없다. 남은 것은 선택 권고 Q1·Q2와 기존 미해결 P1·R1뿐이다.

판정: 승인
