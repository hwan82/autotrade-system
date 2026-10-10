# D-057 선정 조건 카탈로그·선정 계산, 스타일별 무작위 대조군, 중기 검증 규칙 (배치표 2번)

- 날짜: 2026-10-10
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 중기 선발 수치는 제안만 하고 Q-013에 남김(승격 기준 변경은 사용자 결정)
- 담당: strategy-researcher(카탈로그·프로필·절차), sim-engine(실험실 공급자). 교차 검토: architect
- 근거: 기획서 STR-10·15~18, MD-07·10·11, [D-041·D-042·D-043](D-041-selection-x-signal-combinations.md), [ADR-007](../adr/ADR-007-selection-combination.md)(D-049) 2.1·2.3·2.6·2.7, PR #44 `SelectionSource`
- 문서: [선정 조건 카탈로그](../strategy/selection-catalog.md), [조합 실험 절차](../strategy/combination-experiments.md)
- 범위: 실험실 쪽만. 결과 파일 코덱·유효성 판정(`SelectionValidity`)·생산 일괄 명령·실전 엔진 읽기는 실전 관련 별도 PR

## 결정

1. **선정 조건 문법**: `filter.kinds`·`filter.min-price`·`filter.min-bar-days`, `gate.<nn>.{metric,op,value,window|years}`(모두 AND), 선택 `score.{metric,window|years,order}` 한 지표. 모르는 키 거부(`notes.*` 예외). 지표 9종(`op-margin-growth`, `total-assets-growth`, `revenue-growth`, `op-margin`, `debt-ratio`, `market-cap`, `volume-ratio`, `return`, `avg-turnover`). 재무 지표는 KRX만.
2. **계산 기준**: 선정 기준일 T = `apply_from`, 기준 데이터일 = T의 직전 거래일. 유니버스는 그날 상장 종목(이후 폐지 포함). 일봉 결측은 보간하지 않고 `NO_DATA`. 수정 거래량 = 거래량 ÷ 수정 계수. 값은 중간 계산 `DECIMAL128`, 마지막에 한 번 `DECIMAL64`로 줄인 뒤 `toPlainString`(경계값 보존, architect F2·F2b). 조건이 쓰는 입력이 없거나 manifest 수록 기준일이 기준 데이터일보다 이르면(재무는 수집 시각의 현지 날짜) 계산하지 않고 실패하며, 결과 `inputs`의 기준일은 manifest 값을 옮긴다(PR #45 architect F1).
3. **재무 시점 규칙 구현**(MD-10): 사업보고서만, "접수일 < T". 그 연도 가장 늦은 공시 값이 있으면 기본 규칙, 없으면 그 연도를 아직 없는 것으로 보고 직전 사업연도를 씀(대체 규칙, 평가에 표시·건수). 비교 기준 연도 값을 모르면 대체하지 않고 결측.
4. **순위**: 점수 순(같으면 종목 코드), 점수 없으면 종목 코드 순으로 `max-symbols`까지. 넘친 종목은 `RANK_CUTOFF`.
5. **예시 프로필**: (가) `kr-fin-quality-growth@1` POSITION·주간·50종목·영업이익률 증가율 순, (나) `kr-vol-surge-quiet@1` SWING·매일·30종목·거래량 비율 순. 조건 값은 D-043 그대로.
6. **결과 모델과 내용 해시**: ADR-007 2.6 필드 그대로의 `SelectionResult`와 정규 JSON(키 사전순·공백 없음) SHA-256을 지금 `selection.result`에 둔다. 실험실 `run_selection_day` 결과 해시가 생산 일괄 명령 결과 파일의 `content_hash`와 같은 규칙이 되게 하려는 것이다. 입력 칸은 쓴 입력만 채운다(시가총액 조건이 없으면 `shares` null, 재무 조건이 없으면 `financials` null). 재무 칸 모양은 `{snapshot_id, sha256, last_collected_at}`이다(ADR-007 2.6 예시에 없던 칸, architect F5).
7. **실험실 공급자** `CalculatedSelectionSource`: 거래일마다 적용 결과의 기준일(매일 = 그날, 주간 = 그 ISO 주 첫 거래일)을 정해 계산. 주중 시작 실험도 그 주 첫 거래일 결과를 쓴다. 같은 (선정 해시, 기준일) 결과는 한 번만 계산한다.
8. **중기·스윙 보유 규칙**(STR-18): `POSITION`은 `CARRY`만, `exit.max-hold-days` 20~120 필수. 스윙은 카탈로그에서 선택 2~20이고, 저장소 검사가 `kr-ma-trend@1`·`kr-bh-baseline@1` 밖의 스윙 프로필에 필수로 요구한다(기존 해시·동작 불변). 단타는 그 키를 받지 않는다. 진입 거래일 = 0일째, 상한에 이른 날 첫 이벤트에서 `TIME_EXIT`.
9. **일봉 무작위 대조군** `random-daily`(SWING·POSITION): 종목·거래일 첫 체결마다 정확히 한 번 추첨(보유 상태와 무관해 난수열이 결과에 따라 갈라지지 않음). `ma-trend`는 POSITION도 받는다. 새 프로필 `kr-ma-trend-position@1`, `kr-random-daily-swing@1`, `kr-random-daily-position@1`.
10. **조합 실험 절차**: 사전 등록, 같은 선정 + 무작위 진입 / 같은 지표 + 선정 없음 대조군, 본페로니 보정(대조군 분포 순위 백분위 ≥ 1 − 0.05/m), 결정론 재실행, 선정 품질 기록.

## 대안과 되돌릴 조건

대안 비교는 [카탈로그 6장](../strategy/selection-catalog.md#6-대안과-한계)과 아래.

| 선택 | 버린 대안 | 이유 | 되돌릴 조건 |
|---|---|---|---|
| 스윙 최대 보유일을 저장소 검사로 새 버전에만 요구 | 카탈로그에서 스윙도 필수 | `kr-ma-trend@1`이 검증에서 떨어져 기존 실험(`EXP-20261008-STR01`)을 같은 코드로 재현할 수 없음 | 기존 두 버전을 은퇴시키면 카탈로그 필수로 바꾸는 새 결정 |
| `random-daily` 새 로직 | 기존 `random`이 스타일에 따라 동작을 바꿈 | 같은 로직 이름이 스타일마다 다른 추첨 단위를 가지면 프로필만 보고 동작을 알 수 없음 | — |
| 결과 해시를 지금 정의 | 실험실은 임시 해시, 코덱 PR에서 정의 | 실험 기록(`run_selection_day`)의 해시가 나중에 바뀌어 6단계 실시간 모의와 대조할 수 없음 | 코덱 PR 검토에서 형식이 바뀌면 새 스키마 번호로 |

## 위험과 한계
- 관리종목·투자경고·거래정지·우선주 필터는 스냅샷에 상태 칸이 없어 아직 없다(data-engineer 변경 제안, 카탈로그 6장).
- 합성 데이터 골든 테스트로만 검증했고 실제 스냅샷 실행은 키 발급 뒤다.
- 중기 선발 수치(3장)는 `[가정]`이고 Q-013에서 사용자 결정이 필요하다.
- 최대 보유일 상태(`dayIndex`, 진입일)는 전략 객체 메모리에만 있고 받은 이벤트의 날짜 수로 센다. 실험실 리플레이에서는 문제없지만, 실전 엔진이 이 전략 코드를 쓰게 되는 PR에서 재기동 복구와 거래일 기준 계산을 risk-ops가 검토해야 한다(architect F3).
- 결과의 `selected`와 `passed=true` 평가가 맞는지 보는 일은 결과 파일 코덱 PR의 `MALFORMED` 판정에, ArchUnit 규칙 "실전 엔진은 선정 계산을 쓰지 않는다"는 엔진에 selection 의존을 더하는 PR에 넣는다(architect F5. 지금은 엔진에 그 의존이 없어 Gradle이 막는다).

## 검증
- `autotrade-selection` 테스트: (가)(나) 손 계산 골든, 입력 수록 기준일 확인과 실제 스냅샷 폴더 읽기(`SnapshotSelectionDataTest`), 수정 계수가 1이 아닌 거래량 비율·수익률 경계, 영업이익률 증가율 경계, 경계값, 정정 두 갈래, 접수일 당일 미사용, 분할 시가총액, 상장폐지 유니버스, 결정론(같은 입력 같은 해시), 문법 거부, 정규 JSON 고정 문자열.
- `autotrade-strategy` 테스트: POSITION 검증, 스윙·단타 최대 보유일 키 규칙, 최대 보유일 청산 시점, `random-daily` 결정론·하루 한 번.
- `autotrade-sim` 테스트: KRX 2026 캘린더(추석·대체공휴일 낀 주)에서 주간 결과 하나가 한 주를 덮고 두 조합이 공유, 매일 갱신 결정론, 저장소 프로필 검사.
