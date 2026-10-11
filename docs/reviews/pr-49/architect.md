# architect 검토: 선정 프로필 편집, 조합 교차표 리더보드, 선정 결과 조회(D-060, 배치표 4번)
- 대상: PR #49 / 커밋 3ddfedb409fea5e1b20d0f0f557421b31aca3ce8 / `git diff origin/main...HEAD` 전체(30개 파일, +2446 −236)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL, 종료 코드 0(122 tasks, verifyPureDependencies·verifyProcessIsolation 포함). 테스트 태스크가 캐시로 UP-TO-DATE여서 아래를 따로 다시 돌렸다.
  - `./gradlew :autotrade-app-lab:test :autotrade-architecture-test:test --rerun-tasks` → 종료 코드 0. 결과 XML 집계: autotrade-app-lab 116개 실패 0·오류 0·건너뜀 0, autotrade-architecture-test 30개 실패 0·오류 0·건너뜀 0.
  - diff 스캔: `now()`·`Instant.now`·`Math.random`·`double`·비밀값 문자열, UI의 `innerHTML`·`insertAdjacentHTML`·`eval`.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, app-lab·architecture-test 강제 재실행 146개 전부 통과 |
| B2 | 통과 | 바뀐 Java는 `autotrade-app-lab`(Spring 모듈)뿐. `autotrade-app-lab/build.gradle.kts:14`에 `:autotrade-selection`(순수 모듈) 의존 추가는 app → 순수 방향이라 허용. 순수 모듈·engine·gateway·watchdog·broker 변경 없음. 새 운영 클래스는 `com.autotrade.app.lab..` 안(`ModuleOwnershipTest.java:121` 소유 규칙), ArchUnit 30개 통과 |
| B3 | 실패 | 새 REST 경로 7개와 `GET /api/experiments/{id}` 새 필드, 설정 키 `autotrade.lab.selections-dir`(docs/config/config-keys.md:40), `ExperimentCatalog.register(spec, report, selectionResults)`는 ADR-005 5장·D-060에 적혔다. 그러나 기존 응답 두 개에 더한 필드가 계약 표에 없다: `GET /api/experiments/{id}/equity`의 `series[].selectionId`·`selectionVersion`(ExperimentViews.java `EquitySeries`), `GET /api/experiments/{id}/runs/{runId}/summary`의 `selectionId`·`selectionVersion`·`selectionHash`·`combinationHash`(ExperimentViews.java `RunSummary`). docs/adr/ADR-005-ui-dashboard.md:91·92 행은 그대로다(D-060 4번 산문에만 화면 표시로 언급) |
| B4 | 통과 | `autotrade-sim`·`autotrade-strategy`·`autotrade-selection` 변경 없음, 골든 테스트 포함 빌드 통과. 새 코드의 시각은 주입 `Clock`만 씀(`LabWebConfiguration.selectionProfileStore`, `VersionedProfileFiles.create/createVersion`의 `clock.instant()`). 교차표 행·열 순서는 Run 목록 순서(LinkedHashMap), 사유 집계는 TreeMap/TreeSet, 평가 행은 결과의 종목 코드순이라 같은 입력이면 같은 응답 |
| B5 | 통과 | 교차표 차이는 `SelectionViews.CrossTable.diff`가 `BigDecimal.subtract` 후 `toPlainString`(서버 계산). UI `format.js delta()`의 `Number`는 표시 형식뿐이고 기존 `pct()`와 같은 방식. 금액 계산 코드 추가 없음 |
| B6 | 통과 | 새 테스트: `CombinationViewsTest`(6개, 교차표 차이 손 계산 0.033−0.022=0.011 등, 실패 Run null, 모르는 지표·대조 열 400, 해시 형식 400·없는 결과 404, 조합 밖 선정 결과 등록 거절), `SelectionProfileStoreTest`(4개, 순수 `SelectionProfileYaml`과 같은 해시, 옛 버전 불변, 오타 키·갱신 주기·가설 누락 거절과 파일 미생성, 저장소 `selections/` 파일 해시 일치), `SelectionProfileControllerTest`(4개, CSRF 없는 저장 거절 포함). 기존 테스트 파일 수정·삭제 없음(`git diff --stat`에 기존 테스트는 `DemoLabApplication.java`만, 미리보기 실행기 시드 추가). `StrategyProfileStore` 리팩터는 기존 `StrategyProfileStoreTest`·`ProfileControllerTest`가 그대로 통과 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크·장애 대응·브로커·engine/gateway/watchdog 파일 변경 없음(diff 파일 목록 30개 전부 app-lab, autotrade-ui, docs). 보호 테스트 변경 없음 |
| B8 | 통과 | diff에 키·계좌번호·토큰 문자열 없음(스캔 결과 CSRF 테스트 헬퍼 `token`만). 새 로그는 폴더 경로와 ID·버전·해시(`VersionedProfileFiles.write`, `LabWebConfiguration`) |

