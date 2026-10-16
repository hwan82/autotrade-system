# D-100 알림 연결(MON-06 2차): 감시·실전 엔진, 카카오 갱신 키, 알림 큐

- 날짜: 2026-10-16
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 보호 경로(`autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-config`·`autotrade-notification-jar`)를 바꾸므로 실전 관련 PR로 다룬다(architect·risk-ops 검토, 독립 QA, 사용자 머지).
- 담당: risk-ops(+architect). 교차 검토: architect(`docs/reviews/pr-<번호>/architect.md`), risk-ops(`docs/reviews/pr-<번호>/risk-ops.md`)
- 근거: [D-099](D-099-notification-library-first.md) 후속 표 1·2·5번, PR #127 독립 QA 권고 Q1·R1·R3와 참고 R2(`docs/reviews/pr-127/qa.md`), PR #123 독립 QA 권고 Q1·Q2·R4(`docs/reviews/pr-123/qa.md`), [D-092](D-092-watchdog-heartbeat-and-stop-requests.md)·[D-096](D-096-watchdog-missing-reading-warnings.md), 절대 규칙 6(실전 매매는 다른 기능에 영향받지 않음)

## 결정

1. **알림 큐**(`NotificationQueue`, 알림 모듈). 실전 엔진·감시는 알림을 이 큐에 넣기만 한다. HTTP는 프로세스마다 데몬 스레드 하나(`notification-<프로세스>`)에서만 돈다.
   - `offer`는 막지 않고(큐 64건, 차면 버리고 센다) 어떤 `Throwable`도 던지지 않는다. 보내는 스레드는 발송 중 `Error`가 나도 그 알림을 로그에만 남기고 다음 알림을 보낸다(PR #127 QA R3). 그래서 채널의 지연·`Error`가 엔진의 명령·주문 스레드로 번지지 않는다.
   - 부르는 쪽은 종류만 준다. 등급은 `EventRoutes` 표가 정한다(PR #127 risk-ops R4).
   - **폭주 억제**: 같은 대상·같은 종류는 10분에 한 번. 생략 건수는 다음에 보내는 같은 대상·종류 알림에 붙인다. 종류가 바뀌면(멈춤 → 회복 → 멈춤) 늘 보낸다. 정보 알림은 전체로 10분에 20건(긴급 포함해 셈)까지만 받는다. 긴급 알림은 전체 상한으로 버리지 않고 큐 크기(64건)까지 받는다. 감시는 상태가 바뀔 때만 알리므로 버린 긴급 알림은 다시 오지 않기 때문이다(PR #128 architect F1). 넘친 건수·큐가 차서 버린 건수는 다음에 받는 알림에 붙인다. 버린 알림도 그 대상의 마지막 종류로 남긴다. 그래서 멈춤(보냄) → 회복(버림) → 다시 멈춤에서 두 번째 멈춤을 억제하지 않는다(PR #128 독립 QA F1). 시계가 뒤로 가면 간격이 찬 것으로 본다.
   - **닫기**: 프로세스 종료 때 남은 알림을 최대 3초 보내 보고, 그래도 남은 알림은 로그 채널에만 남긴다.
2. **매핑표에 운영 이벤트 추가**(`EventRoutes`). 긴급: `process-stale`·`heartbeat-unreadable`·`process-restarted`·`stop-request-on`·`reading-missing`·`config-warning`·`real-trading-command`·`notification-channel`. 정보: `process-recovered`·`process-stopped`·`stop-request-off`·`reading-recovered`·`watchdog-started`·`engine-started`. 기존 7종(D-099 2번)과 "표에 없으면 긴급"은 그대로다.
3. **감시 연결**: 감시는 상태가 바뀔 때만 알린다.
   - 하트비트: 멈춤·읽기 실패(긴급, 처음 점검에서도), 점검 사이 재기동(긴급), 멈춤·읽기 실패에서 회복(정보), 살아 있다가 정상 종료(정보). 처음 본 살아 있음·꺼짐은 알리지 않는다(기동 소음).
   - 정지 요청: 켜짐(긴급, 자원 상세와 "실전 엔진은 멈추지 않음"), 꺼짐(정보).
   - 자원 값 결측: 새 값이 빠질 때(긴급)와 다 돌아올 때(정보)만. 10분 반복 경고(D-096)는 로그에만 남긴다.
   - 감시 시작(정보, 남은 정지 요청을 이어받았으면 그 사실). 외부 긴급 채널이 없으면 기동 때 WARN 로그(ADR-005 표).
4. **실전 엔진 연결**: 실전 엔진은 다음만 알린다. 주문 경로 이벤트(손절·한도·강제 종료·결산)는 7단계 주문 경로 PR에서 같은 큐로 잇는다(D-099 후속 3번 그대로).
   - 엔진 시작(정보, 실전 계좌 수와 Guard 모두 OFF), 기동 때 설정 파일 경고(긴급, 값 없는 경고 문구).
   - 실전 제어 명령(실전 활성화·비활성화·PANIC SELL)의 시도와 결과 코드(긴급). 명령 감사 로그(`CommandAuditLog`)를 감싸 같은 내용을 큐에 넣는다(`NotifyingCommandAuditLog`). 비밀번호·확인 문구는 감사 로그에도 알림에도 없고, 대상은 계좌 별칭이다. 억제 대상에 결과 코드를 넣어 거절 뒤 성공처럼 결과가 바뀐 명령은 10분 안이어도 늘 알린다.
   - 실전 계좌가 있는데 외부 긴급 채널이 없으면 기동 때 WARN 로그.
   - 알림 설정이 틀려도 엔진 기동을 막지 않는다. 그 채널만 끄고 ERROR 로그(값 없이)를 남긴다. 로그 채널은 늘 있다.
5. **카카오 설정 키**(D-099 후속 2번, architect 변경 제안 확정): `notification.kakao.rest-api-key` 🔒, `notification.kakao.refresh-token` 🔒, `notification.kakao.client-secret` 🔒(선택), `notification.kakao.link-url`(일반, 카카오 앱에 등록한 웹 도메인). `notification.kakao.access-token` 🔒은 선택으로 바뀐다(없으면 첫 발송 전 갱신). `enabled=true`이고 `mode=ME`면 rest-api-key·refresh-token·link-url이 있어야 보낸다. 모자라도 설정 파일은 거부하지 않는다. 거부하면 D-100 이전에 access-token만으로 켠 파일에서 실전 계좌 등 다른 설정까지 빠지기 때문이다. 대신 실전 엔진·감시가 카카오 채널만 끄고 ERROR 로그를 남기며, 실험실 설정 화면은 카카오 값을 고칠 때만 거부한다(`Kakao.meReady()`, PR #128 architect F3). `mode=BIZ`(알림톡, 유료)는 알림 모듈이 채널을 만들지 않고 WARN 로그를 남긴다(D-033, 과금 결정 없음).
   - 실험실 설정 화면은 새 비밀값 셋을 쓰기 전용으로 받는다. link-url은 등록 도메인과 맞춰야 해 화면에서 바꾸지 않고 `runtime-config.json`에서 바꾼다. 알림 키의 반영 시점 표시는 "실전 엔진·감시 재기동"이다(두 프로세스가 기동 때 읽는다).
   - `SecretPaths`는 `Secret` 타입 필드에서 경로를 만들므로 코드와 지문(`RealPathReflectionTest.RECORD_READER_FINGERPRINTS`)은 바뀌지 않는다. D-099 후속 2번의 "지문이 바뀐다"는 예상은 틀렸다(비밀값 경로 고정 시험 `ConfigLoaderTest`만 바뀐다).
6. **갱신 토큰 저장**(`FileKakaoTokenStore`): `<설정 폴더>/notification-state/kakao-token.json`. 같은 설정 폴더의 실전 엔진과 감시가 함께 읽고 쓴다. 임시 파일에 쓰고 원자적 이동, POSIX면 소유자만 읽기·쓰기, 4KB까지만 읽는다. 파일에는 `secrets.json` 리프레시 토큰의 SHA-256(출처)을 적어, 사용자가 다시 동의해 새 리프레시 토큰을 넣으면 옛 파일을 쓰지 않는다. 채널은 보내기 전에 이 파일을 보고 더 늦게 만료되는 토큰이면(다른 프로세스가 갱신) 갈아탄다. `secrets.json`은 다시 쓰지 않는다(사용자·실험실 설정 화면이 쓰는 파일을 두 프로세스가 덮어쓰지 않게).
7. **카카오 거절 기억**(PR #127 QA R1): 갱신이 400·401로 거절되면 그 리프레시 토큰을 기억하고, 같은 토큰인 동안은 카카오에 묻지 않고 `kakao-reconsent-required`로 바로 실패한다(다음 채널로 넘어감). 거절을 처음 알린 발송만 `kakao-refresh-rejected`를 돌려주고, 큐는 이것을 보면 "카카오톡 알림 재동의 필요" 긴급 알림을 한 번 넣는다(Slack·로그로 감). 토큰 파일에 다른 리프레시 토큰이 들어오면 갈아타고 거절 상태를 푼다.
8. **라이브러리 보강**: 갱신 응답 `expires_in` 상한 7일(넘으면 `kakao-refresh-bad-response`, PR #127 QA R2 `[가정]` 여유), `Notifier`는 런타임 예외만 잡는다고 문서를 좁힘(QA R3), 이름 `log`는 `LogChannel`만 쓸 수 있고 외부 채널 판정은 타입으로(architect R2), 알림 시각을 시간대와 함께 보임(실전 엔진·감시는 Asia/Seoul, 예 `2026-10-16T09:00:00+09:00`, architect R3).
9. **시험 보강**: PR #127 QA Q1(기한 초과 때 요청 취소가 서버 쪽 연결을 끊는지, 8KB를 채우면 구독을 끊어 나머지를 받지 않는지: 변이 M2·M8), PR #123 QA Q1(간격이 찬 경고 뒤 다시 빠진 값 즉시 경고: 변이 W5)·Q2(10분 값과 로그 수준: 변이 W1·W8·W10), R4(시계 역행 시 반복 경고가 밀리지 않음, `Watchdog`·`HeartbeatWatch` 함께).

## 대안

| 대안 | 판단 |
|---|---|
| ① 프로세스마다 알림 큐 하나, 등급은 표로만, 억제는 큐에서(채택) | 엔진 스레드는 넣기만 하고, 감시·엔진이 같은 억제·`Error` 격리를 쓴다 |
| ② 엔진·감시가 `Notifier.dispatch`를 직접 부름 | 엔진 명령 스레드가 HTTP 최대 약 30초에 묶이고 `Error`가 번진다(절대 규칙 6). 버림 |
| ③ 실행기(`ExecutorService`)에 작업으로 넘김 | 큐 크기·버림 정책·닫을 때 남은 알림 처리를 따로 짜야 하고, 작업이 `Error`로 끝나면 결과가 조용히 사라진다. 직접 만든 루프가 짧고 시험하기 쉽다. 버림 |
| ④ 갱신 토큰을 `secrets.json`에 다시 씀 | 사용자 편집 파일을 엔진·감시·실험실 설정 화면이 동시에 덮어쓸 수 있다. 실험실 설정 화면의 "디스크와 메모리가 같을 때만 저장" 검사도 깨진다. 버림 |
| ⑤ 카카오 채널은 감시만(엔진은 Slack만) | 토큰 공유 문제는 없어지지만 엔진의 정보 알림(7단계 결산 등)이 카카오로 못 간다. 파일 공유로 충분하다. 버림 |
| ⑥ 감시가 빠진 값 10분 반복 경고마다 알림 | 하루 최대 144건, 사람이 무시하게 된다. 새로 빠질 때와 회복 때만 알리고 반복은 로그로. 버림 |
| ⑦ 실전 Guard 전이마다 알림 | 지금 전이는 모두 명령에서 오므로 명령 알림과 겹친다. 자동 전이(킬스위치)는 7단계 주문 경로 PR에서. 이번엔 버림 |

되돌릴 조건: 실제 운영에서 억제가 중요한 알림을 가리면 같은 대상·종류 간격을 줄이거나 긴급만 억제에서 뺀다. 두 프로세스가 토큰 파일을 두고 거절을 자주 겪으면 카카오 갱신을 한 프로세스(감시)만 하게 바꾼다.

## 위험과 한계

- `[가정]` 엔진과 감시가 같은 리프레시 토큰으로 거의 동시에 갱신하면 둘 다 성공한다고 본다(카카오 문서에 동시 갱신 규칙이 없다). 리프레시 토큰이 새로 나오는 때(남은 기간 1개월 미만)에 두 프로세스가 엇갈리면 한쪽이 거절을 겪을 수 있다. 그때는 재동의 알림이 가고, 다른 프로세스가 파일에 남긴 새 토큰으로 다음 발송부터 풀린다.
- `[가정]` 억제 간격 10분·전체 20건·큐 64건·닫기 3초는 운영 근거 없이 정한 값이다. 6단계 실시간 모의 운영 주간 점검에서 생략 건수를 본다.
- 억제로 생략된 건수는 다음 같은 알림이 올 때만 보인다. 폭주 뒤 조용해지면 생략 건수는 알림으로 나가지 않는다(로그에는 원래 이벤트 줄이 남는다).
- 토큰 파일 권한은 POSIX 파일 시스템에서만 소유자 읽기·쓰기로 좁힌다. Windows(사용자 PC)에서는 설정 폴더의 기존 ACL을 그대로 물려받으므로, `secrets.json`과 같은 폴더 보호(사용자 계정 전용 폴더)에 기댄다(PR #128 architect R4).
- 재동의 알림은 프로세스마다 한 번이라 엔진과 감시가 둘 다 켜져 있으면 두 번 온다.
- 회복 직후 같은 값이 다시 빠지면 최대 10분 동안 로그·알림이 없다(PR #123 QA R1, D-096이 받아들인 한계 그대로).
- Slack·카카오 실제 호출은 시험하지 않았다(키 없음). 카카오 링크에 `http://localhost`를 등록할 수 있는지는 여전히 `[가정]`이다(D-099). 사용자 PC 설정 절차는 7단계 시험 절차서에 넣는다.
- 이 컨테이너에서는 Gradle 빌드가 실행 권한에 막혀 로컬 빌드를 하지 못했다. 시험 통과는 PR CI(`Gradle build`)로 확인한다.

## 검증

- `NotificationQueueTest`: 등급은 표로만·보내는 스레드에서 보냄, 채널이 막혀도 넣는 쪽은 1초 안에 돌아오고 큐 가득(64건)은 버림, 전체 상한은 정보 알림에만(긴급은 상한을 넘어도 받고 버린 정보 건수를 붙임), 상한·큐 가득으로 회복을 버려도 다시 멈춤은 받음(QA 탐침 P1·P2), 채널 `StackOverflowError`가 넣는 쪽으로 번지지 않고 다음 알림 발송, 형식 오류 거절, 같은 대상·종류 10분 억제와 생략 건수, 종류가 바뀌면 억제 안 함, 시계 역행, 상한 생략 건수, 카카오 거절 시 재동의 알림 한 번, 닫기 때 남은 알림 로그.
- `KakaoMemoChannelTest`: 거절 기억(갱신 요청 한 번), 401 뒤 거절도 기억, 저장소의 다른 리프레시 토큰으로 갈아타 거절 해제, 더 늦게 만료되는 저장소 토큰 사용, 저장소 예외 무시, `expires_in` 상한.
- `FileKakaoTokenStoreTest`: 같은 출처 공유, 새 동의는 옛 파일 무시, 없음·깨짐·4KB 초과(상한을 빼면 정상으로 읽힐 내용), 문자열에 값 없음.
- `NotificationSetupTest`: 켜진 채널만 표준 순서, BIZ는 안 만듦, 틀린 설정은 그 채널만 끔, 문자열에 값 없음.
- `JdkHttpTransportTest`: 기한 초과 때 서버 쪽 쓰기가 끊김(M2), 8KB 뒤 64MB 본문을 끝까지 받지 않음(M8).
- `WatchdogTest`: 엔진 멈춤·회복·정상 종료, 기동 때 이미 멈춘 엔진, 재기동, 정지 요청 켜짐·꺼짐, 자원 결측·회복(10분 반복은 알림 없음), 알림이 던져도 점검 계속, 10분 값, W5 순서, 시계 역행(감시·하트비트), 로그 수준.
- 운영 배선(PR #128 QA Q1): `EngineMainTest`가 실제 기동한 엔진의 큐에 시작·실전 제어 명령 알림이 들어가는지, `WatchdogMainTest`가 실제 기동한 감시가 멈춘 엔진 하트비트를 큐에 넣는지 본다(`NotificationQueue.acceptedCount`).
- `NotifyingCommandAuditLogTest`, `SettingsControllerTest`(카카오 ME 켜기 조건, 새 비밀값 쓰기 전용, link-url은 화면에서 못 바꿈), `ConfigLoaderTest`(비밀값 경로).
- 보호 테스트(`RealPathReflectionTest`·`ArchitectureTest`·`ModuleOwnershipTest`)는 고치지 않았다. 새 운영 코드는 D-086 허용 목록 안 타입만 쓴다(`java.util.concurrent`, `java.nio.file(.attribute)`, `java.security.MessageDigest`, `java.util.HexFormat`, `java.time.format`, 허용된 Jackson 타입).

## 후속

| 후속 | 담당 | 언제 |
|---|---|---|
| 1. 실전 엔진 주문 경로 이벤트(손절·한도·강제 종료·장 시작 예수금·장 마감 결산, Guard 자동 전이)를 같은 큐로 | risk-ops | 7단계 엔진 주문 경로 PR |
| 2. 설정 화면 "알림 시험 보내기"와 마지막 채널 끄기 경고, link-url 편집(문자열 입력) | ui-dev | 다음 설정 화면 PR |
| 3. 사용자 PC 카카오 앱 설정 절차(REST API 키, talk_message 동의, 등록 도메인, 리프레시 토큰 받기) | risk-ops | 7단계 시험 절차서 |
| 4. 억제 값(10분·20건) 운영 점검 | risk-ops | 6단계 주간 점검 |

## 반영 기록

- 2026-10-16, PR #128 검토 권고 반영(결정 내용은 바뀌지 않아 새 번호를 쓰지 않음):
  - architect R1: `NotificationSetup`을 위치 인자 생성자 대신 이름 있는 빌더(`NotificationSetup.builder()`)로만 만든다. 엔진·감시의 설정 옮기기(`EngineMain.setup`·`WatchdogMain.setup`)는 값마다 다른 설정으로 제자리에 갔는지 시험한다(`EngineMainTest`·`WatchdogMainTest#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다`). 두 곳의 옮기는 코드는 그대로 둘이다(알림 모듈은 설정 모듈을 모르고, 둘이 함께 기대는 모듈을 새로 만들 만큼 크지 않음).
  - architect R2: 알림 라이브러리에서 이 시스템의 설정 키·프로세스 이름이 든 재동의 문구와 `Asia/Seoul`을 뺐다. 부르는 쪽이 `displayZone`(필수)과 `kakaoReconsentHelp`를 넘기고, 값은 `RuntimeConfig.Notification.DISPLAY_ZONE`·`KAKAO_RECONSENT_HELP`에 둔다. 라이브러리에는 일반 문구(`NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP`)만 남는다. 사용자가 받는 알림 문구와 시각 표시는 바뀌지 않는다.
  - QA 재검토 Q1(변이 M17): 버린 종류가 같은 대상으로 다시 오는 순서 시험 둘(`NotificationQueueTest#상한으로_버린_종류가_새_창에서_다시_오면_받는다`, `#큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`). 조건 `last.at() != null`을 지우면 둘 다 실패함을 확인했다.

