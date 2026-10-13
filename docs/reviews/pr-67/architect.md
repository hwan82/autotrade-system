# architect 검토: 2단계 실녹화 replayCheck 원본 출력 파일 추가 (재검토 (2) 완료 조건 5)
- 대상: PR #67 / 브랜치 `claude/project-thread-jdynjl` 커밋 a2eee7897675deada912e2997a2d6ca35c42fb17 / `git diff origin/main...HEAD`(커밋 1개, 2파일 +23 −1: `experiments/stage-2-replay/2026-10-12.md` 1줄 교체, `experiments/stage-2-replay/2026-10-12.replay-check.md` 새 파일). 코드·설정·스키마 변경 없음
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL(130 tasks, 테스트는 입력이 같아 UP-TO-DATE)
  - `./gradlew build --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL**(130 tasks 모두 실행, 2분 36초). 테스트 결과 XML 합계 tests 905, failures 0, errors 0, skipped 0
  - `git diff origin/main...HEAD` 전체 확인, `wc -c`(1,833바이트), `file`(UTF-8), 끝 바이트 `|\n` 확인
  - 보고서 원문 1 코드 블록을 뽑아 새 파일과 `diff` → 차이는 7번째 줄 "재기동 공백 표시 없음(D-065 이전 녹화): 구간 시작 14:19:47(seq 410641). …" 한 줄 추가뿐
  - `git merge-base --is-ancestor ce554ba origin/main` → 참(ce554ba = PR #66 머지), `git diff ce554ba HEAD -- autotrade-app-gateway` → 변경 없음
  - `ReplayCheck.toMarkdown`(`autotrade-app-gateway/src/main/java/com/autotrade/app/gateway/ReplayCheck.java:277~319`) 출력 형식과 새 파일 줄 단위 대조
  - `gh api .../commits/a2eee78/check-runs` → Gradle build, Secret scan and collector boundary, DB schema 모두 success

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행 BUILD SUCCESSFUL, 905건 실패·오류·건너뜀 0. 이번에는 Maven Central 429 없음 |
| B2 | 해당 없음 | Java·Gradle 파일 변경 없음. ArchUnit(`autotrade-architecture-test`)은 위 빌드에서 그대로 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST 변경 없음. 새 파일은 실험 폴더의 증거 파일(`docs/team/project-instructions.md` 2장 "실험 결과: `experiments/<실험ID>/`") |
| B4 | 통과 | 결정론 코드 변경 없음. 새 파일의 두 회차 SHA-256 `b85109ab…c4bf4c4f`·528,156건이 같고(판정 "동일"), 첫 실행 사본(원문 1)과도 같다. sim 골든 테스트 포함 전체 테스트 재실행 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서·증거 파일만). 지우거나 끈 테스트 없음 |
| B7 | 해당 없음 | 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동 변경 없음. 시세 녹화 결과 파일과 보고서 문장만 바뀜 |
| B8 | 통과 | diff에 키·토큰·계좌번호·사용자 경로 외 식별 정보 없음(직접 확인). 해시(eventsSha256, 리플레이 SHA-256)는 비밀값이 아니며 CI gitleaks 통과. 새 파일의 종목별 OHLC·체결 수·수량 합은 보고서 대조표·원문 1에 이미 있는 집계값이고 이벤트 원문(시세 틱)은 없다(ADR-003 4장 재배포 금지 대상 아님, 녹화 파일은 넣지 않음) |

## 점검표 밖 발견

- 확인함 [사실]: 보고서 `2026-10-12.md:64`의 주장 중 저장소에서 확인할 수 있는 것은 모두 맞다.
  - 1,833바이트(`wc -c`), 두 회차 SHA-256·이벤트 수 528,156·끊김 6·eventsSha256·종목별 OHLC가 원문 1과 같음, 다른 점은 재기동 표시 없음 줄 하나(`diff`).
  - 그 줄은 `ReplayCheck.java:290~297`이 `unmarkedRestarts`가 있을 때만 내는 문구와 글자 그대로 같고, 시각 14:19:47·seq 410641은 `2026-10-12.meta.json` 2구간 `startedAt` 05:19:47.35Z(+09:00)·`firstSeq` 410641과 맞는다. `ReplayCheckTest.java:178`이 같은 문자열을 검사한다.
  - 나머지 줄(메타 줄의 "(파일과 일치 확인됨)", `TreeMap` 순서 출처 표기, 정규장 안내 문장, 표 머리)도 `ReplayCheck.toMarkdown` 출력 형식과 같다. ce554ba와 HEAD 사이 게이트웨이 코드 변경이 없어 "main `ce554ba`로 실행"과 모순되지 않는다.
- 확인 못 함(판정에 넣지 않음) [가정]: 실행 시각(2026-10-13 16:29 KST)과 "PC 파일을 바꾸지 않고 복사"는 사용자 PC에서 일어난 일이라 저장소로 확인할 수 없다. 다만 이 파일은 `events.jsonl.gz` 없이는 QA도 다시 만들 수 없으므로, 증거 가치는 "형식이 현재 코드 출력과 같고 meta.json·첫 실행 사본과 모순이 없다"까지다. 보고서가 이를 "원본 출력 파일"로 부르는 것은 맞지만, 녹화 파일 자체에 대한 독립 재현은 여전히 아니다(H-3의 원래 한계, ADR-003상 불가피).
- **O1 (권고, 문서)** 보고서 `2026-10-12.md:69` 머리 "원문 1: replayCheck 보고서 (`replay-2026-10-12.md`)"와 새 증거 파일의 PC 쪽 이름이 같다(PR 본문: PC 저장소 루트 `replay-2026-10-12.md`). 이제 같은 이름의 출력이 두 번(첫 실행 사본, 2026-10-13 재실행 원본) 있으므로, 머리를 "원문 1: 첫 실행 replayCheck 보고서 사본(2026-10-12, 원본 파일 없음)"처럼 바꾸면 QA가 어느 것을 대조하는지 헷갈리지 않는다. 내용 오류는 아니다.
- **O2 (권고, 문서)** 재검토 (2) 완료 조건 5(`docs/reviews/gates/stage-2.md:203`)는 `meta.json`과 `replayCheck` 원 출력 둘 다를 요구했고, 이 PR로 둘 다 갖춰졌다. 보고서 "후속 과제" 절이나 증거 파일 절 첫 줄에 "H-3 조건 충족(meta.json PR #66, replay-check PR #67)"을 한 줄 두면 다음 관문 판정 때 추적이 쉽다. 머지를 막지 않는다.

## 판정
승인. B 점검표에 실패·확인 못 함이 없다. 문서·증거 파일만 바뀐 PR이고, 저장소로 확인할 수 있는 주장(크기, 원문 1과의 차이, 새 줄의 출처 코드와 meta.json 값)은 모두 직접 확인했다. O1·O2는 머지를 막지 않는 권고다. 실전 경로 변경이 없어 risk-ops 검토 대상이 아니다(B7 해당 없음).
