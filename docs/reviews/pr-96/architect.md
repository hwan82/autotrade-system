# architect 검토: 실험실 실시간 모의 화면(#/live)과 start 읽기 실패 409 정리
- 대상: PR #96 / 커밋 `e2f1ca5` / `git diff origin/main...HEAD` 전체(커밋 1개, 파일 14개: LiveLabService, LiveSessionController, LiveLabServiceTest, LiveSessionControllerTest(새 파일), DemoLabApplication(테스트), LiveSession, LiveSessionTest, autotrade-ui README·app.css·index.html·app.js·pages/live.js(새 파일), ADR-005, stage-6-live-session 절차서)
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더에서 `./gradlew build --offline`: **BUILD SUCCESSFUL**(38초, 130 task, 22 실행). 테스트 결과 XML 합계 1066건, 실패 0·오류 0·건너뜀 0. `autotrade-app-lab:test`·`autotrade-architecture-test:test` 실행됨, `autotrade-sim:test`는 UP-TO-DATE(입력 동일). `LiveLabServiceTest` 10건, `LiveSessionControllerTest` 2건, `LiveSessionTest` 10건 통과.
  - 임시 시험(시험 파일에 넣어 돌린 뒤 `git checkout`으로 되돌림, 작업 폴더 변경 없음): ADR-005가 주장하는 "보고서를 못 쓴 뒤 다시 시작하면 되살린 뒤 끝내기를 다시 할 수 있음"을 확인했다. 보고서 자리에 폴더를 두어 `finish()`를 실패시키고 → 폴더를 지운 뒤 → `registrations()`가 `finished=false, hasJournal=true` → `start` → 되살리기 끝 → `finish()` 성공, `live-detail.txt` 생김, 실패 계좌 0. 통과.
  - 코드 읽기: `LiveSession.finish → close → JournalWriter.close(Instant) → DayWriter.close(Instant)` 경로의 예외 종류(아래 R1).
  - 저장소에는 이 파일만 새로 생겼다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --offline` 실행, BUILD SUCCESSFUL, 테스트 1066건 실패·건너뜀 0 |
| B2 | 통과 | 순수 모듈 변경은 `autotrade-sim`의 `LiveSession.recentGaps(int)`(LiveSession.java:369-374) 하나로 새 의존 없음. Spring 연결은 `autotrade-app-lab`에만(LiveSessionController.java). 새 패키지·새 모듈 없음. `ArchitectureTest`·`ModuleOwnershipTest`·`verifyPureDependencies` 통과 |
| B3 | 실패 | REST 계약 변경: `GET /api/live/registrations`·`GET /api/live/gaps` 추가, `View`에 `profiles` 추가, `start` 읽기 실패 400/500→409, `finish` 500 `REPORT_NOT_WRITTEN` 추가. ADR-005 API 표는 같은 PR에서 갱신됨(ADR-005-ui-dashboard.md:113-118). 그러나 (1) 실시간 모의 API의 원 계약 문서인 ADR-010이 그대로다: `docs/adr/ADR-010-live-feed-and-session.md:49`는 API를 4개만 적고 "화면은 후속(ui-dev)", `:105` 후속 표는 "실시간 모의 화면 … API만 / ui-dev"로 남아 있다. D-076:46도 화면을 후속으로 적는다. (2) ADR-005:118의 `REPORT_NOT_WRITTEN` 설명("입력 기록은 정상 종료로 닫혀")은 R1 경로에서 사실이 아니다 |
| B4 | 통과 | 새 서버 코드는 읽기 전용(공백 목록 복사, 등록 파일 나열). 시계·난수 사용 없음. 나열 순서는 `Comparator.reverseOrder()`로 명시(LiveLabService.java:331), 공백 순서는 기록 순서를 뒤집기만 함(:295-309). 계좌 처리·기록 순서 변경 없음. 골든 테스트 포함 전체 통과 |
| B5 | 통과 | 금액 계산 없음. 화면은 서버 `Money` 문자열을 `money()`로 표시만(live.js:accountsTable, format.js:4 "계산하지 않음") |
| B6 | 통과 | 새 동작 시험: `LiveLabServiceTest#unreadableRegistrationIsARefusalNotAServerError`, `#listsLiveRegistrationsOnly`, `#showsRecentGapsNewestFirst`, `#reportWriteFailureAfterFinishIsExplainedAndLeftInStatus`, `LiveSessionControllerTest#unreadableRegistrationIsConflict`, `#badRequestsAreRefused`, `LiveSessionTest` 공백 단언 추가(LiveSessionTest.java:158-163). 지우거나 끈 시험 없음. 단, 컨트롤러의 `UncheckedIOException → 500 REPORT_NOT_WRITTEN` 매핑(LiveSessionController.java:75-77)은 시험이 없다(R1과 함께 보강 권고). 화면 JS는 시험 기반이 없어 시험 없음(기존 화면과 같음) |
| B7 | 해당 없음 | 실험실 프로세스의 모의 세션 API와 화면만 바꿈. 실전 주문 경로, RealTradingGuard, GuardedOrderGateway, 블랙리스트·수동 보유, 리스크 한도, 장애 대응, 증권사 주문 연동 변경 없음. 보호 테스트 변경 없음. live.js는 `/api/live/*`만 부름(실전 명령 채널 `/api/real*` 호출 없음) |
| B8 | 통과 | diff에 API Key·계좌번호·토큰 없음. 데모 등록 파일은 종목코드·가상 시드만(DemoLabApplication.java:298-312). 새 로그는 경로와 예외 클래스 이름만(LiveLabService.java:243) |

