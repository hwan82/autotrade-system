# architect 검토: PR #211 KRX 실제 거래대금 ACC_TRDVAL을 일봉 스냅샷 선택 파일로 수집(ADR-012 후속 F1, D-133)
- 대상: PR #211 / 브랜치 `claude/project-thread-fmuvun` / 커밋 41609c105d201e85b56fda09513962e226616f6e / `git diff origin/main...HEAD` 전체(17파일, +394 −18)
- 점검표: B
- 직접 실행한 것: `./gradlew build` → BUILD SUCCESSFUL(138 tasks, 실패 0). `autotrade-app-collector` 테스트 결과 XML 합계 107건, 건너뜀 0, 실패 0, 오류 0. 새 테스트 7건이 결과에 있음(아래 B6).

## 변경 요약(코드로 확인)
- `KrxOpenApiClient.parse`가 `ACC_TRDVAL`을 `optionalWhole`로 읽음: 없음·빈칸·`-` → `null`, 음수·소수 → `IOException`(KrxOpenApiClient.java:64, 104-118).
- `KrxDailyRow`에 `Long turnover` 추가(KrxDailyRow.java:26).
- `KrxDailySnapshotBuilder.build`에 다섯째 받는 쪽 `turnover`(null이면 쓰지 않음). 일봉을 쓴 행 중 값 있는 행만 `daily_turnover.csv`(`market,code,date,turnover`)에 쓰고, 범위 검사는 원가격 저가·고가 × 거래량(수정 계수 곱하기 전 값, KrxDailySnapshotBuilder.java:286-298, 304-318). 예시는 20건까지만 보관해 메모리 상한 있음.
- `CollectorMain.krxDaily`: `--without-turnover`가 아니면 파일을 열고 ID 해시 기준을 `[daily_bars.csv, daily_turnover.csv]`로(`krxHashFiles`, CollectorMain.java:175-178). 해시 조합은 기존 `SnapshotWriter.commit(List)`(D-123 공시 스냅샷과 같은 `<이름>:<SHA>\n` 규칙, SnapshotWriter.java:67-84)을 그대로 씀. `--without-turnover`는 일봉 단독 해시라 D-133 전 ID와 같음.
- 읽는 쪽 영향: `SnapshotFiles.readVerified`는 이름으로 필요한 파일만 찾고 manifest의 다른 항목은 보지 않음(autotrade-marketdata/.../SnapshotFiles.java:28-45). 스키마 버전 1 유지라 기존 실험실·선정 코드가 새 스냅샷을 그대로 읽음. 보호 경로(`scripts/ci/protected-paths.txt`) 파일 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build` 직접 실행, BUILD SUCCESSFUL. collector 테스트 107건 전부 통과 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-app-collector`의 `com.autotrade.app.collector` 패키지뿐. 새 모듈·새 의존 없음, 선정 모듈 의존을 만들지 않고 파일 이름·헤더 상수를 수집기 쪽에 둠(KrxDailySnapshotBuilder.java:103-111). ArchUnit(`autotrade-architecture-test`) 빌드 안에서 통과 |
| B3 | 통과 | 스냅샷 형식(선택 파일 추가)과 ID 규칙 변경을 ADR-008 2.3·2.4(docs/adr/ADR-008-collector.md:50, 57)와 D-133에 적음. ADR-012 6장 F1에 "수집은 D-133로 구현, 스키마 버전 그대로" 기록. runbook 3.8·5장 갱신. 설정 키·REST·인터페이스 변경 없음(CLI 인자 `--without-turnover`만 추가, 도움말 CollectorMain.java:460) |
| B4 | 통과 | 시각은 주입 `Clock`만 사용(CollectorMain.java:162), 바뀐 파일에 `Instant.now`·`System.currentTimeMillis`·시드 없는 난수 없음(grep). 같은 캐시 → 같은 두 파일 → 같은 ID. 옛 ID 재현은 `CollectorMainTest#krx_daily는_실제_거래대금_파일을_더하고_without_turnover면_옛_형식과_옛_ID를_다시_만든다`가 일봉 바이트 동일·ID = 일봉 SHA 앞 8자리로 확인. 일봉 행은 받는 쪽 유무와 무관(`KrxDailySnapshotBuilderTest#실제_거래대금은_…`의 `usingRecursiveComparison().ignoringFields("turnover")`·`bars` 동일 단언) |
| B5 | 통과 | 거래대금은 원 단위 정수 `Long`, `longValueExact`로 소수 거부(KrxOpenApiClient.java:114). 범위 검사는 `BigDecimal` 곱(KrxDailySnapshotBuilder.java:309-312) |
| B6 | 통과 | 새 테스트: `CollectorMainTest#krx_daily는_실제_거래대금_파일을_더하고_without_turnover면_옛_형식과_옛_ID를_다시_만든다`, `#without_turnover는_krx_daily에만_쓴다`, `CollectorReportsTest#실제_거래대금_칸은_행_수_결측_범위_밖_비율과_예시를_적는다`, `KrxDailySnapshotBuilderTest#실제_거래대금은_쓴_일봉_중_값이_있는_행만_원가격_그대로_따로_쓰고_범위_밖은_경고로_센다`, `#범위_밖_예시는_앞_20건까지만_남기고_수는_전부_센다`, `KrxOpenApiClientTest#실제_거래대금을_읽고_칸이_없거나_비면_null이다`, `#실제_거래대금이_음수거나_소수면_멈춘다`. 모두 결과 XML에 통과로 있음. 지우거나 `@Disabled`한 테스트 없음(diff 확인) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 경로 파일 변경 없음(diff 파일 목록과 protected-paths.txt 대조). 수집기는 실전 엔진과 별도 프로세스 |
| B8 | 통과 | diff에 키·계좌·토큰 값 없음(grep). 새 CLI 경로 테스트가 `assertNoKeyAnywhere()`로 스냅샷·리포트·캐시에 키가 없음을 확인(CollectorMainTest.java:118, 183). gitleaks는 이 환경에 없어 CI 결과로 확인 |

