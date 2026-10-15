# architect 검토: 모니터 수집원 실패 WARN을 상태 변화 때만, 종료 시 송신 거절 경고 제거(MON-05 후속)
- 대상: PR #119 / 브랜치 claude/project-thread-5u4jv5 / 커밋 17dd9ac / `git diff origin/main...HEAD` 전체(4파일, +157 −6)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL. 대부분 UP-TO-DATE(캐시)라 아래로 다시 확인.
  - `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL**(EXIT 0). 결과 XML 집계: 테스트 1256건, 실패·오류·건너뜀 0. `MonitorAgentTest` 9건, `MonitorServiceTest` 8건 포함.
  - 변이 시험 1(MonitorAgent): 같은 실패 반복 로그를 DEBUG → WARNING으로 바꾸면 `MonitorAgentTest` 23건 중 1건 실패(`같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다`). 새 시험이 퇴행을 잡는다. 원복 확인.
  - 변이 시험 2(MonitorService): 이 PR의 운영 코드 변경 세 가지(`push`의 `closing` 검사, `RejectedExecutionException` catch, `close()` 순서 변경)를 모두 main 상태로 되돌리고(시험용 가시성만 유지) `MonitorServiceTest`를 돌림 → **8건 모두 통과**(새 시험 `종료와_겹쳐_도착한_표본은_예외_없이_버린다` 포함, 결과 XML 11:22:54Z 확인). 원복 확인.
  - JDK 동작 확인: `ThreadPoolExecutor(…, DiscardOldestPolicy)`를 `shutdownNow()` 한 뒤 `execute()` → 예외 없이 조용히 버림(`DiscardOldestPolicy.rejectedExecution`은 `isShutdown()`이면 아무것도 하지 않는다). 즉 이 executor는 `RejectedExecutionException`을 던지지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --rerun-tasks` 실행, BUILD SUCCESSFUL, 1256건 실패 0 |
