# risk-ops 재검토 3: Q-042 ① 사용자 확인 반영, Q-042 닫음 (7fa3111)
- 대상: PR #47 / 커밋 7fa3111 / main(9100cb3) 대비 diff 전체. 직전 검토 [risk-ops-2.md](risk-ops-2.md)(5a14853, 승인) 뒤 바뀐 것은 7fa3111 한 커밋(`docs/adr/ADR-003-market-data.md`, `docs/decisions/D-059-toss-token-owner-engine.md`, `docs/decisions/README.md`, `docs/research/toss-openapi-spec.md`, `docs/spec/autotrade-spec.md`, 7줄 추가·7줄 삭제)과 architect 재검토 원문
- 점검표: A
- 직접 실행한 것
  - 별도 복제본에서 HEAD 7fa3111 `./gradlew build --no-daemon` 종료 코드 0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 29건, `:autotrade-app-engine:test` 48건, `:autotrade-app-gateway:test` 36건, 실패·오류·건너뜀 0. 5a14853 이후 코드 변경이 없어 테스트 작업은 최신 상태로 판정됐고, 결과는 5a14853의 `--rerun-tasks` 실행 결과와 같다
  - `git merge-tree --write-tree origin/main HEAD`: 충돌 없음
  - 사용자 확인 출처: 프로젝트 스레드 "토스 REST API 보완" 메시지 cmsg_01Du6VLttqgn6f15sF8UYTuUJ4kaK8G2arrYBVq4eYh5sN, 2026-10-11T09:37:41Z, 사용자(user_01Vc7USeKzFq1SwmNQt6Je3E, BAE) "설정 화면에서 Client ID를 하나 더 만들 수 없습니다." 문서에 인용한 시각·문구와 일치

## 7fa3111 변경 확인
- D-059:13 `[가정]` → `[사실, 사용자 확인]`. 결정의 전제(Client ID 1개)가 사용자 확인으로 사실이 됐고, 결정 내용은 바뀌지 않음
- 되돌릴 조건(D-059, README D-059 행, ADR-003 5장 행): "토스가 Client ID를 2개 이상 발급하도록 바뀌면 새 D 번호로 사용자에게 묻는다, 엔진 Client ID·토큰은 나누지 않음" 유지. 되돌림 경로가 남아 있음
- Q-042가 미확정 표에서 빠지고 닫힌 미확정 표(`docs/decisions/README.md:109`)로 옮겨짐. Q-047·Q-048은 미확정 표에 그대로(`:78-79`)
- 다른 문서(broker-and-fault-design.md 1-T·4.1·연결 배분, config-keys.md, MD-02)의 "Client ID가 하나인 동안" 문구는 사실이 된 전제와 맞고, 되돌릴 조건과도 맞음. 남은 "사용자 확인 대기" 문구 없음(검색 0건)

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Guard 코드·설정·테스트 변경 없음(diff 전체가 문서) |
| A2 | 해당 없음 | 주문 출구·ArchUnit 변경 없음. `:autotrade-architecture-test:test` 29건 통과 |
| A3 | 통과 | 토스 주문은 실전 엔진의 토스 토큰으로만(D-059:17). Q-048 추천안도 같은 토스 계좌로만. 증권사 간 대체 없음 |
| A4 | 해당 없음 | 타임아웃·재전송 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷 코드 변경 없음 |
| A8 | 통과 | 실전 엔진만 토스 토큰을 가져 다른 프로세스가 엔진 토큰을 끊는 경로가 없음. Client ID 1개가 사실로 확인돼 이 구조가 유일한 안전한 선택임이 굳어짐(토큰 공유 대안은 절대 규칙 6 위반). 현재 코드: `GatewayMain.java:125` TOSS 미연결, `GatewayMainTest.java:44-47` 통과 |
| A9 | 통과 | 보호 폴링 한계(D-059:34)와 엔진 정지 중 보호 수단(D-059:35, Q-048)이 그대로 명시됨. 7fa3111은 보호 경로 서술을 바꾸지 않음 |
| A10 | 통과 | 추가 줄에 키·계좌번호·토큰·Client ID 값 없음(사용자 인용 문구에도 식별값 없음) |
| A11 | 통과 | 테스트 삭제·비활성화 없음. 전체 빌드 통과 |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 변경은 5a14853 그대로(1-T는 D-059 유지 동안 해당 없음, Q-047). 7fa3111은 그 전제(Client ID 1개)를 사실로 바꿨을 뿐이라 1-T 처리 근거가 더 단단해짐 |
| A13 | 해당 없음 | 시장별 규칙·시간대·통화 변경 없음 |
| A14 | 통과 | 사용자 확인이 D-059, README(D-059 행, Q-042 닫힌 표), ADR-003 5장, 토스 명세 요약, 기획서 v14.14 이력에 같은 내용으로 반영됨. 출처 시각·문구가 스레드 원문과 일치 |

## 점검표 밖 발견
- risk-ops-2.md의 사소 항목(기획서 v14.14 이력 영향 칸의 "15장", 다른 열린 PR의 v14.12·v14.13 머지 순서, 게이트웨이 TOSS 건너뜀 경고 로그는 토스 연결 PR에서 구현)은 그대로 남아 있다. 모두 차단 아님.
- 이 PR은 장애 대응 기준(1-T, Q-047)을 바꾸므로 사용자 머지와 독립 QA 관문 대상이다. Q-048(감시 토스 긴급 경로)은 7단계 토스 연결 전 사용자 결정으로 남는다.

## 판정
승인
