# K10 OS 자격 증명 저장소 재검토 (Windows DPAPI·Credential Manager, macOS Keychain)

- 작성일: 2026-10-26 · 작성: risk-ops · 기준 코드: main `53482a56`
- 출처 항목: [ADR-001](../adr/ADR-001-module-structure.md) 2.6절 대안 D(165행 "7단계 전 risk-ops 재검토"), 4장 위험 표(232행 "Windows에서는 설정 폴더·파일 권한을 POSIX로 제한하지 못함"), `autotrade-config/src/main/java/com/autotrade/config/RuntimeConfigStore.java:77` 주석, [7단계 준비 계획](../team/stage-7-readiness-plan.md) 2.3 K10(묶음 0)
- 이 문서는 검토와 추천안이다. 코드·설정은 바꾸지 않는다. 추천안의 코드 변경은 7장의 실전 관련 PR로 따로 한다.

## 1. 결론

1. **추천안: 지금의 파일 방식(`secrets.json`, 설정 폴더)을 유지하고, 권한·위치 검사 경고와 운영 절차를 더한다(대안 A').** OS 자격 증명 저장소(DPAPI·Credential Manager·Keychain)로 옮기지 않는다.
2. [사실] DPAPI 사용자 범위 암호화는 "암호화한 사용자와 로그온 자격 증명이 같은 사용자"가 풀 수 있다(Microsoft `CryptProtectData` 설명). 같은 Windows 계정에서 도는 악성 프로세스(가장 현실적인 위협)는 막지 못한다. [추론] 이득은 백업·클라우드 동기화·다른 PC로 파일이 새는 경우에 한정되는데, 이 경우는 설정 폴더 위치·백업 제외 확인으로 대부분 막을 수 있다.
3. [추론] Java 21 표준 라이브러리에는 DPAPI·Credential Manager 호출 API가 없다. 쓰려면 JNA 같은 외부 네이티브 라이브러리나 보조 프로세스 실행이 실전 엔진에 들어와야 하고, 이는 실전 경로 리플렉션 금지·외부 jar 허용 목록(D-086, `RealPathReflectionTest`)과 프로세스 실행 금지 목록을 넓히는 보호 테스트 변경이다. 얻는 보호에 비해 실전 경로의 공격 면과 검증 부담이 커진다.
4. [사실] 지금 구현의 빈틈: Windows에서는 폴더·파일 권한을 전혀 설정·확인하지 않고(`RuntimeConfigStore.java:77-78`), POSIX에서도 폴더가 이미 있으면 권한을 고치지 않으며(`:71-73`), 읽을 때 권한 검사가 없다(`ConfigLoader.java:75-77`). 사용자가 편집기로 만든 `secrets.json`·`collector-secrets.json`은 기본 권한 그대로다. 추천안은 이 빈틈을 **경고**로 드러낸다(기동 거부나 활성화 차단은 하지 않는다).
5. **사용자 결정 필요 없음**(지침 8장 기준: 과금 없음, 실전 주문 경로·Guard·블랙리스트·한도·장애 대응 규칙 무변경, 확정 결정과 충돌 없음). 추천안 채택(자율). 다만 코드 변경은 보호 경로(`autotrade-config/*`, 수집기 `CollectorSecrets.java`)라 실전 관련 PR이고 사용자가 머지한다. 사용자 작업(결정 아님) 1건: 7단계 절차서 시행 때 설정 폴더 권한·위치 확인(6장).

## 2. 지금 방식 (코드 확인)

| 비밀값 | 저장 위치 | 읽는 곳 | 보호(POSIX) | 보호(Windows) | 근거 |
|---|---|---|---|---|---|
| 증권사 app-key·app-secret, 실전 계좌번호, 운영자 비밀번호 해시, Slack webhook, 카카오 키·refresh token | `<설정 폴더>/secrets.json`(`AUTOTRADE_CONFIG_DIR`, 기본 `~/.autotrade/`) | 4개 상주 프로세스 모두(엔진·게이트웨이·감시는 `ProcessStartup.start`, 실험실은 설정 화면). 실험실은 쓰기도 함 | 실험실 저장 시 임시 파일 600 → 원자적 이동, 새 폴더면 700. **폴더가 이미 있으면 권한을 고치지 않음.** 읽을 때 권한 검사 없음 | 아무것도 안 함. 상위 폴더 ACL 상속 | `RuntimeConfigStore.java:45-86`, `ConfigLoader.java:75-77`, [config-keys.md](../config/config-keys.md) 머리말·35행, [ADR-006](../adr/ADR-006-real-control-channel.md) 3장 B1 |
| 수집기 키(KRX·OpenDART·kis-news 앱키) | `<설정 폴더>/collector-secrets.json`(사용자가 직접 만듦) | 수집 일괄 명령만 | 권한 설정·검사 없음 | 없음 | `autotrade-app-collector/.../CollectorSecrets.java`(권한 관련 코드 검색 0건), D-051 |
| 카카오 갱신 토큰 | `<설정 폴더>/notification-state/kakao-token.json` | 실전 엔진·감시 | 새로 만들 때 600 | 없음 | `autotrade-notification-jar/.../FileKakaoTokenStore.java:41,78-79` |
| 증권사 접근 토큰·접속키 | 메모리만 | 발급한 프로세스 | 파일 없음 | 파일 없음 | `autotrade-marketdata/.../live/TokenCache.java`(파일 입출력 0건) |
| 운영자 비밀번호(평문) | 저장 안 함. 실험실 화면 입력 → 명령 채널 소켓 → 엔진이 해시와 비교 | 실전 엔진 | 소켓 600, `run` 폴더 700 | 없음(ACL 상속) | `ControlServer.java:520-535`, ADR-006 2.4 |
| 운영자 비밀번호 해시 | `secrets.json` | 4개 프로세스 메모리에 올라감 | 위와 같음 | 위와 같음 | PBKDF2-HMAC-SHA256 600,000회·salt 16바이트·15자 이상(`OperatorPasswordHash.java:17-18`, ADR-006 2.4), 변경 시 긴급 알림 `real-config-changed`(D-142) |

이미 있는 보조 장치: 비밀값 화면·로그 `****`(`Secret.toString`), `runtime-config.json`에 비밀값이 있으면 그 파일 거부, `.gitignore`의 `**/secrets.json`·`**/collector-secrets.json`, CI gitleaks(`.gitleaks.toml`).

[사실] 사용자 운영 PC는 Windows다(QA 폴더 `C:\2.Code\...`, CLAUDE.md). 따라서 위 표의 "Windows" 열이 실제 보호 수준이다.

## 3. 위협 모델

| # | 위협 | 무엇이 새나 | 지금 방식(Windows) | 영향 |
|---|---|---|---|---|
| T1 | 같은 PC의 다른 Windows 계정 | secrets.json 전체 | 기본 위치(사용자 홈 아래)면 다른 표준 사용자는 읽지 못한다고 `[가정]`(사용자 프로필 기본 ACL: 본인·SYSTEM·Administrators). `AUTOTRADE_CONFIG_DIR`을 `C:\autotrade` 같은 곳에 두면 상위 ACL에 따라 `Users`가 읽을 수 있다 `[추론]` | 증권사 키·계좌번호 유출 → 다른 곳에서 주문 가능 `[추론]` |
| T2 | 같은 계정에서 도는 악성 프로세스(악성 첨부·확장 프로그램·공급망) | 파일, 프로세스 메모리, 입력하는 비밀번호 | 막지 못함 | 가장 현실적이고 가장 큼. 어떤 저장 방식으로도 같은 계정 권한은 막기 어렵다(4장) |
| T3 | 백업·클라우드 동기화로 파일 사본이 밖에 생김 | secrets.json·collector-secrets.json 사본 | 설정 폴더가 OneDrive·Dropbox 등 동기화 폴더 안이면 그대로 올라감. 기본 `~/.autotrade/`는 OneDrive 폴더 이동(Desktop·Documents·Pictures 등) 대상이 아니다 `[사실: Microsoft 문서가 이동 대상을 그 폴더들로 적음]` | 계정 하나가 뚫리면 키 유출 |
| T4 | PC 분실·디스크 탈취·폐기 | 디스크의 모든 파일 | 디스크 암호화(BitLocker·장치 암호화)가 없으면 그대로 읽힘 | T3과 같음 |
| T5 | 사람의 실수 노출(지원 요청·화면 공유에 파일 첨부, 저장소 커밋) | 파일 내용 | 커밋은 `.gitignore`·gitleaks가 막음. 첨부·공유는 막지 못함 | 운영 절차로 줄임 |
| T6 | 관리자 권한 악성코드 | 모든 것 | 막지 못함 | 범위 밖(어떤 대안도 못 막음) |

실전 매매에서의 결과 `[추론]`: 증권사 앱키와 계좌번호가 함께 새면 이 시스템 밖에서 그 계좌로 주문할 수 있다. 운영자 비밀번호 해시가 새면 오프라인 대입이 가능하지만, 15자 이상과 PBKDF2 600,000회로 비용이 크고, 해시만으로는 증권사 주문을 낼 수 없다(엔진 명령 채널은 같은 PC 소켓이다).

## 4. 대안 비교

| 대안 | 내용 | T1 | T2 | T3·T4 | 구현·검증 부담 | 판단 |
|---|---|---|---|---|---|---|
| A. 지금 그대로 | 파일 + POSIX 600/700, Windows는 상속 ACL | 위치에 따라 다름 | 못 막음 | 못 막음 | 없음 | 버림: 빈틈(1장 4번)이 드러나지 않는다 |
| **A'. 지금 방식 + 권한·위치 검사 경고 + 운영 절차** | 기동 때 비밀 파일과 설정 폴더의 권한(POSIX 모드, Windows ACL)을 읽어 본인·SYSTEM·Administrators 밖에 읽기 허용이 있으면 경고. 설정 폴더 경로가 알려진 동기화 폴더 아래면 경고. 절차서에 디스크 암호화·백업 제외 확인 | 경고로 드러냄, 사용자가 고침 | 못 막음(다른 대안과 같음) | 위치 경고 + 디스크 암호화 절차로 줄임 | JDK `AclFileAttributeView`·`PosixFileAttributeView`만 씀(외부 의존 없음). 보호 경로 PR 1건 | **채택(추천안)** |
| B. DPAPI 사용자 범위로 비밀값 암호화 | secrets.json 값(또는 파일 전체)을 `CryptProtectData`로 암호화해 저장 | A'와 비슷(다른 계정은 못 풂) | 못 막음: 같은 사용자 자격이면 풀 수 있음 | 사본은 다른 PC·계정에서 못 풂(로밍 프로필 예외). PC 분실은 사용자 Windows 비밀번호를 아는 공격자에게는 못 막음 `[가정]` | JDK에 API 없음 `[추론]` → JNA(네이티브·리플렉션) 또는 FFM API 필요. 실험실 설정 화면·수집기·4개 프로세스 로더가 모두 바뀜. Windows 전용 코드와 다른 OS 경로가 갈림. 사용자가 손으로 secrets.json을 편집할 수 없게 되어 별도 입력 명령 필요 | 버림(되돌릴 조건 5장) |
| C. Windows Credential Manager(일반 자격 증명) | 비밀값마다 `CredWrite`(CRED_TYPE_GENERIC, CRED_PERSIST_LOCAL_MACHINE)로 저장 | A'와 같음 | 못 막음: 같은 사용자의 다른 로그온 세션에도 보임 `[사실: CREDENTIAL 문서 Persist 설명]` | 파일 사본 문제는 없어짐. 값 크기 상한 2,560바이트(5×512) `[사실]`로 지금 값은 들어감 `[추론]` | B와 같은 네이티브 호출 부담 + 값 목록·이름 관리, 백업·이전 절차가 따로 필요 | 버림 |
| D. macOS Keychain | 일반 암호 항목, 접근 조건(`kSecAttrAccessible`)·사용자 확인(`SecAccessControl`) | — | 사용자 확인 조건을 걸면 꺼낼 때마다 사람 확인 → 무인 실전 엔진에 맞지 않음 `[추론]` | `ThisDeviceOnly`면 다른 기기로 옮겨지지 않음 `[사실]` | 운영 PC가 Windows라 지금 쓸 곳이 없음 | 해당 없음(macOS 운영 시 다시 봄) |
| E. 마스터 비밀번호로 파생한 키로 파일 암호화 | 기동 때 사람이 비밀번호 입력 | 막음 | 못 막음(입력 가로채기) | 막음 | 실전 엔진·감시가 무인 재기동할 수 없음(장애표 #8·#9, 준비 계획 W2와 충돌), 4개 프로세스가 각각 입력 받아야 함 | 버림 |

**B·C를 버린 핵심 이유**

1. 가장 큰 위협 T2를 어느 쪽도 막지 못한다. [사실] DPAPI 문서: "Typically, only a user with logon credentials that match those of the user who encrypted the data can decrypt the data." 같은 사용자로 도는 악성 프로세스는 이 조건을 만족한다 `[추론]`. Credential Manager 일반 자격 증명도 같은 사용자 세션에 보인다.
2. 실전 경로에 네이티브 호출이 들어온다. [사실] D-086은 실전 엔진·감시·증권사 플러그인 런타임의 리플렉션과 외부 jar를 허용 목록으로 묶고, 프로세스 실행 허용 목록(`ALLOWED_PROCESS_LAUNCHERS`)은 빈 집합이다(`RealPathReflectionTest.java:100,111`, 준비 계획 K8). JNA는 네이티브 라이브러리를 불러오고 리플렉션을 쓴다 `[가정: JNA 문서로 확인 필요]`. PowerShell `ConvertFrom-SecureString` 같은 보조 프로세스 실행은 실행 허용 목록을 연다. 둘 다 보호 테스트 고정 값 변경이다.
3. Java FFM API는 Java 21에서 미리보기이고 정식판은 그 뒤 판이다 `[가정: JEP 454 원문을 이 환경에서 읽지 못함. 확인 방법: openjdk.org/jeps/454의 Release 칸]`. 실전 엔진에 `--enable-preview`를 켜는 것은 받아들이지 않는다. 정식판을 쓰려면 D-002(Java 21) 변경이라 사용자 결정이다.
4. 이득(T3·T4)은 A'의 위치 경고와 디스크 암호화 절차로 대부분 얻는다 `[추론]`.

## 5. 추천안 A' 상세

**코드(실전 관련 PR, 7장)**

1. 비밀 파일 접근 검사: 기동 때 `secrets.json`, `collector-secrets.json`(수집 명령), `notification-state/kakao-token.json`과 설정 폴더를 검사한다.
   - POSIX: 파일이 600보다 넓거나 폴더가 700보다 넓으면 경고(`RuntimeConfigStore.java:71-73`의 "이미 있는 폴더" 빈틈 포함).
   - Windows: `AclFileAttributeView`로 ACL을 읽어 본인·`SYSTEM`·`Administrators` 밖의 주체(`Users`, `Authenticated Users`, `Everyone` 등)에 읽기 허용이 있으면 경고 `[사실: JDK AclFileAttributeView는 ACL을 NFSv4 모델로 옮겨 읽음]`. [가정] Windows 기본 파일 시스템이 `acl` 보기를 지원한다. 확인 방법: 사용자 PC에서 `FileSystems.getDefault().supportedFileAttributeViews()`에 `acl`이 있는지 시험 실행으로 확인. 지원하지 않으면 "권한 확인 불가" 경고를 낸다(닫힌 쪽).
   - 설정 폴더의 절대 경로에 `OneDrive`, `Dropbox`, `Google Drive`, `iCloud` 경로 조각이 있으면 "동기화 폴더 안" 경고 `[추론: 이름 기반이라 놓칠 수 있음]`.
2. 경고는 **기동 거부나 파일 버림이 아니다.** 파일을 버리면 실전 계좌 목록과 비밀번호 해시가 함께 빠지므로 경고만 한다. 경고는 기존 `LoadedConfig.warning()` 경로로 내보내고, 실전 엔진은 이를 스냅샷 `configWarning`으로 운영 화면에 보인다(ADR-006 2.3, 이미 구현된 표시). 경고 문구에는 경로와 주체 이름만 쓰고 값은 쓰지 않는다.
3. (선택, 같은 PR 또는 뒤) Windows에서 실험실이 비밀 파일을 저장할 때 ACL을 본인·SYSTEM·Administrators로 좁혀 쓴다. 기존 사용자 PC의 백업 도구가 Administrators 권한으로 읽는 경우를 깨지 않게 Administrators는 남긴다 `[추론]`. 검사(1번)로 충분하면 생략한다.

**운영 절차(7단계 절차서 [stage-7-real-trading-tests.md](../runbooks/stage-7-real-trading-tests.md), 묶음 I)**

| 확인 | 방법 | 위협 |
|---|---|---|
| 설정 폴더 권한 | `icacls "%USERPROFILE%\.autotrade"`로 본인·SYSTEM·Administrators만 있는지 확인. 넓으면 `icacls <폴더> /inheritancelevel:r /grant:r "<사용자>:(OI)(CI)F"` 후 SYSTEM·Administrators를 다시 넣음 `[사실: icacls 문서 /inheritancelevel·/grant:r·(OI)(CI) 설명]` | T1 |
| 설정 폴더 위치 | 동기화·백업 폴더 밖인지 확인. PC 백업 도구가 사용자 홈 전체를 올리면 `.autotrade`를 제외 목록에 넣음 | T3 |
| 디스크 암호화 | Windows 장치 암호화 또는 BitLocker 켜짐 확인 `[가정: 사용자 PC 판(Home/Pro)에 따라 다름]` | T4 |
| 운영 계정 | 이 PC에 다른 사람이 쓰는 Windows 계정이 있으면 그 계정은 표준 사용자로 둠. 실전 운영 계정에서 메일 첨부·브라우저 확장 설치를 줄임 `[추론]` | T1·T2 |
| 유출 대응 | 증권사 앱키 재발급·폐기, 운영자 비밀번호 해시 다시 만들기(`autotrade-engine hash-password`), 카카오·Slack 토큰 폐기. 비밀값 변경은 엔진이 `real-config-changed`로 알림(D-142) | T2~T5 |
| 지원 요청 | 로그·설정을 남에게 보낼 때 `secrets.json`·`collector-secrets.json`·`notification-state/`를 빼고 보냄 | T5 |

**T2에 대해 남는 위험**: 어떤 저장 방식도 같은 계정의 악성 프로세스를 막지 못한다. 줄이는 수단은 저장소가 아니라 (1) 실전 금액을 소액으로 시작(STR-05, 자산 10% 이하), (2) 실전 설정 변경 알림(D-142), (3) 증권사가 제공하면 앱키의 접속 IP 제한 같은 서버 측 제한 `[가정: 증권사별 제공 여부 미확인, 확인 방법: 한국투자 KIS Developers 앱 관리 화면·공지]`, (4) 운영 계정 위생이다.

## 6. 되돌릴 조건

다음 중 하나면 DPAPI(대안 B) 또는 Credential Manager(대안 C)를 다시 검토한다.

1. 설정 폴더를 동기화·백업 대상에서 뺄 수 없는 운영 환경이 된다(T3을 위치로 막을 수 없음).
2. 권한 검사 경고(5장 1번)가 사용자 PC에서 반복되고 ACL로 고칠 수 없다.
3. 실전 엔진만 비밀값을 읽도록 구조가 바뀐다(ADR-006 3장 E3 되돌릴 조건 "실험실이 secrets.json을 쓰지 않게 되는 설계"). 그때는 엔진 한 곳만 바꾸면 되어 부담이 줄어든다.
4. Java 런타임을 FFM API 정식판이 있는 판으로 올리는 결정이 따로 나서(D-002 변경, 사용자 결정), 외부 jar·프로세스 실행 없이 OS API를 부를 수 있다.
5. 비밀값 유출 사고가 난다.
6. macOS에서 실전을 운영하게 된다(대안 D를 그때 본다).

## 7. 코드 변경 배치 제안

- **실전 관련 PR**이다: `autotrade-config/*`(검사 로직, `ConfigLoader` 경고), `autotrade-app-collector/.../CollectorSecrets.java`(수집 명령 경고), 필요하면 `autotrade-notification-jar/*`(카카오 토큰 파일)가 모두 `scripts/ci/protected-paths.txt`의 보호 경로다. risk-ops·architect 검토, 독립 QA, 사용자 머지(지침 10·11장).
- **묶음**: 7단계 준비 계획 3장의 기존 묶음과 파일이 겹치지 않는 **독립 소형 PR(가칭 K10-a)** 로 하기를 제안한다. 엔진 표시는 이미 있는 `LoadedConfig.warning` → `configWarning` 경로를 쓰므로 `EngineMain`·명령 채널(묶음 A·E)을 고치지 않는다. 묶음 B·C·D는 다른 모듈이다. 시작은 지금 가능하고, **실전 활성화 결정 전에 머지**되어야 한다. 절차서 문구(5장 운영 절차)는 묶음 I에서 넣는다.
- 시험(완료 조건): POSIX 600/700보다 넓은 권한·이미 있는 넓은 폴더에서 경고, 경고 문구에 비밀값이 없음, 경고가 나도 파일을 버리지 않고 실전 계좌·해시가 그대로 읽힘, Windows ACL 보기 미지원 시 "확인 불가" 경고, 동기화 폴더 경로 경고. Windows ACL 판정은 CI(리눅스)에서 돌지 않으므로 ACL 목록을 입력으로 받는 판정 함수를 분리해 단위 시험하고, 실제 Windows 확인은 절차서 시행 때 한다 `[추론]`.
- 이 변경은 RealTradingGuard·주문 경로·블랙리스트·리스크 한도·장애 대응을 건드리지 않고, 기동·활성화를 막는 새 조건을 만들지 않는다.

## 8. 사용자 결정 필요 여부 (지침 8장)

| 조건 | 해당 | 이유 |
|---|---|---|
| 1. 과금 | 아니오 | JDK만 씀 |
| 2. 실전 영향 | 아니오 | 실전 주문 경로·Guard·블랙리스트·한도·장애 대응 규칙 무변경, 차단 조건 추가 없음(경고만). 코드 PR 자체는 보호 경로라 사용자 머지 |
| 3. 되돌리기 어려운 일 | 아니오 | 저장 형식 그대로 |
| 4. 확정 결정과 충돌 | 아니오 | ADR-001 대안 D는 "재검토"로 남긴 항목이고 결정 번호가 없다. D-086·D-002를 건드리지 않는 쪽을 골랐다 |
| 5. 목표·범위 변경 | 아니오 | — |
| 6. 특이 사항 | 아니오 | — |

→ 추천안 채택(자율). 사용자가 DPAPI 암호화를 원하면 선택지는 4장 B(외부 네이티브 라이브러리를 실전 경로에 허용, 보호 테스트 고정 값 변경) 또는 6장 4번(Java 판 올림)이며 둘 다 그때 사용자 결정이다.

## 9. 검증과 한계

- [사실] 2장은 main `53482a56` 코드를 직접 읽은 결과다(파일·행 표시).
- 검증하지 못한 것: Windows 기본 프로필 ACL(`[가정]`), Windows에서 JDK `acl` 보기 지원(`[가정]`), JNA의 리플렉션 사용(`[가정]`), Java FFM 정식판 시기(`[가정]`, JEP 원문 읽기 실패), 증권사 앱키 IP 제한 제공 여부(`[가정]`). 각 확인 방법은 본문에 적었다.
- 이 문서는 코드를 바꾸지 않았고 시험을 돌리지 않았다.

## 출처

- Microsoft, `CryptProtectData` function (dpapi.h), Remarks·`pOptionalEntropy`: https://learn.microsoft.com/pl-pl/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata (영문 본문, 2026-10-26 확인)
- Microsoft, `CREDENTIALW` structure (wincred.h), `CredentialBlobSize`·`Persist`·`CRED_TYPE_GENERIC`: https://learn.microsoft.com/en-us/windows/win32/api/wincred/ns-wincred-credentialw (2026-10-26 확인)
- Microsoft, Redirect and move Windows known folders to OneDrive: https://learn.microsoft.com/sharepoint/redirect-known-folders (이동 대상 Desktop·Documents·Pictures·Screenshots·Camera Roll, 2026-10-26 확인)
- Microsoft, icacls: https://learn.microsoft.com/windows-server/administration/windows-commands/icacls (2026-10-26 확인)
- Apple, Restricting keychain item accessibility: https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility (2026-10-26 확인)
- Oracle, `AclFileAttributeView`(Java SE 8 API, 21에서 같은 인터페이스 `[가정]`): https://docs.oracle.com/javase/8/docs/api/java/nio/file/attribute/AclFileAttributeView.html (2026-10-26 확인)
- 읽기 실패: OpenJDK JEP 454 https://openjdk.org/jeps/454 (이 환경에서 응답 오류)
- 내부: [ADR-001](../adr/ADR-001-module-structure.md) 2.6·4장, [ADR-006](../adr/ADR-006-real-control-channel.md) 2.3·2.4·3장, [D-086](../decisions/D-086-real-path-reflection-ban.md), [D-142](../decisions/D-142-control-channel-hardening.md), [설정 키](../config/config-keys.md), [7단계 준비 계획](../team/stage-7-readiness-plan.md)