| B2 | 통과 | 순수 모듈 변경 없음. `autotrade-monitor-agent-jar` 새 타입은 `java.util.HashMap`·`Map`과 private record·class뿐(MonitorAgent.java:8-10, :212-222). `RealPathReflectionTest`·`ArchitectureTest`·`ModuleOwnershipTest` 변경 없이 통과. 새 클래스 없음(중첩 타입만), 소유 패키지 그대로 |
| B3 | 해당 없음 | 공개 인터페이스·스키마·설정 키·REST 변경 없음. `MonitorService.push`·`sender`가 private → 패키지 공개로 바뀌었으나(MonitorService.java:59, :134) 시험용 가시성이며 계약 아님. 로그 수준 변경은 계약 아님 |
| B4 | 통과 | 새 시각 호출 없음(시각은 기존 주입 `Clock`, MonitorAgent.java:91). 난수 없음. `failing` 맵은 조회·갱신만 하고 순회하지 않아 순서 영향 없음(MonitorAgent.java:44, :188-210). 로그 출력 순서는 `sources` 목록 순서 그대로(:68-87). 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 실패 | MonitorAgent 쪽은 새 동작(처음 실패 WARN, 반복 DEBUG, 종류 바뀜 WARN, 회복 INFO)을 수준·문구·건수까지 단언하고 변이 시험으로 퇴행을 잡는다(MonitorAgentTest#같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다). **그러나 MonitorService 쪽 새 시험은 고친 코드를 하나도 검증하지 못한다**: 운영 변경을 모두 되돌려도 통과(변이 시험 2). 아래 A1. 지우거나 건너뛴 시험은 없음 |
| B7 | risk-ops 검토 필요 | `autotrade-monitor-agent-jar/*`는 보호 대상 경로다(scripts/ci/protected-paths.txt "실전 경로 런타임 모듈(실전 엔진·감시…)"). `MonitorAgent`는 감시 프로세스 런타임이 직접 쓴다(autotrade-app-watchdog/.../Watchdog.java:54, 장애표 #10 자원 감시·정지 요청 파일 경로). 지침 10장에 따라 risk-ops 검토 후 사용자 머지 대상이고, 11장에 따라 머지 전 독립 QA 관문이다. 이 브랜치에 `docs/qa/pending.md` 항목 추가가 없다 |
| B8 | 통과 | 새 로그는 수집원 id·예외 클래스 단순 이름·연속 횟수만 남긴다(MonitorAgent.java:76, :195-209). 예외 메시지(`getMessage`)는 남기지 않는다. 시험 픽스처 문자열("OSHI 없음")에도 비밀값 없음 |

## 지적 (수정 필요)

### A1. A7 "송신 거절 경고 제거"의 전제가 사실이 아니고, 새 catch는 닿지 않는 코드이며, 새 시험은 아무것도 검증하지 못한다 (B6)
- [사실] `sender`는 `ThreadPoolExecutor.DiscardOldestPolicy`를 쓴다(MonitorService.java:59-61). 이 정책은 종료된 executor에서 `execute`가 오면 예외 없이 버린다(검토자 직접 실행 확인, JDK `DiscardOldestPolicy.rejectedExecution`의 `if (!e.isShutdown())`). 따라서 `RejectedExecutionException`은 이 executor에서 나지 않고, catch(MonitorService.java:141-143)는 도달 불가 코드다.
- [사실] PR #114 architect 검토의 A7("닫는 순간 수집 스레드가 `push`하면 `RejectedExecutionException`이 나지만 … 경고 한 줄", docs/reviews/pr-114/architect.md:82)은 **이 검토자(architect)가 틀린 전제로 쓴 권고**다. 원래부터 경고 로그는 남지 않았다. 앞 검토의 오류를 여기서 바로잡는다.
- [사실] 새 시험 `MonitorServiceTest#종료와_겹쳐_도착한_표본은_예외_없이_버린다`(MonitorServiceTest.java:158-170)의 주석 "RejectedExecutionException이 리스너 실패 경고로 남던 것"은 사실이 아니고, 운영 변경 세 가지를 모두 되돌려도 통과한다(변이 시험 2). 고친 동작을 지키는 시험이 아니라 원래도 참이던 성질을 다시 확인할 뿐이다.
- [사실] 운영 코드 주석 "종료 중: … 버린다(architect PR #114 A7, 경고 로그를 남기지 않음)"(:142)과 커밋 메시지 "송신 거절 경고 제거"도 같은 잘못된 사실을 기록에 남긴다.
- [추론] `close()` 순서 변경(closeStreams 먼저, :168-175)과 `push`의 `closing` 검사(:135)는 해롭지 않다. 다만 `closeStreams()`가 이미 `streams`를 비우므로 `closing` 검사는 "검사 뒤 비우기 전" 짧은 창만 줄이고, 그 창에서 들어간 작업도 `send`가 끝난 emitter의 `IllegalStateException`을 잡아 처리한다(:150-153). 실질 동작 변화는 거의 없다.
- 요청(둘 중 하나, 추천은 ①):
  - ① 거짓 전제를 걷어낸다: catch와 그 주석을 지우거나, 남긴다면 "지금 정책(DiscardOldest)은 종료 뒤 조용히 버린다. 정책을 바꿀 때를 위한 방어"로 사실대로 고친다. 새 시험 주석도 사실대로 고치고, 시험이 실제로 무엇을 지키는지 이름에 맞춘다(예: "닫은 뒤 push는 송신 작업을 만들지 않는다"를 `sender.getTaskCount()`가 늘지 않음으로 단언하면 `closing` 검사를 되돌렸을 때 실패하는지 변이로 확인). 시험용으로 연 가시성(`sender`, `push`)이 그 시험에 꼭 필요할 때만 유지한다.
  - ② MonitorService 변경 전체를 이 PR에서 빼고 A7을 "전제 오류로 철회"로만 기록한다.
  - 어느 쪽이든 PR 본문에 "A7은 architect 검토의 전제 오류였다"를 적는다.

## 점검표 밖 발견 (권고, 머지를 막지 않음)
- A2. 반복 실패 DEBUG 문구를 주기마다 문자열로 만든다(MonitorAgent.java:209, 출력은 :85-87). DEBUG가 꺼져 있어도 연결 비용이 든다. 5초 주기라 실측 영향은 없겠지만 `System.Logger.log(Level, Supplier<String>)`로 미루거나 `LOG.isLoggable` 확인을 권한다 `[추론]`.
- A3. 실패 종류를 예외 **단순 클래스 이름**으로 가른다(:76, :203). 다른 패키지의 같은 이름 예외는 같은 종류로 묶인다. 로그 목적으로는 충분하고 메시지를 남기지 않는 쪽(B8)이 낫다. 그대로 둬도 된다.
- A4. 수집원 id 중복은 막혀 있지 않다(MonitorAgent 생성자, :51-61). 같은 id 두 수집원이 하나는 실패·하나는 성공이면 매 주기 WARN(실패)·INFO(회복)가 번갈아 나와 이 PR의 목적이 깨진다. 현재 수집원(StandardSources, Watchdog의 `disk` 하나)은 id가 겹치지 않는다 `[추론]`. 생성자에서 id 중복을 거절하는 한 줄을 권한다(계약 강화라 별 PR).
- A5. 감시 프로세스 영향: `DiskFreeSource.read()`는 경로별 `IOException | RuntimeException`을 안에서 삼키므로(DiskFreeSource.java:51-55) 감시에서 이 PR의 WARN 억제 경로에 닿는 것은 `Error`뿐이다. 그때도 첫 WARN은 남고, 판단 못 함(`Pressure.UNKNOWN`)은 Watchdog.pressure가 그대로 낸다(Watchdog.java:86-108). 감시 동작(정지 요청 파일)은 바뀌지 않는다 `[추론]`. risk-ops가 확인할 항목으로 남긴다.
- A6. `closing` 필드 주석 "종료 중이면 새 SSE를 받지 않는다"(MonitorService.java:53)는 이제 `push`도 막는다. A1 처리 때 함께 고친다.

## 판정
수정 필요(A1). B7: risk-ops 검토 필요(보호 경로 `autotrade-monitor-agent-jar` 변경, 감시 프로세스 런타임 사용. 사용자 머지·독립 QA 대상).
