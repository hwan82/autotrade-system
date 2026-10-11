# architect 재검토: 일봉 리플레이와 조합 실험 실행 명령: 수집 스냅샷 → (가)(나) × 매매지표 실험 (D-061)
- 대상: PR #50 / 커밋 616ab14ac6f80a089bb11e28c70c6b1e497fbe15 (main de5a886 = PR #49 머지를 병합한 커밋, 원격 브랜치 최신과 같음을 fetch로 확인) / `git diff origin/main...HEAD` 전체(origin/main = de5a886, 19파일 +2077 −6). 1차 검토(architect.md, e02edd9) 이후 PR 자체 변경은 4dd15c7 한 커밋이고, 616ab14는 main 병합이다
- 병합 확인: `git diff origin/main HEAD`가 PR diff(19파일)와 같아 병합에서 PR 밖 변경이 생기지 않았다. 충돌 해결 결과 docs/decisions/README.md 결정 표는 D-060(65행, PR #49) → D-061(66행) 순으로 두 행이 모두 있고, 미확정 표에 Q-049(85행)가 있다. 병합으로 들어온 PR #49 코드는 autotrade-app-lab·autotrade-ui만 바꾸고 이 PR이 고친 autotrade-sim 파일과 겹치지 않는다
- 점검표: B
- 비고: docs/reviews/README.md는 재검토를 같은 파일에 `## 재검토`로 덧붙이라고 하지만, 이번에는 요청에 따라 별도 파일로 쓴다. 1차 원문 architect.md는 4dd15c7에 내 작성본 그대로 커밋됐다(작업 트리와 HEAD 차이 없음, 고쳐지지 않음)
- 직접 실행한 것:
  - 4dd15c7에서 `./gradlew build --rerun-tasks --no-daemon` → BUILD SUCCESSFUL in 2m 21s. `ArchitectureTest` 17건 통과, `CombinationMainTest` 2건 통과
  - 616ab14(병합 뒤)에서 다시 `./gradlew build --rerun-tasks --no-daemon` → BUILD SUCCESSFUL in 2m 7s. 테스트 결과 XML 합계 852건, 건너뜀 0, 실패·오류 0
  - `git diff e02edd9 4dd15c7`로 수정 커밋 전체를 읽음. PR #49 브랜치의 결정 로그 diff에 Q-049가 없어 번호 충돌이 없음을 확인
  - 커밋 메시지·PR 본문은 판정 근거로 쓰지 않음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 616ab14에서 `--rerun-tasks` 전체 빌드 성공, 852건 실패 0 |
| B2 | 통과 | 수정 커밋은 `autotrade-sim` 안의 `CombinationMain`과 테스트, 문서만 바꿈. 새 의존 없음. `java.util.regex.Pattern`만 추가. ArchitectureTest 통과 |
| B3 | 통과 | 계약 변경 없음. 실행 명령 인자 규칙(실험 ID 형식, 설정 폴더 순서)은 CombinationMain.java 클래스 주석에 반영. Q-049 등록(docs/decisions/README.md 미확정 표), ADR-004 4장 후속 표에 행 추가 |
| B4 | 통과 | 새 코드에 시계·난수 없음. `System.getProperty`/`getenv`는 실행 인자 결정에만 쓰고 결과에 영향 없음. 골든 파일 변경 없음, 결정론 테스트 그대로 통과 |
| B5 | 통과 | 수정 커밋에 금액 계산 없음. 1차 근거 그대로 |
| B6 | 통과 | `CombinationMainTest#실험_ID가_experiments_밖을_가리키면_실행하지_않는다`(`../x`, `..`, `a/b` → 종료 코드 1, 밖의 `x/experiment.yaml`이 있어도 실행 안 함), `#설정_폴더는_시스템_속성을_먼저_본다`(속성 복원 포함). 테스트 삭제·비활성화 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·리스크 한도·장애 대응 코드·보호 테스트 변경 없음. 자원 부족 정지 순서 문제는 Q-049(담당 risk-ops·sim-engine)로 넘겨졌고, 그 결정 때 risk-ops 검토가 필요하다 |
| B8 | 통과 | 수정 커밋에 비밀값 없음. 로그·오류 메시지에는 실험 ID와 경로만 나옴. 로컬에 gitleaks가 없어 gitleaks는 CI 결과에 맡김 |

## 1차 지적 처리 확인

| 지적 | 결과 | 근거 |
|---|---|---|
| F1(수정 필요) 정지 순서 공백 추적 | 해결 | ① Q-049가 미확정 표에 등록됨(대상 `runCombinationExperiment`·`runStrategyStudy`, 담당 risk-ops·sim-engine, 추천안·대안, 7단계 전 시한). ② D-061 "위험과 한계" 문장이 고쳐짐: 장 시간 보호만 벽시계 문제이고 정지 요청 파일 확인에는 벽시계가 필요 없다는 점, 범위 결정이 Q-049라는 점. ③ ADR-004 4장 후속 표에 Q-049로 연결한 행이 추가됨 |
| F2(권고) 설정 폴더 순서 | 해결 | CombinationMain.java `configDir()`: 시스템 속성 `autotrade.config-dir` → `AUTOTRADE_CONFIG_DIR` → `~/.autotrade`. `ConfigPaths`와 같은 순서. 테스트 있음 |
| F3(권고) 권리 변동 뒤 거래량 단위 | 문서로 처리 | D-061 한계에 추가: 지정가 체결 상한이나 VWAP를 쓰는 전략을 일봉에 올리기 전에 거래량도 같은 계수로 나눈다. 그때 `DAILY-PATH-V1` 버전을 올려야 한다는 점은 PATH_MODEL 주석("시각·순서·가격 기준을 바꾸면 올린다")이 거래량을 명시하지 않아 아래 R1로 남김 |
| F4(권고) 실험 ID 경로 | 해결 | 경로를 만들기 전에 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`로 검사(CombinationMain.java run 앞부분). 첫 글자가 `.`일 수 없고 `/`가 없어 `..`·`../x`·`a/b`가 모두 막힌다. `a..b` 같은 값은 한 폴더 이름이라 밖으로 나가지 못한다 |

## 점검표 밖 발견
- R1(권고, 머지 막지 않음): 나중에 거래량을 계수로 나누면 같은 스냅샷이라도 결과가 바뀌므로 `DAILY-PATH-V1` → V2로 올려야 한다. `DailyBarMarketDataSet.PATH_MODEL` 주석이나 D-061 한계 문장에 "거래량 변경도 버전을 올린다"를 넣어 두기를 권한다.
- 그 밖에 새 문제는 찾지 못했다. 1차에서 확인한 미래 정보 차단, 실행기 구간 넘김, 병렬 안전성, 수집기 보관 연동은 수정 커밋에서 바뀌지 않았다.

## 판정
승인
