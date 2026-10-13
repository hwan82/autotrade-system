# EXP-20261013-REC01 보고서: 실녹화 실험 두 번 실행 확인 (3단계 QA C1 증거)

등록 파일: [experiment.yaml](experiment.yaml)(main `b69d57c`에 커밋된 그대로, SHA-256 `d155cf88…e5c33071`가 실행 PC 파일과 같음). 녹화 파일 자체는 올리지 않는다(개인 연구용 저장, 재배포 금지, ADR-003·PR #72 architect O2).

## 결론

- [사실] 같은 녹화·등록 파일로 두 번 실행한 상세 보고서가 바이트까지 같다. 1회차 `detail.run1.txt`(실행 직후 사용자가 복사한 사본)와 2회차 `detail.txt`를 둘 다 커밋했고, SHA-256이 둘 다 `68d2fd3d429fcc3fabf11d4f4dbdc43bed36f9e4c0bc0263e15333703288d806`(12,559바이트)이다. 저장소에서 `sha256sum experiments/EXP-20261013-REC01/detail*.txt`로 다시 확인할 수 있다. 각 실행 안에서도 병렬 2와 병렬 1 결과가 바이트까지 같다(`inputs.yaml` `determinism`).
- [사실] 9개 프로필 Run이 모두 `COMPLETED`로 끝났다(`results.md`). 체결 0건인 Run 4개(orb-30, pdh-breakout, volume-surge, ma-trend)는 하루 안에 진입 조건이 서지 않은 것이다(ma-trend는 이동평균에 여러 날이 필요).
- [사실] 입력: 녹화 2026-10-12 1일, 528,156건 + 표시 없는 재기동 경계(seq 410641)에 끼운 RECORDER_RESTART 3건 = 528,159건, `complete: true`, `truncated: false`, 구간 2개, 시세 공백 9건(재기동 3 + 끊김 6). 녹화 파일 SHA-256 `860da4e7…b400eeeb64`.
- [사실] 시가 단일가 처리(QA S2): 장전(08:50~) 신호로 낸 매수 기준선(A01) 주문 3건이 모두 그 종목 첫 체결가로 슬리피지 0에 체결됐고, 그 값이 키움 ka11081 KRX 단독 일봉의 시가와 같다: 035420 182,400, 000660 1,640,000, 005930 260,000([2026-10-12.md](../stage-2-replay/2026-10-12.md) 원본 대조표). 한계: 그 일봉도 녹화와 같은 키움 출처이고, 2단계 G-1(원본 대조)은 조건부이며 HTS 독립 대조가 끝나지 않았다(005930 시가는 영웅문 기록이 260,500·260,000으로 엇갈림, 같은 문서 결론 절). 따라서 이 일치는 "첫 체결이 그날 시가 단일가 체결이다"는 가정과 맞는다는 정도의 근거다. BOOK-V2였다면 08:50 예상 호가로 체결됐을 주문이다.
- [사실] 지표: 관측 1일이라 모든 Run의 샤프·CAGR이 `-`(판정 불가, QA R1)다.
- 이 결과는 3단계 완료 기준의 증거일 뿐이며 성과 비교·기법 선택에 쓰지 않는다(관측 1일, 74분 녹화 공백). 투자 자문이 아니다.

## 실행 환경

- 실행 PC: 사용자 Windows PC, JDK 21.0.12(Eclipse Adoptium), 저장소 main `b69d57c`(PR #74 머지).
- 명령(두 번, 2026-10-13 22시대 KST): `.\gradlew :autotrade-app-experiment:runRecordingExperiment -Pexperiment=EXP-20261013-REC01 -PverifyDeterminism -PallowDuringSession`. 미국 정규장 보호 구간이라 첫 시도는 종료 코드 4로 멈췄고(결과 파일 없음, D-063 동작 확인), 실전 엔진이 이 PC에서 돌지 않으므로(7단계 전) 해제 옵션으로 다시 돌렸다.
- 녹화 위치: 기본값 `~\.autotrade\recordings\KRX\2026-10-12`.
- 코드 표시 `b69d57c… dirty`: 실행 PC 저장소에 `experiments/` 밖 커밋하지 않은 변경이 있었다는 표시다. 내용은 아래 절(추적하지 않는 보고서 파일 하나, 코드 변경 없음).

## 실행 PC 작업 트리(dirty 표시)

[사실, 사용자 제공 2026-10-13 22:49 KST] `git status --short -- . ":!experiments"` 출력은 `?? replay-2026-10-12.md` 한 줄이다. 저장소 루트에 남은, 추적하지 않는 2단계 리플레이 보고서 파일이며 빌드 입력(소스·빌드 스크립트·리소스)이 아니다. 수정된 추적 파일은 없으므로 실행한 코드는 main `b69d57c`와 같다. 이 파일은 저장소에 올리지 않는다.

## 파일

- `inputs.yaml`, `results.md`, `detail.txt`: 2회차 실행 원 출력(실행기가 쓴 그대로, 줄바꿈 LF).
- `detail.run1.txt`: 1회차 실행의 `detail.txt` 사본. 1회차 `inputs.yaml`·`results.md`는 2회차가 덮어써서 남지 않았다(둘 다 `detail.txt` 내용과 결정론 확인으로 만들어지므로 같은 `detail.txt`면 같다고 본다 `[추론]`).
- 다음 실녹화 실험은 작업 트리를 깨끗하게 한 뒤 돌린다(architect PR #75 권고).
