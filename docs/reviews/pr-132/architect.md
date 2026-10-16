# architect 검토: 알림 설정을 이름 있는 빌더로, 시간대·재동의 문구는 부르는 쪽이 (PR #128 후속)
- 대상: PR #132 / 헤드 d06dd7c (브랜치 claude/project-thread-7fa1t2) / `git diff origin/main...origin/claude/project-thread-7fa1t2` 전체. merge-base = origin/main = 81b5285, PR 커밋은 d06dd7c 하나, 11개 파일(+401 −40)
- 점검표: B
- 직접 실행한 것:
  - d06dd7c에서 `./gradlew --init-script <scratchpad>/mirror.gradle build --continue` → `BUILD SUCCESSFUL in 3m 1s`, 138개 작업 모두 실행(캐시 재사용 없음), 종료 코드 0. 시험 XML 집계 1,445건 / 실패 0 / 오류 0 / 건너뜀 0(`autotrade-notification-jar` 77, `autotrade-app-engine` 55, `autotrade-app-watchdog` 53, `autotrade-architecture-test` 44). 미러 init 스크립트는 저장소 밖 파일이고 저장소 파일 변경 없음.
  - 새 시험 8건이 실제로 실행·통과했는지 XML에서 이름으로 확인: `EngineMainTest`·`WatchdogMainTest#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다`, `NotificationQueueTest#상한으로_버린_종류가_새_창에서_다시_오면_받는다`·`#큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`·`#재동의_알림_본문은_부르는_쪽이_넘긴_문구다`, `NotificationSetupTest#표시_시간대는_부르는_쪽이_반드시_넣는다`·`#재동의_문구는_부르는_쪽이_넘긴_것을_쓰고_없으면_일반_문구를_쓴다`·`#같은_값이면_같고_값이_하나라도_다르면_다르다`.
  - 변이 12개를 하나씩 넣고 해당 모듈 시험(`--offline`)을 돌린 뒤 되돌림. 끝난 뒤 `git status --short` 출력 없음.

    | 변이 | 결과 |
    |---|---|
    | M17 `last.at() != null` 삭제(NotificationQueue.java:154, PR #128 QA 재검토 Q1) | 잡힘 |
    | 큐가 재동의 본문 대신 `DEFAULT_KAKAO_RECONSENT_HELP` 사용(NotificationQueue.java:227) | 잡힘 |
    | `NotificationSetup.start`가 재동의 문구 대신 기본 문구 전달(NotificationSetup.java:162) | 잡힘 |
    | `NotificationSetup.start`가 `displayZone` 대신 UTC 전달 | 잡힘 |
    | `NotificationSetup.equals`에서 `kakaoReconsentHelp` 비교 삭제 | **살아남음**(아래 R1) |
    | EngineMain.setup restApiKey↔clientSecret 바꿈 | 잡힘 |
    | EngineMain.setup accessToken↔refreshToken 바꿈 | 잡힘 |
    | EngineMain.setup `kakaoReconsentHelp(...)` 줄 삭제 | 잡힘 |
    | EngineMain.setup BIZ 판정 뒤집기 | 잡힘 |
    | WatchdogMain.setup restApiKey↔clientSecret 바꿈 | 잡힘 |
    | WatchdogMain.setup `kakaoLinkUrl(...)` 삭제 | 잡힘 |
    | WatchdogMain.setup `kakaoReconsentHelp(...)` 줄 삭제 | 잡힘 |
  - PR CI(헤드 d06dd7c, REST check-runs 조회 시점): `Secret scan and collector boundary` success, `DB schema (PostgreSQL 16)` success, `Gradle build`·`Lab screens (Playwright)`는 진행 중이었다(판정 근거는 위 직접 빌드).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 빌드 `BUILD SUCCESSFUL`, 138개 작업 실행, 1,445/0/0/0 |
| B2 | 통과 | 새 의존 없음. `autotrade-notification-jar`는 여전히 설정 모듈을 모른다(새 import는 `java.time.ZoneId`뿐, JDK 허용 목록 D-086 안). `autotrade-config`의 새 import도 `java.time.ZoneId`뿐(RuntimeConfig.java:8). 엔진·감시는 기존 의존(config, notification-jar)만 쓴다. `autotrade-architecture-test` 44건 통과, 규칙·고정 값 변경 없음. 경계 개선: 라이브러리에서 이 시스템의 설정 키(`secrets.json`, `notification.kakao.refresh-token`)·프로세스 이름("실전 엔진·감시")·`Asia/Seoul`이 빠졌고(NotificationQueue.java:49 일반 문구, NotificationSetup.java에서 `DISPLAY_ZONE` 삭제), `NotificationSetupTest#재동의_문구는_…`이 기본 문구에 그 낱말이 없음을 고정한다. PR #128 architect R2 해소 |
| B3 | 통과 | 바뀐 계약은 `autotrade-notification-jar`의 공개 Java API다: `NotificationSetup` 공개 생성자·`logOnly()`·`DISPLAY_ZONE` 삭제, `builder()`/`Builder` 추가, `NotificationQueue` 6인자 공개 생성자·`DEFAULT_KAKAO_RECONSENT_HELP` 추가, `RuntimeConfig.Notification.DISPLAY_ZONE`·`KAKAO_RECONSENT_HELP` 상수 추가. 지운 API의 다른 사용처 없음(저장소 전체 grep: 운영 코드 호출자는 EngineMain.java:129-143·WatchdogMain.java:126-140뿐, 실험실은 notification-jar를 쓰지 않음). ADR-001 모듈 표 notification-jar 행 갱신(ADR-001-module-structure.md:56), D-100 "반영 기록" 절에 R1·R2·Q1 처리와 "결정 내용이 바뀌지 않아 새 번호를 쓰지 않음"을 적음. 설정 키·스키마·REST API 변경 없음(`DISPLAY_ZONE`은 설정 키가 아닌 고정값이라고 RuntimeConfig.java:70-73에 명시). 사용자가 받는 재동의 문구는 옛 라이브러리 문구와 글자 단위로 같음(main NotificationQueue.java 옛 문자열과 RuntimeConfig.java:76-78 대조) |
| B4 | 해당 없음 | 시뮬레이션·전략 코드 변경 없음. 시계는 기존 주입 `Clock` 그대로, 새 `now()` 호출 없음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 시험이 있고 위 변이로 확인했다: 빌더 값 위치(엔진·감시 각 1, 값마다 다른 자리표시자로 자리 바뀜을 잡음, 변이 6개 잡힘), 표시 시간대 필수(`NotificationSetupTest#표시_시간대는_…`, NPE 메시지 `displayZone`), 재동의 문구 전달과 빈 값 기본 문구, 큐가 넘긴 본문을 씀, QA Q1 순서 시험 2건(M17 잡힘). 지운 시험 없음. 바뀐 기존 시험은 `NotificationSetupTest`의 생성자 호출을 빌더로, `logOnly()`를 `builder().displayZone(UTC).build()`로 옮긴 것뿐이고 단언은 그대로다. `@Disabled`·`assume` 없음, 건너뜀 0. 빈틈은 R1 |
| B7 | 실패(별도 검토 필요) | 바뀐 코드 파일 9개가 모두 보호 경로(`autotrade-app-engine/*`, `autotrade-app-watchdog/*`, `autotrade-config/*`, `autotrade-notification-jar/*`, scripts/ci/protected-paths.txt 기준. 이 목록 파일 자체는 바뀌지 않음)다. 실전 엔진 기동 경로의 알림 조립(EngineMain.java:129-143)을 바꾼다. 주문·Guard·블랙리스트 코드는 건드리지 않는다. **risk-ops 검토 필요**. 이 글을 쓰는 시점에 `docs/reviews/pr-132/risk-ops.md`는 없고, `docs/qa/pending.md`에 PR #132 줄도 없다(지침 11장). 사용자 머지·독립 QA 대상 |
| B8 | 통과 | 새 시험 값은 자리표시자(`SLACK-URL-PH`, `REST-PH`, `ACCESS-PH`, `REFRESH-PH`, `CLIENT-PH`, `http://link.example`). `toString`은 여전히 값 없이 `values=****`(NotificationSetup.java 끝, 기존 시험 통과), 새 `hashCode`는 문자열 값을 쓰지 않는다(:215). 재동의 문구 상수에 비밀값 없음. 새 로그 문구 없음. CI gitleaks success |

## 점검표 밖 발견
- R1(권고, 시험 공백): `NotificationSetup.equals`(NotificationSetup.java:205-213)에서 `kakaoReconsentHelp` 비교를 지우는 변이가 살아남는다. `NotificationSetupTest#같은_값이면_같고_값이_하나라도_다르면_다르다`(:99)는 리프레시 토큰·링크·유료 모드만 바꿔 본다. 지금은 엔진·감시 시험이 문구를 직접 넘기므로 단독으로는 해가 없지만, equals 결함과 `.kakaoReconsentHelp(...)` 누락이 함께 들어오면 둘 다 통과한다. 같은 시험에 `displayZone`·`kakaoReconsentHelp`·각 문자열 키 하나씩 다른 경우를 더할 것을 권한다.
- R2(참고): `equals`/`hashCode`를 시험 목적으로 운영 클래스에 공개로 두었다(:203 주석에 "시험용"이라 적음). 비밀값을 `String.equals`로 비교하지만 외부 입력과 비교하는 경로가 없어 시간차 문제는 없다. 대안(패키지 접근자로 값을 꺼내 비교)은 엔진·감시 패키지가 달라 쓸 수 없었으므로 받아들인다.
- R3(참고): `EngineMain.setup`과 `WatchdogMain.setup`은 여전히 같은 코드 둘이다. D-100 반영 기록에 "알림 모듈은 설정 모듈을 모르고, 둘이 함께 기대는 모듈을 새로 만들 만큼 크지 않음"이라 이유를 적었고, 두 곳 모두 값 위치 시험으로 묶였다. config → notification-jar 의존을 새로 만들면 notification-jar의 "의존 없음"(ADR-001:56)과 config의 순수성이 흐려지므로 이 선택에 동의한다. 세 번째 호출자가 생기면 다시 볼 것.
- R4(참고): `NotificationQueueTest#큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`(:249)는 단언이 실패하면 막힌 `release` 래치가 풀리지 않은 채 `@AfterEach`의 1초 닫기로 넘어간다. 보내는 스레드는 데몬이라 빌드를 막지 않는다. 지금은 통과하므로 고칠 필요는 없다.

### PR #128 권고 처리
| 항목 | 상태 | 근거 |
|---|---|---|
| architect R1(설정 옮기기 위치 인자 순서) | 해결 | 공개 위치 인자 생성자 삭제, 빌더만 남음(NotificationSetup.java:63). 엔진·감시 값 위치 시험, 자리 바꿈 변이 잡힘 |
| architect R2(라이브러리 안 호스트 문구·시간대) | 해결 | 위 B2 |
| QA 재검토 Q1(변이 M17 생존) | 해결 | 시험 2건 추가, M17 잡힘 |

## 판정
승인 (architect 기준. 실전 관련 보호 경로 PR이므로 risk-ops 검토, `docs/qa/pending.md` 등록과 독립 QA, 사용자 직접 머지가 남아 있다. R1은 권고)
