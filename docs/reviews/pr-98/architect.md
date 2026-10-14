# architect 검토: 4단계 등록 전 보강(D-083): 반전 스윙 v2, 결과 파일 해석 칸, 등록 파일 해시, 깔때기 연결 확인

- 대상: PR #98 / 브랜치 `claude/project-thread-pb3vv4` / 커밋 `0e94c75` / main `a8633eb` 대비 diff 전체(`git diff a8633eb..0e94c75`, 11파일 +147 −26)
- 점검표: B
- 직접 실행한 것: 깨끗한 복제본(`/tmp/claude-0/review-pr98-architect`, `0e94c75` 체크아웃)에서 `./gradlew build --offline` → 종료 코드 0. 테스트 결과 XML 합계 1,067건, 실패 0, 오류 0, 건너뜀 0. `WalkForwardStudyTest` 6건 모두 통과(바뀐 두 테스트 `등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`, `사전_등록할_4단계_10조합은_시작_전_검증과_예열_300일을_통과한다` 포함).
- 읽은 것: diff 전체, `Pooled.java`, `MetricsCalculator.java`, `WindowRun.java`, `RegistrationYaml` 파서, `Sha256.java`, `ScreeningCriteria.num/pct`, runbook 4~7장, `strategies/kr-short-reversal-swing/v1.yaml`. PR 본문·커밋 메시지는 판정 근거로 쓰지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행: `./gradlew build --offline` 종료 0, 1,067건 실패 0 |
| B2 | 통과 | 새 의존은 `autotrade-sim` → `com.autotrade.marketdata.recording.Sha256`(WalkForwardStudy.java:8) 하나이며, `autotrade-sim/build.gradle.kts:9`에 `implementation(project(":autotrade-marketdata"))`가 이미 있다. Spring·SDK·DB 의존 추가 없음. ArchUnit(`autotrade-architecture-test`) 빌드에서 통과 |
| B3 | 통과 | 결과 파일 형식(계약) 변경: `inputs.yaml`의 `study-sha256`, 리더보드 3열, 대조군 표 1열, 깔때기 연결 예외가 ADR-009 2.7(docs/adr/ADR-009-daily-walkforward-runner.md:61, :63)에 반영. D-083·결정 로그 README:89·기획서 v14.28·stage-4-plan·runbook 갱신. 인터페이스·DB 스키마·설정 키·REST 변경 없음. 단 해석 칸 정의 문구가 계산과 맞지 않는 점은 아래 M2 |
| B4 | 통과 | 새 코드에 시스템 시계·난수 없음. 해시는 등록 파일 바이트, 평균 보유는 `ChronoUnit.DAYS`(WalkForwardStudy.java:445), 순회는 기존 정렬 그대로. `study-sha256`은 결정론 비교(`run`) 뒤에 넣어(WalkForwardStudy.java:175-180) 병렬 1과 N 비교에 영향 없음. 바이트 동일 테스트와 `같은_입력을_두_번_돌리면_결과가_같다` 통과 |
| B5 | 통과 | 대조군 평균 보유는 `BigDecimal` 나눗셈 scale 6 HALF_EVEN(WalkForwardStudy.java:461-462), 표시는 `ScreeningCriteria.num`(scale 2 HALF_EVEN). `double` 없음 |
| B6 | 통과(보완 권고 있음) | 해석 칸·`study-sha256` 위치(WalkForwardStudyTest.java:155-159), 깔때기 연결이 실제로 이어졌는지(검증 행 `signals` 열 합 > 0, :153-154, 헤더 0기준 14열이 `signals`임을 WalkForwardStudy.java:335-337로 확인), v2 ±20% 대상이 `signal.down-days` 2·4인지(:214-219) 테스트가 있다. 지우거나 건너뛴 테스트 없음. 새 예외 경로(완료 Run의 깔때기 누락 시 `IllegalStateException`, WalkForwardStudy.java:558-564) 자체를 일으키는 테스트는 없다(아래 m1) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동을 건드리지 않는다. 바뀐 운영 코드는 `autotrade-sim/.../walkforward/` 3파일과 전략 프로필 YAML 하나뿐 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 로그·예외 메시지는 Run ID와 (프로필 해시, Run 시드) 키만 담는다(WalkForwardStudy.java:563) |

## 점검표 밖 발견

