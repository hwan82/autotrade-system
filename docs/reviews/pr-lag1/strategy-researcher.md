# strategy-researcher 검토: D-137 하루 늦춘 장전 선정 `PRE_OPEN_DAILY_LAG1`·EDGE04 사전 등록

- 대상: 브랜치 `claude/intraday-at-selection-bs1xvu` 커밋 `cadc7403` / `git diff origin/main...cadc7403`(기준 `62d81a7`, 32개 파일, +479 −56). PR 번호는 검토 시점에 몰라 `pr-lag1`에 둔다
- 기준: 지침 5장(결정론, 미래 정보 금지, 비용 차감, 생존편향, 같은 키 비교)·9·11장, 에이전트 규칙(비용 차감 전 결론 금지, 검증 구간 재사용 금지, 실패 실험 보존), ADR-012 3.7·배치표 1·3b, D-125 3·4장, LIVE02 계획 3.3
- 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·보호 경로는 이 diff에 없다 `[사실]`(diff 파일 목록: `autotrade-selection`, `autotrade-sim`, DB V4, 화면 이름표, 문서, 선정 프로필 3개, EDGE04 등록)
- 직접 확인한 것:
  - `./gradlew :autotrade-selection:test :autotrade-sim:test --tests 'com.autotrade.sim.edge.*' --tests 'com.autotrade.sim.live.LiveSelectionSessionTest' --tests 'com.autotrade.sim.selection.*'` 통과(종료 코드 0, `cadc7403`, 미러 init 스크립트 사용) `[사실]`. 전체 `build`(ArchUnit 포함)는 돌리지 않았다
  - 실제 일봉 스냅샷으로 EDGE01·EDGE04를 돌리지 않았다. 결과는 모른다(결과 전 등록이 맞는지만 봤다)

## 판정

**판정: 승인** (막는 결함 없음. 미래 정보는 원래 판보다 하루 더 적고, EDGE04는 EDGE01과 같은 키로 결과 전에 등록됐으며, 가족 교체도 결과를 보기 전에 정해졌다. 아래 n1~n5는 EDGE04 실행 전 또는 EDGE05·06 등록 커밋 전까지 정하기를 권하는 사항이다)

## 점검표

| 항목 | 결과 | 근거 |
|---|---|---|
| 미래 정보 금지 | 통과 | `SelectionCalculator.calculate`가 정보 시점 `asOf = refresh.infoDate(T)`(LAG1은 T′ = T의 직전 거래일)로 일봉 c = prev(T′) = T−2까지, 공시·재무는 접수일 < T′만 읽는다(diff 102~114행). `DisclosureSelectionCalculatorTest#하루_늦춘_판은_전_거래일_원래_판_결과와_같은_입력으로_고른다`가 T 당일 접수(800050)를 T+1 LAG1 선정에서 0건으로 확인, `LiveSelectionSessionTest#lagOneSelectionUsesDayBeforeCutoffAndMatchesPreviousDayResult`가 스냅샷 마지막 날(10-13) 급등이 10-14 LAG1 선정에 안 들어감을 확인 `[사실]` |
| 체결·측정 시점 | 통과 | 사전 검사 `SelectionEdgeStudy.day`는 선정을 `calc.calculate(profile, t)`로, 측정을 T 일봉(`range`, `net` = T 시가 매수 → T 종가 매도)으로 한다. 기준 데이터일 c만 `refresh.cutoff`로 바뀌었다(267행) `[사실]`. 정보(T−2)와 측정(T) 사이가 원래 판보다 하루 벌어질 뿐 순서는 지켜진다 |
| 비용 차감 | 통과 | `net`은 EDGE01과 같은 비용 모델·슬리피지·호가 반올림. 주 통계 `RANGE`, 판정 E3(`net`이 무작위보다 유의하게 나쁘면 탈락)도 그대로 `[사실]`(edge.yaml diff) |
| 결정론·같은 키 비교 | 통과 | EDGE04는 EDGE01과 스냅샷 `krx-daily-2026-10-16-a6df4719`·구간 11개·기술 통계 구간·`holdout-from`·`seed 20261020`·`null-seeds 999`·`family-size 3`·`trade-notional`이 같고 `id`·`question`·`selection`만 다르다. `EdgePlanTest#lagOneRegistrationDiffersFromEdge01OnlyInIdQuestionAndSelection`이 주석 외 줄을 고정 비교한다 `[사실]` |
| 사전 등록(결과 전 커밋) | 통과 | D-137 6번·EDGE04 주석: EDGE01도 아직 실행 전에 커밋 `[사실, 문서]`. 저장소에 EDGE01 결과 파일이 없다(`experiments/EXP-20261020-EDGE01/`에 edge.yaml만) `[사실]` |
| 생존편향 | 통과 | 유니버스는 c(T−2)에 상장돼 있던 종목, 폐지 예정 포함(MD-07 경로 그대로). T−2와 T 사이 폐지·거래정지로 T 일봉이 없으면 `NaN`(기존 처리)으로 빠진다. 원래 판의 T−1~T 경우와 같은 처리다 `[사실]`(`day` 296~300행) |
| 가설과 프로필 | 통과 | 프로필 3개는 `refresh`·`hypothesis`·주석·`created-at`만 원래 판과 다르다(`diff selections/kr-intraday-*/v1.yaml`, 테스트로도 고정). 가설 문구를 "전전 거래일 … 이틀 뒤"로 고쳐 실제 정보 시점과 맞췄다 `[사실]` |
| 공시 구간 이중 계산 | 통과 | 공시 구간도 T′ 기준으로 통째 하루 미뤄 "각 접수일이 정확히 한 번"(ADR-012 3.2)을 지킨다. 일봉만 미루는 안을 버린 이유(w1 구간 이틀, 같은 공시 두 번)가 맞다 `[추론]` |
| 다중 비교 | 통과(조건부, n2) | 2b 가족을 EDGE01~03 → EDGE04~06으로 결과 전에 바꾸고 크기 3을 유지. EDGE01~03은 LIVE02 판정 대상에서 빠졌다(LIVE02 계획 3.3·G2) `[사실]` |
| 실패 실험 보존 | 해당 없음 | 아직 결과 없음. 원래 판 EDGE01~03을 지우지 않고 참고로 남긴다 `[사실]` |

