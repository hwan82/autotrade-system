# architect 검토: 실험 생성 화면(D-074): 조합 실험 등록 파일 쓰기, 시장·스타일 필터, 선정 편집 의미 동일 알림
- 대상: PR #82 / 커밋 de1474a / main 대비 diff(`git diff origin/main...HEAD`, 20개 파일, +1231 −19)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL, 종료 코드 0. `autotrade-architecture-test:test`는 이번 실행에서 실제로 돌았다(UP-TO-DATE 아님). `autotrade-app-lab:test`는 UP-TO-DATE로 표시돼 따로 강제 재실행함.
  - `./gradlew :autotrade-app-lab:test --rerun-tasks` → 통과. 결과 XML(2026-10-14T00:15 생성) 기준 `ExperimentCreationEndToEndTest` 3건, `SelectionEquivalenceTest` 6건 모두 실패·오류·건너뜀 0.
  - diff 전체 읽기(Java 7개, 테스트 2개, JS·CSS 6개, 문서 5개), 관련 기존 코드 대조: `ExperimentId`, `CombinationPlan`, `ExperimentFolders`, `SelectionCriteria`, `SelectionCalculator`, `CombinationMain`, `Page`, `ApiExceptionHandler`, `autotrade-app-lab/build.gradle.kts`(bootRun 작업 폴더).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, 실험실 모듈 테스트 강제 재실행 통과 |
| B2 | 통과 | 빌드 파일 변경 없음(diff에 `*.gradle*` 없음). 새 클래스는 모두 `autotrade-app-lab`의 기존 패키지(`com.autotrade.app.lab.experiment`, `...selection`)에 있고, 쓰는 순수 모듈(`autotrade-sim` `CombinationPlan`, `autotrade-selection` `SelectionCriteria`, `autotrade-marketdata` `SnapshotFiles`)은 기존 의존이다. Spring 의존은 app-lab 안에만 있다. ArchUnit(`autotrade-architecture-test:test`) 이번 빌드에서 실행·통과 |
| B3 | 통과 | REST 계약 변경 4건(`POST /api/experiment-folders`, `POST /api/experiment-folders/preview`, `GET /api/experiment-folders/snapshots`, `POST /api/selections/{id}/versions/{v}/compare`)과 목록 질의 `market`·`style`이 ADR-005 5장 표에 추가됨(docs/adr/ADR-005-ui-dashboard.md:96,99,101,108~110). 결정은 D-074와 결정 로그 README에 기록. 스키마·새 설정 키 없음. 단, 기존 키 `autotrade.lab.experiments-dir`의 용도가 "읽기"에서 "쓰기 포함"으로 넓어졌는데 docs/config/config-keys.md:41 설명은 그대로다(아래 권고 4) |
| B4 | 통과 | 서버 코드에 `now()`·난수 없음(`ExperimentRegistrations`는 입력 값만 씀, 기본 실험 ID·시드는 브라우저 제안값이고 파일에는 사용자가 확인한 값이 들어감, D-074 위험 1). 실행 본체 `CombinationExperiment.runAndWrite`는 바뀌지 않았고 종단 테스트가 결정론 확인(병렬 2 vs 1 "같음")을 단언함(ExperimentCreationEndToEndTest `화면으로_등록한_실험을_실행하고_불러와_조합끼리_비교한다`). 기존 골든 테스트는 `./gradlew build`에서 통과 |
| B5 | 통과 | 시드머니는 `long`(원) 검사 후 문자열로 등록 파일에 씀(ExperimentRegistrations.java `number(..., "시드머니", 1, Long.MAX_VALUE)`). 의미 동일 비교의 기준값·최소 가격은 `BigDecimal.compareTo`/`stripTrailingZeros`(SelectionEquivalence.java `sameNumber`, `plain`). `double` 금액 없음 |
| B6 | 통과 | 새 서버 동작마다 테스트: 등록·미리 보기·CSRF 403·409·스냅샷 목록·시장/스타일 필터·거절 6종(폴더 미생성 단언)·의미 동일 API(ExperimentCreationEndToEndTest 3건), 의미 동일 판정 경계(SelectionEquivalenceTest 6건: 순서·값 표기·가설/메모는 같음, 기준값·기간·연산·순위·최대 종목 수·상품 유형·조건 삭제는 다름, 해석 불가). 삭제·`@Disabled`·가정 건너뛰기 없음(diff 검색). 화면 JS는 자동 테스트가 없음(빌드 도구 없는 정적 프런트, D-026 기존 한계, D-074 검증 절에 명시) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트(실전)·리스크 한도·장애 대응·증권사 연동·보호 테스트 파일을 건드리지 않음(diff 파일 목록에 engine·broker·risk·real·Guard 경로 없음, `real.js` 미변경). 등록 파일의 `blacklist:`는 실험(모의)의 제외 종목 입력으로, 실전 블랙리스트 저장소와 무관 |
| B8 | 통과 | 새 코드·테스트 픽스처에 키·계좌번호·토큰 없음. 화면이 읽는 `/api/settings`는 기존 마스킹 응답에서 `lab.default-seed-capital-krw`만 꺼냄(new-experiment.js `settingValue`). 로그는 스냅샷 폴더 경로 한 줄(LabWebConfiguration `snapshotList`) |

