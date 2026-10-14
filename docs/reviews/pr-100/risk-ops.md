# risk-ops 검토: 실전 운영 화면에 엔진의 설정 파일 거부 경고 표시(1단계 QA 권고 R3)
- 대상: PR #100 / 커밋 e3b49c6 (브랜치 claude/project-thread-gayfxk) / `git diff origin/main...HEAD` 전체(9개 파일, +107 -7)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-control:test :autotrade-app-lab:test --offline` 실패 없이 종료. 테스트 결과 XML 합계: risk 55, architecture-test 31, app-engine 49, control 22, app-lab 156건, 실패·오류·건너뜀 모두 0.
  - 전체 `./gradlew build`(다른 모듈 포함)는 돌리지 않았다. 변경 범위 밖 모듈은 확인 못 함이므로 CI 결과를 따로 봐야 한다.
  - 변경 코드 정독: EngineMain.java:83,107-121, EngineCommandHandler.java:42-66,106-109, EngineStatus.java, ConfigLoader/ConfigJson.describe, ControlCodec(STRICT/LENIENT), real.js, dom.js.

## 변경 요약 (코드로 확인한 사실)
- `EngineStatus`에 `configWarning` 문자열 필드 추가(null은 빈 문자열). 3인자 생성자는 빈 경고로 유지.
- 엔진은 기동 때 `loaded.warning()`을 `EngineCommandHandler`에 넘겨 상태 스냅샷에 싣는다. Guard 전이·명령 검증·인증 코드는 이 값을 읽지 않는다(EngineCommandHandler.java에서 configWarning 사용처는 :45,:59,:66,:109 뿐).
- 실험실은 그대로 전달, UI는 `h()`(textContent)로 표시(dom.js:1, innerHTML 없음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·기본 OFF 경로(EngineMain.startGuard)는 diff에 없음. 새 테스트 EngineMainTest#설정_파일을_버리고_기동하면_상태_조회에_그_경고가_실린다에서 `engine.guard().accounts()` 비어 있음, 기존 EngineMainTest 기동 시 OFF 단언 유지. 설정 값이 Guard를 켜는 경로 추가 없음 |
| A2 | 통과 | 주문 출구·GuardedOrderGateway 변경 없음. architecture-test 31건 통과, 규칙 파일 diff 없음 |
| A3 | 해당 없음 | 증권사 주문 경로 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키·주문 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음. 경고는 엔진 전체 기동 사실이라 계좌별 상태에 섞이지 않음 |
| A8 | 통과 | 엔진은 문자열 하나를 보관해 응답에 실을 뿐 실험실 호출 없음. 실험실은 기존대로 소켓 조회만 함(RealOpsControllerTest 통과). 엔진이 실험실·UI에 의존하는 방향 추가 없음 |
| A9 | 해당 없음 | 시세 단절·손절 감시 경로 변경 없음 |
| A10 | 통과 | 경고 문구는 ConfigJson.describe(예외 클래스명+JSON 경로+자체 검증 메시지)로 만들고 원문 값은 버림(ConfigJson.java:38-49). 새 테스트가 파일 경로 미포함을 단언(`doesNotContain(dir.toString())`). 픽스처에 실제 키·계좌번호 없음. 한계: 자체 검증 메시지는 코드가 키 이름·범위만 넣는다는 기존 가정에 의존하며 이 PR이 새로 넓힌 것은 아님 |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. 기존 테스트는 시그니처 변경(4번째 인자)에 맞춰 호출만 수정(EngineMainTest). 새 테스트: 엔진 경고 전달, 정상 기동 시 빈 경고, 옛 엔진 응답 호환(ControlChannelTest), 경고 왕복 전달, 실험실 전달(RealOpsControllerTest). 실패 사례(오타 키로 파일 거부) 포함. real.js는 JS 단위 테스트가 없다 |
| A12 | 통과 | 장애 대응표 17종·O1~O8 전제를 바꾸지 않음. "설정 오타로 실전 계좌가 조용히 빠짐"을 운영자가 볼 수 있게 하는 감지 수단이며, 표 행 추가가 필요한 변경은 아님 |
| A13 | 해당 없음 | 시장 규칙·시간·통화 코드 변경 없음 |
| A14 | 통과 | ADR-006 2.3에 configWarning 계약과 옛 엔진 호환 서술 추가. 새 확정 결정(D-xxx)이 필요한 변경은 아님 |

## 점검표 밖 발견
- 낮음(N1): "옛 엔진 응답은 빈 문자열" 호환은 문서·테스트로 보장된다. 반대 방향(새 엔진 응답을 옛 실험실이 읽음)은 ControlCodec이 응답 읽기에 LENIENT를 쓰는 구조(ControlCodec.java:29,44)라 모르는 필드를 무시할 것으로 보이나, 옛 실험실 대상 테스트는 없고 코드 읽기까지만 확인했다.
- 낮음(N2): 경고는 엔진 재기동 전까지 기동 시점 값으로 고정된다. 의도된 동작이며 UI 문구도 그렇게 안내한다. 조치 불필요.
- 낮음(N3): real.js 변경은 브라우저 렌더링을 직접 보지 않았다(JS 테스트 없음). 코드상 textContent 경로라 주입 위험은 낮다고 판단한다.

## 판정
승인
