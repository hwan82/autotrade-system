# risk-ops 검토: 알림 큐 시험 경합 고침(보내는 스레드가 첫 건에서 막힌 뒤 큐를 채운다)
- 대상: PR #154 / 브랜치 `claude/project-thread-oxpaek` / 커밋 069130b0dbbbd09a922ed99b9518fd0be08893e8 / `git diff origin/main...HEAD`(origin/main이 HEAD의 조상임을 확인, 커밋 1개)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL(138 tasks). 테스트 결과 XML 집계(시험 수/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 46/0/0/0, `:autotrade-app-engine` 55/0/0/0, `:autotrade-notification-jar` 86/0/0/0, `:autotrade-app-watchdog` 61/0/0/0. 대상 시험 `NotificationQueueTest#큐가_차서_회복_알림을_버려도_다시_멈춤은_억제하지_않는다` 통과(0.213s).
  - 경합 재현(커밋하지 않은 임시 시험 `ZzRaceTmpTest`, 실행 후 삭제, `git status` 깨끗함 확인): 코어 수×2개 바쁜 스레드로 CPU를 채운 상태에서 이 시험의 앞부분(감시 시작 → 엔진 멈춤 → 큐 64건 채움 → 회복 offer가 false인지)을 고치기 전 본문과 고친 본문으로 각 300회 반복. **고치기 전 4/300 실패, 고친 뒤 0/300 실패.** `LC_ALL=C.UTF-8 ./gradlew :autotrade-notification-jar:test --tests com.autotrade.notification.ZzRaceTmpTest -i`.

## 변경 요약(코드로 확인한 사실)
- [사실] 바뀐 파일은 `autotrade-notification-jar/src/test/java/com/autotrade/notification/NotificationQueueTest.java` 하나, +4줄. 운영 코드 변경 없음.
- [사실] 추가: `entered` 래치(213행), 채널 동작에서 `entered.countDown()`(216행), 첫 offer 뒤 `entered.await(5s)` 단언(224행). 기존 단언(222, 226, 232, 235, 236, 243, 244행)은 그대로다.
- [사실] 경합 원인: 보내는 스레드는 `queue.poll(200ms)`(NotificationQueue.java:203)로 한 건을 꺼내 `deliver`에서 막힌다. 고치기 전에는 시험 스레드가 64건을 채운 **뒤** 보내는 스레드가 첫 건을 꺼낼 수 있었고, 그러면 자리가 하나 생겨 회복(PROCESS_RECOVERED)이 `queue.offer` 성공으로 받아져 235행 단언이 깨졌다. 고친 뒤에는 보내는 스레드가 이미 첫 건을 꺼내 `release.await()`에서 막힌 것을 확인한 뒤 채우므로, 꺼내 갈 주체가 없어 232·235행이 결정적이다.
- [사실] 같은 패턴이 이미 옆 시험 `큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`(266~276행, PR #132 risk-ops R1)에 있다. 일관된 고침이다.
- [사실] 시험이 지키는 의미는 약해지지 않았다: 창 시작(T0, 222행) → 멈춤(T0+9분) → 새 창(T0+10분)에서 회복은 상한(INFO, windowCount 0)이 아니라 큐 가득(NotificationQueue.java:174~175)으로만 버려진다. 래치는 첫 offer와 시계 이동 사이에 들어가므로 창 시각에도 영향이 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 변경은 알림 큐 시험 4줄뿐, Guard 관련 파일 무변경(`git diff --stat origin/main...HEAD` 1 file). `:autotrade-risk` 55건, `:autotrade-app-engine` 55건 통과(Guard 시험 포함) |
| A2 | 통과 | 주문 경로·ArchUnit 규칙 무변경. `:autotrade-architecture-test` 46건 통과 |
| A3 | 통과 | 증권사 플러그인·주문 라우팅 무변경(diff에 없음) |
| A4 | 통과 | 타임아웃·조회 경로 무변경(diff에 없음). 엔진 시험 통과 |
| A5 | 통과 | 멱등 키 생성·저장 코드 무변경(diff에 없음) |
| A6 | 통과 | 블랙리스트·수동 보유 필터 무변경(diff에 없음). `:autotrade-risk` 통과 |
| A7 | 통과 | 계좌별 큐·토큰 버킷·킬스위치 무변경(diff에 없음) |
| A8 | 통과 | 운영 `NotificationQueue`(엔진 `EngineMain`·`NotifyingCommandAuditLog`, 감시 `WatchdogMain`·`Watchdog`·`Alerts`가 씀)는 무변경. 막지 않음·던지지 않음 성질(NotificationQueue.java:137~143, 174~176)은 그대로이고 `채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`·`채널이_Error를_던져도_넣는_쪽으로_번지지_않고_다음_알림은_보낸다` 통과 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 무변경(diff에 없음) |
| A10 | 통과 | 추가 4줄에 키·계좌번호·토큰 없음(diff 직접 확인). 커밋 메시지에도 없음 |
| A11 | 통과 | `autotrade-notification-jar/*`는 보호 경로(scripts/ci/protected-paths.txt). 시험을 지우거나 단언을 바꾸지 않았고 사전 조건(보내는 스레드가 첫 건을 쥐고 막힘)만 확정했다. 고정 값 갱신 없음. 임시 반복 시험으로 고치기 전 4/300 실패 → 고친 뒤 0/300을 직접 확인해 시험이 간헐 실패 대신 결정적으로 같은 성질을 검사함을 확인 |
| A12 | 통과 | 이 시험은 장애표 #8(엔진 크래시: 멈춤 긴급·회복 정보 알림, D-100, broker-and-fault-design.md:167)의 "회복 알림을 큐 가득으로 버려도 다시 멈춤은 억제하지 않는다"를 지킨다. 운영 동작 무변경이라 대응표 17종·O1~O8·장애 테스트 8종 전제 변화 없음 |
| A13 | 해당 없음 | 시장·시간대·통화 계산을 건드리지 않음(알림 큐 시험만) |
| A14 | 통과 | 동작 변경이 없어 새 결정 불필요. 시험 의도는 D-100과 PR #128 QA 탐침 P2 주석(212행)과 일치 |

## 점검표 밖 발견
1. [사실] 커밋 메시지와 시험 주석이 근거로 드는 "PR #151 risk-ops 점검표 밖 4번" 원문(`docs/reviews/pr-151/risk-ops.md`)은 origin/main과 이 브랜치에 없다. 이 검토는 그 원문에 기대지 않고 코드와 직접 재현으로 판정했다. 출처 추적을 위해 PR #151 검토 원문이 어디에 있는지(그 PR 브랜치에만 있는지) PR 본문에 링크해 두기를 권한다(판정에 영향 없음).
2. [추론] `entered.await` 단언이 실패하는 경우(보내는 스레드가 5초 안에 첫 건을 못 꺼냄)는 큐 시작 실패를 뜻하므로 시험 실패가 맞다. 남은 비결정 요소는 238~241행의 비우기 대기(5초 마감)뿐이며, 이는 이 PR 이전부터 있던 것이고 같은 패턴의 옆 시험과 같다.
3. [사실] 이 PR은 보호 경로 변경이라 프로젝트 지침 10·11장에 따라 사용자 머지와 머지 전 독립 QA 대상이다.

## 판정
승인
