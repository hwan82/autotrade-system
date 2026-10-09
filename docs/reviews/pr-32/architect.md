# architect 검토: PanicLiquidator 계약 주석 갱신과 PR #26 QA 지적 보강 (ADR-006 6장)
- 대상: PR #32 / 커밋 87763a9 / main 대비 diff(merge-base 0a4adf4 = 현재 origin/main, `git diff origin/main...HEAD`: `autotrade-app-engine/.../command/PanicLiquidator.java` +4 −1, `docs/adr/ADR-006-real-control-channel.md` +3 −3. 2개 파일)
- 점검표: B
- 직접 실행한 것: 별도 작업 트리(/tmp/arch-pr32, 87763a9)에서 `./gradlew build --no-daemon` → `BUILD SUCCESSFUL in 2m 27s`(104 tasks). 테스트 결과 XML 집계 522건, 실패 0·오류 0·건너뜀 0. 보호 테스트: `RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `ArchitectureTest` 15, `ModuleOwnershipTest` 13, `EngineCommandHandlerTest` 22 모두 통과
- 함께 본 것: `PanicLiquidator.java` 전문(1-38행), `PanicLiquidator` 참조처(`EngineCommandHandler`, `EngineMain`, `EngineCommandHandlerTest`), ADR-006 2.5(88-104행)·6장(177-206행), `docs/spec/autotrade-spec.md` RSK-13(191행), `docs/decisions/README.md` D-037(42행), `docs/reviews/pr-26/qa.md`(점검표 밖 발견 1~6), PR 본문(대조용, 사실로 받지 않음)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build` → BUILD SUCCESSFUL, 522건 실패·오류·건너뜀 0 |
| B2 | 통과 | 의존·import 변경 없음. 코드 변경은 Javadoc뿐(PanicLiquidator.java:11-12, :20-21). ArchUnit 규칙 파일 무변경, `ArchitectureTest`·`ModuleOwnershipTest` 통과 |
| B3 | 통과 | 인터페이스 시그니처(`liquidate(AccountRef)`, `BROKER_NOT_CONNECTED`) 무변경. 주석의 계약 서술 변경은 ADR-006 2.5(:99)와 6장(:202-205)에 근거를 둠. 단 주석과 ADR·D-037의 예시 범위가 다름(아래 F1) |
| B4 | 해당 없음 | sim·strategy·시계·난수 코드 변경 없음 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 실행 동작 변경 없음(Javadoc·문서만). 새 계약의 실패 테스트는 7단계 구현 합격 조건으로 ADR-006:202-203에 등록됨. 테스트 삭제·비활성화 없음(`git diff --name-only`에 테스트 파일 없음) |
| B7 | 확인 못 함 | 실전 경로를 건드린다: 실전 엔진 PANIC SELL 증권사 단계의 구현 계약(`PanicLiquidator` Javadoc)과 7단계 장애 대응 계약(ADR-006 6장 서버 측 손절 재설정)을 바꾼다 → **risk-ops 검토 필요**. 이 검토 시점에 `docs/reviews/pr-32/risk-ops.md`가 브랜치에 없다(origin 브랜치도 87763a9에서 앞선 커밋 없음). 실전 관련 PR이므로 독립 QA와 사용자 직접 머지 대상(지침 10·11장) |
| B8 | 통과 | diff 전문 확인. API 키·계좌번호·토큰 없음 |

