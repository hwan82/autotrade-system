# QA 검토: 알림 재동의 문구 전체 시험·공개 5인자 NotificationQueue 생성자 삭제(PR #132 QA Q1·R1)
- 대상: PR #134 / 헤드 88df89c(브랜치 `claude/project-thread-dvuau0`) / main 대비 diff `git diff origin/main...HEAD` 전체(커밋 4개, 파일 7개, +109 −7. 코드 4개 파일 +35 −7, 문서 3개). 머지 기준점 a2f94ac, 검토 시점 main 184a9f3(PR #135 병합 뒤)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - 헤드 88df89c에서 `./gradlew -I <저장소 밖 미러 init 스크립트> build --rerun-tasks --refresh-dependencies` → **BUILD SUCCESSFUL in 5m 54s**, 138 actionable tasks 모두 실행. JUnit XML 집계 1,447건, 실패 0, 오류 0, 건너뜀 0. `RuntimeConfigNotificationTest` 2/0, `NotifyingCommandAuditLogTest` 2/0, `NotificationQueueTest` 16/0, `NotificationSetupTest` 7/0, `EngineMainTest` 13/0, `WatchdogMainTest` 6/0. init 스크립트는 Maven Central 차단을 피하려고 저장소 미러만 지정하며 저장소 파일은 바꾸지 않았다.
  - main 184a9f3에 헤드 88df89c를 병합한 임시 작업 트리(b3d0563, 충돌 없음)에서 `./gradlew build` → **BUILD SUCCESSFUL**, 138 tasks, 1,464건 실패 0. PR #135(D-103 설정 화면 알림 시험 보내기)가 지운 5인자 생성자를 쓰지 않음을 컴파일로 확인.
  - GitHub CI(헤드 88df89c): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary, Lab screens (Playwright) 4개 모두 success.
  - 변이 8개(아래 표). 각 변이 뒤 `git checkout -- .`로 되돌렸고 실패한 시험 이름은 시험 결과 XML로 확인.
  - `git grep "new NotificationQueue("`(헤드·main 둘 다): 공개 6인자 호출은 `NotificationSetup.java:162`(운영)와 `NotifyingCommandAuditLogTest.java:40`(시험)뿐, 나머지는 같은 패키지 시험의 패키지 비공개 생성자(`NotificationQueueTest.java:71,327,349`).

