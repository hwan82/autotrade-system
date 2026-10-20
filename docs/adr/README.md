# ADR (Architecture Decision Records)

모듈 간 계약(인터페이스, DB 스키마, 설정 키, REST API)을 바꿀 때 먼저 쓴다. 바꿀 때는 기존 ADR을 고치지 않고 새 번호로 "ADR-xxx 대체"를 남긴다.

| ID | 제목 | 상태 |
|---|---|---|
| [ADR-001](ADR-001-module-structure.md) | 모듈 구조, 핵심 계약, 설정 체계, 실험 스키마 초안 | 채택 |
| [ADR-002](ADR-002-real-trading-guard.md) | RealTradingGuard 골격과 실전 계좌 설정 구조 | 채택(PR #9 사용자 머지, D-078) |
| [ADR-003](ADR-003-market-data.md) | 시세 게이트웨이, 녹화·리플레이, 거래소 캘린더, 일봉 스냅샷 | 채택 |
| [ADR-004](ADR-004-simulation-engine.md) | 시뮬레이션 엔진: 전략 실행 계약, 호가 기반 가상 체결, 비용 모델, 실험 실행기, 성과 지표 | 제안 |
| [ADR-005](ADR-005-ui-dashboard.md) | 로컬 웹 대시보드 1차: 프런트 방식, 웹 보안, REST 계약, 전략 프로필 저장 | 채택(D-026) |
| [ADR-006](ADR-006-real-control-channel.md) | 실전 제어 명령 채널(로컬 소켓)과 운영 화면 1차: 실전 활성화 2차 확인, PANIC SELL | 채택(D-037, PR #18 사용자 머지) |
| [ADR-007](ADR-007-selection-combination.md) | 선정 프로필과 조합: 형식·해시, `combination_hash`, `POSITION`, 선정 결과 파일, 실험 계약 변경, 실전용 선정 결과 생산자 | 제안(사용자 직접 머지) |
| [ADR-008](ADR-008-collector.md) | 과거 데이터 수집 일괄 명령(Q-037): Java 수집기, 수집기 전용 키 파일, 일봉·상장주식수·재무 스냅샷 | 제안 |
| [ADR-009](ADR-009-daily-walkforward-runner.md) | 일봉 워크포워드 판정 실행기: 예열·구간 끝 정리 계약, `study.yaml`, 결과 형식 | 채택(D-072) |
| [ADR-010](ADR-010-live-feed-and-session.md) | 실시간 시세 전달(게이트웨이 → 실험실, 로컬 소켓)과 실시간 모의 세션: 입력 기록 먼저, 리플레이 확인 명령 | 채택(D-076) |
| [ADR-011](ADR-011-watchdog-heartbeat.md) | 감시 프로세스 1차: 하트비트 파일 계약, 감시 설정 파일(틀리면 기동 거부), 감시 에이전트 코어 의존 | 제안(D-092, 사용자 머지) |
| [ADR-012](ADR-012-intraday-selection.md) | 단타 종목 선정: 장전 일봉·공시 선정과 실시간·녹화 실험 연결, 뉴스 신호 앞으로 전용 수집, 장중 순위 선정(2차) | 채택(D-121) |
| [ADR-014](ADR-014-live02-judgement-roles-precheck.md) | LIVE02 등록 전 판정 계약: 참고 계좌 역할 `references`, 대조군 시드 수 `group-null-seeds`, 녹화 사전 검사 명령, 공시 입력 키 | 채택(D-131) |
