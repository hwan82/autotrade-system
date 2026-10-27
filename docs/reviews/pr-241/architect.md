# architect 검토: DART 재무 스냅샷을 공시마다 계정 응답을 읽어 만들기(전체 수집 OutOfMemoryError)
- 대상: PR #241 / 커밋 d4a471d / main 대비 diff(`git diff origin/main...claude/project-thread-pb3vv4`). 코드 변경은 f4cd797 한 커밋, 파일 3개 +96 -35(CollectorMain.java, DartSnapshotBuilder.java, DartFinancialCollectorTest.java). 나머지 커밋(b019340~f373b965, d4a471d)은 이미 squash 머지된 PR #137을 이력에만 합친 것으로 main 대비 diff에 남는 내용이 없다(`git diff --stat` 3파일만 확인).
- 점검표: B
- 직접 실행한 것:
  - 메인 작업 트리(HEAD d4a471d)에서 `./gradlew build --offline --no-daemon` → 종료 코드 0. 테스트 결과 XML 합계 2005건, 건너뜀 0, 실패 0, 오류 0. ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest·StopRequestPathTest 이번 실행에서 다시 돔(결과 파일 시각 확인).
  - collector 테스트 결과가 이전 실행분(up-to-date)이어서 `./gradlew :autotrade-app-collector:test --offline --no-daemon --rerun-tasks`로 강제 재실행 → 종료 코드 0, 154건 실패 0. DartFinancialCollectorTest 11건 통과(새 2건 포함).
  - 오프라인 빌드가 이 환경에서 끝까지 돌았으므로 CI 결과에 기대지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --offline` 실행, 종료 코드 0, 2005/실패 0. collector 모듈은 `--rerun-tasks`로 다시 돌려 154/실패 0 |
| B2 | 통과 | 변경은 `autotrade-app-collector`의 `com.autotrade.app.collector` 안 2개 운영 클래스와 테스트 1개뿐. 새 의존·새 모듈·build.gradle.kts·ArchUnit 규칙 변경 없음. ArchitectureTest·ModuleOwnershipTest 통과 |
| B3 | 해당 없음 | 모듈 사이 계약(인터페이스, 스키마, 설정 키, REST API, 스냅샷 CSV 헤더·manifest) 변경 없음. 새 `DartSnapshotBuilder.AccountsSource`(DartSnapshotBuilder.java:49-53)와 `build(List<JsonNode>, AccountsSource)`(같은 파일 68-75)는 수집기 앱 모듈 안 공개 메서드이고 다른 모듈에서 쓰지 않는다(grep: 운영 호출처는 CollectorMain.java:246 하나). 기존 `build(List<Input>)`는 그대로 남아 기존 호출이 깨지지 않는다 |
| B4 | 통과 | 시계·난수 새 사용 없음. `Builder.add`(DartSnapshotBuilder.java:88-129)는 기존 루프 몸체를 `in.accounts()`→`accounts`, `continue`→`return`으로만 옮겼고, `result()`(같은 파일 131-153)는 기존 정렬·그룹·통계 계산과 한 줄씩 같다(diff로 대조). 입력 순서는 `filings()`의 정렬(DartFinancialCollector.java:199) 그대로이고 결과는 `SnapshotFinancials.ORDER`로 다시 정렬되므로 출력이 입력 경로와 무관하다. 골든 테스트 포함 빌드 통과 |
| B5 | 통과 | 금액은 기존대로 `BigDecimal`(values 맵, FinancialFiling 필드). 금액 처리 코드 자체는 바뀌지 않았다 |
| B6 | 통과 | DartFinancialCollectorTest#계정_응답을_공시마다_읽어_만들어도_한꺼번에_읽은_결과와_같다: 정정·대기 해소 시나리오(두 번 수집)에서 새 경로와 기존 경로의 `filings()`·`stats()`가 같고, 계정 응답을 공시마다 한 번씩 공시 순서대로 읽는지 확인. #계정_응답을_읽다_실패하면_스냅샷을_만들지_않고_그_오류로_멈춘다: 읽기 IOException이 그대로 올라온다(CollectorMain에서는 기존 IOException 경로로 종료). 기존 테스트 삭제·비활성 없음(테스트 파일 diff는 추가만) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·장애 대응 미변경. 오프라인 수집기 프로세스의 스냅샷 생성 메모리 사용 방식만 바뀜. 보호 경로(`scripts/ci/protected-paths.txt`) 대상 파일 없음 |
| B8 | 통과 | 새 문자열은 주석·Javadoc과 테스트의 "읽기 실패 <접수번호>"뿐. 키·URL·계좌 정보 없음. 계정 응답 읽기는 로컬 캐시 파일만 읽고(DartFinancialCollector.java:204-207) 외부 호출을 하지 않는다 |

## 점검표 밖 발견 (차단 아님)
- N1 메모리 효과는 테스트로 고정되지 않았다. 새 테스트는 결과 동등성과 "공시 순서대로 하나씩 읽는다"만 확인하고, 힙 상한 아래에서 끝나는지는 보지 않는다(CollectorScaleTest는 KRX 경로만 `-Xmx128m`으로 본다). PR이 근거로 든 2026-10-26 전체 수집 OutOfMemoryError와 수정 후 -Xmx2g 통과는 검토자가 재현하지 못했다. 코드상으로는 계정 응답 JsonNode가 공시 하나 처리 뒤 참조가 끊기므로 방향은 맞다[추론]. 다시 OOM이 나면 DART 경로용 규모 프로브를 CollectorScaleTest 방식으로 추가하는 것을 권한다.
- N2 `collector.filings()`는 여전히 공시 목록 JSON 전부를 한꺼번에 올린다(DartFinancialCollector.java:188-200). 공시 목록 항목은 계정 응답보다 훨씬 작아 지금 규모에서는 문제가 아닐 것이나[추론], 같은 패턴이므로 규모가 더 커지면 다음 병목 후보다. 결과 `out`도 전부 들고 있다가 `List.copyOf`로 한 번 더 복사한다(DartSnapshotBuilder.java:151) — 행 수만큼 참조 배열 하나라 영향은 작다.
- N3 `collect()`의 `fetchAccounts`는 이미 받은 응답인지 보려고 `accountsOf(no) != null`로 파일 전체를 파싱한다(DartFinancialCollector.java:215, 이번 PR 범위 밖). 결과를 바로 버리므로 힙 문제는 아니지만 10만 건이면 시간 낭비다. `Files.exists`로 바꾸는 후속 정리를 고려.
- N4 `build(List<Input>)`의 Javadoc이 "시험용"이라고 적지만 공개 메서드로 남아 있다. 운영 코드가 다시 이쪽을 쓰면 같은 OOM이 돌아오므로, 쓰임이 테스트뿐이면 package-private로 좁히는 것도 방법이다.

## 판정
승인
