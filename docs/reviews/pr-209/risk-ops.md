# risk-ops 검토: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험
- 대상: PR #209 / 커밋 d167717 / main(f571a38) 대비 diff(`git diff origin/main...HEAD`, 8개 파일: `RecordingMeta.java`, `DayWriter.java`, 시험 2개(`DayWriterRestartStreamTest` 수정, `RecordingMetaSourcesOrderTest` 새로), D-119 후속 한 줄, D-132 새 결정, 결정 로그 README 1행, 기획서 v14.58)
- 점검표: A (보호 경로 `autotrade-marketdata/*` 변경, `scripts/ci/protected-paths.txt`의 `autotrade-marketdata/*` 줄, 실전 관련 PR로 취급)
- 직접 실행한 것(별도 git worktree `/home/user/rv209-risk`, 저장소 작업 트리는 건드리지 않음):
  - `./gradlew build` → BUILD SUCCESSFUL(4분 45초, 138 tasks). 전체 빌드(컴파일, 테스트, 순수 모듈 의존 검사, ArchUnit) 통과.
  - 결과 XML(실패/오류/건너뜀 모두 0): marketdata 161, risk 55, architecture-test 46, app-engine 68, app-gateway 55, sim 452. `RecordingMetaSourcesOrderTest` 3개, `DayWriterRestartStreamTest` 7개 통과.
  - 변이 시험(두 시험 클래스만 실행, 끝나고 원복, `git status` 깨끗함·원복 뒤 통과 확인):
    - M1: `RecordingMeta` 생성자를 `sources = Map.copyOf(sources)`로 되돌림 → `만든_순서와_상관없이_소스는_이름순이다`, `녹화기가_쓴_meta_json의_소스_줄이_이름순이고_다시_읽어도_같다` 2개 실패.
    - M2(QA P15): `lastAlive`가 정상 종료 시각을 무시(`alive == null ? ended : alive`) → 새 시험 `같은_날_앞_구간_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다`와 기존 날짜 넘김 짝 시험 2개 실패.
    - M3(QA P13): 다시 쓰기 실패 문구에서 폴더를 뺌 → 새 시험 `다시_쓰기_중_쓰기가_실패하면_폴더를_밝힌_문구로_멈추고_원본을_그대로_둔다` 1개만 실패.

## 변경 요약(코드로 확인)
- `RecordingMeta.java:47-51`: sources의 null 키·값을 `NullPointerException`으로 거절한 뒤 `Collections.unmodifiableMap(new TreeMap<>(sources))`로 복사. 예전 `Map.copyOf`와 같이 null 맵·null 키·null 값은 NPE, 결과는 불변. 바뀐 것은 순회 순서(이름순)뿐. 형식 번호·이벤트 파일·SHA 계산 경로 무변경.
- `DayWriter.java:72-84, 137, 158-161`: tmp 여는 방법(`TmpOpener`)을 받는 패키지 전용 생성자 추가. 운영 생성자(`:75`)는 `Files::newOutputStream`을 넘겨 예전과 같은 호출. 운영 호출처 `MarketEventRecorder.java:148`, `JournalWriter.java:74`는 7인자 생성자만 씀(grep). `TmpOpener`는 패키지 전용이라 밖에서 바꿀 수 없음.
- 실전 엔진·감시는 녹화 패키지를 쓰지 않는다: `autotrade-app-engine`이 `autotrade-marketdata`에 의존하지만(`autotrade-app-engine/build.gradle.kts:20`) `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`에 `recording` 참조 0건(grep). `RecordingMeta.sources()` 운영 사용처는 `ReplayCheck.java:375`(이미 `new TreeMap<>`로 정렬해 출력)뿐이고, `LiveWeeklyCheck`·`RecordingDayAdapter`는 segments·complete만 씀(grep).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정 로딩·Guard 테스트 변경 없음(`git diff --name-only`). `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 바뀐 운영 코드는 녹화 메타 복사와 녹화 tmp 열기 주입점뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 무관. 시험의 순번은 녹화 줄·소스 순번(DayWriterRestartStreamTest.java:154-178) |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 68개 통과 |
| A8 | 통과 | 녹화 코드는 게이트웨이·실험실 입력 기록에서만 돌고 실전 엔진·감시는 싣지 않음(위 grep). 운영 동작은 meta.json 키 순서만 바뀜. TreeMap 복사는 소스 수(수 개) 수준이라 자원 영향 없음 `[추론]`. `:autotrade-app-gateway:test` 55개 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 appkey·secret·token·password·account·계좌번호 문자열 0줄(grep -i). 시험 픽스처는 공개 종목코드·합성 가격·임의 소스 이름(Z-WS 등), 예외 문구는 녹화 폴더 경로만 담음(DayWriter.java:143) |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·장애 테스트) 삭제·비활성화·고정 값 변경 없음. 기존 시험은 주석 두 곳만 고침(단언 무변경). 새 동작(sources 이름순)에 이름순·null 거절 시험이 있고, M1·M2·M3 변이를 새 시험이 잡음을 직접 확인 |
| A12 | 통과 | 대응표 #9(broker-and-fault-design.md:168, 게이트웨이·실험실 크래시 → 재기동 후 `RECORDER_RESTART` 공백)의 같은 날 공백 시작 규칙(`lastAlive`, DayWriter.java:177-184)을 새 시험이 고정. 운영 동작 변경 없음이라 장애 테스트 8종·해외 O1~O8 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 시험은 KRX·Asia/Seoul 그대로 |
| A14 | 통과 | D-132(결정·대안·검증·위험), 결정 로그 README D-132 행, 기획서 v14.58 변경 이력(MD-09), D-119 후속 한 줄이 diff 내용과 일치. D-132 번호는 main에 없음(`git ls-tree origin/main docs/decisions/`), 기획서 main 판 v14.57 다음 번호. D-132의 "변이: Map.copyOf로 되돌리면 시험 2개 실패" 주장을 M1으로 재현 |

## 점검표 밖 발견
- 참고(판정 무관): D-132 "위험과 한계"대로 이 PR 전 meta.json은 섞인 순서로 남는다. 읽기는 키 순서와 무관하고(Jackson 맵 역직렬화 후 생성자가 이름순으로 다시 복사) 성과·판정 수치에 영향 없음.
- 참고(판정 무관): D-132 문제 절의 근거 "메모리 pr206"은 저장소에서 확인할 수 없는 출처다. 같은 줄의 `docs/reviews/pr-f9/architect.md`는 있음. 판정에는 직접 실행한 M1만 썼다.
- 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응 규칙 변경 없음.

## 판정
승인
