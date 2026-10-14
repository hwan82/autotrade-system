# architect 검토: D-085 리플레이 점검에 장 시간 보호와 정지 요청 파일 관문 (Q-050)
- 대상: PR #105 / 브랜치 `claude/project-thread-g5f5i8` / 커밋 `a4bc3f65525fd6872d465a714df972f2583b3774` / `git diff origin/main...HEAD` 전체(12개 파일, +443 −35)
- 점검표: B
- 직접 실행한 것:
  - 별도 clone에서 `./gradlew -I <미러 init 스크립트> build --no-daemon` → `BUILD SUCCESSFUL in 2m 57s`, 130 tasks executed. 첫 시도는 Maven Central 429로 플러그인 해석 실패(코드와 무관), 미러로 재실행.
  - 테스트 결과 XML 집계: 전체 1,110건, skipped 0, failures 0, errors 0. `ReplayCheckTest` 16건(기존 8 + 새 8) 통과, `StopRequestPathTest` 1건 통과, `ArchitectureTest` 19건 통과.
  - diff 전체에서 키·토큰·계좌번호 패턴 검색: 해당 없음(걸린 줄은 기존 결정 로그 문장의 "토큰" 단어뿐).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. `./gradlew build` 성공, 테스트 1,110건 실패 0 |
