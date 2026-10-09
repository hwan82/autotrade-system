# 검토 원문

역할 검토와 독립 QA 검토의 결과를 **원문 그대로** 보관하는 곳입니다. 메인 Claude는 이 파일을 고치거나 요약으로 바꾸지 않습니다.

## 위치
- PR 검토: `docs/reviews/pr-<PR번호>/<검토자>.md` (예: `pr-15/risk-ops.md`, `pr-15/architect.md`, `pr-15/qa.md`)
- PR 번호가 아직 없을 때: `docs/reviews/<브랜치이름>/<검토자>.md`로 쓰고 PR을 연 뒤 옮기지 않아도 된다(PR 본문에서 링크).
- 단계 완료 판정: `docs/reviews/gates/stage-<번호>.md`
- 실전 활성화 판정: `docs/reviews/gates/live-<계좌ID>-<날짜>.md`
- 재검토는 같은 파일 아래에 `## 재검토 <날짜>`로 덧붙인다. 앞의 내용은 지우지 않는다.

## 형식
```
# <검토자> 검토: <PR 제목 또는 관문>
- 대상: PR #<번호> / 커밋 <SHA> / main 대비 diff
- 점검표: A | B | C | D
- 직접 실행한 것: <명령과 결과 요약>

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | autotrade-risk/.../RealTradingGuard.java:42, RealTradingGuardTest#재기동_후_OFF 통과 |

## 점검표 밖 발견
- ...

## 판정
승인 | 수정 필요(항목) | 거부(이유)
```
