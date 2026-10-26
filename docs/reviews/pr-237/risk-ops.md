# risk-ops 검토: 기획서 v14.59 — D-140(7단계 결정 6건) 반영 (RSK-01·11·13, TST-01, 14·15장)
- 대상: PR #237 / 커밋 `7261cf21`(변경), 브랜치 헤드 `6fdeb656`(architect 원문 추가만) / main(`085a1781`) 대비 diff. 바뀐 파일 `docs/spec/autotrade-spec.md` 하나(+10 −10)
- 점검표: A
- 직접 실행한 것:
  - `git diff origin/main...origin/claude/project-thread-hcybc4` 전체(word-diff)를 [D-140](../../decisions/D-140-stage7-panic-and-first-broker.md) ①~⑥ 문구, [결정 로그](../../decisions/README.md):146·169-174, [broker-and-fault-design.md](../../team/broker-and-fault-design.md):190(3-R), [ADR-002](../../adr/ADR-002-real-trading-guard.md) 2.1, [ADR-006](../../adr/ADR-006-real-control-channel.md) 2.4-2.5와 한 줄씩 대조했다.
  - 영향받는 실전 경로 코드 확인: `EngineCommandHandler.java:243-256`(PANIC 1단계 `lock`), `RealTradingGuard.java:225-250`(`failActivation`·`deactivate`), `PanicLiquidator.java:9-21`(계약 주석).
  - `./gradlew build`: **컨테이너에서 끝까지 못 돌렸다.** Maven Central이 프록시 너머로 같은 파일에 200/404를 번갈아 돌려주고(curl 6회 중 4회 404), 뒤에는 429를 돌려줬다. 기본 저장소로 여러 번 재시도한 결과 `:autotrade-app-engine:test`는 실행됐다(6개 클래스 68건, 실패 0, 오류 0, 건너뜀 0, `EngineCommandHandlerTest` 포함). `:autotrade-risk:test`·`:autotrade-architecture-test:test`는 의존성 해석(`spring-boot-loader-tools:3.5.16` 등 Could not find)에서 멈춰 실행되지 않았다(마지막 3회 재시도도 같은 원인). 다른 미러로 우회하는 방법은 쓰지 않았다. 같은 작업 트리에 다른 검토자 실행이 남긴 결과 XML(`autotrade-risk` 55건, `autotrade-architecture-test` 46건, 실패 0)이 있지만, 내가 돌린 것이 아니라 근거로 쓰지 않는다.
  - 빌드 근거 보충(코디네이터 지시, 사용자가 앞서 정한 방식): GitHub check-runs API로 헤드 `7261cf21`와 `6fdeb656`를 직접 조회했다. 두 커밋 모두 `Gradle build` success, `DB schema (PostgreSQL 16)` success, `Lab screens (Playwright)` success, `Secret scan and collector boundary` success. 이 PR에는 코드·테스트·빌드 파일 변경이 없어(diff stat 1개 문서) CI 결과가 그대로 이 변경의 빌드 결과다.
  - `scripts/ci/protected-paths.txt` 대조: `docs/spec/autotrade-spec.md`는 보호 경로가 아니다. 그래도 RealTradingGuard·PANIC·장애 테스트 규칙 문구라 실전 관련 문서로 보고 A 점검표를 전부 적용했다.
  - diff의 추가 줄에서 키·토큰·계좌번호 형식을 grep했고 0건이었다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RSK-01 "기본 OFF, 재기동 시 OFF", "재기동 시에는 보호 모드로도 자동 진입하지 않고 OFF로 시작"은 그대로 두었다. 새 문구 "이 OFF 계좌에서 PANIC SELL을 누르면 그 2차 확인으로 보호 모드로 올려 매도"는 사용자 2차 확인을 거치는 상향이라 ADR-002 2.1(`ADR-002-real-trading-guard.md:41` 올라가는 전이는 `SecondConfirmation` 필요, `:42` 자동 상향 없음)과 맞는다. RSK-13에 "확인 실패·잠김·비밀번호 미설정이면 OFF 계좌는 OFF 그대로"와 "OFF 계좌를 확인 없이 올리지 않는다(주문 세션을 여는 상향 금지)"가 들어갔고, 인수 조건에 "확인 실패 PANIC에서 OFF 계좌는 OFF이고 주문 세션 허가 발급 0건 테스트"가 들어갔다. 코드·설정·테스트 변경 없음 |
