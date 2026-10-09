# architect 검토: ADR-006 6장: PR #18 QA·architect 후속 지적을 7단계 완료 조건으로 등록
- 대상: PR #22 / 브랜치 claude/project-thread-r930sh / 커밋 84e69de / `git diff origin/main...HEAD` 전체(origin/main 42acc86 = merge-base, 1커밋, 1개 파일 `docs/adr/ADR-006-real-control-channel.md` +4 −0)
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더를 건드리지 않으려고 scratchpad 전용 폴더에 저장소를 복제해 84e69de를 체크아웃하고 `./gradlew build --rerun-tasks --max-workers=2` 실행: **BUILD SUCCESSFUL**(종료 코드 0). 테스트 결과 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - gitleaks는 이 환경에 설치돼 있지 않아 실행하지 못했다. diff가 문서 4줄이라 직접 읽고 key·token·secret·password·계좌번호·긴 숫자열을 검색했다(해당 0건, `password`는 `hash-password` 명령 이름뿐)
  - 새 표 행이 가리키는 코드와 검토 원문을 대조: `ControlServer.java:45,60,87,104-107,242`, `OperatorAuthenticator.java:48,60,97,107`, `EngineMain.java:134`, `docs/config/config-keys.md:30`, `docs/reviews/pr-18/qa.md` 점검표 밖 발견 1~5, `docs/reviews/claude-project-thread-r930sh/architect.md` 재검토 3 참고 1·2
- 코드와 다른 파일은 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행: `./gradlew build --rerun-tasks` 성공, 378건 실패 0 |
| B2 | 해당 없음 | 빌드 스크립트·Java 코드·ArchUnit 규칙 변경 없음(diff는 ADR-006 한 파일). ArchitectureTest는 빌드에서 그대로 통과 |
| B3 | 통과 | 계약(인터페이스, 스키마, 설정 키, REST API, 명령 채널 프로토콜) 변경 없음. 바뀐 것은 ADR-006 6장 "범위 밖(후속)" 표에 4행 추가뿐이고 2~5장(계약 본문)은 그대로 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든 테스트 포함 전체 테스트 통과(B1) |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서만). 테스트 삭제·비활성화 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 경로 코드·설정·보호 테스트를 바꾸지 않는다. 추가된 4행은 실전 경로의 7단계 완료 조건을 늘리는 기록이며 기존 조건을 지우거나 약화하지 않는다(6장 기존 행 diff 없음). 실전 관련 행의 담당을 risk-ops로 둔 것이 맞는지는 risk-ops가 7단계 착수 때 확인하면 된다 |
| B8 | 통과 | diff에 비밀값 없음(위 검색). 해시 리터럴·계좌번호·키 없음 |

## 새 행의 사실 확인
| 행 | 대조 결과 |
|---|---|
| 1 (기한 넘긴 활성화) | `[사실]` STALE은 대기열 대기만 본다: `ControlServer.java:45` Javadoc, `:60` `STALE_AFTER` 10초, `:242` 판정. QA pr-18 발견 1, architect 재검토 3 참고 1과 내용 일치 |
| 2 (해시 계산을 잠금 밖으로) | `[사실]` `OperatorAuthenticator.java:48`(`lockedUntil`)과 `:60`(`verify`)가 같은 `synchronized`. QA 발견 2와 일치 |
| 3 (잠금 반복으로 PANIC 청산 방해) | QA 발견 3과 일치 |
| 4 (경미 3건) | `[사실]` `EngineMain.java:134`는 "실전 엔진을 다시 시작하세요"만, `config-keys.md:30`은 "실전 엔진과 실험실을 모두 다시 시작". `ControlServer.java:87`의 `FileChannel.open`에 `NOFOLLOW_LINKS` 없음. `:104-107` truncate 뒤 PID 쓰기. QA 발견 4·5, architect 재검토 3 참고 2와 일치 |

## 점검표 밖 발견

