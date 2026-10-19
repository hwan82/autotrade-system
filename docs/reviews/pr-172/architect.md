# architect 검토: 실전 엔진 기동 실패 원인 한 줄로 접기·표준 오류 전체 고정 시험(D-112, PR #169 QA Q1·R2)
- 대상: PR #172 / 브랜치 `claude/project-thread-blfvr9` / 커밋 `1556952` / main 대비 diff(`git diff origin/main...HEAD`, 병합 기준 `f81f2f7`. 이후 main `dc41d07`은 `docs/qa-out-status.md`만 바꿔 겹치는 파일 없음, `git merge-tree` 충돌 없음)
- 점검표: B
- 직접 실행한 것:
  - 별도 clone(`/home/claude/review-pr172-architect`)에서 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(4m 7s). 시험 결과 XML 합계 1588건, 실패 0, 오류 0, 건너뜀 0. `EngineMainTest` 25건 모두 통과(새 시험 3건 `#기동_실패_표준_오류는_첫머리부터_줄_끝까지_정해진_한_줄이다`, `#기동_실패_원인에_줄바꿈이_있어도_로그_ERROR와_표준_오류는_한_줄이고_예외_흐름은_원문을_남긴다`, `#기동_실패_원인의_줄바꿈은_세_종류_모두_한_줄로_접는다` 포함).
  - 변이 시험(운영 코드 `EngineMain.java`를 직접 바꾸고 `:autotrade-app-engine:test --tests EngineMainTest`, 매번 `git checkout`으로 원복, 모두 컴파일은 되고 시험이 실패함을 로그로 확인):
    - M1 `oneLine`이 늘 원문 반환(접기 없음) → **잡힘**(2건 실패)
    - M2 `message.strip()` 제거 → **잡힘**(1건, 끝 `\r`이 " / "로 남음)
    - M3 `LINE_BREAK_RUN`을 `\R`로(둘레 공백·연속 줄바꿈 묶지 않음) → **잡힘**(2건)
    - M4 프로그램 오류 `detail`에만 `oneLine` 빠짐 → **잡힘**(2건)
    - M5 표준 오류 `println`→`print`(QA P3) → **잡힘**(2건)
    - M6 줄바꿈 없는 원인도 `strip()` → **잡힘**(1건, 앞뒤 공백 유지 단언)
    - M7 로그 예외 흐름에 원래 예외 대신 새 예외 → **잡힘**(2건, `getThrown()).isSameAs(codeBug)`)
  - `java` 단일 파일로 `oneLine`과 같은 정규식을 U+0085·연속 U+2028·VT·FF·탭 섞인 입력에 돌려 결과가 모두 한 줄(`lines().count()==1`)임을 확인(아래 점검표 밖 N1·N2).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 1588건 실패·오류·건너뜀 0 |
| B2 | 통과 | 운영 코드 변경은 `autotrade-app-engine/.../EngineMain.java:282-305`(`StartFailure` 안 private 정규식 2개와 `oneLine`)뿐, 새 의존은 JDK `java.util.regex.Pattern`. 빌드 스크립트·모듈 의존 무변경. ArchUnit(`autotrade-architecture-test`)·순수 모듈 금지 의존 검사 빌드 안에서 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음(ADR 불필요). 운영자 출력(표준 오류·로그 ERROR 줄)의 원인 부분 표시 방식이 바뀌므로 결정 문서 D-112를 새로 두고 D-107에 후속 절, `docs/decisions/README.md` 표에 한 줄 추가. runbook(docs/runbooks/README.md)은 "로그의 ERROR 줄을 본다"는 안내라 이 변경과 어긋나지 않음(오히려 그 안내를 지키게 함). 판정·종료 코드(2/1/1)·첫머리 문구 무변경(`EngineMain.java:285-294`) |
| B4 | 해당 없음 | 시뮬레이션 코드 무관. 시험은 기존 `FIXED` Clock 사용 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작(`oneLine`)에 시험 3건(EngineMainTest.java:511, :540, :581). 기존 시험 삭제·`@Disabled`·단언 약화 없음(diff는 추가만). 표준 오류 세 경우를 `System.lineSeparator()`까지 `isEqualTo`로 고정해 PR #169 QA Q1(변이 P3·P8)을 막음. 로그 ERROR 줄·표준 오류 전체 비교와 예외 흐름 원문 보존(`isSameAs`, `hasMessage`) 단언. 위 변이 M1~M7 모두 잡힘으로 실효 확인 |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-engine/*`은 보호 경로(scripts/ci/protected-paths.txt:8)이고 이번에는 운영 코드(`EngineMain.StartFailure`)도 바뀜. 바뀐 것은 원인 문자열 표시뿐이고 `StartFailure.of`의 종류 판정·종료 코드·`programError` 범위·Guard 기동(모든 계좌 OFF)·명령 채널·주문 경로는 diff에 없음. 그래도 실전 엔진 변경이므로 **risk-ops 검토 필요**, 머지는 사용자 직접, 머지 전 독립 QA(지침 10·11장) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 픽스처는 `"C:\\x"` 경로 예외, "첫 줄/둘째 줄" 같은 고정 문구뿐. 커밋 메시지에도 비밀값 없음. 설정 오류·명령 채널 실패는 예전처럼 예외 흐름을 남기지 않아(`EngineMain.java:308-313`) 원인 예외에 실릴 수 있는 값의 노출 범위가 늘지 않음 |

## 점검표 밖 발견
- N1 (참고, 판정 무관) D-112 결정 1번은 `\R`을 6글자(`\n`, `\r\n`, `\r`, U+0085, U+2028, U+2029)로 적었지만 Java `\R`은 VT(U+000B)·FF(U+000C)도 포함한다. 실제로 두 글자도 " / "로 접힌다(직접 확인). 동작은 "한 줄" 약속에 더 맞으므로 코드는 그대로 두고, 다음에 D-112를 손볼 때 목록을 고치면 된다.
- N2 (참고, 판정 무관) `String.strip()`은 `Character.isWhitespace` 기준이라 U+0085를 걷지 않는다. 그래서 `"a\u0085"`는 `"a / "`, 공백 아닌 줄바꿈이 연달아 오면(`"a\u2028\u2028b"`) `"a /  / b"`가 된다(`\s`가 U+2028을 포함하지 않음). 둘 다 한 줄이고 표시용이라 영향 없음. 굳이 맞추려면 `LINE_BREAK_RUN`을 `"(?:\\s|\\R)*\\R(?:\\s|\\R)*"`류로 바꾸고 끝단도 같은 규칙으로 걷으면 된다.
- N3 (참고) 새 첫 시험의 명령 채널 단언은 `ControlServer.LOCK_FILE` 상수를 쓰지만 "다른 실전 엔진이 이미 실행 중입니다(... 잠금)" 문구는 `autotrade-control` 쪽 글자에 기댄다. PR #169 검토 때와 같은 모듈 간 문구 결합이고, 바뀌면 시험이 실패하므로(조용히 통과하지 않음) 받아들일 만하다 `[추론]`.
- N4 (참고) D-112 "위험과 한계"에 적힌 대로 감시(`WatchdogMain`, D-106)의 같은 기동 실패 출력은 아직 줄바꿈을 접지 않는다. 다음 감시 PR에서 같은 규칙(가능하면 공용 도우미 하나)으로 맞추기를 권한다.

## 판정
승인
