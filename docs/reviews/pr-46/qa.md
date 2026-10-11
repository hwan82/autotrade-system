# QA 검토: Q-041 결정(D-058) 단발 일괄 명령을 자원 부족 정지 순서 0번에, 수집기는 감시의 정지 요청 파일로 멈춤
- 대상: PR #46 / 커밋 9d3ec3165c0f02edd4e352b88bfee8690a949d33 / main(e8cc529) 대비 diff(merge-base 51eb6ef, 15개 파일 +376 −21, 그중 검토 원문 2개)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (PR 헤드 9d3ec31): **BUILD SUCCESSFUL**(8회째 시도. 1~7회는 Maven Central 429로 의존성 받기 실패, 빌드 판정 아님). 테스트 결과 XML 집계 805개, 실패 0, 건너뜀 0. 이 PR 관련: `RunGuardTest` 8, `CollectorMainTest` 15, `ArchitectureTest` 17, `ModuleOwnershipTest` 13 모두 통과. 새 테스트 `감시의_정지_요청이_있으면_장_시간_해제_옵션을_줘도_시작하지_않고_파일을_지우지_않는다`, `실행_중_정지_요청이_생기면_다음_호출_전에_멈추고_파일을_지우지_않는다`, `정지_요청_파일은_해제_옵션과_상관없이_막고_지우지_않는다`, `정지_요청이_있는지_판단할_수_없으면_멈춘다`, `감시는_프로세스를_죽이지_않는다` 통과.
  - `git merge-tree --write-tree origin/main origin/pr46`: **충돌** `CONFLICT (content): Merge conflict in docs/spec/autotrade-spec.md`. main의 PR #38(D-056)이 기획서를 v14.12로 올리고 변경 이력 맨 위에 v14.12 줄을 넣었고, 이 PR은 같은 위치에 v14.13 버전 칸과 v14.13 줄을 넣음. `docs/decisions/README.md`는 자동 병합됨.
  - 호출·종료 경로 검색: `grep -rn "ProcessBuilder|Runtime.getRuntime|.exec(|destroy"` 운영 코드 → 엔진·게이트웨이 shutdown hook과 실험실 `@Bean(destroyMethod)`뿐. 감시(`WatchdogMain`)와 `com.autotrade.monitor`(현재 `package-info.java`만)에 프로세스 종료 코드 없음.
  - `stop-request` 검색: 운영 코드에서 읽는 곳은 수집기 `RunGuard`·`CollectorMain.Options.stopRequest()`뿐. 만드는 곳(감시)과 선정 일괄 명령 쪽 코드는 아직 없음(D-058 ④ 후속 과제로 문서에 적힘).
  - 보호 테스트 변경 확인: `ArchitectureTest`는 규칙 `WATCHDOG_KILLS_NO_PROCESS`와 테스트 1개 **추가만** 있고 기존 규칙·테스트의 삭제·완화 없음(diff 확인). `autotrade-risk`(RealTradingGuard·GuardedOrderGateway) 파일 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`, `autotrade-config`, Guard 테스트 없음. 수집기는 Guard 상태·`runtime-config.json`·`secrets.json`을 읽지 않음(기존 ArchUnit 수집기 규칙 그대로, 통과) |
| A2 | 통과 | 주문 코드 변경 없음. `ArchitectureTest`의 Guard 팩토리·출구 규칙 변경 없음(추가만, 위 확인) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음. 수집기는 `com.autotrade.broker..`·`com.autotrade.domain.order..` 의존 금지 규칙 그대로 |
| A4 | 해당 없음 | 주문 재전송 경로 없음. 수집기 `ApiCaller` 재시도(GET, 429·5xx만)는 변경 없음 |
| A5 | 해당 없음 | 주문 생성 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 통과 | 계좌 직렬 큐·토큰 버킷 변경 없음. 수집기 호출 예산(`CallBudget`)도 변경 없음 |
| A8 | 통과 | 실전 엔진 PC의 새 부하원(수집기)에 멈춤 수단이 하나 더 생김(`RunGuard.java:64-70`, 시작 전 `CollectorMain.java:75`, 호출 전 `ApiCaller` `beforeCall`, 스냅샷 쓰기 하루마다 `guarded`, 품질 리포트 하루마다 `checkSnapshot(dir, guard::checkSession)`). 정지 요청은 `--allow-during-session`보다 먼저 판단(`RunGuard.java:65`, 테스트로 확인). 감시가 어떤 프로세스도 죽이지 않는 규칙은 PID 재사용으로 실전 엔진이 죽는 경로를 막음. 단, 감시 쪽 파일 생성 코드가 없어 0번은 지금 실제로 동작하지 않음(문서에 후속 과제로 명시, 기존 시간·힙·디스크 기준은 그대로) |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(`grep -iE "appkey|secret|token|계좌번호 형식"` 추가 줄 0건, 검토 원문 인용 제외). 거부 메시지는 경로와 파일 시각만 담음 |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. `ArchitectureTest`는 규칙 추가와 위반 고정 예제(`KillsBatchProcess`: `ProcessHandle::destroyForcibly` 메서드 참조, `Process.destroy()` 호출)로 규칙이 실제로 위반을 잡는지 단언. 새 동작마다 실패 경우 테스트 있음(시작 거부, 실행 중 멈춤 호출 1건, 파일 유지, 확인 실패 → 멈춤, 파일 제거 뒤 재개) |
| A12 | 통과 | 장애표 #10(`broker-and-fault-design.md:169`) 감지·실전 대응·실험실 대응 칸과 5.2 0번이 함께 바뀜. 감지 칸은 감시 생존을 보는 주체가 없다는 사실대로 적힘. 장애 테스트 8종의 시나리오·합격 기준은 바뀌지 않고, #2(실험실 CPU 부하)의 전제도 그대로 |
| A13 | 통과 | 미국 경로 변경 없음. 정지 요청 판단은 시장과 무관하고, 장 시간 판단은 기존대로 시장별 캘린더 현지 시간대(`RunGuard`) |
| A14 | **실패** | 결정·문서 내용은 코드와 일치함(D-058 ②의 종료 코드 4·해제 옵션 무시·파일 미삭제 = `RunGuard.java:64-70`·`CollectorMain.java:79,95`·테스트, ADR-008 2.7, runbook 3·4장, 기획서 RSK-07). 그러나 **PR 헤드가 main과 충돌**한다: `docs/spec/autotrade-spec.md` 버전 칸(main v14.12 / PR v14.13)과 변경 이력 맨 위 줄. 이대로는 머지할 수 없고, 충돌 해결 뒤 기획서 버전·이력 순서(v14.12 D-056 다음 v14.13 D-058)를 다시 확인해야 함 |
| B1 | 통과 | `./gradlew build` 직접 실행 BUILD SUCCESSFUL(위 기록, 테스트 805개 실패 0) |
| B6 | 통과 | 새 동작에 테스트가 있고(A11), 건너뛰거나 지운 테스트 없음(건너뜀 0, 기존 테스트는 생성자 인자 추가만) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | 이 PR은 새 외부 호출을 추가하지 않음. 수집기의 기존 호출 지점은 KRX Open API(`KrxOpenApiClient`)·OpenDART(`OpenDartClient`), 둘 다 `ApiCaller.get` 한 곳을 거침. KRX Open API는 약관에 요금 조항 없음(하루 10,000회 초과 시 서비스 중지), OpenDART는 약관 제11조①로 원칙적 무료(이전 QA #39·#43에서 확인). 유료 호출 지점 0건 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음. 이 PR은 호출을 줄이는 쪽(정지 요청 시 호출 전 멈춤)만 더함 |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 없음. 정지 요청은 해제 옵션으로 우회되지 않음(테스트) |
| 8.4 폭주 방지 | 통과 | 정지 요청 확인은 키 읽기·호출보다 먼저(`CollectorMain.java:75` 시작 확인 → 83 키 읽기). 예약 작업이 요청이 남은 동안 매일 다시 돌아도 외부 호출 0건(`감시의_정지_요청이…` 테스트 `http.calls` 비어 있음 단언). `ApiCaller` 재시도는 시도마다 `beforeCall` → `budget.acquire` 순서라 요청이 생기면 그 뒤 호출 0건(`실행_중_정지_요청이…` 호출 1건 단언). 호출 상한(`CallBudget` 9,000·10,000)과 재시도 백오프는 기존 그대로 |
| 8.5 실주문 비용 | 통과 | 수집기는 주문 코드·증권사 모듈 의존이 없음(ArchUnit 규칙 그대로). 이 PR로 실주문이 나갈 경로 없음 |

## 연결 PR 조건 확인
이 PR은 증권사 조회 호출기(#40 키움, #41 토스, #42 한국투자)를 어느 프로세스에도 연결하지 않고, 선정 일괄 명령(#37)을 구현하지 않는다. 그래서 #42 D-054 R1·R2, #41 N1·N2·N4·N5, #40 R1·R2, #37 G1은 이 PR의 판정 대상이 아니다(해당 없음). 선정 일괄 명령의 정지 요청 경로(`<results-dir>/_status/<MARKET>/stop-request`)는 ADR-007 2.7·D-058에 적혀 있고 구현 PR에서 #37 G1과 함께 확인한다.

## 점검표 밖 발견 (장애·순서 시나리오)
1. **감시가 죽은 채 정지 요청 파일이 남는 경우** [추론]: 수집기는 매일 종료 코드 4로 끝나고 스냅샷이 오래된다. 실전 쪽은 STR-19 입력 신선도 판정(D-044)이 그 계좌 신규 진입을 막는 닫힌 쪽이라 잘못된 주문·중복 주문은 생기지 않는다. 다만 지금 감지는 사용자가 예약 작업 결과를 보는 것뿐이고(장애표 #10 감지 칸에 사실대로 적힘), 감시 생존 감지는 D-058 ④ 후속 과제다. 판정 무관, 감시 구현 PR에서 확인할 항목으로 기록.
2. **수집기와 감시의 설정 폴더가 다른 경우**: 수집기는 감시의 요청을 영영 보지 못하고, 시간·힙·디스크 기준으로만 물러난다. 이 PR은 시작 때 확인 경로를 출력하고(`CollectorMain.java:73`) runbook 3장에 같은 설정 폴더로 실행하라고 적었다. 코드로 강제하지는 않음. 감시 구현 PR에서 감시가 요청을 만든 폴더를 알림·로그로 남기면 사람이 대조할 수 있다(권고 Q1).
3. **정지 요청이 API 재시도 중이나 스냅샷 쓰기 중에 생기는 경우**: 재시도마다 호출 전에 확인하고(`ApiCaller.get`의 `beforeCall.run()`이 `budget.acquire()`·`http.get` 앞), `Refused`는 `RuntimeException`이라 `ApiCaller`의 `IOException` 처리에 삼켜지지 않고 `CollectorMain.run`의 `catch (RunGuard.Refused)`로 가서 종료 코드 4. 스냅샷 쓰기 중이면 `SnapshotWriter`가 닫히며 임시 폴더를 지운다(기존 장 시간 경로와 같은 코드, 테스트 `스냅샷을_쓰는_중_보호_구간에_들어서면_멈추고_임시_폴더를_남기지_않는다`). 품질 리포트 단계에서 멈추면 **스냅샷은 커밋된 채 리포트만 빠진다**(ADR-008 2.7에 이제 사실대로 적힘). 이 동작은 main에 이미 있던 것(장 시간 보호 구간)이고, 이 PR로 장 밖에서도 일어날 수 있게 됐다. 리포트 없는 스냅샷을 실전용 선정 입력으로 쓰지 않게 하는 확인은 7단계 STR-19·MD-09 구현에서 필요(권고 Q2).
4. **정지 요청 파일 확인 실패**(권한 오류, 부모가 파일): `!Files.notExists`로 확인 못 함을 멈춤 쪽으로 판정(`RunGuard.java:65`), 테스트 `정지_요청이_있는지_판단할_수_없으면_멈춘다` 통과(Linux). Windows에서 이 테스트의 전제(부모가 일반 파일이면 notExists=false)가 같은지는 확인 못 함(risk-ops N5와 같음, 운영 코드 판단은 맞음, 판정 무관).
5. **ArchUnit 규칙의 범위** [추론]: `WATCHDOG_KILLS_NO_PROCESS`는 `Process`·`ProcessHandle`의 `destroy*` 호출·참조만 막는다. 감시가 `ProcessBuilder`·`Runtime.exec`로 `taskkill`/`kill`을 실행하는 경로나, 감시가 의존하는 `autotrade-config`·`autotrade-notification-jar` 안의 종료 코드는 잡지 않는다. 지금 감시에는 해당 코드가 없어 현재 결함은 아니다. 감시는 장애표 #8 자동 재기동 때문에 `ProcessBuilder`를 써야 하므로 전면 금지는 어렵고, 감시 구현 PR에서 프로세스 실행을 한 클래스로 모으고 그 클래스만 허용하는 규칙 + 실행 명령 허용 목록 테스트를 두기를 권한다(권고 Q3).

## 권고 (판정 무관)
- Q1 감시 구현 PR: 정지 요청을 만든 경로를 감시 로그·알림에 남겨 수집기 출력(`감시 정지 요청 확인 위치:`)과 대조할 수 있게 한다.
- Q2 7단계 STR-19·MD-09 구현: 품질 리포트가 없는 일봉 스냅샷은 실전용 선정 입력으로 쓰지 않는다(정지 요청·장 시간으로 리포트 단계가 끊긴 경우).
- Q3 감시 구현 PR: 프로세스 실행(`ProcessBuilder`·`Runtime.exec`)을 한 클래스로 모으고 ArchUnit으로 그 밖의 사용을 막으며, 실행 명령 허용 목록에 `kill`·`taskkill` 등이 없음을 테스트한다.

## 판정
수정 필요(A14: PR 헤드가 main과 `docs/spec/autotrade-spec.md`에서 충돌. main을 병합해 버전 칸 v14.13과 변경 이력 순서(v14.13 D-058 줄을 v14.12 D-056 줄 위)를 정리한 뒤 재검토. 그 밖의 A·B1·B6·과금 항목은 통과 또는 해당 없음)