### 참고 (판정 사유 아님)
1. **architect 원문 위치를 링크하지 않았다.** 행 1은 QA 원문만 링크하고 "architect 재검토 3 참고 1"은 글로만 적었다. PR #18의 architect 원문은 `docs/reviews/pr-18/architect.md`가 아니라 `docs/reviews/claude-project-thread-r930sh/architect.md`에 있어(README의 "PR 번호가 없을 때" 규칙) 찾기 어렵다. 행 2·4의 "architect 참고 1", "architect 참고 2"는 재검토 1·2·3 모두에 참고 1·2가 있어 어느 것인지 모호하다(실제로는 재검토 3의 참고 1·2). 다음에 이 표를 고칠 때 `../reviews/claude-project-thread-r930sh/architect.md` 링크와 "재검토 3"을 붙이기를 권한다.
2. **행 2가 QA 발견 2의 두 번째 제안을 빠뜨렸다.** QA는 해시를 잠금 밖으로 빼는 것과 함께 "반복 상한(파서 허용 10,000,000, `OperatorPasswordHash.java:28`)을 실험실 대기 상한과 맞춰 낮추기"를 검토하라고 했다. 잠금 밖으로 빼도 활성화 응답 자체는 10초 넘게 걸릴 수 있어 행 1(기한)과 엮인다. 7단계 작업 때 함께 다루면 된다.
3. **행 1의 "실험실 대기 상한"은 엔진이 실험실 설정을 알아야 하는 것처럼 읽힌다.** `[추론]` 엔진은 실험실과 독립이어야 하므로(지침 4장 6, ADR-001 프로세스 분리) 구현 때는 (a) 요청에 클라이언트가 기한을 실어 보내기(명령 채널 프로토콜 변경, ADR-006 개정 필요) 또는 (b) 엔진 쪽 상수 기한을 두고 실험실 대기 상한을 그보다 길게 두는 규칙을 ADR에 적기 중 하나로 정해야 한다. (a)가 기한이 한곳에서 정해져 어긋날 여지가 적고, (b)는 프로토콜을 안 바꾼다. 지금 결정할 필요는 없고 7단계 활성화 비동기화 설계에서 정한다.
4. **아직 표에 없는 이전 참고 1건.** architect 재검토 2에서 "프로브 connect 타임아웃 없음, ADR 기록 없음"으로 적은 항목(`ControlServer.java:299-300` `probe.connect`에 시간 상한 없음)이 이번에도 6장 표에 없다. 기동 시 남은 소켓 정리에서만 쓰여 실행 중 엔진에는 영향이 없고 Unix 소켓 connect는 보통 즉시 끝나므로 등급은 참고다. 표 4행(경미 묶음)에 넣어 두면 7단계 정리 때 빠지지 않는다.

## 판정
판정은 **승인**. 점검표 B는 실패 0, 확인 못 함 0, 해당 없음 5(B2, B4, B5, B6, B7). 변경은 ADR-006 후속 표에 4행을 추가한 문서뿐이고, 각 행의 근거를 코드와 검토 원문에서 확인했으며, 기존 조건을 지우거나 약화하지 않았다. 전체 빌드 378건 통과. 참고 4건은 승인 조건이 아니다.

## 재검토 2026-10-09
- 대상: PR #22 / 브랜치 claude/project-thread-r930sh / HEAD 60d66e6 / `git diff origin/main...HEAD` 전체. merge-base 42acc86(origin/main은 그 뒤 9eab2e6 PR #19로 나아감), 커밋 2개(84e69de, 60d66e6), 파일 3개: `docs/adr/ADR-006-real-control-channel.md`(+7 −2), `docs/reviews/pr-22/architect.md`(직전 검토 원문), `docs/reviews/pr-22/risk-ops.md`(risk-ops 원문). 직전 검토 뒤 바뀐 것은 60d66e6: ADR-006 머리 상태(제안 → 채택), 4장 위험 표 1행 보강, 6장 새 행 1~4 고쳐 쓰기와 1행(프로브 타임아웃) 추가, 검토 원문 2건 추가
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더를 건드리지 않으려고 scratchpad의 새 전용 폴더(`arch-pr22-r2`)에 저장소를 복제해 60d66e6을 체크아웃하고 `./gradlew build --rerun-tasks --max-workers=2` 실행: **BUILD SUCCESSFUL**(종료 코드 0). 테스트 결과 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - `git merge-tree --write-tree origin/main HEAD`: 충돌 없음(종료 코드 0). PR #19가 ADR-006을 건드리지 않음을 `git diff 42acc86 origin/main -- docs/adr/ADR-006-real-control-channel.md`(빈 결과)로 확인. 이 PR은 문서만 바꾸므로 main 최신과 합친 트리의 빌드 결과는 main 자체와 같다 `[추론]`
  - gitleaks는 이 환경에 없어 실행하지 못했다. diff 전체에서 key·token·secret·password·긴 숫자열을 검색: 비밀값 0건(걸린 것은 `secrets.json` 파일 이름, `hash-password` 명령 이름, 검토 원문 속 설명 문장뿐)
  - 고친 행이 가리키는 코드를 직접 대조(아래 "고친 행의 사실 확인")
