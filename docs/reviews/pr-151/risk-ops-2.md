# risk-ops 재검토: D-107 실전 엔진 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R2)
- 대상: PR #151 / 커밋 a210bf33fccf63544c8af2c905fe1baaf4c6a3f0(GitHub PR head와 같음) / `git diff origin/main...HEAD` 전체(코드 2파일 `EngineMain.java` +117/-13, `EngineMainTest.java` +136, 문서 D-107·결정 로그 README·runbook README·`docs/qa/pending.md`·검토 원문). 1차 원문 [risk-ops.md](risk-ops.md)(4e778bd, 승인) 뒤 추가된 a210bf3을 중심으로 diff 전체를 다시 봤다.
- 점검표: A
- 위치 메모: docs/reviews/README.md는 재검토를 같은 파일에 `## 재검토 <날짜>`로 덧붙이라고 하지만, 요청에 따라 별도 파일 `risk-ops-2.md`로 쓴다. 1차 원문은 고치지 않았다.
- 직접 실행한 것:
  - 다른 검토자와 같은 폴더를 쓰지 않도록 a210bf3을 별도 worktree로 떼어 `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL in 5m 8s**(138 tasks 모두 실행). 테스트 결과 XML 합계 1512건 / 실패 0 / 오류 0 / 건너뜀 0. 모듈별(실행/건너뜀/실패/오류): app-engine 62/0/0/0(`EngineMainTest` 20건, 새 2건 포함), risk 55/0/0/0(`RealTradingGuardTest`, `GuardedOrderGatewayTest` 포함), architecture-test 46/0/0/0, config 42/0/0/0, notification-jar 86/0/0/0. 1차 때 간헐 실패한 `NotificationQueueTest`도 이번엔 통과.
  - 변이 확인(같은 worktree, 확인 후 원복): M1 `configPaths`의 `catch (IllegalArgumentException)`를 다른 예외로 바꿈 → `#진입점은_설정_폴더_경로가_틀리면…`, `#진입점은_실제_설정_폴더_값이_경로_형식이_아니면…` 2건 실패(잡힘). M2 `EngineRefusal`의 `programError`를 true로 → `#프로그램_오류만_예외_흐름을_남기는_종류로_판정한다` 실패(잡힘).
  - `gh api repos/hwan82/autotrade-system/pulls/148`: open, merged=false. `git ls-tree origin/main docs/decisions/`에 D-106 파일 없음.

