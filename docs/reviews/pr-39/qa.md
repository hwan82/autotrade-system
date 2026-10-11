# QA 검토: 수집 일괄 명령(Q-037, D-051, ADR-008): KRX 일봉·상장주식수, OpenDART 재무 증분 수집
- 대상: PR #39 / 커밋 2ff4310ce11763e2496d63342dc306313faf9968 / main(cf3759a) 대비 전체 diff 65개 파일(+4435 −52). main이 PR 브랜치의 merge-base와 같음을 확인(`git merge-base HEAD origin/main` = cf3759a), GitHub mergeable_state `clean`
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 프로젝트 지침 8장)
- 검토자: 독립 QA 프로젝트 "자동매매 시스템 QA", 2026-10-10
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (2ff4310): BUILD SUCCESSFUL(20회째). 1~19회는 Maven Central 429로 의존성 받기 실패해 20초 간격 재시도(429는 빌드 실패로 보지 않음). 테스트 결과 XML 합계 606 tests, 0 failures, 0 errors, 0 skipped. 그중 `autotrade-app-collector` 53 tests
  - PR 체크 런(GitHub, 2ff4310): `Gradle build` success, `Secret scan and collector boundary` success, `DB schema (PostgreSQL 16)` success
  - **품질 리포트 단계 메모리 실측**: 빌드된 배포본(`autotrade-collector-0.1.0-SNAPSHOT.tar`)의 lib 클래스패스로 `SnapshotDailyBarSource.load(dir).qualityReport()`(수집기 `CollectorMain.java:138`과 같은 호출)를 `-Xmx2g`(배포 스크립트 `DEFAULT_JVM_OPTS='"-Xmx2g"'`와 같음)로 실행. 합성 스냅샷은 2,700종목 × N거래일, 행 형식은 `daily_bars.csv` 헤더 그대로, manifest 해시·행 수 일치
    - 675,000행(250일): 성공, 사용 힙 692MB
    - 1,350,000행(500일): 성공, 1,257MB
    - 2,025,000행(750일): 성공, 1,819MB
    - 2,970,000행(1,100일): `java.lang.OutOfMemoryError: Java heap space`, 34초 뒤 종료 코드 1
    - 11,070,000행(4,100일, 2010-01-04부터 전체 규모): `OutOfMemoryError`(`Csv.read`), 17초 뒤 종료 코드 1
  - KRX Open API 공식 약관 원문 확인(`https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO002.jsp`, 2026-10-10 조회): 제8조 ④ "하나의 키당 1일(매일 0시~24시) 10,000회 이하의 요청으로 제한하며, 이를 초과할 경우 서비스가 중지될 수 있다", 제6조 ② 비상업 목적, 시행일 2025-12-26. 요금·이용료·과금 조항 없음. 이 컨테이너에서 `openapi.krx.co.kr`·`data-dbg.krx.co.kr` 직접 접속(curl)은 프록시 403으로 막혀 실호출은 못 함
  - `git diff --numstat` 보호 테스트: `ArchitectureTest.java` +28 −0, `ModuleOwnershipTest.java` +2 −1(표 끝 항목에 콤마 추가와 새 행), Guard·GuardedOrderGateway·장애 테스트 변경 0건. 추가 줄에 `@Disabled`·`@Ignore`·`assumeTrue` 없음(검토 원문 인용 줄 제외)
  - 열린 PR과 번호 충돌 검색: 원격 브랜치 전체의 `docs/decisions/README.md` 추가 행 D-049(#37), D-051·Q-041(이 PR), D-052(#40), D-053·Q-042, D-054 → 겹침 없음. #37 브랜치와 겹치는 파일은 `docs/adr/README.md`, `docs/decisions/README.md` 두 개뿐(한 줄씩), #37 브랜치에는 `new SnapshotManifest(` 호출이 없어 이 PR의 record 칸 추가(`Coverage`)와 컴파일 충돌 없음
  - 개발 쪽 검토 원문(architect 2차 재검토 "승인", risk-ops 2차 재검토 "승인")과 PR 본문은 사실로 받지 않고 같은 항목을 처음부터 다시 확인했다

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 관련 코드·설정·테스트 변경 0건(`git diff --name-only`에 `autotrade-risk`, `autotrade-app-engine/src` 없음). 수집기는 `com.autotrade.domain.order..`·`com.autotrade.broker..`를 참조하지 못하고(`ArchitectureTest.java` 새 규칙 `COLLECTOR_READS_ONLY_ITS_SECRETS`, 위반 고정 예제 `LoadsProcessConfig`로 실제로 잡히는지 검증), `processIsolation.forbiddenProjects`에 risk·control·broker·engine이 들어 있음(`autotrade-app-collector/build.gradle.kts:16-20`) |
| A2 | 통과 | 기존 ArchUnit 규칙 삭제·완화 없음(+28 −0). 새 프로세스 패키지가 `PLAIN_PROCESSES`·`PROCESSES_ARE_INDEPENDENT` 대상에 자동 포함. 수집기에 주문 출구 없음 |
| A3 | 해당 없음 | 주문·증권사 라우팅 코드 변경 없음. 수집기가 부르는 곳은 `KrxOpenApiClient`(`data-dbg.krx.co.kr`)와 `OpenDartClient`(`opendart.fss.or.kr`)뿐 |
| A4 | 해당 없음 | 주문 재전송 경로 없음. 수집기 HTTP 재시도(`ApiCaller.java:45-69`)는 조회 GET이며 주문과 무관 |
| A5 | 해당 없음 | 주문 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 관련 변경 없음. 수집기는 주문 포트를 참조할 수 없음(A1 근거) |
| A7 | 통과 | 계좌 큐·토큰 버킷 코드 변경 없음. 수집기의 하루 호출 상한(`CallBudget`)은 KRX·OpenDART 소스별이며 증권사 계좌 레이트리밋과 별개 |
| A8 | 통과 | 별도 JVM 단발 명령, 실전 엔진·게이트웨이·감시의 `forbiddenProjects`에 `:autotrade-app-collector` 추가(engine·gateway·watchdog `build.gradle.kts` 각 1줄). 자원 보호: 장 시간 보호 구간 거부(`RunGuard.java:276-302`, 시작 시·매 API 호출 전·스냅샷 하루 읽을 때마다), 힙 `-Xmx2g`(배포 스크립트·`run` 태스크), 디스크 여유 20GB 미만 거부(`RunGuard.java:305-320`), 각 거부 동작 실패 경우 테스트(`RunGuardTest` 6건, `CollectorMainTest` 종료 코드 4 경로 5건) 통과. 단 ① 디스크 보호의 한 축인 스냅샷 정리가 기본 배치에서 동작하지 않는 결함은 점검표 밖 F1(디스크 바닥 20GB 거부는 동작하므로 실전 엔진 디스크 보호 자체는 유지), ② 품질 리포트 단계 비보호와 OS 우선순위 미강제는 R2 |
| A9 | 해당 없음 | 시세 경로·보호 시세·서버 측 손절 코드 변경 없음. 수집기는 실시간 시세를 다루지 않음 |
| A10 | 통과 | 테스트 픽스처는 자리표시자(`KRXPLACEHOLDERAAAA`, `DARTPLACEHOLDERBBBB` `CollectorMainTest.java:36-37`, `KRX-PLACEHOLDER` `CollectorSecretsTest.java:27`). 문서 예시는 `<KRX_OPEN_API_AUTH_KEY>`·`<OPENDART_CRTFC_KEY>`. `.gitignore`에 `**/collector-secrets.json`. 키는 `Secret`으로만 보관, 오류 메시지는 `Redaction`(`crtfc_key=` 패턴 + 알려진 값 치환, `Redaction.java:255-264`), JDK 예외 메시지 미전달(`ApiCaller.java:54-57`), 리다이렉트 추종 끔(`JdkHttpGetter.java:282`), https만 허용(`CollectorMain.java:315-320`). 실행 뒤 출력·캐시·스냅샷·리포트에 키 없음 테스트(`CollectorMainTest`) 통과. `.gitleaks.toml`은 기본 규칙 유지 + `^abcdef1234567890$` 정확히 일치 한 값만 예외라 스캔을 넓게 약화하지 않음. CI gitleaks success. 단 CI `crtfc_key` 패턴의 검사 범위 한계는 R4 |
| A11 | 통과 | 보호 테스트는 추가만(위 numstat), 지운 테스트·비활성화 없음. 새 동작마다 실패 경우 테스트: 모르는 키 거부·`secrets.json` 미로딩(`CollectorSecretsTest`), 상한·020·동시 실행 합산(`CallBudgetTest`), 장 시간·서머타임·휴장일·캘린더 범위 밖(`RunGuardTest`), 디스크 거부, 쓰는 중 보호 구간 진입 시 임시 폴더 제거, https만, 응답 모양 이상 시 멈춤, 접수일 없는 재무 행 거부. 단 F1을 잡는 테스트(실제 규모 또는 힙 상한 아래에서의 기본 배치)는 없다 |
| A12 | 통과 | 장애표(`broker-and-fault-design.md` 6장)·장애 테스트 8종 문구와 코드 변경 없음. 새 실행 단위가 5.2 정지 순서 밖이라는 전제 변화는 숨기지 않고 Q-041(사용자 결정, risk-ops 추천: 넣음)로 등록됐고(`docs/decisions/README.md` Q-041 행), 그때까지 수집기가 시간·힙·디스크로 스스로 물러나는 보완이 코드와 테스트로 있다(A8). 장애표 #10 "5.2 정지 순서 적용"의 대상(실험실·녹화·UI)은 바뀌지 않음 |
| A13 | 통과 | 보호 구간은 시장별 `ExchangeCalendar`(KRX `Asia/Seoul`, US 현지 시간대, 서머타임)로 계산(`RunGuard.java:281-300`), 하드코딩 시각 없음. `미국_정규장은_서머타임을_따라_막는다` 통과. 금액 경로 없음(수집 값은 `BigDecimal`) |
| A14 | 통과 | D-051 추가(추천안 채택(자율) 표시), Q-037 닫음, Q-041 등록, 기획서 v14.10 변경 이력·ARC-06·MD-11·배치표 1번·15장 갱신이 코드와 일치(명령 두 개, `collector-secrets.json`, 상한 9,000·10,000, 020 중단, 자원 보호 ⑤). 번호 충돌 없음(직접 실행 참고). 단 ADR-008·runbook의 "지운 스냅샷은 같은 ID로 복원된다" 주장이 재무 스냅샷에서는 틀린 점은 점검표 밖 F2(결정 로그·기획서 문구가 아니라 ADR·runbook 문구라 A14 판정에는 넣지 않음) |

## 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행 결과(BUILD SUCCESSFUL, 606 tests 0 failures). CI `Gradle build` success(2ff4310) |
| B6 | 통과 | 수집기 새 동작에 53건 테스트, 지운 테스트·skip 없음. `CollectorMainTest`의 시계 값 변경(03:00Z → 10:00Z)은 새 보호 구간을 피하려는 것이고 검증 내용은 줄지 않음(diff 확인). 단 F1(실제 규모에서 품질 리포트 OOM → 정리 미동작)은 테스트가 작은 픽스처만 써서 드러나지 않음 |

## 과금 검사 (QA 지침 8장)

| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 호출 경로 식별 | 통과 | 외부 호출 지점은 두 곳뿐: ① KRX Open API `GET https://data-dbg.krx.co.kr/svc/apis/sto/{stk,ksq,knx}_bydd_trd?basDd=` (`KrxOpenApiClient.java:42-45`), 경로 `CollectorMain.main` → `run` → `krxDaily` → `KrxDailyCollector.collect`(날짜·시장마다 캐시에 없을 때 1회) → `fetchRaw` → `ApiCaller.get` → `JdkHttpGetter.get`. ② OpenDART `list.json`·`fnlttSinglAcnt.json` (`OpenDartClient.java:135-165`), 경로 `run` → `dartFinancials` → `DartFinancialCollector.collect` → `scan`(3개월 창·쪽마다) / `fetchAccounts`(계정 응답 없는 공시마다) → `ApiCaller.get`. 둘 다 사용자가 명령을 직접(또는 예약 작업으로) 실행할 때만 돈다. 상주 프로세스 4개는 이 모듈을 클래스패스에 넣지 못함(`forbiddenProjects`). 과금 여부: OpenDART는 약관 제11조 ① "원칙적으로 무료"(메모리·D-050 근거, 기존 확인). KRX Open API는 공식 약관 원문에 요금·이용료 조항이 없고 초과 시 효과가 "서비스 중지"(제8조 ④)이며 과금이 아님을 이번에 원문으로 확인. 유료 데이터는 별도 "데이터 구입"·마켓데이터 상품 경로이고 이 코드는 그 주소를 부르지 않음. 증권사 API 호출 0건(ArchUnit·빌드 격리) |
| 8.2 필요성과 대체 수단 | 통과 | 유료 경로 없음. 무료 공식 API만 쓰고, 응답 캐시로 같은 날짜·공시를 다시 부르지 않음(`KrxDailyCollector.java:305-308`, `DartFinancialCollector.java:113-116, 215-217`), OpenDART 훑은 구간을 기억해 빈 구간만 훑음(`DartFinancialCollector.java:85-101`) |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 경로가 없어 해당 위험 없음. 무료 호출도 사용자가 명령을 실행하고 `collector-secrets.json`에 키를 넣어야만 나간다(키 없으면 `IllegalStateException`으로 종료 코드 1, `KrxOpenApiClient.java:33-35`, `OpenDartClient.java:125-127`). 상주 프로세스·기본 기동에서 호출되는 경로 없음 |
| 8.4 폭주 방지 | 통과 | 매 호출 전 하루 상한(KRX 9,000·OpenDART 10,000 기본, 파일 잠금으로 같은 캐시 폴더의 동시 실행까지 합산, `CallBudget.java:130-173`), 재시도도 호출로 셈, 재시도 2회·1초·2초 대기, 4xx(429 제외)는 재시도 안 함(`ApiCaller.java:45-86`), OpenDART 020이면 그날 멈춤 기록(`OpenDartClient.java:157-160`). 테스트: `CallBudgetTest` 3건, `ApiCallerTest` 3건, `DartFinancialCollectorTest#요청_제한_020이면_…`. 과금 위험은 없으나 KRX 키 정지 위험은 R3 |
| 8.5 실주문 비용 | 통과 | 주문 경로 없음(A1·A6 근거). 수집기가 실주문을 낼 수 있는 클래스 참조가 ArchUnit과 빌드 격리로 막혀 있음 |

## 점검표 밖 발견

시나리오 시도(잘못된 주문·중복 주문·보호 공백, 그리고 이 PR 고유의 자원·데이터 위험):

1. **[F1, 수정 필요] 기본 기간·매일 예약 실행에서 품질 리포트 단계가 항상 메모리 부족으로 죽고, 그 뒤에 있는 스냅샷 정리(`--keep-snapshots`)가 영영 돌지 않는다.** 순서는 `CollectorMain.krxDaily`에서 스냅샷 커밋(`:134`) → `SnapshotDailyBarSource.load(dir).qualityReport()`(`:138`) → 리포트 쓰기(`:139-140`) → `prune`(`:142`)이다. `--from`을 생략하면 기본이 2010-01-04(`:105`)이고, runbook 4장의 매일 예약 명령(`docs/runbooks/collector.md:65-66`)도 `--from` 없이 `--keep-snapshots 3`만 준다. `load`는 전 기간 행을 문자열 목록으로 읽고(`SnapshotFiles.java:40` `Csv.read`) 다시 `DailyBar`로 만든다. 실측(직접 실행 참고)으로 `-Xmx2g`에서 약 200만 행(2,700종목 × 750거래일, 약 3년)은 1.8GB를 쓰고 297만 행(약 4.4년)부터 `OutOfMemoryError`다. 2010년부터 전체(약 1,100만 행)는 17초 만에 OOM. `OutOfMemoryError`는 `Error`라 `run`의 `catch (IOException | RuntimeException e)`(`:94`)에 잡히지 않고 종료 코드 1로 끝난다. 결과:
   - 매일 새 ID(`krx-daily-<마지막 거래일>-<해시>`)의 전체 기간 스냅샷(실측 합성 데이터 기준 약 560MB)이 커밋되고 지워지지 않는다. risk-ops F2로 넣은 보관 규칙이 권장 설정에서 한 번도 동작하지 않는다(재검토 승인의 근거였던 `CollectorMainTest#기본_배치에서_keep_snapshots가_옛_스냅샷을_정리한다`는 작은 픽스처라 통과).
   - 디스크 여유가 매일 줄다가 20GB 바닥(`RunGuard.checkDisk`)에 닿으면 그때부터 수집기가 매일 종료 코드 4로 거부된다. 실전 엔진 디스크 바닥 자체는 지켜지지만, 같은 디스크의 여유를 수집기 산출물이 20GB까지 계속 깎는다.
   - 품질 리포트(MD-09)가 한 번도 만들어지지 않는다. 스냅샷은 리포트 없이 남아 실험실이 고를 수 있다.
   - ADR-008 4장은 "2GB를 넘을 수 있다 `[추론]`"이라고 적었지만 실제 한계는 약 3년이고, 기획서 MD-10·STR-18 중기·워크포워드가 쓰는 기간(2016년 이후 등)도 넘는다.
   고칠 방향(예시): 품질 리포트를 스트리밍 계산으로 바꾸거나 기간을 나눠 검사, 리포트 실패와 무관하게 `prune`이 돌도록 순서·예외 처리 변경(`OutOfMemoryError`로 죽기 전에 크기로 미리 판단해 리포트를 건너뛰고 경고), runbook 예약 명령에 기본 기간을 명시. 실제 규모(또는 줄인 힙)에서 기본 배치가 정리까지 끝나는 테스트를 함께 넣는다.

2. **[F2, 수정 필요] 재무 스냅샷(`dart-financials`)은 지우면 같은 ID로 다시 만들 수 없는데, 문서·주석은 "같은 ID로 복원된다"고 적어 되돌릴 수 없는 삭제를 되돌릴 수 있는 것처럼 안내한다.** `dartFinancials`는 `--from`·`--to`와 상관없이 캐시에 있는 공시 전체(`collector.filings()`, `CollectorMain.java:165`; `DartFinancialCollector.filings()` `:188-201`은 기간 필터 없음)로 스냅샷을 만든다. 캐시는 실행마다 바뀐다: 새 공시와 정정 공시가 늘고, 대기(14일) 공시가 값 있음/값 모름으로 확정되며(`:224-233`), ID의 날짜는 `scannedThrough`(`:181`)다. 그래서 다음 날 이후로는 예전 재무 스냅샷과 같은 내용·같은 ID를 다시 만들 방법이 없다. 그런데 ADR-008 2.7(`docs/adr/ADR-008-collector.md:85`), runbook 5.1(`docs/runbooks/collector.md:83`), `SnapshotRetention.java:22-23`은 "지운 스냅샷은 캐시가 남아 있으면 같은 기간으로 다시 만들 수 있다(ID가 내용 해시)"라고 소스 구분 없이 적는다. `--keep-snapshots`는 `dart-financials`에도 그대로 적용되고(`CollectorMain.java:188`), 실험실 DB에만 적힌 참조는 보지 못한다(문서가 인정). 결과: 사용자가 문서를 믿고 재무 스냅샷 정리를 켜면, 그 스냅샷을 쓴 실험·선정 결과를 다시 재현할 수 없게 된다(지침 5장 결정론, 지침 8장 3번 "데이터 삭제" 범주). 고칠 방향(예시): 재무 스냅샷을 정리 대상에서 빼거나, 지울 때 "복원 불가"를 명시해 별도 옵션으로만 허용, 또는 캐시에서 특정 시점 스냅샷을 재현할 수 있게(수집 시각 기준 필터) 만들고 문서를 소스별로 고친다.

3. **[R1, 권고] 재무 스냅샷도 일봉과 같은 이유로 리포트·정리 순서 문제가 있는지 확인 필요.** `dartFinancials`는 `SnapshotFinancials.load`(`:185`) 뒤에 `prune`(`:188`)한다. 재무는 행 수가 수만 건 수준이라 `[추론]` 메모리 문제는 낮지만, F1을 고칠 때 같은 구조로 함께 정리한다.

4. **[R2, 권고] 보호 구간 직전 커밋 → 품질 리포트 단계는 보호되지 않는다.** 스냅샷 쓰기를 21:29(서머타임 미국 보호 구간 21:30 직전)에 끝내면 `load`·리포트 단계는 `checkSession` 없이 계속 돈다(ADR-008 2.7이 인정). 실측에서 OOM까지 17~34초 동안 4코어 환경을 썼다. 미국 정규장 개장(22:30)까지 60분 버퍼가 있어 실전 엔진 영향은 낮다 `[추론]`. 다만 OS 낮은 우선순위는 runbook 안내(`nice -n 19`, `start /low`)뿐이고 코드·배포 스크립트가 강제하지 않는다(지침 4장 6번, broker-and-fault-design 5.1 "실험실은 … OS 프로세스 우선순위 낮춤"). 수집기 안에 보호 구간을 주기적으로 보는 감시 스레드(구간에 들어서면 `Runtime.halt(4)`)를 두면 리포트 단계까지 덮을 수 있다. Q-041 결정 때 함께 본다.

5. **[R3, 권고] KRX 하루 10,000회 초과로 키가 정지될 수 있는 경로.** ① `--max-calls`에 상한 검사가 없어 10,000 이상도 받는다(`CollectorMain.java:285`). ② 하루 호출 기록이 캐시 폴더별(`CallBudget.java:123`)이라 `--cache-dir`를 바꿔 두 번 돌리면 각 9,000회, 합계 18,000회가 나간다. KRX 약관 제8조 ④는 "키당 1일 10,000회 이하 … 초과할 경우 서비스가 중지될 수 있다". 과금은 아니지만(8장 통과) 키 정지 시 수집이 멈춘다. `--max-calls`를 약관 한도 이하로 막고, 호출 기록을 캐시 폴더가 아니라 설정 폴더 기준으로 두는 것을 권한다.

6. **[R4, 권고] CI `crtfc_key` 비밀값 패턴은 `collectors/python`만 검사한다.** `scripts/ci/check-python-collectors.sh:5`의 검사 폴더 기본값이 `collectors/python`이고 그 폴더에는 지금 수집기 코드가 없다. 실제로 `crtfc_key`를 다루는 Java 코드·문서·테스트는 이 패턴의 검사 대상이 아니다(gitleaks 기본 규칙만 적용). MD-11 인수 조건 "키·토큰이 로그·스냅샷에 없음(CI 검사 …)"은 문구상 충족이지만 효과는 없다. 저장소 전체(검토 원문 제외)에 같은 패턴과 KRX `AUTH_KEY` 값 패턴을 적용하는 검사를 권한다. 지금 diff에 실제 키는 없음(A10).

7. **[R5, 권고] 종목 코드 재사용 시 옛 회사 일봉에 새 회사의 수정 계수가 곱해진다.** `KrxDailySnapshotBuilder`는 코드별 상태 하나(`states`, `:54, 72`)에 이벤트를 모으고, 20거래일 넘게 비었다가 다시 나오면 전일 종가만 끊는다(`:73-76`). 새 회사에서 생긴 분할 이벤트는 같은 코드의 `events`에 들어가 `cumulative`(`:117, 149`)가 그 날짜 이전 일봉 전체, 즉 옛(상장폐지된) 회사 일봉에도 곱해진다. 생존편향 제거용 폐지 종목 가격이 틀어진다(지침 5장). 재등장 시점 이전 일봉에는 새 회사 이벤트를 적용하지 않도록 구간을 나눌 것을 권한다(data-engineer, 실전 경로 무관이라 판정 제외).

8. **[R6, 권고] KRX 캘린더 수록 범위가 2026-12-31까지라 2026-12-31부터 수집기가 매일 종료 코드 4로 멈춘다.** `RunGuard`가 오늘 ±1일을 보므로(`:283`) 12-31에 2027-01-01 조회로 `CalendarCoverageException` → 거부(`krx.json` `coverageTo: 2026-12-31`). 안전 쪽 동작이고 PR 본문 후속 과제에 있으나, 남은 기간이 82일이라 일정으로 잡아 둘 것.

9. **잘못된 주문·중복 주문 시도 결과(결함 없음)**: 수집기에서 주문 포트·증권사 플러그인·`secrets.json` 로더로 가는 경로를 찾았으나 없다. 컴파일 수준(`forbiddenProjects`)과 클래스 수준(`COLLECTOR_READS_ONLY_ITS_SECRETS`, 위반 예제로 규칙이 실제로 잡는지 확인)에서 모두 막힌다. `ConfigPaths`는 폴더 위치만 구하고 파일을 열지 않음(`CollectorMain.java:307`). 수집기가 `secrets.json`을 직접 `Files`로 읽는 것까지 ArchUnit이 막지는 못하지만 현재 코드에 그런 호출은 없다(`CollectorSecretsTest#프로세스용_secrets_json은_읽지_않는다`).

## 판정
수정 필요(F1: 기본 기간·runbook 매일 예약 실행에서 품질 리포트 단계가 2GB 힙에서 항상 `OutOfMemoryError`(실측 약 300만 행부터, 2010년부터 전체 약 1,100만 행)로 종료 코드 1이 되어 그 뒤의 `--keep-snapshots` 정리가 한 번도 돌지 않고, 매일 약 560MB 스냅샷이 쌓여 디스크 여유를 20GB 바닥까지 깎으며 품질 리포트도 만들어지지 않음. F2: 재무 스냅샷은 지우면 같은 ID로 재현할 수 없는데 ADR-008·runbook·`SnapshotRetention` 주석이 "같은 ID로 복원된다"고 안내해, `--keep-snapshots`가 되돌릴 수 없는 재무 스냅샷 삭제를 되돌릴 수 있는 것처럼 만든다)

## 재검토 2026-10-10
- 대상: PR #39 / 커밋 61ad73e687109fff08777c515b91348a010c68f9 / main(8680fde, #37·#40 머지 뒤) 대비 전체 diff 70개 파일(+5050 −58). 1차 이후 커밋: 8deb727(QA 지적 반영), 2acfd92(개발 쪽 3차 재검토 원문), 61ad73e(main 병합, `docs/adr/README.md`·`docs/decisions/README.md` 목록 충돌 해소). 병합분은 main(#37·#40)과 같은 내용이고 PR 고유 변경은 위 두 커밋뿐임을 `git show --cc 61ad73e`로 확인. 1차 보고서(이 파일 위쪽)는 개발 쪽이 고치지 않았음(`git diff 7f4f44a HEAD -- docs/reviews/pr-39/qa.md` 변경 없음)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (61ad73e): BUILD SUCCESSFUL(1회째). 테스트 결과 XML 합계 654 tests, 0 failures, 0 errors, 0 skipped. 그중 `autotrade-app-collector` 56 tests, `CollectorScaleTest#전_기간_규모에서도_정리와_품질_리포트가_힙_상한_안에서_끝난다` 통과(11.96초)
  - PR 체크 런(GitHub, 61ad73e): `Gradle build`, `Secret scan and collector boundary`(새 단계 `check-collector-keys.sh` 포함), `DB schema (PostgreSQL 16)` 모두 success
  - **F1 재실측**: 새 배포본 lib 클래스패스와 `-Xmx2g`로 수집기가 쓰는 `DailyBarQualityCheck.checkSnapshot(dir, perDate)`를 1차와 같은 합성 스냅샷에 실행
    - 11,070,000행(2,700종목 × 4,100거래일, 2010년부터 전체 규모): 48.7초에 끝남, 검사한 일봉 11,070,000건, 끝난 뒤 사용 힙 29MB. 1차의 전체 적재 방식은 같은 자료에서 17초 만에 `OutOfMemoryError`였다
    - 동등성: 이상 자료를 넣은 합성 스냅샷(1,500종목 × 400일, 599,099행: 일봉 누락 0.2%, 2배 가격 점프 0.2%, 거래량 0, 시가 0, 고가<저가, 같은 날 중복 행, 상장 시작 늦음·상장 종료 구간 포함)에서 `checkSnapshot` 발견 9,192건(오류 5,581건)과 `SnapshotDailyBarSource.load(dir).qualityReport()` 발견 9,192건이 `List.equals`로 완전히 같고 검사 일봉 수도 같음
  - 배포 스크립트로 `krx-daily --max-calls 10001`, `dart-financials --max-calls 20001` 실행: 둘 다 사용법 오류(1 이상 하루 한도 이하)로 거부, 네트워크 호출 없음
  - `scripts/ci/check-collector-keys.sh`를 저장소에서 실행: 종료 코드 0. 임시 저장소에 가짜 값 `Map.of("AUTH_KEY", "ABCDEF0123456789ABCDEF")`를 넣으면 잡힘(종료 코드 1). 같은 값을 Java 문자열 안의 이스케이프된 JSON(`"{\"krx\":{\"auth-key\":\"ABCDEF…\"}}"`)으로 넣으면 잡히지 않음(아래 R7)
  - 보호 테스트: `ArchitectureTest.java` +28 −0, `ModuleOwnershipTest.java` +2 −1, Guard·GuardedOrderGateway·장애 테스트 변경 0건(1차와 같음). 추가 줄에 `@Disabled`·`@Ignore`·`assumeTrue` 없음(검토 원문 인용 줄 제외)

### 1차 지적 확인

| 항목 | 결과 | 근거 |
|---|---|---|
| F1 품질 리포트 OOM으로 정리 미동작 | 해결 | ① 정리가 리포트보다 먼저 돈다: `CollectorMain.finishKrxSnapshot`(커밋 직후 `prune` → `DailyBarQualityCheck.checkSnapshot` → 리포트 쓰기). ② 리포트는 `daily_bars.csv`를 한 줄씩 읽는 날짜 순 검사이고 해시·행 수·헤더·날짜 순서를 확인(`DailyBarQualityCheck.checkSnapshot`). 메모리가 행 수와 무관함을 위 재실측(1,107만 행, 29MB)으로 확인, 결과가 전체 적재 검사와 같음을 이상 자료로 확인. ③ 실제 규모 회귀 테스트 `CollectorScaleTest`(힙 128MB 자식 JVM, 120만 행, 옛 스냅샷이 지워지고 리포트가 남는지, 같은 힙에서 예전 방식은 OOM인지 비교)가 빌드에서 통과. ④ 리포트 단계도 날짜마다 `guard::checkSession`을 불러 장 시간 보호 구간에 들어서면 멈춘다(1차 R2의 비보호 구간이 닫힘) |
| F2 재무 스냅샷 복원 불가 안내 | 해결 | `dartFinancials`에서 `prune` 호출을 없애고 `--keep-snapshots`를 줘도 "재무 스냅샷은 지우면 재현할 수 없어 정리하지 않습니다"만 출력. 테스트 `CollectorMainTest#재무_스냅샷은_keep_snapshots를_줘도_지우지_않는다` 통과. ADR-008 2.7·runbook 5.1·`SnapshotRetention` 주석·D-051 ⑤가 "정리는 일봉만, 재무 스냅샷은 지우면 같은 내용으로 다시 만들 수 없다"로 고쳐짐 |
| R1 재무 쪽 리포트·정리 순서 | 해결 | 재무는 정리 자체를 하지 않음(F2) |
| R2 리포트 단계 비보호·우선순위 미강제 | 일부 해결 | 리포트 단계는 날짜마다 보호 구간 확인(F1 ④). OS 낮은 우선순위는 여전히 runbook 안내뿐이고 코드가 강제하지 않음. 단발 명령을 감시 프로세스 정지 순서에 넣는 일은 Q-041(사용자 결정)로 남아 있음. 판정 제외 |
| R3 KRX 하루 한도 초과로 키 정지 | 해결 | `--max-calls`는 KRX 10,000·OpenDART 20,000 이하만 받음(`CollectorMain.Options.parse`), 호출 기록은 `<설정 폴더>/collector-state/budget/`로 옮겨 `--cache-dir`와 무관하게 합산. 테스트 `CollectorMainTest#호출_상한은_약관_한도를_넘길_수_없고_기록은_캐시_폴더와_무관하게_합산된다` 통과, 배포본 직접 실행으로도 거부 확인 |
| R4 CI 키 패턴 범위 | 대부분 해결 | `scripts/ci/check-collector-keys.sh`가 `git ls-files` 전체(검토 원문 제외)에 `crtfc_key`·`crtfc-key`·`auth-key`·`AUTH_KEY` 값 패턴을 검사하고 CI 단계로 추가됨. 남은 빈틈은 R7 |
| R5 코드 재사용 시 수정 계수 오염 | 해결 | 재등장일을 구간 시작으로 두고 구간별로 누적 계수를 따로 계산(`KrxDailySnapshotBuilder`의 `segmentStarts`, `cumulative.floorEntry(d)`). 테스트에 새 회사 분할이 옛 회사 일봉에 곱해지지 않는 경우 추가, 통과 |
| R6 KRX 캘린더 2026-12-31 만료 | 남음(판정 제외) | `krx.json` `coverageTo` 그대로. 만료되면 수집기가 멈추는 안전 쪽 동작이고 PR 후속 과제에 있음 |

### 점검표 재확인
1차 점검표 A·B·과금 판정은 새 diff에서도 그대로다. 8deb727은 수집기·`autotrade-marketdata` 읽기 코드·문서·CI만 바꿨고 실전 주문 경로, Guard, 블랙리스트, 리스크 한도, 장애 대응 코드는 바꾸지 않았다. 바뀐 근거만 적는다.

| # | 결과 | 근거 |
|---|---|---|
| A8 | 통과 | 1차 근거에 더해 정리가 기본 배치에서 실제로 돌고(F1), 리포트 단계도 보호 구간을 본다. 디스크 20GB 바닥 거부는 그대로 |
| A10 | 통과 | 저장소 전체 수집기 키 값 CI 검사 추가(R4), 테스트 값은 자리표시자. 새 테스트·스크립트에 실제 키 없음(`check-collector-keys.sh` 종료 코드 0, CI gitleaks success) |
| A11 | 통과 | 보호 테스트는 1차와 같이 추가만. F1·F2·R3·R5 각각에 실패 경우를 포함한 테스트 추가(위 표) |
| A14 | 통과 | D-051 ⑤에 "일봉만, 재무 스냅샷은 대상 아님", "품질 리포트는 날짜 순 스트리밍 검사" 반영. 기획서 v14.10 변경 이력 갱신. main 병합 뒤 D-049·D-050·D-051·D-052 순서와 Q-041 번호 충돌 없음 |
| B1 | 통과 | 위 직접 실행 결과(BUILD SUCCESSFUL, 654 tests 0 failures), CI `Gradle build` success(61ad73e) |
| B6 | 통과 | 수집기 테스트 56건(1차 53건 + 재무 정리 제외, 호출 상한 한도·합산, 규모 테스트). 지운 테스트·skip 없음 |
| 과금 8.1~8.5 | 통과 | 외부 호출 지점은 1차와 같은 KRX Open API·OpenDART 두 곳뿐이고 새 호출 경로 없음. 8.4는 `--max-calls` 약관 한도 제한과 설정 폴더 단위 합산으로 1차보다 강해짐. 주문 경로 없음 |

### 점검표 밖 발견(재검토)
시나리오 시도:
1. **기본 매일 예약 실행 → 정리·리포트 완주**: 1차의 1,107만 행 자료로 리포트 단계가 2GB 안에서 끝남을 재실측했고, 정리가 리포트 앞이라 리포트가 어떤 이유로 실패해도 정리는 끝나 있다. 결함 없음.
2. **리포트 단계 중 보호 구간 진입**: `checkSnapshot`이 날짜마다 `perDate.run()`(= `RunGuard.checkSession`)을 불러 `Refused`(종료 코드 4)로 멈춘다. 스냅샷은 이미 커밋돼 있고 리포트만 빠진다. 결함 없음.
3. **캐시 폴더를 바꾼 반복 실행·과대 상한**: 둘 다 막힘(R3). 결함 없음.
4. **잘못된 주문·중복 주문**: 수집기에서 주문 포트·증권사 플러그인·`secrets.json` 로더로 가는 경로가 여전히 컴파일·클래스 수준에서 막혀 있음(1차 시나리오 9와 같음). main에 들어온 #40의 `KiwoomRestClient`는 게이트웨이 쪽이고 수집기 `forbiddenProjects`·ArchUnit 규칙(`com.autotrade.broker..` 참조 금지)에 걸려 수집기에서 쓸 수 없다.
5. **[R7, 권고] `check-collector-keys.sh`는 Java 문자열 안의 이스케이프된 JSON 키를 잡지 못한다.** 패턴 `"auth-key"\s*:\s*"…"`는 `\"auth-key\":\"…\"` 형태(테스트 코드에서 키 파일 내용을 문자열로 만드는 방식, 예: `CollectorMainTest`의 `writeKeys`)와 맞지 않는다. 가짜 값으로 직접 확인했다. 지금 저장소에 실제 키는 없다. 패턴에 선택적 `\\?`를 넣는 정도로 막을 수 있다.
6. **[R8, 권고] "지운 일봉 스냅샷은 같은 `--from`·`--to`로 다시 만들면 같은 ID" 주장의 예외.** 최근 7일 안의 빈 평일 응답과 오늘 응답은 캐시하지 않는다(`KrxDailyCollector.collect`). 그래서 `--to`가 오늘이거나 자료 게시 전이던 날을 포함해 만든 스냅샷은, 나중에 같은 기간으로 다시 만들면 그날 자료가 채워져 다른 ID가 될 수 있다 `[추론]`(KRX 당일 자료 게시 시각 18:00은 `[가정]`). 정리는 최근 K개와 실험 폴더가 참조하는 ID를 남기므로 영향은 실험실 DB에만 적힌 옛 스냅샷으로 좁다. runbook 5.1에 이 예외를 한 줄 적어 둘 것을 권한다.
7. **Q-041 상태**: main에 들어온 ADR-007 선정 일괄 명령은 감시 프로세스의 `stop-request` 파일을 보고 시작을 거부하지만(D-049 ⑨), 수집기는 그 파일을 보지 않는다. 수집기는 시간·힙·디스크로 스스로 물러나고(A8), 단발 명령을 정지 순서에 넣을지는 Q-041 사용자 결정으로 열려 있다. 판정 제외, Q-041 결정 때 수집기도 같은 파일을 보게 할지 함께 정할 것.

## 판정 (재검토)
승인
