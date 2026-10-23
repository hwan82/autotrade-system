# architect 검토: LIVE00 시험 운용 결과·리포트, 점검표 주간 점검 명령 고침(ID 따옴표·gc 기록 데몬)
- 대상: PR #227 / 커밋 e44de04d / main 대비 diff (`git diff origin/main...HEAD`: 9개 파일, +1509/-4. 코드·빌드 스크립트·스키마 변경 없음. `docs/runbooks/stage-6-start-checklist.md`, `docs/strategy/stage-6-plan.md`, `experiments/EXP-20261020-LIVE00/` 아래 새 파일 7개)
- 점검표: B
- 직접 실행한 것: `./gradlew build` → 종료 코드 0. `sha256sum detail.txt live-detail.txt` → 둘 다 `0a847f83…b36f`로 같고 `inputs.yaml`의 `detail-sha256`과 일치, `diff` 차이 없음. `detail.txt`에서 블랙리스트 종목 035720 체결 0건, `rejected … RISK:BLACKLIST` 9건 확인.

## 변경 내용 [사실]
- `experiments/EXP-20261020-LIVE00/`: 실행기 출력(`inputs.yaml`, `results.md`, `detail.txt`, `live-detail.txt`, `judgement.md`, `weekly/2026-10-23.md`)과 사람이 쓴 `report.md`. 사전 등록 파일 `experiment.yaml`(main에 이미 있음)은 바꾸지 않았다.
- `docs/runbooks/stage-6-start-checklist.md` 3장: `$id` 빈 값 확인 줄 추가, `-Pexperiment=$id`를 `"-Pexperiment=$id"`로, gc 기록 삭제 전 `.\gradlew --stop`, 걸린 시간 실측(4일 7분 29초) 반영.
- `docs/strategy/stage-6-plan.md` 7장: LIVE00 실측과 8주차 추정(주간 점검 약 75분·판정 약 2시간 `[추론]`) 추가.

## 수치 대조 [사실]
- 리포트 "받음 ÷ 녹화": 10-20 781530/1008093=77.5%, 10-22 969850/981044=98.9%, 10-23 1146898/1157786=99.1% — `weekly/2026-10-23.md` 1장 표와 일치.
- "줄 약 820만" = 녹화(boards) 4,228,888 + 입력 기록 3,980,303 ≈ 821만. "8주차 약 75분" = 7.5분 × 40/4일, 단순 비례로 산식 일치(`[추론]` 표시 있음).
- "설명 없음 8줄(10-20)", "재기동 빠짐 234,697줄", 계좌 대조 kr-orb-30 5 대 0·kr-volume-surge 10 대 0·kr-book-imbalance 36 대 43 — 주간 점검 1·3장과 일치.
- 참조 결정 D-115, D-118, D-122 파일이 `docs/decisions/`에 있다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, EXIT=0 |
| B2 | 해당 없음 | Java·Gradle 파일 변경 없음. 모듈 의존·ArchUnit 규칙 변경 없음(빌드에서 ArchUnit 통과) |
| B3 | 해당 없음 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 런북의 명령 인자 표기(`"-Pexperiment=$id"`)는 같은 Gradle 속성 이름을 쓰며 계약 변경이 아님 |
| B4 | 통과 | 코드 변경 없음. 실험 산출물 자체가 결정론을 보여 준다: `detail.txt`와 `live-detail.txt` 바이트 일치(sha256 동일, `inputs.yaml` determinism·detail-sha256 줄과 일치). 골든 테스트는 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 처리 코드 변경 없음(산출물의 금액은 정수 KRW 표기) |
| B6 | 해당 없음 | 새 동작 코드 없음. 테스트 삭제·건너뛰기 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 코드·장애 대응을 건드리지 않음. 변경 파일 중 `scripts/ci/protected-paths.txt`에 있는 경로 없음(목록의 docs 항목은 project-instructions·qa·D-086·ADR-002뿐). 블랙리스트는 실험 결과로만 확인(체결 0, 거절 9) |
| B8 | 통과 | diff에서 appkey·secret·token·password·계좌번호 형식 검색 결과 없음. 계좌 ID는 내부 모의 계좌 이름(`EXP-…-A01` 등)뿐 |

## 점검표 밖 발견
- [사실, 차단 아님] `weekly/2026-10-23.md` 머리와 4장에 **"수집: 멈춤 의심"**(KRX 일봉 스냅샷 마지막 2026-10-21, 10-22·10-23 수집 안 됨)이 찍혀 있는데 `report.md`에는 이 내용이 없다. 같은 파일 4장 설명대로 6단계 실시간 모의 계좌는 일봉 스냅샷을 입력으로 쓰지 않아 LIVE00·LIVE01 결과에는 영향이 없지만, 4단계 실험·선정 입력 쪽 공백이고 사용자가 수집기를 다시 돌려야 할 수 있다. 리포트 3장이나 LIVE01 준비 항목에 한 줄로 넣고 사용자에게 알리기를 권한다.
- [추론, 차단 아님] 런북 3장 첫 줄 `if (-not $id) { "…먼저 넣으세요…" }`는 메시지만 찍고 이어지는 명령을 막지 않는다. 블록을 통째로 붙여 넣으면 빈 ID로 Gradle이 돌아 종료 코드 1이 나온다(이번 사고와 같은 결과지만 원인은 화면에 보이므로 해는 작다). 막고 싶으면 안내 문구에 "이 줄이 경고를 찍으면 나머지를 실행하지 않는다"를 붙이거나 블록을 나누는 정도로 충분하다. PowerShell에서 `throw`/`return`이 붙여 넣은 여러 줄 입력을 어디까지 멈추는지는 이 리눅스 환경에서 확인 못 했다.
- [추론, 차단 아님] 따옴표 없는 `-Pexperiment=$id`가 빈 값으로 넘어간 원인은 리포트대로 `[추론]`이다. 따옴표로 감싸는 방식은 원인과 상관없이 문자열 한 개로 넘기므로 고친 방향은 맞다. Windows에서 다시 재현해 확인하지는 못했다(판정 영향 없음).
- [사실, 참고] 주간 점검 결과 머리의 `code: … dirty`는 리포트 2장에 원인(저장소 안 `weekly-run.txt`)이 적혀 있고, 리플레이 확인·판정은 깨끗한 트리였다고 적혀 있다. 이 주장은 사용자 PC 실행 기록이라 검토자가 확인할 수 없고, `inputs.yaml`의 code 줄에는 dirty 표시가 없다는 것만 확인했다.

## 판정
승인
