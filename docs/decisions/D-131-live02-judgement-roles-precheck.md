# D-131 LIVE02 등록 전 판정 계약: 참고 계좌 `references`, 대조군 시드 수 `null-seeds`, 녹화 사전 검사 명령, 공시 입력 키(ADR-014)

- 날짜: 2026-10-20
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 문서만 바꾼다(ADR). 구현은 sim-engine이 ADR-014 9장 순서로 따로 한다. 실전 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 게이트웨이, 보호 경로는 바꾸지 않는다.
- 담당: architect(계약), strategy-researcher(사전 검사 판정 명세). 교차 검토: architect, strategy-researcher
- 근거: [ADR-014](../adr/ADR-014-live02-judgement-roles-precheck.md), [LIVE02 계획](../strategy/live02-preregistration-plan.md) 2.2 G1·4장·6.2·8장 F1~F4, [D-128](D-128-live02-preregistration-plan.md) 7번, architect PR #202 M2
- 번호: D-129는 열린 PR #203, D-130·ADR-013은 F7 스레드(선정 밖 신규 진입 미리 거름)가 써서 D-131·ADR-014를 쓴다.

## 결정

1. **F1 참고 계좌**: 실시간(`input: live`)·사전 검사 등록 파일에 목록 키 `references`(`profiles`·`selections` 줄을 그대로 다시 적음). 계좌 번호·세션·주간 점검은 그대로이고, 판정 명령에서만 m·변형·지연·대조군 시드에서 빠지며 `judgement.md`에 역할 "참고", 돌리지 않은 L7·L9·L10은 "–"로 낸다. 기준선·대조군을 참고로 적거나 참고를 빼고 판정 대상이 0이면 거절.
2. **F3 대조군 시드 수**: 목록 키 `null-seeds`(`"<선정 id@버전 | none> <N>"`). 적힌 묶음만 그 N, 없는 묶음은 21m − 1. N < 21m − 1, 대조군·판정 대상 없는 묶음, 1~999 밖은 거절. 시드·레인 ID 규칙 그대로라 앞 21m − 1개 결과는 같다.
3. **빨리 실패**: `references`·`null-seeds` 중 하나라도 있으면 실험실 세션 시작 때 같은 역할 계산으로 확인하고 틀리면 시작하지 않는다(8주 뒤 판정 때 드러나지 않게).
4. **F4 녹화 사전 검사**: 새 등록 `input: recording-precheck`(녹화 실험 키 + `references`·`null-seeds`·`family-size`·`alpha`·`min-round-trips`·`p2`·`p3`·`p3-min-segments`·`segments`)와 새 명령 `runRecordingPrecheck`. 녹화를 레인(BASE + 묶음별 대조군 시드)으로 한 번 읽어 `precheck.md`(P0~P3, q̂·Clopper–Pearson 상한, LIVE02 N 표, 구독 커버리지, 체결 기준 한도 도달·거절 사유, 선정 표)와 `inputs.yaml`을 쓴다. 문턱은 코드가 아니라 등록 파일 값이다. 결과 파일은 바이트 재현(실행 시간은 로그만).
5. **F9 공시 입력 키**(이번에 찾은 빈틈): 실시간·녹화·사전 검사 등록 파일의 `selection-input.disclosures`를 받는다(`daily`와 같은 값 규칙). 지금은 거절해서(`RegisteredSelections.java` 107~108행) C3((라))을 사전 검사·LIVE02에 등록할 수 없다.
6. **바이트 동일**: 새 키가 없는 등록은 판정·녹화 실험 결과 파일이 지금과 같다(LIVE01 판정 보호). 역할·시드 수 계산은 판정 명령·세션 시작 확인·사전 검사가 한 곳의 코드를 쓴다.
7. **순서**: PR A(F9) → PR #203(F2, D-129) 머지 → PR B(F1·F3·F6) → PR C(F4). F7(D-130) 머지가 사전 검사 실행의 선행 조건(계획 G1)이고, 이 결정은 F7 파일(`ManagedStrategy`·`RunStepper`·`StrategyContext`)을 고치지 않는다.

## 대안과 되돌릴 조건

ADR-014 8장(참고 표시 방법 3안, 참고 레인, 키 모양, N 하한, 사전 검사 위치 2안, 문턱 위치, 공시 입력)과 11장.

## 위험

ADR-014 10장(사전 검사 약 375 Run 판정 시간, 체결 기준 한도 도달 과소 추정, 참고 계좌 L7·L9 없음, 공시 `latest` 수집 시각 의존).
