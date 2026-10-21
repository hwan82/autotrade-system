# D-137 하루 늦춘 장전 선정 `PRE_OPEN_DAILY_LAG1`: KRX 일봉이 장 전까지 게시되지 않아 기준 데이터일을 하루 앞으로(ADR-012 3.7 (a), 배치표 1·3b)

- 날짜: 2026-10-22
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). ADR-012 3.7이 미리 정한 추천안 (a)를 확인 결과대로 실행한 것이다. `autotrade-selection`·`autotrade-sim`·DB 이전 V4·화면 이름표·선정 프로필 3개·일봉 사전 검사 등록 EDGE04만 바꾼다. 실전 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 게이트웨이, 수집기, 보호 경로는 바꾸지 않는다. 기존 갱신 주기의 계산 결과는 그대로다(LIVE00·LIVE01 불변, 기존 시험 그대로 통과).
- 담당: sim-engine. 교차 검토: architect(코드·스키마), strategy-researcher(선정 정의·사전 검사)
- 근거: [ADR-012](../adr/ADR-012-intraday-selection.md) 3.7·배치표 1·3b, [D-121](D-121-intraday-selection.md) "되돌릴 조건", [D-127](D-127-intraday-at-selection.md) 12번, [LIVE02 계획](../strategy/live02-preregistration-plan.md) 3.3, [ADR-007](../adr/ADR-007-selection-combination.md) 2.1·2.6. 번호: main과 열린 브랜치 전부에서 가장 큰 번호가 D-136(PR #222)이라 D-137을 쓴다.

## 확인 결과(배치표 1번)

- `[사실, 사용자 PC 콘솔]` `krx-daily --from 2026-10-14 --no-snapshot`을 2026-10-21 18:31, 21:01, 2026-10-22 07:34(KST)에 세 번 돌렸다. 세 번 모두 2026-10-21 KOSPI·KOSDAQ·KONEX 일봉이 게시 전이었다.
- `[추론]` KRX Open API는 T 일봉을 T+1 07:34까지 게시하지 않는다. 08:00 뒤 정확한 게시 시각은 모른다. 수집기가 08:00부터 장 시간 보호로 멈추기 때문이다(ADR-008 2.3). 2차 자료의 "매일 오전 8시 갱신"(ADR-012 3.7)과 어긋나지 않는다.
- 그래서 장전 선정 `PRE_OPEN_DAILY`(기준 데이터일 c = T의 직전 거래일)는 실시간 모의에서 T 장 전에 c 일봉을 받을 수 없다. 준비 작업은 매일 `STALE_INPUT`을 내고, 그 조합 계좌는 매일 신규 진입 0건이 된다. 리플레이(과거 스냅샷)에서는 이 문제가 보이지 않는다.

## 결정

1. **새 갱신 주기 `PRE_OPEN_DAILY_LAG1`**(ADR-007 2.1 단타 집합에 더함). 단타 선정의 갱신 주기는 `PRE_OPEN_DAILY`·`PRE_OPEN_DAILY_LAG1`·`INTRADAY_AT`이다(`SelectionRefresh.intraday()`). `at` 키는 받지 않는다(`INTRADAY_AT` 전용 그대로).
2. **정의**: 적용일 T 하루. 계산은 T의 직전 거래일 T′를 선정 기준일로 둔 `PRE_OPEN_DAILY` 계산과 같다(정보 시점 I = T′, `SelectionRefresh.infoDate`).
   - 일봉·상장주식수는 c = T′의 직전 거래일(T의 두 거래일 전)까지 읽는다(`SelectionRefresh.cutoff`).
   - 재무·공시는 접수일 < T′만 읽는다(공시 구간 끝날 T′ 전날).
   - 유니버스는 c에 상장돼 있던 종목이다.
   - 그래서 결과의 선정·평가·입력은 T′에 적용할 원래 판 결과와 같고, 적용일(`apply_from`·`apply_to`)만 T다(`DisclosureSelectionCalculatorTest`, `LiveSelectionSessionTest#lagOneSelectionUsesDayBeforeCutoffAndMatchesPreviousDayResult`). 미래 정보는 원래 판보다 하루 더 적다.
   - 버린 정의: 일봉만 하루 앞당기고 공시·재무는 T 전날까지 두는 안. 공시 `disclosure-count` w1 구간이 [c, T−1]로 이틀이 되어 날마다 선정하면 같은 공시를 두 번 센다(ADR-012 3.2의 "각 접수일이 정확히 한 번"이 깨짐). 또 "원래 판의 하루 늦은 판"이라는 비교가 흐려진다.
3. **기준 데이터일을 선정마다 정한다**: 준비 작업(`SelectionPreparer`·`LiveSelectionPrep`), 녹화 실험 준비, 날짜 전환 지점 판정(`SessionSelections`), 일봉 사전 검사(`SelectionEdgeStudy`)가 c를 하나로 두지 않고 선정의 갱신 주기로 정한다. 같은 등록에 두 판이 함께 있으면 일봉이 T−2까지만 있는 날 하루 늦춘 판은 계산되고 원래 판만 `STALE_INPUT`이다. `latest` 스냅샷이 가장 이른 c도 덮지 못할 때만 모든 선정을 한꺼번에 `STALE_INPUT`으로 남긴다.
4. **DB 이전 `V4__selection_refresh_pre_open_lag1.sql`**(V2·V3 파일은 고치지 않음). `selection_profile`의 `refresh` CHECK에 값을 더하고, "단타 ⇔ 단타용 갱신 주기 집합"에 `PRE_OPEN_DAILY_LAG1`을 넣는다. `scripts/ci/schema-smoke.sql`이 받는 행 1개(단타)와 거부 2개(스윙, `at` 있음)를 확인한다.
5. **선정 프로필 3개**(원래 판은 고치지 않음, 조건·수치는 원래 판과 같고 `refresh`·가설 문구만 다름):
   - `kr-intraday-hot-lag1@1`: (다)의 하루 늦춘 판
   - `kr-intraday-news-lag1@1`: (라)의 하루 늦춘 판
   - `kr-intraday-news-nodisc-lag1@1`: (라) 공시 대조의 하루 늦춘 판
   - 버전을 올리지 않고 새 ID로 둔 이유: 두 판을 함께 돌려 비교한다(ADR-012 3.7). `kr-intraday-hot@2`로 두면 원래 판을 대체한 것처럼 읽힌다.
6. **LIVE02 계획 3.3 실행**: LIVE02 실시간에는 원래 판을 쓸 수 없으므로 C1~C3 자리에 하루 늦춘 판을 넣는다. 매매지표 짝(계획 3.1)은 그대로다. 하루 늦춘 판은 원래 판 EDGE 결과를 근거로 쓰지 않고 같은 키로 새 사전 검사를 통과해야 한다(계획 3.3).
   - **EDGE04** `experiments/EXP-20261022-EDGE04/edge.yaml`(`kr-intraday-hot-lag1@1`): EDGE01과 같은 스냅샷·구간·시드·대조군 수·주 통계 `RANGE`, 다른 것은 `id`·`question`·`selection`뿐이다. 결과를 보기 전(EDGE01도 아직 실행 전)에 커밋한다.
   - EDGE05·EDGE06(하루 늦춘 (라)·공시 대조)은 EDGE02·03과 같은 시점(공시 스냅샷 뒤)에 같은 키로 커밋한다.
   - 2b 가족 크기는 3 그대로다. LIVE02에 들어갈 수 있는 2b 가족이 하루 늦춘 판 셋(EDGE04~06)으로 바뀐 것이다. EDGE01~03은 원래 판과 하루 늦춘 판의 차이를 보는 참고로 남고 LIVE02 판정 대상이 아니다.
7. **화면**: 갱신 주기 이름표 "매일 장전(하루 늦춘 일봉)"(`format.js`), 선정 편집 화면 안내 문구. 선정 편집 화면의 갱신 주기 목록은 카탈로그 API가 `SelectionRefresh` 값을 그대로 내므로 저절로 생긴다.

## 대안

| 대안 | 내용 | 판단 |
|---|---|---|
| (a) 하루 늦춘 갱신 주기(채택) | 기준 데이터일 T−2, 시장 전체 유니버스 유지 | 계산 경로가 원래 판과 같고 일봉 사전 검사·워크포워드로 과거 검증이 된다. 하루 늦은 정보라 단타 효과가 줄 수 있어 두 판을 사전 검사로 비교한다 |
| (b) 게이트웨이 녹화로 c의 boards 일봉 만들기 | 장 마감 녹화에서 boards 종목 일봉을 만들어 입력으로 씀 | 유니버스가 boards(100종목)로 줄어 선정 정의가 바뀌고, 과거 검증 표본이 녹화 기간뿐이다. 버림 |
| (c) 다른 일봉 출처(증권사 일봉 조회) | 키움·한투 일봉 조회로 c 일봉을 장 전에 받음 | 증권사 연동 경로(앱키·레이트리밋)를 수집기에 더 넣어야 하고 실전 관련 검토가 필요하다. 사용자 결정 사항이라 이번에 하지 않는다(아래 "사용자에게 물을 것") |
| (d) 수집기 장 시간 보호를 08:00 뒤로 늦춤 | 08:00~08:30에 KRX 수집 | 08:00 게시조차 확인되지 않았고(`[추론]`), 장 시간 보호는 실전 엔진 자원 보호 규칙(지침 4장 6번)이라 바꾸지 않는다 |

되돌릴 조건: KRX가 당일 저녁에 게시하는 것으로 바뀌거나 (c)가 생기면 실시간에도 원래 판을 쓸 수 있다. 그때는 하루 늦춘 판을 지우지 않고 새 실험 ID로 원래 판을 쓴다. 사전 검사에서 하루 늦춘 판만 떨어지고 원래 판은 통과하면, 그 차이가 "하루 늦은 정보의 비용"이다. 그때 (c)의 가치를 사용자에게 다시 묻는다.

## 위험과 한계

- 하루 늦은 정보라 (다)의 "전일 급등·거래량 폭발 다음 날" 가설이 "이틀 뒤"로 바뀐다. 효과가 약하거나 사라질 수 있다 `[추론]`. EDGE04 결과로 판단한다.
- 하루 늦춘 판의 사전 검사는 원래 판과 같은 대조군 규칙(같은 날 하드 필터 통과 종목에서 무작위)을 쓴다. 하드 필터도 T−2 일봉으로 정해진다.
- 08:00 이후 게시 시각을 모른다. 아침 수집으로 T−1 일봉을 받을 길은 이 결정에서 열지 않는다.
- `CollectorMain.KRX_SAME_DAY_FROM`(18:00)과 주석은 당일 게시를 가정한다. 수집기 쪽 문구·동작 정리는 data-engineer 후속이다(이 PR은 수집기를 바꾸지 않음).

## 후속

- EDGE04 실행(사용자 PC, EDGE01과 같은 날): [selection-edge-check runbook](../runbooks/selection-edge-check.md) 2장.
- EDGE05·EDGE06 등록(공시 스냅샷 뒤, EDGE02·03과 같은 커밋).
- 수집기 `KRX_SAME_DAY_FROM` 당일 게시 가정 정리와 runbook 3.7 문구(data-engineer).
- 결과 DB 저장을 붙일 때 `selection_result`가 하루 늦춘 판도 담는지 확인(V4는 `selection_profile`만 바꿈).

## 사용자에게 물을 것(진행은 막지 않음)

- 대안 (c) 증권사 일봉 조회로 T−1 일봉을 장 전에 받는 길을 열지. 과금은 없지만(시세 조회 무료, 2026-10-09 사용자 확인) 수집기가 증권사 앱키를 더 쓰게 되어 실전 관련 검토가 필요하다. 추천은 **지금은 열지 않음**이다. EDGE01과 EDGE04 결과를 비교해 하루 늦은 비용이 크다고 나올 때 다시 묻는다.
