# QA 검토: 알림 큐 시험 간헐 실패 고침(PR #151 risk-ops 점검표 밖 4번)
- 대상: PR #154 / 브랜치 `claude/project-thread-oxpaek` / 헤드 d8fb64c / `git diff origin/main...HEAD`(origin/main b17c8ff가 merge-base와 같음 확인)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL(138 tasks 모두 실행). Maven Central 429로 6회 실패 뒤 20초 간격 재시도 7회째 통과(의존성 받기 실패, 코드 실패 아님). 테스트 결과 XML 합계 1,532건, 실패·오류·건너뜀 0. 대상 시험 `NotificationQueueTest#큐가_차서_회복_알림을_버려도_다시_멈춤은_억제하지_않는다` 통과(0.214s), 클래스 17건 통과.
  - 임시 탐침 `ZzQaRaceProbeTest`(커밋하지 않음, 실행 뒤 삭제, `git status` 깨끗함 확인), `LC_ALL=C.UTF-8 ./gradlew :autotrade-notification-jar:test --tests ...ZzQaRaceProbeTest --rerun -i`:
    - CPU 4코어에 바쁜 스레드 8개를 돌린 상태에서 고치기 전 본문과 고친 본문을 번갈아 300회씩. 1차: 고치기 전 0/300 실패, 고친 뒤 0/300. 2차: **고치기 전 2/300 실패(회복 offer가 true), 고친 뒤 0/300.**
    - 결정적 재현: 큐를 `start()` 전에 64건까지 채우고 `start()` 뒤 400ms 기다린 다음 회복 offer → `true`(고치기 전 시험의 단언이 깨지는 상태). PR 본문의 원인 주장과 일치.
  - 운영 코드 변이(`NotificationQueue.java`, 실행 뒤 원복)로 고친 시험 하나만 돌림: M1 `drop`이 대상의 마지막 종류를 남기지 않음 → 잡힘(243행 다시 멈춤 offer가 false). M3 큐 가득에도 true 반환 → 잡힘. M4 전체 상한을 긴급에도 적용 → 잡힘. M5 `droppedTotal` 안 셈 → 잡힘. M2 `drop`이 시각을 남김 → 이 시험은 생존(종류가 회복→멈춤으로 바뀌어 억제 조건이 안 걸림), 클래스 전체로는 `큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`가 잡음.
  - PR #151 브랜치(`claude/project-thread-4l8sa4`)의 `docs/reviews/pr-151/risk-ops.md` 37행 「점검표 밖」 4번 원문 확인: 같은 시험 1회 실패·재실행 2회 통과, 원인은 `[추론]` 경합.
  - CI(헤드 d8fb64c): Gradle build, Secret scan and collector boundary, Lab screens, DB schema 4개 success(GitHub check runs).

