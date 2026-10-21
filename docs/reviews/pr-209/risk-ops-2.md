# risk-ops 재검토: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험
- 대상: PR #209 / 커밋 de8b381574f7606abe4fcbe76c198af605b41705 / main(f571a38, merge-base 같음) 대비 diff(`git diff origin/main...HEAD`, 10개 파일: 운영 코드 `DayWriter.java`·`RecordingMeta.java`, 시험 `DayWriterRestartStreamTest`·`RecordingMetaSourcesOrderTest`, D-119·D-132·결정 로그 README·기획서 v14.58, 검토 원문 2개)
- 앞 검토: `docs/reviews/pr-209/risk-ops.md`(d167717 기준, 승인). 그 파일은 고치지 않고 이 파일에 재검토를 따로 쓴다(메인 지시의 파일 위치). 앞 검토 뒤 새 커밋은 de8b381 하나(`DayWriter.java` +3/−2, `DayWriterRestartStreamTest` +10, D-132 +2). 그 사이 원문 커밋 3b52682·0cd4c59는 `docs/reviews/`만 바꿈
- 점검표: A (보호 경로 `autotrade-marketdata/*`, `scripts/ci/protected-paths.txt`, 실전 관련 PR로 취급)
- 직접 실행한 것(별도 git worktree `/home/user/rv209-risk2`, de8b381 체크아웃, 저장소 작업 트리는 건드리지 않음):
  - `./gradlew build --no-daemon` → EXIT 0(4분 0초). 전체 빌드(컴파일, 테스트, 순수 모듈 의존 검사, ArchUnit) 통과.
  - 결과 XML(tests / 실패 / 오류 / 건너뜀): marketdata 161/0/0/0, risk 55/0/0/0, architecture-test 46/0/0/0, app-engine 68/0/0/0, app-gateway 55/0/0/0, sim 452/0/0/0. `DayWriterRestartStreamTest` 7개, `RecordingMetaSourcesOrderTest` 3개 통과.
  - 변이 M4(새 커밋 대상): `rewriteToTmp`를 앞 판(파일 스트림을 `GZIPOutputStream(tmpOpener.open(tmp))` 안에 바로 넣는 한 자원 try)으로 되돌림 → 10개 중 1개 실패: `다시_쓰기_중_쓰기가_실패하면_폴더를_밝힌_문구로_멈추고_원본을_그대로_둔다`, 메시지 `Expecting AtomicBoolean(false) to have value: true`. 즉 앞 판에서는 쓰기 실패 때 파일 스트림 `close()`가 정말 불리지 않았고(architect 발견 1 재현), 새 판은 불린다. 원복 뒤 `git status` 깨끗함, 같은 두 클래스 재실행 10/10 통과.
  - 앞 검토의 M1~M3(sources 순서, P15, P13)는 해당 코드(`RecordingMeta.java`, `lastAlive`, 실패 문구)가 de8b381에서 바뀌지 않아 다시 돌리지 않았다. 그 시험들은 이번 build에서 통과.

