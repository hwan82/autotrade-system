# risk-ops 검토: 문서 정리(PR #105 QA 판정 처리 기록, ADR-006 소유 규칙 전제, D-040 각주, v14.30 번호 미사용, KrxMarketRules 주석 정정)
- 대상: PR #111 / 커밋 `83b5df2`(브랜치 `claude/project-thread-12fja9`) / main `d0e9f3f`(머지 기준점과 같음) 대비 `git diff origin/main...HEAD` 전체. 커밋 1개, 파일 7개, +10 −5.
  - 운영 코드: `KrxMarketRules.java` 클래스 Javadoc 3줄만(`git diff -- '*.java'`에서 ` *`로 시작하지 않는 바뀐 줄 0건). 테스트 파일 변경 0건.
  - 문서: ADR-006 5장 1줄, D-034 부록 제목, D-039:26, `qa-out-status.md` 1행, 기획서 변경 이력 1행(v14.30 번호 미사용), `broker-and-fault-design.md:197`(7장 장애 테스트 표 아래 문단).
- 점검표: A
- PR 본문·커밋 메시지의 설명은 판정 근거로 쓰지 않았다. 바뀐 문장이 주장하는 사실을 저장소 코드·문서·실행 결과로 하나씩 대조했다.
- 직접 실행한 것:
  - `./gradlew build --offline` → `:build-logic` 의존성 캐시 없음으로 실패(환경 문제). 온라인 1차 `./gradlew build` → `:autotrade-app-lab:compileJava`가 Maven Central `429 Too Many Requests`로 실패(환경 문제, 코드 무관).
  - `./gradlew --continue :autotrade-risk:test :autotrade-app-engine:test :autotrade-marketdata:test :autotrade-sim:test :autotrade-architecture-test:test` → BUILD SUCCESSFUL. 결과 XML 집계: risk 55, app-engine 50, marketdata 141, sim 225, architecture-test 40, 실패·오류·건너뜀 모두 0.
  - 온라인 2차 `./gradlew build --continue` → **BUILD SUCCESSFUL**(130 태스크). 전 모듈 테스트 결과 XML 집계 1,140건, 실패 0·오류 0·건너뜀 0(PR #105 QA 재검토가 적은 병합 빌드 1,140건과 같은 수).
  - `@Disabled`·`assumeTrue`·`assumeFalse` 검색(빌드 산출물 제외) 0건.
  - diff의 추가 줄에서 `key|secret|token|account|8자리 이상 숫자` 검색 0건.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Guard 관련 코드·설정·테스트 변경 0건(diff 7개 파일 중 Java는 `KrxMarketRules.java` 주석뿐). `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | ArchUnit 규칙 변경 없음(`ArchitectureTest.java`, `ModuleOwnershipTest.java` diff 0줄). `:autotrade-architecture-test:test` 40건 통과. ADR-006:177에 더한 문장은 실제 코드와 맞다: 네 규칙이 존재하고(`ArchitectureTest.java:177` `ONLY_COMMAND_CHANNEL_RAISES_GUARD`, `:212` `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, `:220` `ONLY_ENGINE_OPENS_CONTROL_SERVER`, `:226` `ONLY_LAB_SENDS_CONTROL_COMMANDS`) 모두 `resideOutsideOfPackages(...)`로 패키지 이름 허용을 쓴다. 소유 규칙 없이 위장이 통과한다는 서술은 PR #21 risk-ops 재검토 4의 실측 E2(`docs/reviews/pr-21/risk-ops.md` 재검토 4, 실험실 운영 코드 `com.autotrade.control.LabOpensServer`가 `ArchitectureTest`는 통과하고 `ModuleOwnershipTest`에서 실패)와 같고, 같은 검토의 권고("ADR-006에도 한 줄", 재검토 4 점검표 밖 발견)를 이행한 것이다. `OWNERS`에 `com.autotrade.control` 항목이 있다(`ModuleOwnershipTest.java:116`). 규칙을 약화하는 서술이 아니라 전제를 밝히는 서술이다 |
