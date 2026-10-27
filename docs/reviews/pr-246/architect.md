# architect 검토: 7단계 묶음 0 문서 정리(D-144): D-037 근거 보충, Q-047 닫기, TST-06 대응표, K10 검토, 장애표 #13·RSK-13 문구
- 대상: PR #246 / 커밋 `67585eac`(브랜치 `claude/project-thread-npk3vh`, 앞 커밋 `ea28d27e` 포함) / `git diff origin/main...HEAD` 전체(8개 파일, 모두 `docs/`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (HEAD `67585eac`를 별도 worktree에 꺼내 실행): **BUILD SUCCESSFUL in 4m 21s**, 138 tasks, 테스트 결과 XML 합계 2,005건 실패 0·건너뜀 0. 같은 작업 폴더에서 처음 두 번은 Maven Central 429(Too Many Requests)로, 세 번째는 같은 폴더에서 동시에 돌던 다른 빌드와 테스트 결과 XML 쓰기가 겹쳐 실패해 별도 worktree로 다시 돌렸다(코드 원인 아님).
  - `git diff --name-status origin/main...HEAD`: `docs/adr/ADR-006…`, `docs/decisions/D-144-…`(새), `docs/decisions/README.md`, `docs/research/k10-os-credential-store-review.md`(새), `docs/spec/autotrade-spec.md`, `docs/team/broker-and-fault-design.md`, `docs/team/stage-7-readiness-plan.md`, `docs/team/tst-06-checklist-map.md`(새). 코드·설정·스키마·테스트 변경 없음.
  - `scripts/ci/protected-paths.txt`·`.github/CODEOWNERS` 대조: 바뀐 파일 중 보호 경로 없음.
  - 추가된 상대 링크 전부 존재 확인(빠진 링크 0). `git grep D-143 HEAD -- docs ':!docs/reviews'` 0건(번호 바꾼 뒤 남은 참조 없음).
  - 열린 PR과 시험 병합: #239·#243 충돌 없음, #245는 `docs/decisions/README.md` 결정 표 같은 위치(D-142 다음 줄)에 행을 넣어 충돌. 결정 번호는 #245 D-143, #239 D-141, 이 PR D-144로 겹치지 않음.
  - 문서가 인용한 코드 위치를 직접 읽어 대조: `RuntimeConfigStore.java:71-78`, `ConfigLoader.java:75-77`, `SelectionCriteria.java:24-38`, `TradingRules.java:90-98`, `IdempotencyKey.java:20`, `MarketRules.java:46`, `OperatorPasswordHash.java:17-18`, `FileKakaoTokenStore.java:41,78-79`, `ControlServer.java:520-535`, `KisPlugin.java:93`, `RealPathReflectionTest.java:111,114`, `LoadedConfig.java:11`, ADR-001 165·232행. `order.max-signal-age`·`STALE_SIGNAL`·`CircuitBreaker` 검색 0건, `hash-password`·`configWarning`·`BROKER_NOT_CONNECTED`·`TossRateLimiter` 존재 확인.
  - TST-06 대응표 3·4장 행을 세어 합계 대조: 장중 16(채택 11·대체 5, 부분 12·미충족 4), 스크리너 18(채택 6·대체 12, 충족 4·부분 12·미충족 2), 합 34(충족 4·부분 24·미충족 6, 채택 17·대체 17). 문서 합계 표·D-144 ③과 일치. 충족 4행만 경고 "아니오", I-15는 B-01, 나머지 29행은 모두 W-ID가 있어 TST-06 "미충족 항목 경고"를 빠짐없이 덮음. 5.2·5.3의 원본 항목 열과 3·4장 경고 열이 서로 맞음.
  - 근거 대조: QA [pr-25](../pr-25/qa.md) 4(D-037 근거 섞임, D-023 문구 좁힘), [pr-237 architect](../pr-237/architect.md) 참고 2(장애표 #13), [pr-237 risk-ops](../pr-237/risk-ops.md) 권고 3(RSK-13 인수 조건 한정), PR #47 머지 시각 2026-10-11(`gh api`)과 D-059 날짜, broker-and-fault-design.md 7.1 1-0·1-T·1-TR 행.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | HEAD `67585eac` `./gradlew build` BUILD SUCCESSFUL, 테스트 2,005건 실패 0(위 실행 기록). 문서만 바뀐 PR이라 빌드 결과는 main과 같아야 하며 그렇게 나옴 |
| B2 | 해당 없음 | 빌드 스크립트·모듈·ArchUnit 규칙 변경 없음(diff 8개 파일 모두 `docs/`) |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 기획서 변경(RSK-13 인수 조건 범위 한정, TST-06 링크)은 v14.60 변경 이력(`autotrade-spec.md:20`)과 결정 D-144가 함께 있음. K10 추천안의 코드(경고)는 이 PR에 없고 별도 실전 관련 PR K10-a로 남김(`k10-os-credential-store-review.md` 7장) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 삭제·건너뜀 없음. 인수 조건은 좁혔지만 OFF 계좌는 기존 "확인 실패 PANIC에서 OFF 계좌는 OFF이고 주문 세션 허가 발급 0건 테스트"가 그대로 다룸(같은 RSK-13 인수 조건 칸) |
| B7 | risk-ops 검토 필요 | 주문 경로 코드·RealTradingGuard·블랙리스트·보호 경로는 건드리지 않지만, 장애 대응 문서(`broker-and-fault-design.md` 6장 #13 행)와 PANIC 규칙(기획서 RSK-13 인수 조건) 문구를 바꾼다. 이것이 지침 10장의 "장애 대응을 건드리는 PR"(사용자 머지·독립 QA)에 드는지는 risk-ops가 판정해야 한다. architect가 본 범위에서는 두 문구 모두 D-140 ①④를 옮긴 것이고 새 규칙·완화는 없음 |
| B8 | 통과 | 새 문서에 키·계좌번호·토큰 값 없음(경로·키 이름·명령 예시만). K10 문서의 `icacls` 예시는 자리표시자 `<사용자>`·`<폴더>` |

## 점검표 밖 발견

모두 비차단(참고)이다.

1. (참고) `docs/research/k10-os-credential-store-review.md` 4장 B·C를 버린 이유 2번: "D-086은 … 외부 jar를 허용 목록으로 묶고, 프로세스 실행 허용 목록(`ALLOWED_PROCESS_LAUNCHERS`)은 빈 집합이다(`RealPathReflectionTest.java:111,114`)". 111행은 맞지만 114행은 `ALLOWED_SERVICE_LOADERS`(ServiceLoader 허용 목록)다. 결론에는 영향 없으나 다음 갱신 때 행 번호나 설명을 맞추기를 권한다.
2. (참고) `docs/team/tst-06-checklist-map.md` I-8 근거: "`OrderState.java:21`, 주석 '전이 규칙은 별도 작업에서 정의'". 21행은 `public enum OrderState`이고 해당 주석은 4-5행이며 원문은 "전이 규칙(허용 전이표, 상태머신)은 가상·실전이 같은 코드로 공유하며 별도 작업에서 정의한다"다. 내용은 맞고 인용 위치·문구만 다르다.
3. (참고) 열린 PR #245와 `docs/decisions/README.md` 결정 표에서 같은 자리(D-142 다음)에 행을 넣어 시험 병합이 충돌한다. 나중에 머지하는 쪽이 D-143 → D-144 순서로 두 행을 모두 남기면 된다(번호는 이미 겹치지 않음).
4. (참고) PR 제목·본문은 아직 "D-143"이다. 커밋 `67585eac`로 D-144가 됐으므로 PR 본문의 결정 링크(`docs/decisions/D-143-…` → `D-144-…`)와 제목을 맞춰야 PR 본문 링크가 깨지지 않는다.
5. (참고) 닫힌 미확정 표에서 Q-047(2026-10-11) 행이 Q-009(2026-10-14) 다음에 들어가 날짜 순서가 아니다. 표가 원래 엄격한 순서가 아니어서(예: Q-055 다음 Q-009) 고칠 필요는 없다.

## 판정
승인

- architect 범위(B1~B6·B8)에서 수정할 것은 없다. B7은 risk-ops 검토 대상이며, 이 PR이 지침 10장 실전 관련 PR(사용자 머지)인지는 risk-ops 판정을 따른다.

## 재검토 (2026-10-26)
- 대상: PR #246 / 커밋 `22123a6d`(앞 검토 `67585eac` 뒤 추가된 `4958c489` architect 원문, `3d34b906` risk-ops 원문, `22123a6d` risk-ops 수정 필요 반영) / `git diff origin/main...HEAD` 전체(11개 파일, 모두 `docs/`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (HEAD `22123a6d`를 별도 worktree `/tmp/wt-arch-246`에 꺼내 실행): **BUILD SUCCESSFUL in 4m 42s**, 138 tasks, 테스트 결과 XML 합계 2,005건 실패 0·오류 0·건너뜀 0.
  - `git diff --stat origin/main...HEAD`: 앞 검토의 8개 파일 + `docs/qa/pending.md`(1줄 추가), `docs/reviews/pr-246/{architect,risk-ops}.md`(원문). 코드·설정·스키마·테스트 변경 없음. 보호 경로(`scripts/ci/protected-paths.txt`) 해당 파일 없음.
  - `git show 22123a6d` 단어 단위 비교로 바뀐 내용 전부 확인: D-144 상태 줄·③·⑥, README D-144 행, 기획서 v14.60 이력·RSK-13 인수 조건·TST-06 행, 대응표 1장 3·4번·I-8·S-2·S-4·S-5·W-21·6장 대안 A, K10 인용 줄, pending.md 줄.
  - `origin/main`과 시험 병합(`git merge-tree`): 충돌 없음(main에 새 커밋 없음).
  - 대응표 3·4장 행을 스크립트로 다시 셈: 장중 16(부분 12·미충족 4, 채택 11·대체 5), 스크리너 18(충족 1·부분 15·미충족 2, 채택 6·대체 12) → 합 34(충족 1·부분 27·미충족 6). 충족은 S-15 하나(경고 "아니오"), 부분·미충족 33행은 모두 W-ID 또는 B-01이 있음(경고 빠짐 0).
  - 인용 대조: `RealPathReflectionTest.java:100`(`EXTERNAL_ALLOWLIST`, 외부 jar 허용 목록)·`:111`(`ALLOWED_PROCESS_LAUNCHERS = Set.of()`), `OrderState.java:4-5`(주석 원문 일치), `autotrade-app-engine/build.gradle.kts:19-28`(`autotrade-selection` 의존 없음, 대응표 S-4·S-5 [사실] 맞음).
  - 기획서 RSK-13 인수 조건 새 문구: "2차 확인 실패·잠금·비밀번호 미설정이면 모든 계좌(OPEN·ACTIVATING·PROTECTIVE·OFF)에서 청산(②~⑥) 미수행 테스트, 같은 조건에서 OPEN·ACTIVATING 계좌는 ① 고정 … PROTECTIVE 계좌는 PROTECTIVE 유지 테스트". 같은 행 본문(① 고정은 OPEN·ACTIVATING에만, OFF는 OFF 그대로)과 D-140 ①과 모순 없음. risk-ops 발견 1의 빈칸(PROTECTIVE 청산 미수행)이 채워짐.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | HEAD `22123a6d` `./gradlew build` BUILD SUCCESSFUL, 테스트 2,005건 실패 0(위 실행 기록) |
| B2 | 해당 없음 | 빌드 스크립트·모듈·ArchUnit 규칙 변경 없음(diff 11개 파일 모두 `docs/`) |
| B3 | 실패 | 인터페이스·스키마·설정 키·REST 변경은 없고 기획서 변경은 v14.60 이력(`autotrade-spec.md:20`)과 D-144가 함께 있다. 그러나 이 PR이 새로 만든 대응표의 **4장 합계 표가 고친 행과 맞지 않는다**: `docs/team/tst-06-checklist-map.md:96-98`이 아직 "스크리너 18 \| 4 \| 12 \| 2", "합 34 \| 4 \| 24 \| 6"이다. 행(S-2·S-4·S-5를 부분으로 바꿈)·1장 3번(`:12` 충족 1·부분 27)·D-144 ③·6장 대안 A(`:155` 33개)는 1·27·6인데 합계 표만 앞 판 그대로다(아래 발견 1) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 삭제·건너뜀 없음. RSK-13 인수 조건은 이번 고침으로 앞 판보다 넓어짐(PROTECTIVE 청산 미수행·PROTECTIVE 유지 추가) |
| B7 | risk-ops 검토 필요 | 장애표 #13 문구, RSK-13 PANIC 인수 조건, TST-06 이행 방식을 바꾼다. risk-ops 1차 판정이 "실전 관련 PR(사용자 머지)"이고 D-144 상태 줄·`docs/qa/pending.md:84`도 그렇게 고쳤다. 수정 반영분(발견 1·2)의 재검토는 risk-ops 몫 |
| B8 | 통과 | 바뀐 문서에 키·계좌번호·토큰 값 없음(pending.md 줄 포함 눈으로 확인) |

## 점검표 밖 발견

1. **(수정 필요) `docs/team/tst-06-checklist-map.md` 4장 합계 표가 갱신되지 않았다.** `:96-98`을 "장중 16 \| 0 \| 12 \| 4 \| 0 \| 11 \| 5 \| 0", "스크리너 18 \| 1 \| 15 \| 2 \| 0 \| 6 \| 12 \| 0", "합 34 \| 1 \| 27 \| 6 \| 0 \| 17 \| 17 \| 0"으로 맞춘다(행을 직접 센 값). 앞 architect 검토가 "문서 합계 표·D-144 ③과 일치"로 확인한 표라, 이번 수정으로 같은 문서 안에서 두 숫자(1장 3번 1·27·6, 4장 합계 4·24·6)가 엇갈리게 됐다. 이 표는 묶음 H 화면과 7단계 독립 QA의 입력이고 risk-ops 발견 2도 "합계를 맞춘다"를 요구했다.
2. (참고) 앞 검토 참고 1·2(K10 `RealPathReflectionTest` 인용 줄, 대응표 `OrderState` 인용)는 고쳐졌고 새 인용이 코드와 맞다.
3. (참고) 앞 검토 참고 4(PR 제목·본문의 D-143)는 저장소 밖이라 이 검토에서 확인하지 않았다. 머지 전 PR 본문 결정 링크가 `D-144-…`인지 확인하기를 권한다.
4. (참고) 기획서 v14.60 이력 행을 새 판 번호 없이 고쳐 썼다. 아직 머지 전 같은 PR 안의 판이라 문제 삼지 않는다.

## 판정
수정 필요(1. TST-06 대응표 4장 합계 표 `tst-06-checklist-map.md:96-98`이 충족 4·부분 24로 남아 행·1장·D-144의 충족 1·부분 27과 어긋남)

## 재검토 2 (2026-10-26)
- 대상: PR #246 / 커밋 `cbd676e6`(앞 재검토 `22123a6d` 뒤 추가된 `a83d1891` architect 재검토 원문, `b1c27fa7` risk-ops 재검토 원문, `cbd676e6` TST-06 대응표 4장 합계 표 갱신) / `git diff origin/main...HEAD` 전체(11개 파일, 모두 `docs/`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (HEAD `cbd676e6`를 별도 worktree `/tmp/wt-arch-246b`에 꺼내 실행): **종료 코드 0(BUILD SUCCESSFUL)**, 테스트 결과 XML 합계 2,005건 실패 0·오류 0·건너뜀 0.
  - `git diff --stat origin/main...HEAD`: 11개 파일, 533줄 추가·11줄 삭제, 앞 재검토와 같은 파일 목록. 코드·설정·스키마·테스트 변경 없음. 보호 경로 해당 파일 없음.
  - `git show cbd676e6`: `docs/team/tst-06-checklist-map.md` 2줄만 바뀜(스크리너 18 `4|12|2` → `1|15|2`, 합 34 `4|24|6` → `1|27|6`). 다른 파일 변경 없음.
  - 대응표 3·4장 행을 스크립트로 다시 셈: 장중 16(부분 12·미충족 4, 채택 11·대체 5), 스크리너 18(충족 1(S-15)·부분 15·미충족 2, 채택 6·대체 12) → 합 34(충족 1·부분 27·미충족 6, 채택 17·대체 17). 4장 합계 표(`tst-06-checklist-map.md:96-98`), 1장 3번(`:12`), D-144 ③과 모두 일치. 경고 열이 빈 행 0.
  - `docs/` 안(`docs/reviews/` 제외)에서 옛 개수 "충족 4"·"부분 24" 검색 0건.
  - `origin/main`(`53482a56`)과 시험 병합(`git merge-tree`): 충돌 없음.
  - `docs/qa/pending.md:83` PR #246 QA 요청 줄 있음(요청 "PR #246 검토해줘", 보고서 `docs/reviews/pr-246/qa.md`).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | HEAD `cbd676e6` `./gradlew build` 종료 코드 0, 테스트 2,005건 실패 0(위 실행 기록) |
| B2 | 해당 없음 | 빌드 스크립트·모듈·ArchUnit 규칙 변경 없음(diff 11개 파일 모두 `docs/`) |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 기획서 변경은 v14.60 이력과 D-144가 함께 있음. 앞 재검토 실패 사유(4장 합계 표 불일치)는 `tst-06-checklist-map.md:96-98`이 행을 센 값(충족 1·부분 27·미충족 6)과 맞아 해결 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 코드 없음, 테스트 삭제·건너뜀 없음 |
| B7 | risk-ops 검토 필요 | 장애표 #13 문구, RSK-13 PANIC 인수 조건, TST-06 이행 방식을 바꾸는 실전 관련 PR(risk-ops 판정, D-144 상태 줄). risk-ops 재검토 원문 판정은 "승인"(`docs/reviews/pr-246/risk-ops.md`). 이번 커밋(합계 표 숫자)은 risk-ops 재검토가 이미 센 값과 같음 |
| B8 | 통과 | 이번 커밋은 숫자 2줄뿐이고 키·계좌번호·토큰 없음 |

## 점검표 밖 발견

1. (참고) 앞 재검토 참고 3(PR 제목·본문의 D-143 → D-144)은 저장소 밖이라 이번에도 확인하지 않았다. 머지 전 PR 본문 결정 링크를 확인하기를 권한다.
2. (참고) 이 PR은 실전 관련 PR이라 architect 승인은 머지 권한이 아니다. 지침 10·11장대로 독립 QA("PR #246 검토해줘") 판정과 사용자 확인 뒤 사용자가 머지한다.

## 판정
승인
