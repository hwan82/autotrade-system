# risk-ops 검토: PanicLiquidator 계약 주석을 D-037 매도 불가 규칙에 맞추고 PR #26 QA 지적을 ADR-006 6장에 보강
- 대상: PR #32 / 브랜치 claude/project-thread-efvxw0 / 커밋 87763a9 / main 대비 diff(merge-base 0a4adf4 = 현재 origin/main, `git diff origin/main...HEAD`: `autotrade-app-engine/.../command/PanicLiquidator.java` +4 −1(주석만), `docs/adr/ADR-006-real-control-channel.md` +3 −3)
- 점검표: A
- 실전 관련 여부: 실전 관련 PR. 실전 주문 경로의 PANIC 증권사 단계 인터페이스(`PanicLiquidator`) 계약과 서버 측 손절 취소·재설정 규칙을 다룬다. 지침 10장에 따라 사용자 직접 머지 대상
- 직접 실행한 것:
  - `./gradlew build --console=plain` → `BUILD SUCCESSFUL` (104 tasks, 40 executed)
  - `./gradlew :autotrade-risk:test --rerun :autotrade-architecture-test:test --rerun :autotrade-app-engine:test --rerun` → `BUILD SUCCESSFUL`, 세 test 태스크 모두 실제 실행
  - 테스트 결과 XML 집계: 전체 522건, 실패 0·오류 0·건너뜀 0. 보호 테스트 `RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `ArchitectureTest` 15, `ModuleOwnershipTest` 13, `EngineCommandHandlerTest` 22 모두 통과
- 함께 본 것: `PanicLiquidator.java` 전체(1-36행), 호출부 `EngineCommandHandler.java:32,43,48`, `EngineMain.java:117`(`PanicLiquidator.BROKER_NOT_CONNECTED` 주입), `EngineCommandHandlerTest.java:45,224`, ADR-006 2.5(88-104행)·6장(199-207행), `docs/decisions/README.md:42`(D-037), `docs/spec/autotrade-spec.md:189`(RSK-11)·`:191`(RSK-13), `docs/reviews/pr-26/qa.md`(발견 1~6), broker-and-fault-design.md 5.2·6장·6.1, overseas-design.md O4·O8

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff의 코드 변경은 `PanicLiquidator.java` Javadoc 주석뿐(실행 코드 변경 0행). Guard 기본값·재기동 OFF 관련 변경 없음. `RealTradingGuardTest` 31건 직접 실행 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `ArchitectureTest` 15건, `ModuleOwnershipTest` 13건 통과. 실제 주입 구현은 여전히 `BROKER_NOT_CONNECTED`(PanicLiquidator.java:30-35, EngineMain.java:117)로 주문을 내지 않음 |
| A3 | 통과 | ADR-006:203 보강분(가)(나)(다)는 같은 계좌의 매도 취소·서버 측 손절 재설정만 다루고, 다른 증권사로 주문·취소를 보내는 내용 없음 |
| A4 | 통과 | 현재 바뀌는 동작 없음. ADR-006:203은 재설정 주문에 "타임아웃 시 재전송 없이 조회 우선"을 유지하고, 보강분(가)는 매도 취소·체결 확인 뒤에만 재설정하게 해 중복 주문 방향을 줄임 |
| A5 | 통과 | 멱등 키 관련 코드·문서 변경 없음. ADR-006:203의 재설정 주문 멱등 키 요구 유지 |
| A6 | 통과 | 새 주석이 블랙리스트·수동 보유 제외(RSK-12)를 그대로 둠(PanicLiquidator.java:11). 제외 로직 변경 없음 |
| A7 | 통과 | 큐·토큰 버킷·계좌 간 격리 변경 없음. 판정 단위 "계좌·종목의 시장 단위"(ADR-006:203) 유지 |
| A8 | 해당 없음 | 프로세스 구조·자원 부족 정지 순서(broker-and-fault-design.md 5.2) 변경 없음 |
| A9 | 실패 | 방향은 강화(주석이 "매도 불가 시 서버 측 손절 취소 안 함, 상태 불명이면 매도 불가"를 계약에 넣음, PanicLiquidator.java:11-12,20-21)이나, 매도 불가 예시에서 **VI가 빠졌다**(PanicLiquidator.java:11 "장 외 시간, 거래정지·LULD 정지 등"). 확정 결정 D-037(decisions/README.md:42), RSK-13(autotrade-spec.md:191), ADR-006:99는 모두 "거래정지·VI·LULD 정지"로 적는다. 7단계 구현자가 인터페이스 주석을 기준으로 삼으면 VI 중 PANIC에서 ③ 서버 측 손절을 취소할 수 있다. VI 분류는 ADR-006:205에서 사용자 결정 대상으로 열려 있으므로 결정 전에 주석이 먼저 빼면 안 된다(지침 8장 2·4번). 점검표 밖 발견 1 |
| A10 | 통과 | diff 전문 확인. API 키·계좌번호·토큰 없음 |
| A11 | 통과 | 테스트 파일 변경·삭제·비활성화 없음. 보호 테스트 전부 통과. 새 계약의 실패 테스트는 7단계 전 필수로 남아 있음(ADR-006:202 상태 열). 다만 합격 테스트 목록에 빈틈(점검표 밖 발견 2) |
| A12 | 통과 | 대응표 17종·해외 O1~O8·장애 테스트 8종의 전제를 바꾸지 않음. 서버 측 손절을 마지막 방어선으로 두는 6.1, O4(LULD 중 보유분 유지)·O8과 같은 방향 |
| A13 | 통과 | 시장 분기 코드 없음. 판정 출처를 `MarketRules` 세션 구간·증권사 조회로 둔 기존 문구 유지(ADR-006:203) |
| A14 | 실패 | (1) 주석의 매도 불가 예시가 D-037·RSK-13·ADR-006:99와 다름(A9). (2) ADR-006:203 보강분이 QA pr-26 발견 3의 요구 중 "합격 테스트 '미체결로 남은 보유가 서버 측 손절 없이 마감을 넘기지 않음'을 '넘기지 않거나, 넘기면 긴급 FAILED로 알림'으로 고칠 것"을 반영하지 않았다. 증권사가 정지 중 재설정을 거절하면 지금 문구는 달성 불가능한 합격 기준이라 구현 때 실패를 숨기는 쪽으로 해석될 위험이 있음. 점검표 밖 발견 2 |

## 점검표 밖 발견
1. **[중, 수정 필요] `PanicLiquidator` 주석의 매도 불가 예시에 VI 누락.** PanicLiquidator.java:11을 "매도를 낼 수 없으면(장 외 시간, 거래정지·VI·LULD 정지 등)"으로 D-037·RSK-13·ADR-006:99와 같은 목록으로 맞출 것. VI를 매도 가능으로 옮기는 것은 ADR-006:205의 사용자 결정 뒤에 결정 로그·기획서·ADR·주석을 함께 바꾼다.
2. **[중, 수정 필요] ADR-006:203 합격 테스트 보강 미완.** QA pr-26 발견 3이 요구한 두 가지를 반영할 것.
   - 기존 합격 테스트 "마감 직전 PANIC에서 미체결로 남은 보유가 서버 측 손절 없이 마감을 넘기지 않음"을 "넘기지 않거나, 재설정이 거절·불가하면 해당 단계 FAILED + 긴급(`urgent`) 표시, Guard OFF 없음"으로 고친다.
   - 보강분(다)의 "③ 뒤 ④ 전에 정지가 걸리면 즉시 재설정 시도, 실패하면 긴급 알림"에 대응하는 합격 테스트가 없다(합격 테스트 추가 목록은 ②~③ 사이 정지만 있음). "③ 뒤 정지 시 재설정 시도, 실패하면 FAILED·긴급, DONE/SKIPPED로 숨기지 않음" 테스트를 추가한다.
3. [하, 참고] ADR-006:202 앞부분은 여전히 "지금 주석(`PanicLiquidator.java:11`)은 '장 외 시간에는 취소만'"이라 적는다. 상태 열에 "주석은 고침"이 있어 오해 가능성은 낮지만, 이 PR 뒤에는 사실이 아니므로 "고치기 전 주석은"처럼 시점을 밝히면 좋다.
4. [하, 참고] PanicLiquidator.java:8 첫 줄의 2단계 이름 "미체결 전부 취소 확인"은 ADR-006 2.5 표 2단계 이름과 같아 문제는 아니나, 바로 아래 11-12행의 "매도 불가 시 미체결 매수만 취소"와 한 문단에서 읽힐 때 혼동될 수 있다. "(매도 가능할 때)"를 덧붙이는 것을 권고.
5. 확인했지만 문제 아님: (a) 보강분(가) 재설정 순서(매도 취소·체결 확인 → 남은 보유 수량으로만 재설정)는 QA pr-26 발견 1과 일치하고 부분 체결 시 남은 수량 기준을 포함. (b) 보강분(나)는 "일괄 취소가 방금 건 서버 측 손절을 지우지 않게"와 "재설정은 일괄 취소 시각 전"을 적어 발견 2를 반영. RSK-11 본문 반영은 아직이지만 7단계 전 필수 행으로 추적됨. (c) ADR-006:205에 VI 분류 변경을 사용자 결정으로 명시해 발견 4 반영. (d) 주석 수정으로 발견 5(“다음 코드 PR에서 바로”) 해소. (e) 실행 코드 변경이 없어 현재 실전 엔진 동작은 바뀌지 않음(BROKER_NOT_CONNECTED, EngineCommandHandlerTest#… 22건 통과).

## 판정
수정 필요(A9·A14: PanicLiquidator.java:11 매도 불가 예시에 VI 추가(발견 1), ADR-006:203 합격 테스트를 QA pr-26 발견 3대로 고치고 ③~④ 사이 정지 테스트 추가(발견 2))

## 재검토 2026-10-09
- 대상: PR #32 / 커밋 38ab8aa(05ece15·38ab8aa 추가) / main 대비 diff(merge-base 0a4adf4, `git diff origin/main...HEAD`: `PanicLiquidator.java` +4 −3(주석만), `ADR-006-real-control-channel.md` +3 −3, `docs/reviews/pr-32/architect.md`·`risk-ops.md` 신규)
- 점검표: A
- 직접 실행한 것: `./gradlew build --console=plain` → `BUILD SUCCESSFUL`. `./gradlew :autotrade-risk:test --rerun :autotrade-architecture-test:test --rerun :autotrade-app-engine:test --rerun` → 세 test 태스크 실제 실행, `BUILD SUCCESSFUL`. 테스트 결과 XML 집계 522건, 실패·오류·건너뜀 0. `RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `ArchitectureTest` 15, `ModuleOwnershipTest` 13, `EngineCommandHandlerTest` 22 통과
- 원문 보존 확인: 이 파일의 앞 내용은 22555e9 이후 다른 커밋이 고치지 않음(`git log -- docs/reviews/pr-32/risk-ops.md`가 22555e9 하나)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 실행 코드 변경 0행(주석만). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 변경 없음. `ArchitectureTest` 15·`ModuleOwnershipTest` 13 통과. 주입 구현은 `BROKER_NOT_CONNECTED`(EngineMain.java:117) |
| A3 | 통과 | 추가 문구(ADR-006:203)는 같은 계좌 내 재설정·FAILED 표시만 다룸. 증권사 전환 없음 |
| A4 | 통과 | 재설정 주문 "타임아웃 시 재전송 없이 조회" 합격 테스트 유지(ADR-006:203) |
| A5 | 통과 | 멱등 키 요구 변경 없음 |
| A6 | 통과 | 블랙리스트·수동 보유 제외 문구 유지(PanicLiquidator.java:11) |
| A7 | 통과 | 큐·토큰 버킷·계좌 격리 변경 없음 |
| A8 | 해당 없음 | 프로세스·정지 순서 변경 없음 |
| A9 | 통과 | 앞 검토 발견 1 해소: PanicLiquidator.java:11이 "거래정지·VI·LULD 정지 등. VI 분류는 ADR-006 6장에서 사용자 결정"으로 D-037(decisions/README.md:42)·RSK-13(autotrade-spec.md:191)·ADR-006:99와 일치. 서버 측 손절 취소 금지·상태 불명 시 매도 불가 계약 유지(PanicLiquidator.java:12,20-21) |
| A10 | 통과 | 추가 diff 전문 확인. 비밀값 없음 |
| A11 | 통과 | 테스트 파일 변경 없음. 보호 테스트 통과. ADR-006:203 합격 테스트에 재설정 실패 시 FAILED·긴급·Guard OFF 없음, ③ 뒤 ④ 전 정지 시 재설정 시도·실패 시 FAILED·긴급 테스트가 추가됨(7단계 전 필수) |
| A12 | 통과 | 대응표 17종·O1~O8·장애 테스트 8종 전제 변경 없음. 서버 측 손절을 마지막 방어선으로 두는 6.1·O4·O8과 같은 방향 |
| A13 | 통과 | 시장 분기 없음. `MarketRules` 판정 출처 유지 |
| A14 | 통과 | 앞 검토 발견 2 해소: ADR-006:203 합격 테스트가 QA pr-26 발견 3대로 "재설정이 거절·실패해 서버 측 손절 없이 마감을 넘기면 FAILED·긴급(`urgent`) 표시와 Guard OFF 없음(DONE·SKIPPED로 숨기지 않음)"으로 바뀌고, ③~④ 사이 정지 테스트 추가. 주석·ADR·D-037·RSK-13의 매도 불가 예시 일치 |

