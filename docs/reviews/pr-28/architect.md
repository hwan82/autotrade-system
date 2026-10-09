# architect 검토: Q-020 결정 D-039 토스 시세 조회 무료 확인, 기본 우선순위를 키움·토스·한국투자로 복원
- 대상: PR #28 / 커밋 `1a0ee03` / main(`307ca00`) 대비 diff (`git diff origin/main...HEAD`, 10개 파일 +47 −19)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → Maven Central 429로 의존성 해석 실패(`jackson-bom:2.21.4`). 지시대로 `-I .../scratchpad/mirror.gradle`를 붙여 다시 실행 → `BUILD SUCCESSFUL`.
  - 앞 실행이 대부분 UP-TO-DATE라 `./gradlew build --rerun-tasks -I .../mirror.gradle`로 모든 작업을 다시 실행 → `BUILD SUCCESSFUL in 1m 30s`, 테스트 결과 XML 합계 521개, 실패 0, 오류 0, 건너뜀 0.
  - `ConfigLoaderTest` 결과에 `사용자가_토스를_빼면_적은_순서대로_쓴다()`와 `파일이_없으면_내장_default로_기동한다()` 포함·통과 확인.
  - 사용자 결정 출처 확인: 프로젝트 스레드 `cmsg_01Du6VLttqgn6f15sF8UYTuUEYGT6HDyvtbtii8CkYt2eX`에서 BAE(`user_01Vc7USeKzFq1SwmNQt6Je3E`)의 2026-10-09T11:26:26Z "시세 호출 조회 api는 무료입니다. 매매 체결시에만 수수료가 발생합니다.", 11:27:01Z "단 초당 무한정 api를 호출할수는 없습니다. 증권사마다 제한이 모두 다릅니다." 원문을 읽었다. D-039:4의 인용·시각과 일치한다.
  - 저장소 파일은 바꾸지 않았다(이 검토 파일만 새로 씀, `git status` 깨끗함 확인).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `--rerun-tasks` 전체 빌드 성공, 521 테스트 실패 0. 순수 모듈 금지 의존 검사·ArchUnit(`autotrade-architecture-test`) 포함 |
| B2 | 해당 없음 | 코드 변경은 기본값 JSON 한 줄(`autotrade-default.json:3`), 테스트, `GatewayMain.java:33` 주석뿐. import·모듈 의존·ArchUnit 규칙 변경 없음 |
| B3 | 실패 | 설정 키 `market-data.priority` 기본값 변경. 결정 D-039, `config-keys.md:15`, 기획서 MD-02(`autotrade-spec.md:110`), `broker-and-fault-design.md:68` 예시, ADR-003:17 주석은 갱신됨. 그러나 같은 ADR-003 안의 2.1 "토스 폴링 위치"(`ADR-003-market-data.md:45`)와 대안표(`ADR-003-market-data.md:132`)는 여전히 "연결 PR이 그 이전 처리와 테스트, 요금 확인(Q-020)과 함께 다시 넣는다", "D-033 이전 처리와 함께"라고 적어, D-039 "이전 처리는 더는 필요 없다"(D-039:23)·ADR-003:17 새 주석과 모순된다. 토스 연결 PR 작성자가 따를 계약 문서가 두 갈래다(아래 N1) |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 테스트 변경 없음. 골든 테스트는 전체 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작(기본 우선순위에 TOSS 포함)을 `ConfigLoaderTest.java:42` `containsExactly(KIWOOM, TOSS, KIS)`로 고정. 지운 단언 `doesNotContain(TOSS)`와 바꾼 테스트 `사용자가_토스를_넣으면…`→`사용자가_토스를_빼면_적은_순서대로_쓴다`(`ConfigLoaderTest.java:79-88`)는 결정이 바뀐 데 따른 기대값 교체이고, "사용자가 적은 목록 그대로 쓴다"는 경로 검사는 유지된다. 건너뜀 0. 보호 테스트(`ArchitectureTest`, Guard·GuardedOrderGateway, 장애 테스트) 변경 없음 |
| B7 | 해당 없음 | 실전 엔진·RealTradingGuard·주문 경로·블랙리스트·리스크 한도·장애 대응 코드 변경 없음. `market-data.priority`를 읽는 운영 코드는 게이트웨이(`GatewayMain.java:88`)와 실험실 설정 화면 표시(`SettingsController.java:276`)뿐이다. `market-data.toss-polling-scope`(실전 엔진 보호 폴링)는 바뀌지 않음 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 사용자 식별은 표시 이름과 GitHub 계정명뿐 |

