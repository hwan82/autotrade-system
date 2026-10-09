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