## a210bf3 변경 요지(코드로 확인한 것)
- `StartFailure`에 `boolean programError` 필드를 추가(EngineMain.java:273), `of`가 `EngineRefusal`·`IOException`은 false, 나머지는 true로 정함(:280-289). `report`는 문구 비교 대신 이 필드로 예외 흐름 기록 여부를 고름(:292-298). 종료 코드·문구·분류는 4e778bd와 같다.
- 시험 2건 추가: `#진입점은_실제_설정_폴더_값이_경로_형식이_아니면_설정_오류로_종료_코드_2로_끝낸다`(EngineMainTest.java:403, 운영과 같은 `ConfigPaths::defaultLocation`에 `autotrade.config-dir`=NUL 포함 값을 넣고 `finally`에서 원래 값 복구), `#프로그램_오류만_예외_흐름을_남기는_종류로_판정한다`(:425).
- 문서: D-107에 "PR #148 뒤에 머지" 줄과 검증 시험 목록 추가, `docs/qa/pending.md`에 PR #151 QA 요청 한 줄.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성은 그대로 `startGuard`(EngineMain.java:191-193, `new RealTradingGuard(...)`)뿐이고 a210bf3은 `StartFailure` 표시만 바꿈. 실패 경로는 `exit` 뒤 null을 돌려 엔진 객체가 남지 않음(:100-110). `EngineMainTest#진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다`(:326), `#진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다`(:340, 실패 뒤 재기동 시 모든 계좌 OFF), `RealTradingGuardTest` 전부 통과 |
| A2 | 통과 | diff에 `autotrade-risk`·주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음(`git diff origin/main...HEAD --stat`에서 코드 파일은 `EngineMain.java`, `EngineMainTest.java` 둘뿐). `:autotrade-architecture-test:test` 46건 통과 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 코드 변경 없음(엔진은 증권사 미연결 상태 그대로) |
| A4 | 해당 없음 | 주문·타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷 코드 변경 없음. 바뀐 것은 프로세스가 아예 뜨지 않는 경우의 표시뿐 |
| A8 | 통과 | 변경은 실전 엔진 진입점 안에서만, 다른 프로세스 의존 추가 없음(import는 `java.util.function` 3개, EngineMain.java:34-36). 알림 단계 실패는 명령 채널·잠금 전이라 소켓을 남기지 않음(`#진입점은_알림_채널_코드_실수…`가 확인). 명령 채널을 연 뒤 예외는 `System.exit(1)`로 끝나 OS가 잠금을 풂 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 없음/변경 없음 |
| A10 | 통과 | diff(테스트 픽스처 포함)에 키·계좌번호·토큰 없음. 새 시험 값은 `bad\u0000dir`뿐. 예외 흐름을 로그에 남기는 경우는 여전히 "프로그램 오류"만이고(`programError` true는 `of`의 마지막 분기 하나, :287-288) 그 메시지 출처 검토는 1차 A10과 같음(설정 로딩은 값을 싣지 않음, 채널 꾸리기 값 예외는 이유 코드로 바뀜). 설정 폴더 경로 오류 문구는 경로 문자열만 |
| A11 | 통과 | 보호 테스트 삭제·약화·`@Disabled` 없음: `EngineMainTest`는 추가만, 기존 `#소켓_파일을_지워도_두_번째_엔진은_잠금_때문에_뜨지_않는다`(:199) 통과. 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작 실패 경우 시험 7건(:340, :365, :376, :389, :403, :425 등)이고 변이 M1·M2가 잡힘을 직접 확인 |
| A12 | 통과 | 대응표 #8(실전 엔진 크래시) 감지는 하트비트 기준이고, 기동 실패가 하트비트 전에 끝나는 점은 변경 전과 같음(하트비트는 명령 채널 뒤, EngineMain.java:143). CI의 "두 번째 엔진은 0이 아님" 점검은 명령 채널 실패가 여전히 1이라 그대로. 장애 테스트 8종 전제(재기동 시 OFF, 중복 주문 0)와 O1~O8에 닿는 동작 없음 |
| A13 | 해당 없음 | 시장별 경로·시간·금액 계산 변경 없음 |
| A14 | 통과 | D-107 결정 1~5와 코드 일치(2/1/1 분류 :280-289, `IOException` 예전 문구, 로그·표준 오류 각 1줄 :292-298, `Error` 미처리). 검증 절의 시험 목록이 실제 시험 이름 7건과 일치. 결정 로그 README 112행, runbook README 종료 코드 표 반영. D-106 링크는 아래 1번 |

## 점검표 밖 발견
1. (머지 순서, 1차 1번 후속) D-107(5행 "PR #148 뒤에 머지한다")과 결정 로그 README 112행의 `D-106-watchdog-start-failure-kinds.md` 링크는 여전히 이 브랜치와 origin/main에서 깨져 있다(PR #148 open, 미머지). 이제 문서에 머지 순서가 적혀 있으므로 사용자 머지 때 #148을 먼저 머지하면 해소된다. 실전 동작과 무관해 판정을 막지 않는다.
2. (1차 2번 반영 확인) 실제 `ConfigPaths.defaultLocation` 경로 시험이 추가됐고 변이 M1로 진입점 연결까지 덮는 것을 확인했다. 이 시험은 JVM 전역 시스템 속성을 바꾸지만 `finally`로 복구하고, 저장소에 JUnit 병렬 실행 설정(`junit-platform.properties`, `maxParallelForks`)이 없어 같은 JVM의 다른 시험과 겹치지 않는다 `[사실]`. 나중에 병렬 실행을 켜면 이 시험을 격리해야 한다(권고).
3. (권고, 비차단) `#프로그램_오류만_예외_흐름을_남기는_종류로_판정한다`는 `programError` 필드 값만 본다. `report`가 이 필드를 무시하고 항상(또는 전혀) 예외 흐름을 남기도록 바뀌어도 잡는 시험은 없다(로그 출력을 보는 시험 없음). 비밀값 노출 면에서는 예외 흐름 기록 범위가 넓어지는 쪽이 위험이므로, 나중에 로그 캡처 시험을 하나 두길 권한다. 현 코드 동작은 맞다(:292-298).
4. (1차 3번, 범위 밖 그대로) 기동 실패는 외부 알림으로 가지 않고 감시는 ABSENT로만 본다. 7단계 자동 재기동(#8) 설계 때 함께 정할 것.

## 판정
승인
