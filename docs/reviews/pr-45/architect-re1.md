# architect 재검토 1: 선정 계산과 조건 카탈로그(배치표 2번, D-057)
- 대상: PR #45 / 브랜치 `claude/project-thread-5jfdgg` / head `d20d289` / main(`6209c77`) 대비 `git diff origin/main...HEAD` 전체(38개 파일, +3270 −16). merge-base = `origin/main`. 1차 원문 `architect.md`(커밋 `4468be1`)는 이번 수정 커밋 `d20d289`에서 바뀌지 않았다(`git show --stat d20d289`에 reviews 파일 없음).
- 1차(`42e74ae`) 대비 바뀐 것: `SelectionCalculator`, `SelectionData`, `SnapshotSelectionData`, `SelectionResult`(주석), `CalculatedSelectionSource`, 테스트 4개(새 `SnapshotSelectionDataTest` 포함), D-057, 선정 카탈로그(12개 파일, +435 −35).
- 점검표: B
- 직접 실행한 것:
  - `d20d289`을 별도 worktree에 고정하고 `./gradlew build --no-daemon --rerun-tasks`를 돌렸다. **BUILD SUCCESSFUL in 1m 59s, 117개 task 모두 실행**. 테스트 708개, 실패·오류·건너뜀 0(autotrade-selection 26 → 32, autotrade-sim 92). 실행 뒤 worktree는 지웠다.
  - F2 다시 재현: 고친 계산(일별 수정 거래량과 합은 DECIMAL128, 마지막 비율만 DECIMAL64)을 그대로 옮긴 스크립트로 1차 탐침의 계수 넷 × 거래량 다섯 × 창 셋을 다시 돌렸다. **0/60건 탈락**(1차는 21건).
  - 같은 종류의 오차가 다른 지표에도 있는지 같은 방식으로 확인했다(코드의 반올림 위치를 그대로 옮김, F2b).
    - `op-margin-growth`(영업이익률 성장률, 정확히 1.5배인 쌍): **147/3781건이 `0.499999999999999`** 로 GE 0.5에서 탈락했다. 예: 영업이익 7·매출 45 → 21·90.
    - `return`(같은 수정 계수로 종가가 정확히 3% 오름): **4/725건이 `0.029999999999999`** 다. 예: 계수 0.9876543210123, 종가 10611 → 10929.33. 이렇게 되면 LT 0.03을 통과한다.
  - 실전 경로 diff 확인: 1차와 같다. risk, app-engine, broker-*, domain, config, control, 보호 테스트는 변경 0건이다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 격리 빌드 성공, 708개 통과 |
| B2 | 통과 | 의존 변경 없음(1차와 같음). `SnapshotSelectionData`는 `ZoneId`만 더했다(`calendar.zone()`). 순수 모듈 검사와 ArchUnit 통과 |
| B3 | 통과 | F1이 해소됐다(아래). `inputs.bars.last_trading_date`·`shares.as_of_date`가 manifest `coverage` 값을 옮긴다(`SelectionCalculator.java:129-131`, `SnapshotSelectionData` 생성자). 이는 `SnapshotManifest.java:17-18` 계약 및 ADR-007 2.6 `STALE_INPUT`의 입력과 맞는다. 결과의 재무 칸 모양은 D-057 6번에 적혔다. ADR-007 2.6 예시 JSON은 아직 그대로다(권고 유지) |
| B4 | 통과 | 시스템 시계 0건. 재무 수집 시각은 manifest의 `Instant` 문자열을 거래소 시간대로 바꿔 쓴다(`SnapshotSelectionData#financials`). 결과 캐시(`CalculatedSelectionSource.java:36-42`)는 같은 (선정 해시, 기준일)이면 같은 결과라서 결정론에 영향이 없다. `CalculatedSelectionSourceTest`가 계산 횟수 3번을 확인하고 기존 골든도 통과한다 |
| B5 | 통과 | `BigDecimal`만 쓴다. 거래량 비율과 거래대금 합의 중간 계산은 DECIMAL128이다(`SelectionCalculator.java` `WIDE`). 남은 경계 오차는 F2b(권고) |
| B6 | 통과 | 새 `SnapshotSelectionDataTest`가 임시 폴더에 manifest·CSV를 써서 실데이터 경로를 검증한다. 검증 내용은 다음과 같다. ID·파일 SHA·수록 기준일·재무 수집 시각이 `inputs`로 옮겨지는지(`#결과_입력_칸이_manifest의_ID_해시_수록_기준일을_옮긴다`), 수록 범위가 모자랄 때 실패하는지, 현지 날짜로 바꾸는지(UTC 14:00 → KST 23:00 → 10/1 탈락, UTC 16:00 → 10/2 통과), `coverage`가 없을 때 거부하는지. `SelectionCalculatorTest`에는 입력 부재·기준일 부족의 실패 테스트(경계: 같은 날은 통과)와 수정 계수 14종의 경계 테스트가 더해졌다. 삭제된 `#재무_스냅샷이_없으면_재무_조건은_결측으로_탈락한다`는 같은 상황이 이제 실패하도록 동작이 바뀐 것이다. 새 기대값 테스트 `#조건이_쓰는_입력이_없으면_계산하지_않는다`가 대신하므로 약화가 아니다 |
| B7 | 해당 없음 | 실전 경로 변경 없음. 실전 엔진은 selection 모듈에 의존하지 않는다 |
| B8 | 통과 | 바뀐 줄에 비밀값 없음. 테스트 manifest URL은 `example.invalid` |

