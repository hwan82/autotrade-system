# risk-ops 검토: 녹화 저장 시험 보강(같은 날 재기동 tmp 비움·sources 불변·비정상 종료 흉내 단언, PR #209 QA Q1·R2·R3)
- 대상: PR #217 / 커밋 c3f3a459680c23d9f98e751eeaa71b5b57e48a67 / main 대비 diff(`git diff origin/main...HEAD`, 4파일 +66 −2)
- 점검표: A (보호 경로 `autotrade-marketdata/*`를 건드려 실전 관련 PR로 취급, `scripts/ci/protected-paths.txt`)
- 직접 실행한 것:
  - `./gradlew build --no-daemon`: 첫 실행은 Maven Central 429(archunit 1.4.1 내려받기 실패)로 실패했고, 다시 실행해 **BUILD SUCCESSFUL**. 시험 XML 집계(건너뜀·실패·오류 모두 0): `:autotrade-marketdata` 163, `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 68.
  - 변이 확인(하나씩 넣고 해당 시험만 돌린 뒤 `git checkout`으로 되돌리고, 작업 트리가 깨끗한지 확인):
    - P12: `DayWriter.java:75` 운영 여는 방법을 `Files.newOutputStream(t, CREATE, APPEND)`로 바꿈 → `같은_날_재기동은_앞_재기동이_남긴_tmp를_비우고_다시_쓴다` **실패**(`IllegalStateException: 녹화 순번이 끊겼습니다: 기대 6, 실제 1`). 잡힘.
    - P4: `RecordingMeta.java:51`에서 `Collections.unmodifiableMap` 감싸기를 지움 → `소스_맵은_고칠_수_없다` **실패**. 잡힘.
    - T2: `JournalWriterTest#crash`·`DayWriterRestartStreamTest#crashedDay`에서 닫은 뒤 사본으로 되돌리는 단계를 지움(= 정상 종료) → crash를 쓰는 JournalWriterTest 3개, crashedDay를 쓰는 DayWriterRestartStreamTest 2개 **실패**. 잡힘.
    - `crashedDay`에서 이벤트만 되돌리지 않음(메타만 되돌림) → DayWriterRestartStreamTest 2개 **실패**. 잡힘.
  - Windows 실행: 확인 못 함(Linux 컨테이너에서만 실행).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 시험 변경이 없다. 바뀐 파일은 `autotrade-marketdata/src/test/.../recording/` 아래 시험 3개와 `docs/decisions/D-132-...md`뿐이다. `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 코드 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·재전송 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 운영 코드 무변경(`DayWriter`·`RecordingMeta`·`JournalWriter`·`MarketEventRecorder`의 main 소스 diff 없음). `autotrade-app-engine`이 `autotrade-marketdata`에 의존하지만(`autotrade-app-engine/build.gradle.kts:20`) 바뀐 것은 시험 소스뿐이라 엔진 런타임 클래스패스에는 영향이 없다. `:autotrade-app-engine:test` 68개 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff 4파일을 직접 읽었고 키·계좌번호·토큰이 없다(`appkey|secret|account` grep 결과 없음). 시험 픽스처는 `KIWOOM-WS`·`KRX_REALTIME`·종목코드·임시 폴더뿐이다 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard 시험, 장애 시험) 변경 없음. 바뀐 시험은 단언 추가뿐이고 지운 단언이나 `@Disabled`가 없다(diff −2줄은 주석 2줄 교체). 고정 값 갱신 없음. 새 시험 3종이 실패해야 할 경우를 실제로 잡는다(위 변이 P12·P4·T2·이벤트만 미복원 모두 실패) |
| A12 | 통과 | 장애 대응표·장애 테스트 8종 관련 코드·문서 변경 없음. 새 시험은 D-119 "tmp 잔존" 상황에서 녹화가 순번 끊김 없이 이어지는 현재 동작을 고정할 뿐이다(`DayWriter.java:75`의 기본 `Files::newOutputStream`이 기존 tmp를 비우고 쓴다, `:96-117`). 대응표 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 미국 시장 경로·`MarketRules` 변경 없음(시험은 KRX·Asia/Seoul) |
| A14 | 통과 | `D-132-recording-meta-sources-order.md` "후속 반영(시험만, 운영 코드 무변경)"의 Q1·R2·R3·N3 설명이 실제 시험(`DayWriterRestartStreamTest.java:186`, `:92-95`, `JournalWriterTest.java:57-60`, `RecordingMetaSourcesOrderTest.java:88`)과 주석 변경에 맞는다. 동작 변경이 없으므로 새 결정이나 기획서 변경은 필요 없다. 결정 로그 표의 D-132 행(`docs/decisions/README.md:138`)과 충돌 없음 |

## 점검표 밖 발견
- N1(참고, 비차단): 새 tmp 시험은 "정상 종료 폴더 + 남은 tmp"만 다룬다. "비정상 종료 폴더 + 남은 tmp + 이미 있는 보관본" 조합은 기존 보관본 시험과 운영 분기(`DayWriter.java:108-115`)에 기댄다. 다만 둘 다 같은 `rewriteToTmp` 경로를 지나므로 P12 변이는 이 시험 하나로 잡힌다. 추가 요구는 아니다.
- N2(참고, 비차단): D-132 후속 문단에 "사용자 PC Windows에서 `./gradlew :autotrade-marketdata:test` 통과 확인은 이 PR 머지 전에 받는다"고 적혀 있다. 이 검토는 Linux에서만 돌렸으므로 그 확인은 사용자 몫으로 남는다(점검표 A 항목은 아니다). 새 시험도 열린 핸들을 남기지 않는 기존 crash 방식을 그대로 쓰므로, Windows에서 파일 바꾸기가 거부되는 상황을 새로 만들지 않는다 `[추론]`.
- N3(참고): 앞 검토 risk-ops-3 N4(다른 핸들이 그날 파일을 열고 있을 때 Windows 재기동 `Files.move`가 거부됨)는 D-132에 이번 범위 밖으로 남는다고 적혀 있다. 운영 성질은 이 PR 전과 같다.

## 판정
승인
