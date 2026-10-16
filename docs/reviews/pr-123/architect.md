# architect 검토: 감시 자원 값 결측 경고를 10분마다 반복, 오가는 결측은 간격마다 한 번(D-096, PR #122 QA R1·R4)
- 대상: PR #123 / 브랜치 `claude/project-thread-0ganrn` 헤드 `5f23d8a` / base main `1006a33`(merge-base도 `1006a33`) 대비 diff 전체(`git diff origin/main...origin/claude/project-thread-0ganrn`, 8파일 +127 −24, 커밋 2개 `6f4cae7`, `5f23d8a`)
- 점검표: B
- 직접 실행한 것:
  - 헤드 `5f23d8a`를 분리 체크아웃해 `./gradlew build --no-daemon` → `BUILD SUCCESSFUL in 4m 38s`, 종료 코드 0(Maven 429 없음).
  - `autotrade-app-watchdog/build/test-results/test/TEST-com.autotrade.app.watchdog.WatchdogTest.xml`: tests=10, skipped=0, failures=0, errors=0. 새 시험 `값이_빠지면_바로_알리고_계속_빠지면_간격마다_다시_알린다`, `같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_한_번만_알린다` 포함.
  - 변이 3건(운영 코드 한 곳씩 고치고 `:autotrade-app-watchdog:test --tests '*WatchdogTest'`, 매번 원복, 끝에 `git status` 깨끗함·모듈 시험 재통과 확인):
    - V1 반복 조건 제거(`due = lastMissingWarning == null`) → 2건 실패(검출)
    - V2 간격 안 경고 값 합치기 제거(`warned.addAll(warnedReadings)` 삭제) → 1건 실패(검출)
    - V3 회복 INFO 조건 제거(`if (warnedSinceRecovery)` → `if (true)`) → 2건 실패(검출)
    - D-096 검증 절의 네 번째 변이(새 값 판정을 집합 동일 비교로)는 이 검토에서 돌리지 않았다(판정 항목 아님).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 헤드 `5f23d8a`에서 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 종료 코드 0 |
