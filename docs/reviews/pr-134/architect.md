# architect 검토: 알림 재동의 문구 전체 시험·공개 5인자 NotificationQueue 생성자 삭제(PR #132 QA Q1·R1)
- 대상: PR #134 / 커밋 0a0e8c4 / `git diff origin/main...HEAD` 전체(커밋 1개, 파일 4개, +35 −7)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --init-script <저장소 밖 미러 init 스크립트>` → BUILD SUCCESSFUL in 4m 50s, 138 tasks executed(전체 테스트, 순수 모듈 금지 의존 검사, ArchUnit 포함). init 스크립트는 Maven Central 429를 피하려고 저장소 미러만 지정하며 저장소 파일은 바꾸지 않았다.
  - 테스트 결과 XML 확인: `RuntimeConfigNotificationTest` tests=2 skipped=0 failures=0, `NotifyingCommandAuditLogTest` tests=2 skipped=0 failures=0, `NotificationQueueTest` tests=16 skipped=0 failures=0.
  - `grep -rn "new NotificationQueue("`: 공개 6인자 호출은 `NotificationSetup.java:162`(운영)와 `NotifyingCommandAuditLogTest.java:40`(시험)뿐이고, 나머지는 같은 패키지 시험이 부르는 패키지 비공개 생성자(`NotificationQueueTest.java:71,327,349`)다. 지운 5인자를 부르는 곳은 남지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL |
| B2 | 통과 | 새 의존 없음. 시험 파일 추가(`autotrade-config` 테스트, 이미 있던 assertj만 사용)와 알림 모듈 생성자 삭제뿐. `ArchitectureTest`·`ModuleOwnershipTest`가 빌드 안에서 통과. 규칙 변경 없음 |
| B3 | 통과 | 설정 키, 스키마, REST 변경 없음. 바뀐 계약은 `autotrade-notification-jar`의 공개 생성자 하나 삭제(`NotificationQueue.java:83-97`). ADR-001:56 모듈 표가 이미 "재동의 문구는 부르는 쪽이 넘김"으로 적고 있어 갱신할 문장이 없다. 결정 근거, 대안, 되돌릴 조건은 `D-100-notification-wiring-watchdog-engine.md:86-89`에 있다. 이 라이브러리를 저장소 밖에서 쓰는 곳은 없다(D-099) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 시험 시계는 `Clock.fixed`(`NotifyingCommandAuditLogTest.java:42`) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 시험 `RuntimeConfigNotificationTest#재동의_안내는_토큰을_넣을_곳과_다시_시작할_프로세스와_그동안의_채널을_모두_알린다`가 문구 전체를 `isEqualTo`로 고정한다. 그래서 QA 변이 M15(" 실전 엔진·감시를 다시 시작하세요." 삭제)가 이제 잡힌다(문자열 동치 비교라 코드 읽기로 확정). `#알림_표시_시간대는_서울이다`도 추가. 바뀐 기존 시험은 `NotifyingCommandAuditLogTest`의 생성자 호출 하나다. 지운 5인자가 넘기던 값과 같은 `DEFAULT_KAKAO_RECONSENT_HELP`를 명시했을 뿐 단언은 그대로다. 건너뛴 시험 0, 지운 시험 없음 |
| B7 | risk-ops 검토 필요 | 실전 경로 런타임 모듈을 바꾼다. `autotrade-notification-jar/*`, `autotrade-config/*`, `autotrade-app-engine/*`는 `scripts/ci/protected-paths.txt`의 보호 대상이다. 바뀐 것은 실전 엔진·감시가 쓰는 알림 큐의 공개 생성자와 시험이고, 주문·Guard·블랙리스트 코드는 diff에 없다. 프로젝트 지침 10·11장에 따라 risk-ops 검토, 사용자 머지, 머지 전 독립 QA가 필요하다 |
| B8 | 통과 | 새 문자열은 설정 키 이름(`notification.kakao.refresh-token`)과 안내 문구뿐이다. 토큰, 키, 계좌번호 없음. 커밋 메시지에도 없음 |

## 점검표 밖 발견
- R1(권고, 판정에 영향 없음): D-100:89에 적은 삭제 이유("남기면 앞으로 부르는 쪽이 문구를 빠뜨려도 컴파일이 막지 못한다")가 운영 경로인 `NotificationSetup.Builder`에는 아직 적용되지 않는다. 빌더는 `kakaoReconsentHelp`를 지정하지 않으면 일반 문구로 채운다(`NotificationSetup.java:79`, 빈 값도 일반 문구 `:141`). 지금은 운영 호출부 둘(`EngineMain.java:142`, `WatchdogMain.java:139`)이 값을 넘기고 `EngineMainTest:277-283`·`WatchdogMainTest:103-109`가 이를 시험하므로 영향은 없다 `[사실]`. 같은 이유를 끝까지 적용하려면 빌더에서도 `displayZone`처럼 필수로 두고 `build()`에서 비었으면 던지는 편이 일관된다. 대안인 "빌더 기본값 유지"는 저장소 밖 사용처가 없어 기본값을 쓰는 곳이 시험뿐이므로 얻는 것이 작다. 다음 알림 PR에서 정하면 된다.
- R2(참고): 새 시험의 두 번째 단언(`contains(...)`, `RuntimeConfigNotificationTest.java:19-20`)은 바로 앞 `isEqualTo`가 이미 보장하므로 중복이다. 문구를 바꿀 때 어느 단계가 필수인지 알려 주는 문서 역할이 있어 남겨도 무방하다.
- R3(참고): 남은 공개 생성자의 Javadoc에 `@param clock`이 없다(`NotificationQueue.java:83-94`). 지운 생성자에도 없던 기존 빈틈이고 빌드를 막지 않는다.
- 패키지 비공개 `NotificationQueue(…, LogChannel)` 생성자(`NotificationQueue.java:99-101`)는 여전히 일반 문구로 위임한다. 다만 같은 패키지 시험만 부를 수 있어 R1의 취지(공개 API에서 문구 누락 차단)에 어긋나지 않는다.

## 판정
승인 (B7: 보호 경로 변경이므로 risk-ops 검토, 사용자 머지, 독립 QA가 별도로 필요)