### 지난 지적 처리
- **F1: 해소.**
  - `checkInputs`(`SelectionCalculator.java:148-172`)는 다음 경우 `IllegalStateException`으로 계산을 멈춘다.
    - 일봉 `coverage.lastTradingDate`가 기준 데이터일보다 이를 때
    - 시가총액 조건이 있는데 상장주식수 입력이 없거나 `sharesAsOf`가 기준 데이터일보다 이를 때
    - 재무 조건이 있는데 재무 입력이 없거나, 수집 시각을 모르거나, 수집 시각의 현지 날짜가 기준 데이터일보다 이를 때
  - manifest에 `coverage`가 없는 옛 스냅샷은 `SnapshotSelectionData` 생성에서 거부된다.
  - 실험실에서는 이 예외가 `SelectionSchedule`에서 그날 `PRODUCER_FAILED`가 된다(`SelectionSchedule.java` `schedule`의 catch). 그래서 1차에서 본 "조용히 0종목"이 이제 그날 판정 사유로 보인다.
  - 1차 탐침 시나리오(일봉 3/13 끝, T=4/20)는 이제 `#입력_기준일이_기준_데이터일보다_이르면_계산하지_않는다`와 같은 경로에서 실패한다.
  - 고른 방식은 1차의 a·b를 합친 것이다(실패 확인 + manifest 값 기록). 백테스트에서는 `last_trading_date`가 기준 데이터일보다 늦게 적히지만, `Inputs` 주석이 그렇게 고쳐졌고 ADR-007 2.6 문구("일봉 마지막 거래일")와도 맞으므로 받아들인다.
- **F2: 거래량 비율은 해소**(재현 0/60, 테스트 `#거래량_비율_경계는_수정_계수가_1이_아니어도_정확하다`). 같은 종류의 오차가 다른 지표에 남아 있어 F2b로 따로 적는다.
- **F3: 문서화로 반영.** D-057 "위험과 한계"에 실전 엔진 PR에서 risk-ops가 검토할 항목으로 적혔다. 코드 변경은 필요 없다(실험실 범위).
- **F4: 해소.** `CalculatedSelectionSource`가 결과를 캐시하고 테스트가 계산 3번을 확인한다.
- **F5: 일부 반영.** D-057 6번에 재무 칸 모양을 적었고, `MALFORMED`·ArchUnit 규칙의 후속 위치를 D-057에 적었다. ADR-007 2.6 예시 JSON 갱신은 남았다(권고, 코덱 PR 전).

## 점검표 밖 발견

### F2b (권고) 영업이익률 성장률과 수익률에 경계 반올림 오차가 남았다
1차 원문 F2에서 "`return`·`avg-turnover`는 단일 나눗셈이라 해당 없음"이라고 쓴 것은 **`return`에 대해서는 틀렸다**. 이번에 바로잡는다.
- `op-margin-growth`: `margin()`이 두 해 영업이익률을 각각 DECIMAL64로 반올림한 뒤(`SelectionCalculator.java:375`) 그 비를 다시 반올림한다(`:434`). 정확히 1.5배인 쌍의 3.9%(147/3781)가 `0.499999999999999`가 되어, (가) 프로필 gate.01(GE 0.5)의 "경계 포함" 규칙이 깨진다.
- `return`: `DailyBar.adjustedClose()`가 종가 × 계수를 DECIMAL64로 반올림한다(`DailyBar.java:44-45`). 그래서 같은 계수가 곱해진 두 종가의 비에도 오차가 생긴다. 정확히 3%가 `0.029999999999999`가 되면, 카탈로그와 골든 테스트가 "3% 정확히는 탈락"이라 한 (나) gate.02(LT 0.03)를 통과한다(4/725).
- 실제 재무·가격 데이터에서 정확한 경계값이 나올 확률은 낮다. 결정론도 깨지지 않는다. 다만 문서화된 경계 규칙이라 고치길 권한다. 고칠 때는 거래량 비율과 같은 방식으로 한다.
  - 성장률: `(oi1 × rev0) ÷ (rev1 × oi0)`처럼 한 번만 나누거나, 중간 계산을 DECIMAL128로 한다.
  - 수익률: 같은 계수면 원가격 비를 쓰거나, `close × adj`를 DECIMAL128로 계산한다.
  - 계수 0.9876543210123과 영업이익 7/45 같은 경계 테스트를 더한다.
- 이번 PR에서 고치지 않고 후속으로 넘긴다면 카탈로그 3장 "값 표기"에 이 한계를 적어 둘 것.

### 남은 권고(차단 아님)
- ADR-007 2.6 예시 JSON에 `financials` 칸 모양(`snapshot_id`, `sha256`, `last_collected_at`)을 반영한다(코덱 PR 전).
- 재무 입력 확인 기준(수집 현지일 ≥ 기준 데이터일)은 ADR-007 2.6 엔진 판정(지연 상한 기본 5거래일)보다 엄격하다. 생산 쪽이 더 닫힌 방향이라 안전하다. 다만 재무 수집 주기가 매일이 아니면 재무 선정 생산이 자주 실패할 수 있다(`[추론]`). 생산 일괄 명령 PR에서 data-engineer·risk-ops가 수집 주기와 맞춰 볼 것.
- F3 실전 이관 조건과 `ENGINE_DOES_NOT_COMPUTE_SELECTIONS`는 D-057에 적힌 대로 해당 PR에서 처리한다.

## 판정 (재검토 1)
승인
