# PR #241 검토: strategy-researcher

- PR: #241 "DART 재무 스냅샷을 공시마다 계정 응답을 읽어 만들기(전체 수집 OutOfMemoryError)"
- 브랜치: `claude/project-thread-pb3vv4`, 머리 `d4a471d`
- 범위: `git diff origin/main...claude/project-thread-pb3vv4` 전체(파일 3개, +96 −35)
  - `autotrade-app-collector/.../CollectorMain.java`
  - `autotrade-app-collector/.../DartSnapshotBuilder.java`
  - `autotrade-app-collector/.../DartFinancialCollectorTest.java`
  - 브랜치 이력의 PR #137 커밋(`b019340`~`f373b96`)은 이미 squash 머지되어 main 대비 diff에 나타나지 않는다(`d4a471d` 머지 커밋)
- 검토 관점(strategy-researcher): 실험 입력인 재무 스냅샷의 결정론·재현성, 미래 정보 차단(접수일 기준), 정정 공시 처리(MD-10), 품질 리포트 건수의 정확성

## 직접 실행한 것

- diff 전체와 `DartFinancialCollector.filings()`·`accountsOf()`(`DartFinancialCollector.java:188-207`), `CollectorMain.dartFinancials` 전체를 읽었다.
- `./gradlew :autotrade-app-collector:test --offline` (머리 `d4a471d`) → 종료 코드 0. 결과 XML 합계: 154건, 실패·오류·건너뜀 0. `DartFinancialCollectorTest` 11건에 새 시험 2건 포함.
- 전체 `./gradlew build`와 실제 10만 건 캐시로 실행은 하지 않았다.

## 확인 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 계산 로직 불변 | 확인 | [사실] 기존 반복문 몸체가 `Builder.add(f, accounts)`로, 정렬·묶음 집계가 `Builder.result()`로 그대로 옮겨졌다. 바뀐 것은 `in.filing()`→`f`, `in.accounts()`→`accounts`, `continue`→`return` 뿐이다. 계정 선택, CFS/OFS 판정, 정정 대체 규칙(MD-10) 집계 코드에 변경 없음 |
| 결정론·재현성 | 유지 | [사실] 입력 순서는 전과 같다: `filings()`가 `rcept_dt`→`rcept_no`로 정렬한 목록(`DartFinancialCollector.java:199`)을 같은 순서로 읽는다. 결과는 `SnapshotFinancials.ORDER`로 다시 정렬되므로 행 순서는 읽는 순서와도 무관하다. [추론] 같은 캐시면 같은 행 → 같은 스냅샷 내용·해시가 나온다. 재무 스냅샷은 지우면 재현할 수 없다는 기존 전제(QA PR #39 F2)를 바꾸지 않는다 |
| 미래 정보 차단 | 영향 없음 | [사실] 접수일(`receiptDate`)·`valuesFor` 일치 검사(`known`)·대기(`accounts == null` → `pending`) 처리가 그대로다. 스냅샷 쓰기와 `SnapshotFinancials.load` 읽는 쪽 검사도 바뀌지 않았다 |
| 품질 리포트 건수 | 동일 | [사실] `Stats`의 pending·separate·missing·equity·fallback·noValue·firstReceipt를 같은 Builder가 센다. 동등성 시험이 `streamed.stats()`와 `all.stats()`가 같음을 고정한다 |
| 동등성 시험 | 적절 | `계정_응답을_공시마다_읽어_만들어도_한꺼번에_읽은_결과와_같다`: 정정·대기 반영이 섞인 캐시(두 번 수집)에서 행과 Stats가 같고, 공시마다 한 번씩 공시 순서대로 읽음을 고정한다 |
| 실패 처리 | 적절 | [사실] 읽기 실패 시 `IOException`이 `build`에서 그대로 올라가 스냅샷을 쓰기 전에 멈춘다(전에는 `CollectorMain` 반복문에서 같은 예외로 멈췄다). 부분 스냅샷이 생기지 않는다. `계정_응답을_읽다_실패하면_...` 시험이 고정한다 |
| 시험 삭제·약화 | 없음 | 기존 `build(List<Input>)` 진입점과 기존 시험(108, 207행)이 그대로 돈다 |

## 권고(차단 아님)

- R1. 메모리 주장(10만 건 캐시를 `-Xmx2g`로 만든다, `DartSnapshotBuilder` Javadoc)은 [추론]이다. 시험은 작은 고정 데이터만 쓴다. 사용자 PC에서 `dart-financials` 스냅샷 단계를 다시 돌려 (a) OOM 없이 끝나는지, (b) 품질 리포트의 공시 수가 수집 줄의 공시 수(PR 본문 기준 104,609건)와 맞는지, (c) 대기·정정 대체 건수가 상식 범위인지 한 번 확인하고 결과를 남길 것을 권한다. 이 스냅샷은 4단계 이후 재무 조건 전략 실험의 입력이므로 데이터 버전(스냅샷 ID)을 실험 리포트에 적을 수 있게 된다.
- R2. [추론] 메모리에 남는 것은 공시 목록 JsonNode 10만 개와 결과 행이다. 지금 규모에서는 충분하지만, 공시 목록도 커지면 같은 문제가 반복될 수 있다. 후속으로 필요하면 data-engineer 판단에 맡긴다.
- R3. 범위 밖: `fetchAccounts`가 캐시 존재 확인에 `accountsOf`(파일 전체 파싱)를 쓴다(`DartFinancialCollector.java:215`). 메모리 문제는 아니지만 매 실행 10만 개 파일을 파싱한다. `Files.exists`로 충분한지 data-engineer 검토 제안.

## 한계

- 실 OpenDART 캐시와 실제 힙 사용량으로는 검증하지 않았다.
- 이 PR은 전략 프로필·실험 결과를 바꾸지 않으므로 성과 비교 관점의 검토 대상은 없다.

## 판정

승인. 계산 로직을 바꾸지 않은 구조 변경이며, 결과 행·순서·품질 건수가 기존 방식과 같음을 시험이 고정한다. 실험 입력의 결정론과 미래 정보 차단에 영향이 없다. R1 실측 확인을 권한다.

판정: 승인
