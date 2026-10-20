# architect 검토: PR #189 6단계 절차서·점검표: #/live 계좌 체결·거절 내역 보기(PR #188 D-120 후속)
- 대상: PR #189 / 커밋 fd8fcda4 / main(22f05c16) 대비 diff `git diff origin/main...HEAD` 전체(2파일, `docs/runbooks/stage-6-live-session.md` +1 −1, `docs/runbooks/stage-6-start-checklist.md` +1 −1)
- 점검표: B
- 직접 실행한 것: `./gradlew build` → BUILD SUCCESSFUL(2m 25s, 138 tasks, 36 executed·102 up-to-date, 종료 코드 0, 로그에 FAILED 0줄). GitHub 검사(fd8fcda4): Lab screens(Playwright) 성공, Secret scan and collector boundary 성공, DB schema(PostgreSQL 16) 성공, Gradle build는 검토 시점 진행 중

## 문서 내용과 코드 대조
새 문장의 주장을 main 코드로 확인했다(PR 본문·커밋 메시지는 근거로 쓰지 않음).
- 기능 출처: PR #188(D-120)이 머지되어 main(22f05c16)에 있고 이 브랜치의 기준점이 그 커밋이다 `[사실]`(GitHub API `pulls/188` merged=true, `git merge-base`)
- "계좌 ID를 누르면": `autotrade-ui/src/main/resources/static/js/pages/live.js:199-200` 계좌 열이 `#/live/runs/<runId>` 링크. 계좌 상태가 없으면 `–`(링크 없음) `[사실]`
- "체결 내역과 거절 사유를 최근 것부터": `autotrade-app-lab/.../live/LiveSessionController.java:62-66`(`a.fills().reversed()`), `:73-78`(`a.rejections().reversed()`) `[사실]`
- "그 기능이 들어간 코드로 실험실을 띄운 뒤부터": D-120 "위험과 한계" 적용 시점(실험실 재기동 때부터)과 일치 `[사실]`(`docs/decisions/D-120-live-account-fills-view.md`)
- "끝낸 세션의 체결은 `experiments/<ID>/live-detail.txt`의 `fill` 줄": 파일 이름 `LiveSession.java:74`(`LIVE_DETAIL_FILE`), 보고서 형식 `ReportWriter.java:58`(`  fill <key> #<seq> ...`, 두 칸 들여쓴 줄) `[사실]`. 세션이 없으면 API가 404인 것은 `LiveSessionControllerTest.java:76-78` `[사실]`
- 화면 주소 `http://127.0.0.1:8480`: `autotrade-app-lab/src/main/resources/application.yml:14` 기본 포트 8480 `[사실]`

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew build` 직접 실행, BUILD SUCCESSFUL·종료 코드 0, FAILED 0줄 |
| B2 | 해당 없음 | 코드·빌드 설정·ArchUnit 규칙 변경 없음(diff는 `docs/runbooks/` 두 줄) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. API·화면은 PR #188(D-120)에서 이미 들어왔고 이 PR은 절차서 안내만 추가 |
| B4 | 해당 없음 | 코드 변경 없음. 기존 골든·결정론 시험은 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서). 문장이 기대는 동작은 기존 시험 `LiveSessionTest#activityShowsEachAccountsFillsAndRejectionsWhileRunningAndAcrossRestart`, `LiveSessionControllerTest`(세션 없음·없는 계좌 404)가 뒷받침. 시험 삭제·건너뜀 없음 |
| B7 | 해당 없음 | 실전 경로 미접촉. 바뀐 두 파일은 `scripts/ci/protected-paths.txt`의 보호 경로가 아님. 실험실 모의 운영 절차서 문장 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(주소 127.0.0.1:8480과 자리표시자 `<ID>`만). CI Secret scan 성공 |

## 점검표 밖 발견
- (참고, 수정 불요) `live-detail.txt`에는 `fill` 줄 외에 `rejected` 줄(`ReportWriter.java:80-82`)도 있어 끝낸 세션의 거절 사유도 볼 수 있다. 문서가 체결만 안내해도 틀린 말은 아니다. 다음에 절차서를 고칠 때 함께 적으면 좋다.
- (참고, 이 PR 범위 밖) "최근 것부터" 순서 자체를 단정하는 시험은 컨트롤러 시험에 보이지 않는다(`LiveSessionControllerTest`는 빈 결과·404·잘못된 쪽 크기만 확인). 코드(`reversed()`)로는 확인됨. PR #188 후속 과제로 순서 시험 추가를 권한다.

## 판정
승인
