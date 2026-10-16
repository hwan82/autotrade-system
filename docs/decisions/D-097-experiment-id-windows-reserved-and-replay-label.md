# D-097 실험 ID의 Windows 예약 이름 거절과 리플레이 실행 방식 표시(PR #82 독립 QA R1·R2)

- 날짜: 2026-10-16
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 실험실 화면·실험 ID 검사만 바꾸고 실전 경로·보호 테스트는 건드리지 않는다.
- 담당: ui-dev. 교차 검토: architect(`docs/reviews/pr-<번호>/architect.md`). `ExperimentId`는 sim-engine 모듈 값이라 architect 검토 범위에 넣는다.
- 근거: `docs/reviews/gates/stage-5.md` 1차 권고 R1·R2(재판정에서도 남음), [D-074](D-074-ui-experiment-registration.md), PR #82 architect 검토 뒤 남은 후속(쓰기 실패 정리 경로 시험 없음, 비검사 예외는 정리 안 함)

## 결정

1. **Windows 예약 이름 거절**(QA R1): `ExperimentId`가 `CON`, `PRN`, `AUX`, `NUL`, `COM0`~`COM9`, `LPT0`~`LPT9`를 거절한다. 실험 ID 형식이 대문자·숫자·`-`뿐이라 확장자 붙은 꼴(`CON.TXT`)과 위첨자 숫자(`COM¹`)는 형식에서 이미 막히고, 예약 이름을 포함하기만 한 ID(`EXP-CON`, `COM10`, `NUL-1`)는 받는다 `[사실]`(근거: Microsoft Learn "Naming Files, Paths, and Namespaces"의 예약 이름 목록, https://learn.microsoft.com/windows/win32/fileio/naming-a-file). 실험 생성 화면(`ExperimentRegistrations`)은 폴더를 만들기 전에 `ExperimentId`로 검사해 미리 보기·등록 모두 400과 이유 문구를 준다.
2. **실행 방식 표시**(QA R2): 실험 목록 "방식"과 실험 상세 "실행 방식"이 `clockMode`가 `REPLAY`면 데이터 버전 표지로 나눠 보여 준다. `#DAILY-PATH-`가 들어 있으면 "리플레이(일봉 경로)", `REC-`로 시작하면 "녹화 리플레이", `SYNTH-`로 시작하면 "리플레이(합성 시세)", 그 밖은 "리플레이". `REALTIME`은 "실시간 모의" 그대로. 화면(`format.js` `clockModeLabel`)만 바꾸고 API·DB `clock_mode` 값은 그대로 둔다.
3. **등록 파일 쓰기 실패 정리**(PR #82 남은 후속): 임시 파일 쓰기·옮기기가 `IOException`뿐 아니라 비검사 예외로 실패해도 임시 파일과 새 폴더를 지우고 원래 예외를 던진다. 시험은 쓰기 함수를 바꿔 끼우는 패키지 전용 생성자로 확인한다.

## 대안

| 대안 | 판단 |
|---|---|
| ① 예약 이름 검사를 `ExperimentId`에 둠(채택) | 실행 명령이 읽는 등록 파일, 화면 등록, 결과 불러오기가 모두 같은 규칙을 쓴다. QA가 "`ExperimentId`와 함께" 권함 |
| ② 실험 생성 화면(`ExperimentRegistrations`)에만 둠 | 손으로 쓴 등록 파일은 실행 명령에서 폴더 만들기 때 Windows 오류로 늦게 실패한다. 버림 |
| ③ 실행 방식에 새 API 값(`dataKind` 등)을 더함 | 계약(ADR-005 5장)과 결과 묶음 스키마를 함께 바꿔야 한다. 표시 차이만 필요해 버림. 데이터 버전 표지 규칙이 바뀌면 되돌릴 조건 |
| ④ 실행 방식 표시는 그대로, 데이터 칸만 봄 | QA R2가 헷갈린다고 지적한 상태 그대로. 버림 |

되돌릴 조건: 데이터 버전 표지(`DAILY-PATH-`, `REC-`, `SYNTH-`)를 바꾸는 sim-engine 변경이 생기면 `clockModeLabel`도 같이 고친다. 결과 DB 저장(UI-02 남은 이연) 때 데이터 종류 열이 생기면 그 값으로 바꾼다.

## 위험과 한계

- 이미 예약 이름으로 만든 실험은 없다(저장소 검색, Windows에서는 폴더를 만들 수 없음) `[사실]`. 리눅스에서 만든 `CON` 결과 묶음이 있다면 이제 불러오기가 거절한다.
- 표시 규칙은 데이터 버전 문자열에 기대므로 표지가 바뀌면 "리플레이"로만 보인다(틀린 이름이 아니라 덜 구체적인 이름).
- 화면 JS는 자동 시험이 없다(D-026 한계, QA R3 Playwright CI는 이 결정 범위 밖). 표시 규칙은 node로 다섯 경우를 확인했다.

## 검증

- `ExperimentIdTest`: 예약 이름 10종 거절, 포함하기만 한 ID 8종 받음, 형식 위반 거절.
- `ExperimentRegistrationsTest`: 예약 이름 미리 보기·등록 거절과 폴더 미생성, 포함 ID 등록, `IOException`·비검사 예외 쓰기 실패 뒤 폴더 정리와 같은 ID 재등록.