## 점검표 밖 발견 (재검토)
1. 앞 검토 참고 3 해소: ADR-006:202가 "옛 주석(…)은 … 고쳤다", 상태 칸 "주석은 PR #32에서 고침"으로 시점이 분명해짐.
2. 앞 검토 참고 4 해소: PanicLiquidator.java:8 "미체결 취소 확인(매도 불가면 매수만)". 이 줄은 매도 불가 시 ③을 건너뛴다는 점은 적지 않지만 바로 아래 11-12행이 적으므로 문제 아님.
3. 남은 후속(머지를 막지 않음, ADR-006 6장에서 7단계 전 필수로 추적): `PanicLiquidator` 실패 테스트, RSK-11 일괄 취소와 서버 측 손절 관계의 기획서 반영, 새 합격 테스트의 RSK-13 인수 조건 반영(pm-lead), VI 분류 사용자 결정.
4. 이 PR은 실전 관련 PR이므로 지침 10·11장에 따라 독립 QA 후 사용자가 직접 머지한다.

## 판정 (재검토)
승인

## 재검토 2026-10-09 (2차, 독립 QA 수정 필요 반영 후)
- 대상: PR #32 / 커밋 1827f8c(0c0d613 뒤 추가: 4441029·1477dfb architect 원문, 45607ca QA 원문, 1827f8c 수정) / main 대비 diff(merge-base 0a4adf4, `git diff origin/main...HEAD`: `PanicLiquidator.java` +7 −3(주석만), `ADR-006-real-control-channel.md` +3 −3, `docs/reviews/pr-32/`의 architect·qa·risk-ops 원문)
- 점검표: A
- 직접 실행한 것: `./gradlew build --console=plain` → `BUILD SUCCESSFUL`. `./gradlew :autotrade-risk:test --rerun :autotrade-architecture-test:test --rerun :autotrade-app-engine:test --rerun` → 세 test 태스크 실제 실행, `BUILD SUCCESSFUL`. 테스트 결과 XML 집계 522건, 실패·오류·건너뜀 0. `RealTradingGuardTest` 31, `GuardedOrderGatewayTest` 8, `ArchitectureTest` 15, `ModuleOwnershipTest` 13, `EngineCommandHandlerTest` 22 통과
- 원문 보존 확인: 이 파일은 22555e9·0c0d613(둘 다 risk-ops 커밋) 외에 바뀐 적 없음(`git log -- docs/reviews/pr-32/risk-ops.md`)
- 먼저 밝힐 것: 독립 QA 발견 1(② 시점 매도 불가 → ③ 시점 매도 가능 방향)은 앞의 두 risk-ops 검토가 놓친 보호 공백이다. QA 지적이 타당하다고 판단하고 이번 수정을 그 기준으로 대조했다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 실행 코드 변경 0행(주석만). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 변경 없음. `ArchitectureTest` 15·`ModuleOwnershipTest` 13 통과. 주입 구현은 `BROKER_NOT_CONNECTED`(PanicLiquidator.java:33-38, EngineMain.java:117) |
| A3 | 통과 | 추가 문구는 같은 계좌 안의 단계 진행·결과 값만 다룸. 증권사 전환 없음 |
| A4 | 통과 | 재설정 주문 "타임아웃 시 재전송 없이 조회" 유지(ADR-006:203). 새 규칙은 매도 불가로 시작한 실행에서 ③~⑤를 하지 않아 주문 수를 줄이는 방향 |
| A5 | 통과 | 멱등 키 요구 변경 없음. 재실행은 사용자가 PANIC을 다시 보내 ②부터 시작(PanicLiquidator.java:20-22). 같은 주문의 재전송이 아님 |
| A6 | 통과 | 블랙리스트·수동 보유 제외 유지(PanicLiquidator.java:11) |
| A7 | 통과 | 큐·토큰 버킷·계좌 격리 변경 없음. 종목 단위 처리 범위가 ADR-006:205에 추가됐지만 계좌 간 영향 없음 |
| A8 | 해당 없음 | 프로세스·정지 순서 변경 없음 |
| A9 | 통과 | QA 발견 1 해소: PanicLiquidator.java:20-22와 ADR-006:203 (다)가 "② 직전 판정이 매도 불가면 그 실행에서 ③~⑤를 하지 않음, 매수만 취소한 ②는 SKIPPED, ③~⑤ SKIPPED, 정지가 풀려도 ③으로 넘어가지 않고 사용자 재실행으로 ②부터"를 정함. 미체결 매도가 남은 채 서버 측 손절을 지우는 경로가 계약상 막힘. 현재 처리기와의 정합도 직접 확인: Guard OFF는 ②~⑤ 모두 DONE일 때만(EngineCommandHandler.java:290-296 `allBrokerStepsDone`), ③이 DONE이 아니면 6단계 설명은 "손절·익절과 서버 측 손절 계속"(비긴급, EngineCommandHandler.java:278-287)이라 실제 상태와 맞음. 매도 가능 판정 시 기존 서버 측 손절 취소·재설정 계약과 VI 포함 매도 불가 예시 유지 |
| A10 | 통과 | 추가 diff 전문 확인. 비밀값 없음 |
| A11 | 통과 | 테스트 파일 변경 없음. 보호 테스트 통과. ADR-006:203 합격 테스트에 "② 시점 매도 불가 → ③ 시점 매도 가능이어도 ③ 미수행(②·③~⑤ SKIPPED, Guard OFF 없음)"이 추가됨(7단계 전 필수) |
| A12 | 통과 | 대응표 17종·O1~O8·장애 테스트 8종 전제 변경 없음. O4(LULD 중 보유분 유지)·6.1(서버 측 손절은 마지막 방어선)과 같은 방향 |
| A13 | 통과 | 시장 분기 없음. 판정은 계좌·종목의 시장 단위, `MarketRules` 출처 유지 |
| A14 | 통과 | QA 발견 2·3 반영 확인: ADR-006:205에 "종목 단위로 건너뛰면 ②의 매도 취소와 ③은 매도 가능 종목에만", ADR-006:203 (나)에 "일괄 취소 대상에 서버 측 손절 포함 여부는 사용자 결정(지침 8장 2번)", 기획서 RSK-11·RSK-13 반영 추적 문장이 (가)~(다) 뒤로 옮겨져 새 합격 테스트 전부와 일괄 취소 대상 정의를 덮음(pm-lead). 주석·ADR·D-037·RSK-13의 매도 불가 규칙 서로 일치 |