## 지적(막지 않음)

- **n1 "하루 늦은 비용" 비교는 기술 통계로만.** D-137 "되돌릴 조건"은 "EDGE04만 떨어지고 EDGE01은 통과하면 그 차이가 하루 늦은 정보의 비용"이라고 쓴다. 한쪽 통과·한쪽 탈락은 두 판 차이가 유의하다는 뜻이 아니다(차이를 검정하지 않았고, 대조군 모집단도 T−1·T−2 하드 필터 집합으로 서로 다르다). 이 비교는 사용자에게 (c)를 다시 묻는 계기로만 쓰고, 구간별 `RANGE`·`net` 차이를 표로 보이는 기술 통계라고 D-137 또는 EDGE 결과 리포트 양식에 한 줄 적기를 권한다 `[추론]`.
- **n2 원래 판을 다시 쓸 때의 가족.** 되돌릴 조건(KRX 당일 게시나 (c)가 생김)에서 "새 실험 ID로 원래 판을 쓴다"고 했는데, 그때 EDGE01~03 결과를 LIVE 진입 근거로 쓸 수 있는지 정하지 않았다. 두 가족(EDGE01~03, 04~06) 결과를 본 뒤 나은 쪽을 고르면 사실상 m = 6에서 고른 것이 된다. "원래 판을 실시간에 쓰려면 EDGE01~03 결과를 쓰되, 이미 LIVE02에 하루 늦춘 판이 들어간 뒤라면 별도 새 등록(새 구간 또는 가족 크기 6으로 재계산)" 같은 규칙을 결과 전에 D-137 되돌릴 조건에 고정하기를 권한다 `[추론]`.
- **n3 D-125 4장 규칙의 이름 바꿈을 명시.** D-125 4장의 (라)·공시 대조 선택 규칙과 PR #198 n2 규칙은 EDGE02·03 이름으로 적혀 있다. LIVE02 계획 3.3에 "EDGE01·02·03은 EDGE04·05·06으로 바뀐다"는 문장이 있어 해석은 되지만, EDGE05·06 등록 커밋 때 D-125 4장 규칙이 EDGE05·06에 그대로 적용됨을 그 등록 주석에 적어 두기를 권한다.
- **n4 T−2 일봉의 실제 게시 시점은 확인되지 않았다.** stage-6 runbook은 "T의 두 거래일 전 일봉까지만 필요해 전날 저녁 수집으로 준비된다"고 쓰지만, D-137 확인 결과는 "10-21 일봉이 10-22 07:34까지 없음"뿐이다. 10-21 18:31 실행 때 10-20 일봉이 있었는지는 적혀 있지 않다. KRX가 T−1 08:00 이후 언제 T−2를 게시하는지 모르므로, LAG1도 실시간에 `STALE_INPUT`이 날 수 있다 `[추론]`. LIVE02 시작 전 한 번 확인(전날 저녁 수집 로그에 T−2 행이 있는지)하고 결과를 D-137 또는 runbook에 `[사실]`로 남기기를 권한다(수집기 `KRX_SAME_DAY_FROM` 정리와 함께 data-engineer 후속).
- **n5 사전 검사 범위 확인과 LAG1 시험.** `SelectionEdgeStudy.run`의 스냅샷 범위 확인은 여전히 "첫 구간 시작 > 스냅샷 첫 날"(158행)이라 LAG1이 하루 더 앞을 읽는 것을 반영하지 않는다. EDGE04에서는 첫 구간이 기술 통계 구간(2010-03-02)이고 조회 기간(21일) 때문에 초반 며칠은 원래 판도 하드 필터에서 빠지므로 결과에 영향이 없을 것으로 본다 `[추론]`(스냅샷 첫 날은 확인하지 못했다). 확인 조건을 `refresh.cutoff(from)` ≥ 스냅샷 첫 날로 바꾸고, `SelectionEdgeStudy.day`가 LAG1에서 `c = T−2`를 내는지(`windows.tsv`의 c 열) 보는 작은 시험을 EDGE05·06 등록 전에 더하기를 권한다. 지금 LAG1 사전 검사 경로는 계산기 시험과 등록 파일 비교 시험으로만 간접 확인된다.

## 해석 한계(EDGE04 결과를 읽을 때)

- LAG1의 T 선정은 원래 판의 T−1 선정과 같다(`lagD2.selected() == hotD1.selected()`). 그래서 EDGE04는 "급등·거래량 폭발의 변동성 효과가 둘째 날까지 남는가"를 본다. 탈락해도 원래 판의 가설이 틀렸다는 뜻은 아니다.
- EDGE01과 EDGE04는 같은 T 날짜·같은 시드를 쓰지만 대조군 모집단(하드 필터 통과 집합)이 다르므로 두 p값은 독립도 아니고 짝지은 검정도 아니다.
- 이 검토와 그 결과는 투자 자문이 아니다. LIVE02 편입과 실전 적용은 사용자가 결정한다.
