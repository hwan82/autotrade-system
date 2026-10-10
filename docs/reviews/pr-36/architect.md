# architect 검토: Q-007·Q-026 수집 소스 라이선스·약관 조사, D-050, 기획서 v14.9
- 대상: PR #36 / 브랜치 claude/project-thread-ngnjxp / 커밋 85d187a(직전 105cb8f 포함) / main(f2e39b3) 대비 diff (`git diff origin/main...HEAD`: docs/decisions/README.md, docs/research/q-007-q-026-collector-licenses.md(신규), docs/spec/autotrade-spec.md 3개 파일)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` 5회(105cb8f에서 1+3회, 85d187a에서 1회) → 모두 EXIT 1. 원인은 코드가 아니라 `:build-logic` 플러그인 의존성 해석 실패: `repo.maven.apache.org`가 `kotlin-stdlib:2.0.21`, `kotlin-gradle-plugins-bom:2.0.21`, `kotlin-compiler-embeddable:2.0.21` POM 요청에 **429 Too Many Requests**. 컴파일·테스트 단계까지 가지 못했다. 테스트 결과 0건.
  - `git diff 105cb8f 85d187a --word-diff`: 추가 커밋은 D-049→D-050, Q-035→Q-036 번호 치환뿐이고 내용 변경 없음을 확인. 원격 브랜치 전체에서 결정 로그 번호를 대조: D-049는 origin/claude/project-thread-hwfrnx가 쓰고, D-050·Q-036은 이 브랜치만 쓴다(충돌 없음). 다른 열린 브랜치의 기획서 버전은 v14.8이라 v14.9 충돌 없음.
  - `grep -rn "Q-007\|Q-026\|D-050\|Q-036"`로 바뀌지 않은 문서·코드의 잔존 참조 확인.
  - 설정 로더 확인: `autotrade-config/src/main/java/com/autotrade/config/ConfigJson.java:17,27`(`FAIL_ON_UNKNOWN_PROPERTIES`), `ConfigLoader.java:18`, `docs/config/config-keys.md:7-8,59-70`.
  - 외부 약관 원문(openapi.krx.co.kr, opendart.fss.or.kr) 직접 대조 시도 → 프록시가 CONNECT 거부(403). 인용 문구는 대조하지 못했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 검토자 직접 실행 `./gradlew build` 5회 모두 Maven Central 429로 `:build-logic` 구성 단계에서 실패(코드와 무관한 환경 원인). diff가 문서 3개뿐이라 빌드 결과가 바뀔 가능성은 낮지만 `[추론]`, 규칙상 직접 통과를 확인하지 못했다 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음 |
| B3 | 실패 | 이 PR이 계약 파일을 직접 고치지는 않지만, 사용자에게 지금 할 작업으로 "KRX Open API·OpenDART 인증키를 `secrets.json`에 등록"을 지시한다(docs/spec/autotrade-spec.md:322, docs/research/q-007-q-026-collector-licenses.md:112,134). `secrets.json`은 Java 설정 계약이고 모르는 키가 있으면 파일 전체를 버린다(ConfigJson.java:27, config-keys.md:8). 설정 키 ADR·config-keys.md 갱신 없이 등록을 지시해 계약과 충돌한다. 점검표 밖 1 |
| B4 | 해당 없음 | 코드 변경 없음. 문서상 결정론 규칙 변경 없음(스냅샷 해시 고정 유지, MD-11) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 해당 없음 | 코드 변경 없음. 테스트 삭제·건너뛰기 없음(diff에 테스트 파일 없음) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 변경 없음. Q-036(7단계 실전용 선정 입력의 약관 해석)은 사용자 결정으로 남겼고 실전 경로 코드·규칙을 바꾸지 않는다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 설정 이름 제안(`krx-openapi.auth-key`)과 파라미터 이름(`crtfc_key`, `AUTH_KEY`)만 있음 |

## 점검표 밖 발견

