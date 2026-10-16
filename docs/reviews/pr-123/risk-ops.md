# risk-ops 검토: 감시 자원 값 결측 경고를 10분마다 반복, 오가는 결측은 간격마다 한 번(D-096, PR #122 QA R1·R4)
- 대상: PR #123 / 헤드 5f23d8a (브랜치 claude/project-thread-0ganrn) / main 1006a33 대비 diff(`git diff origin/main...origin/claude/project-thread-0ganrn`, 커밋 2개: 6f4cae7, 5f23d8a)
- 점검표: A
- 직접 실행한 것:
  - 헤드 5f23d8a를 분리 체크아웃해 `./gradlew build --console=plain` → `BUILD SUCCESSFUL in 4m 32s`, 종료 코드 0.
  - 시험 결과 XML 집계(tests/failures/errors/skipped): `:autotrade-risk:test` 55/0/0/0, `:autotrade-architecture-test:test` 44/0/0/0, `:autotrade-app-engine:test` 52/0/0/0, `:autotrade-app-watchdog:test` 40/0/0/0, `:autotrade-monitor-agent-jar:test` 27/0/0/0. `WatchdogTest` 10건 모두 통과(새 시험 `값이_빠지면_바로_알리고_계속_빠지면_간격마다_다시_알린다`, `같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_한_번만_알린다` 포함).
  - 변이 V1~V9: `Watchdog.java`를 한 곳씩 고치고 `:autotrade-app-watchdog:test --tests '*WatchdogTest'` 실행, 매번 원본으로 되돌림. 끝난 뒤 `git status --short` 출력 없음(작업 트리 깨끗).

    | 변이 | 결과 |
    |---|---|
    | V1 반복 조건 제거(`due = lastMissingWarning == null`) | 잡힘: 두 새 시험 실패 |
    | V2 간격 안 경고 값 합치기 제거(`warned.addAll(warnedReadings)` 삭제) | 잡힘: `같은_값이_…_한_번만_알린다` 실패 |
    | V3 새 값 판정을 집합 동일 비교로(`!warnedReadings.equals(missing)`) | 잡힘: `같은_값이_…` 실패 |
    | V4 회복 INFO 조건 제거(`if (true)`) | 잡힘: 두 새 시험 실패 |
    | V5 경계 바꿈(`!now.isBefore(...)` → `now.isAfter(...)`, 정확히 10분에 다시 알리지 않음) | 잡힘: `값이_빠지면_…_간격마다_다시_알린다` 실패 |
    | V6 회복 때 경고 값 잊음(`warnedReadings = Set.of()`) | 잡힘: `같은_값이_…` 실패 |
    | V7 새 값 즉시 경고 제거(`if (!due)`) | 잡힘: 두 새 시험 실패 |
    | V8 간격 1분으로 | 잡힘: `같은_값이_…` 실패 |
    | V9 `tick`에서 `reportMissing` 호출 제거 | 잡힘: 두 새 시험 실패 |