| B2 | 통과 | 의존성·빌드 스크립트·모듈 추가 없음(diff 8파일 중 코드는 `Watchdog.java`, `WatchdogTest.java` 둘). 새 import는 `java.time.Duration`뿐(Watchdog.java:12). `autotrade-architecture-test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest)가 build 안에서 통과. 규칙 변경 없음 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `MISSING_REPEAT`는 패키지 전용 코드 상수(Watchdog.java:49)이고 `watchdog.properties` 키가 아님(WatchdogSettings.java:45-52 키 목록 그대로). 로그 정책 변경이지만 ADR-011 3장에 D-096 줄을 추가(docs/adr/ADR-011-watchdog-heartbeat.md:35), 결정 로그 D-096 파일·색인 행, 기획서 v14.39 변경 이력·MON-02 근거 링크, 런북 5장 확인 항목 갱신 |
| B4 | 통과 | 현재 시각은 주입된 `Clock`에서 한 번 얻은 `now`를 넘김(Watchdog.java:83, 89, 151). 시스템 시계 생성은 `WatchdogMain`(WatchdogMain.java:34)뿐이고 ArchitectureTest 통과. 난수 없음. `Set` 순회·출력은 `TreeSet`(Watchdog.java:138, 155)으로 순서 고정. 시뮬레이션 코드 변경 없음, 골든 테스트 포함 전체 build 통과 |
| B5 | 해당 없음 | 금액 계산 없음(로그 정책만) |
| B6 | 통과 | 새 동작(간격 반복, 간격 경계, 새 값 즉시, 오가는 결측 억제, 회복 INFO 한 번)마다 단언이 있음(WatchdogTest.java:172-230). 기존 시험 `값이_빠지기_시작하면_한_번만_알리고_돌아오면_다시_알릴_수_있다`는 이전 정책(집합 변화 때만)을 고정하던 것이라 새 정책 시험으로 바꿈. 바뀐 정책의 단언(최초 1회, 새 값 추가 시 다시)은 새 시험에 남아 있어 약화가 아님. 건너뛴 시험 0건. 변이 V1~V3 모두 검출 |
| B7 | risk-ops 검토 필요 | 보호 경로 `autotrade-app-watchdog/*`(scripts/ci/protected-paths.txt:9, .github/CODEOWNERS:6) 변경. D-096도 스스로 실전 관련 PR로 분류. 이 변경은 로그만 바꾸고 `pressure` 판정·`StopRequests.update` 호출(Watchdog.java:90, 101)은 그대로임을 확인했으나, risk-ops 검토 원문(`docs/reviews/pr-123/risk-ops.md`)은 이 브랜치에 없다. risk-ops 검토, 독립 QA(`docs/qa/pending.md` 마지막 행 추가 확인), 사용자 머지 대상 |
| B8 | 통과 | diff에 비밀값·계좌번호·토큰 없음. 새 로그 문구는 지표 이름 집합(`cpu.system`, `memory.used`, 드라이브 지표 이름)과 상수만 출력(Watchdog.java:146, 163). build 안 gitleaks는 CI 단계라 여기서는 diff 검색으로 대신함 |

## 점검표 밖 발견
- N1(참고, D-096 위험과 한계에 이미 적힘) 회복 INFO 뒤 같은 간격 안에서 같은 값이 다시 빠져 그대로 이어지면, 마지막 로그가 "자원 값을 다시 읽음"인 채 최대 10분 동안 WARN이 없다(Watchdog.java:150-154: `newName=false`, `due=false`). 예: t=0 결측 WARN, t=5s 회복 INFO, t=10s부터 계속 결측 → 다음 WARN은 t=10분. 위험 판정은 그동안 `UNKNOWN`이라 새 정지 요청을 만들지 못하는데 로그는 회복을 마지막으로 보여 준다. 대안으로 "회복 INFO는 간격 안에서 억제하고, INFO 뒤 재결측은 바로 WARN" 같은 순서를 고르면 마지막 로그가 실제 상태와 어긋나는 구간이 없어진다. 문서화된 한계이고 최대 10분이라 막지 않는다. MON-06(알림) 때 다시 볼 것을 권한다.
- N2(참고) 시스템 시계가 뒤로 크게 가면(`now`가 `lastMissingWarning`보다 이전) 반복 WARN이 시계가 따라잡을 때까지 미뤄진다(Watchdog.java:151). `HeartbeatWatch.ALL_ABSENT_REPEAT`(HeartbeatWatch.java:107)와 같은 방식이라 일관되지만, 두 곳 모두 `now.isBefore(last)`이면 바로 다시 알리는 처리를 후속으로 고려할 수 있다.
- N3(참고) 회복 INFO가 출력하는 `warnedReadings`(Watchdog.java:146)는 간격 안에서 합친 값이라 마지막 경고 때 실제로 빠져 있던 값보다 많을 수 있다. 표시만의 문제.
- N4(참고) D-096 대안표의 "하루 결측이 이어져도 WARN 144줄 이하"는 같은 집합이 이어질 때 맞다. 간격 안에 새 이름이 생기면 그 간격에 이름 수만큼 더 남을 수 있어 상한은 간격마다 필수 값 수+1이다. 문구 정확도 문제.
- N5(참고) 런북 5장의 낮춘 임계(`cpu.system=1:2:1`, 범위 검사 WatchdogSettings 통과 확인) 시험은 정지 요청 파일을 만든다. 원래 값으로 되돌려 감시를 다시 띄워도 `adoptLeftovers`로 이어받아 `release-hold-seconds`(기본 300초) 정상이 이어진 뒤에야 지워진다(StopRequests.java:80, 172). 런북에 "파일이 지워졌는지 확인" 한 줄을 더하면 좋다.

## 판정
승인
(B 실패 0건, 확인 못 함 0건. B7은 risk-ops 검토 필요: 실전 관련 PR이므로 risk-ops 검토·독립 QA·사용자 머지가 남는다. 이 판정은 architect 범위에 한한다.)