### M1 (수정 필요): `study-sha256`이 Windows 체크아웃의 CRLF 때문에 커밋된 등록 파일과 어긋날 수 있다
- [사실] `Sha256.ofFile(file)`은 작업 트리 파일의 원 바이트를 해시한다(WalkForwardStudy.java:168, Sha256.java:16-). 등록 파일 파서는 `split("\n")` 뒤 `stripTrailing()`이라 CRLF여도 정상으로 읽는다(RegistrationYaml 44-46행). 저장소에 `.gitattributes`가 없다.
- [사실] 절차서상 등록 파일은 Claude가 커밋하고(runbook 4장) 사용자가 PC에서 `git pull` 뒤 실행하며(5장), 7장에 새로 "`study-sha256`이 커밋된 등록 파일의 SHA-256과 같은지 확인한다(다르면 결과를 쓰지 않는다)"가 들어갔다(docs/runbooks/stage-4-walkforward.md:114).
- [추론] 사용자 PC는 Windows다(CLAUDE.md의 QA 폴더 `C:\...`, collector runbook의 Windows 작업 스케줄러 안내). Git for Windows 기본값(`core.autocrlf=true`)이면 작업 트리 `study.yaml`이 CRLF가 되고, 5장 둘째 줄 `git diff --exit-code`는 차이가 없다고 나오지만 해시는 LF 블롭과 달라진다. 결과적으로 몇 시간(결정론 확인 포함이면 두 배 넘게) 걸린 정상 결과를 7장 규칙대로 버리게 되거나, 반대로 "줄바꿈 탓"이라며 확인을 건너뛰는 관행이 생겨 이 칸의 목적(손으로 만든 미추적 등록 파일 걸러내기)이 무너진다.
- 고칠 방법(둘 중 하나, 추천 a): (a) 해시 전에 `\r\n`→`\n` 정규화한 UTF-8 내용을 해시하고, ADR-009 2.7·D-083·runbook 7장에 "LF 정규화 후 SHA-256"과 대조 명령(예: `git show origin/main:experiments/<ID>/study.yaml | sha256sum`)을 적는다. CRLF 파일과 LF 파일이 같은 해시를 내는 테스트를 더한다. (b) `.gitattributes`에 `experiments/**/study.yaml text eol=lf`(가능하면 `experiments/**` 전체)를 넣고 runbook에 대조 명령을 적는다. (b)는 사용자 PC의 기존 체크아웃에 `git add --renormalize`/재체크아웃이 필요할 수 있어 (a)가 덜 깨진다.

### M2 (수정 필요): 리더보드 "비용 비중"·"회전율"의 정의 문구와 실제 계산이 다르다
- [사실] 리더보드 행은 `Pooled.of`가 만든 지표다. `Pooled.of`는 자산 곡선을 구간 수익률로 복리로 이어 다시 스케일하지만(Pooled.java:56-63) 체결(`fills`)은 구간별 원 계좌 금액 그대로 합친다(Pooled.java:65). `MetricsCalculator`는 `costs`·`traded`를 그 원 체결에서, `gross = 이은 최종 평가금액 − 시드 + costs`(MetricsCalculator.java:101), 회전율 분모를 이은 곡선 평균(:98-100)에서 잡는다.
- 그래서 비용 비중은 "구간별 원 금액 비용 ÷ (복리로 이은 손익 + 원 금액 비용)", 회전율은 "원 금액 매매 대금 ÷ 2 ÷ 복리로 이은 평균 평가금액"이 된다. 결과 파일 문구(WalkForwardReport.java:180-181)와 D-083 3번·ADR-009 2.7의 "비용 ÷ 비용 전 손익", "매매 대금 ÷ 2 ÷ 평균 평가금액"과 같은 기준이 아니다. [추론] 검증 4구간 누적 수익률이 클수록(상승 시 회전율 과소, 비용 비중은 복리 손익과 단리 비용이 섞여 어느 쪽으로든 틀어짐) 차이가 커지고, 손실 구간이 섞이면 "비용 전 손익 ≤ 0이면 -" 경계도 원 금액 기준과 다르게 걸린다.
- 판정에 쓰지 않는 칸이지만, 사전 등록 결과 파일의 고정 문구가 계산과 다르면 리포트 해석(D-083 "되돌릴 조건"의 근거)이 틀린 값 위에 선다. 기존 "비용" 열(원 금액 합)과 "왕복당 손익"(이은 손익, 후보·대조군 같은 기준)은 문제 없다.
- 고칠 방법(추천 a): (a) 해석 칸을 구간 Run 지표에서 직접 계산한다. 비용 비중 = Σ구간 비용 ÷ Σ구간 (순손익 + 비용), 회전율 = Σ구간 매매 대금 ÷ 2 ÷ 구간 평균 평가금액의 관측일 가중 평균(또는 구간 회전율의 합). 손 계산 기대값 단위 테스트(두 구간, 한 구간 +50%)를 더한다. (b) 계산은 그대로 두고 문구를 "구간 원 금액 비용 ÷ 이은 곡선 손익 기준(근사)"으로 바꾸고 한계를 적는다. (a)가 이름과 값이 맞아 해석이 쉽다.