## 이번 PR이 실전 경로에 닿는 곳
- 운영 코드 변경은 `autotrade-app-watchdog/.../Watchdog.java` 한 파일, 그중 결측 로그 정책(`reportMissing`, Watchdog.java:45-56, 133-165)과 시험용 계수기(:172-175)뿐이다. `autotrade-app-watchdog`는 보호 경로(`scripts/ci/protected-paths.txt` "autotrade-app-watchdog/*")라 실전 관련 PR이다.
- 정지 요청 판정 `pressure`(Watchdog.java:108-131)와 `StopRequests`, `HeartbeatWatch`, `WatchdogSettings`, `PlatformOsReadings`는 바뀌지 않았다(diff에 없음). 결측이면 `UNKNOWN`이라 새 정지 요청을 만들지 못하고 켜진 요청은 풀지 않는 동작은 그대로다.
- `reportMissing`은 `pressure`보다 먼저 같은 `try` 안에서 불린다(Watchdog.java:88-91, 기존과 같은 순서). 새 코드가 던질 수 있는 곳은 `Reading.name`이 null일 때의 `TreeSet.remove`뿐인데 `Reading` 생성자가 `requireNonNull(name)`(Reading.java:16)이라 없다. `Instant.plus(10분)`도 넘침이 없다. 그래서 결측 로그 변경이 위험 판정을 `UNKNOWN`으로 떨어뜨리는 새 경로는 없다.
- 실전 엔진·`autotrade-risk`·`autotrade-broker-*`·`autotrade-architecture-test` 변경 0건(`git diff --stat origin/main...HEAD -- autotrade-architecture-test autotrade-risk autotrade-app-engine autotrade-broker-api` 출력 없음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` 변경 없음(diff 범위 밖). `:autotrade-risk:test` 55/0/0/0 직접 실행 통과(`RealTradingGuardTest` 포함). Guard 기본값·재기동 OFF에 닿는 설정·코드·시험 변경 없음 |
| A2 | 통과 | 보호 테스트 모듈 변경 없음, `:autotrade-architecture-test:test` 44/0/0/0(`ArchitectureTest`, `ModuleOwnershipTest`, `RealPathReflectionTest`). 새 코드는 `java.time.Duration` import 하나만 늘었고 관리 API·리플렉션·주문 출구를 쓰지 않는다(Watchdog.java:9-21) |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진 변경 없음, `:autotrade-app-engine:test` 52/0/0/0. 감시는 로그만 더 남기고 정지 요청 판정은 그대로다(Watchdog.java:108-131 diff 없음). 5.2 정지 순서 0번(broker-and-fault-design.md:144)의 감지 수단(드라이브·CPU·메모리)이 꺼진 상태가 이제 10분마다 WARN으로 드러나 정지 순서가 조용히 무력화되는 위험이 줄었다(D-096 결정 1, 시험 `값이_빠지면_…_간격마다_다시_알린다`, 변이 V1·V5). 로그량은 같은 결측 지속 시 10분에 1줄로 상한이 있어 감시 루프 부하가 늘지 않는다(V8로 간격이 시험에 묶인 것 확인) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 로그 문구는 지표 이름 집합(`cpu.system`, `memory.used`, `disk.free-gb:<경로>`)과 결정 번호·간격 분만 낸다(Watchdog.java:146, 163-164). 시험 픽스처·문서 diff에 키·계좌번호·토큰 없음(diff 전문 확인, CI gitleaks는 빌드와 별개로 확인하지 않았으나 diff가 이 리뷰 범위 전부다) |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 시험 `값이_빠지기_시작하면_한_번만_알리고_돌아오면_다시_알릴_수_있다`는 동작이 바뀌어(같은 결측 지속 시 한 번 → 10분마다) `값이_빠지면_바로_알리고_계속_빠지면_간격마다_다시_알린다`로 대체됐다. 대체 시험이 옛 시험이 보던 것(첫 결측 즉시 WARN, 빠진 값이 늘면 다시 WARN, 회복)을 모두 단언하고 간격 경계(직전 없음·정확히 10분에 있음)를 더 본다(WatchdogTest.java:171-201). 오가는 결측은 새 시험이 본다(:203-230). 변이 V1~V9 모두 잡힘 |
| A12 | 통과 | 장애표 #10(broker-and-fault-design.md:169)과 5.2 0번(:144)의 발동·해제 조건은 바뀌지 않았다(`pressure`·`StopRequests` 변경 없음). :144의 "(빠지기 시작하면 경고 로그)"는 새 동작과 어긋나지 않으나 반복 주기가 빠져 있다(점검표 밖 N1). 장애 테스트 8종의 전제(감시가 정지 요청 파일을 만들고 지우는 조건)는 그대로다 |
| A13 | 해당 없음 | 시장별 코드 변경 없음. 간격 계산은 `Instant`·`Duration` 경과 시간이라 시간대와 무관하다 |
| A14 | 통과 | D-096(결정 1·2, 대안 4개, 위험과 한계, 검증)이 코드 동작과 맞는다: 새 값 즉시(Watchdog.java:150), 지속·되풀이 시 10분(:151, `MISSING_REPEAT` :49), 간격 안 경고 값 합치기(:155-158), 경고 뒤 회복 INFO 한 번(:142-147). 결정 로그 D-096 행, 기획서 v14.39와 MON-02 근거 열, ADR-011 3장(:35), runbook 5장(stage-6-live-session.md:77-79)이 같은 내용이다. `ALL_ABSENT_REPEAT`가 10분이라는 주장은 HeartbeatWatch.java:48과 맞다. D-095:31·43의 옛 동작·옛 시험 이름은 D-096 결정 3과 "머지된 결정 본문은 고치지 않음" 관례로 대체되었다(D-096 결정 1 "이전에는 결측 집합이 바뀔 때만"). 추천안 채택(자율) 표시와 사용자 머지 표시가 있다 |

## 점검표 밖 발견 (판정에 넣지 않음, 후속 권고)
- **N1(문서) 5.2 0번 문구 보충.** broker-and-fault-design.md:144 "(빠지기 시작하면 경고 로그)"를 "(빠지면 경고 로그, 빠진 동안 10분마다 다시, D-096)"로 맞추기를 권한다. 지금 문구가 틀린 것은 아니라 판정에 넣지 않는다.
- **N2(문서 정밀도) 상한 문구.** D-096 결정 2 "오가는 결측은 간격마다 WARN 하나와 INFO 하나가 최대"와 대안 ① "하루 WARN 144줄 이하"는 같은 값(같은 집합) 기준일 때만 맞다. 서로 다른 값이 번갈아 빠지면 새 값마다 즉시 WARN이 나므로 간격당 WARN은 필수 값 수(드라이브 경로 수 + 2)까지, 그 사이 회복 INFO도 그만큼 나올 수 있다(새 시험의 마지막 구간이 실제로 한 간격 안 WARN 2건을 보인다, WatchdogTest.java:222-229). 그래도 상한이 있고 감시 부하 문제는 아니다. 문구에 "필수 값 하나당"을 붙이기를 권한다 `[사실, 코드와 시험 읽기]`.
- **N3(남은 위험) 벽시계 역행.** 운영 시계는 `Clock.systemUTC()`(WatchdogMain.java:34)다. 마지막 경고 뒤 PC 시각이 뒤로 크게 바뀌면(수동 변경·NTP 큰 보정) `now.isBefore(last + 10분)`이 그 역행 폭만큼 더 이어져 반복 WARN이 늦어진다. 새 값 즉시 경고와 정지 요청 판정에는 영향이 없고, `HeartbeatWatch.ALL_ABSENT_REPEAT`(HeartbeatWatch.java:107)도 같은 성질이다. 로그만의 문제라 막지 않는다. 고칠 때는 두 곳을 함께 단조 시계 또는 "역행이면 바로 due"로 바꾸는 것을 권한다 `[추론, 코드 읽기, 시험하지 않음]`.
- **N4(운영 절차) 임계 낮춰 띄우기 뒤 정지 요청 잔존.** runbook 5장 새 항목(stage-6-live-session.md:77)은 `cpu.system=1:2:1`로 잠깐 띄우면 정지 요청 파일이 생긴다고 적고 되돌린 뒤 감시를 다시 띄우라고 한다. 다시 띄운 감시는 남은 파일을 이어받아(`StopRequests.adoptLeftovers`, StopRequests.java:80) 정상이 `release-hold-seconds`(기본 300초) 이어진 뒤에야 지운다. 그 사이 장 마감 뒤 수집기·실험 실행 명령이 시작을 거부한다(안전한 쪽). runbook에 "감시 로그에 '정지 요청 파일을 지웠습니다'가 나온 뒤 일괄 명령을 돌린다"를 덧붙이기를 권한다. 실전 엔진은 이 파일을 읽지 않으므로 계좌 영향은 없다.
- **N5(시험) 로그 수준·문구는 단언하지 않음.** 새 시험은 WARN·INFO 횟수 계수기만 본다. `LOG.log` 수준을 WARNING에서 DEBUG로 낮추는 변이는 시험이 잡지 못한다 `[추론, 변이로 확인하지 않음]`. 계수기가 로그 호출과 같은 분기에 있어 지금 코드에서는 수준만 바뀔 위험이 낮으므로 권고로 둔다.
- **N6(설계상 한계, D-096에 적혀 있음).** 회복 INFO 뒤 같은 값이 간격 안에 다시 빠지면 다음 간격(최대 10분)까지 로그가 없고 마지막 로그가 회복 INFO일 수 있다. 그동안에도 정지 요청 판정은 `UNKNOWN`이라 켜진 요청을 풀지 않는다(안전한 쪽). D-096 위험과 한계 첫 줄과 일치한다.

## 판정
승인

점검표 A 실패 0건, 확인 못 함 0건. N1~N6은 판정에 넣지 않은 권고·남은 위험이다. 실전 관련 PR(보호 경로 `autotrade-app-watchdog`)이므로 머지는 독립 QA(`docs/qa/pending.md` PR #123 행) 뒤 사용자가 직접 한다.