## 점검표 밖 발견

- **R1 (수정 필요) `finish`의 모든 `UncheckedIOException`을 "보고서만 못 씀"으로 분류하고 그 메시지를 그대로 내보낸다.**
  - 컨트롤러는 `service.finish()`의 `UncheckedIOException`을 전부 잡아 500 `REPORT_NOT_WRITTEN`으로 바꾸고 `e.getMessage()`를 응답에 넣는다. 주석은 "메시지는 서비스가 만든 것(경로는 저장소 상대, 입력값 없음)"이라고 적는다(LiveSessionController.java:75-77).
  - 그러나 같은 호출 안의 `r.session.finish(code)`(LiveLabService.java:235)도 `UncheckedIOException`을 던질 수 있다: `LiveSession.finish → close()`(LiveSession.java, `close()` 안 `journal.close(clock.instant())`) → `JournalWriter.close(Instant)`(JournalWriter.java:121-129) → `DayWriter.close(Instant)`가 gzip 닫기·SHA-256·meta 쓰기 실패 시 `new UncheckedIOException("녹화 파일을 닫을 수 없습니다: " + dir, e)`(DayWriter.java:240-251). `dir`은 설정 폴더 아래 절대 경로(사용자 홈 경로 포함)다.
  - 이 경우 (a) 응답 메시지에 절대 경로가 나가 `ApiExceptionHandler`의 "메시지는 남기지 않는다" 원칙(ApiExceptionHandler.java:55-63)과 주석이 어긋나고, (b) 코드는 "보고서만 못 씀"인데 실제로는 기록을 정상 종료로 닫지 못한 것이며(meta `complete`가 안 써졌을 수 있음), (c) `running`은 이미 null이고(LiveLabService.java:233) `lastStopReason`은 갱신되지 않아 상태 화면에 아무 사유도 남지 않는다. ADR-005:118의 "입력 기록은 정상 종료로 닫혀" 설명도 이 경로에서는 틀리다.
  - 고치는 방법(추천): 서비스에서 보고서 쓰기 실패만 전용 예외(예: `LiveLabService.ReportNotWrittenException extends RuntimeException`, 메시지는 서비스가 만든 저장소 상대 경로만)로 던지고 컨트롤러는 그 타입만 `REPORT_NOT_WRITTEN`으로 매핑한다. `session.finish`의 실패는 `try/catch (RuntimeException)`로 `lastStopReason = "끝내기 실패(<예외 클래스>): 기록을 닫지 못했습니다"`를 남기고 다시 던져 공통 500(메시지 숨김)으로 보낸다. 대안(컨트롤러에서 메시지 접두어로 구분)은 문자열 결합이라 버림. 시험: 컨트롤러 `finish`가 보고서 실패 시 500 `REPORT_NOT_WRITTEN`이고 메시지에 임시 폴더 절대 경로가 없음을 확인하는 시험 1건.
