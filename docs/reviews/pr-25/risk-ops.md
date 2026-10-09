# risk-ops 검토: ADR-006 결정 번호(D-037)와 기획서 RSK-13 갱신 (v14.1)
- 대상: PR #25 / 커밋 936de53 / main 대비 diff(`git diff origin/main...HEAD`: docs/adr/ADR-006-real-control-channel.md, docs/decisions/README.md, docs/spec/autotrade-spec.md 3개 파일, +11 −6. 코드 변경 없음)
- 점검표: A
- 직접 실행한 것: `./gradlew build --console=plain` (Maven Central 429 응답으로 7번 의존성 내려받기 실패 후 8번째 시도) → `BUILD SUCCESSFUL`. 테스트 결과 XML 집계: 전체 411건 실패 0·오류 0·건너뜀 0. `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 27건, `:autotrade-app-engine:test` 48건 모두 통과(`EngineCommandHandlerTest` 실패 0). 코드 변경이 없는 PR이라 테스트는 현재 코드가 새 기획서 문구와 맞는지 확인하는 용도로 봤다.
- 함께 본 실전 경로 코드: `autotrade-app-engine/.../command/EngineCommandHandler.java`(PANIC 1~6단계), `PanicLiquidator.java`(7단계 구현 계약), `PanicSteps.java`, `autotrade-risk/.../guard/RealTradingGuard.java`, `EngineCommandHandlerTest.java`. 기준 문서: broker-and-fault-design.md 6장·6.1·7장, overseas-design.md 7장(O4·O8), ADR-006 2.3~2.5·7장.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드·설정 변경 없음. RealTradingGuard.java:26(생성 직후 모든 계좌 OFF, 상태를 인자로 받지 않음) 그대로. RSK-01(autotrade-spec.md:176) 문구 변경 없음. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | ArchUnit·Guard 출구 코드 변경 없음. `:autotrade-architecture-test:test` 27건 통과 |
| A3 | 통과 | 주문 경로 변경 없음. 새 RSK-13 문구(autotrade-spec.md:188)에도 다른 증권사로 PANIC 매도를 보내는 내용 없음 |
| A4 | 해당 없음 | 타임아웃·재주문 규칙(ORD 계열)을 바꾸지 않음. PANIC 증권사 단계는 7단계 구현이며 현재 `PanicLiquidator.BROKER_NOT_CONNECTED`(PanicLiquidator.java:28)가 주문을 내지 않음 |
| A5 | 해당 없음 | 멱등 키 관련 문서·코드 변경 없음 |
| A6 | 통과 | 새 RSK-13이 "대상은 자동 매매 포지션만(블랙리스트·수동 보유 제외)"을 유지(autotrade-spec.md:188). D-035(보유분 청산도 차단)와 충돌 없음. PanicLiquidator.java:11 계약도 블랙리스트 제외 |
| A7 | 통과 | 계좌별 PANIC은 해당 계좌만, 전체 PANIC은 모든 계좌 1단계 후 계좌별 진행(autotrade-spec.md:188, EngineCommandHandler.java:192-209). 테스트 `전체_PANIC은_모든_계좌를_먼저_잠근_뒤_계좌별로_청산하고_OFF_계좌는_건너뛴다` 통과 |
| A8 | 통과 | 명령 채널 구조 변경 없음. UI-06 근거에 ADR-006 연결만 추가(autotrade-spec.md UI-06 행) |
| A9 | 실패 | 새 RSK-13 문구는 순서 ② 미체결 전부 취소 → ③ 서버 측 손절 취소 → ④ 매도를 정하고 "장 외 시간에는 매도를 내지 않는다"고만 쓴다. Q-017 결정 전 임시 규칙도 "구현은 보호 모드를 유지한다"(Guard 상태)뿐이다. 이 문구대로면 장 외 시간 PANIC에서 ②·③은 수행되고 ④만 빠져, 보유가 **서버 측 손절 없이 밤을 넘긴다**. 7단계 구현 계약도 같다: PanicLiquidator.java:11 "장 외 시간에는 취소만 하고 매도하지 않는다", ADR-006 95행(2.5 표 4단계). broker-and-fault-design.md 6.1(178행)은 서버 측 손절을 "PC가 멈췄을 때의 마지막 방어선"으로 두고, 장애표 #11·#12(170·171행)는 단절·전원 차단 동안 서버 측 손절만 보호한다고 전제하며, 해외 O8(overseas-design.md:94)도 같다. 현재 코드(EngineCommandHandler.java keepProtective)는 이 경우를 긴급 표시만 하고 막지 않는다(`서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다`). 같은 문제가 매도가 불가능한 장중 상황(거래정지, VI, 미국 LULD 정지 O4)에도 생긴다. 고칠 것은 아래 "수정 요청" 1 |
| A10 | 통과 | diff 3개 파일에 API 키·계좌번호·토큰 없음(비밀값은 키 이름 `real-trading.operator-password-hash`와 형식 설명만). 직접 diff 전문 확인 |
| A11 | 통과 | 테스트 파일 변경 없음. 기획서 RSK-13 인수 조건에 실패 경우 테스트 2건 추가("2차 확인 실패·잠금·비밀번호 미설정에서도 보호 모드 고정·청산 미수행", "②~⑤ 중 하나라도 끝나지 않으면 Guard OFF 없음"). 두 조건은 현재 테스트 `PANIC은_2차_확인이_틀려도_보호_모드_고정은_하고_청산은_하지_않는다`, `잠긴_상태에서도_PANIC은_보호_모드_고정을_한다`, `비밀번호가_없어도_PANIC은_열린_계좌를_보호_모드로_고정한다`, `매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다`로 이미 고정되어 있고 통과 |
| A12 | 실패 | A9와 같은 원인. 바뀐 RSK-13(장 외·매도 불가 시 ③ 수행 허용)이 장애표 #11·#12와 6.1, 해외 O8의 전제(서버 측 손절이 PC 장애 동안 보유를 지킨다)를 깨는데, 대응표·장애 테스트와 함께 바뀌지 않았다. 장애 테스트 8종 자체(broker-and-fault-design.md 7장)는 이 PR이 바꾸지 않음 |
| A13 | 통과 | 매도 방식 "국내 시장가, 미국 지정가+여유폭(OVS-06)" 그대로. 시장 분기 코드 변경 없음 |
| A14 | 실패 | (1) D-037(decisions/README.md:40)은 "남은 질문 Q-016·Q-017·Q-018"이라고 적었고 결정 로그 미확정 표에 Q-018(장애 테스트 목록에 run 폴더 삭제 시나리오 추가, 실전 활성화 조건)이 있으나, 기획서 15장(autotrade-spec.md:325-326 부근, "원본은 결정 로그 미확정 표")에는 Q-016·Q-017만 넣고 Q-018을 빠뜨렸다. Q-018은 TST-01 장애 테스트 목록에 영향을 주는 실전 영향 항목이다. (2) A9의 장 외 시간 서버 측 손절 처리 규칙이 기획서·ADR-006 2.5·PanicLiquidator 계약 어디에도 없다. 나머지(1단계 확인 전 고정, Guard OFF는 ②~⑤ 모두 DONE일 때만, OFF 계좌 ②~⑥ 건너뜀, 전체 PANIC 문구)는 코드(EngineCommandHandler.java:174-209, 232-293)와 ADR-006 2.4·2.5·7장과 일치함을 확인 |

## 점검표 밖 발견
- D-036 번호를 건너뛴 것은 열린 PR #23(브랜치 claude/project-thread-89zdzi, 커밋 170a146)이 D-036을 쓰고 있어서다. 의도된 예약으로 보이며 문제 아님. 다만 두 PR이 모두 decisions/README.md 표와 기획서를 고치므로 먼저 머지되는 쪽 뒤에 충돌 해결이 필요하다.
- 기획서 15장 표 순서가 Q-020·Q-021 뒤에 Q-016·Q-017을 붙였다. 의미 문제는 아님.
- RSK-13 인수 조건에 새로 넣은 테스트 2건은 현재 1단계 범위(엔진 처리기)에서만 고정되어 있다. 7단계 실제 `PanicLiquidator` 구현 테스트로 옮기는 일은 기획서 16장 risk-ops 후속 항목(autotrade-spec.md:361)으로 잡혀 있어 이 PR에서는 요구하지 않는다.

## 수정 요청
1. (A9·A12·A14) RSK-13과 ADR-006 2.5 4단계 행, `PanicLiquidator` 계약 주석(PanicLiquidator.java:11)에 다음을 명시한다: **④ 매도를 낼 수 없는 경우(장 외 시간, 거래정지·VI·LULD 정지 등)에는 ③ 서버 측 손절 취소를 하지 않고, ②는 미체결 매수만 취소한다. Q-017 결정 전에도 이 규칙을 적용한다.** 인수 조건에 "매도 불가 시간 PANIC에서 서버 측 손절 미취소·보유 유지·보호 모드 유지" 테스트를 추가한다. 이 규칙은 Q-017 추천안과 같은 방향이라 기존 결정을 뒤집지 않고 더 안전한 쪽으로 좁히는 것이다. Q-017이 다른 쪽으로 결정되면 그때 새 번호로 바꾼다. (코드 주석 변경이 이 PR 범위를 넘는다고 보면 ADR-006 6장 후속 표에 "7단계 전 필수"로 올리는 것도 받는다. 기획서·ADR 문구는 이 PR에서 고쳐야 한다.)
2. (A14) 기획서 15장에 Q-018을 추가한다(담당 risk-ops·사용자, 영향 TST-01·RSK-08).

## 판정
수정 필요(A9, A12, A14: 장 외·매도 불가 시 서버 측 손절 취소 금지 규칙 명시와 테스트 조건, 기획서 15장 Q-018 누락)

## 재검토 2026-10-09
- 대상: PR #25 / 커밋 610ca0a(직전 검토 대상 936de53 + 검토 원문 0e910f1 + 수정 610ca0a) / main 대비 diff(`git diff origin/main...HEAD`: docs/adr/ADR-006-real-control-channel.md, docs/decisions/README.md, docs/reviews/pr-25/risk-ops.md, docs/spec/autotrade-spec.md 4개 파일, +50 −6. `docs/` 밖 변경 없음을 `git diff --name-only`로 확인)
- 점검표: A
- 직접 실행한 것: `./gradlew build --console=plain` → `BUILD SUCCESSFUL`(테스트 결과 XML 집계 전체 411건, 실패 0·오류 0·건너뜀 0). 캐시 결과를 쓰지 않도록 `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test --rerun-tasks` 다시 실행 → `BUILD SUCCESSFUL`, `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 27건, `:autotrade-app-engine:test` 48건 모두 통과(실패·오류·건너뜀 0)
- 함께 본 실전 경로 코드·문서: `PanicLiquidator.java`(계약 주석 11행), `EngineCommandHandler.java`(PANIC 단계 처리, Guard OFF 조건), `RealTradingGuard.java`, broker-and-fault-design.md 6장(#8·#11·#12, 167·170·171행)·6.1(178행), overseas-design.md 7장 O8, ADR-006 2.5·6장

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드·설정 변경 없음. RealTradingGuard.java:26(생성 시 모든 계좌 OFF) 그대로. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | ArchUnit·Guard 출구 코드 변경 없음. `:autotrade-architecture-test:test` 27건 통과 |
| A3 | 통과 | 새 문구(autotrade-spec.md:188, ADR-006:97)에 다른 증권사로 취소·매도를 보내는 내용 없음. 주문 경로 코드 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재주문 규칙을 바꾸지 않음. PANIC 증권사 단계는 7단계 구현이고 현재 `PanicLiquidator`가 주문을 내지 않음 |
| A5 | 해당 없음 | 멱등 키 관련 문서·코드 변경 없음 |
| A6 | 통과 | RSK-13 "대상은 자동 매매 포지션만(블랙리스트·수동 보유 제외)" 유지(autotrade-spec.md:188), D-035와 충돌 없음. PanicLiquidator.java:11 계약도 같음 |
| A7 | 통과 | 계좌별·전체 PANIC 순서 문구는 직전 검토와 같고 EngineCommandHandler와 일치. 새 규칙은 종목·계좌 단위 제약을 더할 뿐 다른 계좌에 영향 없음. `:autotrade-app-engine:test` 48건 통과 |
| A8 | 통과 | 명령 채널·프로세스 구조 변경 없음 |
| A9 | 통과 | 직전 실패 해소. RSK-13(autotrade-spec.md:188)과 ADR-006:97에 "매도를 낼 수 없으면(장 외 시간, 거래정지·VI·LULD 정지 등) ③ 서버 측 손절 취소를 하지 않고 ②는 미체결 매수만 취소, Q-017 결정 전에도 적용"이 들어갔다. ADR-006:100(Guard OFF는 2~5 모두 DONE일 때만)과 합치면 이 경우 3단계가 DONE이 아니므로 보호 모드가 유지되고 서버 측 손절도 남는다. 인수 조건에 "장 외 시간·거래정지 종목 PANIC에서 서버 측 손절 취소와 미체결 매도 취소가 일어나지 않는 테스트" 추가(autotrade-spec.md:188). `PanicLiquidator` 계약 주석(PanicLiquidator.java:11)은 아직 옛 문구이나, 직전 검토가 받아들인 대로 ADR-006 6장 후속 표(ADR-006:204)에 "7단계 전 필수"로 올라가 있고 기획서 16장(autotrade-spec.md:362)에도 risk-ops 후속으로 있다. 현재 구현은 증권사 단계를 수행하지 않으므로(BROKER_NOT_CONNECTED) 지금 서버 측 손절이 지워지는 경로는 없다 |
| A10 | 통과 | diff 4개 파일 전문 확인. API 키·계좌번호·토큰 없음(설정 키 이름과 해시 형식 설명만) |
| A11 | 통과 | 테스트 파일 변경 없음(보호 테스트 삭제·약화 없음). 새 규칙의 실패 경우 테스트는 기획서 인수 조건(autotrade-spec.md:188)과 ADR-006:204 후속 항목으로 7단계 구현에 고정. 이 PR은 코드 변경이 없어 지금 추가할 코드 테스트 대상이 없음 |
| A12 | 통과 | 새 규칙이 장애표 #8·#11·#12(broker-and-fault-design.md:167·170·171)와 6.1(178행), 해외 O8의 전제(PC·연결 장애 동안 서버 측 손절이 보유를 지킴)를 매도 불가 PANIC에서도 지키도록 맞췄다. 대응표·장애 테스트 8종 자체는 바꾸지 않았고 바꿀 필요도 없다 |
| A13 | 통과 | 매도 방식(국내 시장가, 미국 지정가+여유폭 OVS-06) 그대로. 미국 LULD 정지를 매도 불가 사유로 명시(O4와 맞음). 시장 분기 코드 변경 없음 |
| A14 | 실패 | (1) **ADR-006 2.5 표가 끊겼다**: 새 문단(ADR-006:97)이 표의 4단계 행(95행)과 5단계 행(99행) 사이에 들어가, 5·6단계 행(99·100행)이 머리행·구분행 없는 줄이 되어 GFM에서 표로 렌더링되지 않는다. 6행은 "Guard OFF는 2~5가 모두 DONE일 때만"이라는 이 PANIC 규칙의 핵심 행이다. 문단을 표 아래(100행 뒤)로 옮겨야 한다. (2) **결정 로그에 새 규칙이 없다**: ADR-006:97은 새 규칙의 근거를 "D-037"로 적지만 D-037 행(decisions/README.md:40)은 PR #18 머지 결정만 담고 "매도 불가 시 서버 측 손절 취소 금지·미체결 매수만 취소" 규칙이 없다. 이 규칙은 PR #18에 없던, 이 PR에서 추가된 장애 대응 규칙(지침 8장 2번 실전 영향)이므로 D-037 행에 "매도 불가 시 서버 측 손절 유지(PR #25에서 추가, 사용자 머지로 결정)"를 덧붙이거나 새 번호로 기록해야 기획서·ADR·결정 로그가 일치한다. Q-018 누락은 해소(autotrade-spec.md:327) |

## 점검표 밖 발견 (재검토)
- ADR-006:104 "장 외 시간: 기획서 RSK-13은 '장 외 시간에는 미체결 취소와 Guard OFF만'이라고 적었지만"은 v14.1에서 RSK-13 문구가 바뀌어 더 이상 사실이 아니다. Q-017이 생긴 배경 설명이라 "v14까지의 RSK-13은 …"처럼 과거형으로 고치길 권한다. 판정에는 넣지 않는다.
- 매도 불가 사유에 VI를 넣었는데, 국내 VI 발동 중에는 단일가 매매로 주문 접수 자체는 된다 `[추론: 공식 자료 미확인]`. 서버 측 손절을 남기는 쪽이라 안전 방향이고, VI가 길어야 수 분이라 위험이 늘지 않으므로 이 PR에서는 요구하지 않는다. 7단계 `PanicLiquidator` 구현 때 "매도 불가" 판정 기준(장 상태·종목 정지 여부를 어디서 읽는지)을 `MarketRules`·증권사 조회로 정의하고 출처를 남길 것.
- 종목 일부만 매도 불가인 계좌(예: 한 종목 거래정지)에서는 그 종목만 ③을 건너뛰고 나머지는 진행하는지, 계좌 전체를 멈추는지가 문구상 정해지지 않았다. 인수 조건 문구("거래정지 종목 PANIC")로 보아 종목 단위로 읽히며, 어느 쪽이든 3단계가 DONE이 아니므로 Guard OFF는 일어나지 않아 안전하다. 7단계 구현 시 명시할 것.

## 수정 요청 (재검토)
1. (A14) ADR-006:97 "매도를 낼 수 없을 때" 문단을 2.5 표 밖(100행 뒤)으로 옮겨 1~6단계 표를 하나로 복원한다.
2. (A14) 결정 로그 D-037 행에 매도 불가 시 서버 측 손절 유지 규칙을 덧붙이거나(이 PR에서 추가, 사용자 머지로 결정) 새 번호로 기록하고, ADR-006:97·기획서 변경 이력의 결정 근거를 그 번호와 맞춘다.

## 판정 (재검토)
수정 필요(A14: ADR-006 2.5 표가 새 문단으로 끊김, 매도 불가 시 서버 측 손절 유지 규칙이 결정 로그 D-037에 없음)
