# architect 검토: 단타 선정 지표 day-range·close-strength·volume-spike와 예시 (다) kr-intraday-hot(ADR-012 배치표 2번)
- 대상: PR #193 / 커밋 4d93d1c / main 대비 diff(`git diff origin/main...HEAD`, 9개 파일 +335 −2)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --init-script <저장소 밖 Maven Central 미러 init 스크립트> build` → BUILD SUCCESSFUL(138 tasks, 대부분 같은 head의 이전 실행 결과로 up-to-date)
  - 캐시를 믿지 않으려고 `:autotrade-selection:test --rerun :autotrade-architecture-test:test --rerun` 재실행 → BUILD SUCCESSFUL. test-results XML 집계: autotrade-selection 40건 실패 0·오류 0·건너뜀 0, autotrade-architecture-test 46건 실패 0·오류 0·건너뜀 0. `IntradaySelectionCalculatorTest` 6건 모두 통과
  - 손 계산 재확인: 400010 거래대금 10,300 × 2,000,000 = 20,600,000,000, 400060 10,300 × 970,873 = 9,999,991,900(< 100억), 400090 수정 종가 20,000 × 0.5 = 10,000 → 수익률 0.06·수정 거래량 500,000 ÷ 0.5 = 1,000,000 → 급증 2, 500030 (10500−9500)×f ÷ (10000×f) = 0.1(DECIMAL128에서 정확), 600040 400 ÷ 600 → DECIMAL64 0.6666666666666667. 모두 테스트 기대값과 일치

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. 전체 build 성공, 선정·아키텍처 테스트 재실행 성공 |
| B2 | 통과 | 운영 코드 변경은 `autotrade-selection`(순수 Java, `com.autotrade.selection.calc`)과 `autotrade-ui` 정적 JS뿐. 새 의존 없음(diff에 build 파일 없음). `scripts/ci/protected-paths.txt`의 보호 경로를 하나도 건드리지 않음(diff 파일 목록 대조). ArchitectureTest·ModuleOwnershipTest 재실행 통과 |
| B3 | 통과 | 계약 변경 = 선정 프로필 `gate.<nn>.metric`·`score.metric`에 쓸 수 있는 키 3개 추가와, 그 결과로 `SelectionCatalogController.java:24`(`Metric.values()`를 그대로 노출)의 카탈로그 REST 응답에 항목 3개가 더해지는 것(추가만, 기존 항목 불변). ADR-012 3.2(채택, D-121)가 정의·결측 규칙을 먼저 정했고 `docs/strategy/selection-catalog.md` 3장 표·5장 (다)·골든 테스트 목록, `selections/README.md`가 갱신됨. 정의 대조: `SelectionCalculator.java:379`(day-range: c 원가 고저차 × c 수정 계수 ÷ c−1 수정 종가 = ADR-012 3.2 "모두 수정 가격"), `:397`(close-strength: 원가격, 수정 계수 약분), `:410`(volume-spike: c 수정 거래량 × w ÷ 앞 w일 수정 거래량 합) 모두 ADR 표와 일치. 기존 지표 키·기간 규칙은 그대로라 기존 프로필 해시 불변(SelectionCalculatorTest 골든 통과) |
| B4 | 통과 | 새 코드에 Clock·`now()`·난수 없음(`grep` 결과 0건). 입력은 `days.previous(applyFrom)`의 c까지만 읽는 `ctx.lastBars`·`ctx.bars.get(ctx.cutoff)`이고 창 길이는 `SelectionCriteria.java:92-93`의 lookback(day-range 2, volume-spike w+1, close-strength 기본 1)로 정해짐. 미래 정보 미사용은 `IntradaySelectionCalculatorTest#다_선정은_T_당일_이후_일봉을_쓰지_않는다`(T·T+1 일봉 추가 시 contentHash 불변, c 변경 시 변함)로 확인. 순위는 기존 점수 → 종목코드 정렬 그대로. 기존 골든(SelectionCalculatorTest) 통과 |
| B5 | 통과 | 모든 계산이 `BigDecimal`, 중간 `DECIMAL128`(`SelectionCalculator.java:55`), 값만 마지막에 `DECIMAL64`(`:53`, `Value.of`). 거래량은 `long` → `BigDecimal.valueOf`. double 없음. 경계 테스트(수정 계수 0.9876543210123에서 정확히 0.1, 0.123456789에서 정확히 2)로 반올림 흔들림 없음을 확인 |
| B6 | 통과 | 새 테스트 `IntradaySelectionCalculatorTest` 6건: (다) 골든(경계 0.03·0.25 포함, 0.0299·0.2501·1.999999·100억 미만 탈락, 필터 2종, 분할 낀 종목, 순위), 미래 정보, 지표별 3건(분할·경계·결측·고가=저가·앞 구간 합 0), 기간 키 거부와 lookback. 기존 테스트 삭제·`@Disabled` 없음(diff에 테스트 삭제 줄 없음, 건너뜀 0건) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동 변경 없음. 선정 계산기는 나중에 실전용 선정 생산자도 쓰지만(D-044) 그 연결은 ADR-012 배치표 5번 PR(risk-ops 검토·사용자 머지)이고 이 PR은 새 지표를 쓰는 프로필을 실전에 연결하지 않음 |
| B8 | 통과 | diff에 키·토큰·계좌번호 문자열 없음(diff 대상 `grep -iE "api[_-]?key|secret|token|account|계좌번호"` 0건). 새 코드는 로그를 남기지 않음 |

## 점검표 밖 발견
- N1(권고, 비차단): 고저 모순 처리 불일치. `DailyBar`는 고가 ≥ 저가, 저가 ≤ 종가 ≤ 고가를 검증하지 않는다. `closeStrength`(`SelectionCalculator.java:402-404`)는 고가 < 저가를 `NO_DATA`로 막지만 `dayRange`(`:389`)는 같은 모순 일봉에 음수 값을 내고, `closeStrength`는 종가가 [저가, 고가] 밖이면 0~1 밖 값을 그대로 낸다. KRX 원천에서는 생기기 어려워[추론] 지금 선정 결과에 영향은 없지만, 두 지표의 결측 규칙을 "고가 < 저가 또는 종가가 범위 밖이면 `NO_DATA`"로 맞추거나 수집기 검증으로 올리는 것을 후속으로 제안한다. 규칙을 바꾸면 계산 결과가 바뀔 수 있으니 새 지표 키 없이 고치려면 카탈로그에 "버그 수정" 기록이 필요하다.
- N2(정보): ADR-012 배치표 2번의 담당은 strategy-researcher·sim-engine이고 (다)의 가설·출발값·사전 검사 연결(배치표 2b)은 실험·통계 영역이다. 프로젝트 지침 9장 교차 검토에 따라 strategy-researcher 검토가 따로 필요하다(이 판정은 코드·계약 측면만 본다).
- N3(정보): `filter.min-bar-days: 21`과 volume-spike w20의 lookback 21이 일치하고, (다)의 `refresh: PRE_OPEN_DAILY`에서 applyTo = T 하루임을 골든이 고정한다. 선정 화면은 카탈로그 REST의 `param`으로 기간 입력을 그리므로(`selections.js:158,167`) NONE 지표 2개에는 기간 칸이 나오지 않는다 [추론, 화면은 실행해 보지 않음 — 표시 이름 3개 추가 외 JS 로직 변경 없음].

## 판정
승인
