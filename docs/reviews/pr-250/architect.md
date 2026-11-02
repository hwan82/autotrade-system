# architect 검토: DART 재무 수집을 사업보고서(정정 포함)만으로 거르기
- 대상: PR #250 / 커밋 ae0b5e995eb6049f24f2ed10e743009ae992a9c6 / main 대비 diff(`git diff origin/main...HEAD`, 2파일 +67 −2)
- 점검표: B
- 직접 실행한 것: `./gradlew build --offline` → BUILD SUCCESSFUL(1m 49s). `DartFinancialCollectorTest` 13건 통과, skipped 0(autotrade-app-collector/build/test-results/test/TEST-com.autotrade.app.collector.DartFinancialCollectorTest.xml). 새 테스트 2건 포함.

## 변경 요약(코드로 확인한 것)
- `DartFinancialCollector.accept`에 보고서명 조건 `annualReport` 추가(DartFinancialCollector.java:184, 189-191). 정규식 `^(\[[^\]]+\])?사업보고서\s*\(\d{4}\.\d{2}\)$`(:45)로 분기·반기보고서와 "사업보고서제출기한연장신고서"를 뺀다.
- `filings()`가 캐시 파일을 읽을 때도 `accept`로 다시 거른다(:203-219). 그래서 이미 캐시에 들어간 분기·반기 항목이 계정 호출(`fetchAccounts`, :231)과 스냅샷(`CollectorMain.java:246` → `DartSnapshotBuilder.build`)에서 함께 빠진다. 캐시 파일은 지우지 않는다(되돌리기 쉬움).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build --offline` 직접 실행, BUILD SUCCESSFUL |
| B2 | 통과 | 변경은 `autotrade-app-collector` 안 두 파일뿐이고 새 의존·새 패키지 없음. 빌드의 ArchUnit(`autotrade-architecture-test`) 통과 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 스냅샷 CSV 열도 그대로(`DartSnapshotBuilder` 미변경). 다만 ADR-008 서술이 낡았다(아래 N1) |
| B4 | 통과 | 시계·난수 사용 변경 없음. 거르기는 보고서명만 보는 순수 함수. `filings()` 정렬(:217) 유지. 골든 테스트 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | `사업보고서와_그_정정만_받고_분기_반기_보고서와_기한연장신고서는_뺀다`(이름 판정 7경우), `공시검색이_분기_반기_보고서를_섞어_줘도_계정은_사업보고서에만_부르고_캐시의_옛_분기_항목도_뺀다`(훑기·계정 호출 수·캐시 옛 항목·스냅샷까지). 지우거나 건너뛴 테스트 없음 |
| B7 | 해당 없음 | 실전 경로·보호 경로(`scripts/ci/protected-paths.txt`) 미포함. 수집기 보호 파일(`CollectorMain`, `ApiCaller` 등)도 안 바뀜 |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음(테스트 기존 자리표시자만 사용) |

## 점검표 밖 발견
- N1(권고, 비차단): ADR-008 2.5의 수용 조건 서술(docs/adr/ADR-008-collector.md:67 "유가·코스닥·코넥스 … 사업연도가 있는 것만")에 새 보고서명 조건이 없고, 같은 문서 :66~67은 `pblntf_detail_ty=A001`이 사업보고서만 준다고 읽힌다. 이 PR이 근거로 든 관찰(A001을 줘도 분기·반기·기한연장신고서가 섞여 온다)은 저장소에 재현 자료가 없어 검토자가 확인하지 못했다 `[가정]`. 다만 거르기 자체는 API 동작이 어느 쪽이든 안전한 방어 조건이라 판정에는 영향이 없다. 후속으로 ADR-008에 "보고서명으로 한 번 더 거른다"와 관찰 출처를 적기를 권한다.
- N2(권고, 비차단): 정규식은 머리 꺾쇠를 하나만 허용한다. "[기재정정][첨부추가]사업보고서 (…)"처럼 겹친 머리가 오면 조용히 빠진다 `[추론]`(OpenDART가 이런 이름을 주는지는 확인 못 함). 이름 조건으로 뺀 건수(또는 뺀 이름의 머리별 건수)를 실행 결과나 스냅샷 통계에 남기면 이런 누락이 보인다.
- N3(데이터 영향, strategy-researcher·data-engineer 참고): 이 PR 전 `DartSnapshotBuilder`는 모든 행을 `OpenDartClient.ANNUAL_REPORT`(11011)로 붙이고 (종목, 사업연도, 보고서코드)로 묶었다(DartSnapshotBuilder.java:123-146). 그래서 "분기보고서 (2025.09)"가 같은 해 사업보고서와 한 묶음에 들어가 원 공시 판정·"값 모름" 통계를 흐렸을 수 있다 `[추론]`. 이 PR 전에 만든 재무 스냅샷과 그것으로 돌린 실험은 다시 만들어 비교하는 것이 맞다. 코드 결함은 이 PR로 고쳐진다.
- 이력: 브랜치에 이미 squash 머지된 PR #137·#241 커밋이 merge로만 합쳐져 있고 main 대비 내용 diff는 위 2파일뿐임을 `git diff origin/main...HEAD --stat`으로 확인했다.

## 판정
승인