### m1 (권고): 깔때기 연결 예외 경로의 직접 테스트
- 현재 테스트는 "이어졌다"(합 > 0)만 본다. 시드 파생이 바뀌면 멈춘다는 새 동작(D-083 4번)을 지키려면 `FunnelCapture`를 패키지 범위로 열거나 키를 일부러 어긋나게 만든 `RunReport`로 `totals`가 `IllegalStateException`을 던지고, 미완료 Run은 `ZERO`를 돌려주는지 보는 단위 테스트가 있으면 좋다. 머지 조건은 아니다.

### 확인했지만 문제 아님
- v2.yaml은 v1과 `version`, `created-at`, `study.key-param`만 다르다(`diff v1.yaml v2.yaml`). v1 파일은 고치지 않았다(ADR-005 4장 "저장된 버전 파일은 고치지 않는다" 준수). 예열 필요량 max(100, 4+1, 5) = 100일은 runbook 등록 파일 `warmup-days: 300` 안이고 테스트가 시작 전 검증을 통과한다.
- ±20% 반올림: 3 × 0.8 = 2.4 → 2, 3 × 1.2 = 3.6 → 4를 테스트가 실제 후보 생성 결과로 확인(WalkForwardStudyTest.java:214-219).
- `replaceFirst("(?m)^(experiment: .*\\n)", "$1study-sha256: " + 해시 + "\n")`: 치환 문자열의 해시는 16진수라 `$`·`\` 이스케이프 문제가 없고, `experiment:` 줄은 `inputs.yaml` 첫 블록에 항상 있다(WalkForwardReport.java:39). 테스트가 줄 위치까지 확인.
- 대조군 평균 보유는 완료 시드의 모든 구간 왕복(WINDOW_END 포함)을 왕복 수로 나눠, 후보 리더보드(`Pooled` → `MetricsCalculator.avgHolding`, 같은 `ChronoUnit.DAYS` 정의, WINDOW_END 포함)와 같은 정의다.
- 깔때기 키 (프로필 해시, Run 시드): 한 `runAll` 청크 안에서 조합이 서로 달라 Run 시드가 겹치지 않고, `FunnelCapture`는 청크마다 새로 만든다(WalkForwardStudy.java:521). 비관리 전략은 `Optional.empty()`로 넣어 "못 찾음"과 구별한다. 예외는 청크가 끝날 때 바로 나므로 결과 파일을 쓰기 전에 멈춘다.
- D-083은 등록 파일 커밋 전 변경이라 사전 등록 원칙(결과를 본 뒤 고치지 않음)과 충돌하지 않는다. 프로젝트 지침 8장 멈출 조건(과금·실전 영향·되돌리기 어려움·확정 결정 충돌·범위 변경)에 해당하지 않아 "추천안 채택(자율)" 표시가 맞다. 전략·통계 측면(key-param 선택, 50% 문턱)은 strategy-researcher 검토 범위로 두고 여기서는 판정하지 않았다.

## 판정
수정 필요(M1 `study-sha256` CRLF 불일치, M2 비용 비중·회전율 정의와 계산 불일치)

## 재검토 2026-10-14

- 대상: PR #98 / 새 헤드 `60ecf87`(추가 커밋 1개 `60ecf87`) / main `a8633eb` 대비 diff 전체(`git diff a8633eb..60ecf87`)와 직전 검토 이후 변화(`git diff 0e94c75..60ecf87`, 10파일 +220 −13)
- 점검표: B
- 직접 실행한 것: 같은 복제본에서 `git fetch /home/claude/autotrade-system claude/project-thread-pb3vv4 && git checkout -q FETCH_HEAD`(HEAD `60ecf87`) 뒤 `./gradlew build --offline` → 종료 코드 0. 테스트 결과 XML 합계 1,070건(직전보다 3건 늘어남), 실패 0, 오류 0, 건너뜀 0. 새 테스트 `PooledCostShareTest` 2건과 `WalkForwardStudyTest#등록_파일_해시는_줄바꿈이_CRLF여도_LF_원본과_같다` 통과. 테스트에 박힌 해시 상수를 `printf 'id: "EXP-1"\nseed: "1"\n' | sha256sum`으로 따로 계산해 `85faebda…0fa75`와 같음을 확인. PR에 들어간 이 파일의 첫 검토 본문(`git show 60ecf87:docs/reviews/pr-98/architect.md`)이 내가 쓴 원문과 바이트까지 같음을 `diff`로 확인(고쳐지지 않음).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행: `./gradlew build --offline` 종료 0, 1,070건 실패 0 |
| B2 | 통과 | `marketdata.recording.Sha256` import를 없애고 JDK `MessageDigest`·`HexFormat`만 쓴다(WalkForwardStudy.java:54-60, :166-174). 새 모듈 의존 없음. ArchUnit 빌드 통과 |
| B3 | 통과 | 바뀐 정의가 ADR-009 2.7(LF 기준 해시, 구간 원 금액 합 비용 비중, 구간 회전율 합), D-083 1·3번, runbook 7장 대조 명령, 결과 파일 고정 문구(WalkForwardReport.java:179-182)에 똑같이 반영됨 |
| B4 | 통과 | 새 계산은 구간 순서 그대로의 `BigDecimal` 합이고 시계·난수 없음. 바이트 동일·두 번 실행 테스트 통과 |
| B5 | 통과 | `Pooled.costShare`·`Pooled.turnover`는 `BigDecimal`, `MC` 나눗셈 뒤 `SCALE` HALF_EVEN(Pooled.java:109-133) |
| B6 | 통과(m1 권고 남음) | M1·M2 각각 테스트 추가(아래). 지우거나 건너뛴 테스트 없음 |
| B7 | 해당 없음 | 추가 커밋도 `autotrade-sim/.../walkforward/`와 문서만 바뀜. 실전 경로 무관 |
| B8 | 통과 | 비밀값 없음 |