1. **(수정 필요) 수집기 인증키를 `secrets.json`에 두라는 지시가 D-019 경계와 설정 계약을 모두 어긴다.**
   - `[사실]` `secrets.json`에는 증권사 `app-key`·`app-secret`, 실전 계좌번호, 운영자 비밀번호 해시가 들어 있다(config-keys.md:59-70). D-019 3장은 Python 수집기가 "증권사 API 키·토큰·계좌 정보를 다루지 않는다"고 정한다(docs/decisions/D-019-python-collector.md 3장). Python 수집기가 KRX·OpenDART 키를 읽으려고 `secrets.json`을 열면 증권사 키와 계좌번호가 Python 프로세스 메모리에 함께 올라온다. `scripts/ci/check-python-collectors.sh`는 키 이름 대입만 보므로 파일 통째 읽기는 잡지 못한다.
   - `[사실]` Java 로더는 모르는 키가 있으면 그 파일 전체를 쓰지 않는다(ConfigJson.java:27, config-keys.md:8). 실패 시나리오: 사용자가 배치표 1번 지시대로 지금 `"krx-openapi": {"auth-key": ...}`를 `secrets.json`에 넣으면, 실전 엔진·게이트웨이·실험실·감시 4개 프로세스가 다음 기동에서 `secrets.json` 없이 뜬다. 증권사 키가 빠져 시세 수신이 멈추고(BRK NOT_CONFIGURED), 실전 계좌번호·운영자 해시도 빠진다. 안전 쪽 실패이지만 6단계 실시간 모의·녹화가 조용히 멈춘다.
   - 이 문제의 뿌리는 이 PR 전부터 있었다(D-043·MD-11의 OpenDART 키 `secrets.json` 등록). 이 PR은 KRX 키를 더하고 "선행: 없음, 사용자 작업: … `secrets.json` 등록"으로 지금 할 일로 올렸다.
   - 고칠 것: 배치표 1번(autotrade-spec.md:322), 조사 문서 3장 조건 1(:112)·5장(:134)·6장(:143), MD-11 "비밀 저장소(CFG-03)"의 해석을 "수집기 전용 비밀 파일(Java 설정 로더가 읽지 않고, 증권사 키를 담지 않는 별도 파일. 이름·위치·권한은 architect 설정 ADR)"로 바꾸고, ADR이 나오기 전에는 사용자에게 `secrets.json` 등록을 지시하지 않는다. architect 변경 제안(spec:404)도 "secrets.json 항목 추가"가 아니라 "수집기 비밀값 위치 결정"으로 고친다. D-043의 OpenDART 문구와 겹치면 D-050에 이 정정을 함께 적는다.

2. **(수정 필요) D-038 되돌릴 조건이 발동했는데 평가가 없다.**
   - `[사실]` D-038(docs/decisions/README.md:43): "되돌릴 조건: Q-007이 2단계 재판정 전에 풀리면 수집기를 2단계에 넣는다." 2단계는 아직 완료 판정 전이다(autotrade-spec.md:309 "QA 완료 판정 수정 필요", 결정 로그에 2단계 완료 기록 없음). 이 PR이 Q-007을 닫았으므로 조건이 충족됐다.
   - 그런데 배치표 1번은 여전히 "2단계 후속"이고(spec:322) D-050·변경 이력 어디에도 D-038 조건을 검토한 흔적이 없다.
   - 고칠 것: 수집기를 2단계 범위에 넣을지, 넣지 않을지(예: 키 발급·이용 승인 대기라 2단계 재판정을 막지 않으려고 후속 유지)를 D-050에 한 줄로 적고, 넣지 않으면 D-038을 뒤집는 것이므로 지침 2장대로 새 번호 또는 D-050 안에 명시한다.

3. **(수정 필요) D-019의 Python 수집기 채택 전제가 바뀐 것을 평가하지 않았다.**
   - `[사실]` D-019는 대안 A(Java만)를 "국내 공개 시세·상장폐지 이력 라이브러리가 부족해 직접 구현 부담"으로 버렸고, 되돌릴 조건으로 "Java만으로 같은 데이터를 같은 품질로 확보할 수 있게 되면 Python 수집기를 걷어낸다"를 뒀다(D-019 대안 비교·되돌릴 조건). 이 PR은 Python 공개 라이브러리(pykrx·FinanceDataReader)를 모두 버리고 두 출처를 공식 REST API(KRX Open API, OpenDART)로 정했다. 둘 다 Java HTTP 클라이언트로 같은 데이터를 받을 수 있다 `[추론]`. 조사 문서 5장도 수정 계수 대조는 "Java 쪽 도구로"라고 해 수집 경로가 두 언어로 갈린다(:132).
   - 발견 1의 비밀값 문제도 Java 수집 명령이면 기존 설정 체계(`secrets.json` 스키마 확장, 🔒 마스킹)로 풀린다. 반대로 Java 수집 명령은 시세 게이트웨이·실전 엔진과 같은 비밀값 파일을 읽게 되므로 프로세스 격리 쪽 부담이 있다. 어느 쪽이든 판단이 필요하다.
   - 고칠 것: D-050 또는 조사 문서에 "D-019 되돌릴 조건 검토: Python 유지 / Java 일괄 명령으로 전환" 대안 2개를 비교한 결과(또는 architect ADR 과제로 넘김)를 적는다. 이 PR에서 결정까지 할 필요는 없고, 미확정으로 남기면 Q 번호를 단다.