## 점검표 밖 발견 (2차 재검토)
1. [하, 참고] 매수만 취소한 ②를 SKIPPED로 보고하면 "실제로 매수 취소는 했다"는 사실이 결과 값에서 사라진다. `PanicStepResult`에 `note`가 있으므로(autotrade-control/.../PanicStepResult.java:12) 7단계 구현 때 ②의 note에 "매도 불가(사유)라 미체결 매수 N건만 취소, 미체결 매도·서버 측 손절 유지"처럼 실제 처리와 사유를 적을 것을 권고. 결과 값을 새로 늘리는 대안(예: PARTIAL)도 있으나 처리기·화면 계약을 바꾸므로 이 PR 범위 밖이며, SKIPPED는 Guard OFF를 막는 안전한 쪽이라 머지를 막지 않는다.
2. [하, 참고] "② 직전 판정"의 대상 단위가 종목 단위 건너뛰기(ADR-006:205, 미결정)와 만날 때, 일부 종목만 매도 불가인 계좌에서 ②를 SKIPPED로 볼지 DONE으로 볼지는 :205 결정 때 같이 정해야 한다. :205가 이미 "②의 매도 취소와 ③은 매도 가능 종목에만"을 적어 방향은 정해져 있으므로 7단계 전 필수 항목 안에서 추적된다.
3. 남은 후속(머지를 막지 않음, ADR-006 6장에서 7단계 전 필수로 추적): `PanicLiquidator` 실패 테스트, RSK-11·RSK-13 기획서 반영(pm-lead), VI 분류·일괄 취소 대상·종목 단위 처리에 대한 사용자 결정.
4. 이 PR은 실전 관련 PR이므로 지침 10·11장에 따라 독립 QA 재검토 후 사용자가 직접 머지한다.

## 판정 (2차 재검토)
승인