| A3 | 해당 없음 | 주문 라우팅·증권사 플러그인 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 50건 통과 |
| A8 | 통과 | 프로세스 경계·정지 순서 코드 변경 없음. `qa-out-status.md:58`이 PR #105 QA 권고 R1(`gradle.properties`의 `allowDuringSession=true`가 장 시간 보호를 조용히 끔)을 "7단계 절차서 전 검토"로 넘긴 처리는 QA 원문의 권고 문구(`docs/reviews/pr-105/qa.md:60` "7단계 절차서 전에 검토하길 권한다")와 같다. 이 위험은 이미 D-063:18, D-085:39, 실행 매뉴얼 `docs/runbooks/README.md:84`에 적혀 있어 이 PR이 보호를 약화하거나 위험을 숨기지 않는다. 추적 위치에 대한 권고는 아래 N2 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff 추가 줄 비밀값 패턴 검색 0건. 새 문장에 계좌번호·키·토큰 없음 |
| A11 | 통과 | 테스트 파일 변경 0건(`git diff origin/main...HEAD -- '*/src/test/*'` 빈 결과), `@Disabled`·`assume` 0건, 전체 1,140건 통과. 새 동작이 없어(주석 변경) 새 테스트 요구 없음. 주석이 서술하는 동작은 기존 테스트가 고정한다: `VirtualExchangeTest`의 D-087 테스트(`시가_단일가_접수_구간의_장전_시간외_체결_뒤_장중에야_이벤트가_오면_시가_단일가가_지난_것으로_본다` 등, `:autotrade-sim:test` 225건 통과) |
| A12 | 통과 | 장애 대응표(6장 #1~#17)와 장애 테스트 8종(7장 표) 본문은 바뀌지 않았다(`broker-and-fault-design.md` diff는 7장 표 아래 문단 1줄뿐). 더한 문장은 토스 실주문 연동 시험 상한을 D-040·TST-05로 연결한다. D-040(`docs/decisions/D-040-toss-real-order-test-cap.md`, 상태 "확정, 사용자 결정")과 기획서 TST-05(`autotrade-spec.md:321`)의 상한(왕복 5회, 1회 2만 원·50달러, 손실 1천 원·5달러)이 같고, 링크 경로가 실제 파일이다. 상한을 바꾸거나 시험을 승인하는 문장이 아니다. 해외 O1~O8(`overseas-design.md` 7장) 변경 없음 |
| A13 | 통과 | `KrxMarketRules` 주석 정정이 코드와 맞다. `sessionPhase`는 개장 30분 전부터 개장까지 `OPENING_AUCTION`(`KrxMarketRules.java:50`, `:90`), 그래서 장전 시간외 종가 08:30~08:40은 `OPENING_AUCTION`으로 판정된다. "가상 거래소는 그 구간의 체결 틱을 정규장 체결로 쓰지 않는다"는 `VirtualExchange.java:194-202`(단일가 구간 체결 틱은 `matchTrade`를 타지 않고 `lastTrade`도 갱신하지 않음)와 `:240`(접수 구간 체결 틱은 첫 정규장 이벤트로 세지 않음, D-087 1번)과 맞다. 장후 시간외는 `CLOSED`(`:92-93`). 시장 분기 추가 없음, 주석만 |
| A14 | 통과(발견 N1은 막지 않음) | D-034:35·D-039:26의 "D-040으로 확정" 각주가 D-040 상태와 맞다. 기획서 v14.30 "번호 미사용" 행(`autotrade-spec.md:20`)은 사실과 맞다: 저장소 전체에서 `v14.30`을 쓰는 곳은 v14.32 행의 예고 문구, PR #105 QA R4, PR #106 architect F4 언급뿐이고 v14.30 버전 행은 없었다. 현재 버전 표기 v14.32(`autotrade-spec.md:12`) 그대로. `qa-out-status.md:58`의 PR #105 처리 기록은 QA 원문 판정(1차 "수정 필요(A14)", 재검토 "승인", 권고 R1·참고 R2·R3·R4, `pr-105/qa.md:65`, `:88`)과 맞고, 커밋 `7ae247c`(main 병합)·`460cad2`(Merge pull request #105)가 저장소에 있다. 새 결정이 필요한 변경 없음 |

## 점검표 밖 발견
- N1(권고, 막지 않음): `KrxMarketRules.java:30`의 "장전 시간외 종가 매매(08:30~08:40)"에 출처 표시가 없다. 같은 시각을 D-087 배경과 `docs/runbooks/collector.md:67`은 `[가정]`(KRX 공식 안내로 확인)으로 적는다. 바로 앞 문장은 `[사실]`·`[가정]`을 붙이므로, 다음 이 파일 수정 때 `[가정]`이나 KRX 공식 출처를 붙이길 권한다. 판정 동작(개장 30분 전부터 `OPENING_AUCTION`)은 이 시각과 상관없이 같아 실전·모의 영향은 없다.
- N2(권고, 막지 않음): PR #105 QA R1(장 시간 보호 해제 값이 `gradle.properties`로 조용히 들어옴)의 후속은 지금 `qa-out-status.md` 한 행에만 "7단계 절차서 전 검토"로 남는다. 이 보호는 자원 부족 정지 순서 0번(실전 엔진과 같은 PC의 일괄 명령)에 속하므로, 7단계 진입 관문 목록(기획서 TST-07 또는 미확정 Q 항목)에 한 줄로 올려 두어야 7단계 절차서 작성 때 빠지지 않는다. 이 PR의 범위(처리 기록)에서 요구하지는 않는다.

## 실전 관련 PR 여부
실전 관련 PR이 아니다(지침 10장 머지 권한의 실전 항목에 해당하지 않음). 근거: 운영 코드 변경은 주석뿐이고, 실전 주문 경로·Guard·블랙리스트·리스크 한도·증권사 주문 연동·보호 테스트 변경이 없다. 장애 대응 기준 문서(`broker-and-fault-design.md`)와 ADR-006의 변경은 이미 확정된 사용자 결정(D-040)과 기존 검토 원문(PR #21 risk-ops 재검토 4)으로 가는 참조와 전제 서술이며, 대응 규칙·합격 기준·감지 방법을 바꾸지 않는다.

## 판정
승인
