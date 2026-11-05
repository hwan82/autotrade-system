# architect 검토: 실전 엔진 시세 통로 G1: 엔진 전달 서버·받는 쪽·줄 형식과 경계 규칙(D-148) [실전 관련]
- 대상: PR #257 / 커밋 f9c09801(코드), ba5209c3(결정 번호 D-147 → D-148, 문서 3개만) / main 대비 diff(merge-base f085478f, 19개 파일 +3226)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(f9c09801): **BUILD SUCCESSFUL in 8m 15s**. ba5209c3은 `git diff f9c09801 ba5209c3`로 문서 3개(ADR-016 8장 링크, D-148 파일 이름·제목 줄, 결정 README 행)만 바뀐 것을 확인해 다시 돌리지 않음.
  - 테스트 결과(XML): `EngineFeedProtocolTest` 5, `EngineFeedServerTest` 18, `EngineFeedClientServerTest` 5, `EngineFeedLatencyTest` 1, `EngineFeedBoundaryTest` 3 모두 통과·건너뜀 0. `autotrade-architecture-test` 전체 50건 통과·건너뜀 0.
  - 지연 시험 출력(이 컨테이너): publish p50 533ns, p99.9 31.3µs(기준 50µs), 최대 3.0ms / 콜백 → 엔진 해석 p50 442µs, p99 3.8ms(기준 10ms), 최대 18.2ms(기준 200ms), 25,600건.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL |
| B2 | 통과 | 새 코드는 기존 `autotrade-marketdata`의 `com.autotrade.marketdata.enginefeed`·`.server` 패키지에만 있음(새 모듈 없음, `ModuleOwnershipTest` 통과). 의존은 domain·`marketdata.ConnectionState`·`recording.RecordingCodec`·Jackson뿐(EngineFeedProtocol.java:3-24, EngineFeedServer.java:3-44). 규칙 추가는 새 보호 테스트 `EngineFeedBoundaryTest`(5개 규칙, 위반 고정 예제 4개로 실제로 잡는지와 실제 클래스가 통과하는지 함께 확인, EngineFeedBoundaryTest.java:101-132). 근거 ADR은 ADR-016 2.2·5장, 허용 목록으로 넓힌 이유는 D-148 결정 8. 기존 `ArchitectureTest`는 바꾸지 않음 |
| B3 | 통과 | 새 계약(엔진 전달 줄 형식 v1, `EngineFeedServer.publish/sourceState/assignment/InterestSink`, `EngineFeedClient.Listener`)은 ADR-016(D-143) 2.2~2.5에 먼저 설계돼 있고, 2.3·2.5와 다르게 정한 것(N6 `sources` 객체 모양, N7 생존 줄 버림, 깨우지 않는 큐)을 ADR-016 8장 구현 기록과 D-148에 적음. 설정 키·DB 스키마·REST API 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 서버는 `Clock`을 주입받아 줄의 `at`에 쓰고(EngineFeedServer.java:106,393,430), `System.nanoTime`은 멈춤·로그 간격 측정에만 씀. 난수 없음. 골든 테스트는 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액을 다루지 않음(이벤트 줄은 기존 `RecordingCodec` 그대로, 시험의 가격은 `BigDecimal`) |
| B6 | 통과 | 새 동작마다 시험이 있음: 줄 형식 되읽기·거부 이유(EngineFeedProtocolTest), 단일 받는 쪽·전체 교체·rev 무시·rejected·멈춤 끊기·종목별 공백·생존 줄 버림/상태 줄 재전송·유지 60초·만료·인사 시간 초과·publish 예외 흡수·소켓 재생성·폴더 재생성·남의 파일 비탈취·중복 실행 거부·순번(EngineFeedServerTest 18건), 재접속 rev 1 재전송·상한 초과 미전송(EngineFeedClientServerTest), 지연 기준(EngineFeedLatencyTest). 지운 시험·`@Disabled`·assume 없음(`grep` 확인) |
| B7 | risk-ops 검토 필요 | 보호 경로 `autotrade-marketdata/*`와 보호 테스트 `autotrade-architecture-test/*`(새 `EngineFeedBoundaryTest`·고정 예제)를 바꿈(scripts/ci/protected-paths.txt). 실전 엔진의 시세 입력 통로이므로 A 점검표 risk-ops 검토, 독립 QA, 사용자 직접 머지 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 로그는 소켓 파일 이름·예외 클래스 이름·부팅 표지만 남기고(EngineFeedListener.java:327,349, EngineFeedServer.java:571), 줄 형식 오류는 받은 원문을 메시지에 넣지 않음(EngineFeedProtocol.java:164-176) |

