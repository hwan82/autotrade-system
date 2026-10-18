# risk-ops 검토: 실전 엔진 기동 실패 로그 원인·표준 오류 한 줄 시험(PR #165 QA Q1·Q2·R1)
- 대상: PR #169 / 커밋 ba2897ed / `git diff origin/main...HEAD`(merge-base 7491fd84, PR #165 머지 커밋)
- 바뀐 파일: `autotrade-app-engine/src/test/java/com/autotrade/app/engine/EngineMainTest.java`(기존 시험 2개에 단언 추가, +12/−1), `docs/decisions/D-107-engine-start-failure-kinds.md`(결정 3번 인용 문구 정정, 후속 반영 절 추가). 운영 코드 변경 없음. 보호 경로(`scripts/ci/protected-paths.txt:8` `autotrade-app-engine/*`, `.github/CODEOWNERS:5`)의 시험 변경이라 실전 관련 PR이다.
- 영향 받는 실전 경로 코드(직접 읽음): `EngineMain.launch`·`configPaths`·`StartFailure.of`·`report`(EngineMain.java:100~121, 273~301), `ControlServer` 잠금 실패 문구(autotrade-control/.../ControlServer.java:100).
- 점검표: A
- 직접 실행한 것:
  - 첫 시도는 스크래치 폴더의 clone에서 돌렸는데, 같은 폴더를 다른 빌드가 함께 써서 `:autotrade-app-experiment:test` XML 쓰기 충돌·`:autotrade-app-lab:test` `NoClassDefFoundError`(도중 clean)로 거짓 실패했다. 코드 실패가 아니다. 그래서 이름이 겹치지 않는 새 clone(`scratchpad/riskops-pr169-9867`, 헤드 ba2897ed)에서 `./gradlew build --no-build-cache` → **BUILD SUCCESSFUL**(138개 작업 모두 실행).
  - 결과 XML 집계: `:autotrade-risk:test` 55개, `:autotrade-architecture-test:test` 46개, `:autotrade-app-engine:test` 64개, 모두 실패·오류·건너뜀 0. 바뀐 시험 `EngineMainTest#진입점은_프로그램_오류만_로그에_예외_흐름을_남기고_설정_오류와_명령_채널_실패는_한_줄만_남긴다`, `#기동_실패_표준_오류_첫머리는_runbook이_안내하는_낱말로_시작한다` 통과.
  - 변이 5개를 운영 코드(EngineMain.java)에 넣고 `EngineMainTest`를 돌림 → 모두 잡힘: N5 프로그램 오류 로그에서 원인 뺌, N6 설정·채널 실패 로그에서 원인 뺌, N6c 채널 실패 원인을 빈 글자로, N9 표준 오류에 `e.toString()` 한 줄 더, N9b 표준 오류에 스택 출력.
  - 대조: 머지 기준(7491fd84)의 `EngineMainTest`로 되돌린 뒤 N5·N9를 넣으면 **둘 다 살아남음**. 이 PR이 실제로 빈틈을 메운다는 것을 확인. 변이·되돌림은 clone에서만 했고 원상 복구 뒤 시험 통과 확인.
  - `git merge-tree origin/main origin/claude/project-thread-ryxl0y` → 충돌 없음(브랜치가 main의 #166·#167 뒤에 있으나 겹치는 파일 없음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 운영 코드 변경 없음(diff는 시험 1파일·문서 1파일). 기동 실패 시 `launch`(EngineMain.java:100~109)는 null을 돌려 엔진이 뜨지 않고, Guard는 `startGuard`에서 모든 계좌 OFF로만 만들어진다. D-107 결정 5번(Guard) 문구 무변경. `EngineMainTest` 64개 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 변경 없음. `autotrade-architecture-test` 변경 없음, 46개 통과 |
| A3 | 해당 없음 | 증권사 선택·주문 라우팅 코드를 건드리지 않음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·보존 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 통과 | 프로세스 분리·정지 순서 코드 변경 없음. 두 번째 엔진이 명령 채널 잠금으로 뜨지 않음을 시험이 이제 원인 문구("다른 실전 엔진이 이미 실행 중입니다", ControlServer.java:100)까지 확인 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 경로 변경 없음 |
| A10 | 통과 | 새 픽스처는 `"C:\x"` 경로 예외와 "꾸리기 실패"뿐, 키·계좌번호·토큰 없음. 기존 단언 `getThrown()` null(설정 거부·채널 실패의 원인 예외를 로그에 싣지 않음)은 그대로 유지. 새 단언 `isEqualTo`가 로그 ERROR 줄 전체를 고정해 원인 외 다른 값이 덧붙는 변경도 잡음 |
| A11 | 통과 | 보호 시험 삭제·비활성화·약화 없음: 기존 단언은 모두 남기고 단언만 추가(`InvalidPathException`을 변수 `badPath`로 뺀 것은 같은 값). 고정 값(지문·허용 목록·소유 표) 변경 없음. 실패 경우(세 가지 기동 실패)를 직접 일으키고 위 변이 5개를 모두 잡음, 기준 시험에선 N5·N9가 살아남았음 |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 전제(기동 실패 시 엔진 안 뜸, 종료 코드 0이 아님, 재기동 시 OFF) 무변경. 종료 코드 1·2 판정(`StartFailure.of`, EngineMain.java:281~291) 무변경. runbook(docs/runbooks/README.md:64)이 기대는 "로그의 ERROR 줄"에 원인이 실린다는 점을 이제 시험이 고정 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-107 결정 3번 본문 인용(D-107:17)을 코드 상수 `PROGRAM_ERROR_HEADLINE`(EngineMain.java:278~279)과 같은 글자로 정정하고, 옛 문구는 후속 반영 R1(D-107:46)에 남김(PR #165 risk-ops 검토 [참고] 1번 해소). 새 후속 반영 절(D-107:50~)이 판정·종료 코드·Guard·운영 코드 무변경을 밝히며, 실제 diff와 일치 |

## 점검표 밖 발견
- [참고] D-107 새 절의 "남은 한계"(원인 메시지에 줄바꿈이 있으면 표준 오류가 여러 줄)는 사실이다(`report`가 `detail`을 그대로 출력, EngineMain.java:299). "지금 기동 단계 예외 메시지는 한 줄"은 시험의 세 예외(InvalidPathException, ControlServer 잠금 IOException, IllegalStateException)에서만 확인했고 모든 기동 경로를 다 보진 않았다 → [추론]. 표시 문제일 뿐 판정·종료 코드·Guard와 무관하므로 이 PR을 막지 않는다. 출력 동작을 바꿀 때(줄바꿈 접기)는 실전 관련 PR로 다시 검토.
- [참고] 채널 실패 단언은 `contains("다른 실전 엔진이 이미 실행 중입니다")`로 원인을 본다. 잠금이 아닌 다른 이유(소켓 경로 길이 등)로 채널이 실패하는 환경에선 시험이 실패할 수 있으나, ControlServer는 잠금을 소켓보다 먼저 잡으므로(ControlServer.java:87~101) Linux CI에서는 안정적이다. Windows 운영 PC에서 이 시험을 돌린 결과는 확인 못 했다(판정 항목 밖).
- [참고] 같은 스크래치 폴더를 검토자들이 함께 쓰면 빌드가 거짓 실패한다. 검토용 clone은 고유한 이름으로 만들 것을 권고.

## 판정
승인
