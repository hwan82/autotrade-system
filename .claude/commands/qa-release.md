---
description: 독립 QA에 보낼 릴리스 패키지(REL)를 qa-inbox에 만든다. 예: /qa-release, /qa-release REL-003(이전 REL 지정)
argument-hint: [이전 REL 번호]
---

QA 수신함: `C:\2.Code\myproject\autotrade-system-qa\qa-inbox`
이전 REL: `$ARGUMENTS` (비어 있으면 "없음")

## 절차
1. `git status --porcelain`으로 커밋 안 된 변경을 확인합니다. 하나라도 있으면 아무것도 만들지 않고 "커밋 안 된 변경 있음"과 파일 목록만 출력하고 끝냅니다.
2. qa-inbox의 `REL-<번호>` 폴더 중 최대 번호 + 1로 새 폴더 `REL-<세 자리 번호>`를 만듭니다(없으면 `REL-001`). 이미 있는 REL 폴더는 열어 고치지 않습니다.
3. 아래 순서로 씁니다. **`build.md`는 반드시 마지막에 씁니다.** 감시 스크립트가 `build.md`가 생긴 것을 "패키지 완성" 신호로 봅니다.
   1. `spec\`: 이번 범위 기능의 요구 본문과 인수 기준(Given/When/Then)만. 기획서(`docs/spec/autotrade-spec.md`)와 결정 로그에서 뽑되, 구현 상태·결함·코드 근거·설계 대안은 넣지 않습니다. 기능 ID를 붙입니다.
   2. `README.md`: 릴리스 범위 요약(대상 기능 ID, 바뀐 점).
   3. `build.md`:
      - 커밋 해시(`git rev-parse HEAD`)
      - 대상 기능 ID 목록
      - 모의투자·백테스트 모드 기동 방법(실험실 등). 실전 엔진 기동 방법은 쓰지 않습니다.
      - 알려진 제한
      - 마지막 줄: `이전 REL: <$ARGUMENTS 값 또는 없음>`
4. 금지: 코드 원문, 계좌번호, API Key, 토큰, 비밀번호, 실제 설정 파일 내용. 설정 예시가 필요하면 자리표시자만 씁니다.
5. 출력은 만든 폴더 경로 한 줄만 합니다.
