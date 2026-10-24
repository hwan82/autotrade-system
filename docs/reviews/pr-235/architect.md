# architect 검토: 7단계 실전 연동 준비 계획(항목 표·구현 묶음·사용자 결정 모음), Q-057~Q-061 등록
- 대상: PR #235 / 브랜치 `claude/project-thread-ousa4v` 커밋 `6cfaf613` / main(`fc28786d`) 대비 diff (`git diff origin/main...HEAD`: `docs/decisions/README.md` +5, `docs/team/stage-7-readiness-plan.md` +262, 코드·설정·보호 경로 변경 없음)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → 성공(EXIT 0). 테스트 결과 XML 합계 1950건, 건너뜀 0, 실패 0, 오류 0. (처음 `--offline`으로 돌렸을 때는 build-logic의 `kotlin-dsl` 플러그인 캐시가 없어 실패, 온라인으로 다시 돌려 통과. PR과 무관한 환경 문제)
  - 2장 항목 표 줄 수·상태 집계 직접 셈: 91줄(2.6 장애 테스트 14줄 포함), 안 됨 59(그중 `안 됨(미확정)` 4: P7·P8·D15·I4), 부분 29(그중 `부분/안 됨` 3: K2·D8·D10), 처리됨 2(K7·W6), 해당 없음 1(T1-T/TK). 1장 2번 숫자와 일치
  - 근거 열의 코드 위치 표본 대조(아래 표 B3 근거), 문서 상대 링크 8개 존재 확인, 새 Q 번호 Q-057~Q-061이 main(최대 Q-056)과 다른 원격 브랜치 결정 로그에 없음 확인, `scripts/ci/protected-paths.txt` 대조(변경 파일 둘 다 보호 경로 아님)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 `./gradlew build` 성공, 테스트 1950건 실패·건너뜀 0 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 변경 없음(diff 2개 파일 모두 `docs/`) |
| B3 | 해당 없음 | 계약(인터페이스·스키마·설정 키·REST) 변경 없음. 계획 문서가 앞으로 필요한 ADR(B0 저장소, D12 시세 전달, 묶음 F 감시 긴급 경로)을 적어 둠. 근거 열 표본 대조는 모두 맞음: `EngineMain.java:220` `BROKER_NOT_CONNECTED` 둘, `KisPlugin.java:93`·`KiwoomPlugin.java:92`·`TossPlugin.java:102` `orderGateway` → `Optional.empty()`, `ControlServer.java:73` 단일 스레드, `:76` `Semaphore slots`(`MAX_CONNECTIONS = 8`, `:56`), `:87-90` `FileChannel.open`(NOFOLLOW 없음)·`tryLock`, `:237` `enqueuedAt`, `:298-305` `isListening` 시간 상한 없음, `:341` `shutdownNow()`, `OperatorAuthenticator.java:60` `synchronized verify`, `OperatorPasswordHash.java:28` `10_000_000`, `SecondConfirmation.java:17` `public record`, `RealTradingGuard.java:309-324` 감사 잠금 밖, `RuntimeConfigHolder.java:46`, `RuntimeConfig.java:24`, `RuntimeConfigStore.java:77`, `RealPathReflectionTest.java:111,114` 빈 집합, `KisStreamProtocol.java:51` `ws://`, `TossRestClient.java:50` 10분, `ControlCommand` 5개 값. 예외 1건은 아래 발견 2 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음. 테스트 삭제·건너뜀 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응 코드와 보호 경로를 바꾸지 않음. 다만 문서 내용(Q-057~Q-061 추천안)은 실전 영향 결정이며, 문서가 스스로 자율 채택하지 않고 사용자 결정으로 두었음(4장 머리, 결정 로그 각 행 "**사용자 결정**"). 실제 결정 기록·구현 PR은 그때 risk-ops 검토 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(문자열 검색으로 확인). 앱키 관련 내용은 "따로 발급" 같은 절차 언급뿐 |

## 점검표 밖 발견
1. **(수정 필요) Q-060 추천안의 계산이 앞뒤가 맞지 않는다.** `stage-7-readiness-plan.md:225` "키움 1순위(200, 체결·호가를 따로 세면 100 `[가정]`)면 watchlist ≤ 80(따로 세면 ≤ 80/2 = 40, …)". 식(세션 한도 − 실전 몫, 실전 몫 최소 20)대로면 종목 기준 200이면 ≤ 180, 체결·호가를 따로 세서 100종목이면 ≤ 80이다. 지금 글은 80이 이미 "따로 센" 값인데 다시 "따로 세면 40"으로 한 번 더 나눴다(D-122 16행도 "100 × 2 = 200"으로 따로 센 경우를 100종목으로 본다). 사용자에게 선택지로 내는 수치라 바로잡아야 한다. 같이 적을 것: 따로 세는 경우(≤ 80)면 D-122의 지금 100종목 watchlist를 줄여야 해서 LIVE 운용·단타 선정 실험에 영향이 있다는 점, 그리고 "하한 보장 위해 실전 몫 먼저"가 무엇을 뜻하는지(실전 몫을 먼저 떼고 남는 것을 watchlist에 준다는 뜻이면 그렇게).
2. (사소) D17 근거 "README 미확정 표 160행"은 실제로 161행이다(`docs/decisions/README.md:161`, main과 이 브랜치 모두 같음, 160행은 Q-046). 줄 번호 대신 "Q-047 행"으로 적으면 이후 행 추가에도 안 틀린다.
3. (참고, 수정 요구 아님) 1장 4번·4장의 "지금 6건"에 Q-016·Q-017·Q-018은 기존 미확정(결정 로그 157~159행)이고 새 등록은 Q-057·Q-058·Q-061이다. 결정 로그 diff와 맞음. 새 Q 번호는 main과 다른 원격 브랜치 결정 로그에서 겹치지 않음을 확인했다.
4. (참고) 2.6 T5를 "첫 실전이 한 증권사뿐이면 해당 없음(계좌 1개)"로 둔 것은 `[추론, risk-ops 제안]`으로 표시돼 있고 결정 기록이 아니다. 실전 활성화 조건(장애 테스트 8종 합격, 지침 4장 8번)의 해석을 바꾸는 일이라 나중에 결정 로그로 확정할 때 사용자 결정·독립 QA 대상이다. 이 PR에서 확정하지 않았으므로 문제는 아님.

## 판정
수정 필요(발견 1: Q-060 추천안 수치 계산 정정과 D-122 100종목 watchlist 영향 명시. 발견 2는 같이 고치기를 권함)