## 점검표 밖 발견
- **N1 (수정 필요, B3)** `docs/adr/ADR-003-market-data.md:45`와 `:132`를 D-039에 맞춘다. 17행처럼 "(2026-10-09 D-039: 이전 처리·요금 확인 조건은 없어지고 연결 PR은 호출 한도 준수만 지킨다)"를 덧붙이면 충분하다. 연결 PR 조건에서 빠지면 안 되는 것은 남긴다: 토스 토큰 1개 제약으로 게이트웨이가 실전 계좌 증권사를 쓰지 않는 규칙(`GatewayMain.java:86-92`), GCRA 간격·429 `Retry-After`.
- **N2 (권장)** `docs/research/market-data-limits.md:65`의 "토스 국내 실시간 요금도 Q-020에서 미확인이라 같은 문제가 이어진다"가 Q-020 종료 뒤에도 남았다. 사용자 확인은 국내·미국을 가리지 않은 "시세 호출 조회 api" 전체여서 이 문장도 고칠 대상이다. 다만 데이터 엔지니어 문서라 그 역할의 변경으로 처리해도 된다.
- **N3 (권장)** D-039 "영향과 남은 위험"은 이전 기본값 `["KIWOOM","KIS","TOSS"]` 파일만 다룬다. 반대 경우도 있다: D-033 기간에 UI에서 설정을 한 번이라도 저장한 사용자는 `RuntimeConfigStore.save`가 설정 전체를 쓰므로(`RuntimeConfigStore.java:37-42`, `RuntimeConfigHolder.java:48`) 파일에 `["KIWOOM","KIS"]`가 남고, 기본값이 바뀌어도 토스가 켜지지 않는다. 과금이나 안전 문제는 아니고 용량만 D-033 수준에 머문다. D-039에 한 줄로 적고 설정 화면에서 기본값과 다름을 보이는 것으로 충분하다(`SettingsController.java:276`이 이미 현재값과 기본값을 함께 내려줌).
- **N4 (권장)** D-039:22 "게이트웨이는 아직 토스 실시간을 구현하지 않아 목록에 있어도 토스를 건너뛴다"는 `GatewayMain.java:125` `case TOSS -> Optional.empty()`로 사실이지만, 이것을 고정하는 테스트가 없다(`GatewayMainTest`에는 실전 계좌 증권사 제외 테스트만 있음). 이제 기본 경로에 TOSS가 들어가므로 "기본 우선순위 + 토스 키 설정 → 게이트웨이 플러그인은 키움·한국투자만" 테스트를 토스 연결 PR 전에 넣어 두면, 연결 PR이 이 동작을 바꿀 때 테스트가 함께 바뀌어 검토에 드러난다. PR #10 QA R-3이 같은 공백을 지적했다.
- 참고(문제 아님):
  - 기획서 v14.4 건너뜀은 `origin/claude/project-thread-89zdzi`가 v14.4를 쓰고 있어 사실이다.
  - D-039는 D-033 결정 3(`D-033-unpriced-source-default-off.md:15`, "무료로 확인되면 새 번호로 되돌려 기본 목록에 다시 넣는다")이 미리 정한 절차를 따른다. 사용자는 "무료"라는 사실을 알려 줬고 "기본 목록에 넣으라"고 명시하지는 않았지만, 미리 정한 되돌림 조건이 충족된 것이라 결정 성격(사용자 결정)과 기록 방식은 맞다. D-039:4의 "PR #21 작업 스레드"라는 표현은 스레드 제목이 "QA 보고서 후속 조치"라서 찾기 어렵다. 스레드 ID를 적어 두면 좋다(권장).
  - 결정 로그 규칙(README 3행 "기존 항목을 고치지 않는다")대로 D-033·D-028 행은 고치지 않았다. 맞다.
  - AUTO 모드의 SHARDED/FAILOVER 판단은 만들어진 플러그인 중 쓸 수 있는 소스 수 기준이라(`GatewayMain.java:99`, `SourcePlanner.java:59`) TOSS가 건너뛰어지는 지금은 동작 변화가 없다.

## 판정
수정 필요(N1: ADR-003 45·132행이 D-039와 모순, B3)
