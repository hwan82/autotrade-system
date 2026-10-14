# architect 검토: 실시간 모의 화면 후속(PR #96 N1·N2·R4·R5)
- 대상: PR #97 / 커밋 e50677e / main 대비 diff(`git diff origin/main...HEAD`, 4개 파일: LiveLabService.java, LiveLabServiceTest.java, live.js, ADR-005)
- 점검표: B
- 직접 실행한 것: `./gradlew build` 성공(BUILD SUCCESSFUL in 1m 16s, 130 tasks). 앞선 시도는 Maven Central 429(Too Many Requests)로 의존성 내려받기에 실패했고, 같은 명령을 재시도해 통과했다(코드와 무관). `LiveLabServiceTest` 결과 XML: tests=12, skipped=0, failures=0, errors=0. `node --check live.js` 출력 없음(문법 오류 없음). PR 본문·커밋 메시지는 근거로 쓰지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 결과. 순수 모듈 금지 의존 검사(verifyPureDependencies)와 ArchUnit 포함 전부 통과 |
| B2 | 통과 | 변경은 `autotrade-app-lab`(LiveLabService)와 `autotrade-ui` 정적 파일뿐. 새 의존·모듈 이동·ArchUnit 규칙 변경 없음 |
| B3 | 통과 | REST `GET /api/live/status`의 `View`에 `market`·`stopKind` 추가(필드 추가만). docs/adr/ADR-005-ui-dashboard.md:113 표가 같은 PR에서 두 필드와 `시작 실패(예외 종류)`·`시작 실패: <정지 사유>` 문구, 절대 경로 미포함을 기술한다. 소비자(live.js)도 같은 PR에서 갱신됐고 View 생성자를 쓰는 다른 코드는 저장소에 없다(grep). 새 ADR 없이 ADR-005 갱신으로 충분하다고 판단 |
| B4 | 통과 | 시계·난수 신규 사용 없음. 시뮬레이션 골든 테스트는 빌드에서 통과. 변경은 화면용 상태 표시이며 시뮬레이션 결과에 영향 없음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 테스트 `finishFailureBeforeTheReportLeavesOnlyTheExceptionKind`(N1: 마감 실패 분기, 경로 미노출, PROBLEM), `startFailureShowsTheExceptionKindWithoutPaths`(N2: 시작 실패, 경로 미노출). 기존 테스트에 market(KRX/빈 문자열)·stopKind(NONE/NORMAL/PROBLEM) 단언 추가. 삭제·@Disabled 없음 |
| B7 | 해당 없음 | 실시간 모의 상태 표시와 화면만 바뀐다. 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응, 증권사 주문 연동을 건드리지 않았다(diff 전체 확인). risk-ops 검토 필요 없음 |
| B8 | 통과 | 비밀값 없음. 시작 실패 사유에서 예외 메시지(절대 경로 가능)를 화면에서 빼고 로그에만 남긴다(LiveLabService.java:185-191) |

## 점검표 밖 발견
- 상태 전이 확인: `stopKind`를 코드로 따라가 봤다. start에서 NONE 초기화(163), 사용자 stop은 NORMAL(215), 감시 정지 요청은 PROBLEM(543), 세션 스스로 멈춤은 PROBLEM(308), 끝내기 성공 NORMAL(274), 끝내기·보고서 실패 PROBLEM(257, 268), 시작 실패 PROBLEM(189). 되살리는 중 사용자가 멈추면 stop()이 running을 비운 뒤라 시작 실패 분기가 덮어쓰지 않는다(`running == r` 조건, 182).
- 의견(차단 아님) 1: `gateStop`은 되살리기 스레드만 쓰고 같은 스레드의 catch에서 읽으므로 비휘발이어도 안전하다.
- 의견(차단 아님) 2: 시작 실패 때 로그에 `e.getMessage()`(경로 포함 가능)를 남긴다. 로컬 실험실 로그이고 비밀값이 아니라 경로이므로 B8 위반으로 보지 않는다. 예외 메시지에 비밀값이 섞일 수 있는 경로가 생기면 다시 봐야 한다.
- 의견(차단 아님) 3: `View.market`은 세션이 없으면 빈 문자열이라 live.js의 `view.market || 'KRX'` 기본값에 의존한다. 세션 화면은 세션이 있을 때만 그려지므로 현재는 문제 없다.
- 확인 못 한 것: live.js의 화면 동작(경고 색, US 시장 시각 표시)은 `autotrade-ui`에 JS 테스트가 없어(`:autotrade-ui:test NO-SOURCE`) 브라우저에서 실행해 보지 못했다. 문법 검사와 `format.js:2,41`의 `time(iso, market)`가 KRX·US를 지원함을 코드로만 확인했다. B1~B8 항목 판정에는 영향이 없다.

## 판정
승인
