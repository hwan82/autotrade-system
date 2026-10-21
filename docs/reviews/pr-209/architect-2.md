# architect 재검토: 녹화·입력 기록 meta.json sources 이름순 고정(D-132)·PR #192 QA Q1·Q2 시험 (재검토 2026-10-20)
- 대상: PR #209 / 커밋 de8b381574f7606abe4fcbe76c198af605b41705 / main 대비 diff(`git diff origin/main...HEAD` 전체, 10개 파일 +326 −7; 그중 검토 원문 2개 제외 코드·문서 8개 파일). 이전 검토(`architect.md`, 커밋 d167717) 뒤 바뀐 것은 de8b381 하나: `DayWriter.java`(+3 −2), `DayWriterRestartStreamTest.java`(+10), `D-132-recording-meta-sources-order.md`(+2). d167717 이후 나머지 코드 diff는 이전 검토와 같음(`git diff --stat d167717 HEAD`로 확인).
- 점검표: B
- 직접 실행한 것(별도 `git worktree`에서 HEAD de8b381 체크아웃, JDK 21.0.12):
  - `./gradlew build --no-daemon` → 종료 코드 0(BUILD SUCCESSFUL). marketdata 시험 결과 XML 합계 tests=161, failures=0, errors=0, skipped=0. `:autotrade-architecture-test` 포함 전 모듈 통과.
  - 변이 M4: `DayWriter.java`를 d167717판(파일 스트림을 따로 잡지 않던 이전 코드)으로 되돌리고 `DayWriterRestartStreamTest`만 실행 → 7개 중 1개 실패: `다시_쓰기_중_쓰기가_실패하면_폴더를_밝힌_문구로_멈추고_원본을_그대로_둔다` ("Expecting AtomicBoolean(false) to have value: true"). 원복 후 재실행 7/7 통과, 작업 트리 깨끗함.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, 종료 코드 0, 실패·건너뜀 0 |
| B2 | 통과 | de8b381은 `DayWriter.rewriteToTmp` 내부 자원 선언만 바꿈(`DayWriter.java:137-138`). 새 타입·새 의존 없음(`OutputStream`은 이미 import, `:123`에서 사용). ArchUnit·소유 표 무변경, `autotrade-architecture-test` 통과. 이전 검토의 `TmpOpener` 패키지 전용 중첩 인터페이스 판단 그대로 |
| B3 | 통과 | 공개 계약 변경 없음. de8b381은 내부 자원 관리만 바꾸고 파일 형식·예외 문구·메서드 시그니처 그대로. D-132에 위험·한계 1줄과 시험 보강 1줄 추가(`D-132-recording-meta-sources-order.md:40`, `:48`). ADR 불필요 |
| B4 | 통과 | 시스템 시계·무시드 난수 추가 없음. 성공 경로에서 `raw`가 두 번 닫히지만(`GZIPOutputStream.close`가 아래로 닫고, try-with-resources가 다시 닫음) `Files.newOutputStream`의 채널 스트림 `close`는 멱등이라 출력 바이트 무변경 `[추론]`; 실제로 같은 날 재기동 다시 쓰기 성공 시험과 기존 골든·바이트 시험이 모두 통과(build) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작(실패 때 파일 핸들 닫힘)을 Q2 시험이 `fileClosed` 플래그로 고정(`DayWriterRestartStreamTest.java:189`, `:195`, `:220`). 변이 M4가 잡힘. 이전 변이 M1~M3 대상 시험·단언은 그대로 남아 있음. 지운·건너뛴 시험 없음(de8b381의 시험 변경은 추가 10줄뿐) |
| B7 | risk-ops 검토 필요 | `autotrade-marketdata/*`는 `scripts/ci/protected-paths.txt`의 보호 경로. de8b381이 이 경로의 운영 코드(`DayWriter.java`)를 새로 바꿨다. `docs/reviews/pr-209/risk-ops.md`(판정 승인)는 de8b381 이전 커밋 기준이므로 이 변경에 대한 risk-ops 재검토가 따로 필요. 이후 사용자 머지·독립 QA 대상 |
| B8 | 통과 | de8b381 diff에 키·계좌·토큰 없음. 시험 예외 문구 `디스크 가득 참(시험)`뿐 |

## 점검표 밖 발견
1. 이전 발견 1(쓰기 실패 때 tmp 파일 핸들 미닫힘) 해소 확인. 파일 스트림을 첫 자원으로 따로 선언해, 위 `BufferedWriter`/`GZIPOutputStream` 닫기가 같은 실패로 다시 던져도 `raw.close()`가 불린다(try-with-resources는 역순으로 모든 자원을 닫고 예외는 suppressed로 붙임). 덤으로 `new GZIPOutputStream(raw)`가 머리 쓰기에서 실패하는 경우에도 이제 핸들이 닫힌다. Windows에서 실제로 tmp 삭제가 되는지는 여전히 직접 확인하지 못했으나, 핸들이 닫히므로 남은 위험은 낮다 `[추론]`.
2. 이전 발견 2(meta.json OS 줄바꿈 차이) 해소 확인: D-132 "위험과 한계"에 한 줄 추가(`D-132-recording-meta-sources-order.md:40`).
3. (비차단) 시험의 `close()` 재정의는 `fileClosed`를 먼저 세우고 `out.close()`를 부르므로 "close가 불렸다"를 확인하지 실제 OS 핸들 해제를 확인하지는 않는다. 실제 해제는 `Files.newOutputStream` 스트림의 `close` 계약에 기댄다. 충분하다고 본다.
4. (참고) 이 재검토 원문은 caller 지시로 `architect.md`에 덧붙이지 않고 새 파일 `architect-2.md`로 남겼다(`docs/reviews/README.md`는 같은 파일 아래 `## 재검토`를 권함). 앞 원문은 고치지 않았다.

## 판정
승인 (단, B7: 보호 경로 운영 코드가 이전 risk-ops 검토 뒤 바뀌었으므로 risk-ops 재검토·사용자 머지·독립 QA를 거쳐야 하며 이 판정만으로 Claude가 머지하지 않는다)
