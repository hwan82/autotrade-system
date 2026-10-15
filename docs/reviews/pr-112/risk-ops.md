# risk-ops 검토: SecretPaths 지문 고정과 모양 검사 보강(PR #110 QA 권고 후속, D-086)
- 대상: PR #112 / 헤드 b85bede (검토 도중 c650772에서 바뀜. 판정은 b85bede 코드 기준. 그 뒤 618e559는 architect 재검토 원문만 더함) / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base fa1996b, 커밋 3개, 파일 6개 +195 −15: `RealPathReflectionTest.java`, 테스트 소스 `com.autotrade.config.SamePackageRecordReader`, `ADR-002-real-trading-guard.md`, `D-086-real-path-reflection-ban.md`, `docs/qa/pending.md`, `docs/reviews/pr-112/architect.md`). 운영 코드 변경 0줄
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (c650772): BUILD SUCCESSFUL. (b85bede): BUILD SUCCESSFUL
  - `./gradlew --rerun-tasks :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test` (b85bede): 성공. risk 55건, architecture-test 42건(`RealPathReflectionTest` 9건 포함), app-engine 50건, 실패·오류·건너뜀 0. 전체 결과 XML 합계 1,142건, 실패·오류·건너뜀 0
  - CI(b85bede, REST check-runs): `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` 모두 success
  - main 갱신 확인: origin/main이 d6fb6e7(PR #111 머지)로 앞서 있다. `git merge-tree --write-tree origin/main HEAD`는 충돌 없이 트리를 만든다. PR #111은 문서와 `KrxMarketRules` 주석만 바꿨다
  - 변이·변조 실험(각각 `./gradlew --offline :autotrade-architecture-test:test --tests com.autotrade.architecture.RealPathReflectionTest`, 실험 뒤 원문 복원, `git status` 깨끗함 확인). 아래 표

## 변이·변조 실험 (b85bede)
| # | 바꾼 것 | 결과 |
|---|---|---|
| K1 | 운영 `SecretPaths`에 주석 한 줄(뒤 줄 번호 전부 밀림) | 통과(의도대로 지문 불변) |
| K6 | 운영 `SecretPaths`에 주석 두 줄과 빈 줄 | 통과(의도대로) |
| K2 | 운영 `SecretPaths`에 private 메서드 추가 | 잡힘 `레코드_구성_요소를_읽는_클래스의_지문은_고정_값과_같다` |
| K3 | `collect`의 `t == Secret.class`를 `t != Secret.class`로(비밀값 판정 조건만) | 잡힘(지문). c650772에서는 지문 시험을 통과했다(`:autotrade-config:test`만 잡음) |
| K4 | `collect`의 기존 `paths.add(path)`를 `Set<Object>`로 바꿔 본 같은 집합에 구성 요소 타입의 클래스 객체 넣기(`@SuppressWarnings`는 소스 보존이라 클래스 파일에 안 남음) | 잡힘(지문). c650772에서는 `RealPathReflectionTest` 9건 모두 통과했고 지문 계산 입력 줄이 한 줄도 바뀌지 않음을 덤프로 확인했다(architect FM1과 같은 결론) |
| K5 | 정규식 상수 `KEY_LIKE` `[a-z][a-z-]*` → `.*`(경고에 계좌번호가 키 이름으로 나갈 수 있는 변경) | 잡힘(지문). c650772에서는 지문 시험 통과 |
| MD | 지문 함수를 `SKIP_DEBUG \| SKIP_CODE`로 약화 | 잡힘(고정 값과 달라짐). 다만 함수와 고정 값을 함께 고치면 통과한다. 아래 R-4 |
| MR2 | "허용 목록 밖 리플렉션 타입 메서드" 검사를 끔 | 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다` |
| MT | `REFLECT_TYPE_OWNERS`에서 `java.lang.reflect.Type` 제거(architect MM4) | 잡힘(새 예제 `plainTypeName`) |
| MRW | `REFLECT_TYPE_OWNERS`에서 새로 넣은 `WildcardType`·`TypeVariable`·`GenericArrayType` 제거 | **살아남음**. 아래 R-5 |
| MP | 와일드카드 검사를 반환 타입에만(architect MM3) | 잡힘(새 예제 `drain(List<? super Object>)`) |
| MW | 와일드카드·타입 변수 검사를 끔 | 잡힘 모양 시험 |
| MN | "중첩 클래스를 둔다" 보고를 끔 | 잡힘 모양 시험 |
| MNT | 고정 타입 목록 검사를 중첩 클래스에 적용하지 않음 | 잡힘 모양 시험 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄(diff 파일 목록). `RealTradingGuard` 기본 OFF·재기동 OFF 시험은 `:autotrade-risk:test` 55건 통과로 그대로 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. `RealPathReflectionTest`의 변경은 강화뿐이다: `Type`·`ParameterizedType`·`WildcardType`·`TypeVariable`·`GenericArrayType` 메서드를 허용 클래스 안으로(RealPathReflectionTest.java:407-408, :470-474), 서명 와일드카드·타입 변수 금지(반환·매개변수·필드, :810-813, :826-838), 중첩 클래스 금지와 중첩 클래스 타입 검사(:782-794), 디버그 정보만 뺀 바이트코드 지문 고정(:710-750). 삭제 줄은 지문 계산 방식 교체, 기존 타입 목록 검사 4줄(중첩 클래스까지 넓혀 옮김), 단언 1줄(같은 내용에 단언을 더해 교체)뿐이다. MR2·MT·MP·MW·MN·MNT·K2~K5로 시험에 고정됐음을 확인 |
| A3 | 해당 없음 | 주문·증권사 연동 코드 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 코드 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·자원 정지 순서 변경 없음. 위반 예제 `SamePackageRecordReader`(중첩 `Sink` 포함)는 테스트 소스라 런타임 구성에 들어가지 않는다(`실전_경로_런타임_구성은_고정_목록과_같다` 통과) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff 전체를 읽음. 키·계좌번호·토큰 없음. 지문 값은 `SecretPaths` 클래스 파일의 SHA-256이라 비밀값이 아니다. CI 비밀값 스캔 success. 덧붙여 K5처럼 비밀값 가리기 규칙(`KEY_LIKE`)을 바꾸는 변경도 이제 지문으로 보호 테스트 변경이 된다 |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 0건(1,141 → 1,142건). 새 검사마다 위반 예제와 기대 문자열이 있다(`lastType`·`leak()`·`drain`·`Sink`·`typeArgument`·`plainTypeName`, RealPathReflectionTest.java:680-701, :983-988). 운영 `SecretPaths`에 대해 위반 0건과 지문 일치를 단언한다(:677-679, :714-720). 변이 표의 MRW만 살아남는데 같은 타입이 실전 경로 타입 허용 목록(`ALLOWED_REFLECT_TYPES`, :286-287, 판정 :340) 밖이라 따로 막혀 있다(R-5, 권고) |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음. 실전 경로 정적 검사만 강화 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | D-086:51이 지문을 "디버그 정보(줄 번호·지역 변수 이름)만 뺀 바이트코드"로, 데이터 흐름과 상수가 들어간다고 적고, 이는 `fingerprint`(:734-750, ASM `ClassReader.SKIP_DEBUG` → `ClassWriter`)와 K1·K3·K4·K5·K6 결과와 일치한다. c650772에서 문서가 기계 검사보다 넓게 주장하던 점(architect AR-1, 이 검토의 K3·K4·K5)은 b85bede에서 해소됐다. JDK 갱신 시 지문이 바뀔 수 있다는 한계(실패 방향)도 적혀 있다. 검증 절 `RealPathReflectionTest` 9건(D-086:58)과 ADR-002:242 "테스트 9건"은 헤드 `@Test` 9개와 같다. `docs/qa/pending.md:31`에 PR #112 독립 QA 요청 줄이 있다 |

## 점검표 밖 발견
- **R-4 (권고, 낮음) 지문 함수 자체의 성질을 고정하는 시험이 약하다.** 지문 시험의 자기 확인은 서로 다른 두 클래스(`SamePackageRecordReader`와 `ReflectionFreeEngineCode`)의 지문이 다르다는 것뿐이다(RealPathReflectionTest.java:722-725). MD처럼 `SKIP_CODE`를 더해 본문을 빼도 두 클래스는 여전히 다르므로, 지문 함수와 고정 값을 함께 바꾸는 약화는 이 시험으로 잡히지 않는다. 그 변경은 보호 테스트 파일 diff로 드러나 실전 관련 PR 검토를 거치므로 지금 열린 길은 아니다. 다음에 이 파일을 고칠 때 "메서드 본문만 다른 두 고정 예제의 지문은 다르고, 줄 번호만 다른 두 예제는 같다"를 단언하기를 권한다.
- **R-5 (권고, 낮음) `REFLECT_TYPE_OWNERS`의 새 세 타입에 위반 예제가 없다(MRW 생존).** 실전 경로 클래스는 `ALLOWED_REFLECT_TYPES`(`Type`·`ParameterizedType`·`RecordComponent`) 밖의 `java.lang.reflect` 타입을 쓰면 허용 목록 밖 타입으로 이미 실패하므로[사실, RealPathReflectionTest.java:286-287·:340] 이중 방어다. 지금 열린 길은 없다. 예제를 두려면 그 예제는 타입 목록 위반도 함께 내므로 기대 문자열을 "허용 목록 밖 리플렉션 타입 메서드: ...WildcardType"처럼 메서드 쪽으로 단언하면 된다.
- 참고: 지문은 `autotrade-config` 빌드 출력의 클래스 파일을 읽는다. 툴체인 JDK 21 javac 출력에 기대므로 JDK 갱신이나 컴파일 옵션 변경(`-parameters` 등) 때 바뀔 수 있고, 실패하는 방향이라 보호를 약하게 하지 않는다.
- 참고: 검토 도중 같은 브랜치 헤드가 c650772 → e2b78f0(architect 원문) → b85bede(architect 반영)로 바뀌었다. c650772의 지문은 멤버·접근 쌍·타입만 묶어 K3·K4·K5를 놓쳤고, 이 검토에서도 같은 결론을 냈으나 b85bede가 바이트코드 지문으로 고쳐 해소됐다. 판정은 b85bede 코드와 위 실행 결과에만 근거한다. architect 원문은 판정 근거로 쓰지 않았다.
- 참고: origin/main(d6fb6e7)이 이 브랜치보다 앞서 있으나 충돌은 없다. 머지 전 main 병합 뒤 CI 재확인을 권한다.
- 참고: 실전 관련 PR(보호 테스트 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 관문이다(`docs/qa/pending.md:31`).

## 판정
승인 (b85bede 기준. 지문이 메서드 본문 데이터 흐름·상수 변경을 잡음을 K3·K4·K5로 확인. 권고 R-4·R-5는 머지를 막지 않는다. 보호 테스트 변경이라 머지는 사용자가 하고, 머지 전 독립 QA 관문)

## 재검토 2026-10-15
- 대상: PR #112 / 헤드 654b20e / main 대비 diff 전체(`git diff origin/main...HEAD`, origin/main = b4ed7fc, merge-base d6fb6e7, 파일 16개 +484 −18). 1차(b85bede) 이후 이 PR 자신의 변경은 69b78bc(main d6fb6e7 병합)와 654b20e(독립 QA F1·R1~R4 반영)다. 654b20e는 `RealPathReflectionTest.java`, `SamePackageRecordReader.java`, D-086, `docs/team/project-instructions.md` 11장 보호 테스트, `docs/qa/README.md`, `docs/qa/checklists.md` A11, `docs/qa/pending.md`, `docs/qa-out-status.md`를 고치고 `.github/CODEOWNERS`, `.github/workflows/ci.yml` 단계 "Protected change marker", `scripts/ci/mark-protected-changes.sh`, `scripts/ci/protected-paths.txt`를 더했다. 운영 코드 변경은 여전히 0줄이다
- 점검표: A (헤드의 A11 새 문구 "고정 값 갱신이 있으면 그 이유와 대상 코드 변경을 검토했다" 포함)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (654b20e): BUILD SUCCESSFUL
  - `./gradlew --rerun-tasks :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test`: 성공. risk 55건, architecture-test 42건(`RealPathReflectionTest` 9건 포함), app-engine 50건, 실패·오류·건너뜀 0. 결과 XML 전체 합계 1,142건, 실패·오류·건너뜀 0
  - CI: 654b20e에는 workflow run이 없다(REST `actions/runs?head_sha=654b20e…` 빈 목록, `check-runs`도 0건). 직전 헤드 520ef5c까지는 `pull_request` run이 있었다. 원인은 아래 F-3의 main 충돌로 본다[추론: `pull_request` 이벤트는 병합 커밋을 만들 수 없으면 실행되지 않는다]. 그래서 새 CI 단계가 GitHub Actions에서 실제로 도는지는 **확인 못 함**
  - main 병합 확인: `git merge-tree --write-tree --name-only origin/main HEAD` → `CONFLICT (content): Merge conflict in docs/qa/pending.md`. main 6f241f3("PR #114 독립 QA 요청을 대기 목록에 추가")과 이 PR이 같은 표 끝에 줄을 더했다
  - 표시 스크립트 로컬 실행: `bash scripts/ci/mark-protected-changes.sh main` → 경고 1줄과 보호 대상 6개 목록(`.github/CODEOWNERS`, `ci.yml`, `RealPathReflectionTest.java`, `SamePackageRecordReader.java`, 스크립트 2개). 정상 동작
  - 표시 스크립트 이름 바꾸기 실험: 스크래치 저장소에서 `autotrade-architecture-test/src/A.java`를 `git mv`로 `other/A.java`로 옮긴 커밋에 스크립트를 실행 → `보호 대상 변경 없음`. `git diff --name-only`가 이름 바꾸기를 새 경로 하나로만 보이기 때문이다. `--no-renames`를 붙이면 두 경로가 모두 나온다. 아래 F-4
  - 브랜치 보호 확인: `gh api repos/hwan82/autotrade-system/branches/main/protection` → 404 "Branch not protected"
  - 변이·변조 실험(각각 `./gradlew --offline :autotrade-architecture-test:test --tests com.autotrade.architecture.RealPathReflectionTest`, 실험 뒤 원문 복원)
    - MD2: 지문 함수에 `SKIP_CODE`를 더하고 운영 지문 비교를 `isNotNull()`로 바꿈(함수 약화와 값 갱신을 함께 한 것과 같음) → 잡힘 `레코드_구성_요소를_읽는_클래스의_지문은_고정_값과_같다`(새 자체 시험 "본문 상수만 다르면 지문이 달라야 한다"). 1차 R-4 해소
    - N3: `hasWildcardOrTypeVariable`의 제네릭 배열 갈래를 끔 → 잡힘 모양 시험(`drainAll`)
    - MRW: `REFLECT_TYPE_OWNERS`에서 `WildcardType`·`TypeVariable`·`GenericArrayType` 제거 → 잡힘 `규칙은_리플렉션_우회를_실제로_잡아낸다`(`wildcardBound`·`variableBound`·`arrayComponent`). 1차 R-5 해소
    - MT(`Type` 제거), MP(와일드카드 검사를 반환 타입에만) → 잡힘
    - K1(운영 `SecretPaths`에 주석) → 통과. K4(기존 `paths.add`에 클래스 객체) → 잡힘(지문)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | `ArchitectureTest` 변경 없음. 1차 이후 시험 변경은 강화뿐이다: 위반 예제 `drainAll`·`wildcardBound`·`variableBound`·`arrayComponent`와 기대 문자열(RealPathReflectionTest.java:694, :1020-1023), 지문 함수 자체 시험(:726-729, `probeClass` :752). N3·MRW·MD2로 고정 확인 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음. 새 CI 단계는 `guards` 작업 안의 경고 표시라 빌드·런타임에 영향 없다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 1차 이후 diff 전체를 읽음. 키·계좌번호·토큰 없음(CODEOWNERS의 `@hwan82`는 저장소 소유자 계정 이름이라 비밀값이 아니다). 비밀값 스캔 CI는 654b20e에서 돌지 않았다(위 CI 항목) |
| A11 | 실패 | 보호 테스트 삭제·약화는 없고, 이 PR이 새로 고정한 값(`RECORD_READER_FINGERPRINTS`, :712)의 대상은 운영 `SecretPaths`이며 이 PR은 그 코드를 바꾸지 않았다(새 A11 문구 충족). 1차 R-4·R-5는 MD2·MRW로 해소 확인. 그러나 이 PR이 F1 대응으로 더한 기계 표시(`mark-protected-changes.sh:17`)는 보호 테스트를 보호 경로 밖으로 **옮기는** 변경을 보지 못한다(F-4, 직접 재현). 옮기면 그 시험은 `autotrade-architecture-test`에서 빠져 사실상 삭제인데 표시가 "보호 대상 변경 없음"을 낸다. 새 보호 장치에 우회가 있고 이를 잡는 시험도 없다 |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 실패 | (1) main(b4ed7fc)과 `docs/qa/pending.md` 충돌(F-3). 이 때문에 654b20e의 CI가 돌지 않아 새 CI 단계와 비밀값 스캔 결과를 확인하지 못했다. (2) 그 밖의 문서 대조는 맞다: 독립 QA F1의 (a)(보호 테스트 정의에 `autotrade-architecture-test` 전체와 고정 값 갱신을 넣음, project-instructions.md:114-117, qa/README.md:15-17, checklists.md A11)와 (b)(CI 표시, CODEOWNERS)가 들어갔고, D-086:51이 지문 함수 자체 시험과 CI 표시를 적는다. D-086 검증 절의 새 줄은 헤드 시험과 맞다 |

## 점검표 밖 발견 (재검토)
- **F-3 (수정 필요, A14) main과 충돌, CI 미실행.** origin/main b4ed7fc의 `docs/qa/pending.md`(6f241f3, PR #114 요청 줄)와 이 PR의 두 줄이 같은 표 끝에서 충돌한다. main을 병합해 두 줄을 모두 남기고, 새 헤드에서 CI 세 작업(특히 새 단계 "Protected change marker"가 이 PR 자체에 경고를 남기는지)을 확인한다.
- **F-4 (수정 필요, A11) 보호 대상 표시가 이름 바꾸기를 놓친다.** `scripts/ci/mark-protected-changes.sh:17`의 `git diff --name-only "origin/${base}...HEAD"`는 git 기본 이름 바꾸기 감지 때문에 옮긴 파일의 새 경로만 낸다. 보호 시험(예: `RealPathReflectionTest.java`)을 `autotrade-architecture-test` 밖으로 옮기면 경고가 나오지 않는다(스크래치 저장소에서 재현). 고칠 것: `git diff --name-only --no-renames ...`로 바꾸고, 가능하면 표시 스크립트의 동작(보호 경로 수정·삭제·밖으로 옮기기가 걸리고 보호 경로 밖 변경은 안 걸림)을 확인하는 작은 CI 자체 시험(임시 저장소)을 둔다.
- **R-6 (권고, 중간) 보호 대상 경로 목록이 실전 경로 모듈 일부만 덮는다.** `scripts/ci/protected-paths.txt`는 engine·watchdog·risk·broker-*·`SecretPaths.java`만 담고, 실전 경로 런타임에 실리는 `autotrade-domain`(`OrderGateway`·주문 상태), `autotrade-control`(명령 채널·2차 확인, ADR-006), `autotrade-config`(나머지, `RuntimeConfig`의 실전 설정), `autotrade-marketdata`·`autotrade-strategy`·`notification-jar`·`monitor-agent-jar`(RealPathReflectionTest.java:87-91 `REQUIRED_PROJECTS`)는 빠져 있다. 규칙 문서도 빠져 있다(`docs/team/project-instructions.md`, `docs/qa/README.md`·`checklists.md`, D-086, ADR-002). 표시는 경고일 뿐이고 실전 관련 PR 판정은 프로젝트 지침 10장 내용 기준이라 보호가 약해지지는 않지만, 경고 제목이 "실전 관련 PR"이라 경고가 없으면 실전 관련이 아니라고 읽힐 수 있다. `REQUIRED_PROJECTS` 전체와 규칙 문서를 넣거나, 스크립트·README에 "이 목록 밖도 10장 기준으로 실전 관련일 수 있다"를 적기를 권한다.
- **R-7 (참고) CODEOWNERS는 지금 아무것도 강제하지 않는다.** main에 브랜치 보호가 없어(REST 404) 코드 소유자 리뷰 요구가 걸리지 않는다. 실전 관련 PR의 사용자 머지는 여전히 절차(지침 10·11장)에 기댄다. 저장소 설정에서 main 보호와 "Require review from Code Owners"를 켤지는 사용자 결정이다(혼자 쓰는 저장소라 본인 PR 승인 문제를 함께 따져야 한다).
- **참고: 프로젝트 지침 원문 변경.** `docs/team/project-instructions.md` 11장 보호 테스트 정의가 넓어졌다(강화 방향, 4장 절대 규칙 약화 없음). CLAUDE.md는 이 파일을 직접 불러오므로 머지 즉시 적용되지만, 사용자가 프로젝트 설정 > 지침에 붙여 넣은 사본은 사용자가 따로 갱신해야 한다. 이 결과로 `autotrade-architecture-test`만 고치는 PR도 모두 사용자 머지 대상이 된다.
- 참고: 1차 원문과 architect 원문(`docs/reviews/pr-112/risk-ops.md` 1차 절, `architect.md`)은 380c612 이후 바뀌지 않았다(`git diff --stat 380c612 HEAD -- docs/reviews/pr-112/risk-ops.md docs/reviews/pr-112/architect.md` 빈 출력). 독립 QA 원문은 무엇을 확인할지 정하는 데만 썼고, 판정은 654b20e 코드와 위 실행 결과로만 했다.
- 참고: 실전 관련 PR(보호 테스트·CI·지침 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 재검토 관문이다(`docs/qa/pending.md`의 "PR #112 재검토해줘" 줄).

## 판정 (재검토)
수정 필요(A14·F-3: main b4ed7fc와 `docs/qa/pending.md` 충돌, 그 때문에 654b20e CI 미실행으로 새 CI 단계를 확인 못 함. A11·F-4: 보호 대상 표시가 보호 시험을 밖으로 옮기는 변경을 놓침, `--no-renames` 필요. 권고 R-6, 참고 R-7. 1차 권고 R-4·R-5는 해소. 머지는 사용자)

## 재검토 2026-10-15 (2)
- 대상: PR #112 / 헤드 c14578d / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 17개 +602 −18, merge-base b4ed7fc). 2차(654b20e) 이후 이 PR 자신의 변경은 병합 커밋 c14578d 하나다. `git show --remerge-diff c14578d`로 본 이 PR 쪽 손질은 `.github/CODEOWNERS`, `ci.yml`(자체 시험 단계), `scripts/ci/mark-protected-changes.sh`(`--no-renames`, 경고 제목), `scripts/ci/protected-paths.txt`(실전 경로 런타임 모듈 전체·규칙 문서), 새 `scripts/ci/test-mark-protected-changes.sh`, `docs/qa/README.md`, `docs/qa-out-status.md`, `docs/qa/pending.md` 충돌 해소(양쪽 줄 유지)다. main 쪽 PR #114가 바꾼 `ArchitectureTest`·`ModuleOwnershipTest`는 이미 main에 머지된 변경이라 이 PR diff에 없고, `RealPathReflectionTest`는 main에서 바뀌지 않았다(`git diff --stat d6fb6e7 b4ed7fc -- autotrade-architecture-test`). 운영 코드 변경은 여전히 0줄이다
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (c14578d): BUILD SUCCESSFUL
  - `./gradlew --rerun-tasks :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test`: 성공. risk 55건, architecture-test 42건(`RealPathReflectionTest` 9건 포함), app-engine 50건, 실패·오류·건너뜀 0. 결과 XML 전체 합계 1,178건, 실패·오류·건너뜀 0
  - CI(c14578d, REST): workflow run 38704444993 `pull_request` success. `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary` success. `guards` 작업 단계 "Protected change marker self-test"·"Protected change marker" success이고, 실행 주석에 `warning 보호 대상 변경(실전 관련 PR) 보호 대상 파일 12개를 바꾼다...`가 남았다. 2차의 "확인 못 함"(새 CI 단계 실제 동작) 해소
  - `bash scripts/ci/test-mark-protected-changes.sh` → "표시 스크립트 자체 시험 통과". 변조: `mark-protected-changes.sh`에서 `--no-renames`를 빼면 자체 시험이 `::error::보호 시험을 밖으로 옮긴 변경을 표시하지 않았다: 보호 대상 변경 없음`으로 실패(복원 뒤 `git status` 깨끗함). F-4 고정 확인
  - `bash scripts/ci/mark-protected-changes.sh main` → 보호 대상 12개 표시(규칙 문서 5개 포함)
  - 지문 시험 재확인(RealPathReflectionTest 단독 실행, 실험 뒤 복원): K1(운영 `SecretPaths` 주석) 통과, K4(기존 `paths.add`에 클래스 객체) 잡힘, MD2(지문 함수 `SKIP_CODE` + 값 갱신 대용) 잡힘
  - main 병합 확인: origin/main이 이 헤드 뒤에 다시 88212d3(PR #115 머지, 2026-10-15 08:21 UTC, 이 헤드 커밋 08:15 UTC보다 뒤)으로 앞섰다. `git merge-tree --write-tree --name-only origin/main HEAD` → `CONFLICT (content): Merge conflict in docs/qa-out-status.md`(main은 PR #114 QA 처리 줄, 이 PR은 PR #112 처리 줄 두 개를 같은 표 끝에 더함)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | `ArchitectureTest`는 이 PR diff에 없다. `RealPathReflectionTest` 변경은 2차와 같고(강화뿐), K4·MD2로 다시 확인 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음. 새 CI 단계는 `guards` 작업의 표시·자체 시험이라 런타임 영향 없다. main의 새 모듈 `autotrade-monitor-sources`는 실험실만 의존하고(`autotrade-app-lab/build.gradle.kts:20`) 실전 경로 런타임 구성 시험이 통과한다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 2차 이후 diff 전체를 읽음. 키·계좌번호·토큰 없음(자체 시험의 `ci@example.invalid`는 임시 저장소 커밋 작성자 자리표시자). CI 비밀값 스캔(전체 이력) success |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. 2차 F-4 해소: `mark-protected-changes.sh:19`가 `--no-renames`로 옛 경로를 보고, 자체 시험(`test-mark-protected-changes.sh`)이 밖으로 옮기기·수정·보호 밖 변경 세 경우를 고정하며 CI에서 PR마다 돈다. `--no-renames`를 빼는 변조가 자체 시험에서 실패함을 확인. `BASE_REF_PREFIX`(`:19`)는 자체 시험이 지역 브랜치를 쓰기 위한 환경 변수로, CI에서 바꾸려면 보호 경로인 `ci.yml`을 고쳐야 한다. 이 PR이 고정한 값 `RECORD_READER_FINGERPRINTS`의 대상 `SecretPaths`는 바뀌지 않았다 |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 실패 | 2차 F-3(main b4ed7fc 충돌)은 c14578d에서 해소됐고(`pending.md` 양쪽 줄 유지 확인), 문서 대조도 맞다: `docs/qa/README.md:17`이 "경고가 없다고 실전 관련이 아닌 것은 아니다", 이름 바꾸기 처리, 자체 시험, 브랜치 보호가 꺼져 있으면 CODEOWNERS가 강제되지 않음을 적는다(R-6·R-7 반영). 그러나 그 뒤 main이 88212d3으로 앞서며 `docs/qa-out-status.md`가 새로 충돌한다(F-5). 지금 헤드는 GitHub에서 병합할 수 없다 |

## 점검표 밖 발견 (재검토 2)
- **F-5 (수정 필요, A14) main 88212d3과 `docs/qa-out-status.md` 충돌.** PR #115 머지(main b4ed7fc → 88212d3)가 이 헤드보다 늦게 들어와 생긴 문서 표 충돌이다. main을 병합해 양쪽 줄(PR #114 QA 처리 줄, PR #112 처리 줄 두 개)을 모두 남기고 새 헤드의 CI를 확인한다. 이 검토가 본 c14578d의 코드·스크립트·규칙 문서 내용에는 더 고칠 것이 없다. 따라서 다음 재검토는 병합 커밋이 문서 표 충돌 해소와 main 내용만 담는지(`git show --remerge-diff`)와 CI 결과 확인으로 충분하다.
- 2차 권고 R-6 해소: `protected-paths.txt`와 CODEOWNERS가 `REQUIRED_PROJECTS`(RealPathReflectionTest.java:87-91)의 실전 경로 런타임 모듈 전체(`autotrade-broker-*`로 broker-api 포함)와 규칙 문서 5개를 덮는다. 경고 제목도 "보호 대상 변경(실전 관련 PR)"으로 바뀌고 README에 판단 기준이 지침 10·11장임을 적었다.
- 2차 참고 R-7은 그대로다: main 브랜치 보호가 없어 CODEOWNERS는 강제되지 않는다(사용자 결정, README에 명시됨).
- 참고: 1차·2차 원문과 다른 검토 원문은 이 재검토에서 바뀌지 않았다. 판정은 c14578d 코드와 위 실행 결과로만 했다.
- 참고: 실전 관련 PR(보호 테스트·CI·지침 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 재검토 관문이다.

## 판정 (재검토 2)
수정 필요(A14·F-5: c14578d 뒤에 들어온 main 88212d3과 `docs/qa-out-status.md` 충돌로 지금은 병합 불가. 2차 F-3·F-4와 권고 R-6은 해소 확인, 그 밖의 내용은 더 고칠 것 없음. 머지는 사용자)

## 재검토 2026-10-15 (3)
- 대상: PR #112 / 헤드 3fbc32c / main 대비 diff 전체(`git diff origin/main...HEAD`, origin/main = 88212d3, 파일 17개 +678 −18). origin/main은 헤드의 조상이다(`git merge-base --is-ancestor origin/main HEAD` 참). 2차 재검토(c14578d) 이후 커밋은 a45632e(architect 원문), b09cd74(이 원문 재검토 2), 3fbc32c(main 88212d3 병합)다
- 점검표: A
- 직접 실행한 것:
  - 병합 커밋 내용: `git show --remerge-diff 3fbc32c` → 손으로 고친 것은 `docs/qa-out-status.md` 충돌 해소 하나뿐이다. 충돌 표지를 지우고 main 쪽 PR #114 QA 처리 줄과 이 PR 쪽 PR #112 처리 줄 두 개를 모두 남겼다. 그 밖의 변경은 main(b4ed7fc → 88212d3, PR #115)에서 그대로 들어왔다. main 쪽 변경 파일은 `autotrade-app-lab` 모니터링, `autotrade-ui` 화면, 문서(D-090·D-091·ADR-005·config-keys·결정 목록)와 검토 원문이다. 실전 경로 모듈과 보호 경로에 겹치는 파일은 없다. PR diff의 파일 목록과 내용은 c14578d와 같고 검토 원문 2개만 늘었다
  - `./gradlew build --no-daemon` (3fbc32c): BUILD SUCCESSFUL
  - `./gradlew --rerun-tasks :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test`: 성공. risk 55건, architecture-test 42건(`RealPathReflectionTest` 9건 포함), app-engine 50건, 실패·오류·건너뜀 0. 결과 XML 전체 합계 1,185건, 실패·오류·건너뜀 0
  - CI(3fbc32c, REST): workflow run 38705541668 `pull_request`. `Gradle build`·`DB schema (PostgreSQL 16)`·`Secret scan and collector boundary`는 모두 success다. `guards`의 "Protected change marker self-test"·"Protected change marker" 단계는 success이고, `보호 대상 변경(실전 관련 PR)` 경고 주석을 남겼다
  - 검토를 마칠 때 origin/main은 여전히 88212d3이다(fetch 후 확인)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 운영 코드 변경 0줄. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | `ArchitectureTest`는 PR diff에 없다. `RealPathReflectionTest` 변경은 2차 재검토와 같다(강화뿐, 1~2차 변이 실험으로 고정 확인) |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음. main에서 들어온 PR #115는 실험실·UI 변경이고, 실전 경로 런타임 구성 시험이 통과한다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 병합 해소 줄을 읽었고 비밀값이 없다. CI 비밀값 스캔(전체 이력) success |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. 표시 스크립트 자체 시험 CI 단계 success. 2차 재검토의 `--no-renames` 변조 실험 결과가 그대로 유효하다(스크립트 변경 없음) |
| A12 | 통과 | 장애 대응표 17종·해외 O1~O8·장애 테스트 8종 문서와 시험 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음 |
| A14 | 통과 | 재검토 2의 F-5 해소. main 88212d3이 조상이고 충돌이 없다. `docs/qa-out-status.md`는 세 줄을 모두 보존한다. 결정 로그·규칙 문서 대조는 재검토 2와 같다(변경 없음) |

## 점검표 밖 발견 (재검토 3)
- 없음. 참고 R-7(main 브랜치 보호가 없어 CODEOWNERS가 강제되지 않음)은 사용자 결정으로 남는다.
- 참고: 실전 관련 PR(보호 테스트·CI·지침 변경)이라 머지는 사용자가 하고, 머지 전 독립 QA 재검토 관문이다(`docs/qa/pending.md`의 "PR #112 재검토해줘" 줄). 지침 원문(`docs/team/project-instructions.md` 11장)이 바뀌므로, 사용자는 프로젝트 설정 > 지침의 사본도 갱신해야 한다.
- 참고: 머지 직전에 main이 다시 앞서 있으면, 병합한 뒤 CI를 다시 확인한다.

## 판정 (재검토 3)
승인 (3fbc32c 기준. F-5 해소, 병합 커밋은 `docs/qa-out-status.md` 충돌 해소만 담음, CI 세 작업 success. 참고 R-7은 사용자 결정. 보호 테스트·CI·지침 변경이라 머지는 사용자가 하고, 머지 전 독립 QA 재검토 관문)