## diff 범위
- [사실] 4개 파일: `NotificationQueueTest.java`(+4), `docs/qa/pending.md`(+1), `docs/reviews/pr-154/architect.md`, `risk-ops.md`(신규). 운영 코드·빌드 설정·다른 시험 무변경.
- [사실] 시험 변경: `entered` 래치(213행), 채널 동작에서 `entered.countDown()`(216행), 첫 offer 뒤 `assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()`(224행). 기존 단언(222, 226, 231, 235, 236, 243, 244행) 그대로, 삭제 0줄. `@Disabled`·`assume*` 없음.
- [사실] 보호 테스트 판단: `autotrade-notification-jar/*`는 `scripts/ci/protected-paths.txt`의 보호 경로다. `autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험은 diff에 없다. 고정 값(지문·허용 목록·소유 표) 갱신 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 관련 운영·시험 파일 diff 없음. build의 `:autotrade-risk`, `:autotrade-app-engine` 시험 통과(전체 1,532건 실패 0) |
| A2 | 통과 | 주문 경로·ArchUnit 규칙 diff 없음. `:autotrade-architecture-test` 통과(build) |
| A3 | 통과 | 증권사 주문 라우팅 diff 없음 |
| A4 | 통과 | 타임아웃·조회 경로 diff 없음 |
| A5 | 통과 | 멱등 키 코드 diff 없음 |
| A6 | 통과 | 블랙리스트·수동 보유 필터 diff 없음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷·킬스위치 diff 없음 |
| A8 | 통과 | 엔진·감시가 쓰는 `NotificationQueue` 운영 코드 무변경. 막지 않음·던지지 않음 시험(`채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`, `채널이_Error를_던져도_…`) build에서 통과 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 diff 없음 |
| A10 | 통과 | 추가 4줄·문서·커밋 메시지에 키·계좌번호·토큰 없음(diff 직접 확인). CI Secret scan success |
| A11 | 통과 | 보호 경로 시험이지만 단언을 지우거나 바꾸지 않고 사전 조건(보내는 스레드가 첫 건을 쥐고 막힘)만 확정, 단언 1개 추가. 부하 아래 고치기 전 2/300 실패 → 고친 뒤 0/300, 결정적 재현으로 원인 확인. 변이 M1·M3·M4·M5를 이 시험이 잡아 검사 의미가 유지됨을 확인. 고정 값 갱신 없음 |
| A12 | 통과 | 운영 동작 무변경. 이 시험이 지키는 "회복을 큐 가득으로 버려도 다시 멈춤은 억제하지 않음"(D-100, 장애표 엔진 크래시 알림 전제)을 그대로 검사(M1로 확인) |
| A13 | 해당 없음 | 시장·시간대·통화 계산 무관(알림 큐 시험만) |
| A14 | 통과 | 동작 변경 없어 새 결정 불필요. 시험 주석의 근거(PR #151 risk-ops 점검표 밖 4번)가 해당 원문(PR #151 브랜치 risk-ops.md 37행)과 일치 |
| B1 | 통과 | 검토자가 `./gradlew build --rerun-tasks` 직접 실행, BUILD SUCCESSFUL(429 재시도 뒤), 1,532건 실패 0 |
| B6 | 통과 | 새 운영 동작 없음(시험 안정화). 시험을 건너뛰거나 지우지 않았고 기존 단언 유지. 고친 시험이 운영 코드 변이 4개를 잡음 |

## 점검표 밖 발견
위험 시나리오(잘못된 주문·중복 주문·보호 공백을 만들 수 있는가):
1. **고친 시험이 운영 결함을 가릴 수 있는가** → 아니다. 래치는 시험의 사전 조건만 고정한다. 운영에서 보내는 스레드가 한 건을 꺼내 자리가 생겼을 때 회복을 받는 것은 올바른 동작이고, 시험이 지키는 성질(버린 회복 뒤 다시 멈춤을 억제하지 않음)은 M1 변이로 여전히 잡힌다. 운영 코드 무변경이라 주문 경로 영향 없음.
2. **보내는 스레드가 첫 알림을 sink로 보내지 않게 바뀌면(채널 순서·등급 매핑 변경)** → `entered.await(5s)` 단언이 5초 뒤 명확히 실패한다. 멈추지 않고 실패로 드러나므로 보호 공백 아님. [추론] 그때는 이 시험 의도와 다른 원인으로 실패하므로 원인 메시지를 보고 고쳐야 한다.
3. **남은 비결정 요소** → 238~241행 비우기 대기(5초 마감, `pending() > 0`). 마감 전 큐가 다 안 비면 243행 다시 멈춤이 큐 가득으로 버려져 실패할 수 있다. 이 PR 전부터 있던 것이고, 부하 아래 600회(1·2차 합) 고친 본문에서 0회 실패. 판정을 막지 않음.

그 밖:
- Q1(문서, 판정 무관) PR 본문 Before 문단의 "PR #151 머지 뒤 main에 들어옴"은 검토 시점 사실과 다르다: `origin/main`(b17c8ff)에 `docs/reviews/pr-151/`가 없다(PR #151 미머지). 링크된 PR #151 브랜치에는 원문이 있다.
- Q2(참고) 변이 M2(`drop`이 시각을 남김)는 이 시험만으로는 살아남는다. 같은 클래스의 다른 시험이 잡으므로 공백은 아니다.

## 판정
승인
