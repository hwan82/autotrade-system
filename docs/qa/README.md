# 검토(QA) 체계

실전 돈이 걸린 시스템이라 검토를 세 겹으로 둡니다. 아래로 갈수록 독립성이 높고 비용(사람 손)도 큽니다.

| 층 | 누가 | 언제 | 무엇을 막나 |
|---|---|---|---|
| 1. 기계 검사 | CI | 모든 PR | 모델이 같은 곳을 놓치는 문제. 사람·모델 판단과 무관하게 빌드를 실패시킨다 |
| 2. 역할 검토 | 개발 프로젝트의 risk-ops·architect 서브에이전트 | 모든 코드 PR, 실전 관련 PR | 메인이 범위를 좁히거나 결과를 약하게 전달하는 문제(점검표 고정, 원문 보존, 불신 원칙) |
| 3. 독립 QA | 별도 Claude 프로젝트 "자동매매 QA" | 관문에서만 | 같은 프로젝트의 지침·메모리·작업 지시를 공유해 생기는 문제. 사용자가 직접 요청하고 직접 결과를 받는다 |

## 1. 기계 검사 (CI)
- 모듈 경계와 안전 규칙: `autotrade-architecture-test`의 `ArchitectureTest` (순수 모듈 프레임워크 금지, 게이트웨이는 주문 포트를 모름, 실전 주문 출구는 Guard 팩토리만, 금액에 실수형 금지 등).
- 실전 가드: `autotrade-risk`의 `RealTradingGuardTest`, `GuardedOrderGatewayTest` (기본 OFF, 재기동 시 OFF 포함).
- 장애 테스트 8종(`docs/team/broker-and-fault-design.md` 7장): 7단계에서 risk-ops가 자동화해 CI에 넣는다. 자동화가 불가능한 항목은 수동 절차서와 결과 기록으로 대신한다.
- 위 테스트 파일과 규칙(이하 **보호 테스트**)을 지우거나 약화하는 변경은 실전 관련 PR로 취급한다.

## 2. 역할 검토
- 점검표: [checklists.md](checklists.md)의 A(실전 경로), B(일반 코드).
- 검토 범위, 점검표, 원문 저장 규칙은 `.claude/agents/risk-ops.md`, `.claude/agents/architect.md`에 고정되어 있어 메인 Claude가 좁힐 수 없다.
- 원문은 `docs/reviews/pr-<번호>/<검토자>.md`에 남고 PR 본문에 링크된다. 형식은 [../reviews/README.md](../reviews/README.md).

## 3. 독립 QA 프로젝트
- 설정 방법과 붙여 넣을 지침: [qa-project-instructions.md](qa-project-instructions.md).
- 쓰는 관문:
  1. 실전 관련 PR 머지 전 (점검표 A)
  2. 로드맵 각 단계 완료 판정 (점검표 C)
  3. 7단계 실전 활성화 결정 (점검표 D)
- 평소 PR에는 쓰지 않는다. 관문이 아닌데 불안한 PR이 있으면 사용자가 판단해 요청한다.