## 새 커밋 변경 확인(코드로 확인)
- `DayWriter.java:137-138`: `try (OutputStream raw = tmpOpener.open(tmp); BufferedWriter w = ...new GZIPOutputStream(raw)...)`. try-with-resources는 역순으로 닫으므로 `w.close()`가 쓰기 실패로 다시 던져도 `raw.close()`는 불리고 그 예외는 suppressed로 붙는다. 그 뒤 `catch`의 `Files.deleteIfExists(tmp)`(`:150`)가 닫힌 파일을 지운다.
- 성공 경로: `w.close()`가 사슬을 따라 `raw`를 먼저 닫고(gzip 꼬리 기록 포함), twr가 `raw.close()`를 한 번 더 부른다. 운영 `Files.newOutputStream`의 스트림 `close()`는 두 번째 호출에서 아무것도 하지 않는다 `[추론]`(JDK `Channels.newOutputStream` → 채널 `close()`는 멱등). 이 경로는 다른 재기동 시험 6개가 모두 지나가고 통과해 동작이 같음을 확인했다.
- 부수 효과(좋은 쪽): `new GZIPOutputStream(raw)`가 머리 쓰기에서 실패해도 이제 `raw`가 닫힌다(앞 판에서는 생성 전 실패라 열린 채 남음) `[추론]`, 시험 없음.
- 시험 `DayWriterRestartStreamTest.java:189-220`: 시험용 `FilterOutputStream`의 `close()`가 표시를 남기고 `out.close()`를 부른다. 단언 추가뿐이고 기존 단언(문구, 원인 문구, 원본 바이트 그대로, tmp 없음, 보관본 없음)은 그대로. 변이 M4로 이 단언이 회귀를 잡는 것을 확인.
- 운영 호출처와 영향 범위는 앞 검토와 같다: 녹화는 게이트웨이·실험실 입력 기록에서만 돌고 `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`에 `recording` 참조 0건(grep 다시 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff(10개 파일, `git diff --name-only`)에 RealTradingGuard·설정 로딩·Guard 테스트 변경 없음. `:autotrade-risk:test` 55개 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 운영 코드 변경은 녹화 메타 복사(`RecordingMeta.java:47-51`)와 녹화 tmp 열기·닫기(`DayWriter.java:72-84, 137-138, 158-161`)뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 무관. 시험의 순번은 녹화 줄·소스 순번 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 68개 통과 |
| A8 | 통과 | 녹화 코드는 실전 엔진·감시 프로세스에 실리지 않음(위 grep 0건). 새 커밋은 실패 때 파일 핸들을 닫아 게이트웨이·실험실 쪽 핸들 누수를 줄이는 방향이며 실전 엔진 자원과 무관. `:autotrade-app-gateway:test` 55개 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | `git diff origin/main...HEAD`에서 appkey·secret·token·password·account·계좌번호(대소문자 무시) 일치 1줄뿐이며 그것은 앞 risk-ops 원문의 A10 근거 문장 자체. 코드·시험 픽스처에는 0줄. 새 시험 예외 문구는 `디스크 가득 참(시험)`, 운영 문구는 녹화 폴더 경로만 담음(`DayWriter.java:144`) |
| A11 | 통과 | 보호 테스트(architecture-test, Guard·장애 테스트) 삭제·비활성화·고정 값 변경 없음. 새 커밋은 기존 시험에 단언 1개 추가(`DayWriterRestartStreamTest.java:220`), 지우거나 약화한 단언 없음. 고친 동작(실패 때 핸들 닫기)에 실패하는 경우의 시험이 있고 변이 M4가 잡힘을 직접 확인 |
| A12 | 통과 | 대응표 #9(게이트웨이·실험실 크래시 → 재기동 후 `RECORDER_RESTART` 공백)의 재기동 다시 쓰기 실패 경로에서 원본·보관본 무변경과 tmp 삭제가 그대로 유지되고(Q2 시험 단언), 핸들을 닫아 Windows에서 tmp 삭제가 막힐 여지를 줄임 `[추론]`(Windows 직접 실행은 확인 못 함, 판정 근거로 쓰지 않음). 장애 테스트 8종·해외 O1~O8 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 시험은 KRX·Asia/Seoul 그대로 |
| A14 | 통과 | D-132 "위험과 한계"에 OS 줄바꿈 차이 한 줄(architect 2), "같은 PR의 시험 보강"에 핸들 닫기 한 줄(architect 1)이 추가되어 de8b381 코드와 일치. D-132 README 행·기획서 v14.58·D-119 후속 줄은 앞 검토 때와 같고 diff와 일치 |

## 점검표 밖 발견
- 참고(판정 무관): 기획서 v14.58 변경 이력과 결정 로그 README D-132 행에는 핸들 닫기 보강이 적혀 있지 않다. 동작 계약이 아닌 자원 정리라 D-132 본문 기록으로 충분하다고 본다 `[추론]`.
- 참고(판정 무관): 앞 검토의 참고 2건(이 PR 전 meta.json 순서, "메모리 pr206" 출처)은 그대로다.
- Windows에서 Q2 시험이 통과하는지는 여기서 확인하지 못했다(Linux 실행만). 판정은 Linux에서 직접 확인한 "실패 때 close 호출"(M4)에 근거했고, 사용자 PC에서 `./gradlew :autotrade-marketdata:test` 한 번 실행을 권한다.
- 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응 규칙 변경 없음. 보호 경로 변경이라 지침 10·11장대로 사용자 머지·독립 QA 대상.

## 판정
승인
