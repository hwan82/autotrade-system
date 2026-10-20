# architect 재검토: 단타 선정 지표 day-range·close-strength·volume-spike와 예시 (다) kr-intraday-hot(ADR-012 배치표 2번)
- 대상: PR #193 / 커밋 de4283d(코드 head 9fedb6d, 그 뒤 커밋은 strategy-researcher-2.md 검토 원문 1개) / main 대비 diff 전체(`git diff origin/main...HEAD`). 1차 검토(architect.md, 4d93d1c) 뒤 바뀐 것은 `SelectionCalculator`(volume-spike 거래량 0 결측, closeStrength javadoc), `IntradaySelectionCalculatorTest`(700060·700070), selection-catalog, `kr-intraday-hot/v1.yaml` 주석
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --init-script <저장소 밖 Maven Central 미러 init 스크립트> build` → BUILD SUCCESSFUL(138 tasks)
  - `:autotrade-selection:test --rerun :autotrade-architecture-test:test --rerun` → BUILD SUCCESSFUL. test-results XML: autotrade-selection 40건, autotrade-architecture-test 46건, 둘 다 실패 0·오류 0·건너뜀 0
  - 근거 대조: `DailyBarQualityCheck.java:205-208`(OHLC_INCONSISTENT: 고가 < max(시가, 종가, 저가) 또는 저가 > min(시가, 종가)), `QualityReport.java:38`(ERROR), `SnapshotDailyBarSource.java:66-76`(ERROR 행 제외), `DailyBarQualityCheck.java:219-221`·`QualityReport.java:43`(거래량 ≤ 0은 ZERO_VOLUME **WARNING**, 행 유지), `SnapshotDailyBarSource.java:99`(`Long.parseLong`, 음수 허용), `DailyBar` 생성자(거래량 범위 검증 없음), ADR-008 47행(거래정지일은 시·고·저를 종가로 채우고 거래량 0), `KrxMarketRules.java:47-48,129`(±30%, 2015-06-15)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행 |
| B2 | 통과 | 바뀐 운영 코드는 여전히 `autotrade-selection`(순수 Java)과 UI 정적 JS뿐. 새 의존·보호 경로 변경 없음(`scripts/ci/protected-paths.txt` 대조). ArchitectureTest·ModuleOwnershipTest 재실행 통과 |
| B3 | **실패(F2)** | volume-spike의 결측 규칙이 "앞 구간 합 0"에서 "w+1일 중 거래량 0인 날"로 바뀌었고 카탈로그(`selection-catalog.md` 3장 표)는 갱신됐지만, 지표 계약을 정한 ADR-012 3.2 표(`docs/adr/ADR-012-*.md:46`)는 아직 "앞 구간 합 0 → `NO_DATA`"다. 같은 ADR 60행도 ±30%를 `[가정]`으로 두어 이번 커밋의 카탈로그·프로필 주석(`[사실]`, `KrxMarketRules`)과 다르다. 아직 머지 전인 새 지표라 호환성 문제는 없지만, 계약 원문과 구현이 어긋난 채 머지되면 배치표 5번(실전용 선정 생산자)과 2b 실험 정의가 어느 쪽을 기준으로 삼을지 갈린다 |
| B4 | 통과 | 새 코드에 Clock·난수 없음. 결측 판정은 이미 읽던 w+1개 일봉 안에서만 하므로 창이 넓어지지 않는다(`SelectionCriteria.java:93` 그대로). `#다_선정은_T_당일_이후_일봉을_쓰지_않는다`와 기존 골든 통과 |
| B5 | 통과 | 계산은 그대로 `BigDecimal`/`DECIMAL128` 중간, `DECIMAL64` 값 |
| B6 | 통과 | 700060(앞 구간 하루 거래량 0 → 평균에 넣으면 7.5배로 통과할 종목이 `GATE_01_NO_DATA`), 700070(c 거래량 0) 추가. 기존 단언 삭제·약화 없음(700030 앞 구간 합 0도 같은 사유로 계속 결측). 건너뜀 0 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·리스크 한도·장애 대응 변경 없음. 실전용 선정 생산자 연결은 ADR-012 배치표 5번 |
| B8 | 통과 | diff에 비밀값 없음, 새 로그 없음 |

## 점검표 밖 발견
- **F1(수정 필요): 지운 분모 가드가 음수 거래량에서 계산 전체를 깬다.** `SelectionCalculator.volumeSpike`는 `b.volume() == 0`만 결측으로 보고 `prior.signum() == 0` 검사를 지웠다. 그런데 음수 거래량 행은 적재 단계에서 빠지지 않는다(ZERO_VOLUME은 `volume <= 0`에 걸리는 WARNING이고, `Long.parseLong`·`DailyBar`가 음수를 막지 않음). 앞 구간에 음수가 섞여 합이 정확히 0이면 `divide(prior, WIDE)`가 `ArithmeticException`(Division by zero)를 던지고, 계산기가 종목 단위로 잡지 않아 그 선정 전체가 실패한다. 합이 음수면 음수 배율이 그대로 나온다. 1차 코드는 합 0을 `NO_DATA`로 막고 있었으므로 이번 커밋이 만든 퇴행이다. KRX 원천에서 음수 거래량이 나올 가능성은 낮다 `[추론]`. 하지만 품질 검사의 ZERO_VOLUME 조건과 같은 `b.volume() <= 0`으로 바꾸면 한 글자로 닫힌다. 음수 거래량 일봉이 `GATE_<nn>_NO_DATA`가 되는 단언을 테스트에 하나 더하고, 카탈로그 표의 "거래량 0인 날"을 "거래량 0 이하인 날"로 맞춘다.
- **F2(수정 필요, 문서): ADR-012 동기화.** 위 B3. 3.2 표 volume-spike 행의 결측 칸을 카탈로그와 같게(F1 반영 시 "거래량 0 이하") 고치고 "PR #193 검토 반영"을 상태 줄이나 행에 적는다. 60행의 ±30% 표기도 `[사실]`(`KrxMarketRules`, 2015-06-15)로 맞춘다.
- 1차 N1(고저 모순 처리 불일치)은 해소: 모순 행은 `OHLC_INCONSISTENT` ERROR로 계산기에 오지 않는다(위 근거). 새 closeStrength javadoc이 이를 정확히 적는다.
- 정보: 거래정지일은 ADR-008 47행에 따라 시·고·저가 종가로 채워지므로 c가 정지일이면 close-strength는 `NO_DATA`, day-range는 0이 된다. 의도대로지만 day-range GE 게이트를 쓸 프로필이 생기면 정지일 0이 "변동 없음"과 섞인다는 점을 그때 카탈로그에 적어 두면 좋다(비차단).
- 정보: `volume-ratio`((나))가 정지일을 그대로 합에 넣는 비대칭은 카탈로그 6장 "남은 한계"에 기록됐다. 해시·기존 실험 결과 보존을 위해 그대로 두는 판단에 동의한다.

## 판정
수정 필요(F1, F2)