## 점검표 밖 발견
1. (수정 요청, 문서) D-060 1번과 대안 표의 "전략 프로필 저장소는 동작·메시지 그대로"는 사실과 다르다. 공통화로 오류 메시지가 바뀌었다: "프로필이 없습니다" → "전략 프로필이 없습니다", "이미 있는 프로필 ID입니다" → "이미 있는 전략 프로필 ID입니다", "프로필 ID 형식이 아닙니다(...)" → "전략 프로필 ID 형식이 아닙니다(...)"(`VersionedProfileFiles.java` `create`·`createVersion`·`dir`의 `codec.label()` 접두). 동작은 같고 메시지를 단언하는 기존 테스트도 없어 해는 없지만, 결정 문서는 "동작 그대로, 메시지에 종류 이름이 붙음"으로 고친다.
2. (권고) `VersionedProfileFiles.now()`는 어디서도 부르지 않는 죽은 코드다. 지운다.
3. (권고) 선정 프로필 화면의 "조합할 수 있는 전략 프로필"은 `/api/profiles?page=0&size=200` 한 쪽만 읽어(`selections.js` `selectionPage`) 전략 프로필이 200개를 넘으면 조용히 빠진다. 지금 규모에서는 문제없고, 실험 생성 화면 작업 때 시장·스타일 필터 질의로 바꾸면 된다.
4. (권고) 조건 행 편집기는 `filter.kinds`를 카탈로그 enum 순서로 다시 쓰고 자격 조건을 `gate.01`부터 다시 번호를 매긴다(D-060 3번에 적힘). 손으로 쓴 파일(예: kinds 순서가 다르거나 번호에 빈칸)을 메모만 바꿔 저장하면 의미는 같고 해시만 다른 새 버전이 생기고 "바뀐 파라미터가 없습니다" 검사를 지나간다. 저장소 예시 두 파일(`selections/kr-fin-quality-growth/v1.yaml`, `selections/kr-vol-surge-quiet/v1.yaml`)은 `filter.kinds: "STOCK"` 하나에 gate 번호가 01부터 이어져 해당하지 않는다(grep 확인). 화면에서 원본과 `collect()` 결과가 의미상 같으면 알려 주는 정도로 후속 처리 권고.
5. (확인) `ExperimentCatalog` 구현은 `InMemoryExperimentCatalog` 하나뿐이고 2인자 `register`는 default로 유지되어 기존 호출부가 그대로 컴파일된다. 조합 밖 선정 결과 거절은 선정 해시 대조(`InMemoryExperimentCatalog.register`)로 하며 테스트가 있다.
6. (확인) D-060 위험과 한계에 적힌 대로 실제 실행 명령은 아직 카탈로그에 `selectionResults`를 넘기지 않아 교차표·선정 결과 화면은 샘플로만 검증됐다. 이 PR의 범위 밖이며 후속 과제로 남아 있다.