- **R2 (수정 필요, 문서) ADR-010과 D-076의 "화면 후속" 표기 갱신.** ADR-010:49의 실험실 API 목록에 `registrations`·`gaps`를 더하고(상세는 ADR-005 링크로 충분), "화면은 후속(ui-dev)"과 :105 후속 표 행을 이 PR로 바뀐 상태(화면 `#/live`, 3초 폴링, SSE는 UI-01 이연)로 고친다. D-076:46은 결정 로그라 직접 고치지 않아도 되며 ADR-010 갱신으로 충분하다. ADR-005:118은 R1 수정 뒤 실제 동작에 맞춘다.
- **R3 (권고, 막지 않음) 대기 화면이 3초마다 모든 실험 폴더의 `experiment.yaml`을 읽는다.** 세션이 없을 때 폴링마다 `registrations()`가 `experiments/` 전체를 나열하고 파일을 읽는다(LiveLabService.java:325-366, live.js `refresh`). 조합 실험 폴더가 수백 개로 늘면 실험실의 불필요한 디스크 I/O가 된다[추론]. 실험실은 자원 부족 시 먼저 양보하는 프로세스이므로(절대 규칙 6) 등록 목록은 화면 진입·새로 읽기·명령 뒤에만 읽고, 3초 폴링은 `status`(와 세션 중 `gaps`)만 하기를 권한다.
- **R4 (권고) 화면 시각대가 `'KRX'`로 고정이다.** live.js는 `time(…, 'KRX')`를 공백·마지막 이벤트에 고정으로 쓴다. 지금 `LivePlan`이 KRX만 받으므로(LivePlan.java:80-81) 동작은 맞다. 해외 실시간 모의를 열 때는 `Status`나 `View`에 `market`을 실어 화면이 그것을 쓰게 해야 한다(overseas-design 시간 표시 규칙). 해외 지원 PR의 할 일로 남긴다.
- **R5 (참고) 화면이 서버 문자열 `'끝냄'`·`'화면에서 멈춤'`으로 경고 색을 고른다(live.js idleView).** 서버 문구를 바꾸면 조용히 경고 색으로 바뀐다. 해롭지 않지만, 나중에 `View`에 멈춘 이유 종류(코드)를 두면 더 단단하다.
- **참고**: 동시성. `gaps()`는 서비스 잠금 → 세션 잠금 순서로, 기존 `status()`와 같은 순서라 교착 위험을 새로 만들지 않는다. `registrations()`는 잠금 없이 파일만 읽어 세션 처리와 경쟁하지 않는다. 폴링이 세션 잠금을 잡는 시간은 공백 최대 200건 복사로 짧다.
- **참고**: XSS·CSRF. live.js는 `h()`(textContent)만 쓰고 `innerHTML` 류가 없다. 상태 변경 요청은 `post()`로 CSRF 토큰을 붙인다(api.js:12). 화면을 떠나면 폴링을 멈춘다(hashchange와 `document.body.contains(host)` 이중 확인).
- **참고**: `start` 읽기 실패를 `IllegalStateException`(409)으로 바꾼 것은 사용자 고칠 수 있는 상태라 타당하다. 메시지는 예외 클래스 이름과 저장소 상대 경로만 담아 절대 경로·파일 내용이 나가지 않는다(LiveLabService.java:152-156).

## 판정
수정 필요(R1: `finish`의 `UncheckedIOException` 분류·메시지 노출과 상태 사유 누락, 컨트롤러 시험 보강 / R2: ADR-010 화면·API 표기와 ADR-005:118 설명 갱신)
