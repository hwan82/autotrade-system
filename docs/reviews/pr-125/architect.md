# architect 검토: 실시간 모의 세션: meta 남은 빈 2일차 재개 시험, 되살리기 시간 로그(PR #94 F1·R2 측정)
- 대상: PR #125 / 브랜치 `claude/project-thread-an5owo` / head `db8f728` / `git diff origin/main...HEAD` 전체(커밋 1개, 파일 3개: `LiveLabService.java`, `LiveSessionTest.java`, `docs/runbooks/stage-6-live-session.md`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --max-workers=1 --console=plain`(작업 폴더, head `db8f728`): 1회차는 Maven Central 429(`archunit-junit5:1.4.1` pom 받기 실패, 코드 무관)로 실패, 30초 뒤 2회차 **BUILD SUCCESSFUL**(1분 25초). 테스트 결과 XML 합계 1301건, 실패 0·오류 0·건너뜀 0. `:autotrade-architecture-test:test` 실행됨.
  - 2회차에서 `:autotrade-app-lab:test`가 UP-TO-DATE였으므로 `:autotrade-app-lab:test --rerun`, `:autotrade-sim:test --rerun`을 따로 돌림: 둘 다 통과(app-lab 193건, `LiveLabServiceTest` 12건, `LiveSessionTest` 10건 실패 0).
  - 변이 시험(`git archive HEAD` 스크래치 사본에서만): `LiveSession.restore`의 `resumeAfter` 인자를 PR #94 R1 수정 전처럼 마지막 기록일(`days.get(days.size() - 1)`)로 되돌리고, 시험에서 `DayTwo.ZERO_BYTES` 호출 줄을 지워 새 `OPENED_UNFLUSHED` 경우만 남김 → `crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame` 실패(10건 중 1건, `Expecting actual: 0 to be greater than: 0`, 공백 증가 단언 `crashAndResume` LiveSessionTest.java:269 사본 기준). 새 경우가 단독으로 R1 수정을 판별한다.
  - 보호 경로 대조: 변경 파일 3개 중 `scripts/ci/protected-paths.txt` 패턴에 걸리는 것 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 2회차 BUILD SUCCESSFUL, 1301건 실패 0. 1회차 실패는 Maven 429(네트워크)이며 재시도에서 같은 코드로 통과 |
| B2 | 통과 | 운영 코드 변경은 `autotrade-app-lab`(Spring 쪽 프로세스 모듈) 한 곳. 새 의존 없음(`TimeUnit` 기존 import, LiveLabService.java:30). 시험의 `JournalWriter`·`RecordedDay`·`RecordingLayout` 사용은 `autotrade-sim`이 이미 `implementation(project(":autotrade-marketdata"))`(autotrade-sim/build.gradle.kts)라 경계 변화 없음. ArchUnit 규칙 변경 없음, `ArchitectureTest`·`ModuleOwnershipTest` 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 바뀐 것은 로그 문구(LiveLabService.java:209-210)와 절차서 한 줄(stage-6-live-session.md:62) |
| B4 | 통과 | `System.nanoTime()`은 실험실 프로세스의 되살리기 스레드에서 경과 시간 로그에만 쓰이고(LiveLabService.java:173, 210) 세션·계좌 계산에 들어가지 않는다. 같은 파일에 기존 사용례 있음(LiveLabService.java:517, 531). ArchitectureTest의 시계 금지 규칙(ArchitectureTest.java:140 부근)은 순수 모듈 대상이고 빌드 통과. 시험은 주입 시계(`clock.now`)만 쓰고 `LiveReplayCheck.run(...).same()`이 새 경우에도 참(LiveSessionTest `crashAndResume`) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 시험 경우 `DayTwo.OPENED_UNFLUSHED`(LiveSessionTest.java:204, 214, 249-260)가 실제 `JournalWriter`로 2일차를 연 뒤 flush 없이 버려 "gzip 머리 + meta(complete=false, 끝 없는 구간)" 모양을 만들고, 그 모양을 단언으로 확인한 뒤 재개한다. 변이 시험으로 판별력 확인. 기존 경우(`NONE`, `ZERO_BYTES`)는 그대로 유지, 지우거나 건너뛴 시험 없음. 로그 한 줄은 시험이 없으나 동작 변화가 없는 관측 로그라 막지 않음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응·보호 테스트·보호 경로(`autotrade-marketdata` 등) 변경 없음. 실험실 프로세스와 sim 시험만 바뀜 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 로그 인자는 실험 ID, 되살린 이벤트 수, 초(LiveLabService.java:209-210) |

## 점검표 밖 발견
- **N1 (참고, 막지 않음) 로그 초는 내림이다.** `TimeUnit.NANOSECONDS.toSeconds`라 1초 미만은 `0초`로 찍힌다. 절차서가 "몇 분을 넘기면 알린다"를 기준으로 하므로 지금 용도에는 충분하다. 측정 구간은 되살리기 스레드 시작(LiveLabService.java:173)부터 받는 쪽 시작 뒤(:210)까지이고, `restoring`이 false로 바뀌는 것도 같은 `synchronized` 블록 안에서 `r.session`을 넣을 때라 절차서의 "시작 요청부터 `restoring` false까지"와 사실상 같다(차이는 스레드 기동 지연뿐)[추론].
- **N2 (참고, 막지 않음) 시험이 `dying` 기록기를 닫지 않고 버린다**(LiveSessionTest.java:252-254). 의도(flush 없이 죽음)상 닫을 수 없다. 뒤이은 `LiveSession.open`이 같은 2일차 `events.jsonl.gz`를 `Files.move(..., REPLACE_EXISTING)`로 바꾸는데(DayWriter.java:127), Linux에서는 문제없고 CI도 `ubuntu-latest`(.github/workflows/ci.yml:18)다. Windows 개발 PC에서는 열린 핸들 위로 바꾸기·`@TempDir` 정리가 실패할 수 있다[추론: `Files.newOutputStream`은 Windows에서 기본으로 FILE_SHARE_DELETE를 주므로 대개 통과할 것으로 보나 Windows에서 돌려 보지 않음]. 사용자 PC에서 `./gradlew build`가 이 시험에서 깨지면 첫 의심 대상이다.
- **N3 (참고) PR #94 R2는 측정만 넣었다.** 고치지 않고 6단계 주간 점검의 되살리기 시간으로 판단한다는 절차서 문구(stage-6-live-session.md:62)는 PR #94 architect 재검토의 "권고 유지"와 맞다. "고치려면 `autotrade-marketdata`를 바꿔야 한다"는 서술도 사실이다(`carryRestartFrom`이 DayWriter.java에 있고 이 모듈은 protected-paths.txt의 실전 경로 런타임 모듈).
- PR #94 risk-ops F1(meta 남은 2일차 시험 권고)은 이 PR로 해소된다.

## 판정
승인
