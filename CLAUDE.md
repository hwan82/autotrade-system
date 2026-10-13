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

## QA 연계 (독립 QA 폴더 C:\2.Code\myproject\autotrade-system-qa)
- `/qa-release [이전 REL]`: QA에 보낼 릴리스 패키지를 `qa-inbox\REL-번호\`에 만든다(build.md를 마지막에 씀).
- `/qa-feedback <QA폴더명>`: qa-out의 RPT·QST를 처리한다(QST는 `qa-inbox\ANS-번호_QA번호\`로 답, DEF는 수정·테스트·커밋 후 새 REL).
- qa-inbox에는 새 폴더만 만들고 기존 REL·ANS는 고치지 않는다. qa-out은 읽기만 한다. QA 폴더의 baseline·.claude는 건드리지 않는다.
- QA 회신은 확인할 주장으로 다루며, 실전 경로 수정이 필요한 결함은 자동 수정하지 않고 멈춘다.
- 작업 시작 때 `git pull`로 main을 받은 뒤, `docs/reviews/gates/*.md`와 `docs/reviews/pr-*/qa.md` 중 `docs/qa-out-status.md`에 처리 기록이 없는 QA 판정(새 파일 또는 새 `## 재검토 <날짜>`)이 있으면 사용자에게 먼저 알린다. 처리는 `/qa-feedback reviews`로 한다.

## 빌드와 테스트
- 전체 빌드(컴파일, 테스트, 순수 모듈 금지 의존 검사, ArchUnit): `./gradlew build`
- 테스트만: `./gradlew test`, 모듈 하나: `./gradlew :autotrade-domain:test`
- 프로세스 4개(D-013): 실험실 `./gradlew :autotrade-app-lab:bootRun`, 실전 엔진·시세 게이트웨이·감시는 `./gradlew :autotrade-app-{engine,gateway,watchdog}:run`
- ArchUnit 규칙: `autotrade-architecture-test` (settings에 include한 Java 모듈은 자동으로 이 모듈의 테스트 클래스패스에 들어온다. 새 모듈은 패키지 소유 모듈 표 `ModuleOwnershipTest.OWNERS`를 갱신해야 빌드가 통과한다. 운영 클래스는 그 모듈이 소유한 `com.autotrade` 패키지 안에만 두고, 같은 이름의 프로젝트를 두 개 두지 않는다. ADR-002 9장)
- 설정 폴더: `AUTOTRADE_CONFIG_DIR`(기본 `~/.autotrade/`), 일반 키는 `runtime-config.json`, 비밀값은 `secrets.json`
- DB 스키마: `autotrade-app-lab/src/main/resources/db/migration/V*.sql` (CI가 PostgreSQL 16에 적용하고 `scripts/ci/schema-smoke.sql` 실행)
- CI 경계 검사: gitleaks 비밀값 스캔, `scripts/ci/check-python-collectors.sh`
- 모듈 구조와 계약: docs/adr/ADR-001-module-structure.md, 설정 키: docs/config/config-keys.md
