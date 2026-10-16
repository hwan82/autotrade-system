# 독립 QA 대기 목록

개발 쪽(메인 Claude)이 독립 QA 관문(프로젝트 지침 11장)에 올 때마다 아래 표에 한 줄을 추가합니다. 사용자가 "자동매매 QA" 프로젝트에 **"대기 목록 처리해줘"**라고 하면 QA가 이 목록에서 아직 판정이 없는 요청을 찾아 검토합니다. QA를 시작하는 것은 여전히 사용자입니다.

- 줄 위치: PR 검토 요청은 그 PR 브랜치의 이 파일에, 단계 완료·실전 활성화 판정 요청은 main으로 가는 문서 PR의 이 파일에 추가합니다.
- 처리 여부는 보고서 위치의 파일에 요청일 이후 판정(첫 판정 또는 `## 재검토 <날짜>`)이 있는지로 판단합니다. 줄을 지우거나 고치지 않습니다.
- 요청 칸에는 QA 프로젝트에 붙여 넣을 문구를 그대로 씁니다. 결론이나 안심시키는 설명은 쓰지 않습니다(11장, QA 지침 3장).

| 요청일 | 요청 | 보고서 위치 | 대상 |
|---|---|---|---|
| 2026-10-13 | PR #65 검토해줘 | docs/reviews/pr-65/qa.md | 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차) |
| 2026-10-13 | main 기준으로 2단계 완료 재판정해줘 | docs/reviews/gates/stage-2.md | 단계 완료 판정(재검토 2026-10-12 (2) 완료 조건 1~5) |
| 2026-10-13 | main 기준으로 3단계 완료 판정해줘 | docs/reviews/gates/stage-3.md | 단계 완료 판정(3단계 실험실 MVP, D-068 머지 후 main) |
| 2026-10-13 | main 기준으로 3단계 완료 재판정해줘 | docs/reviews/gates/stage-3.md(재검토 절) | 단계 완료 재판정(1차 수정 필요 C1 → D-069 BOOK-V3·녹화 실험 명령 PR #74, 실녹화 실험 두 번 실행 experiments/EXP-20261013-REC01/report.md) |
| 2026-10-14 | main 기준으로 1단계 완료 판정해줘 | docs/reviews/gates/stage-1.md | 단계 완료 판정(1단계 뼈대, PR #7·#9. 개발 쪽 대조표 docs/qa/stage-1-completion-check.md) |
| 2026-10-14 | main 기준으로 5단계 완료 판정해줘 | docs/reviews/gates/stage-5.md | 단계 완료 판정(5단계 UI, D-074 PR #82 머지 f292fee. 개발 쪽 대조표 docs/qa/stage-5-readiness.md, 3장 이연 항목의 판정 범위 포함 여부는 사용자 확인 대상) |
| 2026-10-14 | PR #87 검토해줘 | docs/reviews/pr-87/qa.md | 실전 관련 PR(D-076 게이트웨이 시세 전달 소켓·실시간 모의 세션. 게이트웨이를 실전 엔진과 공유, 장애 대응 표 #9·#10, 브랜치 claude/project-thread-go4oua) |
| 2026-10-14 | main 기준으로 5단계 완료 재판정해줘 | docs/reviews/gates/stage-5.md(재검토 절) | 단계 완료 재판정(1차 수정 필요 C5 → 사용자 결정 D-079 기록과 C5 4곳 정정, 이 줄이 들어간 문서 PR 머지 뒤 main) |
| 2026-10-14 | PR #91 검토해줘 | docs/reviews/pr-91/qa.md | 실전 관련 PR(장애 대응: 실험실 실시간 세션 디스크 하한 1GB 미만 거부, PR #87 QA 권고 후속, 브랜치 claude/project-thread-go4oua) |
| 2026-10-14 | PR #100 검토해줘 | docs/reviews/pr-100/qa.md | 실전 관련 PR(1단계 QA 권고 R3: 실전 엔진 상태 스냅샷에 설정 파일 거부 경고 configWarning, 실전 운영 화면 표시. 실전 엔진·명령 채널 계약 변경, 브랜치 claude/project-thread-gayfxk) |
| 2026-10-14 | PR #100 재검토해줘 | docs/reviews/pr-100/qa.md(재검토 절) | 실전 관련 PR 재검토(첫 판정 승인의 권고 Q1·R1·R2를 fa50f3f에서 반영, 사용자 결정 "고친 뒤 머지". risk-ops·architect 재검토 승인, 브랜치 claude/project-thread-gayfxk) |
| 2026-10-14 | PR #101 검토해줘 | docs/reviews/pr-101/qa.md | 실전 관련 PR(PR #100 QA 권고 R4: secrets.json·runtime-config.json 거부 경고에서 계좌번호 모양 키 이름 가림, 실전 엔진 로그·운영 화면에 나가는 경고 변경, 브랜치 claude/project-thread-gayfxk) |
| 2026-10-14 | PR #102 검토해줘 | docs/reviews/pr-102/qa.md | 실전 관련 PR(PR #101 QA 권고 Q1·R1·R2: 설정 경고에서 값 자리 아래 키 가림, 겹친 계좌 id는 위치만, 실전 엔진 로그·운영 화면 경고 변경, 브랜치 claude/project-thread-gayfxk) |
| 2026-10-14 | PR #108 검토해줘 | docs/reviews/pr-108/qa.md | 실전 관련 PR(보호 테스트 강화: D-086 실전 경로 리플렉션 금지·JDK·Jackson·외부 jar 허용 목록, `RealPathReflectionTest`, 독립 QA PR #21 Q-4·risk-ops R-8 후속, 브랜치 claude/project-thread-l44wj7) |
| 2026-10-14 | PR #108 재검토해줘 | docs/reviews/pr-108/qa.md (## 재검토) | 실전 관련 PR(QA 수정 필요 F1·F2 반영, 브랜치 claude/project-thread-l44wj7) |
| 2026-10-14 | PR #105 검토해줘 | docs/reviews/pr-105/qa.md | 실전 관련 PR(D-085, Q-050: 리플레이 점검에 장 시간 보호·실험 정지 요청 파일 관문, 정지 순서 0번·장애표 #10 변경, 브랜치 claude/project-thread-g5f5i8) |
| 2026-10-15 | PR #105 재검토해줘 | docs/reviews/pr-105/qa.md (## 재검토 절) | 실전 관련 PR 재검토(1차 수정 필요 A14: main 병합 충돌 해결, docs/runbooks/README.md replayCheck 설명 D-085에 맞춤, 브랜치 claude/project-thread-g5f5i8) |
| 2026-10-15 | PR #109 검토해줘 | docs/reviews/pr-109/qa.md | 실전 관련 PR(보호 테스트 강화: PR #108 QA 권고 R7, 레코드 구성 요소 읽기를 `SecretPaths`만 허용, 브랜치 claude/project-thread-l44wj7) |
| 2026-10-15 | PR #109 재검토해줘 | docs/reviews/pr-109/qa.md (## 재검토 절) | 실전 관련 PR 재검토(1차 수정 필요 A14: main 460cad2와 pending.md 충돌, main 병합으로 해소) |
| 2026-10-15 | PR #110 검토해줘 | docs/reviews/pr-110/qa.md | 실전 관련 PR(보호 테스트 강화: PR #109 QA 권고 Q1·Q2·R1, `SecretPaths` 모양 고정·고정 타입 목록·같은 패키지 위반 예제, D-086 한계 갱신, 브랜치 claude/project-thread-l44wj7) |
| 2026-10-15 | PR #112 검토해줘 | docs/reviews/pr-112/qa.md | 실전 관련 PR(보호 테스트 강화: PR #110 QA 권고 Q1·R1~R3, `SecretPaths` 바이트코드 지문 고정·와일드카드·중첩 클래스 금지, 브랜치 claude/project-thread-l44wj7) |
| 2026-10-15 | PR #112 재검토해줘 | docs/reviews/pr-112/qa.md (## 재검토 절) | 실전 관련 PR 재검토(1차 수정 필요 A14/F1: 보호 테스트 정의·기계 표시 추가, 권고 R1~R4 반영) |
| 2026-10-15 | PR #114 검토해줘 | docs/reviews/pr-114/qa.md | 보호 테스트 변경·감시 런타임에 처음 코드가 들어가는 PR(D-090, MON-05 모니터 에이전트·모니터링 화면: 코어 `autotrade-monitor-agent-jar`, 새 모듈 `autotrade-monitor-sources`(OSHI), 실험실 `/api/monitor`·`#/monitor`, `ModuleOwnershipTest`·`ArchitectureTest` 변경, 브랜치 claude/project-thread-y0syxq) |
| 2026-10-15 | PR #113 검토해줘 | docs/reviews/pr-113/qa.md | 실전 관련 PR(D-089 SIM-14: 실험 실행 명령의 정지 확인 배선을 진행 보고·취소 작업 제어로 감쌈, 정지 순서 0번·장애표 #10 경로, 종료 코드 3 취소 추가, 실험실 /api/experiment-jobs, 브랜치 claude/project-thread-4t6q46) |
| 2026-10-15 | PR #113 재검토해줘 | docs/reviews/pr-113/qa.md (## 재검토 절) | 실전 관련 PR 재검토(1차 수정 필요 A14: main d6fb6e7·b4ed7fc 병합으로 충돌 해소, 권고 Q1 상한 1 겹침 시험·R1 취소 뒤 정지 요청은 종료 코드 4·R2 작업 목록 메모리 상한 반영, 브랜치 claude/project-thread-4t6q46) |
| 2026-10-15 | PR #116 검토해줘 | docs/reviews/pr-116/qa.md | 실전 관련 PR(D-092, MON-02 감시 프로세스 1차: 하트비트 파일(실전 엔진 포함), 감시의 드라이브 여유 감시와 정지 요청 파일 생성·갱신·해제(5.2 0번, 장애표 #8·#9·#10 감지), 감시 설정 오류 시 기동 거부, `StopRequestPathTest` 변경, 브랜치 claude/project-thread-0ganrn) |
| 2026-10-15 | PR #119 검토해줘 | docs/reviews/pr-119/qa.md | 실전 관련 PR(보호 경로 `autotrade-monitor-agent-jar` 변경: MON-05 후속, `MonitorAgent` 수집원 실패 로그를 상태 변화 때만 WARN·반복 DEBUG·회복 INFO로(risk-ops PR #114 K5). 감시 프로세스도 이 코어를 씀. 브랜치 claude/project-thread-5u4jv5) |
| 2026-10-15 | PR #118 검토해줘 | docs/reviews/pr-118/qa.md | 실전 관련 PR(PR #116 후속: 감시 파일 읽기 4KB 상한, 남은 임시 파일 정리, 정지 요청 부분 삭제 실패 시험, 설정 폴더 경고, 엔진·감시 forbiddenProjects에 monitor-sources), 브랜치 claude/project-thread-0ganrn |
| 2026-10-15 | PR #122 검토해줘 | docs/reviews/pr-122/qa.md | 실전 관련 PR(보호 테스트 `RealPathReflectionTest` 변경: D-095 Q-053 사용자 결정 ①, 감시의 `PlatformOsReadings` 한 클래스만 JDK 운영체제 MXBean 읽기 세 메서드 허용, 감시 CPU·메모리 정지 요청, 못 읽는 JVM 기동 거부. PR #118 QA Q1·R1 후속. 브랜치 claude/project-thread-0ganrn) |
| 2026-10-15 | PR #123 검토해줘 | docs/reviews/pr-123/qa.md | 실전 관련 PR(보호 경로 `autotrade-app-watchdog` 변경: D-096, PR #122 QA R1·R4 후속. 감시 자원 값 결측 WARN을 10분마다 반복, 오가는 결측은 간격마다 한 번. D-095 문구 정정(R3), 6단계 Windows 값 대조(R2). 브랜치 claude/project-thread-0ganrn) |
| 2026-10-16 | PR #127 검토해줘 | docs/reviews/pr-127/qa.md | 실전 관련 PR(보호 경로 `autotrade-notification-jar` 첫 코드와 `ModuleOwnershipTest.NO_CLASSES_YET` 고정 값 변경: D-099 MON-06 알림 라이브러리 1차, 매핑표·Slack·카카오 나에게 보내기(토큰 자동 갱신)·로그 대체, JDK `java.net.http`. 실전 엔진·감시 런타임 클래스패스에 들어감. 브랜치 claude/project-thread-othqxw) |
| 2026-10-16 | PR #128 검토해줘 | docs/reviews/pr-128/qa.md | 실전 관련 PR(보호 경로 `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-notification-jar` 변경: D-100 MON-06 2차, 알림을 감시·실전 엔진에 연결(전용 스레드 큐, 실전 제어 명령 알림), 카카오 갱신 설정 키 4개와 엔진·감시 공유 토큰 파일, PR #127 QA Q1·R1·R2·R3, PR #123 QA Q1·Q2·R4. 브랜치 claude/project-thread-vtmvk8) |
