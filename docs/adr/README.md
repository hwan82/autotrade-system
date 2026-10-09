# ADR (Architecture Decision Records)

모듈 간 계약(인터페이스, DB 스키마, 설정 키, REST API)을 바꿀 때 먼저 쓴다. 바꿀 때는 기존 ADR을 고치지 않고 새 번호로 "ADR-xxx 대체"를 남긴다.

| ID | 제목 | 상태 |
|---|---|---|
| [ADR-001](ADR-001-module-structure.md) | 모듈 구조, 핵심 계약, 설정 체계, 실험 스키마 초안 | 채택 |
| [ADR-002](ADR-002-real-trading-guard.md) | RealTradingGuard 골격과 실전 계좌 설정 구조 | 제안(사용자 직접 머지) |
| [ADR-004](ADR-004-simulation-engine.md) | 시뮬레이션 엔진: 전략 실행 계약, 호가 기반 가상 체결, 비용 모델, 실험 실행기, 성과 지표 | 제안 |
| [ADR-005](ADR-005-ui-dashboard.md) | 로컬 웹 대시보드 1차: 프런트 방식, 웹 보안, REST 계약, 전략 프로필 저장 | 제안 |
| [ADR-006](ADR-006-real-control-channel.md) | 실전 제어 명령 채널(로컬 소켓)과 운영 화면 1차: 실전 활성화 2차 확인, PANIC SELL | 제안(사용자 직접 머지) |