| B2 | 통과 | 새 의존 없음: `ReplayCheckGate`는 이미 의존하던 `autotrade-marketdata`의 `SessionProtection`·`ExchangeCalendar`만 씀(`ReplayCheckGate.java:3-5`), `processIsolation` 금지 목록 그대로(`autotrade-app-gateway/build.gradle.kts:11-14`). 시스템 시계는 진입점 `ReplayCheckMain.java:17`에서만(`Clock.systemUTC()`), `ArchitectureTest`의 `NO_SYSTEM_TIME`(`ArchitectureTest.java:132`) 통과. 새 모듈 없음이라 `ModuleOwnershipTest.OWNERS` 변경 불필요. ArchUnit 규칙 변경 없음(새 `StopRequestPathTest`는 규칙이 아니라 상수 일치 검사) |
| B3 | 통과 | 바뀐 계약은 명령행(진입 클래스 `ReplayCheckMain`, 인자 `--allow-during-session`, Gradle 속성 `-PallowDuringSession`, 종료 코드 4)이다. 모듈 간 인터페이스·스키마·설정 키·REST 변경 없음. 결정 문서 D-085(`docs/decisions/D-085-replay-check-gate.md`), 결정 로그·Q-050 닫힘(`docs/decisions/README.md`), 기획서 RSK-07·v14.29, broker-and-fault-design 5.2 0번·#10, runbook 2곳(`stage-2-real-recording.md` 7번, `collector.md` 3.6 6번) 갱신. 실험 명령(D-063)의 같은 형태 명령행 계약을 따라 ADR 대신 결정 문서로 다룬 것은 기존 관례와 같다 |
| B4 | 통과 | 관문은 주입된 `Clock`만 씀(`ReplayCheckGate.java:62`). 정지 확인 삽입이 결과를 바꾸지 않음을 `ReplayCheckTest#정지_확인은_회차_시작과_정해진_줄마다_묻고_멈추지_않으면_결과가_같다`가 보고서 문자열 동일로 확인. 기존 테스트는 벽시계 대신 고정 시각(토요일 `2026-10-10T01:00:00Z`)으로 바꿨고 기대값은 그대로(diff 확인). 골든·결정론 테스트 포함 전체 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 테스트 8개(`ReplayCheckTest`): 장 시간 보호면 4·보고서 안 씀·확인 위치 출력, 해제 옵션 값 없음·`=`·`=true`만, `=false`·`--allow-during-sessions`는 2, 미국 장 경계(12:29Z 통과 / 12:30Z 정지)와 캘린더 범위 밖, 정지 요청 파일은 해제 옵션과 무관하게 멈추고 지우지 않음, 판단 불가(부모가 파일)도 멈춤, 실행 중 정지면 4·보고서 안 씀, 확인 횟수. 확인 횟수 테스트(회차당 10회 = 시작 1 + 수집 4 + 수집 뒤 1 + 리플레이 4)는 `ReplayCheck.java:218·225·228·237` 네 지점 중 하나라도 빠지면 깨진다. `StopRequestPathTest`가 세 모듈의 정지 요청 파일 상수 일치를 확인. 지운·끈 테스트 없음 |
| B7 | 해당 — risk-ops 검토 필요 | 주문 경로·Guard·블랙리스트 코드는 건드리지 않지만, 장애 대응 문서(broker-and-fault-design 5.2 0번 정지 순서, 장애표 #10)와 RSK-07을 바꾸고 D-085 스스로 "실전 관련 변경(지침 8장 2번), 사용자 머지로 확정"이라 적었다. 지침 10장에 따라 risk-ops A 점검표 검토와 사용자 직접 머지, 독립 QA 관문 대상 |
| B8 | 통과 | diff에 비밀값 없음. 새 출력은 정지 요청 파일 경로와 수정 시각, 해제 여부뿐(`ReplayCheck.java:117-119`, `ReplayCheckGate.java:58-60`) |

## 설계 확인
- [사실] 관문 규칙이 `ExperimentGate`와 같다: `!Files.notExists`로 판단 불가도 정지, 해제 옵션은 장 시간 보호만 풀고 정지 요청 파일은 못 풂, `SessionProtection.check`로 국내·미국 구간과 수록 범위 밖 처리(`ReplayCheckGate.java:56-76` 대 `ExperimentGate.java:51-71`). 해제 옵션 파싱도 `ExperimentMain.java:47-52`와 같은 세 값 허용이다. 입력 오류 종료 코드만 이 명령의 기존 체계(2)를 따른다.
- [사실] 정지 예외 `StoppedException`은 `RuntimeException`보다 먼저 잡혀 4가 되고(`ReplayCheck.java:144-149`), 보고서 출력·쓰기는 `check` 성공 뒤에만 일어나 멈추면 이전 보고서가 그대로 남는다(`ReplayCheck.java:137-142`). 리플레이 콜백 안에서 던진 예외를 `RecordedReplaySource.replay`가 삼키지 않는다(그 클래스에 catch 없음).
- [사실] 운영 경로에서 `check`를 부르는 곳은 `run(... StopCheck gate ...)` 하나뿐이고, 진입점은 항상 `ReplayCheckGate`를 넘긴다(`ReplayCheck.java:115·128`). `StopCheck.NONE` 오버로드(`ReplayCheck.java:181-187`)는 테스트만 부른다(운영 코드 검색 결과).
- [추론] 대안 B(공용 관문 클래스)를 버린 근거 중 "정지 요청 파일 위치가 명령마다 다르다"는 이번 경우엔 맞지 않는다(실험 명령과 같은 파일). 다만 두 클래스가 서로 의존할 수 없는 모듈에 있고 `StopRequestPathTest`가 어긋남을 막으므로 지금 중복은 받아들일 만하다. D-085의 되돌릴 조건(관문 쓰는 명령 넷 이상)도 적혀 있다.

## 점검표 밖 발견
- N1(권고, 문서): `docs/runbooks/collector.md` 67번째 줄(3.6 1번 근처)의 `ReplayCheck.java:128` 인용이 이 PR로 어긋났다. main에서 128행은 `regularSession(market, date)` 호출이었고, 이 브랜치에서는 197행이다(128행은 이제 `return run(rest.toArray(...))`). 이 PR이 같은 파일 3.6 6번을 고치므로 함께 `ReplayCheck.java:197`(또는 메서드 이름 인용)로 바꾸기를 권한다.
- N2(권고, 테스트): "실행 중 정지" 테스트(`실행_중에_정지_요청이_오면_회차_안에서_멈추고_보고서를_쓰지_않는다`)는 세 번째 확인, 곧 종목 수집 단계에서 멈춘다. 리플레이 콜백 단계(`ReplayCheck.java:236-238`)에서 던진 정지가 4로 이어지는 경로는 확인 횟수 테스트와 코드 읽기로만 확인된다. 멈추는 호출 번호를 리플레이 구간(예: 7번째)으로 둔 경우를 하나 더 두면 좋다. 차단 사항은 아니다.
- N3(권고, 위험 기록 확인): D-085 "위험과 한계"대로 `RecordedDay.open`(파일 해시 대조와 전체 줄 파싱) 동안은 정지 확인이 없다. 첫 실녹화 점검에서 단계별 시간을 재고, 길면 marketdata 소유자에게 `RecordedDay` 쪽 정지 확인을 제안하는 후속 과제를 결정 문서에 적어 둔 것을 확인했다. 후속 추적만 요청한다.

## 판정
승인 (N1~N3은 차단하지 않는 권고. B7에 따라 risk-ops 검토와 사용자 직접 머지가 별도로 필요)
