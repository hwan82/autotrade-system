-- 하루 늦춘 장전 선정 갱신 주기 PRE_OPEN_DAILY_LAG1 (ADR-012 3.7 (a), D-137)
-- KRX 일봉이 다음 날 장 시작 전까지 게시되지 않아(2026-10-21 사용자 PC 확인) 장전 선정이 T의 직전 거래일 일봉을 받을 수 없다.
-- 기준 데이터일을 하루 앞(T−2 거래일)으로 둔 단타용 갱신 주기를 더한다. ADR-007 2.1 "단타 ⇔ 단타용 갱신 주기 집합"에 넣는다.
-- V3의 CHECK는 고치지 않고 여기서 교체한다(적용된 마이그레이션은 불변). Java SelectionRefresh.allows와 같은 규칙이다.
-- at(장중 순위 시각) 규칙(selection_profile_intraday_at_check)은 그대로다: LAG1은 at을 두지 않는다.

ALTER TABLE selection_profile DROP CONSTRAINT selection_profile_refresh_check;
ALTER TABLE selection_profile ADD CONSTRAINT selection_profile_refresh_check
    CHECK (refresh IN ('PRE_OPEN_DAILY', 'POST_CLOSE_DAILY', 'POST_CLOSE_WEEKLY', 'INTRADAY_AT', 'PRE_OPEN_DAILY_LAG1'));

ALTER TABLE selection_profile DROP CONSTRAINT selection_profile_intraday_refresh_check;
ALTER TABLE selection_profile ADD CONSTRAINT selection_profile_intraday_refresh_check
    CHECK ((style = 'INTRADAY') = (refresh IN ('PRE_OPEN_DAILY', 'PRE_OPEN_DAILY_LAG1', 'INTRADAY_AT')));
