# architect 검토: 실험 ID Windows 예약 이름 거절·리플레이 실행 방식 표시(D-097, PR #82 QA R1·R2)
- 대상: PR #126 / 브랜치 `claude/project-thread-uzl25d` 헤드 `61d3ada` / base main `587ff37` (merge-base = `587ff37`), `git diff origin/main...HEAD` 전체 8파일 +217 −12
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` 로컬 7회(1회 + 백오프 재시도 6회) → 모두 `BUILD FAILED`. 원인은 코드가 아니라 의존성 내려받기: `repo.maven.apache.org`가 `429 Too Many Requests`(`kotlin-assignment-compiler-plugin-embeddable-2.0.21.jar`, `spring-boot-loader-tools-3.5.16.pom`, `commons-compress-1.27.1.pom`). 컴파일·시험 단계까지 가지 못함. JUnit 단독 실행용 jar 내려받기도 429로 실패.
  - 대신 CI 확인(GitHub REST): 워크플로 `CI` 실행 38835512498, `head_sha=61d3ada641f81e3adc915ad3da173f39802ae2e5`(이 PR 헤드와 같음), 체크 `Gradle build` success(이 단계는 `.github/workflows/*.yml:28` `./gradlew build --no-daemon`), `DB schema (PostgreSQL 16)` success, `Secret scan and collector boundary` success. CI 작업 로그 본문은 이 세션에서 저장소 blob 주소를 열 수 없어 읽지 못함(시험별 결과 XML은 보지 못함).
  - `ExperimentId.java`만 `javac`로 따로 컴파일해 손 검사: 예약 이름 10종·형식 위반 5종(`con`, `Nul`, `CON.TXT`, `../X`, 빈 문자열) 거절, 포함하기만 한 ID 8종 받음, 실패 0.
  - `format.js` `clockModeLabel`을 node로 7경우 실행: REALTIME→실시간 모의, `…#DAILY-PATH-V1#…`→리플레이(일봉 경로), `REC-…`→녹화 리플레이, `SYNTH-…`→리플레이(합성 시세), REPLAY+null·누락→리플레이, 빈 객체→실시간 모의(이전 동작과 같음).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI로 대체) | 로컬 빌드는 Maven 429로 구성 단계에서 실패(코드 원인 아님, 위 기록). 같은 헤드 `61d3ada`의 CI `Gradle build`(= `./gradlew build`: 컴파일, 전체 시험, 순수 모듈 의존 검사, ArchUnit) success. 시험 제외 설정 변경이 diff에 없으므로 새 시험 `ExperimentIdTest`(3개 매개변수 시험), `ExperimentRegistrationsTest`(4개)가 CI에서 통과한 것으로 판단 `[추론]`(결과 XML은 직접 보지 못함) |
| B2 | 통과 | `autotrade-sim`(순수 Java) 변경은 `java.util.regex`만 씀(`ExperimentId.java:3-4`). `autotrade-app-lab`이 `com.autotrade.sim.ExperimentId`를 import(`ExperimentRegistrations.java:11`)하는 것은 기존 방향(app-lab → sim, `ExperimentFolders`·`ExperimentJobs`가 이미 사용). 새 운영 클래스·패키지 없음, 보호 경로(`scripts/ci/protected-paths.txt`) 파일 변경 없음. 새 패키지 전용 `FileWriter` 인터페이스는 같은 클래스 안 |
| B3 | 통과 | REST 응답 형태·상태 코드 체계는 그대로: 예약 이름은 `IllegalArgumentException` → `ExperimentFolderController.java:43-44,54-55` 400 `INVALID`로, ADR-005 `POST /api/experiment-folders`의 "입력이 틀리면 400(폴더를 만들지 않음)"(`docs/adr/ADR-005-ui-dashboard.md:109`) 범위 안. `clock_mode` API·DB 값 불변, 화면 문구만 바뀜. 설정 키·스키마 변경 없음. 결정 기록 D-097과 결정 로그 행 있음 |
| B4 | 통과 | 시뮬레이션 계산 경로 변경 없음. 바뀐 것은 실험 ID 검사(입력 거절만 늘어남)라 결과에 영향 없음. 저장소의 기존 등록 파일·시험에 예약 이름 ID 없음(`grep`로 `experiments/`, `*.java`, `*.yaml` 확인). 시험의 `Clock.systemUTC()`는 프로필 저장소 생성용 시험 코드뿐. 골든 시험은 CI 전체 빌드 통과로 갈음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 시험: 예약 이름 거절(`ExperimentIdTest#Windows_예약_이름은_받지_않는다`, `ExperimentRegistrationsTest#Windows_예약_이름은_실험_ID로_거절하고_폴더를_만들지_않는다` — 미리 보기·등록 모두, 폴더 미생성 단언), 포함 ID 허용(양쪽 `예약_이름을_포함하기만_한_ID는_받는다`), 쓰기 실패 정리(`쓰기가_IOException으로_실패하면_…`: 부분 쓰기 후 실패 → 폴더 없음·같은 ID 재등록 가능, `쓰기가_비검사_예외로_실패해도_…`: 같은 예외 인스턴스 재던짐 `isSameAs`). 시험 삭제·`@Disabled` 없음. 빈 곳: 화면 JS 시험 없음(D-026 한계, 아래 N3) |
| B7 | 해당 없음 | 실전 경로·RealTradingGuard·블랙리스트·장애 대응·보호 테스트 변경 없음. 실전 엔진·감시·게이트웨이 모듈은 `com.autotrade.sim`을 참조하지 않음(`grep -rl com.autotrade.sim autotrade-app-{engine,watchdog,gateway}` 결과 없음) |
| B8 | 통과 | diff와 커밋 메시지에 키·계좌번호·토큰 없음. 새 예외 문구는 실험 ID만 포함. gitleaks는 CI `Secret scan` success |

## 점검표 밖 발견
- **N1 (확인, 좋음) 검사 위치가 맞다**: 예약 이름 검사를 `ExperimentId` 생성자에 둔 덕에 화면 등록뿐 아니라 `CombinationPlan.parse`(`CombinationPlan.java:140`), `WalkForwardPlan`·`RecordingPlan`·`LivePlan` 등록 파일 읽기, `ExperimentJobFiles`의 `<ID>.status/.lock/.cancel-request` 파일 이름(`ExperimentJobFiles.java:39-47`), 결과 묶음 읽기(`ResultBundleReader.java:146`)가 모두 같은 규칙을 탄다. Windows는 `CON.status`처럼 확장자가 붙어도 장치로 열므로 작업 상태 파일 쪽 위험도 함께 닫힌다. 리눅스에서 이미 만든 `CON` 폴더가 있어도 폴더 목록은 `CombinationPlan.parse` 예외를 `INVALID`로 보여 주고(`ExperimentFolders.java` list), 작업 목록은 읽기 실패를 `unreadable`로 세므로(`ExperimentJobs.java` list) 화면 전체가 깨지지 않는다.
- **N2 (권고, 비차단) 형식 정규식 중복**: `ExperimentRegistrations.ID`(`ExperimentRegistrations.java:45`)와 `ExperimentId.FORMAT`이 같은 식을 따로 갖고, `render`는 둘 다 검사한다(`:148-152`). 화면용 예시 문구를 주려는 의도는 이해하지만 형식이 바뀌면 두 곳을 고쳐야 한다. 다음에 이 파일을 고칠 때 `ExperimentId`의 예외를 받아 화면 문구로 감싸는 한 곳 검사로 줄이기를 권한다.
- **N3 (한계, 비차단) 화면 표시 규칙은 자동 시험 없음**: `clockModeLabel`은 데이터 버전 문자열 표지에 기댄다. 표지 출처 확인: `DailyBarMarketDataSet.java:59,183`(`<스냅샷>#DAILY-PATH-V1#기간`), `RecordedMarketDataSet.java:182`(`REC-…`), `LiveWeeklyCheck.java:469`(`REC-BOARDS-…`, 녹화 리플레이로 표시되어 맞음), `SyntheticMarket.java:55,98`(`SYNTH-KRX-V1-…`). 표지가 바뀌면 덜 구체적인 "리플레이"로 떨어질 뿐 틀린 이름은 나오지 않는다(D-097 위험과 한계와 일치). node로 7경우 직접 확인.
- **N4 (참고) 역할 경계**: D-097 담당은 ui-dev인데 `ExperimentId`(sim-engine 값 타입)를 바꾼다. 변경이 입력 거절 추가뿐이고 D-097이 architect 검토 범위로 명시했으므로 이 검토로 갈음한다. sim-engine 쪽 영향(기존 ID 거절로 깨지는 등록·골든)은 저장소 검색과 CI로 없음을 확인.
- **N5 (참고) 외부 근거**: D-097의 Microsoft Learn 예약 이름 목록(COM0·LPT0, 위첨자 숫자 포함)은 이 세션에서 웹으로 다시 열어 보지 못했다. 내가 아는 해당 문서 내용과 일치한다 `[추론]`. 형식이 대문자·숫자·`-`만 받으므로 `CONIN$`·`CONOUT$`·확장자·끝 공백/마침표 꼴은 형식 단계에서 막힌다.
- **N6 (참고) 로컬 빌드 미실행**: 위 B1처럼 이 세션의 로컬 `./gradlew build`는 Maven 429로 끝까지 돌지 못했다. 요청자 지시에 따라 같은 헤드 SHA의 CI 결과로 대체했다. Windows 실기 동작(실제로 `CON` 폴더 만들기 실패)은 확인하지 않음.

## 판정
승인
