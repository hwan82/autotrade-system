# QA 검토: ADR-006 6장: PR #18 QA·architect 후속 지적을 7단계 완료 조건으로 등록
- 대상: PR #22 / 커밋 42922b72eb4856c8a885067b2f93212743a82882 / main 대비 diff(merge-base 42acc86, 3개 파일 +208 −2: `docs/adr/ADR-006-real-control-channel.md` +7 −2, `docs/reviews/pr-22/architect.md`·`risk-ops.md` 신규)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 실전 관련 여부: **실전 관련**. 실전 제어 명령 채널(RealTradingGuard 활성화·PANIC 경로) 설계 문서의 위험 표와 7단계 완료 조건(실전 활성화 판정 기준)을 바꾼다. PR 본문도 사용자 직접 머지 대상으로 밝힘
- 직접 실행한 것:
  - PR 머지 뒤 상태를 재현하려고 scratchpad 작업 폴더에 현재 `origin/main`(9eab2e6, PR #19 머지 후)을 꺼내 PR 헤드(42922b7)를 병합(충돌 없음, 병합 결과의 main 대비 변경은 위 3개 파일뿐)하고 `./gradlew build --rerun-tasks --max-workers=2` 실행: 처음 8번은 Maven Central이 `429 Too Many Requests`로 의존성 내려받기를 막아 컴파일 전에 실패(환경 문제, 코드 오류 아님), 9번째에 **BUILD SUCCESSFUL**(종료 코드 0, 91 tasks executed). 테스트 결과 XML 집계 393건, 실패 0, 오류 0, 건너뜀 0 (app-lab 97, sim 75, risk 55, config 29, domain 27, strategy 25, control 20, app-engine 48, architecture-test 14, gateway 1, watchdog 1, broker-api 1). PR 헤드 단독(42acc86 기반)이 아니라 현재 main과 병합한 결과로 실행했다. PR #19가 main에 들어온 뒤라 머지 후 상태가 이것이다
  - `git diff --stat origin/main <병합 결과> -- autotrade-risk autotrade-architecture-test docs/team docs/decisions docs/spec '*.java' '*.gradle*' .github`: 빈 결과(코드·보호 테스트·장애 설계·결정 로그·기획서·CI 무변경)
  - ADR 새 행이 근거로 든 코드 사실을 main(9eab2e6)에서 직접 대조: `ControlServer.java:237-244`(STALE은 요청을 다 읽은 뒤 잡는 `enqueuedAt` 기준 대기열 시간만 봄), `OperatorAuthenticator.java:48,60,97,107`(`lockedUntil`·`verify`·`confirmationFor`·`discardVerification`이 같은 객체 `synchronized`, `verify` 안에서 `hash.get().matches` 호출 71행), `OperatorPasswordHash.java:28`(`MAX_ITERATIONS = 10_000_000`), `EngineMain.java:134`("실전 엔진을 다시 시작하세요"만 안내), `docs/config/config-keys.md:30`("실전 엔진과 실험실을 모두 다시 시작"), `ControlServer.java:87`(잠금 파일을 `NOFOLLOW_LINKS` 없이 엶), `ControlServer.java:104-110`(truncate 뒤 PID 쓰기, 실패 시 잠금 해제), `ControlServer.java:298-305`(`isListening` 프로브에 연결 시간 상한 없음), `EngineCommandHandler.java:120-150`(`beginActivation` → 단계별 `steps.perform` → `completeStep`, 마지막 `completeStep`이 OPEN 전이), `RealTradingGuard.java:184-203,225-233`. 모두 행 설명과 일치
  - 4장 위험 표 수정 행 대조: `EngineCommandHandler.java:174-189`(PANIC_ACCOUNT는 2차 확인 전에 보호 모드 고정, 확인 실패·잠금이면 증권사 단계 SKIPPED), `OperatorAuthenticator.java:26-27,78-82`(5회 연속 실패 → 15분 잠금). "잠금 반복 시 사람의 PANIC도 보호 모드 고정까지만" 서술이 코드와 일치
  - PBKDF2 소요 시간 직접 측정(이 컨테이너, JDK `PBKDF2WithHmacSHA256`, 15자 비밀번호): 600,000회 0.27초, 10,000,000회 3.15초
  - diff에서 비밀값 형태(key·token·secret·password 값·계좌번호·긴 숫자열) 검색: 0건(걸린 줄은 `hash-password` 명령 이름, `secrets.json` 파일 이름, 검토 원문의 검색 설명 문장)
  - gitleaks는 이 환경에 없어 실행하지 못함(문서만 바뀌어 위 직접 검색으로 갈음)

## 보호 테스트 변경 여부(먼저 확인)
- 없음. `ArchitectureTest`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`, 장애 테스트 관련 파일 모두 diff에 없다(위 `git diff --stat` 빈 결과).

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 무변경. 새 행·수정 행 어디에도 기본 OFF·재기동 OFF를 바꾸는 내용이 없고, 새 행 1(늦게 끝나는 활성화 차단)은 OPEN 전이 조건을 좁히는 방향(`failActivation`으로 출발 상태 복귀). 기존 보호는 `RealTradingGuard`·`EngineMainTest`(app-engine) 전체 빌드 통과로 유지 확인(app-engine 48건, risk 55건 통과) |
| A2 | 통과 | 주문 출구 코드·ArchUnit 무변경(`autotrade-architecture-test` diff 없음). 새 행 3의 "잠금과 별개인 청산 확인 수단"은 아직 결정 전(Q 번호로 사용자 결정)이며 Guard 팩토리 우회를 제안하지 않음 |
| A3 | 통과 | 증권사 선택·주문 라우팅 언급 없음. 코드 무변경 |
| A4 | 통과 | 타임아웃 재주문 규칙을 건드리지 않음. 새 행 1의 `STALE`은 활성화 명령(주문 아님)의 거절이며 재전송을 만들지 않는다(실험실 `ControlClient`는 재전송 없음, `ControlClient.java:50-70`) |
| A5 | 통과 | 멱등 키 관련 변경 없음. 코드 무변경 |
| A6 | 통과 | 블랙리스트·수동 보유 경로 무변경. 새 행 3의 대체 청산 경로 예시(감시 프로세스 긴급 PANIC)도 기획서 RSK-13(`docs/spec/autotrade-spec.md:186`, "대상은 자동 매매 포지션만(블랙리스트·수동 보유 제외)")을 따르게 되어 있음. 구현 때 확인 필요(점검표 밖 발견 3) |
| A7 | 통과 | 계좌별 큐·토큰 버킷 무변경. 명령 채널 잠금은 원래 엔진 전체 단위(`OperatorAuthenticator` 한 개)이고 이 PR은 그 영향을 위험 표에 기록만 함 |
| A8 | 통과 | 프로세스 분리 무변경. 새 행 2(해시 계산을 검증기 잠금 밖으로)는 STATUS 지연을 줄이는 방향 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 무변경. 4장 수정 행이 "잠금 남용 시 PANIC 청산 거부"를 새로 기록해 보호 공백을 숨기지 않고 드러냄 |
| A10 | 통과 | diff 비밀값 검색 0건(위 직접 실행) |
| A11 | 통과 | 보호 테스트 무변경. 새 동작 없음(문서만). 새 7단계 완료 조건 행에 합격 테스트가 적혀 있음(행 1: 세 지점 각각 기한 초과 시 OPEN 안 됨, 행 3: 잠금 시 알림 + 청산 경로 장애 테스트) |
| A12 | 통과 | `docs/team/broker-and-fault-design.md` 무변경. 새 행은 장애 대응표·장애 테스트 8종의 전제를 바꾸지 않음. 장애 테스트 후보 추가는 기존 Q-018 행을 그대로 둠 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 언급 없음 |
| A14 | 통과 | 동작 변경이 없어 기획서·결정 로그와 충돌 없음. ADR 상태 "채택"은 PR #18 머지(main 42acc86)와 일치. 단, ADR-006의 결정 로그 D 번호는 PR #18 머지 뒤에도 아직 없고(`docs/decisions/README.md`에 ADR-006 D 항목 없음, Q-016~Q-018만 있음), 새 행 3이 말하는 "Q 번호로 묻는다"도 아직 등록 전. PR 본문 "남은 참고"에 pm-lead 후속으로 적혀 있어 이 PR의 실패로 보지 않음(점검표 밖 발견 5) |

## 점검표 B (QA 관문 지정 항목)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 현재 main(9eab2e6)과 병합한 결과에서 `./gradlew build --rerun-tasks` 직접 실행, BUILD SUCCESSFUL, 393건 통과·실패 0·오류 0·건너뜀 0(위 직접 실행 참고) |
| B6 | 해당 없음 | 새 코드 동작 없음(문서 3개). 테스트 삭제·건너뜀 없음(전체 빌드에서 skipped 0건) |

## 과금 검사 (QA 지침 8장)

| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | diff에 코드·빌드·CI 파일이 없다(`git diff --stat` 결과 3개 문서). 새로 생기는 외부 호출 지점 없음. 유료 호출 지점 목록: 없음 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음 |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 없음. 새 행 3이 요구하는 "잠금 시 외부 알림"은 7단계 구현 대상이고, 기존 알림 설정은 기본 꺼짐(`notification.slack.enabled`·`notification.kakao.enabled` 기본 `false`, 유료 `kakao.mode=BIZ`는 켜기 전 사용자 확인, `docs/config/config-keys.md:21-25`). 구현 때 확인 사항은 점검표 밖 발견 2 |
| 8.4 폭주 방지 | 통과 | 이 PR에는 호출 경로가 없다. 다만 새 행 3의 알림은 외부 프로그램이 잠금을 반복해 일으킬 수 있는 트리거라 7단계 구현에 발송 상한이 필요(점검표 밖 발견 2) |
| 8.5 실주문 비용 | 통과 | 실주문을 내는 코드 변경 없음. 새 행 3의 "잠금과 별개인 청산 확인 수단"은 사람 의도 없는 실제 매도(수수료·세금) 경로가 될 수 있어 결정 시 조건이 필요(점검표 밖 발견 3) |

## 점검표 밖 발견
"이 변경이 어떤 입력·순서·장애에서 잘못된 주문, 중복 주문, 보호 공백을 만들 수 있는가"를 이 PR이 만드는 7단계 합격 기준 기준으로 시도했다. 이 PR 자체는 코드가 없어 지금 피해 경로는 없고, 아래는 모두 7단계 구현·실전 활성화 판정 때 확인할 항목이다.

1. **절대 기한을 지켜도 "실험실은 응답 없음, 엔진은 OPEN"이 남을 수 있다.** 새 행 1의 ③ 지점은 `completeStep` 직전에 기한을 보지만, 기한 안에 OPEN이 된 뒤 응답을 쓰고 실험실이 받는 시간(`EngineCommandHandler.java:150` → `ControlServer.java:215` 응답 쓰기)은 기한 밖이다. (나) 안은 부등식으로 여유를 두게 적었지만(risk-ops 참고 1), (가) 안(실험실이 기한을 실어 보냄)에는 응답 전달 여유가 적혀 있지 않다. 실험실 기한을 그대로 엔진 기한으로 쓰면 경계 직전 OPEN이 실험실 시간 초과와 겹친다. 또 ③ 확인과 `completeStep`은 원자적이지 않다(`RealTradingGuard.java:184` 안이 아니라 처리기에서 확인). 권고: (가)·(나) 모두 "엔진 기한 = 실험실 대기 상한 − 응답 전달 여유"로 적고, 합격 테스트에 "실험실이 시간 초과(UNKNOWN)로 끝난 뒤 STATUS를 다시 읽어 실제 상태(OPEN 포함)를 보여 준다"(`RealOpsController.java:57-58`의 UNKNOWN 처리 뒤 재조회)를 넣는다. 기존 4장의 "실전 활성화마다 외부 알림"이 마지막 안전망이다.
2. **잠금 알림이 공격자가 반복 유발할 수 있는 외부 발송이 된다(과금·폭주).** 새 행 3은 잠금마다 외부 알림을 요구한다. 같은 OS 사용자 프로그램은 15분마다 5번 틀린 요청으로 잠금을 다시 걸 수 있고(`OperatorAuthenticator.java:78-82`), 엔진 재기동 때 잠금이 풀리므로 하루 최소 약 96회 이상 알림이 나갈 수 있다 `[추론]`. 알림이 카카오 BIZ(알림톡, 유료) 경로로 나가면 건당 과금이 쌓인다. 권고: 7단계 완료 조건에 "잠금 알림은 같은 원인 묶음·발송 상한(예: 시간당 N건)을 두고, 유료 채널은 사용자가 켠 경우에만, 상한 동작을 테스트로 확인"을 덧붙인다.
3. **"잠금과 별개인 청산 확인 수단"이 확인을 약화하는 경로가 될 수 있다.** 새 행 3의 (2)는 잠금 중 PANIC 청산 방법을 Q 번호로 정하게 했지만 합격 기준에 "대체 수단의 인증 강도가 기존 2차 확인 이상"이 없다. 비밀번호 없이 청산되는 경로를 고르면 같은 OS 사용자 프로그램이 실제 매도(손실 확정·수수료·세금)를 일으킬 수 있다. 감시 프로세스 긴급 PANIC 경로(MON-02)를 쓸 경우에도 RSK-13의 대상 제한(블랙리스트·수동 보유 제외)과 순서(서버 측 손절 취소 확인 전 매도 금지)를 같은 코드로 지키는지 장애 테스트에 포함해야 한다. 권고: Q 질문지에 이 조건을 선택지별 위험으로 적는다.
4. **해시 시간 수치가 PC마다 다르다.** 새 행 2는 `MAX_ITERATIONS` 10,000,000 "직접 시험 약 10.8초"를 근거로 들지만, 이 컨테이너에서는 3.15초, 600,000회는 0.27초였다(4장 기존 행 "시도당 약 0.5초 이상"보다 빠름). 활성화 기한 판단은 운영 PC 측정값으로 해야 한다. 권고: 7단계 설계 때 운영 PC에서 측정하고, 기한 산정에 "설정 가능한 최대 반복 수 기준 최악값"을 쓴다.
5. **결정 기록이 아직 비어 있다.** ADR-006은 "채택"이 됐지만 결정 로그 D 번호가 없고, 새 행 3의 Q 번호도 없다. 7단계 완료 조건이 결정 로그·미확정 목록에서 보이지 않으면 C4(다음 단계를 막는 Q 항목) 판정 때 빠질 수 있다. PR 본문 "남은 참고"대로 pm-lead가 등록해야 한다.

## 판정
**승인**

- 점검표 A는 실패 0, 확인 못 함 0, 해당 없음 1(A13). B1 통과, B6 해당 없음. 과금 검사 8.1~8.5 모두 통과(유료 호출 지점 없음).
- 이 PR은 문서만 바꾸고, 기존 조건을 지우거나 약화하지 않았다. 새 행은 OPEN 전이 조건을 좁히고 PANIC 청산 방해 위험을 드러내는 방향이며, 인용한 코드 사실이 모두 main 코드와 일치한다.
- 점검표 밖 발견 1~5는 승인 조건이 아니다. 7단계 구현 PR과 7단계 실전 활성화 판정(점검표 D)에서 반영 여부를 확인한다. 특히 1(응답 전달 여유와 시간 초과 뒤 재조회), 2(잠금 알림 발송 상한·유료 채널 옵션), 3(대체 청산 수단의 인증 강도)은 7단계 완료 조건 문구에 넣을 것을 권고한다.
- QA는 머지·승인(approve)하지 않는다. 머지는 사용자가 한다.