## 점검표 밖 발견
판정을 막지 않는 것들이다. G2·G4에서 처리하면 된다.

- N1(받는 쪽 줄 상한, G4 전 권고): `EngineFeedClient.receive`는 `BufferedReader.readLine()`으로 줄 전체를 메모리에 읽은 **뒤** `line.length() > MAX_LINE`을 본다(EngineFeedClient.java:199-226). 서버 쪽 `LineReader`처럼 읽는 동안 끊지 않아서, 줄바꿈 없이 계속 보내는 상대(ADR-016 4장의 같은 사용자 거짓 서버, 또는 게이트웨이 결함)에 실전 엔진 힙이 늘 수 있다. 또 비교가 바이트가 아니라 UTF-16 글자 수다. 위협 모델상 같은 사용자 프로세스는 엔진을 다른 방법으로도 죽일 수 있어 막는 결함은 아니지만, 엔진(G4)에 연결하기 전에 바이트 상한 읽기로 바꾸기를 권한다.
- N2(G4 해석 주의): 새 연결의 첫 상태 줄(rev 0)은 앞 연결에서 유지 중인 관심(`applied`)의 `served`·`unserved`를 그대로 싣는다(EngineFeedServer.java:391-393, 시험 `끊긴_뒤_유지_시간_안에_다시_붙어_rev_1을_보내면_받고_그_전_상태_줄은_rev_0이다`). 엔진은 rev 0 상태 줄의 `served`를 자기 보호 대상이 덮였다는 근거로 쓰면 안 된다. G4 받는 쪽 판단에서 "자기가 보낸 rev와 같은 상태 줄만 덮음 판단에 씀"을 규칙으로 적기를 권한다.
- N3(줄 형식 엄격성): `EngineFeedProtocol.read`는 기본 `ObjectMapper`라 한 줄 안 첫 JSON 객체 뒤의 꼬리 토큰과 중복 키를 받아들인다(EngineFeedProtocol.java:549-559). 크기·키 수 검사가 있어 실제 위험은 작다. `FAIL_ON_TRAILING_TOKENS`·`STRICT_DUPLICATE_DETECTION`을 켜면 형식 v1의 "한 줄에 JSON 하나"가 코드로 굳는다.
- N4(Windows 가정): 파일 키가 없는 파일 시스템에서는 닫을 때 그 자리의 파일을 남의 것인지 보지 않고 지운다(EngineFeedListener.java:413-414). D-148 결정 9의 `[가정]`과 같으며 7단계 절차서 I1에서 확인할 항목에 넣어 두면 된다.
- N5(CI 지연 시험): `EngineFeedLatencyTest`는 절대 시간 기준을 CI에서 단정한다. 이 컨테이너에선 여유가 있었지만(p99.9 31µs/50µs) 러너 부하로 흔들릴 수 있다. D-148 위험 2번대로 넘으면 느슨하게 고치지 말고 G5 근거로 다루는 것에 동의한다.
- N6(사소): EngineFeedServer.java:607-608에 Javadoc이 두 번 겹쳐 있다.

## 판정
승인

