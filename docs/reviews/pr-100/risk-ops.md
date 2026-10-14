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

## 재검토 2026-10-14
- 대상: PR #100 / 커밋 e298e7b (브랜치 claude/project-thread-gayfxk). 앞선 원문은 7a557cd 기준. 그 뒤 독립 QA(docs/reviews/pr-100/qa.md, 판정 승인, 권고 Q1·R1·R2)를 반영한 fa50f3f·e298e7b를 포함해 `git diff origin/main...HEAD` 전체(16파일, +330 -9)를 다시 봤다. 7a557cd..HEAD 의 코드 변화는 EngineStatus 길이 상한, SecretPaths.forWarning, ConfigLoader 호출 1줄, real.js 문구, ADR-006 한 문단, 시험 4건이다
- 점검표: A
- 직접 실행한 것:
  - `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-control:test :autotrade-config:test :autotrade-app-lab:test --offline --no-daemon` 성공. 테스트 결과 XML 합계: risk 55, architecture-test 31, app-engine 50, control 23, config 31, app-lab 156건, 실패·오류·건너뜀 모두 0
  - `./gradlew build --offline --no-daemon` (전체) BUILD SUCCESSFUL. Maven Central 429는 이번에 나지 않았다
  - `git diff origin/main...HEAD` 중 autotrade-architecture-test, autotrade-risk, autotrade-broker-api 변경 0줄. 추가 줄에서 키·비밀번호·계좌번호 모양을 grep: 실제 값 없음(QA 보고서가 가짜 값 `99990000-01`을 인용한 줄만 나옴)
  - EngineStatus.java의 절단 로직을 같은 코드의 복사본으로 반복 적용해 서로게이트 경계 동작을 확인했다(아래 N1)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·기본 OFF 경로와 autotrade-risk diff 0줄. configWarning은 EngineCommandHandler에서 저장과 status() 응답에만 쓰이고 Guard 전이·인증·명령 검증은 읽지 않는다(이번 커밋들이 이 사용처를 늘리지 않음). EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다 에서 Guard 계좌 0개 단언, 기존 기동 직후 OFF 단언 유지 |
| A2 | 통과 | 주문 출구·GuardedOrderGateway·ArchitectureTest 변경 없음. architecture-test 31건 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키·주문 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 통과 | 큐·토큰 버킷·킬스위치 변경 없음. 경고는 엔진 전체 값 하나라 계좌별 상태와 섞이지 않음 |
| A8 | 통과 | QA 탐침 P1(경고 82,941자가 64KB 상한에 걸려 status·deactivate·panicAll 응답이 모두 끊김)이 EngineStatus 생성자의 2,000자 절단(EngineStatus.java MAX_CONFIG_WARNING, TRUNCATED_MARK)으로 닫혔다. 같은 설정을 쓰는 EngineMainTest#경고가_아주_길어도_상태_조회와_내리기는_응답한다(로더 경고 > 64KB를 먼저 단언한 뒤 status와 deactivate 응답 확인), ControlChannelTest#긴_설정_경고는_잘라서_응답_한도_안에_둔다 통과. 한 필드가 2,000자(최악 JSON 이스케이프 6바이트씩이어도 약 12KB)로 묶여 PANIC 응답이 이 값 때문에 끊길 수 없다. 엔진은 실험실·UI를 호출하지 않고 의존 방향 추가 없음 |
| A9 | 해당 없음 | 시세 단절·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | QA R2 반영: SecretPaths.forWarning이 맵 비밀값(경로 끝 `*`)의 키 이름을 패턴과 개수("real-trading/account-numbers/* N개")로 바꾸고 ConfigLoader.java:65-66 이 이를 쓴다. ConfigLoaderTest#runtime_config의_계좌번호_키_자리에_적은_값도_경고에_남기지_않는다 (가짜 계좌번호 모양 키 2개, 경고에 없음 단언)와 수정된 기존 시험(KIS-US-1 별칭도 없음) 통과. 경고가 "전체는 실전 엔진 로그"라 한 말도 맞다: ProcessStartup.java:20-21 이 loaded.warning() 전체를 로그에 남기며 그 문자열도 같은 forWarning을 거친 값이라 키 이름이 새지 않는다. 한계: secrets.json 거부 경고의 "비밀값 아닌 키" 목록(nonSecretLeaves)은 여전히 키 경로를 쓴다. 비밀값이 아닌 경로라 계좌번호가 들어갈 자리는 아니며 이 PR이 넓힌 것도 아니다(N2) |
| A11 | 통과 | 보호 테스트 삭제·@Disabled 0건. 기존 기대값 변경은 ConfigLoaderTest의 경고 문구 하나인데 단언이 약해진 것이 아니라 오히려 강해졌다(키 이름 없음 + 개수 포함). 새 시험 4건(위 A8·A10, 서로게이트 쌍 절단 포함)은 실패 경우를 다룬다. real.js는 JS 시험이 없다 |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 전제를 바꾸지 않는다. 오히려 "경고가 커져 명령 채널 응답이 끊기는" 새 실패 경로가 닫혔다 |
| A13 | 해당 없음 | 시장 규칙·시간·통화 변경 없음 |
| A14 | 통과 | ADR-006 2.3에 2,000자 절단, 잘림 표시, 맵 비밀값 키 이름 대신 패턴·개수가 코드와 일치하게 적혀 있다. 새 D 번호 필요 없음. docs/qa-out-status.md에 PR #100 QA 처리 기록이 있다 |

## 점검표 밖 발견
- 이전 N1(옛 실험실 대상 호환 시험 없음)은 그대로이며 QA도 같은 구조를 코드로 확인했다(LENIENT 응답 읽기). 낮음.
- 이전 N3(real.js 렌더링 직접 못 봄)은 그대로다. 이번에 문구만 바뀌었고 h() 텍스트 노드 경로는 같다. 배너 문구 "쓰지 않고"로 시작하는 경고와 "무시했습니다"를 구분하는 설명은 ConfigLoader 경고 문구에 기대는데, 문구가 바뀌면 배너 설명이 어긋날 수 있고 이를 묶는 시험은 없다(낮음, 표시 전용).
- 새 N1(낮음, 미관): EngineStatus 절단이 멱등이 아니다. 절단 위치가 서로게이트 쌍에 걸려 1자 줄어든 경우, 잘린 값을 다시 읽을 때(엔진 응답을 실험실이 역직렬화하고 다시 내보내는 홉마다) 공백이 하나 늘어 2019 → 2020 → 2020자에서 멈춘다. 길이는 항상 한도 안이고 내용이 아닌 공백만 늘어난다. 경고에 이모지 같은 보충 문자가 2,000자 경계에 걸릴 때만이라 조치 불필요. 고치려면 이미 TRUNCATED_MARK로 끝나는 값은 건드리지 않게 한다.
- 새 N2(낮음): 위 A10 한계. secrets.json 거부 경고의 비밀값 아닌 키 경로 목록은 개수 요약 없이 경로를 나열하되 길이는 2,000자로 묶인다.

## 재검토 판정
승인 (이전 risk-ops 승인 유지. QA 권고 Q1·R1·R2 반영을 코드와 시험으로 확인함. 확인 못 한 항목 없음, real.js 화면 렌더링은 직접 보지 못함. 실전 관련 PR이라 머지는 사용자가 직접 한다)
