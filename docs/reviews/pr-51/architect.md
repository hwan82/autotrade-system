# architect 검토: 실험실이 조합 실험 결과 묶음을 불러와 교차표·선정 결과에 실제 값으로 보이기 (D-062)
- 대상: PR #51(호출자 지정 경로, 번호는 확인 못 함) / 브랜치 `claude/project-thread-2ad6vg` 커밋 23efd04ac9242bcd5ea7e803d107c72e4293b48a / `git diff origin/main...HEAD`(origin/main = 120b827, 커밋 1개, 21파일 +1401 −26)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL (122 tasks, 18 executed, 나머지 캐시)
  - 캐시 재사용분을 피하려고 `./gradlew :autotrade-sim:test :autotrade-app-lab:test :autotrade-architecture-test:test --rerun-tasks --no-daemon` → 성공(EXIT 0). 테스트 결과 XML 115개 묶음 전부 failures=0, errors=0, skipped=0
  - 새·바뀐 테스트: `ExperimentFoldersTest` 6, `ExperimentFolderControllerTest` 2, `ResultBundleReaderTest` 6, `CombinationExperimentTest` 5, 모두 통과
  - diff 파일 목록에서 risk·broker·engine·guard·gateway·watchdog·real 경로 검색: 해당 파일 없음
  - PR 본문·커밋 메시지·D-062 문서 설명은 판정 근거로 쓰지 않았다. 아래 근거는 코드와 직접 실행 결과다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 전체 빌드 성공, 변경 모듈 테스트 강제 재실행 성공, 실패·건너뜀 0 |
| B2 | 통과 | 쓰기 `ResultBundle`은 순수 모듈 `autotrade-sim`의 `com.autotrade.sim.run`(JDK `java.io`만 사용, 자체 JSON 쓰기 ResultBundle.java:270 `Json`). 읽기 `ResultBundleReader`(Jackson)·`ExperimentFolders`·`ExperimentFolderController`는 `autotrade-app-lab`의 `com.autotrade.app.lab.experiment`. 빌드 스크립트 의존 변경 없음, `verifyPureDependencies`·`ArchitectureTest`·`ModuleOwnershipTest` 통과. ArchUnit 규칙 변경 없음 |
| B3 | 실패 | 새 REST 경로 `GET /api/experiment-folders`, `POST /api/experiment-folders/{id}/load`(ExperimentFolderController.java:24·30)가 ADR-005 5장 REST 계약 표(docs/adr/ADR-005-ui-dashboard.md:86-108)에 행으로 없다. 같은 장 115행 산문과 D-062에 경로 이름만 나온다. 응답 모양(`Folder`의 `id·question·period·combinations·state·lastError`, `state` 값 4개), 오류 코드(404·409·400, CSRF 403)가 계약 문서 어디에도 없다. 또 5장 공통 규칙 "목록은 모두 `{items, page, size, total}` 페이징"(84행)과 달리 목록이 맨 배열이다. 설정 키 `autotrade.lab.experiments-dir`는 config-keys.md 실험실 속성 표에 기본값·설명·근거와 함께 들어갔다(통과 부분). 결과 묶음 파일 형식은 ResultBundle.java 클래스 주석과 D-062에 스키마 번호와 함께 문서화됨(통과 부분) → 아래 F1 |
| B4 | 통과 | 새 코드에 `now()`·시스템 시계·난수 없음. 묶음 쓰기 순서 고정: Run은 명세 조합 순(ResultBundle.write에서 순서 대조), 선정 결과는 내용 해시 `TreeMap`, 비용 항목 `SortedMap`, 프로필 파라미터 `TreeMap`. `GZIPOutputStream`은 헤더 MTIME을 0으로 써 바이트가 같다. `CombinationExperimentTest#같은_입력이면_결과_파일이_바이트까지_같다`가 두 실행의 묶음 바이트 일치와 `.tmp` 미잔존을 확인(통과). `ResultBundleReaderTest#같은_입력이면_같은_글이다` 통과. 기존 골든·결정론 테스트 변경 없이 통과 |
| B5 | 통과 | 금액은 `Money`(BigDecimal) ↔ `{"amount": toPlainString, "currency"}` 문자열, 소수 지표도 `toPlainString` 문자열, long은 문자열(ResultBundle.java `money`·`dec`). 읽기는 `new BigDecimal(text)`(ResultBundleReader.java `money`·`dec`). double 없음. 왕복 equals를 `ResultBundleReaderTest#조합_실험을_쓰고_읽으면_명세_결과_선정_결과가_그대로다`가 확인(BigDecimal scale까지 equals) |
| B6 | 통과 | 새 동작마다 테스트: 묶음 쓰기·결정론·선정 결과 포함 범위(`CombinationExperimentTest` 2건 추가·확장), 왕복·선정 없는 실험·실패 Run·잘린 파일·빠진 줄·프로필 본문 변조·선정 결과 변조·스키마 불일치(`ResultBundleReaderTest`), 목록 상태·detail.txt 불일치·selection-days.tsv 불일치·폴더 이름 불일치·묶음 없음·경로 이탈 ID(`ExperimentFoldersTest`), CSRF 403·409·404·400·불러온 뒤 교차표와 평가 행 API(`ExperimentFolderControllerTest`). 삭제·`@Disabled` 없음. `SampleReports` 변경은 요약의 선정·조합 해시와 시드 방식을 실행기와 같게 맞춘 것으로 기존 테스트를 약화하지 않음(전체 통과) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트 코드·리스크 한도·장애 대응·보호 테스트 변경 없음(diff 21파일 확인). 실험실 프로세스 안 읽기 전용 화면 기능이며 실전 엔진 프로세스와 무관 |
| B8 | 통과 | diff에 비밀값 없음. 로그는 실험 폴더 경로만(LabWebConfiguration `log.info("실험 폴더: {}")`). 예외·`lastError` 문구는 실험 ID·파일 이름·필드 이름만 담는다(ExperimentFolders.load, ResultBundleReader) |