## 재검토 2026-11-02
- 대상: PR #257 / 헤드 `c2530f00`(독립 QA 수정 필요 반영) / main 대비 diff 전체(merge-base = main `17193571`, 24개 파일 +3642). 앞 검토 뒤 바뀐 것: `ce8650c4`(main 합치기, 결정 README D-147·D-148 행 함께 둠), `c2530f00`(운영 2파일·시험 2파일·보호 테스트 1파일과 새 위반 예제 1개·ADR-016 8장·D-148 결정 10~15·결정 README)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(헤드 `c2530f00`): **BUILD SUCCESSFUL in 5m 14s**(미러 init 스크립트 있는 환경). 시험 결과 XML 합계 2,111건, 실패·오류·건너뜀 0. `EngineFeedServerTest` 21, `EngineFeedClientServerTest` 7, `EngineFeedProtocolTest` 5, `EngineFeedLatencyTest` 1, `EngineFeedBoundaryTest` 3 모두 통과.
  - 지연 시험 출력: `publish p50=298ns p99=1200ns p99.9=4411ns 최대=34825ns, 콜백→해석 p50=344µs p99=908µs 최대=6385µs (25600건)`(기준 안).
  - 발견 1 회귀 확인(돌연변이): `EngineFeedServer.slots`를 고치기 전처럼 늘 `new Slot()`으로 바꾸고 `:autotrade-marketdata:test --tests '*EngineFeedServerTest*'` → `넘침_뒤_관심이_바뀌어도_남는_종목은_다시_읽을_때_공백이_먼저_온다` **FAILED**(21건 중 1 실패, "남는 종목 A만 공백을 받는다"). 되돌린 뒤 `git status` 깨끗 확인. 새 시험이 발견 1을 실제로 잡는다.
  - `git merge-tree --write-tree origin/main HEAD`(main `12e88baa`): `docs/qa/pending.md` 충돌(아래 R2).
  - `git diff origin/main...HEAD`에서 `@Disabled`·assume 추가 없음, 키·계좌번호·토큰 없음(grep).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(2,111건, 실패·건너뜀 0) |
| B2 | 통과 | 새 운영 코드는 여전히 `com.autotrade.marketdata.enginefeed`·`.server` 안뿐, 새 의존 없음(`EngineFeedClient`는 `BufferedReader`·`Channels`를 빼고 `ByteArrayOutputStream`·`OptionalLong`만 더함). 규칙 추가 `ENGINE_OPENS_NO_MARKET_STREAM`(EngineFeedBoundaryTest.java:95-100): 실전 엔진 패키지가 `MarketDataSource`(와 구현)에 의존하거나 `BrokerPlugin.marketData(Market)`를 부르면 위반. 근거는 ADR-016 2.1과 D-148 결정 15(대안·되돌릴 조건 있음). 위반 예제 `EngineOpensBrokerStream`으로 위반을 실제로 잡는지와 허용(`pollingQuotes`·`PollingQuoteSource`)을 잡지 않는지 함께 단언(:126-128), 실제 운영 클래스 통과(:106). 엔진은 `autotrade-broker-api`만 의존해(autotrade-app-engine/build.gradle.kts:19-28) 호출의 정적 형이 `BrokerPlugin`이므로 `callMethod(BrokerPlugin.class, …)`로 덮이고, 받은 소스를 쓰는 순간(`subscribe` 등)은 `MarketDataSource` 의존으로도 걸린다. 기존 `ArchitectureTest`·`ModuleOwnershipTest` 무변경 |
| B3 | 통과 | 계약 변경: `EngineFeedClient.interest` 반환 `long` → `OptionalLong`(EngineFeedClient.java:124, 아직 쓰는 곳 없음), 받는 쪽 줄 상한이 글자 수 → 바이트 64KB(:242-283, `EngineFeedProtocol.MAX_LINE`과 같은 값), 상태 줄 `rejected`가 밀려도 보존(EngineFeedServer.java:409-415), 관심 변경 때 남는 종목 넘침 칸 유지(:361). 모두 D-148 결정 10~15와 ADR-016 8장 구현 기록에 적힘. 줄 형식 v1 바이트 모양·설정 키·DB 스키마·REST API 변경 없음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 새 코드에 시스템 시계·난수 없음. 골든 테스트 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 없음(시험의 호가 가격은 `BigDecimal`) |
| B6 | 통과 | 새 동작마다 시험: 발견 1 `넘침_뒤_관심이_바뀌어도_남는_종목은_다시_읽을_때_공백이_먼저_온다`(EngineFeedServerTest.java:365, 위 돌연변이로 실패 확인), Q1 `큐가_차서_밀린_상태_줄도_거부_이유를_싣는다`(:434, 받아들인 뒤 지움까지), Q2 `구독_계획_호출은_한_줄로_서고…`(:420, 늦은 세대 버림만 직접 시험), Q4 빈 값 단언(EngineFeedClientServerTest.java:139), Q5 `게이트웨이_줄이_바이트_상한을_넘으면…`(:223)·`줄이_여러_번에_나눠_와도_한_줄로_모은다`(:246), Q3 위반 예제. 기존 시험 2건 변경(`e.status()` → rev 1 상태 줄 기다리기)은 인사 직후 rev 0 상태 줄과의 시작 경합을 고친 것으로, 단언을 줄이지 않고 기다림을 더 정확히 한 것(약화 아님). 지운 시험·`@Disabled`·assume 없음 |
| B7 | risk-ops 검토 필요 | 보호 경로 `autotrade-marketdata/*`와 보호 테스트 `EngineFeedBoundaryTest`(새 규칙·새 위반 예제)를 다시 바꿈. 앞 risk-ops 원문은 `c2530f00` 전 판정이므로 risk-ops 재검토, 독립 QA 재검토, 사용자 직접 머지 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 로그·예외 메시지는 상한 숫자·예외 클래스 이름뿐(EngineFeedClient.java:273, EngineFeedServer.java:340) |

