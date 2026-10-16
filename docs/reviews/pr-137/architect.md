# architect 검토: 수집기: KRX 목록 빠진 200 응답 1회 재시도, 오류 문구 표시
- 대상: PR #137 / 커밋 b019340 / main 대비 diff(`git diff origin/main...HEAD`, 커밋 1개, 파일 4개 +44 -2)
- 점검표: B
- 직접 실행한 것: 별도 worktree(origin/claude/project-thread-pb3vv4, b019340)에서 `./gradlew build --no-daemon` → 종료 코드 0. 전 모듈 테스트 1463건, 건너뜀 0, 실패 0, 오류 0. ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest·StopRequestPathTest 실행됨. KrxDailyCollectorTest 4건, KrxOpenApiClientTest 3건 통과.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, 종료 코드 0, 테스트 1463/실패 0 |
| B2 | 통과 | 변경은 `autotrade-app-collector`의 `com.autotrade.app.collector` 안 2개 클래스뿐. 새 의존·새 모듈·ArchUnit 규칙 변경 없음(build.gradle 변경 없음). ArchitectureTest·ModuleOwnershipTest 통과 |
| B3 | 해당 없음 | 공개 인터페이스, 스키마, 설정 키, REST API, 종료 코드 변경 없음. `describe`는 package-private(KrxOpenApiClient.java:68). 예외 메시지 문구만 늘었다 |
| B4 | 통과 | 시계·난수 새 사용 없음. 재시도는 같은 입력에 같은 호출 순서(KrxDailyCollector.java:61-69). 시뮬레이션 코드·골든 테스트 영향 없음(빌드 통과) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | KrxDailyCollectorTest#목록이_빠진_응답은_한_번만_다시_부르고_두_번째도_틀리면_멈춘다(1회 재시도 후 캐시 저장, 2회 모두 틀리면 예외·호출 2회), KrxOpenApiClientTest#모양이_다른_응답은_멈춘다(respCode/respMsg 표시, 비객체 응답) 추가. 기존 테스트 삭제·비활성 없음(diff의 테스트 파일은 추가만) |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응 미변경. 오프라인 수집기 프로세스의 KRX 조회 재시도만 바뀜 |
| B8 | 통과 | 새 메시지는 최상위 필드 이름과 respCode·respMsg(200자 절단)만 담고 URL·헤더·AUTH_KEY를 넣지 않는다(KrxOpenApiClient.java:67-79). 이 IOException은 CollectorMain.java:101-102에서 `redaction.clean`을 거쳐 출력된다. 재호출은 기존 ApiCaller를 그대로 쓰므로 CallBudget 상한·호출 간격·장 시간 가드(ApiCaller.java:48-50)가 그대로 적용된다 |

## 점검표 밖 발견 (차단 아님)
- N1 `Result.fetched`가 재시도 호출을 세지 않는다(KrxDailyCollector.java:70). 주석 "새로 부른 횟수"(36행)와 1건씩 어긋날 수 있다. 실제 호출 수는 CollectorMain.java:120의 `budget.usedToday()`가 함께 출력되므로 운영 판단에는 지장이 없다. 이름을 "새로 받은 응답 수"로 읽으면 맞다(출력 문구도 "새로 받은 응답").
- N2 `catch (IOException first)`가 OutBlock_1 누락뿐 아니라 날짜 불일치·종목 코드 형식 오류·JSON 파싱 실패에도 1회 재호출한다(KrxOpenApiClient.java:52,58,89). 결과는 두 번째도 틀리면 멈추므로 안전하고 추가 비용은 호출 1회다. 첫 예외(`first`)는 버려지고 두 번째 예외만 보인다. 원인 구분이 필요해지면 두 번째 예외에 `addSuppressed(first)`를 고려.
- N3 `describe`가 최상위 필드 이름을 개수 제한 없이 이어 붙인다(KrxOpenApiClient.java:72-73). KRX 응답 최상위 필드는 소수라 실제 위험은 낮다.

## 판정
승인