- 코드와 다른 파일은 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행: 60d66e6에서 `./gradlew build --rerun-tasks` 성공, 378건 실패 0 |
| B2 | 해당 없음 | 빌드 스크립트·Java 코드·ArchUnit 규칙 변경 없음(diff는 ADR-006과 검토 원문 2개). `ArchitectureTest` 13건 그대로 통과 |
| B3 | 통과 | 계약(인터페이스, 스키마, 설정 키, REST API, 명령 채널 프로토콜) 변경 없음. ADR-006 2~5장 계약 본문은 무변경이고, 바뀐 것은 머리 상태 줄, 4장 위험 표의 영향·대응 문구, 6장 후속 표다. 6장 행 1의 (가) 안은 명령 형식을 바꾸는 선택지인데 "이 ADR 개정"을 전제로 적어 계약 변경 절차를 지킨다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든 테스트 포함 전체 테스트 통과(B1) |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서만). 테스트 삭제·비활성화 없음(diff에 테스트 파일 없음). 새 7단계 완료 조건 행 1·3에 합격 테스트·합격 기준이 적혀 있다 |
| B7 | 통과 | 코드·설정·보호 테스트 무변경이지만 실전 경로의 7단계 완료 조건과 합격 기준을 정하는 문서라 **risk-ops 검토 필요**. A 점검표 검토 원문(`docs/reviews/pr-22/risk-ops.md`)이 따로 있으나 그 판정은 84e69de 기준 "수정 필요"이고 60d66e6에 대한 risk-ops 재검토는 아직 없다. 머지 전 risk-ops 재검토 승인과 사용자 머지(지침 10장)가 필요하다 |
| B8 | 통과 | diff에 비밀값 없음(위 검색). 해시 리터럴·계좌번호·키 없음 |

## 고친 행의 사실 확인
| 행 | 대조 결과 |
|---|---|
| 머리 상태 "채택 (PR #18 머지)" | `[사실]` origin/main 이력에 42acc86 "(ADR-006) (#18)" 머지가 있다. 결정 로그(`docs/decisions/README.md`)에는 ADR-006 채택 D 번호가 아직 없고 Q-016~018만 있다. 머리 줄도 "결정 로그 번호는 머지 후 pm-lead가 부여"를 그대로 두어 모순은 없다 |
| 4장 위험 표(잠금 남용) | `[사실]` `OperatorAuthenticator.java:26-27`(`MAX_FAILURES` 5, `LOCKOUT` 15분)과 일치. QA pr-18 발견 3의 영향 서술과 일치 |
| 6장 행 1(늦게 끝나는 활성화) | `[사실]` ① `EngineCommandHandler.java:115-120`(확인 검증 뒤 `beginActivation`), ② `:136-138`(단계별 `steps.perform`), ③ `:149`의 `completeStep`이 마지막 단계에서 OPEN으로 올림(`RealTradingGuard.java:184-196`). `ControlServer.java:237` `enqueuedAt`이 요청을 읽은 뒤 잡히고 `:58` `READ_TIMEOUT` 5초, `:60` `STALE_AFTER` 10초, 실험실 대기 상한 15초(`LabWebConfiguration.java:35`). 행이 든 확인 지점 셋과 (나) 안의 부등식이 코드 구조와 맞는다. risk-ops 수정 필요 1(a)(b)(c)와 architect 직전 참고 3(기한 기준 (가)/(나))이 반영됐다 |
| 6장 행 2(해시를 잠금 밖으로, 반복 상한) | `[사실]` `OperatorAuthenticator.java:48,60` 같은 `synchronized`, `OperatorPasswordHash.java:28` `MAX_ITERATIONS` 10,000,000. "약 10.8초"는 QA pr-18 표의 ACTIVATE 응답 10.76초와 맞다. risk-ops 수정 필요 3과 architect 직전 참고 2 반영 |
| 6장 행 3(잠금 남용 대책) | 알림·청산 경로 결정·장애 테스트 합격 기준으로 판정 가능한 조건이 됐다. risk-ops 수정 필요 2 반영. 결정을 "Q 번호로 사용자에게 묻는다"고 적어 실전 영향 결정을 자율로 하지 않는다(지침 8장 2) |
| 6장 행 4(경미 3건) | 링크와 "재검토 3 참고 2"로 출처가 분명해졌다(architect 직전 참고 1 반영) |
| 6장 새 행(프로브 연결 시간 상한) | `[사실]` `ControlServer.java:298-300` `isListening`의 `probe.connect`에 시간 상한 없음. architect 직전 참고 4 반영 |