## 점검표 밖 발견
- **m1(문서, 비차단)**: ADR-012 49행이 "실제 거래대금(`ACC_TRDVAL`)은 지금 수집하지 않는다 `[사실]`(…, 2026-10-20 D-133로 수집 추가, 지표는 아직)"로 한 문장 안에서 서로 어긋난다. `[사실]` 표시가 붙은 문장이 이제 틀리므로 "D-133부터 일봉 스냅샷 선택 파일 `daily_turnover.csv`로 수집한다, 이를 읽는 지표 `turnover`는 아직 없다"처럼 고치길 권한다(selection-catalog.md 71행은 이미 그렇게 씀).
- **m2(주석, 비차단)**: `KrxDailySnapshotBuilder.TURNOVER_FILE` Javadoc(KrxDailySnapshotBuilder.java:105-106)이 "읽는 쪽은 `com.autotrade.selection.calc.SnapshotTurnover`다"라고 현재형으로 쓰지만 그 클래스는 저장소에 없다(grep 결과 이 주석과 D-133 6번뿐). "읽는 쪽은 다음 PR의 `SnapshotTurnover`(D-133 6번)"처럼 예정으로 적길 권한다. 다음 PR에서 그 클래스가 같은 이름·헤더를 쓰는지 확인하는 테스트(헤더 상수 대조)를 넣길 권한다.
- 참고(조치 불필요): 시·고·저가가 비어 종가로 채운 행(`filled`)에 거래량이 있으면 범위가 [종가×거래량, 종가×거래량] 한 점이라 대부분 범위 밖 경고로 잡힌다. 경고만이라 동작 문제는 없고, 첫 실행 리포트를 읽을 때 범위 밖 수에 이 행 수(`filledMissingPrice`)가 섞일 수 있음을 알아 두면 된다.
- 참고: D-133 전 스냅샷과 같은 캐시로 만든 새 스냅샷의 ID가 달라지는 것은 의도이며 D-133 3번·위험에 적혀 있다. `--keep-snapshots` 정리는 실험 폴더에 적힌 ID(EDGE01 등)를 남기므로 옛 ID 실험이 깨지지 않는다(SnapshotRetention 기존 동작, 변경 없음).

## 판정
승인
