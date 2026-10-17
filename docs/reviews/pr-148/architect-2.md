# architect 재검토: D-106 감시 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #148)
- 대상: PR #148 / 헤드 커밋 c6cd841f9c5f99ef56ddff718e1b3d2968a022c8 / `git diff origin/main...HEAD` 전체(파일 11개, +352 −23. 검토 원문 2개 포함). 앞 검토(architect.md, 82e2cb5) 뒤 코드·문서 커밋은 c6cd841 하나(`WatchdogMain.java`, `WatchdogMainTest.java`, D-106, `docs/runbooks/README.md`). 브랜치는 main보다 2커밋 뒤(29a2c9d, 1bb2f3e)이고 겹치는 파일 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → **BUILD SUCCESSFUL**, 138 tasks(28 executed). 시험 결과 XML 합계 1510개, 실패·오류·건너뜀 0. `WatchdogMainTest` 14개 모두 통과(이번 커밋의 새 시험 4개 포함).
  - `./gradlew :autotrade-app-watchdog:javadoc :autotrade-notification-jar:javadoc` → 통과.
  - 변이 2개를 함께 넣음: `launch`의 `catch (RuntimeException e)`를 `catch (IllegalArgumentException e)`로 좁힘, `configPaths`의 `catch (IllegalArgumentException e)`를 `catch (UnsupportedOperationException e)`로 바꿈 → `:autotrade-app-watchdog:test` 61개 중 2개 실패(`WatchdogMainTest#진입점은_설정_오류가_아닌_기동_예외를_종료_코드_1로_끝낸다`, `#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`). `git checkout`으로 되돌리고 작업 트리 깨끗함 확인.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 시험 1510개 실패 0 |