| A2 | 통과 | 코드·ArchUnit 변경 없음(diff stat). 바뀐 문구는 주문 출구를 새로 만들지 않는다. PANIC 매도도 PROTECTIVE 허가(ADR-002 2.1 표 `:39`)를 받은 뒤의 보호 동작으로만 적혀 있다. CI `Gradle build`(ArchUnit 포함) success(`7261cf21`, `6fdeb656`) |
| A3 | 통과 | 14장 7단계에 "첫 실전 증권사는 한국투자(D-140 ⑥)"만 추가했다. 증권사 사이 주문 전환 문구는 없다. RSK-13·RSK-11 새 문구도 계좌의 증권사 밖으로 주문을 보내는 경로를 만들지 않는다(서버 측 손절 재설정도 "남은 보유에 다시 건다"뿐) |
| A4 | 해당 없음 | 타임아웃·재전송 규칙(ORD-*)은 diff에 없다. PANIC ⑤ 체결 확인 상한을 VI 때 늘리는 것은 재주문이 아니라 대기 시간이고, 상한 안 미체결이면 재매도가 아니라 서버 측 손절 재설정으로 끝난다(RSK-13 새 문구) |
| A5 | 해당 없음 | 멱등 키 규칙 변경 없음 |
| A6 | 통과 | RSK-13 "대상은 자동 매매 포지션만(블랙리스트·수동 보유 제외)"는 그대로 두었다. 새 OFF 계좌 PANIC 경로도 같은 RSK-13 안의 단계라 같은 대상 제한을 받는다. 인수 조건 "블랙리스트·수동 보유 종목 미매도 테스트"도 그대로다. RSK-02·RSK-12 무변경 |
| A7 | 통과 | 전체 PANIC에서 OFF 계좌를 올릴 때 "계좌마다 확인을 받는다"(RSK-13 새 문구, D-140 ①)와, 일부 종목 매도 불가 시 "그 종목만 건너뛰고"를 계좌 단위로 적었다. ADR-002 2.1 확인은 계좌 일치·1회용(`:41`)이라 한 계좌 확인이 다른 계좌를 올리지 않는다. 직렬 큐·토큰 버킷 문구 변경 없음 |
| A8 | 해당 없음 | 프로세스 분리·정지 순서 문구(RSK-09·10, broker-and-fault-design.md 5.2) 변경 없음 |
| A9 | 통과 | 보호가 강해지는 쪽으로만 바뀌었다. ① RSK-11 일괄 취소 대상에서 서버 측 손절 조건주문 제외(D-140 ⑤)와 그 테스트, ② 장 외 PANIC은 Guard를 끄지 않고 보호 모드·서버 측 손절 유지(D-140 ②, v13 "Guard OFF" 대체), ③ VI 종목이 상한 안에 체결되지 않으면 서버 측 손절 재설정, ④ 매도 불가 종목은 ③ 서버 측 손절 취소를 하지 않음(유지), ⑤ PANIC 재설정은 일괄 취소 시각 전에 끝냄. 보호 시세 경로(RSK-10) 무변경 |
| A10 | 통과 | diff 추가 줄 grep(appkey·secret·token·password·계좌번호 형식) 0건. CI `Secret scan and collector boundary`(gitleaks) success |
| A11 | 통과 | 보호 테스트·고정 값 변경 없음(diff는 기획서 하나). 새 동작마다 실패하는 경우의 인수 테스트가 RSK-13·RSK-11·TST-01에 추가됐다: OFF 계좌 확인 실패 시 허가 0건, 대사가 매도보다 먼저이고 뒤 단계 실패 시 PROTECTIVE 유지, 장 외 PANIC 뒤 Guard 유지, VI 상한 연장·미체결 시 재설정·마감 초과 시 매도 불가, 제출 뒤 VI 연장, 일부 종목 매도 불가 시 그 종목만 건너뛰고 Guard OFF 없음, 일괄 취소가 서버 측 손절(방금 다시 건 것 포함)을 취소하지 않음. 테스트 삭제·약화 문구 없음. 로컬 `:autotrade-app-engine:test` 68건 통과 |
| A12 | 통과 | TST-01은 "8종"을 그대로 두고 3번 하위 항목 3-R을 넣었다(D-140 ③). 합격 기준 원문을 `broker-and-fault-design.md:190`(이미 main에 3-R 행이 있음)으로 가리키고, 요약도 그 행과 어긋나지 않는다(두 번째 엔진 기동 거부, 거부 못 하면 허가·토큰 0건, "가상"·OFF 오표시 없음, Windows 포함 운영 PC 시행). RSK-08 장애표 17종·O1~O8 문구 무변경 |
| A13 | 통과 | 시장별 차이는 RSK-11 세션 구간표(`MarketRules`)로 남아 있고 시각 기본값 변경 없음. 매도 불가 목록에 미국 LULD 정지를 유지했고(옛 문구에서 이어옴, D-140보다 보수적), VI 단일가 시간(2분 + 최대 30초 `[가정]`)은 국내 VI에만 붙는다. 금액·시간대 계산 문구 변경 없음 |
| A14 | 통과 | D-140 ①~⑥과 기획서 반영을 하나씩 대조했다. ① RSK-01·RSK-13(대사 먼저, 계좌마다 확인, 확인 통과 뒤 실패면 PROTECTIVE 유지, 확인 실패면 OFF 그대로, 1단계 고정은 OPEN·ACTIVATING만) 일치. ② RSK-13 장 외 Guard 유지와 v13 문구 대체 일치. ③ TST-01 3-R, 8종 유지 일치. ④ RSK-13 VI 매도 가능, 상한 연장, 마감 시각 초과 시 매도 불가, 제출 뒤 VI 연장, 매도 불가 종목만 건너뜀, 건너뛰면 Guard OFF 없음 일치. ⑤ RSK-11 일괄 취소 대상 정의와 시각 전 재설정 일치. ⑥ 14장 한국투자 일치. 15장 Q-016·017·018 삭제와 "닫은 항목" 기록은 결정 로그 `README.md:169-171`과 일치하고, Q-057·058·061이 기획서 15장에 원래 없었다는 기록은 `git show origin/main:docs/spec/autotrade-spec.md`에서 세 번호가 0건인 것으로 확인했다. 기획서가 ADR-006과 D-140이 다르면 D-140이 우선한다고 적어, 아직 옛 문구인 ADR-006(`:101-104`, `:154`, `:160`)과의 차이를 숨기지 않고 정리했다 |

