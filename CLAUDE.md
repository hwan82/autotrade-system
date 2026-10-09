# 자동매매 시스템 (autotrade-system)

개인 PC에서 동작하는 Java 21 / Spring Boot 기반 주식 자동매매 시스템.
1차 목표는 실전 API 실시간 시세 위에서 자체 모의 계좌 여러 개로 매매 기법을 병렬 운용하고 비교해 실전에 쓸 기법을 찾는 시뮬레이션 실험실이다.

## 반드시 따를 규칙
@docs/team/project-instructions.md

## 기준 문서
- 팀 구성과 시스템 개념: docs/team/team-design.md
- 역할별 지침: docs/team/role-guides.md
- 증권사 연동과 장애 대응: docs/team/broker-and-fault-design.md
- 해외(미국)주식: docs/team/overseas-design.md
- 확정된 결정: docs/decisions/README.md
- 검토 체계와 점검표: docs/qa/README.md, docs/qa/checklists.md (검토 원문은 docs/reviews/)

## 역할 에이전트 (.claude/agents)
pm-lead, architect, data-engineer, sim-engine, strategy-researcher, risk-ops, ui-dev
작업이 특정 역할에 해당하면 해당 에이전트에 위임한다. 실전 주문 경로, RealTradingGuard, 블랙리스트, 장애 대응을 건드리는 변경은 반드시 risk-ops 검토를 거친다. 검토 맡기는 방법과 원문 보존은 프로젝트 지침 11장을 따른다.

## 빌드와 테스트
- 전체 빌드(컴파일, 테스트, 순수 모듈 금지 의존 검사, ArchUnit): `./gradlew build`
- 테스트만: `./gradlew test`, 모듈 하나: `./gradlew :autotrade-domain:test`
- 프로세스 4개(D-013): 실험실 `./gradlew :autotrade-app-lab:bootRun`, 실전 엔진·시세 게이트웨이·감시는 `./gradlew :autotrade-app-{engine,gateway,watchdog}:run`
- ArchUnit 규칙: `autotrade-architecture-test` (새 모듈은 이 모듈 의존에도 추가하고, 패키지 소유 모듈 표 `ModuleOwnershipTest.OWNERS`도 갱신. ADR-002 9장)
- 설정 폴더: `AUTOTRADE_CONFIG_DIR`(기본 `~/.autotrade/`), 일반 키는 `runtime-config.json`, 비밀값은 `secrets.json`
- DB 스키마: `autotrade-app-lab/src/main/resources/db/migration/V*.sql` (CI가 PostgreSQL 16에 적용하고 `scripts/ci/schema-smoke.sql` 실행)
- CI 경계 검사: gitleaks 비밀값 스캔, `scripts/ci/check-python-collectors.sh`
- 모듈 구조와 계약: docs/adr/ADR-001-module-structure.md, 설정 키: docs/config/config-keys.md