## 점검표 밖 발견
1. **F1 [중] 주석의 매도 불가 예시에서 VI가 빠짐 → 확정 결정과 계약 주석이 어긋남.** 새 주석(PanicLiquidator.java:11)은 "매도를 낼 수 없으면(장 외 시간, 거래정지·LULD 정지 등)"이라 쓴다. 그런데 확정 결정 D-037(decisions/README.md:42), 기획서 RSK-13(autotrade-spec.md:191), ADR-006 2.5(:99)는 모두 "장 외 시간, 거래정지·**VI**·LULD 정지 등"이다. 같은 PR이 ADR-006:205에 "VI 분류 변경은 PANIC 청산 범위가 달라지는 실전 영향이라 **사용자 결정**"이라고 적었으므로, 결정 전인 지금 7단계 구현자가 가장 먼저 읽을 계약 주석에서 VI를 빼면 그 결정을 앞질러 VI를 매도 가능으로 읽게 할 수 있다(VI 중 ③ 서버 측 손절 취소로 이어짐) `[추론]`. "등"이 있어 문언상 금지는 아니지만 계약 주석은 단일 원본과 같은 예시를 써야 한다. 수정: "장 외 시간, 거래정지·VI·LULD 정지 등"으로 맞추고, 필요하면 "VI 분류는 ADR-006 6장 재검토 대상(사용자 결정)"을 덧붙인다.
2. **F2 [하] PR #26 QA 발견 3의 합격 테스트 문구 수정이 반영되지 않음.** QA pr-26 발견 3은 "미체결로 남은 보유가 서버 측 손절 없이 마감을 넘기지 않음"을 증권사 거절 시 보장할 수 없으니 "넘기지 않거나, 넘기면 긴급 FAILED로 알림"으로 고치라고 했다. ADR-006:203은 보강 (다)에 "재설정 실패하면 긴급 알림"을 더했지만, 같은 행 앞부분의 원래 합격 테스트 문구는 그대로라 두 기준이 한 행 안에서 다르다. PR 본문은 3건을 반영했다고 하나 이 부분은 빠졌다. 수정: 원래 합격 테스트 문구를 "넘기지 않거나, 넘기면 긴급 FAILED"로 바꾼다.
3. [하] ADR-006:202 상태 칸 "주석은 고침(PR #25·#26 QA 후속 2차)"은 실제 PR 번호가 아니라 추적이 어렵다. "PR #32"로 적기를 권고(머지를 막지 않음).
4. [참고] 새 주석(PanicLiquidator.java:20-21)이 판정 기준·재설정 규칙을 "ADR-006 6장 '7단계 전 필수' 항목"이라는 후속 과제 표에 위임한다. 표 행은 완료되면 상태가 바뀌거나 정리될 수 있으므로, 7단계에서 규칙이 확정되면 2.5 본문으로 옮기고 주석 참조도 2.5로 바꾼다(7단계 구현 PR에서 처리, 지금 수정 불요).
5. 확인했지만 문제 아님: (a) "미체결 매수만 취소"는 RSK-13 인수 조건 "장 외 시간·거래정지 종목 PANIC에서 서버 측 손절 취소와 미체결 매도 취소가 일어나지 않는 테스트"(autotrade-spec.md:191)와 일치. (b) 주석 첫 줄의 "② 미체결 전부 취소"는 매도 가능 경우의 기본 순서이고, 매도 불가 예외는 11-12행이 설명하므로 모순 아님. (c) 현재 구현은 `BROKER_NOT_CONNECTED`(PanicLiquidator.java:33-37)뿐이라 주석과 어긋나게 동작하는 코드 없음. (d) ADR-006:203 보강 (가)(나)(다)는 QA pr-26 발견 1·2·3의 요구(매도 취소 확인 후 남은 수량 재설정, 일괄 취소와의 관계, ③ 직전 재판정)를 담고 있고, :205는 발견 4를 반영함. 발견 5(주석 수정 시점)는 이 PR의 주석 수정으로 해소.

## 판정
수정 필요(F1, B7)

- F1: 주석 예시를 D-037·RSK-13·ADR-006 2.5와 같게 VI를 포함해 고칠 것. F2도 같은 커밋에서 고치기를 권고.
- B7: risk-ops A 점검표 검토 원문(`docs/reviews/pr-32/risk-ops.md`)이 있어야 한다. 실전 관련 PR이라 머지는 사용자가 직접 한다.

## 재검토 2026-10-09 (B7 보정, 대상 커밋 동일 87763a9)
위 원문을 푸시하기 직전 `git pull --rebase`로 risk-ops 원문 커밋 22555e9(`docs/reviews/pr-32/risk-ops.md`, 87763a9 대상)가 들어왔다. 위 B7 "확인 못 함"의 근거(원문 없음)는 푸시 시점 기준으로 사실이 아니게 되어 이 절에서 바로잡는다. 코드·ADR 변경은 없어 다른 항목은 그대로다.

| # | 결과 | 근거 |
|---|---|---|
| B7 | 통과(risk-ops 검토 필요) | 실전 경로(PANIC SELL 증권사 단계 계약, 서버 측 손절 재설정 장애 대응 계약)를 건드리므로 **risk-ops 검토 필요**이며, A 점검표 원문 `docs/reviews/pr-32/risk-ops.md`(22555e9)가 따로 있다. 그 판정은 "수정 필요(A9·A14)"이고 지적 내용이 이 원문의 F1(VI 누락)·F2(합격 테스트 문구)와 같다. 수정 커밋 뒤 risk-ops 재검토 승인, 독립 QA, 사용자 직접 머지가 필요하다(지침 10·11장) |

## 판정
수정 필요(F1)

- F1(PanicLiquidator.java:11 매도 불가 예시에 VI 추가)을 고칠 것. F2(ADR-006:203 합격 테스트 문구)는 risk-ops도 수정 필요로 냈으므로 같은 커밋에서 고칠 것. 수정 뒤 architect·risk-ops 재검토를 받는다.