4. **(수정 필요) 2차 사본만 확인한 법 조문을 `[사실]`로 표기했다.**
   - 조사 문서 :70(공공데이터법 제3조 ④)과 :107(저작권법 제7조 5호)은 law.go.kr 원문을 못 보고 nepla.ai 사본으로 확인했다고 스스로 적으면서 `[사실]`로 달았다. 지침 9장은 "2차 자료만 있으면 `[가정]`으로 두고 확인 방법을 적는다"고 한다. D-050 ③도 이 조문을 근거로 든다(docs/decisions/README.md:54).
   - Q-026 결론(로컬 저장 허용)은 1차 출처인 OpenDART 약관에 저장 금지 조항이 없다는 점으로도 서므로 결론은 유지해도 되지만, 표기는 `[가정]`(확인 방법: law.go.kr 원문 대조)으로 고친다.

5. (권고) 잔존 참조. 이 PR 뒤에도 "Q-007에서 확인/이후"가 남는다: `collectors/python/README.md:9`, `autotrade-marketdata/src/main/java/com/autotrade/marketdata/daily/SnapshotManifest.java:32`(주석), `docs/adr/ADR-003-market-data.md:104,169`, `docs/decisions/D-041-selection-x-signal-combinations.md:85`. ADR-003은 변경 제안으로 남긴 것이 맞다(지침 3장). 나머지는 data-engineer 소유 README와 주석이라 같은 PR에서 고쳐도 되고, 아니면 기획서 15장 "이번 판의 영향 역할" 표에 넣는다.

6. (권고) 호출량 계산(조사 문서 :38)은 일별매매정보만 셌다. 종목기본정보·코넥스(2013-07 이후)를 날짜별로 따로 부르면 합계가 달라진다. 수집기 설계 때 API별 호출 수를 표로 다시 계산하고, 일일 상한(기본 8,000) 초과 시 다음 날 이어 받는 재개 지점을 manifest에 남기는 설계를 권한다.

7. 확인 못 한 것(판정 근거에는 쓰지 않음): KRX Open API 약관(2025-12-26 시행)·KRX 정보데이터시스템 약관(2026-08-29 시행)·OpenDART 약관 인용 문구는 이 환경에서 원문 접근이 막혀 대조하지 못했다. 독립 QA에서 원문 대조를 권한다.

## 판정
수정 필요(B1 빌드 직접 통과 확인 — 환경 회복 뒤 재실행, B3·발견 1 수집기 비밀값을 `secrets.json`에 등록하라는 지시 철회와 위치 결정을 architect ADR 과제로, 발견 2 D-038 되돌릴 조건 평가 기록, 발견 3 D-019 전제 변화 평가 기록, 발견 4 2차 사본 근거 `[가정]` 표기)

## 재검토 2026-10-10
- 대상: PR #36 / 커밋 ebd2ce5(85d187a 위 수정 커밋) / main(f2e39b3) 대비 diff (`git diff origin/main...HEAD`: 7개 파일. 앞 검토의 3개에 `autotrade-marketdata/.../SnapshotManifest.java`(주석 1줄), `collectors/python/README.md`, `docs/adr/ADR-003-market-data.md`, `docs/reviews/pr-36/architect.md`(앞 검토 원문)가 더해짐)
- 점검표: B
- 직접 실행한 것:
  - 앞 검토 원문 보존 확인: `git show HEAD:docs/reviews/pr-36/architect.md`와 검토자가 쓴 파일의 md5가 같다(ff6739f7…). 메인이 원문을 고치지 않았다.
  - `git diff 85d187a HEAD --word-diff`로 수정 커밋의 바뀐 줄 전부 대조. 남은 `secrets.json` 언급을 `grep -n "secrets.json"`으로 확인.
  - 원격 브랜치 전체에서 D-050·Q-036·Q-037 사용처 대조: 이 브랜치만 쓴다(충돌 없음).
  - `./gradlew build` 1회(ebd2ce5) → EXIT 1, 앞 검토와 같은 Maven Central 429(`kotlin-compiler-embeddable:2.0.21` POM). 외부 init 스크립트로 Maven Central 미러를 쓰는 빌드를 시도했으나 1회는 Gradle 설정 충돌로 즉시 실패했고, 고친 시도는 이 세션의 권한 정책이 막았다. 빌드는 끝내 직접 통과시키지 못했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | ebd2ce5에서 `./gradlew build` EXIT 1(Maven Central 429, `:build-logic` 구성 단계). 이번 diff에는 Java 파일 1개(SnapshotManifest.java:32 Javadoc 주석 한 줄)가 들어와 코드 변경이 생겼다. 주석만 바뀌어 빌드 결과가 바뀔 가능성은 낮지만 `[추론]`, 직접 통과를 확인하지 못했다. CI 결과는 검토자가 직접 실행한 것이 아니라 판정 근거로 쓰지 않는다 |
