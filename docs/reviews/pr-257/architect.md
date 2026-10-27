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
