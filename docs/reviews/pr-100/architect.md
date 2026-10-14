# architect 검토: 실전 운영 화면에 엔진의 설정 파일 거부 경고 표시(1단계 QA 권고 R3)
- 대상: PR #100 / 커밋 e3b49c6 / `git diff origin/main...HEAD` 전체(코드 6개, UI 1개, ADR-006, qa/pending.md)
- 점검표: B
- 직접 실행한 것: `./gradlew build --offline` -> BUILD SUCCESSFUL (130 tasks, 테스트·verifyPureDependencies·ArchUnit 포함). PR 본문·커밋 메시지는 근거로 쓰지 않고 diff와 코드만 봤다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 직접 실행, 성공 |
| B2 | 통과 | 새 모듈·의존 추가 없음. EngineStatus는 기존 autotrade-control 안, 엔진→control 방향 유지. ArchUnit·verifyPureDependencies 빌드에서 통과 |
| B3 | 통과 | EngineStatus 레코드 컴포넌트 추가(제어 채널 계약). docs/adr/ADR-006-real-control-channel.md 2.3 뒤에 configWarning 절 추가(표시 전용, 옛 엔진 응답은 빈 문자열). 설정 키·스키마·REST 경로 변경 없음. 기존 3인자·6인자 생성자를 유지해 호출부 호환 |
| B4 | 해당 없음 | sim/strategy 변경 없음. 새 now()·난수 없음 |
| B5 | 해당 없음 | 금액 코드 없음 |
| B6 | 통과 | EngineMainTest#설정_파일을_버리고_기동하면_상태_조회에_그_경고가_실린다(오타 키, 경로 미노출, 계좌 0개), 기존 정상 경로에 configWarning 빈 값 단언 추가; ControlChannelTest#설정_경고가_없는_옛_엔진의_상태는_빈_경고로_읽는다, #설정_경고는_상태와_함께_전달된다; RealOpsControllerTest#엔진이_설정_파일을_버리고_떴으면_상태에_그_경고가_보인다. 삭제·@Disabled 없음 |
| B7 | 확인 필요 | 실전 엔진 명령 처리(EngineCommandHandler, EngineMain.commandHandler)와 제어 채널 계약을 건드린다. **risk-ops 검토 필요**, 머지는 사용자 확인(프로젝트 지침 10·11장). docs/qa/pending.md에 독립 QA 요청이 이미 추가돼 있음. 내가 본 범위: configWarning은 status() 응답에만 쓰이고 Guard 전이·인증·명령 검증 경로에서 읽지 않는다(EngineCommandHandler.java 변경 3곳). Guard 기본 OFF·재기동 OFF·블랙리스트 코드는 diff에 없다 |
| B8 | 통과 | 경고 문자열은 ConfigLoader가 만들며 ConfigJson.describe가 예외 메시지(비밀값 가능)를 버리고 클래스명·JSON 경로만 남긴다(ConfigJson.java:38-49). 새 테스트가 설정 폴더 경로 미노출을 단언. 로그·커밋에 비밀값 없음 |

## 점검표 밖 발견
- (낮음) real.js의 경고 배너는 자동화된 테스트가 없다(autotrade-ui 테스트 NO-SOURCE). 값은 h() 텍스트 노드로 들어가 HTML 주입 위험은 보이지 않는다(innerHTML 사용 없음 확인). 화면 동작은 직접 실행해 확인하지 못했다.
- (낮음) 경고 문자열 길이 상한이 없다. 제어 채널 응답 크기 제한(64KB, ADR-006)은 있어 실질 위험은 낮다고 추정한다.
- 한계: 엔진이 설정을 거부하면 해당 계좌가 엔진에서 빠져 화면에서 내리기·PANIC도 못 한다. 배너는 이를 알림으로만 다룬다. 동작 변경은 이 PR 범위 밖이며 후속 검토 대상이다.

## 판정
승인 (B7은 risk-ops 검토와 사용자 머지·독립 QA가 별도로 필요. 이 승인은 architect 범위 한정)
