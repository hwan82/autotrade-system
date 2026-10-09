# architect 검토: 시세 조사: 토스 호출 한도 차이와 IP 허용 목록 조건 기록
- 대상: PR #29 / 커밋 8f855fa864733910f60448704b24d25fee80b1ec / main(e69d1815) 대비 diff (`git diff origin/main...HEAD`)
- 범위: diff 전체. 바뀐 파일은 `docs/research/market-data-limits.md` 하나(+2행, 5장 "위험과 남은 확인 항목" 표에 행 2개 추가). 코드·설정·스키마·테스트 변경 없음
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: 처음 여러 번은 Maven Central이 HTTP 429(Too Many Requests)로 응답해 의존성 해석 단계에서 실패(`autotrade-marketdata:compileJava`, jackson-bom 2.21.4 등). 저장소 문제가 아니라 외부 저장소 레이트리밋이다. 재시도 12회째에 `BUILD SUCCESSFUL`
  - 캐시 재사용을 배제하려고 `./gradlew build --rerun-tasks --offline` 다시 실행: `BUILD SUCCESSFUL in 1m 5s`, 104 tasks 모두 실행. 테스트 결과 XML 합계 522건, 실패 0, 오류 0, 건너뜀 0
  - 운영·테스트 코드에서 `@Disabled`, `assumeTrue` 검색: 없음
  - diff에서 비밀값 형태 검색(key/secret/token/8자리 이상 숫자): 낱말 "Client ID·Secret", "Client Secret"만 나오고 값은 없음. 로컬에 gitleaks가 없어 CI 스캔은 돌리지 못했다(B8은 수동 확인 근거로 판정)
  - 토스 T1 문서(https://openapi.tossinvest.com/openapi-docs/overview.md) 재확인 시도: 이 환경 프록시가 CONNECT 403으로 막음. PR 본문의 "T1 재확인 불가"와 같은 상태다. 15회/10회 중 어느 쪽이 맞는지는 이 검토에서 판정하지 않는다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행 기록. `./gradlew build --rerun-tasks --offline` 104 tasks 실행, 522 tests 0 fail 0 skip. 첫 실패들은 Maven Central 429로 이 PR과 무관(diff는 문서 1개) |
| B2 | 해당 없음 | 코드·빌드 스크립트 변경 없음(`git diff --name-only` = `docs/research/market-data-limits.md`). 빌드에 포함된 ArchUnit·금지 의존 검사도 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API를 바꾸지 않았다. 다만 새 행이 기존 계약 문서 수치와 다른 운용값(토스 시세 초당 10회)을 지시형으로 적었다 → 아래 F2 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든 테스트 포함 전체 테스트 통과(B1) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음. 테스트 삭제·비활성화 없음(diff에 테스트 파일 없음, `@Disabled`/`assumeTrue` 없음) |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·리스크 한도·장애 대응 코드나 장애표(`broker-and-fault-design.md` 6장)를 바꾸지 않았다. 단, 새 행이 장애 처리 방식을 제안하므로 그 제안을 장애표나 코드에 반영할 때는 risk-ops 검토가 필요하다(F1) |
| B8 | 통과 | diff에 키·계좌번호·토큰 값 없음(수동 검색). 비밀값은 `secrets.json`에만 둔다는 문구는 `docs/config/config-keys.md:26-27`의 🔒 키 규칙과 맞다 |

## 점검표 밖 발견

- F1 [수정 필요] 인증 실패 재시도 문구가 ADR-003의 확정 규칙과 엇갈린다.
  - 새 행(`docs/research/market-data-limits.md:144`)은 IP 미등록 시 "장애표에서는 인증 실패로 처리하고 자동 재시도로 반복 호출하지 않음"이라고 적었다.
  - 이미 정해진 규칙은 ADR-003 토큰 절(`docs/adr/ADR-003-market-data.md:33`)과 구현(`autotrade-marketdata/.../live/StreamingMarketDataSource.java:45-50`, `AUTH_RETRY_MIN` 60초, `AUTH_FAILURE_LIMIT` 3, `AUTH_RETRY_MAX` 30분)이다. 인증 실패 뒤 60초 하한, 연속 3회면 DEGRADED, 이후 2분부터 30분까지 두 배씩 늘리며 **재시도는 계속한다**(D-028). 장애표 7번(`docs/team/broker-and-fault-design.md:165`)도 같은 처리다.
  - "자동 재시도로 반복 호출하지 않음"은 "재시도를 아예 하지 않는다"로 읽힐 수 있다. 그렇게 구현하면 IP를 다시 등록해도 재기동 전까지 토스가 복구되지 않고, 토스 보호 폴링(장애표 2번, `market-data.toss-polling-scope`)이 걸린 계좌의 보호가 길게 끊긴다. 장애 대응 규칙 변경이므로 연구 문서에서 지시할 내용이 아니다.
  - 고칠 방법: "기존 인증 실패 규칙(ADR-003 토큰 절: 60초 하한, 연속 3회 DEGRADED, 2분~30분 백오프)대로 처리한다. IP 미등록이 원인으로 보이면 운영 화면에 'IP 미등록 의심'을 띄운다" 정도로 바꾸고, IP 변경 시나리오를 장애표 새 행 후보로 risk-ops에 넘기는 변경 제안(4장 표)으로 둔다. 토스 계좌를 실전에 쓰면 IP 변경은 주문·조회까지 막으므로(실전 엔진도 같은 키 조건) 장애 테스트 전제와의 관계는 risk-ops가 판단해야 한다.

- F2 [수정 필요] 토스 시세 초당 10회 시작값이 ADR-003 표와 맞지 않고, 변경 제안으로 남지 않았다.
  - 새 행(`:143`)은 "토스 클라이언트는 초당 10회로 시작하고 응답 헤더 값을 따른다"고 굵게 적었다. 반면 ADR-003 소스 한도 표(`docs/adr/ADR-003-market-data.md:40`)와 이 문서 1장(`docs/research/market-data-limits.md:21`)은 토스 REST를 "초당 15회"로 적고 있다.
  - 근거 엇갈림을 기록한 것과 보수적 시작값 제안 자체는 타당하다(15를 믿었다 429가 나는 쪽이 더 나쁜 실패이고, `X-RateLimit-*`를 따르는 설계는 1장 T1 근거와 맞다). 문제는 운용 기본값이 ADR과 다른데 담당 역할로 넘어가는 경로가 없다는 점이다. 프로젝트 지침 3장대로 다른 역할의 계약은 변경 제안으로 남겨야 한다.
  - 고칠 방법: 4장 "설계 반영 제안" 표에 "ADR-003 소스 한도 표 토스 REST 값: 공식 15회/초, 사용자 전달 약 10회/초로 엇갈림. 토스 클라이언트 초기 토큰 버킷은 10회/초, 헤더 값 우선 | architect" 행을 추가한다. 이 값이 들어갈 설정 키는 같은 문서 `:148`의 `broker.<id>.rest.<group>.tps` 제안과 묶어 토스 클라이언트 PR에서 ADR로 정한다. 행의 "그래서 … 시작한다"는 "제안한다"로 바꿔 [추론] 표시와 어조를 맞춘다.

- F3 [권고, 판정에 영향 없음] 용어 대응: 토스 화면의 "Client ID·Secret"이 설정 키 `brokers.toss.app-key`·`brokers.toss.app-secret`(`docs/config/config-keys.md:26-27`)에 대응한다는 한 줄을 붙이면 키 입력 시 혼동이 줄어든다.

- F4 [참고] 출처 등급: 사용자 전달 요약은 "공식 소개 페이지·기사·블로그 종합"이라 1차 근거(T1)보다 등급이 낮다. 행은 이를 구분해 적었고, 확인 방법(Rate Limits 표 재확인, 2단계 `X-RateLimit-*` 실측)도 있어 9장 근거 기준은 충족한다. "Client Secret은 발급 화면에서만 복사 가능"도 같은 사용자 전달 근거로 "공식 문서 원문 미확인"에 묶여 있다.

## 판정
수정 필요(F1, F2)

## 재검토 2026-10-09 (e255afe)
- 대상: PR #29 / 커밋 e255afe348f48ae2f58cdde626a4a29d906817fb / main(e69d1815) 대비 diff 전체(`git diff origin/main...HEAD`)
- 범위: 바뀐 파일은 `docs/research/market-data-limits.md`(4장 변경 제안 표 +2행, 5장 2행 수정)와 `docs/reviews/pr-29/architect.md`(첫 검토 원문 추가). 코드·설정·스키마·테스트 변경 없음
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --offline`(의존성은 첫 검토 때 캐시됨): `BUILD SUCCESSFUL in 1m 6s`, 104 tasks 모두 실행, 테스트 522건 실패 0, 오류 0, 건너뜀 0
  - `git diff --quiet e255afe -- docs/reviews/pr-29/architect.md`: 커밋된 첫 검토 원문이 검토자가 쓴 내용과 같다(메인이 고치지 않음)
  - diff에서 비밀값 형태 검색: 값 없음(설정 키 이름 `brokers.toss.app-key`·`app-secret`만)
  - `StreamingMarketDataSource.java:310-330, 364-371` 대조: 인증 실패 횟수는 인증이 확인되면 0으로 풀리고(`:321`), 거부 뒤 간격은 60초×2^n, 상한 30분(`:367-371`). 수정된 5장 문구와 맞다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행 기록(e255afe에서 104 tasks 실행, 522 tests 0 fail 0 skip) |
| B2 | 해당 없음 | 코드·빌드 스크립트 변경 없음. ArchUnit·금지 의존 검사 포함 빌드 통과 |
| B3 | 해당 없음 | 계약 변경 없음. 15회→10회 시작값은 4장 표(`docs/research/market-data-limits.md:128`)에 architect 변경 제안으로 넘겼고, 5장(`:145`)은 "제안한다"로 바뀌었다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든 테스트 포함 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음. 테스트 파일 변경 없음 |
| B7 | 해당 없음 | 장애표·실전 경로 코드 변경 없음. IP 변경 시나리오와 안내 화면은 4장 표(`:129`)에 risk-ops(ui-dev) 변경 제안으로 넘겼다. 반영 PR이 장애표를 바꾸면 그때 risk-ops 검토 대상 |
| B8 | 통과 | diff에 비밀값 없음. Client ID·Secret ↔ `brokers.toss.app-key`·`app-secret` 대응과 `secrets.json` 보관은 `docs/config/config-keys.md:26-27`과 맞다 |

### 첫 검토 지적 처리
- F1 해결: `:146`이 확정된 인증 실패 규칙(ADR-003 2장, D-028, 장애표 7번: 60초 하한, 연속 3회 DEGRADED, 2~30분 간격 재시도 계속)을 그대로 따른다고 고쳤다. 장애표 반영과 안내 화면은 risk-ops 변경 제안(`:129`)으로 넘겼다. "ADR-003 2장"은 실제로 2.1절(`docs/adr/ADR-003-market-data.md:33`)이라 참조가 맞다
- F2 해결: 4장에 architect 행(`:128`) 추가, 5장 어조를 "제안한다"로 바꾸고 확정 수치(ADR-003 2장 표, 1장 표 15회)는 그대로 두었다
- F3 반영: `:146`에 설정 키 대응을 명시했다

### 새 발견
- R1 [권고, 판정에 영향 없음] `:146`의 "그래서 IP를 다시 등록하면 재기동 없이 복구된다"는 아직 없는 토스 클라이언트에 대한 단정이다. 현재 코드에서 인증 실패 횟수는 "인증 확인" 때만 풀리고(`StreamingMarketDataSource.java:321`), ADR-003 2.1절(`:33`)은 인증 확인을 첫 시세로만 하는 한국투자가 배분에서 빠지면 스스로 풀리지 않아 재기동이 필요하다고 적었다(risk-ops 검토 N1). 토스도 첫 시세로만 인증을 확인하게 만들면 같은 문제가 생긴다. 이 셀은 `[추론]`으로 표시되어 있고 4장으로 architect·risk-ops에 넘어가므로 막지는 않는다. 다음에 문서를 고칠 때 "토스 클라이언트가 토큰 발급·연결 응답으로 인증을 확인하는 경우"라는 조건을 붙이길 권한다. architect는 토스 클라이언트 PR에서 이 조건을 ADR 요건으로 넣는다

## 판정 (재검토 e255afe)
승인