## 직전 지적(첫 검토 참고 1~4) 처리 확인
- 참고 1(architect 원문 링크·재검토 번호): 행 1·2·4에 반영. 새 프로브 행만 "architect 재검토 2 참고"로 링크 없이 적었다(아래 참고 2).
- 참고 2(반복 상한 재검토 누락): 행 2에 반영.
- 참고 3(기한 기준): 행 1에 (가)/(나) 두 안과 결정 시점(7단계 설계)으로 반영.
- 참고 4(프로브 타임아웃 누락): 새 행으로 반영.

## 점검표 밖 발견

### 참고 (판정 사유 아님)
1. **행 1의 ① 지점에서 `failActivation`은 아무 일도 하지 않는다.** `[사실]` `failActivation`은 상태가 ACTIVATING일 때만 출발 상태로 내린다(`RealTradingGuard.java` `failActivation`, `snap.state() == GuardState.ACTIVATING` 조건). ①은 `beginActivation` **전**이라 상태가 아직 OFF·PROTECTIVE이므로, 이 지점의 올바른 동작은 "`beginActivation`을 부르지 않고 쓰지 않은 검증을 버린 뒤(`OperatorAuthenticator.discardVerification`) `STALE`로 답한다"이다. 지금 문구대로 구현해도 안전 방향(호출이 무시되거나, `beginActivation` 후 곧바로 되돌림)이라 결함은 아니지만, 7단계 구현 때 ①은 "시작하지 않음", ②③은 "`failActivation`"으로 나눠 쓰기를 권한다.
2. **새 프로브 행의 출처에 링크가 없다.** "architect 재검토 2 참고"는 실제로는 1차 검토 참고 (e)에서 처음 나오고 재검토 1·2의 "남은 것"에 이어진 항목이다(`docs/reviews/claude-project-thread-r930sh/architect.md` 111·178·232행). 다른 행처럼 링크를 붙이면 찾기 쉽다.
3. **ADR-006 채택의 결정 로그 번호가 아직 없다.** 상태 줄을 "채택"으로 바꿨으니 pm-lead가 D 번호를 부여해 결정 로그에 올려야 단일 원본 규칙(지침 2장)이 맞는다. 이 PR 범위 밖의 후속이다.
4. **(나) 안의 부등식은 실험실 쪽 연결·전송 시간을 0으로 본다.** `[추론]` 같은 PC의 Unix 소켓이라 연결은 보통 즉시 끝나지만, 실험실 15초는 `ControlClient` 호출 시작부터 흐르므로 7단계 설계 때 여유 1초 정도를 더 두거나 (가) 안을 고르면 이 틈이 없어진다. 7단계 설계에서 정하면 된다.

## 판정
판정은 **승인**. 점검표 B는 실패 0, 확인 못 함 0, 해당 없음 4(B2, B4, B5, B6). 60d66e6의 변경은 ADR-006 머리 상태, 4장 위험 표, 6장 후속 표를 고친 문서와 검토 원문 보존뿐이고, 고친 행의 근거를 코드와 검토 원문에서 확인했으며 기존 조건을 지우거나 약화하지 않았다. 직전 architect 참고 4건과 risk-ops 수정 필요 3건의 내용이 문구에 반영됐다. 전체 빌드 378건 통과, main 최신과 충돌 없음. 참고 4건은 승인 조건이 아니다. 이 판정은 architect 판정이며, 머지에는 risk-ops 재검토 승인과 사용자 직접 머지가 따로 필요하다(B7, 지침 10장).
