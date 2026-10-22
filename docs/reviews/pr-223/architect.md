# architect 검토: PR #223 6단계 점검표: 입력 기록 크기는 실험실을 끈 뒤 잼
- 대상: PR #223 / 커밋 2ae0ffba / main(4c48b884) 대비 diff `git diff origin/main...HEAD` 전체(1파일, `docs/runbooks/stage-6-start-checklist.md` +1 −1)
- 점검표: B
- 직접 실행한 것: `./gradlew build` → BUILD SUCCESSFUL(3m 26s, 138 tasks, 62 executed·76 up-to-date, 종료 코드 0, 로그에 FAILED 0줄). GitHub 검사(2ae0ffba): Lab screens(Playwright) 성공, Secret scan and collector boundary 성공, DB schema(PostgreSQL 16) 성공, Gradle build는 검토 시점 진행 중

## 문서 내용과 코드 대조
새 문장의 주장을 main 코드로 확인했다(PR 본문·커밋 메시지는 근거로 쓰지 않음).
- "실험실이 파일을 열어 둔 동안": 입력 기록은 `LiveSession`이 세션 동안 `JournalWriter`로 계속 열어 두고(`autotrade-sim/.../live/LiveSession.java:142` 생성, `:318` append, `:389` flush만), 세션 `close()` 때만 닫는다(`:578`). 날 파일은 `DayWriter`가 `Files.newOutputStream(..., APPEND)` + `GZIPOutputStream(raw, 64KB, syncFlush=true)`로 연 채 쓴다(`autotrade-marketdata/.../recording/DayWriter.java:123-124`), 닫기는 `:274-283` `[사실]`. 실험실 종료 시 세션을 닫는 경로는 `LiveLabService.java:223,259` `[사실]`
- 경로 `live-sessions\$id\journal\KRX\$d`: `LiveCheckMain.SESSIONS_DIR = "live-sessions"`(`LiveCheckMain.java:23`), `LiveSession.JOURNAL_DIR = "journal"`(`LiveSession.java:83,125`) `[사실]`
- "Windows가 크기를 늦게 갱신해 0MB로 보일 수 있다": NTFS가 열린 파일의 디렉터리 항목 크기를 지연 갱신하는 일반 동작과 맞는다 `[추론]`. 0MB 관측 자체는 사용자 실측(2026-10-21)으로 이 검토에서 재현하지 못했다(Linux 검토 환경). 문서 문장이 "보일 수 있다"로 단정하지 않아 판정에 영향 없음
- "마지막 날은 녹화 크기만 본다": 녹화는 게이트웨이(별도 프로세스)가 같은 `DayWriter`로 쓰며, 같은 절의 1단계에서 게이트웨이를 먼저 끄므로 녹화 크기는 닫힌 파일 기준이다 `[사실]`(diff 주변 `stage-6-start-checklist.md:119`). 2단계의 "운용 마지막 날(10-23, 12-18)은 실험실을 끄지 않는다"와도 일관 `[사실]`

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build` 직접 실행, BUILD SUCCESSFUL·종료 코드 0, FAILED 0줄 |
| B2 | 해당 없음 | 코드·빌드 설정·ArchUnit 규칙 변경 없음(diff는 `docs/runbooks/` 한 줄) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 절차서 측정 시점 안내만 바뀜 |
| B4 | 해당 없음 | 코드 변경 없음. 기존 골든·결정론 시험은 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서). 시험 삭제·건너뜀 없음 |
| B7 | 해당 없음 | 실전 경로 미접촉. 바뀐 파일은 `scripts/ci/protected-paths.txt`의 보호 경로가 아님. 실험실 모의 운영 절차서 문장 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. CI Secret scan 성공 |

## 점검표 밖 발견
- (참고, 수정 불요) 2단계는 마지막 날이 아니면 실험실을 "켜 두어도 된다"고 하므로, 켜 둔 날에는 그날 입력 기록 크기를 언제 재는지가 문장에 명시돼 있지 않다(다음에 실험실을 끈 뒤 재거나 금요일 주간 점검 전에 재는 식). LIVE00 동안은 매일 장중 재기동이 있어 실무상 문제는 작다. 또 PowerShell 블록은 마지막 날에도 두 경로를 모두 출력하므로, 입력 기록 줄이 0MB로 나와도 무시하라는 뜻을 블록 뒤 한 줄로 적어 두면 오해가 줄어든다.
- (참고) 지연 갱신 대신 정확한 값을 원하면 `Get-ChildItem` 크기 대신 파일을 열어 길이를 읽는 방법(`[IO.File]::Open(..., 'Read', 'ReadWrite').Length`)이 있다 `[추론]`. 지금 문장(끈 뒤 잰다)이 더 단순하므로 바꿀 필요는 없다.

## 판정
승인