## 점검표 밖 발견

모두 비차단(승인을 막지 않음). 후속 PR이나 다음 손질 때 고치기를 권한다.

1. **실험 ID 모양 불일치** `[사실]` `ExperimentRegistrations.ID`(ExperimentRegistrations.java:44)와 화면 `pattern`(new-experiment.js:35)은 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`이지만, 실제로 받아들이는 모양은 `ExperimentId`의 `[A-Z0-9][A-Z0-9-]{0,47}`이다(autotrade-sim/.../ExperimentId.java:9). 소문자·`.`·`_`·49자 이상 ID는 `CombinationPlan.parse` 재확인에서 400으로 걸리므로 잘못 쓰이지는 않는다(검사 순서상 폴더를 만들기 전). 다만 화면 힌트와 첫 오류 문구("영문·숫자·.·_·- 128자")가 실제 규칙과 달라 사용자를 헷갈리게 한다. 권고: 등록 쪽 패턴과 문구, 화면 `pattern`을 `ExperimentId` 규칙으로 맞춘다(또는 `new ExperimentId(id)`를 직접 호출).
2. **쓰기 실패 시 빈 폴더가 남음** `[사실]` `create`는 `Files.createDirectory(dir)`(97행) 뒤 임시 파일 쓰기·이동(105~106행)이 실패하면 빈 폴더(또는 `experiment.yaml.tmp`만 있는 폴더)를 남긴다. `ExperimentFolders`는 `experiment.yaml`이 없는 폴더를 목록에서 빼므로(ExperimentFolders.java:81) 사용자는 그 ID가 왜 계속 409인지 화면에서 볼 수 없다. 권고: 실패 시 tmp 파일과 빈 폴더를 지우고(방금 만든 빈 폴더라 데이터 삭제가 아님) 예외를 던진다. 테스트로 고정.
3. **`IllegalStateException` 상태 코드** `[사실]` `render`의 재확인 불일치는 `IllegalStateException`(202행)이라 `create`에서는 409 "CONFLICT"로(ExperimentFolderController.java:42), `preview`에서는 처리기가 없어 500으로 나간다(같은 파일 50~57행). 입력 값 문제(따옴표 처리 등)라 400 또는 500 중 하나로 일관되게 두는 편이 맞다 `[추론]`. 지금 입력 검사로는 재현 경로가 보이지 않아 실사용 영향은 낮다.
4. **설정 키 문서** docs/config/config-keys.md:41의 `autotrade.lab.experiments-dir` 설명에 "실험 생성 화면이 새 등록 파일을 이 폴더에 쓴다(D-074), 실험실 프로세스에 쓰기 권한 필요"를 한 줄 더하기를 권한다.
5. **설계 확인(문제 아님)** `[사실]` 의미 동일 판정이 비교하는 항목(refresh, max-symbols, kinds 집합, minPrice, minBarDays, gate 다중집합, score)은 `SelectionCriteria`의 전체 필드와 일치하고(SelectionCriteria.java record 정의), `SelectionCalculator`는 모든 gate를 끝까지 평가해 사유만 모으므로(SelectionCalculator.java:216~228) 순서 무관 판정이 현재 구현과 맞다. D-074 위험 4에 적힌 대로 평가 단락이 생기면 판정을 고쳐야 한다. 실험실이 실험을 돌리지 않는 D-062 경계와 실행 명령 폴더(`bootRun` 작업 폴더 = 저장소 루트, autotrade-app-lab/build.gradle.kts:28~32), 스냅샷 기본 폴더(`CombinationMain` 설정 폴더/snapshots)도 일치한다.

## 판정
승인

## 재검토 2026-10-14
- 대상: PR #82 / 커밋 de1474a + 커밋 전 작업 트리 변경 4개 파일(`git diff`: ExperimentRegistrations.java, ExperimentCreationEndToEndTest.java, new-experiment.js, docs/config/config-keys.md, +18 −7). 커밋된 부분(`git diff origin/main...HEAD`)은 첫 검토 뒤 바뀌지 않았다(HEAD 그대로 de1474a).
- 점검표: B
- 직접 실행한 것:
  - 작업 트리에서 `./gradlew build` → BUILD SUCCESSFUL, 종료 코드 0. `autotrade-architecture-test:test` 실행·통과. `autotrade-app-lab:test`는 UP-TO-DATE(다른 실행 결과 재사용)로 나와 따로 강제 재실행함.
  - `./gradlew :autotrade-app-lab:test --rerun` → BUILD SUCCESSFUL. 결과 XML(00:18 생성) 기준 testcase 140건, 실패·오류·건너뜀 0. `ExperimentCreationEndToEndTest` 3건 통과(소문자 ID 단언 포함).
  - 주의: 이 판정은 **커밋 전 작업 트리** 기준이다. 커밋 내용이 이 diff와 다르면 이 재검토는 적용되지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, 실험실 모듈 테스트 강제 재실행 통과 |
| B2 | 통과 | 변경은 app-lab 클래스 1개, 테스트 1개, JS 1개, 문서 1개. 의존·패키지 변경 없음. ArchUnit 이번 빌드에서 실행·통과 |
| B3 | 통과 | 첫 검토 근거 그대로이고, 권고 4가 반영됨: docs/config/config-keys.md:41에 "실험 생성 화면도 이 폴더에 새 실험 폴더와 등록 파일을 쓴다", 근거 D-074 추가 |
| B4 | 통과 | 변경에 시계·난수 없음. 실행 본체 미변경, 종단 테스트의 결정론 단언 통과 |
| B5 | 통과 | 금액 처리 변경 없음(첫 검토 근거 그대로) |
| B6 | 통과 | 권고 1에 테스트 추가: `시장_스타일이_다르거나_입력이_틀리면_쓰지_않는다`가 `exp-lower` 미리 보기 400 + 메시지 "대문자"를 단언(ExperimentCreationEndToEndTest.java:216). 권고 2(쓰기 실패 시 정리)는 테스트가 없다. 입출력 실패 주입 장치가 없어 단위 테스트가 어렵고 경로가 좁아서 차단 사유로 보지 않는다(아래 남은 것 1). 삭제·건너뜀 없음 |
| B7 | 해당 없음 | 실전 경로 파일 변경 없음(첫 검토와 같음) |
| B8 | 통과 | 새 문구·테스트에 비밀값 없음 |

### 권고 반영 확인
1. 실험 ID 모양: **반영됨.** `ExperimentRegistrations.ID`가 `[A-Z0-9][A-Z0-9-]{0,47}`로 `ExperimentId`와 같다(ExperimentRegistrations.java:44). 오류 문구, 화면 `pattern`, 힌트도 같은 규칙으로 맞춤(new-experiment.js:35, 175행 힌트). 대문자·숫자·하이픈만 허용하므로 `/`, `\`, `..`가 들어갈 수 없다(경로 이탈 차단은 그대로). 기존 테스트의 `../escape` 400도 통과.
2. 쓰기 실패 시 빈 폴더: **반영됨.** `IOException`이면 tmp 파일과 방금 만든 폴더를 `deleteIfExists`로 지운 뒤 원래 오류를 던진다(ExperimentRegistrations.java:108~115). 지우는 대상은 같은 호출에서 `createDirectory`로 새로 만든 폴더뿐이라 기존 데이터 삭제가 아니다. `deleteIfExists(dir)`는 비어 있지 않으면 실패하고, 그 경우 무시하므로 다른 파일을 지우지 않는다.
3. 상태 코드: **반영됨.** 재확인 불일치가 `IllegalArgumentException`이 되어(ExperimentRegistrations.java:209) 등록·미리 보기 모두 400으로 나간다. 컨트롤러의 `IllegalStateException` → 409(ExperimentFolderController.java:42)는 이제 같은 ID 폴더가 있을 때만 해당한다.
4. 설정 키 문서: **반영됨**(B3 근거).

### 남은 것(비차단)
1. 권고 2의 정리 경로를 고정하는 테스트가 없다. 나중에 `ExperimentRegistrations`에 쓰기 함수를 주입할 수 있게 되면 실패 주입 테스트로 고정하기를 권한다.
2. `IOException`이 아닌 런타임 예외(`SecurityException` 등)에는 정리가 돌지 않는다 `[추론]`. 로컬 단일 사용자 환경에서는 실사용 영향이 낮다.

## 판정 (재검토)
승인
