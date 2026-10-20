-- 장중 순위 선정 갱신 주기 INTRADAY_AT (ADR-012 5.2 2차-a, 배치표 3b·7, D-127)
-- ADR-007 2.1 개정: "단타 ⇔ PRE_OPEN_DAILY"를 "단타 ⇔ 단타용 갱신 주기 집합(PRE_OPEN_DAILY, INTRADAY_AT)"으로 바꾼다.
-- V2의 CHECK는 고치지 않고 여기서 교체한다(적용된 마이그레이션은 불변). Java SelectionRefresh.allows와 같은 규칙이다.
-- INTRADAY_AT은 순위 시각 at(HH:mm, 거래소 현지)이 parameters에 있어야 하고, 다른 갱신 주기는 at을 두지 않는다(SelectionProfile).

ALTER TABLE selection_profile DROP CONSTRAINT selection_profile_refresh_check;
ALTER TABLE selection_profile ADD CONSTRAINT selection_profile_refresh_check
    CHECK (refresh IN ('PRE_OPEN_DAILY', 'POST_CLOSE_DAILY', 'POST_CLOSE_WEEKLY', 'INTRADAY_AT'));

ALTER TABLE selection_profile DROP CONSTRAINT selection_profile_check;
ALTER TABLE selection_profile ADD CONSTRAINT selection_profile_intraday_refresh_check
    CHECK ((style = 'INTRADAY') = (refresh IN ('PRE_OPEN_DAILY', 'INTRADAY_AT')));

ALTER TABLE selection_profile ADD CONSTRAINT selection_profile_intraday_at_check
    CHECK ((refresh = 'INTRADAY_AT') = (parameters ? 'at'));

-- selection_result·run_selection_day는 장전·장 마감 뒤 선정 결과(일봉 스냅샷 입력)만 담는다. 장중 순위 결과(입력 기록 기반,
-- IntradaySelectionResult)를 결과 DB에 넣는 일은 결과 DB 저장을 붙일 때 정한다(D-127 후속).
