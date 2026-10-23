# architect 검토: D-136 후속 고침 2 (PR #225 QA Q1~Q3·R2, EGW00215 안내)
- 대상: PR #228 / 커밋 20b5506795b133e44f644ef2d0e14c8ccba938e5 / `git diff origin/main...HEAD` 전체(9개 파일, +79 −10)
- 점검표: B
- 직접 실행한 것(모두 PR 머리 커밋을 따로 꺼낸 `git worktree`에서):
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL in 5m 16s, 138 tasks
  - `bash scripts/ci/check-collector-keys.sh`(작업 트리) → exit 0. 바뀐 정규식에 표본 문자열을 직접 넣어 봄: `"appkey": "<26자>"`, `'appsecret': '<24자, -·_ 포함>'`, `{\"app-key\":\"<26자>\"}` 잡힘. `"app-key": "<앱키>"`, 16자 시험 값 안 잡힘(의도대로). `"appKey": "<26자>"`(camelCase), `appkey: <값>`(따옴표 없는 YAML)은 안 잡힘
  - 변이 시험 4종(`:autotrade-app-collector:test`):
    - M1 `000001` 한 번 더를 지우고 늘 전날 23:59:59로: `새_행_없는_쪽이_00시_00분_01초에서_끝나면_000001로_한_번_더_받아_00시_행을_본다` 실패 → 잡힘
    - M2 `midnightCuts++` 제거: `자정_행이_한_쪽보다_많으면_확인_못_한_것을_세어_알린다`, `백필이_보관_한도에_닿으면_바닥을_적고_다시_시도하지_않는다`, `KisNewsCommandTest.거른_행과_자정_확인_못_함이_0이_아니면_콘솔과_리포트에_그_수가_찍힌다` 실패 → 잡힘
    - M3 `EGW00215` 안내 제거: `KisNewsClientTest.거절은_결과_코드만_남기고_응답_문구와_토큰은_메시지에_없다` 실패 → 잡힘
    - M4 `next.isBefore(stopAt)` 멈춤 제거: `자정_행이_한_쪽보다_많으면…`의 `hasSize(1)` 실패 → 잡힘
  - 탐침 시험(커밋하지 않음, 아래 발견 1): PR 코드에서 실패, main 코드에서 통과, 제안한 한 줄 고침에서 통과(이때 수집기 모듈 시험 전체 통과)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(PR 머리 커밋 20b55067, 별도 worktree) |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-app-collector` 안 두 파일(`KisNewsCollector.java`, `KisNewsClient.java`)뿐, import·모듈 의존 변화 없음. ArchUnit(`autotrade-architecture-test`)은 빌드에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. `Walk` 레코드 모양 그대로(javadoc 문구만). 동작 변경은 D-136 "후속 고침 2" 절(docs/decisions/D-136-kis-news-snapshot.md:62-71)과 README 행에 기록 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 수집기 시험은 `Clock.fixed`(KisNewsCollectorTest.java:26-28) 사용 |
| B5 | 해당 없음 | 금액 다루는 코드 없음 |
| B6 | 실패 | 새 동작마다 시험이 있고 변이 M1~M4를 모두 잡는다. 지운·건너뛴 시험 없음. 다만 새 자정 처리가 **커서와 다른 날짜의 00:00:00**에서 이전 판의 불변식(000000을 보내지 않음, 자정 확인 못 함을 셈)을 깨는데 이를 잡는 시험이 없다(아래 발견 1) |
| B7 | 통과(risk-ops 검토 필요) | 보호 경로 `autotrade-app-collector/.../KisNewsClient.java` 수정(scripts/ci/protected-paths.txt). 실전 관련 PR이므로 risk-ops 검토와 사용자 머지·독립 QA 대상(docs/qa/pending.md 끝 줄 추가 확인) |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음. 시험 값은 `"K"`, `"S"`, `KIS-APPKEY` 같은 자리표시. CI 키 검사 exit 0 |

## 점검표 밖 발견

1. **(수정 필요) 다른 날짜의 00:00:00 커서를 그대로 보낸다 — 이전 판에서 회귀.**
   `KisNewsCollector.java:236`이 `dayStart`를 **지금 커서(`current`)의 날짜**로 잡는다. 쪽이 커서 날짜보다 앞 날로 넘어가 그 날 00:00:00 행에서 끝나면(`next` = D−1 00:00:00) `next.equals(dayStart)`가 거짓이라 아무 처리 없이 `FID_INPUT_HOUR_1=000000`을 보낸다. 이전 판(main)은 `next.toLocalTime().equals(LocalTime.MIDNIGHT)`로 날짜와 상관없이 막았다. D-136 후속 고침 2 1번 자체가 "000000은 보낼 수 없음"을 전제로 한다.
   - 재현(탐침 시험, 쪽 크기 2): 행 10-21 06:00 A, 05:00 B, 10-20 00:00:00 Y(그날 유일), 10-19 23:00 W, 22:00 V. 1회차 07:00 호출 상한 2로 끊어 훑은 구간 `[05:00:01, 07:00]`, 2회차 07:00:30 `--from 2026-10-19`.
     - PR 코드 호출: `20261021 ""`, `20261021 050001`, **`20261020 000000`**, `20261019 230000`, … / 백필 걸음 `midnightCuts=0`
     - main 코드 호출: `20261021 ""`, `20261021 050001`, `20261019 235959`, … / 백필 걸음 `midnightCuts=1`
   - 영향 `[추론]`: 그 쪽이 D−1 00:00:00 뒤의 행이 없음을 보였으므로 "000000 = 그날 끝까지"(시험 서버 `KisNewsFixtures.server`의 가정)라면 받는 행은 같다. 그러나 이 가정은 2차 자료 `[가정]`이고, 다르게 읽히면 D−1 00:00:00 행이 한 쪽보다 많을 때 나머지를 잃으면서 "자정 확인 못 함"도 0으로 남아 **조용한 손실**이 된다(이 PR이 막으려던 바로 그 종류). 새 행 없는 쪽이 D−1 00:00:01에서 끝나는 경우도 같은 갈래로 `000000`을 보낸다.
   - 고칠 방법(확인함): `LocalDateTime dayStart = next.toLocalDate().atStartOfDay();`(커서가 아니라 다음 커서의 날짜). 이 한 줄로 탐침 시험이 main과 같은 호출(`20261019 235959`)을 내고 수집기 모듈 시험 전체가 통과했다. 같은 날 경우(D−1로 넘어간 뒤 `next.isBefore(dayStart)` 판단)도 그대로다. 위 재현을 시험으로 더해(호출에 `000000` 없음, `midnightCuts`가 1) 고정할 것을 권한다.

2. (권고, 막지 않음) CI 키 검사 정규식은 대소문자를 가린다(`grep -nEI`, `-i` 없음). `"appKey": "<값>"`(camelCase)·따옴표 없는 YAML `appkey: <값>`은 안 잡힌다. gitleaks가 따로 있어 막지 않으나, 한국투자 헤더가 `appkey` 소문자라도 JSON 설정을 손으로 쓸 때 camelCase가 섞일 수 있으니 `-i` 또는 `[Kk]`를 검토할 만하다.

3. (참고) 자정 셈은 이제 "넉넉하게" 센다(쪽이 마침 00:00:00에서 끝나 다 받았어도 1). javadoc(KisNewsCollector.java:67)과 D-136 후속 고침 2 1번에 적혀 있어 문서와 코드가 맞다. 리포트 독자가 0이 아닌 값을 곧 손실로 읽지 않도록 품질 리포트 칸 설명에도 같은 말을 두면 좋다(막지 않음).

4. (확인) 새 `000001` 갈래가 끝나지 않을 가능성: `next = 00:00:01`은 `current.isAfter(00:00:01)`일 때만 고르므로 커서는 늘 엄격히 줄어든다. 전날 갈래도 `current`보다 앞. 무한 반복 없음(코드 추론, 변이 M1·탐침 실행에서 호출 수 유한 확인).

## 판정
수정 필요(1: `KisNewsCollector.java:236` `dayStart`를 `next`의 날짜로 잡고, 커서와 다른 날짜의 00:00:00에서 `000000`을 보내지 않고 자정 확인 못 함을 세는 시험 추가)