## 점검표 밖 발견

### F1 (수정 필요, B3): 새 REST 경로가 ADR-005 5장 계약 표에 없고 목록 모양이 공통 규칙과 다르다
- [사실] ADR-005 5장은 실험실 REST 계약의 단일 표다(84-108행). 새 두 경로는 표에 없고 115행 "실험 결과 출처" 산문에만 이름이 있다. 응답 필드, `state` 값(`LOADED`·`READY`·`NO_BUNDLE`·`NOT_RUN`), `lastError`의 의미(이 프로세스에서 마지막 불러오기 실패 사유), 오류 코드(없음 404, 이미 불러옴 409, 형식·대조 실패 400)가 계약 문서에 없다.
- [사실] 84행 공통 규칙은 목록을 모두 `{items, page, size, total}`로 준다고 정한다. `GET /api/experiment-folders`는 `List<Folder>` 맨 배열이다(ExperimentFolderController.java:24-27).
- 요구 사항: ADR-005 5장 표에 두 행을 넣는다(응답 필드·상태 값·오류 코드, `POST`는 CSRF 필요·실험을 다시 돌리지 않음). 목록은 페이징으로 맞추거나, 페이징하지 않는 이유(폴더 수가 적은 로컬 목록)를 84행 공통 규칙의 예외로 적는다. 둘 중 무엇을 골라도 된다.

### F2 (권고): 실험 폴더 목록 실패가 실험 목록 화면 전체를 막는다
- [사실] experiments.js:10이 `Promise.all`로 실험 목록과 폴더 목록을 함께 기다린다. 폴더 목록이 실패하면(예: `experiments-dir`가 읽기 권한 없는 폴더, ExperimentFolders.list의 `UncheckedIOException`) 이미 불러온 실험 목록도 보이지 않는다.
- 권고: 폴더 목록 요청 실패를 따로 잡아 "실험 폴더를 읽지 못함" 안내만 보이고 실험 목록은 그대로 그린다.

