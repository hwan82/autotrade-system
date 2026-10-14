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

## 재검토 2026-10-14
- 대상: PR #96 / head `c595938` / `git diff origin/main...HEAD` 전체(커밋 3개 `e2f1ca5`·`33e0534`(검토 원문)·`c595938`). 이번 수정 커밋 `c595938`의 변경: LiveLabService(`finish` 마감 실패 처리, `ReportNotWrittenException`), LiveSessionController(그 예외만 `REPORT_NOT_WRITTEN`), LiveLabServiceTest(예외 타입), LiveSessionControllerTest(시험 1건 추가), live.js(등록 목록 폴링 제외), ADR-005:118, ADR-010:49·:105. 검토 원문(`33e0534`)은 내가 쓴 내용 그대로임을 확인했다(`git diff 33e0534 c595938 -- docs/reviews/` 비어 있음).
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더(`c595938`, 깨끗한 상태)에서 `./gradlew build --offline`: **BUILD SUCCESSFUL**(24초). 테스트 결과 XML 합계 1067건, 실패 0·오류 0·건너뜀 0. `autotrade-app-lab:test` 실행, `LiveLabServiceTest` 10건, `LiveSessionControllerTest` 3건 통과. `autotrade-architecture-test:test`는 UP-TO-DATE(같은 입력으로 이미 통과한 결과).
  - 임시 시험(시험 파일에 넣어 돌린 뒤 `git checkout`으로 되돌림): R1의 "보고서 전" 실패 경로. 이벤트 1건을 받은 뒤 세션 폴더의 `events.jsonl.gz`를 지워 `DayWriter.close`의 SHA-256 계산을 실패시키고 `LiveSessionController.finish()` 호출 → 던져진 것은 `ApiException`이 아닌 `UncheckedIOException`(메시지 `녹화 파일을 닫을 수 없습니다: /tmp/junit-…/live-sessions/…`, 공통 처리기 `ApiExceptionHandler.unexpected`가 메시지 없이 500 `INTERNAL`로 바꾼다, ApiExceptionHandler.java:53-63), 상태 `stopReason`은 `끝내기 실패(UncheckedIOException). 실험실 로그를 확인하세요`로 절대 경로 없음, `experimentId` 빈 문자열. 통과.
  - 저장소 작업 폴더는 이 파일 외에 바꾸지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --offline` 실행, BUILD SUCCESSFUL, 테스트 1067건 실패·건너뜀 0 |
| B2 | 통과 | 새 클래스 `LiveLabService.ReportNotWrittenException`은 `autotrade-app-lab`의 기존 패키지 안 중첩 클래스(LiveLabService.java:262-268). 순수 모듈 변경은 첫 검토와 같은 `LiveSession.recentGaps` 하나. ArchUnit·모듈 소유·순수 의존 검사 통과 |
| B3 | 통과 | ADR-010:49에 `GET /registrations`·`GET /gaps`와 화면 `#/live`, ADR-010:105 후속 표를 실제 상태로 갱신. ADR-005:118이 `REPORT_NOT_WRITTEN`(보고서 파일만 실패)과 공통 500 `INTERNAL`(마감·기록 닫기 실패, `stopReason`에 `끝내기 실패(예외 종류)`)을 나눠 적고, 코드(LiveLabService.java:235-254, LiveSessionController.java:74-78)와 임시 시험 결과가 그 설명과 맞는다 |
| B4 | 통과 | 수정은 예외 분류와 화면 폴링 범위만. 시계·난수·계좌 처리 순서 변경 없음. 골든 테스트 포함 전체 통과 |
| B5 | 통과 | 금액 계산 없음(첫 검토와 같음) |
| B6 | 통과 | `LiveSessionControllerTest#reportWriteFailureIsReportNotWrittenWithoutAbsolutePath`가 500·`REPORT_NOT_WRITTEN`·저장소 상대 경로·임시 폴더 절대 경로 없음·`stopReason`을 확인. `LiveLabServiceTest#reportWriteFailureAfterFinishIsExplainedAndLeftInStatus`는 새 예외 타입으로 강화. 지우거나 끈 시험 없음. 마감·기록 닫기 실패 분기(LiveLabService.java:237-243)는 저장소 시험이 없고 검토자 임시 시험으로만 확인함(아래 권고 N1) |
| B7 | 해당 없음 | 실험실 모의 세션 API·화면만. 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응, 증권사 주문 연동, 보호 테스트 변경 없음 |
| B8 | 통과 | 비밀값 없음. 새 로그는 실험 ID와 예외 클래스 이름만(LiveLabService.java:241). 사유 문자열에 예외 메시지를 넣지 않아 절대 경로가 화면에 나가지 않음(임시 시험으로 확인) |

## 점검표 밖 발견(재검토)
- **R1 해결.** 보고서 쓰기 실패만 전용 예외로 던지고 컨트롤러는 그 타입만 `REPORT_NOT_WRITTEN`으로 바꾼다. 마감·기록 닫기 실패는 `stopReason`을 남기고 그대로 던져 공통 500(메시지 숨김)으로 간다. `IllegalStateException`(예: 이미 닫힌 세션)이면 컨트롤러의 409 분기로 가며 메시지에 경로가 없다.
- **R2 해결.** 위 B3.
- **R3 해결.** 3초 폴링은 `refresh(false)`로 등록 목록을 읽지 않고, 화면 진입·새로 읽기·명령 뒤(`act`의 `refresh()`), 세션이 끝나 목록으로 돌아올 때(`active`면 `regs=null`, 다음 대기 화면에서 `regs===null`이면 읽음)만 읽는다(live.js `refresh`). 감시 정지 등으로 세션이 화면 밖에서 끝난 경우도 목록이 다시 읽힌다.
- **R4·R5**는 첫 검토대로 권고로 남는다(막지 않음).
- **N1 (권고, 막지 않음)** 마감·기록 닫기 실패 분기 시험을 저장소에 넣기를 권한다. 재현 방법: 이벤트 1건 뒤 세션 폴더의 `events.jsonl.gz`를 지우고 `finish` → `UncheckedIOException`, `stopReason`이 `끝내기 실패(UncheckedIOException)`로 시작하고 설정 폴더 경로를 담지 않음, 컨트롤러에서 `ApiException`이 아님. 검토자 임시 시험으로 동작은 확인했다.
- **N2 (참고, 이 PR 밖 기존 동작)** 되살리기 실패 사유는 `"시작 실패: " + e.getMessage()`(LiveLabService.java:182)라 `LiveSession.open`의 `세션 폴더를 열 수 없습니다: <절대 경로>` 같은 메시지가 `stopReason`으로 화면에 보일 수 있다. 이 PR이 만든 동작은 아니고 로컬 전용 화면이며 비밀값도 아니다. 다음에 이 부분을 손볼 때 끝내기 실패와 같이 예외 종류만 남기는 쪽으로 맞추기를 권한다.

## 판정
승인