## 판정
수정 필요(B3: `GET /api/experiments/{id}/equity`와 `GET /api/experiments/{id}/runs/{runId}/summary`에 더한 선정·조합 필드를 ADR-005 5장 REST 표에 적을 것. 점검표 밖 1번: D-060의 "메시지 그대로" 문구 정정)

## 재검토 2026-10-11
- 대상: PR #49 / 커밋 4808c0f7e6ec1e5b2d399c009cee7c38dab166c9 / `git diff origin/main...HEAD` 전체(31개 파일, +2473 −238). 앞 검토 뒤 바뀐 것은 커밋 4808c0f 하나(4개 파일: `VersionedProfileFiles.java`, `ADR-005-ui-dashboard.md`, `D-060-selection-ui.md`, 이 검토 원문 추가)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → 종료 코드 0.
  - `./gradlew :autotrade-app-lab:test :autotrade-architecture-test:test --rerun-tasks` → 종료 코드 0. 결과 XML 집계: autotrade-app-lab 116개 실패 0·오류 0·건너뜀 0, autotrade-architecture-test 30개 실패 0·오류 0·건너뜀 0.
  - `git show 4808c0f` 전체 확인, 커밋된 `docs/reviews/pr-49/architect.md`가 앞 검토 원문과 같은지 대조(같음, 판정 줄 포함).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 종료 코드 0, 강제 재실행 146개 통과 |
| B2 | 통과 | 4808c0f의 코드 변경은 `VersionedProfileFiles.now()` 삭제뿐(app-lab 내부, 호출부 없음 grep 확인, `java.time.Instant` import는 `NextVersion`·`createdAtInstant`에서 계속 씀). 모듈 의존·ArchUnit 변화 없음, ArchUnit 30개 통과 |
| B3 | 통과 | docs/adr/ADR-005-ui-dashboard.md:91에 equity 계열 `selectionId`·`selectionVersion`(선정 없음이면 null), :92에 summary `selectionId`·`selectionVersion`·`selectionHash`("none")·`combinationHash`가 적혔고, 코드(`ExperimentViews.EquitySeries`, `ExperimentViews.RunSummary`)의 필드 이름·null 규칙과 일치. 나머지 계약 문서는 앞 검토 그대로 |
| B4 | 통과 | 시뮬레이션·전략·선정 순수 모듈 변경 없음, 골든 테스트 포함 빌드 통과. 앞 검토 근거 그대로 |
| B5 | 통과 | 앞 검토 근거 그대로(교차표 차이 서버 `BigDecimal`). 4808c0f에 금액 관련 변경 없음 |
| B6 | 통과 | 4808c0f에 테스트 변경 없음. 기존·새 테스트 모두 통과, 건너뜀 0 |
| B7 | 해당 없음 | 실전 경로·보호 테스트 파일 변경 없음(31개 파일 전부 app-lab, autotrade-ui, docs) |
| B8 | 통과 | 4808c0f에 비밀값 없음. 앞 검토 근거 그대로 |

## 점검표 밖 발견(재검토)
1. 앞 검토 점검표 밖 1번(D-060 "메시지 그대로"): 해결. D-060-selection-ui.md:11이 "동작은 그대로이고 오류 메시지 앞에 종류 이름이 붙는다"로 사실대로 고쳐졌다.
2. 앞 검토 2번(죽은 `now()`): 해결.
3. 앞 검토 3·4번(전략 프로필 200개 한 쪽 조회, 편집기의 재번호·순서 재작성으로 의미 같은 새 해시): 권고로 남긴 그대로 미반영. 머지를 막지 않는 후속 과제다.
4. 검토 방식 메모: 조정자는 재검토를 `architect-r2.md` 새 파일로 쓰라고 했으나, docs/reviews/README.md "재검토는 같은 파일 아래에 `## 재검토 <날짜>`로 덧붙인다"를 따라 이 파일에 덧붙였다. 앞 내용은 고치지 않았다.

## 판정
승인