| B2 | 통과 | 의존·패키지 변경 없음. SnapshotManifest.java 변경은 주석뿐(`git diff 85d187a HEAD -- autotrade-marketdata`: 32행 Javadoc만) |
| B3 | 통과(후속 과제 있음) | 수집기 키를 `secrets.json`에 넣으라는 지시를 철회했다: spec:322 배치표 1번("키는 `secrets.json`에 넣지 않음, D-050 ⑤"), MD-11("수집기 전용 비밀 파일, `secrets.json` 아님"), 조사 문서 3장 조건 1·5장 사용자 작업·6장, D-050 ⑤. 전용 파일의 위치·형식은 Q-037과 함께 배치표 1번 시작 전 architect ADR 과제로 올렸다(docs/decisions/README.md Q-037, spec 15장). 이 PR은 설정 계약을 바꾸지 않는다. 다만 아래 점검표 밖 R1의 남은 한 줄은 고쳐야 한다 |
| B4 | 해당 없음 | 결정론 관련 코드·규칙 변경 없음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 해당 없음 | 동작 변경 없음(주석·문서). 테스트 삭제·건너뛰기 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·한도·장애 대응 변경 없음 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음 |

### 앞 검토 지적 처리
| 앞 지적 | 처리 | 판단 |
|---|---|---|
| 발견 1 `secrets.json` 등록 지시 | D-050 ⑤, 조사 7.1장, 배치표 1번, MD-11, 영향 역할 표(architect 과제) 수정 | 해결. 단 R1의 남은 한 줄 |
| 발견 2 D-038 되돌릴 조건 | D-050 ⑥, 조사 7.3장: 조건은 걸리지만 2단계에 넣지 않음, 이유(키 발급·승인·Q-037 선행, 결과는 6단계 전에만 필요), 버린 대안, 되돌릴 조건(2단계 QA 재판정이 일봉 실데이터를 요구하면) | 해결. D-038을 그대로 따르지 않는 결정을 D-050 안에 명시했고 "추천안 채택(자율)" 표시가 붙어 있다 |
| 발견 3 D-019 전제 변화 | 조사 7.2장 대안 A/B 비교, data-engineer 추천 B(Java 일괄 명령), Q-037로 architect ADR 과제 | 해결(미확정으로 남김, Q 번호 있음) |
| 발견 4 2차 사본 `[사실]` | 조사 :71·:107·:108 `[가정]`으로, 3장 판단에 "1차 출처인 OpenDART 약관만으로도 결론이 선다", D-050 ③에 "법 조문은 2차 사본만 봐서 `[가정]`", manifest `license` 예시에서 법 조문 제거 | 해결 |
| 권고 5 잔존 참조 | SnapshotManifest.java:32, collectors/python/README.md:9, ADR-003:104·169 갱신 | 해결. D-041:47·85는 확정 결정 원문이라 D-050 ⑤가 대체를 명시한 것으로 충분 |
| 권고 6 호출량 | 조사 :38 API별 재계산(약 23,000회, 하루 8,000회면 사흘) | 해결 |

### 점검표 밖 발견
- R1 (수정 필요) 조사 문서 docs/research/q-007-q-026-collector-licenses.md:100(OpenDART 약관 제19조 ② 행)이 여전히 "키는 `secrets.json`만"이라고 쓴다. 같은 문서 3장 조건 1·7.1장, D-050 ⑤와 정반대다. data-engineer가 수집기를 구현할 때 이 표를 보면 앞 검토 발견 1의 실패(모르는 키로 4개 프로세스가 `secrets.json` 없이 기동, 증권사 키가 Python에 노출)가 다시 생길 수 있다. "키는 수집기 전용 비밀 파일만(7.1장)"으로 고친다.
- R2 (참고) ADR-003은 architect 소유 문서인데 이 PR이 직접 고쳤다(:104, :169, "Q-007 이후" → "D-050·Q-037 뒤"). 문구만 바꿨고 내용은 앞 검토가 요청한 갱신과 같아 architect로서 받아들인다. 계약 변경은 없다.
- R3 (참고) Q-037 추천(Java 일괄 명령)으로 가면, 일괄 명령이 Java 설정 로더로 `secrets.json`을 읽을지가 다시 문제가 된다. 실전 엔진과 같은 비밀값 파일을 읽는 별도 프로세스를 하나 더 만드는 셈이기 때문이다. ADR에서 "수집기 전용 비밀 파일 + 증권사 키 비로딩"을 언어와 관계없이 지키는 쪽으로 정할 예정이다. 이 PR의 판정과는 관계없다.

### 판정
수정 필요(B1 빌드 직접 통과 확인 — Maven Central 429가 풀린 뒤 검토자 재실행, R1 조사 문서 :100의 "키는 `secrets.json`만" 문구 수정)
