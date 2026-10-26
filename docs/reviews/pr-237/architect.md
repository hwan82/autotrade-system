# architect 검토: 기획서 v14.59 — D-140(7단계 결정 6건) 반영(RSK-01·11·13, TST-01, 14장 7단계, 15장 Q-016~018)
- 대상: PR #237 / 브랜치 `claude/project-thread-hcybc4` 커밋 `7261cf21` / main 대비 diff (`git diff origin/main...origin/claude/project-thread-hcybc4`: `docs/spec/autotrade-spec.md` +10 −10 한 파일. 코드·빌드 스크립트·설정·스키마·보호 경로 변경 없음. 브랜치 기준점은 main `085a1781`보다 1커밋 뒤이며 그 커밋은 `docs/qa-out-status.md`만 바꿔 겹치지 않음)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`(PR 머리 `7261cf21`, 작업 트리 깨끗) → 처음 여러 번은 build-logic의 Kotlin 2.0.21 의존성 내려받기 실패로 BUILD FAILED. 원인은 Maven Central 응답이 404/200을 번갈아 내는 환경 문제(같은 POM을 `curl`로 12번 받아 404 7번·200 5번 확인). PR과 무관. 저장소 파일은 바꾸지 않고 스크래치 폴더의 init 스크립트로 Maven Central 미러(`maven-central.storage-download.googleapis.com`)를 앞에 덧붙여 `./gradlew build --refresh-dependencies --init-script <스크래치>/mirror.init.gradle` → **BUILD SUCCESSFUL in 3m 56s**, 138 tasks(81 실행). 테스트 결과 XML 합계 1,952건, 실패 0, 오류 0, 건너뜀 0
  - 참고로 GitHub 체크 4종(Gradle build, Lab screens, DB schema, Secret scan and collector boundary)도 `7261cf21`에서 success(REST `check-runs`). 판정 근거는 위 직접 실행
  - diff 문구를 [D-140](../../decisions/D-140-stage7-panic-and-first-broker.md) ①~⑥, 결정 로그 `docs/decisions/README.md:146,169-174`, `broker-and-fault-design.md:190`(3-R), ADR-002 2.1(`ADR-002-real-trading-guard.md:41`)과 한 줄씩 대조
  - 기획서 전체에서 `Q-016|Q-017|Q-018|Q-057|Q-058|Q-061`, `Guard OFF만`, `장 외 시간에 Guard`, `VI`, `PANIC` 검색(남은 옛 문구 확인), 새 상대 링크 `../team/stage-7-readiness-plan.md` 존재 확인, `scripts/ci/protected-paths.txt` 대조(`docs/spec/` 없음), diff에서 키·토큰·계좌번호 형태 문자열 검색(0건)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 `./gradlew build` 성공(위 미러 init 스크립트 사용, 저장소 무변경), 테스트 1,952건 실패·건너뜀 0 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 변경 없음(diff는 `docs/spec/autotrade-spec.md` 하나) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. 요구사항 문구가 확정 결정 D-140을 옮긴 것이고 새 규칙은 없음: RSK-13 ①(OFF 계좌는 2차 확인 통과 시 OFF→PROTECTIVE, 대사 먼저, 뒤 단계 실패 시 PROTECTIVE 유지, 확인 실패·잠김·미설정이면 OFF 그대로, 1단계 고정은 OPEN·ACTIVATING만) = D-140 ①, 장 외 시간 Guard 유지 = ②, VI 매도 가능·상한 연장(2분+30초 `[가정]`)·마감 시각 초과 시 매도 불가·제출 뒤 VI 상한 연장·일부 종목만 건너뜀·건너뛰면 Guard OFF 없음 = ④, RSK-11 일괄 취소 대상(서버 측 손절 제외, 재설정은 일괄 취소 전) = ⑤, TST-01 3-R(8종 유지, 원문 기준은 `broker-and-fault-design.md:190`) = ③, 14장 7단계 한국투자 = ⑥. OFF→PROTECTIVE 상향에 2차 확인이 필요하다는 점은 ADR-002 2.1 전이 규칙(`ADR-002-real-trading-guard.md:41`)과 맞음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 삭제·건너뜀 없음. 문서가 인수 조건(실패 테스트 6건 추가: 확인 실패 시 OFF·허가 0건, 대사 선행·PROTECTIVE 잔류, 장 외 Guard 유지, VI 상한·재설정·마감 초과, 제출 뒤 VI, 일부 종목 건너뜀 / RSK-11 일괄 취소가 손절 미취소)을 늘렸고 D-140 합격 시험과 맞음 |
