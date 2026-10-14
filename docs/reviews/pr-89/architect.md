# architect 검토: 1단계 QA C5 정리(Q-009 닫기 D-077, ADR-002 채택 D-078, ADR-001 2.4·2.7 정정, 스모크 매수 행 실현손익 거부)
- 대상: PR #89 / 커밋 6fbcf38ed5a4c5175b3e94a792a37e36b7bcc67e / `git diff origin/main...HEAD` 전체(5개 파일, 문서 4 + CI SQL 1, Java·Gradle·마이그레이션 변경 0건)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --console=plain` -> BUILD SUCCESSFUL (130 tasks, 19 executed, 111 up-to-date). 이 PR은 Java를 바꾸지 않아 대부분 UP-TO-DATE이며, 캐시된 test 결과에 의존한다는 한계가 있다.
  - 로컬 PostgreSQL 16(별도 클러스터, 검토 후 종료·삭제)에 `autotrade-app-lab/src/main/resources/db/migration/V1,V2`를 CI와 같은 순서로 적용하고 `psql -v ON_ERROR_STOP=1 -f scripts/ci/schema-smoke.sql` 실행 -> 전 구간 통과. 새 줄의 `NOTICE: ok: buy row with realized pnl rejected` 출력 확인.
  - 변이 확인: 새 스모크 행의 `realized_pnl`을 1000에서 NULL로 바꿔 돌리면 `ERROR: buy row with realized pnl was accepted`로 실패한다. 즉 새 검사는 V1 `CHECK ((side = 'BUY' AND realized_pnl IS NULL) OR side = 'SELL')`(V1__lab_baseline.sql:90)가 실제로 거부해서 통과하는 것이지, 다른 제약 때문에 우연히 통과하는 것이 아니다.
  - 정정 주장 대조: `Clock.system`을 만드는 운영 코드는 `EngineMain`, `GatewayMain`, `KiwoomDailyMain`, `WatchdogMain`, `CollectorMain`, `ExperimentMain`, 실험실 `CoreConfiguration` 7곳(grep, 테스트 제외). `ReplayClock.java:12`는 Javadoc 문구뿐이다. `ArchitectureTest.java:120-126` `CLOCK_SOURCE`는 `com.autotrade.app.` 패키지의 `*Main`과 `CoreConfiguration`으로 정정문과 일치한다.
  - 날짜 대조: ae4343d(#9), 53a7de9(#7) 커밋은 KST 2026-10-09 이지만 UTC로는 2026-10-08이다. 저장소가 날짜를 UTC 기준으로 써 왔으므로 "2026-10-08 머지"와 모순으로 보지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공. 단 Java 무변경이라 UP-TO-DATE 비중이 크다. 이 PR의 실질 변경인 스키마 스모크는 별도로 직접 실행해 통과 |
| B2 | 해당 없음 | 모듈 의존·ArchUnit 규칙 변경 없음. `ArchitectureTest.java`를 읽기만 했고 diff에 없음. 정정문(ADR-001:122)이 기존 규칙을 정확히 서술함 |
| B3 | 통과 | 계약 변경 없음(인터페이스, 스키마, 설정 키, REST). `schema-smoke.sql`은 CI 검증 스크립트이며 `V*.sql` 불변. ADR-001 2.7 정정(ADR-001:198)이 스모크 추가와 같은 PR에서 맞춰짐 |
| B4 | 해당 없음 | 시뮬레이션 코드·골든 테스트 변경 없음. ADR-001 2.4 정정은 설명이며 규칙 자체는 불변 |
| B5 | 해당 없음 | 금액 코드 변경 없음. 스모크 SQL의 금액은 NUMERIC 컬럼 |
| B6 | 통과 | 새 동작(스모크의 매수 행 거부 경우)에 실패 경로 포함 테스트가 있고 위 변이로 판별력 확인. 테스트 삭제·약화·건너뜀 없음(diff에 삭제된 검증 줄 없음, 주석 줄 1건만 수정) |
| B7 | 확인 필요(risk-ops) | Java·실전 코드 경로 변경은 없다. 다만 ADR-002(RealTradingGuard 설계 문서)의 상태를 "제안"에서 "채택"으로 바꾸고, D-078이 Guard 결정 8건을 결정 로그에 새로 기록한다. 실전 안전장치에 관한 기록이므로 **risk-ops 검토 필요**: D-078 ①~⑧이 ADR-002 7장과 의미가 같은지, "사용자 결정(PR #9 머지)" 귀속이 맞는지. 이 검토는 architect가 대신하지 않음 |
| B8 | 통과 | diff 전체에 키·계좌번호·토큰 없음. 스모크의 값은 해시 자리표시자와 종목코드뿐 |

## 점검표 밖 발견
각 항목은 승인을 막지 않는 문서 일관성 지적이다(수정 권고).

1. [중] 기획서가 열린 상태로 남음. `docs/spec/autotrade-spec.md:360`의 Q 목록은 Q-009를 여전히 열린 항목("메타·상태 DB 엔진(추천: PostgreSQL) ADR")으로 둔다. 이 PR은 결정 로그에서 Q-009를 닫았으나 기획서는 단일 원본(지침 2장)이다. autotrade-spec-keeper로 기획서 버전과 변경 이력을 올려 맞추거나, 다음 정리 PR에서 처리한다고 PR 본문에 남길 것.
2. [하] ADR 본문에 낡은 문구가 남음. `ADR-001-module-structure.md:249`("결정 로그 번호는 pm-lead가 다음 정리 때 부여한다")와 `ADR-002-real-trading-guard.md:165`("머지 후 pm-lead가 부여한다")는 번호가 이제 D-077/D-078로 부여됐는데 그대로다. ADR-002는 머리말에만 D-078 링크가 있고, ADR-001은 6장에 D-077 링크가 없다. 한 줄씩 갱신하면 추적이 닫힌다.
3. [하] D-077 ⑩과 D-078 ③의 연결이 느슨함. D-077 ⑩이 "D-078 ③에서 확정"이라 하지만 D-078 ③은 `accounts` 배열과 `account-numbers` 맵만 말한다. `enabled` 키를 저장하지 않는다는 내용은 ADR-002 본문(ADR-002:70, 146)에 있다. 내용은 맞고 참조만 부정확하다. D-078 ③에 한 구절을 넣거나 참조를 ADR-002 해당 절로 바꿀 것. B7의 risk-ops 검토에서 함께 보면 된다.
4. [하] 결정 번호 D-076이 비어 있음. D-075 다음이 D-077이다. 이 저장소(origin/main 포함)의 docs에서 D-076을 찾지 못했다. 다른 브랜치에서 예약된 번호일 가능성은 있으나 `[추론]`이며 확인하지 못했다. 결번이 의도라면 PR 본문에 한 줄 남길 것.
5. [참고] 이 PR의 D-077·D-078은 이미 머지된 결정(PR #7, #9)의 사후 번호 부여이고 새 결정을 만들지 않는다. 결정 로그 문구가 "추천안 채택(자율)"(D-077)과 "사용자 결정(PR #9 머지)"(D-078)로 구분된 점은 적절하다. ADR-001 6장의 항목 10은 ADR 안에서 "제안, risk-ops 확정"이었으므로 D-077이 D-078 ③에 의존시키는 처리는 타당하다.

## 판정
**승인**. 확인 못 함 항목 없음(B7은 "해당 코드 변경 없음, 문서의 Guard 결정 기록에 대해 risk-ops 검토 필요"로 명시). 점검표 밖 발견 1번(기획서 Q-009)은 머지 전 또는 직후 정리를 권고한다. 단 지침 10장상 이 PR의 머지는 실전 안전장치 기록(ADR-002/D-078)을 건드리므로 risk-ops 판정을 함께 본 뒤에 진행할 것.
