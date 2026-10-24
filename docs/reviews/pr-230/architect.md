# architect 검토: D-136 후속 고침 3 (PR #228 QA Q1~Q3·R1)
- 대상: PR #230 / 커밋 e41e06d50261f40d782bd5f5568ce53140268384 / `git diff origin/main...HEAD` 전체(6개 파일, +102 −8)
- 점검표: B
- 직접 실행한 것(모두 PR 머리 커밋을 따로 꺼낸 detached `git worktree`에서):
  - `./gradlew build --offline` → exit 0. 테스트 결과 XML 합계 1,934건, 실패 0, 건너뜀 0
  - `bash scripts/ci/check-collector-keys.sh` → 자체 시험 통과 후 저장소 전체 exit 0. `bash scripts/ci/test-check-collector-keys.sh` 단독 → exit 0
  - CI 키 검사 자체 시험의 변이 4종(스크립트 복사본을 고쳐 자체 시험 실행): (a) `app-?(key|secret)` 길이 `{24,}`→`{40,}`, (b) `docs/reviews` 제외 제거, (c) `app-?`→`app-`(대시 없는 이름 놓침), (d) `grep -i` 제거(대소문자) → 4개 모두 자체 시험 exit 1로 잡힘
  - 수집기 변이 4종(하나씩 넣고 `:autotrade-app-collector:test`, 끝나고 되돌림, `git status` 깨끗 확인):
    - N1 옛 조건 복원 `current.isAfter(oneSecond) && pageOldest.isAfter(dayStart)` → 3건 실패(`자정_행이_한_쪽보다_많으면…`, `쪽이_커서보다_앞선_날의_00시_행에서_끝나도…`, 새 `쪽_끝이_00시_행이고_나머지가_한_쪽에_들어가면…`) → 잡힘
    - N2 자정 셈을 커서(`current`) 날짜와 비교(PR #228 QA M5) → 2건 실패(새 `쪽이_커서보다_앞선_날의_평범한_시각에서_끝나면_자정을_세지_않는다` 포함) → 잡힘
    - N3 되풀이 막기 약화 `current.isAfter(dayStart)` → 3건 실패(`자정_1초_행이_한_쪽보다_많으면_000001을_되풀이하지_않고…` 포함) → 잡힘
    - N4 `midnightCuts++` 제거 → 5건 실패 → 잡힘

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --offline` exit 0, 1,934건 실패 0(PR 머리 커밋 e41e06d5, 별도 worktree) |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-app-collector/.../KisNewsCollector.java` 한 줄 조건(242행)과 주석뿐, import·모듈 의존 변화 없음. ArchUnit(`autotrade-architecture-test`)은 빌드에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음(`Walk`·`Interval` 레코드 그대로). 동작 변경은 docs/decisions/D-136-kis-news-snapshot.md "후속 고침 3" 절(73~80행)과 README D-136 행에 기록, 코드(KisNewsCollector.java:236-248)와 일치 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 수집기 시험은 고정 시계(`seoul(...)`)와 가짜 HTTP 사용 |
| B5 | 해당 없음 | 금액 다루는 코드 없음 |
| B6 | 통과 | 새 시험 2건(KisNewsCollectorTest.java:344, 358)과 새 CI 자체 시험 스크립트. 기존 시험 2곳의 기대값 변경(221행 `hasSize(1)`→`hasSize(2)`+두 번째 호출 `000001`, 315~319행 두 번째 호출 `20261020 000001`·세 번째 `20261019 235959`)은 의도한 동작 변경(쪽 끝이 00:00:00이어도 `000001`로 한 번 더)에 맞춘 것이고, `000000`을 보내지 않음·자정 셈 1·되풀이 없음 단언은 그대로 남아 약화 아님. 변이 N1~N4와 스크립트 변이 (a)~(d) 모두 잡힘. 지운·건너뛴 시험 없음 |
| B7 | 해당 없음 | `scripts/ci/protected-paths.txt`·`.github/CODEOWNERS`의 보호 경로를 건드리지 않음(`KisNewsCollector.java`, `check-collector-keys.sh`, 새 `test-check-collector-keys.sh`는 목록 밖). CI 작업 정의(`.github/workflows/ci.yml:244`)도 그대로이고 자체 시험은 기존 단계 안에서 돈다. 보호 테스트·RealTradingGuard·주문 경로 무변경 |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음. 자체 시험의 키 모양 값은 실행 중 `printf`로 만들어 파일에 키 문자열이 남지 않음(저장소 전체 키 검사 exit 0으로 확인). 임시 저장소는 `mktemp -d`+`trap` 정리 |

## 점검표 밖 발견
1. **(권고, 막지 않음) `Walk` javadoc·리포트 안내 문구가 동작보다 뒤처짐.** KisNewsCollector.java:65-67은 `midnightCuts`를 "쪽 끝이 00:00:00 행이었는데 커서 000000을 보낼 수 없어 … 전날로 넘어간 횟수", "쪽이 마침 00:00:00에서 끝나 실제로는 다 받았어도 셈"이라고 적는다. 이 PR부터 쪽 끝이 00:00:00이면 `000001`로 한 번 더 받고, 셈은 "다음 커서가 쪽의 가장 옛 행보다 앞선 날로 넘어갈 때"(244행)다. CollectorReports.java:273 안내도 같은 표현. 다음 수집기 PR에서 문구를 맞추길 권한다(동작·시험 영향 없음).
2. **(참고) 넉넉한 셈은 남는다.** `000001` 쪽이 쪽 크기보다 적게 오고(더 옛 행이 없음) 모두 이미 본 행이면 `added == 0` → `pageOldest − 1초`로 전날로 넘어가 셈이 1 늘 수 있다(실제 손실 없음). 손실을 숨기는 쪽이 아니라 문서의 "넉넉하게 셈"과 같은 방향이라 판정 영향 없음 `[추론]`(코드 읽기, 시험으로 고정하지 않음).
3. **(확인) 호출 증가 상한.** 다시 받기는 `current.isAfter(oneSecond)`일 때만이고 `000001` 쪽을 받은 뒤의 커서는 `oneSecond`와 같아 같은 날 두 번째 `000001`이 생기지 않는다(N3로 시험이 고정함을 확인). 자정 하나에 최대 1번 늘어난다는 D-136 후속 고침 3 1번 문장과 일치.
4. **(확인) 자체 시험 연결.** `check-collector-keys.sh`가 `COLLECTOR_KEYS_SELF_TEST` 기본값 1에서 자체 시험을 먼저 부르고, 자체 시험은 0으로 다시 부르므로 재귀 없음. `git init -b`는 git 2.28 이상 필요(CI ubuntu 러너·로컬 2.43 충족).

## 판정
승인
