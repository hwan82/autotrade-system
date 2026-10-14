# risk-ops 검토: 1단계 QA C5 정리 (Q-009 닫기 D-077, ADR-002 채택 D-078, ADR-001 2.4·2.7 정정, 스모크 보강)
- 대상: PR #89 / 브랜치 claude/project-thread-ua33rm / 커밋 6fbcf38 / `git diff origin/main...HEAD` 전체 (5개 파일: ADR-001, ADR-002, adr/README, decisions/README, scripts/ci/schema-smoke.sql). Java·Gradle·설정 코드 변경 없음.
- 점검표: A
- 직접 실행한 것:
  - `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test --rerun-tasks` 성공. 결과 XML 집계: risk 55건, architecture-test 31건, app-engine 48건, 건너뜀 0, 실패 0. (전체 `./gradlew build`는 돌리지 않음. 이 PR은 코드를 바꾸지 않아 요청된 최소 3개 모듈만 실행.)
  - PostgreSQL 16.15를 임시로 띄워 V1·V2 마이그레이션을 순서대로 적용하고 `scripts/ci/schema-smoke.sql` 실행: `ok: buy row with realized pnl rejected`를 포함해 모든 ok NOTICE 출력, `ON_ERROR_STOP` 오류 없음. 새 케이스가 `check_violation`으로 거부되는 것은 V1 `virtual_trade_log`의 `CHECK ((side='BUY' AND realized_pnl IS NULL) OR side='SELL')`(V1__lab_baseline.sql:90)와 맞다.
  - 대조 확인: `ArchitectureTest.java:120-125`의 `CLOCK_SOURCE`(com.autotrade.app. 패키지의 `*Main`과 `CoreConfiguration`), `Clock.system*` 호출 파일 목록, GitHub REST로 PR #9 병합자.
  - 작업 트리 주의: 검토 중 작업 트리에 커밋되지 않은 수정이 생겼다(ADR-001, ADR-002, decisions/README.md 각 1줄, 내가 만든 것이 아님). 이 검토는 커밋 6fbcf38 기준이며 그 수정은 판정에 넣지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·설정 로딩·테스트 변경 없음. ADR-002 변경은 상태 줄(1줄)뿐이고 "실전 안전장치라 사용자가 직접 확인하고 머지한다"는 문구가 유지된다(ADR-002:3). 보호 테스트 실행 결과 risk 55·architecture 31건 통과. |
| A2 | 통과 | 출구 팩토리·ArchUnit 코드 변경 없음. `autotrade-architecture-test:test` 31건 통과(--rerun-tasks). D-078 ②의 서술(허가 토큰 + `RealOrderGateways` + ArchUnit)은 ADR-002 7장 2번과 일치. |
| A3 | 해당 없음 | 주문 경로·증권사 라우팅 코드 변경 없음. |
| A4 | 해당 없음 | 타임아웃·재주문 코드 변경 없음. |
| A5 | 해당 없음 | 멱등 키·복구 코드 변경 없음. |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음. D-078 ⑤(OFF 출발 활성화 중 청산·손절 차단)는 ADR-002 7장 5번을 옮긴 것이고 새 동작이 아님. |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 코드 변경 없음. |
| A8 | 해당 없음 | 프로세스 분리·정지 순서 변경 없음. `autotrade-app-engine:test` 48건 통과. |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. |
| A10 | 통과 | diff를 전부 읽었고 키·계좌번호·토큰 값 없음. ADR-002의 계좌번호 예시는 자리표시자 `<ACCOUNT_NUMBER>`(ADR-002:75-78 부근, 기존 내용). 스모크 SQL의 값은 합성 데이터. |
| A11 | 통과 | 삭제·비활성화·약화된 테스트 없음. 스모크는 케이스가 1개 추가(`schema-smoke.sql` +10줄)되어 강해졌다. 새 케이스는 거부되어야 하는 실패 경우이고, 수용되면 `RAISE EXCEPTION 'buy row with realized pnl was accepted'`로 CI가 깨지게 되어 있다(직접 실행으로 거부 확인). |
| A12 | 통과 | broker-and-fault-design.md·장애 테스트 8종 변경 없음. 17종 표와 O1~O8 전제를 건드리는 서술 없음. |
| A13 | 해당 없음 | 시장별 경로 코드 변경 없음. |
| A14 | 통과(발견 2건은 아래, 막지 않음) | D-077 10건은 ADR-001 6장 1~10번과 항목 수·내용이 일치(ADR-001:247-260). D-078 8건은 ADR-002 7장 1~8번과 일치(ADR-002:165-174). "PR #9 사용자 머지 2026-10-08"은 GitHub REST로 확인: merged_by=hwan82, merged_at=2026-10-08T21:32:40Z. ADR-001 정정(시스템 시계 생성 7곳)은 `Clock.system*` 호출 파일 목록(EngineMain, GatewayMain, KiwoomDailyMain, WatchdogMain, CollectorMain, ExperimentMain, CoreConfiguration)과 일치하고, `ReplayClock.java:12`는 주석 언급뿐이며 `CLOCK_SOURCE`(ArchitectureTest.java:120-125)도 서술과 같다. 정정은 규칙을 약화하지 않는다(규칙 자체는 그대로). D-029 "번호 미사용" 문구는 `grep -rn D-029`로 확인한 결과 결정 로그 줄 외에는 검토·QA 문서의 "부재" 언급뿐. Q-009 닫기는 ADR-001 2.7(DB 엔진 PostgreSQL 16 채택)과 일치. 실전 규칙을 바꾸는 결정은 새로 만들지 않았고, 기존 사용자 결정(PR #9)에 번호만 붙였다. |

## 점검표 밖 발견
1. (낮음, 문서) `docs/spec/autotrade-spec.md:360`의 미확정 표에 Q-009가 아직 열린 항목으로 남아 있다. 이 PR이 결정 로그에서 닫았으므로 기획서도 맞춰야 한다. 이 PR이 고치지 않아도 판정에는 영향 없음(결정 로그가 단일 원본, 다음 기획서 정리에 포함 권고).
2. (낮음, 문서) D-077 ⑩의 "D-078 ③에서 확정"은 D-078 ③이 계좌 키 구조(accounts 배열 + account-numbers 맵)를 다루므로 근거가 정확하지 않다. `enabled` 미저장의 실제 근거는 ADR-001:177(`enabled` 키는 파일에 저장하지 않는다)과 모르는 키 파일 거부 규칙이다. 의미는 안전 방향(저장 안 함)이라 위험은 없다.
3. (정보) 결정 로그 D-029 줄이 `docs/reviews/gates/stage-1.md`를 링크하는데 그 파일은 아직 없다(`docs/qa/pending.md:15`에 QA 요청이 대기 중이므로 판정 때 생길 파일). 지금은 깨진 링크.
4. (정보) D-078 ⑥ "60초 `[가정]`(Q-008에서 확정)"처럼 가정 표시가 그대로 유지되어, 실전 한도 값이 이 PR로 확정된 것처럼 보이지 않는다. 좋음.
5. 이 PR은 실전 코드를 건드리지 않는 문서·CI 스모크 PR이다. 다만 ADR-002 상태를 "제안"에서 "채택"으로 바꾸고 실전 안전장치 결정에 번호를 부여하므로 지침 10장에 따라 사용자가 직접 확인하고 머지하는 것이 맞다(실전 안전 ADR 변경). 머지는 메인이 하지 않는다.

## 판정
승인
