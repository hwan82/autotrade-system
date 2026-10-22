# risk-ops 검토: D-136 후속 고침(PR #222 QA Q1~Q4·R2·R3, risk-ops 재검토 R1·R2)
- 대상: PR #225 / 커밋 `5b7fa65a` / `git diff origin/main...HEAD` 전체(커밋 `7505c958`, `5b7fa65a`, 18개 파일 +319 −39)
- 점검표: A
- 직접 실행한 것:
  - `5b7fa65a`를 별도 worktree(다른 검토자와 다른 경로)에 꺼내 `./gradlew build --no-daemon`: **EXIT 0**. 시험 결과 XML 합계 1,928건, 실패·오류·건너뜀 0(`autotrade-app-collector` 144, `autotrade-architecture-test` 46, `autotrade-risk` 55, `autotrade-app-engine` 68, `autotrade-app-gateway` 55, `autotrade-broker-kis` 30).
  - 변이 시험(같은 worktree, 하나씩 넣고 `:autotrade-app-collector:test` 뒤 되돌림, 끝에 `git status` 깨끗):
    - M1 `ApiCaller.getAnswered`에서 `|| answered.test(r)` 삭제 → 4건 실패(`ApiCallerTest#증권사가_결과_코드로_답한_5xx는_다시_부르지_않고_그대로_돌려준다`, `KisNewsClientTest#초당_한도_초과가_HTTP_500으로_와도_…`, `#토큰_만료가_HTTP_500으로_와도_…`, `#결과_코드_없는_5xx는_…`)
    - M2 `KisNewsClient.answered`를 항상 거짓 → 3건 실패
    - M3 "성공 코드인데 200 아님" 거절 삭제 → `#결과_코드_없는_5xx는_전처럼_두_번_다시_부르고_성공_코드인데_200이_아니면_믿지_않는다` 실패
    - M4 `CollectorMain.redaction`에서 `kisNewsAppSecret` 뺌 → `ApiCallerTest#수집기가_읽은_키는_모두_가린다` 실패
    - M5 시각 정규식만 `\d{1,6}`로 되돌림 → 생존(`LocalTime.parse`가 5자리·4자리를 거르므로 동치 변이). M5b 정규식 + 오른쪽 채우기(옛 동작) 함께 되돌림 → `#여섯_자리가_아니거나_없는_시각_날짜는_채우지_않고_거르고_센다` 실패
    - M6 `badRows++` 삭제 → 같은 시험 실패
    - M7 `ApiCaller.post`의 `beforeCall.run()` 삭제 → `ApiCallerTest#토큰_발급_POST도_보내기_전에_장_시간을_확인한다` 실패
  - `scripts/ci/check-collector-keys.sh`(저장소 전체): 종료 0. 새 패턴을 스크래치 파일로 확인: JSON `"app-key":"<38자>"`, 공백 있는 JSON, Java 이스케이프 `\"app-key\":\"…\"` 모두 잡음, 자리표시자 `<한국투자 앱키>`와 17자 값은 통과.
  - `scripts/ci/test-mark-protected-changes.sh`: "표시 스크립트 자체 시험 통과". `mark-protected-changes.sh main`: 보호 대상 5개(`.github/CODEOWNERS`, `ApiCaller.java`, `CollectorMain.java`, `KisNewsClient.java`, `protected-paths.txt`) 표시 → 실전 관련 PR로 분류됨.
  - `protected-paths.txt`와 `CODEOWNERS` 목록 대조: 차이는 기존 표기 방식(`dir/*` ↔ `dir/`)뿐, 새 두 줄(`CollectorSecrets.java`, `CollectorMain.java`)은 양쪽에 같다.
  - PR 머리 `5b7fa65a` CI(REST 조회): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 모두 success.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-app-engine`·`autotrade-config`·`autotrade-domain` 변경 없음(diff 파일 목록). `autotrade-risk` 55·`autotrade-app-engine` 68건 통과(worktree 빌드) |
| A2 | 통과 | 수집기 한국투자 주소는 여전히 `KisNewsClient` 상수 두 개(KisNewsClient.java 37~39행)와 `page` GET·`token` POST뿐. 새 `ApiCaller.getAnswered`(ApiCaller.java 55~80행)는 주소를 늘리지 않고 응답 해석만 바꿈. 보호 경로·CODEOWNERS에 `CollectorSecrets.java`·`CollectorMain.java` 추가(앞 PR #222 재검토 R1 반영), 표시 스크립트 확인. `ArchitectureTest` 수집기 규칙 그대로 46건 통과 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 수집기는 주문을 보내지 않음(계좌번호 칸 거부 `CollectorSecrets` 무변경) |
| A4 | 해당 없음 | 주문 재전송 로직 변경 없음. 참고로 조회 재시도는 줄어드는 방향: 본문에 `rt_cd`가 있으면 5xx·429라도 재시도하지 않음(M1·M2가 잡음) |
| A5 | 해당 없음 | 주문 생성 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 종목 경로 변경 없음, `autotrade-selection` 무변경, 뉴스 제목은 아직 지표에 연결 안 됨 |
| A7 | 통과 | 계좌별 직렬 큐·토큰 버킷 코드 변경 없음. 수집기 호출 간격·하루 상한은 그대로(`pause`, `budget.acquire` 매 시도 전, ApiCaller.java 59~61행). 초당 한도 초과(`EGW00201`)가 HTTP 500으로 와도 1번에 멈춤(시험·M1) → 같은 앱키를 다른 프로그램과 나눌 때 부하가 이전보다 줄어듦 |
| A8 | 통과 | 수집기는 장 시간 보호 구간 밖에서만 호출(GET은 매 시도 전 `beforeCall.run()`, POST도 같음 ApiCaller.java 88행, M7로 시험이 잡음). 실전 앱키 공유는 PR #222 사용자 결정(D-054 R1 수집기 예외, 점검표 D8) 그대로이고 이 PR이 넓히지 않음. D-054 쪽에서 그 예외로 가는 줄이 생김(D-054 34행) |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음(`autotrade-broker-*`, `autotrade-app-gateway` 무변경, 55건 통과) |
| A10 | 통과 | 새 오류 메시지는 결과 코드나 `(HTTP 상태)`만(KisNewsClient.java 189행), 상태 오류는 가린 URL만(ApiCaller.java 73행). `answered`는 본문을 읽기만 하고 남기지 않음. 가림 목록에 한국투자 앱키·시크릿 포함을 시험으로 고정(M4로 확인), 토큰 응답 본문을 메시지에 싣지 않는 시험 추가. 시험 키 값은 16~17자 가짜 값. CI 키 검사에 `app-key`·`app-secret` 패턴 추가, 저장소 전체 통과. CI gitleaks success |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·장애 테스트) 변경 없음. 시험 삭제·`@Disabled` 없음. 기존 시험 기대값 변경 두 곳은 동작 변경에 따른 것: `KisNewsClientTest` 시각 `"9301"`→`"093010"`(채우지 않음, Q2), `KisNewsCollectorTest` 23:59:59 호출 7→8번 + `000000` 커서 없음 단언 추가(Q4, 더 엄격해짐). 보호 경로 목록은 늘기만 함. 새 동작마다 실패 경우 시험이 있고 변이 6종을 잡음(위) |
| A12 | 통과 | 장애표 #14(한도 초과 시 재시도하지 않고 호출자 백오프)와 같은 방향으로 맞춰짐: 결과 코드가 있는 응답은 상태와 무관하게 재시도 없음. #17(실전 키 공유)은 PR #222 결정 그대로(D8). 장애 테스트 8종의 대상(실전 엔진·게이트웨이) 코드 변경 없음 |
| A13 | 해당 없음 | 미국 시장 경로 변경 없음. 수집기 시각은 서울 기준 그대로 |
| A14 | 통과 | D-136 "후속 고침" 절 1~7번이 코드와 일치함을 확인: 1 `ApiCaller.getAnswered`·`KisNewsClient.answered`/`getNews`, 2 `headline` 6자리·실제 값 검사·`badRows`(리포트·콘솔), 3 `walk`의 `isBefore(stopAt)`·`midnightCuts`·시작 커서 00:00:00 처리, 4 시험, 5 보호 경로 두 줄, 6 CI 패턴, 7 D-054 연결 줄. "추천안 채택(자율)" 표시는 실전 키 규칙을 완화하지 않는 고침이라 지침 8장 2·4번에 걸리지 않음. runbook collector.md 3.9·6번이 새 리포트 칸과 재시도 규칙을 설명 |

## 점검표 밖 발견
- R1 (참고, 판정 영향 없음) `KisRejected`의 `--interval-ms` 안내는 `EGW00201`에만 붙는다(KisNewsClient.java 60행). D-054 4번은 `EGW00215`도 초당 한도 초과 `[사실]`로 적는다. 재시도하지 않는 동작은 같으므로 계좌 위험은 없고, 다음 수집기 PR에서 안내 문구에 함께 넣으면 운영자가 덜 헷갈린다.
- R2 (참고) 새 CI 키 패턴은 키 이름(`app-key`·`app-secret`) 옆 값만 본다. Java 시험에서 `Secret.of("<36자>")`처럼 이름 없이 실제 앱키를 넣으면 이 검사로는 못 잡고 gitleaks에 기댄다. 지금 시험 값은 모두 짧은 가짜 값이라 문제없다.
- R3 (참고) M5(정규식만 되돌림)는 `LocalTime.parse`가 같은 행을 거르는 동치 변이라 생존이 정상이다. 옛 동작(채우기) 전체 복귀는 M5b로 잡힌다.
- 앞 PR #222 재검토 R1(보호 경로 틈 `CollectorSecrets`)·R2(D-054 연결) 해결 확인. 상수 검사(ArchUnit) 생략 이유는 D-136 후속 5번에 적혀 있고, 한국투자 실전 연결 PR에서 다시 본다는 조건에 동의한다.

## 판정
승인