| B7 | risk-ops 검토 필요 | 코드·보호 경로는 안 바꾸지만 실전 주문 경로·Guard·장애 대응의 요구사항(RSK-01·11·13, TST-01) 문구를 바꾼다. 내용의 실전 안전성 판단은 risk-ops 몫 |
| B8 | 통과 | diff에 API Key·계좌번호·토큰 없음(문자열 검색 0건) |

## 점검표 밖 발견
1. (참고, 수정 요구 아님) [ADR-006](../../adr/ADR-006-real-control-channel.md) 2.5가 아직 옛 문구다: `ADR-006-real-control-channel.md:101` "매도를 낼 수 없을 때(장 외 시간, 거래정지·VI·LULD 정지 등)", `:102` "OFF 계좌는 … 2~6단계를 모두 건너뛴다(7장 Q-016)". 이 PR의 RSK-13이 "ADR-006 문구가 D-140과 다르면 D-140이 우선한다(묶음 E 첫 PR 전에 맞춤)"로 우선순위를 밝혔고, D-140 후속과 기획서 469행(risk-ops 과제)에도 같은 일이 잡혀 있어 이 PR 범위 밖으로 본다. 묶음 E 첫 PR 검토 때 ADR-006 2.5·4장·7장과 `PanicLiquidator` 계약 주석이 맞춰졌는지 확인한다(architect·risk-ops 공동).
2. (참고) `broker-and-fault-design.md:172` 장애 매트릭스 #13 "VI·거래정지 → 해당 종목 신규 주문 금지, 보유분 유지"는 자동 대응 규칙이라 사용자가 누른 PANIC의 VI 매도 허용(D-140 ④)과 직접 부딪치지는 않는다고 읽힌다 `[추론]`. 다만 구현자가 #13을 "VI 종목 매도 금지"로 읽으면 RSK-13과 엇갈린다. 묶음 E에서 #13 행에 "PANIC 매도는 RSK-13 ④ 따름" 같은 한 줄을 넣을지 risk-ops가 판단할 것을 권한다.
3. (참고) 전체 PANIC에서 OFF 계좌는 "계좌마다 확인"을 받는다고 했는데(RSK-13), 확인 문구는 같은 줄에 "전체는 `전체 청산`"으로만 적혀 있다. OFF 계좌 상향 확인의 문구가 계좌 별칭인지는 D-140에도 없다. 실전 계약(ADR-006 2.4 확인 문구)을 맞출 때 정하면 된다. 이 PR이 새로 만들 규칙은 아니다.
4. (사소) 14장 역할별 후속 표에서 새 risk-ops v14.59 행(`autotrade-spec.md:469`)이 v14.8 행들 사이에 끼어 있다. 표가 엄격한 순서는 아니지만 찾기 쉽게 끝이나 v14.5x 근처로 두는 편이 낫다. 판정에 영향 없음.
5. (확인) 15장에서 Q-016·Q-017·Q-018을 미확정 표에서 지우고 닫은 항목에 옮겼고, Q-057·Q-058·Q-061은 기획서에 원래 없던 항목임을 기획서 전체 검색으로 확인(닫은 항목 줄에서만 나옴). 결정 로그 닫은 표(`docs/decisions/README.md:169-174`)와 맞음.

## 판정
승인