## 변이 시험
| # | 변이 | 결과 | 잡은 시험 |
|---|---|---|---|
| M1 | `RuntimeConfig.KAKAO_RECONSENT_HELP`에서 " 실전 엔진·감시를 다시 시작하세요." → " 다시 시작하세요."(PR #132 QA M15) | 잡힘 | `RuntimeConfigNotificationTest#재동의_안내는_토큰을_넣을_곳과_다시_시작할_프로세스와_그동안의_채널을_모두_알린다` |
| M2 | 같은 문구에서 "secrets.json의 " 삭제 | 잡힘 | autotrade-config 42건 중 1건 실패(같은 시험, 문자열 동일 비교) |
| M3 | `DISPLAY_ZONE` `Asia/Seoul` → `UTC` | 잡힘 | autotrade-config 1건 실패(`#알림_표시_시간대는_서울이다`) |
| M4 | `EngineMain`에서 `.kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP)` 삭제(빌더 일반 문구로 떨어짐) | 잡힘 | `EngineMainTest#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다` |
| M5 | `WatchdogMain`에서 같은 줄 삭제 | 잡힘 | `WatchdogMainTest#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다` |
| M6 | `NotificationSetup.start`가 `kakaoReconsentHelp` 대신 `DEFAULT_KAKAO_RECONSENT_HELP`를 큐에 넘김 | 잡힘 | autotrade-notification-jar 77건 중 1건 실패 |
| M7 | 지운 공개 5인자 생성자(일반 문구로 위임)를 그대로 되살림 | **생존** | `./gradlew build` 통과. 공개 생성자 개수·모양을 고정하는 시험 없음 → 권고 Q1 |
| M8 | 빌더 `kakaoReconsentHelp(v)`가 넘긴 값을 버리고 늘 일반 문구 | 잡힘 | `NotificationSetupTest#재동의_문구는_부르는_쪽이_넘긴_것을_쓰고_없으면_일반_문구를_쓴다`, `#같은_값이면_같고_값이_하나라도_다르면_다르다` |

## 이 PR의 범위(판정의 전제)
- [사실] 보호 경로 `autotrade-config/*`(scripts/ci/protected-paths.txt:11), `autotrade-notification-jar/*`(:16), `autotrade-app-engine` 시험을 바꾸므로 실전 관련 PR이다.
- [사실] 운영 코드 변경은 `NotificationQueue`의 공개 5인자 생성자 삭제와 Javadoc 이동뿐이다(NotificationQueue.java:82-96). 남은 공개 생성자는 문구를 받는 6인자 하나(:93-96), 패키지 비공개 생성자 둘(:98-100, :102-111)은 그대로다.
- [사실] 주문 출구, RealTradingGuard, 블랙리스트·수동 보유, 주문 큐·토큰 버킷, 시세 게이트웨이·보호 시세, 서버 측 손절, 장애 대응 코드와 보호 테스트(`autotrade-architecture-test` 전체, RealTradingGuard·GuardedOrderGateway 테스트, 장애 테스트) 파일은 diff에 없다(`git diff origin/main...HEAD --stat`).
- [사실] 사용자에게 가는 재동의 문구·표시 시간대 값(RuntimeConfig.java:73, 76-78)과 큐의 억제·상한·닫기 로직은 diff에 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·기동 코드 diff 없음. 바뀐 생성자는 알림 큐만 만든다. 이번 빌드에서 autotrade-risk(RealTradingGuardTest 포함)와 `EngineMainTest` 13건(기동 직후 OFF 시험 포함) 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 규칙·고정 값(허용 목록·소유 표·지문) diff 없음. 지운 생성자는 다른 생성자로 위임만 하던 코드라 새 경로가 생기지 않는다. autotrade-architecture-test(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest) 빌드 안에서 통과 |
| A3 | 해당 없음 | 증권사 주문 연동·알림 채널 구성 코드 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 우선 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 통과 | 주문 큐·토큰 버킷·킬스위치 변경 없음. 알림 `offer`·억제 로직 diff 없음. `NotifyingCommandAuditLogTest`가 6인자로 같은 큐를 만들고 단언은 그대로이며 통과 |
| A8 | 통과 | 엔진·감시 기동 순서·프로세스 의존 변경 없음. 6인자 생성자는 문구가 null이면 던지지만(NotificationQueue.java:110) 운영 호출부 `NotificationSetup.start`(:162)는 빌더가 null·빈 값을 일반 문구로 바꾼 값을 넘기므로(NotificationSetup.java:79, 139-143) 기동 때 던질 수 없다. 이 정규화는 변이 M8로 시험이 지킴을 확인 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 추가된 줄의 문자열은 설정 키 이름 `notification.kakao.refresh-token`과 안내 문구뿐, 값 없음(RuntimeConfigNotificationTest.java:15-17). CI "Secret scan and collector boundary"(gitleaks) success |
| A11 | 통과 | 보호 테스트 파일 diff 없음, `@Disabled`·`assume` 추가 없음, 고정 값 변경 없음. 기존 시험에서 바뀐 줄은 `NotifyingCommandAuditLogTest.java:42-43` 생성자 호출 하나(지운 5인자가 넘기던 것과 같은 `DEFAULT_KAKAO_RECONSENT_HELP`를 명시, 단언 불변). 새 시험이 PR #132 QA M15를 잡음을 직접 확인(M1), 문구 다른 단계(M2)·시간대(M3)도 잡힘 |
| A12 | 통과 | 장애표 감지·알림 동작과 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음. 재동의 알림 문구는 값이 같고 이번에 시험으로 고정됐다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. `DISPLAY_ZONE`은 알림 표시용(RuntimeConfig.java:69-73 주석) |
| A14 | 통과 | D-100 반영 기록 추가분(D-100-notification-wiring-watchdog-engine.md:86-88)이 코드와 일치: 시험 이름(RuntimeConfigNotificationTest.java:12), 공개 생성자 6인자 하나(NotificationQueue.java:93-96), 5인자를 쓰던 곳이 엔진 시험 하나(`git grep`으로 확인). 결정 내용 불변이라 새 D 번호 없음에 동의, R1 삭제는 "추천안 채택(자율)"과 대안·되돌릴 조건 표시. ADR-001:56은 이미 "재동의 문구는 부르는 쪽이 넘김"이라 갱신할 문장 없음. 설정 키 변경 없음. main(184a9f3)과 충돌 없고 병합 빌드 통과 |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL(헤드 1,447건, main 병합 1,464건, 실패 0) |
| B6 | 통과 | 새 시험 2개(`RuntimeConfigNotificationTest`). 건너뛴 시험 0, 지운 시험 없음. 변이 8개 중 7개 잡힘, 생존 M7은 권고 Q1 |

## 점검표 밖 발견

### 위험 시나리오
1. **운영 호출부가 재동의 문구를 빠뜨림**: 엔진이나 감시가 `.kakaoReconsentHelp(...)`를 빼면 빌더가 조용히 일반 문구로 채운다(NotificationSetup.java:79). 컴파일은 막지 못하지만 `EngineMainTest`·`WatchdogMainTest`가 잡는다(M4·M5 직접 확인). 결과는 문구 품질 저하이고 주문·보호 공백은 아니다. → 권고 R1
2. **공개 5인자 생성자 재도입**: 누군가 되살려도 빌드가 통과한다(M7 생존). 그 생성자를 새 운영 호출부가 쓰면 시나리오 1과 같은 문구 저하가 시험 없이 생길 수 있다. 지금 운영 호출부는 `NotificationSetup.start` 한 곳이다. → 권고 Q1
3. **null 문구로 큐 생성 실패 → 엔진·감시 기동 실패**: 6인자 생성자는 null에 던진다(NotificationQueue.java:110). 공개 경로로 null이 오려면 빌더를 거치지 않고 6인자를 직접 불러야 하는데 운영 코드에 그런 호출이 없고(`git grep`), 빌더는 null을 일반 문구로 바꾼다(M8로 시험 확인). 재현 경로 없음.
4. **main의 PR #135(실험실 알림 시험 보내기)와의 병합**: `NotificationTester`는 `NotificationQueue`를 직접 만들지 않는다(`git grep`). 병합 트리 빌드 1,464건 통과로 컴파일 충돌 없음을 확인.

### 권고(판정 영향 없음)
- Q1(변이 M7): `NotificationQueue`의 공개 생성자가 문구를 받는 6인자 하나뿐임을 고정하는 시험이 없다. D-100:88의 삭제 이유("남기면 부르는 쪽이 문구를 빠뜨려도 컴파일이 막지 못한다")를 지키려면 리플렉션으로 공개 생성자 수·인자 모양을 고정하는 시험 하나면 된다. 대안은 지금처럼 리뷰에 맡기는 것이다.
- R1(architect R1과 같음): 실제 운영 경로인 `NotificationSetup.Builder`는 `kakaoReconsentHelp`가 없거나 비면 여전히 일반 문구로 채운다(NotificationSetup.java:79, 141). D-100:88의 이유를 끝까지 적용하려면 `displayZone`처럼 필수로 두는 편이 일관된다. 지금은 M4·M5가 시험으로 막는다.
- R2(참고): PR 브랜치는 main a2f94ac 기준이고 main은 184a9f3까지 나아갔다. 충돌·컴파일 문제는 없으나 머지 전 CI는 병합 결과로 다시 돈다.

## 판정
승인
