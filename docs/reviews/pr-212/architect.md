# architect 검토: PR #205 QA Q1·Q2 시험 보강(장중 순위 시각 전 신호, 깔때기 미집계)과 QA 처리 기록
- 대상: PR #212 / 커밋 5a6cae408ca9b802ab83e1935acc66d1de01f50c / main(613ef94) 대비 diff (`git diff origin/main...origin/claude/f7-blocked-signal-quota-oh76cf`, 4파일 +63 −6)
- 점검표: B
- 직접 실행한 것:
  - 별도 작업 트리(`/tmp/arch212`, PR 헤드)에서 `./gradlew build --no-daemon` → BUILD SUCCESSFUL(4분 12초). 시험 결과 XML 합계 1,853건, 실패 0, 오류 0, 건너뜀 0. 새 시험 2건이 결과에 있음(`SelectionEntryQuotaTest#장중_순위_시각_전_신호는_…`, `CatalogStrategyTest#실행기가_받지_않는_신규_진입은_…`).
  - 변이 P1(`RunStepper.admits`가 `allowsEntry(symbol, Long.MAX_VALUE)`로 판정, 순번 무시): `:autotrade-sim:test --tests '*SelectionEntryQuotaTest'` → 5건 중 1건 실패, 실패는 새 시험(매수 `[]`, 기대 `[KRX:000660]`). 되돌림.
  - 변이 P10(`ManagedStrategy`가 `admitsEntry` false일 때도 `funnel.signal(today)`): `:autotrade-strategy:test --tests '*CatalogStrategyTest'` → 21건 중 1건 실패, 실패는 새 시험. 되돌림(작업 트리 깨끗함 확인).
  - GitHub REST: PR `mergeable: true`, `mergeable_state: clean`, base 613ef94. 헤드 커밋 체크 4종(Gradle build, DB schema, Lab screens, Secret scan and collector boundary) 모두 success.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | PR 헤드에서 직접 `./gradlew build` 성공, 1,853건 실패 0·건너뜀 0 |
| B2 | 통과 | 운영 코드·빌드 파일 변경 없음. 새 시험의 `com.autotrade.marketdata.rules.BundledMarketRules` 사용은 `autotrade-sim/build.gradle.kts`의 기존 `implementation(project(":autotrade-marketdata"))` 범위 안이고 sim 시험에 선례가 있음(`IntradayTrackerTest`). ArchUnit·순수 모듈 금지 의존 검사가 build 안에서 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 문서 변경은 D-130 "후속 반영" 절 추가(SelectionEntryQuotaTest·CatalogStrategyTest 내용과 맞음)와 `docs/qa-out-status.md` 처리 기록 한 줄 |
| B4 | 통과 | 새 시험은 고정 시각(`DAY` 2026-03-03, Tape 기본 2026-03-02)·고정 시드 7·`signal.per-mille=1000`으로 결정적이다. 시스템 시계·시드 없는 난수 없음. 골든·스냅샷 시험 변경 없이 통과 |
| B5 | 통과 | 새 시험의 가격은 `BigDecimal.valueOf(long)`, 시드머니 `Money.of`. 부동소수 금액 없음 |
| B6 | 통과 | QA 권고 Q1(변이 P1)·Q2(변이 P10)를 고정하는 시험 2건 추가, 검토자가 두 변이를 직접 넣어 각각 새 시험이 실패함을 확인. 기존 시험 변경은 `SelectionEntryQuotaTest`의 `runDetailed`를 `runner`·`data`·`spec` 도우미로 나눈 것뿐이고 생성 인자(데이터·규칙·비용·`ExecutionSettings.defaults()`·시드 1·기준 종목 OUTSIDE·선정 원천·`ExperimentSpec` 값)가 이전과 같음(SelectionEntryQuotaTest.java:175-194). 지우거나 건너뛴 시험 없음 |
| B7 | 해당(risk-ops 검토 필요) | 보호 경로 `autotrade-strategy/*`(`scripts/ci/protected-paths.txt`)의 시험 파일 `CatalogStrategyTest.java`를 바꾼다. 보호 테스트(지침 11장 목록)는 아니고 운영 코드·실전 주문 경로·Guard·블랙리스트·리스크 한도 변경은 없지만, 보호 경로 변경이므로 지침 10·11장에 따라 risk-ops 검토와 사용자 머지 대상이다 |
| B8 | 통과 | diff와 커밋 메시지에 키·계좌번호·토큰 없음. CI gitleaks 스캔 success |

## 점검표 밖 발견
- (정보) `SelectionEntryQuotaTest.java:20` `com.autotrade.marketdata.rules.BundledMarketRules` 가져오기가 `com.autotrade.selection…` 뒤에 있어 가나다(알파벳) 순서가 아니다. 동작 영향 없음, 다음에 이 파일을 고칠 때 정리하면 된다.
- (정보) 새 장중 시험의 막은 수 1은 09:00 하이닉스 체결이 `entry.window.start=09:05` 앞이라 신호가 되지 않는다는 프로필 값에 기댄다. 그 체결은 09:30 순위의 거래대금 입력(관문 `session-turnover > 0`) 역할이다. 주석에 순위 입력 범위만 적혀 있고 진입 창 의존은 적혀 있지 않지만, 창을 바꾸면 시험이 즉시 실패하므로 조용한 공백은 아니다.
- (정보) 실시간 장 규칙(`BundledMarketRules.of(KRX)`)과 시험 규칙(`TestKrxRules`)을 한 시험에 섞는 이유가 79행 주석에 있다(시험 규칙은 늘 정규장이라 첫 이벤트를 늦은 시작으로 봄). 시험 의도에 맞다.
- D-130 후속 반영 절과 `qa-out-status.md` 기록의 "두 변이 모두 잡힘" 주장은 검토자가 직접 재현해 사실로 확인했다.

## 판정
승인

- 실패·확인 못 함 없음. 보호 경로(`autotrade-strategy`) 변경이므로 risk-ops 검토와 사용자 머지가 따로 필요하다(B7).
