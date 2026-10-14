# architect 검토: PR #87 QA 권고 후속(디스크 하한 1GB 미만 거부, 날짜 넘긴 비정상 종료 시험, ADR-010 채택)
- 대상: PR #91 / 브랜치 `claude/project-thread-go4oua` / 커밋 `0e1ba9d` / `git diff origin/main...HEAD` 전체(8파일, +53 −9, 커밋 1개)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: 처음 두 번은 Maven Central 429(Too Many Requests)로 의존성 해석 실패(코드와 무관, `junit-jupiter-engine`·`archunit-junit5` pom HEAD 실패). `--offline`은 캐시 부족으로 실패. 세 번째 온라인 실행 **BUILD SUCCESSFUL**(130 tasks).
  - 테스트 결과 집계(모든 모듈 `build/test-results`): 1,058개, 건너뜀 0, 실패 0, 오류 0.
  - 새 시험 실행 확인: `LiveLabServiceTest` 6개(새 `diskFloorMustBeAtLeastOneGb` 포함, 같은 HEAD로 두 번째 실행 때 돌았고 세 번째 실행은 입력이 같아 UP-TO-DATE), `LiveSessionTest` 10개(새 `crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame`, 고친 `crashWithLostTailAndUnclosedMetaStillReplaysTheSame` 포함) 모두 통과. `autotrade-architecture-test:test` 이번 실행에서 수행·통과.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행. 세 번째 `./gradlew build` 성공, 1,058 시험 실패 0·건너뜀 0 |
| B2 | 통과 | 운영 코드 변경은 `autotrade-app-lab`의 `LiveLabService.java` 한 곳(생성자 검사, 상수 1개). 새 import·모듈 의존 없음. `autotrade-sim` 쪽은 시험 코드만. ArchUnit(`autotrade-architecture-test:test`) 통과, 규칙 변경 없음 |
| B3 | 통과 | 설정 키 `autotrade.lab.live.min-free-gb` 허용 범위가 "0 이상"에서 "1~1,000,000"으로 좁아짐(`LiveLabService.java:108-112`). 기본값 20은 그대로. `docs/config/config-keys.md:43`과 절차서 `docs/runbooks/stage-6-live-session.md:45`가 같은 범위·동작(범위 밖이면 실험실 기동 실패)으로 갱신됨. ADR-010은 범위를 정하지 않았으므로(`ADR-010:72`는 기본 20GB와 동작만) 새 ADR 불필요. ADR-010 상태를 "채택(D-076, PR #87 머지 `40388a4`)"으로 바꾼 것은 ADR-009 "채택(D-072)"과 같은 방식이고 `docs/adr/README.md:16` 표와 일치. REST·스키마·인터페이스 변경 없음 |
| B4 | 통과 | 운영 시뮬레이션 코드 변경 없음. 새 시험은 고정 씨앗(`Random(7)`) 이틀치 시세와 주입 시계(`Mutable`)를 쓰고 리플레이 확인(`LiveReplayCheck.run`)의 `same()`으로 판정. 기존 골든·리플레이 시험 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음. 바이트 환산 `minFreeGb * 1024³`은 상한 1,000,000GB에서 약 1.07×10^15로 `long` 범위 안(넘침 방지가 상한의 목적, `LiveLabService.java:108`) |
| B6 | 통과 | 새 동작(하한 범위 검사)에 `LiveLabServiceTest#diskFloorMustBeAtLeastOneGb`(0, −1, 상한+1 거부와 메시지의 키 이름 확인). R5 시나리오에 `LiveSessionTest#crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame`(1일차 중간 비정상 종료 → 2일차부터 재개, 1일차 끝 비정상 종료 → 2일차부터 재개). 기존 시험은 공통 도우미 `crashAndResume`으로 옮겼을 뿐 같은 경우(`crashAt`, `crashAt`)를 그대로 검사하고, 재개 뒤 `accept`가 `ACCEPTED`인지 확인이 더해져 오히려 강해짐. 지우거나 건너뛴 시험 없음 |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·보호 테스트·게이트웨이 코드는 바뀌지 않음. 다만 바뀐 값은 실전 엔진 기록과 같은 디스크를 보호하는 장애 대응 장치(디스크 여유 하한, `LiveLabService` javadoc의 장애표 #8·#10, risk-ops PR #87 R1)이고 이 PR은 그것을 **강화**하는 방향이다. 이 검토 시점에 `docs/reviews/pr-91/risk-ops.md`는 아직 없어 그 판정은 확인하지 못했다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 예외 메시지는 설정 키 이름과 입력 숫자만 담음 |

## 점검표 밖 발견
1. (참고) 범위 밖 값이면 빈 생성 단계에서 예외가 나 실험실 전체가 뜨지 않는다(`LabWebConfiguration.java:101-112`). "없거나 깨진 설정 파일은 빼고 기동" 원칙과는 다른 방향이지만, 그 원칙은 파일 단위이고 값이 잘못된 경우 조용히 기본값으로 바꾸는 것보다 기동 실패가 디스크 보호에는 안전하다. 실전 엔진 프로세스에는 영향이 없다. 문서(`config-keys.md:43`, 절차서 2-5)에 동작이 적혀 있으므로 받아들인다. 수집기 `--min-free-gb`도 1 미만을 거부하므로(`CollectorMain.java:361-365`) 두 경로 기준이 같아졌다.
2. (권고, 머지 막지 않음) 새 시험 주석은 "1일차 파일은 다시 열리지 않음"이라고 하지만 이를 직접 단정하지는 않는다. 재개 뒤 잘린 1일차 `events.jsonl.gz`의 바이트가 그대로인지 확인하는 단정을 더하면 R5 설명과 시험이 정확히 맞는다. 지금도 리플레이 일치(`out.same()`)는 확인하므로 결과 동등성은 검증된다.
3. (참고) 한 시험 안에서 `crashAndResume`을 두 번 부르므로 `experimentDir`의 `live-detail.txt`를 두 번째 호출이 덮어쓴다. 각 호출이 새 임시 폴더(`Files.createTempDirectory`)와 원래 세션 폴더 삭제(`deleteTree`)로 서로 독립이 되게 했고, 리플레이 확인도 호출마다 끝나므로 문제없다.
4. QA PR #87 권고 처리 대조: R2(1 미만 거부)·R4(ADR-010 채택)·R5(시험 추가)는 이 PR의 코드·문서로 확인. R6는 절차서 3장에 기록 항목 추가로 처리(`stage-6-live-session.md:52`). `docs/qa-out-status.md` 기록은 이 처리 범위와 일치.

## 판정
승인. B 점검표에 실패와 확인 못 함이 없다. 이 승인은 architect 역할 검토에 한한다. B7: 실전 엔진과 같은 디스크를 보호하는 장애 대응 장치를 강화하는 변경이므로 risk-ops 판정(`docs/reviews/pr-91/risk-ops.md`)을 따로 확인해야 하고, 그 판정에 따라 지침 10장의 머지 권한(Claude 머지 또는 사용자 직접 머지)을 정한다.