## 점검표 밖 발견
1. **(비차단, 묶음 E에서 명시 권고) ACTIVATING 계좌의 1단계 "고정" 뜻.** 새 문구 "이 고정은 OPEN·ACTIVATING 계좌에만 한다"를 글자대로 읽으면 ACTIVATING도 PROTECTIVE로 올리는 것처럼 읽힐 수 있다. 실제 기준(ADR-006 2.4 `:94` "ACTIVATING → `failActivation`", 코드 `EngineCommandHandler.java:248`, `RealTradingGuard.java:225-231`)은 **활성화를 중단하고 출발 상태로** 내리는 것이라, OFF에서 출발한 ACTIVATING은 OFF로 돌아간다. ACTIVATING(OFF 출발)을 확인 없이 PROTECTIVE로 올리면 청산·손절 허가가 새로 생기는 상향이다(ADR-002 2.1 표 `:36` 대 `:39`). 이는 D-140 ①의 "확인 없이 주문 세션을 여는 상향 금지"와 어긋난다. 기획서가 단계별 처리 기준을 ADR-006에 두고 있어 이번 PR의 결함으로 보지는 않는다. 묶음 E 전에 ADR-006을 맞출 때 다음 두 가지를 적고 실패 테스트로 고정하기를 권고한다. (a) ACTIVATING은 출발 상태로 내린다. (b) 그렇게 OFF가 된 계좌에도 같은 PANIC의 2차 확인으로 D-140 ① OFF 경로를 적용할지(보유가 있을 때).
2. **(비차단) 인수 조건에 빠진 시험 하나.** "전체 PANIC에서 OFF 계좌를 올리는 것은 계좌마다 확인"(D-140 ① 조건)에 맞는 실패 테스트가 RSK-13 인수 조건에 없다. 예: 전체 PANIC 확인 하나로 OFF 계좌 두 개가 함께 올라가지 않는 테스트. 묶음 E `EngineCommandHandlerTest`에 넣기를 권고한다.
3. **(비차단) 기존 인수 조건 문구.** "2차 확인 실패·잠금·비밀번호 미설정에서도 보호 모드 고정·청산 미수행 테스트"는 이제 OPEN·ACTIVATING 계좌에만 해당한다. OFF 계좌는 새 조건 "OFF이고 허가 0건"이 따로 다룬다. 두 조건이 함께 있어 해석 충돌은 없지만, 다음 판에 "OPEN·ACTIVATING 계좌는"이라고 한정하면 더 분명하다.
4. **(추적) 구현과 기획서의 차이.** 지금 코드는 OFF 계좌 PANIC의 증권사 단계를 건너뛴다(`EngineCommandHandler.java:258` 주석, ADR-006 `:102`·`:168` "OFF 계좌 건너뜀"). 이것은 D-140 ① 이전의 대안이다. 지금은 `BROKER_NOT_CONNECTED`라 주문이 나가지 않아 위험은 없다. 하지만 7단계 실전 활성화 전에 묶음 E(P1~P8)에서 D-140대로 바꾸고 위 인수 테스트로 고정해야 한다. 기획서 담당 표(risk-ops v14.59 행)와 D-140 후속에 기록돼 있다.
5. **(환경) 로컬 빌드.** 위 "직접 실행한 것"대로 `:autotrade-risk:test`와 `:autotrade-architecture-test:test`는 컨테이너 저장소 응답 문제로 로컬에서 실행하지 못했다. 문서 한 파일만 바뀐 PR이라 헤드 커밋 CI `Gradle build` 성공을 빌드 근거로 썼다(코디네이터 지시, 사용자가 앞서 정한 방식). 코드가 바뀌는 PR이었다면 이 근거만으로 승인하지 않는다.

## 판정
승인
