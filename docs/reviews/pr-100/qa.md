# QA 검토: 실전 운영 화면에 엔진의 설정 파일 거부 경고 표시(1단계 QA 권고 R3)
- 대상: PR #100 / 커밋 dd80a1daa5f92f495e243ea1fab0d55281bcdb7e / main 대비 diff 전체(merge-base e9427eb. 현재 origin/main은 d0a04ba로 #99 한 커밋 앞서 있음)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 실전 관련 PR 여부: 실전 관련으로 본다. 실전 엔진(`autotrade-app-engine`)과 실전 제어 명령 채널 계약(`autotrade-control` `EngineStatus`, ADR-006 2.3)을 바꾼다. 머지는 사용자가 직접 한다
- PR 본문, 커밋 메시지, 개발 쪽 검토 원문(`docs/reviews/pr-100/architect.md`, `risk-ops.md`)의 주장은 판정 근거로 쓰지 않고 확인할 주장으로만 봤다
- 직접 실행한 것:
  - `git diff origin/main...HEAD --stat`: 11파일, +167 −7. 운영 코드 4파일(`EngineMain.java`, `EngineCommandHandler.java`, `EngineStatus.java`, `real.js`), 시험 3파일, 문서 4파일(ADR-006, pending.md, 검토 원문 2)
  - 보호 영역 변경 여부: `autotrade-architecture-test`, `autotrade-risk`, `autotrade-broker*` diff **0줄**. `ArchitectureTest`·`RealTradingGuardTest`·`GuardedOrderGatewayTest` 변경 없음
  - `./gradlew build --no-daemon`(head dd80a1d): Maven Central 429로 7회 실패(재시도 스크립트가 실패 로그마다 429를 확인한 뒤에만 재시도, 루프 하나) 뒤 8회째 **BUILD SUCCESSFUL**(130 tasks). 테스트 결과 XML 합계 tests **1,073**, failures 0, errors 0, skipped 0
  - **현재 main과 병합 빌드**: 별도 worktree에서 origin/main(d0a04ba, #99 OVS-02 ArchUnit 규칙 포함) + 이 PR 병합(충돌 없음) → `./gradlew build --offline` **BUILD SUCCESSFUL**, tests **1,087**, 실패·오류·건너뜀 0. `git merge-tree` 충돌 없음, GitHub `mergeable_state: clean`
  - CI(head dd80a1d, check-runs 3건): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - QA 탐침(`autotrade-app-engine` 시험 폴더에 임시 시험 `QaProbePr100Test`를 넣어 실행, 확인 뒤 삭제. 저장소에 남기지 않음):
    - P1: runtime-config.json 정상(계좌 KIS-US-1) + secrets.json에 비밀값 아닌 키 6,000개 → `loaded.warning()` 길이 **82,941자**. 엔진 기동 후 `status`, `deactivate`, `panicAll` **세 명령 모두 `EngineUnreachableException`("실전 엔진과 통신하지 못했습니다")**. 엔진은 명령을 처리하지만 응답 직렬화가 64KB 상한(`ControlCodec.java:52`)에 걸려 `IOException("메시지가 너무 큽니다")`이 나고, `ControlServer.java:218-221`이 DEBUG 로그만 남기고 연결을 닫는다. 같은 설정으로 main에서는 상태에 경고가 실리지 않으므로 이 PR이 새로 만든 실패다
    - P2: secrets.json `account-numbers`에 accounts에 없는 별칭 하나 → 파일은 둘 다 쓰였고 계좌 1개가 그대로인데 `configWarning` = "secrets.json의 real-trading.account-numbers 중 … 1개를 무시했습니다"(비어 있지 않음). real.js는 이 경우에도 "실전 엔진이 설정 파일을 거부하고 기동했습니다"를 띄운다
    - P3: runtime-config.json `account-numbers`의 키 자리에 계좌번호 모양 문자열(`99990000-01`, 가짜 값) → `configWarning` = "… 비밀값은 secrets.json에만 둡니다: [real-trading/account-numbers/99990000-01]". 키 이름이 경고에 그대로 실려 상태 응답과 실험실 `/api/real/status`, 브라우저 화면까지 간다
  - `node --check real.js` 문법 통과. 화면 렌더링은 브라우저로 직접 보지 않았다(JS 시험 없음). 값은 `h()`의 텍스트 노드로 들어간다(`dom.js:1-30`, innerHTML 없음)
  - diff 추가 줄에서 `appkey|secret|password|token\s*=|계좌번호 모양 숫자` grep: 값 0건

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `EngineMain.startGuard`와 `autotrade-risk` diff 0줄. `configWarning`은 `EngineCommandHandler.java`에서 필드 저장(:66)과 `status()`(:109)에만 쓰이고 Guard 전이·인증·명령 검증은 읽지 않는다. `EngineMainTest` 기동 직후 OFF 단언 유지, 새 시험 `설정_파일을_버리고_기동하면_상태_조회에_그_경고가_실린다`에서 Guard 계좌 0개. 위 빌드 통과 |
| A2 | 통과 | 주문 출구·`GuardedOrderGateway`·`ArchitectureTest` 변경 없음. 위 두 빌드(PR 헤드, main 병합)의 ArchUnit 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 통과 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. 경고는 엔진 전체 값 하나라 계좌별 상태에 섞이지 않는다(`EngineStatus.java`) |
| A8 | 통과 | 엔진은 기동 때 계산된 문자열을 보관해 응답에 실을 뿐 실험실·UI를 호출하지 않는다. 실험실은 기존대로 소켓 조회만 한다(`RealOpsControllerTest` 통과). 다만 경고가 64KB를 넘으면 명령 채널 응답 전체가 끊긴다(탐침 P1, 아래 시나리오 1·Q1). 이 경우 엔진 자체 동작(Guard·명령 처리)은 영향이 없고 응답만 사라진다 |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 경고는 파일 이름(`getFileName`)과 `ConfigJson.describe`(예외 클래스명·JSON 경로·자체 검증 메시지)로만 만든다(`ConfigLoader.java:136-147`, `ConfigJson.java:38-49`). 절대 경로 미포함은 새 시험이 단언. 추가 줄에 키·계좌번호·토큰 없음. 실험실은 상태 내용을 로그에 남기지 않는다(`RealOpsController.java:88,92`는 명령 이름과 코드만). 사용자가 키 이름 자리에 값을 적으면 그 키 이름이 경고로 나가는 점(P3)은 이 PR 전부터 엔진 로그(`ProcessStartup.java:20-21`)에 있던 동작이고, 이 PR은 표시 범위를 화면까지 넓힌다(아래 R2) |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled` 0건. 기존 시험은 4번째 인자 추가에 맞춘 호출 변경뿐(`EngineMainTest`). 새 동작 시험: 오타 키로 거부된 기동에서 경고 전달·경로 미포함(실패 경우), 정상 기동 빈 경고, 옛 엔진 응답 빈 경고, 왕복 동일, 실험실 API 전달. 경고 크기 상한 시험은 없다(Q1). real.js는 시험 없음 |
| A12 | 통과 | 장애 대응표 17종·O1~O8·장애 테스트 8종의 전제를 바꾸지 않는다. 설정 거부를 운영자에게 드러내는 표시만 더했다. 단 P1처럼 경고가 커지면 운영 화면이 엔진을 `UNKNOWN`으로 계속 보여 준다. 그 기동에서는 열 수 있는 계좌가 없으므로(아래 시나리오 1) 대응표 행이 필요한 보호 공백은 아니다 `[추론]` |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | ADR-006 2.3에 `configWarning` 계약(파일 이름·JSON 경로·규칙만, 빈 문자열, 표시 전용, 옛 엔진 호환)이 적혀 있고 코드와 일치(`EngineStatus.java`, `ControlCodec` LENIENT 응답 읽기, `ControlChannelTest`). 기획서 14장 1단계 행의 "QA 권고 후속 R3(ui-dev)"와 어긋나지 않는다. 표시만 더한 변경이라 새 D 번호는 필요 없다고 본다 `[추론]`. ADR의 "버렸거나 일부를 무시한 이유"는 P2(무시 경고)를 포함하는데 화면 제목은 "거부"로만 말한다(R1) |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(PR 헤드 1,073건, main 병합 1,087건, 실패·오류·건너뜀 0) |
| B6 | 통과 | 새 동작에 시험 있음(A11). 건너뛰거나 지운 시험 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 라이브러리 없음(`build.gradle.kts` 변경 0건) |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **경고가 64KB를 넘는 설정으로 엔진 기동(탐침 P1)**: 상태 조회·내리기·PANIC 응답이 모두 끊겨 실험실은 `UNKNOWN`("엔진이 살아 있고 실전 계좌가 열려 있을 수 있음")을 계속 보여 주고, 명령이 엔진에서 처리돼도 화면은 결과를 받지 못한다. 그러나 큰 경고는 비밀값 아닌 키 목록(secrets.json 거부 → 비밀번호 해시 없음 → 올리는 명령 거절, ADR-006 2.4) 또는 runtime-config.json의 비밀값 경로 목록(runtime-config.json 거부 → 실전 계좌 0개)에서만 나온다(`ConfigLoader.java:61-83`). 그래서 그 기동 동안 OPEN 계좌가 생길 수 없고, 잘못된 주문·중복 주문·보호 공백으로 이어지지 않는다 `[추론]`. 이 PR이 노린 "거부 사실을 화면에 보이기"가 바로 그 경우에 실패하는 문제라 Q1로 둔다.
2. **파일을 버리지 않았는데 경고가 뜸(탐침 P2)**: 계좌·비밀번호 해시가 모두 쓰였는데 화면은 "설정 파일을 거부하고 기동했습니다", "거부된 파일의 실전 계좌 목록이나 비밀번호 해시는 이번 기동에 쓰이지 않았습니다"를 띄운다. 운영자가 엔진을 다시 시작하면 모든 계좌가 OFF로 돌아갈 뿐이라 주문 쪽 위험은 없지만, 실제와 다른 안내다(R1).
3. **경고가 있는 채로 엔진이 응답하지 않음**: real.js는 `UNKNOWN`일 때 마지막 `UP` 스냅샷의 경고를 그대로 보여 준다(`real.js:47,59-64`). 경고는 기동 시점 값으로 고정되므로 오래된 경고가 되지 않는다. 활성화는 기존대로 막힌다.
4. **옛 실험실 ↔ 새 엔진, 새 실험실 ↔ 옛 엔진**: 응답은 LENIENT로 읽어(`ControlCodec.java:44-46,66-68`) 모르는 필드를 무시하고, 빠진 필드는 빈 문자열(`EngineStatus` 생성자, `ControlChannelTest.설정_경고가_없는_옛_엔진의_상태는_빈_경고로_읽는다` 통과). 요청 쪽(STRICT)은 바뀌지 않았다.

## 점검표 밖 발견
- **Q1 경고 길이 상한 없음, 넘으면 명령 채널 응답 전체가 끊김**(권고, 우선): 탐침 P1. 모든 응답(PANIC 포함)이 `status()`를 싣기 때문에(`EngineCommandHandler.java:72,158-226,359`) 경고 하나가 64KB를 넘으면 모든 명령의 응답이 사라진다. architect 원문은 "64KB 제한이 있어 실질 위험은 낮다"고 썼으나, 그 제한이 바로 실패 원인이다. `configWarning`을 상한(예: 2,000자, 넘으면 잘랐다는 표시와 "전체는 엔진 로그")으로 자르고, 상한을 넘는 경고로 `status`·`deactivate`가 응답하는 시험을 더할 것. 지금은 열 수 있는 계좌가 없는 기동에서만 나므로 차단하지 않는다.
- **R1 "무시" 경고에도 "거부" 배너**(권고): 탐침 P2. 배너 제목과 설명을 "설정 파일 문제" 등으로 넓히거나, 엔진이 `ConfigSource`(거부 여부)를 함께 보내 문구를 나눌 것.
- **R2 키 이름에 적은 값이 화면까지 나감**(권고): 탐침 P3. 사용자가 계좌번호를 키 자리에 잘못 적으면 그 문자열이 엔진 로그(이전부터)와 이제 실험실 API·화면에 나온다. 이 PR이 만든 원인은 아니지만, `account-numbers/*` 아래 키 이름은 `ConfigLoader.load`의 orphan 경고처럼 개수만 쓰도록 바꾸는 것을 권한다(`ConfigLoader.java:45` 주석이 같은 위험을 이미 인정).
- **R3 화면 시험 없음**(참고): real.js 배너는 자동 시험이 없고 이 검토에서도 브라우저로 렌더링을 보지 않았다(문법 검사만). 판정 항목에는 영향이 없어 차단하지 않는다.

## 판정
승인 (권고 Q1·R1·R2·R3, 차단 아님. 실전 관련이라 머지는 사용자)