### 앞 검토 발견 처리 확인
- N1(받는 쪽 줄 상한): 해결. `BoundedLines`가 줄바꿈 전 누적 바이트가 `MAX_LINE`을 넘는 순간 예외(EngineFeedClient.java:272-274), 끝없는 줄에 메모리를 쓰지 않음. 버퍼 경계에서 UTF-8 여러 바이트 글자가 잘려도 바이트로 모은 뒤 디코딩하므로 안전.
- N2(rev 0 상태 줄 해석): G1 API 쪽은 QA Q4(빈 값)로 실수가 어려워짐. G4 규칙은 그대로 남은 과제.
- N3(꼬리 토큰·중복 키), N4(Windows 파일 키), N5(CI 지연 시험): 이번 범위 밖, 앞 판단 그대로.
- N6(Javadoc 겹침): 해결(EngineFeedServer.java 쓰기 스레드 주석 하나).

### 점검표 밖 발견 (판정을 막지 않음)
- M1(G2 주의, 타이머 대기): `expire`는 단일 타이머 스레드(EngineFeedServer.java:110-111, 생존 줄 `tick`도 같은 스레드)에서 돌고 이제 `applyLock`을 기다린다. 만료 판단을 지난 직후 엔진이 다시 붙어 관심을 보내면, 타이머는 연결 읽기 스레드의 `sink.apply`가 끝날 때까지 기다렸다가 자기 호출을 버린다. 그 동안 생존 줄이 밀린다. 지금은 `sink`가 시험용이라 무해하고, 고치기 전에도 타이머가 `sink.apply`를 직접 불렀으므로 새 위험은 "다른 스레드의 호출 하나만큼 더"다. G2에서 구독 계획을 붙일 때 `apply` 소요 시간을 재고, 엔진 3초 끊김 기준에 가까우면 D-148 결정 12의 되돌릴 조건(실행기)으로 옮기기를 권한다.
- M2(G2 시험): Q2의 만료·재접속 경합 재현 시험이 없다는 것은 D-148 검증 절에 스스로 적혀 있다. G2에서 함께 넣는 것에 동의한다.
- M3(사소): `BoundedLines`는 `\r`을 지우지 않고 끝의 줄바꿈 없는 조각을 버린다(BufferedReader와 다름). 서버는 `\n`만 쓰고(EngineFeedServer 쓰기 경로), JSON 해석이 꼬리 공백을 받아들이므로 영향 없음. `EngineOpensBrokerStream.java:8`에 쓰지 않는 `import java.util.Set`이 있다.
- R2(머지 전): 이 PR 뒤에 main이 `12e88baa`(#253)로 나아가 `docs/qa/pending.md`가 충돌한다(표 줄 추가끼리로 보임). 사용자 머지 전에 main을 다시 합치고 CI가 도는지 확인해야 한다.

### 판정
승인
