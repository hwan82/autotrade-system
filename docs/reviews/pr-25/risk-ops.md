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