### F3 (권고): 불러오기 동안 목록 요청이 막힌다
- [사실] `list()`와 `load()`가 같은 모니터로 `synchronized`다(ExperimentFolders.java:67·111). `load()`는 gzip 묶음 전체를 읽고 해시를 다시 계산하며 `ReportWriter`로 다시 그리는 동안 잠금을 쥔다. 다년·전 종목 실험이면 수 초 이상 걸릴 수 있다 `[추론]`(실측 없음). 그동안 실험 화면을 다시 열면 폴더 목록 요청이 잠금에서 기다리고, F2 때문에 화면 전체가 그만큼 늦게 뜬다.
- 권고: `lastErrors`를 `ConcurrentHashMap`으로 바꾸고 `list()`의 잠금을 빼거나, 불러오는 중인 ID 집합만 잠금으로 보호한다. 실전 엔진과 무관한 실험실 안 응답성 문제라 머지 차단 사유로 두지 않는다.

### F4 (권고): 조합 실험 등록 파일이 깨지면 목록에서 말없이 사라진다
- [사실] ExperimentFolders.java:82가 `CombinationPlan.parse` 실패를 모두 건너뛴다. 합성 시세 실험(`EXP-20261008-STR01`)처럼 다른 모양의 `experiment.yaml`을 빼려는 의도지만, 조합 실험 등록 파일의 오타도 같은 길로 사라져 사용자는 이유를 알 수 없다.
- 권고: `combinations:` 키가 있는데 파싱이 실패한 경우는 `INVALID` 상태와 사유로 보이게 한다.

### F5 (권고, 문서): "detail.txt 바이트 대조"가 확인하는 범위를 D-062에 정확히 적는다
- [사실] D-062 대안표 B행은 `detail.txt`에 체결 시각, 거절 상세, 선정 종목 목록, 계좌 ID가 없다고 적는다. 따라서 대조 ②는 그 필드들을 확인하지 못한다. 묶음과 `detail.txt`를 같은 실행이 쓰고(CombinationExperiment.runAndWrite) 코드 커밋 줄까지 대조하므로 다른 실행의 묶음은 막히지만, 묶음만 손으로 고친 경우 이 필드들의 변조는 걸리지 않는다.
- [추론] 묶음은 로컬 산출물이고 커밋하지 않으므로 위험은 낮다. D-062 "위험과 한계"에 "대조는 detail.txt에 나오는 값과 해시가 있는 값만 확인한다"를 한 줄 적기를 권한다.

### 확인한 사항 (문제 없음)
- 경로 이탈: 실험 ID 정규식 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`이 `/`·`\`를 막고, `resolve(id).normalize()`의 부모가 루트와 같은지 다시 본다(ExperimentFolders.load). `../etc`는 400(`ExperimentFoldersTest#결과_묶음이_없거나_조합_실험이_아닌_폴더와_잘못된_ID`).
- CSRF: `POST`는 기존 `LocalOnlyRequestFilter`를 거쳐 토큰 없이는 403(`ExperimentFolderControllerTest#목록에서_불러오면_실험_화면_API가_실제_결과를_준다`). 화면은 `api.js`의 `post`가 토큰을 붙인다.
- 원자적 교체: 묶음은 `.tmp`에 다 쓴 뒤 `Files.move(..., REPLACE_EXISTING)`(CombinationExperiment.java:248). 같은 폴더 안 이름 바꾸기라 POSIX에서는 원자적이다 `[추론]`. 쓰다 실패해 이전 묶음이 남아도 새 `detail.txt`와 대조가 맞지 않아 불러오지 않는다.
- 왕복 무결성: 읽기는 Run별 줄 수, Run·선정 결과 수, 프로필·선정 해시 재계산, 선정 결과 내용 해시 재계산을 대조한다(ResultBundleReader). 조합 해시는 `detail.txt`에 찍혀(ReportWriter.java:40) 대조 ②로 확인된다.
- 메모리: 불러온 평가 행이 실험실 힙에 남는 위험은 D-062 "위험과 한계"에 있고, 실험실은 별도 프로세스라 실전 엔진에 영향이 없다(지침 4장 6번). 실험실 안에서 실험을 다시 돌리지 않으므로 Q-049 대상이 늘지 않는다.
- 실전 경로 영향 없음. 시세 게이트웨이·실전 엔진 코드 변경 없음.

## 판정
수정 필요(F1: B3 — ADR-005 5장 REST 계약 표에 새 두 경로의 응답·상태 값·오류 코드를 넣고, 목록의 페이징 규칙 예외를 정리)