### 직전 지적 처리
- **M1(CRLF) 해소.** `runAndWrite`가 등록 파일을 한 번 읽어 같은 문자열로 파싱과 해시를 하고(WalkForwardStudy.java:183-185), `studySha256`이 `\r\n`→`\n` 정규화 뒤 UTF-8 바이트를 해시한다(:166-174). CRLF 문자열과 LF 문자열이 같은 값이고 그 값이 독립 계산한 `sha256sum`과 같다는 테스트가 있다(WalkForwardStudyTest.java:162-168). runbook 7장의 `git show origin/main:<경로> | sha256sum`은 저장된 블롭 원 바이트를 출력하므로, Linux에서 커밋한 LF 블롭이면 코드 값과 같다.
- **M2(정의 불일치) 해소.** 리더보드 비용 비중은 `Pooled.costShare(시드, 그 후보의 구간 검증 Run)`: Σ구간 비용 ÷ Σ(구간 최종 평가금액 − 시드 + 구간 비용)이고, 구간 최종 평가금액은 `WindowRun.finalEquity()`의 원 계좌 금액(WindowRun.java:53-55)이라 분자·분모가 같은 원 금액 기준이다. 회전율은 구간 `RunMetrics.turnover`(구간 자신의 평균 평가금액이 분모)의 합. 행 순서는 `validationRuns`가 후보 순서라(WalkForwardResult.java:19) `get(i)`이 같은 후보다. 손 계산 테스트(+30%·−25%·+10%·−5%, 비용 각 50만 원 → 300만 중 200만 = 0.666667, 회전율 1.5+2+0.5+1 = 5)를 내가 다시 계산해 같음을 확인. 비용 전 손익 합 ≤ 0과 실패 구간이면 null도 테스트됨. 평균 보유는 왕복 단위 평균이라 이은 곡선 스케일과 무관해 그대로 둔 것이 맞다.

### 남은 것과 새로 본 것
- m1(깔때기 연결 예외 경로 직접 테스트)은 반영되지 않았다. 직전 검토대로 권고이며 머지 조건 아니다.
- n1(참고): 코드 주석·테스트 주석의 검토 번호가 이 원문과 어긋난다. CRLF 수정 주석은 "PR #98 검토 M2"(WalkForwardStudy.java:163, WalkForwardStudyTest.java:164), 비용 비중 주석은 "PR #98 검토 M1"·"strategy-researcher 검토 M1"(Pooled.java:106, PooledCostShareTest.java:13)인데, 이 architect 원문에서는 CRLF가 M1, 정의 불일치가 M2다. strategy-researcher 원문의 번호를 따른 것일 수 있어 동작 문제는 아니다. 다음에 그 파일을 고칠 때 "architect M1/M2"처럼 검토자를 붙이면 추적이 쉽다.
- n2(참고): 해시가 맞으려면 커밋된 블롭이 LF여야 한다. 등록 파일을 Linux 쪽(Claude)에서 커밋하는 지금 절차에서는 성립한다. 사용자가 Windows에서 직접 커밋하는 절차로 바뀌면 runbook 대조 명령도 정규화(`| tr -d '\r' | sha256sum`)로 바꾸면 된다.
- 추가 커밋에 들어간 `docs/reviews/pr-98/strategy-researcher.md`는 내 판정 범위 밖이라 내용 판정은 하지 않았다.

## 판정
승인
