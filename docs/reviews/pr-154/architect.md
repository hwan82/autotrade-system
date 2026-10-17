# architect 검토: 알림 큐 시험 경합 고침(보내는 스레드가 첫 건에서 막힌 뒤 큐를 채운다)
- 대상: PR #154 / 브랜치 claude/project-thread-oxpaek / 커밋 069130b / main 대비 diff(`git diff origin/main...HEAD`, origin/main이 HEAD의 조상임을 확인)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon -q` → 종료 코드 0(컴파일, 전체 테스트, 순수 모듈 금지 의존 검사, ArchUnit 포함)
  - `LC_ALL=C.UTF-8 ./gradlew :autotrade-notification-jar:test --tests 'com.autotrade.notification.NotificationQueueTest' --rerun` 15회 반복 → 15회 통과, 0회 실패. 마지막 결과 XML `tests="17" skipped="0" failures="0" errors="0"`

## diff 범위
바뀐 파일은 하나다: `autotrade-notification-jar/src/test/java/com/autotrade/notification/NotificationQueueTest.java` (+4줄, 삭제 0). 운영 코드 변경 없음.
`큐가_차서_회복_알림을_버려도_다시_멈춤은_억제하지_않는다`에 `entered` 래치를 넣어, 보내는 채널이 첫 알림(감시 시작)에서 막힌 것을 확인(`entered.await(5s)`가 참)한 뒤에 큐를 채운다. 기존 단언은 하나도 바뀌거나 빠지지 않았고 단언이 하나 늘었다.

## 경합 분석(코드로 확인)
- [사실] 보내는 스레드는 `queue.poll(200ms)`로 한 건을 꺼낸 뒤 `deliver(n)`에서 채널을 부른다(NotificationQueue.java:199-213). 큐는 `ArrayBlockingQueue(CAPACITY=64)`(NotificationQueue.java:44, 61)이고, 가득 차면 `queue.offer`가 거짓이라 `drop`으로 간다(NotificationQueue.java:174-176).
- [사실] 고치기 전에는 첫 `offer` 뒤 곧바로 채우기 반복문이 `pending() == CAPACITY`까지 돈다. 보내는 스레드가 반복문이 끝난 **뒤에** 첫 건을 꺼내면 자리가 하나 생겨 `엔진 회복` offer가 참이 되어 `isFalse()` 단언이 깨질 수 있다. 커밋 설명의 원인과 일치한다.
- [사실] 고친 뒤에는 보내는 스레드가 첫 건을 이미 꺼내 `release.await()`에서 막혀 있으므로, 채운 64건은 `release.countDown()` 전까지 줄지 않는다. 같은 파일의 `큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`(PR #132 risk-ops R1)와 같은 방식이다.
- [사실] 시계 순서는 바뀌지 않았다. 래치 대기는 `clock.now = T0+9분` 설정 전이고 StepClock은 손으로만 움직이므로(NotificationQueueTest.java:35-51) 래치 대기 시간이 시험의 시각 조건(T0 창, T0+10분 새 창)에 영향을 주지 않는다.
- [추론] 첫 건이 이제 확실히 큐 밖에 있으므로 큐의 64건은 `엔진 멈춤` 1건 + 명령 63건이다. 채우기 반복문은 `pending()`으로 멈추므로 건수 가정이 따로 없어 영향 없다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build --no-daemon -q` 직접 실행, 종료 코드 0. 해당 시험 클래스 `--rerun` 15회 반복 모두 통과(17개 시험, 실패·오류·건너뜀 0) |
| B2 | 통과 | 시험 파일 하나만 바뀌었고 새 의존(import)이 없다(`CountDownLatch`, `TimeUnit`은 이미 import, NotificationQueueTest.java:15-16). ArchUnit을 포함한 build 통과. 규칙 변경 없음 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 운영 코드 변경 없음 |
| B4 | 통과 | 시뮬레이션 코드 미변경. 시험은 주입된 StepClock을 쓰고 래치는 시각에 영향을 주지 않는다. 고친 결과 이 시험의 스레드 순서 비결정성이 줄었다. build의 골든 테스트 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작 없음(시험 안정화). 시험을 건너뛰거나 지우지 않았고 기존 단언을 모두 유지했다(diff는 추가 4줄뿐). `@Disabled`·`assumeTrue` 등 추가 없음 |
| B7 | risk-ops 검토 필요 | 바뀐 파일이 `autotrade-notification-jar/*` 아래이고 이 경로는 `scripts/ci/protected-paths.txt`의 보호 대상(실전 경로 런타임 모듈)이다. 프로젝트 지침 10·11장에 따라 실전 관련 PR로 취급: risk-ops 검토, 사용자 머지, 머지 전 독립 QA. 실전 주문 경로·RealTradingGuard·블랙리스트·보호 테스트(아키텍처 시험, Guard 시험, 장애 시험)는 diff에 없다. 이 글을 쓰는 시점에 `docs/reviews/pr-154/risk-ops.md`는 브랜치에 없다 |
| B8 | 통과 | diff와 커밋 메시지에 API Key·계좌번호·토큰 없음. 시험 문자열은 "감시 시작", "엔진 멈춤" 등 고정 문구뿐 |

## 점검표 밖 발견
1. [사실] 커밋 메시지와 시험 주석이 근거로 든 "PR #151 risk-ops 점검표 밖 4번"의 원문 `docs/reviews/pr-151/risk-ops.md`가 origin/main에도 이 브랜치에도 없다(`git ls-tree origin/main docs/reviews/pr-151` 결과 없음). 추적 근거를 저장소에서 확인할 수 없다. 이 PR의 판정에는 영향이 없다(경합은 위에서 코드로 직접 확인). PR 본문에 그 원문의 위치(다른 브랜치 등)를 링크하길 권한다.
2. [추론] 고치기 전 코드로 간헐 실패를 이 환경에서 재현해 보지는 않았다. 원인 분석은 코드 읽기로만 확인했다.
3. [사실] 이 파일에서 큐를 `CAPACITY`까지 채운 뒤 정확한 건수를 단언하는 두 시험(NotificationQueueTest.java:211-249, 263-)이 이제 모두 `entered` 래치를 쓴다. `채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`(115-)는 래치가 없지만 받은 건수를 `isBetween(CAPACITY, CAPACITY + 1)`로 경합을 허용해 단언하므로 같은 문제가 없다.

## 판정
승인 (B7: 보호 경로 변경이므로 risk-ops 검토, 사용자 머지, 독립 QA가 별도로 필요)