| B2 | 통과 | 변경은 `autotrade-app-watchdog`의 `com.autotrade.app.watchdog` 안(새 `WatchdogRefusal`은 패키지 전용 `WatchdogRefusal.java:11`), 새 import는 `java.util.function.IntConsumer`뿐. 알림 모듈은 Javadoc 위치만(`NotificationSetup.java:89-96`). ArchUnit·`verifyProcessIsolation`·`verifyPureDependencies` 빌드 안에서 통과. `Clock.systemUTC()`는 진입점 한 곳 그대로(`WatchdogMain.java:44`) |
| B3 | 통과 | 공개 계약 무변경: `run`·`start` 시그니처 그대로, 새 `launch`(`WatchdogMain.java:63`)·`configPaths`(`:78`)는 패키지 전용·private. 설정 키·스키마·REST 무변경. 바뀐 외부 동작(감시 종료 코드 1/2 나눔, 설정 폴더 경로 오류 → 2)은 D-106 결정 4·5번·위험·검증과 결정 로그(`docs/decisions/README.md:112`), runbook(`docs/runbooks/README.md:21`, `docs/runbooks/collector.md:62`)에 반영. 종료 코드 2의 뜻(ADR-011:25, D-092 7번, CI `ci.yml:37-40`)은 그대로라 ADR 갱신 불필요 |
| B4 | 해당 없음 | 시뮬레이션·시계·난수 코드 무변경. 골든 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 시험: `#설정_파일을_못_읽어도_설정_오류로_거부한다`, `#알림_채널_코드_실수_같은_예외는_설정_오류로_표시하지_않는다`, `#그_밖의_기동_예외도_프로그램_오류로_종료_코드_1이다`, `#기동_실패는_표준_오류에_첫머리_문구와_원인을_한_줄로_남긴다`, `#진입점은_설정_오류면_종료_코드_2로_끝내고_감시를_돌려주지_않는다`(`WatchdogMainTest.java:72`), `#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`(`:81`), `#진입점은_설정_오류가_아닌_기동_예외를_종료_코드_1로_끝낸다`(`:92`, 하트비트 미생성 확인 포함), `#기동_점검은_설정_오류가_아닌_예외를_종료_코드로_바꾸지_않고_던진다`(`:107`). 앞 검토의 빈틈(진입점 종료 코드 배선·`run`의 비거부 예외)이 `launch`에 `exit`를 끼우는 방식으로 메워졌고 위 변이를 실제로 잡음. 기존 시험 두 개는 단언을 `WatchdogRefusal`로 좁힘(약화 아님). 지우거나 끈 시험 없음. 로그에 예외 흐름을 남기는지는 시험하지 않는다고 D-106에 적혀 있음(표준 오류 한 줄은 시험됨) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-watchdog/*`, `autotrade-notification-jar/*`는 보호 경로(`scripts/ci/protected-paths.txt`). 감시 기동 실패 처리·종료 코드를 바꾼다. 지침 10·11장에 따라 risk-ops 재검토, 사용자 직접 머지, 머지 전 독립 QA 대상. 이 판정은 architect 범위에 한한다 |
| B8 | 통과 | 새 문구는 고정 문장 + 예외 종류·메시지(`WatchdogMain.java:78-84`, StartFailure). 설정 폴더 경로 오류 문구는 경로 값(사용자가 넣은 폴더 경로)만 실음. 알림 채널 꾸리기 예외는 `NotificationSetup.built`가 메시지를 버리고, `SlackWebhookChannel.java:41-43`·`KakaoMemoChannel.java:97-99`는 원인 예외를 잇지 않아 스택에 웹훅 URL 등이 실리지 않음. 키·토큰·계좌번호가 코드·문서·커밋에 없음 |

## 앞 검토 발견의 처리
- N1(권고) → 반영. ① 설정 폴더 경로 오류는 `configPaths`(`WatchdogMain.java:78-84`)가 `WatchdogRefusal.settings`로 바꿔 종료 코드 2, 시험 `#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`. ② 기동 중 I/O 실패가 프로그램 오류로 보이는 한계는 D-106 "위험과 한계"에 적고 되돌릴 조건("자주 보이면 거부 종류로 옮긴다")을 둠. 받아들임.
- 시험 빈틈(진입점 배선) → `launch` 추출과 시험 3개로 메워짐.

## 점검표 밖 발견
- N4(권고, 막지 않음): 설정 폴더 경로 오류도 첫머리가 "감시 설정이 틀려 기동하지 않습니다"(`WatchdogRefusal.settings`)인데, 수집기 runbook(`docs/runbooks/collector.md:62`)은 이 줄을 보면 "`watchdog.properties`를 고친다"고 안내한다. 세부 문구가 "설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR)"라 원인은 보이지만, runbook 한 줄에 "세부가 '설정 폴더 경로가 틀렸습니다'면 환경 변수를 고친다"를 보태거나 이 경우만 별도 첫머리를 쓰길 권한다.
- N5(권고, 막지 않음): `docs/runbooks/README.md:21`의 감시 행은 "상주, Ctrl+C"로 고쳐졌지만 같은 파일 4장(`docs/runbooks/README.md:72`)은 여전히 "감시는 현재 설정만 읽고 종료한다(하트비트 감시·긴급 명령은 risk-ops 후속 작업)"라 한 문서 안에서 어긋난다(4장 문장은 이 PR 전부터 낡았음). 다음 문서 PR에서 4장을 "감시는 상주하며 하트비트·드라이브·CPU·메모리를 보고 정지 요청 파일을 만든다(D-092·D-095). 긴급 정지·청산은 후속"으로 맞추길 권한다.
- 기록: `configPaths`는 `paths.get()`의 모든 `IllegalArgumentException`을 경로 오류로 본다. 운영 공급자 `ConfigPaths::defaultLocation`에서 나올 수 있는 IAE는 `Path.of`의 `InvalidPathException`과 `ConfigPaths` 생성자의 "일반 설정과 비밀값은 다른 파일"뿐이며 후자는 `in(dir)`로는 도달하지 않는다(`ConfigPaths.java:15-25`). 범위가 넓지 않다.
- 기록: `main`의 `if (watchdog == null) return;`(`WatchdogMain.java:45-47`)은 `System::exit`가 돌아오지 않으므로 운영에서는 닿지 않고, 시험용 `exit`에서만 의미가 있다. 문제 아님.

## 판정
승인 (architect 범위. B7에 따라 risk-ops 재검토·사용자 머지·독립 QA가 별도로 필요)
